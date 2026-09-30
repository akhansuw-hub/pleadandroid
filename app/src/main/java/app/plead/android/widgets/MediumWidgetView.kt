// Port of Shared/WidgetViews/MediumWidgetView.swift. Home Screen medium: the judge's bench, then partner, case title,
// status and the next action.
package app.plead.android.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import app.plead.android.services.WidgetAction
import app.plead.android.services.WidgetCase
import app.plead.android.services.WidgetPrivacyMode
import app.plead.android.services.WidgetSnapshot
import java.time.Instant

@Composable
fun MediumWidgetView(snapshot: WidgetSnapshot, now: Instant = Instant.now()) {
    val width = LocalSize.current.width.value - 2 * PleadStatusWidget.contentPadding.value
    val narrow = width < MediumWidgetView.narrowWidth
    Row(
        modifier = GlanceModifier.fillMaxSize()
            .semantics { contentDescription = WidgetSnapshot.accessibilityLabel(snapshot, detailed = true, now = now) },
    ) {
        PleadBenchPanel(
            judgeSize = if (narrow) 80.dp else 96.dp,
            modifier = GlanceModifier.width(if (narrow) 88.dp else 112.dp).fillMaxHeight(),
        )
        Spacer(GlanceModifier.width(if (narrow) 10.dp else 12.dp))
        MediumColumn(snapshot, now, GlanceModifier.defaultWeight().fillMaxHeight())
    }
}

@Composable
private fun MediumColumn(snapshot: WidgetSnapshot, now: Instant, modifier: GlanceModifier) {
    val context = LocalContext.current
    val cap = PleadWidgetFont.xLargeScale
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        val p = snapshot.primary
        if (p != null) {
            Text(
                text = p.partnerDisplayName,
                style = PleadWidgetFont.serif(context, WidgetTextStyle.title3, PleadWidgetPalette.deepWine, cap),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = p.caseTitle,
                style = PleadWidgetFont.ui(context, WidgetTextStyle.subheadline, WidgetFontWeight.semibold, PleadWidgetPalette.darkCocoa, cap),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(2.dp))
            if (WidgetSnapshot.activeDeadline(p, now) != null) {
                PleadDeadlineText(primary = p, now = now, color = PleadWidgetPalette.mutedCocoa, maxScale = cap)
            } else {
                Text(
                    text = p.docketNumber,
                    style = PleadWidgetFont.ui(context, WidgetTextStyle.caption2, WidgetFontWeight.semibold, PleadWidgetPalette.mutedCocoa, cap),
                    maxLines = 1,
                )
            }
            Spacer(GlanceModifier.defaultWeight().height(4.dp))
            PleadStatusChip(text = WidgetSnapshot.headline(p, WidgetPrivacyMode.detailed), maxScale = cap)
            Spacer(GlanceModifier.height(5.dp))
            PleadActionPill(title = p.nextAction.title, maxScale = cap)
        } else {
            Text(
                text = WidgetSnapshot.headline(null as WidgetCase?),
                style = PleadWidgetFont.serif(context, WidgetTextStyle.title3, PleadWidgetPalette.deepWine, cap),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(2.dp))
            Text(
                text = WidgetSnapshot.detailLine(null, WidgetPrivacyMode.generic, snapshot.activeCaseCount),
                style = PleadWidgetFont.ui(context, WidgetTextStyle.subheadline, WidgetFontWeight.semibold, PleadWidgetPalette.mutedCocoa, cap),
                maxLines = 2,
            )
            Spacer(GlanceModifier.defaultWeight().height(4.dp))
            PleadActionPill(title = WidgetAction.openApp.title, maxScale = cap)
        }
    }
}

object MediumWidgetView {
    /**
     * Content width below which the medium widget is on a narrow phone (iPhone SE / 16e: ~290–306 pt inside the
     * margins, vs ~330 pt on 6.3" and wider). Same threshold in dp.
     */
    const val narrowWidth: Float = 320f
}
