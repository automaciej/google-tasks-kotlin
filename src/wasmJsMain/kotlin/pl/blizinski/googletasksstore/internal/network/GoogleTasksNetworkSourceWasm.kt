package pl.blizinski.googletasksstore.internal.network

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.serialization.kotlinx.json.json
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import pl.blizinski.googletasksstore.GoogleAccessTokenProvider
import pl.blizinski.googletasksstore.internal.GoogleTask
import pl.blizinski.googletasksstore.internal.GoogleTaskList
import pl.blizinski.tasksync.NetworkSource
import pl.blizinski.tasksync.RemoteListRecord
import pl.blizinski.tasksync.RemoteRecord

private const val TASKS_API_BASE = "https://www.googleapis.com/tasks/v1"

/**
 * wasmJs [NetworkSource] for Google Tasks — the Ktor-based counterpart of the Android target's
 * `GoogleTasksNetworkSource` (which uses Google's official `google-api-client-android`/
 * `google-api-services-tasks` libraries, JVM/Android-only). Talks to the Tasks API v1 REST
 * endpoints directly. See TaskCompass's Docs/designs/2026-07-30-web-wasmjs-google-tasks-poc.md.
 *
 * Scope cut vs. the Android version: `links`/`assignmentInfo` are not parsed (always
 * empty/null) — edge-case fields not needed for a listing PoC.
 */
@OptIn(ExperimentalTime::class)
internal class GoogleTasksNetworkSourceWasm(
    private val tokenProvider: GoogleAccessTokenProvider,
    private val httpClient: HttpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    },
) : NetworkSource<GoogleTask, GoogleTaskList> {

    private suspend fun authHeader(): Pair<String, String> = "Authorization" to "Bearer ${tokenProvider.getToken()}"

    override suspend fun getLists(): List<RemoteListRecord<GoogleTaskList>> {
        val result = mutableListOf<RemoteListRecord<GoogleTaskList>>()
        var pageToken: String? = null
        do {
            val (headerName, headerValue) = authHeader()
            val response: TaskListsResponse = httpClient.get("$TASKS_API_BASE/users/@me/lists") {
                header(headerName, headerValue)
                parameter("maxResults", 100)
                if (pageToken != null) parameter("pageToken", pageToken)
            }.body()
            result += response.items.map { RemoteListRecord(remoteId = it.id, content = GoogleTaskList(title = it.title)) }
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return result
    }

    override suspend fun createList(content: GoogleTaskList): RemoteListRecord<GoogleTaskList> {
        val (headerName, headerValue) = authHeader()
        val dto: TaskListDto = httpClient.post("$TASKS_API_BASE/users/@me/lists") {
            header(headerName, headerValue)
            setBody(TaskListDto(title = content.title))
        }.body()
        return RemoteListRecord(remoteId = dto.id, content = GoogleTaskList(title = dto.title))
    }

    override suspend fun updateList(remoteListId: String, content: GoogleTaskList) {
        val (headerName, headerValue) = authHeader()
        httpClient.patch("$TASKS_API_BASE/users/@me/lists/$remoteListId") {
            header(headerName, headerValue)
            setBody(TaskListDto(title = content.title))
        }
    }

    override suspend fun deleteList(remoteListId: String) {
        val (headerName, headerValue) = authHeader()
        httpClient.delete("$TASKS_API_BASE/users/@me/lists/$remoteListId") {
            header(headerName, headerValue)
        }
    }

    override suspend fun getRecords(remoteListId: String, updatedMin: Long?): List<RemoteRecord<GoogleTask>> {
        val result = mutableListOf<RemoteRecord<GoogleTask>>()
        var pageToken: String? = null
        do {
            val (headerName, headerValue) = authHeader()
            val response: TasksResponse = httpClient.get("$TASKS_API_BASE/lists/$remoteListId/tasks") {
                header(headerName, headerValue)
                parameter("maxResults", 100)
                if (pageToken != null) parameter("pageToken", pageToken)
                if (updatedMin != null) {
                    parameter("updatedMin", updatedMin.toRfc3339())
                    parameter("showCompleted", true)
                    parameter("showDeleted", true)
                    parameter("showHidden", true)
                } else {
                    parameter("showCompleted", false)
                    parameter("showDeleted", false)
                    parameter("showHidden", false)
                }
            }.body()
            result += response.items.map { it.toRemoteRecord() }
            pageToken = response.nextPageToken
        } while (pageToken != null)
        return result
    }

    override suspend fun createRecord(remoteListId: String, content: GoogleTask): RemoteRecord<GoogleTask> {
        val (headerName, headerValue) = authHeader()
        val dto: TaskDto = httpClient.post("$TASKS_API_BASE/lists/$remoteListId/tasks") {
            header(headerName, headerValue)
            setBody(TaskDto(title = content.title, notes = content.notes, due = content.dueDate?.toRfc3339()))
        }.body()
        return dto.toRemoteRecord()
    }

    override suspend fun updateRecord(remoteListId: String, remoteId: String, content: GoogleTask) {
        val (headerName, headerValue) = authHeader()
        httpClient.patch("$TASKS_API_BASE/lists/$remoteListId/tasks/$remoteId") {
            header(headerName, headerValue)
            setBody(TaskDto(title = content.title, notes = content.notes, due = content.dueDate?.toRfc3339()))
        }
    }

    override suspend fun completeRecord(remoteListId: String, remoteId: String) {
        val (headerName, headerValue) = authHeader()
        httpClient.patch("$TASKS_API_BASE/lists/$remoteListId/tasks/$remoteId") {
            header(headerName, headerValue)
            setBody(TaskStatusPatch(status = "completed"))
        }
    }

    override suspend fun uncompleteRecord(remoteListId: String, remoteId: String) {
        // Google Tasks has no dedicated "uncomplete" endpoint — patch status back to
        // needsAction (matches the Android network source's behavior). The `completed`
        // timestamp is left for the server to clear; Google's API clears it automatically
        // when status flips away from "completed".
        val (headerName, headerValue) = authHeader()
        httpClient.patch("$TASKS_API_BASE/lists/$remoteListId/tasks/$remoteId") {
            header(headerName, headerValue)
            setBody(TaskStatusPatch(status = "needsAction"))
        }
    }

    override suspend fun deleteRecord(remoteListId: String, remoteId: String) {
        val (headerName, headerValue) = authHeader()
        httpClient.delete("$TASKS_API_BASE/lists/$remoteListId/tasks/$remoteId") {
            header(headerName, headerValue)
        }
    }

    override suspend fun moveRecord(
        sourceRemoteListId: String,
        remoteId: String,
        destRemoteListId: String,
        previousRemoteId: String?,
    ) {
        val (headerName, headerValue) = authHeader()
        httpClient.post("$TASKS_API_BASE/lists/$sourceRemoteListId/tasks/$remoteId/move") {
            header(headerName, headerValue)
            parameter("destinationTasklist", destRemoteListId)
            if (previousRemoteId != null) parameter("previous", previousRemoteId)
        }
    }
}

@Serializable
private data class TaskListDto(val id: String = "", val title: String = "")

@Serializable
private data class TaskListsResponse(val items: List<TaskListDto> = emptyList(), val nextPageToken: String? = null)

@Serializable
private data class TaskLinkDto(val type: String? = null, val description: String? = null, val link: String? = null)

@Serializable
private data class TaskDto(
    val id: String = "",
    val title: String? = null,
    val notes: String? = null,
    val status: String? = null,
    val due: String? = null,
    val completed: String? = null,
    val updated: String? = null,
    val deleted: Boolean = false,
    val hidden: Boolean = false,
    val parent: String? = null,
    val position: String? = null,
    val etag: String? = null,
    val webViewLink: String? = null,
    val links: List<TaskLinkDto>? = null,
)

@Serializable
private data class TasksResponse(val items: List<TaskDto> = emptyList(), val nextPageToken: String? = null)

@Serializable
private data class TaskStatusPatch(val status: String)

@OptIn(ExperimentalTime::class)
private fun TaskDto.toRemoteRecord(): RemoteRecord<GoogleTask> = RemoteRecord(
    remoteId = id,
    isCompleted = status == "completed",
    isDeleted = deleted,
    remoteUpdatedAt = updated?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
    content = GoogleTask(
        title = title ?: "",
        notes = notes,
        createdDate = null, // Google Tasks API does not expose creation date
        dueDate = due?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
        parentId = parent,
        position = position,
        etag = etag,
        completedDate = completed?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() },
        isHidden = hidden,
        webViewLink = webViewLink,
        linksJson = null, // scope cut for this PoC — see class doc comment
    ),
)

@OptIn(ExperimentalTime::class)
private fun Long.toRfc3339(): String = Instant.fromEpochMilliseconds(this).toString()
