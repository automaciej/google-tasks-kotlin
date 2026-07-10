package pl.blizinski.googletasksstore.internal

import pl.blizinski.tasksync.SyncedListRecord
import pl.blizinski.tasksync.SyncedRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntityMappersTest {

    @Test
    fun toTaskMapsEnvelopeAndContentFields() {
        val record = SyncedRecord(
            localId = "local-1",
            remoteId = "remote-1",
            listLocalId = "list-1",
            content = GoogleTask(title = "Buy milk", notes = "2%", parentId = "parent-remote-id"),
            isCompleted = true,
        )

        val task = record.toTask()

        assertEquals("local-1", task.id.localId)
        assertEquals("remote-1", task.id.remoteId)
        assertEquals("list-1", task.listId)
        assertEquals("Buy milk", task.title)
        assertEquals("2%", task.notes)
        assertEquals(true, task.isCompleted)
        assertTrue(task.isSubtask, "parentId != null must mark the task as a subtask")
    }

    @Test
    fun toTaskListMapsFields() {
        val record = SyncedListRecord(localId = "local-1", remoteId = "remote-1", content = GoogleTaskList(title = "Work"))
        val list = record.toTaskList()
        assertEquals("local-1", list.id)
        assertEquals("Work", list.title)
    }

    @Test
    fun toTaskLinksReturnsEmptyForNullOrBlank() {
        assertEquals(emptyList(), null.toTaskLinks())
        assertEquals(emptyList(), "".toTaskLinks())
    }

    @Test
    fun toTaskLinksParsesFieldsAndBlanksEmptyTypeAndDescription() {
        val json = """[{"type":"","description":"","link":"https://example.com"}]"""
        val links = json.toTaskLinks()
        assertEquals(1, links.size)
        assertNull(links[0].type)
        assertNull(links[0].description)
        assertEquals("https://example.com", links[0].link)
    }

    @Test
    fun toTaskLinksKeepsNonEmptyTypeAndDescription() {
        val json = """[{"type":"email","description":"From Gmail","link":"https://mail.google.com/x"}]"""
        val links = json.toTaskLinks()
        assertEquals(1, links.size)
        assertEquals("email", links[0].type)
        assertEquals("From Gmail", links[0].description)
        assertEquals("https://mail.google.com/x", links[0].link)
    }
}
