// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/** A commodity/currency reference, written on the wire as `cmdty:space`/`cmdty:id`. */
data class CommodityRef(val space: String, val id: String)

/**
 * A GnuCash `GNCPrice`: one entry in the book's price database, recording
 * `value` units of `currency` per unit of `commodity` as of `time`. Mirrors
 * the web track's `XmlPrice` (`packages/gnucash/src/xml-document.ts`).
 * Milestone 1 (tracked in the repo's issue): every price this engine
 * creates has `source = "user:price-editor"` -- no Finance::Quote / online
 * quote fetching.
 */
data class XmlPrice(
    val guid: Guid,
    val commodity: CommodityRef,
    val currency: CommodityRef,
    /** `YYYY-MM-DD HH:MM:SS +ZZZZ`, as GnuCash stores it; `""` if malformed/missing. */
    val time: String,
    val source: String,
    /** Free text on the wire (desktop writes `last`/`bid`/`ask`/`nav`/etc.); `""` if unset. */
    val type: String,
    val value: Money,
)
