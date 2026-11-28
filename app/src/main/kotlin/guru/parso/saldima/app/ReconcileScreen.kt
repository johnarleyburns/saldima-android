// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.SnapshotAccount

/**
 * `AccountsScreen.kt` and `JournalScreen.kt` each already have their own
 * file-private copy of this exact parser; this one stays file-private too
 * rather than widening either (and colliding with the other) just to share
 * six lines across files.
 */
private fun parseStatementBalanceCents(text: String): Long? {
    val pieces = text.trim().split('.')
    if (pieces.size > 2) return null
    val whole = pieces[0].toLongOrNull() ?: return null
    val fraction = pieces.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
    if (pieces.size == 2 && pieces[1].length > 2) return null
    return whole * 100 + fraction
}

/**
 * Top-level "Reconcile" tab (CLAUDE.md issue #56): a plain list of
 * accounts so reconcile is reachable from the bottom tab bar, instead of
 * being buried in each account's row menu on the Accounts screen (which
 * still offers the same "Reconcile" item too, for anyone already there).
 */
@Composable
fun ReconcileScreen(repository: BookRepository) {
    LaunchedEffect(Unit) { repository.loadExisting() }
    val snapshot = repository.snapshot

    var reconciling by remember { mutableStateOf<SnapshotAccount?>(null) }
    var importingStatement by remember { mutableStateOf<SnapshotAccount?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Reconcile", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        if (snapshot == null) {
            Text(
                repository.error ?: "Open a .gnucash file on the Accounts tab first.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }

        val rows = flattenAccounts(snapshot.accounts)
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows.size) { index ->
                val row = rows[index]
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { reconciling = row.account }
                        .padding(start = (row.depth * 16).dp, top = 10.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(row.account.name, style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
            }
        }
    }

    reconciling?.let { account ->
        ReconcileDialog(
            repository,
            account,
            onDismiss = { reconciling = null },
            onImportStatement = {
                importingStatement = account
                reconciling = null
            },
        )
    }
    importingStatement?.let { account ->
        ImportStatementScreen(
            repository = repository,
            targetAccount = account,
            onDone = {
                importingStatement = null
                reconciling = account
            },
            onCancel = {
                importingStatement = null
                reconciling = account
            },
        )
    }
}

/**
 * The same action as desktop GnuCash's Reconcile window: check off every
 * split that appears on the bank statement, then finish once the cleared
 * total matches the statement's ending balance -- distinct from bank
 * integration/auto-feeds, which stays out of scope. `onImportStatement`
 * is the statement importer's entry point (issue #56): bulk-check a whole
 * file's worth of matching splits instead of one at a time.
 */
@Composable
internal fun ReconcileDialog(
    repository: BookRepository,
    account: SnapshotAccount,
    onDismiss: () -> Unit,
    onImportStatement: () -> Unit,
) {
    val snapshot = repository.snapshot
    val clock = remember { SystemClock() }
    var statementDate by remember { mutableStateOf(clock.todayIso()) }
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
                TextButton(onClick = onImportStatement) { Text("Import statement…") }
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
                            Checkbox(
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
                val cents = parseStatementBalanceCents(statementBalance)
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
