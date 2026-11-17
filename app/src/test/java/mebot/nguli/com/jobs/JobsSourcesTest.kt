package mebot.nguli.com.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import mebot.nguli.com.feed.FeedHttp
import org.junit.Assert.*
import org.junit.Test

class JobsSourcesTest {
    private fun http(block: suspend (String) -> String) = object : FeedHttp {
        override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
            assertEquals("GET", method)
            assertNull(body)
            assertNull(bearer)
            return block(url)
        }
    }
    private fun response(ids: List<String>, status: String = "active") =
        """{"success":true,"jobs":[${ids.joinToString(",") { """{"id":$it,"status":"$status"}""" }}]}"""

    @Test fun parsesExplicitStatusesWithoutPromotingUnknown() {
        assertEquals(mapOf("jobicy:1" to "active", "jobicy:2" to "closed", "jobicy:3" to "unknown"),
            parseJobicyStatuses("""{"jobs":[{"id":1,"status":"active"},{"id":2,"status":"closed"},{"id":3,"status":"unknown"}]}""", setOf("1", "2", "3")))
    }
    @Test fun incompleteMalformedOrUnrequestedEvidenceFailsInsteadOfClosingJobs() {
        val invalid = listOf("{}", """{"jobs":[]}""", """{"success":false,"jobs":[{"id":1,"status":"closed"}]}""",
            """{"jobs":[{"id":2,"status":"closed"}]}""", """{"jobs":[{"id":1,"status":"expired"}]}""",
            """{"jobs":[{"id":1}]}""", """{"jobs":[null]}""")
        invalid.forEach { raw -> assertThrows(Exception::class.java) { parseJobicyStatuses(raw, setOf("1")) } }
        assertThrows(Exception::class.java) {
            parseJobicyStatuses(response(listOf("1", "1")), setOf("1", "2"))
        }
    }
    @Test fun batchesDeduplicatedProviderIdsWithoutSendingOtherContext() = runBlocking {
        val calls = mutableListOf<String>()
        val result = fetchJobicyStatuses(http { url ->
            calls += url
            response(url.substringAfter("?ids=").split(','))
        }, (1..205).map { "jobicy:$it" } + listOf("jobicy:1", "remotive:12", "private:note"))
        assertEquals(205, result.size)
        assertEquals(listOf(100, 100, 5), calls.map { it.substringAfter("?ids=").split(',').size })
        assertTrue(calls.all { it.startsWith("https://jobicy.com/api/jobs/status?ids=") })
    }
    @Test fun emptyInputMakesNoRequestAndInvalidIdsNeverReachNetwork() = runBlocking {
        var calls = 0
        val network = http { calls++; error("Must not send") }
        assertTrue(fetchJobicyStatuses(network, listOf("remotive:9")).isEmpty())
        listOf("jobicy:0", "jobicy:1&secret=value", "jobicy:01", "jobicy:-1", "jobicy:9999999999999999999").forEach { id ->
            assertTrue(runCatching { fetchJobicyStatuses(network, listOf(id)) }.isFailure)
        }
        assertTrue(runCatching { fetchJobicyStatuses(network, (1..2001).map { "jobicy:$it" }) }.isFailure)
        assertEquals(0, calls)
    }
    @Test fun lateBatchFailureAndCancellationNeverReturnPartialStatusProof() = runBlocking {
        var calls = 0
        val failed = runCatching {
            fetchJobicyStatuses(http { url ->
                calls++
                if (calls == 2) error("Transient provider failure")
                response(url.substringAfter("?ids=").split(','), "closed")
            }, (1..101).map { "jobicy:$it" })
        }
        assertTrue(failed.isFailure)
        assertEquals(2, calls)
        val cancelled = runCatching {
            fetchJobicyStatuses(http { throw CancellationException("cancelled") }, listOf("jobicy:1"))
        }
        assertTrue(cancelled.exceptionOrNull() is CancellationException)
    }
}
