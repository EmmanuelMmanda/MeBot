package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.test.*
import androidx.compose.ui.unit.dp
import org.robolectric.RuntimeEnvironment
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.MainActivity
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

/** Full Activity onboarding at ordinary system font scale; never an isolated chooser mock. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w411dp-h891dp-xhdpi",application=FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Onboarding090ShellTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val app get()=compose.activity.application as FeedTestApplication

    @Test fun englishOnboardingShell() = journey("en")
    @Test @Config(qualifiers="sw-rTZ-w411dp-h891dp-xhdpi")
    fun swahiliOnboardingShell() = journey("sw")

    @Test @Config(qualifiers="sw-rTZ-w320dp-h800dp-xhdpi")
    fun swahiliLargeSystemTextKeepsFullShellReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        compose.activityRule.scenario.recreate()
        journey("sw", "sw-stress", true)
    }

    private fun journey(language:String, suffix:String = language, stress:Boolean = false) {
        compose.waitForIdle()
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.update { AppState(settings=Settings(language=language)) }
            app.feedRepository.update { FeedState(modules=ModuleCatalog.defaults.map { it.copy(enabled=it.kind=="news") },personalSeedApplied=true) }
        }
        waitForTag("onboarding-screen")
        compose.onNodeWithTag("onboarding-next").performClick()
        waitForTag("onboarding-step-1")
        capture("onboarding-$suffix-top")
        compose.onNodeWithTag("onboarding-interest-search").performScrollTo()
        compose.onNodeWithTag("onboarding-language").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-back").assertIsDisplayed()
        compose.onNodeWithTag("onboarding-next").assertIsDisplayed()
        capture("onboarding-$suffix-chooser")
        val news = compose.onNodeWithTag("onboarding-interest-news").performScrollTo().getUnclippedBoundsInRoot()
        val technology = compose.onNodeWithTag("onboarding-interest-technology").performScrollTo().getUnclippedBoundsInRoot()
        assertTrue(news.bottom-news.top >= 48.dp)
        assertTrue(technology.bottom-technology.top >= 48.dp)
        assertEquals(news.right-news.left,technology.right-technology.left)
        if (stress) assertEquals(news.left,technology.left) else assertTrue(technology.left >= news.right)
        compose.onNodeWithTag("onboarding-interest-news").assertIsOn()
        compose.onNodeWithTag("onboarding-interest-search").performScrollTo().performTextInput(if(language=="sw") "tekn" else "tech")
        compose.onNodeWithTag("onboarding-interest-technology").performScrollTo().performClick().assertIsSelected()
        compose.activityRule.scenario.recreate()
        waitForTag("onboarding-step-1")
        compose.onNodeWithTag("onboarding-interest-search").performScrollTo().assertTextContains(if(language=="sw") "tekn" else "tech")
        compose.onNodeWithTag("onboarding-interest-technology").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("onboarding-interest-search").performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("onboarding-interest-group-growth").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("onboarding-interest-learning").performScrollTo().performClick().assertIsSelected()
        compose.onNodeWithTag("onboarding-interest-group-all").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-interests-more").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-interest-food").performScrollTo().performClick().assertIsSelected()
        val note="Napenda kilimo na biashara ndogo"
        compose.onNodeWithTag("onboarding-preferences").performScrollTo().performTextReplacement(note)
        compose.onNodeWithTag("onboarding-preferences").performScrollTo().assertIsDisplayed()
        val field = compose.onNodeWithTag("onboarding-preferences").getUnclippedBoundsInRoot()
        val footer = compose.onNodeWithTag("onboarding-next").getUnclippedBoundsInRoot()
        assertTrue("Custom input must be fully above the pinned footer", field.bottom <= footer.top)
        capture("onboarding-$suffix-custom-text")
        if (!stress) {
            val portrait = RuntimeEnvironment.getQualifiers()
            RuntimeEnvironment.setQualifiers("w891dp-h411dp-land-xhdpi")
            compose.activityRule.scenario.recreate()
            waitForTag("onboarding-step-1")
            assertEquals(android.content.res.Configuration.ORIENTATION_LANDSCAPE, compose.activity.resources.configuration.orientation)
            compose.onNodeWithTag("onboarding-preferences").performScrollTo().assertTextContains(note)
            compose.onNodeWithTag("onboarding-back").assertIsDisplayed()
            compose.onNodeWithTag("onboarding-next").assertIsDisplayed()
            compose.onNodeWithTag("onboarding-start").assertIsDisplayed()
            capture("onboarding-$suffix-landscape")
            RuntimeEnvironment.setQualifiers(portrait)
            compose.activityRule.scenario.recreate()
            waitForTag("onboarding-step-1")
            compose.onNodeWithTag("onboarding-preferences").performScrollTo().assertTextContains(note)
        }
        compose.onNodeWithTag("onboarding-back").performClick()
        compose.onNodeWithTag("onboarding-next").performClick()
        compose.onNodeWithTag("onboarding-preferences").performScrollTo().assertTextContains(note)
        compose.onNodeWithTag("onboarding-language").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("onboarding-preferences").performScrollTo().assertTextContains(note)
        compose.onNodeWithTag("onboarding-start").performClick()
        waitForTag("feed-screen")
        val saved=runBlocking {app.feedRepository.current()}
        val interest=saved.context.single { it.id=="onboarding-interests" }
        assertEquals(note,interest.text)
        assertTrue(interest.scopes.containsAll(setOf("news","technology","learning","food")))
        assertFalse(interest.global)
        compose.activityRule.scenario.recreate();waitForTag("feed-screen")
        assertEquals(interest,runBlocking {app.feedRepository.current()}.context.single { it.id==interest.id })
    }
    private fun waitForTag(tag:String) { compose.waitUntil(15_000) {compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()} }
    private fun capture(name:String) {
        compose.mainClock.advanceTimeBy(600);compose.waitForIdle()
        val view=compose.activity.window.decorView
        val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
        var status=-1
        compose.runOnIdle {PixelCopy.request(compose.activity.window,bitmap,{status=it},Handler(Looper.getMainLooper()))}
        compose.waitUntil(10_000){status!=-1};assertEquals(PixelCopy.SUCCESS,status)
        val phase=System.getenv("MEBOT_ONBOARDING_PHASE") ?: "after"
        File("build/outputs/screenshots/v090-onboarding-$phase/$name.png").also{it.parentFile.mkdirs()}.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
    }
}
