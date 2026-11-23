// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import java.io.File
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/**
 * Reads GnuCash's XML backend (plain or gzip). Prefixes are fixed by the
 * GnuCash DTD (gnc, act, trn, split, cmdty), so local names plus the element
 * prefix keep the parser unambiguous.
 */
object XmlBookReader {
    fun read(file: File): BookSnapshot {
        val factory = XMLInputFactory.newInstance()
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false)
        val reader = factory.createXMLStreamReader(BookFiles.xmlStream(file))
        try {
            return parse(reader)
        } finally {
            reader.close()
        }
    }

    private class AccountBuilder {
        var guid: Guid? = null
        var name = ""
        var type = ""
        var parent: Guid? = null
        var commodity: String? = null
        var code = ""
        var description = ""
    }

    private class SplitBuilder {
        var account: Guid? = null
        var value: Money? = null
    }

    private class TransactionBuilder {
        var guid: Guid? = null
        var date = ""
        var description = ""
        var currency: String? = null
        val splits = mutableListOf<SnapshotSplit>()
    }

    private fun parse(reader: XMLStreamReader): BookSnapshot {
        val stack = mutableListOf<String>()
        val text = StringBuilder()
        var bookGuid: Guid? = null
        val accounts = mutableListOf<SnapshotAccount>()
        val transactions = mutableListOf<SnapshotTransaction>()
        var account: AccountBuilder? = null
        var transaction: TransactionBuilder? = null
        var split: SplitBuilder? = null

        fun textValue() = text.toString().trim()
        fun local(): String = reader.localName
        fun prefix(): String? = reader.prefix

        fun finishAccount(builder: AccountBuilder) {
            builder.guid?.let { guid ->
                accounts += SnapshotAccount(
                    guid = guid,
                    name = builder.name,
                    type = builder.type,
                    parentGuid = builder.parent,
                    commodityCode = builder.commodity,
                    code = builder.code,
                    description = builder.description,
                )
            }
            account = null
        }

        fun finishSplit(builder: SplitBuilder) {
            val guid = builder.account
            val value = builder.value
            if (guid != null && value != null) {
                transaction?.splits?.add(SnapshotSplit(guid, value))
            }
            split = null
        }

        fun finishTransaction(builder: TransactionBuilder) {
            builder.guid?.let { guid ->
                transactions += SnapshotTransaction(
                    guid = guid,
                    date = builder.date,
                    description = builder.description,
                    currencyCode = builder.currency,
                    splits = builder.splits.toList(),
                )
            }
            transaction = null
        }

        while (reader.hasNext()) {
            when (reader.next()) {
                XMLStreamConstants.START_ELEMENT -> {
                    val name = local()
                    stack += name
                    text.setLength(0)
                    when {
                        name == "account" && prefix() == "gnc" -> account = AccountBuilder()
                        name == "transaction" && prefix() == "gnc" -> transaction = TransactionBuilder()
                        name == "split" && prefix() == "trn" && transaction != null -> split = SplitBuilder()
                    }
                }

                XMLStreamConstants.CHARACTERS,
                XMLStreamConstants.CDATA,
                XMLStreamConstants.SPACE,
                -> text.append(reader.text)

                XMLStreamConstants.END_ELEMENT -> {
                    val name = local()
                    val parent = if (stack.size >= 2) stack[stack.size - 2] else ""
                    val value = textValue()
                    when {
                        name == "account" && account != null -> finishAccount(account!!)
                        name == "transaction" && transaction != null -> finishTransaction(transaction!!)
                        name == "split" && split != null -> finishSplit(split!!)
                        split != null -> when (name to parent) {
                            "account" to "split" -> split!!.account = guidOrNull(value)
                            "value" to "split" -> split!!.value = rational(value)
                        }
                        transaction != null -> when (name to parent) {
                            "id" to "transaction" -> transaction!!.guid = guidOrNull(value)
                            "date" to "date-posted" -> transaction!!.date = value.take(10)
                            "description" to "transaction" -> transaction!!.description = value
                            "id" to "currency" -> transaction!!.currency = value
                        }
                        account != null -> when (name to parent) {
                            "name" to "account" -> account!!.name = value
                            "id" to "account" -> account!!.guid = guidOrNull(value)
                            "type" to "account" -> account!!.type = value
                            "parent" to "account" -> account!!.parent = guidOrNull(value)
                            "id" to "commodity" -> account!!.commodity = value
                            "code" to "account" -> account!!.code = value
                            "description" to "account" -> account!!.description = value
                        }
                        name == "id" && parent == "book" -> guidOrNull(value)?.let { bookGuid = it }
                    }
                    stack.removeAt(stack.lastIndex)
                    text.setLength(0)
                }
            }
        }

        val snapshotBookGuid = bookGuid ?: throw BookFormatException("book has no GUID")
        if (accounts.isEmpty()) throw BookFormatException("book has no accounts")
        val fraction = transactions.firstOrNull()?.splits?.firstOrNull()?.value?.denom
            ?: accounts.firstOrNull { it.commodityCode != null }?.let { 100L }
        return BookSnapshot(
            bookGuid = snapshotBookGuid,
            currencyCode = transactions.firstOrNull()?.currencyCode
                ?: accounts.firstNotNullOfOrNull { it.commodityCode },
            fraction = fraction,
            accounts = accounts,
            transactions = transactions,
        )
    }

    private fun guidOrNull(value: String): Guid? =
        if (Guid.isValid(value)) Guid.parse(value) else null

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
