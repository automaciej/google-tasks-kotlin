package pl.blizinski.googletasksstore.models

data class Task(
    val id: TaskId,
    val listId: String,
    val title: String,
    val notes: String? = null,
    val isCompleted: Boolean = false,
    val createdDate: Long? = null,
    /** Due date as epoch milliseconds (midnight UTC). Null means no due date. */
    val dueDate: Long? = null,
    /** True when this task has a parent task (i.e. it is a subtask). */
    val isSubtask: Boolean = false,
    /** String position among sibling tasks under the same parent (lexicographic ordering). */
    val position: String? = null,
    /** Completion date as epoch milliseconds. Null if the task is not completed. */
    val completedDate: Long? = null,
    /** True when the task is hidden (completed when the task list was last cleared). */
    val isHidden: Boolean = false,
    /** Absolute link to the task in Google Tasks Web UI. Null if not yet synced. */
    val webViewLink: String? = null,
    /** Links back to the resource(s) this task was created from (e.g. Gmail message, Doc). */
    val links: List<TaskLink> = emptyList(),
)
