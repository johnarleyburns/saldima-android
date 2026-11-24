// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
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
    Tab("Receipts", "▧"),
    Tab("Reports", "◫"),
    Tab("Settings", "⚙"),
)

@Composable
private fun SaldimaApp() {
    var selected by rememberSaveable { mutableIntStateOf(0) }
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
            0 -> Placeholder("Accounts", "Open or create a .gnucash book to see its account tree.", padding)
            1 -> Placeholder("Register", "Transactions appear once a book is open.", padding)
            2 -> Placeholder("Receipts", "Receipts captured on this phone wait here.", padding)
            3 -> Placeholder("Reports", "Balance sheet and profit and loss appear once a book is open.", padding)
            else -> SettingsScreen(padding)
        }
    }
}

@Composable
private fun Placeholder(title: String, message: String, padding: androidx.compose.foundation.layout.PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SettingsScreen(padding: androidx.compose.foundation.layout.PaddingValues) {
    val activity = LocalContext.current as android.app.Activity
    val store = remember { SupporterStore(activity) }
    var supporter by remember { mutableStateOf(store.isSupporter) }

    Column(Modifier.fillMaxSize().padding(padding).padding(24.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
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
    }
}
