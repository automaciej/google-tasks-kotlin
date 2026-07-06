package pl.blizinski.googletasksstore.internal.network

/**
 * Abstracts the Google Tasks REST API. All methods run on [kotlinx.coroutines.Dispatchers.IO].
 * The implementation uses remoteIds for all API calls; localId mapping is the
 * responsibility of [pl.blizinski.googletasksstore.internal.sync.SyncEngine].
 */
internal interface NetworkTasksSource {
    suspend fun getTaskLists(): List<RemoteTaskList>
    suspend fun createTaskList(title: String): RemoteTaskList
    suspend fun updateTaskList(remoteListId: String, title: String)
    suspend fun deleteTaskList(remoteListId: String)
    /**
     * Fetches tasks for [remoteListId].
     *
     * When [updatedMin] is non-null (RFC 3339 timestamp) an incremental pull
     * is performed: only tasks modified after that time are returned, including
     * completed and deleted ones. When null a full pull is performed (active
     * tasks only, no deleted entries).
     */
    suspend fun getTasks(remoteListId: String, updatedMin: String? = null): List<RemoteTask>
    /** [dueDate] is an RFC 3339 date string ("2026-03-20T00:00:00.000Z"), or null to omit. */
    suspend fun createTask(remoteListId: String, title: String, notes: String?, dueDate: String? = null): RemoteTask
    /**
     * [dueDate] is an RFC 3339 date string, or null to clear the due date.
     * [isCompleted] when explicitly false, clears the completion status (status → "needsAction",
     * completed timestamp omitted). null leaves completion status unchanged.
     */
    suspend fun updateTask(remoteListId: String, remoteTaskId: String, title: String, notes: String?, dueDate: String? = null, isCompleted: Boolean? = null)
    suspend fun completeTask(remoteListId: String, remoteTaskId: String)
    suspend fun deleteTask(remoteListId: String, remoteTaskId: String)
    /**
     * Moves [remoteTaskId] to the position immediately after [previousRemoteTaskId].
     * Pass null for [previousRemoteTaskId] to move the task to the top of the list.
     */
    suspend fun moveTask(remoteListId: String, remoteTaskId: String, previousRemoteTaskId: String?)
}

internal data class RemoteTaskList(
    val remoteId: String,
    val title: String,
)

internal data class RemoteTask(
    val remoteId: String,
    val remoteListId: String,
    val title: String,
    val notes: String?,
    val isCompleted: Boolean,
    val isDeleted: Boolean = false,
    val isHidden: Boolean = false,
    val createdDate: Long?,
    val remoteUpdatedAt: Long?,
    /** RFC 3339 date string from the API, or null if not set. */
    val dueDate: String? = null,
    /** RFC 3339 date string of when the task was completed, or null. */
    val completedDate: String? = null,
    /** Remote ID of the parent task, or null for top-level tasks. */
    val parentId: String? = null,
    /** String position among sibling tasks (lexicographic ordering). */
    val position: String? = null,
    /** ETag of the resource (for conflict detection). */
    val etag: String? = null,
    /** Absolute link to the task in Google Tasks Web UI. */
    val webViewLink: String? = null,
    /** JSON array string of link objects [{type, description, link}], or null. */
    val linksJson: String? = null,
    /** JSON object string of AssignmentInfo, or null if not an assigned task. */
    val assignmentInfoJson: String? = null,
)
