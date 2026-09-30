// Port of Shared/WidgetViews/AccessoryRectangularView.swift. Lock Screen rectangular on iOS; on Android the same view
// is what a Plead widget shows when it is resized to a short strip (one cell tall). Judge + headline + one more line
// (the case title only with detailed previews; otherwise the countdown or a neutral line).
package app.plead.android.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import app.plead.android.R
import app.plead.android.services.WidgetPrivacyMode
import app.plead.android.services.WidgetSnapshot
import java.util.Locale
import java.time.Instant

@Composable
fun AccessoryRectangularView(snapshot: WidgetSnapshot, now: Instant = Instant.now()) {
    val context = LocalContext.current
    val detailed = snapshot.privacyMode == WidgetPrivacyMode.detailed
    // Accessibility text sizes: the headline alone (no judge, no second line) so it stays legible.
    val headlineOnly = PleadWidgetFont.isAccessibilitySize(context)
    Row(
        modifier = GlanceModifier.fillMaxSize()
            .background(ImageProvider(R.drawable.widget_card_cream))
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .semantics { contentDescription = WidgetSnapshot.accessibilityLabel(snapshot, detailed = detailed, now = now) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!headlineOnly) {
            PixelJudgeGlyph(kind = PixelJudgeGlyph.Kind.judge, size = 40.dp)
            Spacer(GlanceModifier.width(6.dp))
        }
        Column(modifier = GlanceModifier.defaultWeight()) {
            Text(
                text = WidgetSnapshot.headline(snapshot.primary, snapshot.privacyMode).uppercase(Locale.getDefault()),
                style = PleadWidgetFont.ui(context, WidgetTextStyle.subheadline, WidgetFontWeight.heavy, PleadWidgetPalette.courtBurgundy),
                maxLines = if (headlineOnly) 3 else 2,
            )
            if (!headlineOnly) {
                Spacer(GlanceModifier.height(1.dp))
                SecondLine(snapshot, now, detailed)
            }
        }
    }
}

@Composable
private fun SecondLine(snapshot: WidgetSnapshot, now: Instant, detailed: Boolean) {
    val context = LocalContext.current
    val bodyColor = PleadWidgetPalette.darkCocoa
    val p = snapshot.primary
    if (detailed && p != null) {
        Text(
            text = p.caseTitle,
            style = PleadWidgetFont.ui(context, WidgetTextStyle.caption, WidgetFontWeight.semibold, bodyColor),
            maxLines = 1,
        )
        PleadDeadlineText(primary = p, now = now, color = bodyColor)
    } else if (WidgetSnapshot.activeDeadline(p, now) != null) {
        PleadDeadlineText(primary = p, now = now, color = bodyColor, style = WidgetTextStyle.caption)
    } else {
        Text(
            text = WidgetSnapshot.detailLine(p, WidgetPrivacyMode.generic, snapshot.activeCaseCount),
            style = PleadWidgetFont.ui(context, WidgetTextStyle.caption, WidgetFontWeight.semibold, bodyColor),
            maxLines = 1,
        )
    }
}
