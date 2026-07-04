package com.elegen.elegencashbook.domain.usecase

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class AuthValidationTest {

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(
        "'  User@Example.COM ', user@example.com",
        "a@b.co,               a@b.co",
    )
    fun `email normalizes to lowercase trimmed`(input: String, expected: String) {
        assertEquals(expected, normalizeEmail(input))
    }

    @ParameterizedTest(name = "\"{0}\" -> null")
    @CsvSource("''", "'   '", "plainaddress", "'@no-local.com'", "'two@@ats.com'", "'user@nodot'", "'user@.start'", "'user@end.'")
    fun `invalid emails rejected`(input: String) {
        assertNull(normalizeEmail(input))
    }

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @CsvSource(
        "+923001234567,        +923001234567",
        "'+92 300 123-4567',   +923001234567",
        "923001234567,         +923001234567",
    )
    fun `phone normalizes to E164`(input: String, expected: String) {
        assertEquals(expected, normalizePhone(input))
    }

    @ParameterizedTest(name = "\"{0}\" -> null")
    @CsvSource("''", "abc", "+12345", "'+1234567890123456'", "'03001234567'")
    fun `invalid phones rejected`(input: String) {
        assertNull(normalizePhone(input))
    }
}
