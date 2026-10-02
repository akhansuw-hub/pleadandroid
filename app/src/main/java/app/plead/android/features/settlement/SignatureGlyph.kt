// The settlement "signature" mark: Android's stand-in for the SF Symbol `signature` that iOS draws on the settlement
// seal (SettlementComponents.swift `SettlementSeal`) and the "Settle Outside Court" button (`SettlementEntryButton`).
// An original vector drawn for Plead (no Apple artwork): a small "x" mark, a looped cursive initial whose descender
// crosses the signing line, two humps and a flick, over a straight signing line. Round caps and joins, one stroke
// weight, sized like the symbol at a given point size (`.font(.system(size: N, weight: .semibold))`).
package app.plead.android.features.settlement

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object SignatureGlyph {
    /** Viewport (units); the glyph fills it edge to edge, strokes included. */
    const val viewportWidth = 30f
    const val viewportHeight = 22f

    /** Semibold stroke weight, in viewport units (≈ 6 % of the glyph's width, as the symbol at `.semibold`). */
    const val strokeWidth = 1.9f

    /** Glyph box per point of font size: the symbol at `.system(size: N)` is ≈ 1.36 N wide and N tall. */
    const val widthPerPoint = viewportWidth / viewportHeight
    const val heightPerPoint = 1f

    /** Width and height of the glyph drawn for SF Symbol `signature` at `.font(.system(size: pointSize))`. */
    fun size(pointSize: Dp): Pair<Dp, Dp> = (pointSize * widthPerPoint) to (pointSize * heightPerPoint)

    val vector: ImageVector by lazy {
        ImageVector.Builder(
            name = "PleadSignature",
            defaultWidth = viewportWidth.dp,
            defaultHeight = viewportHeight.dp,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
        ).apply {
            val stroke = SolidColor(Color.Black)
            fun line(build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) = path(
                stroke = stroke,
                strokeLineWidth = strokeWidth,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = build,
            )
            // The "x" where you sign.
            line {
                moveTo(1.6f, 11.4f); lineTo(4.6f, 14.4f)
                moveTo(4.6f, 11.4f); lineTo(1.6f, 14.4f)
            }
            // The signing line.
            line {
                moveTo(2.4f, 18.0f); lineTo(28.6f, 18.0f)
            }
            // The signature: a looped capital, its descender looping under the line, then across and two humps.
            line {
                moveTo(11.0f, 12.0f)
                curveTo(8.0f, 9.6f, 5.2f, 7.4f, 5.4f, 4.4f)
                curveTo(5.6f, 1.9f, 7.6f, 0.95f, 9.6f, 1.0f)
                curveTo(12.6f, 1.1f, 14.8f, 3.6f, 15.0f, 7.6f)
                lineTo(15.1f, 15.0f)
                curveTo(15.1f, 18.9f, 13.4f, 21.05f, 11.0f, 21.0f)
                curveTo(9.2f, 20.9f, 8.2f, 19.6f, 8.4f, 17.6f)
                curveTo(8.6f, 15.4f, 10.6f, 13.6f, 13.2f, 12.6f)
                curveTo(15.2f, 11.8f, 17.2f, 10.4f, 18.4f, 9.2f)
                curveTo(19.3f, 8.4f, 20.6f, 8.3f, 20.6f, 9.8f)
                lineTo(20.4f, 12.6f)
                curveTo(21.0f, 10.4f, 22.4f, 8.3f, 23.8f, 8.4f)
                curveTo(25.2f, 8.5f, 25.0f, 11.2f, 25.6f, 12.0f)
                curveTo(26.2f, 12.8f, 27.6f, 12.3f, 28.4f, 10.8f)
            }
        }.build()
    }
}

/**
 * SF Symbol `signature` at `.font(.system(size: pointSize, weight: .semibold))`, tinted [tint]
 * (`.foregroundStyle`). Decorative: no content description, as the Swift call sites hide it from VoiceOver.
 */
@Composable
fun SignatureGlyph(pointSize: Dp, tint: Color, modifier: Modifier = Modifier) {
    val (w, h) = SignatureGlyph.size(pointSize)
    Icon(SignatureGlyph.vector, contentDescription = null, tint = tint, modifier = modifier.size(w, h))
}
