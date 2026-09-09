package pl.blizinski.googletasksstore.internal

import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import pl.blizinski.tasksync.model.TaskDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GoogleTasksContentAdapterTest {

    private val adapter = GoogleTasksContentAdapter

    @Test
    fun toTask_mapsEnvelopeAndContentFields() {
        val record = SyncedRecord(
            localId = "local-1",
            remoteId = "remote-1",
            listLocalId = "list-1",
            content = GoogleTask(title = "Buy milk", notes = "2%", parentId = "parent-remote-id", position = "0000"),
            isCompleted = true,
        )

        val task = adapter.toTask(record)

        assertEquals("local-1", task.id.localId)
        assertEquals("remote-1", task.id.remoteId)
        assertEquals("list-1", task.listId)
        assertEquals("Buy milk", task.title)
        assertEquals("2%", task.notes)
        assertEquals(true, task.isCompleted)
        assertEquals("0000", task.position)
        assertTrue(task.isSubtask, "parentId != null must mark the task as a subtask")
    }

    @Test
    fun toTask_neverFabricatesPriorityLabelsOrRecurrence() {
        val record = SyncedRecord(localId = "l", remoteId = null, listLocalId = "list", content = GoogleTask(title = "x"))
        val task = adapter.toTask(record)
        assertNull(task.priority)
        assertEquals(emptyList(), task.labels)
        assertNull(task.recurrenceRule)
    }

    @Test
    fun toTaskList_mapsFields() {
        val record = SyncedListRecord(localId = "local-1", remoteId = "remote-1", content = GoogleTaskList(title = "Work"))
        val list = adapter.toTaskList(record)
        assertEquals("local-1", list.id)
        assertEquals("Work", list.title)
    }

    @Test
    fun newContent_dateOnlyDueDate_isKeptVerbatim() {
        val midnightUtc = 1_772_841_600_000L // 2026-03-05T00:00:00Z
        val content = adapter.newContent(TaskDraft(title = "t", dueDate = midnightUtc, dueHasTime = false), now = 1L)
        assertEquals(midnightUtc, content.dueDate)
        assertEquals(1L, content.createdDate)
    }

    @Test
    fun newContent_timeOfDayDueDate_isTruncatedToMidnightUtc() {
        val withTime = 1_772_841_600_000L + 13 * 3_600_000L // same day, 13:00Z
        val content = adapter.newContent(TaskDraft(title = "t", dueDate = withTime, dueHasTime = true), now = 1L)
        assertEquals(1_772_841_600_000L, content.dueDate)
    }

    @Test
    fun applyDraft_updatesTitleNotesDue_keepsOtherContentFields() {
        val existing = GoogleTask(title = "old", notes = "old", createdDate = 5L, parentId = "p", etag = "e")
        val updated = adapter.applyDraft(existing, TaskDraft(title = "new", notes = "n", dueDate = null))
        assertEquals("new", updated.title)
        assertEquals("n", updated.notes)
        assertNull(updated.dueDate)
        assertEquals("p", updated.parentId)
        assertEquals("e", updated.etag)
        assertEquals(5L, updated.createdDate)
    }

    @Test
    fun toTaskLinks_returnsEmptyForNullOrBlank() {
        assertEquals(emptyList(), null.toTaskLinks())
        assertEquals(emptyList(), "".toTaskLinks())
    }

    @Test
    fun toTaskLinks_parsesFieldsAndBlanksEmptyTypeAndDescription() {
        val links = """[{"type":"","description":"","link":"https://example.com"}]""".toTaskLinks()
        assertEquals(1, links.size)
        assertNull(links[0].type)
        assertNull(links[0].description)
        assertEquals("https://example.com", links[0].link)
    }

    @Test
    fun toTaskLinks_keepsNonEmptyTypeAndDescription() {
        val links = """[{"type":"email","description":"From Gmail","link":"https://mail.google.com/x"}]""".toTaskLinks()
        assertEquals(1, links.size)
        assertEquals("email", links[0].type)
        assertEquals("From Gmail", links[0].description)
        assertEquals("https://mail.google.com/x", links[0].link)
    }
}
