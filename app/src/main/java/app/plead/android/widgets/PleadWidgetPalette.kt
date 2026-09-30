// Port of Shared/PleadWidgetPalette.swift: the widget palette (docs/widgets-brief/BRIEF.md §2). Shared by the Glance
// widgets and the court-session notification, depending on nothing in the design system (as on iOS, where the widget
// extension cannot see `AWColor`).
package app.plead.android.widgets

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

object PleadWidgetPalette {
    /** Widget surfaces, neutral background. */
    val warmCream = pleadRGB(0xFFF6ED)

    /** Primary status / CTA. */
    val courtBurgundy = pleadRGB(0x7C3042)

    /** Headlines and dark accents. */
    val deepWine = pleadRGB(0x541F2C)

    /** Body text. */
    val darkCocoa = pleadRGB(0x3B2425)

    /** Gavel / scales and small emphasis. */
    val courtGold = pleadRGB(0xCA9858)

    /** Relationship accents only. */
    val romanceBlush = pleadRGB(0xEAA0A4)

    // Supporting tints derived from the brief tokens (pixel art and quiet fills).
    val paperWhite = pleadRGB(0xFFFDFC)
    val chipFill = pleadRGB(0xF9DCDA) // blush over cream
    val mutedCocoa = pleadRGB(0x3B2425, opacity = 0.68f)
    val mahogany = pleadRGB(0x704735)
    val woodLight = pleadRGB(0x9A6444)
    val woodDark = pleadRGB(0x4A2A20)
    val panelWood = pleadRGB(0x5B3326)
}

/** `0xRRGGBB` → Color (Swift `Color(pleadRGB:opacity:)`; named so it never collides with the app's own helpers). */
fun pleadRGB(hex: Int, opacity: Float = 1f): Color = Color(
    red = ((hex shr 16) and 0xFF) / 255f,
    green = ((hex shr 8) and 0xFF) / 255f,
    blue = (hex and 0xFF) / 255f,
    alpha = opacity,
)

/** ARGB int for RemoteViews / Bitmaps. */
val Color.argb: Int get() = toArgb()
