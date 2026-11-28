// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * Ported from the web track's `packages/importers/src/detect.ts`. PDF is
 * not covered here: a PDF is never read as a text statement by extension
 * sniffing, it goes through `parsePdfStatementText` once its text has been
 * extracted (`ImportPdf.kt`).
 */
fun detectImportSource(fileName: String, text: String): ImportSource {
    val head = text.take(4096)
    if (isOfx(head)) return ImportSource.OFX
    if (Regex("(?mi)^\\s*!Type:").containsMatchIn(text)) return ImportSource.QIF
    if (Regex("(?m)^\\^(\\r?\\n|$)").containsMatchIn(text) &&
        Regex("(?m)^(D\\s*\\d|T\\s*[-+(]?\\d)").containsMatchIn(text)
    ) {
        return ImportSource.QIF
    }
    val extension = fileName.lowercase().substringAfterLast('.', "")
    if (extension == "ofx" || extension == "qfx") return ImportSource.OFX
    return ImportSource.CSV
}

fun parseImportFile(fileName: String, text: String): ImportResult =
    when (detectImportSource(fileName, text)) {
        ImportSource.QIF -> parseQif(text)
        ImportSource.OFX -> parseOfx(text)
        else -> parseCsv(text)
    }
