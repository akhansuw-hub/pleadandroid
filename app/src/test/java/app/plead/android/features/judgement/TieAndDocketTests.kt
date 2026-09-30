// Port of ArgueWinTests/TieAndDocketTests.swift (the judgement / store halves). `activeCardLeavesJudgementStepsToTheOutstandingCard`
// (Home's ActiveCaseCard, wave 3d) and `CourtroomTieTests` (CourtroomLogic, wave 3a) stay with those waves.
//
// CONTRACTS-v2 amendment l: the court chooses on a tie, "open" means the court process is ongoing,
// and judgement fulfilment is a separate block (docket slip, record card, Home's outstanding card).
package app.plead.android.features.judgement

import app.plead.android.designsystem.PleadColor
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private fun courtChosen(status: JudgementStatus, caseId: UUID = slice): Judgement = Judgement(
    caseId = caseId, status = status, chooserId = null,
    selected = if (status == JudgementStatus.pendingSelection) null else JudgementOption(
        id = "x", title = "Cook dinner together", detail = "Cook one dinner together.", type = JudgementOptionType.compromise, dueDays = 7,
    ),
)

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
        val kase = PreviewData.judgementCase   // verdict
        for (user in listOf(me, partner)) {
            assertEquals(CaseAction.awaitJudgementChoice, kase.nextAction(user, courtChosen(JudgementStatus.pendingSelection)))
            assertEquals(CaseAction.markJudgementServed, kase.nextAction(user, courtChosen(JudgementStatus.delivered)))
            assertEquals(CaseAction.markJudgementServed, kase.nextAction(user, courtChosen(JudgementStatus.accepted)))
            assertEquals(CaseAction.hearVerdict, kase.nextAction(user, courtChosen(JudgementStatus.served)))
            assertEquals(CaseAction.hearVerdict, kase.nextAction(user, courtChosen(JudgementStatus.declined)))
        }
    }

    @Test fun tieCardActionsServeOrDeclineForEither() {
        for (user in listOf(me, partner)) {
            assertEquals(listOf(JudgementCardAction.markServed, JudgementCardAction.decline), JudgementCardAction.actions(courtChosen(JudgementStatus.delivered), user))
            assertEquals(listOf(JudgementCardAction.markServed, JudgementCardAction.decline), JudgementCardAction.actions(courtChosen(JudgementStatus.accepted), user))
            assertTrue(JudgementCardAction.actions(courtChosen(JudgementStatus.pendingSelection), user).isEmpty())
            assertTrue(JudgementCardAction.actions(courtChosen(JudgementStatus.served), user).isEmpty())
        }
    }

    @Test fun storeNeverMakesAnyoneTheChooserOfATie() {
        val store = PreviewData.store()
        val id = PreviewData.dinnerTieCase.id
        assertFalse(store.isChooser(id))
        assertNull(store.judgementRecipientId(PreviewData.dinnerTieCase))   // it binds both
        assertEquals(CaseAction.markJudgementServed, store.nextAction(PreviewData.dinnerTieCase))
    }
}

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
        for (c in listOf(PreviewData.dinnerTieCase, PreviewData.guiltyCase, PreviewData.wonCase)) {
            assertTrue(store.closedCases.any { it.id == c.id })
            assertFalse(store.openCases.any { it.id == c.id })
        }
        assertTrue(store.openCases.all { it.status.isOpen })
    }

    @Test fun currentCaseIsOnlyEverOpen() {
        val store = PreviewData.store(cases = listOf(PreviewData.dinnerTieCase, PreviewData.wonCase))
        assertNull(store.currentCase)
        assertEquals(listOf(PreviewData.dinnerTieCase.id), store.outstandingJudgementCases.map { it.id })
    }
}

class JudgementFulfilmentTests {
    private val zone = ZoneId.of("Europe/London")
    private val now: Instant = LocalDateTime.of(2026, 9, 24, 10, 0).atZone(zone).toInstant()

    private fun j(status: JudgementStatus, chooser: UUID? = partner, dueIn: Long? = null): Judgement =
        Judgement(caseId = UUID.randomUUID(), status = status, chooserId = chooser, dueAt = dueIn?.let { now.plusSeconds(it) })

    private fun label(judgement: Judgement, name: String? = "Alex"): String =
        JudgementFulfilment.of(judgement, me, name, now, zone).label

    @Test fun due() {
        assertEquals("DUE · 3 DAYS", label(j(JudgementStatus.delivered, dueIn = 3 * 86_400)))
        assertEquals("DUE · 1 DAY", label(j(JudgementStatus.accepted, dueIn = 86_400)))
        assertEquals("DUE · TODAY", label(j(JudgementStatus.delivered, dueIn = 3600)))
        assertEquals("OUTSTANDING", label(j(JudgementStatus.delivered)))
        assertEquals(JudgementFulfilment.due(3), JudgementFulfilment.of(j(JudgementStatus.delivered, dueIn = 3 * 86_400), me, null, now, zone))
    }

    @Test fun overdue() {
        assertEquals("OVERDUE · 2 DAYS", label(j(JudgementStatus.accepted, dueIn = -2 * 86_400)))
        assertEquals("OVERDUE · 1 DAY", label(j(JudgementStatus.delivered, dueIn = -86_400)))
        assertEquals("OVERDUE", label(j(JudgementStatus.delivered, dueIn = -3600)))
        assertEquals(PleadColor.burgundy, JudgementFulfilment.overdue(2).tint)
    }

    @Test fun servedAndDeclined() {
        assertEquals("SERVED ✓", label(j(JudgementStatus.served, dueIn = -2 * 86_400)))   // served wins over a passed due date
        assertEquals("DECLINED", label(j(JudgementStatus.declined, dueIn = 2 * 86_400)))
        assertEquals(PleadColor.gold, JudgementFulfilment.served.tint)
        assertEquals(PleadColor.subtleText, JudgementFulfilment.declined.tint)
    }

    @Test fun pending() {
        assertEquals("AWAITING THE COURT", label(j(JudgementStatus.pendingSelection, chooser = null)))
        assertEquals("AWAITING ALEX'S CHOICE", label(j(JudgementStatus.pendingSelection, chooser = partner), name = "Alex"))
        assertEquals("AWAITING YOUR CHOICE", label(j(JudgementStatus.pendingSelection, chooser = me)))
    }

    @Test fun heading() {
        val kase = PreviewData.judgementCase
        assertEquals("Court judgement", JudgementFulfilmentBlock.heading(kase, j(JudgementStatus.delivered)))
        assertEquals("Court resolution", JudgementFulfilmentBlock.heading(kase, j(JudgementStatus.delivered, chooser = null)))
        assertEquals("Verdict final", JudgementFulfilmentBlock.heading(kase.copy(status = CaseStatus.closed), j(JudgementStatus.served)))
        assertTrue(j(JudgementStatus.delivered, chooser = null).noun == "Resolution" && j(JudgementStatus.delivered).noun == "Judgement")
        assertNotEquals("", JudgementFulfilment.due(1).accessibilityText)
    }
}

class HomeCardSelectionTests {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    @Test fun outstandingCardsAreSeparateFromTheActiveCase() {
        val store = PreviewData.store()
        val current = requireNotNull(store.currentCase)
        assertTrue(current.status.isOpen)
        // Outstanding: overdue #011 first (soonest due), then the tie #017; the served #012 is gone.
        assertEquals(listOf(PreviewData.guiltyCase.id, PreviewData.dinnerTieCase.id), store.outstandingJudgementCases.map { it.id })
    }

    @Test fun outstandingCardDisappearsOnceServedOrDeclined() = runTest(main.dispatcher) {
        val store = PreviewData.store()
        store.demo?.cancelAll()
        store.markServed(PreviewData.dinnerTieCase.id)
        assertEquals(listOf(PreviewData.guiltyCase.id), store.outstandingJudgementCases.map { it.id })
        store.markServed(PreviewData.guiltyCase.id)
        assertTrue(store.outstandingJudgementCases.isEmpty())
    }

    @Test fun pendingChoiceStaysOnTheActiveCardWhileOpen() {
        val store = PreviewData.judgementStore()   // #014 verdict revealed, I choose
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
}
