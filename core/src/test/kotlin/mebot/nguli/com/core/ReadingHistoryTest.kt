package mebot.nguli.com.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReadingHistoryTest {
    private val now = Instant.parse("2026-10-23T09:00:00Z").toEpochMilli()
    private fun story(id: String, relevance: Int = 60) = FeedItem(id = id, moduleId = "news", kind = "news",
        title = "Synthetic $id", summary = "Test source metadata", sourceUrl = "https://example.com/$id",
        relevance = relevance, fetchedAtMillis = now, publishedAtMillis = now)
    private fun state(vararg items: FeedItem) = FeedState(modules = listOf(FeedModule(id = "news", kind = "news")),
        items = items.toList(), timezone = "UTC")

    @Test fun readingPromotesUnseenStoriesWithoutHidingTheReadStory() {
        val read = story("read", 70); val unseen = story("unseen", 60)
        val original = state(read, unseen)
        assertEquals("read", FeedLogic.edition(original, now).first().id)
        val viewed = FeedLogic.markViewed(original, read.id, now)
        assertEquals(listOf("unseen", "read"), FeedLogic.edition(viewed, now).map { it.id })
        assertEquals(setOf("read", "unseen"), FeedLogic.visible(viewed, now).map { it.id }.toSet())
    }

    @Test fun disablingReadingHistoryStopsCollectionAndRestoresRelevanceOrdering() {
        val original = state(story("read", 70), story("unseen", 60)).copy(readingHistoryEnabled = false, viewedAtMillis = mapOf("read" to now))
        assertEquals(original, FeedLogic.markViewed(original, "unseen", now))
        assertEquals("read", FeedLogic.edition(original, now).first().id)
    }

    @Test fun oldReadsReturnToContentRankingAndDeadlineStillMatters() {
        val read = story("deadline", 70).copy(deadline = "2026-10-24")
        val s = state(read, story("unseen", 60)).copy(viewedAtMillis = mapOf(read.id to now))
        assertEquals("deadline", FeedLogic.edition(s, now).first().id)
        val old = state(story("old", 80), story("new", 60)).copy(viewedAtMillis = mapOf("old" to now - 4 * 86_400_000L))
        assertEquals("old", FeedLogic.edition(old, now).first().id)
    }

    @Test fun unknownItemsDoNotCreateHistoryAndTheStoreStaysBounded() {
        val s = state(story("current")).copy(viewedAtMillis = (0..999).associate { "previous-$it" to now - it - 1L })
        assertEquals(s, FeedLogic.markViewed(s, "missing", now))
        val viewed = FeedLogic.markViewed(s, "current", now)
        assertEquals(1000, viewed.viewedAtMillis.size)
        assertEquals(now, viewed.viewedAtMillis["current"])
        assertFalse(viewed.viewedAtMillis.containsKey("previous-999"))
        FeedLogic.validate(viewed)
    }

    @Test fun quickReopeningDoesNotRewriteHistoryButALaterReadUpdatesIt() {
        val s = FeedLogic.markViewed(state(story("current")), "current", now)
        assertSame(s, FeedLogic.markViewed(s, "current", now + 120_000))
        assertEquals(now + 300_000, FeedLogic.markViewed(s, "current", now + 300_000).viewedAtMillis["current"])
        assertEquals(now - 1, FeedLogic.markViewed(s, "current", now - 1).viewedAtMillis["current"])
    }
}
