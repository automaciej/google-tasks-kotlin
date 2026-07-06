package pl.blizinski.googletasksstore.internal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskListsDao {

    @Query("SELECT * FROM task_lists WHERE isDeleted = 0 ORDER BY position ASC")
    fun observeAll(): Flow<List<TaskListEntity>>

    @Query("SELECT * FROM task_lists WHERE localId = :localId")
    suspend fun getByLocalId(localId: String): TaskListEntity?

    @Query("SELECT * FROM task_lists WHERE remoteId = :remoteId")
    suspend fun getByRemoteId(remoteId: String): TaskListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(taskList: TaskListEntity)

    @Query("UPDATE task_lists SET remoteId = :remoteId WHERE localId = :localId")
    suspend fun updateRemoteId(localId: String, remoteId: String)

    @Query("SELECT * FROM task_lists WHERE isDeleted = 0 ORDER BY position ASC")
    suspend fun getAll(): List<TaskListEntity>

    @Query("DELETE FROM task_lists WHERE localId = :localId")
    suspend fun hardDelete(localId: String)
}
