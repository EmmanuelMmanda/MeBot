package app.mynguli.ui

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import app.mynguli.MainActivity
import app.mynguli.R
import app.mynguli.core.FeedItem
import app.mynguli.feed.FeedTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotFeedNotificationJourneyTest {
    private val activityRule = ActivityScenarioRule<MainActivity>(Intent(
        ApplicationProvider.getApplicationContext<FeedTestApplication>(), MainActivity::class.java,
    ).setAction("app.mynguli.OPEN_FEED").putExtra("item_id", "first-notification"))

    @get:Rule val compose = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>(activityRule) { rule ->
        lateinit var activity: MainActivity
        rule.scenario.onActivity { activity = it }
        activity
    }

    @Test fun notificationOpensTheSpecificUpdateAndSurvivesRecreationWithGracefulMissingContent() {
        compose.waitForIdle()
        val app = compose.activity.application as FeedTestApplication
        runBlocking {
            app.repository.erase()
            app.feedRepository.erase()
            app.repository.update { it.copy(profile = it.profile.copy(onboardingComplete = true)) }
            app.feedRepository.update { it.copy(onboardingComplete = true, items = listOf(article("first-notification"), article("second-notification"))) }
        }
        waitForText("Synthetic first-notification")
        compose.onNodeWithTag("detail-save").assertExists()
        compose.activityRule.scenario.recreate()
        waitForText("Synthetic first-notification")
        compose.onNodeWithTag("detail-save").assertExists()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
        waitForTag("feed-screen")

        sendNotification("second-notification")
        waitForText("Synthetic second-notification")
        waitForTag("detail-save")
        compose.onNodeWithTag("detail-save").performScrollTo().performClick()
        try {
            compose.waitUntil(15_000) {
                // ActivityScenario synchronization drains queued ViewModel work
                // before reading the DataStore from Robolectric's main thread.
                val currentApp = compose.activity.application as FeedTestApplication
                runBlocking { "second-notification" in currentApp.feedRepository.current().savedIds }
            }
        } catch (failure: ComposeTimeoutException) {
            val currentApp = compose.activity.application as FeedTestApplication
            throw AssertionError("Saved IDs: ${runBlocking { app.feedRepository.current().savedIds }}; same Application: ${app === currentApp}; current saved IDs: ${runBlocking { currentApp.feedRepository.current().savedIds }}\n${compose.onRoot().printToString()}", failure)
        }
        compose.activityRule.scenario.recreate()
        waitForText("Synthetic second-notification")
        compose.onNodeWithTag("detail-save").assertExists()

        sendNotification("removed-update")
        waitForText(compose.activity.getString(R.string.feed_unavailable_title))
        compose.onNodeWithTag("detail-save").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(R.string.feed_back_feed)).performScrollTo().performClick()
        waitForTag("feed-screen")
        compose.onNodeWithTag("nav-saved").performClick()
        compose.onNodeWithTag("item-second-notification").performScrollTo().assertIsDisplayed()
    }

    private fun sendNotification(id: String) = compose.activityRule.scenario.onActivity { activity ->
        activity.onNewIntent(Intent(activity, MainActivity::class.java).setAction("app.mynguli.OPEN_FEED").putExtra("item_id", id))
    }
    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun article(id: String) = FeedItem(id, "news", "Synthetic $id", "Test-only notification content.", sourceUrl = "https://example.com/$id")
}
