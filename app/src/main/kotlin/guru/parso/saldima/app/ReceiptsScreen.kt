// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.SnapshotAccount
import java.time.LocalDate

/** Captures a receipt photo and posts the expense it documents (GnuCash's "Associate File/Location"). */
@Composable
fun ReceiptsScreen(repository: BookRepository) {
    LaunchedEffect(Unit) { repository.loadExisting() }
    val snapshot = repository.snapshot
    var capturing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Receipts", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Capture a receipt to post the expense it documents.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(10.dp))

        if (snapshot == null) {
            Text(repository.error ?: "Open a book from Accounts first.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        repository.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { capturing = true }) { Text("Add receipt") }
        Spacer(Modifier.height(10.dp))

        val receipts = snapshot.transactions.filter { it.docLink != null }
        if (receipts.isEmpty()) {
            Text("No receipts yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(receipts.size) { index ->
                    val transaction = receipts[index]
                    val amount = transaction.splits.firstOrNull { it.value.num > 0 }?.value ?: Money.ZERO
                    Card(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            ReceiptThumbnail(repository, transaction.docLink!!)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Text(transaction.description)
                                Text(transaction.date, style = MaterialTheme.typography.bodySmall)
                            }
                            Text(amount.formatted(100))
                        }
                    }
                }
            }
        }
    }

    if (capturing && snapshot != null) {
        val expenseAccounts = snapshot.accounts.filter { it.type == "EXPENSE" && !it.placeholder }
        val paymentTypes = setOf("ASSET", "BANK", "CASH", "CREDIT", "LIABILITY")
        val paymentAccounts = snapshot.accounts.filter { it.type in paymentTypes && !it.placeholder }
        NewReceiptDialog(
            expenseAccounts = expenseAccounts,
            paymentAccounts = paymentAccounts,
            onDismiss = { capturing = false },
            onCapture = { bytes, extension, date, description, amount, expenseAccount, paymentAccount ->
                repository.postReceiptExpense(bytes, extension, date, description, amount, expenseAccount, paymentAccount)
                capturing = false
            },
        )
    }
}

@Composable
private fun ReceiptThumbnail(repository: BookRepository, relativePath: String) {
    val bytes = remember(relativePath) { repository.receiptImage(relativePath) }
    val bitmap = remember(bytes) {
        bytes?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    if (bitmap != null) {
        Image(
            painter = BitmapPainter(bitmap.asImageBitmap()),
            contentDescription = "Receipt",
            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)),
        )
    }
}

@Composable
private fun NewReceiptDialog(
    expenseAccounts: List<SnapshotAccount>,
    paymentAccounts: List<SnapshotAccount>,
    onDismiss: () -> Unit,
    onCapture: (
        bytes: ByteArray,
        extension: String,
        date: String,
        description: String,
        amount: Money,
        expenseAccount: Guid,
        paymentAccount: Guid,
    ) -> Unit,
) {
    val context = LocalContext.current
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var description by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var amount by remember { mutableStateOf("") }
    var expenseAccount by remember { mutableStateOf(expenseAccounts.firstOrNull()) }
    var expenseExpanded by remember { mutableStateOf(false) }
    var paymentAccount by remember { mutableStateOf(paymentAccounts.firstOrNull()) }
    var paymentExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        imageUri = uri
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add receipt") },
        text = {
            Column {
                Button(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text(if (imageUri == null) "Choose receipt photo" else "Photo selected")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, singleLine = true)
                OutlinedTextField(date, { date = it }, label = { Text("Date") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount") }, singleLine = true)
                Button(onClick = { expenseExpanded = true }) {
                    Text("Expense account: ${expenseAccount?.name ?: "Choose"}")
                }
                DropdownMenu(expanded = expenseExpanded, onDismissRequest = { expenseExpanded = false }) {
                    for (candidate in expenseAccounts) {
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = { expenseAccount = candidate; expenseExpanded = false },
                        )
                    }
                }
                Button(onClick = { paymentExpanded = true }) {
                    Text("Paid from: ${paymentAccount?.name ?: "Choose"}")
                }
                DropdownMenu(expanded = paymentExpanded, onDismissRequest = { paymentExpanded = false }) {
                    for (candidate in paymentAccounts) {
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = { paymentAccount = candidate; paymentExpanded = false },
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val uri = imageUri
                val expense = expenseAccount
                val payment = paymentAccount
                val cents = parseCentsOrNull(amount)
                if (uri == null || expense == null || payment == null || description.isBlank() || cents == null || cents <= 0L) {
                    error = "Choose a photo, fill in every field, and enter a positive amount."
                    return@TextButton
                }
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null) {
                    error = "Could not read that photo."
                    return@TextButton
                }
                val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
                val extension = mimeType.substringAfter('/', "jpg").ifBlank { "jpg" }
                onCapture(bytes, extension, date, description, Money(cents, 100), expense.guid, payment.guid)
            }) { Text("Post expense") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun parseCentsOrNull(text: String): Long? {
    val pieces = text.trim().split('.')
    if (pieces.size > 2) return null
    val whole = pieces[0].toLongOrNull() ?: return null
    val fraction = pieces.getOrNull(1)?.padEnd(2, '0')?.take(2)?.toLongOrNull() ?: 0L
    if (pieces.size == 2 && pieces[1].length > 2) return null
    return whole * 100 + fraction
}
