// The model half of ArgueWin/App/DemoHarness.swift (wave 1 ported the flag parsing in DemoHarness.kt): the demo
// AppModel factory, `apply(to:)`, the account adoption and the sample drafts. Debug builds only use these
// (`LaunchArguments` ignores every flag in release builds, so `DemoHarness.isDemo` is always false there).
package app.plead.android.app

import app.plead.android.models.ExhibitType
import app.plead.android.models.JudgementStatus
import app.plead.android.services.CaseStore
import app.plead.android.services.DraftExhibit
import app.plead.android.services.PreviewData
import app.plead.android.services.UserDefaults
import app.plead.android.services.WidgetFamily
import app.plead.android.services.WidgetSetupService
import java.time.Instant

/** Enough drafts to show the list growing past the old cap and scrolling. */
val DemoHarness.sampleDrafts: List<DraftExhibit>
    get() {
        val day = 86_400L
        val d1 = Instant.now().minusSeconds(3 * day)
        val d2 = Instant.now().minusSeconds(2 * day)
        return listOf(
            DraftExhibit(type = ExhibitType.receipt, caption = "The promise", body = DraftExhibit.receiptLine(d1, "said he'd be home by 9"), occurredAt = d1),
            DraftExhibit(type = ExhibitType.text, caption = "Their exact words", body = "It's not cold, you're being dramatic"),
            DraftExhibit(type = ExhibitType.screenshot, caption = "Thermostat at 26°C at 23:40", occurredAt = d2),
            DraftExhibit(type = ExhibitType.receipt, caption = "Second offence", body = DraftExhibit.receiptLine(d2, "turned it up again"), occurredAt = d2),
            DraftExhibit(type = ExhibitType.text, caption = "The apology that wasn't", body = "Sorry you feel that way"),
            DraftExhibit(type = ExhibitType.photo, caption = "Me, in a jumper, in September"),
            DraftExhibit(type = ExhibitType.text, caption = "Witness statement (the cat)", body = "Meow"),
        )
    }

/**
 * `AWOnboardStep n|screen_id` as the persisted `OnboardingStep` raw value (1…12; e.g. 11 or `mock_trial` for the
 * Mock Trial Demo, 12 or `summons_intro` for the summons explainer), if given.
 */
val DemoHarness.onboardStep: Int?
    get() {
        val value = onboardStepRaw ?: return null
        value.toIntOrNull()?.let { raw -> return raw.takeIf { it in ShellOnboarding.displayOrder } }
        return ShellOnboarding.screenIds.entries.firstOrNull { it.value == value }?.key
    }

/** A step at or before `identity` in display order (amendment y: raw values are not ordered). */
private fun atOrBeforeIdentity(raw: Int) = ShellOnboarding.displayIndex(raw) <= ShellOnboarding.displayIndex(ShellOnboarding.identity)

/** The demo AppModel for this launch's flags (`AWOnboardStep`, `AWDemoStore …`). */
fun DemoHarness.model(defaults: UserDefaults = UserDefaults.standard): AppModel {
    onboardStep?.let { step ->
        if (atOrBeforeIdentity(step)) {
            return PreviewData.model(store = CaseStore(preview = null, partner = null, couple = null), signedIn = false, defaults = defaults)
        }
        if (step == ShellOnboarding.partner) {
            return PreviewData.model(store = CaseStore(preview = PreviewData.onboardingMe, partner = null, couple = null), defaults = defaults)
        }
        return PreviewData.model(store = PreviewData.onboardingSoloStore(), defaults = defaults)
    }
    return when (demoStore) {
        DemoHarness.DemoStore.solo -> PreviewData.model(PreviewData.soloStore(), defaults = defaults)
        DemoHarness.DemoStore.empty -> PreviewData.model(PreviewData.emptyStore(), defaults = defaults)
        DemoHarness.DemoStore.premium -> PreviewData.model(PreviewData.premiumStore(), defaults = defaults)
        DemoHarness.DemoStore.unpaid -> PreviewData.model(PreviewData.unpaidStore(), defaults = defaults)
        DemoHarness.DemoStore.partnerPaid -> PreviewData.partnerPaidModel(defaults)
        DemoHarness.DemoStore.awaiting -> PreviewData.model(PreviewData.store(cases = listOf(PreviewData.awaitingCase, PreviewData.wonCase)), defaults = defaults)
        DemoHarness.DemoStore.deliberating -> PreviewData.model(PreviewData.deliberatingStore(), defaults = defaults)
        DemoHarness.DemoStore.signedOut -> PreviewData.model(CaseStore(preview = null, partner = null, couple = null), signedIn = false, defaults = defaults)
        DemoHarness.DemoStore.anonymousPaid -> PreviewData.anonymousPaidModel(defaults)
        DemoHarness.DemoStore.profile -> PreviewData.model(CaseStore(preview = null, partner = null, couple = null), defaults = defaults)
        DemoHarness.DemoStore.link -> PreviewData.model(CaseStore(preview = PreviewData.me, partner = null, couple = null), defaults = defaults)
        // Court judgement (amendment j): verdict just revealed on "The Last Slice".
        DemoHarness.DemoStore.judgement -> PreviewData.model(PreviewData.judgementStore(), defaults = defaults)
        DemoHarness.DemoStore.judgementLoser -> PreviewData.model(PreviewData.judgementStore(iWon = false), defaults = defaults)
        DemoHarness.DemoStore.judgementDelivered -> PreviewData.model(PreviewData.judgementStore(iWon = false, status = JudgementStatus.delivered), defaults = defaults)
        DemoHarness.DemoStore.judgementAccepted -> PreviewData.model(PreviewData.judgementStore(status = JudgementStatus.accepted), defaults = defaults)
        DemoHarness.DemoStore.judgementServed -> PreviewData.model(PreviewData.judgementStore(status = JudgementStatus.served), defaults = defaults)
        // Settle Outside Court (amendment n): an offer from Alex awaits me (round 1 / final round 3),
        // or #028 is settled with the agreement outstanding.
        DemoHarness.DemoStore.settlementOffer -> PreviewData.model(PreviewData.settlementOfferStore(round = 1), defaults = defaults)
        DemoHarness.DemoStore.settlementCounter -> PreviewData.model(PreviewData.settlementOfferStore(round = 2), defaults = defaults)
        DemoHarness.DemoStore.settlementFinal -> PreviewData.model(PreviewData.settlementOfferStore(round = 3), defaults = defaults)
        DemoHarness.DemoStore.settled -> PreviewData.model(PreviewData.settledStore(), defaults = defaults)
        null -> PreviewData.model(defaults = defaults)
    }
}

/** Applies the navigation / sheet / onboarding flags to a freshly built demo model. */
fun DemoHarness.apply(to: AppModel, defaults: UserDefaults = UserDefaults.standard) {
    val model = to
    if (!isDemo) return
    when (tab) {
        DemoHarness.Tab.cases -> model.router.tab = AppTab.cases
        DemoHarness.Tab.court -> model.router.tab = AppTab.court
        DemoHarness.Tab.us -> model.router.tab = AppTab.us
        else -> Unit
    }
    model.router.deferredSummons = model.router.deferredSummons + PreviewData.summonedCase.id
    // Start (or restart) the simulator: anything queued by a previous launch is gone with that process.
    model.store.demo?.resume()
    applyOnboarding(model)
    // Demo launches replay the paywall opening every time (the real once-per-user rule, amendment s, is
    // keyed on `paywallOpeningSeen.<uid>`; forget it for every user so a demo run always shows the bloom).
    for (key in defaults.keys) if (key.startsWith("paywallOpeningSeen.")) defaults.removeObject(key)
    when (sheet) {
        DemoHarness.Sheet.fileCase -> model.router.sheet = AppSheet.fileCase
        DemoHarness.Sheet.defence -> model.router.sheet = AppSheet.defence(PreviewData.defenceCase.id)
        DemoHarness.Sheet.scheduling -> model.router.sheet = AppSheet.scheduling(PreviewData.schedulingCase.id)
        DemoHarness.Sheet.paywall, DemoHarness.Sheet.exitOffer -> model.store.markPremiumLapsed() // the gate paywall (root state, not a sheet)
        DemoHarness.Sheet.settings -> model.router.sheet = AppSheet.settings
        DemoHarness.Sheet.summons -> model.router.summonsCaseId = PreviewData.summonedCase.id
        DemoHarness.Sheet.detail -> { model.router.tab = AppTab.cases; model.router.casesPath = listOf(PreviewData.wonCase.id) }
        DemoHarness.Sheet.deliberationDetail -> { model.router.tab = AppTab.cases; model.router.casesPath = listOf(PreviewData.deliberatingCase.id) }
        DemoHarness.Sheet.celebrate -> model.store.linkCelebration = true
        DemoHarness.Sheet.judgement -> model.router.sheet = AppSheet.chooseJudgement(PreviewData.judgementCase.id)
        DemoHarness.Sheet.judgementDetail -> { model.router.tab = AppTab.cases; model.router.casesPath = listOf(PreviewData.judgementCase.id) }
        DemoHarness.Sheet.tieDetail -> { model.router.tab = AppTab.cases; model.router.casesPath = listOf(PreviewData.dinnerTieCase.id) }
        DemoHarness.Sheet.overdueDetail -> { model.router.tab = AppTab.cases; model.router.casesPath = listOf(PreviewData.guiltyCase.id) }
        DemoHarness.Sheet.settlementRoom -> model.router.sheet = AppSheet.settlementRoom(PreviewData.summonedCase.id)
        DemoHarness.Sheet.settlementCounter -> model.router.sheet = AppSheet.settlementRoom(PreviewData.trialCase.id) // with AWDemoStore settlementCounter
        DemoHarness.Sheet.settlementAccepted -> model.router.sheet = AppSheet.settlementAccepted(PreviewData.settledCase.id)
        DemoHarness.Sheet.settledDetail -> { model.router.tab = AppTab.cases; model.router.casesPath = listOf(PreviewData.settledCase.id) }
        // ArgueWinApp.applyNotificationDemo: `AWSheet notifications` opens Settings on its Notifications section.
        DemoHarness.Sheet.notifications -> model.router.sheet = AppSheet.settings
        null -> Unit
    }
    applyCourtSessionDemo(model)
}

/**
 * `AWLiveActivity summons|verdict|verdictReady`: a fixed demo court session (`auto` runs the planner). A no-op until
 * the presenter is attached, so PleadApplication calls it again right after setting `courtSession`.
 */
fun DemoHarness.applyCourtSessionDemo(model: AppModel) {
    liveActivity?.takeIf { it != "auto" }?.let { mode ->
        model.courtSession?.let {
            it.isForeground = true
            it.startDemo(mode)
        }
    }
}

/**
 * "Sign in to that account instead" in demo mode (no backend): the existing account is the
 * default demo couple (Sam & Alex, premium, with case history).
 */
fun DemoHarness.adoptExistingAccount(model: AppModel) {
    model.store.demoAdopt(PreviewData.store())
    model.store.demo?.resume()
}

/** Demo onboarding starts clean (screen 1, or `AWOnboardStep`) unless `AWOnboardResume YES`. */
private fun DemoHarness.applyOnboarding(model: AppModel) {
    val onboarding = model.onboarding
    if (!onboardResume) {
        // Signed-out demo: THAT'S ME always signs in as the same anonymous demo user, so clear its scope too.
        val signedOut = demoStore == DemoHarness.DemoStore.signedOut || (onboardStep?.let(::atOrBeforeIdentity) ?: false)
        onboarding.resetForDemo(if (signedOut) listOf(PreviewData.anonId) else emptyList())
    }
    if (widgetFlagsSet) {
        onboarding.widgetSetup = WidgetSetupService.fixed(
            families = if (widgetDetected) listOf(WidgetFamily.systemSmall) else emptyList(),
            activitiesEnabled = !liveActivitiesOff,
        )
    }
    val step = onboardStep ?: return
    onboarding.applyDemoStep(step)
}
