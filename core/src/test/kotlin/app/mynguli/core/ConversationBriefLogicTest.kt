package app.mynguli.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ConversationBriefLogicTest {
    private val module = FeedModule(id = "technology", kind = "technology")
    private val item = FeedItem("source-item", module.id, "A useful release", "The source describes a new capability.",
        sourceUrl = "https://publisher.example/release", kind = module.kind, fetchedAtMillis = 100)
    private val note = ContextEntry(id = "approved", text = "An approved technical priority", scopes = setOf(module.kind), updatedAtMillis = 1)
    private val state = FeedState(modules = listOf(module), items = listOf(item), context = listOf(note), timezone = "UTC")

    @Test fun cachedExplanationSurvivesReadingRankAndPublisherPreviewChanges() {
        val fingerprint = BriefCache.fingerprint(state, item, "en")
        val refreshed = item.copy(fetchedAtMillis = 200, relevance = 91, why = "Updated ranking explanation",
            media = FeedMedia("https://publisher.example/image.jpg", item.sourceUrl, "Publisher"))
        val revisedState = state.copy(items = listOf(refreshed), viewedAtMillis = mapOf(item.id to 150), savedIds = setOf(item.id), requestsToday = 3)
        assertEquals(fingerprint, BriefCache.fingerprint(revisedState, refreshed, "en"))
    }

    @Test fun sourceContextModelLanguageAndModuleChangesInvalidateTheExplanation() {
        val baseline = BriefCache.fingerprint(state, item, "en")
        val scenarios = listOf(
            state to item.copy(summary = "The publisher corrected the capability."),
            state to item.copy(sourceUrl = "https://publisher.example/corrected-release"),
            state to item.copy(keyPoints = listOf("A corrected limit.")),
            state.copy(context = listOf(note.copy(text = "A different approved priority"))) to item,
            state.copy(modules = listOf(module.copy(query = "Only open-source releases"))) to item,
            state.copy(connection = state.connection.copy(model = "another-model")) to item,
            state.copy(connection = AiConnection(mode = "chatgpt", model = "selected-model", accountId = "another-account")) to item,
            state.copy(region = "A different region") to item,
            state.copy(timezone = "Europe/London") to item,
        )
        scenarios.forEach { (changedState, changedItem) -> assertNotEquals(baseline, BriefCache.fingerprint(changedState, changedItem, "en")) }
        assertNotEquals(baseline, BriefCache.fingerprint(state, item, "sw"))
    }

    @Test fun unsharedNotesDoNotInfluenceAnItemsExplanationOrInvalidateItsCache() {
        val baseline = BriefCache.fingerprint(state, item, "en")
        val extra = listOf(
            ContextEntry(id = "unconfirmed", text = "A private unconfirmed note", scopes = setOf(module.kind), confirmed = false),
            ContextEntry(id = "unrelated", text = "A private music note", scopes = setOf("music")),
        )
        val changed = state.copy(context = state.context + extra)
        assertEquals(baseline, BriefCache.fingerprint(changed, item, "en"))
        assertEquals(listOf(note), ConversationLogic.context(changed, AiConversation(itemId = item.id, moduleId = module.id)))
        assertTrue(ConversationLogic.context(changed, AiConversation()).isEmpty())
    }

    @Test fun expiredFutureAndMismatchedBriefsCannotBeReused() {
        val now = 1_000_000_000L
        val fingerprint = BriefCache.fingerprint(state, item, "en")
        val brief = AiItemBrief(item.id, "A completed explanation", generatedAtMillis = now, model = state.connection.model, fingerprint = fingerprint)
        assertTrue(BriefCache.reusable(brief, fingerprint, now))
        assertTrue(BriefCache.reusable(brief, fingerprint, now + 6 * 3_600_000L - 1))
        assertFalse(BriefCache.reusable(brief, fingerprint, now + 6 * 3_600_000L))
        assertFalse(BriefCache.reusable(brief, fingerprint, now - 1))
        assertFalse(BriefCache.reusable(brief, "f".repeat(64), now))
    }

    @Test fun requestHistoryKeepsTheNewestTurnsInOrderWithinItsBudgetWithoutRewritingStorage() {
        val turns = (0 until 40).map { index -> AiTurn(id = "turn-$index", role = if (index % 2 == 0) "user" else "assistant",
            text = if (index % 2 == 0) "u".repeat(1000) else "a".repeat(5000)) }
        val thread = AiConversation(turns = turns)
        val history = ConversationLogic.requestHistory(thread)
        assertTrue(history.size <= 16)
        assertTrue(history.sumOf { it.text.length } <= 36_000)
        assertEquals(turns.last(), history.last())
        assertEquals(history.map { it.id }.sortedBy { it.substringAfter('-').toInt() }, history.map { it.id })
        assertTrue(history.all { it.id in turns.takeLast(16).map { turn -> turn.id } })
        assertEquals(turns, thread.turns)
        assertEquals(40, thread.turns.size)
    }

    @Test fun legacyFeedFilesDecodeWithoutInventingConversationOrBriefData() {
        val restored = Json.decodeFromString<FeedState>("""{"schemaVersion":1,"name":"Local reader"}""")
        assertTrue(restored.conversations.isEmpty())
        assertTrue(restored.briefs.isEmpty())
        assertEquals("Local reader", restored.name)
    }
}
