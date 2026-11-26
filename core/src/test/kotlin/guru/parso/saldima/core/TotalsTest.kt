// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class TotalsTest {
    @Test
    fun `balances and totals match the fixture`() {
        val snapshot = XmlBookReader.read(File("../fixtures/books/oracle-mini.gnucash"))
        val byName = snapshot.accounts.associateBy { it.name }
        val balances = Totals.balances(snapshot)
        assertEquals(Money(2_500, 1), balances[byName.getValue("Bank").guid])
        assertEquals(Money(-2_500, 1), balances[byName.getValue("Salary").guid])

        val totals = Totals.totals(snapshot)
        assertEquals(Money(2_500, 1), totals.assets)
        assertEquals(Money.ZERO, totals.liabilities)
        assertEquals(Money(2_500, 1), totals.netAssets)
        assertEquals(Money(2_500, 1), totals.profit)
    }

    @Test
    fun `parent totals include descendants`() {
        val snapshot = XmlBookReader.read(File("../fixtures/books/oracle-mini.gnucash"))
        val byName = snapshot.accounts.associateBy { it.name }
        val rolled = Totals.rolledUp(snapshot)
        assertEquals(Money(2_500, 1), rolled[byName.getValue("Bank").guid])
        assertEquals(Money(2_500, 1), rolled[byName.getValue("Assets").guid])
        assertEquals(Money(-2_500, 1), rolled[byName.getValue("Income").guid])
    }

    @Test
    fun `balances and totals can be filtered to a period, as-of a date, or both unbounded`() {
        val snapshot = XmlBookReader.read(File("../fixtures/books/oracle-mini.gnucash"))
        val byName = snapshot.accounts.associateBy { it.name }
        val bank = byName.getValue("Bank").guid

        // The one posting is dated 2025-01-15.
        assertEquals(Money(2_500, 1), Totals.balances(snapshot, to = "2025-01-15")[bank])
        assertEquals(Money.ZERO, Totals.balances(snapshot, to = "2025-01-14")[bank] ?: Money.ZERO)
        assertEquals(Money.ZERO, Totals.balances(snapshot, from = "2025-01-16")[bank] ?: Money.ZERO)
        assertEquals(Money(2_500, 1), Totals.balances(snapshot, from = "2025-01-01", to = "2025-01-31")[bank])

        val beforeHand = Totals.totals(snapshot, to = "2025-01-14")
        assertEquals(Money.ZERO, beforeHand.assets)
        val sameDay = Totals.totals(snapshot, to = "2025-01-15")
        assertEquals(Money(2_500, 1), sameDay.assets)

        val rolledBefore = Totals.rolledUp(snapshot, to = "2025-01-14")
        assertEquals(Money.ZERO, rolledBefore[byName.getValue("Assets").guid] ?: Money.ZERO)
    }
}
