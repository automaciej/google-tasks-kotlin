package pl.blizinski.googletasksstore.internal.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_ops")
data class PendingOpEntity(
    @PrimaryKey val id: String,
    val type: OpType,
    val entityLocalId: String,
    val listLocalId: String,
    /** JSON of changed fields for UPDATE_TASK; empty string for other op types. */
    val payloadJson: String,
    val createdAt: Long,
    val attemptCount: Int = 0,
    val status: OpStatus = OpStatus.PENDING,
)
