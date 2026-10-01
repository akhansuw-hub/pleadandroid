// Port of ArgueWinUITests/OnboardingFlowTests.swift: the screens of a brand-new user (the Mock Trial Demo second,
// amendment y; the summons explainer third, amendment ai), back navigation, and resume after relaunch, driven through
// the real RootScreen / OnboardingContainer on a demo AppModel built from the same launch flags.
//
// Android differences (docs/STATUS.md, wave 3b decisions; CONTRACTS-v2 amendment az): eleven screens ("Step N of
// 11"), no Privacy & tracking step between Court Notices and Widgets, so the tracking test asserts the step is
// skipped instead of passing it.
package app.plead.android.features.onboarding

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.features.onboarding.OnboardingTestSupport.Companion.newUserArguments
import app.plead.android.features.onboarding.OnboardingTestSupport.Onboarding
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingFlowComposeTests {
    @get:Rule val rule = createComposeRule()
    private val ui = OnboardingTestSupport(rule)

    @After fun tearDown() = ui.tearDown()

    /** signedOut → Welcome → Mock Trial Demo → every screen via its CTA → the gate. */
    @Test fun testWalksAllScreens() {
        ui.launch(newUserArguments)
        ui.walkOnboarding(name = "Arif")
        // Past the last screen the gate takes over (link step or paywall), never the tabs: nothing is paid yet.
        assertFalse("An unpaid new user reached the tabs", ui.tabsAppear(timeout = 2.0))
        assertTrue(
            "Neither the paywall nor the link step followed onboarding",
            ui.waitUntil { ui.exists("Close") } || ui.exists("Continue on my own"),
        )
    }

    /**
     * The chevron goes back one screen (How Plead Works → the summons explainer → the demo, back on its invitation →
     * Welcome); Welcome has no back.
     */
    @Test fun testBackReturnsToPreviousScreen() {
        ui.launch(newUserArguments)
        ui.onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
        ui.passMockTrial()
        ui.passSummonsIntro()
        ui.onboardingStep(Onboarding.howItWorks, tapping = "Continue")
        ui.waitFor(Onboarding.aiCourt)
        ui.tap("onboarding.back")
        ui.waitFor(Onboarding.howItWorks)
        ui.tap("onboarding.back")
        ui.waitFor(Onboarding.summonsIntro)
        ui.tap("onboarding.back")
        ui.waitFor(Onboarding.mockTrial)
        ui.waitFor(Onboarding.mockTrialStart)
        assertFalse("Re-entering the demo kept the finished trial", ui.exists(Onboarding.mockTrialContinue))
        ui.tap("onboarding.back")
        ui.waitFor(Onboarding.welcome)
        assertFalse("Welcome offers a back button", ui.existsAndEnabled("onboarding.back"))
    }

    /**
     * Kill the app during the trial; `AWOnboardResume YES` relaunches on the demo's intro (amendments y + ab), not
     * mid-trial and not a broken state.
     */
    @Test fun testResumesOnTheMockTrialAfterRelaunch() {
        ui.launch(newUserArguments)
        ui.onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
        ui.tap(Onboarding.mockTrialStart)

        ui.launch(newUserArguments + listOf("AWOnboardResume", "YES"))
        ui.waitFor(Onboarding.mockTrial)
        ui.waitFor(Onboarding.mockTrialStart)
        assertFalse("Relaunch started over on Welcome", ui.exists(Onboarding.welcome))
        assertFalse("Relaunch resumed mid-trial on CASE CLOSED", ui.exists(Onboarding.mockTrialContinue))
        ui.passMockTrial()
        ui.waitFor(Onboarding.summonsIntro)
    }

    /**
     * SKIP DEMO on the invitation skips the whole demo (never onboarding) and lands on the summons explainer
     * (amendment ai), whose CONTINUE reaches How Plead Works.
     */
    @Test fun testSkipDemoShowsTheSummonsExplainerThenHowPleadWorks() {
        ui.launch(newUserArguments)
        ui.onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
        ui.skipMockTrialFromInvitation()
        ui.waitFor(Onboarding.summonsIntro)
        ui.waitFor(Onboarding.summonsHeadline)
        ui.waitFor("When to call court")
        assertFalse("How Plead Works showed before the summons explainer", ui.exists(Onboarding.howItWorks))
        ui.tap(Onboarding.summonsIntroContinue)
        ui.waitForGone(Onboarding.summonsIntro)
        ui.waitFor(Onboarding.howItWorks)
    }

    /**
     * Amendment aj: START MOCK TRIAL → the shared entrance → the judge's session line and the CLAIM card → the
     * compressed full case autoplays (≈ 55 s, no taps) → CASE CLOSED ("That's a Plead trial.") → I'M READY FOR
     * COURT → the summons explainer (amendment ai).
     */
    @Test fun testStartMockTrialThenContinueGoesToSummonsExplainer() {
        ui.launch(newUserArguments)
        ui.onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
        ui.waitFor(Onboarding.mockTrial)
        ui.waitFor("See how a Plead trial works")
        ui.tap(Onboarding.mockTrialStart)
        ui.waitForGone(Onboarding.mockTrialStart)
        // The claim is read after the entrance, before any testimony (and so long before CASE CLOSED).
        ui.waitFor(ui.claimCard, Onboarding.mockClaim, timeout = 10.0)
        assertFalse("CASE CLOSED arrived before the claim", ui.exists(Onboarding.mockTrialContinue))
        // No taps: CASE CLOSED must arrive on its own, and its text is up before the CTA is used.
        ui.waitForContaining(Onboarding.mockClosedTitle, timeout = 90.0)
        ui.tap(Onboarding.mockTrialContinue, timeout = 10.0)
        ui.waitForGone(Onboarding.mockTrial)
        ui.waitFor(Onboarding.summonsIntro)
    }

    /**
     * Amendment ae: the OPENING STATEMENT help button beside Sam's opening-statement label opens its sheet (pausing
     * playback); Got it closes it and the trial still reaches CASE CLOSED's CTA.
     */
    @Test fun testMockTrialHelpSheetPausesThenGotItResumesToCaseClosed() {
        ui.launch(newUserArguments)
        ui.onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
        ui.waitFor(Onboarding.mockTrial)
        assertFalse("The help button showed on the intro", ui.exists(Onboarding.mockHelp))
        ui.tap(Onboarding.mockTrialStart)
        // Entrance (2.2 s) + the session line and claim (≈ 4.4 s), then Sam's opening offers the button.
        ui.tap(Onboarding.mockHelp, timeout = 15.0)
        ui.waitFor("What's an opening statement?")
        ui.waitFor(Onboarding.helpGotIt)
        // Paused: nothing moves behind the sheet (the next beat's exhibit never arrives).
        assertFalse(
            "Playback carried on behind the help sheet",
            ui.waitUntil(8.0) { ui.exists(ui.exhibitCard) },
        )
        ui.tap(Onboarding.helpGotIt)
        ui.waitForGone(Onboarding.helpGotIt)
        ui.tap(Onboarding.mockTrialContinue, timeout = 90.0)
        ui.waitForGone(Onboarding.mockTrial)
        ui.waitFor(Onboarding.summonsIntro)
    }

    /** Kill the app on Example Cases; `AWOnboardResume YES` relaunches there. */
    @Test fun testResumesOnLastStepAfterRelaunch() {
        ui.launch(newUserArguments)
        ui.onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
        ui.passMockTrial()
        ui.passSummonsIntro()
        ui.onboardingStep(Onboarding.howItWorks, tapping = "Continue")
        ui.onboardingStep(Onboarding.aiCourt, tapping = "Continue")
        ui.waitFor(Onboarding.examples)

        ui.launch(newUserArguments + listOf("AWOnboardResume", "YES"))
        ui.waitFor(Onboarding.examples)
        assertFalse("Relaunch started over on Welcome", ui.exists(Onboarding.welcome))
    }

    /**
     * iOS (amendment at): Court Notices → Privacy & tracking ("Step 10 of 12", the ATT note) → Widgets ("Step 11 of
     * 12") → Court Is Ready. Android (amendment az, no ATT): Court Notices → Widgets directly ("Step 10 of 11"), the
     * Privacy screen and its note never show → Court Is Ready.
     */
    @Test fun testTrackingFollowsNotificationsThenWidgets() {
        ui.launch(newUserArguments + listOf("AWOnboardStep", "notifications"))
        ui.waitFor(Onboarding.notifications)
        assertEquals("Step 9 of ${Onboarding.screenCount}", ui.progressLabel())
        ui.tap("Not now")
        ui.waitForGone(Onboarding.notifications)
        ui.waitFor(Onboarding.widgets)
        ui.assertNoPrivacyStep()
        assertFalse("The ATT note showed on Android", ui.exists("onboarding.privacy.note"))
        assertEquals("Step 10 of ${Onboarding.screenCount}", ui.progressLabel())
        ui.onboardingStep(Onboarding.widgets, tapping = "Not now")
        ui.waitFor(Onboarding.ready)
    }

    /**
     * Amendment aw: Bring in Your Partner no longer asks for the partner's name. Still "Step 8" (of 11 on Android), the
     * card reads YOU v. PARTNER until the couple links, and the invite options stay; I'LL DO THIS LATER reaches Court
     * Notices.
     */
    @Test fun testPartnerStepAsksNoName() {
        ui.launch(newUserArguments + listOf("AWOnboardStep", "partner"))
        ui.waitFor(Onboarding.partner)
        assertEquals("Step 8 of ${Onboarding.screenCount}", ui.progressLabel())
        assertFalse("The partner step still asks for a name", ui.exists("onboarding.partnerName"))
        assertFalse("The partner step still shows the name field", ui.exists("Partner's name"))
        ui.waitFor(ui.versusCard, "onboarding.versusCard")
        val card = ui.label(ui.versusCard, "onboarding.versusCard")
        assertTrue(card, card.contains("versus your partner"))
        ui.waitFor("onboarding.partner.haveCode")
        ui.waitFor("Together since")
        ui.onboardingStep(Onboarding.partner, tapping = "I'll do this later")
        ui.waitFor(Onboarding.notifications)
    }
}
