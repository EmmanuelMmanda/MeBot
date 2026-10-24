package app.mynguli.alarm

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.Closeable
import java.util.Locale

/** Optional installed/offline speech. A tone remains the alarm even when speech is unavailable. */
class AlarmSpeech(
    context: Context,
    private val onSpeaking: (Boolean) -> Unit = {},
    private val onUnavailable: () -> Unit = {},
) : Closeable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var engine: TextToSpeech? = null
    private var generation = 0
    private var timeout: Runnable? = null

    fun speak(message: String, language: String) {
        stop()
        val token = generation
        timeout = Runnable { finish(token, failed = true) }.also { main.postDelayed(it, 5_000L) }
        engine = runCatching {
            TextToSpeech(context) { status -> main.post {
                val current = engine
                if (token != generation || current == null) return@post
                if (status != TextToSpeech.SUCCESS) { finish(token, true); return@post }
                val languageCode = if (language == "system") Locale.getDefault().language else language
                val locale = Locale.forLanguageTag(if (languageCode == "sw") "sw" else "en")
                val ready = runCatching {
                    val voices = current.voices.orEmpty().filter {
                        it.locale.language == locale.language && !it.isNetworkConnectionRequired &&
                            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
                    }
                    val voice = voices.firstOrNull { it.name == current.defaultVoice?.name }
                        ?: voices.maxByOrNull { it.quality }
                    voice != null && current.setVoice(voice) == TextToSpeech.SUCCESS &&
                        current.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) == TextToSpeech.SUCCESS
                }.getOrDefault(false)
                if (!ready) { finish(token, true); return@post }
                current.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { main.post {
                        if (token == generation && engine === current) {
                            timeout?.let(main::removeCallbacks)
                            timeout = Runnable { finish(token, true) }.also { main.postDelayed(it, 30_000L) }
                            onSpeaking(true)
                        }
                    } }
                    override fun onDone(id: String?) { main.post { finish(token, false) } }
                    @Deprecated("Android callback")
                    override fun onError(id: String?) { main.post { finish(token, true) } }
                    override fun onError(id: String?, errorCode: Int) { main.post { finish(token, true) } }
                })
                val result = runCatching {
                    current.speak(message, TextToSpeech.QUEUE_FLUSH,
                        Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }, "alarm-$token")
                }.getOrDefault(TextToSpeech.ERROR)
                if (result != TextToSpeech.SUCCESS) finish(token, true)
            } }
        }.getOrElse { finish(token, true); null }
    }

    private fun finish(token: Int, failed: Boolean) {
        if (token != generation) return
        stop()
        if (failed) onUnavailable()
    }

    fun stop() {
        generation++
        timeout?.let(main::removeCallbacks); timeout = null
        val current = engine; engine = null
        runCatching { current?.stop() }
        runCatching { current?.shutdown() }
        onSpeaking(false)
    }

    override fun close() = stop()
}
