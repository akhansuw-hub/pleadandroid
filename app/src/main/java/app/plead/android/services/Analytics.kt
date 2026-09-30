// Port of ArgueWin/Features/Paywall/Analytics.swift. The services call it on every platform path, so it lives in
// `services/` (wave 3c imports `app.plead.android.services.Analytics` instead of re-declaring it in
// `features/paywall/`; see docs/android-port/STATUS.md, wave 2a decisions).
package app.plead.android.services

import android.util.Log
import app.plead.android.BuildConfig

/**
 * Tiny analytics seam: DEBUG logs; a fixed allow-list of funnel event names (no props) also goes to AppsFlyer
 * when it is running (CONTRACTS-v2 amendment at). Props never leave the app.
 */
object Analytics {
    /** Test hook: every event, with its props (unit tests record them; nil in the app). */
    @Volatile var sink: ((String, Map<String, String>) -> Unit)? = null

    fun track(event: String, props: Map<String, String> = emptyMap()) {
        if (BuildConfig.DEBUG) {
            val suffix = if (props.isEmpty()) "" else " " + props.entries.sortedBy { it.key }.joinToString(" ") { "${it.key}=${it.value}" }
            runCatching { Log.d("analytics", "[analytics] $event$suffix") }
        }
        sink?.invoke(event, props)
        if (Attribution.forwardedEvents.contains(event)) {
            Attribution.forward(event)
        }
    }
}
