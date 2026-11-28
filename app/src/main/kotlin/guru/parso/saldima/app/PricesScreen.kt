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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import guru.parso.saldima.core.CommodityRef
import guru.parso.saldima.core.Guid
import guru.parso.saldima.core.Money
import guru.parso.saldima.core.XmlPrice
import java.time.LocalDate

/**
 * The GnuCash price database (desktop's Price Editor / exchange-rate
 * database): a flat list of commodity/currency pairs, each with a date,
 * value and optional type. Mirrors the web track's `/app/prices`
 * (`apps/web/src/book.ts`). Milestone 1: no Finance::Quote fetching, and no
 * report/transaction-entry conversion UI yet -- see the tracking issue.
 */
@Composable
fun PricesScreen(repository: BookRepository) {
    LaunchedEffect(Unit) { repository.loadExisting() }
    val snapshot = repository.snapshot
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<XmlPrice?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (snapshot == null) {
            Text("Prices", style = MaterialTheme.typography.headlineMedium)
            Text(repository.error ?: "Open a book from Accounts first.", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        Text("Prices", style = MaterialTheme.typography.headlineMedium)
        Text(
            "The exchange-rate database: one value per commodity/currency pair as of a date, " +
                "like desktop GnuCash's Price Editor.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        repository.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        Button(onClick = { creating = true }) { Text("New price") }
        Spacer(Modifier.height(10.dp))

        val prices = repository.prices
        if (prices.isEmpty()) {
            Text("No prices yet.", style = MaterialTheme.typography.bodyMedium)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(prices.size) { index ->
                    val price = prices[index]
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${price.commodity.id} / ${price.currency.id}", fontWeight = FontWeight.SemiBold)
                            Text(
                                "${price.time.take(10)} · ${price.value.num}/${price.value.denom}" +
                                    if (price.type.isNotEmpty()) " · ${price.type}" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row {
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { editing = price }) { Text("Edit") }
                                TextButton(onClick = { repository.deletePrice(price.guid) }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) {
        NewPriceDialog(
            onDismiss = { creating = false },
            onCreate = { commodity, currency, date, type, value ->
                repository.createPrice(commodity, currency, "$date 00:00:00 +0000", value, type)
                creating = false
            },
        )
    }

    editing?.let { price ->
        EditPriceDialog(
            price = price,
            onDismiss = { editing = null },
            onSave = { date, type, value ->
                repository.updatePrice(price.guid, "$date 00:00:00 +0000", type, value)
                editing = null
            },
        )
    }
}

private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

/** A free decimal, not rounded to any commodity's fraction -- the price value field is exact, unlike a posted split amount. */
private fun parsePriceValue(text: String): Money? = try {
    Money.parseExact(text.trim())
} catch (_: IllegalArgumentException) {
    null
}

@Composable
private fun NewPriceDialog(
    onDismiss: () -> Unit,
    onCreate: (commodity: CommodityRef, currency: CommodityRef, date: String, type: String, value: Money) -> Unit,
) {
    var commoditySpace by remember { mutableStateOf("CURRENCY") }
    var commodityId by remember { mutableStateOf("") }
    var currencySpace by remember { mutableStateOf("CURRENCY") }
    var currencyId by remember { mutableStateOf("USD") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var type by remember { mutableStateOf("") }
    var valueText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New price") },
        text = {
            Column {
                Row {
                    OutlinedTextField(
                        commoditySpace,
                        { commoditySpace = it },
                        label = { Text("Commodity space") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        commodityId,
                        { commodityId = it },
                        label = { Text("Commodity id") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row {
                    OutlinedTextField(
                        currencySpace,
                        { currencySpace = it },
                        label = { Text("Currency space") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        currencyId,
                        { currencyId = it },
                        label = { Text("Currency id") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(type, { type = it }, label = { Text("Type (optional: last, bid, ask, nav...)") }, singleLine = true)
                OutlinedTextField(valueText, { valueText = it }, label = { Text("Value") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = parsePriceValue(valueText)
                when {
                    commodityId.isBlank() -> error = "Enter a commodity id."
                    currencyId.isBlank() -> error = "Enter a currency id."
                    !DATE_PATTERN.matches(date) -> error = "Use YYYY-MM-DD for the date."
                    value == null -> error = "Enter a valid decimal value."
                    else -> onCreate(
                        CommodityRef(commoditySpace.trim(), commodityId.trim()),
                        CommodityRef(currencySpace.trim(), currencyId.trim()),
                        date,
                        type.trim(),
                        value,
                    )
                }
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditPriceDialog(
    price: XmlPrice,
    onDismiss: () -> Unit,
    onSave: (date: String, type: String, value: Money) -> Unit,
) {
    var date by remember { mutableStateOf(price.time.take(10).ifEmpty { LocalDate.now().toString() }) }
    var type by remember { mutableStateOf(price.type) }
    var valueText by remember { mutableStateOf("${price.value.num}/${price.value.denom}") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit price") },
        text = {
            Column {
                Text(
                    "${price.commodity.id} / ${price.currency.id}. The commodity/currency pair can't be " +
                        "changed here -- delete this price and create a new one instead.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(date, { date = it }, label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(type, { type = it }, label = { Text("Type (optional)") }, singleLine = true)
                OutlinedTextField(valueText, { valueText = it }, label = { Text("Value") }, singleLine = true)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val value = parsePriceValue(valueText)
                when {
                    !DATE_PATTERN.matches(date) -> error = "Use YYYY-MM-DD for the date."
                    value == null -> error = "Enter a valid decimal value."
                    else -> onSave(date, type.trim(), value)
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
