package mebot.nguli.com.core.jobs

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mebot.nguli.com.core.FeedState
import org.junit.Assert.*
import org.junit.Test

class JobsBackupTest {
    @Test fun onboardingProgressIsBackwardCompatibleAndBoundedInBackup() {
        assertEquals(0, Json.decodeFromString<JobPreferences>("{}").onboardingStep)
        for (step in 0..3) {
            val portable = JobsBackup.export(JobsState(preferences = JobPreferences(onboardingStep = step)))
            assertEquals(step, JobsBackup.restore(Json.decodeFromString<JobsState>(Json.encodeToString(portable))).preferences.onboardingStep)
        }
        for (step in listOf(-1, 4)) {
            assertThrows(IllegalArgumentException::class.java) {
                JobsBackup.validate(JobsState(preferences = JobPreferences(onboardingStep = step)))
            }
        }
    }

    private val job = JobPosting(id = "source:1", title = "Engineer", url = "https://example.org/jobs/1", source = "Example")
    @Test fun portableBackupRetainsRecordsButNotCatalogAndDeliveryState() {
        val state = JobsState(preferences = JobPreferences(roles = "Engineer", alertsEnabled = true), jobs = listOf(job),
            applications = listOf(JobApplication(job.id, notes = "My notes", draft = "My reviewed draft")), lastRefreshAt = 42, lastAttemptAt = 44)
        val backup = JobsBackup.export(state)
        assertTrue(backup.jobs.isEmpty()); assertEquals(0L, backup.lastAttemptAt)
        assertEquals(job, backup.applications.single().snapshot)
        val restored = JobsBackup.restore(backup)
        assertFalse(restored.preferences.alertsEnabled)
        assertEquals("My notes", restored.applications.single().notes)
        assertEquals("My reviewed draft", restored.applications.single().draft)
    }
    @Test fun oldBackupsDoNotCarryAnImplicitEmptyJobsReplacement() {
        assertNull(Json.decodeFromString<FeedState>("{}").jobsBackup)
        val exported = FeedState(jobsBackup = JobsBackup.export(JobsState(applications = listOf(JobApplication(job.id, snapshot = job)))))
        assertEquals(exported, Json.decodeFromString<FeedState>(Json.encodeToString(exported)))
    }
    @Test fun rejectsExecutableLinksAndSnapshotIdentitySubstitution() {
        reject(JobsState(applications = listOf(JobApplication(job.id, snapshot = job.copy(url = "javascript:alert(1)")))))
        reject(JobsState(applications = listOf(JobApplication("other", snapshot = job))))
    }
    @Test fun rejectsOversizedDuplicateAndUnknownStateRecords() {
        reject(JobsState(applications = listOf(JobApplication(job.id, draft = "x".repeat(24001)))))
        reject(JobsState(applications = listOf(JobApplication(job.id), JobApplication(job.id))))
        reject(JobsState(applications = listOf(JobApplication(job.id, status = "submitted-by-ai"))))
        reject(JobsState(jobs = listOf(job)))
    }
    @Test fun salaryMetadataMustBeBoundedFiniteAndConsistent() {
        reject(JobsState(preferences = JobPreferences(salaryCurrency = "INVALID")))
        reject(JobsState(preferences = JobPreferences(salaryPeriod = "sometimes")))
        fun snapshot(value: JobPosting) = JobsState(applications = listOf(JobApplication(job.id, snapshot = value)))
        reject(snapshot(job.copy(salaryMin = Double.NaN)))
        reject(snapshot(job.copy(salaryMax = Double.POSITIVE_INFINITY)))
        reject(snapshot(job.copy(salaryMin = -1.0)))
        reject(snapshot(job.copy(salaryMin = 500.0, salaryMax = 100.0)))
        val valid = snapshot(job.copy(salaryMin = 500.0, salaryMax = 1000.0, salaryCurrency = "USD", salaryPeriod = "month"))
        assertEquals(valid, JobsBackup.validate(valid))
        val rawProvider = snapshot(job.copy(salaryCurrency = "usd", salaryPeriod = "yearly"))
        assertEquals(rawProvider, JobsBackup.validate(rawProvider))
    }
    private fun reject(value: JobsState) {
        try { JobsBackup.validate(value); fail("Expected invalid backup rejection") } catch (_: IllegalArgumentException) { }
    }
    @Test fun deviceEditingScopeNeverTravelsInBackup() {
        val backup = JobsBackup.export(JobsState(editingScope = "device-local-scope"))
        assertEquals("", backup.editingScope)
        assertThrows(IllegalArgumentException::class.java) { JobsBackup.validate(backup.copy(editingScope = "injected")) }
    }
    @org.junit.Test fun legacyBookmarkDefaultsAndUnsaveBackupRetainPrivateWork() {
        val old = kotlinx.serialization.json.Json.decodeFromString<JobApplication>("""{"jobId":"old","status":"applied","notes":"Preserved","draft":"Old draft"}""")
        org.junit.Assert.assertTrue(old.bookmarked)
        val state = JobsState(applications = listOf(old.copy(bookmarked = false)))
        val restored = JobsBackup.restore(JobsBackup.export(state)).applications.single()
        org.junit.Assert.assertFalse(restored.bookmarked)
        org.junit.Assert.assertEquals(old.notes, restored.notes)
        org.junit.Assert.assertEquals(old.draft, restored.draft)
        org.junit.Assert.assertEquals("applied", restored.status)
    }
}
