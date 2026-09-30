// Port of ArgueWin/Services/PushService.swift. Android: FCM (amendment az). The token comes from
// `FirebaseMessaging.getInstance().token` when Firebase is initialised (a `google-services.json` was built in),
// otherwise registration is skipped, as on iOS without APNs. `register_live_activity` is never called.
package app.plead.android.services

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.plead.android.app.PleadApplication
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * FCM registration + `register_push` scheduling. Taps are routed through [DeepLinkRouter] by MainActivity.
 *
 * `register_push` is the only way to set the user's time zone (quiet hours, push dates) and push token
 * (20260924000700). The app sends:
 * - `{timezone}` on launch and on every foreground, whatever the notification permission, coalesced so
 *   launch + foreground (or a quick background/foreground) send at most one call per 60 s;
 * - `{token, timezone, platform: "fcm"}` whenever an FCM token arrives (or the user changes);
 * - `{token: null}` on sign-out and account deletion, before the session is cleared.
 */
class PushService(
    private val now: () -> Instant = { Instant.now() },
    private val timeZone: () -> String = { ZoneId.systemDefault().id },
    private val context: Context? = PleadApplication.contextOrNull,
) {
    /** The notification permission as last read (iOS `UNAuthorizationStatus`). */
    var authorization: NotificationStatus by mutableStateOf(NotificationStatus.notDetermined)
        private set

    /** Sends one `register_push` call (set by `AppModel.start`; null in demo / previews). */
    var sender: (suspend (PushRegistration) -> Unit)? = null
    var currentUser: UUID? = null
        private set

    /** Silent (data-only) push: refresh app state before the widget snapshot is rewritten. */
    var onSilentRefresh: (suspend () -> Unit)? = null

    private var token: String? = null
    private var uploadedFor: Pair<String, UUID>? = null

    /**
     * Last time-zone send (a token upload carries the zone too). Cleared on failure so the next
     * launch / foreground retries.
     */
    private var lastZoneSync: Triple<UUID, String, Instant>? = null

    /** Between `unregister()` and the auth change: nothing more is sent for the outgoing user. */
    private var suspended = false

    fun refreshStatus() {
        val context = context ?: return
        authorization = if (androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            NotificationStatus.authorized
        } else {
            NotificationStatus.denied
        }
    }

    /**
     * iOS asks for permission and registers with APNs. Android asks through [NotificationPermissionService]
     * (the POST_NOTIFICATIONS prompt needs an Activity); here the FCM token is fetched when allowed.
     */
    suspend fun requestAuthorizationAndRegister() {
        registerIfAuthorized()
    }

    /** Re-register silently on launch if already authorised (tokens can rotate). */
    suspend fun registerIfAuthorized() {
        refreshStatus()
        if (authorization == NotificationStatus.authorized || authorization == NotificationStatus.provisional) {
            fetchToken()?.let { didReceive(it) }
        }
    }

    /** `FirebaseMessaging.getInstance().token`, or null when Firebase isn't configured (no google-services.json). */
    private suspend fun fetchToken(): String? {
        val context = context ?: return null
        if (FirebaseApp.getApps(context).isEmpty()) return null
        return suspendCancellableCoroutine { cont ->
            runCatching {
                FirebaseMessaging.getInstance().token
                    .addOnCompleteListener { task -> cont.resume(if (task.isSuccessful) task.result else null) }
            }.onFailure { cont.resume(null) }
        }
    }

    // MARK: register_push scheduling

    /**
     * The signed-in user changed (launch included). A new user gets any pending token and the
     * time zone; signing out forgets what was sent.
     */
    suspend fun userChanged(uid: UUID?) {
        currentUser = uid
        suspended = false
        if (uid == null) {
            uploadedFor = null
            lastZoneSync = null
            return
        }
        retryPending()
    }

    /**
     * Foreground (and after the profile row is first created, since `register_push` needs it):
     * upload a pending token, else send the time zone (coalesced).
     */
    suspend fun retryPending() {
        uploadTokenIfNeeded()
        syncTimezone()
    }

    /** `register_push {timezone}`, at most once per `coalesceWindow` for the same user and zone. */
    suspend fun syncTimezone() {
        if (suspended) return
        val user = currentUser ?: return
        val sender = sender ?: return
        val zone = timeZone()
        val at = now()
        val last = lastZoneSync
        if (last != null && last.first == user && last.second == zone && Duration.between(last.third, at).seconds < coalesceWindow) return
        // Claimed before the await so a concurrent launch/foreground call coalesces into this one.
        lastZoneSync = Triple(user, zone, at)
        try {
            sender(PushRegistration.timezone(zone))
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            val l = lastZoneSync
            if (l != null && l.first == user && l.third == at) lastZoneSync = null
        }
    }

    /** FirebaseMessagingService.onNewToken / the token fetch: an FCM registration token arrived. */
    suspend fun didReceive(token: String) {
        this.token = token
        uploadTokenIfNeeded()
    }

    /**
     * `register_push {token: null}` for the current user. Call before the session is cleared
     * (sign-out) or before `delete_account`. Best effort: failures are ignored.
     */
    suspend fun unregister() {
        if (currentUser == null) return
        val sender = sender ?: return
        suspended = true
        uploadedFor = null
        lastZoneSync = null
        try {
            sender(PushRegistration.clear)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline: the server row keeps the token until the next account on this device registers.
        }
    }

    /** Account deletion failed after `unregister()`: resume normal registration for this user. */
    suspend fun resume() {
        suspended = false
        retryPending()
    }

    private suspend fun uploadTokenIfNeeded() {
        if (suspended) return
        val token = token ?: return
        val user = currentUser ?: return
        val sender = sender ?: return
        val last = uploadedFor
        if (last != null && last.first == token && last.second == user) return
        val zone = timeZone()
        uploadedFor = token to user
        try {
            sender(PushRegistration.token(token, zone))
            lastZoneSync = Triple(user, zone, now())
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Retried on the next foreground / token callback.
            val u = uploadedFor
            if (u != null && u.first == token && u.second == user) uploadedFor = null
        }
    }

    /** A notification was tapped: `push_opened`, then the destination (null when the payload has none). */
    fun opened(info: Map<String, Any?>): DeepLink? {
        Analytics.track("push_opened", analyticsProps(info))
        return link(info)
    }

    /**
     * Silent push: refresh the store (via `onSilentRefresh`, set by `AppModel`), then write the widget
     * snapshot immediately. Observers of `pleadSilentRefresh` (the court-session notification) run afterwards.
     */
    suspend fun handleSilentPush(info: Map<String, Any?>) {
        Analytics.track("push_silent_received", analyticsProps(info))
        onSilentRefresh?.invoke()
        WidgetSnapshotStore.shared.refreshNow()
        AppNotifications.post(AppNotifications.pleadSilentRefresh)
    }

    companion object {
        /** Launch + foreground calls closer together than this are coalesced into one. */
        const val coalesceWindow: Long = 60

        val shared: PushService by lazy { PushService() }

        /** Flattens string values whether top-level or nested under `data`. */
        fun stringPayload(info: Map<String, Any?>): Map<String, String> {
            val out = mutableMapOf<String, String>()
            val sources = mutableListOf(info)
            @Suppress("UNCHECKED_CAST")
            (info["data"] as? Map<String, Any?>)?.let { sources.add(it) }
            for (source in sources) for ((k, v) in source) if (v is String) out[k] = v
            return out
        }

        /**
         * Where a tapped push lands: `data.link` (`plead://case/{id}/plea`, …, parsed by [DeepLink]),
         * else the legacy `{case_id, screen}` pair.
         */
        fun link(info: Map<String, Any?>): DeepLink? {
            val payload = stringPayload(info)
            payload["link"]?.let { raw -> DeepLink.parse(raw)?.let { return it } }
            return DeepLink.parse(push = payload)
        }

        /**
         * `aps.content-available == 1` with no alert (APNs shape), or an FCM data-only message
         * (`content_available` / `silent` = "1"/"true" with no `title`): a background state refresh.
         */
        fun isSilent(info: Map<String, Any?>): Boolean {
            @Suppress("UNCHECKED_CAST")
            val aps = info["aps"] as? Map<String, Any?>
            if (aps != null) {
                val available = when (val v = aps["content-available"]) {
                    is Number -> v.toInt()
                    is String -> v.toIntOrNull() ?: 0
                    else -> 0
                }
                return available == 1 && aps["alert"] == null
            }
            val payload = stringPayload(info)
            val flag = payload["content_available"] ?: payload["content-available"] ?: payload["silent"]
            return (flag == "1" || flag == "true") && payload["title"] == null
        }

        /** Analytics properties for a push (no case content). */
        fun analyticsProps(info: Map<String, Any?>): Map<String, String> {
            val payload = stringPayload(info)
            val props = mutableMapOf<String, String>()
            @Suppress("UNCHECKED_CAST")
            val category = ((info["aps"] as? Map<String, Any?>)?.get("category") as? String) ?: payload["category"]
            category?.let { props["category"] = it }
            (payload["type"] ?: payload["event"])?.let { props["type"] = it }
            val raw = payload["link"]
            if (raw != null) props["screen"] = raw.lastPathComponent
            else payload["screen"]?.let { props["screen"] = it }
            return props
        }
    }
}

/**
 * Categories the backend sets on court pushes (`aps.category`; the FCM notification channel on Android). No actions:
 * consequential choices (plea, accept, reject) always open the app. The generic placeholder covers hidden previews.
 */
enum class PushCategory(val rawValue: String) {
    summons("summons"), turn("turn"), settlement("settlement"), trial("trial"), verdict("verdict"),
    judgement("judgement"), reminder("reminder"), partner("partner"), general("general");

    companion object {
        /** iOS `hiddenPreviewsBodyPlaceholder`: the lock-screen-safe body. */
        const val hiddenPreviewsBodyPlaceholder = "Court notice"
    }
}
