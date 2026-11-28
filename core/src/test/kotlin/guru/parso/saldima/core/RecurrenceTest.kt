// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Direct port of `packages/gnucash/src/recurrence.test.ts` -- same cases, same expectations. */
class RecurrenceTest {
    private fun recurrence(
        periodType: PeriodType = PeriodType.DAY,
        multiplier: Int = 1,
        start: String,
        weekendAdjust: WeekendAdjust = WeekendAdjust.NONE,
    ) = Recurrence(periodType, multiplier, start, weekendAdjust)

    @Test
    fun `'once' fires exactly once -- the start date before it, then never again`() {
        val r = recurrence(periodType = PeriodType.ONCE, start = "2026-03-01")
        assertEquals("2026-03-01", nextInstance(r, "2026-02-01"))
        assertNull(nextInstance(r, "2026-03-01"))
        assertNull(nextInstance(r, "2026-03-02"))
    }

    @Test
    fun `'day' with a multiplier steps by whole multiples from start, not from refDate`() {
        val r = recurrence(periodType = PeriodType.DAY, multiplier = 3, start = "2026-01-01")
        assertEquals("2026-01-01", nextInstance(r, "2025-12-31"))
        assertEquals("2026-01-04", nextInstance(r, "2026-01-01"))
        assertEquals("2026-01-04", nextInstance(r, "2026-01-03"))
        assertEquals("2026-01-07", nextInstance(r, "2026-01-04"))
    }

    @Test
    fun `'week' with a multiplier steps by multiples of 7 days`() {
        val r = recurrence(periodType = PeriodType.WEEK, multiplier = 2, start = "2026-01-05")
        assertEquals("2026-01-19", nextInstance(r, "2026-01-05"))
        assertEquals("2026-01-19", nextInstance(r, "2026-01-18"))
    }

    @Test
    fun `'month' keeps the start day-of-month, clamped to shorter months`() {
        val r = recurrence(periodType = PeriodType.MONTH, multiplier = 1, start = "2026-01-31")
        // January 31 + 1 month has no 31st in February -- clamps to the 28th.
        assertEquals("2026-02-28", nextInstance(r, "2026-01-31"))
    }

    @Test
    fun `'month' with a multiplier greater than 1 steps by whole multiples of months`() {
        val r = recurrence(periodType = PeriodType.MONTH, multiplier = 3, start = "2026-01-15")
        assertEquals("2026-04-15", nextInstance(r, "2026-01-15"))
        assertEquals("2026-04-15", nextInstance(r, "2026-03-01"))
    }

    @Test
    fun `'end_of_month' always lands on the last day of the target month`() {
        val r = recurrence(periodType = PeriodType.END_OF_MONTH, multiplier = 1, start = "2026-01-15")
        assertEquals("2026-01-31", nextInstance(r, "2026-01-10"))
        assertEquals("2026-02-28", nextInstance(r, "2026-01-31"))
    }

    @Test
    fun `'nth_weekday' keeps the same occurrence of the same weekday each month`() {
        // 2026-01-13 is the 2nd Tuesday of January 2026.
        val r = recurrence(periodType = PeriodType.NTH_WEEKDAY, multiplier = 1, start = "2026-01-13")
        assertEquals("2026-02-10", nextInstance(r, "2026-01-13")) // 2nd Tuesday of February
    }

    @Test
    fun `'nth_weekday' falls back to the last occurrence when the nth doesn't exist`() {
        // 2026-12-29 is the 5th Tuesday of December 2026; January 2027 only has 4.
        val r = recurrence(periodType = PeriodType.NTH_WEEKDAY, multiplier = 1, start = "2026-12-29")
        assertEquals("2027-01-26", nextInstance(r, "2026-12-29")) // last Tuesday of January
    }

    @Test
    fun `'last_weekday' always lands on the last occurrence of the weekday in the target month`() {
        // 2026-01-27 is the last Tuesday of January 2026.
        val r = recurrence(periodType = PeriodType.LAST_WEEKDAY, multiplier = 1, start = "2026-01-27")
        assertEquals("2026-02-24", nextInstance(r, "2026-01-27")) // last Tuesday of February
    }

    @Test
    fun `'year' keeps the month and day, clamping Feb 29 to Feb 28 off a leap year`() {
        val r = recurrence(periodType = PeriodType.YEAR, multiplier = 1, start = "2024-02-29")
        assertEquals("2025-02-28", nextInstance(r, "2024-02-29"))
    }

    @Test
    fun `'year' with a multiplier greater than 1 steps by whole multiples of years`() {
        val r = recurrence(periodType = PeriodType.YEAR, multiplier = 2, start = "2026-06-01")
        assertEquals("2028-06-01", nextInstance(r, "2026-06-01"))
    }

    @Test
    fun `weekend adjustment moves a Saturday or Sunday instance to Friday or Monday`() {
        val forward = recurrence(periodType = PeriodType.DAY, multiplier = 7, start = "2026-01-03", weekendAdjust = WeekendAdjust.FORWARD)
        assertEquals("2026-01-12", nextInstance(forward, "2026-01-03")) // Saturday -> Monday

        val back = recurrence(periodType = PeriodType.DAY, multiplier = 7, start = "2026-01-03", weekendAdjust = WeekendAdjust.BACK)
        assertEquals("2026-01-09", nextInstance(back, "2026-01-03")) // Saturday -> Friday

        val sunday = recurrence(periodType = PeriodType.DAY, multiplier = 7, start = "2026-01-04", weekendAdjust = WeekendAdjust.FORWARD)
        assertEquals("2026-01-12", nextInstance(sunday, "2026-01-04")) // Sunday -> Monday

        val sundayBack = recurrence(periodType = PeriodType.DAY, multiplier = 7, start = "2026-01-04", weekendAdjust = WeekendAdjust.BACK)
        assertEquals("2026-01-09", nextInstance(sundayBack, "2026-01-04")) // Sunday -> Friday
    }

    @Test
    fun `does not weekend-adjust a weekday instance`() {
        val r = recurrence(periodType = PeriodType.DAY, multiplier = 1, start = "2026-01-05", weekendAdjust = WeekendAdjust.FORWARD) // a Monday
        assertEquals("2026-01-06", nextInstance(r, "2026-01-05"))
    }

    @Test
    fun `rejects a multiplier under 1`() {
        val r = recurrence(periodType = PeriodType.DAY, multiplier = 0, start = "2026-01-01")
        assertFailsWith<IllegalArgumentException> { nextInstance(r, "2026-01-01") }
    }

    @Test
    fun `rejects a malformed date`() {
        val r = recurrence(periodType = PeriodType.DAY, multiplier = 1, start = "not-a-date")
        assertFailsWith<IllegalArgumentException> { nextInstance(r, "2026-01-01") }
    }

    @Test
    fun `daysBetween is order-independent`() {
        assertEquals(9L, daysBetween("2026-01-01", "2026-01-10"))
        assertEquals(9L, daysBetween("2026-01-10", "2026-01-01"))
    }

    @Test
    fun `daysBetween is zero for the same date`() {
        assertEquals(0L, daysBetween("2026-01-01", "2026-01-01"))
    }

    @Test
    fun `daysBetween counts across a year boundary`() {
        assertEquals(7L, daysBetween("2025-12-28", "2026-01-04"))
    }
}
