// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * Ported from the web track's `packages/importers/src/date.ts`. Bank
 * exports are not ISO: supports ISO, US and European slash/dash dates (the
 * first component above 12 disambiguates day-first), OFX timestamps, and
 * two-digit years. `isoOrNull`/`fullYear` are reused by the PDF
 * line-parsing heuristic (`ImportPdf.kt`) so both share one validator.
 */

private val ISO_DATE_RE = Regex("^(\\d{4})-(\\d{1,2})-(\\d{1,2})$")
private val NUMERIC_DATE_RE = Regex("^(\\d{1,4})[/. '-](\\d{1,2})[/. '-](\\d{1,4})$")
private val OFX_STAMP_RE = Regex("^(\\d{4})(\\d{2})(\\d{2})")

internal fun isoOrNull(year: Int, month: Int, day: Int): String? {
    if (month < 1 || month > 12) return null
    if (day < 1 || day > daysInMonth(year, month)) return null
    return "%04d-%02d-%02d".format(year, month, day)
}

internal fun fullYear(value: Int, digits: Int): Int =
    if (digits == 4) value else if (value >= 70) 1900 + value else 2000 + value

fun parseImportDate(text: String): String? {
    val value = text.trim()
    if (value.isEmpty()) return null

    OFX_STAMP_RE.find(value)?.let { match ->
        val (y, m, d) = match.destructured
        return isoOrNull(y.toInt(), m.toInt(), d.toInt())
    }

    ISO_DATE_RE.matchEntire(value)?.let { match ->
        val (y, m, d) = match.destructured
        return isoOrNull(y.toInt(), m.toInt(), d.toInt())
    }

    val parts = NUMERIC_DATE_RE.matchEntire(value) ?: return null
    val (firstStr, middleStr, thirdStr) = parts.destructured
    val first = firstStr.toInt()
    val middle = middleStr.toInt()
    val third = thirdStr.toInt()

    if (firstStr.length == 4) {
        return isoOrNull(first, middle, third)
    }
    val year = fullYear(third, thirdStr.length)
    return if (first > 12) isoOrNull(year, middle, first) else isoOrNull(year, first, middle)
}
