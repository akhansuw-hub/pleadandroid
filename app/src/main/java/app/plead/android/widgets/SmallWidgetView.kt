// Port of Shared/WidgetViews/SmallWidgetView.swift. Home Screen small: the judge at the bench on cream, the case title
// and one status chip. Home Screen widgets may show the title (the user placed them deliberately); never evidence.
package app.plead.android.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.Text
import app.plead.android.services.WidgetPrivacyMode
import app.plead.android.services.WidgetSnapshot
import java.time.Instant

@Composable
fun SmallWidgetView(snapshot: WidgetSnapshot, now: Instant = Instant.now()) {
    val context = LocalContext.current
    val cap = PleadWidgetFont.xLargeScale
    // iOS draws the bench 70 pt tall in a 138 pt content square; smaller launcher cells shrink it to keep the text.
    val benchSize = SmallWidgetView.benchSize(LocalSize.current.height.value - 2 * PleadStatusWidget.contentPadding.value)
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .semantics { contentDescription = WidgetSnapshot.accessibilityLabel(snapshot, detailed = true, now = now) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelJudgeGlyph(kind = PixelJudgeGlyph.Kind.bench, size = benchSize.dp)
        Spacer(GlanceModifier.height(5.dp))
        Box(GlanceModifier.fillMaxWidth().padding(horizontal = 6.dp)) {
            Box(GlanceModifier.fillMaxWidth().height(1.5.dp).background(PleadWidgetPalette.romanceBlush)) {}
        }
        Spacer(GlanceModifier.height(5.dp))
        val p = snapshot.primary
        if (p != null) {
            Text(
                text = p.caseTitle,
                style = PleadWidgetFont.serif(context, WidgetTextStyle.subheadline, PleadWidgetPalette.deepWine, cap),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(5.dp))
            // One line, tail-truncated (never wraps into the title at large text sizes).
            PleadStatusChip(text = WidgetSnapshot.statusChip(p.state), maxScale = cap)
        } else {
            Text(
                text = WidgetSnapshot.headline(null as app.plead.android.services.WidgetCase?),
                style = PleadWidgetFont.serif(context, WidgetTextStyle.subheadline, PleadWidgetPalette.deepWine, cap),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(5.dp))
            Text(
                text = WidgetSnapshot.detailLine(null, WidgetPrivacyMode.generic, snapshot.activeCaseCount),
                style = PleadWidgetFont.ui(context, WidgetTextStyle.caption, WidgetFontWeight.semibold, PleadWidgetPalette.mutedCocoa, cap),
                maxLines = 1,
            )
        }
    }
}

object SmallWidgetView {
    /** The bench glyph's height (dp) for this much content height: 70 like iOS, never below 36. */
    fun benchSize(contentHeight: Float): Float = (contentHeight - 64f).coerceIn(36f, 70f)
}
