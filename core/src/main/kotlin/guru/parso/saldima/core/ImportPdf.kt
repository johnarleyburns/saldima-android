// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * Experimental PDF statement line-parsing. There is nothing to port here --
 * this is a from-scratch heuristic over already-extracted page text (PDF
 * text extraction itself is `:app`'s job, via `pdfbox-android`'s
 * `PDFTextStripper`; this file never touches Android or any PDF library,
 * so it stays testable on the plain JVM).
 *
 * Known, documented limitations (not solved here):
 * - Year inference can mis-attribute dates across a year boundary when the
 *   statement never prints a year anywhere in its first ~2000 characters.
 * - Multi-line-wrapped descriptions are not stitched back together.
 * - A scanned/image-only PDF yields no extractable text at all; real OCR
 *   (e.g. Google ML Kit) is a proprietary SDK, not an allowed dependency
 *   license, so that case surfaces a clear message instead of a crash.
 */

private val MONTH_ABBREVIATIONS = mapOf(
    "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
    "jul" to 7, "aug" to 8, "sep" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
)
private const val MONTH_ALTERNATION = "Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec"

// Priority order matters: the first pattern (in this order) that matches
// anywhere in the line, and validates as a real calendar date, wins.
private val DATE_PATTERNS_WITH_YEAR: List<Pair<Regex, (MatchResult) -> Triple<Int, Int, Int>?>> = listOf(
    // MM/DD/YYYY
    Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b") to { m ->
        Triple(m.groupValues[3].toInt(), m.groupValues[1].toInt(), m.groupValues[2].toInt())
    },
    // MM-DD-YYYY
    Regex("\\b(\\d{1,2})-(\\d{1,2})-(\\d{4})\\b") to { m ->
        Triple(m.groupValues[3].toInt(), m.groupValues[1].toInt(), m.groupValues[2].toInt())
    },
    // MM/DD/YY
    Regex("\\b(\\d{1,2})/(\\d{1,2})/(\\d{2})\\b") to { m ->
        Triple(fullYear(m.groupValues[3].toInt(), 2), m.groupValues[1].toInt(), m.groupValues[2].toInt())
    },
    // Mon DD, YYYY
    Regex("(?i)\\b($MONTH_ALTERNATION)\\s+(\\d{1,2}),\\s*(\\d{4})\\b") to { m ->
        val month = MONTH_ABBREVIATIONS[m.groupValues[1].lowercase()] ?: return@to null
        Triple(m.groupValues[3].toInt(), month, m.groupValues[2].toInt())
    },
    // Mon DD YYYY
    Regex("(?i)\\b($MONTH_ALTERNATION)\\s+(\\d{1,2})\\s+(\\d{4})\\b") to { m ->
        val month = MONTH_ABBREVIATIONS[m.groupValues[1].lowercase()] ?: return@to null
        Triple(m.groupValues[3].toInt(), month, m.groupValues[2].toInt())
    },
)

private val DATE_PATTERNS_NO_YEAR: List<Pair<Regex, (MatchResult) -> Pair<Int, Int>?>> = listOf(
    // MM/DD
    Regex("\\b(\\d{1,2})/(\\d{1,2})\\b") to { m -> Pair(m.groupValues[1].toInt(), m.groupValues[2].toInt()) },
    // Mon DD
    Regex("(?i)\\b($MONTH_ALTERNATION)\\s+(\\d{1,2})\\b") to { m ->
        val month = MONTH_ABBREVIATIONS[m.groupValues[1].lowercase()] ?: return@to null
        Pair(month, m.groupValues[2].toInt())
    },
)

private data class PdfToken(val range: IntRange, val iso: String)

private fun findPdfDateToken(line: String, inferredYear: Int): PdfToken? {
    for ((pattern, extract) in DATE_PATTERNS_WITH_YEAR) {
        val match = pattern.find(line) ?: continue
        val (year, month, day) = extract(match) ?: continue
        val iso = isoOrNull(year, month, day) ?: continue
        return PdfToken(match.range, iso)
    }
    for ((pattern, extract) in DATE_PATTERNS_NO_YEAR) {
        val match = pattern.find(line) ?: continue
        val (month, day) = extract(match) ?: continue
        val iso = isoOrNull(inferredYear, month, day) ?: continue
        return PdfToken(match.range, iso)
    }
    return null
}

// Optional `$`, digits with optional comma thousands separators, a literal
// decimal point, exactly two digits; negative when wrapped in parentheses
// or immediately preceded by a bare `-` with no space (enforced simply by
// never allowing whitespace between the `-`/`(` and the amount itself).
private val PDF_AMOUNT_RE = Regex(
    "\\(\\s*-?\\$?\\d{1,3}(?:,\\d{3})*\\.\\d{2}\\s*\\)|-?\\$?\\d{1,3}(?:,\\d{3})*\\.\\d{2}",
)

private fun parsePdfAmount(token: String): Money {
    val negative = token.contains('(') || token.contains('-')
    val digitsOnly = token.filter { it.isDigit() || it == '.' }
    val amount = Money.parseExact(digitsOnly)
    return if (negative) amount.negated() else amount
}

private fun stripRanges(line: String, ranges: List<IntRange>): String {
    val builder = StringBuilder()
    for (index in line.indices) {
        if (ranges.none { index in it }) builder.append(line[index])
    }
    return builder.toString()
}

/**
 * Scans the first ~2000 characters for a 4-digit number in `[2000, 2099]`;
 * that's the default year for any date token found later with no year of
 * its own. Falls back to `fallbackYear` (the caller's injected "today",
 * never a raw wall-clock call) if nothing is found anywhere -- a statement
 * spanning a year boundary with no explicit year printed can still
 * mis-attribute dates; that is a known, documented limitation.
 */
fun inferStatementYear(text: String, fallbackYear: Int): Int {
    val head = text.take(2000)
    val match = Regex("\\b(20\\d{2})\\b").find(head) ?: return fallbackYear
    return match.groupValues[1].toIntOrNull() ?: fallbackYear
}

/**
 * One date token and one amount token (the first on the line, so an
 * "Amount, then running Balance" column layout is handled correctly) make
 * a row; a line missing either is skipped silently, never fabricated.
 * Multi-line-wrapped descriptions are not stitched together.
 */
fun parsePdfStatementLines(lines: List<String>, inferredYear: Int): List<ImportRow> {
    val rows = mutableListOf<ImportRow>()
    for (line in lines) {
        val dateToken = findPdfDateToken(line, inferredYear) ?: continue
        val amountMatch = PDF_AMOUNT_RE.find(line) ?: continue
        val payee = stripRanges(line, listOf(dateToken.range, amountMatch.range))
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(200)
        rows.add(
            ImportRow(
                date = dateToken.iso,
                amount = parsePdfAmount(amountMatch.value),
                payee = payee,
            ),
        )
    }
    return rows
}

/**
 * Entry point for the PDF import path: `text` is the full, already
 * extracted statement text (every page's `PDFTextStripper` output,
 * concatenated in page order). A blank extraction means a scanned/image
 * PDF -- OCR is out of scope, so this throws the same way a corrupt
 * QIF/OFX/CSV file would, with a message the UI can show as-is.
 */
fun parsePdfStatementText(text: String, fallbackYear: Int): ImportResult {
    if (text.isBlank()) {
        throw ImportFormatException(
            "This looks like a scanned image, not a text PDF — OCR isn't supported yet.",
        )
    }
    val year = inferStatementYear(text, fallbackYear)
    val lines = text.replace("\r\n", "\n").replace("\r", "\n").split("\n")
    val rows = parsePdfStatementLines(lines, year)
    val warning =
        "Experimental PDF import: ${rows.size} transaction-like line(s) found. Review every row carefully before importing."
    return ImportResult(ImportSource.PDF, rows, listOf(warning))
}
