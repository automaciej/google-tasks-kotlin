# google-tasks-store

[![](https://jitpack.io/v/automaciej/google-tasks-kotlin.svg)](https://jitpack.io/#automaciej/google-tasks-kotlin)

Android library that wraps the [Google Tasks API](https://developers.google.com/tasks)
with a local Room cache and exposes a reactive `TaskStoreApi`, built on top
of [task-sync-kotlin](https://github.com/automaciej/task-sync-kotlin)'s
shared offline-first sync engine.

This is not a thin, stateless network wrapper: reads and writes go through a
local Room database that is the actual source of truth for the UI, kept in
sync with Google's servers in the background. Google Tasks itself remains
the ultimate source of truth for task data; this library's cache is what
lets the app work fully offline in between syncs.

This library never handles Google sign-in itself — it takes a
`GoogleAccountCredential` supplied by the consuming app, which owns the
actual OAuth/`GoogleSignIn` flow. This keeps the library free of any client
ID or other app-specific credential.

## Features

- **`TaskStoreApi`**: reactive `Flow`s of task lists and tasks per list, plus
  a `Flow<SyncStatus>` for surfacing sync errors/progress in the UI.
- **Optimistic writes**: `createTask`/`updateTask`/`completeTask`/`deleteTask`/
  `moveTask` and their list-level equivalents apply to the local cache
  immediately and queue for background push — no manual refresh needed
  after a write.
- **Native cross-list move**: Google Tasks' `tasks.move` (with
  `destinationTasklist`) is used directly, so moving a task between lists
  never falls back to delete-and-recreate.
- **Adaptive background polling and pending-op merging** inherited from
  `task-sync-kotlin` (see that repo's README for the full feature list):
  op-merging, tombstone detection, per-account polling isolation via
  `AdaptivePoller`, and structured `SyncErrorKind` classification specific
  to Google's auth/consent errors.
- **`forceSync()` / `fullSync()`**: run a sync cycle synchronously on demand,
  with `fullSync()` re-pulling every list from scratch to repair local state
  that drifted in a way incremental sync can't catch.

## What it is *not*

- **Android-only.** Built on `task-sync-kotlin`, which currently declares
  only an `androidTarget` — see that repo's README for what a
  multiplatform port would require.
- **Not a general-purpose task-list abstraction.** `Task`/`TaskList` here
  are Google Tasks' own shape (title, notes, due date, completion). It's
  not meant to be swapped for another source's schema — that's what
  `microsoft-todo-kotlin` is, as a separate, independently-versioned library
  sharing the same underlying engine.

## Usage

Add the JitPack repository:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }
    }
}
```

Add the dependency:

```kotlin
dependencies {
    implementation("com.github.automaciej:google-tasks-kotlin:0.1.0")
}
```

Construct a `GoogleTasksStore` with a `GoogleAccountCredential` your app
already manages (this library never triggers or stores sign-in state
itself), then consume it through `TaskStoreApi`.

## Build

```
./gradlew build
```
