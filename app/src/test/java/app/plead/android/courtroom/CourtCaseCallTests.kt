// Port of ArgueWinTests/CourtCaseCallTests.swift: the case call before testimony (CONTRACTS-v2 amendment ad, motion
// brief §18): the shared copy template, the live data mapping (topic from the filed title), and the opening flow
// entrance → caseCall → judgeIntroduction → opening on a fake clock: tap-advance, restored courts, Reduce Motion, and
// nothing of the record before the call ends.
package app.plead.android.courtroom

import app.plead.android.models.Role
import app.plead.android.models.Speaker
import app.plead.android.models.TrialPhase
import app.plead.android.models.Turn
import app.plead.android.services.UserDefaults
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CourtCaseCallTests {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    // MARK: Template

    @Test fun mockJudgeLineIsExact() {
        assertEquals(
            "The court is now in session. Case 14: Sam versus Alex. The matter before the court is the last slice of pizza. Sam, you may begin.",
            CourtCaseCall.mock.judgeLine,
        )
        assertEquals("Sam vs Alex", CourtCaseCall.mock.partiesLine)
        assertEquals("CASE #14 / THE LAST SLICE CASE", CourtCaseCall.mock.referenceLine)
        assertEquals("Now hearing: Sam versus Alex. Case 14, The Last Slice Case.", CourtCaseCall.mock.accessibilityLabel)
    }

    @Test fun liveCaseWithTopic() {
        val call = CourtCaseCall.live(CourtFixtures.openingMyTurn)
        assertEquals(CourtCaseCall(number = 14, plaintiff = "Aria", defendant = "Sam", title = "The Thermostat Incident", topic = "the thermostat incident"), call)
        assertEquals(
            "The court is now in session. Case 14: Aria versus Sam. The matter before the court is the thermostat incident. Aria, you may begin.",
            call.judgeLine,
        )
        assertEquals("Aria vs Sam", call.partiesLine)
        assertEquals("Now hearing: Aria versus Sam. Case 14, The Thermostat Incident.", call.accessibilityLabel)
    }

    @Test fun withoutTopicOrNumberNamesOnlyTheParties() {
        val noTopic = CourtCaseCall(number = 7, plaintiff = "Sam", defendant = "Alex", title = null, topic = null)
        assertEquals("The court is now in session. Case 7: Sam versus Alex. Sam, you may begin.", noTopic.judgeLine)
        val bare = CourtCaseCall(number = null, plaintiff = "Sam", defendant = "Alex", title = null, topic = null)
        assertEquals("The court is now in session. Sam versus Alex. Sam, you may begin.", bare.judgeLine)
        assertEquals("", bare.referenceLine)
        assertEquals("Now hearing: Sam versus Alex.", bare.accessibilityLabel)
        val empty = CourtCaseCall(number = 3, plaintiff = "Sam", defendant = "Alex", title = null, topic = "")
        assertEquals("The court is now in session. Case 3: Sam versus Alex. Sam, you may begin.", empty.judgeLine)

        // A live case whose title gives no usable topic: the parties only, never the charge.
        val s0 = CourtFixtures.openingMyTurn
        val s = s0.copy(kase = s0.kase.copy(title = "He never does the dishes"))
        val live = CourtCaseCall.live(s)
        assertNull(live.topic)
        assertEquals("The court is now in session. Case 14: Aria versus Sam. Aria, you may begin.", live.judgeLine)
        assertFalse(live.judgeLine.contains(s.kase.charge))
        assertEquals("CASE #14 / HE NEVER DOES THE DISHES", live.referenceLine)
    }

    @Test fun referenceLineIsTrackedCaps() {
        assertEquals("CASE #14 / THE THERMOSTAT INCIDENT", CourtCaseCall(number = 14, plaintiff = "A", defendant = "B", title = "The Thermostat Incident").referenceLine)
        assertEquals("CASE #7 / SOCKS ON THE STAIRS", CourtCaseCall(number = 7, plaintiff = "A", defendant = "B", title = "socks on the stairs").referenceLine)
        assertEquals("CASE #7", CourtCaseCall(number = 7, plaintiff = "A", defendant = "B").referenceLine)
        assertEquals("THE LAST SLICE CASE", CourtCaseCall(number = null, plaintiff = "A", defendant = "B", title = "The Last Slice Case").referenceLine)
        // The spoken label keeps the title as filed.
        assertEquals(
            "Now hearing: A versus B. The Last Slice Case.",
            CourtCaseCall(number = null, plaintiff = "A", defendant = "B", title = "The Last Slice Case").accessibilityLabel,
        )
    }

    @Test fun liveFallsBackToRoleNamesAndDropsEmptyTitles() {
        val s0 = CourtFixtures.openingMyTurn
        val s = s0.copy(me = s0.me.copy(displayName = "  "), kase = s0.kase.copy(title = "   "))
        val call = CourtCaseCall.live(s)
        assertTrue(call.plaintiff == "Plaintiff" && call.defendant == "Sam")
        assertTrue(call.title == null && call.topic == null)
        assertEquals("CASE #14", call.referenceLine)
    }

    // MARK: Topic derivation

    @Test fun topicFromTitle() {
        val cases = listOf(
            "The Thermostat Incident" to "the thermostat incident",
            "the thermostat incident" to "the thermostat incident",
            "The Last Slice Case" to "the last slice",
            "The Case of the Missing Remote" to "the missing remote",
            "Case of the Cold Feet" to "the cold feet",
            "Dishes" to "the dishes",
            "Socks on the stairs" to "the socks on the stairs",
            "Leaving the Lights On" to "leaving the lights on",
            "The TV Remote" to "the TV remote",
            "Sam's Socks" to "Sam's socks",
            "The “Quick” Errand." to "the quick errand",
            "A Tale of Two Thermostats" to "a tale of two thermostats",
        )
        for ((title, topic) in cases) assertEquals(title, topic, CourtCaseCall.topic(fromTitle = title, names = listOf("Sam", "Alex")))
    }

    @Test fun unusableTitlesGiveNoTopic() {
        val titles = listOf(
            "", "   ", "Who ate my fries?", "He never does the dishes", "My sweater", "You always forget",
            "Sam ate the last slice", "Socks. Again.", "The pizza 🍕", "One two three four five six seven eight nine",
            "An extraordinarily overcomplicated disagreement regarding refrigerator etiquette",
        )
        for (title in titles) assertNull(title, CourtCaseCall.topic(fromTitle = title, names = listOf("Sam", "Alex")))
    }

    // MARK: Flow

    private fun defaults(): UserDefaults = UserDefaults.inMemory()

    /** Sleeps return at once (after a yield), or never (`suspend`, for taps). */
    private fun clock(suspend: Boolean = false): suspend (Double) -> Unit = {
        if (suspend) awaitCancellation()
        yield()
    }

    @Test fun flowRunsEntranceThenCaseCallThenIntroductionThenOpening() = runTest(dispatcher) {
        val d = defaults()
        val live = CourtLiveEntrance(state = CourtFixtures.openingMyTurn, reduceMotion = false, defaults = d, sleep = clock())
        assertTrue(live.plays && live.caseCall.plays)
        assertTrue(live.caseCall.step == CourtOpeningStep.entrance && live.holdsRecord)
        val steps = mutableListOf<Triple<CourtOpeningStep, Double, CourtEntrancePhase>>()
        live.caseCall.onStep = { s, t -> steps.add(Triple(s, t, live.director.phase)) }
        live.begin()
        assertTrue(d.bool(CourtLiveEntrance.seenKey(CourtFixtures.caseId)))
        assertTrue(d.bool(CourtLiveEntrance.caseCallSeenKey(CourtFixtures.caseId)))
        live.director.join()
        live.caseCall.join()

        assertEquals(listOf(CourtOpeningStep.caseCall, CourtOpeningStep.judgeIntroduction, CourtOpeningStep.opening), steps.map { it.first })
        // The card only after the entrance is ready (judge and parties in place).
        assertEquals(CourtEntrancePhase.ready, steps.first().third)
        // Card for 2.5 s; the introduction readable for at least 3 s (reveal + reading).
        assertEquals(CourtCaseCallTiming.card, steps[1].second, 1e-9)
        val introduction = steps[2].second - steps[1].second
        assertTrue(introduction >= CourtCaseCallTiming.introductionMinimum)
        assertTrue(abs(introduction - CourtCaseCallTiming.introductionHold(reveal = live.caseCall.introductionRevealDuration)) < 0.0001)
        assertTrue(live.caseCall.introductionRevealed)
        assertTrue(!live.holdsRecord && !live.caseCall.isRunning)
    }

    @Test fun tapAdvancesTheCardThenCompletesThenLeavesTheIntroduction() = runTest(dispatcher) {
        val live = CourtLiveEntrance(state = CourtFixtures.openingMyTurn, reduceMotion = false, defaults = defaults(), sleep = clock(suspend = true))
        live.begin()
        assertEquals(CourtEntrancePhase.entering, live.director.phase)
        // First tap: the entrance finishes, the case is called at once.
        live.tap()
        assertTrue(live.director.phase == CourtEntrancePhase.ready && live.caseCall.step == CourtOpeningStep.caseCall)
        live.tap()
        assertTrue(live.caseCall.step == CourtOpeningStep.judgeIntroduction && !live.caseCall.introductionRevealed)
        live.tap()
        assertTrue(live.caseCall.step == CourtOpeningStep.judgeIntroduction && live.caseCall.introductionRevealed)
        live.tap()
        assertTrue(live.caseCall.step == CourtOpeningStep.opening && !live.holdsRecord)
        assertFalse(live.caseCall.isRunning)
        live.tap()
        assertEquals(CourtOpeningStep.opening, live.caseCall.step)
        live.end()
    }

    @Test fun leavingSnapsToTheOpening() {
        val live = CourtLiveEntrance(state = CourtFixtures.openingMyTurn, reduceMotion = false, defaults = defaults(), sleep = clock(suspend = true))
        live.begin()
        live.end()
        assertTrue(live.director.phase == CourtEntrancePhase.ready && live.caseCall.step == CourtOpeningStep.opening && !live.caseCall.isRunning)
    }

    @Test fun restoredCourtSkipsBoth() {
        val d = defaults()
        val s = CourtFixtures.openingMyTurn
        d.set(true, CourtLiveEntrance.seenKey(s.kase.id))
        d.set(true, CourtLiveEntrance.caseCallSeenKey(s.kase.id))
        val live = CourtLiveEntrance(state = s, reduceMotion = false, defaults = d, sleep = clock(suspend = true))
        assertTrue(!live.plays && !live.caseCall.plays)
        live.begin()
        assertTrue(live.director.phase == CourtEntrancePhase.ready && live.caseCall.step == CourtOpeningStep.opening && !live.holdsRecord)
        live.tap()
        assertEquals(CourtOpeningStep.opening, live.caseCall.step)
    }

    @Test fun restoredCourtNotYetCalledIsCalledAtOnce() {
        val d = defaults()
        val s = CourtFixtures.openingMyTurn
        d.set(true, CourtLiveEntrance.seenKey(s.kase.id))
        val live = CourtLiveEntrance(state = s, reduceMotion = false, defaults = d, sleep = clock(suspend = true))
        assertTrue(!live.plays && live.caseCall.plays)
        live.begin()
        assertTrue(live.director.phase == CourtEntrancePhase.ready && live.caseCall.step == CourtOpeningStep.caseCall)
        assertTrue(d.bool(CourtLiveEntrance.caseCallSeenKey(s.kase.id)))
        live.end()
    }

    @Test fun noCallOnceTestimonyHasStarted() {
        // First open while a party has already spoken: the entrance plays, "Sam, you may begin" would be false.
        val s = CourtFixtures.waitingForPartner
        val live = CourtLiveEntrance(state = s, reduceMotion = false, defaults = defaults(), sleep = clock(suspend = true))
        assertTrue(live.plays && !live.caseCall.plays && !live.holdsRecord)
        assertFalse(CourtLiveEntrance.canCallCase(CourtFixtures.deliberating))
        assertFalse(CourtLiveEntrance.canCallCase(CourtFixtures.safetyNotice))
        assertTrue(CourtLiveEntrance.canCallCase(CourtFixtures.openingMyTurn))
        assertFalse(CourtLiveEntrance.shouldCallCase(CourtFixtures.openingMyTurn, defaults = defaults(), mode = CourtEntranceMode.hold(0.6)))
    }

    @Test fun testimonyDuringTheCallEndsIt() {
        val s = CourtFixtures.openingMyTurn
        val live = CourtLiveEntrance(state = s, reduceMotion = false, defaults = defaults(), sleep = clock(suspend = true))
        live.begin()
        live.tap()
        assertEquals(CourtOpeningStep.caseCall, live.caseCall.step)
        live.update(CourtFixtures.state(TrialPhase.defendantOpening, owner = Role.defendant, turns = 2))
        assertEquals(CourtOpeningStep.opening, live.caseCall.step)
    }

    @Test fun reduceMotionShowsTheFullTextWithoutStaging() = runTest(dispatcher) {
        val live = CourtLiveEntrance(state = CourtFixtures.openingMyTurn, reduceMotion = true, defaults = defaults(), sleep = clock(suspend = true))
        assertEquals(0.0, live.caseCall.introductionRevealDuration, 0.0)
        live.begin()
        live.tap() // entrance fade → ready → card
        assertEquals(CourtOpeningStep.caseCall, live.caseCall.step)
        live.tap()
        assertTrue(live.caseCall.step == CourtOpeningStep.judgeIntroduction && live.caseCall.introductionRevealed)
        live.tap() // already complete: one tap moves on
        assertEquals(CourtOpeningStep.opening, live.caseCall.step)

        // Unattended: the same steps, the introduction held for the minimum.
        val auto = CourtLiveEntrance(state = CourtFixtures.openingMyTurn, reduceMotion = true, defaults = defaults(), sleep = clock())
        val steps = mutableListOf<Pair<CourtOpeningStep, Double>>()
        auto.caseCall.onStep = { s, t -> steps.add(s to t) }
        auto.begin()
        auto.director.join()
        auto.caseCall.join()
        assertEquals(listOf(CourtOpeningStep.caseCall, CourtOpeningStep.judgeIntroduction, CourtOpeningStep.opening), steps.map { it.first })
        assertTrue(abs(steps[2].second - steps[1].second - CourtCaseCallTiming.introductionMinimum) < 0.0001)
    }

    @Test fun frozenStillsHoldTheirStep() {
        val card = CourtLiveEntrance(
            state = CourtFixtures.openingMyTurn, reduceMotion = false, mode = CourtEntranceMode.holdCaseCall,
            defaults = defaults(), sleep = clock(suspend = true),
        )
        card.begin()
        assertTrue(!card.plays && card.caseCall.step == CourtOpeningStep.caseCall && card.caseCall.frozen)
        card.tap(); card.end()
        assertEquals(CourtOpeningStep.caseCall, card.caseCall.step)
        val intro = CourtLiveEntrance(
            state = CourtFixtures.openingMyTurn, reduceMotion = false, mode = CourtEntranceMode.holdIntroduction,
            defaults = defaults(), sleep = clock(suspend = true),
        )
        intro.begin()
        assertTrue(intro.caseCall.step == CourtOpeningStep.judgeIntroduction && intro.caseCall.introductionRevealed)
    }

    // MARK: Nothing of the record before the call completes

    @Test fun recordWaitsForTheCall() {
        val motion = CourtMotionDirector(sleep = { }, now = { 0.0 }, random = { it.start }, memory = CourtRevealMemory())
        val s = CourtFixtures.openingMyTurn
        motion.update(s)
        val intro = UUID.randomUUID()
        motion.introductionTurnId = intro
        motion.openingStep = CourtOpeningStep.entrance
        assertTrue(motion.phase == CourtPhase.opening && motion.caseCallHold && motion.openingHold)
        motion.openingStep = CourtOpeningStep.caseCall
        assertEquals(CourtPhase.caseCall, motion.phase)
        val record = CourtBubbleModel(s.turns[0], s)
        assertTrue(motion.isHeld(record.turn.id) && !motion.isHeld(intro))
        assertEquals(CourtMotionDirector.RevealDecision.held, motion.requestReveal(record, turns = s.turns))
        assertFalse(motion.hasRevealed(record.turn.id))
        motion.openingStep = CourtOpeningStep.judgeIntroduction
        assertEquals(CourtPhase.judgeIntroduction, motion.phase)
        val introTurn = Turn(id = intro, caseId = s.kase.id, speaker = Speaker.judge, body = CourtCaseCall.live(s).judgeLine)
        assertNotEquals(
            "the introduction must reveal during the call",
            CourtMotionDirector.RevealDecision.held, motion.requestReveal(CourtBubbleModel(introTurn, s), turns = s.turns),
        )
        assertEquals(CourtMotionDirector.RevealDecision.held, motion.requestReveal(record, turns = s.turns))
        motion.openingStep = CourtOpeningStep.opening
        assertTrue(motion.phase == CourtPhase.opening && !motion.openingHold && !motion.isHeld(record.turn.id))
        assertNotEquals(CourtMotionDirector.RevealDecision.held, motion.requestReveal(record, turns = s.turns))
    }

    @Test fun noTrayObjectionOrEvidenceDuringTheCall() {
        val present = CourtroomLogic.dockMode(CourtFixtures.presentExhibits)
        assertTrue("fixture is not presenting", present is DockMode.compose && present.kind is ComposeKind.presentExhibits)
        assertEquals(DockMode.judgeHasFloor, CourtCaseCallGate.dockMode(present, calling = true))
        assertEquals(present, CourtCaseCallGate.dockMode(present, calling = false))
        val opening = CourtroomLogic.dockMode(CourtFixtures.openingMyTurn)
        assertTrue(opening.isMyTurn)
        assertFalse(CourtCaseCallGate.dockMode(opening, calling = true).isMyTurn)
        val objection = CourtroomLogic.dockMode(CourtFixtures.objectionWindow)
        assertEquals(DockMode.judgeHasFloor, CourtCaseCallGate.dockMode(objection, calling = true))
        assertEquals(DockMode.deliberating, CourtCaseCallGate.dockMode(DockMode.deliberating, calling = true))

        val easel = CourtroomLogic.easelPresentation(CourtFixtures.objectionWindow)
        assertTrue(easel != null)
        assertNull(CourtCaseCallGate.easel(easel, calling = true))
        assertEquals(easel, CourtCaseCallGate.easel(easel, calling = false))
    }

    @Test fun caseCallPhasesStayInsideTheAmbientBand() {
        for (p in listOf(CourtPhase.caseCall, CourtPhase.judgeIntroduction)) {
            assertTrue(p.crowdSpacing == CourtPhase.opening.crowdSpacing && p.judgeSpacing == CourtPhase.opening.judgeSpacing)
        }
        // Never derived from a case: only the opening flow reports them.
        for (s in listOf(CourtFixtures.openingMyTurn, CourtFixtures.presentExhibits, CourtFixtures.deliberating, CourtFixtures.verdictIn)) {
            assertFalse(CourtPhase.derive(s) in listOf(CourtPhase.caseCall, CourtPhase.judgeIntroduction))
        }
    }
}
