// Port of ArgueWin/Features/Onboarding/SummonsIntroView.swift. Onboarding step 3 · the summons explainer
// (CONTRACTS-v2 amendment ai, docs/summons-onboarding-brief).
//
//   hero     the mock trial's courtroom at rest, full bleed under the status bar and the top bar: Judge
//            Wigsworth at the bench, Sam and Alex at their podiums (no name tags), the audience, ambient idles
//            only (blinks, 1 px settles, audience bob). No dialogue, no case chip. The easel holds a
//            "COURT SUMMONS / YOU vs PARTNER" card in the court-file style (paper, gold rule, pixel heart).
//   over     only the eyebrow (THE DEMO IS OVER, court gold) and the headline (Fraunces displayXL, cream, ≤ 2
//            lines) sit on the art, on the upper wall between the chrome and the judge's wig, over a dark scrim
//            that quietens the lamps and windows behind them.
//   sheet    an opaque warm-cream sheet (rounded top, decorative handle): WHEN TO CALL COURT, the lead, a hairline,
//            HOW IT WORKS, the explanation, then CONTINUE above the navigation bar.
//
// The art is placed from the sheet up: the podiums' hearts sit just above the sheet's top edge, and the art is
// scaled only as far as needed to cover the top of the screen. If the headline block would reach the judge (large
// font scale), it drops onto the top of the sheet instead (wine on cream). The sheet grows with the font scale
// and scrolls once the hero reaches its minimum (judge → podiums still visible under the chrome).
//
// Motion: a 0.32 s settle (headline 10 pt rise, sheet 28 pt rise, both fading in); Reduce Motion fades only.
// Nothing here links a partner, files a case, sends a summons or calls the network / AI: CONTINUE only advances.
package app.plead.android.features.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.plead.android.app.AppModel
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.FrauncesFont
import app.plead.android.designsystem.PixelGrid
import app.plead.android.designsystem.PleadBrandColor
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadPixelArt
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.fixedSp
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.Role
import kotlin.math.max
import kotlin.math.min
import app.plead.android.courtroom.CourtroomZones
import app.plead.android.courtroom.CourtroomBackground
import app.plead.android.courtroom.JudgeSprite
import app.plead.android.courtroom.CourtFigurePose
import app.plead.android.courtroom.CourtMotionTiming
import app.plead.android.courtroom.CourtRevealMemory
import app.plead.android.courtroom.CourtMotionDirector
import app.plead.android.courtroom.CourtCrowdLayer

object SummonsIntroView {
    // Exact copy (amendment ai).
    const val eyebrow = "THE DEMO IS OVER"
    const val headline = "Your turn to summon them."
    const val sheetEyebrow = "WHEN TO CALL COURT"
    const val lead = "For the little arguments you can't quite settle yourselves."
    const val howEyebrow = "HOW IT WORKS"
    const val explanation = "Link your partner, file a case and serve a summons. They answer before the judge rules."
    const val cta = "CONTINUE"
    /** The easel card (decorative; read as part of the hero description). */
    const val cardKicker = "COURT"
    const val cardTitle = "SUMMONS"
    const val cardParties = "YOU vs PARTNER"

    const val heroAccessibilityText =
        "A pixel-art courtroom: the judge at the bench, two partners at their podiums, and a court summons on the easel, You versus Partner."

    // Motion (amendment ai: a short settle, 250–350 ms; Reduce Motion = fade).
    const val settleDuration: Double = 0.32
    const val sheetDelay: Double = 0.06
    const val headlineRise: Float = 10f
    const val sheetRise: Float = 28f

    fun settleAnimation(reduceMotion: Boolean): AnimationSpec<Float> =
        if (reduceMotion) tween(OnboardingMotionTokens.reduceMotionCrossfade.millis(), easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))
        else tween(settleDuration.millis(), easing = OnboardingEaseOut)
}

@Composable
fun SummonsIntroView(app: AppModel) {
    val reduceMotion = accessibilityReduceMotion()
    val density = LocalDensity.current
    // Ambient idles only (a private director: no reveals claimed, no courtroom analytics).
    val ambient = remember { CourtMotionDirector(memory = CourtRevealMemory()) }
    val settled = remember { Animatable(0f) }
    var headlineHeight by remember { mutableFloatStateOf(0f) }
    var copyHeight by remember { mutableFloatStateOf(0f) }
    var footerHeight by remember { mutableFloatStateOf(0f) }
    // The window's safe area: the art is full bleed, but the text and the button still clear the status bar, top
    // bar and navigation bar (iOS reads the key window's insets on appear).
    val insets = WindowInsets.safeDrawing
    val safeTop = with(density) { insets.getTop(this).toDp().value }
    val safeBottom = with(density) { insets.getBottom(this).toDp().value }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        ambient.reduceMotion = reduceMotion
        ambient.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        ambient.appear(analytics = false)
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> ambient.setActive(true)
                Lifecycle.Event.ON_PAUSE -> ambient.setActive(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            ambient.disappear()
        }
    }
    LaunchedEffect(reduceMotion) { ambient.reduceMotion = reduceMotion }
    LaunchedEffect(Unit) {
        if (settled.value == 1f) return@LaunchedEffect
        // Back from How Plead Works: already familiar, no entrance.
        if (app.onboardingModel.direction != OnboardingModel.Direction.forward) {
            settled.snapTo(1f)
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(SummonsIntroView.sheetDelay.millis().toLong())
        settled.animateTo(1f, SummonsIntroView.settleAnimation(reduceMotion))
    }

    BoxWithConstraints(Modifier.fillMaxSize().testTag("onboarding.summonsIntro")) {
        val fullWidth = maxWidth
        val layout = SummonsIntroLayout(
            size = Size(maxWidth.value, maxHeight.value), safeTop = safeTop, safeBottom = safeBottom,
            headlineHeight = headlineHeight, copyHeight = copyHeight, footerHeight = footerHeight,
        )
        val v = settled.value
        val start = OnboardingMotionTokens.revealStartOpacity.toFloat()
        Box(Modifier.fillMaxSize()) {
            SummonsCourtHero(layout, ambient)
            // Dark wash behind the chrome and the headline: quietens the chandeliers, sconces and windows under the
            // text and fades out well above the judge.
            val end = layout.scrimBottom
            val solid = if (layout.headlineInHero) max(layout.headlineBottom - 8, 1f) / end else 0.5f
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(end.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to SummonsIntroLayout.scrimColor.copy(alpha = 0.86f),
                            min(solid, 0.95f) to SummonsIntroLayout.scrimColor.copy(alpha = 0.74f),
                            1f to SummonsIntroLayout.scrimColor.copy(alpha = 0f),
                        ),
                    )
                    .clearAndSetSemantics { },
            )
            if (layout.headlineInHero) {
                HeadlineBlock(
                    onSheet = false,
                    onHeight = { headlineHeight = it },
                    modifier = Modifier
                        .placeAt(0f, layout.headlineTop)
                        .width(fullWidth)
                        .padding(horizontal = SummonsIntroLayout.gutter.dp)
                        .graphicsLayer {
                            alpha = start + (1f - start) * v
                            translationY = if (reduceMotion) 0f else SummonsIntroView.headlineRise.dp.toPx() * (1f - v)
                        },
                )
            }
            SummonsSheet(
                app, layout,
                onHeadlineHeight = { headlineHeight = it },
                onCopyHeight = { copyHeight = it },
                onFooterHeight = { footerHeight = it },
                modifier = Modifier
                    .placeAt(0f, layout.sheetTop)
                    .width(fullWidth)
                    .height(layout.sheetHeight.dp)
                    .graphicsLayer {
                        alpha = start + (1f - start) * v
                        translationY = if (reduceMotion) 0f else SummonsIntroView.sheetRise.dp.toPx() * (1f - v)
                    },
            )
        }
    }
}

// MARK: Pieces

/**
 * Eyebrow + headline. Over the art: gold eyebrow, cream headline (≤ 2 lines). On the sheet (large text):
 * burgundy eyebrow, wine headline (gold on cream would fail contrast).
 */
@Composable
private fun HeadlineBlock(onSheet: Boolean, onHeight: (Float) -> Unit, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .widthIn(max = SummonsIntroLayout.maxTextWidth.dp)
                .fillMaxWidth()
                .onSizeChanged { onHeight(with(density) { it.height.toDp().value }) },
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
        ) {
            Text(
                SummonsIntroView.eyebrow,
                style = PleadType.labelCapsTracked,
                color = if (onSheet) OnboardingPalette.burgundy else OnboardingPalette.gold,
                modifier = Modifier.testTag("onboarding.summonsIntro.eyebrow"),
            )
            Text(
                SummonsIntroView.headline,
                style = PleadType.displayXL.copy(
                    shadow = if (onSheet) null else Shadow(SummonsIntroLayout.scrimColor.copy(alpha = 0.55f), Offset(0f, with(density) { 1.dp.toPx() }), with(density) { 6.dp.toPx() }),
                ),
                color = if (onSheet) OnboardingPalette.wine else OnboardingPalette.cream,
                maxLines = if (onSheet) Int.MAX_VALUE else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }.testTag("onboarding.summonsIntro.headline"),
            )
        }
    }
}

@Composable
private fun SummonsSheet(
    app: AppModel,
    layout: SummonsIntroLayout,
    onHeadlineHeight: (Float) -> Unit,
    onCopyHeight: (Float) -> Unit,
    onFooterHeight: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val shape = RoundedCornerShape(topStart = SummonsIntroLayout.sheetRadius.dp, topEnd = SummonsIntroLayout.sheetRadius.dp)
    Column(
        modifier
            .pleadShadow(SummonsIntroLayout.scrimColor.copy(alpha = 0.28f), radius = 14.dp, y = (-4).dp, shape = shape)
            .background(OnboardingPalette.cream, shape),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Drag-indicator style handle: decorative (this is not a real sheet).
        Box(Modifier.height(SummonsIntroLayout.handleHeight.dp).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(40.dp, 5.dp).background(OnboardingPalette.mahogany.copy(alpha = 0.28f), RoundedCornerShape(50)))
        }
        val fadeScroll = layout.sheetScrolls
        Box(
            Modifier
                .fillMaxWidth()
                .height(layout.scrollHeight.dp)
                // When the copy scrolls (large text), it fades out above the button instead of being cut.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    if (fadeScroll) {
                        val h = 20.dp.toPx()
                        drawRect(
                            Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - h, endY = size.height),
                            topLeft = Offset(0f, size.height - h),
                            size = Size(size.width, h),
                            blendMode = BlendMode.DstIn,
                        )
                    }
                }
                .verticalScroll(rememberScrollState()),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = (SummonsIntroLayout.maxTextWidth + SummonsIntroLayout.gutter * 2).dp)
                    .fillMaxWidth()
                    .padding(horizontal = SummonsIntroLayout.gutter.dp)
                    .padding(top = PleadSpacing.xs, bottom = PleadSpacing.s),
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
            ) {
                if (!layout.headlineInHero) HeadlineBlock(onSheet = true, onHeight = onHeadlineHeight)
                SummonsCopy(onHeight = onCopyHeight)
            }
        }
        OnboardingPrimaryButton(
            SummonsIntroView.cta,
            modifier = Modifier
                .onSizeChanged { onFooterHeight(with(density) { it.height.toDp().value } + PleadSpacing.m.value + layout.buttonBottomPadding) }
                .widthIn(max = (SummonsIntroLayout.maxTextWidth + SummonsIntroLayout.gutter * 2).dp)
                .padding(horizontal = SummonsIntroLayout.gutter.dp)
                .padding(top = PleadSpacing.m, bottom = layout.buttonBottomPadding.dp),
            identifier = "onboarding.summonsIntro.continue",
        ) { app.onboardingModel.advance() }
    }
}

@Composable
private fun SummonsCopy(onHeight: (Float) -> Unit) {
    val density = LocalDensity.current
    Column(Modifier.fillMaxWidth().onSizeChanged { onHeight(with(density) { it.height.toDp().value }) }) {
        Text(
            SummonsIntroView.sheetEyebrow, style = PleadType.labelCapsTracked, color = OnboardingPalette.burgundy,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            SummonsIntroView.lead, style = PleadType.displayM, color = OnboardingPalette.wine,
            modifier = Modifier.padding(top = PleadSpacing.s).testTag("onboarding.summonsIntro.lead"),
        )
        Box(Modifier.padding(vertical = PleadSpacing.l).fillMaxWidth().height(1.dp).background(OnboardingPalette.border).clearAndSetSemantics { })
        Text(
            SummonsIntroView.howEyebrow, style = PleadType.labelCapsTracked, color = OnboardingPalette.burgundy,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            SummonsIntroView.explanation,
            style = PleadType.body.copy(lineHeight = (PleadType.body.fontSize.value * 1.2f + 3f).sp),
            color = OnboardingPalette.cocoa,
            modifier = Modifier.padding(top = PleadSpacing.s).testTag("onboarding.summonsIntro.explanation"),
        )
    }
}

// MARK: - Layout

/** Where the sheet, the art and the headline go for a screen of `size` (pure, unit-tested). All values in dp. */
class SummonsIntroLayout(
    val size: Size,
    val safeTop: Float,
    val safeBottom: Float,
    headlineHeight: Float,
    copyHeight: Float,
    footerHeight: Float,
) {
    /** Bottom of the top bar (status bar + progress rail): nothing of ours goes above it. */
    val chromeBottom: Float = safeTop + OnboardingContainer.chromeHeight.value
    val headlineTop: Float = chromeBottom + headlineInset
    val headlineInHero: Boolean
    val sheetHeight: Float
    val scrollHeight: Float
    val buttonBottomPadding: Float = max(safeBottom, PleadSpacing.l.value) + PleadSpacing.s.value
    /** The sheet's content is taller than the space it has (it scrolls). */
    val sheetScrolls: Boolean
    /** The courtroom art: its size and vertical offset (horizontally centred). */
    val artSize: Size
    val artOffsetY: Float
    /** Top of the judge's wig, in screen points. */
    val judgeTop: Float
    val headlineBottom: Float

    val sheetTop: Float get() = size.height - sheetHeight

    /** Where the scrim has faded out: under the headline, never down onto the judge. */
    val scrimBottom: Float
        get() {
            val wanted = if (headlineInHero) headlineBottom + 40 else chromeBottom + 36
            return max(chromeBottom, min(wanted, judgeTop))
        }

    private data class Placed(val art: Size, val dy: Float, val judgeTop: Float)

    init {
        val w = max(size.width, 1f)
        val h = max(size.height, 1f)
        val footer = if (footerHeight > 0) footerHeight else 56 + PleadSpacing.m.value + buttonBottomPadding

        val fillHeight = w / CourtroomZones.artAspect
        // The hero never shrinks below: chrome, then judge's wig → the anchor row at the art's natural size.
        val judgeUnit = judgeTopUnit(fillHeight)
        val heroMin = chromeBottom + (anchorUnit - judgeUnit) * fillHeight - anchorGap + 8
        val sheetMax = max(h - heroMin, footer + handleHeight + 80)

        fun place(sheet: Float): Placed {
            val anchorY = h - sheet + anchorGap
            // Fill the width, and cover the screen's top edge (art top ≤ 0).
            val artH = max(fillHeight, anchorY / anchorUnit)
            val dy = anchorY - anchorUnit * artH
            return Placed(Size(artH * CourtroomZones.artAspect, artH), dy, dy + judgeTopUnit(artH) * artH)
        }

        val headlineBlock = max(headlineHeight, 0f)
        val baseContent = copyHeight + PleadSpacing.xs.value + PleadSpacing.s.value
        val baseSheet = handleHeight + baseContent + footer
        val base = place(min(baseSheet, sheetMax))
        val fits = base.judgeTop - judgeClearance >= headlineTop + headlineBlock
        headlineInHero = fits
        headlineBottom = headlineTop + headlineBlock

        val content = if (fits) baseContent else baseContent + headlineBlock + PleadSpacing.l.value
        val natural = handleHeight + content + footer
        sheetHeight = min(natural, sheetMax)
        sheetScrolls = natural > sheetMax + 0.5f
        scrollHeight = max(sheetHeight - handleHeight - footer, 0f)
        val placed = if (fits) base else place(sheetHeight)
        artSize = placed.art
        artOffsetY = placed.dy
        judgeTop = placed.judgeTop
    }

    companion object {
        val gutter: Float = PleadSpacing.xl.value
        const val maxTextWidth: Float = 520f
        const val sheetRadius: Float = 28f
        const val handleHeight: Float = 22f
        /**
         * Art row (unit y) that sits `anchorGap` below the sheet's top edge: just under the podiums' hearts, so the
         * judge, both partners and the podium fronts stay above the sheet.
         */
        const val anchorUnit: Float = 0.64f
        const val anchorGap: Float = 8f
        /** Space kept between the headline block and the top of the judge's wig. */
        const val judgeClearance: Float = 14f
        /** Gap under the top bar before the eyebrow. */
        const val headlineInset: Float = 6f
        /** Scrim tone behind the chrome and the headline (the courtroom's darkest wood). */
        val scrimColor = Color(hex = 0x1C0B0E)

        /**
         * The judge sprite's top as a unit of the art height, for art `artHeight` points tall (the sprite's cell
         * size is whole points, so this varies slightly with scale).
         */
        fun judgeTopUnit(artHeight: Float): Float {
            val zones = CourtroomZones(Size(artHeight * CourtroomZones.artAspect, artHeight))
            return zones.judgeFrame.top / max(artHeight, 1f)
        }
    }
}

// MARK: - Hero

/**
 * The mock trial's courtroom at its ready state, drawn from the same pieces as `MockTrialStage` (painted room,
 * audience, Judge Wigsworth, Sam and Alex at their podiums) with the ambient idles only, and the summons card on
 * the easel. Decorative: TalkBack reads the copy, not the art.
 */
@Composable
internal fun SummonsCourtHero(layout: SummonsIntroLayout, ambient: CourtMotionDirector, modifier: Modifier = Modifier) {
    val reduceMotion = accessibilityReduceMotion()
    val size = layout.artSize
    val z = CourtroomZones(size)
    val board = z.rect(CourtroomZones.easel).inflate(3f)
    val pose = if (reduceMotion) CourtFigurePose() else ambient.judgePose
    val judgeLift by animateFloatAsState(
        pose.lift,
        if (reduceMotion) tween(0) else tween(CourtMotionTiming.bobEase.millis(), easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
        label = "judgeLift",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(min(layout.size.height, layout.sheetTop + SummonsIntroLayout.sheetRadius + 4).dp)
            .clipToBounds()
            .background(SummonsIntroLayout.scrimColor)
            .clearAndSetSemantics { },
    ) {
        Box(
            Modifier
                .placeAt((layout.size.width - size.width) / 2, layout.artOffsetY)
                .requiredSize(size.width.dp, size.height.dp),
        ) {
            CourtroomBackground(size)
            CourtCrowdLayer(z, ambient)
            val frame = z.judgeFrame
            JudgeSprite(
                MockTrialPersonas.judge,
                Modifier.centeredAt(frame.center.x, frame.center.y - judgeLift),
                cell = z.judgeCell,
                eyesClosed = pose.eyesClosed,
            )
            PlaqueSeal(z)
            for (role in listOf(Role.plaintiff, Role.defendant)) {
                MockTrialPodium(role, z, pose = ambient.pose(role), showsTag = false)
            }
            SummonsEaselCard(
                height = board.height,
                modifier = Modifier.centeredAt(board.center.x, board.center.y).requiredSize(board.width.dp, board.height.dp),
            )
        }
    }
}

/** `CourtroomScene.plaqueSeal(_:)` (wave 3a): the pixel scales on the bench's brass plaque. */
@Composable
private fun PlaqueSeal(z: CourtroomZones) {
    val plaque: Rect = z.rect(CourtroomZones.plaque)
    val s = max(12f, plaque.height * 0.55f)
    val color = PleadColor.mahogany.copy(alpha = 0.55f)
    Canvas(Modifier.centeredAt(plaque.center.x, plaque.center.y).requiredSize(s.dp, (s * 7 / 9).dp)) {
        // `ScalesGlyph`.
        val rows = listOf("....X....", "XXXXXXXXX", "X...X...X", "X...X...X", "XX..X..XX", "....X....", "..XXXXX..")
        val cell = size.width / 9
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                if (ch == 'X') drawRect(color, Offset(c * cell, r * cell), Size(cell + 0.1f, cell + 0.1f))
            }
        }
    }
}

// MARK: - Easel card

/**
 * "COURT SUMMONS / YOU vs PARTNER" on the easel, in the court-file style: paper white, cocoa edge, an inner gold
 * rule, a pixel heart on top and a gold rule between the title and the parties. Sized to the painted board (fixed
 * point sizes scaled with it, like the rest of the art: it does not grow with the font scale).
 */
@Composable
fun SummonsEaselCard(height: Float, modifier: Modifier = Modifier) {
    val s = max(height / 96, 0.6f)
    val shape = RoundedCornerShape((6 * s).dp)
    Box(
        modifier
            .pleadShadow(Color.Black.copy(alpha = 0.3f), radius = 5.dp, y = 3.dp, shape = shape)
            .background(OnboardingPalette.paper, shape)
            .border(1.5.dp, PleadColor.cocoa.copy(alpha = 0.85f), shape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().padding((3 * s).dp)
                .border(1.dp, OnboardingPalette.gold.copy(alpha = 0.75f), RoundedCornerShape((max(6 * s - 3 * s, 0f)).dp)),
        )
        Column(Modifier.padding(horizontal = (6 * s).dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PixelGrid(
                rows = PleadPixelArt.heart,
                colors = mapOf('c' to PleadBrandColor.coral, 'h' to Color(hex = 0xF7B7B9), 'd' to OnboardingPalette.burgundy),
                modifier = Modifier.size((18 * s).dp, (15 * s).dp),
            )
            Text(
                SummonsIntroView.cardKicker,
                style = TextStyle(fontSize = fixedSp(9.5f * s), fontWeight = FontWeight.Bold, letterSpacing = (1.4f * s).sp),
                color = OnboardingPalette.burgundy,
                modifier = Modifier.padding(top = (4 * s).dp),
            )
            Text(
                SummonsIntroView.cardTitle,
                style = SummonsEaselCard.serif(fixedSp(17f * s).value).copy(fontSize = fixedSp(17f * s), letterSpacing = (0.6f * s).sp),
                color = OnboardingPalette.wine,
                maxLines = 1,
            )
            Box(Modifier.padding(vertical = (5 * s).dp).size((58 * s).dp, (1.5f * s).dp).background(OnboardingPalette.gold, RoundedCornerShape(50)))
            Text(
                SummonsIntroView.cardParties,
                style = TextStyle(fontSize = fixedSp(9f * s), fontWeight = FontWeight.Bold, letterSpacing = (0.8f * s).sp),
                color = OnboardingPalette.cocoa,
                maxLines = 1,
            )
        }
    }
}

object SummonsEaselCard {
    fun serif(size: Float): TextStyle =
        if (FrauncesFont.isAvailable) TextStyle(fontFamily = FrauncesFont.family, fontWeight = FontWeight.Bold, fontSize = size.sp)
        else TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = size.sp)
}
