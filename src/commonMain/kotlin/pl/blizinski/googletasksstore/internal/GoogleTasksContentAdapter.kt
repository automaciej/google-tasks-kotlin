package pl.blizinski.googletasksstore.internal

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.model.Task
import pl.blizinski.tasksync.model.TaskDraft
import pl.blizinski.tasksync.model.TaskLink
import pl.blizinski.tasksync.model.TaskList
import pl.blizinski.tasksync.model.TaskRef
import pl.blizinski.tasksync.store.ContentAdapter

/**
 * Maps between Google's opaque content types and the shared [Task]/[TaskList]. Google Tasks has
 * no priority, labels, or recurrence — those stay at [Task]'s defaults. Due dates are date-only:
 * a [TaskDraft] carrying a time-of-day is truncated to that day's midnight UTC (Google's own
 * convention) before it is stored, so a later pull of the date-only value does not read as a
 * spurious content change.
 */
internal object GoogleTasksContentAdapter : ContentAdapter<GoogleTask, GoogleTaskList> {

    override fun toTask(record: SyncedRecord<GoogleTask>) = Task(
        id = TaskRef(localId = record.localId, remoteId = record.remoteId),
        listId = record.listLocalId,
        title = record.content.title,
        notes = record.content.notes,
        isCompleted = record.isCompleted,
        createdDate = record.content.createdDate,
        dueDate = record.content.dueDate,
        isSubtask = record.content.parentId != null,
        position = record.content.position,
        completedDate = record.content.completedDate,
        isHidden = record.content.isHidden,
        webViewLink = record.content.webViewLink,
        links = record.content.linksJson.toTaskLinks(),
    )

    override fun toTaskList(list: SyncedListRecord<GoogleTaskList>) = TaskList(
        id = list.localId,
        title = list.content.title,
    )

    override fun newContent(draft: TaskDraft, now: Long) = GoogleTask(
        title = draft.title,
        notes = draft.notes,
        createdDate = now,
        dueDate = draft.dueDate?.dateOnly(draft.dueHasTime),
    )

    override fun applyDraft(existing: GoogleTask, draft: TaskDraft) = existing.copy(
        title = draft.title,
        notes = draft.notes,
        dueDate = draft.dueDate?.dateOnly(draft.dueHasTime),
    )

    override fun applyCompletion(existing: GoogleTask, completed: Boolean, at: Long?) =
        existing.copy(completedDate = at)

    override fun newListContent(title: String) = GoogleTaskList(title = title)

    override fun applyListTitle(existing: GoogleTaskList, title: String) = GoogleTaskList(title = title)
}

private const val MS_PER_DAY = 86_400_000L

/** Truncates an epoch-ms value to that day's midnight UTC when [hasTime]; otherwise returns it
 *  unchanged (it is already date-only). */
private fun Long.dateOnly(hasTime: Boolean): Long = if (hasTime) this - this.mod(MS_PER_DAY) else this

@Serializable
private data class SerializableTaskLink(
    val type: String = "",
    val description: String = "",
    val link: String = "",
)

private val taskLinksJson = Json { ignoreUnknownKeys = true }

/**
 * Parses Google's `links` JSON array string into [TaskLink]s. Uses kotlinx.serialization rather
 * than org.json so it is directly unit-testable without Robolectric.
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
