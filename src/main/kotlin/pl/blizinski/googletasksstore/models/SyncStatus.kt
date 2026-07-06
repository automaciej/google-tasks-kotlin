package pl.blizinski.googletasksstore.models

data class SyncStatus(
    val isSyncing: Boolean = false,
    val lastSyncedAt: Long? = null,
    val pendingOpCount: Int = 0,
    val recentErrors: List<SyncError> = emptyList(),
    /**
     * Non-null when the last sync failed because the user needs to grant OAuth
     * consent. On Android this is an [android.content.Intent]; stored as [Any]
     * to keep this model free of Android platform types. The consumer casts it
     * to [android.content.Intent] before launching.
     */
    val consentIntent: Any? = null,
)

data class SyncError(
    val occurredAt: Long,
    val kind: SyncErrorKind,
    val taskLocalId: String? = null,
    val httpStatus: Int? = null,
    val message: String,
)

enum class SyncErrorKind { PUSH_FAILED, PULL_FAILED, AUTH_FAILED, CONSENT_REQUIRED, ADVANCED_PROTECTION }
