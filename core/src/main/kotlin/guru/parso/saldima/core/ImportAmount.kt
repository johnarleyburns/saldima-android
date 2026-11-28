// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * Ported from the web track's `packages/importers/src/amount.ts`. Reads a
 * bank amount exactly to cents: currency symbols, thousands separators,
 * European decimal commas and accounting parentheses.
 */

private fun decimalSeparator(value: String): Char? {
    val comma = value.lastIndexOf(',')
    val dot = value.lastIndexOf('.')
    if (comma >= 0 && dot >= 0) return if (comma > dot) ',' else '.'
    if (comma >= 0) {
        val digits = value.length - comma - 1
        return if (value.split(',').size == 2 && digits in 1..2) ',' else null
    }
    return if (value.split('.').size > 2) null else '.'
}

fun parseImportAmount(text: String): Money? {
    var value = text.trim()
    if (value.isEmpty()) return null

    var negative = false
    if (Regex("^\\(.*\\)$").matches(value)) {
        negative = true
        value = value.substring(1, value.length - 1)
    }
    value = value.replace(Regex("[\\s']"), "").replace(Regex("[\\p{Sc}A-Za-z]"), "")

    if (value.startsWith("+")) value = value.substring(1)
    if (value.startsWith("-")) {
        negative = !negative
        value = value.substring(1)
    }
    if (value.endsWith("-")) {
        negative = !negative
        value = value.substring(0, value.length - 1)
    }

    val separator = decimalSeparator(value)
    val normalised = when (separator) {
        ',' -> value.replace(".", "").replace(",", ".")
        '.' -> value.replace(",", "")
        else -> value.replace(",", "").replace(".", "")
    }

    if (!Regex("^\\d+(?:\\.\\d+)?$").matches(normalised)) return null
    val amount = try {
        Money.parseExact(normalised).roundedHalfUp(100)
    } catch (_: Exception) {
        return null
    }
    return if (negative) amount.negated() else amount
}
