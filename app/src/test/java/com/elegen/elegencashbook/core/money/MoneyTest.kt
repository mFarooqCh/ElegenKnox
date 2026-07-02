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
    }
}
