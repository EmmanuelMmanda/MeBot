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
    val sourceStatus: String = "", val statusCheckedAt: Long = 0,
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
    val bookmarked: Boolean = true,
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
    // Bounded occupation aliases only. Never equate generic "engineer" and "developer":
    // those words also describe civil engineering, property and business development.
    private val roleAliasGroups = listOf(
        listOf("software engineer", "software developer"),
        listOf("backend engineer", "backend developer", "back-end engineer", "back-end developer", "back end engineer", "back end developer"),
        listOf("frontend engineer", "frontend developer", "front-end engineer", "front-end developer", "front end engineer", "front end developer"),
        listOf("full stack engineer", "full stack developer", "full-stack engineer", "full-stack developer", "fullstack engineer", "fullstack developer"),
        listOf("android engineer", "android developer"),
        listOf("ios engineer", "ios developer"),
    )
    private val roleAliasPatterns = roleAliasGroups.flatMap { aliases ->
        aliases.drop(1).map { alias ->
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(alias) + "(?![\\p{L}\\p{N}])") to aliases.first()
        }
    }
    private fun canonicalRole(value: String): String {
        var result = normalized(value).replace(Regex("\\s+"), " ")
        roleAliasPatterns.forEach { (pattern, canonical) -> result = result.replace(pattern, canonical) }
        return result
    }
    private fun roleMatches(title: String, role: String) = contains(canonicalRole(title), canonicalRole(role))
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
    // Source geography is evidence for browsing, never work authorization. In particular,
    // Jobicy "Anywhere" means no assigned source region, not verified worldwide hiring.
    private fun unspecifiedLocation(job: JobPosting) = normalized(job.location) in
        setOf("", "remote", "not specified", "unknown", "anywhere")
    private fun worldwide(job: JobPosting): Boolean = normalized(job.location) in
        setOf("worldwide", "global", "remote - worldwide", "remote (worldwide)")
    private fun geographicParts(job: JobPosting): List<String> = normalized(job.location)
        .split(Regex("[,;/|()]"))
        .map { it.trim().removePrefix("remote - ").removePrefix("remote in ").removeSuffix(" only").removeSuffix(" remote").trim() }
        .filter(String::isNotBlank)
    private fun broadRegion(job: JobPosting): Boolean = geographicParts(job).any { it in setOf(
        "emea", "africa", "europe", "eu", "european union", "apac", "asia pacific", "asia-pacific",
        "asia", "latam", "latin america", "north america", "south america", "americas", "middle east", "oceania") }
    private fun locationMatches(job: JobPosting, preferences: JobPreferences): Boolean {
        if (unspecifiedLocation(job) || worldwide(job) || broadRegion(job)) return false
        return countryAliases(preferences.country).any { alias ->
            // ISO2 codes must be location tokens, not ordinary prose such as "in" or "us".
            if (alias.length == 2) alias in geographicParts(job) else contains(job.location, alias)
        }
    }

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
            if (!broadRegion(job)) return false
        }
        val searchable = content(job)
        if (query.trim().split(Regex("\\s+")).filter(String::isNotBlank).any { !contains(searchable, it) }) return false
        val roles = terms(preferences.roles)
        val skills = terms(preferences.skills)
        // Skills refine the chosen occupation; generic description words must not override it.
        return when {
            roles.isNotEmpty() -> roles.any { roleMatches(job.title, it) }
            skills.isNotEmpty() -> skills.any { contains(skillContent(job), it) }
            else -> true
        }
    }

    private fun skillContent(job: JobPosting) = listOf(job.title, job.tags.joinToString(" "), job.description).joinToString(" ")

    /** Lexical ordering only, never a percentage, qualification or hiring prediction. */
    fun ranked(jobs: List<JobPosting>, preferences: JobPreferences, query: String = ""): List<JobPosting> {
        val roles = terms(preferences.roles)
        val skills = terms(preferences.skills)
        fun score(job: JobPosting): Int {
            val title = canonicalRole(job.title)
            val roleScore = when {
                roles.any { title == canonicalRole(it) } -> 100
                roles.any { roleMatches(title, it) } -> 80
                else -> 0
            }
            // Cap skill influence so verbosity/long skill lists cannot outrank explicit role intent.
            val skillScore = skills.fold(0) { total, skill -> total + when {
                contains(job.title, skill) || job.tags.any { contains(it, skill) } -> 4
                contains(job.description, skill) -> 1
                else -> 0
            } }.coerceAtMost(16)
            return roleScore + skillScore
        }
        return jobs.filter { matches(it, preferences, query) }.map { it to score(it) }
            .sortedWith(compareByDescending<Pair<JobPosting, Int>> { it.second }
                .thenByDescending { it.first.postedAt }.thenBy { it.first.id }.thenBy { it.first.title })
            .map { it.first }
    }

    /** Stable resource keys. The Android UI supplies localized text. No key asserts employer eligibility. */
    fun reasons(job: JobPosting, preferences: JobPreferences): List<String> = buildList {
        if (terms(preferences.roles).any { roleMatches(job.title, it) }) add("role_match")
        if (terms(preferences.skills).any { contains(skillContent(job), it) }) add("skills_match")
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
