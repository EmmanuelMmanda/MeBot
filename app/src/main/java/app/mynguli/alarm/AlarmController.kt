package app.mynguli.alarm

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import app.mynguli.R
import app.mynguli.core.AlarmPlan
import app.mynguli.core.AlarmDismissMode
import app.mynguli.core.MathQuestions
import app.mynguli.core.effectiveDismissMode
import app.mynguli.core.nextAlarmOccurrence
import java.time.ZonedDateTime
import java.util.UUID

/** Public boundary used by the main application. It never depends on a running activity. */
class AlarmController(context: Context) {
    private val context = context.applicationContext
    private val store = AlarmStore(this.context)
    private val manager = this.context.getSystemService(AlarmManager::class.java)

    fun setLanguage(language: String) = synchronized(lock) { store.language = language }
    fun language(): String = synchronized(lock) { store.language }
    fun session(): AlarmSession? = synchronized(lock) { store.session }
    fun events(): List<AlarmEvent> = synchronized(lock) { store.events() }
    fun completedOneTimeIds(): Set<String> = synchronized(lock) { store.consumed.keys }

    fun readiness(): AlarmReadiness {
        val notifications = context.getSystemService(NotificationManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        return AlarmReadiness(
            exactAllowed = canSchedule(),
            notificationsAllowed = notifications.areNotificationsEnabled() &&
                (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED),
            fullScreenAllowed = Build.VERSION.SDK_INT < 34 || notifications.canUseFullScreenIntent(),
            alarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM),
            alarmVolumeMaximum = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            batteryOptimizationIgnored = power.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    /** Reconciles the persistent mirror and OS schedules without resetting an active occurrence. */
    fun sync(plans: List<AlarmPlan>): List<AlarmScheduleResult> = synchronized(lock) {
        require(plans.size <= 100) { "At most 100 alarms are supported" }
        require(plans.map { it.id }.distinct().size == plans.size) { "Alarm IDs must be unique" }
        plans.forEach(::validate)
        val previous = store.plans.associateBy { it.id }
        store.plans = plans
        val ids = plans.map { it.id }.toSet()
        store.consumed = store.consumed.filter { (id, signature) ->
            val plan = plans.find { it.id == id }
            plan != null && plan.enabled && plan.days.isEmpty() && signature == scheduleSignature(plan) && previous[id]?.enabled != false
        }
        val keep = store.scheduled.filter { scheduled ->
            val plan = plans.find { it.id == scheduled.plan.id }
            val valid = scheduled.isTest || (plan != null && plan.enabled && plan == scheduled.plan)
            if (!valid) cancelScheduledIntent(scheduled.plan.id)
            valid
        }
        store.scheduled = keep
        store.pending = store.pending.filter { it.isTest || it.plan.id in ids }
        store.session?.let { active ->
            if (!active.isTest && active.plan.id !in ids) finishInternal(active.id, "alarm_deleted", false)
        }
        val results = plans.filter { it.enabled }.map { plan ->
            if (plan.days.isEmpty() && store.consumed[plan.id] == scheduleSignature(plan)) {
                AlarmScheduleResult(plan.id, null)
            } else {
                val existing = store.scheduled.find { !it.isTest && it.plan.id == plan.id && it.atMillis > System.currentTimeMillis() }
                register(existing ?: nextSchedule(plan))
            }
        }
        if (store.session == null) restoreAudio()
        results
    }

    /** Explicit user cancellation also stops that alarm's current and queued occurrences. */
    fun cancel(id: String) = synchronized(lock) {
        cancelScheduledIntent(id)
        store.scheduled = store.scheduled.filterNot { it.plan.id == id }
        store.plans = store.plans.filterNot { it.id == id }
        store.pending = store.pending.filterNot { it.plan.id == id }
        store.consumed = store.consumed - id
        store.session?.takeIf { it.plan.id == id }?.let { finishInternal(it.id, "cancelled", false) }
        store.log("cancelled", id)
    }

    /** Disables the module, including tests, while keeping language and diagnostic history. */
    fun cancelAll() = synchronized(lock) {
        val hadAlarms = store.scheduled.isNotEmpty() || store.session != null || store.pending.isNotEmpty() || store.plans.isNotEmpty()
        stopAll()
        if (hadAlarms) store.log("module_disabled", "")
    }

    /** Removes the module's operational data and diagnostic history for erase or import. */
    fun clearAll() = synchronized(lock) {
        stopAll()
        store.clear()
    }

    private fun stopAll() {
        store.scheduled.forEach { cancelScheduledIntent(it.plan.id) }
        store.session?.let { cancelContinuation(it.id) }
        store.session = null
        store.pending = emptyList()
        store.scheduled = emptyList()
        store.plans = emptyList()
        store.consumed = emptyMap()
        context.stopService(Intent(context, AlarmRingingService::class.java))
        restoreAudio()
    }

    fun testAlarm(plan: AlarmPlan, delaySeconds: Int = 10): AlarmScheduleResult = synchronized(lock) {
        validate(plan)
        val test = plan.copy(id = TEST_PREFIX + UUID.randomUUID(), days = emptySet(), enabled = true)
        register(ScheduledAlarm(UUID.randomUUID().toString(), System.currentTimeMillis() + delaySeconds.coerceIn(3, 300) * 1_000L, test, true))
    }

    fun snooze(sessionId: String): Boolean = synchronized(lock) {
        val current = store.session?.takeIf { it.id == sessionId } ?: return@synchronized false
        val next = AlarmProtocol.snooze(current, System.currentTimeMillis()) ?: return@synchronized false
        if (!persistWithDeadline(next)) return@synchronized false
        store.log("snoozed", next.plan.id, "count=${next.snoozesUsed}")
        refreshPlayback()
        true
    }

    /** Returns false when the one-time reduced-volume interval is unavailable. Scanning remains possible. */
    fun beginWalk(sessionId: String): Boolean = synchronized(lock) {
        val current = store.session?.takeIf { it.id == sessionId } ?: return@synchronized false
        val next = AlarmProtocol.walk(current, System.currentTimeMillis())
        if (next == current || !persistWithDeadline(next)) return@synchronized false
        store.log("walk_window", current.plan.id)
        refreshPlayback()
        true
    }

    fun submitCheckpoint(sessionId: String, code: String): CheckpointResult = synchronized(lock) {
        val current = store.session?.takeIf { it.id == sessionId } ?: return@synchronized CheckpointResult.NO_SESSION
        val transition = AlarmProtocol.checkpoint(current, code, System.currentTimeMillis())
        when (transition.result) {
            CheckpointResult.COMPLETE -> finishInternal(current.id, "routine_completed", false)
            CheckpointResult.WAITING -> {
                if (!persistWithDeadline(requireNotNull(transition.session))) return@synchronized CheckpointResult.NOT_READY
                store.log("checkpoint_completed", current.plan.id, "follow_up_minutes=${current.plan.followUpMinutes}")
                refreshPlayback()
            }
            CheckpointResult.WRONG_CODE -> store.log("checkpoint_mismatch", current.plan.id)
            else -> Unit
        }
        transition.result
    }

    fun dismiss(sessionId: String): Boolean = synchronized(lock) {
        val current = store.session?.takeIf { it.id == sessionId } ?: return@synchronized false
        if (current.plan.effectiveDismissMode != AlarmDismissMode.STANDARD) return@synchronized false
        finishInternal(sessionId, "dismissed", false)
        true
    }

    /** Both IDs are checked under the same lock as persistence and completion. */
    fun submitMathAnswer(sessionId: String, questionId: String, answer: Int): MathAnswerResult = synchronized(lock) {
        val current = store.session?.takeIf { it.id == sessionId } ?: return@synchronized MathAnswerResult.NO_SESSION
        val transition = AlarmProtocol.mathAnswer(current, questionId, answer)
        when (transition.result) {
            MathAnswerResult.COMPLETE -> finishInternal(current.id, "math_completed", false)
            MathAnswerResult.CORRECT -> store.session = requireNotNull(transition.session)
            else -> Unit
        }
        transition.result
    }

    fun emergencyStop(sessionId: String) = synchronized(lock) {
        finishInternal(sessionId, "emergency_stopped", true)
    }

    internal fun receiveAlarm(alarmId: String, token: String) = synchronized(lock) {
        val occurrence = store.scheduled.find { it.plan.id == alarmId && it.token == token } ?: return@synchronized
        store.scheduled = store.scheduled.filterNot { it.token == token }
        if (!occurrence.isTest) {
            val currentPlan = store.plans.find { it.id == alarmId && it.enabled } ?: return@synchronized
            if (currentPlan.days.isEmpty()) store.consumed = store.consumed + (alarmId to scheduleSignature(currentPlan))
            else register(nextSchedule(currentPlan))
        }
        store.log("triggered", alarmId, "delay_ms=${(System.currentTimeMillis() - occurrence.atMillis).coerceAtLeast(0)}")
        if (store.session != null) {
            if (store.pending.none { it.token == token }) store.pending = store.pending + occurrence
            store.log("queued", alarmId)
        } else activate(occurrence)
    }

    internal fun receiveContinuation(sessionId: String, expectedDeadline: Long) = synchronized(lock) {
        val current = store.session?.takeIf { it.id == sessionId } ?: return@synchronized
        val next = AlarmProtocol.deadline(current, expectedDeadline, System.currentTimeMillis())
        if (next == current) return@synchronized
        store.session = next
        store.log(if (next.verificationStage == 1) "follow_up_ringing" else "ringing_resumed", next.plan.id)
        refreshPlayback()
    }

    internal fun resumeSession(sessionId: String) = synchronized(lock) {
        if (store.session?.id == sessionId && store.session?.audible == true) refreshPlayback()
    }

    /** Boot only re-registers exact alarms; it does not start a media service from a boot receiver. */
    fun restore(reason: String = "restore") = synchronized(lock) {
        store.log("restoring", "", reason)
        val now = System.currentTimeMillis()
        val stale = store.scheduled.filter { it.atMillis <= now }
        val catchUp = stale.filter { now - it.atMillis <= MISSED_RECOVERY_MILLIS }
        val catchUpIds = catchUp.map { it.plan.id }.toSet()
        store.scheduled = store.scheduled.filter { it.atMillis > now }
        val originalPlans = store.plans
        // Exclude recent missed occurrences from normal rescheduling until their callback consumes them.
        originalPlans.filter { it.enabled && it.id !in catchUpIds }.forEach { plan ->
            if (!(plan.days.isEmpty() && store.consumed[plan.id] == scheduleSignature(plan))) {
                register(nextSchedule(plan))
            }
        }
        catchUp.forEach { register(it.copy(atMillis = now + 1_500L)) }
        store.scheduled.filter { it.isTest && it.atMillis > now }.forEach(::register)
        stale.filter { it.plan.id !in catchUpIds }.forEach {
            store.log("missed_while_unavailable", it.plan.id)
            if (!it.isTest && it.plan.days.isEmpty()) {
                cancelScheduledIntent(it.plan.id)
                store.scheduled = store.scheduled.filterNot { scheduled -> scheduled.plan.id == it.plan.id }
                store.consumed = store.consumed + (it.plan.id to scheduleSignature(it.plan))
            }
        }
        val active = store.session
        if (active != null && now - active.startedAtMillis > SESSION_RECOVERY_MILLIS) {
            finishInternal(active.id, "expired_after_interruption", true)
        } else if (active != null) {
            if (active.deadlineAtMillis != null) {
                val adjusted = if (active.bootCount == currentBootCount() && active.deadlineElapsedMillis != null) {
                    active.copy(deadlineAtMillis = now + (active.deadlineElapsedMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                } else active
                store.session = adjusted
                armContinuation(adjusted)
            } else if (active.audible) {
                armResume(active.id, now + 1_500L)
            }
        } else restoreAudio()
    }

    internal fun log(kind: String, alarmId: String, detail: String = "") = synchronized(lock) { store.log(kind, alarmId, detail) }

    internal fun applyAlarmVolume(percent: Int) = synchronized(lock) {
        val audio = context.getSystemService(AudioManager::class.java)
        if (percent == 0) return@synchronized
        try {
            if (store.originalVolume == null) store.originalVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val desired = ((audio.getStreamMaxVolume(AudioManager.STREAM_ALARM) * percent.coerceIn(1, 100)) / 100f).toInt().coerceAtLeast(1)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, desired, 0)
            store.appliedVolume = desired
        } catch (failure: RuntimeException) {
            store.log("volume_unavailable", store.session?.plan?.id ?: "", failure.javaClass.simpleName)
        }
    }

    internal fun restoreAudio() = synchronized(lock) {
        val audio = context.getSystemService(AudioManager::class.java)
        val original = store.originalVolume
        val applied = store.appliedVolume
        try {
            // A manual hardware-volume change made during the alarm belongs to the user.
            if (original != null && applied != null && audio.getStreamVolume(AudioManager.STREAM_ALARM) == applied) {
                audio.setStreamVolume(AudioManager.STREAM_ALARM, original, 0)
            }
        } catch (_: RuntimeException) {
            store.log("volume_restore_unavailable", "")
        } finally {
            store.originalVolume = null
            store.appliedVolume = null
        }
    }

    private fun activate(occurrence: ScheduledAlarm) {
        store.session = AlarmSession(plan = occurrence.plan, occurrenceId = occurrence.token,
            startedAtMillis = System.currentTimeMillis(), bootCount = currentBootCount(), isTest = occurrence.isTest,
            mathChallenge = if (occurrence.plan.effectiveDismissMode == AlarmDismissMode.MATH) {
                MathQuestions.create(occurrence.plan.mathDifficulty, occurrence.plan.mathQuestionCount)
            } else null)
        refreshPlayback()
    }

    private fun finishInternal(sessionId: String, result: String, clearQueue: Boolean) {
        val active = store.session?.takeIf { it.id == sessionId } ?: return
        cancelContinuation(sessionId)
        store.session = null
        store.log(result, active.plan.id)
        if (clearQueue) store.pending = emptyList()
        val next = store.pending.firstOrNull()
        if (next != null) {
            store.pending = store.pending.drop(1)
            activate(next)
        } else {
            context.stopService(Intent(context, AlarmRingingService::class.java))
            restoreAudio()
        }
    }

    private fun persistWithDeadline(next: AlarmSession): Boolean {
        if (!canSchedule()) {
            store.log("exact_permission_missing", next.plan.id)
            return false
        }
        val previous = store.session
        val withClock = next.copy(
            deadlineElapsedMillis = next.deadlineAtMillis?.let { SystemClock.elapsedRealtime() + (it - System.currentTimeMillis()).coerceAtLeast(0) },
            bootCount = currentBootCount(),
        )
        store.session = withClock
        return if (armContinuation(withClock)) true else {
            store.session = previous
            false
        }
    }

    private fun armContinuation(session: AlarmSession): Boolean {
        val deadline = session.deadlineAtMillis ?: return false
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_CONTINUE)
            .setData(Uri.parse("mynguli://alarm-session/${Uri.encode(session.id)}/continue"))
            .putExtra(EXTRA_SESSION, session.id).putExtra(EXTRA_DEADLINE, deadline)
        val pending = PendingIntent.getBroadcast(context, 0, intent, FLAGS)
        return try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(deadline.coerceAtLeast(System.currentTimeMillis() + 500), screenIntent(session.id)), pending)
            true
        } catch (failure: RuntimeException) {
            store.log("continuation_not_scheduled", session.plan.id, failure.javaClass.simpleName)
            false
        }
    }

    private fun armResume(sessionId: String, at: Long) {
        if (!canSchedule()) return
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_RESUME)
            .setData(Uri.parse("mynguli://alarm-session/${Uri.encode(sessionId)}/resume"))
            .putExtra(EXTRA_SESSION, sessionId)
        runCatching {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, screenIntent(sessionId)), PendingIntent.getBroadcast(context, 0, intent, FLAGS))
        }.onFailure { store.log("session_resume_unavailable", "", it.javaClass.simpleName) }
    }

    private fun cancelContinuation(sessionId: String) {
        listOf(ACTION_CONTINUE to "continue", ACTION_RESUME to "resume").forEach { (action, suffix) ->
            val intent = Intent(context, AlarmReceiver::class.java).setAction(action)
                .setData(Uri.parse("mynguli://alarm-session/${Uri.encode(sessionId)}/$suffix"))
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pending != null) { manager.cancel(pending); pending.cancel() }
        }
    }

    private fun nextSchedule(plan: AlarmPlan): ScheduledAlarm = ScheduledAlarm(
        UUID.randomUUID().toString(), nextAlarmOccurrence(plan, ZonedDateTime.now()).toInstant().toEpochMilli(), plan,
    )

    private fun register(scheduled: ScheduledAlarm): AlarmScheduleResult {
        if (!canSchedule()) {
            store.log("exact_permission_missing", scheduled.plan.id)
            return AlarmScheduleResult(scheduled.plan.id, null, context.alarmString(R.string.alarm_exact_permission_needed))
        }
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE)
            .setData(Uri.parse("mynguli://alarm/${Uri.encode(scheduled.plan.id)}"))
            .putExtra(EXTRA_ALARM, scheduled.plan.id).putExtra(EXTRA_TOKEN, scheduled.token)
        val pending = PendingIntent.getBroadcast(context, 0, intent, FLAGS)
        store.scheduled = store.scheduled.filterNot { it.plan.id == scheduled.plan.id } + scheduled
        return try {
            val show = PendingIntent.getActivity(context, 0,
                Intent().setClassName(context, "app.mynguli.MainActivity").setAction("app.mynguli.OPEN_ALARMS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), FLAGS)
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(scheduled.atMillis, show), pending)
            AlarmScheduleResult(scheduled.plan.id, scheduled.atMillis)
        } catch (failure: RuntimeException) {
            store.scheduled = store.scheduled.filterNot { it.token == scheduled.token }
            store.log("schedule_failed", scheduled.plan.id, failure.javaClass.simpleName)
            AlarmScheduleResult(scheduled.plan.id, null, context.alarmString(R.string.alarm_schedule_failed))
        }
    }

    private fun cancelScheduledIntent(alarmId: String) {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_FIRE)
            .setData(Uri.parse("mynguli://alarm/${Uri.encode(alarmId)}"))
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pending != null) { manager.cancel(pending); pending.cancel() }
    }

    private fun refreshPlayback() {
        val active = store.session
        if (active == null || !active.audible) {
            context.stopService(Intent(context, AlarmRingingService::class.java))
            restoreAudio()
            return
        }
        try {
            context.startForegroundService(Intent(context, AlarmRingingService::class.java))
        } catch (failure: RuntimeException) {
            store.log("playback_start_failed", active.plan.id, failure.javaClass.simpleName)
        }
    }

    internal fun screenIntent(sessionId: String): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, AlarmActivity::class.java).setData(Uri.parse("mynguli://alarm-session/${Uri.encode(sessionId)}"))
            .putExtra(EXTRA_SESSION, sessionId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), FLAGS)

    private fun canSchedule(): Boolean = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
    private fun currentBootCount(): Int = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    private fun validate(plan: AlarmPlan) {
        app.mynguli.core.AlarmAudioSettings.validate(plan)
        require(plan.id.isNotBlank() && plan.id.length <= 160)
        require(plan.hour in 0..23 && plan.minute in 0..59)
        require(plan.days.all { it in 1..7 })
        require(plan.snoozeMinutes in 1..30 && plan.maxSnoozes in 0..5)
        require(plan.followUpMinutes in 0..30 && plan.volumePercent in 1..100 && plan.rampSeconds in 0..120)
        require(plan.mathQuestionCount in 1..5)
        require(plan.effectiveDismissMode != AlarmDismissMode.QR || plan.checkpointCode.isNotBlank()) { context.alarmString(R.string.alarm_checkpoint_required) }
    }

    private fun scheduleSignature(plan: AlarmPlan): String = "${plan.hour}:${plan.minute}:${plan.days.sorted().joinToString(",")}"

    companion object {
        internal val lock = Any()
        internal const val ACTION_FIRE = "app.mynguli.alarm.FIRE"
        internal const val ACTION_CONTINUE = "app.mynguli.alarm.CONTINUE"
        internal const val ACTION_RESUME = "app.mynguli.alarm.RESUME"
        internal const val ACTION_SNOOZE = "app.mynguli.alarm.SNOOZE"
        internal const val ACTION_EMERGENCY = "app.mynguli.alarm.EMERGENCY"
        internal const val ACTION_DISMISS = "app.mynguli.alarm.DISMISS"
        internal const val EXTRA_ALARM = "alarm_id"
        internal const val EXTRA_TOKEN = "occurrence_token"
        internal const val EXTRA_SESSION = "session_id"
        internal const val EXTRA_DEADLINE = "expected_deadline"
        private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        private const val TEST_PREFIX = "test-"
        private const val MISSED_RECOVERY_MILLIS = 15 * 60_000L
        private const val SESSION_RECOVERY_MILLIS = 60 * 60_000L
    }
}
