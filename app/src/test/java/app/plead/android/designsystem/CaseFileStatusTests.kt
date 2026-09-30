// Port of the CaseFlow-dependent half of ArgueWinTests/CaseFileTests.swift (owed by wave 2b, landed with wave 3d):
// `CaseFileStatusTests` (the `CaseFileStatus.make` matrix + the CaseStore extension) and the docket tests that need
// `make` / PreviewData / CasesView (`actionFirstByNearestDeadlineThenWaiting`, `aWaitingCaseNeverDisplacesAnAction`,
// `countsFromData`, `demoDocketLeadsWithAnAction`). The countdown, card copy and `closedNewestFirst` are in CaseFileTests.kt.
package app.plead.android.designsystem

import app.plead.android.designsystem.CaseFileStatus.Kind
import app.plead.android.features.cases.CasesView
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementStatus
import app.plead.android.models.Role
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.TrialPhase
import app.plead.android.services.CaseAction
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.PreviewData
import app.plead.android.services.caseFileAction
import app.plead.android.services.caseFileParties
import app.plead.android.services.caseFileStatus
import app.plead.android.services.make
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private val me = UUID.fromString("11111111-1111-1111-1111-111111111111")       // PreviewData.meId
private val partner = UUID.fromString("22222222-2222-2222-2222-222222222222")  // PreviewData.partnerId
// Swift `Date(timeIntervalSinceReferenceDate: 800_000_000)`.
private val now: Instant = Instant.ofEpochSecond(978_307_200L + 800_000_000L)
private const val hour: Long = 3600

private fun kase(
    n: Int = 1,
    status: CaseStatus,
    plaintiff: UUID = partner,
    defendant: UUID = me,
    phase: TrialPhase? = null,
    owner: Role? = null,
    proposalCount: Int = 0,
    trialAt: Instant? = null,
    verdictAt: Instant? = null,
    deadline: Instant? = null,
    updated: Instant = now,
): Case = Case(
    id = UUID.randomUUID(), coupleId = UUID.randomUUID(), caseNumber = n, title = "The Spoiler", plaintiffId = plaintiff,
    defendantId = defendant, status = status, phase = phase, phaseTurnOwner = owner, charge = "c", remedyRequested = "r",
    proposalCount = proposalCount, trialAt = trialAt, verdictAt = verdictAt, deadlineAt = deadline, createdAt = now, updatedAt = updated,
)

private fun status(c: Case, judgement: Judgement? = null, settlement: Settlement? = null, offer: SettlementOffer? = null): CaseFileStatus =
    CaseFileStatus.make(c, me, partnerName = "Alex", judgement = judgement, settlement = settlement, latestOffer = offer, now = now)

class CaseFileStatusTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun defenceDue() {
        val s = status(kase(status = CaseStatus.defence, deadline = now.plusSeconds(29 * hour)))
        assertEquals(CaseFileStatus(Kind.needsYou, "Needs you · 1d 5h left", "File your defence", now.plusSeconds(29 * hour)), s)
        assertTrue(s.needsMe)
    }

    @Test fun defenceDueWithoutDeadlineShowsNoTime() {
        val s = status(kase(status = CaseStatus.defence))
        assertEquals("Needs you", s.message)
        assertNull(s.deadline)
    }

    @Test fun pastDeadlineShowsNoTime() {
        val s = status(kase(status = CaseStatus.defence, deadline = now.minusSeconds(hour)))
        assertTrue(s.kind == Kind.needsYou && s.message == "Needs you")
    }

    @Test fun waitingForTheDefence() {
        val s = status(kase(status = CaseStatus.defence, plaintiff = me, defendant = partner, deadline = now.plusSeconds(5 * hour)))
        assertEquals(Kind.waiting, s.kind)
        assertEquals("Waiting for Alex", s.message)
        assertEquals("Alex to file their defence", s.nextStep)
        assertFalse(s.message.contains("left"))
    }

    @Test fun summons() {
        val plea = status(kase(status = CaseStatus.summoned, deadline = now.plusSeconds(42 * 60)))
        assertEquals(CaseFileStatus(Kind.needsYou, "Needs you · 42m left", "Enter your plea", now.plusSeconds(42 * 60)), plea)
        val waiting = status(kase(status = CaseStatus.summoned, plaintiff = me, defendant = partner, deadline = now.plusSeconds(hour)))
        assertTrue(waiting.kind == Kind.waiting && waiting.nextStep == "Alex to enter a plea")
        val lapsed = status(kase(status = CaseStatus.summoned, plaintiff = me, defendant = partner, deadline = now.minusSeconds(hour)))
        assertTrue(lapsed.kind == Kind.needsYou && lapsed.message == "Needs you" && lapsed.nextStep == "Request default judgment")
    }

    @Test fun schedulingMineToAccept() {
        // proposal_count 0: the defendant proposed, so the plaintiff (me) accepts.
        val s = status(kase(status = CaseStatus.scheduling, plaintiff = me, defendant = partner, deadline = now.plusSeconds(20 * hour)))
        assertTrue(s.kind == Kind.needsYou && s.message == "Needs you · 20h left" && s.nextStep == "Agree the trial time")
    }

    @Test fun schedulingWaitingOnPartner() {
        val s = status(kase(status = CaseStatus.scheduling, plaintiff = partner, defendant = me))
        assertEquals(CaseFileStatus(Kind.scheduling, "Scheduling", "Waiting for Alex to agree", null), s)
    }

    @Test fun schedulingForANonParty() {
        val s = CaseFileStatus.make(kase(status = CaseStatus.scheduling), UUID.randomUUID(), now = now)
        assertTrue(s.kind == Kind.scheduling && s.message == "Scheduling" && s.nextStep == "Agree the trial time")
    }

    @Test fun trialTurns() {
        val d = now.plusSeconds(90 * 60)
        val opening = status(kase(status = CaseStatus.trial, phase = TrialPhase.defendantOpening, owner = Role.defendant, deadline = d))
        assertTrue(opening.kind == Kind.needsYou && opening.message == "Needs you · 1h 30m left" && opening.nextStep == "Your turn in court")
        val objection = status(kase(status = CaseStatus.trial, phase = TrialPhase.plaintiffExhibits, owner = Role.defendant, deadline = d))
        assertTrue(objection.kind == Kind.needsYou && objection.nextStep == "Object or let it stand")
        val presenting = status(kase(status = CaseStatus.trial, phase = TrialPhase.defendantExhibits, owner = Role.defendant, deadline = d))
        assertEquals("Your turn in court", presenting.nextStep)
        val cross = status(kase(status = CaseStatus.trial, phase = TrialPhase.crossExamination, owner = Role.defendant, deadline = d))
        assertTrue(cross.kind == Kind.needsYou && cross.nextStep == "Answer the judge")
    }

    @Test fun trialTheirTurn() {
        val s = status(kase(status = CaseStatus.trial, phase = TrialPhase.plaintiffOpening, owner = Role.plaintiff, deadline = now.plusSeconds(hour)))
        assertTrue(s.kind == Kind.waiting && s.message == "Waiting for Alex" && s.nextStep == "Alex is giving their opening")
        val objecting = status(kase(status = CaseStatus.trial, phase = TrialPhase.defendantExhibits, owner = Role.plaintiff))
        assertEquals("Alex is considering an objection", objecting.nextStep)
        val judge = status(kase(status = CaseStatus.trial))
        assertTrue(judge.kind == Kind.waiting && judge.message == "In court" && judge.nextStep == "The judge has the floor")
    }

    @Test fun deliberating() {
        val s = status(kase(status = CaseStatus.deliberating, trialAt = now.plusSeconds(2 * hour + 50 * 60)))
        assertTrue(s.kind == Kind.deliberating && s.message == "Judge is deliberating")
        assertEquals("Awaiting verdict · due in 2h 50m", s.nextStep)
        assertEquals(now.plusSeconds(2 * hour + 50 * 60), s.deadline)
        // No reading time (or overdue): no estimate.
        val none = status(kase(status = CaseStatus.awaitingVerdict))
        assertEquals(CaseFileStatus(Kind.deliberating, "Judge is deliberating", "Awaiting verdict", null), none)
        val overdue = status(kase(status = CaseStatus.deliberating, trialAt = now.minusSeconds(hour)))
        assertTrue(overdue.nextStep == "Awaiting verdict" && overdue.deadline == null)
    }

    @Test fun verdictAndJudgement() {
        val v = kase(status = CaseStatus.verdict, plaintiff = me, defendant = partner)
        assertTrue(status(v).kind == Kind.needsYou && status(v).nextStep == "Hear the verdict" && status(v).message == "Needs you")
        val choose = Judgement(caseId = v.id, status = JudgementStatus.pendingSelection, chooserId = me)
        assertEquals("Choose the judgement", status(v, judgement = choose).nextStep)
        val theirs = Judgement(caseId = v.id, status = JudgementStatus.pendingSelection, chooserId = partner)
        assertEquals(CaseFileStatus(Kind.waiting, "Waiting for Alex", "Alex is choosing the judgement", null), status(v, judgement = theirs))
        val delivered = Judgement(caseId = v.id, status = JudgementStatus.delivered, chooserId = partner)
        assertEquals("Accept the judgement", status(v, judgement = delivered).nextStep)
        val tie = Judgement(caseId = v.id, status = JudgementStatus.pendingSelection, chooserId = null)
        assertEquals("Judgement pending", status(v, judgement = tie).message)
        val outstanding = Judgement(caseId = v.id, status = JudgementStatus.accepted, chooserId = partner, dueAt = now.plusSeconds(3 * 86_400))
        val o = status(v, judgement = outstanding)
        assertTrue(o.kind == Kind.waiting && o.message == "Judgement outstanding" && o.deadline == outstanding.dueAt)
    }

    @Test fun closedStates() {
        for (s in listOf(CaseStatus.closed, CaseStatus.closedGuilty, CaseStatus.closedDefault)) {
            assertEquals(CaseFileStatus(Kind.closed, "Verdict delivered", "View the ruling", null), status(kase(status = s)))
        }
        assertEquals(CaseFileStatus(Kind.settled, "Settled outside court", "View the settlement", null), status(kase(status = CaseStatus.closedSettled)))
        assertEquals(CaseFileStatus(Kind.stopped, "Trial stopped", "View the record", null), status(kase(status = CaseStatus.mistrial)))
    }

    @Test fun closedIgnoresStaleDeadlines() {
        assertNull(status(kase(status = CaseStatus.closed, deadline = now.plusSeconds(hour))).deadline)
    }

    @Test fun settlementPending() {
        val receiver = PreviewData.settlementOfferStore(round = 1)
        val c = receiver.cases.first { receiver.hasPendingSettlement(it.id) && it.status.isOpen }
        val s = receiver.caseFileStatus(c)
        val action = receiver.nextAction(c)
        if (action == CaseAction.respondToSettlement) {
            assertTrue(s.kind == Kind.needsYou && s.nextStep == "Respond to the settlement offer" && s.deadline == null)
        } else {
            assertEquals(CaseAction.awaitSettlement, action)
            assertTrue(s.kind == Kind.waiting && s.nextStep == "Alex to answer your settlement offer")
        }
    }

    @Test fun fallbackNameWithoutPartner() {
        val s = CaseFileStatus.make(kase(status = CaseStatus.defence, plaintiff = me, defendant = partner), me, now = now)
        assertTrue(s.message == "Waiting for your partner" && s.nextStep == "Your partner to file their defence")
    }

    @Test fun storeFillsThePartnerName() {
        val store = PreviewData.store()
        val s = store.caseFileStatus(PreviewData.defenceCase)
        assertTrue(s.kind == Kind.needsYou && s.nextStep == "File your defence")
        assertTrue(s.message, s.message.startsWith("Needs you · 1d "))
        assertEquals("Alex v. Sam", store.caseFileParties(PreviewData.defenceCase))
        assertEquals(CaseAction.fileDefence, store.caseFileAction(PreviewData.defenceCase))
        assertNull(store.caseFileAction(PreviewData.deliberatingCase))
        assertNull(store.caseFileAction(PreviewData.wonCase))
    }
}

class CaseFileDocketStatusTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun actionFirstByNearestDeadlineThenWaiting() {
        val far = kase(1, status = CaseStatus.defence, deadline = now.plusSeconds(50 * hour))
        val near = kase(2, status = CaseStatus.trial, phase = TrialPhase.defendantOpening, owner = Role.defendant, deadline = now.plusSeconds(2 * hour))
        val noDeadline = kase(3, status = CaseStatus.verdict)
        val waiting = kase(4, status = CaseStatus.defence, plaintiff = me, defendant = partner, deadline = now.plusSeconds(hour))
        val scheduling = kase(5, status = CaseStatus.scheduling)
        val deliberating = kase(6, status = CaseStatus.deliberating, trialAt = now.plusSeconds(30 * 60))
        val rows = CaseFileDocket.sortOpen(listOf(deliberating, waiting, noDeadline, far, scheduling, near).map { CaseFileRow(it, status(it)) })
        assertEquals(listOf(2, 1, 3, 4, 5, 6), rows.map { it.kase.caseNumber })
    }

    @Test fun aWaitingCaseNeverDisplacesAnAction() {
        val waiting = kase(1, status = CaseStatus.defence, plaintiff = me, defendant = partner, deadline = now.plusSeconds(60))
        val action = kase(2, status = CaseStatus.defence, deadline = now.plusSeconds(30 * 86_400))
        assertEquals(2, CaseFileDocket.sortOpen(listOf(waiting, action).map { CaseFileRow(it, status(it)) }).first().kase.caseNumber)
    }

    @Test fun countsFromData() {
        val all = PreviewData.allCases
        val counts = CaseFileDocket.counts(all)
        assertEquals(all.count { it.status.isOpen }, counts.open)
        assertEquals(all.count { it.status.isClosed }, counts.closed)
        assertEquals(all.size, counts.open + counts.closed)
        val none = CaseFileDocket.counts(emptyList())
        assertTrue(none.open == 0 && none.closed == 0)
        assertEquals("Open ${counts.open}", CasesView.DocketSection.open.label(counts.open))
        assertEquals("Closed 8", CasesView.DocketSection.closed.label(8))
    }

    @Test fun demoDocketLeadsWithAnAction() {
        val store = PreviewData.store()
        val open = store.cases.filter { it.status.isOpen }
        val rows = CaseFileDocket.sortOpen(open.map { CaseFileRow(it, store.caseFileStatus(it)) })
        assertEquals(true, rows.firstOrNull()?.status?.needsMe)
        // Every action-required row precedes every other row.
        val firstOther = rows.indexOfFirst { !it.status.needsMe }.let { if (it < 0) rows.size else it }
        assertTrue(rows.drop(firstOther).all { !it.status.needsMe })
    }
}
