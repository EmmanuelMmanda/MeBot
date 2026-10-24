package app.mynguli.feed

import app.mynguli.core.AiConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Owner-triggered checks must verify the selected model, not only a catalog or health page. */
class AiConnectionCheckTest {
    private val direct = AiConnection(mode = "direct", model = "account-selected-model")
    private val service = AiConnection(mode = "service", model = "service-selected-model", serviceUrl = "https://service.example/")
    private val secret = "synthetic-api-key"

    @Test fun apiKeyCheckMakesOneSupportedNonstreamInferenceWithTheSelectedModel() = runBlocking {
        val http = FakeHttp(completed())
        OpenAiFeedProvider(http).check(direct, secret)
        val request = http.requests.single()
        assertEquals("https://api.openai.com/v1/responses", request.url)
        assertEquals("POST", request.method)
        assertEquals(secret, request.bearer)
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals(direct.model, body["model"]!!.jsonPrimitive.content)
        assertEquals(false, body["stream"]!!.jsonPrimitive.boolean)
        assertEquals(false, body["store"]!!.jsonPrimitive.boolean)
        assertFalse(body.containsKey("tools"))
        assertFalse(request.body.contains(secret))
    }

    @Test fun apiCatalogObjectsPartialAnswersAndMalformedBodiesAreNotConnections() = runBlocking {
        listOf(
            """{"id":"account-selected-model","object":"model"}""",
            completed(status = "incomplete"),
            completed(text = ""),
            """{"status":"completed","output":[]}""",
            """{"status":"completed","output":[{"type":"reasoning","content":[{"type":"output_text","text":"Thinking"}]}]}""",
            "<html>Gateway page</html>",
            "[]",
        ).forEach { wire ->
            val http = FakeHttp(wire)
            val failure = runCatching { OpenAiFeedProvider(http).check(direct, secret) }.exceptionOrNull()
            assertTrue(failure is FeedFailure)
            assertEquals("invalid_response", (failure as FeedFailure).code)
            assertEquals(1, http.requests.size)
        }
    }

    @Test fun apiStructuredFailureHasSafeDiagnosticsAndNoFallback() = runBlocking {
        val http = FakeHttp("""{"status":"failed","error":{"code":"insufficient_quota","param":"model","message":"Do not expose $secret"}}""")
        val failure = runCatching { OpenAiFeedProvider(http).check(direct, secret) }.exceptionOrNull() as FeedFailure
        assertEquals("quota", failure.code)
        assertEquals("error_object", failure.diagnostic?.responseShape)
        assertEquals("failed", failure.diagnostic?.responseStatus)
        assertEquals("insufficient_quota", failure.diagnostic?.providerCode)
        assertEquals("model", failure.diagnostic?.providerParam)
        assertNull(failure.diagnostic?.httpStatus) // FeedHttp does not expose response metadata.
        assertFalse(failure.diagnostic.toString().contains(secret))
        assertEquals(1, http.requests.size)
    }

    @Test fun serviceCheckRequiresAnAuthenticatedAnswerWithNoPersonalContext() = runBlocking {
        val http = FakeHttp("""{"answer":"Ready.","citations":[]}""")
        OpenAiFeedProvider(http).check(service, secret)
        val request = http.requests.single()
        assertEquals("https://service.example/v1/ask", request.url)
        assertEquals("POST", request.method)
        assertEquals(secret, request.bearer)
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals(service.model, body["model"]!!.jsonPrimitive.content)
        assertTrue(body["context"]!!.jsonArray.isEmpty())
        assertEquals(JsonNull, body["item"])
        assertFalse(body.containsKey("name"))
        assertFalse(body.containsKey("region"))
    }

    @Test fun serviceHealthAndInvalidAnswerCannotCertifyInference() = runBlocking {
        listOf("""{"ok":true}""", """{"answer":""}""", """{"answer":true}""", "not-json").forEach { wire ->
            val http = FakeHttp(wire)
            val failure = runCatching { OpenAiFeedProvider(http).check(service, secret) }.exceptionOrNull()
            assertTrue(failure is FeedFailure)
            assertEquals("invalid_response", (failure as FeedFailure).code)
            assertEquals(1, http.requests.size)
        }
    }

    @Test fun cancellationNeverBecomesAProviderFailureOrAnAutomaticRetry() = runBlocking {
        listOf(direct, service).forEach { connection ->
            var calls = 0
            val http = object : FeedHttp {
                override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
                    calls++
                    throw CancellationException("fixture cancellation")
                }
            }
            val failure = runCatching { OpenAiFeedProvider(http).check(connection, secret) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(1, calls)
        }
    }

    private fun completed(status: String = "completed", text: String = "Ready.") = buildJsonObject {
        put("status", status)
        put("output", buildJsonArray {
            add(buildJsonObject {
                put("type", "message"); put("role", "assistant"); put("status", "completed")
                put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
            })
        })
    }.toString()

    private data class Request(val url: String, val method: String, val body: String?, val bearer: String?)
    private class FakeHttp(private val wire: String) : FeedHttp {
        val requests = mutableListOf<Request>()
        override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
            requests += Request(url, method, body, bearer)
            return wire
        }
    }
}
