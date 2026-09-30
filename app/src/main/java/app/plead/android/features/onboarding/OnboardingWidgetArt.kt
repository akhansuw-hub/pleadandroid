// Interim copies of Shared/PleadWidgetPalette.swift and Shared/PixelJudgeGlyph.swift for the onboarding widget screens.
// Their Android originals belong to wave 3f (`widgets/`), which is not merged yet: when it is, delete this file and
// import the widgets package's `PleadWidgetPalette` / `PixelJudgeGlyph` (same names and signatures).
// (Split out of wave 3b's OnboardingBorrowedArt.kt by the integrator; the courtroom/paywall copies now import the originals.)
package app.plead.android.features.onboarding

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import app.plead.android.designsystem.Color as HexColor

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

