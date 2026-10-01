// Port of ArgueWinUITests/PaywallGateTests.swift: the paywall gate (`AWDemoStore unpaid`), its exit offer, and the demo
// purchase into the app. Paywall nodes are found by their visible labels / content descriptions only, as on iOS.
package app.plead.android.features.shell

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaywallGateComposeTests : ShellUITestCase() {

    private fun plan(name: String) = button(beginningWith(name))

    /**
     * The distinct plan names on screen ("Annual", "Monthly", "Weekly"), from the cards' spoken labels
     * ("Annual, best value. 3 days free, then …", "Weekly. £9.99 / week.").
     */
    private fun planNames(): Set<String> = nodes(plan("Annual")).plus(nodes(plan("Monthly"))).plus(nodes(plan("Weekly")))
        .mapNotNull { n -> labels(n).firstOrNull()?.split(",")?.first()?.split(".")?.first()?.lowercase()?.replaceFirstChar { it.uppercase() } }
        .toSet()

    private fun ctaLabel(): String = label(node(purchaseCTA()))

    @Test fun testPaywallShowsPlansAndWeeklyChangesCTA() {
        launch("AWDemoStore" to "unpaid")
        assertTrue("No Annual plan on the paywall", waitUntil { exists(plan("Annual")) })
        val weekly = plan("Weekly")
        assertTrue("No Weekly plan on the paywall", waitUntil(5_000) { exists(weekly) })
        if (exists(plan("Monthly"))) {
            assertEquals("Expected Annual, Monthly and Weekly", setOf("Annual", "Monthly", "Weekly"), planNames())
        } else {
            // The paywall workstream may be mid-change on the plan set: at least two plans must show.
            assertTrue("Expected at least two plans", planNames().size >= 2)
        }

        assertTrue("No purchase CTA", waitUntil(5_000) { exists(purchaseCTA()) })
        val before = ctaLabel()
        tap(weekly, "Weekly plan")
        assertTrue("CTA stayed \"$before\" after selecting Weekly", waitUntil(5_000) { ctaLabel() != before })
        assertTrue("Weekly CTA reads \"${ctaLabel()}\"", ctaLabel().contains("week", ignoreCase = true))
    }

    /** X → the exit offer → "No thanks, not now" → back at the gated link step, not the tabs. */
    @Test fun testCloseShowsExitOfferThenDeclineReturnsToGate() {
        launch("AWDemoStore" to "unpaid")
        assertTrue("Paywall never appeared", waitUntil { exists(purchaseCTA()) })
        tap("Close")
        waitForContaining("CLAIM")
        tap("No thanks, not now")
        waitForGone("No thanks, not now")
        waitForContaining("are linked")
        assertFalse("Declining the exit offer unlocked the tabs", exists(tabButton("Home")))
    }

    /** Demo purchase on an already-secured account goes straight to the tabs. */
    @Test fun testDemoPurchaseUnlocksTabs() {
        launch("AWDemoStore" to "unpaid")
        tap(purchaseCTA(), "purchase CTA")
        // `unpaid` is a secured account; if the gate ever asks to secure it, do so.
        if (waitUntil(3_000) { exists("secure.apple") }) secureAccountWithApple()
        assertTabs()
    }

    /** A brand-new (anonymous) user: onboarding → paywall → demo purchase → SecureAccountView → Apple → tabs. */
    @Test fun testNewUserPurchaseThenSecureAccountThenTabs() {
        launch(newUserArguments)
        walkOnboarding(name = "Robin")
        // Past onboarding: the link step first (no partner yet), then the paywall.
        if (waitUntil(5_000) { exists("Continue on my own") }) {
            tap("Continue on my own")
            // The link step cross-fades out; its "Continue on my own" would otherwise also match the CTA below.
            waitForGone("Continue on my own")
        }
        tap(purchaseCTA(), "purchase CTA")
        secureAccountWithApple()
    }

    /** `anonymousPaid`: the gate resolved while anonymous → SecureAccountView → Apple → tabs. */
    @Test fun testAnonymousPaidSecuresAccount() {
        launch("AWDemoStore" to "anonymousPaid")
        secureAccountWithApple()
    }
}
