package pl.blizinski.googletasksstore.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
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

@Serializable
private data class SerializableTaskLink(
    val type: String = "",
    val description: String = "",
    val link: String = "",
)

private val taskLinksJson = Json { ignoreUnknownKeys = true }

/**
 * Uses kotlinx.serialization rather than org.json — unlike org.json (an Android-stub class in
 * JVM unit tests, requiring Robolectric to behave correctly), this is directly unit-testable,
 * consistent with [GoogleTask] itself already using kotlinx.serialization.
 */
internal fun String?.toTaskLinks(): List<TaskLink> {
    if (this.isNullOrEmpty()) return emptyList()
    return taskLinksJson.decodeFromString<List<SerializableTaskLink>>(this).map {
        TaskLink(
            type = it.type.takeIf { t -> t.isNotEmpty() },
            description = it.description.takeIf { d -> d.isNotEmpty() },
            link = it.link,
        )
    }
}

internal fun SyncedListRecord<GoogleTaskList>.toTaskList(): TaskList = TaskList(
    id = localId,
    title = content.title,
)
