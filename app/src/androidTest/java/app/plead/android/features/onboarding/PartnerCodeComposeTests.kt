// Port of ArgueWinUITests/PartnerCodeTests.swift (amendment as): an invited partner enters their code on the
// onboarding partner step, before the paywall; LinkCoupleView's "I have a code" still joins.
//
// Android differences (docs/STATUS.md; CONTRACTS-v2 amendment az): no Privacy & tracking step after Court Notices,
// and SecureAccountView is left with Sign in with Google (Apple is hidden unless the project enables it). XCTest's
// screenshot attachment ("partner-code-linked") has no Compose-test equivalent and is not reproduced.
package app.plead.android.features.onboarding

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.support.Onboarding
import app.plead.android.support.PleadComposeTestCase
import app.plead.android.support.assertNoPrivacyStep
import app.plead.android.support.onboardingStep
import app.plead.android.support.passSummonsIntro
import app.plead.android.support.skipMockTrialFromInvitation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PartnerCodeComposeTests : PleadComposeTestCase(manualClock = true) {
    /**
     * Fresh anonymous user → partner step → I HAVE AN INVITE CODE → join Alex's paid couple → rest of onboarding →
     * "already unlocked" (never the paywall) → SecureAccountView → tabs.
     */
    @Test fun testEnterCodeOnPartnerStepEndsOnPartnerPaid() {
        launch(newUserArguments + ("AWDemoJoin" to "paid"))
        onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
        skipMockTrialFromInvitation()
        passSummonsIntro()
        onboardingStep(Onboarding.howItWorks, tapping = "Continue")
        onboardingStep(Onboarding.aiCourt, tapping = "Continue")
        onboardingStep(Onboarding.examples, tapping = "Continue")
        waitFor(Onboarding.identity)
        type("Sam", into = "onboarding.name")
        onboardingStep(Onboarding.identity, tapping = "Save my identity")

        joinOnPartnerStep()
        waitFor("onboarding.partner.linked")
        val linked = fullLabel("onboarding.partner.linked")
        assertTrue(linked, linked.contains("You're linked with Alex"))
        assertFalse("Invite Partner offered to someone who just joined", exists("Invite Partner"))
        tap("onboarding.primary")

        finishOnboardingAfterPartner()
        waitForContaining("Your partner")
        assertFalse("The paywall showed for a couple the partner already paid for", exists("Close"))
        tap("CONTINUE")
        secureAccountWithGoogle()
    }

    /** The inviter hasn't paid: after onboarding the joined partner meets the paywall (one subscription covers both). */
    @Test fun testJoinedUnpaidCoupleMeetsThePaywall() {
        launch("AWOnboardStep" to "partner", "AWDemoJoin" to "unpaid")
        joinOnPartnerStep()
        waitFor("onboarding.partner.linked")
        tap("onboarding.primary")
        finishOnboardingAfterPartner()
        waitFor("Close")
        assertFalse("An unpaid couple reached the tabs", tabsVisible())
    }

    /** Unknown code: inline error, still on code entry; BACK returns to the three options. */
    @Test fun testWrongCodeShowsAnInlineError() {
        launch("AWOnboardStep" to "partner", "AWDemoJoin" to "invalid")
        tap("onboarding.partner.haveCode")
        type("zz99zz", into = "onboarding.partner.code")
        tap("onboarding.partner.join")
        waitForContaining("doesn't match an open invite")
        tap("onboarding.partner.joinBack")
        waitFor("onboarding.partner.haveCode")
        waitFor("Invite Partner")
    }

    /** LinkCoupleView's "I have a code" (users who skipped) still joins, celebrates, and greets with "already unlocked". */
    @Test fun testLinkStepCodeStillJoins() {
        launch("AWDemoStore" to "link", "AWDemoJoin" to "paid")
        tap("I have a code")
        type("XK7P2Q", into = "Invite code")
        tap("Join")
        tap("Enter the court")
        waitForContaining("Your partner")
        tap("CONTINUE")
        assertTabs()
    }

    // MARK: Helpers

    private fun joinOnPartnerStep() {
        waitFor(Onboarding.partner)
        tap("onboarding.partner.haveCode")
        waitFor("Enter your invite code.")
        type("xk7p2q", into = "onboarding.partner.code")
        // The field upper-cases as it sanitises (the recomposition lands on the next frame).
        waitUntil { value("onboarding.partner.code") == "XK7P2Q" }
        assertEquals("XK7P2Q", value("onboarding.partner.code"))
        tap("onboarding.partner.join")
    }

    private fun finishOnboardingAfterPartner() {
        onboardingStep(Onboarding.notifications, tapping = "Not now")
        // iOS passes Privacy & tracking here (amendment at); Android goes straight to Widgets (amendment az).
        waitFor(Onboarding.widgets)
        assertNoPrivacyStep()
        onboardingStep(Onboarding.widgets, tapping = "Not now")
        onboardingStep(Onboarding.ready, tapping = Onboarding.readyFileCase)
    }
}
