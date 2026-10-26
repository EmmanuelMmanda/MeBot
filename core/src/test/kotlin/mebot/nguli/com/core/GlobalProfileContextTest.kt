package mebot.nguli.com.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class GlobalProfileContextTest {
    private val about = ContextEntry(id=PersonalProfile085.PROFILE_ID, text="I run a small shop", category="profile", scopes=emptySet(), global=true, updatedAtMillis=1000)
    private val music = ContextEntry(id="music-note", text="I practise piano",scopes=setOf("music"),updatedAtMillis=1000)
    private val hidden = ContextEntry(id="private-note",text="Not shared",scopes=setOf("news"),confirmed=false,updatedAtMillis=1000)
    private val state = FeedState(modules=listOf(FeedModule(id="music",kind="music"),FeedModule(id="food",kind="food",enabled=false)),context=listOf(music,hidden,about))

    @Test fun globalProfileReachesCurrentDisabledFutureAndCustomModuleRequests() {
        for (kind in listOf("food","technology","watch","future-kind")) {
            assertEquals(listOf(about),FeedLogic.scopedContext(state,FeedModule(id="custom-$kind",kind=kind,enabled=false)))
        }
        assertEquals(listOf(about,music),FeedLogic.scopedContext(state,state.modules.first()))
        assertEquals(listOf(about),FeedLogic.contextForKinds(state,emptySet()))
        assertEquals(listOf(about),FeedLogic.contextForKinds(state,setOf("music"),limit=1))
    }
    @Test fun freeAndTopicChatUseGlobalProfileWithoutBroadeningUnrelatedNotes() {
        assertEquals(listOf(about),ConversationLogic.context(state,AiConversation()))
        assertEquals(listOf(about),RecommendationPolicy.contextForQuestion(state,"Hello, can you help?"))
        assertEquals(listOf(about),RecommendationPolicy.contextForQuestion(state,"What matters for me today?"))
        assertEquals(listOf(about),RecommendationPolicy.generalContext(state))
        assertEquals(listOf(about,music),RecommendationPolicy.contextForQuestion(state,"Nipatie muziki"))
        assertEquals(listOf(about,music),ConversationLogic.context(state,AiConversation(moduleId="music")))
        assertEquals(listOf(about),ConversationLogic.context(state,AiConversation(moduleId="food")))
    }
    @Test fun questionRoutingMetadataUsesTheSameBoundedContextSelection() {
        for ((question,kinds) in listOf("Nipatie muziki" to setOf("music"), "What matters for me" to emptySet(), "Food ideas" to emptySet(), "Hello" to emptySet())) {
            assertEquals(kinds, RecommendationPolicy.questionKinds(state,question))
            assertEquals(FeedLogic.contextForKinds(state,kinds,24),RecommendationPolicy.contextForQuestion(state,question))
        }
    }
    @Test fun legacyProfileAndOnboardingAreNotPromotedOrFlattened() {
        val old = listOf(ContextEntry(id="profile-about-you",text="Old scoped profile",scopes=setOf("music")),
            ContextEntry(id="onboarding-interests",text="Original interest",scopes=setOf("food")))
        val draft = PersonalProfile085.draft(old)
        assertEquals(old,draft.filter { it.id!=PersonalProfile085.PROFILE_ID })
        val untouched=state.copy(context=old)
        assertTrue(RecommendationPolicy.contextForQuestion(untouched,"Hello").isEmpty())
        assertTrue(FeedLogic.scopedContext(untouched,FeedModule(id="news",kind="news")).isEmpty())
        assertFalse(PersonalProfile085.primary(draft)!!.global)
    }
    @Test fun malformedOrUnconfirmedGlobalRecordsNeverEnterPayloads() {
        for (invalid in listOf(about.copy(id="arbitrary"),about.copy(category="instruction"),about.copy(scopes=setOf("music")))) {
            assertThrows(IllegalArgumentException::class.java) { FeedLogic.validate(state.copy(context=listOf(invalid))) }
            assertTrue(FeedLogic.scopedContext(state.copy(context=listOf(invalid)),state.modules.first()).isEmpty())
        }
        val unconfirmed=state.copy(context=listOf(about.copy(confirmed=false)))
        assertTrue(FeedLogic.scopedContext(unconfirmed,state.modules.first()).isEmpty())
        assertTrue(RecommendationPolicy.contextForQuestion(unconfirmed,"Hello").isEmpty())
    }
    @Test fun oldJsonDefaultsScopedAndGlobalBackupRoundTripRetainsExplicitConsent() {
        val old=Json.decodeFromString<ContextEntry>("""{"id":"profile-about-you","text":"Old profile","scopes":["music"]}""")
        assertFalse(old.global)
        assertEquals(setOf("music"),old.scopes)
        // Match the production FeedJson persistence/export contract, including dynamic defaults.
        val persistenceJson = Json { encodeDefaults = true }
        val encoded=persistenceJson.encodeToString(state)
        val decoded=persistenceJson.decodeFromString<FeedState>(encoded)
        FeedLogic.validate(decoded)
        assertEquals(state,decoded)
        assertTrue(PersonalProfile085.isGlobal(decoded.context.last()))
    }
}
