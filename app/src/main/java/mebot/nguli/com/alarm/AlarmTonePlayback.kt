package mebot.nguli.com.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.CancellationSignal
import android.os.Looper
import android.os.PowerManager
import mebot.nguli.com.R
import mebot.nguli.com.core.AlarmAudioSettings
import mebot.nguli.com.core.AlarmPlan
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

internal interface AlarmToneHandle : Closeable {
    fun start(): Boolean
    fun pause()
    fun gain(value: Float)
}

internal interface AlarmToneLoader : Closeable {
    fun bundled(id: String, onError: (AlarmToneHandle) -> Unit): AlarmToneHandle?
    fun external(uri: Uri, onReady: (AlarmToneHandle) -> Unit, onFailure: () -> Unit,
        onError: (AlarmToneHandle) -> Unit): Closeable
}

/** A bundled tone is audible while external audio prepares. No failed source can remove it. */
class AlarmTonePlayback internal constructor(
    private val loader: AlarmToneLoader,
    private val onFallback: () -> Unit,
    private val onUnavailable: () -> Unit,
) : Closeable {
    constructor(context: Context, onFallback: () -> Unit = {}, onUnavailable: () -> Unit = {}) :
        this(AndroidAlarmToneLoader(context.applicationContext), onFallback, onUnavailable)

    private var generation = 0
    private var active: AlarmToneHandle? = null
    private var pending: Closeable? = null
    private var gain = 1f
    private var paused = false
    private var activeBundledId: String? = null
    private val failedBundled = mutableSetOf<String>()

    fun play(plan: AlarmPlan) {
        stop()
        paused = false
        failedBundled.clear()
        val token = generation
        if (plan.soundId in AlarmAudioSettings.bundled) {
            if (!useBundled(plan.soundId, token)) {
                if (fallback(token)) onFallback()
            }
            return
        }
        fallback(token)
        val uri = if (AlarmAudioSettings.isContentUri(plan.soundUri)) Uri.parse(plan.soundUri)
            else if (plan.soundId == "system") RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            else null
        if (uri == null) { if (active != null) onFallback(); return }
        pending = runCatching { loader.external(uri, onReady = { output ->
            if (token != generation) output.close()
            else {
                pending = null
                if (!activate(output, null)) {
                    output.close()
                    if (active != null) onFallback() else onUnavailable()
                }
            }
        }, onFailure = {
            if (token == generation) { pending = null; if (active != null) onFallback() }
        }, onError = { output -> failedActive(output, token) }) }.getOrElse {
            if (active != null) onFallback()
            null
        }
    }

    private fun useBundled(id: String, token: Int): Boolean {
        if (id in failedBundled) return false
        val output = runCatching { loader.bundled(id) { failedActive(it, token) } }.getOrNull()
        if (output != null && activate(output, id)) return true
        failedBundled += id
        output?.close()
        return false
    }

    private fun fallback(token: Int): Boolean {
        if (token != generation) return false
        val available = useBundled(AlarmAudioSettings.DEFAULT_SOUND, token) || useBundled(AlarmAudioSettings.LEGACY_SOUND, token)
        if (!available) onUnavailable()
        return available
    }

    private fun failedActive(output: AlarmToneHandle, token: Int) {
        if (token != generation || active !== output) return
        activeBundledId?.let(failedBundled::add)
        active = null
        activeBundledId = null
        output.close()
        if (fallback(token)) onFallback()
    }

    private fun activate(output: AlarmToneHandle, bundledId: String?): Boolean {
        output.gain(gain)
        if (!paused && !output.start()) return false
        val previous = active
        active = output
        activeBundledId = bundledId
        previous?.close()
        return true
    }

    fun setGain(value: Float) { gain = value.coerceIn(0f, 1f); active?.gain(gain) }
    fun pause() { paused = true; active?.pause() }
    fun resume() {
        paused = false
        val output = active ?: return
        if (!output.start()) failedActive(output, generation)
    }
    fun stop() {
        generation++
        pending?.close(); pending = null
        active?.close(); active = null
        activeBundledId = null
    }
    override fun close() { stop(); loader.close() }
}

private class AndroidAlarmToneLoader(private val context: Context) : AlarmToneLoader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()

    override fun bundled(id: String, onError: (AlarmToneHandle) -> Unit): AlarmToneHandle? {
        val resource = when (id) {
            "pulse" -> R.raw.alarm_pulse; "chime" -> R.raw.alarm_chime
            "bell" -> R.raw.alarm_bell; else -> R.raw.alarm_tone
        }
        var output: MediaToneHandle? = null
        return runCatching {
            val created = MediaToneHandle(MediaPlayer()).also { output = it }
            configure(created.media)
            context.resources.openRawResourceFd(resource).use { descriptor ->
                created.media.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            }
            created.media.prepare()
            created.media.setOnErrorListener { _, _, _ -> onError(created); true }
            created
        }.getOrElse { output?.close(); null }
    }

    override fun external(uri: Uri, onReady: (AlarmToneHandle) -> Unit, onFailure: () -> Unit,
        onError: (AlarmToneHandle) -> Unit): Closeable {
        // MediaPlayer is created and accessed only on the main Looper. A provider may
        // open a cloud-backed document slowly, so only that blocking open runs on IO.
        var output: MediaToneHandle? = null
        val cancelled = AtomicBoolean(false)
        val openCancellation = CancellationSignal()
        var delivered = false
        var job: Job? = null
        val timeout = Runnable {
            if (cancelled.compareAndSet(false, true)) {
                runCatching { openCancellation.cancel() }; job?.cancel(); output?.close(); onFailure()
            }
        }
        fun fail() {
            main.post {
                if (cancelled.compareAndSet(false, true)) {
                    main.removeCallbacks(timeout); output?.close(); onFailure()
                }
            }
        }
        main.postDelayed(timeout, 4_000L)
        job = scope.launch {
            try {
                val descriptor = context.contentResolver.openAssetFileDescriptor(uri, "r", openCancellation)
                if (descriptor == null) { fail(); return@launch }
                if (!isActive || cancelled.get()) { descriptor.close(); return@launch }
                main.post {
                    if (cancelled.get()) { runCatching { descriptor.close() }; return@post }
                    try {
                        val created = MediaToneHandle(MediaPlayer()).also { output = it }
                        configure(created.media)
                        created.media.setOnPreparedListener {
                            if (cancelled.get() || created.closed.get()) created.close()
                            else {
                                main.removeCallbacks(timeout)
                                delivered = true
                                onReady(created)
                            }
                        }
                        created.media.setOnErrorListener { _, _, _ ->
                            if (delivered && !cancelled.get()) onError(created) else fail()
                            true
                        }
                        descriptor.use {
                            if (it.declaredLength < 0) created.media.setDataSource(it.fileDescriptor)
                            else created.media.setDataSource(it.fileDescriptor, it.startOffset, it.declaredLength)
                        }
                        created.media.prepareAsync()
                    } catch (_: Exception) { runCatching { descriptor.close() }; fail() }
                }
            } catch (_: CancellationException) { /* Cancellation owns no MediaPlayer on IO. */ }
            catch (_: Exception) { fail() }
        }
        return Closeable {
            cancelled.set(true); main.removeCallbacks(timeout); runCatching { openCancellation.cancel() }; job?.cancel(); output?.close()
        }
    }

    private fun configure(media: MediaPlayer) {
        media.setAudioAttributes(attributes)
        media.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
        media.isLooping = true
        media.setVolume(.15f, .15f)
    }

    override fun close() { scope.cancel() }
}

private class MediaToneHandle(val media: MediaPlayer) : AlarmToneHandle {
    val closed = AtomicBoolean(false)
    override fun start(): Boolean = !closed.get() && runCatching { media.start(); true }.getOrDefault(false)
    override fun pause() { if (!closed.get()) runCatching { media.pause() } }
    override fun gain(value: Float) { if (!closed.get()) runCatching { media.setVolume(value, value) } }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { media.setOnPreparedListener(null); media.setOnErrorListener(null) }
        runCatching { media.stop() }
        runCatching { media.release() }
    }
}
