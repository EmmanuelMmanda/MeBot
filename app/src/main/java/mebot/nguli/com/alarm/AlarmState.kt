package mebot.nguli.com.alarm

import mebot.nguli.com.core.AlarmPlan
import mebot.nguli.com.core.AlarmDismissMode
import mebot.nguli.com.core.MathChallenge
import mebot.nguli.com.core.effectiveDismissMode
import java.util.UUID

enum class AlarmPhase { RINGING, WALKING, SNOOZED, WAITING_FOLLOWUP, FOLLOWUP_RINGING }

/** A persisted occurrence. A screen restart never creates a fresh snooze allowance. */
data class AlarmSession(
    val id: String = UUID.randomUUID().toString(),
    val plan: AlarmPlan,
    val occurrenceId: String,
    val startedAtMillis: Long,
    val phase: AlarmPhase = AlarmPhase.RINGING,
    val snoozesUsed: Int = 0,
    val walkUsed: Boolean = false,
    val verificationStage: Int = 0,
    val deadlineAtMillis: Long? = null,
    val deadlineElapsedMillis: Long? = null,
    val bootCount: Int = -1,
    val isTest: Boolean = false,
    val mathChallenge: MathChallenge? = null,
) {
    val audible: Boolean get() = phase in setOf(AlarmPhase.RINGING, AlarmPhase.FOLLOWUP_RINGING, AlarmPhase.WALKING)
    val canSnooze: Boolean get() = phase == AlarmPhase.RINGING && verificationStage == 0 && snoozesUsed < plan.maxSnoozes
}

data class AlarmReadiness(
    val exactAllowed: Boolean,
    val notificationsAllowed: Boolean,
    val fullScreenAllowed: Boolean,
    val alarmVolume: Int,
    val alarmVolumeMaximum: Int,
    val batteryOptimizationIgnored: Boolean,
)

data class AlarmEvent(val atMillis: Long, val kind: String, val alarmId: String, val detail: String = "")

data class AlarmScheduleResult(val alarmId: String, val scheduledAtMillis: Long?, val error: String? = null)

enum class CheckpointResult { WAITING, COMPLETE, WRONG_CODE, NOT_READY, NO_SESSION }
enum class MathAnswerResult { CORRECT, COMPLETE, INCORRECT, NOT_READY, STALE, NO_SESSION }

internal data class CheckpointTransition(val result: CheckpointResult, val session: AlarmSession?)
internal data class MathAnswerTransition(val result: MathAnswerResult, val session: AlarmSession?)

/** Pure policy. Android delivery and storage are intentionally outside these transitions. */
internal object AlarmProtocol {
    const val WALK_WINDOW_MILLIS = 60_000L

    fun snooze(session: AlarmSession, now: Long): AlarmSession? {
        if (!session.canSnooze) return null
        return session.copy(
            phase = AlarmPhase.SNOOZED,
            snoozesUsed = session.snoozesUsed + 1,
            deadlineAtMillis = now + session.plan.snoozeMinutes * 60_000L,
        )
    }

    fun walk(session: AlarmSession, now: Long): AlarmSession {
        if (session.plan.effectiveDismissMode != AlarmDismissMode.QR || !session.audible || session.walkUsed) return session
        return session.copy(phase = AlarmPhase.WALKING, walkUsed = true, deadlineAtMillis = now + WALK_WINDOW_MILLIS)
    }

    fun deadline(session: AlarmSession, expectedDeadline: Long, now: Long): AlarmSession {
        if (session.deadlineAtMillis != expectedDeadline || now < expectedDeadline) return session
        return session.copy(
            phase = if (session.verificationStage == 1) AlarmPhase.FOLLOWUP_RINGING else AlarmPhase.RINGING,
            deadlineAtMillis = null,
            deadlineElapsedMillis = null,
        )
    }

    fun checkpoint(session: AlarmSession, code: String, now: Long): CheckpointTransition {
        if (session.plan.effectiveDismissMode != AlarmDismissMode.QR || !session.audible) return CheckpointTransition(CheckpointResult.NOT_READY, session)
        if (session.plan.checkpointCode.isBlank() || code != session.plan.checkpointCode) {
            return CheckpointTransition(CheckpointResult.WRONG_CODE, session)
        }
        if (session.verificationStage == 1 || session.plan.followUpMinutes == 0) {
            return CheckpointTransition(CheckpointResult.COMPLETE, null)
        }
        return CheckpointTransition(
            CheckpointResult.WAITING,
            session.copy(
                phase = AlarmPhase.WAITING_FOLLOWUP,
                verificationStage = 1,
                deadlineAtMillis = now + session.plan.followUpMinutes * 60_000L,
            ),
        )
    }

    fun mathAnswer(session: AlarmSession, questionId: String, answer: Int): MathAnswerTransition {
        if (session.plan.effectiveDismissMode != AlarmDismissMode.MATH || !session.audible) {
            return MathAnswerTransition(MathAnswerResult.NOT_READY, session)
        }
        val challenge = session.mathChallenge ?: return MathAnswerTransition(MathAnswerResult.NOT_READY, session)
        val question = challenge.current ?: return MathAnswerTransition(MathAnswerResult.NOT_READY, session)
        // A queued click from the previous question must never answer the next one.
        if (question.id != questionId) return MathAnswerTransition(MathAnswerResult.STALE, session)
        if (question.answer != answer) return MathAnswerTransition(MathAnswerResult.INCORRECT, session)
        val next = challenge.copy(answered = challenge.answered + 1)
        return if (next.complete) MathAnswerTransition(MathAnswerResult.COMPLETE, null)
        else MathAnswerTransition(MathAnswerResult.CORRECT, session.copy(mathChallenge = next))
    }
}
