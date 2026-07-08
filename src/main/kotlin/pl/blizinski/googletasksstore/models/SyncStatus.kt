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
    /**
     * Non-null when the local database itself could not be opened (e.g. an
     * unrecognized on-disk schema state) — a condition retrying or background sync
     * cannot recover from. When set, [pl.blizinski.googletasksstore.TaskStoreApi.taskLists]/
     * [pl.blizinski.googletasksstore.TaskStoreApi.tasks] emit empty lists and further
     * reads/writes will keep failing the same way until the app is updated or
     * reinstalled. Consumers should show a dedicated diagnostic screen rather than
     * treating this as an ordinary empty state.
     */
    val fatalStorageError: FatalStorageError? = null,
)

data class SyncError(
    val occurredAt: Long,
    val kind: SyncErrorKind,
    val taskLocalId: String? = null,
    val httpStatus: Int? = null,
    val message: String,
)

enum class SyncErrorKind { PUSH_FAILED, PULL_FAILED, AUTH_FAILED, CONSENT_REQUIRED, ADVANCED_PROTECTION }

data class FatalStorageError(
    val occurredAt: Long,
    /** Short, human-readable summary (typically the exception's message or class name). */
    val summary: String,
    /** Full diagnostic text (stack trace) — meant to be shown on-screen for the user to
     *  screenshot or copy, not just logged, since this failure mode has no automatic recovery. */
    val details: String,
)
