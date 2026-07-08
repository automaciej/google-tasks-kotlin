package pl.blizinski.googletasksstore.internal

import pl.blizinski.tasksync.SyncErrorClassifier
import pl.blizinski.tasksync.SyncErrorKind

internal class GoogleSyncErrorClassifier : SyncErrorClassifier {

    override fun classifySpecial(e: Exception): SyncErrorKind? = when {
        e.isAdvancedProtectionError() -> SyncErrorKind.ADVANCED_PROTECTION
        e.isAuthError() -> SyncErrorKind.AUTH_FAILED
        else -> null
    }

    override fun httpStatus(e: Exception): Int? =
        (e as? com.google.api.client.googleapis.json.GoogleJsonResponseException)?.statusCode

    /**
     * If this exception (or any cause) is a [com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException],
     * returns the intent the user must launch to grant OAuth consent. Returns null otherwise.
     */
    override fun extractConsentIntent(e: Exception): Any? {
        var ex: Throwable? = e
        while (ex != null) {
            if (ex is com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
                return ex.intent
            }
            ex = ex.cause
        }
        return null
    }
}

/** Returns true if this exception (or any cause in the chain) is a Google auth error. */
private fun Exception.isAuthError(): Boolean {
    var ex: Throwable? = this
    while (ex != null) {
        if (ex.javaClass.name.contains("GoogleAuthException")) return true
        ex = ex.cause
    }
    return false
}

/**
 * Returns true if the error is a 403 Forbidden specifically caused by the Google Account
 * Advanced Protection Program (which blocks unverified apps).
 */
private fun Exception.isAdvancedProtectionError(): Boolean {
    val httpStatus = (this as? com.google.api.client.googleapis.json.GoogleJsonResponseException)?.statusCode
    if (httpStatus != 403) return false
    val msg = message?.lowercase() ?: return false
    return msg.contains("advanced protection") || msg.contains("legacy login") || msg.contains("access_denied")
}
