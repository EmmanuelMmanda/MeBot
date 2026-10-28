package mebot.nguli.com.ui.feed

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import mebot.nguli.com.core.ContextEntry
import mebot.nguli.com.core.FeedState
import mebot.nguli.com.ui.MeBotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Setup099VoiceTipTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actualVoiceSetupNeedShowsOptionalNoticeAndRoutesOnlyAfterExplicitTap() {
        var routeRequests = 0
        val dismissed = mutableListOf<String>()
        compose.setContent {
            MeBotTheme {
                CompositionLocalProvider(
                    LocalSetup099TipHostActions provides Setup099TipHostActions(
                        voiceSetupNeeded = true,
                        onVoiceSetup = { routeRequests++ },
                    ),
                ) {
                    SetupNotice(
                        state = FeedState(
                            onboardingComplete = true,
                            context = listOf(ContextEntry(id = "confirmed", text = "A confirmed preference")),
                            backgroundEnabled = true,
                        ),
                        connected = true,
                        page = "feed",
                        onConnect = {},
                        onContext = {},
                        onDismiss = { dismissed += it },
                    )
                }
            }
        }

        compose.onNodeWithTag("setup-notice-voice_setup").assertIsDisplayed()
        compose.onNodeWithText(
            "Live voice needs a separately configured service. ChatGPT sign-in does not enable OpenAI API voice; " +
                "access and usage may be billed separately. This setup tip does not start a call."
        ).assertIsDisplayed()
        assertEquals("No voice setup or call begins just because the notice is shown", 0, routeRequests)
        compose.onNodeWithTag("setup-tip-action-voice_setup").performClick()
        assertEquals(1, routeRequests)
        compose.onNodeWithTag("setup-tip-later-voice_setup").performClick()
        assertEquals(listOf("voice_setup"), dismissed)
    }

    @Test fun defaultHostDoesNotIntroduceVoiceSetupNotice() {
        compose.setContent {
            MeBotTheme {
                SetupNotice(
                    state = FeedState(
                        onboardingComplete = true,
                        context = listOf(ContextEntry(id = "confirmed", text = "A confirmed preference")),
                        backgroundEnabled = true,
                    ),
                    connected = true,
                    page = "feed",
                    onConnect = {},
                    onContext = {},
                    onDismiss = {},
                )
            }
        }

        assertTrue(compose.onAllNodesWithTag("setup-notice-voice_setup").fetchSemanticsNodes().isEmpty())
    }
}
