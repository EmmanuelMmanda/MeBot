package mebot.nguli.com.core.jobs

import kotlinx.serialization.Serializable
import java.util.Locale

@Serializable
data class JobPosting(
    val id: String = "", val title: String = "", val company: String = "",
    val location: String = "", val remote: Boolean = false, val employmentType: String = "",
    val description: String = "", val url: String = "", val source: String = "",
    val postedAt: Long = 0, val fetchedAt: Long = 0, val salary: String = "", val tags: List<String> = emptyList(),
    val salaryMin: Double? = null, val salaryMax: Double? = null, val salaryCurrency: String = "", val salaryPeriod: String = "",
)

@Serializable
data class JobPreferences(
    val roles: String = "", val skills: String = "", val country: String = "",
    val remoteOnly: Boolean = false, val employmentType: String = "",
    val salaryMinimum: String = "", val onboarded: Boolean = false,
    val alertsEnabled: Boolean = false, val alertFrequencyHours: Int = 24,
    val salaryCurrency: String = "", val salaryPeriod: String = "",
    val onboardingStep: Int = 0,
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
    val editingScope: String = "",
)

/** Deterministic relevance, not hiring likelihood or proof of eligibility. */
object JobsMatcher {
    private fun normalized(value: String) = value.lowercase(Locale.ROOT).trim()
    private fun terms(value: String) = value.split(',', ';', '\n').map(::normalized).filter(String::isNotEmpty).distinct()
    private fun contains(haystack: String, needle: String): Boolean =
        Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(needle) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(haystack)
    private fun content(job: JobPosting) = listOf(job.title, job.company, job.location, job.description, job.tags.joinToString(" ")).joinToString(" ")
    private fun type(value: String) = normalized(value).replace(Regex("[ _-]+"), "")
    private val aliasesByCountry: Map<String, Set<String>> by lazy {
        val common = mapOf("US" to setOf("us", "usa", "united states", "united states of america"),
            "GB" to setOf("uk", "gb", "united kingdom", "great britain"),
            "TZ" to setOf("tz", "tanzania", "tanzania, united republic of"))
        buildMap {
            for (code in Locale.getISOCountries()) {
                val locale = Locale.Builder().setRegion(code).build()
                val aliases = setOf(code.lowercase(Locale.ROOT), locale.getDisplayCountry(Locale.ENGLISH).lowercase(Locale.ROOT)) + common[code].orEmpty()
                aliases.forEach { put(it, aliases) }
            }
        }
    }
    private fun countryAliases(value: String): Set<String> = normalized(value).let {
        if (it.isBlank()) emptySet() else aliasesByCountry[it] ?: setOf(it)
    }
    private fun locationMatches(job: JobPosting, preferences: JobPreferences): Boolean =
        countryAliases(preferences.country).any { contains(job.location, it) }
    private fun worldwide(job: JobPosting): Boolean = normalized(job.location) in setOf("worldwide", "anywhere", "global", "remote - worldwide", "remote (worldwide)")
    private fun unspecifiedLocation(job: JobPosting) = normalized(job.location) in setOf("", "remote", "not specified", "unknown")

    private fun comparableSalary(job: JobPosting, preferences: JobPreferences, upperBoundOnly: Boolean = false): Double? {
        if (preferences.salaryCurrency.isBlank() || preferences.salaryPeriod.isBlank()) return null
        if (!job.salaryCurrency.equals(preferences.salaryCurrency.trim(), true)) return null
        fun annualFactor(period: String): Double? = when (normalized(period)) {
            "year", "yearly", "annual", "annually" -> 1.0
            "month", "monthly" -> 12.0
            else -> null
        }
        val amount = (if (upperBoundOnly) job.salaryMax else job.salaryMax ?: job.salaryMin)?.takeIf { it.isFinite() && it >= 0 } ?: return null
        if (job.salaryPeriod.equals(preferences.salaryPeriod.trim(), true)) return amount
        val sourceFactor = annualFactor(job.salaryPeriod) ?: return null
        val targetFactor = annualFactor(preferences.salaryPeriod) ?: return null
        return amount * sourceFactor / targetFactor
    }
    fun matches(job: JobPosting, preferences: JobPreferences, query: String = ""): Boolean {
        val minimum = preferences.salaryMinimum.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
        val salary = comparableSalary(job, preferences, upperBoundOnly = true)
        if (minimum != null && salary != null && salary < minimum) return false
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
        // Only explicit structured salary fields are comparable; raw salary prose is never inferred.
        if (preferences.salaryMinimum.isNotBlank()) {
            val minimum = preferences.salaryMinimum.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 }
            val salary = comparableSalary(job, preferences)
            if (minimum != null && salary != null && salary >= minimum) add("salary_match") else add("salary_unverified")
        }
    }
}
