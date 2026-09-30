// Port of Shared/WidgetViews/PleadWidgetComponents.swift: pieces shared by the widget families (fonts, the live
// deadline line, the status chip, the action pill, the bench panel, the circular glyph per state). Glance
// composables; also rendered by the DEBUG `AWWidgetPreview` harness (WidgetPreviewHarness.kt).
//
// Not 1:1 (platform): SwiftUI's `minimumScaleFactor` has no RemoteViews equivalent (text truncates instead), and
// Android has no tinted (`.accented`) Home Screen, so every family renders in full colour.
@file:Suppress("EnumEntryName")

package app.plead.android.widgets

import android.content.Context
import android.os.SystemClock
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.appwidget.AndroidRemoteViews
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.plead.android.R
import app.plead.android.services.WidgetCase
import app.plead.android.services.WidgetSnapshot
import app.plead.android.services.WidgetState
import java.time.Duration
import java.time.Instant

/** SwiftUI text styles at the default (Large) Dynamic Type size, in points → sp. */
enum class WidgetTextStyle(val size: Float) {
    title3(20f), headline(17f), subheadline(15f), caption(12f), caption2(11f),
}

/** Swift `Font.Weight` → the weights RemoteViews text supports (heavy/black → bold). */
enum class WidgetFontWeight { regular, medium, semibold, bold, heavy }

object PleadWidgetFont {
    /**
     * Rounded UI text on a text style. SF Pro Rounded has no Android equivalent: the system sans (PORT.md §2).
     * `maxScale` caps the font scale like `.dynamicTypeSize(...xLarge)` (null = uncapped).
     */
    fun ui(context: Context, style: WidgetTextStyle, weight: WidgetFontWeight = WidgetFontWeight.regular, color: Color, maxScale: Float? = null): TextStyle =
        TextStyle(color = ColorProvider(color), fontSize = scaled(context, style.size, maxScale), fontWeight = glanceWeight(weight))

    /** The brief's bold serif for names / headlines. */
    fun serif(context: Context, style: WidgetTextStyle, color: Color, maxScale: Float? = null): TextStyle =
        TextStyle(color = ColorProvider(color), fontSize = scaled(context, style.size, maxScale), fontWeight = FontWeight.Bold, fontFamily = FontFamily.Serif)

    fun glanceWeight(weight: WidgetFontWeight): FontWeight = when (weight) {
        WidgetFontWeight.regular -> FontWeight.Normal
        WidgetFontWeight.medium, WidgetFontWeight.semibold -> FontWeight.Medium
        WidgetFontWeight.bold, WidgetFontWeight.heavy -> FontWeight.Bold
    }

    /** `.dynamicTypeSize(...DynamicTypeSize.xLarge)`: xLarge body is 19 pt against Large's 17 pt. */
    const val xLargeScale = 19f / 17f

    /** iOS `isAccessibilitySize` on Android (as wave 2b): font scale 1.6 and up. */
    fun isAccessibilitySize(context: Context): Boolean = fontScale(context) >= 1.6f

    fun fontScale(context: Context): Float = context.resources.configuration.fontScale.takeIf { it > 0f } ?: 1f

    /** `size` sp, with the font scale capped at `maxScale`. */
    fun scaled(context: Context, size: Float, maxScale: Float?): TextUnit {
        maxScale ?: return size.sp
        val scale = fontScale(context)
        return if (scale <= maxScale) size.sp else (size * maxScale / scale).sp
    }
}

/**
 * "Plea due in 5:12:03" (live-updating), only while the deadline is ahead of `now`. A RemoteViews `Chronometer`
 * counting down (iOS `Text(timerInterval:countsDown:showsHours:)`), so the launcher ticks it with no widget updates.
 */
@Composable
fun PleadDeadlineText(
    primary: WidgetCase?,
    now: Instant,
    color: Color,
    style: WidgetTextStyle = WidgetTextStyle.caption2,
    maxScale: Float? = null,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    val views = PleadDeadlineText.remoteViews(context, primary, now, color, style, maxScale) ?: return
    // A RemoteViews host otherwise takes all the height it is offered: one line of text, exactly.
    AndroidRemoteViews(remoteViews = views, modifier = modifier.height(PleadDeadlineText.lineHeight(context, style, maxScale).dp))
}

object PleadDeadlineText {
    /** The text before the timer ("Plea due in"), or null when there is no live deadline. */
    fun label(primary: WidgetCase?, now: Instant): String? {
        WidgetSnapshot.activeDeadline(primary, now) ?: return null
        val state = primary?.state ?: return null
        return WidgetSnapshot.deadlineLabel(state)
    }

    /** One line of the countdown at this style (dp): the text size after the font scale, plus leading. */
    fun lineHeight(context: Context, style: WidgetTextStyle, maxScale: Float?): Float =
        PleadWidgetFont.scaled(context, style.size, maxScale).value * PleadWidgetFont.fontScale(context) * 1.3f

    fun remoteViews(context: Context, primary: WidgetCase?, now: Instant, color: Color, style: WidgetTextStyle, maxScale: Float?): RemoteViews? {
        val deadline = WidgetSnapshot.activeDeadline(primary, now) ?: return null
        val label = label(primary, now) ?: return null
        val remaining = Duration.between(now, deadline).toMillis()
        val size = PleadWidgetFont.scaled(context, style.size, maxScale).value
        return RemoteViews(context.packageName, R.layout.widget_deadline_text).apply {
            setChronometer(R.id.deadline_chronometer, SystemClock.elapsedRealtime() + remaining, "$label %s", true)
            setChronometerCountDown(R.id.deadline_chronometer, true)
            setTextColor(R.id.deadline_chronometer, color.argb)
            setTextViewTextSize(R.id.deadline_chronometer, TypedValue.COMPLEX_UNIT_SP, size)
        }
    }
}

/** Blush capsule with a small pixel gavel: the widget's one status. Always a single line. */
@Composable
fun PleadStatusChip(
    text: String,
    glyph: PixelJudgeGlyph.Kind? = PixelJudgeGlyph.Kind.gavel,
    maxScale: Float? = null,
    modifier: GlanceModifier = GlanceModifier,
) {
    val context = LocalContext.current
    Row(
        modifier = modifier.background(ImageProvider(R.drawable.widget_capsule_chip)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            PixelJudgeGlyph(kind = glyph, size = 12.dp)
            Spacer(GlanceModifier.width(4.dp))
        }
        Text(
            text = text,
            style = PleadWidgetFont.ui(context, WidgetTextStyle.caption, WidgetFontWeight.bold, PleadWidgetPalette.courtBurgundy, maxScale),
            maxLines = 1,
        )
    }
}

/** Burgundy next-action pill ("Enter plea ›"). Decorative: the whole widget is the tap target. */
@Composable
fun PleadActionPill(title: String, maxScale: Float? = null, modifier: GlanceModifier = GlanceModifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth().background(ImageProvider(R.drawable.widget_capsule_burgundy))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = PleadWidgetFont.ui(context, WidgetTextStyle.subheadline, WidgetFontWeight.bold, PleadWidgetPalette.paperWhite, maxScale),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight(),
        )
        Spacer(GlanceModifier.width(2.dp))
        Image(provider = ImageProvider(R.drawable.widget_chevron_right), contentDescription = null, modifier = GlanceModifier.size(11.dp))
    }
}

/** Mahogany panel with the judge at the bench (medium widget's left column, image 1). */
@Composable
fun PleadBenchPanel(judgeSize: Dp = 96.dp, modifier: GlanceModifier = GlanceModifier) {
    Box(
        modifier = modifier.background(ImageProvider(R.drawable.widget_bench_panel)),
        contentAlignment = Alignment.BottomCenter,
    ) {
        // Panel grooves.
        Row(
            modifier = GlanceModifier.fillMaxSize().padding(bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (i in 0 until 4) {
                if (i > 0) Spacer(GlanceModifier.width(18.dp))
                Box(GlanceModifier.width(2.dp).fillMaxHeight().background(PleadWidgetPalette.woodDark.copy(alpha = 0.55f))) {}
            }
        }
        PixelJudgeGlyph(kind = PixelJudgeGlyph.Kind.bench, size = judgeSize)
    }
}

/** The glyph a state wears on the circular accessory: judge face / gavel / heart. */
val WidgetState.circularGlyph: PixelJudgeGlyph.Kind
    get() = when (this) {
        WidgetState.summoned, WidgetState.deliberating, WidgetState.verdictReady -> PixelJudgeGlyph.Kind.face
        WidgetState.yourTurn, WidgetState.settlement, WidgetState.judgementDue, WidgetState.agreementDue -> PixelJudgeGlyph.Kind.gavel
        WidgetState.none -> PixelJudgeGlyph.Kind.heart
    }
