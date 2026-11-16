package mebot.nguli.com.core.jobs

import mebot.nguli.com.core.FeedState
import mebot.nguli.com.core.PersonalProfile085
import java.security.MessageDigest
import java.util.Locale

enum class JobsContextField { ROLE_INTEREST, SKILL_TO_CONFIRM, COUNTRY }
data class JobsContextCandidate(
    val id: String, val sourceId: String, val sourceLabel: String, val sourceText: String,
    val field: JobsContextField, val value: String, val reasonKey: String,
)
data class JobsModuleLink(val id: String, val title: String, val kind: String, val reasonKey: String)
data class JobsContextSnapshot(
    val revision: String = "", val candidates: List<JobsContextCandidate> = emptyList(),
    val modules: List<JobsModuleLink> = emptyList(),
)

/** Local review suggestions, not verified qualifications or permission to send context to AI. */
object JobsContextSuggestions {
    private val careerKinds = setOf("opportunities", "technology", "learning", "business")
    private val eligibleScopes = setOf("jobs", "opportunities")
    private fun clean(value: String, limit: Int) = value.replace("\u0000", "").trim().take(limit)
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun country(value: String): String? {
        val text = value.trim()
        val aliases = mapOf("tanzania" to "TZ", "tanzania, united republic of" to "TZ", "uk" to "GB", "usa" to "US")
        val code = aliases[text.lowercase(Locale.ROOT)] ?: Locale.getISOCountries().firstOrNull {
            text.equals(it, true) || text.equals(Locale.Builder().setRegion(it).build().getDisplayCountry(Locale.ENGLISH), true)
        } ?: return null
        return Locale.Builder().setRegion(code).build().getDisplayCountry(Locale.ENGLISH)
    }

    fun build(feed: FeedState, @Suppress("UNUSED_PARAMETER") preferences: JobPreferences): JobsContextSnapshot {
        val entries = feed.context.filter {
            it.confirmed && it.id.isNotBlank() && it.id.length <= 200 && '\u0000' !in it.id && it.text.isNotBlank() &&
                (PersonalProfile085.isGlobal(it) || it.scopes.any(eligibleScopes::contains))
        }.sortedBy { it.id }.take(80)
        val modules = feed.modules.filter { it.enabled && it.id.isNotBlank() && it.id.length <= 200 && '\u0000' !in it.id && it.kind in careerKinds }
            .sortedBy { it.id }.take(20).map {
                JobsModuleLink(it.id, clean(it.title, 200), it.kind, "module_interest_not_qualification")
            }
        val candidates = mutableListOf<JobsContextCandidate>()
        fun add(sourceId: String, label: String, sourceText: String, field: JobsContextField, raw: String, reason: String) {
            val value = clean(raw, if (field == JobsContextField.SKILL_TO_CONFIRM) 1000 else 500)
            if (value.isBlank()) return
            // Keep provenance available for explicit review even after preferences are saved.
            // The UI, not this read-only projection, decides whether an empty field can be filled.
            if (candidates.any { it.field == field && it.value.equals(value, true) }) return
            candidates += JobsContextCandidate(digest("$sourceId\n$field\n$value"), sourceId,
                clean(label, 200), clean(sourceText, 1000), field, value, reason)
        }
        entries.forEach { entry ->
            val label = if (PersonalProfile085.isGlobal(entry)) "About you" else entry.category
            // Only explicit labelled facts are offered. Interest prose never becomes a skill claim.
            entry.text.take(1000).lineSequence().take(30).forEach line@{ line ->
                val split = line.indexOf(':')
                if (split <= 0) return@line
                val key = line.substring(0, split).trim().lowercase(Locale.ROOT)
                val value = line.substring(split + 1).trim()
                when (key) {
                    "role", "roles", "job title", "desired role", "target role", "kazi", "nafasi" ->
                        add(entry.id, label, line, JobsContextField.ROLE_INTEREST, value, "explicit_role_review")
                    "skills", "skill", "ujuzi" ->
                        add(entry.id, label, line, JobsContextField.SKILL_TO_CONFIRM, value, "self_reported_skill_review")
                    "country", "nchi" -> country(value)?.let {
                        add(entry.id, label, line, JobsContextField.COUNTRY, it, "country_not_work_authorization")
                    }
                }
            }
        }
        country(feed.region)?.let {
            add("feed-region", "Region", feed.region, JobsContextField.COUNTRY, it, "country_not_work_authorization")
        }
        // Length-prefixed fields avoid ambiguous concatenation and track revocation/removal too.
        val revisionFields = entries.flatMap { listOf(it.id, it.text, it.category, it.updatedAtMillis.toString(), it.scopes.sorted().joinToString(","), it.global.toString()) } +
            modules.flatMap { listOf(it.id, it.title, it.kind) } + listOf(feed.region)
        val revision = digest(revisionFields.joinToString("") { "${it.length}:$it" })
        return JobsContextSnapshot(revision, candidates.take(20), modules)
    }
}
