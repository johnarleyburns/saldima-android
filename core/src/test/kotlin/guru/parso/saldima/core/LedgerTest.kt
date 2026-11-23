// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private fun g(c: String) = Guid.parse(c.repeat(32))
private fun m(n: Long, d: Long = 1) = Money(n, d)
private val bank = g("1"); private val card = g("2"); private val opening = g("3")
private val sales = g("4"); private val rent = g("5")
private val accounts = listOf(
    LedgerAccount(bank, "Bank", AccountType.BANK),
    LedgerAccount(card, "Card", AccountType.CREDIT),
    LedgerAccount(opening, "Opening Balances", AccountType.EQUITY),
    LedgerAccount(sales, "Sales", AccountType.INCOME),
    LedgerAccount(rent, "Rent", AccountType.EXPENSE),
)

private fun tx(id: String, date: String, vararg splits: Pair<Guid, Long>) = LedgerTransaction(
    g(id), date, "test", splits = splits.map { LedgerSplit(it.first, m(it.second, 100)) },
)

class LedgerTest {
    @Test
    fun `opening balances tie out`() {
        var ledger = LedgerEngine.create(accounts)
        ledger = LedgerEngine.post(ledger, tx("a", "2025-01-01", bank to 100_000, card to -20_000, opening to -80_000))
        assertEquals(m(1000), LedgerEngine.accountBalance(ledger, bank))
        val trial = LedgerEngine.trialBalance(ledger)
        assertTrue(trial.balanced)
        assertEquals(m(1000), trial.totalDebits)
        val sheet = LedgerEngine.balanceSheet(ledger, "2025-12-31")
        assertEquals(m(1000), sheet.assets)
        assertEquals(m(200), sheet.liabilities)
        assertEquals(m(800), sheet.equity)
        assertEquals(Money.ZERO, sheet.retainedEarnings)
        assertTrue(sheet.balanced)
    }

    @Test
    fun `sale and expense flow through P&L and retained earnings`() {
        var ledger = LedgerEngine.create(accounts)
        ledger = LedgerEngine.post(ledger, tx("a", "2025-01-01", bank to 100_000, card to -20_000, opening to -80_000))
        ledger = LedgerEngine.post(ledger, tx("b", "2025-02-01", bank to 20_000, sales to -20_000))
        ledger = LedgerEngine.post(ledger, tx("c", "2025-02-02", rent to 5_000, bank to -5_000))
        val sheet = LedgerEngine.balanceSheet(ledger, "2025-12-31")
        assertEquals(m(1150), sheet.assets)
        assertEquals(m(150), sheet.retainedEarnings)
        assertTrue(sheet.balanced)
        val pnl = LedgerEngine.incomeStatement(ledger, "2025-01-01", "2025-12-31")
        assertEquals(m(200), pnl.revenue)
        assertEquals(m(50), pnl.expenses)
        assertEquals(m(150), pnl.netIncome)
    }

    @Test
    fun `invalid journals are rejected and half-up posting is normalised`() {
        val base = LedgerEngine.create(accounts)
        assertFailsWith<LedgerException> {
            LedgerEngine.post(base, tx("f", "2025-01-01", bank to 100, opening to -99))
        }
        val ledger = LedgerEngine.post(base, LedgerTransaction(
            g("e"), "2025-01-01", "half-up", 100,
            listOf(LedgerSplit(bank, m(2665, 1000)), LedgerSplit(opening, m(-2665, 1000))),
        ))
        assertEquals(m(267, 100), ledger.transactions[0].splits[0].value)
    }
}
