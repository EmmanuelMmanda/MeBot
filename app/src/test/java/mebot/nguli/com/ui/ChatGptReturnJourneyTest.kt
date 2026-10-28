package mebot.nguli.com.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import mebot.nguli.com.MainActivity
import mebot.nguli.com.R
import mebot.nguli.com.feed.FeedTestApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi", application = FeedTestApplication::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatGptReturnJourneyTest {
    // ActivityScenario matches lifecycle callbacks against its original Intent's action,
    // component and categories. Keep the browser's VIEW/BROWSABLE shape for both entries.
    // A separate manifest-resolution test covers the component-free browser intent.
    private val activityRule = ActivityScenarioRule<MainActivity>(returnIntent()
        .putExtra("code", "untrusted-cold-code").putExtra("access_token", "untrusted-cold-token"))

    @get:Rule val compose = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>(activityRule) { rule ->
        lateinit var activity: MainActivity
        rule.scenario.onActivity { activity = it }
        activity
    }
    private val app get() = compose.activity.application as FeedTestApplication

    @Test fun browserReturnIntentsOnlyNavigateAndNeverAcceptCredentialsAcrossRecreation() {
        compose.waitForIdle()
        assertDisconnectedCredentials()
        runBlocking {
            app.repository.update { it.copy(profile = it.profile.copy(onboardingComplete = true)) }
            app.feedRepository.update { it.copy(onboardingComplete = true) }
        }
        assertDisconnectedConnection(chatGptPrimary = false)
        runBlocking {
            app.feedEngine.clearAllCredentials()
            app.repository.erase()
            app.feedRepository.erase()
            app.repository.update { it.copy(profile = it.profile.copy(onboardingComplete = true)) }
            app.feedRepository.update { it.copy(onboardingComplete = true) }
        }
        app.alarms.clearAll()
        assertDisconnectedConnection(chatGptPrimary = false)
        activityRule.scenario.recreate()
        assertDisconnectedConnection(chatGptPrimary = false)
        assertEquals("untrusted-cold-code", compose.activity.intent.getStringExtra("code"))
        assertEquals("untrusted-cold-token", compose.activity.intent.getStringExtra("access_token"))

        compose.onNodeWithContentDescription(compose.activity.getString(R.string.back)).performClick()
        waitForTag("feed-screen")
        compose.onNodeWithTag("nav-saved").performClick()
        activityRule.scenario.onActivity { activity ->
            activity.onNewIntent(returnIntent()
                .putExtra("code", "untrusted-warm-code").putExtra("access_token", "untrusted-warm-token"))
        }
        assertDisconnectedConnection(chatGptPrimary = false)
        assertEquals("untrusted-warm-code", compose.activity.intent.getStringExtra("code"))
        assertEquals("untrusted-warm-token", compose.activity.intent.getStringExtra("access_token"))
        activityRule.scenario.recreate()
        assertDisconnectedConnection(chatGptPrimary = false)
        // Robolectric's controller recreates from its original launch Intent, even after
        // direct onNewIntent delivery. Malicious extras are still present and stay ignored.
        assertEquals("untrusted-cold-code", compose.activity.intent.getStringExtra("code"))
        assertEquals("untrusted-cold-token", compose.activity.intent.getStringExtra("access_token"))
    }

    private fun assertDisconnectedConnection(chatGptPrimary: Boolean) {
        waitForTag("connection-screen")
        if (chatGptPrimary) {
            compose.onNodeWithTag("chatgpt-sign-in").assertIsDisplayed()
        } else {
            compose.onNodeWithTag("connection-reconnect-provider-099").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("chatgpt-sign-in").assertDoesNotExist()
        }
        compose.onNodeWithTag("connection-key").assertDoesNotExist()
        assertDisconnectedCredentials()
    }
    private fun assertDisconnectedCredentials() {
        assertNull(app.feedSecrets.read())
        assertTrue(app.chatGptAuth.state.value.accounts.none { it.connected })
        val state = runBlocking { app.feedRepository.current() }
        assertEquals("direct", state.connection.mode)
        assertTrue(state.connection.accountId.isEmpty())
    }
    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    companion object {
        private fun returnIntent(): Intent {
            val context = ApplicationProvider.getApplicationContext<FeedTestApplication>()
            return Intent(Intent.ACTION_VIEW, Uri.parse("${context.packageName}.connect://chatgpt/return"))
                .setClass(context, MainActivity::class.java).addCategory(Intent.CATEGORY_BROWSABLE)
        }
    }
}
