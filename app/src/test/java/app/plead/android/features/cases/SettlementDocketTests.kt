// Port of ArgueWinTests/SettlementDocketTests.swift (docket rows, docket sections, Home and the record; the
// `SettlementCourtroomLogicTests` suite tests CourtroomLogic, wave 3a).
//
// CONTRACTS-v2 amendment n (Settle Outside Court) on the docket, Home and the record:
// SETTLED ribbon + SETTLED OUT OF COURT stamp, the pending chip and routing, Home's card selection and the
// negotiation-history labels. Home's fulfilment line is `homeLabel(SettlementFulfilment.of(...))`,
// as in Swift.
package app.plead.android.features.cases

import app.plead.android.designsystem.CaseFileDocket
import app.plead.android.designsystem.CaseFileStatus
import app.plead.android.features.home.ActiveCaseCard
import app.plead.android.models.CaseStatus
import app.plead.android.models.SettlementSource
import app.plead.android.models.SettlementStatus
import app.plead.android.services.CaseAction
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.PreviewData
import app.plead.android.services.caseFileStatus
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import app.plead.android.features.settlement.SettlementFulfilment

private val me = UUID.fromString("11111111-1111-1111-1111-111111111111")       // PreviewData.meId
private val partner = UUID.fromString("22222222-2222-2222-2222-222222222222")  // PreviewData.partnerId

// MARK: - Docket rows

class SettlementDocketRowTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun settledRowRibbonAndStamp() {
        val kase = PreviewData.settledCase
        assertEquals("Settled", SettlementDocket.ribbonTitle(kase, storeTitle = "Settled out of court"))
        assertEquals("Settled\nout of court", SettlementDocket.stamp(kase))
        assertEquals("Settled out of court", SettlementDocket.stamp(kase, wide = true))
        // Everything else keeps the store's ribbon and CLOSED.
        val closed = kase.copy(status = CaseStatus.closed)
        assertEquals("You won", SettlementDocket.ribbonTitle(closed, storeTitle = "You won"))
        assertEquals("Closed", SettlementDocket.stamp(closed))
        assertNull(SettlementDocket.stamp(kase.copy(status = CaseStatus.mistrial)))
        assertNull(SettlementDocket.stamp(PreviewData.trialCase))
    }

    @Test fun settledRowNeverSaysServedOrWon() {
        val store = PreviewData.settledStore()
        val kase = PreviewData.settledCase
        assertNull(store.outcome(kase))
        for (text in listOf(
            SettlementDocket.ribbonTitle(kase, storeTitle = store.ribbonTitle(kase)),
            SettlementDocket.stamp(kase) ?: "",
            SettlementDocket.homeLabel(SettlementFulfilment.of(PreviewData.settledSettlement)),
        )) {
            assertFalse(text, text.uppercase().contains("SERVED"))
            assertFalse(text, text.lowercase().contains("won"))
        }
    }

    @Test fun pendingChipAndRoute() {
        val store = PreviewData.settlementOfferStore(round = 2)
        val kase = PreviewData.settlementTrialCase
        val s = store.settlement(kase.id)
        assertEquals("Settlement offer pending · Round 2 of 3", SettlementDocket.pendingChip(kase, s))
        assertEquals("Round 2 of 3", SettlementDocket.pendingRound(kase, s))
        assertTrue(store.pendingSettlementForMe(kase.id))
        assertEquals(SettlementDocket.RowRoute.settlementResponse, SettlementDocket.route(kase, s, pendingForMe = true))
        assertEquals(SettlementDocket.RowRoute.settlementRoom, SettlementDocket.route(kase, s, pendingForMe = false))
        // No settlement: the row opens the record.
        assertNull(SettlementDocket.pendingChip(PreviewData.trialCase, null))
        assertEquals(SettlementDocket.RowRoute.record, SettlementDocket.route(PreviewData.trialCase, null, pendingForMe = false))
    }

    @Test fun pendingFromCasePointerAlone() {
        // Realtime delivered the case row before the settlement row: still pending, round 1.
        val kase = PreviewData.settlementTrialCase
        assertTrue(SettlementDocket.isPending(kase, null))
        assertEquals("Settlement offer pending · Round 1 of 3", SettlementDocket.pendingChip(kase, null))
    }

    @Test fun endedAttemptsAreNotPending() {
        for (status in listOf(SettlementStatus.rejected, SettlementStatus.withdrawn, SettlementStatus.expired)) {
            val s = PreviewData.settlementPending(round = 3).copy(status = status)
            val kase = PreviewData.trialCase.copy(settlementId = null)
            assertFalse("$status", SettlementDocket.isPending(kase, s))
            assertEquals(SettlementDocket.RowRoute.record, SettlementDocket.route(kase, s, pendingForMe = false))
        }
        // A settled (closed) case is never "pending".
        assertFalse(SettlementDocket.isPending(PreviewData.settledCase, PreviewData.settledSettlement))
    }

    @Test fun roundIsClamped() {
        val s = PreviewData.settlementPending(round = 3).copy(currentRound = 7)
        assertEquals("Round 3 of 3", SettlementDocket.pendingRound(PreviewData.settlementTrialCase, s))
    }
}

// MARK: - Docket sections (amendment af: Open / Closed replace the outcome filter)

class SettlementFilterTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun settledCasesAreClosedFilesWithTheirOwnStatus() {
        val store = PreviewData.settledStore()
        val settled = PreviewData.settledCase
        assertTrue(store.closedCases.any { it.id == settled.id }) // settled cases are closed cases
        assertTrue(CaseFileDocket.sortClosed(store.cases.filter { it.status.isClosed }).any { it.id == settled.id })
        val status = store.caseFileStatus(settled)
        assertEquals(CaseFileStatus.Kind.settled, status.kind)
        assertEquals("Settled outside court", status.message)
        assertEquals("View the settlement", status.nextStep)
        // Court outcomes never read as settled.
        for (kase in store.closedCases.filter { it.status != CaseStatus.closedSettled }) {
            assertNotEquals(CaseFileStatus.Kind.settled, store.caseFileStatus(kase).kind)
        }
    }

    @Test fun tallyIgnoresSettled() {
        val with = PreviewData.settledStore().winTally
        val without = PreviewData.store().winTally
        assertTrue(with.mine == without.mine && with.partners == without.partners && with.ties == without.ties)
    }
}

// MARK: - Home

class SettlementHomeTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun activeCardCopy() {
        assertEquals(SettlementDocket.ActiveCardCopy("Settlement offer pending", "Respond to offer"), SettlementDocket.activeCardCopy(pendingForMe = true))
        assertEquals(SettlementDocket.ActiveCardCopy("Settlement offer pending", "View offer"), SettlementDocket.activeCardCopy(pendingForMe = false))
    }

    @Test fun pendingOfferCardSelection() {
        val store = PreviewData.settlementOfferStore(round = 1)
        val kase = PreviewData.settlementTrialCase
        assertEquals(CaseAction.respondToSettlement, store.nextAction(kase))
        // The card keeps the settlement step (it isn't a judgement step).
        assertEquals(CaseAction.respondToSettlement, ActiveCaseCard.cardAction(CaseAction.respondToSettlement, base = CaseAction.watchCourt))
        assertEquals(
            SettlementDocket.RowRoute.settlementResponse,
            SettlementDocket.route(kase, store.settlement(kase.id), pendingForMe = store.pendingSettlementForMe(kase.id)),
        )
        assertTrue(store.outstandingSettlementCases.isEmpty())
    }

    @Test fun outstandingAgreementCardUntilFulfilled() {
        val store = PreviewData.settledStore()
        assertEquals(listOf(PreviewData.settledCase.id), store.outstandingSettlementCases.map { it.id })
        // Settled cases are closed: never the active card.
        assertNotEquals(CaseStatus.closedSettled, store.currentCase?.status)
        assertFalse(store.openCases.any { it.id == PreviewData.settledCase.id })
        val fulfilled = PreviewData.settledSettlement.copy(status = SettlementStatus.fulfilled, fulfilledAt = Instant.now())
        val done = PreviewData.store(
            cases = PreviewData.allCases + PreviewData.settledCase, settlements = listOf(fulfilled),
            settlementOffers = listOf(PreviewData.settledOffer),
        )
        assertTrue(done.outstandingSettlementCases.isEmpty())
    }

    @Test fun homeDueLabels() {
        val zone = ZoneId.of("UTC")
        val now = ZonedDateTime.of(2026, 9, 24, 10, 0, 0, 0, zone).toInstant()
        fun label(s: app.plead.android.models.Settlement) = SettlementDocket.homeLabel(SettlementFulfilment.of(s, now, zone))
        var s = PreviewData.settledSettlement.copy(dueAt = now.atZone(zone).plusDays(3).toInstant())
        assertEquals("DUE · 3 DAYS", label(s))
        s = s.copy(dueAt = now.atZone(zone).plusDays(1).toInstant())
        assertEquals("DUE · 1 DAY", label(s))
        s = s.copy(dueAt = now.plusSeconds(3600))
        assertEquals("DUE · TODAY", label(s))
        s = s.copy(dueAt = now.minusSeconds(3600))
        assertEquals("OVERDUE", label(s))
        s = s.copy(status = SettlementStatus.fulfilled)
        assertEquals("SETTLEMENT FULFILLED ✓", label(s))
        s = s.copy(status = SettlementStatus.accepted, dueAt = null)
        assertEquals("OUTSTANDING", label(s))
    }
}

// MARK: - Record

class SettlementRecordTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun historyLabels() {
        val store = PreviewData.settledStore()
        val offers = store.settlementOffers(PreviewData.settledCase.id)
        assertEquals(listOf(1, 2), offers.map { it.roundNumber })
        val first = offers[0]
        val accepted = offers[1]
        assertEquals("Round 1 · Alex", SettlementDocket.offerHeading(first, me, "Alex"))
        assertEquals("Round 2 · You", SettlementDocket.offerHeading(accepted, me, "Alex"))
        assertEquals("Court suggestion", SettlementDocket.sourceChip(first, me, "Alex"))
        var custom = first.copy(source = SettlementSource.custom)
        assertEquals("Written by Alex", SettlementDocket.sourceChip(custom, me, "Alex"))
        custom = custom.copy(proposedBy = me)
        assertEquals("Written by you", SettlementDocket.sourceChip(custom, me, "Alex"))
        // Only the accepted offer is highlighted, and only once agreed.
        val s = store.settlement(PreviewData.settledCase.id)
        assertTrue(SettlementDocket.isAccepted(accepted, s))
        assertFalse(SettlementDocket.isAccepted(first, s))
        assertFalse(SettlementDocket.isAccepted(accepted, PreviewData.settlementPending(round = 2)))
        assertNotEquals(me, partner)
    }
}
