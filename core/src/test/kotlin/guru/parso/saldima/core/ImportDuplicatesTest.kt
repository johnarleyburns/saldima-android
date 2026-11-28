// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportDuplicatesTest {
    private val existing = listOf(ExistingEntry("2024-01-10", Money(1000, 100)))

    @Test
    fun `flags the same amount within three days`() {
        assertTrue(isLikelyDuplicate("2024-01-13", Money(10, 1), existing))
    }

    @Test
    fun `does not flag a different amount or a distant date`() {
        assertFalse(isLikelyDuplicate("2024-01-13", Money(1001, 100), existing))
        assertFalse(isLikelyDuplicate("2024-01-14", Money(1000, 100), existing))
        assertFalse(isLikelyDuplicate("2024-01-10", Money(-1000, 100), existing))
    }

    @Test
    fun `ignores entries with unreadable dates`() {
        assertFalse(isLikelyDuplicate("not-a-date", Money(1000, 100), existing))
        assertFalse(
            isLikelyDuplicate("2024-01-10", Money(1000, 100), listOf(ExistingEntry("not-a-date", Money(1000, 100)))),
        )
    }

    @Test
    fun `computes calendar ordinals without a clock`() {
        assertNotNull(dayOrdinal("2024-02-29"))
        assertNull(dayOrdinal("2023-02-29"))
        assertNull(dayOrdinal("2024-04-31"))
        assertNotNull(dayOrdinal("2024-06-30"))
        assertNotNull(dayOrdinal("2024-09-30"))
        assertNotNull(dayOrdinal("2024-11-30"))
        assertNull(dayOrdinal("2024-13-01"))
        assertNull(dayOrdinal("bad"))
    }

    @Test
    fun `treats differently-represented equal amounts as equal`() {
        assertTrue(moneyEquals(Money(3, 1), Money(300, 100)))
        assertFalse(moneyEquals(Money(3, 1), Money(301, 100)))
    }
}
