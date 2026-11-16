package mebot.nguli.com.core.jobs

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class JobsModelsTest {
    private val job = JobPosting(id = "source:1", title = "Senior Backend Engineer", company = "Example",
        location = "Worldwide", remote = true, employmentType = "full_time", description = "PHP and Laravel engineering", url = "https://example.org/job/1", source = "Example")

    @Test fun defaultsAreEmptyAndOldStateDecodes() {
        assertEquals(JobsState(), Json.decodeFromString<JobsState>("{}"))
        assertTrue(JobsState().jobs.isEmpty())
        assertFalse(JobsState().preferences.onboarded)
    }
    @Test fun persistenceRetainsIntentAndApplicationNotes() {
        val state = JobsState(JobPreferences(roles = "Engineer", country = "Tanzania", onboarded = true), listOf(job), listOf(JobApplication(job.id, "interview", "Prepare questions", 123, snapshot = job, draft = "Reviewed application", draftUpdatedAt = 456)), 123)
        assertEquals(state, Json.decodeFromString<JobsState>(Json.encodeToString(state)))
    }
    @Test fun explicitRemoteAndEmploymentConstraintsAreApplied() {
        assertFalse(JobsMatcher.matches(job.copy(remote = false), JobPreferences(remoteOnly = true)))
        assertFalse(JobsMatcher.matches(job, JobPreferences(employmentType = "contract")))
        assertTrue(JobsMatcher.matches(job, JobPreferences(employmentType = "Full-time")))
        assertTrue(JobsMatcher.matches(job.copy(employmentType = ""), JobPreferences(employmentType = "Full-time")))
    }
    @Test fun countryRestrictionIsNotMistakenForWorldwideRemote() {
        val prefs = JobPreferences(country = "Tanzania", remoteOnly = true)
        assertFalse(JobsMatcher.matches(job.copy(location = "United States"), prefs))
        assertTrue(JobsMatcher.matches(job, prefs))
        assertTrue(JobsMatcher.matches(job.copy(location = "EMEA"), prefs))
        assertTrue(JobsMatcher.reasons(job.copy(location = "EMEA"), prefs).contains("eligibility_unknown"))
        assertFalse(JobsMatcher.reasons(job, prefs).contains("location_match"))
    }
    @Test fun queryRequiresAllWordsAndMatchesLiteralSkillNames() {
        assertTrue(JobsMatcher.matches(job, JobPreferences(), "backend Laravel"))
        assertFalse(JobsMatcher.matches(job, JobPreferences(), "backend nurse"))
        assertFalse(JobsMatcher.matches(job.copy(description = "Django"), JobPreferences(skills = "go")))
        assertTrue(JobsMatcher.matches(job.copy(description = "C++ development"), JobPreferences(skills = "C++")))
    }
    @Test fun rolesAndSkillsAreAlternativeRelevanceSignals() {
        assertTrue(JobsMatcher.matches(job, JobPreferences(roles = "Nurse; Engineer")))
        assertTrue(JobsMatcher.matches(job, JobPreferences(roles = "Nurse", skills = "Laravel, React")))
        assertFalse(JobsMatcher.matches(job, JobPreferences(roles = "Nurse", skills = "Clinical care")))
    }
    @Test fun salariesNeverBecomeUnsupportedEligibilityClaims() {
        val prefs = JobPreferences(salaryMinimum = "2000")
        assertTrue(JobsMatcher.matches(job.copy(salary = "£50,000 yearly"), prefs))
        assertTrue(JobsMatcher.reasons(job, prefs).contains("salary_unverified"))
        assertTrue(JobsMatcher.reasons(job, prefs).contains("eligibility_unknown"))
    }
}
