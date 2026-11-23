// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyTest {
    @Test
    fun `posting normalisation is half-up`() {
        assertEquals(Money(267, 100), Money.parseHalfUp("2.665", 100))
        assertEquals(Money(-267, 100), Money.parseHalfUp("-2.665", 100))
        assertEquals(Money(268, 100), Money.parseHalfUp("2.675", 100))
        assertEquals(Money(266, 100), Money.parseExact("2.665").roundedHalfEven(100))
    }

    @Test
    fun `arithmetic is exact and normalised`() {
        assertEquals(Money(2, 3), Money.add(Money(1, 3), Money(1, 3)))
        assertEquals(Money(20, 1), Money.add(Money(1234, 100), Money(766, 100)))
        assertEquals(Money(-1, 50), Money.subtract(Money(1, 100), Money(3, 100)))
        assertEquals(Money(5, 1), Money.multiply(Money(250, 100), Money(2, 1)))
    }

    @Test
    fun `formats and rejects invalid fractions`() {
        assertEquals("12.34", Money(12345, 1000).formatted(100))
        assertEquals("-0.05", Money(-5, 100).formatted(100))
        assertFailsWith<IllegalArgumentException> { Money(1, 0) }
        assertFailsWith<IllegalArgumentException> { Money.decimalPlaces(3) }
        assertFailsWith<ArithmeticException> { Money.add(Money(Long.MAX_VALUE, 1), Money(1, 1)) }
    }
}
