// Port of `CourtroomTieTests` (ArgueWinTests/TieAndDocketTests.swift) and `SettlementCourtroomLogicTests`
// (ArgueWinTests/SettlementDocketTests.swift): the courtroom halves of amendments l and n (pure CourtroomLogic).
package app.plead.android.courtroom

import app.plead.android.models.TrialPhase
import app.plead.android.services.SettlementRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourtroomTieTests {
    @Test fun dockOffersServeOrDeclineToBoth() {
        assertEquals(JudgementDockMode.awaitingCourt, CourtroomLogic.judgementDockMode(CourtFixtures.judgementTiePending))
        assertEquals(JudgementDockMode.courtResolution, CourtroomLogic.judgementDockMode(CourtFixtures.judgementTieDelivered))
        assertEquals(JudgementDockMode.courtResolution, CourtroomLogic.judgementDockMode(CourtFixtures.asPartner(CourtFixtures.judgementTieDelivered)))
        assertEquals(JudgementDockMode.served, CourtroomLogic.judgementDockMode(CourtFixtures.judgementTieServed))
    }

    @Test fun verdictStepCopyIsTheSameForBoth() {
        val copy = StepCopy("The court could not separate you.", "The court has chosen a resolution.")
        assertEquals(copy, CourtroomLogic.judgementStepCopy(CourtFixtures.judgementTieDelivered))
        assertEquals(copy, CourtroomLogic.judgementStepCopy(CourtFixtures.asPartner(CourtFixtures.judgementTieDelivered)))
    }

    @Test fun deliveryReadsTheTurnElseTheTieTemplate() {
        assertEquals(CourtFixtures.tieDeliveryTurn.body, CourtroomLogic.deliveryText(CourtFixtures.judgementTieDelivered))
        assertEquals(
            "The court could not separate you. It has chosen a resolution for you both. Both parties are hereby ordered to meet at 22° and share the fluffy socks on alternate nights. The court considers this matter settled.",
            CourtroomLogic.deliveryText(CourtFixtures.judgementTieTemplate),
        )
    }
}

class SettlementCourtroomLogicTests {
    @Test fun dockModesAndCopy() {
        assertEquals(SettlementDockMode.pending(round = 2, awaitingMe = true), CourtroomLogic.settlementDockMode(CourtFixtures.settlementPending))
        assertEquals(SettlementDockMode.pending(round = 1, awaitingMe = false), CourtroomLogic.settlementDockMode(CourtFixtures.settlementPendingMine))
        assertEquals(SettlementDockMode.settled, CourtroomLogic.settlementDockMode(CourtFixtures.settled))
        assertNull(CourtroomLogic.settlementDockMode(CourtFixtures.presenting))
        assertNull(CourtroomLogic.countdownTarget(CourtFixtures.settlementPending))
        assertEquals("The parties are talking. The court will wait.", CourtroomLogic.settlementJudgeTurn(CourtFixtures.settlementPending)?.body)
        assertEquals("The parties have spared the court the trouble. Miracles do happen.", CourtroomLogic.settlementJudgeTurn(CourtFixtures.settled)?.body)
        assertEquals("Round 2 of 3", CourtroomLogic.settlementRoundLine(2))
        assertEquals("Settlement pending · the court will wait", CourtroomLogic.settlementPendingTitle)
    }

    @Test fun proposeHiddenFromClosingsAndWhenPending() {
        assertTrue(CourtroomLogic.showsProposeSettlement(CourtFixtures.settleable))
        val closing = CourtFixtures.closing.copy(canProposeSettlement = true)
        assertFalse(CourtroomLogic.showsProposeSettlement(closing))
        val pending = CourtFixtures.settlementPending.copy(canProposeSettlement = true)
        assertFalse(CourtroomLogic.showsProposeSettlement(pending))
        // Mirrors the store's rule for the cut-off.
        for (phase in TrialPhase.entries) {
            assertEquals("$phase", SettlementRules.phaseAllowsSettlement(phase), CourtroomLogic.phaseAllowsSettlement(phase))
        }
    }
}
