// Android counterpart of the process-level setup in ArgueWin/App/ArgueWinApp.swift + AppDelegate.swift: the one
// AppModel of the process (the App struct's `@State` model), notification channels (iOS notification categories),
// AppsFlyer, and the context the services need.
package app.plead.android.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import app.plead.android.push.CourtSessionNotification
import app.plead.android.services.Attribution
import app.plead.android.services.PushCategory
import app.plead.android.widgets.PleadWidgets

class PleadApplication : Application() {
    /** The process's AppModel, built on first use (after MainActivity has read the launch flags). */
    private var model: AppModel? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerNotificationChannels(this)
        // The medium and 1x1 glance receivers join WidgetCenter.receivers (wave 3f).
        PleadWidgets.install()
    }

    /**
     * iOS `ArgueWinApp.model`: demo (`AWDemo YES`, or no Supabase project in a debug build) runs on PreviewData with
     * no network; otherwise the live model. Decides the cold open before the first frame so the gate never flashes
     * underneath it. Called by MainActivity once the launch flags are loaded.
     */
    fun appModel(): AppModel {
        model?.let { return it }
        // AppsFlyer attribution (amendments at, az): after the flags, so demo launches never start it.
        Attribution.configure(this)
        val demo = DemoHarness.isDemo
        val made = if (demo) DemoHarness.model() else AppModel.live()
        // Demo runs skip the cold open unless `AWColdOpen full|sting` asks for it.
        made.coldOpen.launch(forced = DemoHarness.coldOpen, demo = demo, reduceMotion = systemReduceMotion(this))
        // The Live Activity's Android replacement: the ongoing "court in session" notification (wave 3f).
        made.courtSession = CourtSessionNotification.shared
        if (demo) DemoHarness.applyCourtSessionDemo(made)
        model = made
        return made
    }

    /** The model if MainActivity has built it (the FCM service routes taps and tokens through it). */
    val existingModel: AppModel? get() = model

    companion object {
        /** The process's Application, for services that need a Context (DataStore, notifications, widgets). */
        lateinit var instance: PleadApplication
            private set

        /** The Application context, or null in plain JVM unit tests (no Application). */
        val contextOrNull: Context?
            get() = if (::instance.isInitialized) instance else null

        /** Android's Reduce Motion: animations switched off in system settings (or `AWDemoReduceMotion YES`). */
        fun systemReduceMotion(context: Context): Boolean = DemoHarness.demoReduceMotion ||
            runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

        /** User-visible channel names (system notification settings). No emojis; generic like the lock-screen copy. */
        fun channelName(category: PushCategory): String = when (category) {
            PushCategory.summons -> "Summons"
            PushCategory.turn -> "Your turn in court"
            PushCategory.settlement -> "Settlement offers"
            PushCategory.trial -> "Trial times"
            PushCategory.verdict -> "Verdicts"
            PushCategory.judgement -> "Judgements"
            PushCategory.reminder -> "Deadline reminders"
            PushCategory.partner -> "Your partner"
            PushCategory.general -> "Court notices"
        }

        /**
         * AppDelegate's `PushService.registerCategories()`: one channel per push category (the backend's `aps.category`,
         * FCM `channel_id`). No actions: consequential choices (plea, accept, reject) always open the app.
         */
        fun registerNotificationChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channels = PushCategory.entries.map { category ->
                val importance = if (category == PushCategory.reminder || category == PushCategory.general) {
                    NotificationManager.IMPORTANCE_DEFAULT
                } else {
                    NotificationManager.IMPORTANCE_HIGH
                }
                // No channel-level lockscreenVisibility: a channel override would force redaction, so "Show case
                // details on Lock Screen" could never take effect. Each notification sets its own visibility
                // (PushNotifications: private with a generic public version unless the user opted in, amendment o).
                NotificationChannel(category.rawValue, channelName(category), importance)
            }
            manager.createNotificationChannels(channels)
        }
    }
}
