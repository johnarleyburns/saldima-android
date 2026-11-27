// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * One account's register as a flat row, ready to write out as QIF or CSV
 * -- the mirror of `packages/importers`' `ImportRow` on the web track, so
 * a file exported here reads back in there (and vice versa).
 */
data class RegisterExportRow(
    val date: String,
    val amount: Money,
    val payee: String,
    val memo: String,
    val category: String,
)

/**
 * Builds one account's register rows from the book's transactions, the
 * same convention desktop GnuCash's own QIF export uses for a two-split
 * transaction: the other split becomes the "category", or "Split
 * Transaction" when there's more than one.
 */
fun registerExportRows(
    transactions: List<SnapshotTransaction>,
    accountNames: Map<Guid, String>,
    accountGuid: Guid,
): List<RegisterExportRow> {
    val rows = mutableListOf<RegisterExportRow>()
    for (transaction in transactions) {
        val mine = transaction.splits.filter { it.accountGuid == accountGuid }
        if (mine.isEmpty()) continue
        val others = transaction.splits.filter { it.accountGuid != accountGuid }
        val only = others.singleOrNull()
        val category = if (only != null) accountNames[only.accountGuid] ?: "" else "Split Transaction"
        for (split in mine) {
            rows.add(
                RegisterExportRow(
                    date = transaction.date.take(10),
                    amount = split.value,
                    payee = transaction.description,
                    memo = split.memo,
                    category = category,
                ),
            )
        }
    }
    return rows.sortedBy { it.date }
}

/**
 * Writes a register's rows as a `!Type:Bank` QIF file, the same format
 * `packages/importers`' `serializeQif` writes on the web track.
 */
fun serializeQif(rows: List<RegisterExportRow>): String {
    val lines = mutableListOf("!Type:Bank")
    for (row in rows) {
        lines.add("D${row.date}")
        lines.add("T${row.amount.formatted(100)}")
        if (row.payee.isNotEmpty()) lines.add("P${row.payee}")
        if (row.memo.isNotEmpty()) lines.add("M${row.memo}")
        if (row.category.isNotEmpty()) lines.add("L${row.category}")
        lines.add("^")
    }
    return lines.joinToString("\n") + "\n"
}

private fun escapeCsvCell(value: String): String =
    if (value.contains(',') || value.contains('"') || value.contains('\n')) {
        "\"${value.replace("\"", "\"\"")}\""
    } else {
        value
    }

/**
 * Writes a register's rows with the same CSV headers `packages/importers`'
 * `serializeCsv` writes on the web track.
 */
fun serializeCsv(rows: List<RegisterExportRow>): String {
    val lines = mutableListOf("Date,Description,Memo,Amount,Category")
    for (row in rows) {
        val cells = listOf(row.date, row.payee, row.memo, row.amount.formatted(100), row.category)
        lines.add(cells.joinToString(",") { escapeCsvCell(it) })
    }
    return lines.joinToString("\n") + "\n"
}

/** A safe, extension-free filename stem from an account name. */
fun registerExportFilename(accountName: String, extension: String): String {
    val safe = accountName.trim().map { character ->
        if (character.isLetterOrDigit() || character == '.' || character == '_' || character == '-') character else '-'
    }.joinToString("")
    val collapsed = safe.replace(Regex("-+"), "-").trim('-')
    val stem = collapsed.ifEmpty { "account" }
    return "$stem.$extension"
}
