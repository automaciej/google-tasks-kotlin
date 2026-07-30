package pl.blizinski.googletasksstore

import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import pl.blizinski.googletasksstore.internal.GoogleSyncErrorClassifierWasm
import pl.blizinski.googletasksstore.internal.GoogleTask
import pl.blizinski.googletasksstore.internal.GoogleTaskList
import pl.blizinski.googletasksstore.internal.network.GoogleTasksNetworkSourceWasm
import pl.blizinski.googletasksstore.internal.toPublic
import pl.blizinski.googletasksstore.internal.toTask
import pl.blizinski.googletasksstore.internal.toTaskList
import pl.blizinski.googletasksstore.models.SyncStatus
import pl.blizinski.googletasksstore.models.Task
import pl.blizinski.googletasksstore.models.TaskList
import pl.blizinski.tasksync.InMemoryLocalStore
import pl.blizinski.tasksync.OpType
import pl.blizinski.tasksync.PendingOp
import pl.blizinski.tasksync.PendingOpsProcessor
import pl.blizinski.tasksync.SyncEngine
import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.accumulateRecentErrors

/**
 * wasmJs [TaskStoreApi] implementation for the Google Tasks web PoC — see TaskCompass's
 * Docs/designs/2026-07-30-web-wasmjs-google-tasks-poc.md.
 *
 * Structurally mirrors the Android target's `GoogleTasksStore`: wires the *same*
 * [SyncEngine]/[PendingOpsProcessor] Android uses into an [InMemoryLocalStore] (no Room, no
 * persistence across page reloads) and [GoogleTasksNetworkSourceWasm] (Ktor, no
 * `google-api-client-android`). No [pl.blizinski.tasksync.AdaptivePoller]/WorkManager — this PoC
 * syncs only on demand ([forceSync]/[fullSync]), same UX as the GitHub Issues PoC.
 */
@OptIn(ExperimentalTime::class, ExperimentalUuidApi::class)
class GoogleTasksStoreWasm(
    tokenProvider: GoogleAccessTokenProvider,
    private val config: GoogleTasksStoreConfig = GoogleTasksStoreConfig(),
) : TaskStoreApi {

    private val json = Json { ignoreUnknownKeys = true }
    private val store = InMemoryLocalStore<GoogleTask, GoogleTaskList>()
    private val network = GoogleTasksNetworkSourceWasm(tokenProvider)
    private val errorClassifier = GoogleSyncErrorClassifierWasm()
    private val pendingOpsProcessor = PendingOpsProcessor(store, network, serializer<GoogleTask>(), errorClassifier)
    private val syncEngine = SyncEngine(store, network, pendingOpsProcessor, errorClassifier)

    private val _syncStatus = MutableStateFlow(SyncStatus())

    private fun applySyncResult(result: SyncEngine.SyncResult) {
        val now = Clock.System.now().toEpochMilliseconds()
        _syncStatus.update { current ->
            current.copy(
                isSyncing = false,
                lastSyncedAt = now,
                recentErrors = accumulateRecentErrors(
                    previous = current.recentErrors,
                    new = result.errors.map { it.toPublic() },
                    max = config.maxRecentErrors,
                ),
            )
        }
    }

    // -----------------------------------------------------------------------
    // Public read API
    // -----------------------------------------------------------------------

    override fun taskLists(): Flow<List<TaskList>> =
        store.lists().map { lists -> lists.map { it.toTaskList() } }

    override fun tasks(listLocalId: String): Flow<List<Task>> =
        store.records(listLocalId).map { records -> records.map { it.toTask() } }

    override fun syncStatus(): Flow<SyncStatus> = combine(
        _syncStatus,
        store.pendingOpCount(),
        store.failedOpCount(),
    ) { status, pending, failed -> status.copy(pendingOpCount = pending, failedOpCount = failed) }

    // -----------------------------------------------------------------------
    // Public write API — optimistic local write + pending op
    // -----------------------------------------------------------------------

    private suspend fun <T> guardWrite(block: suspend () -> T): T = syncEngine.writeMutex.withLock { block() }

    override suspend fun createList(title: String): String = guardWrite {
        val localId = Uuid.random().toString()
        val now = Clock.System.now().toEpochMilliseconds()
        store.upsertList(
            SyncedListRecord(localId = localId, remoteId = null, content = GoogleTaskList(title = title), lastSyncedAt = null, position = Int.MAX_VALUE)
        )
        store.enqueuePendingOp(PendingOp(id = Uuid.random().toString(), type = OpType.CREATE_LIST, entityLocalId = localId, listLocalId = localId, createdAt = now))
        localId
    }

    override suspend fun updateList(localId: String, title: String): Unit = guardWrite {
        val entity = store.getListByLocalId(localId) ?: return@guardWrite
        val now = Clock.System.now().toEpochMilliseconds()
        store.upsertList(entity.copy(content = GoogleTaskList(title = title)))
        store.enqueuePendingOp(PendingOp(id = Uuid.random().toString(), type = OpType.UPDATE_LIST, entityLocalId = localId, listLocalId = localId, createdAt = now))
    }

    override suspend fun deleteList(localId: String): Unit = guardWrite {
        val entity = store.getListByLocalId(localId) ?: return@guardWrite
        val now = Clock.System.now().toEpochMilliseconds()
        for (task in store.getAllRecordsForList(localId)) {
            store.removeAllPendingOpsForEntity(task.localId)
            if (task.remoteId == null) store.hardDeleteRecord(task.localId)
        }
        store.upsertList(entity.copy(isDeleted = true))
        if (entity.remoteId != null) {
            store.enqueuePendingOp(PendingOp(id = Uuid.random().toString(), type = OpType.DELETE_LIST, entityLocalId = localId, listLocalId = localId, contentJson = entity.remoteId, createdAt = now))
        } else {
            store.hardDeleteList(localId)
        }
    }

    override suspend fun createTask(listLocalId: String, title: String, notes: String?, dueDate: Long?): String = guardWrite {
        val localId = Uuid.random().toString()
        val now = Clock.System.now().toEpochMilliseconds()
        val content = GoogleTask(title = title, notes = notes, createdDate = now, dueDate = dueDate)
        store.upsertRecord(SyncedRecord(localId = localId, remoteId = null, listLocalId = listLocalId, content = content, isCompleted = false, lastSyncedAt = null))
        store.enqueuePendingOp(
            PendingOp(id = Uuid.random().toString(), type = OpType.CREATE_RECORD, entityLocalId = localId, listLocalId = listLocalId, contentJson = json.encodeToString(serializer(),content), createdAt = now)
        )
        localId
    }

    override suspend fun updateTask(localId: String, title: String, notes: String?, dueDate: Long?): Unit = guardWrite {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = Clock.System.now().toEpochMilliseconds()
        val newContent = entity.content.copy(title = title, notes = notes, dueDate = dueDate)
        store.upsertRecord(entity.copy(content = newContent))
        store.enqueuePendingOp(
            PendingOp(id = Uuid.random().toString(), type = OpType.UPDATE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, contentJson = json.encodeToString(serializer(),newContent), createdAt = now)
        )
    }

    override suspend fun completeTask(localId: String): Unit = guardWrite {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = Clock.System.now().toEpochMilliseconds()
        store.upsertRecord(entity.copy(isCompleted = true, content = entity.content.copy(completedDate = now)))
        store.enqueuePendingOp(PendingOp(id = Uuid.random().toString(), type = OpType.COMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now))
    }

    override suspend fun uncompleteTask(localId: String): Unit = guardWrite {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = Clock.System.now().toEpochMilliseconds()
        store.upsertRecord(entity.copy(isCompleted = false, content = entity.content.copy(completedDate = null)))
        store.enqueuePendingOp(PendingOp(id = Uuid.random().toString(), type = OpType.UNCOMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now))
    }

    override suspend fun deleteTask(localId: String): Unit = guardWrite {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = Clock.System.now().toEpochMilliseconds()
        store.softDeleteRecord(localId)
        store.enqueuePendingOp(PendingOp(id = Uuid.random().toString(), type = OpType.DELETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now))
    }

    override suspend fun moveTask(localId: String, destListLocalId: String): Unit = guardWrite {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val sourceListLocalId = entity.listLocalId
        if (sourceListLocalId == destListLocalId) return@guardWrite
        val now = Clock.System.now().toEpochMilliseconds()
        store.reassignRecord(localId, destListLocalId)
        store.enqueuePendingOp(
            PendingOp(id = Uuid.random().toString(), type = OpType.MOVE_RECORD, entityLocalId = localId, listLocalId = destListLocalId, contentJson = sourceListLocalId, createdAt = now)
        )
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override suspend fun forceSync() {
        _syncStatus.update { it.copy(isSyncing = true) }
        applySyncResult(syncEngine.sync())
    }

    override suspend fun fullSync() {
        _syncStatus.update { it.copy(isSyncing = true) }
        applySyncResult(syncEngine.fullSync())
    }

    override fun close() {
        // No background work, no database — nothing to release for this PoC.
    }
}
