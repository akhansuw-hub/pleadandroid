// Port of ArgueWinTests/ExitOfferTests.swift.
package app.plead.android.features.paywall

import app.plead.android.services.ExitOfferPrices
import app.plead.android.services.PurchasesService
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Exit offer (docs/paywall-brief/exit/BRIEF.md, CONTRACTS-v2 amendments f, aa, ao). */
class ExitOfferTests {
    private fun offer(standard: String, offer: String, sLabel: String = "£49.99", oLabel: String = "£24.99"): ExitOfferState? =
        ExitOfferState(standardPrice = BigDecimal(standard), standardLocalized = sLabel, offerPrice = BigDecimal(offer), offerLocalized = oLabel)

    // MARK: PaywallStage transition on X

    @Test fun eligibleShowsExitOfferEveryTime() {
        // Amendment aa: no frequency cap; every X on the standard paywall shows the offer while eligible.
        repeat(3) {
            assertEquals(PaywallCloseDecision.showExitOffer, PaywallCloseDecision.onCloseStandard(offer = offer("49.99", "24.99")))
        }
    }

    @Test fun ineligibleDismisses() {
        assertEquals(PaywallCloseDecision.dismiss, PaywallCloseDecision.onCloseStandard(offer = null))
    }

    // MARK: Percentage is derived, never assumed

    @Test fun halfPriceRoundsToFifty() {
        assertEquals(50, ExitOfferMath.discountPercent(standard = BigDecimal("49.99"), offer = BigDecimal("24.99")))
        assertEquals(50, ExitOfferMath.discountPercent(standard = 59.99, offer = 29.99))
        assertEquals(50, ExitOfferMath.discountPercent(standard = 5000.0, offer = 2500.0))   // e.g. JPY
        assertEquals("50% OFF", offer("49.99", "24.99")?.discountLabel)
        assertEquals("CLAIM 50% OFF", offer("49.99", "24.99")?.ctaTitle)
    }

    @Test fun otherDiscountsShowTheRealPercentage() {
        assertEquals(40, ExitOfferMath.discountPercent(standard = BigDecimal("49.99"), offer = BigDecimal("29.99")))
        val o = offer("49.99", "29.99", "£49.99", "£29.99")
        assertEquals("40% OFF", o?.discountLabel)
        assertEquals("CLAIM 40% OFF", o?.ctaTitle)
        assertEquals("Before you go, take 40% off Plead.", o?.subheadline)
        // 44.5% rounds half-up to 45, never to a flattering 50.
        assertEquals(45, ExitOfferMath.discountPercent(standard = BigDecimal(100), offer = BigDecimal("55.5")))
    }

    @Test fun noRealDiscountMeansNoOffer() {
        assertNull(ExitOfferMath.discountPercent(standard = 49.99, offer = 49.99))
        assertNull(ExitOfferMath.discountPercent(standard = 49.99, offer = 59.99))
        assertNull(ExitOfferMath.discountPercent(standard = 0.0, offer = 0.0))
        assertNull(offer("49.99", "49.99"))
    }

    // MARK: Renewal copy

    @Test fun renewalCopyUsesLocalizedStrings() {
        // Amendment ao: the offer product renews at its own (lower) standard price every year.
        assertEquals("£24.99/year. Renews automatically. Cancel anytime.", ExitOfferMath.renewalCopy(offer = "£24.99"))
        assertEquals("$29.99/year. Renews automatically. Cancel anytime.", offer("59.99", "29.99", "$59.99", "$29.99")?.renewalCopy)
    }

    @Test fun copyNeverClaimsAFirstYearOrIntro() {
        val o = offer("49.99", "24.99")
        assertNotNull("no offer", o)
        o!!
        for (s in listOf(o.subheadline, o.renewalCopy, o.discountLabel, o.ctaTitle)) {
            assertFalse(s, s.contains("first year", ignoreCase = true))
            assertFalse(s, s.contains("then", ignoreCase = true))
            assertFalse(s, s.contains("trial", ignoreCase = true))
        }
        assertEquals("Before you go, take 50% off Plead.", o.subheadline)
        assertEquals("£49.99", o.standardAnnualPrice)
        assertEquals("£24.99", o.offerAnnualPrice)
    }

    @Test fun productIds() {
        assertEquals("plead.discount", PurchasesService.annualOfferID)
        assertEquals("exit_offer", PurchasesService.exitOfferingID)
    }

    // Android: the billing layer hands over micros (exact) + localized strings.
    @Test fun buildsFromTheBillingLayersMicros() {
        val o = ExitOfferState.from(ExitOfferPrices(49_990_000, "£49.99", 24_990_000, "£24.99"))
        assertEquals(50, o?.discountPercent)
        assertEquals("£24.99/year. Renews automatically. Cancel anytime.", o?.renewalCopy)
        assertNull(ExitOfferState.from(ExitOfferPrices(49_990_000, "£49.99", 49_990_000, "£49.99")))
    }
}
