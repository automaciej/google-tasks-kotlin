package pl.blizinski.googletasksstore.internal.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_lists")
data class TaskListEntity(
    @PrimaryKey val localId: String,
    val remoteId: String?,
    val title: String,
    val lastSyncedAt: Long?,
    val isDeleted: Boolean = false,
    /** Position of this list as returned by the API (0-based). Used for stable ordering. */
    val position: Int = 0,
)
