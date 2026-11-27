// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * Pure recurrence math for scheduled transactions, matching desktop
 * GnuCash's own `Recurrence` engine (`libgnucash/engine/Recurrence.c`) and
 * this project's web track (`packages/gnucash/src/recurrence.ts`): a period
 * type, a multiplier ("every N periods"), a start date that also fixes the
 * recurrence's phase (which day of the week/month it falls on), and an
 * optional weekend adjustment. All dates are plain `YYYY-MM-DD` strings --
 * never `java.time`, matching this module's determinism convention (no core
 * file depends on a platform clock; see `JournalSearch.kt`).
 */
enum class PeriodType { ONCE, DAY, WEEK, MONTH, END_OF_MONTH, NTH_WEEKDAY, LAST_WEEKDAY, YEAR }

enum class WeekendAdjust { NONE, BACK, FORWARD }

/** "Every N periods". `multiplier` must be at least 1. `start` also fixes the recurrence's phase. */
data class Recurrence(
    val periodType: PeriodType,
    val multiplier: Int = 1,
    val start: String,
    val weekendAdjust: WeekendAdjust = WeekendAdjust.NONE,
)

private data class DateParts(val y: Int, val m: Int, val d: Int)

private val ISO_DATE = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$")

private fun parseIso(date: String): DateParts {
    val match = ISO_DATE.matchEntire(date) ?: throw IllegalArgumentException("invalid date: $date")
    val (y, m, d) = match.destructured
    return DateParts(y.toInt(), m.toInt(), d.toInt())
}

private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()

private fun formatIso(parts: DateParts): String =
    "${parts.y.toString().padStart(4, '0')}-${pad2(parts.m)}-${pad2(parts.d)}"

private fun isLeapYear(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

private val DAYS_IN_MONTH = intArrayOf(31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

private fun daysInMonth(year: Int, month: Int): Int {
    if (month == 2 && isLeapYear(year)) return 29
    return DAYS_IN_MONTH.getOrElse(month - 1) { 30 }
}

/** Howard Hinnant's `days_from_civil`: days since 1970-01-01, proleptic Gregorian. */
private fun daysFromCivil(y: Int, m: Int, d: Int): Long {
    val year = (if (m <= 2) y - 1 else y).toLong()
    val era = Math.floorDiv(if (year >= 0) year else year - 399, 400L)
    val yoe = year - era * 400
    val monthIndex = (m + (if (m > 2) -3 else 9)).toLong()
    val doy = Math.floorDiv(153L * monthIndex + 2, 5L) + d - 1
    val doe = yoe * 365 + Math.floorDiv(yoe, 4L) - Math.floorDiv(yoe, 100L) + doy
    return era * 146097L + doe - 719468L
}

/** The inverse of `daysFromCivil`. */
private fun civilFromDays(daysSinceEpoch: Long): DateParts {
    val z = daysSinceEpoch + 719468L
    val era = Math.floorDiv(if (z >= 0) z else z - 146096L, 146097L)
    val doe = z - era * 146097L
    val yoe = Math.floorDiv(
        doe - Math.floorDiv(doe, 1460L) + Math.floorDiv(doe, 36524L) - Math.floorDiv(doe, 146096L),
        365L,
    )
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + Math.floorDiv(yoe, 4L) - Math.floorDiv(yoe, 100L))
    val mp = Math.floorDiv(5L * doy + 2, 153L)
    val d = doy - Math.floorDiv(153L * mp + 2, 5L) + 1
    val m = mp + (if (mp < 10) 3 else -9)
    return DateParts((if (m <= 2) y + 1 else y).toInt(), m.toInt(), d.toInt())
}

/** `0` Sunday .. `6` Saturday. */
private fun dayOfWeek(parts: DateParts): Int {
    val z = daysFromCivil(parts.y, parts.m, parts.d)
    return (((z + 4) % 7 + 7) % 7).toInt()
}

private fun isWeekend(weekday: Int): Boolean = weekday == 0 || weekday == 6

private fun addCalendarMonths(parts: DateParts, deltaMonths: Int): Pair<Int, Int> {
    val totalIndex = parts.y * 12 + (parts.m - 1) + deltaMonths
    val y = Math.floorDiv(totalIndex, 12)
    val m = totalIndex - y * 12 + 1
    return y to m
}

/** The `n`th occurrence of `weekday` in `year`-`month`, clamped to the last if `n` doesn't exist. */
private fun nthWeekdayOfMonth(year: Int, month: Int, weekday: Int, n: Int): Int {
    val firstWeekday = dayOfWeek(DateParts(year, month, 1))
    val firstOccurrence = 1 + (((weekday - firstWeekday) % 7 + 7) % 7)
    val candidate = firstOccurrence + (n - 1) * 7
    if (candidate <= daysInMonth(year, month)) return candidate
    return lastWeekdayOfMonth(year, month, weekday)
}

private fun lastWeekdayOfMonth(year: Int, month: Int, weekday: Int): Int {
    val lastDay = daysInMonth(year, month)
    val lastDayWeekday = dayOfWeek(DateParts(year, month, lastDay))
    return lastDay - (((lastDayWeekday - weekday) % 7 + 7) % 7)
}

private fun resolveDay(periodType: PeriodType, start: DateParts, year: Int, month: Int): Int = when (periodType) {
    PeriodType.END_OF_MONTH -> daysInMonth(year, month)
    PeriodType.NTH_WEEKDAY -> {
        val weekday = dayOfWeek(start)
        val occurrence = (start.d - 1) / 7 + 1
        nthWeekdayOfMonth(year, month, weekday, occurrence)
    }
    PeriodType.LAST_WEEKDAY -> lastWeekdayOfMonth(year, month, dayOfWeek(start))
    else -> minOf(start.d, daysInMonth(year, month))
}

/** Adds (or, with a negative count, subtracts) whole days to a `YYYY-MM-DD` date. */
fun addDays(date: String, days: Int): String {
    val parts = parseIso(date)
    return formatIso(civilFromDays(daysFromCivil(parts.y, parts.m, parts.d) + days))
}

private fun applyWeekendAdjust(parts: DateParts, adjust: WeekendAdjust): DateParts {
    if (adjust == WeekendAdjust.NONE) return parts
    val weekday = dayOfWeek(parts)
    if (!isWeekend(weekday)) return parts
    val shift = if (adjust == WeekendAdjust.BACK) {
        if (weekday == 0) -2 else -1
    } else {
        if (weekday == 6) 2 else 1
    }
    return civilFromDays(daysFromCivil(parts.y, parts.m, parts.d) + shift)
}

private val MONTH_BASED = setOf(PeriodType.MONTH, PeriodType.END_OF_MONTH, PeriodType.NTH_WEEKDAY, PeriodType.LAST_WEEKDAY, PeriodType.YEAR)

/**
 * The earliest date strictly after `refDate`, on or after the recurrence's
 * `start`, that is an integral number of periods from `start` -- matching
 * desktop GnuCash's own `recurrenceNextInstance`. `null` only for
 * `PeriodType.ONCE`, once it has already fired.
 */
fun nextInstance(recurrence: Recurrence, refDate: String): String? {
    if (recurrence.multiplier < 1) {
        throw IllegalArgumentException("recurrence multiplier must be at least 1")
    }
    val start = parseIso(recurrence.start)
    val ref = parseIso(refDate)

    if (recurrence.periodType == PeriodType.ONCE) {
        return if (formatIso(ref) < formatIso(start)) formatIso(start) else null
    }

    if (recurrence.periodType !in MONTH_BASED) {
        val periodDays = (if (recurrence.periodType == PeriodType.WEEK) recurrence.multiplier * 7 else recurrence.multiplier).toLong()
        val startDays = daysFromCivil(start.y, start.m, start.d)
        val refDays = daysFromCivil(ref.y, ref.m, ref.d)
        val diff = refDays - startDays
        val k = if (diff < 0) 0L else diff / periodDays + 1
        val candidate = civilFromDays(startDays + k * periodDays)
        return formatIso(applyWeekendAdjust(candidate, recurrence.weekendAdjust))
    }

    val monthMultiplier = if (recurrence.periodType == PeriodType.YEAR) recurrence.multiplier * 12 else recurrence.multiplier
    val monthsDiff = ref.y * 12 + (ref.m - 1) - (start.y * 12 + (start.m - 1))
    val estimate = Math.floorDiv(monthsDiff, monthMultiplier)
    var k = maxOf(0, estimate - 2)
    val refIso = formatIso(ref)
    // A handful of steps past the estimate always finds the crossing point;
    // the cap only guards against a pathological input, never real use.
    repeat(10_000) {
        val (y, m) = addCalendarMonths(start, k * monthMultiplier)
        val day = resolveDay(recurrence.periodType, start, y, m)
        val candidate = DateParts(y, m, day)
        if (formatIso(candidate) > refIso) {
            return formatIso(applyWeekendAdjust(candidate, recurrence.weekendAdjust))
        }
        k += 1
    }
    throw IllegalStateException("could not find a next recurrence instance")
}
