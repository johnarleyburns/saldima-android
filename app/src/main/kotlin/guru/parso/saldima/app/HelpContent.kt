// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val topics = listOf(
    "Getting started" to "Open a .gnucash file from Accounts. The book is copied into this app and never leaves the device.",
    "Accounts" to "The chart of accounts is a tree. Parents show their own balance plus every child, like GnuCash's Total column.",
    "Register and journal" to "Register lists every transaction. New transaction posts a balanced entry; amounts are exact fractions.",
    "Reports" to "Trial balance, balance sheet and profit and loss read the same posted splits as the rest of the app.",
    "GnuCash compatibility" to "SQLite and gzip XML books use the desktop schema. Posting rounds half-up; display rounds half-even.",
    "Supporter" to "The one-time Supporter purchase is optional and never unlocks a feature.",
)

/** Original text, following the same chapter order as the GnuCash manual. */
@Composable
fun HelpContent() {
    Column(Modifier.fillMaxWidth()) {
        Text("Help", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        for ((title, body) in topics) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
        }
    }
}
