# Plead for Android: port status

> Copied from the iOS repo (`akhansuw-hub/arguewin`, `docs/android-port/`), where the Android project lived under `android/` until it was split into this repository. Paths below that start with `android/` are this repository's root; paths such as `ArgueWin/`, `docs/CONTRACTS*.md` and `supabase/` are in the iOS repo.

What is ported, what is owed, and what cannot be ported 1:1. Each wave updates its own section; the
integrator (wave 4) keeps the summary current. Brief: [PORT.md](PORT.md).

## Summary (final integration)

- **Ported and wired:** every iOS screen and flow: cold open, onboarding (11 screens, incl. the mock trial on the real
  courtroom stage and the Play review prompt after it), link step, paywall + exit offer, secure account, Home, Cases,
  case record, filing, defence, summons, scheduling, Court (every dock mode, verdict, deliberation, help), settlement,
  judgement, Settings (incl. the onboarding preview), Us, the Glance widgets (small / medium / 1x1 / one-row strip),
  FCM push and the ongoing "court in session" notification (the Live Activity's replacement). `DemoHarness` flags
  drive all of it on the emulator; screenshots in [screenshots/](screenshots/).
- **Build (final, `polish/all`: court + misc + shell polish integrated):** `assembleDebug` + `assembleRelease` (unsigned:
  no release signing config, see "Needs the user") OK; JVM tests 835 run, 0 failures; lint 0 errors (44 warnings,
  5 hints); Compose tests on the `visage_phone` emulator (API 35) 53 run, 52 passed, 1 skipped, 0 failures (first full
  run, 10 min); backend `deno check` clean, `deno test` 279 passed, 0 failed (unchanged by the polish branches).
- **Compose UI tests:** every ArgueWinUITests suite is ported: 43 instrumented tests under
  `src/androidTest/.../features/{court,shell,onboarding}/` on one shared support package (see "Compose UI tests"
  below), next to the 8 earlier smoke tests and the 2 tab-state tests (`features/shell/TabStateComposeTests`). Run on
  the `visage_phone` emulator (Pixel 7, API 35, `connectedDebugAndroidTest`): 53 run, 52 passed, 1 skipped
  (`testDefenceDueOpensTheDefenceFlow`, an assumption, as on iOS), 0 failed (~10 min).
- **Owed:** the Wave 4 visual gaps are fixed (polish branches, listed under Wave 4); what is still open: the onboarding
  widget illustration under ~360 dp, the judge name at large text on 360 dp phones (as on iOS), the Liquid Glass pill
  behind the selected tab, slow debug cold launches on the emulator.
- **Needs the user:** see the last section.

## Wave 1: skeleton (done)

| iOS | Android | Notes |
|---|---|---|
| `project.yml`, `Config.xcconfig` | `android/` Gradle project, `app/build.gradle.kts`, `local.properties(.example)` | AGP 8.13.2, Gradle 9.1.0, Kotlin 2.2.20. Version name/code are read from `project.yml`. |
| `Info.plist` / entitlements | `AndroidManifest.xml` | `plead://`, `plead://login-callback`, `https://plead-drab.vercel.app/join/*` + legacy hosts (autoVerify). FCM service and Glance receiver declared (stubs until 3f). |
| `Assets.xcassets`, `Resources/Fonts` | `res/drawable-nodpi`, `res/font`, adaptive icon, `values/asset_colors.xml` | `tools/android/import_assets.sh` (rerunnable). iOS scale per image: `designsystem/PleadAssets.kt`. |
| `DesignSystem/Tokens.swift` | `designsystem/Tokens.kt` | `PleadColor`, `PleadRadius`, `PleadSpacing`, `PleadFont`, `PleadMotion`, `PleadCopy`. |
| `DesignSystem/Typography.swift`, `CourtFont` (CourtStyle.swift) | `designsystem/Typography.kt` | `FrauncesFont`, `PleadType`, `TextStyleKind`, `CourtFont`. `CourtStyle.kt` (wave 3a) must not redefine `CourtFont`. |
| `Models/Models.swift` | `models/Models.kt` | Every type, case, field and JSON key. |
| `Services/JSONCoding.swift` | `services/JSONCoding.kt` | One `Json` (`JSONCoding.json`), `SupabaseDate`, `parseUUID`. |
| `Services/AppConfig.swift`, `SupabaseService.swift` | `services/AppConfig.kt`, `SupabaseService.kt` | |
| `App/DemoHarness.swift` (flag parsing) | `app/DemoHarness.kt` | Every `AW…` flag as an intent extra; typed accessors. `model()` / `apply(to:)` owed by 2b. |
| `App/AppRouter.swift` (state) | `app/AppRouter.kt` | `open`, `route`, `presentSettlementPrompt` owed by 2a/2b (need CaseStore/CaseFlow/DeepLink). |
| `App/MainTabView.swift` (tab bar) | `app/MainTabScreen.kt` | Real tab bar, placeholder bodies and sheet slot. |
| Tests | `TypographyTests`, `ModelDecodingTests` (+ `LimitsTests`, `ExhibitLabelTests`, `TrialPhaseTests`) | Plus Android-only `AppConfigTests`, `DemoHarnessTests`, `AppRouterTests`. |

Tests owed with their subjects: `encodesAvatarForProfileUpdate`, `edgeErrorEnvelope`, `draftsGetSequentialLabels`,
`CaseFlowTests`, `DeepLinkTests` (2a); `ExhibitDockCopyTests` (3a); `AccountDeletionTests` (3e); the
`PaywallCopy` half of `websiteDomain` (3c).

## Wave 2a: services, demo simulator, app shell (done)

| iOS | Android | Notes |
|---|---|---|
| `Services/EdgeFunctions.swift` | `services/EdgeFunctions.kt` | Every function, same names/bodies; `{ok:false}` envelope + HTTP errors → `EdgeError`; 402/403 posted on `AppNotifications`. `PushRegistration.token` adds `platform: "fcm"` (amendment az). Static `EdgeError.*Message(for:)` → `EdgeErrors.*`. |
| `Services/AuthService.swift` | `services/AuthService.kt` | Anonymous-first, session restore + server validation, email OTP link / sign-in, Google (Credential Manager → `IDToken`), Apple via Supabase OAuth browser flow (`appleOAuth(link:)`; `isAppleAvailable()` probes `/auth/v1/settings` so the button can degrade), `plead://login-callback` (PKCE code, fragment tokens, `error_code` → `linkConflicts`). `LocalAuthBackend` for demo/tests. |
| `ProfileService`, `StorageService`, `DraftExhibit`, `NotificationPrefs`, `NotificationPermissionService` | same names in `services/` | Same tables, column lists, bucket and paths. POST_NOTIFICATIONS via a prompt MainActivity installs; `notification_status` key unchanged. |
| `Services/CaseStore.swift`, `CaseFlow.swift` | `services/CaseStore.kt`, `CaseFlow.kt` | Full port incl. Realtime (same channel names/filters), settlements, judgements, demo hooks. `SettlementPrompt` lives in CaseStore.kt. |
| `PurchasesService.swift` | `services/PurchasesService.kt` | RevenueCat Android, `premium`, default + `exit_offer`; prices only from `StoreProduct` (base-plan price); annual trial = default option's free phase (Google only returns eligible offers). Needs an Activity: `purchases.activity` (MainActivity sets it). |
| `PushService.swift` | `services/PushService.kt` | Same `register_push` scheduling (tests ported); FCM token when Firebase is initialised, else skipped; payload helpers take FCM/APNs maps. Never calls `register_live_activity`. |
| `LiveActivityService.swift` + `Shared/PleadCaseActivityAttributes.swift` (state half) | `services/CourtSessionState.kt` | `PleadCaseActivityAttributes` / `ContentState` (typealias `CourtSessionState`, `CourtSessionPhase`), `PleadActivityCopy`, `LiveActivityPlanner`. The service itself is not ported (see Not portable). |
| `DeepLinkRouter.swift` | `services/DeepLinkRouter.kt` | `java.net.URI`; consumes `LaunchLinks.links` in `AppModel.start()`. |
| `Shared/WidgetSnapshot.swift`, `WidgetSnapshotStore.swift` | `services/WidgetSnapshot.kt`, `WidgetSnapshotStore.kt` | Same JSON (camelCase, sorted keys, ISO dates), stored in the App Group DataStore under `widget-snapshot.json`; `WidgetSnapshot.load()` is what the Glance widget reads; `WidgetCenter.reloadAllTimelines()` broadcasts APPWIDGET_UPDATE to `WidgetCenter.receivers`. |
| `WidgetSetupService.swift`, `TrackingPermissionService.swift`, `AttributionService.swift` | same names | Widgets pinned = `AppWidgetManager.getAppWidgetIds`, family from width. Tracking always `authorized` (amendment az). AppsFlyer starts with a dev key outside demo/tests. |
| `DemoTrialSimulator.swift`, `Preview Content/PreviewData.swift` | `services/DemoTrialSimulator.kt`, `services/PreviewData.kt` | Full ports (line banks, catalogs, settlement partner). |
| `App/AppModel.swift`, `AppGate.swift`, `RootView.swift`, `AppDelegate.swift`, `ArgueWinApp.swift` | `app/AppModel.kt` (ViewModel held by `PleadApplication`), `AppGate.kt`, `RootScreen.kt` (+ LaunchView, LoadFailedView), `PleadApplication.kt` (notification channels, AppsFlyer, the model), `MainActivity.kt` (flags, links, push taps, permission prompt, resume/pause) | Placeholders name their wave ("Onboarding — wave 3b"). |
| `DemoHarness.swift` (model half), `AppRouter` routing, `MainTabView` behaviour | `app/DemoHarnessModel.kt`, `AppRouter.kt` (`open`, `route`, `presentSettlementPrompt`), `app/MainTabEffects.kt` | Every `AWDemoStore` / `AWSheet` / `AWTab` / `AWOnboardStep` flag builds the same model. |

Tests (JVM, 212 total incl. wave 1): `DemoTrialSimulatorTests`, `LiveActivityTests` (state half + prefs + push payloads),
`SecurityAlignmentTests` (MockEngine stub), `WidgetSnapshotTests`, `WidgetSetupServiceTests`, `CaseFlowTests`, `DeepLinkTests`,
`AppGateTests`/`OnboardingGateTests`/`PartnerCodeGateTests`, `AppModelTests` (paywall, onboarding completion, partner code join,
anonymous auth), the owed `encodesAvatarForProfileUpdate`, `edgeErrorEnvelope`, `draftsGetSequentialLabels`, plus attribution,
permission routing and router routing.

Tests still owed (their subject belongs to another wave): `CaseFileTests` (`CaseFileStatus`/`CaseFileDocket` live in
DesignSystem/CourtFile.swift, wave 2b), `clockCountdownFormat` and the `Countdown` lines of `CourtOutageTests` (2b),
`VerdictCard.isProvisional` / `CaseDetailView.defenceEvidenceSealed` (3d/3e), `SecureAccountFlowTests` (SecureAccountModel, 3b),
`InviteCodeTests` except `joinErrorsReadPlainly` and `WidgetSetupModelTests` (OnboardingModel, 3b).

### Wave 2a decisions (not covered by the brief)

- **Observable state = Compose snapshot state** (`mutableStateOf`), like wave 1's `AppRouter`, not `StateFlow`: it is the direct
  equivalent of `@Observable` (screens recompose on read); non-Compose observers use `snapshotFlow`. `AppModel` extends `ViewModel`
  but is owned by `PleadApplication` for the process (the iOS App's `@State`).
- **`UserDefaults`** (`services/UserDefaults.kt`): synchronous reads from an in-memory mirror of a Preferences DataStore (writes
  persisted in order); `standard` and the App Group suite `group.app.plead.shared`. Same keys everywhere. In-memory in JVM tests.
- **`AppNotifications`** (EdgeFunctions.kt) replaces `NotificationCenter` names `aw.premiumRequired`, `aw.identityRequired`,
  `plead.silentRefresh`.
- **`Analytics` lives in `services/`** (the services call it); wave 3c should import `app.plead.android.services.Analytics`
  rather than create `features/paywall/Analytics.kt`.
- **Exit offer**: `PurchasesService.exitOffer` is `ExitOfferPrices` (localized strings + micros); wave 3c's `ExitOfferState`
  (with `ExitOfferMath`) is built from it, so billing does not depend on the paywall feature.
- **Preview prices only in `src/debug`** (`PreviewPrices.kt`; the release twin has none), so no price literal ships.
- **Seams for later waves** (`app/AppModelHosts.kt`): `OnboardingHost` (3b's `OnboardingModel` implements it; `ShellOnboarding`
  keeps the step machine and the same `onboarding.<scope>.step/.completed` keys until then), `ColdOpenHost` (`NoColdOpen` until
  3b), `CourtSessionPresenter` (3f's court-session notification; `AppModel.courtSession`). Pass them to `AppModel` /
  `DemoHarness.model()` when they land.
- **Kept in sync by hand**: `DemoTrialSimulator.judgementPendingSentence` (= `Verdict.judgementPendingSentence`, 3e),
  `DemoTrialSimulator.restLine` (= `CourtroomLogic.restLine`, 3a), CaseStore's private `CaseStatus.shortTitle` (= Components, 2b),
  `PreviewData.onboardingAvatarPresets` (= `OnboardingAvatars.presets`, 3b).
- **Anonymous flag** comes from the JWT `is_anonymous` claim (supabase-kt's `UserInfo` has no field for it).
- **Push taps** reach MainActivity as intent extras (`link` and/or `case_id`, `screen`): wave 3f's notifications must put the FCM
  data there. Notification channels = `PushCategory` raw values (the backend's FCM `channel_id` should use them).
- **`AWPermissions fresh`** applies to notifications only; tracking is never undetermined on Android.
- **Test dependency** `ktor-client-mock` added to the catalog (iOS `StubURLProtocol`).
## Wave 2b: design system + Court tab host (done; app shell items are wave 2a's)

| iOS | Android | Notes |
|---|---|---|
| `DesignSystem/Components.swift` | `designsystem/Components.kt` | Every view, same names/params/tokens. View modifiers → `Modifier.awBackground/awCard/awCourtFile/awInput/awBottomBar`. `.buttonStyle(.aw(kind, fullWidth:, large:))` → `AWButton(style = AWButtonStyle.aw(...))`. Statics live in an `object` beside the composable of the same name (`Countdown.clock`, `ScalesShape.path`). `ExhibitTile`'s `imageURL` is a `String` (Coil), `imageData` a `ByteArray`. |
| `DesignSystem/PixelAvatar.swift` | `designsystem/PixelAvatar.kt` | Pixel maps and palettes verbatim; `grid(for:)`/`outline(of:)`/`description(of:)` → `grid(a)`/`outline(grid)`/`description(a)`. Verified against a golden generated by running the iOS source itself (`src/test/resources/pixel_avatar_golden.*`, 180 avatars incl. clamped indices). |
| `DesignSystem/AvatarBadge.swift` | `designsystem/AvatarBadge.kt` | `AvatarSize`, `AvatarBadge`, `AvatarPlaceholder`, `AvatarPair`. |
| `DesignSystem/PleadWordmark.swift` | `designsystem/PleadWordmark.kt` | `PleadLogo` (flat + layered `heartScale` rendering from the same @3x asset), `PleadLogoPlacement`, `PleadBrandColor`, `PleadPixelArt`, `PixelGrid`. |
| `DesignSystem/CourtFile.swift` | `designsystem/CourtFile.kt` | `CaseFileStatus` (+ countdown helpers), `CaseFileDocket`, `CourtFilePalette`, `CaseFileCard`, `CaseFilePortrait`, `CourtTabHeader`. Tuples → `CaseFileRow`, `CaseFileAction`, `CaseFileDocket.Counts`. |
| `Features/Court/CourtTabView.swift` | `features/court/CourtTabView.kt` | Host only: case selection, fixture vs live, router actions, fixture late-join/replay clocks. Placeholders `CourtroomScenePlaceholder`, `CourtroomEmptyStatePlaceholder`, `CourtFixturePlaceholder` for wave 3a. Reads the store through `CourtTabStore` (CaseStore implements it after the 2a merge). |
| (SwiftUI primitives) | `designsystem/SwiftUIBridge.kt` | Android-only helpers for every wave: `SFSymbol.icon("name")` (every SF Symbol the app uses → Material icon), `accessibilityReduceMotion()` + `LocalReduceMotion`, `swiftSpring(duration, bounce)`, `Modifier.pleadShadow`, `Modifier.saturation`, `fixedSp`, `NumericText` (`.numericText()`). |
| Previews | `@Preview` in each file + `designsystem/ComponentGallery.kt` | Gallery flag: `DemoHarness.showsComponentGallery` (`--es AWSheet gallery`), an extension property so `DemoHarness.kt` is untouched; the shell must show `ComponentGallery()` when it is true. |
| Tests | `PixelAvatarTests`, `ComponentsTests` (`clockCountdownFormat`, the Countdown line of `overdueDeliberationNeverCountsNegative`), `PleadWordmarkTests` (`welcomeLogoMatchesTheEndCardPlacement`), `CaseFileTests` (countdown, card copy, `closedNewestFirst`), `CourtTabViewTests`, `ComponentGallerySnapshotTests` (renders PNGs to `app/build/outputs/snapshots/designsystem/`); `src/androidTest/.../ComponentsComposeTests` (emulator) | `testImplementation` of `compose-ui-test-junit4` added for the Robolectric snapshots. |

Not 1:1 / owed:
- **`CaseFileStatus.make` and the `CaseStore` case-file extension** (`caseFileStatus`, `caseFileParties`, `caseFileAction`) need `CaseFlow`/`CaseStore` (2a); owed by the integrator or 3d after the merge, with the `CaseFileStatusTests` and `countsFromData` / `demoDocketLeadsWithAnAction` / `actionFirstByNearestDeadlineThenWaiting` / `aWaitingCaseNeverDisplacesAnAction` tests. The status matrix is in the file header.
- **`ExhibitTile(label:draft:ownerRole:)`** needs `DraftExhibit` (2a): a three-line overload onto the primary `ExhibitTile(...)` (see its KDoc).
- `pixelArtRendersAtIntegerScales` (OnboardingIdentityTokens) and `OnboardingKitTests` have onboarding subjects: wave 3b.
- Pixel cells are laid out in dp exactly as iOS lays them out in points, then their edges are snapped to whole device pixels (iOS @3x is always whole pixels) so there are no seams; the difference is under half a pixel.
- Shadows use the framework shadow layer: hardware-drawn from API 28; API 26–27 draw no shadow.
- SF Symbols are Material icons (closest glyph); `applelogo` has none (the Apple sign-in button draws its own).
- Haptics: `sensoryFeedback(.impact(weight: .medium))` on the summons seal → `HapticFeedbackConstants.CONTEXT_CLICK`.
- `PleadLogo(reportsMarkBounds:)` + `PleadLogoMarkAnchorKey` → `onMarkBounds: (Rect) -> Unit` (bounds in root, before the optical offset).
- `isAccessibilitySize` (CaseFileCard layout switch) → `fontScale >= 1.6`.

## Wave 3a: courtroom, deliberation, Court tab body (done)

| iOS | Android | Notes |
|---|---|---|
| `Courtroom/CourtroomState.swift`, `CourtroomLogic.swift`, `CourtroomPreviewData.swift` | `courtroom/` same names | Pure logic ported line for line. Swift enums with payloads are sealed classes keeping the case names (`DockMode.compose(ComposeKind.opening)`); tuples are small data classes (`Stamps`, `PanelVotes`, `DockCopy`, …). `CourtroomState` is an immutable data class (Swift `var s = …; s.x = …` → `copy`). `CourtFixtures` vals are ordered for Kotlin's eager object init. |
| `CourtMotion`, `CourtEntrance`, `CourtEntranceLive`, `CourtCaseCall`, `CourtCaseCallLive`, `CourtHelp`, `CourtMiddleBand` | same names | Directors are snapshot-state classes with one coroutine `Job` on an injected `CoroutineScope` (default `MainScope()`; the scene passes its `rememberCoroutineScope()`) and an injected `sleep: suspend (Double) -> Unit`, `now`, `random`: the same deterministic schedules as iOS. Seen keys keep iOS's upper-case `uuidString`. |
| `CourtStyle`, `CourtArt`, `CourtBubble`, `CourtStage`, `CourtMotionViews`, `CourtEasel`, `CourtJudgement`, `CourtTranscript` | same names | `CourtFont` stays in designsystem/Typography.kt. `CourtStyle.kt` also holds the SwiftUI bridges the courtroom shares: `DynamicTypeSize`/`DynamicTypeCap`/`cappedAt`, `Modifier.position`, `frameIn`, `ViewThatFits`, `ScaledText` (`minimumScaleFactor` via `TextAutoSize`), accessibility sort priority, `CourtHaptics`, `CourtInsets`, `CourtSheet`, `CourtSheetTopBar`. Sprites are Canvas cells; the painting is drawn with `FilterQuality.Medium` like iOS `.interpolation(.medium)`; crowd / gavel crops are cut from the same bitmap. |
| `CourtDock`, `CourtroomScene`, `VerdictMomentView` | same names | Every dock mode, density stepping, pulse, explainer, objection sheet, decline alerts, case-actions and Change menus, countdown; the scene's stage, middle band, case call, delivery moment, settled seal, deliberation overlay, safety overlay, pull-down transcript; the full verdict sequence with confetti (same SplitMix seed) and `AWAutoplay`. |
| `Features/Deliberation/DeliberationPanel.swift` | `features/deliberation/DeliberationPanel.kt` | |
| `Features/Court/CourtTabView.swift` | `features/court/CourtTabView.kt` | Placeholders replaced. `CourtTab(model)` is the tab body; `CourtTabView.state/actions` build the scene input from `CaseStore` + router; `CourtFixtureStage` maps `AWCourtFixture`, `AWCourtReplayFrom/Step`, `AWCourtEntrance replay|late|<s>|caseCall|introduction`; `AWCourtHelp` opens its sheet from the dock / deliberation overlay (debug only). Revealed verdicts shown in court call `WidgetSnapshotStore.markVerdictOpened`. |
| (store seam) | `services/CaseStoreCourt.kt` | `CaseStoreCourt(store) : CourtTabStore` adapter + `CaseStore.court`; CaseStore.kt untouched. |
| Tests | `CourtMotionTests` (30), `CourtCaseCallTests` (19), `CourtEntranceTests` (13), `CourtHelpTests` (12), `ExhibitDockCopyTests` (3) | JVM, kotlinx-coroutines-test fake clocks; Swift parameterised tests are loops in one test of the same name; `courtroomUsesNoTimers` scans the Kotlin sources. Suite total 327, all green. |

**Integrator:** in `app/MainTabScreen.kt` replace the Court placeholder with `AppTab.court -> CourtTab(model = model)`
(`app.plead.android.features.court.CourtTab`; MainTabScreen needs the `AppModel`, it currently only takes the router).
Mount it without `statusBarsPadding`: `CourtTab` draws edge to edge, under the status bar and (by
`CourtTabLayout.tabBarHeight` + the navigation bar) under `PleadTabBar`, which must stay drawn after it. Sheets the court
requests go through `router.sheet` (chooseJudgement, settlementRoom, settlementResponse) as before.

Owed / not in 3a:
- `MockTrialSceneTests` and `AWMockTrialBeat`: their subject (`Features/Onboarding/MockTrial`) is wave 3b's. The shared
  pieces it uses are ready: `CourtEntranceDirector` (injected sleep/scope), `CourtHelpButton`, `CourtHelpSheetHost`,
  `CourtHelpTopic.mockOpeningStatement`, `CourtCaseCall.mock`, `CourtCaseCallCard`.
- Compose UI tests for the scene (iOS covers the court through UI tests): none added; screenshots vs
  `docs/screenshots/v2` are the integrator's.

Not 1:1 (platform gaps, decisions):
- SwiftUI sheets / `NavigationStack` inline bars → Material `ModalBottomSheet` with an in-sheet top bar (Done / Cancel);
  `fullScreenCover` (verdict) → a full-screen `Dialog`; `Menu` → `DropdownMenu`; `.alert` → `AlertDialog`.
- The line-by-line reveal (`TextRenderer`) draws each laid-out line clipped, lifted and faded from the `TextLayoutResult`;
  semantics carry the full text from the first frame, as on iOS.
- `.id(x).transition(.opacity)` on the dock content / header icon → `AnimatedContent` / `Crossfade` that render the
  current state on both sides (lint suppressed there): the outgoing content leaves in 80 ms, as iOS.
- Dynamic Type caps map to font scale (iOS body size / 17); `MiddleBand.Metrics` uses the SF callout line-height factor
  (1.1934) so the band budgets match iOS.
- Haptics: light impacts (ruling stamps, judgement delivery) → `HapticFeedbackConstants.CLOCK_TICK`.
- The keyboard lifts only the dock (edge-to-edge window; the stage stays still, as `.ignoresSafeArea(.keyboard)`).
## Wave 3b: onboarding, cold open, secure account (done; mock-trial stage owed after 3a)

| iOS | Android | Notes |
|---|---|---|
| `Features/Onboarding/OnboardingModel.swift` | `features/onboarding/OnboardingModel.kt` | `OnboardingStep`, `OnboardingFlow`, `OnboardingAvatars`, `OnboardingModel` (implements `app.OnboardingHost`), `WidgetSetupSurface`, `OnboardingEvent`. Same `onboarding.<scope>.<key>` keys; avatar stored as JSON, `togetherSince` as seconds since 1970. Display-order comparisons are `before/after/atOrAfter/atOrBefore` (Kotlin enums compare by declaration). `AppModel.onboardingModel` casts the host. |
| `OnboardingMotion.swift`, `OnboardingKit.swift`, `OnboardingContainer.swift` | same names | `.pleadReveal`/`.courtLayer`/`.pleadPress` → `Modifier` extensions; `pleadRevealID`/`courtLayerIndex`/`courtOnDark` → `LocalPleadRevealID`/`LocalCourtLayerIndex`/`LocalCourtOnDark`; `Group(subviews:)` stacks (`OnboardingShell`, `CourtLayerStack`, `PleadStaggeredStack`) take a `LayerListScope` (`item {}` / `items(list) {}`). `OnboardingPalette` inlines the `PaywallPalette` hexes. `ActivityShareSheet` = `ACTION_SEND` chooser (completion flag always false; every Swift caller ignores it). Rating prompt = Play In-App Review, same `ratingPromptRequested` / `AWNoReviewPrompt` rules. |
| `OnboardingStoryViews`, `CaseDocketScreen`, `CourtPanelScreen`, `SummonsIntroView`, `CourtIdentityView`, `AvatarCreatorView`, `PartnerSetupView`, `LinkCoupleView` (+ `InviteSheet`, `LinkedCelebrationView`), `InviteCode`, `PermissionViews`, `WidgetSetupEducationView`, `WidgetSetupInstructionsSheet`, `OnboardingCompleteView` | same names in `features/onboarding/` | Copy verbatim except the widget screens (below). |
| `MockTrial/MockTrialScript.swift` | `features/onboarding/mocktrial/MockTrialScript.kt` | Full port. |
| `MockTrial/MockTrialDemoView.swift` | `mocktrial/MockTrialDemoView.kt` | Demo view, controls, `MockTrialInvitation`, `AWMockTrialBeat` 1:1. **Interim stage** (see owed). |
| `MockTrial/MockTrialPlayer.swift` | `mocktrial/MockTrialPlayer.kt` | `MockTrialTiming` (part times, dwells) verbatim; **interim** `MockTrialPlayer`: beats, autoplay, tap-to-complete / advance, commit window, skip, CASE CLOSED waits, TalkBack = no autoplay, Reduce Motion = whole beats. |
| `MockTrial/MockTrialScene.swift` | `mocktrial/MockTrialScene.kt` | **Partial**: `MockTrialPersonas`, `MockTrialPodium` and what `SummonsIntroView` needs. |
| `Features/ColdOpen/*` (7 files) | `features/coldopen/*.kt` | Same frame timings, `AWColdOpen full|sting|none`. `ColdOpenCoordinator` implements `app.ColdOpenHost` (injectable scope). Frames decoded as HARDWARE bitmaps off the main thread; the frame clock is a `withFrameNanos` loop. Android has no Increase Contrast / Reduce Transparency: those branches are parameters defaulting to off. |
| `Features/Account/SecureAccountView.swift` | `features/account/SecureAccountView.kt` | `SecureAccountModel` + view (amendment az): **Sign in with Google** first (Credential Manager → `linkGoogle`), **Sign in with Apple** via the Supabase OAuth browser flow (hidden while `isAppleAvailable()` is false; browser conflicts arrive on `auth.linkConflicts`), then the email one-time code. New strings: "Sign in with Google", "Links your Google account to this court record", the Google conflict / failure / retry lines; "Back to Sign in with Apple" → "Back to Sign in with Google"; "on this iPhone" → "on this phone". Google "G" / Apple buttons are drawn (no brand artwork bundled). |
| (borrowed art) | `features/onboarding/OnboardingBorrowedArt.kt` | `internal` interim copies of `JudgeSprite`, `CourtroomBackground`, `CourtroomZones`, `CourtCrowdLayer`, `CourtGavelLayer/Sprite`, `CourtArtCrops`, `CourtMotionDirector` (ambient only), `CourtMotionTiming`, `CourtFigurePose`, `PixelSprite/PixelGlyph/PixelInk/PaywallSprites`, `PaywallPalette`, `PixelJudgeGlyph`, `PleadWidgetPalette` (waves 3a/3c/3f own the originals). **Integrator: delete the file after the 3a/3c/3f merge and import the originals** (same names and signatures). |

Decisions (not 1:1):
- **No Privacy & tracking step on Android** (amendment az, no ATT): `OnboardingStep.skipsTracking`; `activeSteps()` drops `.tracking`, so the flow is 11 screens ("N OF 11", rail of 11 dots). Raw values, screen ids and keys are unchanged; a persisted 9 resumes on Widgets; passing the Privacy position sets `trackingSeen` (the state an iOS user who answered ATT has); `TrackingPermissionService` is always `authorized`. `PrivacyScreenView` is still ported (and tested) but never routed to. *CONTRACTS-v2 should record this in amendment az (integrator/user: docs are outside wave 3b's folders).*
- **Widget screens (Android copy):** eyebrow "Widgets & Live Updates", pills "Lock Screen · Home Screen · Live Updates", widgets are added from the launcher ("touch and hold an empty spot on your Home Screen, then tap Widgets"), Live Activities → "Live updates" (the court-session notification, wave 3f), settings hint "Settings › Apps › Plead › Notifications", sheet title "Add Plead to your phone". Every other string is verbatim. The iOS strings are in KDoc beside each Android constant.
- **Back:** iOS left-edge swipe → the system back gesture (`BackHandler`, only while back is offered).
- **Haptics:** light impact → `CLOCK_TICK`, gavel → `CONTEXT_CLICK`.

Owed (both done in wave 4):
- **Mock trial stage + player** (`MockTrialStage`, the full `MockTrialPlayer` with the shared court entrance, line reveal, talk/gavel/crowd cues and the help-sheet pause) are built on wave 3a's `CourtEntranceDirector`, `CourtRevealPlan`, `CourtHelp`, bubbles and overlays: port after the 3a merge, replacing `mocktrial/MockTrialPlayer.kt` and the interim `MockTrialTranscriptStage` in `MockTrialDemoView.kt`, together with the rest of `MockTrialSceneTests` (PORT.md §7 gives those tests to 3a). Until then the step plays the same script, beats, timings and analytics as cards under the painted room with the judge.
- Integrator wiring (below).

Integrator wiring (`app/` is not wave 3b's):
- `AppModel.kt`: `onboarding ?: ShellOnboarding(defaults)` → `onboarding ?: app.plead.android.features.onboarding.OnboardingModel(defaults)`; `coldOpen ?: NoColdOpen()` → `coldOpen ?: app.plead.android.features.coldopen.ColdOpenCoordinator()`; and in `saveCourtIdentity` use `OnboardingAvatars.presets` instead of `PreviewData.onboardingAvatarPresets` (identical lists, tested).
- `RootScreen.kt`: cold open → `ColdOpenView(model.coldOpen as ColdOpenCoordinator)`; `onboarding` → `OnboardingContainer(model)`; `linkCouple` → `LinkCoupleView(model)`; `secureAccount` → `SecureAccountView(model)`; the link celebration → `LinkedCelebrationView(model.store) { model.store.linkCelebration = false; model.finishLinkStep() }`.
- JVM tests that build an `AppModel` with the real `ColdOpenCoordinator` need `Dispatchers.setMain` (its default scope is `Main.immediate`).
- `DemoHarnessModel.kt` can keep `ShellOnboarding.displayOrder/screenIds` (identical to `OnboardingStep`).

Tests (JVM): `OnboardingFlowTests`, `OnboardingModelTests`, `OnboardingMockTrialTests` (OnboardingTests.kt), `OnboardingMotionTests`, `OnboardingKitTests`, `MockTrialSceneTests` (script half + interim player), `ColdOpenCoordinatorTests`, `SecureAccountTests`, `SecureAccountFlowTests`, `OnboardingCourtScreensTests` (CourtPanelScreenTests, CaseDocketScreenTests), `OnboardingIdentityPartnerTests` (incl. `pixelArtRendersAtIntegerScales`), `InviteCodeTests` (onboarding), `OnboardingPermissionScreensTests`, `WidgetSetupTests` (+ `WidgetSetupStepTests`, `WidgetSetupModelTests`): 150 wave-3b tests, 400 in the module, all passing; lint 0 errors. Not ported (iOS-only subjects): `appTrackingTransparencyIsWiredHonestly`, `privacyManifestsDeclareTrackingOnlyForTheApp`.
## Wave 3c: paywall, exit offer, courtroom hero (done)

| iOS | Android | Notes |
|---|---|---|
| `Features/Paywall/PaywallGateView.swift` | `features/paywall/PaywallGateView.kt` | `PaywallGate(model)` (= `PaywallGateView(model, model.auth.userId)`) is the entry point for `.paywall` / `.partnerPaid`. X → `loadExitOffer()` → exit offer every time while eligible (amendment aa) or `model.closeGate()`. 0.3 s cross-dissolve, hero held still. `AWSheet exitOffer`, `AWAutoCloseAfter`. |
| `PaywallView.swift`, `PaywallComponents.swift`, `PaywallCopy.swift`, `PaywallPalette.swift` | same names | Three plans, skeletons until prices load, CTA/disclosure from `PaywallCTAState`, CTA scrolls with the plans and only the legal footer is pinned (amendment z), partner-paid state + CONTINUE → `model.enterApp()`. `AWPlan`, `AWTrial`, `AWProducts`, `AWPricesLoading` work through `PurchasesService`. |
| `ExitOffer.swift`, `ExitOfferPaywallView.swift` | same names | `ExitOfferState.from(ExitOfferPrices)` (micros → `BigDecimal`, half-up rounding); `ExitOfferState(...)` returns null without a real discount. Stamp lands with the 0.97 → 1.03 → 1 keyframes + one light haptic. |
| `PaywallOpening.swift`, `PaywallOpeningHaptics.swift` | same names | Same timing table, rule (`paywallOpeningSeen.<uid>` in `UserDefaults.standard`, `AWPaywallOpening YES|NO`), director (injectable `sleep`/`now`) and views (flying lockup onto the header logo's measured slot via `PleadLogo(onMarkBounds:)`, blush glow, 12 gold rays). Core Haptics swell → a `Vibrator` amplitude waveform shaped like the intensity curve (peak at 78%); no amplitude control → two light taps, as the iOS fallback. |
| `PaywallEntrance.swift` | `features/paywall/PaywallEntrance.kt` | Same layer table; `Modifier.paywallEntrance(layer, index)` + `LocalPaywallEntrance`. The `OnboardingMotionTokens` values it reads are copied as `OnboardingMotionTokensForPaywall`, and `PleadRevealParameters` is declared here (paywall package) because wave 3b owns OnboardingMotion: the integrator can point both at one definition after the merge. |
| `PaywallCourtroomAnimator.swift`, `PaywallCourtroomHero.swift`, `PaywallCourtroomSprites.swift` | same names | One coroutine `Job` (the Swift single `Task`), same loop, frame timings, gavel spacing, 1.5 s tap debounce, first-strike haptic (`CLOCK_TICK`), Reduce Motion → static (`accessibilityReduceMotion()` incl. `AWDemoReduceMotion`). scenePhase → `LifecycleResumeEffect`. Sprites drawn in the art's own pixel space under one scale transform with `FilterQuality.None`. |
| `PixelGlyphs.swift` | `features/paywall/PixelGlyphs.kt` | Same sprites/palette; `PerkIconView` uses a `pixel_*` drawable when one exists (none do, same as iOS). |
| `Analytics.swift` | `services/Analytics.kt` (wave 2a) | Not duplicated: the paywall imports the services object. Same event names and props. |
| Tests | `ExitOfferTests`, `PaywallCourtroomHeroTests`, `PaywallGateTests` (`PaywallCopyTests`), `PaywallOpeningTests`, `PaywallTests` (`PaywallThreePlanTests`, `PaywallEntranceTests`, `PaywallWebsiteDomainTests` = the owed PaywallCopy half of `websiteDomain`, `PaywallSourceGuardTests`), `AttributionTests` (source half), `PaywallSnapshotTests` (Robolectric renders to `app/build/outputs/snapshots/paywall/`) | 89 JVM tests. Source guards: no string literal in `features/paywall/` contains "premium"; no price literal (`£1`, `$1`, `1.99€`) in any `src/main` or `src/release` Kotlin string. `AppGateTests`/`PaywallModelTests` and the first half of `AttributionTests` were already ported in 2a. |

**Integrator wiring** (`app/RootScreen.kt`, not edited by 3c): in `Gate`, replace the `paywall, partnerPaid` placeholder with
`AppGate.Destination.paywall, AppGate.Destination.partnerPaid -> PaywallGate(model)` (import `app.plead.android.features.paywall.PaywallGate`).
`MainActivity` already sets `purchases.activity` (Google Play's sheet needs it).

Not 1:1 / decisions:
- **Store names in error copy**: "Couldn't reach the App Store. Try again" → "Couldn't reach Google Play. Try again"; "No active subscription found for this Apple ID." → "No active subscription found for this Google account." Everything else is verbatim.
- **Manage subscription** is not on the paywall on iOS (it is Settings, wave 3e: `AppConfig.manageSubscriptionsURL`).
- **Fonts**: `.rounded` system text → default sans at the same text-style sizes; `minimumScaleFactor` → `BasicText(autoSize = StepBased)`. Dynamic Type thresholds as font scales: xLarge 1.1, xxLarge 1.3, accessibility 1.6 (`DynamicTypeScale`).
- **Haptics**: `.sensoryFeedback(.success)` → `HapticFeedbackType.Confirm`, `.selection` → `SegmentTick`, light impacts → `CLOCK_TICK`.
- **Safe areas**: the window is edge to edge; the hero runs under the status bar, the close button and footer pad by the status / navigation bar insets.
- **Blur** on the rays needs API 31+ (older devices draw them unblurred).
- Needs the user: Google Play products `plead.weekly`, `plead.monthly`, `plead.yearly` (with a free-trial offer on the default base plan), `plead.discount`, RevenueCat offerings `default` + `exit_offer` on the Play app, and the `goog_` public key. Until then a release build shows the neutral loading / "Couldn't reach Google Play" state (no prices in code).
## Wave 3d: Home, Cases, Case detail, File a case, Defence, Summons, Scheduling (done)

| iOS | Android | Notes |
|---|---|---|
| `Features/Home/HomePlan.swift` + HomeView's "Store → plan" / `ActiveCaseCard` | `features/home/HomePlan.kt` | `HomeItem`, `HomePlan` (`Lead` sealed class, `ranked`/`lead`/`preview`/`key`, copy, greeting with a `ZoneId`), `CardAction`, `cardAction`/`courtAction`/`items`, `ActiveCaseCard`. |
| `Features/Home/HomeView.swift` | `features/home/HomeView.kt` | `HomeTab(model)` (the Home stack: `TabNavHost` + records), `HomeView`, `OutstandingAgreementCard`, greeting, new-case / solo panels, invite code, offline notice, `AWScroll outstanding|agreement`. |
| `Features/Cases/CasesView.swift`, `SettlementDocket.swift` | `features/cases/CasesView.kt`, `SettlementDocket.kt` | `CasesTab(model)`, `CasesView` (+ `CasesView.DocketSection`), `courtFileButtonStyle` (Swift `CourtFileButtonStyle`), `ClosedCourtFileEntry`, `SettlementDocketSlip`; `SettlementDocket`, `SettlementPendingChip`, `AppRouter.openSettlement`, `AWSettlementPrompt off`, `AWOpenCase N`, `AWDocketFilter`. |
| `Features/CaseDetail/CaseDetailView.swift`, `SettlementRecordSection.swift` | `features/casedetail/CaseDetailView.kt`, `SettlementRecordSection.kt` | Record with nav bar (back + docket number), `TranscriptRow`, `VerdictCard` (+ statics), `PanelSection`, exhibit detail sheet, `RecordLabel`, `CaseType`; `SettlementRecordSection`, `SettlementOfferHistoryRow`, `SettlementPendingCard`. `AWScroll panel|judgement|settlement`. |
| `Features/FileCase/ExhibitEditor.swift`, `FileCaseView.swift` | `features/filecase/ExhibitEditor.kt`, `FileCaseView.kt` | Photo Picker (`PickVisualMedia`, images only), `ImageCompressor` (≤ 1600 px, JPEG 0.8, orientation via ImageDecoder on API 28+), upload through `CaseStore.fileCase` → `StorageService`. `FileCaseSheet(model, onDismiss)`, `StepHeader`, `AWFileStep evidence`. |
| `Features/Defence/DefenceView.swift` | `features/defence/DefenceView.kt` | `DefenceSheet(model, caseId, onDismiss)`. |
| `Features/Summons/SummonsView.swift` | `features/summons/SummonsView.kt` | `SummonsCover(model, caseId, onDismiss)`; system back = "Decide later". |
| `Features/Scheduling/SchedulingView.swift` | `features/scheduling/SchedulingView.kt` | `SchedulingSheet(model, caseId, onDismiss)` (half sheet, expands while countering). |
| Owed by 2b: `CaseFileStatus.make`, `CaseStore.caseFileStatus/caseFileParties/caseFileAction` | `services/CaseStoreCaseFile.kt` | `fun CaseFileStatus.Companion.make(...)` (both overloads) + CaseStore extensions. |
| Owed by 2b: `ExhibitTile(label:draft:ownerRole:)` | `designsystem/ExhibitTileDraft.kt` | |
| SwiftUI system pieces | `features/casedetail/CaseScreenKit.kt` | Android-only: nav bar, `CaseSheetHost` (+ `InteractiveDismissDisabled`), segmented picker, dialogs, text field, trial-time / compact date pickers, `ContentUnavailable`, share, scroll anchors, date formats, `FormPrimaryButton` (PrimaryButton with `.disabled`). |
| Views from waves 3a / 3e | `features/casedetail/CrossFeatureSlots.kt` | Labelled placeholders with the Swift parameters; the integrator swaps each body for the one-line call in its KDoc (DeliberationPanel, JudgementStatusCard, VerdictJudgementLine, JudgementFulfilmentSlip, OutstandingJudgementCard, SettlementFulfilmentCard, SettlementStatusRow, SettlementEntryButton, SettlementRoomView sheet, `CourtroomLogic.settledJudgeLine`). |

Tests (JVM, 73 new; suite 323/323): `HomeTests` (primary selection, greeting, urgent label), `TieAndDocketTests`
(`TieCaseFlowTests`, `OpenMeansCourtOngoingTests`, `HomeCardSelectionTests`), `SettlementDocketTests` (rows, sections, Home,
record), `designsystem/CaseFileStatusTests.kt` (`CaseFileStatusTests` + `CaseFileDocketStatusTests`: `actionFirstByNearestDeadlineThenWaiting`,
`aWaitingCaseNeverDisplacesAnAction`, `countsFromData`, `demoDocketLeadsWithAnAction`), `CaseDetailViewTests`
(`provisionalRulingFlag`, `defenceEvidenceIsSealedForThePlaintiffUntilFiled`, headlines, panel preferences).

Integrator wiring (`app/MainTabScreen.kt`, `app/MainTabEffects.kt`, not edited by 3d):
- Home tab → `HomeTab(model)`; Cases tab → `CasesTab(model)` (each replaces the `TabNavHost` + placeholders).
- Sheets: `AppSheet.fileCase` → `FileCaseSheet(model) { router.sheet = null }`; `defence(id)` → `DefenceSheet(model, id) {…}`;
  `scheduling(id)` → `SchedulingSheet(model, id) {…}`. Each brings its own `ModalBottomSheet` (don't wrap it again).
- Summons: replace `WavePlaceholder("Summons", …)` in MainTabEffects with `SummonsCover(model, summonsId) { router.summonsCaseId = null }`.
- After merging 3a/3e: fill the bodies in `CrossFeatureSlots.kt`.

Not 1:1:
- **Cross-feature views** are slots until the merge (above).
- **`SettlementDocket.homeLabel`** takes `SettlementDocket.HomeFulfilment` (same five cases and rules as 3e's `SettlementFulfilment.of`)
  so Home does not depend on 3e; after the merge it can be re-expressed over `SettlementFulfilment`.
- **Not ported here:** `SummonsIntroTests` (subject `SummonsIntroView` / `OnboardingStep`: wave 3b), `tieCardActionsServeOrDeclineForEither`
  and `JudgementFulfilmentTests` (3e), `CourtroomTieTests` / `SettlementCourtroomLogicTests` (3a).
- Photo Picker has no screenshots-only filter; the screenshot type picks from all images.
- Date pickers: Material calendar + a clock dialog for the graphical picker; date / time chips for the compact one.
- `confirmationDialog` → an `AlertDialog` with the same title, message and buttons.
- 3e's Judgement views use Swift `CourtFileButtonStyle`: on Android it is `features.cases.courtFileButtonStyle(onClick)`.
## Wave 3e: Settlement, Judgement, Settings, Us (done)

| iOS | Android | Notes |
|---|---|---|
| `Features/Settlement/SettlementComponents.swift` | `features/settlement/SettlementComponents.kt` | `SettlementSeal`, `SettlementSuggestionCard`, `SettlementRadio`, `SettlementOfferCard` (live "Answer within …" = `SettlementCopy.relative`, SwiftUI `.relative` style), `SettlementEntryButton`, `SettlementCopy`, `String.capitalizedFirst`, `SettlementType`. `SettlementPressStyle` → `Modifier.pressScaleClickable`. |
| `SettlementFulfilment.swift` | `SettlementFulfilment.kt` | Sealed `SettlementFulfilment` (`of(s, now, zone)`), `SettlementStatusRow`, `SettlementFulfilmentCard(settlement, offer, store)`. |
| `SettlementRoomModel.swift`, `SettlementRoomView.swift` | same names | `SettlementRoomView(caseId, model, onDismiss, onSent?)`, `SettlementOfferComposer` (Stepper → − / + pill). Skeleton cards drawn as bars (`.redacted`). |
| `SettlementResponseSheet.swift` | same name | `SettlementResponseModel` + `SettlementResponseSheet(caseId, model, onDismiss)`. |
| `SettlementAcceptedView.swift` | same name | Marks the settlement celebrated on appear. |
| `Features/Judgement/*` (5 files) | `features/judgement/*.kt` | `Verdict.judgementPendingSentence` (companion extension; equals `DemoTrialSimulator.judgementPendingSentence`, asserted in a test), `JudgementCardAction`, `JudgementFulfilment`, `JudgementFulfilmentBlock` (object for `heading` + composable), `JudgementFulfilmentSlip`, `JudgementActionButtons`, `OutstandingJudgementCard`, `JudgementType`, `JudgementSelectionModel`/`View`, `JudgementOptionCard`, `JudgementRerollButton`, `JudgementStatusCard`, `VerdictJudgementLine`. Composables take `store` / `router` explicitly (no environment). |
| `Features/Settings/NotificationSettingsSections.swift` | same name | "Open Settings" → `Settings.ACTION_APP_NOTIFICATION_SETTINGS` for this package; status re-read on resume. Also the inset-grouped list primitives `SettingsSection`/`SettingsRowContainer`/`SettingsDivider`. |
| `Features/Settings/SettingsView.swift` | same name | `SettingsView(model, onDismiss, editAvatar?, onboardingPreview?)`, `AccountDeletion`, `DeleteAccountSheet` (M3 bottom sheet, not dismissable while deleting), `HoldToConfirmButton` (1.2 s ring; TalkBack double-tap confirms), `OnboardingPreviewCover` (full-screen dialog around a slot). `AWSettings delete|preview|bottom` honoured in debug. Version = `AppConfig.appVersion` (BuildConfig). Legal URLs = `SettingsLinks` (same as `PaywallCopy.termsURL/privacyURL`). |
| `Features/Us/UsSummary.swift`, `UsView.swift` | same names | `UsTab(model, modifier, editAvatar?, judgeSprite?)` entry point + `UsView(store, router, …)`. `ViewThatFits` → a SubcomposeLayout helper; `isAccessibilitySize` → `fontScale >= 1.6`. |
| (SwiftUI primitives) | `features/settlement/SheetChrome.kt` | Android-only helpers used by all four folders: `SheetScaffold` (NavigationStack + Cancel / Done, back swallowed while working), `ConfirmationDialog`, `MessageAlert`, `ActionButton` (PrimaryButton with a disabled state and any icon), `QuietTextButton`, `pressScaleClickable`, `Haptics` + `SuccessFeedback`/`SelectionFeedback`, `IconLabel`. |

Tests (JVM, 89 new; 339 total, 0 failures): `JudgementTests` (next action, card actions, selection model, demo simulator, edge-function
bodies/decoding via `StubSupabase`, deep links), `TieAndDocketTests` (tie flow, openness, `JudgementFulfilmentTests`, Home card
selection), `SettlementTests` (case flow, can-propose matrix, response/room models, tally, demo settlement, edge functions, plus
Android-only `SettlementCopyTests`), `UsTests`, `AccountDeletionTests` (the owed three; onboarding reset runs against `ShellOnboarding`).
Lint: 0 errors (no warnings in these folders).

### Integrator wiring (MainTabScreen, not edited here)

- `AppTab.us -> UsTab(model, editAvatar = { done -> EditAvatarView(model.store, onDone = done) }, judgeSprite = { p, cell -> JudgeSprite(p, cell) })`
  (slots: `EditAvatarView` is wave 3b's port of AvatarCreatorView.swift, `JudgeSprite` wave 3a's CourtArt).
- Sheet slot (`model.router.sheet`), each with `onDismiss = { router.sheet = null }`:
  `settings -> SettingsView(model, onDismiss, editAvatar = …same…, onboardingPreview = { close -> <3b preview screens> })`,
  `chooseJudgement(id) -> JudgementSelectionView(id, model, onDismiss)`, `settlementRoom(id) -> SettlementRoomView(id, model, onDismiss)`,
  `settlementResponse(id) -> SettlementResponseSheet(id, model, onDismiss)`, `settlementAccepted(id) -> SettlementAcceptedView(id, model, onDismiss)`.
  Present them full height (`skipPartiallyExpanded = true`) and key the content on `sheet.id` so a swap (room → response) rebuilds it.
  Swipe-to-dismiss should be blocked while a send is in flight (the composables already swallow Back).
- Wave 3d mounts `JudgementStatusCard`, `VerdictJudgementLine`, `JudgementFulfilmentSlip`, `OutstandingJudgementCard`,
  `SettlementStatusRow`, `SettlementFulfilmentCard` and `SettlementEntryButton` (the Summons' third path) from these folders.
- Wave 3f: the notification builder should read `NotificationPrefs.cached().lockscreenDetails` and set
  `VISIBILITY_PRIVATE` (off, default) / `VISIBILITY_PUBLIC` (on) — the Android mapping of "Show case details on Lock Screen".

### Not 1:1 (and why)

- **Store names in copy**: "No active subscription found for this Google account." (iOS: Apple ID); the delete sheet says "manage the
  subscription in Google Play" (iOS: App Store); "Off in Android Settings" / "Opens Plead's notification settings in Android Settings"
  (iOS: iOS Settings). Everything else is verbatim.
- **Manage subscription** opens Google Play's subscriptions page (`AppConfig.manageSubscriptionsURL`), not an in-app sheet.
- **Slots until the merge**: Edit avatar rows/links and "Preview onboarding" are hidden while their slot is null; judge tiles are empty
  without `judgeSprite`. `CourtroomLogic.sentenceCase` is copied privately in JudgementSelectionView.kt (wave 3a owns the original).
- **`VerdictCard.isProvisional` / `CaseDetailView.defenceEvidenceSealed`** are CaseDetailView.swift subjects (wave 3d), so those checks stay
  with 3d. `CourtroomTieTests` (CourtroomLogic, 3a) and `activeCardLeavesJudgementStepsToTheOutstandingCard` (Home, 3d) likewise.
- Confirmation dialogs are Material alert dialogs (iOS action sheets); haptics use the closest `HapticFeedbackConstants`.

## Wave 4: integration (done, except what waits for 3f)

Wired (subjects of each wave's "integrator" list):

| Where | What |
|---|---|
| `app/MainTabScreen.kt` | Takes `AppModel`; Home → `HomeTab`, Cases → `CasesTab`, Court → `CourtTab` (in the tab column; it measures itself `tabBarHeight` + nav bar taller and runs under `PleadTabBar`, drawn after it), Us → `UsTab(editAvatar = EditAvatarView, judgeSprite = JudgeSprite)`. Sheet slot keyed on `sheet.id`: `fileCase`/`defence`/`scheduling` bring their own sheet; `settings` (with `OnboardingPreviewScreens`), `invite` (`InviteSheet`), `chooseJudgement`, `settlementRoom`/`Response`/`Accepted` in a full-height `CaseSheetHost`. `SheetScaffold(dismissDisabled)` now also blocks swipe-down (`InteractiveDismissDisabled`). |
| `app/MainTabEffects.kt` | `SummonsCover(model, id) { router.summonsCaseId = null }` (keyed on the case). |
| `app/RootScreen.kt` | Gallery (`AWSheet gallery`) → `ComponentGallery`; cold open → `ColdOpenView` fading out over the gate (0.5 s, status bar hidden while it plays); onboarding / link / paywall (`PaywallGate`) / secure account; the link celebration as a full-screen dialog. `WavePlaceholder` removed. |
| `app/AppModel.kt` | Defaults `OnboardingModel(defaults)` + `ColdOpenCoordinator()`; `OnboardingAvatars.presets`. Light status-bar icons also over the paywall hero and the summons cover. (`ShellOnboarding`/`NoColdOpen` stay: DemoHarnessModel reads `ShellOnboarding`'s tables and the onboarding preview uses `NoColdOpen`.) |
| `features/onboarding/OnboardingPreview.kt` (new) | Body of Swift `OnboardingPreviewCover` (throwaway `OnboardingModel` on in-memory defaults; Welcome → mock trial → summons explainer → How it works → AI court → docket; closes after the docket). |
| `features/casedetail/CrossFeatureSlots.kt` | Every slot calls the real 3a/3e view; `SettlementRoomView` gained a `(caseId, store, router, …)` overload for the summons' own sheet. |
| Dedupes | `OnboardingBorrowedArt.kt` deleted (imports `courtroom/` + `features/paywall/` originals); the Shared widget art it also held moved to `OnboardingWidgetArt.kt` until 3f. Paywall entrance uses `OnboardingMotionTokens`/`PleadRevealParameters` from onboarding (`OnboardingMotionTokensForPaywall` removed). `SettlementDocket.homeLabel(SettlementFulfilment)` (the `HomeFulfilment` copy removed). One definition each for `CourtroomLogic.sentenceCase`, `Verdict.judgementPendingSentence` (`DemoTrialSimulator.judgementPendingSentence` reads it), `CourtroomLogic.restLine`, `CaseStatus.shortTitle`, `SettingsLinks` → `PaywallCopy` URLs, `PreviewData.onboardingAvatarPresets` → `OnboardingAvatars.presets`. |
| Mock trial | Real stage and player (`MockTrialPlayer`, `MockTrialScene`, `MockTrialDemoView`) on `CourtEntranceDirector`, `CourtRevealPlan`, `CourtHelp`, bubbles/overlays; `AWMockTrialBeat` works; all 29 `MockTrialSceneTests`. `courtroom/CourtroomScene.kt` `PlaqueSeal` is `internal` (shared with the mock trial). |
| Tests added | `CourtroomTieTests`, `SettlementCourtroomLogicTests` (`courtroom/CourtroomTieAndSettlementTests.kt`), `SummonsIntroTests` (copy/step/flow/layout; layout also on a Pixel 7), summons/paywall status-bar checks in `AppModelTests`; Compose `AppShellComposeTests` (tabs, sheet slot, summons cover, gallery). `tieCardActionsServeOrDeclineForEither`, `JudgementFulfilmentTests`, `activeCardLeavesJudgementStepsToTheOutstandingCard` were already ported by 3d/3e. |
| Docs | Amendment az: bullet for the missing Privacy & tracking step. |

Fixed on the emulator run:
- Court dock hidden under the tab bar (the court was mounted full screen instead of in the tab column).
- Summons explainer and Court Is Ready: the painting sat half its overflow too high (`requiredSize` centres an
  oversized child) — the judge was under the headline / off the scene. Now laid out top-leading as Swift's `.frame(alignment: .topLeading)`.
- `CourtRoleChip` clipped to "DEFENDAN" on narrow name tags: now fixed-size like Swift's `.fixedSize()`.
- Component gallery crashed (nested vertical scrolls in a `LazyColumn`).
- `CaseFileCard`'s test tag was cleared by `clearAndSetSemantics` (Compose test failed); `ComponentsComposeTests` stacked two buttons.
- Dark status-bar icons over the paywall hero and the summons cover.

Screenshots ([screenshots/](screenshots/), 720 px wide, 256 colours, Pixel 7 API 35 emulator, demo clock 9:41) are named
after their iOS counterpart in `docs/screenshots/v2/` where one exists. Many iOS captures predate later redesigns
(ArgueWin-era Home, old paywall/welcome copy, iOS 26 floating tab bar); the Android screens were checked against the
current Swift source in those cases.

Remaining gaps (Android vs iOS, all small; the polish branches fixed most of them, marked "Fixed"):
- Fixed (polish/shell): tab bar over the Court. `app/TabBarStyle.kt`: the paper-white bar on Home/Cases/Us, and over the Court the
  dark bar iOS 26 renders there (background #401B17, items #FFECE8, selected #BC4A46, no hairline; sampled from
  `court-clean-1-17pro.png`), cross-faded with `PleadMotion.fade()` (snaps with Reduce Motion). `docs/screenshots/tabbar-court.png`, `tabbar-home.png`.
- Fixed (polish2/tabpill): the iOS 26 selected-tab pill. `app/TabBarPill.kt` draws a capsule behind the selected item,
  its tab's slot inset 4 dp (iOS 4 pt from the bar edge; ~95 x 45 dp on a Pixel 7, iOS 98 x 52 pt) and 2 dp inside the
  49 dp row; icon and label are centred on it. Tokens in `TabBarStyle.kt` (`pillFill`, `pillHighlight`,
  `TabBarPillMetrics`): light cocoa 8 % (#EDE9DF over the iOS bar, flat), Court #F4BBB7 at 20 % (= #643B37, sampled)
  with a faint blush rim on the trailing edge. It slides on `swiftSpring(0.4, bounce 0.15)`, clamped to the end tabs,
  colours cross-fading with the bar; jumps with Reduce Motion. `TabBarStyleTests`, `features/shell/TabBarPillComposeTests`,
  `docs/screenshots/tabbar-pill-slide.png`. Still differs from iOS: no live glass refraction/blur of the content behind,
  and the bar stays docked full width (the pill is shorter than iOS's, as the 49 dp row is fixed).
- Fixed (polish/shell): tab state retention. `MainTabScreen` keeps each tab's saveable state in a `SaveableStateHolder` (scroll
  positions, the tab's NavController and its records' state, Us's avatar editor); `TabNavHost` adopts a restored stack
  instead of rebuilding it. Only the selected tab stays composed, so the Court's motion stops when it is left (iOS
  `CourtroomScene.onDisappear`). `features/shell/TabStateComposeTests`, `docs/screenshots/tabs-home-scroll-kept.png`.
- Fixed (polish/misc): `AWScroll judgement|panel` landed the anchor 16 dp low (the record column's padding sits
  inside its scroll, the anchor frames inside that padding); `ScrollAnchors.scrollTo(contentPaddingTop:)` now puts the
  card right under the nav bar as `scrollTo(anchor: .top)` does.
- Fixed (polish/misc): scheduling half sheet. A Material sheet slides one full-size sheet half off screen, so the end
  of the content (its last line, the xl padding and the navigation-bar inset) sat below the screen edge. Partial
  `CaseSheetHost` sheets are now full height in the expanded state (iOS `.large`) and lay the content out in the part
  that is on screen (`SheetDetentLayout.visibleHeight`), as iOS lays a detent sheet out at the detent height: the
  scroll ends with xl padding + the navigation-bar inset in both states. Roboto's taller lines make the content
  taller than the half detent, so there the last line scrolls (drag up expands first, as iOS) rather than sitting at
  the edge. Same for the exhibit detail sheet (also `[.medium, .large]`).
- Fixed (polish/shell): cold open status bar. `features/coldopen/ColdOpenSystemBars.kt` (from `MainActivity.onCreate`) requests the
  hide before the window is added and, while the cold open plays, finishes the system's status-bar hide animation at
  once (the splash window controls the bar until it exits; the hide used to fade over the first cold-open frames).
  The bar now leaves while the system splash is still up; RootScreen shows it again when the cold open ends, as before.
  `docs/screenshots/coldopen-first-frames.png` (splash, splash without bar, first app frame, first scene).
- Judge nameplate truncation, the settlement seal's Material glyph and the mock trial's vanishing bubbles: fixed on
  `polish/court`, see "Fixed since (branch `polish/court`)" below.
- Cold launches on the emulator are slow (debug build, unoptimised dex): run `adb shell cmd package compile -m speed -f app.plead.android` after installing for captures.

Fixed since (branch `polish/court`):
- Judge bubble header truncated the name ("Judge Wigswo…") beside CROSS-EXAMINATION. Cause: the name and a weighted
  Spacer split the room the chip left, so the name got half of it. `CourtJudgeHeader` (courtroom/CourtBubble.kt) lays it
  out as Swift's `Text.lineLimit(1) · Spacer(minLength: 4) · chip.fixedSize()`: the name alone fills, the chip keeps its
  natural width. `CourtJudgeHeaderTests` (Pixel 7 at ×1, ×1.3 up to the bubble's xxLarge cap; 360 dp at ×1; ruling box).
  On a 360 dp phone at large text the name still truncates, as `lineLimit(1)` does on iOS when the row is too narrow.
- Mock trial: a line bubble that leaves its slot fades out where it stood (Swift
  `.transition(.asymmetric(insertion: .identity, removal: .opacity))` under the stage's 0.22 s ease-out, 0.15 s under
  Reduce Motion) instead of vanishing: `mocktrial/MockTrialBubbleExit.kt` keeps a copy of each departed bubble at its
  last bounds over the band and fades it (`MockTrialBubbleExitTests`; frames in `screenshots/mocktrial2-cross-bubble-fade.png`).
  The CLAIM and verdict cards still leave with their beat at once (not bubbles).
- Settlement seal and "Settle Outside Court" draw Plead's own signature glyph (`features/settlement/SignatureGlyph.kt`,
  an original vector: x mark, looped initial over the signing line) at the SF Symbol's size and weight, in place of the
  Material "draw" icon (`SignatureGlyphTests`). `SFSymbol.map["signature"]` (designsystem) names the same
  vector since the integration (`polish/all`).

Resolved in the final integration (see below): 3f merged, `AppModel.courtSession`, lock-screen visibility,
`OnboardingWidgetArt.kt` removed, `AWWidgetPreview` / `AWLiveActivity` screens, widget screenshots.

Still owed:
- Compose UI tests for the rest of ArgueWinUITests: ported since (see "Compose UI tests"); a device run is still owed.

## Wave 3f: widgets, FCM push, court-session notification, review prompt, backend (done)

| iOS | Android | Notes |
|---|---|---|
| `PleadWidgets/PleadStatusWidget.swift` (+ `PleadWidgetBundle.swift`) | `widgets/PleadStatusWidget.kt`, `widgets/PleadWidgetBundle.kt` | One Glance widget ("Case status") with `SizeMode.Responsive`: 1×1 → circular accessory, one-cell strip → rectangular, 2×2 small, 4×2 medium. Three receivers (`PleadWidgetReceiver` small, `PleadMediumWidgetReceiver`, `PleadGlanceWidgetReceiver`) only differ in the picker's default size (`res/xml/plead_widget_info{,_medium,_glance}.xml`). `PleadStatusProvider.timeline` ported; the deadline entry is an inexact alarm (`ACTION_REFRESH`), the hourly re-ask is `updatePeriodMillis`. Tap = `ACTION_VIEW` on the snapshot link into MainActivity. |
| `Shared/WidgetViews/*`, `PleadWidgetPalette.swift`, `PixelJudgeGlyph.swift` | `widgets/SmallWidgetView.kt`, `MediumWidgetView.kt`, `AccessoryRectangularView.kt`, `AccessoryCircularView.kt`, `PleadWidgetContent.kt`, `PleadWidgetComponents.kt`, `PleadWidgetPalette.kt`, `PixelJudgeGlyph.kt` | Same copy, colours, sizes and sprites. Sprites render to bitmaps at whole device pixels per cell (no resampling). Countdown = RemoteViews `Chronometer` ("Plea due in 5:12:03"). Capsules/cards are shape drawables (rounded on API 26+). |
| DEBUG `WidgetPreviewHarness` (`AWWidgetPreview`, `AWWidgetPreviewType`) | `widgets/WidgetPreviewHarness.kt` (`WidgetPreviewOverlay()`) | Renders the real widget: `GlanceAppWidget.compose` → RemoteViews → inflated. All pages; `tinted` says it has no Android equivalent; `activity` renders the court-session notification view. Glance `@Preview`s in `widgets/PleadWidgetPreviews.kt`. |
| `AppDelegate` APNs callbacks | `push/PleadMessagingService.kt` (`PleadMessaging`), `push/PushNotifications.kt` | `onNewToken` → `PushService.shared.didReceive`. Data messages: silent → `handleSilentPush`; alert → notification on channel = `category`, tag = `collapse_id` (newer replaces older), `VISIBILITY_PRIVATE` + public "Plead / Court notice" unless "Show case details on Lock Screen", emoji stripped, tap → MainActivity with every field as an extra. Inert without `google-services.json`. |
| `LiveActivityService` + `PleadCaseLiveActivity` | `push/CourtSessionNotification.kt` (implements `CourtSessionPresenter`), `res/layout/court_session_notification.xml` | Ongoing notification (channel `court_session_v2`, default importance but silent, public; was `court_session`, low) with the banner layout (cream card, burgundy bar, judge, headline, "Case #021", detail, chronometer or gavel) + system chronometer. Planner actions → post / re-post / cancel; plea entered lingers 15 min; stale → `setTimeoutAfter`; user dismissal = finished. Same `liveActivity.startedAt` / `liveActivity.finished` keys; same `live_activity_*` analytics. Without a running app model, `summons` / `verdict_soon` / `verdict_ready` pushes drive it directly (no case number in a push: the "Case #" line is hidden). |
| `requestReview()` after the mock trial (OnboardingContainer) | `push/ReviewPrompt.kt` (`rememberRequestReview()`, `ReviewPrompt.askForRatingOnce`) | Play In-App Review; same `ratingPromptRequested` key, `rating_prompt_requested {after: mock_trial}`, 0.8 s settle, `AWNoReviewPrompt YES` respected. Demo runs use `FakeReviewManager`. |
| Backend (amendment az) | `supabase/migrations/20260930000100_push_tokens_platform.sql`, `register_push`, `_shared/fcm.ts`, `_shared/push.ts`, `_shared/notify.ts` | `push_tokens.platform` (`apns` default / `fcm`, platform-aware token check); `register_push {platform?}` (FCM tokens kept case-sensitive); `deliver` routes `fcm` rows to FCM HTTP v1 (service-account RS256 JWT → OAuth token, cached), data-only message with the APNs fields, `android.collapse_key`, high priority for alerts; `fcm_unconfigured` without `FCM_SERVICE_ACCOUNT`; dead tokens cleared. Queue flush + silent refresh platform-aware. APNs unchanged. Tests: `_shared/tests/fcm_test.ts` + grep/anonymous additions. |

Tests (JVM): `widgets/PleadWidgetTimelineTests` (timeline, families, sprites incl. `pixelArtRendersAtIntegerScales`, harness flags), `widgets/WidgetRenderingTests` (every family composed by Glance and inflated with Robolectric, copy + privacy asserted, PNGs in `app/build/outputs/snapshots/widgets/`), `push/PushNotificationsTests`, `push/CourtSessionNotificationTests`, `push/ReviewPromptTests`.

**Integrator wiring (app/ is not wave 3f's; all done, see "Final integration"):**
1. `PleadApplication.onCreate()`: `PleadWidgets.install()` (adds the medium and glance receivers to `WidgetCenter.receivers`).
2. `PleadApplication.appModel()`, before `model = made`: `made.courtSession = CourtSessionNotification.shared`.
3. Above `RootScreen(model)` in `MainActivity.setContent`: `Box { RootScreen(model); WidgetPreviewOverlay() }`.
4. Wave 3b's `OnboardingContainer`: `val requestReview = rememberRequestReview()`; call it where iOS calls `askForRatingOnce()`.
5. `PleadApplication.registerNotificationChannels`: drop `lockscreenVisibility = VISIBILITY_PRIVATE` (a channel override forces redaction, so the per-notification visibility from "Show case details on Lock Screen" could never show details; the notifications default to PRIVATE with a generic public version anyway).
6. Optional: manifest label of `PleadWidgetReceiver` `@string/app_name` → `@string/plead_widget_name` (manifest is add-only for 3f).

Not 1:1:
- No tinted / vibrant rendering modes on Android (full colour always); `minimumScaleFactor` has no RemoteViews equivalent (text truncates); privacy redaction (`.privacySensitive`) has no widget equivalent (the Home Screen never shows evidence; the glance cell never shows the title).
- Widget gallery previews: Glance 1.1 has no generated previews (`providePreview` needs Glance 1.2 / Android 15); the picker shows the loading layout until placed.
- Dynamic Island has no equivalent; the notification's collapsed / expanded views are the Lock Screen banner.
- A process started only for a push has no app model, so silent pushes then only refresh what the push itself says (the widget snapshot is rewritten on the next launch).

## Final integration (wave 3f wiring, verification)

| Where | What |
|---|---|
| `app/PleadApplication.kt` | `PleadWidgets.install()` in `onCreate`; `made.courtSession = CourtSessionNotification.shared` before the model is kept, then `DemoHarness.applyCourtSessionDemo(made)` (the `AWLiveActivity summons|verdict|verdictReady` demo used to run inside `DemoHarness.model()`, before any presenter existed, so it never started). Push channels no longer force `lockscreenVisibility = VISIBILITY_PRIVATE`: each notification sets its own (`PushNotifications`: private with the generic "Plead / Court notice" public version unless `NotificationPrefs.cached().lockscreenDetails`, then public). Channels created by an earlier build keep the old override until the app is reinstalled (channel settings are immutable once created; no release has shipped). |
| `app/MainActivity.kt` | `Box { RootScreen(model); WidgetPreviewOverlay() }`, so `AWWidgetPreview` works. |
| `app/DemoHarnessModel.kt` | `applyCourtSessionDemo(model)` split out of `apply` (called again once the presenter is attached). |
| `features/onboarding/OnboardingContainer.kt` | The private Play review copy removed; `val requestReview = rememberRequestReview()` (push/ReviewPrompt: once per install, 0.8 s settle, `AWNoReviewPrompt YES` respected, `FakeReviewManager` in demo) called where iOS calls `askForRatingOnce()` (the mock trial's CONTINUE). |
| `features/onboarding/OnboardingWidgetArt.kt` | Deleted. Onboarding imports `widgets/PleadWidgetPalette` and draws the widget sprites with the new `widgets/PixelJudgeGlyphCanvas.kt` (Compose Canvas over the same `PleadPixelSprites`; the Glance `PixelJudgeGlyph` cannot run in app Compose, and a same-named overload would be ambiguous). Sprite grids were identical. |
| Manifest | `PleadWidgetReceiver` label `@string/plead_widget_name` ("Case status"), like the medium / glance receivers. |

Fixed from the emulator screenshots:
- Small widget: the case title tail-truncated ("The Dinner Incide…"): `widgets/WidgetTextFit.kt` now emulates
  `.minimumScaleFactor` (measures with the same typeface and shrinks down to the Swift factor), used for the small title (0.75).
- Lock Screen rectangular: the headline broke mid-phrase ("YOUR RESPONSE IS …") and the detailed variant clipped its
  countdown line: the headline shrinks (to 0.6, as Swift) until whole words fit two lines and the second line(s) fit
  the height; the generic detail line shrinks to 0.8 ("Review it before court…").
- Medium widget at iPhone SE height (148 dp): the "Enter plea" pill was clipped (Roboto's line boxes are taller than
  SF's): short cells tighten the gaps and the pill padding (`MediumWidgetView.compactHeight`).
- Court-session notification in the shade: the collapsed row reused the full banner and was cut in half (Android caps
  collapsed custom views at ~48 dp). The collapsed view is now a compact row (small judge, one-line headline, timer);
  expanded and heads-up keep the full banner.

Screenshots (Pixel 7 API 35 emulator, demo clock 9:41): `widgets2-small`, `widgets2-medium`, `widgets2-lock` (incl.
the 1x1 circular), `widgets2-states`, `live-activity2-banner` (the notification view via `AWWidgetPreview activity`),
`court-session-shade` / `court-session-shade-expanded` (the real notification from `AWLiveActivity summons`),
`onb2-widgets` / `onb2-notices` (onboarding with the widgets package's art).

Fixed since (polish/misc):
- Court-session notification importance (product decision by the integrator; CONTRACTS-v2 amendment ba (2026-10-02)): the
  channel was `IMPORTANCE_LOW`, so Android filed the notification under "Silent" without a status-bar icon, while the
  iOS Live Activity is prominent on the Lock Screen. New channel `court_session_v2` ("Court in session"): default
  importance, no sound, no vibration, no badge; every post `setSilent(true)` + `setOnlyAlertOnce(true)` (no noise, no
  heads-up for updates). The old `court_session` channel is deleted at launch
  (`PleadApplication.setUpNotificationChannels` from `onCreate`, since `polish/all`), not on the first post. Same content, visibility, analytics and `liveActivity.*` keys. Screenshots:
  `court-session-shade` (collapsed, above "Silent"), `court-session-shade-expanded`, `court-session-statusbar`.
- Onboarding's widget illustration no longer tail-truncates "The Dinner Incid…": `widgets/WidgetFitText` (Compose,
  measured with the theme style merged in) applies the same `.minimumScaleFactor(0.75)` as the real small widget.

Open (not fixed):
- Onboarding widgets step at widths under ~360 dp: the small-widget illustration runs past the right edge (the
  composition's offsets are fixed; seen with a 900 px wide override, not on any Pixel width).

## Compose UI tests (ArgueWinUITests port)

| iOS | Android (`src/androidTest/java/app/plead/android/`) | Tests |
|---|---|---|
| `PleadUITestCase.swift` | `support/PleadComposeTestCase.kt` (base class: launch, lookups, waits, taps, gate flows), `support/OnboardingFlows.kt` (`Onboarding` keys, `onboardingStep`, `passMockTrial`, `passSummonsIntro`, `skipMockTrialFromInvitation`, `walkOnboarding`, `progressLabel`, `assertNoPrivacyStep`) | |
| `TrialFlowTests`, `SettlementFlowTests` | `features/court/TrialFlowComposeTests`, `SettlementFlowComposeTests` | 2 + 2 |
| (owed filing / courtroom coverage, Android-only) | `features/court/FileCaseComposeTests` (File a case step by step to a served summons), `CourtFixtureComposeTests` (every `AWCourtFixture` renders and offers its dock's primary control) | 1 + 8 |
| `PaywallGateTests`, `PartnerPaidTests`, `HomeTests`, `DocketTests`, `SettingsSmokeTests`, `UsTabTests` | `features/shell/*ComposeTests` | 5 + 1 + 3 + 5 + 1 + 2 |
| `OnboardingFlowTests`, `PartnerCodeTests` | `features/onboarding/*ComposeTests` | 9 + 4 |

43 tests (plus the earlier `ComponentsComposeTests` / `AppShellComposeTests` smoke tests). Each test builds the demo
`AppModel` from the same launch flags the way `PleadApplication` + `MainActivity` do (on in-memory defaults) and shows
MainActivity's content; a second `launch` in a test is a relaunch on the same defaults. XCUITest's "identifier" is the
Compose `testTag`, its "label" the content description / text. The onboarding and partner-code suites drive the Compose
clock by hand (`PleadComposeTestCase(manualClock = true)`): the link celebration's hearts run a frame loop forever, and
the mock trial must not fast-forward past the beat a test waits for.

**Status:** run on the `visage_phone` emulator (Pixel 7, API 35, headless, animation scales at the default 1) with
`./gradlew --no-daemon :app:connectedDebugAndroidTest`: 51 tests (these 43 + the 8 smoke tests), **50 passed, 1 skipped,
0 failed**, three full runs in a row (624 s, 599 s, 601 s of test time). No test is `@Ignore`d; the one skip is
`testDefenceDueOpensTheDefenceFlow` (below). The mock-trial suites dominate (each plays ~55 s of trial; the onboarding
class takes ~7 min). Earlier they ran under Robolectric only (42 passed, 1 skipped).

Fixed for the device (tests and support only; no app change was needed):
- **Teardown hang** (`UsTabComposeTests.testPairRecordAndPresidingJudge`): the test ended on a `swipeUp()` at the
  bottom of the judge list. Left mid-stretch, the overscroll edge effect redraws every frame (~130 ms a frame on the
  emulator's software GPU), so the main looper never idles and `ActivityScenario.close()` (`waitForIdleSync`) waited
  forever. The test now lets the fling settle (`waitForIdle`) before it ends.
- **Mock-trial timing** (`testResumesOnTheMockTrialAfterRelaunch` failed 1 in 4): manual-clock waits timed out in real
  time while the trial runs on the test clock, and a poll costs more real time than the 48 ms it moves the clock, by a
  varying amount (90 s of real time gave the trial 37–57 s of its ~55 s script). `poll` now runs out only when the
  timeout has passed on the test clock **and** in real time, the way iOS waits on a single clock.
- **Leftover state:** a few services read `UserDefaults.standard` / `UserDefaults.appGroup` directly (court entrance
  seen per case, celebrated settlements, launch state, paywall opening, review prompt, widget snapshot). On a device
  those DataStore files outlive a test, so the support empties both suites before and after every test. Notification
  permission and widget detection never touch real device state: every flow that meets those steps launches with
  `AWPermissions fresh` / `AWWidgetDetected NO`.

Android vs iOS, on purpose:
- No Privacy & tracking step (amendment az: no ATT): onboarding has 11 screens ("Step N of 11"); where iOS passes the
  Privacy screen (`testTrackingFollowsNotificationsThenWidgets`, the partner-code flows) the Android tests assert it
  never shows.
- `testEnterCodeOnPartnerStepEndsOnPartnerPaid` secures the account with **Google** (`secure.google`), the first
  option on Android's SecureAccountView; the shell suites still use the demo `secure.apple`.
- The photo / screenshot exhibit path is not covered: it goes through the system Photo Picker, outside the app's
  Compose hierarchy (`FileCaseComposeTests` adds a quote exhibit instead).
- XCTest screenshot attachments (`us-judges-scrolled`, `us-solo`, `partner-code-linked`) are dropped: a Compose test has
  no attachment store.
- `testDefenceDueOpensTheDefenceFlow` is skipped (assumption) until DemoHarness has an `AWDemoStore defence` store, as
  on iOS.
- `settlement.accept` / `.propose` / `.suggestion`, `judgement.option` / `.deliver` are test tags on the same elements
  as iOS (before any `clearAndSetSemantics`); `TrialFlowComposeTests` / `SettlementFlowComposeTests` use them.
- 21 `testTag`s used to sit after `clearAndSetSemantics` on the same node, which wipes them (onboarding mock-trial
  cards, progress rail, versus card, identity preview, court panel, case previews, court help close / example /
  deadline, case call, Rest…); the tag now comes before it, and the tests use the iOS identifiers again.

## Deviations from the brief (for the integrator / user)

- **compileSdk 36** (brief: 35). supabase-kt 3.2 (androidx.browser 1.9) and Compose 1.9 refuse to compile against
  35. Build-time only; `targetSdk` stays 35. Note: Google Play has required new apps and updates to target the
  latest-but-one API level each August; check whether `targetSdk 36` is now required before the first upload.
- **UUIDs encode upper-case** (Swift `uuidString`); Postgres accepts either case.
- **Backups off** (`allowBackup=false`): the session and state live on the backend.

## Not portable (platform gaps)

| iOS | Android replacement |
|---|---|
| SF Pro Rounded / SF Pro Text | Default sans (Roboto), same weights and sizes; `tnum` for monospaced digits. |
| Live Activity | Ongoing "court in session" notification (wave 3f). `LiveActivityService` (ActivityKit, push-to-start / update tokens, `register_live_activity`) is not ported; its planner and content state are in `services/CourtSessionState.kt`. |
| `WidgetPreviewHarness` (`AWWidgetPreview`) | Ported in wave 3f (`widgets/WidgetPreviewHarness.kt`). |
| ATT prompt / `checkTrialOrIntroDiscountEligibility` | Tracking always authorised (amendment az); Google Play only returns trial offers the account is eligible for. |
| ATT prompt | None on Android; `AWNoATTPrompt` parsed for flag parity only. |
| `AppsFlyer app id` (App Store id) | AppsFlyer keys Android apps by package name: `AppConfig.appsFlyerAppID` is always null. |

## Needs the user

One list for everything the port cannot do without the user's accounts or decisions:

- **Firebase:** a Firebase project for `app.plead.android`; `google-services.json` in `android/app/` (gitignored) and
  the `FCM_SERVICE_ACCOUNT` secret on the Supabase project (amendment az). Until then push is inert (the app runs; no
  token is registered).
- **Backend deploy:** `db push` of `20260930000100_push_tokens_platform.sql` and deploy of `register_push` + the
  functions that import `_shared/notify.ts` / `_shared/push.ts` / `_shared/fcm.ts`.
- **RevenueCat:** the **Google Play** public key (`goog_…`) in `local.properties` (the iOS `appl_` key does not
  work); the Play app added to RevenueCat with the `premium` entitlement, default offering and `exit_offer` offering.
- **Play Console:** the app (`app.plead.android`), subscription products matching the iOS ones, a release **upload
  keystore** and a `signingConfig` for `release` (today `assembleRelease` produces an unsigned APK; no keystore was
  invented), Data safety form (merged library permissions to review: `AD_ID` from Firebase / AppsFlyer,
  `USE_BIOMETRIC` / `USE_FINGERPRINT` from Credential Manager, vendor store permissions from AppsFlyer), and a check
  whether `targetSdk 36` is now required.
- **Google sign-in:** `GOOGLE_WEB_CLIENT_ID` (Google OAuth web client) in `local.properties` and the Supabase Google
  provider enabled.
- **Sign in with Apple on Android:** the Supabase Apple provider enabled for the OAuth (web) flow (Services ID + secret
  key); until then the app detects it is off (`AuthService.isAppleAvailable()`).
- **App Links:** `/.well-known/assetlinks.json` on `plead-drab.vercel.app` with the release signing SHA-256 so
  `/join/` links verify.
- **AppsFlyer:** the dev key in `local.properties` if attribution should run on Android.
- **Legal / business facts:** unchanged from iOS: bracketed placeholders until supplied.
