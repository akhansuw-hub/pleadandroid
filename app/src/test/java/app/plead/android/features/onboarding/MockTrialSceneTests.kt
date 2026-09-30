// Partial port of ArgueWinTests/MockTrialSceneTests.swift: the script-level tests (intro, script, beats, names,
// TalkBack text) plus two for the interim player. The player / stage / motion tests are ported with the full
// MockTrialPlayer after the wave 3a merge (PORT.md §7 assigns them to 3a); `revealedAt` needs `CourtRevealPlan`.
package app.plead.android.features.onboarding

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MockTrialSceneTests {
    private val S = MockTrialScript
    private fun line(s: MockTrialSpeaker, t: String) = MockTrialLine(s, t)

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

    // MARK: Interim player

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun theInvitationWaitsForStartThenAutoplayWalksToCaseClosed() {
        val scope = TestScope(StandardTestDispatcher())
        var clock = 0.0
        val beats = mutableListOf<MockTrialBeat>()
        val player = MockTrialPlayer(scope = scope, now = { clock })
        player.onBeatChange = { beats += it }
        scope.advanceTimeBy(60_000)
        assertTrue(player.isInvitation)
        assertFalse(player.courtShown)
        player.start()
        scope.runCurrent()
        assertEquals(MockTrialBeat.opening, player.currentBeat)
        clock = 100.0
        scope.advanceTimeBy(((MockTrialTiming.autoplayTotal + 1) * 1000).toLong())
        assertTrue(player.isClosed)
        assertEquals(MockTrialBeat.entries.drop(1), beats)
        player.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun tapCompletesThenAdvancesAndADoubleTapIsGuarded() {
        val scope = TestScope(StandardTestDispatcher())
        var clock = 0.0
        val player = MockTrialPlayer(scope = scope, now = { clock })
        player.autoplay = false
        player.start()
        scope.runCurrent()
        assertFalse(player.revealComplete)
        player.advance() // completes the opening
        assertTrue(player.revealComplete)
        assertEquals(MockTrialBeat.opening, player.currentBeat)
        player.advance() // moves on
        assertEquals(MockTrialBeat.plaintiffOpening, player.currentBeat)
        clock += 0.1
        player.advance() // inside the commit window: ignored
        assertEquals(MockTrialBeat.plaintiffOpening, player.currentBeat)
        assertFalse(player.revealComplete)
        var skipped = false
        player.onSkip = { skipped = true }
        player.skip()
        assertTrue(skipped)
        assertFalse(player.isRunning)
    }
}
