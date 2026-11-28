// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private const val OFX_SGML = """OFXHEADER:100
DATA:OFXSGML
VERSION:102

<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
<STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20240115120000<TRNAMT>-45.67<FITID>abc123<NAME>Grocery Mart<MEMO>Card</STMTTRN>
<STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20240116<TRNAMT>1500.00<FITID>def456<NAME>Employer</STMTTRN>
</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>"""

private const val OFX_XML = """<?xml version="1.0"?>
<OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><BANKTRANLIST>
<STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20240115</DTPOSTED><TRNAMT>-45.67</TRNAMT><FITID>abc123</FITID><NAME>Grocery Mart</NAME><MEMO>Card</MEMO></STMTTRN>
</BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>"""

class ImportOfxTest {
    private fun amounts(rows: List<ImportRow>) = rows.map { it.amount.formatted(100) }

    @Test
    fun `reads SGML bank transactions`() {
        val result = parseOfx(OFX_SGML)
        assertEquals(ImportSource.OFX, result.source)
        assertEquals(2, result.rows.size)
        assertEquals("2024-01-15", result.rows[0].date)
        assertEquals("Grocery Mart", result.rows[0].payee)
        assertEquals("Card", result.rows[0].memo)
        assertEquals("abc123", result.rows[0].reference)
        assertEquals(listOf("-45.67", "1500.00"), amounts(result.rows))
    }

    @Test
    fun `reads XML bank transactions`() {
        val result = parseOfx(OFX_XML)
        assertEquals(1, result.rows.size)
        assertEquals("Grocery Mart", result.rows[0].payee)
    }

    @Test
    fun `keeps usable rows and falls back to payee and check number`() {
        val result = parseOfx(
            """<OFX>
<STMTTRN><DTPOSTED>bad<TRNAMT>1.00</STMTTRN>
<STMTTRN><DTPOSTED>20240101<TRNAMT>bad</STMTTRN>
<STMTTRN><DTPOSTED>20240101<TRNAMT>0.00</STMTTRN>
<STMTTRN><DTPOSTED>20240102<TRNAMT>2.00<PAYEE>Payee Co<CHECKNUM>77</STMTTRN>
</OFX>""",
        )
        assertEquals(1, result.rows.size)
        assertEquals("Payee Co", result.rows[0].payee)
        assertEquals("77", result.rows[0].reference)
        assertEquals(3, result.warnings.size)
    }

    @Test
    fun `rejects files without transactions`() {
        assertFailsWith<ImportFormatException> { parseOfx("<OFX></OFX>") }
    }
}
