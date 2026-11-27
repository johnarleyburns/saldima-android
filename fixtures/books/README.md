<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Fixture books

`oracle-mini.gnucash` and `oracle-mini.sqlite.gnucash` are pulled from the
web repo at its pinned commit (see `scripts/pull-vectors.sh` and the web
repo's own `fixtures/books/README.md` for their provenance and hashes).

`fa-001-straight-line-equipment-depreciation.gnucash` is the same fixture
added to the web and iOS repos: a larger synthetic book from the sibling
`saldima-accounting-verifier` project's reviewed `FA-001` scenario (five
years of straight-line equipment depreciation, 8 real accounts, 187
transactions), hand-verified against that project's own independent
reference-model numbers, then re-opened and re-saved by GnuCash 5.5 as an
external oracle -- the same process used for `oracle-mini.*`. Exporting it
surfaced and fixed a real bug on that project's side (`trn:commodity`
instead of `trn:currency`, which the real GnuCash engine rejected
outright). It does **not** replace the owner's own real anonymised book
(still pending, per the iOS repo's `Fixtures/books/README.md`); it's useful
for exercising the read path against independently-verified, known-correct
numbers across all three tracks. SHA-256:
`791a5ddd8fa3e9ee293090dbe68ce8f2d5270b6ed2be3daea620265c99d6e0e9`.

`gronewald-gl-2023-01.gnucash`, `gronewald-gl-2023-07.gnucash`, and
`gronewald-gl-2024-12.gnucash` are the same fixtures added to the web and
iOS repos: a real, independently-published academic synthetic
general-ledger dataset -- **"DIB General Ledger Data"**, Jan Gronewald,
Sebastian Stephan, Alexander Rombach, and Peter Fettke (Saarland University
/ DFKI), Zenodo record [22014653](https://doi.org/10.5281/zenodo.22014653)
(v2, 2026-08-19), **CC BY 4.0**. A genuinely complex German SME chart of
accounts (VAT, trade receivables/payables, payroll) and balanced multi-leg
journal entries; each file is one standalone calendar month (not a
cumulative series) from the dataset's full 2023-01..2024-12 range, picked
non-adjacent for temporal spread.

`gronewald-gl-full.gnucash` is the same dataset again, but as one
continuous book spanning the entire 2023-01..2024-12 range (102,429
transactions, 25 accounts) instead of a standalone month -- for
year-over-year comparison (e.g. total revenue 2023 vs 2024) that a single
month can't give you. It's a single ~10 MB file (~119 MB decompressed);
prefer a `YYYY-MM` month above for a fast test and reach for this one
specifically when the test needs more than one month's data.

Built by the web repo's `scripts/import-gronewald-fixture.ts`; see its
`fixtures/books/README.md` for full provenance, per-file account/
transaction counts, and hashes. Every file here was opened and re-saved by
the real GnuCash engine as an external oracle, with zero errors.
