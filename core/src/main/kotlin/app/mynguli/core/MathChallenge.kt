package app.mynguli.core

import java.util.UUID
import kotlin.random.Random
import kotlinx.serialization.Serializable

@Serializable
enum class MathDifficulty { EASY, MEDIUM, HARD }

/** Numbers and operators are generated locally; expressions are never evaluated as code. */
@Serializable
data class MathQuestion(val id: String, val expression: String, val answer: Int)

@Serializable
data class MathChallenge(val questions: List<MathQuestion>, val answered: Int = 0) {
    val current: MathQuestion? get() = questions.getOrNull(answered)
    val complete: Boolean get() = answered == questions.size
}

object MathQuestions {
    private val binary = Regex("([0-9]{1,3}) ([+−×÷]) ([0-9]{1,3})")
    private val compound = Regex("\\(([^()]+)\\) ([+×]) ([0-9]{1,3})")

    /** Every answer is a nonnegative integer, so the keypad needs only digits. */
    fun create(difficulty: MathDifficulty, count: Int, random: Random = Random.Default): MathChallenge {
        require(count in 1..5) { "Choose between 1 and 5 questions." }
        val questions = mutableListOf<MathQuestion>()
        val expressions = mutableSetOf<String>()
        while (questions.size < count) {
            val (expression, answer) = when (difficulty) {
                MathDifficulty.EASY -> easy(random)
                MathDifficulty.MEDIUM -> medium(random)
                MathDifficulty.HARD -> hard(random)
            }
            if (expressions.add(expression)) questions += MathQuestion(UUID.randomUUID().toString(), expression, answer)
        }
        return MathChallenge(questions)
    }

    /** Accept only the small arithmetic grammar produced above, with a keypad-safe correct answer. */
    fun isValid(question: MathQuestion): Boolean {
        if (question.id.isBlank() || question.id.length > 128 || '\u0000' in question.id) return false
        if (question.expression.length !in 1..80 || question.answer !in 0..9999) return false
        val nested = compound.matchEntire(question.expression)
        val calculated = if (nested != null) {
            val first = calculateBinary(nested.groupValues[1]) ?: return false
            calculate(first, nested.groupValues[2], nested.groupValues[3].toInt())
        } else calculateBinary(question.expression)
        return calculated != null && calculated == question.answer
    }

    private fun calculateBinary(expression: String): Int? {
        val parts = binary.matchEntire(expression)?.groupValues ?: return null
        return calculate(parts[1].toInt(), parts[2], parts[3].toInt())
    }

    private fun calculate(first: Int, operator: String, second: Int): Int? = when (operator) {
        "+" -> first + second
        "−" -> first - second
        "×" -> first * second
        "÷" -> if (second > 0 && first % second == 0) first / second else null
        else -> null
    }

    private fun easy(random: Random): Pair<String, Int> {
        val a = random.nextInt(2, 21)
        val b = random.nextInt(2, 21)
        return if (random.nextBoolean()) "$a + $b" to a + b
        else "${maxOf(a, b)} − ${minOf(a, b)}" to maxOf(a, b) - minOf(a, b)
    }

    private fun medium(random: Random): Pair<String, Int> = when (random.nextInt(3)) {
        0 -> {
            val a = random.nextInt(12, 100)
            val b = random.nextInt(12, 100)
            "$a + $b" to a + b
        }
        1 -> {
            val a = random.nextInt(12, 100)
            val b = random.nextInt(12, 100)
            "${maxOf(a, b)} − ${minOf(a, b)}" to maxOf(a, b) - minOf(a, b)
        }
        else -> {
            val a = random.nextInt(2, 13)
            val b = random.nextInt(2, 13)
            "$a × $b" to a * b
        }
    }

    private fun hard(random: Random): Pair<String, Int> = when (random.nextInt(3)) {
        0 -> {
            val a = random.nextInt(3, 21)
            val b = random.nextInt(3, 13)
            val c = random.nextInt(2, 40)
            "($a × $b) + $c" to a * b + c
        }
        1 -> {
            val a = random.nextInt(2, 13)
            val b = random.nextInt(3, 21)
            val c = random.nextInt(3, 30)
            "(${a * b} ÷ $a) + $c" to b + c
        }
        else -> {
            val a = random.nextInt(12, 40)
            val b = random.nextInt(2, 13)
            "($a + $b) × 2" to (a + b) * 2
        }
    }
}
