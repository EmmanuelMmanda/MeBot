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
        val repository = JobsRepository(ApplicationProvider.getApplicationContext(), http)
        repository.erase()
        assertTrue(repository.refresh(100000))
        assertFalse(repository.refresh(100001))
        assertEquals(2, calls)
        repository.save("remotive:1")
        repository.update { it.copy(jobs = emptyList()) }
        assertEquals("Engineer", repository.current().applications.single().snapshot?.title)
    }
}
