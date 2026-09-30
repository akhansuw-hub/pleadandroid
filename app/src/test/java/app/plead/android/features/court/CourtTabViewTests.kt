// Android-only: the Court tab host's decision logic (CourtTabView.swift `kase`, `recentlySettledTrialCase` and the
// router-side actions). iOS covers these through UI tests.
package app.plead.android.features.court

import app.plead.android.app.AppRouter
import app.plead.android.app.AppSheet
import app.plead.android.app.AppTab
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Profile
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementStatus
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourtTabViewTests {
    private val now: Instant = Instant.parse("2026-09-30T12:00:00Z")
    private val meId = UUID.randomUUID()
    private val partnerId = UUID.randomUUID()

    private fun kase(status: CaseStatus, closedAt: Instant? = null) = Case(
        id = UUID.randomUUID(), coupleId = UUID.randomUUID(), caseNumber = 1, title = "t", plaintiffId = meId,
        defendantId = partnerId, status = status, charge = "c", remedyRequested = "r", updatedAt = now.minusSeconds(86_400),
        closedAt = closedAt,
    )

    private class FakeStore(
        val cases: List<Case>,
        override val courtroomCase: Case? = null,
        val settlements: Map<UUID, Settlement> = emptyMap(),
        val pending: Set<UUID> = emptySet(),
    ) : CourtTabStore {
        override val me: Profile? = null
        override val partner: Profile? = null
        override val closedCases: List<Case> get() = cases.filter { it.status.isClosed }
        override fun caseById(id: UUID): Case? = cases.firstOrNull { it.id == id }
        override fun settlement(caseId: UUID): Settlement? = settlements[caseId]
        override fun pendingSettlementForMe(caseId: UUID): Boolean = caseId in pending
    }

    private fun settlement(c: Case, entryPoint: String) =
        Settlement(id = UUID.randomUUID(), caseId = c.id, status = SettlementStatus.accepted, initiatedBy = meId, entryPoint = entryPoint)

    @Test fun routedCaseWinsWhileInCourtOrSettled() {
        val routed = kase(CaseStatus.verdict)
        val live = kase(CaseStatus.trial)
        val store = FakeStore(listOf(routed, live), courtroomCase = live)
        assertEquals(routed, CourtTabView.kase(store, routed.id, now))
        val settled = kase(CaseStatus.closedSettled, closedAt = now.minusSeconds(30 * 86_400))
        assertEquals(settled, CourtTabView.kase(FakeStore(listOf(settled, live), courtroomCase = live), settled.id, now))
    }

    @Test fun routedCaseOutOfCourtFallsBackToTheCourtroomCase() {
        val routed = kase(CaseStatus.defence)
        val live = kase(CaseStatus.deliberating)
        assertEquals(live, CourtTabView.kase(FakeStore(listOf(routed, live), courtroomCase = live), routed.id, now))
    }

    @Test fun trialSettledWithinTwelveHoursStaysOnStage() {
        val recent = kase(CaseStatus.closedSettled, closedAt = now.minusSeconds(11 * 3600))
        val store = FakeStore(listOf(recent), settlements = mapOf(recent.id to settlement(recent, "trial")))
        assertEquals(recent, CourtTabView.kase(store, null, now))
        val old = kase(CaseStatus.closedSettled, closedAt = now.minusSeconds(13 * 3600))
        assertNull(CourtTabView.kase(FakeStore(listOf(old), settlements = mapOf(old.id to settlement(old, "trial"))), null, now))
        val fromDocket = kase(CaseStatus.closedSettled, closedAt = now.minusSeconds(3600))
        assertNull(CourtTabView.kase(FakeStore(listOf(fromDocket), settlements = mapOf(fromDocket.id to settlement(fromDocket, "docket"))), null, now))
    }

    @Test fun routerActions() {
        val router = AppRouter()
        val id = UUID.randomUUID()
        CourtTabView.chooseJudgement(router, id)
        assertEquals(AppSheet.chooseJudgement(id), router.sheet)
        CourtTabView.proposeSettlement(router, id)
        assertEquals(AppSheet.settlementRoom(id), router.sheet)
        CourtTabView.openSettlement(FakeStore(emptyList(), pending = setOf(id)), router, id)
        assertEquals(AppSheet.settlementResponse(id), router.sheet)
        CourtTabView.openSettlement(FakeStore(emptyList()), router, id)
        assertEquals(AppSheet.settlementRoom(id), router.sheet)
        router.courtCaseId = id
        router.tab = AppTab.court
        CourtTabView.backToDocket(router)
        assertNull(router.courtCaseId)
        assertEquals(AppTab.cases, router.tab)
    }
}
