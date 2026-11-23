// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

data class TemplateAccount(
    val name: String,
    val type: AccountType,
    val children: List<TemplateAccount> = emptyList(),
    val placeholder: Boolean = false,
)

data class BookTemplate(
    val id: String,
    val name: String,
    val description: String,
    val roots: List<TemplateAccount>,
)

data class FlatTemplateAccount(
    val path: String,
    val parentPath: String?,
    val name: String,
    val type: AccountType,
    val placeholder: Boolean,
)

object BookTemplates {
    val personal = BookTemplate(
        id = "personal",
        name = "Personal",
        description = "Everyday household accounts.",
        roots = listOf(
            TemplateAccount("Assets", AccountType.ASSET, listOf(
                TemplateAccount("Bank", AccountType.BANK, listOf(
                    TemplateAccount("Checking", AccountType.BANK),
                    TemplateAccount("Savings", AccountType.BANK),
                )),
                TemplateAccount("Investments", AccountType.ASSET, placeholder = true),
            )),
            TemplateAccount("Liabilities", AccountType.LIABILITY, listOf(
                TemplateAccount("Credit Card", AccountType.CREDIT),
            )),
            TemplateAccount("Equity", AccountType.EQUITY, listOf(
                TemplateAccount("Opening Balances", AccountType.EQUITY),
            )),
            TemplateAccount("Income", AccountType.INCOME, listOf(
                TemplateAccount("Salary", AccountType.INCOME),
                TemplateAccount("Interest", AccountType.INCOME),
            )),
            TemplateAccount("Expenses", AccountType.EXPENSE, listOf(
                TemplateAccount("Rent", AccountType.EXPENSE),
                TemplateAccount("Groceries", AccountType.EXPENSE),
                TemplateAccount("Utilities", AccountType.EXPENSE),
            )),
        ),
    )

    val smallBusiness = BookTemplate(
        id = "small-business",
        name = "Small business",
        description = "Sole trader and small-team accounts.",
        roots = listOf(
            TemplateAccount("Assets", AccountType.ASSET, listOf(
                TemplateAccount("Business Checking", AccountType.BANK),
                TemplateAccount("Accounts Receivable", AccountType.RECEIVABLE),
            )),
            TemplateAccount("Liabilities", AccountType.LIABILITY, listOf(
                TemplateAccount("Accounts Payable", AccountType.PAYABLE),
                TemplateAccount("Sales Tax Payable", AccountType.LIABILITY),
            )),
            TemplateAccount("Equity", AccountType.EQUITY, listOf(
                TemplateAccount("Owner's Equity", AccountType.EQUITY),
            )),
            TemplateAccount("Income", AccountType.INCOME, listOf(
                TemplateAccount("Sales", AccountType.INCOME),
                TemplateAccount("Services", AccountType.INCOME),
            )),
            TemplateAccount("Expenses", AccountType.EXPENSE, listOf(
                TemplateAccount("Payroll", AccountType.EXPENSE),
                TemplateAccount("Rent", AccountType.EXPENSE),
                TemplateAccount("Supplies", AccountType.EXPENSE),
                TemplateAccount("Software", AccountType.EXPENSE),
            )),
        ),
    )

    val insuranceAgency = BookTemplate(
        id = "insurance-agency",
        name = "Insurance agency",
        description = "Premium trust, commissions and agency overhead.",
        roots = listOf(
            TemplateAccount("Assets", AccountType.ASSET, listOf(
                TemplateAccount("Trust", AccountType.BANK, listOf(
                    TemplateAccount("Premium Trust", AccountType.BANK),
                    TemplateAccount("Operating", AccountType.BANK),
                )),
                TemplateAccount("Commissions Receivable", AccountType.RECEIVABLE),
            )),
            TemplateAccount("Liabilities", AccountType.LIABILITY, listOf(
                TemplateAccount("Commissions Payable", AccountType.PAYABLE),
                TemplateAccount("Premiums Payable", AccountType.PAYABLE),
            )),
            TemplateAccount("Equity", AccountType.EQUITY, listOf(
                TemplateAccount("Owner's Equity", AccountType.EQUITY),
            )),
            TemplateAccount("Income", AccountType.INCOME, listOf(
                TemplateAccount("Commissions", AccountType.INCOME),
                TemplateAccount("Policy Fees", AccountType.INCOME),
            )),
            TemplateAccount("Expenses", AccountType.EXPENSE, listOf(
                TemplateAccount("E&O Insurance", AccountType.EXPENSE),
                TemplateAccount("Licensing", AccountType.EXPENSE),
                TemplateAccount("Office", AccountType.EXPENSE),
                TemplateAccount("Marketing", AccountType.EXPENSE),
            )),
        ),
    )

    val all = listOf(personal, smallBusiness, insuranceAgency)

    fun byId(id: String?): BookTemplate = all.firstOrNull { it.id == id } ?: personal

    fun flatten(template: BookTemplate): List<FlatTemplateAccount> {
        val rows = mutableListOf<FlatTemplateAccount>()
        fun visit(account: TemplateAccount, parent: String?) {
            val path = if (parent == null) account.name else "$parent/${account.name}"
            rows += FlatTemplateAccount(path, parent, account.name, account.type, account.placeholder)
            account.children.forEach { visit(it, path) }
        }
        template.roots.forEach { visit(it, null) }
        return rows
    }
}
