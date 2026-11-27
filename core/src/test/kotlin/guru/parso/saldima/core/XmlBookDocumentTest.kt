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
    fun `voids a transaction, zeroing its effect on balances without deleting it, then unvoids it`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val txGuid = Guid.parse("2".repeat(32))
        val bankSplit = Guid.parse("3".repeat(32))
        val salarySplit = Guid.parse("4".repeat(32))
        document.postTransaction(
            guid = txGuid,
            date = "2026-02-01",
            description = "Coffee",
            splits = listOf(Triple(bank, Money(-500, 100), bankSplit), Triple(salary, Money(500, 100), salarySplit)),
        )
        document.setSplitReconcileState(txGuid, bankSplit, "c")
        val bankBalanceBeforeVoid = assertNotNull(document.balances()[bank])

        document.voidTransaction(txGuid, "Entered in error", "2026-02-10 00:00:00 +0000")
        val voided = assertNotNull(document.transaction(txGuid))
        assertTrue(voided.voided)
        assertEquals("Entered in error", voided.voidReason)
        assertEquals("2026-02-10 00:00:00 +0000", voided.voidTime)
        val voidedSplit = voided.splits.first { it.guid == bankSplit }
        assertEquals(Money(0, 100), voidedSplit.value)
        assertEquals("v", voidedSplit.reconcileState)
        assertEquals(Money(-500, 100), voidedSplit.voidFormerValue)

        // Zeroed, so it no longer contributes to the balance -- the fixture's
        // own pre-existing "Opening deposit" transaction still does.
        val bankBalanceAfterVoid = assertNotNull(document.balances()[bank])
        assertEquals(Money.add(bankBalanceBeforeVoid, Money(500, 100)), bankBalanceAfterVoid)

        val reparsed = XmlBookDocument.parse(document.serialize())
        val reparsedVoided = assertNotNull(reparsed.transaction(txGuid))
        assertTrue(reparsedVoided.voided)
        assertEquals(Money(0, 100), reparsedVoided.splits.first { it.guid == bankSplit }.value)

        document.unvoidTransaction(txGuid)
        val unvoided = assertNotNull(document.transaction(txGuid))
        assertFalse(unvoided.voided)
        assertNull(unvoided.voidReason)
        val unvoidedSplit = unvoided.splits.first { it.guid == bankSplit }
        assertEquals(Money(-500, 100), unvoidedSplit.value)
        assertEquals("n", unvoidedSplit.reconcileState)
        assertNull(unvoidedSplit.voidFormerValue)
    }

    @Test
    fun `refuses to void an already-voided transaction, an unknown one, or a blank reason, and refuses to unvoid an unvoided one`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val txGuid = Guid.parse("2".repeat(32))
        document.postTransaction(
            guid = txGuid,
            date = "2026-02-01",
            description = "Coffee",
            splits = listOf(
                Triple(bank, Money(-500, 100), Guid.parse("3".repeat(32))),
                Triple(salary, Money(500, 100), Guid.parse("4".repeat(32))),
            ),
        )
        val unknown = Guid.parse("f".repeat(32))

        assertFailsWith<BookFormatException> {
            document.voidTransaction(unknown, "x", "2026-02-10 00:00:00 +0000")
        }
        assertFailsWith<BookFormatException> {
            document.voidTransaction(txGuid, "   ", "2026-02-10 00:00:00 +0000")
        }
        assertFailsWith<BookFormatException> { document.unvoidTransaction(txGuid) }

        document.voidTransaction(txGuid, "Entered in error", "2026-02-10 00:00:00 +0000")
        assertFailsWith<BookFormatException> {
            document.voidTransaction(txGuid, "Again", "2026-02-11 00:00:00 +0000")
        }
        assertFailsWith<BookFormatException> { document.unvoidTransaction(unknown) }
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
    fun `creates a vendor, a bill with lines, and posts it as a real transaction`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val vendorGuid = Guid.parse("1".repeat(32))

        val vendor = document.createVendor(
            vendorGuid,
            VendorDraft(name = "Acme Supplies", vendorNumber = "V-0001", email = "ap@acme.test"),
            "USD",
        )
        assertEquals("Acme Supplies", vendor.name)
        assertEquals(1, document.vendors().size)
        assertEquals("V-0001", assertNotNull(document.vendor(vendorGuid)).vendorNumber)

        document.updateVendor(vendorGuid, name = "Acme Supplies Inc")
        assertEquals("Acme Supplies Inc", assertNotNull(document.vendor(vendorGuid)).name)

        val billGuid = Guid.parse("2".repeat(32))
        val bill = document.createBill(
            billGuid,
            vendorGuid,
            billNumber = "BILL-1001",
            dateOpened = "2026-03-01",
            currencyCode = "USD",
        )
        assertNull(bill.datePosted)
        assertEquals(1, document.bills().size)
        // A bill shares GncInvoice with customer invoices but must never
        // show up as one, and vice versa.
        assertEquals(0, document.invoices().size)

        val lineGuid = Guid.parse("3".repeat(32))
        val entry = document.addBillEntry(
            lineGuid,
            billGuid,
            date = "2026-03-01",
            description = "Office supplies",
            quantity = Money(1, 1),
            unitPrice = Money(8000, 100),
            expenseAccountGuid = salary,
        )
        assertEquals(Money(8000, 100), entry.unitPrice)
        assertEquals(1, document.billEntries(billGuid).size)

        val txGuid = Guid.parse("4".repeat(32))
        document.postTransaction(
            guid = txGuid,
            date = "2026-03-01",
            description = "Bill BILL-1001",
            splits = listOf(
                Triple(salary, Money(8000, 100), Guid.parse("5".repeat(32))),
                Triple(bank, Money(-8000, 100), Guid.parse("6".repeat(32))),
            ),
        )
        document.markBillPosted(billGuid, "2026-03-01", txGuid, bank)

        val posted = assertNotNull(document.bill(billGuid))
        assertEquals("2026-03-01", posted.datePosted)
        assertEquals(txGuid, posted.postedTransactionGuid)
        assertEquals(bank, posted.payableAccountGuid)

        val reparsed = XmlBookDocument.parse(document.serialize())
        assertEquals(1, reparsed.vendors().size)
        assertEquals(1, reparsed.bills().size)
        assertEquals(1, reparsed.billEntries(billGuid).size)
        assertEquals("2026-03-01", assertNotNull(reparsed.bill(billGuid)).datePosted)
        assertEquals(0, reparsed.invoices().size)

        document.deleteVendor(vendorGuid)
        assertNull(document.vendor(vendorGuid))
    }

    @Test
    fun `refuses to delete a vendor that still has bills, and no-ops on unknown ids`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val vendorGuid = Guid.parse("1".repeat(32))
        document.createVendor(vendorGuid, VendorDraft(name = "Acme"), "USD")
        val billGuid = Guid.parse("2".repeat(32))
        document.createBill(billGuid, vendorGuid, "BILL-1", "2026-03-01", currencyCode = "USD")
        assertTrue(document.vendorInUse(vendorGuid))

        document.deleteBill(billGuid)
        assertNull(document.bill(billGuid))
        assertFalse(document.vendorInUse(vendorGuid))

        val unknown = Guid.parse("f".repeat(32))
        document.deleteVendor(unknown)
        document.deleteBill(unknown)
        document.deleteBillEntry(unknown)
        assertFailsWith<BookFormatException> { document.updateVendor(unknown, name = "x") }
        assertFailsWith<BookFormatException> { document.markBillPosted(unknown, "2026-01-01", unknown, unknown) }
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

    // MARK: - Scheduled transactions

    private fun createMonthlySalary(
        document: XmlBookDocument,
        sxGuid: Guid,
        bank: Guid,
        salary: Guid,
    ): XmlScheduledTransaction = document.createScheduledTransaction(
        guid = sxGuid,
        name = "Monthly salary",
        description = "Salary",
        currencyCode = "USD",
        start = "2026-01-15",
        recurrence = Recurrence(PeriodType.MONTH, 1, "2026-01-15", WeekendAdjust.NONE),
        templateSplits = listOf(
            Triple(bank, Money(50000, 100), Guid.parse("f0".repeat(16))),
            Triple(salary, Money(-50000, 100), Guid.parse("f1".repeat(16))),
        ),
        templateAccountGuid = Guid.parse("d".repeat(32)),
        templateTransactionGuid = Guid.parse("e".repeat(32)),
        autoCreate = true,
        advanceCreateDays = 2,
        advanceRemindDays = 5,
        templateRootGuid = Guid.parse("f2".repeat(16)),
    )

    @Test
    fun `creates a scheduled transaction matching real GnuCash's on-disk shape, and reads it back`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val sxGuid = Guid.parse("1".repeat(32))
        val sx = createMonthlySalary(document, sxGuid, bank, salary)

        assertEquals("Monthly salary", sx.name)
        assertEquals("Salary", sx.description)
        assertTrue(sx.enabled)
        assertTrue(sx.autoCreate)
        assertFalse(sx.autoCreateNotify)
        assertEquals(2, sx.advanceCreateDays)
        assertEquals(5, sx.advanceRemindDays)
        assertEquals("2026-01-15", sx.start)
        assertNull(sx.lastOccurred)
        assertEquals(0, sx.instanceCount)
        assertNull(sx.end)
        assertEquals(Recurrence(PeriodType.MONTH, 1, "2026-01-15", WeekendAdjust.NONE), sx.recurrence)
        assertEquals(
            listOf(XmlScheduledSplit(bank, Money(50000, 100)), XmlScheduledSplit(salary, Money(-50000, 100))),
            sx.templateSplits,
        )

        val serialized = String(document.serialize(), Charsets.UTF_8)
        assertTrue("<gnc:template-transactions>" in serialized)
        assertTrue("<act:name>Template Root</act:name>" in serialized)
        assertTrue("<act:type>ROOT</act:type>" in serialized)
        assertTrue("<cmdty:space>template</cmdty:space>" in serialized)
        assertTrue("<recurrence:period_type>month</recurrence:period_type>" in serialized)
        assertTrue("<slot:key>sched-xaction</slot:key>" in serialized)
        assertTrue("<slot:key>debit-formula</slot:key>" in serialized)
        // The unused side of each split's KVP is an empty formula and a zero
        // numeric, not omitted -- matching real GnuCash exactly.
        assertTrue("<slot:key>credit-formula</slot:key><slot:value type=\"string\"/>" in serialized)
        assertTrue(
            "<slot:key>credit-numeric</slot:key><slot:value type=\"numeric\">0/1</slot:value>" in serialized,
        )

        // Not a normal account/transaction: stays out of the everyday lists.
        assertFalse(document.accounts().any { it.guid == sx.templateAccountGuid })
        assertEquals(1, document.transactions().size) // only the fixture's own pre-existing one

        val reparsed = XmlBookDocument.parse(document.serialize())
        assertEquals(sx, reparsed.scheduledTransaction(sxGuid))
    }

    @Test
    fun `shares one Template Root across multiple scheduled transactions`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        createMonthlySalary(document, Guid.parse("1".repeat(32)), bank, salary)

        val second = document.createScheduledTransaction(
            guid = Guid.parse("2".repeat(32)),
            name = "Rent",
            description = "Rent",
            currencyCode = "USD",
            start = "2026-02-01",
            recurrence = Recurrence(PeriodType.MONTH, 1, "2026-02-01", WeekendAdjust.NONE),
            templateSplits = listOf(
                Triple(salary, Money(120000, 100), Guid.parse("a0".repeat(16))),
                Triple(bank, Money(-120000, 100), Guid.parse("a1".repeat(16))),
            ),
            templateAccountGuid = Guid.parse("3".repeat(32)),
            templateTransactionGuid = Guid.parse("4".repeat(32)),
            // No templateRootGuid -- must reuse the one the first SX created.
        )
        assertEquals(2, document.scheduledTransactions().size)
        val serialized = String(document.serialize(), Charsets.UTF_8)
        val rootCount = Regex("<act:type>ROOT</act:type>").findAll(serialized).count()
        assertEquals(2, rootCount) // the fixture's own book root + one shared template root
        assertTrue(second.enabled)
        assertFalse(second.autoCreate)
    }

    @Test
    fun `refuses to create the first scheduled transaction without a template-root id, an unbalanced template, too few splits, or a duplicate guid`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)

        assertFailsWith<BookFormatException> {
            document.createScheduledTransaction(
                guid = Guid.parse("1".repeat(32)),
                name = "x",
                description = "x",
                currencyCode = "USD",
                start = "2026-01-01",
                recurrence = Recurrence(PeriodType.MONTH, 1, "2026-01-01", WeekendAdjust.NONE),
                templateSplits = listOf(
                    Triple(bank, Money(100, 100), Guid.parse("f0".repeat(16))),
                    Triple(salary, Money(-100, 100), Guid.parse("f1".repeat(16))),
                ),
                templateAccountGuid = Guid.parse("d".repeat(32)),
                templateTransactionGuid = Guid.parse("e".repeat(32)),
                // No templateRootGuid, and none exists yet.
            )
        }

        assertFailsWith<BookFormatException> {
            document.createScheduledTransaction(
                guid = Guid.parse("1".repeat(32)),
                name = "x",
                description = "x",
                currencyCode = "USD",
                start = "2026-01-01",
                recurrence = Recurrence(PeriodType.MONTH, 1, "2026-01-01", WeekendAdjust.NONE),
                templateSplits = listOf(Triple(bank, Money(100, 100), Guid.parse("f0".repeat(16)))),
                templateAccountGuid = Guid.parse("d".repeat(32)),
                templateTransactionGuid = Guid.parse("e".repeat(32)),
                templateRootGuid = Guid.parse("f2".repeat(16)),
            )
        }

        assertFailsWith<BookFormatException> {
            document.createScheduledTransaction(
                guid = Guid.parse("1".repeat(32)),
                name = "x",
                description = "x",
                currencyCode = "USD",
                start = "2026-01-01",
                recurrence = Recurrence(PeriodType.MONTH, 1, "2026-01-01", WeekendAdjust.NONE),
                templateSplits = listOf(
                    Triple(bank, Money(100, 100), Guid.parse("f0".repeat(16))),
                    Triple(salary, Money(-99, 100), Guid.parse("f1".repeat(16))),
                ),
                templateAccountGuid = Guid.parse("d".repeat(32)),
                templateTransactionGuid = Guid.parse("e".repeat(32)),
                templateRootGuid = Guid.parse("f2".repeat(16)),
            )
        }

        createMonthlySalary(document, Guid.parse("1".repeat(32)), bank, salary)
        assertFailsWith<BookFormatException> { createMonthlySalary(document, Guid.parse("1".repeat(32)), bank, salary) }
    }

    @Test
    fun `updates the simple fields of a scheduled transaction, and rejects an unknown guid`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val sxGuid = Guid.parse("1".repeat(32))
        createMonthlySalary(document, sxGuid, bank, salary)

        val updated = document.updateScheduledTransaction(
            sxGuid,
            name = "Renamed",
            enabled = false,
            autoCreate = false,
            autoCreateNotify = true,
            advanceCreateDays = 10,
            advanceRemindDays = 20,
        )
        assertEquals("Renamed", updated.name)
        assertFalse(updated.enabled)
        assertFalse(updated.autoCreate)
        assertTrue(updated.autoCreateNotify)
        assertEquals(10, updated.advanceCreateDays)
        assertEquals(20, updated.advanceRemindDays)

        assertFailsWith<BookFormatException> {
            document.updateScheduledTransaction(Guid.parse("f".repeat(32)), name = "x")
        }

        // A partial update leaves every other field untouched.
        val partial = document.updateScheduledTransaction(sxGuid, name = "Renamed again")
        assertEquals("Renamed again", partial.name)
        assertFalse(partial.enabled)
        assertFalse(partial.autoCreate)
        assertTrue(partial.autoCreateNotify)
        assertEquals(10, partial.advanceCreateDays)
        assertEquals(20, partial.advanceRemindDays)
    }

    @Test
    fun `round-trips a weekend-adjusted recurrence`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val sxGuid = Guid.parse("1".repeat(32))
        val sx = document.createScheduledTransaction(
            guid = sxGuid,
            name = "Weekend adjusted",
            description = "Weekend adjusted",
            currencyCode = "USD",
            start = "2026-01-15",
            recurrence = Recurrence(PeriodType.WEEK, 1, "2026-01-15", WeekendAdjust.FORWARD),
            templateSplits = listOf(
                Triple(bank, Money(100, 100), Guid.parse("f0".repeat(16))),
                Triple(salary, Money(-100, 100), Guid.parse("f1".repeat(16))),
            ),
            templateAccountGuid = Guid.parse("d".repeat(32)),
            templateTransactionGuid = Guid.parse("e".repeat(32)),
            templateRootGuid = Guid.parse("f2".repeat(16)),
        )
        assertEquals(WeekendAdjust.FORWARD, sx.recurrence.weekendAdjust)
        val reparsed = XmlBookDocument.parse(document.serialize())
        assertEquals(WeekendAdjust.FORWARD, reparsed.scheduledTransaction(sxGuid)?.recurrence?.weekendAdjust)
    }

    @Test
    fun `can be created disabled, with autoCreateNotify on, and in a currency with no commodity entry yet`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val sx = document.createScheduledTransaction(
            guid = Guid.parse("1".repeat(32)),
            name = "Disabled, notify-on-create, unknown currency",
            description = "x",
            currencyCode = "GBP",
            enabled = false,
            autoCreate = true,
            autoCreateNotify = true,
            start = "2026-01-15",
            recurrence = Recurrence(PeriodType.MONTH, 1, "2026-01-15", WeekendAdjust.NONE),
            templateSplits = listOf(
                Triple(bank, Money(100, 100), Guid.parse("f0".repeat(16))),
                Triple(salary, Money(-100, 100), Guid.parse("f1".repeat(16))),
            ),
            templateAccountGuid = Guid.parse("d".repeat(32)),
            templateTransactionGuid = Guid.parse("e".repeat(32)),
            templateRootGuid = Guid.parse("f2".repeat(16)),
        )
        assertFalse(sx.enabled)
        assertTrue(sx.autoCreate)
        assertTrue(sx.autoCreateNotify)
        // A currency with no existing commodity entry falls back to the
        // conventional 100 (cents) fraction for the zero placeholder value.
        assertTrue("<split:value>0/100</split:value>" in String(document.serialize(), Charsets.UTF_8))
    }

    @Test
    fun `uses an existing commodity's own fraction for the zero placeholder value`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        document.createScheduledTransaction(
            guid = Guid.parse("1".repeat(32)),
            name = "Euro schedule",
            description = "x",
            currencyCode = "USD",
            start = "2026-01-15",
            recurrence = Recurrence(PeriodType.MONTH, 1, "2026-01-15", WeekendAdjust.NONE),
            templateSplits = listOf(
                Triple(bank, Money(100, 100), Guid.parse("f0".repeat(16))),
                Triple(salary, Money(-100, 100), Guid.parse("f1".repeat(16))),
            ),
            templateAccountGuid = Guid.parse("d".repeat(32)),
            templateTransactionGuid = Guid.parse("e".repeat(32)),
            templateRootGuid = Guid.parse("f2".repeat(16)),
        )
        assertTrue("<split:value>0/100</split:value>" in String(document.serialize(), Charsets.UTF_8))
    }

    @Test
    fun `defaults missing schedule and template data when reading a hand-written scheduled transaction`() {
        val document = XmlBookDocument.parse(
            """<?xml version="1.0" encoding="utf-8" ?>
<gnc-v2 xmlns:gnc="http://www.gnucash.org/XML/gnc" xmlns:book="http://www.gnucash.org/XML/book" xmlns:sx="http://www.gnucash.org/XML/sx">
<gnc:book version="2.0.0">
<book:id type="guid">${"9".repeat(32)}</book:id>
<gnc:schedxaction version="2.0.0">
  <sx:id type="guid">${"1".repeat(32)}</sx:id>
  <sx:name>Minimal</sx:name>
  <sx:advanceCreateDays>0</sx:advanceCreateDays>
  <sx:advanceRemindDays>0</sx:advanceRemindDays>
  <sx:instanceCount>0</sx:instanceCount>
  <sx:start><gdate>2026-01-01</gdate></sx:start>
  <sx:templ-acct type="guid">${"f".repeat(32)}</sx:templ-acct>
</gnc:schedxaction>
</gnc:book>
</gnc-v2>""".toByteArray(Charsets.UTF_8),
        )
        val sx = assertNotNull(document.scheduledTransaction(Guid.parse("1".repeat(32))))
        assertEquals("Minimal", sx.name)
        assertEquals("", sx.description)
        assertEquals("USD", sx.currencyCode)
        assertTrue(sx.enabled)
        assertFalse(sx.autoCreate)
        assertFalse(sx.autoCreateNotify)
        assertEquals(Recurrence(PeriodType.ONCE, 1, "2026-01-01", WeekendAdjust.NONE), sx.recurrence)
        assertEquals(emptyList(), sx.templateSplits)
    }

    @Test
    fun `posts a due instance, advancing sx last and instanceCount, and rejects an unknown guid`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val sxGuid = Guid.parse("1".repeat(32))
        createMonthlySalary(document, sxGuid, bank, salary)

        val posted = document.postScheduledInstance(
            sxGuid,
            "2026-01-15",
            Guid.parse("9".repeat(32)),
            listOf(Guid.parse("b0".repeat(16)), Guid.parse("b1".repeat(16))),
        )
        assertEquals("Salary", posted.description)
        assertEquals(
            listOf(bank to Money(50000, 100), salary to Money(-50000, 100)),
            posted.splits.map { it.accountGuid to it.value },
        )

        val advanced = assertNotNull(document.scheduledTransaction(sxGuid))
        assertEquals("2026-01-15", advanced.lastOccurred)
        assertEquals(1, advanced.instanceCount)

        // Posting again updates sx:last in place rather than duplicating it.
        document.postScheduledInstance(
            sxGuid,
            "2026-02-15",
            Guid.parse("a".repeat(32)),
            listOf(Guid.parse("d0".repeat(16)), Guid.parse("d1".repeat(16))),
        )
        val advancedAgain = assertNotNull(document.scheduledTransaction(sxGuid))
        assertEquals("2026-02-15", advancedAgain.lastOccurred)
        assertEquals(2, advancedAgain.instanceCount)

        assertFailsWith<BookFormatException> {
            document.postScheduledInstance(Guid.parse("f".repeat(32)), "2026-01-01", Guid.parse("c".repeat(32)), emptyList())
        }
    }

    @Test
    fun `skips a due instance, advancing sx last without posting anything, and rejects an unknown guid`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val sxGuid = Guid.parse("1".repeat(32))
        createMonthlySalary(document, sxGuid, bank, salary)
        val transactionCountBefore = document.transactions().size

        document.skipScheduledInstance(sxGuid, "2026-01-15")
        val advanced = assertNotNull(document.scheduledTransaction(sxGuid))
        assertEquals("2026-01-15", advanced.lastOccurred)
        assertEquals(1, advanced.instanceCount)
        assertEquals(transactionCountBefore, document.transactions().size)

        assertFailsWith<BookFormatException> {
            document.skipScheduledInstance(Guid.parse("f".repeat(32)), "2026-01-01")
        }
    }

    @Test
    fun `classifies due instances as auto-post, auto-post-notify, needs-approval or reminder`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)

        // Auto-posts silently: due within advanceCreateDays, autoCreate on, no notify.
        val autoPost = document.createScheduledTransaction(
            guid = Guid.parse("1".repeat(32)),
            name = "Auto",
            description = "Auto",
            currencyCode = "USD",
            start = "2026-01-15",
            recurrence = Recurrence(PeriodType.MONTH, 1, "2026-01-15", WeekendAdjust.NONE),
            templateSplits = listOf(
                Triple(bank, Money(100, 100), Guid.parse("f0".repeat(16))),
                Triple(salary, Money(-100, 100), Guid.parse("f1".repeat(16))),
            ),
            templateAccountGuid = Guid.parse("d1".repeat(16)),
            templateTransactionGuid = Guid.parse("e1".repeat(16)),
            autoCreate = true,
            advanceCreateDays = 5,
            templateRootGuid = Guid.parse("f2".repeat(16)),
        )
        // Auto-posts but still flagged for review.
        val autoPostNotify = document.createScheduledTransaction(
            guid = Guid.parse("2".repeat(32)),
            name = "AutoNotify",
            description = "AutoNotify",
            currencyCode = "USD",
            start = "2026-01-15",
            recurrence = Recurrence(PeriodType.MONTH, 1, "2026-01-15", WeekendAdjust.NONE),
            templateSplits = listOf(
                Triple(bank, Money(100, 100), Guid.parse("f3".repeat(16))),
                Triple(salary, Money(-100, 100), Guid.parse("f4".repeat(16))),
            ),
            templateAccountGuid = Guid.parse("d2".repeat(16)),
            templateTransactionGuid = Guid.parse("e2".repeat(16)),
            autoCreate = true,
            autoCreateNotify = true,
            advanceCreateDays = 5,
        )
        // Within advanceCreateDays but autoCreate is off -- needs a human decision.
        val needsApproval = document.createScheduledTransaction(
            guid = Guid.parse("3".repeat(32)),
            name = "Manual",
            description = "Manual",
            currencyCode = "USD",
            start = "2026-01-15",
            recurrence = Recurrence(PeriodType.MONTH, 1, "2026-01-15", WeekendAdjust.NONE),
            templateSplits = listOf(
                Triple(bank, Money(100, 100), Guid.parse("f5".repeat(16))),
                Triple(salary, Money(-100, 100), Guid.parse("f6".repeat(16))),
            ),
            templateAccountGuid = Guid.parse("d3".repeat(16)),
            templateTransactionGuid = Guid.parse("e3".repeat(16)),
            advanceCreateDays = 5,
        )
        // Further out than advanceCreateDays but within advanceRemindDays of it.
        val reminder = document.createScheduledTransaction(
            guid = Guid.parse("4".repeat(32)),
            name = "Reminder",
            description = "Reminder",
            currencyCode = "USD",
            start = "2026-02-01",
            recurrence = Recurrence(PeriodType.MONTH, 1, "2026-02-01", WeekendAdjust.NONE),
            templateSplits = listOf(
                Triple(bank, Money(100, 100), Guid.parse("f7".repeat(16))),
                Triple(salary, Money(-100, 100), Guid.parse("f8".repeat(16))),
            ),
            templateAccountGuid = Guid.parse("d4".repeat(16)),
            templateTransactionGuid = Guid.parse("e4".repeat(16)),
            advanceCreateDays = 1,
            advanceRemindDays = 20,
        )
        // Disabled -- never due, even though it would otherwise match.
        document.createScheduledTransaction(
            guid = Guid.parse("5".repeat(32)),
            name = "Off",
            description = "Off",
            currencyCode = "USD",
            start = "2026-01-15",
            recurrence = Recurrence(PeriodType.MONTH, 1, "2026-01-15", WeekendAdjust.NONE),
            templateSplits = listOf(
                Triple(bank, Money(100, 100), Guid.parse("f9".repeat(16))),
                Triple(salary, Money(-100, 100), Guid.parse("fa".repeat(16))),
            ),
            templateAccountGuid = Guid.parse("d5".repeat(16)),
            templateTransactionGuid = Guid.parse("e5".repeat(16)),
            enabled = false,
            autoCreate = true,
            advanceCreateDays = 5,
        )
        // Already exhausted its occurrence count -- never due again.
        document.createScheduledTransaction(
            guid = Guid.parse("6".repeat(32)),
            name = "Exhausted",
            description = "Exhausted",
            currencyCode = "USD",
            start = "2026-01-15",
            recurrence = Recurrence(PeriodType.MONTH, 1, "2026-01-15", WeekendAdjust.NONE),
            templateSplits = listOf(
                Triple(bank, Money(100, 100), Guid.parse("fb".repeat(16))),
                Triple(salary, Money(-100, 100), Guid.parse("fc".repeat(16))),
            ),
            templateAccountGuid = Guid.parse("d6".repeat(16)),
            templateTransactionGuid = Guid.parse("e6".repeat(16)),
            advanceCreateDays = 5,
            end = ScheduledEnd.Occurrences(0),
        )

        val due = document.dueScheduledInstances("2026-01-14")
        val byGuid = due.associateBy { it.scheduledTransactionGuid }
        assertEquals(DueInstanceAction.AUTO_POST, byGuid[autoPost.guid]?.action)
        assertEquals(DueInstanceAction.AUTO_POST_NOTIFY, byGuid[autoPostNotify.guid]?.action)
        assertEquals(DueInstanceAction.NEEDS_APPROVAL, byGuid[needsApproval.guid]?.action)
        assertEquals(DueInstanceAction.REMINDER, byGuid[reminder.guid]?.action)
        assertEquals("2026-01-15", byGuid[autoPost.guid]?.date)
        assertEquals(4, due.size) // the disabled and exhausted schedules never appear
    }

    @Test
    fun `deleteScheduledTransaction also removes its template account and transaction, and no-ops on an unknown guid`() {
        val document = XmlBookDocument.parse(gnucashBytes("oracle-mini.gnucash"))
        val bank = assertNotNull(document.accounts().firstOrNull { it.name == "Bank" }?.guid)
        val salary = assertNotNull(document.accounts().firstOrNull { it.name == "Salary" }?.guid)
        val sxGuid = Guid.parse("1".repeat(32))
        val sx = createMonthlySalary(document, sxGuid, bank, salary)

        document.deleteScheduledTransaction(sxGuid)
        assertNull(document.scheduledTransaction(sxGuid))
        val serialized = String(document.serialize(), Charsets.UTF_8)
        assertFalse(sx.templateAccountGuid.raw in serialized)

        document.deleteScheduledTransaction(Guid.parse("f".repeat(32)))
    }
}
