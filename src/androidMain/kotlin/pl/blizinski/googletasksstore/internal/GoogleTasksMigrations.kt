package pl.blizinski.googletasksstore.internal

import android.database.Cursor
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Migrates from the pre-rewrite `GoogleTasksDatabase` schema (version 5: named-column
 * `tasks`/`task_lists`/`pending_ops` tables) onto the shared `TaskSyncDatabase` schema
 * (`synced_records`/`synced_lists`/`pending_ops` with opaque `contentJson`), preserving every
 * `localId`/`remoteId` so that other local stores referencing them — TaskCompass's comparisons
 * and workspace-list-memberships — keep working.
 *
 * Without this migration, `TaskSyncDatabase`'s version (6, opening the same on-disk file name
 * the old database used) is read by Room as a downgrade from the on-disk version 5, which
 * forces a destructive recreate that discards every local ID. That is exactly what happened in
 * production before this migration was written — see
 * Docs/designs/2026-07-08-shared-task-sync-engine.md's implementation log for the incident.
 */
val MIGRATION_5_6: Migration = object : Migration(5, 6) {
    private val json = Json { ignoreUnknownKeys = true }

    override fun migrate(db: SupportSQLiteDatabase) {
        val database = db
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `synced_records` (`localId` TEXT NOT NULL, `remoteId` TEXT, " +
                "`listLocalId` TEXT NOT NULL, `contentJson` TEXT NOT NULL, `isCompleted` INTEGER NOT NULL, " +
                "`isDeleted` INTEGER NOT NULL, `lastSyncedAt` INTEGER, `remoteUpdatedAt` INTEGER, " +
                "`lastSyncedContentJson` TEXT, PRIMARY KEY(`localId`))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `synced_lists` (`localId` TEXT NOT NULL, `remoteId` TEXT, " +
                "`contentJson` TEXT NOT NULL, `isDeleted` INTEGER NOT NULL, `lastSyncedAt` INTEGER, " +
                "`position` INTEGER NOT NULL, PRIMARY KEY(`localId`))"
        )
        database.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_ops_new` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`entityLocalId` TEXT NOT NULL, `listLocalId` TEXT NOT NULL, `contentJson` TEXT, " +
                "`createdAt` INTEGER NOT NULL, `attemptCount` INTEGER NOT NULL, `status` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))"
        )

        // Old pending CREATE_TASK/UPDATE_TASK ops carried only a partial-diff payload (unchanged
        // fields were read from the task row at flush time). The task row itself already
        // reflects any pending edit (writes are applied locally first, per this library's
        // optimistic-write contract), so the full content this migration just built for each
        // task is exactly the full content those ops would have flushed. Keep it around to
        // reuse when migrating pending_ops below.
        val taskContentByLocalId = mutableMapOf<String, String>()

        database.query("SELECT * FROM tasks").use { cursor ->
            val col = ColumnLookup(cursor)
            while (cursor.moveToNext()) {
                val localId = cursor.getString(col("localId"))
                val content = GoogleTask(
                    title = cursor.getString(col("title")),
                    notes = cursor.stringOrNull(col("notes")),
                    createdDate = cursor.longOrNull(col("createdDate")),
                    dueDate = cursor.longOrNull(col("dueDate")),
                    parentId = cursor.stringOrNull(col("parentId")),
                    position = cursor.stringOrNull(col("position")),
                    etag = cursor.stringOrNull(col("etag")),
                    completedDate = cursor.longOrNull(col("completedDate")),
                    isHidden = cursor.getInt(col("isHidden")) != 0,
                    webViewLink = cursor.stringOrNull(col("webViewLink")),
                    linksJson = cursor.stringOrNull(col("linksJson")),
                    assignmentInfoJson = cursor.stringOrNull(col("assignmentInfoJson")),
                )
                val contentJson = json.encodeToString(content)
                taskContentByLocalId[localId] = contentJson

                database.execSQL(
                    "INSERT INTO synced_records (localId, remoteId, listLocalId, contentJson, " +
                        "isCompleted, isDeleted, lastSyncedAt, remoteUpdatedAt, lastSyncedContentJson) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NULL)",
                    arrayOf<Any?>(
                        localId,
                        cursor.stringOrNull(col("remoteId")),
                        cursor.getString(col("listLocalId")),
                        contentJson,
                        cursor.getInt(col("isCompleted")),
                        cursor.getInt(col("isDeleted")),
                        cursor.longOrNull(col("lastSyncedAt")),
                        cursor.longOrNull(col("remoteUpdatedAt")),
                    ),
                )
            }
        }

        database.query("SELECT * FROM task_lists").use { cursor ->
            val col = ColumnLookup(cursor)
            while (cursor.moveToNext()) {
                val content = GoogleTaskList(title = cursor.getString(col("title")))
                database.execSQL(
                    "INSERT INTO synced_lists (localId, remoteId, contentJson, isDeleted, lastSyncedAt, position) " +
                        "VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        cursor.getString(col("localId")),
                        cursor.stringOrNull(col("remoteId")),
                        json.encodeToString(content),
                        cursor.getInt(col("isDeleted")),
                        cursor.longOrNull(col("lastSyncedAt")),
                        cursor.getInt(col("position")),
                    ),
                )
            }
        }

        database.query("SELECT * FROM pending_ops").use { cursor ->
            val col = ColumnLookup(cursor)
            while (cursor.moveToNext()) {
                val entityLocalId = cursor.getString(col("entityLocalId"))
                val payloadJson = cursor.stringOrNull(col("payloadJson")) ?: ""
                val newType = when (cursor.getString(col("type"))) {
                    "CREATE_TASK" -> "CREATE_RECORD"
                    "UPDATE_TASK" -> "UPDATE_RECORD"
                    "COMPLETE_TASK" -> "COMPLETE_RECORD"
                    "DELETE_TASK" -> "DELETE_RECORD"
                    "CREATE_LIST" -> "CREATE_LIST"
                    "UPDATE_LIST" -> "UPDATE_LIST"
                    "DELETE_LIST" -> "DELETE_LIST"
                    else -> continue // unknown/future op type — nothing safe to translate to
                }
                val newContentJson = when (newType) {
                    "CREATE_RECORD", "UPDATE_RECORD" -> taskContentByLocalId[entityLocalId]
                    "DELETE_LIST" -> payloadJson.ifEmpty { null }
                    else -> null
                }
                database.execSQL(
                    "INSERT INTO pending_ops_new (id, type, entityLocalId, listLocalId, contentJson, " +
                        "createdAt, attemptCount, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    arrayOf<Any?>(
                        cursor.getString(col("id")),
                        newType,
                        entityLocalId,
                        cursor.getString(col("listLocalId")),
                        newContentJson,
                        cursor.getLong(col("createdAt")),
                        cursor.getInt(col("attemptCount")),
                        cursor.getString(col("status")),
                    ),
                )
            }
        }

        database.execSQL("DROP TABLE tasks")
        database.execSQL("DROP TABLE task_lists")
        database.execSQL("DROP TABLE pending_ops")
        database.execSQL("ALTER TABLE pending_ops_new RENAME TO pending_ops")
    }
}

private class ColumnLookup(private val cursor: Cursor) {
    operator fun invoke(name: String): Int = cursor.getColumnIndexOrThrow(name)
}

private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
private fun Cursor.longOrNull(index: Int): Long? = if (isNull(index)) null else getLong(index)
