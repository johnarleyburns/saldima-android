// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val QIF = """!Type:Bank
D01/15/2024
T-45.67
PGrocery Mart
MGroceries
LFood:Groceries
N1234
^
D01/16/2024
T1,500.00
PEmployer
LSalary
^
D01/17/2024
T-100.00
PTransfer
L[Savings]
^
D03/01/2024
T-60.00
PStore
SGroceries
$-40.00
SHousehold
$-20.00
^
"""

class ImportQifTest {
    private fun amounts(rows: List<ImportRow>) = rows.map { it.amount.formatted(100) }

    @Test
    fun `reads dates, exact amounts, categories, references and splits`() {
        val result = parseQif(QIF)
        assertEquals(ImportSource.QIF, result.source)
        assertEquals(4, result.rows.size)
        val first = result.rows[0]
        assertEquals("2024-01-15", first.date)
        assertEquals("Grocery Mart", first.payee)
        assertEquals("Groceries", first.memo)
        assertEquals("Food:Groceries", first.category)
        assertEquals("1234", first.reference)
        assertEquals(listOf("-45.67", "1500.00", "-100.00", "-60.00"), amounts(result.rows))
        assertEquals("[Savings]", result.rows[2].category)
        assertEquals(listOf("Groceries", "Household"), result.rows[3].splits.map { it.category })
        assertEquals(listOf("-40.00", "-20.00"), result.rows[3].splits.map { it.amount.formatted(100) })
    }

    @Test
    fun `skips records without a date or amount with a warning`() {
        val result = parseQif(
            """!Type:Bank
D01/01/2024
^
T10.00
^
D01/02/2024
PGood
T1.00
^
""",
        )
        assertEquals(1, result.rows.size)
        assertEquals("Good", result.rows[0].payee)
        assertEquals(2, result.warnings.size)
    }

    @Test
    fun `rejects a file with no usable rows`() {
        assertFailsWith<ImportFormatException> { parseQif("not a qif file") }
    }

    @Test
    fun `keeps the last record without a terminator and warns about odd splits`() {
        val result = parseQif(
            """!Type:Bank
!Option:AutoSwitch
D01/01/2024
T0.00
^
D01/02/2024
T5.00
PGift
SFood
^
D01/03/2024
T6.00
PFinal
$9.00
""",
        )
        assertEquals(2, result.rows.size)
        assertEquals("Final", result.rows[1].payee)
        assertEquals(3, result.warnings.size)
    }
}
