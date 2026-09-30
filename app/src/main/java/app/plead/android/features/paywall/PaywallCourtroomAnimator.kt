// Port of ArgueWin/Features/Paywall/PaywallCourtroomAnimator.swift. One Swift `Task` → one coroutine `Job` on the
// scope the view passes in (`rememberCoroutineScope()`); time is injectable exactly as in Swift.
package app.plead.android.features.paywall

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Timing of the paywall courtroom loop (CONTRACTS-v2 amendment v, brief §6). Frames change at a deliberately
 * pixel-art cadence: no frame is held for less than ~1/12 s.
 */
object PaywallCourtroomTiming {
    /** The hero's own fade/rise is `PaywallEntrance` (.hero); the characters hold still while it plays. */
    const val entrance = 0.4
    val talk: ClosedFloatingPointRange<Double> = 0.7..0.9

    /** Talking alternates these mouths; the talk duration is split evenly across them (≈ 7–8 fps). */
    val talkFrames: List<PartnerFrame> = listOf(
        PartnerFrame.talkA, PartnerFrame.talkB, PartnerFrame.talkA, PartnerFrame.idle, PartnerFrame.talkB, PartnerFrame.talkA,
    )
    val gap: ClosedFloatingPointRange<Double> = 0.2..0.4

    /** Judge reacts: the other partner's reaction + a blink. */
    const val blink = 0.12
    const val afterBlink = 0.14

    /** Raise → strike → recoil → idle: 0.16 + 0.12 + 0.10 = 0.38 s (brief: 0.35–0.5 s, the fastest motion). */
    const val gavelUp = 0.16
    const val gavelDown = 0.12
    const val gavelRecoil = 0.10
    val gavelSwing: Double get() = gavelUp + gavelDown + gavelRecoil
    val idleHold: ClosedFloatingPointRange<Double> = 1.2..2.0

    /** A repeated gavel waits at least this long after the previous one. */
    val gavelInterval: ClosedFloatingPointRange<Double> = 7.0..10.0

    /** Plan-selection gavel taps are ignored inside this window after the last one. */
    const val tapCooldown = 1.5

    /** The shortest hold of any frame (≤ 12 fps). */
    const val minimumFrame = 1.0 / 12
}

/**
 * Drives the paywall courtroom's frames with ONE cancellable coroutine (no timers). `start()` on appear, `stop()` on
 * disappear / background. Time is injectable (`sleep`, `now`, `random`) so the sequence is deterministic in tests.
 *
 * One loop (≈ 5 s): entrance hold (first loop) → plaintiff talks 0.7–0.9 s → gap 0.2–0.4 s (defendant reacts) →
 * defendant talks → judge reacts (plaintiff reacts, judge blinks, then the gavel: raise → strike → recoil, only when
 * 7–10 s have passed since the last one; the first loop always strikes) → idle hold 1.2–2 s with one 1 px breath →
 * repeat. Reduce Motion: no coroutine at all, the characters rest (static).
 */
class PaywallCourtroomAnimator(
    private val scope: CoroutineScope,
    private val sleep: suspend (Double) -> Unit = { delay((it * 1000).toLong()) },
    private val now: () -> Double = { System.nanoTime() / 1e9 },
    private val random: (ClosedFloatingPointRange<Double>) -> Double = { Random.nextDouble(it.start, it.endInclusive) },
) {
    var pose: CourtroomPose by mutableStateOf(CourtroomPose.rest)
        private set

    /** True while the loop coroutine exists. */
    val isRunning: Boolean get() = task != null

    /** Set by the view from Reduce Motion. While true nothing animates. */
    var reduceMotion: Boolean = false
        set(value) {
            field = value
            if (value && isRunning) stop()
        }

    /**
     * Called once per animator (one paywall presentation) when the gavel first lands. The view plays one light
     * impact there; never on later strikes, never under Reduce Motion (no strike happens then).
     */
    var onFirstStrike: (() -> Unit)? = null

    private var task: Job? = null
    private var generation = 0
    private var playedEntrance = false
    private var lastGavel: Double? = null
    private var nextGavelGap: Double = PaywallCourtroomTiming.gavelInterval.start
    private var lastTap: Double? = null
    var hasStruck: Boolean = false
        private set

    // MARK: Control

    fun start() {
        if (task != null) return
        pose = CourtroomPose.rest
        if (reduceMotion) return
        run { loop() }
    }

    /** Cancels the coroutine and puts everyone back at rest. Safe to call repeatedly. */
    fun stop() {
        task?.cancel()
        task = null
        generation += 1
        pose = CourtroomPose.rest
    }

    /**
     * Plan selection: one quick blink + gavel, then the loop carries on from the idle hold. Ignored under Reduce
     * Motion, while stopped, and within `tapCooldown` of the previous tap.
     */
    fun gavelTap() {
        if (reduceMotion || task == null) return
        val t = now()
        lastTap?.let { if (t - it < PaywallCourtroomTiming.tapCooldown) return }
        lastTap = t
        task?.cancel()
        generation += 1
        run {
            // The judge reacts as in the loop: a blink, then the gavel. The blink here also keeps blinks 3–6 s apart
            // when a tap lands just before the loop's own blink (the tap resumes at the idle hold).
            if (!show(CourtroomPose(judge = JudgeFrame.blink), PaywallCourtroomTiming.blink)) return@run
            if (!strike()) return@run
            loop(skipToIdle = true)
        }
    }

    private fun run(body: suspend () -> Unit) {
        generation += 1
        val mine = generation
        task = scope.launch {
            body()
            // A coroutine that ended by itself (never, in practice: the loop is endless) clears its handle.
            if (generation == mine) task = null
        }
    }

    // MARK: Loop

    /** Shows [pose] for [seconds]. Returns false when cancelled (the caller must then stop without touching `pose`). */
    private suspend fun show(pose: CourtroomPose, seconds: Double): Boolean {
        if (!currentCoroutineContext().isActive) return false
        this.pose = pose
        try {
            sleep(maxOf(seconds, PaywallCourtroomTiming.minimumFrame))
        } catch (e: CancellationException) {
            return false
        }
        return currentCoroutineContext().isActive
    }

    private suspend fun talk(who: PaywallCourtroomSprites.Character, listener: PartnerFrame): Boolean {
        val frames = PaywallCourtroomTiming.talkFrames
        val each = random(PaywallCourtroomTiming.talk) / frames.size
        for (f in frames) {
            val p = if (who == PaywallCourtroomSprites.Character.plaintiff) {
                CourtroomPose(plaintiff = f, defendant = listener)
            } else {
                CourtroomPose(defendant = f, plaintiff = listener)
            }
            if (!show(p, each)) return false
        }
        return true
    }

    private suspend fun strike(): Boolean {
        val t = PaywallCourtroomTiming
        lastGavel = now()
        nextGavelGap = random(t.gavelInterval)
        if (!show(CourtroomPose(judge = JudgeFrame.gavelUp), t.gavelUp)) return false
        if (!currentCoroutineContext().isActive) return false
        pose = CourtroomPose(judge = JudgeFrame.gavelDown)
        if (!hasStruck) {
            hasStruck = true
            onFirstStrike?.invoke()
        }
        try {
            sleep(t.gavelDown)
        } catch (e: CancellationException) {
            return false
        }
        return show(CourtroomPose(judge = JudgeFrame.gavelRecoil), t.gavelRecoil)
    }

    private val gavelDue: Boolean
        get() {
            val last = lastGavel ?: return true
            return now() - last >= nextGavelGap
        }

    private suspend fun loop(skipToIdle: Boolean = false) {
        val t = PaywallCourtroomTiming
        var skip = skipToIdle
        while (currentCoroutineContext().isActive) {
            if (!skip) {
                if (!playedEntrance) {
                    playedEntrance = true
                    if (!show(CourtroomPose.rest, t.entrance)) return
                }
                if (!talk(PaywallCourtroomSprites.Character.plaintiff, listener = PartnerFrame.idle)) return
                if (!show(CourtroomPose(defendant = PartnerFrame.react), random(t.gap))) return
                if (!talk(PaywallCourtroomSprites.Character.defendant, listener = PartnerFrame.idle)) return
                if (!show(CourtroomPose(plaintiff = PartnerFrame.react), t.afterBlink)) return
                if (!show(CourtroomPose(judge = JudgeFrame.blink, plaintiff = PartnerFrame.react), t.blink)) return
                if (!show(CourtroomPose.rest, t.afterBlink)) return
                if (gavelDue) {
                    if (!strike()) return
                }
            }
            skip = false
            // Idle hold: the judge takes one 1 px breath in the middle of it.
            val hold = random(t.idleHold)
            if (!show(CourtroomPose.rest, hold * 0.35)) return
            if (!show(CourtroomPose(judge = JudgeFrame.idle2), hold * 0.35)) return
            if (!show(CourtroomPose.rest, hold * 0.30)) return
        }
    }
}
