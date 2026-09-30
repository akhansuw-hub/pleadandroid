// Port of ArgueWin/Courtroom/CourtCaseCallLive.swift: the case call's opening flow (CONTRACTS-v2 amendment ad, motion
// brief §18):
//
//   entrance ──(entrance ready / restored court)──▶ caseCall ──(2.5 s or tap)──▶ judgeIntroduction
//            ──(reveal + reading hold, ≥ 3 s, or tap once revealed)──▶ opening
//
//   entrance            the shared entrance (amendment ac) is still running; the record waits
//   caseCall            the NOW HEARING card (`CourtCaseCallCard`) over the stage
//   judgeIntroduction   the card is replaced by the judge's bubble with `CourtCaseCall.judgeLine` (line reveal, judge
//                       talk loop, everyone else idle); a tap first completes the reveal, the next one moves on
//   opening             the existing opening: the AI judge's own opening line, then the plaintiff's turn
//
// Until `opening`: no evidence on the easel, no "Show your evidence" tray (the dock shows the judge has the floor), no
// "your turn" pulse, and no record bubble shows (they stay in the accessibility tree, masked). ONE cancellable job on
// an injected clock drives it (no timers); `finish()` snaps to `opening`. Reduce Motion: the same steps, with the card
// and the full text shown without staged movement.
@file:Suppress("EnumEntryName")

package app.plead.android.courtroom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The opening flow's step (amendment ad). */
enum class CourtOpeningStep(val rawValue: String) {
    entrance("entrance"), caseCall("caseCall"), judgeIntroduction("judgeIntroduction"), opening("opening")
}

object CourtCaseCallTiming {
    /** The NOW HEARING card auto-advances after this long. */
    const val card: Double = 2.5

    /** The introduction stays up at least this long (from when it appears). */
    const val introductionMinimum: Double = 3.0

    /** Reading time after the reveal completes. */
    const val introductionReading: Double = 2.6

    /** How long the introduction stays up in total, for a reveal of `reveal` seconds. */
    fun introductionHold(reveal: Double): Double = max(introductionMinimum, reveal + introductionReading)
}

class CourtCaseCallDirector(
    val call: CourtCaseCall,
    /** This open calls the case (false: the court opens straight on `opening`). */
    val plays: Boolean,
    val reduceMotion: Boolean,
    /** The judge's introduction bubble's turn id (a client-rendered line, never part of the record). */
    val introductionTurnId: UUID = UUID.randomUUID(),
    private val sleep: suspend (Double) -> Unit = { delay((it * 1000).toLong()) },
    private val scope: CoroutineScope = MainScope(),
) {
    var step: CourtOpeningStep by mutableStateOf(if (plays) CourtOpeningStep.entrance else CourtOpeningStep.opening)
        private set

    /** The introduction's text is fully revealed (after its reveal, a tap, or at once under Reduce Motion). */
    var introductionRevealed: Boolean by mutableStateOf(false)
        private set

    /** Frozen on a step for stills (debug `AWCourtEntrance caseCall|introduction`): no job, taps ignored. */
    var frozen: Boolean by mutableStateOf(false)
        private set

    /** Test / debug hook: every step change with the seconds since `begin()`. */
    var onStep: ((CourtOpeningStep, Double) -> Unit)? = null

    private var task: Job? = null
    private var generation = 0
    private var elapsed: Double = 0.0

    /** The record waits (bubbles, easel, tray, pulse) until the court has been called. */
    val holdsRecord: Boolean get() = step != CourtOpeningStep.opening
    val isRunning: Boolean get() = task != null

    /** The introduction's staged reveal (the judge bubble's plan), 0 under Reduce Motion. */
    val introductionRevealDuration: Double
        get() = if (reduceMotion) 0.0 else CourtRevealPlan.make(body = call.judgeLine, questions = emptyList(), charsPerLine = 34).total

    // MARK: Flow

    /** The court is ready (entrance done, or a restored court): show the card and run the flow. */
    fun begin() {
        if (!plays || step != CourtOpeningStep.entrance || frozen) return
        elapsed = 0.0
        run(CourtOpeningStep.caseCall)
    }

    /**
     * A tap anywhere: the card advances to the introduction; during the introduction the first tap completes the
     * reveal and the next one moves on to the opening. Nothing during the entrance (the entrance takes that tap).
     */
    fun tap() {
        if (frozen) return
        when (step) {
            CourtOpeningStep.caseCall -> run(CourtOpeningStep.judgeIntroduction)
            CourtOpeningStep.judgeIntroduction -> if (introductionRevealed) finish() else introductionRevealed = true
            CourtOpeningStep.entrance, CourtOpeningStep.opening -> Unit
        }
    }

    /** Straight to the opening (leaving the screen, the trial moved on, a restored court). */
    fun finish() {
        cancel()
        frozen = false
        introductionRevealed = true
        set(CourtOpeningStep.opening)
    }

    /** Stills: hold on `s` (the introduction fully revealed), no job. */
    fun freeze(s: CourtOpeningStep) {
        cancel()
        frozen = true
        introductionRevealed = s == CourtOpeningStep.judgeIntroduction
        set(s)
    }

    /** Awaits the running flow (tests). */
    suspend fun join() {
        task?.join()
    }

    // MARK: Running

    private fun run(start: CourtOpeningStep) {
        cancel()
        val reveal = introductionRevealDuration
        generation += 1
        val mine = generation
        enter(start)
        task = scope.launch {
            if (start == CourtOpeningStep.caseCall) {
                if (!wait(CourtCaseCallTiming.card, mine)) return@launch
                enter(CourtOpeningStep.judgeIntroduction)
            }
            val hold = CourtCaseCallTiming.introductionHold(reveal)
            if (reveal > 0) {
                if (!wait(reveal, mine)) return@launch
                introductionRevealed = true
            }
            if (!wait(hold - reveal, mine)) return@launch
            task = null
            introductionRevealed = true
            set(CourtOpeningStep.opening)
        }
    }

    private fun enter(s: CourtOpeningStep) {
        if (s == CourtOpeningStep.judgeIntroduction) introductionRevealed = reduceMotion
        set(s)
    }

    /** Sleeps on the injected clock; false when cancelled or superseded. */
    private suspend fun wait(t: Double, mine: Int): Boolean {
        try {
            sleep(max(0.0, t))
        } catch (e: Exception) {
            return false
        }
        if (!currentCoroutineContext().isActive || generation != mine) return false
        elapsed += max(0.0, t)
        return true
    }

    private fun set(s: CourtOpeningStep) {
        if (step == s) return
        step = s
        onStep?.invoke(s, elapsed)
    }

    private fun cancel() {
        task?.cancel(); task = null
        generation += 1
    }
}

/** What waits for the case call (amendment ad), as pure functions the scene and the dock share. */
object CourtCaseCallGate {
    /**
     * The dock while the case is called: the judge has the floor (no turn, no "Show your evidence" tray, no objection
     * window); other states (deliberating, stopped…) are untouched.
     */
    fun dockMode(m: DockMode, calling: Boolean): DockMode {
        if (!calling) return m
        return when (m) {
            is DockMode.compose, is DockMode.objectionWindow, is DockMode.waiting -> DockMode.judgeHasFloor
            else -> m
        }
    }

    /** No evidence on the easel while the case is called. */
    fun easel(p: CourtroomLogic.EaselPresentation?, calling: Boolean): CourtroomLogic.EaselPresentation? =
        if (calling) null else p
}
