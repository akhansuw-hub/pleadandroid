// Port of ArgueWin/DesignSystem/PleadWordmark.swift.
//
// The Plead logo (CONTRACTS-v2 Amendment k, straight wordmark since Amendment w / docs/brand/new-logo-reference.png):
// the traced straight-baseline "Plead" wordmark in Deep Burgundy with a small Soft Coral pixel heart above the
// gap between the "l" and the "e"; no scales (they live on as a separate courtroom symbol, `plead_scales`).
// Asset-backed (`res/drawable-nodpi/plead_wordmark*.png`, the @3x iOS assets), never redrawn; a Compose fallback
// only renders if the asset cannot be decoded.
package app.plead.android.designsystem

import android.content.res.Resources
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.plead.android.R
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The logo's constants and geometry (Swift `PleadLogo` statics). All lengths are points (= dp) as Float.
 *
 * - `width`: rendered width of the mark's canvas (the asset is 220×110 pt with the wordmark 200 pt wide inside
 *   it, ≈ 4.5% side / ≈ 7% top-bottom transparent margin). null = the asset's natural 220 pt.
 * - `opticalOffset`: horizontal nudge as a fraction of `width` applied to the mark only (the strapline stays
 *   geometrically centred): −1% reads as centred.
 * - Clear space: keep at least `clearSpace(width)` (≈ the lowercase "e" height) free around the ink.
 */
object PleadLogo {
    enum class Variant {
        /** Deep Burgundy wordmark + Soft Coral pixel heart, on Warm Cream / paper. The default logo. */
        primary,

        /** No heart: tight or minimal placements. */
        wordmarkOnly,

        /** Warm Cream wordmark + Romance Blush heart, on a burgundy block. */
        reversed,
    }

    const val straplineText = "A HAPPIER KIND OF DEBATE"
    const val naturalWidth: Float = 220f
    const val defaultOpticalOffset: Float = -0.01f

    /** The iOS asset name (Swift `assetName(_:)`). */
    fun assetName(variant: Variant): String = when (variant) {
        Variant.primary -> "PleadWordmark"
        Variant.wordmarkOnly -> "PleadWordmarkOnly"
        Variant.reversed -> "PleadWordmarkReversed"
    }

    /** The Android drawable for [variant] (`assetName` in snake case, res/drawable-nodpi). */
    fun drawable(variant: Variant): Int = when (variant) {
        Variant.primary -> R.drawable.plead_wordmark
        Variant.wordmarkOnly -> R.drawable.plead_wordmark_only
        Variant.reversed -> R.drawable.plead_wordmark_reversed
    }

    /** Asset height / width (220×110, or 220×94 without the heart row). */
    fun markAspect(variant: Variant): Float = if (variant == Variant.wordmarkOnly) 94f / 220f else 110f / 220f

    /** Transparent margin under the ink (the serifs and the d's tail) as a fraction of width. */
    fun bottomMargin(variant: Variant): Float = if (variant == Variant.wordmarkOnly) 10.9f / 220f else 7.4f / 220f

    /** The lowercase "e" height (x-height of the traced wordmark) for a mark `width` wide: the clear-space unit. */
    fun clearSpace(width: Float): Float = width * 41f / 220f

    fun straplineSize(width: Float): Float = max(10f, width * 0.05f)

    /** Gap from the bottom of the ink to the top of the strapline. */
    fun straplineGap(width: Float): Float = width * 0.045f

    /** Total height of the lockup (mark canvas + strapline) for a given width; used by `PleadLogoPlacement`. */
    fun height(variant: Variant = Variant.primary, strapline: Boolean, width: Float): Float {
        val mark = width * markAspect(variant)
        if (!strapline) return mark
        return mark - width * bottomMargin(variant) + straplineGap(width) + straplineSize(width) * 1.2f
    }

    /**
     * The heart's pixels in the primary / reversed canvas, as fractions of the canvas (220×110 pt): the 7×6 grid
     * at 5 pt per art pixel, 35×30 pt at (94.67, 7.33), measured from the @3x asset.
     */
    val heartFrame = Rect(Offset(94.67f / 220f, 7.33f / 110f), Size(35f / 220f, 30f / 110f))

    /**
     * `heartFrame` padded by 1 pt on every side: the bounds of `heartMask` and the heart's scale anchor. The top
     * of the "l" reaches into this rect's lower-left corner, so layers are cut with the heart-shaped
     * `heartMask`, never with this rect.
     */
    val heartCell = Rect(Offset(93.67f / 220f, 6.33f / 110f), Size(37f / 220f, 32f / 110f))

    /** The heart's silhouette (each row's run of `PleadPixelArt.heart`) padded by 1 pt, for a canvas `size`. */
    fun heartMask(size: Size): Path {
        val f = Rect(
            Offset(heartFrame.left * size.width, heartFrame.top * size.height),
            Size(heartFrame.width * size.width, heartFrame.height * size.height),
        )
        val cols = (PleadPixelArt.heart.maxOfOrNull { it.length } ?: 7).toFloat()
        val u = f.width / cols
        val pad = size.width / naturalWidth
        val path = Path()
        PleadPixelArt.heart.forEachIndexed { r, row ->
            val first = row.indexOfFirst { it != '.' }
            val last = row.indexOfLast { it != '.' }
            if (first < 0 || last < 0) return@forEachIndexed
            path.addRect(
                Rect(
                    Offset(f.left + first * u - pad, f.top + r * u - pad),
                    Size((last - first + 1) * u + 2 * pad, u + 2 * pad),
                ),
            )
        }
        return path
    }
}

/**
 * The Plead logo lockup. Parameters as Swift `PleadLogo`:
 * - [image]: a pre-decoded asset (the cold open's frame cache); otherwise loaded from `res`.
 * - [heartScale]: layered rendering (Amendment q, the paywall opening). null = the flat asset. A value draws the
 *   wordmark and the pixel heart as two layers cut from the SAME asset (so the settled result is pixel-identical
 *   to the flat logo) and applies this to the heart only, as scale and opacity together: 0 = no heart,
 *   1 = settled. Values past 1 (a spring's overshoot) grow it without over-brightening.
 * - [markOpacity]: opacity of the mark only; the strapline is unaffected.
 * - [onMarkBounds]: Swift `reportsMarkBounds` + `PleadLogoMarkAnchorKey`: receives the mark canvas's bounds in the
 *   root (before `opticalOffset`), so another view can land an identical mark on exactly this frame.
 */
@Composable
fun PleadLogo(
    modifier: Modifier = Modifier,
    variant: PleadLogo.Variant = PleadLogo.Variant.primary,
    strapline: Boolean = false,
    width: Dp? = null,
    opticalOffset: Float = PleadLogo.defaultOpticalOffset,
    image: ImageBitmap? = null,
    heartScale: Double? = null,
    markOpacity: Double = 1.0,
    onMarkBounds: ((Rect) -> Unit)? = null,
) {
    val w = width?.value ?: PleadLogo.naturalWidth
    val resources = LocalResources.current
    val logo = image ?: remember(variant, resources) { loadLogo(resources, variant) }
    val label = if (strapline) "Plead. ${capitalized(PleadLogo.straplineText)}" else "Plead"
    val straplineColor = if (variant == PleadLogo.Variant.reversed) {
        PleadBrandColor.warmCream.copy(alpha = 0.9f)
    } else {
        PleadBrandColor.deepWine.copy(alpha = 0.86f)
    }
    val markHeight = w * PleadLogo.markAspect(variant)
    val topPad = PleadLogo.straplineGap(w) - w * PleadLogo.bottomMargin(variant)
    val straplineHeight = PleadLogo.straplineSize(w) * 1.2f

    Layout(
        modifier = modifier.clearAndSetSemantics {
            contentDescription = label
            role = Role.Image
        },
        content = {
            // Mark (offset by the optical nudge; the bounds are reported before it).
            Box(
                Modifier
                    .size(w.dp, markHeight.dp)
                    .then(
                        if (onMarkBounds != null) Modifier.onGloballyPositioned { onMarkBounds(it.boundsInRoot()) } else Modifier,
                    )
                    .offset(x = (opticalOffset * w).dp)
                    .alpha(markOpacity.toFloat()),
            ) {
                if (heartScale != null && variant != PleadLogo.Variant.wordmarkOnly && logo != null) {
                    PleadLogoLayers(image = logo, width = w.dp, heartScale = heartScale)
                } else if (logo != null) {
                    // `.interpolation(.high)`: the logo is smooth art, not pixel art.
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(
                            logo,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.High,
                        )
                    }
                } else {
                    PleadLogoFallback(variant = variant, width = w)
                }
            }
            if (strapline) {
                Text(
                    PleadLogo.straplineText,
                    style = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = fixedSp(PleadLogo.straplineSize(w)),
                        letterSpacing = fixedSp(w * 0.012f),
                    ),
                    color = straplineColor,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val mark = measurables[0].measure(loose.copy(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE))
        val text = if (measurables.size > 1) measurables[1].measure(loose.copy(maxWidth = Int.MAX_VALUE, maxHeight = Int.MAX_VALUE)) else null
        val totalWidth = max(mark.width, text?.width ?: 0)
        // The strapline is pulled up into the mark's transparent bottom margin (a negative top padding when
        // the gap is smaller than the margin, as SwiftUI allows).
        val textTop = mark.height + (topPad.dp).roundToPx()
        val textBlock = (straplineHeight.dp).roundToPx()
        val totalHeight = if (text != null) textTop + textBlock else mark.height
        layout(totalWidth, max(totalHeight, 0)) {
            mark.placeRelative((totalWidth - mark.width) / 2, 0)
            if (text != null) {
                // `.frame(height: size * 1.2)`: the text centred in its line box.
                text.placeRelative((totalWidth - text.width) / 2, textTop + (textBlock - text.height) / 2)
            }
        }
    }
}

private fun loadLogo(resources: Resources, variant: PleadLogo.Variant): ImageBitmap? =
    runCatching { ImageBitmap.imageResource(resources, PleadLogo.drawable(variant)) }.getOrNull()

/** Swift `String.capitalized`: every word's first letter upper-cased, the rest lower-cased. */
internal fun capitalized(text: String): String =
    text.split(" ").joinToString(" ") { word -> word.lowercase().replaceFirstChar { it.uppercase() } }

/**
 * The wordmark and the heart as separate layers of one asset, each drawn at the canvas's final size (so neither
 * is resampled when a container scales the lockup: scale the container, never the width). Swift `PleadLogo.Layers`.
 */
@Composable
fun PleadLogoLayers(image: ImageBitmap, width: Dp, heartScale: Double, modifier: Modifier = Modifier) {
    val height = width * PleadLogo.markAspect(PleadLogo.Variant.primary)
    val cell = PleadLogo.heartCell
    val anchor = TransformOrigin(cell.center.x, cell.center.y)
    Box(modifier.size(width, height)) {
        // Wordmark: the canvas with the heart's silhouette punched out.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
        ) {
            drawLogo(image)
            drawPath(PleadLogo.heartMask(size), color = Color.Black, blendMode = BlendMode.Clear)
        }
        // Heart: only its silhouette, scaled about the cell's centre.
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = max(0.0, heartScale).toFloat()
                    scaleX = s
                    scaleY = s
                    transformOrigin = anchor
                    alpha = min(1.0, max(0.0, heartScale)).toFloat()
                },
        ) {
            clipPath(PleadLogo.heartMask(size)) { drawLogo(image) }
        }
    }
}

private fun DrawScope.drawLogo(image: ImageBitmap) {
    drawImage(
        image,
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
        filterQuality = FilterQuality.High,
    )
}

/** Only if the asset is missing: a system serif stand-in with the same pixel heart. */
@Composable
private fun PleadLogoFallback(variant: PleadLogo.Variant, width: Float) {
    val size = width * 0.36f
    val heartColors: Map<Char, Color> = if (variant == PleadLogo.Variant.reversed) {
        mapOf('c' to PleadBrandColor.blush, 'h' to Color(hex = 0xF7D9DA), 'd' to Color(hex = 0xE3868D))
    } else {
        mapOf('c' to PleadBrandColor.coral, 'h' to Color(hex = 0xF4B2B6), 'd' to Color(hex = 0xC95463))
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Box {
            Text(
                "Plead",
                style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = fixedSp(size)),
                color = if (variant == PleadLogo.Variant.reversed) PleadBrandColor.warmCream else PleadBrandColor.courtBurgundy,
                maxLines = 1,
                softWrap = false,
            )
            if (variant != PleadLogo.Variant.wordmarkOnly) {
                BoxWithConstraints(Modifier.matchParentSize()) {
                    val hw = width * 35f / 220f
                    val hh = width * 30f / 220f
                    PixelGrid(
                        rows = PleadPixelArt.heart,
                        colors = heartColors,
                        modifier = Modifier
                            .offset(x = maxWidth * 0.51f - (hw / 2).dp, y = (-width * 0.07f - hh / 2).dp)
                            .size(hw.dp, hh.dp),
                    )
                }
            }
        }
    }
}

/**
 * Where the lockup (primary + strapline) sits, in the safe area: the cold-open end card and the Welcome screen
 * both place it with this so the logo holds perfectly still through the cold open → Welcome crossfade.
 */
object PleadLogoPlacement {
    /** Lockup width for a container `width` points wide. */
    fun width(width: Float): Float = min(width * 0.58f, 250f)

    /** Lockup height (mark + strapline) for a lockup `width` wide: ≈ 0.57 × width. */
    fun height(lockupWidth: Float): Float = PleadLogo.height(strapline = true, width = lockupWidth)

    /** Gap from the top of the safe area to the top of the lockup, for a safe area `height` points tall. */
    fun top(height: Float): Float = min(28f, max(10f, height * 0.028f))
}

/** Brief colour tokens (docs/cold-open-brief/BRIEF.md §3) used by the logo and the cold open. */
object PleadBrandColor {
    val courtBurgundy = Color(hex = 0x7C3042)
    val deepWine = Color(hex = 0x541F2C)
    val mahogany = Color(hex = 0x704735)
    val warmCream = Color(hex = 0xFFF6ED)
    val paperWhite = Color(hex = 0xFFFDFC)
    val blush = Color(hex = 0xEAA0A4)
    val coral = Color(hex = 0xE85E68)
    val gold = Color(hex = 0xCA9858)
}

/** Pixel sprites for the mark. `.` is transparent; other characters index the palette. */
object PleadPixelArt {
    /** 7×6 heart (same grid as the `PleadWordmark` / `PleadHeart` assets): c = body, h = highlight, d = shade. */
    val heart = listOf(
        ".cc.cc.",
        "chccccc",
        "ccccccd",
        ".ccccd.",
        "..ccd..",
        "...d...",
    )

    /** 15×10 balanced scales: g = gold, l = light, s = shadow. */
    val scales = listOf(
        ".......l.......",
        "..ggggggggggg..",
        "..g....g....g..",
        ".g.g...g...g.g.",
        "ggggg..g..ggggg",
        ".sss...g...sss.",
        ".......g.......",
        ".......g.......",
        ".....ggggg.....",
        "....sssssss....",
    )
}

/** Draws a character grid as hard-edged square pixels, centred in the available space. */
@Composable
fun PixelGrid(rows: List<String>, colors: Map<Char, Color>, modifier: Modifier = Modifier) {
    val density = LocalDensity.current.density
    Canvas(modifier.clearAndSetSemantics { }) {
        val cols = rows.maxOfOrNull { it.length } ?: 1
        val px = min(size.width / cols, size.height / rows.size)
        val ox = (size.width - px * cols) / 2
        val oy = (size.height - px * rows.size) / 2
        // A hair of overlap so adjacent pixels never show seams (0.3 pt).
        val overlap = 0.3f * density
        rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                val color = colors[ch] ?: return@forEachIndexed
                drawRect(color, topLeft = Offset(ox + c * px, oy + r * px), size = Size(px + overlap, px + overlap))
            }
        }
    }
}

@Preview(name = "Logo", widthDp = 402, heightDp = 700)
@Composable
private fun PleadLogoPreview() {
    Column(
        Modifier.fillMaxSize().background(PleadBrandColor.warmCream),
        verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PleadLogo(strapline = true, width = 240.dp)
        PleadLogo(variant = PleadLogo.Variant.wordmarkOnly, width = 140.dp)
        PleadLogo(
            modifier = Modifier.background(PleadBrandColor.courtBurgundy, RoundedCornerShape(20.dp)).padding(24.dp),
            variant = PleadLogo.Variant.reversed,
            strapline = true,
            width = 200.dp,
        )
        PleadLogo(width = 200.dp, heartScale = 0.6)
    }
}
