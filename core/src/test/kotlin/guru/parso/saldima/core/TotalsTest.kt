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
}
