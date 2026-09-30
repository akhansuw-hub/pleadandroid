// Port of Shared/WidgetViews/AccessoryCircularView.swift. Lock Screen circular on iOS; on Android the 1×1 "glance"
// cell (PORT.md §2): judge face (summons / deliberation / verdict), gavel (an action of mine) or the Plead heart
// (nothing open), with the active-case count as a badge.
package app.plead.android.widgets

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.plead.android.R
import app.plead.android.services.WidgetPrivacyMode
import app.plead.android.services.WidgetSnapshot
import app.plead.android.services.WidgetState
import java.time.Instant
import kotlin.math.min

@Composable
@Suppress("UNUSED_PARAMETER")
fun AccessoryCircularView(snapshot: WidgetSnapshot, now: Instant = Instant.now()) {
    val size = LocalSize.current
    // iOS: a 76 pt circle. The cell's shorter side here.
    val diameter = min(size.width.value, size.height.value).coerceAtLeast(40f)
    val scale = diameter / 76f
    val glyph = snapshot.state.circularGlyph
    val glyphSize = (if (snapshot.state == WidgetState.none) 26f else 36f) * scale
    Box(
        modifier = GlanceModifier.fillMaxSize().semantics { contentDescription = AccessoryCircularView.label(snapshot) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier.size(diameter.dp).background(ImageProvider(R.drawable.widget_circle_cream)),
            contentAlignment = Alignment.Center,
        ) {
            PixelJudgeGlyph(
                kind = glyph,
                size = glyphSize.dp,
                modifier = if (glyph == PixelJudgeGlyph.Kind.face) GlanceModifier.padding(top = (4f * scale).dp) else GlanceModifier,
            )
        }
        if (snapshot.activeCaseCount > 0) {
            Box(modifier = GlanceModifier.size(diameter.dp), contentAlignment = Alignment.TopEnd) {
                Box(
                    modifier = GlanceModifier.size(18.dp).background(ImageProvider(R.drawable.widget_badge_burgundy)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = AccessoryCircularView.badge(snapshot.activeCaseCount),
                        style = TextStyle(
                            color = ColorProvider(PleadWidgetPalette.paperWhite),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                        ),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

object AccessoryCircularView {
    fun badge(count: Int): String = if (count > 9) "9+" else "$count"

    fun label(snapshot: WidgetSnapshot): String {
        var s = "Plead. ${WidgetSnapshot.headline(snapshot.primary, WidgetPrivacyMode.generic)}"
        if (snapshot.activeCaseCount > 0) s += ". " + WidgetSnapshot.activeCountLine(snapshot.activeCaseCount)
        return s
    }
}
