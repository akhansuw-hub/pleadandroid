// Port of ArgueWin/Services/TrackingPermissionService.swift. Android has no App Tracking Transparency (amendment az):
// the same API, always reporting `authorized` ("not required"), so the Privacy step's CTA just continues and AppsFlyer
// starts whenever a dev key is configured.
@file:Suppress("EnumEntryName")

package app.plead.android.services

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Mirror of the App Tracking Transparency state, persisted as `att_status` (amendment g, restored by amendment at).
 * Nothing about cases, evidence, partner names or transcripts is ever sent to advertising services.
 */
enum class TrackingStatus(val rawValue: String) {
    notDetermined("notDetermined"), restricted("restricted"), denied("denied"), authorized("authorized");

    val isDetermined: Boolean get() = this != notDetermined

    companion object {
        fun fromRaw(raw: String?): TrackingStatus? = entries.firstOrNull { it.rawValue == raw }
    }
}

/**
 * The tracking step's permission (amendment at). iOS shows Apple's ATT prompt from the Privacy screen's CONTINUE;
 * Android has no such prompt (amendment az), so [status] is always [TrackingStatus.authorized] and [request] returns
 * at once (still calling [onAnswer], so the RevenueCat ↔ AppsFlyer link re-sends identifiers as on iOS).
 */
class TrackingPermissionService(private val defaults: UserDefaults = UserDefaults.standard) {
    var status: TrackingStatus by mutableStateOf(TrackingStatus.authorized)
        private set

    /** Called with every answer (the RevenueCat ↔ AppsFlyer link re-sends identifiers). */
    var onAnswer: ((TrackingStatus) -> Unit)? = null

    init {
        set(TrackingStatus.authorized)
    }

    fun refresh() {
        set(TrackingStatus.authorized)
    }

    /** No system prompt on Android: reports the (always determined) status. */
    suspend fun request(): TrackingStatus {
        refresh()
        onAnswer?.invoke(status)
        return status
    }

    private fun set(new: TrackingStatus) {
        status = new
        defaults.set(new.rawValue, storageKey)
    }

    companion object {
        const val storageKey = "att_status"

        fun cta(status: TrackingStatus): PermissionCTA = if (status.isDetermined) PermissionCTA.continueOnly else PermissionCTA.requestNative
    }
}
