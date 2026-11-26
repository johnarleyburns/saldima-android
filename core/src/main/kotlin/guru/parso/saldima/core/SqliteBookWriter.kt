// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * The two write operations materializing a book needs; the app supplies
 * android.database.sqlite execution (`:core:test` uses a real JVM SQLite
 * engine, so this stays pure JVM and testable without Android or an
 * Android-specific driver). Each call is exactly one statement: Android's
 * `SQLiteDatabase.execSQL`/`insert` never run more than one per call.
 */
interface SqliteWrites {
    fun exec(sql: String)
    fun insert(sql: String, params: List<Any?>)
}

/**
 * Writes a fresh GnuCash 5.14 SQLite book, either empty (from a template, as
 * `:app` does today) or materialized from a `BookSnapshot` read from any
 * format (XML included) -- the same shape as the iOS track's `BookWriter`
 * plus `BookImporter`. No GnuCash source is copied; this is the observed
 * on-disk interface of GnuCash 5.14, the pinned oracle version.
 */
object SqliteBookWriter {
    const val GNUCASH_VERSION = 5_000_014L
    const val GNUCASH_RESAVE_VERSION = 19_920L

    fun createSchema(writes: SqliteWrites) {
        for (statement in SCHEMA) writes.exec(statement)
        for ((table, version) in VERSION_ROWS) {
            writes.insert(
                "INSERT INTO versions(table_name, table_version) VALUES (?, ?);",
                listOf(table, version),
            )
        }
    }

    /** Materializes `snapshot` as a brand-new book; `writes` must be an empty database. */
    fun materialize(snapshot: BookSnapshot, writes: SqliteWrites, ids: IdGenerator) {
        require(snapshot.accounts.isNotEmpty()) { "book has no accounts" }
        createSchema(writes)

        val currencyCode = snapshot.currencyCode ?: "USD"
        val fraction = snapshot.fraction ?: 100L
        val commodityGuid = ids.next()
        writes.insert(
            """
            INSERT INTO commodities(
                guid, namespace, mnemonic, fullname, cusip, fraction,
                quote_flag, quote_source, quote_tz
            ) VALUES (?, 'CURRENCY', ?, ?, NULL, ?, 1, 'currency', NULL);
            """.trimIndent(),
            listOf(commodityGuid.raw, currencyCode, currencyCode, fraction),
        )

        val rootAccount = snapshot.accounts.firstOrNull { it.type == "ROOT" }
        val rootGuid = rootAccount?.guid ?: ids.next()
        val templateRootGuid = ids.next()
        insertAccount(writes, rootGuid, rootAccount?.name ?: "Root Account", "ROOT", null, null, false, false)
        insertAccount(writes, templateRootGuid, "Template Root", "ROOT", null, null, false, false)
        writes.insert(
            "INSERT INTO books(guid, root_account_guid, root_template_guid) VALUES (?, ?, ?);",
            listOf(snapshot.bookGuid.raw, rootGuid.raw, templateRootGuid.raw),
        )

        for (account in snapshot.accounts) {
            if (account.guid == rootGuid) continue
            insertAccount(
                writes,
                account.guid,
                account.name,
                account.type,
                commodityGuid,
                account.parentGuid ?: rootGuid,
                account.hidden,
                account.placeholder,
                account.code,
                account.description,
            )
        }

        for (transaction in snapshot.transactions) {
            insertTransaction(writes, transaction, commodityGuid, ids)
        }
    }

    fun insertAccount(
        writes: SqliteWrites,
        guid: Guid,
        name: String,
        type: String,
        commodity: Guid?,
        parent: Guid?,
        hidden: Boolean,
        placeholder: Boolean,
        code: String = "",
        description: String = "",
    ) {
        writes.insert(
            """
            INSERT INTO accounts(
                guid, name, account_type, commodity_guid, commodity_scu,
                non_std_scu, parent_guid, code, description, hidden, placeholder
            ) VALUES (?, ?, ?, ?, 0, 0, ?, ?, ?, ?, ?);
            """.trimIndent(),
            listOf(
                guid.raw,
                name,
                type,
                commodity?.raw,
                parent?.raw,
                code,
                description,
                if (hidden) 1 else 0,
                if (placeholder) 1 else 0,
            ),
        )
    }

    private fun insertTransaction(
        writes: SqliteWrites,
        transaction: SnapshotTransaction,
        currencyGuid: Guid,
        ids: IdGenerator,
    ) {
        val timestamp = "${transaction.date} 00:00:00"
        writes.insert(
            """
            INSERT INTO transactions(guid, currency_guid, num, post_date, enter_date, description)
            VALUES (?, ?, '', ?, ?, ?);
            """.trimIndent(),
            listOf(transaction.guid.raw, currencyGuid.raw, timestamp, timestamp, transaction.description),
        )
        for (split in transaction.splits) {
            writes.insert(
                """
                INSERT INTO splits(
                    guid, tx_guid, account_guid, memo, action, reconcile_state,
                    reconcile_date, value_num, value_denom, quantity_num, quantity_denom, lot_guid
                ) VALUES (?, ?, ?, '', '', 'n', NULL, ?, ?, ?, ?, NULL);
                """.trimIndent(),
                listOf(
                    ids.next().raw,
                    transaction.guid.raw,
                    split.accountGuid.raw,
                    split.value.num,
                    split.value.denom,
                    split.value.num,
                    split.value.denom,
                ),
            )
        }
    }

    private val VERSION_ROWS = listOf(
        "Gnucash" to GNUCASH_VERSION,
        "Gnucash-Resave" to GNUCASH_RESAVE_VERSION,
        "accounts" to 1L,
        "billterms" to 2L,
        "books" to 1L,
        "budget_amounts" to 1L,
        "budgets" to 1L,
        "commodities" to 1L,
        "customers" to 2L,
        "employees" to 2L,
        "entries" to 4L,
        "invoices" to 4L,
        "jobs" to 1L,
        "lots" to 2L,
        "orders" to 1L,
        "prices" to 3L,
        "recurrences" to 2L,
        "schedxactions" to 1L,
        "slots" to 4L,
        "splits" to 5L,
        "taxtable_entries" to 3L,
        "taxtables" to 2L,
        "transactions" to 4L,
        "vendors" to 1L,
    )

    /** GnuCash 5.14 tables, in the order the SQLite backend creates them. */
    private val SCHEMA = listOf(
        "CREATE TABLE accounts(guid text(32) PRIMARY KEY NOT NULL, name text(2048) NOT NULL, account_type text(2048) NOT NULL, commodity_guid text(32), commodity_scu integer NOT NULL, non_std_scu integer NOT NULL, parent_guid text(32), code text(2048), description text(2048), hidden integer, placeholder integer);",
        "CREATE TABLE billterms(guid text(32) PRIMARY KEY NOT NULL, name text(2048) NOT NULL, description text(2048) NOT NULL, refcount integer NOT NULL, invisible integer NOT NULL, parent text(32), type text(2048) NOT NULL, duedays integer, discountdays integer, discount_num bigint, discount_denom bigint, cutoff integer);",
        "CREATE TABLE books(guid text(32) PRIMARY KEY NOT NULL, root_account_guid text(32) NOT NULL, root_template_guid text(32) NOT NULL);",
        "CREATE TABLE budget_amounts(id integer PRIMARY KEY AUTOINCREMENT NOT NULL, budget_guid text(32) NOT NULL, account_guid text(32) NOT NULL, period_num integer NOT NULL, amount_num bigint NOT NULL, amount_denom bigint NOT NULL);",
        "CREATE TABLE budgets(guid text(32) PRIMARY KEY NOT NULL, name text(2048) NOT NULL, description text(2048), num_periods integer NOT NULL);",
        "CREATE TABLE commodities(guid text(32) PRIMARY KEY NOT NULL, namespace text(2048) NOT NULL, mnemonic text(2048) NOT NULL, fullname text(2048), cusip text(2048), fraction integer NOT NULL, quote_flag integer NOT NULL, quote_source text(2048), quote_tz text(2048));",
        "CREATE TABLE customers(guid text(32) PRIMARY KEY NOT NULL, name text(2048) NOT NULL, id text(2048) NOT NULL, notes text(2048) NOT NULL, active integer NOT NULL, discount_num bigint NOT NULL, discount_denom bigint NOT NULL, credit_num bigint NOT NULL, credit_denom bigint NOT NULL, currency text(32) NOT NULL, tax_override integer NOT NULL, addr_name text(1024), addr_addr1 text(1024), addr_addr2 text(1024), addr_addr3 text(1024), addr_addr4 text(1024), addr_phone text(128), addr_fax text(128), addr_email text(256), shipaddr_name text(1024), shipaddr_addr1 text(1024), shipaddr_addr2 text(1024), shipaddr_addr3 text(1024), shipaddr_addr4 text(1024), shipaddr_phone text(128), shipaddr_fax text(128), shipaddr_email text(256), terms text(32), tax_included integer, taxtable text(32));",
        "CREATE TABLE employees(guid text(32) PRIMARY KEY NOT NULL, username text(2048) NOT NULL, id text(2048) NOT NULL, language text(2048) NOT NULL, acl text(2048) NOT NULL, active integer NOT NULL, currency text(32) NOT NULL, ccard_guid text(32), workday_num bigint NOT NULL, workday_denom bigint NOT NULL, rate_num bigint NOT NULL, rate_denom bigint NOT NULL, addr_name text(1024), addr_addr1 text(1024), addr_addr2 text(1024), addr_addr3 text(1024), addr_addr4 text(1024), addr_phone text(128), addr_fax text(128), addr_email text(256));",
        "CREATE TABLE entries(guid text(32) PRIMARY KEY NOT NULL, date text(19) NOT NULL, date_entered text(19), description text(2048), action text(2048), notes text(2048), quantity_num bigint, quantity_denom bigint, i_acct text(32), i_price_num bigint, i_price_denom bigint, i_discount_num bigint, i_discount_denom bigint, invoice text(32), i_disc_type text(2048), i_disc_how text(2048), i_taxable integer, i_taxincluded integer, i_taxtable text(32), b_acct text(32), b_price_num bigint, b_price_denom bigint, bill text(32), b_taxable integer, b_taxincluded integer, b_taxtable text(32), b_paytype integer, billable integer, billto_type integer, billto_guid text(32), order_guid text(32));",
        "CREATE TABLE gnclock ( Hostname varchar(255), PID int );",
        "CREATE TABLE invoices(guid text(32) PRIMARY KEY NOT NULL, id text(2048) NOT NULL, date_opened text(19), date_posted text(19), notes text(2048) NOT NULL, active integer NOT NULL, currency text(32) NOT NULL, owner_type integer, owner_guid text(32), terms text(32), billing_id text(2048), post_txn text(32), post_lot text(32), post_acc text(32), billto_type integer, billto_guid text(32), charge_amt_num bigint, charge_amt_denom bigint);",
        "CREATE TABLE jobs(guid text(32) PRIMARY KEY NOT NULL, id text(2048) NOT NULL, name text(2048) NOT NULL, reference text(2048) NOT NULL, active integer NOT NULL, owner_type integer, owner_guid text(32));",
        "CREATE TABLE lots(guid text(32) PRIMARY KEY NOT NULL, account_guid text(32), is_closed integer NOT NULL);",
        "CREATE TABLE orders(guid text(32) PRIMARY KEY NOT NULL, id text(2048) NOT NULL, notes text(2048) NOT NULL, reference text(2048) NOT NULL, active integer NOT NULL, date_opened text(19) NOT NULL, date_closed text(19) NOT NULL, owner_type integer NOT NULL, owner_guid text(32) NOT NULL);",
        "CREATE TABLE prices(guid text(32) PRIMARY KEY NOT NULL, commodity_guid text(32) NOT NULL, currency_guid text(32) NOT NULL, date text(19) NOT NULL, source text(2048), type text(2048), value_num bigint NOT NULL, value_denom bigint NOT NULL);",
        "CREATE TABLE recurrences(id integer PRIMARY KEY AUTOINCREMENT NOT NULL, obj_guid text(32) NOT NULL, recurrence_mult integer NOT NULL, recurrence_period_type text(2048) NOT NULL, recurrence_period_start text(8) NOT NULL, recurrence_weekend_adjust text(2048) NOT NULL);",
        "CREATE TABLE schedxactions(guid text(32) PRIMARY KEY NOT NULL, name text(2048), enabled integer NOT NULL, start_date text(8), end_date text(8), last_occur text(8), num_occur integer NOT NULL, rem_occur integer NOT NULL, auto_create integer NOT NULL, auto_notify integer NOT NULL, adv_creation integer NOT NULL, adv_notify integer NOT NULL, instance_count integer NOT NULL, template_act_guid text(32) NOT NULL);",
        "CREATE TABLE slots(id integer PRIMARY KEY AUTOINCREMENT NOT NULL, obj_guid text(32) NOT NULL, name text(4096) NOT NULL, slot_type integer NOT NULL, int64_val bigint, string_val text(4096), double_val float8, timespec_val text(19), guid_val text(32), numeric_val_num bigint, numeric_val_denom bigint, gdate_val text(8));",
        "CREATE TABLE splits(guid text(32) PRIMARY KEY NOT NULL, tx_guid text(32) NOT NULL, account_guid text(32) NOT NULL, memo text(2048) NOT NULL, action text(2048) NOT NULL, reconcile_state text(1) NOT NULL, reconcile_date text(19), value_num bigint NOT NULL, value_denom bigint NOT NULL, quantity_num bigint NOT NULL, quantity_denom bigint NOT NULL, lot_guid text(32));",
        "CREATE TABLE taxtable_entries(id integer PRIMARY KEY AUTOINCREMENT NOT NULL, taxtable text(32) NOT NULL, account text(32) NOT NULL, amount_num bigint NOT NULL, amount_denom bigint NOT NULL, type integer NOT NULL);",
        "CREATE TABLE taxtables(guid text(32) PRIMARY KEY NOT NULL, name text(50) NOT NULL, refcount bigint NOT NULL, invisible integer NOT NULL, parent text(32));",
        "CREATE TABLE transactions(guid text(32) PRIMARY KEY NOT NULL, currency_guid text(32) NOT NULL, num text(2048) NOT NULL, post_date text(19), enter_date text(19), description text(2048));",
        "CREATE TABLE vendors(guid text(32) PRIMARY KEY NOT NULL, name text(2048) NOT NULL, id text(2048) NOT NULL, notes text(2048) NOT NULL, currency text(32) NOT NULL, active integer NOT NULL, tax_override integer NOT NULL, addr_name text(1024), addr_addr1 text(1024), addr_addr2 text(1024), addr_addr3 text(1024), addr_addr4 text(1024), addr_phone text(128), addr_fax text(128), addr_email text(256), terms text(32), tax_inc text(2048), tax_table text(32));",
        "CREATE TABLE versions(table_name text(50) PRIMARY KEY NOT NULL, table_version integer NOT NULL);",
    )
}
