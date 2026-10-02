// SwiftUI `.minimumScaleFactor` for Glance text (RemoteViews has no auto-shrink before API 26 text autosizing, and
// Glance does not expose it): measure the line with the same typeface and shrink the font size, down to `minScale`,
// until it fits the width. Beyond that the text tail-truncates as before.
package app.plead.android.widgets

import android.content.Context
import android.graphics.Paint
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

object WidgetTextFit {
    /**
     * The font size for [text] set in [size] (sp) so one line fits [widthDp], never below `size * minScale`.
     * [serifBold] measures with the serif bold face the widgets use for titles, otherwise the bold sans (conservative for medium weights).
     */
    fun fitted(context: Context, text: String, size: TextUnit, widthDp: Float, minScale: Float, serifBold: Boolean = true): TextUnit {
        if (!size.isSp || widthDp <= 0f || text.isEmpty()) return size
        val metrics = context.resources.displayMetrics
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = if (serifBold) Typeface.create(Typeface.SERIF, Typeface.BOLD) else Typeface.DEFAULT_BOLD
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, size.value, metrics)
        }
        return scaledSize(size.value, paint.measureText(text), widthDp * metrics.density, minScale).sp
    }

    /**
     * The one-line `.minimumScaleFactor` rule both renderers share: [sizeSp] when [measuredPx] fits [availablePx],
     * else shrunk in proportion, never below `sizeSp * minScale`, rounded down to a quarter sp (so the renderer's
     * own rounding never pushes it back over).
     */
    fun scaledSize(sizeSp: Float, measuredPx: Float, availablePx: Float, minScale: Float): Float {
        if (measuredPx <= availablePx || availablePx <= 0f) return sizeSp
        val scale = (availablePx / measuredPx).coerceAtLeast(minScale)
        return kotlin.math.floor(sizeSp * scale * 4f) / 4f
    }

    /**
     * App Compose (the onboarding widget illustration): the [style] font size at which [text] fits one line of
     * [maxWidthPx], measured with Compose's own [measurer] in that style, down to `style.fontSize * minScale`.
     */
    fun fittedStyle(measurer: TextMeasurer, text: String, style: TextStyle, maxWidthPx: Int, minScale: Float): TextStyle {
        val size = style.fontSize
        if (!size.isSp || maxWidthPx <= 0 || text.isEmpty()) return style
        val measured = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toFloat()
        val fitted = scaledSize(size.value, measured, maxWidthPx.toFloat(), minScale)
        return if (fitted == size.value) style else style.copy(fontSize = fitted.sp)
    }

    /**
     * Multi-line `.lineLimit(maxLines).minimumScaleFactor(minScale)`: the largest size (down to `size * minScale`)
     * at which [text] wraps into at most [maxLines] lines without breaking a word, and whose height stays within
     * [maxHeightDp] (when given). Measured with the bold sans the Lock Screen headlines use.
     */
    fun fittedLines(context: Context, text: String, size: TextUnit, widthDp: Float, maxLines: Int, minScale: Float, maxHeightDp: Float? = null): TextUnit {
        if (!size.isSp || widthDp <= 0f || text.isEmpty()) return size
        val metrics = context.resources.displayMetrics
        val width = (widthDp * metrics.density).toInt().coerceAtLeast(1)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
        var scale = 1f
        while (true) {
            val sp = kotlin.math.floor(size.value * scale * 4f) / 4f
            paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, metrics)
            val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(true).build()
            val wordsWhole = (0 until layout.lineCount).none { line ->
                val end = layout.getLineEnd(line)
                end in 1 until text.length && !text[end - 1].isWhitespace() && !text[end].isWhitespace()
            }
            val fitsHeight = maxHeightDp == null || layout.height <= maxHeightDp * metrics.density
            if ((layout.lineCount <= maxLines && wordsWhole && fitsHeight) || scale <= minScale) return sp.sp
            scale = (scale - 0.05f).coerceAtLeast(minScale)
        }
    }
}

/**
 * SwiftUI `Text(text).lineLimit(1).minimumScaleFactor(minScale)` in app Compose: shrinks [style] until [text] fits
 * the available width (down to [minScale]), then tail-truncates as before.
 */
@androidx.compose.runtime.Composable
fun WidgetFitText(
    text: String,
    style: TextStyle,
    color: androidx.compose.ui.graphics.Color,
    minScale: Float,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
) {
    androidx.compose.foundation.layout.BoxWithConstraints(modifier, contentAlignment = androidx.compose.ui.Alignment.Center) {
        val measurer = androidx.compose.ui.text.rememberTextMeasurer()
        val maxWidth = constraints.maxWidth
        // Measure what `Text` will draw: the theme's text style (letter spacing, line height…) merged with [style].
        val merged = androidx.compose.material3.LocalTextStyle.current.merge(style)
        val fitted = androidx.compose.runtime.remember(text, merged, maxWidth) {
            if (constraints.hasBoundedWidth) WidgetTextFit.fittedStyle(measurer, text, merged, maxWidth, minScale) else merged
        }
        androidx.compose.material3.Text(
            text,
            style = fitted,
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
