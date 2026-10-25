package mebot.nguli.com.feed

import mebot.nguli.com.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real provider payloads with synthetic transports; no live inference or credentials. */
class ModuleDefaultRequestTest {
    private val emptyFeed = completed("""{"items":[]}""")
    private val secret = "synthetic-module-defaults-credential"

    @Test fun everyModuleCarriesItsDefaultPurposeAndOnlyItsApprovedNotesOnBothCredentialRoutes() = runBlocking {
        listOf("direct", "chatgpt").forEach { mode ->
            ModuleCatalog.kinds.forEach { kind ->
                val module = FeedModule(id = "selected-$kind", kind = kind, query = "Configured focus for $kind", zodiacSign = if (kind == "horoscope") "aries" else "")
                val state = scopedState(module, mode)
                val http = RecordingHttp(emptyFeed.toString())
                val plan = RecordingTransport(emptyFeed)
                val provider = OpenAiFeedProvider(http, plan)
                assertTrue(provider.feed(state, module, secret, "en").isEmpty())
                val request = if (mode == "chatgpt") plan.requests.single() else http.requests.single()
                assertEquals(if (mode == "chatgpt") 0 else 1, http.requests.size)
                assertEquals(if (mode == "chatgpt") 1 else 0, plan.requests.size)
                val input = inputOf(request)
                assertEquals(ModuleDefaults.requestContext(module), input["defaultContext"]!!.jsonPrimitive.content)
                assertTrue(input["defaultContext"]!!.jsonPrimitive.content.contains(ModuleCatalog.briefGuidance(kind)))
                assertTrue(input["defaultContext"]!!.jsonPrimitive.content.contains(module.query))
                assertEquals(listOf("approved"), contextIds(input))
                assertFalse(input.toString().contains("Unconfirmed private preference"))
                assertFalse(input.toString().contains("Unrelated private preference"))
                assertFalse(input.toString().contains(secret))
            }
        }
    }

    @Test fun combinedSearchKeepsEachDefaultAndItsContextIdsSeparate() = runBlocking {
        val technical = FeedModule(id = "technical", kind = "technology", preferences = mapOf("focus" to "infrastructure"))
        val viewing = FeedModule(id = "viewing", kind = "entertainment", query = "Serious psychological mysteries", preferences = mapOf("mood" to "tense", "type" to "series"))
        val modules = listOf(technical, viewing)
        listOf("direct", "chatgpt").forEach { mode ->
            val state = scopedState(technical, mode).copy(modules = modules, context = listOf(
                ContextEntry(id = "engineering", text = "A confirmed engineering interest", scopes = setOf("technology")),
                ContextEntry(id = "taste", text = "A confirmed viewing preference", scopes = setOf("entertainment")),
                ContextEntry(id = "unconfirmed", text = "Unconfirmed private preference", scopes = setOf("entertainment"), confirmed = false),
                ContextEntry(id = "unrelated", text = "Unrelated private preference", scopes = setOf("music")),
            ))
            val http = RecordingHttp(emptyFeed.toString())
            val plan = RecordingTransport(emptyFeed)
            OpenAiFeedProvider(http, plan).search(state, modules, "Useful resources", secret, "en")
            val input = inputOf(if (mode == "chatgpt") plan.requests.single() else http.requests.single())
            val sent = input["modules"]!!.jsonArray.map { it.jsonObject }
            modules.forEachIndexed { index, module ->
                assertEquals(ModuleDefaults.requestContext(module), sent[index]["defaultContext"]!!.jsonPrimitive.content)
            }
            assertEquals(listOf("engineering"), sent[0]["contextIds"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("taste"), sent[1]["contextIds"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(setOf("engineering", "taste"), contextIds(input).toSet())
            assertFalse(input.toString().contains("Unconfirmed private preference"))
            assertFalse(input.toString().contains("Unrelated private preference"))
        }
    }

    @Test fun streamingDetailsCarrySelectedTasteWhileGeneralWorkspaceHasNoAmbientContext() = runBlocking {
        val module = FeedModule(id = "viewing", kind = "entertainment", query = "Serious psychological mysteries", preferences = mapOf("mood" to "tense", "type" to "series"))
        val item = FeedItem(id = "story", moduleId = module.id, kind = module.kind, title = "A synthetic title", summary = "A source summary.", sourceUrl = "https://example.org/title")
        val thread = AiConversation(id = "thread", moduleId = module.id, itemId = item.id)
        listOf("direct", "chatgpt").forEach { mode ->
            val state = scopedState(module, mode).copy(items = listOf(item))
            val plan = RecordingTransport(completed("A brief, completed explanation."))
            val direct = RecordingTransport(completed("A brief, completed explanation."))
            val provider = OpenAiFeedProvider(chatGpt = plan, directStream = direct)
            provider.streamConversation(state, item, thread, "Why does this fit my taste?", secret, "en") { }
            val transport = if (mode == "chatgpt") plan else direct
            val input = inputOf(transport.requests.single())
            assertEquals(ModuleDefaults.requestContext(module), input["defaultContext"]!!.jsonPrimitive.content)
            assertEquals(listOf("approved"), contextIds(input))
            assertTrue(input["defaultContext"]!!.jsonPrimitive.content.contains("mood: tense"))
            provider.streamConversation(state, null, AiConversation(), "A general question", secret, "en") { }
            val general = inputOf(transport.requests.last())
            assertTrue(contextIds(general).isEmpty())
            assertNull(general["defaultContext"])
        }
    }

    @Test fun optionalServiceReceivesValidatedModuleSettingsForBothItemAndConversationQuestions() = runBlocking {
        val module = FeedModule(id = "viewing", kind = "entertainment", query = "Serious psychological mysteries", preferences = mapOf("mood" to "tense"))
        val item = FeedItem(id = "story", moduleId = module.id, kind = module.kind, title = "A title", summary = "A summary.", sourceUrl = "https://example.org/title")
        val state = scopedState(module, "service").copy(items = listOf(item))
        val http = RecordingHttp("""{"answer":"A completed explanation.","citations":[]}""")
        val provider = OpenAiFeedProvider(http)
        provider.ask(state, item, "Explain this", secret, "en")
        provider.converse(state, item, AiConversation(moduleId = module.id, itemId = item.id), "Tell me more", secret, "en")
        assertEquals(2, http.requests.size)
        http.requests.forEach { body ->
            assertEquals(FeedJson.encodeToJsonElement(module), body["module"])
            assertEquals(listOf("approved"), contextIds(body))
            assertNull(body["defaultContext"])
        }
    }

    private fun scopedState(module: FeedModule, mode: String) = FeedState(
        modules = listOf(module),
        connection = AiConnection(mode = mode, model = "selected-model", accountId = if (mode == "chatgpt") "selected-account" else "", serviceUrl = if (mode == "service") "https://service.example" else ""),
        context = listOf(
            ContextEntry(id = "approved", text = "A confirmed module preference", scopes = setOf(module.kind)),
            ContextEntry(id = "unconfirmed", text = "Unconfirmed private preference", scopes = setOf(module.kind), confirmed = false),
            ContextEntry(id = "unrelated", text = "Unrelated private preference", scopes = setOf(if (module.kind == "music") "news" else "music")),
        ),
    )

    private fun inputOf(body: JsonObject): JsonObject {
        val input = body.getValue("input")
        val text = if (input is JsonArray) input.single().jsonObject.getValue("content").jsonPrimitive.content else input.jsonPrimitive.content
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun contextIds(input: JsonObject): List<String> = input.getValue("context").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }

    private class RecordingHttp(private val response: String) : FeedHttp {
        val requests = mutableListOf<JsonObject>()
        override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
            requests += Json.parseToJsonElement(body!!).jsonObject
            return response
        }
    }

    private class RecordingTransport(private val response: JsonObject) : ChatGptResponses {
        val requests = mutableListOf<JsonObject>()
        override suspend fun complete(body: JsonObject, accessToken: String): JsonObject { requests += body; return response }
        override suspend fun stream(body: JsonObject, accessToken: String, onText: (String) -> Unit): JsonObject {
            requests += body
            onText("A brief, completed explanation.")
            return response
        }
    }

    private fun completed(text: String) = buildJsonObject {
        put("status", "completed")
        put("output", buildJsonArray { add(buildJsonObject {
            put("type", "message"); put("role", "assistant"); put("status", "completed")
            put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", text) }) })
        }) })
    }
}
