package mebot.nguli.com.feed

import android.app.Application
import mebot.nguli.com.core.*
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class FeedNetworkTest {
    private val source = FeedSource("Example publication", "https://example.com/feed.xml")
    private val module = FeedModule(id = "news", kind = "news", sources = listOf(source))
    private val now = Instant.parse("2026-10-23T08:00:00Z").toEpochMilli()

    @Test fun rssKeepsSourceAndPublicationTimeAndRemovesMarkup() {
        val xml = """<rss version="2.0"><channel><item>
            <title>A useful update</title><link>https://example.com/update?utm_source=rss</link>
            <description><![CDATA[<p>One <b>useful</b> change.</p>]]></description>
            <pubDate>Thu, 22 Oct 2026 08:00:00 GMT</pubDate>
            </item></channel></rss>"""
        val item = RssSourceReader().parse(xml, source, module, now).single()
        assertEquals("A useful update", item.title)
        assertEquals("One useful change.", item.summary)
        assertEquals(Instant.parse("2026-10-22T08:00:00Z").toEpochMilli(), item.publishedAtMillis)
        assertEquals(source.name, item.sourceName)
        assertEquals(FeedLogic.itemId("https://example.com/update"), item.id)
        assertFalse(item.aiGenerated)
    }

    @Test fun atomUsesTheArticleLinkAndSkipsNonWebLinks() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom">
            <entry><title>Release notes</title><link rel="self" href="https://example.com/api/entry"/>
            <link rel="alternate" href="https://example.com/release"/><summary>Details</summary>
            <updated>2026-10-22T12:00:00Z</updated></entry>
            <entry><title>Unsafe link</title><link href="javascript:alert(1)"/></entry>
            </feed>"""
        val item = RssSourceReader().parse(xml, source, module, now).single()
        assertEquals("https://example.com/release", item.sourceUrl)
        assertEquals(0L, item.publishedAtMillis)
        assertNull(item.temporal) // Atom updated alone proves neither publication nor a substantive edit.
    }

    @Test fun publisherDateNotAtomMetadataUpdateQualifiesToday() {
        val xml = """<feed xmlns="http://www.w3.org/2005/Atom"><entry><title>Source item</title>
            <link href="https://example.com/source"/><updated>2026-10-23T07:00:00Z</updated>
            <published>2026-10-22T12:00:00Z</published></entry></feed>"""
        val item = RssSourceReader().parse(xml, source, module, now).single()
        assertEquals(Instant.parse("2026-10-22T12:00:00Z").toEpochMilli(), item.temporal!!.publishedAtMillis)
        assertNull(item.temporal!!.substantiveUpdatedAtMillis)
        assertTrue(TodayFeed.edition(FeedState(modules = listOf(module), items = listOf(item)), now).isEmpty())
        val today = RssSourceReader().parse(xml.replace("2026-10-22T12:00:00Z", "2026-10-23T06:00:00Z"), source, module, now).single()
        assertEquals(TodayReason.PUBLISHED, TodayFeed.edition(FeedState(modules = listOf(module), items = listOf(today)), now).single().reason)
        val future = RssSourceReader().parse(xml.replace("2026-10-22T12:00:00Z", "2026-10-23T12:00:00Z"), source, module, now).single()
        assertNull(future.temporal)
    }

    @Test fun externalEntitiesAreRejectedBeforeParsing() {
        val invalidDocuments = listOf(
            """<!DOCTYPE rss [<!ENTITY external SYSTEM "file:///private/example">]><rss><channel/></rss>""",
            """<!-- a harmless note --><!DOCTYPE rss SYSTEM "https://example.com/remote.dtd"><rss/>""",
            """<rss><![CDATA[<!DOCTYPE html>]]><!ENTITY external SYSTEM "file:///private/example"></rss>""",
            """<rss><![CDATA[unterminated <!DOCTYPE html>""",
            """<!-- unterminated <!ENTITY external><rss/>""",
        )
        invalidDocuments.forEach { xml ->
            val failure = runCatching { RssSourceReader().parse(xml, source, module, now) }.exceptionOrNull()
            assertTrue(failure is FeedFailure)
            assertEquals("invalid_response", (failure as FeedFailure).code)
        }
    }

    @Test fun htmlDoctypeInsideCdataAndCommentsDoesNotRejectAValidFeed() {
        val xml = """<rss version="2.0"><channel>
            <!-- An article may discuss <!DOCTYPE html> or <!ENTITY example>. -->
            <item><title>Release notes</title><link>https://example.com/release</link>
            <description><![CDATA[<!DOCTYPE html PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN"
                "https://www.w3.org/TR/REC-html40/loose.dtd"><html><body>
                <p>A useful release.</p></body></html>]]></description>
            </item></channel></rss>"""
        val item = RssSourceReader().parse(xml, source, module, now).single()
        assertEquals("Release notes", item.title)
        assertEquals("A useful release.", item.summary)
        assertEquals("https://example.com/release", item.sourceUrl)
    }

    @Test fun factualAiItemsRequireRetrievedEvidenceAndOnlyScopedContextIsSent() = runBlocking {
        val items = buildJsonObject { put("items", buildJsonArray {
            add(article("Supported update", "https://example.com/supported"))
            add(article("Unverified claim", "https://example.com/invented"))
        }) }.toString()
        val http = FakeHttp(response(items, listOf("https://example.com/supported")))
        val state = FeedState(context = listOf(
            ContextEntry(id = "approved", text = "Public technical interests", scopes = setOf("news")),
            ContextEntry(id = "other", text = "Private horoscope note", scopes = setOf("horoscope")),
            ContextEntry(id = "unconfirmed", text = "Unconfirmed guess", confirmed = false),
        ))
        val result = OpenAiFeedProvider(http).feed(state, module, "test-key-not-a-real-secret", "en")
        assertEquals(listOf("Supported update"), result.map { it.title })
        assertEquals("https://example.com/supported", result.single().citations.single().url)
        val request = http.requests.single()
        assertEquals("https://api.openai.com/v1/responses", request.url)
        assertEquals("POST", request.method)
        assertEquals("test-key-not-a-real-secret", request.bearer)
        val body = FeedJson.parseToJsonElement(request.body!!).jsonObject
        assertEquals(false, body["store"]!!.jsonPrimitive.boolean)
        assertTrue(body["input"]!!.jsonPrimitive.content.contains("Public technical interests"))
        assertFalse(body["input"]!!.jsonPrimitive.content.contains("Private horoscope note"))
        assertFalse(body["input"]!!.jsonPrimitive.content.contains("Unconfirmed guess"))
        assertFalse(request.body.contains("test-key-not-a-real-secret"))
    }

    @Test fun incompleteAiResponsesNeverBecomeAnAnswer() = runBlocking {
        val http = FakeHttp("""{"status":"incomplete","output":[]} """)
        val failure = runCatching { OpenAiFeedProvider(http).ask(FeedState(), null, "Explain this", "test-key-not-real", "en") }.exceptionOrNull()
        assertTrue(failure is FeedFailure)
        assertEquals("invalid_response", (failure as FeedFailure).code)
    }

    @Test fun allUnverifiedCandidatesAreAnErrorRatherThanAnEmptySuccessfulRefresh() = runBlocking {
        val output = buildJsonObject { put("items", buildJsonArray {
            add(article("Unsupported headline", "https://example.com/unsupported"))
        }) }.toString()
        val provider = OpenAiFeedProvider(FakeHttp(response(output, emptyList())))
        val failure = runCatching { provider.feed(FeedState(), module, "test-key-not-real", "en") }.exceptionOrNull()
        assertEquals("invalid_response", (failure as FeedFailure).code)
    }

    @Test fun futureDatesRemainUnknownAndSourceNamesComeFromTheVerifiedUrl() = runBlocking {
        val output = buildJsonObject { put("items", buildJsonArray {
            add(JsonObject(article("Supported update", "https://example.com/supported").toMutableMap().apply {
                put("publishedAt", JsonPrimitive("2099-01-01T00:00:00Z"))
                put("sourceName", JsonPrimitive("A fabricated publisher"))
            }))
        }) }.toString()
        val provider = OpenAiFeedProvider(FakeHttp(response(output, listOf("https://example.com/supported"))))
        val item = provider.feed(FeedState(), module, "test-key-not-real", "en").single()
        assertEquals(0L, item.publishedAtMillis)
        assertEquals("example.com", item.sourceName)
    }

    @Test fun horoscopeDoesNotSearchOrFabricateCitations() = runBlocking {
        val http = FakeHttp(response(buildJsonObject { put("items", buildJsonArray {
            add(article("A quiet moment", ""))
        }) }.toString(), emptyList()))
        val module = FeedModule(id = "horoscope", kind = "horoscope", zodiacSign = "Leo")
        val result = OpenAiFeedProvider(http).feed(FeedState(), module, "test-key-not-real", "en").single()
        assertEquals("horoscope", result.kind)
        assertEquals("", result.sourceUrl)
        assertEquals(0L, result.publishedAtMillis)
        assertEquals("", result.deadline)
        assertTrue(result.citations.isEmpty())
        val body = FeedJson.parseToJsonElement(http.requests.single().body!!).jsonObject
        assertFalse(body.containsKey("tools"))
        assertTrue(body["instructions"]!!.jsonPrimitive.content.contains("entertainment"))
    }

    @Test fun moduleDiscoveryUsesItsPurposeAndOnlyItsRecentHistory() = runBlocking {
        val raw = buildJsonObject { put("items", buildJsonArray {
            add(JsonObject(article("A verified lesson", "https://example.com/lesson").toMutableMap().apply {
                put("contentType", JsonPrimitive("video"))
                put("deadline", JsonPrimitive("1999-01-01"))
                put("referenceUrls", buildJsonArray { add("https://example.com/teacher"); add("https://invented.example/unsupported") })
                put("media", buildJsonObject { put("url", "https://invented.example/fake-image.jpg") })
            }))
        }) }.toString()
        val http = FakeHttp(response(raw, listOf("https://example.com/lesson", "https://example.com/teacher")))
        val state = FeedState(context = listOf(
            ContextEntry(text = "Approved piano practice", scopes = setOf("music")),
            ContextEntry(text = "Private career interest", scopes = setOf("opportunities")),
        ), items = listOf(
            FeedItem("old-music", "music", "A previous lesson", "", sourceUrl = "https://example.com/previous", kind = "music"),
            FeedItem("old-career", "opportunities", "A private career listing", "", sourceUrl = "https://example.com/job", kind = "opportunities"),
        ))
        val item = OpenAiFeedProvider(http).feed(state, FeedModule(id = "music", kind = "music"), "test-key-not-real", "en").single()
        assertEquals("video", item.contentType)
        assertEquals("", item.deadline)
        assertNull(item.media)
        assertEquals(listOf("https://example.com/lesson", "https://example.com/teacher"), item.citations.map { it.url })
        val body = FeedJson.parseToJsonElement(http.requests.single().body!!).jsonObject
        val input = body["input"]!!.jsonPrimitive.content
        assertTrue(input.contains("Approved piano practice"))
        assertTrue(input.contains("A previous lesson"))
        assertFalse(input.contains("Private career interest"))
        assertFalse(input.contains("A private career listing"))
        assertTrue(body["instructions"]!!.jsonPrimitive.content.contains(ModuleCatalog.briefGuidance("music")))
        assertTrue(body["instructions"]!!.jsonPrimitive.content.contains("live web search"))
    }

    @Test fun emptyFocusUsesTheModuleGoalAndExplicitFocusWinsInBothAiModes() = runBlocking {
        val music = FeedModule(id = "music", kind = "music")
        listOf(music, music.copy(query = "  Official jazz piano lessons  ")).forEach { configured ->
            val expected = if (configured.query.isBlank()) ModuleCatalog.preset("music")!!.defaultQuery else "Official jazz piano lessons"
            val direct = FakeHttp(response("{\"items\":[]}", emptyList()))
            OpenAiFeedProvider(direct).feed(FeedState(), configured, "synthetic-key", "en")
            val body = Json.parseToJsonElement(direct.requests.single().body!!).jsonObject
            val input = Json.parseToJsonElement(body["input"]!!.jsonPrimitive.content).jsonObject
            assertEquals(expected, input["discoveryGoal"]!!.jsonPrimitive.content)
            val service = FakeHttp("{\"items\":[]}")
            OpenAiFeedProvider(service).feed(FeedState(connection = AiConnection(mode = "service", serviceUrl = "https://service.example")), configured, "synthetic-key", "en")
            val serviceBody = Json.parseToJsonElement(service.requests.single().body!!).jsonObject
            assertEquals(expected, serviceBody["modules"]!!.jsonArray.single().jsonObject["query"]!!.jsonPrimitive.content)
        }
        assertEquals("", music.query) // Request preparation never changes stored preferences.
    }

    @Test fun fencedJsonCompatibilityDoesNotLetUnsupportedSourcesThrough() = runBlocking {
        val json = buildJsonObject { put("items", buildJsonArray {
            add(article("Verified", "https://example.com/verified"))
            add(article("Invented", "https://example.com/invented"))
        }) }.toString()
        val result = OpenAiFeedProvider(FakeHttp(response("```json\n$json\n```", listOf("https://example.com/verified"))))
            .feed(FeedState(), module, "test-key-not-real", "en")
        assertEquals(listOf("Verified"), result.map { it.title })
    }

    private fun article(title: String, url: String) = buildJsonObject {
        put("title", title); put("summary", "Synthetic response used only by tests.")
        put("why", "Matches an approved interest."); put("sourceName", "Example"); put("sourceUrl", url)
        put("publishedAt", "2026-10-22T08:00:00Z"); put("deadline", ""); put("relevance", 80)
    }

    private fun response(output: String, urls: List<String>) = buildJsonObject {
        put("status", "completed")
        put("output", buildJsonArray {
            add(buildJsonObject { put("type", "web_search_call"); put("action", buildJsonObject {
                put("sources", buildJsonArray { urls.forEach { url -> add(buildJsonObject { put("title", "Example"); put("url", url) }) } })
            }) })
            add(buildJsonObject { put("type", "message"); put("content", buildJsonArray {
                add(buildJsonObject { put("type", "output_text"); put("text", output) })
            }) })
        })
    }.toString()

    private data class Request(val url: String, val method: String, val body: String?, val bearer: String?)
    private class FakeHttp(private val result: String) : FeedHttp {
        val requests = mutableListOf<Request>()
        override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
            requests += Request(url, method, body, bearer)
            return result
        }
    }
}
