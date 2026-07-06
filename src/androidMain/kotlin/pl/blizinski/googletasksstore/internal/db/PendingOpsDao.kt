package pl.blizinski.googletasksstore.internal.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingOpsDao {

    @Query("SELECT * FROM pending_ops ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<PendingOpEntity>>

    @Query("SELECT * FROM pending_ops ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingOpEntity>

    @Query("SELECT * FROM pending_ops WHERE entityLocalId = :entityLocalId ORDER BY createdAt ASC")
    suspend fun getByEntity(entityLocalId: String): List<PendingOpEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(op: PendingOpEntity)

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM pending_ops WHERE entityLocalId = :entityLocalId")
    suspend fun deleteByEntity(entityLocalId: String)

    @Query("UPDATE pending_ops SET listLocalId = :newListLocalId WHERE entityLocalId = :entityLocalId")
    suspend fun updateListIdForEntity(entityLocalId: String, newListLocalId: String)

    @Query("""
        UPDATE pending_ops
        SET attemptCount = attemptCount + 1, status = :status
        WHERE id = :id
    """)
    suspend fun recordAttempt(id: String, status: OpStatus)

    @Query("SELECT COUNT(*) FROM pending_ops")
    fun observeCount(): Flow<Int>
}
