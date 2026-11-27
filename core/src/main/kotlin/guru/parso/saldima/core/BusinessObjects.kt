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

/** A GnuCash `GncVendor` business object: who a bill is owed to. */
data class XmlVendor(
    val guid: Guid,
    val name: String,
    val vendorNumber: String = "",
    val addressLine1: String = "",
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val active: Boolean = true,
)

data class VendorDraft(
    val name: String,
    val vendorNumber: String = "",
    val addressLine1: String = "",
    val phone: String = "",
    val email: String = "",
    val notes: String = "",
    val active: Boolean = true,
)

/**
 * One line on a bill: also a `GncEntry`, but using its bill-side fields
 * (`entry:bill`/`entry:b-acct`/`entry:b-price`) -- distinct from an
 * invoice line's `entry:invoice`/`entry:i-acct`/`entry:i-price`, even
 * though both share the same XML element.
 */
data class XmlBillEntry(
    val guid: Guid,
    val billGuid: Guid,
    val date: String,
    val description: String,
    val quantity: Money,
    val unitPrice: Money,
    val expenseAccountGuid: Guid,
) {
    val amount: Money get() = Money.multiply(quantity, unitPrice)
}

/**
 * A vendor bill: GnuCash stores it as a `gnc:GncInvoice` exactly like a
 * customer invoice, distinguished only by `invoice:owner`'s `owner:type`
 * (`"gncVendor"` here, `"gncCustomer"` for an invoice). `datePosted ==
 * null` means it is still a draft; posting is the one-way action that
 * creates the real payable/expense transaction behind it.
 */
data class XmlBill(
    val guid: Guid,
    val billNumber: String,
    val vendorGuid: Guid?,
    val dateOpened: String,
    val datePosted: String? = null,
    val notes: String = "",
    val active: Boolean = true,
    val postedTransactionGuid: Guid? = null,
    val payableAccountGuid: Guid? = null,
) {
    val isPosted: Boolean get() = datePosted != null
}
