// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

enum class AccountType {
    ASSET, BANK, CASH, CREDIT, LIABILITY, INCOME, EXPENSE, EQUITY, RECEIVABLE, PAYABLE
}

data class LedgerAccount(
    val id: Guid,
    val name: String,
    val type: AccountType,
    val parentId: Guid? = null,
)

data class LedgerSplit(val accountId: Guid, val value: Money)

data class LedgerTransaction(
    val id: Guid,
    val date: String,
    val description: String,
    val fraction: Long = 100,
    val splits: List<LedgerSplit>,
)

data class Ledger(
    val accounts: List<LedgerAccount>,
    val transactions: List<LedgerTransaction> = emptyList(),
)

enum class LedgerErrorCode {
    DUPLICATE_ACCOUNT, DUPLICATE_TRANSACTION, UNKNOWN_ACCOUNT, INVALID_DATE, TOO_FEW_SPLITS, UNBALANCED
}

class LedgerException(val code: LedgerErrorCode, message: String) : Exception(message)

data class TrialBalanceRow(
    val accountId: Guid,
    val name: String,
    val type: AccountType,
    val debit: Money,
    val credit: Money,
)

data class TrialBalance(
    val rows: List<TrialBalanceRow>,
    val totalDebits: Money,
    val totalCredits: Money,
) {
    val balanced: Boolean get() = totalDebits == totalCredits
}

data class BalanceSheet(
    val assets: Money,
    val liabilities: Money,
    val equity: Money,
    val retainedEarnings: Money,
    val totalLiabilitiesAndEquity: Money,
) {
    val balanced: Boolean get() = assets == totalLiabilitiesAndEquity
}

data class IncomeStatement(val revenue: Money, val expenses: Money, val netIncome: Money)

object LedgerEngine {
    private val datePattern = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val assetTypes = setOf(AccountType.ASSET, AccountType.BANK, AccountType.CASH, AccountType.RECEIVABLE)
    private val liabilityTypes = setOf(AccountType.LIABILITY, AccountType.CREDIT, AccountType.PAYABLE)

    fun create(accounts: List<LedgerAccount>): Ledger {
        val ids = mutableSetOf<Guid>()
        for (account in accounts) {
            if (!ids.add(account.id)) throw LedgerException(LedgerErrorCode.DUPLICATE_ACCOUNT, "duplicate account")
        }
        return Ledger(accounts)
    }

    fun post(ledger: Ledger, transaction: LedgerTransaction): Ledger {
        if (!datePattern.matches(transaction.date)) {
            throw LedgerException(LedgerErrorCode.INVALID_DATE, "invalid date")
        }
        if (ledger.transactions.any { it.id == transaction.id }) {
            throw LedgerException(LedgerErrorCode.DUPLICATE_TRANSACTION, "duplicate transaction")
        }
        if (transaction.splits.size < 2) throw LedgerException(LedgerErrorCode.TOO_FEW_SPLITS, "too few splits")
        val accounts = ledger.accounts.map { it.id }.toSet()
        var total = Money.ZERO
        val normalized = transaction.splits.map { split ->
            if (split.accountId !in accounts) throw LedgerException(LedgerErrorCode.UNKNOWN_ACCOUNT, "unknown account")
            LedgerSplit(split.accountId, split.value.roundedHalfUp(transaction.fraction)).also {
                total = Money.add(total, it.value)
            }
        }
        if (total != Money.ZERO) throw LedgerException(LedgerErrorCode.UNBALANCED, "unbalanced")
        return ledger.copy(transactions = ledger.transactions + transaction.copy(splits = normalized))
    }

    fun accountBalance(ledger: Ledger, accountId: Guid, asOf: String? = null): Money {
        var total = Money.ZERO
        for (transaction in ledger.transactions) {
            if (asOf != null && transaction.date > asOf) continue
            for (split in transaction.splits) {
                if (split.accountId == accountId) total = Money.add(total, split.value)
            }
        }
        return total
    }

    private fun sum(ledger: Ledger, types: Set<AccountType>, from: String? = null, to: String? = null): Money {
        val ids = ledger.accounts.filter { it.type in types }.map { it.id }.toSet()
        var total = Money.ZERO
        for (transaction in ledger.transactions) {
            if (from != null && transaction.date < from) continue
            if (to != null && transaction.date > to) continue
            for (split in transaction.splits) {
                if (split.accountId in ids) total = Money.add(total, split.value)
            }
        }
        return total
    }

    fun trialBalance(ledger: Ledger, asOf: String? = null): TrialBalance {
        val rows = mutableListOf<TrialBalanceRow>()
        var debits = Money.ZERO
        var credits = Money.ZERO
        for (account in ledger.accounts) {
            val balance = accountBalance(ledger, account.id, asOf)
            if (balance == Money.ZERO) continue
            val debit = if (balance.num > 0) balance else Money.ZERO
            val credit = if (balance.num < 0) balance.negated() else Money.ZERO
            rows += TrialBalanceRow(account.id, account.name, account.type, debit, credit)
            debits = Money.add(debits, debit)
            credits = Money.add(credits, credit)
        }
        return TrialBalance(rows, debits, credits)
    }

    fun balanceSheet(ledger: Ledger, asOf: String): BalanceSheet {
        val assets = sum(ledger, assetTypes, to = asOf)
        val liabilities = sum(ledger, liabilityTypes, to = asOf).negated()
        val equity = sum(ledger, setOf(AccountType.EQUITY), to = asOf).negated()
        val retained = Money.add(sum(ledger, setOf(AccountType.INCOME), to = asOf), sum(ledger, setOf(AccountType.EXPENSE), to = asOf)).negated()
        return BalanceSheet(assets, liabilities, equity, retained, Money.add(Money.add(liabilities, equity), retained))
    }

    fun incomeStatement(ledger: Ledger, from: String, to: String): IncomeStatement {
        val revenue = sum(ledger, setOf(AccountType.INCOME), from, to).negated()
        val expenses = sum(ledger, setOf(AccountType.EXPENSE), from, to)
        return IncomeStatement(revenue, expenses, Money.subtract(revenue, expenses))
    }
}
