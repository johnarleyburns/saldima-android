// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * Ported from the web track's `packages/importers/src/duplicates.ts`. The
 * same purchase entered twice usually has different GUIDs but the same
 * account, amount and a date within three days.
 *
 * `dayOrdinal` deliberately reuses `Recurrence.kt`'s own
 * `daysFromCivil`/`daysInMonth` (Howard Hinnant's `days_from_civil`) instead
 * of a second copy of the same day-counting math.
 */

/** An existing posted split on the target account, for duplicate matching. */
data class ExistingEntry(
    val date: String,
    val amount: Money,
)

/** Days since 1970-01-01, or `null` for an unparsable or impossible date. */
fun dayOrdinal(date: String): Long? {
    val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(date) ?: return null
    val (yearText, monthText, dayText) = match.destructured
    val year = yearText.toInt()
    val month = monthText.toInt()
    val day = dayText.toInt()
    if (month < 1 || month > 12 || day < 1 || day > daysInMonth(year, month)) return null
    return daysFromCivil(year, month, day)
}

/** Structural equality that ignores how a `Money` happens to be represented (e.g. `3/1` vs `300/100`). */
fun moneyEquals(left: Money, right: Money): Boolean = left.normalized() == right.normalized()

fun isLikelyDuplicate(
    candidateDate: String,
    candidateAmount: Money,
    existing: List<ExistingEntry>,
    windowDays: Int = 3,
): Boolean {
    val candidateDay = dayOrdinal(candidateDate) ?: return false
    return existing.any { entry ->
        moneyEquals(candidateAmount, entry.amount) &&
            dayOrdinal(entry.date)?.let { entryDay -> kotlin.math.abs(candidateDay - entryDay) <= windowDays } == true
    }
}

fun isLikelyDuplicate(candidate: ImportRow, existing: List<ExistingEntry>, windowDays: Int = 3): Boolean =
    isLikelyDuplicate(candidate.date, candidate.amount, existing, windowDays)
