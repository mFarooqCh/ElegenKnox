package com.elegen.elegencashbook.core.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Monetary amount stored as integer paisa (constitution §4, spec §5).
 *
 * Rules:
 *  - Storage/arithmetic: Long paisa only. Never Double/Float.
 *  - Rounding policy: HALF_UP, applied once at parse time (spec §5).
 *  - Overflow is a data-integrity error: arithmetic uses *Exact and throws
 *    [ArithmeticException] instead of silently wrapping (spec §5).
 *  - BigDecimal appears only as a parse/format intermediary, never as storage.
 */
@JvmInline
value class Money(val paisa: Long) : Comparable<Money> {

    operator fun plus(other: Money): Money = Money(Math.addExact(paisa, other.paisa))
    operator fun minus(other: Money): Money = Money(Math.subtractExact(paisa, other.paisa))
    operator fun unaryMinus(): Money = Money(Math.negateExact(paisa))

    override fun compareTo(other: Money): Int = paisa.compareTo(other.paisa)

    val isNegative: Boolean get() = paisa < 0
    val isPositive: Boolean get() = paisa > 0
    val isZero: Boolean get() = paisa == 0L

    /**
     * Numeric display string, no currency symbol (UI owns the "Rs " prefix).
     * Whole rupees render without decimals ("1234"), otherwise two decimals ("1234.50").
     */
    fun format(): String {
        val bd = BigDecimal.valueOf(paisa, 2)
        return if (paisa % 100L == 0L) bd.setScale(0).toPlainString() else bd.toPlainString()
    }

    companion object {
        val ZERO = Money(0)

        /**
         * Parses user input in rupees ("12", "12.5", "12.345") into paisa.
         * More than two decimal places round HALF_UP. Returns null for blank,
         * non-numeric, or out-of-range input.
         */
        fun parse(input: String): Money? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            return try {
                val rupees = BigDecimal(trimmed).setScale(2, RoundingMode.HALF_UP)
                Money(rupees.movePointRight(2).longValueExact())
            } catch (_: NumberFormatException) {
                null
            } catch (_: ArithmeticException) {
                null
            }
        }
    }
}

/** Overflow-safe sum. Empty iterable sums to [Money.ZERO]. */
fun Iterable<Money>.sum(): Money = fold(Money.ZERO) { acc, m -> acc + m }
