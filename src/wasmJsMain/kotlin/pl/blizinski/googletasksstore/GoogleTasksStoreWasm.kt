package pl.blizinski.googletasksstore

import kotlinx.serialization.serializer
import pl.blizinski.googletasksstore.internal.GoogleSyncErrorClassifierWasm
import pl.blizinski.googletasksstore.internal.GoogleTask
import pl.blizinski.googletasksstore.internal.GoogleTaskList
import pl.blizinski.googletasksstore.internal.GoogleTasksContentAdapter
import pl.blizinski.googletasksstore.internal.network.GoogleTasksNetworkSourceWasm
import pl.blizinski.tasksync.model.AccessTokenProvider
import pl.blizinski.tasksync.model.StoreConfig
import pl.blizinski.tasksync.store.TaskStore
import pl.blizinski.tasksync.store.buildWasmTaskStore

/**
 * Builds an IndexedDB-backed [TaskStore] for Google Tasks on wasmJs, syncing on demand only —
 * see TaskCompass's `Docs/designs/2026-07-30-web-wasmjs-google-tasks-poc.md`.
 */
fun GoogleTasks.wasmStore(
    tokenProvider: AccessTokenProvider,
    config: StoreConfig,
): TaskStore = buildWasmTaskStore(
    config = config,
    capabilities = capabilities,
    network = GoogleTasksNetworkSourceWasm(tokenProvider),
    errorClassifier = GoogleSyncErrorClassifierWasm(),
    recordSerializer = serializer<GoogleTask>(),
    listSerializer = serializer<GoogleTaskList>(),
    adapter = GoogleTasksContentAdapter,
)
