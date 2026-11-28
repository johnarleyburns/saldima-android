// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportAmountTest {
    private fun cents(value: Money?): String = (value ?: Money.ZERO).formatted(100)

    @Test
    fun `parses exact cents from common formats`() {
        assertEquals("1234.56", cents(parseImportAmount("1,234.56")))
        assertEquals("1234.56", cents(parseImportAmount("$1.234,56")))
        assertEquals("-45.00", cents(parseImportAmount("(45.00)")))
        assertEquals("-12.35", cents(parseImportAmount("-12.345")))
        assertEquals("1.23", cents(parseImportAmount("1.234")))
    }

    @Test
    fun `rejects empty and non-numeric text`() {
        assertNull(parseImportAmount(""))
        assertNull(parseImportAmount("n/a"))
    }

    @Test
    fun `handles signs, thousands and multiple separators`() {
        assertEquals("-45.00", cents(parseImportAmount("45.00-")))
        assertEquals("45.00", cents(parseImportAmount("+45.00")))
        assertEquals("1234.00", cents(parseImportAmount("1,234")))
        assertEquals("1.23", cents(parseImportAmount("1,23")))
        assertEquals("1234567.00", cents(parseImportAmount("1.234.567")))
        assertEquals("12.00", cents(parseImportAmount("USD 12.00")))
        assertEquals("1234.56", cents(parseImportAmount("1.234,56")))
        assertNull(parseImportAmount("."))
        assertNull(parseImportAmount(","))
    }
}
