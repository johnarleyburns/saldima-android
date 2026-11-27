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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.SnapshotAccount
import guru.parso.saldima.core.VendorDraft
import guru.parso.saldima.core.XmlBill
import guru.parso.saldima.core.XmlVendor
import java.time.LocalDate

/**
 * Vendors and bills: GnuCash's `GncVendor`/vendor-owned `GncInvoice`
 * business objects -- accounts payable, the mirror image of the
 * customers/invoices (accounts receivable) screen.
 */
@Composable
fun BillsScreen(repository: BookRepository) {
    LaunchedEffect(Unit) { repository.loadExisting() }
    val snapshot = repository.snapshot
    var managingVendors by remember { mutableStateOf(false) }
    var creatingBill by remember { mutableStateOf(false) }
    var selectedBill by remember { mutableStateOf<Guid?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (snapshot == null) {
            Text("Bills", style = MaterialTheme.typography.headlineMedium)
            Text(repository.error ?: "Open a book from Accounts first.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        val detail = selectedBill?.let { guid -> repository.bills.firstOrNull { it.guid == guid } }
        if (detail != null) {
            BillDetail(repository, detail, snapshot.accounts, onBack = { selectedBill = null })
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Bills", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { managingVendors = true }) { Text("Vendors") }
        }
        Spacer(Modifier.height(8.dp))
        repository.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { creatingBill = true }, enabled = repository.vendors.isNotEmpty()) {
            Text("New bill")
        }
        if (repository.vendors.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Add a vendor before creating a bill.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(10.dp))

        if (repository.bills.isEmpty()) {
            Text("No bills yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val vendorNames = repository.vendors.associate { it.guid to it.name }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(repository.bills.size) { index ->
                    val bill = repository.bills[index]
                    val total = repository.billEntries(bill.guid)
                        .fold(Money.ZERO) { sum, entry -> Money.add(sum, entry.amount) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { selectedBill = bill.guid }) {
                                    Text(bill.billNumber.ifBlank { "Bill" }, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.weight(1f))
                                Text(if (bill.isPosted) "Posted" else "Draft", style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                "${vendorNames[bill.vendorGuid] ?: "Unknown vendor"} · ${total.formatted(100)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    if (managingVendors) {
        VendorsDialog(repository, onDismiss = { managingVendors = false })
    }
    if (creatingBill && snapshot != null) {
        NewBillDialog(
            vendors = repository.vendors,
            onDismiss = { creatingBill = false },
            onCreate = { vendor, number, dateOpened ->
                val guid = repository.createBillDraft(vendor, number, dateOpened)
                creatingBill = false
                if (guid != null) selectedBill = guid
            },
        )
    }
}

@Composable
private fun VendorsDialog(repository: BookRepository, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<XmlVendor?>(null) }
    var adding by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Vendors") },
        text = {
            Column {
                Button(onClick = { adding = true }) { Text("Add vendor") }
                Spacer(Modifier.height(8.dp))
                if (repository.vendors.isEmpty()) {
                    Text("No vendors yet.", style = MaterialTheme.typography.bodySmall)
                }
                for (vendor in repository.vendors) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { editing = vendor }) { Text(vendor.name) }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    if (adding) {
        VendorEditor(
            vendor = null,
            onDismiss = { adding = false },
            onSave = { draft -> repository.createVendor(draft); adding = false },
            onDelete = null,
        )
    }
    editing?.let { vendor ->
        VendorEditor(
            vendor = vendor,
            onDismiss = { editing = null },
            onSave = { draft -> repository.updateVendor(vendor.guid, draft); editing = null },
            onDelete = { repository.deleteVendor(vendor.guid); editing = null },
        )
    }
}

@Composable
private fun VendorEditor(
    vendor: XmlVendor?,
    onDismiss: () -> Unit,
    onSave: (VendorDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(vendor?.name ?: "") }
    var number by remember { mutableStateOf(vendor?.vendorNumber ?: "") }
    var address by remember { mutableStateOf(vendor?.addressLine1 ?: "") }
    var phone by remember { mutableStateOf(vendor?.phone ?: "") }
    var email by remember { mutableStateOf(vendor?.email ?: "") }
    var notes by remember { mutableStateOf(vendor?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (vendor == null) "Add vendor" else "Edit vendor") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(number, { number = it }, label = { Text("Vendor number") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("Address") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
                if (onDelete != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDelete) { Text("Delete vendor") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        VendorDraft(
                            name = name,
                            vendorNumber = number,
                            addressLine1 = address,
                            phone = phone,
                            email = email,
                            notes = notes,
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun NewBillDialog(
    vendors: List<XmlVendor>,
    onDismiss: () -> Unit,
    onCreate: (vendor: Guid, number: String, dateOpened: String) -> Unit,
) {
    var vendor by remember { mutableStateOf(vendors.firstOrNull()) }
    var vendorExpanded by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var dateOpened by remember { mutableStateOf(LocalDate.now().toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New bill") },
        text = {
            Column {
                Button(onClick = { vendorExpanded = true }) { Text("Vendor: ${vendor?.name ?: "Choose"}") }
                DropdownMenu(expanded = vendorExpanded, onDismissRequest = { vendorExpanded = false }) {
                    for (candidate in vendors) {
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = { vendor = candidate; vendorExpanded = false },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(number, { number = it }, label = { Text("Bill number") }, singleLine = true)
                OutlinedTextField(dateOpened, { dateOpened = it }, label = { Text("Date opened") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { vendor?.let { onCreate(it.guid, number, dateOpened) } },
                enabled = vendor != null && number.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun BillDetail(
    repository: BookRepository,
    bill: XmlBill,
    accounts: List<SnapshotAccount>,
    onBack: () -> Unit,
) {
    val entries = repository.billEntries(bill.guid)
    val total = entries.fold(Money.ZERO) { sum, entry -> Money.add(sum, entry.amount) }
    var addingLine by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Bills") }
        }
        Text(bill.billNumber.ifBlank { "Bill" }, style = MaterialTheme.typography.headlineMedium)
        Text(
            if (bill.isPosted) "Posted ${bill.datePosted}" else "Draft · opened ${bill.dateOpened}",
            style = MaterialTheme.typography.bodyMedium,
        )
        repository.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(10.dp))

        val expenseNames = accounts.associate { it.guid to it.name }
        if (entries.isEmpty()) {
            Text("No lines yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(entries.size) { index ->
                    val entry = entries[index]
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.description)
                            Text(
                                expenseNames[entry.expenseAccountGuid] ?: "Account",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(entry.amount.formatted(100))
                        if (!bill.isPosted) {
                            TextButton(onClick = { repository.deleteBillLine(bill.guid, entry.guid) }) {
                                Text("Remove")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Total: ${total.formatted(100)}", fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))

        if (!bill.isPosted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addingLine = true }) { Text("Add line") }
                Button(onClick = { posting = true }, enabled = entries.isNotEmpty()) { Text("Post bill") }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { repository.deleteBillDraft(bill.guid); onBack() }) { Text("Delete draft") }
        }
    }

    if (addingLine) {
        val expenseAccounts = accounts.filter { it.type == "EXPENSE" && !it.placeholder }
        AddBillLineDialog(
            expenseAccounts = expenseAccounts,
            onDismiss = { addingLine = false },
            onAdd = { date, description, quantity, unitPrice, account ->
                repository.addBillLine(bill.guid, date, description, quantity, unitPrice, account)
                addingLine = false
            },
        )
    }
    if (posting) {
        val payableTypes = setOf("ASSET", "BANK", "CASH", "LIABILITY", "CREDIT", "PAYABLE")
        val payableAccounts = accounts.filter { it.type in payableTypes && !it.placeholder }
        PostBillDialog(
            accounts = payableAccounts,
            onDismiss = { posting = false },
            onPost = { account, postedDate ->
                repository.postBill(bill.guid, account, postedDate)
                posting = false
            },
        )
    }
}

@Composable
private fun AddBillLineDialog(
    expenseAccounts: List<SnapshotAccount>,
    onDismiss: () -> Unit,
    onAdd: (date: String, description: String, quantity: Money, unitPrice: Money, account: Guid) -> Unit,
) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var description by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var unitPrice by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(expenseAccounts.firstOrNull()) }
    var accountExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a line") },
        text = {
            Column {
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, singleLine = true)
                OutlinedTextField(date, { date = it }, label = { Text("Date") }, singleLine = true)
                OutlinedTextField(quantity, { quantity = it }, label = { Text("Quantity") }, singleLine = true)
                OutlinedTextField(unitPrice, { unitPrice = it }, label = { Text("Unit price") }, singleLine = true)
                Button(onClick = { accountExpanded = true }) { Text("Expense account: ${account?.name ?: "Choose"}") }
                DropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                    for (candidate in expenseAccounts) {
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = { account = candidate; accountExpanded = false },
                        )
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val quantityMoney = parseBillDecimal(quantity, 10000)
                val priceMoney = parseBillDecimal(unitPrice, 100)
                val chosen = account
                if (description.isBlank() || quantityMoney == null || priceMoney == null || chosen == null) {
                    error = "Fill in every field with a valid expense account."
                    return@TextButton
                }
                onAdd(date, description, quantityMoney, priceMoney, chosen.guid)
            }) { Text("Add line") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PostBillDialog(
    accounts: List<SnapshotAccount>,
    onDismiss: () -> Unit,
    onPost: (account: Guid, postedDate: String) -> Unit,
) {
    var account by remember { mutableStateOf(accounts.firstOrNull()) }
    var accountExpanded by remember { mutableStateOf(false) }
    var postedDate by remember { mutableStateOf(LocalDate.now().toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Post bill") },
        text = {
            Column {
                Text(
                    "Posting debits each line's expense account and credits the account below for the bill total. This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { accountExpanded = true }) { Text("Pay from: ${account?.name ?: "Choose"}") }
                DropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                    for (candidate in accounts) {
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = { account = candidate; accountExpanded = false },
                        )
                    }
                }
                OutlinedTextField(postedDate, { postedDate = it }, label = { Text("Posted date") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { account?.let { onPost(it.guid, postedDate) } }, enabled = account != null) {
                Text("Post bill")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Parses a decimal string like "42.00" into an exact `fraction`-denominator `Money`. */
private fun parseBillDecimal(text: String, fraction: Long): Money? {
    val pieces = text.trim().split('.')
    if (pieces.isEmpty() || pieces.size > 2) return null
    val whole = pieces[0].toLongOrNull() ?: return null
    val decimalDigits = Money.decimalPlaces(fraction)
    val fractionText = pieces.getOrNull(1) ?: ""
    if (fractionText.length > decimalDigits) return null
    val scaledFraction = fractionText.padEnd(decimalDigits, '0').ifEmpty { "0" }.toLongOrNull() ?: return null
    val value = whole * fraction + scaledFraction
    return if (value == 0L) null else Money(value, fraction)
}
