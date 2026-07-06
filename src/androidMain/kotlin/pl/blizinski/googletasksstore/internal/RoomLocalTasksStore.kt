package pl.blizinski.googletasksstore.internal

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.blizinski.googletasksstore.internal.db.OpStatus
import pl.blizinski.googletasksstore.internal.db.PendingOpEntity
import pl.blizinski.googletasksstore.internal.db.PendingOpsDao
import pl.blizinski.googletasksstore.internal.db.TaskEntity
import pl.blizinski.googletasksstore.internal.db.TaskListEntity
import pl.blizinski.googletasksstore.internal.db.TaskSnapshot
import pl.blizinski.googletasksstore.internal.db.toJson
import pl.blizinski.googletasksstore.internal.db.TaskListsDao
import pl.blizinski.googletasksstore.internal.db.TasksDao
import pl.blizinski.googletasksstore.models.Task
import pl.blizinski.googletasksstore.models.TaskList

internal class RoomLocalTasksStore(
    private val tasksDao: TasksDao,
    private val taskListsDao: TaskListsDao,
    private val pendingOpsDao: PendingOpsDao,
) : LocalTasksStore {

    override fun taskLists(): Flow<List<TaskList>> =
        taskListsDao.observeAll().map { entities -> entities.map { it.toTaskList() } }

    override fun tasks(listLocalId: String): Flow<List<Task>> =
        tasksDao.observeByList(listLocalId).map { entities -> entities.map { it.toTask() } }

    override fun pendingOpCount(): Flow<Int> =
        pendingOpsDao.observeCount()

    override suspend fun getTaskByLocalId(localId: String): TaskEntity? =
        tasksDao.getByLocalId(localId)

    override suspend fun getTaskByRemoteId(remoteId: String): TaskEntity? =
        tasksDao.getByRemoteId(remoteId)

    override suspend fun getAllTaskEntitiesForList(listLocalId: String): List<TaskEntity> =
        tasksDao.getAllForList(listLocalId)

    override suspend fun getTaskListByLocalId(localId: String): TaskListEntity? =
        taskListsDao.getByLocalId(localId)

    override suspend fun getTaskListByRemoteId(remoteId: String): TaskListEntity? =
        taskListsDao.getByRemoteId(remoteId)

    override suspend fun upsertTask(entity: TaskEntity, snapshot: TaskSnapshot?) =
        tasksDao.upsert(if (snapshot != null) entity.copy(lastSyncedSnapshot = snapshot.toJson()) else entity)

    override suspend fun updateTaskRemoteId(localId: String, remoteId: String) =
        tasksDao.updateRemoteId(localId, remoteId)

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
    ) = tasksDao.updateSyncedFields(
        localId = localId,
        title = title,
        notes = notes,
        isCompleted = isCompleted,
        dueDate = dueDate,
        parentId = parentId,
        lastSyncedAt = lastSyncedAt,
        remoteUpdatedAt = remoteUpdatedAt,
        lastSyncedSnapshot = snapshot.toJson(),
        position = position,
        etag = etag,
        completedDate = completedDate,
        isHidden = isHidden,
        webViewLink = webViewLink,
        linksJson = linksJson,
        assignmentInfoJson = assignmentInfoJson,
    )

    override suspend fun softDeleteTask(localId: String) =
        tasksDao.softDelete(localId)

    override suspend fun hardDeleteTask(localId: String) =
        tasksDao.hardDelete(localId)

    override suspend fun getAllTaskLists(): List<TaskListEntity> =
        taskListsDao.getAll()

    override suspend fun upsertTaskList(entity: TaskListEntity) =
        taskListsDao.upsert(entity)

    override suspend fun reassignTask(taskLocalId: String, newListLocalId: String) {
        tasksDao.updateListId(taskLocalId, newListLocalId)
        pendingOpsDao.updateListIdForEntity(taskLocalId, newListLocalId)
    }

    override suspend fun hardDeleteTaskList(localId: String) {
        val remaining = tasksDao.getAllForList(localId)
        for (task in remaining) {
            pendingOpsDao.deleteByEntity(task.localId)
            tasksDao.hardDelete(task.localId)
        }
        taskListsDao.hardDelete(localId)
    }

    override suspend fun getAllPendingOps(): List<PendingOpEntity> =
        pendingOpsDao.getAll()

    override suspend fun getPendingOpsForEntity(entityLocalId: String): List<PendingOpEntity> =
        pendingOpsDao.getByEntity(entityLocalId)

    override suspend fun enqueuePendingOp(op: PendingOpEntity) =
        pendingOpsDao.insert(op)

    override suspend fun removePendingOp(id: String) =
        pendingOpsDao.delete(id)

    override suspend fun removeAllPendingOpsForEntity(entityLocalId: String) =
        pendingOpsDao.deleteByEntity(entityLocalId)

    override suspend fun recordPendingOpAttempt(id: String, status: OpStatus) =
        pendingOpsDao.recordAttempt(id, status)
}
