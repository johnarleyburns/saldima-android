<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Fixture books

`oracle-mini.gnucash` and `oracle-mini.sqlite.gnucash` are pulled from the
web repo at its pinned commit (see `scripts/pull-vectors.sh` and the web
repo's own `fixtures/books/README.md` for their provenance and hashes).

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
non-adjacent for temporal spread. Built by the web repo's `scripts/
import-gronewald-fixture.ts`; see its `fixtures/books/README.md` for full
provenance, per-file account/transaction counts, and hashes. Every file was
opened and re-saved by the real GnuCash engine as an external oracle, with
zero errors.

A separate, still-open PR adds `fa-001-straight-line-equipment-
depreciation.gnucash` (a synthetic fixture from the sibling
`saldima-accounting-verifier` project) alongside this file; merging both
will need this README reconciled by hand.
