// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.Totals

/** Trial balance, balance sheet and P&L, mirroring web and iOS. */
@Composable
fun ReportsScreen(repository: BookRepository) {
    LaunchedEffect(Unit) { repository.loadExisting() }
    val snapshot = repository.snapshot
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text("Reports", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        if (snapshot == null) {
            Text(repository.error ?: "Open a book from Accounts first.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val totals = Totals.totals(snapshot)
        val balances = Totals.balances(snapshot)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Balance sheet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Line("Assets", totals.assets)
                Line("Liabilities", totals.liabilities)
                Line("Equity", Money.add(totals.netAssets, totals.liabilities))
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Profit & loss", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Line("Profit", totals.profit)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Trial balance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                var debits = Money.ZERO
                var credits = Money.ZERO
                for (account in snapshot.accounts) {
                    val balance = balances[account.guid] ?: continue
                    if (balance.num == 0L) continue
                    Line(account.name, balance)
                    if (balance.num > 0) debits = Money.add(debits, balance)
                    else credits = Money.add(credits, balance.negated())
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Line("Debits", debits)
                Line("Credits", credits)
                LineText("Status", if (debits == credits) "Balanced" else "Out of balance")
            }
        }
    }
}

@Composable
private fun Line(label: String, value: Money) {
    LineText(label, value.formatted(100))
}

@Composable
private fun LineText(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
