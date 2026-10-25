package mebot.nguli.com.core

import org.junit.Assert.*
import org.junit.Test

class EverydayModules083Test {
    @Test fun additionsHaveStableIdsAndRemainPausedDuringUpgrade() {
        val old = FeedModule(id="news",kind="news",enabled=true,query="Keep this")
        val upgraded = ModuleCatalog.mergeCatalog(listOf(old))
        assertEquals(old, upgraded.first())
        assertEquals(setOf("food","farming","family","sport","home","wellbeing"), EverydayModules083.presets.map { it.kind }.toSet())
        EverydayModules083.presets.forEach { preset ->
            val added = upgraded.single { it.id == preset.kind }
            assertFalse(added.enabled)
            assertTrue(ModulePreferences.definitions(added.kind).isNotEmpty())
            assertTrue(ModuleCatalog.briefGuidance(added.kind).isNotBlank())
            assertTrue(FeedLogic.scopedContext(FeedState(context=listOf(ContextEntry(text="News only",scopes=setOf("news")))),added).isEmpty())
        }
        assertEquals(upgraded, ModuleCatalog.mergeCatalog(upgraded))
    }
    @Test fun bilingualSearchAndStatusRemainIndependent() {
        val modules = listOf(FeedModule(id="food",kind="food",enabled=false),FeedModule(id="sport",kind="sport",enabled=true))
        assertEquals(listOf("food"),ModuleDiscovery.filter(modules,query="mapishi",localizedLabels=mapOf("food" to "Chakula na mapishi")).map{it.id})
        assertTrue(ModuleDiscovery.filter(modules,status=ModuleDiscovery.ENABLED,query="mapishi",localizedLabels=mapOf("food" to "Chakula na mapishi")).isEmpty())
    }
    @Test fun everydayPreferenceSchemaRejectsUnexpectedValues() {
        val module=FeedModule(id="food",kind="food",preferences=mapOf("format" to "videos","language" to "sw","location" to "Dar es Salaam"))
        ModulePreferences.validate(module)
        assertTrue(ModulePreferences.discoveryGuidance(module).contains("Kiswahili"))
        assertThrows(IllegalArgumentException::class.java){ModulePreferences.validate(module.copy(preferences=mapOf("execute" to "commands")))}
    }
}
