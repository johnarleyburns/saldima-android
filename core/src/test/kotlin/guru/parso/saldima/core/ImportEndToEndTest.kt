// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scripted end-to-end run over a small, real-shaped QIF checking-account
 * statement: detect -> parse -> flag duplicates against existing book
 * history -> plan -> verify every planned transaction actually balances,
 * the same sequence `ImportStatementScreen` runs in `:app`.
 */
class ImportEndToEndTest {
    private val checking = Guid.parse("a".repeat(32))
    private val groceries = Guid.parse("b".repeat(32))
    private val salary = Guid.parse("c".repeat(32))
    private val unclassified = Guid.parse("d".repeat(32))

    private val statement = """!Type:Bank
D01/02/2026
T-52.14
PCorner Market
LFood:Groceries
N4471
^
D01/05/2026
T2500.00
PAcme Payroll
LSalary
^
D01/09/2026
T-18.40
PCoffee Shop
LFood:Groceries
^
"""

    @Test
    fun `imports a QIF checking statement end to end`() {
        assertEquals(ImportSource.QIF, detectImportSource("checking-jan-2026.qif", statement))

        val parsed = parseImportFile("checking-jan-2026.qif", statement)
        assertEquals(3, parsed.rows.size)
        assertTrue(parsed.warnings.isEmpty())

        // The book already has a near-identical purchase a day apart --
        // the coffee shop charge should be flagged, the other two should not.
        val history = listOf(ExistingEntry("2026-01-08", Money(-1840, 100)))
        val duplicateFlags = parsed.rows.map { isLikelyDuplicate(it, history) }
        assertEquals(listOf(false, false, true), duplicateFlags)

        val selected = parsed.rows.filterIndexed { index, _ -> !duplicateFlags[index] }
        assertEquals(2, selected.size)

        val mapping = ImportMapping(
            targetAccountGuid = checking,
            defaultAccountGuid = unclassified,
            categoryAccounts = mapOf("Food:Groceries" to groceries, "Salary" to salary),
        )
        val planned = planImport(selected, mapping)
        assertEquals(2, planned.size)

        for (transaction in planned) {
            assertTrue(transaction.splits.size >= 2, "every posted transaction needs at least two splits")
            val total = transaction.splits.fold(Money.ZERO) { sum, split -> Money.add(sum, split.value) }
            assertFalse(total.num != 0L, "transaction on ${transaction.date} does not balance: $total")
        }

        assertEquals("Corner Market", planned[0].description)
        assertEquals(listOf(checking, groceries), planned[0].splits.map { it.accountGuid })
        assertEquals("Acme Payroll", planned[1].description)
        assertEquals(listOf(checking, salary), planned[1].splits.map { it.accountGuid })
    }
}
