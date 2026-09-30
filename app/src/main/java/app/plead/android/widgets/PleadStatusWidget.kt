// Port of PleadWidgets/PleadStatusWidget.swift: the "Case status" widget. One entry = the app's latest snapshot,
// rendered as of `date` (countdowns hide once their deadline passes). The widget keeps no business state of its own:
// the app rewrites `widget-snapshot.json` (WidgetSnapshotStore) and reloads the widgets on every relevant change
// (and on silent pushes); WidgetCenter's APPWIDGET_UPDATE broadcast re-runs `provideGlance` here.
//
// Families (PORT.md §2): the same Glance widget answers every size (SizeMode.Responsive): a 1×1 cell is the circular
// accessory, a one-cell strip the rectangular accessory, 2×2 small, 4×2 medium. The three receivers only differ in the
// size the widget picker offers first (res/xml/plead_widget_info*.xml).
package app.plead.android.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import app.plead.android.app.MainActivity
import app.plead.android.services.WidgetFamily
import app.plead.android.services.WidgetSnapshot
import java.time.Instant

/** One timeline entry (Swift `PleadStatusEntry`). `snapshot` null = the app has never written one. */
data class PleadStatusEntry(val date: Instant, val snapshot: WidgetSnapshot?)

/** Swift `Timeline<PleadStatusEntry>`: the entries and when to ask again (`.after(date)`). */
data class PleadStatusTimeline(val entries: List<PleadStatusEntry>, val refreshAfter: Instant)

object PleadStatusProvider {
    /** Widget gallery / placeholder content (brief image 1). */
    fun placeholder(now: Instant = Instant.now()): PleadStatusEntry = PleadStatusEntry(now, WidgetSnapshot.sample())

    /**
     * Now, plus a refresh at the deadline (the countdown line drops away); then ask again within the hour in case a
     * background refresh wrote a new snapshot without reloading.
     */
    fun timeline(snapshot: WidgetSnapshot?, now: Instant): PleadStatusTimeline {
        val entries = mutableListOf(PleadStatusEntry(now, snapshot))
        WidgetSnapshot.activeDeadline(snapshot?.primary, now)?.let { deadline ->
            entries.add(PleadStatusEntry(deadline.plusSeconds(1), snapshot))
        }
        val last = entries.last().date
        val next = minOf(last.plusSeconds(60), now.plusSeconds(3600))
        return PleadStatusTimeline(entries, maxOf(next, last.plusSeconds(60)))
    }

    /** The entry to render now: the latest entry whose date has come. */
    fun current(timeline: PleadStatusTimeline, now: Instant): PleadStatusEntry =
        timeline.entries.lastOrNull { !it.date.isAfter(now) } ?: timeline.entries.first()
}

/** The Glance widget (Swift `PleadStatusWidget` + its provider). */
class PleadStatusWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Responsive(PleadWidgetContent.sizes)

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val now = Instant.now()
        val snapshot = WidgetSnapshot.load()
        val timeline = PleadStatusProvider.timeline(snapshot, now)
        // WidgetKit's timeline: re-render just after the deadline (the countdown line drops away).
        PleadWidgets.scheduleRefresh(context, timeline, now)
        provideContent { PleadStatusEntryView(PleadStatusProvider.current(timeline, now)) }
    }

    companion object {
        /** Swift `PleadStatusWidget.kind` (WidgetSetupService reports every Plead widget under it). */
        const val kind = "PleadStatusWidget"

        /** Widget gallery name and description (Swift `configurationDisplayName` / `description`). */
        const val displayName = "Case status"
        const val description = "Know when the court needs you: summons, your turn, offers and verdicts."

        /** Home Screen content margin (iOS: the system's 16 pt widget margins; launchers add their own padding). */
        val contentPadding = 12.dp
    }
}

/** Swift `PleadStatusEntryView`: the family's view, the container background and the tap target (`widgetURL`). */
@Composable
fun PleadStatusEntryView(entry: PleadStatusEntry) {
    val size = LocalSize.current
    val family = PleadWidgetContent.family(size.width.value, size.height.value)
    val link = entry.snapshot?.link ?: WidgetSnapshot.homeLink
    PleadWidgetContainer(family = family, link = link) {
        val snapshot = entry.snapshot
        if (snapshot != null) PleadWidgetContent(snapshot, family, entry.date) else PleadWidgetPlaceholderView(family)
    }
}

/**
 * `containerBackground(for: .widget)`: warmCream behind small / medium, nothing behind the accessories (they draw their
 * own card / circle). The whole widget opens `link` in MainActivity (same deep link as the iOS `widgetURL`).
 */
@Composable
fun PleadWidgetContainer(family: WidgetFamily, link: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val home = family == WidgetFamily.systemSmall || family == WidgetFamily.systemMedium
    val base = GlanceModifier.fillMaxSize().clickable(actionStartActivity(PleadWidgets.tapIntent(context, link)))
    val modifier = if (home) {
        base.appWidgetBackground().cornerRadius(24.dp).background(ImageProvider(app.plead.android.R.drawable.widget_background))
            .padding(PleadStatusWidget.contentPadding)
    } else {
        base
    }
    Box(modifier = modifier) { content() }
}

/** Opens a widget link in the app (MainActivity routes `ACTION_VIEW` data through DeepLinkRouter). */
internal fun tapIntentFor(context: Context, link: String): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra("link", link)
