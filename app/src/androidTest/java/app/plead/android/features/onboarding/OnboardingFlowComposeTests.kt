// Port of ArgueWinUITests/OnboardingFlowTests.swift: the screens of a brand-new user (the Mock Trial Demo second,
// amendment y; the summons explainer third, amendment ai), back navigation, and resume after relaunch, driven through
// the real RootScreen / OnboardingContainer on a demo AppModel built from the same launch flags.
//
// Android differences (docs/STATUS.md, wave 3b decisions; CONTRACTS-v2 amendment az): eleven counted screens ("Step N
// of 11"), no Privacy & tracking step between Court Notices and Widgets, so the tracking test asserts the step is
// skipped instead of passing it. Amendment bd: the six "About you two" / oath screens follow Welcome (not counted).
package app.plead.android.features.onboarding

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.support.Onboarding
import app.plead.android.support.PleadComposeTestCase
import app.plead.android.support.answerAbout
import app.plead.android.support.assertNoPrivacyStep
import app.plead.android.support.beginOnboarding
import app.plead.android.support.isOn
import app.plead.android.support.pressOath
import app.plead.android.support.startQuestions
import app.plead.android.support.takeOath
import app.plead.android.support.onboardingStep
import app.plead.android.support.passMockTrial
import app.plead.android.support.passSummonsIntro
import app.plead.android.support.progressLabel
import app.plead.android.support.skipMockTrialFromInvitation
import app.plead.android.support.walkOnboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnboardingFlowComposeTests : PleadComposeTestCase(manualClock = true) {
    /** signedOut → Welcome → Mock Trial Demo → every screen via its CTA → the gate. */
    @Test fun testWalksAllScreens() {
        launch(newUserArguments)
        walkOnboarding(name = "Arif")
        // Past the last screen the gate takes over (link step or paywall), never the tabs: nothing is paid yet.
        assertFalse("An unpaid new user reached the tabs", tabsAppear(timeoutMs = 2_000))
        assertTrue(
            "Neither the paywall nor the link step followed onboarding",
            waitUntil { exists("Close") } || exists("Continue on my own"),
        )
    }

    /**
     * The chevron goes back one screen (How Plead Works → the summons explainer → the demo, back on its invitation →
     * Welcome); Welcome has no back.
     */
    @Test fun testBackReturnsToPreviousScreen() {
        launch(newUserArguments)
        beginOnboarding()
        passMockTrial()
        passSummonsIntro()
        onboardingStep(Onboarding.howItWorks, tapping = "Continue")
        waitFor(Onboarding.aiCourt)
        tap("onboarding.back")
        waitFor(Onboarding.howItWorks)
        tap("onboarding.back")
        waitFor(Onboarding.summonsIntro)
        tap("onboarding.back")
        waitFor(Onboarding.mockTrial)
        waitFor(Onboarding.mockTrialStart)
        assertFalse("Re-entering the demo kept the finished trial", exists(Onboarding.mockTrialContinue))
        // Amendment bd: back from the demo is the oath, then the five questions, then Welcome.
        tap("onboarding.back")
        waitFor(tag(Onboarding.oath), Onboarding.oath)
        for ((question, _) in Onboarding.aboutFirstOptions.reversed()) {
            tap("onboarding.back")
            waitFor(tag("onboarding.about.$question"), question)
        }
        // Amendment bg: then the intro, then Welcome.
        tap("onboarding.back")
        waitFor(tag(Onboarding.aboutIntro), Onboarding.aboutIntro)
        tap("onboarding.back")
        waitFor(Onboarding.welcome)
        assertFalse("Welcome offers a back button", existsAndEnabled("onboarding.back"))
    }

    /**
     * Kill the app during the trial; `AWOnboardResume YES` relaunches on the demo's intro (amendments y + ab), not
     * mid-trial and not a broken state.
     */
    @Test fun testResumesOnTheMockTrialAfterRelaunch() {
        launch(newUserArguments)
        beginOnboarding()
        tap(Onboarding.mockTrialStart)

        launch(newUserArguments + ("AWOnboardResume" to "YES"))
        waitFor(Onboarding.mockTrial)
        waitFor(Onboarding.mockTrialStart)
        assertFalse("Relaunch started over on Welcome", exists(Onboarding.welcome))
        assertFalse("Relaunch resumed mid-trial on CASE CLOSED", exists(Onboarding.mockTrialContinue))
        passMockTrial()
        waitFor(Onboarding.summonsIntro)
    }

    /**
     * SKIP DEMO on the invitation skips the whole demo (never onboarding) and lands on the summons explainer
     * (amendment ai), whose CONTINUE reaches How Plead Works.
     */
    @Test fun testSkipDemoShowsTheSummonsExplainerThenHowPleadWorks() {
        launch(newUserArguments)
        beginOnboarding()
        skipMockTrialFromInvitation()
        waitFor(Onboarding.summonsIntro)
        waitFor(Onboarding.summonsHeadline)
        waitFor("When to call court")
        assertFalse("How Plead Works showed before the summons explainer", exists(Onboarding.howItWorks))
        tap(Onboarding.summonsIntroContinue)
        waitForGone(Onboarding.summonsIntro)
        waitFor(Onboarding.howItWorks)
    }

    /**
     * Amendment aj: START MOCK TRIAL → the shared entrance → the judge's session line and the CLAIM card → the
     * compressed full case autoplays (≈ 55 s, no taps) → CASE CLOSED ("That's a Plead trial.") → I'M READY FOR
     * COURT → the summons explainer (amendment ai).
     */
    @Test fun testStartMockTrialThenContinueGoesToSummonsExplainer() {
        launch(newUserArguments)
        beginOnboarding()
        waitFor(Onboarding.mockTrial)
        waitFor("See how a Plead trial works")
        tap(Onboarding.mockTrialStart)
        waitForGone(Onboarding.mockTrialStart)
        // The claim is read after the entrance, before any testimony (and so long before CASE CLOSED).
        waitFor(Onboarding.mockClaim, timeoutMs = 10_000)
        assertFalse("CASE CLOSED arrived before the claim", exists(Onboarding.mockTrialContinue))
        // No taps: CASE CLOSED must arrive on its own, and its text is up before the CTA is used.
        waitForContaining(Onboarding.mockClosedTitle, timeoutMs = 90_000)
        tap(Onboarding.mockTrialContinue, timeoutMs = 10_000)
        waitForGone(Onboarding.mockTrial)
        waitFor(Onboarding.summonsIntro)
    }

    /**
     * Amendment ae: the OPENING STATEMENT help button beside Sam's opening-statement label opens its sheet (pausing
     * playback); Got it closes it and the trial still reaches CASE CLOSED's CTA.
     */
    @Test fun testMockTrialHelpSheetPausesThenGotItResumesToCaseClosed() {
        launch(newUserArguments)
        beginOnboarding()
        waitFor(Onboarding.mockTrial)
        assertFalse("The help button showed on the intro", exists(Onboarding.mockHelp))
        tap(Onboarding.mockTrialStart)
        // Entrance (2.2 s) + the session line and claim (≈ 4.4 s), then Sam's opening offers the button.
        tap(Onboarding.mockHelp, timeoutMs = 15_000)
        waitFor("What's an opening statement?")
        waitFor(Onboarding.helpGotIt)
        // Paused: nothing moves behind the sheet (the next beat's exhibit never arrives).
        assertFalse(
            "Playback carried on behind the help sheet",
            waitUntil(8_000) { exists(Onboarding.mockExhibit) },
        )
        tap(Onboarding.helpGotIt)
        waitForGone(Onboarding.helpGotIt)
        tap(Onboarding.mockTrialContinue, timeoutMs = 90_000)
        waitForGone(Onboarding.mockTrial)
        waitFor(Onboarding.summonsIntro)
    }

    /** Kill the app on Example Cases; `AWOnboardResume YES` relaunches there. */
    @Test fun testResumesOnLastStepAfterRelaunch() {
        launch(newUserArguments)
        beginOnboarding()
        passMockTrial()
        passSummonsIntro()
        onboardingStep(Onboarding.howItWorks, tapping = "Continue")
        onboardingStep(Onboarding.aiCourt, tapping = "Continue")
        waitFor(Onboarding.examples)

        launch(newUserArguments + ("AWOnboardResume" to "YES"))
        waitFor(Onboarding.examples)
        assertFalse("Relaunch started over on Welcome", exists(Onboarding.welcome))
    }

    /**
     * iOS (amendment at): Court Notices → Privacy & tracking ("Step 10 of 12", the ATT note) → Widgets ("Step 11 of
     * 12") → Court Is Ready. Android (amendment az, no ATT): Court Notices → Widgets directly ("Step 10 of 11"), the
     * Privacy screen and its note never show → Court Is Ready.
     */
    @Test fun testTrackingFollowsNotificationsThenWidgets() {
        launch(newUserArguments + ("AWOnboardStep" to "notifications"))
        waitFor(Onboarding.notifications)
        assertEquals("Step 9 of ${Onboarding.screenCount}", progressLabel())
        tap("Not now")
        waitForGone(Onboarding.notifications)
        waitFor(Onboarding.widgets)
        assertNoPrivacyStep()
        assertFalse("The ATT note showed on Android", exists("onboarding.privacy.note"))
        assertEquals("Step 10 of ${Onboarding.screenCount}", progressLabel())
        onboardingStep(Onboarding.widgets, tapping = "Not now")
        waitFor(Onboarding.ready)
    }

    /**
     * Amendment aw: Bring in Your Partner no longer asks for the partner's name. Still "Step 8" (of 11 on Android), the
     * card reads YOU v. PARTNER until the couple links, and the invite options stay; I'LL DO THIS LATER reaches Court
     * Notices.
     */
    @Test fun testPartnerStepAsksNoName() {
        launch(newUserArguments + ("AWOnboardStep" to "partner"))
        waitFor(Onboarding.partner)
        assertEquals("Step 8 of ${Onboarding.screenCount}", progressLabel())
        assertFalse("The partner step still asks for a name", exists("onboarding.partnerName"))
        assertFalse("The partner step still shows the name field", exists("Partner's name"))
        waitFor(Onboarding.versusCard)
        val card = fullLabel(Onboarding.versusCard)
        assertTrue(card, card.contains("versus your partner"))
        waitFor("onboarding.partner.haveCode")
        waitFor("Together since")
        onboardingStep(Onboarding.partner, tapping = "I'll do this later")
        waitFor(Onboarding.notifications)
    }

    /**
     * Amendment bd: six screens between Welcome and the demo, none skippable. No Skip; CONTINUE disabled until an
     * answer; choose-all toggles; back keeps answers; a short press on the oath does nothing, the 2 s hold advances to
     * the demo; the court rail is replaced by the six-segment rail.
     */
    @Test fun testAboutYouTwoIsNotSkippableAndKeepsAnswers() {
        launch(newUserArguments)
        onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
        // Amendment bg: the intro first: no Skip, no six-segment rail, START QUESTIONS the only way on.
        waitFor(tag(Onboarding.aboutIntro), Onboarding.aboutIntro)
        waitFor("Tell the court about you two.")
        waitFor("5 quick questions")
        waitFor("1 courtroom oath")
        assertFalse("A Skip control is offered on the intro", exists("Skip"))
        assertFalse("The six-segment rail shows on the intro", exists(tag(Onboarding.aboutProgress)))
        startQuestions()
        waitFor(tag("onboarding.about.frequency"), "frequency")
        waitFor("How often do little things turn into arguments?")
        assertEquals("Question 1 of 6", stateDescription(node(tag(Onboarding.aboutProgress))))
        assertFalse("A Skip control is offered", exists("Skip"))
        assertFalse("A Skip control is offered", exists("SKIP"))
        assertFalse("CONTINUE enabled with nothing chosen", existsAndEnabled(Onboarding.aboutContinue))
        tap("onboarding.about.frequency.few_times_week")
        assertTrue(waitUntil { isOn("onboarding.about.frequency.few_times_week") })
        tap(Onboarding.aboutContinue)
        waitForGone(tag("onboarding.about.frequency"), "frequency")

        waitFor(tag("onboarding.about.topics"), "topics")
        assertTrue(waitUntil { stateDescription(node(tag(Onboarding.aboutProgress))) == "Question 2 of 6" })
        assertFalse(existsAndEnabled(Onboarding.aboutContinue))
        tap("onboarding.about.topics.chores")
        tap("onboarding.about.topics.food")
        tap("onboarding.about.topics.chores")
        assertTrue(waitUntil { !isOn("onboarding.about.topics.chores") })
        assertTrue(isOn("onboarding.about.topics.food"))
        assertTrue(existsAndEnabled(Onboarding.aboutContinue))
        // Back keeps the first answer.
        tap("onboarding.back")
        waitFor(tag("onboarding.about.frequency"), "frequency")
        assertTrue("Back lost the answer", isOn("onboarding.about.frequency.few_times_week"))
        assertTrue(existsAndEnabled(Onboarding.aboutContinue))
        tap(Onboarding.aboutContinue)
        waitForGone(tag("onboarding.about.frequency"), "frequency")
        waitFor(tag("onboarding.about.topics"), "topics")
        assertTrue("Forward again lost the answer", isOn("onboarding.about.topics.food"))
        tap(Onboarding.aboutContinue)
        waitForGone(tag("onboarding.about.topics"), "topics")
        for ((question, option) in Onboarding.aboutFirstOptions.drop(2)) answerAbout(question, option)

        waitFor(tag(Onboarding.oath), Onboarding.oath)
        waitFor("Do you swear to hear them out?")
        assertTrue(waitUntil { stateDescription(node(tag(Onboarding.aboutProgress))) == "Question 6 of 6" })
        pressOath(600)
        assertFalse("A short press took the oath", waitUntil(2_000) { exists(Onboarding.mockTrial) })
        assertTrue(exists(tag(Onboarding.oath)))
        takeOath()
        waitFor(Onboarding.mockTrial)
        assertEquals("Step 2 of ${Onboarding.screenCount}", progressLabel())
    }

    /** Killed on the third question: relaunch resumes there with the earlier answers kept. */
    @Test fun testAboutYouTwoResumesAfterRelaunch() {
        launch(newUserArguments)
        onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
        startQuestions()
        for ((question, option) in Onboarding.aboutFirstOptions.take(2)) answerAbout(question, option)
        waitFor(tag("onboarding.about.outcome"), "outcome")
        launch(newUserArguments + ("AWOnboardResume" to "YES"))
        waitFor(tag("onboarding.about.outcome"), "outcome")
        assertFalse(exists(Onboarding.welcome))
        tap("onboarding.back")
        waitFor(tag("onboarding.about.topics"), "topics")
        assertTrue("Relaunch lost the answer", isOn("onboarding.about.topics.chores"))
    }

    /** TalkBack / Switch Access: activating the oath button (an accessibility click, no hold) takes the oath. */
    @Test fun testOathCompletesOnAccessibilityActivation() {
        launch(newUserArguments + ("AWOnboardStep" to "oath"))
        waitFor(tag(Onboarding.oath), Onboarding.oath)
        waitFor(tag(Onboarding.oathHold), Onboarding.oathHold)
        first(tag(Onboarding.oathHold)).performSemanticsAction(SemanticsActions.OnClick)
        waitForGone(tag(Onboarding.oath), Onboarding.oath)
        waitFor(Onboarding.mockTrial)
    }
}
