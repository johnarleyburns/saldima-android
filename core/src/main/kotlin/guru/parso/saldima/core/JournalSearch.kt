// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * Desktop GnuCash's "Find Transactions", simplified to one free-text
 * query: matches a transaction's description, any split's memo, or any
 * split's account name (case-insensitive substring) -- the same fields
 * the web track's `/app/journal?q=` search matches. A blank query
 * matches everything.
 */
fun matchesJournalSearch(
    transaction: SnapshotTransaction,
    query: String,
    accountNames: Map<Guid, String>,
): Boolean {
    if (query.isEmpty()) return true
    val needle = query.lowercase()
    if (transaction.description.lowercase().contains(needle)) return true
    return transaction.splits.any { split ->
        split.memo.lowercase().contains(needle) ||
            accountNames[split.accountGuid]?.lowercase()?.contains(needle) == true
    }
}
