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
    @Test fun countryAliasesKeepExplicitEligibleLocations() {
        assertTrue(JobsMatcher.matches(job.copy(location = "USA"), JobPreferences(country = "United States")))
        assertTrue(JobsMatcher.matches(job.copy(location = "United Kingdom"), JobPreferences(country = "UK")))
        assertTrue(JobsMatcher.matches(job.copy(location = "Tanzania"), JobPreferences(country = "TZ")))
        assertFalse(JobsMatcher.matches(job.copy(location = "USA"), JobPreferences(country = "Tanzania")))
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
    @Test fun reliableSalaryFieldsFilterWithoutCurrencyGuessing() {
        val prefs = JobPreferences(salaryMinimum = "3000", salaryCurrency = "USD", salaryPeriod = "month")
        assertFalse(JobsMatcher.matches(job.copy(salaryMax = 30000.0, salaryCurrency = "USD", salaryPeriod = "year"), prefs))
        assertTrue(JobsMatcher.matches(job.copy(salaryMax = 48000.0, salaryCurrency = "USD", salaryPeriod = "year"), prefs))
        assertTrue(JobsMatcher.matches(job.copy(salaryMax = 1000.0, salaryCurrency = "EUR", salaryPeriod = "month"), prefs))
        assertTrue(JobsMatcher.matches(job, prefs))
        assertTrue(JobsMatcher.reasons(job.copy(salaryMax = 48000.0, salaryCurrency = "USD", salaryPeriod = "year"), prefs).contains("salary_match"))
    }
    @Test fun salariesNeverBecomeUnsupportedEligibilityClaims() {
        val prefs = JobPreferences(salaryMinimum = "2000")
        assertTrue(JobsMatcher.matches(job.copy(salary = "£50,000 yearly"), prefs))
        assertTrue(JobsMatcher.reasons(job, prefs).contains("salary_unverified"))
        assertTrue(JobsMatcher.reasons(job, prefs).contains("eligibility_unknown"))
    }
    @Test fun minimumOnlySalaryDoesNotInventAnUpperBound() {
        val preferences = JobPreferences(salaryMinimum = "30000", salaryCurrency = "USD", salaryPeriod = "year")
        val fromLow = JobPosting(salaryMin = 20000.0, salaryCurrency = "USD", salaryPeriod = "year")
        assertTrue(JobsMatcher.matches(fromLow, preferences))
        assertTrue("salary_unverified" in JobsMatcher.reasons(fromLow, preferences))
        assertTrue("salary_match" in JobsMatcher.reasons(fromLow.copy(salaryMin = 40000.0), preferences))
    }
}
