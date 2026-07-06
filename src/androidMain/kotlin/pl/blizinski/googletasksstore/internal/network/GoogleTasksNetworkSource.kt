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
import pl.blizinski.googletasksstore.parseRfc3339ToEpochMs

internal class GoogleTasksNetworkSource(credential: GoogleAccountCredential) : NetworkTasksSource {

    private val client: Tasks = Tasks.Builder(
        NetHttpTransport(),
        GsonFactory.getDefaultInstance(),
        credential,
    )
        .setApplicationName("GoogleTasksStore")
        .build()

    override suspend fun createTaskList(title: String): RemoteTaskList = withContext(Dispatchers.IO) {
        val result = client.tasklists().insert(GoogleApiTaskList().apply { this.title = title }).execute()
        RemoteTaskList(remoteId = result.id, title = result.title ?: "")
    }

    override suspend fun updateTaskList(remoteListId: String, title: String): Unit = withContext(Dispatchers.IO) {
        client.tasklists().patch(remoteListId, GoogleApiTaskList().apply { this.title = title }).execute()
    }

    override suspend fun deleteTaskList(remoteListId: String): Unit = withContext(Dispatchers.IO) {
        client.tasklists().delete(remoteListId).execute()
    }

    override suspend fun getTaskLists(): List<RemoteTaskList> = withContext(Dispatchers.IO) {
        client.tasklists().list()
            .execute()
            .items
            .orEmpty()
            .map { RemoteTaskList(remoteId = it.id, title = it.title ?: "") }
    }

    override suspend fun getTasks(remoteListId: String, updatedMin: String?): List<RemoteTask> =
        withContext(Dispatchers.IO) {
            val result = mutableListOf<RemoteTask>()
            var pageToken: String? = null
            do {
                val request = client.tasks().list(remoteListId)
                    .setMaxResults(100)
                    .apply { if (pageToken != null) setPageToken(pageToken) }
                if (updatedMin != null) {
                    // Incremental: fetch everything changed since updatedMin,
                    // including completed and deleted tasks.
                    request
                        .setUpdatedMin(updatedMin)
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
                result += response.items.orEmpty().map { it.toRemoteTask(remoteListId) }
                pageToken = response.nextPageToken
            } while (pageToken != null)
            result
        }

    override suspend fun createTask(
        remoteListId: String,
        title: String,
        notes: String?,
        dueDate: String?,
    ): RemoteTask = withContext(Dispatchers.IO) {
        val body = Task().apply {
            this.title = title
            this.notes = notes
            this.due = dueDate
        }
        client.tasks().insert(remoteListId, body).execute().toRemoteTask(remoteListId)
    }

    override suspend fun updateTask(
        remoteListId: String,
        remoteTaskId: String,
        title: String,
        notes: String?,
        dueDate: String?,
        isCompleted: Boolean?,
    ): Unit = withContext(Dispatchers.IO) {
        val patch = Task().apply {
            this.title = title
            // GsonFactory omits null fields from PATCH JSON, so the server would
            // ignore them and leave the old value. Data.NULL_STRING is serialized
            // as JSON null, which explicitly clears the field server-side.
            this.notes = notes ?: Data.NULL_STRING
            this.due = dueDate ?: Data.NULL_STRING
            if (isCompleted == false) {
                // Revert to needsAction: set status and omit the completed timestamp.
                this.status = "needsAction"
                this.completed = Data.NULL_STRING
            }
        }
        client.tasks().patch(remoteListId, remoteTaskId, patch).execute()
    }

    override suspend fun completeTask(
        remoteListId: String,
        remoteTaskId: String,
    ): Unit = withContext(Dispatchers.IO) {
        val patch = Task().apply { status = "completed" }
        client.tasks().patch(remoteListId, remoteTaskId, patch).execute()
    }

    override suspend fun deleteTask(
        remoteListId: String,
        remoteTaskId: String,
    ): Unit = withContext(Dispatchers.IO) {
        client.tasks().delete(remoteListId, remoteTaskId).execute()
    }

    override suspend fun moveTask(
        remoteListId: String,
        remoteTaskId: String,
        previousRemoteTaskId: String?,
    ): Unit = withContext(Dispatchers.IO) {
        client.tasks().move(remoteListId, remoteTaskId)
            .apply { if (previousRemoteTaskId != null) setPrevious(previousRemoteTaskId) }
            .execute()
    }
}

private fun Task.toRemoteTask(remoteListId: String) = RemoteTask(
    remoteId = id,
    remoteListId = remoteListId,
    title = title ?: "",
    notes = notes,
    isCompleted = "completed" == status,
    isDeleted = deleted == true,
    isHidden = hidden == true,
    createdDate = null, // Google Tasks API does not expose creation date
    remoteUpdatedAt = updated?.parseRfc3339ToEpochMs(),
    dueDate = due,  // RFC 3339 date string, e.g. "2026-03-20T00:00:00.000Z"
    completedDate = completed,
    parentId = parent,
    position = position,
    etag = etag,
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
)
