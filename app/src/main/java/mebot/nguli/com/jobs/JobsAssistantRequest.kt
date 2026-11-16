package mebot.nguli.com.jobs

import mebot.nguli.com.core.jobs.JobApplication

/** A user-selected Jobs action. Personal context is empty until explicitly reviewed. */
enum class JobsAssistantIntent { REQUIREMENTS, COVER_LETTER, CV_IMPROVEMENTS, INTERVIEW, QUESTION }

data class JobsAssistantRequest(
    val intent: JobsAssistantIntent = JobsAssistantIntent.QUESTION,
    val question: String = "",
    val sharedContext: String = "",
    val contextRevision: String = "",
    val application: JobApplication? = null,
)
