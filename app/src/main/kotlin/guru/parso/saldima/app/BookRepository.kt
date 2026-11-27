// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import guru.parso.saldima.core.AccountType
import guru.parso.saldima.core.BookFiles
import guru.parso.saldima.core.BookFormat
import guru.parso.saldima.core.BookFormatException
import guru.parso.saldima.core.BookSnapshot
import guru.parso.saldima.core.CustomerDraft
import guru.parso.saldima.core.DueInstanceAction
import guru.parso.saldima.core.DueScheduledInstance
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.IdGenerator
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.Recurrence
import guru.parso.saldima.core.RegisterExportRow
import guru.parso.saldima.core.ScheduledEnd
import guru.parso.saldima.core.SqliteBookReader
import guru.parso.saldima.core.SqliteRows
import guru.parso.saldima.core.Totals
import guru.parso.saldima.core.VendorDraft
import guru.parso.saldima.core.XmlBill
import guru.parso.saldima.core.XmlBillEntry
import guru.parso.saldima.core.XmlBookDocument
import guru.parso.saldima.core.XmlCustomer
import guru.parso.saldima.core.XmlInvoice
import guru.parso.saldima.core.XmlInvoiceEntry
import guru.parso.saldima.core.XmlScheduledTransaction
import guru.parso.saldima.core.XmlVendor
import guru.parso.saldima.core.registerExportFilename
import guru.parso.saldima.core.registerExportRows
import guru.parso.saldima.core.serializeCsv
import guru.parso.saldima.core.serializeQif
import java.io.File
import java.util.UUID

/**
 * Owns the first company file on this device. Standalone: no network.
 *
 * GnuCash XML is the canonical on-disk book (`XmlBookDocument`): the whole
 * file is parsed once on open, mutated in memory, and atomically rewritten
 * on every save -- the same way desktop GnuCash's own XML backend behaves.
 * SQLite is an import/export adapter only: a legacy SQLite-dialect file
 * still opens transparently (read into the equivalent in-memory XML model
 * via `SqliteBookReader` + `XmlBookDocument.build`) but is rewritten as XML
 * the first time anything saves.
 */
class BookRepository(private val context: Context) {
    var snapshot by mutableStateOf<BookSnapshot?>(null)
        private set
    var totals by mutableStateOf<Map<Guid, Money>>(emptyMap())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var books by mutableStateOf<List<File>>(emptyList())
        private set
    var currentName by mutableStateOf<String?>(null)
        private set
    var customers by mutableStateOf<List<XmlCustomer>>(emptyList())
        private set
    var invoices by mutableStateOf<List<XmlInvoice>>(emptyList())
        private set
    var vendors by mutableStateOf<List<XmlVendor>>(emptyList())
        private set
    var bills by mutableStateOf<List<XmlBill>>(emptyList())
        private set
    var scheduledTransactions by mutableStateOf<List<XmlScheduledTransaction>>(emptyList())
        private set
    var dueScheduledReview by mutableStateOf<List<DueScheduledInstance>>(emptyList())
        private set
    var autoPostedCount by mutableIntStateOf(0)
        private set

    private val directory: File get() = File(context.filesDir, "books").apply { mkdirs() }
    private val receiptsDirectory: File get() = File(context.filesDir, "receipts").apply { mkdirs() }
    private var document: XmlBookDocument? = null
    private var currentFile: File? = null
    private val ids = IdGenerator {
        Guid.parse(UUID.randomUUID().toString().replace("-", ""))
    }

    /** Writes a gzip GnuCash XML export of the open book to a chosen document. */
    fun exportXml(uri: Uri): Boolean {
        val loaded = document
        if (loaded == null) {
            error = "Open a book first."
            return false
        }
        return try {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(loaded.gzip())
            } ?: run {
                error = "Could not write that export."
                return false
            }
            error = null
            true
        } catch (failure: Exception) {
            error = "Could not export: ${failure.message}"
            false
        }
    }

    fun loadExisting() {
        refreshBooks()
        if (currentFile == null) books.firstOrNull()?.let(::read)
    }

    fun open(file: File) {
        read(file)
    }

    private fun refreshBooks() {
        books = directory.listFiles { candidate -> candidate.extension == "gnucash" }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
    }

    /**
     * Imports a `.gnucash` file. An XML book is copied in as-is (XML is
     * already the canonical format); a legacy SQLite-dialect book is
     * materialized into a fresh XML working copy, so either source opens
     * and edits exactly like a book created on-device.
     */
    fun import(uri: Uri) {
        try {
            val name = (uri.lastPathSegment ?: "book").substringAfterLast('/').ifBlank { "book" }
            val target = File(directory, if (name.endsWith(".gnucash")) name else "$name.gnucash")
            val staged = File.createTempFile("saldima-import", ".gnucash", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                error = "Could not read that file."
                staged.delete()
                return
            }
            when (BookFiles.detect(staged)) {
                BookFormat.XML -> staged.copyTo(target, overwrite = true)
                BookFormat.SQLITE -> {
                    val imported = readSqliteSnapshot(staged)
                    target.writeBytes(XmlBookDocument.build(imported, ids.next(), ids).gzip())
                }
            }
            staged.delete()
            refreshBooks()
            read(target)
        } catch (failure: Exception) {
            error = failure.message
        }
    }

    fun createAccount(
        name: String,
        type: AccountType,
        parentGuid: Guid,
        code: String = "",
        description: String = "",
    ): Guid? {
        val doc = document ?: run {
            error = "Open a book first."
            return null
        }
        val guid = ids.next()
        doc.createAccount(
            guid = guid,
            name = name,
            type = type.name,
            parentGuid = parentGuid,
            code = code,
            description = description,
        )
        error = null
        save()
        return guid
    }

    fun updateAccount(guid: Guid, name: String, code: String, description: String) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        if (name.isBlank()) {
            error = "Enter a name."
            return
        }
        val existing = doc.account(guid) ?: run {
            error = "Unknown account."
            return
        }
        doc.updateAccount(
            guid,
            name = name,
            type = existing.type,
            parentGuid = existing.parentGuid,
            code = code,
            description = description,
            hidden = existing.hidden,
            placeholder = existing.placeholder,
        )
        error = null
        save()
    }

    fun deleteAccount(guid: Guid) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        if (doc.accountCount(guid) > 0 || doc.splitCount(guid) > 0) {
            error = "Move or delete this account's children and transactions first."
            return
        }
        doc.deleteAccount(guid)
        error = null
        save()
    }

    /**
     * Posts a transaction with two or more splits, each a signed amount
     * (debit positive, credit negative); the splits used must sum to zero.
     * Two legs cover a simple payment; more support entries with a tax or
     * fee line (expense + input tax + payable, say).
     */
    fun post(date: String, description: String, splits: List<Pair<Guid, Money>>) {
        if (splits.size < 2) {
            error = "Use at least two splits."
            return
        }
        val total = splits.fold(Money.ZERO) { sum, (_, value) -> Money.add(sum, value) }
        if (total != Money.ZERO) {
            error = "The splits must sum to zero."
            return
        }
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        doc.postTransaction(
            guid = ids.next(),
            date = date,
            description = description,
            splits = splits.map { (account, value) -> Triple(account, value, ids.next()) },
        )
        error = null
        save()
    }

    private fun read(file: File) {
        try {
            val format = BookFiles.detect(file)
            val doc = when (format) {
                BookFormat.XML -> XmlBookDocument.parse(inflateIfGzip(file.readBytes()))
                BookFormat.SQLITE -> XmlBookDocument.build(readSqliteSnapshot(file), ids.next(), ids)
            }
            document = doc
            currentFile = file
            currentName = file.nameWithoutExtension
            refreshBooks()
            applySnapshot(doc)
            error = null
        } catch (failure: Exception) {
            error = "That file is not a GnuCash book: ${failure.message}"
        }
    }

    private fun applySnapshot(doc: XmlBookDocument) {
        val currency = doc.currency()
        val built = BookSnapshot(
            bookGuid = doc.bookGuid() ?: ids.next(),
            currencyCode = currency?.code,
            fraction = currency?.fraction,
            accounts = doc.accounts(),
            transactions = doc.transactions(),
        )
        snapshot = built
        totals = Totals.rolledUp(built)
        customers = doc.customers().sortedBy { it.name.lowercase() }
        invoices = doc.invoices().sortedByDescending { it.dateOpened }
        vendors = doc.vendors().sortedBy { it.name.lowercase() }
        bills = doc.bills().sortedByDescending { it.dateOpened }
        scheduledTransactions = doc.scheduledTransactions().sortedBy { it.name.lowercase() }
    }

    // MARK: - Clients (GncCustomer)

    fun invoiceEntries(invoiceGuid: Guid): List<XmlInvoiceEntry> = document?.invoiceEntries(invoiceGuid).orEmpty()

    fun createCustomer(draft: CustomerDraft) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        if (draft.name.isBlank()) {
            error = "Enter a name."
            return
        }
        doc.createCustomer(ids.next(), draft, snapshot?.currencyCode ?: "USD")
        error = null
        save()
    }

    fun updateCustomer(guid: Guid, draft: CustomerDraft) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        if (draft.name.isBlank()) {
            error = "Enter a name."
            return
        }
        doc.updateCustomer(
            guid,
            name = draft.name,
            customerNumber = draft.customerNumber,
            addressLine1 = draft.addressLine1,
            phone = draft.phone,
            email = draft.email,
            notes = draft.notes,
        )
        error = null
        save()
    }

    fun deleteCustomer(guid: Guid) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        if (doc.customerInUse(guid)) {
            error = "This client has invoices and cannot be deleted."
            return
        }
        doc.deleteCustomer(guid)
        error = null
        save()
    }

    // MARK: - Invoices and lines (GncInvoice / GncEntry)

    fun createInvoiceDraft(customerGuid: Guid, invoiceNumber: String, dateOpened: String): Guid? {
        val doc = document ?: run {
            error = "Open a book first."
            return null
        }
        if (invoiceNumber.isBlank()) {
            error = "Enter an invoice number."
            return null
        }
        val guid = ids.next()
        doc.createInvoice(
            guid,
            customerGuid,
            invoiceNumber = invoiceNumber,
            dateOpened = dateOpened,
            currencyCode = snapshot?.currencyCode ?: "USD",
        )
        error = null
        save()
        return guid
    }

    fun addInvoiceLine(
        invoiceGuid: Guid,
        date: String,
        description: String,
        quantity: Money,
        unitPrice: Money,
        incomeAccount: Guid,
    ) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        val invoice = doc.invoice(invoiceGuid)
        if (invoice == null || invoice.isPosted) {
            error = "A posted invoice cannot be edited."
            return
        }
        if (description.isBlank() || quantity == Money.ZERO || unitPrice == Money.ZERO) {
            error = "Fill in a description and a non-zero quantity and unit price."
            return
        }
        doc.addInvoiceEntry(ids.next(), invoiceGuid, date, description, quantity, unitPrice, incomeAccount)
        error = null
        save()
    }

    fun deleteInvoiceLine(invoiceGuid: Guid, lineGuid: Guid) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        val invoice = doc.invoice(invoiceGuid)
        if (invoice == null || invoice.isPosted) {
            error = "A posted invoice cannot be edited."
            return
        }
        doc.deleteInvoiceEntry(lineGuid)
        error = null
        save()
    }

    fun deleteInvoiceDraft(invoiceGuid: Guid) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        val invoice = doc.invoice(invoiceGuid)
        if (invoice == null || invoice.isPosted) {
            error = "A posted invoice cannot be deleted."
            return
        }
        doc.deleteInvoice(invoiceGuid)
        error = null
        save()
    }

    /**
     * Posts a draft invoice: debits the chosen receivable account for the
     * total and credits each line's income account -- the same effect as
     * desktop GnuCash's own "Post Invoice". One-way: a posted invoice is
     * permanent ledger history and can no longer be edited or deleted.
     */
    fun postInvoice(invoiceGuid: Guid, receivableAccount: Guid, postedDate: String) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        val invoice = doc.invoice(invoiceGuid)
        if (invoice == null || invoice.isPosted) {
            error = "This invoice is already posted."
            return
        }
        val entries = doc.invoiceEntries(invoiceGuid)
        if (entries.isEmpty()) {
            error = "Add at least one line before posting."
            return
        }
        var total = Money.ZERO
        val splits = entries.map { entry ->
            total = Money.add(total, entry.amount)
            Triple(entry.incomeAccountGuid, entry.amount.negated(), ids.next())
        } + Triple(receivableAccount, total, ids.next())
        val txGuid = ids.next()
        doc.postTransaction(txGuid, postedDate, "Invoice ${invoice.invoiceNumber}", splits)
        doc.markInvoicePosted(invoiceGuid, postedDate, txGuid, receivableAccount)
        error = null
        save()
    }

    // MARK: - Vendors (GncVendor)

    fun billEntries(billGuid: Guid): List<XmlBillEntry> = document?.billEntries(billGuid).orEmpty()

    fun createVendor(draft: VendorDraft) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        if (draft.name.isBlank()) {
            error = "Enter a name."
            return
        }
        doc.createVendor(ids.next(), draft, snapshot?.currencyCode ?: "USD")
        error = null
        save()
    }

    fun updateVendor(guid: Guid, draft: VendorDraft) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        if (draft.name.isBlank()) {
            error = "Enter a name."
            return
        }
        doc.updateVendor(
            guid,
            name = draft.name,
            vendorNumber = draft.vendorNumber,
            addressLine1 = draft.addressLine1,
            phone = draft.phone,
            email = draft.email,
            notes = draft.notes,
        )
        error = null
        save()
    }

    fun deleteVendor(guid: Guid) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        if (doc.vendorInUse(guid)) {
            error = "This vendor has bills and cannot be deleted."
            return
        }
        doc.deleteVendor(guid)
        error = null
        save()
    }

    // MARK: - Bills and lines (GncInvoice / GncEntry, vendor-owned)

    fun createBillDraft(vendorGuid: Guid, billNumber: String, dateOpened: String): Guid? {
        val doc = document ?: run {
            error = "Open a book first."
            return null
        }
        if (billNumber.isBlank()) {
            error = "Enter a bill number."
            return null
        }
        val guid = ids.next()
        doc.createBill(
            guid,
            vendorGuid,
            billNumber = billNumber,
            dateOpened = dateOpened,
            currencyCode = snapshot?.currencyCode ?: "USD",
        )
        error = null
        save()
        return guid
    }

    fun addBillLine(
        billGuid: Guid,
        date: String,
        description: String,
        quantity: Money,
        unitPrice: Money,
        expenseAccount: Guid,
    ) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        val bill = doc.bill(billGuid)
        if (bill == null || bill.isPosted) {
            error = "A posted bill cannot be edited."
            return
        }
        if (description.isBlank() || quantity == Money.ZERO || unitPrice == Money.ZERO) {
            error = "Fill in a description and a non-zero quantity and unit price."
            return
        }
        doc.addBillEntry(ids.next(), billGuid, date, description, quantity, unitPrice, expenseAccount)
        error = null
        save()
    }

    fun deleteBillLine(billGuid: Guid, lineGuid: Guid) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        val bill = doc.bill(billGuid)
        if (bill == null || bill.isPosted) {
            error = "A posted bill cannot be edited."
            return
        }
        doc.deleteBillEntry(lineGuid)
        error = null
        save()
    }

    fun deleteBillDraft(billGuid: Guid) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        val bill = doc.bill(billGuid)
        if (bill == null || bill.isPosted) {
            error = "A posted bill cannot be deleted."
            return
        }
        doc.deleteBill(billGuid)
        error = null
        save()
    }

    /**
     * Posts a draft bill: debits each line's expense account for its
     * amount and credits the chosen payable account for the total -- the
     * same effect as desktop GnuCash's own "Post Bill". One-way: a posted
     * bill is permanent ledger history and can no longer be edited or
     * deleted.
     */
    fun postBill(billGuid: Guid, payableAccount: Guid, postedDate: String) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        val bill = doc.bill(billGuid)
        if (bill == null || bill.isPosted) {
            error = "This bill is already posted."
            return
        }
        val entries = doc.billEntries(billGuid)
        if (entries.isEmpty()) {
            error = "Add at least one line before posting."
            return
        }
        var total = Money.ZERO
        val splits = entries.map { entry ->
            total = Money.add(total, entry.amount)
            Triple(entry.expenseAccountGuid, entry.amount, ids.next())
        } + Triple(payableAccount, total.negated(), ids.next())
        val txGuid = ids.next()
        doc.postTransaction(txGuid, postedDate, "Bill ${bill.billNumber}", splits)
        doc.markBillPosted(billGuid, postedDate, txGuid, payableAccount)
        error = null
        save()
    }

    // MARK: - Receipts (GnuCash "Associate File/Location")

    /** Captures a receipt image on-device and posts the expense it documents. */
    fun postReceiptExpense(
        bytes: ByteArray,
        extension: String,
        date: String,
        description: String,
        amount: Money,
        expenseAccount: Guid,
        paymentAccount: Guid,
    ) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        if (description.isBlank() || amount == Money.ZERO) {
            error = "Fill in a description and a non-zero amount."
            return
        }
        val relativePath = "receipts/${UUID.randomUUID()}.$extension"
        File(receiptsDirectory, relativePath.removePrefix("receipts/")).writeBytes(bytes)
        val txGuid = ids.next()
        doc.postTransaction(
            guid = txGuid,
            date = date,
            description = description,
            splits = listOf(
                Triple(expenseAccount, amount, ids.next()),
                Triple(paymentAccount, amount.negated(), ids.next()),
            ),
        )
        doc.setTransactionDocLink(txGuid, relativePath)
        error = null
        save()
    }

    fun receiptImage(relativePath: String): ByteArray? {
        val file = File(receiptsDirectory, relativePath.removePrefix("receipts/"))
        return if (file.exists()) file.readBytes() else null
    }

    // MARK: - Register export (QIF/CSV)

    private val exportsDirectory: File get() = File(context.cacheDir, "exports").apply { mkdirs() }

    /**
     * Writes one account's register as a QIF file (the same format
     * `packages/importers` reads and writes on the web track) to a cache
     * file and returns a `content://` URI the caller can share, or `null`
     * if there's no open book or the account is unknown.
     */
    fun exportQifUri(accountGuid: Guid): Uri? = exportRegisterUri(accountGuid, "qif", ::serializeQif)

    /** Same as [exportQifUri], but as a CSV file. */
    fun exportCsvUri(accountGuid: Guid): Uri? = exportRegisterUri(accountGuid, "csv", ::serializeCsv)

    private fun exportRegisterUri(
        accountGuid: Guid,
        extension: String,
        serialize: (List<RegisterExportRow>) -> String,
    ): Uri? {
        val snap = snapshot ?: return null
        val account = snap.accounts.firstOrNull { it.guid == accountGuid } ?: return null
        val names = snap.accounts.associate { it.guid to it.name }
        val rows = registerExportRows(snap.transactions, names, accountGuid)
        val filename = registerExportFilename(account.name, extension)
        val file = File(exportsDirectory, filename)
        file.writeText(serialize(rows))
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    // MARK: - Reconcile

    /** The same action as checking a row off in GnuCash's Reconcile window. */
    fun setSplitReconcileState(transactionGuid: Guid, splitGuid: Guid, state: String, reconcileDate: String? = null) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        doc.setSplitReconcileState(transactionGuid, splitGuid, state, reconcileDate)
        error = null
        save()
    }

    /**
     * Voids a transaction (GnuCash's "Void Transaction", not a delete): the
     * original splits are preserved for audit but zeroed, so the
     * transaction stops affecting any balance. Can be reversed with
     * [unvoidTransaction].
     */
    fun voidTransaction(transactionGuid: Guid, reason: String, voidTime: String) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        val transaction = doc.transaction(transactionGuid) ?: run {
            error = "That transaction is no longer in the book."
            return
        }
        if (reason.isBlank()) {
            error = "Enter a reason to void this transaction."
            return
        }
        if (transaction.voided) {
            error = "This transaction is already voided."
            return
        }
        doc.voidTransaction(transactionGuid, reason, voidTime)
        error = null
        save()
    }

    /** Reverses [voidTransaction]. A split's reconciled state before voiding is not restored. */
    fun unvoidTransaction(transactionGuid: Guid) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        val transaction = doc.transaction(transactionGuid) ?: run {
            error = "That transaction is no longer in the book."
            return
        }
        if (!transaction.voided) {
            error = "This transaction isn't voided."
            return
        }
        doc.unvoidTransaction(transactionGuid)
        error = null
        save()
    }

    // MARK: - Scheduled transactions (recurring bills, invoices and transfers)

    /**
     * Creates a scheduled transaction: a recurrence rule plus a template of
     * splits. Each split takes a signed amount (debit positive, credit
     * negative); the splits used must sum to zero, same as [post].
     */
    fun createScheduledTransaction(
        name: String,
        description: String,
        start: String,
        recurrence: Recurrence,
        end: ScheduledEnd?,
        autoCreate: Boolean,
        autoCreateNotify: Boolean,
        advanceCreateDays: Int,
        advanceRemindDays: Int,
        templateSplits: List<Pair<Guid, Money>>,
    ) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        if (name.isBlank()) {
            error = "Enter a name."
            return
        }
        if (description.isBlank()) {
            error = "Enter a description."
            return
        }
        if (templateSplits.size < 2) {
            error = "Use at least two splits."
            return
        }
        val total = templateSplits.fold(Money.ZERO) { sum, (_, value) -> Money.add(sum, value) }
        if (total != Money.ZERO) {
            error = "The splits must sum to zero."
            return
        }
        try {
            doc.createScheduledTransaction(
                guid = ids.next(),
                name = name,
                description = description,
                currencyCode = snapshot?.currencyCode ?: "USD",
                start = start,
                recurrence = recurrence,
                templateSplits = templateSplits.map { (account, value) -> Triple(account, value, ids.next()) },
                templateAccountGuid = ids.next(),
                templateTransactionGuid = ids.next(),
                autoCreate = autoCreate,
                autoCreateNotify = autoCreateNotify,
                advanceCreateDays = advanceCreateDays,
                advanceRemindDays = advanceRemindDays,
                end = end,
                // Ignored by the engine if a Template Root already exists for
                // this book -- only used the very first time any schedule is made.
                templateRootGuid = ids.next(),
            )
        } catch (failure: BookFormatException) {
            error = failure.message ?: "Could not create that schedule."
            return
        }
        error = null
        save()
    }

    fun updateScheduledTransaction(
        guid: Guid,
        name: String,
        enabled: Boolean,
        autoCreate: Boolean,
        autoCreateNotify: Boolean,
        advanceCreateDays: Int,
        advanceRemindDays: Int,
    ) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        if (name.isBlank()) {
            error = "Enter a name."
            return
        }
        doc.updateScheduledTransaction(
            guid,
            name = name,
            enabled = enabled,
            autoCreate = autoCreate,
            autoCreateNotify = autoCreateNotify,
            advanceCreateDays = advanceCreateDays,
            advanceRemindDays = advanceRemindDays,
        )
        error = null
        save()
    }

    fun deleteScheduledTransaction(guid: Guid) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        doc.deleteScheduledTransaction(guid)
        error = null
        save()
    }

    /**
     * "Check on open": computes every due instance as of `today` and acts on
     * it -- `AUTO_POST`/`AUTO_POST_NOTIFY` instances post immediately and
     * silently (notify ones are still listed in [dueScheduledReview], for
     * visibility); `NEEDS_APPROVAL`/`REMINDER` ones are only listed there for
     * the caller to act on by hand. This never runs on a schedule or in the
     * background -- only when this is called (from the journal screen, on
     * load), mirroring the web track's `processDueScheduledTransactions`.
     */
    fun checkDueScheduledTransactions(today: String) {
        val doc = document ?: return
        val due = doc.dueScheduledInstances(today)
        var posted = 0
        val review = mutableListOf<DueScheduledInstance>()
        for (instance in due) {
            when (instance.action) {
                DueInstanceAction.AUTO_POST, DueInstanceAction.AUTO_POST_NOTIFY -> {
                    val sx = doc.scheduledTransaction(instance.scheduledTransactionGuid)
                    if (sx != null) {
                        doc.postScheduledInstance(
                            instance.scheduledTransactionGuid,
                            instance.date,
                            ids.next(),
                            sx.templateSplits.map { ids.next() },
                        )
                        posted += 1
                    }
                    if (instance.action == DueInstanceAction.AUTO_POST_NOTIFY) review += instance
                }
                DueInstanceAction.NEEDS_APPROVAL, DueInstanceAction.REMINDER -> review += instance
            }
        }
        dueScheduledReview = review
        autoPostedCount = posted
        if (posted > 0) {
            error = null
            save()
        }
    }

    /** A `NEEDS_APPROVAL` due instance from the review banner: posts it the same way an auto-created instance would be. */
    fun approveScheduledInstance(guid: Guid, date: String) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        val sx = doc.scheduledTransaction(guid) ?: run {
            error = "That scheduled transaction is no longer in the book."
            return
        }
        doc.postScheduledInstance(guid, date, ids.next(), sx.templateSplits.map { ids.next() })
        dueScheduledReview = dueScheduledReview.filterNot { it.scheduledTransactionGuid == guid && it.date == date }
        error = null
        save()
    }

    /** Permanently skips a due instance without posting it -- Milestone 1 has no "postpone". */
    fun skipScheduledInstance(guid: Guid, date: String) {
        val doc = document ?: run {
            error = "Open a book first."
            return
        }
        doc.skipScheduledInstance(guid, date)
        dueScheduledReview = dueScheduledReview.filterNot { it.scheduledTransactionGuid == guid && it.date == date }
        error = null
        save()
    }

    /**
     * Atomically rewrites the whole book, the way desktop GnuCash's XML
     * backend saves: write a temporary file, then replace the original. A
     * fixed name is safe -- the repository is only ever driven from the
     * main thread, so there is never more than one writer at a time.
     */
    private fun save() {
        val doc = document ?: return
        val file = currentFile ?: return
        val temp = File(file.parentFile, ".${file.name}-saving.tmp")
        try {
            temp.writeBytes(doc.gzip())
            if (file.exists()) file.delete()
            temp.renameTo(file)
            applySnapshot(doc)
        } catch (failure: Exception) {
            temp.delete()
            error = "Could not save: ${failure.message}"
        }
    }

    private fun inflateIfGzip(bytes: ByteArray): ByteArray {
        if (bytes.size < 2 || bytes[0] != 0x1f.toByte() || bytes[1] != 0x8b.toByte()) return bytes
        return java.util.zip.GZIPInputStream(bytes.inputStream()).readBytes()
    }

    private fun readSqliteSnapshot(file: File): BookSnapshot {
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            SqliteBookReader.read(QueryRows(database))
        } finally {
            database?.close()
        }
    }

    private class QueryRows(private val database: SQLiteDatabase) : SqliteRows {
        override fun query(sql: String): List<Map<String, String?>> {
            val rows = mutableListOf<Map<String, String?>>()
            database.rawQuery(sql, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val row = mutableMapOf<String, String?>()
                    for (index in 0 until cursor.columnCount) {
                        row[cursor.getColumnName(index)] =
                            if (cursor.isNull(index)) null else cursor.getString(index)
                    }
                    rows += row
                }
            }
            return rows
        }
    }
}
