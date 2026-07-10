package pl.blizinski.googletasksstore

import kotlinx.coroutines.flow.Flow
import pl.blizinski.googletasksstore.models.SyncStatus
import pl.blizinski.googletasksstore.models.Task
import pl.blizinski.googletasksstore.models.TaskList

/**
 * Public contract for the Google Tasks local store. Implemented by
 * [GoogleTasksStore]; can be faked in tests without Android framework deps.
 */
interface TaskStoreApi {

    // --- Read ---

    fun taskLists(): Flow<List<TaskList>>

    /** [listLocalId] is the [TaskList.id] returned by [taskLists]. */
    fun tasks(listLocalId: String): Flow<List<Task>>

    fun syncStatus(): Flow<SyncStatus>

    // --- Write (optimistic — applied locally, synced in background) ---

    /** Creates a task list and returns its stable localId. */
    suspend fun createList(title: String): String

    /** Renames a task list. */
    suspend fun updateList(localId: String, title: String)

    /**
     * Deletes a task list and all its tasks. Locally-created tasks (not yet synced)
     * are removed immediately; synced tasks are cleaned up after the server confirms.
     */
    suspend fun deleteList(localId: String)

    /** Creates a task and returns its stable [Task.id] localId. */
    suspend fun createTask(listLocalId: String, title: String, notes: String? = null, dueDate: Long? = null): String

    /** Updates title, notes, and due date. Pass null to clear that field. */
    suspend fun updateTask(localId: String, title: String, notes: String?, dueDate: Long? = null)

    suspend fun completeTask(localId: String)

    suspend fun uncompleteTask(localId: String)

    suspend fun deleteTask(localId: String)

    /**
     * Moves a task to [destListLocalId] in place, preserving [localId] — Google Tasks supports
     * moving a task between lists natively (`tasks.move` with `destinationTasklist`), so callers
     * never need to fall back to delete-and-recreate for this source.
     */
    suspend fun moveTask(localId: String, destListLocalId: String)

    // --- Lifecycle ---

    /** Runs a full sync cycle synchronously (flush pending ops, then pull). */
    suspend fun forceSync()

    /** Cancels background work and closes the database. */
    fun close()
}
