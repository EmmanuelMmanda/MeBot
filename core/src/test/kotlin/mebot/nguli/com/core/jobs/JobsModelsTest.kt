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
    @Test fun explicitRolesCannotBeBypassedByGenericOrTechnicalSkills() {
        assertTrue(JobsMatcher.matches(job, JobPreferences(roles = "Nurse; Engineer")))
        assertFalse(JobsMatcher.matches(job, JobPreferences(roles = "Nurse", skills = "Laravel, React")))
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
    @Test fun genericSkillsDoNotOverrideOccupationAndCompanyIsNotSkillEvidence() {
        val sales = job.copy(title = "Sales manager", description = "Great communication skills")
        assertFalse(JobsMatcher.matches(sales, JobPreferences(roles = "Developer", skills = "Communication")))
        val unrelated = job.copy(title = "Office manager", company = "Kotlin", description = "Manage office")
        assertFalse(JobsMatcher.matches(unrelated, JobPreferences(skills = "Kotlin")))
        assertFalse("skills_match" in JobsMatcher.reasons(unrelated, JobPreferences(skills = "Kotlin")))
    }

    @Test fun rankingPrefersRoleThenStructuredSkillsAndUsesStableFreshnessTies() {
        val exact = job.copy(id = "exact", title = "Backend Engineer", postedAt = 1)
        val tagged = job.copy(id = "tagged", tags = listOf("Kotlin"), postedAt = 2)
        val prose = job.copy(id = "prose", description = "Kotlin", postedAt = 100)
        val unrelated = job.copy(id = "unrelated", title = "Sales manager", description = "Kotlin")
        val prefs = JobPreferences(roles = "Backend Engineer", skills = "Kotlin")
        assertEquals(listOf("exact", "tagged", "prose"), JobsMatcher.ranked(listOf(prose, unrelated, tagged, exact), prefs).map { it.id })
        val a = job.copy(id = "a", postedAt = 5)
        val b = a.copy(id = "b")
        assertEquals(listOf(a, b), JobsMatcher.ranked(listOf(b, a), prefs))
        assertTrue(JobsMatcher.ranked(listOf(exact), prefs, "nurse").isEmpty())
    }

    @Test fun boundedRoleAliasesMatchSymmetricallyAndKeepSeniority() {
        val developer = job.copy(title = "Senior Backend Developer")
        val engineerPrefs = JobPreferences(roles = "Backend Engineer")
        assertTrue(JobsMatcher.matches(developer, engineerPrefs))
        assertTrue(JobsMatcher.matches(job, JobPreferences(roles = "Back-end Developer")))
        assertTrue("role_match" in JobsMatcher.reasons(developer, engineerPrefs))
        assertFalse(JobsMatcher.matches(job.copy(title = "Junior Backend Developer"), JobPreferences(roles = "Senior Backend Engineer")))
        assertTrue(JobsMatcher.matches(job.copy(title = "Full-stack Developer"), JobPreferences(roles = "Full stack engineer")))
        assertTrue(JobsMatcher.matches(job.copy(title = "Android Engineer"), JobPreferences(roles = "Android developer")))
    }

    @Test fun roleAliasesNeverBroadenToDifferentOccupationsOrDescriptionMentions() {
        val preferences = JobPreferences(roles = "Software developer", skills = "Communication")
        listOf("Civil Engineer", "Business Developer", "Software Engineering Recruiter", "Sales Manager").forEach { title ->
            assertFalse(title, JobsMatcher.matches(job.copy(title = title, description = "Software engineer with communication skills"), preferences))
        }
        assertFalse(JobsMatcher.matches(job.copy(title = "Engineer"), JobPreferences(roles = "Developer")))
        assertFalse(JobsMatcher.matches(job.copy(title = "Frontend Developer"), JobPreferences(roles = "Backend Engineer")))
        val exactAlias = job.copy(id = "alias", title = "Backend Developer", postedAt = 1)
        val senior = job.copy(id = "senior", postedAt = 2)
        assertEquals(listOf("alias", "senior"), JobsMatcher.ranked(listOf(senior, exactAlias), JobPreferences(roles = "Backend Engineer")).map { it.id })
    }

    @Test fun jobicyAnywhereRemainsUnassignedWithoutCountryOrWorldwideClaim() {
        val source = job.copy(source = "Jobicy", location = "Anywhere")
        for (country in listOf("Tanzania", "Germany", "India", "United States")) {
            val preferences = JobPreferences(country = country)
            assertTrue(JobsMatcher.matches(source, preferences))
            assertFalse(JobsMatcher.reasons(source, preferences).contains("location_match"))
            assertTrue(JobsMatcher.reasons(source, preferences).contains("eligibility_unknown"))
        }
        assertEquals("Anywhere", source.location)
    }
    @Test fun broadSourceRegionsDoNotFalselyExcludeCountriesOrAssertMembership() {
        for ((region, country) in listOf("Europe" to "Germany", "EU" to "France", "APAC" to "India",
            "Remote - Africa" to "Tanzania", "LATAM" to "Brazil", "North America" to "Canada",
            "Europe, USA" to "Germany", "EMEA" to "United States")) {
            val listing = job.copy(location = region)
            val preferences = JobPreferences(country = country)
            assertTrue("$region remains reviewable for $country", JobsMatcher.matches(listing, preferences))
            assertFalse(JobsMatcher.reasons(listing, preferences).contains("location_match"))
            assertTrue(JobsMatcher.reasons(listing, preferences).contains("eligibility_unknown"))
        }
        assertFalse(JobsMatcher.matches(job.copy(location = "USA"), JobPreferences(country = "Germany")))
        assertFalse(JobsMatcher.matches(job.copy(location = "Germany"), JobPreferences(country = "India")))
    }
    @Test fun twoLetterCountryCodesAreNotEnglishProseMatches() {
        val india = JobPreferences(country = "India")
        assertFalse(JobsMatcher.matches(job.copy(location = "Remote in USA"), india))
        assertFalse(JobsMatcher.reasons(job.copy(location = "Remote in USA"), india).contains("location_match"))
        assertTrue(JobsMatcher.matches(job.copy(location = "IN"), india))
        assertTrue(JobsMatcher.matches(job.copy(location = "US, IN"), india))
        assertTrue(JobsMatcher.matches(job.copy(location = "Remote - GB"), JobPreferences(country = "United Kingdom")))
        assertTrue(JobsMatcher.matches(job.copy(location = "Remote in US"), JobPreferences(country = "United States")))
        assertTrue(JobsMatcher.matches(job.copy(location = "UK only"), JobPreferences(country = "United Kingdom")))
        assertFalse(JobsMatcher.matches(job.copy(location = "Work at USA"), JobPreferences(country = "Austria")))
        assertFalse(JobsMatcher.matches(job.copy(location = "Open to USA"), JobPreferences(country = "Tonga")))
        assertTrue(JobsMatcher.matches(job.copy(location = "AT / DE"), JobPreferences(country = "Austria")))
    }

}
