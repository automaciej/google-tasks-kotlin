package pl.blizinski.googletasksstore.internal.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [TaskEntity::class, TaskListEntity::class, PendingOpEntity::class],
    version = 5,
    exportSchema = false,
)
abstract class GoogleTasksDatabase : RoomDatabase() {
    abstract fun tasksDao(): TasksDao
    abstract fun taskListsDao(): TaskListsDao
    abstract fun pendingOpsDao(): PendingOpsDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN dueDate INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE task_lists ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN parentId TEXT")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE tasks ADD COLUMN position TEXT")
                database.execSQL("ALTER TABLE tasks ADD COLUMN etag TEXT")
                database.execSQL("ALTER TABLE tasks ADD COLUMN completedDate INTEGER")
                database.execSQL("ALTER TABLE tasks ADD COLUMN isHidden INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE tasks ADD COLUMN webViewLink TEXT")
                database.execSQL("ALTER TABLE tasks ADD COLUMN linksJson TEXT")
                database.execSQL("ALTER TABLE tasks ADD COLUMN assignmentInfoJson TEXT")
            }
        }
    }
}
