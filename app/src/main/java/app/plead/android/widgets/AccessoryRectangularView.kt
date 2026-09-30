// Port of Shared/WidgetViews/AccessoryRectangularView.swift. Lock Screen rectangular on iOS; on Android the same view
// is what a Plead widget shows when it is resized to a short strip (one cell tall). Judge + headline + one more line
// (the case title only with detailed previews; otherwise the countdown or a neutral line).
package app.plead.android.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
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
            val headline = WidgetSnapshot.headline(snapshot.primary, snapshot.privacyMode).uppercase(Locale.getDefault())
            val headlineStyle = PleadWidgetFont.ui(context, WidgetTextStyle.subheadline, WidgetFontWeight.heavy, PleadWidgetPalette.courtBurgundy)
            val maxLines = if (headlineOnly) 3 else 2
            // iOS `.lineLimit(2).minimumScaleFactor(0.6)`: shrink so whole words fit the lines, leaving room for the
            // second line(s) (caption lines, ~16 dp each; two with detailed previews).
            val size = LocalSize.current
            val textWidth = size.width.value - 16f - if (headlineOnly) 0f else 40f * PleadPixelSprites.art(PixelJudgeGlyph.Kind.judge).aspect + 6f
            val reserve = when {
                headlineOnly -> 0f
                detailed && snapshot.primary != null && WidgetSnapshot.activeDeadline(snapshot.primary, now) != null -> 33f
                else -> 17f
            }
            Text(
                text = headline,
                style = headlineStyle.copy(
                    fontSize = WidgetTextFit.fittedLines(context, headline, headlineStyle.fontSize!!, textWidth, maxLines, 0.6f, size.height.value - 12f - reserve),
                ),
                maxLines = maxLines,
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
        val line = WidgetSnapshot.detailLine(p, WidgetPrivacyMode.generic, snapshot.activeCaseCount)
        val style = PleadWidgetFont.ui(context, WidgetTextStyle.caption, WidgetFontWeight.semibold, bodyColor)
        // iOS `.minimumScaleFactor(0.8)`.
        val width = LocalSize.current.width.value - 16f - 40f * PleadPixelSprites.art(PixelJudgeGlyph.Kind.judge).aspect - 6f
        Text(
            text = line,
            style = style.copy(fontSize = WidgetTextFit.fitted(context, line, style.fontSize!!, width, 0.8f, serifBold = false)),
            maxLines = 1,
        )
    }
}
