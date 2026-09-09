package pl.blizinski.googletasksstore

import pl.blizinski.tasksync.model.RecurrenceStyle
import pl.blizinski.tasksync.model.StoreCapabilities

/**
 * Static facts about the Google Tasks source, available before any account is connected.
 * [GoogleTasks.store] (androidMain) / [GoogleTasks.wasmStore] (wasmJsMain) build a
 * [pl.blizinski.tasksync.store.TaskStore] for a connected account.
 */
object GoogleTasks {

    /**
     * Google Tasks lists support real create/rename/delete and a native cross-list move.
     * Due dates are date-only (no time-of-day — `supportsDueTime = false`), there is no
     * priority, no labels, and no recurrence field; tasks do have lexicographic manual order
     * and parent/subtask nesting.
     */
    val capabilities = StoreCapabilities(
        supportsDueTime = false,
        supportsPriority = false,
        supportsLabels = false,
        supportsManualOrdering = true,
        supportsSubtasks = true,
        supportsMultipleLists = true,
        supportsListCreation = true,
        supportsManualDelete = true,
        supportsNativeMove = true,
        recurrenceStyle = RecurrenceStyle.NONE,
    )
}
