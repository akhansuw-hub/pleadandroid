// Port of ArgueWin/Features/Onboarding/OnboardingMotion.swift: the onboarding motion system
// (docs/onboarding-motion-brief/BRIEF.md §2, §5; CONTRACTS-v2 amendment r).
// "Every screen should assemble in layers, like a court coming into session."
//
// One grammar for screens 2–10. Every timing lives in `OnboardingMotionTokens`, so the whole flow is tuned here.
//
//   layer      effect                       duration     delay                   curve
//   headline   opacity 0→1 · y 10→0          0.32 s       0.00 s                  ease out
//   body       opacity 0→1 · y 8→0           0.28 s       0.14 s                  softer ease out
//   card/art   scale 0.98→1 · y 12→0         0.36–0.44 s  0.24 s + 0.12 s × index tiny spring (bounce 0.12)
//     parts    opacity · y 4→0 (number → title → line)  0.24 s  card + 0.06 s × part
//   cta        opacity 0→1 · y 8→0           0.28 s       0.60 s                  ease out
//   page       out: 12 pt left + fade · in: from 16 pt right       0.34 s (back mirrored)
//
// Rules: one-shot per screen entry (never loops); never blocks input (reveals start at opacity 0.001 so
// hit-testing and TalkBack work from the first frame; nothing is disabled while animating); Reduce Motion keeps
// the same timings but drops every offset, scale and spring (opacity only).
//
// Compose mapping: SwiftUI view modifiers → `Modifier` extensions (`pleadReveal`, `pleadPress`, …); the
// `pleadRevealID` environment value → `LocalPleadRevealID`; `Group(subviews:)` stacks → `LayerListScope`
// (`item {}` / `items(list) {}` register one child each, like a LazyColumn DSL). Points are dp, seconds Double.
package app.plead.android.features.onboarding

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.fixedSp
import app.plead.android.designsystem.pleadShadow
import app.plead.android.designsystem.swiftSpring
import kotlinx.coroutines.delay

// MARK: - Tokens

object OnboardingMotionTokens {
    // Entrance cadence (brief §2 table, amendment r).
    const val headlineDelay: Double = 0.0
    const val headlineDuration: Double = 0.32
    const val bodyDelay: Double = 0.14
    const val bodyDuration: Double = 0.28
    val cardDuration: ClosedFloatingPointRange<Double> = 0.36..0.44
    const val cardDelay: Double = 0.24
    const val cardStagger: Double = 0.12
    const val ctaDelay: Double = 0.60
    const val ctaDuration: Double = 0.28
    const val screenTransition: Double = 0.34
    const val pressScale: Float = 0.97f

    // Distances.
    const val headlineRise: Float = 10f
    const val bodyRise: Float = 8f
    const val cardRise: Float = 12f
    const val cardStartScale: Float = 0.98f
    const val ctaRise: Float = 8f
    /** "A very small spring" for cards. */
    const val cardBounce: Double = 0.12

    // Parts inside a card (screen 2: gold number → title → line).
    const val partStagger: Double = 0.06
    const val partDuration: Double = 0.24
    const val partRise: Float = 4f

    /** Reveals start here rather than at 0 so the view hit-tests and stays in the accessibility tree. */
    const val revealStartOpacity: Double = 0.001

    // Page transition (brief §2: 10–14 pt out, 14–18 pt in, 0.30–0.38 s).
    const val outgoingShift: Float = 12f
    const val incomingShift: Float = 16f
    /** The outgoing page fades faster than it slides, so two pages never read on top of each other. */
    const val outgoingFade: Double = 0.16
    const val reduceMotionCrossfade: Double = 0.25

    // Screen moments (brief §3).
    const val judgeStartScale: Float = 0.96f
    const val highlightPulse: Double = 0.4
    const val docketShift: Float = 12f
    const val stampDuration: Double = 0.18
    const val stampStartScale: Float = 1.35f
    val avatarSpring: List<Float> = listOf(0.97f, 1.03f, 1.0f)
    const val previewCrossfade: Double = 0.2
    const val versusShift: Float = 40f
    const val versusDuration: Double = 0.36
    const val notificationDrop: Float = 14f
    const val railFill: Double = screenTransition

    /**
     * Court Is Ready (amendment ak): the courtroom assembles (room → judge → both parties), the judge raises the
     * gavel and strikes it (the one haptic), and "Court is now in session" lands with the strike; then the
     * supporting line and the CTAs. Reduce Motion: every layer fades on the same clock, no swing.
     */
    object Ready {
        const val roomDuration: Double = 0.40
        const val judgeEnter: Double = cardStagger
        const val userEnter: Double = cardDelay
        const val partnerEnter: Double = cardDelay + cardStagger * 1.5
        val enterDuration: Double = cardDuration.start
        const val enterShift: Float = 28f
        /** Figures rise onto their podiums (amendment ak: 6–12 pt). */
        const val figureRise: Float = 10f
        val settled: Double get() = partnerEnter + enterDuration
        /** The gavel goes up once the room has settled… */
        val gavelRaise: Double get() = settled + cardStagger
        const val gavelRaiseDuration: Double = 0.22
        const val gavelStrikeDuration: Double = 0.10
        const val gavelHold: Double = 0.18
        /** …and comes down with the session line (and the haptic). */
        val gavel: Double get() = gavelRaise + gavelRaiseDuration
        val session: Double get() = gavel
        val subtitle: Double get() = session + bodyDelay
        val cta: Double get() = session + headlineDuration
    }

    /** End of the card-part reveal of card `index` (screen 2 budget: ≤ 0.85 s). */
    fun cardPartsEnd(index: Int, parts: Int): Double =
        PleadRevealParameters.make(PleadRevealKind.card, index = index, part = parts - 1, reduceMotion = false).end
}

/** SwiftUI `.timingCurve(0.22, 1, 0.36, 1)`: the flow's soft ease out. */
val OnboardingEaseOut = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/** Seconds → animation milliseconds. */
internal fun Double.millis(): Int = (this * 1000).toInt().coerceAtLeast(0)

// MARK: - Reveal

enum class PleadRevealKind { headline, body, card, cta }

/** The resolved entrance of one layer. Pure, so the cadence is unit-tested. `offset` is in points (dp). */
data class PleadRevealParameters(
    var delay: Double,
    var duration: Double,
    var offset: Offset,
    var startScale: Float,
    var startOpacity: Double = OnboardingMotionTokens.revealStartOpacity,
    var springy: Boolean,
) {
    val end: Double get() = delay + duration
    val isOpacityOnly: Boolean get() = offset == Offset.Zero && startScale == 1f && !springy

    /** The curve (the delay is applied separately: Compose springs take no delay). */
    fun animationSpec(bounce: Double = OnboardingMotionTokens.cardBounce): AnimationSpec<Float> =
        if (springy) swiftSpring(duration.toFloat(), bounce.toFloat())
        else tween(duration.millis(), easing = OnboardingEaseOut)

    companion object {
        /**
         * @param index stagger position (each step adds `cardStagger`).
         * @param part a part inside a card (number → title → line); follows the card with `partStagger`.
         * @param offset / scale / spring / duration: per-screen overrides (docket ±12 pt, judge 0.96, calm screens).
         * @param extraDelay added after the kind's delay.
         */
        fun make(
            kind: PleadRevealKind,
            index: Int = 0,
            part: Int? = null,
            reduceMotion: Boolean,
            offset: Offset? = null,
            scale: Float? = null,
            spring: Boolean? = null,
            duration: Double? = null,
            extraDelay: Double = 0.0,
        ): PleadRevealParameters {
            val t = OnboardingMotionTokens
            val stagger = t.cardStagger * maxOf(index, 0)
            val p = when (kind) {
                PleadRevealKind.headline -> PleadRevealParameters(
                    delay = t.headlineDelay + stagger, duration = t.headlineDuration,
                    offset = Offset(0f, t.headlineRise), startScale = 1f, springy = false,
                )
                PleadRevealKind.body -> PleadRevealParameters(
                    delay = t.bodyDelay + stagger, duration = t.bodyDuration,
                    offset = Offset(0f, t.bodyRise), startScale = 1f, springy = false,
                )
                PleadRevealKind.card -> PleadRevealParameters(
                    delay = t.cardDelay + stagger, duration = t.cardDuration.start,
                    offset = Offset(0f, t.cardRise), startScale = t.cardStartScale, springy = true,
                )
                PleadRevealKind.cta -> PleadRevealParameters(
                    delay = t.ctaDelay + stagger, duration = t.ctaDuration,
                    offset = Offset(0f, t.ctaRise), startScale = 1f, springy = false,
                )
            }
            if (part != null) {
                p.delay += t.partStagger * maxOf(part, 0)
                p.duration = t.partDuration
                p.offset = Offset(0f, t.partRise)
                p.startScale = 1f
                p.springy = false
            }
            if (offset != null) p.offset = offset
            if (scale != null) p.startScale = scale
            if (spring != null) p.springy = spring
            if (duration != null) p.duration = duration
            p.delay += extraDelay
            if (reduceMotion) {
                p.offset = Offset.Zero
                p.startScale = 1f
                p.springy = false
            }
            return p
        }
    }
}

/**
 * Set by `OnboardingContainer` per screen entry. A reveal fires once per value (one-shot: a re-shown view never
 * replays it). `null` outside the onboarding container.
 */
val LocalPleadRevealID = compositionLocalOf<Int?> { null }

/**
 * Applies a one-shot entrance with [parameters]: opacity from `startOpacity`, scale from `startScale`, offset from
 * `offset` (dp), animated to rest after `delay`. Fires once per `LocalPleadRevealID` value.
 */
internal fun Modifier.revealLayer(parameters: @Composable () -> PleadRevealParameters, bounce: Double = OnboardingMotionTokens.cardBounce): Modifier = composed {
    val p = parameters()
    val revealID = LocalPleadRevealID.current
    val progress = remember { Animatable(0f) }
    var firedFor by remember { mutableStateOf<Any?>(Unfired) }
    LaunchedEffect(revealID) {
        if (firedFor == revealID) return@LaunchedEffect
        firedFor = revealID
        if (p.delay > 0) delay(p.delay.millis().toLong())
        progress.animateTo(1f, p.animationSpec(bounce))
    }
    val density = LocalDensity.current
    val dx = with(density) { p.offset.x.dp.toPx() }
    val dy = with(density) { p.offset.y.dp.toPx() }
    graphicsLayer {
        val v = progress.value
        val start = p.startOpacity.toFloat()
        alpha = (start + (1f - start) * v).coerceIn(0f, 1f)
        val s = p.startScale + (1f - p.startScale) * v
        scaleX = s
        scaleY = s
        translationX = dx * (1f - v)
        translationY = dy * (1f - v)
    }
}

private object Unfired

/** A one-shot entrance layer: headline / body / card / cta (Swift `.pleadReveal(_:index:part:…)`). */
fun Modifier.pleadReveal(
    kind: PleadRevealKind,
    index: Int = 0,
    part: Int? = null,
    offset: Offset? = null,
    scale: Float? = null,
    spring: Boolean? = null,
    duration: Double? = null,
    delay: Double = 0.0,
): Modifier = revealLayer({
    PleadRevealParameters.make(
        kind, index = index, part = part, reduceMotion = accessibilityReduceMotion(), offset = offset, scale = scale,
        spring = spring, duration = duration, extraDelay = delay,
    )
})

/**
 * The children of a layered stack, one per `item` / element of `items` (SwiftUI `Group(subviews:)`: a `ForEach`
 * counts one per element). Built fresh on every composition, like a LazyColumn DSL.
 */
class LayerListScope {
    internal val children = mutableListOf<@Composable () -> Unit>()

    fun item(content: @Composable () -> Unit) {
        children += content
    }

    fun <T> items(list: List<T>, content: @Composable (index: Int, item: T) -> Unit) {
        list.forEachIndexed { i, element -> children += { content(i, element) } }
    }

    companion object {
        fun build(block: LayerListScope.() -> Unit): List<@Composable () -> Unit> = LayerListScope().apply(block).children
    }
}

/** A column whose children enter as cards, one `cardStagger` apart (starting at [firstIndex]). */
@Composable
fun PleadStaggeredStack(
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    spacing: Dp? = null,
    firstIndex: Int = 0,
    /** Calm screens (Tracking) pass `false`: no spring. */
    spring: Boolean = true,
    /** Per-child offset override (the docket's alternating ±12 pt). */
    offset: ((Int) -> Offset)? = null,
    content: LayerListScope.() -> Unit,
) {
    val children = LayerListScope.build(content)
    Column(
        modifier,
        horizontalAlignment = alignment,
        verticalArrangement = if (spacing != null) Arrangement.spacedBy(spacing) else Arrangement.spacedBy(8.dp),
    ) {
        children.forEachIndexed { i, child ->
            Box(Modifier.pleadReveal(PleadRevealKind.card, index = firstIndex + i, offset = offset?.invoke(i), spring = spring)) {
                child()
            }
        }
    }
}

// MARK: - One-shot accents

/** Screen 3: one subtle 0.4 s highlight pulse (gold edge + glow) at `delay`; never loops. */
fun Modifier.pleadHighlightPulse(
    delay: Double,
    cornerRadius: Dp = OnboardingRadius.card,
    color: Color = OnboardingPalette.gold,
): Modifier = composed {
    val glow = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        val half = (OnboardingMotionTokens.highlightPulse / 2).millis()
        if (delay > 0) delay(delay.millis().toLong())
        glow.animateTo(1f, tween(half, easing = FastOutSlowInEasing))
        glow.animateTo(0f, tween(half, easing = FastOutSlowInEasing))
    }
    val shape = RoundedCornerShape(cornerRadius)
    this
        .pleadShadow(color.copy(alpha = 0.35f * glow.value), radius = (10 * glow.value).dp, shape = shape)
        .drawWithContent {
            drawContent()
            val g = glow.value
            if (g > 0f) {
                val stroke = 2.dp.toPx()
                val r = cornerRadius.toPx()
                drawRoundRect(
                    color = color.copy(alpha = g),
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(r - stroke / 2, r - stroke / 2),
                    style = Stroke(stroke),
                )
            }
        }
}

/** Screen 5: the 0.97 → 1.03 → 1.0 micro-spring each time `trigger` changes (identity under Reduce Motion). */
fun Modifier.pleadMicroSpring(trigger: Any?, active: Boolean = true): Modifier = composed {
    val reduceMotion = accessibilityReduceMotion()
    val enabled = active && !reduceMotion
    val scale = remember { Animatable(1f) }
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(trigger) {
        if (first) {
            first = false
            return@LaunchedEffect
        }
        val s = OnboardingMotionTokens.avatarSpring
        scale.animateTo(s[0], tween(60, easing = FastOutSlowInEasing))
        scale.animateTo(s[1], spring(dampingRatio = 0.85f, stiffness = 1500f))
        scale.animateTo(s[2], spring(dampingRatio = 1f, stiffness = 600f))
    }
    graphicsLayer {
        val v = if (enabled) scale.value else 1f
        scaleX = v
        scaleY = v
    }
}

/** Screen 4: a quick burgundy rubber stamp ("OPEN CASE") that lands once at `delay`, then stays put. */
@Composable
fun PleadStamp(text: String, delay: Double, modifier: Modifier = Modifier) {
    val reduceMotion = accessibilityReduceMotion()
    val landed = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (landed.value == 1f) return@LaunchedEffect
        if (delay > 0) delay(delay.millis().toLong())
        landed.animateTo(1f, tween(OnboardingMotionTokens.stampDuration.millis(), easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)))
    }
    val shape = RoundedCornerShape(4.dp)
    Text(
        text,
        style = androidx.compose.ui.text.TextStyle(fontSize = fixedSp(11f), fontWeight = FontWeight.Black, letterSpacing = 1.4.sp),
        color = OnboardingPalette.burgundy,
        modifier = modifier
            .graphicsLayer {
                val v = landed.value
                val start = OnboardingMotionTokens.revealStartOpacity.toFloat()
                alpha = start + (0.9f - start) * v
                val s = if (reduceMotion) 1f else OnboardingMotionTokens.stampStartScale + (1f - OnboardingMotionTokens.stampStartScale) * v
                scaleX = s
                scaleY = s
            }
            .rotate(-8f)
            .border(1.5.dp, OnboardingPalette.burgundy, shape)
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .clearAndSetSemantics { contentDescription = text.lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } },
    )
}

// MARK: - Press

/**
 * Primary / secondary press state: 0.97 on touch-down, spring back to 1.0 on release.
 * Reduce Motion: no scale (a slight dim instead).
 */
object PleadPressStyle {
    fun scale(isPressed: Boolean, reduceMotion: Boolean): Float =
        if (isPressed && !reduceMotion) OnboardingMotionTokens.pressScale else 1f

    /** `.easeOut(0.1)` down, `.spring(response: 0.32, dampingFraction: 0.58)` back. */
    fun animation(isPressed: Boolean): AnimationSpec<Float> =
        if (isPressed) tween(100, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))
        else spring(dampingRatio = 0.58f, stiffness = springStiffness(0.32f))

    /** SwiftUI `response` → stiffness: (2π / response)². */
    internal fun springStiffness(response: Float): Float {
        val omega = (2 * Math.PI / response).toFloat()
        return omega * omega
    }
}

/** Swift `.pleadPress(_:)`. */
fun Modifier.pleadPress(isPressed: Boolean): Modifier = composed {
    val reduceMotion = accessibilityReduceMotion()
    val scale by animateFloatAsState(
        targetValue = PleadPressStyle.scale(isPressed, reduceMotion),
        animationSpec = if (reduceMotion) tween(100) else PleadPressStyle.animation(isPressed),
        label = "pleadPress",
    )
    val dim by animateFloatAsState(if (reduceMotion && isPressed) 0.85f else 1f, tween(100), label = "pleadPressDim")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
        alpha = dim
    }
}

// MARK: - Page transition

object OnboardingPageTransition {
    data class Offsets(val insertion: Float, val removal: Float)

    /** Forward: the next page comes in from 16 pt right, the old one leaves 12 pt left. Back: mirrored. */
    fun offsets(forward: Boolean): Offsets {
        val t = OnboardingMotionTokens
        return if (forward) Offsets(insertion = t.incomingShift, removal = -t.outgoingShift)
        else Offsets(insertion = -t.incomingShift, removal = t.outgoingShift)
    }

    /** The `AnimatedContent` transform (`density` converts the dp shifts). */
    fun transition(forward: Boolean, reduceMotion: Boolean, density: androidx.compose.ui.unit.Density): ContentTransform {
        val t = OnboardingMotionTokens
        if (reduceMotion) {
            val spec = tween<Float>(t.reduceMotionCrossfade.millis(), easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f))
            return fadeIn(spec) togetherWith fadeOut(spec)
        }
        val o = offsets(forward)
        val slide = tween<androidx.compose.ui.unit.IntOffset>(t.screenTransition.millis(), easing = OnboardingEaseOut)
        val inPx = with(density) { o.insertion.dp.roundToPx() }
        val outPx = with(density) { o.removal.dp.roundToPx() }
        return (slideInHorizontally(slide) { inPx } + fadeIn(tween(t.screenTransition.millis(), easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)))) togetherWith
            (slideOutHorizontally(slide) { outPx } + fadeOut(tween(t.outgoingFade.millis(), easing = CubicBezierEasing(0.42f, 0f, 1f, 1f))))
    }

    fun animation(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) tween(OnboardingMotionTokens.reduceMotionCrossfade.millis(), easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f))
        else tween(OnboardingMotionTokens.screenTransition.millis(), easing = OnboardingEaseOut)
}

/** For `AnimatedContent(transitionSpec = …)` call sites. */
fun <S> AnimatedContentTransitionScope<S>.onboardingPage(forward: Boolean, reduceMotion: Boolean, density: androidx.compose.ui.unit.Density): ContentTransform =
    OnboardingPageTransition.transition(forward, reduceMotion, density)

// MARK: - Progress rail

/**
 * Persistent segmented rail for screens 2–10: done segments Court Burgundy, upcoming parchment, and the
 * active segment gold. Only the active gold segment animates (it fills in over the page transition).
 */
object OnboardingProgressRail {
    /** One segment per active screen after Welcome. */
    fun segmentCount(steps: List<OnboardingStep> = OnboardingStep.activeSteps()): Int = steps.size - 1
    val segmentCount: Int get() = segmentCount()

    /** Welcome has no rail. */
    fun activeIndex(step: OnboardingStep, steps: List<OnboardingStep> = OnboardingStep.activeSteps()): Int? =
        if (step == OnboardingStep.welcome) null else step.position(steps) - 2
}

@Composable
fun OnboardingProgressRail(
    step: OnboardingStep,
    modifier: Modifier = Modifier,
    /** The active steps. */
    steps: List<OnboardingStep> = OnboardingStep.activeSteps(),
    /**
     * Over dark art (the summons explainer's full-bleed courtroom, amendment ai): cream segments instead of
     * burgundy / parchment, so the rail stays legible. The active gold segment is unchanged.
     */
    onDark: Boolean = false,
) {
    val reduceMotion = accessibilityReduceMotion()
    val active = OnboardingProgressRail.activeIndex(step, steps) ?: -1
    Row(
        modifier.height(6.dp).semantics(mergeDescendants = true) {
            contentDescription = "Setup progress"
            stateDescription = "Step ${step.position(steps)} of ${steps.size}"
        },
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(OnboardingProgressRail.segmentCount(steps)) { i ->
            val fill = if (i < active) {
                if (onDark) OnboardingPalette.cream.copy(alpha = 0.92f) else OnboardingPalette.burgundy
            } else {
                if (onDark) OnboardingPalette.cream.copy(alpha = 0.32f) else OnboardingPalette.parchment
            }
            Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).background(fill)) {
                if (i == active) androidx.compose.runtime.key(step) { ActiveRailSegment(reduceMotion) }
            }
        }
    }
}

@Composable
private fun ActiveRailSegment(reduceMotion: Boolean) {
    val filled = remember { Animatable(0f) }
    LaunchedEffect(Unit) { filled.animateTo(1f, tween(OnboardingMotionTokens.railFill.millis(), easing = OnboardingEaseOut)) }
    BoxWithConstraints(Modifier.fillMaxHeight()) {
        val full = maxWidth
        val h = maxHeight
        val w = if (reduceMotion) full else h + (full - h) * filled.value
        Box(
            Modifier.width(w).fillMaxHeight().clip(RoundedCornerShape(50))
                .background(OnboardingPalette.gold.copy(alpha = 0.35f + 0.65f * filled.value)),
        )
    }
}

// MARK: - Haptics

/** Light haptics, only for the brief's meaningful moments. Nothing for reveals, progress or back. */
object OnboardingHaptics {
    enum class Moment { avatarSelected, partnerLinked, notificationsEnabled, gavel, completion }

    /** Every moment is a light impact (the gavel slightly firmer within "light"). */
    fun intensity(moment: Moment): Float = if (moment == Moment.gavel) 1.0f else 0.8f

    /** `UIImpactFeedbackGenerator(style: .light)`: a clock tick; the gavel a context click (slightly firmer). */
    fun constant(moment: Moment): Int =
        if (moment == Moment.gavel) HapticFeedbackConstants.CONTEXT_CLICK else HapticFeedbackConstants.CLOCK_TICK

    fun fire(view: android.view.View, moment: Moment) {
        view.performHapticFeedback(constant(moment))
    }
}

/** Declarative light haptic for `moment` whenever `trigger` changes (not on first composition), like `sensoryFeedback`. */
@Composable
fun OnboardingHaptic(moment: OnboardingHaptics.Moment, trigger: Any?) {
    val view = LocalView.current
    var previous by remember { mutableStateOf<Any?>(Unfired) }
    LaunchedEffect(trigger) {
        if (previous !== Unfired && previous != trigger) OnboardingHaptics.fire(view, moment)
        previous = trigger
    }
}
