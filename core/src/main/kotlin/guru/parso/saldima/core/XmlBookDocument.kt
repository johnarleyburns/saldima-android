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

    init {
        reindex()
    }

    private fun reindex() {
        accountIndex.clear()
        transactionIndex.clear()
        for (node in children(book, "account")) {
            guidValue(node, "id")?.let { accountIndex[it] = node }
        }
        for (node in children(book, "transaction")) {
            guidValue(node, "id")?.let { transactionIndex[it] = node }
        }
    }

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
        )

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
            SnapshotSplit(account, value)
        }
        return SnapshotTransaction(guid, date, description, currency, splits)
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
