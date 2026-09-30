// Port of ArgueWinTests/DemoTrialSimulatorTests.swift: the demo trial simulator mirrors trial.ts:
// present → objection window → pass → next exhibit / rest → next phase.
package app.plead.android.services

import app.plead.android.models.AICall
import app.plead.android.models.CaseStatus
import app.plead.android.models.EdgeError
import app.plead.android.models.Exhibit
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import app.plead.android.models.ObjectionReason
import app.plead.android.models.ObjectionRuling
import app.plead.android.models.Role
import app.plead.android.models.Speaker
import app.plead.android.models.TrialPhase
import app.plead.android.models.Turn
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class DemoTrialSimulatorTests {
    /** Queued (not eager) main dispatcher: like Swift's `Task.yield`, simulated work waits for the next turn. */
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())

    /**
     * Demo store with instant delays and a fixed random source (0 → the partner always passes,
     * rulings sustained, first line of every bank).
     */
    private fun makeStore(random: Double = 0.0, autoplay: Boolean = false): Pair<CaseStore, DemoTrialSimulator> {
        val store = PreviewData.store()
        val sim = requireNotNull(store.demo)
        sim.cancelAll()
        sim.config = DemoTrialSimulator.Config(speed = DemoTrialSimulator.Speed.instant, autoplay = autoplay, random = { random })
        return store to sim
    }

    private val caseId = PreviewData.trialCase.id
    private val exA = PreviewData.id(104)
    private val exB = PreviewData.id(105)
    private val partnerExA = PreviewData.id(106)

    /** `CourtroomLogic.isPass` (wave 3a). */
    private fun isPass(t: Turn) = t.meta["pass"]?.boolValue == true

    @Test fun presentOpensObjectionWindowThenPassReturnsTheFloor() = runTest(main.dispatcher) {
        val (store, sim) = makeStore()
        store.submitTurn(caseId, "The labelled box.", exA)

        // Presenting hands the observer (the partner) the objection window.
        var c = requireNotNull(store.caseById(caseId))
        assertEquals(TrialPhase.plaintiffExhibits, c.phase)
        assertEquals(Role.defendant, c.phaseTurnOwner)
        assertNotNull(store.exhibits.first { it.id == exA }.presentedAt)
        val presented = store.turns(caseId).last()
        assertTrue(presented.speaker == Speaker.plaintiff && presented.exhibitId == exA)
        assertTrue(c.deadlineAt!!.isAfter(Instant.now().plusSeconds(11 * 3600)))

        // The simulated partner passes; exhibit B is left, so the floor returns to me.
        assertTrue(sim.settle())
        c = requireNotNull(store.caseById(caseId))
        val pass = store.turns(caseId).last()
        assertTrue(isPass(pass) && pass.speaker == Speaker.defendant && pass.exhibitId == exA)
        assertTrue(c.phase == TrialPhase.plaintiffExhibits && c.phaseTurnOwner == Role.plaintiff)
    }

    @Test fun restEndsThePhaseAndThePartnerPresents() = runTest(main.dispatcher) {
        val (store, sim) = makeStore()
        store.submitTurn(caseId, "The labelled box.", exA)
        assertTrue(sim.settle())
        store.submitTurn(caseId, "I rest.", null)
        val rest = store.turns(caseId).last { it.speaker == Speaker.plaintiff }
        assertEquals(true, rest.meta["rest"]?.boolValue)

        assertTrue(sim.settle())
        val c = requireNotNull(store.caseById(caseId))
        val turns = store.turns(caseId)
        // Judge phase line for the defendant's exhibits, then the partner presents: my objection window.
        assertTrue(turns.any { it.speaker == Speaker.judge && it.aiCall == AICall.phaseLine && it.phase == TrialPhase.defendantExhibits })
        assertTrue(c.phase == TrialPhase.defendantExhibits && c.phaseTurnOwner == Role.plaintiff)
        assertTrue(turns.last().speaker == Speaker.defendant && turns.last().exhibitId == partnerExA)
    }

    @Test fun lastExhibitAutoRestsAndMyObjectionIsRuled() = runTest(main.dispatcher) {
        val (store, sim) = makeStore()
        store.submitTurn(caseId, "A.", exA)
        assertTrue(sim.settle())
        store.submitTurn(caseId, "B.", exB)
        assertTrue(sim.settle())
        // Nothing left after the window: automatic rest, then the defendant's exhibits phase.
        var turns = store.turns(caseId)
        assertTrue(turns.any { it.speaker == Speaker.plaintiff && it.meta["auto"]?.boolValue == true && it.phase == TrialPhase.plaintiffExhibits })
        assertEquals(TrialPhase.defendantExhibits, store.caseById(caseId)?.phase)

        // I object to the partner's exhibit; the judge rules, and the trial moves to cross-examination.
        store.raiseObjection(caseId, partnerExA, ObjectionReason.speculation)
        assertNull(store.caseById(caseId)?.phaseTurnOwner) // the judge has the floor
        assertTrue(sim.settle())
        turns = store.turns(caseId)
        val ruling = turns.first { it.aiCall == AICall.objectionRuling }
        assertTrue(ruling.objectionReason == ObjectionReason.speculation && ruling.objectionRuling == ObjectionRuling.sustained)
        assertEquals(ObjectionRuling.sustained, store.exhibits.first { it.id == partnerExA }.objectionRuling)
        val c = requireNotNull(store.caseById(caseId))
        assertTrue(c.phase == TrialPhase.crossExamination && c.phaseTurnOwner == Role.plaintiff)
        val cross = turns.last()
        assertTrue(cross.aiCall == AICall.crossExamine && cross.crossSide == Role.plaintiff && cross.questions.size in 2..3)
    }

    @Test fun noExhibitCapThirtyExhibitsPresentInOrder() = runTest(main.dispatcher) {
        val (store, sim) = makeStore()
        val mine = store.exhibits.first { it.id == exA }
        // Top up to 30 exhibits for my side (A…AD); no cap anywhere.
        val existing = store.exhibits.count { it.caseId == caseId && it.ownerId == mine.ownerId }
        for (i in existing until 30) {
            store.demoUpsertExhibit(
                Exhibit(id = UUID.randomUUID(), caseId = caseId, ownerId = mine.ownerId, label = ExhibitLabel.at(i), type = ExhibitType.text, caption = "Item $i", body = "b", sort = i),
            )
        }
        val shown = mutableListOf<ExhibitLabel>()
        while (true) {
            val next = DemoTrialSimulator.unpresented(requireNotNull(store.caseById(caseId)), store.exhibits(caseId), Role.plaintiff).firstOrNull() ?: break
            shown.add(next.label)
            store.submitTurn(caseId, "Look.", next.id)
            assertTrue(sim.settle())
        }
        assertEquals(30, shown.size)
        assertEquals((0 until 30).map { ExhibitLabel.at(it) }, shown)
        assertEquals(TrialPhase.defendantExhibits, store.caseById(caseId)?.phase)
    }

    @Test fun exhibitPhaseLinesExplainOneAtATime() = runTest(main.dispatcher) {
        val (store, sim) = makeStore()
        store.submitTurn(caseId, "I rest.", null)
        assertTrue(sim.settle())
        val line = store.turns(caseId).last { it.aiCall == AICall.phaseLine && it.phase == TrialPhase.defendantExhibits }
        assertTrue(line.body.contains("one exhibit at a time") || line.body.contains("shown one at a time"))
        assertTrue(line.body.contains("object"))
        assertTrue(line.body.split(" ").size <= 40)
    }

    @Test fun wrongTurnIsRejected() = runTest(main.dispatcher) {
        val (store, _) = makeStore()
        try {
            store.raiseObjection(caseId, exA, null)
            fail("expected an EdgeError")
        } catch (_: EdgeError) {
        }
    }

    @Test fun autoplayRunsTheTrialToAVerdict() = runTest(main.dispatcher) {
        val (store, sim) = makeStore(random = 0.9, autoplay = true)
        sim.resume()
        assertTrue(sim.settle(timeout = 20.seconds))
        val c = requireNotNull(store.caseById(caseId))
        assertEquals(CaseStatus.verdict, c.status)
        assertTrue(c.panelProgress == 4 && c.deliberatingAt != null)
        val v = requireNotNull(store.verdict(caseId))
        assertTrue(v.panelSplit == "2-1" && v.findings.isNotEmpty() && !v.isTie)
        assertEquals(3, store.jurorReviews(caseId).size)
        val phases = store.turns(caseId).mapNotNull { it.phase }.toSet()
        assertTrue(
            phases.containsAll(
                setOf(TrialPhase.plaintiffExhibits, TrialPhase.defendantExhibits, TrialPhase.crossExamination, TrialPhase.plaintiffClosing, TrialPhase.defendantClosing),
            ),
        )
        // The revealed verdict opens the judgement (amendment j): the winner chooses.
        assertNotNull(store.judgement(caseId))
    }
}
