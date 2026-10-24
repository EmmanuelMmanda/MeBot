package app.mynguli.feed

import app.mynguli.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AiCitationMetadataTest {
    private val module = FeedModule(id = "news", kind = "news")
    private val state = FeedState(modules = listOf(module))
    private val source = "https://publisher.example/original"
    private val candidate = buildJsonObject {
        put("items", buildJsonArray { add(buildJsonObject {
            put("moduleId", module.id); put("title", "A source-backed result"); put("summary", "A verified change.")
            put("sourceUrl", source); put("contentType", "article")
        }) })
    }.toString()

    @Test fun flatAndNestedUrlCitationAnnotationsAreAccepted() = runBlocking {
        listOf(false, true).forEach { nested ->
            val metadata = buildJsonObject { put("url", source); put("title", "Original source") }
            val annotation = if (nested) buildJsonObject { put("type", "url_citation"); put("url_citation", metadata) }
                else JsonObject(metadata + ("type" to JsonPrimitive("url_citation")))
            val items = search(listOf(message(annotations = listOf(annotation))))
            assertEquals(source, items.single().sourceUrl)
            assertEquals(listOf(FeedCitation("Original source", source)), items.single().citations)
        }
    }

    @Test fun aUrlInsideOutputTextCannotInventEvidence() = runBlocking {
        assertInvalid(listOf(message()))
    }

    @Test fun arbitraryOutputActionsAndNonUrlAnnotationsCannotCertifyTheSource() = runBlocking {
        val sourceObject = buildJsonObject { put("url", source); put("title", "A metadata-shaped value") }
        val action = buildJsonObject { put("sources", buildJsonArray { add(sourceObject) }) }
        val fakeTool = buildJsonObject { put("type", "function_call"); put("action", action) }
        val fakeReasoning = buildJsonObject { put("type", "reasoning"); put("action", action) }
        listOf(fakeTool, fakeReasoning).forEach { output ->
            assertInvalid(listOf(output, message()))
        }
        assertInvalid(listOf(JsonObject(message() + ("action" to action))))
        listOf("file_citation", "image_citation", "", "unrecognized").forEach { type ->
            val annotation = JsonObject(sourceObject + ("type" to JsonPrimitive(type)))
            assertInvalid(listOf(message(annotations = listOf(annotation))))
        }
    }

    @Test fun onlyAssistantOutputTextAnnotationsAreEvidence() = runBlocking {
        val annotation = buildJsonObject { put("type", "url_citation"); put("title", "Original"); put("url", source) }
        assertInvalid(listOf(message(role = "user", annotations = listOf(annotation)), message()))
        assertInvalid(listOf(message(contentType = "reasoning_text", annotations = listOf(annotation)), message()))
        assertInvalid(listOf(message(contentType = "refusal", annotations = listOf(annotation)), message()))
    }

    private suspend fun assertInvalid(output: List<JsonObject>) {
        val failure = runCatching { search(output) }.exceptionOrNull()
        assertTrue(failure is FeedFailure)
        assertEquals("invalid_response", (failure as FeedFailure).code)
    }

    private suspend fun search(output: List<JsonObject>): List<FeedItem> {
        var calls = 0
        val wire = buildJsonObject { put("status", "completed"); put("output", JsonArray(output)) }.toString()
        val http = object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String { calls++; return wire }
        }
        return try { OpenAiFeedProvider(http).search(state, listOf(module), "Find an update", "synthetic-key", "en") }
        finally { assertEquals(1, calls) }
    }

    private fun message(role: String = "assistant", contentType: String = "output_text", annotations: List<JsonObject> = emptyList()) = buildJsonObject {
        put("type", "message"); put("role", role); put("status", "completed")
        put("content", buildJsonArray { add(buildJsonObject {
            put("type", contentType); put("text", candidate); put("annotations", JsonArray(annotations))
        }) })
    }
}
