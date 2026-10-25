package mebot.nguli.com.ui

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import mebot.nguli.com.MainActivity
import mebot.nguli.com.MeBotApplication
import mebot.nguli.com.R
import mebot.nguli.com.feed.FeedTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MeBotNavigationJourneyTest {
    // ActivityScenario matches lifecycle events against its original Intent.
    // Launch with the same action used by subsequent system alarm shortcuts so
    // it can track recreation after MainActivity correctly updates its Intent.
    private val activityRule = ActivityScenarioRule<MainActivity>(Intent(
        ApplicationProvider.getApplicationContext<MeBotApplication>(), MainActivity::class.java,
    ).setAction("mebot.nguli.com.OPEN_ALARMS"))

    @get:Rule
    val compose = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>(activityRule) { rule ->
        lateinit var activity: MainActivity
        rule.scenario.onActivity { activity = it }
        activity
    }

    @Test
    fun systemAlarmShortcutOpensListAgainWithoutOverridingEditorOnRotation() {
        compose.waitForIdle()
        val application = compose.activity.application as MeBotApplication
        runBlocking { application.repository.erase(); application.feedRepository.erase() }
        application.alarms.clearAll()
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        waitForTag("onboarding-screen")
        compose.onNodeWithTag("onboarding-start").performScrollTo().performClick()
        // The initial system shortcut waits for onboarding, then opens Alarms.
        waitForText(R.string.mebot_empty_alarm)
        compose.onNodeWithContentDescription(text(R.string.back)).performClick()
        waitForTag("nav-feed")
        compose.onNodeWithTag("nav-feed").performClick()
        waitForTag("feed-screen")

        sendSystemShortcut()
        waitForText(R.string.mebot_empty_alarm)
        compose.onNodeWithTag("alarm-create").performClick()
        waitForText(R.string.mebot_time_title)
        compose.onNodeWithText(text(R.string.mebot_medium)).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(text(R.string.mebot_medium)).assertIsSelected()
        compose.activityRule.scenario.recreate()
        waitForText(R.string.mebot_time_title)
        compose.onNodeWithTag("alarm-create").assertDoesNotExist()
        compose.onNodeWithTag("alarm-mode-math").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mebot_medium)).assertIsSelected()

        sendSystemShortcut()
        waitForText(R.string.mebot_empty_alarm)
        compose.onNodeWithTag("alarm-create").assertIsDisplayed()
        compose.onNodeWithTag("alarm-save").assertDoesNotExist()
        assertTrue(runBlocking { application.repository.current().alarms.isEmpty() })
    }

    private fun sendSystemShortcut() = compose.activityRule.scenario.onActivity { activity ->
        activity.onNewIntent(Intent(activity, MainActivity::class.java).setAction("mebot.nguli.com.OPEN_ALARMS"))
    }
    private fun text(resource: Int) = compose.activity.getString(resource)
    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitForText(resource: Int) {
        val value = text(resource)
        compose.waitUntil(15_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
}
