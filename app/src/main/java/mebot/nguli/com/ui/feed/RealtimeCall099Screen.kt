package mebot.nguli.com.ui.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import mebot.nguli.com.R
import mebot.nguli.com.realtime.RealtimeCallPhase
import mebot.nguli.com.realtime.RealtimeCallSnapshot
import mebot.nguli.com.realtime.RealtimeServerEvent

/** Presentation-only projection; the host owns permission, controller and lifecycle cleanup. */
internal data class RealtimeCall099UiState(
    val snapshot: RealtimeCallSnapshot = RealtimeCallSnapshot(),
    val recentEvents: List<RealtimeServerEvent> = emptyList(),
    val microphoneMuted: Boolean = snapshot.microphoneMuted,
)

/**
 * A live two-way call screen, deliberately separate from answer read-aloud and voice-note capture.
 * Callbacks are supplied by the host only after it has handled microphone permission and policy.
 */
@Composable
internal fun RealtimeCall099Screen(
    state: RealtimeCall099UiState,
    onStart: () -> Unit,
    onCancelOrEnd: () -> Unit,
    onRetry: () -> Unit,
    onToggleMicrophone: ((Boolean) -> Unit)?,
    onBack: () -> Unit,
) {
    val snapshot = state.snapshot
    val connected = snapshot.isConnected
    val phase = snapshot.phase
    val inFlight = phase in setOf(
        RealtimeCallPhase.PREPARING_MEDIA,
        RealtimeCallPhase.GATHERING_ICE,
        RealtimeCallPhase.REQUESTING_SESSION,
        RealtimeCallPhase.EXCHANGING_SDP,
        RealtimeCallPhase.WAITING_FOR_READY,
        RealtimeCallPhase.ENDING,
    )
    val canStart = phase in setOf(RealtimeCallPhase.IDLE, RealtimeCallPhase.ENDED)
    val canRetry = phase in setOf(RealtimeCallPhase.FAILED, RealtimeCallPhase.DISCONNECTED)
    val mode = remember(state.recentEvents, connected) { activeVoiceMode(state.recentEvents, connected) }
    val transcript = remember(state.recentEvents) { transcriptLines(state.recentEvents) }

    Scaffold { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("realtime-call-back")) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back))
                }
                Text(stringResource(R.string.realtime_call_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            }
            Text(stringResource(R.string.realtime_call_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Card(
                modifier = Modifier.fillMaxWidth().testTag("realtime-call-status-card"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val statusRes = when {
                        connected -> mode.statusResource
                        phase == RealtimeCallPhase.CONNECTED -> R.string.realtime_call_waiting_ready
                        else -> phaseStatusResource(phase)
                    }
                    Text(
                        stringResource(statusRes),
                        Modifier.fillMaxWidth().testTag("realtime-call-status")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleMedium,
                        color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(iceStatusResource(phase, snapshot.readiness.iceGatheringComplete)),
                        Modifier.testTag("realtime-call-ice-status"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ReadinessRow(stringResource(R.string.realtime_call_peer), snapshot.readiness.peerConnected)
                    ReadinessRow(stringResource(R.string.realtime_call_data_channel), snapshot.readiness.dataChannelOpen)
                    ReadinessRow(stringResource(R.string.realtime_call_remote_audio), snapshot.readiness.remoteAudioTrackReceived)
                    ReadinessRow(stringResource(R.string.realtime_call_session), snapshot.readiness.sessionCreated)
                    if (!connected && snapshot.reasonCode != null) {
                        Text(stringResource(R.string.realtime_call_failure_help),
                            Modifier.testTag("realtime-call-failure-help"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Text(stringResource(R.string.realtime_call_privacy_note), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (canStart) {
                    Button(onClick = onStart, Modifier.weight(1f).testTag("realtime-call-start")) {
                        Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.realtime_call_start))
                    }
                }
                if (inFlight || connected || canRetry) {
                    OutlinedButton(onClick = onCancelOrEnd, Modifier.weight(1f).testTag("realtime-call-end")) {
                        Icon(Icons.Outlined.CallEnd, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(if (connected) R.string.realtime_call_end else R.string.realtime_call_cancel))
                    }
                }
                if (canRetry) {
                    Button(onClick = onRetry, Modifier.weight(1f).testTag("realtime-call-retry")) {
                        Text(stringResource(R.string.realtime_call_retry))
                    }
                }
            }

            if (connected && onToggleMicrophone != null) {
                OutlinedButton(
                    onClick = { onToggleMicrophone(!state.microphoneMuted) },
                    modifier = Modifier.fillMaxWidth().testTag("realtime-call-mute"),
                ) {
                    Icon(if (state.microphoneMuted) Icons.Outlined.MicOff else Icons.Outlined.Mic,
                        stringResource(if (state.microphoneMuted) R.string.realtime_call_unmute else R.string.realtime_call_mute),
                        Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (state.microphoneMuted) R.string.realtime_call_unmute else R.string.realtime_call_mute))
                }
            }

            if (connected && transcript.isNotEmpty()) {
                Text(stringResource(R.string.realtime_call_transcript_heading), style = MaterialTheme.typography.titleSmall)
                Card(Modifier.fillMaxWidth().testTag("realtime-call-transcript"), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        transcript.takeLast(12).forEach { line ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(stringResource(if (line.assistant) R.string.realtime_call_assistant else R.string.realtime_call_you),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(line.text, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            if (connected && mode == VoiceMode.INTERRUPTED) {
                Text(stringResource(R.string.realtime_call_interrupted), Modifier.testTag("realtime-call-interrupted"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            if (phase == RealtimeCallPhase.ENDED) {
                TextButton(onClick = onBack, Modifier.fillMaxWidth().testTag("realtime-call-close")) {
                    Text(stringResource(R.string.realtime_call_close))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ReadinessRow(label: String, ready: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(stringResource(if (ready) R.string.realtime_call_ready else R.string.realtime_call_pending),
            style = MaterialTheme.typography.labelSmall,
            color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun phaseStatusResource(phase: RealtimeCallPhase): Int = when (phase) {
    RealtimeCallPhase.IDLE -> R.string.realtime_call_idle
    RealtimeCallPhase.PREPARING_MEDIA -> R.string.realtime_call_preparing
    RealtimeCallPhase.GATHERING_ICE -> R.string.realtime_call_gathering_ice
    RealtimeCallPhase.REQUESTING_SESSION -> R.string.realtime_call_requesting
    RealtimeCallPhase.EXCHANGING_SDP -> R.string.realtime_call_negotiating
    RealtimeCallPhase.WAITING_FOR_READY -> R.string.realtime_call_waiting_ready
    RealtimeCallPhase.CONNECTED -> R.string.realtime_call_waiting_ready
    RealtimeCallPhase.DISCONNECTED -> R.string.realtime_call_disconnected
    RealtimeCallPhase.FAILED -> R.string.realtime_call_failed
    RealtimeCallPhase.ENDING -> R.string.realtime_call_ending
    RealtimeCallPhase.ENDED -> R.string.realtime_call_ended
}

private fun iceStatusResource(phase: RealtimeCallPhase, gatheringComplete: Boolean): Int = when {
    gatheringComplete -> R.string.realtime_call_ice_complete
    phase == RealtimeCallPhase.GATHERING_ICE -> R.string.realtime_call_ice_gathering
    else -> R.string.realtime_call_ice_not_started
}

private enum class VoiceMode(val statusResource: Int) {
    LISTENING(R.string.realtime_call_listening),
    THINKING(R.string.realtime_call_thinking),
    SPEAKING(R.string.realtime_call_speaking),
    INTERRUPTED(R.string.realtime_call_listening),
}

private fun activeVoiceMode(events: List<RealtimeServerEvent>, connected: Boolean): VoiceMode {
    if (!connected) return VoiceMode.LISTENING
    return when (events.lastOrNull()) {
        RealtimeServerEvent.UserSpeechStarted -> VoiceMode.LISTENING
        RealtimeServerEvent.UserSpeechStopped -> VoiceMode.THINKING
        is RealtimeServerEvent.AssistantTranscriptDelta,
        is RealtimeServerEvent.AssistantTranscriptCompleted,
        is RealtimeServerEvent.TextDelta -> VoiceMode.SPEAKING
        RealtimeServerEvent.ResponseCancelled -> VoiceMode.INTERRUPTED
        is RealtimeServerEvent.ResponseCompleted -> VoiceMode.LISTENING
        else -> VoiceMode.LISTENING
    }
}

private data class TranscriptLine(val key: String, val assistant: Boolean, val text: String)

private fun transcriptLines(events: List<RealtimeServerEvent>): List<TranscriptLine> {
    val lines = linkedMapOf<String, TranscriptLine>()
    events.takeLast(100).forEach { event ->
        when (event) {
            is RealtimeServerEvent.UserTranscriptDelta -> appendTranscript(lines, "user:${event.itemId}", false, event.text)
            is RealtimeServerEvent.UserTranscriptCompleted -> replaceTranscript(lines, "user:${event.itemId}", false, event.text)
            is RealtimeServerEvent.AssistantTranscriptDelta -> appendTranscript(lines, "assistant:${event.itemId}", true, event.text)
            is RealtimeServerEvent.AssistantTranscriptCompleted -> replaceTranscript(lines, "assistant:${event.itemId}", true, event.text)
            is RealtimeServerEvent.TextDelta -> appendTranscript(lines, "assistant:${event.itemId}", true, event.text)
            else -> Unit
        }
    }
    return lines.values.filter { it.text.isNotBlank() }.takeLast(12)
}

private fun appendTranscript(lines: MutableMap<String, TranscriptLine>, key: String, assistant: Boolean, delta: String) {
    val previous = lines[key]?.text.orEmpty()
    lines[key] = TranscriptLine(key, assistant, (previous + delta).takeLast(4000))
}

private fun replaceTranscript(lines: MutableMap<String, TranscriptLine>, key: String, assistant: Boolean, text: String) {
    lines[key] = TranscriptLine(key, assistant, text.takeLast(4000))
}
