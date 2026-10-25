package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import mebot.nguli.com.MeBotNavigation
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.*
import mebot.nguli.com.ui.feed.*
import mebot.nguli.com.updates.SoftwareUpdateState
import kotlinx.serialization.json.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Dated public captures, never production seed data or live inference claims. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Release082CaptureTest {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 private val items=FeedJson.decodeFromString<List<FeedItem>>(javaClass.getResourceAsStream("/release-082/public-items.json")!!.bufferedReader().use{it.readText()})
 private val now=items.first().fetchedAtMillis
 private val state=FeedState(onboardingComplete=true,region="Tanzania",timezone="Africa/Dar_es_Salaam",interestsConfigured=true,quickGuideSeen=true,items=items,
    modules=ModuleCatalog.defaults.map{it.copy(enabled=it.kind in setOf("news","business","science","github","learning","travel","music"))},
    context=listOf(ContextEntry(id="interest",text="Local business, practical technology, research and learning.",scopes=setOf("news","business","science","github","learning"))),lastRefreshMillis=now)
 private val base=FeedUiState(state=state,loading=false,connected=true)
 private val bitmaps=mutableMapOf<String,Bitmap?>()
 private fun image(url:String):Bitmap?=bitmaps.getOrPut(url) {
    val root=System.getenv("MEBOT_CAPTURE_ASSETS")?.let(::File)?:return@getOrPut null
    val manifest=File(root,"manifest.json").takeIf{it.isFile}?:return@getOrPut null
    val record=FeedJson.parseToJsonElement(manifest.readText()).jsonObject["records"]!!.jsonArray.firstOrNull{it.jsonObject["imageUrl"]?.jsonPrimitive?.content==url}?.jsonObject?:return@getOrPut null
    val file=record["file"]?.jsonPrimitive?.content?.let{File(root,it)}?:return@getOrPut null
    BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply{inSampleSize=2})
 }
 @Composable private fun Frame(page:String="feed",ui:FeedUiState=base,body:@Composable ()->Unit) {
    MeBotTheme { CompositionLocalProvider(LocalPublisherImageLoader provides {url->image(url)},LocalFeedClock provides now,LocalRelevanceContext provides RelevanceUiContext(ui.state,{_,_->})) {
      Scaffold(topBar={ProcessingStatus(ui,page,ui.detailItemId,Modifier.padding(horizontal=20.dp,vertical=4.dp))},bottomBar={MeBotNavigation(page,{})}){p->Box(Modifier.fillMaxSize().padding(p)){body()}}
    } }
 }
 @Test fun feedShowsDatedLocalReportingWithItsPublisher() {
   compose.setContent{Frame{FeedHome(base,{},{},{},{},{},{},{},{},{},null,{},onSearch={})}}
   compose.onNodeWithTag("feed-screen").assertExists()
   compose.waitForIdle();capture("feed")
   compose.onNodeWithTag("filter-news").performScrollTo().performClick()
   compose.onNodeWithTag("item-capture-news").assertExists();capture("news")
 }
 @Test fun moduleCatalogShowsEnabledChoicesWithoutAnAccountRequirement() {
   compose.setContent{Frame("modules"){ModulesScreen(state,AppState(),{},{_,_->},{},{_,_->})}}
   compose.onNodeWithTag("module-search").assertExists();capture("modules")
 }
 @Test fun introductoryContentRemainsVisibleBesideStreaming() {
   val item=items.first();val ui=base.copy(detailItemId=item.id,detailLoading=true,detailText="The report describes a repair-led approach to keeping school furniture in use.\n\nCheck the source for the reported costs, number of items repaired and schools covered.",activities=listOf(AiActivity("story",item.id,"news","answer","writing",contextEntries=1)))
   compose.setContent{Frame("item",ui){FeedDetail(ui,item.id,{},{},{})}}
   compose.onNodeWithTag("detail-summary-intro").assertExists();capture("detail-streaming")
   compose.onNodeWithTag("detail-brief-draft").performScrollTo().assertIsDisplayed();capture("detail-answer")
 }
 @Test fun mebotDisplaysAConcreteSourceBackedResearchAnswer() {
   val item=items.last();val thread=AiConversation(id="research",moduleId="learning",turns=listOf(AiTurn("q","user","Where can I learn about support for small businesses in Tanzania?"),AiTurn("a","assistant","## Start with CRDB Bank Foundation\nIts programmes include entrepreneurship development and financial literacy for women and young people in Tanzania.\n\n**Check the programme before applying.** The programme page does not establish that an application window is open.\n\nThe annual report and documentary provide background; neither is an application form.",citations=item.citations)))
   val ui=base.copy(state=state.copy(conversations=listOf(thread)),activeConversationId=thread.id)
   compose.setContent{Frame("ai",ui){AiWorkspace(ui,{},{},{},{},{},{},{},{},{})}}
   compose.onNodeWithTag("workspace-messages").performScrollToIndex(0);capture("mebot")
 }
 @Test fun myMeBotShowsSoftwareUpdatesAsARealDestination() {
   compose.setContent{Frame("you"){YouScreen(base,{},{},{},{},{},onSoftwareUpdates={})}}
   compose.onNodeWithText("Software Updates").assertExists();capture("my-mebot")
 }
 @Test fun softwareUpdatesExplainsAutomaticChecksAndConsent() {
   compose.setContent{Frame("software-updates"){SoftwareUpdatesContent(SoftwareUpdateState(lastChecked=now),{},{},{},{},{_,_->})}}
   compose.onNodeWithText("Software Updates").assertExists();capture("software-updates")
 }
 @Test fun providerChoicesAndAllowanceRemainExplicit() {
   compose.setContent{MeBotTheme{ScreenContainer {ScreenTitle("AI connection");ProviderConnectionCard(base,{_,_->},{_,_->},{},{_,_->},{})}}}
   compose.onNodeWithTag("provider-claude").performClick();capture("providers")
 }
 @Test fun allowanceShowsConsumedCallsWithoutChangingTheBudget() {
   val s=state.copy(usageDate=java.time.LocalDate.now(java.time.ZoneId.of(state.timezone)).toString(),requestsToday=9)
   compose.setContent{MeBotTheme{ScreenContainer{ScreenTitle("AI connection");RequestAllowanceCard(s,true,{})}}}
   compose.onNodeWithTag("connection-limit-28").assertIsSelected();capture("usage")
 }
 @Test fun welcomeUsesLicensedPhotographyAndNoRequiredImport() {
   compose.setContent{MeBotTheme{FeedOnboarding(state.copy(onboardingComplete=false),"en",{},{},{})}}
   compose.onNodeWithTag("onboarding-progress").assertExists();capture("welcome")
   compose.onNodeWithTag("onboarding-next").performClick();compose.mainClock.advanceTimeBy(400);capture("interests")
   repeat(3){compose.onNodeWithTag("onboarding-next").performClick();compose.mainClock.advanceTimeBy(400)}
   compose.onNodeWithTag("onboarding-step-4").assertExists();capture("daily-tools")
 }
 @Test fun aboutCreditsActualPhotographyAndThePublicProject() {
   compose.setContent{MeBotTheme{ScreenContainer{AboutMeBotContent()}}}
   compose.onNodeWithText(compose.activity.getString(R.string.about_source)).assertExists();capture("about")
 }
 @Test fun newsFilterAndLearningFilterRetainTheirContentTypes() {
   compose.setContent{Frame{FeedHome(base,{},{},{},{},{},{},{},{},{},null,{},onSearch={})}}
   compose.onNodeWithTag("filter-learning").performScrollTo().performClick()
   compose.onNodeWithTag("item-capture-learning").assertExists();capture("learning")
 }
 private fun capture(name:String) {
   compose.mainClock.advanceTimeBy(1200);compose.waitForIdle()
   val bitmap=compose.runOnIdle{
      val w=compose.activity.window;w.decorView.invalidate()
      Bitmap.createBitmap(w.decorView.width,w.decorView.height,Bitmap.Config.ARGB_8888).also{var result:Int?=null;PixelCopy.request(w,it,{r->result=r},Handler(Looper.getMainLooper()));assertEquals(PixelCopy.SUCCESS,result)}
   }
   val file=File("build/outputs/screenshots/v082/$name.png");file.parentFile!!.mkdirs();file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
 }
}
