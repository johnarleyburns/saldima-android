<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Saldima — Parity & Product Spec

Nov 20, 2026 · @j

## Summary

Saldima is a hosted, GnuCash-compatible accounting app written in TypeScript and run entirely on Cloudflare. It covers what most people and small businesses use desktop GnuCash for, works fully on phones, and syncs both ways with desktop GnuCash by passing the real `.gnucash` file back and forth. Customers sign up with a card, pick a subdomain or bring their own domain, and get their own login and their own book. One plan: $5/month or $50/year, everything included, with a 30-day money-back guarantee.

Key decisions:

- **TypeScript on Cloudflare, end to end.** One Worker serves server-rendered pages (Hono + JSX + htmx). Each customer's book lives in its own Durable Object, whose built-in SQLite holds the GnuCash tables. R2 stores snapshots and attachments, KV routes hostnames, and a Container converts XML books. No VPS, no Python.
- **GnuCash is the basis.** Its file format, business features and user base are the product; Frappe Books' desktop app is archived and its successor isn't released.
- **Small phases, MVP in about three months.** Test signup and chart of accounts first, then double entry, then desktop upload/download with backups, then reconcile, all mobile-first. Paid launch follows at about five months.
- **The `.gnucash` file is always one click away.** Upload and download are lossless, so leaving costs the customer nothing.
- **Single writer, enforced.** A Durable Object handles one request at a time, and a check-out/check-in lease with SHA-256 version hashes keeps web and desktop from editing at once.
- **AGPLv3, owner-only deploys.** Anyone can read the code and send pull requests under a contributor agreement that keeps the copyright with you; only `johnarleyburns` can approve a deployment, and deploy credentials exist only inside protected GitHub Environments.
- **Test-first, with every async dependency mocked. The whole stack runs and debugs locally.** `wrangler dev` runs the real Workers runtime; Vitest runs inside it; Playwright drives end-to-end tests.
- **In parallel: an iPhone app** that opens and saves `.gnucash` files on the device with no account, and can optionally link to Saldima hosting the way desktop GnuCash does. It has its own repo and follows the Voxglass project setup, built phase by phase alongside the web app.
- **Healthy margins.** About $4,900/year net at 100 users and $49,200/year at 1,000.

## Name and domain

**The product is Saldima (sahl-DEE-mah), at `saldima.com`.** It's built on *saldo*, the word for an account balance in German, Spanish, Italian, Portuguese, Polish, Dutch and more, so it's easy to say almost everywhere. The earlier working name, Cashflare, was dropped: `cashflare.com` has been registered since 2019, and "-flare" names are crowded in accounting software.

Names were judged on three tests: easy to say in many languages, an exact-match .com that's unregistered, and no live USPTO mark on the same word in the software and finance classes (9, 35, 36, 42). "Tally" fails the third test badly: USPTO shows 38 live TALLY marks in those classes, including TALLYPRIME from Tally Solutions, whose accounting software is sold in 100+ countries. **"Saldo" passes:** it means *account balance* in German, Spanish, Italian, Portuguese, Polish, Dutch and more, and only two live US marks contain it, both for phone credit.

Checked 20 November 2026 against the Verisign .com registry and the USPTO trademark search:

| Name | Say it | .com | USPTO (exact and spaced forms) | Outcome |
| --- | --- | --- | --- | --- |
| **Saldima** | sahl-DEE-mah | `saldima.com` free | No marks | **Chosen** |
| Saldopad | SAHL-doh-pad | `saldopad.com` free | No marks | Runner-up |
| Saldoduo | SAHL-doh-DOO-oh | `saldoduo.com` free | No marks | Harder to say |
| Kontopad | KON-toh-pad | `kontopad.com` free | No marks | 11 live KONTO… marks in these classes |
| Tallyduo / Tallido | — | Free | No exact marks | Crowded TALLY field; TallyPrime |
| Cashflare | — | `cashflare.com` taken | No marks | Domain taken; "Flare Accounting" exists |

Next steps: register `saldima.com` (and `saldima.app`) at Cloudflare Registrar today, then file a US trademark application for SALDIMA in classes 9 and 42 once a trademark attorney has cleared it.

"No marks" is a knock-out search, not a clearance: before filing or launching, have a trademark attorney check confusingly similar marks, state registrations and other countries. Register the .com at Cloudflare Registrar, and consider `.app` too.

Sources: .com status from the Verisign RDAP registry (`rdap.verisign.com`), 20 November 2026 · [Flare Accounting on Capterra](https://www.capterra.com/p/213342/Flare-Accounting/) · [CA$H FLO registered mark](https://trademark.justia.com/866/96/ca-h-86696443.html) · [Cloudflare Registrar](https://developers.cloudflare.com/registrar/llms-full.txt).

- **Repos:** two public AGPLv3 repos under johnarleyburns: `saldima` (web) and `saldima-ios` (iPhone). Both start empty rather than as forks, so no outside code comes in and you keep the whole copyright. gnucash-web is credited in `NOTICE` as a reference.
- **Description wording:** "compatible with GnuCash", never "GnuCash" as the product name.

Whatever the final name, the repo, product and domain all use it; renaming before the first public commit costs nothing.

## Starting point: gnucash-web today

gnucash-web is our reference for screens and scope, not our codebase. Saldima reimplements its features in TypeScript and goes much further; its proof that the GnuCash SQLite schema can be driven from outside GnuCash is the part we rely on.

| Aspect | gnucash-web today | Saldima |
| --- | --- | --- |
| Stack | Python, Flask, Jinja, Bootstrap, piecash | TypeScript, Hono, JSX + htmx, Drizzle over Durable Object SQLite |
| Runs on | Any WSGI server | Cloudflare Workers only (plus `wrangler dev` locally) |
| Storage | A SQLite, PostgreSQL or MySQL book | The GnuCash tables inside each tenant's Durable Object; snapshots as real `.gnucash` files in R2 |
| Features | Browse accounts, add and edit two-split transactions, CLI price updates | The full parity list in this spec |
| Gaps | Can't create accounts; needs an existing book; companion only | Account setup, new books from templates, upload/download with desktop |
| Auth | Optional; logout deletes a cookie | Per-tenant users, roles, passkeys or TOTP |
| License | GPLv3 | AGPLv3 with a contributor agreement; none of its code copied |

Upstream: [joshuabach/gnucash-web](https://github.com/joshuabach/gnucash-web) · [PyPI](https://pypi.org/project/gnucash-web) · [piecash](https://pypi.org/project/piecash/0.4.3).

## License and contributor agreement

Both repos are AGPLv3 (`AGPL-3.0-only`), and you keep sole copyright through a contributor agreement. That keeps commercial licensing, relicensing, App Store distribution and a future sale all open.

- **Why AGPL:** anyone who runs a modified Saldima as a service must publish their changes, so a competitor can't take the code private. Self-hosting your own books stays free.
- **Clean start, no fork:** neither repo forks gnucash-web or copies GnuCash or piecash code. Our own code reads and writes the GnuCash file format; those projects are credited in NOTICE as references only. No GPL or AGPL third-party code is linked into either app.
- **Contributor agreement (CLA):** every outside pull request needs a signed CLA, checked by the CLA Assistant bot as a required status check. Contributors keep their copyright but grant you a perpetual right to relicense their work, including under App Store or proprietary terms. You are exempt.
- **Converter container:** python3-gnucash (GPL) runs as a separate program behind an HTTP endpoint on our servers only. It is never linked into our code or shipped to users.
- **App Store:** as sole copyright holder you publish the iPhone build under Apple's terms, while the public source stays AGPL. This holds only while every line is yours or under the CLA and every dependency is MIT, BSD or Apache, so CI runs a license scan on both repos.
- **Source offer:** the AGPL requires offering users the source of what they use. The web footer and the iPhone About screen link to the public repo at the running commit.
- **Files in each repo:** `LICENSE` (AGPLv3), `CLA.md` (based on the Apache Individual CLA), `CONTRIBUTING.md`, `NOTICE`, and an SPDX header on every source file.

Have a lawyer review the CLA and the App Store position before the first outside contribution.

## Architecture

Everything runs on Cloudflare in TypeScript: one Worker, one Durable Object per customer book, and R2, KV and a small Container around them. TypeScript is the platform's native language, so there's no WebAssembly layer in the request path, Durable Object storage is typed, and local testing runs in the real runtime. Production uses the $5/month Workers Paid plan, which Containers require and which raises the CPU limit to 30 seconds per request.

&#91;embedded content: hosting architecture · edge, origin, storage\]

### Components

| Component | Runs on | Job |
| --- | --- | --- |
| App Worker | Worker, TypeScript, Hono router | Serves static assets (marketing site, signup, CSS, htmx, icons) at no CPU cost. Handles the Stripe webhook and the signup API. Looks up the tenant by hostname in KV and forwards every `/app/*` request to that tenant's Book |
| Book | Durable Object class, one per tenant (`idFromName(tenantId)`) | Owns the book. Runs the app's routes and JSX views next to its own SQLite, which holds the GnuCash tables plus our `gcw_` tables (users, sessions, leases, inbox, audit log). Handles one request at a time: the single-writer guarantee |
| R2 | Object storage | Book snapshots as real `.gnucash` files (`tenants/<id>/<sha256>.gnucash.gz`), attachments, uploads in progress |
| KV | Key-value store | Hostname → tenant ID, plan and status |
| Converter | Cloudflare Container (basic: 1 GiB, ¼ vCPU) with `python3-gnucash` | XML ↔ SQLite conversion, and a validator that opens files in the real GnuCash engine. Starts on demand only |
| Cron Trigger | App Worker | Daily price quotes; nightly check that every tenant has a recent snapshot |

### How a book lives in a Durable Object

- **Schema.** `packages/gnucash` defines the GnuCash 5.x tables in Drizzle ORM, generated by introspecting a real GnuCash book, so column names and types match exactly. Drizzle's `durable-sqlite` driver runs it over the object's own SQLite. Our `gcw_` tables are migrated when the object starts.
- **Money.** GnuCash stores amounts as integer numerator/denominator pairs. We keep them as `bigint` pairs end to end; a CI rule forbids plain JavaScript numbers for money.
- **Upload.** The file goes to R2 by multipart upload. The Book opens it with SQLite compiled to WebAssembly (an in-memory build that works in Workers), validates it, then copies every table, including ones we don't model, into its own database in one transaction.
- **Download and snapshot.** The Book builds a fresh in-memory SQLite database with GnuCash's own table definitions, copies the GnuCash tables across (never `gcw_`), exports the bytes, gzips them and writes them to R2 under their SHA-256 hash. An alarm takes a snapshot one hour after the first change.
- **Size.** Personal and small-business books are typically 1–20 MB. Uploads over about 60 MB are refused until large-book memory use has been measured, since the in-memory copy and the Worker share its 128 MB limit.

### Spike gate (part of P0)

Before feature work, a throwaway branch must show all four, locally and on staging, on a real 20 MB book:

1. The introspected Drizzle schema reads the account tree and a register from Durable Object SQLite.
2. Import and export stay under 100 MB of memory, and desktop GnuCash 5.x opens the exported file cleanly.
3. A warm register page costs under 20 ms of CPU.
4. `wrangler dev`, a Vitest test running inside workerd, and one Playwright test all pass locally and in CI.

If export runs out of memory, the fallback is to build the export file in the Converter container, which has 1 GiB.

## Runtime language and basis

**Decision (November 2026): TypeScript on GnuCash's file format.** Python and Go were both workable; TypeScript wins on Workers because it runs natively, has typed Durable Object storage, and has the best local test tooling.

| Option considered | Why not |
| --- | --- |
| Python (gnucash-web + piecash on Pyodide) | Largest bundle, slowest CPU, and a DBAPI driver we'd have to write; Python Workers only reached GA in September 2026 |
| Go (WebAssembly) | Lighter than Python, but relies on a one-maintainer library and hand-written JavaScript glue for Durable Object SQL |
| Frappe Books as the basis | Its Electron/Vue app is archived; the successor on the Frappe Framework is unreleased and server-heavy; its file opens in nothing our customers use |

What we give up: piecash's ready-made GnuCash data layer. We write our own in `packages/gnucash`, tested against real GnuCash in CI.

## Desktop sync: upload and download

**Locks by default, a three-way merge as the safety net.** Download-to-edit checks the book out to the desktop, and the web stops accepting ledger edits until it comes back, so the common case never conflicts. Locks alone aren't enough, though: someone edits a "copy" they downloaded, force-unlocks after losing a laptop, or posts on the web anyway. So every file we hand out is stamped with the snapshot it came from, and an upload that changed alongside the web is merged transaction by transaction against that snapshot. Receipts captured on phones never wait on any of this.

### Book states

| State | Who can write the ledger | Web shows | Ways out |
| --- | --- | --- | --- |
| Web active | Web app (one editor session at a time) | Normal app | *Download to edit* → Checked out |
| Checked out | One desktop copy, identified by the lease ID | Read-only banner: "Being edited on desktop since \<date>"; receipts still accepted into the inbox | Upload → Web active (or Merging if the web changed too). Owner can *Unlock*, which keeps the lease's base snapshot so a later upload can still merge |
| Merging | Nobody until reviewed | Merge summary: changes applied from each side, plus any conflicts to decide | Apply → Web active; Cancel → back to the previous state, upload kept for 30 days |

### Download

- **Download to edit on desktop.** Mints a lease ID and writes it into a book-level KVP slot (`gcw/lease`). Stores `lease_id` and the served file's SHA-256 in the `gcw_leases` table, sets the book to Checked out, and serves `<name>.gnucash` (SQLite). GnuCash keeps KVP slots it doesn't recognise, so the lease survives desktop edits.
- **Download a copy.** A snapshot with no lease, for backups or accountants. It still carries the base stamp, so any edits made to it can be merged later.
- **XML option.** The same file, converted to compressed XML for users who prefer it.

### Upload

1. Accept `.gnucash` up to 60 MB. Check the first bytes to tell SQLite from gzip-compressed XML.
2. XML is converted to SQLite with GnuCash's own engine (`python3-gnucash` bindings, headless) in the Converter container, started on demand.
3. Validate: `PRAGMA integrity_check`, the schema version in the `versions` table is within the tested range, our data layer opens it, every transaction balances, no orphan splits.
4. Clear the `gnclock` table. Desktop leaves a lock row behind, and the next desktop open would otherwise refuse.
5. Find the base: read the `gcw/base` slot every exported file carries (the SHA-256 of the snapshot it was made from) and the lease ID if any.
6. Decide:
   - **Nothing changed on the web since that base** (the normal checked-out case) → replace the book, after a pre-replace snapshot, and go to Web active.
   - **Both sides changed** → three-way merge against the base snapshot (below) and go to Merging for review.
   - **No base stamp** (a file never downloaded from us, e.g. the first import or a book from another computer) → first import if the web book is empty; otherwise a two-way comparison by GUID, with every difference shown for review.

### When both sides changed: three-way merge

GnuCash gives every transaction, account and price a permanent GUID, which makes a reliable merge possible. We compare three versions: the **base** (the exact snapshot that was downloaded, kept in R2 for as long as any copy of it is out, and at least 90 days), the **web** book now, and the **upload**. A transaction is merged as one unit: the transaction row, all its splits and its notes.

| Since the base, on the web | Since the base, on desktop | Result |
| --- | --- | --- |
| Unchanged | Unchanged | Keep |
| Changed | Unchanged | Take the web version |
| Unchanged | Changed | Take the desktop version |
| Changed the same way | Changed the same way | Keep (identical) |
| Changed differently | Changed differently | **Conflict:** shown side by side; pick web or desktop per transaction |
| Deleted | Changed (or the reverse) | **Conflict:** keep the changed one or confirm the delete |
| Added | — | Added (new GUIDs never collide) |

After merging, the checks run again: every transaction balances, every split's account still exists (an account deleted on one side but used by a new transaction on the other becomes a conflict), and a split reconciled differently on each side becomes a conflict. Likely duplicates (the same purchase entered on both sides, so different GUIDs: same date within 3 days, same amount and account) are flagged for review, not merged automatically. The owner sees a summary ("14 from desktop, 6 from web, 2 conflicts, 1 possible duplicate"), decides the conflicts, then applies. A pre-merge snapshot makes it one click to undo.

### Receipts and quick entry on mobile

Anyone allowed to can snap a receipt in a few seconds from a phone, whatever state the book is in.

- **Capture:** an "Add receipt" button opens the phone camera or photo library; PDFs from the Files app work too. The app shrinks photos on the phone before upload (about 1600 px JPEG) and stores them in R2 under the tenant's attachments.
- **Fields:** amount first on a number keypad, then date (today by default), payee with autocomplete, the account it was paid from, and the expense account, pre-filled from that payee's history. A note is optional.
- **Posting:** if the book is Web active and the person is an Editor, the receipt posts straight away as a balanced transaction with the image attached. Otherwise it waits in the *Receipts inbox*, and an Editor posts it later (one tap each, or in bulk) once the book is back from desktop.
- **Offline:** with no signal, receipts queue on the phone and upload when it reconnects.
- **Desktop link:** each exported transaction carries GnuCash's linked-document field pointing at the receipt's page in Saldima, so clicking it in desktop GnuCash opens the image in a browser after sign-in.
- **Who can add them:** a new **Contributor** role (for staff) can add receipts and see their own submissions, nothing else.
- **Later:** forward emailed receipts to `receipts@<book>.saldima.com` (Cloudflare Email Routing into the Worker), and optional text recognition to pre-fill amount, date and payee, off by default for privacy.

### Later extra: one-command sync

A small `gcw` CLI using a per-user API token: `gcw pull` checks out and opens the file in GnuCash, `gcw push` uploads it. That removes the browser round-trip for people who switch often.

## Feature parity matrix

Everything except Scheme reports and online banking is in scope, phased so the personal-books core ships first and business features come last. Phases are defined in the Roadmap. "Out" means a deliberate non-goal; the book still keeps that data intact on round-trip.

| Area | Desktop feature | gnucash-web today | Target | Phase |
| --- | --- | --- | --- | --- |
| Accounts | Create, edit, move, hide, delete; all account types; placeholder, tax-related, notes | Browse only | Full | P1 |
| Accounts | New book from template (personal, small business, insurance agency), opening balances | None | Full | P1 |
| Register | Multi-split transactions; basic ledger, auto-split and journal views | Two-split add/edit | Full | P2 |
| Register | Autocomplete from past transactions, num/action, notes, memo | Partial | Full | P2 |
| Register | Void, reverse, duplicate, delete, jump to other account | Edit only | Full | P2 |
| Reports | Balance sheet, income statement (profit and loss) | None | Rebuilt in TypeScript; CSV and PDF | P2 |
| Sync | Upload from and download to desktop GnuCash, XML and SQLite | None | Full, with leases and three-way merge | P3 |
| Backup | Snapshots and restore | None | Hourly R2 snapshots, one-click restore | P3 |
| Reconcile | Reconcile window against a statement balance and date | None | Full, phone and desktop | P4 |
| Register | Find and filter (date, amount, text, account, reconcile status) | Basic | Full, saved searches | P4 |
| Register | Linked files and URLs on transactions | None | Full | P5 |
| Scheduled | Scheduled transactions and Since Last Run | None | Full | P5 |
| Import | CSV, OFX/QFX and QIF import with the matcher | None | Full | P5 |
| Budgets | Budgets by period, budget report | None | Full | P5 |
| Reports | Cash flow, trial balance, transaction report, account summary, general journal, charts | None | Rebuilt in TypeScript | P5 |
| Currencies | Multiple currencies, exchange rates, trading accounts | None | Full | P6 |
| Investments | Securities, price database, online quotes, lots, capital gains, stock splits, portfolio reports | CLI price update | Full | P6 |
| Tools | Loan and mortgage assistant, financial calculator, close book | None | Full | P6 |
| Business | Customers, vendors, employees, jobs; invoices, bills, vouchers, credit notes, payments, PDF invoices | None | Full | P7 |
| Business | Billing terms, tax tables, A/R and A/P aging, sales tax report | None | Full | P7 |
| Out | Custom Scheme reports, Guile scripting | — | Out; offer a read-only SQL/CSV export instead | — |
| Out | HBCI/AqBanking online banking, check printing | — | Out; bank feeds may come later via an aggregator | — |

### Web-only extras

- Upload from and download to desktop GnuCash, with leases (Desktop sync section).
- Quick-capture inbox on mobile, including a receipt photo attached to the posted transaction.
- Read-only users (spouse, accountant) with their own logins.
- Activity log: who changed what, with the transaction GUID.
- Restore any of the last 30 snapshots in one click.

## Data integrity

A book that comes back from the web must open in desktop GnuCash with nothing lost, and the CI pipeline proves that on every commit. These are build-breaking invariants, not guidelines.

### Invariants

1. **Keep what we don't understand.** Never delete or rewrite tables, columns or KVP slots the app doesn't model. Write only through the typed data layer in packages/gnucash or explicit, reviewed SQL.
2. **Every transaction balances.** The split *values* sum to zero in the transaction currency. Each split's *quantity* uses its account's commodity and smallest-unit fraction. This is checked on every write and on upload.
3. **GUIDs are GnuCash-style.** 32 lowercase hex characters, newly generated, and never reused, even after a delete.
4. **Schema-version gate.** Read the `versions` table and refuse to write a book whose versions are outside the tested matrix, with a clear message. Today's targets are GnuCash 4.14 and 5.x (5.16 current; 5.18 planned for December 2026).
5. **One writer.** Every write runs as one transaction inside the tenant's Durable Object, which handles one request at a time, and checks that the book is Web active. Only one browser session holds the edit role at a time; a second gets a read-only view and a "take over editing" button.
6. **Content-addressed snapshots.** Before any replace, restore or bulk import, snapshot the book by its SHA-256 hash.

### Test oracle: real GnuCash in CI

- A fixtures corpus of real anonymised books: personal, multi-currency, investments with lots, business with invoices, scheduled transactions, budgets. Each exists in XML and SQLite, created by desktop GnuCash 4.14 and 5.x.
- Round-trip test: upload → apply a scripted set of web edits → download → open with the `python3-gnucash` bindings → compare the account tree, balances by date, the transaction GUID set, slots, lots and scheduled transactions against the expected result.
- Report parity test: our balance sheet and income statement must match GnuCash's own report numbers on each fixture to the cent.
- Gates in CI (grep/AST): no raw `DELETE FROM` outside the data-access module; no JavaScript `number` near money (use `bigint` num/denom pairs, as GnuCash does); every route resolves the tenant first.

## Mobile and desktop UI

One server-rendered app, built mobile-first, that widens into a desktop layout. No separate native app and no SPA rewrite. Pages are server-rendered with Hono's JSX, with htmx for in-place updates, so the register feels live without a client-side framework.

| Screen | Phone (< 768 px) | Desktop (≥ 1024 px) |
| --- | --- | --- |
| Accounts | Collapsible tree, balance on the right, search on top | Tree with columns (balance, today, cleared, reconciled) like desktop GnuCash |
| Register | Card list grouped by date; tap a card to open a full-screen editor; swipe actions: void, duplicate, reconcile flag | Spreadsheet-style grid with inline editing, keyboard navigation (Tab, Enter, +/− for dates), split rows expand in place |
| Add transaction | Floating + button; amount keypad first, then payee with autocomplete, then account; camera button for a receipt | Blank row at the bottom of the register, as in desktop |
| Reconcile | Two tabs (deposits / withdrawals) with a sticky difference bar | Two-column window like desktop |
| Reports | One report per screen, charts first, table below; share as PDF | Side panel of report options, live preview |
| Business | Invoice list and invoice viewer; posting payments | Full invoice editor with line items |

Requirements that hold everywhere:

- Installable PWA (manifest + service worker) with an app icon on the home screen. The service worker caches the shell and static assets only; book data is never cached, so a lost phone holds no financial data.
- Tap targets at least 44 px; amounts use `inputmode="decimal"`; dates use native pickers.
- Desktop-style keyboard shortcuts on wide screens; `?` shows them.
- Light and dark themes; respects reduced motion; WCAG 2.2 AA contrast.
- Every screen is checked at 375, 768 and 1280 px in Playwright visual tests.

## Signup, billing, domain and login

From the landing page to a working book takes about two minutes on a subdomain. A custom domain adds one DNS record on the customer's side. Nothing in provisioning needs a human.

### Signup flow

1. **Landing page** (App Worker static assets). Pick a plan and type a name. The page checks live whether `name.saldima.com` is free.
2. **Stripe Checkout** (hosted by Stripe) collects card and email. We never touch card data. Billing is annual by default (see Costs and pricing).
3. **Stripe webhook** `checkout.session.completed` → the App Worker verifies the signature, writes the tenant record to KV with status `provisioning`, and sends a provisioning request to the tenant's new Book Durable Object.
4. **Provisioning in the Book object:** create the `gcw_` tables and the owner user (email from Stripe, no password yet). Create a book from the chosen template, or leave it empty and wait for an upload. Mark the tenant `active` in KV.
5. **Welcome email** with a one-time sign-in link (15-minute expiry). The owner sets a password and is asked to enrol a passkey or TOTP.
6. **First run:** choose *Start a new book* or *Upload from desktop GnuCash*. Upload goes straight into the Desktop sync upload flow.

### Custom domain (optional, any time)

- In Settings → Domain, the owner types `books.acme.com`. The app calls the Cloudflare custom hostnames API and shows one CNAME record to add: `books.acme.com → `customers.saldima.com``.
- The page polls until Cloudflare marks the hostname active and the certificate is issued, then flips the tenant's primary hostname. The subdomain keeps working as a fallback.
- Apex domains (`acme.com` itself) aren't offered at launch, because they need CNAME flattening or Apex Proxying on the customer's side.

### Login and roles

- Each tenant has its own user table inside its own Book Durable Object. A person with two books has two separate logins; there's no global account.
- Roles: **Owner** (billing, users, domain, edit), **Editor** (edit, upload/download), **Viewer** (read-only, reports, download a copy), **Contributor** (adds receipts only; for staff).
- Sign-in: passkey, or password (PBKDF2-SHA256 via WebCrypto) plus TOTP; mandatory second factor for Owner and Editor. Session cookie is host-only, `Secure`, `HttpOnly`, `SameSite=Lax`, signed with a per-tenant secret.
- Rate-limit sign-in at the edge (Cloudflare rate limiting rule per hostname + IP).

### Isolation, in layers

| Layer | What separates tenants |
| --- | --- |
| Network | Separate hostname and certificate per tenant; cookies scoped to that host |
| Application | Tenant resolved from `Host` before any route; every R2 key built from that tenant's prefix and checked to stay inside it |
| Data | Separate Durable Object (its own SQLite database) and R2 prefix per tenant; no shared tables |
| Secrets | Per-tenant session-signing secret and per-tenant backup encryption key, wrapped by a master key stored as a Worker secret |
| Strongest option | *Bring your own bucket:* backups go to the customer's own R2/S3 account |

### Lifecycle

- Payment failed → Stripe retries for 14 days, then the App Worker serves a read-only, download-only mode for 30 days, then the data is deleted with notice.
- Cancel any time: one-click full export (SQLite book, XML book, attachments ZIP), then deletion of the tenant's Durable Object storage and R2 prefix within 7 days.

### 30-day money-back guarantee

Any first purchase can be refunded in full within 30 days, no questions asked, from a button in Settings → Billing.

- The button calls Stripe's refund API, cancels the subscription, and offers the full export (SQLite book, XML book, attachments) right away.
- The book stays readable and downloadable for 30 days, then is deleted with an email warning 7 days before.
- Renewals aren't covered by the guarantee, but a renewal charge refunded within 14 days of a forgotten auto-renew is granted on request. Customers get a renewal reminder email 14 days ahead.
- One guarantee refund per email address and per card, to stop people cycling free years.
- Stripe keeps its processing fee on refunded payments, so every refund costs us that fee. The revenue model counts it.

## Costs and pricing

One plan at **$5/month or $50/year** nets about **$4,890/year at 100 users** and **$49,164/year at 1,000**, assuming half of customers choose each billing period. That's about 89% of gross sales. Card fees are the largest cost at both sizes.

### Plan

| Plan | Price | Includes |
| --- | --- | --- |
| Self-host | Free (AGPLv3 source) | Everything; run it on your own Cloudflare account |
| Saldima | $5/month or $50/year | Every feature including business, custom domain, 3 books, 5 users, 5 GB attachments, 30 snapshots |
| Bring your own bucket | No extra charge | Snapshots go to the customer's own R2/S3 bucket |

One plan keeps the decision to a single click. $5 is about a third of consumer budgeting apps and a fifth to a tenth of hosted small-business accounting ($24–59/month). Yearly billing is shown first, labelled "2 months free". Both periods carry the 30-day money-back guarantee on the first payment.

### What each billing period leaves us

| Billing | Customer pays per year | Stripe fees (card + 0.7% Billing) | We keep | Fees as share |
| --- | --- | --- | --- | --- |
| Monthly ($5 × 12) | $60.00 | $5.76 | $54.24 | 9.6% |
| Yearly | $50.00 | $2.10 | $47.90 | 4.2% |

### Revenue model (per year)

| Line | 100 users | 1,000 users |
| --- | --- | --- |
| Gross sales (half monthly, half yearly) | $5,500.00 | $55,000.00 |
| Refunds under the guarantee (5% of yearly sales, 5% of first months) | −$137.50 | −$1,375.00 |
| Stripe card fees (2.9% + 30¢ per charge; 12 charges a year for monthly) | −$354.50 | −$3,545.00 |
| Stripe Billing (0.7% of billing volume) | −$38.50 | −$385.00 |
| Cloudflare (breakdown below) | −$64.00 | −$396.00 |
| Email and domain | −$15.00 | −$135.00 |
| **Net** | **$4,890.50** | **$49,164.00** |
| Cost per user per year (excluding refunds) | $4.72 | $4.46 |

### Cloudflare costs (per month)

| Item | Included / price | 100 users | 1,000 users |
| --- | --- | --- | --- |
| Workers Paid plan (staging and production share it) | $5/month | $5.00 | $5.00 |
| Worker requests (\~1,000 per user) | 10M included, then $0.30/M | $0 | $0 |
| Worker CPU (\~10 ms per request in TypeScript) | 30M ms included, then $0.02/M ms | $0 | $0 |
| Durable Object requests | 1M included, then $0.15/M | $0 | $0 |
| Durable Object duration (\~60 active min per user, billed at 128 MB) | 400,000 GB-s included, then $12.50/M GB-s | $0 | $0.63 |
| Durable Object SQLite storage (\~20 MB per book) | 5 GB-month included, then $0.20/GB-month | $0 | $3.00 |
| R2 (\~300 MB snapshots and attachments per user) | 10 GB free, then $0.015/GB-month | $0.30 | $4.35 |
| Custom hostnames (30% of users add a custom domain) | 100 free, then $0.10 each | $0 | $20.00 |
| Converter container (a few runs per user per year) | Within the included 25 GiB-hours | $0 | $0 |
| **Total** |  | **$5.30** | **$32.98** |

### Assumptions and exclusions

- Each user makes about 20 visits a month of about 3 minutes each. A Durable Object is billed for duration only while it's active or can't hibernate, at a full 128 MB each.
- Refunds run at 5% of first-year sales; the first few months will show the real rate.
- Email is free at 100 users and about $10/month at 1,000 (transactional email provider; to be confirmed). The domain costs about $15/year.
- Not included: sales tax or VAT collection, chargebacks ($15 each from Stripe), and your own time on development and support.

Sources: [Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing) · [Workers limits](https://developers.cloudflare.com/workers/platform/limits) · [Workers pricing](https://workers.cloudflare.com/pricing) · [R2 pricing](https://developers.cloudflare.com/r2/pricing) · [Containers pricing](https://developers.cloudflare.com/containers/pricing/) · [Cloudflare for SaaS plans](https://developers.cloudflare.com/cloudflare-for-saas/plans/) · [Stripe pricing](https://stripe.com/pricing?tp=1) · [Stripe Billing pricing](https://stripe.com/en-us/billing/pricing) · [Stripe refund fees](https://support.stripe.com/questions/understanding-fees-for-refunded-payments).

## Local development, testing and deployment

The entire stack runs, tests and debugs on your laptop: `pnpm dev` starts `wrangler dev`, which runs the real Workers runtime (workerd) with local Durable Objects, KV and R2 saved under `.wrangler/state`, plus the converter image in Docker. The same build then deploys unchanged to staging and production. The code layout is in the Design tab.

### Local loop

- **Run:** `pnpm dev`. The app is at `http://alice.localhost:8787`; browsers send every `*.localhost` name to your own machine, so `bob.localhost` is a second, isolated tenant and hostname routing works exactly as in production.
- **Seed and reset:** `pnpm reset` wipes local state and creates two demo tenants from the fixture books.
- **Payments:** Stripe test mode, with `stripe listen --forward-to localhost:8787/stripe/webhook` delivering real webhook events locally.
- **Debugging:** breakpoints in TypeScript (source maps) through the inspector `wrangler dev` exposes, in VS Code or Chrome DevTools (press `d`). Local tracing shows every request's spans. Pure packages also debug in plain Node.
- **End-to-end debugging:** `pnpm e2e --ui` opens Playwright's UI mode against the local stack; every failed CI run uploads a Playwright trace you can open locally, step by step.

### Tests

Development is test-first: write the acceptance test, watch it fail, write the smallest code that passes, then refactor with the suite green. Nothing merges without a test that failed before the change.

- **Every async dependency sits behind an interface, and unit tests mock all of them.** The clock, ID generator, random bytes, R2, KV, the Book object stub, the Converter, Stripe, email and `fetch` come in through one `Ports` object. Unit tests pass in-memory fakes from `packages/testkit` that are deterministic, instant, and can fail on demand (timeouts, partial writes, 5xx responses, eviction mid-request).
- **Fakes are checked against the real thing.** Each fake has a contract suite that runs against the fake in Node and against the real binding in workerd; Stripe's runs nightly in test mode. The mocks can't drift from reality.
- **Async is deterministic.** A manual clock and scheduler replace timers. No test sleeps, polls or uses wall time. Races (two uploads, a write during a merge, an alarm during an import) are scripted step by step.
- **Shape and gates:** about 80% unit tests with fakes, 15% runtime tests in workerd and 5% Playwright journeys. The unit suite runs in under 30 seconds; CI enforces at least 90% line coverage on `packages/*`.
- **Mutation testing** (Stryker) runs weekly on `packages/gnucash` and `packages/sync`, where a missed bug costs money.

| Layer | Tool | Runs in | Covers |
| --- | --- | --- | --- |
| Unit | Vitest | Node | Every package and handler with injected fakes: money, invariants, reports on fixture books, lease rules, merge, signup, billing, XML parsing |
| Runtime | Vitest with `@cloudflare/vitest-pool-workers` | workerd (the real runtime) | The Book object with real SQLite, alarms and eviction; routing by hostname; upload/download; R2 snapshots |
| End-to-end | Playwright | Local stack, then staging | Signup with a Stripe test card, upload a book, edit on iPhone (WebKit) and Android (Chromium) viewports and on desktop (Chromium, Firefox), reconcile, refund, download |
| Visual | Playwright screenshots | Local stack | Every screen at 375, 768 and 1280 px, light and dark |
| Round-trip oracle | `python3-gnucash` in a CI container | GitHub Actions | Books edited on the web open in real GnuCash with nothing lost |

### Environments

|  | Local | Staging | Production |
| --- | --- | --- | --- |
| Command | `pnpm dev` | `wrangler deploy --env staging` | `wrangler deploy --env production` |
| Hostnames | `*.localhost:8787` | `*.stage.<domain>` | `*.<domain>` + customer domains |
| Data | `.wrangler/state` on disk | Own Durable Object namespace, KV and R2 bucket | Own Durable Object namespace, KV and R2 bucket |
| Stripe | Test mode | Test mode | Live mode |

### CI/CD (GitHub Actions, public repo)

Anyone can read the code and open pull requests; only `johnarleyburns` can ship. Deploys run only inside protected GitHub Environments, whose secrets a fork's pull request can never see.

1. **`ci.yml`, every pull request** (triggered by `pull_request`, never `pull_request_target`): typecheck, Biome lint, unit and runtime tests, then Playwright against a local stack in the runner. No secrets; the token is read-only.
2. **`deploy-staging.yml`, push to `main`** (only via a merged pull request): the job targets the `staging` environment and waits for your approval, then deploys, runs the full Playwright suite against staging and runs the round-trip oracle.
3. **`release.yml`, a `v*` tag:** targets the `production` environment, waits for your approval, deploys, runs a smoke suite, and rolls back with `wrangler rollback` if it fails.
4. **Durable Object changes** ship as wrangler migrations; each Book upgrades its `gcw_` tables the first time it wakes on a new version.

| Control | Setting |
| --- | --- |
| Environments `staging` and `production` | Required reviewer: `johnarleyburns` only; administrators can't bypass; "prevent self-review" off so you can approve your own runs; staging deploys only from `main`, production only from `v*` tags |
| Deploy secrets | Cloudflare API token and account ID, Stripe keys and the master key stored as environment secrets only, never repository or organization secrets |
| Job guard | Deploy jobs also require `github.repository == 'johnarleyburns/<repo>'` and `github.triggering_actor == 'johnarleyburns'`, so forks and other people can't start them at all |
| `main` ruleset | Pull requests required; all checks must pass; `CODEOWNERS` is `* @johnarleyburns` with code-owner review required; no force pushes or deletion |
| Tag ruleset | Only `johnarleyburns` can create, move or delete `v*` tags |
| Actions settings | Approval required for every outside contributor's workflow run; only GitHub-owned and verified actions, pinned to commit SHAs; Dependabot for actions and npm |
| Cloudflare tokens | One per environment, scoped to that account's Workers, KV, R2, Durable Objects and Containers; rotated every 90 days |
| Your account | Passkey sign-in on GitHub; secret scanning and push protection on |
| Contributor agreement | CLA Assistant is a required check on every pull request in both repos; johnarleyburns is allow-listed |
| Test and license gates | Required checks: unit, runtime and e2e suites, coverage at least 90% on packages, actionlint, and a dependency license scan that fails on GPL, AGPL or unknown licenses |
| iPhone repo | Same rulesets, CODEOWNERS and CLA; TestFlight and App Store uploads run only in a protected release environment that requires your approval |

Local values live in `.dev.vars`, which is gitignored; nothing secret is ever committed.

Sources: [Testing Durable Objects](https://developers.cloudflare.com/durable-objects/examples/testing-with-durable-objects) · [Drizzle with Durable Objects](https://orm.drizzle.team/docs/connect-cloudflare-do) · [Workers local development](https://developers.cloudflare.com/workers/testing/local-development/).

## Roadmap

Start small and useful: test signup and a chart of accounts, then double entry, then desktop round-trip with backups, then reconcile, all mobile-first. That's the MVP, about three months in. You are customer zero: nobody pays until your agency's books have run on it for 30 days.

&#91;embedded content: roadmap · 9 phases, MVP and launch gates\]

Phases run in order. Only the launch gate (highlighted) blocks taking money. P6 and P7 can swap if early customers are mostly small businesses. The iPhone track runs alongside every phase in its own repo, with its own agent, and ships with the web at each exit.

### Implementation plan

About three months to the MVP and five to a paid launch. Durations assume one developer full-time working with coding agents, from exhaustive plan files in `docs/plans/`; each phase ends with a deploy you approve.

| Phase | Duration | Web (saldima) | iPhone (saldima-ios) | Exit criteria |
| --- | --- | --- | --- | --- |
| P0 Foundations | 1–2 weeks | Repo created (not a fork) with LICENSE, CLA and NOTICE; rulesets, environments and approvals; pnpm workspace; `packages/testkit` ports and fakes; wrangler config for local, staging and production; Vitest in workerd and the Playwright harness; Durable Object spike (introspected schema, import/export of a real 20 MB book) | Repo from Voxglass conventions: XcodeGen, Swift 6 strict, SaldimaCore package with Swift Testing, Clock and IDGenerator seams, fakes; CI on Xcode 27; a SQLite actor that reads a fixture book's account tree; empty Liquid Glass shell; TestFlight lane behind your approval | Spike checks pass; hello-world reached staging and the empty app reached TestFlight, each only after your approval |
| P1 Test signup + chart of accounts | 2–3 weeks | Stripe test-mode Checkout on staging; webhook provisioning; subdomain routing; sign-in with passkey or password + TOTP; new book from templates, including an insurance agency; account tree create, edit, move, hide; mobile-first shell | New on-device book from the same templates; account tree create, edit, move, hide; save as a .gnucash file | You sign up on staging and build your agency's chart; the iPhone app builds the same chart offline and desktop GnuCash opens it |
| P2 Double entry | 3–4 weeks | Money, GUID and invariant modules; multi-split register with autocomplete; void, reverse, duplicate; balances; balance sheet and profit-and-loss; receipt capture from the phone camera; golden-vector export | Money, GUID and invariants replaying the web's golden vectors; register with splits; void, reverse, duplicate; balances and both reports; receipt capture into the local book | A month of entries on each; reports on both match GnuCash to the cent |
| P3 Desktop round-trip + backup | 3–4 weeks | Upload and download with leases; XML through the Converter; hourly R2 snapshots and restore; receipts inbox while checked out; three-way merge with conflict review; token-auth sync API for apps; round-trip oracle in CI | Open and save books in Files and iCloud Drive with gnclock; local snapshots; link to Saldima: sign in, download to edit, upload, merge review on the phone; receipts to the hosted book or inbox | Weekly round-trips desktop ↔ web and phone ↔ web with zero integrity diffs; a restore drill passes |
| P4 Reconcile | \~2 weeks | Reconcile on phone and desktop; find and filter; reconciled flags round-trip to desktop | Reconcile on the phone, offline or linked; find and filter | One real statement reconciled on phone, web and desktop, all agreeing |
| MVP gate | — | — | — | 30 days running your agency's books on the web and the phone |
| P5 Launch | 6–8 weeks | Live billing, custom domains, money-back refunds, roles, export and delete; CSV, OFX and QIF import; scheduled transactions; budgets; remaining reports; attachments; PWA; marketing site and docs | App Store submission: privacy label, accessibility audit, review notes; scheduled transactions, budgets, CSV import; purchase model decided | Web launch gate (cross-tenant tests, restore drill, report parity) passed and the app approved on the App Store |
| P6 Currencies and investments | \~6 weeks | Multiple currencies, trading accounts, securities, daily quotes, lots and capital gains, stock splits, portfolio reports, loan assistant, close book | Currencies, securities, prices, lots and the portfolio report | Investment fixtures round-trip with report parity on both |
| P7 Business | \~8 weeks | Customers, vendors, employees, jobs; invoices, bills, vouchers, credit notes, payments; PDF invoices; billing terms, tax tables, aging, sales tax | Customers, vendors, invoices, bills and payments; PDF invoices through the share sheet | Business fixtures round-trip; invoices post the same entries GnuCash does |

## iPhone app (parallel track)

Saldima for iPhone is a complete GnuCash-compatible book on the phone that needs no account. It can optionally link to a hosted Saldima book the same way desktop GnuCash does. It lives in its own repo, `saldima-ios`, and each phase ships on the web and the phone together (see Implementation plan).

### Two modes

|  | Standalone (default) | Linked to Saldima hosting (optional) |
| --- | --- | --- |
| Where the book lives | A `.gnucash` SQLite file in the app, or opened from Files or iCloud Drive | A local working copy of the hosted book |
| Account needed | None | A Saldima login on the user's subdomain |
| Editing | Always | After "Download to edit" checks the hosted book out to this phone, exactly as for desktop |
| Sending changes back | Save the file; desktop GnuCash opens it | "Upload" returns the book; if the web changed too, the three-way merge review opens on the phone |
| Receipts | Posted into the local book | Posted locally while checked out to this phone; otherwise sent to the hosted book, or its inbox if a desktop has it |
| Network use | None | Sign-in, download, upload and receipts only |

To the server, a linked phone is just another desktop: same lease, same `gcw/base` stamp, same merge rules. The hosted side needs only the token-auth sync API (Design tab).

### Project setup, from parso-voxglass

Checked against the public johnarleyburns/parso-voxglass repo on 2026-11-20; the agent copies its conventions into this repo's `CLAUDE.md`.

- **Identity:** bundle ID `guru.parso.saldima`, signed with your johnarleyburns Apple Developer team. You create the App ID and the App Store Connect record.
- **Toolchain:** Xcode 27 with the iOS 27 SDK; deployment target iOS 27.0; iPhone first, iPad allowed, no Mac Catalyst (Voxglass dropped it).
- **Design:** Liquid Glass throughout. Standard SwiftUI navigation, tab bars, toolbars and sheets get it automatically from the iOS 27 SDK; custom surfaces use the glass effect APIs. Layouts are checked with the transparency setting, Reduce Transparency, Increase Contrast and the largest Dynamic Type size.
- **Project:** XcodeGen `project.yml`, never a hand-edited `.xcodeproj`. Swift 6 language mode, complete strict concurrency, no warnings and no escape hatches, enforced by `scripts/check-swift6.sh`.
- **Code rules:** `@Observable` only (a CI gate rejects `ObservableObject`); `Date()` and `UUID()` only through `Clock` and `IDGenerator` seams; persistence is a hand-rolled `actor` over SQLite3 with numbered migrations, no GRDB.
- **Layout:** `SaldimaCore` (pure accounting logic, also builds on Linux), `SaldimaSync` (API client, lease and merge review), and the app target for UI. Core never imports SwiftUI, StoreKit or networking.
- **Workflow:** one task, one branch, one PR, squash auto-merge; `gh` CLI only; `.githooks` pre-commit runs `swift test`.
- **Devices:** physical iPhone first, no simulator unless you ask (Voxglass rule). CI compiles for generic iOS and runs `swift test` on a macOS runner.
- **Release:** TestFlight label state machine and human QA recorded on the issue, as in Voxglass. Upload lanes run only after your approval.

### Data and files

- Amounts stay as 64-bit integer numerator/denominator pairs, exactly as GnuCash stores them; the same invariants as the web.
- Edits go to a working copy in Application Support (never Caches, which iOS can purge). Save writes back atomically and keeps earlier versions by SHA-256.
- While a book is open the app writes a `gnclock` row, as desktop GnuCash does, and clears it on close.
- Our own tables are prefixed `gcw_` and are stripped on export, the same as on the web.

### Testing

- Test-first with Swift Testing, as on the web: failing test, smallest change, refactor green.
- Every async dependency sits behind a protocol: `BookStore`, `FileAccess`, `SyncAPI`, `Clock`, `IDGenerator`, `KeychainStore`, `ImageEncoder`. Tests use in-memory fakes from `SaldimaTestKit`, plus a `URLProtocol` stub that replays responses recorded by the web repo's API contract tests.
- **Golden vectors:** the web suite exports JSON cases (inputs, expected rows, balances, merge decisions); the Swift suite replays them, so both apps compute identical results.
- Files saved on the phone go through the same real-GnuCash oracle in the web repo's CI.

### Licensing and purchases

- AGPLv3 source in a public repo; you publish the App Store build as sole copyright holder (see License and contributor agreement). Dependencies must be MIT, BSD or Apache.
- Standalone use is free, with an optional one-time unlock through StoreKit 2 (open question). The hosting subscription is bought on the web; the app signs in to it. Apple may require it to be sold in the app too, so decide before P5.

## Coding-agent handoff

The spec is ready for P0 on both tracks. A coding agent can start once you finish the first checklist below; everything after that is the agent's job.

### You do first (human only)

- [ ] Register saldima.com and saldima.app.
- [ ] Create two empty public repos, johnarleyburns/saldima and johnarleyburns/saldima-ios, and give the agent push access to both.
- [ ] Create the GitHub Environments (staging, production, ios-release) with you as the required reviewer, and add the secrets yourself: per-environment Cloudflare tokens and account ID, Stripe test keys for staging, an App Store Connect API key for ios-release.
- [ ] Cloudflare Workers Paid plan on the account.
- [ ] Apple: App ID `guru.parso.saldima` and its App Store Connect record under your team.
- [ ] Install the CLA Assistant app on both repos.
- [ ] Put one real GnuCash 5.x book in a private place for the spike (an anonymised copy of the agency's books works), plus the GnuCash version you use.

### Rules for the agent

1. Read this Spec tab, then the Design tab, then `CLAUDE.md` in johnarleyburns/parso-voxglass for the iPhone conventions.
2. First PR in each repo: `CLAUDE.md` written from this doc (stack, commands, gates, rules below) and `docs/plans/P0.md` as a checklist of issues.
3. Test-first, always. A PR without a test that failed first isn't done.
4. Never deploy, create tags, upload to TestFlight, or touch secrets, environments or rulesets. Open PRs only; deploys and uploads run after you approve them.
5. Never copy code from gnucash-web, GnuCash or piecash; read them for behaviour only.
6. When a decision is missing, stop and ask, or add it to Risks and open questions; don't guess.
7. Run the tracks in parallel, but merge the web's golden vectors and API contract before the iPhone code that consumes them.

### P0 backlog: web (saldima)

1. Scaffold: `LICENSE`, `CLA.md`, `CONTRIBUTING.md`, `NOTICE`, `CODEOWNERS`, pnpm workspace, strict TypeScript, ESLint rules (no `number` for money, no `Math.random`), Vitest. Accept: CI runs the suite on a pull request.
2. Workflows `ci.yml`, `deploy-staging.yml` and `release.yml` with every guard in the CI/CD table, plus actionlint and the license scan. Accept: the staging deploy waits for your approval.
3. `packages/testkit`: the `Ports` interface, fakes for each port, a manual clock and scheduler, and contract tests. Accept: each contract suite passes against its fake in Node and its real binding in workerd.
4. `packages/gnucash`: `Money` and `Guid` with property-based tests (fast-check). Accept: exact add and round-half-even match GnuCash on fixture amounts.
5. Durable Object spike: introspect the schema from the fixture book; import and export a 20 MB book inside a Book in workerd; query the account tree. Accept: under 100 MB memory and 10 s, and the oracle opens the export.
6. wrangler config for local, staging and production; `pnpm dev` serves alice.localhost and bob.localhost; Playwright smoke on iPhone, Android and desktop projects.
7. `pnpm vectors` writes `fixtures/vectors/*.json` (money and GUID cases to start).

### P0 backlog: iPhone (saldima-ios)

1. Scaffold from Voxglass conventions: `project.yml`, `SaldimaCore` and `SaldimaTestKit` packages, `LICENSE`, `CLA.md`, `NOTICE`, `CLAUDE.md`, `.githooks`, `check-swift6.sh`, the `ObservableObject` gate.
2. CI on Xcode 27: compile for generic iOS and run `swift test`; a TestFlight lane in the ios-release environment.
3. `Clock` and `IDGenerator` seams with fakes.
4. `Money` and `Guid` in Swift replaying the web's vectors, pulled by a script pinned to a web commit. Accept: every vector passes.
5. SQLite actor that opens the fixture `.gnucash` read-only and lists the account tree. Accept: the tree matches the web's vector.
6. Empty Liquid Glass shell (tabs: Accounts, Register, Receipts, Reports, Settings). Accept: installs from TestFlight on your iPhone after your approval.

### Definition of done (every PR)

- A test failed first and passes now; all suites and gates are green with no warnings.
- A completion audit against the issue finds no gaps.
- `CLAUDE.md`, the phase checklist and this spec are updated if a decision changed.

## Risks and open questions

The biggest risk is a book that comes back subtly corrupted. That is why round-trip testing against real GnuCash gates every release.

| Risk | Likelihood | Mitigation |
| --- | --- | --- |
| Our own data layer misses a GnuCash rule, so web edits produce a book desktop GnuCash rejects or misreads | Medium | Invariants + round-trip oracle in CI on real fixture books; schema-version gate; snapshot before every replace |
| Exporting a large book in memory exceeds the 128 MB limit | Medium | 60 MB upload cap until measured; fallback builds the export in the Converter container |
| GnuCash changes its schema (6.0 is tentatively planned for March 2027) | Medium | Version gate refuses untested books; fixtures from every new release run in CI within a week |
| Cross-tenant data leak through an app bug | Low, severe | One Durable Object per tenant; tenant resolved before routing; CI tests that try to reach another tenant's data; per-tenant secrets |
| Dependence on one platform | Low | The customer's `.gnucash` download is always complete; AGPLv3 source lets anyone self-host on their own Cloudflare account |
| Use of the GnuCash name in marketing | Medium | Say "compatible with GnuCash" only; never use its name or logo as our brand or domain |
| Refund abuse under the guarantee | Low | One guarantee refund per email and per card |
| Support load at $5/month | High | Self-serve docs, export and restore buttons, status page; email support only |
| Holding people's financial records | — | Encrypted snapshots, 2FA required for editors, clear privacy policy, no analytics or third-party scripts |
| The App Store build's license position breaks, through an outside contribution without a CLA or a GPL dependency | Low | CLA as a required check; license scan in CI; MIT, BSD and Apache dependencies only |
| The web and iPhone apps compute different results | Medium | Golden vectors from the web suite replayed by the Swift tests; files saved on the phone go through the same GnuCash oracle |
| Apple requires the hosting subscription to also be sold in the app | Medium | Decide before P5: an in-app subscription at the same price, or sign-in only if review allows it |

Open questions:

- Final name and domain: see the Go-to-market tab; needs a trademark search before launch.
- Should edits be blocked while a book is checked out, or allowed with an automatic Conflict on upload? This spec blocks them.
- Which transactional email provider?
- Sales tax and VAT: collect it through Stripe Tax, or sell through a merchant of record?
- Bank feeds: is there an aggregator cheap enough at $5/month, or does that stay CSV/OFX import only?
- Which GnuCash versions to accept on upload: 4.14 and 5.x only, or older 3.x books too?
- Yearly discount: $50/year is 2 months free (17% off); the big budgeting apps discount about 40%. Worth testing $40/year?
