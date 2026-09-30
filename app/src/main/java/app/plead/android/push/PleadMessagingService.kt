// Firebase Cloud Messaging entry point (PORT.md §2 Push; amendment az). Declared in the manifest now so wave 3f
// fills it in without touching the manifest: token → `register_push {token, timezone, platform: "fcm"}` through
// PushService, data pushes → the same handling as AppDelegate's APNs callbacks, and the ongoing
// "court in session" notification (push/CourtSessionNotification.kt) that replaces the Live Activity.
//
// Without google-services.json Firebase never initialises, this service never receives anything, and token
// registration is skipped (as on iOS when APNs is unavailable).
package app.plead.android.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PleadMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        // Wave 3f: PushService.didRegister(token) → register_push with platform "fcm".
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // Wave 3f: silent refresh (amendment o), banner while foregrounded, court-session notification update.
    }
}
