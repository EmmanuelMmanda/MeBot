package mebot.nguli.com.jobs

import mebot.nguli.com.core.jobs.JobPosting
import mebot.nguli.com.core.jobs.JobPreferences
import org.junit.Assert.*
import org.junit.Test

class JobsAlertsTest {
    private val enabled = JobPreferences(roles = "Engineer", country = "Tanzania", onboarded = true, alertsEnabled = true)
    private val relevant = JobPosting(id = "new", title = "Software Engineer", location = "Worldwide")

    @Test fun `alerts require both onboarding and explicit opt in`() {
        assertFalse(JobsAlertScheduler.enabled(JobPreferences()))
        assertFalse(JobsAlertScheduler.enabled(enabled.copy(onboarded = false)))
        assertFalse(JobsAlertScheduler.enabled(enabled.copy(alertsEnabled = false)))
        assertTrue(JobsAlertScheduler.enabled(enabled))
    }
    @Test fun `frequency cannot bypass source throttle or create extreme intervals`() {
        assertEquals(6L, JobsAlertScheduler.intervalHours(enabled.copy(alertFrequencyHours = 1)))
        assertEquals(24L, JobsAlertScheduler.intervalHours(enabled))
        assertEquals(168L, JobsAlertScheduler.intervalHours(enabled.copy(alertFrequencyHours = Int.MAX_VALUE)))
    }
    @Test fun `only unseen matching jobs are alerted with duplicate IDs removed`() {
        val jobs = listOf(relevant, relevant, relevant.copy(id = "old"), relevant.copy(id = "wrong", title = "Accountant"), relevant.copy(id = "restricted", location = "United States"))
        assertEquals(setOf("new"), JobsAlertWorker.matchingNewIds(jobs, enabled, setOf("old")))
    }
    @Test fun `turning alerts off suppresses candidates`() {
        assertTrue(JobsAlertWorker.matchingNewIds(listOf(relevant), enabled.copy(alertsEnabled = false), emptySet()).isEmpty())
    }
}
