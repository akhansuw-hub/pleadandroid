// Port of the launch-argument side of ArgueWin/App/DemoHarness.swift (and every other `"AW…"` flag the iOS app
// reads through `UserDefaults.standard`). Debug builds only: release builds ignore every flag, as iOS `#if DEBUG`.
//
// iOS passes `-AWDemo YES` as launch arguments; Android takes the same names, minus the dash, as intent extras
// on MainActivity (PORT.md §4):
//
//   adb shell am start -S -n app.plead.android/.app.MainActivity \
//       --es AWDemo YES --es AWDemoStore empty --es AWTab court
//
// String extras (`--es`) are the norm; `--ez/--ei/--ef/--el` work too (read back as their string form). Like the
// iOS argument domain the flags live for one process: start with `-S` (force-stop first) so a new set applies.
// With no extras, debug builds default `AWDemo` to YES when no Supabase project is configured (commit 83141d4).
//
// Wave 1 owns the parsing (this file's `LaunchArguments` + typed accessors). The demo model factory
// (`DemoHarness.model()`), `apply(to:)`, `adoptExistingAccount`, `sampleDrafts` and `onboardStep` depend on
// AppModel / PreviewData / OnboardingStep and are added here by their waves (2a/2b/3b) as extensions or members.
//
// Every flag, grouped by area (see DemoHarness.swift for the full semantics). Most need `AWDemo YES`.
//
// Demo mode & trial simulator
//   AWDemo YES · AWDemoSpeed fast · AWAutoplay YES
//   AWDemoStore solo|empty|premium|unpaid|partnerPaid|signedOut|anonymousPaid|awaiting|deliberating|profile|link
//               |judgement|judgementLoser|judgementDelivered|judgementAccepted|judgementServed
//               |settlementOffer|settlementCounter|settlementFinal|settled
// Launch & cold open
//   AWColdOpen full|sting|none · AWNoReviewPrompt YES · AWDemoReduceMotion YES
//   AWMockTrialBeat <beat name | analytics name | 0…13>
//   AWCourtFixture opening|evidence|cross|ruling|objection|deliberation|verdict|replay
//   AWCourtReplayFrom N · AWCourtReplayStep S · AWCourtEntrance replay|late|<seconds>|caseCall|introduction
//   AWCourtHelp openingStatement|evidence|crossExamination|objection|verdict
// Navigation, sheets & scroll positions
//   AWTab home|cases|court|us
//   AWSheet fileCase|defence|scheduling|paywall|exitOffer|settings|notifications|summons|detail|deliberationDetail
//           |celebrate|judgement|judgementDetail|tieDetail|overdueDetail|settlementRoom|settlementCounter
//           |settlementAccepted|settledDetail
//   AWFileStep evidence · AWOpenCase 14 · AWScroll outstanding|agreement|closed…|panel|judgement|settlement
//   AWDocketFilter open|closed · AWJudgementSelect <option id> · AWSettlementPrompt off · AWSettings delete|preview|bottom
// Onboarding
//   AWOnboardStep 1…12|<screen_id> · AWOnboardResume YES · AWPermissions fresh · AWNoATTPrompt YES
//   AWWidgetDetected YES · AWLiveActivitiesOff YES · AWWidgetSheet home|lock|both · AWLinkMode join|linked
//   AWPartnerJoin YES|<CODE> · AWDemoJoin paid|unpaid|invalid|limited · AWDemoName <name>
//   AWSecureConflict YES · AWSecureStage email|code|conflict
// Paywall & exit offer
//   AWTrial no · AWExitOffer no · AWPlan annual|monthly|weekly · AWProducts annual,monthly,weekly
//   AWPricesLoading YES · AWPaywallOpening YES|NO · AWAutoCloseAfter 2.5
// Widgets & Live Activities (Android: the ongoing court-session notification, PORT.md §2)
//   AWLiveActivity summons|verdict|verdictReady|auto
//   AWWidgetPreview YES|lock|small|medium|states|tinted|activity|live · AWWidgetPreviewType large|xl|xxl|ax1|ax3|ax5
package app.plead.android.app

import android.content.Intent
import android.os.Bundle
import app.plead.android.BuildConfig

/**
 * The iOS `UserDefaults` argument domain: flag values for this process, with `UserDefaults` read semantics
 * (`bool`, `integer`, `double` parse the string the way `NSString.boolValue` etc. do). Registered defaults
 * (iOS `UserDefaults.register(defaults:)`) sit underneath the launch values.
 */
object LaunchArguments {
    @Volatile private var values: Map<String, String> = emptyMap()
    @Volatile private var defaults: Map<String, String> = registeredDefaults()

    /** Debug builds honour flags; release builds never do (iOS `#if DEBUG`). */
    val isEnabled: Boolean get() = BuildConfig.DEMO_HARNESS

    /** Reads every `AW…` extra of the launch intent. Call once per process from MainActivity.onCreate. */
    fun load(intent: Intent?) {
        load(intent?.extras)
    }

    fun load(extras: Bundle?) {
        if (!isEnabled) {
            values = emptyMap()
            return
        }
        val out = mutableMapOf<String, String>()
        extras?.keySet()?.filter { it.startsWith("AW") }?.forEach { key ->
            @Suppress("DEPRECATION")
            val raw = extras.get(key)
            when (raw) {
                null -> Unit
                is Boolean -> out[key] = if (raw) "YES" else "NO"
                is Array<*> -> out[key] = raw.joinToString(",")
                else -> out[key] = raw.toString()
            }
        }
        values = out
        registerDefaults()
    }

    /** For unit tests and previews: replace the launch values directly (keys without the dash). */
    fun set(values: Map<String, String>) {
        this.values = values.toMap()
        registerDefaults()
    }

    private fun registerDefaults() {
        defaults = registeredDefaults()
    }

    /**
     * Commit 83141d4: with no Supabase project configured a debug build can't sign in at all, so demo becomes
     * the registered default (tapping the icon works); a real config or an explicit `AWDemo NO` still wins.
     */
    private fun registeredDefaults(): Map<String, String> =
        if (BuildConfig.DEMO_BY_DEFAULT) mapOf("AWDemo" to "YES") else emptyMap()

    /** `UserDefaults.object(forKey:) != nil`. */
    fun has(key: String): Boolean = isEnabled && (values.containsKey(key) || defaults.containsKey(key))

    /** `UserDefaults.string(forKey:)`. */
    fun string(key: String): String? = if (!isEnabled) null else values[key] ?: defaults[key]

    /** `UserDefaults.bool(forKey:)`: `NSString.boolValue` (Y/y/T/t or a non-zero digit, after sign/zeros). */
    fun bool(key: String): Boolean = string(key)?.let(::boolValue) ?: false

    /** `UserDefaults.integer(forKey:)`: leading integer, 0 otherwise. */
    fun integer(key: String): Int = string(key)?.let(::integerValue) ?: 0

    /** `UserDefaults.double(forKey:)`: leading decimal, 0 otherwise. */
    fun double(key: String): Double = string(key)?.let(::doubleValue) ?: 0.0

    /** All values currently set (debug overlays, logging). */
    val all: Map<String, String> get() = if (!isEnabled) emptyMap() else defaults + values

    // MARK: NSString parsing rules

    fun boolValue(raw: String): Boolean {
        var s = raw.trimStart()
        if (s.startsWith("+") || s.startsWith("-")) s = s.drop(1)
        s = s.trimStart('0')
        val c = s.firstOrNull() ?: return false
        return c == 'Y' || c == 'y' || c == 'T' || c == 't' || c in '1'..'9'
    }

    fun integerValue(raw: String): Int {
        val m = Regex("^\\s*([+-]?\\d+)").find(raw) ?: return 0
        return m.groupValues[1].toLongOrNull()?.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())?.toInt() ?: 0
    }

    fun doubleValue(raw: String): Double {
        val m = Regex("^\\s*([+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)").find(raw) ?: return 0.0
        return m.groupValues[1].toDoubleOrNull() ?: 0.0
    }
}

/**
 * Typed view of every demo flag (Swift `DemoHarness` + the `UserDefaults` reads spread over the iOS app).
 * Raw strings stay available through [LaunchArguments] for flags whose value set is open-ended.
 */
object DemoHarness {
    private val d = LaunchArguments

    // MARK: Demo mode & trial simulator

    /** `AWDemo YES`: PreviewData + the local trial simulator, no network. */
    val isDemo: Boolean get() = d.bool("AWDemo")

    /** `AWDemoSpeed fast`: every simulated delay 0.3 s (verdict reveal 3 s). */
    val demoSpeed: String? get() = d.string("AWDemoSpeed")
    val isFastDemo: Boolean get() = demoSpeed == "fast"

    /** `AWAutoplay YES`: the simulator also plays my turns. */
    val autoplay: Boolean get() = d.bool("AWAutoplay")

    enum class DemoStore {
        solo, empty, premium, unpaid, partnerPaid, signedOut, anonymousPaid, awaiting, deliberating, profile, link,
        judgement, judgementLoser, judgementDelivered, judgementAccepted, judgementServed,
        settlementOffer, settlementCounter, settlementFinal, settled;

        companion object {
            fun fromRaw(raw: String?): DemoStore? = entries.firstOrNull { it.name == raw }
        }
    }

    /** `AWDemoStore …` (null = the default demo couple). */
    val demoStore: DemoStore? get() = DemoStore.fromRaw(d.string("AWDemoStore"))

    // MARK: Launch & cold open

    enum class ColdOpen { full, sting, none }

    /** `AWColdOpen full|sting|none` (works without AWDemo). */
    val coldOpen: ColdOpen? get() = d.string("AWColdOpen")?.let { raw -> ColdOpen.entries.firstOrNull { it.name == raw } }

    /** `AWNoReviewPrompt YES`: never ask for a rating after the mock trial. */
    val noReviewPrompt: Boolean get() = d.bool("AWNoReviewPrompt")

    /** `AWDemoReduceMotion YES` (with AWDemo): paywall courtroom hero behaves as if Reduce Motion were on. */
    val demoReduceMotion: Boolean get() = isDemo && d.bool("AWDemoReduceMotion")

    /** `AWMockTrialBeat <beat name | analytics name | 0…13>` (raw; MockTrial resolves it). */
    val mockTrialBeat: String? get() = d.string("AWMockTrialBeat")

    /** `AWCourtFixture opening|evidence|cross|ruling|objection|deliberation|verdict|replay`. */
    val courtFixture: String? get() = d.string("AWCourtFixture")

    /** `AWCourtReplayFrom N` (0 when absent, as `integer(forKey:)`). */
    val courtReplayFrom: Int get() = d.integer("AWCourtReplayFrom")

    /** `AWCourtReplayStep S` seconds (0 when absent). */
    val courtReplayStep: Double get() = d.double("AWCourtReplayStep")

    /** `AWCourtEntrance replay|late|<seconds>|caseCall|introduction`. */
    val courtEntrance: String? get() = d.string("AWCourtEntrance")

    /** `AWCourtHelp openingStatement|evidence|crossExamination|objection|verdict`. */
    val courtHelp: String? get() = d.string("AWCourtHelp")

    // MARK: Navigation, sheets & scroll positions

    enum class Tab { home, cases, court, us }

    /** `AWTab home|cases|court|us`. */
    val tab: Tab? get() = d.string("AWTab")?.let { raw -> Tab.entries.firstOrNull { it.name == raw } }

    enum class Sheet {
        fileCase, defence, scheduling, paywall, exitOffer, settings, notifications, summons, detail, deliberationDetail,
        celebrate, judgement, judgementDetail, tieDetail, overdueDetail, settlementRoom, settlementCounter,
        settlementAccepted, settledDetail,
    }

    /** `AWSheet …`. */
    val sheet: Sheet? get() = d.string("AWSheet")?.let { raw -> Sheet.entries.firstOrNull { it.name == raw } }

    /** `AWFileStep evidence` (with AWSheet fileCase): open on the evidence step with sample drafts. */
    val fileCaseEvidenceStep: Boolean get() = isDemo && d.string("AWFileStep") == "evidence"

    /** `AWOpenCase 14`: push that case number's record on the Cases tab (0 = none). */
    val openCase: Int get() = d.integer("AWOpenCase")

    /** `AWScroll outstanding|agreement|closed…|panel|judgement|settlement`. */
    val scroll: String? get() = d.string("AWScroll")

    /** `AWDocketFilter open|closed` (older values all/won/lost/tied/settled mean closed). */
    val docketFilter: String? get() = d.string("AWDocketFilter")

    /** `AWJudgementSelect <option id>`. */
    val judgementSelect: String? get() = d.string("AWJudgementSelect")

    /** `AWSettlementPrompt off`. */
    val settlementPromptOff: Boolean get() = d.string("AWSettlementPrompt") == "off"

    /** `AWSettings delete|preview|bottom` (with AWSheet settings). */
    val settings: String? get() = d.string("AWSettings")

    // MARK: Onboarding

    /** `AWOnboardStep 1…12|<screen_id>` (raw; OnboardingStep resolves numbers and screen ids). */
    val onboardStepRaw: String? get() = d.string("AWOnboardStep")

    /** `AWOnboardResume YES`: keep saved onboarding progress between demo launches. */
    val onboardResume: Boolean get() = d.bool("AWOnboardResume")

    /** `AWPermissions fresh` (with AWDemo): notifications report undetermined until their CTA is tapped. */
    val permissionsFresh: Boolean get() = isDemo && d.string("AWPermissions") == "fresh"

    /** `AWNoATTPrompt YES` (iOS ATT; Android has no ATT prompt, kept for flag parity). */
    val noATTPrompt: Boolean get() = d.bool("AWNoATTPrompt")

    /** `AWWidgetDetected YES` / `AWLiveActivitiesOff YES` (onboarding screen 8); [widgetFlagsSet] = either given. */
    val widgetDetected: Boolean get() = d.bool("AWWidgetDetected")
    val liveActivitiesOff: Boolean get() = d.bool("AWLiveActivitiesOff")
    val widgetFlagsSet: Boolean get() = d.has("AWWidgetDetected") || d.has("AWLiveActivitiesOff")

    /** `AWWidgetSheet home|lock|both`. */
    val widgetSheet: String? get() = d.string("AWWidgetSheet")

    /** `AWLinkMode join|linked`. */
    val linkMode: String? get() = d.string("AWLinkMode")

    /** `AWPartnerJoin YES|<CODE>` (with AWDemo). */
    val partnerJoin: String? get() = if (isDemo) d.string("AWPartnerJoin") else null

    /** `AWDemoJoin paid|unpaid|invalid|limited`. */
    val demoJoin: String? get() = d.string("AWDemoJoin")

    /** `AWDemoName <name>` (with AWDemo): the identity step's prefilled name (first 30 characters). */
    val demoName: String? get() = if (isDemo) d.string("AWDemoName")?.take(30) else null

    /** `AWSecureConflict YES`. */
    val secureConflict: Boolean get() = d.bool("AWSecureConflict")

    /** `AWSecureStage email|code|conflict`. */
    val secureStage: String? get() = d.string("AWSecureStage")

    // MARK: Paywall & exit offer

    /** `AWTrial no`: the annual product is not trial-eligible. */
    val trialEligible: Boolean get() = d.string("AWTrial") != "no"

    /** `AWExitOffer no`: the exit offer is not eligible. */
    val exitOfferEligible: Boolean get() = d.string("AWExitOffer") != "no"

    /** `AWPlan annual|monthly|weekly`. */
    val plan: String? get() = d.string("AWPlan")

    /** `AWProducts annual,monthly,weekly`: only these products "load" (null = all). */
    val products: Set<String>? get() = d.string("AWProducts")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()

    /** `AWPricesLoading YES`. */
    val pricesLoading: Boolean get() = d.bool("AWPricesLoading")

    /** `AWPaywallOpening YES|NO`: true = force the bloom every time, false = never, null = the real rule. */
    val paywallOpening: Boolean? get() = if (d.has("AWPaywallOpening")) d.bool("AWPaywallOpening") else null

    /** `AWAutoCloseAfter 2.5` seconds (with AWDemo; 0 = off). */
    val autoCloseAfter: Double get() = if (isDemo) d.double("AWAutoCloseAfter") else 0.0

    // MARK: Widgets & Live Activities

    /** `AWLiveActivity summons|verdict|verdictReady|auto` (Android: the ongoing court-session notification). */
    val liveActivity: String? get() = d.string("AWLiveActivity")

    /** `AWWidgetPreview YES|lock|small|medium|states|tinted|activity|live` (works without AWDemo). */
    val widgetPreview: String? get() = d.string("AWWidgetPreview")

    /** `AWWidgetPreviewType large|xl|xxl|ax1|ax3|ax5` (lower-cased). */
    val widgetPreviewType: String? get() = d.string("AWWidgetPreviewType")?.lowercase()
}
