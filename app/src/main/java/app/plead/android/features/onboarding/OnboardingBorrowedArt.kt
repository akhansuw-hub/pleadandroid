// Interim copies of wave 3a/3c pieces onboarding needs (from ArgueWin/Courtroom/CourtArt.swift, CourtMotion.swift,
// CourtMotionViews.swift, CourtEntrance.swift (walk-frame count), ArgueWin/Features/Paywall/PixelGlyphs.swift,
// PaywallPalette.swift, Shared/PixelJudgeGlyph.swift and Shared/PleadWidgetPalette.swift). After the 3a/3c merge the
// integrator deletes this file and imports the originals; names and signatures match.
//
// Units: every geometry value (CGFloat / CGSize / CGRect in Swift) is a Float in dp (iOS points), carried in
// Compose `Size` / `Rect` / `Offset`. Time values are Double seconds, as in Swift.
//
// Not ported (not used by onboarding): `CourtroomBackdrop` (the drawn fallback when the painting is missing: the
// drawable is always bundled on Android), `CourtNameplate`, `CourtPartyFigure` / `CourtAvatarSprite`, and every part
// of `CourtMotionDirector` that reads a live case (update / claimReveal / stamps / verdict choreography). The
// director here runs the ambient clock only (blinks, 1 pt settles, crowd bobs), which is all onboarding drives.
package app.plead.android.features.onboarding

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.plead.android.R
import app.plead.android.app.PleadApplication
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.models.JudgePersona
import app.plead.android.models.Role
import java.util.UUID
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import app.plead.android.designsystem.Color as HexColor

/** Swift `CGRect(x:y:width:height:)`. */
private fun rectXYWH(x: Float, y: Float, width: Float, height: Float): Rect = Rect(Offset(x, y), Size(width, height))

/** Draws one cell of a pixel grid with its edges snapped to whole device pixels (no seams). */
private fun DrawScope.fillCell(ox: Float, oy: Float, c: Int, r: Int, cell: Float, color: Color) {
    val x0 = (ox + c * cell).roundToInt().toFloat()
    val y0 = (oy + r * cell).roundToInt().toFloat()
    val x1 = (ox + (c + 1) * cell).roundToInt().toFloat()
    val y1 = (oy + (r + 1) * cell).roundToInt().toFloat()
    drawRect(color, topLeft = Offset(x0, y0), size = Size(x1 - x0, y1 - y0))
}

// MARK: - Paywall palette (Features/Paywall/PaywallPalette.swift)

/** Paywall-local palette (docs/paywall-brief/BRIEF.md §5). */
internal object PaywallPalette {
    val courtBurgundy = HexColor(hex = 0x7C3042)   // brand, selected states, key borders
    val deepWine = HexColor(hex = 0x541F2C)        // headlines, strong text
    val mahogany = HexColor(hex = 0x704735)        // wood detail
    val darkCocoa = HexColor(hex = 0x3B2425)       // main text, icons
    val warmCream = HexColor(hex = 0xFFF6ED)       // screen background
    val paperWhite = HexColor(hex = 0xFFFDFC)      // cards
    val parchment = HexColor(hex = 0xF3E4D6)       // secondary panels, quiet borders
    val romanceBlush = HexColor(hex = 0xEAA0A4)    // couple / heart details
    val softRose = HexColor(hex = 0xC66C78)        // secondary accents
    val coral = HexColor(hex = 0xE85E68)           // "3 DAYS FREE", logo heart
    val courtGold = HexColor(hex = 0xCA9858)       // judicial accents
    val goldLight = HexColor(hex = 0xF3C76A)       // BEST VALUE pill / crown

    /** Annual (preferred) plan card fill. */
    val annualFill = HexColor(hex = 0xFFF1EB)

    /** Perk card fill: blush over cream. */
    val perkFill = HexColor(hex = 0xFCEBE4)

    /** Unselected plan card border. */
    val planBorder = HexColor(hex = 0xE8D5C4)

    /** Legal / footer text. */
    val mutedCocoa = HexColor(hex = 0x3B2425, opacity = 0.66f)
}

// MARK: - Widget palette (Shared/PleadWidgetPalette.swift)

internal object PleadWidgetPalette {
    /** Widget surfaces, neutral background. */
    val warmCream = HexColor(hex = 0xFFF6ED)

    /** Primary status / CTA. */
    val courtBurgundy = HexColor(hex = 0x7C3042)

    /** Headlines and dark accents. */
    val deepWine = HexColor(hex = 0x541F2C)

    /** Body text. */
    val darkCocoa = HexColor(hex = 0x3B2425)

    /** Gavel / scales and small emphasis. */
    val courtGold = HexColor(hex = 0xCA9858)

    /** Relationship accents only. */
    val romanceBlush = HexColor(hex = 0xEAA0A4)

    // Supporting tints derived from the brief tokens (pixel art and quiet fills).
    val paperWhite = HexColor(hex = 0xFFFDFC)
    val chipFill = HexColor(hex = 0xF9DCDA)
    val mutedCocoa = HexColor(hex = 0x3B2425, opacity = 0.68f)
    val mahogany = HexColor(hex = 0x704735)
    val woodLight = HexColor(hex = 0x9A6444)
    val woodDark = HexColor(hex = 0x4A2A20)
    val panelWood = HexColor(hex = 0x5B3326)
}

// MARK: - Pixel sprites (Features/Paywall/PixelGlyphs.swift)

/** A tiny pixel sprite: rows of palette characters ("." is transparent). */
internal class PixelSprite(val rows: List<String>) {
    val width: Int get() = rows.maxOfOrNull { it.length } ?: 1
    val height: Int get() = rows.size
}

/**
 * Draws a [PixelSprite] crisply: every cell is snapped to whole device pixels so there are no seams or blurred
 * edges at any size. Aspect-fit inside the frame the caller gives it.
 */
@Composable
internal fun PixelGlyph(sprite: PixelSprite, modifier: Modifier = Modifier) {
    val w = sprite.width
    val h = sprite.height
    Canvas(modifier.aspectRatio(w.toFloat() / h.toFloat()).clearAndSetSemantics { }) {
        val raw = min(size.width / w, size.height / h)
        val cell = max(1f, floor(raw))
        val ox = ((size.width - cell * w) / 2).roundToInt().toFloat()
        val oy = ((size.height - cell * h) / 2).roundToInt().toFloat()
        sprite.rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                val color = PixelInk.color(ch) ?: return@forEachIndexed
                drawRect(color, topLeft = Offset(ox + x * cell, oy + y * cell), size = Size(cell, cell))
            }
        }
    }
}

/** Sprite palette, all from [PaywallPalette] plus two skin tones for the couple. */
internal object PixelInk {
    fun color(ch: Char): Color? = when (ch) {
        'k' -> PaywallPalette.darkCocoa
        'm' -> PaywallPalette.mahogany
        'w' -> HexColor(hex = 0x9A6444)            // light wood (mahogany highlight)
        'b' -> PaywallPalette.courtBurgundy
        'd' -> PaywallPalette.deepWine
        'r' -> PaywallPalette.coral
        'p' -> PaywallPalette.romanceBlush
        's' -> PaywallPalette.softRose
        'g' -> PaywallPalette.courtGold
        'G' -> PaywallPalette.goldLight
        'P' -> PaywallPalette.paperWhite
        'c' -> PaywallPalette.parchment
        'f' -> HexColor(hex = 0xF7D2B6)            // skin
        'e' -> HexColor(hex = 0xD9A07E)            // skin shade
        'y' -> HexColor(hex = 0xF0C46A)            // blonde hair
        'h' -> HexColor(hex = 0x4A2C22)            // dark hair
        'u' -> HexColor(hex = 0x4F6FA8)            // hoodie blue
        'W' -> Color.White
        else -> null
    }
}

internal object PaywallSprites {
    val gavel = PixelSprite(
        listOf(
            "............p.p.",
            "...........ppppp",
            "..kkkkk.....ppp.",
            ".kGgggGk.....p..",
            ".kmwwwmk........",
            ".kmwwwmkkkkkkkk.",
            ".kmwwwmmwwwwwwwk",
            ".kmmmmmmmmmmmmmk",
            ".kmmmmmkkkkkkkk.",
            ".kmmmmmk........",
            ".kGgggGk........",
            "..kkkkk.........",
            "....kkkkkkkkk...",
            "...kwwwwwwwwwk..",
            "...kmmmmmmmmmk..",
            "....kkkkkkkkk...",
        ),
    )

    val evidence = PixelSprite(
        listOf(
            "..kkkkkkkk......",
            "..kccccccck.....",
            "..kckkkkkkkkk...",
            "..kckPPPPPPPPk..",
            "..kckPkkkkkPPk..",
            "..kckPPPPPPPPk..",
            "..kckPkkkkkkPk..",
            "..kckPPPPPPPPk..",
            "..kckPkkkkPPPk..",
            "..kkkPPPPPPPPk..",
            "....kPPPPPPPPk..",
            "....kPPPPrr.rr..",
            "....kkkkrrWrrrr.",
            "........rrrrrrr.",
            ".........rrrrr..",
            "..........rrr...",
            "...........r....",
        ),
    )

    val robotJudge = PixelSprite(
        listOf(
            ".......rr.......",
            ".......rr.......",
            "........k.......",
            "...kkkkkkkkkk...",
            "..kPPPPPPPPPPk..",
            ".bkPkkkkkkkkPkb.",
            ".bkPkddddddkPkb.",
            ".bkPkdGddGdkPkb.",
            ".bkPkddddddkPkb.",
            "..kPkkkkkkkkPk..",
            "..kPPPPrrPPPPk..",
            "...kkkkkkkkkk...",
            ".....kccccck....",
            "....kbbbbbbbk...",
            "...kbbbGGbbbbk..",
            "...kkkkkkkkkkk..",
        ),
    )

    val couple = PixelSprite(
        listOf(
            "........rr.rr.....",
            "........rrrrr.....",
            ".........rrr......",
            "..kkkk....r..kkkk.",
            ".khhhhk.....kyyyyk",
            "khhhhhhk...kyyyyyy",
            "khhffhhk...kyfffyy",
            "kfffffhk...kyffffy",
            "kfkffkfk...kyfkfky",
            "kffffffk...kyffffy",
            ".kpffpk....kypffpy",
            "..kffk.....ky.kk.y",
            ".kuuuuk....kssssk.",
            "kuuuuuuk..kssssssk",
        ),
    )

    val heart = PixelSprite(
        listOf(
            ".rr.rr.",
            "rWrrrrr",
            "rrrrrrr",
            ".rrrrr.",
            "..rrr..",
            "...r...",
        ),
    )

    val crown = PixelSprite(
        listOf(
            "G..G..G",
            "GG.G.GG",
            "GGGGGGG",
            "GrGGGrG",
            "ggggggg",
        ),
    )

    val scales = PixelSprite(
        listOf(
            ".......G.......",
            ".ggggggggggggg.",
            ".g.....g.....g.",
            "g.g....g....g.g",
            "g..g...g...g..g",
            "ggggg..g..ggggg",
            ".GGG...g...GGG.",
            ".......g.......",
            ".......g.......",
            ".....ggggg.....",
            "....ggggggg....",
        ),
    )

    val calendar = PixelSprite(
        listOf(
            "...k.......k...",
            ".kkkkkkkkkkkkk.",
            ".kbbbbbbbbbbbk.",
            ".kbbbbbbbbbbbk.",
            ".kkkkkkkkkkkkk.",
            ".kPPPPPPPPPPPk.",
            ".kPPPrr.rrPPPk.",
            ".kPPPrrrrrPPPk.",
            ".kPPPPrrrPPPPk.",
            ".kPPPPPrPPPPPk.",
            ".kPPPPPPPPPPPk.",
            ".kkkkkkkkkkkkk.",
        ),
    )

    val sparkle = PixelSprite(
        listOf(
            ".G.",
            "GGG",
            ".G.",
        ),
    )
}

// MARK: - Widget-system pixel judge (Shared/PixelJudgeGlyph.swift)

/**
 * The widget system's pixel art: the judge (wig, face, robe), the judge at the bench with a gavel, the gavel, the
 * scales and the Plead heart, each with an automatic 1-cell dark-cocoa outline. [size] is the height; the width
 * follows the sprite's aspect ratio. (The iOS accented-widget luminance mode has no app-side equivalent.)
 */
@Composable
internal fun PixelJudgeGlyph(kind: PixelJudgeGlyph.Kind, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val art = PleadPixelSprites.art(kind)
    Canvas(modifier.size(width = size * art.aspect, height = size).clearAndSetSemantics { }) {
        val w = art.columns.toFloat()
        val h = art.rows.toFloat()
        val raw = min(this.size.width / w, this.size.height / h)
        // Whole device pixels per cell: crisp, seam-free edges at every size.
        val cell = max(1f, floor(raw))
        val ox = ((this.size.width - cell * w) / 2).roundToInt().toFloat()
        val oy = ((this.size.height - cell * h) / 2).roundToInt().toFloat()
        art.cells.forEachIndexed { r, row ->
            row.forEachIndexed { c, color ->
                if (color != null) drawRect(color, topLeft = Offset(ox + c * cell, oy + r * cell), size = Size(cell, cell))
            }
        }
    }
}

internal object PixelJudgeGlyph {
    enum class Kind {
        /** Head and shoulders (Lock Screen rectangular, medium widget). */
        judge,

        /** Head only, with the collar (circular accessory). */
        face,

        /** The judge behind the bench, gavel raised (small / medium Home Screen widgets). */
        bench,
        gavel, scales, heart,
    }
}

/** A rendered sprite: a colour per cell (null = transparent), outline included. */
internal class PleadPixelBitmap(val cells: List<List<Color?>>) {
    val rows: Int get() = cells.size
    val columns: Int get() = cells.firstOrNull()?.size ?: 1
    val aspect: Float get() = columns.toFloat() / max(rows, 1).toFloat()
}

internal object PleadPixelSprites {
    // MARK: Palette

    val ink: Map<Char, Color> = mapOf(
        'W' to HexColor(hex = 0xF7F2EA),              // wig
        'w' to HexColor(hex = 0xD9CDC1),              // wig curl shade
        'S' to HexColor(hex = 0xF5C9A6),              // skin
        's' to HexColor(hex = 0xE4AE8A),              // skin shade
        'E' to PleadWidgetPalette.darkCocoa,          // eyes
        'B' to PleadWidgetPalette.romanceBlush,       // cheeks
        'M' to PleadWidgetPalette.courtBurgundy,      // mouth
        'R' to PleadWidgetPalette.courtBurgundy,      // robe
        'r' to PleadWidgetPalette.deepWine,           // robe fold
        'C' to PleadWidgetPalette.paperWhite,         // collar / bands
        'G' to PleadWidgetPalette.courtGold,          // gold
        'g' to HexColor(hex = 0x9C6F3A),              // gold shade
        'm' to PleadWidgetPalette.mahogany,           // wood
        'n' to PleadWidgetPalette.woodLight,          // wood highlight
        'd' to PleadWidgetPalette.woodDark,           // wood shade
        'H' to PleadWidgetPalette.romanceBlush,       // heart
        'h' to PleadWidgetPalette.paperWhite,         // heart highlight
        'P' to PleadWidgetPalette.courtBurgundy,      // heart shade
        'k' to PleadWidgetPalette.darkCocoa,          // explicit outline
    )
    val outline = PleadWidgetPalette.darkCocoa

    // MARK: Fill grids ("." = transparent)

    val head: List<String> = listOf(
        "....WWWWWWWW....",
        "...WWwWWWWwWW...",
        "..WWWWWWWWWWWW..",
        "..WwSSSSSSSSwW..",
        "..WWSSSSSSSSWW..",
        "..WwSESSSSESwW..",
        "..WWSSSSSSSSWW..",
        "..WwSBSSSSBSwW..",
        "..WWSSSMMSSSWW..",
        ".WWwWSSSSSSWwWW.",
        ".WWWW.SSSS.WWWW.",
        ".WwWRRCCCCRRWwW.",
    )

    val robe: List<String> = listOf(
        "..RRRRRCCRRRRR..",
        ".RRRRRRCCRRRRRR.",
        ".RRRRRRrrRRRRRR.",
        "RRRRRRRrrRRRRRRR",
        "RRRRRRRrrRRRRRRR",
        "RRRRRRRrrRRRRRRR",
    )

    val judge: List<String> = head + robe

    /** A raised gavel held up beside the wig: gold-banded head on top, handle down to the hand, sleeve below. */
    val raisedGavel: List<String> = listOf(
        "GmmmmG",
        "mnnnnm",
        "mnnnnm",
        "GmmmmG",
        "..dd..",
        "..dd..",
        "..dd..",
        ".SSSS.",
        ".SSSS.",
        "RRRRR.",
        "RRRRR.",
    )

    val gavel: List<String> = listOf(
        "..........HH.H.",
        "...........HHH.",
        ".GmmmG......H..",
        ".mnnnm.........",
        ".mnnnmddddddddd",
        ".mnnnmnnnnnnnnn",
        ".mmmmmddddddddd",
        ".mnnnm.........",
        ".GmmmG.........",
        "...............",
        "...ddddddddd...",
        "..dnnnnnnnnnd..",
        "..dmmmmmmmmmd..",
    )

    val scales: List<String> = listOf(
        ".......G.......",
        ".GGGGGGGGGGGGG.",
        ".G.....G.....G.",
        "G.G....G....G.G",
        "G..G...G...G..G",
        "GGGGG..G..GGGGG",
        ".ggg...G...ggg.",
        ".......G.......",
        ".......G.......",
        ".....GGGGG.....",
        "....ggggggg....",
    )

    val heart: List<String> = listOf(
        ".HH.HH.",
        "HhHHHHH",
        "HHHHHHP",
        ".HHHHP.",
        "..HHP..",
        "...P...",
    )

    /** The judge behind the bench with a gavel in hand (fills only; outlined on render). */
    val bench: List<String> by lazy {
        val width = 26
        val judgeCol = 3
        val benchTop = 15
        val canvas = MutableList(benchTop + 8) { CharArray(width) { '.' } }
        stamp(judge, canvas, row = 0, col = judgeCol)
        stamp(raisedGavel, canvas, row = 4, col = judgeCol + 16)
        // Bench: gold rail, wood front, heart plaque under the judge.
        for (c in 0 until width) canvas[benchTop][c] = 'G'
        for (c in 0 until width) canvas[benchTop + 1][c] = 'd'
        for (r in (benchTop + 2) until (benchTop + 8)) {
            for (c in 0 until width) canvas[r][c] = if (c == 1 || c == width - 2) 'n' else 'm'
        }
        val heartCol = judgeCol + 8 - 3
        stamp(heart.map { line -> line.map { if (it == '.') 'm' else it }.joinToString("") }, canvas, row = benchTop + 2, col = heartCol)
        canvas.map { String(it) }
    }

    private fun stamp(src: List<String>, canvas: MutableList<CharArray>, row: Int, col: Int) {
        src.forEachIndexed { r, line ->
            line.forEachIndexed { c, ch ->
                if (ch == '.') return@forEachIndexed
                val rr = row + r
                val cc = col + c
                if (rr < 0 || rr >= canvas.size || cc < 0 || cc >= canvas[rr].size) return@forEachIndexed
                canvas[rr][cc] = ch
            }
        }
    }

    // MARK: Rendering

    fun fill(kind: PixelJudgeGlyph.Kind): List<String> = when (kind) {
        PixelJudgeGlyph.Kind.judge -> judge
        PixelJudgeGlyph.Kind.face -> head
        PixelJudgeGlyph.Kind.bench -> bench
        PixelJudgeGlyph.Kind.gavel -> gavel
        PixelJudgeGlyph.Kind.scales -> scales
        PixelJudgeGlyph.Kind.heart -> heart
    }

    private val cache: Map<PixelJudgeGlyph.Kind, PleadPixelBitmap> by lazy {
        PixelJudgeGlyph.Kind.entries.associateWith { outlined(fill(it)) }
    }

    fun art(kind: PixelJudgeGlyph.Kind): PleadPixelBitmap = cache[kind] ?: outlined(fill(kind))

    /** Fill grid → colours with a 1-cell outline around every filled cell (4-neighbour). */
    fun outlined(src: List<String>): PleadPixelBitmap {
        val h = src.size
        val w = src.maxOfOrNull { it.length } ?: 0
        fun filled(r: Int, c: Int): Boolean = r in 0 until h && c >= 0 && c < src[r].length && src[r][c] != '.'
        val out = MutableList(h + 2) { MutableList<Color?>(w + 2) { null } }
        for (r in -1..h) {
            for (c in -1..w) {
                if (filled(r, c)) {
                    out[r + 1][c + 1] = ink[src[r][c]] ?: outline
                } else if (filled(r - 1, c) || filled(r + 1, c) || filled(r, c - 1) || filled(r, c + 1)) {
                    out[r + 1][c + 1] = outline
                }
            }
        }
        return PleadPixelBitmap(out)
    }
}

// MARK: - Zones (Courtroom/CourtArt.swift)

/**
 * Where things are painted in the background, as unit rects of the artwork (0…1 in both axes), plus the
 * aspect-fill mapping into a container of [size] (dp). Measured on the 1170×2532 asset.
 */
internal data class CourtroomZones(val size: Size) {
    /** Frame of the whole artwork in container coordinates (aspect-fill, may overflow). */
    val art: Rect

    init {
        val w = max(size.width, 1f)
        val h = max(size.height, 1f)
        val scale = max(w / artAspect, h)            // art height in points
        val artW = scale * artAspect
        val artH = scale
        art = rectXYWH((w - artW) / 2, (h - artH) * anchorY, artW, artH)
    }

    fun rect(unit: Rect): Rect = rectXYWH(
        art.left + unit.left * art.width, art.top + unit.top * art.height,
        unit.width * art.width, unit.height * art.height,
    )

    fun x(u: Float): Float = art.left + u * art.width
    fun y(u: Float): Float = art.top + u * art.height

    // Derived placements used by the scene.
    val judgeCell: Float get() = max(2f, floor(art.width * 0.155f / JudgeSprite.columns.toFloat()))
    val judgeSpriteSize: Size get() = JudgeSprite.size(cell = judgeCell)

    /** Bottom of the judge sprite = bench top (a few points into the bench so no gap shows). */
    val judgeFrame: Rect
        get() {
            val s = judgeSpriteSize
            return rectXYWH(x(0.5f) - s.width / 2, y(bench.top) + 2 - s.height, s.width, s.height)
        }
    val avatarSize: Float get() = (art.width * 0.185f).roundToInt().toFloat()

    fun avatarFrame(r: Role): Rect {
        val p = rect(podium(r))
        val s = avatarSize
        return rectXYWH(p.center.x - s / 2, p.top + s * 0.06f - s, s, s)
    }

    companion object {
        const val artAspect: Float = 1170f / 2532f

        /** Vertical crop anchor when the screen is shorter than 9:19.5 (0 = keep top, 1 = keep bottom). */
        const val anchorY: Float = 0.35f

        // Painted furniture (unit rects of the artwork).
        val banner = rectXYWH(0.333f, 0.047f, 0.334f, 0.257f)

        /** Empty judge's chair; the sprite sits in it with its bottom on the bench top. */
        val judgeChair = rectXYWH(0.428f, 0.277f, 0.144f, 0.078f)
        val bench = rectXYWH(0.337f, 0.356f, 0.326f, 0.089f)

        /** The blank brass plaque on the bench front. */
        val plaque = rectXYWH(0.41f, 0.395f, 0.18f, 0.038f)
        val standsLeft = rectXYWH(0.0f, 0.27f, 0.23f, 0.13f)
        val standsRight = rectXYWH(0.77f, 0.27f, 0.23f, 0.13f)

        /** The easel's blank board (legs continue to y ≈ 0.636). */
        val easel = rectXYWH(0.342f, 0.46f, 0.316f, 0.107f)
        val plaintiffPodium = rectXYWH(0.043f, 0.551f, 0.261f, 0.124f)
        val defendantPodium = rectXYWH(0.697f, 0.551f, 0.26f, 0.124f)

        /** Bottom band (pews and carpet) the dock may cover. */
        val dock = rectXYWH(0.0f, 0.69f, 1.0f, 0.31f)

        fun podium(r: Role): Rect = if (r == Role.plaintiff) plaintiffPodium else defendantPodium
    }
}

// MARK: - Background

/** The painted courtroom, aspect-filled into [size] (dp) using [CourtroomZones]' mapping. */
@Composable
internal fun CourtroomBackground(size: Size, modifier: Modifier = Modifier, closed: Boolean = false) {
    val z = CourtroomZones(size)
    val image = CourtroomBackground.image ?: ImageBitmap.imageResource(R.drawable.courtroom_background)
    Canvas(modifier.size(size.width.dp, size.height.dp).clipToBounds().clearAndSetSemantics { }) {
        clipRect {
            drawImage(
                image,
                dstOffset = IntOffset(z.art.left.dp.toPx().roundToInt(), z.art.top.dp.toPx().roundToInt()),
                dstSize = IntSize(z.art.width.dp.toPx().roundToInt(), z.art.height.dp.toPx().roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            if (closed) {
                // Lamps out: cool the room and drop it into shadow, keep a little warmth up top.
                drawRect(Brush.verticalGradient(listOf(PleadColor.cocoa.copy(alpha = 0.55f), PleadColor.cocoa.copy(alpha = 0.78f))))
            }
        }
    }
}

internal object CourtroomBackground {
    /** The painting, decoded once (null without an Application context, e.g. plain JVM tests). */
    val image: ImageBitmap? by lazy {
        val context = PleadApplication.contextOrNull ?: return@lazy null
        runCatching {
            BitmapFactory.decodeResource(context.resources, R.drawable.courtroom_background, BitmapFactory.Options().apply { inScaled = false })
                ?.asImageBitmap()
        }.getOrNull()
    }
}

// MARK: - Judge sprite

/**
 * Static pixel judge (16×18 grid + 1-cell dark outline), persona-tinted: Wigsworth white wig + burgundy robe; Blunt
 * reading glasses + dark cocoa; Sunny blush flower; Chaos rose accessory + sunglasses. [cell] is in dp.
 */
@Composable
internal fun JudgeSprite(
    persona: JudgePersona,
    modifier: Modifier = Modifier,
    cell: Float = 4f,
    eyesClosed: Boolean = false,
    mouthOpen: Boolean = false,
    walkFrame: Int = 0,
) {
    val grid = JudgeSprite.grid(persona, eyesClosed = eyesClosed, mouthOpen = mouthOpen, walkFrame = walkFrame)
    val bob = CourtWalkCycle.bob(walkFrame)
    val s = JudgeSprite.size(cell)
    Canvas(
        modifier
            // The passing frames rise one art pixel (one cell).
            .offset(y = (-bob * cell).dp)
            .size(s.width.dp, s.height.dp)
            .semantics { contentDescription = "${persona.displayName}, at the bench" },
    ) {
        val cellPx = cell.dp.toPx()
        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, color -> if (color != null) fillCell(0f, 0f, c, r, cellPx, color) }
        }
    }
}

internal object JudgeSprite {
    const val columns = 18   // 16 + outline
    const val rows = 19      // 18 + top outline

    fun size(cell: Float): Size = Size(columns * cell, rows * cell)

    // MARK: Grids

    private val robe: List<String> = listOf(
        "..RRRRRCCRRRRR..",
        ".RRRRRRCCRRRRRR.",
        ".RRRRRRrrRRRRRR.",
        "RRRRRRRrrRRRRRRR",
        "RRRRRRRrrRRRRRRR",
        "RRRRRRRrrRRRRRRR",
    )

    private fun rows(p: JudgePersona): List<String> = when (p) {
        JudgePersona.wigsworth -> listOf(
            "....WWWWWWWW....",
            "...WWwWWWWwWW...",
            "..WWWWWWWWWWWW..",
            "..WwSSSSSSSSwW..",
            "..WWSSSSSSSSWW..",
            "..WwSESSSSESwW..",
            "..WWSSSSSSSSWW..",
            "..WwSBSSSSBSwW..",
            "..WWSSSMMSSSWW..",
            ".WWwWSSSSSSWwWW.",
            ".WWWW.SSSS.WWWW.",
            ".WwWRRCCCCRRWwW.",
        ) + robe
        JudgePersona.blunt -> listOf(
            "................",
            "....HHHHHHHH....",
            "...HHHHHHHHHH...",
            "...HHSSSSSSHH...",
            "...SSSSSSSSSS...",
            "...SGGGGGGGGS...",
            "...SGEGSSGEGS...",
            "...SGGGSSGGGS...",
            "...SSSSSSSSSS...",
            "....SSMMMMSS....",
            "......SSSS......",
            "....RRCCCCRR....",
        ) + robe
        JudgePersona.sunny -> listOf(
            "......HHHH......",
            "....HHHHHHHH.FF.",
            "...HHHHHHHHHFYF.",
            "...HHSSSSSSHHFF.",
            "...HSSSSSSSSH...",
            "...SSSSSSSSSS...",
            "...SSESSSSESS...",
            "...SBSSSSSSBS...",
            "...SSSMSSMSSS...",
            "....SSSMMSSS....",
            "......SSSS......",
            "....RRCCCCRR....",
        ) + robe
        JudgePersona.chaos -> {
            val body = robe.toMutableList()
            body[1] = ".RROORRCCRRRRRR."
            body[2] = ".RROLRRrrRRRRRR."
            listOf(
                "...P..PP..P.....",
                "...PPPPPPPPPP...",
                "..PPPPPPPPPPPP..",
                "..PPSSSSSSSSPP..",
                "...SSSSSSSSSS...",
                "...KKKKKKKKKK...",
                "...SKKKSSKKKS...",
                "...SSSSSSSSSS...",
                "...SSSSSSSSSS...",
                "....SSMMMMSS....",
                "......SSSS......",
                "....RRCCCCRR....",
            ) + body
        }
    }

    private fun palette(p: JudgePersona): Map<Char, Color> {
        val c = mutableMapOf(
            'S' to HexColor(hex = 0xF5C9A6), 'E' to PleadColor.cocoa, 'B' to PleadColor.blush.copy(alpha = 0.85f), 'M' to PleadColor.burgundy,
            'R' to PleadColor.burgundy, 'r' to PleadColor.mahogany, 'C' to PleadColor.paperWhite,
            'o' to HexColor(hex = 0x5A1A18),
        )
        when (p) {
            JudgePersona.wigsworth -> {
                c['W'] = HexColor(hex = 0xF7F2EA); c['w'] = HexColor(hex = 0xD9CDC1)
            }
            JudgePersona.blunt -> {
                c['S'] = HexColor(hex = 0xD9A377); c['H'] = HexColor(hex = 0x3B2119); c['G'] = HexColor(hex = 0x1E1410)
                c['M'] = HexColor(hex = 0x6E3A2A); c['R'] = HexColor(hex = 0x3D1F18); c['r'] = PleadColor.cocoa
                c['C'] = PleadColor.parchment
            }
            JudgePersona.sunny -> {
                c['H'] = HexColor(hex = 0x8A4B2A); c['F'] = PleadColor.blush; c['Y'] = PleadColor.gold; c['C'] = HexColor(hex = 0xF6CFCB)
            }
            JudgePersona.chaos -> {
                c['S'] = HexColor(hex = 0xFFE0C8); c['P'] = HexColor(hex = 0xC24E6E); c['K'] = HexColor(hex = 0x1E1410)
                c['O'] = HexColor(hex = 0xE0607E); c['L'] = PleadColor.success
            }
        }
        return c
    }

    /**
     * The persona's rows with the motion frames applied: eyes shut ('E' → skin; glasses stay) and / or the mouth
     * open (the row under the lowest face 'M' gets two mouth cells in the middle).
     */
    fun rows(p: JudgePersona, eyesClosed: Boolean, mouthOpen: Boolean, walkFrame: Int = 0): List<CharArray> {
        val src = rows(p).map { it.toCharArray() }.toMutableList()
        // Walk steps: the robe's hem (last two rows) swings one cell towards the stepping side.
        val dir = CourtWalkCycle.step(walkFrame)
        if (dir != null && src.size >= 2) {
            for (r in (src.size - 2) until src.size) src[r] = CourtWalkCycle.shift(src[r].toList(), dir, '.').toCharArray()
        }
        val face = 0 until min(src.size, 12)
        if (eyesClosed) {
            for (r in face) for (c in src[r].indices) if (src[r][c] == 'E') src[r][c] = 'S'
        }
        if (mouthOpen) {
            val m = face.lastOrNull { src[it].contains('M') }
            if (m != null && m + 1 < src.size) {
                for (c in listOf(7, 8)) if (src[m + 1][c] == 'S') src[m + 1][c] = 'o'
            }
        }
        return src
    }

    /** Cached grids per persona and frame (the talk loop swaps these at ≈ 9 fps). */
    private val cache = HashMap<String, List<List<Color?>>>()

    fun grid(p: JudgePersona, eyesClosed: Boolean, mouthOpen: Boolean, walkFrame: Int = 0): List<List<Color?>> {
        val walk = CourtWalkCycle.normalized(walkFrame)
        val key = "${p.rawValue}-$eyesClosed-$mouthOpen-$walk"
        synchronized(cache) { cache[key]?.let { return it } }
        val g = grid(rows(p, eyesClosed = eyesClosed, mouthOpen = mouthOpen, walkFrame = walk), palette(p))
        synchronized(cache) { cache[key] = g }
        return g
    }

    /** Builds the walk frames (and the standing / blink / talk ones) before the court is interactive. */
    fun preload(p: JudgePersona) {
        for (w in 0..CourtWalkCycle.walkFrames) {
            for (eyes in listOf(false, true)) for (mouth in listOf(false, true)) grid(p, eyesClosed = eyes, mouthOpen = mouth, walkFrame = w)
        }
    }

    /** Colour grid including the dark outline (row-major, [rows] × [columns]). */
    fun grid(p: JudgePersona): List<List<Color?>> = grid(rows(p).map { it.toCharArray() }, palette(p))

    private fun grid(src: List<CharArray>, pal: Map<Char, Color>): List<List<Color?>> {
        val h = src.size
        val w = 16
        val out = MutableList(rows) { MutableList<Color?>(columns) { null } }
        fun filled(r: Int, c: Int): Boolean = r in 0 until h && c in 0 until w && src[r][c] != '.'
        val outline = PleadColor.cocoa
        for (r in -1 until h) {
            for (c in -1..w) {
                val orow = r + 1
                val ocol = c + 1
                if (orow >= rows || ocol >= columns) continue
                if (filled(r, c)) {
                    out[orow][ocol] = pal[src[r][c]] ?: outline
                } else if (filled(r - 1, c) || filled(r + 1, c) || filled(r, c - 1) || filled(r, c + 1)) {
                    out[orow][ocol] = outline
                }
            }
        }
        return out
    }
}

// MARK: - Walk cycle

/**
 * The entrance walk cycle shared by the judge and the parties (amendment ac): four frames at 8 fps: 1 step left,
 * 2 passing (the figure rises one art pixel), 3 step right, 4 passing; and 0, the standing pose.
 */
internal object CourtWalkCycle {
    /** `CourtEntrancePose.walkFrames`. */
    const val walkFrames = 4

    /** 0 (standing) or 1…4. */
    fun normalized(f: Int): Int = if (f <= 0) 0 else (f - 1) % walkFrames + 1

    /** The hem's swing: -1 left, +1 right, null for standing / passing. */
    fun step(f: Int): Int? = when (normalized(f)) {
        1 -> -1
        3 -> 1
        else -> null
    }

    /** Cells the figure rises on this frame. */
    fun bob(f: Int): Int = if (normalized(f) == 2 || normalized(f) == 4) 1 else 0

    /** A row shifted one cell (`by` −1 / +1), padded with [empty]. */
    fun <T> shift(row: List<T>, by: Int, empty: T): List<T> {
        if (row.isEmpty() || by == 0) return row
        return if (by < 0) row.drop(1) + empty else listOf(empty) + row.dropLast(1)
    }
}

// MARK: - Motion (Courtroom/CourtMotion.swift)

/** Gavel frames: raise → strike → 1-frame impact hold → return (then the painted gavel is back at rest). */
internal enum class GavelFrame { rest, raised, struck, returning }

/**
 * One figure's pose. [lift] is in points (1 = the 1–2 px settle / bob, 2 = a reaction hop), [lean] a scale delta for
 * the speaking "forward emphasis".
 */
internal data class CourtFigurePose(
    val eyesClosed: Boolean = false,
    val mouthOpen: Boolean = false,
    val lift: Float = 0f,
    val lean: Float = 0f,
) {
    val isRest: Boolean get() = this == CourtFigurePose()
}

/** Everything that moves on the shared clock. */
internal enum class CourtActor {
    judge, plaintiff, defendant, crowdLeftBack, crowdLeftFront, crowdRightBack, crowdRightFront;

    val crowdIndex: Int? get() = crowd.indexOf(this).takeIf { it >= 0 }

    companion object {
        val crowd: List<CourtActor> = listOf(crowdLeftBack, crowdLeftFront, crowdRightBack, crowdRightFront)
        fun party(r: Role): CourtActor = if (r == Role.plaintiff) plaintiff else defendant
    }
}

/** Timing tokens (brief §9). Seconds; distances in points. */
internal object CourtMotionTiming {
    /** Ambient idle spacing per character (randomised, staggered). */
    val ambientSpacing: ClosedFloatingPointRange<Double> = 2.5..5.0

    /** First idle after the court appears, then one character every [ambientStagger] (+ jitter). */
    const val ambientFirstBeat = 0.9
    const val ambientStagger = 0.45
    val ambientJitter: ClosedFloatingPointRange<Double> = 0.0..0.3
    const val blink = 0.12

    /** A bob / settle holds the lifted pose this long; the view eases in and out over [bobEase]. */
    const val bobHold = 0.6
    const val bobEase = 0.45

    /** Bubble entrance: fade + scale 0.96 → 1 + 6 pt rise, restrained spring (180–250 ms). */
    const val bubbleEntrance = 0.22
    const val bubbleRise = 6f
    const val bubbleScale = 0.96f

    /** Speaker label / chip first, dialogue 80–120 ms later. */
    const val bodyDelay = 0.1

    /** Line reveal: opacity + 2–4 pt rise, 60–100 ms stagger. */
    const val lineStagger = 0.08
    const val lineDuration = 0.2
    const val lineRise = 3f

    /** Cross-examination: each numbered question after the previous one, quickly. */
    const val questionStagger = 0.14

    /** Talk loop: 2-frame mouth at ≈ 9 fps while the text reveals (bounded). */
    const val talkFrame = 0.11
    const val talkMin = 0.45
    const val talkMax = 1.6

    /** Character state change (lean in / out, nameplate highlight): 180–300 ms. */
    const val characterChange = 0.22

    /** Gavel: 0.09 raise + 0.05 strike + 0.05 impact hold + 0.08 return = 0.27 s (200–300 ms). */
    const val gavelRaise = 0.09
    const val gavelStrike = 0.05
    const val gavelHold = 0.05
    const val gavelReturn = 0.08
    val gavelTotal: Double get() = gavelRaise + gavelStrike + gavelHold + gavelReturn

    /** Exhibit reveal: rise 12 pt + fade with a small settle (250–400 ms); EXHIBIT label lands after. */
    const val exhibitRise = 12f
    const val exhibitDuration = 0.32
    const val exhibitLabelDelay = 0.14

    /** OBJECTION / SUSTAINED / OVERRULED stamp: scale overshoot (180–260 ms). */
    const val stamp = 0.22
    const val stampFromScale = 1.3f

    /** Scene / state transition (dock crossfade): 250–350 ms. */
    const val stateTransition = 0.3

    /** Dock "your turn" pulse (once). */
    const val pulse = 0.9

    /** A party's reaction hop, and the crowd's one restrained group reaction. */
    const val reactionHold = 0.28
    const val crowdReactionStagger = 0.07
    const val crowdHop = 2f

    /** Figures that may be mid-motion at once. */
    const val maxConcurrentMotions = 5
}

/** Turns already revealed this launch (a revisit shows them at once; a fresh launch reveals again). */
internal class CourtRevealMemory {
    val turns: MutableSet<UUID> = mutableSetOf()
    val exhibits: MutableSet<UUID> = mutableSetOf()
    val stamps: MutableSet<String> = mutableSetOf()
    val pulses: MutableSet<String> = mutableSetOf()

    companion object {
        val shared = CourtRevealMemory()
    }
}

/** Per-actor next ambient beat (randomised, staggered). */
internal class CourtAmbientScheduler(start: Double, actors: List<CourtActor>, random: (ClosedFloatingPointRange<Double>) -> Double) {
    val next: MutableMap<CourtActor, Double> = mutableMapOf()

    init {
        actors.forEachIndexed { i, a ->
            next[a] = start + CourtMotionTiming.ambientFirstBeat + i * CourtMotionTiming.ambientStagger + random(CourtMotionTiming.ambientJitter)
        }
    }

    val earliest: Double? get() = next.values.minOrNull()

    /** Pops every actor due at [now] (in a stable order) and schedules its next beat `spacing(actor)` later. */
    fun popDue(
        now: Double,
        spacing: (CourtActor) -> ClosedFloatingPointRange<Double>,
        random: (ClosedFloatingPointRange<Double>) -> Double,
    ): List<CourtActor> {
        val due = next.entries.filter { it.value <= now + 0.0001 }
            .map { it.key to it.value }
            .sortedWith(compareBy<Pair<CourtActor, Double>>({ it.second }, { it.first.ordinal }))
        for ((a, at) in due) {
            val range = spacing(a)
            val lo = max(range.start, CourtMotionTiming.ambientSpacing.start)
            val hi = min(range.endInclusive, CourtMotionTiming.ambientSpacing.endInclusive)
            // Measured from when it was due (so a late wake-up doesn't bunch the next beats), never in the past.
            next[a] = max(at, now - 0.25) + random(lo..hi)
        }
        return due.map { it.first }
    }
}

/**
 * The courtroom's shared ambient clock (the part of `CourtMotionDirector` onboarding uses): ONE cancellable
 * coroutine playing the idles (blink, 1 pt settle / bob, crowd clusters; randomised 2.5–5 s per character,
 * staggered). Views read poses from it. Time is injected (sleep / now / random). Reduce Motion: no clock at all.
 * Onboarding never feeds it a case, so the room stays in the opening phase with nobody holding the floor.
 */
internal class CourtMotionDirector(
    private val sleep: suspend (Double) -> Unit = { delay((it * 1000).toLong()) },
    private val now: () -> Double = { System.nanoTime() / 1_000_000_000.0 },
    private val random: (ClosedFloatingPointRange<Double>) -> Double = { r ->
        if (r.endInclusive <= r.start) r.start else kotlin.random.Random.nextDouble(r.start, r.endInclusive)
    },
    memory: CourtRevealMemory? = null,
    private val scope: CoroutineScope? = null,
) {
    // Poses (one property per figure, so a blink redraws only that figure).
    var judgePose: CourtFigurePose by mutableStateOf(CourtFigurePose())
        private set
    var plaintiffPose: CourtFigurePose by mutableStateOf(CourtFigurePose())
        private set
    var defendantPose: CourtFigurePose by mutableStateOf(CourtFigurePose())
        private set
    var crowdLift: List<Float> by mutableStateOf(List(CourtActor.crowd.size) { 0f })
        private set
    var gavel: GavelFrame by mutableStateOf(GavelFrame.rest)
        private set

    /** The side holding the floor (never set in onboarding). */
    val floor: Role? = null

    /** Reduce Motion: no clock at all; figures rest; never talk / gavel / bounce. */
    var reduceMotion: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            if (value) {
                stopTask(); settle()
            } else {
                restartIfNeeded()
            }
        }

    /** True while the (single) clock task exists. */
    val isRunning: Boolean get() = job?.isActive == true

    /** Every ambient beat (actor, time): tests check spacing and stagger. */
    var onBeat: ((CourtActor, Double) -> Unit)? = null

    /** Clock tasks started so far (each replaces the previous one). */
    var tasksStarted = 0
        private set

    /** Most figures mid-motion at once since creation. */
    var peakConcurrentMotions = 0
        private set

    @Suppress("unused")
    private val memory: CourtRevealMemory = memory ?: CourtRevealMemory.shared
    private val runScope: CoroutineScope by lazy { scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    private var job: Job? = null
    private var generation = 0
    private var scheduler: CourtAmbientScheduler? = null
    private val timeline = mutableListOf<Triple<Double, Int, Change>>()
    private var seq = 0
    private var visible = false
    private var active = true

    private sealed class Change {
        data class Eyes(val actor: CourtActor, val closed: Boolean) : Change()
        data class Mouth(val actor: CourtActor, val open: Boolean) : Change()
        data class Lift(val actor: CourtActor, val value: Float) : Change()
        data class Lean(val actor: CourtActor, val value: Float) : Change()
        data class Gavel(val frame: GavelFrame) : Change()
    }

    // MARK: Lifecycle

    /** The court is on screen. ([analytics] is accepted for parity; onboarding always passes false.) */
    @Suppress("UNUSED_PARAMETER")
    fun appear(analytics: Boolean = true) {
        visible = true
        restartIfNeeded()
    }

    /** Off screen: cancel the clock, everyone back at rest. */
    fun disappear() {
        visible = false
        stopTask()
        settle()
    }

    /** The activity is resumed. Backgrounded pauses the clock. */
    fun setActive(isActive: Boolean) {
        if (isActive == active) return
        active = isActive
        if (isActive) restartIfNeeded() else {
            stopTask(); settle()
        }
    }

    /** Cancels the clock (idempotent). */
    fun stopTask() {
        job?.cancel()
        job = null
        generation += 1
    }

    /** Awaits the current clock task (tests). */
    suspend fun join() {
        job?.join()
    }

    /** Swift `pose(for:)`. */
    fun pose(r: Role): CourtFigurePose = if (r == Role.plaintiff) plaintiffPose else defendantPose

    /** The waiting party (not holding the floor while someone else does) idles more calmly. */
    fun isCalm(r: Role): Boolean = floor != null && floor != r

    private val shouldRun: Boolean get() = visible && active && !reduceMotion

    private fun restartIfNeeded() {
        if (!shouldRun) return
        if (scheduler == null) scheduler = CourtAmbientScheduler(now(), ambientActors, random)
        // Replace (never run beside) the previous clock: there is only ever one.
        job?.cancel()
        generation += 1
        val mine = generation
        tasksStarted += 1
        job = runScope.launch {
            loop()
            if (generation == mine) job = null
        }
    }

    private val ambientActors: List<CourtActor> = listOf(
        CourtActor.judge, CourtActor.plaintiff, CourtActor.crowdLeftBack, CourtActor.defendant,
        CourtActor.crowdRightFront, CourtActor.crowdLeftFront, CourtActor.crowdRightBack,
    )

    // MARK: Scheduling

    private fun insert(at: Double, c: Change) {
        seq += 1
        val i = timeline.indexOfFirst { it.first > at }.let { if (it < 0) timeline.size else it }
        timeline.add(i, Triple(at, seq, c))
    }

    /** One idle beat for [a] at [t]. */
    private fun scheduleIdle(a: CourtActor, t: Double) {
        onBeat?.invoke(a, t)
        when (a) {
            CourtActor.judge -> {
                // Mostly a blink, sometimes the 1 px settle (never an obvious loop).
                if (random(0.0..1.0) < 0.6) {
                    insert(t, Change.Eyes(a, true)); insert(t + CourtMotionTiming.blink, Change.Eyes(a, false))
                } else {
                    insert(t, Change.Lift(a, 1f)); insert(t + CourtMotionTiming.bobHold, Change.Lift(a, 0f))
                }
            }
            CourtActor.plaintiff, CourtActor.defendant -> {
                val r = if (a == CourtActor.plaintiff) Role.plaintiff else Role.defendant
                // Waiting (the other side has the floor): calmer, mostly blinks.
                val blinkOdds = if (isCalm(r)) 0.75 else 0.5
                if (random(0.0..1.0) < blinkOdds) {
                    insert(t, Change.Eyes(a, true)); insert(t + CourtMotionTiming.blink, Change.Eyes(a, false))
                } else {
                    insert(t, Change.Lift(a, 1f)); insert(t + CourtMotionTiming.bobHold, Change.Lift(a, 0f))
                }
            }
            else -> {
                insert(t, Change.Lift(a, 1f)); insert(t + CourtMotionTiming.bobHold * 1.2, Change.Lift(a, 0f))
            }
        }
    }

    /** `CourtPhase.opening`'s spacing (onboarding's room never leaves the opening). */
    private fun spacing(a: CourtActor): ClosedFloatingPointRange<Double> = when (a) {
        CourtActor.judge -> 2.5..4.0
        CourtActor.plaintiff, CourtActor.defendant ->
            if (isCalm(if (a == CourtActor.plaintiff) Role.plaintiff else Role.defendant)) 3.5..5.0 else 2.5..4.5
        else -> 3.0..5.0
    }

    // MARK: Clock

    private suspend fun loop() {
        while (currentCoroutineContext().isActive) {
            val t = now()
            applyDue(t)
            val wake = listOfNotNull(timeline.firstOrNull()?.first, scheduler?.earliest).minOrNull() ?: return
            try {
                sleep(max(wake - t, 1.0 / 120))
            } catch (_: CancellationException) {
                return
            }
        }
    }

    private fun applyDue(t: Double) {
        scheduler?.let { s ->
            val due = s.popDue(t, ::spacing, random)
            for (a in due) scheduleIdle(a, t)
        }
        while (timeline.isNotEmpty() && timeline.first().first <= t + 0.0005) {
            val first = timeline.removeAt(0)
            apply(first.third)
        }
        peakConcurrentMotions = max(peakConcurrentMotions, motionCount)
    }

    /** Figures mid-motion right now (the crowd is one layer). */
    val motionCount: Int
        get() = listOf(!judgePose.isRest, gavel != GavelFrame.rest, !plaintiffPose.isRest, !defendantPose.isRest, crowdLift.any { it != 0f })
            .count { it }

    private fun apply(c: Change) {
        // Reduce Motion: nothing that talks, swings or bounces is ever applied.
        if (reduceMotion && c !is Change.Eyes) return
        when (c) {
            is Change.Eyes -> mutate(c.actor) { it.copy(eyesClosed = c.closed) }
            is Change.Mouth -> mutate(c.actor) { it.copy(mouthOpen = c.open) }
            is Change.Lift -> mutate(c.actor) { it.copy(lift = c.value) }
            is Change.Lean -> mutate(c.actor) { it.copy(lean = c.value) }
            is Change.Gavel -> gavel = c.frame
        }
    }

    private fun mutate(a: CourtActor, f: (CourtFigurePose) -> CourtFigurePose) {
        when (a) {
            CourtActor.judge -> f(judgePose).let { if (it != judgePose) judgePose = it }
            CourtActor.plaintiff -> f(plaintiffPose).let { if (it != plaintiffPose) plaintiffPose = it }
            CourtActor.defendant -> f(defendantPose).let { if (it != defendantPose) defendantPose = it }
            else -> {
                val i = a.crowdIndex ?: return
                val p = f(CourtFigurePose(lift = crowdLift[i]))
                if (p.lift != crowdLift[i]) crowdLift = crowdLift.toMutableList().also { it[i] = p.lift }
            }
        }
    }

    /** Everyone back at rest, pending beats dropped. */
    private fun settle() {
        timeline.clear()
        scheduler = null
        if (!judgePose.isRest) judgePose = CourtFigurePose()
        if (!plaintiffPose.isRest) plaintiffPose = CourtFigurePose()
        if (!defendantPose.isRest) defendantPose = CourtFigurePose()
        if (crowdLift.any { it != 0f }) crowdLift = List(CourtActor.crowd.size) { 0f }
        if (gavel != GavelFrame.rest) gavel = GavelFrame.rest
    }
}

// MARK: - Gavel (Courtroom/CourtMotionViews.swift)

/**
 * The gavel strike. The painted gavel rests on the bench; while the judge strikes, the painted one is covered by
 * its mirror-image patch of the bench and a pixel gavel swings: raise → strike (+ impact pixels) → return.
 * Fills a box of `zones.size`.
 */
@Composable
internal fun CourtGavelLayer(zones: CourtroomZones, motion: CourtMotionDirector, modifier: Modifier = Modifier) {
    val f = motion.gavel
    val patch = zones.rect(CourtArtCrops.gavelPatchUnit)
    val cell = zones.art.width / CourtArtCrops.artPixels.width * CourtGavelSprite.artCell
    val origin = Offset(zones.x(CourtArtCrops.gavelOriginUnit.x), zones.y(CourtArtCrops.gavelOriginUnit.y))
    val angle by animateFloatAsState(
        targetValue = CourtGavelLayer.angle(f).toFloat(),
        animationSpec = tween(
            durationMillis = ((if (f == GavelFrame.struck) CourtMotionTiming.gavelStrike else CourtMotionTiming.gavelRaise) * 1000).toInt(),
            easing = PleadMotion.easeOut,
        ),
        label = "gavel",
    )
    Box(
        modifier
            .size(zones.size.width.dp, zones.size.height.dp)
            .graphicsLayer { alpha = if (f == GavelFrame.rest) 0f else 1f }
            .clearAndSetSemantics { },
    ) {
        CourtArtCrops.shared.gavelPatch?.let { img ->
            Canvas(Modifier.offset(patch.left.dp, patch.top.dp).size(patch.width.dp, patch.height.dp)) {
                drawImage(img, dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()), filterQuality = FilterQuality.Medium)
            }
        }
        CourtGavelSprite(
            cell = cell,
            impact = f == GavelFrame.struck,
            modifier = Modifier
                .offset((origin.x - CourtGavelSprite.margin * cell).dp, origin.y.dp)
                .graphicsLayer {
                    rotationZ = angle
                    transformOrigin = CourtGavelSprite.pivot
                },
        )
    }
}

internal object CourtGavelLayer {
    fun angle(f: GavelFrame): Double = when (f) {
        GavelFrame.rest, GavelFrame.returning -> 0.0
        GavelFrame.raised -> 50.0
        GavelFrame.struck -> -5.0
    }
}

/** Pixel gavel (head upright on the left, handle to the right). Rotates about the handle's end. [cell] is in dp. */
@Composable
internal fun CourtGavelSprite(cell: Float, modifier: Modifier = Modifier, impact: Boolean = false) {
    val s = CourtGavelSprite.size(cell)
    Canvas(modifier.size(s.width.dp, s.height.dp)) {
        val cellPx = cell.dp.toPx()
        val m = CourtGavelSprite.margin.toInt()
        CourtGavelSprite.rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                val color = CourtGavelSprite.palette[ch] ?: return@forEachIndexed
                fillCell(0f, 0f, c + m, r, cellPx, color)
            }
        }
        if (impact) {
            for ((c, r) in CourtGavelSprite.sparks) fillCell(0f, 0f, c, r, cellPx, PleadColor.cream)
        }
    }
}

internal object CourtGavelSprite {
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

    /** Rotation pivot: the handle's end (Swift `UnitPoint(x: 1, y: 5.5 / 11)`). */
    val pivot = TransformOrigin(1f, 5.5f / 11f)
    val palette: Map<Char, Color> = mapOf(
        'O' to HexColor(hex = 0x2E0306), 'd' to HexColor(hex = 0x652A29), 'h' to HexColor(hex = 0xE18B56),
        'm' to HexColor(hex = 0x8A4A3A), 'k' to HexColor(hex = 0x79342D),
    )

    /** Impact pixels (col, row) in the margin, shown for the one-frame hold. */
    val sparks: List<Pair<Int, Int>> = listOf(0 to 2, 1 to 9, 0 to 6)
}

// MARK: - Audience

/**
 * The painted stands as four clusters lifted from the painting itself (feathered crops drawn exactly over their
 * source). Each cluster bobs 1 pt on the shared clock and hops 2 pt on key moments; at rest they are
 * indistinguishable from the painting. Fills a box of `zones.size`. Nothing under Reduce Motion.
 */
@Composable
internal fun CourtCrowdLayer(zones: CourtroomZones, motion: CourtMotionDirector, modifier: Modifier = Modifier, settle: Double = 1.0) {
    if (accessibilityReduceMotion()) return
    val crops = CourtArtCrops.shared.crowd
    Box(modifier.size(zones.size.width.dp, zones.size.height.dp).clearAndSetSemantics { }) {
        CourtArtCrops.crowdUnits.forEachIndexed { i, unit ->
            val img = crops.getOrNull(i) ?: return@forEachIndexed
            key(i) {
                val r = zones.rect(unit)
                val target = (motion.crowdLift.getOrNull(i) ?: 0f) + CourtCrowdLayer.settleLift(i, settle)
                val lift by animateFloatAsState(
                    targetValue = target,
                    animationSpec = if (settle < 1) {
                        tween(durationMillis = 125, easing = LinearEasing) // `CourtEntranceTiming.frame`
                    } else {
                        tween(durationMillis = if (target > 1f) 160 else (CourtMotionTiming.bobEase * 1000).toInt(), easing = PleadMotion.easeInOut)
                    },
                    label = "crowd$i",
                )
                val leftSide = unit.center.x < 0.5f
                Canvas(
                    Modifier
                        .offset(r.left.dp, (r.top - lift).dp)
                        .size(r.width.dp, r.height.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
                ) {
                    drawImage(img, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                    // Soft edges on the inner side and the top so a 1–2 pt offset never shows a seam.
                    val horizontal = listOf(0f to Color.Black, 0.78f to Color.Black, 1f to Color.Transparent)
                    drawRect(
                        if (leftSide) Brush.horizontalGradient(*horizontal.toTypedArray())
                        else Brush.horizontalGradient(*horizontal.toTypedArray(), startX = size.width, endX = 0f),
                        blendMode = BlendMode.DstIn,
                    )
                    drawRect(
                        Brush.verticalGradient(0f to Color.Transparent, 0.18f to Color.Black, 0.92f to Color.Black, 1f to Color.Transparent),
                        blendMode = BlendMode.DstIn,
                    )
                }
            }
        }
    }
}

internal object CourtCrowdLayer {
    /** Cluster [i]'s settle lift for entrance progress [settle] (staggered windows, a sine hop in each). */
    fun settleLift(i: Int, settle: Double): Float {
        if (settle >= 1) return 0f
        val start = i * 0.15
        val span = 0.5
        val p = min(1.0, max(0.0, (settle - start) / span))
        return (sin(p * PI) * CourtMotionTiming.crowdHop).toFloat()
    }
}

// MARK: - Crops of the painting

/**
 * Pieces of [CourtroomBackground] used by the motion layers (derived from the painting, nothing redrawn): the four
 * audience clusters and the mirror-image bench patch that hides the painted gavel mid-strike.
 */
internal class CourtArtCrops(image: ImageBitmap? = CourtroomBackground.image) {
    val crowd: List<ImageBitmap?>
    val gavelPatch: ImageBitmap?

    init {
        val bmp = image?.asAndroidBitmap()
        if (bmp == null) {
            crowd = List(crowdPixels.size) { null }
            gavelPatch = null
        } else {
            // The asset may be any resolution: scale the source-pixel rects to it.
            val sx = bmp.width / artPixels.width
            val sy = bmp.height / artPixels.height
            fun crop(r: Rect, mirror: Boolean): ImageBitmap? = runCatching {
                val x0 = floor(r.left * sx).toInt().coerceIn(0, bmp.width - 1)
                val y0 = floor(r.top * sy).toInt().coerceIn(0, bmp.height - 1)
                val x1 = ceil(r.right * sx).toInt().coerceIn(x0 + 1, bmp.width)
                val y1 = ceil(r.bottom * sy).toInt().coerceIn(y0 + 1, bmp.height)
                val matrix = if (mirror) Matrix().apply { preScale(-1f, 1f) } else null
                Bitmap.createBitmap(bmp, x0, y0, x1 - x0, y1 - y0, matrix, false).asImageBitmap()
            }.getOrNull()
            crowd = crowdPixels.map { crop(it, mirror = false) }
            // Mirror of the (symmetric) bench on the other side of the chair: plain bench top and wall.
            val p = gavelPatchPixels
            val mirrored = rectXYWH(artPixels.width - p.right, p.top, p.width, p.height)
            gavelPatch = crop(mirrored, mirror = true)
        }
    }

    companion object {
        val shared: CourtArtCrops by lazy { CourtArtCrops() }
        val artPixels = Size(1170f, 2532f)

        /** Audience clusters (source pixels): left back / front, right back / front. */
        val crowdPixels: List<Rect> = listOf(
            rectXYWH(0f, 690f, 140f, 110f),
            rectXYWH(0f, 790f, 190f, 135f),
            rectXYWH(1020f, 690f, 150f, 115f),
            rectXYWH(985f, 790f, 185f, 135f),
        )

        /** The painted gavel and its block on the bench (source pixels). */
        val gavelPatchPixels = rectXYWH(412f, 842f, 150f, 90f)

        /** Top-left of the pixel gavel's grid (after its spark margin) at rest. */
        val gavelOriginPixels = Offset(442f, 861f)

        fun unit(r: Rect): Rect = rectXYWH(r.left / artPixels.width, r.top / artPixels.height, r.width / artPixels.width, r.height / artPixels.height)
        val crowdUnits: List<Rect> get() = crowdPixels.map(::unit)
        val gavelPatchUnit: Rect get() = unit(gavelPatchPixels)
        val gavelOriginUnit: Offset get() = Offset(gavelOriginPixels.x / artPixels.width, gavelOriginPixels.y / artPixels.height)
    }
}
