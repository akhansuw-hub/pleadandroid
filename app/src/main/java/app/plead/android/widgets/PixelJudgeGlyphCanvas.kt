// The in-app (Jetpack Compose) rendering of Shared/PixelJudgeGlyph.swift, for the app screens that show the widget
// system's art (onboarding's widget and notification education screens). Same sprites as the Glance
// [PixelJudgeGlyph] (`PleadPixelSprites`), drawn on a Canvas at whole device pixels per cell. A separate name because
// the Glance composable already takes `(kind, size, modifier)` and the two would be ambiguous at call sites.
package app.plead.android.widgets

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

/**
 * The glyph, [size] tall (the width follows the sprite's aspect ratio). Decorative (iOS `.accessibilityHidden(true)`).
 * (The iOS accented-widget luminance mode has no app-side equivalent.)
 */
@Composable
fun PixelJudgeGlyphCanvas(kind: PixelJudgeGlyph.Kind, modifier: Modifier = Modifier, size: Dp = 44.dp) {
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
            row.forEachIndexed { c, argb ->
                if (argb != null) drawRect(Color(argb), topLeft = Offset(ox + c * cell, oy + r * cell), size = Size(cell, cell))
            }
        }
    }
}
