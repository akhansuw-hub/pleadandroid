// Port of ArgueWin/Features/Paywall/PaywallOpeningHaptics.swift. Core Haptics → `Vibrator` (PORT.md §2): one
// amplitude waveform shaped like the Core Haptics intensity curve where the motor has amplitude control, otherwise
// the same two light taps as the iOS fallback.
package app.plead.android.features.paywall

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import app.plead.android.app.PleadApplication
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The swell under the paywall opening's bloom (Amendment q; ported from Bagged's `IntroHaptics`).
 *
 * One continuous vibration rather than a run of taps: what's on screen is one object growing, not a count of
 * things, so the vibration has a shape (in from almost nothing, hardest at ~78% of its length, which lands under
 * the heart popping on, then released to silence) instead of a rhythm.
 *
 * Where the vibrator has no amplitude control it falls back to two light taps.
 */
object PaywallOpeningHaptics {
    /** Base intensity of the continuous event; the curve below scales intensity 0…1 against `peak`. */
    const val peak: Float = 0.72f
    const val sharpness: Float = 0.28f

    /** Where in the swell the intensity peaks, as a fraction of its duration. */
    const val peakAt: Double = 0.78

    /** How long a cancel takes to fade the swell out (a continuous event cut dead ends on a felt click). */
    const val release: Double = 0.12

    /** One waveform step (the intensity curve is sampled at this rate). */
    const val stepMillis: Long = 20

    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var fallback: Job? = null
    private var playing = false

    /** Rises with the mark and releases as it settles. */
    fun swell(duration: Double) {
        cancel(gently = false)
        val vibrator = vibrator() ?: return
        if (vibrator.hasAmplitudeControl()) {
            val (timings, amplitudes) = waveform(duration)
            runCatching { vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) }
            playing = true
        } else {
            playTaps(vibrator, duration)
        }
    }

    /**
     * Ends the swell. Gently by default: the intensity is ramped to zero over `release` and only then stopped,
     * because a continuous event stopped outright drops straight to nothing and you can feel the edge.
     */
    fun cancel(gently: Boolean = true) {
        fallback?.cancel()
        fallback = null
        if (!playing) return
        playing = false
        val vibrator = vibrator() ?: return
        if (!gently || !vibrator.hasAmplitudeControl()) {
            vibrator.cancel()
            return
        }
        val steps = maxOf(1, (release * 1000 / stepMillis).roundToInt())
        val timings = LongArray(steps) { stepMillis }
        val start = 0.3f * peak * 255f
        val amplitudes = IntArray(steps) { i -> (start * (1f - i.toFloat() / steps)).roundToInt().coerceIn(0, 255) }
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1)) }
    }

    /**
     * The intensity curve as a waveform (pure; unit-tested): barely there (0.08), hardest at [peakAt], back to zero
     * at the end so the pattern finishes in silence rather than on a click. Amplitudes are 0…255 against [peak].
     */
    fun waveform(duration: Double): Pair<LongArray, IntArray> {
        val total = (duration * 1000).roundToLong()
        val steps = maxOf(1, (total / stepMillis).toInt())
        val timings = LongArray(steps) { stepMillis }
        val amplitudes = IntArray(steps) { i ->
            val t = (i + 0.5) / steps
            (intensity(t) * peak * 255.0).roundToInt().coerceIn(0, 255)
        }
        return timings to amplitudes
    }

    /** The control curve at `t` (0…1 of the swell): 0.08 → 1 at [peakAt] → 0. */
    fun intensity(t: Double): Double = when {
        t <= 0 -> 0.08
        t < peakAt -> 0.08 + (1 - 0.08) * (t / peakAt)
        t < 1 -> 1 - (t - peakAt) / (1 - peakAt)
        else -> 0.0
    }

    /** No amplitude control: two light taps, one as the mark surfaces and one on the peak (the heart). */
    private fun playTaps(vibrator: Vibrator, duration: Double) {
        fallback = scope.launch {
            var elapsed = 0.0
            for (at in listOf(duration * 0.35, duration * peakAt)) {
                delay(((at - elapsed) * 1000).roundToLong())
                elapsed = at
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                    } else {
                        vibrator.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
                    }
                }
            }
        }
    }

    private fun vibrator(): Vibrator? {
        val context = PleadApplication.contextOrNull ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()?.takeIf { it.hasVibrator() }
    }
}
