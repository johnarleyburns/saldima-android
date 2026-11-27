// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * A GnuCash `GncBudget` (desktop's "Budgets"): a recurrence plus a count of
 * periods. Per-account-per-period budgeted amounts live on the budget's own
 * KVP frame (`budgetAmount`/`setBudgetAmount` on `XmlBookDocument`), not
 * here -- matching real GnuCash, where an unset period is simply absent,
 * never a zero. Mirrors the web track's `XmlBudget`
 * (`packages/gnucash/src/xml-document.ts`). Milestone 1 (tracked in the
 * repo's issue): no per-period notes, no book-level default-budget option,
 * no "estimate from historical actuals" autofill.
 */
data class XmlBudget(
    val guid: Guid,
    val name: String,
    val description: String,
    val periodCount: Int,
    val recurrence: Recurrence,
)

/** A budget period's `[start, end)` range, both `YYYY-MM-DD`. */
data class BudgetPeriodRange(val start: String, val end: String)
