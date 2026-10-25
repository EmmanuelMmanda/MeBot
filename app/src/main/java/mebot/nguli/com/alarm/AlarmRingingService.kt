package mebot.nguli.com.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import mebot.nguli.com.R
import mebot.nguli.com.core.AlarmDismissMode
import mebot.nguli.com.core.effectiveDismissMode

/** Playback belongs to an Android foreground service, not to a Compose screen's lifetime. */
class AlarmRingingService : Service() {
    private lateinit var controller: AlarmController
    private lateinit var audio: AudioManager
    private var player: AlarmTonePlayback? = null
    private var speech: AlarmSpeech? = null
    private var speaking = false
    private var vibrator: Vibrator? = null
    private var focusRequest: AudioFocusRequest? = null
    private var focusDucked = false
    private var focusPaused = false
    private var playingSessionId: String? = null
    private var playbackStartedElapsed: Long = 0
    private var lastPhase: AlarmPhase? = null
    private val handler = Handler(Looper.getMainLooper())
    private var tickScheduled = false

    private val tick = object : Runnable {
        override fun run() {
            val session = controller.session()
            if (session == null || !session.audible) {
                stopSelf()
                return
            }
            if (session.id != playingSessionId) startPlayback(session)
            updateVolume(session)
            if (lastPhase != session.phase) {
                lastPhase = session.phase
                if (session.phase == AlarmPhase.WALKING) speech?.stop()
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(session))
            }
            // The scheduled callback owns expiry. This is a redundant in-process recovery path.
            session.deadlineAtMillis?.takeIf { it <= System.currentTimeMillis() }?.let {
                controller.receiveContinuation(session.id, it)
            }
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate() {
        super.onCreate()
        controller = AlarmController(this)
        audio = getSystemService(AudioManager::class.java)
        vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator
        else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as Vibrator)
        val channel = NotificationChannel(CHANNEL_ID, alarmString(R.string.alarm_channel_name), NotificationManager.IMPORTANCE_HIGH).apply {
            description = alarmString(R.string.alarm_channel_description)
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = controller.session()
        if (session == null || !session.audible) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification(session), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIFICATION_ID, notification(session))
        } catch (failure: RuntimeException) {
            controller.log("foreground_unavailable", session.plan.id, failure.javaClass.simpleName)
            stopSelf()
            return START_NOT_STICKY
        }
        if (playingSessionId != session.id) startPlayback(session)
        updateVolume(session)
        if (!tickScheduled) {
            tickScheduled = true
            handler.post(tick)
        }
        return START_STICKY
    }

    private fun startPlayback(session: AlarmSession) {
        releasePlayback()
        playingSessionId = session.id
        playbackStartedElapsed = SystemClock.elapsedRealtime()
        controller.applyAlarmVolume(session.plan.volumePercent)
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener({ change ->
                // An abandoned request may dispatch after a different occurrence has started.
                if (playingSessionId != session.id) return@setOnAudioFocusChangeListener
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        focusPaused = true
                        player?.pause()
                        speech?.stop()
                        controller.log("audio_interrupted", controller.session()?.plan?.id ?: "")
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        focusPaused = false
                        focusDucked = false
                        controller.session()?.let(::updateVolume)
                        player?.resume()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        focusDucked = true
                        speech?.stop()
                        controller.session()?.let(::updateVolume)
                    }
                }
            }, handler).build()
        val granted = runCatching { audio.requestAudioFocus(requireNotNull(focusRequest)) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
        if (!granted) controller.log("audio_focus_not_granted", session.plan.id)
        player = AlarmTonePlayback(this,
            onFallback = { if (playingSessionId == session.id) controller.log("audio_fallback", session.plan.id) },
            onUnavailable = { if (playingSessionId == session.id) controller.log("no_audio_available", session.plan.id) })
        updateVolume(session)
        player?.play(session.plan)
        if (focusPaused) player?.pause()
        controller.log("playback_requested", session.plan.id)
        if (session.plan.speechEnabled) {
            speech = AlarmSpeech(this, onSpeaking = { active ->
                if (playingSessionId == session.id) {
                    speaking = active
                    controller.session()?.takeIf { it.id == session.id }?.let(::updateVolume)
                }
            }, onUnavailable = { if (playingSessionId == session.id) controller.log("speech_unavailable", session.plan.id) })
            handler.postDelayed({
                val current = controller.session()
                if (playingSessionId == session.id && current?.id == session.id && !focusPaused && !focusDucked &&
                    current.phase in setOf(AlarmPhase.RINGING, AlarmPhase.FOLLOWUP_RINGING))
                    speech?.speak(session.plan.speechMessage.trim().ifBlank { alarmString(R.string.alarm_audio_default_message) }, controller.language())
            }, maxOf(1_500L, session.plan.rampSeconds * 1_000L))
        }
        if (session.plan.vibrate) {
            runCatching { vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 650, 500, 1_200), 0)) }
        }
    }

    private fun updateVolume(session: AlarmSession) {
        val baseGain = if (session.plan.volumePercent == 0) 0f else if (session.phase == AlarmPhase.WALKING) 0.12f else {
            val ramp = session.plan.rampSeconds * 1_000L
            if (ramp == 0L) 1f else (0.20f + 0.80f * ((SystemClock.elapsedRealtime() - playbackStartedElapsed).toFloat() / ramp)).coerceIn(0.20f, 1f)
        }
        val gain = baseGain * (if (focusDucked) 0.15f else 1f) * (if (speaking) .25f else 1f)
        player?.setGain(gain)
    }

    private fun notification(session: AlarmSession): Notification {
        val title = session.plan.label.ifBlank { alarmString(R.string.alarm_generic_title) }
        val text = when (session.phase) {
            AlarmPhase.WALKING -> alarmString(R.string.alarm_go_checkpoint)
            AlarmPhase.FOLLOWUP_RINGING -> alarmString(R.string.alarm_notification_followup)
            else -> when (session.plan.effectiveDismissMode) {
                AlarmDismissMode.QR -> alarmString(R.string.alarm_scan_code)
                AlarmDismissMode.MATH -> alarmString(R.string.mebot_alarm_solve_to_stop)
                AlarmDismissMode.STANDARD -> alarmString(R.string.alarm_time_set)
            }
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true).setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setContentIntent(controller.screenIntent(session.id))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (controller.readiness().fullScreenAllowed) builder.setFullScreenIntent(controller.screenIntent(session.id), true)
        if (session.canSnooze) {
            builder.addAction(0, alarmString(R.string.alarm_snooze_action), actionIntent(session.id, AlarmController.ACTION_SNOOZE))
        }
        if (session.plan.effectiveDismissMode == AlarmDismissMode.STANDARD) {
            builder.addAction(0, alarmString(R.string.alarm_dismiss_action), actionIntent(session.id, AlarmController.ACTION_DISMISS))
        } else {
            // Challenge alarms open the task. Emergency stop stays available on that screen.
            builder.addAction(0, alarmString(R.string.mebot_alarm_open), controller.screenIntent(session.id))
        }
        return builder.build()
    }

    private fun actionIntent(sessionId: String, action: String): PendingIntent = PendingIntent.getBroadcast(this, 0,
        Intent(this, AlarmReceiver::class.java).setAction(action)
            .setData(Uri.parse("mebot://alarm-action/${Uri.encode(sessionId)}/${Uri.encode(action)}"))
            .putExtra(AlarmController.EXTRA_SESSION, sessionId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun releasePlayback() {
        speech?.close(); speech = null; speaking = false
        player?.close()
        player = null
        vibrator?.cancel()
        focusRequest?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focusRequest = null
        focusDucked = false
        focusPaused = false
        controller.restoreAudio()
        playingSessionId = null
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        tickScheduled = false
        releasePlayback()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "mebot_active_alarm_v1"
        private const val NOTIFICATION_ID = 3101
    }
}
