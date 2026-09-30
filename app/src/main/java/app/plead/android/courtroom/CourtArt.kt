// Port of ArgueWin/Courtroom/CourtArt.swift: the static courtroom art layer (spec v2 §5 "Static scene architecture").
//   CourtroomBackground  one 9:19.5 pixel-art painting (drawable `courtroom_background`), aspect-filled
//   CourtroomZones       unit rects of the painted furniture, mapped into any container size (dp)
//   JudgeSprite          static pixel judge, persona-tinted, sat on the bench
//   CourtNameplate       "Case #14 · Judge Wigsworth" plate over the bench plaque
// Everything native (bubbles, exhibit card, dock, overlays) is positioned from `CourtroomZones`.
@file:Suppress("ObjectPropertyName")

package app.plead.android.courtroom

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.plead.android.R
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.PleadColor
import app.plead.android.models.JudgePersona
import app.plead.android.models.Role
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

// MARK: - Zones

/**
 * Where things are painted in the background, as unit rects of the artwork (0...1 in both axes), plus the aspect-fill
 * mapping into a container. Measured on the 1170×2532 asset. All values in dp.
 */
data class CourtroomZones(val size: Size) {
    /** Frame of the whole artwork in container coordinates (aspect-fill, may overflow). */
    val art: Rect

    init {
        val w = max(size.width, 1f)
        val h = max(size.height, 1f)
        val scale = max(w / artAspect, h)            // art height in points
        val artW = scale * artAspect
        val artH = scale
        art = Rect(offset = Offset((w - artW) / 2f, (h - artH) * anchorY), size = Size(artW, artH))
    }

    fun rect(unit: Rect): Rect = Rect(
        offset = Offset(art.left + unit.left * art.width, art.top + unit.top * art.height),
        size = Size(unit.width * art.width, unit.height * art.height),
    )

    fun x(u: Float): Float = art.left + u * art.width
    fun y(u: Float): Float = art.top + u * art.height

    // Derived placements used by the scene.
    val judgeCell: Float get() = max(2f, floor(art.width * 0.155f / JudgeSprite.columns.toFloat()))
    val judgeSpriteSize: Size get() = JudgeSprite.size(judgeCell)

    /** Bottom of the judge sprite = bench top (a few points into the bench so no gap shows). */
    val judgeFrame: Rect
        get() {
            val s = judgeSpriteSize
            return Rect(offset = Offset(x(0.5f) - s.width / 2f, y(bench.top) + 2f - s.height), size = s)
        }

    val avatarSize: Float get() = (art.width * 0.185f).roundToInt().toFloat()

    fun avatarFrame(r: Role): Rect {
        val p = rect(podium(r))
        val s = avatarSize
        return Rect(offset = Offset(p.center.x - s / 2f, p.top + s * 0.06f - s), size = Size(s, s))
    }

    companion object {
        const val artAspect: Float = 1170f / 2532f

        /** Vertical crop anchor when the screen is shorter than 9:19.5 (0 = keep top, 1 = keep bottom). */
        const val anchorY: Float = 0.35f

        private fun r(x: Float, y: Float, w: Float, h: Float) = Rect(offset = Offset(x, y), size = Size(w, h))

        // Painted furniture (unit rects of the artwork).
        val banner = r(0.333f, 0.047f, 0.334f, 0.257f)

        /** Empty judge's chair; the sprite sits in it with its bottom on the bench top. */
        val judgeChair = r(0.428f, 0.277f, 0.144f, 0.078f)
        val bench = r(0.337f, 0.356f, 0.326f, 0.089f)

        /** The blank brass plaque on the bench front. */
        val plaque = r(0.41f, 0.395f, 0.18f, 0.038f)
        val standsLeft = r(0.0f, 0.27f, 0.23f, 0.13f)
        val standsRight = r(0.77f, 0.27f, 0.23f, 0.13f)

        /** The easel's blank board (legs continue to y ≈ 0.636). */
        val easel = r(0.342f, 0.46f, 0.316f, 0.107f)
        val plaintiffPodium = r(0.043f, 0.551f, 0.261f, 0.124f)
        val defendantPodium = r(0.697f, 0.551f, 0.26f, 0.124f)

        /** Bottom band (pews and carpet) the dock may cover. */
        val dock = r(0.0f, 0.69f, 1.0f, 0.31f)

        fun podium(r: Role): Rect = if (r == Role.plaintiff) plaintiffPodium else defendantPodium
    }
}

// MARK: - Background

/**
 * The painted courtroom, aspect-filled into `size` using `CourtroomZones`' mapping. Falls back to a drawn pixel
 * backdrop if the asset is missing.
 */
@Composable
fun CourtroomBackground(size: Size, modifier: Modifier = Modifier, closed: Boolean = false) {
    val z = CourtroomZones(size)
    val image = CourtroomBackground.image()
    Box(
        modifier
            .size(size.width.dp, size.height.dp)
            .clipToBounds()
            .clearAndSetSemantics { },
    ) {
        if (image != null) {
            Canvas(Modifier.fillMaxSize()) {
                drawImage(
                    image,
                    dstOffset = IntOffset(z.art.left.dp.toPx().roundToInt(), z.art.top.dp.toPx().roundToInt()),
                    dstSize = IntSize(z.art.width.dp.toPx().roundToInt(), z.art.height.dp.toPx().roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        } else {
            CourtroomBackdrop(
                Modifier
                    .offset(z.art.left.dp, z.art.top.dp)
                    .size(z.art.width.dp, z.art.height.dp),
            )
        }
        if (closed) {
            // Lamps out: cool the room and drop it into shadow, keep a little warmth up top.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(PleadColor.cocoa.copy(alpha = 0.55f), PleadColor.cocoa.copy(alpha = 0.78f)))),
            )
        }
    }
}

object CourtroomBackground {
    /** The painting (`CourtroomBackground.imageset` → `drawable-nodpi/courtroom_background.png`). */
    @Composable
    fun image(): ImageBitmap? = ImageBitmap.imageResource(R.drawable.courtroom_background)
}

/**
 * Fallback pixel backdrop in the same zone layout (used only if the painted asset is absent). Chunky 64×140 grid.
 */
@Composable
fun CourtroomBackdrop(modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val cols = 64f
        val rows = 140f
        val cw = size.width / cols
        val ch = size.height / rows
        fun block(c0: Float, r0: Float, c1: Float, r1: Float, color: Color) {
            drawRect(color, topLeft = Offset(c0 * cw, r0 * ch), size = Size((c1 - c0) * cw, (r1 - r0) * ch))
        }
        block(0f, 0f, 64f, 140f, PleadColor.courtBackdrop)
        // Wall panels, dithered
        var r = 0f
        while (r < 77f) {
            var c = (r / 2f) % 2f
            while (c < 64f) {
                block(c, r, c + 1, r + 1, PleadColor.mahogany.copy(alpha = 0.5f))
                c += 2f
            }
            r += 2f
        }
        block(21f, 6f, 43f, 42f, PleadColor.burgundy)                     // banner
        block(22f, 7f, 42f, 8f, PleadColor.gold.copy(alpha = 0.6f))
        block(31f, 12f, 33f, 30f, PleadColor.gold); block(25f, 14f, 39f, 15f, PleadColor.gold)
        block(24f, 20f, 29f, 21f, PleadColor.gold); block(35f, 20f, 40f, 21f, PleadColor.gold)
        for (lamp in listOf(8f, 54f)) {                                   // lamps
            block(lamp, 3f, lamp + 3, 6f, PleadColor.gold); block(lamp - 2, 6f, lamp + 5, 9f, Color(hex = 0xFFD9A8))
        }
        block(0f, 38f, 15f, 56f, PleadColor.walnut); block(49f, 38f, 64f, 56f, PleadColor.walnut)   // stands
        var c = 1f
        while (c < 14f) {
            block(c, 40f, c + 2, 43f, PleadColor.blush); block(c + 48, 40f, c + 50, 43f, PleadColor.terracotta)
            c += 3f
        }
        block(27f, 39f, 37f, 50f, PleadColor.burgundy)                    // chair
        block(21f, 50f, 43f, 63f, PleadColor.walnut); block(21f, 50f, 43f, 51f, PleadColor.gold.copy(alpha = 0.5f))   // bench
        block(0f, 63f, 64f, 97f, Color(hex = 0x6B3A2A))                   // floor
        block(22f, 64f, 42f, 79f, PleadColor.parchment); block(25f, 79f, 26f, 89f, PleadColor.walnut); block(38f, 79f, 39f, 89f, PleadColor.walnut)
        block(3f, 77f, 19f, 95f, PleadColor.walnut); block(45f, 77f, 61f, 95f, PleadColor.walnut)   // podiums
        block(26f, 97f, 38f, 140f, PleadColor.courtCarpet)                // carpet
        block(0f, 97f, 25f, 140f, PleadColor.mahogany); block(39f, 97f, 64f, 140f, PleadColor.mahogany)
    }
}

// MARK: - Judge sprite

/** Draws a colour grid, one rect per filled cell (dp cell), pixel-crisp. */
internal fun DrawScope.drawCellGrid(grid: List<List<Color?>>, cellDp: Float, originX: Float = 0f, originY: Float = 0f) {
    val cell = cellDp * density
    grid.forEachIndexed { r, row ->
        row.forEachIndexed { c, color ->
            if (color != null) {
                drawRect(color, topLeft = Offset(originX + c * cell, originY + r * cell), size = Size(cell, cell))
            }
        }
    }
}

/**
 * Static pixel judge (16×18 grid + 1-cell dark outline), persona-tinted: Wigsworth white wig + burgundy robe; Blunt
 * reading glasses + dark cocoa; Sunny blush flower; Chaos rose accessory + sunglasses.
 */
@Composable
fun JudgeSprite(
    persona: JudgePersona,
    modifier: Modifier = Modifier,
    cell: Float = 4f,
    /** Motion frames (amendment x): blink and the open mouth of the talk loop. */
    eyesClosed: Boolean = false,
    mouthOpen: Boolean = false,
    /** Entrance walk cycle (amendment ac): 0 standing, 1 step left, 2 passing, 3 step right, 4 passing. */
    walkFrame: Int = 0,
) {
    val grid = JudgeSprite.grid(persona, eyesClosed = eyesClosed, mouthOpen = mouthOpen, walkFrame = walkFrame)
    val bob = CourtWalkCycle.bob(walkFrame)
    val s = JudgeSprite.size(cell)
    Canvas(
        modifier
            .clearAndSetSemantics { contentDescription = "${persona.displayName}, at the bench" }
            .size(s.width.dp, s.height.dp)
            // The passing frames rise one art pixel (one cell).
            .offset(y = (-bob * cell).dp),
    ) {
        drawCellGrid(grid, cell)
    }
}

object JudgeSprite {
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

    private fun baseRows(p: JudgePersona): List<String> = when (p) {
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
            'S' to Color(hex = 0xF5C9A6), 'E' to PleadColor.cocoa, 'B' to PleadColor.blush.copy(alpha = 0.85f), 'M' to PleadColor.burgundy,
            'R' to PleadColor.burgundy, 'r' to PleadColor.mahogany, 'C' to PleadColor.paperWhite,
            'o' to Color(hex = 0x5A1A18),
        )
        when (p) {
            JudgePersona.wigsworth -> {
                c['W'] = Color(hex = 0xF7F2EA); c['w'] = Color(hex = 0xD9CDC1)
            }
            JudgePersona.blunt -> {
                c['S'] = Color(hex = 0xD9A377); c['H'] = Color(hex = 0x3B2119); c['G'] = Color(hex = 0x1E1410)
                c['M'] = Color(hex = 0x6E3A2A); c['R'] = Color(hex = 0x3D1F18); c['r'] = PleadColor.cocoa
                c['C'] = PleadColor.parchment
            }
            JudgePersona.sunny -> {
                c['H'] = Color(hex = 0x8A4B2A); c['F'] = PleadColor.blush; c['Y'] = PleadColor.gold; c['C'] = Color(hex = 0xF6CFCB)
            }
            JudgePersona.chaos -> {
                c['S'] = Color(hex = 0xFFE0C8); c['P'] = Color(hex = 0xC24E6E); c['K'] = Color(hex = 0x1E1410)
                c['O'] = Color(hex = 0xE0607E); c['L'] = PleadColor.success
            }
        }
        return c
    }

    /**
     * The persona's rows with the motion frames applied: eyes shut ('E' → skin; glasses stay) and / or the mouth open
     * (the row under the lowest face 'M' gets two mouth cells in the middle).
     */
    fun rows(p: JudgePersona, eyesClosed: Boolean, mouthOpen: Boolean, walkFrame: Int = 0): List<List<Char>> {
        val src = baseRows(p).map { it.toMutableList() }.toMutableList()
        // Walk steps: the robe's hem (last two rows) swings one cell towards the stepping side.
        val dir = CourtWalkCycle.step(walkFrame)
        if (dir != null && src.size >= 2) {
            for (r in (src.size - 2) until src.size) src[r] = CourtWalkCycle.shift(src[r], by = dir, empty = '.').toMutableList()
        }
        val face = 0 until minOf(src.size, 12)
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
        cache[key]?.let { return it }
        val g = grid(rows(p, eyesClosed = eyesClosed, mouthOpen = mouthOpen, walkFrame = walk), palette(p))
        cache[key] = g
        return g
    }

    /** Builds the walk frames (and the standing / blink / talk ones) before the court is interactive. */
    fun preload(p: JudgePersona) {
        for (w in 0..CourtEntrancePose.walkFrames) {
            for (eyes in listOf(false, true)) for (mouth in listOf(false, true)) grid(p, eyesClosed = eyes, mouthOpen = mouth, walkFrame = w)
        }
    }

    /** Colour grid including the dark outline (row-major, `rows` × `columns`). */
    fun grid(p: JudgePersona): List<List<Color?>> = grid(baseRows(p).map { it.toList() }, palette(p))

    private fun grid(src: List<List<Char>>, pal: Map<Char, Color>): List<List<Color?>> {
        val h = src.size
        val w = 16
        val out = Array(rows) { arrayOfNulls<Color>(columns) }
        fun filled(r: Int, c: Int): Boolean {
            if (r < 0 || r >= h || c < 0 || c >= w) return false
            return src[r][c] != '.'
        }
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
        return out.map { it.toList() }
    }
}

// MARK: - Walk cycle

/**
 * The entrance walk cycle shared by the judge and the parties (amendment ac): four frames at 8 fps — 1 step left
 * (the hem swings a cell left), 2 passing (the figure rises one art pixel), 3 step right, 4 passing — and 0, the
 * standing pose. Whole cells only (pixel-crisp).
 */
object CourtWalkCycle {
    /** 0 (standing) or 1…4. */
    fun normalized(f: Int): Int = if (f <= 0) 0 else (f - 1) % CourtEntrancePose.walkFrames + 1

    /** The hem's swing: -1 left, +1 right, null for standing / passing. */
    fun step(f: Int): Int? = when (normalized(f)) {
        1 -> -1
        3 -> 1
        else -> null
    }

    /** Cells the figure rises on this frame. */
    fun bob(f: Int): Int = if (normalized(f) == 2 || normalized(f) == 4) 1 else 0

    /** A row shifted one cell (`by` −1 / +1), padded with `empty`. */
    fun <T> shift(row: List<T>, by: Int, empty: T): List<T> {
        if (row.isEmpty() || by == 0) return row
        return if (by < 0) row.drop(1) + empty else listOf(empty) + row.dropLast(1)
    }
}

// MARK: - Nameplate

/** Mahogany plate on the bench front: tiny gold scales + "Case #14 · Judge Wigsworth" in serif. */
@Composable
fun CourtNameplate(caseNumber: Int, persona: JudgePersona, modifier: Modifier = Modifier) {
    val shape = remember { RoundedCornerShape(6.dp) }
    DynamicTypeCap(DynamicTypeSize.large) {
        Row(
            modifier
                .clearAndSetSemantics { contentDescription = "Case $caseNumber. ${persona.displayName} presiding." }
                .background(PleadColor.mahogany, shape)
                .drawWithContent {
                    drawContent()
                    strokeBorder(6.dp.toPx(), inset = 2.dp.toPx(), width = 1.dp.toPx(), color = PleadColor.gold.copy(alpha = 0.75f))
                    strokeBorder(6.dp.toPx(), inset = 0f, width = 1.dp.toPx(), color = PleadColor.cocoa)
                }
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScalesGlyph(size = 11.dp)
            ScaledText(
                "Case #$caseNumber · ${persona.displayName}",
                style = CourtFont.judgeName,
                color = PleadColor.cream,
                minimumScaleFactor = 0.7f,
                maxLines = 1,
            )
        }
    }
}
