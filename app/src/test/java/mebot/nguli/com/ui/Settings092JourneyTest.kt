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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Full native Activity; synthetic follows, no owner data, PIN or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi",application=FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Settings092JourneyTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as FeedTestApplication
    @Test fun englishSettingsAndFollowPersistence()=journey("en")
    @Test @Config(qualifiers="sw-rTZ-w411dp-h891dp-xhdpi") fun swahiliSettingsAndFollowPersistence()=journey("sw")
    @Test @Config(qualifiers="sw-rTZ-w320dp-h800dp-xhdpi") fun largeTextActionsRemainReachable() {
        RuntimeEnvironment.setFontScale(1.3f);compose.activityRule.scenario.recreate();journey("sw","sw-stress")
    }
    private fun journey(language:String,suffix:String=language) {
        compose.waitForIdle();val now=System.currentTimeMillis()
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.update{AppState(profile=Profile(onboardingComplete=true),settings=Settings(language=language))}
            app.feedRepository.update{FeedState(onboardingComplete=true,quickGuideSeen=true,interestsConfigured=true,
                lastRefreshMillis=now,modules=listOf(FeedModule(id="news",kind="news")),
                items=listOf(FeedItem(id="sample-follow",moduleId="news",kind="news",title="Local market update — sample",summary="Illustrative follow-up fixture",sourceName="Example publisher",sourceUrl="https://example.com/sample",fetchedAtMillis=now)),
                starredStories=listOf(StarredStory("sample-follow",checkEveryHours=24,starredAtMillis=now)))}
        }
        waitForTag("nav-you");compose.onNodeWithTag("nav-you").performClick()
        compose.onNodeWithText(text(R.string.personalization_092)).performScrollTo().assertIsDisplayed()
        capture("you-$suffix")
        compose.onNodeWithText(text(R.string.starred_settings_092)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.starred_background_off_092)).assertExists()
        capture("starred-$suffix")
        compose.onNodeWithTag("star-frequency-sample-follow").performScrollTo().performClick()
        compose.onNodeWithTag("star-frequency-sample-follow-72").performClick()
        compose.waitUntil(10000){runBlocking{app.feedRepository.current()}.starredStories.single().checkEveryHours==72}
        compose.onNodeWithTag("star-active-sample-follow").performScrollTo().performClick()
        compose.waitUntil(10000){runBlocking{app.feedRepository.current()}.starredStories.single().paused}
        compose.activityRule.scenario.recreate()
        waitForTag("star-active-sample-follow");compose.onNodeWithTag("star-active-sample-follow").assertIsOff()
        compose.onNodeWithText(compose.activity.getString(R.string.star_every_hours,72)).assertExists()
        compose.onNodeWithText(text(R.string.starred_background_092)).performScrollTo().performClick()
        compose.onNodeWithTag("delivery-enabled").performScrollTo().assertIsOff()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() };compose.waitForIdle()
        compose.onNodeWithTag("star-active-sample-follow").assertExists()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        compose.onNodeWithText(text(R.string.personalization_092)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.personalization_lock_092)).performScrollTo().assertIsDisplayed()
        // Module choices remain in Modules, not duplicated in a settings toggle panel.
        compose.onNodeWithText(text(R.string.modules)).assertDoesNotExist()
        capture("personalization-$suffix")
        compose.onNodeWithText(text(R.string.app_lock_title)).performScrollTo().performClick()
        waitForTag("app-lock-settings")
        compose.onNodeWithText(text(R.string.lock_setup_intro_092)).assertExists()
        capture("lock-setup-$suffix")
        compose.onNodeWithText(text(R.string.lock_protects_092)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.app_lock_boundary)).assertExists()
        compose.onNodeWithText(text(R.string.app_lock_enable)).performScrollTo().assertIsNotEnabled()
        assertEquals(0,runBlocking{app.feedRepository.current()}.requestsToday)
    }
    private fun text(id:Int)=compose.activity.getString(id)
    private fun waitForTag(tag:String){compose.waitUntil(15000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun capture(name:String){
        compose.mainClock.advanceTimeBy(600);compose.waitForIdle();val window=compose.activity.window
        val b=Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888);var result=-1
        compose.runOnIdle{PixelCopy.request(window,b,{result=it},Handler(Looper.getMainLooper()))}
        compose.waitUntil(10000){result!=-1};assertEquals(PixelCopy.SUCCESS,result)
        File("build/outputs/screenshots/v092/$name.png").also{it.parentFile.mkdirs()}.outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }
}
