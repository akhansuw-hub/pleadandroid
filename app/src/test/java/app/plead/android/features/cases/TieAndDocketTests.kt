// Port of ArgueWinTests/TieAndDocketTests.swift (the suites whose subjects wave 3d owns: CaseFlow ties seen from the
// docket, open means ongoing, and Home's card selection). `tieCardActionsServeOrDeclineForEither`
// (JudgementCardAction) and `JudgementFulfilmentTests` belong to wave 3e (Features/Judgement); `CourtroomTieTests`
// to wave 3a (CourtroomLogic).
//
// CONTRACTS-v2 amendment l: the court chooses on a tie, "open" means the court process is ongoing,
// and judgement fulfilment is a separate block (docket slip, record card, Home's outstanding card).
package app.plead.android.features.cases

import app.plead.android.features.home.ActiveCaseCard
import app.plead.android.models.CaseStatus
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementOption
import app.plead.android.models.JudgementOptionType
import app.plead.android.models.JudgementStatus
import app.plead.android.services.CaseAction
import app.plead.android.services.CaseStore
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.PreviewData
import app.plead.android.services.nextAction
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private val me = UUID.fromString("11111111-1111-1111-1111-111111111111")       // PreviewData.meId
private val partner = UUID.fromString("22222222-2222-2222-2222-222222222222")  // PreviewData.partnerId
private val slice = UUID.fromString("AAAAAAAA-0000-0000-0000-000000000014")    // PreviewData.judgementCase

private fun courtChosen(status: JudgementStatus, caseId: UUID = slice): Judgement = Judgement(
    caseId = caseId, status = status, chooserId = null,
    selected = if (status == JudgementStatus.pendingSelection) null else JudgementOption(
        id = "x", title = "Cook dinner together", detail = "Cook one dinner together.", type = JudgementOptionType.compromise, dueDays = 7,
    ),
)

// MARK: - CaseFlow: ties

class TieCaseFlowTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun tiesNeverAskAnyoneToChooseOrAccept() {
        for (status in CaseStatus.entries) {
            val kase = PreviewData.judgementCase.copy(status = status)
            for (user in listOf(me, partner)) {
                for (js in JudgementStatus.entries) {
                    val action = kase.nextAction(user, courtChosen(js))
                    assertTrue("$status $js", action != CaseAction.chooseJudgement && action != CaseAction.acceptJudgement)
                }
            }
        }
    }

    @Test fun tieStepsByState() {
        val kase = PreviewData.judgementCase // .verdict
        for (user in listOf(me, partner)) {
            assertEquals(CaseAction.awaitJudgementChoice, kase.nextAction(user, courtChosen(JudgementStatus.pendingSelection)))
            assertEquals(CaseAction.markJudgementServed, kase.nextAction(user, courtChosen(JudgementStatus.delivered)))
            assertEquals(CaseAction.markJudgementServed, kase.nextAction(user, courtChosen(JudgementStatus.accepted)))
            assertEquals(CaseAction.hearVerdict, kase.nextAction(user, courtChosen(JudgementStatus.served)))
            assertEquals(CaseAction.hearVerdict, kase.nextAction(user, courtChosen(JudgementStatus.declined)))
        }
    }

    @Test fun storeNeverMakesAnyoneTheChooserOfATie() {
        val store = PreviewData.store()
        val id = PreviewData.dinnerTieCase.id
        assertFalse(store.isChooser(id))
        assertNull(store.judgementRecipientId(PreviewData.dinnerTieCase)) // it binds both
        assertEquals(CaseAction.markJudgementServed, store.nextAction(PreviewData.dinnerTieCase))
    }
}

// MARK: - Open means the court process is ongoing

class OpenMeansCourtOngoingTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun statusOpenness() {
        val open = listOf(
            CaseStatus.drafting, CaseStatus.summoned, CaseStatus.defence, CaseStatus.scheduling, CaseStatus.trial,
            CaseStatus.deliberating, CaseStatus.awaitingVerdict, CaseStatus.verdict, CaseStatus.appeal,
        )
        for (s in CaseStatus.entries) assertEquals("$s", open.contains(s), s.isOpen)
    }

    @Test fun closedCasesStayClosedWhateverTheJudgement() {
        val store = PreviewData.store()
        // #017 (tie, due), #011 (overdue) and #012 (served) all have judgements; all are closed.
        for (c in listOf(PreviewData.dinnerTieCase, PreviewData.guiltyCase, PreviewData.wonCase)) {
            assertTrue(store.closedCases.any { it.id == c.id })
            assertFalse(store.openCases.any { it.id == c.id })
        }
        assertTrue(store.openCases.all { it.status.isOpen })
    }

    @Test fun currentCaseIsOnlyEverOpen() {
        // Only closed cases, one with a delivered judgement: no active case card.
        val store = PreviewData.store(cases = listOf(PreviewData.dinnerTieCase, PreviewData.wonCase))
        assertNull(store.currentCase)
        assertEquals(listOf(PreviewData.dinnerTieCase.id), store.outstandingJudgementCases.map { it.id })
    }
}

// MARK: - Home card selection

class HomeCardSelectionTests {
    @get:Rule val main = MainDispatcherRule()

    @Test fun outstandingCardsAreSeparateFromTheActiveCase() {
        val store = PreviewData.store()
        // The active card is an open case.
        val current = store.currentCase
        assertNotNull(current)
        assertTrue(current!!.status.isOpen)
        // Outstanding: overdue #011 first (soonest due), then the tie #017; the served #012 is gone.
        assertEquals(listOf(PreviewData.guiltyCase.id, PreviewData.dinnerTieCase.id), store.outstandingJudgementCases.map { it.id })
    }

    @Test fun outstandingCardDisappearsOnceServedOrDeclined() = runTest {
        val store = PreviewData.store()
        store.demo?.cancelAll()
        store.markServed(PreviewData.dinnerTieCase.id)
        assertEquals(listOf(PreviewData.guiltyCase.id), store.outstandingJudgementCases.map { it.id })
        store.markServed(PreviewData.guiltyCase.id)
        assertTrue(store.outstandingJudgementCases.isEmpty())
    }

    @Test fun pendingChoiceStaysOnTheActiveCardWhileOpen() {
        val store = PreviewData.judgementStore() // #014 verdict revealed, I choose
        assertEquals(PreviewData.judgementCase.id, store.currentCase?.id)
        assertFalse(store.outstandingJudgementCases.any { it.id == PreviewData.judgementCase.id })
        // Once the case closes with the choice still owed, it moves to an outstanding card.
        val closed = PreviewData.judgementCase.copy(status = CaseStatus.closed)
        val later = CaseStore(
            preview = PreviewData.me, partner = PreviewData.partner, couple = PreviewData.couple, cases = listOf(closed),
            judgements = listOf(PreviewData.judgementFixture(JudgementStatus.pendingSelection)),
        )
        assertNull(later.currentCase)
        assertEquals(listOf(closed.id), later.outstandingJudgementCases.map { it.id })
        // …but a choice the partner owes is not mine to act on.
        val theirs = CaseStore(
            preview = PreviewData.me, partner = PreviewData.partner, couple = PreviewData.couple, cases = listOf(closed),
            judgements = listOf(PreviewData.judgementFixture(JudgementStatus.pendingSelection, iWon = false)),
        )
        assertTrue(theirs.outstandingJudgementCases.isEmpty())
    }

    @Test fun activeCardLeavesJudgementStepsToTheOutstandingCard() {
        assertEquals(CaseAction.hearVerdict, ActiveCaseCard.cardAction(CaseAction.acceptJudgement, base = CaseAction.hearVerdict))
        assertEquals(CaseAction.hearVerdict, ActiveCaseCard.cardAction(CaseAction.markJudgementServed, base = CaseAction.hearVerdict))
        assertEquals(CaseAction.hearVerdict, ActiveCaseCard.cardAction(CaseAction.awaitJudgementChoice, base = CaseAction.hearVerdict))
        assertEquals(CaseAction.chooseJudgement, ActiveCaseCard.cardAction(CaseAction.chooseJudgement, base = CaseAction.hearVerdict))
        assertEquals(CaseAction.yourTurnInCourt, ActiveCaseCard.cardAction(CaseAction.yourTurnInCourt, base = CaseAction.yourTurnInCourt))
        assertNotEquals(CaseAction.viewRecord, ActiveCaseCard.cardAction(CaseAction.hearVerdict, base = CaseAction.viewRecord))
    }
}
