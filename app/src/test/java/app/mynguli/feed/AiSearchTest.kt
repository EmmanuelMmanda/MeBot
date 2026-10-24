package app.mynguli.feed

import app.mynguli.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic Responses fixtures: no account tokens, external requests or paid inference. */
class AiSearchTest {
    private val news = FeedModule(id = "local-news", kind = "news")
    private val technology = FeedModule(id = "tools", kind = "technology", query = "Useful development tools")
    private val disabled = FeedModule(id = "disabled", kind = "security", enabled = false)
    private val horoscope = FeedModule(id = "horoscope", kind = "horoscope", zodiacSign = "aries")
    private val state = FeedState(
        connection = AiConnection(model = "selected-model"),
        modules = listOf(news, technology, disabled, horoscope),
        context = listOf(
            ContextEntry(id = "news-note", text = "Approved news interest", scopes = setOf("news")),
            ContextEntry(id = "tech-note", text = "Approved technical interest", scopes = setOf("technology")),
            ContextEntry(id = "unconfirmed", text = "Unconfirmed private note", scopes = setOf("news"), confirmed = false),
            ContextEntry(id = "unrelated", text = "Unrelated private note", scopes = setOf("music")),
        ),
    )
    private val secret = "synthetic-search-credential"
    private val source = "https://publisher.example/original"

    @Test fun oneRequestSearchesSelectedModulesWithOnlyApprovedScopedContext() = runBlocking {
        val http = FakeHttp(response(listOf(candidate(news.id, source)), listOf(source)).toString())
        val result = OpenAiFeedProvider(http).search(state, listOf(news, technology), "  current useful tools  ", secret, "en")
        assertEquals(news.id, result.single().moduleId)
        val request = http.requests.single()
        assertEquals("https://api.openai.com/v1/responses", request.url)
        assertEquals(secret, request.bearer)
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("selected-model", body["model"]!!.jsonPrimitive.content)
        assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)
        val input = Json.parseToJsonElement(body["input"]!!.jsonPrimitive.content).jsonObject
        assertEquals("current useful tools", input["query"]!!.jsonPrimitive.content)
        val modules = input["modules"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(news.id, technology.id), modules.map { it["moduleId"]!!.jsonPrimitive.content })
        assertTrue(modules.all { it["goal"]!!.jsonPrimitive.content.isNotBlank() })
        assertEquals(listOf("news-note"), modules[0]["contextIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("tech-note"), modules[1]["contextIds"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(setOf("news-note", "tech-note"), input["context"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }.toSet())
        assertFalse(request.body.contains("Unconfirmed private note"))
        assertFalse(request.body.contains("Unrelated private note"))
        assertFalse(request.body.contains(secret))
        val schemaItem = body["text"]!!.jsonObject["format"]!!.jsonObject["schema"]!!.jsonObject["properties"]!!.jsonObject["items"]!!.jsonObject["items"]!!.jsonObject
        assertTrue(schemaItem["required"]!!.jsonArray.map { it.jsonPrimitive.content }.containsAll(listOf("moduleId", "keyPoints", "nextStep", "caveat")))
    }

    @Test fun resultsMustUseAnEnabledSelectedModuleAndAnActuallyConsultedSource() = runBlocking {
        val secondary = "https://publisher.example/context"
        val invented = "https://invented.example/uncited"
        val good = JsonObject(candidate(technology.id, source) + mapOf(
            "kind" to JsonPrimitive("horoscope"), "sourceName" to JsonPrimitive("Invented source label"),
            "referenceUrls" to buildJsonArray { add(secondary); add(invented) },
            "keyPoints" to buildJsonArray { repeat(6) { add("x".repeat(420)) } },
            "nextStep" to JsonPrimitive("n".repeat(900)), "caveat" to JsonPrimitive("c".repeat(700)),
            "media" to buildJsonObject { put("url", "https://invented.example/image.jpg") },
        ))
        val http = FakeHttp(response(listOf(candidate("unknown", source), candidate(news.id, invented), good), listOf(source, secondary)).toString())
        val item = OpenAiFeedProvider(http).search(state, listOf(news, technology), "Find an update", secret, "en").single()
        assertEquals(technology.id, item.moduleId)
        assertEquals("technology", item.kind)
        assertEquals("publisher.example", item.sourceName)
        assertEquals(setOf(source, secondary), item.citations.map { it.url }.toSet())
        assertEquals(4, item.keyPoints.size)
        assertTrue(item.keyPoints.all { it.length == 400 })
        assertEquals(800, item.nextStep.length)
        assertEquals(600, item.caveat.length)
        assertNull(item.media)
    }

    @Test fun unusableCandidatesAreFailuresAndAValidEmptySearchIsEmpty() = runBlocking {
        val invalid = FakeHttp(response(listOf(candidate(news.id, source)), emptyList()).toString())
        val failure = runCatching { OpenAiFeedProvider(invalid).search(state, listOf(news), "Search", secret, "en") }.exceptionOrNull()
        assertEquals("invalid_response", (failure as FeedFailure).code)
        val empty = FakeHttp(response(emptyList(), emptyList()).toString())
        assertTrue(OpenAiFeedProvider(empty).search(state, listOf(news), "Search", secret, "en").isEmpty())
        assertEquals(1, invalid.requests.size)
        assertEquals(1, empty.requests.size)
    }

    @Test fun duplicateSourcesAreDeduplicatedAndResultCountIsBounded() = runBlocking {
        val urls = (1..10).map { "https://publisher.example/story-$it" }
        val rows = listOf(candidate(news.id, urls.first() + "?utm_source=fixture")) + urls.map { candidate(news.id, it) }
        val http = FakeHttp(response(rows, urls).toString())
        val items = OpenAiFeedProvider(http).search(state, listOf(news), "Search", secret, "en")
        assertEquals(8, items.size)
        assertEquals(8, items.map { it.id }.distinct().size)
    }

    @Test fun subscriptionSearchKeepsTheSelectedAccountRouteAndRequiresNoApiCredential() = runBlocking {
        var calls = 0
        val transport = object : ChatGptResponses {
            override suspend fun complete(body: JsonObject, accessToken: String): JsonObject {
                calls++
                assertEquals(secret, accessToken)
                assertEquals("plan-model", body["model"]!!.jsonPrimitive.content)
                assertTrue(body["stream"]!!.jsonPrimitive.boolean)
                assertFalse(body["store"]!!.jsonPrimitive.boolean)
                assertEquals("required", body["tool_choice"]!!.jsonPrimitive.content)
                assertTrue(body["instructions"]!!.jsonPrimitive.content.contains("moduleId"))
                return response(listOf(candidate(news.id, source)), listOf(source))
            }
        }
        val forbiddenHttp = object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String = error("No alternate billing path")
        }
        val result = OpenAiFeedProvider(forbiddenHttp, transport).search(
            state.copy(connection = AiConnection(mode = "chatgpt", model = "plan-model", accountId = "synthetic-account")), listOf(news), "Search", secret, "en")
        assertEquals(1, result.size)
        assertEquals(1, calls)
    }

    @Test fun unsupportedOrUnselectedModulesDoNotMakeRequests() = runBlocking {
        val http = FakeHttp("")
        val provider = OpenAiFeedProvider(http)
        listOf(emptyList(), listOf(disabled), listOf(horoscope), listOf(news, news), listOf(news.copy(query = "not the saved configuration"))).forEach { modules ->
            assertEquals("invalid_input", (runCatching { provider.search(state, modules, "Search", secret, "en") }.exceptionOrNull() as FeedFailure).code)
        }
        assertEquals("invalid_input", (runCatching { provider.search(state, listOf(news), " ", secret, "en") }.exceptionOrNull() as FeedFailure).code)
        assertTrue(http.requests.isEmpty())
    }

    @Test fun serviceSearchUsesItsConfiguredEndpointAndChecksModuleAndCitationContract() = runBlocking {
        val item = FeedItem("server-generated-id", news.id, "A source-backed result", "A concise summary.",
            sourceUrl = source, citations = listOf(FeedCitation("Original", source)), kind = news.kind)
        val wire = buildJsonObject { put("items", FeedJson.encodeToJsonElement(listOf(item))) }.toString()
        val http = FakeHttp(wire)
        val service = state.copy(connection = AiConnection(mode = "service", model = "service-model", serviceUrl = "https://service.example/"))
        val result = OpenAiFeedProvider(http).search(service, listOf(news), "Find updates", secret, "sw")
        assertEquals(FeedLogic.itemId(source), result.single().id)
        val request = http.requests.single()
        assertEquals("https://service.example/v1/search", request.url)
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("sw", body["language"]!!.jsonPrimitive.content)
        assertEquals("service-model", body["model"]!!.jsonPrimitive.content)
        assertEquals(8, body["limit"]!!.jsonPrimitive.int)
        assertEquals(listOf("news-note"), body["context"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertTrue(body["modules"]!!.jsonArray.single().jsonObject["query"]!!.jsonPrimitive.content.isNotBlank())
        listOf(item.copy(moduleId = "unselected"), item.copy(kind = "technology"), item.copy(citations = emptyList())).forEach { invalid ->
            val badHttp = FakeHttp(buildJsonObject { put("items", FeedJson.encodeToJsonElement(listOf(invalid))) }.toString())
            assertEquals("invalid_response", (runCatching { OpenAiFeedProvider(badHttp).search(service, listOf(news), "Search", secret, "en") }.exceptionOrNull() as FeedFailure).code)
        }
    }

    @Test fun cancellationIsNotRetriedOrConvertedToEmptyResults() = runBlocking {
        var calls = 0
        val http = object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
                calls++
                throw CancellationException("Synthetic cancellation")
            }
        }
        assertTrue(runCatching { OpenAiFeedProvider(http).search(state, listOf(news), "Search", secret, "en") }.exceptionOrNull() is CancellationException)
        assertEquals(1, calls)
    }

    private fun candidate(moduleId: String, url: String) = buildJsonObject {
        put("moduleId", moduleId); put("title", "A source-backed update"); put("summary", "The source describes a useful change.")
        put("why", "Matches the search."); put("sourceName", "Original publisher"); put("sourceUrl", url)
        put("publishedAt", ""); put("deadline", ""); put("relevance", 80); put("contentType", "article")
        put("referenceUrls", buildJsonArray { }); put("keyPoints", buildJsonArray { add("A verified detail.") })
        put("nextStep", "Read the original update."); put("caveat", "")
    }

    private fun response(items: List<JsonObject>, sources: List<String>) = buildJsonObject {
        put("status", "completed")
        put("output", buildJsonArray {
            add(buildJsonObject {
                put("type", "web_search_call")
                put("action", buildJsonObject { put("sources", buildJsonArray { sources.forEach { url ->
                    add(buildJsonObject { put("url", url); put("title", "Original source") })
                } }) })
            })
            add(buildJsonObject {
                put("type", "message"); put("role", "assistant"); put("status", "completed")
                put("content", buildJsonArray { add(buildJsonObject {
                    put("type", "output_text"); put("text", buildJsonObject { put("items", JsonArray(items)) }.toString())
                }) })
            })
        })
    }

    private data class Request(val url: String, val body: String?, val bearer: String?)
    private class FakeHttp(private val wire: String) : FeedHttp {
        val requests = mutableListOf<Request>()
        override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
            requests += Request(url, body, bearer)
            return wire
        }
    }
}
