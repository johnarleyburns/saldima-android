// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private fun fixture(name: String) = File("../fixtures/books/$name")

class BookFormatTest {
    @Test
    fun `detects the backend by bytes, not by extension`() {
        assertEquals(BookFormat.SQLITE, BookFiles.detect(fixture("oracle-mini.sqlite.gnucash")))
        assertEquals(BookFormat.XML, BookFiles.detect(fixture("oracle-mini.gnucash")))
    }

    @Test
    fun `refuses an unknown file`() {
        val file = File.createTempFile("saldima-unknown", ".gnucash")
        file.writeText("not a book")
        try {
            assertFailsWith<BookFormatException> { BookFiles.detect(file) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `reads the GnuCash XML backend`() {
        val snapshot = XmlBookReader.read(fixture("oracle-mini.gnucash"))

        assertEquals("USD", snapshot.currencyCode)
        assertEquals(100L, snapshot.fraction)
        assertEquals(5, snapshot.accounts.size)

        val byName = snapshot.accounts.associateBy { it.name }
        assertEquals("ROOT", byName.getValue("Root Account").type)
        assertEquals("ASSET", byName.getValue("Assets").type)
        assertEquals("BANK", byName.getValue("Bank").type)
        assertEquals("INCOME", byName.getValue("Income").type)
        assertEquals("INCOME", byName.getValue("Salary").type)
        assertEquals(byName.getValue("Root Account").guid, byName.getValue("Assets").parentGuid)
        assertEquals(byName.getValue("Assets").guid, byName.getValue("Bank").parentGuid)
        assertEquals(byName.getValue("Income").guid, byName.getValue("Salary").parentGuid)
        assertEquals("USD", byName.getValue("Bank").commodityCode)

        assertEquals(1, snapshot.transactions.size)
        val transaction = snapshot.transactions.first()
        assertEquals("Opening deposit", transaction.description)
        assertEquals("2025-01-15", transaction.date)
        assertEquals("USD", transaction.currencyCode)
        assertEquals(2, transaction.splits.size)

        val bank = byName.getValue("Bank").guid
        val salary = byName.getValue("Salary").guid
        val bankSplit = transaction.splits.first { it.accountGuid == bank }
        val salarySplit = transaction.splits.first { it.accountGuid == salary }
        assertEquals(Money(250_000, 100), bankSplit.value)
        assertEquals(Money(-250_000, 100), salarySplit.value)
        assertTrue(transaction.splits.fold(Money.ZERO) { total, split -> Money.add(total, split.value) } == Money.ZERO)
    }

    @Test
    fun `XML and SQLite fixtures agree on the chart names and types`() {
        val xml = XmlBookReader.read(fixture("oracle-mini.gnucash"))
        val stored = sqliteChart(fixture("oracle-mini.sqlite.gnucash"))
        assertEquals(
            xml.accounts.map { it.name to it.type }.sortedBy { it.first },
            stored.filterNot { it.first == "Template Root" }.sortedBy { it.first },
        )
    }

    private fun sqliteChart(file: File): List<Pair<String, String>> {
        val connection = java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
        return connection.use { db ->
            db.createStatement().use { statement ->
                statement.executeQuery("SELECT name, account_type FROM accounts").use { rows ->
                    buildList { while (rows.next()) add(rows.getString(1) to rows.getString(2)) }
                }
            }
        }
    }
}
