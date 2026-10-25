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

/** Actual native shell rendering of dated public example fixtures; no live provider requests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Release083LibraryCaptureTest {
 @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
 private val items=FeedJson.decodeFromString<List<FeedItem>>(javaClass.getResourceAsStream("/release-082/public-items.json")!!.bufferedReader().use{it.readText()})
 private val now=items.first().fetchedAtMillis
 private val state=FeedState(onboardingComplete=true,region="Tanzania",timezone="Africa/Dar_es_Salaam",interestsConfigured=true,quickGuideSeen=true,
    items=listOf(items.first().copy(media=null)), savedIds=setOf(items.first().id), savedCollections=mapOf(items.first().id to "Community"),
    starredStories=listOf(StarredStory(items.first().id,starredAtMillis=now,lastCheckedAtMillis=now)),
    readingPreferences=ReadingPreferences(autoLoadMore=false),lastRefreshMillis=now)
 private val base=FeedUiState(state=state,loading=false,connected=true)
 @Composable private fun Frame(page:String,body:@Composable ()->Unit) {
   MeBotTheme { CompositionLocalProvider(LocalPublisherImageLoader provides {null},LocalFeedClock provides now,
       LocalRelevanceContext provides RelevanceUiContext(state,onVote={_,_->},onStar={},onConfigureStar={_,_,_->},onCollection={_,_->})) {
       Scaffold(bottomBar={MeBotNavigation(page,{})}) { padding -> Box(Modifier.fillMaxSize().padding(padding)){body()} }
   } }
 }
 @Test fun feedShowsStarWithinNativeNavigation() {
   compose.setContent { Frame("feed") { FeedHome(base,{},{},{},{},{},{},{},{},{},null,{}) } }
   compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("star-${items.first().id}"))
   compose.onNodeWithTag("star-${items.first().id}").assertIsDisplayed()
   capture("feed")
 }
 @Test fun savedShowsFollowingCadenceWithinNativeNavigation() {
   compose.setContent { Frame("saved") { SavedFeed(state,{},{},{}) } }
   compose.onNodeWithTag("library-starred").performClick()
   compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("star-active-${items.first().id}"))
   compose.onNodeWithTag("star-active-${items.first().id}").assertIsDisplayed()
   capture("saved")
 }
 private fun capture(name:String) {
   compose.mainClock.advanceTimeBy(1200);compose.waitForIdle()
   val bitmap=compose.runOnIdle{
      val w=compose.activity.window;w.decorView.invalidate()
      Bitmap.createBitmap(w.decorView.width,w.decorView.height,Bitmap.Config.ARGB_8888).also{var result:Int?=null;PixelCopy.request(w,it,{r->result=r},Handler(Looper.getMainLooper()));assertEquals(PixelCopy.SUCCESS,result)}
   }
   val file=File("build/outputs/screenshots/v084/$name.png");file.parentFile!!.mkdirs();file.outputStream().use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
 }
}
