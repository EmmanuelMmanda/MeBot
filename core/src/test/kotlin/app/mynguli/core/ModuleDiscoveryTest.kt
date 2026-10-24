package app.mynguli.core

import org.junit.Assert.*
import org.junit.Test

class ModuleDiscoveryTest {
    private val modules = listOf(
        FeedModule(id = "news", kind = "news"),
        FeedModule(id = "weather", kind = "weather", enabled = false),
        FeedModule(id = "systems", kind = "watch", title = "Server changes", query = "PostgreSQL", enabled = false),
        FeedModule(id = "learning", kind = "learning", preferences = mapOf("level" to "advanced")),
    )

    @Test fun defaultCatalogIncludesEveryModuleAndFilteringPreservesSavedOrderAndChoices() {
        assertEquals(modules, ModuleDiscovery.filter(modules))
        assertEquals(listOf("weather", "systems"), ModuleDiscovery.filter(modules, status = ModuleDiscovery.DISABLED).map { it.id })
        assertEquals(listOf("news", "learning"), ModuleDiscovery.filter(modules, status = ModuleDiscovery.ENABLED).map { it.id })
        assertEquals(listOf(modules[2]), ModuleDiscovery.filter(modules, status = ModuleDiscovery.DISABLED, group = "signals"))
        assertEquals(listOf(modules[1]), ModuleDiscovery.filter(modules, group = "life"))
        assertEquals(mapOf("level" to "advanced"), modules.last().preferences)
    }

    @Test fun searchUsesLocalizedNamesTopicsAndEveryWordWithoutReorderingTheCatalog() {
        val labels = mapOf("weather" to "Hali ya hewa", "learning" to "Learn · courses and guides")
        assertEquals(listOf(modules[1]), ModuleDiscovery.filter(modules, query = " HEWA hali ", localizedLabels = labels))
        assertEquals(listOf(modules[2]), ModuleDiscovery.filter(modules, query = "postgres server", localizedLabels = labels))
        assertTrue(ModuleDiscovery.filter(modules, query = "weather security", localizedLabels = labels).isEmpty())
        assertTrue(ModuleDiscovery.matches("cafe", "Café operations"))
        assertTrue(ModuleDiscovery.matches("  ", "Any module"))
    }

    @Test fun filteredReorderKeepsEveryHiddenModuleInItsPreviousSlot() {
        val reordered = ModuleOrder.reorderSubset(modules.map { it.id }, listOf("learning", "news"))
        assertEquals(listOf("learning", "weather", "systems", "news"), reordered)
        assertEquals(modules[1], ModuleOrder.reorder(modules, reordered)[1])
        assertEquals(modules[2], ModuleOrder.reorder(modules, reordered)[2])
    }

    @Test fun customFocusRetainsTheModuleEditorialRequirementsAndSelectedPreferences() {
        val selected = FeedModule(id = "security", kind = "security", query = "PostgreSQL and Linux", preferences = mapOf("urgency" to "critical"))
        val context = ModuleDefaults.requestContext(selected)
        assertTrue(context.contains("vendor advisories"))
        assertTrue(context.contains("affected products and versions"))
        assertTrue(context.contains("PostgreSQL and Linux"))
        assertTrue(context.contains("critical vendor advisories"))
        assertTrue(context.contains("confirmed context"))
        assertTrue(context.contains("selection data"))
        assertEquals("PostgreSQL and Linux", selected.query)
        ModuleCatalog.kinds.forEach { kind ->
            assertTrue(ModuleDefaults.requestContext(FeedModule(id = kind, kind = kind)).contains(ModuleCatalog.briefGuidance(kind)))
        }
    }
}
