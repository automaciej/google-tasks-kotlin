package pl.blizinski.googletasksstore.internal.db

import org.json.JSONObject

/**
 * A snapshot of the mutable fields of a task as of the last successful
 * server pull. Stored as JSON in [TaskEntity.lastSyncedSnapshot] and used
 * as the merge base in three-way conflict resolution.
 */
internal data class TaskSnapshot(
    val title: String,
    val notes: String?,
    val isCompleted: Boolean,
)

internal fun TaskSnapshot.toJson(): String = JSONObject().apply {
    put("title", title)
    put("notes", notes ?: JSONObject.NULL)
    put("isCompleted", isCompleted)
}.toString()

internal fun String.toTaskSnapshot(): TaskSnapshot {
    val json = JSONObject(this)
    return TaskSnapshot(
        title = json.getString("title"),
        notes = if (json.isNull("notes")) null else json.getString("notes"),
        isCompleted = json.getBoolean("isCompleted"),
    )
}

internal fun TaskEntity.toSnapshot(): TaskSnapshot =
    TaskSnapshot(title = title, notes = notes, isCompleted = isCompleted)
