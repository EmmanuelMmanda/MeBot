package mebot.nguli.com.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class Release082Test {
    private val now=1_793_000_000_000L
    private fun item(id:String="a",module:String="tech",title:String="Private local AI deployment for hospitals",summary:String="Local inference deployment costs and data privacy")=
        FeedItem(id,module,title,summary,sourceUrl="https://example.com/$id",sourceName="Source",kind="technology",fetchedAtMillis=now,publishedAtMillis=now)
    private fun state(vararg items:FeedItem)=FeedState(modules=listOf(FeedModule(id="tech",kind="technology"),FeedModule(id="music",kind="music")),items=items.toList())

    @Test fun relevantFeedbackImprovesRelatedContentOnlyInItsModule() {
        val original=state(item(),item("b"),item("c","music"))
        val next=RelevanceFeedback.record(original,"a",true,now)
        assertTrue(RelevanceFeedback.adjustment(next,item("b"),now)>0)
        assertEquals(0,RelevanceFeedback.adjustment(next,item("c","music"),now))
        assertEquals(0,RelevanceFeedback.adjustment(next,item("z",title="Orchestra piano live concert",summary="Songs and musical performances"),now))
        assertEquals(listOf(next.relevanceSignals.single()),RelevanceFeedback.forModule(next,next.modules.first()))
        assertTrue(RelevanceFeedback.forModule(next,next.modules.last()).isEmpty())
    }
    @Test fun replacingTheVoteDoesNotStackAndUndoDoesNotAlterOtherPersonalData() {
        val original=state(item()).copy(savedIds=setOf("a"),viewedAtMillis=mapOf("a" to now))
        val positive=RelevanceFeedback.record(original,"a",true,now)
        assertEquals(positive,RelevanceFeedback.record(positive,"a",true,now+100))
        val negative=RelevanceFeedback.record(positive,"a",false,now+1)
        assertEquals(1,negative.relevanceSignals.size)
        assertTrue(RelevanceFeedback.adjustment(negative,item(),now+1)<0)
        assertEquals(original,RelevanceFeedback.record(negative,"a",null,now+2))
    }
    @Test fun feedbackIsBoundedAndDecaysInsteadOfBecomingPermanent() {
        val s=RelevanceFeedback.record(state(item()),"a",true,now)
        assertTrue(RelevanceFeedback.adjustment(s,item(),now)>RelevanceFeedback.adjustment(s,item(),now+180L*86_400_000))
        val many=s.copy(relevanceSignals=(1..500).map{ s.relevanceSignals.single().copy(itemId=it.toString()) })
        assertEquals(35,RelevanceFeedback.adjustment(many,item(),now))
        val n=RelevanceFeedback.record(many.copy(items=listOf(item("last"))),"last",false,now+1)
        assertEquals(500,n.relevanceSignals.size);RelevanceFeedback.validate(n.relevanceSignals)
    }
    @Test fun unusualLongWordsCannotBreakFeedbackPersistence() {
        val x=item(summary="a".repeat(700))
        val updated=RelevanceFeedback.record(state(x),x.id,true,now)
        RelevanceFeedback.validate(updated.relevanceSignals)
        val restored=Json.decodeFromString<FeedState>(Json.encodeToString(updated))
        assertEquals(updated.relevanceSignals,restored.relevanceSignals)
    }
    @Test fun importedOrOnboardedPreferencesDoNotProduceAnImportNag() {
        assertEquals("preferences",SetupGuidance.next(FeedState(),true,"feed",now))
        assertNull(SetupGuidance.next(FeedState(interestsConfigured=true),true,"feed",now))
        assertNull(SetupGuidance.next(FeedState(context=listOf(ContextEntry(text="Interested in music"))),true,"feed",now))
        val dismissed=FeedState(dismissedNotices=mapOf("connection" to now))
        assertNotEquals("connection",SetupGuidance.next(dismissed,false,"feed",now+1))
        assertEquals("connection",SetupGuidance.next(dismissed,false,"feed",now+SetupGuidance.SNOOZE_MILLIS))
    }
    @Test fun introducedFieldsRemainBackwardCompatibleAndHistoryDoesNotEraseVotes() {
        val s=Json.decodeFromString<FeedState>("{}")
        assertEquals(28,s.dailyRequestLimit);assertFalse(s.backgroundEnabled);assertTrue(s.relevanceSignals.isEmpty())
        val x=RelevanceFeedback.record(state(item()).copy(viewedAtMillis=mapOf("a" to now)),"a",true,now)
        assertEquals(x.relevanceSignals,x.copy(viewedAtMillis=emptyMap()).relevanceSignals)
    }
    @Test fun theSummaryIntroIsAlwaysNonEmptyWhenTheSourceHasText() {
        assertEquals("A useful introduction.",DetailPresentation.introduction("A useful introduction."))
        val text="Information about this source. ".repeat(100)
        val short=DetailPresentation.introduction(text)
        assertTrue(short.isNotBlank());assertTrue(short.length<=421);assertTrue(text.startsWith(short.removeSuffix("…")))
        assertEquals("",DetailPresentation.introduction("  "))
    }
    @Test fun providerEndpointsAreExplicitAndRejectPlaintextCloudOrCredentialsInUrls() {
        AiProviders.validate(AiConnection(mode="claude",model="model-id"))
        AiProviders.validate(AiConnection(mode="openrouter",model="provider/model-name"))
        AiProviders.validate(AiConnection(mode="compatible",model="local-model:latest",serviceUrl="http://192.168.1.2:11434/v1",allowLocalHttp=true))
        for(url in listOf("http://example.com/v1","https://user:key@example.com/v1","https://example.com/v1?key=secret","https://example.com/v1#key","https://example.com/../secret"))
            assertFalse(url,AiProviders.validEndpoint(url,true))
        assertFalse(AiProviders.validEndpoint("http://192.168.1.2/v1",false))
        assertTrue(AiProviders.validEndpoint("https://inference.example/v1",false))
        assertFalse(AiProviders.supportsWeb(AiConnection(mode="compatible",model="local",serviceUrl="https://local.example/v1")))
        assertFalse(AiProviders.supportsWeb(AiConnection(mode="gemini",model="catalog-model")))
        assertTrue(AiProviders.supportsWeb(AiConnection(mode="claude",model="catalog-model")))
    }
    @Test fun connectionSettingsNeverContainAccountIdsFromOtherProviders() {
        assertTrue(runCatching{AiProviders.validate(AiConnection(mode="claude",model="x",accountId="chatgpt-account"))}.isFailure)
        assertTrue(runCatching{AiProviders.validate(AiConnection(mode="compatible",model="../secret",serviceUrl="https://server.example/v1"))}.isFailure)
        assertEquals("https://api.anthropic.com/v1",AiProviders.baseUrl(AiConnection(mode="claude")))
    }
    private val certificate="a".repeat(64)
    private fun metadata(pkg:String="mebot.nguli.com",code:Int=14,cert:String=certificate,hash:String="b".repeat(64),bytes:Long=500,min:Int=26)=
        """{"version":"0.8.3","versionCode":$code,"applicationId":"$pkg","minimumSdk":$min,"signerCertificateSha256":"$cert","assets":[{"name":"mebot-0.8.3.apk","bytes":$bytes,"sha256":"$hash"}]}"""
    @Test fun softwareUpdatesAcceptOnlyANewerSamePackageSameCertificateArtifact() {
        val release=SoftwareUpdates.parse(metadata(),"v0.8.3","mebot.nguli.com",13,certificate)!!
        assertEquals(14,release.versionCode);assertEquals("https://github.com/EmmanuelMmanda/MeBot/releases/download/v0.8.3/mebot-0.8.3.apk",release.apkUrl)
        assertNull(SoftwareUpdates.parse(metadata(pkg="app.mynguli"),"v0.8.3","mebot.nguli.com",13,certificate))
        assertNull(SoftwareUpdates.parse(metadata(code=13),"v0.8.3","mebot.nguli.com",13,certificate))
        for(m in listOf(metadata(cert="c".repeat(64)),metadata(hash="invalid"),metadata(bytes=0),metadata(bytes=SoftwareUpdates.MAX_APK_BYTES+1),metadata(min=1)))
            assertTrue(runCatching{SoftwareUpdates.parse(m,"v0.8.3","mebot.nguli.com",13,certificate)}.isFailure)
        assertTrue(runCatching{SoftwareUpdates.parse(metadata(),"v0.8.3-beta.1","mebot.nguli.com",13,certificate)}.isFailure)
    }
    @Test fun releaseAssetPolicyRejectsWrongOwnersTraversalAndCredentialUrls() {
        assertTrue(SoftwareUpdates.releaseAsset("https://github.com/EmmanuelMmanda/MeBot/releases/download/v0.8.3/release-metadata.json","v0.8.3"))
        for(u in listOf("https://github.com/Other/MeBot/releases/download/v0.8.3/release-metadata.json","https://github.com/EmmanuelMmanda/MeBot/releases/download/v0.8.3/../bad/file.json","https://evil.example/releases/download/v0.8.3/release-metadata.json","http://github.com/EmmanuelMmanda/MeBot/releases/download/v0.8.3/file.json"))
            assertFalse(u,SoftwareUpdates.releaseAsset(u,"v0.8.3"))
    }
    @Test fun changingRequestAllowanceNeverResetsTheExistingCounter() {
        val today=FeedLogic.dateAt(now,"Africa/Dar_es_Salaam")
        val s=FeedState(timezone="Africa/Dar_es_Salaam",usageDate=today,requestsToday=27,dailyRequestLimit=28)
        assertEquals(27,RequestBudget.usedToday(s.copy(dailyRequestLimit=0),now))
        assertEquals(28,RequestBudget.reserve(s.copy(dailyRequestLimit=0),now)!!.requestsToday)
        assertNull(RequestBudget.reserve(s.copy(dailyRequestLimit=12),now))
    }
    @Test fun updaterIgnoresLegacyManifestsThatDoNotIdentifyThisApplication() {
        assertNull(SoftwareUpdates.parse("""{"version":"0.8.0","assets":[]}""","v0.8.0","mebot.nguli.com",13,"b".repeat(64)))
        assertNull(SoftwareUpdates.parse("""{"applicationId":"app.mynguli"}""","v0.8.1","mebot.nguli.com",13,"b".repeat(64)))
    }

}
