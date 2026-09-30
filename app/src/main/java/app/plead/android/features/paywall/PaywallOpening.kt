// Port of ArgueWin/Features/Paywall/PaywallOpening.swift.
package app.plead.android.features.paywall

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.PleadLogo
import app.plead.android.services.Analytics
import app.plead.android.services.UserDefaults
import java.util.Locale
import java.util.UUID
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The paywall opening (CONTRACTS-v2 Amendment q, adapted from Bagged's paywall bloom). One tunable table, the
 * same style as `ColdOpenTimeline`: every duration lives here rather than beside the animation that uses it,
 * because the opening is one piece of timing, not six animations — moving the heart earlier means moving the
 * haptic's peak too, and two numbers a hundred lines apart never get moved together.
 *
 * 0.00 plain Warm Cream · 0.10 the mark blooms out of dead centre (0.80 s, custom curve) over a 0.90 s haptic
 * swell · 0.78 the pixel heart pops on, gold rays go out and dissipate (1.05 s) · hold · 1.83 the lockup travels
 * into the header slot while the paywall fades in beneath (0.55 s) · 2.38 the header owns the logo.
 * Tap anywhere after 0.8 s to skip. Reduce Motion: a 0.3 s fade of the settled paywall, no swell.
 */
object PaywallOpeningTiming {
    /**
     * Nothing at all, first. A screen that starts moving on its own first frame reads as something loading,
     * not as an entrance — and this is the one still moment for the hero art to decode before it's needed.
     */
    const val stillness = 0.10

    /** The mark growing out of the middle of the cream. */
    const val bloom = 0.80

    /** Slow out of nothing, then decelerating hard into full size: the mark surfaces rather than pops. */
    fun bloomCurve(duration: Double = bloom): AnimationSpec<Float> =
        tween((duration * 1000).roundToInt(), easing = CubicBezierEasing(0.16f, 0.85f, 0.25f, 1f))

    /** Where the bloom starts, as a scale of the settled mark. */
    const val bloomFromScale = 0.2

    /**
     * The vibration under the growth. It peaks at 78% of its length (`PaywallOpeningHaptics.peakAt` ≈ 0.70 s
     * in), which puts the hardest part of the swell under the heart landing, not in the middle of the growth.
     */
    const val swell = 0.90

    /**
     * From the first movement to the heart arriving. Late enough that the wordmark has all but settled: the
     * heart has to read as a piece added to something that already exists, not as part of the growth.
     */
    const val toBadge = 0.68

    /** The rays going out and dissipating. Longer than anything they overlap, so the light is still leaving while the screen settles. */
    const val rays = 1.05

    /** The settled beat between the heart landing and the lockup moving: long enough to read "Plead" once. */
    const val hold = 1.05

    /** The lockup travelling into the header while the paywall fades in beneath it. */
    const val travel = 0.55

    /**
     * Not part of the opening: the gate arrives through RootScreen's 0.25 s phase crossfade, and a clock started
     * on appear would spend the stillness (and the start of the bloom) with the previous screen still fading out
     * over the mark. The opening's t = 0 is after this.
     */
    const val arrival = 0.25

    /** Taps before this are ignored: early enough to never feel trapped, late enough that the first accidental touch doesn't eat the whole thing. */
    const val skippableAfter = 0.8

    /** A skip settles over this rather than cutting. */
    const val skipSettle = 0.25

    /** Reduce Motion: the settled paywall fades in over this, and nothing else moves. */
    const val reducedFade = 0.3

    /** The mark as drawn during the opening: `PleadLogo(.primary)` this wide, centred… */
    const val markWidth = 200f

    /** …at this fraction of the screen height. Dead centre: the eye tracks the mark and the light coming off it. */
    const val markHeight = 0.5f

    /** The blush glow's radius as a fraction of the mark's width (so it scales with the lockup exactly). */
    const val glowRadius = 0.95f
    const val glowOpacity = 0.12f

    /** When the whole opening is over, measured from the first frame. */
    val total: Double get() = stillness + toBadge + hold + travel

    data class Step(val phase: PaywallOpeningPhase, val at: Double)

    /** Start time of every phase, in order. The director walks this table; nothing else schedules. */
    val schedule: List<Step>
        get() {
            val badge = stillness + toBadge
            val travelAt = badge + hold
            return listOf(
                Step(PaywallOpeningPhase.still, 0.0),
                Step(PaywallOpeningPhase.bloom, stillness),
                Step(PaywallOpeningPhase.badge, badge),
                Step(PaywallOpeningPhase.travel, travelAt),
                Step(PaywallOpeningPhase.settled, travelAt + travel),
            )
        }

    /** The phase at `t` seconds since the opening started. */
    fun phase(at: Double): PaywallOpeningPhase = schedule.lastOrNull { at >= it.at }?.phase ?: PaywallOpeningPhase.still
}

/** Beats of the opening, in order. `badge` = heart pops + rays go out (the hold runs under it). */
enum class PaywallOpeningPhase {
    still, bloom, badge, travel, settled;

    /** The heart is a separate layer: absent until it pops at `badge`, then always there. */
    val heartVisible: Boolean get() = this >= badge
}

// MARK: - Eligibility

enum class PaywallOpeningMode {
    /** The whole choreography. */
    full,

    /** Reduce Motion: a 0.3 s fade of the settled paywall. */
    fade,

    /** Straight to the settled paywall. */
    none,
}

/**
 * When the opening plays: the standard paywall's first appearance per user, and never for the exit offer,
 * a re-show (after a purchase failure, closing and returning), or the partner-paid state.
 */
object PaywallOpeningRule {
    data class Input(
        val stage: PaywallStage = PaywallStage.standard,
        val partnerPaid: Boolean = false,
        /** `paywallOpeningSeen.<uid>` in UserDefaults. */
        val seen: Boolean = false,
        /** The paywall has already been shown in this process (a re-show). */
        val shownThisLaunch: Boolean = false,
        /** `AWPaywallOpening YES` (debug): every appearance, seen or not. */
        val forced: Boolean = false,
        /** `AWPaywallOpening NO` (debug): never (the settled paywall assembles with the entrance instead). */
        val disabled: Boolean = false,
        val reduceMotion: Boolean = false,
    )

    fun mode(i: Input): PaywallOpeningMode {
        if (i.stage != PaywallStage.standard || i.partnerPaid || i.disabled) return PaywallOpeningMode.none
        if (!(i.forced || (!i.seen && !i.shownThisLaunch))) return PaywallOpeningMode.none
        return if (i.reduceMotion) PaywallOpeningMode.fade else PaywallOpeningMode.full
    }

    fun seenKey(userId: UUID?): String = "paywallOpeningSeen.${userId?.toString()?.lowercase() ?: "anon"}"

    /** `AWPaywallOpening YES` (debug builds only: the demo harness is compiled out of release). */
    val forcedFromArguments: Boolean get() = app.plead.android.app.DemoHarness.paywallOpening == true

    /** `AWPaywallOpening NO` given explicitly (absent ≠ NO). */
    val disabledFromArguments: Boolean get() = app.plead.android.app.DemoHarness.paywallOpening == false

    @Volatile var shownThisLaunch: Boolean = false

    /** Decides and records (seen flag + this-launch flag) in one step, so a decision is never made twice. */
    fun consume(
        userId: UUID?,
        stage: PaywallStage,
        partnerPaid: Boolean,
        reduceMotion: Boolean,
        defaults: UserDefaults = UserDefaults.standard,
    ): PaywallOpeningMode {
        val key = seenKey(userId)
        val mode = mode(
            Input(
                stage = stage, partnerPaid = partnerPaid, seen = defaults.bool(key),
                shownThisLaunch = shownThisLaunch, forced = forcedFromArguments,
                disabled = disabledFromArguments, reduceMotion = reduceMotion,
            ),
        )
        if (stage == PaywallStage.standard && !partnerPaid) {
            shownThisLaunch = true
            if (mode != PaywallOpeningMode.none) defaults.set(true, key)
        }
        return mode
    }
}

// MARK: - Director

/**
 * Owns the opening's state and walks `PaywallOpeningTiming.schedule`. Every visual value the overlay draws is a
 * property here, changed together with the animation spec its beat calls for (SwiftUI `withAnimation`).
 * [scope] runs the opening's clock (the view's `rememberCoroutineScope()`); tests call [run] directly.
 */
class PaywallOpeningDirector(private val scope: CoroutineScope? = null) {
    var mode: PaywallOpeningMode by mutableStateOf(PaywallOpeningMode.none)
        private set
    var phase: PaywallOpeningPhase by mutableStateOf(PaywallOpeningPhase.still)
        private set

    /** Mark scale during the bloom (0.2 → 1). */
    var growth: Double by mutableDoubleStateOf(PaywallOpeningTiming.bloomFromScale)
        private set
    var markAlpha: Double by mutableDoubleStateOf(0.0)
        private set

    /** The heart layer: 0 → 1 with a spring (scale + opacity together). */
    var heart: Double by mutableDoubleStateOf(0.0)
        private set

    /** Rays: 0 tucked in → 1 out and gone. Driven linearly; `PaywallOpeningRays` shapes the fade. */
    var burst: Double by mutableDoubleStateOf(0.0)
        private set

    /** The blush glow under the mark. */
    var glow: Double by mutableDoubleStateOf(0.0)
        private set

    /** The lockup is at (or on its way to) the header slot and the paywall is up (or coming up). */
    var arrived: Boolean by mutableStateOf(false)
        private set

    /** The header owns the logo; the flying copy is gone and the paywall is interactive. */
    var handedOver: Boolean by mutableStateOf(false)
        private set

    // The spec each value animates with on its latest change (SwiftUI's `withAnimation(...)` around the write).
    internal var growthSpec: AnimationSpec<Float> = snap(); private set
    internal var markAlphaSpec: AnimationSpec<Float> = snap(); private set
    internal var heartSpec: AnimationSpec<Float> = snap(); private set
    internal var burstSpec: AnimationSpec<Float> = snap(); private set
    internal var glowSpec: AnimationSpec<Float> = snap(); private set
    internal var arrivedSpec: AnimationSpec<Float> = snap(); private set

    var playsHaptics: Boolean = true
    var sleep: suspend (Double) -> Unit = { delay((it * 1000).toLong()) }
    var now: () -> Double = { System.nanoTime() / 1e9 }

    /** Every phase entered, in order (tests; debugging). */
    val entered: MutableList<PaywallOpeningPhase> = mutableListOf()
    private var startedAt: Double? = null
    private var task: Job? = null

    val isPlaying: Boolean get() = mode == PaywallOpeningMode.full && !handedOver
    val contentOpacity: Double get() = if (arrived) 1.0 else 0.0
    val elapsed: Double get() = startedAt?.let { now() - it } ?: 0.0
    val canSkip: Boolean get() = isPlaying && elapsed >= PaywallOpeningTiming.skippableAfter

    fun start(mode: PaywallOpeningMode) {
        task?.cancel()
        this.mode = mode
        when (mode) {
            PaywallOpeningMode.none -> settleInstantly()
            PaywallOpeningMode.fade -> {
                growth = 1.0; heart = 1.0; phase = PaywallOpeningPhase.settled
                arrivedSpec = tween((PaywallOpeningTiming.reducedFade * 1000).roundToInt(), easing = FastOutSlowInEasing)
                arrived = true
                handedOver = true
            }
            PaywallOpeningMode.full -> {
                Analytics.track("paywall_opening_played", mapOf("forced" to PaywallOpeningRule.forcedFromArguments.toString()))
                task = scope?.launch {
                    sleep(PaywallOpeningTiming.arrival)
                    if (!isActive) return@launch
                    run()
                }
            }
        }
    }

    /** The whole opening on its own clock: a straight line of waits through the schedule. */
    suspend fun run() {
        startedAt = now()
        var clock = 0.0
        for (step in PaywallOpeningTiming.schedule) {
            // Waits are measured against the start, not chained, so each sleep's overshoot doesn't accumulate.
            val wait = step.at - maxOf(clock, elapsed)
            if (wait > 0) sleep(wait)
            if (!currentCoroutineContext().isActive || handedOver) return
            clock = step.at
            enter(step.phase)
        }
    }

    /** Tap anywhere after `skippableAfter`: jump to the settled paywall. */
    fun skip() {
        if (!canSkip) return
        Analytics.track(
            "paywall_opening_skipped",
            mapOf("at" to String.format(Locale.US, "%.2f", elapsed), "phase" to phase.name),
        )
        task?.cancel()
        if (playsHaptics) PaywallOpeningHaptics.cancel()
        burstSpec = snap()
        burst = 1.0
        val settle = tween<Float>((PaywallOpeningTiming.skipSettle * 1000).roundToInt(), easing = FastOutSlowInEasing)
        growthSpec = settle; markAlphaSpec = settle; heartSpec = settle; glowSpec = settle; arrivedSpec = settle
        growth = 1.0; markAlpha = 1.0; heart = 1.0; glow = 0.0; arrived = true
        phase = PaywallOpeningPhase.travel
        entered.add(PaywallOpeningPhase.travel)
        task = scope?.launch {
            sleep(PaywallOpeningTiming.skipSettle)
            if (!isActive) return@launch
            enter(PaywallOpeningPhase.settled)
        }
    }

    fun stop() {
        task?.cancel()
        if (playsHaptics) PaywallOpeningHaptics.cancel()
        if (mode == PaywallOpeningMode.full && !handedOver) settleInstantly()
    }

    private fun settleInstantly() {
        growthSpec = snap(); markAlphaSpec = snap(); heartSpec = snap(); burstSpec = snap(); glowSpec = snap(); arrivedSpec = snap()
        growth = 1.0; markAlpha = 1.0; heart = 1.0; burst = 1.0; glow = 0.0
        arrived = true; handedOver = true; phase = PaywallOpeningPhase.settled
    }

    private fun enter(next: PaywallOpeningPhase) {
        phase = next
        entered.add(next)
        when (next) {
            PaywallOpeningPhase.still -> Unit
            PaywallOpeningPhase.bloom -> {
                if (playsHaptics) PaywallOpeningHaptics.swell(duration = PaywallOpeningTiming.swell)
                growthSpec = PaywallOpeningTiming.bloomCurve(); glowSpec = PaywallOpeningTiming.bloomCurve()
                growth = 1.0; glow = 1.0
                markAlphaSpec = tween(220, easing = FastOutSlowInEasing)
                markAlpha = 1.0
            }
            PaywallOpeningPhase.badge -> {
                // Scale and opacity together on a lively spring: the heart pops on, it doesn't fade up.
                heartSpec = springResponse(response = 0.36f, dampingFraction = 0.56f)
                heart = 1.0
                burstSpec = tween((PaywallOpeningTiming.rays * 1000).roundToInt(), easing = LinearEasing)
                burst = 1.0
            }
            PaywallOpeningPhase.travel -> {
                val t = tween<Float>((PaywallOpeningTiming.travel * 1000).roundToInt(), easing = FastOutSlowInEasing)
                arrivedSpec = t; glowSpec = t
                arrived = true; glow = 0.0
            }
            // The flying copy and the header's logo are identical at this instant, so the swap is invisible;
            // from here the logo scrolls with the content, which a positioned overlay can't.
            PaywallOpeningPhase.settled -> handedOver = true
        }
    }

    private companion object {
        /** SwiftUI `.spring(response:dampingFraction:)`: stiffness (2π / response)². */
        fun springResponse(response: Float, dampingFraction: Float): AnimationSpec<Float> {
            val omega = (2 * PI / response).toFloat()
            return spring(dampingRatio = dampingFraction, stiffness = omega * omega)
        }
    }
}

// MARK: - Views

/**
 * The flying lockup, its glow and its rays, drawn over the (invisible, fully laid out) paywall. [slot] is the
 * header logo's mark frame in this view's coordinate space (px); the lockup is drawn once at `markWidth` and moved
 * only by position + one scale, so nothing inside it drifts on the way there.
 */
@Composable
fun PaywallOpening(director: PaywallOpeningDirector, slot: Rect?, modifier: Modifier = Modifier) {
    val growth by animateFloatAsState(director.growth.toFloat(), director.growthSpec, label = "growth")
    val markAlpha by animateFloatAsState(director.markAlpha.toFloat(), director.markAlphaSpec, label = "markAlpha")
    val heart by animateFloatAsState(director.heart.toFloat(), director.heartSpec, label = "heart")
    val burst by animateFloatAsState(director.burst.toFloat(), director.burstSpec, label = "burst")
    val glow by animateFloatAsState(director.glow.toFloat(), director.glowSpec, label = "glow")
    val arrived by animateFloatAsState(if (director.arrived && slot != null) 1f else 0f, director.arrivedSpec, label = "arrived")
    val density = LocalDensity.current.density
    val markW = PaywallOpeningTiming.markWidth
    val markH = markW * PleadLogo.markAspect(PleadLogo.Variant.primary)

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (director.handedOver) 0f else 1f }
            .clearAndSetSemantics { },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val home = Offset(w / 2, h * PaywallOpeningTiming.markHeight)
        val target = slot?.center ?: home
        val centre = home + (target - home) * arrived
        val slotScale = slot?.let { it.width / (markW * density) } ?: growth
        val scale = growth + (slotScale - growth) * arrived
        val glowRadius = PaywallOpeningTiming.markWidth * PaywallOpeningTiming.glowRadius
        val rayReach = PaywallOpeningTiming.markWidth * 0.78f

        PaywallOpeningGlow(
            radius = glowRadius.dp,
            modifier = Modifier
                .centredAt(centre, glowRadius * 2 * density, glowRadius * 2 * density)
                .graphicsLayer {
                    // SwiftUI: `scale * (landed ? 1 : 0.6 + 0.4 * glow)`, interpolated with the travel.
                    val unlanded = 0.6f + 0.4f * glow
                    val s = scale * (unlanded + (1f - unlanded) * arrived)
                    scaleX = s; scaleY = s
                    alpha = (glow * (1f - arrived)).coerceIn(0f, 1f)
                },
        )
        PaywallOpeningRays(
            burst = burst.toDouble(),
            reach = rayReach.dp,
            modifier = Modifier.centredAt(home, rayReach * 2 * density, rayReach * 2 * density),
        )
        Box(
            Modifier
                .centredAt(centre, markW * density, markH * density)
                .graphicsLayer {
                    scaleX = scale; scaleY = scale
                    alpha = markAlpha.coerceIn(0f, 1f)
                },
        ) {
            PleadLogo(width = markW.dp, heartScale = heart.toDouble())
        }
    }
}

/** Places a child of size (w, h) px with its centre at [centre] (SwiftUI `.position`). */
private fun Modifier.centredAt(centre: Offset, w: Float, h: Float): Modifier = this
    .offset { IntOffset((centre.x - w / 2).roundToInt(), (centre.y - h / 2).roundToInt()) }

/** Soft Romance Blush light (#EAA0A4 at 12% at its core), two stops so the eye never finds an edge. */
@Composable
fun PaywallOpeningGlow(radius: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .requiredSize(radius * 2)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        0f to PaywallPalette.romanceBlush.copy(alpha = PaywallOpeningTiming.glowOpacity),
                        0.45f to PaywallPalette.romanceBlush.copy(alpha = PaywallOpeningTiming.glowOpacity * 0.55f),
                        1f to Color.Transparent,
                        center = center,
                        radius = size.minDimension / 2,
                    ),
                )
            },
    )
}

/**
 * Twelve thin Court Gold wedges behind the mark, going out once (0.6 → 1.6) and dissipating.
 *
 * Wedges filled with a gradient that thins outwards and softened with a little blur, so they read as light
 * thrown off the mark rather than as twelve drawn lines.
 */
@Composable
fun PaywallOpeningRays(burst: Double, reach: Dp, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .requiredSize(reach * 2)
            .graphicsLayer {
                val s = PaywallOpeningRays.scale(burst)
                scaleX = s; scaleY = s
                // Never quite 0 before the burst, so the blurred layer is built during the stillness, not on the
                // heart's first frame.
                alpha = if (burst < 1) {
                    maxOf(PaywallOpeningRays.prewarmOpacity, PaywallOpeningRays.opacity * PaywallOpeningRays.fade(burst)).toFloat()
                } else {
                    0f
                }
            }
            .blur(2.5.dp),
    ) {
        val c = center
        val r = size.minDimension / 2
        val inner = r * 0.22f
        val half = PaywallOpeningRays.halfAngle * PI / 180
        for (i in 0 until PaywallOpeningRays.count) {
            val mid = i.toDouble() / PaywallOpeningRays.count * 2 * PI - PI / 2
            val a0 = mid - half
            val a1 = mid + half
            val wedge = Path().apply {
                moveTo(c.x + inner * cos(a0).toFloat(), c.y + inner * sin(a0).toFloat())
                lineTo(c.x + r * cos(a0).toFloat(), c.y + r * sin(a0).toFloat())
                lineTo(c.x + r * cos(a1).toFloat(), c.y + r * sin(a1).toFloat())
                lineTo(c.x + inner * cos(a1).toFloat(), c.y + inner * sin(a1).toFloat())
                close()
            }
            val innerStop = inner / r
            drawPath(
                wedge,
                Brush.radialGradient(
                    innerStop to PaywallPalette.courtGold.copy(alpha = 0f),
                    (innerStop + (1 - innerStop) * 0.35f) to PaywallPalette.courtGold,
                    1f to PaywallPalette.courtGold.copy(alpha = 0f),
                    center = c,
                    radius = r,
                ),
            )
        }
    }
}

/** Statics of [PaywallOpeningRays] (Swift `PaywallOpeningRays.scale/fade`). */
object PaywallOpeningRays {
    const val count = 12
    const val opacity = 0.30
    /** Half the angular width of each wedge, in degrees. */
    const val halfAngle = 5.5
    const val prewarmOpacity = 0.002

    /** 0.6 → 1.6, leaving fast and coasting to a stop. */
    fun scale(burst: Double): Float {
        val p = burst.coerceIn(0.0, 1.0)
        return (0.6 + 1.0 * (1 - (1 - p).pow(2.2))).toFloat()
    }

    /** In fast (a fifth — the heart lands on it), out slow (the rest reads as dissipation). */
    fun fade(burst: Double): Double {
        val p = burst.coerceIn(0.0, 1.0)
        return if (p < 0.2) p / 0.2 else 1 - (p - 0.2) / 0.8
    }
}
