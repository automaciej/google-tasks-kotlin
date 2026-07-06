package pl.blizinski.googletasksstore.internal

import org.json.JSONArray
import pl.blizinski.googletasksstore.internal.db.TaskEntity
import pl.blizinski.googletasksstore.internal.db.TaskListEntity
import pl.blizinski.googletasksstore.models.Task
import pl.blizinski.googletasksstore.models.TaskId
import pl.blizinski.googletasksstore.models.TaskLink
import pl.blizinski.googletasksstore.models.TaskList

internal fun TaskEntity.toTask(): Task = Task(
    id = TaskId(localId = localId, remoteId = remoteId),
    listId = listLocalId,
    title = title,
    notes = notes,
    isCompleted = isCompleted,
    createdDate = createdDate,
    dueDate = dueDate,
    isSubtask = parentId != null,
    position = position,
    completedDate = completedDate,
    isHidden = isHidden,
    webViewLink = webViewLink,
    links = linksJson.toTaskLinks(),
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

internal fun TaskListEntity.toTaskList(): TaskList = TaskList(
    id = localId,
    title = title,
)
