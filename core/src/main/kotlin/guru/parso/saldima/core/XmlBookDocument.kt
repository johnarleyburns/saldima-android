// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.GZIPOutputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants

/**
 * A live, mutable GnuCash XML book. Keeps the original parsed element tree
 * and mutates known objects (accounts, transactions, splits) in place, so
 * prices, lots, scheduled transactions, business objects and unmodelled
 * slots survive a parse/edit/serialize round trip untouched -- unlike
 * `XmlBookWriter`, which always regenerates a minimal document from a
 * narrow `BookSnapshot` and is only ever an export, never edited.
 *
 * Mirrors the iOS (`XMLBookDocument`) and web (`GncXmlDocument`) engines.
 * The tree (`XmlNode`) is hand-rolled from plain Kotlin collections, built
 * from StAX pull-parser events (`javax.xml.stream`, already used by
 * `XmlBookReader` and confirmed to compile against the Android SDK); no
 * platform-specific DOM class is used.
 */
class XmlBookDocument private constructor(
    private val root: XmlNode,
    private val book: XmlNode,
) {
    private val accountIndex = mutableMapOf<Guid, XmlNode>()
    private val transactionIndex = mutableMapOf<Guid, XmlNode>()
    private val customerIndex = mutableMapOf<Guid, XmlNode>()
    private val invoiceIndex = mutableMapOf<Guid, XmlNode>()
    private val vendorIndex = mutableMapOf<Guid, XmlNode>()
    private val scheduledTransactionIndex = mutableMapOf<Guid, XmlNode>()

    init {
        reindex()
    }

    private fun reindex() {
        accountIndex.clear()
        transactionIndex.clear()
        customerIndex.clear()
        invoiceIndex.clear()
        vendorIndex.clear()
        scheduledTransactionIndex.clear()
        for (node in children(book, "account")) {
            guidValue(node, "id")?.let { accountIndex[it] = node }
        }
        for (node in children(book, "transaction")) {
            guidValue(node, "id")?.let { transactionIndex[it] = node }
        }
        for (node in children(book, "GncCustomer")) {
            guidValue(node, "guid")?.let { customerIndex[it] = node }
        }
        // Customer invoices and vendor bills are both `gnc:GncInvoice`,
        // distinguished only by `invoice:owner`'s `owner:type` -- this
        // index holds both; invoices()/bills() filter by owner type.
        for (node in children(book, "GncInvoice")) {
            guidValue(node, "guid")?.let { invoiceIndex[it] = node }
        }
        for (node in children(book, "GncVendor")) {
            guidValue(node, "guid")?.let { vendorIndex[it] = node }
        }
        for (node in children(book, "schedxaction")) {
            guidValue(node, "id")?.let { scheduledTransactionIndex[it] = node }
        }
    }

    private fun ownerType(node: XmlNode): String? =
        firstChild(node, "owner")?.let { firstChild(it, "type") }?.text

    companion object {
        private val NAMESPACES = linkedMapOf(
            "xmlns:gnc" to "http://www.gnucash.org/XML/gnc",
            "xmlns:act" to "http://www.gnucash.org/XML/act",
            "xmlns:book" to "http://www.gnucash.org/XML/book",
            "xmlns:cd" to "http://www.gnucash.org/XML/cd",
            "xmlns:cmdty" to "http://www.gnucash.org/XML/cmdty",
            "xmlns:slot" to "http://www.gnucash.org/XML/slot",
            "xmlns:split" to "http://www.gnucash.org/XML/split",
            "xmlns:trn" to "http://www.gnucash.org/XML/trn",
            "xmlns:ts" to "http://www.gnucash.org/XML/ts",
            "xmlns:cust" to "http://www.gnucash.org/XML/cust",
            "xmlns:addr" to "http://www.gnucash.org/XML/addr",
            "xmlns:invoice" to "http://www.gnucash.org/XML/invoice",
            "xmlns:entry" to "http://www.gnucash.org/XML/entry",
            "xmlns:owner" to "http://www.gnucash.org/XML/owner",
        )

        /**
         * Desktop GnuCash's own `recurrencePeriodTypeToString`/`FromString`
         * (`libgnucash/engine/Recurrence.cpp`) -- note the spaces, not
         * underscores, in three of these: verified against real GnuCash
         * source (and against the web track's own port of it).
         */
        private val PERIOD_TYPE_TO_XML = mapOf(
            PeriodType.ONCE to "once",
            PeriodType.DAY to "day",
            PeriodType.WEEK to "week",
            PeriodType.MONTH to "month",
            PeriodType.END_OF_MONTH to "end of month",
            PeriodType.NTH_WEEKDAY to "nth weekday",
            PeriodType.LAST_WEEKDAY to "last weekday",
            PeriodType.YEAR to "year",
        )
        private val PERIOD_TYPE_FROM_XML = PERIOD_TYPE_TO_XML.entries.associate { (key, value) -> value to key }

        fun parse(bytes: ByteArray): XmlBookDocument {
            val root = parseTree(ByteArrayInputStream(bytes))
            val book = findDescendant(root, "book") ?: throw BookFormatException("book has no <gnc:book>")
            return XmlBookDocument(root, book)
        }

        /** Builds a fresh document from a format-neutral snapshot -- the import/materialize boundary. */
        fun build(snapshot: BookSnapshot, templateRootGuid: Guid, ids: IdGenerator): XmlBookDocument {
            val currencyCode = snapshot.currencyCode ?: "USD"
            val fraction = snapshot.fraction ?: 100L

            val root = XmlNode("#document")
            val gncRoot = XmlNode("gnc-v2")
            for ((key, value) in NAMESPACES) gncRoot.attributes[key] = value
            root.addChild(gncRoot)

            gncRoot.addChild(leaf("gnc:count-data", "1", "cd:type" to "book"))

            val book = XmlNode("gnc:book")
            book.attributes["version"] = "2.0.0"
            gncRoot.addChild(book)

            book.addChild(leaf("book:id", snapshot.bookGuid.raw, "type" to "guid"))

            val currencyCommodity = XmlNode("gnc:commodity")
            currencyCommodity.attributes["version"] = "2.0.0"
            currencyCommodity.addChild(leaf("cmdty:space", "CURRENCY"))
            currencyCommodity.addChild(leaf("cmdty:id", currencyCode))
            currencyCommodity.addChild(leaf("cmdty:fraction", fraction.toString()))
            book.addChild(currencyCommodity)

            val ordered = XmlBookWriter.orderedAccounts(snapshot)
            val rootAccount = snapshot.accounts.firstOrNull { it.type == "ROOT" }
            val rootGuid = rootAccount?.guid ?: ids.next()
            book.addChild(
                accountNode(
                    rootGuid,
                    rootAccount?.name ?: "Root Account",
                    "ROOT",
                    null,
                    "",
                    "",
                    hidden = false,
                    placeholder = false,
                    currencyCode = null,
                    fraction = fraction,
                ),
            )
            book.addChild(
                accountNode(
                    templateRootGuid,
                    "Template Root",
                    "ROOT",
                    null,
                    "",
                    "",
                    hidden = false,
                    placeholder = false,
                    currencyCode = null,
                    fraction = fraction,
                ),
            )

            for (account in ordered) {
                if (account.guid == rootGuid) continue
                book.addChild(
                    accountNode(
                        account.guid,
                        account.name,
                        account.type,
                        account.parentGuid,
                        account.code,
                        account.description,
                        account.hidden,
                        account.placeholder,
                        currencyCode,
                        fraction,
                    ),
                )
            }

            for (transaction in snapshot.transactions) {
                val splits = transaction.splits.map { split -> Triple(ids.next(), split.accountGuid, split.value) }
                book.addChild(
                    transactionNode(
                        transaction.guid,
                        transaction.date,
                        transaction.description,
                        transaction.currencyCode ?: currencyCode,
                        splits,
                    ),
                )
            }

            return XmlBookDocument(root, book)
        }

        private fun accountNode(
            guid: Guid,
            name: String,
            type: String,
            parentGuid: Guid?,
            code: String,
            description: String,
            hidden: Boolean,
            placeholder: Boolean,
            currencyCode: String?,
            fraction: Long,
        ): XmlNode {
            val node = XmlNode("gnc:account")
            node.attributes["version"] = "2.0.0"
            node.addChild(leaf("act:name", name))
            node.addChild(leaf("act:id", guid.raw, "type" to "guid"))
            node.addChild(leaf("act:type", type))
            if (currencyCode != null) {
                val commodity = XmlNode("act:commodity")
                commodity.addChild(leaf("cmdty:space", "CURRENCY"))
                commodity.addChild(leaf("cmdty:id", currencyCode))
                node.addChild(commodity)
                node.addChild(leaf("act:commodity-scu", fraction.toString()))
            }
            parentGuid?.let { node.addChild(leaf("act:parent", it.raw, "type" to "guid")) }
            if (hidden || placeholder) {
                val slots = XmlNode("act:slots")
                if (placeholder) slots.addChild(booleanSlot("placeholder"))
                if (hidden) slots.addChild(booleanSlot("hidden"))
                node.addChild(slots)
            }
            node.addChild(leaf("act:code", code))
            node.addChild(leaf("act:description", description))
            return node
        }

        private fun booleanSlot(key: String): XmlNode {
            val slot = XmlNode("slot")
            slot.addChild(leaf("slot:key", key))
            slot.addChild(leaf("slot:value", "true", "type" to "string"))
            return slot
        }

        private fun transactionNode(
            guid: Guid,
            date: String,
            description: String,
            currencyCode: String,
            splits: List<Triple<Guid, Guid, Money>>,
        ): XmlNode {
            val node = XmlNode("gnc:transaction")
            node.attributes["version"] = "2.0.0"
            node.addChild(leaf("trn:id", guid.raw, "type" to "guid"))

            val currency = XmlNode("trn:currency")
            currency.addChild(leaf("cmdty:space", "CURRENCY"))
            currency.addChild(leaf("cmdty:id", currencyCode))
            node.addChild(currency)

            val trimmedDate = date.take(10)
            val posted = XmlNode("trn:date-posted")
            posted.addChild(leaf("ts:date", "$trimmedDate 00:00:00 +0000"))
            node.addChild(posted)
            val entered = XmlNode("trn:date-entered")
            entered.addChild(leaf("ts:date", "$trimmedDate 00:00:00 +0000"))
            node.addChild(entered)

            node.addChild(leaf("trn:description", description))

            val splitsNode = XmlNode("trn:splits")
            for ((splitGuid, accountGuid, value) in splits) {
                val splitNode = XmlNode("trn:split")
                splitNode.addChild(leaf("split:id", splitGuid.raw, "type" to "guid"))
                splitNode.addChild(leaf("split:reconciled-state", "n"))
                val amount = "${value.num}/${value.denom}"
                splitNode.addChild(leaf("split:value", amount))
                splitNode.addChild(leaf("split:quantity", amount))
                splitNode.addChild(leaf("split:account", accountGuid.raw, "type" to "guid"))
                splitsNode.addChild(splitNode)
            }
            node.addChild(splitsNode)
            return node
        }

        private fun customerNode(guid: Guid, draft: CustomerDraft, currencyCode: String): XmlNode {
            val node = XmlNode("gnc:GncCustomer")
            node.attributes["version"] = "2.0.0"
            node.addChild(leaf("cust:guid", guid.raw, "type" to "guid"))
            node.addChild(leaf("cust:name", draft.name))
            node.addChild(leaf("cust:id", draft.customerNumber))
            val address = XmlNode("cust:addr")
            address.attributes["version"] = "2.0.0"
            address.addChild(leaf("addr:name", draft.addressName))
            address.addChild(leaf("addr:addr1", draft.addressLine1))
            address.addChild(leaf("addr:addr2", draft.addressLine2))
            address.addChild(leaf("addr:phone", draft.phone))
            address.addChild(leaf("addr:email", draft.email))
            node.addChild(address)
            node.addChild(leaf("cust:notes", draft.notes))
            node.addChild(leaf("cust:active", if (draft.active) "1" else "0"))
            val currency = XmlNode("cust:currency")
            currency.addChild(leaf("cmdty:space", "CURRENCY"))
            currency.addChild(leaf("cmdty:id", currencyCode))
            node.addChild(currency)
            return node
        }

        private fun vendorNode(guid: Guid, draft: VendorDraft, currencyCode: String): XmlNode {
            val node = XmlNode("gnc:GncVendor")
            node.attributes["version"] = "2.0.0"
            node.addChild(leaf("vendor:guid", guid.raw, "type" to "guid"))
            node.addChild(leaf("vendor:name", draft.name))
            node.addChild(leaf("vendor:id", draft.vendorNumber))
            val address = XmlNode("vendor:addr")
            address.attributes["version"] = "2.0.0"
            address.addChild(leaf("addr:name", ""))
            address.addChild(leaf("addr:addr1", draft.addressLine1))
            address.addChild(leaf("addr:addr2", ""))
            address.addChild(leaf("addr:phone", draft.phone))
            address.addChild(leaf("addr:email", draft.email))
            node.addChild(address)
            node.addChild(leaf("vendor:notes", draft.notes))
            node.addChild(leaf("vendor:taxincluded", "0"))
            node.addChild(leaf("vendor:active", if (draft.active) "1" else "0"))
            val currency = XmlNode("vendor:currency")
            currency.addChild(leaf("cmdty:space", "CURRENCY"))
            currency.addChild(leaf("cmdty:id", currencyCode))
            node.addChild(currency)
            return node
        }

        private fun leaf(name: String, text: String, vararg attributes: Pair<String, String>): XmlNode {
            val node = XmlNode(name)
            for ((key, value) in attributes) node.attributes[key] = value
            node.text = text
            return node
        }
    }

    // MARK: - Serialization

    fun serialize(): ByteArray {
        val out = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\" ?>\n")
        for (child in root.children) serializeNode(child, out)
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    fun gzip(): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(serialize()) }
        return output.toByteArray()
    }

    // MARK: - Reading

    fun bookGuid(): Guid? = firstChild(book, "id")?.let { guidOrNull(it.text) }

    fun accounts(): List<SnapshotAccount> = children(book, "account").mapNotNull(::accountRow)

    fun account(guid: Guid): SnapshotAccount? = accountIndex[guid]?.let(::accountRow)

    private fun accountRow(node: XmlNode): SnapshotAccount? {
        val guid = guidValue(node, "id") ?: return null
        val name = firstChild(node, "name")?.text ?: return null
        val type = firstChild(node, "type")?.text ?: return null
        val parent = guidValue(node, "parent")
        val commodity = firstChild(node, "commodity")?.let { firstChild(it, "id")?.text }
        val code = firstChild(node, "code")?.text ?: ""
        val description = firstChild(node, "description")?.text ?: ""
        val (hidden, placeholder) = readFlags(node)
        return SnapshotAccount(
            guid = guid,
            name = name,
            type = type,
            parentGuid = parent,
            commodityCode = commodity,
            code = code,
            description = description,
            hidden = hidden,
            placeholder = placeholder,
        )
    }

    private fun readFlags(accountNode: XmlNode): Pair<Boolean, Boolean> {
        val slots = firstChild(accountNode, "slots") ?: return false to false
        var hidden = false
        var placeholder = false
        for (slot in children(slots, "slot")) {
            val key = firstChild(slot, "key")?.text ?: continue
            val value = firstChild(slot, "value")?.text ?: ""
            if (key == "hidden") hidden = value == "true"
            if (key == "placeholder") placeholder = value == "true"
        }
        return hidden to placeholder
    }

    /** The CURRENCY commodity GnuCash would show first, preferring USD when more than one exists. */
    fun currency(): BookCurrency? {
        val commodities = children(book, "commodity").filter { firstChild(it, "space")?.text == "CURRENCY" }
        if (commodities.isEmpty()) return null
        val chosen = commodities.firstOrNull { firstChild(it, "id")?.text == "USD" } ?: commodities[0]
        val code = firstChild(chosen, "id")?.text ?: return null
        val fullName = firstChild(chosen, "name")?.text ?: code
        val fraction = firstChild(chosen, "fraction")?.text?.toLongOrNull() ?: 100L
        return BookCurrency(code, fullName, fraction)
    }

    /** All transactions, newest first; splits within a transaction keep document order. */
    fun transactions(): List<SnapshotTransaction> =
        children(book, "transaction").mapNotNull(::transactionRow)
            .sortedWith(compareByDescending<SnapshotTransaction> { it.date }.thenByDescending { it.guid.raw })

    fun transaction(guid: Guid): SnapshotTransaction? = transactionIndex[guid]?.let(::transactionRow)

    private fun transactionRow(node: XmlNode): SnapshotTransaction? {
        val guid = guidValue(node, "id") ?: return null
        val description = firstChild(node, "description")?.text ?: return null
        val date = firstChild(node, "date-posted")?.let { firstChild(it, "date")?.text }?.take(10) ?: ""
        val currency = firstChild(node, "currency")?.let { firstChild(it, "id")?.text }
        val splitsContainer = firstChild(node, "splits") ?: node
        val splits = children(splitsContainer, "split").mapNotNull { splitNode ->
            val account = guidValue(splitNode, "account") ?: return@mapNotNull null
            val value = firstChild(splitNode, "value")?.text?.let(::rational) ?: return@mapNotNull null
            val splitGuid = guidValue(splitNode, "id")
            val memo = firstChild(splitNode, "memo")?.text ?: ""
            val reconcileState = firstChild(splitNode, "reconciled-state")?.text?.ifEmpty { null } ?: "n"
            val reconcileDate = firstChild(splitNode, "reconcile-date")?.let { firstChild(it, "date")?.text }
            val voidFormerValue = readNumericSlot(splitNode, "void-former-value")
            SnapshotSplit(account, value, splitGuid, memo, reconcileState, reconcileDate, voidFormerValue)
        }
        val docLink = firstChild(node, "slots")
            ?.let { slots -> children(slots, "slot").firstOrNull { firstChild(it, "key")?.text == "assoc_uri" } }
            ?.let { firstChild(it, "value")?.text }
        return SnapshotTransaction(
            guid,
            date,
            description,
            currency,
            splits,
            docLink,
            voided = readStringSlot(node, "void-time") != null,
            voidReason = readStringSlot(node, "void-reason"),
            voidTime = readStringSlot(node, "void-time"),
        )
    }

    /**
     * Sets one split's reconcile state, the same action as checking a row
     * off in GnuCash's Reconcile window. `reconcileDate` is required for
     * `"y"` (reconciled) and cleared for any other state, matching desktop
     * GnuCash.
     */
    fun setSplitReconcileState(transactionGuid: Guid, splitGuid: Guid, state: String, reconcileDate: String? = null) {
        val transactionNode = transactionIndex[transactionGuid]
            ?: throw BookFormatException("unknown transaction ${transactionGuid.raw}")
        val splitsContainer = firstChild(transactionNode, "splits") ?: transactionNode
        val splitNode = children(splitsContainer, "split").firstOrNull { guidValue(it, "id") == splitGuid }
            ?: throw BookFormatException("unknown split ${splitGuid.raw}")
        setChildText(splitNode, "reconciled-state", "split:reconciled-state", state)
        val existingDate = firstChild(splitNode, "reconcile-date")
        if (state == "y") {
            if (reconcileDate == null) {
                throw BookFormatException("a reconcile date is required to mark a split reconciled")
            }
            if (existingDate != null) {
                firstChild(existingDate, "date")?.let { it.text = reconcileDate }
            } else {
                val dateNode = XmlNode("split:reconcile-date")
                dateNode.addChild(leaf("ts:date", reconcileDate))
                splitNode.addChild(dateNode)
            }
        } else {
            existingDate?.detach()
        }
    }

    /**
     * Sets or clears a transaction's "Associate File/Location" -- an
     * `assoc_uri` slot, the same slot desktop GnuCash's own right-click
     * action writes. `uri = null` removes it.
     */
    fun setTransactionDocLink(transactionGuid: Guid, uri: String?) {
        val transactionNode = transactionIndex[transactionGuid]
            ?: throw BookFormatException("unknown transaction ${transactionGuid.raw}")
        var slots = firstChild(transactionNode, "slots")
        if (slots == null) {
            slots = XmlNode("trn:slots")
            transactionNode.addChild(slots)
        }
        val existing = children(slots, "slot").firstOrNull { firstChild(it, "key")?.text == "assoc_uri" }
        if (uri != null) {
            if (existing != null) {
                firstChild(existing, "value")?.let { it.text = uri }
            } else {
                val slot = XmlNode("slot")
                slot.addChild(leaf("slot:key", "assoc_uri"))
                slot.addChild(leaf("slot:value", uri, "type" to "string"))
                slots.addChild(slot)
            }
        } else {
            existing?.detach()
        }
        if (slots.children.isEmpty()) slots.detach()
    }

    /**
     * Voids a transaction exactly like desktop GnuCash's own `xaccTransVoid`:
     * every split's value and quantity is preserved in a `void-former-*`
     * slot, then zeroed (so the transaction stops affecting any balance
     * without being deleted), every split is marked `"v"`, and the
     * transaction's notes/void-reason/void-time slots record why and when.
     * Refuses to void an already-voided transaction.
     */
    fun voidTransaction(transactionGuid: Guid, reason: String, voidTime: String) {
        val transactionNode = transactionIndex[transactionGuid]
            ?: throw BookFormatException("unknown transaction ${transactionGuid.raw}")
        if (reason.isBlank()) {
            throw BookFormatException("a reason is required to void a transaction")
        }
        if (readStringSlot(transactionNode, "void-time") != null) {
            throw BookFormatException("transaction ${transactionGuid.raw} is already voided")
        }

        var slots = firstChild(transactionNode, "slots")
        if (slots == null) {
            slots = XmlNode("trn:slots")
            transactionNode.addChild(slots)
        }
        readStringSlot(transactionNode, "notes")?.let { setStringSlot(slots, "void-former-notes", it) }
        setStringSlot(slots, "notes", "Voided transaction")
        setStringSlot(slots, "void-reason", reason)
        setStringSlot(slots, "void-time", voidTime)
        setStringSlot(slots, "trans-read-only", "Transaction Voided")

        val splitsContainer = firstChild(transactionNode, "splits") ?: transactionNode
        for (splitNode in children(splitsContainer, "split")) {
            val valueText = firstChild(splitNode, "value")?.text ?: continue
            val value = rational(valueText) ?: continue
            val quantityText = firstChild(splitNode, "quantity")?.text ?: valueText
            val quantity = rational(quantityText) ?: value
            var splitSlots = firstChild(splitNode, "slots")
            if (splitSlots == null) {
                splitSlots = XmlNode("split:slots")
                splitNode.addChild(splitSlots)
            }
            setNumericSlot(splitSlots, "void-former-value", value)
            setNumericSlot(splitSlots, "void-former-amount", quantity)
            setChildText(splitNode, "value", "split:value", "0/${value.denom}")
            setChildText(splitNode, "quantity", "split:quantity", "0/${quantity.denom}")
            setChildText(splitNode, "reconciled-state", "split:reconciled-state", "v")
        }
    }

    /**
     * Reverses `voidTransaction`: restores every split's original
     * value/quantity and reconcile state (always back to `"n"`, matching
     * desktop GnuCash -- a split's reconciled state before voiding is not
     * preserved), restores the transaction's notes, and clears every
     * void-related slot. Refuses a transaction that isn't voided.
     */
    fun unvoidTransaction(transactionGuid: Guid) {
        val transactionNode = transactionIndex[transactionGuid]
            ?: throw BookFormatException("unknown transaction ${transactionGuid.raw}")
        if (readStringSlot(transactionNode, "void-time") == null) {
            throw BookFormatException("transaction ${transactionGuid.raw} is not voided")
        }

        var slots = firstChild(transactionNode, "slots")
        if (slots == null) {
            slots = XmlNode("trn:slots")
            transactionNode.addChild(slots)
        }
        setStringSlot(slots, "notes", readStringSlot(transactionNode, "void-former-notes"))
        setStringSlot(slots, "void-former-notes", null)
        setStringSlot(slots, "void-reason", null)
        setStringSlot(slots, "void-time", null)
        setStringSlot(slots, "trans-read-only", null)
        if (slots.children.isEmpty()) slots.detach()

        val splitsContainer = firstChild(transactionNode, "splits") ?: transactionNode
        for (splitNode in children(splitsContainer, "split")) {
            val formerValue = readNumericSlot(splitNode, "void-former-value")
            val formerQuantity = readNumericSlot(splitNode, "void-former-amount")
            if (formerValue != null) {
                setChildText(splitNode, "value", "split:value", "${formerValue.num}/${formerValue.denom}")
            }
            if (formerQuantity != null) {
                setChildText(splitNode, "quantity", "split:quantity", "${formerQuantity.num}/${formerQuantity.denom}")
            }
            setChildText(splitNode, "reconciled-state", "split:reconciled-state", "n")
            val splitSlots = firstChild(splitNode, "slots")
            if (splitSlots != null) {
                setNumericSlot(splitSlots, "void-former-value", null)
                setNumericSlot(splitSlots, "void-former-amount", null)
                if (splitSlots.children.isEmpty()) splitSlots.detach()
            }
        }
    }

    /** Sums each account's split value (the book currency amount); not rolled up to parents. */
    fun balances(): Map<Guid, Money> {
        val totals = mutableMapOf<Guid, Money>()
        for (transaction in transactions()) {
            for (split in transaction.splits) {
                totals[split.accountGuid] = Money.add(totals[split.accountGuid] ?: Money.ZERO, split.value)
            }
        }
        return totals
    }

    // MARK: - Mutating: accounts

    fun createAccount(
        guid: Guid,
        name: String,
        type: String,
        parentGuid: Guid?,
        code: String = "",
        description: String = "",
        hidden: Boolean = false,
        placeholder: Boolean = false,
    ): SnapshotAccount {
        val currencyCode = if (type == "ROOT") null else currency()?.code
        val fraction = currency()?.fraction ?: 100L
        val newNode = accountNode(guid, name, type, parentGuid, code, description, hidden, placeholder, currencyCode, fraction)
        val firstTransaction = children(book, "transaction").firstOrNull()
        if (firstTransaction != null) {
            book.insertChild(newNode, firstTransaction.indexInParent() ?: book.children.size)
        } else {
            book.addChild(newNode)
        }
        accountIndex[guid] = newNode
        return accountRow(newNode)!!
    }

    fun updateAccount(
        guid: Guid,
        name: String,
        type: String,
        parentGuid: Guid?,
        code: String,
        description: String,
        hidden: Boolean,
        placeholder: Boolean,
    ) {
        val node = accountIndex[guid] ?: return
        setChildText(node, "name", "act:name", name)
        setChildText(node, "type", "act:type", type)
        setChildText(node, "code", "act:code", code)
        setChildText(node, "description", "act:description", description)

        val existingParent = firstChild(node, "parent")
        if (existingParent != null) {
            if (parentGuid != null) existingParent.text = parentGuid.raw else existingParent.detach()
        } else if (parentGuid != null) {
            node.addChild(leaf("act:parent", parentGuid.raw, "type" to "guid"))
        }

        var slots = firstChild(node, "slots")
        if (slots == null) {
            slots = XmlNode("act:slots")
            node.addChild(slots)
        }
        setBooleanSlot(slots, "hidden", hidden)
        setBooleanSlot(slots, "placeholder", placeholder)
        if (children(slots, "slot").isEmpty()) slots.detach()
    }

    fun deleteAccount(guid: Guid) {
        val node = accountIndex[guid] ?: return
        node.detach()
        accountIndex.remove(guid)
    }

    fun accountCount(withParent: Guid): Int = accounts().count { it.parentGuid == withParent }

    fun splitCount(forAccount: Guid): Int =
        transactions().sumOf { transaction -> transaction.splits.count { it.accountGuid == forAccount } }

    private fun findSlot(slots: XmlNode, key: String): XmlNode? =
        children(slots, "slot").firstOrNull { firstChild(it, "key")?.text == key }

    private fun readStringSlot(owner: XmlNode, key: String): String? {
        val slots = firstChild(owner, "slots") ?: return null
        val slot = findSlot(slots, key) ?: return null
        return firstChild(slot, "value")?.text
    }

    private fun setStringSlot(slots: XmlNode, key: String, value: String?) {
        val existing = findSlot(slots, key)
        if (value == null) {
            existing?.detach()
            return
        }
        if (existing != null) {
            firstChild(existing, "value")?.let { it.text = value }
            return
        }
        val slot = XmlNode("slot")
        slot.addChild(leaf("slot:key", key))
        slot.addChild(leaf("slot:value", value, "type" to "string"))
        slots.addChild(slot)
    }

    private fun readNumericSlot(owner: XmlNode, key: String): Money? {
        val slots = firstChild(owner, "slots") ?: return null
        val slot = findSlot(slots, key) ?: return null
        val valueNode = firstChild(slot, "value") ?: return null
        return rational(valueNode.text)
    }

    private fun setNumericSlot(slots: XmlNode, key: String, value: Money?) {
        val existing = findSlot(slots, key)
        if (value == null) {
            existing?.detach()
            return
        }
        val valueText = "${value.num}/${value.denom}"
        if (existing != null) {
            firstChild(existing, "value")?.let { it.text = valueText }
            return
        }
        val slot = XmlNode("slot")
        slot.addChild(leaf("slot:key", key))
        slot.addChild(leaf("slot:value", valueText, "type" to "numeric"))
        slots.addChild(slot)
    }

    private fun setBooleanSlot(slots: XmlNode, key: String, value: Boolean) {
        val existing = children(slots, "slot").firstOrNull { firstChild(it, "key")?.text == key }
        if (!value) {
            existing?.detach()
            return
        }
        if (existing != null) {
            firstChild(existing, "value")?.let { it.text = "true" }
            return
        }
        slots.addChild(booleanSlot(key))
    }

    private fun setChildText(parent: XmlNode, localName: String, tag: String, value: String) {
        val existing = firstChild(parent, localName)
        if (existing != null) {
            existing.children.clear()
            existing.text = value
            return
        }
        parent.addChild(leaf(tag, value))
    }

    // MARK: - Mutating: transactions

    fun postTransaction(
        guid: Guid,
        date: String,
        description: String,
        splits: List<Triple<Guid, Money, Guid>>,
    ): SnapshotTransaction {
        val currencyCode = currency()?.code ?: "USD"
        val node = transactionNode(
            guid,
            date,
            description,
            currencyCode,
            splits.map { (accountGuid, value, splitGuid) -> Triple(splitGuid, accountGuid, value) },
        )
        book.addChild(node)
        transactionIndex[guid] = node
        return transactionRow(node)!!
    }

    fun deleteTransaction(guid: Guid) {
        val node = transactionIndex[guid] ?: return
        node.detach()
        transactionIndex.remove(guid)
    }

    // MARK: - Scheduled transactions (GncSchedXaction)

    fun scheduledTransactions(): List<XmlScheduledTransaction> =
        scheduledTransactionIndex.values.mapNotNull(::scheduledTransactionRow)

    fun scheduledTransaction(guid: Guid): XmlScheduledTransaction? =
        scheduledTransactionIndex[guid]?.let(::scheduledTransactionRow)

    private fun scheduledTransactionRow(node: XmlNode): XmlScheduledTransaction? {
        val guid = guidValue(node, "id") ?: return null
        val name = firstChild(node, "name")?.text ?: ""
        val enabled = firstChild(node, "enabled")?.text != "n"
        val autoCreate = firstChild(node, "autoCreate")?.text == "y"
        val autoCreateNotify = firstChild(node, "autoCreateNotify")?.text == "y"
        val advanceCreateDays = firstChild(node, "advanceCreateDays")?.text?.toIntOrNull() ?: 0
        val advanceRemindDays = firstChild(node, "advanceRemindDays")?.text?.toIntOrNull() ?: 0
        val instanceCount = firstChild(node, "instanceCount")?.text?.toIntOrNull() ?: 0
        val start = readGdate(node, "start") ?: ""
        val lastOccurred = readGdate(node, "last")
        val numOccur = firstChild(node, "num-occur")?.text?.toIntOrNull()
        val endDate = readGdate(node, "end")
        val end = when {
            numOccur != null -> ScheduledEnd.Occurrences(numOccur)
            endDate != null -> ScheduledEnd.OnDate(endDate)
            else -> null
        }
        val templateAccountGuid = guidValue(node, "templ-acct") ?: return null

        val scheduleNode = firstChild(node, "schedule")
        val recurrenceNode = scheduleNode?.let { firstChild(it, "recurrence") }
        val multiplier = recurrenceNode?.let { firstChild(it, "mult")?.text?.toIntOrNull() } ?: 1
        val periodTypeXml = recurrenceNode?.let { firstChild(it, "period_type")?.text } ?: "once"
        val periodType = PERIOD_TYPE_FROM_XML[periodTypeXml] ?: PeriodType.ONCE
        val recurrenceStart = recurrenceNode?.let { readGdate(it, "start") } ?: start
        val weekendAdjust = when (recurrenceNode?.let { firstChild(it, "weekend_adj")?.text }) {
            "back" -> WeekendAdjust.BACK
            "forward" -> WeekendAdjust.FORWARD
            else -> WeekendAdjust.NONE
        }

        val templateTransactionNode = findTemplateTransactionNode(templateAccountGuid)
        var description = ""
        var currencyCode = "USD"
        val templateSplits = mutableListOf<XmlScheduledSplit>()
        if (templateTransactionNode != null) {
            description = firstChild(templateTransactionNode, "description")?.text ?: ""
            currencyCode = firstChild(templateTransactionNode, "currency")
                ?.let { firstChild(it, "id")?.text } ?: currencyCode
            val splitsContainer = firstChild(templateTransactionNode, "splits") ?: templateTransactionNode
            for (splitNode in children(splitsContainer, "split")) {
                val frame = readFrameSlotEntries(splitNode, "sched-xaction")
                val realAccountGuid = frame["account"]?.let(::guidOrNull) ?: continue
                val debitNumeric = frame["debit-numeric"]?.let(::rational)
                val creditNumeric = frame["credit-numeric"]?.let(::rational)
                val value = when {
                    debitNumeric != null && debitNumeric.num != 0L -> debitNumeric
                    creditNumeric != null && creditNumeric.num != 0L -> Money(-creditNumeric.num, creditNumeric.denom)
                    else -> Money(0, 1)
                }
                templateSplits += XmlScheduledSplit(realAccountGuid, value)
            }
        }

        return XmlScheduledTransaction(
            guid = guid,
            name = name,
            description = description,
            currencyCode = currencyCode,
            enabled = enabled,
            autoCreate = autoCreate,
            autoCreateNotify = autoCreateNotify,
            advanceCreateDays = advanceCreateDays,
            advanceRemindDays = advanceRemindDays,
            start = start,
            lastOccurred = lastOccurred,
            instanceCount = instanceCount,
            end = end,
            recurrence = Recurrence(periodType, multiplier, recurrenceStart, weekendAdjust),
            templateAccountGuid = templateAccountGuid,
            templateSplits = templateSplits,
        )
    }

    /**
     * Creates a scheduled transaction: a recurrence rule plus a template of
     * splits, matching desktop GnuCash's own on-disk shape exactly -- a
     * hidden, per-schedule template account (type `BANK`, named after its
     * own guid) under the one shared, book-wide "Template Root" account; a
     * template `gnc:transaction` whose splits point at that template
     * account (never the real one); and a `sched-xaction` KVP frame on each
     * split carrying the real account and amount. Both sides of every
     * split's KVP (`credit-*`/`debit-*`) are always written, matching real
     * GnuCash -- the unused side is an empty formula and a zero numeric,
     * never omitted. Amount formulas are always a plain decimal number in
     * this engine (Milestone 1: no algebraic formula evaluation).
     */
    fun createScheduledTransaction(
        guid: Guid,
        name: String,
        description: String,
        currencyCode: String,
        start: String,
        recurrence: Recurrence,
        templateSplits: List<Triple<Guid, Money, Guid>>,
        templateAccountGuid: Guid,
        templateTransactionGuid: Guid,
        enabled: Boolean = true,
        autoCreate: Boolean = false,
        autoCreateNotify: Boolean = false,
        advanceCreateDays: Int = 0,
        advanceRemindDays: Int = 0,
        end: ScheduledEnd? = null,
        templateRootGuid: Guid? = null,
    ): XmlScheduledTransaction {
        if (scheduledTransactionIndex.containsKey(guid)) {
            throw BookFormatException("duplicate scheduled transaction ${guid.raw}")
        }
        if (templateSplits.size < 2) {
            throw BookFormatException("a scheduled transaction needs at least two template splits")
        }
        var total = Money.ZERO
        for ((_, value, _) in templateSplits) total = Money.add(total, value)
        if (total != Money.ZERO) {
            throw BookFormatException("template split values must sum to zero")
        }

        val templateTransactionsNode = findOrCreateTemplateTransactionsNode()
        val templateRootNode = findOrCreateTemplateRootNode(templateTransactionsNode, templateRootGuid)
        val templateRootGuidValue = guidValue(templateRootNode, "id")
            ?: throw BookFormatException("template root is missing an id")
        val templateAccountXml = createTemplateAccountNode(templateAccountGuid, templateRootGuidValue)
        templateTransactionsNode.addChild(templateAccountXml)

        val fraction = currencyFraction(currencyCode)
        val splitNodes = templateSplits.map { (accountGuid, value, splitGuid) ->
            val isDebit = value.num >= 0L
            val magnitude = if (isDebit) value else value.negated()
            val formulaText = magnitude.formatted(magnitude.denom)
            val numericText = "${magnitude.num}/${magnitude.denom}"
            val debitFormula = if (isDebit) formulaText else ""
            val creditFormula = if (isDebit) "" else formulaText
            val debitNumeric = if (isDebit) numericText else "0/1"
            val creditNumeric = if (isDebit) "0/1" else numericText

            val splitNode = XmlNode("trn:split")
            splitNode.addChild(leaf("split:id", splitGuid.raw, "type" to "guid"))
            splitNode.addChild(leaf("split:reconciled-state", "n"))
            splitNode.addChild(leaf("split:value", "0/$fraction"))
            splitNode.addChild(leaf("split:quantity", "0/1"))
            splitNode.addChild(leaf("split:account", templateAccountGuid.raw, "type" to "guid"))
            val slots = XmlNode("split:slots")
            val schedSlot = XmlNode("slot")
            schedSlot.addChild(leaf("slot:key", "sched-xaction"))
            schedSlot.addChild(
                frameSlotValue(
                    listOf(
                        Triple("account", "guid", accountGuid.raw),
                        Triple("credit-formula", "string", creditFormula),
                        Triple("credit-numeric", "numeric", creditNumeric),
                        Triple("debit-formula", "string", debitFormula),
                        Triple("debit-numeric", "numeric", debitNumeric),
                    ),
                ),
            )
            slots.addChild(schedSlot)
            splitNode.addChild(slots)
            splitNode
        }

        val templateTransactionNode = XmlNode("gnc:transaction")
        templateTransactionNode.attributes["version"] = "2.0.0"
        templateTransactionNode.addChild(leaf("trn:id", templateTransactionGuid.raw, "type" to "guid"))
        val currencyNode = XmlNode("trn:currency")
        currencyNode.addChild(leaf("cmdty:space", "CURRENCY"))
        currencyNode.addChild(leaf("cmdty:id", currencyCode))
        templateTransactionNode.addChild(currencyNode)
        val posted = XmlNode("trn:date-posted")
        posted.addChild(leaf("ts:date", "$start 00:00:00 +0000"))
        templateTransactionNode.addChild(posted)
        val entered = XmlNode("trn:date-entered")
        entered.addChild(leaf("ts:date", "$start 00:00:00 +0000"))
        templateTransactionNode.addChild(entered)
        templateTransactionNode.addChild(leaf("trn:description", description))
        val splitsNode = XmlNode("trn:splits")
        for (splitNode in splitNodes) splitsNode.addChild(splitNode)
        templateTransactionNode.addChild(splitsNode)
        templateTransactionsNode.addChild(templateTransactionNode)

        val sxNode = XmlNode("gnc:schedxaction")
        sxNode.attributes["version"] = "2.0.0"
        sxNode.addChild(leaf("sx:id", guid.raw, "type" to "guid"))
        sxNode.addChild(leaf("sx:name", name))
        sxNode.addChild(leaf("sx:enabled", if (enabled) "y" else "n"))
        sxNode.addChild(leaf("sx:autoCreate", if (autoCreate) "y" else "n"))
        sxNode.addChild(leaf("sx:autoCreateNotify", if (autoCreateNotify) "y" else "n"))
        sxNode.addChild(leaf("sx:advanceCreateDays", advanceCreateDays.toString()))
        sxNode.addChild(leaf("sx:advanceRemindDays", advanceRemindDays.toString()))
        sxNode.addChild(leaf("sx:instanceCount", "0"))
        sxNode.addChild(gdateNode("sx:start", start))
        when (end) {
            is ScheduledEnd.Occurrences -> {
                sxNode.addChild(leaf("sx:num-occur", end.count.toString()))
                sxNode.addChild(leaf("sx:rem-occur", end.count.toString()))
            }
            is ScheduledEnd.OnDate -> sxNode.addChild(gdateNode("sx:end", end.date))
            null -> Unit
        }
        sxNode.addChild(leaf("sx:templ-acct", templateAccountGuid.raw, "type" to "guid"))
        val recurrenceNode = XmlNode("gnc:recurrence")
        recurrenceNode.attributes["version"] = "1.0.0"
        recurrenceNode.addChild(leaf("recurrence:mult", recurrence.multiplier.toString()))
        recurrenceNode.addChild(leaf("recurrence:period_type", PERIOD_TYPE_TO_XML.getValue(recurrence.periodType)))
        recurrenceNode.addChild(gdateNode("recurrence:start", recurrence.start))
        if (recurrence.weekendAdjust != WeekendAdjust.NONE) {
            recurrenceNode.addChild(leaf("recurrence:weekend_adj", recurrence.weekendAdjust.name.lowercase()))
        }
        val scheduleNode = XmlNode("sx:schedule")
        scheduleNode.addChild(recurrenceNode)
        sxNode.addChild(scheduleNode)

        book.addChild(sxNode)
        scheduledTransactionIndex[guid] = sxNode
        return scheduledTransactionRow(sxNode) ?: throw BookFormatException("failed to build scheduled transaction")
    }

    fun updateScheduledTransaction(
        guid: Guid,
        name: String? = null,
        enabled: Boolean? = null,
        autoCreate: Boolean? = null,
        autoCreateNotify: Boolean? = null,
        advanceCreateDays: Int? = null,
        advanceRemindDays: Int? = null,
    ): XmlScheduledTransaction {
        val node = scheduledTransactionIndex[guid]
            ?: throw BookFormatException("unknown scheduled transaction ${guid.raw}")
        name?.let { setChildText(node, "name", "sx:name", it) }
        enabled?.let { setChildText(node, "enabled", "sx:enabled", if (it) "y" else "n") }
        autoCreate?.let { setChildText(node, "autoCreate", "sx:autoCreate", if (it) "y" else "n") }
        autoCreateNotify?.let { setChildText(node, "autoCreateNotify", "sx:autoCreateNotify", if (it) "y" else "n") }
        advanceCreateDays?.let { setChildText(node, "advanceCreateDays", "sx:advanceCreateDays", it.toString()) }
        advanceRemindDays?.let { setChildText(node, "advanceRemindDays", "sx:advanceRemindDays", it.toString()) }
        return scheduledTransactionRow(node)
            ?: throw BookFormatException("failed to read scheduled transaction ${guid.raw}")
    }

    /** Also removes its template account and template transaction. */
    fun deleteScheduledTransaction(guid: Guid) {
        val node = scheduledTransactionIndex[guid] ?: return
        val templateAccountGuid = guidValue(node, "templ-acct")
        val templateTransactionsNode = findTemplateTransactionsNode()
        if (templateTransactionsNode != null && templateAccountGuid != null) {
            findTemplateTransactionNode(templateAccountGuid)?.detach()
            children(templateTransactionsNode, "account")
                .firstOrNull { guidValue(it, "id") == templateAccountGuid }
                ?.detach()
        }
        node.detach()
        scheduledTransactionIndex.remove(guid)
    }

    /**
     * Every enabled schedule's due instances as of `asOf`, classified by
     * what should happen to them -- this is a pure read, it never posts or
     * mutates anything; the caller is responsible for calling
     * [postScheduledInstance] for every `AUTO_POST`/`AUTO_POST_NOTIFY`
     * result and surfacing `NEEDS_APPROVAL`/`REMINDER` ones for the owner
     * to act on.
     */
    fun dueScheduledInstances(asOf: String): List<DueScheduledInstance> {
        val results = mutableListOf<DueScheduledInstance>()
        for (sx in scheduledTransactions()) {
            if (!sx.enabled) continue
            val occursEnd = sx.end as? ScheduledEnd.Occurrences
            if (occursEnd != null && sx.instanceCount >= occursEnd.count) continue
            val createBoundary = addDays(asOf, sx.advanceCreateDays)
            val remindBoundary = addDays(createBoundary, sx.advanceRemindDays)
            var current = sx.lastOccurred ?: "0001-01-01"
            for (guard in 0 until 1000) {
                val next = nextInstance(sx.recurrence, current) ?: break
                val dateEnd = sx.end as? ScheduledEnd.OnDate
                if (dateEnd != null && next > dateEnd.date) break
                if (next > remindBoundary) break
                val action = if (next <= createBoundary) {
                    if (sx.autoCreate) {
                        if (sx.autoCreateNotify) DueInstanceAction.AUTO_POST_NOTIFY else DueInstanceAction.AUTO_POST
                    } else {
                        DueInstanceAction.NEEDS_APPROVAL
                    }
                } else {
                    DueInstanceAction.REMINDER
                }
                results += DueScheduledInstance(sx.guid, sx.name, next, action)
                if (action == DueInstanceAction.REMINDER) break
                current = next
            }
        }
        return results
    }

    /** Posts a real transaction from the template and advances `sx:last`. */
    fun postScheduledInstance(
        scheduledTransactionGuid: Guid,
        date: String,
        transactionGuid: Guid,
        splitGuids: List<Guid>,
    ): SnapshotTransaction {
        val sx = scheduledTransaction(scheduledTransactionGuid)
            ?: throw BookFormatException("unknown scheduled transaction ${scheduledTransactionGuid.raw}")
        val result = postTransaction(
            guid = transactionGuid,
            date = date,
            description = sx.description,
            splits = sx.templateSplits.mapIndexed { index, split ->
                Triple(split.accountGuid, split.value, splitGuids[index])
            },
        )
        advanceScheduledTransaction(scheduledTransactionGuid, date)
        return result
    }

    /** Advances `sx:last` past this instance without posting anything -- Milestone 1's "skip". */
    fun skipScheduledInstance(scheduledTransactionGuid: Guid, date: String) {
        if (!scheduledTransactionIndex.containsKey(scheduledTransactionGuid)) {
            throw BookFormatException("unknown scheduled transaction ${scheduledTransactionGuid.raw}")
        }
        advanceScheduledTransaction(scheduledTransactionGuid, date)
    }

    private fun advanceScheduledTransaction(guid: Guid, date: String) {
        val node = scheduledTransactionIndex[guid] ?: return
        val existingLast = firstChild(node, "last")
        if (existingLast != null) {
            val gdate = firstChild(existingLast, "gdate")
            if (gdate != null) gdate.text = date else existingLast.addChild(leaf("gdate", date))
        } else {
            val startNode = firstChild(node, "start")
            val insertAt = startNode?.indexInParent()?.plus(1) ?: node.children.size
            node.insertChild(gdateNode("sx:last", date), insertAt)
        }
        val instanceCount = firstChild(node, "instanceCount")?.text?.toIntOrNull() ?: 0
        setChildText(node, "instanceCount", "sx:instanceCount", (instanceCount + 1).toString())
    }

    private fun findTemplateTransactionsNode(): XmlNode? = firstChild(book, "template-transactions")

    private fun findOrCreateTemplateTransactionsNode(): XmlNode {
        findTemplateTransactionsNode()?.let { return it }
        val created = XmlNode("gnc:template-transactions")
        book.addChild(created)
        return created
    }

    /**
     * The single, book-wide hidden "Template Root" account every scheduled
     * transaction's own template account lives under, matching desktop
     * GnuCash's `gnc_book_get_template_root`. Created the first time any
     * scheduled transaction is made; `templateRootGuid` is only used then.
     */
    private fun findOrCreateTemplateRootNode(templateTransactionsNode: XmlNode, templateRootGuid: Guid?): XmlNode {
        val existing = children(templateTransactionsNode, "account")
            .firstOrNull { firstChild(it, "type")?.text == "ROOT" }
        if (existing != null) return existing
        val guid = templateRootGuid ?: throw BookFormatException(
            "creating the first scheduled transaction in this book requires a template-root id",
        )
        val created = newTemplateAccountNode(guid, "Template Root", "ROOT", null)
        templateTransactionsNode.addChild(created)
        return created
    }

    /** Named after its own guid, like real GnuCash -- never shown to a user. */
    private fun createTemplateAccountNode(guid: Guid, parentGuid: Guid): XmlNode =
        newTemplateAccountNode(guid, guid.raw, "BANK", parentGuid)

    /**
     * Shared builder for the Template Root and every per-schedule template
     * account: commodity space/id `"template"`/`"template"`, scu `1` --
     * distinct from `accountNode()`, which always hardcodes a `CURRENCY`
     * commodity.
     */
    private fun newTemplateAccountNode(guid: Guid, name: String, type: String, parentGuid: Guid?): XmlNode {
        val node = XmlNode("gnc:account")
        node.attributes["version"] = "2.0.0"
        node.addChild(leaf("act:name", name))
        node.addChild(leaf("act:id", guid.raw, "type" to "guid"))
        node.addChild(leaf("act:type", type))
        val commodity = XmlNode("act:commodity")
        commodity.addChild(leaf("cmdty:space", "template"))
        commodity.addChild(leaf("cmdty:id", "template"))
        node.addChild(commodity)
        node.addChild(leaf("act:commodity-scu", "1"))
        parentGuid?.let { node.addChild(leaf("act:parent", it.raw, "type" to "guid")) }
        return node
    }

    private fun findTemplateTransactionNode(templateAccountGuid: Guid): XmlNode? {
        val templateTransactionsNode = findTemplateTransactionsNode() ?: return null
        return children(templateTransactionsNode, "transaction").firstOrNull { transactionNode ->
            val splitsContainer = firstChild(transactionNode, "splits") ?: transactionNode
            children(splitsContainer, "split").any { guidValue(it, "account") == templateAccountGuid }
        }
    }

    /** Matches an existing `CURRENCY` commodity's own fraction; falls back to the conventional 100 (cents). */
    private fun currencyFraction(currencyCode: String): Long {
        val match = children(book, "commodity").firstOrNull {
            firstChild(it, "space")?.text == "CURRENCY" && firstChild(it, "id")?.text == currencyCode
        }
        return match?.let { firstChild(it, "fraction")?.text?.toLongOrNull() } ?: 100L
    }

    private fun gdateNode(tag: String, date: String): XmlNode {
        val node = XmlNode(tag)
        node.addChild(leaf("gdate", date))
        return node
    }

    private fun readGdate(parent: XmlNode, tag: String): String? {
        val wrapper = firstChild(parent, tag) ?: return null
        return firstChild(wrapper, "gdate")?.text
    }

    /** A KVP frame value: `<slot:value type="frame">` wrapping its own nested `<slot>` entries. */
    private fun frameSlotValue(entries: List<Triple<String, String, String>>): XmlNode {
        val value = XmlNode("slot:value")
        value.attributes["type"] = "frame"
        for ((key, type, text) in entries) {
            val slot = XmlNode("slot")
            slot.addChild(leaf("slot:key", key))
            slot.addChild(leaf("slot:value", text, "type" to type))
            value.addChild(slot)
        }
        return value
    }

    /** Reads a nested KVP frame slot (e.g. `sched-xaction`) as a flat key -> text map. */
    private fun readFrameSlotEntries(owner: XmlNode, frameKey: String): Map<String, String> {
        val slots = firstChild(owner, "slots") ?: return emptyMap()
        val frameSlot = children(slots, "slot").firstOrNull { firstChild(it, "key")?.text == frameKey }
            ?: return emptyMap()
        val frameValue = firstChild(frameSlot, "value") ?: return emptyMap()
        val entries = mutableMapOf<String, String>()
        for (entry in children(frameValue, "slot")) {
            val key = firstChild(entry, "key")?.text
            val entryValue = firstChild(entry, "value")?.text
            if (key != null && entryValue != null) entries[key] = entryValue
        }
        return entries
    }

    // MARK: - Customers (GncCustomer)

    fun customers(): List<XmlCustomer> = customerIndex.values.mapNotNull(::customerRow)

    fun customer(guid: Guid): XmlCustomer? = customerIndex[guid]?.let(::customerRow)

    private fun customerRow(node: XmlNode): XmlCustomer? {
        val guid = guidValue(node, "guid") ?: return null
        val address = firstChild(node, "addr")
        fun addressField(name: String): String = address?.let { firstChild(it, name)?.text } ?: ""
        return XmlCustomer(
            guid = guid,
            name = firstChild(node, "name")?.text ?: "",
            customerNumber = firstChild(node, "id")?.text ?: "",
            addressName = addressField("name"),
            addressLine1 = addressField("addr1"),
            addressLine2 = addressField("addr2"),
            phone = addressField("phone"),
            email = addressField("email"),
            notes = firstChild(node, "notes")?.text ?: "",
            active = firstChild(node, "active")?.text != "0",
        )
    }

    fun createCustomer(guid: Guid, draft: CustomerDraft, currencyCode: String): XmlCustomer {
        if (customerIndex.containsKey(guid)) throw BookFormatException("duplicate customer ${guid.raw}")
        val node = customerNode(guid, draft, currencyCode)
        book.addChild(node)
        customerIndex[guid] = node
        return customerRow(node)!!
    }

    fun updateCustomer(
        guid: Guid,
        name: String? = null,
        customerNumber: String? = null,
        addressName: String? = null,
        addressLine1: String? = null,
        addressLine2: String? = null,
        phone: String? = null,
        email: String? = null,
        notes: String? = null,
        active: Boolean? = null,
    ): XmlCustomer {
        val node = customerIndex[guid] ?: throw BookFormatException("unknown customer ${guid.raw}")
        name?.let { setChildText(node, "name", "cust:name", it) }
        customerNumber?.let { setChildText(node, "id", "cust:id", it) }
        notes?.let { setChildText(node, "notes", "cust:notes", it) }
        active?.let { setChildText(node, "active", "cust:active", if (it) "1" else "0") }
        if (addressName != null || addressLine1 != null || addressLine2 != null || phone != null || email != null) {
            var address = firstChild(node, "addr")
            if (address == null) {
                address = XmlNode("cust:addr")
                address.attributes["version"] = "2.0.0"
                node.addChild(address)
            }
            addressName?.let { setChildText(address, "name", "addr:name", it) }
            addressLine1?.let { setChildText(address, "addr1", "addr:addr1", it) }
            addressLine2?.let { setChildText(address, "addr2", "addr:addr2", it) }
            phone?.let { setChildText(address, "phone", "addr:phone", it) }
            email?.let { setChildText(address, "email", "addr:email", it) }
        }
        return customerRow(node)!!
    }

    fun deleteCustomer(guid: Guid) {
        val node = customerIndex[guid] ?: return
        node.detach()
        customerIndex.remove(guid)
    }

    fun customerInUse(guid: Guid): Boolean = invoices().any { it.customerGuid == guid }

    // MARK: - Invoices and entries (GncInvoice / GncEntry)

    fun invoices(): List<XmlInvoice> =
        invoiceIndex.values.filter { ownerType(it) != "gncVendor" }.mapNotNull(::invoiceRow)

    fun invoice(guid: Guid): XmlInvoice? {
        val node = invoiceIndex[guid] ?: return null
        if (ownerType(node) == "gncVendor") return null
        return invoiceRow(node)
    }

    private fun invoiceRow(node: XmlNode): XmlInvoice? {
        val guid = guidValue(node, "guid") ?: return null
        val owner = firstChild(node, "owner")
        return XmlInvoice(
            guid = guid,
            invoiceNumber = firstChild(node, "id")?.text ?: "",
            customerGuid = owner?.let { guidValue(it, "id") },
            dateOpened = firstChild(node, "opened")?.let { firstChild(it, "date")?.text } ?: "",
            datePosted = firstChild(node, "posted")?.let { firstChild(it, "date")?.text },
            notes = firstChild(node, "notes")?.text ?: "",
            active = firstChild(node, "active")?.text != "0",
            postedTransactionGuid = guidValue(node, "posttxn"),
            receivableAccountGuid = guidValue(node, "postacc"),
        )
    }

    /** Every line on one invoice, oldest first. */
    fun invoiceEntries(invoiceGuid: Guid): List<XmlInvoiceEntry> =
        children(book, "GncEntry").mapNotNull(::entryRow)
            .filter { it.invoiceGuid == invoiceGuid }
            .sortedWith(compareBy({ it.date }, { it.guid.raw }))

    private fun entryRow(node: XmlNode): XmlInvoiceEntry? {
        val guid = guidValue(node, "guid") ?: return null
        val invoiceGuid = guidValue(node, "invoice") ?: return null
        val incomeAccountGuid = guidValue(node, "i-acct") ?: return null
        val date = firstChild(node, "date")?.let { firstChild(it, "date")?.text } ?: ""
        val description = firstChild(node, "description")?.text ?: ""
        val quantity = firstChild(node, "qty")?.text?.let(::rational) ?: return null
        val unitPrice = firstChild(node, "i-price")?.text?.let(::rational) ?: return null
        return XmlInvoiceEntry(guid, invoiceGuid, date, description, quantity, unitPrice, incomeAccountGuid)
    }

    fun createInvoice(
        guid: Guid,
        customerGuid: Guid,
        invoiceNumber: String,
        dateOpened: String,
        notes: String = "",
        currencyCode: String,
    ): XmlInvoice {
        if (invoiceIndex.containsKey(guid)) throw BookFormatException("duplicate invoice ${guid.raw}")
        val owner = XmlNode("invoice:owner")
        owner.attributes["version"] = "2.0.0"
        owner.addChild(leaf("owner:type", "gncCustomer"))
        owner.addChild(leaf("owner:id", customerGuid.raw, "type" to "guid"))

        val node = XmlNode("gnc:GncInvoice")
        node.attributes["version"] = "2.0.0"
        node.addChild(leaf("invoice:guid", guid.raw, "type" to "guid"))
        node.addChild(leaf("invoice:id", invoiceNumber))
        node.addChild(owner)
        val opened = XmlNode("invoice:opened")
        opened.addChild(leaf("ts:date", dateOpened))
        node.addChild(opened)
        node.addChild(leaf("invoice:notes", notes))
        node.addChild(leaf("invoice:active", "1"))
        val currency = XmlNode("invoice:currency")
        currency.addChild(leaf("cmdty:space", "CURRENCY"))
        currency.addChild(leaf("cmdty:id", currencyCode))
        node.addChild(currency)

        book.addChild(node)
        invoiceIndex[guid] = node
        return invoiceRow(node)!!
    }

    /** Drafts only: a posted invoice is part of permanent ledger history. */
    fun deleteInvoice(guid: Guid) {
        val node = invoiceIndex[guid] ?: return
        for (entry in children(book, "GncEntry")) {
            if (guidValue(entry, "invoice") == guid) entry.detach()
        }
        node.detach()
        invoiceIndex.remove(guid)
    }

    fun addInvoiceEntry(
        guid: Guid,
        invoiceGuid: Guid,
        date: String,
        description: String,
        quantity: Money,
        unitPrice: Money,
        incomeAccountGuid: Guid,
    ): XmlInvoiceEntry {
        val node = XmlNode("gnc:GncEntry")
        node.attributes["version"] = "2.0.0"
        node.addChild(leaf("entry:guid", guid.raw, "type" to "guid"))
        val dateNode = XmlNode("entry:date")
        dateNode.addChild(leaf("ts:date", date))
        node.addChild(dateNode)
        val enteredNode = XmlNode("entry:entered")
        enteredNode.addChild(leaf("ts:date", date))
        node.addChild(enteredNode)
        node.addChild(leaf("entry:description", description))
        node.addChild(leaf("entry:qty", "${quantity.num}/${quantity.denom}"))
        node.addChild(leaf("entry:i-acct", incomeAccountGuid.raw, "type" to "guid"))
        node.addChild(leaf("entry:i-price", "${unitPrice.num}/${unitPrice.denom}"))
        node.addChild(leaf("entry:i-discount", "0/100"))
        node.addChild(leaf("entry:invoice", invoiceGuid.raw, "type" to "guid"))
        node.addChild(leaf("entry:i-disc-type", "PERCENT"))
        node.addChild(leaf("entry:i-disc-how", "PRETAX"))
        node.addChild(leaf("entry:i-taxable", "0"))
        node.addChild(leaf("entry:i-taxincluded", "0"))
        book.addChild(node)
        return entryRow(node) ?: throw BookFormatException("failed to build invoice entry")
    }

    fun deleteInvoiceEntry(guid: Guid) {
        children(book, "GncEntry").firstOrNull { guidValue(it, "guid") == guid }?.detach()
    }

    /**
     * Marks a draft invoice posted: stamps the posted date, the real
     * ledger transaction behind it, and the receivable account used --
     * matching desktop GnuCash's own "Post Invoice" action. The caller
     * creates that transaction separately via `postTransaction`.
     */
    fun markInvoicePosted(guid: Guid, postedDate: String, transactionGuid: Guid, receivableAccountGuid: Guid) {
        val node = invoiceIndex[guid] ?: throw BookFormatException("unknown invoice ${guid.raw}")
        val posted = XmlNode("invoice:posted")
        posted.addChild(leaf("ts:date", postedDate))
        node.addChild(posted)
        node.addChild(leaf("invoice:posttxn", transactionGuid.raw, "type" to "guid"))
        node.addChild(leaf("invoice:postacc", receivableAccountGuid.raw, "type" to "guid"))
    }

    // MARK: - Vendors (GncVendor)

    fun vendors(): List<XmlVendor> = vendorIndex.values.mapNotNull(::vendorRow)

    fun vendor(guid: Guid): XmlVendor? = vendorIndex[guid]?.let(::vendorRow)

    private fun vendorRow(node: XmlNode): XmlVendor? {
        val guid = guidValue(node, "guid") ?: return null
        val address = firstChild(node, "addr")
        fun addressField(name: String): String = address?.let { firstChild(it, name)?.text } ?: ""
        return XmlVendor(
            guid = guid,
            name = firstChild(node, "name")?.text ?: "",
            vendorNumber = firstChild(node, "id")?.text ?: "",
            addressLine1 = addressField("addr1"),
            phone = addressField("phone"),
            email = addressField("email"),
            notes = firstChild(node, "notes")?.text ?: "",
            active = firstChild(node, "active")?.text != "0",
        )
    }

    fun createVendor(guid: Guid, draft: VendorDraft, currencyCode: String): XmlVendor {
        if (vendorIndex.containsKey(guid)) throw BookFormatException("duplicate vendor ${guid.raw}")
        val node = vendorNode(guid, draft, currencyCode)
        book.addChild(node)
        vendorIndex[guid] = node
        return vendorRow(node)!!
    }

    fun updateVendor(
        guid: Guid,
        name: String? = null,
        vendorNumber: String? = null,
        addressLine1: String? = null,
        phone: String? = null,
        email: String? = null,
        notes: String? = null,
        active: Boolean? = null,
    ): XmlVendor {
        val node = vendorIndex[guid] ?: throw BookFormatException("unknown vendor ${guid.raw}")
        name?.let { setChildText(node, "name", "vendor:name", it) }
        vendorNumber?.let { setChildText(node, "id", "vendor:id", it) }
        notes?.let { setChildText(node, "notes", "vendor:notes", it) }
        active?.let { setChildText(node, "active", "vendor:active", if (it) "1" else "0") }
        if (addressLine1 != null || phone != null || email != null) {
            var address = firstChild(node, "addr")
            if (address == null) {
                address = XmlNode("vendor:addr")
                address.attributes["version"] = "2.0.0"
                node.addChild(address)
            }
            addressLine1?.let { setChildText(address, "addr1", "addr:addr1", it) }
            phone?.let { setChildText(address, "phone", "addr:phone", it) }
            email?.let { setChildText(address, "email", "addr:email", it) }
        }
        return vendorRow(node)!!
    }

    fun deleteVendor(guid: Guid) {
        val node = vendorIndex[guid] ?: return
        node.detach()
        vendorIndex.remove(guid)
    }

    fun vendorInUse(guid: Guid): Boolean = bills().any { it.vendorGuid == guid }

    // MARK: - Bills and bill entries (GncInvoice / GncEntry, vendor-owned)

    fun bills(): List<XmlBill> =
        invoiceIndex.values.filter { ownerType(it) == "gncVendor" }.mapNotNull(::billRow)

    fun bill(guid: Guid): XmlBill? {
        val node = invoiceIndex[guid] ?: return null
        if (ownerType(node) != "gncVendor") return null
        return billRow(node)
    }

    private fun billRow(node: XmlNode): XmlBill? {
        val guid = guidValue(node, "guid") ?: return null
        val owner = firstChild(node, "owner")
        return XmlBill(
            guid = guid,
            billNumber = firstChild(node, "id")?.text ?: "",
            vendorGuid = owner?.let { guidValue(it, "id") },
            dateOpened = firstChild(node, "opened")?.let { firstChild(it, "date")?.text } ?: "",
            datePosted = firstChild(node, "posted")?.let { firstChild(it, "date")?.text },
            notes = firstChild(node, "notes")?.text ?: "",
            active = firstChild(node, "active")?.text != "0",
            postedTransactionGuid = guidValue(node, "posttxn"),
            payableAccountGuid = guidValue(node, "postacc"),
        )
    }

    /** Every line on one bill, oldest first. */
    fun billEntries(billGuid: Guid): List<XmlBillEntry> =
        children(book, "GncEntry").mapNotNull(::billEntryRow)
            .filter { it.billGuid == billGuid }
            .sortedWith(compareBy({ it.date }, { it.guid.raw }))

    private fun billEntryRow(node: XmlNode): XmlBillEntry? {
        val guid = guidValue(node, "guid") ?: return null
        val billGuid = guidValue(node, "bill") ?: return null
        val expenseAccountGuid = guidValue(node, "b-acct") ?: return null
        val date = firstChild(node, "date")?.let { firstChild(it, "date")?.text } ?: ""
        val description = firstChild(node, "description")?.text ?: ""
        val quantity = firstChild(node, "qty")?.text?.let(::rational) ?: return null
        val unitPrice = firstChild(node, "b-price")?.text?.let(::rational) ?: return null
        return XmlBillEntry(guid, billGuid, date, description, quantity, unitPrice, expenseAccountGuid)
    }

    fun createBill(
        guid: Guid,
        vendorGuid: Guid,
        billNumber: String,
        dateOpened: String,
        notes: String = "",
        currencyCode: String,
    ): XmlBill {
        if (invoiceIndex.containsKey(guid)) throw BookFormatException("duplicate bill ${guid.raw}")
        val owner = XmlNode("invoice:owner")
        owner.attributes["version"] = "2.0.0"
        owner.addChild(leaf("owner:type", "gncVendor"))
        owner.addChild(leaf("owner:id", vendorGuid.raw, "type" to "guid"))

        val node = XmlNode("gnc:GncInvoice")
        node.attributes["version"] = "2.0.0"
        node.addChild(leaf("invoice:guid", guid.raw, "type" to "guid"))
        node.addChild(leaf("invoice:id", billNumber))
        node.addChild(owner)
        val opened = XmlNode("invoice:opened")
        opened.addChild(leaf("ts:date", dateOpened))
        node.addChild(opened)
        node.addChild(leaf("invoice:notes", notes))
        node.addChild(leaf("invoice:active", "1"))
        val currency = XmlNode("invoice:currency")
        currency.addChild(leaf("cmdty:space", "CURRENCY"))
        currency.addChild(leaf("cmdty:id", currencyCode))
        node.addChild(currency)

        book.addChild(node)
        invoiceIndex[guid] = node
        return billRow(node)!!
    }

    /** Drafts only: a posted bill is part of permanent ledger history. */
    fun deleteBill(guid: Guid) {
        val node = invoiceIndex[guid] ?: return
        for (entry in children(book, "GncEntry")) {
            if (guidValue(entry, "bill") == guid) entry.detach()
        }
        node.detach()
        invoiceIndex.remove(guid)
    }

    fun addBillEntry(
        guid: Guid,
        billGuid: Guid,
        date: String,
        description: String,
        quantity: Money,
        unitPrice: Money,
        expenseAccountGuid: Guid,
    ): XmlBillEntry {
        val node = XmlNode("gnc:GncEntry")
        node.attributes["version"] = "2.0.0"
        node.addChild(leaf("entry:guid", guid.raw, "type" to "guid"))
        val dateNode = XmlNode("entry:date")
        dateNode.addChild(leaf("ts:date", date))
        node.addChild(dateNode)
        val enteredNode = XmlNode("entry:entered")
        enteredNode.addChild(leaf("ts:date", date))
        node.addChild(enteredNode)
        node.addChild(leaf("entry:description", description))
        node.addChild(leaf("entry:qty", "${quantity.num}/${quantity.denom}"))
        node.addChild(leaf("entry:b-acct", expenseAccountGuid.raw, "type" to "guid"))
        node.addChild(leaf("entry:b-price", "${unitPrice.num}/${unitPrice.denom}"))
        node.addChild(leaf("entry:bill", billGuid.raw, "type" to "guid"))
        node.addChild(leaf("entry:billable", "0"))
        node.addChild(leaf("entry:b-taxable", "0"))
        node.addChild(leaf("entry:b-taxincluded", "0"))
        book.addChild(node)
        return billEntryRow(node) ?: throw BookFormatException("failed to build bill entry")
    }

    fun deleteBillEntry(guid: Guid) {
        children(book, "GncEntry").firstOrNull { guidValue(it, "guid") == guid }?.detach()
    }

    /**
     * Marks a draft bill posted: stamps the posted date, the real
     * ledger transaction behind it, and the payable account used --
     * matching desktop GnuCash's own "Post Bill" action. The caller
     * creates that transaction separately via `postTransaction`.
     */
    fun markBillPosted(guid: Guid, postedDate: String, transactionGuid: Guid, payableAccountGuid: Guid) {
        val node = invoiceIndex[guid] ?: throw BookFormatException("unknown bill ${guid.raw}")
        val posted = XmlNode("invoice:posted")
        posted.addChild(leaf("ts:date", postedDate))
        node.addChild(posted)
        node.addChild(leaf("invoice:posttxn", transactionGuid.raw, "type" to "guid"))
        node.addChild(leaf("invoice:postacc", payableAccountGuid.raw, "type" to "guid"))
    }

    // MARK: - Tree helpers

    private fun localName(name: String): String {
        val colon = name.lastIndexOf(':')
        return if (colon >= 0) name.substring(colon + 1) else name
    }

    private fun children(node: XmlNode, name: String): List<XmlNode> = node.children.filter { localName(it.name) == name }

    private fun firstChild(node: XmlNode, name: String): XmlNode? = children(node, name).firstOrNull()

    private fun guidValue(node: XmlNode, name: String): Guid? = firstChild(node, name)?.let { guidOrNull(it.text) }

    private fun guidOrNull(value: String): Guid? = if (Guid.isValid(value)) Guid.parse(value) else null

    private fun rational(value: String): Money? {
        val pieces = value.split('/')
        if (pieces.size != 2) return null
        val num = pieces[0].toLongOrNull() ?: return null
        val denom = pieces[1].toLongOrNull() ?: return null
        return try {
            Money(num, denom)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

/** The book's default currency commodity. */
data class BookCurrency(val code: String, val fullName: String, val fraction: Long)

/**
 * A hand-rolled XML element/text node, built once from a StAX parse and
 * mutated in place. `.text` holds decoded (plain) content -- StAX already
 * resolves entities on read -- so it is escaped exactly once, on write.
 */
private class XmlNode(val name: String) {
    val attributes: MutableMap<String, String> = LinkedHashMap()
    val children: MutableList<XmlNode> = mutableListOf()
    var text: String = ""
    var parent: XmlNode? = null

    fun addChild(child: XmlNode) {
        child.parent = this
        children += child
    }

    fun insertChild(child: XmlNode, index: Int) {
        child.parent = this
        children.add(index.coerceIn(0, children.size), child)
    }

    fun detach() {
        parent?.children?.remove(this)
        parent = null
    }

    fun indexInParent(): Int? = parent?.children?.indexOf(this)
}

private fun findDescendant(node: XmlNode, name: String): XmlNode? {
    for (child in node.children) {
        val colon = child.name.lastIndexOf(':')
        val local = if (colon >= 0) child.name.substring(colon + 1) else child.name
        if (local == name) return child
        findDescendant(child, name)?.let { return it }
    }
    return null
}

/**
 * A deliberately small StAX-based tree builder: every element, attribute,
 * namespace declaration and text run becomes a generic node, so content
 * this engine does not model (prices, lots, scheduled transactions, unknown
 * slots) is retained rather than dropped.
 */
private fun parseTree(stream: InputStream): XmlNode {
    val factory = XMLInputFactory.newInstance()
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
    val reader = factory.createXMLStreamReader(stream)
    try {
        val root = XmlNode("#document")
        val stack = mutableListOf(root)
        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val prefix = reader.prefix
                    val name = if (prefix.isNullOrEmpty()) reader.localName else "$prefix:${reader.localName}"
                    val node = XmlNode(name)
                    for (index in 0 until reader.attributeCount) {
                        val attrPrefix = reader.getAttributePrefix(index)
                        val attrName = if (attrPrefix.isNullOrEmpty()) {
                            reader.getAttributeLocalName(index)
                        } else {
                            "$attrPrefix:${reader.getAttributeLocalName(index)}"
                        }
                        node.attributes[attrName] = reader.getAttributeValue(index)
                    }
                    for (index in 0 until reader.namespaceCount) {
                        val namespacePrefix = reader.getNamespacePrefix(index)
                        val key = if (namespacePrefix.isNullOrEmpty()) "xmlns" else "xmlns:$namespacePrefix"
                        node.attributes[key] = reader.getNamespaceURI(index)
                    }
                    stack.last().addChild(node)
                    stack.add(node)
                }

                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE ->
                    stack.last().text += reader.text

                XMLStreamConstants.END_ELEMENT -> stack.removeAt(stack.lastIndex)

                else -> Unit
            }
        }
        return root
    } finally {
        reader.close()
    }
}

private fun serializeNode(node: XmlNode, out: StringBuilder) {
    out.append('<').append(node.name)
    for ((key, value) in node.attributes) {
        out.append(' ').append(key).append("=\"").append(escapeAttribute(value)).append('"')
    }
    if (node.children.isNotEmpty()) {
        out.append('>')
        for (child in node.children) serializeNode(child, out)
        out.append("</").append(node.name).append('>')
    } else if (node.text.isEmpty()) {
        out.append("/>")
    } else {
        out.append('>').append(escapeText(node.text)).append("</").append(node.name).append('>')
    }
}

private fun escapeText(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")

private fun escapeAttribute(value: String): String = escapeText(value).replace("\"", "&quot;")
