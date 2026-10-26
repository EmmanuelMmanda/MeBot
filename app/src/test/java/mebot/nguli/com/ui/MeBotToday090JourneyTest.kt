package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.MainActivity
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual Activity, navigation, ViewModel and durable repository. External transports are fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi",application=FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotToday090JourneyTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as FeedTestApplication
    private fun state()=runBlocking { app.feedRepository.current() }
    private fun waitForState(predicate:(FeedState)->Boolean) { compose.waitUntil(15_000) { predicate(state()) } }
    private fun waitForTag(tag:String) { compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() } }

    @Test fun actualTodayShellKeepsStarBookmarkCollectionsFollowSettingsAndOriginalOwnership() {
        compose.waitForIdle()
        val now=System.currentTimeMillis()
        val item=FeedItem(id="today-owned",moduleId="news",kind="news",title="A local market update — illustrative fixture",
            summary="Synthetic source-backed reading fixture for native Activity verification.",sourceName="Example publisher",
            sourceUrl="https://example.com/today-owned",publishedAtMillis=now,fetchedAtMillis=now,
            temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now))
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.update { AppState(profile=Profile(onboardingComplete=true)) }
            app.feedRepository.update { FeedState(onboardingComplete=true,interestsConfigured=true,quickGuideSeen=true,
                timezone="UTC",modules=listOf(FeedModule(id="news",kind="news")),items=listOf(item),lastRefreshMillis=now,
                context=listOf(ContextEntry(id="scoped",text="Synthetic local business interest",scopes=setOf("news")))) }
        }
        waitForTag("feed-screen")
        for(tab in listOf("feed","modules","ai","saved","you")) compose.onNodeWithTag("nav-$tab").assertIsDisplayed()
        compose.onNodeWithTag("filter-today").performScrollTo().performClick()
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("star-${item.id}"))
        compose.onNodeWithTag("star-${item.id}").assertIsDisplayed()
        compose.onNodeWithTag("save-${item.id}").assertIsDisplayed()
        val star=compose.onNodeWithTag("star-${item.id}").getUnclippedBoundsInRoot()
        val bookmark=compose.onNodeWithTag("save-${item.id}").getUnclippedBoundsInRoot()
        assertTrue(star.right<=bookmark.left)
        assertEquals(star.top,bookmark.top)
        compose.onNodeWithTag("star-${item.id}").performClick()
        waitForState { it.starredStories.any { star->star.itemId==item.id } }
        compose.onNodeWithTag("save-${item.id}").performClick()
        waitForState { item.id in it.savedIds }
        capture("today-full-shell-en")
        compose.onNodeWithTag("nav-saved").performClick()
        waitForTag("saved-library")
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("library-collection-${item.id}"))
        compose.onNodeWithTag("library-collection-${item.id}").performClick()
        compose.onNodeWithTag("collection-name").performScrollTo().performTextInput("Market reading")
        compose.onNodeWithText(compose.activity.getString(R.string.library_collection_apply)).performScrollTo().performClick()
        waitForState { it.savedCollections[item.id]=="Market reading" }
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("library-starred"))
        compose.onNodeWithTag("library-starred").performClick()
        compose.onNodeWithTag("saved-library").performScrollToNode(hasTestTag("star-active-${item.id}"))
        compose.onNodeWithTag("star-active-${item.id}").performClick()
        waitForState { it.starredStories.single().paused }
        assertEquals(item,state().items.single())
        assertEquals("news",state().items.single().moduleId)
        assertEquals(setOf("news"),state().context.single().scopes)
        assertEquals(0,state().requestsToday)
        compose.activityRule.scenario.recreate()
        waitForTag("saved-library")
        assertEquals("Market reading",state().savedCollections[item.id])
        assertTrue(state().starredStories.single().paused)
        compose.onNodeWithTag("nav-feed").performClick()
        waitForTag("feed-screen")
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("item-${item.id}"))
        compose.onNodeWithTag("item-${item.id}").performClick()
        waitForTag("detail-save")
        compose.onNodeWithText(item.title).assertExists()
        assertEquals(1,state().items.size)
        assertEquals(0,state().requestsToday)
    }

    private fun capture(name:String) {
        compose.waitForIdle()
        val view=compose.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        var status=-1
        compose.runOnIdle { PixelCopy.request(compose.activity.window,bitmap,{status=it},Handler(Looper.getMainLooper())) }
        compose.waitUntil(10_000) { status!=-1 };assertEquals(PixelCopy.SUCCESS,status)
        File("build/outputs/screenshots/v090/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
