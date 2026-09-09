package pl.blizinski.googletasksstore.internal

import pl.blizinski.tasksync.store.ContentMerger
import pl.blizinski.tasksync.store.contentMerger

/**
 * Three-way merge for [GoogleTask] content, passed to `buildAndroidTaskStore` /
 * `buildWasmTaskStore` so a title edited on one device and a due date edited on another both
 * survive instead of one overwriting the other.
 *
 * Only the fields this app can edit locally are picked: [GoogleTask.title], [GoogleTask.notes],
 * [GoogleTask.dueDate] (via `applyDraft`) and [GoogleTask.completedDate] (via `applyCompletion`).
 * The result starts from the just-pulled `remote`, so every server-owned field — `etag`,
 * `position`, `parentId`, `isHidden`, `webViewLink`, `linksJson`, `assignmentInfoJson`,
 * `createdDate` — is carried through unchanged.
 */
internal val GoogleTasksContentMerger: ContentMerger<GoogleTask> =
    contentMerger(emptyBase = GoogleTask(title = "")) {
        remote.copy(
            title = pick { it.title },
            notes = pick { it.notes },
            dueDate = pick { it.dueDate },
            completedDate = pick { it.completedDate },
        )
    }
