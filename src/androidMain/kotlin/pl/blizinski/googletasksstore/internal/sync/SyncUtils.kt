package pl.blizinski.googletasksstore.internal.sync

internal fun Exception.httpStatusOrNull(): Int? =
    (this as? com.google.api.client.googleapis.json.GoogleJsonResponseException)?.statusCode

/** Returns true if this exception (or any cause in the chain) is a Google auth error. */
internal fun Exception.isAuthError(): Boolean {
    var ex: Throwable? = this
    while (ex != null) {
        if (ex.javaClass.name.contains("GoogleAuthException")) return true
        ex = ex.cause
    }
    return false
}

/**
 * Returns true if the error is a 403 Forbidden specifically caused by the
 * Google Account Advanced Protection Program (which blocks unverified apps).
 */
internal fun Exception.isAdvancedProtectionError(): Boolean {
    val httpStatus = httpStatusOrNull()
    if (httpStatus != 403) return false
    val msg = message?.lowercase() ?: return false
    return msg.contains("advanced protection") || msg.contains("legacy login") || msg.contains("access_denied")
}

/**
 * If this exception (or any cause) is a [UserRecoverableAuthIOException] or
 * [UserRecoverableAuthException], returns the intent the user must launch to
 * grant OAuth consent. Returns null for all other exceptions.
 */
/**
 * Walks the cause chain looking for a [UserRecoverableAuthIOException].
 * Returns the intent the user must launch to grant OAuth consent, or null.
 * [UserRecoverableAuthIOException] wraps the underlying
 * [com.google.android.gms.auth.UserRecoverableAuthException] and delegates
 * its `intent` property, so only one check is needed.
 */
internal fun Exception.extractConsentIntent(): android.content.Intent? {
    var ex: Throwable? = this
    while (ex != null) {
        if (ex is com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException) {
            return ex.intent
        }
        ex = ex.cause
    }
    return null
}
