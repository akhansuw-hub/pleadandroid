# Plead for Android

Plead is a couples "courtroom" app: one partner files a case, the other is summoned, both argue in a pixel-art
court and an AI judge (with a juror panel) delivers a verdict and a judgement. This repository is the **Android
app**: a 1:1 port of the iOS app (SwiftUI, repo `akhansuw-hub/arguewin`) on the **same Supabase backend**, with the
same screens, flows, copy, colours and pixel art.

- Port brief (binding): [docs/PORT.md](docs/PORT.md)
- What is ported, what is owed, platform gaps: [docs/STATUS.md](docs/STATUS.md)
- Screenshots (Pixel 7 API 35 emulator, demo mode): [docs/screenshots/](docs/screenshots/)
- Contracts (names, enums, tables, edge-function shapes, product decisions): in the iOS repo,
  `docs/CONTRACTS.md` + `docs/CONTRACTS-v2.md` (amendment `az` covers Android) and `docs/android-port/`.
- Backend (Supabase edge functions + migrations): in the iOS repo under `supabase/`, shared by both apps.

This repository was split from the `android/` folder of the iOS repo (`git subtree split`), so its history is every
commit that touched the Android project.

## Layout

```
app/src/main/java/app/plead/android/
    app/           AppModel, router, root/tab screens, DemoHarness (ArgueWin/App)
    designsystem/  tokens, typography, components, pixel avatars (ArgueWin/DesignSystem)
    models/        Models.kt (ArgueWin/Models)
    services/      Supabase, auth, cases, purchases, push token, deep links, widget snapshot (ArgueWin/Services)
    courtroom/     the pixel courtroom (ArgueWin/Courtroom)
    features/<x>/  one folder per iOS feature (ArgueWin/Features/<X>)
    widgets/       Glance widgets (PleadWidgets + Shared/)
    push/          FCM service, court-session notification (Live Activity replacement), Play review prompt
app/src/test/          JVM unit tests (Robolectric where a Context is needed)
app/src/androidTest/   Compose tests (need an emulator)
local.properties.example
```

Package and `applicationId`: `app.plead.android`. minSdk 26, targetSdk 35, compileSdk 36.
Version name/code are read from the iOS repo's `project.yml` when the project sits next to it; standalone they fall
back to `1.0.0` / `6` (`app/build.gradle.kts`).

## Prerequisites

- **JDK:** Android Studio's bundled JBR 21:
  `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`
- **Android SDK:** `~/Library/Android/sdk` (platform 36, build tools), plus an emulator image (API 35) for the Compose
  tests.
- **`local.properties`** (gitignored): copy `local.properties.example` and fill in. Same keys as the iOS
  `Config.xcconfig`; any value still containing `YOUR_` / `YOUR-` is treated as **unset**, exactly as on iOS:

  | Key | Unset means |
  |---|---|
  | `sdk.dir` | required by Gradle |
  | `SUPABASE_URL`, `SUPABASE_ANON_KEY` | no backend: debug builds start in demo mode |
  | `REVENUECAT_API_KEY` | must be the RevenueCat **Google Play** key (`goog_…`), not the iOS `appl_` key; unset = purchases unavailable |
  | `APPSFLYER_DEV_KEY` | AppsFlyer never starts |
  | `GOOGLE_WEB_CLIENT_ID` | Sign in with Google unavailable |

- **`app/google-services.json`** (gitignored, optional): the Firebase config for FCM. Without it the app runs and push
  is simply inert (no token is registered).

## Build and test

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
./gradlew --no-daemon :app:connectedDebugAndroidTest     # with an emulator running
```

`assembleRelease` produces an unsigned APK until a release signing config exists (see "Needs the user").
Report test counts and lint errors (zero) when you change something.

## Demo flags

Debug builds take the iOS `DemoHarness` launch flags (`ArgueWin/App/DemoHarness.swift`) as intent extras with the same
names minus the dash. Release builds ignore them.

```sh
adb shell am start -S -n app.plead.android/.app.MainActivity \
    --es AWDemo YES --es AWDemoStore empty --es AWTab court
```

With no extras, a debug build with no Supabase project configured defaults to demo mode. The full list is at the top
of `app/src/main/java/app/plead/android/app/DemoHarness.kt`; the most used:

- `AWDemo YES` · `AWDemoStore solo|empty|premium|unpaid|…|settled` · `AWTab home|cases|court|us`
- `AWSheet fileCase|defence|paywall|exitOffer|settings|summons|judgement|settlementRoom|…`
- `AWOnboardStep 1…12|<screen_id>` · `AWMockTrialBeat <beat>` · `AWColdOpen full|sting|none` · `AWNoReviewPrompt YES`
- `AWCourtFixture opening|evidence|cross|ruling|objection|deliberation|verdict|replay`
- `AWPlan annual|monthly|weekly` · `AWTrial no` · `AWExitOffer no` · `AWPricesLoading YES`
- `AWWidgetPreview YES|lock|small|medium|states|activity|live` (renders the real Glance widgets over the app)
- `AWLiveActivity summons|verdict|verdictReady|auto` (the ongoing court-session notification)

For screenshots, run `adb shell cmd package compile -m speed -f app.plead.android` after installing (debug cold
launches are slow) and set the status bar to 9:41 with SystemUI demo mode.

## Needs the user

Things the app cannot do without the owner's accounts or decisions (kept current in [docs/STATUS.md](docs/STATUS.md)):

- **Firebase:** a Firebase project for `app.plead.android`; `app/google-services.json` and the `FCM_SERVICE_ACCOUNT`
  secret on the Supabase project (amendment az).
- **Backend deploy** (iOS repo): `db push` of `20260930000100_push_tokens_platform.sql`, deploy `register_push` and the
  functions that import `_shared/notify.ts` / `_shared/push.ts` / `_shared/fcm.ts`.
- **RevenueCat:** the Google Play public key (`goog_…`); the Play app in RevenueCat with the `premium` entitlement,
  default offering and `exit_offer` offering.
- **Play Console:** the app, subscription products matching iOS, a release upload keystore and a `release`
  `signingConfig` (none is committed or invented), the Data safety form (review `AD_ID`, `USE_BIOMETRIC` /
  `USE_FINGERPRINT`, AppsFlyer vendor permissions), and whether `targetSdk 36` is now required.
- **Google sign-in:** `GOOGLE_WEB_CLIENT_ID` and the Supabase Google provider.
- **Sign in with Apple on Android:** the Supabase Apple provider for the OAuth (web) flow (Services ID + key).
- **App Links:** `/.well-known/assetlinks.json` on `plead-drab.vercel.app` with the release signing SHA-256.
- **AppsFlyer:** the dev key, if attribution should run on Android.
- **Legal / business facts:** bracketed placeholders until supplied, as on iOS.
