package mebot.nguli.com.feed

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import mebot.nguli.com.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PublisherTemporal090Test {
    private val now = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
    private val url = "https://example.com/story"
    @Test fun publisherPublicationIsNotMetadataModificationOrUnrelatedStructuredArticle() {
        assertNull(PublisherTemporalParser.parse("<meta property='article:modified_time' content='2026-10-25T11:00:00Z'>", url, now))
        assertNull(PublisherTemporalParser.parse("<meta property='article:published_time' content='2026-10-26T11:00:00Z'>", url, now))
        val json = """<script type="application/ld+json">{"@type":"NewsArticle","url":"$url","datePublished":"2026-10-25T09:00:00Z","dateModified":"2026-10-25T11:00:00Z"}</script>"""
        val evidence = PublisherTemporalParser.parse(json, url, now)!!
        assertEquals(Instant.parse("2026-10-25T09:00:00Z").toEpochMilli(), evidence.publishedAtMillis)
        assertNull(evidence.substantiveUpdatedAtMillis)
        assertNull(PublisherTemporalParser.parse(json.replace(url, "https://example.com/related"), url, now))
        assertNull(PublisherTemporalParser.parse(json.replace("2026-10-25T09:00:00Z", "2026-10-25"), url, now))
    }
    @Test fun dateAndImageShareOneExistingBoundedRequestAndNoImageDoesNotLoseDate() = runBlocking {
        var requests = 0
        val http = object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
                requests++; assertNull(bearer)
                return "<meta property='article:published_time' content='2026-10-25T09:00:00Z'>"
            }
        }
        val item = FeedItem("story", "news", "Fixture", "Synthetic source", sourceUrl=url, aiGenerated=true)
        val result = PublisherMediaEnricher(http, clock={now}).enrich(listOf(item)).single()
        assertEquals(1, requests); assertNull(result.media); assertNotNull(result.temporal)
        assertEquals(item.id, result.id); assertEquals(item.moduleId, result.moduleId)
        assertTrue(TodayFeed.edition(FeedState(modules=listOf(FeedModule(id="news")),items=listOf(result)),now).isNotEmpty())
    }
    @Test fun serviceCannotSelfAssertPublisherVerification() = runBlocking {
        val module = FeedModule(id="news",kind="news")
        val item = FeedItem("fixture",module.id,"Synthetic item","Fixture",sourceUrl=url,
            temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now))
        val http = object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?) = "{\"items\":" + FeedJson.encodeToString(listOf(item)) + "}"
        }
        val state=FeedState(connection=AiConnection(mode="service",serviceUrl="https://service.example"))
        assertNull(OpenAiFeedProvider(http).feed(state,module,"synthetic-token","en").single().temporal)
    }
}
