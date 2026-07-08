package pl.blizinski.googletasksstore

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.work.WorkManager
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.serializer
import pl.blizinski.googletasksstore.internal.GoogleSyncErrorClassifier
import pl.blizinski.googletasksstore.internal.GoogleTask
import pl.blizinski.googletasksstore.internal.GoogleTaskList
import pl.blizinski.googletasksstore.internal.MIGRATION_1_6
import pl.blizinski.googletasksstore.internal.MIGRATION_5_6
import pl.blizinski.googletasksstore.internal.network.GoogleTasksNetworkSource
import pl.blizinski.googletasksstore.internal.toPublic
import pl.blizinski.googletasksstore.internal.toTask
import pl.blizinski.googletasksstore.internal.toTaskList
import pl.blizinski.googletasksstore.models.FatalStorageError
import pl.blizinski.googletasksstore.models.SyncStatus
import pl.blizinski.googletasksstore.models.Task
import pl.blizinski.googletasksstore.models.TaskList
import pl.blizinski.tasksync.AdaptivePoller
import pl.blizinski.tasksync.OpType
import pl.blizinski.tasksync.PendingOp
import pl.blizinski.tasksync.PendingOpsProcessor
import pl.blizinski.tasksync.RoomLocalStore
import pl.blizinski.tasksync.SyncConfig
import pl.blizinski.tasksync.SyncEngine
import pl.blizinski.tasksync.SyncWorkerDependencies
import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.db.TaskSyncDatabase
import java.io.Closeable
import java.util.UUID
import kotlinx.serialization.json.Json

private const val TAG = "GoogleTasksStore"

/**
 * Local-first store for Google Tasks. Reads always come from the Room cache;
 * writes are applied locally and queued for background sync. The library
 * manages all network interaction internally.
 *
 * Typical usage:
 * ```
 * val store = GoogleTasksStore(context, credential)
 * // observe
 * store.taskLists().collect { lists -> ... }
 * // write
 * store.completeTask(localId)
 * // close when done (e.g. in onDestroy)
 * store.close()
 * ```
 *
 * Only one instance per process is supported (WorkManager worker
 * dependency injection uses a process-scoped singleton).
 */
class GoogleTasksStore(
    context: Context,
    credential: GoogleAccountCredential,
    private val config: GoogleTasksStoreConfig = GoogleTasksStoreConfig(),
) : TaskStoreApi, Closeable {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private val db: TaskSyncDatabase = Room.databaseBuilder(
        appContext,
        TaskSyncDatabase::class.java,
        config.dbName,
    )
        // config.dbName is the same filename the old GoogleTasksDatabase (schema version 5)
        // used before this store migrated onto the shared task-sync-kotlin engine
        // (TaskSyncDatabase, version 6). MIGRATION_5_6 repacks the old named-column
        // tasks/task_lists/pending_ops rows into the new synced_records/synced_lists/
        // pending_ops shape, preserving every localId/remoteId — required so that
        // TaskCompass's own comparisons and workspace-list-memberships (which reference
        // these local IDs) keep working across the upgrade. MIGRATION_1_6 additionally covers
        // installs that already hit the incident MIGRATION_5_6's doc comment describes: the
        // destructive-fallback fix briefly shipped for it recreated this database at the wrong
        // version number (1) rather than 5/6, so those installs need a 1 -> 6 path too — a
        // no-op, since the table shapes it left behind already match version 6.
        .addMigrations(MIGRATION_5_6, MIGRATION_1_6)
        .build()

    private val store = RoomLocalStore<GoogleTask, GoogleTaskList>(
        db.recordsDao(),
        db.listsDao(),
        db.pendingOpsDao(),
        serializer(),
        serializer(),
    )

    private val network = GoogleTasksNetworkSource(credential)
    private val errorClassifier = GoogleSyncErrorClassifier()
    private val pendingOpsProcessor = PendingOpsProcessor(store, network, serializer<GoogleTask>(), errorClassifier)
    private val syncEngine = SyncEngine(store, network, pendingOpsProcessor, errorClassifier)

    private val syncConfig = SyncConfig(config.minPollInterval, config.maxPollInterval)
    private val workManager = WorkManager.getInstance(appContext)
    private val poller = AdaptivePoller(workManager, syncConfig)

    private val _syncStatus = MutableStateFlow(SyncStatus())

    init {
        SyncWorkerDependencies.current = SyncWorkerDependencies.Deps(syncEngine, syncConfig)
        poller.start()
    }

    /**
     * Logs and records a fatal, unrecoverable local-storage failure (e.g. an unmigrated Room
     * schema mismatch) instead of letting it propagate and crash the process. Only the first
     * such error is kept — later ones are almost certainly the same root cause retried.
     */
    private fun reportFatalStorageError(e: Throwable) {
        Log.e(TAG, "Local storage unusable", e)
        _syncStatus.update { current ->
            if (current.fatalStorageError != null) current
            else current.copy(
                fatalStorageError = FatalStorageError(
                    occurredAt = System.currentTimeMillis(),
                    summary = e.message ?: e::class.simpleName ?: "Unknown error",
                    details = e.stackTraceToString(),
                )
            )
        }
    }

    /** Catches any exception from a Room-backed flow, reports it, and substitutes [default]
     *  instead of letting the exception propagate up and crash the process. */
    private fun <T> Flow<T>.guardStorage(default: T): Flow<T> = catch { e ->
        reportFatalStorageError(e)
        emit(default)
    }

    // -----------------------------------------------------------------------
    // Public read API
    // -----------------------------------------------------------------------

    override fun taskLists(): Flow<List<TaskList>> =
        store.lists().guardStorage(emptyList()).map { lists -> lists.map { it.toTaskList() } }

    /** [listLocalId] is the [TaskList.id] value returned by [taskLists]. */
    override fun tasks(listLocalId: String): Flow<List<Task>> =
        store.records(listLocalId).guardStorage(emptyList()).map { records -> records.map { it.toTask() } }

    /**
     * Live sync status: [SyncStatus.pendingOpCount] is always current (sourced
     * from Room). [SyncStatus.isSyncing], [SyncStatus.lastSyncedAt], and
     * [SyncStatus.recentErrors] reflect only explicit [forceSync] calls;
     * background WorkManager syncs do not update them in v1.
     */
    override fun syncStatus(): Flow<SyncStatus> = combine(
        _syncStatus,
        store.pendingOpCount().guardStorage(0),
    ) { status, count -> status.copy(pendingOpCount = count) }

    // -----------------------------------------------------------------------
    // Public write API — optimistic local write + pending op + trigger sync
    // -----------------------------------------------------------------------

    /** Runs a write that returns a value, reporting (rather than throwing) a fatal storage
     *  error and returning [onError] instead — every genuine write in this class is a Room
     *  call, so any exception here is that same class of failure, not a bug worth crashing on. */
    private suspend fun <T> guardWrite(onError: T, block: suspend () -> T): T = try {
        block()
    } catch (e: Exception) {
        reportFatalStorageError(e)
        onError
    }

    override suspend fun createList(title: String): String = guardWrite(onError = "") {
        val localId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        store.upsertList(
            SyncedListRecord(
                localId = localId,
                remoteId = null,
                content = GoogleTaskList(title = title),
                lastSyncedAt = null,
                position = Int.MAX_VALUE, // corrected to real position on next sync
            )
        )
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.CREATE_LIST, entityLocalId = localId, listLocalId = localId, createdAt = now)
        )
        poller.onLocalWrite()
        localId
    }

    override suspend fun updateList(localId: String, title: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getListByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        store.upsertList(entity.copy(content = GoogleTaskList(title = title)))
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.UPDATE_LIST, entityLocalId = localId, listLocalId = localId, createdAt = now)
        )
        poller.onLocalWrite()
    }

    override suspend fun deleteList(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getListByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        // Cancel pending ops for all tasks in this list; remove locally-created tasks immediately.
        for (task in store.getAllRecordsForList(localId)) {
            store.removeAllPendingOpsForEntity(task.localId)
            if (task.remoteId == null) store.hardDeleteRecord(task.localId)
        }
        // Soft-delete the list so it disappears from the UI immediately.
        store.upsertList(entity.copy(isDeleted = true))
        if (entity.remoteId != null) {
            // Save remoteId in contentJson — entity may be modified/cleaned before sync runs.
            store.enqueuePendingOp(
                PendingOp(id = UUID.randomUUID().toString(), type = OpType.DELETE_LIST, entityLocalId = localId, listLocalId = localId, contentJson = entity.remoteId, createdAt = now)
            )
            poller.onLocalWrite()
        } else {
            // Never synced — clean up locally without touching the server.
            store.hardDeleteList(localId)
        }
    }

    /**
     * Creates a task locally and returns its stable [localId].
     * The task is synced to the server in the background.
     */
    override suspend fun createTask(listLocalId: String, title: String, notes: String?, dueDate: Long?): String = guardWrite(onError = "") {
        val localId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val content = GoogleTask(title = title, notes = notes, createdDate = now, dueDate = dueDate)
        store.upsertRecord(
            SyncedRecord(localId = localId, remoteId = null, listLocalId = listLocalId, content = content, isCompleted = false, lastSyncedAt = null)
        )
        store.enqueuePendingOp(
            PendingOp(
                id = UUID.randomUUID().toString(), type = OpType.CREATE_RECORD, entityLocalId = localId, listLocalId = listLocalId,
                contentJson = json.encodeToString(serializer(), content), createdAt = now,
            )
        )
        poller.onLocalWrite()
        localId
    }

    /** Updates [title], [notes], and [dueDate]. Pass null to clear that field. */
    override suspend fun updateTask(localId: String, title: String, notes: String?, dueDate: Long?): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        val newContent = entity.content.copy(title = title, notes = notes, dueDate = dueDate)
        store.upsertRecord(entity.copy(content = newContent))
        store.enqueuePendingOp(
            PendingOp(
                id = UUID.randomUUID().toString(), type = OpType.UPDATE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId,
                contentJson = json.encodeToString(serializer(), newContent), createdAt = now,
            )
        )
        poller.onLocalWrite()
    }

    override suspend fun completeTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        store.upsertRecord(entity.copy(isCompleted = true, content = entity.content.copy(completedDate = now)))
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.COMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now)
        )
        poller.onLocalWrite()
    }

    override suspend fun uncompleteTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        store.upsertRecord(entity.copy(isCompleted = false, content = entity.content.copy(completedDate = null)))
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.UNCOMPLETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now)
        )
        poller.onLocalWrite()
    }

    override suspend fun deleteTask(localId: String): Unit = guardWrite(onError = Unit) {
        val entity = store.getRecordByLocalId(localId) ?: return@guardWrite
        val now = System.currentTimeMillis()
        store.softDeleteRecord(localId)
        store.enqueuePendingOp(
            PendingOp(id = UUID.randomUUID().toString(), type = OpType.DELETE_RECORD, entityLocalId = localId, listLocalId = entity.listLocalId, createdAt = now)
        )
        poller.onLocalWrite()
    }

    /**
     * Runs a full sync cycle synchronously (flush pending ops, then pull).
     * Updates [syncStatus] with the result.
     */
    override suspend fun forceSync() {
        _syncStatus.update { it.copy(isSyncing = true, consentIntent = null) }
        try {
            val result = syncEngine.sync()
            val now = System.currentTimeMillis()
            _syncStatus.update { current ->
                val mappedErrors = result.errors.map { it.toPublic() }
                val allErrors = mappedErrors + current.recentErrors
                current.copy(
                    isSyncing = false,
                    lastSyncedAt = now,
                    // Clear accumulated errors when the new sync is clean; otherwise accumulate.
                    recentErrors = if (mappedErrors.isEmpty()) emptyList()
                                   else allErrors.take(config.maxRecentErrors),
                    consentIntent = result.consentIntent as? android.content.Intent,
                )
            }
        } catch (e: Exception) {
            reportFatalStorageError(e)
            _syncStatus.update { it.copy(isSyncing = false) }
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    // Closeable.close() satisfies TaskStoreApi.close() — both declared
    override fun close() {
        poller.cancel()
        db.close()
        SyncWorkerDependencies.current = null
    }
}
