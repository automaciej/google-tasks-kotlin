package pl.blizinski.googletasksstore.internal.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf

/**
 * WorkManager worker that runs one full sync cycle (flush + pull).
 *
 * The worker self-schedules its next run using the adaptive interval: the
 * current interval is passed as [KEY_INTERVAL_MS] input data, and the next
 * interval is computed from [computeNextIntervalMs] based on whether the pull
 * found remote changes.
 *
 * Dependencies ([SyncWorkerDependencies]) are set by [GoogleTasksStore] before
 * the first work request is enqueued. Only one store instance per process is
 * supported.
 */
internal class SyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val currentIntervalMs = inputData.getLong(KEY_INTERVAL_MS, DEFAULT_INTERVAL_MS)

        val deps = SyncWorkerDependencies.current
        if (deps == null) {
            // Process was started by WorkManager without the store being initialized
            // (e.g. app not open). Keep the chain alive so the next run can sync.
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                buildRequest(currentIntervalMs),
            )
            return Result.success()
        }

        val syncResult = deps.syncEngine.sync()
        val nextIntervalMs = computeNextIntervalMs(
            currentMs = currentIntervalMs,
            hasChanges = syncResult.hasRemoteChanges,
            minMs = deps.config.minPollInterval.inWholeMilliseconds,
            maxMs = deps.config.maxPollInterval.inWholeMilliseconds,
        )

        // Self-schedule the next poll. Using APPEND_OR_REPLACE ensures the next
        // run is enqueued to start after the current run finishes, even if the
        // current run is still in the RUNNING state.
        WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            buildRequest(nextIntervalMs),
        )

        return Result.success()
    }

    companion object {
        internal const val WORK_NAME = "google_tasks_sync_poll"
        internal const val KEY_INTERVAL_MS = "intervalMs"
        private const val DEFAULT_INTERVAL_MS = 60_000L // 1 minute fallback when deps unavailable

        internal fun buildRequest(initialDelayMs: Long) =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(initialDelayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_INTERVAL_MS to initialDelayMs))
                .build()

        internal fun buildImmediateRequest(nextIntervalMs: Long) =
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInputData(workDataOf(KEY_INTERVAL_MS to nextIntervalMs))
                .build()
    }
}

/**
 * Holds the live dependencies for [SyncWorker]. Set once by [GoogleTasksStore]
 * before any work requests are enqueued.
 */
internal object SyncWorkerDependencies {
    @Volatile
    var current: Deps? = null

    internal class Deps(
        val syncEngine: SyncEngine,
        val config: pl.blizinski.googletasksstore.GoogleTasksStoreConfig,
    )
}

/**
 * Pure function that computes the next poll interval.
 * - If the last sync found remote changes: reset to [minMs].
 * - Otherwise: double the current interval, capped at [maxMs].
 */
internal fun computeNextIntervalMs(
    currentMs: Long,
    hasChanges: Boolean,
    minMs: Long,
    maxMs: Long,
): Long = if (hasChanges) minMs else minOf(currentMs * 2, maxMs)
