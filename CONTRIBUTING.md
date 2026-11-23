# Contributing to Saldima

<!-- SPDX-License-Identifier: AGPL-3.0-only -->

Saldima is licensed under `AGPL-3.0-only`. Outside contributions require the
[Contributor License Agreement](CLA.md), confirmed through the CLA Assistant
check on your pull request. You keep your copyright and grant the project the
rights described there.

## Before you start

- Open or claim an issue. Small, single-purpose changes are much easier to
  review.
- Read [`CLAUDE.md`](CLAUDE.md) for the stack, commands and rules. It applies to
  humans and agents alike.
- Do not copy code from GnuCash, gnucash-web, piecash or any other GPL project.
  Read them for behaviour only.

## Test-first

1. Write the acceptance test and watch it fail for the right reason.
2. Write the smallest change that makes it pass.
3. Refactor with the suite green.

Every async dependency goes through the injected `Ports` object. Unit tests use
deterministic fakes; contract tests cover the real binding. Tests never sleep
or read wall time.

## Local checks

```sh
pnpm install
pnpm typecheck
pnpm lint
pnpm test:unit
```

Later phases add `pnpm test:runtime`, `pnpm e2e` and the round-trip oracle. All
gates must be green before a PR is ready.

## Pull requests

- One branch, one small PR, squash merge. Never push to `main`.
- Link the issue and include the failing test that now passes.
- Add the SPDX header to every file you create.
- New dependencies must be MIT, BSD, Apache, ISC, 0BSD or CC0. GPL, AGPL and
  unknown licences fail CI.
- Never commit secrets, `.dev.vars` or customer data. Use anonymised fixtures.
- Do not deploy, tag, or touch environments, rulesets or secrets. The owner
  approves every release.

## Code style

- TypeScript strict mode, `bigint` for money, GUIDs through `IdGenerator`.
- Keep `packages/gnucash` pure: no network, database driver or UI framework.
- Prefer small modules with explicit ports over globals and module-level state.
- Accessibility, mobile-first layouts and light/dark themes are requirements,
  not polish.

## Reporting security issues

Do not open a public issue for a vulnerability. Contact the owner privately via
the GitHub account `johnarleyburns`.
