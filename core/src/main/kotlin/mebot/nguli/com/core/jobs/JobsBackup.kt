package mebot.nguli.com.core.jobs

import mebot.nguli.com.core.FeedLogic

/** Portable user-owned intent and records; fetched catalogs and delivery ledgers are device-local. */
object JobsBackup {
    private const val MAX_TIME = 32_503_680_000_000L
    fun export(state: JobsState): JobsState = validate(JobsState(
        preferences = state.preferences,
        applications = state.applications.map { application ->
            application.copy(snapshot = application.snapshot ?: state.jobs.find { it.id == application.jobId })
        },
    ))

    fun restore(backup: JobsState): JobsState = validate(backup).copy(
        preferences = backup.preferences.copy(alertsEnabled = false),
        jobs = emptyList(), lastRefreshAt = 0, lastAttemptAt = 0, failedSources = emptyList(),
    )

    fun validate(value: JobsState): JobsState {
        val p = value.preferences
        fun bounded(text: String, maximum: Int) { require(text.length <= maximum && '\u0000' !in text) }
        bounded(p.roles, 500); bounded(p.skills, 1000); bounded(p.country, 100)
        bounded(p.employmentType, 100); bounded(p.salaryMinimum, 200)
        require(p.alertFrequencyHours in 6..168)
        fun currency(value: String) {
            require(value.isEmpty() || (value.length == 3 && value.all { it in 'A'..'Z' } &&
                runCatching { java.util.Currency.getInstance(value) }.isSuccess))
        }
        fun period(value: String) { require(value in setOf("", "hour", "day", "week", "month", "year")) }
        currency(p.salaryCurrency); period(p.salaryPeriod)
        // A backup is never a channel for injecting supposedly fresh provider listings.
        require(value.jobs.isEmpty() && value.lastRefreshAt == 0L && value.lastAttemptAt == 0L && value.failedSources.isEmpty())
        require(value.applications.size <= 500)
        require(value.applications.map { it.jobId }.distinct().size == value.applications.size)
        var total = 0L
        value.applications.forEach { a ->
            require(a.jobId.isNotBlank()); bounded(a.jobId, 200)
            require(a.status in setOf("saved", "preparing", "applied", "interview", "offer", "closed"))
            bounded(a.notes, 10000); bounded(a.draft, 24000)
            require(a.updatedAt in 0..MAX_TIME && a.draftUpdatedAt in 0..MAX_TIME)
            total += a.notes.length + a.draft.length
            a.snapshot?.let { j ->
                require(j.id == a.jobId && j.title.isNotBlank())
                bounded(j.id, 200); bounded(j.title, 500); bounded(j.company, 500)
                bounded(j.location, 500); bounded(j.employmentType, 100); bounded(j.description, 60000)
                bounded(j.url, 2048); require(FeedLogic.safeUrl(j.url, true))
                bounded(j.source, 100); bounded(j.salary, 500)
                // Provider metadata may use unrecognised spellings; preserve bounded raw values.
                // Matching treats these as unverified instead of blocking the entire user backup.
                bounded(j.salaryCurrency, 20); bounded(j.salaryPeriod, 40)
                listOfNotNull(j.salaryMin, j.salaryMax).forEach { require(it.isFinite() && it >= 0 && it <= 1_000_000_000_000) }
                if (j.salaryMin != null && j.salaryMax != null) require(j.salaryMin <= j.salaryMax)
                require(j.tags.size <= 30); j.tags.forEach { bounded(it, 500) }
                require(j.postedAt in 0..MAX_TIME && j.fetchedAt in 0..MAX_TIME)
                total += j.description.length + j.tags.sumOf { it.length } + 5000
            }
        }
        require(total <= 2_000_000) { "Jobs backup exceeds portable size limit" }
        return value
    }
}
