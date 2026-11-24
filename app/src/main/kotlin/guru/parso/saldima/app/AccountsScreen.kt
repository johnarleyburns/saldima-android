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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.AccountType
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
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Accounts", style = MaterialTheme.typography.headlineMedium)
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
        Button(onClick = { launcher.launch(arrayOf("*/*")) }) { Text("Open another book") }
        Spacer(Modifier.height(8.dp))

        val rows = flatten(snapshot.accounts)
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows.size) { index ->
                val row = rows[index]
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { }
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
                }
                androidx.compose.material3.HorizontalDivider()
            }
        }
    }
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
