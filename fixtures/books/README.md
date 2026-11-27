<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Fixture books

`oracle-mini.gnucash`, `oracle-mini.sqlite.gnucash`, and
`gronewald-gl-2023-01.gnucash` are pulled from the web repo at its pinned
commit (see `scripts/pull-vectors.sh` and the web repo's own
`fixtures/books/README.md` for their provenance and hashes).

`fa-001-straight-line-equipment-depreciation.gnucash` is the same new
fixture added to the web and iOS repos: a larger synthetic book from the
sibling `saldima-accounting-verifier` project's reviewed `FA-001` scenario
(five years of straight-line equipment depreciation, 8 real accounts, 187
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
