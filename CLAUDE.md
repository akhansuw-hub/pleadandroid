# Working in the Plead Android repo

Read [README.md](README.md) first (layout, prerequisites, demo flags), then [docs/PORT.md](docs/PORT.md) (the binding
port brief) and [docs/STATUS.md](docs/STATUS.md) (what is ported, what is owed).

## The iOS app is the reference

- This app is a **1:1 port** of the iOS app in `akhansuw-hub/arguewin` (read-only from here). When in doubt, open the
  Swift file and port what it does: same file names (`CourtDock.swift` → `courtroom/CourtDock.kt`), same type and
  property names, same copy, colours, sizes, motion and Reduce-Motion fallbacks.
- **Contracts are binding** and live in the iOS repo: `docs/CONTRACTS.md` (v1) + `docs/CONTRACTS-v2.md` (v2 delta and
  lettered amendments; amendment `az` covers the Android decisions) + `docs/android-port/`. A later amendment overrides
  earlier text. Before changing behaviour, the decision gets a new amendment there first; do not silently diverge.
- **The backend is shared** (`supabase/` in the iOS repo). Enum raw values, JSON keys, DataStore/UserDefaults keys and
  notification identifiers stay byte-identical with iOS. Backend changes happen in the iOS repo, not here.
- A platform gap is never dropped silently: list it in `docs/STATUS.md` under "Not portable" with its replacement.

## Shared files are additive-only

`models/Models.kt`, `designsystem/Tokens.kt`, `gradle/libs.versions.toml`, `AndroidManifest.xml`,
`app/build.gradle.kts`, `app/AppRouter.kt`: add, never rename, reorder or delete. Prefer a new file.

## Parallel agents

One owner per folder for a task; read anything, edit only what you own, and say in your report if something elsewhere
must change. Never `git stash`, reset, or reformat files you do not own. Do not commit unless the user asked.

## Always verify

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug   # zero lint errors
./gradlew --no-daemon :app:assembleRelease                                        # must assemble
./gradlew --no-daemon :app:connectedDebugAndroidTest                              # flows you touched (emulator)
```

Report test counts and lint errors. Delete `app/build`, `.gradle`, `build`, `.kotlin` when done (disk).

## Screenshots

Capture with the demo flags (README), status bar at 9:41 (SystemUI demo mode). Save as
`docs/screenshots/<feature>-<what>.png`, named after the iOS counterpart in the iOS repo's `docs/screenshots/v2/` when
one exists. **Open and look at every screenshot you take** before calling a screen done: clipping, overlap with the
status/navigation bars, truncation, compared against the iOS capture or the current Swift source.

## Facts you must not invent

- Legal and business facts stay as bracketed placeholders until the user supplies them: `[LEGAL ENTITY NAME]`,
  `[SUPPORT EMAIL]`, `[GOVERNING LAW / COURTS]`, `[EFFECTIVE DATE]`, etc.
- Prices, trial lengths, discounts and savings come from RevenueCat / Google Play (`StoreProduct`) only. No hard-coded
  price ships in a release build (debug preview prices are marked as such).
- No invented claims about AI training, retention, encryption or deletion.
- Never invent keys, keystores or config values: `local.properties`, `google-services.json`, keystores and build
  outputs are gitignored and never committed.

## Copy rules

- The product is **Plead**. Never "ArgueWin"/"Arguewin" in anything a user, reviewer or the judge can read (UI,
  notifications, share text, store listing, prompts). Identifiers keep `arguewin`/`plead` as the iOS code does.
- Never "Premium" in user-facing copy (`premium` is only the internal RevenueCat entitlement id).
- No emojis in notifications. Lock-screen copy is generic unless the user turned on "Show case details on Lock Screen".

## Emulator

Use the existing AVD for captures and Compose tests; do not create, wipe or delete AVDs, and shut the emulator down
when done.

## Commits (only when asked)

Subject: short, imperative, prefixed by area (`Android: …`, `Widgets: …`, `Paywall: …`). Body: a few wrapped lines on
what changed and why, test counts if relevant. End with the trailer for the model doing the work:

```
Co-Authored-By: Claude <model name> <noreply@anthropic.com>
```
