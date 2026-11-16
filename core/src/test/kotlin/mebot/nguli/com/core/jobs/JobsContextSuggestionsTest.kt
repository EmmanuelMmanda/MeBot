package mebot.nguli.com.core.jobs

import mebot.nguli.com.core.*
import org.junit.Assert.*
import org.junit.Test

class JobsContextSuggestionsTest {
    private fun profile(text: String, confirmed: Boolean = true) = ContextEntry(
        id = PersonalProfile085.PROFILE_ID, text = text, category = "profile", scopes = emptySet(),
        global = true, confirmed = confirmed, updatedAtMillis = 123,
    )
    private fun build(feed: FeedState, prefs: JobPreferences = JobPreferences()) = JobsContextSuggestions.build(feed, prefs)

    @Test fun explicitReviewedFactsHaveStableProvenanceAndNeverMutatePreferences() {
        val feed = FeedState(context = listOf(profile("Role: Android developer\nSkills: Kotlin, SQL\nCountry: Tanzania")))
        val prefs = JobPreferences()
        val result = build(feed, prefs)
        assertEquals(listOf("Android developer", "Kotlin, SQL", "Tanzania"), result.candidates.map { it.value })
        assertEquals(JobsContextField.SKILL_TO_CONFIRM, result.candidates[1].field)
        assertEquals("Skills: Kotlin, SQL", result.candidates[1].sourceText)
        assertEquals(PersonalProfile085.PROFILE_ID, result.candidates[1].sourceId)
        assertEquals(64, result.revision.length)
        assertEquals(result, build(feed, prefs))
        assertEquals(JobPreferences(), prefs)
    }

    @Test fun unrelatedUnconfirmedAndUnconsentedProfileAreNotUsed() {
        val feed = FeedState(context = listOf(
            profile("Skills: Kotlin", confirmed = false),
            ContextEntry(id = "medical", text = "Skills: secret", scopes = setOf("health")),
            profile("Role: Engineer").copy(id = "other-profile", global = true),
            ContextEntry(id = "plain", text = "I read about AI and hope to learn programming", scopes = setOf("opportunities")),
        ))
        assertTrue(build(feed).candidates.isEmpty())
    }

    @Test fun existingPreferencesArePreservedAndCountryNeverComesFromTimezone() {
        val feed = FeedState(region = "Dar es Salaam", timezone = "Africa/Dar_es_Salaam",
            context = listOf(profile("Role: Engineer\nSkills: Kotlin")))
        val preferences = JobPreferences(roles = "Designer", skills = "Figma")
        assertEquals(listOf("Engineer", "Kotlin"), build(feed, preferences).candidates.map { it.value })
        assertEquals("Designer", preferences.roles)
        assertEquals("Figma", preferences.skills)
        assertEquals(build(feed).revision, build(feed, preferences).revision)
        assertEquals("Tanzania", build(feed.copy(region = "TZ")).candidates.last().value)
        assertTrue(build(FeedState(region = "Africa", timezone = "Africa/Dar_es_Salaam")).candidates.isEmpty())
    }

    @Test fun moduleLinksAreInterestsNotQualificationsAndSensitiveModulesAreExcluded() {
        val feed = FeedState(modules = listOf(
            FeedModule(id = "tech", kind = "technology", title = "Technology", query = "Private module query"),
            FeedModule(id = "health", kind = "health", title = "Health"),
            FeedModule(id = "learn", kind = "learning", title = "Learning", enabled = false),
        ))
        val result = build(feed)
        assertEquals(listOf("tech"), result.modules.map { it.id })
        assertEquals("module_interest_not_qualification", result.modules.single().reasonKey)
        assertTrue(result.candidates.isEmpty())
        assertFalse(result.toString().contains("Private module query"))
    }

    @Test fun revisionTracksRevocationRemovalAndSourceEditsButNotUnrelatedPrivateContext() {
        val entry = profile("Skills: Kotlin")
        val feed = FeedState(context = listOf(entry))
        val revision = build(feed).revision
        assertNotEquals(revision, build(feed.copy(context = emptyList())).revision)
        assertNotEquals(revision, build(feed.copy(context = listOf(entry.copy(confirmed = false)))).revision)
        assertNotEquals(revision, build(feed.copy(context = listOf(entry.copy(text = "Skills: Java")))).revision)
        assertEquals(revision, build(feed.copy(context = listOf(entry,
            ContextEntry(id = "private", text = "Unrelated personal information", scopes = setOf("health"))))).revision)
    }

    @Test fun swahiliLabelsAndBoundsRemainLiteralAndLocal() {
        val text = "Ujuzi: " + "x".repeat(1500)
        val feed = FeedState(context = listOf(profile("Nafasi: Mhasibu\nNchi: TZ"),
            ContextEntry(id = "skills", text = text, scopes = setOf("opportunities"))))
        val result = build(feed)
        assertTrue(result.candidates.any { it.value == "Mhasibu" })
        assertTrue(result.candidates.any { it.value == "Tanzania" })
        assertTrue(result.candidates.all { it.sourceText.length <= 1000 && it.value.length <= 1000 })
    }
}
