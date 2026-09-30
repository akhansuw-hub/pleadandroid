// Port of ArgueWin/Features/ColdOpen/ColdOpenTimeline.swift.
package app.plead.android.features.coldopen

import androidx.compose.ui.geometry.Offset
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Pure timing for the cold open (docs/cold-open-brief/BRIEF.md §2). Everything the view draws is a function of
 * the elapsed time `t` (seconds) since playback started, so the sequence is deterministic, skippable at any point and
 * tunable here without touching the view. This table is the single source of truth for every timing.
 *
 * Full (5.8 s, then a 0.5 s crossfade to the next screen):
 * 0.0–1.3 courthouse push-in 1.0→1.05 + window-glow pulse · 1.3–2.8 doors part over 1.0 s (ease-in-out),
 * bloom peaking mid-opening, 0.3 s hold on the open doorway · 2.8–4.0 judge push-in 1.0→1.12 over 1.0 s,
 * 0.2 s hold · 4.0 hard cut to the gavel: raise/mid at 8 fps, impact held 250 ms (haptic + ≤ 4 pt shake),
 * settle, rest · 5.0–5.8 end card, logo 0.94→1.0 + fade over 0.5 s, 0.3 s hold.
 * Reduce Motion: static crossfades 1 → 3 → 5, ~1.5 s each (4.5 s). Repeat launches: a 1.2 s logo sting.
 */
object ColdOpenTimeline {
    // MARK: Durations

    fun duration(mode: ColdOpenCoordinator.Mode, reduceMotion: Boolean): Double = when (mode) {
        ColdOpenCoordinator.Mode.none -> 0.0
        ColdOpenCoordinator.Mode.sting -> Sting.total
        ColdOpenCoordinator.Mode.full -> if (reduceMotion) Reduced.total else Full.total
    }

    /** Taps before this are ignored (brief: "after ~1 second, a tap may skip"). */
    const val skipAllowedAfter: Double = 1.0

    /** Crossfade from the end card into whatever the gate shows next (RootScreen). */
    const val crossfade: Double = 0.5

    /** The coordinator force-completes after this, whatever happens (preload stall, stuck timeline). */
    const val hardTimeout: Double = 8.0

    /** The end-card logo resolves from this scale to 1.0. */
    const val logoStartScale: Double = 0.94

    object Full {
        val fadeFromLaunch = 0.0..0.15

        // 1 · Courthouse
        val courthousePush = 0.0..1.3
        val courthouseScale = 1.0..1.05

        // 2 · Doors: fade over the courthouse, part (ease-in-out), hold on the open doorway.
        val doorsIn = 1.2..1.4
        val doorsPart = 1.45..2.45
        const val doorsHoldEnd: Double = 2.8
        val bloom = 1.45..2.65
        const val bloomPeak: Double = 0.25

        // 3 · Judge: crossfade in, push toward the bench, hold.
        val judgeIn = 2.75..2.95
        val judgePush = 2.8..3.8
        val judgeScale = 1.0..1.12

        // 4 · Gavel: hard cut; the gavel is fully on screen from this instant.
        const val gavelCut: Double = 4.0

        /**
         * Start of each gavel frame relative to the cut: raised, mid (8 fps), impact (held 250 ms),
         * settling, rest (held until the end card covers it).
         */
        val gavelFrameStarts = listOf(0.0, 0.125, 0.25, 0.5, 0.625)
        val gavelFrameCount: Int get() = gavelFrameStarts.size

        /** Frame 3 (index 2) is the strike. */
        const val impactFrame = 2
        val impact: Double get() = gavelCut + gavelFrameStarts[impactFrame]
        const val shakeDuration: Double = 0.15
        const val shakeMax: Double = 4.0

        // 5 · End card
        val endcardIn = 4.9..5.1
        val logoIn = 5.0..5.5
        const val total: Double = 5.8
    }

    object Reduced {
        val judgeIn = 1.35..1.65
        val endcardIn = 2.85..3.15
        const val total: Double = 4.5
    }

    object Sting {
        val endcardIn = 0.0..0.25
        val logoIn = 0.05..0.55
        const val total: Double = 1.2
    }

    // MARK: Curves

    /** 0…1 progress of `t` through `range`, clamped. */
    fun progress(t: Double, range: ClosedFloatingPointRange<Double>): Double {
        if (range.endInclusive <= range.start) return if (t >= range.start) 1.0 else 0.0
        return min(1.0, max(0.0, (t - range.start) / (range.endInclusive - range.start)))
    }

    fun easeInOut(x: Double): Double = if (x < 0.5) 2 * x * x else 1 - (-2 * x + 2).pow(2) / 2
    fun easeOut(x: Double): Double = 1 - (1 - x).pow(3)
    fun easeIn(x: Double): Double = x * x * x

    fun lerp(r: ClosedFloatingPointRange<Double>, x: Double): Double = r.start + (r.endInclusive - r.start) * x

    // MARK: Full sequence

    fun courthouseScale(t: Double): Double = lerp(Full.courthouseScale, easeInOut(progress(t, Full.courthousePush)))

    /** Warm window glow: one soft breath across the courthouse beat. */
    fun windowGlow(t: Double): Double {
        if (t >= Full.doorsIn.endInclusive) return 0.0
        return 0.18 + 0.22 * sin(PI * progress(t, Full.courthousePush))
    }

    /** 0 = closed, 1 = fully out of frame. */
    fun doorsOpen(t: Double): Double = easeInOut(progress(t, Full.doorsPart))

    /** White bloom 0 → 0.25 → 0 as the doors open. */
    fun bloom(t: Double): Double {
        val x = progress(t, Full.bloom)
        if (x <= 0 || x >= 1) return 0.0
        return Full.bloomPeak * sin(PI * x)
    }

    fun judgeScale(t: Double): Double = lerp(Full.judgeScale, easeInOut(progress(t, Full.judgePush)))

    /** Gavel frame index (0…4) at `t`, or null before the cut. Holds the last frame. */
    fun gavelFrame(t: Double): Int? {
        if (t < Full.gavelCut) return null
        val dt = t - Full.gavelCut
        return Full.gavelFrameStarts.indexOfLast { it <= dt + 1e-9 }.takeIf { it >= 0 } ?: 0
    }

    /** Screen shake right after the strike: ≤ `shakeMax` points (dp), decaying to 0 over `shakeDuration`. */
    fun shake(t: Double): Offset {
        val dt = t - Full.impact
        if (dt < 0 || dt >= Full.shakeDuration) return Offset.Zero
        val decay = 1 - dt / Full.shakeDuration
        val phase = 2 * PI * dt / 0.04
        return Offset((3.6 * sin(phase) * decay).toFloat(), (1.6 * cos(phase * 1.3) * decay).toFloat())
    }

    /** A brief warm flash on the strike frame. */
    fun impactFlash(t: Double): Double {
        val dt = t - Full.impact
        if (dt < 0 || dt >= 0.1) return 0.0
        return 0.16 * (1 - dt / 0.1)
    }

    /** Logo reveal: `logoStartScale`→1.0 scale and 0→1 opacity. */
    data class LogoReveal(val scale: Double, val opacity: Double)

    fun logoReveal(t: Double, range: ClosedFloatingPointRange<Double>): LogoReveal {
        val x = easeOut(progress(t, range))
        return LogoReveal(scale = logoStartScale + (1 - logoStartScale) * x, opacity = x)
    }
}
