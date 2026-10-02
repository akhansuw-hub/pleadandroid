// Port of ArgueWinUITests/SettlementFlowTests.swift: Settle Outside Court, answering an offer and proposing one from
// the summons.
//
// The iOS identifiers `settlement.accept`, `settlement.suggestion` and `settlement.propose` are the Android test tags
// on the same elements. Sheets are Material bottom sheets (docs/STATUS.md "Not 1:1").
package app.plead.android.features.court

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.support.PleadComposeTestCase
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettlementFlowComposeTests : PleadComposeTestCase() {
    /** `settlementOffer`: Alex's offer on #014 opens its response sheet on launch → ACCEPT → CASE SETTLED. */
    @Test fun testAcceptOfferSettlesOutOfCourt() {
        launch("AWDemoStore" to "settlementOffer")
        tap(tag("settlement.accept"), "settlement.accept", timeoutMs = 15_000)
        waitFor("settlement.acceptedTitle")
        waitFor("CASE SETTLED")
        waitForContaining("Out of court")
    }

    /**
     * The #015 summons → Settle Outside Court → a suggestion → PROPOSE SETTLEMENT → "Offer sent · waiting…".
     * Normal demo speed: the simulated partner answers ~4 s after the offer, leaving the waiting state visible.
     */
    @Test fun testProposeFromSummonsShowsWaitingState() {
        launch("AWSheet" to "summons", "AWDemoSpeed" to "normal")
        waitFor("YOU HAVE BEEN SUMMONED")
        tap("Settle Outside Court")
        tap(tag("settlement.suggestion"), "first settlement suggestion", timeoutMs = 15_000)
        tap(tag("settlement.propose"), "settlement.propose")
        assertTrue("The proposer's waiting state never showed", waitForExistence(beginningWith("Offer sent"), 4_000))
        waitFor("Withdraw offer", timeoutMs = 2_000)
    }
}
