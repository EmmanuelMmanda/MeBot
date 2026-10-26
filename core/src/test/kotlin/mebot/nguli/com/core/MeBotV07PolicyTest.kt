package mebot.nguli.com.core

import org.junit.Assert.*
import org.junit.Test

class MeBotV07PolicyTest {
    private val now = 1_790_000_000_000L
    private fun item(id:String, kind:String="music", score:Int=70) = FeedItem(id,kind,"Piano lesson $id","Practice piano",kind=kind,sourceUrl="https://example.com/$id",publishedAtMillis=now,relevance=score)
    @Test fun termsHandleEnglishAndSwahili() {
        assertTrue("muziki" in RecommendationPolicy.terms("Napenda muziki wa piano"))
        assertTrue("piano" in RecommendationPolicy.terms("I practise piano"))
    }
    @Test fun contextDoesNotLeakModuleOnlyOrUnconfirmedNotesIntoGeneralChat() {
        val s=FeedState(modules=listOf(FeedModule(id="music",kind="music")),context=listOf(
            ContextEntry(text="Piano",scopes=setOf("music")),
            ContextEntry(text="Private work",category="private",scopes=setOf("music")),
            ContextEntry(text="Hidden",confirmed=false,scopes=setOf("music")),
            ContextEntry(text="Career",scopes=setOf("opportunities"))))
        assertTrue(RecommendationPolicy.generalContext(s).isEmpty())
    }
    @Test fun savesAreRelevantOnlyWithinTheirModule() {
        val a=item("a");val b=item("b");val s=FeedState(items=listOf(a,b),savedIds=setOf("a"))
        assertTrue(RecommendationPolicy.score(s,b,now).feedback>0)
        assertEquals(0,RecommendationPolicy.score(s,b.copy(moduleId="news"),now).feedback)
    }
    @Test fun viewedPenaltyCanBeDisabled() {
        val a=item("a");val s=FeedState(viewedAtMillis=mapOf(a.id to now))
        assertTrue(RecommendationPolicy.score(s,a,now).novelty<0)
        assertTrue(RecommendationPolicy.score(s.copy(readingHistoryEnabled=false),a,now).novelty>0)
    }
    @Test fun currentNewsOutranksVeryOldHighScoreNews() {
        val a=item("new","news",80);val old=item("old","news",99).copy(publishedAtMillis=now-20*86_400_000L)
        assertTrue(RecommendationPolicy.score(FeedState(),a,now).total>RecommendationPolicy.score(FeedState(),old,now).total)
    }
    @Test fun explorationDoesNotResurfaceHiddenOrDisabledItems() {
        val s=FeedState(modules=listOf(FeedModule(id="music",kind="music")),items=listOf(item("a"),item("b"),item("c","weather")),hiddenIds=setOf("a"),editionSequence=8)
        assertEquals(listOf("b"),FeedLogic.edition(s,now).map{it.id})
    }
    @Test fun youtubeIdsAcceptRealUrlShapesOnly() {
        val id="abcdefghijk"
        assertEquals(id,RichContent.youtubeId("https://www.youtube.com/watch?v=$id&t=12"))
        assertEquals(id,RichContent.youtubeId("https://youtu.be/$id"))
        assertEquals(id,RichContent.youtubeId("https://www.youtube.com/shorts/$id"))
        assertNull(RichContent.youtubeId("https://youtube.com.evil.example/watch?v=$id"))
        assertNull(RichContent.youtubeId("javascript:alert(1)"))
        assertNull(RichContent.youtubeId("https://youtube.com/watch?v=bad"))
    }
    @Test fun documentsAndAudioUseDestinationNotHeadline() {
        assertEquals("document",RichContent.type("https://example.com/guide.pdf?download=1"))
        assertEquals("audio",RichContent.type("https://example.com/episode.mp3"))
        assertEquals("link",RichContent.type("https://example.com/best-youtube-video"))
        assertEquals("link",RichContent.type("http://127.0.0.1/private.mp3"))
    }
    @Test fun promptsFollowTheModuleAndLanguage() {
        assertTrue(PromptSuggestions.forModule("weather",null,true).first().contains("mvua"))
        assertTrue(PromptSuggestions.forModule("music",null,false).first().contains("music"))
        assertFalse(PromptSuggestions.forModule("weather",null,true).first().contains("music"))
    }
    @Test fun generalSuggestionsOnlyExposeEnabledModuleTopics() {
        val s=FeedState(modules=listOf(FeedModule(kind="weather",enabled=false),FeedModule(kind="music")))
        val prompts=PromptSuggestions.forModule(null,s,false)
        assertTrue(prompts.any{it.contains("music")});assertFalse(prompts.any{it.contains("rain")})
    }
    @Test fun prefetchRangeIsValidated() {
        FeedLogic.validate(FeedState(prefetchBatches=3))
        assertThrows(IllegalArgumentException::class.java){FeedLogic.validate(FeedState(prefetchBatches=99))}
    }
    @Test fun generalQuestionsRouteBeforeNotesAreShared() {
        val s=FeedState(modules=listOf(FeedModule(kind="music"),FeedModule(kind="technology")),context=listOf(
            ContextEntry(text="Piano",scopes=setOf("music")),ContextEntry(text="Infrastructure",scopes=setOf("technology"))))
        assertTrue(RecommendationPolicy.contextForQuestion(s,"A general question").isEmpty())
        assertEquals(listOf("Piano"),RecommendationPolicy.contextForQuestion(s,"Nipatie muziki").map{it.text})
    }
    @Test fun swahiliPolicyAsksForOrdinaryTanzanianLanguage() {
        assertTrue(LanguagePolicy.instruction("sw").contains("Tanzania"))
        assertTrue(LanguagePolicy.instruction("en").contains("everyday English"))
    }
}
