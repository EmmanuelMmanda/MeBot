package mebot.nguli.com.ui.feed

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import mebot.nguli.com.R
import java.util.Locale

internal data class WorkspaceSpeech(
    val available: Boolean,
    val readingId: String?,
    val voices: List<Pair<String, String>> = emptyList(),
    val statusMessage: Int? = null,
    val feedbackId: String? = null,
    val read: (String, String) -> Unit,
    val stop: () -> Unit,
    val retry: () -> Unit,
)

internal enum class WorkspaceSpeechStatus {
    INITIALIZING, READY, READING, STOPPED, INTERRUPTED,
    MISSING_ENGINE, ENGINE_ERROR, MISSING_VOICE, VOICE_DATA_MISSING,
    UNSUPPORTED_LANGUAGE, SELECTED_VOICE_MISSING, FOCUS_DENIED, SPEAK_ERROR,
}

internal data class WorkspaceSpeechSnapshot(
    val status: WorkspaceSpeechStatus = WorkspaceSpeechStatus.INITIALIZING,
    val readingId: String? = null,
    val feedbackId: String? = null,
)

internal data class InstalledWorkspaceVoice(val name: String, val label: String, val language: String, val hasData: Boolean)

/** Android TTS operations are kept behind a small port so setup and interruption behavior can be tested. */
internal interface WorkspaceSpeechEngine {
    fun hasEngineService(): Boolean
    fun initialize(onInitialized: (Int) -> Unit)
    fun setAudioAttributes(attributes: AudioAttributes): Int
    fun setLanguage(locale: Locale): Int
    fun voices(): List<InstalledWorkspaceVoice>
    fun setVoice(name: String): Int
    fun setSpeechRate(rate: Float): Int
    fun speak(text: String, utteranceId: String): Int
    fun stop()
    fun shutdown()
    fun setListener(listener: WorkspaceSpeechEngineListener)
    val maxSpeechInputLength: Int
}

internal interface WorkspaceSpeechEngineListener {
    fun onDone(utteranceId: String?)
    fun onError(utteranceId: String?)
    fun onStopped(utteranceId: String?)
}

internal interface WorkspaceAudioFocus {
    fun request(onLost: () -> Unit): Boolean
    fun abandon()
}

/** Owns language/voice validation, explicit read sessions, audio-focus release, and stale TTS callbacks. */
internal class WorkspaceSpeechController(
    private val engine: WorkspaceSpeechEngine,
    private val audioFocus: WorkspaceAudioFocus,
    private val locale: Locale,
) : WorkspaceSpeechEngineListener {
    var snapshot by mutableStateOf(WorkspaceSpeechSnapshot())
        private set
    var voiceOptions by mutableStateOf<List<InstalledWorkspaceVoice>>(emptyList())
        private set

    private var preferences = SpeechPreferences(1f, "")
    private var baseStatus = WorkspaceSpeechStatus.INITIALIZING
    private var started = false
    private var closed = false
    private var generation = 0L
    private var activeBatch: String? = null
    private var finalUtterance: String? = null
    private var batchNumber = 0L

    fun start(rate: Float, voiceName: String) {
        closed = false
        preferences = SpeechPreferences(rate.coerceIn(0.6f, 1.5f), voiceName)
        beginInitialization()
    }

    fun retry() {
        if (closed) closed = false
        stopInternal(WorkspaceSpeechStatus.STOPPED, notify = false)
        engine.shutdown()
        started = false
        beginInitialization()
    }

    private fun beginInitialization() {
        val token = ++generation
        snapshot = WorkspaceSpeechSnapshot(WorkspaceSpeechStatus.INITIALIZING)
        baseStatus = WorkspaceSpeechStatus.INITIALIZING
        if (!engine.hasEngineService()) {
            started = false
            baseStatus = WorkspaceSpeechStatus.MISSING_ENGINE
            snapshot = WorkspaceSpeechSnapshot(baseStatus)
            return
        }
        engine.setListener(this)
        try {
            engine.initialize { result ->
                if (closed || token != generation) return@initialize
                if (result != TextToSpeech.SUCCESS) {
                    started = false
                    baseStatus = WorkspaceSpeechStatus.ENGINE_ERROR
                    snapshot = WorkspaceSpeechSnapshot(baseStatus)
                } else {
                    started = true
                    configurePreferences()
                }
            }
        } catch (_: Throwable) {
            started = false
            baseStatus = WorkspaceSpeechStatus.ENGINE_ERROR
            snapshot = WorkspaceSpeechSnapshot(baseStatus)
        }
    }

    fun updatePreferences(rate: Float, voiceName: String) {
        preferences = SpeechPreferences(rate.coerceIn(0.6f, 1.5f), voiceName)
        if (snapshot.readingId != null) stopInternal(WorkspaceSpeechStatus.STOPPED, notify = false)
        if (started && !closed) configurePreferences()
    }

    private fun configurePreferences() {
        generation++
        stopInternal(WorkspaceSpeechStatus.STOPPED, notify = false)
        val audioAttributesResult = safely { engine.setAudioAttributes(SPEECH_ATTRIBUTES) }
        if (audioAttributesResult != TextToSpeech.SUCCESS) return setBaseStatus(WorkspaceSpeechStatus.ENGINE_ERROR)
        val matchingVoices = safely {
            engine.voices().filter { it.language == locale.language }
                .sortedWith(compareBy<InstalledWorkspaceVoice>({ !it.hasData }, { it.name.lowercase(Locale.ROOT) }))
        }.orEmpty()
        voiceOptions = matchingVoices.filter { it.hasData }
        if (matchingVoices.isEmpty()) return setBaseStatus(WorkspaceSpeechStatus.MISSING_VOICE)
        if (voiceOptions.isEmpty()) return setBaseStatus(WorkspaceSpeechStatus.VOICE_DATA_MISSING)
        val languageResult = safely { engine.setLanguage(locale) }
        if (languageResult == TextToSpeech.LANG_MISSING_DATA) return setBaseStatus(WorkspaceSpeechStatus.VOICE_DATA_MISSING)
        if (languageResult == TextToSpeech.LANG_NOT_SUPPORTED) return setBaseStatus(WorkspaceSpeechStatus.UNSUPPORTED_LANGUAGE)
        if (languageResult == TextToSpeech.ERROR || languageResult == null) return setBaseStatus(WorkspaceSpeechStatus.ENGINE_ERROR)
        if (languageResult < TextToSpeech.LANG_AVAILABLE) return setBaseStatus(WorkspaceSpeechStatus.UNSUPPORTED_LANGUAGE)
        val selectedVoice = preferences.voiceName
        if (selectedVoice.isNotBlank()) {
            val installed = voiceOptions.firstOrNull { it.name == selectedVoice && it.hasData }
                ?: return setBaseStatus(WorkspaceSpeechStatus.SELECTED_VOICE_MISSING)
            if (safely { engine.setVoice(installed.name) } != TextToSpeech.SUCCESS) {
                return setBaseStatus(WorkspaceSpeechStatus.SELECTED_VOICE_MISSING)
            }
        }
        if (safely { engine.setSpeechRate(preferences.rate) } != TextToSpeech.SUCCESS) {
            return setBaseStatus(WorkspaceSpeechStatus.ENGINE_ERROR)
        }
        setBaseStatus(WorkspaceSpeechStatus.READY)
    }

    private fun setBaseStatus(status: WorkspaceSpeechStatus) {
        baseStatus = status
        snapshot = WorkspaceSpeechSnapshot(status)
    }

    fun read(id: String, rawText: String) {
        val plainText = RichMarkdown.plainText(rawText)
        if (closed || !started || baseStatus != WorkspaceSpeechStatus.READY || plainText.isBlank()) {
            snapshot = WorkspaceSpeechSnapshot(baseStatus, feedbackId = id)
            return
        }
        stopInternal(WorkspaceSpeechStatus.READY, notify = false)
        if (!audioFocus.request { onAudioFocusLost() }) {
            snapshot = WorkspaceSpeechSnapshot(WorkspaceSpeechStatus.FOCUS_DENIED, feedbackId = id)
            return
        }
        val parts = chunkSpeechText(plainText, engine.maxSpeechInputLength.coerceAtLeast(80))
        if (parts.isEmpty()) {
            audioFocus.abandon()
            snapshot = WorkspaceSpeechSnapshot(WorkspaceSpeechStatus.SPEAK_ERROR, feedbackId = id)
            return
        }
        val batch = "${++batchNumber}-${System.nanoTime()}"
        activeBatch = batch
        finalUtterance = "$batch:${parts.lastIndex}"
        snapshot = WorkspaceSpeechSnapshot(WorkspaceSpeechStatus.READING, id, id)
        parts.forEachIndexed { index, part ->
            val utteranceId = "$batch:$index"
            val result = safely { engine.speak(part, utteranceId) }
            if (result != TextToSpeech.SUCCESS) {
                stopInternal(WorkspaceSpeechStatus.SPEAK_ERROR, notify = true)
                return
            }
        }
    }

    fun stop() {
        if (snapshot.readingId != null) stopInternal(WorkspaceSpeechStatus.STOPPED, notify = true)
        else if (baseStatus != WorkspaceSpeechStatus.READY) snapshot = WorkspaceSpeechSnapshot(baseStatus)
    }

    fun onLifecycleStopped() {
        if (snapshot.readingId != null) stopInternal(WorkspaceSpeechStatus.STOPPED, notify = false)
    }

    private fun onAudioFocusLost() {
        if (snapshot.readingId != null) stopInternal(WorkspaceSpeechStatus.INTERRUPTED, notify = false)
    }

    private fun stopInternal(status: WorkspaceSpeechStatus, notify: Boolean) {
        val wasReading = snapshot.readingId != null
        val feedbackId = snapshot.readingId ?: snapshot.feedbackId
        activeBatch = null
        finalUtterance = null
        if (wasReading || notify) safely { engine.stop() }
        audioFocus.abandon()
        snapshot = WorkspaceSpeechSnapshot(if (wasReading || notify) status else baseStatus,
            feedbackId = feedbackId.takeIf { wasReading || notify })
    }

    override fun onDone(utteranceId: String?) {
        if (utteranceId != null && utteranceId == finalUtterance && utteranceId.substringBeforeLast(':') == activeBatch) {
            activeBatch = null
            finalUtterance = null
            audioFocus.abandon()
            snapshot = WorkspaceSpeechSnapshot(WorkspaceSpeechStatus.READY)
        }
    }

    override fun onError(utteranceId: String?) {
        if (snapshot.readingId != null && (activeBatch == null || utteranceId == null || utteranceId.startsWith("$activeBatch:"))) {
            val feedbackId = snapshot.readingId
            activeBatch = null
            finalUtterance = null
            audioFocus.abandon()
            snapshot = WorkspaceSpeechSnapshot(WorkspaceSpeechStatus.SPEAK_ERROR, feedbackId = feedbackId)
        }
    }

    override fun onStopped(utteranceId: String?) {
        // Explicit stop already owns the visible state; ignore stale engine callbacks.
        if (utteranceId != null && activeBatch != null && utteranceId.startsWith("$activeBatch:")) {
            val feedbackId = snapshot.readingId
            activeBatch = null
            finalUtterance = null
            audioFocus.abandon()
            snapshot = WorkspaceSpeechSnapshot(WorkspaceSpeechStatus.STOPPED, feedbackId = feedbackId)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        generation++
        stopInternal(WorkspaceSpeechStatus.STOPPED, notify = true)
        engine.shutdown()
        started = false
        snapshot = WorkspaceSpeechSnapshot(WorkspaceSpeechStatus.STOPPED)
    }

    private data class SpeechPreferences(val rate: Float, val voiceName: String)

    private inline fun <T> safely(block: () -> T): T? = try { block() } catch (_: Throwable) { null }

    companion object {
        private val SPEECH_ATTRIBUTES = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}

/** Splits without cutting a word (or a UTF-16 surrogate pair) whenever a natural boundary exists. */
internal fun chunkSpeechText(text: String, maxLength: Int): List<String> {
    val limit = maxLength.coerceAtLeast(80)
    val result = mutableListOf<String>()
    var cursor = 0
    while (cursor < text.length) {
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
        if (cursor >= text.length) break
        var end = (cursor + limit).coerceAtMost(text.length)
        if (end < text.length) {
            val boundary = text.lastIndexOf(' ', end - 1).takeIf { it >= cursor + limit / 2 }
            if (boundary != null) end = boundary
            if (end > cursor && end < text.length && Character.isHighSurrogate(text[end - 1]) && Character.isLowSurrogate(text[end])) end--
        }
        if (end <= cursor) end = (cursor + limit).coerceAtMost(text.length)
        result += text.substring(cursor, end).trimEnd()
        cursor = end
    }
    return result.filter { it.isNotBlank() }
}

/** Phone speech is optional and user-initiated; it never changes or overrides the system volume. */
@Composable
internal fun rememberWorkspaceSpeech(rate: Float = 1f, voiceName: String = ""): WorkspaceSpeech {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val owner = LocalLifecycleOwner.current
    val appContext = context.applicationContext
    val controller = remember(appContext, locale) {
        WorkspaceSpeechController(AndroidWorkspaceSpeechEngine(appContext), AndroidWorkspaceAudioFocus(appContext), locale)
    }
    DisposableEffect(controller, owner) {
        controller.start(rate, voiceName)
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) controller.onLifecycleStopped()
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.close()
        }
    }
    LaunchedEffect(controller, rate, voiceName) { controller.updatePreferences(rate, voiceName) }
    val snapshot = controller.snapshot
    val message = when (snapshot.status) {
        WorkspaceSpeechStatus.INITIALIZING -> R.string.workspace_speech_initializing
        WorkspaceSpeechStatus.READY -> null
        WorkspaceSpeechStatus.READING -> R.string.workspace_speech_reading
        WorkspaceSpeechStatus.STOPPED -> R.string.workspace_speech_stopped
        WorkspaceSpeechStatus.INTERRUPTED -> R.string.workspace_speech_interrupted
        WorkspaceSpeechStatus.MISSING_ENGINE -> R.string.workspace_speech_engine_missing
        WorkspaceSpeechStatus.ENGINE_ERROR -> R.string.workspace_speech_engine_error
        WorkspaceSpeechStatus.MISSING_VOICE -> R.string.workspace_speech_voice_missing
        WorkspaceSpeechStatus.VOICE_DATA_MISSING -> R.string.workspace_speech_voice_data
        WorkspaceSpeechStatus.UNSUPPORTED_LANGUAGE -> R.string.workspace_speech_language_unsupported
        WorkspaceSpeechStatus.SELECTED_VOICE_MISSING -> R.string.workspace_speech_selected_voice_missing
        WorkspaceSpeechStatus.FOCUS_DENIED -> R.string.workspace_speech_focus_denied
        WorkspaceSpeechStatus.SPEAK_ERROR -> R.string.workspace_speech_error
    }
    return WorkspaceSpeech(
        available = snapshot.status in setOf(WorkspaceSpeechStatus.READY, WorkspaceSpeechStatus.READING,
            WorkspaceSpeechStatus.STOPPED, WorkspaceSpeechStatus.INTERRUPTED, WorkspaceSpeechStatus.FOCUS_DENIED,
            WorkspaceSpeechStatus.SPEAK_ERROR),
        readingId = snapshot.readingId,
        voices = controller.voiceOptions.map { it.name to it.label },
        statusMessage = message,
        feedbackId = snapshot.feedbackId,
        read = controller::read,
        stop = controller::stop,
        retry = controller::retry,
    )
}

private class AndroidWorkspaceSpeechEngine(context: Context) : WorkspaceSpeechEngine {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var listener: WorkspaceSpeechEngineListener? = null
    override val maxSpeechInputLength: Int get() = TextToSpeech.getMaxSpeechInputLength()

    override fun hasEngineService(): Boolean = runCatching {
        appContext.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
    }.getOrDefault(false)

    override fun initialize(onInitialized: (Int) -> Unit) {
        shutdown()
        val created = TextToSpeech(appContext) { status -> handler.post { onInitialized(status) } }
        tts = created
        created.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) { handler.post { listener?.onDone(utteranceId) } }
            @Deprecated("Required platform callback")
            override fun onError(utteranceId: String?) { handler.post { listener?.onError(utteranceId) } }
            override fun onError(utteranceId: String?, errorCode: Int) { handler.post { listener?.onError(utteranceId) } }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { handler.post { listener?.onStopped(utteranceId) } }
        })
    }

    override fun setAudioAttributes(attributes: AudioAttributes): Int = tts?.setAudioAttributes(attributes) ?: TextToSpeech.ERROR
    override fun setLanguage(locale: Locale): Int = tts?.setLanguage(locale) ?: TextToSpeech.ERROR
    override fun voices(): List<InstalledWorkspaceVoice> = tts?.voices.orEmpty().map { voice ->
        val language = voice.locale.language
        val hasData = TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in voice.features.orEmpty()
        InstalledWorkspaceVoice(voice.name, "${voice.locale.displayName} · ${voice.name}", language, hasData)
    }
    override fun setVoice(name: String): Int {
        val voice = tts?.voices?.firstOrNull { it.name == name } ?: return TextToSpeech.ERROR
        return tts?.setVoice(voice) ?: TextToSpeech.ERROR
    }
    override fun setSpeechRate(rate: Float): Int = tts?.setSpeechRate(rate) ?: TextToSpeech.ERROR
    override fun speak(text: String, utteranceId: String): Int = tts?.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), utteranceId) ?: TextToSpeech.ERROR
    override fun stop() { tts?.stop() }
    override fun shutdown() { tts?.stop(); tts?.shutdown(); tts = null }
    override fun setListener(listener: WorkspaceSpeechEngineListener) { this.listener = listener }
}

private class AndroidWorkspaceAudioFocus(context: Context) : WorkspaceAudioFocus {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var currentRequest: AudioFocusRequest? = null

    override fun request(onLost: () -> Unit): Boolean {
        abandon()
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) onLost()
            }
            .build()
        currentRequest = request
        val result = runCatching { audioManager.requestAudioFocus(request) }.getOrDefault(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) currentRequest = null
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    override fun abandon() {
        val request = currentRequest ?: return
        runCatching { audioManager.abandonAudioFocusRequest(request) }
        currentRequest = null
    }
}
