// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ImportPdfTest {
    @Test
    fun `reads a clean single-amount line`() {
        val rows = parsePdfStatementLines(listOf("01/15/2024 Grocery Mart -45.67"), 2024)
        val row = rows.single()
        assertEquals("2024-01-15", row.date)
        assertEquals("-45.67", row.amount.formatted(100))
        assertEquals("Grocery Mart", row.payee)
    }

    @Test
    fun `takes the first amount token when an amount column is followed by a running balance`() {
        val rows = parsePdfStatementLines(listOf("01/15/2024 Grocery Mart -45.67 1234.56"), 2024)
        assertEquals("-45.67", rows.single().amount.formatted(100))
    }

    @Test
    fun `reads a parenthesized amount as negative`() {
        val rows = parsePdfStatementLines(listOf("01/15/2024 Annual fee (45.00)"), 2024)
        assertEquals("-45.00", rows.single().amount.formatted(100))
    }

    @Test
    fun `a bare minus with a space before the amount does not make it negative`() {
        val rows = parsePdfStatementLines(listOf("01/15/2024 Note - 45.00"), 2024)
        assertEquals("45.00", rows.single().amount.formatted(100))
    }

    @Test
    fun `recognises every supported date-token form`() {
        assertEquals("2024-01-15", parsePdfStatementLines(listOf("01/15/2024 Shop 5.00"), 2024).single().date)
        assertEquals("2024-01-15", parsePdfStatementLines(listOf("01-15-2024 Shop 5.00"), 2024).single().date)
        assertEquals("2024-01-15", parsePdfStatementLines(listOf("01/15/24 Shop 5.00"), 2024).single().date)
        assertEquals("2024-01-15", parsePdfStatementLines(listOf("Jan 15, 2024 Shop 5.00"), 2024).single().date)
        assertEquals("2024-01-15", parsePdfStatementLines(listOf("Jan 15 2024 Shop 5.00"), 2024).single().date)
        assertEquals("2024-01-15", parsePdfStatementLines(listOf("01/15 Shop 5.00"), 2024).single().date)
        assertEquals("2024-01-15", parsePdfStatementLines(listOf("Jan 15 Shop 5.00"), 2024).single().date)
    }

    @Test
    fun `uses the inferred year for a no-year date`() {
        val rows = parsePdfStatementLines(listOf("03/02 Coffee 3.50"), 2026)
        assertEquals("2026-03-02", rows.single().date)
    }

    @Test
    fun `infers the statement year from the first 2000 characters`() {
        val text = "Statement period: 01/01/2025 - 01/31/2025\n" + "x".repeat(2000)
        assertEquals(2025, inferStatementYear(text, 1999))
        assertEquals(1999, inferStatementYear("no year printed anywhere", 1999))
    }

    @Test
    fun `silently skips a line missing a date or an amount`() {
        assertTrue(parsePdfStatementLines(listOf("Grocery Mart -45.67"), 2024).isEmpty())
        assertTrue(parsePdfStatementLines(listOf("01/15/2024 Grocery Mart"), 2024).isEmpty())
        assertTrue(parsePdfStatementLines(listOf("Just a line of text"), 2024).isEmpty())
    }

    @Test
    fun `an invalid calendar date on a line is skipped, not fabricated`() {
        assertTrue(parsePdfStatementLines(listOf("13/45/2024 Nonsense 5.00"), 2024).isEmpty())
    }

    @Test
    fun `a blank text extraction reports the scanned-image message instead of crashing`() {
        val error = assertFailsWith<ImportFormatException> { parsePdfStatementText("", 2024) }
        assertTrue(error.message!!.contains("scanned image"))
        assertTrue(error.message!!.contains("OCR"))
    }

    @Test
    fun `a non-blank extraction with no matching lines reports zero rows, not an error`() {
        val result = parsePdfStatementText("Dear customer, thank you for banking with us.", 2024)
        assertEquals(ImportSource.PDF, result.source)
        assertTrue(result.rows.isEmpty())
        assertTrue(result.warnings.single().contains("0 transaction-like line"))
    }

    @Test
    fun `surfaces the experimental PDF warning with a row count`() {
        val result = parsePdfStatementText("01/15/2024 Grocery Mart -45.67", 2024)
        assertEquals(1, result.rows.size)
        assertTrue(result.warnings.single().contains("1 transaction-like line"))
        assertTrue(result.warnings.single().contains("Experimental PDF import"))
    }
}
