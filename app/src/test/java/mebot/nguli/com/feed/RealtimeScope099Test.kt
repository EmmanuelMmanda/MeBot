package mebot.nguli.com.feed

import mebot.nguli.com.core.*
import org.junit.Assert.*
import org.junit.Test

class RealtimeScope099Test {
    private val module = FeedModule(id = "tech", kind = "technology", title = "My workflow", query = "Improve review habits")
    private val global = ContextEntry(id = PersonalProfile085.PROFILE_ID, text = "Prefer concise explanations", category = "profile", scopes = emptySet(), global = true, updatedAtMillis = 1)
    private val scoped = ContextEntry(id = "tech-note", text = "Review code carefully", scopes = setOf("technology"), updatedAtMillis = 1)
    private val unrelated = ContextEntry(id = "other-note", text = "UNRELATED_PRIVATE_NOTE", scopes = setOf("opportunities"), updatedAtMillis = 1)
    private val item = FeedItem(id = "item", moduleId = module.id, kind = module.kind, title = "A public source", summary = "Selected source summary", sourceName = "Example", sourceUrl = "https://example.org/story")
    private val thread = AiConversation(id = "chat", itemId = item.id, moduleId = module.id,
        turns = listOf(AiTurn(id = "turn", role = "user", text = "Compare the approaches")),
        attachments = listOf(AiAttachment("private", "private.txt", "UNSENT_PRIVATE_ATTACHMENT")))
    private fun state() = FeedState(modules = listOf(module), context = listOf(global, scoped, unrelated), items = listOf(item), conversations = listOf(thread,
        AiConversation(id = "other-chat", turns = listOf(AiTurn(id = "other", role = "user", text = "UNRELATED_CHAT")))))

    @Test fun generalPreparationUsesOnlyConfirmedGlobalProfileAndDoesNotCreateThread() {
        val source = state()
        val projected = RealtimeScope099.project(FeedUiState(state = source))!!
        assertEquals(listOf(global.id), projected.context.confirmedContext.map { it.id })
        assertNull(projected.context.selectedItem)
        assertTrue(projected.context.recentHistory.isEmpty())
        assertEquals(2, source.conversations.size)
    }

    @Test fun selectedThreadProjectsOnlyItsScopeAndNeverAttachmentsOrOtherChats() {
        val ui = FeedUiState(state = state(), activeConversationId = thread.id, appLanguage = "sw")
        val projected = RealtimeScope099.project(ui)!!
        assertEquals(listOf(global.id, scoped.id), projected.context.confirmedContext.map { it.id })
        assertEquals(module.query, projected.context.moduleQuery)
        assertEquals("sw", projected.context.language)
        assertEquals(listOf("Compare the approaches"), projected.context.recentHistory.map { it.text })
        assertFalse(projected.context.toString().contains("PRIVATE"))
        assertFalse(projected.context.toString().contains("UNRELATED"))
        assertFalse(projected.toString().contains(global.text))
        assertFalse(RealtimeScope099.stillCurrent(projected, ui, locked = true))
        assertFalse(RealtimeScope099.stillCurrent(projected, ui.copy(appLanguage = "en"), locked = false))
        assertFalse(RealtimeScope099.stillCurrent(projected, ui.copy(activeConversationId = "other-chat"), locked = false))
    }

    @Test fun deletedOrUnreadyScopedThreadFailsClosedRatherThanUsingGeneralContext() {
        val ui = FeedUiState(state = state(), activeConversationId = thread.id)
        assertNull(RealtimeScope099.project(ui.copy(state = state().copy(conversations = emptyList()))))
        assertNull(RealtimeScope099.project(ui.copy(conversationReady = false)))
        assertNull(RealtimeScope099.project(ui.copy(conversationOpening = true)))
        assertNull(RealtimeScope099.project(ui.copy(state = state().copy(modules = listOf(module.copy(enabled = false))))))
    }

    @Test fun originalReadKeepsSubstantiveBodyWithoutInventingPublicationOrSource() {
        val read = item.copy(contentType = "original_read", acquisition = FeedAcquisition.ORIGINAL_READ,
            authorship = FeedAuthorship.AI, aiGenerated = true, sourceName = "", sourceUrl = "",
            publishedAtMillis = 0, fetchedAtMillis = 1,
            sections = listOf(FeedContentSection("A practical reflection", "A careful reading practice. ".repeat(25)),
                FeedContentSection("Try it thoughtfully", "Consider your own circumstances. ".repeat(25))),
            furtherReading = listOf(FeedCitation("Optional resource", "https://example.org/resource")))
        FeedLogic.validateItem(read)
        val projected = RealtimeScope099.project(FeedUiState(state = state().copy(items = listOf(read)), activeConversationId = thread.id))!!
        val evidence = projected.context.selectedItem!!
        assertEquals(read.sections.map { it.body }, evidence.sections.map { it.body })
        assertEquals(0L, evidence.publishedAtMillis)
        assertEquals("", evidence.sourceUrl)
        assertEquals(FeedAcquisition.ORIGINAL_READ, evidence.acquisition)
        assertTrue(evidence.citationReferences.isEmpty())
        assertEquals(1, evidence.furtherReading.size)
    }
}
