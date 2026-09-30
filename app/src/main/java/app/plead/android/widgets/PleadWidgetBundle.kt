// Port of PleadWidgets/PleadWidgetBundle.swift: the Plead widgets (the status widget; the Live Activity is the
// court-session notification in push/) and their receivers, plus WidgetKit's reload / timeline plumbing on Android.
package app.plead.android.widgets

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import app.plead.android.services.WidgetCenter
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Small (2×2) — the receiver wave 1 declared (`res/xml/plead_widget_info.xml`). */
class PleadWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PleadStatusWidget()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == PleadWidgets.ACTION_REFRESH) {
            PleadWidgets.updateAllAsync(context, goAsync())
            return
        }
        super.onReceive(context, intent)
    }
}

/** Medium (4×2), `res/xml/plead_widget_info_medium.xml`. */
class PleadMediumWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PleadStatusWidget()
}

/** The 1×1 "glance" cell (iOS Lock Screen accessory), `res/xml/plead_widget_info_glance.xml`. */
class PleadGlanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PleadStatusWidget()
}

object PleadWidgets {
    /** Every Plead widget receiver (WidgetCenter reloads them; WidgetSetupService counts them). */
    val receivers: List<Class<out GlanceAppWidgetReceiver>> = listOf(
        PleadWidgetReceiver::class.java,
        PleadMediumWidgetReceiver::class.java,
        PleadGlanceWidgetReceiver::class.java,
    )

    /** Explicit broadcast to [PleadWidgetReceiver]: re-render every Plead widget (the timeline's deadline entry). */
    const val ACTION_REFRESH = "app.plead.android.widgets.REFRESH"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Registers the medium and glance receivers with [WidgetCenter] so `WidgetSnapshotStore`'s reloads reach them too
     * (the small receiver is registered by WidgetCenter itself). Idempotent. Called once from `PleadApplication.onCreate`.
     */
    fun install() {
        synchronized(WidgetCenter.receivers) {
            for (r in receivers) if (!WidgetCenter.receivers.contains(r)) WidgetCenter.receivers.add(r)
        }
    }

    /** `WidgetCenter.reloadAllTimelines()` straight through Glance (every placed Plead widget re-renders). */
    suspend fun updateAll(context: Context) {
        PleadStatusWidget().updateAll(context)
    }

    internal fun updateAllAsync(context: Context, pending: android.content.BroadcastReceiver.PendingResult?) {
        scope.launch {
            try {
                runCatching { updateAll(context.applicationContext) }
            } finally {
                pending?.finish()
            }
        }
    }

    /** Placed Plead widgets, by receiver. */
    fun placedWidgetIds(context: Context): Map<Class<*>, IntArray> {
        val manager = AppWidgetManager.getInstance(context) ?: return emptyMap()
        return receivers.associateWith { manager.getAppWidgetIds(ComponentName(context, it)) ?: IntArray(0) }
    }

    fun tapIntent(context: Context, link: String): Intent = tapIntentFor(context, link)

    /**
     * WidgetKit's timeline on Android: when the timeline has a later entry (the deadline), an inexact alarm re-renders
     * the widgets just after it; the hourly re-ask is `updatePeriodMillis` in the provider info. Replaces any previous
     * alarm (one per app).
     */
    fun scheduleRefresh(context: Context, timeline: PleadStatusTimeline, now: Instant) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(ACTION_REFRESH).setComponent(ComponentName(context, PleadWidgetReceiver::class.java))
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val next = timeline.entries.map { it.date }.firstOrNull { it.isAfter(now) }
        if (next == null) {
            alarms.cancel(pending)
            return
        }
        runCatching { alarms.set(AlarmManager.RTC, next.toEpochMilli(), pending) }
    }
}
