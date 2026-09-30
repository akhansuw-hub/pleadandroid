// Port of ArgueWinTests/CourtMotionTests.swift: the courtroom motion system (CONTRACTS-v2 amendment x): state
// derivation, the one shared clock, reveal timings, gavel rules, Reduce Motion, cancellation, analytics hygiene.
package app.plead.android.courtroom

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import app.plead.android.models.ExhibitType
import app.plead.android.models.JudgePersona
import app.plead.android.models.ObjectionRuling
import app.plead.android.models.Role
import app.plead.android.models.Speaker
import app.plead.android.models.Turn
import app.plead.android.services.PreviewData
import app.plead.android.services.UserDefaults
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CourtMotionTests {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Fake clock: sleeps advance `t` at once (after a yield) and end the loop past `until`. */
    class Clock {
        var t: Double = 100.0
        var until: Double = 130.0
        val sleeps = mutableListOf<Double>()
        var suspend = false
        var cancelled = 0

        fun director(random: (ClosedFloatingPointRange<Double>) -> Double = ::randomIn): CourtMotionDirector =
            CourtMotionDirector(
                sleep = { d ->
                    sleeps.add(d)
                    if (suspend) {
                        try {
                            awaitCancellation()
                        } catch (e: CancellationException) {
                            cancelled += 1; throw e
                        }
                    }
                    yield()
                    t += d
                    if (t > until) throw CancellationException("past until")
                },
                now = { t },
                random = random,
                memory = CourtRevealMemory(),
            )
    }

    private fun turn(i: Int): Turn = CourtFixtures.allTurns[i]
    private fun model(i: Int, s: CourtroomState = CourtFixtures.crossExam) = CourtBubbleModel(turn(i), s)

    // MARK: State model

    @Test fun phaseFollowsTheCase() {
        assertEquals(CourtPhase.opening, CourtPhase.derive(CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffOpening, owner = Role.plaintiff, turns = 1)))
        assertEquals(CourtPhase.testimony, CourtPhase.derive(CourtFixtures.waitingForPartner))
        assertEquals(CourtPhase.evidence, CourtPhase.derive(CourtFixtures.presentExhibits))
        assertEquals(CourtPhase.evidence, CourtPhase.derive(CourtFixtures.objectionWindow))
        assertEquals(CourtPhase.crossExamination, CourtPhase.derive(CourtFixtures.crossExam))
        assertEquals(CourtPhase.testimony, CourtPhase.derive(CourtFixtures.closing))
        assertEquals(CourtPhase.deliberation, CourtPhase.derive(CourtFixtures.deliberating))
        assertEquals(CourtPhase.deliberation, CourtPhase.derive(CourtFixtures.awaitingVerdict))
        assertEquals(CourtPhase.verdict, CourtPhase.derive(CourtFixtures.verdictIn))
    }

    @Test fun directorDerivesFloorAndJudge() {
        val d = Clock().director()
        d.update(CourtFixtures.waitingForPartner)
        assertTrue(d.phase == CourtPhase.testimony && d.floor == Role.defendant)
        assertTrue(d.isCalm(Role.plaintiff) && !d.isCalm(Role.defendant))
        assertTrue(d.judgeState == JudgeState.idle && d.plaintiffState == CharacterState.idle && d.defendantState == CharacterState.idle)
        d.update(CourtFixtures.deliberating)
        assertTrue(d.phase == CourtPhase.deliberation && d.judgeState == JudgeState.deliberating && d.floor == null)
        d.update(CourtFixtures.verdictIn)
        assertTrue(d.phase == CourtPhase.verdict && d.judgeState == JudgeState.idle)
    }

    @Test fun speakingThenIdleAroundTheReveal() = runTest(dispatcher) {
        val c = Clock()
        c.until = 105.0
        val d = c.director()
        d.update(CourtFixtures.waitingForPartner)
        d.appear(analytics = false)
        val plan = d.claimReveal(model(3, CourtFixtures.waitingForPartner), turns = CourtFixtures.waitingForPartner.turns)
        assertNotNull(plan)
        assertEquals(CharacterState.idle, d.defendantState) // applied by the clock, not synchronously
        var sawSpeaking = false
        var sawMouth = false
        d.onBeat = { _, _ -> }
        // Drive the clock step by step, sampling states.
        val task: Job = launch { d.join() }
        repeat(400) {
            yield()
            if (d.defendantState == CharacterState.speaking) sawSpeaking = true
            if (d.defendantPose.mouthOpen) sawMouth = true
        }
        task.join()
        assertTrue(sawSpeaking && sawMouth)
        assertTrue(d.defendantState == CharacterState.idle && !d.defendantPose.mouthOpen)
        assertTrue(d.plaintiffState != CharacterState.speaking)
    }

    // MARK: Events & the gavel

    @Test fun gavelOnlyForOpeningsRulingsTransitionsAndVerdicts() {
        val turns = CourtFixtures.allTurns
        fun e(i: Int) = CourtMotionEvent.classify(turns[i], turns)
        assertEquals(CourtMotionEvent.opening, e(0))                          // "Order! … now in session"
        assertEquals(CourtMotionEvent.partyLine(Role.plaintiff), e(1))
        assertEquals(CourtMotionEvent.judgeLine, e(2))                        // same group (openings): no gavel
        assertEquals(CourtMotionEvent.majorTransition, e(4))                  // openings → exhibits
        assertEquals(CourtMotionEvent.objection(Role.defendant), e(6))
        assertEquals(CourtMotionEvent.ruling(ObjectionRuling.overruled), e(7))
        assertEquals(CourtMotionEvent.pass(Role.defendant), e(9))
        assertEquals(CourtMotionEvent.judgeLine, e(11))                       // defendant's exhibits: same group
        assertEquals(CourtMotionEvent.ruling(ObjectionRuling.sustained), e(14))
        assertEquals(CourtMotionEvent.majorTransition, e(16))                 // exhibits → cross-examination
        assertEquals(CourtMotionEvent.crossQuestions, e(18))
        assertEquals(CourtMotionEvent.majorTransition, e(20))                 // → closings
        assertEquals(CourtMotionEvent.majorTransition, e(23))                 // court adjourned
        val gavels = turns.indices.filter { e(it).triggersGavel }
        assertEquals(listOf(0, 4, 7, 14, 16, 20, 23), gavels)
        for (i in turns.indices) if (turns[i].speaker != Speaker.judge) assertFalse(e(i).triggersGavel)
        assertTrue(CourtMotionEvent.verdict.triggersGavel && CourtMotionEvent.verdict.triggersCrowd)
        assertTrue(!CourtMotionEvent.crossQuestions.triggersGavel && !CourtMotionEvent.judgeLine.triggersGavel)
    }

    @Test fun clockStrikesOnlyOnAllowedEvents() = runTest(dispatcher) {
        val s = CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffExhibits, owner = Role.plaintiff, turns = 8)
        // Party line + objection + a plain judge line: no strike, ambient idles never strike either.
        val c = Clock()
        c.until = 125.0
        val d = c.director()
        d.update(s)
        d.appear(analytics = false)
        d.claimReveal(model(5, s), turns = s.turns)
        d.claimReveal(model(6, s), turns = s.turns)
        d.claimReveal(model(2, s), turns = s.turns)
        d.join()
        assertEquals(0, d.gavelStrikes)

        // A ruling: exactly one strike, and the gavel is back at rest.
        val c2 = Clock()
        c2.until = 105.0
        val d2 = c2.director()
        d2.update(s)
        d2.appear(analytics = false)
        d2.claimReveal(model(7, s), turns = s.turns)
        d2.join()
        assertEquals(1, d2.gavelStrikes)
        assertTrue(d2.gavel == GavelFrame.rest && d2.judgeState == JudgeState.idle)
        // Claiming the same turn again (a revisit) never strikes twice.
        assertNull(d2.claimReveal(model(7, s), turns = s.turns))
    }

    @Test fun gavelSwingTiming() {
        val t = CourtMotionTiming.gavelTotal
        assertTrue(t >= 0.2 && t <= 0.3)
        assertTrue(CourtMotionTiming.gavelRaise > 0 && CourtMotionTiming.gavelHold > 0 && CourtMotionTiming.gavelReturn > 0)
    }

    // MARK: Ambient clock

    @Test fun schedulerSpacingAndStagger() {
        val randoms: List<(ClosedFloatingPointRange<Double>) -> Double> = listOf({ r -> r.start }, { r -> r.endInclusive }, ::randomIn)
        for (random in randoms) {
            val actors = CourtActor.entries.toList()
            val s = CourtAmbientScheduler(start = 0.0, actors = actors, random = random)
            val firsts = actors.map { s.next.getValue(it) }
            // Staggered: no two characters start together.
            for ((a, b) in firsts.zipWithNext()) assertTrue(b - a >= CourtMotionTiming.ambientStagger - 0.3 - 0.0001)
            val last = mutableMapOf<CourtActor, Double>()
            var t = 0.0
            while (t < 120) {
                t = s.earliest!!
                for (a in s.popDue(now = t, spacing = { 2.5..5.0 }, random = random)) {
                    val prev = last[a]
                    if (prev != null) assertTrue("$a spacing ${t - prev}", t - prev >= 2.5 - 0.0001 && t - prev <= 5 + 0.0001)
                    last[a] = t
                }
            }
            assertEquals(actors.size, last.size)
        }
    }

    @Test fun phaseEnergyStaysInsideTheBand() {
        for (p in CourtPhase.entries) {
            for (r in listOf(p.crowdSpacing, p.judgeSpacing)) {
                assertTrue(r.start >= CourtMotionTiming.ambientSpacing.start)
                assertTrue(r.endInclusive <= CourtMotionTiming.ambientSpacing.endInclusive)
            }
        }
        assertTrue(CourtPhase.deliberation.crowdSpacing.start > CourtPhase.verdict.crowdSpacing.start)
    }

    @Test fun directorClockBeatsAreSpacedAndStaggered() = runTest(dispatcher) {
        val c = Clock()
        c.until = 160.0
        val d = c.director()
        d.update(CourtFixtures.waitingForPartner)
        val beats = mutableMapOf<CourtActor, MutableList<Double>>()
        d.onBeat = { a, t -> beats.getOrPut(a) { mutableListOf() }.add(t) }
        d.appear(analytics = false)
        assertTrue(d.isRunning)
        d.join()
        assertEquals(7, beats.size)
        val firsts = beats.values.mapNotNull { it.firstOrNull() }.sorted()
        assertEquals(firsts.size, firsts.map { Math.round(it * 1000) }.toSet().size)
        for ((a, ts) in beats) {
            for ((x, y) in ts.zipWithNext()) assertTrue("$a ${y - x}", y - x >= 2.5 - 0.01 && y - x <= 5 + 0.01)
        }
        // Idles are small: never more than the five figures moving at once.
        assertTrue(d.peakConcurrentMotions <= CourtMotionTiming.maxConcurrentMotions)
    }

    @Test fun busyCourtStaysWithinTheAnimationBudget() = runTest(dispatcher) {
        val s = CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffExhibits, owner = Role.plaintiff, turns = 8)
        val c = Clock()
        c.until = 140.0
        val d = c.director()
        d.update(s)
        d.appear(analytics = false)
        for (i in listOf(4, 5, 6, 7)) d.claimReveal(model(i, s), turns = s.turns)
        d.exhibitRevealed(CourtFixtures.exA, live = true)
        d.join()
        assertTrue(d.peakConcurrentMotions >= 2)
        assertTrue(d.peakConcurrentMotions <= CourtMotionTiming.maxConcurrentMotions)
        // The only long-lived work is the one clock job (each restart replaces it; it ends with the scene).
        assertFalse(d.isRunning)
        assertTrue(d.tasksStarted >= 1)
    }

    @Test fun stopCancelsTheClock() = runTest(dispatcher) {
        val c = Clock()
        c.suspend = true
        val d = c.director()
        d.update(CourtFixtures.crossExam)
        d.appear(analytics = false)
        repeat(50) { if (c.sleeps.isEmpty()) yield() }
        assertTrue(d.isRunning && c.sleeps.size == 1)
        d.disappear()
        assertFalse(d.isRunning)
        repeat(50) { if (c.cancelled == 0) yield() }
        assertEquals(1, c.cancelled)
        assertTrue(d.judgePose.isRest && d.gavel == GavelFrame.rest)
    }

    @Test fun backgroundPausesTheClock() {
        val c = Clock()
        c.suspend = true
        val d = c.director()
        d.appear(analytics = false)
        assertTrue(d.isRunning)
        d.setActive(false)
        assertFalse(d.isRunning)
        d.setActive(true)
        assertTrue(d.isRunning)
        d.disappear()
        assertFalse(d.isRunning)
    }

    // MARK: Reduce Motion

    @Test fun reduceMotionNeverTalksStrikesOrBounces() = runTest(dispatcher) {
        val s = CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffExhibits, owner = Role.plaintiff, turns = 8)
        val c = Clock()
        val d = c.director()
        d.reduceMotion = true
        d.update(s)
        d.appear(analytics = false)
        assertFalse(d.isRunning)
        for (i in listOf(0, 4, 5, 6, 7)) assertNull(d.claimReveal(model(i, s), turns = s.turns))
        d.playVerdictOpening()
        d.playVerdictOutcome(winner = Role.plaintiff, tie = false)
        assertFalse(d.isRunning)
        repeat(50) { yield() }
        assertTrue(d.gavelStrikes == 0 && d.gavel == GavelFrame.rest)
        assertTrue(d.judgeState != JudgeState.gavel && d.judgePose.isRest && d.plaintiffPose.isRest && d.defendantPose.isRest)
        assertTrue(d.crowdLift.all { it == 0f })
        assertFalse(d.stampShown(key = "x", text = "SUSTAINED"))
        assertFalse(d.exhibitRevealed(CourtFixtures.exB, live = true))
    }

    @Test fun turningOnReduceMotionStopsAndSettles() {
        val c = Clock()
        c.suspend = true
        val d = c.director()
        val s = CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffExhibits, owner = Role.plaintiff, turns = 8)
        d.update(s)
        d.appear(analytics = false)
        d.claimReveal(model(7, s), turns = s.turns)
        assertTrue(d.isRunning)
        d.reduceMotion = true
        assertTrue(!d.isRunning && d.gavel == GavelFrame.rest && d.judgeState == JudgeState.idle)
    }

    // MARK: Reveal timings

    @Test fun revealTimingsWithinTheAmendment() {
        val p = CourtRevealPlan.make(
            body = "Order! Case #14, the Thermostat Incident, is now in session. The court notes the room is a balmy 26 degrees.",
            questions = emptyList(),
        )
        assertTrue(p.entrance >= 0.18 && p.entrance <= 0.25)
        assertEquals(0.96f, p.entranceScale)
        assertTrue(p.entranceRise >= 4 && p.entranceRise <= 8)
        assertTrue(p.bodyDelay >= 0.08 && p.bodyDelay <= 0.12)
        assertTrue(p.lineStagger >= 0.06 && p.lineStagger <= 0.1)
        assertTrue(p.lineRise >= 2 && p.lineRise <= 4)
        assertEquals(listOf(4), p.lineCounts)
        // The whole message is readable quickly.
        assertTrue(p.total <= 0.7)
        assertTrue(p.talkDuration >= CourtMotionTiming.talkMin && p.talkDuration <= CourtMotionTiming.talkMax)
        assertTrue(CourtMotionTiming.exhibitDuration >= 0.25 && CourtMotionTiming.exhibitDuration <= 0.4)
        assertTrue(CourtMotionTiming.exhibitRise >= 8 && CourtMotionTiming.exhibitRise <= 16)
        assertTrue(CourtMotionTiming.exhibitLabelDelay > 0 && CourtMotionTiming.exhibitLabelDelay < CourtMotionTiming.exhibitDuration)
        assertTrue(CourtMotionTiming.stamp >= 0.18 && CourtMotionTiming.stamp <= 0.26 && CourtMotionTiming.stampFromScale > 1)
        assertTrue(CourtMotionTiming.stateTransition >= 0.25 && CourtMotionTiming.stateTransition <= 0.35)
        assertTrue(CourtMotionTiming.characterChange >= 0.18 && CourtMotionTiming.characterChange <= 0.3)
    }

    @Test fun crossExaminationQuestionsRevealOneAfterAnother() {
        val m = model(16)
        val p = CourtRevealPlan.make(m, lineLimit = 3)
        assertEquals(3, p.lineCounts.size)
        val starts = (0 until 3).map { p.start(it) }
        assertTrue(starts == starts.sorted() && starts.toSet().size == 3)
        assertTrue(starts[0] == p.bodyDelay)
        assertTrue(p.total <= 1.0)
        // Question 2 is still hidden when question 1 has started, and fully in by the end.
        assertTrue(CourtRevealPlan.lineProgress(elapsed = starts[0] + 0.01, start = starts[1], stagger = p.lineStagger, duration = p.lineDuration, line = 0) == 0.0)
        assertTrue(CourtRevealPlan.lineProgress(elapsed = p.total, start = starts[2], stagger = p.lineStagger, duration = p.lineDuration, line = 1) == 1.0)
    }

    @Test fun lineProgressIsLineByLine() {
        val p = CourtRevealPlan()
        fun at(t: Double, line: Int) =
            CourtRevealPlan.lineProgress(elapsed = t, start = p.bodyDelay, stagger = p.lineStagger, duration = p.lineDuration, line = line)
        assertTrue(at(0.0, 0) == 0.0) // the header first, dialogue after
        assertTrue(at(p.bodyDelay + 0.05, 0) > 0 && at(p.bodyDelay + 0.05, 1) == 0.0)
        assertTrue(at(p.bodyDelay + p.lineDuration, 0) == 1.0)
        var prev = 0.0
        for (i in 0..40) {
            val v = at(i * 0.01, 0); assertTrue(v >= prev); prev = v
        }
        assertEquals(1, CourtRevealPlan.estimatedLines("", charsPerLine = 30))
        assertEquals(1, CourtRevealPlan.estimatedLines("one two three", charsPerLine = 30))
        assertEquals(5, CourtRevealPlan.estimatedLines("word ".repeat(30), charsPerLine = 30))
    }

    @Test fun revealIsClaimedOncePerLaunch() {
        val memory = CourtRevealMemory()
        val d1 = CourtMotionDirector(memory = memory)
        val d2 = CourtMotionDirector(memory = memory)
        val s = CourtFixtures.crossExam
        assertFalse(d1.hasRevealed(turn(16).id))
        assertNotNull(d1.claimReveal(model(16), turns = s.turns))
        assertTrue(d2.hasRevealed(turn(16).id))
        assertNull(d2.claimReveal(model(16), turns = s.turns))
        assertTrue(d1.claimPulse("a") && !d2.claimPulse("a"))
        assertTrue(d1.stampShown(key = "k", text = "OBJECTION") && !d2.stampShown(key = "k", text = "OBJECTION"))
        d1.disappear(); d2.disappear()
    }

    // MARK: Analytics

    @Test fun analyticsNeverCarryContent() = runTest(dispatcher) {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        val saved = CourtMotionAnalytics.sink
        CourtMotionAnalytics.sink = { e, p -> events.add(e to p) }
        try {
            val s = CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffExhibits, owner = Role.plaintiff, turns = 8)
            val c = Clock()
            c.until = 104.0
            val d = c.director()
            d.update(s)
            d.appear()
            for (i in listOf(4, 5, 6, 7)) d.claimReveal(model(i, s), turns = s.turns)
            d.exhibitRevealed(CourtFixtures.exA, live = true)
            d.stampShown(key = "a-overruled", text = "OVERRULED")
            d.playVerdictOpening()
            d.playVerdictOutcome(winner = Role.plaintiff, tie = false)
            d.join()
            d.disappear()

            val names = events.map { it.first }.toSet()
            for (e in listOf(
                CourtMotionAnalytics.Event.screenOpened, CourtMotionAnalytics.Event.dialogueRevealStarted,
                CourtMotionAnalytics.Event.dialogueRevealCompleted, CourtMotionAnalytics.Event.exhibitRevealed,
                CourtMotionAnalytics.Event.objectionShown, CourtMotionAnalytics.Event.verdictRevealStarted,
                CourtMotionAnalytics.Event.verdictRevealCompleted, CourtMotionAnalytics.Event.timeToFirstDialogueVisible,
            )) {
                assertTrue("missing ${e.rawValue}", names.contains(e.rawValue))
            }
            assertEquals(4, events.count { it.first == "dialogue_reveal_started" })
            assertEquals(4, events.count { it.first == "dialogue_reveal_completed" })
            // Values are enums, counts or durations only: never words from the dialogue or the exhibits.
            val vocabulary: Set<String> = CourtPhase.entries.map { it.rawValue }.toSet() + setOf(
                "judge", "plaintiff", "defendant", "tie", "none", "live", "mini", "0", "1",
                "objection", "sustained", "overruled", "opening", "ruling", "transition", "verdict", "judge_line",
                "cross_questions", "party_line", "pass",
            ) + ExhibitType.entries.map { it.rawValue }
            for ((_, props) in events) {
                assertTrue(props.keys.all { it in CourtMotionAnalytics.allowedKeys })
                for (v in props.values) assertTrue("unexpected value: $v", v.all { it.isDigit() } || v in vocabulary)
            }

            // Reduce Motion reports itself on open.
            events.clear()
            val rm = Clock().director()
            rm.reduceMotion = true
            rm.appear()
            assertEquals(listOf("courtroom_screen_opened", "reduced_motion_active"), events.map { it.first })
            rm.disappear()
        } finally {
            CourtMotionAnalytics.sink = saved
        }
    }

    // MARK: Sprites & crops

    @Test fun judgeFramesOnlyTouchEyesAndMouth() {
        for (p in JudgePersona.entries) {
            val base = JudgeSprite.rows(p, eyesClosed = false, mouthOpen = false)
            val blink = JudgeSprite.rows(p, eyesClosed = true, mouthOpen = false)
            val talk = JudgeSprite.rows(p, eyesClosed = false, mouthOpen = true)
            assertTrue(base.size == blink.size && base.size == talk.size)
            var blinkDiff = 0
            var talkDiff = 0
            for (r in base.indices) {
                for (c in base[r].indices) {
                    if (base[r][c] != blink[r][c]) {
                        blinkDiff += 1; assertTrue(base[r][c] == 'E' && blink[r][c] == 'S')
                    }
                    if (base[r][c] != talk[r][c]) {
                        talkDiff += 1; assertTrue(talk[r][c] == 'o' && (c == 7 || c == 8))
                    }
                }
            }
            assertEquals("$p talk frame", 2, talkDiff)
            if (p == JudgePersona.wigsworth || p == JudgePersona.sunny || p == JudgePersona.blunt) assertEquals("$p blink frame", 2, blinkDiff)
        }
    }

    @Test fun avatarFramesOnlyTouchEyesAndMouth() {
        for (a in listOf(PreviewData.me.avatar, PreviewData.partner.avatar, CourtFixtures.aria.avatar, CourtFixtures.sam.avatar)) {
            val base = CourtAvatarSprite.grid(a, eyesClosed = false, mouthOpen = false)
            val blink = CourtAvatarSprite.grid(a, eyesClosed = true, mouthOpen = false)
            val talk = CourtAvatarSprite.grid(a, eyesClosed = false, mouthOpen = true)
            val bd = mutableListOf<List<Int>>()
            val td = mutableListOf<List<Int>>()
            for (r in base.indices) for (c in base[r].indices) {
                if (base[r][c] != blink[r][c]) bd.add(listOf(r, c))
                if (base[r][c] != talk[r][c]) td.add(listOf(r, c))
            }
            assertEquals(listOf(listOf(6, 6), listOf(6, 9)), bd)
            assertEquals(listOf(listOf(9, 7), listOf(9, 8)), td)
        }
    }

    private fun Rect.containsRect(o: Rect): Boolean = o.left >= left && o.top >= top && o.right <= right && o.bottom <= bottom

    @Test fun cropsSitInsideThePainting() {
        val art = Rect(Offset.Zero, CourtArtCrops.artPixels)
        for (r in CourtArtCrops.crowdPixels) assertTrue(art.containsRect(r))
        val p = CourtArtCrops.gavelPatchPixels
        assertTrue(art.containsRect(p))
        assertTrue(art.containsRect(Rect(art.width - p.right, p.top, art.width - p.left, p.bottom)))
        assertEquals(CourtActor.crowd.size, CourtArtCrops.crowdUnits.size)
        // 2–4 clusters, over the stands only (never the whole background).
        assertTrue(CourtArtCrops.crowdUnits.size in 2..4)
        val area = CourtArtCrops.crowdUnits.fold(0f) { acc, u -> acc + u.width * u.height }
        assertTrue(area < 0.05f)
        // The gavel's resting grid starts on the painted gavel.
        assertTrue(p.contains(CourtArtCrops.gavelOriginPixels))
    }

    // MARK: No scattered timers

    @Test fun courtroomUsesNoTimers() {
        val dirs = listOf("src/main/java/app/plead/android/courtroom", "src/main/java/app/plead/android/features/court")
        var checked = 0
        for (dir in dirs) {
            val files = File(dir).listFiles() ?: continue
            for (f in files.filter { it.extension == "kt" }) {
                val src = f.readText()
                checked += 1
                for (banned in listOf("java.util.Timer", "Timer(", "Handler(", "postDelayed", "repeatForever", "infiniteRepeatable", "Choreographer")) {
                    assertFalse("${f.name} uses $banned", src.contains(banned))
                }
            }
        }
        assertTrue(checked >= 10)
    }

    // MARK: Shared court entrance in the live scene (amendment ac)

    private fun entranceDefaults(): UserDefaults = UserDefaults.inMemory()

    private val yieldSleep: suspend (Double) -> Unit = { yield() }

    @Test fun firstOpenPlaysTheEntranceSecondOpenRestores() = runTest(dispatcher) {
        val defaults = entranceDefaults()
        val s = CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffOpening, owner = Role.plaintiff, turns = 1)
        val key = CourtLiveEntrance.seenKey(s.kase.id)
        assertEquals("courtEntranceSeen.${s.kase.id.toString().uppercase()}", key)

        val first = CourtLiveEntrance(s, reduceMotion = false, defaults = defaults, sleep = yieldSleep)
        assertTrue(first.plays && first.isEntering && first.director.phase == CourtEntrancePhase.preparing)
        first.begin()
        assertEquals(CourtEntrancePhase.entering, first.director.phase)
        assertTrue(defaults.bool(key))
        first.director.join()
        assertTrue(first.director.phase == CourtEntrancePhase.ready && first.director.gavelTaps == 1 && !first.isEntering)

        val second = CourtLiveEntrance(s, reduceMotion = false, defaults = defaults, sleep = yieldSleep)
        assertTrue(!second.plays && !second.isEntering)
        second.begin()
        assertTrue(second.director.phase == CourtEntrancePhase.ready && second.director.gavelTaps == 0 && !second.director.isRunning)
        assertTrue(
            second.director.judge == CourtEntrancePose.standing && second.director.plaintiff == CourtEntrancePose.standing &&
                second.director.defendant == CourtEntrancePose.standing,
        )
    }

    @Test fun entranceOnlyPlaysDuringTheTrial() {
        val defaults = entranceDefaults()
        for (s in listOf(CourtFixtures.deliberating, CourtFixtures.verdictIn, CourtFixtures.safetyNotice)) {
            val e = CourtLiveEntrance(s, reduceMotion = false, defaults = defaults, sleep = yieldSleep)
            assertTrue(!e.plays && e.director.phase == CourtEntrancePhase.ready)
        }
        val replay = CourtLiveEntrance(CourtFixtures.deliberating, reduceMotion = false, mode = CourtEntranceMode.replay, defaults = defaults)
        assertTrue(replay.plays)
    }

    @Test fun tapFinishesAndLeavingSnapsToReady() {
        val defaults = entranceDefaults()
        val s = CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffOpening, owner = Role.plaintiff, turns = 1)
        val e = CourtLiveEntrance(s, reduceMotion = false, defaults = defaults, sleep = { awaitCancellation() })
        e.begin()
        assertTrue(e.isEntering)
        e.tap()
        assertTrue(e.director.phase == CourtEntrancePhase.ready && e.director.plaintiff == CourtEntrancePose.standing && !e.isEntering)
        val e2 = CourtLiveEntrance(s, reduceMotion = false, mode = CourtEntranceMode.replay, defaults = defaults, sleep = { awaitCancellation() })
        e2.begin()
        e2.end()
        assertTrue(e2.director.phase == CourtEntrancePhase.ready && !e2.director.isRunning)
    }

    @Test fun missingPartyStaysOutThenWalksInOnce() = runTest(dispatcher) {
        val defaults = entranceDefaults()
        val s = CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffOpening, owner = Role.plaintiff, turns = 1)
            .copy(presentRoles = setOf(Role.plaintiff))
        // First open: the entrance plays without the defendant.
        val e = CourtLiveEntrance(s, reduceMotion = false, defaults = defaults, sleep = yieldSleep)
        e.begin()
        e.director.join()
        assertTrue(e.director.defendant == CourtEntrancePose.hidden && e.director.plaintiff == CourtEntrancePose.standing)

        // A returning open with the defendant still away: restored, podium empty.
        val back = CourtLiveEntrance(s, reduceMotion = false, defaults = defaults, sleep = yieldSleep)
        back.begin()
        assertTrue(!back.plays && back.director.defendant == CourtEntrancePose.hidden)
        val walked = mutableListOf<Double>()
        back.director.onKeyframe = { walked.add(it) }
        back.update(s)
        assertTrue(walked.isEmpty() && back.director.defendant == CourtEntrancePose.hidden)

        // They join: one short walk-in, nothing else replays.
        val joined = s.copy(presentRoles = null)
        back.update(joined)
        back.director.join()
        assertEquals(CourtEntranceTiming.walkIn, walked.last(), 0.0)
        assertTrue(
            back.director.defendant == CourtEntrancePose.standing && back.director.gavelTaps == 0 &&
                back.director.phase == CourtEntrancePhase.ready,
        )
        val count = walked.size
        back.update(joined)
        back.director.join()
        assertEquals(count, walked.size)
    }

    @Test fun livePresenceFollowsTheRecord() {
        val opening = CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffOpening, owner = Role.plaintiff, turns = 1)
        assertEquals(setOf(Role.plaintiff), CourtroomLogic.presentRoles(opening.kase, opening.turns, myRole = Role.plaintiff))
        assertEquals(setOf(Role.defendant), CourtroomLogic.presentRoles(opening.kase, opening.turns, myRole = Role.defendant))
        val later = CourtFixtures.crossExam
        assertEquals(setOf(Role.plaintiff, Role.defendant), CourtroomLogic.presentRoles(later.kase, later.turns, myRole = Role.plaintiff))
        val done = CourtFixtures.deliberating
        assertEquals(setOf(Role.plaintiff, Role.defendant), CourtroomLogic.presentRoles(done.kase, emptyList(), myRole = Role.plaintiff))
        assertTrue(opening.isPresent(Role.defendant)) // null = both (fixtures, previews)
    }

    @Test fun entranceHoldsTheOpeningGavelUntilTheJudgeIsSeated() = runTest(dispatcher) {
        val s = CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffOpening, owner = Role.plaintiff, turns = 1)
        val c = Clock()
        c.until = 104.0
        val d = c.director()
        d.update(s)
        d.entranceHold = true
        d.appear(analytics = false)
        d.claimReveal(model(0, s), turns = s.turns)
        d.join()
        assertEquals(0, d.gavelStrikes)
        assertFalse(d.judgePose.mouthOpen)

        val c2 = Clock()
        c2.until = 104.0
        val d2 = c2.director()
        d2.update(s)
        d2.appear(analytics = false)
        d2.entranceHold = false
        d2.playEntranceGavel()
        d2.join()
        assertTrue(d2.gavelStrikes == 1 && d2.gavel == GavelFrame.rest)

        // Reduce Motion: the tap is shown static (no strike).
        val d3 = Clock().director()
        d3.reduceMotion = true
        d3.appear(analytics = false)
        d3.playEntranceGavel()
        assertTrue(d3.gavelStrikes == 0 && !d3.isRunning)
    }

    @Test fun firstRevealWaitsForTheEntranceToBeReady() = runTest(dispatcher) {
        val s = CourtFixtures.state(app.plead.android.models.TrialPhase.plaintiffOpening, owner = Role.plaintiff, turns = 1)
        val e = CourtLiveEntrance(s, reduceMotion = false, mode = CourtEntranceMode.replay, defaults = entranceDefaults(),
            sleep = { awaitCancellation() })
        val c = Clock()
        c.until = 104.0
        val d = c.director()
        d.entranceHold = e.isEntering
        d.update(s)
        d.appear(analytics = false)
        e.begin()
        assertTrue(e.director.phase == CourtEntrancePhase.entering && d.entranceHold)
        // Before ready: held, nothing claimed (the bubble's text is on screen for TalkBack, masked visually).
        assertEquals(CourtMotionDirector.RevealDecision.held, d.requestReveal(model(0, s), turns = s.turns))
        assertFalse(d.hasRevealed(turn(0).id))
        // Ready (a tap / the gavel): the scene releases the hold and the first bubble plays its entrance.
        e.tap()
        d.entranceHold = e.isEntering
        assertTrue(!d.entranceHold && e.director.phase == CourtEntrancePhase.ready)
        assertTrue("first reveal not played after ready", d.requestReveal(model(0, s), turns = s.turns) is CourtMotionDirector.RevealDecision.play)
        assertTrue(d.hasRevealed(turn(0).id))
        assertEquals(CourtMotionDirector.RevealDecision.shown, d.requestReveal(model(0, s), turns = s.turns))
        d.join()

        // A restored court never holds.
        val defaults = entranceDefaults()
        defaults.set(true, CourtLiveEntrance.seenKey(s.kase.id))
        val back = CourtLiveEntrance(s, reduceMotion = false, defaults = defaults)
        assertFalse(back.isEntering)
    }

    companion object {
        fun randomIn(r: ClosedFloatingPointRange<Double>): Double =
            if (r.endInclusive <= r.start) r.start else Random.nextDouble(r.start, r.endInclusive)
    }
}
