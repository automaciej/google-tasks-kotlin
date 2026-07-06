package pl.blizinski.googletasksstore.internal.sync

import pl.blizinski.googletasksstore.internal.network.NetworkTasksSource
import pl.blizinski.googletasksstore.internal.network.RemoteTask
import pl.blizinski.googletasksstore.internal.network.RemoteTaskList

/**
 * In-memory [NetworkTasksSource] for unit tests.
 *
 * [taskListsResponse] and [tasksResponse] are keyed by remoteListId and
 * contain the data the fake server returns. Tests can set these before
 * calling [SyncEngine.sync].
 *
 * [updatedMinCapture] records the updatedMin value passed to [getTasks]
 * per list, so tests can assert what the engine sent.
 *
 * [failingListIds] causes [createTask] to throw for those remoteListIds,
 * simulating a 404 from the server (e.g. the list was deleted upstream).
 */
internal class FakeNetworkTasksSource : NetworkTasksSource {

    var taskListsResponse: List<RemoteTaskList> = emptyList()

    /** Tasks per remoteListId returned by [getTasks]. */
    val tasksResponse: MutableMap<String, List<RemoteTask>> = mutableMapOf()

    /** Captures the updatedMin argument passed to [getTasks] per list. */
    val updatedMinCapture: MutableMap<String, String?> = mutableMapOf()

    /** [createTask] throws for any remoteListId in this set. */
    val failingListIds = mutableSetOf<String>()

    override suspend fun createTaskList(title: String): RemoteTaskList =
        RemoteTaskList(remoteId = "created-list-${title.take(8)}", title = title)

    override suspend fun updateTaskList(remoteListId: String, title: String) = Unit

    override suspend fun deleteTaskList(remoteListId: String) = Unit

    override suspend fun getTaskLists(): List<RemoteTaskList> = taskListsResponse

    override suspend fun getTasks(remoteListId: String, updatedMin: String?): List<RemoteTask> {
        updatedMinCapture[remoteListId] = updatedMin
        return tasksResponse[remoteListId] ?: emptyList()
    }

    override suspend fun createTask(
        remoteListId: String,
        title: String,
        notes: String?,
        dueDate: String?,
    ): RemoteTask {
        if (remoteListId in failingListIds) {
            throw IllegalStateException("404 Not Found: list $remoteListId does not exist")
        }
        return RemoteTask(
            remoteId = "created-remote-${title.take(8)}",
            remoteListId = remoteListId,
            title = title,
            notes = notes,
            isCompleted = false,
            createdDate = null,
            remoteUpdatedAt = null,
        )
    }

    override suspend fun updateTask(
        remoteListId: String,
        remoteTaskId: String,
        title: String,
        notes: String?,
        dueDate: String?,
        isCompleted: Boolean?,
    ) {
        if (remoteListId in failingListIds) {
            throw IllegalStateException("404 Not Found: list $remoteListId does not exist")
        }
    }

    override suspend fun completeTask(remoteListId: String, remoteTaskId: String) = Unit

    override suspend fun deleteTask(remoteListId: String, remoteTaskId: String) = Unit

    /** Captures (remoteTaskId, previousRemoteTaskId) in call order. */
    val moveTaskCalls = mutableListOf<Pair<String, String?>>()

    override suspend fun moveTask(
        remoteListId: String,
        remoteTaskId: String,
        previousRemoteTaskId: String?,
    ) {
        moveTaskCalls += remoteTaskId to previousRemoteTaskId
    }
}
