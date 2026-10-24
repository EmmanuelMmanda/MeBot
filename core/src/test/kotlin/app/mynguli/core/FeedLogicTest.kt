package app.mynguli.core

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class FeedLogicTest {
    @Test fun quietHoursUseTheConfiguredTimezoneAcrossMidnight() {
        val state = FeedState(timezone = "Africa/Dar_es_Salaam", quietStartHour = 22, quietEndHour = 7)
        fun quiet(utc: String) = FeedLogic.quietAt(Instant.parse(utc).toEpochMilli(), state)
        assertFalse(quiet("2026-10-22T18:59:00Z"))
        assertTrue(quiet("2026-10-22T19:00:00Z"))
        assertTrue(quiet("2026-10-23T03:59:00Z"))
        assertFalse(quiet("2026-10-23T04:00:00Z"))
        assertFalse(FeedLogic.quietAt(Instant.parse("2026-10-23T01:00:00Z").toEpochMilli(), state.copy(quietStartHour = 7, quietEndHour = 7)))
    }

    @Test fun modulesReceiveOnlyConfirmedContextWithinTheirScope() {
        val approved = ContextEntry(id = "approved", text = "Technical interests", scopes = setOf("news"))
        val state = FeedState(context = listOf(
            approved,
            ContextEntry(id = "private", text = "Other private context", scopes = setOf("horoscope")),
            ContextEntry(id = "inferred", text = "A guess", confirmed = false, scopes = setOf("news")),
        ))
        assertEquals(listOf(approved), FeedLogic.scopedContext(state, FeedModule(id = "news", kind = "news")))
    }

    @Test fun retentionPreservesSavedItemsAndGroundedExplanations() {
        val saved = item("saved", 1).copy(aiGenerated = true, why = "A sourced personal explanation")
        val state = FeedState(items = listOf(saved), savedIds = setOf(saved.id))
        val incoming = (1..300).map { item("fresh-$it", it.toLong()) } + saved.copy(aiGenerated = false, why = "")
        val merged = FeedLogic.merge(state, incoming, 500)
        assertEquals(251, merged.items.size)
        assertEquals("A sourced personal explanation", merged.items.single { it.id == "saved" }.why)
        assertEquals(500L, merged.items.single { it.id == "saved" }.fetchedAtMillis)
        assertEquals(1, merged.items.count { it.id == "saved" })
        assertEquals(setOf("saved"), merged.savedIds)
    }

    @Test fun disabledAndDismissedItemsLeaveTheFeedWithoutDeletingSavedContent() {
        val state = FeedState(
            modules = listOf(FeedModule(id = "news", kind = "news"), FeedModule(id = "opportunities", kind = "opportunities", enabled = false)),
            items = listOf(item("visible", 3), item("dismissed", 2), item("paused-module", 1).copy(moduleId = "opportunities", kind = "opportunities")),
            hiddenIds = setOf("dismissed"), savedIds = setOf("paused-module"),
        )
        assertEquals(listOf("visible"), FeedLogic.visible(state).map { it.id })
        assertTrue(state.items.any { it.id == "paused-module" })
    }

    @Test fun canonicalUrlsDropTrackingWithoutChangingEncodedQueryValues() {
        val url = "https://EXAMPLE.com/story?utm_source=rss&q=a%20b#part"
        val normalized = FeedLogic.canonicalUrl(url)
        assertEquals("https://example.com/story?q=a%20b", normalized)
        assertEquals(normalized, FeedLogic.canonicalUrl(normalized))
        assertEquals(FeedLogic.itemId(url), FeedLogic.itemId("https://example.com/story?q=a%20b"))
    }

    @Test fun unsafeSourceAndCredentialBearingUrlsAreRejected() {
        listOf("file:///tmp/feed", "javascript:alert(1)", "https://user:secret@example.com/rss", "https://127.0.0.1/rss", "https://server.internal/rss").forEach {
            assertFalse(it, FeedLogic.safeUrl(it, true))
        }
        assertTrue(FeedLogic.safeUrl("https://example.com/rss", true))
        assertFalse(FeedLogic.safeUrl("http://example.com/rss", true))
    }

    private fun item(id: String, published: Long) = FeedItem(
        id = id, moduleId = "news", title = "Update $id", summary = "Synthetic test article.",
        sourceUrl = "https://example.com/$id", publishedAtMillis = published, fetchedAtMillis = published,
    )
}
