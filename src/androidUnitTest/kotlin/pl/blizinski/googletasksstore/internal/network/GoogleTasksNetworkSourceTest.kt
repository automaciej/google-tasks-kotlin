package pl.blizinski.googletasksstore.internal.network

import com.google.api.services.tasks.model.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GoogleTasksNetworkSourceTest {

    @Test
    fun dueDateRoundTripsThroughRfc3339() {
        // 2026-03-07T00:00:00.000Z
        val epochMs = 1772841600000L
        val formatted = epochMs.toRfc3339DueDate()
        assertEquals(epochMs, formatted.parseRfc3339ToEpochMs())
    }

    @Test
    fun malformedDateParsesToNull() {
        assertNull("not-a-date".parseRfc3339ToEpochMs())
    }

    @Test
    fun toRemoteRecordMapsFieldsWithNoLinksOrAssignment() {
        // links/assignmentInfo are excluded here since GoogleTask.linksJson/assignmentInfoJson
        // are still built via org.json (an Android-stub class in plain JVM unit tests) inside
        // toRemoteRecord() itself -- see this stage's design-doc note on the accepted gap.
        val task = Task().apply {
            id = "remote-1"
            title = "Buy milk"
            notes = "2%"
            status = "completed"
            deleted = false
            due = 1772841600000L.toRfc3339DueDate()
            updated = 1772841600000L.toRfc3339DueDate()
            parent = "parent-remote-id"
            position = "0000001"
            etag = "etag-value"
            hidden = true
            webViewLink = "https://tasks.google.com/task/x"
        }

        val record = task.toRemoteRecord()

        assertEquals("remote-1", record.remoteId)
        assertEquals(true, record.isCompleted)
        assertEquals(false, record.isDeleted)
        assertEquals(1772841600000L, record.remoteUpdatedAt)
        assertEquals("Buy milk", record.content.title)
        assertEquals("2%", record.content.notes)
        assertEquals(1772841600000L, record.content.dueDate)
        assertEquals("parent-remote-id", record.content.parentId)
        assertEquals("0000001", record.content.position)
        assertEquals("etag-value", record.content.etag)
        assertEquals(true, record.content.isHidden)
        assertEquals("https://tasks.google.com/task/x", record.content.webViewLink)
        assertNull(record.content.linksJson)
        assertNull(record.content.assignmentInfoJson)
    }

    @Test
    fun toRemoteRecordTreatsMissingStatusAndDeletedAsNotCompletedNotDeleted() {
        val task = Task().apply {
            id = "remote-2"
            title = "Needs action"
        }

        val record = task.toRemoteRecord()

        assertEquals(false, record.isCompleted)
        assertEquals(false, record.isDeleted)
    }
}
