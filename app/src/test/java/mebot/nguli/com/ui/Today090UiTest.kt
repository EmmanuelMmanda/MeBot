package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.ui.feed.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Today090UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = Instant.parse("2026-10-25T12:00:00Z").toEpochMilli()
    private fun item(id: String, date: Long, verified: Boolean) = FeedItem(id=id,moduleId="news",kind="news",
        title=if(id=="current") "Local market report — illustrative reading fixture" else "Earlier reading — illustrative fixture",
        summary="Synthetic content for native layout verification.",sourceName="Example publisher",sourceUrl="https://example.com/$id",
        publishedAtMillis=date,fetchedAtMillis=now,temporal=if(verified) FeedTemporal(TemporalProvenance.PUBLISHER,publishedAtMillis=date) else null)
    private val state = FeedState(timezone="Africa/Dar_es_Salaam",onboardingComplete=true,interestsConfigured=true,
        modules=listOf(FeedModule(id="news",kind="news")),items=listOf(item("current",now-3_600_000,true),item("old",now-86_400_000,true),item("unknown",now,false)))

    @Test fun cachedTodayPreservesIdsRestoresFilterAndRollsOverWithoutRequests() = todayJourney("en", 1f)

    @Test @Config(qualifiers="sw-rTZ-w320dp-h800dp-xhdpi")
    fun todayAtLargeSwahiliTextKeepsOfflineAndRolloverStateHonest() = todayJourney("sw", 1.3f)

    private fun todayJourney(language: String, fontScale: Float) {
        var requests=0;var opened=""
        val clock=mutableStateOf(now)
        val restoration=StateRestorationTester(compose)
        restoration.setContent { MeBotTheme(Settings(language=language)) { CompositionLocalProvider(LocalFeedClock provides clock.value,LocalPublisherImageLoader provides {null},
            LocalDensity provides Density(LocalDensity.current.density,fontScale)) {
            Surface { FeedHome(FeedUiState(state=state),{opened=it},{},{},{},{},{requests++},{},{},{},null,{},onLoadMore={requests++}) }
        } } }
        compose.onNodeWithTag("filter-today").performScrollTo().performClick()
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("item-current"))
        compose.onNodeWithTag("item-current").assertIsDisplayed().performClick()
        assertEquals("current",opened)
        compose.onNodeWithTag("item-old").assertDoesNotExist()
        compose.onNodeWithTag("item-unknown").assertDoesNotExist()
        capture("today-$language")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("filter-today"))
        compose.onNodeWithTag("filter-today").assertIsSelected()
        compose.runOnIdle { clock.value=Instant.parse("2026-10-25T21:00:00Z").toEpochMilli() }
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasText(compose.activity.getString(R.string.today_090_empty)))
        compose.onNodeWithText(compose.activity.getString(R.string.today_090_empty)).assertIsDisplayed()
        compose.onNodeWithTag("item-current").assertDoesNotExist()
        capture("today-empty-$language")
        val returnLabel = compose.activity.getString(R.string.today_090_regular_feed)
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasText(returnLabel))
        compose.onNodeWithText(returnLabel).assertIsDisplayed().performClick()
        compose.onNodeWithTag("feed-screen").performScrollToNode(hasTestTag("filter-all"))
        compose.onNodeWithTag("filter-all").assertIsSelected()
        assertEquals(0,requests)
    }

    @Test fun temporalLabelsUseSourceEvidenceNotLegacyOrRefreshedFetchTime() {
        val updated = item("updated", Instant.parse("2030-01-01T00:00:00Z").toEpochMilli(), false).copy(
            temporal = FeedTemporal(TemporalProvenance.PUBLISHER, publishedAtMillis = now - 86_400_000,
                substantiveUpdatedAtMillis = now))
        val forecast = item("forecast", 0, false).copy(kind="weather", temporal = FeedTemporal(
            TemporalProvenance.FORECAST_PROVIDER, validFromMillis=now-3_600_000, validUntilMillis=now+3_600_000,
            verifiedAtMillis=now-3*3_600_000))
        compose.setContent { MeBotTheme { CompositionLocalProvider(LocalFeedClock provides now) {
            Surface { Column {
                androidx.compose.material3.Text(todayPublication090(TodayEntry(updated,TodayReason.UPDATED),"UTC"))
                TodayReasonLabel090(TodayEntry(forecast,TodayReason.FORECAST),"UTC")
            } }
        } } }
        compose.onNodeWithText("2030",substring=true).assertDoesNotExist()
        assertTrue(compose.onAllNodesWithText("2026",substring=true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("today-why-forecast").performClick()
        compose.onNodeWithText("9:00",substring=true).assertExists()
    }

    @Test fun unknownWeatherTimeIsNotReplacedByTheFetchTime() {
        val forecast = item("unknown-weather", 0, false).copy(kind="weather", contentType="weather", fetchedAtMillis=now,
            weather=FeedWeather(28.0,29.0,20,1,"Example location"))
        compose.setContent { MeBotTheme { CompositionLocalProvider(LocalFeedClock provides now) {
            WeatherStrip(forecast,"UTC") {}
        } } }
        compose.onNodeWithText(compose.activity.getString(R.string.weather_unknown_time_091)).assertExists()
        compose.onNodeWithText(compose.activity.getString(R.string.feed_just_now),substring=true).assertDoesNotExist()
    }

    @Test fun compactInterestIconsInEnglish() = interestJourney("en", 1f, "interests-en")

    @Test @Config(qualifiers="sw-rTZ-w320dp-h800dp-xhdpi")
    fun compactInterestIconsRemainSelectableAndRestoreAtLargeSwahiliText() = interestJourney("sw", 1.3f, "interests-small-sw-large")

    private fun interestJourney(language: String, fontScale: Float, name: String) {
        val restoration=StateRestorationTester(compose)
        restoration.setContent { MeBotTheme(Settings(language=language)) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,fontScale)) {
                var selected by rememberSaveable { mutableStateOf(arrayListOf("news")) }
                Surface { Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                    InterestPicker083(selected,true) {selected=ArrayList(it)}
                } }
            }
        } }
        compose.onNodeWithTag("onboarding-interest-technology").performScrollTo().performClick().assertIsSelected()
        val size=compose.onNodeWithTag("onboarding-interest-technology").getUnclippedBoundsInRoot()
        assertTrue(size.bottom - size.top >= 48.dp)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding-interest-technology").performScrollTo().assertIsSelected()
        capture(name)
    }

    private fun capture(name:String) {
        compose.mainClock.advanceTimeBy(600);compose.waitForIdle()
        val bitmap=compose.runOnIdle {
            val window=compose.activity.window;window.decorView.invalidate()
            Bitmap.createBitmap(window.decorView.width,window.decorView.height,Bitmap.Config.ARGB_8888).also {
                var status:Int?=null;PixelCopy.request(window,it,{value->status=value},Handler(Looper.getMainLooper()));assertEquals(PixelCopy.SUCCESS,status)
            }
        }
        File("build/outputs/screenshots/v090/$name.png").also { it.parentFile!!.mkdirs() }.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
    }
}
