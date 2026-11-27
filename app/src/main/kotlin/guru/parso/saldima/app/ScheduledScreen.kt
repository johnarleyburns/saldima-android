// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.PeriodType
import guru.parso.saldima.core.Recurrence
import guru.parso.saldima.core.ScheduledEnd
import guru.parso.saldima.core.SnapshotAccount
import guru.parso.saldima.core.WeekendAdjust
import guru.parso.saldima.core.XmlScheduledTransaction
import guru.parso.saldima.core.nextInstance
import java.time.LocalDate

/** Recurring bills, invoices and transfers, like desktop GnuCash's Actions -> Scheduled Transactions. */
@Composable
fun ScheduledScreen(repository: BookRepository) {
    LaunchedEffect(Unit) { repository.loadExisting() }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<XmlScheduledTransaction?>(null) }
    val snapshot = repository.snapshot

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Scheduled", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Recurring bills, invoices and transfers. Due instances are checked whenever you open the register.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        if (snapshot == null) {
            Text(repository.error ?: "Open a book from Accounts first.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        repository.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = { creating = true }) { Text("New scheduled transaction") }
        Spacer(Modifier.height(10.dp))

        val schedules = repository.scheduledTransactions
        if (schedules.isEmpty()) {
            Text("No scheduled transactions yet.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(schedules.size) { index ->
                val sx = schedules[index]
                val nextDue = runCatching { nextInstance(sx.recurrence, sx.lastOccurred ?: "0001-01-01") }.getOrNull()
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sx.name, fontWeight = FontWeight.SemiBold)
                            if (!sx.enabled) {
                                Spacer(Modifier.width(8.dp))
                                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                                    Text(
                                        "Disabled",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            }
                        }
                        Text(
                            "${describeRecurrence(sx.recurrence)} · next due ${nextDue ?: "never again"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { editing = sx }) { Text("Edit") }
                            TextButton(onClick = { repository.deleteScheduledTransaction(sx.guid) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }

    if (creating && snapshot != null) {
        NewScheduledTransactionDialog(
            accounts = snapshot.accounts,
            onDismiss = { creating = false },
            onCreate = { name, description, start, recurrence, end, autoCreate, autoCreateNotify, advanceCreateDays, advanceRemindDays, splits ->
                repository.createScheduledTransaction(
                    name, description, start, recurrence, end,
                    autoCreate, autoCreateNotify, advanceCreateDays, advanceRemindDays, splits,
                )
                creating = false
            },
        )
    }

    editing?.let { sx ->
        EditScheduledTransactionDialog(
            scheduledTransaction = sx,
            onDismiss = { editing = null },
            onSave = { name, enabled, autoCreate, autoCreateNotify, advanceCreateDays, advanceRemindDays ->
                repository.updateScheduledTransaction(
                    sx.guid, name, enabled, autoCreate, autoCreateNotify, advanceCreateDays, advanceRemindDays,
                )
                editing = null
            },
        )
    }
}

private val PERIOD_TYPE_LABELS = mapOf(
    PeriodType.ONCE to "once",
    PeriodType.DAY to "day",
    PeriodType.WEEK to "week",
    PeriodType.MONTH to "month",
    PeriodType.END_OF_MONTH to "month, on the last day",
    PeriodType.NTH_WEEKDAY to "month, same weekday",
    PeriodType.LAST_WEEKDAY to "month, last weekday",
    PeriodType.YEAR to "year",
)

private fun describeRecurrence(recurrence: Recurrence): String {
    val label = PERIOD_TYPE_LABELS.getValue(recurrence.periodType)
    if (recurrence.periodType == PeriodType.ONCE) return "Once"
    return if (recurrence.multiplier > 1) "Every ${recurrence.multiplier} ${label}s" else "Every $label"
}

/** Two splits cover a simple payment; up to four more are optional for entries with a tax or fee line. */
private const val SPLIT_ROWS = 6

@Composable
private fun NewScheduledTransactionDialog(
    accounts: List<SnapshotAccount>,
    onDismiss: () -> Unit,
    onCreate: (
        name: String,
        description: String,
        start: String,
        recurrence: Recurrence,
        end: ScheduledEnd?,
        autoCreate: Boolean,
        autoCreateNotify: Boolean,
        advanceCreateDays: Int,
        advanceRemindDays: Int,
        templateSplits: List<Pair<Guid, Money>>,
    ) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var start by remember { mutableStateOf(LocalDate.now().toString()) }
    var periodType by remember { mutableStateOf(PeriodType.MONTH) }
    var periodExpanded by remember { mutableStateOf(false) }
    var multiplierText by remember { mutableStateOf("1") }
    var weekendAdjust by remember { mutableStateOf(WeekendAdjust.NONE) }
    var weekendExpanded by remember { mutableStateOf(false) }
    var endType by remember { mutableStateOf("never") }
    var endExpanded by remember { mutableStateOf(false) }
    var endOccurrencesText by remember { mutableStateOf("") }
    var endDateText by remember { mutableStateOf("") }
    var autoCreate by remember { mutableStateOf(false) }
    var autoCreateNotify by remember { mutableStateOf(false) }
    var advanceCreateDaysText by remember { mutableStateOf("0") }
    var advanceRemindDaysText by remember { mutableStateOf("0") }
    val splitAccounts = remember { (0 until SPLIT_ROWS).map { null as Guid? }.toMutableStateList() }
    val splitAmounts = remember { (0 until SPLIT_ROWS).map { "" }.toMutableStateList() }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New scheduled transaction") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(
                    description,
                    { description = it },
                    label = { Text("Description (used on every posted transaction)") },
                    singleLine = true,
                )
                OutlinedTextField(start, { start = it }, label = { Text("First due date (YYYY-MM-DD)") }, singleLine = true)

                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { periodExpanded = true }) { Text("Recurs: ${PERIOD_TYPE_LABELS.getValue(periodType)}") }
                    DropdownMenu(expanded = periodExpanded, onDismissRequest = { periodExpanded = false }) {
                        for (candidate in PeriodType.entries) {
                            DropdownMenuItem(
                                text = { Text(PERIOD_TYPE_LABELS.getValue(candidate)) },
                                onClick = { periodType = candidate; periodExpanded = false },
                            )
                        }
                    }
                    if (periodType != PeriodType.ONCE) {
                        Spacer(Modifier.width(8.dp))
                        OutlinedTextField(
                            multiplierText,
                            { multiplierText = it },
                            label = { Text("Every N") },
                            singleLine = true,
                            modifier = Modifier.width(96.dp),
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("If it falls on a weekend:", style = MaterialTheme.typography.bodySmall)
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

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ends:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { endExpanded = true }) { Text(endType) }
                    DropdownMenu(expanded = endExpanded, onDismissRequest = { endExpanded = false }) {
                        for (candidate in listOf("never", "occurrences", "date")) {
                            DropdownMenuItem(text = { Text(candidate) }, onClick = { endType = candidate; endExpanded = false })
                        }
                    }
                }
                when (endType) {
                    "occurrences" -> OutlinedTextField(
                        endOccurrencesText,
                        { endOccurrencesText = it },
                        label = { Text("Number of times") },
                        singleLine = true,
                    )
                    "date" -> OutlinedTextField(
                        endDateText,
                        { endDateText = it },
                        label = { Text("End date (YYYY-MM-DD)") },
                        singleLine = true,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoCreate, onCheckedChange = { autoCreate = it })
                    Text("Post automatically when due", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoCreateNotify, onCheckedChange = { autoCreateNotify = it })
                    Text("Still show it for review when posted automatically", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    advanceCreateDaysText,
                    { advanceCreateDaysText = it },
                    label = { Text("Create this many days before it's due") },
                    singleLine = true,
                )
                OutlinedTextField(
                    advanceRemindDaysText,
                    { advanceRemindDaysText = it },
                    label = { Text("Show as a reminder this many days before that") },
                    singleLine = true,
                )

                Text(
                    "Each split takes a signed amount (debit positive, credit negative); the splits used must sum to zero.",
                    style = MaterialTheme.typography.bodySmall,
                )
                for (index in 0 until SPLIT_ROWS) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ScheduledAccountPicker(
                            if (index < 2) "Account" else "Account (optional)",
                            accounts,
                            splitAccounts[index],
                        ) { splitAccounts[index] = it }
                        Spacer(Modifier.weight(1f))
                        OutlinedTextField(
                            splitAmounts[index],
                            { splitAmounts[index] = it },
                            label = { Text("Amount") },
                            singleLine = true,
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val multiplier = multiplierText.toIntOrNull() ?: 1
                val advanceCreateDays = advanceCreateDaysText.toIntOrNull() ?: 0
                val advanceRemindDays = advanceRemindDaysText.toIntOrNull() ?: 0
                val end = when (endType) {
                    "occurrences" -> endOccurrencesText.toIntOrNull()?.let { ScheduledEnd.Occurrences(it) }
                    "date" -> endDateText.ifBlank { null }?.let { ScheduledEnd.OnDate(it) }
                    else -> null
                }
                val splits = (0 until SPLIT_ROWS).mapNotNull { index ->
                    val account = splitAccounts[index]
                    val text = splitAmounts[index]
                    if (account == null && text.isBlank()) return@mapNotNull null
                    val cents = parseScheduledCents(text)
                    if (account == null || cents == null || cents == 0L) {
                        error = "Fill in every split used: an account and a non-zero amount."
                        return@TextButton
                    }
                    account to Money(cents, 100)
                }
                when {
                    name.isBlank() -> error = "Enter a name."
                    description.isBlank() -> error = "Enter a description."
                    !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(start) -> error = "Use YYYY-MM-DD for the first due date."
                    multiplier < 1 -> error = "Enter a whole number of 1 or more for \"Every N\"."
                    splits.size < 2 -> error = "Use at least two splits."
                    else -> {
                        onCreate(
                            name.trim(),
                            description.trim(),
                            start,
                            Recurrence(periodType, multiplier, start, weekendAdjust),
                            end,
                            autoCreate,
                            autoCreateNotify,
                            advanceCreateDays,
                            advanceRemindDays,
                            splits,
                        )
                    }
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditScheduledTransactionDialog(
    scheduledTransaction: XmlScheduledTransaction,
    onDismiss: () -> Unit,
    onSave: (
        name: String,
        enabled: Boolean,
        autoCreate: Boolean,
        autoCreateNotify: Boolean,
        advanceCreateDays: Int,
        advanceRemindDays: Int,
    ) -> Unit,
) {
    var name by remember { mutableStateOf(scheduledTransaction.name) }
    var enabled by remember { mutableStateOf(scheduledTransaction.enabled) }
    var autoCreate by remember { mutableStateOf(scheduledTransaction.autoCreate) }
    var autoCreateNotify by remember { mutableStateOf(scheduledTransaction.autoCreateNotify) }
    var advanceCreateDaysText by remember { mutableStateOf(scheduledTransaction.advanceCreateDays.toString()) }
    var advanceRemindDaysText by remember { mutableStateOf(scheduledTransaction.advanceRemindDays.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit scheduled transaction") },
        text = {
            Column {
                Text(
                    "${describeRecurrence(scheduledTransaction.recurrence)}, starting ${scheduledTransaction.start}. " +
                        "The recurrence and splits can't be changed here -- delete this schedule and create a new one instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enabled, onCheckedChange = { enabled = it })
                    Text("Enabled", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoCreate, onCheckedChange = { autoCreate = it })
                    Text("Post automatically when due", style = MaterialTheme.typography.bodySmall)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = autoCreateNotify, onCheckedChange = { autoCreateNotify = it })
                    Text("Still show it for review when posted automatically", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(
                    advanceCreateDaysText,
                    { advanceCreateDaysText = it },
                    label = { Text("Create this many days before it's due") },
                    singleLine = true,
                )
                OutlinedTextField(
                    advanceRemindDaysText,
                    { advanceRemindDaysText = it },
                    label = { Text("Show as a reminder this many days before that") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        name.trim(),
                        enabled,
                        autoCreate,
                        autoCreateNotify,
                        advanceCreateDaysText.toIntOrNull() ?: 0,
                        advanceRemindDaysText.toIntOrNull() ?: 0,
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScheduledAccountPicker(
    label: String,
    accounts: List<SnapshotAccount>,
    selected: Guid?,
    onSelect: (Guid) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val name = accounts.firstOrNull { it.guid == selected }?.name ?: "Choose"
    Column {
        TextButton(onClick = { open = true }) { Text("$label: $name") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (account in accounts.filter { it.type != "ROOT" }) {
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        onSelect(account.guid)
                        open = false
                    },
                )
            }
        }
    }
}

private fun parseScheduledCents(text: String): Long? {
    val pieces = text.trim().split('.')
    if (pieces.size > 2) return null
    val whole = pieces[0].toLongOrNull() ?: return null
    val fraction = pieces.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
    if (pieces.size == 2 && pieces[1].length > 2) return null
    return whole * 100 + fraction
}
