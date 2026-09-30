// Port of ArgueWin/Features/Onboarding/MockTrial/MockTrialPlayer.swift — onboarding mock trial playback
// (CONTRACTS-v2 amendments ab + ac + ae + aj). A local scripted state machine: `phase` is invitation (waits for START
// MOCK TRIAL, nothing plays) → entrance (the shared court entrance, `CourtEntranceDirector`, never waits on the network)
// → trial, the fourteen beats of amendment aj (the twelve phases, cross-examination split into one beat per
// question/answer pair plus the follow-up). Each beat reveals its parts (lines, the claim card, an exhibit, deliberation
// rows, the verdict card, the judgement options…) on a schedule (`MockTrialTiming.partTimes`), then autoplays to the
// next beat after its dwell. A tap first completes the current beat (every part on screen, text fully revealed), the
// next tap moves on. ONE cancellable job plays each beat's choreography; time is injected (`sleep`, `now`) and so is
// the coroutine scope, so the schedule is deterministic in tests. No timers, no backend, no judge call.
package app.plead.android.features.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.plead.android.courtroom.CourtEntranceDirector
import app.plead.android.courtroom.CourtEntrancePose
import app.plead.android.courtroom.CourtEntranceTiming
import app.plead.android.courtroom.CourtMotionTiming
import app.plead.android.courtroom.CourtRevealPlan
import app.plead.android.courtroom.GavelFrame
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Timing tokens (amendment x tokens where they already exist). */
object MockTrialTiming {
    /** Scene soft fade into the courtroom (250–350 ms): the view's fade of the stage under Reduce Motion. */
    const val sceneSettle: Double = 0.3

    /** When each part of a beat appears, from the beat's start (same order as `MockTrialStep.parts`). */
    val partTimes: Map<MockTrialBeat, List<Double>> = mapOf(
        MockTrialBeat.opening to listOf(0.0, 2.6),
        MockTrialBeat.plaintiffOpening to listOf(0.5),
        MockTrialBeat.plaintiffEvidence to listOf(0.0, 2.0),
        MockTrialBeat.defendantOpening to listOf(0.4),
        MockTrialBeat.defendantEvidence to listOf(0.0, 2.0),
        MockTrialBeat.crossExamination1 to listOf(0.0, 1.7),
        MockTrialBeat.crossExamination2 to listOf(0.0, 1.7),
        MockTrialBeat.crossExamination3 to listOf(0.0, 1.6),
        MockTrialBeat.crossFollowUp to listOf(0.0),
        MockTrialBeat.closings to listOf(0.7, 2.0),
        MockTrialBeat.deliberation to listOf(0.4, 1.0, 1.6, 2.2),
        MockTrialBeat.verdict to listOf(0.0, 1.3),
        MockTrialBeat.judgement to listOf(0.0, 1.2, 1.9),
        MockTrialBeat.closed to emptyList(),
    )

    /** How long each beat holds before autoplay moves on (reading time for everything it shows). `closed` waits for the user. */
    val dwell: Map<MockTrialBeat, Double> = mapOf(
        MockTrialBeat.opening to 5.4,
        MockTrialBeat.plaintiffOpening to 5.8,
        MockTrialBeat.plaintiffEvidence to 4.0,
        MockTrialBeat.defendantOpening to 4.8,
        MockTrialBeat.defendantEvidence to 4.0,
        MockTrialBeat.crossExamination1 to 3.9,
        MockTrialBeat.crossExamination2 to 3.4,
        MockTrialBeat.crossExamination3 to 3.5,
        MockTrialBeat.crossFollowUp to 3.2,
        MockTrialBeat.closings to 4.6,
        MockTrialBeat.deliberation to 3.4,
        MockTrialBeat.verdict to 4.8,
        MockTrialBeat.judgement to 3.8,
    )

    /** Judgement: "Replace the pizza" highlights itself this long after the options (~1.2 s). */
    val judgementSelectAt: Double get() = partTimes.getValue(MockTrialBeat.judgement)[1]

    /** Deliberation: the panel rows tick in over this window (≈ 2–3 s, from the room dimming). */
    val deliberationTicks: Double get() = partTimes.getValue(MockTrialBeat.deliberation).last() + 0.25

    /** A tap within this window of the previous beat change is ignored (the transition is committing). */
    const val commitWindow: Double = 0.25

    /** Speech bubble: fade + scale 0.96 → 1 + 6 pt rise, restrained spring (180–250 ms). */
    const val bubbleEntrance: Double = CourtMotionTiming.bubbleEntrance
    const val bubbleScale: Float = CourtMotionTiming.bubbleScale
    const val bubbleRise: Float = CourtMotionTiming.bubbleRise

    /** Speaker name / role first, the line 100 ms later, revealed line by line (60–100 ms stagger). */
    const val bodyDelay: Double = CourtMotionTiming.bodyDelay
    const val lineStagger: Double = CourtMotionTiming.lineStagger
    const val lineDuration: Double = CourtMotionTiming.lineDuration
    const val lineRise: Float = CourtMotionTiming.lineRise

    /** Previous dialogue fades back to this opacity (never vanishes abruptly). */
    const val fadedBack: Double = 0.38
    const val fadeBack: Double = 0.22

    /** Talk loop: 2-frame mouth while the line reveals. */
    const val talkFrame: Double = CourtMotionTiming.talkFrame

    /** Verdict: the judge's line, then the gavel (raise → strike → hold → return, 0.27 s), then the card. */
    const val gavelAt: Double = 0.8
    val gavelTotal: Double get() = CourtMotionTiming.gavelTotal

    /** Exhibit reveal (amendment x): rise + fade with a small settle; the EXHIBIT label lands after. */
    const val exhibitRise: Float = CourtMotionTiming.exhibitRise
    const val exhibitDuration: Double = CourtMotionTiming.exhibitDuration
    const val exhibitLabelDelay: Double = CourtMotionTiming.exhibitLabelDelay

    /** Verdict card: stronger rise + fade with one soft spring (250–400 ms). */
    const val verdictCard: Double = 0.34
    const val verdictBounce: Double = 0.22
    const val verdictRise: Float = 16f
    const val verdictScale: Float = 0.94f

    /** The reason line follows the headline. */
    const val judgementDelay: Double = 0.16

    /** Audience micro-reaction on the verdict card (< 300 ms, hop + settle) and the winner's hop. */
    const val crowdAt: Double = 0.12
    const val crowdHold: Double = 0.14
    const val crowdEase: Double = 0.12
    val crowdReaction: Double get() = crowdHold + crowdEase
    const val crowdHop: Float = 2f
    const val winnerAt: Double = 0.08
    const val winnerHold: Double = 0.2
    const val winnerHop: Float = 3f

    /** Invitation copy / START button fade on START. */
    const val invitationFade: Double = 0.15

    /** From the court being ready (end of the entrance) to CASE CLOSED when left to autoplay. */
    val autoplayTotal: Double get() = MockTrialBeat.entries.mapNotNull { dwell[it] }.sum()

    /** From START MOCK TRIAL to CASE CLOSED: the shared entrance, then `autoplayTotal`. */
    val fromStartTotal: Double get() = CourtEntranceTiming.ready + autoplayTotal

    /** When a line is fully on screen (the bubble's line-by-line reveal). */
    fun revealDuration(text: String): Double {
        if (text.isEmpty()) return 0.0
        return CourtRevealPlan.make(body = text, questions = emptyList(), charsPerLine = 30).total
    }

    /** The speaker's talk loop for a line: while it reveals, bounded. */
    fun talkDuration(text: String): Double {
        if (text.isEmpty()) return 0.0
        return CourtRevealPlan.make(body = text, questions = emptyList(), charsPerLine = 30).talkDuration
    }

    /** When everything in `beat` is on screen and revealed (autoplay; a tap gets there at once). */
    fun revealedAt(beat: MockTrialBeat): Double {
        val step = MockTrialScript.step(beat)
        val times = partTimes[beat].orEmpty()
        val last = step.parts.lastOrNull() ?: return 0.0
        val at = times.lastOrNull() ?: return 0.0
        return when (last) {
            is MockTrialPart.say -> at + revealDuration(last.value.text)
            MockTrialPart.verdictCard -> at + judgementDelay + lineStagger * 2 + lineDuration
            else -> at + 0.3
        }
    }
}

/** Where the onboarding step is: the invitation, the court's load-in, or the trial. */
enum class MockTrialPhase { invitation, entrance, trial }

class MockTrialPlayer(
    private val sleep: suspend (Double) -> Unit = { delay((it * 1000).toLong()) },
    private val now: () -> Double = { System.nanoTime() / 1e9 },
    /** The single playback job (and the default entrance's job) run here; the view passes its composition scope. */
    private val scope: CoroutineScope = MainScope(),
    private val makeEntrance: (reduceMotion: Boolean) -> CourtEntranceDirector = { rm ->
        CourtEntranceDirector(hasPlaintiff = true, hasDefendant = true, reduceMotion = rm, scope = scope)
    },
) {
    /** What the choreography did, in order (tests check the schedule and the Reduce Motion path). */
    sealed class Cue {
        data class beat(val beat: MockTrialBeat) : Cue()

        /** START: the shared entrance began / the director reported ready. */
        data object entranceStarted : Cue()
        data object entranceReady : Cue()

        /** The trial opened on the judge's session line (not a beat change). */
        data object entered : Cue()

        /** `n` parts of the current beat are on screen. */
        data class parts(val n: Int) : Cue()

        /** Everything in the current beat is on screen and revealed (a tap can also complete it). */
        data object revealed : Cue()
        data class mouth(val speaker: MockTrialSpeaker, val open: Boolean) : Cue()
        data class gavel(val frame: GavelFrame) : Cue()
        data class crowd(val lift: Float) : Cue()
        data class winner(val lift: Float) : Cue()
    }

    /** One recorded cue (Swift `(at:, cue:)`). */
    data class TimedCue(val at: Double, val cue: Cue)

    // MARK: State (views read these)

    /** Invitation until START MOCK TRIAL; nothing plays (no job, no dialogue) while it is on screen. */
    var phase: MockTrialPhase by mutableStateOf(MockTrialPhase.invitation)
        private set

    /** The shared court entrance (amendment ac), created on START; the stage applies its poses to the figures. */
    var entrance: CourtEntranceDirector? by mutableStateOf(null)
        private set
    var currentBeat: MockTrialBeat by mutableStateOf(MockTrialBeat.opening)
        private set

    /** How many of the current beat's parts are on screen. */
    var partsShown: Int by mutableIntStateOf(0)
        private set

    /** False until the trial opens (after the entrance); the judge's session line lands with it. */
    var entered: Boolean by mutableStateOf(false)
        private set

    /** Everything in the beat is on screen with its text fully revealed (a tap completes it). */
    var revealComplete: Boolean by mutableStateOf(false)
        private set

    /** The phase help sheet (amendment ae) is open: playback waits where it was until it is dismissed. */
    var pausedForHelp: Boolean by mutableStateOf(false)
        private set

    /** Open-mouth frame of whoever is talking (null = everyone's mouth closed). */
    var talking: MockTrialSpeaker? by mutableStateOf(null)
        private set
    var gavel: GavelFrame by mutableStateOf(GavelFrame.rest)
        private set
    var crowdLift: Float by mutableFloatStateOf(0f)
        private set
    var winnerLift: Float by mutableFloatStateOf(0f)
        private set

    val step: MockTrialStep get() = MockTrialScript.step(currentBeat)

    /** The parts of the current beat on screen, in order. */
    val visibleParts: List<MockTrialPart> get() = if (entered) step.parts.take(partsShown) else emptyList()
    fun shows(part: MockTrialPart): Boolean = visibleParts.contains(part)
    val isInvitation: Boolean get() = phase == MockTrialPhase.invitation

    /** The courtroom card is on screen (from START on). */
    val courtShown: Boolean get() = phase != MockTrialPhase.invitation

    /** The case chip is on the stage (the entrance's case label; always once the trial is on). */
    val chipVisible: Boolean get() = phase == MockTrialPhase.trial || (entrance?.caseLabelVisible ?: false)

    /** The painted room is revealed (the entrance lifts its dim; always once the trial is on). */
    val roomRevealed: Boolean get() = phase == MockTrialPhase.trial || (entrance?.roomRevealed ?: false)

    /** The help button is on screen (only beside the opening statement labels). */
    val helpAvailable: Boolean get() = phase == MockTrialPhase.trial && helpBeats.contains(currentBeat)

    /** The entrance pose for a figure (standing once the trial is on, or with no entrance: the capture harness). */
    fun entrancePose(s: MockTrialSpeaker): CourtEntrancePose {
        val e = entrance
        if (phase != MockTrialPhase.entrance || e == null) return CourtEntrancePose.standing
        return when (s) {
            MockTrialSpeaker.judge -> e.judge
            MockTrialSpeaker.plaintiff -> e.plaintiff
            MockTrialSpeaker.defendant -> e.defendant
            MockTrialSpeaker.court -> CourtEntrancePose.standing
        }
    }

    /** 0 → 1 while the audience settles during the entrance. */
    val audienceSettle: Double get() = if (phase == MockTrialPhase.entrance) (entrance?.audienceSettle ?: 0.0) else 1.0

    /** CASE CLOSED is on screen (the CTA is offered). */
    val isClosed: Boolean get() = phase == MockTrialPhase.trial && currentBeat == MockTrialBeat.closed

    /** The plaintiff has won (verdict card up, and after): the winner's glow. */
    val plaintiffWon: Boolean
        get() {
            if (!entered) return false
            return currentBeat.rawValue > MockTrialBeat.verdict.rawValue ||
                (currentBeat == MockTrialBeat.verdict && shows(MockTrialPart.verdictCard))
        }

    /**
     * The side / judge holding the floor (label + emphasis): whoever the latest part belongs to, else the beat's first
     * speaker. Nobody during deliberation or once the case is closed.
     */
    val activeSpeaker: MockTrialSpeaker?
        get() {
            if (!entered || phase != MockTrialPhase.trial) return null
            if (currentBeat == MockTrialBeat.deliberation || currentBeat == MockTrialBeat.closed) return null
            for (part in visibleParts.asReversed()) speaker(part)?.let { return it }
            return step.parts.firstNotNullOfOrNull(::speaker)
        }

    fun mouthOpen(s: MockTrialSpeaker): Boolean = talking == s

    /** Reduce Motion: fades only, immediate text; never talk, gavel, hops or walk-ins. */
    var reduceMotion: Boolean = false
        set(value) {
            field = value
            if (!value) return
            rest()
            if (phase == MockTrialPhase.entrance) entrance?.finish()
            // The whole beat shows at once (no staged reveal).
            if (phase == MockTrialPhase.trial) showAll(record = false)
        }

    /** False under TalkBack: each beat stays until the user advances (autoplay is never mandatory). */
    var autoplay: Boolean = true
        set(value) {
            val old = field
            field = value
            if (value != old && wantsRun) restart()
        }

    /** True while the (single) playback job exists. */
    val isRunning: Boolean get() = task != null

    // MARK: Callbacks (the view forwards them to the flow)

    var onBeatChange: ((MockTrialBeat) -> Unit)? = null
    var onSkip: (() -> Unit)? = null
    var onContinue: (() -> Unit)? = null

    // MARK: Test hooks

    private val _cues = mutableListOf<TimedCue>()
    val cues: List<TimedCue> get() = _cues
    var tasksStarted = 0
        private set

    /** The entrance's gavel tap is due at the start of the next job run. */
    private var gavelPending = false
    private var task: Job? = null
    private var generation = 0
    private var wantsRun = false
    private var active = true
    private var lastCommit: Double? = null

    /**
     * When the current beat began (its dwell counts from here), and how far into it playback was when the help sheet
     * paused it or a tap completed it (the rest of the dwell is what remains).
     */
    private var beatStartedAt: Double = 0.0
    private var resumeElapsed: Double? = null

    // MARK: Lifecycle

    /**
     * START MOCK TRIAL: invitation → entrance (the court opens), then the opening beat and autoplay. Past the
     * invitation it resumes playback (the view calls it again when it comes back on screen).
     */
    fun start() {
        wantsRun = true
        when (phase) {
            MockTrialPhase.invitation -> {
                phase = MockTrialPhase.entrance
                val director = makeEntrance(reduceMotion)
                director.onReady = { entranceReady() }
                entrance = director
                record(Cue.entranceStarted)
                // Reduce Motion: straight to the occupied court (the view fades it in), then the dialogue.
                if (reduceMotion || !active) director.finish() else director.start()
            }
            // Back on screen mid-entrance: show the ready court rather than replaying the walk-in.
            MockTrialPhase.entrance -> entrance?.finish()
            MockTrialPhase.trial -> restart()
        }
    }

    /** Off screen: cancel the job, everyone at rest. The beat is kept (coming back resumes it). */
    fun stop() {
        wantsRun = false
        entrance?.stop()
        cancel()
        rest()
    }

    /** Resumed / paused (Swift `scenePhase == .active`). Background pauses playback; returning replays the current beat. */
    fun setActive(isActive: Boolean) {
        if (isActive == active) return
        active = isActive
        if (isActive) {
            restart()
        } else {
            cancel(); rest()
            // Coming back carries on with the rest of the beat's dwell (the help sheet keeps its own pause point).
            if (phase == MockTrialPhase.trial && !pausedForHelp) {
                resumeElapsed = max(0.0, now() - beatStartedAt); showAll(record = false)
            }
            // Backgrounded mid-entrance: it completes (coming back shows the ready court, no replay).
            if (phase == MockTrialPhase.entrance) entrance?.finish()
        }
    }

    /**
     * The entrance's director tapped its gavel (the view forwards `gavelTaps` changes): one raise → strike → return on
     * the pixel gavel. Never under Reduce Motion.
     */
    fun entranceGavel() {
        if (phase != MockTrialPhase.entrance || reduceMotion || !active) return
        gavelPending = true
        restart()
    }

    /** Awaits the playback job (tests). */
    suspend fun join() {
        task?.join()
    }

    // MARK: Actions

    /**
     * Tap anywhere. Ignored on the invitation (only START begins), at CASE CLOSED (the CTA continues) and while a beat
     * change is committing. During the entrance it finishes the load-in at once. In a beat it first shows everything
     * the beat holds (every part, text complete; autoplay keeps what is left of the dwell), and the next tap moves to
     * the next beat.
     */
    fun advance(): Boolean {
        when (phase) {
            MockTrialPhase.invitation -> return false
            MockTrialPhase.entrance -> {
                entrance?.finish()
                return true
            }
            MockTrialPhase.trial -> Unit
        }
        val next = currentBeat.next
        if (pausedForHelp || next == null) return false
        val t = now()
        lastCommit?.let { if (t - it < MockTrialTiming.commitWindow) return false }
        if (!revealComplete) {
            cancel()
            rest()
            resumeElapsed = max(0.0, t - beatStartedAt)
            showAll(record = true)
            lastCommit = t
            restart()
            return true
        }
        cancel()
        rest()
        commit(next, t)
        restart()
        return true
    }

    /**
     * The help sheet opened (amendment ae; only ever by the user): the single job stops where it is, the beat shows
     * complete and everyone rests. Nothing changes beat while paused.
     */
    fun pauseForHelp() {
        if (!helpAvailable || pausedForHelp) return
        pausedForHelp = true
        resumeElapsed = max(0.0, now() - beatStartedAt)
        showAll(record = true)
        cancel()
        rest()
    }

    /**
     * The help sheet closed (Got it, swipe or Close): playback carries on from the same beat with what was left of
     * its dwell; the entrance and the bubble never replay.
     */
    fun resumeFromHelp() {
        if (!pausedForHelp) return
        pausedForHelp = false
        resumeElapsed?.let { beatStartedAt = now() - it }
        restart()
    }

    /**
     * SKIP DEMO (always available: invitation, entrance and every beat; the flow reports the beat it saw last,
     * `opening` before the trial starts).
     */
    fun skip() {
        if (phase == MockTrialPhase.entrance) entrance?.skip()
        stop()
        onSkip?.invoke()
    }

    /** I'M READY FOR COURT: only once CASE CLOSED is on screen. */
    fun finish() {
        if (!isClosed) return
        stop()
        onContinue?.invoke()
    }

    // MARK: Playback

    /** Screenshot harness (`AWMockTrialBeat <beat>`, debug builds): open on a beat, settled (every part shown), no autoplay. */
    fun debugHold(at: MockTrialBeat) {
        autoplay = false
        phase = MockTrialPhase.trial
        entered = true
        currentBeat = at
        partsShown = step.parts.size
        revealComplete = true
        if (at == MockTrialBeat.verdict || at == MockTrialBeat.judgement || at == MockTrialBeat.closed) rest()
    }

    /**
     * Screenshot harness (`AWMockTrialBeat entrance`): START with autoplay off (the entrance plays, then the judge's
     * session line and the claim card hold).
     */
    fun debugStartEntrance() {
        autoplay = false
        start()
    }

    private fun restart() {
        cancel()
        if (!wantsRun || !active || pausedForHelp) return
        generation += 1
        val mine = generation
        tasksStarted += 1
        val job = scope.launch {
            play()
            if (generation == mine) task = null
        }
        task = job
        // An eager dispatcher may have finished the job before it was stored.
        if (job.isCompleted && generation == mine) task = null
    }

    private fun cancel() {
        task?.cancel()
        task = null
        generation += 1
    }

    private fun commit(beat: MockTrialBeat, t: Double) {
        phase = MockTrialPhase.trial
        entered = true
        currentBeat = beat
        partsShown = 0
        revealComplete = false
        lastCommit = t
        beatStartedAt = t
        resumeElapsed = null
        record(Cue.beat(beat))
        // Reduce Motion: the whole beat at once.
        if (reduceMotion) showAll(record = false)
        onBeatChange?.invoke(beat)
    }

    /**
     * The entrance hands over to the trial on the judge's session line (the opening beat; not a beat change for
     * analytics: the flow already reports `opening` until the next beat).
     */
    private fun openTrial(t: Double) {
        phase = MockTrialPhase.trial
        entered = true
        currentBeat = MockTrialBeat.opening
        partsShown = 0
        revealComplete = false
        lastCommit = t
        beatStartedAt = t
        resumeElapsed = null
        record(Cue.entered)
        if (reduceMotion) showAll(record = false)
    }

    /** Every part of the current beat on screen, text complete. */
    private fun showAll(record: Boolean) {
        val n = step.parts.size
        if (partsShown != n) {
            partsShown = n
            if (record) record(Cue.parts(n))
        }
        if (revealComplete) return
        revealComplete = true
        if (record) record(Cue.revealed)
    }

    /**
     * The entrance's gavel tap (if one is due), then each beat's choreography and, with autoplay, the next beat after
     * its dwell. Ends at CASE CLOSED (the user continues). The entrance itself is the director's job; it runs even
     * without autoplay (it is visual).
     */
    private suspend fun play() {
        if (gavelPending) {
            gavelPending = false
            var elapsed = 0.0
            for ((at, cue) in gavelCues(0.0)) {
                if (!wait(at - elapsed)) return
                elapsed = at
                apply(cue)
            }
            // Still entering: the director's `onReady` opens the trial (and restarts playback if idle).
            if (phase == MockTrialPhase.entrance) return
        }
        if (phase != MockTrialPhase.trial) return
        while (currentCoroutineContext().isActive) {
            val beat = currentBeat
            var elapsed = 0.0
            var cues = choreography(beat)
            val resumed = resumeElapsed
            if (resumed != null) {
                // Back from the help sheet / background, or a tap completed the beat: everything is already on screen
                // and everyone rests; only the rest of the dwell remains (no talk loop or reveal replays).
                resumeElapsed = null
                elapsed = resumed
                cues = emptyList()
            }
            for ((at, cue) in cues) {
                if (!wait(at - elapsed)) return
                elapsed = at
                apply(cue)
            }
            val next = beat.next
            val dwell = MockTrialTiming.dwell[beat]
            if (!autoplay || next == null || dwell == null) return
            if (!wait(dwell - elapsed)) return
            rest()
            commit(next, now())
        }
    }

    /**
     * The director reports the court ready (end of the sequence, a tap, Reduce Motion or background): the judge opens
     * the session. A gavel tap still in flight finishes first, on the same job.
     */
    private fun entranceReady() {
        if (phase != MockTrialPhase.entrance) return
        record(Cue.entranceReady)
        openTrial(now())
        if (!isRunning) restart()
    }

    /** Returns false when cancelled (the caller stops without touching state). */
    private suspend fun wait(seconds: Double): Boolean {
        if (!currentCoroutineContext().isActive) return false
        if (seconds > 0.0001) {
            try {
                sleep(seconds)
            } catch (e: Exception) {
                return false
            }
        }
        return currentCoroutineContext().isActive
    }

    /**
     * One beat's schedule, as offsets from the beat's start: each part's appearance, the talk loop for every line, the
     * verdict's gavel and reactions, and the moment the beat is complete. Reduce Motion: nothing (the beat is shown
     * whole at its start; fades are the views').
     */
    fun choreography(beat: MockTrialBeat): List<Pair<Double, Cue>> {
        if (reduceMotion) return emptyList()
        val T = MockTrialTiming
        val step = MockTrialScript.step(beat)
        val times = T.partTimes[beat].orEmpty()
        val out = mutableListOf<Pair<Double, Cue>>()
        step.parts.forEachIndexed { i, part ->
            val at = if (i < times.size) times[i] else (times.lastOrNull() ?: 0.0)
            out.add(at to Cue.parts(i + 1))
            when {
                part is MockTrialPart.say && part.value.speaker != MockTrialSpeaker.court -> {
                    val line = part.value
                    val start = at + T.bodyDelay
                    val end = start + T.talkDuration(line.text)
                    var t = start
                    var open = true
                    while (t < end - 0.001) {
                        out.add(t to Cue.mouth(line.speaker, open))
                        open = !open
                        t += T.talkFrame
                    }
                    out.add(end to Cue.mouth(line.speaker, false))
                }
                part == MockTrialPart.verdictCard -> {
                    out.add(at + T.winnerAt to Cue.winner(T.winnerHop))
                    out.add(at + T.crowdAt to Cue.crowd(T.crowdHop))
                    out.add(at + T.crowdAt + T.crowdHold to Cue.crowd(0f))
                    out.add(at + T.winnerAt + T.winnerHold to Cue.winner(0f))
                }
            }
        }
        if (beat == MockTrialBeat.verdict) out += gavelCues(T.gavelAt)
        if (step.parts.isNotEmpty()) out.add(T.revealedAt(beat) to Cue.revealed)
        // Stable: equal times keep their insertion order (a part appears before its talk loop starts).
        return out.sortedBy { it.first }
    }

    private fun apply(cue: Cue) {
        when (cue) {
            is Cue.parts -> {
                if (cue.n <= partsShown) return
                partsShown = cue.n
            }
            Cue.revealed -> {
                if (revealComplete) return
                partsShown = step.parts.size
                revealComplete = true
            }
            is Cue.mouth -> {
                if (reduceMotion) return
                if (cue.open) talking = cue.speaker else if (talking == cue.speaker) talking = null
            }
            is Cue.gavel -> {
                if (reduceMotion) return
                gavel = cue.frame
            }
            is Cue.crowd -> {
                if (reduceMotion) return
                crowdLift = cue.lift
            }
            is Cue.winner -> {
                if (reduceMotion) return
                winnerLift = cue.lift
            }
            is Cue.beat, Cue.entranceStarted, Cue.entranceReady, Cue.entered -> Unit
        }
        record(cue)
    }

    /** Everyone at rest (mouths shut, gavel down, no hops). */
    private fun rest() {
        if (talking != null) talking = null
        if (gavel != GavelFrame.rest) gavel = GavelFrame.rest
        if (crowdLift != 0f) crowdLift = 0f
        if (winnerLift != 0f) winnerLift = 0f
    }

    private fun record(cue: Cue) {
        _cues.add(TimedCue(now(), cue))
    }

    companion object {
        /** Beats that offer the OPENING STATEMENT help button beside their label (amendment ae). */
        val helpBeats: Set<MockTrialBeat> = setOf(MockTrialBeat.plaintiffOpening, MockTrialBeat.defendantOpening)

        fun speaker(part: MockTrialPart): MockTrialSpeaker? = when (part) {
            is MockTrialPart.say -> part.value.speaker
            MockTrialPart.claim -> MockTrialSpeaker.judge
            is MockTrialPart.exhibit -> if (part.id == MockTrialExhibitID.a) MockTrialSpeaker.plaintiff else MockTrialSpeaker.defendant
            MockTrialPart.options, MockTrialPart.select -> MockTrialSpeaker.plaintiff
            is MockTrialPart.panelRow, MockTrialPart.verdictCard -> null
        }

        /** One gavel strike (raise → strike → hold → return, `CourtMotionTiming`) starting at `g`. */
        fun gavelCues(g: Double): List<Pair<Double, Cue>> {
            val m = CourtMotionTiming
            return listOf(
                g to Cue.gavel(GavelFrame.raised),
                g + m.gavelRaise to Cue.gavel(GavelFrame.struck),
                g + m.gavelRaise + m.gavelStrike + m.gavelHold to Cue.gavel(GavelFrame.returning),
                g + MockTrialTiming.gavelTotal to Cue.gavel(GavelFrame.rest),
            )
        }
    }
}

