package mebot.nguli.com.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import mebot.nguli.com.core.AlarmDismissMode
import mebot.nguli.com.core.AlarmPlan
import mebot.nguli.com.core.MathDifficulty
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MathAlarmControllerTest {
    private lateinit var context: Context
    private lateinit var store: AlarmStore
    private lateinit var controller: AlarmController

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AlarmStore(context)
        store.clear()
        controller = AlarmController(context)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After fun cleanUp() { controller.clearAll() }

    private fun start(count: Int = 3): AlarmSession {
        controller.sync(listOf(AlarmPlan(id = "math", days = emptySet(), dismissMode = AlarmDismissMode.MATH, mathDifficulty = MathDifficulty.MEDIUM, mathQuestionCount = count)))
        controller.receiveAlarm("math", store.scheduled.single().token)
        return requireNotNull(controller.session())
    }

    @Test fun questionProgressAndInputContractSurviveNewController() {
        val first = start()
        val question = requireNotNull(first.mathChallenge?.current)
        assertEquals(MathAnswerResult.CORRECT, controller.submitMathAnswer(first.id, question.id, question.answer))
        val next = requireNotNull(AlarmController(context).session())
        assertEquals(first.id, next.id)
        assertEquals(first.mathChallenge?.questions, next.mathChallenge?.questions)
        assertEquals(1, next.mathChallenge?.answered)
        assertNotEquals(question.id, next.mathChallenge?.current?.id)
    }

    @Test fun wrongAnswerAndOrdinaryDismissCannotSilenceMathAlarm() {
        val session = start()
        val question = requireNotNull(session.mathChallenge?.current)
        assertEquals(MathAnswerResult.INCORRECT, controller.submitMathAnswer(session.id, question.id, question.answer + 1))
        assertFalse(controller.dismiss(session.id))
        assertEquals(session, controller.session())
        assertEquals(CheckpointResult.NOT_READY, controller.submitCheckpoint(session.id, "anything"))
        assertFalse(controller.beginWalk(session.id))
    }

    @Test fun staleQuestionAndSessionIdentifiersCannotAdvanceProgress() {
        val first = start()
        val question = requireNotNull(first.mathChallenge?.current)
        assertEquals(MathAnswerResult.NO_SESSION, controller.submitMathAnswer("old-session", question.id, question.answer))
        assertEquals(MathAnswerResult.CORRECT, controller.submitMathAnswer(first.id, question.id, question.answer))
        assertEquals(MathAnswerResult.STALE, controller.submitMathAnswer(first.id, question.id, question.answer))
        assertEquals(1, controller.session()?.mathChallenge?.answered)
    }

    @Test fun onlyAllConfiguredCorrectAnswersCompleteTheOccurrence() {
        val first = start(count = 5)
        requireNotNull(first.mathChallenge).questions.forEachIndexed { index, question ->
            val expected = if (index == 4) MathAnswerResult.COMPLETE else MathAnswerResult.CORRECT
            assertEquals(expected, controller.submitMathAnswer(first.id, question.id, question.answer))
        }
        assertNull(controller.session())
        assertTrue(controller.events().any { it.kind == "math_completed" })
        controller.sync(store.plans)
        assertTrue(store.scheduled.isEmpty())
    }

    @Test fun snoozeRetainsProgressAndRejectsAnswersUntilRingingResumes() {
        val first = start()
        val question = requireNotNull(first.mathChallenge?.current)
        controller.submitMathAnswer(first.id, question.id, question.answer)
        assertTrue(controller.snooze(first.id))
        val snoozed = requireNotNull(controller.session())
        val next = requireNotNull(snoozed.mathChallenge?.current)
        assertEquals(MathAnswerResult.NOT_READY, controller.submitMathAnswer(first.id, next.id, next.answer))
        val deadline = System.currentTimeMillis() - 1
        store.session = snoozed.copy(deadlineAtMillis = deadline)
        controller.receiveContinuation(first.id, deadline)
        assertEquals(1, controller.session()?.mathChallenge?.answered)
        assertEquals(MathAnswerResult.CORRECT, controller.submitMathAnswer(first.id, next.id, next.answer))
    }

    @Test fun testAlarmRetainsTheSelectedChallengeSettings() {
        controller.testAlarm(AlarmPlan(dismissMode = AlarmDismissMode.MATH, mathDifficulty = MathDifficulty.HARD, mathQuestionCount = 1))
        val scheduled = store.scheduled.single()
        controller.receiveAlarm(scheduled.plan.id, scheduled.token)
        val session = requireNotNull(controller.session())
        assertTrue(session.isTest)
        assertEquals(MathDifficulty.HARD, session.plan.mathDifficulty)
        assertEquals(1, session.mathChallenge?.questions?.size)
    }

    @Test fun emergencyStopRemainsAvailableDuringMathChallenge() {
        val session = start()
        controller.emergencyStop(session.id)
        assertNull(controller.session())
        assertTrue(controller.events().any { it.kind == "emergency_stopped" })
    }

    @Test fun operationalLegacyJsonDoesNotChangeExistingQrPlans() {
        val old = planToJson(AlarmPlan(id = "legacy", requireCheckpoint = true, checkpointCode = "hallway"))
        old.remove("dismissMode")
        old.remove("mathDifficulty")
        old.remove("mathQuestionCount")
        val plan = planFromJson(old)
        controller.sync(listOf(plan))
        controller.receiveAlarm(plan.id, store.scheduled.single().token)
        val current = requireNotNull(controller.session())
        assertNull(current.mathChallenge)
        assertFalse(controller.dismiss(current.id))
        assertEquals(CheckpointResult.WAITING, controller.submitCheckpoint(current.id, "hallway"))
    }
}
