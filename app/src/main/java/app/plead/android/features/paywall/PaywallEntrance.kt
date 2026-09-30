// Port of ArgueWin/Features/Paywall/PaywallEntrance.swift.
//
// Paywall entrance (CONTRACTS-v2 amendment s). When the bloom opening (`PaywallOpening`) does NOT play — a re-show,
// the exit offer, the partner-paid state — the screen assembles in layers with the onboarding grammar
// (`OnboardingMotionTokens` / `PleadRevealParameters`), like a court coming into session:
//
//   layer    effect                              duration  delay
//   hero     opacity · y 5→0 · scale 0.98→1      0.36 s    0.00 s   (amendment v: fade + 4–6 pt rise)
//   brand    opacity · y 10→0                    0.32 s    0.10 s
//   sub      opacity · y 8→0                     0.28 s    0.14 s
//   tile i   opacity · y 8→0                     0.28 s    0.24 s + 0.10 s × i
//   plan i   opacity · y 12→0 · scale 0.98→1     0.36 s    0.40 s + 0.12 s × i   (tiny spring)
//   cta      opacity · y 8→0 (CTA, disclosure, couple line)  0.28 s  0.60 s
//   footer   opacity · y 8→0                     0.28 s    0.76 s (last)
//
// One-shot per appearance; never blocks taps (layers start at opacity 0.001, so they hit-test and stay in the
// accessibility tree from the first frame, and nothing is disabled while it runs); Reduce Motion keeps the timings
// but drops every offset, scale and spring (opacity only). When the bloom plays, the settled paywall fades in under
// the travelling lockup as before and these layers are placed at rest without animating: never both.
//
// Android: `OnboardingMotionTokens` and `PleadRevealParameters` are the onboarding definitions
// (features/onboarding/OnboardingMotion.kt), as in Swift.
package app.plead.android.features.paywall

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.features.onboarding.OnboardingMotionTokens
import app.plead.android.features.onboarding.PleadRevealParameters
import kotlinx.coroutines.delay

enum class PaywallEntranceLayer { hero, brand, sub, tile, plan, cta, footer }

object PaywallEntranceTokens {
    private val O = OnboardingMotionTokens

    const val heroDuration = 0.36
    val heroStartScale: Float = O.cardStartScale            // 0.98

    /** Amendment v (paywall courtroom hero): the hero fades in with a small 4–6 pt rise, no large zoom. */
    const val heroRise = 5f
    const val brandDelay = 0.10
    val brandDuration = O.headlineDuration                  // 0.32
    val subDelay = O.bodyDelay                              // 0.14
    val subDuration = O.bodyDuration                        // 0.28
    val tileDelay = O.cardDelay                             // 0.24
    const val tileStagger = 0.10
    val tileDuration = O.bodyDuration                       // 0.28
    const val planDelay = 0.40
    val planStagger = O.cardStagger                         // 0.12
    val planDuration = O.cardDuration.start                 // 0.36
    val ctaDelay = O.ctaDelay                               // 0.60
    val ctaDuration = O.ctaDuration                         // 0.28

    /** Last: after the final plan card (0.40 + 2 × 0.12 = 0.64) has started. */
    const val footerDelay = 0.76
    val footerDuration = O.ctaDuration

    /** Rises stay inside the brief's 8–12 pt. */
    val brandRise = O.headlineRise                          // 10
    val smallRise = O.bodyRise                              // 8
    val planRise = O.cardRise                               // 12

    /** Standard → exit offer: the cross-dissolve (the hero is held in place). */
    const val exitCrossfade = 0.3

    /** The EXIT OFFER stamp's landing spring. */
    val stampSpring: List<Float> = O.avatarSpring           // 0.97 → 1.03 → 1.0
}

object PaywallEntrance {
    /** The resolved entrance of one layer (pure; unit-tested). */
    fun parameters(layer: PaywallEntranceLayer, index: Int = 0, reduceMotion: Boolean): PleadRevealParameters {
        val t = PaywallEntranceTokens
        val i = maxOf(index, 0).toDouble()
        val p = when (layer) {
            PaywallEntranceLayer.hero -> PleadRevealParameters(
                delay = 0.0, duration = t.heroDuration, offset = Offset(0f, t.heroRise), startScale = t.heroStartScale, springy = false,
            )
            PaywallEntranceLayer.brand -> PleadRevealParameters(
                delay = t.brandDelay, duration = t.brandDuration, offset = Offset(0f, t.brandRise), startScale = 1f, springy = false,
            )
            PaywallEntranceLayer.sub -> PleadRevealParameters(
                delay = t.subDelay, duration = t.subDuration, offset = Offset(0f, t.smallRise), startScale = 1f, springy = false,
            )
            PaywallEntranceLayer.tile -> PleadRevealParameters(
                delay = t.tileDelay + t.tileStagger * i, duration = t.tileDuration,
                offset = Offset(0f, t.smallRise), startScale = 1f, springy = false,
            )
            PaywallEntranceLayer.plan -> PleadRevealParameters(
                delay = t.planDelay + t.planStagger * i, duration = t.planDuration,
                offset = Offset(0f, t.planRise), startScale = t.heroStartScale, springy = true,
            )
            PaywallEntranceLayer.cta -> PleadRevealParameters(
                delay = t.ctaDelay, duration = t.ctaDuration, offset = Offset(0f, t.smallRise), startScale = 1f, springy = false,
            )
            PaywallEntranceLayer.footer -> PleadRevealParameters(
                delay = t.footerDelay, duration = t.footerDuration, offset = Offset(0f, t.smallRise), startScale = 1f, springy = false,
            )
        }
        if (reduceMotion) {
            p.offset = Offset.Zero
            p.startScale = 1f
            p.springy = false
        }
        return p
    }

    /** When the whole entrance has landed (the last layer's end), for [plans] plan cards. */
    fun total(plans: Int = 3, reduceMotion: Boolean = false): Double {
        val lastPlan = parameters(PaywallEntranceLayer.plan, index = maxOf(plans - 1, 0), reduceMotion = reduceMotion).end
        return maxOf(lastPlan, parameters(PaywallEntranceLayer.footer, reduceMotion = reduceMotion).end)
    }

    /** Whether the settled screen assembles in layers: only when the bloom opening doesn't play. */
    fun plays(opening: PaywallOpeningMode): Boolean = opening == PaywallOpeningMode.none
}

/** What the layers below read from the composition: whether they are at rest, and whether reaching rest animates. */
data class PaywallEntranceState(
    /** Layers at rest (full opacity, no offset). */
    val shown: Boolean,
    /** Reaching rest uses each layer's delayed animation (false: placed at rest in one frame). */
    val animated: Boolean,
) {
    companion object {
        /** Previews, anything outside a paywall: at rest. */
        val settled = PaywallEntranceState(shown = true, animated = false)

        /** Before the entrance begins. */
        val pending = PaywallEntranceState(shown = false, animated = false)
    }
}

/** SwiftUI `@Entry var paywallEntrance`. */
val LocalPaywallEntrance = staticCompositionLocalOf { PaywallEntranceState.settled }

/** One entrance layer of the paywall / exit offer (driven by [LocalPaywallEntrance]). */
fun Modifier.paywallEntrance(layer: PaywallEntranceLayer, index: Int = 0): Modifier = composed {
    val entrance = LocalPaywallEntrance.current
    val reduceMotion = accessibilityReduceMotion()
    val p = remember(layer, index, reduceMotion) { PaywallEntrance.parameters(layer, index = index, reduceMotion = reduceMotion) }
    val progress = remember { Animatable(if (entrance.shown) 1f else 0f) }
    LaunchedEffect(entrance) {
        if (entrance.shown && entrance.animated) {
            delay((p.delay * 1000).toLong())
            progress.animateTo(1f, p.animationSpec())
        } else {
            progress.snapTo(if (entrance.shown) 1f else 0f)
        }
    }
    val density = LocalDensity.current.density
    graphicsLayer {
        val v = progress.value
        val start = p.startOpacity.toFloat()
        alpha = (start + (1f - start) * v).coerceIn(0f, 1f)
        val s = p.startScale + (1f - p.startScale) * v
        scaleX = s
        scaleY = s
        translationX = p.offset.x * density * (1f - v)
        translationY = p.offset.y * density * (1f - v)
    }
}

/** Drives [LocalPaywallEntrance] for a screen: `begin(animated)` once per appearance. */
class PaywallEntranceDirector(initial: PaywallEntranceState = PaywallEntranceState.pending) {
    var state: PaywallEntranceState by mutableStateOf(initial)
        private set
    var runs: Int by mutableIntStateOf(0)
        private set

    /** Assemble in layers ([animated]) or jump straight to rest (the bloom is playing / has played). */
    fun begin(animated: Boolean) {
        runs += 1
        state = if (animated) PaywallEntranceState(shown = true, animated = true) else PaywallEntranceState(shown = true, animated = false)
    }

    /** Replay from the start (e.g. the content swapped to the partner-paid state while the paywall was up). */
    suspend fun replay() {
        state = PaywallEntranceState.pending
        delay(20)   // one committed frame at rest-before
        state = PaywallEntranceState(shown = true, animated = true)
    }
}
