package pl.blizinski.googletasksstore

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

data class GoogleTasksStoreConfig(
    val minPollInterval: Duration = 1.minutes,
    val maxPollInterval: Duration = 30.minutes,
    val dbName: String = "google_tasks_store",
    val maxRecentErrors: Int = 50,
)
