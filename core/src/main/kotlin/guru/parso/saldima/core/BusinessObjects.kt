// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/** A GnuCash `GncCustomer` business object: a billing contact for invoices. */
data class XmlCustomer(
    val guid: Guid,
    val name: String,
    val customerNumber: String = "",
    val addressName: String = "",
    val addressLine1: String = "",
    val addressLine2: String = "",
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val active: Boolean = true,
)

data class CustomerDraft(
    val name: String,
    val customerNumber: String = "",
    val addressName: String = "",
    val addressLine1: String = "",
    val addressLine2: String = "",
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val active: Boolean = true,
)

/** One line on an invoice: a `GncEntry` business object. */
data class XmlInvoiceEntry(
    val guid: Guid,
    val invoiceGuid: Guid,
    val date: String,
    val description: String,
    val quantity: Money,
    val unitPrice: Money,
    val incomeAccountGuid: Guid,
) {
    val amount: Money get() = Money.multiply(quantity, unitPrice)
}

/**
 * A GnuCash `GncInvoice` business object. `datePosted == null` means it is
 * still a draft -- GnuCash's own distinction between an open and a posted
 * invoice; posting is the one-way action that creates the real
 * receivable/income transaction behind it.
 */
data class XmlInvoice(
    val guid: Guid,
    val invoiceNumber: String,
    val customerGuid: Guid?,
    val dateOpened: String,
    val datePosted: String? = null,
    val notes: String = "",
    val active: Boolean = true,
    val postedTransactionGuid: Guid? = null,
    val receivableAccountGuid: Guid? = null,
) {
    val isPosted: Boolean get() = datePosted != null
}
