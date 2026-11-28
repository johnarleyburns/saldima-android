// SPDX-License-Identifier: AGPL-3.0-only
package guru.parso.saldima.core

/**
 * The `Clock` port CLAUDE.md already names alongside `IdGenerator`: "today"
 * as a `YYYY-MM-DD` string, injected so callers never reach for a raw
 * wall-clock API directly. Introduced for the statement importer's PDF
 * year-inference fallback (`ImportPdf.kt`'s `inferStatementYear`); the real
 * implementation lives in `:app` (`SystemClock`), fakeable the same way
 * `IdGenerator` already is in tests.
 */
fun interface Clock {
    fun todayIso(): String
}
