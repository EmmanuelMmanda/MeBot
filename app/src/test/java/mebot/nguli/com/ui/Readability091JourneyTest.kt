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
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual Activity + persistence; synthetic weather/news and no provider credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi",application=FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Readability091JourneyTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as FeedTestApplication
    @Test fun englishDatesSettingsAndReadAloud()=journey("en")
    @Test @Config(qualifiers="sw-rTZ-w411dp-h891dp-xhdpi")
    fun swahiliDatesSettingsAndReadAloud()=journey("sw")
    @Test @Config(qualifiers="sw-rTZ-w320dp-h800dp-xhdpi")
    fun narrowLargeTextKeepsActionsReachable() {
        RuntimeEnvironment.setFontScale(1.3f);compose.activityRule.scenario.recreate();journey("sw","sw-stress")
    }
    private fun journey(language:String,suffix:String=language) {
        compose.waitForIdle()
        val now=System.currentTimeMillis()
        val forecast=FeedItem(id="weather-091",moduleId="weather",kind="weather",contentType="weather",title="Forecast fixture",summary="Synthetic forecast for layout testing",
            sourceName="Example forecast",sourceUrl="https://example.com/weather",fetchedAtMillis=now,
            weather=FeedWeather(29.0,30.0,20,1,"Dar es Salaam"),
            temporal=FeedTemporal(TemporalProvenance.FORECAST_PROVIDER,verifiedAtMillis=now-3_600_000,validFromMillis=now-3_600_000,validUntilMillis=now+3_600_000))
        val story=FeedItem(id="news-091",moduleId="news",kind="news",title="Local market update — illustrative fixture",summary="Synthetic reading fixture",
            sourceName="Example publisher",sourceUrl="https://example.com/story",fetchedAtMillis=now,publishedAtMillis=now,
            temporal=FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=now))
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.update{AppState(profile=Profile(onboardingComplete=true),settings=Settings(language=language))}
            app.feedRepository.update{FeedState(onboardingComplete=true,quickGuideSeen=true,interestsConfigured=true,timezone="Africa/Dar_es_Salaam",
                lastRefreshMillis=now,modules=listOf(FeedModule(id="weather",kind="weather"),FeedModule(id="news",kind="news")),items=listOf(forecast,story))}
        }
        waitForTag("feed-screen")
        compose.onNodeWithTag("filter-today").performScrollTo().performClick()
        // Exercise the real optional-notice dismissal, never fake a connected account for a capture.
        compose.onNodeWithTag("setup-tip-later-missing_ai").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            runBlocking { app.feedRepository.current() }.dismissedNotices["missing_ai"] != null &&
                compose.onAllNodesWithTag("setup-notice-missing_ai").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("setup-notice-missing_ai").assertDoesNotExist()
        if (language == "en") {
            compose.activityRule.scenario.recreate()
            waitForTag("feed-screen")
            compose.onNodeWithTag("filter-today").assertIsSelected()
            compose.onNodeWithTag("setup-notice-missing_ai").assertDoesNotExist()
            assertNotNull(runBlocking { app.feedRepository.current() }.dismissedNotices["missing_ai"])
        }
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("weather-${forecast.id}"))
        compose.onAllNodesWithTag("today-time-${forecast.id}").assertCountEquals(1)
        val forecastPrefix=compose.activity.getString(R.string.weather_range_091).substringBefore("%")
        compose.onAllNodesWithText(forecastPrefix,substring=true).assertCountEquals(1)
        compose.onNodeWithTag("weather-${forecast.id}").assertIsDisplayed()
        capture("today-weather-$suffix")
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("star-${story.id}"))
        compose.onNodeWithTag("star-${story.id}").assertIsDisplayed()
        compose.onNodeWithTag("save-${story.id}").assertIsDisplayed()
        if (suffix == language) capture("today-weather-$suffix")
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("today-why-${forecast.id}"))
        compose.onNodeWithTag("today-why-${forecast.id}").performClick()
        compose.onNodeWithText("Africa/Dar_es_Salaam",substring=true).assertExists()
        capture("date-details-$suffix", dialog=true)
        compose.onNodeWithText(compose.activity.getString(android.R.string.ok)).performClick()
        compose.onNodeWithTag("nav-you").performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.starred_summary_092)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.personalization_summary_092)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(compose.activity.getString(R.string.settings_data_summary_091)).performScrollTo().assertIsDisplayed()
        capture("settings-$suffix")
        compose.onNodeWithTag("nav-ai").performClick()
        waitForTag("workspace-voice-options")
        capture("workspace-$suffix")
        compose.onNodeWithTag("workspace-voice-options").assertIsDisplayed().assertContentDescriptionEquals(compose.activity.getString(R.string.rich_voice_settings)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.rich_voice_help)).assertExists()
        compose.onNodeWithTag("workspace-speech-rate").assertExists()
        capture("read-aloud-$suffix", dialog=true)
        assertEquals(0,runBlocking{app.feedRepository.current()}.requestsToday)
    }
    private fun waitForTag(tag:String){compose.waitUntil(15_000){compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()}}
    private fun capture(name:String, dialog:Boolean=false){
        compose.mainClock.advanceTimeBy(600);compose.waitForIdle()
        val window=if(dialog) checkNotNull(ShadowDialog.getLatestDialog().window) else compose.activity.window
        val view=window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);var status=-1
        compose.runOnIdle{PixelCopy.request(window,bitmap,{status=it},Handler(Looper.getMainLooper()))}
        compose.waitUntil(10_000){status!=-1};assertEquals(PixelCopy.SUCCESS,status)
        File("build/outputs/screenshots/v091/$name.png").also{it.parentFile.mkdirs()}.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
}
