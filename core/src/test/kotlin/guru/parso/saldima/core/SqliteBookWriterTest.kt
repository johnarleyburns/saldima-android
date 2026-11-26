// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A real JVM SQLite engine, the same dialect `:app` writes with on-device. */
private class JdbcWrites(private val connection: Connection) : SqliteWrites {
    override fun exec(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    override fun insert(sql: String, params: List<Any?>) {
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeUpdate()
        }
    }

    override fun <T> inTransaction(block: () -> T): T {
        val wasAutoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = block()
            connection.commit()
            return result
        } catch (failure: Exception) {
            connection.rollback()
            throw failure
        } finally {
            connection.autoCommit = wasAutoCommit
        }
    }
}

private class JdbcRows(private val connection: Connection) : SqliteRows {
    override fun query(sql: String): List<Map<String, String?>> =
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                val columns = (1..rows.metaData.columnCount).map { rows.metaData.getColumnLabel(it) }
                buildList {
                    while (rows.next()) {
                        add(columns.associateWith { rows.getString(it) })
                    }
                }
            }
        }
}

private fun sequentialIds(start: Int = 1): IdGenerator {
    var counter = start
    return IdGenerator { Guid.parse(counter++.toString().padStart(32, '0')) }
}

class SqliteBookWriterTest {
    private val files = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        files.forEach { it.delete() }
    }

    private fun connect(): Connection {
        val file = File.createTempFile("saldima-writer-test", ".gnucash")
        files.add(file)
        return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")
    }

    @Test
    fun `materializes a snapshot and reopens it through the shared reader`() {
        val root = Guid.parse("1".repeat(32))
        val bank = Guid.parse("2".repeat(32))
        val salary = Guid.parse("3".repeat(32))
        val transaction = Guid.parse("4".repeat(32))
        val snapshot = BookSnapshot(
            bookGuid = Guid.parse("5".repeat(32)),
            currencyCode = "USD",
            fraction = 100,
            accounts = listOf(
                SnapshotAccount(guid = root, name = "Root Account", type = "ROOT"),
                SnapshotAccount(guid = bank, name = "Bank", type = "BANK", parentGuid = root, code = "1000"),
                SnapshotAccount(guid = salary, name = "Salary", type = "INCOME", parentGuid = root),
            ),
            transactions = listOf(
                SnapshotTransaction(
                    guid = transaction,
                    date = "2026-01-15",
                    description = "Opening deposit",
                    currencyCode = "USD",
                    splits = listOf(
                        SnapshotSplit(bank, Money(250_000, 100)),
                        SnapshotSplit(salary, Money(-250_000, 100)),
                    ),
                ),
            ),
        )

        connect().use { connection ->
            SqliteBookWriter.materialize(snapshot, JdbcWrites(connection), sequentialIds(start = 1000))
            val reopened = SqliteBookReader.read(JdbcRows(connection))

            assertEquals(snapshot.bookGuid, reopened.bookGuid)
            assertEquals("USD", reopened.currencyCode)
            assertEquals(
                setOf("Root Account", "Bank", "Salary", "Template Root"),
                reopened.accounts.map { it.name }.toSet(),
            )
            assertEquals("1000", reopened.accounts.first { it.name == "Bank" }.code)
            assertEquals(1, reopened.transactions.size)
            val posted = reopened.transactions.first()
            assertEquals("Opening deposit", posted.description)
            assertEquals("2026-01-15", posted.date)
            assertEquals(2, posted.splits.size)
            val total = posted.splits.fold(Money.ZERO) { sum, split -> Money.add(sum, split.value) }
            assertEquals(Money.ZERO, total)
        }
    }

    @Test
    fun `synthesizes a root account when the snapshot has none`() {
        val checking = Guid.parse("6".repeat(32))
        val snapshot = BookSnapshot(
            bookGuid = Guid.parse("7".repeat(32)),
            accounts = listOf(SnapshotAccount(guid = checking, name = "Checking", type = "BANK")),
            transactions = emptyList(),
        )

        connect().use { connection ->
            SqliteBookWriter.materialize(snapshot, JdbcWrites(connection), sequentialIds())
            val reopened = SqliteBookReader.read(JdbcRows(connection))
            val checkingAccount = reopened.accounts.first { it.name == "Checking" }
            val root = reopened.accounts.first { it.type == "ROOT" && it.name == "Root Account" }
            assertEquals(root.guid, checkingAccount.parentGuid)
        }
    }

    @Test
    fun `refuses an empty snapshot`() {
        val snapshot = BookSnapshot(bookGuid = Guid.parse("8".repeat(32)), accounts = emptyList(), transactions = emptyList())
        connect().use { connection ->
            assertFailsWith<IllegalArgumentException> {
                SqliteBookWriter.materialize(snapshot, JdbcWrites(connection), sequentialIds())
            }
        }
    }

    @Test
    fun `round-trips the real XML fixture through materialize and the reader`() {
        val xmlSnapshot = XmlBookReader.read(File("../fixtures/books/oracle-mini.gnucash"))
        connect().use { connection ->
            SqliteBookWriter.materialize(xmlSnapshot, JdbcWrites(connection), sequentialIds(start = 2000))
            val reopened = SqliteBookReader.read(JdbcRows(connection))
            assertEquals(
                xmlSnapshot.accounts.map { it.name to it.type }.sortedBy { it.first },
                reopened.accounts.filterNot { it.name == "Template Root" }
                    .map { it.name to it.type }.sortedBy { it.first },
            )
            assertEquals(xmlSnapshot.transactions.size, reopened.transactions.size)
            assertTrue(reopened.transactions.first().splits.fold(Money.ZERO) { sum, split -> Money.add(sum, split.value) } == Money.ZERO)
        }
    }

    @Test
    fun `imports the real ~3,300-transaction synthetic ledger and reopens it`() {
        val snapshot = XmlBookReader.read(File("../fixtures/books/gronewald-gl-2023-01.gnucash"))
        assertEquals(3305, snapshot.transactions.size)

        connect().use { connection ->
            SqliteBookWriter.materialize(snapshot, JdbcWrites(connection), sequentialIds(start = 5000))
            val reopened = SqliteBookReader.read(JdbcRows(connection))

            assertEquals(24, reopened.accounts.size) // 22 real accounts + the book's own root + the template root
            assertEquals(3305, reopened.transactions.size)
            var total = Money.ZERO
            for (transaction in reopened.transactions) {
                for (split in transaction.splits) total = Money.add(total, split.value)
            }
            assertEquals(Money.ZERO, total)
        }
    }
}
