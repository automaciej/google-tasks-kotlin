package pl.blizinski.googletasksstore.models

/**
 * A link from a task back to the resource it was created from (e.g. a Gmail
 * message or a Google Doc), as returned by the Google Tasks API's `links` field.
 */
data class TaskLink(
    val type: String?,
    val description: String?,
    val link: String,
)
