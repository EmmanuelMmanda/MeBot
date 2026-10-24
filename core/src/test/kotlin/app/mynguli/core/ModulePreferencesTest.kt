package app.mynguli.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ModulePreferencesTest {
    @Test fun legacyModuleDecodesWithoutOverridesAndRoundTripsConfiguredChoices() {
        val old = Json.decodeFromString<FeedModule>("""{"id":"learning","kind":"learning","query":"Databases"}""")
        assertTrue(old.preferences.isEmpty())
        assertEquals("Databases", ModuleCatalog.discoveryQuery(old))
        val configured = old.copy(preferences = mapOf("level" to "advanced", "format" to "guides", "cost" to "free", "language" to "sw"))
        val decoded = Json.decodeFromString<FeedModule>(Json.encodeToString(configured))
        FeedLogic.validateModule(decoded)
        assertEquals(configured, decoded)
        val goal = ModuleCatalog.discoveryQuery(configured)
        assertTrue(goal.startsWith("Databases"))
        assertTrue(goal.contains("level: advanced"))
        assertTrue(goal.contains("Kiswahili"))
    }

    @Test fun everyKindHasAnExplicitPreferenceSchemaAndInvalidOrCrossModuleValuesFail() {
        ModuleCatalog.kinds.forEach { kind ->
            val definitions = ModulePreferences.definitions(kind)
            assertTrue(kind, definitions.isNotEmpty())
            assertEquals(definitions.size, definitions.map { it.key }.distinct().size)
            val defaults = definitions.associate { it.key to it.defaultValue }
            FeedLogic.validateModule(FeedModule(id = kind, kind = kind, preferences = defaults))
        }
        reject { FeedLogic.validateModule(FeedModule(kind = "learning", preferences = mapOf("level" to "expert_guess"))) }
        reject { FeedLogic.validateModule(FeedModule(kind = "weather", preferences = mapOf("tone" to "playful"))) }
        reject { FeedLogic.validateModule(FeedModule(kind = "weather", preferences = mapOf("location" to "a".repeat(161)))) }
        reject { FeedLogic.validateModule(FeedModule(kind = "weather", preferences = mapOf("location" to "City\nSecret"))) }
    }

    @Test fun weatherLocationOverrideDoesNotChangeThePersonalProfile() {
        val module = FeedModule(id = "weather", kind = "weather", preferences = mapOf("location" to "  Arusha  "))
        assertEquals("Arusha", ModulePreferences.location(module, "Dar es Salaam"))
        assertEquals("Dar es Salaam", ModulePreferences.location(module.copy(preferences = emptyMap()), "Dar es Salaam"))
        assertEquals("  Arusha  ", module.preferences["location"])
    }

    @Test fun mediaUpgradeIsOptionalAndDoesNotBroadenContextPermissions() {
        val old = FeedState(modules = listOf(FeedModule(id = "news", kind = "news")),
            context = listOf(ContextEntry(id = "work", text = "A technical interest", scopes = setOf("technology"))))
        val upgraded = old.copy(modules = ModuleCatalog.mergeCatalog(old.modules))
        val media = upgraded.modules.single { it.kind == "media" }
        assertFalse(media.enabled)
        assertTrue(FeedLogic.scopedContext(upgraded, media).isEmpty())
        assertEquals("video", ModuleCatalog.preset("media")!!.contentType)
        assertTrue(ModuleCatalog.briefGuidance("media").contains("official"))
        assertTrue(ModuleCatalog.discoveryQuery(media.copy(preferences = mapOf("duration" to "quick"))).contains("3 minutes"))
    }

    @Test fun reorderChangesOrderOnlyAndRejectsDuplicateMissingOrStaleIds() {
        val a = FeedModule(id = "news", kind = "news", query = "My topic", enabled = true)
        val b = FeedModule(id = "weather", kind = "weather", preferences = mapOf("location" to "Arusha"), enabled = false)
        val c = FeedModule(id = "my-watch", kind = "watch", title = "My custom topic", enabled = true)
        val modules = listOf(a, b, c)
        val reordered = ModuleOrder.reorder(modules, listOf(c.id, b.id, a.id))
        assertEquals(listOf(c, b, a), reordered)
        assertTrue(reordered[0] === c)
        assertEquals(listOf(c.id, b.id, a.id), ModuleOrder.reorderSubset(modules.map { it.id }, listOf(c.id, a.id)))
        reject { ModuleOrder.reorder(modules, listOf(a.id, a.id, c.id)) }
        reject { ModuleOrder.reorder(modules, listOf(a.id, c.id)) }
        reject { ModuleOrder.reorder(modules, listOf(a.id, b.id, "deleted")) }
        assertEquals(listOf(a, b, c), modules)
    }

    private fun reject(action: () -> Unit) {
        try { action(); fail("Expected invalid configuration") } catch (_: IllegalArgumentException) { }
    }
}
