// Glance app widget receiver (PORT.md §2 Widgets), declared in the manifest now so wave 3f fills it in: the
// PleadWidgets families (small / medium / lock-screen ↔ 1×1 "glance") rendered from the shared WidgetSnapshot
// JSON (Shared/WidgetSnapshot.swift shape) stored via DataStore. Until then it shows the Plead status card
// in its empty state.
package app.plead.android.widgets

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.plead.android.designsystem.PleadColor

class PleadWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PleadWidget()
}

class PleadWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            Box(
                modifier = GlanceModifier.fillMaxSize().background(ColorProvider(PleadColor.launchBackground)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Plead",
                    style = TextStyle(color = ColorProvider(PleadColor.accentColor), fontWeight = FontWeight.Bold),
                )
            }
        }
    }
}
