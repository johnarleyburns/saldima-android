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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.Money
import java.time.LocalDate

/** Every transaction in the open book, like Tools -> General Journal. */
@Composable
fun JournalScreen(repository: BookRepository) {
    LaunchedEffect(Unit) { repository.loadExisting() }
    var composing by remember { mutableStateOf(false) }
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
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(snapshot.transactions.size) { index ->
                val transaction = snapshot.transactions[index]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(transaction.description, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text(transaction.date, style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(6.dp))
                        for (split in transaction.splits) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(names[split.accountGuid] ?: "Account", style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.weight(1f))
                                Text(split.value.formatted(100), style = MaterialTheme.typography.bodyMedium)
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
}

@Composable
private fun NewTransactionDialog(
    repository: BookRepository,
    accounts: List<guru.parso.saldima.core.SnapshotAccount>,
    onDismiss: () -> Unit,
) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var description by remember { mutableStateOf("") }
    var from by remember { mutableStateOf<Guid?>(null) }
    var to by remember { mutableStateOf<Guid?>(null) }
    var amount by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New transaction") },
        text = {
            Column {
                OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, singleLine = true)
                AccountPicker("From", accounts, from) { from = it }
                AccountPicker("To", accounts, to) { to = it }
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val source = from
                val target = to
                val cents = parseCents(amount)
                when {
                    source == null || target == null || source == target -> error = "Choose two accounts."
                    description.isBlank() -> error = "Enter a description."
                    !Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date) -> error = "Use YYYY-MM-DD."
                    cents == null || cents == 0L -> error = "Enter an amount like 12.34."
                    else -> {
                        repository.post(date, description.trim(), source, target, Money(cents, 100))
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
