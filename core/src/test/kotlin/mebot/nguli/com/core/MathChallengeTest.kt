package mebot.nguli.com.core

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class MathChallengeTest {
    @Test fun generatedProblemsHaveCorrectIntegerAnswersAndUniqueQuestionIds() {
        MathDifficulty.entries.forEach { difficulty ->
            repeat(100) { seed ->
                val challenge = MathQuestions.create(difficulty, 5, Random(seed))
                assertEquals(5, challenge.questions.size)
                assertEquals(5, challenge.questions.map { it.id }.toSet().size)
                assertEquals(5, challenge.questions.map { it.expression }.toSet().size)
                assertFalse(challenge.complete)
                assertEquals(challenge.questions.first(), challenge.current)
                challenge.questions.forEach { problem ->
                    assertEquals(problem.expression, calculate(problem.expression), problem.answer)
                    assertTrue(problem.answer in 0..999)
                    assertTrue(MathQuestions.isValid(problem))
                }
            }
        }
    }

    @Test fun eachOccurrenceGetsFreshQuestionIdentifiersEvenWhenOperandsRepeat() {
        val first = MathQuestions.create(MathDifficulty.EASY, 3, Random(20))
        val second = MathQuestions.create(MathDifficulty.EASY, 3, Random(20))
        assertEquals(first.questions.map { it.expression }, second.questions.map { it.expression })
        assertTrue(first.questions.map { it.id }.toSet().intersect(second.questions.map { it.id }.toSet()).isEmpty())
    }

    @Test fun completedChallengeHasNoCurrentQuestion() {
        val challenge = MathQuestions.create(MathDifficulty.MEDIUM, 3, Random(15)).copy(answered = 3)
        assertTrue(challenge.complete)
        assertNull(challenge.current)
    }

    @Test(expected = IllegalArgumentException::class)
    fun zeroQuestionsAreRejected() { MathQuestions.create(MathDifficulty.EASY, 0) }

    @Test(expected = IllegalArgumentException::class)
    fun unboundedQuestionCountsAreRejected() { MathQuestions.create(MathDifficulty.EASY, 6) }

    @Test fun previousBackupsRetainOrdinaryAndQrDismissal() {
        val decoded = BackupCodec.decode("""{"schemaVersion":1,"alarms":[{"id":"ordinary"},{"id":"checkpoint","requireCheckpoint":true,"checkpointCode":"hallway"}]}""")
        assertEquals(AlarmDismissMode.STANDARD, decoded.alarms.first().effectiveDismissMode)
        assertEquals(AlarmDismissMode.QR, decoded.alarms.last().effectiveDismissMode)
    }

    @Test fun mathConfigurationSurvivesPortableBackup() {
        val state = AppState(alarms = listOf(AlarmPlan(id = "math", dismissMode = AlarmDismissMode.MATH, mathDifficulty = MathDifficulty.HARD, mathQuestionCount = 5)))
        assertEquals(state, BackupCodec.decode(BackupCodec.encode(state)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun importedMathCountCannotBypassChallenge() {
        BackupCodec.decode("""{"schemaVersion":1,"alarms":[{"id":"math","dismissMode":"MATH","mathQuestionCount":0}]}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun explicitQrModeRequiresACheckpointEvenWithoutLegacyFlag() {
        StateValidation.alarm(AlarmPlan(dismissMode = AlarmDismissMode.QR))
    }

    @Test fun operationalValidationRejectsUnanswerableOrMismatchedProblems() {
        assertTrue(MathQuestions.isValid(MathQuestion("q", "(24 ÷ 3) + 4", 12)))
        assertFalse(MathQuestions.isValid(MathQuestion("q", "12 + 7", 20)))
        assertFalse(MathQuestions.isValid(MathQuestion("q", "5 − 8", -3)))
        assertFalse(MathQuestions.isValid(MathQuestion("q", "12 ÷ 0", 0)))
        assertFalse(MathQuestions.isValid(MathQuestion("q", "5 ÷ 2", 2)))
        assertFalse(MathQuestions.isValid(MathQuestion("q", "9 + 1", 10_000)))
        assertFalse(MathQuestions.isValid(MathQuestion("", "9 + 1", 10)))
        assertFalse(MathQuestions.isValid(MathQuestion("q", "not arithmetic", 0)))
    }

    private fun calculate(expression: String): Int {
        if (expression.startsWith("(")) {
            val closing = expression.indexOf(')')
            val inner = calculate(expression.substring(1, closing))
            return calculate("$inner${expression.substring(closing + 1)}")
        }
        val tokens = expression.split(' ')
        val first = tokens[0].toInt()
        val second = tokens[2].toInt()
        return when (tokens[1]) {
            "+" -> first + second
            "−" -> first - second
            "×" -> first * second
            "÷" -> { assertEquals(0, first % second); first / second }
            else -> throw AssertionError("Unexpected operator")
        }
    }
}
