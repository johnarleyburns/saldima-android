// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportDateTest {
    @Test
    fun `accepts the date shapes banks export`() {
        assertEquals("2024-01-31", parseImportDate("2024-01-31"))
        assertEquals("2024-01-31", parseImportDate("2024/1/31"))
        assertEquals("2024-01-31", parseImportDate("01/31/2024"))
        assertEquals("2024-01-31", parseImportDate("31/01/2024"))
        assertEquals("2024-01-02", parseImportDate("1/2/24"))
        assertEquals("2024-01-31", parseImportDate("20240131120000[0:GMT]"))
    }

    @Test
    fun `rejects impossible and unrecognised dates`() {
        assertNull(parseImportDate("2024-02-30"))
        assertNull(parseImportDate("13/13/2024"))
        assertNull(parseImportDate("yesterday"))
        assertNull(parseImportDate(""))
    }

    @Test
    fun `handles leap years, short years and other separators`() {
        assertEquals("2024-02-29", parseImportDate("02/29/2024"))
        assertNull(parseImportDate("02/29/2023"))
        assertEquals("1970-01-02", parseImportDate("01/02/70"))
        assertNull(parseImportDate("2024/13/01"))
        assertEquals("2024-01-02", parseImportDate("1 2 2024"))
        assertNull(parseImportDate("20241301"))
    }
}
