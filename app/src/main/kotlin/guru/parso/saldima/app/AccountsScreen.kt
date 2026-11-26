// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.AccountType
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.SnapshotAccount
import guru.parso.saldima.core.Totals
import java.time.LocalDate

private data class Row(val account: SnapshotAccount, val depth: Int)

@Composable
fun AccountsScreen(repository: BookRepository) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(repository::import)
    }
    LaunchedEffect(Unit) { repository.loadExisting() }

    val snapshot = repository.snapshot
    var switching by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Accounts", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            Button(onClick = { switching = true }) {
                Text(repository.currentName ?: "Books")
            }
            DropdownMenu(expanded = switching, onDismissRequest = { switching = false }) {
                for (file in repository.books) {
                    DropdownMenuItem(
                        text = { Text(file.nameWithoutExtension) },
                        onClick = {
                            repository.open(file)
                            switching = false
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Open another book\u2026") },
                    onClick = {
                        switching = false
                        launcher.launch(arrayOf("*/*"))
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        if (snapshot == null) {
            Text(
                repository.error ?: "Open a .gnucash file to see the chart of accounts.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(arrayOf("*/*")) }) {
                Text("Open a book")
            }
            return@Column
        }

        val totals = repository.totals
        val bookTotals = Totals.totals(snapshot)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard("Net assets", bookTotals.netAssets, Modifier.weight(1f))
            SummaryCard("Profit", bookTotals.profit, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { launcher.launch(arrayOf("*/*")) }) { Text("Open another book") }
            var adding by remember { mutableStateOf(false) }
            Button(onClick = { adding = true }) { Text("Add account") }
            if (adding) {
                AddAccountDialog(
                    accounts = snapshot.accounts,
                    onDismiss = { adding = false },
                    onCreate = { name, type, parent ->
                        repository.createAccount(name, type, parent)
                        adding = false
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        val rows = flatten(snapshot.accounts)
        var menuFor by remember { mutableStateOf<Guid?>(null) }
        var editing by remember { mutableStateOf<SnapshotAccount?>(null) }
        var reconciling by remember { mutableStateOf<SnapshotAccount?>(null) }
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows.size) { index ->
                val row = rows[index]
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { menuFor = row.account.guid }
                        .padding(start = (row.depth * 16).dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.account.name, style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.weight(1f))
                    Text(
                        (totals[row.account.guid] ?: Money.ZERO).formatted(100),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    DropdownMenu(
                        expanded = menuFor == row.account.guid,
                        onDismissRequest = { menuFor = null },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit account") },
                            onClick = {
                                editing = row.account
                                menuFor = null
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete account") },
                            onClick = {
                                repository.deleteAccount(row.account.guid)
                                menuFor = null
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Reconcile") },
                            onClick = {
                                reconciling = row.account
                                menuFor = null
                            },
                        )
                    }
                }
                androidx.compose.material3.HorizontalDivider()
            }
        }
        editing?.let { account ->
            EditAccountDialog(
                account = account,
                onDismiss = { editing = null },
                onSave = { name, code, description ->
                    repository.updateAccount(account.guid, name, code, description)
                    editing = null
                },
            )
        }
        reconciling?.let { account ->
            ReconcileDialog(repository, account, onDismiss = { reconciling = null })
        }
    }
}

/**
 * The same action as desktop GnuCash's Reconcile window: check off every
 * split that appears on the bank statement, then finish once the cleared
 * total matches the statement's ending balance -- distinct from bank
 * integration/auto-feeds, which stays out of scope.
 */
@Composable
private fun ReconcileDialog(repository: BookRepository, account: SnapshotAccount, onDismiss: () -> Unit) {
    val snapshot = repository.snapshot
    var statementDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var statementBalance by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val rows = remember(snapshot) {
        (snapshot?.transactions ?: emptyList()).flatMap { transaction ->
            transaction.splits
                .filter { it.accountGuid == account.guid && it.reconcileState != "y" }
                .mapNotNull { split -> split.guid?.let { Triple(transaction, it, split.value) } }
        }
    }
    val states = remember(snapshot, account.guid) {
        (snapshot?.transactions ?: emptyList()).flatMap { transaction ->
            transaction.splits.filter { it.accountGuid == account.guid }
        }.associateBy { it.guid }
    }
    val cleared = rows.filter { (_, splitGuid, _) -> states[splitGuid]?.reconcileState == "c" }
    val clearedTotal = cleared.fold(Money.ZERO) { sum, (_, _, value) -> Money.add(sum, value) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Reconcile ${account.name}") },
        text = {
            Column {
                Text(
                    "Check off every transaction that appears on your statement. The cleared total must match the statement's ending balance before you can finish.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    statementDate,
                    { statementDate = it },
                    label = { Text("Statement date") },
                    singleLine = true,
                )
                OutlinedTextField(
                    statementBalance,
                    { statementBalance = it },
                    label = { Text("Statement ending balance") },
                    singleLine = true,
                )
                Text("Cleared total so far: ${clearedTotal.formatted(100)}", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                if (rows.isEmpty()) {
                    Text("Nothing left to reconcile.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    for ((transaction, splitGuid, value) in rows) {
                        val checked = states[splitGuid]?.reconcileState == "c"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.material3.Checkbox(
                                checked = checked,
                                onCheckedChange = { isChecked ->
                                    repository.setSplitReconcileState(
                                        transaction.guid,
                                        splitGuid,
                                        if (isChecked) "c" else "n",
                                    )
                                },
                            )
                            Text("${transaction.date} · ${transaction.description}")
                            Spacer(Modifier.weight(1f))
                            Text(value.formatted(100))
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val cents = parseCents(statementBalance)
                if (!Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(statementDate)) {
                    error = "Enter a valid statement date to finish."
                    return@TextButton
                }
                if (cents == null) {
                    error = "Enter amounts like 1234.56 or -1234.56."
                    return@TextButton
                }
                val target = Money(cents, 100)
                if (Money.subtract(clearedTotal, target) != Money.ZERO) {
                    error =
                        "Not balanced yet: cleared total is ${clearedTotal.formatted(100)}, statement balance is ${target.formatted(100)}."
                    return@TextButton
                }
                for ((transaction, splitGuid, _) in cleared) {
                    repository.setSplitReconcileState(transaction.guid, splitGuid, "y", statementDate)
                }
                onDismiss()
            }) { Text("Finish reconciling") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

@Composable
private fun EditAccountDialog(
    account: SnapshotAccount,
    onDismiss: () -> Unit,
    onSave: (name: String, code: String, description: String) -> Unit,
) {
    var name by remember { mutableStateOf(account.name) }
    var code by remember { mutableStateOf(account.code) }
    var description by remember { mutableStateOf(account.description) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit account") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("Code") })
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, code, description) }, enabled = name.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddAccountDialog(
    accounts: List<SnapshotAccount>,
    onDismiss: () -> Unit,
    onCreate: (name: String, type: AccountType, parent: Guid) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var typeExpanded by remember { mutableStateOf(false) }
    var type by remember { mutableStateOf(AccountType.ASSET) }
    var parentExpanded by remember { mutableStateOf(false) }
    var parent by remember { mutableStateOf(accounts.firstOrNull()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add account") },
        text = {
            Column {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Spacer(Modifier.height(8.dp))
                Button(onClick = { typeExpanded = true }) { Text("Type: ${type.name}") }
                DropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                    for (candidate in AccountType.entries) {
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = { type = candidate; typeExpanded = false },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { parentExpanded = true }) {
                    Text("Parent: ${parent?.name ?: "None"}")
                }
                DropdownMenu(expanded = parentExpanded, onDismissRequest = { parentExpanded = false }) {
                    for (candidate in accounts) {
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = { parent = candidate; parentExpanded = false },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { parent?.let { onCreate(name, type, it.guid) } },
                enabled = name.isNotBlank() && parent != null,
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SummaryCard(label: String, value: Money, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.height(76.dp),
        shape = RoundedCornerShape(18.dp),
        color = scheme.primary,
    ) {
        Column(
            Modifier.background(
                Brush.linearGradient(listOf(scheme.primary, scheme.secondary)),
            ).fillMaxSize().padding(12.dp),
        ) {
            Text(label, color = scheme.onPrimary, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(
                value.formatted(100),
                color = scheme.onPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

private fun parseCents(text: String): Long? {
    val pieces = text.trim().split('.')
    if (pieces.size > 2) return null
    val whole = pieces[0].toLongOrNull() ?: return null
    val fraction = pieces.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
    if (pieces.size == 2 && pieces[1].length > 2) return null
    return whole * 100 + fraction
}

private fun flatten(accounts: List<SnapshotAccount>): List<Row> {
    val children = accounts.groupBy { it.parentGuid }
    val rows = mutableListOf<Row>()
    fun walk(account: SnapshotAccount, depth: Int) {
        rows += Row(account, depth)
        children[account.guid].orEmpty().forEach { walk(it, depth + 1) }
    }
    children[null].orEmpty().forEach { walk(it, 0) }
    return rows
}
