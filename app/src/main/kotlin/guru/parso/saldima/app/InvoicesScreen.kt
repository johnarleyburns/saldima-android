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
import guru.parso.saldima.core.CustomerDraft
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.SnapshotAccount
import guru.parso.saldima.core.XmlCustomer
import guru.parso.saldima.core.XmlInvoice
import java.time.LocalDate

/** Clients and invoices: GnuCash's `GncCustomer`/`GncInvoice` business objects. */
@Composable
fun InvoicesScreen(repository: BookRepository) {
    LaunchedEffect(Unit) { repository.loadExisting() }
    val snapshot = repository.snapshot
    var managingClients by remember { mutableStateOf(false) }
    var creatingInvoice by remember { mutableStateOf(false) }
    var selectedInvoice by remember { mutableStateOf<Guid?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (snapshot == null) {
            Text("Invoices", style = MaterialTheme.typography.headlineMedium)
            Text(repository.error ?: "Open a book from Accounts first.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        val detail = selectedInvoice?.let { guid -> repository.invoices.firstOrNull { it.guid == guid } }
        if (detail != null) {
            InvoiceDetail(repository, detail, snapshot.accounts, onBack = { selectedInvoice = null })
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Invoices", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { managingClients = true }) { Text("Clients") }
        }
        Spacer(Modifier.height(8.dp))
        repository.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(onClick = { creatingInvoice = true }, enabled = repository.customers.isNotEmpty()) {
            Text("New invoice")
        }
        if (repository.customers.isEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Add a client before creating an invoice.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(10.dp))

        if (repository.invoices.isEmpty()) {
            Text("No invoices yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            val customerNames = repository.customers.associate { it.guid to it.name }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(repository.invoices.size) { index ->
                    val invoice = repository.invoices[index]
                    val total = repository.invoiceEntries(invoice.guid)
                        .fold(Money.ZERO) { sum, entry -> Money.add(sum, entry.amount) }
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.fillMaxWidth().padding(12.dp)
                                .then(Modifier),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { selectedInvoice = invoice.guid }) {
                                    Text(invoice.invoiceNumber.ifBlank { "Invoice" }, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.weight(1f))
                                Text(if (invoice.isPosted) "Posted" else "Draft", style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                "${customerNames[invoice.customerGuid] ?: "Unknown client"} · ${total.formatted(100)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    if (managingClients) {
        ClientsDialog(repository, onDismiss = { managingClients = false })
    }
    if (creatingInvoice && snapshot != null) {
        NewInvoiceDialog(
            customers = repository.customers,
            onDismiss = { creatingInvoice = false },
            onCreate = { customer, number, dateOpened ->
                val guid = repository.createInvoiceDraft(customer, number, dateOpened)
                creatingInvoice = false
                if (guid != null) selectedInvoice = guid
            },
        )
    }
}

@Composable
private fun ClientsDialog(repository: BookRepository, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<XmlCustomer?>(null) }
    var adding by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clients") },
        text = {
            Column {
                Button(onClick = { adding = true }) { Text("Add client") }
                Spacer(Modifier.height(8.dp))
                if (repository.customers.isEmpty()) {
                    Text("No clients yet.", style = MaterialTheme.typography.bodySmall)
                }
                for (customer in repository.customers) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { editing = customer }) { Text(customer.name) }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    if (adding) {
        CustomerEditor(
            customer = null,
            onDismiss = { adding = false },
            onSave = { draft -> repository.createCustomer(draft); adding = false },
            onDelete = null,
        )
    }
    editing?.let { customer ->
        CustomerEditor(
            customer = customer,
            onDismiss = { editing = null },
            onSave = { draft -> repository.updateCustomer(customer.guid, draft); editing = null },
            onDelete = { repository.deleteCustomer(customer.guid); editing = null },
        )
    }
}

@Composable
private fun CustomerEditor(
    customer: XmlCustomer?,
    onDismiss: () -> Unit,
    onSave: (CustomerDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(customer?.name ?: "") }
    var number by remember { mutableStateOf(customer?.customerNumber ?: "") }
    var address by remember { mutableStateOf(customer?.addressLine1 ?: "") }
    var phone by remember { mutableStateOf(customer?.phone ?: "") }
    var email by remember { mutableStateOf(customer?.email ?: "") }
    var notes by remember { mutableStateOf(customer?.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (customer == null) "Add client" else "Edit client") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(number, { number = it }, label = { Text("Client number") }, singleLine = true)
                OutlinedTextField(address, { address = it }, label = { Text("Address") }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text("Phone") }, singleLine = true)
                OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true)
                OutlinedTextField(notes, { notes = it }, label = { Text("Notes") })
                if (onDelete != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = onDelete) { Text("Delete client") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        CustomerDraft(
                            name = name,
                            customerNumber = number,
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
private fun NewInvoiceDialog(
    customers: List<XmlCustomer>,
    onDismiss: () -> Unit,
    onCreate: (customer: Guid, number: String, dateOpened: String) -> Unit,
) {
    var customer by remember { mutableStateOf(customers.firstOrNull()) }
    var customerExpanded by remember { mutableStateOf(false) }
    var number by remember { mutableStateOf("") }
    var dateOpened by remember { mutableStateOf(LocalDate.now().toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New invoice") },
        text = {
            Column {
                Button(onClick = { customerExpanded = true }) { Text("Client: ${customer?.name ?: "Choose"}") }
                DropdownMenu(expanded = customerExpanded, onDismissRequest = { customerExpanded = false }) {
                    for (candidate in customers) {
                        DropdownMenuItem(
                            text = { Text(candidate.name) },
                            onClick = { customer = candidate; customerExpanded = false },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(number, { number = it }, label = { Text("Invoice number") }, singleLine = true)
                OutlinedTextField(dateOpened, { dateOpened = it }, label = { Text("Date opened") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { customer?.let { onCreate(it.guid, number, dateOpened) } },
                enabled = customer != null && number.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun InvoiceDetail(
    repository: BookRepository,
    invoice: XmlInvoice,
    accounts: List<SnapshotAccount>,
    onBack: () -> Unit,
) {
    val entries = repository.invoiceEntries(invoice.guid)
    val total = entries.fold(Money.ZERO) { sum, entry -> Money.add(sum, entry.amount) }
    var addingLine by remember { mutableStateOf(false) }
    var posting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Invoices") }
        }
        Text(invoice.invoiceNumber.ifBlank { "Invoice" }, style = MaterialTheme.typography.headlineMedium)
        Text(
            if (invoice.isPosted) "Posted ${invoice.datePosted}" else "Draft · opened ${invoice.dateOpened}",
            style = MaterialTheme.typography.bodyMedium,
        )
        repository.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Spacer(Modifier.height(10.dp))

        val incomeNames = accounts.associate { it.guid to it.name }
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
                                incomeNames[entry.incomeAccountGuid] ?: "Account",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(entry.amount.formatted(100))
                        if (!invoice.isPosted) {
                            TextButton(onClick = { repository.deleteInvoiceLine(invoice.guid, entry.guid) }) {
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

        if (!invoice.isPosted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addingLine = true }) { Text("Add line") }
                Button(onClick = { posting = true }, enabled = entries.isNotEmpty()) { Text("Post invoice") }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { repository.deleteInvoiceDraft(invoice.guid); onBack() }) { Text("Delete draft") }
        }
    }

    if (addingLine) {
        val incomeAccounts = accounts.filter { it.type == "INCOME" && !it.placeholder }
        AddInvoiceLineDialog(
            incomeAccounts = incomeAccounts,
            onDismiss = { addingLine = false },
            onAdd = { date, description, quantity, unitPrice, account ->
                repository.addInvoiceLine(invoice.guid, date, description, quantity, unitPrice, account)
                addingLine = false
            },
        )
    }
    if (posting) {
        val receivableTypes = setOf("ASSET", "BANK", "CASH", "RECEIVABLE")
        val receivableAccounts = accounts.filter { it.type in receivableTypes && !it.placeholder }
        PostInvoiceDialog(
            accounts = receivableAccounts,
            onDismiss = { posting = false },
            onPost = { account, postedDate ->
                repository.postInvoice(invoice.guid, account, postedDate)
                posting = false
            },
        )
    }
}

@Composable
private fun AddInvoiceLineDialog(
    incomeAccounts: List<SnapshotAccount>,
    onDismiss: () -> Unit,
    onAdd: (date: String, description: String, quantity: Money, unitPrice: Money, account: Guid) -> Unit,
) {
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var description by remember { mutableStateOf("") }
    var quantity by remember { mutableStateOf("1") }
    var unitPrice by remember { mutableStateOf("") }
    var account by remember { mutableStateOf(incomeAccounts.firstOrNull()) }
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
                Button(onClick = { accountExpanded = true }) { Text("Income account: ${account?.name ?: "Choose"}") }
                DropdownMenu(expanded = accountExpanded, onDismissRequest = { accountExpanded = false }) {
                    for (candidate in incomeAccounts) {
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
                val quantityMoney = parseDecimal(quantity, 10000)
                val priceMoney = parseDecimal(unitPrice, 100)
                val chosen = account
                if (description.isBlank() || quantityMoney == null || priceMoney == null || chosen == null) {
                    error = "Fill in every field with a valid income account."
                    return@TextButton
                }
                onAdd(date, description, quantityMoney, priceMoney, chosen.guid)
            }) { Text("Add line") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PostInvoiceDialog(
    accounts: List<SnapshotAccount>,
    onDismiss: () -> Unit,
    onPost: (account: Guid, postedDate: String) -> Unit,
) {
    var account by remember { mutableStateOf(accounts.firstOrNull()) }
    var accountExpanded by remember { mutableStateOf(false) }
    var postedDate by remember { mutableStateOf(LocalDate.now().toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Post invoice") },
        text = {
            Column {
                Text(
                    "Posting debits the account below for the invoice total and credits each line's income account. This cannot be undone.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { accountExpanded = true }) { Text("Deposit to: ${account?.name ?: "Choose"}") }
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
                Text("Post invoice")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Parses a decimal string like "42.00" into an exact `fraction`-denominator `Money`. */
private fun parseDecimal(text: String, fraction: Long): Money? {
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
