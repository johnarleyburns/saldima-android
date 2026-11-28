// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/** Ported from the web track's `packages/importers/src/csv.ts`. */

data class CsvColumnMap(
    val dateIndex: Int,
    val descriptionIndex: Int,
    val valueIndex: Int?,
    val outflowIndex: Int?,
    val inflowIndex: Int?,
    val categoryIndex: Int?,
    val referenceIndex: Int?,
)

data class CsvTable(
    val header: List<String>,
    val rows: List<List<String>>,
    val delimiter: Char,
)

private val DELIMITERS = listOf(',', ';', '\t', '|')

private val DATE_NAMES = listOf("date", "posted", "postdate", "transactiondate", "bookingdate", "valuedate", "datum")
private val DESCRIPTION_NAMES = listOf("description", "payee", "name", "details", "narrative", "merchant", "memo")
private val VALUE_NAMES = listOf("amount", "value", "transactionamount", "amountusd", "amt")
private val OUTFLOW_NAMES =
    listOf("debit", "debitamount", "withdrawal", "withdrawals", "moneyout", "paidout", "outflow")
private val INFLOW_NAMES = listOf("credit", "creditamount", "deposit", "deposits", "moneyin", "paidin", "inflow")
private val CATEGORY_NAMES = listOf("category", "type", "classification", "class")
private val REFERENCE_NAMES =
    listOf("reference", "ref", "checknumber", "checkno", "check", "fitid", "transactionid", "id")

private fun normaliseHeader(value: String): String =
    value.removePrefix("﻿").lowercase().replace(Regex("[^a-z0-9]"), "")

private fun countOutsideQuotes(line: String, delimiter: Char): Int {
    var count = 0
    var quoted = false
    var index = 0
    while (index < line.length) {
        val character = line[index]
        if (character == '"') {
            if (quoted && index + 1 < line.length && line[index + 1] == '"') {
                index += 1
            } else {
                quoted = !quoted
            }
        } else if (!quoted && character == delimiter) {
            count += 1
        }
        index += 1
    }
    return count
}

private fun detectDelimiter(text: String): Char {
    val line = text.split(Regex("\r?\n")).firstOrNull { it.trim().isNotEmpty() } ?: ""
    var best = ','
    var bestCount = 0
    for (delimiter in DELIMITERS) {
        val count = countOutsideQuotes(line, delimiter)
        if (count > bestCount) {
            best = delimiter
            bestCount = count
        }
    }
    return best
}

private fun scanCsv(text: String, delimiter: Char): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var index = 0
    while (index < text.length) {
        val character = text[index]
        when {
            quoted -> {
                if (character == '"') {
                    if (index + 1 < text.length && text[index + 1] == '"') {
                        cell.append('"')
                        index += 1
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(character)
                }
            }
            character == '"' -> quoted = true
            character == delimiter -> {
                row.add(cell.toString())
                cell.clear()
            }
            character == '\n' -> {
                row.add(cell.toString())
                rows.add(row)
                row = mutableListOf()
                cell.clear()
            }
            character != '\r' -> cell.append(character)
        }
        index += 1
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) {
        row.add(cell.toString())
        rows.add(row)
    }
    return rows.filter { candidate -> candidate.any { it.trim().isNotEmpty() } }
}

fun parseCsvTable(text: String): CsvTable {
    val delimiter = detectDelimiter(text)
    val rows = scanCsv(text, delimiter)
    val header = (rows.firstOrNull() ?: emptyList()).map { it.trim() }
    return CsvTable(header, rows.drop(1), delimiter)
}

private fun findColumn(header: List<String>, names: List<String>, claimed: Set<Int>): Int? {
    val normalised = header.map(::normaliseHeader)
    val exact = normalised.withIndex().firstOrNull { (index, value) -> index !in claimed && value in names }
    if (exact != null) return exact.index
    val partial = normalised.withIndex()
        .firstOrNull { (index, value) -> index !in claimed && names.any { value.contains(it) } }
    return partial?.index
}

/** Detects the common bank CSV shapes; throws when the table is unusable. */
fun detectCsvMap(header: List<String>): CsvColumnMap {
    val claimed = mutableSetOf<Int>()
    fun take(names: List<String>): Int? {
        val found = findColumn(header, names, claimed)
        if (found != null) claimed.add(found)
        return found
    }

    val dateIndex = take(DATE_NAMES)
    var descriptionIndex = take(DESCRIPTION_NAMES)
    if (descriptionIndex == null) {
        val fallback = header.indices.firstOrNull { it !in claimed }
        if (fallback != null) {
            descriptionIndex = fallback
            claimed.add(fallback)
        } else {
            descriptionIndex = 0
        }
    }
    val valueIndex = take(VALUE_NAMES)
    val outflowIndex = take(OUTFLOW_NAMES)
    val inflowIndex = take(INFLOW_NAMES)
    val categoryIndex = take(CATEGORY_NAMES)
    val referenceIndex = take(REFERENCE_NAMES)

    if (dateIndex == null) {
        throw ImportFormatException(
            "No date column found. Columns: ${header.joinToString(", ").ifEmpty { "(none)" }}.",
        )
    }
    if (valueIndex == null && outflowIndex == null && inflowIndex == null) {
        throw ImportFormatException(
            "No amount, debit or credit column found. Columns: ${header.joinToString(", ").ifEmpty { "(none)" }}.",
        )
    }
    return CsvColumnMap(dateIndex, descriptionIndex, valueIndex, outflowIndex, inflowIndex, categoryIndex, referenceIndex)
}

private fun cellAt(row: List<String>, index: Int?): String =
    if (index == null) "" else (row.getOrNull(index) ?: "").trim()

fun parseCsv(text: String, map: CsvColumnMap? = null): ImportResult {
    val table = parseCsvTable(text)
    if (table.header.isEmpty()) throw ImportFormatException("The CSV file is empty.")
    val columns = map ?: detectCsvMap(table.header)
    val rows = mutableListOf<ImportRow>()
    val warnings = mutableListOf<String>()

    table.rows.forEachIndexed { rowIndex, row ->
        val line = rowIndex + 2
        val date = parseImportDate(cellAt(row, columns.dateIndex))
        if (date == null) {
            if (row.any { it.trim().isNotEmpty() }) warnings.add("row $line: skipped (invalid date)")
            return@forEachIndexed
        }

        var amount: Money?
        if (columns.valueIndex != null) {
            amount = parseImportAmount(cellAt(row, columns.valueIndex))
        } else {
            val outflow = cellAt(row, columns.outflowIndex)
            val inflow = cellAt(row, columns.inflowIndex)
            val out = if (outflow.isNotEmpty()) parseImportAmount(outflow) else null
            val into = if (inflow.isNotEmpty()) parseImportAmount(inflow) else null
            if (outflow.isNotEmpty() && out == null) {
                warnings.add("row $line: skipped (invalid debit)")
                return@forEachIndexed
            }
            if (inflow.isNotEmpty() && into == null) {
                warnings.add("row $line: skipped (invalid credit)")
                return@forEachIndexed
            }
            amount = if (outflow.isEmpty() && inflow.isEmpty()) {
                null
            } else {
                Money.add(into ?: Money.ZERO, (out ?: Money.ZERO).negated())
            }
        }

        if (amount == null) {
            warnings.add("row $line: skipped (invalid amount)")
            return@forEachIndexed
        }
        if (amount.num == 0L) {
            warnings.add("row $line: skipped (zero amount)")
            return@forEachIndexed
        }

        rows.add(
            ImportRow(
                date = date,
                amount = amount,
                payee = cellAt(row, columns.descriptionIndex),
                memo = "",
                reference = cellAt(row, columns.referenceIndex),
                category = cellAt(row, columns.categoryIndex),
                splits = emptyList(),
            ),
        )
    }

    if (rows.isEmpty()) throw ImportFormatException("No usable CSV transactions found.")
    return ImportResult(ImportSource.CSV, rows, warnings)
}
