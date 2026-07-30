package pl.blizinski.googletasksstore

/**
 * Supplies a Google OAuth access token. Implemented by the host app (wrapping wherever the token
 * is obtained — `GoogleAccountCredential`/Play Services on Android, Google Identity Services in
 * a browser) so this library never hardcodes a platform-specific auth mechanism.
 *
 * Used by [pl.blizinski.googletasksstore.GoogleTasksStoreWasm] (the wasmJs target's
 * [TaskStoreApi] implementation) in place of the Android target's concrete
 * `GoogleAccountCredential` parameter.
 */
interface GoogleAccessTokenProvider {
    suspend fun getToken(): String
}
