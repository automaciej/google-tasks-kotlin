package pl.blizinski.googletasksstore

import android.content.Context
import androidx.room.Room
import androidx.work.WorkManager
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import pl.blizinski.googletasksstore.internal.LocalTasksStore
import pl.blizinski.googletasksstore.internal.RoomLocalTasksStore
import pl.blizinski.googletasksstore.internal.db.GoogleTasksDatabase
import pl.blizinski.googletasksstore.internal.db.OpType
import pl.blizinski.googletasksstore.internal.db.PendingOpEntity
import pl.blizinski.googletasksstore.internal.db.TaskEntity
import pl.blizinski.googletasksstore.internal.db.TaskListEntity
import pl.blizinski.googletasksstore.internal.network.GoogleTasksNetworkSource
import pl.blizinski.googletasksstore.internal.sync.AdaptivePoller
import pl.blizinski.googletasksstore.internal.sync.OrderPusher
import pl.blizinski.googletasksstore.internal.sync.PendingOpsProcessor
import pl.blizinski.googletasksstore.internal.sync.SyncEngine
import pl.blizinski.googletasksstore.internal.sync.SyncWorkerDependencies
import pl.blizinski.googletasksstore.models.SyncStatus
import pl.blizinski.googletasksstore.models.Task
import pl.blizinski.googletasksstore.models.TaskList
import java.io.Closeable
import java.util.UUID

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

    private val db: GoogleTasksDatabase = Room.databaseBuilder(
        appContext,
        GoogleTasksDatabase::class.java,
        config.dbName,
    )
        .addMigrations(GoogleTasksDatabase.MIGRATION_1_2, GoogleTasksDatabase.MIGRATION_2_3, GoogleTasksDatabase.MIGRATION_3_4, GoogleTasksDatabase.MIGRATION_4_5)
        .build()

    private val store: LocalTasksStore = RoomLocalTasksStore(
        db.tasksDao(),
        db.taskListsDao(),
        db.pendingOpsDao(),
    )

    private val network = GoogleTasksNetworkSource(credential)
    private val pendingOpsProcessor = PendingOpsProcessor(store, network)
    private val syncEngine = SyncEngine(store, network, pendingOpsProcessor)
    private val orderPusher = OrderPusher(store, network)

    private val workManager = WorkManager.getInstance(appContext)
    private val poller = AdaptivePoller(workManager, config)

    private val _syncStatus = MutableStateFlow(SyncStatus())

    init {
        SyncWorkerDependencies.current = SyncWorkerDependencies.Deps(syncEngine, config)
        poller.start()
    }

    // -----------------------------------------------------------------------
    // Public read API
    // -----------------------------------------------------------------------

    override fun taskLists(): Flow<List<TaskList>> = store.taskLists()

    /** [listLocalId] is the [TaskList.id] value returned by [taskLists]. */
    override fun tasks(listLocalId: String): Flow<List<Task>> = store.tasks(listLocalId)

    /**
     * Live sync status: [SyncStatus.pendingOpCount] is always current (sourced
     * from Room). [SyncStatus.isSyncing], [SyncStatus.lastSyncedAt], and
     * [SyncStatus.recentErrors] reflect only explicit [forceSync] calls;
     * background WorkManager syncs do not update them in v1.
     */
    override fun syncStatus(): Flow<SyncStatus> = combine(
        _syncStatus,
        store.pendingOpCount(),
    ) { status, count -> status.copy(pendingOpCount = count) }

    // -----------------------------------------------------------------------
    // Public write API — optimistic local write + pending op + trigger sync
    // -----------------------------------------------------------------------

    override suspend fun createList(title: String): String {
        val localId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        store.upsertTaskList(
            TaskListEntity(
                localId = localId,
                remoteId = null,
                title = title,
                lastSyncedAt = null,
                position = Int.MAX_VALUE,  // corrected to real position on next sync
            )
        )
        store.enqueuePendingOp(
            PendingOpEntity(
                id = UUID.randomUUID().toString(),
                type = OpType.CREATE_LIST,
                entityLocalId = localId,
                listLocalId = localId,
                payloadJson = "",
                createdAt = now,
            )
        )
        poller.onLocalWrite()
        return localId
    }

    override suspend fun updateList(localId: String, title: String) {
        val entity = store.getTaskListByLocalId(localId) ?: return
        val now = System.currentTimeMillis()
        store.upsertTaskList(entity.copy(title = title))
        store.enqueuePendingOp(
            PendingOpEntity(
                id = UUID.randomUUID().toString(),
                type = OpType.UPDATE_LIST,
                entityLocalId = localId,
                listLocalId = localId,
                payloadJson = "",
                createdAt = now,
            )
        )
        poller.onLocalWrite()
    }

    override suspend fun deleteList(localId: String) {
        val entity = store.getTaskListByLocalId(localId) ?: return
        val now = System.currentTimeMillis()
        // Cancel pending ops for all tasks in this list; remove locally-created tasks immediately.
        for (task in store.getAllTaskEntitiesForList(localId)) {
            store.removeAllPendingOpsForEntity(task.localId)
            if (task.remoteId == null) store.hardDeleteTask(task.localId)
        }
        // Soft-delete the list so it disappears from the UI immediately.
        store.upsertTaskList(entity.copy(isDeleted = true))
        if (entity.remoteId != null) {
            // Save remoteId in payloadJson — entity may be modified/cleaned before sync runs.
            store.enqueuePendingOp(
                PendingOpEntity(
                    id = UUID.randomUUID().toString(),
                    type = OpType.DELETE_LIST,
                    entityLocalId = localId,
                    listLocalId = localId,
                    payloadJson = entity.remoteId,
                    createdAt = now,
                )
            )
            poller.onLocalWrite()
        } else {
            // Never synced — clean up locally without touching the server.
            store.hardDeleteTaskList(localId)
        }
    }

    /**
     * Creates a task locally and returns its stable [localId].
     * The task is synced to the server in the background.
     */
    override suspend fun createTask(listLocalId: String, title: String, notes: String?, dueDate: Long?): String {
        val localId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        store.upsertTask(
            TaskEntity(
                localId = localId,
                remoteId = null,
                listLocalId = listLocalId,
                title = title,
                notes = notes,
                isCompleted = false,
                createdDate = now,
                dueDate = dueDate,
                lastSyncedAt = null,
            )
        )
        store.enqueuePendingOp(
            PendingOpEntity(
                id = UUID.randomUUID().toString(),
                type = OpType.CREATE_TASK,
                entityLocalId = localId,
                listLocalId = listLocalId,
                payloadJson = taskPayloadJson(title, notes, dueDate),
                createdAt = now,
            )
        )
        poller.onLocalWrite()
        return localId
    }

    /** Updates [title], [notes], and [dueDate]. Pass null to clear that field. */
    override suspend fun updateTask(localId: String, title: String, notes: String?, dueDate: Long?) {
        val entity = store.getTaskByLocalId(localId) ?: return
        val now = System.currentTimeMillis()
        store.upsertTask(entity.copy(title = title, notes = notes, dueDate = dueDate))
        store.enqueuePendingOp(
            PendingOpEntity(
                id = UUID.randomUUID().toString(),
                type = OpType.UPDATE_TASK,
                entityLocalId = localId,
                listLocalId = entity.listLocalId,
                payloadJson = taskPayloadJson(title, notes, dueDate),
                createdAt = now,
            )
        )
        poller.onLocalWrite()
    }

    override suspend fun completeTask(localId: String) {
        val entity = store.getTaskByLocalId(localId) ?: return
        val now = System.currentTimeMillis()
        store.upsertTask(entity.copy(isCompleted = true, completedDate = now))
        store.enqueuePendingOp(
            PendingOpEntity(
                id = UUID.randomUUID().toString(),
                type = OpType.COMPLETE_TASK,
                entityLocalId = localId,
                listLocalId = entity.listLocalId,
                payloadJson = "",
                createdAt = now,
            )
        )
        poller.onLocalWrite()
    }

    override suspend fun uncompleteTask(localId: String) {
        val entity = store.getTaskByLocalId(localId) ?: return
        val now = System.currentTimeMillis()
        store.upsertTask(entity.copy(isCompleted = false, completedDate = null))
        store.enqueuePendingOp(
            PendingOpEntity(
                id = UUID.randomUUID().toString(),
                type = OpType.UPDATE_TASK,
                entityLocalId = localId,
                listLocalId = entity.listLocalId,
                payloadJson = taskPayloadJson(entity.title, entity.notes, entity.dueDate, isCompleted = false),
                createdAt = now,
            )
        )
        poller.onLocalWrite()
    }

    override suspend fun deleteTask(localId: String) {
        val entity = store.getTaskByLocalId(localId) ?: return
        val now = System.currentTimeMillis()
        store.softDeleteTask(localId)
        store.enqueuePendingOp(
            PendingOpEntity(
                id = UUID.randomUUID().toString(),
                type = OpType.DELETE_TASK,
                entityLocalId = localId,
                listLocalId = entity.listLocalId,
                payloadJson = "",
                createdAt = now,
            )
        )
        poller.onLocalWrite()
    }

    override suspend fun pushOrder(localTaskIds: List<String>) =
        orderPusher.push(localTaskIds)

    /**
     * Runs a full sync cycle synchronously (flush pending ops, then pull).
     * Updates [syncStatus] with the result.
     */
    override suspend fun forceSync() {
        _syncStatus.update { it.copy(isSyncing = true, consentIntent = null) }
        val result = syncEngine.sync()
        val now = System.currentTimeMillis()
        _syncStatus.update { current ->
            val allErrors = result.errors + current.recentErrors
            current.copy(
                isSyncing = false,
                lastSyncedAt = now,
                // Clear accumulated errors when the new sync is clean; otherwise accumulate.
                recentErrors = if (result.errors.isEmpty()) emptyList()
                               else allErrors.take(config.maxRecentErrors),
                consentIntent = result.consentIntent,
            )
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

// ---------------------------------------------------------------------------
// Payload helpers
// ---------------------------------------------------------------------------

private fun taskPayloadJson(title: String, notes: String?, dueDate: Long? = null, isCompleted: Boolean? = null): String =
    JSONObject().apply {
        put("title", title)
        put("notes", notes ?: JSONObject.NULL)
        put("dueDate", dueDate ?: JSONObject.NULL)
        if (isCompleted != null) put("isCompleted", isCompleted)
    }.toString()

// ---------------------------------------------------------------------------
// RFC 3339 helpers for due date (Google Tasks API format)
// ---------------------------------------------------------------------------

/** Converts epoch milliseconds to an RFC 3339 date string ("2026-03-20T00:00:00.000Z"). */
internal fun Long.toRfc3339DueDate(): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(this))
}

/** Parses an RFC 3339 date string to epoch milliseconds, or null on failure. */
internal fun String.parseRfc3339ToEpochMs(): Long? = try {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    sdf.parse(this)?.time
} catch (e: Exception) { null }
