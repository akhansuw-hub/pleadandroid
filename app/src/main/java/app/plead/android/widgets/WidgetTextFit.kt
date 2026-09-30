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
        val available = widthDp * metrics.density
        val measured = paint.measureText(text)
        if (measured <= available) return size
        val scale = (available / measured).coerceAtLeast(minScale)
        // Round down to a quarter sp so the launcher's own rounding never pushes it back over.
        return (kotlin.math.floor(size.value * scale * 4f) / 4f).sp
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
