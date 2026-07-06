package pl.blizinski.googletasksstore.internal

import kotlinx.coroutines.flow.Flow
import pl.blizinski.googletasksstore.internal.db.OpStatus
import pl.blizinski.googletasksstore.internal.db.PendingOpEntity
import pl.blizinski.googletasksstore.internal.db.TaskEntity
import pl.blizinski.googletasksstore.internal.db.TaskListEntity
import pl.blizinski.googletasksstore.internal.db.TaskSnapshot
import pl.blizinski.googletasksstore.models.Task
import pl.blizinski.googletasksstore.models.TaskList

internal interface LocalTasksStore {

    // --- Public read streams ---

    fun taskLists(): Flow<List<TaskList>>
    fun tasks(listLocalId: String): Flow<List<Task>>
    fun pendingOpCount(): Flow<Int>

    // --- Internal entity access (for SyncEngine / PendingOpsProcessor) ---

    suspend fun getTaskByLocalId(localId: String): TaskEntity?
    suspend fun getTaskByRemoteId(remoteId: String): TaskEntity?
    suspend fun getAllTaskEntitiesForList(listLocalId: String): List<TaskEntity>
    suspend fun getTaskListByLocalId(localId: String): TaskListEntity?
    suspend fun getTaskListByRemoteId(remoteId: String): TaskListEntity?

    // --- Task mutations ---

    /**
     * [snapshot] is the merge-base for three-way conflict resolution.
     * Pass null for optimistic local writes that haven't been synced yet.
     */
    suspend fun upsertTask(entity: TaskEntity, snapshot: TaskSnapshot? = null)
    suspend fun updateTaskRemoteId(localId: String, remoteId: String)
    suspend fun updateTaskSyncedFields(
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
    )
    suspend fun softDeleteTask(localId: String)
    suspend fun hardDeleteTask(localId: String)

    // --- Task list queries ---

    suspend fun getAllTaskLists(): List<TaskListEntity>

    // --- Task list mutations ---

    suspend fun upsertTaskList(entity: TaskListEntity)

    /**
     * Reassigns a task to a different list, updating both the task entity and
     * any pending ops for that task so they push to the correct remote list.
     */
    suspend fun reassignTask(taskLocalId: String, newListLocalId: String)

    /**
     * Hard-deletes a task list and all tasks still in it (along with their
     * pending ops). Locally-created tasks should be reassigned via
     * [reassignTask] before calling this so they are not lost.
     */
    suspend fun hardDeleteTaskList(localId: String)

    // --- Pending ops ---

    suspend fun getAllPendingOps(): List<PendingOpEntity>
    suspend fun getPendingOpsForEntity(entityLocalId: String): List<PendingOpEntity>
    suspend fun enqueuePendingOp(op: PendingOpEntity)
    suspend fun removePendingOp(id: String)
    suspend fun removeAllPendingOpsForEntity(entityLocalId: String)
    suspend fun recordPendingOpAttempt(id: String, status: OpStatus)
}
