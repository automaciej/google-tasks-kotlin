package pl.blizinski.googletasksstore.internal

import org.json.JSONArray
import pl.blizinski.googletasksstore.models.Task
import pl.blizinski.googletasksstore.models.TaskId
import pl.blizinski.googletasksstore.models.TaskLink
import pl.blizinski.googletasksstore.models.TaskList
import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord

internal fun SyncedRecord<GoogleTask>.toTask(): Task = Task(
    id = TaskId(localId = localId, remoteId = remoteId),
    listId = listLocalId,
    title = content.title,
    notes = content.notes,
    isCompleted = isCompleted,
    createdDate = content.createdDate,
    dueDate = content.dueDate,
    isSubtask = content.parentId != null,
    position = content.position,
    completedDate = content.completedDate,
    isHidden = content.isHidden,
    webViewLink = content.webViewLink,
    links = content.linksJson.toTaskLinks(),
)

private fun String?.toTaskLinks(): List<TaskLink> {
    if (this.isNullOrEmpty()) return emptyList()
    val array = JSONArray(this)
    return (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        TaskLink(
            type = obj.optString("type").takeIf { it.isNotEmpty() },
            description = obj.optString("description").takeIf { it.isNotEmpty() },
            link = obj.optString("link"),
        )
    }
}

internal fun SyncedListRecord<GoogleTaskList>.toTaskList(): TaskList = TaskList(
    id = localId,
    title = content.title,
)
