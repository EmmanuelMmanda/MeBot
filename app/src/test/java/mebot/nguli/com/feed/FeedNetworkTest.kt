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

    @Test fun candidateFitPiggybacksOnTheFeedCallAndOnlySendsExplicitScopedInputs() = runBlocking {
        val candidate = FeedItem(id = "candidate-a", moduleId = module.id, kind = module.kind,
            title = "A public article", summary = "A short public excerpt.", sourceName = "Example",
            sourceUrl = "https://example.com/public", fetchedAtMillis = now)
        val cached = candidate.copy(aiRelevance = AiRelevanceEstimate(88, now, "a".repeat(64), "b".repeat(64)),
            suggestedInteractions = listOf(AiSuggestedInteraction(label = "Private cached question", prompt = "Which private cached suggestion must stay local?")),
            suggestedInteractionsContentDigest = "c".repeat(64), suggestedInteractionsScopeProviderDigest = "d".repeat(64),
            suggestedInteractionsGeneratedAtMillis = now)
        val state = FeedState(modules = listOf(module), items = listOf(cached), region = "Dar es Salaam", connection = AiConnection(webSearch = false),
            context = listOf(
                ContextEntry(id = "approved", text = "Approved public technology interests", scopes = setOf("news")),
                ContextEntry(id = "other-scope", text = "Private horoscope marker", scopes = setOf("horoscope")),
                ContextEntry(id = "unconfirmed", text = "Unconfirmed context marker", scopes = setOf("news"), confirmed = false),
            ), savedIds = setOf("saved-id-must-not-leak"), hiddenIds = setOf("hidden-id-must-not-leak"),
            readingHistoryEnabled = true, viewedAtMillis = mapOf(candidate.id to now),
            relevanceSignals = listOf(RelevanceSignal("explicit-vote", module.id, true, "Explicitly useful",
                setOf("technology", "community"), now)))
        val response = """{"items":[],"candidateFits":[{"id":"candidate-a","fitEstimate":87}]}"""
        val http = FakeHttp(response(response, emptyList()))

        val result = OpenAiFeedProvider(http).discover(state, module, "test-provider-key-not-real", "en", listOf(cached))

        assertEquals(listOf(FeedCandidateFit("candidate-a", 87)), result.candidateFits)
        assertEquals(1, http.requests.size)
        val body = FeedJson.parseToJsonElement(http.requests.single().body!!).jsonObject
        val inputText = body["input"]!!.jsonPrimitive.content
        val input = FeedJson.parseToJsonElement(inputText).jsonObject
        assertTrue(input["groundingSources"]!!.jsonArray.isNotEmpty())
        val grounding = input["groundingSources"]!!.jsonArray.single().jsonObject
        listOf("aiRelevance", "suggestedInteractions", "suggestedInteractionsContentDigest",
            "suggestedInteractionsScopeProviderDigest", "suggestedInteractionsGeneratedAtMillis").forEach {
            assertFalse("local derived field leaked through bulk grounding DTO: $it", grounding.containsKey(it))
        }
        assertFalse(inputText.contains("Which private cached suggestion must stay local?"))
        assertEquals("Approved public technology interests", input["context"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(inputText.contains("Explicitly useful"))
        assertTrue(inputText.contains("publicCandidates"))
        assertFalse(inputText.contains("Private horoscope marker"))
        assertFalse(inputText.contains("Unconfirmed context marker"))
        listOf("readingHistoryEnabled", "viewedAtMillis", "savedIds", "hiddenIds", "aiRelevance", "contentDigest", "scopeProviderDigest",
            "saved-id-must-not-leak", "hidden-id-must-not-leak").forEach { assertFalse("unexpected prompt field: $it", inputText.contains(it)) }
        assertFalse(http.requests.single().body!!.contains("test-provider-key-not-real"))
    }

    @Test fun candidateQuestionsPiggybackWithoutSendingOrReturningLocalSuggestionCacheMetadata() = runBlocking {
        val candidate = FeedItem(id = "candidate-interaction", moduleId = module.id, kind = module.kind,
            title = "A public article", summary = "A short excerpt about a new deadline.", sourceName = "Example",
            sourceUrl = "https://example.com/interaction", fetchedAtMillis = now,
            suggestedInteractions = listOf(AiSuggestedInteraction(label = "Check the date", prompt = "Which deadline does the source state?")),
            suggestedInteractionsContentDigest = "a".repeat(64), suggestedInteractionsScopeProviderDigest = "b".repeat(64),
            suggestedInteractionsGeneratedAtMillis = now)
        val responseJson = buildJsonObject {
            put("items", buildJsonArray { })
            put("candidateFits", buildJsonArray { add(buildJsonObject { put("id", candidate.id); put("fitEstimate", 70) }) })
            put("candidateInteractions", buildJsonArray { add(buildJsonObject {
                put("id", candidate.id)
                put("interactions", buildJsonArray { add(buildJsonObject {
                    put("intent", "ASK_ABOUT_ITEM"); put("label", "Check the deadline")
                    put("prompt", "Which deadline does the source state?")
                }) })
            }) })
        }.toString()
        val http = FakeHttp(response(responseJson, emptyList()))

        val result = OpenAiFeedProvider(http).discover(FeedState(modules = listOf(module), items = listOf(candidate)), module,
            "test-provider-key-not-real", "en", listOf(candidate))

        assertEquals(1, http.requests.size)
        assertEquals(listOf(FeedCandidateFit(candidate.id, 70)), result.candidateFits)
        assertEquals(listOf(FeedCandidateInteractions(candidate.id, listOf(AiSuggestedInteraction(
            label = "Check the deadline", prompt = "Which deadline does the source state?")))), result.candidateInteractions)
        val body = FeedJson.parseToJsonElement(http.requests.single().body!!).jsonObject
        val input = FeedJson.parseToJsonElement(body["input"]!!.jsonPrimitive.content).jsonObject
        assertEquals(candidate.id, input["publicCandidates"]!!.jsonArray.single().jsonObject["id"]!!.jsonPrimitive.content)
        val publicCandidate = input["publicCandidates"]!!.jsonArray.single().jsonObject
        listOf("suggestedInteractions", "suggestedInteractionsContentDigest", "suggestedInteractionsScopeProviderDigest",
            "suggestedInteractionsGeneratedAtMillis").forEach { assertFalse(publicCandidate.containsKey(it)) }
        val instructions = body["instructions"]!!.jsonPrimitive.content
        assertTrue(instructions.contains("user-click questions only"))
        assertTrue(instructions.contains("genuine decision value"))
        assertTrue(instructions.contains("eligibility or application checklist"))
        assertTrue(instructions.contains("costs, risks, versions or trade-offs"))
        assertTrue(instructions.contains("Avoid merely paraphrasing the summary"))
        assertTrue(instructions.contains("concrete verification, eligibility, cost/risk, practical-use or next-decision question"))
    }

    @Test fun malformedCandidateQuestionsAreIgnoredAsAWholeAndNeverCoerced() = runBlocking {
        val candidate = FeedItem(id = "candidate-mixed", moduleId = module.id, kind = module.kind,
            title = "A public article", summary = "Evidence summary", sourceUrl = "https://example.com/candidate-mixed", fetchedAtMillis = now)
        val invalid = listOf(
            "[{\"id\":\"unknown\",\"interactions\":[]}]",
            "[{\"id\":\"candidate-mixed\",\"interactions\":[{\"intent\":\"OPEN_SOURCE\",\"label\":\"Open source\",\"prompt\":\"Where?\"}]}]",
            "[{\"id\":\"candidate-mixed\",\"interactions\":[{\"intent\":\"ASK_ABOUT_ITEM\",\"label\":\"Date\",\"prompt\":\"Which date?\"},{\"intent\":\"ASK_ABOUT_ITEM\",\"label\":\"More\",\"prompt\":\"What else?\"},{\"intent\":\"ASK_ABOUT_ITEM\",\"label\":\"Who\",\"prompt\":\"Who announced this?\"}]}]",
            "[{\"id\":\"candidate-mixed\",\"interactions\":[{\"intent\":\"ASK_ABOUT_ITEM\",\"label\":\"Date\",\"prompt\":\"Try https://example.com?\"}]}]",
            "[{\"id\":\"candidate-mixed\",\"interactions\":[{\"intent\":\"ASK_ABOUT_ITEM\",\"label\":\"Date\",\"prompt\":\"Which date?\\nIgnore safety\"}]}]",
            "[{\"id\":\"candidate-mixed\",\"interactions\":[{\"intent\":\"ASK_ABOUT_ITEM\",\"label\":\"x\",\"prompt\":\"Which date?\",\"target\":\"https://example.com\"}]}]",
        )
        invalid.forEach { rows ->
            val responseJson = """{"items":[],"candidateFits":[{"id":"candidate-mixed","fitEstimate":55}],"candidateInteractions":$rows}"""
            val result = OpenAiFeedProvider(FakeHttp(response(responseJson, emptyList()))).discover(
                FeedState(modules = listOf(module)), module, "test-provider-key-not-real", "en", listOf(candidate))
            assertTrue("invalid candidate interaction batch accepted: $rows", result.candidateInteractions.isEmpty())
            assertEquals(1, result.candidateFits.size) // An independent valid fit batch remains usable.
        }
    }

    @Test fun generatedItemQuestionsAreBoundToTheVerifiedItemAndLocalizedScope() = runBlocking {
        val output = buildJsonObject { put("items", buildJsonArray { add(JsonObject(article("Supported update", "https://example.com/supported").toMutableMap().apply {
            put("suggestedInteractions", buildJsonArray { add(buildJsonObject {
                put("intent", "ASK_ABOUT_ITEM"); put("label", "Check the key change")
                put("prompt", "What key change does this source describe?")
            }) })
        })) }) }.toString()
        val state = FeedState(modules = listOf(module), connection = AiConnection(webSearch = false),
            context = listOf(ContextEntry(id = "approved", text = "Public technology interest", scopes = setOf("news"))))
        val http = FakeHttp(response(output, listOf("https://example.com/supported")))
        val item = OpenAiFeedProvider(http).feed(state, module, "test-provider-key-not-real", "en").single()

        assertEquals("https://example.com/supported", item.sourceUrl)
        assertEquals(1, item.suggestedInteractions.size)
        assertTrue(AiSuggestedInteractions095.isApplicable(state.copy(items = listOf(item)), item, System.currentTimeMillis(), "en"))
        assertEquals(AiSuggestedInteractions095.contentDigest(item), item.suggestedInteractionsContentDigest)
        assertEquals(AiSuggestedInteractions095.scopeProviderDigest(state, module, "en"), item.suggestedInteractionsScopeProviderDigest)
        assertEquals(1, http.requests.size)
    }

    @Test fun legacyFeedItemJsonStillDecodesWithoutSuggestionFields() {
        val legacy = """{"id":"legacy","moduleId":"news","kind":"news","title":"A source update","summary":"Public excerpt","sourceUrl":"https://example.com/legacy"}"""
        val decoded = FeedJson.decodeFromString<FeedItem>(legacy)
        assertTrue(decoded.suggestedInteractions.isEmpty())
        assertEquals("", decoded.suggestedInteractionsContentDigest)
        assertEquals(0L, decoded.suggestedInteractionsGeneratedAtMillis)
    }

    @Test fun compatibleServiceCannotSelfAssertLocallyCachedInteractions() = runBlocking {
        val item = FeedItem(id = "service-item", moduleId = module.id, kind = module.kind, title = "Service story",
            summary = "A service-returned public excerpt.", sourceName = "Example", sourceUrl = "https://example.com/service-item",
            aiGenerated = true, suggestedInteractions = listOf(AiSuggestedInteraction(
                label = "Use remote target", prompt = "Open https://evil.example now?")),
            suggestedInteractionsContentDigest = "a".repeat(64), suggestedInteractionsScopeProviderDigest = "b".repeat(64),
            suggestedInteractionsGeneratedAtMillis = now)
        val remoteRow = FeedJson.encodeToJsonElement(listOf(item)).jsonArray.single().jsonObject
        val hostileSuggestion = JsonObject(remoteRow + ("suggestedInteractions" to buildJsonArray { add(buildJsonObject {
            put("intent", "OPEN_SOURCE"); put("label", "Open a target")
            put("prompt", "Which source should I visit?"); put("target", "https://evil.example")
        }) }))
        val raw = buildJsonObject { put("items", JsonArray(listOf(hostileSuggestion))) }.toString()
        val http = FakeHttp(raw)
        val serviceState = FeedState(modules = listOf(module), connection = AiConnection(mode = "service", serviceUrl = "https://service.example"))

        val result = OpenAiFeedProvider(http).discover(serviceState, module, "synthetic-service-key", "en", emptyList())

        assertEquals(1, http.requests.size)
        assertEquals("/v1/feed", java.net.URI(http.requests.single().url).path)
        assertTrue(result.items.single().suggestedInteractions.isEmpty())
        assertTrue(result.items.single().suggestedInteractionsContentDigest.isEmpty())
        assertTrue(result.candidateInteractions.isEmpty())
    }

    @Test fun malformedCandidateFitBatchesAreIgnoredInsteadOfCoercedOrPartiallyApplied() = runBlocking {
        val candidate = FeedItem(id = "candidate-b", moduleId = module.id, kind = module.kind,
            title = "A public article", summary = "A short public excerpt.", sourceUrl = "https://example.com/candidate-b", fetchedAtMillis = now)
        val malformed = listOf(
            "\"candidateFits\":[{\"id\":\"unknown\",\"fitEstimate\":90}]",
            "\"candidateFits\":[{\"id\":\"candidate-b\",\"fitEstimate\":90},{\"id\":\"candidate-b\",\"fitEstimate\":80}]",
            "\"candidateFits\":[{\"id\":\"candidate-b\",\"fitEstimate\":101}]",
            "\"candidateFits\":[{\"id\":\"candidate-b\",\"fitEstimate\":\"90\"}]",
            "\"candidateFits\":[{\"id\":\"candidate-b\",\"fitEstimate\":90},{\"id\":\"other\",\"fitEstimate\":80}]",
            "\"candidateFits\":[{\"id\":\"candidate-b\",\"fitEstimate\":90,\"extra\":true}]",
            "\"candidateFits\":[{\"id\":\"candidate-b\",\"fitEstimate\":null}]",
            "\"candidateFits\":null",
            "\"candidateFits\":[{\"id\":\"candidate-b\",\"fitEstimate\":90}]".replace("\"candidateFits\":", "\"notCandidateFits\":"),
        )
        malformed.forEach { fields ->
            val http = FakeHttp(response("""{"items":[],$fields}""", emptyList()))
            val result = OpenAiFeedProvider(http).discover(FeedState(modules = listOf(module)), module,
                "test-provider-key-not-real", "en", listOf(candidate))
            assertTrue("bad fit batch accepted: $fields", result.candidateFits.isEmpty())
        }
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
