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

    fun balances(snapshot: BookSnapshot): Map<Guid, Money> {
        val totals = mutableMapOf<Guid, Money>()
        for (transaction in snapshot.transactions) {
            for (split in transaction.splits) {
                totals[split.accountGuid] = Money.add(totals[split.accountGuid] ?: Money.ZERO, split.value)
            }
        }
        return totals
    }

    /** Parent rows show their own balance plus every descendant. */
    fun rolledUp(snapshot: BookSnapshot): Map<Guid, Money> {
        val balances = balances(snapshot)
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

    fun totals(snapshot: BookSnapshot): BookTotals {
        val balances = balances(snapshot)
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
