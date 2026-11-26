// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

data class BookTotals(
    val assets: Money,
    val liabilities: Money,
    val netAssets: Money,
    val profit: Money,
)

/** Same balance and total rules as the web and iOS tracks. */
object Totals {
    private val assetTypes = setOf("ASSET", "BANK", "CASH", "RECEIVABLE")
    private val liabilityTypes = setOf("LIABILITY", "CREDIT", "PAYABLE")

    /**
     * Balances from every split whose transaction date falls within
     * [from, to] (either bound omitted means unbounded on that side) --
     * the same as-of/period convention the web and iOS reports use.
     */
    fun balances(snapshot: BookSnapshot, from: String? = null, to: String? = null): Map<Guid, Money> {
        val totals = mutableMapOf<Guid, Money>()
        for (transaction in snapshot.transactions) {
            val day = transaction.date.take(10)
            if (from != null && day < from) continue
            if (to != null && day > to) continue
            for (split in transaction.splits) {
                totals[split.accountGuid] = Money.add(totals[split.accountGuid] ?: Money.ZERO, split.value)
            }
        }
        return totals
    }

    /** Parent rows show their own balance plus every descendant. */
    fun rolledUp(snapshot: BookSnapshot, from: String? = null, to: String? = null): Map<Guid, Money> {
        val balances = balances(snapshot, from, to)
        val children = mutableMapOf<Guid, MutableList<Guid>>()
        for (account in snapshot.accounts) {
            val parent = account.parentGuid ?: continue
            children.getOrPut(parent) { mutableListOf() }.add(account.guid)
        }
        val totals = mutableMapOf<Guid, Money>()
        fun visit(guid: Guid): Money {
            totals[guid]?.let { return it }
            var total = balances[guid] ?: Money.ZERO
            for (child in children[guid].orEmpty()) total = Money.add(total, visit(child))
            totals[guid] = total
            return total
        }
        for (account in snapshot.accounts) visit(account.guid)
        return totals
    }

    fun totals(snapshot: BookSnapshot, from: String? = null, to: String? = null): BookTotals {
        val balances = balances(snapshot, from, to)
        var assets = Money.ZERO
        var liabilities = Money.ZERO
        var income = Money.ZERO
        var expense = Money.ZERO
        for (account in snapshot.accounts) {
            val balance = balances[account.guid] ?: Money.ZERO
            when (account.type) {
                in assetTypes -> assets = Money.add(assets, balance)
                in liabilityTypes -> liabilities = Money.add(liabilities, balance)
                "INCOME" -> income = Money.add(income, balance)
                "EXPENSE" -> expense = Money.add(expense, balance)
            }
        }
        return BookTotals(
            assets = assets,
            liabilities = liabilities,
            netAssets = Money.subtract(assets, liabilities),
            profit = Money.add(income, expense).negated(),
        )
    }
}
