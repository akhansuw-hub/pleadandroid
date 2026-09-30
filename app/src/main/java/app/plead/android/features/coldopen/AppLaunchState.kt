// Port of ArgueWin/Features/ColdOpen/AppLaunchState.swift.
package app.plead.android.features.coldopen

import app.plead.android.services.UserDefaults
import java.time.Instant

/**
 * Persisted launch facts the cold open decision needs (CONTRACTS-v2 amendment h).
 * Local only: a reinstall plays the full cinematic again, which is the intended first-launch experience.
 */
class AppLaunchState(private val defaults: UserDefaults = UserDefaults.standard) {
    /** The full cinematic (or a sting) has played to completion (or been skipped) at least once. */
    var hasSeenColdOpen: Boolean
        get() = defaults.bool(hasSeenKey)
        set(value) = defaults.set(value, hasSeenKey)

    /** The previous cold launch (before [recordLaunch]), if any. Stored as seconds since 1970 (Swift `Date`). */
    var lastLaunchDate: Instant?
        get() = defaults.doubleOrNull(lastLaunchKey)?.let { Instant.ofEpochMilli((it * 1000).toLong()) }
        set(value) {
            if (value == null) defaults.removeObject(lastLaunchKey) else defaults.set(value.toEpochMilli() / 1000.0, lastLaunchKey)
        }

    fun recordLaunch(date: Instant = Instant.now()) {
        lastLaunchDate = date
    }

    companion object {
        const val hasSeenKey = "coldOpen.hasSeen"
        const val lastLaunchKey = "coldOpen.lastLaunchDate"
    }
}
