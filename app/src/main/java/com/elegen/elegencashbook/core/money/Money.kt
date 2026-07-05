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

    /** Magnitude only, sign dropped — UI shows the sign via color instead (red = negative). */
    val abs: Money get() = if (paisa < 0) Money(-paisa) else this

    /**
     * Numeric display string, no currency symbol (UI owns the "Rs " prefix).
     * Whole rupees render without decimals ("1234"), otherwise two decimals ("1234.50").
     */
    fun format(): String {
        val bd = BigDecimal.valueOf(paisa, 2)
        return if (paisa % 100L == 0L) bd.setScale(0).toPlainString() else bd.toPlainString()
    }

    /**
     * Indian lakh/crore short form for list rows where space is tight ("49.45L" not "4945000").
     * Below 1 lakh, falls back to [format] unchanged.
     */
    fun formatCompact(): String {
        val absRupees = BigDecimal.valueOf(Math.abs(paisa), 2)
        val sign = if (paisa < 0) "-" else ""
        return when {
            absRupees >= CRORE -> "$sign${absRupees.divide(CRORE, 2, RoundingMode.HALF_UP).toPlainString()}Cr"
            absRupees >= LAKH -> "$sign${absRupees.divide(LAKH, 2, RoundingMode.HALF_UP).toPlainString()}L"
            else -> format()
        }
    }

    /**
     * Indian digit grouping on the magnitude only, sign dropped ("36,963" not "-36963") —
     * for prominent single-amount displays where color already conveys the sign.
     *
     * ponytail: hand-rolled because java.text.NumberFormat's en-IN grouping is host-JVM-dependent
     * (confirmed wrong 3-3-3 grouping under Robolectric's JDK) — not safe across the test/device split.
     */
    fun formatGrouped(): String {
        val plain = abs.format()
        val dotIndex = plain.indexOf('.')
        val intPart = if (dotIndex >= 0) plain.substring(0, dotIndex) else plain
        val fraction = if (dotIndex >= 0) plain.substring(dotIndex) else ""
        if (intPart.length <= 3) return intPart + fraction
        val lastThree = intPart.takeLast(3)
        val rest = intPart.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
        return "$rest,$lastThree$fraction"
    }

    companion object {
        val ZERO = Money(0)
        private val LAKH = BigDecimal(100_000)
        private val CRORE = BigDecimal(1_00_00_000)

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
