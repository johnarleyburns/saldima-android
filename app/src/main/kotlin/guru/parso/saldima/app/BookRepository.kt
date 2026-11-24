// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import guru.parso.saldima.core.BookSnapshot
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.SqliteBookReader
import guru.parso.saldima.core.SqliteRows
import guru.parso.saldima.core.Totals
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

    fun import(uri: Uri) {
        try {
            val name = (uri.lastPathSegment ?: "book").substringAfterLast('/').ifBlank { "book" }
            val target = File(directory, if (name.endsWith(".gnucash")) name else "$name.gnucash")
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: run {
                error = "Could not read that file."
                return
            }
            refreshBooks()
            read(target)
        } catch (failure: Exception) {
            error = failure.message
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
