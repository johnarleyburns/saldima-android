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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.Totals
import java.time.LocalDate

/**
 * Trial balance, balance sheet and P&L, mirroring web and iOS -- including
 * the same report-period convention: the balance sheet and trial balance
 * are always a snapshot "as of" one date, and the P&L is always a range,
 * never a single-date snapshot.
 */
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

        val today = remember { LocalDate.now() }
        var asOf by remember { mutableStateOf(today.toString()) }
        var periodFrom by remember { mutableStateOf("") }
        var periodTo by remember { mutableStateOf("") }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Period", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    asOf,
                    { asOf = it },
                    label = { Text("Balance sheet & trial balance, as of") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    OutlinedTextField(
                        periodFrom,
                        { periodFrom = it },
                        label = { Text("P&L from") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        periodTo,
                        { periodTo = it },
                        label = { Text("to") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { monthRange(today).let { periodFrom = it.first; periodTo = it.second } }) {
                        Text("This month")
                    }
                    TextButton(onClick = { quarterRange(today).let { periodFrom = it.first; periodTo = it.second } }) {
                        Text("Quarter")
                    }
                    TextButton(onClick = { yearRange(today).let { periodFrom = it.first; periodTo = it.second } }) {
                        Text("Year")
                    }
                    TextButton(onClick = { periodFrom = ""; periodTo = "" }) {
                        Text("All time")
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        val asOfValue = asOf.ifBlank { null }
        val fromValue = periodFrom.ifBlank { null }
        val toValue = periodTo.ifBlank { null }
        val asOfTotals = Totals.totals(snapshot, to = asOfValue)
        val periodTotals = Totals.totals(snapshot, from = fromValue, to = toValue)
        val asOfBalances = Totals.balances(snapshot, to = asOfValue)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Balance sheet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("as of ${asOf.ifBlank { "now" }}", style = MaterialTheme.typography.bodySmall)
                Line("Assets", asOfTotals.assets)
                Line("Liabilities", asOfTotals.liabilities)
                Line("Equity", Money.add(asOfTotals.netAssets, asOfTotals.liabilities))
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Profit & loss", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    if (fromValue == null && toValue == null) "all time" else "${fromValue ?: "the start"} to ${toValue ?: "now"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Line("Profit", periodTotals.profit)
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp)) {
                Text("Trial balance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("as of ${asOf.ifBlank { "now" }}", style = MaterialTheme.typography.bodySmall)
                var debits = Money.ZERO
                var credits = Money.ZERO
                for (account in snapshot.accounts) {
                    val balance = asOfBalances[account.guid] ?: continue
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

private fun monthRange(today: LocalDate): Pair<String, String> {
    val start = today.withDayOfMonth(1)
    val end = today.withDayOfMonth(today.lengthOfMonth())
    return start.toString() to end.toString()
}

private fun quarterRange(today: LocalDate): Pair<String, String> {
    val startMonth = ((today.monthValue - 1) / 3) * 3 + 1
    val start = LocalDate.of(today.year, startMonth, 1)
    val end = start.plusMonths(3).minusDays(1)
    return start.toString() to end.toString()
}

private fun yearRange(today: LocalDate): Pair<String, String> {
    return LocalDate.of(today.year, 1, 1).toString() to LocalDate.of(today.year, 12, 31).toString()
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
