// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class DocumentSequenceIds(start: Long = 0) : IdGenerator {
    private var value = start

    override fun next(): Guid {
        value += 1
        return Guid.parse(value.toString(16).padStart(32, '0'))
    }
}

private fun gnucashBytes(name: String): ByteArray {
    val raw = File("../fixtures/books/$name").readBytes()
    return if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
        GZIPInputStream(raw.inputStream()).readBytes()
    } else {
        raw
    }
}

class XmlBookDocumentTest {
    @Test
    fun `parses the real fixture and preserves structure it does not model`() {
        val data = gnucashBytes("oracle-mini.gnucash")
        val document = XmlBookDocument.parse(data)

        assertEquals(5, document.accounts().size)
        assertEquals(1, document.transactions().size)
        assertEquals("USD", document.currency()?.code)

        val serialized = String(document.serialize(), Charsets.UTF_8)
        assertTrue("date-posted" in serialized)
        assertTrue("gdate" in serialized)
        assertTrue("xmlns:lot" in serialized)
    }

    @Test
    fun `creates, updates and deletes an account, preserving unrelated accounts`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bankGuid = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val guid = Guid.parse("1".repeat(32))

        val created = document.createAccount(
            guid = guid,
            name = "Petty Cash",
            type = "CASH",
            parentGuid = bankGuid,
            code = "1010",
            description = "Cash box",
            hidden = false,
            placeholder = true,
        )
        assertEquals("Petty Cash", created.name)
        assertTrue(assertNotNull(document.account(guid)).placeholder)

        document.updateAccount(
            guid,
            name = "Petty Cash Box",
            type = "CASH",
            parentGuid = bankGuid,
            code = "1010",
            description = "Cash box",
            hidden = true,
            placeholder = true,
        )
        val updated = assertNotNull(document.account(guid))
        assertEquals("Petty Cash Box", updated.name)
        assertTrue(updated.hidden)
        assertTrue(updated.placeholder)

        document.updateAccount(
            guid,
            name = updated.name,
            type = updated.type,
            parentGuid = updated.parentGuid,
            code = updated.code,
            description = updated.description,
            hidden = false,
            placeholder = true,
        )
        assertFalse(assertNotNull(document.account(guid)).hidden)

        val serialized = String(document.serialize(), Charsets.UTF_8)
        assertTrue("Salary" in serialized)
        assertTrue("gdate" in serialized)

        document.deleteAccount(guid)
        assertNull(document.account(guid))
    }

    @Test
    fun `posts and deletes a transaction`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val txGuid = Guid.parse("2".repeat(32))
        val split1 = Guid.parse("3".repeat(32))
        val split2 = Guid.parse("4".repeat(32))

        val posted = document.postTransaction(
            guid = txGuid,
            date = "2026-02-01",
            description = "Coffee",
            splits = listOf(
                Triple(bank, Money(-500, 100), split1),
                Triple(salary, Money(500, 100), split2),
            ),
        )
        assertEquals("Coffee", posted.description)
        assertEquals(2, document.transactions().size)

        val reparsed = XmlBookDocument.parse(document.serialize())
        assertEquals("Coffee", reparsed.transaction(txGuid)?.description)

        document.deleteTransaction(txGuid)
        assertNull(document.transaction(txGuid))
        assertEquals(1, document.transactions().size)
    }

    @Test
    fun `builds a fresh document from a snapshot, for the import boundary`() {
        val root = Guid.parse("5".repeat(32))
        val bank = Guid.parse("6".repeat(32))
        val salary = Guid.parse("7".repeat(32))
        val tx = Guid.parse("8".repeat(32))
        val templateRoot = Guid.parse("9".repeat(32))

        val snapshot = BookSnapshot(
            bookGuid = Guid.parse("a".repeat(32)),
            currencyCode = "EUR",
            fraction = 100,
            accounts = listOf(
                SnapshotAccount(root, "Root Account", "ROOT"),
                SnapshotAccount(bank, "Bank", "BANK", parentGuid = root),
                SnapshotAccount(salary, "Salary", "INCOME", parentGuid = root),
            ),
            transactions = listOf(
                SnapshotTransaction(
                    guid = tx,
                    date = "2026-01-15",
                    description = "Opening deposit",
                    currencyCode = "EUR",
                    splits = listOf(
                        SnapshotSplit(bank, Money(250_000, 100)),
                        SnapshotSplit(salary, Money(-250_000, 100)),
                    ),
                ),
            ),
        )

        val document = XmlBookDocument.build(snapshot, templateRoot, DocumentSequenceIds(start = 1000))
        assertEquals(4, document.accounts().size) // root + bank + salary + template root
        assertEquals(1, document.transactions().size)
        assertEquals("EUR", document.currency()?.code)

        val reparsed = XmlBookDocument.parse(document.serialize())
        assertEquals(4, reparsed.accounts().size)
        assertEquals("Opening deposit", reparsed.transactions().first().description)
    }

    @Test
    fun `imports the real ~3,300-transaction synthetic ledger and reopens it`() {
        val snapshot = XmlBookReader.read(File("../fixtures/books/gronewald-gl-2023-01.gnucash"))
        val document = XmlBookDocument.build(snapshot, Guid.parse("f".repeat(32)), DocumentSequenceIds(start = 9000))

        assertEquals(3305, document.transactions().size)
        var total = Money.ZERO
        for (transaction in document.transactions()) {
            for (split in transaction.splits) total = Money.add(total, split.value)
        }
        assertEquals(Money.ZERO, total)

        val reparsed = XmlBookDocument.parse(document.serialize())
        assertEquals(3305, reparsed.transactions().size)
    }
}
