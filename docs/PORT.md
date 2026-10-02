# Plead for Android — port contract

> Copied from the iOS repo (`akhansuw-hub/arguewin`, `docs/android-port/`), where the Android project lived under `android/` until it was split into this repository. Paths below that start with `android/` are this repository's root; paths such as `ArgueWin/`, `docs/CONTRACTS*.md` and `supabase/` are in the iOS repo.

Plead ships on iOS (`ArgueWin/`, SwiftUI). This document is the binding brief for the Android port:
a **1:1 copy** of the iOS app on the same Supabase backend, same contracts (`docs/CONTRACTS.md`,
`docs/CONTRACTS-v2.md`, amendment `az` covers the Android-specific decisions), same screens, same flows,
same copy, same colours and same pixel art. Where Android has no equivalent of an iOS feature, the
mapping table below says what replaces it. Nothing else is up for reinterpretation: when in doubt,
open the Swift file and port what it does.

The user's rules in `CLAUDE.md` apply unchanged (contracts binding, no invented legal/business facts,
no prices in code, "Plead" never "ArgueWin" in anything a user can read, no "Premium", no emojis in
pushes, identifiers keep `arguewin`/`plead` as the iOS code does).

## 1. Where things live

```
android/                          Gradle root (settingsgradle.kts, gradle/libs.versions.toml, gradlew)
android/app/                      the one application module
android/app/src/main/java/app/plead/android/
    app/            ← ArgueWin/App            (PleadApplication, MainActivity, AppModel, AppRouter, AppGate, RootScreen, MainTabScreen, DemoHarness)
    designsystem/   ← ArgueWin/DesignSystem   (Tokens, Typography, Components, PixelAvatar, AvatarBadge, PleadWordmark)
    models/         ← ArgueWin/Models         (Models.kt — one file, additive-only like Models.swift)
    services/       ← ArgueWin/Services
    courtroom/      ← ArgueWin/Courtroom
    features/<name>/ ← ArgueWin/Features/<Name>   (lower-case folder, same file names with .kt)
    widgets/        ← PleadWidgets + Shared/WidgetSnapshot.swift (Glance)
    push/           ← FCM service, ongoing "court in session" notification (replaces Live Activity)
android/app/src/main/res/font/    Fraunces (same eight .ttf files as ArgueWin/Resources/Fonts)
android/app/src/main/res/drawable-nodpi/   every image from Assets.xcassets (largest scale of each imageset), snake_case names
android/app/src/test/             JVM unit tests  ← ArgueWinTests (port every test whose subject is logic/models/view-models)
android/app/src/androidTest/      Compose tests ← ArgueWinUITests + the ArgueWinTests that need a Compose host
android/local.properties.example  the config keys; android/local.properties is gitignored (real values)
docs/android-port/                this brief + STATUS.md (what is ported, what is owed)
```

Package: `app.plead.android`. `applicationId`: `app.plead.android` (mirrors `app.plead.ios`).
Version name/code come from `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` in `project.yml` (`1.0.0` / `6`).

## 2. Stack (fixed)

| Concern | Android |
|---|---|
| Language / UI | Kotlin 2.x, Jetpack Compose (Compose BOM), Material 3 only as a base — the design system is ours |
| Navigation | Navigation Compose; one `AppRouter` mirroring `ArgueWin/App/AppRouter.swift` (tabs, sheets, deep links) |
| State | `ViewModel` + `StateFlow`, one per `@Observable` model in Swift, same names (`AppModel`, `OnboardingModel`, `SettlementRoomModel`, …) |
| JSON | kotlinx.serialization; same keys and rules as `ArgueWin/Services/JSONCoding.swift` (snake_case, ISO-8601 dates, unknown enum values → the same fallbacks) |
| Backend | supabase-kt (auth, postgrest, realtime, storage, functions) against the same project; every write goes through the same edge functions as `EdgeFunctions.swift` |
| Auth | anonymous sign-in on first launch (as iOS); "secure account" = Sign in with Google (Credential Manager → Supabase `signInWith(Google)`), Sign in with Apple via Supabase OAuth browser flow (so an account secured on iPhone restores on Android), email one-time code (unchanged). Redirect scheme `plead://login-callback` (same as iOS) |
| Purchases | RevenueCat `purchases-android`, entitlement id `premium`, default offering + `exit_offer` offering, Google Play billing. Prices/trial text only ever from RevenueCat `StoreProduct`, never hard-coded |
| Push | Firebase Cloud Messaging; token registered through `register_push` with `platform: "fcm"` (amendment az). Payloads identical to APNs (title/body/data), no emojis |
| Live Activity | no Android equivalent → `push/CourtSessionNotification.kt`: an ongoing notification (`setOngoing(true)`) showing the same phase/turn/countdown state the Live Activity shows, updated from the same pushes and from app state. `register_live_activity` is never called from Android |
| Widgets | Glance app widgets reading the same `WidgetSnapshot` JSON (`Shared/WidgetSnapshot.swift`) stored via DataStore; same three families as PleadWidgets (small / medium / lock-screen ↔ small / medium / a 1×1 "glance" cell) |
| Deep / universal links | intent filters for `plead://` and `https://www.plead-app.com/join/*` (+ apex `plead-app.com` and legacy hosts, amendment bc), routed by `DeepLinkRouter` like iOS |
| Haptics | `HapticFeedback` / `Vibrator` with the same call sites as `UIImpactFeedbackGenerator` in Swift (`PaywallOpeningHaptics`, court beats) |
| Reduce Motion | `Settings.Global.ANIMATOR_DURATION_SCALE == 0` or the demo flag → same branches as `accessibilityReduceMotion` |
| ATT / AppsFlyer | no ATT prompt on Android. AppsFlyer Android SDK started only when `APPSFLYER_DEV_KEY` is set (amendment at); the same events as `Features/Paywall/Analytics.swift` |
| Review prompt | Google Play In-App Review at the same moment iOS calls `SKStoreReviewController` |
| Images | Coil for exhibit photos from Supabase Storage; pixel art via `painterResource` with `FilterQuality.None` (nearest-neighbour, as iOS `.interpolation(.none)`) |
| Fonts | Fraunces (display) → `res/font`; SF Pro Rounded has no Android equivalent → system default sans (Roboto) with the same weights and sizes; monospaced digits via `FontFeature` `tnum` |
| Min / target SDK | minSdk 26, targetSdk 36 (raised from 35 on 2026-10-02: Google Play rejects uploads targeting 35), compileSdk 36 (wave 1: supabase-kt 3.2 / Compose 1.9 refuse to compile against 35; build-time only, see STATUS.md) |
| JDK | Android Studio's bundled JBR 21: `export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` |
| SDK | `~/Library/Android/sdk` (write `sdk.dir` into `android/local.properties`) |

## 3. Configuration

`android/local.properties` (gitignored) holds the same keys as `Config.xcconfig`:

```
sdk.dir=/Users/arifkhan/Library/Android/sdk
SUPABASE_URL=https://YOUR-PROJECT-REF.supabase.co
SUPABASE_ANON_KEY=YOUR_SUPABASE_ANON_KEY
REVENUECAT_API_KEY=goog_YOUR_REVENUECAT_PUBLIC_KEY
APPSFLYER_DEV_KEY=YOUR_APPSFLYER_DEV_KEY
GOOGLE_WEB_CLIENT_ID=YOUR_GOOGLE_OAUTH_WEB_CLIENT_ID
```

`app/build.gradle.kts` reads them into `BuildConfig` fields with the same placeholder semantics as
`AppConfig.swift` (`YOUR_` → treated as unset; `isSupabaseConfigured`; debug builds default to demo mode
when Supabase is not configured, like commit `83141d4`). `google-services.json` is gitignored; the FCM
service must start cleanly without it (token registration is simply skipped, as when APNs is unavailable).

## 4. Demo harness

`ArgueWin/App/DemoHarness.swift` lists every `-AW…` launch flag. Android takes the same flags as intent
extras on `MainActivity` with the same names minus the dash (`adb shell am start -n app.plead.android/.app.MainActivity
--es AWDemo YES --es AWDemoStore empty --es AWTab court`). Every flag in DemoHarness.swift must work,
because that is how the port is verified (screenshots side by side with `docs/screenshots/v2/*`).
`DemoTrialSimulator` and `PreviewData` are ported in full.

## 5. Conventions

- **File-for-file.** Each Swift file becomes one Kotlin file of the same name (`CourtDock.swift` → `courtroom/CourtDock.kt`). Types, functions and properties keep their names (Swift `camelCase` already matches Kotlin). Enum raw values, JSON keys, UserDefaults keys (→ DataStore keys) and notification identifiers stay byte-identical: iOS and Android share the backend and pushes.
- **Copy is identical.** Every user-facing string is copied verbatim from the Swift source (inline strings, as iOS does; no strings.xml translation pass). Same punctuation, same capitalisation.
- **Visuals are identical.** Colours from `Tokens.swift` (`AWColor` → `PleadColor`), radii, spacing, fonts and sizes from `Typography.swift`/`CourtStyle.swift`. Pixel art is the same PNGs, drawn nearest-neighbour. Motion: same durations and curves (`OnboardingMotion`, `PaywallEntrance`, `CourtMotion`), same Reduce-Motion fallbacks.
- **Additive shared files.** `models/Models.kt`, `designsystem/Tokens.kt`, `gradle/libs.versions.toml`, `AndroidManifest.xml`, `app/build.gradle.kts`, `app/AppRouter.kt` are shared: add, never rename/reorder/delete. Prefer a new file to editing a shared one; say in your report if something must change elsewhere.
- **Tests.** Port the iOS test for everything you port (`ArgueWinTests/<X>Tests.swift` → `src/test/.../XTests.kt`, JUnit 4 + kotlinx-coroutines-test; Compose-only ones → `src/androidTest`). Keep the test names.
- **No stubs.** A screen or service is done when it does what the Swift one does. If something cannot be ported (platform gap) it is listed in `docs/android-port/STATUS.md` under "Not portable" with the replacement, not silently dropped.
- **Backend is shared.** Only the `supabase/` owner changes edge functions/migrations, and only per amendment az. Everything else calls the existing functions with the existing shapes.

## 6. Build and verify (every agent, before reporting)

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android
./gradlew --offline :app:assembleDebug :app:testDebugUnitTest     # drop --offline the first time deps are fetched
./gradlew :app:lintDebug                                           # zero errors
```

Report: build result, unit test counts (run/passed), lint errors, anything not ported and why.
**Do not start the Android emulator** (only one exists on this Mac and the integrator drives it) and do not
run `xcodebuild`/`xcodegen`. Delete `android/app/build` and `android/.gradle` in your worktree when done.

## 7. Work split and ownership

Every agent works in its own git worktree branched from `android-port`, owns only the folders listed,
commits on its branch, and reports. The integrator merges into `android-port`.

| Wave | Task | Owns |
|---|---|---|
| 1 | Skeleton: Gradle project, manifest, config, fonts, assets, `Tokens.kt`, `Typography.kt`, `Models.kt`, `JSONCoding.kt`, `AppConfig.kt`, `SupabaseService.kt`, `PleadApplication`, `MainActivity` + `AppRouter` shell with placeholder tabs, `DemoHarness` flag parsing | `android/` (all), `docs/android-port/` |
| 2a | Services: `EdgeFunctions`, `AuthService`, `ProfileService`, `CaseStore`, `CaseFlow`, `StorageService`, `DraftExhibit`, `NotificationPrefs`, `NotificationPermissionService`, `PurchasesService`, `PushService` (token registration), `DeepLinkRouter`, `WidgetSnapshotStore`, `DemoTrialSimulator`, `PreviewData` + their tests | `services/`, `src/test/…/services` |
| 2b | Design system + app shell: `Components`, `PixelAvatar`, `AvatarBadge`, `PleadWordmark`, `AppModel`, `AppGate`, `RootScreen`, `MainTabScreen`, `Features/Court/CourtTabView` (tab host only) + tests | `designsystem/`, `app/` |
| 3a | Courtroom: everything in `ArgueWin/Courtroom` + `Features/Deliberation` + the court tab body + `CourtCaseCall/CourtEntrance/CourtHelp/CourtMotion/MockTrialScene` tests | `courtroom/`, `features/deliberation/`, `features/court/` |
| 3b | Onboarding + ColdOpen + Account (`SecureAccountView`) + their tests | `features/onboarding/`, `features/coldopen/`, `features/account/` |
| 3c | Paywall (all 15 files incl. `Analytics`, `ExitOffer`, courtroom hero sprites) + tests | `features/paywall/` |
| 3d | Home, Cases, CaseDetail, FileCase, Defence, Summons, Scheduling + tests | those `features/*` folders |
| 3e | Settlement, Judgement, Settings, Us + tests | those `features/*` folders |
| 3f | Widgets (Glance), FCM service + court-session notification, Google Play review prompt hook, and the **backend** side of amendment az (`push_tokens.platform`, `register_push`, `_shared/notify.ts` FCM sender, tests) | `widgets/`, `push/`, `supabase/` |
| 4 | Integration: merge, emulator run of every DemoHarness flag, screenshots vs `docs/screenshots/v2`, `STATUS.md` | integrator |
