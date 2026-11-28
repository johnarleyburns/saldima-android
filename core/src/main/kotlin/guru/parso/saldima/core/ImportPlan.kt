// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * Ported from the web track's `packages/importers/src/plan.ts`. Builds
 * balanced two-or-more-split transactions from statement rows; account
 * identifiers are `Guid` here (the web type uses a plain string since
 * TypeScript has no such distinct type).
 */
data class ImportMapping(
    val targetAccountGuid: Guid,
    val defaultAccountGuid: Guid,
    val categoryAccounts: Map<String, Guid> = emptyMap(),
)

data class PlannedSplit(
    val accountGuid: Guid,
    val value: Money,
)

data class PlannedTransaction(
    val date: String,
    val description: String,
    val splits: List<PlannedSplit>,
)

/** QIF writes transfers as `[Savings]`; map either form to the account name. */
fun categoryKey(category: String): String {
    val value = category.trim()
    return if (value.startsWith("[") && value.endsWith("]")) {
        value.substring(1, value.length - 1).trim()
    } else {
        value
    }
}

private fun counterGuid(category: String, mapping: ImportMapping): Guid =
    mapping.categoryAccounts[categoryKey(category)] ?: mapping.defaultAccountGuid

private fun descriptionFor(row: ImportRow): String {
    val parts = listOf(row.payee.trim(), row.memo.trim()).filter { it.isNotEmpty() }
    val joined = if (parts.isNotEmpty()) parts.joinToString(" — ") else "Imported transaction"
    return joined.take(200)
}

fun planImport(rows: List<ImportRow>, mapping: ImportMapping): List<PlannedTransaction> =
    rows.map { row ->
        val splits = mutableListOf(PlannedSplit(mapping.targetAccountGuid, row.amount))
        if (row.splits.isEmpty()) {
            splits.add(PlannedSplit(counterGuid(row.category, mapping), row.amount.negated()))
        } else {
            var counterTotal = Money.ZERO
            for (split in row.splits) {
                val value = split.amount.negated()
                counterTotal = Money.add(counterTotal, value)
                splits.add(PlannedSplit(counterGuid(split.category, mapping), value))
            }
            val remainder = Money.add(counterTotal, row.amount)
            if (remainder.num != 0L) {
                splits.add(PlannedSplit(mapping.defaultAccountGuid, remainder.negated()))
            }
        }
        PlannedTransaction(row.date, descriptionFor(row), splits.toList())
    }
