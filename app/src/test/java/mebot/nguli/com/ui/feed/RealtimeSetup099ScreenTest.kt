package mebot.nguli.com.ui.feed

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import mebot.nguli.com.R
import mebot.nguli.com.realtime.RealtimeCapability
import mebot.nguli.com.realtime.RealtimeServerCapability
import mebot.nguli.com.realtime.RecordingTranscriptionCapability
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
class RealtimeSetup099ScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun initialScreenDoesNotCheckServiceAndSaveThenTestAreSeparateExplicitActions() {
        var ui by mutableStateOf(RealtimeSetup099UiState())
        val saved = mutableListOf<Pair<String, String?>>()
        var tests = 0
        var removes = 0
        compose.setContent {
            MeBotTheme {
                RealtimeSetup099Screen(
                    state = ui,
                    onSave = { url, token ->
                        saved += url to token
                        ui = ui.copy(savedServiceUrl = url, tokenConfigured = true, capability = null)
                    },
                    onTest = {
                        tests++
                        ui = ui.copy(capability = RealtimeServerCapability(
                            RealtimeCapability.Unavailable(RealtimeCapability.Reason.TRANSPORT_NOT_AVAILABLE),
                            RecordingTranscriptionCapability.Unavailable(RecordingTranscriptionCapability.Reason.TRANSCRIPTION_NOT_CONFIGURED),
                        ))
                    },
                    onRemove = { removes++ },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("realtime-setup-text-separation").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Your ChatGPT sign-in remains unchanged and is used for supported text features. It does not grant OpenAI API Realtime access or pay for API voice usage.")
            .performScrollTo().assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag("realtime-test").fetchSemanticsNodes().isEmpty())
        assertEquals(0, tests)
        assertEquals(0, saved.size)
        assertEquals(0, removes)

        compose.onNodeWithTag("realtime-service-url").performScrollTo().performTextInput("https://voice.example.com/")
        compose.onNodeWithTag("realtime-service-token").performScrollTo().performTextInput("synthetic-service-token-12345678901234567890")
        compose.onNodeWithTag("realtime-save").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://voice.example.com" to "synthetic-service-token-12345678901234567890"), saved)
        assertEquals("Saving only persists the URL/token; it never runs a capability request", 0, tests)

        compose.onNodeWithTag("realtime-test").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(1, tests)
        compose.onNodeWithTag("realtime-test-status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The live voice-call transport is not available yet. No call was started.").assertIsDisplayed()
        assertEquals("A status check must not start a voice call", 0, removes)
    }

    @Test fun changedServiceUrlRequiresNewTokenAndRemovalRequiresConfirmation() {
        var ui by mutableStateOf(RealtimeSetup099UiState(
            savedServiceUrl = "https://voice.example.com",
            tokenConfigured = true,
            capability = RealtimeServerCapability(
                RealtimeCapability.Available,
                RecordingTranscriptionCapability.Unavailable(RecordingTranscriptionCapability.Reason.TRANSCRIPTION_NOT_CONFIGURED),
            ),
        ))
        val saved = mutableListOf<Pair<String, String?>>()
        var tests = 0
        var removes = 0
        compose.setContent {
            MeBotTheme {
                RealtimeSetup099Screen(
                    state = ui,
                    onSave = { url, token -> saved += url to token },
                    onTest = { tests++ },
                    onRemove = { removes++ },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithTag("realtime-service-token").assertExists()
        // The saved token stays out of UI state; the supporting copy below the field communicates
        // that it is stored and can be retained for this same URL. TextField placeholder decoration
        // is not exposed as a standalone Compose text node, so assert the user-visible helper.
        compose.onNodeWithText(
            "A service token is stored. Leave blank to keep it for this same URL, or enter a replacement."
        ).assertExists()
        compose.onNodeWithTag("realtime-test").assertIsEnabled()
        compose.onNodeWithTag("realtime-test-status").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("The service reports that live voice is available. This is a status result only; no call was started.").assertExists()

        compose.onNodeWithTag("realtime-service-url").performScrollTo().performTextReplacement("https://voice.example.com/v2")
        compose.onNodeWithTag("realtime-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("realtime-test").assertIsNotEnabled()
        assertTrue("An edited URL hides stale capability status until saved and checked again",
            compose.onAllNodesWithTag("realtime-test-status").fetchSemanticsNodes().isEmpty())
        val replacementToken = "replacement-service-token-12345678901234567890"
        compose.onNodeWithTag("realtime-service-token").performScrollTo().performTextInput(replacementToken)
        val enteredUrl = compose.onNodeWithTag("realtime-service-url").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        val enteredTokenLength = compose.onNodeWithTag("realtime-service-token").fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text.length
        assertEquals("Editing the service URL retains the valid HTTPS path", "https://voice.example.com/v2", enteredUrl)
        assertEquals("The masked field accepts the full replacement token without exposing its value",
            replacementToken.length, enteredTokenLength)
        assertTrue(compose.onAllNodesWithTag("realtime-setup-error-https").fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodesWithTag("realtime-setup-error-token").fetchSemanticsNodes().isEmpty())
        compose.onNodeWithTag("realtime-save").performScrollTo().assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("https://voice.example.com/v2" to replacementToken), saved)
        assertEquals(0, tests)

        compose.onNodeWithTag("realtime-remove").performScrollTo().performClick()
        compose.onNodeWithTag("realtime-remove-confirm-dialog").assertIsDisplayed()
        assertEquals("Remove is explicit and confirmed", 0, removes)
        compose.onNodeWithTag("realtime-remove-confirm").performClick()
        compose.waitForIdle()
        assertEquals(1, removes)
        compose.onNodeWithTag("realtime-setup-text-separation").performScrollTo().assertIsDisplayed()
    }

    @Test fun savingSameUrlWithBlankSecretRequestsKeepingTheEncryptedToken() {
        val saves = mutableListOf<Pair<String, String?>>()
        compose.setContent {
            MeBotTheme {
                RealtimeSetup099Screen(
                    state = RealtimeSetup099UiState(savedServiceUrl = "https://voice.example.com", tokenConfigured = true),
                    onSave = { url, token -> saves += url to token },
                    onTest = {}, onRemove = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithTag("realtime-save").performScrollTo().assertIsEnabled().performClick()
        assertEquals(listOf("https://voice.example.com" to null), saves)
    }

    @Test fun nonHttpsAndMalformedTokenAreNotSavable() {
        var saves = 0
        compose.setContent {
            MeBotTheme {
                RealtimeSetup099Screen(
                    state = RealtimeSetup099UiState(),
                    onSave = { _, _ -> saves++ },
                    onTest = {}, onRemove = {}, onBack = {},
                )
            }
        }
        compose.onNodeWithTag("realtime-service-url").performScrollTo().performTextInput("http://voice.example.com")
        compose.onNodeWithTag("realtime-service-token").performScrollTo().performTextInput("short")
        compose.onNodeWithTag("realtime-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("realtime-setup-error-https", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("realtime-setup-error-token", useUnmergedTree = true).assertExists()
        assertEquals(0, saves)
    }
}
