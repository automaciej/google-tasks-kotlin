package pl.blizinski.googletasksstore.internal

import kotlinx.serialization.Serializable

/**
 * Opaque content type for [pl.blizinski.tasksync.SyncEngine]/[pl.blizinski.tasksync.PendingOpsProcessor]
 * — everything about a Google Task except the fields promoted into the shared sync envelope
 * (localId, remoteId, listLocalId, isCompleted, isDeleted, lastSyncedAt, remoteUpdatedAt).
 * [dueDate]/[completedDate] are epoch milliseconds — RFC 3339 conversion is entirely
 * [GoogleTasksNetworkSource]'s concern, never above it.
 */
@Serializable
internal data class GoogleTask(
    val title: String,
    val notes: String? = null,
    val createdDate: Long? = null,
    val dueDate: Long? = null,
    /** Remote ID of the parent task. Non-null means this is a subtask. */
    val parentId: String? = null,
    /** String position among sibling tasks under the same parent (lexicographic ordering). */
    val position: String? = null,
    val etag: String? = null,
    val completedDate: Long? = null,
    /** True when the task is hidden (completed and list was cleared). Read-only from server. */
    val isHidden: Boolean = false,
    val webViewLink: String? = null,
    /** JSON array string of link objects [{type, description, link}], or null. */
    val linksJson: String? = null,
    /** JSON object string of AssignmentInfo, or null if not an assigned task. */
    val assignmentInfoJson: String? = null,
)

@Serializable
internal data class GoogleTaskList(
    val title: String,
)
