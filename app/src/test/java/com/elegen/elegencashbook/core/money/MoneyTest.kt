package com.elegen.elegencashbook.core.money

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class MoneyTest {

    @Nested
    inner class Parse {

        @ParameterizedTest(name = "\"{0}\" -> {1} paisa")
        @CsvSource(
            "12,        1200",
            "12.5,      1250",
            "12.50,     1250",
            "0,         0",
            "0.01,      1",
            "-5,        -500",
            "-0.50,     -50",
            "' 7.25 ',  725",   // trimmed
            "12.345,    1235",  // HALF_UP on 3rd decimal
            "12.344,    1234",
            "0.005,     1",     // half rounds up
            "0.004,     0",
        )
        fun `parses rupees to paisa with HALF_UP`(input: String, expected: Long) {
            assertEquals(Money(expected), Money.parse(input))
        }

        @ParameterizedTest(name = "\"{0}\" -> null")
        @CsvSource("''", "'   '", "abc", "12.3.4", "12a", "'--5'", "'+ 5'")
        fun `rejects invalid input`(input: String) {
            assertNull(Money.parse(input))
        }

        @Test
        fun `accepts max representable amount`() {
            // Long.MAX_VALUE paisa = 92233720368547758.07 rupees
            assertEquals(Money(Long.MAX_VALUE), Money.parse("92233720368547758.07"))
        }

        @Test
        fun `rejects amount beyond Long paisa range`() {
            assertNull(Money.parse("92233720368547758.08"))
            assertNull(Money.parse("99999999999999999999"))
        }
    }

    @Nested
    inner class Arithmetic {

        @Test
        fun `float classic 0_1 plus 0_2 is exactly 0_3`() {
            val result = Money.parse("0.1")!! + Money.parse("0.2")!!
            assertEquals(Money(30), result)
            assertEquals("0.30", result.format())
        }

        @Test
        fun `plus minus and negate`() {
            assertEquals(Money(500), Money(300) + Money(200))
            assertEquals(Money(100), Money(300) - Money(200))
            assertEquals(Money(-300), -Money(300))
        }

        @Test
        fun `overflow throws, never wraps`() {
            assertThrows(ArithmeticException::class.java) { Money(Long.MAX_VALUE) + Money(1) }
            assertThrows(ArithmeticException::class.java) { Money(Long.MIN_VALUE) - Money(1) }
            assertThrows(ArithmeticException::class.java) { -Money(Long.MIN_VALUE) }
        }

        @Test
        fun `sum of list is exact and empty sums to zero`() {
            val entries = listOf(Money.parse("0.1")!!, Money.parse("0.2")!!, Money.parse("99.7")!!)
            assertEquals(Money(10000), entries.sum())
            assertEquals(Money.ZERO, emptyList<Money>().sum())
        }

        @Test
        fun `comparison works`() {
            assertTrue(Money(100) < Money(200))
            assertTrue(Money(-1).isNegative)
            assertTrue(Money(1).isPositive)
            assertTrue(Money(0).isZero)
        }
    }

    @Nested
    inner class Format {

        @ParameterizedTest(name = "{0} paisa -> \"{1}\"")
        @CsvSource(
            "1200,   12",
            "1250,   12.50",
            "1,      0.01",
            "0,      0",
            "-50,    -0.50",
            "-1200,  -12",
            "123456789, 1234567.89",
        )
        fun `formats paisa for display`(paisa: Long, expected: String) {
            assertEquals(expected, Money(paisa).format())
        }

        @Test
        fun `parse-format round trip`() {
            for (s in listOf("12", "12.50", "0.01", "-3.75", "1234567.89")) {
                assertEquals(s, Money.parse(s)!!.format())
            }
        }

        @ParameterizedTest(name = "{0} paisa -> \"{1}\"")
        @CsvSource(
            "9999900,      99999",     // just under 1 lakh -> plain format, no suffix
            "10000000,     1.00L",     // exactly 1 lakh
            "494500000,    49.45L",
            "-494500000,   -49.45L",
            "990000000,    99.00L",    // just under 1 crore -> still L, not Cr
            "1000000000,   1.00Cr",    // exactly 1 crore
            "49450000000,  49.45Cr",
            "-49450000000, -49.45Cr",
            "837000,       8370",      // sub-lakh unaffected
        )
        fun `formatCompact uses lakh-crore short form above 1 lakh`(paisa: Long, expected: String) {
            assertEquals(expected, Money(paisa).formatCompact())
        }

        @ParameterizedTest(name = "{0} paisa -> \"{1}\"")
        @CsvSource(
            "12300,       123",         // 3 digits or fewer -> no grouping
            "123400,      '1,234'",
            "-123400,     '1,234'",     // sign dropped, magnitude only
            "123450,      '1,234.50'",  // fraction preserved after the grouped integer part
            "494500000,   '49,45,000'", // Indian grouping (2-digit groups after the first 3)
            "0,           0",
        )
        fun `formatGrouped applies Indian digit grouping to the magnitude`(paisa: Long, expected: String) {
            assertEquals(expected, Money(paisa).formatGrouped())
        }
    }
}
