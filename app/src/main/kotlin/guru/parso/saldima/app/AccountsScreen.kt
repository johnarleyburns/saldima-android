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
    }
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
