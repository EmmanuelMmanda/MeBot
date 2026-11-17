package mebot.nguli.com.jobs

/** A user-selected Jobs action. Personal context is empty until explicitly reviewed. */
enum class JobsAssistantIntent { REQUIREMENTS, QUESTION }

data class JobsAssistantRequest(
    val intent: JobsAssistantIntent = JobsAssistantIntent.QUESTION,
    val question: String = "",
    val sharedContext: String = "",
    val contextRevision: String = "",
)
