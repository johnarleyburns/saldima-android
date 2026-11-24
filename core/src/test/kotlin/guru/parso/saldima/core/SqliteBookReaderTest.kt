// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals

private class FakeRows(private val tables: Map<String, List<Map<String, String?>>>) : SqliteRows {
    override fun query(sql: String): List<Map<String, String?>> = when {
        sql.contains(" FROM books") -> tables["books"].orEmpty()
        sql.contains(" FROM commodities") -> tables["commodities"].orEmpty()
        sql.contains(" FROM accounts") -> tables["accounts"].orEmpty()
        sql.contains(" FROM splits") -> tables["splits"].orEmpty()
        sql.contains(" FROM transactions") -> tables["transactions"].orEmpty()
        else -> emptyList()
    }
}

class SqliteBookReaderTest {
    @Test
    fun `maps a SQLite book into the shared snapshot`() {
        val rows = FakeRows(
            mapOf(
                "books" to listOf(mapOf("guid" to "a".repeat(32), "root_account_guid" to "b".repeat(32), "root_template_guid" to "c".repeat(32))),
                "commodities" to listOf(mapOf("guid" to "d".repeat(32), "mnemonic" to "USD")),
                "accounts" to listOf(
                    mapOf("guid" to "b".repeat(32), "name" to "Root Account", "account_type" to "ROOT", "parent_guid" to null, "commodity_guid" to null, "code" to "", "description" to ""),
                    mapOf("guid" to "e".repeat(32), "name" to "Bank", "account_type" to "BANK", "parent_guid" to "b".repeat(32), "commodity_guid" to "d".repeat(32), "code" to "1000", "description" to "Checking"),
                ),
                "transactions" to listOf(mapOf("guid" to "f".repeat(32), "post_date" to "2025-01-15 10:00:00", "description" to "Opening deposit", "currency_guid" to "d".repeat(32))),
                "splits" to listOf(
                    mapOf("tx_guid" to "f".repeat(32), "account_guid" to "e".repeat(32), "value_num" to "250000", "value_denom" to "100"),
                    mapOf("tx_guid" to "f".repeat(32), "account_guid" to "e".repeat(32), "value_num" to "-250000", "value_denom" to "100"),
                ),
            ),
        )
        val snapshot = SqliteBookReader.read(rows)
        assertEquals("USD", snapshot.currencyCode)
        assertEquals(Guid.parse("a".repeat(32)), snapshot.bookGuid)
        assertEquals(Guid.parse("c".repeat(32)), snapshot.templateRootGuid)
        assertEquals(listOf("Root Account", "Bank"), snapshot.accounts.map { it.name })
        assertEquals("BANK", snapshot.accounts.last().type)
        assertEquals("1000", snapshot.accounts.last().code)
        assertEquals(1, snapshot.transactions.size)
        assertEquals("2025-01-15", snapshot.transactions.first().date)
        assertEquals(Money(250_000, 100), snapshot.transactions.first().splits.first().value)
    }
}
