// FCM alert pushes (amendment az): the notification built from the backend's data message (the APNs payload's fields),
// its channel, collapse tag, lock-screen visibility and tap extras, and the service's silent / alert routing.
package app.plead.android.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.plead.android.app.PleadApplication
import app.plead.android.services.Analytics
import app.plead.android.services.PushCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class PushNotificationsTests {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private val case = "33333333-3333-4333-8333-333333333333"

    /** What `_shared/fcm.ts` sends for a summons (notify_test's payload, as FCM data). */
    private val summons = mapOf(
        "title" to "You've been summoned",
        "body" to "Your partner has filed a case. The court awaits your plea.",
        "category" to "summons",
        "link" to "plead://case/$case/plea",
        "screen" to "summons",
        "case_id" to case,
        "kind" to "summons",
        "action_key" to "plea",
        "thread_id" to case,
        "interruption_level" to "time-sensitive",
        "collapse_id" to "$case:plea",
    )

    @Before fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PleadApplication.registerNotificationChannels(context)
        Analytics.sink = null
    }

    @Test fun channelsAreThePushCategories() {
        for (c in PushCategory.entries) assertEquals(c.rawValue, PushNotifications.channelId(c.rawValue))
        assertEquals("general", PushNotifications.channelId("nope"))
        assertEquals("general", PushNotifications.channelId(null))
    }

    @Test fun collapseIdIsTheTag() {
        assertEquals("push:$case:plea", PushNotifications.tag(summons))
        assertEquals("push:n7", PushNotifications.tag(mapOf("title" to "x"), unique = { 7 }))
    }

    @Test fun genericByDefaultWithAHiddenPublicVersion() {
        val n = PushNotifications.build(context, summons, detailed = false, requestCode = 1)!!
        assertEquals("summons", n.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Plead", n.publicVersion.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Court notice", n.publicVersion.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("You've been summoned", n.extras.getString(Notification.EXTRA_TITLE))
        val detailed = PushNotifications.build(context, summons, detailed = true, requestCode = 1)!!
        assertEquals(Notification.VISIBILITY_PUBLIC, detailed.visibility)
    }

    @Test fun tapCarriesThePayloadToMainActivity() {
        val intent = PushNotifications.tapIntent(context, summons)
        assertEquals("app.plead.android.app.MainActivity", intent.component?.className)
        assertEquals("plead://case/$case/plea", intent.getStringExtra("link"))
        assertEquals(case, intent.getStringExtra("case_id"))
        assertEquals("summons", intent.getStringExtra("screen"))
    }

    @Test fun noEmojisEverReachTheShade() {
        assertEquals("All rise ", PushNotifications.stripEmoji("All rise 👨‍⚖️"))
        assertEquals("Court – adjourned…", PushNotifications.stripEmoji("Court – adjourned…"))
        val n = PushNotifications.build(context, summons + ("title" to "⚖️ You've been summoned"), false, 1)!!
        assertEquals("You've been summoned", n.extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun anAlertIsShownAndANewerPushForTheSameActionReplacesIt() {
        val court = CourtSessionNotification(context, app.plead.android.services.UserDefaults.inMemory(), activitiesEnabled = { false })
        val tag = PleadMessaging.received(context, summons, court)
        assertEquals("push:$case:plea", tag)
        PleadMessaging.received(context, summons + ("body" to "The court awaits your plea."), court)
        val posted = shadowOf(manager).allNotifications
        assertEquals(1, posted.size)
        assertEquals("The court awaits your plea.", posted.single().extras.getString(Notification.EXTRA_TEXT))
    }

    @Test fun silentPushShowsNothing() {
        val court = CourtSessionNotification(context, app.plead.android.services.UserDefaults.inMemory(), activitiesEnabled = { false })
        val events = mutableListOf<String>()
        Analytics.sink = { e, _ -> events.add(e) }
        val silent = mapOf("content_available" to "1", "case_id" to case, "kind" to "refresh", "link" to "plead://case/$case")
        assertNull(PleadMessaging.received(context, silent, court))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
        assertTrue(events.contains("push_silent_received"))
        Analytics.sink = null
    }

    @Test fun nothingWithoutPermission() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(PushNotifications.canPost(context))
        assertNull(PushNotifications.show(context, summons))
        assertNotNull(PushNotifications.build(context, summons, false, 1))
    }
}
