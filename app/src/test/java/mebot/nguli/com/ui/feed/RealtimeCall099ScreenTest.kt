package mebot.nguli.com.ui.feed

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import mebot.nguli.com.realtime.RealtimeCallPhase
import mebot.nguli.com.realtime.RealtimeCallSnapshot
import mebot.nguli.com.realtime.RealtimeReadiness
import mebot.nguli.com.realtime.RealtimeServerEvent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RealtimeCall099ScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun callIsNotReportedLiveUntilPeerDataAudioAndSessionAreReady() {
        var starts = 0
        var current by mutableStateOf(RealtimeCall099UiState(
            snapshot = RealtimeCallSnapshot(phase = RealtimeCallPhase.CONNECTED,
                readiness = RealtimeReadiness(peerConnected = true, dataChannelOpen = true)),
        ))
        compose.setContent {
            MaterialTheme {
                RealtimeCall099Screen(current, { starts++ }, {}, {}, { _ -> }, {})
            }
        }
        compose.onNodeWithText("Waiting for audio, events, and session readiness…").assertIsDisplayed()
        compose.onNodeWithText("Live call · listening").assertDoesNotExist()
        assertEquals("Composition must never auto-start a call", 0, starts)

        compose.runOnIdle {
            current = current.copy(snapshot = current.snapshot.copy(readiness = RealtimeReadiness(
                iceGatheringComplete = true,
                peerConnected = true,
                dataChannelOpen = true,
                remoteAudioTrackReceived = true,
                sessionCreated = true,
            )))
        }
        compose.onNodeWithText("Live call · listening").assertIsDisplayed()
        assertEquals(0, starts)
    }

    @Test fun connectionCanAlwaysBeCancelledAndRetryIsExplicit() {
        var cancelled = 0
        var retried = 0
        compose.setContent {
            MaterialTheme {
                RealtimeCall099Screen(
                    state = RealtimeCall099UiState(snapshot = RealtimeCallSnapshot(phase = RealtimeCallPhase.GATHERING_ICE)),
                    onStart = {},
                    onCancelOrEnd = { cancelled++ },
                    onRetry = { retried++ },
                    onToggleMicrophone = null,
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Gathering network connection details (ICE)…").assertIsDisplayed()
        compose.onNodeWithTag("realtime-call-end").assertIsDisplayed().performClick()
        assertEquals(1, cancelled)
        assertEquals("No retry may happen without an explicit failure-state action", 0, retried)
    }

    @Test fun liveStateUsesTypedEventsForSpeakingAndShowsTranscriptOnlyWhenConnected() {
        val ready = RealtimeCall099UiState(
            snapshot = RealtimeCallSnapshot(phase = RealtimeCallPhase.CONNECTED,
                readiness = RealtimeReadiness(iceGatheringComplete = true, peerConnected = true,
                    dataChannelOpen = true, remoteAudioTrackReceived = true, sessionCreated = true)),
            recentEvents = listOf(
                RealtimeServerEvent.UserTranscriptCompleted("u1", "How are you?"),
                RealtimeServerEvent.AssistantTranscriptDelta("a1", "I am well."),
            ),
        )
        compose.setContent {
            MaterialTheme {
                RealtimeCall099Screen(ready, {}, {}, {}, { _ -> }, {})
            }
        }
        compose.onNodeWithText("Live call · speaking").assertIsDisplayed()
        compose.onNodeWithTag("realtime-call-transcript").assertIsDisplayed()
        compose.onNodeWithText("How are you?").assertIsDisplayed()
        compose.onNodeWithText("I am well.").assertIsDisplayed()
        compose.onNodeWithTag("realtime-call-mute").assertIsDisplayed()
    }
}
