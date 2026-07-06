package pl.blizinski.googletasksstore.internal.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey val localId: String,
    val remoteId: String?,
    val listLocalId: String,
    val title: String,
    val notes: String?,
    val isCompleted: Boolean,
    val createdDate: Long?,
    /** Due date as epoch milliseconds (midnight UTC). Null means no due date. */
    val dueDate: Long?,
    val lastSyncedAt: Long?,
    val isDeleted: Boolean = false,
    /** Server's `updated` timestamp (epoch ms). Used as the remote side's
     *  timestamp in three-way conflict resolution. */
    val remoteUpdatedAt: Long? = null,
    /** JSON snapshot of {title, notes, isCompleted} as of the last successful
     *  pull. Serves as the merge base for three-way conflict resolution.
     *  Null for tasks created locally and not yet synced. */
    val lastSyncedSnapshot: String? = null,
    /** Remote ID of the parent task. Non-null means this is a subtask. */
    val parentId: String? = null,
    /** String position among sibling tasks under the same parent (lexicographic ordering). */
    val position: String? = null,
    /** ETag of the resource (for conflict detection). */
    val etag: String? = null,
    /** Completion date of the task (epoch milliseconds). Null if not completed. */
    val completedDate: Long? = null,
    /** True when the task is hidden (completed and list was cleared). Read-only from server. */
    val isHidden: Boolean = false,
    /** Absolute link to the task in Google Tasks Web UI. */
    val webViewLink: String? = null,
    /** JSON array string of link objects [{type, description, link}], or null. */
    val linksJson: String? = null,
    /** JSON object string of AssignmentInfo, or null if not an assigned task. */
    val assignmentInfoJson: String? = null,
)
