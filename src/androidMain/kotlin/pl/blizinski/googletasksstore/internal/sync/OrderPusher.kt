package pl.blizinski.googletasksstore.internal.sync

import android.util.Log
import pl.blizinski.googletasksstore.internal.LocalTasksStore
import pl.blizinski.googletasksstore.internal.network.NetworkTasksSource

private const val TAG = "OrderPusher"

/**
 * Moves the tasks identified by [localTaskIds] to the top of their list on the
 * server, in the given order, using the Google Tasks `move` API.
 *
 * - The first task is moved to position 1 (previous = null).
 * - Each subsequent task is placed immediately after the preceding one.
 * - Tasks without a remoteId (not yet synced) are silently skipped; the chain
 *   continues with the next task.
 */
internal class OrderPusher(
    private val store: LocalTasksStore,
    private val network: NetworkTasksSource,
) {
    suspend fun push(localTaskIds: List<String>) {
        var previousRemoteId: String? = null
        for (localId in localTaskIds) {
            val task = store.getTaskByLocalId(localId) ?: continue
            val remoteTaskId = task.remoteId ?: run {
                Log.d(TAG, "push: skipping $localId — no remoteId yet")
                continue
            }
            val list = store.getTaskListByLocalId(task.listLocalId) ?: continue
            val remoteListId = list.remoteId ?: continue
            Log.d(TAG, "push: moving $remoteTaskId after $previousRemoteId")
            network.moveTask(remoteListId, remoteTaskId, previousRemoteId)
            previousRemoteId = remoteTaskId
        }
    }
}
