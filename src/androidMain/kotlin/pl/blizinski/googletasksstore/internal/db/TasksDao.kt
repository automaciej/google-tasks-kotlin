package pl.blizinski.googletasksstore.internal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TasksDao {

    @Query("SELECT * FROM tasks WHERE listLocalId = :listLocalId AND isDeleted = 0")
    fun observeByList(listLocalId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE localId = :localId")
    suspend fun getByLocalId(localId: String): TaskEntity?

    @Query("SELECT * FROM tasks WHERE remoteId = :remoteId")
    suspend fun getByRemoteId(remoteId: String): TaskEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: TaskEntity)

    @Query("""
        UPDATE tasks
        SET title = :title, notes = :notes, isCompleted = :isCompleted,
            dueDate = :dueDate, parentId = :parentId,
            lastSyncedAt = :lastSyncedAt, remoteUpdatedAt = :remoteUpdatedAt,
            lastSyncedSnapshot = :lastSyncedSnapshot,
            position = :position, etag = :etag,
            completedDate = :completedDate, isHidden = :isHidden,
            webViewLink = :webViewLink, linksJson = :linksJson,
            assignmentInfoJson = :assignmentInfoJson
        WHERE localId = :localId
    """)
    suspend fun updateSyncedFields(
        localId: String,
        title: String,
        notes: String?,
        isCompleted: Boolean,
        dueDate: Long?,
        parentId: String?,
        lastSyncedAt: Long,
        remoteUpdatedAt: Long?,
        lastSyncedSnapshot: String,
        position: String?,
        etag: String?,
        completedDate: Long?,
        isHidden: Boolean,
        webViewLink: String?,
        linksJson: String?,
        assignmentInfoJson: String?,
    )

    /** Returns all non-deleted tasks for a list, used by SyncEngine for deletion detection. */
    @Query("SELECT * FROM tasks WHERE listLocalId = :listLocalId AND isDeleted = 0")
    suspend fun getAllForList(listLocalId: String): List<TaskEntity>

    @Query("UPDATE tasks SET remoteId = :remoteId WHERE localId = :localId")
    suspend fun updateRemoteId(localId: String, remoteId: String)

    @Query("UPDATE tasks SET listLocalId = :newListLocalId WHERE localId = :localId")
    suspend fun updateListId(localId: String, newListLocalId: String)

    @Query("UPDATE tasks SET isDeleted = 1 WHERE localId = :localId")
    suspend fun softDelete(localId: String)

    @Query("DELETE FROM tasks WHERE localId = :localId")
    suspend fun hardDelete(localId: String)
}
