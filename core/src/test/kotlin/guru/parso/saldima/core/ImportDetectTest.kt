// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val QIF_SAMPLE = "!Type:Bank\nD01/15/2024\nT-45.67\nPGrocery Mart\n^\n"
private const val OFX_SAMPLE = "OFXHEADER:100\n<OFX><STMTTRN><DTPOSTED>20240115<TRNAMT>1.00</STMTTRN></OFX>"

class ImportDetectTest {
    @Test
    fun `sniffs QIF, OFX and CSV regardless of extension`() {
        assertEquals(ImportSource.QIF, detectImportSource("statement.txt", QIF_SAMPLE))
        assertEquals(ImportSource.OFX, detectImportSource("statement.csv", OFX_SAMPLE))
        assertEquals(ImportSource.CSV, detectImportSource("statement.qif", "Date,Amount\n2024-01-01,5.00"))
        assertEquals(ImportSource.OFX, detectImportSource("statement.qfx", "no markers here"))
        assertEquals(ImportSource.CSV, detectImportSource("statement.txt", "^ only\nsomething"))
    }

    @Test
    fun `routes a parsed file through the right parser`() {
        val result = parseImportFile("statement.qif", QIF_SAMPLE)
        assertEquals(ImportSource.QIF, result.source)
        assertEquals(1, result.rows.size)
        assertFailsWith<ImportFormatException> { parseImportFile("empty.csv", "Name,Note\n") }
    }
}
