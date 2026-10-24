package app.mynguli.ui.feed

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.mynguli.R
import java.util.Locale

internal data class WorkspaceSpeech(
    val available: Boolean,
    val readingId: String?,
    val read: (String, String) -> Unit,
    val stop: () -> Unit,
)

/** Phone speech services are independent of model capabilities and never start automatically. */
@Composable
internal fun rememberWorkspaceSpeech(): WorkspaceSpeech {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val owner = LocalLifecycleOwner.current
    var engine by remember { mutableStateOf<TextToSpeech?>(null) }
    var available by remember { mutableStateOf(false) }
    var readingId by remember { mutableStateOf<String?>(null) }
    var lastUtterance by remember { mutableStateOf<String?>(null) }
    val handler = remember { Handler(Looper.getMainLooper()) }
    DisposableEffect(context, locale) {
        var disposed = false
        var created: TextToSpeech? = null
        val installed = runCatching {
            context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0).isNotEmpty()
        }.getOrDefault(false)
        if (installed) {
            created = runCatching { TextToSpeech(context.applicationContext) { status ->
                handler.post {
                    if (!disposed && status == TextToSpeech.SUCCESS) {
                        available = runCatching { created?.setLanguage(locale)?.let { it >= TextToSpeech.LANG_AVAILABLE } == true }.getOrDefault(false)
                    }
                }
            } }.getOrNull()
            engine = created
            created?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { handler.post { if (!disposed && utteranceId == lastUtterance) readingId = null } }
                @Deprecated("Required platform callback")
                override fun onError(utteranceId: String?) { handler.post {
                    if (!disposed && readingId != null) {
                        readingId = null
                        Toast.makeText(context, R.string.workspace_voice_error, Toast.LENGTH_SHORT).show()
                    }
                } }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { handler.post { if (!disposed && utteranceId == lastUtterance) readingId = null } }
            })
        }
        onDispose {
            disposed = true
            available = false
            readingId = null
            engine = null
            created?.stop()
            created?.shutdown()
        }
    }
    DisposableEffect(owner, engine) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { engine?.stop(); readingId = null }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    return WorkspaceSpeech(available, readingId, read = { id, text ->
        val active = engine
        if (available && active != null && text.isNotBlank()) {
            val parts = text.chunked((TextToSpeech.getMaxSpeechInputLength() - 1).coerceAtLeast(500))
            val batch = System.nanoTime().toString()
            lastUtterance = "$batch:${parts.lastIndex}"
            readingId = id
            parts.forEachIndexed { index, part ->
                val result = active.speak(part, if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$batch:$index")
                if (result == TextToSpeech.ERROR) {
                    active.stop(); readingId = null
                    Toast.makeText(context, R.string.workspace_voice_error, Toast.LENGTH_SHORT).show()
                    return@WorkspaceSpeech
                }
            }
        }
    }, stop = { engine?.stop(); readingId = null })
}
