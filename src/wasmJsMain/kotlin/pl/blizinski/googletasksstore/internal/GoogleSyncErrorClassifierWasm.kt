package pl.blizinski.googletasksstore.internal

import io.ktor.client.plugins.ResponseException
import pl.blizinski.tasksync.NoStoredTokenException
import pl.blizinski.tasksync.SyncErrorClassifier
import pl.blizinski.tasksync.SyncErrorKind

/**
 * wasmJs [SyncErrorClassifier] for Google Tasks — the Ktor-exception-based counterpart of the
 * Android target's `GoogleSyncErrorClassifier` (which inspects
 * `com.google.api.client.googleapis.*` exception types, Android/JVM-only).
 *
 * Scope cut vs. the Android version: no [SyncErrorKind.ADVANCED_PROTECTION] detection (that
 * heuristic inspects a Google-client-specific exception message shape not exposed the same way
 * through Ktor) and no [extractConsentIntent] support (the browser token-model flow used by
 * `GoogleTasksStoreWasm` has no comparable "recoverable consent" concept — an expired/invalid
 * token just fails with 401, surfaced as [SyncErrorKind.AUTH_FAILED] below).
 */
internal class GoogleSyncErrorClassifierWasm : SyncErrorClassifier {

    override fun classifySpecial(e: Exception): SyncErrorKind? = when {
        e is NoStoredTokenException -> SyncErrorKind.AUTH_FAILED
        httpStatus(e) == 401 -> SyncErrorKind.AUTH_FAILED
        else -> null
    }

    override fun httpStatus(e: Exception): Int? =
        (e as? ResponseException)?.response?.status?.value

    override fun extractConsentIntent(e: Exception): Any? = null
}
