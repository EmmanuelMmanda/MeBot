package app.mynguli.feed

import app.mynguli.feed.auth.ChatGptDiagnostic
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Exercises the production HTTP adapter against controlled wire bytes, never a paid provider. */
class ChatGptResponsesTransportTest {
    private val request = ChatGptRequest.build("catalog-visible-model", "Reply briefly.", "Connection check", false)
    private val token = "synthetic-access-token"

    @Test fun realAdapterConsumesFinalizedItemsWithOneAuthorizedRequestAndNoRedirect() = runBlocking {
        val fixture = javaClass.getResourceAsStream("/chatgpt/completed-items.sse")!!.use { it.readBytes() }
        val connection = FixtureConnection(ByteArrayInputStream(fixture))
        var calls = 0
        val transport = StreamingChatGptResponses({ uri ->
            assertEquals("https://api.openai.com/v1/responses", uri.toString())
            calls++
            connection
        })
        val response = transport.complete(request, token)
        assertEquals(1, calls)
        assertEquals("completed", response["status"]!!.jsonPrimitive.content)
        assertEquals(3, response["output"]!!.jsonArray.size)
        assertNotNull(responseOutputText(response))
        assertEquals(request, Json.parseToJsonElement(connection.written.toString("UTF-8")))
        assertEquals("Bearer $token", connection.getRequestProperty("Authorization"))
        assertEquals("POST", connection.requestMethod)
        assertEquals("text/event-stream", connection.getRequestProperty("Accept"))
        assertFalse(connection.instanceFollowRedirects)
        assertTrue(connection.disconnected)
    }

    @Test fun directAdmissionFailurePreservesSafeStatusShapeAndRequestId() = runBlocking {
        val body = """{"detail":"Private provider explanation containing $token"}"""
        val connection = FixtureConnection(ByteArrayInputStream(body.toByteArray()), status = 403, contentType = "application/json")
        val failure = failure { StreamingChatGptResponses({ connection }).complete(request, token) }
        assertEquals("chatgpt_policy", failure.code)
        assertEquals(ChatGptDiagnostic("inference", 403, "req_wire_fixture", "detail", responseMediaType = "application/json"), failure.diagnostic)
        assertFalse(failure.toString().contains(token))
        assertFalse(failure.diagnostic.toString().contains("Private"))
        assertTrue(connection.disconnected)
    }

    @Test fun anHtmlPageWithHttp200IsDiagnosedWithoutPretendingInferenceSucceeded() = runBlocking {
        val connection = FixtureConnection(ByteArrayInputStream("<html>A private login page</html>".toByteArray()), contentType = "text/html")
        val failure = failure { StreamingChatGptResponses({ connection }).complete(request, token) }
        assertEquals("chatgpt_invalid_response", failure.code)
        assertEquals(ChatGptDiagnostic("inference", 200, "req_wire_fixture", "html_body", responseMediaType = "text/html"), failure.diagnostic)
        assertFalse(failure.diagnostic.toString().contains("private login"))
    }

    @Test fun emptyCompletedStreamIsNotAWorkingConnection() = runBlocking {
        val wire = "event: response.completed\ndata: {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\",\"output\":[]}}\n\n"
        val connection = FixtureConnection(ByteArrayInputStream(wire.toByteArray()), requestId = "invalid request id containing spaces")
        val failure = failure { StreamingChatGptResponses({ connection }).complete(request, token) }
        assertEquals("invalid_response", failure.code)
        assertEquals(ChatGptDiagnostic("inference", 200, responseShape = "completed_without_output",
            responseMediaType = "text/event-stream", responseStatus = "completed"), failure.diagnostic)
    }

    @Test fun failedStreamRetainsTheUsageCodeAndItsHttpDiagnostic() = runBlocking {
        val wire = "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"
        val connection = FixtureConnection(ByteArrayInputStream(wire.toByteArray()))
        val failure = failure { StreamingChatGptResponses({ connection }).complete(request, token) }
        assertEquals("chatgpt_limit", failure.code)
        assertEquals(ChatGptDiagnostic("inference", 200, "req_wire_fixture", "event_stream",
            responseMediaType = "text/event-stream", responseStatus = "failed",
            providerCode = "subscription_sharing_usage_limit_exceeded"), failure.diagnostic)
    }

    @Test fun validEventsAreReadWhenMimeIsMissingOrRewrittenWithoutANewRequest() = runBlocking {
        val fixture = javaClass.getResourceAsStream("/chatgpt/completed-items.sse")!!.use { it.readBytes() }
        listOf(null, "application/json", "text/plain; charset=utf-8", "application/octet-stream", "TEXT/EVENT-STREAM; charset=UTF-8").forEach { mediaType ->
            var calls = 0
            val connection = FixtureConnection(ByteArrayInputStream(fixture), contentType = mediaType)
            val response = StreamingChatGptResponses({ calls++; connection }).complete(request, token)
            assertEquals("completed", response["status"]!!.jsonPrimitive.content)
            assertNotNull(responseOutputText(response))
            assertEquals(1, calls)
            assertTrue(connection.disconnected)
            assertEquals(true, Json.parseToJsonElement(connection.written.toString("UTF-8")).jsonObject["stream"]!!.jsonPrimitive.boolean)
        }
    }

    @Test fun mislabeledFailedEventsKeepTheirFailureAndSafeMediaDiagnosis() = runBlocking {
        val wire = "event: response.failed\ndata: {\"type\":\"response.failed\",\"response\":{\"status\":\"failed\",\"error\":{\"code\":\"subscription_sharing_unsupported_capability\",\"param\":\"tools[0].type\"}}}\n\n"
        val connection = FixtureConnection(ByteArrayInputStream(wire.toByteArray()), contentType = "application/json")
        val failure = failure { StreamingChatGptResponses({ connection }).complete(request, token) }
        assertEquals("chatgpt_capability", failure.code)
        assertEquals("event_stream_mislabeled", failure.diagnostic?.responseShape)
        assertEquals("application/json", failure.diagnostic?.responseMediaType)
        assertEquals("failed", failure.diagnostic?.responseStatus)
        assertEquals("subscription_sharing_unsupported_capability", failure.diagnostic?.providerCode)
        assertEquals("tools", failure.diagnostic?.providerParam)
    }

    @Test fun http200JsonErrorKeepsTheActualProviderFailureWithoutLeakingTheBody() = runBlocking {
        val wire = """{"error":{"code":"subscription_sharing_usage_limit_exceeded","param":"model","message":"Private $token content"}}"""
        val connection = FixtureConnection(ByteArrayInputStream(wire.toByteArray()), contentType = "application/json")
        val failure = failure { StreamingChatGptResponses({ connection }).complete(request, token) }
        assertEquals("chatgpt_limit", failure.code)
        assertEquals("error_object", failure.diagnostic?.responseShape)
        assertEquals("subscription_sharing_usage_limit_exceeded", failure.diagnostic?.providerCode)
        assertEquals("model", failure.diagnostic?.providerParam)
        assertFalse(failure.toString().contains(token))
        assertFalse(failure.diagnostic.toString().contains("Private"))
        assertTrue(connection.disconnected)
    }

    @Test fun plainCompletedJsonDoesNotInventTheRequiredSubscriptionCompletionEvent() = runBlocking {
        val wire = """{"status":"completed","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Ready."}]}]}"""
        val connection = FixtureConnection(ByteArrayInputStream(wire.toByteArray()), contentType = "application/json")
        val failure = failure { StreamingChatGptResponses({ connection }).complete(request, token) }
        assertEquals("chatgpt_invalid_response", failure.code)
        assertEquals("nonstream_response", failure.diagnostic?.responseShape)
        assertEquals("completed", failure.diagnostic?.responseStatus)
    }

    @Test fun aDeclaredEventStreamDoesNotMakeHtmlOrEmptyContentSuccessful() = runBlocking {
        listOf("<html>Gateway login</html>" to "html_body", "" to "empty_body", "not a response" to "text_body").forEach { (wire, shape) ->
            val connection = FixtureConnection(ByteArrayInputStream(wire.toByteArray()))
            val failure = failure { StreamingChatGptResponses({ connection }).complete(request, token) }
            assertEquals("chatgpt_invalid_response", failure.code)
            assertEquals(shape, failure.diagnostic?.responseShape)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun unexpectedHeaderAndErrorFieldsAreNeverCopiedToDiagnostics() = runBlocking {
        val wire = """{"error":{"code":"$token","param":"$token","message":"Private body"}}"""
        val connection = FixtureConnection(ByteArrayInputStream(wire.toByteArray()), contentType = "private/$token")
        val failure = failure { StreamingChatGptResponses({ connection }).complete(request, token) }
        assertEquals("provider_error", failure.code)
        assertEquals("other", failure.diagnostic?.responseMediaType)
        assertNull(failure.diagnostic?.providerCode)
        assertNull(failure.diagnostic?.providerParam)
        assertFalse(failure.diagnostic.toString().contains(token))
    }

    @Test fun jsonDiagnosticsAreBoundedAndMalformedJsonIsDistinct() = runBlocking {
        listOf("{" to "invalid_json", "{\"private\":\"" + "x".repeat(100_000) + "\"}" to "oversized_json").forEach { (wire, shape) ->
            val connection = FixtureConnection(ByteArrayInputStream(wire.toByteArray()), contentType = "application/json")
            val failure = failure { StreamingChatGptResponses({ connection }).complete(request, token) }
            assertEquals(shape, failure.diagnostic?.responseShape)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun aNonstreamPlanRequestIsRejectedBeforeSendingAnything() = runBlocking {
        var calls = 0
        val body = JsonObject(request + ("stream" to JsonPrimitive(false)))
        val failure = failure { StreamingChatGptResponses({ calls++; error("Must not send") }).complete(body, token) }
        assertEquals("invalid_input", failure.code)
        assertEquals(0, calls)
    }

    @Test fun actualAssistantDeltasArriveBeforeTheTerminalEventOnBothCredentialPaths() = runBlocking {
        listOf(true, false).forEach { planUsage ->
            val finalResponse = completedText("Final authoritative explanation.")
            val input = GatedInput(assistantDeltas("A first", " useful fragment."), event("response.completed", buildJsonObject { put("response", finalResponse) }))
            val connection = FixtureConnection(input)
            val seen = CopyOnWriteArrayList<String>()
            val firstText = CompletableDeferred<Unit>()
            val task = async {
                StreamingChatGptResponses({ connection }, planUsage = planUsage).stream(request, token) {
                    seen += it
                    firstText.complete(Unit)
                }
            }
            withTimeout(3000) { firstText.await() }
            assertFalse("A delta must precede completed inference", task.isCompleted)
            assertEquals("A first", seen.first())
            input.release()
            assertEquals(finalResponse, task.await())
            assertTrue(seen.contains("A first useful fragment."))
            assertEquals("Final authoritative explanation.", seen.last())
            assertTrue(connection.disconnected)
        }
    }

    @Test fun displayedDeltasDoNotConvertAnInterruptedStreamIntoSuccess() = runBlocking {
        listOf(true, false).forEach { planUsage ->
            val connection = FixtureConnection(ByteArrayInputStream(assistantDeltas("Unfinished explanation").toByteArray()))
            val seen = mutableListOf<String>()
            val failure = failure { StreamingChatGptResponses({ connection }, planUsage = planUsage).stream(request, token) { seen += it } }
            assertEquals(listOf("Unfinished explanation"), seen)
            assertEquals(if (planUsage) "chatgpt_interrupted" else "ai_interrupted", failure.code)
            assertTrue(connection.disconnected)
        }
    }

    @Test fun streamedQuotaFailureRetainsPartialTextWithoutBillingFallback() = runBlocking {
        val wire = assistantDeltas("Still provisional") + event("response.failed", buildJsonObject {
            put("response", buildJsonObject { put("status", "failed"); put("error", buildJsonObject { put("code", "insufficient_quota") }) })
        })
        val connection = FixtureConnection(ByteArrayInputStream(wire.toByteArray()))
        val seen = mutableListOf<String>()
        var calls = 0
        val failure = failure { StreamingChatGptResponses({ calls++; connection }, planUsage = false).stream(request, token) { seen += it } }
        assertEquals("quota", failure.code)
        assertEquals(listOf("Still provisional"), seen)
        assertEquals(1, calls)
        assertEquals("insufficient_quota", failure.diagnostic?.providerCode)
    }

    @Test fun reasoningAndUnidentifiedDeltasAreNeverPublishedAsAssistantText() = runBlocking {
        val unknown = event("response.output_text.delta", buildJsonObject { put("output_index", 0); put("content_index", 0); put("item_id", "rs_private"); put("delta", "Private reasoning") })
        val reasoning = event("response.output_item.added", buildJsonObject { put("output_index", 0); put("item", buildJsonObject { put("id", "rs_private"); put("type", "reasoning") }) })
        val finalResponse = completedText("The actual answer.")
        val wire = unknown + reasoning + unknown + event("response.completed", buildJsonObject { put("response", finalResponse) })
        val seen = mutableListOf<String>()
        val connection = FixtureConnection(ByteArrayInputStream(wire.toByteArray()))
        StreamingChatGptResponses({ connection }).stream(request, token) { seen += it }
        assertEquals(listOf("The actual answer."), seen)
    }

    @Test fun callerCancellationDisconnectsTheWireAndDoesNotBecomeAnInvalidResponse() = runBlocking {
        val input = WaitingInput()
        val connection = FixtureConnection(input)
        val task = async { StreamingChatGptResponses({ connection }).complete(request, token) }
        withTimeout(3000) { input.started.await() }
        task.cancel()
        try { task.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertTrue(connection.disconnected)
    }

    @Test fun deadlineDisconnectsTheWireAndOffersInterruptedStreamRecovery() = runBlocking {
        val input = WaitingInput()
        val connection = FixtureConnection(input)
        val failure = failure { StreamingChatGptResponses({ connection }, requestTimeoutMillis = 150).complete(request, token) }
        assertEquals("chatgpt_interrupted", failure.code)
        assertEquals("deadline", failure.diagnostic?.responseShape)
        assertTrue(connection.disconnected)
    }

    private suspend fun failure(action: suspend () -> Any?): FeedFailure {
        val result = runCatching { action() }.exceptionOrNull()
        assertTrue("Expected a safe FeedFailure, got ${result?.javaClass?.simpleName}", result is FeedFailure)
        return result as FeedFailure
    }

    private fun event(type: String, values: JsonObject) = "event: $type\ndata: " + JsonObject(mapOf("type" to JsonPrimitive(type)) + values) + "\n\n"

    private fun completedText(text: String) = buildJsonObject {
        put("status", "completed"); put("output", buildJsonArray {
            add(buildJsonObject { put("type", "message"); put("role", "assistant"); put("status", "completed")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
            })
        })
    }

    private fun assistantDeltas(vararg fragments: String): String = event("response.output_item.added", buildJsonObject {
        put("output_index", 0); put("item", buildJsonObject { put("id", "msg_stream"); put("type", "message"); put("role", "assistant"); put("status", "in_progress") })
    }) + fragments.joinToString("") { fragment -> event("response.output_text.delta", buildJsonObject {
        put("output_index", 0); put("content_index", 0); put("item_id", "msg_stream"); put("delta", fragment)
    }) }

    /** Returns the prefix immediately, then blocks the terminal event until the test releases it. */
    private class GatedInput(prefix: String, suffix: String) : InputStream() {
        private val before = ByteArrayInputStream(prefix.toByteArray())
        private val after = ByteArrayInputStream(suffix.toByteArray())
        private val gate = CountDownLatch(1)
        @Volatile private var closed = false
        fun release() { gate.countDown() }
        override fun read(): Int {
            val byte = ByteArray(1)
            return if (read(byte, 0, 1) < 0) -1 else byte[0].toInt() and 255
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (closed) throw IOException("fixture closed")
            if (before.available() > 0) return before.read(buffer, offset, length)
            if (!gate.await(5, TimeUnit.SECONDS)) throw IOException("fixture terminal timeout")
            if (closed) throw IOException("fixture closed")
            return after.read(buffer, offset, length)
        }
        override fun close() { closed = true; release() }
    }

    private class WaitingInput : InputStream() {
        val started = CompletableDeferred<Unit>()
        private val closed = CountDownLatch(1)
        override fun read(): Int {
            started.complete(Unit)
            if (!closed.await(5, TimeUnit.SECONDS)) throw IOException("fixture timeout")
            throw IOException("fixture closed")
        }
        override fun close() { closed.countDown() }
    }

    private class FixtureConnection(
        private val input: InputStream,
        private val status: Int = 200,
        private val contentType: String? = "text/event-stream; charset=utf-8",
        private val requestId: String = "req_wire_fixture",
    ) : HttpURLConnection(URL("https://api.openai.com/v1/responses")) {
        @Volatile var disconnected = false
        val written = ByteArrayOutputStream()
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnected = true; input.close() }
        override fun getResponseCode() = status
        override fun getContentType() = contentType
        override fun getHeaderField(name: String?): String? = if (name.equals("x-request-id", true)) requestId else null
        override fun getInputStream() = input
        override fun getErrorStream() = input
        override fun getOutputStream() = written
    }
}
