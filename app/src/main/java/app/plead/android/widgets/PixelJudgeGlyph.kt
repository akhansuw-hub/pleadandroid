// Port of Shared/PixelJudgeGlyph.swift: the widget system's pixel art (the judge, the judge at the bench with a gavel,
// the gavel, the scales and the Plead heart), drawn from the same character grids in the brief palette, each fill
// grid with an automatic 1-cell dark-cocoa outline.
//
// Glance (RemoteViews) has no Canvas, so a glyph is rendered to a Bitmap at whole device pixels per cell (iOS: "whole
// device pixels per cell: crisp, seam-free edges at every size") and shown with `Image` at exactly its pixel size, so
// the launcher never resamples it. The court-session notification uses the same bitmaps.
@file:Suppress("EnumEntryName")

package app.plead.android.widgets

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.size
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The glyph, `size` dp tall (the width follows the sprite's aspect ratio), centred in a `size * aspect × size` frame
 * like the SwiftUI view. Decorative (iOS `.accessibilityHidden(true)`).
 */
@Composable
fun PixelJudgeGlyph(kind: PixelJudgeGlyph.Kind, size: Dp = 44.dp, modifier: GlanceModifier = GlanceModifier) {
    val context = LocalContext.current
    val art = PleadPixelSprites.art(kind)
    val rendered = PleadPixelSprites.render(context, kind, size)
    Box(modifier = modifier.size(size * art.aspect, size), contentAlignment = Alignment.Center) {
        Image(
            provider = ImageProvider(rendered.bitmap),
            contentDescription = null,
            modifier = GlanceModifier.size(rendered.widthDp.dp, rendered.heightDp.dp),
            contentScale = ContentScale.Fit,
        )
    }
}

object PixelJudgeGlyph {
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

/** A rendered sprite: a colour per cell (null = transparent), outline included. Colours are ARGB ints. */
class PleadPixelBitmap(val cells: List<List<Int?>>) {
    val rows: Int get() = cells.size
    val columns: Int get() = cells.firstOrNull()?.size ?: 1
    val aspect: Float get() = columns.toFloat() / max(rows, 1).toFloat()
}

object PleadPixelSprites {
    // MARK: Palette

    val ink: Map<Char, Int> = mapOf(
        'W' to pleadRGB(0xF7F2EA).argb, // wig
        'w' to pleadRGB(0xD9CDC1).argb, // wig curl shade
        'S' to pleadRGB(0xF5C9A6).argb, // skin
        's' to pleadRGB(0xE4AE8A).argb, // skin shade
        'E' to PleadWidgetPalette.darkCocoa.argb, // eyes
        'B' to PleadWidgetPalette.romanceBlush.argb, // cheeks
        'M' to PleadWidgetPalette.courtBurgundy.argb, // mouth
        'R' to PleadWidgetPalette.courtBurgundy.argb, // robe
        'r' to PleadWidgetPalette.deepWine.argb, // robe fold
        'C' to PleadWidgetPalette.paperWhite.argb, // collar / bands
        'G' to PleadWidgetPalette.courtGold.argb, // gold
        'g' to pleadRGB(0x9C6F3A).argb, // gold shade
        'm' to PleadWidgetPalette.mahogany.argb, // wood
        'n' to PleadWidgetPalette.woodLight.argb, // wood highlight
        'd' to PleadWidgetPalette.woodDark.argb, // wood shade
        'H' to PleadWidgetPalette.romanceBlush.argb, // heart
        'h' to PleadWidgetPalette.paperWhite.argb, // heart highlight
        'P' to PleadWidgetPalette.courtBurgundy.argb, // heart shade
        'k' to PleadWidgetPalette.darkCocoa.argb, // explicit outline
    )
    val outline: Int = PleadWidgetPalette.darkCocoa.argb

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
        val canvas = Array(benchTop + 8) { CharArray(width) { '.' } }
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

    private fun stamp(src: List<String>, canvas: Array<CharArray>, row: Int, col: Int) {
        for ((r, line) in src.withIndex()) {
            for ((c, ch) in line.withIndex()) {
                if (ch == '.') continue
                val rr = row + r
                val cc = col + c
                if (rr < 0 || rr >= canvas.size || cc < 0 || cc >= canvas[rr].size) continue
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
        fun filled(r: Int, c: Int): Boolean {
            if (r < 0 || r >= h || c < 0 || c >= src[r].length) return false
            return src[r][c] != '.'
        }
        val out = Array(h + 2) { arrayOfNulls<Int>(w + 2) }
        for (r in -1..h) {
            for (c in -1..w) {
                if (filled(r, c)) {
                    out[r + 1][c + 1] = ink[src[r][c]] ?: outline
                } else if (filled(r - 1, c) || filled(r + 1, c) || filled(r, c - 1) || filled(r, c + 1)) {
                    out[r + 1][c + 1] = outline
                }
            }
        }
        return PleadPixelBitmap(out.map { it.toList() })
    }

    /** A glyph rendered for display: the bitmap and its size in dp (whole device pixels per cell). */
    class Rendered(val bitmap: Bitmap, val cellPx: Int, val widthDp: Float, val heightDp: Float)

    /**
     * Whole device pixels per cell for a glyph `heightDp` tall at `density` (iOS: `floor(raw × displayScale)`, at
     * least 1). The frame is `height × aspect` wide, so the cell is limited by whichever side is tighter.
     */
    fun cellPixels(kind: PixelJudgeGlyph.Kind, heightDp: Float, density: Float): Int {
        val art = art(kind)
        val widthDp = heightDp * art.aspect
        val raw = minOf(widthDp / art.columns, heightDp / art.rows)
        return max(1, floor(raw * density + 1e-4f).toInt())
    }

    private val bitmaps = HashMap<Pair<PixelJudgeGlyph.Kind, Int>, Bitmap>()

    /** The glyph at `height` for this context's density (cached per kind and cell size). */
    fun render(context: Context, kind: PixelJudgeGlyph.Kind, height: Dp): Rendered {
        val density = context.resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val cell = cellPixels(kind, height.value, density)
        val bitmap = synchronized(bitmaps) { bitmaps.getOrPut(kind to cell) { bitmap(kind, cell) } }
        return Rendered(bitmap, cell, bitmap.width / density, bitmap.height / density)
    }

    /** One `cellPx × cellPx` square per cell, no filtering (nearest-neighbour by construction). */
    fun bitmap(kind: PixelJudgeGlyph.Kind, cellPx: Int): Bitmap {
        val art = art(kind)
        val w = art.columns * cellPx
        val h = art.rows * cellPx
        val pixels = IntArray(w * h)
        for ((r, row) in art.cells.withIndex()) {
            for ((c, color) in row.withIndex()) {
                color ?: continue
                for (y in r * cellPx until (r + 1) * cellPx) {
                    val base = y * w
                    for (x in c * cellPx until (c + 1) * cellPx) pixels[base + x] = color
                }
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** Pixel size of [bitmap] for a glyph `heightDp` tall (tests; RemoteViews budget). */
    fun pixelSize(kind: PixelJudgeGlyph.Kind, heightDp: Float, density: Float): Pair<Int, Int> {
        val art = art(kind)
        val cell = cellPixels(kind, heightDp, density)
        return (art.columns * cell) to (art.rows * cell)
    }

    /** Rounds a dp value to whole device pixels (offsets in the frame, like iOS). */
    fun snap(dp: Float, density: Float): Float = (dp * density).roundToInt() / density
}
