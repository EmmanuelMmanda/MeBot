package app.mynguli.feed

import android.app.Application
import app.mynguli.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringReader

/** Subscription contract fixtures, never real accounts, tokens or billable requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ChatGptResponsesIntegrationTest {
    private val connection = AiConnection(mode = "chatgpt", model = "account-visible-model")
    private val token = "synthetic-oauth-credential-for-tests"
    private val news = FeedModule(id = "news", kind = "news")

    @Test fun requestMatchesTheSubscriptionRouteWithoutApiOnlyParameters() {
        val body = ChatGptRequest.build(connection.model, "Be concise.", "Question as untrusted data", true, OpenAiFeedProvider.feedSchema)
        assertEquals(connection.model, body["model"]!!.jsonPrimitive.content)
        assertFalse(body["store"]!!.jsonPrimitive.boolean)
        assertTrue(body["stream"]!!.jsonPrimitive.boolean)
        val input = body["input"] as JsonArray
        assertTrue(input.isNotEmpty())
        assertTrue(input.none { it.jsonObject["role"]?.jsonPrimitive?.content == "system" })
        assertTrue(input.toString().contains("Question as untrusted data"))
        val unsupported = setOf("background", "conversation", "max_output_tokens", "max_tool_calls", "metadata", "moderation", "multi_agent", "prompt", "prompt_cache_retention", "safety_identifier", "temperature", "top_logprobs", "top_p", "truncation", "user", "previous_response_id")
        assertTrue("Unsupported fields: ${body.keys.intersect(unsupported)}", body.keys.intersect(unsupported).isEmpty())
        assertTrue(body["tools"]!!.jsonArray.all { it.jsonObject["type"]!!.jsonPrimitive.content == "web_search" })
        assertFalse(body.toString().contains(token))
    }

    @Test fun entertainmentRequestDoesNotClaimOrRequestSearchEvidence() {
        val body = ChatGptRequest.build(connection.model, "Entertainment only.", "Leo", false, OpenAiFeedProvider.feedSchema)
        assertFalse(body.containsKey("tools"))
        assertFalse(body.containsKey("tool_choice"))
    }

    @Test fun streamAcceptsCommentsCrLfAndMultilineDataThenReturnsOnlyTheCompletedResponse() {
        val response = completed("Only this verified final text.")
        val stream = ": keepalive\r\n\r\n" +
            "event: response.output_text.delta\r\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"Unfinished text\"}\r\n\r\n" +
            "event: response.completed\r\ndata: {\"type\":\"response.completed\",\r\ndata: \"response\":$response}\r\n\r\n"
        assertEquals(response, ChatGptSse.read(StringReader(stream)))
    }

    @Test fun finalizedItemsAreReassembledOnlyAfterTheCompletedEnvelope() = runBlocking {
        val stream = javaClass.getResourceAsStream("/chatgpt/completed-items.sse")!!.bufferedReader().use { it.readText() }
        val response = ChatGptSse.read(StringReader(stream))
        assertEquals(3, response["output"]!!.jsonArray.size)
        val provider = OpenAiFeedProvider(NeverApiHttp(), RecordingResponses(response))
        provider.check(connection, token)
        val items = provider.feed(FeedState(connection = connection), news, token, "en")
        assertEquals(listOf("Verified release"), items.map { it.title })
        assertEquals(listOf("https://example.com/release"), items.single().citations.map { it.url })
    }

    @Test fun nullableReasoningAndToolContentCannotHideACompletedAssistantMessage() = runBlocking {
        val response = buildJsonObject {
            put("id", "resp_fixture"); put("status", "completed")
            put("output", buildJsonArray {
                add(buildJsonObject { put("id", "rs_fixture"); put("type", "reasoning"); put("content", JsonNull); put("summary", buildJsonArray { }) })
                add(buildJsonObject { put("type", "web_search_call"); put("content", JsonNull); put("action", JsonNull) })
                add(completed("Ready.")["output"]!!.jsonArray.last())
            })
        }
        val provider = OpenAiFeedProvider(NeverApiHttp(), RecordingResponses(response))
        provider.check(connection, token)
        assertEquals("Ready.", provider.ask(FeedState(connection = connection), null, "Explain", token, "en").answer)
    }

    @Test fun completedEnvelopeRemainsAuthoritativeWithoutDuplicatingFinalizedItems() {
        val response = completed("Final response.")
        val item = JsonObject(response["output"]!!.jsonArray.single().jsonObject + ("id" to JsonPrimitive("msg_fixture")))
        val stream = event("response.output_item.done", buildJsonObject { put("output_index", 0); put("item", item) }) +
            event("response.completed", buildJsonObject { put("response", response) })
        assertEquals(response, ChatGptSse.read(StringReader(stream)))
    }

    @Test fun emptyTerminalCannotSilentlyDropATrailingUnfinishedOutputItem() {
        val response = completed("Ready.")
        val finalized = JsonObject(response["output"]!!.jsonArray.single().jsonObject + ("id" to JsonPrimitive("msg_finished")))
        val unfinished = buildJsonObject {
            put("id", "msg_unfinished"); put("type", "message"); put("status", "in_progress"); put("content", buildJsonArray { })
        }
        val prefix = event("response.output_item.done", buildJsonObject { put("output_index", 0); put("item", finalized) }) +
            event("response.output_item.added", buildJsonObject { put("output_index", 1); put("item", unfinished) })
        val emptyTerminal = JsonObject(response + ("output" to buildJsonArray { }))
        assertEquals("invalid_response", assertFailure {
            ChatGptSse.read(StringReader(prefix + event("response.completed", buildJsonObject { put("response", emptyTerminal) })))
        }.code)
        // An explicit terminal output remains authoritative and needs no reconstruction.
        assertEquals(response, ChatGptSse.read(StringReader(prefix + event("response.completed", buildJsonObject { put("response", response) }))))
    }

    @Test fun finalizedOutputDoesNotBypassAUsageFailureAtTheEnd() {
        val fixture = javaClass.getResourceAsStream("/chatgpt/completed-items.sse")!!.bufferedReader().use { it.readText() }
        val stream = fixture.substringBefore("event: response.completed") + event("response.failed", buildJsonObject {
            put("response", buildJsonObject { put("id", "resp_items_fixture"); put("status", "failed")
                put("error", buildJsonObject { put("code", "subscription_sharing_usage_limit_exceeded") }) })
        })
        assertEquals("chatgpt_limit", assertFailure { ChatGptSse.read(StringReader(stream)) }.code)
        assertEquals("chatgpt_interrupted", assertFailure { ChatGptSse.read(StringReader(fixture.substringBefore("event: response.completed"))) }.code)
    }

    @Test fun changedOutputIdentityAndSparseIndicesAreRejected() {
        val item = buildJsonObject { put("id", "msg_one"); put("type", "message"); put("content", buildJsonArray { }) }
        val added = event("response.output_item.added", buildJsonObject { put("output_index", 0); put("item", item) })
        val replaced = event("response.output_item.done", buildJsonObject { put("output_index", 0); put("item", JsonObject(item + ("id" to JsonPrimitive("msg_other")))) })
        assertFailure { ChatGptSse.read(StringReader(added + replaced)) }
        val sparse = event("response.output_item.done", buildJsonObject { put("output_index", 100); put("item", item) }) +
            event("response.completed", buildJsonObject { put("response", buildJsonObject { put("status", "completed"); put("output", buildJsonArray { }) }) })
        assertFailure { ChatGptSse.read(StringReader(sparse)) }
    }

    @Test fun defaultMessageEventAndAllStandardLineEndingsPreserveCompletion() {
        val response = completed("Ready.")
        val event = "\uFEFFevent: message\ndata: " + buildJsonObject { put("type", "response.completed"); put("response", response) } + "\n\n"
        listOf("\n", "\r\n", "\r").forEach { ending ->
            assertEquals(response, ChatGptSse.read(StringReader(event.replace("\n", ending))))
        }
    }

    @Test fun reasoningTextOrIncompleteMessagesCannotSatisfyAConnectionCheck() = runBlocking {
        val outputs = listOf(
            buildJsonObject { put("type", "reasoning"); put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", "Not an answer") }) }) },
            buildJsonObject { put("type", "message"); put("status", "incomplete"); put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", "Partial") }) }) },
        )
        outputs.forEach { output ->
            val response = buildJsonObject { put("status", "completed"); put("output", JsonArray(listOf(output))) }
            val failure = runCatching { OpenAiFeedProvider(NeverApiHttp(), RecordingResponses(response)).check(connection, token) }.exceptionOrNull()
            assertEquals("invalid_response", (failure as FeedFailure).code)
        }
    }

    @Test fun partialTextAndDoneMarkerDoNotProveSuccessfulInference() {
        val stream = event("response.output_text.delta", buildJsonObject { put("delta", "A tempting partial answer.") }) + "data: [DONE]\n\n"
        assertFailure { ChatGptSse.read(StringReader(stream)) }
    }

    @Test fun prematureEofDoesNotTurnDeltasIntoACompletedAnswer() {
        val stream = event("response.output_text.delta", buildJsonObject { put("delta", "Partial") })
        assertFailure { ChatGptSse.read(StringReader(stream)) }
    }

    @Test fun usageLimitAfterTextStillFailsTheWholeResponse() {
        val stream = event("response.output_text.delta", buildJsonObject { put("delta", "Partial answer") }) +
            event("response.failed", buildJsonObject { put("response", buildJsonObject {
                put("status", "failed"); put("error", buildJsonObject { put("code", "subscription_sharing_usage_limit_exceeded") })
            }) })
        val failure = assertFailure { ChatGptSse.read(StringReader(stream)) }
        assertEquals("chatgpt_limit", failure.code)
    }

    @Test fun temporaryUsageFailureAfterTextPreservesItsRetryableMeaning() {
        val stream = event("response.output_text.delta", buildJsonObject { put("delta", "Partial answer") }) +
            event("response.failed", buildJsonObject { put("response", buildJsonObject {
                put("status", "failed"); put("error", buildJsonObject { put("code", "subscription_sharing_usage_unavailable") })
            }) })
        val failure = assertFailure { ChatGptSse.read(StringReader(stream)) }
        assertEquals("chatgpt_unavailable", failure.code)
        assertNotEquals(ChatGptErrors.code("subscription_sharing_usage_limit_exceeded"), failure.code)
    }

    @Test fun incompleteResponseIsNeverAcceptedAsCompleted() {
        val stream = event("response.incomplete", buildJsonObject { put("response", buildJsonObject {
            put("status", "incomplete"); put("output", completed("Partial") ["output"]!!)
        }) })
        assertFailure { ChatGptSse.read(StringReader(stream)) }
    }

    @Test fun completionEventCannotHideAnIncompleteStatus() {
        val response = JsonObject(completed("Partial").toMutableMap().apply { put("status", JsonPrimitive("incomplete")) })
        assertFailure { ChatGptSse.read(StringReader(event("response.completed", buildJsonObject { put("response", response) }))) }
    }

    @Test fun completedStatusCannotHideAContradictoryProviderError() {
        val response = JsonObject(completed("A tempting answer") + ("error" to buildJsonObject {
            put("code", "subscription_sharing_usage_limit_exceeded")
            put("message", "Private provider detail")
        }))
        val failure = assertFailure { ChatGptSse.read(StringReader(event("response.completed", buildJsonObject { put("response", response) }))) }
        assertEquals("invalid_response", failure.code)
        assertEquals("completed_with_error", failure.diagnostic?.responseShape)
        assertEquals("subscription_sharing_usage_limit_exceeded", failure.diagnostic?.providerCode)
        assertFalse(failure.diagnostic.toString().contains("Private"))
    }

    @Test fun explicitErrorEventAfterDeltasIsNotIgnored() {
        val stream = event("response.output_text.delta", buildJsonObject { put("delta", "Partial") }) +
            event("error", buildJsonObject { put("code", "subscription_sharing_unsupported_capability"); put("param", "tools") })
        val failure = assertFailure { ChatGptSse.read(StringReader(stream)) }
        assertEquals("chatgpt_capability", failure.code)
    }

    @Test fun malformedCompletedPayloadIsRejected() {
        listOf(
            "event: response.completed\ndata: broken-json\n\n",
            "event: response.completed\ndata: {\"type\":\"response.completed\"}\n\n",
            "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":null}\n\n",
        ).forEach { stream -> assertFailure { ChatGptSse.read(StringReader(stream)) } }
    }

    @Test fun unboundedEventCannotExhaustTheFeedReader() {
        val stream = "event: response.output_text.delta\ndata: {\"type\":\"response.output_text.delta\",\"delta\":\"" + "x".repeat(8 * 1024 * 1024) + "\"}\n\n"
        assertFailure { ChatGptSse.read(StringReader(stream)) }
    }

    @Test fun directAdmissionErrorsNeedNoStandardErrorEnvelope() {
        val detail = buildJsonObject { put("detail", "Opaque admission diagnostic, not a stable code") }
        assertNotEquals(ChatGptErrors.code(401, detail), ChatGptErrors.code(503, detail))
        assertNotEquals(ChatGptErrors.code(403, detail), ChatGptErrors.code(503, detail))
        assertFalse(ChatGptErrors.code(403, detail).contains("Opaque"))
    }

    @Test fun subscriptionProviderUsesOnlyConfirmedScopedContextAndRetrievedUrls() = runBlocking {
        val raw = buildJsonObject { put("items", buildJsonArray {
            add(article("Verified update", "https://example.com/verified"))
            add(article("Invented update", "https://example.com/unverified"))
        }) }.toString()
        val transport = RecordingResponses(completed(raw, listOf("https://example.com/verified")))
        val noApi = NeverApiHttp()
        val state = FeedState(connection = connection, context = listOf(
            ContextEntry("public", "Relevant technical interest", scopes = setOf("news")),
            ContextEntry("private", "Private horoscope detail", scopes = setOf("horoscope")),
            ContextEntry("guess", "Unconfirmed inferred fact", confirmed = false),
        ))
        val items = OpenAiFeedProvider(noApi, transport).feed(state, news, token, "en")
        assertEquals(listOf("Verified update"), items.map { it.title })
        assertEquals("https://example.com/verified", items.single().citations.single().url)
        assertEquals(0, noApi.calls)
        val body = transport.requests.single().first
        assertEquals(token, transport.requests.single().second)
        assertTrue(body["input"].toString().contains("Relevant technical interest"))
        assertFalse(body.toString().contains("Private horoscope detail"))
        assertFalse(body.toString().contains("Unconfirmed inferred fact"))
        assertFalse(body.toString().contains(token))
    }

    @Test fun subscriptionLimitNeverTriggersApiBillingFallback() = runBlocking {
        val noApi = NeverApiHttp()
        val transport = object : ChatGptResponses {
            override suspend fun complete(body: JsonObject, accessToken: String): JsonObject =
                throw FeedFailure(ChatGptErrors.code("subscription_sharing_usage_limit_exceeded"))
        }
        val failure = runCatching { OpenAiFeedProvider(noApi, transport).ask(FeedState(connection = connection), null, "Explain", token, "en") }.exceptionOrNull()
        assertTrue(failure is FeedFailure)
        assertEquals("chatgpt_limit", (failure as FeedFailure).code)
        assertEquals(0, noApi.calls)
    }

    @Test fun cancellationPropagatesWithoutRetryOrFallback() = runBlocking {
        val noApi = NeverApiHttp()
        var calls = 0
        val transport = object : ChatGptResponses {
            override suspend fun complete(body: JsonObject, accessToken: String): JsonObject {
                calls++
                throw CancellationException("test cancellation")
            }
        }
        val failure = runCatching { OpenAiFeedProvider(noApi, transport).ask(FeedState(connection = connection), null, "Explain", token, "en") }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertEquals(1, calls)
        assertEquals(0, noApi.calls)
    }

    @Test fun connectionCheckRequiresAnInferenceRequestWithTheChosenAccountModel() = runBlocking {
        val noApi = NeverApiHttp()
        val transport = RecordingResponses(completed("Connected."))
        OpenAiFeedProvider(noApi, transport).check(connection, token)
        assertEquals(0, noApi.calls)
        assertEquals(1, transport.requests.size)
        assertEquals(connection.model, transport.requests.single().first["model"]!!.jsonPrimitive.content)
        assertFalse(transport.requests.single().first.containsKey("tools"))
    }

    private fun assertFailure(action: () -> Any?): FeedFailure {
        val failure = runCatching(action).exceptionOrNull()
        assertTrue("Expected a bounded FeedFailure, got ${failure?.javaClass?.simpleName}", failure is FeedFailure)
        return failure as FeedFailure
    }

    private fun event(type: String, fields: JsonObject) = "event: $type\ndata: " +
        JsonObject(mapOf("type" to JsonPrimitive(type)) + fields) + "\n\n"

    private fun completed(text: String, urls: List<String> = emptyList()) = buildJsonObject {
        put("id", "resp_synthetic_fixture"); put("status", "completed")
        put("output", buildJsonArray {
            if (urls.isNotEmpty()) add(buildJsonObject {
                put("type", "web_search_call")
                put("action", buildJsonObject { put("sources", buildJsonArray {
                    urls.forEach { url -> add(buildJsonObject { put("title", "Fixture publication"); put("url", url) }) }
                }) })
            })
            add(buildJsonObject { put("type", "message"); put("content", buildJsonArray {
                add(buildJsonObject { put("type", "output_text"); put("text", text) })
            }) })
        })
    }

    private fun article(title: String, url: String) = buildJsonObject {
        put("title", title); put("summary", "Synthetic test content."); put("why", "Matches a confirmed interest.")
        put("sourceName", "Fixture"); put("sourceUrl", url); put("publishedAt", ""); put("deadline", ""); put("relevance", 80)
    }

    private class RecordingResponses(private val result: JsonObject) : ChatGptResponses {
        val requests = mutableListOf<Pair<JsonObject, String>>()
        override suspend fun complete(body: JsonObject, accessToken: String): JsonObject {
            requests += body to accessToken
            return result
        }
    }

    private class NeverApiHttp : FeedHttp {
        var calls = 0
        override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
            calls++
            error("API-key transport must never be called for ChatGPT mode")
        }
    }
}
