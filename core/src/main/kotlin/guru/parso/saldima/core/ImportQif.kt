// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/** Ported from the web track's `packages/importers/src/qif.ts`. */

private class MutableQifRecord {
    var date: String = ""
    var amount: Money? = null
    var payee: String = ""
    var memo: String = ""
    var reference: String = ""
    var category: String = ""
    val splits: MutableList<ImportSplit> = mutableListOf()
    val warnings: MutableList<String> = mutableListOf()
}

private class PendingQifSplit(val category: String) {
    var amount: Money? = null
}

fun parseQif(text: String): ImportResult {
    val rows = mutableListOf<ImportRow>()
    val warnings = mutableListOf<String>()
    var record = MutableQifRecord()
    var recordNumber = 1
    var pendingSplit: PendingQifSplit? = null

    fun finishSplit() {
        val split = pendingSplit ?: return
        val amount = split.amount
        if (amount == null) {
            record.warnings.add("record: split \"${split.category}\" has no amount")
        } else {
            record.splits.add(ImportSplit(split.category, amount))
        }
    }

    fun finish() {
        for (warning in record.warnings) warnings.add("record $recordNumber: $warning")
        val date = parseImportDate(record.date)
        val amount = record.amount
        when {
            date == null -> warnings.add("record $recordNumber: skipped (no valid date)")
            amount == null -> warnings.add("record $recordNumber: skipped (no valid amount)")
            amount.num == 0L -> warnings.add("record $recordNumber: skipped (zero amount)")
            else -> rows.add(
                ImportRow(
                    date = date,
                    amount = amount,
                    payee = record.payee,
                    memo = record.memo,
                    reference = record.reference,
                    category = record.category,
                    splits = record.splits.toList(),
                ),
            )
        }
        recordNumber += 1
        record = MutableQifRecord()
        pendingSplit = null
    }

    val normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    for (raw in normalized.split("\n")) {
        val code = raw.getOrNull(0)
        val value = raw.drop(1).trim()
        if (code == '!' || raw.trim().isEmpty()) continue
        when (code) {
            '^' -> {
                finishSplit()
                finish()
            }
            'S' -> {
                finishSplit()
                pendingSplit = PendingQifSplit(value)
            }
            '$' -> {
                val split = pendingSplit
                if (split == null) {
                    record.warnings.add("record: split amount without a category")
                } else {
                    split.amount = parseImportAmount(value)
                }
            }
            'D' -> record.date = value
            'T' -> record.amount = parseImportAmount(value)
            'P' -> record.payee = value
            'M' -> record.memo = value
            'N' -> record.reference = value
            'L' -> record.category = value
            else -> Unit
        }
    }

    // A final record without the `^` terminator is still a record.
    if (record.date.isNotEmpty() || record.amount != null || record.payee.isNotEmpty()) {
        finishSplit()
        finish()
    }

    if (rows.isEmpty()) throw ImportFormatException("No QIF transactions found.")
    return ImportResult(ImportSource.QIF, rows, warnings)
}
