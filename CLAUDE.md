<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# CLAUDE.md — Saldima for Android (`johnarleyburns/saldima-android`)

Saldima for Android is a **complete, standalone GnuCash-compatible book** on the
phone. It needs no Saldima account, no subscription and no network. Linking to a
hosted Saldima book is optional and removable: the local `.gnucash` file is
always the source of truth. This repo mirrors the iPhone track
(`johnarleyburns/saldima-ios`) and the web accounting rules.

The product is **Saldima**. Say "compatible with GnuCash"; never use GnuCash as
our name or logo.

## Sources of truth

1. `docs/architecture/TECHNICAL-DESIGN.md` — the current architecture
   baseline (mirrors the web repo's copy). GnuCash XML is the canonical
   book format; SQLite is for import/export of a desktop SQLite-dialect
   file only, never the live store. Zero-knowledge sync is a later,
   deferred phase. See `docs/plans/P0.md` risks for the migration's phasing.
2. `docs/spec/SPEC.md` and `docs/spec/DESIGN.md` copied from the web repo at the
   pinned commit (see `docs/vectors.md`) for product/accounting behaviour;
   their storage/encryption passages are superseded by (1).
3. `saldima-ios/CLAUDE.md` and the iOS plan; Android should reach feature parity.
4. This file and `docs/plans/P0.md`.
5. The GitHub issue being worked.

Stop and ask if a decision is missing; never guess.

## Stack

- **Kotlin 2.x**, Java 17, Gradle 8.9, Android Gradle Plugin, Jetpack Compose,
  Material 3. `compileSdk 36`, `minSdk 26`, `targetSdk 36`.
- **Pure core module** `:core` (Kotlin/JVM): Money, Guid, Ledger, Reports,
  templates, invariants, supporter policy. No Android, Billing, Compose or
  networking imports; also runs in plain JVM tests.
- **`:sync`**: `/api/v1` client and lease/merge models. Optional, never called in
  standalone mode.
- **`:app`**: Compose UI, SQLite book storage, Billing adapter, Keychain-equivalent
  encrypted token storage (Android Keystore).
- **Persistence:** GnuCash XML is the sole canonical on-disk book
  (`XmlBookDocument`, in `BookRepository`): the whole file is parsed once
  on open, mutated in memory, and atomically rewritten on every save — the
  same way desktop GnuCash's own XML backend behaves. SQLite is an
  import/export adapter only: `SqliteBookReader` reads a desktop
  SQLite-dialect file into a `BookSnapshot`, and `XmlBookDocument.build`
  materializes it into a fresh XML working copy. A legacy SQLite-dialect
  file still opens transparently (read into the equivalent in-memory XML
  model) but is rewritten as XML the first time anything saves. No Room, no
  ORM rewriting GnuCash tables.
- **Purchases:** Google Play Billing one-time **consumable** `saldima_supporter`.
  It never gates a feature; it sets a one-way local Supporter flag/badge, just
  like Voxglass. No subscription. Hosting is bought on the web.

## Layout

```
core/          Money, Guid, ledger, reports, templates, supporter policy
sync/          /api/v1 client, device token, lease and merge models
app/           MainActivity, Compose screens, SQLite BookStore, Billing adapter
core-test/     or core/src/test — JVM tests, fakes, verification scenarios
app/src/test/  ViewModel and billing-fake tests
app/src/androidTest/ optional device tests; never required in hosted CI
fixtures/      books and vectors pulled from the web commit
scripts/       check-license, pull-vectors, guard scripts
.github/workflows/ci.yml
```

## Commands

| Command | Purpose |
| --- | --- |
| `./gradlew :core:test` | pure JVM tests, no Android/network |
| `./gradlew test` | all JVM unit tests |
| `./gradlew :app:assembleDebug` | compile the app |
| `./gradlew :app:lintDebug` | Android lint |
| `scripts/pull-vectors.sh` | fetch web vectors at the pinned SHA |
| `scripts/check-licenses.sh` | reject GPL/AGPL/unknown dependencies |
| `scripts/check-swift6.sh` is iOS-only | — |

Hosted CI runs `test` and `assembleDebug` on Ubuntu. Emulator/device tests are
local-only. Playwright is web-only.

## Test-first

- Failing acceptance test → smallest code → refactor green.
- Every async dependency is a Kotlin interface/port: `Clock`, `IdGenerator`,
  `BookStore`, `FileAccess`, `SyncAPI`, `Billing`, `TokenStore`.
- Unit tests use in-memory fakes; no network, no real clock, no sleeping.
- Contract tests run the same suite against fake and real adapters (SQLite,
  Billing sandbox only with the owner).
- Port the web ledger/report verification scenarios and the two-year partnership
  scenario; expected numbers must match `saldima/fixtures/vectors` and the
  GnuCash oracle.
- The mobile core never imports `android.*`; that is what keeps it testable on
  the JVM.

## Money and data invariants

- Amounts are `Long` numerator/denominator pairs, exact; posting normalisation is
  **half-up**; display/report rounding is half-even.
- GUIDs are 32 lowercase hex, generated through `IdGenerator`, never reused.
- Every transaction balances on split values; account balances sum amounts.
- Keep unknown tables/columns/KVP; strip `gcw_` on export; write `gnclock` while
  open and clear on close.
- Working copy in app-private storage; atomic save; SHA-256 versions.
- Standalone mode has **zero network calls**. Sync is an explicit optional link.

## Supporter

- One product: `saldima_supporter`, one-time consumable.
- Never gates anything. Purchase success sets `isSupporter`; Settings shows
  thanks; a small badge may appear.
- Do not add a subscription, ads, analytics or restore entitlement.
- Play Console product/price and signing are owner-only.

## Licence and dependencies

- AGPL-3.0-only, SPDX header every authored file; CLA for outsiders.
- Dependencies MIT/BSD/Apache/ISC/0BSD/CC0; no GPL/AGPL. CI license scan.
- Never copy GnuCash/gnucash-web/piecash code; behaviour reference only.

## Git

One task, one branch, one PR, squash merge; `gh` CLI; never push `main`.
Never deploy, tag, upload to Play Console, or touch secrets/environments/rulesets.
Owner approves releases.

## Definition of done

- A test failed first and passes; `./gradlew test` and `assembleDebug` green.
- Completion audit against the issue; plan/docs updated.
- No secrets, network calls in standalone, deploys or release uploads.
