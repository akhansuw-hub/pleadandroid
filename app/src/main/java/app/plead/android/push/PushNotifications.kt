// The alert half of ArgueWin/App/AppDelegate.swift + the system's APNs presentation, on Android: an FCM data message
// (amendment az: the same fields as the APNs payload, as strings) becomes a notification the app builds itself.
//
//   channel      `category` (PushCategory raw value = the channel PleadApplication registers; unknown → general)
//   title/body   `title` / `body` exactly as sent (the server's copy library: generic unless the recipient turned on
//                "Show case details on Lock Screen"); emoji are never shown (defensive: the server sends none)
//   replace      `collapse_id` is the notification tag, so a newer push for the same action replaces the older one
//                (APNs `apns-collapse-id`)
//   lock screen  VISIBILITY_PRIVATE with a generic public version ("Plead" / "Court notice", iOS
//                `hiddenPreviewsBodyPlaceholder`) unless the user opted into case details, then VISIBILITY_PUBLIC
//   tap          MainActivity with every data field as a string extra (`link`, `case_id`, `screen`, …): MainActivity
//                sends it through `PushService.opened` (`push_opened`, then `data.link` or `{case_id, screen}`)
package app.plead.android.push

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.plead.android.R
import app.plead.android.app.MainActivity
import app.plead.android.services.NotificationPrefs
import app.plead.android.services.PushCategory
import app.plead.android.services.UserDefaults
import app.plead.android.widgets.PleadWidgetPalette
import app.plead.android.widgets.argb

object PushNotifications {
    /** Tag prefix for alert notifications (the court-session notification uses its own). */
    const val tagPrefix = "push:"

    /** Every alert shares one id; the tag (collapse id) tells them apart. */
    const val notificationId = 1

    /** The tapped notification's intent action (MainActivity reads the extras). */
    const val ACTION_OPEN = "app.plead.android.push.OPEN"

    /** Lock-screen-safe public version (iOS hidden previews). */
    const val publicTitle = "Plead"
    val publicBody: String get() = PushCategory.hiddenPreviewsBodyPlaceholder

    /** The channel for a payload's `category` (unknown or missing → general). */
    fun channelId(category: String?): String =
        PushCategory.entries.firstOrNull { it.rawValue == category }?.rawValue ?: PushCategory.general.rawValue

    /** Notification tag: the collapse id when there is one (replaces the older push), else unique. */
    fun tag(data: Map<String, String>, unique: () -> Long = System::nanoTime): String =
        tagPrefix + (data["collapse_id"]?.takeIf { it.isNotBlank() } ?: "n${unique()}")

    /** The "Show case details on Lock Screen" setting (amendment o), cached locally by NotificationPrefsModel. */
    fun lockscreenDetails(defaults: UserDefaults = UserDefaults.standard): Boolean = NotificationPrefs.cached(defaults).lockscreenDetails

    /** The intent a tap opens: MainActivity with every payload field as a string extra. */
    fun tapIntent(context: Context, data: Map<String, String>): Intent =
        Intent(context, MainActivity::class.java).setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply {
                for ((k, v) in data) putExtra(k, v)
            }

    /** Builds the notification for an alert payload, or null when it has no title/body (not an alert). */
    fun build(context: Context, data: Map<String, String>, detailed: Boolean, requestCode: Int): Notification? {
        val title = data["title"]?.let(::stripEmoji)?.trim().orEmpty()
        val body = data["body"]?.let(::stripEmoji)?.trim().orEmpty()
        if (title.isEmpty() && body.isEmpty()) return null
        val channel = channelId(data["category"])
        val tap = PendingIntent.getActivity(
            context, requestCode, tapIntent(context, data),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val color = PleadWidgetPalette.courtBurgundy.argb
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_plead)
            .setColor(color)
            .setContentTitle(publicTitle)
            .setContentText(publicBody)
            .build()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_plead)
            .setColor(color)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(tap)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (data["interruption_level"] == "time-sensitive") NotificationCompat.CATEGORY_EVENT else NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(if (detailed) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .build()
    }

    /** Shows an alert push (no-op without the notification permission). Returns the tag it was posted under. */
    fun show(context: Context, data: Map<String, String>, detailed: Boolean = lockscreenDetails()): String? {
        if (!canPost(context)) return null
        val tag = tag(data)
        val notification = build(context, data, detailed, requestCode = tag.hashCode()) ?: return null
        return runCatching {
            @Suppress("MissingPermission") // checked in canPost
            NotificationManagerCompat.from(context).notify(tag, notificationId, notification)
            tag
        }.getOrNull()
    }

    /** Notifications allowed (API 33+: POST_NOTIFICATIONS granted) and not blocked for the app. */
    fun canPost(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /** No emojis in pushes (CLAUDE.md): drops pictographs, flags, dingbats, variation selectors and joiners. */
    fun stripEmoji(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (!isEmoji(cp)) out.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return out.toString().replace(Regex(" {2,}"), " ")
    }

    private fun isEmoji(cp: Int): Boolean = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2300..0x23FF ||
        cp in 0x2B00..0x2BFF || cp in 0x1F1E6..0x1F1FF || cp == 0xFE0F || cp == 0x200D || cp in 0xE0020..0xE007F
}
