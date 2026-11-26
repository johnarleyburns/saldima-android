// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import guru.parso.saldima.core.AccountType
import guru.parso.saldima.core.BookFiles
import guru.parso.saldima.core.BookFormat
import guru.parso.saldima.core.BookSnapshot
import guru.parso.saldima.core.CustomerDraft
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.IdGenerator
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.SqliteBookReader
import guru.parso.saldima.core.SqliteRows
import guru.parso.saldima.core.Totals
import guru.parso.saldima.core.XmlBookDocument
import guru.parso.saldima.core.XmlCustomer
import guru.parso.saldima.core.XmlInvoice
import guru.parso.saldima.core.XmlInvoiceEntry
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
