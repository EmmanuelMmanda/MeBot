package mebot.nguli.com.ui

import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import mebot.nguli.com.core.AiConversation
import mebot.nguli.com.core.AiTurn
import mebot.nguli.com.core.FeedState
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.realtime.RealtimeCapability
import mebot.nguli.com.ui.feed.AiWorkspace
import mebot.nguli.com.ui.feed.RichResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceResponse099UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val markdown = """# A readable response

        A selectable sentence with **bold emphasis**, [Open guide](https://example.org/read), and `inline code`.

        - First item
        - Second item

        | Choice | Cost |
        | --- | ---: |
        | Alpha | Free |

        ```kotlin
        println("a full code example")
        ```
    """.trimIndent()

    @Test fun richMarkdownKeepsSelectableTextTableAndPerBlockCodeCopy() {
        compose.setContent { MaterialTheme { RichResponse(markdown, Modifier.testTag("rich-answer")) } }
        compose.onNodeWithText("A readable response").assertIsDisplayed()
        compose.onNodeWithText("First item").assertIsDisplayed()
        // Annotated link text is represented both in the paragraph and its clickable link node.
        compose.onAllNodesWithText("Open guide", substring = true).assertCountEquals(2)
        compose.onNodeWithText("Table").assertExists()
        compose.onNodeWithText("Choice").assertExists()
        compose.onNodeWithTag("rich-code-5").assertExists()
        compose.onNodeWithTag("rich-code-copy-5").performClick()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        assertEquals("        println(\"a full code example\")", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    /**
     * Exercise selection with a real long-press and the actual Compose Copy menu item. Compose's API-28+ magnifier
     * calls Android Magnifier; Robolectric's Surface is null when it is dismissed, throwing
     * `Magnifier$InternalPopupWindow.destroy(): Surface.destroy()` instead of completing the test.
     * API 27 avoids only that unavailable harness feature; SDK 35 rendering/link/code-copy remains above.
     * Real-device API-35 text selection is not claimed as verified by this Robolectric test.
     */
    @Test
    @Config(sdk = [27])
    fun richResponseProseCanBeSelectedAndCopiedByLongPress() {
        val selectableText = "Selectable prose can be copied after a long press."
        val menuProvider = RecordingTextContextMenuProvider()
        compose.setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides menuProvider) {
                    // Keep this a single plain prose block: links expose their own click target,
                    // which is a different pointer gesture from text-range selection.
                    RichResponse(selectableText, Modifier.testTag("rich-answer"))
                }
            }
        }
        val prose = compose.onNodeWithText(selectableText)
        var glyphCenter = Offset.Zero
        prose.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue("Selectable prose should expose its text layout", action(layouts))
            assertTrue(layouts.isNotEmpty())
            glyphCenter = layouts.first().getBoundingBox(selectableText.indexOf("prose") + 1).center
        }
        // Target a real glyph, not the full-width text node's center (which can be whitespace).
        prose.performTouchInput { longClick(glyphCenter) }
        compose.runOnIdle {
            val copy = menuProvider.copyItem
            assertTrue("SelectionManager should publish a real Copy menu item after the long-press", copy != null)
            copy!!.onClick.invoke(NoopTextContextMenuSession)
        }
        val copied = compose.activity.getSystemService(ClipboardManager::class.java)
            .primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
        assertTrue("The real selection gesture should copy selected prose", copied.isNotBlank())
        assertTrue(
            "Clipboard text should come from the selectable prose block, not another answer block",
            selectableText.contains(copied.trim()),
        )
    }

    @Test fun answerActionsCopyAndShareTheCompleteMarkdownAnswer() {
        val conversation = AiConversation(id = "answer-thread", turns = listOf(
            AiTurn(id = "question", role = "user", text = "Could you explain this?"),
            AiTurn(id = "answer", role = "assistant", text = "The full **answer** remains copyable and shareable."),
        ))
        val ui = mutableStateOf(FeedUiState(
            state = FeedState(onboardingComplete = true, conversations = listOf(conversation)),
            connected = true,
            loading = false,
            activeConversationId = conversation.id,
        ))
        compose.setContent {
            MaterialTheme {
                AiWorkspace(ui.value, {}, {}, {}, {}, {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("turn-copy-answer").performClick()
        val clipboard = compose.activity.getSystemService(ClipboardManager::class.java)
        assertEquals("The full **answer** remains copyable and shareable.", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
        compose.onNodeWithTag("turn-share-answer").performClick()
        assertTrue(shadowOf(compose.activity).nextStartedActivity.action == android.content.Intent.ACTION_CHOOSER)
        compose.onNodeWithTag("turn-read-answer").assertExists()
    }

    @Test fun unavailableAudioHasNoDeadComposerMicrophoneOrCallControls() {
        val ui = FeedUiState(state = FeedState(onboardingComplete = true), connected = true, loading = false)
        compose.setContent { MaterialTheme { AiWorkspace(ui, {}, {}, {}, {}, {}, {}, {}, {}, {}) } }
        compose.onNodeWithTag("workspace-voice").assertDoesNotExist()
        compose.onNodeWithTag("workspace-voice-live-start").assertDoesNotExist()
        compose.onNodeWithTag("workspace-voice-live-status").assertDoesNotExist()
        compose.onNodeWithTag("workspace-voice-options").assertExists() // Local read-aloud settings are independent.
    }

    @Test fun capabilityRemovalClosesOptionsAndRemovesCallControls() {
        val capability = mutableStateOf<RealtimeCapability>(RealtimeCapability.Available)
        val ui = FeedUiState(state = FeedState(onboardingComplete = true), connected = true, loading = false)
        compose.setContent { MaterialTheme {
            AiWorkspace(ui, {}, {}, {}, {}, {}, {}, {}, {}, {},
                realtimeCapability = capability.value, onStartRealtimeVoiceCall = {})
        } }
        compose.onNodeWithTag("workspace-voice").performClick()
        compose.onNodeWithTag("workspace-voice-live-start").assertExists()
        compose.runOnIdle { capability.value = RealtimeCapability.Unavailable(RealtimeCapability.Reason.SERVICE_NOT_CONFIGURED) }
        compose.onNodeWithTag("workspace-voice").assertDoesNotExist()
        compose.onNodeWithTag("workspace-voice-live-start").assertDoesNotExist()
        compose.onNodeWithText("Voice features").assertDoesNotExist()
    }

    @Test fun transcriptionOnlyShowsRecordingWithoutUnavailableCallControls() {
        val ui = FeedUiState(state = FeedState(onboardingComplete = true), connected = true, loading = false)
        compose.setContent { MaterialTheme {
            AiWorkspace(ui, {}, {}, {}, {}, {}, {}, {}, {}, {},
                recordingTranscriptionCapability = mebot.nguli.com.realtime.RecordingTranscriptionCapability.Available,
                onTranscribeVoice = mebot.nguli.com.ui.feed.WorkspaceVoiceTranscriber("Configured service", "Separate API usage") { "" })
        } }
        compose.onNodeWithTag("workspace-voice").performClick()
        compose.onNodeWithTag("workspace-voice-record-transcribe").assertExists()
        compose.onNodeWithTag("workspace-voice-live-start").assertDoesNotExist()
        compose.onNodeWithTag("workspace-voice-live-status").assertDoesNotExist()
    }

    @Test fun liveVoiceCallNeedsExplicitPrivacyAndBillingConfirmation() {
        var started = false
        val ui = FeedUiState(state = FeedState(onboardingComplete = true), connected = true, loading = false)
        compose.setContent {
            MaterialTheme {
                AiWorkspace(
                    ui, {}, {}, {}, {}, {}, {}, {}, {}, {},
                    realtimeCapability = RealtimeCapability.Available,
                    onStartRealtimeVoiceCall = { started = true },
                )
            }
        }
        compose.onNodeWithTag("workspace-voice").performClick()
        compose.onNodeWithTag("workspace-voice-live-start").performClick()
        compose.onNodeWithTag("workspace-voice-live-confirm").assertIsDisplayed()
        compose.onNodeWithText("Your microphone audio will stream to OpenAI", substring = true).assertIsDisplayed()
        assertTrue("Starting a call should require confirmation", !started)
        compose.onNodeWithTag("workspace-voice-live-cancel").performClick()
        assertTrue("Cancel must not start a call", !started)
        compose.onNodeWithTag("workspace-voice-live-start").performClick()
        compose.onNodeWithTag("workspace-voice-live-confirm").assertIsDisplayed()
        assertTrue("Reopening confirmation must still not start a call", !started)
        compose.onNodeWithTag("workspace-voice-live-confirm").performClick()
        assertTrue(started)
    }
}

private class RecordingTextContextMenuProvider : TextContextMenuProvider {
    var copyItem: TextContextMenuItem? = null
        private set

    override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
        copyItem = dataProvider.data().components
            .filterIsInstance<TextContextMenuItem>()
            .firstOrNull { it.key == TextContextMenuKeys.CopyKey }
    }
}

private object NoopTextContextMenuSession : TextContextMenuSession {
    override fun close() = Unit
}
