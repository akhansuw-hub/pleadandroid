// Port of ArgueWin/Features/Paywall/PaywallCourtroomHero.swift.
package app.plead.android.features.paywall

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.accessibilityReduceMotion

/**
 * The paywall hero as a miniature living courtroom (CONTRACTS-v2 amendment v): the static courtroom plus three
 * sprite layers (Judge Wigsworth, the plaintiff, the defendant) driven by [PaywallCourtroomAnimator].
 *
 * Same box, placement, fade and accessibility as the old single-image `PaywallHero`: [height] tall, full width,
 * the art aspect-filled and bottom-aligned, dissolving into the cream below. The sprites are positioned in the
 * art's own pixel space with one scale factor, so they sit exactly on the pixels they replace at any hero size.
 * Only frames swap: nothing here changes size, so the pricing below never moves.
 *
 * [gavelTrigger]: any change is a plan selection: one quick gavel tap (debounced by the animator). null: never.
 */
@Composable
fun PaywallCourtroomHero(height: Dp, modifier: Modifier = Modifier, gavelTrigger: String? = null) {
    val scope = rememberCoroutineScope()
    val animator = remember { PaywallCourtroomAnimator(scope) }
    val systemReduceMotion = accessibilityReduceMotion()
    val reduceMotion = PaywallCourtroomHero.reduceMotion(systemReduceMotion)
    val view = LocalView.current

    SideEffect {
        animator.onFirstStrike = {
            // One very light tap the first time the gavel lands in this presentation; never again.
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    // scenePhase `.active` ↔ RESUMED: start on resume (and appear), stop on pause (and disappear).
    val currentReduceMotion by rememberUpdatedState(reduceMotion)
    LifecycleResumeEffect(animator) {
        animator.reduceMotion = currentReduceMotion
        animator.start()
        onPauseOrDispose { animator.stop() }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(reduceMotion) {
        animator.reduceMotion = reduceMotion
        if (!reduceMotion && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) animator.start()
    }
    val firstTrigger = remember { gavelTrigger }
    LaunchedEffect(gavelTrigger) {
        if (gavelTrigger != firstTrigger) animator.gavelTap()
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clipToBounds()
            .paywallHeroFade()
            .clearAndSetSemantics {
                contentDescription = "A pixel-art courtroom: the judge at the bench, a couple at the two stands, spectators in the gallery."
                role = Role.Image
            },
    ) {
        PaywallCourtroomScene(pose = animator.pose, modifier = Modifier.fillMaxSize())
    }
}

/** The bottom dissolves into the cream: black to 80%, 55% at 90%, clear at the edge (SwiftUI `.mask`). */
internal fun Modifier.paywallHeroFade(): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black,
                0.80f to Color.Black,
                0.90f to Color.Black.copy(alpha = 0.55f),
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

object PaywallCourtroomHero {
    /** The system setting, or (DEBUG demo captures only) `AWDemoReduceMotion YES`. */
    fun reduceMotion(system: Boolean): Boolean = system || DemoHarness.demoReduceMotion
}

/**
 * The layered art for one pose, drawn nearest-neighbour in the art's own pixel space (one scale transform), so a
 * frame lands exactly on the background pixels it replaces. Every frame is decoded once up front (`remember`), so
 * nothing decodes on the first swap.
 */
@Composable
fun PaywallCourtroomScene(pose: CourtroomPose, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val background = remember(resources) { ImageBitmap.imageResource(resources, PaywallCourtroomSprites.backgroundDrawable) }
    val frames: Map<Int, ImageBitmap> = remember(resources) {
        PaywallCourtroomSprites.Character.entries.flatMap { it.frameDrawables }.associateWith { ImageBitmap.imageResource(resources, it) }
    }
    // The judge stays clear of the status bar when the hero is squeezed on a short screen (`PaywallFit`).
    val density = LocalDensity.current
    val keepClearTop = WindowInsets.statusBars.getTop(density) + with(density) { PaywallFit.judgeClearance.dp.toPx() }
    Canvas(modifier) {
        val placement = PaywallCourtroomPlacement(hero = Size(size.width, size.height), keepClearTop = keepClearTop)
        val src = PaywallCourtroomSprites.sourcePixels
        withTransform({
            translate(placement.art.left, placement.art.top)
            scale(placement.pointsPerPixel, placement.pointsPerPixel, pivot = Offset.Zero)
        }) {
            pixelImage(background, 0, 0, src.width.toInt(), src.height.toInt())
            layer(PaywallCourtroomSprites.Character.judge, frames.getValue(pose.judge.drawable))
            layer(PaywallCourtroomSprites.Character.plaintiff, frames.getValue(pose.plaintiff.drawable(PaywallCourtroomSprites.Character.plaintiff)))
            layer(PaywallCourtroomSprites.Character.defendant, frames.getValue(pose.defendant.drawable(PaywallCourtroomSprites.Character.defendant)))
        }
    }
}

private fun DrawScope.layer(who: PaywallCourtroomSprites.Character, frame: ImageBitmap) {
    val r = who.rect
    pixelImage(frame, r.left.toInt(), r.top.toInt(), r.width.toInt(), r.height.toInt())
}

/** `.resizable().interpolation(.none)`: nearest-neighbour. */
private fun DrawScope.pixelImage(image: ImageBitmap, x: Int, y: Int, w: Int, h: Int) {
    drawImage(
        image,
        dstOffset = IntOffset(x, y),
        dstSize = IntSize(w, h),
        filterQuality = FilterQuality.None,
    )
}
