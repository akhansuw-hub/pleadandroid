// Port of ArgueWin/Features/Onboarding/About/AboutHeroScene.swift. The pixel courtroom hero of the six "About you two"
// screens (CONTRACTS-v2 amendment bd). Composed from the courtroom art code, never a bitmap of the mockup:
//   CourtroomBackground's painting   the bench wall (gallery, flags, bench, plaque), cropped so the bench top sits at
//                                    60 % of the hero; the painted sconces fall above the crop, so two pixel wall
//                                    candles (AboutPixelArt.candle) carry the flame flicker
//   JudgeSprite (Wigsworth)          at the bench (`CourtroomZones.judgeFrame` in the cropped art), blink frames
//   CourtGavelSprite + bench patch   the oath's gavel (`CourtGavelLayer`'s geometry and pivot)
//   CourtAvatarSprite (Sam / Alex)   the mock trial's couple behind two pixel desks (AboutPixelArt.desk, the painted
//                                    podiums' wood and heart)
//   per-screen props                 1 arguing (pointing hands, cross veins, alternate talk), 2 she holds a wallet,
//                                    he a phone (amendment be), 3 facepalm (judge's hand, head shake, both
//                                    palms up), 4 "REPEAT CASE FILES" stack (a folder keeps landing on it, thought
//                                    bubble with a turning loop), 5 calm (heartbeat heart over the bench, hearts
//                                    rising), 6 banners (sway) and the gavel the oath raises.
//
// Motion runs on one clock (`withInfiniteAnimationFrameNanos`, only while the hero is composed, the app resumed and
// motion allowed; Compose tests' infinite-animation policy stops it), read in the draw phase so the loop redraws and
// never recomposes; figures move in whole dp so the art stays crisp. Reduce Motion: a still frame, nothing loops.
//
// Android: `CourtroomZones` cannot take a placed artwork (Swift adds `init(size:art:)`), so `AboutHeroZones` carries
// the same mapping (x / y / rect / judgeCell / judgeFrame) for the cropped art.
package app.plead.android.features.onboarding.about

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.withInfiniteAnimationFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.plead.android.R
import app.plead.android.courtroom.CourtArtCrops
import app.plead.android.courtroom.CourtAvatarSprite
import app.plead.android.courtroom.CourtGavelSprite
import app.plead.android.courtroom.CourtMotionTiming
import app.plead.android.courtroom.CourtroomZones
import app.plead.android.courtroom.JudgeSprite
import app.plead.android.courtroom.ScalesGlyph
import app.plead.android.courtroom.position
import app.plead.android.designsystem.Color as hex
import app.plead.android.designsystem.PixelAvatar
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadPixelArt
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.features.onboarding.MockTrialPersonas
import app.plead.android.features.onboarding.MockTrialPizzaBox
import app.plead.android.features.onboarding.OnboardingPalette
import app.plead.android.features.onboarding.OnboardingStep
import app.plead.android.models.Avatar
import app.plead.android.models.Role
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Which scene the hero shows (amendment bd: one per screen). */
enum class AboutHeroKind {
    arguing, takeaway, facepalm, repeatFiles, calm, oath,
    /** Amendment bg: the intro (the judge writes on a clipboard; both parties calm and attentive; banners, candles). */
    intro;

    companion object {
        fun forStep(step: OnboardingStep): AboutHeroKind = when (step) {
            OnboardingStep.aboutTopics -> takeaway
            OnboardingStep.aboutOutcome -> facepalm
            OnboardingStep.aboutRepeat -> repeatFiles
            OnboardingStep.aboutEasier -> calm
            OnboardingStep.oath -> oath
            OnboardingStep.aboutIntro -> intro
            else -> arguing
        }
    }
}

/** The oath's gavel, driven by the hold (screen 6). */
data class AboutGavelState(
    /** 0 = resting, 1 = fully raised (the hold's progress). */
    val raise: Double = 0.0,
    /** The strike landed (the oath is taken). */
    val struck: Boolean = false,
)

// MARK: - Layout

/** `CourtroomZones` for an artwork placed at `art` in a container of `size` (Swift `CourtroomZones(size:art:)`). */
data class AboutHeroZones(val size: Size, val art: Rect) {
    fun rect(unit: Rect): Rect = Rect(Offset(art.left + unit.left * art.width, art.top + unit.top * art.height), Size(unit.width * art.width, unit.height * art.height))
    fun x(u: Float): Float = art.left + u * art.width
    fun y(u: Float): Float = art.top + u * art.height
    val judgeCell: Float get() = max(2f, floor(art.width * 0.155f / JudgeSprite.columns.toFloat()))
    val judgeFrame: Rect
        get() {
            val s = JudgeSprite.size(judgeCell)
            return Rect(Offset(x(0.5f) - s.width / 2f, y(CourtroomZones.bench.top) + 2f - s.height), s)
        }
}

/** Where everything goes in a hero of `size` dp (pure; unit-tested). */
class AboutHeroLayout(val size: Size) {
    val zones: AboutHeroZones
    /** Avatar / desk cell (whole dp). */
    val cell: Float
    val avatarSize: Float
    val deskColumns: Int
    val deskRows: Int
    val deskTop: Float
    val leftDeskX: Float
    val rightDeskX: Float

    /**
     * Height above the standard hero (amendment bg: a taller hero keeps the standard composition at its bottom and
     * reveals more of the painted wall above; 0 on the question screens).
     */
    val extra: Float

    init {
        val w = max(size.width, 1f)
        val h = max(size.height, 1f)
        // A taller hero (amendment bg) first scales the whole composition up (by at most `maxScale`; the sides crop),
        // then any height still left over shows more of the painted wall above. Laid out for a virtual width `vw`
        // (= w × scale), centred: on the question screens scale = 1 and extra = 0.
        val scale = min(max(h / height(w), 1f), maxScale)
        val vw = w * scale
        val dx = ((w - vw) / 2).roundToInt().toFloat()
        val base = min(h, height(vw))
        extra = h - base
        val artW = vw * artZoom
        val artH = artW / CourtroomZones.artAspect
        // Android: a hero squeezed below the iOS height (`AboutScaffold` on short screens) lowers the bench just enough
        // to keep the judge's wig in view; at the iOS height this is exactly the iOS placement.
        val judgeH = JudgeSprite.size(max(2f, floor(artW * 0.155f / JudgeSprite.columns.toFloat()))).height
        val benchY = extra + max(base * benchLine, judgeH + 2f)
        val art = Rect(Offset(((w - artW) / 2).roundToInt().toFloat(), (benchY - CourtroomZones.bench.top * artH).roundToInt().toFloat()), Size(artW, artH))
        zones = AboutHeroZones(size, art)
        cell = max(3f, min(8f, floor(vw * 0.013f)))
        avatarSize = cell * PixelAvatar.side
        deskColumns = floor(vw * deskWidth / cell).toInt()
        deskTop = min(max(extra + base * deskTopLine, benchY + cell * 2), h - cell * 3).roundToInt().toFloat()
        deskRows = ceil((h - deskTop) / cell).toInt() + 1
        leftDeskX = dx + (vw * deskInset).roundToInt()
        rightDeskX = dx + vw - (vw * deskInset).roundToInt() - deskColumns * cell
    }

    val deskSize: Size get() = Size(deskColumns * cell, deskRows * cell)

    fun deskFrame(r: Role): Rect = Rect(Offset(if (r == Role.plaintiff) leftDeskX else rightDeskX, deskTop), deskSize)

    /** The avatar behind its desk: the lid hides the bottom two rows (hem), shifted a little towards the bench. */
    fun avatarFrame(r: Role): Rect {
        val d = deskFrame(r)
        val cx = d.left + d.width * (if (r == Role.plaintiff) 0.56f else 0.44f)
        return Rect(Offset((cx - avatarSize / 2).roundToInt().toFloat(), deskTop + cell * 2 - avatarSize), Size(avatarSize, avatarSize))
    }

    val judgeFrame: Rect get() = zones.judgeFrame
    val benchTop: Float get() = zones.y(CourtroomZones.bench.top)

    companion object {
        /** Bench top as a fraction of the hero's height. */
        const val benchLine: Float = 0.60f
        /** The painting is drawn this much wider than the hero (crops the outer gallery, enlarges the bench). */
        const val artZoom: Float = 1.4f
        const val deskTopLine: Float = 0.74f
        const val deskWidth: Float = 0.36f
        const val deskInset: Float = 0.035f
        /** A taller hero (amendment bg) first scales the whole composition up, by at most this much (the sides crop). */
        const val maxScale: Float = 1.3f


        /** Hero height for a screen `width` dp wide. */
        fun height(width: Float): Float = min(max((width * 0.53f).roundToInt().toFloat(), 170f), 240f)
    }
}

// MARK: - Scene

/**
 * The hero. [gavel] is read every frame (the oath's hold drives it); [strikeCount] bumps once per strike (the room
 * shakes).
 */
@Composable
fun AboutHeroScene(
    kind: AboutHeroKind,
    modifier: Modifier = Modifier,
    gavel: () -> AboutGavelState = { AboutGavelState() },
    strikeCount: Int = 0,
) {
    val reduceMotion = accessibilityReduceMotion()
    val motion = !reduceMotion
    val clock = remember { mutableFloatStateOf(0f) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(motion, lifecycle) {
        if (!motion) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val start = withInfiniteAnimationFrameNanos { it }
            val base = clock.floatValue
            while (true) withInfiniteAnimationFrameNanos { clock.floatValue = base + (it - start) / 1e9f }
        }
    }
    // The once-per-strike room shake (a 3 dp jolt, 0.28 s).
    val shake = remember { Animatable(0f) }
    LaunchedEffect(strikeCount) {
        if (strikeCount == 0 || !motion) return@LaunchedEffect
        shake.animateTo(0f, keyframes {
            durationMillis = 280
            -3f at 40 using LinearEasing
            3f at 100 using LinearEasing
            -2f at 160 using LinearEasing
            1f at 210 using LinearEasing
        })
    }
    // The strike lands with an ease-in (0.1 s), from wherever the gavel was.
    val strikeAngle = remember { Animatable(50f) }
    val struckNow = gavel().struck
    LaunchedEffect(struckNow) {
        if (!struckNow) return@LaunchedEffect
        strikeAngle.snapTo(50f)
        strikeAngle.animateTo(-5f, tween((CourtMotionTiming.gavelStrike * 1000).toInt(), easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)))
    }
    val background = ImageBitmap.imageResource(R.drawable.courtroom_background)
    val crops = CourtArtCrops.shared()
    val text = rememberTextMeasurer()
    val loop = rememberVectorPainter(Icons.Filled.Sync)

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .background(hex(0x3A1414))
            .testTag("onboarding.about.hero")
            .clearAndSetSemantics { },
    ) {
        val layout = AboutHeroLayout(Size(maxWidth.value, maxHeight.value))
        val z = layout.zones
        val frame = { AboutHeroFrame(kind, layout, if (motion) clock.floatValue.toDouble() else 0.0, motion, gavel(), strikeAngle.value) }
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val x = shake.value
                translationX = x.dp.toPx()
                translationY = (abs(x) * 0.5f).dp.toPx()
            },
        ) {
            // Room, candles, banners, glow, gavel, judge, facepalm (behind the plaque seal).
            Canvas(Modifier.fillMaxSize()) { frame().drawBack(this, background, crops) }
            // `CourtroomScene.plaqueSeal(z)`.
            val plaque = z.rect(CourtroomZones.plaque)
            ScalesGlyph(
                color = PleadColor.mahogany.copy(alpha = 0.55f), size = max(12f, plaque.height * 0.55f).dp,
                modifier = Modifier.position(plaque.center.x, plaque.center.y),
            )
            // Folder stack, bench heart, the couple, their props, the desks and what stands on them.
            Canvas(Modifier.fillMaxSize()) { frame().drawFront(this, text, loop) }
            // The hairline under the hero.
            Canvas(Modifier.fillMaxSize()) {
                drawRect(OnboardingPalette.mahogany.copy(alpha = 0.55f), Offset(0f, size.height - 1.dp.toPx()), Size(size.width, 1.dp.toPx()))
            }
        }
    }
}

// MARK: - One frame

/** The hero at time `t` (seconds since it appeared). Pure function of its inputs. */
private class AboutHeroFrame(
    val kind: AboutHeroKind,
    val layout: AboutHeroLayout,
    val t: Double,
    val motion: Boolean,
    val gavel: AboutGavelState,
    val strikeAngle: Float,
) {
    private val art = AboutPixelArt

    // MARK: Clock helpers

    /** A sine wave in −1…1 with `period` seconds (0 when motion is off). */
    fun wave(period: Double, offset: Double = 0.0): Double = if (motion) sin(2 * PI * (t + offset) / period) else 0.0

    /** 0…1 position inside a repeating `period`. */
    fun cycle(period: Double, offset: Double = 0.0): Double {
        val v = ((t + offset) % period) / period
        return if (v < 0) v + 1 else v
    }

    /** A 0.13 s blink every `period` seconds. */
    fun blink(period: Double, offset: Double): Boolean = motion && cycle(period, offset) * period < 0.13

    /** Whole-dp breathing (−1, 0 or +1). */
    fun breathe(period: Double, offset: Double): Float = (wave(period, offset) * 1.2).roundToInt().toFloat()

    // MARK: Back layer

    fun drawBack(d: DrawScope, background: ImageBitmap, crops: CourtArtCrops) = with(d) {
        val z = layout.zones
        // The painting.
        drawImage(
            background,
            dstOffset = IntOffset(z.art.left.dp.roundToPx(), z.art.top.dp.roundToPx()),
            dstSize = IntSize(z.art.width.dp.roundToPx(), z.art.height.dp.roundToPx()),
            filterQuality = FilterQuality.Medium,
        )
        // A warm light from above, a little deeper at the floor (keeps the figures forward).
        drawRect(Brush.verticalGradient(listOf(hex(0x1C0B0E).copy(alpha = 0.18f), Color.Transparent, hex(0x1C0B0E).copy(alpha = 0.22f))))
        candles()
        if (kind == AboutHeroKind.oath || kind == AboutHeroKind.intro) banners()
        if (kind == AboutHeroKind.calm) benchGlow()
        if (kind == AboutHeroKind.oath) gavelLayer(crops)
        judge()
        if (kind == AboutHeroKind.facepalm) facepalm()
        if (kind == AboutHeroKind.intro) clipboard()
    }

    private fun DrawScope.px(v: Float): Float = v * density
    private fun DrawScope.sprite(rows: List<String>, palette: Map<Char, Color>, cellDp: Float, x: Float, y: Float, alpha: Float = 1f) {
        val s = AboutPixelSprite.size(rows, cellDp)
        // Swift `aboutPlaced`: centre snapped to whole points.
        val cx = (x + s.width / 2).roundToInt() - s.width / 2
        val cy = (y + s.height / 2).roundToInt() - s.height / 2
        drawSprite(rows, palette, px(cellDp), Offset(px(cx), px(cy)), alpha)
    }

    /** The painted wall sconces are in view (a tall hero): their flames flicker as warm glows instead of pixel candles. */
    private val paintedSconcesVisible: Boolean
        get() = layout.zones.y(506f / 2532f) >= 0 && layout.zones.x(0.198f) >= 0 && layout.zones.x(0.802f) <= layout.size.width

    /** A scaled hero (amendment bg) crops the pillars: the candles then hang above the banners' rods instead. */
    private val pillarsCropped: Boolean get() = layout.zones.x(0.198f) < 0

    private fun DrawScope.candles() {
        if (paintedSconcesVisible) sconceGlows() else pixelCandles()
    }

    private fun DrawScope.sconceGlows() {
        val r = layout.zones.art.width * 0.05f
        val pts = listOf(Offset(layout.zones.x(0.198f), layout.zones.y(0.222f)), Offset(layout.zones.x(0.802f), layout.zones.y(0.222f)))
        pts.forEachIndexed { i, p ->
            val c = Offset(px(p.x), px(p.y))
            val alpha = (0.45 + 0.18 * wave(0.9, i * 0.37) + 0.08 * wave(0.23, i.toDouble())).toFloat().coerceIn(0f, 1f)
            drawCircle(
                Brush.radialGradient(listOf(hex(0xFFE2B0).copy(alpha = 0.85f), hex(0xFFD9A8).copy(alpha = 0f)), center = c, radius = px(r)),
                radius = px(r), center = c, alpha = alpha,
            )
        }
    }

    private fun DrawScope.pixelCandles() {
        val c = max(2f, layout.cell - 1)
        val size = AboutPixelSprite.size(art.candle(0), c)
        // On the painted pillars, just under the (cropped) sconces; kept on screen when a scaled hero crops the sides.
        val cropped = pillarsCropped && (kind == AboutHeroKind.oath || kind == AboutHeroKind.intro)
        val xs = if (cropped) listOf(layout.zones.x(0.2786f), layout.zones.x(0.7214f)) else listOf(layout.zones.x(0.198f), layout.zones.x(0.802f))
        val topY = if (cropped) max(2f, max(2f, layout.extra * 0.55f) - size.height - 4) else 6f
        xs.forEachIndexed { i, x ->
            val frame = if (motion) ((t + i * 0.19) * 8).toInt() else 0
            val glow = (0.55 + 0.12 * wave(0.9, i * 0.37) + 0.06 * wave(0.23, i.toDouble())).toFloat()
            val cx = x.roundToInt().toFloat()
            val cy = (topY + size.height / 2).roundToInt().toFloat()
            val glowCenter = Offset(px(cx), px(cy - size.height / 2 + c * 1.5f))
            drawCircle(
                Brush.radialGradient(listOf(hex(0xFFD9A8).copy(alpha = 0.75f), hex(0xFFD9A8).copy(alpha = 0f)), center = glowCenter, radius = px(c * 9)),
                radius = px(c * 9), center = glowCenter, alpha = glow.coerceIn(0f, 1f),
            )
            drawSprite(art.candle(frame), art.candlePalette, px(c), Offset(px(cx - size.width / 2), px(cy - size.height / 2)))
        }
    }

    private fun DrawScope.banners() {
        val c = layout.cell
        // Hung over the painted flags, long enough to reach behind the desks.
        val bannerTop = max(2f, layout.extra * 0.55f)
        val rows = art.banner(length = ceil((layout.deskTop - bannerTop) / c).toInt())
        val size = AboutPixelSprite.size(rows, c)
        val xs = listOf(layout.zones.x(0.2786f) - size.width / 2, layout.zones.x(0.7214f) - size.width / 2)
        xs.forEachIndexed { i, x ->
            val deg = (wave(3.2, i * 0.8) * 2.2).toFloat()
            val left = (x + size.width / 2).roundToInt() - size.width / 2
            val top = (bannerTop + size.height / 2).roundToInt() - size.height / 2
            rotate(deg, pivot = Offset(px(left + size.width / 2), px(top))) {
                drawSprite(rows, art.bannerPalette, px(c), Offset(px(left), px(top)))
            }
        }
    }

    private fun DrawScope.benchGlow() {
        val j = layout.judgeFrame
        val w = j.width * 2.8f
        val h = j.height * 1.9f
        val alpha = (0.75 + 0.25 * wave(2.4)).toFloat()
        val center = Offset(px(j.center.x), px(j.center.y))
        // Swift: an ellipse w × h filled with a radial gradient (end radius 1.3 × the judge's width).
        scale(scaleX = 1f, scaleY = h / w, pivot = center) {
            drawCircle(
                Brush.radialGradient(listOf(hex(0xFFD9A8).copy(alpha = 0.45f), Color.Transparent), center = center, radius = px(j.width * 1.3f)),
                radius = px(w / 2), center = center, alpha = alpha.coerceIn(0f, 1f),
            )
        }
    }

    // MARK: Judge

    private fun DrawScope.judge() {
        val j = layout.judgeFrame
        val cell = layout.zones.judgeCell
        val eyesClosed = kind == AboutHeroKind.facepalm || blink(4.1, 0.6)
        val mouthOpen = kind == AboutHeroKind.oath && gavel.struck
        // Facepalm: a slow, weary head shake (whole points).
        val shake = if (kind == AboutHeroKind.facepalm) (wave(1.3) * 1.6).roundToInt().toFloat() else 0f
        val lift = if (kind == AboutHeroKind.facepalm) 0f else -breathe(3.4, 0.4)
        val grid = JudgeSprite.grid(MockTrialPersonas.judge, eyesClosed = eyesClosed, mouthOpen = mouthOpen)
        val left = (j.center.x + shake - j.width / 2).roundToInt().toFloat()
        val top = (j.center.y + lift - j.height / 2).roundToInt().toFloat()
        val c = px(cell)
        val overlap = 0.25f * density
        grid.forEachIndexed { r, row ->
            row.forEachIndexed { col, color ->
                if (color != null) drawRect(color, Offset(px(left) + col * c, px(top) + r * c), Size(c + overlap, c + overlap))
            }
        }
    }

    /**
     * Amendment be (screen 3): eyes closed, the judge's own hand pressed to the brow from the right (its row 0 is the
     * judge's grid row 1, column 0 the judge's column 0, i.e. one outline cell in), and a sweat drop beside the left of
     * the wig that slides 3 dp and fades every 1.6 s. Both move with the head shake. Reduce Motion: still.
     */
    private fun DrawScope.facepalm() {
        val j = layout.judgeFrame
        val c = layout.zones.judgeCell
        val shake = (wave(1.3) * 1.6).roundToInt().toFloat()
        val dc = max(2f, c - 1)
        val drop = AboutPixelSprite.size(art.sweatDrop, dc)
        val p = if (motion) cycle(1.6) else 0.3
        val slide = floor(p * 3).toFloat()
        val dropOpacity = if (p < 0.75) 1.0 else max(0.0, 1 - (p - 0.75) / 0.25)
        sprite(art.browHand, art.browHandPalette, c, j.left + c + shake, j.top + c * 2)
        sprite(art.sweatDrop, art.sweatPalette, dc, j.left - drop.width + c * 2 + shake, j.top + c * 3 + slide, alpha = dropOpacity.toFloat())
    }

    /**
     * Amendment bg: the judge takes notes. A sheet of paper lies flat on the bench in front of him (below the robe's
     * collar, never over the face), and a quill writes along it: the nib steps one paper cell at a time across the
     * sheet's ruled line (6 steps × 0.2 s), lifts 1 dp between strokes, and starts the next line (two lines, 2.4 s loop).
     * Reduce Motion: still, nib at the start of the first line.
     */
    private fun DrawScope.clipboard() {
        val j = layout.judgeFrame
        val c = layout.zones.judgeCell
        val paper = AboutPixelSprite.size(art.notesPaper, c)
        val qc = max(2f, c - 1)
        val quill = AboutPixelSprite.size(art.quill, qc)
        // Centred a little right of the judge's middle, its top two cells above the bench top.
        val px0 = (j.center.x - paper.width / 2 + c).roundToInt().toFloat()
        val py0 = (layout.benchTop - c * 2).roundToInt().toFloat()
        val p = if (motion) cycle(2.4) else 0.0
        val line = min(1, (p * 2).toInt())
        val step = if (motion) min(5, ((p * 2 - line) * 6).toInt()) else 0
        val lift = if (motion && (t * 10).toInt() % 2 == 0) 1f else 0f
        // The quill's nib is its bottom-left cell.
        val nibX = px0 + (2 + step) * c
        val nibY = py0 + (1 + line) * c + c / 2
        sprite(art.notesPaper, art.notesPaperPalette, c, px0, py0)
        sprite(art.quill, art.quillPalette, qc, nibX, nibY - quill.height - lift)
    }

    private fun DrawScope.gavelLayer(crops: CourtArtCrops) {
        val z = layout.zones
        val patch = z.rect(CourtArtCrops.gavelPatchUnit)
        val cell = z.art.width / CourtArtCrops.artPixels.width * CourtGavelSprite.artCell
        val size = CourtGavelSprite.size(cell)
        val origin = Offset(z.x(CourtArtCrops.gavelOriginUnit.x), z.y(CourtArtCrops.gavelOriginUnit.y))
        // Idle: the judge taps the gavel in the air (0–10°); the hold raises it to 50°; the strike lands at −5°.
        val idle = if (motion) (wave(1.8) * 0.5 + 0.5) * 10 else 0.0
        val angle = if (gavel.struck) strikeAngle else (idle * (1 - gavel.raise) + 50 * gavel.raise).toFloat()
        crops.gavelPatch?.let { img ->
            drawImage(
                img,
                dstOffset = IntOffset(px(patch.left).roundToInt(), px(patch.top).roundToInt()),
                dstSize = IntSize(px(patch.width).roundToInt(), px(patch.height).roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
        val frameLeft = origin.x - CourtGavelSprite.margin * cell
        val pivot = Offset(px(frameLeft + size.width), px(origin.y + size.height * 5.5f / 11f))
        val c = px(cell)
        val m = CourtGavelSprite.margin.toInt()
        rotate(angle, pivot) {
            CourtGavelSprite.rows.forEachIndexed { r, row ->
                row.forEachIndexed { col, ch ->
                    val color = CourtGavelSprite.palette[ch] ?: return@forEachIndexed
                    drawRect(color, Offset(px(frameLeft) + (col + m) * c, px(origin.y) + r * c), Size(c + 0.5f, c + 0.5f))
                }
            }
            if (gavel.struck) {
                for ((col, r) in CourtGavelSprite.sparks) drawRect(PleadColor.cream, Offset(px(frameLeft) + col * c, px(origin.y) + r * c), Size(c, c))
            }
        }
    }

    // MARK: Front layer

    fun drawFront(d: DrawScope, text: TextMeasurer, loop: VectorPainter) = with(d) {
        if (kind == AboutHeroKind.repeatFiles) folderStack(text)
        if (kind == AboutHeroKind.calm) benchHeart()
        party(Role.plaintiff)
        party(Role.defendant)
        props(loop)
        desk(Role.plaintiff)
        desk(Role.defendant)
        deskProps()
    }

    // MARK: Parties

    private fun talking(r: Role): Boolean {
        if (!motion) return false
        return when (kind) {
            AboutHeroKind.arguing -> (cycle(1.7) < 0.5) == (r == Role.plaintiff)
            AboutHeroKind.facepalm -> (cycle(1.1) < 0.5) == (r == Role.plaintiff)
            else -> false
        }
    }

    private fun avatar(r: Role): Avatar = if (r == Role.plaintiff) MockTrialPersonas.plaintiff else MockTrialPersonas.defendant
    private fun skin(a: Avatar): Color = hex(Avatar.skinTones[PixelAvatar.clamp(a.skin, Avatar.skinTones.size)])
    private fun sleeve(a: Avatar): Color = hex(Avatar.topColours[PixelAvatar.clamp(a.top, Avatar.topColours.size)])

    private fun DrawScope.party(r: Role) {
        val f = layout.avatarFrame(r)
        val side = if (r == Role.plaintiff) 1.0 else -1.0
        val speaking = talking(r)
        val mouth = speaking && (t * 7).toInt() % 2 == 0
        val eyes = blink(if (r == Role.plaintiff) 3.7 else 4.4, if (r == Role.plaintiff) 1.1 else 2.3)
        var lift = -breathe(if (r == Role.plaintiff) 3.0 else 3.3, if (r == Role.plaintiff) 0.0 else 1.2)
        var lean = 0.0
        when (kind) {
            AboutHeroKind.arguing -> {
                // Each leans in on their turn (≤ 3°), back out on the other's.
                lean = (if (speaking) 3.0 else -1.0) * side * (if (motion) 1 else 0)
                if (speaking) lift -= 1
            }
            AboutHeroKind.facepalm -> lean = if (speaking) 2 * side else 0.0
            AboutHeroKind.calm -> lean = wave(4.0, if (r == Role.plaintiff) 0.0 else 2.0) * 1.2 * side
            // Calm and attentive: a slight lean towards the bench (1°), breathing on.
            AboutHeroKind.intro -> lean = (if (motion) 1 + wave(5.0, if (r == Role.plaintiff) 0.0 else 2.5) * 0.5 else 1.0) * side
            else -> Unit
        }
        val frame = CourtAvatarSprite.frame(avatar(r), eyes, mouth, 0)
        val left = (f.center.x - f.width / 2).roundToInt().toFloat()
        val top = (f.center.y + lift - f.height / 2).roundToInt().toFloat()
        val n = PixelAvatar.side.toFloat()
        val raw = f.width / n
        val cellDp = if (raw >= 2) floor(raw) else raw
        val inset = (f.width - cellDp * n) / 2f
        val c = px(cellDp)
        val cs = Size(c + 0.25f * density, c + 0.25f * density)
        rotate(lean.toFloat(), pivot = Offset(px(left + f.width / 2), px(top + f.height))) {
            fun tl(rr: Int, cc: Int) = Offset(px(left + inset) + cc * c, px(top + inset) + rr * c)
            for ((rr, cc) in frame.outline) drawRect(PixelAvatar.outlineColor, tl(rr, cc), cs)
            frame.grid.forEachIndexed { rr, row -> row.forEachIndexed { cc, color -> if (color != null) drawRect(color, tl(rr, cc), cs) } }
        }
    }

    /** Hands, marks and bubbles around the parties (behind the desks' lids where they rise from below). */
    private fun DrawScope.props(loop: VectorPainter) {
        when (kind) {
            AboutHeroKind.arguing -> {
                for (r in listOf(Role.plaintiff, Role.defendant)) pointingHand(r)
                for (r in listOf(Role.plaintiff, Role.defendant)) vein(r)
            }
            AboutHeroKind.takeaway, AboutHeroKind.intro -> Unit
            AboutHeroKind.facepalm -> for (r in listOf(Role.plaintiff, Role.defendant)) {
                raisedPalm(r, bob = if (talking(r)) (abs(wave(0.55)) * 4).roundToInt().toFloat() else 0f)
            }
            AboutHeroKind.repeatFiles -> thoughtBubble(Role.defendant, loop)
            AboutHeroKind.calm -> for (i in 0 until 4) risingHeart(i)
            AboutHeroKind.oath -> if (gavel.raise > 0.02 || gavel.struck) {
                for (r in listOf(Role.plaintiff, Role.defendant)) {
                    raisedPalm(r, bob = if (gavel.struck) 0f else ((1 - gavel.raise) * 10).roundToInt().toFloat())
                }
            }
        }
    }

    private fun DrawScope.pointingHand(r: Role, slow: Boolean = false) {
        val f = layout.avatarFrame(r)
        val c = max(2f, layout.cell - 1)
        val rows = if (r == Role.plaintiff) art.pointRight else art.mirrored(art.pointRight)
        val size = AboutPixelSprite.size(rows, c)
        val a = avatar(r)
        val jab = if (slow) ((wave(1.6) * 0.5 + 0.5) * 3).roundToInt().toFloat()
        else if (talking(r)) (abs(wave(0.36)) * 4).roundToInt().toFloat() else 0f
        val x = if (r == Role.plaintiff) f.right - c * 3 + jab else f.left - size.width + c * 3 - jab
        sprite(rows, art.handPalette(skin(a), sleeve(a)), c, x, layout.deskTop - size.height - c * 3)
    }

    private fun DrawScope.raisedPalm(r: Role, bob: Float) {
        val f = layout.avatarFrame(r)
        val c = max(2f, layout.cell - 1)
        val rows = if (r == Role.plaintiff) art.palm else art.mirrored(art.palm)
        val size = AboutPixelSprite.size(rows, c)
        val a = avatar(r)
        val x = if (r == Role.plaintiff) f.right - c * 2 else f.left - size.width + c * 2
        sprite(rows, art.handPalette(skin(a), sleeve(a)), c, x, layout.deskTop - size.height - c - bob)
    }

    private fun DrawScope.vein(r: Role) {
        val f = layout.avatarFrame(r)
        val c = max(2f, layout.cell - 1)
        val size = AboutPixelSprite.size(art.vein, c)
        val on = if (motion) talking(r) else r == Role.plaintiff
        if (!on) return
        val local = cycle(1.7 / 2)
        val pop = if (motion) (if (local < 0.18) local / 0.18 * 1.25 else max(1.0, 1.25 - (local - 0.18) * 2)) else 1.0
        val x = if (r == Role.plaintiff) f.right - c * 4 else f.left - c
        val y = f.top + c
        val center = Offset(px(x + size.width / 2), px(y + size.height / 2))
        scale(pop.toFloat(), pivot = center) { sprite(art.vein, art.veinPalette, c, x, y) }
    }

    private fun DrawScope.thoughtBubble(r: Role, loop: VectorPainter) {
        val f = layout.avatarFrame(r)
        val c = max(2f, layout.cell - 1)
        val size = AboutPixelSprite.size(art.bubble, c)
        val bob = (wave(2.2) * 1.5).roundToInt().toFloat()
        val x = f.right - c * 4
        val y = f.top - size.height + c * 4 + bob
        sprite(art.mirrored(art.bubble), art.bubblePalette, c, x, y)
        // The turning loop ("arrow.triangle.2.circlepath").
        val icon = px(c * 4.2f)
        val center = Offset(px(x + size.width / 2), px(y + size.height / 2 - c * 1.4f))
        rotate(if (motion) (t * 120).toFloat() else 0f, pivot = center) {
            translate(center.x - icon / 2, center.y - icon / 2) {
                with(loop) { draw(Size(icon, icon), colorFilter = ColorFilter.tint(OnboardingPalette.burgundy)) }
            }
        }
    }

    private fun DrawScope.risingHeart(i: Int) {
        val r = if (i % 2 == 0) Role.plaintiff else Role.defendant
        val f = layout.avatarFrame(r)
        val c = max(2f, layout.cell - 2)
        val size = AboutPixelSprite.size(PleadPixelArt.heart, c)
        val p = if (motion) cycle(3.2, i * 0.8) else 0.35
        val rise = p.toFloat() * 26
        val opacity = if (p < 0.15) p / 0.15 else max(0.0, 1 - (p - 0.15) / 0.85)
        val sway = (wave(1.1, i.toDouble()) * 2).roundToInt().toFloat()
        val x = (if (r == Role.plaintiff) f.right - c * 2 else f.left - size.width + c * 2) + sway + (i / 2) * c * 3
        sprite(PleadPixelArt.heart, art.heartPalette, c, x, f.top + c * 2 - rise, alpha = opacity.toFloat())
    }

    // MARK: Bench props

    private fun DrawScope.folderStack(text: TextMeasurer) {
        val c = max(2f, layout.cell - 1)
        val j = layout.judgeFrame
        val cols = 15
        val count = 9
        val w = cols * c
        val rowH = c * 3
        val x0 = j.left - w - c * 2
        val base = layout.benchTop + c
        // Folders, bottom up, each nudged a cell left or right (a hastily kept pile).
        val nudges = listOf(0f, 1f, -1f, 0f, 1f, 0f, -1f, 1f, 0f)
        // The loop: a folder drops onto the pile (ease-in, 0.42 s), lands with a 1-cell bounce, then fades for the
        // next one (2.6 s period).
        val p = cycle(2.6, 0.4)
        val fall = min(1.0, p / 0.16)
        val dropY = base - (count + 1) * rowH - (if (motion) ((1 - fall * fall) * 34).toFloat() else 0f)
        val bounce = if (motion && p >= 0.16 && p < 0.22) -c else 0f
        val dropOpacity = if (motion) (if (p > 0.85) max(0.0, 1 - (p - 0.85) / 0.15) else 1.0) else 1.0
        val squash = if (motion && p >= 0.16 && p < 0.2) 0.97f else 1f
        scale(1f, squash, pivot = Offset(px(x0 + w / 2), px(base))) {
            for (i in 0 until count) {
                sprite(art.folder(cols, shade = i % 2 == 1), art.folderPalette, c, x0 + nudges[i] * c, base - (i + 1) * rowH)
            }
        }
        sprite(art.folder(cols, shade = false), art.folderPalette, c, x0 + c, dropY + bounce, alpha = dropOpacity.toFloat())
        // "REPEAT / CASE FILES" (fixed size: `.dynamicTypeSize(.large)`).
        val fontDp = max(6f, c * 2.3f)
        val style = TextStyle(
            fontSize = (fontDp / fontScale).sp, fontWeight = FontWeight.Black, letterSpacing = (0.2f / fontScale).sp,
            textAlign = TextAlign.Center, lineHeight = ((fontDp * 1.18f - 1) / fontScale).sp, color = PleadColor.cocoa,
        )
        val measured = text.measure("REPEAT\nCASE FILES", style)
        val pw = measured.size.width + px(c) * 2
        val ph = measured.size.height + px(c * 0.6f) * 2
        val cx = px(x0 + w / 2)
        val cy = px(base - count * rowH * 0.74f)
        val tl = Offset((cx - pw / 2).roundToInt().toFloat(), (cy - ph / 2).roundToInt().toFloat())
        drawRect(hex(0xFFFDFC), tl, Size(pw, ph))
        val stroke = 1.dp.toPx()
        drawRect(AboutPixelArt.outline, Offset(tl.x + stroke / 2, tl.y + stroke / 2), Size(pw - stroke, ph - stroke), style = Stroke(stroke))
        drawText(measured, topLeft = Offset(tl.x + px(c), tl.y + px(c * 0.6f)))
    }

    private fun DrawScope.benchHeart() {
        val j = layout.judgeFrame
        val c = max(2f, layout.cell - 1)
        val size = AboutPixelSprite.size(PleadPixelArt.heart, c)
        val p = cycle(1.25)
        val beat = if (motion) (if (p < 0.1) 1 + p / 0.1 * 0.18 else if (p < 0.25) 1.18 - (p - 0.1) / 0.15 * 0.18 else 1.0) else 1.0
        val float = (wave(2.4) * 2).roundToInt().toFloat()
        val x = j.center.x - size.width / 2
        val y = j.top - size.height - c * 2 + float
        val center = Offset(px(x + size.width / 2), px(y + size.height / 2))
        scale(beat.toFloat(), pivot = center) { sprite(PleadPixelArt.heart, art.heartPalette, c, x, y) }
    }

    // MARK: Desks

    private fun DrawScope.desk(r: Role) {
        val d = layout.deskFrame(r)
        val hc = max(2f, layout.cell - 1)
        val heart = AboutPixelSprite.size(PleadPixelArt.heart, hc)
        val panelTop = d.top + layout.cell * 6
        sprite(art.desk(layout.deskColumns, layout.deskRows), art.deskPalette, layout.cell, d.left, d.top)
        sprite(
            PleadPixelArt.heart, art.deskHeartPalette, hc,
            d.center.x - heart.width / 2, min(panelTop + layout.cell, layout.size.height - heart.height - 3),
        )
    }

    /**
     * Screen 2 (amendment be, supersedes the plates and pizza box): she holds a small wallet, he holds a small phone,
     * each beside its owner's body at desk height (the gripping hand rests on the desk lid), never over a face or body.
     */
    private fun DrawScope.deskProps() {
        if (kind != AboutHeroKind.takeaway) return
        heldProp(Role.plaintiff)
        heldProp(Role.defendant)
    }

    private fun DrawScope.heldProp(r: Role) {
        val pc = AboutHeroFrameProps.propCell
        val icon = if (r == Role.plaintiff) AboutOptionIcon.wallet else AboutOptionIcon.phone
        val size = AboutPixelSprite.size(icon.rows, pc)
        val grip = AboutPixelSprite.size(art.grip, pc)
        val f = layout.avatarFrame(r)
        val ac = CourtAvatarSprite.cell(f.width)
        // Body edges: the plaintiff's hoodie ends at column 13, the defendant's shirt starts at column 4. The phone grid
        // has two empty columns each side, so it is pulled in by 2 cells.
        val x = if (r == Role.plaintiff) f.left + 13 * ac + AboutHeroFrameProps.propGap
        else f.left + 4 * ac - AboutHeroFrameProps.propGap - size.width + 2 * pc
        val gripTop = layout.deskTop + 2 - grip.height
        val propTop = gripTop - size.height + 3 * pc
        val bob = if (r == Role.plaintiff) (wave(1.4) * 1.5).roundToInt().toFloat() else (wave(2.1, 0.5) * 1).roundToInt().toFloat()
        val tilt = if (r == Role.plaintiff) wave(2.8) * 5 else wave(2.0, 0.3) * 3
        val a = avatar(r)
        val left = (x + size.width / 2).roundToInt() - size.width / 2
        val top = (propTop - bob + size.height / 2).roundToInt() - size.height / 2
        rotate(tilt.toFloat(), pivot = Offset(px(left + size.width / 2), px(top + size.height))) {
            drawSprite(icon.rows, icon.palette, px(pc), Offset(px(left), px(top)))
            if (r == Role.defendant) {
                // The phone's screen glows on and off (scrolling).
                val glow = if (motion) 0.12 + 0.18 * (wave(0.7) * 0.5 + 0.5) else 0.15
                val gw = 6 * pc
                val gh = 7 * pc
                drawRect(
                    Color.White, Offset(px(left + size.width / 2 - gw / 2), px(top + size.height / 2 - gh / 2 - 0.5f * pc)),
                    Size(px(gw), px(gh)), alpha = glow.toFloat(),
                )
            }
        }
        sprite(art.grip, art.handPalette(skin(a), sleeve(a)), pc, x + (size.width - grip.width) / 2, gripTop - bob)
    }
}

/**
 * Screen 2's held props (amendment be; pure, mirrors iOS): icon grid 12 × 12 at `propCell` (2 dp → 24 dp), the grip
 * 6 × 4 at the same cell, overlapping the prop's bottom 3 cells, its bottom 2 dp into the desk lid; `propGap` dp clear
 * of the body's edge (hoodie / shirt columns of the 16-cell avatar grid).
 */
object AboutHeroFrameProps {
    const val propCell: Float = 2f
    const val propGap: Float = 3f
}
