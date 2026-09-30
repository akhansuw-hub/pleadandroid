// Port of ArgueWin/Services/WidgetSetupService.swift. Android: a Plead widget is "set up" when the launcher has one
// pinned (`AppWidgetManager.getAppWidgetIds`); "Live Activities enabled" means the court-session notification can
// show (notifications allowed; wave 3f's ongoing notification replaces the Live Activity).
@file:Suppress("EnumEntryName")

package app.plead.android.services

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import app.plead.android.app.PleadApplication

/**
 * WidgetKit's `WidgetFamily`, the sizes Plead's widgets come in. Android's Glance widgets map onto the same names
 * (PORT.md §2: small / medium / a 1×1 "glance" cell for the Lock Screen accessory).
 */
enum class WidgetFamily {
    systemSmall, systemMedium, systemLarge, systemExtraLarge, accessoryCircular, accessoryRectangular, accessoryInline;

    companion object {
        /** A pinned widget's size (dp) → family: 1×1 cell → circular accessory, up to ~2 cells → small, wider → medium. */
        fun fromSize(minWidthDp: Int): WidgetFamily = when {
            minWidthDp in 1 until 110 -> accessoryCircular
            minWidthDp < 250 -> systemSmall
            else -> systemMedium
        }
    }
}

/** One configured widget: its kind and family (Swift's `(kind: String, family: WidgetFamily)` tuple). */
data class WidgetConfiguration(val kind: String, val family: WidgetFamily)

/**
 * Widget / court-notification setup detection for onboarding screen 8 (CONTRACTS-v2 amendment r).
 *
 * Neither platform has an "allow widgets" permission: people add widgets themselves. Plead only *detects* what they
 * placed and reads whether the court-session notification may show. It never prompts for either.
 *
 * A value type of two functions so tests and the DEBUG harness can substitute the platform calls.
 */
data class WidgetSetupService(
    /** The user's configured widgets as `(kind, family)` pairs. */
    val currentConfigurations: suspend () -> List<WidgetConfiguration>,
    /** iOS `ActivityAuthorizationInfo().areActivitiesEnabled`; Android: notifications allowed for Plead. */
    val activitiesEnabled: () -> Boolean,
) {
    /** The families of the Plead widgets the user has placed, in a stable order. */
    suspend fun detectConfiguredWidgets(): List<WidgetFamily> = pleadFamilies(currentConfigurations())

    val liveActivitiesEnabled: Boolean get() = activitiesEnabled()

    companion object {
        /**
         * Every Plead widget kind that counts as "set up" (only the status widget today). Mirrors
         * `PleadStatusWidget.kind` on iOS; Android reports every Plead app widget under this kind.
         */
        val pleadWidgetKinds: Set<String> = setOf("PleadStatusWidget")

        /** Pure filter (unit-tested): our kinds only, de-duplicated, small → large → Lock Screen. */
        fun pleadFamilies(configs: List<WidgetConfiguration>): List<WidgetFamily> {
            val families = configs.filter { pleadWidgetKinds.contains(it.kind) }.map { it.family }
            val seen = mutableSetOf<String>()
            return families.filter { seen.add(analyticsName(it)) }.sortedBy(::order)
        }

        /** `widget_detected_after_setup {family}` value. */
        fun analyticsName(family: WidgetFamily): String = when (family) {
            WidgetFamily.systemSmall -> "system_small"
            WidgetFamily.systemMedium -> "system_medium"
            WidgetFamily.systemLarge -> "system_large"
            WidgetFamily.systemExtraLarge -> "system_extra_large"
            WidgetFamily.accessoryCircular -> "accessory_circular"
            WidgetFamily.accessoryRectangular -> "accessory_rectangular"
            WidgetFamily.accessoryInline -> "accessory_inline"
        }

        private fun order(family: WidgetFamily): Int = when (family) {
            WidgetFamily.systemSmall -> 0
            WidgetFamily.systemMedium -> 1
            WidgetFamily.systemLarge -> 2
            WidgetFamily.systemExtraLarge -> 3
            WidgetFamily.accessoryRectangular -> 4
            WidgetFamily.accessoryCircular -> 5
            WidgetFamily.accessoryInline -> 6
        }

        /** The real platform calls. */
        val live: WidgetSetupService
            get() = WidgetSetupService(
                currentConfigurations = { liveConfigurations(PleadApplication.contextOrNull) },
                activitiesEnabled = {
                    PleadApplication.contextOrNull?.let { NotificationManagerCompat.from(it).areNotificationsEnabled() } ?: true
                },
            )

        private fun liveConfigurations(context: Context?): List<WidgetConfiguration> {
            context ?: return emptyList()
            val manager = AppWidgetManager.getInstance(context) ?: return emptyList()
            return WidgetCenter.receivers.flatMap { receiver ->
                manager.getAppWidgetIds(ComponentName(context, receiver)).map { id ->
                    val width = manager.getAppWidgetOptions(id)?.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) ?: 0
                    WidgetConfiguration(kind = "PleadStatusWidget", family = WidgetFamily.fromSize(width))
                }
            }
        }

        /** Fixed answers (tests, previews, the DEBUG harness's `AWWidgetDetected` / `AWLiveActivitiesOff`). */
        fun fixed(families: List<WidgetFamily>, activitiesEnabled: Boolean): WidgetSetupService {
            val configs = families.map { WidgetConfiguration(kind = "PleadStatusWidget", family = it) }
            return WidgetSetupService(currentConfigurations = { configs }, activitiesEnabled = { activitiesEnabled })
        }
    }
}
