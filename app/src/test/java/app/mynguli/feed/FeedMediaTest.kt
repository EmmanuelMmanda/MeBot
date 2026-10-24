package app.mynguli.feed

import android.app.Application
import app.mynguli.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FeedMediaTest {
    private val article = "https://publisher.example/stories/update"

    @Test fun publisherOpenGraphImagePreservesAttributionAndDecodesEntities() {
        val html = """<html><head><meta content='https://images.example/press/photo.jpg?w=1200&amp;h=800' property='og:image'></head></html>"""
        val media = PublisherMediaParser.parse(html, article, "Example publication")!!
        assertEquals("https://images.example/press/photo.jpg?w=1200&h=800", media.url)
        assertEquals(article, media.sourceUrl)
        assertEquals("Example publication", media.credit)
    }

    @Test fun relativeImagesResolveAgainstThePublisherAndUnsafeValuesAreIgnored() {
        val html = """<meta property="og:image" content="http://127.0.0.1/private"><meta NAME="twitter:image" content="/assets/photo.webp">"""
        assertEquals("https://publisher.example/assets/photo.webp", PublisherMediaParser.parse(html, article)!!.url)
        assertNull(PublisherMediaParser.parse("""<meta property="og:image" content="javascript:alert(1)">""", article))
        assertNull(PublisherMediaParser.parse("""<meta property="og:image" content="https://user:password@images.example/a.jpg">""", article))
    }

    @Test fun structuredArticleImageIsSupportedWithoutTreatingWebsiteLogosAsStoryMedia() {
        val html = """<script type="application/ld+json">{"@graph":[{"@type":"Organization","logo":"https://publisher.example/logo.png"},{"@type":"NewsArticle","image":[{"@type":"ImageObject","contentUrl":"https://cdn.example/story.jpg"}]}]}</script>"""
        assertEquals("https://cdn.example/story.jpg", PublisherMediaParser.parse(html, article)!!.url)
        assertNull(PublisherMediaParser.parse("""<script type="application/ld+json">{"@type":"Organization","image":"https://publisher.example/logo.png"}</script>""", article))
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun malformedMetadataAndNonPublicPagesDoNotBreakTheArticle() {
        assertNull(PublisherMediaParser.parse("<script type='application/ld+json'>{broken</script>", article))
        assertNull(PublisherMediaParser.parse("<meta property='og:image' content='/a.jpg'>", "http://localhost/page"))
        // Legacy Robolectric BitmapFactory shadows can return a synthetic bitmap
        // for arbitrary bytes. Exercise the native decoder used by this boundary.
        assertNull(FeedMediaRepository.decode("This is not an image".toByteArray()))
    }

    @Test fun metadataFailureLeavesContentAndExistingMediaIntactAndFetchesAreCapped() = runBlocking {
        val requests = mutableListOf<String>()
        val http = object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
                synchronized(requests) { requests += url }
                assertNull(bearer)
                if (url.endsWith("1")) throw FeedFailure("offline")
                return "<meta property='og:image' content='https://cdn.example/photo.jpg'>"
            }
        }
        val items = (1..8).map { index -> FeedItem("item$index", "news", "Story $index", "Original summary", sourceUrl = "https://publisher.example/$index") }
        val existing = FeedMedia("https://cdn.example/existing.jpg", "https://publisher.example/8", "Publisher")
        val result = PublisherMediaEnricher(http, maxItems = 3).enrich(items.dropLast(1) + items.last().copy(media = existing))
        assertEquals(3, requests.size)
        assertEquals(items.map { it.title }, result.map { it.title })
        assertEquals(items.map { it.summary }, result.map { it.summary })
        assertNull(result.first().media)
        assertNotNull(result[1].media)
        assertEquals(existing, result.last().media)
        assertNull(result[5].media)
    }

    @Test fun cancelledEnrichmentCannotCompleteAsSuccessfulContent() = runBlocking {
        val http = object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String = throw CancellationException("cancelled fixture")
        }
        val item = FeedItem("1", "news", "Story", "Summary", sourceUrl = article)
        assertTrue(runCatching { PublisherMediaEnricher(http).enrich(listOf(item)) }.exceptionOrNull() is CancellationException)
    }

    @Test fun rssImagesComeFromPublisherMetadataAndKeepItsSourceLink() {
        val source = FeedSource("Publisher", "https://publisher.example/feed.xml")
        val xml = """<rss xmlns:media="http://search.yahoo.com/mrss/"><channel><item><title>A sourced update</title><link>$article</link><media:content url="https://cdn.example/photo.jpg" medium="image"/><description>Details.</description></item></channel></rss>"""
        val item = RssSourceReader().parse(xml, source, FeedModule(id = "news", kind = "news"), 1792742400000L).single()
        assertEquals("https://cdn.example/photo.jpg", item.media!!.url)
        assertEquals(source.url, item.media!!.sourceUrl)
        assertEquals(source.name, item.media!!.credit)
    }
}
