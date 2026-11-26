// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `reconciles a split, requires a date to mark it reconciled, and rejects unknown ids`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val txGuid = Guid.parse("2".repeat(32))
        val split1 = Guid.parse("3".repeat(32))
        val split2 = Guid.parse("4".repeat(32))
        document.postTransaction(
            guid = txGuid,
            date = "2026-02-01",
            description = "Coffee",
            splits = listOf(Triple(bank, Money(-500, 100), split1), Triple(salary, Money(500, 100), split2)),
        )

        document.setSplitReconcileState(txGuid, split1, "c")
        var split = assertNotNull(document.transaction(txGuid)).splits.first { it.guid == split1 }
        assertEquals("c", split.reconcileState)
        assertNull(split.reconcileDate)

        document.setSplitReconcileState(txGuid, split1, "y", "2026-02-05")
        split = assertNotNull(document.transaction(txGuid)).splits.first { it.guid == split1 }
        assertEquals("y", split.reconcileState)
        assertEquals("2026-02-05", split.reconcileDate)

        // Setting it again with a new date updates it in place.
        document.setSplitReconcileState(txGuid, split1, "y", "2026-02-06")
        split = assertNotNull(document.transaction(txGuid)).splits.first { it.guid == split1 }
        assertEquals("2026-02-06", split.reconcileDate)

        val reparsed = XmlBookDocument.parse(document.serialize())
        val reparsedSplit = assertNotNull(reparsed.transaction(txGuid)).splits.first { it.guid == split1 }
        assertEquals("y", reparsedSplit.reconcileState)
        assertEquals("2026-02-06", reparsedSplit.reconcileDate)

        document.setSplitReconcileState(txGuid, split1, "n")
        split = assertNotNull(document.transaction(txGuid)).splits.first { it.guid == split1 }
        assertEquals("n", split.reconcileState)
        assertNull(split.reconcileDate)

        assertFailsWith<BookFormatException> { document.setSplitReconcileState(txGuid, split1, "y") }
        assertFailsWith<BookFormatException> {
            document.setSplitReconcileState(Guid.parse("f".repeat(32)), split1, "c")
        }
        assertFailsWith<BookFormatException> { document.setSplitReconcileState(txGuid, Guid.parse("f".repeat(32)), "c") }
    }

    @Test
    fun `attaches, updates and clears a receipt doc-link on a posted transaction`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val txGuid = Guid.parse("2".repeat(32))
        document.postTransaction(
            guid = txGuid,
            date = "2026-02-01",
            description = "Office supplies",
            splits = listOf(
                Triple(bank, Money(-4200, 100), Guid.parse("3".repeat(32))),
                Triple(salary, Money(4200, 100), Guid.parse("4".repeat(32))),
            ),
        )
        assertNull(assertNotNull(document.transaction(txGuid)).docLink)

        document.setTransactionDocLink(txGuid, "receipts/one.jpg")
        assertEquals("receipts/one.jpg", assertNotNull(document.transaction(txGuid)).docLink)
        document.setTransactionDocLink(txGuid, "receipts/two.jpg")
        assertEquals("receipts/two.jpg", assertNotNull(document.transaction(txGuid)).docLink)

        val reparsed = XmlBookDocument.parse(document.serialize())
        assertEquals("receipts/two.jpg", assertNotNull(reparsed.transaction(txGuid)).docLink)

        document.setTransactionDocLink(txGuid, null)
        assertNull(assertNotNull(document.transaction(txGuid)).docLink)
        // Clearing an absent doc-link is a no-op.
        document.setTransactionDocLink(txGuid, null)
        assertNull(assertNotNull(document.transaction(txGuid)).docLink)

        assertFailsWith<BookFormatException> { document.setTransactionDocLink(Guid.parse("f".repeat(32)), "x") }
    }

    @Test
    fun `creates a customer, an invoice with lines, and posts it as a real transaction`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val customerGuid = Guid.parse("c".repeat(32))

        val customer = document.createCustomer(
            customerGuid,
            CustomerDraft(name = "Acme Holdings", customerNumber = "C-0001", email = "billing@acme.test"),
            "USD",
        )
        assertEquals("Acme Holdings", customer.name)
        assertEquals(1, document.customers().size)
        assertEquals("C-0001", assertNotNull(document.customer(customerGuid)).customerNumber)

        document.updateCustomer(customerGuid, name = "Acme Holdings LLC")
        assertEquals("Acme Holdings LLC", assertNotNull(document.customer(customerGuid)).name)

        val invoiceGuid = Guid.parse("d".repeat(32))
        val invoice = document.createInvoice(
            invoiceGuid,
            customerGuid,
            invoiceNumber = "INV-1001",
            dateOpened = "2026-03-01",
            notes = "March premium",
            currencyCode = "USD",
        )
        assertNull(invoice.datePosted)
        assertEquals(1, document.invoices().size)

        val lineGuid = Guid.parse("e".repeat(32))
        val entry = document.addInvoiceEntry(
            lineGuid,
            invoiceGuid,
            date = "2026-03-01",
            description = "Monthly premium",
            quantity = Money(1, 1),
            unitPrice = Money(25000, 100),
            incomeAccountGuid = salary,
        )
        assertEquals(Money(25000, 100), entry.unitPrice)
        assertEquals(1, document.invoiceEntries(invoiceGuid).size)

        val txGuid = Guid.parse("f".repeat(32))
        document.postTransaction(
            guid = txGuid,
            date = "2026-03-01",
            description = "Invoice INV-1001",
            splits = listOf(
                Triple(bank, Money(25000, 100), Guid.parse("1".repeat(32))),
                Triple(salary, Money(-25000, 100), Guid.parse("2".repeat(32))),
            ),
        )
        document.markInvoicePosted(invoiceGuid, "2026-03-01", txGuid, bank)

        val posted = assertNotNull(document.invoice(invoiceGuid))
        assertEquals("2026-03-01", posted.datePosted)
        assertEquals(txGuid, posted.postedTransactionGuid)
        assertEquals(bank, posted.receivableAccountGuid)

        val reparsed = XmlBookDocument.parse(document.serialize())
        assertEquals(1, reparsed.customers().size)
        assertEquals(1, reparsed.invoices().size)
        assertEquals(1, reparsed.invoiceEntries(invoiceGuid).size)
        assertEquals("2026-03-01", assertNotNull(reparsed.invoice(invoiceGuid)).datePosted)

        document.deleteCustomer(customerGuid)
        assertNull(document.customer(customerGuid))
    }

    @Test
    fun `refuses to delete a customer that still has invoices, and no-ops on unknown ids`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val customerGuid = Guid.parse("c".repeat(32))
        document.createCustomer(customerGuid, CustomerDraft(name = "Acme"), "USD")
        val invoiceGuid = Guid.parse("d".repeat(32))
        document.createInvoice(invoiceGuid, customerGuid, "INV-1", "2026-03-01", currencyCode = "USD")
        assertTrue(document.customerInUse(customerGuid))

        document.deleteInvoice(invoiceGuid)
        assertNull(document.invoice(invoiceGuid))
        assertFalse(document.customerInUse(customerGuid))

        val unknown = Guid.parse("f".repeat(32))
        document.deleteCustomer(unknown)
        document.deleteInvoice(unknown)
        document.deleteInvoiceEntry(unknown)
        assertFailsWith<BookFormatException> { document.updateCustomer(unknown, name = "x") }
        assertFailsWith<BookFormatException> { document.markInvoicePosted(unknown, "2026-01-01", unknown, unknown) }
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
