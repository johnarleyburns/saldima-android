// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ImportPlanTest {
    private val target = Guid.parse("1".repeat(32))
    private val unclassified = Guid.parse("2".repeat(32))
    private val groceries = Guid.parse("3".repeat(32))
    private val salary = Guid.parse("4".repeat(32))
    private val household = Guid.parse("5".repeat(32))
    private val savings = Guid.parse("6".repeat(32))

    private val mapping = ImportMapping(
        targetAccountGuid = target,
        defaultAccountGuid = unclassified,
        categoryAccounts = mapOf(
            "Food:Groceries" to groceries,
            "Salary" to salary,
            "Household" to household,
        ),
    )

    private fun cents(value: Money) = value.formatted(100)

    @Test
    fun `posts a two-split transaction`() {
        val planned = planImport(
            listOf(
                ImportRow(
                    date = "2024-01-15",
                    amount = Money(-4567, 100),
                    payee = "Grocery Mart",
                    reference = "1001",
                    category = "Food:Groceries",
                ),
            ),
            mapping,
        ).single()
        assertEquals("Grocery Mart", planned.description)
        assertEquals(listOf(target, groceries), planned.splits.map { it.accountGuid })
        assertEquals(listOf("-45.67", "45.67"), planned.splits.map { cents(it.value) })
    }

    @Test
    fun `keeps QIF splits and balances any remainder to the default account`() {
        val planned = planImport(
            listOf(
                ImportRow(
                    date = "2024-03-01",
                    amount = Money(-6000, 100),
                    payee = "Store",
                    splits = listOf(
                        ImportSplit("Food:Groceries", Money(-4000, 100)),
                        ImportSplit("Household", Money(-1900, 100)),
                    ),
                ),
            ),
            mapping,
        ).single()
        assertEquals(listOf(target, groceries, household, unclassified), planned.splits.map { it.accountGuid })
        assertEquals(listOf("-60.00", "40.00", "19.00", "1.00"), planned.splits.map { cents(it.value) })
    }

    @Test
    fun `falls back to the default account for unknown categories`() {
        val planned = planImport(
            listOf(
                ImportRow(
                    date = "2024-01-01",
                    amount = Money(150000, 100),
                    payee = "Employer",
                    memo = "Payroll",
                    category = "Unknown",
                ),
            ),
            mapping,
        ).single()
        assertEquals("Employer — Payroll", planned.description)
        assertEquals(listOf(target, unclassified), planned.splits.map { it.accountGuid })
    }

    @Test
    fun `normalises transfer categories and default descriptions`() {
        val planned = planImport(
            listOf(
                ImportRow(
                    date = "2024-01-01",
                    amount = Money(-1000, 100),
                    category = "[Savings]",
                ),
            ),
            mapping.copy(categoryAccounts = mapOf("Savings" to savings)),
        ).single()
        assertEquals("Imported transaction", planned.description)
        assertEquals(listOf(target, savings), planned.splits.map { it.accountGuid })
    }

    @Test
    fun `does not add a balancing split when QIF splits already balance`() {
        val planned = planImport(
            listOf(
                ImportRow(
                    date = "2024-01-01",
                    amount = Money(-1000, 100),
                    payee = "Shop",
                    splits = listOf(ImportSplit("Food:Groceries", Money(-1000, 100))),
                ),
            ),
            mapping,
        ).single()
        assertEquals(2, planned.splits.size)
    }

    @Test
    fun `strips QIF transfer bracket syntax from category keys`() {
        assertEquals("Savings", categoryKey("[Savings]"))
        assertEquals("Food:Groceries", categoryKey("Food:Groceries"))
        assertEquals("", categoryKey(""))
    }
}
