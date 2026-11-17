package mebot.nguli.com.jobs

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import mebot.nguli.com.core.jobs.JobPosting
import mebot.nguli.com.feed.FeedHttp
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JobsPaginationTest {
    private fun oldJob() = JobPosting(id = "jobicy:9001", title = "Previously retrieved", source = "Jobicy",
        url = "https://jobicy.com/jobs/9001-previous", postedAt = 100, fetchedAt = 200)
    private fun page(number: Int) = """{"success":true,"jobs":[${((number - 1) * 100 + 1..number * 100).joinToString(",") { id ->
        """{"id":$id,"jobTitle":"Role $id","companyName":"Example","url":"https://jobicy.com/jobs/$id-role","pubDate":"2026-11-16T17:01:27+00:00","jobType":["Full-Time"]}"""
    }}],"hasMore":true,"nextCursor":"page-${number + 1}"}"""

    @Test fun boundedPartialDiscoveryKeepsRealRowsAndPriorCacheWithoutEleventhPage() = runBlocking {
        var pages = 0
        val statusBatches = mutableListOf<List<String>>()
        val network = object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String = when {
                url.startsWith("https://remotive.com/") -> """{"jobs":[]}"""
                url.startsWith("https://jobicy.com/api/jobs/status?") -> {
                    val ids = url.substringAfter("?ids=").split(',')
                    statusBatches += ids
                    """{"success":true,"jobs":[${ids.joinToString(",") { """{"id":$it,"status":"active"}""" }}]}"""
                }
                url.startsWith("https://jobicy.com/api/jobs?") -> {
                    pages++
                    check(pages <= 10) { "An eleventh page must not be requested" }
                    page(pages)
                }
                else -> error("Unexpected endpoint: $url")
            }
        }
        val repository = JobsRepository(ApplicationProvider.getApplicationContext(), network, isOnline = { true })
        repository.erase()
        val cached = oldJob()
        repository.update { it.copy(jobs = listOf(cached)) }
        repository.setSaved(cached.id, true)
        val saved = repository.current().applications.single().copy(notes = "My notes", draft = "My draft")
        repository.application(saved)
        assertTrue(repository.refresh(1_000_000))
        val result = repository.current()
        assertEquals(10, pages)
        assertEquals(1000, result.jobs.count { it.id.substringAfter(':').toInt() in 1..1000 })
        assertEquals(1001, result.jobs.size)
        assertTrue(result.failedSources.contains("Jobicy partial"))
        assertEquals(cached.title, result.jobs.single { it.id == cached.id }.title)
        assertEquals(cached.fetchedAt, result.jobs.single { it.id == cached.id }.fetchedAt)
        assertTrue(statusBatches.isNotEmpty())
        assertTrue(statusBatches.all { it.size in 1..100 })
        assertTrue(statusBatches.flatten().size <= 2000)
        assertEquals("My notes", result.applications.single().notes)
        assertEquals("My draft", result.applications.single().draft)
    }

    @Test fun malformedLaterPageKeepsPreviousCatalogRatherThanClaimingSuccessfulReplacement() = runBlocking {
        var pages = 0
        val network = object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String = when {
                url.startsWith("https://remotive.com/") -> """{"jobs":[]}"""
                url.startsWith("https://jobicy.com/api/jobs?") -> { pages++; if (pages == 1) page(1) else "{}" }
                else -> error("Failed source must not trigger status checks")
            }
        }
        val repository = JobsRepository(ApplicationProvider.getApplicationContext(), network, isOnline = { true })
        repository.erase()
        val cached = oldJob()
        repository.update { it.copy(jobs = listOf(cached)) }
        assertTrue(repository.refresh(1_000_000))
        val result = repository.current()
        assertEquals(2, pages)
        assertEquals(listOf(cached), result.jobs)
        assertTrue(result.failedSources.contains("Jobicy"))
        assertFalse(result.failedSources.contains("Jobicy partial"))
    }
    @Test fun catalogByteBudgetRetainsUsefulRowsAndSavedWorkWithoutStoreOverflow() = runBlocking {
        var pages = 0
        val description = "x".repeat(60_000)
        val network = object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String = when {
                url.startsWith("https://remotive.com/") -> """{"jobs":[]}"""
                url.startsWith("https://jobicy.com/api/jobs/status?") -> {
                    val ids = url.substringAfter("?ids=").split(',')
                    """{"jobs":[${ids.joinToString(",") { """{"id":$it,"status":"active"}""" }}]}"""
                }
                url.startsWith("https://jobicy.com/api/jobs?") -> {
                    pages++
                    check(pages <= 2)
                    val rows = ((pages - 1) * 85 + 1..pages * 85).joinToString(",") { id ->
                        """{"id":$id,"jobTitle":"Role $id","url":"https://jobicy.com/jobs/$id-role","jobDescription":"$description","pubDate":"2026-11-16T17:01:27+00:00"}"""
                    }
                    val raw = """{"jobs":[$rows],"hasMore":${pages == 1},"nextCursor":${if (pages == 1) "\"next\"" else "null"}}"""
                    assertTrue(raw.toByteArray().size < 8 * 1024 * 1024)
                    raw
                }
                else -> error("Unexpected endpoint: $url")
            }
        }
        val repository = JobsRepository(ApplicationProvider.getApplicationContext(), network, isOnline = { true })
        repository.erase()
        val cached = oldJob()
        repository.update { it.copy(jobs = listOf(cached)) }
        repository.setSaved(cached.id, true)
        repository.application(repository.current().applications.single().copy(notes = "Keep notes", draft = "Keep reviewed document"))
        assertTrue(repository.refresh(1_000_000))
        val result = repository.current()
        assertEquals(2, pages)
        assertTrue(result.jobs.isNotEmpty())
        assertTrue(result.jobs.size < 170)
        assertTrue(result.failedSources.contains("Catalog partial"))
        val encodedCatalog = kotlinx.serialization.json.Json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(JobPosting.serializer()), result.jobs)
        assertTrue(encodedCatalog.toByteArray().size <= 8 * 1024 * 1024)
        assertEquals("Keep notes", result.applications.single().notes)
        assertEquals("Keep reviewed document", result.applications.single().draft)
        assertEquals(cached.title, result.applications.single().snapshot?.title)
    }

}
