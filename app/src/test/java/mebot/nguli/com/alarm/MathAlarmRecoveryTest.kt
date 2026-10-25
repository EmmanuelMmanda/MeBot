package mebot.nguli.com.alarm

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import mebot.nguli.com.core.AlarmDismissMode
import mebot.nguli.com.core.AlarmPlan
import mebot.nguli.com.core.MathDifficulty
import mebot.nguli.com.core.MathQuestions
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MathAlarmRecoveryTest {
    private lateinit var context: Context
    private lateinit var store: AlarmStore
    private lateinit var controller: AlarmController

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AlarmStore(context)
        store.clear()
        controller = AlarmController(context)
    }

    @After fun cleanUp() { controller.clearAll() }

    private fun ringing() = AlarmSession(
        id = "recovery-session",
        plan = AlarmPlan(id = "math", dismissMode = AlarmDismissMode.MATH),
        occurrenceId = "occurrence",
        startedAtMillis = 1_792_540_800_000L,
        mathChallenge = MathQuestions.create(MathDifficulty.EASY, 3).copy(answered = 1),
    )

    private fun inject(json: JSONObject) {
        assertTrue(context.createDeviceProtectedStorageContext()
            .getSharedPreferences("mebot_alarm_v1", Context.MODE_PRIVATE)
            .edit().putString("session", json.toString()).commit())
    }

    @Test fun missingChallengeRepairsOnceAndPreservesSnoozeAndDeadline() {
        val original = ringing().copy(
            phase = AlarmPhase.SNOOZED, snoozesUsed = 1,
            deadlineAtMillis = 1_792_540_980_000L, deadlineElapsedMillis = 180_000L, bootCount = 9,
        )
        val json = sessionToJson(original).apply { remove("mathChallenge") }
        inject(json)
        val repaired = requireNotNull(controller.session())
        assertEquals(original.copy(mathChallenge = repaired.mathChallenge), repaired)
        assertEquals(0, repaired.mathChallenge?.answered)
        assertEquals(3, repaired.mathChallenge?.questions?.size)
        assertTrue(requireNotNull(repaired.mathChallenge).questions.all(MathQuestions::isValid))
        repeat(5) { assertEquals(repaired, AlarmController(context).session()) }
        assertEquals(1, controller.events().count { it.kind == "math_challenge_recovered" })
        assertFalse(repaired.audible)
    }

    @Test fun malformedChallengeCannotSilenceAnOtherwiseValidRingingOccurrence() {
        val damage = listOf<(JSONObject) -> Unit>(
            { it.put("mathChallenge", "damaged") },
            { it.getJSONObject("mathChallenge").put("answered", 3) },
            { it.getJSONObject("mathChallenge").put("answered", 1.5) },
            { it.getJSONObject("mathChallenge").getJSONArray("questions").remove(1) },
            { it.getJSONObject("mathChallenge").getJSONArray("questions").put(1, JSONObject.NULL) },
            { val questions = it.getJSONObject("mathChallenge").getJSONArray("questions")
                questions.getJSONObject(1).put("id", questions.getJSONObject(0).getString("id")) },
        )
        damage.forEach { corrupt ->
            store.clear()
            val original = ringing()
            inject(sessionToJson(original).also(corrupt))
            val repaired = requireNotNull(controller.session())
            assertTrue(repaired.audible)
            assertEquals(original.id, repaired.id)
            assertEquals(original.occurrenceId, repaired.occurrenceId)
            assertEquals(0, repaired.mathChallenge?.answered)
            assertEquals(3, repaired.mathChallenge?.questions?.size)
            assertEquals(1, controller.events().count { it.kind == "math_challenge_recovered" })
        }
    }

    @Test fun invalidOrIncorrectProblemGetsFreshIdentifiersAndValidArithmetic() {
        val damage = listOf<(JSONObject) -> Unit>(
            { it.put("answer", -1) },
            { it.put("answer", 10_000) },
            { it.put("answer", it.getInt("answer") + 1) },
            { it.put("answer", it.getInt("answer") + .5) },
            { it.put("answer", it.getInt("answer").toString()) },
            { it.put("expression", "99999999999999 × 2") },
            { it.put("id", "") },
        )
        damage.forEach { corrupt ->
            store.clear()
            val original = ringing()
            val json = sessionToJson(original)
            corrupt(json.getJSONObject("mathChallenge").getJSONArray("questions").getJSONObject(0))
            inject(json)
            val repaired = requireNotNull(controller.session()?.mathChallenge)
            assertTrue(repaired.questions.all(MathQuestions::isValid))
            val oldIds = requireNotNull(original.mathChallenge).questions.map { it.id }.toSet()
            assertTrue(repaired.questions.none { it.id in oldIds })
        }
    }

    @Test fun aQueuedAnswerCannotAdvanceTheReplacementChallenge() {
        val original = ringing()
        val oldQuestion = requireNotNull(original.mathChallenge?.current)
        val json = sessionToJson(original)
        json.getJSONObject("mathChallenge").getJSONArray("questions").getJSONObject(0).put("answer", -1)
        inject(json)
        assertEquals(MathAnswerResult.STALE, controller.submitMathAnswer(original.id, oldQuestion.id, oldQuestion.answer))
        assertEquals(0, controller.session()?.mathChallenge?.answered)
        assertNotNull(controller.session())
    }

    @Test fun aValidChallengeRetainsItsQuestionsAndProgressWithoutRecoveryEvents() {
        val original = ringing()
        inject(sessionToJson(original))
        repeat(5) { assertEquals(original, controller.session()) }
        assertTrue(controller.events().none { it.kind == "math_challenge_recovered" })
    }
}
