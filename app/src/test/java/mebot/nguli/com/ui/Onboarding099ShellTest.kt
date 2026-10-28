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
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Full Activity setup journey; connect/cancel/Back/rotation stay on the real shell. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Onboarding099ShellTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val app get() = compose.activity.application as FeedTestApplication

    @Test fun englishFirstRunUsesChatGptFirstButPublicFeedSkipIsDurable() = firstRun("en")

    @Test @Config(qualifiers = "sw-rTZ-w411dp-h891dp-xhdpi")
    fun swahiliFirstRunUsesTheSameThreeStepJourney() = firstRun("sw")

    @Test @Config(qualifiers = "sw-rTZ-w320dp-h800dp-xhdpi")
    fun narrowLargeTextKeepsConnectAndPublicSkipReachable() {
        RuntimeEnvironment.setFontScale(1.3f)
        resetFirstRun("sw")
        waitForTag("setup-099-onboarding")
        compose.onNodeWithTag("setup099-progress").assertExists()
        compose.onNodeWithTag("setup099-connect").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup099-continue-public").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("setup099-connect").performClick()
        waitForTag("connection-screen")
        compose.onNodeWithTag("setup099-progress").assertExists()
        compose.onNodeWithTag("chatgpt-sign-in").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection-continue").assertDoesNotExist()
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForTag("setup-099-onboarding")
        compose.onNodeWithTag("setup099-continue-public").performScrollTo().assertIsDisplayed()
        capture("setup-099-small-sw-large-text")
    }

    @Test fun existingProviderAndDataRemainPrimaryWhenOpeningConnectionSettings() {
        val context = ContextEntry(id = "existing-note", text = "Keep my saved context", category = "preference")
        val selected = AiConnection(mode = "claude", model = "existing-model")
        val item = FeedItem(id = "existing-item", moduleId = "news", title = "Saved item", summary = "Keep this story",
            sourceName = "Example publisher", sourceUrl = "https://example.com/story")
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.update { AppState(profile = Profile(onboardingComplete = true)) }
            app.feedRepository.update { FeedState(
                onboardingComplete = true,
                connection = selected,
                context = listOf(context),
                items = listOf(item),
                savedIds = setOf(item.id),
            ) }
        }
        waitForTag("feed-screen")
        compose.onNodeWithTag("nav-you").performClick()
        compose.onNodeWithText(text(R.string.feed_connection)).performScrollTo().performClick()
        waitForTag("connection-screen")
        compose.onNodeWithTag("connection-reconnect-provider-099").assertIsDisplayed()
        compose.onNodeWithTag("chatgpt-sign-in").assertDoesNotExist()
        assertEquals(Setup099Stage.INTRO, state().setup099.stage)
        assertEquals(selected, state().connection)
        assertEquals(listOf(context), state().context)
        assertEquals(setOf(item.id), state().savedIds)

        compose.onNodeWithTag("connection-reconnect-provider-099").performClick()
        compose.onNodeWithTag("chatgpt-sign-in-alternative-099").performScrollTo().assertIsDisplayed()
        assertEquals("Opening alternatives must not activate ChatGPT or rewrite saved data", selected, state().connection)
        assertEquals(listOf(context), state().context)
        assertEquals(setOf(item.id), state().savedIds)
    }

    private fun firstRun(language: String) {
        resetFirstRun(language)
        waitForTag("setup-099-onboarding")
        compose.onNodeWithTag("setup099-progress").assertExists()
        compose.onNodeWithTag("setup099-step-intro").assertExists()
        compose.onNodeWithTag("setup099-connect").assertIsDisplayed()
        compose.onNodeWithTag("setup099-continue-public").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.setup099_intro_title)).assertExists()
        capture("setup-099-intro-$language")

        val before = state()
        assertFalse(before.onboardingComplete)
        assertEquals(Setup099Stage.INTRO, before.setup099.stage)
        assertEquals(AiConnection(), before.connection)
        assertTrue(before.context.isEmpty())

        compose.onNodeWithTag("setup099-connect").performClick()
        waitForTag("connection-screen")
        compose.onNodeWithTag("chatgpt-sign-in").assertIsDisplayed()
        compose.onNodeWithTag("setup099-progress").assertExists()
        compose.waitUntil(10_000) { state().setup099.stage == Setup099Stage.CONNECT_CHATGPT }
        assertEquals("Choosing the primary action does not mutate provider state", AiConnection(), state().connection)
        compose.activityRule.scenario.recreate()
        waitForTag("connection-screen")
        assertEquals(Setup099Stage.CONNECT_CHATGPT, state().setup099.stage)
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        waitForTag("setup-099-onboarding")
        compose.onNodeWithTag("setup099-step-connect").assertExists()
        assertEquals(Setup099Stage.CONNECT_CHATGPT, state().setup099.stage)
        compose.activityRule.scenario.recreate()
        waitForTag("setup-099-onboarding")
        compose.onNodeWithTag("setup099-connect").assertIsDisplayed()
        compose.onNodeWithTag("setup099-continue-public").performScrollTo().performClick()
        waitForTag("feed-screen")
        compose.waitUntil(10_000) { state().onboardingComplete && state().setup099.stage == Setup099Stage.COMPLETE }
        assertEquals("Skipping setup keeps the configured provider unchanged", AiConnection(), state().connection)
        assertTrue(state().context.isEmpty())
    }

    private fun resetFirstRun(language: String) {
        compose.waitForIdle()
        runBlocking {
            app.feedEngine.disconnect()
            app.repository.erase()
            app.feedRepository.update {
                FeedState(
                    modules = ModuleCatalog.defaults.map { it.copy(enabled = it.kind == "news") },
                    personalSeedApplied = true,
                )
            }
            app.repository.update { AppState(settings = Settings(language = language)) }
        }
        app.alarms.clearAll()
    }

    private fun state() = runBlocking { app.feedRepository.current() }
    private fun text(resource: Int) = compose.activity.getString(resource)
    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val window = compose.activity.window
        val view = window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        var status = -1
        compose.runOnUiThread { PixelCopy.request(window, bitmap, { status = it }, Handler(Looper.getMainLooper())) }
        compose.waitUntil(10_000) { status != -1 }
        assertEquals(PixelCopy.SUCCESS, status)
        File("build/outputs/screenshots/099-onboarding/$name.png").also { it.parentFile!!.mkdirs() }
            .outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
