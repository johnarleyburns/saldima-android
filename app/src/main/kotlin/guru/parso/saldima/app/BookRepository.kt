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
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.IdGenerator
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.SqliteBookReader
import guru.parso.saldima.core.SqliteBookWriter
import guru.parso.saldima.core.SqliteRows
import guru.parso.saldima.core.SqliteWrites
import guru.parso.saldima.core.Totals
import guru.parso.saldima.core.XmlBookReader
import guru.parso.saldima.core.XmlBookWriter
import java.io.File
import java.util.UUID

/** Owns the first company file on this device. Standalone: no network. */
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

    private val directory: File get() = File(context.filesDir, "books").apply { mkdirs() }
    private var currentFile: File? = null
    private val ids = IdGenerator {
        Guid.parse(UUID.randomUUID().toString().replace("-", ""))
    }

    /** Writes a gzip GnuCash XML export of the open book to a chosen document. */
    fun exportXml(uri: Uri): Boolean {
        val loaded = snapshot
        if (loaded == null) {
            error = "Open a book first."
            return false
        }
        return try {
            val bytes = XmlBookWriter.gzip(loaded, ids)
            context.contentResolver.openOutputStream(uri)?.use { output ->
                output.write(bytes)
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
     * Imports a `.gnucash` file (XML or SQLite). An XML book is materialized
     * as a fresh SQLite working copy (SQLite is still the live store today;
     * the XML import boundary is the same shape the eventual XML-canonical
     * migration will use), so it opens and edits exactly like a book created
     * on-device.
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
                BookFormat.SQLITE -> staged.copyTo(target, overwrite = true)
                BookFormat.XML -> {
                    val imported = XmlBookReader.read(staged)
                    target.delete()
                    var created: SQLiteDatabase? = null
                    try {
                        created = SQLiteDatabase.openOrCreateDatabase(target, null)
                        SqliteBookWriter.materialize(imported, DatabaseWrites(created), ids)
                    } finally {
                        created?.close()
                    }
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
        val file = currentFile ?: run {
            error = "Open a book first."
            return null
        }
        var database: SQLiteDatabase? = null
        return try {
            database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
            val currencyGuid = database.rawQuery(
                "SELECT commodity_guid FROM accounts WHERE guid = ? LIMIT 1",
                arrayOf(parentGuid.raw),
            ).use { cursor -> if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null }
                ?: database.rawQuery(
                    "SELECT guid FROM commodities WHERE namespace = 'CURRENCY' LIMIT 1",
                    null,
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            val guid = ids.next()
            SqliteBookWriter.insertAccount(
                DatabaseWrites(database),
                guid = guid,
                name = name,
                type = type.name,
                commodity = currencyGuid?.let { Guid.parse(it) },
                parent = parentGuid,
                hidden = false,
                placeholder = false,
                code = code,
                description = description,
            )
            error = null
            read(file)
            guid
        } catch (failure: Exception) {
            error = "Could not add account: ${failure.message}"
            null
        } finally {
            database?.close()
        }
    }

    fun deleteAccount(guid: Guid) {
        val file = currentFile ?: run {
            error = "Open a book first."
            return
        }
        var database: SQLiteDatabase? = null
        try {
            database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
            val children = database.rawQuery(
                "SELECT count(*) FROM accounts WHERE parent_guid = ?",
                arrayOf(guid.raw),
            ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }
            val splits = database.rawQuery(
                "SELECT count(*) FROM splits WHERE account_guid = ?",
                arrayOf(guid.raw),
            ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) > 0 }
            if (children || splits) {
                error = "Move or delete this account's children and transactions first."
                return
            }
            database.execSQL("DELETE FROM accounts WHERE guid = ?", arrayOf(guid.raw))
            error = null
            read(file)
        } catch (failure: Exception) {
            error = "Could not delete account: ${failure.message}"
        } finally {
            database?.close()
        }
    }

    fun post(date: String, description: String, from: Guid, to: Guid, amount: Money) {
        val file = currentFile ?: run {
            error = "Open a book first."
            return
        }
        var database: SQLiteDatabase? = null
        try {
            database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
            val tx = UUID.randomUUID().toString().replace("-", "")
            database.execSQL(
                "INSERT INTO transactions(guid, currency_guid, num, post_date, enter_date, description) VALUES (?, NULL, '', ?, ?, ?)",
                arrayOf(tx, "$date 00:00:00", "$date 00:00:00", description),
            )
            for ((account, value) in listOf(from to amount, to to Money(-amount.num, amount.denom))) {
                database.execSQL(
                    "INSERT INTO splits(guid, tx_guid, account_guid, memo, action, reconcile_state, reconcile_date, value_num, value_denom, quantity_num, quantity_denom, lot_guid) VALUES (?, ?, ?, '', '', 'n', NULL, ?, ?, ?, ?, NULL)",
                    arrayOf(
                        UUID.randomUUID().toString().replace("-", ""),
                        tx,
                        account.raw,
                        value.num,
                        value.denom,
                        value.num,
                        value.denom,
                    ),
                )
            }
            error = null
            read(file)
        } catch (failure: Exception) {
            error = "Could not post: ${failure.message}"
        } finally {
            database?.close()
        }
    }

    private fun read(file: File) {
        var database: SQLiteDatabase? = null
        try {
            database = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            val loaded = SqliteBookReader.read(QueryRows(database))
            currentFile = file
            currentName = file.nameWithoutExtension
            refreshBooks()
            snapshot = loaded
            totals = Totals.rolledUp(loaded)
            error = null
        } catch (failure: Exception) {
            error = "That file is not a GnuCash SQLite book."
        } finally {
            database?.close()
        }
    }

    private class DatabaseWrites(private val database: SQLiteDatabase) : SqliteWrites {
        override fun exec(sql: String) {
            database.execSQL(sql)
        }

        override fun insert(sql: String, params: List<Any?>) {
            database.execSQL(sql, params.toTypedArray())
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
