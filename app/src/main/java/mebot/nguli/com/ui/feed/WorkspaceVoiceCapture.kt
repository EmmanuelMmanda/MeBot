package mebot.nguli.com.ui.feed

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mebot.nguli.com.R
import java.io.File
import java.util.Locale

internal const val WORKSPACE_CAPTURE_MAX_MILLIS = 120_000L
internal const val WORKSPACE_AUDIO_ORPHAN_MIN_AGE_MILLIS = 24L * 60L * 60L * 1000L
internal const val WORKSPACE_AUDIO_ORPHAN_DELETE_LIMIT = 16

internal data class WorkspaceClip(val file: File, val durationMillis: Long, val partial: Boolean = false)
internal data class StoredWorkspaceClip(val path: String, val durationMillis: Long, val transcriptApplied: Boolean)

/** A future transcription route must carry user-visible provider and usage disclosure with its callback. */
data class WorkspaceVoiceTranscriber(
    val providerLabel: String,
    val usageDisclosure: String,
    val transcribe: suspend (File) -> String,
)

internal fun encodeWorkspaceClip(clip: WorkspaceClip, transcriptApplied: Boolean): String =
    "${clip.durationMillis}\u001f${if (transcriptApplied) 1 else 0}\u001f${clip.file.absolutePath}"

internal fun decodeWorkspaceClip(value: String?): StoredWorkspaceClip? {
    val parts = value?.split('\u001f', limit = 3) ?: return null
    if (parts.size != 3) return null
    val duration = parts[0].toLongOrNull()?.coerceIn(0L, WORKSPACE_CAPTURE_MAX_MILLIS) ?: return null
    if (parts[1] !in setOf("0", "1") || parts[2].isBlank()) return null
    return StoredWorkspaceClip(parts[2], duration, parts[1] == "1")
}

internal sealed interface WorkspaceCaptureState {
    data object Idle : WorkspaceCaptureState
    data class Recording(val file: File, val startedAtElapsedMillis: Long, val levels: List<Float>) : WorkspaceCaptureState
    data object Processing : WorkspaceCaptureState
    data class Ready(val clip: WorkspaceClip) : WorkspaceCaptureState
    data class Failed(val code: WorkspaceCaptureFailure, val clip: WorkspaceClip? = null) : WorkspaceCaptureState
}

internal enum class WorkspaceCaptureFailure { START, SAVE }

internal data class WorkspaceCaptureFinish(val file: File?, val interrupted: Boolean)

internal fun isWorkspaceCaptureOwnerCurrent(capturedOwner: String, currentOwner: String): Boolean = capturedOwner == currentOwner

internal fun isWorkspaceTranscriptionCurrent(
    requestGeneration: Int,
    currentGeneration: Int,
    requestOwner: String,
    currentOwner: String,
): Boolean = requestGeneration == currentGeneration && isWorkspaceCaptureOwnerCurrent(requestOwner, currentOwner)

/** Merge a completed editable transcript without replacing text entered while capture/transcription ran. */
internal fun mergeWorkspaceTranscriptIntoDraft(currentDraft: String, transcript: String): String =
    if (currentDraft.isBlank()) transcript else "$currentDraft\n$transcript"

internal interface WorkspaceAudioCaptureDevice {
    fun start(): File
    fun amplitude(): Float
    fun finish(): WorkspaceCaptureFinish
    fun cancel()
    fun delete(file: File)
    fun release()
}

/** Activity-owned recording controller. It never transcribes, uploads, or sends by itself. */
internal class WorkspaceCaptureController(private val device: WorkspaceAudioCaptureDevice) {
    var state by mutableStateOf<WorkspaceCaptureState>(WorkspaceCaptureState.Idle)
        private set
    private var pendingFinish: Pair<File, Long>? = null

    fun restore(clip: WorkspaceClip?) {
        if (state is WorkspaceCaptureState.Recording || state is WorkspaceCaptureState.Processing) return
        state = if (clip?.file?.isFile == true && clip.file.length() > 0L) WorkspaceCaptureState.Ready(clip)
            else WorkspaceCaptureState.Idle
    }

    fun start() {
        if (state is WorkspaceCaptureState.Recording || state is WorkspaceCaptureState.Processing) return
        try {
            val file = device.start()
            state = WorkspaceCaptureState.Recording(file, SystemClock.elapsedRealtime(), listOf(0.08f))
        } catch (_: SecurityException) {
            state = WorkspaceCaptureState.Failed(WorkspaceCaptureFailure.START)
        } catch (_: Exception) {
            state = WorkspaceCaptureState.Failed(WorkspaceCaptureFailure.START)
        }
    }

    fun tick(now: Long = SystemClock.elapsedRealtime()): Boolean {
        val recording = state as? WorkspaceCaptureState.Recording ?: return false
        val level = runCatching { device.amplitude().coerceIn(0f, 1f) }.getOrDefault(0f)
        val levels = (recording.levels + level).takeLast(32)
        state = recording.copy(levels = levels)
        if (now - recording.startedAtElapsedMillis >= WORKSPACE_CAPTURE_MAX_MILLIS) {
            beginFinish()
            return true
        }
        return false
    }

    /** Sets Processing before doing the final local container write. */
    fun beginFinish(): Pair<File, Long>? {
        pendingFinish?.let { return it }
        val recording = state as? WorkspaceCaptureState.Recording ?: return null
        val duration = (SystemClock.elapsedRealtime() - recording.startedAtElapsedMillis).coerceIn(0L, WORKSPACE_CAPTURE_MAX_MILLIS)
        state = WorkspaceCaptureState.Processing
        return (recording.file to duration).also { pendingFinish = it }
    }

    @Synchronized fun finish(ticket: Pair<File, Long>): WorkspaceCaptureState {
        if (pendingFinish != ticket) return state
        val result = runCatching { device.finish() }.getOrElse { WorkspaceCaptureFinish(null, interrupted = true) }
        val file = result.file
        val clip = file?.let { WorkspaceClip(it, ticket.second, partial = result.interrupted) }
        val next = when {
            clip == null -> WorkspaceCaptureState.Failed(WorkspaceCaptureFailure.SAVE)
            else -> WorkspaceCaptureState.Ready(clip)
        }
        pendingFinish = null
        state = next
        return next
    }

    @Synchronized fun stop() {
        val ticket = beginFinish() ?: return
        finish(ticket)
    }

    fun cancelRecording() {
        device.cancel()
        pendingFinish = null
        state = WorkspaceCaptureState.Idle
    }

    fun delete(file: File) {
        device.delete(file)
        if ((state as? WorkspaceCaptureState.Ready)?.clip?.file == file) state = WorkspaceCaptureState.Idle
    }

    fun preserveAndRelease(): WorkspaceClip? {
        if (state is WorkspaceCaptureState.Recording || state is WorkspaceCaptureState.Processing) stop()
        val clip = (state as? WorkspaceCaptureState.Ready)?.clip
        device.release()
        return clip
    }
}

/** Private temporary M4A output. Capture exists only while this foreground UI is active. */
internal class AndroidWorkspaceAudioCaptureDevice(context: Context) : WorkspaceAudioCaptureDevice {
    private val cacheDir = File(context.applicationContext.cacheDir, "voice-captures")
    private var recorder: MediaRecorder? = null
    private var currentFile: File? = null

    @Suppress("DEPRECATION")
    @Synchronized override fun start(): File {
        cancel()
        if (!cacheDir.exists() && !cacheDir.mkdirs()) throw IllegalStateException("cache")
        if (!cacheDir.isDirectory) throw IllegalStateException("cache")
        // Record into an unpublished staging file. Only a successfully finalized container is
        // atomically renamed to the user-visible `voice-*.m4a` cache path.
        val output = File.createTempFile("recording-", ".pending.m4a", cacheDir)
        val created = try { MediaRecorder() } catch (failure: Exception) {
            output.delete()
            throw failure
        }
        try {
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioChannels(1)
            created.setAudioSamplingRate(24_000)
            created.setAudioEncodingBitRate(64_000)
            created.setOutputFile(output.absolutePath)
            created.prepare()
            created.start()
            recorder = created
            currentFile = output
            return output
        } catch (failure: Exception) {
            runCatching { created.reset() }
            runCatching { created.release() }
            recorder = null
            output.delete()
            throw failure
        }
    }

    @Synchronized override fun amplitude(): Float = runCatching {
        val value = recorder?.maxAmplitude ?: return@runCatching 0f
        (kotlin.math.ln(value.coerceAtLeast(0).toDouble() + 1.0) / kotlin.math.ln(32768.0)).toFloat().coerceIn(0f, 1f)
    }.getOrDefault(0f)

    @Synchronized override fun finish(): WorkspaceCaptureFinish {
        val active = recorder
        val output = currentFile
        recorder = null
        var interrupted = false
        if (active != null) {
            try { active.stop() } catch (_: RuntimeException) { interrupted = true }
            finally { runCatching { active.reset() }; runCatching { active.release() } }
        }
        currentFile = null
        val finalized = output?.takeIf { it.isFile && it.length() > 512L }?.let(::publishFinalizedFile)
        if (output != null && finalized == null) output.delete()
        return WorkspaceCaptureFinish(finalized, interrupted)
    }

    @Synchronized override fun cancel() {
        val active = recorder
        recorder = null
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        currentFile?.delete()
        currentFile = null
    }

    override fun delete(file: File) {
        val allowedParent = runCatching { cacheDir.canonicalFile }.getOrNull() ?: return
        val target = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (target.parentFile == allowedParent && target.name.startsWith("voice-") && target.extension == "m4a") target.delete()
    }

    private fun publishFinalizedFile(staging: File): File? {
        val placeholder = runCatching { File.createTempFile("voice-", ".m4a", cacheDir) }.getOrNull() ?: return null
        if (!placeholder.delete()) return null
        val target = placeholder
        return if (staging.renameTo(target) && target.isFile && target.length() > 512L) target else {
            target.delete()
            null
        }
    }

    @Synchronized override fun release() {
        // Never leave an unpublished staging file behind if a caller releases unexpectedly.
        // Normal disposal finalizes first, so this is a no-op after a preserved clip is published.
        cancel()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WorkspaceVoiceCapture(
    ownerKey: String,
    existingClip: WorkspaceClip?,
    existingClipHasTranscript: Boolean,
    onClipReady: (WorkspaceClip) -> Unit,
    onDiscardClip: () -> Unit,
    onTranscript: (String) -> Unit,
    onClose: () -> Unit,
    transcriber: WorkspaceVoiceTranscriber?,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val device = remember(context.applicationContext) { AndroidWorkspaceAudioCaptureDevice(context.applicationContext) }
    val controller = remember(device) { WorkspaceCaptureController(device) }
    val latestClipReady by rememberUpdatedState(onClipReady)
    val latestClose by rememberUpdatedState(onClose)
    val latestTranscriber by rememberUpdatedState(transcriber)
    val latestTranscript by rememberUpdatedState(onTranscript)
    val latestOwnerKey by rememberUpdatedState(ownerKey)
    val scope = rememberCoroutineScope()
    var recordingOwnerKey by remember { mutableStateOf(ownerKey) }
    var permissionOwnerKey by remember { mutableStateOf(ownerKey) }
    var permissionDenied by remember { mutableStateOf(false) }
    var transcriptionError by remember { mutableStateOf(false) }
    var transcribing by remember { mutableStateOf(false) }
    var transcriptionJob by remember { mutableStateOf<Job?>(null) }
    var transcriptionGeneration by remember { mutableIntStateOf(0) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmTranscription by remember { mutableStateOf(false) }
    val deliveredPaths = remember { mutableSetOf<String>() }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted && permissionOwnerKey == latestOwnerKey) {
            recordingOwnerKey = permissionOwnerKey
            controller.start()
        }
    }

    fun cancelTranscription() {
        transcriptionGeneration++
        transcriptionJob?.cancel()
        transcriptionJob = null
        transcribing = false
    }

    LaunchedEffect(ownerKey) {
        cancelTranscription()
        if (recordingOwnerKey != ownerKey &&
            (controller.state is WorkspaceCaptureState.Recording || controller.state is WorkspaceCaptureState.Processing)) {
            controller.stop()
            (controller.state as? WorkspaceCaptureState.Ready)?.clip?.let { controller.delete(it.file) }
        }
    }

    fun deliverReadyClip(clip: WorkspaceClip) {
        if (isWorkspaceCaptureOwnerCurrent(recordingOwnerKey, latestOwnerKey)) {
            if (deliveredPaths.add(clip.file.absolutePath)) latestClipReady(clip)
        } else controller.delete(clip.file)
    }

    LaunchedEffect(existingClip?.file?.absolutePath, existingClip?.durationMillis) { controller.restore(existingClip) }
    fun finishRecording() {
        val ticket = controller.beginFinish() ?: return
        scope.launch {
            // Yield once so the visible processing status is painted before finalizing the file.
            delay(60)
            val result = controller.finish(ticket)
            val ready = (result as? WorkspaceCaptureState.Ready)?.clip
            if (ready != null) deliverReadyClip(ready)
        }
    }

    LaunchedEffect(controller) {
        while (isActive) {
            if (controller.state is WorkspaceCaptureState.Recording) {
                delay(140)
                if (controller.tick()) finishRecording()
                val ready = (controller.state as? WorkspaceCaptureState.Ready)?.clip
                if (ready != null) deliverReadyClip(ready)
            } else delay(250)
        }
    }
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                cancelTranscription()
                controller.stop()
                (controller.state as? WorkspaceCaptureState.Ready)?.clip?.let { clip ->
                    deliverReadyClip(clip)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cancelTranscription()
            controller.preserveAndRelease()?.let { clip ->
                deliverReadyClip(clip)
            }
        }
    }

    fun discard() {
        cancelTranscription()
        ((controller.state as? WorkspaceCaptureState.Ready)?.clip?.file ?: existingClip?.file)?.let(controller::delete)
        if (controller.state is WorkspaceCaptureState.Recording) controller.cancelRecording()
        onDiscardClip()
        confirmDiscard = false
        transcriptionError = false
    }

    fun startRecording() {
        permissionDenied = false
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            recordingOwnerKey = ownerKey
            controller.start()
        } else {
            permissionOwnerKey = ownerKey
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun startTranscription() {
        val clip = (controller.state as? WorkspaceCaptureState.Ready)?.clip ?: existingClip
        val currentTranscriber = latestTranscriber
        if (clip == null || existingClipHasTranscript) return
        if (currentTranscriber == null) {
            transcriptionError = true
            return
        }
        transcriptionJob?.cancel()
        val requestGeneration = ++transcriptionGeneration
        val requestOwner = ownerKey
        transcriptionError = false
        transcribing = true
        transcriptionJob = scope.launch {
            try {
                val transcript = withContext(Dispatchers.IO) { currentTranscriber.transcribe(clip.file) }.trim()
                if (transcript.isBlank()) throw IllegalStateException("empty transcript")
                if (!isWorkspaceTranscriptionCurrent(requestGeneration, transcriptionGeneration, requestOwner, latestOwnerKey)) return@launch
                latestTranscript(transcript.take(8000))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (requestGeneration == transcriptionGeneration) transcriptionError = true
            } finally {
                if (requestGeneration == transcriptionGeneration) {
                    transcribing = false
                    transcriptionJob = null
                }
            }
        }
    }

    ModalBottomSheet(onDismissRequest = {
        cancelTranscription()
        finishRecording()
        latestClose()
    }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 720.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Mic, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.workspace_voice_capture_title), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = {
                    cancelTranscription()
                    finishRecording()
                    latestClose()
                }, modifier = Modifier.testTag("workspace-voice-close")) { Icon(Icons.Outlined.Close, stringResource(R.string.workspace_voice_keep_close)) }
            }
            Text(stringResource(R.string.workspace_voice_intro), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            val state = controller.state
            if (state is WorkspaceCaptureState.Idle && existingClip == null) {
                Text(stringResource(R.string.workspace_voice_permission), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (state) {
                WorkspaceCaptureState.Idle -> Unit
                is WorkspaceCaptureState.Recording -> {
                    val elapsed = (SystemClock.elapsedRealtime() - state.startedAtElapsedMillis).coerceAtLeast(0L)
                    Text(stringResource(R.string.workspace_voice_recording_time, formatCaptureDuration(elapsed)),
                        Modifier.testTag("workspace-voice-timer"), style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error)
                    CaptureWaveform(state.levels, stringResource(R.string.workspace_voice_waveform))
                    Text(stringResource(R.string.workspace_voice_recording_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                WorkspaceCaptureState.Processing -> Text(stringResource(R.string.workspace_voice_processing),
                    Modifier.testTag("workspace-voice-processing"), style = MaterialTheme.typography.bodyMedium)
                is WorkspaceCaptureState.Ready -> {
                    Text(stringResource(if (state.clip.partial) R.string.workspace_voice_partial_ready else R.string.workspace_voice_ready,
                        formatCaptureDuration(state.clip.durationMillis)), style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.testTag("workspace-voice-ready"))
                    if (transcribing) Text(stringResource(R.string.workspace_voice_transcribing), Modifier.testTag("workspace-voice-transcribing"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (existingClipHasTranscript) Text(stringResource(R.string.workspace_voice_transcript_in_composer),
                        Modifier.testTag("workspace-voice-transcript-in-composer"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (latestTranscriber == null) Text(stringResource(R.string.workspace_voice_no_transcriber),
                        Modifier.testTag("workspace-voice-transcription-unavailable"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else if (transcriptionError) Text(stringResource(R.string.workspace_voice_transcription_failed),
                        Modifier.testTag("workspace-voice-transcription-error"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                }
                is WorkspaceCaptureState.Failed -> {
                    Text(stringResource(if (state.code == WorkspaceCaptureFailure.START) R.string.workspace_voice_recording_failed else R.string.workspace_voice_save_failed),
                        Modifier.testTag("workspace-voice-failed"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error)
                    state.clip?.let { clip -> Text(stringResource(R.string.workspace_voice_partial_ready, formatCaptureDuration(clip.durationMillis))) }
                }
            }
            if (permissionDenied) Text(stringResource(R.string.workspace_voice_permission_denied), Modifier.testTag("workspace-voice-permission-denied"),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (existingClip == null && state !is WorkspaceCaptureState.Recording && state !is WorkspaceCaptureState.Processing && !transcribing) {
                FilledTonalButton(onClick = ::startRecording, modifier = Modifier.fillMaxWidth().testTag("workspace-voice-start")) {
                    Icon(Icons.Outlined.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.workspace_voice_start))
                }
            }
            if (state is WorkspaceCaptureState.Recording) {
                FilledTonalButton(onClick = ::finishRecording, modifier = Modifier.fillMaxWidth().testTag("workspace-voice-stop")) {
                    Icon(Icons.Outlined.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.workspace_voice_stop))
                }
            }
            val readyClip = (state as? WorkspaceCaptureState.Ready)?.clip ?: existingClip
            if (readyClip != null) {
                Button(onClick = { confirmTranscription = true }, enabled = !transcribing && latestTranscriber != null && !existingClipHasTranscript,
                    modifier = Modifier.fillMaxWidth().testTag("workspace-voice-transcribe")) {
                    Text(stringResource(R.string.workspace_voice_transcribe))
                }
                if (transcribing) TextButton(onClick = ::cancelTranscription,
                    modifier = Modifier.fillMaxWidth().testTag("workspace-voice-cancel-transcribe")) {
                    Text(stringResource(R.string.workspace_voice_cancel_transcription))
                }
            }
            if (state is WorkspaceCaptureState.Recording || state is WorkspaceCaptureState.Ready ||
                state is WorkspaceCaptureState.Failed || existingClip != null) {
                TextButton(onClick = { confirmDiscard = true }, enabled = !transcribing,
                    modifier = Modifier.fillMaxWidth().testTag("workspace-voice-discard")) {
                    Text(stringResource(R.string.workspace_voice_discard))
                }
            }
            if (state !is WorkspaceCaptureState.Recording) TextButton(onClick = {
                finishRecording()
                latestClose()
            }, modifier = Modifier.fillMaxWidth().testTag("workspace-voice-keep-close")) {
                Text(stringResource(R.string.workspace_voice_keep_close))
            }
            Text(stringResource(R.string.workspace_voice_send_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text(stringResource(R.string.workspace_voice_discard_title)) },
        text = { Text(stringResource(R.string.workspace_voice_discard_body)) },
        confirmButton = {
            TextButton(onClick = ::discard, modifier = Modifier.testTag("workspace-voice-discard-confirm")) {
                Text(stringResource(R.string.workspace_voice_discard))
            }
        },
        dismissButton = {
            TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.workspace_voice_keep_close)) }
        },
    )

    if (confirmTranscription) {
        val selected = latestTranscriber
        if (selected != null) AlertDialog(
            onDismissRequest = { confirmTranscription = false },
            title = { Text(stringResource(R.string.workspace_voice_transcribe_confirm_title)) },
            text = { Text(stringResource(R.string.workspace_voice_transcribe_confirm_body, selected.providerLabel, selected.usageDisclosure)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmTranscription = false
                    startTranscription()
                }, modifier = Modifier.testTag("workspace-voice-transcribe-confirm")) {
                    Text(stringResource(R.string.workspace_voice_transcribe_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmTranscription = false }, modifier = Modifier.testTag("workspace-voice-transcribe-cancel-confirm")) {
                    Text(stringResource(R.string.workspace_voice_cancel_transcription))
                }
            },
        )
    }
}

@Composable
private fun CaptureWaveform(levels: List<Float>, description: String) {
    val waveformColor = MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(58.dp).semantics { contentDescription = description }.testTag("workspace-voice-waveform")) {
        val count = 28
        val gap = 3.dp.toPx()
        val width = ((size.width - gap * (count - 1)) / count).coerceAtLeast(1f)
        val center = size.height / 2
        for (index in 0 until count) {
            val level = levels.getOrNull(levels.size - count + index)?.coerceIn(0f, 1f) ?: 0.04f
            val barHeight = (size.height * (0.10f + level * 0.82f)).coerceAtLeast(4.dp.toPx())
            drawLine(waveformColor, start = androidx.compose.ui.geometry.Offset(index * (width + gap), center - barHeight / 2),
                end = androidx.compose.ui.geometry.Offset(index * (width + gap), center + barHeight / 2), strokeWidth = width, cap = StrokeCap.Round)
        }
    }
}

internal fun formatCaptureDuration(millis: Long): String {
    val totalSeconds = (millis / 1000L).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

internal fun resolveWorkspaceClip(context: Context, path: String?, durationMillis: Long): WorkspaceClip? {
    if (path.isNullOrBlank()) return null
    val candidate = safeWorkspaceClipFile(context, path) ?: return null
    if (!candidate.isFile || candidate.length() <= 512L) return null
    return WorkspaceClip(candidate, durationMillis.coerceIn(0L, WORKSPACE_CAPTURE_MAX_MILLIS))
}

/** Safe deletion accepts truncated final clips too, but never follows a path outside our cache folder. */
internal fun deleteWorkspaceClipFile(context: Context, path: String?) {
    safeWorkspaceClipFile(context, path)?.delete()
}

/**
 * Bounded cache maintenance for private voice files which are no longer referenced by any draft.
 * A recent staging file is left alone because it could belong to a live recorder; callers should
 * also pass its path explicitly when they know one is active. Referenced finalized clips are kept
 * even when old or truncated so a recoverable draft is never silently lost.
 *
 * Only app-owned files directly inside `cache/voice-captures` are eligible. No recursive walk,
 * symlink traversal, or path from a caller is ever deleted. The maximum deletion count limits I/O.
 */
internal fun cleanupOrphanedWorkspaceVoiceAudio(
    context: Context,
    referencedPaths: Set<String> = emptySet(),
    activePath: String? = null,
    nowMillis: Long = System.currentTimeMillis(),
    minimumAgeMillis: Long = WORKSPACE_AUDIO_ORPHAN_MIN_AGE_MILLIS,
    maxDeletes: Int = WORKSPACE_AUDIO_ORPHAN_DELETE_LIMIT,
): Int {
    if (maxDeletes <= 0 || minimumAgeMillis < 0L) return 0
    val deleteLimit = maxDeletes.coerceAtMost(WORKSPACE_AUDIO_ORPHAN_DELETE_LIMIT)
    val directory = voiceCaptureDirectory(context) ?: return 0
    if (!directory.isDirectory) return 0

    val protectedPaths = (referencedPaths + listOfNotNull(activePath))
        .mapNotNull { safeWorkspaceAudioFile(directory, it)?.path }
        .toSet()
    val candidates = runCatching { directory.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
        .mapNotNull { file ->
            val safe = safeWorkspaceAudioFile(directory, file.path) ?: return@mapNotNull null
            if (!safe.isFile || safe.path in protectedPaths) return@mapNotNull null
            val modified = runCatching { safe.lastModified() }.getOrDefault(0L)
            val age = nowMillis - modified
            if (modified <= 0L || age < minimumAgeMillis) return@mapNotNull null
            safe to modified
        }
        .sortedBy { it.second }
        .take(deleteLimit)

    return candidates.count { (file, _) -> runCatching { file.delete() }.getOrDefault(false) }
}

/** Explicit user-requested erase: delete every app-owned clip and recording stage in this cache. */
internal fun purgeWorkspaceVoiceAudio(context: Context): Int {
    val directory = voiceCaptureDirectory(context) ?: return 0
    if (!directory.isDirectory) return 0
    val files = runCatching { directory.listFiles()?.toList().orEmpty() }.getOrDefault(emptyList())
    return files.count { file ->
        val safe = safeWorkspaceAudioFile(directory, file.path) ?: return@count false
        safe.isFile && runCatching { safe.delete() }.getOrDefault(false)
    }
}

private fun safeWorkspaceClipFile(context: Context, path: String?): File? {
    if (path.isNullOrBlank()) return null
    val directory = voiceCaptureDirectory(context) ?: return null
    val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    if (candidate.parentFile != directory || !candidate.extension.equals("m4a", ignoreCase = true) ||
        !candidate.name.startsWith("voice-")) return null
    return candidate
}

private fun voiceCaptureDirectory(context: Context): File? {
    val cacheRoot = runCatching { context.applicationContext.cacheDir.canonicalFile }.getOrNull() ?: return null
    val directory = runCatching { File(cacheRoot, "voice-captures").canonicalFile }.getOrNull() ?: return null
    // Refuse a replaced/symlinked voice directory rather than following it outside private cache.
    if (directory.parentFile != cacheRoot || directory.name != "voice-captures") return null
    return directory
}

private fun safeWorkspaceAudioFile(directory: File, path: String?): File? {
    if (path.isNullOrBlank()) return null
    val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
    if (candidate.parentFile != directory) return null
    val isFinalized = candidate.name.startsWith("voice-") && candidate.extension.equals("m4a", ignoreCase = true)
    val isStaging = candidate.name.startsWith("recording-") && candidate.name.endsWith(".pending.m4a", ignoreCase = true)
    if (!isFinalized && !isStaging) return null
    return candidate
}
