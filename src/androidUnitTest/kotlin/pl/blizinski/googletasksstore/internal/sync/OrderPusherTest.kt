package pl.blizinski.googletasksstore.internal.sync

import kotlinx.coroutines.test.runTest
import pl.blizinski.googletasksstore.internal.db.TaskEntity
import pl.blizinski.googletasksstore.internal.db.TaskListEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrderPusherTest {

    private fun store() = FakeLocalTasksStore()
    private fun network() = FakeNetworkTasksSource()
    private fun pusher(store: FakeLocalTasksStore, network: FakeNetworkTasksSource) =
        OrderPusher(store, network)

    private fun localList(localId: String, remoteId: String) = TaskListEntity(
        localId = localId,
        remoteId = remoteId,
        title = "List $localId",
        lastSyncedAt = null,
    )

    private fun localTask(
        localId: String,
        listLocalId: String,
        remoteId: String?,
    ) = TaskEntity(
        localId = localId,
        remoteId = remoteId,
        listLocalId = listLocalId,
        title = "Task $localId",
        notes = null,
        isCompleted = false,
        createdDate = null,
        dueDate = null,
        lastSyncedAt = null,
    )

    @Test
    fun push_movesTasksInOrder_withCorrectPreviousIds() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1")
        store.tasks["A"] = localTask("A", "L1", remoteId = "RA")
        store.tasks["B"] = localTask("B", "L1", remoteId = "RB")
        store.tasks["C"] = localTask("C", "L1", remoteId = "RC")

        pusher(store, network).push(listOf("A", "B", "C"))

        assertEquals(3, network.moveTaskCalls.size)
        assertEquals("RA" to null,  network.moveTaskCalls[0])  // first → top
        assertEquals("RB" to "RA",  network.moveTaskCalls[1])  // second after first
        assertEquals("RC" to "RB",  network.moveTaskCalls[2])  // third after second
    }

    @Test
    fun push_skipsUnsyncedTask_andContinuesChain() = runTest {
        val store = store()
        val network = network()

        store.taskLists["L1"] = localList("L1", remoteId = "RL1")
        store.tasks["A"] = localTask("A", "L1", remoteId = "RA")
        store.tasks["B"] = localTask("B", "L1", remoteId = null)  // not yet synced
        store.tasks["C"] = localTask("C", "L1", remoteId = "RC")

        pusher(store, network).push(listOf("A", "B", "C"))

        assertEquals(2, network.moveTaskCalls.size)
        assertEquals("RA" to null,  network.moveTaskCalls[0])  // A to top
        assertEquals("RC" to "RA",  network.moveTaskCalls[1])  // C after A (B skipped)
    }

    @Test
    fun push_emptyList_makesNoCalls() = runTest {
        val store = store()
        val network = network()

        pusher(store, network).push(emptyList())

        assertTrue(network.moveTaskCalls.isEmpty())
    }
}
