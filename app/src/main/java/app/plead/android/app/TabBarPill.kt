// The selected-tab pill iOS 26 draws behind the selected tab bar item (the Liquid Glass selection capsule), on
// PleadTabBar's docked bar. Measured from the iOS captures (see TabBarStyle.kt); no live glass: the look is a
// translucent fill over the bar plus, on the dark Court bar, a faint rim on the trailing edge.
package app.plead.android.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.swiftSpring
import kotlin.math.roundToInt

/** Where the pill sits in the item row. Pure, so the JVM tests can check it. */
object TabBarPillGeometry {
    /**
     * The pill's bounds in the item row (px) at [position], the selected tab's index (fractional while it slides).
     * The row is split into [count] equal slots, as PleadTabBar's items are (`weight(1f)` each, so this is the
     * measured item); the pill is its slot inset by [horizontalInset] / [verticalInset]. [position] is clamped to the
     * first and last tab, so a spring that overshoots an end tab never carries the pill past the bar's edge.
     */
    fun bounds(
        position: Float,
        count: Int,
        rowWidth: Float,
        rowHeight: Float,
        horizontalInset: Float,
        verticalInset: Float,
    ): Rect {
        require(count > 0) { "A tab bar has at least one tab" }
        val slot = rowWidth / count
        val clamped = position.coerceIn(0f, (count - 1).toFloat())
        val width = (slot - 2 * horizontalInset).coerceAtLeast(0f)
        val height = (rowHeight - 2 * verticalInset).coerceAtLeast(0f)
        val left = clamped * slot + (slot - width) / 2
        val top = (rowHeight - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    /** A capsule: half the pill's height. */
    fun cornerRadius(bounds: Rect): Float = bounds.height / 2
}

/**
 * Draws the pill behind the items of a tab bar row; place it in the row's Box before the items. It slides to
 * [selectedIndex] on a spring (jumps with Reduce Motion) and cross-fades [style]'s pill colours with the bar's.
 */
@Composable
internal fun BoxScope.TabBarPill(selectedIndex: Int, count: Int, style: TabBarStyle) {
    val reduceMotion = accessibilityReduceMotion()
    val slide: AnimationSpec<Float> = if (reduceMotion) snap() else
        swiftSpring(TabBarPillMetrics.springDuration, TabBarPillMetrics.springBounce)
    val fade: AnimationSpec<Color> = if (reduceMotion) snap() else PleadMotion.fade()
    // Read in the layout / draw phases only, so the slide re-places and redraws without recomposing.
    val position = animateFloatAsState(selectedIndex.toFloat(), slide, label = "tabBarPillPosition")
    val fill by animateColorAsState(style.pillFill, fade, label = "tabBarPillFill")
    val highlight by animateColorAsState(style.pillHighlight, fade, label = "tabBarPillHighlight")
    Spacer(
        Modifier
            .matchParentSize()
            .layout { measurable, constraints ->
                val bounds = TabBarPillGeometry.bounds(
                    position = position.value,
                    count = count,
                    rowWidth = constraints.maxWidth.toFloat(),
                    rowHeight = constraints.maxHeight.toFloat(),
                    horizontalInset = TabBarPillMetrics.horizontalInset.toPx(),
                    verticalInset = TabBarPillMetrics.verticalInset.toPx(),
                )
                val placeable = measurable.measure(
                    Constraints.fixed(bounds.width.roundToInt(), bounds.height.roundToInt()),
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(bounds.left.roundToInt(), bounds.top.roundToInt())
                }
            }
            // UI tests: the pill's frame (no semantics of its own; the tabs carry the selected state).
            .testTag("tabBarPill")
            .drawBehind {
                val radius = CornerRadius(size.height / 2)
                drawRoundRect(color = fill, cornerRadius = radius)
                if (highlight.alpha > 0f) {
                    val stroke = TabBarPillMetrics.highlightWidth.toPx()
                    drawRoundRect(
                        brush = Brush.horizontalGradient(
                            0f to highlight.copy(alpha = 0f),
                            0.5f to highlight.copy(alpha = highlight.alpha * 0.2f),
                            1f to highlight,
                        ),
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = Size(size.width - stroke, size.height - stroke),
                        cornerRadius = CornerRadius(radius.x - stroke / 2),
                        style = Stroke(width = stroke),
                    )
                }
            },
    )
}
