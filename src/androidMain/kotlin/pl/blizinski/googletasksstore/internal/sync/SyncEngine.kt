package pl.blizinski.googletasksstore.internal.sync

import android.util.Log
import pl.blizinski.googletasksstore.internal.LocalTasksStore
import pl.blizinski.googletasksstore.internal.db.TaskEntity
import pl.blizinski.googletasksstore.internal.db.TaskListEntity
import pl.blizinski.googletasksstore.internal.db.TaskSnapshot
import pl.blizinski.googletasksstore.internal.network.NetworkTasksSource
import pl.blizinski.googletasksstore.internal.network.RemoteTask
import pl.blizinski.googletasksstore.internal.network.RemoteTaskList
import pl.blizinski.googletasksstore.models.SyncError
import pl.blizinski.googletasksstore.models.SyncErrorKind
import pl.blizinski.googletasksstore.parseRfc3339ToEpochMs
import pl.blizinski.googletasksstore.toRfc3339DueDate
import java.util.UUID

private const val TAG = "SyncEngine"

/**
 * Orchestrates a full sync cycle: flush pending ops then pull from the server.
 *
 * Pull rules (apply to both full and incremental modes):
 * - Tasks with pending ops are skipped (local wins).
 * - New server tasks are inserted with a fresh localId.
 * - Existing tasks without pending ops are updated to the server state.
 *
 * Full pull (first sync, updatedMin == null):
 * - Local tasks whose remoteId is absent from the server response are
 *   hard-deleted (server deleted them).
 *
 * Incremental pull (updatedMin == lastSyncedAt of the list):
 * - Only tasks modified since updatedMin are returned by the API.
 * - Deleted tasks are returned with isDeleted == true and hard-deleted locally.
 * - Completed tasks are returned with isCompleted == true and updated locally.
 * - Absence from the response means "unchanged", not "deleted".
 */
internal class SyncEngine(
    private val store: LocalTasksStore,
    private val network: NetworkTasksSource,
    private val pendingOpsProcessor: PendingOpsProcessor,
) {

    data class SyncResult(
        val hasRemoteChanges: Boolean,
        val errors: List<SyncError>,
        /** Non-null when a sync call failed with a recoverable OAuth consent error. */
        val consentIntent: android.content.Intent? = null,
    )

    suspend fun sync(): SyncResult {
        Log.d(TAG, "sync: starting flush+pull")
        // Snapshot pending entity IDs before flush so that tasks whose ops are
        // successfully pushed are still protected during the pull in this same
        // cycle. Without this, a task completed locally could be overwritten by
        // stale server data if the server's read replicas haven't caught up yet.
        val pendingEntityIds = store.getAllPendingOps().map { it.entityLocalId }.toSet()
        val pushErrors = pendingOpsProcessor.flush()
        if (pushErrors.isNotEmpty()) {
            Log.w(TAG, "sync: ${pushErrors.size} push errors: ${pushErrors.map { it.message }}")
        }

        return try {
            val pullResult = pull(pendingEntityIds)
            if (pullResult.errors.isNotEmpty()) {
                Log.w(TAG, "sync: ${pullResult.errors.size} pull errors: ${pullResult.errors.map { it.message }}")
            }
            Log.d(TAG, "sync: done hasRemoteChanges=${pullResult.hasRemoteChanges}")
            SyncResult(
                hasRemoteChanges = pullResult.hasRemoteChanges,
                errors = pushErrors + pullResult.errors,
                consentIntent = pullResult.consentIntent,
            )
        } catch (e: Exception) {
            Log.e(TAG, "sync: pull threw exception", e)
            val consentIntent = e.extractConsentIntent()
            val pullError = SyncError(
                occurredAt = System.currentTimeMillis(),
                kind = when {
                    consentIntent != null -> SyncErrorKind.CONSENT_REQUIRED
                    e.isAdvancedProtectionError() -> SyncErrorKind.ADVANCED_PROTECTION
                    e.isAuthError() -> SyncErrorKind.AUTH_FAILED
                    else -> SyncErrorKind.PULL_FAILED
                },
                taskLocalId = null,
                httpStatus = e.httpStatusOrNull(),
                message = e.message ?: "Unknown error during pull",
            )
            SyncResult(hasRemoteChanges = false, errors = pushErrors + pullError,
                consentIntent = consentIntent)
        }
    }

    // -----------------------------------------------------------------------
    // Pull
    // -----------------------------------------------------------------------

    private data class PullResult(
        val hasRemoteChanges: Boolean,
        val errors: List<SyncError>,
        val consentIntent: android.content.Intent? = null,
    )

    private suspend fun pull(
        pendingEntityIds: Set<String>,
    ): PullResult {
        var hasRemoteChanges = false
        val errors = mutableListOf<SyncError>()
        val now = System.currentTimeMillis()

        val remoteLists = network.getTaskLists()
        Log.d(TAG, "pull: fetched ${remoteLists.size} remote lists")

        // Remote task IDs from full pulls, accumulated across all lists.
        // Zombie detection is deferred until all lists finish so that a task absent from
        // its source list is not deleted before we see it in the destination list.
        val fullPullTaskIds = mutableMapOf<String, Set<String>>() // listLocalId → remoteTaskIds

        for ((position, remoteList) in remoteLists.withIndex()) {
            if (syncTaskList(remoteList, position, now)) hasRemoteChanges = true

            val localList = store.getTaskListByRemoteId(remoteList.remoteId) ?: continue

            // Use the list's lastSyncedAt as updatedMin for incremental pulls.
            // Use null (full pull) on first sync or when the list has no local tasks
            // (recovers lists whose lastSyncedAt was set before tasks were pulled).
            // Subtract a 60-second buffer to close the race window on incremental pulls.
            val hasLocalTasks = store.getAllTaskEntitiesForList(localList.localId).isNotEmpty()
            val updatedMin = if (!hasLocalTasks) null
                             else localList.lastSyncedAt?.minus(60_000L)?.toRfc3339DueDate()
            Log.d(TAG, "pull: list=${localList.localId} " +
                "mode=${if (updatedMin != null) "incremental updatedMin=$updatedMin" else "full"}")

            val remoteTasks = try {
                network.getTasks(remoteList.remoteId, updatedMin)
            } catch (e: Exception) {
                val consentIntent = e.extractConsentIntent()
                if (consentIntent != null) {
                    // Recoverable auth error — surface the consent intent immediately.
                    val error = SyncError(
                        occurredAt = now,
                        kind = SyncErrorKind.CONSENT_REQUIRED,
                        taskLocalId = null,
                        httpStatus = e.httpStatusOrNull(),
                        message = e.message ?: "Consent required for list ${remoteList.remoteId}",
                    )
                    return PullResult(hasRemoteChanges, errors + error, consentIntent)
                }
                errors += SyncError(
                    occurredAt = now,
                    kind = when {
                        e.isAdvancedProtectionError() -> SyncErrorKind.ADVANCED_PROTECTION
                        e.isAuthError() -> SyncErrorKind.AUTH_FAILED
                        else -> SyncErrorKind.PULL_FAILED
                    },
                    taskLocalId = null,
                    httpStatus = e.httpStatusOrNull(),
                    message = e.message ?: "Failed fetching tasks for list ${remoteList.remoteId}",
                )
                continue
            }

            for (remoteTask in remoteTasks) {
                if (syncTask(remoteTask, localList.localId, pendingEntityIds, now)) {
                    hasRemoteChanges = true
                }
            }

            if (updatedMin == null) {
                // Full pull: accumulate remote task IDs for deferred zombie detection.
                fullPullTaskIds[localList.localId] = remoteTasks.mapTo(mutableSetOf()) { it.remoteId }
            }
            // Incremental: deletions signalled by remoteTask.isDeleted handled in syncTask.

            // Advance lastSyncedAt so the next poll uses updatedMin.
            store.upsertTaskList(localList.copy(lastSyncedAt = now))
        }

        // Deferred zombie detection for full-pulled lists.
        // A task is only deleted if its remoteId is absent from every full-pulled list —
        // if it appears in another list's response it moved rather than was deleted.
        if (fullPullTaskIds.isNotEmpty()) {
            val allSeenRemoteIds = fullPullTaskIds.values.flatten().toSet()
            for ((listLocalId, remoteTaskIds) in fullPullTaskIds) {
                val localTasks = store.getAllTaskEntitiesForList(listLocalId)
                for (localTask in localTasks) {
                    val remoteId = localTask.remoteId ?: continue  // locally-created, not on server
                    if (remoteId in remoteTaskIds) continue        // still in this list on server
                    if (remoteId in allSeenRemoteIds) continue     // moved to another list
                    if (localTask.localId in pendingEntityIds) continue
                    // Full pull uses showCompleted=false, so completed tasks are absent from the
                    // response by design — their absence is not a deletion signal. They are only
                    // truly deleted when the server sends isDeleted=true in an incremental pull.
                    if (localTask.isCompleted) continue
                    store.hardDeleteTask(localTask.localId)
                    hasRemoteChanges = true
                }
            }
        }

        // Detect zombie lists: local lists whose remoteId is absent from the server response.
        // Locally-created tasks (no remoteId) are reassigned to the default list (index 0)
        // so user data is not lost. Remaining tasks (already synced, now deleted server-side)
        // are hard-deleted along with the list.
        val remoteListIds = remoteLists.mapTo(mutableSetOf()) { it.remoteId }
        val defaultList = remoteLists.firstOrNull()
            ?.let { store.getTaskListByRemoteId(it.remoteId) }

        for (localList in store.getAllTaskLists()) {
            val remoteId = localList.remoteId ?: continue  // locally-created list, skip
            if (remoteId in remoteListIds) continue        // still on server, skip

            Log.w(TAG, "pull: list ${localList.localId} (remote=$remoteId) absent from server — zombie")

            if (defaultList != null && defaultList.localId != localList.localId) {
                for (task in store.getAllTaskEntitiesForList(localList.localId)) {
                    if (task.remoteId == null) {
                        Log.d(TAG, "pull: reassigning task ${task.localId} to default list ${defaultList.localId}")
                        store.reassignTask(task.localId, defaultList.localId)
                    }
                }
            }

            store.hardDeleteTaskList(localList.localId)
            hasRemoteChanges = true
        }

        return PullResult(hasRemoteChanges, errors)
    }

    // -----------------------------------------------------------------------
    // Per-entity sync helpers
    // -----------------------------------------------------------------------

    /**
     * Upserts a remote task list into the local store.
     * Returns true if the local state changed.
     */
    private suspend fun syncTaskList(remoteList: RemoteTaskList, position: Int, now: Long): Boolean {
        val existing = store.getTaskListByRemoteId(remoteList.remoteId)
        return if (existing == null) {
            store.upsertTaskList(
                TaskListEntity(
                    localId = UUID.randomUUID().toString(),
                    remoteId = remoteList.remoteId,
                    title = remoteList.title,
                    lastSyncedAt = null,  // null → full task pull on the first sync cycle
                    position = position,
                )
            )
            true
        } else if (existing.title != remoteList.title || existing.position != position) {
            store.upsertTaskList(existing.copy(title = remoteList.title, position = position, lastSyncedAt = now))
            true
        } else {
            false
        }
    }

    /**
     * Syncs a single remote task into the local store.
     * Returns true if the local state changed.
     *
     * - If remoteTask.isDeleted: hard-delete the local entity (if any).
     * - If no local entity exists: insert with a fresh localId.
     * - If the local entity has pending ops: skip (local wins).
     * - Otherwise: apply the remote state.
     */
    private suspend fun syncTask(
        remoteTask: RemoteTask,
        listLocalId: String,
        pendingEntityIds: Set<String>,
        now: Long,
    ): Boolean {
        if (remoteTask.isDeleted) {
            val existing = store.getTaskByRemoteId(remoteTask.remoteId) ?: return false
            if (existing.localId in pendingEntityIds) return false  // pending op; local wins
            // If the task was already reassigned to a different list in this sync cycle
            // (cross-list move), the isDeleted signal is from the source list — skip.
            if (existing.listLocalId != listLocalId) return false
            store.hardDeleteTask(existing.localId)
            return true
        }

        val existing = store.getTaskByRemoteId(remoteTask.remoteId)

        val remoteDueDate = remoteTask.dueDate?.parseRfc3339ToEpochMs()
        val remoteCompletedDate = remoteTask.completedDate?.parseRfc3339ToEpochMs()

        val snapshot = TaskSnapshot(
            title = remoteTask.title,
            notes = remoteTask.notes,
            isCompleted = remoteTask.isCompleted,
        )

        return if (existing == null) {
            Log.d(TAG, "syncTask: insert '${remoteTask.title}' completed=${remoteTask.isCompleted}")
            store.upsertTask(
                TaskEntity(
                    localId = UUID.randomUUID().toString(),
                    remoteId = remoteTask.remoteId,
                    listLocalId = listLocalId,
                    title = remoteTask.title,
                    notes = remoteTask.notes,
                    isCompleted = remoteTask.isCompleted,
                    createdDate = remoteTask.createdDate,
                    dueDate = remoteDueDate,
                    lastSyncedAt = now,
                    remoteUpdatedAt = remoteTask.remoteUpdatedAt,
                    parentId = remoteTask.parentId,
                    position = remoteTask.position,
                    etag = remoteTask.etag,
                    completedDate = remoteCompletedDate,
                    isHidden = remoteTask.isHidden,
                    webViewLink = remoteTask.webViewLink,
                    linksJson = remoteTask.linksJson,
                    assignmentInfoJson = remoteTask.assignmentInfoJson,
                ),
                snapshot,
            )
            true
        } else if (existing.localId in pendingEntityIds) {
            Log.d(TAG, "syncTask: skip '${remoteTask.title}' — pending ops")
            false  // pending ops — local wins; skip
        } else {
            val changed = existing.title != remoteTask.title
                || existing.notes != remoteTask.notes
                || existing.isCompleted != remoteTask.isCompleted
                || existing.dueDate != remoteDueDate
                || existing.parentId != remoteTask.parentId
                || existing.position != remoteTask.position
                || existing.isHidden != remoteTask.isHidden
                || existing.completedDate != remoteCompletedDate
                || existing.webViewLink != remoteTask.webViewLink
                || existing.etag != remoteTask.etag
            val listChanged = existing.listLocalId != listLocalId
            if (changed) {
                Log.d(TAG, "syncTask: update '${remoteTask.title}'" +
                    " completed: ${existing.isCompleted}→${remoteTask.isCompleted}")
                store.updateTaskSyncedFields(
                    localId = existing.localId,
                    title = remoteTask.title,
                    notes = remoteTask.notes,
                    isCompleted = remoteTask.isCompleted,
                    dueDate = remoteDueDate,
                    parentId = remoteTask.parentId,
                    lastSyncedAt = now,
                    remoteUpdatedAt = remoteTask.remoteUpdatedAt,
                    snapshot = snapshot,
                    position = remoteTask.position,
                    etag = remoteTask.etag,
                    completedDate = remoteCompletedDate,
                    isHidden = remoteTask.isHidden,
                    webViewLink = remoteTask.webViewLink,
                    linksJson = remoteTask.linksJson,
                    assignmentInfoJson = remoteTask.assignmentInfoJson,
                )
            }
            if (listChanged) {
                Log.d(TAG, "syncTask: reassign '${remoteTask.title}' " +
                    "${existing.listLocalId} → $listLocalId")
                store.reassignTask(existing.localId, listLocalId)
            }
            changed || listChanged
        }
    }
}
