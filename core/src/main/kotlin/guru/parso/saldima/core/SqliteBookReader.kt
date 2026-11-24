// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/** The four queries a SQLite book needs; the app supplies android.database rows. */
interface SqliteRows {
    /** Column values by name, in query order. */
    fun query(sql: String): List<Map<String, String?>>
}

/**
 * Reads a GnuCash SQLite book through a tiny row port, so the mapping stays
 * pure JVM and testable without Android or a JDBC driver.
 */
object SqliteBookReader {
    fun read(rows: SqliteRows): BookSnapshot {
        val bookRow = rows.query("SELECT guid, root_account_guid, root_template_guid FROM books LIMIT 1")
            .firstOrNull() ?: throw BookFormatException("book has no books row")
        val bookGuid = bookRow["root_account_guid"]
            ?: throw BookFormatException("book has no root account")

        val commodityRows = rows.query("SELECT guid, mnemonic FROM commodities WHERE namespace = 'CURRENCY'")
        val currency = commodityRows.firstOrNull()?.get("mnemonic")

        val accounts = rows.query(
            "SELECT guid, name, account_type, parent_guid, commodity_guid, code, description FROM accounts",
        ).mapNotNull { row ->
            val guid = row["guid"] ?: return@mapNotNull null
            if (!Guid.isValid(guid)) return@mapNotNull null
            SnapshotAccount(
                guid = Guid.parse(guid),
                name = row["name"] ?: return@mapNotNull null,
                type = row["account_type"] ?: return@mapNotNull null,
                parentGuid = row["parent_guid"]?.takeIf { Guid.isValid(it) }?.let { Guid.parse(it) },
                commodityCode = row["commodity_guid"]?.let { commodityGuid ->
                    commodityRows.firstOrNull { it["guid"] == commodityGuid }?.get("mnemonic")
                },
                code = row["code"] ?: "",
                description = row["description"] ?: "",
            )
        }
        if (accounts.isEmpty()) throw BookFormatException("book has no accounts")

        val splitRows = rows.query(
            "SELECT tx_guid, account_guid, value_num, value_denom FROM splits",
        )
        val transactions = rows.query(
            "SELECT guid, post_date, description, currency_guid FROM transactions ORDER BY post_date",
        ).mapNotNull { row ->
            val guid = row["guid"] ?: return@mapNotNull null
            if (!Guid.isValid(guid)) return@mapNotNull null
            val splits = splitRows.filter { it["tx_guid"] == guid }.mapNotNull { split ->
                val account = split["account_guid"] ?: return@mapNotNull null
                val num = split["value_num"]?.toLongOrNull() ?: return@mapNotNull null
                val denom = split["value_denom"]?.toLongOrNull() ?: return@mapNotNull null
                SnapshotSplit(Guid.parse(account), Money(num, denom))
            }
            SnapshotTransaction(
                guid = Guid.parse(guid),
                date = (row["post_date"] ?: "").take(10),
                description = row["description"] ?: "",
                currencyCode = currency,
                splits = splits,
            )
        }

        return BookSnapshot(
            bookGuid = Guid.parse(bookGuid),
            currencyCode = currency,
            fraction = transactions.firstOrNull()?.splits?.firstOrNull()?.value?.denom,
            accounts = accounts,
            transactions = transactions,
        )
    }
}
