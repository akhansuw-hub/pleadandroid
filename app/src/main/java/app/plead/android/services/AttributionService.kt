// Port of ArgueWin/Services/AttributionService.swift (AppsFlyer, CONTRACTS-v2 amendment at; amendment az: no ATT on
// Android, AppsFlyer starts only when a dev key is configured).
package app.plead.android.services

import android.app.Application
import app.plead.android.app.DemoHarness
import app.plead.android.app.LaunchArguments
import com.appsflyer.AppsFlyerLib

/**
 * AppsFlyer install / campaign attribution (CONTRACTS-v2 amendment at).
 *
 * Configured once at launch from `BuildConfig` (`APPSFLYER_DEV_KEY` from `local.properties`). iOS waits up to 60 s
 * for the ATT answer; Android has no ATT (amendment az), so the SDK starts immediately and on every activation.
 * Never started without a real key, in DEBUG demo mode or under unit tests. No AppsFlyer customer user id, and no
 * case content or personal data in any event: `forward` only sends a fixed allow-list of funnel event names without
 * values. Purchases are reported by RevenueCat's server-side AppsFlyer integration, never from here.
 *
 * AppsFlyer identifies an Android app by its package name, so the iOS `appID` requirement does not apply: the
 * Android rule is `shouldStart(devKey:, demo:, underTests:)` with the app id treated as present.
 */
object Attribution {
    /** In-app events forwarded to AppsFlyer by name only (no values). */
    val forwardedEvents: Set<String> = setOf("onboarding_completed", "paywall_viewed")

    /** iOS: the ATT wait before the first launch event (amendment at). Android has no ATT: kept for parity. */
    const val attTimeout: Double = 60.0

    /** AppsFlyer was configured and is started on activation. */
    @Volatile var isEnabled: Boolean = false
        private set

    private var application: Application? = null

    /** Whether this process may run AppsFlyer at all (pure; unit-tested). `appID` is the iOS App Store id. */
    fun shouldStart(devKey: String?, appID: String?, demo: Boolean, underTests: Boolean): Boolean {
        if (devKey.isNullOrEmpty() || appID.isNullOrEmpty()) return false
        return !demo && !underTests
    }

    /** Android: the app is keyed by package name, so only the dev key, demo mode and tests decide. */
    fun shouldStart(devKey: String?, demo: Boolean, underTests: Boolean): Boolean =
        shouldStart(devKey, appID = "android", demo = demo, underTests = underTests)

    /** `Application.onCreate` (iOS `application(_:didFinishLaunchingWithOptions:)`). */
    fun configure(app: Application) {
        if (isEnabled) return
        // Mirrors ArgueWinApp: no Supabase project → demo by default; `AWDemo YES` / `NO` wins (debug only).
        val demo = LaunchArguments.isEnabled && (if (LaunchArguments.has("AWDemo")) DemoHarness.isDemo else !AppConfig.isSupabaseConfigured)
        val underTests = isUnderTests()
        val key = AppConfig.appsFlyerDevKey
        if (!shouldStart(key, demo, underTests) || key == null) return
        val lib = AppsFlyerLib.getInstance()
        lib.setDebugLog(app.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
        lib.init(key, null, app)
        application = app
        isEnabled = true
        lib.start(app)
    }

    /** Scene became active (iOS `didBecomeActive` → `start()`). */
    fun becameActive() {
        val app = application ?: return
        if (isEnabled) AppsFlyerLib.getInstance().start(app)
    }

    /** The AppsFlyer install id (for RevenueCat's `$appsflyerId`), null while AppsFlyer is off. */
    val appsFlyerUID: String?
        get() {
            val app = application ?: return null
            if (!isEnabled) return null
            return AppsFlyerLib.getInstance().getAppsFlyerUID(app)
        }

    /** `Analytics.track` hands every event here; only the allow-listed names go out, never with values. */
    fun forward(event: String) {
        val app = application ?: return
        if (!isEnabled || !forwardedEvents.contains(event)) return
        AppsFlyerLib.getInstance().logEvent(app, event, null)
    }

    private fun isUnderTests(): Boolean =
        runCatching { Class.forName("org.junit.Test"); true }.getOrDefault(false)
}
