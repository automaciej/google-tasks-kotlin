package pl.blizinski.googletasksstore.internal.sync

import android.util.Log
import pl.blizinski.googletasksstore.internal.LocalTasksStore
import pl.blizinski.googletasksstore.internal.db.OpStatus
import pl.blizinski.googletasksstore.internal.db.OpType
import pl.blizinski.googletasksstore.internal.db.PendingOpEntity
import pl.blizinski.googletasksstore.internal.network.NetworkTasksSource
import pl.blizinski.googletasksstore.models.SyncError
import pl.blizinski.googletasksstore.models.SyncErrorKind
import pl.blizinski.googletasksstore.toRfc3339DueDate

private const val TAG = "PendingOps"

/**
 * Flushes locally-queued mutations to the network in [createdAt] order,
 * applying op-merging rules before sending.
 *
 * Returns a list of [SyncError]s for any ops that failed.
 */
internal class PendingOpsProcessor(
    private val store: LocalTasksStore,
    private val network: NetworkTasksSource,
) {

    suspend fun flush(): List<SyncError> {
        val ops = store.getAllPendingOps()
        if (ops.isEmpty()) return emptyList()

        val errors = mutableListOf<SyncError>()

        // Group ops by entity, preserve per-entity order (getAll returns ASC)
        val byEntity = ops.groupBy { it.entityLocalId }

        for ((entityLocalId, entityOps) in byEntity) {
            val merged = merge(entityOps)
            if (merged.isEmpty()) {
                // CREATE + DELETE cancelled each other — clean up local state
                store.removeAllPendingOpsForEntity(entityLocalId)
                if (entityOps.any { it.isListOp() }) store.hardDeleteTaskList(entityLocalId)
                else store.hardDeleteTask(entityLocalId)
                continue
            }
            errors += flushEntityOps(entityLocalId, merged)
        }

        return errors
    }

    // -----------------------------------------------------------------------
    // Op merging
    // -----------------------------------------------------------------------

    /**
     * Reduces a per-entity op list before sending to the network.
     *
     * Rules (applied in sequence):
     * - CREATE + DELETE → empty (never touch the server)
     * - UPDATE + DELETE → [DELETE] (skip the update)
     * - UPDATE + COMPLETE → [UPDATE, COMPLETE] (both in order)   [task ops only]
     * - Consecutive UPDATEs → last UPDATE wins (merge payloads not yet needed)
     */
    internal fun merge(ops: List<PendingOpEntity>): List<PendingOpEntity> {
        if (ops.isEmpty()) return emptyList()

        // List ops and task ops cannot be mixed for the same entity.
        if (ops.first().isListOp()) return mergeListOps(ops)

        val hasCreate = ops.any { it.type == OpType.CREATE_TASK }
        val hasDelete = ops.any { it.type == OpType.DELETE_TASK }

        if (hasCreate && hasDelete) return emptyList()

        val result = mutableListOf<PendingOpEntity>()
        if (hasCreate) result += ops.first { it.type == OpType.CREATE_TASK }

        if (hasDelete) {
            // Only DELETE needs to be sent; skip any preceding UPDATEs/COMPLETEs
            result += ops.last { it.type == OpType.DELETE_TASK }
            return result
        }

        // No DELETE — include the last UPDATE (if any) and all COMPLETEs
        ops.lastOrNull { it.type == OpType.UPDATE_TASK }?.let { result += it }
        ops.filter { it.type == OpType.COMPLETE_TASK }.forEach { result += it }

        return result.sortedBy { it.createdAt }
    }

    private fun mergeListOps(ops: List<PendingOpEntity>): List<PendingOpEntity> {
        val hasCreate = ops.any { it.type == OpType.CREATE_LIST }
        val hasDelete = ops.any { it.type == OpType.DELETE_LIST }

        if (hasCreate && hasDelete) return emptyList()

        val result = mutableListOf<PendingOpEntity>()
        if (hasCreate) result += ops.first { it.type == OpType.CREATE_LIST }

        if (hasDelete) {
            result += ops.last { it.type == OpType.DELETE_LIST }
            return result
        }

        ops.lastOrNull { it.type == OpType.UPDATE_LIST }?.let { result += it }
        return result
    }

    // -----------------------------------------------------------------------
    // Per-entity flush
    // -----------------------------------------------------------------------

    private suspend fun flushEntityOps(
        entityLocalId: String,
        ops: List<PendingOpEntity>,
    ): List<SyncError> {
        val errors = mutableListOf<SyncError>()

        for (op in ops) {
            val error = executeOp(op)
            if (error != null) {
                errors += error
                store.recordPendingOpAttempt(op.id, OpStatus.FAILED)
                // Abort remaining ops for this entity — they may depend on
                // prior ops succeeding (e.g. COMPLETE requires remoteId from CREATE)
                break
            } else {
                store.removePendingOp(op.id)
            }
        }

        return errors
    }

    private suspend fun executeOp(op: PendingOpEntity): SyncError? {
        return try {
            when (op.type) {
                OpType.CREATE_TASK -> executeCreate(op)
                OpType.UPDATE_TASK -> executeUpdate(op)
                OpType.COMPLETE_TASK -> executeComplete(op)
                OpType.DELETE_TASK -> executeDelete(op)
                OpType.CREATE_LIST -> executeCreateList(op)
                OpType.UPDATE_LIST -> executeUpdateList(op)
                OpType.DELETE_LIST -> executeDeleteList(op)
            }
            null
        } catch (e: Exception) {
            SyncError(
                occurredAt = System.currentTimeMillis(),
                kind = when {
                    e.isAdvancedProtectionError() -> SyncErrorKind.ADVANCED_PROTECTION
                    else -> SyncErrorKind.PUSH_FAILED
                },
                taskLocalId = op.entityLocalId,
                httpStatus = e.httpStatusOrNull(),
                message = e.message ?: "Unknown error",
            )
        }
    }

    private suspend fun executeCreate(op: PendingOpEntity) {
        val entity = store.getTaskByLocalId(op.entityLocalId) ?: run {
            Log.w(TAG, "executeCreate: local task ${op.entityLocalId} not found — skipping")
            return
        }
        val listEntity = store.getTaskListByLocalId(op.listLocalId) ?: run {
            Log.w(TAG, "executeCreate: list ${op.listLocalId} not found locally — skipping")
            return
        }
        val remoteListId = listEntity.remoteId ?: run {
            Log.w(TAG, "executeCreate: list ${op.listLocalId} has no remoteId yet — skipping")
            return
        }

        val payload = op.payloadJson.parseCreatePayload()
        Log.d(TAG, "executeCreate: pushing '${payload.title}' to remoteList=$remoteListId")
        val remote = network.createTask(
            remoteListId, payload.title, payload.notes,
            dueDate = payload.dueDate?.toRfc3339DueDate(),
        )
        Log.d(TAG, "executeCreate: success — local=${op.entityLocalId} remote=${remote.remoteId}")

        // Write the server-assigned remoteId back to the local entity
        store.updateTaskRemoteId(entity.localId, remote.remoteId)
    }

    private suspend fun executeUpdate(op: PendingOpEntity) {
        val entity = store.getTaskByLocalId(op.entityLocalId) ?: return
        val remoteId = entity.remoteId ?: return  // no remoteId means CREATE not yet flushed
        val listEntity = store.getTaskListByLocalId(op.listLocalId) ?: return
        val remoteListId = listEntity.remoteId ?: return

        val payload = op.payloadJson.parseUpdatePayload(entity)
        network.updateTask(
            remoteListId, remoteId, payload.title, payload.notes,
            dueDate = payload.dueDate?.toRfc3339DueDate(),
            isCompleted = payload.isCompleted,
        )
    }

    private suspend fun executeComplete(op: PendingOpEntity) {
        val entity = store.getTaskByLocalId(op.entityLocalId) ?: return
        val remoteId = entity.remoteId ?: return
        val listEntity = store.getTaskListByLocalId(op.listLocalId) ?: return
        val remoteListId = listEntity.remoteId ?: return

        network.completeTask(remoteListId, remoteId)
    }

    private suspend fun executeDelete(op: PendingOpEntity) {
        val entity = store.getTaskByLocalId(op.entityLocalId) ?: return
        val remoteId = entity.remoteId
        if (remoteId == null) {
            // Task was never synced — nothing to delete on the server
            store.hardDeleteTask(op.entityLocalId)
            return
        }
        val listEntity = store.getTaskListByLocalId(op.listLocalId) ?: return
        val remoteListId = listEntity.remoteId ?: return

        network.deleteTask(remoteListId, remoteId)
        store.hardDeleteTask(op.entityLocalId)
    }

    private suspend fun executeCreateList(op: PendingOpEntity) {
        val entity = store.getTaskListByLocalId(op.entityLocalId) ?: run {
            Log.w(TAG, "executeCreateList: local list ${op.entityLocalId} not found — skipping")
            return
        }
        val remote = network.createTaskList(entity.title)
        Log.d(TAG, "executeCreateList: success — local=${op.entityLocalId} remote=${remote.remoteId}")
        store.upsertTaskList(entity.copy(remoteId = remote.remoteId))
    }

    private suspend fun executeUpdateList(op: PendingOpEntity) {
        val entity = store.getTaskListByLocalId(op.entityLocalId) ?: return
        val remoteId = entity.remoteId ?: return  // CREATE not yet flushed; skip
        network.updateTaskList(remoteId, entity.title)
    }

    private suspend fun executeDeleteList(op: PendingOpEntity) {
        // remoteId is saved in payloadJson at deletion time so it survives soft-delete
        val remoteId = op.payloadJson.ifEmpty { null }
            ?: store.getTaskListByLocalId(op.entityLocalId)?.remoteId
            ?: run {
                Log.w(TAG, "executeDeleteList: no remoteId for ${op.entityLocalId} — skipping")
                return
            }
        Log.d(TAG, "executeDeleteList: deleting remote list $remoteId")
        network.deleteTaskList(remoteId)
        store.hardDeleteTaskList(op.entityLocalId)
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun PendingOpEntity.isListOp() =
    type == OpType.CREATE_LIST || type == OpType.UPDATE_LIST || type == OpType.DELETE_LIST

// ---------------------------------------------------------------------------
// Payload parsing helpers
// ---------------------------------------------------------------------------

private data class CreatePayload(val title: String, val notes: String?, val dueDate: Long?)
private data class UpdatePayload(val title: String, val notes: String?, val dueDate: Long?, val isCompleted: Boolean? = null)

/**
 * For CREATE ops the payload holds title, notes, and dueDate set at creation time.
 */
private fun String.parseCreatePayload(): CreatePayload {
    if (isEmpty()) return CreatePayload("", null, null)
    val json = org.json.JSONObject(this)
    return CreatePayload(
        title = json.optString("title", ""),
        notes = if (json.isNull("notes")) null else json.optString("notes"),
        dueDate = if (json.isNull("dueDate")) null else json.optLong("dueDate").takeIf { it != 0L },
    )
}

/**
 * For UPDATE ops the payload holds only changed fields; unchanged fields
 * fall back to the current entity values.
 */
private fun String.parseUpdatePayload(
    entity: pl.blizinski.googletasksstore.internal.db.TaskEntity,
): UpdatePayload {
    if (isEmpty()) return UpdatePayload(entity.title, entity.notes, entity.dueDate)
    val json = org.json.JSONObject(this)
    return UpdatePayload(
        title = if (json.has("title")) json.getString("title") else entity.title,
        notes = when {
            json.has("notes") && json.isNull("notes") -> null
            json.has("notes") -> json.getString("notes")
            else -> entity.notes
        },
        dueDate = when {
            json.has("dueDate") && json.isNull("dueDate") -> null
            json.has("dueDate") -> json.getLong("dueDate")
            else -> entity.dueDate
        },
        isCompleted = if (json.has("isCompleted")) json.getBoolean("isCompleted") else null,
    )
}

