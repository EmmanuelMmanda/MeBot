package mebot.nguli.com.ui

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import java.io.File
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Scaffold
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import mebot.nguli.com.MeBotNavigation
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import mebot.nguli.com.core.*
import mebot.nguli.com.feed.FeedUiState
import mebot.nguli.com.ui.feed.AiWorkspace
import mebot.nguli.com.ui.feed.openInAppSource
import mebot.nguli.com.ui.feed.PdfResourcePreview
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
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
class RichWorkspace083Test {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val thread = AiConversation(id = "thread", attachments = listOf(AiAttachment("file", "notes.txt", "Approved text")))
    private val ui = mutableStateOf(FeedUiState(state = FeedState(onboardingComplete = true, conversations = listOf(thread)), connected = true, loading = false, activeConversationId = "thread"))
    private val asked = mutableListOf<String>()
    private fun show() { compose.setContent { MaterialTheme { Surface {
        AiWorkspace(ui.value, {}, {}, {}, { asked += it }, {}, {}, {}, {}, {})
    } } } }
    @Test fun clearsComposerOnDurableUserTurnBeforeAnswerAndKeepsFilesReviewable() {
        show()
        compose.onNodeWithTag("workspace-question").performTextInput("Explain this")
        compose.onNodeWithTag("workspace-send").performClick()
        assertEquals(listOf("Explain this"), asked)
        compose.runOnIdle { ui.value = ui.value.copy(asking = true, state = ui.value.state!!.copy(conversations = listOf(thread.copy(turns = listOf(AiTurn("sent", "user", "Explain this")))))) }
        compose.onNodeWithTag("workspace-question").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("attachment-file").assertDoesNotExist()
        compose.onNodeWithTag("workspace-conversation-files").assertExists()
    }
    @Test fun duplicateDurableRetryClearsOnlyItsDraftAndDoesNotHideNewFiles() {
        ui.value = ui.value.copy(state = ui.value.state!!.copy(conversations = listOf(thread.copy(
            turns = listOf(AiTurn("previous", "user", "Retry this"))))))
        show()
        compose.onNodeWithTag("workspace-question").performTextInput("Retry this")
        compose.onNodeWithTag("workspace-send").performClick()
        assertEquals(listOf("Retry this"), asked)
        compose.onNodeWithTag("workspace-question").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("attachment-file").assertExists()
    }
    @Test fun previouslySentFilesStayOutOfComposerAfterFreshComposition() {
        ui.value = ui.value.copy(state = ui.value.state!!.copy(conversations = listOf(thread.copy(
            attachments = listOf(AiAttachment("file", "notes.txt", "Approved text", usedAtMillis = 1)),
            turns = listOf(AiTurn("previous", "user", "Earlier question"))))))
        show()
        compose.onNodeWithTag("attachment-file").assertDoesNotExist()
        compose.onNodeWithTag("workspace-conversation-files").assertExists()
    }
    @Test fun rendersInlineVideoResourceWithoutStartingPlayer() {
        ui.value = ui.value.copy(state = ui.value.state!!.copy(conversations = listOf(thread.copy(attachments = emptyList(),
            title = "Plan a practical learning session",
            turns = listOf(AiTurn("question", "user", "Help me plan a short learning session with a video and a checklist."),
                AiTurn("answer", "assistant", "## A focused 20-minute session\n**Example conversation for preview.** Choose one skill you can practise today, then use a short video to see the steps.\n\n- Spend 5 minutes taking notes.\n- Practise for 10 minutes without looking.\n- Use the last 5 minutes to review what worked.\n\nKeep the checklist nearby and write one question for your next session. These are sample links, not verified learning recommendations.\n\n[Watch the example](https://www.youtube.com/watch?v=M7lc1UVf-VE)\n\n[Sample session checklist](https://example.org/session-checklist.pdf)"))))))
        compose.setContent { MeBotTheme {
            Scaffold(bottomBar = { MeBotNavigation("ai", {}) }) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    AiWorkspace(ui.value, {}, {}, {}, { asked += it }, {}, {}, {}, {}, {})
                }
            }
        } }
        compose.onNodeWithTag("workspace-messages").performScrollToIndex(0)
        screenshot("083-rich-chat-answer")
        compose.onNodeWithTag("workspace-messages").performScrollToNode(hasText("Links and media", substring = true))
        compose.onAllNodesWithText("Watch the example", substring = true).onFirst().assertExists()
        compose.onNodeWithText(compose.activity.getString(mebot.nguli.com.R.string.resource_preview)).assertExists()
        screenshot("083-rich-chat")
    }
    @Test fun rejectedSubmissionKeepsDraft() {
        show()
        compose.onNodeWithTag("workspace-question").performTextInput("Keep me")
        compose.onNodeWithTag("workspace-send").performClick()
        compose.runOnIdle { ui.value = ui.value.copy(error = "offline", asking = false) }
        compose.onNodeWithTag("workspace-question").assertTextContains("Keep me")
        compose.onNodeWithTag("attachment-file").assertExists()
    }
    @Test fun contentBrowserUsesBrowserOwnedCustomTabWithoutCredentialBridge() {
        openInAppSource(compose.activity, "https://example.com/guide")
        val intent = shadowOf(compose.activity).nextStartedActivity
        assertEquals("https://example.com/guide", intent.dataString)
        assertTrue(intent.hasExtra("android.support.customtabs.extra.SESSION"))
    }
    @Test fun pdfFailureShowsRecoveryAndCanBeDismissed() {
        val visible = mutableStateOf(true)
        compose.setContent { MaterialTheme {
            if (visible.value) PdfResourcePreview("https://example.com/guide.pdf", "Guide", { visible.value = false },
                download = { throw java.io.IOException("Controlled failure") })
        } }
        compose.onNodeWithText(compose.activity.getString(mebot.nguli.com.R.string.rich_pdf_failed)).assertExists()
        compose.onNodeWithTag("pdf-close").performClick()
        compose.onNodeWithTag("pdf-preview").assertDoesNotExist()
    }
    @Test fun pdfDownloadTimeoutOffersRecoveryInsteadOfEndlessLoading() {
        compose.setContent { MaterialTheme {
            PdfResourcePreview("https://example.com/guide.pdf", "Guide", {},
                download = { withTimeout(1) { awaitCancellation() } })
        } }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(compose.activity.getString(mebot.nguli.com.R.string.rich_pdf_failed))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pdf-loading").assertDoesNotExist()
        compose.onNodeWithText(compose.activity.getString(mebot.nguli.com.R.string.feed_retry)).assertExists()
    }
    @Test fun closingPdfCancelsPendingDownload() {
        val visible = mutableStateOf(true)
        var cancelled = false
        compose.setContent { MaterialTheme {
            if (visible.value) PdfResourcePreview("https://example.com/guide.pdf", "Guide", { visible.value = false },
                download = { try { awaitCancellation() } finally { cancelled = true } })
        } }
        compose.onNodeWithTag("pdf-loading").assertExists()
        compose.onNodeWithTag("pdf-close").performClick()
        compose.runOnIdle { assertTrue(cancelled) }
    }
    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val bitmap = compose.runOnIdle {
            val window = compose.activity.window
            val view = window.decorView
            view.invalidate()
            Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                var result: Int? = null
                PixelCopy.request(window, it, { status -> result = status }, Handler(Looper.getMainLooper()))
                assertEquals(PixelCopy.SUCCESS, result)
            }
        }
        val target = File("build/outputs/screenshots/$name.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    @Test fun privateBrowserDestinationIsRejected() {
        openInAppSource(compose.activity, "http://127.0.0.1/private")
        assertNull(shadowOf(compose.activity).nextStartedActivity)
    }
}
