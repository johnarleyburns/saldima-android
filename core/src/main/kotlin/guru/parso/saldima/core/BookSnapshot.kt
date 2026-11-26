// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/** Format-neutral view of a book, shared semantics with the iOS/web readers. */
data class SnapshotAccount(
    val guid: Guid,
    val name: String,
    val type: String,
    val parentGuid: Guid? = null,
    val commodityCode: String? = null,
    val code: String = "",
    val description: String = "",
    val hidden: Boolean = false,
    val placeholder: Boolean = false,
)

data class SnapshotSplit(
    val accountGuid: Guid,
    val value: Money,
    val guid: Guid? = null,
    /** `"n"` not reconciled, `"c"` cleared, `"y"` reconciled -- GnuCash's own codes. */
    val reconcileState: String = "n",
    /** Set only once `reconcileState` is `"y"`; `YYYY-MM-DD HH:MM:SS +ZZZZ`. */
    val reconcileDate: String? = null,
)

data class SnapshotTransaction(
    val guid: Guid,
    val date: String,
    val description: String,
    val currencyCode: String? = null,
    val splits: List<SnapshotSplit>,
    /**
     * GnuCash's "Associate File/Location": an `assoc_uri` slot. Saldima
     * writes a path relative to the book's own storage, but reads whatever
     * is there.
     */
    val docLink: String? = null,
)

data class BookSnapshot(
    val bookGuid: Guid,
    val currencyCode: String? = null,
    val fraction: Long? = null,
    val accounts: List<SnapshotAccount>,
    val transactions: List<SnapshotTransaction>,
    val templateRootGuid: Guid? = null,
)
