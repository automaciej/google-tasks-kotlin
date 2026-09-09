package pl.blizinski.googletasksstore.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class GoogleTasksContentMergerTest {

    private val merge = GoogleTasksContentMerger

    private val base = GoogleTask(
        title = "Base title",
        notes = "base notes",
        dueDate = 1_000L,
        createdDate = 500L,
        etag = "etag-base",
        position = "0000",
    )

    @Test
    fun disjointFieldEdits_bothSurvive() {
        val local = base.copy(title = "Local title")            // local edited title only
        val remote = base.copy(notes = "remote notes", etag = "etag-remote")  // server edited notes

        val merged = merge.merge(base, local, remote, preferLocal = true)

        assertEquals("Local title", merged.title, "local's title edit is kept")
        assertEquals("remote notes", merged.notes, "server's notes edit is kept")
        assertEquals(1_000L, merged.dueDate, "untouched field unchanged")
    }

    @Test
    fun sameFieldConflict_preferLocalTrue_keepsLocal() {
        val local = base.copy(title = "Local title")
        val remote = base.copy(title = "Remote title")

        val merged = merge.merge(base, local, remote, preferLocal = true)

        assertEquals("Local title", merged.title)
    }

    @Test
    fun sameFieldConflict_preferLocalFalse_takesRemote() {
        val local = base.copy(title = "Local title")
        val remote = base.copy(title = "Remote title")

        val merged = merge.merge(base, local, remote, preferLocal = false)

        assertEquals("Remote title", merged.title)
    }

    @Test
    fun nullBase_fillsFieldsLocalNeverSet_fromRemote() {
        // Created locally with a title only; server copy has title + notes.
        val local = GoogleTask(title = "My title")
        val remote = GoogleTask(title = "My title", notes = "notes from another device", etag = "e")

        val merged = merge.merge(null, local, remote, preferLocal = true)

        assertEquals("My title", merged.title)
        assertEquals("notes from another device", merged.notes, "unset local field filled from server")
    }

    @Test
    fun nullBase_contestedField_usesPreferLocal() {
        val local = GoogleTask(title = "Local title")
        val remote = GoogleTask(title = "Server title", notes = "server notes")

        assertEquals("Local title", merge.merge(null, local, remote, preferLocal = true).title)
        assertEquals("Server title", merge.merge(null, local, remote, preferLocal = false).title)
        assertEquals(
            "server notes",
            merge.merge(null, local, remote, preferLocal = true).notes,
            "unset local field still filled regardless of preferLocal",
        )
    }

    @Test
    fun nullBase_localFieldMatchesServer_noConflict() {
        val local = GoogleTask(title = "Same", notes = "same notes")
        val remote = GoogleTask(title = "Same", notes = "same notes", position = "0001")

        val merged = merge.merge(null, local, remote, preferLocal = false)

        assertEquals("Same", merged.title)
        assertEquals("same notes", merged.notes)
        assertEquals("0001", merged.position, "server-owned field still comes from remote")
    }

    @Test
    fun localUnchanged_remoteChanged_takesRemoteValue() {
        val local = base.copy()                       // == base
        val remote = base.copy(dueDate = 2_000L)

        val merged = merge.merge(base, local, remote, preferLocal = true)

        assertEquals(2_000L, merged.dueDate)
    }

    @Test
    fun remoteUnchanged_localChanged_keepsLocalValue() {
        val local = base.copy(dueDate = 3_000L)
        val remote = base.copy()                       // == base

        val merged = merge.merge(base, local, remote, preferLocal = false)

        assertEquals(3_000L, merged.dueDate)
    }

    @Test
    fun serverOwnedFields_alwaysTakenFromRemote_evenWhenLocalDiffers() {
        // Local has a stale etag/position; server moved the task and re-tagged it.
        val local = base.copy(title = "Local title", etag = "etag-stale", position = "0000")
        val remote = base.copy(etag = "etag-fresh", position = "9999", isHidden = true, webViewLink = "https://x")

        val merged = merge.merge(base, local, remote, preferLocal = true)

        assertEquals("etag-fresh", merged.etag)
        assertEquals("9999", merged.position)
        assertEquals(true, merged.isHidden)
        assertEquals("https://x", merged.webViewLink)
        assertEquals("Local title", merged.title, "editable field still merged")
    }

    @Test
    fun clearingAFieldLocally_isARealChange_notTreatedAsUnchanged() {
        val local = base.copy(notes = null)           // user cleared the notes
        val remote = base.copy()                       // server untouched

        val merged = merge.merge(base, local, remote, preferLocal = false)

        assertEquals(null, merged.notes, "a local clear beats an untouched server field")
    }
}
