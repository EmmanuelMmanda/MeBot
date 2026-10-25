package mebot.nguli.com.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.math.MathContext

/** Offline arithmetic. The caller labels both quantities with the same unit and currency. */
object UnitPriceComparison {
    data class Result(val firstPerUnit: BigDecimal, val secondPerUnit: BigDecimal, val cheaper: Int, val savingPercent: BigDecimal)
    fun number(raw: String): BigDecimal? {
        val text = raw.trim()
        if (!Regex("[0-9]{1,12}(\\.[0-9]{1,6})?").matches(text)) return null
        return text.toBigDecimalOrNull()?.takeIf { it > BigDecimal.ZERO }
    }
    fun compare(firstPrice: String, firstQuantity: String, secondPrice: String, secondQuantity: String): Result? {
        val p1 = number(firstPrice) ?: return null
        val q1 = number(firstQuantity) ?: return null
        val p2 = number(secondPrice) ?: return null
        val q2 = number(secondQuantity) ?: return null
        val comparison = (p1 * q2).compareTo(p2 * q1)
        val first = p1.divide(q1, MathContext.DECIMAL128)
        val second = p2.divide(q2, MathContext.DECIMAL128)
        val highestCross = maxOf(p1 * q2, p2 * q1)
        val difference = (p1 * q2 - p2 * q1).abs()
        val saving = difference.multiply(BigDecimal(100)).divide(highestCross, 2, RoundingMode.HALF_UP)
        return Result(first, second, comparison.compareTo(0), saving)
    }
    fun display(value: BigDecimal): String = (if (value.signum() != 0 && value.abs() < BigDecimal("0.01")) value.round(MathContext(6))
        else value.setScale(2, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
}
