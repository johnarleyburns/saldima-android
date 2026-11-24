// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Writes a GnuCash 5.x XML book (gzip by default). Mirrors the iOS and web
 * writers: only modelled fields, exact rational amounts, structural ROOT first.
 */
object XmlBookWriter {
    fun xml(snapshot: BookSnapshot, ids: IdGenerator): String {
        val currency = snapshot.currencyCode ?: "USD"
        val fraction = snapshot.fraction ?: 100L
        val accounts = orderedAccounts(snapshot)
        val xml = StringBuilder()
        xml.appendLine("<?xml version=\"1.0\" encoding=\"utf-8\" ?>")
        xml.appendLine("<gnc-v2")
        xml.appendLine("     xmlns:gnc=\"http://www.gnucash.org/XML/gnc\"")
        xml.appendLine("     xmlns:act=\"http://www.gnucash.org/XML/act\"")
        xml.appendLine("     xmlns:book=\"http://www.gnucash.org/XML/book\"")
        xml.appendLine("     xmlns:cd=\"http://www.gnucash.org/XML/cd\"")
        xml.appendLine("     xmlns:cmdty=\"http://www.gnucash.org/XML/cmdty\"")
        xml.appendLine("     xmlns:slot=\"http://www.gnucash.org/XML/slot\"")
        xml.appendLine("     xmlns:split=\"http://www.gnucash.org/XML/split\"")
        xml.appendLine("     xmlns:trn=\"http://www.gnucash.org/XML/trn\"")
        xml.appendLine("     xmlns:ts=\"http://www.gnucash.org/XML/ts\">")
        xml.appendLine("<gnc:count-data cd:type=\"book\">1</gnc:count-data>")
        xml.appendLine("<gnc:book version=\"2.0.0\">")
        xml.appendLine("<book:id type=\"guid\">${escape(snapshot.bookGuid.raw)}</book:id>")
        xml.appendLine("<gnc:count-data cd:type=\"commodity\">2</gnc:count-data>")
        xml.appendLine("<gnc:count-data cd:type=\"account\">${accounts.size}</gnc:count-data>")
        xml.appendLine("<gnc:count-data cd:type=\"transaction\">${snapshot.transactions.size}</gnc:count-data>")
        xml.append(commodity(currency, fraction))
        xml.appendLine("<gnc:commodity version=\"2.0.0\">")
        xml.appendLine("  <cmdty:space>template</cmdty:space>")
        xml.appendLine("  <cmdty:id>template</cmdty:id>")
        xml.appendLine("  <cmdty:name>template</cmdty:name>")
        xml.appendLine("  <cmdty:xcode>template</cmdty:xcode>")
        xml.appendLine("  <cmdty:fraction>1</cmdty:fraction>")
        xml.appendLine("</gnc:commodity>")
        for (account in accounts) {
            xml.append(account(account, currency, fraction))
        }
        for (transaction in snapshot.transactions) {
            xml.append(transaction(transaction, currency, ids))
        }
        xml.appendLine("</gnc:book>")
        xml.appendLine("</gnc-v2>")
        return xml.toString()
    }

    fun gzip(snapshot: BookSnapshot, ids: IdGenerator): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { stream ->
            stream.write(xml(snapshot, ids).toByteArray(Charsets.UTF_8))
        }
        return output.toByteArray()
    }

    private fun commodity(currency: String, fraction: Long): String = buildString {
        appendLine("<gnc:commodity version=\"2.0.0\">")
        appendLine("  <cmdty:space>CURRENCY</cmdty:space>")
        appendLine("  <cmdty:id>${escape(currency)}</cmdty:id>")
        appendLine("  <cmdty:get_quotes/>")
        appendLine("  <cmdty:quote_source>currency</cmdty:quote_source>")
        appendLine("  <cmdty:quote_tz/>")
        appendLine("  <cmdty:fraction>$fraction</cmdty:fraction>")
        appendLine("</gnc:commodity>")
    }

    private fun account(account: SnapshotAccount, currency: String, fraction: Long): String =
        buildString {
            appendLine("<gnc:account version=\"2.0.0\">")
            appendLine("  <act:name>${escape(account.name)}</act:name>")
            appendLine("  <act:id type=\"guid\">${escape(account.guid.raw)}</act:id>")
            appendLine("  <act:type>${escape(account.type)}</act:type>")
            if (account.type != "ROOT") {
                appendLine("  <act:commodity>")
                appendLine("    <cmdty:space>CURRENCY</cmdty:space>")
                appendLine("    <cmdty:id>${escape(currency)}</cmdty:id>")
                appendLine("  </act:commodity>")
                appendLine("  <act:commodity-scu>$fraction</act:commodity-scu>")
            }
            account.parentGuid?.let {
                appendLine("  <act:parent type=\"guid\">${escape(it.raw)}</act:parent>")
            }
            if (account.hidden || account.placeholder) {
                appendLine("  <act:slots>")
                if (account.placeholder) appendLine(slot("placeholder", "true"))
                if (account.hidden) appendLine(slot("hidden", "true"))
                appendLine("  </act:slots>")
            }
            appendLine("  <act:code>${escape(account.code)}</act:code>")
            appendLine("  <act:description>${escape(account.description)}</act:description>")
            appendLine("</gnc:account>")
        }

    private fun slot(key: String, value: String): String =
        "<slot><slot:key>${escape(key)}</slot:key><slot:value type=\"string\">${escape(value)}</slot:value></slot>"

    private fun transaction(
        transaction: SnapshotTransaction,
        currency: String,
        ids: IdGenerator,
    ): String = buildString {
        appendLine("<gnc:transaction version=\"2.0.0\">")
        appendLine("  <trn:id type=\"guid\">${escape(transaction.guid.raw)}</trn:id>")
        appendLine("  <trn:currency>")
        appendLine("    <cmdty:space>CURRENCY</cmdty:space>")
        appendLine("    <cmdty:id>${escape(transaction.currencyCode ?: currency)}</cmdty:id>")
        appendLine("  </trn:currency>")
        val date = escape(transaction.date.take(10))
        appendLine("  <trn:date-posted><ts:date>$date 00:00:00 +0000</ts:date></trn:date-posted>")
        appendLine("  <trn:date-entered><ts:date>$date 00:00:00 +0000</ts:date></trn:date-entered>")
        appendLine("  <trn:description>${escape(transaction.description)}</trn:description>")
        appendLine("  <trn:splits>")
        for (split in transaction.splits) {
            appendLine("    <trn:split>")
            appendLine("      <split:id type=\"guid\">${ids.next().raw}</split:id>")
            appendLine("      <split:reconciled-state>n</split:reconciled-state>")
            appendLine("      <split:value>${split.value.num}/${split.value.denom}</split:value>")
            appendLine("      <split:quantity>${split.value.num}/${split.value.denom}</split:quantity>")
            appendLine("      <split:account type=\"guid\">${escape(split.accountGuid.raw)}</split:account>")
            appendLine("    </trn:split>")
        }
        appendLine("  </trn:splits>")
        appendLine("</gnc:transaction>")
    }

    /**
     * Parents must be written before children; GnuCash resolves the parent
     * while reading. The template root subtree is not part of the export.
     */
    private fun orderedAccounts(snapshot: BookSnapshot): List<SnapshotAccount> {
        val children = snapshot.accounts.groupBy { it.parentGuid }
        val excluded = mutableSetOf<Guid>()
        val templateRoot = snapshot.templateRootGuid
        if (templateRoot != null) {
            fun mark(guid: Guid) {
                if (!excluded.add(guid)) return
                children[guid]?.forEach { mark(it.guid) }
            }
            mark(templateRoot)
        }
        val remaining = snapshot.accounts.filterNot { it.guid in excluded }
        val byParent = remaining.groupBy { it.parentGuid }
        val result = mutableListOf<SnapshotAccount>()
        val visited = mutableSetOf<Guid>()
        fun visit(account: SnapshotAccount) {
            if (!visited.add(account.guid)) return
            result += account
            byParent[account.guid]?.sortedBy { it.name }?.forEach(::visit)
        }
        remaining
            .filter { it.type == "ROOT" || it.parentGuid == null }
            .sortedBy { it.type != "ROOT" }
            .forEach(::visit)
        remaining.forEach(::visit)
        return result
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
