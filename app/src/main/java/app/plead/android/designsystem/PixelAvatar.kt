// Plead — pixel avatar renderer (16×16 grid), driven by `Avatar` from Models.kt.
// Port of ArgueWin/DesignSystem/PixelAvatar.swift. The API `PixelAvatarView(avatar, size)` and
// `PixelAvatar.grid(avatar)` are the contract the courtroom consumes. Pixel maps and palettes are verbatim.
package app.plead.android.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.plead.android.models.Avatar
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object PixelAvatar {
    const val side = 16

    /** Palette entry indices used in the grid. */
    enum class Px { none, skin, hair, top, topDark, eye, mouth, collar, tie, blush }

    /** Row-major 16×16 grid for the avatar (Swift `grid(for:)`). */
    fun grid(a: Avatar): List<List<Color?>> {
        val g = Array(side) { Array(side) { Px.none } }
        fun set(r: Int, cs: IntRange, p: Px) {
            if (r !in 0 until side) return
            for (c in cs) if (c in 0 until side) g[r][c] = p
        }
        fun put(r: Int, c: Int, p: Px) {
            if (r in 0 until side && c in 0 until side) g[r][c] = p
        }

        // Body / outfit (rows 11–15)
        when (a.outfit) {
            Avatar.Outfit.tee -> {
                set(11, 5..10, Px.top); set(12, 4..11, Px.top); set(13, 4..11, Px.top); set(14, 4..11, Px.top); set(15, 4..11, Px.top)
            }
            Avatar.Outfit.hoodie -> {
                set(11, 4..11, Px.top); set(12, 3..12, Px.top); set(13, 3..12, Px.top); set(14, 3..12, Px.top); set(15, 3..12, Px.top)
                set(11, 6..9, Px.topDark) // hood opening
                set(14, 6..9, Px.topDark) // pocket
            }
            Avatar.Outfit.shirt -> {
                set(11, 5..10, Px.top); set(12, 4..11, Px.top); set(13, 4..11, Px.top); set(14, 4..11, Px.top); set(15, 4..11, Px.top)
                put(11, 6, Px.collar); put(11, 9, Px.collar); put(12, 7, Px.collar); put(12, 8, Px.collar)
                put(13, 8, Px.topDark); put(14, 8, Px.topDark) // button line
            }
            Avatar.Outfit.dress -> {
                set(11, 5..10, Px.top); set(12, 5..10, Px.top); set(13, 4..11, Px.top); set(14, 3..12, Px.top); set(15, 2..13, Px.top)
                put(12, 7, Px.topDark); put(12, 8, Px.topDark)
            }
            Avatar.Outfit.suit -> {
                set(11, 4..11, Px.topDark); set(12, 3..12, Px.topDark); set(13, 3..12, Px.topDark); set(14, 3..12, Px.topDark); set(15, 3..12, Px.topDark)
                put(11, 7, Px.collar); put(11, 8, Px.collar); put(12, 7, Px.collar); put(12, 8, Px.collar)
                put(13, 7, Px.tie); put(13, 8, Px.tie); put(14, 7, Px.tie); put(14, 8, Px.tie)
            }
        }
        // Neck
        set(10, 7..8, Px.skin)
        // Head (rows 3–9, cols 4–11)
        for (r in 3..9) set(r, 4..11, Px.skin)
        set(3, 5..10, Px.skin); put(3, 4, Px.none); put(3, 11, Px.none)
        set(9, 5..10, Px.skin); put(9, 4, Px.none); put(9, 11, Px.none)
        // Face
        put(6, 6, Px.eye); put(6, 9, Px.eye)
        set(8, 7..8, Px.mouth)
        put(7, 5, Px.blush); put(7, 10, Px.blush)
        // Hair
        when (a.hairstyle) {
            Avatar.Hairstyle.buzz -> {
                set(2, 5..10, Px.hair); set(3, 4..11, Px.hair)
            }
            Avatar.Hairstyle.short -> {
                set(1, 5..10, Px.hair); set(2, 4..11, Px.hair); set(3, 4..11, Px.hair); set(4, 4..5, Px.hair); set(4, 10..11, Px.hair); put(5, 4, Px.hair); put(5, 11, Px.hair)
            }
            Avatar.Hairstyle.long -> {
                set(1, 5..10, Px.hair); set(2, 4..11, Px.hair); set(3, 4..11, Px.hair)
                for (r in 4..11) { put(r, 3, Px.hair); put(r, 4, Px.hair); put(r, 11, Px.hair); put(r, 12, Px.hair) }
                set(12, 3..4, Px.hair); set(12, 11..12, Px.hair)
            }
            Avatar.Hairstyle.curly -> {
                set(0, 6..9, Px.hair); set(1, 4..11, Px.hair); set(2, 3..12, Px.hair); set(3, 3..12, Px.hair)
                set(4, 3..4, Px.hair); set(4, 11..12, Px.hair); set(5, 3..4, Px.hair); set(5, 11..12, Px.hair); put(6, 3, Px.hair); put(6, 12, Px.hair)
            }
            Avatar.Hairstyle.bun -> {
                set(0, 6..9, Px.hair); set(1, 5..10, Px.hair); set(2, 4..11, Px.hair); set(3, 4..11, Px.hair); put(4, 4, Px.hair); put(4, 11, Px.hair)
            }
            Avatar.Hairstyle.ponytail -> {
                set(1, 5..10, Px.hair); set(2, 4..11, Px.hair); set(3, 4..11, Px.hair); put(4, 4, Px.hair); put(4, 11, Px.hair)
                for (r in 4..10) put(r, 12, Px.hair); put(11, 12, Px.hair); put(11, 13, Px.hair)
            }
        }

        val skin = Color(hex = Avatar.skinTones[clamp(a.skin, Avatar.skinTones.size)])
        val hair = Color(hex = Avatar.hairColours[clamp(a.hair, Avatar.hairColours.size)])
        val topHex = Avatar.topColours[clamp(a.top, Avatar.topColours.size)]
        val top = Color(hex = topHex)
        val topDark = Color(hex = darken(topHex))
        return g.map { row ->
            row.map { px ->
                when (px) {
                    Px.none -> null
                    Px.skin -> skin
                    Px.hair -> hair
                    Px.top -> top
                    Px.topDark -> topDark
                    Px.eye -> Color(hex = 0x2A1310)
                    Px.mouth -> Color(hex = 0x8A2C2A)
                    Px.collar -> Color(hex = 0xFFFDFC)
                    Px.tie -> Color(hex = 0x8A2C2A)
                    Px.blush -> Color(hex = 0xEB9996, opacity = 0.7f)
                }
            }
        }
    }

    fun clamp(i: Int, n: Int): Int = max(0, min(n - 1, i))

    fun darken(hex: Int): Int {
        val r = (((hex shr 16) and 0xFF) * 0.65).toInt()
        val g = (((hex shr 8) and 0xFF) * 0.65).toInt()
        val b = ((hex and 0xFF) * 0.65).toInt()
        return (r shl 16) or (g shl 8) or b
    }

    val outlineColor = Color(hex = 0x2A1310, opacity = 0.85f)

    /** Empty cells that touch a filled cell (4-neighbourhood): the sprite's outline (Swift `outline(of:)`). */
    fun outline(grid: List<List<Color?>>): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        val n = grid.size
        fun filled(rr: Int, cc: Int): Boolean {
            if (rr < 0 || rr >= n || cc < 0 || cc >= n) return false
            return grid[rr][cc] != null
        }
        for (r in 0 until n) {
            for (c in 0 until n) {
                if (grid[r][c] != null) continue
                if (filled(r - 1, c) || filled(r + 1, c) || filled(r, c - 1) || filled(r, c + 1)) out.add(r to c)
            }
        }
        return out
    }

    /** TalkBack description ("Pixel avatar: curly hair, hoodie"), Swift `description(of:)`. */
    fun description(a: Avatar): String =
        "Pixel avatar: ${a.hairstyle.title.lowercase()} hair, ${a.outfit.title.lowercase()}"

    /**
     * Cell edge and inset (in the canvas's units) for a square canvas `width` wide: cells snap to whole units
     * when that leaves ≥ 2 per cell, otherwise they are fractional so small badges still fill their frame.
     */
    fun cellLayout(width: Float): Pair<Float, Float> {
        val n = side.toFloat()
        val raw = width / n
        val cell = if (raw >= 2) floor(raw) else raw
        val inset = (width - cell * n) / 2
        return cell to inset
    }
}

val Avatar.Hairstyle.title: String
    get() = when (this) {
        Avatar.Hairstyle.buzz -> "Buzz"
        Avatar.Hairstyle.short -> "Short"
        Avatar.Hairstyle.long -> "Long"
        Avatar.Hairstyle.curly -> "Curly"
        Avatar.Hairstyle.bun -> "Bun"
        Avatar.Hairstyle.ponytail -> "Ponytail"
    }

val Avatar.Outfit.title: String
    get() = when (this) {
        Avatar.Outfit.tee -> "Tee"
        Avatar.Outfit.hoodie -> "Hoodie"
        Avatar.Outfit.shirt -> "Shirt"
        Avatar.Outfit.dress -> "Dress"
        Avatar.Outfit.suit -> "Suit"
    }

/**
 * Crisp pixel avatar. [size] is the rendered square edge. A one-pixel dark outline is traced around the
 * silhouette so the sprite reads on both cream UI surfaces and the dark courtroom. Cells snap to whole dp when
 * the size allows (≥ 32 dp), exactly as iOS snaps to whole points, otherwise they are fractional.
 */
@Composable
fun PixelAvatarView(avatar: Avatar, modifier: Modifier = Modifier, size: Dp = 64.dp, outlined: Boolean = true) {
    val grid = remember(avatar) { PixelAvatar.grid(avatar) }
    val outline = remember(grid, outlined) { if (outlined) PixelAvatar.outline(grid) else emptyList() }
    val description = PixelAvatar.description(avatar)
    Canvas(
        modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = description },
    ) {
        // Lay the grid out in dp (iOS points), then scale to px, so the snapping matches iOS.
        val (cellDp, insetDp) = PixelAvatar.cellLayout(this.size.width / density)
        val cell = cellDp * density
        val inset = insetDp * density
        // Cell edges snapped to whole device pixels (iOS draws on whole pixels at @3x), so neighbouring cells meet
        // exactly with no antialiased seams; the geometry stays within half a pixel of the iOS points.
        fun edge(i: Int): Float = kotlin.math.round(inset + i * cell)
        fun fill(r: Int, c: Int, color: Color) {
            val x = edge(c)
            val y = edge(r)
            drawRect(color = color, topLeft = Offset(x, y), size = Size(edge(c + 1) - x, edge(r + 1) - y))
        }
        for ((r, c) in outline) fill(r, c, PixelAvatar.outlineColor)
        grid.forEachIndexed { r, row ->
            row.forEachIndexed { c, color -> if (color != null) fill(r, c, color) }
        }
    }
}

@Preview(name = "Avatars")
@Composable
private fun PixelAvatarPreview() {
    Row(Modifier.background(PleadColor.cream).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val outfits = Avatar.Outfit.entries
        Avatar.Hairstyle.entries.forEachIndexed { i, h ->
            PixelAvatarView(Avatar(skin = i, hair = i, hairstyle = h, top = i, outfit = outfits[i % outfits.size]), size = 48.dp)
        }
    }
}
