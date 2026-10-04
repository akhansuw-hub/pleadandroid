// Port of ArgueWin/Features/Onboarding/About/AboutPixelSprites.swift. Pixel props for the "About you two" heroes
// (amendment bd). Same idiom as the courtroom art (`JudgeSprite`, `CourtGavelSprite`, `MockTrialPizzaBox`): character
// grids drawn as whole cells with a dark outline, palettes sampled from the painted courtroom (`CourtroomBackground`).
// Everything here is decorative (hidden from TalkBack).
package app.plead.android.features.onboarding.about

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import app.plead.android.designsystem.Color as hex
import app.plead.android.designsystem.PleadBrandColor
import app.plead.android.features.onboarding.OnboardingPalette
import kotlin.math.roundToInt

/** Swift `AboutPixelSprite`: a character grid drawn at an exact `cell` size (frame = columns × rows cells). */
object AboutPixelSprite {
    fun size(rows: List<String>, cell: Float): Size = Size((rows.maxOfOrNull { it.length } ?: 0) * cell, rows.size * cell)
}

/**
 * Draws `rows` with its top-left at `topLeft` (snapped to whole pixels, as Swift's `aboutPlaced` snaps to whole
 * points). `cell` and `topLeft` are in px.
 */
internal fun DrawScope.drawSprite(rows: List<String>, palette: Map<Char, Color>, cell: Float, topLeft: Offset, alpha: Float = 1f) {
    if (alpha <= 0f) return
    val x0 = topLeft.x.roundToInt().toFloat()
    val y0 = topLeft.y.roundToInt().toFloat()
    // Swift fills each cell 0.2 pt larger so neighbours never show a seam.
    val overlap = 0.2f * density
    rows.forEachIndexed { r, row ->
        row.forEachIndexed { c, ch ->
            val color = palette[ch] ?: return@forEachIndexed
            drawRect(color, Offset(x0 + c * cell, y0 + r * cell), Size(cell + overlap, cell + overlap), alpha = alpha.coerceIn(0f, 1f))
        }
    }
}

/** The grids and palettes. */
object AboutPixelArt {
    /** The courtroom's darkest wood (the painting's outline colour). */
    val outline = hex(0x260306)

    // MARK: Desk

    /**
     * Litigant desk (the painted podiums' wood and heart panel), built for `columns` × `rows` cells: lid, lip, front
     * with an inset panel. The heart is drawn separately (`deskHeart`) so it can sit centred at any width.
     */
    fun desk(columns: Int, rows: Int): List<String> {
        val w = maxOf(columns, 10)
        val h = maxOf(rows, 8)
        fun line(edge: Char, fill: Char, inner: Char? = null, innerFrom: Int = 0): String {
            val s = CharArray(w) { fill }
            s[0] = edge; s[w - 1] = edge
            if (inner != null) for (c in innerFrom until (w - innerFrom)) if (c > 0 && c < w - 1) s[c] = inner
            return String(s)
        }
        val out = mutableListOf<String>()
        out.add("." + "o".repeat(w - 2) + ".")
        // Lid: a highlight streak, then the plain top, then its shaded front edge and the lip outline.
        val lid = line('o', 'L').toCharArray()
        var c = 3
        while (c < w - 3) {
            lid[c] = 'H'; lid[c + 1] = 'H'
            c += 5
        }
        out.add(String(lid))
        out.add(line('o', 'L'))
        out.add(line('o', 'l'))
        out.add("o".repeat(w))
        // Front: frame columns, then an inset panel (dark border, panel fill) from the second front row down.
        out.add(line('o', 'f', 'F', innerFrom = 1).replacingFirstAndLast())
        val remaining = h - out.size
        for (r in 0 until remaining) {
            val s = line('o', 'f').toCharArray()
            s[1] = 'F'; s[w - 2] = 'F'
            val p0 = 3
            val p1 = w - 4
            for (cc in p0..p1) s[cc] = if (r == 0 || cc == p0 || cc == p1) 'd' else 'p'
            out.add(String(s))
        }
        return out
    }

    val deskPalette: Map<Char, Color> = mapOf(
        'o' to outline, 'L' to hex(0xDC7D51), 'H' to hex(0xE89A68), 'l' to hex(0xBE5F39),
        'F' to hex(0x8A3A2C), 'f' to hex(0x772E29), 'd' to hex(0x42191D), 'p' to hex(0x6C2A25),
    )

    /** The heart carved into the desk panel (the painted podium's heart). */
    val deskHeartPalette: Map<Char, Color> = mapOf('c' to hex(0xB0553A), 'h' to hex(0xD97A50), 'd' to hex(0x5E2322))

    // MARK: Hands

    /** An open palm raised (sleeve `T` at the wrist). */
    val palm = listOf(
        ".o.o.o.",
        "oSoSoSo",
        "oSoSoSo.",
        "oSSSSSoo",
        "oSSSSSSo",
        "oSSSSSo.",
        ".oSSSo..",
        ".oTTTo..",
        ".oTTTo..",
    )

    /** A hand pointing right (mirror for left). */
    val pointRight = listOf(
        ".ooo.....",
        "oSSSoooo.",
        "oSSSSSSSo",
        "oSSSoooo.",
        "oTTTo....",
        ".ooo.....",
    )

    fun mirrored(rows: List<String>): List<String> {
        val w = rows.maxOfOrNull { it.length } ?: 0
        return rows.map { (it + ".".repeat(w - it.length)).reversed() }
    }

    /**
     * The judge's facepalm. Amendment be: drawn on the judge's 16-column grid from its face row 3 (row 0 here) down to the
     * collar. Three upright two-cell fingers with separate tips, split by crease columns `k`, over the forehead and eyes
     * (judge row 5; the eyes at columns 5 and 10 sit under fingers), the thumb out to the right on row 3, a palm crease
     * `k`, the palm narrowing to a wrist on the viewer's left so the mouth (columns 7–8, row 8) stays visible, and the
     * robe sleeve running down past the wig's left curls into the robe. The wig's top and sides are untouched.
     * H hand (a shade darker than the face so it reads as a hand), k crease, o outline, R sleeve, r sleeve shade.
     */
    val facepalm = listOf(
        "....oo.oo.oo....",
        "...oHHkHHkHHo...",
        "...oHHkHHkHHo...",
        "...oHHHHHHHHHo..",
        "...oHkHHHHHHo...",
        "..oHHHo.........",
        ".oHHHo..........",
        ".oRRRo..........",
        "oRrRo...........",
    )
    val facepalmPalette: Map<Char, Color> = mapOf(
        'o' to hex(0x2A1310), 'H' to hex(0xDB9A6E), 'k' to hex(0xA8643F), 'R' to hex(0x8A2C2A), 'r' to hex(0x5E251F),
    )

    /**
     * Amendment be (screen 3, reworded): the exasperated judge's hand to the brow. Drawn on the judge's grid with four
     * extra columns to the right (20 columns); row 0 is the judge's wig row 1. The face and wig keep their colours: the
     * fingers lie on the wig fringe (judge row 2, columns 9–14) with their lower edge on the forehead row (row 3), never
     * lower; the palm sits on the wig's right side (columns 13–15), and the robe sleeve runs down and outward past the wig
     * to the right, so it reads as an arm coming up. Skin is the judge's own (`JudgeSprite` "S" #F5C9A6) with finger
     * creases `k`; sleeve the robe's "R". Row 4 (judge row 5) draws the closed eyes as two-cell lids `l` at columns 5–6
     * and 9–10.
     */
    val browHand = listOf(
        ".........oooooo.....",
        "........oSkSkSSo....",
        ".........ooooSSSo...",
        "............oSSSo...",
        ".....ll..ll..oSSo...",
        ".............oRRRo..",
        "..............oRRRo.",
        "..............oRRRo.",
        "..............oRRRo.",
        "..............oRRRo.",
    )
    val browHandPalette: Map<Char, Color> = mapOf(
        'o' to hex(0x2A1310), 'S' to hex(0xF5C9A6), 'k' to hex(0xD9A07E), 'R' to hex(0x8A2C2A), 'l' to hex(0x8A5A48),
    )

    /** Amendment bg: the judge's notes, a sheet lying flat on the bench (10 × 4): cream paper, two ruled lines. */
    val notesPaper = listOf(
        "oooooooooo",
        "oWkkkkkkWo",
        "oWkkkkWWWo",
        "oooooooooo",
    )
    val notesPaperPalette: Map<Char, Color> = mapOf('o' to hex(0x2A1310), 'W' to hex(0xFFF6E0), 'k' to hex(0xA89484))

    /** The quill (6 × 7), nib at the bottom left: white feather, dark shaft, black nib. */
    val quill = listOf(
        "....oo",
        "...oWo",
        "..oWWo",
        ".oWWo.",
        ".oWo..",
        "oko...",
        "n.....",
    )
    val quillPalette: Map<Char, Color> = mapOf('o' to hex(0x2A1310), 'W' to hex(0xF7F2EA), 'k' to hex(0x3B2119), 'n' to hex(0x1E1410))

    /** A sweat drop beside the judge's head (screen 3). */
    val sweatDrop = listOf(
        "..o..",
        ".oBo.",
        "oBBBo",
        "oWBBo",
        ".ooo.",
    )
    val sweatPalette: Map<Char, Color> = mapOf('o' to hex(0x2C5F8A), 'W' to hex(0xFFFFFF), 'B' to hex(0x8FC8F0))

    /** The fingers gripping a held prop (screen 2): 6 × 4 cells. */
    val grip = listOf(
        ".oooo.",
        "oSSSSo",
        "oSsSso",
        ".oooo.",
    )

    fun handPalette(skin: Color, sleeve: Color): Map<Char, Color> =
        mapOf('o' to outline, 'S' to skin, 's' to hex(0xB9805E), 'T' to sleeve, 'R' to sleeve)

    // MARK: Props

    /** A wobbly stack of plates (screen 2). */
    val plates = listOf(
        "..oooooooo..",
        ".oWWWWWWWWo.",
        "oWBBBBBBBBWo",
        ".oooooooooo.",
        ".oWWWWWWWWo.",
        "oWBBBBBBBBWo",
        ".oooooooooo.",
        ".oWWWWWWWWo.",
        "oWBBBBBBBBWo",
        ".oooooooooo.",
        "oWWWWWWWWWWo",
        "oWBBBBBBBBWo",
        ".oooooooooo.",
    )
    val platesPalette: Map<Char, Color> = mapOf('o' to outline, 'W' to hex(0xF7F2EA), 'B' to hex(0xC9D6E3))

    /** One case folder seen edge-on (screen 4's "REPEAT CASE FILES" stack): manila with a darker spine. */
    fun folder(columns: Int, shade: Boolean): List<String> {
        val fill = if (shade) 'm' else 'M'
        return listOf(
            "o".repeat(columns),
            "o" + fill.toString().repeat(columns - 3) + "So",
            "o".repeat(columns),
        )
    }
    val folderPalette: Map<Char, Color> = mapOf('o' to outline, 'M' to hex(0xEED39A), 'm' to hex(0xDDB977), 'S' to hex(0xB0553A))

    /** A hanging court banner (screen 6): gold rod, burgundy cloth, gold trim and heart, swallow-tail hem. */
    val banner = listOf(
        "GGGGGGGGGGG",
        ".oRRRRRRRo.",
        ".oRgggggRo.",
        ".oRRRRRRRo.",
        ".oRRcRcRRo.",
        ".oRcccccRo.",
        ".oRcccccRo.",
        ".oRRcccRRo.",
        ".oRRRcRRRo.",
        ".oRRRRRRRo.",
        ".oRgggggRo.",
        ".oRRRRRRRo.",
        ".oRRRRRRRo.",
        ".oRRRoRRRo.",
        ".oRRo.oRRo.",
        ".oRo...oRo.",
        ".oo.....oo.",
    )
    /** The banner stretched to `length` rows (the plain cloth repeats above the hem). */
    fun banner(length: Int): List<String> {
        val head = banner.take(12)
        val tail = banner.takeLast(4)
        val filler = ".oRRRRRRRo."
        val n = maxOf(0, length - head.size - tail.size)
        return head + List(n) { filler } + tail
    }

    val bannerPalette: Map<Char, Color> = mapOf(
        'o' to outline, 'G' to hex(0xCA9858), 'R' to hex(0x8A2C2A), 'g' to hex(0xCA9858), 'c' to hex(0xE9B872),
    )

    /** Wall candle: three flame frames over the same holder. */
    fun candle(frame: Int): List<String> {
        val flames = listOf(
            listOf("..f..", ".fYf.", ".fYf."),
            listOf(".f...", ".fYf.", "..Yf."),
            listOf("...f.", ".fYf.", ".fY.."),
        )[((frame % 3) + 3) % 3]
        return flames + listOf(
            "..w..",
            ".oWo.",
            ".oWo.",
            ".oWo.",
            "oGGGo",
            ".oGo.",
        )
    }
    val candlePalette: Map<Char, Color> = mapOf(
        'o' to outline, 'f' to hex(0xF39C3A), 'Y' to hex(0xFFE7A0), 'w' to hex(0x3B2119), 'W' to hex(0xF7EBD6), 'G' to hex(0xCA9858),
    )

    /** The manga "cross vein" of mild annoyance (screen 1). */
    val vein = listOf(
        ".r.r.",
        "rr.rr",
        ".....",
        "rr.rr",
        ".r.r.",
    )
    val veinPalette: Map<Char, Color> = mapOf('r' to hex(0xE0443E))

    /** The heart palette for the floating hearts (screen 5) and banners. */
    val heartPalette: Map<Char, Color> = mapOf('c' to PleadBrandColor.coral, 'h' to hex(0xF7B7B9), 'd' to OnboardingPalette.burgundy)

    /** Small thought bubble (screen 4: the argument comes round again). */
    val bubble = listOf(
        "..ooooooo..",
        ".oWWWWWWWo.",
        "oWWWWWWWWWo",
        "oWWWWWWWWWo",
        "oWWWWWWWWWo",
        ".oWWWWWWWo.",
        "..ooooWoo..",
        "......oWo..",
        ".......oo..",
    )
    val bubblePalette: Map<Char, Color> = mapOf('o' to outline, 'W' to hex(0xFFFDFC))
}

/** The desk's first front row keeps its outline edges. */
private fun String.replacingFirstAndLast(): String {
    if (length <= 1) return this
    val a = toCharArray()
    a[0] = 'o'; a[a.size - 1] = 'o'
    return String(a)
}
