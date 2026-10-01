// Port of ArgueWinUITests/PartnerCodeTests.swift (amendment as): an invited partner enters their code on the
// onboarding partner step, before the paywall; LinkCoupleView's "I have a code" still joins.
//
// Android differences (docs/STATUS.md; CONTRACTS-v2 amendment az): no Privacy & tracking step after Court Notices,
// and SecureAccountView is left with Sign in with Google (Apple is hidden unless the project enables it). XCTest's
// screenshot attachment ("partner-code-linked") has no Compose-test equivalent and is not reproduced.
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
class PartnerCodeComposeTests {
    @get:Rule val rule = createComposeRule()
    private val ui = OnboardingTestSupport(rule)

    @After fun tearDown() = ui.tearDown()

    /**
     * Fresh anonymous user → partner step → I HAVE AN INVITE CODE → join Alex's paid couple → rest of onboarding →
     * "already unlocked" (never the paywall) → SecureAccountView → tabs.
     */
    @Test fun testEnterCodeOnPartnerStepEndsOnPartnerPaid() {
        ui.launch(newUserArguments + listOf("AWDemoJoin", "paid"))
        ui.onboardingStep(Onboarding.welcome, tapping = "onboarding.begin")
        ui.skipMockTrialFromInvitation()
        ui.passSummonsIntro()
        ui.onboardingStep(Onboarding.howItWorks, tapping = "Continue")
        ui.onboardingStep(Onboarding.aiCourt, tapping = "Continue")
        ui.onboardingStep(Onboarding.examples, tapping = "Continue")
        ui.waitFor(Onboarding.identity)
        ui.type("Sam", into = "onboarding.name")
        ui.onboardingStep(Onboarding.identity, tapping = "Save my identity")

        joinOnPartnerStep()
        ui.waitFor("onboarding.partner.linked")
        val linked = ui.label("onboarding.partner.linked")
        assertTrue(linked, linked.contains("You're linked with Alex"))
        assertFalse("Invite Partner offered to someone who just joined", ui.exists("Invite Partner"))
        ui.tap("onboarding.primary")

        finishOnboardingAfterPartner()
        ui.waitForContaining("Your partner")
        assertFalse("The paywall showed for a couple the partner already paid for", ui.exists("Close"))
        ui.tap("CONTINUE")
        ui.secureAccountWithGoogle()
    }

    /** The inviter hasn't paid: after onboarding the joined partner meets the paywall (one subscription covers both). */
    @Test fun testJoinedUnpaidCoupleMeetsThePaywall() {
        ui.launch("AWOnboardStep", "partner", "AWDemoJoin", "unpaid")
        joinOnPartnerStep()
        ui.waitFor("onboarding.partner.linked")
        ui.tap("onboarding.primary")
        finishOnboardingAfterPartner()
        ui.waitFor("Close")
        assertFalse("An unpaid couple reached the tabs", ui.tabsVisible())
    }

    /** Unknown code: inline error, still on code entry; BACK returns to the three options. */
    @Test fun testWrongCodeShowsAnInlineError() {
        ui.launch("AWOnboardStep", "partner", "AWDemoJoin", "invalid")
        ui.tap("onboarding.partner.haveCode")
        ui.type("zz99zz", into = "onboarding.partner.code")
        ui.tap("onboarding.partner.join")
        ui.waitForContaining("doesn't match an open invite")
        ui.tap("onboarding.partner.joinBack")
        ui.waitFor("onboarding.partner.haveCode")
        ui.waitFor("Invite Partner")
    }

    /** LinkCoupleView's "I have a code" (users who skipped) still joins, celebrates, and greets with "already unlocked". */
    @Test fun testLinkStepCodeStillJoins() {
        ui.launch("AWDemoStore", "link", "AWDemoJoin", "paid")
        ui.tap("I have a code")
        ui.type("XK7P2Q", into = "Invite code")
        ui.tap("Join")
        ui.tap("Enter the court")
        ui.waitForContaining("Your partner")
        ui.tap("CONTINUE")
        ui.assertTabs()
    }

    // MARK: Helpers

    private fun joinOnPartnerStep() {
        ui.waitFor(Onboarding.partner)
        ui.tap("onboarding.partner.haveCode")
        ui.waitFor("Enter your invite code.")
        ui.type("xk7p2q", into = "onboarding.partner.code")
        // The field upper-cases as it sanitises (the recomposition lands on the next frame).
        ui.waitUntil { ui.value("onboarding.partner.code") == "XK7P2Q" }
        assertEquals("XK7P2Q", ui.value("onboarding.partner.code"))
        ui.tap("onboarding.partner.join")
    }

    private fun finishOnboardingAfterPartner() {
        ui.onboardingStep(Onboarding.notifications, tapping = "Not now")
        // iOS passes Privacy & tracking here (amendment at); Android goes straight to Widgets (amendment az).
        ui.waitFor(Onboarding.widgets)
        ui.assertNoPrivacyStep()
        ui.onboardingStep(Onboarding.widgets, tapping = "Not now")
        ui.onboardingStep(Onboarding.ready, tapping = Onboarding.readyFileCase)
    }
}
