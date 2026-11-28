// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * Shared shape for a parsed statement row, ported from the web track's
 * `packages/importers/src/types.ts` so QIF, OFX, CSV and the new PDF
 * heuristic all produce the same thing for `planImport`/`isLikelyDuplicate`.
 */
enum class ImportSource { QIF, CSV, OFX, PDF }

data class ImportSplit(
    val category: String,
    val amount: Money,
)

/**
 * One statement line. `amount` is positive when it increases the target
 * (statement) account -- the same convention as the web type's own doc
 * comment. `splits` is only non-empty for CSV-style multi-category rows;
 * QIF, OFX and PDF rows never populate it.
 */
data class ImportRow(
    val date: String,
    val amount: Money,
    val payee: String = "",
    val memo: String = "",
    val reference: String = "",
    val category: String = "",
    val splits: List<ImportSplit> = emptyList(),
)

data class ImportResult(
    val source: ImportSource,
    val rows: List<ImportRow>,
    val warnings: List<String>,
)

/** Mirrors the web track's `ImportFormatError`: a statement that could not be read at all. */
class ImportFormatException(message: String) : Exception(message)
