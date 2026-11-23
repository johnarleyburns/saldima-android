<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Android internal testing setup (owner)

The app is **free and standalone**. The only product is the optional one-time
**Supporter** consumable `saldima_supporter`; it never unlocks a feature.

## 1. Play Console app

1. Create the app in Play Console:
   - Name: `Saldima`
   - Default language, app, free
   - Package name: `guru.parso.saldima`
2. Play App Signing: let Google manage the app-signing key; you keep an
   **upload key**.
3. Set up the store listing enough to create an internal testing track.

## 2. Upload key

```sh
keytool -genkeypair -v \
  -keystore ~/saldima-upload.jks \
  -alias saldima \
  -keyalg RSA -keysize 4096 -validity 10000
```

Keep the keystore and passwords safe; they are not in the repo.

## 3. GitHub `android-release` environment

Create an environment named `android-release` with **you as required reviewer**.
Add environment secrets:

| Secret | Value |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 < ~/saldima-upload.jks \| tr -d '\n'` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `saldima` |
| `ANDROID_KEY_PASSWORD` | key password |

```sh
base64 < ~/saldima-upload.jks | tr -d '\n' | gh secret set ANDROID_KEYSTORE_BASE64 --env android-release
gh secret set ANDROID_KEYSTORE_PASSWORD --env android-release
gh secret set ANDROID_KEY_ALIAS --env android-release --body saldima
gh secret set ANDROID_KEY_PASSWORD --env android-release
```

The workflow is `.github/workflows/android-release.yml` (manual dispatch only).
It builds a signed AAB + APK and uploads them as a workflow artifact. The agent
must never upload to Play; you download the AAB and upload it.

## 4. Supporter product

Play Console → Monetize → Products → **In-app products** → Create:

- Product ID: `saldima_supporter`
- Name: `Supporter`
- Description: optional one-time thank-you; every feature is already free
- Price: your choice (Voxglass uses $9.99)
- Activate it.

Add your Google account under **License testing** so test purchases are free.
The product only works for builds installed from Play (internal testing), not a
sideloaded debug APK.

## 5. Internal testing

1. Actions → **android-release** → Run workflow. Approve the environment.
2. Download the `saldima-android-<run>` artifact.
3. Play Console → Testing → **Internal testing** → Create release → upload the
   `.aab`, add release notes, roll out.
4. Add your Google account as an internal tester, open the opt-in link on the
   phone, install Saldima from Play.
5. Test: `Accounts`, supporter purchase, cancel, and confirm no feature is
   gated.

## 6. Direct USB build (no Play)

For UI testing only:

```sh
./gradlew :app:installDebug
# or
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Enable Developer options and USB debugging on the phone. Play Billing and the
real Supporter product require the Play-installed internal build above.
