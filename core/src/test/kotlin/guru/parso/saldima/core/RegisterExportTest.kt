// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RegisterExportTest {
    @Test
    fun `builds one row per split in the target account, categorising a simple transfer by the other account`() {
        val bank = Guid.parse("1".repeat(32))
        val salary = Guid.parse("2".repeat(32))
        val rent = Guid.parse("3".repeat(32))
        val tax = Guid.parse("4".repeat(32))
        val names = mapOf(bank to "Checking", salary to "Salary", rent to "Rent", tax to "Tax")

        val simple = SnapshotTransaction(
            guid = Guid.parse("a".repeat(32)),
            date = "2026-02-01",
            description = "Paycheck",
            splits = listOf(
                SnapshotSplit(bank, Money(250_000, 100), memo = "direct deposit"),
                SnapshotSplit(salary, Money(-250_000, 100)),
            ),
        )
        val split = SnapshotTransaction(
            guid = Guid.parse("b".repeat(32)),
            date = "2026-02-02",
            description = "Rent + tax",
            splits = listOf(
                SnapshotSplit(bank, Money(-11_000, 100)),
                SnapshotSplit(rent, Money(10_000, 100)),
                SnapshotSplit(tax, Money(1_000, 100)),
            ),
        )
        val elsewhere = SnapshotTransaction(
            guid = Guid.parse("c".repeat(32)),
            date = "2026-02-03",
            description = "Not mine",
            splits = listOf(
                SnapshotSplit(salary, Money(100, 100)),
                SnapshotSplit(rent, Money(-100, 100)),
            ),
        )

        val rows = registerExportRows(listOf(elsewhere, split, simple), names, bank)

        assertEquals(2, rows.size)
        assertEquals("2026-02-01", rows[0].date)
        assertEquals("Paycheck", rows[0].payee)
        assertEquals("direct deposit", rows[0].memo)
        assertEquals("Salary", rows[0].category)
        assertEquals(Money(250_000, 100), rows[0].amount)

        assertEquals("2026-02-02", rows[1].date)
        assertEquals("Split Transaction", rows[1].category)
    }

    @Test
    fun `serializes QIF and CSV in the same format the web track reads and writes`() {
        val rows = listOf(
            RegisterExportRow(
                date = "2026-02-01",
                amount = Money(-500, 100),
                payee = "Coffee shop",
                memo = "beans",
                category = "Dining",
            ),
            RegisterExportRow(
                date = "2026-02-02",
                amount = Money(1_000, 100),
                payee = "Refund, with a comma",
                memo = "",
                category = "",
            ),
        )

        val qif = serializeQif(rows)
        assertEquals(
            listOf(
                "!Type:Bank",
                "D2026-02-01",
                "T-5.00",
                "PCoffee shop",
                "Mbeans",
                "LDining",
                "^",
                "D2026-02-02",
                "T10.00",
                "PRefund, with a comma",
                "^",
            ).joinToString("\n") + "\n",
            qif,
        )

        val csv = serializeCsv(rows)
        assertEquals(
            listOf(
                "Date,Description,Memo,Amount,Category",
                "2026-02-01,Coffee shop,beans,-5.00,Dining",
                "2026-02-02,\"Refund, with a comma\",,10.00,",
            ).joinToString("\n") + "\n",
            csv,
        )
    }

    @Test
    fun `sanitizes an account name into a safe, non-empty filename stem`() {
        assertEquals("Checking.qif", registerExportFilename("Checking", "qif"))
        assertEquals("Accounts-Payable.csv", registerExportFilename("Accounts Payable", "csv"))
        assertEquals("account.qif", registerExportFilename("  ", "qif"))
        assertEquals("A-R-USD.csv", registerExportFilename("A/R (USD)", "csv"))
    }
}
