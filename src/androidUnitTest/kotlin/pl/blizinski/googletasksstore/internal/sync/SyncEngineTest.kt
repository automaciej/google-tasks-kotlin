package pl.blizinski.googletasksstore.internal.sync

import kotlinx.coroutines.test.runTest
import pl.blizinski.googletasksstore.internal.db.OpStatus
import pl.blizinski.googletasksstore.internal.db.OpType
import pl.blizinski.googletasksstore.internal.db.PendingOpEntity
import pl.blizinski.googletasksstore.internal.db.TaskEntity
import pl.blizinski.googletasksstore.internal.db.TaskListEntity
import pl.blizinski.googletasksstore.internal.network.RemoteTask
import pl.blizinski.googletasksstore.internal.network.RemoteTaskList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncEngineTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private val T0 = 1_000_000L  // arbitrary base epoch ms

    private fun store() = FakeLocalTasksStore()
    private fun network() = FakeNetworkTasksSource()

    private fun engine(store: FakeLocalTasksStore, network: FakeNetworkTasksSource): SyncEngine {
        val pendingOps = PendingOpsProcessor(store, network)
        return SyncEngine(store, network, pendingOps)
    }

    private fun remoteList(id: String, title: String = "List $id") =
        RemoteTaskList(remoteId = id, title = title)

    private fun remoteTask(
        id: String,
        listId: String,
        title: String = "Task $id",
        isCompleted: Boolean = false,
        isDeleted: Boolean = false,
    ) = RemoteTask(
        remoteId = id,
        remoteListId = listId,
        title = title,
        notes = null,
        isCompleted = isCompleted,
        isDeleted = isDeleted,
        createdDate = null,
        remoteUpdatedAt = null,
    )

    private fun localList(
        localId: String,
        remoteId: String?,
        lastSyncedAt: Long? = null,
    ) = TaskListEntity(
        localId = localId,
        remoteId = remoteId,
        // Title matches what remoteList() generates for the same id, so syncTaskList
        // does not treat a title change as a reason to update lastSyncedAt.
        title = "List ${remoteId ?: localId}",
        lastSyncedAt = lastSyncedAt,
    )

    private fun localTask(
        localId: String,
        listLocalId: String,
        remoteId: String? = null,
        title: String = "Task $localId",
        isCompleted: Boolean = false,
    ) = TaskEntity(
        localId = localId,
        remoteId = remoteId,
        listLocalId = listLocalId,
        title = title,
        notes = null,
        isCompleted = isCompleted,
        createdDate = null,
        dueDate = null,
        lastSyncedAt = null,
    )

    private fun pendingOp(
        id: String,
        entityLocalId: String,
        listLocalId: String,
        type: OpType = OpType.UPDATE_TASK,
    ) = PendingOpEntity(
        id = id,
        type = type,
        entityLocalId = entityLocalId,
        listLocalId = listLocalId,
        payloadJson = "",
        createdAt = T0,
    )

    // -----------------------------------------------------------------------
    // Full pull — new tasks
    // -----------------------------------------------------------------------

    @Test
    fun fullPull_insertsNewTaskFromServer() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        network.taskListsResponse = listOf(remoteList("RL1"))
        network.tasksResponse["RL1"] = listOf(remoteTask("RT1", "RL1", "My Task"))

        engine(store, network).sync()

        val inserted = store.tasks.values.firstOrNull { it.title == "My Task" }
        assertNotNull(inserted, "Task should be inserted from server")
        assertEquals("RT1", inserted.remoteId)
        assertFalse(inserted.isCompleted)
    }

    @Test
    fun fullPull_deletesLocalTaskAbsentFromServer() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        store.tasks["T1"] = localTask("T1", "L1", remoteId = "RT1")
        network.taskListsResponse = listOf(remoteList("RL1"))
        // Server returns no tasks → T1 was deleted on server
        network.tasksResponse["RL1"] = emptyList()

        engine(store, network).sync()

        assertNull(store.tasks["T1"], "Task absent from server should be hard-deleted locally")
    }

    @Test
    fun fullPull_keepsLocallyCreatedTaskNotOnServer() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        // T_local has no remoteId — it was created locally and not yet pushed
        store.tasks["T_local"] = localTask("T_local", "L1", remoteId = null)
        network.taskListsResponse = listOf(remoteList("RL1"))
        network.tasksResponse["RL1"] = emptyList()

        engine(store, network).sync()

        assertNotNull(store.tasks["T_local"], "Locally-created task (no remoteId) should not be deleted")
    }

    @Test
    fun fullPull_keepsTaskWithPendingOps() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        store.tasks["T1"] = localTask("T1", "L1", remoteId = "RT1")
        store.pendingOps["op1"] = pendingOp("op1", "T1", "L1")
        network.taskListsResponse = listOf(remoteList("RL1"))
        // Server no longer returns RT1 (deleted), but T1 has a pending op — local wins.
        // The pending-entity set is snapshotted before flush, so even if the push
        // succeeds and removes op1, pull() still knows T1 had a pending change.
        network.tasksResponse["RL1"] = emptyList()

        engine(store, network).sync()

        assertNotNull(store.tasks["T1"], "Task with pending op should not be deleted during full pull")
    }

    // -----------------------------------------------------------------------
    // Incremental pull — completion status
    // -----------------------------------------------------------------------

    @Test
    fun incrementalPull_appliesRemoteCompletionToLocalTask() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        store.tasks["T1"] = localTask("T1", "L1", remoteId = "RT1", isCompleted = false)
        network.taskListsResponse = listOf(remoteList("RL1"))
        // Server returns the task as completed in the incremental window
        network.tasksResponse["RL1"] = listOf(remoteTask("RT1", "RL1", isCompleted = true))

        engine(store, network).sync()

        assertTrue(store.tasks["T1"]!!.isCompleted,
            "Task completed on server should be marked completed locally")
    }

    @Test
    fun incrementalPull_doesNotOverwriteTaskWithPendingOps() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        store.tasks["T1"] = localTask("T1", "L1", remoteId = "RT1", isCompleted = false)
        store.pendingOps["op1"] = pendingOp("op1", "T1", "L1", OpType.UPDATE_TASK)
        network.taskListsResponse = listOf(remoteList("RL1"))
        network.tasksResponse["RL1"] = listOf(remoteTask("RT1", "RL1", isCompleted = true))
        // The pending-entity set is snapshotted before flush, so even if the push
        // succeeds the pull cannot overwrite T1 with the stale server state.

        engine(store, network).sync()

        assertFalse(store.tasks["T1"]!!.isCompleted,
            "Task with pending op should not be overwritten by remote state (local wins)")
    }

    @Test
    fun incrementalPull_hardDeletesTaskMarkedDeletedOnServer() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        store.tasks["T1"] = localTask("T1", "L1", remoteId = "RT1")
        network.taskListsResponse = listOf(remoteList("RL1"))
        network.tasksResponse["RL1"] = listOf(remoteTask("RT1", "RL1", isDeleted = true))

        engine(store, network).sync()

        assertNull(store.tasks["T1"], "Task with isDeleted=true from server should be hard-deleted locally")
    }

    // -----------------------------------------------------------------------
    // updatedMin — 60-second buffer
    // -----------------------------------------------------------------------

    @Test
    fun incrementalPull_updatedMinIs60SecondsBefore_lastSyncedAt() = runTest {
        val store = store()
        val network = network()
        val lastSynced = 1_800_000L  // 30 minutes in ms

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = lastSynced)
        // A local task must exist so the hasLocalTasks guard does not override to full pull.
        store.tasks["T1"] = localTask("T1", "L1", remoteId = "RT1")
        network.taskListsResponse = listOf(remoteList("RL1"))
        network.tasksResponse["RL1"] = emptyList()

        engine(store, network).sync()

        val capturedMin = network.updatedMinCapture["RL1"]
        assertNotNull(capturedMin, "updatedMin should be sent for incremental pull")

        // Parse captured updatedMin back to epoch ms and verify it is 60s before lastSyncedAt
        val expected = lastSynced - 60_000L
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
        formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        val parsed = formatter.parse(capturedMin)?.time
        assertEquals(expected, parsed,
            "updatedMin should be lastSyncedAt minus 60 seconds to close the race window")
    }

    @Test
    fun fullPull_sendsNullUpdatedMin_onFirstSync() = runTest {
        val store = store()
        val network = network()

        // lastSyncedAt = null → first sync → full pull
        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        network.taskListsResponse = listOf(remoteList("RL1"))
        network.tasksResponse["RL1"] = emptyList()

        engine(store, network).sync()

        assertNull(network.updatedMinCapture["RL1"],
            "First sync should use full pull (null updatedMin)")
    }

    @Test
    fun newList_firstSyncUsesFullPull() = runTest {
        val store = store()
        val network = network()

        // List is on the server but not yet in the local DB.
        network.taskListsResponse = listOf(remoteList("RL1"))
        network.tasksResponse["RL1"] = listOf(remoteTask("RT1", "RL1", "My Task"))

        engine(store, network).sync()

        assertNull(network.updatedMinCapture["RL1"],
            "First sync for a new list should use full pull (null updatedMin)")
        assertNotNull(store.tasks.values.firstOrNull { it.title == "My Task" },
            "Tasks from full pull should be inserted")
    }

    @Test
    fun listWithNoLocalTasks_usesFullPullDespiteLastSyncedAt() = runTest {
        val store = store()
        val network = network()

        // List is in the local DB with lastSyncedAt set (e.g. from a previous install
        // where tasks were never successfully pulled), but has no local tasks.
        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        network.taskListsResponse = listOf(remoteList("RL1"))
        network.tasksResponse["RL1"] = listOf(remoteTask("RT1", "RL1", "Recovered Task"))

        engine(store, network).sync()

        assertNull(network.updatedMinCapture["RL1"],
            "List with lastSyncedAt set but no local tasks should use full pull (null updatedMin)")
        assertNotNull(store.tasks.values.firstOrNull { it.title == "Recovered Task" },
            "Tasks from recovery full pull should be inserted")
    }

    // -----------------------------------------------------------------------
    // Cross-list task moves (server moves task from one list to another)
    // -----------------------------------------------------------------------

    /**
     * Full pull, source list processed first.
     *
     * Without deferred zombie detection the inline check for RL1 deletes T1 (it's absent
     * from RL1's response) before RL2 has a chance to surface it as the new home.
     */
    @Test
    fun crossListMove_fullPull_sourceFirst_taskReassignedNotDeleted() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        store.taskLists["L2"] = localList("L2", remoteId = "RL2", lastSyncedAt = null)
        store.tasks["T1"] = localTask("T1", listLocalId = "L1", remoteId = "RT1")

        // RL1 first: T1 absent. RL2 second: T1 present.
        network.taskListsResponse = listOf(remoteList("RL1"), remoteList("RL2"))
        network.tasksResponse["RL1"] = emptyList()
        network.tasksResponse["RL2"] = listOf(remoteTask("RT1", "RL2"))

        engine(store, network).sync()

        val task = store.tasks["T1"]
        assertNotNull(task, "Task moved to another list should not be deleted")
        assertEquals("L2", task.listLocalId, "Task should be reassigned to the destination list")
    }

    /**
     * Incremental pull: when the task appears in the destination list's response within the
     * updatedMin window (recently moved), the listChanged detection reassigns it.
     */
    @Test
    fun crossListMove_incrementalPull_taskAppearsInDestination_reassigned() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        store.taskLists["L2"] = localList("L2", remoteId = "RL2", lastSyncedAt = T0)
        store.tasks["T1"] = localTask("T1", listLocalId = "L1", remoteId = "RT1")
        store.tasks["T2"] = localTask("T2", listLocalId = "L2", remoteId = "RT2")

        network.taskListsResponse = listOf(remoteList("RL1"), remoteList("RL2"))
        network.tasksResponse["RL1"] = emptyList()
        // T1 appears in RL2's incremental window (recently moved).
        network.tasksResponse["RL2"] = listOf(remoteTask("RT2", "RL2"), remoteTask("RT1", "RL2"))

        engine(store, network).sync()

        val task = store.tasks["T1"]
        assertNotNull(task, "Task should exist after incremental reassignment")
        assertEquals("L2", task.listLocalId, "Task should be reassigned to the destination list")
    }

    /**
     * Incremental pull: isDeleted from the source list must not delete a task that has
     * already been reassigned to the destination list in the same sync cycle.
     */
    @Test
    fun crossListMove_isDeletedFromSourceList_doesNotDeleteReassignedTask() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = T0)
        store.taskLists["L2"] = localList("L2", remoteId = "RL2", lastSyncedAt = T0)
        store.tasks["T1"] = localTask("T1", listLocalId = "L1", remoteId = "RT1")
        store.tasks["T2"] = localTask("T2", listLocalId = "L2", remoteId = "RT2")

        // RL2 first: reassignment fires. RL1 second: isDeleted signal arrives too late.
        network.taskListsResponse = listOf(remoteList("RL2"), remoteList("RL1"))
        network.tasksResponse["RL2"] = listOf(remoteTask("RT2", "RL2"), remoteTask("RT1", "RL2"))
        network.tasksResponse["RL1"] = listOf(remoteTask("RT1", "RL1", isDeleted = true))

        engine(store, network).sync()

        val task = store.tasks["T1"]
        assertNotNull(task, "Task should not be deleted by isDeleted from the source list")
        assertEquals("L2", task.listLocalId, "Task should remain in the destination list")
    }

    // -----------------------------------------------------------------------
    // Full pull — completed task preservation
    // -----------------------------------------------------------------------

    /**
     * Full pull uses showCompleted=false, so completed tasks are absent from the server
     * response by design. They must not be treated as zombies and deleted.
     */
    @Test
    fun fullPull_keepsLocallyCompletedTask() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1", lastSyncedAt = null)
        // Task was previously completed (e.g. pulled in via an earlier incremental sync).
        store.tasks["T_completed"] = localTask("T_completed", "L1", remoteId = "RT_completed", isCompleted = true)
        store.tasks["T_active"] = localTask("T_active", "L1", remoteId = "RT_active")
        network.taskListsResponse = listOf(remoteList("RL1"))
        // Full pull returns only the active task — completed task absent because showCompleted=false.
        network.tasksResponse["RL1"] = listOf(remoteTask("RT_active", "RL1"))

        engine(store, network).sync()

        assertNotNull(store.tasks["T_completed"],
            "Completed task must not be deleted by full pull (absent only because showCompleted=false)")
        assertNotNull(store.tasks["T_active"], "Active task should still be present")
    }

    // -----------------------------------------------------------------------
    // Zombie list detection
    // -----------------------------------------------------------------------

    @Test
    fun zombieList_isDeletedLocallyWhenAbsentFromServer() = runTest {
        val store = store()
        val network = network()

        // Default list (index 0) still on server
        store.taskLists["L_default"] = localList("L_default", remoteId = "RL_default")
        // Zombie list no longer on server
        store.taskLists["L_zombie"] = localList("L_zombie", remoteId = "RL_zombie")

        network.taskListsResponse = listOf(remoteList("RL_default"))
        network.tasksResponse["RL_default"] = emptyList()

        engine(store, network).sync()

        assertNull(store.taskLists["L_zombie"],
            "Local list absent from server response should be hard-deleted")
    }

    @Test
    fun zombieList_locallyCreatedTaskIsReassignedToDefaultList() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L_default"] = localList("L_default", remoteId = "RL_default")
        store.taskLists["L_zombie"] = localList("L_zombie", remoteId = "RL_zombie")
        // Task with no remoteId — created locally, not yet pushed to server
        store.tasks["T_local"] = localTask("T_local", "L_zombie", remoteId = null)

        network.taskListsResponse = listOf(remoteList("RL_default"))
        network.tasksResponse["RL_default"] = emptyList()

        engine(store, network).sync()

        val reassigned = store.tasks["T_local"]
        assertNotNull(reassigned, "Locally-created task should survive zombie list deletion")
        assertEquals("L_default", reassigned.listLocalId,
            "Locally-created task should be reassigned to the default list")
    }

    @Test
    fun zombieList_syncedTaskIsDeletedWithList() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L_default"] = localList("L_default", remoteId = "RL_default")
        store.taskLists["L_zombie"] = localList("L_zombie", remoteId = "RL_zombie")
        // Task that was previously synced (has remoteId) — server already deleted it with the list
        store.tasks["T_synced"] = localTask("T_synced", "L_zombie", remoteId = "RT_synced")

        network.taskListsResponse = listOf(remoteList("RL_default"))
        network.tasksResponse["RL_default"] = emptyList()

        engine(store, network).sync()

        assertNull(store.tasks["T_synced"],
            "Synced task in zombie list should be hard-deleted with the list")
    }

    @Test
    fun zombieList_pendingOpsForReassignedTaskPointToNewList() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L_default"] = localList("L_default", remoteId = "RL_default")
        store.taskLists["L_zombie"] = localList("L_zombie", remoteId = "RL_zombie")
        store.tasks["T_local"] = localTask("T_local", "L_zombie", remoteId = null)
        store.pendingOps["op1"] = pendingOp("op1", "T_local", "L_zombie", OpType.CREATE_TASK)

        network.taskListsResponse = listOf(remoteList("RL_default"))
        network.tasksResponse["RL_default"] = emptyList()
        // Simulate the zombie list returning 404 so the CREATE push fails and the
        // pending op remains for the zombie detection + reassignment path.
        network.failingListIds.add("RL_zombie")

        engine(store, network).sync()

        val op = store.pendingOps.values.firstOrNull { it.entityLocalId == "T_local" }
        assertNotNull(op, "CREATE op for reassigned task should still exist")
        assertEquals("L_default", op.listLocalId,
            "Pending op's listLocalId should be updated to the new list after reassignment")
    }
}
