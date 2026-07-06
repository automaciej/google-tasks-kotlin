package pl.blizinski.googletasksstore.internal.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import pl.blizinski.googletasksstore.internal.LocalTasksStore
import pl.blizinski.googletasksstore.internal.db.OpStatus
import pl.blizinski.googletasksstore.internal.db.PendingOpEntity
import pl.blizinski.googletasksstore.internal.db.TaskEntity
import pl.blizinski.googletasksstore.internal.db.TaskListEntity
import pl.blizinski.googletasksstore.internal.db.TaskSnapshot
import pl.blizinski.googletasksstore.models.Task
import pl.blizinski.googletasksstore.models.TaskList

/**
 * In-memory [LocalTasksStore] for unit tests.
 *
 * All state is public so tests can seed and inspect it directly.
 */
internal class FakeLocalTasksStore : LocalTasksStore {

    val taskLists = mutableMapOf<String, TaskListEntity>()   // key = localId
    val tasks = mutableMapOf<String, TaskEntity>()           // key = localId
    val pendingOps = mutableMapOf<String, PendingOpEntity>() // key = op.id

    // --- Public read streams (unused in SyncEngine unit tests) ---

    override fun taskLists(): Flow<List<TaskList>> = flowOf(emptyList())
    override fun tasks(listLocalId: String): Flow<List<Task>> = flowOf(emptyList())
    override fun pendingOpCount(): Flow<Int> = flowOf(0)

    // --- Internal entity access ---

    override suspend fun getTaskByLocalId(localId: String): TaskEntity? =
        tasks[localId]?.takeIf { !it.isDeleted }

    override suspend fun getTaskByRemoteId(remoteId: String): TaskEntity? =
        tasks.values.firstOrNull { it.remoteId == remoteId && !it.isDeleted }

    override suspend fun getAllTaskEntitiesForList(listLocalId: String): List<TaskEntity> =
        tasks.values.filter { it.listLocalId == listLocalId && !it.isDeleted }

    override suspend fun getTaskListByLocalId(localId: String): TaskListEntity? =
        taskLists[localId]?.takeIf { !it.isDeleted }

    override suspend fun getTaskListByRemoteId(remoteId: String): TaskListEntity? =
        taskLists.values.firstOrNull { it.remoteId == remoteId && !it.isDeleted }

    override suspend fun getAllTaskLists(): List<TaskListEntity> =
        taskLists.values.filter { !it.isDeleted }

    // --- Task mutations ---

    override suspend fun upsertTask(entity: TaskEntity, snapshot: TaskSnapshot?) {
        tasks[entity.localId] = entity
    }

    override suspend fun updateTaskRemoteId(localId: String, remoteId: String) {
        tasks[localId]?.let { tasks[localId] = it.copy(remoteId = remoteId) }
    }

    override suspend fun updateTaskSyncedFields(
        localId: String,
        title: String,
        notes: String?,
        isCompleted: Boolean,
        dueDate: Long?,
        parentId: String?,
        lastSyncedAt: Long,
        remoteUpdatedAt: Long?,
        snapshot: TaskSnapshot,
        position: String?,
        etag: String?,
        completedDate: Long?,
        isHidden: Boolean,
        webViewLink: String?,
        linksJson: String?,
        assignmentInfoJson: String?,
    ) {
        tasks[localId]?.let {
            tasks[localId] = it.copy(
                title = title,
                notes = notes,
                isCompleted = isCompleted,
                dueDate = dueDate,
                parentId = parentId,
                lastSyncedAt = lastSyncedAt,
                remoteUpdatedAt = remoteUpdatedAt,
                position = position,
                etag = etag,
                completedDate = completedDate,
                isHidden = isHidden,
                webViewLink = webViewLink,
                linksJson = linksJson,
                assignmentInfoJson = assignmentInfoJson,
            )
        }
    }

    override suspend fun softDeleteTask(localId: String) {
        tasks[localId]?.let { tasks[localId] = it.copy(isDeleted = true) }
    }

    override suspend fun hardDeleteTask(localId: String) {
        tasks.remove(localId)
    }

    // --- Task list mutations ---

    override suspend fun upsertTaskList(entity: TaskListEntity) {
        taskLists[entity.localId] = entity
    }

    override suspend fun reassignTask(taskLocalId: String, newListLocalId: String) {
        tasks[taskLocalId]?.let { tasks[taskLocalId] = it.copy(listLocalId = newListLocalId) }
        pendingOps.values
            .filter { it.entityLocalId == taskLocalId }
            .forEach { op -> pendingOps[op.id] = op.copy(listLocalId = newListLocalId) }
    }

    override suspend fun hardDeleteTaskList(localId: String) {
        val remaining = tasks.values.filter { it.listLocalId == localId }.map { it.localId }
        for (taskLocalId in remaining) {
            pendingOps.keys.removeAll { pendingOps[it]?.entityLocalId == taskLocalId }
            tasks.remove(taskLocalId)
        }
        taskLists.remove(localId)
    }

    // --- Pending ops ---

    override suspend fun getAllPendingOps(): List<PendingOpEntity> =
        pendingOps.values.sortedBy { it.createdAt }

    override suspend fun getPendingOpsForEntity(entityLocalId: String): List<PendingOpEntity> =
        pendingOps.values.filter { it.entityLocalId == entityLocalId }.sortedBy { it.createdAt }

    override suspend fun enqueuePendingOp(op: PendingOpEntity) {
        pendingOps[op.id] = op
    }

    override suspend fun removePendingOp(id: String) {
        pendingOps.remove(id)
    }

    override suspend fun removeAllPendingOpsForEntity(entityLocalId: String) {
        pendingOps.keys.removeAll { pendingOps[it]?.entityLocalId == entityLocalId }
    }

    override suspend fun recordPendingOpAttempt(id: String, status: OpStatus) {
        pendingOps[id]?.let {
            pendingOps[id] = it.copy(attemptCount = it.attemptCount + 1, status = status)
        }
    }
}
