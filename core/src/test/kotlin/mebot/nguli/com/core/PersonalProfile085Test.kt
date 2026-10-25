package mebot.nguli.com.core

import org.junit.Assert.*
import org.junit.Test

class PersonalProfile085Test {
    @Test fun unicodeWhitespaceAndSwahiliWordsHavePredictableBoundaries() {
        assertEquals(4, PersonalProfile085.words("  Ninapenda\u00a0kilimo\nna\u3000ufugaji  "))
        assertEquals(0, PersonalProfile085.words("\t\u00a0\n"))
        assertTrue(PersonalProfile085.valid(List(150) { "na" }.joinToString(" ")))
        assertFalse(PersonalProfile085.valid(List(151) { "na" }.joinToString(" ")))
        assertTrue(PersonalProfile085.valid("a".repeat(1000)))
        assertFalse(PersonalProfile085.valid("a".repeat(1001)))
        assertFalse(PersonalProfile085.valid("unsafe\u0000value"))
    }
    @Test fun legacyUpgradeDoesNotFlattenConfirmOrWidenAnyRecord() {
        val old = listOf(ContextEntry(id="old",text="Keep this",scopes=setOf("food"),confirmed=false,origin="imported",updatedAtMillis=1))
        val draft = PersonalProfile085.draft(old,setOf("learning"))
        assertEquals(old.single(),draft.last())
        assertTrue(PersonalProfile085.primary(draft)!!.scopes.isEmpty())
        assertFalse(PersonalProfile085.primary(draft)!!.global)
        assertEquals("",PersonalProfile085.primary(draft)!!.text)
        assertEquals(draft,PersonalProfile085.draft(draft,setOf("news")))
    }
    @Test fun fullLegacyCollectionAllowsBlankDraftWithoutMutatingSavedRecords() {
        val old = (1..80).map { ContextEntry(id="old-$it", text="Saved detail $it", confirmed=false, scopes=setOf("food")) }
        val draft = PersonalProfile085.draft(old, setOf("news"))
        assertEquals(old, draft.filter { it.text.isNotBlank() })
        FeedLogic.validate(FeedState(context=draft.filter { it.text.isNotBlank() }))
    }
    @Test fun overLimitImportedPrimaryRemainsVerbatimWithOriginalScopes() {
        val old = ContextEntry(id=PersonalProfile085.PROFILE_ID, text="na ".repeat(180), scopes=setOf("farming"), origin="imported")
        val draft = PersonalProfile085.draft(listOf(old),setOf("news"))
        assertEquals(listOf(old),draft)
        assertFalse(PersonalProfile085.valid(draft.single().text))
    }
    @Test fun onboardingEntryIsReusedWithoutAutomaticMergeOrPermissionExpansion() {
        val entry=ContextEntry(id="onboarding-interests",text="Kilimo",scopes=setOf("farming"),updatedAtMillis=1)
        val old=listOf(entry,ContextEntry(id="private",text="Other details",scopes=emptySet(),confirmed=false))
        assertEquals(old,PersonalProfile085.draft(old,setOf("news")).filterNot { it.id == PersonalProfile085.PROFILE_ID })
        val updated=PersonalProfile085.onboarding(old,"  Bustani  ",setOf("farming"))
        assertEquals(old.last(),updated.first())
        assertEquals("Bustani",updated.last().text)
        assertEquals(setOf("farming"),updated.last().scopes)
        assertEquals(listOf(old.last()),PersonalProfile085.onboarding(old,"",emptySet()))
        assertThrows(IllegalArgumentException::class.java) { PersonalProfile085.onboarding(old,"word ".repeat(151),setOf("news")) }
    }
}
