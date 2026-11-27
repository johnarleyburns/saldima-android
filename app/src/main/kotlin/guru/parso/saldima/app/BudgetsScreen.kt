// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.PeriodType
import guru.parso.saldima.core.Recurrence
import guru.parso.saldima.core.SnapshotAccount
import guru.parso.saldima.core.WeekendAdjust
import guru.parso.saldima.core.XmlBudget
import java.time.LocalDate

/** Budgeted vs. actual by account and period, like desktop GnuCash's Actions -> Budget. */
@Composable
fun BudgetsScreen(repository: BookRepository) {
    LaunchedEffect(Unit) { repository.loadExisting() }
    val snapshot = repository.snapshot
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<XmlBudget?>(null) }
    var gridFor by remember { mutableStateOf<Guid?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (snapshot == null) {
            Text("Budgets", style = MaterialTheme.typography.headlineMedium)
            Text(repository.error ?: "Open a book from Accounts first.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        val grid = gridFor?.let { guid -> repository.budgets.firstOrNull { it.guid == guid } }
        if (grid != null) {
            BudgetGrid(repository, grid, snapshot.accounts, onBack = { gridFor = null })
            return@Column
        }

        Text("Budgets", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Budgeted vs. actual by account and period, like desktop GnuCash's Budgets editor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        repository.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = { creating = true }) { Text("New budget") }
        Spacer(Modifier.height(10.dp))

        val budgets = repository.budgets
        if (budgets.isEmpty()) {
            Text("No budgets yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(budgets.size) { index ->
                    val budget = budgets[index]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(budget.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${describeBudgetRecurrence(budget.recurrence)} · ${budget.periodCount} period(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row {
                                TextButton(onClick = { gridFor = budget.guid }) { Text("Open grid") }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { editing = budget }) { Text("Edit") }
                                TextButton(onClick = { repository.deleteBudget(budget.guid) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NewBudgetDialog(
            onDismiss = { creating = false },
            onCreate = { name, description, periodCount, recurrence ->
                repository.createBudget(name, description, periodCount, recurrence)
                creating = false
            },
        )
    }

    editing?.let { budget ->
        EditBudgetDialog(
            budget = budget,
            onDismiss = { editing = null },
            onSave = { name, description, periodCount ->
                repository.updateBudget(budget.guid, name, description, periodCount)
                editing = null
            },
        )
    }
}

private val BUDGET_PERIOD_TYPE_LABELS = mapOf(
    PeriodType.DAY to "day",
    PeriodType.WEEK to "week",
    PeriodType.MONTH to "month",
    PeriodType.END_OF_MONTH to "month, on the last day",
    PeriodType.NTH_WEEKDAY to "month, same weekday",
    PeriodType.LAST_WEEKDAY to "month, last weekday",
    PeriodType.YEAR to "year",
)

private fun describeBudgetRecurrence(recurrence: Recurrence): String {
    val label = BUDGET_PERIOD_TYPE_LABELS[recurrence.periodType] ?: recurrence.periodType.name.lowercase()
    return if (recurrence.multiplier > 1) "Every ${recurrence.multiplier} ${label}s" else "Every $label"
}

/** Accounts x periods: one editable budgeted-amount row and one read-only actual row per account. */
@Composable
private fun BudgetGrid(
    repository: BookRepository,
    budget: XmlBudget,
    accounts: List<SnapshotAccount>,
    onBack: () -> Unit,
) {
    // Matches web's own budgetable-account filter: visible, non-placeholder
    // accounts of any type (not just INCOME/EXPENSE), same as the
    // scheduled-transaction split picker.
    val budgetable = remember(accounts) {
        accounts.filter { it.type != "ROOT" && !it.hidden && !it.placeholder }.sortedBy { it.name.lowercase() }
    }
    val periods = remember(budget) { (0 until budget.periodCount).toList() }
    val amounts = remember(budget) {
        mutableStateMapOf<Pair<Guid, Int>, String>().apply {
            for (account in budgetable) {
                for (periodIndex in periods) {
                    val existing = repository.budgetAmount(budget.guid, account.guid, periodIndex)
                    put(account.guid to periodIndex, existing?.formatted(100) ?: "")
                }
            }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) { Text("← Budgets") }
        Text(budget.name, style = MaterialTheme.typography.headlineMedium)
        Text(
            "${describeBudgetRecurrence(budget.recurrence)}. Enter a budgeted amount per account and period; " +
                "leave blank to clear it. \"Actual\" is computed live from posted transactions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        (repository.error ?: error)?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(10.dp))

        if (budgetable.isEmpty()) {
            Text("No budgetable accounts yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val scroll = rememberScrollState()
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(budgetable.size) { index ->
                    val account = budgetable[index]
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Text(account.name, fontWeight = FontWeight.SemiBold)
                        Row(Modifier.horizontalScroll(scroll)) {
                            for (periodIndex in periods) {
                                val range = repository.periodRange(budget, periodIndex)
                                Column(Modifier.width(110.dp).padding(horizontal = 4.dp)) {
                                    Text(range?.start ?: "", style = MaterialTheme.typography.labelSmall)
                                    OutlinedTextField(
                                        value = amounts[account.guid to periodIndex] ?: "",
                                        onValueChange = { text -> amounts[account.guid to periodIndex] = text },
                                        placeholder = { Text("0.00") },
                                        singleLine = true,
                                        modifier = Modifier.width(100.dp),
                                    )
                                    Text(
                                        "actual: ${repository.budgetActual(budget.guid, account.guid, periodIndex).formatted(100)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val toSave = mutableMapOf<Pair<Guid, Int>, Money?>()
            var failure: String? = null
            for (account in budgetable) {
                for (periodIndex in periods) {
                    val text = amounts[account.guid to periodIndex]?.trim().orEmpty()
                    if (text.isEmpty()) {
                        toSave[account.guid to periodIndex] = null
                        continue
                    }
                    val cents = parseBudgetCents(text)
                    if (cents == null) {
                        failure = "\"$text\" isn't a valid amount for ${account.name}, period $periodIndex."
                        break
                    }
                    toSave[account.guid to periodIndex] = Money(cents, 100)
                }
                if (failure != null) break
            }
            if (failure != null) {
                error = failure
            } else {
                error = null
                repository.saveBudgetAmounts(budget.guid, toSave)
            }
        }) { Text("Save amounts") }
    }
}

@Composable
private fun NewBudgetDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, periodCount: Int, recurrence: Recurrence) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var periodType by remember { mutableStateOf(PeriodType.MONTH) }
    var periodExpanded by remember { mutableStateOf(false) }
    var multiplierText by remember { mutableStateOf("1") }
    var weekendAdjust by remember { mutableStateOf(WeekendAdjust.NONE) }
    var weekendExpanded by remember { mutableStateOf(false) }
    var periodCountText by remember { mutableStateOf("12") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New budget") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    start,
                    { start = it },
                    label = { Text("First period starts (YYYY-MM-DD)") },
                    singleLine = true,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { periodExpanded = true }) {
                        Text("Each period is: ${BUDGET_PERIOD_TYPE_LABELS.getValue(periodType)}")
                    }
                    DropdownMenu(expanded = periodExpanded, onDismissRequest = { periodExpanded = false }) {
                        // Unlike a scheduled transaction's one-shot option, a budget
                        // recurring "once" isn't a realistic choice -- desktop
                        // GnuCash's own budget editor doesn't offer it either.
                        for (candidate in PeriodType.entries.filter { it != PeriodType.ONCE }) {
                            DropdownMenuItem(
                                text = { Text(BUDGET_PERIOD_TYPE_LABELS.getValue(candidate)) },
                                onClick = { periodType = candidate; periodExpanded = false },
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        multiplierText,
                        { multiplierText = it },
                        label = { Text("Every N") },
                        singleLine = true,
                        modifier = Modifier.width(96.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("If a period boundary falls on a weekend:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { weekendExpanded = true }) { Text(weekendAdjust.name.lowercase()) }
                    DropdownMenu(expanded = weekendExpanded, onDismissRequest = { weekendExpanded = false }) {
                        for (candidate in WeekendAdjust.entries) {
                            DropdownMenuItem(
                                text = { Text(candidate.name.lowercase()) },
                                onClick = { weekendAdjust = candidate; weekendExpanded = false },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    periodCountText,
                    { periodCountText = it },
                    label = { Text("Number of periods") },
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val multiplier = multiplierText.toIntOrNull() ?: 1
                val periodCount = periodCountText.toIntOrNull() ?: 0
                when {
                    name.isBlank() -> error = "Enter a name."
                    !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(start) -> error = "Use YYYY-MM-DD for the start date."
                    multiplier < 1 -> error = "Enter a whole number of 1 or more for \"Every N\"."
                    periodCount < 1 -> error = "Enter a whole number of periods, 1 or more."
                    else -> onCreate(
                        name.trim(),
                        description.trim(),
                        periodCount,
                        Recurrence(periodType, multiplier, start, weekendAdjust),
                    )
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditBudgetDialog(
    budget: XmlBudget,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, periodCount: Int) -> Unit,
) {
    var name by remember { mutableStateOf(budget.name) }
    var description by remember { mutableStateOf(budget.description) }
    var periodCountText by remember { mutableStateOf(budget.periodCount.toString()) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit budget") },
        text = {
            Column {
                Text(
                    "${describeBudgetRecurrence(budget.recurrence)}, starting ${budget.recurrence.start}. " +
                        "The recurrence can't be changed here -- delete this budget and create a new one instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    periodCountText,
                    { periodCountText = it },
                    label = { Text("Number of periods") },
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val periodCount = periodCountText.toIntOrNull() ?: 0
                when {
                    name.isBlank() -> error = "Enter a name."
                    periodCount < 1 -> error = "Enter a whole number of periods, 1 or more."
                    else -> onSave(name.trim(), description.trim(), periodCount)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun parseBudgetCents(text: String): Long? {
    val pieces = text.trim().split('.')
    if (pieces.size > 2) return null
    val whole = pieces[0].toLongOrNull() ?: return null
    val fraction = pieces.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
    if (pieces.size == 2 && pieces[1].length > 2) return null
    return whole * 100 + fraction
}
