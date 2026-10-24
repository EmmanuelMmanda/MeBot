package app.mynguli.alarm

import app.mynguli.core.AlarmPlan
import org.junit.Assert.*
import org.junit.Test

class AlarmProtocolTest {
    private val now = 1_700_000_000_000L
    private val plan = AlarmPlan(id = "alarm-a", requireCheckpoint = true, checkpointCode = "test-marker", followUpMinutes = 3, maxSnoozes = 1)
    private fun active() = AlarmSession(id = "session-a", plan = plan, occurrenceId = "occurrence-a", startedAtMillis = now)

    @Test fun snoozeAllowanceSurvivesResumedRinging() {
        val snoozed = requireNotNull(AlarmProtocol.snooze(active(), now))
        assertEquals(1, snoozed.snoozesUsed)
        assertEquals(now + 180_000L, snoozed.deadlineAtMillis)
        val resumed = AlarmProtocol.deadline(snoozed.copy(), requireNotNull(snoozed.deadlineAtMillis), now + 180_000L)
        assertEquals(AlarmPhase.RINGING, resumed.phase)
        assertNull(AlarmProtocol.snooze(resumed, now + 180_000L))
    }

    @Test fun zeroSnoozesMeansNoRoutineSnooze() {
        assertNull(AlarmProtocol.snooze(active().copy(plan = plan.copy(maxSnoozes = 0)), now))
    }

    @Test fun followupDoesNotOfferAnotherSnooze() {
        val followup = active().copy(phase = AlarmPhase.FOLLOWUP_RINGING, verificationStage = 1)
        assertFalse(followup.canSnooze)
        assertNull(AlarmProtocol.snooze(followup, now))
    }

    @Test fun walkWindowCannotBecomeUnlimitedSnoozing() {
        val walking = AlarmProtocol.walk(active(), now)
        assertTrue(walking.walkUsed)
        assertEquals(now + 60_000L, walking.deadlineAtMillis)
        val resumed = AlarmProtocol.deadline(walking, now + 60_000L, now + 60_000L)
        assertEquals(resumed, AlarmProtocol.walk(resumed, now + 61_000L))
        assertEquals(AlarmPhase.RINGING, resumed.phase)
        assertNull(resumed.deadlineAtMillis)
    }

    @Test fun tappingWalkAgainDoesNotExtendItsDeadline() {
        val walking = AlarmProtocol.walk(active(), now)
        assertEquals(walking, AlarmProtocol.walk(walking, now + 50_000L))
    }

    @Test fun incorrectMarkerDoesNotStopSoundOrAdvance() {
        val result = AlarmProtocol.checkpoint(active(), "some-other-code", now)
        assertEquals(CheckpointResult.WRONG_CODE, result.result)
        assertEquals(active(), result.session)
    }

    @Test fun firstCheckpointSchedulesASeparateFollowup() {
        val result = AlarmProtocol.checkpoint(active(), "test-marker", now)
        assertEquals(CheckpointResult.WAITING, result.result)
        assertEquals(AlarmPhase.WAITING_FOLLOWUP, result.session?.phase)
        assertEquals(now + 180_000L, result.session?.deadlineAtMillis)
        assertEquals(1, result.session?.verificationStage)
    }

    @Test fun repeatingFirstScanCannotSkipTheWaitingPeriod() {
        val waiting = requireNotNull(AlarmProtocol.checkpoint(active(), "test-marker", now).session)
        val tooEarly = AlarmProtocol.checkpoint(waiting, "test-marker", now + 1_000L)
        assertEquals(CheckpointResult.NOT_READY, tooEarly.result)
        assertEquals(waiting, tooEarly.session)
    }

    @Test fun secondCheckpointFinishesOnlyAfterFollowupRings() {
        val waiting = requireNotNull(AlarmProtocol.checkpoint(active(), "test-marker", now).session)
        val followup = AlarmProtocol.deadline(waiting, now + 180_000L, now + 180_000L)
        assertEquals(AlarmPhase.FOLLOWUP_RINGING, followup.phase)
        val completed = AlarmProtocol.checkpoint(followup, "test-marker", now + 181_000L)
        assertEquals(CheckpointResult.COMPLETE, completed.result)
        assertNull(completed.session)
    }

    @Test fun configuredZeroFollowupCompletesAfterFirstMarker() {
        val result = AlarmProtocol.checkpoint(active().copy(plan = plan.copy(followUpMinutes = 0)), "test-marker", now)
        assertEquals(CheckpointResult.COMPLETE, result.result)
        assertNull(result.session)
    }

    @Test fun obsoleteCallbackCannotChangeAReplacementDeadline() {
        val snoozed = requireNotNull(AlarmProtocol.snooze(active(), now))
        assertEquals(snoozed, AlarmProtocol.deadline(snoozed, now + 60_000L, now + 180_000L))
    }

    @Test fun earlyCallbackDoesNotStartFollowup() {
        val waiting = requireNotNull(AlarmProtocol.checkpoint(active(), "test-marker", now).session)
        assertEquals(waiting, AlarmProtocol.deadline(waiting, now + 180_000L, now + 179_999L))
    }

    @Test fun walkExpiryPreservesFollowupStage() {
        val followup = active().copy(phase = AlarmPhase.FOLLOWUP_RINGING, verificationStage = 1)
        val walking = AlarmProtocol.walk(followup, now)
        val resumed = AlarmProtocol.deadline(walking, now + 60_000L, now + 60_000L)
        assertEquals(AlarmPhase.FOLLOWUP_RINGING, resumed.phase)
    }

    @Test fun countdownRoundsUpAndNeverGoesNegative() {
        assertEquals("01:00", formatRemaining(59_001))
        assertEquals("00:01", formatRemaining(1))
        assertEquals("00:00", formatRemaining(-1))
    }
}
