package mebot.nguli.com.feed

import mebot.nguli.com.core.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Verify the request boundary, including selected credentials and the quoted history. */
class AiConversationRequestTest {
    private val module = FeedModule(id = "technology", kind = "technology", preferences = mapOf("language" to "sw"))
    private val item = FeedItem("item", module.id, "A release", "A sourced excerpt", sourceUrl = "https://publisher.example/release", kind = module.kind)
    private val question = "Which limitation matters here?"
    private val thread = AiConversation(moduleId = module.id, itemId = item.id, turns = listOf(
        AiTurn(role = "user", text = "What changed?"), AiTurn(role = "assistant", text = "A new verified capability."),
        AiTurn(role = "user", text = question),
    ), attachments = listOf(AiAttachment(name = "notes.txt", text = "A user-selected reference note.")))
    private val state = FeedState(modules = listOf(module), items = listOf(item), context = listOf(
        ContextEntry(id = "approved", text = "Approved technical priority", scopes = setOf("technology")),
        ContextEntry(id = "unconfirmed", text = "Unconfirmed private preference", scopes = setOf("technology"), confirmed = false),
        ContextEntry(id = "unrelated", text = "Unrelated private preference", scopes = setOf("music")),
    ))

    @Test fun bothCredentialPathsUseTheirSelectedStreamingTransportWithScopedHistoryAndModuleLanguage() = runBlocking {
        listOf("chatgpt", "direct").forEach { mode ->
            val plan = RecordingTransport()
            val api = RecordingTransport()
            val forbiddenHttp = object : FeedHttp {
                override suspend fun request(url: String, method: String, body: String?, bearer: String?): String = error("Must use the selected streaming transport")
            }
            val selected = state.copy(connection = AiConnection(mode = mode, model = "owner-selected-model", accountId = if (mode == "chatgpt") "owner-account" else ""))
            val partials = mutableListOf<String>()
            val answer = OpenAiFeedProvider(forbiddenHttp, plan, api).streamConversation(selected, item, thread, question, "selected-$mode-token", "en") { partials += it }
            val active = if (mode == "chatgpt") plan else api
            val inactive = if (mode == "chatgpt") api else plan
            assertEquals(1, active.requests.size)
            assertTrue(inactive.requests.isEmpty())
            assertEquals("selected-$mode-token", active.tokens.single())
            val request = active.requests.single()
            assertEquals("owner-selected-model", request["model"]!!.jsonPrimitive.content)
            assertTrue(request["stream"]!!.jsonPrimitive.boolean)
            assertFalse(request["store"]!!.jsonPrimitive.boolean)
            assertTrue(request["instructions"]!!.jsonPrimitive.content.contains("in sw,"))
            val input = Json.parseToJsonElement(request["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content).jsonObject
            assertEquals(question, input["question"]!!.jsonPrimitive.content)
            assertEquals(listOf("What changed?", "A new verified capability."), input["history"]!!.jsonArray.map { it.jsonObject["text"]!!.jsonPrimitive.content })
            assertEquals(listOf("approved"), input["context"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
            assertEquals("notes.txt", input["attachments"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)
            assertFalse(input.toString().contains("Unconfirmed private preference"))
            assertFalse(input.toString().contains("Unrelated private preference"))
            assertEquals(listOf("Provisional text", "Completed text"), partials)
            assertEquals("Completed text", answer.answer)
        }
    }

    @Test fun serviceConversationAndItemQuestionBothUseTheModulesResponseLanguage() = runBlocking {
        val requests = mutableListOf<JsonObject>()
        val http = object : FeedHttp {
            override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
                assertEquals("https://service.example/v1/ask", url)
                assertEquals("selected-service-token", bearer)
                requests += Json.parseToJsonElement(body!!).jsonObject
                return """{"answer":"A completed answer.","citations":[]}"""
            }
        }
        val provider = OpenAiFeedProvider(http)
        val service = state.copy(connection = AiConnection(mode = "service", model = "server-model", serviceUrl = "https://service.example"))
        provider.converse(service, item, thread, question, "selected-service-token", "en")
        provider.ask(service, item, "Explain this item", "selected-service-token", "en")
        assertEquals(2, requests.size)
        assertTrue(requests.all { it["language"]!!.jsonPrimitive.content == "sw" && it["model"]!!.jsonPrimitive.content == "server-model" })
        assertEquals(2, requests.first()["history"]!!.jsonArray.size)
        assertEquals(listOf("approved"), requests.first()["context"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
    }

    @Test fun anUnscopedConversationDoesNotReceiveAnyAmbientProfileNotes() = runBlocking {
        val transport = RecordingTransport()
        val provider = OpenAiFeedProvider(chatGpt = transport)
        val connected = state.copy(connection = AiConnection(mode = "chatgpt", model = "owner-model", accountId = "owner-account"))
        provider.streamConversation(connected, null, AiConversation(), "A general question", "synthetic-token", "en") { }
        val request = transport.requests.single()
        val input = Json.parseToJsonElement(request["input"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content).jsonObject
        assertTrue(input["context"]!!.jsonArray.isEmpty())
        assertTrue(request["instructions"]!!.jsonPrimitive.content.contains("in en,"))
    }

    private class RecordingTransport : ChatGptResponses {
        val requests = mutableListOf<JsonObject>()
        val tokens = mutableListOf<String>()
        override suspend fun complete(body: JsonObject, accessToken: String): JsonObject = error("Expected actual streaming entry point")
        override suspend fun stream(body: JsonObject, accessToken: String, onText: (String) -> Unit): JsonObject {
            requests += body; tokens += accessToken
            onText("Provisional text")
            onText("Completed text")
            return buildJsonObject {
                put("status", "completed")
                put("output", buildJsonArray { add(buildJsonObject {
                    put("type", "message"); put("role", "assistant"); put("status", "completed")
                    put("content", buildJsonArray { add(buildJsonObject { put("type", "output_text"); put("text", "Completed text") }) })
                }) })
            }
        }
    }
}
