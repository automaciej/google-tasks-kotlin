package pl.blizinski.googletasksstore.internal

import pl.blizinski.googletasksstore.models.SyncError
import pl.blizinski.googletasksstore.models.SyncErrorKind

/** Maps the shared library's generic sync-error shape to this library's public API surface. */
internal fun pl.blizinski.tasksync.SyncError.toPublic(): SyncError = SyncError(
    occurredAt = occurredAt,
    kind = kind.toPublic(),
    taskLocalId = entityLocalId,
    httpStatus = httpStatus,
    message = message,
)

private fun pl.blizinski.tasksync.SyncErrorKind.toPublic(): SyncErrorKind = when (this) {
    pl.blizinski.tasksync.SyncErrorKind.PUSH_FAILED -> SyncErrorKind.PUSH_FAILED
    pl.blizinski.tasksync.SyncErrorKind.PULL_FAILED -> SyncErrorKind.PULL_FAILED
    pl.blizinski.tasksync.SyncErrorKind.AUTH_FAILED -> SyncErrorKind.AUTH_FAILED
    pl.blizinski.tasksync.SyncErrorKind.CONSENT_REQUIRED -> SyncErrorKind.CONSENT_REQUIRED
    pl.blizinski.tasksync.SyncErrorKind.ADVANCED_PROTECTION -> SyncErrorKind.ADVANCED_PROTECTION
}
