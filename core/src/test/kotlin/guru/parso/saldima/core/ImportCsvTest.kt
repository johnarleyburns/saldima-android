// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ImportCsvTest {
    private fun amounts(rows: List<ImportRow>) = rows.map { it.amount.formatted(100) }

    @Test
    fun `detects headers, quoted fields and exact amounts`() {
        val result = parseCsv(
            "Date,Description,Amount,Category,Reference\n" +
                "2024-01-05,\"Coffee, shop\",-4.50,Meals,1001\n" +
                "2024-01-06,Paycheck,\"2,000.00\",Salary,\n",
        )
        assertEquals(ImportSource.CSV, result.source)
        assertEquals(2, result.rows.size)
        assertEquals("2024-01-05", result.rows[0].date)
        assertEquals("Coffee, shop", result.rows[0].payee)
        assertEquals("Meals", result.rows[0].category)
        assertEquals("1001", result.rows[0].reference)
        assertEquals(listOf("-4.50", "2000.00"), amounts(result.rows))
    }

    @Test
    fun `uses debit and credit columns and a semicolon delimiter`() {
        val text = "Date;Description;Debit;Credit\n01/05/2024;Rent;1,200.00;\n01/06/2024;Refund;;25.00\n"
        val table = parseCsvTable(text)
        assertEquals(';', table.delimiter)
        val map = detectCsvMap(table.header)
        assertNull(map.valueIndex)
        assertEquals(listOf("-1200.00", "25.00"), amounts(parseCsv(text, map).rows))
    }

    @Test
    fun `warns about rows it cannot read but keeps the rest`() {
        val result = parseCsv(
            "Posted,Payee,Debit,Credit\n2024-01-01,Good,10.00,\nnot-a-date,Bad,5.00,\n2024-01-03,Also good,,7.00\n",
        )
        assertEquals(2, result.rows.size)
        assertEquals(1, result.warnings.size)
    }

    @Test
    fun `rejects a table without a date or amount column`() {
        assertFailsWith<ImportFormatException> { detectCsvMap(listOf("Name", "Note")) }
    }

    @Test
    fun `handles BOM, pipes, escaped quotes and CRLF`() {
        val result = parseCsv(
            "﻿Date|Description|Amount\r\n2024-01-01|\"A \"\"quoted\"\" shop\"|-1.00\r\n",
        )
        assertEquals("A \"quoted\" shop", result.rows[0].payee)
        assertEquals(listOf("-1.00"), amounts(result.rows))
    }

    @Test
    fun `skips bad debit, credit, empty and zero rows`() {
        val result = parseCsv(
            "Date,Description,Debit,Credit\n" +
                "2024-01-01,Bad debit,xx,\n" +
                "2024-01-02,Bad credit,,yy\n" +
                "2024-01-03,None,,\n" +
                "2024-01-04,Zero,0.00,\n" +
                ",\n" +
                "2024-01-05,Good,3.00,\n",
        )
        assertEquals(1, result.rows.size)
        assertEquals(4, result.warnings.size)
    }

    @Test
    fun `uses the first unclaimed column for the description and value headers`() {
        val result = parseCsv("Date,Merchant,Value,Type,Check Number\n2024-01-01,Bar,5.00,Income,42\n")
        assertEquals("Bar", result.rows[0].payee)
        assertEquals("Income", result.rows[0].category)
        assertEquals("42", result.rows[0].reference)
    }

    @Test
    fun `throws when every row is unusable`() {
        assertFailsWith<ImportFormatException> { parseCsv("Date,Amount\nnot-a-date,5.00") }
    }
}
