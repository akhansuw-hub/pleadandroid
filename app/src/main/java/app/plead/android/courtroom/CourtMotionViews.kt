// Port of ArgueWin/Courtroom/CourtMotionViews.swift: the courtroom motion system's views (CONTRACTS-v2 amendment x).
// Each moving figure is one small composable that reads only its own pose from `CourtMotionDirector`, so a blink
// redraws that figure and nothing else. One-shot motion (bubble entrance, line reveal, exhibit rise, stamps) starts
// when the view that owns it appears and never delays the real, accessible text underneath.
package app.plead.android.courtroom

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.plead.android.R
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.PixelAvatar
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.swiftSpring
import app.plead.android.models.Avatar
import app.plead.android.models.JudgePersona
import app.plead.android.models.Role
import app.plead.android.models.Turn
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.launch

// MARK: - Line-by-line text reveal

/**
 * How far a bubble's text reveal has got. `elapsed` animates linearly from 0 to `plan.total`; the renderer maps it to
 * per-line progress.
 */
data class CourtTextReveal(val plan: CourtRevealPlan, val elapsed: Double) {
    companion object {
        /** Everything visible (transcript, revisits, Reduce Motion). */
        val shown = CourtTextReveal(plan = CourtRevealPlan(), elapsed = 1_000.0)
    }
}

/**
 * SwiftUI `Text(...).courtLineReveal(reveal, block:)` (the `CourtLineReveal` TextRenderer): draws the text's laid-out
 * lines one after another — opacity + a 2–4 pt rise, staggered. Visual only: the text (and its accessibility) is
 * complete from the first frame. Never per character. `reveal == null` = fully shown.
 */
@Composable
fun CourtRevealText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    reveal: CourtTextReveal? = null,
    block: Int = 0,
    maxLines: Int = Int.MAX_VALUE,
    textAlign: TextAlign? = null,
) {
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    Text(
        text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = textAlign,
        onTextLayout = { layout = it },
        modifier = modifier.then(
            if (reveal == null) {
                Modifier
            } else {
                Modifier.drawWithContent {
                    val l = layout
                    if (l == null) {
                        drawContent()
                        return@drawWithContent
                    }
                    val start = reveal.plan.start(block)
                    for (i in 0 until l.lineCount) {
                        val p = CourtRevealPlan.lineProgress(
                            elapsed = reveal.elapsed, start = start, stagger = reveal.plan.lineStagger,
                            duration = reveal.plan.lineDuration, line = i,
                        ).toFloat()
                        if (p <= 0f) continue
                        val top = if (i == 0) 0f else l.getLineTop(i)
                        val bottom = if (i == l.lineCount - 1) size.height else l.getLineBottom(i)
                        val dy = if (p >= 1f) 0f else ((1f - p) * reveal.plan.lineRise).dp.toPx()
                        translate(0f, dy) {
                            clipRect(0f, top, size.width, bottom) {
                                if (p >= 1f) {
                                    this@drawWithContent.drawContent()
                                } else {
                                    drawIntoCanvas { c ->
                                        c.saveLayer(Rect(0f, top, size.width, bottom), Paint().apply { alpha = p })
                                        this@drawWithContent.drawContent()
                                        c.restore()
                                    }
                                }
                            }
                        }
                    }
                }
            },
        ),
    )
}

// MARK: - Bubble entrance

/**
 * A scene bubble: the first time a turn appears this launch it fades in, scales 0.96 → 1 and rises 6 pt with a
 * restrained spring (≈ 220 ms); the header is up at once and the dialogue follows 100 ms later, line by line.
 * Revisits and Reduce Motion: a short fade, text complete.
 */
@Composable
fun CourtRevealBubble(
    model: CourtBubbleModel,
    turns: List<Turn>,
    modifier: Modifier = Modifier,
    lineLimit: Int? = null,
    motion: CourtMotionDirector?,
    /** Amendment ad: true completes the staged reveal at once (a tap during the judge's introduction). */
    completeReveal: Boolean = false,
    content: @Composable (CourtTextReveal) -> Unit,
) {
    val reduceMotion = accessibilityReduceMotion()
    val done = remember(model.turn.id) { motion?.hasRevealed(model.turn.id) ?: true }
    var entered by remember(model.turn.id) { mutableStateOf(done) }
    var revealPlan by remember(model.turn.id) {
        mutableStateOf(if (done) CourtTextReveal.shown.plan else CourtRevealPlan.make(model, lineLimit = lineLimit))
    }
    val elapsed = remember(model.turn.id) { Animatable(if (done) CourtTextReveal.shown.elapsed.toFloat() else 0f) }
    val enter = remember(model.turn.id) { Animatable(if (done) 1f else 0f) }
    var claimed by remember(model.turn.id) { mutableStateOf(false) }
    var isShown by remember(model.turn.id) { mutableStateOf(done) }

    // Amendment ac: the court entrance is still running (amendment ad: or the case is still being called). The bubble
    // stays in the accessibility tree (TalkBack reads it at once) but is masked out visually; its entrance plays when
    // the court is ready.
    val waiting = !entered && (motion?.isHeld(model.turn.id) ?: false)

    LaunchedEffect(model.turn.id, waiting) {
        if (waiting || claimed) return@LaunchedEffect
        if (entered || motion == null) {
            claimed = true
            return@LaunchedEffect
        }
        val decision = motion.requestReveal(model, turns, lineLimit = lineLimit)
        if (decision is CourtMotionDirector.RevealDecision.held) return@LaunchedEffect
        claimed = true
        if (decision is CourtMotionDirector.RevealDecision.play && !reduceMotion) {
            val plan = decision.plan
            revealPlan = plan
            isShown = false
            elapsed.snapTo(0f)
            entered = true
            launch { enter.animateTo(1f, swiftSpring(plan.entrance.toFloat(), 0.12f)) }
            launch { elapsed.animateTo(plan.total.toFloat(), tween((plan.total * 1000).roundToInt(), easing = LinearEasing)) }
        } else {
            revealPlan = CourtTextReveal.shown.plan
            elapsed.snapTo(CourtTextReveal.shown.elapsed.toFloat())
            isShown = true
            entered = true
            enter.animateTo(1f, tween(150, easing = PleadMotion.easeOut))
        }
    }

    LaunchedEffect(completeReveal) {
        // Everything visible now (no further staging).
        if (!completeReveal || !claimed || isShown) return@LaunchedEffect
        revealPlan = CourtTextReveal.shown.plan
        elapsed.snapTo(CourtTextReveal.shown.elapsed.toFloat())
        enter.snapTo(1f)
        isShown = true
        entered = true
    }

    val reveal = CourtTextReveal(revealPlan, elapsed.value.toDouble())
    val entranceScale = revealPlan.entranceScale
    val entranceRise = revealPlan.entranceRise
    Box(
        modifier
            .graphicsLayer {
                val e = enter.value
                alpha = if (waiting) 1f else e.coerceIn(0f, 1f)
                val s = if (reduceMotion) 1f else entranceScale + (1f - entranceScale) * e
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 1f)
                translationY = if (reduceMotion) 0f else ((1f - e) * entranceRise).dp.toPx()
            }
            // A mask hides pixels only (unlike removing it, the text stays accessible).
            .drawWithContent { if (!waiting) drawContent() },
    ) {
        content(reveal)
    }
}

// MARK: - One-shot landings (stamps, EXHIBIT label, exhibit card)

/**
 * Lands a view once: from `fromScale` / transparent (or `rise` pt lower) to rest with a small overshoot spring, after
 * `delay`. `pending` false = already landed (no motion). `claim` is asked on appear and may veto (already shown this
 * launch); Reduce Motion lands with a short fade instead.
 */
fun Modifier.courtLanding(
    pending: Boolean,
    fromScale: Float = 1f,
    rise: Float = 0f,
    delay: Double = 0.0,
    duration: Double = CourtMotionTiming.stamp,
    bounce: Float = 0.3f,
    claim: () -> Boolean = { true },
): Modifier = composed {
    val reduceMotion = accessibilityReduceMotion()
    var landed by remember { mutableStateOf<Boolean?>(null) }
    val progress = remember { Animatable(if (pending) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (landed != null) return@LaunchedEffect
        if (!pending) {
            landed = true
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        landed = false
        val go = claim()
        landed = true
        when {
            !go -> progress.snapTo(1f)
            reduceMotion -> {
                kotlinx.coroutines.delay((delay * 1000).toLong())
                progress.animateTo(1f, tween(150, easing = PleadMotion.easeOut))
            }
            else -> {
                kotlinx.coroutines.delay((delay * 1000).toLong())
                progress.animateTo(1f, swiftSpring(duration.toFloat(), bounce))
            }
        }
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        val s = if (reduceMotion) 1f else fromScale + (1f - fromScale) * p
        scaleX = s
        scaleY = s
        translationY = if (reduceMotion) 0f else ((1f - p) * rise).dp.toPx()
    }
}

// MARK: - Judge

/**
 * The judge on the bench: idle blink / 1 px settle, the talk loop and a slight forward lean while speaking.
 * Pixel-crisp (whole-cell grid, no filtering).
 */
@Composable
fun CourtJudgeFigure(
    persona: JudgePersona,
    cell: Float,
    motion: CourtMotionDirector?,
    modifier: Modifier = Modifier,
    /** Entrance walk frame (amendment ac); 0 = standing. */
    walkFrame: Int = 0,
) {
    val reduceMotion = accessibilityReduceMotion()
    val pose = if (reduceMotion) CourtFigurePose() else (motion?.judgePose ?: CourtFigurePose())
    val lift by animateFloatAsState(
        pose.lift,
        if (reduceMotion) tween(0) else tween((CourtMotionTiming.bobEase * 1000).roundToInt(), easing = PleadMotion.easeInOut),
        label = "judgeLift",
    )
    val lean by animateFloatAsState(
        pose.lean,
        if (reduceMotion) tween(0) else tween((CourtMotionTiming.characterChange * 1000).roundToInt(), easing = PleadMotion.easeOut),
        label = "judgeLean",
    )
    JudgeSprite(
        persona = persona,
        cell = cell,
        eyesClosed = pose.eyesClosed,
        mouthOpen = pose.mouthOpen,
        walkFrame = if (reduceMotion) 0 else walkFrame,
        modifier = modifier.graphicsLayer {
            translationY = (-lift).dp.toPx()
            scaleX = 1f + lean
            scaleY = 1f + lean
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
    )
}

/**
 * The gavel strike. The painted gavel rests on the bench (the background never moves); while the judge strikes, the
 * painted one is covered by its mirror-image patch of the bench and a pixel gavel swings: raise → strike (+ impact
 * pixels for one frame) → return.
 */
@Composable
fun CourtGavelLayer(zones: CourtroomZones, motion: CourtMotionDirector, modifier: Modifier = Modifier) {
    val f = motion.gavel
    val patch = zones.rect(CourtArtCrops.gavelPatchUnit)
    val cell = zones.art.width / CourtArtCrops.artPixels.width * CourtGavelSprite.artCell
    val size = CourtGavelSprite.size(cell)
    val origin = Offset(zones.x(CourtArtCrops.gavelOriginUnit.x), zones.y(CourtArtCrops.gavelOriginUnit.y))
    val crops = CourtArtCrops.shared()
    val angle by animateFloatAsState(
        CourtGavelLayer.angle(f),
        tween(((if (f == GavelFrame.struck) CourtMotionTiming.gavelStrike else CourtMotionTiming.gavelRaise) * 1000).roundToInt(), easing = PleadMotion.easeOut),
        label = "gavel",
    )
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (f == GavelFrame.rest) 0f else 1f }
            .clearAndSetSemantics { },
    ) {
        val img = crops.gavelPatch
        if (img != null) {
            Canvas(Modifier.frameIn(patch)) { drawImageFill(img) }
        }
        val sprite = Rect(
            offset = Offset(origin.x - CourtGavelSprite.margin * cell, origin.y),
            size = size,
        )
        CourtGavelSprite(
            cell = cell,
            impact = f == GavelFrame.struck,
            modifier = Modifier
                .frameIn(sprite)
                .graphicsLayer {
                    rotationZ = angle
                    transformOrigin = CourtGavelSprite.pivot
                },
        )
    }
}

object CourtGavelLayer {
    fun angle(f: GavelFrame): Float = when (f) {
        GavelFrame.rest, GavelFrame.returning -> 0f
        GavelFrame.raised -> 50f
        GavelFrame.struck -> -5f
    }
}

/** Draws [img] scaled into the whole draw area, medium filtering (iOS `.interpolation(.medium)`). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawImageFill(img: ImageBitmap) {
    drawImage(
        img,
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        filterQuality = FilterQuality.Medium,
    )
}

/**
 * Pixel gavel (head upright on the left, handle to the right; drawn to match the painted one). Rotates about the
 * handle's end, which sits behind the judge.
 */
@Composable
fun CourtGavelSprite(cell: Float, modifier: Modifier = Modifier, impact: Boolean = false) {
    val s = CourtGavelSprite.size(cell)
    Canvas(modifier.size(s.width.dp, s.height.dp)) {
        val c = cell * density
        val m = CourtGavelSprite.margin.toInt()
        CourtGavelSprite.rows.forEachIndexed { r, row ->
            row.forEachIndexed { col, ch ->
                val color = CourtGavelSprite.palette[ch] ?: return@forEachIndexed
                drawRect(color, topLeft = Offset((col + m) * c, r * c), size = Size(c, c))
            }
        }
        if (impact) {
            for ((col, r) in CourtGavelSprite.sparks) {
                drawRect(PleadColor.cream, topLeft = Offset(col * c, r * c), size = Size(c, c))
            }
        }
    }
}

object CourtGavelSprite {
    /** Art pixels per grid cell (the painting's pixel-art block is ≈ 4–5 source pixels). */
    const val artCell: Float = 4.4f

    /** Empty columns on the left for the impact pixels. */
    const val margin: Float = 3f
    val rows: List<String> = listOf(
        ".OOOOOOOO.................",
        "OddhhhhddO................",
        "OdhhhhhhdO................",
        "OddddddddO................",
        "OmmmmmmmmOOOOOOOOOOOOOOOO.",
        "OmhhhhhhmOkkkkkkkkkkkkkkkO",
        "OmmmmmmmmOOOOOOOOOOOOOOOO.",
        "OddddddddO................",
        "OdhhhhhhdO................",
        "OddddddddO................",
        ".OOOOOOOO.................",
    )
    val columns: Int get() = rows[0].length
    fun size(cell: Float): Size = Size((columns + margin) * cell, rows.size * cell)

    /** Rotation pivot: the handle's end. */
    val pivot = TransformOrigin(1f, 5.5f / 11f)
    val palette: Map<Char, Color> = mapOf(
        'O' to Color(hex = 0x2E0306), 'd' to Color(hex = 0x652A29), 'h' to Color(hex = 0xE18B56),
        'm' to Color(hex = 0x8A4A3A), 'k' to Color(hex = 0x79342D),
    )

    /** Impact pixels (col, row) in the margin, shown for the one-frame hold. */
    val sparks: List<Pair<Int, Int>> = listOf(0 to 2, 1 to 9, 0 to 6)
}

// MARK: - Parties

/**
 * A party's pixel avatar with blink / talk frames, the idle bob, a reaction hop and a 1–2 px forward lean while
 * speaking.
 */
@Composable
fun CourtPartyFigure(
    avatar: Avatar,
    size: Float,
    role: Role,
    motion: CourtMotionDirector?,
    modifier: Modifier = Modifier,
    /** Entrance walk frame (amendment ac); 0 = standing. */
    walkFrame: Int = 0,
) {
    val reduceMotion = accessibilityReduceMotion()
    val pose = if (reduceMotion) CourtFigurePose() else (motion?.pose(role) ?: CourtFigurePose())
    val lift by animateFloatAsState(
        pose.lift,
        if (reduceMotion) tween(0)
        else tween(((if (pose.lift > 1f) 0.14 else CourtMotionTiming.bobEase) * 1000).roundToInt(), easing = PleadMotion.easeInOut),
        label = "partyLift",
    )
    val lean by animateFloatAsState(
        pose.lean,
        if (reduceMotion) tween(0) else tween((CourtMotionTiming.characterChange * 1000).roundToInt(), easing = PleadMotion.easeOut),
        label = "partyLean",
    )
    CourtAvatarSprite(
        avatar = avatar,
        size = size,
        eyesClosed = pose.eyesClosed,
        mouthOpen = pose.mouthOpen,
        walkFrame = if (reduceMotion) 0 else walkFrame,
        modifier = modifier.graphicsLayer {
            translationY = (-lift).dp.toPx()
            scaleX = 1f + lean
            scaleY = 1f + lean
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
    )
}

/** `PixelAvatarView`'s rendering (same outline and cell snapping) with blink / talk / walk variants. */
@Composable
fun CourtAvatarSprite(
    avatar: Avatar,
    size: Float,
    modifier: Modifier = Modifier,
    eyesClosed: Boolean = false,
    mouthOpen: Boolean = false,
    /** Entrance walk cycle (amendment ac, `CourtWalkCycle`): 0 standing, 1 step left, 2 passing, 3 step right, 4 passing. */
    walkFrame: Int = 0,
) {
    val f = CourtAvatarSprite.frame(avatar, eyesClosed, mouthOpen, walkFrame)
    val bob = CourtWalkCycle.bob(walkFrame)
    Canvas(
        modifier
            .clearAndSetSemantics { contentDescription = PixelAvatar.description(avatar) }
            .size(size.dp)
            // The passing frames rise one art pixel (one cell).
            .graphicsLayer { translationY = (-bob * CourtAvatarSprite.cell(size)).dp.toPx() },
    ) {
        val n = PixelAvatar.side.toFloat()
        val widthDp = this.size.width / density
        val raw = widthDp / n
        val cellDp = if (raw >= 2) floor(raw) else raw
        val insetDp = (widthDp - cellDp * n) / 2f
        val cellPx = cellDp * density
        val cellSize = Size((cellDp + 0.25f) * density, (cellDp + 0.25f) * density)
        fun topLeft(r: Int, c: Int) = Offset((insetDp * density) + c * cellPx, (insetDp * density) + r * cellPx)
        for ((r, c) in f.outline) drawRect(PixelAvatar.outlineColor, topLeft = topLeft(r, c), size = cellSize)
        f.grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, color -> if (color != null) drawRect(color, topLeft = topLeft(r, c), size = cellSize) }
        }
    }
}

object CourtAvatarSprite {
    val openMouth = Color(hex = 0x5A1A18)

    /** One grid cell at `size` (whole points when ≥ 2 pt, as drawn). */
    fun cell(size: Float): Float {
        val raw = size / PixelAvatar.side.toFloat()
        return if (raw >= 2) floor(raw) else raw
    }

    /**
     * The avatar grid with the eyes shut (skin over the eye cells) and / or the mouth open (a dark cell row under the
     * mouth), and on a walk step the outfit's hem (rows 14–15) swung one cell sideways.
     */
    fun grid(a: Avatar, eyesClosed: Boolean, mouthOpen: Boolean, walkFrame: Int = 0): List<List<Color?>> {
        val base = PixelAvatar.grid(a)
        val g = base.map { it.toMutableList() }.toMutableList()
        if (g.size < 10 || g[5].size < 10) return base
        val skin = g[5][7] ?: return base
        if (eyesClosed) {
            for (c in listOf(6, 9)) if (g[6][c] != null) g[6][c] = skin
        }
        if (mouthOpen) {
            for (c in listOf(7, 8)) if (g[9][c] != null) g[9][c] = openMouth
        }
        val dir = CourtWalkCycle.step(walkFrame)
        if (dir != null && g.size >= 16) {
            for (r in 14..15) g[r] = CourtWalkCycle.shift(g[r], by = dir, empty = null).toMutableList()
        }
        return g
    }

    internal class Frame(val grid: List<List<Color?>>, val outline: List<Pair<Int, Int>>)
    private data class Key(val avatar: Avatar, val eyesClosed: Boolean, val mouthOpen: Boolean, val walk: Int)

    /** Cached frames per avatar (standing, blink, talk, walk) so an 8 fps walk never rebuilds a grid. */
    private val cache = HashMap<Key, Frame>()

    internal fun frame(a: Avatar, eyesClosed: Boolean, mouthOpen: Boolean, walkFrame: Int): Frame {
        val walk = CourtWalkCycle.normalized(walkFrame)
        val key = Key(a, eyesClosed, mouthOpen, walk)
        cache[key]?.let { return it }
        val g = grid(a, eyesClosed = eyesClosed, mouthOpen = mouthOpen, walkFrame = walk)
        val f = Frame(g, PixelAvatar.outline(g))
        if (cache.size > 400) cache.clear()
        cache[key] = f
        return f
    }

    /** Builds every frame of `a` before the court is interactive (amendment ac: sprites preloaded). */
    fun preload(a: Avatar) {
        for (w in 0..CourtEntrancePose.walkFrames) {
            for (eyes in listOf(false, true)) for (mouth in listOf(false, true)) frame(a, eyes, mouth, w)
        }
    }
}

// MARK: - Audience

/**
 * The painted stands as four clusters lifted from the painting itself (feathered crops drawn exactly over their
 * source). Each cluster bobs 1 pt on the shared clock and hops 2 pt once on key moments; at rest they are
 * indistinguishable from the painting. Never per spectator, never the whole background.
 */
@Composable
fun CourtCrowdLayer(
    zones: CourtroomZones,
    motion: CourtMotionDirector,
    modifier: Modifier = Modifier,
    /** Entrance (amendment ac): `CourtEntranceDirector.audienceSettle`; 1 = at rest. */
    settle: Double = 1.0,
) {
    val reduceMotion = accessibilityReduceMotion()
    if (reduceMotion) return
    val crops = CourtArtCrops.shared().crowd
    Box(modifier.fillMaxSize().clearAndSetSemantics { }) {
        CourtArtCrops.crowdUnits.forEachIndexed { i, unit ->
          val img = crops.getOrNull(i)
          if (img != null) key(i) {
            val r = zones.rect(unit)
            val target = (motion.crowdLift.getOrNull(i) ?: 0f) + CourtCrowdLayer.settleLift(i, settle)
            val lift by animateFloatAsState(
                target,
                if (settle < 1) tween((CourtEntranceTiming.frame * 1000).roundToInt(), easing = LinearEasing)
                else tween(((if (target > 1f) 0.16 else CourtMotionTiming.bobEase) * 1000).roundToInt(), easing = PleadMotion.easeInOut),
                label = "crowd$i",
            )
            val leftSide = unit.center.x < 0.5f
            Canvas(
                Modifier
                    .frameIn(r)
                    .graphicsLayer {
                        translationY = (-lift).dp.toPx()
                        compositingStrategy = CompositingStrategy.Offscreen
                    },
            ) {
                drawImageFill(img)
                // Soft edges on the inner side and the top so a 1–2 pt offset never shows a seam.
                drawRect(CourtCrowdLayer.featherHorizontal(leftSide, size.width), blendMode = BlendMode.DstIn)
                drawRect(CourtCrowdLayer.featherVertical(size.height), blendMode = BlendMode.DstIn)
            }
          }
        }
    }
}

object CourtCrowdLayer {
    /** Cluster `i`'s settle lift for entrance progress `settle` (staggered windows, a sine hop in each). */
    fun settleLift(i: Int, settle: Double): Float {
        if (settle >= 1) return 0f
        val start = i * 0.15
        val span = 0.5
        val p = min(1.0, max(0.0, (settle - start) / span))
        return (sin(p * PI) * CourtMotionTiming.crowdHop).toFloat()
    }

    /** Swift `feather(leftSide:)`, inner side: opaque to 78 %, clear at the inner edge. */
    internal fun featherHorizontal(leftSide: Boolean, width: Float): Brush {
        val stops = arrayOf(0f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent)
        return if (leftSide) Brush.horizontalGradient(*stops, startX = 0f, endX = width)
        else Brush.horizontalGradient(*stops, startX = width, endX = 0f)
    }

    /** Swift `feather` top / bottom mask: clear 0, opaque 0.18…0.92, clear 1. */
    internal fun featherVertical(height: Float): Brush = Brush.verticalGradient(
        0f to Color.Transparent, 0.18f to Color.Black, 0.92f to Color.Black, 1f to Color.Transparent,
        startY = 0f, endY = height,
    )
}

// MARK: - Crops of the painting

/**
 * Pieces of `CourtroomBackground` used by the motion layers (derived from the painting, nothing redrawn): the four
 * audience clusters and the mirror-image bench patch that hides the painted gavel mid-strike.
 */
class CourtArtCrops(image: ImageBitmap?) {
    val crowd: List<ImageBitmap?>
    val gavelPatch: ImageBitmap?

    init {
        val src: Bitmap? = image?.asAndroidBitmap()
        if (src == null) {
            crowd = List(crowdPixels.size) { null }
            gavelPatch = null
        } else {
            // The asset may be any resolution: scale the source-pixel rects to it.
            val sx = src.width / artPixels.width
            val sy = src.height / artPixels.height
            fun px(r: Rect): android.graphics.Rect {
                val l = floor(r.left * sx).toInt().coerceIn(0, src.width - 1)
                val t = floor(r.top * sy).toInt().coerceIn(0, src.height - 1)
                val rr = ceil(r.right * sx).toInt().coerceIn(l + 1, src.width)
                val b = ceil(r.bottom * sy).toInt().coerceIn(t + 1, src.height)
                return android.graphics.Rect(l, t, rr, b)
            }
            crowd = crowdPixels.map { r ->
                val p = px(r)
                runCatching { Bitmap.createBitmap(src, p.left, p.top, p.width(), p.height()).asImageBitmap() }.getOrNull()
            }
            // Mirror of the (symmetric) bench on the other side of the chair: plain bench top and wall.
            val g = gavelPatchPixels
            val mirrored = Rect(offset = Offset(artPixels.width - g.right, g.top), size = g.size)
            val p = px(mirrored)
            gavelPatch = runCatching {
                Bitmap.createBitmap(src, p.left, p.top, p.width(), p.height(), Matrix().apply { preScale(-1f, 1f) }, false).asImageBitmap()
            }.getOrNull()
        }
    }

    companion object {
        val artPixels = Size(1170f, 2532f)

        /** Audience clusters (source pixels): left back / front, right back / front. */
        val crowdPixels: List<Rect> = listOf(
            Rect(offset = Offset(0f, 690f), size = Size(140f, 110f)),
            Rect(offset = Offset(0f, 790f), size = Size(190f, 135f)),
            Rect(offset = Offset(1020f, 690f), size = Size(150f, 115f)),
            Rect(offset = Offset(985f, 790f), size = Size(185f, 135f)),
        )

        /** The painted gavel and its block on the bench (source pixels). */
        val gavelPatchPixels = Rect(offset = Offset(412f, 842f), size = Size(150f, 90f))

        /** Top-left of the pixel gavel's grid (after its spark margin) at rest. */
        val gavelOriginPixels = Offset(442f, 861f)

        fun unit(r: Rect): Rect = Rect(
            offset = Offset(r.left / artPixels.width, r.top / artPixels.height),
            size = Size(r.width / artPixels.width, r.height / artPixels.height),
        )

        val crowdUnits: List<Rect> get() = crowdPixels.map(::unit)
        val gavelPatchUnit: Rect get() = unit(gavelPatchPixels)
        val gavelOriginUnit: Offset get() = Offset(gavelOriginPixels.x / artPixels.width, gavelOriginPixels.y / artPixels.height)

        private var cached: CourtArtCrops? = null

        /** The crops of the app's painting, made once per process. */
        @Composable
        fun shared(): CourtArtCrops {
            val image = ImageBitmap.imageResource(R.drawable.courtroom_background)
            return remember(image) { cached ?: CourtArtCrops(image).also { cached = it } }
        }
    }
}
