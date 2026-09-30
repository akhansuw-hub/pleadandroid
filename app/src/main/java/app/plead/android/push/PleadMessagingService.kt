// Firebase Cloud Messaging entry point (PORT.md §2 Push; amendment az): the Android side of AppDelegate.swift's APNs
// callbacks.
//
//   onNewToken         → PushService.shared.didReceive(token) → `register_push {token, timezone, platform: "fcm"}`
//                        (uploaded as soon as a user is signed in; PushService coalesces and retries)
//   onMessageReceived  every Plead push is an FCM data message with the APNs payload's fields (_shared/fcm.ts):
//                        silent (`content_available`, no title) → PushService.handleSilentPush (refresh the store,
//                          rewrite the widget snapshot, re-run the court-session planner)
//                        alert → PushNotifications.show (channel = category, lock-screen visibility from the user's
//                          setting, tap → MainActivity extras)
//                      then the court-session notification catches up (CourtSessionNotification.pushReceived).
//
// Without google-services.json Firebase never initialises: this service never receives anything and token
// registration is skipped (as on iOS when APNs is unavailable). Nothing here needs Firebase to compile or run.
package app.plead.android.push

import android.content.Context
import app.plead.android.services.PushService
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PleadMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        PleadMessaging.newToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = HashMap(message.data)
        // A notification message (not what the backend sends, but e.g. a console test) carries its text here.
        message.notification?.let { n ->
            n.title?.let { data.putIfAbsent("title", it) }
            n.body?.let { data.putIfAbsent("body", it) }
            n.channelId?.let { data.putIfAbsent("category", it) }
        }
        PleadMessaging.received(applicationContext, data)
    }
}

/** The service's work, separate from `FirebaseMessagingService` so it runs (and is tested) without Firebase. */
object PleadMessaging {
    /** FCM gives a message about 10 s; the app state lives on the main thread. */
    private const val budgetMillis = 8_000L

    fun newToken(token: String) {
        onMain { PushService.shared.didReceive(token) }
    }

    /** Handles one push. Returns the tag of the notification it showed, if any. */
    fun received(context: Context, data: Map<String, String>, court: CourtSessionNotification? = null): String? {
        val info: Map<String, Any?> = data
        var shown: String? = null
        if (PushService.isSilent(info)) {
            onMain { PushService.shared.handleSilentPush(info) }
        } else {
            shown = PushNotifications.show(context, data)
        }
        onMain { runCatching { (court ?: CourtSessionNotification.shared).pushReceived(info) } }
        return shown
    }

    private fun onMain(block: suspend () -> Unit) {
        runCatching {
            // Already on the main thread (tests, or a caller on main): run inline; blocking main on Dispatchers.Main
            // would deadlock.
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                runBlocking { withTimeoutOrNull(budgetMillis) { block() } }
            } else {
                runBlocking { withContext(Dispatchers.Main) { withTimeoutOrNull(budgetMillis) { block() } } }
            }
        }
    }
}
