// Port of ArgueWin/Courtroom/CourtMotion.swift: the courtroom motion system (CONTRACTS-v2 amendment x,
// docs/courtroom-motion-brief/BRIEF.md).
//
// State model, not timers:
//   CourtPhase      opening | testimony | evidence | crossExamination | deliberation | verdict (derived), plus
//                   caseCall | judgeIntroduction while the court is called (amendment ad, from `openingStep`)
//   CourtOpeningStep entrance → caseCall → judgeIntroduction → opening (the opening flow, `CourtCaseCallDirector`)
//   CharacterState  idle | speaking | reacting (plaintiff, defendant)
//   JudgeState      idle | speaking | gavel | deliberating
// `CourtMotionDirector` derives those from `CourtroomState` and owns ONE cancellable job: the shared clock that plays
// the ambient idles (blink, 1 px settle / bob, crowd clusters; randomised 2.5–5 s per character, staggered) and the
// event beats (talk loop while a bubble reveals, gavel, reactions). Views read poses from it; everything else (bubble
// entrance, text reveal, exhibit rise, stamps, the dock) is a one-shot Compose animation started when that view
// appears. Time is injected (sleep / now / random) so the schedule is deterministic in tests. Reduce Motion: no job
// at all; no talk, gavel or bounce.
@file:Suppress("ClassName", "EnumEntryName")

package app.plead.android.courtroom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.plead.android.models.AICall
import app.plead.android.models.CaseStatus
import app.plead.android.models.Exhibit
import app.plead.android.models.ObjectionRuling
import app.plead.android.models.Role
import app.plead.android.models.Speaker
import app.plead.android.models.TrialPhase
import app.plead.android.models.Turn
import app.plead.android.services.Analytics
import app.plead.android.services.isRevealed
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// MARK: - State model

enum class CourtPhase(val rawValue: String) {
    opening("opening"), testimony("testimony"), evidence("evidence"), crossExamination("crossExamination"),
    deliberation("deliberation"), verdict("verdict"),

    /**
     * Amendment ad: the opening flow before any testimony. Never derived from the case: the director reports them
     * while its `openingStep` is on the NOW HEARING card / the judge's introduction.
     */
    caseCall("caseCall"), judgeIntroduction("judgeIntroduction");

    /** Ambient energy: how busy the room is (brief §7). Always inside the 2.5–5 s spacing band. */
    val crowdSpacing: ClosedFloatingPointRange<Double>
        get() = when (this) {
            deliberation -> 3.8..5.0
            verdict -> 2.5..3.8
            opening, crossExamination, caseCall, judgeIntroduction -> 3.0..5.0
            testimony, evidence -> 2.8..5.0
        }
    val judgeSpacing: ClosedFloatingPointRange<Double>
        get() = when (this) {
            opening, crossExamination, caseCall, judgeIntroduction -> 2.5..4.0
            deliberation -> 4.0..5.0
            else -> 3.0..5.0
        }

    companion object {
        /** The phase the room is in, from the case. */
        fun derive(s: CourtroomState): CourtPhase {
            if (CourtroomLogic.isDeliberating(s)) return deliberation
            when (s.kase.status) {
                CaseStatus.trial -> Unit
                CaseStatus.deliberating, CaseStatus.awaitingVerdict -> return deliberation
                else -> return if (s.verdict != null || s.kase.isRevealed) verdict else opening
            }
            val phase = s.kase.phase ?: return opening
            return when (phase) {
                // The judge opens the court; once a party has spoken it is their testimony.
                TrialPhase.plaintiffOpening, TrialPhase.defendantOpening ->
                    if (s.turns.any { it.speaker != Speaker.judge }) testimony else opening
                TrialPhase.plaintiffExhibits, TrialPhase.defendantExhibits -> evidence
                TrialPhase.crossExamination -> crossExamination
                TrialPhase.plaintiffClosing, TrialPhase.defendantClosing -> testimony
            }
        }
    }
}

enum class CharacterState { idle, speaking, reacting }
enum class JudgeState { idle, speaking, gavel, deliberating }

/** Gavel frames: raise → strike → 1-frame impact hold → return (then the painted gavel is back at rest). */
enum class GavelFrame { rest, raised, struck, returning }

/**
 * One figure's pose. `lift` is in points (1 = the 1–2 px settle / bob, 2 = a reaction hop), `lean` a scale delta for
 * the speaking "forward emphasis".
 */
data class CourtFigurePose(
    val eyesClosed: Boolean = false,
    val mouthOpen: Boolean = false,
    val lift: Float = 0f,
    val lean: Float = 0f,
) {
    val isRest: Boolean get() = this == CourtFigurePose()
}

/** Everything that moves on the shared clock. */
enum class CourtActor {
    judge, plaintiff, defendant, crowdLeftBack, crowdLeftFront, crowdRightBack, crowdRightFront;

    val crowdIndex: Int? get() = crowd.indexOf(this).takeIf { it >= 0 }

    companion object {
        val crowd: List<CourtActor> = listOf(crowdLeftBack, crowdLeftFront, crowdRightBack, crowdRightFront)
        fun party(r: Role): CourtActor = if (r == Role.plaintiff) plaintiff else defendant
    }
}

// MARK: - Timing tokens (brief §9)

object CourtMotionTiming {
    /** Ambient idle spacing per character (randomised, staggered). */
    val ambientSpacing: ClosedFloatingPointRange<Double> = 2.5..5.0

    /** First idle after the court appears, then one character every `ambientStagger` (+ jitter). */
    const val ambientFirstBeat: Double = 0.9
    const val ambientStagger: Double = 0.45
    val ambientJitter: ClosedFloatingPointRange<Double> = 0.0..0.3
    const val blink: Double = 0.12

    /** A bob / settle holds the lifted pose this long; the view eases in and out over `bobEase`. */
    const val bobHold: Double = 0.6
    const val bobEase: Double = 0.45

    /** Bubble entrance: fade + scale 0.96 → 1 + 6 pt rise, restrained spring (180–250 ms). */
    const val bubbleEntrance: Double = 0.22
    const val bubbleRise: Float = 6f
    const val bubbleScale: Float = 0.96f

    /** Speaker label / chip first, dialogue 80–120 ms later. */
    const val bodyDelay: Double = 0.1

    /** Line reveal: opacity + 2–4 pt rise, 60–100 ms stagger. */
    const val lineStagger: Double = 0.08
    const val lineDuration: Double = 0.2
    const val lineRise: Float = 3f

    /** Cross-examination: each numbered question after the previous one, quickly. */
    const val questionStagger: Double = 0.14

    /** Talk loop: 2-frame mouth at ≈ 9 fps while the text reveals (bounded). */
    const val talkFrame: Double = 0.11
    const val talkMin: Double = 0.45
    const val talkMax: Double = 1.6

    /** Character state change (lean in / out, nameplate highlight): 180–300 ms. */
    const val characterChange: Double = 0.22

    /** Gavel: 0.09 raise + 0.05 strike + 0.05 impact hold + 0.08 return = 0.27 s (200–300 ms). */
    const val gavelRaise: Double = 0.09
    const val gavelStrike: Double = 0.05
    const val gavelHold: Double = 0.05
    const val gavelReturn: Double = 0.08
    val gavelTotal: Double get() = gavelRaise + gavelStrike + gavelHold + gavelReturn

    /** Exhibit reveal: rise 12 pt + fade with a small settle (250–400 ms); EXHIBIT label lands after. */
    const val exhibitRise: Float = 12f
    const val exhibitDuration: Double = 0.32
    const val exhibitLabelDelay: Double = 0.14

    /** OBJECTION / SUSTAINED / OVERRULED stamp: scale overshoot (180–260 ms). */
    const val stamp: Double = 0.22
    const val stampFromScale: Float = 1.3f

    /** Scene / state transition (dock crossfade): 250–350 ms. */
    const val stateTransition: Double = 0.3

    /** Dock "your turn" pulse (once). */
    const val pulse: Double = 0.9

    /** A party's reaction hop, and the crowd's one restrained group reaction. */
    const val reactionHold: Double = 0.28
    const val crowdReactionStagger: Double = 0.07
    const val crowdHop: Float = 2f

    /**
     * Figures (judge, gavel, two parties, crowd layer) that may be mid-motion at once. Each is one view with one
     * implicit animation: the scene never runs more than this many character animations.
     */
    const val maxConcurrentMotions = 5
}

// MARK: - Reveal plan

/**
 * Timings for one bubble's staged reveal. The renderer reveals the real laid-out lines; the plan's line counts are an
 * estimate used for the talk loop and analytics.
 */
data class CourtRevealPlan(
    val entrance: Double = CourtMotionTiming.bubbleEntrance,
    val entranceRise: Float = CourtMotionTiming.bubbleRise,
    val entranceScale: Float = CourtMotionTiming.bubbleScale,
    val bodyDelay: Double = CourtMotionTiming.bodyDelay,
    val lineStagger: Double = CourtMotionTiming.lineStagger,
    val lineDuration: Double = CourtMotionTiming.lineDuration,
    val lineRise: Float = CourtMotionTiming.lineRise,
    val questionStagger: Double = CourtMotionTiming.questionStagger,
    /** Estimated lines per text block (one block for plain speech, one per cross-examination question). */
    val lineCounts: List<Int> = listOf(1),
) {
    /** When text block `block` starts revealing (after the bubble's header). */
    fun start(block: Int): Double = bodyDelay + max(0, block).toDouble() * questionStagger

    /** Every line fully visible. */
    val total: Double
        get() = lineCounts.withIndex().maxOfOrNull { (b, n) -> start(b) + (max(1, n) - 1).toDouble() * lineStagger + lineDuration }
            ?: (bodyDelay + lineDuration)

    /** The speaker's talk loop: while the text reveals, bounded so it never becomes a long animation. */
    val talkDuration: Double
        get() = min(CourtMotionTiming.talkMax, max(CourtMotionTiming.talkMin, total - bodyDelay))

    companion object {
        fun make(body: String, questions: List<String>, charsPerLine: Int = 30, lineLimit: Int? = null): CourtRevealPlan {
            val blocks = if (questions.isEmpty()) listOf(body) else questions
            return CourtRevealPlan(
                lineCounts = blocks.map { text ->
                    val n = estimatedLines(text, charsPerLine)
                    if (questions.isEmpty()) min(n, lineLimit ?: n) else min(n, 2)
                },
            )
        }

        fun make(model: CourtBubbleModel, lineLimit: Int? = null): CourtRevealPlan =
            make(body = model.turn.body, questions = model.questions, charsPerLine = if (model.side == null) 34 else 28, lineLimit = lineLimit)

        /** Greedy word wrap at `charsPerLine` (≥ 1 line). */
        fun estimatedLines(text: String, charsPerLine: Int): Int {
            val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) return 1
            var lines = 1
            var used = 0
            for (w in words) {
                val len = w.length
                if (used == 0) {
                    used = len
                } else if (used + 1 + len <= charsPerLine) {
                    used += 1 + len
                } else {
                    lines += 1; used = len
                }
            }
            return lines
        }

        /** Progress (0…1, eased) of line `line` of a block that starts at `start`, `elapsed` seconds in. */
        fun lineProgress(elapsed: Double, start: Double, stagger: Double, duration: Double, line: Int): Double {
            val t = (elapsed - start - line.toDouble() * stagger) / max(duration, 0.001)
            val c = min(1.0, max(0.0, t))
            return 1 - (1 - c) * (1 - c) // ease out
        }
    }
}

// MARK: - Events

/**
 * What a newly revealed turn means for the room. Only openings, rulings, major transitions and verdicts earn the
 * gavel (amendment x).
 */
sealed class CourtMotionEvent {
    data object opening : CourtMotionEvent()
    data class ruling(val r: ObjectionRuling?) : CourtMotionEvent()
    data object majorTransition : CourtMotionEvent()
    data object verdict : CourtMotionEvent()
    data object judgeLine : CourtMotionEvent()
    data object crossQuestions : CourtMotionEvent()
    data class partyLine(val r: Role) : CourtMotionEvent()
    data class objection(val r: Role) : CourtMotionEvent()
    data class pass(val r: Role) : CourtMotionEvent()

    val triggersGavel: Boolean
        get() = this is opening || this is ruling || this is majorTransition || this is verdict

    /** One restrained crowd reaction on key moments. */
    val triggersCrowd: Boolean
        get() = this is ruling || this is verdict || this is objection

    companion object {
        fun classify(t: Turn, turns: List<Turn>): CourtMotionEvent {
            when (val k = CourtroomLogic.bubbleKind(t)) {
                is CourtBubbleKind.party -> return partyLine(k.role)
                is CourtBubbleKind.objection -> return objection(k.role)
                is CourtBubbleKind.pass -> return pass(k.role)
                is CourtBubbleKind.judgeRuling -> return ruling(k.ruling)
                CourtBubbleKind.safety -> return judgeLine
                CourtBubbleKind.judge -> Unit
            }
            if (t.aiCall == AICall.verdict) return verdict
            val judgeTurns = turns.filter { it.speaker == Speaker.judge && !it.isSafetyNotice }
            val i = judgeTurns.indexOfFirst { it.id == t.id }
            // Synthesised lines (settlement / judgement banner): no ceremony.
            if (i < 0) return judgeLine
            if (i == 0) return opening
            val group: (Turn) -> String = { it.phase?.chipTitle ?: "adjourned" }
            if (group(judgeTurns[i - 1]) != group(t)) return majorTransition
            return if (t.aiCall == AICall.crossExamine || t.questions.isNotEmpty()) crossQuestions else judgeLine
        }
    }
}

// MARK: - Ambient scheduler (pure)

/** Next idle beat per character: first beats staggered, then every 2.5–5 s (phase-shaped), randomised. */
class CourtAmbientScheduler(start: Double, actors: List<CourtActor>, random: (ClosedFloatingPointRange<Double>) -> Double) {
    private val _next = mutableMapOf<CourtActor, Double>()
    val next: Map<CourtActor, Double> get() = _next

    init {
        actors.forEachIndexed { i, a ->
            _next[a] = start + CourtMotionTiming.ambientFirstBeat + i.toDouble() * CourtMotionTiming.ambientStagger +
                random(CourtMotionTiming.ambientJitter)
        }
    }

    val earliest: Double? get() = _next.values.minOrNull()

    /** Pops every actor due at `now` (in a stable order) and schedules its next beat `spacing(actor)` later. */
    fun popDue(
        now: Double,
        spacing: (CourtActor) -> ClosedFloatingPointRange<Double>,
        random: (ClosedFloatingPointRange<Double>) -> Double,
    ): List<CourtActor> {
        val due = _next.entries.filter { it.value <= now + 0.0001 }
            .map { it.key to it.value }
            .sortedWith(compareBy<Pair<CourtActor, Double>>({ it.second }, { it.first.ordinal }))
        for ((a, at) in due) {
            val range = spacing(a)
            val clamped = max(range.start, CourtMotionTiming.ambientSpacing.start)..min(range.endInclusive, CourtMotionTiming.ambientSpacing.endInclusive)
            // Measured from when it was due (so a late wake-up doesn't bunch the next beats), never in the past.
            _next[a] = max(at, now - 0.25) + random(clamped)
        }
        return due.map { it.first }
    }
}

// MARK: - Analytics (no dialogue or evidence content, ever)

object CourtMotionAnalytics {
    enum class Event(val rawValue: String) {
        screenOpened("courtroom_screen_opened"),
        dialogueRevealStarted("dialogue_reveal_started"),
        dialogueRevealCompleted("dialogue_reveal_completed"),
        exhibitRevealed("exhibit_revealed"),
        objectionShown("objection_shown"),
        verdictRevealStarted("verdict_reveal_started"),
        verdictRevealCompleted("verdict_reveal_completed"),
        reducedMotionActive("reduced_motion_active"),
        timeToFirstDialogueVisible("time_to_first_dialogue_visible"),
    }

    /** Property keys that may be sent. Values are enums / counts / durations only. */
    val allowedKeys: Set<String> = setOf("phase", "speaker", "kind", "lines", "type", "ruling", "ms", "reduce_motion", "outcome")

    /** Swappable for tests; defaults to the app's analytics seam. */
    var sink: (String, Map<String, String>) -> Unit = { e, p -> Analytics.track(e, p) }

    fun track(e: Event, props: Map<String, String> = emptyMap()) {
        assert(props.keys.all { it in allowedKeys }) { "courtroom motion analytics must not carry content" }
        sink(e.rawValue, props.filterKeys { it in allowedKeys })
    }
}

/** Turns already revealed this launch (a revisit shows them at once; a fresh launch reveals again). */
class CourtRevealMemory {
    val turns: MutableSet<UUID> = mutableSetOf()
    val exhibits: MutableSet<UUID> = mutableSetOf()
    val stamps: MutableSet<String> = mutableSetOf()
    val pulses: MutableSet<String> = mutableSetOf()

    companion object {
        val shared = CourtRevealMemory()
    }
}

// MARK: - Director

class CourtMotionDirector(
    private val sleep: suspend (Double) -> Unit = { delay((it * 1000).toLong()) },
    private val now: () -> Double = { System.nanoTime() / 1e9 },
    private val random: (ClosedFloatingPointRange<Double>) -> Double = { r ->
        if (r.endInclusive <= r.start) r.start else Random.nextDouble(r.start, r.endInclusive)
    },
    memory: CourtRevealMemory? = null,
    private val scope: CoroutineScope = MainScope(),
) {
    private val memory: CourtRevealMemory = memory ?: CourtRevealMemory.shared

    // Derived state (views read these).
    var phase: CourtPhase by mutableStateOf(CourtPhase.opening)
        private set
    var judgeState: JudgeState by mutableStateOf(JudgeState.idle)
        private set
    var plaintiffState: CharacterState by mutableStateOf(CharacterState.idle)
        private set
    var defendantState: CharacterState by mutableStateOf(CharacterState.idle)
        private set

    /** The side holding the floor (the other one waits: calmer idles). */
    var floor: Role? by mutableStateOf(null)
        private set

    // Poses (one property per figure, so a blink redraws only that figure).
    var judgePose: CourtFigurePose by mutableStateOf(CourtFigurePose())
        private set
    var plaintiffPose: CourtFigurePose by mutableStateOf(CourtFigurePose())
        private set
    var defendantPose: CourtFigurePose by mutableStateOf(CourtFigurePose())
        private set
    var crowdLift: List<Float> by mutableStateOf(List(CourtActor.crowd.size) { 0f })
        private set
    var gavel: GavelFrame by mutableStateOf(GavelFrame.rest)
        private set

    /** Reduce Motion: no clock at all; figures rest; never talk / gavel / bounce. */
    var reduceMotion: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            if (value) {
                stopTask(); settle()
            } else {
                restartIfNeeded()
            }
        }

    /**
     * Entrance in progress (amendment ac): the judge is still walking to the bench, so a newly revealed line earns no
     * gavel or judge talk loop; the entrance's own tap (`playEntranceGavel`) calls the court to order.
     */
    var entranceHold: Boolean by mutableStateOf(false)

    /**
     * Amendment ad: where the opening flow is (`CourtCaseCallDirector.step`, mirrored by the scene). Until it reaches
     * `opening` the record waits: bubbles are held (except the judge's introduction), the easel and the "your turn"
     * pulse stay out, and `phase` reports `caseCall` / `judgeIntroduction`.
     */
    private var _openingStep by mutableStateOf(CourtOpeningStep.opening)
    var openingStep: CourtOpeningStep
        get() = _openingStep
        set(value) {
            if (value != _openingStep) {
                _openingStep = value
                refreshPhase()
            }
        }

    /**
     * The judge's introduction bubble (a client-rendered line, not part of the record): the one bubble that may reveal
     * while the case is being called.
     */
    var introductionTurnId: UUID? = null

    /** The case call has not finished (entrance still running before it, the card, or the introduction). */
    val caseCallHold: Boolean get() = openingStep != CourtOpeningStep.opening

    /** The first turn waits (entrance or case call): no "your turn" pulse yet. */
    val openingHold: Boolean get() = entranceHold || caseCallHold

    /**
     * Is this bubble's reveal waiting for the court (the entrance, or the case call for everything but the judge's
     * introduction)?
     */
    fun isHeld(turnId: UUID): Boolean = entranceHold || (caseCallHold && turnId != introductionTurnId)

    /** True while the (single) clock job exists. */
    val isRunning: Boolean get() = task != null

    // MARK: Test / debug hooks

    /** Every ambient beat (actor, time) — tests check spacing and stagger. */
    var onBeat: ((CourtActor, Double) -> Unit)? = null

    /** Every gavel strike (tests check it only follows allowed events). */
    var gavelStrikes = 0
        private set

    /** Most figures mid-motion at once since creation (tests + the debug assertion). */
    var peakConcurrentMotions = 0
        private set

    /** Clock jobs started so far (each replaces the previous one; tests check only one is ever alive). */
    var tasksStarted = 0
        private set

    /** Light impact when a ruling stamp lands (the view layer plays it; never under Reduce Motion). */
    var onRulingHaptic: (() -> Unit)? = null

    private var task: Job? = null
    private var generation = 0
    private var scheduler: CourtAmbientScheduler? = null
    private val timeline: MutableList<TimelineItem> = mutableListOf()
    private var seq = 0
    private var visible = false
    private var active = true

    /** Until when each figure is busy with an event (talk / gavel / reaction): idles skip it. */
    private val busyUntil: MutableMap<CourtActor, Double> = mutableMapOf()
    private var openedAt: Double? = null
    private var firstDialogueTracked = false
    private var derivedPhase: CourtPhase = CourtPhase.opening

    private class TimelineItem(val at: Double, val seq: Int, val change: Change)

    sealed class Change {
        data class eyes(val a: CourtActor, val v: Boolean) : Change()
        data class mouth(val a: CourtActor, val v: Boolean) : Change()
        data class lift(val a: CourtActor, val v: Float) : Change()
        data class lean(val a: CourtActor, val v: Float) : Change()
        data class gavel(val f: GavelFrame) : Change()
        data class judge(val s: JudgeState) : Change()
        data class party(val r: Role, val s: CharacterState) : Change()
        data class revealDone(val speaker: String, val lines: Int) : Change()
    }

    // MARK: Lifecycle

    /** The court is on screen. */
    fun appear(analytics: Boolean = true) {
        visible = true
        openedAt = now()
        firstDialogueTracked = false
        if (analytics) {
            CourtMotionAnalytics.track(
                CourtMotionAnalytics.Event.screenOpened,
                mapOf("phase" to phase.rawValue, "reduce_motion" to if (reduceMotion) "1" else "0"),
            )
            if (reduceMotion) CourtMotionAnalytics.track(CourtMotionAnalytics.Event.reducedMotionActive, mapOf("phase" to phase.rawValue))
        }
        restartIfNeeded()
    }

    /** Off screen: cancel the clock, everyone back at rest. */
    fun disappear() {
        visible = false
        stopTask()
        settle()
    }

    /** The app is in the foreground (Swift `scenePhase == .active`). Backgrounded / inactive pauses the clock. */
    fun setActive(isActive: Boolean) {
        if (isActive == active) return
        active = isActive
        if (isActive) {
            restartIfNeeded()
        } else {
            stopTask(); settle()
        }
    }

    /** Cancels the clock (idempotent). */
    fun stopTask() {
        task?.cancel()
        task = null
        generation += 1
    }

    /** Awaits the current clock job (tests). */
    suspend fun join() {
        task?.join()
    }

    private val shouldRun: Boolean get() = visible && active && !reduceMotion

    private fun restartIfNeeded() {
        if (!shouldRun) return
        if (scheduler == null) {
            scheduler = CourtAmbientScheduler(start = now(), actors = ambientActors, random = random)
        }
        // Replace (never run beside) the previous clock: there is only ever one.
        task?.cancel()
        generation += 1
        val mine = generation
        tasksStarted += 1
        task = scope.launch {
            loop()
            if (generation == mine) task = null
        }
    }

    private val ambientActors: List<CourtActor>
        get() = listOf(
            CourtActor.judge, CourtActor.plaintiff, CourtActor.crowdLeftBack, CourtActor.defendant,
            CourtActor.crowdRightFront, CourtActor.crowdLeftFront, CourtActor.crowdRightBack,
        )

    // MARK: Derivation

    /** Re-derive phase / floor / judge state from the case. Call on appear and whenever the state changes. */
    fun update(s: CourtroomState) {
        derivedPhase = CourtPhase.derive(s)
        refreshPhase()
        val p = derivedPhase
        val f = CourtroomLogic.activeRole(s)
        if (floor != f) floor = f
        if (p == CourtPhase.deliberation) {
            if (judgeState != JudgeState.deliberating) {
                judgeState = JudgeState.deliberating; judgePose = CourtFigurePose()
            }
        } else if (judgeState == JudgeState.deliberating) {
            judgeState = JudgeState.idle
        }
    }

    /** The case's phase, or the opening flow's step while the court is called. */
    private fun refreshPhase() {
        val p = when (openingStep) {
            CourtOpeningStep.caseCall -> CourtPhase.caseCall
            CourtOpeningStep.judgeIntroduction -> CourtPhase.judgeIntroduction
            CourtOpeningStep.entrance, CourtOpeningStep.opening -> derivedPhase
        }
        if (phase != p) phase = p
    }

    fun state(r: Role): CharacterState = if (r == Role.plaintiff) plaintiffState else defendantState
    fun pose(r: Role): CourtFigurePose = if (r == Role.plaintiff) plaintiffPose else defendantPose

    /** The waiting party (not holding the floor while someone else does) idles more calmly. */
    fun isCalm(r: Role): Boolean = floor != null && floor != r

    // MARK: Reveals

    fun hasRevealed(id: UUID): Boolean = memory.turns.contains(id)

    /**
     * A bubble for `model.turn` has just appeared. Returns the reveal plan the first time this launch (null when
     * already revealed, or under Reduce Motion: show at once). Starts the speaker's talk loop, the gavel / crowd
     * reaction when the event earns them, and the analytics.
     */
    fun claimReveal(model: CourtBubbleModel, turns: List<Turn>, lineLimit: Int? = null): CourtRevealPlan? {
        val id = model.turn.id
        if (memory.turns.contains(id)) return null
        memory.turns.add(id)
        val plan = CourtRevealPlan.make(model, lineLimit = lineLimit)
        val event = CourtMotionEvent.classify(model.turn, turns)
        val speaker = speakerName(model.turn.speaker)
        val lines = plan.lineCounts.sum()
        CourtMotionAnalytics.track(
            CourtMotionAnalytics.Event.dialogueRevealStarted,
            mapOf("speaker" to speaker, "kind" to kindName(event), "lines" to "$lines"),
        )
        val opened = openedAt
        if (!firstDialogueTracked && opened != null) {
            firstDialogueTracked = true
            val ms = (((now() - opened) + (if (reduceMotion) 0.0 else plan.bodyDelay)) * 1000).toInt()
            CourtMotionAnalytics.track(
                CourtMotionAnalytics.Event.timeToFirstDialogueVisible,
                mapOf("ms" to "${max(0, ms)}", "phase" to phase.rawValue),
            )
        }
        if (reduceMotion) {
            // Shown at once: the reveal is complete as it appears.
            CourtMotionAnalytics.track(CourtMotionAnalytics.Event.dialogueRevealCompleted, mapOf("speaker" to speaker, "lines" to "$lines"))
            return null
        }
        if (!shouldRun) {
            // Visual reveal only (the clock is paused): no talk loop or gavel.
            CourtMotionAnalytics.track(CourtMotionAnalytics.Event.dialogueRevealCompleted, mapOf("speaker" to speaker, "lines" to "$lines"))
            return plan
        }
        val t = now()
        val judgeAway = entranceHold && model.turn.speaker == Speaker.judge
        if (event.triggersGavel && !entranceHold) scheduleGavel(t)
        if (!judgeAway) scheduleTalk(model.turn.speaker, from = t + plan.bodyDelay, duration = plan.talkDuration)
        when (event) {
            is CourtMotionEvent.objection -> scheduleReaction(event.r.other, t + 0.12)
            is CourtMotionEvent.ruling -> {
                // The side that objected reacts to the ruling.
                val ex = model.turn.exhibitId
                if (ex != null) {
                    val objector = turns.lastOrNull { it.exhibitId == ex && it.isObjection && it.speaker != Speaker.judge }
                    val r = objector?.let { CourtroomLogic.role(it.speaker) }
                    if (r != null) scheduleReaction(r, t + CourtMotionTiming.gavelTotal)
                }
            }
            else -> Unit
        }
        if (event.triggersCrowd) scheduleCrowdReaction(t + CourtMotionTiming.gavelTotal)
        insert(t + plan.total, Change.revealDone(speaker = speaker, lines = lines))
        restartIfNeeded()
        return plan
    }

    /**
     * What a bubble should do on appear (amendment ac): wait for the court (the entrance is still running; the text is
     * already in the accessibility tree, only its visual entrance waits), play its entrance with `plan`, or show at
     * once (already revealed, Reduce Motion).
     */
    sealed class RevealDecision {
        data object held : RevealDecision()
        data class play(val plan: CourtRevealPlan) : RevealDecision()
        data object shown : RevealDecision()
    }

    /**
     * `claimReveal`, gated on the entrance: nothing is claimed (no talk loop, gavel or analytics) while `entranceHold`
     * is set, so the bubble can claim it once the court is ready.
     */
    fun requestReveal(model: CourtBubbleModel, turns: List<Turn>, lineLimit: Int? = null): RevealDecision {
        if (memory.turns.contains(model.turn.id)) return RevealDecision.shown
        if (isHeld(model.turn.id)) return RevealDecision.held
        return claimReveal(model, turns, lineLimit)?.let { RevealDecision.play(it) } ?: RevealDecision.shown
    }

    /** An exhibit card went up on the easel (first time this launch). Returns true when it should rise in. */
    fun exhibitRevealed(ex: Exhibit, live: Boolean): Boolean {
        if (memory.exhibits.contains(ex.id)) return false
        memory.exhibits.add(ex.id)
        CourtMotionAnalytics.track(CourtMotionAnalytics.Event.exhibitRevealed, mapOf("type" to ex.type.rawValue, "kind" to if (live) "live" else "mini"))
        val owner = floor
        if (owner != null && !reduceMotion && shouldRun) {
            // The presenter leans in as the card lands.
            val t = now()
            busyUntil[CourtActor.party(owner)] = t + 0.5
            insert(t, Change.lean(CourtActor.party(owner), 0.03f))
            insert(t + 0.45, Change.lean(CourtActor.party(owner), 0f))
            restartIfNeeded()
        }
        return !reduceMotion
    }

    fun hasShownStamp(key: String): Boolean = memory.stamps.contains(key)
    fun isFreshExhibit(id: UUID): Boolean = !memory.exhibits.contains(id)

    /** A stamp landed (OBJECTION pending, SUSTAINED, OVERRULED). Returns true when it should slam in. */
    fun stampShown(key: String, text: String): Boolean {
        if (memory.stamps.contains(key)) return false
        memory.stamps.add(key)
        CourtMotionAnalytics.track(CourtMotionAnalytics.Event.objectionShown, mapOf("ruling" to text.lowercase()))
        if (reduceMotion) return false
        if (text != "OBJECTION") onRulingHaptic?.invoke()
        return true
    }

    /** The dock asks: pulse once for this "you must act" state? (once per state per launch) */
    fun claimPulse(key: String): Boolean {
        if (memory.pulses.contains(key)) return false
        memory.pulses.add(key)
        return true
    }

    // MARK: Entrance (amendment ac)

    /**
     * The entrance's one gavel tap (`CourtEntranceDirector.gavelTaps`). Nothing under Reduce Motion or while the clock
     * is paused (the ready court shows the gavel at rest).
     */
    fun playEntranceGavel() {
        if (reduceMotion || !shouldRun) return
        scheduleGavel(now())
        restartIfNeeded()
    }

    // MARK: Verdict choreography (P2)

    /** Ambient → judge activates → gavel. Nothing under Reduce Motion. */
    fun playVerdictOpening() {
        CourtMotionAnalytics.track(CourtMotionAnalytics.Event.verdictRevealStarted, mapOf("reduce_motion" to if (reduceMotion) "1" else "0"))
        if (reduceMotion || !shouldRun) return
        val t = now() + 0.45
        insert(t, Change.judge(JudgeState.speaking))
        insert(t, Change.lean(CourtActor.judge, 0.03f))
        busyUntil[CourtActor.judge] = t + 1.2
        scheduleGavel(t + 0.35)
        insert(t + 0.9, Change.lean(CourtActor.judge, 0f))
        insert(t + 0.9, Change.judge(JudgeState.idle))
        restartIfNeeded()
    }

    /** Winner highlight + one crowd micro-reaction. */
    fun playVerdictOutcome(winner: Role?, tie: Boolean) {
        CourtMotionAnalytics.track(
            CourtMotionAnalytics.Event.verdictRevealCompleted,
            mapOf("outcome" to if (tie) "tie" else (winner?.rawValue ?: "none")),
        )
        if (reduceMotion || !shouldRun) return
        val t = now()
        if (winner != null) scheduleReaction(winner, t + 0.1)
        scheduleCrowdReaction(t + 0.2)
        restartIfNeeded()
    }

    // MARK: Scheduling

    private fun insert(at: Double, c: Change) {
        seq += 1
        val item = TimelineItem(at, seq, c)
        val i = timeline.indexOfFirst { it.at > at }.let { if (it < 0) timeline.size else it }
        timeline.add(i, item)
    }

    private fun scheduleGavel(t: Double) {
        val T = CourtMotionTiming
        busyUntil[CourtActor.judge] = max(busyUntil[CourtActor.judge] ?: 0.0, t + T.gavelTotal)
        insert(t, Change.judge(JudgeState.gavel))
        insert(t, Change.gavel(GavelFrame.raised))
        insert(t + T.gavelRaise, Change.gavel(GavelFrame.struck))
        insert(t + T.gavelRaise + T.gavelStrike + T.gavelHold, Change.gavel(GavelFrame.returning))
        insert(t + T.gavelTotal, Change.gavel(GavelFrame.rest))
        insert(t + T.gavelTotal, Change.judge(JudgeState.idle))
    }

    private fun scheduleTalk(speaker: Speaker, from: Double, duration: Double) {
        val t = from
        val actor = when (speaker) {
            Speaker.judge -> CourtActor.judge
            Speaker.plaintiff -> CourtActor.plaintiff
            Speaker.defendant -> CourtActor.defendant
        }
        val end = t + duration
        busyUntil[actor] = max(busyUntil[actor] ?: 0.0, end)
        when (speaker) {
            Speaker.judge -> insert(t, Change.judge(JudgeState.speaking))
            Speaker.plaintiff -> insert(t - CourtMotionTiming.bodyDelay, Change.party(Role.plaintiff, CharacterState.speaking))
            Speaker.defendant -> insert(t - CourtMotionTiming.bodyDelay, Change.party(Role.defendant, CharacterState.speaking))
        }
        insert(t, Change.lean(actor, 0.02f))
        var at = t
        var open = true
        while (at < end - 0.001) {
            insert(at, Change.mouth(actor, open))
            open = !open
            at += CourtMotionTiming.talkFrame
        }
        insert(end, Change.mouth(actor, false))
        insert(end, Change.lean(actor, 0f))
        when (speaker) {
            Speaker.judge -> insert(end, Change.judge(JudgeState.idle))
            Speaker.plaintiff -> insert(end, Change.party(Role.plaintiff, CharacterState.idle))
            Speaker.defendant -> insert(end, Change.party(Role.defendant, CharacterState.idle))
        }
    }

    private fun scheduleReaction(r: Role, t: Double) {
        val a = CourtActor.party(r)
        busyUntil[a] = max(busyUntil[a] ?: 0.0, t + CourtMotionTiming.reactionHold + 0.1)
        insert(t, Change.party(r, CharacterState.reacting))
        insert(t, Change.lift(a, CourtMotionTiming.crowdHop))
        insert(t + CourtMotionTiming.reactionHold, Change.lift(a, 0f))
        insert(t + CourtMotionTiming.reactionHold, Change.party(r, CharacterState.idle))
    }

    private fun scheduleCrowdReaction(t: Double) {
        CourtActor.crowd.forEachIndexed { i, a ->
            val s = t + i.toDouble() * CourtMotionTiming.crowdReactionStagger
            busyUntil[a] = s + CourtMotionTiming.reactionHold + 0.2
            insert(s, Change.lift(a, CourtMotionTiming.crowdHop))
            insert(s + CourtMotionTiming.reactionHold, Change.lift(a, 0f))
        }
    }

    /** One idle beat for `a` at `t`. */
    private fun scheduleIdle(a: CourtActor, t: Double) {
        onBeat?.invoke(a, t)
        val until = busyUntil[a]
        if (until != null && until > t) return
        val T = CourtMotionTiming
        when (a) {
            CourtActor.judge -> {
                // Mostly a blink, sometimes the 1 px settle (never an obvious loop).
                if (random(0.0..1.0) < 0.6) {
                    insert(t, Change.eyes(a, true)); insert(t + T.blink, Change.eyes(a, false))
                } else {
                    insert(t, Change.lift(a, 1f)); insert(t + T.bobHold, Change.lift(a, 0f))
                }
            }
            CourtActor.plaintiff, CourtActor.defendant -> {
                val r = if (a == CourtActor.plaintiff) Role.plaintiff else Role.defendant
                // Waiting (the other side has the floor): calmer, mostly blinks.
                val blinkOdds = if (isCalm(r)) 0.75 else 0.5
                if (random(0.0..1.0) < blinkOdds) {
                    insert(t, Change.eyes(a, true)); insert(t + T.blink, Change.eyes(a, false))
                } else {
                    insert(t, Change.lift(a, 1f)); insert(t + T.bobHold, Change.lift(a, 0f))
                }
            }
            else -> {
                insert(t, Change.lift(a, 1f)); insert(t + T.bobHold * 1.2, Change.lift(a, 0f))
            }
        }
    }

    private fun spacing(a: CourtActor): ClosedFloatingPointRange<Double> = when (a) {
        CourtActor.judge -> phase.judgeSpacing
        CourtActor.plaintiff, CourtActor.defendant ->
            if (isCalm(if (a == CourtActor.plaintiff) Role.plaintiff else Role.defendant)) 3.5..5.0 else 2.5..4.5
        else -> phase.crowdSpacing
    }

    // MARK: Clock

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val t = now()
            applyDue(t)
            val wake = listOfNotNull(timeline.firstOrNull()?.at, scheduler?.earliest).minOrNull() ?: return
            try {
                sleep(max(wake - t, 1.0 / 120))
            } catch (e: Exception) {
                return
            }
        }
    }

    private fun applyDue(t: Double) {
        val s = scheduler
        if (s != null) {
            val due = s.popDue(t, ::spacing, random)
            for (a in due) {
                if (!(judgeState == JudgeState.deliberating && a == CourtActor.judge)) scheduleIdle(a, t)
            }
        }
        while (timeline.isNotEmpty() && timeline.first().at <= t + 0.0005) {
            val first = timeline.removeAt(0)
            apply(first.change)
        }
        val moving = motionCount
        peakConcurrentMotions = max(peakConcurrentMotions, moving)
        assert(moving <= CourtMotionTiming.maxConcurrentMotions) { "too many courtroom animations at once" }
    }

    /** Figures mid-motion right now (the crowd is one layer). */
    val motionCount: Int
        get() = listOf(
            !judgePose.isRest, gavel != GavelFrame.rest, !plaintiffPose.isRest, !defendantPose.isRest, crowdLift.any { it != 0f },
        ).count { it }

    private fun apply(c: Change) {
        // Reduce Motion: nothing that talks, swings or bounces is ever applied.
        if (reduceMotion) {
            when (c) {
                is Change.mouth, is Change.gavel, is Change.lift, is Change.lean -> return
                is Change.judge -> if (c.s == JudgeState.gavel) return
                else -> Unit
            }
        }
        when (c) {
            is Change.eyes -> mutate(c.a) { it.copy(eyesClosed = c.v) }
            is Change.mouth -> mutate(c.a) { it.copy(mouthOpen = c.v) }
            is Change.lift -> mutate(c.a) { it.copy(lift = c.v) }
            is Change.lean -> mutate(c.a) { it.copy(lean = c.v) }
            is Change.gavel -> {
                if (c.f == GavelFrame.struck) gavelStrikes += 1
                gavel = c.f
            }
            is Change.judge -> {
                if (judgeState == JudgeState.deliberating) return
                // A talk loop ending mid-gavel doesn't cut the gavel short.
                if (c.s == JudgeState.idle && gavel != GavelFrame.rest && judgeState == JudgeState.gavel) return
                if (c.s == JudgeState.speaking && judgeState == JudgeState.gavel) return
                judgeState = c.s
            }
            is Change.party -> if (c.r == Role.plaintiff) plaintiffState = c.s else defendantState = c.s
            is Change.revealDone -> CourtMotionAnalytics.track(
                CourtMotionAnalytics.Event.dialogueRevealCompleted,
                mapOf("speaker" to c.speaker, "lines" to "${c.lines}"),
            )
        }
    }

    private fun mutate(a: CourtActor, f: (CourtFigurePose) -> CourtFigurePose) {
        when (a) {
            CourtActor.judge -> f(judgePose).let { if (it != judgePose) judgePose = it }
            CourtActor.plaintiff -> f(plaintiffPose).let { if (it != plaintiffPose) plaintiffPose = it }
            CourtActor.defendant -> f(defendantPose).let { if (it != defendantPose) defendantPose = it }
            else -> {
                val i = a.crowdIndex ?: return
                val p = f(CourtFigurePose(lift = crowdLift[i]))
                if (p.lift != crowdLift[i]) crowdLift = crowdLift.toMutableList().also { it[i] = p.lift }
            }
        }
    }

    /**
     * Everyone back at rest, pending beats dropped (talk states end; reveals are already complete for the reader since
     * the text is real and on screen).
     */
    private fun settle() {
        for (item in timeline) {
            val c = item.change
            if (c is Change.revealDone) {
                CourtMotionAnalytics.track(CourtMotionAnalytics.Event.dialogueRevealCompleted, mapOf("speaker" to c.speaker, "lines" to "${c.lines}"))
            }
        }
        timeline.clear()
        busyUntil.clear()
        scheduler = null
        if (!judgePose.isRest) judgePose = CourtFigurePose()
        if (!plaintiffPose.isRest) plaintiffPose = CourtFigurePose()
        if (!defendantPose.isRest) defendantPose = CourtFigurePose()
        if (crowdLift.any { it != 0f }) crowdLift = List(CourtActor.crowd.size) { 0f }
        if (gavel != GavelFrame.rest) gavel = GavelFrame.rest
        if (judgeState == JudgeState.speaking || judgeState == JudgeState.gavel) judgeState = JudgeState.idle
        if (plaintiffState != CharacterState.idle) plaintiffState = CharacterState.idle
        if (defendantState != CharacterState.idle) defendantState = CharacterState.idle
    }

    companion object {
        fun speakerName(s: Speaker): String = when (s) {
            Speaker.judge -> "judge"
            Speaker.plaintiff -> "plaintiff"
            Speaker.defendant -> "defendant"
        }

        fun kindName(e: CourtMotionEvent): String = when (e) {
            CourtMotionEvent.opening -> "opening"
            is CourtMotionEvent.ruling -> "ruling"
            CourtMotionEvent.majorTransition -> "transition"
            CourtMotionEvent.verdict -> "verdict"
            CourtMotionEvent.judgeLine -> "judge_line"
            CourtMotionEvent.crossQuestions -> "cross_questions"
            is CourtMotionEvent.partyLine -> "party_line"
            is CourtMotionEvent.objection -> "objection"
            is CourtMotionEvent.pass -> "pass"
        }
    }
}
