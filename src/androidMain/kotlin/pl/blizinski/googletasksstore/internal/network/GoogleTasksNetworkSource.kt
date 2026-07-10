package pl.blizinski.googletasksstore.internal.network

import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.Data
import com.google.api.services.tasks.Tasks
import com.google.api.services.tasks.model.Task
import com.google.api.services.tasks.model.TaskList as GoogleApiTaskList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pl.blizinski.googletasksstore.internal.GoogleTask
import pl.blizinski.googletasksstore.internal.GoogleTaskList
import pl.blizinski.tasksync.NetworkSource
import pl.blizinski.tasksync.RemoteListRecord
import pl.blizinski.tasksync.RemoteRecord

/**
 * The only place in this library that knows [GoogleTask]/[GoogleTaskList]'s shape and Google
 * Tasks' own RFC 3339 date format — [pl.blizinski.tasksync.SyncEngine]/
 * [pl.blizinski.tasksync.PendingOpsProcessor] never see either.
 */
internal class GoogleTasksNetworkSource(
    credential: GoogleAccountCredential,
) : NetworkSource<GoogleTask, GoogleTaskList> {

    private val client: Tasks = Tasks.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        credential,
    )
        .setApplicationName("GoogleTasksStore")
        .build()

    override suspend fun getLists(): List<RemoteListRecord<GoogleTaskList>> = withContext(Dispatchers.IO) {
        client.tasklists().list()
            .execute()
            .items
            .orEmpty()
            .map { RemoteListRecord(remoteId = it.id, content = GoogleTaskList(title = it.title ?: "")) }
    }

    override suspend fun createList(content: GoogleTaskList): RemoteListRecord<GoogleTaskList> =
        withContext(Dispatchers.IO) {
            val result = client.tasklists().insert(GoogleApiTaskList().apply { title = content.title }).execute()
            RemoteListRecord(remoteId = result.id, content = GoogleTaskList(title = result.title ?: ""))
        }

    override suspend fun updateList(remoteListId: String, content: GoogleTaskList): Unit =
        withContext(Dispatchers.IO) {
            client.tasklists().patch(remoteListId, GoogleApiTaskList().apply { title = content.title }).execute()
        }

    override suspend fun deleteList(remoteListId: String): Unit = withContext(Dispatchers.IO) {
        client.tasklists().delete(remoteListId).execute()
    }

    override suspend fun getRecords(remoteListId: String, updatedMin: Long?): List<RemoteRecord<GoogleTask>> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<RemoteRecord<GoogleTask>>()
            var pageToken: String? = null
            do {
                val request = client.tasks().list(remoteListId)
                    .setMaxResults(100)
                    .apply { if (pageToken != null) setPageToken(pageToken) }
                if (updatedMin != null) {
                    // Incremental: fetch everything changed since updatedMin, including
                    // completed and deleted tasks.
                    request
                        .setUpdatedMin(updatedMin.toRfc3339DueDate())
                        .setShowCompleted(true)
                        .setShowDeleted(true)
                        .setShowHidden(true)
                } else {
                    // Full pull: active tasks only.
                    request
                        .setShowCompleted(false)
                        .setShowDeleted(false)
                        .setShowHidden(false)
                }
                val response = request.execute()
                result += response.items.orEmpty().map { it.toRemoteRecord() }
                pageToken = response.nextPageToken
            } while (pageToken != null)
            result
        }

    override suspend fun createRecord(remoteListId: String, content: GoogleTask): RemoteRecord<GoogleTask> =
        withContext(Dispatchers.IO) {
            val body = Task().apply {
                title = content.title
                notes = content.notes
                due = content.dueDate?.toRfc3339DueDate()
            }
            client.tasks().insert(remoteListId, body).execute().toRemoteRecord()
        }

    override suspend fun updateRecord(remoteListId: String, remoteId: String, content: GoogleTask): Unit =
        withContext(Dispatchers.IO) {
            val patch = Task().apply {
                title = content.title
                // GsonFactory omits null fields from PATCH JSON, so the server would ignore
                // them and leave the old value. Data.NULL_STRING is serialized as JSON null,
                // which explicitly clears the field server-side.
                notes = content.notes ?: Data.NULL_STRING
                due = content.dueDate?.toRfc3339DueDate() ?: Data.NULL_STRING
            }
            client.tasks().patch(remoteListId, remoteId, patch).execute()
        }

    override suspend fun completeRecord(remoteListId: String, remoteId: String): Unit =
        withContext(Dispatchers.IO) {
            val patch = Task().apply { status = "completed" }
            client.tasks().patch(remoteListId, remoteId, patch).execute()
        }

    override suspend fun uncompleteRecord(remoteListId: String, remoteId: String): Unit =
        withContext(Dispatchers.IO) {
            // Google Tasks has no dedicated "uncomplete" endpoint — revert via patch: set
            // status back to needsAction and clear the completed timestamp.
            val patch = Task().apply {
                status = "needsAction"
                completed = Data.NULL_STRING
            }
            client.tasks().patch(remoteListId, remoteId, patch).execute()
        }

    override suspend fun deleteRecord(remoteListId: String, remoteId: String): Unit =
        withContext(Dispatchers.IO) {
            client.tasks().delete(remoteListId, remoteId).execute()
        }

    override suspend fun moveRecord(
        sourceRemoteListId: String,
        remoteId: String,
        destRemoteListId: String,
        previousRemoteId: String?,
    ): Unit = withContext(Dispatchers.IO) {
        client.tasks().move(sourceRemoteListId, remoteId)
            .setDestinationTasklist(destRemoteListId)
            .apply { if (previousRemoteId != null) setPrevious(previousRemoteId) }
            .execute()
    }
}

private fun Task.toRemoteRecord(): RemoteRecord<GoogleTask> = RemoteRecord(
    remoteId = id,
    isCompleted = "completed" == status,
    isDeleted = deleted == true,
    remoteUpdatedAt = updated?.parseRfc3339ToEpochMs(),
    content = GoogleTask(
        title = title ?: "",
        notes = notes,
        createdDate = null, // Google Tasks API does not expose creation date
        dueDate = due?.parseRfc3339ToEpochMs(),
        parentId = parent,
        position = position,
        etag = etag,
        completedDate = completed?.parseRfc3339ToEpochMs(),
        isHidden = hidden == true,
        webViewLink = webViewLink,
        linksJson = links?.takeIf { it.isNotEmpty() }?.let { linksList ->
            JSONArray().apply {
                linksList.forEach { link ->
                    put(JSONObject().apply {
                        put("type", link.type ?: "")
                        put("description", link.description ?: "")
                        put("link", link.link ?: "")
                    })
                }
            }.toString()
        },
        assignmentInfoJson = assignmentInfo?.let { info ->
            JSONObject().apply {
                put("linkToTask", info.linkToTask ?: "")
                put("surfaceType", info.surfaceType ?: "")
                info.driveResourceInfo?.let { drive ->
                    put("driveResourceInfo", JSONObject().apply {
                        put("driveFileId", drive.driveFileId ?: "")
                        put("resourceKey", drive.resourceKey ?: "")
                    })
                }
                info.spaceInfo?.let { space ->
                    put("spaceInfo", JSONObject().apply {
                        put("space", space.space ?: "")
                    })
                }
            }.toString()
        },
    ),
)

// ---------------------------------------------------------------------------
// RFC 3339 helpers for due/completed/updated dates (Google Tasks API format).
// This is the only place in the library that touches this date format — everywhere
// above [GoogleTasksNetworkSource] deals in epoch milliseconds.
// ---------------------------------------------------------------------------

private fun Long.toRfc3339DueDate(): String {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(java.util.Date(this))
}

private fun String.parseRfc3339ToEpochMs(): Long? = try {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    sdf.parse(this)?.time
} catch (e: Exception) { null }
