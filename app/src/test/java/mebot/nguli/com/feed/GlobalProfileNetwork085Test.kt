package mebot.nguli.com.feed

import android.app.Application
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import mebot.nguli.com.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** HTTP request boundary tests; all provider traffic is captured locally. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GlobalProfileNetwork085Test {
    private val module = FeedModule(id = "custom-learning", kind = "learning", query = "A new custom focus")
    private val profile = ContextEntry(id = "about-you-global", text = "GLOBAL approved biography", category = "profile", scopes = emptySet(), global = true)
    private val notes = listOf(
        ContextEntry(id = "learning-note", text = "SCOPED learning only", scopes = setOf("learning")),
        ContextEntry(id = "private", text = "PRIVATE music only", scopes = setOf("music")),
        ContextEntry(id = "guess", text = "UNCONFIRMED guessed note", scopes = setOf("learning"), confirmed = false),
        profile,
    )
    @Test fun feedIncludesGlobalAndOnlyMatchingNotesForBothTransports() = checkRoute("feed")
    @Test fun searchIncludesGlobalInPayloadAndModuleContextIdsForBothTransports() = checkRoute("search")
    @Test fun disabledCustomModuleChatStillIncludesGlobalAndSelectedNotes() = checkRoute("module-chat")
    @Test fun generalChatIncludesGlobalButDoesNotLeakScopedNotes() = checkRoute("general-chat")
    @Test fun routedGeneralLearningQuestionIncludesOnlyLearningNotesInBothTransports() = checkRoute("routed-general-chat")
    @Test fun legacyAskWithoutItemDoesNotAssumeNewsScope() = checkRoute("legacy-general-chat")
    @Test fun itemAnswerWithoutConversationIncludesGlobalAndMatchingNotes() = checkRoute("item-chat")

    private fun checkRoute(route: String) = runBlocking {
        for (mode in listOf("direct", "service")) {
            val chat = route.endsWith("chat")
            val result = if (chat) "A completed example answer." else "{\"items\":[]}"
            val http = CaptureHttp(if (mode == "service") {
                if (chat) "{\"answer\":\"$result\",\"citations\":[]}" else result
            } else buildJsonObject {
                put("status", "completed")
                put("output", buildJsonArray { add(buildJsonObject {
                    put("type", "message"); put("content", buildJsonArray { add(buildJsonObject {
                        put("type", "output_text"); put("text", result)
                    }) })
                }) })
            }.toString())
            val selected = module.copy(enabled = route != "module-chat")
            val requestedModules = if (route == "search") listOf(selected, FeedModule(id = "second-new-module", kind = "news")) else listOf(selected)
            val requestNotes = if (route == "legacy-general-chat") notes + ContextEntry(id = "news-private", text = "NEWS private note", scopes = setOf("news")) else notes
            val state = FeedState(context = requestNotes, modules = requestedModules,
                connection = AiConnection(mode = mode, serviceUrl = "https://service.example.org"))
            val provider = OpenAiFeedProvider(http)
            when (route) {
                "feed" -> provider.feed(state, selected, "synthetic-test-value", "en")
                "search" -> provider.search(state, requestedModules, "Useful resources", "synthetic-test-value", "en")
                "module-chat" -> provider.converse(state, null, AiConversation(moduleId = selected.id), "Explain this idea", "synthetic-test-value", "en")
                "legacy-general-chat" -> provider.ask(state, null, "Hello", "synthetic-test-value", "en")
                "item-chat" -> provider.ask(state, FeedItem(id = "example", moduleId = selected.id, title = "Example", summary = "Example summary", kind = selected.kind), "Explain this idea", "synthetic-test-value", "en")
                "routed-general-chat" -> provider.converse(state, null, AiConversation(), "Find a learning course", "synthetic-test-value", "en")
                else -> provider.converse(state, null, AiConversation(), "Hello", "synthetic-test-value", "en")
            }
            val body = FeedJson.parseToJsonElement(http.body!!).jsonObject
            val payload = if (mode == "service") body else FeedJson.parseToJsonElement(body.getValue("input").jsonPrimitive.content).jsonObject
            val context = payload.getValue("context").jsonArray
            assertEquals(profile.id, context.first().jsonObject.getValue("id").jsonPrimitive.content)
            assertTrue(context.first().jsonObject.getValue("global").jsonPrimitive.boolean)
            val texts = context.map { it.jsonObject.getValue("text").jsonPrimitive.content }
            assertEquals(route !in setOf("general-chat", "legacy-general-chat"), "SCOPED learning only" in texts)
            assertFalse(payload.toString().contains("NEWS private note"))
            if (route == "legacy-general-chat" && mode == "service") assertEquals("", payload.getValue("moduleKind").jsonPrimitive.content)
            assertFalse(payload.toString().contains("PRIVATE music only"))
            assertFalse(payload.toString().contains("UNCONFIRMED"))
            if (route == "routed-general-chat" && mode == "service") {
                assertEquals(listOf("learning"), payload.getValue("contextKinds").jsonArray.map { it.jsonPrimitive.content })
            }
            if (route == "search" && mode == "direct") {
                assertEquals(listOf(profile.id, "learning-note"), payload.getValue("modules").jsonArray.first().jsonObject
                    .getValue("contextIds").jsonArray.map { it.jsonPrimitive.content })
                assertEquals(listOf(profile.id), payload.getValue("modules").jsonArray[1].jsonObject
                    .getValue("contextIds").jsonArray.map { it.jsonPrimitive.content })
            }
        }
    }

    private class CaptureHttp(private val result: String) : FeedHttp {
        var body: String? = null
        override suspend fun request(url: String, method: String, body: String?, bearer: String?): String {
            this.body = body
            return result
        }
    }
}
