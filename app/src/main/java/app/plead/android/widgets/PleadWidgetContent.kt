// Port of Shared/WidgetViews/PleadWidgetContent.swift: picks the family's view. Used by the widget's entry view and by
// the DEBUG preview harness.
package app.plead.android.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import app.plead.android.services.WidgetFamily
import app.plead.android.services.WidgetPrivacyMode
import app.plead.android.services.WidgetSnapshot
import java.time.Instant

@Composable
fun PleadWidgetContent(snapshot: WidgetSnapshot, family: WidgetFamily, now: Instant = Instant.now()) {
    when (family) {
        WidgetFamily.accessoryRectangular -> AccessoryRectangularView(snapshot, now)
        WidgetFamily.accessoryCircular -> AccessoryCircularView(snapshot, now)
        WidgetFamily.systemMedium -> MediumWidgetView(snapshot, now)
        else -> SmallWidgetView(snapshot, now)
    }
}

/** Shown before the app has ever written a snapshot (fresh install, signed out): the neutral heart. */
@Composable
fun PleadWidgetPlaceholderView(family: WidgetFamily) {
    PleadWidgetContent(WidgetSnapshot(privacyMode = WidgetPrivacyMode.generic, activeCaseCount = 0, primary = null), family)
}

object PleadWidgetContent {
    /** Glance responsive sizes, one per family (the launcher picks the largest that fits the placed widget). */
    val circularSize = DpSize(40.dp, 40.dp)
    val rectangularSize = DpSize(110.dp, 40.dp)
    val smallSize = DpSize(110.dp, 110.dp)
    val mediumSize = DpSize(250.dp, 110.dp)
    val sizes: Set<DpSize> = setOf(circularSize, rectangularSize, smallSize, mediumSize)

    /**
     * The family for a widget's size (dp), matching [WidgetFamily.fromSize] on width (1 cell → circular, up to ~2
     * cells → small, wider → medium); a one-cell-tall strip is the rectangular accessory.
     */
    fun family(width: Float, height: Float): WidgetFamily = when {
        width < 110f -> WidgetFamily.accessoryCircular
        height < 100f -> WidgetFamily.accessoryRectangular
        width < 250f -> WidgetFamily.systemSmall
        else -> WidgetFamily.systemMedium
    }
}
