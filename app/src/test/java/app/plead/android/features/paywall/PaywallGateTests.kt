// Port of ArgueWinTests/PaywallGateTests.swift (`PaywallCopyTests`). The same file's `AppGateTests` and
// `PaywallModelTests` were ported with their subjects in wave 2a (`app/AppGateTests.kt`, `app/AppModelTests.kt`).
package app.plead.android.features.paywall

import app.plead.android.services.PaywallProduct
import app.plead.android.services.PaywallProducts
import app.plead.android.services.PurchasesService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** BRIEF §7: CTA + disclosure per plan and trial eligibility. Prices come from the store. */
class PaywallCopyTests {
    private val weekly = PaywallProduct(id = PurchasesService.weeklyID, price = "£9.99", period = PaywallProduct.Period.week, freeTrialDays = null)
    private val annual = PaywallProduct(id = PurchasesService.annualID, price = "£59.99", period = PaywallProduct.Period.year, freeTrialDays = 3)

    @Test fun annualTrialEligible() {
        val s = PaywallCTAState.make(PurchasesService.Plan.annual, PaywallProducts(weekly = weekly, annual = annual, annualTrialEligible = true))
        assertEquals(PaywallCTAState.annualTrial(price = "£59.99/year", trialDays = 3), s)
        assertEquals("START 3-DAY FREE TRIAL", s.title)
        assertEquals("Free for 3 days, then £59.99/year. Cancel anytime.", s.disclosure)
    }

    @Test fun annualNotEligible() {
        val s = PaywallCTAState.make(PurchasesService.Plan.annual, PaywallProducts(weekly = weekly, annual = annual, annualTrialEligible = false))
        assertEquals("CONTINUE WITH ANNUAL", s.title)
        assertEquals("£59.99/year. Cancel anytime.", s.disclosure)
    }

    /** Eligible per the store but the product has no free-trial offer configured: never claim a trial. */
    @Test fun noTrialOfferMeansNoTrialCopy() {
        val plain = annual.copy(freeTrialDays = null)
        val s = PaywallCTAState.make(PurchasesService.Plan.annual, PaywallProducts(weekly = weekly, annual = plain, annualTrialEligible = true))
        assertEquals("CONTINUE WITH ANNUAL", s.title)
    }

    @Test fun weeklySelected() {
        val s = PaywallCTAState.make(PurchasesService.Plan.weekly, PaywallProducts(weekly = weekly, annual = annual, annualTrialEligible = true))
        assertEquals("CONTINUE — £9.99/WEEK", s.title)
        assertEquals("£9.99/week. Cancel anytime.", s.disclosure)
        assertFalse(s.disclosure.contains("Free"))
    }

    @Test fun missingProductsAreNotPurchasable() {
        assertEquals(PaywallCTAState.unavailable, PaywallCTAState.make(PurchasesService.Plan.annual, PaywallProducts.empty))
        assertFalse(PaywallCTAState.make(PurchasesService.Plan.weekly, PaywallProducts.empty).isPurchasable)
    }

    @Test fun productIdsAndEntitlement() {
        assertEquals("plead.weekly", PurchasesService.weeklyID)
        assertEquals("plead.yearly", PurchasesService.annualID)
        assertEquals("premium", PurchasesService.entitlementID)
    }
}

/** The gate container's pure decisions (Android: `PaywallGateView.initialStage` reads the demo flag). */
class PaywallGateViewTests {
    @Test fun startsOnTheStandardPaywallOutsideTheDemo() {
        app.plead.android.app.LaunchArguments.set(emptyMap())
        assertEquals(PaywallStage.standard, PaywallGateView.initialStage)
    }
}
