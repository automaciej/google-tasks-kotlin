package pl.blizinski.googletasksstore.models

/**
 * Dual identifier for a task: a device-local UUID (always set) and the
 * server-assigned ID (populated after the first successful sync).
 *
 * [localId] is stable for the lifetime of this installation.
 * [remoteId] is stable across devices and installations once assigned.
 *
 * Use [matches] for semantic equality (two TaskIds referring to the same
 * underlying task, possibly from different devices).
 */
data class TaskId(
    val localId: String,
    val remoteId: String? = null,
) {
    /**
     * Returns true if both TaskIds refer to the same underlying task.
     * Uses remoteId comparison when both sides have one; falls back to localId.
     */
    fun matches(other: TaskId): Boolean = when {
        remoteId != null && other.remoteId != null -> remoteId == other.remoteId
        else -> localId == other.localId
    }

    /** The most stable available identifier: remoteId once synced, localId before that. */
    val effectiveId: String get() = remoteId ?: localId
}
