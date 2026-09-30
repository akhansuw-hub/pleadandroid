// Port of ArgueWinTests/MockTrialSceneTests.swift: onboarding mock trial (CONTRACTS-v2 amendments ab, ac, ae, aj): the
// intro, the shared court entrance, the compressed full case (fourteen beats), the player's schedule and controls,
// Reduce Motion, the help sheet, the stage's slots and layout. The player runs on the test dispatcher
// (`backgroundScope`) with the same fake clock as the Swift suite: each sleep advances `t` at once, or with `suspend`
// waits until cancelled. Swift parameterised loops are loops here; test names are the Swift names.
package app.plead.android.features.onboarding

import androidx.compose.ui.geometry.Size
import app.plead.android.courtroom.CourtEntranceDirector
import app.plead.android.courtroom.CourtEntrancePhase
import app.plead.android.courtroom.CourtEntrancePose
import app.plead.android.courtroom.CourtHelp
import app.plead.android.courtroom.CourtHelpTopic
import app.plead.android.courtroom.CourtPodiumParty
import app.plead.android.courtroom.GavelFrame
import app.plead.android.models.Role
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MockTrialSceneTests {
    private val S = MockTrialScript
    private fun line(s: MockTrialSpeaker, t: String) = MockTrialLine(s, t)

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** A fake clock: each sleep advances time instantly (or, with `suspend`, waits until cancelled). */
    class Clock(private val scope: CoroutineScope) {
        var t = 0.0
        val sleeps = mutableListOf<Double>()
        var suspend = false
        var cancelledSleeps = 0

        /** Directors the player made (one per START), and the Reduce Motion flag each was made with. */
        val directors = mutableListOf<Pair<CourtEntranceDirector, Boolean>>()

        /** `entranceHolds`: the entrance director's clock never advances (its walk-in waits until a tap/skip). */
        fun player(entranceHolds: Boolean = false): MockTrialPlayer = MockTrialPlayer(
            sleep = { d ->
                sleeps.add(d)
                if (suspend) {
                    try {
                        awaitCancellation()
                    } catch (e: CancellationException) {
                        cancelledSleeps += 1
                        throw e
                    }
                }
                t += d
            },
            now = { t },
            scope = scope,
            makeEntrance = { rm ->
                val d = CourtEntranceDirector(
                    hasPlaintiff = true, hasDefendant = true, reduceMotion = rm,
                    sleep = if (entranceHolds) ({ _ -> awaitCancellation() }) else ({ _ -> }),
                    scope = scope,
                )
                directors.add(d to rm)
                d
            },
        )
    }

    private suspend fun TestScope.settle(until: () -> Boolean) {
        for (i in 0 until 5000) {
            if (until()) return
            runCurrent()
            yield()
        }
    }

    /** With a suspended clock (nothing autoplays): START and wait for the judge's session line. */
    private suspend fun TestScope.startSuspended(p: MockTrialPlayer) {
        p.start()
        settle { p.phase == MockTrialPhase.trial }
    }

    /** Tap: complete the beat, then move on (each tap past the commit window). */
    private fun tapToNext(p: MockTrialPlayer, clock: Clock) {
        clock.t += 0.3
        if (!p.revealComplete) assertTrue("completes ${p.currentBeat}", p.advance())
        clock.t += 0.3
        assertTrue("leaves ${p.currentBeat}", p.advance())
    }

    private val allBeatsAfterOpening = MockTrialBeat.entries.drop(1)

    private fun MockTrialPlayer.Cue.isGavel() = this is MockTrialPlayer.Cue.gavel

    // MARK: Intro (amendment aj §1) / Script (amendment aj §2–12, verbatim)

    @Test fun introCopyIsExactAndHasNoPricing() {
        assertEquals("SEE HOW A PLEAD TRIAL WORKS", S.introEyebrow)
        assertEquals("We'll take you through a quick mock case before you enter court for real.", S.introBody)
        assertEquals("Case: The Last Slice · Sam v. Alex", S.introCard)
        assertEquals("START MOCK TRIAL", S.startCTA)
        assertEquals("SKIP DEMO", S.skipCTA)
        assertTrue(MockTrialInvitation.eyebrow == S.introEyebrow && MockTrialInvitation.body == S.introBody && MockTrialInvitation.caseCard == S.introCard)
        val all = S.allCopy.joinToString(" ").lowercase()
        for (word in listOf("price", "premium", "subscri", "trial offer", "$", "£", "€", "free", "arguewin")) {
            assertFalse(word, all.contains(word))
        }
    }

    @Test fun scriptCopyMatchesTheAmendmentVerbatim() {
        assertEquals(line(MockTrialSpeaker.judge, "Court is now in session. Today we're hearing Sam v. Alex — The Last Slice."), S.sessionLine)
        assertEquals("CLAIM · Alex ate the final slice after agreeing to save it.", S.claim)
        assertEquals("OPENING STATEMENT · PLAINTIFF", S.plaintiffOpeningLabel)
        assertEquals("Your opening statement tells the court what happened and why you believe you're right.", S.openingTooltip)
        assertEquals(line(MockTrialSpeaker.plaintiff, "He ate the last slice of pizza after promising to save it for me. I'd like a replacement pizza."), S.plaintiffOpeningLine)
        assertEquals("EXHIBIT A", S.exhibitA.label)
        assertEquals(listOf(line(MockTrialSpeaker.plaintiff, "Save me the last slice ❤️"), line(MockTrialSpeaker.defendant, "Yeah of course.")), S.exhibitA.messages)
        assertTrue(S.exhibitANoted.text == "Exhibit A is noted." && S.exhibitANoted.speaker == MockTrialSpeaker.judge)
        assertEquals("OPENING STATEMENT · DEFENDANT", S.defendantOpeningLabel)
        assertEquals(line(MockTrialSpeaker.defendant, "I thought she meant the last slice at the time. There were two left when I checked later."), S.defendantOpeningLine)
        assertTrue(S.exhibitB.label == "EXHIBIT B" && S.exhibitB.caption == "The box at 9pm: two slices left." && S.exhibitB.messages.isEmpty())
        assertTrue(S.exhibitBNoted.text == "Exhibit B is noted." && S.exhibitBNoted.speaker == MockTrialSpeaker.judge)
        assertEquals("CROSS-EXAMINATION", S.crossLabel)
        assertEquals(
            listOf("Did you know Sam was expecting the slice?", "If the roles were reversed, would you be annoyed?", "What could you have done differently?"),
            S.crossExamination.map { it.question.text },
        )
        assertEquals(listOf("Yes. I thought there was still one for her.", "Honestly, yes.", "Checked with her before eating it."), S.crossExamination.map { it.answer.text })
        assertTrue(S.crossExamination.all { it.question.speaker == MockTrialSpeaker.judge && it.answer.speaker == MockTrialSpeaker.defendant })
        assertEquals(line(MockTrialSpeaker.plaintiff, "There was only one left when I got home, and it was gone."), S.followUp)
        assertEquals("CLOSING STATEMENT", S.closingLabel)
        assertEquals("Your final chance to tell the court why it should rule in your favour.", S.closingTooltip)
        assertEquals(line(MockTrialSpeaker.plaintiff, "A promise is a promise."), S.plaintiffClosing)
        assertEquals(line(MockTrialSpeaker.defendant, "It was an honest mistake."), S.defendantClosing)
        assertEquals("THE COURT IS DELIBERATING…", S.deliberationTitle)
        assertEquals(
            listOf("Evidence Juror · Reviewing evidence ✓", "Consistency Juror · Comparing both accounts ✓", "Fairness Juror · Considering the outcome ✓", "Judge Wigsworth · Preparing ruling…"),
            S.deliberationRows,
        )
        assertEquals(MockTrialScript.DeliberationRow("Evidence Juror", "Reviewing evidence", true), S.deliberationRow(0))
        assertEquals(MockTrialScript.DeliberationRow("Judge Wigsworth", "Preparing ruling…", false), S.deliberationRow(3))
        assertEquals(line(MockTrialSpeaker.judge, "The court has reached a decision."), S.decisionLine)
        assertEquals("PLAINTIFF WINS", S.verdictTitle)
        assertEquals("Alex knew the slice had been promised to Sam, and the evidence supports Sam's account.", S.verdictReason)
        assertEquals("THE WINNER CHOOSES THE JUDGEMENT", S.judgementLabel)
        assertEquals(listOf("Replace the pizza", "Cook Sam's favourite meal", "Sam chooses the next takeaway"), S.judgementOptions)
        assertEquals("Replace the pizza", S.judgementOptions[S.judgementChoice])
        assertEquals(line(MockTrialSpeaker.judge, "So ordered."), S.soOrdered)
        assertEquals("CASE CLOSED", S.closedStamp)
        assertEquals("That's a Plead trial.", S.closedTitle)
        assertEquals(listOf("Both sides are heard.", "AI considers the evidence.", "The court delivers a verdict."), S.closedLines)
        assertEquals("I'M READY FOR COURT", S.readyCTA)
        assertTrue(S.judgeName == "Judge Wigsworth" && S.plaintiffName == "Sam" && S.defendantName == "Alex")
        // No emoji in the UI outside the text message itself.
        val emoji = S.allCopy.filter { s -> s.codePoints().anyMatch { it == 0xFE0F || it >= 0x1F300 || it == 0x2764 } }
        assertEquals(listOf("Save me the last slice ❤️"), emoji)
    }

    @Test fun beatsAreInOrderAndEachHoldsItsParts() {
        assertEquals(
            listOf(
                MockTrialBeat.opening, MockTrialBeat.plaintiffOpening, MockTrialBeat.plaintiffEvidence, MockTrialBeat.defendantOpening,
                MockTrialBeat.defendantEvidence, MockTrialBeat.crossExamination1, MockTrialBeat.crossExamination2,
                MockTrialBeat.crossExamination3, MockTrialBeat.crossFollowUp, MockTrialBeat.closings, MockTrialBeat.deliberation,
                MockTrialBeat.verdict, MockTrialBeat.judgement, MockTrialBeat.closed,
            ),
            MockTrialBeat.entries.toList(),
        )
        assertEquals(S.steps.map { it.beat }, MockTrialBeat.entries.toList())
        fun parts(b: MockTrialBeat) = S.step(b).parts
        assertEquals(listOf(MockTrialPart.say(S.sessionLine), MockTrialPart.claim), parts(MockTrialBeat.opening))
        assertEquals(listOf(MockTrialPart.say(S.plaintiffOpeningLine)), parts(MockTrialBeat.plaintiffOpening))
        assertEquals(listOf(MockTrialPart.exhibit(MockTrialExhibitID.a), MockTrialPart.say(S.exhibitANoted)), parts(MockTrialBeat.plaintiffEvidence))
        assertEquals(listOf(MockTrialPart.say(S.defendantOpeningLine)), parts(MockTrialBeat.defendantOpening))
        assertEquals(listOf(MockTrialPart.exhibit(MockTrialExhibitID.b), MockTrialPart.say(S.exhibitBNoted)), parts(MockTrialBeat.defendantEvidence))
        listOf(MockTrialBeat.crossExamination1, MockTrialBeat.crossExamination2, MockTrialBeat.crossExamination3).forEachIndexed { i, b ->
            assertEquals(listOf(MockTrialPart.say(S.crossExamination[i].question), MockTrialPart.say(S.crossExamination[i].answer)), parts(b))
            assertEquals(S.crossLabel, S.step(b).label)
        }
        assertEquals(listOf(MockTrialPart.say(S.followUp)), parts(MockTrialBeat.crossFollowUp))
        assertEquals(listOf(MockTrialPart.say(S.plaintiffClosing), MockTrialPart.say(S.defendantClosing)), parts(MockTrialBeat.closings))
        assertEquals((0..3).map { MockTrialPart.panelRow(it) }, parts(MockTrialBeat.deliberation))
        assertEquals(listOf(MockTrialPart.say(S.decisionLine), MockTrialPart.verdictCard), parts(MockTrialBeat.verdict))
        assertEquals(listOf(MockTrialPart.options, MockTrialPart.select, MockTrialPart.say(S.soOrdered)), parts(MockTrialBeat.judgement))
        assertTrue(parts(MockTrialBeat.closed).isEmpty())
        assertTrue(S.step(MockTrialBeat.plaintiffOpening).label == S.plaintiffOpeningLabel && S.step(MockTrialBeat.plaintiffOpening).tooltip == S.openingTooltip)
        assertEquals(S.defendantOpeningLabel, S.step(MockTrialBeat.defendantOpening).label)
        assertTrue(S.step(MockTrialBeat.closings).label == S.closingLabel && S.step(MockTrialBeat.closings).tooltip == S.closingTooltip)
        assertEquals(S.judgementLabel, S.step(MockTrialBeat.judgement).label)
        // Timing tables cover every part.
        for (b in MockTrialBeat.entries) {
            val times = MockTrialTiming.partTimes[b].orEmpty()
            assertEquals(b.name, parts(b).size, times.size)
            assertEquals(b.name, times.sorted(), times)
            MockTrialTiming.dwell[b]?.let { dwell ->
                assertTrue("$b stays readable ≥ 0.8 s once complete", MockTrialTiming.revealedAt(b) <= dwell - 0.8)
            }
        }
        assertNull("CASE CLOSED waits for the user", MockTrialTiming.dwell[MockTrialBeat.closed])
    }

    @Test fun analyticsAndDebugNames() {
        assertEquals(
            listOf(
                "opening", "plaintiff_opening", "plaintiff_evidence", "defendant_opening", "defendant_evidence", "cross_examination_1",
                "cross_examination_2", "cross_examination_3", "cross_examination_follow_up", "closings", "deliberation", "verdict", "judgement", "closed",
            ),
            MockTrialBeat.entries.map { it.analyticsName },
        )
        for (b in MockTrialBeat.entries) {
            assertEquals(b, MockTrialBeat.fromFlag(b.debugName))
            assertEquals(b, MockTrialBeat.fromFlag(b.analyticsName))
            assertEquals(b, MockTrialBeat.fromFlag(b.rawValue.toString()))
        }
        assertEquals(MockTrialBeat.plaintiffOpening, MockTrialBeat.fromFlag("plaintiffOpening"))
        assertNull(MockTrialBeat.fromFlag("nope"))
    }

    @Test fun voiceOverReadsSpeakerThenLine() {
        assertEquals("Exhibit A: text message from Sam: Save me the last slice ❤️ Alex: Yeah of course.", S.exhibitA.accessibilityText)
        assertEquals("Exhibit B: photo. The box at 9pm: two slices left.", S.exhibitB.accessibilityText)
        assertEquals(
            "Judge Wigsworth: Court is now in session. Today we're hearing Sam v. Alex — The Last Slice. Claim: Alex ate the final slice after agreeing to save it.",
            S.accessibilityText(MockTrialBeat.opening),
        )
        assertEquals(
            "Opening statement, plaintiff. Your opening statement tells the court what happened and why you believe you're right. Sam: He ate the last slice of pizza after promising to save it for me. I'd like a replacement pizza.",
            S.accessibilityText(MockTrialBeat.plaintiffOpening),
        )
        assertEquals(
            "Exhibit A: text message from Sam: Save me the last slice ❤️ Alex: Yeah of course. Judge Wigsworth: Exhibit A is noted.",
            S.accessibilityText(MockTrialBeat.plaintiffEvidence),
        )
        assertEquals(
            "Cross-examination. Judge Wigsworth: If the roles were reversed, would you be annoyed? Alex: Honestly, yes.",
            S.accessibilityText(MockTrialBeat.crossExamination2),
        )
        assertTrue(S.accessibilityText(MockTrialBeat.closings).endsWith("Sam: A promise is a promise. Alex: It was an honest mistake."))
        assertEquals(
            "The court is deliberating. Evidence Juror, reviewing evidence, done. Consistency Juror, comparing both accounts, done. Fairness Juror, considering the outcome, done. Judge Wigsworth, preparing ruling, in progress.",
            S.deliberationAccessibilityText,
        )
        assertEquals(
            "Judge Wigsworth: The court has reached a decision. Plaintiff wins. Alex knew the slice had been promised to Sam, and the evidence supports Sam's account.",
            S.accessibilityText(MockTrialBeat.verdict),
        )
        assertEquals(
            "The winner chooses the judgement. Options: Replace the pizza, Cook Sam's favourite meal, Sam chooses the next takeaway. Chosen: Replace the pizza. Judge Wigsworth: So ordered.",
            S.accessibilityText(MockTrialBeat.judgement),
        )
        assertEquals(
            "Case closed. That's a Plead trial. Both sides are heard. AI considers the evidence. The court delivers a verdict.",
            S.accessibilityText(MockTrialBeat.closed),
        )
        for (step in S.steps) for (l in step.lines) assertTrue(S.accessibilityText(step.beat).contains(l.accessibilityText))
    }

    // MARK: Invitation / entrance

    @Test fun theInvitationWaitsForStartAndNothingAutoplays() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        val p = clock.player()
        val fired = mutableListOf<MockTrialBeat>()
        p.onBeatChange = { fired.add(it) }
        assertTrue(p.phase == MockTrialPhase.invitation && p.isInvitation && !p.courtShown && p.entrance == null)
        p.setActive(false); p.setActive(true)
        p.autoplay = false; p.autoplay = true
        repeat(200) { runCurrent(); yield() }
        assertTrue(!p.isRunning && p.tasksStarted == 0)
        assertTrue(clock.sleeps.isEmpty() && p.cues.isEmpty())
        assertTrue(!p.entered && p.currentBeat == MockTrialBeat.opening && p.activeSpeaker == null)
        assertFalse(p.advance())
        assertTrue(p.phase == MockTrialPhase.invitation && fired.isEmpty())
    }

    @Test fun startPlaysTheSharedEntranceThenTheJudgesSessionLine() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        clock.suspend = true
        val p = clock.player()
        val fired = mutableListOf<MockTrialBeat>()
        p.onBeatChange = { fired.add(it) }
        p.start()
        assertTrue(p.courtShown && clock.directors.size == 1 && clock.directors.first().second == false)
        settle { p.phase == MockTrialPhase.trial && p.partsShown == 1 }
        assertEquals(CourtEntrancePhase.ready, p.entrance?.phase)
        assertEquals(MockTrialBeat.opening, p.currentBeat)
        assertEquals(listOf(MockTrialPart.say(S.sessionLine)), p.visibleParts)
        assertEquals(MockTrialSpeaker.judge, p.activeSpeaker)
        assertTrue("the opening is not a beat change (the flow already reports `opening`)", fired.isEmpty())
        val ready = p.cues.indexOfFirst { it.cue == MockTrialPlayer.Cue.entranceReady }
        val entered = p.cues.indexOfFirst { it.cue == MockTrialPlayer.Cue.entered }
        assertTrue(ready >= 0 && entered >= 0 && ready < entered)
        p.start()
        assertEquals("never a second entrance", 1, clock.directors.size)
        p.stop()
    }

    @Test fun aTapDuringTheEntranceFinishesIt() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        clock.suspend = true
        val p = clock.player(entranceHolds = true)
        p.start()
        if (p.phase == MockTrialPhase.entrance) {
            assertTrue(p.advance())
            assertEquals(CourtEntrancePhase.ready, p.entrance?.phase)
        }
        settle { p.phase == MockTrialPhase.trial }
        assertEquals(MockTrialBeat.opening, p.currentBeat)
        // A second tap right behind it is guarded.
        clock.t += 0.1
        assertFalse(p.advance())
        p.stop()
    }

    @Test fun theEntranceGavelTapStrikesOnceThenTheTrialStarts() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        val p = clock.player(entranceHolds = true)
        p.autoplay = false
        p.start()
        assertEquals(MockTrialPhase.entrance, p.phase)
        p.entranceGavel()
        p.advance()
        settle { p.phase == MockTrialPhase.trial }
        p.join()
        val g = p.cues.map { it.cue }.filter { it.isGavel() }
        assertEquals(
            listOf(GavelFrame.raised, GavelFrame.struck, GavelFrame.returning, GavelFrame.rest).map { MockTrialPlayer.Cue.gavel(it) },
            g,
        )
        assertTrue(p.gavel == GavelFrame.rest && p.currentBeat == MockTrialBeat.opening && p.phase == MockTrialPhase.trial)
    }

    // MARK: Autoplay

    @Test fun autoplayWalksEveryBeatToCaseClosedInFortyFiveToSeventySeconds() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        val p = clock.player()
        val beats = mutableListOf<MockTrialBeat>()
        var closedAt: Double? = null
        p.onBeatChange = { b ->
            beats.add(b)
            if (b == MockTrialBeat.closed) closedAt = clock.t
        }
        p.start()
        settle { p.isClosed && !p.isRunning }
        assertEquals(allBeatsAfterOpening, beats)
        assertTrue("CASE CLOSED waits for the user", p.isClosed && !p.isRunning)
        assertTrue(abs((closedAt ?: 0.0) - MockTrialTiming.autoplayTotal) < 0.001)
        assertTrue("START → CASE CLOSED ${MockTrialTiming.fromStartTotal}", MockTrialTiming.fromStartTotal in 45.0..70.0)
        assertTrue("target ≈ 50–60 s", MockTrialTiming.fromStartTotal in 50.0..60.0)
        assertTrue(p.talking == null && p.gavel == GavelFrame.rest && p.crowdLift == 0f && p.winnerLift == 0f)
        assertEquals(1, p.tasksStarted)
        // Every beat's parts all appeared before it moved on.
        var beat = MockTrialBeat.opening
        val shown = mutableMapOf<MockTrialBeat, Int>()
        for ((_, cue) in p.cues) {
            when (cue) {
                is MockTrialPlayer.Cue.beat -> beat = cue.beat
                is MockTrialPlayer.Cue.parts -> shown[beat] = maxOf(shown[beat] ?: 0, cue.n)
                else -> Unit
            }
        }
        for (b in MockTrialBeat.entries) if (b != MockTrialBeat.closed) {
            assertEquals(b.name, MockTrialScript.step(b).parts.size, shown[b])
        }
    }

    @Test fun eachLineTalksAndOnlyTheVerdictStrikesTheGavel() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        val p = clock.player()
        p.start()
        settle { p.isClosed && !p.isRunning }
        var beat = MockTrialBeat.opening
        val mouths = mutableMapOf<MockTrialBeat, MutableSet<MockTrialSpeaker>>()
        val strikes = mutableListOf<MockTrialBeat>()
        val crowd = mutableListOf<MockTrialBeat>()
        for ((_, cue) in p.cues) {
            when {
                cue is MockTrialPlayer.Cue.beat -> beat = cue.beat
                cue is MockTrialPlayer.Cue.mouth && cue.open -> mouths.getOrPut(beat) { mutableSetOf() }.add(cue.speaker)
                cue == MockTrialPlayer.Cue.gavel(GavelFrame.struck) -> strikes.add(beat)
                cue is MockTrialPlayer.Cue.crowd && cue.lift > 0 -> crowd.add(beat)
            }
        }
        for (step in MockTrialScript.steps) {
            assertEquals(step.beat.name, step.lines.map { it.speaker }.toSet(), mouths[step.beat] ?: emptySet<MockTrialSpeaker>())
        }
        assertEquals(listOf(MockTrialBeat.verdict), strikes)
        assertEquals(listOf(MockTrialBeat.verdict), crowd)
        // Gavel: raise → strike → return → rest, in 200–300 ms, after the judge's line and before the card.
        val g = p.cues.filter { it.cue.isGavel() }
        if (g.isNotEmpty()) assertTrue((g.last().at - g.first().at) in 0.2..0.3)
        assertTrue(MockTrialTiming.gavelAt + MockTrialTiming.gavelTotal <= MockTrialTiming.partTimes.getValue(MockTrialBeat.verdict)[1])
    }

    @Test fun judgementSelectsItselfAfterAboutOnePointTwoSeconds() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        val p = clock.player()
        var judgementAt: Double? = null
        p.onBeatChange = { if (it == MockTrialBeat.judgement) judgementAt = clock.t }
        p.start()
        settle { p.isClosed && !p.isRunning }
        val start = judgementAt
        assertNotNull(start)
        val i = p.cues.indexOfFirst { it.cue == MockTrialPlayer.Cue.beat(MockTrialBeat.judgement) }.coerceAtLeast(0)
        val rest = p.cues.drop(i)
        val select = rest.firstOrNull { it.cue == MockTrialPlayer.Cue.parts(2) }
        val ordered = rest.firstOrNull { it.cue == MockTrialPlayer.Cue.parts(3) }
        assertTrue(select != null && ordered != null)
        assertTrue("select at ${select!!.at - start!!}", (select.at - start) in 1.0..1.4)
        assertTrue("So ordered. follows the pick", ordered!!.at > select.at)
        assertEquals(MockTrialPart.select, MockTrialScript.step(MockTrialBeat.judgement).parts[1])
    }

    @Test fun deliberationRowsTickInOverTwoToThreeSeconds() {
        val t = MockTrialTiming.partTimes.getValue(MockTrialBeat.deliberation)
        assertEquals(4, t.size)
        assertTrue(MockTrialTiming.deliberationTicks in 2.0..3.0)
        assertTrue("one row at a time", t.zipWithNext().all { (a, b) -> b - a >= 0.4 })
    }

    // MARK: Tap to advance

    @Test fun tapAdvancesEveryBeatAndADoubleTapIsGuarded() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        clock.suspend = true
        val p = clock.player()
        val fired = mutableListOf<MockTrialBeat>()
        p.onBeatChange = { fired.add(it) }
        startSuspended(p)
        for (expected in allBeatsAfterOpening) {
            // First tap: everything in the beat shows, the beat stays.
            clock.t += 0.3
            val before = p.currentBeat
            assertTrue(p.advance())
            assertTrue("$before", p.currentBeat == before && p.revealComplete && p.partsShown == p.step.parts.size)
            // A tap right behind it is guarded.
            clock.t += 0.1
            assertFalse(p.advance())
            clock.t += 0.3
            assertTrue(p.advance())
            assertEquals(expected, p.currentBeat)
            assertEquals("onAdvance fires on every beat change", expected, fired.last())
        }
        assertEquals(allBeatsAfterOpening, fired)
        // CASE CLOSED: taps do nothing (the CTA continues).
        clock.t += 1; assertFalse(p.advance())
        assertTrue(p.isClosed)
        p.stop()
    }

    @Test fun aTapDuringAutoplayCompletesTheBeatAndKeepsTheRestOfTheDwell() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        val p = clock.player()
        p.autoplay = false
        p.start()
        settle { p.phase == MockTrialPhase.trial }
        p.join()
        // Now autoplay on a fresh beat: tap into plaintiff opening, then tap it complete 0.3 s in.
        clock.suspend = true
        p.autoplay = true
        clock.t += 1
        assertTrue(p.advance()) // → plaintiffOpening
        assertTrue(p.currentBeat == MockTrialBeat.plaintiffOpening && !p.revealComplete)
        settle { false } // the beat's job reaches its (suspended) first sleep
        clock.t += 0.3
        val sleepsBefore = clock.sleeps.size
        assertTrue(p.advance())
        assertTrue(p.currentBeat == MockTrialBeat.plaintiffOpening && p.revealComplete)
        settle { clock.sleeps.size > sleepsBefore }
        assertTrue(
            "the rest of the dwell, not a replay",
            abs((clock.sleeps.lastOrNull() ?: 0.0) - (MockTrialTiming.dwell.getValue(MockTrialBeat.plaintiffOpening) - 0.3)) < 0.001,
        )
        p.stop()
    }

    // MARK: Skip / Continue

    @Test fun skipFromAnyBeatKeepsThatBeatAndCallsTheFlow() = runTest(dispatcher) {
        // From the invitation and the entrance: the flow reports `opening`.
        run {
            val p = Clock(backgroundScope).player()
            var skipped = 0
            p.onSkip = { skipped += 1 }
            p.skip()
            assertTrue(skipped == 1 && p.currentBeat == MockTrialBeat.opening && !p.isRunning)
        }
        run {
            val clock = Clock(backgroundScope)
            clock.suspend = true
            val p = clock.player(entranceHolds = true)
            var skipped = 0
            p.onSkip = { skipped += 1 }
            p.start()
            val wasEntering = p.phase == MockTrialPhase.entrance
            p.skip()
            assertTrue(skipped == 1 && p.currentBeat == MockTrialBeat.opening && !p.isRunning)
            if (wasEntering) assertEquals(CourtEntrancePhase.skipped, p.entrance?.phase)
        }
        // From every beat: onAdvance reported it last, and skip leaves it untouched.
        for (target in MockTrialBeat.entries) {
            val clock = Clock(backgroundScope)
            clock.suspend = true
            val p = clock.player()
            var last = MockTrialBeat.opening
            var skipped = 0
            p.onBeatChange = { last = it }
            p.onSkip = { skipped += 1 }
            startSuspended(p)
            while (p.currentBeat != target) tapToNext(p, clock)
            p.skip()
            assertTrue("$target", skipped == 1 && !p.isRunning)
            assertTrue("skip from $target reports $target", p.currentBeat == target && last == target)
        }
    }

    @Test fun continueOnlyOnCaseClosed() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        clock.suspend = true
        val p = clock.player()
        var continued = 0
        p.onContinue = { continued += 1 }
        startSuspended(p)
        while (!p.isClosed) {
            p.finish()
            assertEquals("${p.currentBeat}", 0, continued)
            tapToNext(p, clock)
        }
        p.finish()
        assertTrue(continued == 1 && !p.isRunning)
    }

    // MARK: Reduce Motion

    @Test fun reduceMotionShowsEachBeatWholeWithNoTalkGavelOrHops() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        val p = clock.player(entranceHolds = true)
        p.reduceMotion = true
        val beats = mutableListOf<MockTrialBeat>()
        val partsAtCommit = mutableMapOf<MockTrialBeat, Int>()
        p.onBeatChange = { b -> beats.add(b); partsAtCommit[b] = p.partsShown }
        p.start()
        assertEquals(true, clock.directors.firstOrNull()?.second)
        assertTrue("straight to the occupied court", p.phase == MockTrialPhase.trial && p.entrance?.phase == CourtEntrancePhase.ready)
        assertTrue(p.entrancePose(MockTrialSpeaker.judge) == CourtEntrancePose.standing && p.entrancePose(MockTrialSpeaker.plaintiff) == CourtEntrancePose.standing)
        assertTrue("session line and claim at once", p.partsShown == 2 && p.revealComplete)
        p.entranceGavel()
        settle { p.isClosed && !p.isRunning }
        assertEquals("still autoplays, fades only", allBeatsAfterOpening, beats)
        for (b in allBeatsAfterOpening) {
            assertEquals("$b shows whole (deliberation rows at once)", MockTrialScript.step(b).parts.size, partsAtCommit[b])
        }
        for ((_, cue) in p.cues) {
            when (cue) {
                is MockTrialPlayer.Cue.mouth, is MockTrialPlayer.Cue.gavel, is MockTrialPlayer.Cue.crowd, is MockTrialPlayer.Cue.winner,
                is MockTrialPlayer.Cue.parts, MockTrialPlayer.Cue.revealed,
                -> fail("staged cue under Reduce Motion: $cue")
                is MockTrialPlayer.Cue.beat, MockTrialPlayer.Cue.entered, MockTrialPlayer.Cue.entranceStarted, MockTrialPlayer.Cue.entranceReady -> Unit
            }
        }
        for (beat in MockTrialBeat.entries) assertTrue(p.choreography(beat).isEmpty())
        // Only the dwells are waited.
        assertEquals(MockTrialBeat.entries.mapNotNull { MockTrialTiming.dwell[it] }, clock.sleeps)
    }

    // MARK: Lifecycle

    @Test fun stopCancelsTheTaskAndRests() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        clock.suspend = true
        val p = clock.player()
        startSuspended(p)
        settle { clock.sleeps.isNotEmpty() }
        assertTrue(p.isRunning)
        p.stop()
        settle { clock.cancelledSleeps == 1 }
        assertTrue(!p.isRunning && clock.cancelledSleeps == 1)
        assertTrue(p.talking == null && p.gavel == GavelFrame.rest && p.currentBeat == MockTrialBeat.opening)
        p.stop()
        assertFalse(p.isRunning)
    }

    @Test fun backgroundPausesAndForegroundResumesTheSameBeat() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        clock.suspend = true
        val p = clock.player()
        startSuspended(p)
        settle { clock.sleeps.isNotEmpty() }
        val base = clock.cancelledSleeps
        clock.t += 1
        p.setActive(false)
        settle { clock.cancelledSleeps == base + 1 }
        assertTrue(!p.isRunning && p.revealComplete)
        p.setActive(true)
        assertTrue(p.isRunning && p.currentBeat == MockTrialBeat.opening)
        p.stop()
    }

    @Test fun voiceOverWaitsForTheUserOnEachBeat() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        val p = clock.player()
        p.autoplay = false
        p.start()
        settle { p.phase == MockTrialPhase.trial }
        p.join()
        assertTrue("the parts still appear; no advance", p.currentBeat == MockTrialBeat.opening && !p.isRunning && p.revealComplete)
        clock.t += 1
        assertTrue(p.advance())
        p.join()
        assertTrue(p.currentBeat == MockTrialBeat.plaintiffOpening && p.revealComplete)
        clock.t += 1
        assertTrue(p.advance())
        p.join()
        assertEquals(MockTrialBeat.plaintiffEvidence, p.currentBeat)
    }

    // MARK: Phase help sheet (amendment ae)

    @Test fun helpCopyIsTheMockCopy() {
        val c = CourtHelp.content(CourtHelpTopic.mockOpeningStatement)
        assertEquals("What's an opening statement?", c.title)
        assertEquals(
            "This is where a side briefly tells the judge what happened and what they want. Sam goes first in this demo. Just watch how the trial unfolds.",
            c.body,
        )
        assertTrue(c.example == null && c.button == "Got it")
        assertEquals("What is an opening statement?", c.accessibilityQuestion)
    }

    @Test fun theHelpButtonIsBesideTheOpeningStatementLabelsOnly() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        clock.suspend = true
        val p = clock.player()
        assertFalse(p.helpAvailable)
        startSuspended(p)
        val offered = mutableListOf<MockTrialBeat>()
        while (true) {
            if (p.helpAvailable) offered.add(p.currentBeat)
            if (p.isClosed) break
            tapToNext(p, clock)
        }
        assertEquals(listOf(MockTrialBeat.plaintiffOpening, MockTrialBeat.defendantOpening), offered)
        assertEquals(setOf(MockTrialBeat.plaintiffOpening, MockTrialBeat.defendantOpening), MockTrialPlayer.helpBeats)
        assertTrue(MockTrialPlayer.helpBeats.all { MockTrialScript.step(it).label?.startsWith("OPENING STATEMENT") == true })
        p.stop()
    }

    @Test fun helpPausesAndResumesWithTheRestOfTheDwell() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        clock.suspend = true
        val p = clock.player()
        val fired = mutableListOf<MockTrialBeat>()
        p.onBeatChange = { fired.add(it) }
        startSuspended(p)
        tapToNext(p, clock)
        assertEquals(MockTrialBeat.plaintiffOpening, p.currentBeat)
        settle { p.isRunning && clock.sleeps.isNotEmpty() }
        settle { false }
        val tasks = p.tasksStarted
        clock.t += 1
        p.pauseForHelp()
        assertTrue(p.pausedForHelp && !p.isRunning)
        assertTrue("Sam's line complete, everyone at rest", p.revealComplete && p.partsShown == 1 && p.talking == null)
        clock.t += 30
        assertFalse(p.advance())
        p.setActive(false); p.setActive(true)
        p.start()
        assertTrue(!p.isRunning && p.currentBeat == MockTrialBeat.plaintiffOpening && p.tasksStarted == tasks)
        val before = clock.sleeps.size
        val cuesBefore = p.cues.size
        p.resumeFromHelp()
        assertTrue(!p.pausedForHelp && p.isRunning)
        settle { clock.sleeps.size > before }
        assertTrue(abs((clock.sleeps.lastOrNull() ?: 0.0) - (MockTrialTiming.dwell.getValue(MockTrialBeat.plaintiffOpening) - 1)) < 0.001)
        assertFalse(
            "no talk loop, reveal or entrance replay",
            p.cues.drop(cuesBefore).any { c ->
                c.cue is MockTrialPlayer.Cue.mouth || c.cue == MockTrialPlayer.Cue.entered || c.cue == MockTrialPlayer.Cue.entranceReady ||
                    c.cue is MockTrialPlayer.Cue.parts || c.cue == MockTrialPlayer.Cue.revealed
            },
        )
        assertEquals(listOf(MockTrialBeat.plaintiffOpening), fired)
        // Carried on without the sheet, autoplay still reaches CASE CLOSED.
        clock.suspend = false
        p.stop(); p.start()
        settle { p.isClosed && !p.isRunning }
        assertEquals(allBeatsAfterOpening, fired)
    }

    // MARK: Capture harness

    @Test fun captureHarnessHoldsAnyBeatSettled() = runTest(dispatcher) {
        for (beat in MockTrialBeat.entries) {
            val clock = Clock(backgroundScope)
            val p = clock.player()
            p.debugHold(beat)
            assertTrue(p.phase == MockTrialPhase.trial && p.entered && !p.autoplay && p.currentBeat == beat)
            assertTrue(p.partsShown == MockTrialScript.step(beat).parts.size && p.revealComplete)
            p.start()
            p.join()
            assertTrue("$beat holds, no entrance", p.currentBeat == beat && clock.directors.isEmpty())
            p.stop()
        }
        val q = Clock(backgroundScope).player()
        q.debugStartEntrance()
        assertTrue(q.phase != MockTrialPhase.invitation && !q.autoplay)
        settle { q.phase == MockTrialPhase.trial }
        q.join()
        assertTrue("the entrance plays, then the opening holds", q.currentBeat == MockTrialBeat.opening && q.partsShown == 2)
        q.stop()
    }

    // MARK: Layout

    @Test fun slotsSitClearOfTheJudgeTheTagsAndEachOther() {
        for (size in listOf(Size(370f, 600f), Size(358f, 575f), Size(390f, 640f))) {
            val l = MockTrialLayout(size)
            val judge = l.zones.judgeFrame
            val tagTop = CourtPodiumParty.tagCenter(l.zones, Role.plaintiff).y - MockTrialLayout.tagHalfHeight
            assertTrue("$size", l.topBand.bottom <= judge.top)
            assertTrue("$size: room for a phase plate and a two-line question", l.topBand.height >= 110)
            assertTrue("$size", l.easelRect.top >= judge.bottom && l.easelRect.bottom <= tagTop)
            assertTrue("$size", l.easelRect.height >= 110 && l.easelRect.width >= 150)
            assertTrue("$size", l.lowerBand.top > tagTop && l.lowerBand.bottom <= size.height)
            assertTrue("$size: room for the verdict card", l.lowerBand.height >= 120)
            assertTrue("$size", l.judgementRect.bottom <= size.height && l.judgementRect.height >= 200)
            assertTrue(l.tailX(Role.plaintiff) < 0.5f && l.tailX(Role.defendant) > 0.5f)
        }
    }

    @Test fun crossExaminationCarriesThePreviousPairFadedBack() {
        val St = MockTrialStage
        val lowerSlot = MockTrialStage.Slot.lower
        val topSlot = MockTrialStage.Slot.top
        // Q2 is up, A2 not yet: A1 stays under Alex's podium, faded.
        val lower = St.lines(lowerSlot, MockTrialBeat.crossExamination2, 1)
        assertTrue(lower.map { it.line } == listOf(S.crossExamination[0].answer) && lower.all { !it.current })
        val top = St.lines(topSlot, MockTrialBeat.crossExamination2, 1)
        assertTrue(top.map { it.line } == listOf(S.crossExamination[1].question) && top.all { it.current })
        // Once A2 is up it replaces A1.
        assertEquals(listOf(S.crossExamination[1].answer), St.lines(lowerSlot, MockTrialBeat.crossExamination2, 2).map { it.line })
        // The follow-up keeps Q3 faded above Sam's line.
        assertEquals(listOf(false), St.lines(topSlot, MockTrialBeat.crossFollowUp, 1).map { it.current })
        // Outside cross-examination a beat starts clean.
        assertTrue(St.lines(topSlot, MockTrialBeat.plaintiffOpening, 1).isEmpty())
        assertTrue(St.lines(lowerSlot, MockTrialBeat.plaintiffEvidence, 2).isEmpty())
        assertTrue(St.lines(topSlot, MockTrialBeat.crossExamination1, 0).isEmpty())
        // Closings: both parties at once.
        assertEquals(
            listOf(MockTrialSpeaker.plaintiff, MockTrialSpeaker.defendant),
            St.lines(lowerSlot, MockTrialBeat.closings, 2).map { it.line.speaker },
        )
    }

    @Test fun emphasisFollowsWhoeverHoldsTheFloor() = runTest(dispatcher) {
        val clock = Clock(backgroundScope)
        clock.suspend = true
        val p = clock.player()
        startSuspended(p)
        val seen = mutableMapOf<MockTrialBeat, MockTrialSpeaker?>()
        while (!p.isClosed) {
            clock.t += 0.3
            if (!p.revealComplete) p.advance()
            seen[p.currentBeat] = p.activeSpeaker
            clock.t += 0.3
            p.advance()
        }
        assertEquals(MockTrialSpeaker.plaintiff, seen[MockTrialBeat.plaintiffOpening])
        assertEquals(MockTrialSpeaker.defendant, seen[MockTrialBeat.defendantOpening])
        assertEquals("the answer is the latest part", MockTrialSpeaker.defendant, seen[MockTrialBeat.crossExamination1])
        assertEquals(MockTrialSpeaker.plaintiff, seen[MockTrialBeat.crossFollowUp])
        assertTrue(seen.containsKey(MockTrialBeat.deliberation) && seen[MockTrialBeat.deliberation] == null)
        assertTrue(p.plaintiffWon && p.activeSpeaker == null)
        p.stop()
    }

    @Test fun motionTokensSitInsideTheBriefRanges() {
        val T = MockTrialTiming
        assertTrue(T.sceneSettle in 0.25..0.35)
        assertTrue(T.invitationFade in 0.1..0.2)
        assertTrue(T.bubbleEntrance in 0.18..0.25)
        assertEquals(0.96f, T.bubbleScale)
        assertTrue(T.lineStagger in 0.06..0.1)
        assertTrue(T.gavelTotal in 0.2..0.3)
        assertTrue(T.verdictCard in 0.25..0.4)
        assertTrue(T.exhibitDuration in 0.25..0.4)
        assertTrue(T.crowdReaction < 0.3)
        assertTrue(T.commitWindow in 0.2..0.3)
    }
}
