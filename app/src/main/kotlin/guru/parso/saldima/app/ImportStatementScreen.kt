// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.ExistingEntry
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.ImportFormatException
import guru.parso.saldima.core.ImportMapping
import guru.parso.saldima.core.ImportResult
import guru.parso.saldima.core.ImportRow
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.SnapshotAccount
import guru.parso.saldima.core.categoryKey
import guru.parso.saldima.core.isLikelyDuplicate
import guru.parso.saldima.core.parseImportFile
import guru.parso.saldima.core.parsePdfStatementText
import guru.parso.saldima.core.planImport

/**
 * Statement importer (issue #56): pick a QIF/OFX/CSV/PDF file, parse it,
 * map categories to accounts, flag possible duplicates, then post one
 * balanced transaction per included row via the ported `planImport`. This
 * mirrors the web track's `importTransactionsPage` -> `uploadTransactions`
 * -> `importMappingPage` -> `commitTransactions` flow conceptually, adapted
 * to this app's existing Compose idiom (one screen, not four routes), plus
 * a per-row exclude checkbox on every row (not just duplicates) since PDF
 * parsing confidence is lower than a real QIF/OFX/CSV file.
 */

private fun importCategories(rows: List<ImportRow>): List<String> {
    val keys = sortedSetOf<String>()
    for (row in rows) {
        val key = categoryKey(row.category)
        if (key.isNotEmpty()) keys.add(key)
        for (split in row.splits) {
            val splitKey = categoryKey(split.category)
            if (splitKey.isNotEmpty()) keys.add(splitKey)
        }
    }
    return keys.toList()
}

@Composable
fun ImportStatementScreen(
    repository: BookRepository,
    targetAccount: SnapshotAccount,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clock = remember { SystemClock() }
    var parsed by remember { mutableStateOf<ImportResult?>(null) }
    var parseError by remember { mutableStateOf<String?>(null) }
    var excluded by remember { mutableStateOf<List<Boolean>>(emptyList()) }
    var includeAnyway by remember { mutableStateOf<List<Boolean>>(emptyList()) }
    var categorySelections by remember { mutableStateOf<Map<String, Guid?>>(emptyMap()) }
    var defaultAccountGuid by remember { mutableStateOf<Guid?>(null) }
    var commitError by remember { mutableStateOf<String?>(null) }

    val snapshot = repository.snapshot
    val eligibleAccounts = remember(snapshot, targetAccount.guid) {
        (snapshot?.accounts ?: emptyList())
            .filter { it.guid != targetAccount.guid && !it.placeholder && !it.hidden }
            .sortedBy { it.name.lowercase() }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        parseError = null
        commitError = null
        try {
            val fileName = (uri.lastPathSegment ?: "statement").substringAfterLast('/')
            val isPdf = fileName.lowercase().endsWith(".pdf") ||
                context.contentResolver.getType(uri) == "application/pdf"
            val result = if (isPdf) {
                val text = extractPdfText(context, uri)
                parsePdfStatementText(text, clock.todayIso().take(4).toInt())
            } else {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    ?: throw ImportFormatException("Could not read that file.")
                parseImportFile(fileName, text)
            }
            parsed = result
            excluded = List(result.rows.size) { false }
            includeAnyway = List(result.rows.size) { false }
            val byName = eligibleAccounts.associateBy { it.name.trim().lowercase() }
            categorySelections = importCategories(result.rows).associateWith { byName[it.lowercase()]?.guid }
            defaultAccountGuid = null
        } catch (failure: ImportFormatException) {
            parsed = null
            parseError = failure.message
        } catch (failure: Exception) {
            parsed = null
            parseError = "That file could not be read: ${failure.message}"
        }
    }

    val history = remember(snapshot, targetAccount.guid) {
        (snapshot?.transactions ?: emptyList()).flatMap { transaction ->
            transaction.splits
                .filter { it.accountGuid == targetAccount.guid }
                .map { split -> ExistingEntry(transaction.date.take(10), split.value) }
        }
    }

    val rows = parsed?.rows ?: emptyList()
    val duplicateFlags = remember(rows, history) { rows.map { isLikelyDuplicate(it, history) } }
    val categories = remember(rows) { importCategories(rows) }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Import statement", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        Text("Into: ${targetAccount.name}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))

        val currentResult = parsed
        if (currentResult == null) {
            Text(
                "Choose a QIF, OFX, CSV or PDF statement file. Nothing is posted until you map categories and confirm below.",
                style = MaterialTheme.typography.bodyMedium,
            )
            parseError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(arrayOf("*/*")) }) { Text("Choose statement file") }
            return
        }

        Text(
            "${currentResult.source} statement · ${rows.size} transaction${if (rows.size == 1) "" else "s"}",
            style = MaterialTheme.typography.bodyMedium,
        )
        for (warning in currentResult.warnings) {
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(12.dp))

        if (categories.isNotEmpty()) {
            Text("Categories", style = MaterialTheme.typography.titleMedium)
            for (category in categories) {
                CategoryAccountPicker(
                    category = category,
                    eligibleAccounts = eligibleAccounts,
                    selected = categorySelections[category],
                    onSelect = { guid -> categorySelections = categorySelections + (category to guid) },
                )
            }
            Spacer(Modifier.height(12.dp))
        } else {
            Text("This file has no categories; every row uses the default account below.", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
        }

        Text("Default account", style = MaterialTheme.typography.titleMedium)
        Text(
            "Used for rows without a mapped category, and to balance any remainder.",
            style = MaterialTheme.typography.bodySmall,
        )
        AccountPicker(
            label = eligibleAccounts.firstOrNull { it.guid == defaultAccountGuid }?.name ?: "Choose an account",
            eligibleAccounts = eligibleAccounts,
            onSelect = { defaultAccountGuid = it },
        )
        Spacer(Modifier.height(16.dp))

        Text("Rows", style = MaterialTheme.typography.titleMedium)
        for ((index, row) in rows.withIndex()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val description = row.payee.ifBlank { row.memo.ifBlank { "Imported transaction" } }
                    Text("${row.date} · $description")
                    if (duplicateFlags[index]) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeAnyway.getOrElse(index) { false },
                                onCheckedChange = { checked ->
                                    includeAnyway = includeAnyway.toMutableList().also { it[index] = checked }
                                },
                            )
                            Text("Possible duplicate — include anyway", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text(row.amount.formatted(100))
                Spacer(Modifier.width(12.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Exclude", style = MaterialTheme.typography.labelSmall)
                    Checkbox(
                        checked = excluded.getOrElse(index) { false },
                        onCheckedChange = { checked ->
                            excluded = excluded.toMutableList().also { it[index] = checked }
                        },
                    )
                }
            }
            HorizontalDivider()
        }

        commitError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val chosenDefault = defaultAccountGuid
            Button(
                enabled = chosenDefault != null,
                onClick = {
                    if (chosenDefault == null) return@Button
                    val mapping = ImportMapping(
                        targetAccountGuid = targetAccount.guid,
                        defaultAccountGuid = chosenDefault,
                        categoryAccounts = categorySelections.entries.mapNotNull { (key, guid) ->
                            guid?.let { key to it }
                        }.toMap(),
                    )
                    val included = rows.filterIndexed { index, _ ->
                        val isDuplicate = duplicateFlags.getOrElse(index) { false }
                        val keptAsDuplicate = !isDuplicate || includeAnyway.getOrElse(index) { false }
                        keptAsDuplicate && !excluded.getOrElse(index) { false }
                    }
                    if (included.isEmpty()) {
                        commitError = "Every row is excluded or flagged as a duplicate."
                        return@Button
                    }
                    val planned = planImport(included, mapping)
                    for (transaction in planned) {
                        val total = transaction.splits.fold(Money.ZERO) { sum, split -> Money.add(sum, split.value) }
                        if (transaction.splits.size < 2 || total.num != 0L) {
                            commitError = "That statement could not be balanced."
                            return@Button
                        }
                    }
                    for (transaction in planned) {
                        repository.post(
                            transaction.date,
                            transaction.description,
                            transaction.splits.map { it.accountGuid to it.value },
                        )
                    }
                    commitError = null
                    onDone()
                },
            ) { Text("Import ${rows.size} transaction${if (rows.size == 1) "" else "s"}") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun AccountPicker(label: String, eligibleAccounts: List<SnapshotAccount>, onSelect: (Guid) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Button(onClick = { expanded = true }) { Text(label) }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        for (account in eligibleAccounts) {
            DropdownMenuItem(
                text = { Text(account.name) },
                onClick = {
                    onSelect(account.guid)
                    expanded = false
                },
            )
        }
    }
}

@Composable
private fun CategoryAccountPicker(
    category: String,
    eligibleAccounts: List<SnapshotAccount>,
    selected: Guid?,
    onSelect: (Guid?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = eligibleAccounts.firstOrNull { it.guid == selected }?.name ?: "(use default account)"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(category, Modifier.weight(1f))
        Button(onClick = { expanded = true }) { Text(selectedName) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("(use default account)") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            for (account in eligibleAccounts) {
                DropdownMenuItem(
                    text = { Text(account.name) },
                    onClick = {
                        onSelect(account.guid)
                        expanded = false
                    },
                )
            }
        }
    }
}
