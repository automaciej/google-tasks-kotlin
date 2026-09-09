# google-tasks-kotlin

[![](https://jitpack.io/v/automaciej/google-tasks-kotlin.svg)](https://jitpack.io/#automaciej/google-tasks-kotlin)

Kotlin Multiplatform library that wraps the [Google Tasks API](https://developers.google.com/tasks)
with a local Room cache and exposes it through the shared
[`TaskStore`](https://github.com/automaciej/task-sync-kotlin) contract, built on
[task-sync-kotlin](https://github.com/automaciej/task-sync-kotlin)'s offline-first
sync engine. (`task-sync-kotlin` was originally extracted from this library, then
grew into the shared model + store contract the whole family now uses.)

Reads and writes go through a local Room database that is the source of truth for
the UI, reconciled with Google's servers in the background, so the app works
fully offline between syncs. Google Tasks remains the ultimate source of truth
for task data.

The library never handles Google sign-in — on Android it takes a
`GoogleAccountCredential` the app already manages; on wasmJs it takes an
`AccessTokenProvider` (`pl.blizinski.tasksync.model.AccessTokenProvider`).

## One contract, four sources

`google-tasks-kotlin`, `microsoft-todo-kotlin`, `github-issues-kotlin` and
`todoist-kotlin` are separate, independently-versioned libraries that **all
expose the same `pl.blizinski.tasksync.store.TaskStore` interface over the same
`pl.blizinski.tasksync.model.Task` / `TaskList` types**. A consuming app can hold
several side by side and treat them uniformly, branching only on each one's
`StoreCapabilities` (`GoogleTasks.capabilities`).

## API

```kotlin
// Android
val store: TaskStore = googleTasksStore(
    context,
    credential,                        // GoogleAccountCredential
    StoreConfig(dbName = "google_tasks_store_$accountId"),
)
// wasmJs
val store: TaskStore = googleTasksWasmStore(tokenProvider, StoreConfig(dbName = "google_tasks_store"))
```

`TaskStore` gives you `Flow`s of task lists and tasks per list, a
`Flow<SyncStatus>`, optimistic `createTask`/`updateTask`/`completeTask`/
`uncompleteTask`/`deleteTask`/`moveTask` (native `tasks.move`, never
delete-and-recreate) and the list-level equivalents, and `forceSync()`/
`fullSync()`. Op-merging, tombstone detection, per-account polling isolation and
Google-specific auth/consent `SyncErrorKind` classification are inherited from
`task-sync-kotlin`. A one-off Room migration (`MIGRATION_5_6` / `MIGRATION_1_6`)
carries pre-`task-sync-kotlin` installs across.

## No due *time*, only a due *date*

A limitation of the Google Tasks API itself. Per the
[API reference](https://developers.google.com/workspace/tasks/reference/rest/v1/tasks)
for `Task.due`:

> Only date information is recorded; the time portion of the timestamp is
> discarded when setting this field. It isn't possible to read or write the time
> that a task is scheduled for using the API.

`GoogleTasks.capabilities.supportsDueTime` is `false`; a `TaskDraft` carrying a
time-of-day is truncated to that day. Upstream feature request:
[issuetracker.google.com/issues/166896024](https://issuetracker.google.com/issues/166896024).

## Targets

`androidTarget` (Room + `google-api-client-android`) and a `wasmJs` target
(`googleTasksWasmStore`, IndexedDB, Ktor, sync-on-demand — a
proof-of-concept). wasmJs is excluded from JitPack builds (see `jitpack.yml`).

## Usage

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories { maven { url = uri("https://jitpack.io") } }
}
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.github.automaciej:google-tasks-kotlin:v0.3.0")
}
```

Construct with a `GoogleAccountCredential` your app already manages, then consume
the returned `TaskStore`.

## Build

```
./build.sh build
```
