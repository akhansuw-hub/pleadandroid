// Port of ArgueWin/Features/Paywall/PixelGlyphs.swift.
package app.plead.android.features.paywall

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.Color
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A tiny pixel sprite: rows of palette characters ("." is transparent). */
data class PixelSprite(val rows: List<String>) {
    val width: Int get() = rows.maxOfOrNull { it.length } ?: 1
    val height: Int get() = rows.size
}

/**
 * Draws a [PixelSprite] crisply: every cell is snapped to whole device pixels so there are no seams
 * or blurred edges at any size.
 */
@Composable
fun PixelGlyph(sprite: PixelSprite, modifier: Modifier = Modifier) {
    Canvas(
        modifier
            .aspectRatio(sprite.width.toFloat() / sprite.height.toFloat())
            .clearAndSetSemantics { },
    ) {
        // DrawScope works in device pixels already: `displayScale` is 1 here.
        val w = sprite.width.toFloat()
        val h = sprite.height.toFloat()
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

/** Sprite palette, all from `PaywallPalette` plus two skin tones for the couple. */
object PixelInk {
    fun color(ch: Char): Color? = when (ch) {
        'k' -> PaywallPalette.darkCocoa
        'm' -> PaywallPalette.mahogany
        'w' -> Color(hex = 0x9A6444)            // light wood (mahogany highlight)
        'b' -> PaywallPalette.courtBurgundy
        'd' -> PaywallPalette.deepWine
        'r' -> PaywallPalette.coral
        'p' -> PaywallPalette.romanceBlush
        's' -> PaywallPalette.softRose
        'g' -> PaywallPalette.courtGold
        'G' -> PaywallPalette.goldLight
        'P' -> PaywallPalette.paperWhite
        'c' -> PaywallPalette.parchment
        'f' -> Color(hex = 0xF7D2B6)            // skin
        'e' -> Color(hex = 0xD9A07E)            // skin shade
        'y' -> Color(hex = 0xF0C46A)            // blonde hair
        'h' -> Color(hex = 0x4A2C22)            // dark hair
        'u' -> Color(hex = 0x4F6FA8)            // hoodie blue
        'W' -> Color.White
        else -> null
    }
}

/**
 * The four perk icons. Drawables named `pixel_gavel` etc. replace the drawn sprites automatically
 * when they're added to `res/drawable-nodpi` — no screen changes needed.
 */
enum class PerkIcon {
    gavel, evidence, aiJudge, couple;

    val assetName: String
        get() = when (this) {
            gavel -> "pixel_gavel"
            evidence -> "pixel_evidence"
            aiJudge -> "pixel_ai_judge"
            couple -> "pixel_couple"
        }

    val sprite: PixelSprite
        get() = when (this) {
            gavel -> PaywallSprites.gavel
            evidence -> PaywallSprites.evidence
            aiJudge -> PaywallSprites.robotJudge
            couple -> PaywallSprites.couple
        }
}

@SuppressLint("DiscouragedApi")
@Composable
fun PerkIconView(icon: PerkIcon, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val resources = LocalResources.current
    // Swift `UIImage(named:)`: the asset by name when it exists, else the drawn sprite.
    val image: ImageBitmap? = remember(icon, resources) {
        val id = resources.getIdentifier(icon.assetName, "drawable", context.packageName)
        if (id == 0) null else runCatching { ImageBitmap.imageResource(resources, id) }.getOrNull()
    }
    if (image != null) {
        Image(
            painter = remember(image) { BitmapPainter(image, filterQuality = FilterQuality.None) },
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.clearAndSetSemantics { },
        )
    } else {
        PixelGlyph(sprite = icon.sprite, modifier = modifier)
    }
}

object PaywallSprites {
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

@Preview(name = "Pixel glyphs")
@Composable
private fun PixelGlyphsPreview() {
    Row(
        Modifier
            .background(PaywallPalette.warmCream)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PerkIcon.entries.forEach { PerkIconView(it, Modifier.size(44.dp)) }
        PixelGlyph(PaywallSprites.scales, Modifier.size(44.dp))
        PixelGlyph(PaywallSprites.calendar, Modifier.size(44.dp))
    }
}
