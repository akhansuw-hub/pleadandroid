// Partial port of ArgueWin/Features/Onboarding/MockTrial/MockTrialPlayer.swift: `MockTrialTiming` (the beat schedule,
// verbatim) and `MockTrialPhase`, plus an interim `MockTrialPlayer` that walks the same fourteen beats on the same
// part times and dwells (autoplay, tap to complete / advance, the commit window, skip, CASE CLOSED waits, TalkBack
// turns autoplay off, Reduce Motion shows each beat whole). What it does not do yet: the shared court entrance
// (`CourtEntranceDirector`), the line-by-line bubble reveal (`CourtRevealPlan`), talk / gavel / crowd cues and the
// help-sheet pause — they are built on wave 3a's courtroom engine. After the 3a merge this file is replaced by the
// full port (and `MockTrialSceneTests`, which PORT.md §7 assigns to 3a, is ported against it).
package app.plead.android.features.onboarding

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    const val verdictCard: Double = 0.34
    const val verdictBounce: Double = 0.22

    /** Invitation copy / START button fade on START. */
    const val invitationFade: Double = 0.15

    /** From the court being ready to CASE CLOSED when left to autoplay. */
    val autoplayTotal: Double get() = MockTrialBeat.entries.mapNotNull { dwell[it] }.sum()
}

/** Where the onboarding step is: the invitation, the court's load-in, or the trial. */
enum class MockTrialPhase { invitation, entrance, trial }

/**
 * Interim player (see the file header): the beat state machine of Swift `MockTrialPlayer` without the courtroom
 * engine. Compose snapshot state; the coroutine [scope] and [now] are injectable for tests.
 */
class MockTrialPlayer(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
    private val now: () -> Double = { System.nanoTime() / 1e9 },
) {
    /** Invitation until START MOCK TRIAL; nothing plays while it is on screen. */
    var phase: MockTrialPhase by mutableStateOf(MockTrialPhase.invitation)
        private set
    var currentBeat: MockTrialBeat by mutableStateOf(MockTrialBeat.opening)
        private set
    /** How many of the current beat's parts are on screen. */
    var partsShown: Int by mutableIntStateOf(0)
        private set
    /** Everything in the beat is on screen (a tap completes it). */
    var revealComplete: Boolean by mutableStateOf(false)
        private set

    val step: MockTrialStep get() = MockTrialScript.step(currentBeat)
    val visibleParts: List<MockTrialPart> get() = if (phase == MockTrialPhase.trial) step.parts.take(partsShown) else emptyList()
    fun shows(part: MockTrialPart): Boolean = visibleParts.contains(part)
    val isInvitation: Boolean get() = phase == MockTrialPhase.invitation
    /** The courtroom card is on screen (from START on). */
    val courtShown: Boolean get() = phase != MockTrialPhase.invitation
    /** CASE CLOSED is on screen (the CTA is offered). */
    val isClosed: Boolean get() = phase == MockTrialPhase.trial && currentBeat == MockTrialBeat.closed

    /** The side / judge holding the floor: whoever the latest part belongs to, else the beat's first speaker. */
    val activeSpeaker: MockTrialSpeaker?
        get() {
            if (phase != MockTrialPhase.trial) return null
            if (currentBeat == MockTrialBeat.deliberation || currentBeat == MockTrialBeat.closed) return null
            for (part in visibleParts.reversed()) speaker(part)?.let { return it }
            return step.parts.firstNotNullOfOrNull(::speaker)
        }

    /** Reduce Motion: the whole beat shows at once (no staged reveal). */
    var reduceMotion: Boolean = false
        set(value) {
            field = value
            if (value && phase == MockTrialPhase.trial) showAll()
        }

    /** False under TalkBack: each beat stays until the user advances (autoplay is never mandatory). */
    var autoplay: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (wantsRun) restart()
        }

    val isRunning: Boolean get() = job != null

    var onBeatChange: ((MockTrialBeat) -> Unit)? = null
    var onSkip: (() -> Unit)? = null
    var onContinue: (() -> Unit)? = null

    private var job: Job? = null
    private var wantsRun = false
    private var active = true
    private var lastCommit: Double? = null

    // MARK: Lifecycle

    /** START MOCK TRIAL: invitation → the opening beat and autoplay. Past the invitation it resumes playback. */
    fun start() {
        wantsRun = true
        if (phase != MockTrialPhase.trial) {
            phase = MockTrialPhase.trial
            enter(MockTrialBeat.opening, notify = false)
        } else {
            restart()
        }
    }

    /** Off screen: cancel the task. The beat is kept (coming back resumes it). */
    fun stop() {
        wantsRun = false
        cancel()
    }

    /** Resumed / paused (`scenePhase == .active`). Returning replays the current beat's schedule. */
    fun setActive(isActive: Boolean) {
        if (isActive == active) return
        active = isActive
        if (isActive) restart() else cancel()
    }

    /** A tap: completes the beat if it is still revealing, else moves on (guarded by the commit window). */
    fun advance() {
        if (phase != MockTrialPhase.trial) return
        val t = now()
        lastCommit?.let { if (t - it < MockTrialTiming.commitWindow) return }
        if (!revealComplete) {
            showAll()
            restartDwellOnly()
            return
        }
        val next = currentBeat.next ?: return
        lastCommit = t
        enter(next, notify = true)
    }

    /** SKIP DEMO. */
    fun skip() {
        stop()
        onSkip?.invoke()
    }

    /** I'M READY FOR COURT on CASE CLOSED. */
    fun finish() {
        if (!isClosed) return
        stop()
        onContinue?.invoke()
    }

    /** DEBUG `AWMockTrialBeat <beat>`: that beat, settled, autoplay off. */
    fun debugHold(at: MockTrialBeat) {
        autoplay = false
        phase = MockTrialPhase.trial
        currentBeat = at
        showAll()
    }

    // MARK: Playback

    private fun enter(beat: MockTrialBeat, notify: Boolean) {
        currentBeat = beat
        partsShown = 0
        revealComplete = beat == MockTrialBeat.closed
        if (notify) onBeatChange?.invoke(beat)
        if (reduceMotion) showAll()
        restart()
    }

    private fun showAll() {
        partsShown = step.parts.size
        revealComplete = true
    }

    private fun cancel() {
        job?.cancel()
        job = null
    }

    private fun restart() {
        cancel()
        if (!wantsRun || !active || phase != MockTrialPhase.trial) return
        val beat = currentBeat
        job = scope.launch {
            val times = MockTrialTiming.partTimes[beat].orEmpty()
            var elapsed = 0.0
            for ((i, at) in times.withIndex()) {
                if (i < partsShown) continue
                if (at > elapsed) delay(((at - elapsed) * 1000).toLong())
                elapsed = maxOf(elapsed, at)
                partsShown = i + 1
            }
            revealComplete = true
            dwellThenAdvance(beat, elapsed)
        }
    }

    private fun restartDwellOnly() {
        cancel()
        if (!wantsRun || !active) return
        val beat = currentBeat
        job = scope.launch { dwellThenAdvance(beat, MockTrialTiming.partTimes[beat].orEmpty().lastOrNull() ?: 0.0) }
    }

    private suspend fun dwellThenAdvance(beat: MockTrialBeat, elapsed: Double) {
        val dwell = MockTrialTiming.dwell[beat] ?: return // CASE CLOSED waits for the user.
        if (!autoplay) return
        val rest = dwell - elapsed
        if (rest > 0) delay((rest * 1000).toLong())
        if (currentBeat != beat) return
        val next = beat.next ?: return
        lastCommit = now()
        job = null
        enter(next, notify = true)
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
    }
}
