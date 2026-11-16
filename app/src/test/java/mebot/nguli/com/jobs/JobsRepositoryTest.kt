package mebot.nguli.com.jobs

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import mebot.nguli.com.feed.FeedHttp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JobsRepositoryTest {
    private val response = """{"jobs":[{"id":1,"title":"Engineer","company_name":"Example","url":"https://remotive.com/remote-jobs/software/engineer-1","description":"<p>Build <b>services</b>.</p>","candidate_required_location":"Worldwide","publication_date":"2026-11-16T10:00:00","job_type":"full_time"}]}"""
    @Test fun parsesRealSchemaAndRejectsUnsafeUrl() {
        val jobs = parseRemotiveJobs(response, 123)
        assertEquals(1, jobs.size)
        assertEquals("remotive:1", jobs[0].id)
        assertEquals("Build services.", jobs[0].description)
        assertEquals("Remotive", jobs[0].source)
        assertTrue(jobs[0].remote)
        assertEquals("", jobs[0].salary)
        assertThrows(Exception::class.java) { parseRemotiveJobs(response.replace("https://remotive.com/remote-jobs/software/engineer-1", "javascript:alert(1)"), 123) }
    }
    @Test fun malformedRowsDoNotCrashOtherRows() {
        assertEquals(1, parseRemotiveJobs(response.replace("\"jobs\":[", "\"jobs\":[null,17,{\"id\":2},"), 123).size)
    }
    @Test fun malformedEnvelopeFailsRatherThanWipingCache() {
        assertThrows(Exception::class.java) { parseRemotiveJobs("{}", 123) }
    }
    @Test fun requestsAreThrottledAndApplicationSnapshotSurvivesRefresh() = runBlocking {
        var calls = 0
        val http = object : FeedHttp { override suspend fun request(url: String, method: String, body: String?, bearer: String?): String { calls++; return response } }
        val repository = JobsRepository(ApplicationProvider.getApplicationContext(), http, isOnline = { true })
        repository.erase()
        assertTrue(repository.refresh(100000))
        assertFalse(repository.refresh(100001))
        assertEquals(2, calls)
        repository.save("remotive:1")
        repository.update { it.copy(jobs = emptyList()) }
        assertEquals("Engineer", repository.current().applications.single().snapshot?.title)
    }
    @Test fun offlineAttemptDoesNotConsumeProviderRefreshWindow() = runBlocking {
        var calls = 0
        var online = false
        val http = object : FeedHttp { override suspend fun request(url: String, method: String, body: String?, bearer: String?): String { calls++; return response } }
        val repository = JobsRepository(ApplicationProvider.getApplicationContext(), http, isOnline = { online })
        repository.erase()
        try { repository.refresh(100000); fail("Offline refresh must fail") } catch (_: mebot.nguli.com.feed.FeedFailure) { }
        assertEquals(0, calls)
        assertEquals(0L, repository.current().lastAttemptAt)
        online = true
        assertTrue(repository.refresh(100001))
        assertEquals(2, calls)
    }
    @Test fun jobicyKeepsExplicitSalaryAndPagination() {
        val raw = """{"success":true,"jobs":[{"id":9,"jobTitle":"Engineer","url":"https://jobicy.com/jobs/9-engineer","salaryMin":1000,"salaryMax":2000,"salaryCurrency":"USD","salaryPeriod":"month"}],"hasMore":true,"nextCursor":"next"}"""
        val (jobs, cursor) = parseJobicyJobs(raw, 123)
        assertEquals("next", cursor)
        assertEquals(1000.0, jobs.single().salaryMin!!, 0.0)
        assertEquals(2000.0, jobs.single().salaryMax!!, 0.0)
        assertEquals("USD", jobs.single().salaryCurrency)
        assertEquals("month", jobs.single().salaryPeriod)
    }
    @Test fun explicitBookmarkWritesAreIdempotentAndPreserveApplicationWork() = runBlocking {
        val repository = JobsRepository(ApplicationProvider.getApplicationContext(), isOnline = { false })
        repository.erase()
        val job = mebot.nguli.com.core.jobs.JobPosting(id = "job:1", title = "Example role")
        repository.update { it.copy(jobs = listOf(job)) }
        repository.setSaved(job.id, true)
        repository.setSaved(job.id, true)
        assertEquals(1, repository.current().applications.size)
        repository.application(repository.current().applications.single().copy(notes = "Keep my work", draft = "Reviewed draft"))
        repository.setSaved(job.id, false)
        assertEquals("Keep my work", repository.current().applications.single().notes)
        assertEquals("Reviewed draft", repository.current().applications.single().draft)
    }
    @Test fun resetAndImportRejectPreviouslyQueuedWrites() = runBlocking {
        val repository = JobsRepository(ApplicationProvider.getApplicationContext(), isOnline = { false })
        repository.erase()
        val old = repository.generation()
        val oldScope = repository.current().editingScope
        repository.erase()
        var schedules = 0
        assertTrue(runCatching { repository.preferences(mebot.nguli.com.core.jobs.JobPreferences(roles = "Obsolete"), old) { schedules++ } }.exceptionOrNull() is JobsStateChangedException)
        assertEquals(0, schedules)
        assertEquals("", repository.current().preferences.roles)
        assertTrue(runCatching { repository.preferences(mebot.nguli.com.core.jobs.JobPreferences(roles = "Stale frame"), repository.generation(), oldScope) }.exceptionOrNull() is JobsStateChangedException)
        val beforeImport = repository.generation()
        val beforeImportScope = repository.current().editingScope
        repository.restoreFromJournal(mebot.nguli.com.core.jobs.JobsState(preferences = mebot.nguli.com.core.jobs.JobPreferences(roles = "Restored")))
        assertTrue(runCatching { repository.application(mebot.nguli.com.core.jobs.JobApplication("old", draft = "Obsolete"), beforeImport) }.exceptionOrNull() is JobsStateChangedException)
        assertTrue(runCatching { repository.application(mebot.nguli.com.core.jobs.JobApplication("old", draft = "Stale frame"), repository.generation(), beforeImportScope) }.exceptionOrNull() is JobsStateChangedException)
        assertEquals("Restored", repository.current().preferences.roles)
        assertTrue(repository.current().applications.isEmpty())
    }
}
