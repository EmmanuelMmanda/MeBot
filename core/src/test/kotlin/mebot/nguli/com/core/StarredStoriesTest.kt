package mebot.nguli.com.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class StarredStoriesTest {
    private fun item(id: String, module: String = "news") = FeedItem(id, module, "A useful report $id", "A sourced summary", sourceUrl = "https://example.com/$id")
    private fun state() = FeedState(modules = listOf(FeedModule(id = "news", kind = "news")), items = listOf(item("one"), item("two")))

    @Test fun starsAreIndependentOfSavesAndRoundTrip() {
        val source = state().copy(savedIds = setOf("two"))
        val starred = StarredStories.toggle(source, "one", 100)
        assertEquals(source.savedIds, starred.savedIds)
        assertEquals(starred, Json.decodeFromString<FeedState>(Json.encodeToString(starred)))
        assertTrue(StarredStories.toggle(starred, "one", 200).starredStories.isEmpty())
    }
    @Test fun pauseCadenceAndModuleScopeAreRespected() {
        val source = StarredStories.toggle(state(), "one", 100)
        assertEquals(1, StarredStories.due(source, "news", 200).size)
        assertTrue(StarredStories.due(source, "other", 200).isEmpty())
        val checked = StarredStories.checked(source, source.starredStories, 200)
        assertTrue(StarredStories.due(checked, "news", 300).isEmpty())
        assertEquals(1, StarredStories.due(checked, "news", 200 + 24 * 3_600_000L).size)
        assertTrue(StarredStories.due(StarredStories.configure(source, "one", true, 6), "news", 1_000_000).isEmpty())
    }
    @Test fun staleCheckCannotResumeOrOverwriteChangedFollow() {
        val source = StarredStories.toggle(state(), "one", 100)
        val changed = StarredStories.configure(source, "one", true, 72)
        assertEquals(changed, StarredStories.checked(changed, source.starredStories, 200))
    }
    @Test fun starSurvivesCacheEvictionAndUnsavedDoesNotDeleteIt() {
        val source = StarredStories.toggle(state(), "one", 100)
        val merged = FeedLogic.merge(source, (1..300).map { item("new$it").copy(publishedAtMillis = it.toLong()) }, 400)
        assertTrue(merged.items.any { it.id == "one" })
        FeedLogic.validate(merged)
    }
    @Test fun collectionRequiresSavedItemAndValidName() {
        val saved = state().copy(savedIds = setOf("one"))
        assertEquals("Work", StarredStories.collection(saved, "one", " Work ").savedCollections["one"])
        assertTrue(StarredStories.collection(StarredStories.collection(saved, "one", "Work"), "one", "").savedCollections.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { StarredStories.collection(saved, "two", "Work") }
        assertThrows(IllegalArgumentException::class.java) { StarredStories.collection(saved, "one", "x".repeat(41)) }
    }
    @Test fun invalidStarReferencesAndCadenceAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { FeedLogic.validate(state().copy(starredStories = listOf(StarredStory("missing")))) }
        assertThrows(IllegalArgumentException::class.java) { StarredStories.configure(state(), "one", false, 1) }
    }
    @Test fun followUpQueryIsBoundedAndDoesNotCrossModules() {
        val source = StarredStories.toggle(state(), "one", 100)
        val module = source.modules.first().copy(query = "q".repeat(1000))
        val query = StarredStories.query(source, module, 200)
        assertTrue(query.query.length <= 1000)
        assertTrue(query.query.contains("A useful report one"))
        assertFalse(query.query.contains("A useful report two"))
        assertEquals(module.copy(id = "other"), StarredStories.query(source, module.copy(id = "other"), 200))
    }
    @Test fun followsPreserveTheDefaultDiscoveryGoal() {
        val source = StarredStories.toggle(state(), "one", 100)
        val module = source.modules.first().copy(query = "")
        val goal = ModuleCatalog.preset(module.kind)?.defaultQuery.orEmpty()
        assertTrue(goal.isNotBlank())
        assertTrue(StarredStories.query(source, module, 200).query.startsWith(goal.take(100)))
    }
    @Test fun showNewItemsReallyPutsNewIdsFirst() {
        val ranked = listOf(item("old"), item("new"), item("old2"))
        val accepted = FeedPaging.acceptNew(ranked, listOf("old", "old2"))
        assertEquals(listOf("new", "old", "old2"), accepted)
        assertEquals("new", FeedPaging.ordered(ranked, accepted).first().id)
    }
    @Test fun legacyStateKeepsBookmarksAndStartsWithoutFollows() {
        val decoded = Json { ignoreUnknownKeys = true }.decodeFromString<FeedState>("""{"savedIds":["one"]}""")
        assertEquals(setOf("one"), decoded.savedIds)
        assertTrue(decoded.starredStories.isEmpty())
        assertTrue(decoded.savedCollections.isEmpty())
    }
}
