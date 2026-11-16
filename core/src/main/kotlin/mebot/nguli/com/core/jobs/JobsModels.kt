package mebot.nguli.com.core.jobs

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class JobPosting(
    val id: String = "", val title: String = "", val company: String = "",
    val location: String = "", val remote: Boolean = false, val employmentType: String = "",
    val description: String = "", val url: String = "", val source: String = "",
    val postedAt: Long = 0, val fetchedAt: Long = 0, val salary: String = "", val tags: List<String> = emptyList(),
)

@Serializable
data class JobPreferences(
    val roles: String = "", val skills: String = "", val country: String = "",
    val remoteOnly: Boolean = false, val employmentType: String = "",
    val salaryMinimum: String = "", val onboarded: Boolean = false,
    val alertsEnabled: Boolean = false, val alertFrequencyHours: Int = 24,
)

@Serializable
data class JobApplication(
    val jobId: String = "", val status: String = "saved", val notes: String = "", val updatedAt: Long = 0,
    val snapshot: JobPosting? = null, val draft: String = "", val draftUpdatedAt: Long = 0,
)

@Serializable
data class JobsState(
    val preferences: JobPreferences = JobPreferences(), val jobs: List<JobPosting> = emptyList(),
    val applications: List<JobApplication> = emptyList(), val lastRefreshAt: Long = 0, val lastAttemptAt: Long = 0,
    val failedSources: List<String> = emptyList(),
)

/** Deterministic relevance, not hiring likelihood or proof of eligibility. */
object JobsMatcher {
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).trim()
    private fun terms(value: String) = value.split(',', ';', '\n').map(::normalized).filter(String::isNotEmpty).distinct()
    private fun contains(haystack: String, needle: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(needle) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(haystack)
    private fun content(job: JobPosting) = listOf(job.title, job.company, job.location, job.description, job.tags.joinToString(" ")).joinToString(" ")
    private fun type(value: String) = normalized(value).replace(Regex("[ _-]+"), "")
    private fun locationMatches(job: JobPosting, preferences: JobPreferences): Boolean =
        preferences.country.isNotBlank() && contains(job.location, preferences.country.trim())
    private fun worldwide(job: JobPosting): Boolean = normalized(job.location) in setOf("worldwide", "anywhere", "global", "remote - worldwide", "remote (worldwide)")
    private fun unspecifiedLocation(job: JobPosting) = normalized(job.location) in setOf("", "remote", "not specified", "unknown")

    fun matches(job: JobPosting, preferences: JobPreferences, query: String = ""): Boolean {
        if (preferences.remoteOnly && !job.remote) return false
        if (preferences.employmentType.isNotBlank() && job.employmentType.isNotBlank() &&
            type(preferences.employmentType) != type(job.employmentType)) return false
        // Broad geographic scopes require human verification. Do not turn a continent label into a hiring guarantee.
        if (preferences.country.isNotBlank() && !locationMatches(job, preferences) && !worldwide(job) && !unspecifiedLocation(job)) {
            val broad = normalized(job.location) in setOf("emea", "africa", "remote - emea", "remote - africa")
            if (!broad) return false
        }
        val searchable = content(job)
        if (query.trim().split(Regex("\\s+")).filter(String::isNotBlank).any { !contains(searchable, it) }) return false
        val roles = terms(preferences.roles)
        val skills = terms(preferences.skills)
        return (roles.isEmpty() && skills.isEmpty()) || roles.any { contains(job.title, it) } || skills.any { contains(searchable, it) }
    }

    /** Stable resource keys. The Android UI supplies localized text. No key asserts employer eligibility. */
    fun reasons(job: JobPosting, preferences: JobPreferences): List<String> = buildList {
        if (terms(preferences.roles).any { contains(job.title, it) }) add("role_match")
        if (terms(preferences.skills).any { contains(content(job), it) }) add("skills_match")
        if (preferences.remoteOnly && job.remote) add("remote_match")
        if (preferences.employmentType.isNotBlank() && job.employmentType.isNotBlank() &&
            type(preferences.employmentType) == type(job.employmentType)) add("employment_match")
        if (locationMatches(job, preferences)) add("location_match")
        add("eligibility_unknown")
        // Salary is source text without normalized currency or pay period. Never infer a comparable amount.
        if (preferences.salaryMinimum.isNotBlank()) add("salary_unverified")
    }
}
