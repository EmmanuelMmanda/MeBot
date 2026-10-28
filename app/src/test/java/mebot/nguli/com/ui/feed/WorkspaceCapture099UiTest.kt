package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WorkspaceCapture099UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun delayedTranscriptAppendsToLatestTypedDraftAndKeepsClip() {
        val clipFile = File.createTempFile("voice-", ".m4a", compose.activity.cacheDir).apply { writeBytes(ByteArray(1024)) }
        val clip = WorkspaceClip(clipFile, durationMillis = 12_000L)
        val draft = mutableStateOf("original prompt")
        val started = AtomicBoolean(false)
        val pendingResult = CompletableDeferred<String>()
        val transcriber = WorkspaceVoiceTranscriber("Test service", "No additional usage", transcribe = {
            started.set(true)
            pendingResult.await()
        })

        compose.setContent {
            MaterialTheme {
                Column {
                    TextField(value = draft.value, onValueChange = { draft.value = it }, modifier = Modifier.testTag("voice-test-draft"))
                    WorkspaceVoiceCapture(
                        ownerKey = "test-draft",
                        existingClip = clip,
                        existingClipHasTranscript = false,
                        onClipReady = {},
                        onDiscardClip = {},
                        onTranscript = { result -> draft.value = mergeWorkspaceTranscriptIntoDraft(draft.value, result) },
                        onClose = {},
                        transcriber = transcriber,
                    )
                }
            }
        }

        compose.onNodeWithTag("workspace-voice-transcribe").performClick()
        compose.onNodeWithTag("workspace-voice-transcribe-confirm").performClick()
        compose.waitUntil(5_000) { started.get() }
        compose.runOnIdle { draft.value = "text typed while transcription was pending" }
        pendingResult.complete("recognized words")
        compose.waitUntil(5_000) { draft.value.contains("recognized words") }

        compose.runOnIdle {
            assertEquals("text typed while transcription was pending\nrecognized words", draft.value)
            assertTrue("The local clip must remain available until explicit discard or accepted send", clipFile.exists())
        }
        compose.onNodeWithTag("workspace-voice-transcription-unavailable").assertDoesNotExist()
    }
}
