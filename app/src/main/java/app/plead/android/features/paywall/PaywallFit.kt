// Android-only: fits the paywall's purchase button above the fold on short screens (real-device fix 2026-10-02).
//
// iOS tunes one layout for its smallest phone (iPhone 16e, 844 pt, a 34 pt home indicator): hero 25% of the screen
// and the CTA lands above the pinned legal footer (amendment z). Android phones run much shorter (360×640 dp), with a
// 48 dp three-button navigation bar and wider OEM system fonts, so the same layout pushed the CTA under the footer.
// This measures the content below the hero and gives back only as much hero as the CTA needs, in three steps:
//   1. the hero shrinks from its iOS height down to the "soft floor" (the judge still clear of the status bar);
//   2. the body tightens (smaller logo, tighter gaps, `compact`): no text gets smaller, nothing is hidden;
//   3. the hero shrinks further, down to a hard floor, keeping the judge in view (`PaywallCourtroomPlacement`).
// Screens where it already fits (every iPhone-sized phone) are untouched. Past the hard floor the page scrolls.
package app.plead.android.features.paywall

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Marks, in the body, where the content that must be on the first view ends. */
object PaywallFold {
    /** The bottom of the purchase button: always above the fold when the hero can make room for it. */
    val cta = HorizontalAlignmentLine(::min)

    /** The bottom of the whole CTA block (button, disclosure, couple line): above the fold when it fits. */
    val full = HorizontalAlignmentLine(::min)
}

/** Reports this element's bottom edge as [line] to the fitted layout above it. */
fun Modifier.paywallFoldMark(line: HorizontalAlignmentLine): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    layout(p.width, p.height, mapOf(line to p.height)) { p.place(0, 0) }
}

/** The pure fitting rule (all values in dp). */
object PaywallFit {
    /** The top of Judge Wigsworth's frame in the courtroom art, in source pixels. */
    const val judgeTopPixel: Float = 470f

    /** Clear space kept between the status bar and the judge's head. */
    const val judgeClearance: Float = 4f

    /** The smallest hero: the judge's head and shoulders at the bench below the status bar. */
    const val hardFloorBelowInset: Float = 44f

    /**
     * The hero height at which, aspect-filled and bottom-aligned across [width], the judge's head sits just below a
     * [topInset]-tall status bar. Above it the hero only loses gallery; below it the art has to move.
     */
    fun softFloor(width: Float, topInset: Float): Float {
        val src = PaywallCourtroomSprites.sourcePixels
        return topInset + width * (src.height - judgeTopPixel) / src.width + judgeClearance
    }

    fun hardFloor(topInset: Float): Float = topInset + hardFloorBelowInset

    data class Result(val hero: Float, val wantsCompact: Boolean)

    /**
     * [standard]: the iOS hero height. [available]: the visible height of the scrolling area (above the pinned footer
     * or the navigation bar). [fullNeed] / [ctaNeed]: the body's height down to the end of the CTA block / the button.
     * [compact]: whether the body is already tightened.
     */
    fun fit(
        standard: Float,
        softFloor: Float,
        hardFloor: Float,
        available: Float,
        fullNeed: Float,
        ctaNeed: Float,
        compact: Boolean,
    ): Result {
        val soft = min(softFloor, standard)
        val hard = min(hardFloor, soft)
        // Fits as on iOS.
        if (standard + fullNeed <= available) return Result(standard, wantsCompact = false)
        // Step 1: give up gallery only.
        val forFull = available - fullNeed
        if (forFull >= soft) return Result(forFull, wantsCompact = false)
        // Step 2 lands first (one frame: the entrance hides it); then step 3: the whole CTA block (button, disclosure,
        // couple line) if the courtroom can stay above its hard floor, else as much courtroom as still leaves the
        // whole button visible.
        if (!compact) return Result((available - ctaNeed).coerceIn(hard, soft), wantsCompact = true)
        if (forFull >= hard) return Result(forFull, wantsCompact = false)
        return Result((available - ctaNeed).coerceIn(hard, soft), wantsCompact = false)
    }
}

/**
 * Hero over body, the hero as tall as [PaywallFit] allows. Goes inside the vertical scroll. [available]: the visible
 * height of the scroll area. [topInset]: the status bar height. [onCompactNeeded]: called (during layout) when the body
 * should switch to its compact form. [heroOverride]: draws the hero at this height instead (the exit offer's
 * cross-dissolve hand-off); the fit is still computed and reported through [onHeroHeight].
 */
@Composable
internal fun PaywallFittedColumn(
    available: Dp,
    standardHero: Dp,
    topInset: Dp,
    compact: Boolean,
    onCompactNeeded: () -> Unit,
    hero: @Composable () -> Unit,
    body: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    heroOverride: Dp? = null,
    onHeroHeight: (Dp) -> Unit = {},
) {
    Layout(contents = listOf(hero, body), modifier = modifier) { (heroMeasurables, bodyMeasurables), constraints ->
        val width = constraints.maxWidth
        val bodyPlaceable = bodyMeasurables.first().measure(
            Constraints(minWidth = 0, maxWidth = width, minHeight = 0, maxHeight = Constraints.Infinity),
        )
        val fullLine = bodyPlaceable[PaywallFold.full]
        val full = if (fullLine == AlignmentLine.Unspecified) bodyPlaceable.height else fullLine
        val ctaLine = bodyPlaceable[PaywallFold.cta]
        val cta = if (ctaLine == AlignmentLine.Unspecified) full else ctaLine
        val result = PaywallFit.fit(
            standard = standardHero.value,
            softFloor = PaywallFit.softFloor(width.toDp().value, topInset.value),
            hardFloor = PaywallFit.hardFloor(topInset.value),
            available = available.value,
            fullNeed = full.toDp().value,
            ctaNeed = cta.toDp().value,
            compact = compact,
        )
        if (result.wantsCompact) onCompactNeeded()
        onHeroHeight(result.hero.dp)
        val heroHeight = max(0, (heroOverride ?: result.hero.dp).toPx().roundToInt())
        val heroPlaceable = heroMeasurables.first().measure(Constraints.fixed(width, heroHeight))
        layout(width, heroHeight + bodyPlaceable.height) {
            heroPlaceable.place(0, 0)
            bodyPlaceable.place((width - bodyPlaceable.width) / 2, heroHeight)
        }
    }
}

/** The tightened body for short screens (step 2 of [PaywallFit]): spacing and the logo only, never the text. */
object PaywallCompact {
    /** The standard paywall's stack gap (iOS 7). */
    val stackSpacing: Dp = 4.dp

    /** The header logo's width (iOS 120, `@ScaledMetric`, capped at 170). */
    const val logoWidth: Float = 84f

    /** The gap between plan cards (iOS 6). */
    val planSpacing: Dp = 4.dp

    /**
     * The perk tiles stay 4-up below iOS's 330 pt switch as long as each tile keeps the ≈72 pt that rule is written
     * for (and the text is below xLarge): one row instead of two.
     */
    const val minFourUpTile: Float = 72f

    /** The exit offer's stack gap (iOS 10). */
    val exitStackSpacing: Dp = 6.dp

    /** The fade at the bottom of the standard paywall's scroll area, into the pinned footer. */
    val footerFade: Dp = 12.dp
}
