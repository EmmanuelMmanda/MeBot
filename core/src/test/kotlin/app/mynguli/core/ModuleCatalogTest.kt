package app.mynguli.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ModuleCatalogTest {
    @Test fun upgradeAddsDisabledCapabilitiesWithoutChangingPersonalConfiguration() {
        val news = FeedModule(id = "news", kind = "news", title = "My sources", query = "A chosen topic", enabled = false,
            sources = listOf(FeedSource("Example", "https://example.com/feed")), alerts = false)
        val opportunities = FeedModule(id = "opportunities", kind = "opportunities", enabled = true)
        val watch = FeedModule(id = "custom-watch", kind = "watch", title = "A custom topic", query = "Something specific")
        val context = ContextEntry(id = "note", text = "A confirmed interest", scopes = setOf("news", "watch"))
        val old = FeedState(modules = listOf(news, opportunities, watch), context = listOf(context))

        val upgraded = old.copy(modules = ModuleCatalog.mergeCatalog(old.modules))
        FeedLogic.validate(upgraded)
        assertEquals(listOf(news, opportunities, watch), upgraded.modules.take(3))
        assertEquals(old.context, upgraded.context)
        assertTrue(upgraded.modules.drop(3).none { it.enabled })
        assertEquals(ModuleCatalog.presets.map { it.kind }.toSet(), upgraded.modules.filter { it.kind != "watch" }.map { it.kind }.toSet())
        assertEquals(upgraded.modules, ModuleCatalog.mergeCatalog(upgraded.modules))
    }

    @Test fun aFullInstallationDoesNotLoseCustomModulesOnUpgrade() {
        val existing = (1..ModuleCatalog.maxModules).map { index ->
            FeedModule(id = "custom-$index", title = "Topic $index", query = "Test interest $index", enabled = index % 2 == 0)
        }
        assertEquals(existing, ModuleCatalog.mergeCatalog(existing))
        FeedLogic.validate(FeedState(modules = existing))
        expectInvalid { FeedLogic.validate(FeedState(modules = existing + FeedModule(id = "too-many"))) }
    }

    @Test fun catalogueHasUsablePoliciesWithoutBroadeningContextPermissions() {
        assertEquals(16, ModuleCatalog.presets.size)
        assertEquals(17, ModuleCatalog.kinds.size)
        assertEquals(listOf("news"), ModuleCatalog.defaults.filter { it.enabled }.map { it.id })
        assertEquals(ModuleCatalog.presets.size, ModuleCatalog.presets.map { it.kind }.distinct().size)
        ModuleCatalog.presets.forEach { preset ->
            assertTrue(preset.group in ModuleCatalog.groups)
            assertTrue(preset.briefGuidance.isNotBlank())
            assertTrue(preset.contentType in ModuleCatalog.contentTypes)
        }
        val state = FeedState(context = listOf(ContextEntry(id = "news-only", text = "My technical interests", scopes = setOf("news"))))
        assertTrue(FeedLogic.scopedContext(state, FeedModule(id = "technology", kind = "technology")).isEmpty())
        assertEquals(1, FeedLogic.scopedContext(state, FeedModule(id = "news", kind = "news")).size)
    }

    @Test fun emptyTopicsResolveAtRequestTimeWithoutChangingSavedChoices() {
        ModuleCatalog.kinds.forEach { kind ->
            val saved = FeedModule(id = kind, kind = kind, query = "", enabled = false)
            assertTrue(ModuleCatalog.discoveryQuery(saved).isNotBlank())
            assertTrue(ModuleCatalog.briefGuidance(kind).isNotBlank())
            assertEquals("", saved.query)
            assertFalse(saved.enabled)
        }
        val custom = FeedModule(id = "my-topic", kind = "technology", query = "  PostgreSQL release notes  ")
        assertEquals("PostgreSQL release notes", ModuleCatalog.discoveryQuery(custom))
        assertEquals("  PostgreSQL release notes  ", custom.query)
    }

    @Test fun oldCachedItemsDecodeWithoutMediaAndNewItemsRoundTrip() {
        val legacy = """{"id":"old","moduleId":"news","title":"An old story","summary":"Cached content","sourceUrl":"https://example.com/story"}"""
        val decoded = Json.decodeFromString<FeedItem>(legacy)
        FeedLogic.validateItem(decoded)
        assertNull(decoded.media)
        assertNull(decoded.weather)
        assertEquals("article", decoded.contentType)
        val enriched = decoded.copy(media = FeedMedia("https://images.example.com/preview.webp", decoded.sourceUrl, "Example publisher"), contentType = "video")
        assertEquals(enriched, Json.decodeFromString<FeedItem>(Json.encodeToString(enriched)))
        FeedLogic.validateItem(enriched)
    }

    @Test fun cachedMediaRejectsCredentialBearingOrNonPublicUrls() {
        val item = story("media", "news", "news", 50)
        listOf("http://example.com/image.jpg", "https://127.0.0.1/image.jpg", "https://user:secret@example.com/image.jpg", "file:///tmp/photo").forEach { url ->
            expectInvalid { FeedLogic.validateItem(item.copy(media = FeedMedia(url, item.sourceUrl))) }
            expectInvalid { FeedLogic.validateItem(item.copy(media = FeedMedia("https://example.com/image.jpg", url))) }
        }
        expectInvalid { FeedLogic.validateItem(item.copy(contentType = "arbitrary-executable")) }
        expectInvalid { FeedLogic.validateItem(item.copy(media = FeedMedia("https://example.com/image.jpg", item.sourceUrl, "Bad\ncredit"))) }
    }

    @Test fun typedWeatherRejectsMissingLocationAndImpossibleMeasurements() {
        val weather = FeedWeather(25.0, 27.0, 60, 3, "Example city")
        val item = story("weather", "weather", "weather", 70).copy(contentType = "weather", weather = weather)
        FeedLogic.validateItem(item)
        expectInvalid { FeedLogic.validateItem(item.copy(kind = "news")) }
        expectInvalid { FeedLogic.validateItem(item.copy(contentType = "article")) }
        expectInvalid { FeedLogic.validateItem(item.copy(weather = weather.copy(location = ""))) }
        expectInvalid { FeedLogic.validateItem(item.copy(weather = weather.copy(temperatureC = Double.NaN))) }
        expectInvalid { FeedLogic.validateItem(item.copy(weather = weather.copy(precipitationChance = 101))) }
    }

    @Test fun dailyEditionIncludesDistinctModulesBeforeRepeatingOne() {
        val modules = listOf("news", "technology", "security", "learning", "weather").map { FeedModule(id = it, kind = it) }
        val news = (1..6).map { story("news-$it", "news", "news", 99 - it) }
        val others = modules.drop(1).mapIndexed { i, module -> story("result-${module.id}", module.id, module.kind, 70 - i) }
        val state = FeedState(modules = modules, items = news + others)
        val edition = FeedLogic.edition(state, now = 1_000_000L)
        assertEquals(5, edition.take(5).map { it.moduleId }.distinct().size)
        assertEquals(state.items.map { it.id }.toSet(), edition.map { it.id }.toSet())
        assertEquals(state.items.size, edition.size)
    }

    @Test fun editionFavoursCurrentAndDifferentPublishersAndHonoursHiddenState() {
        val now = 20 * 86_400_000L
        val old = story("old", "news", "news", 99).copy(publishedAtMillis = 1)
        val current = story("current", "news", "news", 80).copy(publishedAtMillis = now, sourceUrl = "https://one.example.com/current")
        val sameSource = story("same", "news", "news", 79).copy(publishedAtMillis = now, sourceUrl = "https://one.example.com/another")
        val different = story("different", "news", "news", 78).copy(publishedAtMillis = now, sourceUrl = "https://two.example.com/story")
        val hidden = story("hidden", "news", "news", 100).copy(publishedAtMillis = now)
        val paused = story("paused", "learning", "learning", 100).copy(publishedAtMillis = now)
        val state = FeedState(modules = listOf(FeedModule(id = "news", kind = "news"), FeedModule(id = "learning", kind = "learning", enabled = false)),
            items = listOf(old, current, sameSource, different, hidden, paused), hiddenIds = setOf("hidden"))
        assertEquals(listOf("current", "different", "same", "old"), FeedLogic.edition(state, now).map { it.id })
    }

    @Test fun refreshKeepsPublisherMediaWhenAnOtherwiseUsefulItemArrivesWithoutIt() {
        val original = story("story", "news", "news", 80).copy(aiGenerated = true,
            media = FeedMedia("https://images.example.com/photo.jpg", "https://example.com/story", "Example"))
        val refreshed = original.copy(summary = "New summary", media = null, fetchedAtMillis = 2_000)
        val merged = FeedLogic.merge(FeedState(items = listOf(original)), listOf(refreshed), 2_000).items.single()
        assertEquals("New summary", merged.summary)
        assertEquals(original.media, merged.media)
        val upgradedFromPublicSource = FeedLogic.merge(FeedState(items = listOf(original.copy(media = null))), listOf(original.copy(aiGenerated = false)), 3_000).items.single()
        assertTrue(upgradedFromPublicSource.aiGenerated)
        assertEquals(original.media, upgradedFromPublicSource.media)
    }

    private fun story(id: String, module: String, kind: String, relevance: Int) = FeedItem(
        id = id, moduleId = module, title = "Synthetic $id", summary = "A synthetic test item.",
        sourceUrl = "https://example.com/$id", publishedAtMillis = 1_000_000L, fetchedAtMillis = 1_000_000L,
        kind = kind, relevance = relevance,
    )

    private fun expectInvalid(block: () -> Unit) {
        try { block(); fail("Expected invalid data to be rejected") } catch (_: IllegalArgumentException) { }
    }
}
