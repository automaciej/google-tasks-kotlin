package pl.blizinski.googletasksstore

import android.content.Context
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import kotlinx.serialization.serializer
import pl.blizinski.googletasksstore.internal.GoogleSyncErrorClassifier
import pl.blizinski.googletasksstore.internal.GoogleTask
import pl.blizinski.googletasksstore.internal.GoogleTaskList
import pl.blizinski.googletasksstore.internal.GoogleTasksContentAdapter
import pl.blizinski.googletasksstore.internal.MIGRATION_1_6
import pl.blizinski.googletasksstore.internal.MIGRATION_5_6
import pl.blizinski.googletasksstore.internal.network.GoogleTasksNetworkSource
import pl.blizinski.tasksync.model.StoreConfig
import pl.blizinski.tasksync.store.TaskStore
import pl.blizinski.tasksync.store.buildAndroidTaskStore

/**
 * Builds a local-first [TaskStore] for Google Tasks on Android. Reads come from the Room cache;
 * writes are optimistic and synced in the background. One instance per connected account, keyed
 * by [config]`.dbName`.
 *
 * [config]`.dbName` is the same filename the pre-`task-sync-kotlin` `GoogleTasksDatabase`
 * (schema version 5) used, so [MIGRATION_5_6] (repacks the old named-column rows, preserving
 * every localId/remoteId) and the no-op [MIGRATION_1_6] (installs left at version 1 by an old
 * destructive-fallback incident) are always applied.
 */
fun GoogleTasks.store(
    context: Context,
    credential: GoogleAccountCredential,
    config: StoreConfig,
): TaskStore = buildAndroidTaskStore(
    context = context,
    config = config,
    capabilities = capabilities,
    network = GoogleTasksNetworkSource(credential),
    errorClassifier = GoogleSyncErrorClassifier(),
    recordSerializer = serializer<GoogleTask>(),
    listSerializer = serializer<GoogleTaskList>(),
    adapter = GoogleTasksContentAdapter,
    migrations = listOf(MIGRATION_5_6, MIGRATION_1_6),
)
