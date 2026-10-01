// PleadUITestCase's onboarding helpers (`Onboarding` keys, `onboardingStep`, `passMockTrial`, `passSummonsIntro`,
// `walkOnboarding`…) for any PleadComposeTestCase.
//
// Android differences (CONTRACTS-v2 amendment az; docs/STATUS.md): eleven screens ("Step N of 11"), no Privacy &
// tracking step between Court Notices and Widgets (asserted absent where iOS passes it).
package app.plead.android.support

import org.junit.Assert.assertTrue

/** `PleadUITestCase.Onboarding`. Headlines are matched as TalkBack reads them (`CourtHeadline.spoken`, one line). */
object Onboarding {
    const val welcome = "Settle arguments. Let AI judge."
    const val mockTrial = "onboarding.mockTrial"
    const val mockTrialStart = "onboarding.mockTrial.start"
    const val mockTrialSkip = "onboarding.mockTrial.skip"
    const val mockTrialContinue = "onboarding.mockTrial.continue"
    const val mockClaim = "onboarding.mockTrial.claim"
    const val mockExhibit = "onboarding.mockTrial.exhibit"
    const val mockClosedTitle = "That's a Plead trial."
    const val mockHelp = "court.help.mockOpeningStatement"
    const val helpSheet = "court.help.sheet"
    const val helpGotIt = "court.help.gotIt"
    const val progress = "onboarding.progress"
    const val versusCard = "onboarding.versusCard"
    const val summonsIntro = "onboarding.summonsIntro"
    const val summonsIntroContinue = "onboarding.summonsIntro.continue"
    const val summonsHeadline = "Your turn to summon them."
    const val howItWorks = "Three steps to a verdict."
    const val aiCourt = "One judge. Multiple opinions."
    const val examples = "What's going to court first?"
    const val identity = "How should the court know you?"
    const val partner = "Every case needs another side."
    const val notifications = "Don't miss your summons."
    const val widgets = "Court follows you."

    /** The iOS Privacy & tracking screen's headline: never routed to on Android (amendment az). */
    const val tracking = "Your arguments stay between you."
    const val ready = "Court is now in session"
    const val readyFileCase = "onboarding.ready.fileCase"

    /** Android: eleven active screens (no Privacy & tracking step). */
    const val screenCount = 11
}

/** An onboarding screen: waits for its headline, taps [tapping], waits until the headline leaves. */
fun PleadComposeTestCase.onboardingStep(headline: String, tapping: String) {
    waitFor(headline)
    tap(tapping)
    waitForGone(headline)
}

/** `element("onboarding.progress").label`: "Step N of M". */
fun PleadComposeTestCase.progressLabel(): String {
    waitFor(tag(Onboarding.progress), Onboarding.progress)
    return label(node(tag(Onboarding.progress)))
}

/**
 * Screen 2, the Mock Trial Demo (`passMockTrial(closedTimeout:)`): START MOCK TRIAL, then up to [closedTimeoutMs] for
 * CASE CLOSED's CTA, falling back to SKIP DEMO; waits until the scene is gone (both exits land on the summons
 * explainer).
 */
fun PleadComposeTestCase.passMockTrial(closedTimeoutMs: Long = 90_000) {
    waitFor(Onboarding.mockTrial)
    val start = element(Onboarding.mockTrialStart)
    if (waitUntil(2_000) { isHittable(start) }) {
        tap(start, Onboarding.mockTrialStart)
        waitUntilAdvancing(closedTimeoutMs) { exists(Onboarding.mockTrialContinue) }
    }
    val exit = listOf(Onboarding.mockTrialContinue, Onboarding.mockTrialSkip, "Skip demo")
        .firstOrNull { isHittable(element(it)) } ?: Onboarding.mockTrialSkip
    tap(exit)
    waitForGone(Onboarding.mockTrial)
}

/** `skipMockTrialFromInvitation()`: START MOCK TRIAL is offered; SKIP DEMO without starting. */
fun PleadComposeTestCase.skipMockTrialFromInvitation() {
    waitFor(Onboarding.mockTrial)
    waitFor(Onboarding.mockTrialStart)
    tap(Onboarding.mockTrialSkip)
    waitForGone(Onboarding.mockTrial)
}

/** Screen 3, the summons explainer: CONTINUE, then gone (How Plead Works follows). */
fun PleadComposeTestCase.passSummonsIntro() {
    waitFor(Onboarding.summonsIntro)
    tap(Onboarding.summonsIntroContinue)
    waitForGone(Onboarding.summonsIntro)
}

/**
 * `walkOnboarding(name:)`: every screen of a brand-new user (`AWDemoStore signedOut`), leaving the app at the gate.
 * iOS passes the Privacy & tracking screen between Court Notices and Widgets; Android has none (amendment az).
 */
fun PleadComposeTestCase.walkOnboarding(name: String = "Arif") {
    onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
    passMockTrial()
    passSummonsIntro()
    onboardingStep(Onboarding.howItWorks, tapping = "Continue")
    onboardingStep(Onboarding.aiCourt, tapping = "Continue")
    onboardingStep(Onboarding.examples, tapping = "Continue")
    waitFor(Onboarding.identity)
    type(name, into = "onboarding.name")
    onboardingStep(Onboarding.identity, tapping = "Save my identity")
    onboardingStep(Onboarding.partner, tapping = "I'll do this later")
    onboardingStep(Onboarding.notifications, tapping = "Not now")
    onboardingStep(Onboarding.widgets, tapping = "Not now")
    onboardingStep(Onboarding.ready, tapping = Onboarding.readyFileCase)
}

/** Android has no Privacy & tracking screen: asserts it never shows (where iOS `passPrivacy()` passes it). */
fun PleadComposeTestCase.assertNoPrivacyStep() {
    assertTrue("The Privacy & tracking screen showed on Android", !exists(Onboarding.tracking))
}
