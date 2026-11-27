// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.SupporterProduct

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SaldimaTheme { SaldimaApp() } }
    }
}

private data class Tab(val label: String, val icon: String)

private val tabs = listOf(
    Tab("Accounts", "▤"),
    Tab("Register", "≡"),
    Tab("Scheduled", "⏰"),
    Tab("Budgets", "📊"),
    Tab("Invoices", "🧾"),
    Tab("Bills", "📦"),
    Tab("Receipts", "▧"),
    Tab("Reports", "◫"),
    Tab("Settings", "⚙"),
)

@Composable
private fun SaldimaApp() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val context = LocalContext.current
    val repository = remember { BookRepository(context.applicationContext) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Text(tab.icon) },
                        label = { Text(tab.label) },
                    )
                }
            }
        }
    ) { padding ->
        when (selected) {
            0 -> AccountsScreen(repository)
            1 -> JournalScreen(repository)
            2 -> ScheduledScreen(repository)
            3 -> BudgetsScreen(repository)
            4 -> InvoicesScreen(repository)
            5 -> BillsScreen(repository)
            6 -> ReceiptsScreen(repository)
            7 -> ReportsScreen(repository)
            else -> SettingsScreen(repository, padding)
        }
    }
}

@Composable
private fun SettingsScreen(
    repository: BookRepository,
    padding: androidx.compose.foundation.layout.PaddingValues,
) {
    val activity = LocalContext.current as android.app.Activity
    val context = LocalContext.current
    val store = remember { SupporterStore(activity) }
    var supporter by remember { mutableStateOf(store.isSupporter) }
    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip")
    ) { uri ->
        if (uri != null) {
            val exported = repository.exportXml(uri)
            val message = if (exported) {
                "Exported GnuCash XML"
            } else {
                repository.error ?: "Export failed"
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState())) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        Text("Book", style = MaterialTheme.typography.titleMedium)
        Text(
            repository.currentName ?: "No book open",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = {
                exporter.launch("${repository.currentName ?: "Saldima"}-xml.gnucash")
            },
            enabled = repository.snapshot != null,
        ) {
            Text("Export as GnuCash XML")
        }
        Text(
            "Gzip XML with the same accounts and postings; GnuCash opens it directly.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(24.dp))
        Text(
            if (supporter) "Thanks for supporting Saldima" else "Become a Supporter",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            "A one-time, optional ${SupporterProduct.DISPLAY_NAME}. Every feature is already free; it only adds a thank-you badge.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                store.purchase(activity)
                supporter = store.isSupporter
            },
            enabled = !supporter,
        ) {
            Text(if (supporter) "Supporter" else "Support Saldima")
        }
        Spacer(Modifier.height(24.dp))
        Text("Saldima is AGPL-3.0-only. No subscription. Compatible with GnuCash.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
        HelpContent()
    }
}
