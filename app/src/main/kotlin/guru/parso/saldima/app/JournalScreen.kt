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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import guru.parso.saldima.core.matchesJournalSearch
import java.time.LocalDate

/** Every transaction in the open book, like Tools -> General Journal. */
@Composable
fun JournalScreen(repository: BookRepository) {
    LaunchedEffect(Unit) { repository.loadExisting() }
    var composing by remember { mutableStateOf(false) }
    var voidingTransaction by remember { mutableStateOf<guru.parso.saldima.core.SnapshotTransaction?>(null) }
    var searchText by remember { mutableStateOf("") }
    val snapshot = repository.snapshot
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Register", style = MaterialTheme.typography.headlineMedium)
        Text("General journal", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        if (snapshot == null) {
            Text(repository.error ?: "Open a book from Accounts first.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        Button(onClick = { composing = true }) { Text("New transaction") }
        Spacer(Modifier.height(10.dp))
        if (snapshot.transactions.isEmpty()) {
            Text("No transactions yet.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        val names = snapshot.accounts.associate { it.guid to it.name }
        OutlinedTextField(
            searchText,
            { searchText = it },
            label = { Text("Find transactions") },
            placeholder = { Text("Description, memo or account") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        val filtered = snapshot.transactions.filter { matchesJournalSearch(it, searchText, names) }
        if (filtered.isEmpty()) {
            Text("No matching transactions.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(filtered.size) { index ->
                val transaction = filtered[index]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(transaction.description, fontWeight = FontWeight.SemiBold)
                            if (transaction.voided) {
                                Spacer(Modifier.width(8.dp))
                                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                                    Text(
                                        "VOID",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Text(transaction.date, style = MaterialTheme.typography.labelMedium)
                        }
                        transaction.voidReason?.let {
                            Text(
                                "Voided: $it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        for (split in transaction.splits) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(names[split.accountGuid] ?: "Account", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.weight(1f))
                                Text(split.value.formatted(100), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row {
                            Spacer(Modifier.weight(1f))
                            if (transaction.voided) {
                                TextButton(onClick = { repository.unvoidTransaction(transaction.guid) }) {
                                    Text("Unvoid")
                                }
                            } else {
                                TextButton(onClick = { voidingTransaction = transaction }) { Text("Void") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (composing && snapshot != null) {
        NewTransactionDialog(repository, snapshot.accounts, onDismiss = { composing = false })
    }

    voidingTransaction?.let { transaction ->
        VoidTransactionDialog(
            repository = repository,
            transaction = transaction,
            onDismiss = { voidingTransaction = null },
        )
    }
}

@Composable
private fun VoidTransactionDialog(
    repository: BookRepository,
    transaction: guru.parso.saldima.core.SnapshotTransaction,
    onDismiss: () -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Void transaction") },
        text = {
            Column {
                Text(
                    "The transaction stays in the register for audit, but no longer affects any balance.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(reason, { reason = it }, label = { Text("Reason") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                repository.voidTransaction(transaction.guid, reason, LocalDate.now().toString())
                onDismiss()
            }) { Text("Void") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Two splits cover a simple payment; up to four more are optional for entries with a tax or fee line. */
private const val SPLIT_ROWS = 6

@Composable
private fun NewTransactionDialog(
    repository: BookRepository,
    accounts: List<guru.parso.saldima.core.SnapshotAccount>,
    onDismiss: () -> Unit,
) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var description by remember { mutableStateOf("") }
    val splitAccounts = remember { (0 until SPLIT_ROWS).map { null as Guid? }.toMutableStateList() }
    val splitAmounts = remember { (0 until SPLIT_ROWS).map { "" }.toMutableStateList() }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New transaction") },
        text = {
            Column {
                OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, singleLine = true)
                Text(
                    "Each split takes a signed amount (debit positive, credit negative); the splits used must sum to zero.",
                    style = MaterialTheme.typography.bodySmall,
                )
                for (index in 0 until SPLIT_ROWS) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        AccountPicker(
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
                val splits = (0 until SPLIT_ROWS).mapNotNull { index ->
                    val account = splitAccounts[index]
                    val text = splitAmounts[index]
                    if (account == null && text.isBlank()) return@mapNotNull null
                    val cents = parseCents(text)
                    if (account == null || cents == null || cents == 0L) {
                        error = "Fill in every split used: an account and a non-zero amount."
                        return@TextButton
                    }
                    account to Money(cents, 100)
                }
                when {
                    description.isBlank() -> error = "Enter a description."
                    !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date) -> error = "Use YYYY-MM-DD."
                    splits.size < 2 -> error = "Use at least two splits."
                    else -> {
                        repository.post(date, description.trim(), splits)
                        onDismiss()
                    }
                }
            }) { Text("Post") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AccountPicker(
    label: String,
    accounts: List<guru.parso.saldima.core.SnapshotAccount>,
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

private fun parseCents(text: String): Long? {
    val pieces = text.trim().split('.')
    if (pieces.size > 2) return null
    val whole = pieces[0].toLongOrNull() ?: return null
    val fraction = pieces.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
    if (pieces.size == 2 && pieces[1].length > 2) return null
    return whole * 100 + fraction
}
