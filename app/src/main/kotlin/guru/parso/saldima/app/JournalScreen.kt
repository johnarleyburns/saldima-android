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
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.Money

/** Every transaction in the open book, like Tools -> General Journal. */
@Composable
fun JournalScreen(repository: BookRepository) {
    LaunchedEffect(Unit) { repository.loadExisting() }
    val snapshot = repository.snapshot
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Register", style = MaterialTheme.typography.headlineMedium)
        Text("General journal", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        if (snapshot == null) {
            Text(repository.error ?: "Open a book from Accounts first.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
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
}
