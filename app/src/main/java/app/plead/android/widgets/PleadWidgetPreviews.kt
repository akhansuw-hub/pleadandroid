// The SwiftUI `#Preview`s of PleadStatusWidget.swift / PixelJudgeGlyph.swift as Glance previews (Android Studio renders
// them with glance-appwidget-preview; the JVM snapshot tests render the same content to PNG).
package app.plead.android.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.glance.layout.Row
import androidx.glance.layout.padding
import androidx.glance.preview.ExperimentalGlancePreviewApi
import androidx.glance.preview.Preview
import app.plead.android.services.WidgetFamily
import app.plead.android.services.WidgetPrivacyMode
import app.plead.android.services.WidgetSnapshot
import app.plead.android.services.WidgetState

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 170, heightDp = 170)
@Composable
fun SmallSummonedPreview() = PreviewEntry(WidgetSnapshot.sample(WidgetState.summoned), WidgetFamily.systemSmall)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 170, heightDp = 170)
@Composable
fun SmallVerdictReadyPreview() = PreviewEntry(WidgetSnapshot.sample(WidgetState.verdictReady), WidgetFamily.systemSmall)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 170, heightDp = 170)
@Composable
fun SmallNonePreview() = PreviewEntry(WidgetSnapshot.sample(WidgetState.none), WidgetFamily.systemSmall)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 364, heightDp = 170)
@Composable
fun MediumSummonedPreview() = PreviewEntry(WidgetSnapshot.sample(WidgetState.summoned), WidgetFamily.systemMedium)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 364, heightDp = 170)
@Composable
fun MediumSettlementPreview() = PreviewEntry(WidgetSnapshot.sample(WidgetState.settlement), WidgetFamily.systemMedium)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 172, heightDp = 76)
@Composable
fun RectangularGenericPreview() = PreviewEntry(WidgetSnapshot.sample(WidgetState.summoned, WidgetPrivacyMode.generic), WidgetFamily.accessoryRectangular)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 172, heightDp = 76)
@Composable
fun RectangularDetailedPreview() = PreviewEntry(WidgetSnapshot.sample(WidgetState.summoned, WidgetPrivacyMode.detailed), WidgetFamily.accessoryRectangular)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 76, heightDp = 76)
@Composable
fun CircularSummonedPreview() = PreviewEntry(WidgetSnapshot.sample(WidgetState.summoned), WidgetFamily.accessoryCircular)

@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 76, heightDp = 76)
@Composable
fun CircularNonePreview() = PreviewEntry(WidgetSnapshot.sample(WidgetState.none), WidgetFamily.accessoryCircular)

/** "Pixel judge glyphs": every kind at 60 dp on cream. */
@OptIn(ExperimentalGlancePreviewApi::class)
@Preview(widthDp = 420, heightDp = 90)
@Composable
fun PixelJudgeGlyphsPreview() {
    Row(modifier = GlanceModifier.background(PleadWidgetPalette.warmCream).padding(12.dp)) {
        for (kind in PixelJudgeGlyph.Kind.entries) PixelJudgeGlyph(kind = kind, size = 60.dp, modifier = GlanceModifier.padding(end = 12.dp))
    }
}

@Composable
private fun PreviewEntry(snapshot: WidgetSnapshot, family: WidgetFamily) {
    PleadWidgetContainer(family = family, link = snapshot.link) { PleadWidgetContent(snapshot, family) }
}
