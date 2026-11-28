<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Saldima design

High-level and low-level design behind the spec. TypeScript on Cloudflare Workers end to end; GnuCash's SQLite schema is the data model.

## High-level design

Saldima is a stateless edge Worker in front of one stateful Durable Object per book. Everything durable lives either in that object's SQLite database or in R2. The component diagram is in the Spec tab's Architecture section.

### Goals

- **File fidelity:** any book that goes in comes out as a `.gnucash` file desktop GnuCash opens with nothing lost.
- **One writer:** never two editors on the same book at once, web or desktop.
- **Phone first:** every screen works at 375 px.
- **No servers:** Workers, Durable Objects, R2, KV and an on-demand Container only.
- **Isolation:** one object, one database and one storage prefix per tenant.
- **Local parity:** the same runtime, storage and tests on a laptop as in production.

### Non-goals (for now)

Real-time co-editing; Scheme custom reports; bank feeds; offline editing in the browser.

### Key flows

**Page view**

1. Browser → Cloudflare, which terminates TLS for the tenant's hostname.
2. App Worker serves static assets directly. Otherwise it looks up the hostname in KV (cached in memory for 60 s), rejects unknown or lapsed tenants, and forwards the request to `BOOK.get(idFromName(tenantId))`.
3. Book checks the session, runs the Hono route, queries its SQLite through Drizzle, and renders JSX. htmx requests get a fragment; others get a full page.

**Saving a transaction**

1. `POST /app/txn` reaches the Book.
2. Checks: Editor role, this session holds the edit role, lease state is Web active.
3. Validate and write in one storage transaction (algorithm in Algorithms below), plus an audit row.
4. Set a snapshot alarm for one hour later if none is pending; return the updated register rows for htmx to swap in.

**Snapshot (alarm)**

1. `alarm()` exports the book to `.gnucash` bytes, gzips them and writes `tenants/<id>/<sha256>.gnucash.gz` to R2.
2. Records it in `gcw_snapshots` and prunes to 30 daily and 12 monthly.

**Download to edit / upload** follow the Desktop sync section of the Spec, using the lease state machine and import/export algorithms below.

**Signup**

1. Stripe Checkout completes; Stripe calls `POST /stripe/webhook`.
2. App Worker verifies the signature, drops duplicate events (event ID kept in KV for 7 days), writes the tenant record as `provisioning`.
3. Calls `Book.provision()` over RPC: creates `gcw_` tables, the owner user and the chosen template book.
4. Marks the tenant `active` and emails a one-time sign-in link.

**Refund**

1. Owner clicks Refund in Billing within 30 days.
2. App Worker calls Stripe's refund API and cancels the subscription; KV status becomes `read-only`.
3. Book sets an alarm 30 days out to delete its storage and R2 prefix, with an email warning 7 days before.

## Data model

Each Book object's SQLite holds two families of tables: GnuCash's own, exactly as GnuCash defines them, and ours, prefixed `gcw_` and never exported.

### GnuCash tables

All of them exist from day one, created from GnuCash's own table definitions and copied through on upload and export, even before we build screens for them.

| Group | Tables | Screens built in |
| --- | --- | --- |
| Core | `books`, `accounts`, `commodities`, `transactions`, `splits`, `slots`, `prices`, `versions`, `gnclock` | P1 (accounts), P2 (transactions), P4 (reconcile flags) |
| Scheduling | `schedxactions`, `recurrences` | P5 |
| Budgets | `budgets`, `budget_amounts` | P5 |
| Investments | `lots` | P6 |
| Business | `customers`, `vendors`, `employees`, `jobs`, `invoices`, `entries`, `billterms`, `taxtables`, `taxtable_entries`, `orders` | P7 |

Conventions we must follow: every row has a 32-character lowercase hex `guid`; amounts are integer `*_num` / `*_denom` pairs; split `value` is in the transaction's currency and `quantity` in the account's commodity; timestamps are UTC text; extra properties live in `slots` as key/value rows keyed by the owning object's GUID.

### Our tables

| Table | Holds | Main columns |
| --- | --- | --- |
| `gcw_meta` | Our schema version and tenant settings | `key`, `value` |
| `gcw_users` | People who can sign in | `id`, `email`, `name`, `role`, `password_hash`, `created_at` |
| `gcw_credentials` | Passkeys and TOTP secrets | `id`, `user_id`, `kind`, `public_key` or encrypted `secret`, `counter` |
| `gcw_sessions` | Active sessions | `token_hash`, `user_id`, `expires_at`, `holds_edit` |
| `gcw_leases` | Desktop checkout history and current state | `id`, `state`, `base_sha256`, `issued_to`, `issued_at`, `closed_at` |
| `gcw_snapshots` | Index of R2 snapshots | `sha256`, `r2_key`, `kind`, `size`, `created_at` |
| `gcw_inbox` | Quick-capture entries awaiting posting | `id`, `created_by`, `date`, `amount_num`, `amount_denom`, `description`, `account_hint`, `receipt_key`, `status` |
| `gcw_attachments` | Files linked to transactions | `id`, `txn_guid`, `r2_key`, `name`, `mime`, `size` |
| `gcw_import_rules` | Saved CSV mappings and matcher memory | `id`, `source`, `mapping_json`, `payee_pattern`, `account_guid` |
| `gcw_audit` | Who changed what | `id`, `at`, `user_id`, `action`, `object_guid`, `detail_json` |

### Tenant registry (KV)

- `host:<hostname>` → `{ tenantId, status, primaryHost }`
- `tenant:<tenantId>` → `{ stripeCustomerId, subscriptionId, hosts[], plan, createdAt }`

Tenant IDs are random 128-bit values in base32, never derived from a hostname, so renaming a domain never moves data.

## Tenancy, security and observability

Only a tenant's own Book object ever reads that tenant's book; the App Worker sees hostnames, session cookies in transit and billing events, never financial data.

| Concern | Design |
| --- | --- |
| Tenant boundary | Durable Object named by tenant ID; R2 keys always built as `tenants/<tenantId>/…` by one helper that rejects `..` and absolute paths; no cross-tenant query exists anywhere in the code |
| Sign-in | Passkeys first (WebAuthn); password fallback hashed with PBKDF2-SHA256 through WebCrypto, plus TOTP. Second factor required for Owner and Editor |
| Sessions | 32 random bytes; only the SHA-256 hash is stored. Cookie `__Host-session`: `Secure`, `HttpOnly`, `SameSite=Lax`, 30-day sliding expiry. One session per book holds the edit role at a time |
| CSRF | `SameSite` cookies plus a per-session token that htmx sends in a header on every non-GET request |
| Content Security Policy | `default-src 'self'`; no third-party scripts, fonts or analytics; inline scripts by hash only |
| Rate limits | Cloudflare rate-limiting rules on sign-in, signup and upload paths, per hostname and IP |
| Encryption | Snapshots encrypted with AES-256-GCM using a per-tenant data key; that key is wrapped by a master key held as a Worker secret and stored only in wrapped form in `gcw_meta` |
| Secrets | Stripe keys, master key and Cloudflare API token as `wrangler secret`, separate per environment |
| Logs | Workers Logs and `wrangler tail`; structured JSON with tenant ID, route, status and timing, never amounts, names or descriptions |
| Metrics | Workers Analytics Engine for counts (signups, active books, request latency); no third-party analytics |
| Alerts | Cron self-test every 15 minutes against a canary tenant on each environment; email on failure |
| Recovery | Any book can be rebuilt from its latest R2 snapshot into a fresh Book object; restore drills run before launch and quarterly |

## Low-level design: code layout

A pnpm monorepo with one deployable Worker and pure TypeScript packages. The packages hold all accounting logic and run unchanged in Node and in workerd, so most tests need no runtime at all.

```
saldima/                      AGPL-3.0-only; LICENSE, CLA.md, CONTRIBUTING.md, NOTICE, CLAUDE.md at the root
├─ apps/
│  └─ web/                      the one deployable Worker
│     ├─ src/
│     │  ├─ worker.ts           App Worker entry: static, signup API, Stripe webhook, routing
│     │  ├─ ports.ts            builds the real Ports from env bindings (the only place that reads env)
│     │  ├─ edge/               tenant lookup, signup, webhook handlers, refunds
│     │  ├─ book/
│     │  │  ├─ book.ts          class Book extends DurableObject
│     │  │  ├─ app.tsx          Hono sub-app mounted at /app
│     │  │  ├─ api.ts           Hono sub-app mounted at /api/v1 (token auth, for apps)
│     │  │  ├─ routes/          accounts, register, reconcile, reports, sync, inbox, settings, billing, auth
│     │  │  ├─ views/           JSX pages and components
│     │  │  └─ migrations/      gcw_ table migrations (drizzle-kit output)
│     │  └─ env.d.ts            bindings: BOOK, TENANTS (KV), SNAPSHOTS (R2), CONVERTER, secrets
│     ├─ public/                CSS, htmx, icons, manifest, service worker, marketing pages
│     ├─ test/                  Vitest tests that run inside workerd, incl. contract suites on real bindings
│     └─ wrangler.jsonc         environments: local, staging, production
├─ packages/
│  ├─ gnucash/                 Drizzle schema of GnuCash tables, money, GUIDs, invariants, queries, reports
│  ├─ gnucash-file/            .gnucash import/export via SQLite WebAssembly; GnuCash table definitions
│  ├─ gnucash-xml/             streaming XML reader (later; replaces the container for imports)
│  ├─ sync/                    lease state machine, three-way merge by GUID
│  ├─ api-contract/            zod schemas for /api/v1; emits OpenAPI and recorded fixtures for saldima-ios
│  ├─ testkit/                 Ports interface, fakes, manual clock and scheduler, contract suites
│  ├─ importers/               CSV, OFX/QFX, QIF parsers and the matcher
│  ├─ billing/                 Stripe Checkout, Billing and refund helpers; plan rules
│  └─ ui/                      shared JSX components and CSS tokens
├─ containers/converter/      Debian + python3-gnucash: convert and validate endpoints (separate program)
├─ e2e/                       Playwright tests, page objects, device projects
├─ fixtures/
│  ├─ books/                   anonymised books per scenario and GnuCash version, XML and SQLite
│  └─ vectors/                 golden JSON vectors replayed by saldima-ios
├─ tools/                     seed, reset, introspect-schema, oracle runner, vectors, license scan
├─ docs/plans/                per-phase checklists for coding agents
└─ .github/workflows/         ci, deploy-staging, release
```

### Boundary rules (enforced by CI)

- `packages/*` never import `cloudflare:workers`; they must run in plain Node.
- Only `packages/gnucash` and `packages/gnucash-file` touch GnuCash tables.
- Only `apps/web/src/book` touches Durable Object storage; only `apps/web/src/edge` touches KV and Stripe.
- No `number` type for money and no `Math.random` for IDs or tokens (lint rules).
- Views never query: routes load data through `packages/gnucash`, then pass plain objects to JSX.

## Key types and interfaces

These signatures are the contracts between packages; implementation plans in `docs/plans/` build against them.

```ts
// packages/gnucash/src/money.ts — GnuCash stores value_num / value_denom as integers
export type Money = { readonly num: bigint; readonly denom: bigint };
export function add(a: Money, b: Money): Money;           // exact, common denominator
export function toScu(m: Money, scu: bigint): Money;       // round half-even to the commodity's fraction
export function isZero(m: Money): boolean;

// packages/gnucash/src/guid.ts
export type Guid = string & { readonly __brand: 'Guid' };  // 32 lowercase hex
export const newGuid = (): Guid => crypto.randomUUID().replaceAll('-', '') as Guid;

// packages/gnucash/src/model.ts
export type Reconcile = 'n' | 'c' | 'y' | 'f' | 'v';
export interface SplitInput {
  accountGuid: Guid; value: Money; quantity: Money;
  memo?: string; action?: string; reconcile: Reconcile;
}
export interface TxnInput {
  currencyGuid: Guid; postDate: string; num?: string;
  description: string; notes?: string; splits: SplitInput[];
}

// packages/gnucash/src/store.ts — implemented over Durable Object SQLite and over plain SQLite in tests
export interface BookStore {
  accounts(): Promise<Account[]>;
  register(account: Guid, range: DateRange, page: Page): Promise<RegisterPage>;
  postTransaction(t: TxnInput, by: UserId): Promise<Guid>;
  editTransaction(guid: Guid, t: TxnInput, by: UserId): Promise<void>;
  voidTransaction(guid: Guid, reason: string, by: UserId): Promise<void>;
  balances(asOf: string): Promise<Map<Guid, Money>>;
}

// packages/sync/src/lease.ts — pure state machine; effects are executed by the Book
export type LeaseState =
  | { kind: 'web-active' }
  | { kind: 'checked-out'; leaseId: string; baseSha256: string; since: string }
  | { kind: 'merging'; uploadKey: string; baseSha256: string; plan: MergePlan };
export type LeaseEvent =
  | { kind: 'download-to-edit' }
  | { kind: 'upload'; baseInFile?: string; leaseInFile?: string; sha256: string; webChangedSinceBase: boolean }
  | { kind: 'unlock' }
  | { kind: 'apply-merge'; decisions: Map<Guid, 'web' | 'upload' | 'delete'> }
  | { kind: 'cancel-merge' };
export function planMerge(base: BookUnits, web: BookUnits, upload: BookUnits): MergePlan;
export function transition(s: LeaseState, e: LeaseEvent): { next: LeaseState; effects: Effect[] };

// apps/web/src/book/book.ts — one instance per tenant
export class Book extends DurableObject<Env> {
  fetch(req: Request): Promise<Response>;                  // runs the /app Hono sub-app
  provision(p: ProvisionInput): Promise<void>;             // called by the signup webhook
  importUpload(r2Key: string, by: UserId): Promise<ImportResult>;
  exportSnapshot(kind: 'hourly' | 'download' | 'pre-replace' | 'manual'): Promise<SnapshotRef>;
  setStatus(s: 'active' | 'read-only' | 'deleting'): Promise<void>;
  alarm(): Promise<void>;                                  // snapshots, scheduled deletion
}
```

Every async effect goes through `Ports`. Code under test receives fakes; only `apps/web/src/ports.ts` builds the real ones.

```ts
// packages/testkit/src/ports.ts
export interface Ports {
  clock: Clock;                 // now(): Date; the fake advances only when the test says so
  ids: IdGen;                   // guid(), token(); seeded and repeatable in tests
  scheduler: Scheduler;         // setAlarm, setTimeout; the fake runs due work on clock.advance()
  snapshots: BlobStore;         // R2: get, put (if-absent), list, delete
  tenants: KeyValue;            // KV: hostname -> tenant
  books: BookDirectory;         // get(tenantId) -> BookClient (the Durable Object stub)
  converter: Converter;         // xmlToSqlite, validate
  payments: Payments;           // Stripe calls used by billing
  mail: Mailer;
  http: Fetcher;                // any other outbound fetch
}

// Each fake can be told to fail: fakes.snapshots.failNext('put', new TimeoutError())
export function makeFakes(seed?: number): Ports & FakeControls;
export function contractSuite<P>(name: string, make: () => Promise<P>): void; // run against fake and real
```

## Algorithms

### Posting a transaction

1. Require the Editor role, the session's edit role and lease state Web active; otherwise 423 with the reason.
2. Normalise each split: `value` in the transaction currency, `quantity` in the account's commodity, both rounded to that commodity's smallest fraction (`toScu`).
3. Check that split values sum to exactly zero using `bigint` arithmetic. Different commodities on one split (value ≠ quantity) are allowed only for accounts whose commodity differs from the transaction currency, and record a price.
4. In one `storage.transactionSync`: insert the `transactions` row (new GUID, `enter_date` = now in UTC), the `splits` rows, `slots` rows for notes, and a `gcw_audit` row.
5. If no alarm is pending, set one for now + 1 hour. Return the new GUID.

Edits delete and re-insert the transaction's splits inside the same storage transaction, keeping the transaction GUID; voids follow GnuCash's own convention (zero the amounts, keep the originals in `slots`, and add a void reason).

### Lease state machine

| From | Event | Condition | To | Effects |
| --- | --- | --- | --- | --- |
| Web active | Download to edit | Editor | Checked out | New lease; export stamped with `gcw/base` and `gcw/lease`; serve the file; audit |
| Any | Download a copy | Viewer | Unchanged | Export stamped with `gcw/base` only |
| Checked out | Any ledger write | — | Checked out | Refused with 423; receipts still go to the inbox |
| Checked out | Unlock | Owner | Web active | Close lease; keep its base snapshot for merging |
| Any | Upload | Web unchanged since the file's base | Web active | Pre-replace snapshot; replace tables; close lease |
| Any | Upload | Web changed since the file's base | Merging | Store upload; run three-way merge; show summary |
| Any | Upload | No base stamp, web book empty | Web active | First import |
| Any | Upload | No base stamp, web book not empty | Merging | Two-way comparison by GUID; everything shown for review |
| Merging | Apply | Owner, all conflicts decided | Web active | Pre-merge snapshot; write merged result in one storage transaction; close lease |
| Merging | Cancel | Owner | Previous state | Keep upload in R2 for 30 days |

### Three-way merge

1. Load three versions into memory as maps keyed by GUID: **base** (the R2 snapshot named by the upload's `gcw/base` slot), **web** (the Book's tables now) and **upload**.
2. Build merge units: one per transaction (its row, its splits and their slots), one per row for accounts, commodities, prices, lots, scheduled transactions, budgets and business objects. Hash each unit's normalised content with SHA-256 so "unchanged" is a hash comparison.
3. Classify each unit using the table in the Spec's Desktop sync section: keep, take web, take upload, add, or conflict.
4. Cross-checks on the tentative result: every transaction balances; every split's account exists; no split carries two different reconcile states; flag likely duplicates (same date within 3 days, same amount and account, different GUIDs).
5. Persist the plan in a `gcw_merges` table (unit GUID, decision, both versions) so the owner can review on a phone over several sittings.
6. On Apply: pre-merge snapshot, then write all units in one storage transaction and run the full invariant check before committing.

The merge lives in `packages/sync` as pure functions over plain objects, so every case in the table is a unit test, and the fixture corpus includes scripted both-sides-changed scenarios that the oracle verifies in real GnuCash.

### Import (upload)

1. Reject files over 60 MB. Read the first bytes: `SQLite format 3` → SQLite; gzip magic → decompress and look again; `<?xml` → send to the Converter, which returns SQLite bytes.
2. Open in SQLite WebAssembly. Run `PRAGMA integrity_check`; read the `versions` table and refuse untested versions.
3. Validate: every transaction's split values sum to zero; every split's account exists; every GUID is well formed.
4. Read the `gcw/lease` slot on the book row and run the lease state machine.
5. To replace: take a pre-replace snapshot, then in one storage transaction drop the GnuCash tables, recreate each from the upload's own `CREATE` statement in `sqlite_master`, and copy rows in batches of 500. Skip `gnclock`; never touch `gcw_` tables.

### Export (download and snapshot)

1. Read the `CREATE` statements of the GnuCash tables from the object's own `sqlite_master`, so the export matches exactly what came in or what the template created.
2. Create an empty in-memory SQLite WebAssembly database, run those statements, and copy every table in pages of 1,000 rows.
3. Leave `gnclock` empty. For download-to-edit, write the new lease ID into the book's slot in the copy.
4. Export the bytes, hash them with SHA-256, gzip with `CompressionStream`, and `put` to R2 only if that key doesn't already exist (identical books dedupe for free).
5. Close the WebAssembly database to release memory before returning.

### Reports

Balances come from one aggregate query per report over `splits` joined to `accounts`, grouped by account and denominator, then summed exactly in `bigint`. Report parity tests compare every figure with GnuCash's own report output on the fixture books.

## Routes

The App Worker owns everything outside `/app`; the Book owns everything inside it. Page routes return full HTML, or a fragment when htmx asks.

| Method | Path | Runs in | Role | Purpose |
| --- | --- | --- | --- | --- |
| GET | `/`, `/pricing`, `/docs/*` | Static assets | Public | Marketing site and docs |
| GET | `/signup` | Static assets | Public | Plan choice and name check |
| POST | `/api/signup/check-name` | App Worker | Public | Is `name.<domain>` free? |
| POST | `/api/signup/checkout` | App Worker | Public | Create a Stripe Checkout session |
| POST | `/stripe/webhook` | App Worker | Stripe signature | Provision, renew, lapse, refund |
| GET/POST | `/app/login`, `/app/logout`, `/app/passkey/*` | Book | Public / signed in | Sign-in, passkeys, TOTP |
| GET | `/app` | Book | Viewer | Dashboard: balances, recent activity, inbox count |
| GET/POST | `/app/accounts`, `/app/accounts/:guid` | Book | Viewer / Editor | Account tree; create, edit, move, hide |
| GET | `/app/accounts/:guid/register` | Book | Viewer | Register, paged and filterable |
| POST/PATCH | `/app/txn`, `/app/txn/:guid` | Book | Editor | Post or edit a transaction |
| POST | `/app/txn/:guid/void`, `/app/txn/:guid/reverse`, `/app/txn/:guid/duplicate` | Book | Editor | Register actions |
| GET/POST | `/app/reconcile/:guid` | Book | Editor | Reconcile against a statement |
| GET | `/app/reports/:name` | Book | Viewer | Reports; `?format=csv` or `pdf` |
| GET/POST | `/app/inbox` | Book | Viewer / Editor | Quick-capture entries; post them |
| GET | `/app/sync` | Book | Viewer | Book state, snapshots, download a copy |
| POST | `/app/sync/download-to-edit` | Book | Editor | Start a desktop checkout |
| POST | `/app/sync/upload/*` | Book | Editor | Multipart upload to R2, then import |
| POST | `/app/sync/resolve` | Book | Owner | Settle a conflict |
| GET/POST | `/app/settings/users`, `/app/settings/domain` | Book | Owner | Users and roles; custom domain |
| GET/POST | `/app/billing`, `/app/billing/refund` | Book → App Worker | Owner | Plan, invoices, money-back refund |

Status codes: 401 not signed in; 403 role too low; 409 conflict waiting to be resolved; 423 book checked out to desktop; 402 subscription lapsed (served by the App Worker before the Book is called).

### Sync API for apps (`/api/v1`)

The iPhone app, and later the `gcw` command-line tool, use JSON endpoints on the same host. They run the same lease and merge code as the web pages, so a phone is just another desktop. Requests carry a bearer device token; schemas live in `packages/api-contract`.

| Method | Path | Role | Purpose |
| --- | --- | --- | --- |
| POST | `/api/v1/auth/token` | Signed in (passkey, or password + TOTP) | Issue a device token named after the device; revocable in Settings → Users |
| GET | `/api/v1/book` | Viewer | Lease state, base SHA-256, book name, user's role |
| POST | `/api/v1/book/download` | Viewer / Editor | Body `{ toEdit: boolean }`; returns a stamped `.gnucash` (and a lease when `toEdit`) |
| POST | `/api/v1/book/upload` | Editor | Multipart `.gnucash`; returns replaced, or a merge plan ID |
| GET | `/api/v1/merges/:id` | Owner | Merge summary and conflict units for review |
| POST | `/api/v1/merges/:id/apply`, `/cancel` | Owner | Decisions per unit; apply or cancel |
| POST | `/api/v1/receipts` | Contributor | Image plus amount, date, payee, account; posted or sent to the inbox |
| POST | `/api/v1/lease/unlock` | Owner | Release a lost checkout |

Errors use the status codes above and a JSON body `{ code, message }`.

## Testing and performance budgets

Most tests are fast unit tests on the pure packages; a smaller set runs inside workerd; Playwright covers the paths a customer can't afford to see broken.

| Package or area | Unit (Vitest, Node) | Runtime (Vitest in workerd) | End-to-end (Playwright) |
| --- | --- | --- | --- |
| `gnucash` | Money, rounding, invariants, every report against fixture books | Queries over real Durable Object SQLite | — |
| `gnucash-file` | Import/export of every fixture book; byte-level checks | Import/export inside a Book within the memory budget | Upload, download and reopen |
| `sync` | Every row of the lease table, conflict diffs | Lease persisted across object eviction | Download to edit → upload → web active; conflict resolution |
| `importers` | Parsers on real bank files; matcher accuracy | — | CSV import with a saved mapping |
| `apps/web` | View helpers | Hostname routing, auth, roles, CSRF, alarms, cross-tenant access attempts | Signup with Stripe test card, register on phone and desktop, reconcile, reports, refund |

Critical end-to-end journeys that must pass before any production deploy: sign up and pay; upload a desktop book; post a split transaction on a phone; reconcile an account; download and reopen in GnuCash (oracle); request a money-back refund.

### Performance budgets

| Measure | Budget |
| --- | --- |
| Register page, server time (p95) | Under 150 ms |
| Register page, CPU (warm) | Under 20 ms |
| Posting a transaction (p95) | Under 100 ms |
| Book cold start (first request after eviction) | Under 300 ms |
| Export of a 20 MB book | Under 10 s and under 100 MB memory |
| JavaScript sent to the browser | Under 50 KB gzipped (htmx plus our own code) |
| First page load on a mid-range phone over 4G | Under 2 s |

Budgets are checked in CI: CPU and memory from the runtime tests, page timings from a Playwright run against staging.

## iPhone app design (saldima-ios)

The iPhone app mirrors the web's packages in Swift. Accounting logic is pure and tested with fakes; the app target only draws screens.

```
saldima-ios/                  AGPL-3.0-only; LICENSE, CLA.md, NOTICE, CLAUDE.md, project.yml
├─ Package.swift              SaldimaCore, SaldimaSync, SaldimaTestKit (Swift 6, strict concurrency)
├─ SaldimaCore/               Money, Guid, invariants, BookStore (actor over SQLite3), reports, templates
├─ SaldimaSync/               SyncAPI client (URLSession), device token, lease, merge-review models
├─ SaldimaTestKit/            fakes for every protocol, manual clock, URLProtocol replay of recorded API fixtures
├─ Saldima/                   app target
│  ├─ App/                    scene-based lifecycle, dependency container
│  ├─ DesignSystem/           Liquid Glass surfaces, typography, amounts formatting
│  └─ Features/               Accounts, Register, Receipts, Reconcile, Reports, Sync, Settings
├─ SaldimaCoreTests/  SaldimaSyncTests/
├─ Fixtures/                  books and vectors pulled from saldima at a pinned commit
├─ scripts/                   check-swift6.sh, guards, pull-vectors.sh, test.sh
├─ .githooks/                 pre-commit runs swift test
└─ .github/workflows/         ci (compile + swift test on Xcode 27), testflight (ios-release environment)
```

```swift
// SaldimaCore
public struct Money: Sendable, Hashable { public let num: Int64; public let denom: Int64 }
public protocol Clock: Sendable { func now() -> Date }
public protocol IDGenerator: Sendable { func guid() -> Guid }
public protocol BookStore: Actor {
  func accounts() async throws -> [Account]
  func register(_ account: Guid, range: DateRange) async throws -> [RegisterRow]
  func post(_ txn: TxnInput) async throws -> Guid
  func save(to url: URL) async throws -> String   // returns SHA-256
}

// SaldimaSync
public protocol SyncAPI: Sendable {
  func book() async throws -> BookStatus
  func download(toEdit: Bool) async throws -> URL
  func upload(_ file: URL) async throws -> UploadResult  // .replaced or .merge(id)
  func merge(_ id: String) async throws -> MergePlan
  func apply(_ id: String, decisions: [Guid: Decision]) async throws
  func sendReceipt(_ r: ReceiptInput) async throws
}
```

View models are `@Observable` classes on the main actor that take these protocols in their initialisers, so every screen's logic is tested with fakes and no simulator. Linked mode stores the device token in the Keychain and the working copy in Application Support; the standalone path never touches the network.

## Android app design (saldima-android)

The Android track mirrors the iPhone app above (standalone by default,
GnuCash XML as the canonical on-disk book via `XmlBookDocument`, no gating
purchase) in Kotlin/Compose instead of Swift. Its own architecture baseline
lives in `saldima-android/docs/architecture/TECHNICAL-DESIGN.md`; this
section only corrects this design doc's description of reconcile for that
track, since it's drifted from what shipped.

The API route table above (`/app/reconcile/:guid`) and the iPhone Features
list just above this section both place reconcile as something reached from
an account or the register. On Android that's stale: the bottom navigation
has its own top-level **Reconcile** tab (`ReconcileScreen`), which replaced
a "Bills" tab — Bills is still available, moved into the Accounts screen's
overflow menu. The Reconcile tab is also where the QIF/OFX/CSV/PDF statement
importer (`ImportStatementScreen`) lives: it parses a bank statement and
bulk-checks the matching splits, rather than reconciling one split at a
time.

Shipped on Android beyond what this doc otherwise implies as still pending:
void/unvoid, QIF/CSV register export, transaction search, scheduled/
recurring transactions, budgets, and the multi-currency price database. The
`/api/v1` sync client is not shipped; it's still a stub.
