// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/** Ported from the web track's `packages/importers/src/ofx.ts`. */

private fun cleanOfxField(value: String): String =
    value.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

private fun ofxField(block: String, name: String): String {
    val closed = Regex("(?i)<$name\\b[^>]*>([\\s\\S]*?)</$name>").find(block)
    if (closed != null) return cleanOfxField(closed.groupValues[1])
    val open = Regex("(?i)<$name\\b[^>]*>([^<\\r\\n]*)").find(block)
    val value = open?.groupValues?.get(1).orEmpty()
    return if (value.isNotEmpty()) cleanOfxField(value) else ""
}

private fun ofxStatementBlocks(text: String): List<String> {
    val marker = Regex("(?i)<STMTTRN\\b[^>]*>")
    val starts = marker.findAll(text).map { it.range.last + 1 }.toList()
    return starts.mapIndexed { index, start ->
        val end = starts.getOrNull(index + 1) ?: text.length
        text.substring(start, end).replace(Regex("(?i)</STMTTRN>"), "")
    }
}

/** Reads both OFX 1.x SGML (unclosed tags) and OFX 2.x XML statements. */
fun isOfx(text: String): Boolean {
    val head = text.take(4096)
    return Regex("(?i)OFXHEADER").containsMatchIn(head) || Regex("(?i)<OFX[\\s>]").containsMatchIn(head)
}

fun parseOfx(text: String): ImportResult {
    val blocks = ofxStatementBlocks(text)
    if (blocks.isEmpty()) throw ImportFormatException("No OFX transactions found.")
    val rows = mutableListOf<ImportRow>()
    val warnings = mutableListOf<String>()

    blocks.forEachIndexed { index, block ->
        val date = parseImportDate(ofxField(block, "DTPOSTED"))
        val amount = parseImportAmount(ofxField(block, "TRNAMT"))
        when {
            date == null -> warnings.add("transaction ${index + 1}: skipped (invalid date)")
            amount == null -> warnings.add("transaction ${index + 1}: skipped (invalid amount)")
            amount.num == 0L -> warnings.add("transaction ${index + 1}: skipped (zero amount)")
            else -> rows.add(
                ImportRow(
                    date = date,
                    amount = amount,
                    payee = ofxField(block, "NAME").ifEmpty { ofxField(block, "PAYEE") },
                    memo = ofxField(block, "MEMO"),
                    reference = ofxField(block, "FITID").ifEmpty { ofxField(block, "CHECKNUM") },
                    category = ofxField(block, "CATEGORY"),
                    splits = emptyList(),
                ),
            )
        }
    }

    if (rows.isEmpty()) throw ImportFormatException("No usable OFX transactions found.")
    return ImportResult(ImportSource.OFX, rows, warnings)
}
