# Saldima for Android

<!-- SPDX-License-Identifier: AGPL-3.0-only -->

Saldima is a standalone, GnuCash-compatible book for Android. No Saldima
account, subscription or network is required. Linking to a hosted Saldima book
is optional and removable.

**Compatible with GnuCash.** Independent project, not affiliated with or
endorsed by the GnuCash project.

- Agent contract and plan: [`CLAUDE.md`](CLAUDE.md), [`docs/plans/P0.md`](docs/plans/P0.md)
- iPhone track: https://github.com/johnarleyburns/saldima-ios
- Web track: https://github.com/johnarleyburns/saldima

## Build

```sh
./gradlew :core:test
./gradlew :app:assembleDebug
```

Requires JDK 17 and Android SDK 36. The **Supporter** product is a one-time,
optional Play Billing consumable (`saldima_supporter`); it never gates a
feature. Play Console product and price are owner-configured.

## Licence

AGPL-3.0-only. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
