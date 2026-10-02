// The court-session notification (Live Activity replacement): start / update / end from the planner's actions, the
// plea linger, sign-out, user dismissal, the demo sessions and push-driven sessions when the app is not running.
package app.plead.android.push

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.plead.android.services.Analytics
import app.plead.android.services.CourtSessionPhase
import app.plead.android.services.LiveActivityPlanner
import app.plead.android.services.PleadActivityCopy
import app.plead.android.services.PleadCaseActivityAttributes
import app.plead.android.services.UserDefaults
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
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
class CourtSessionNotificationTests {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private var clock: Instant = Instant.parse("2026-09-30T12:00:00Z")
    private lateinit var defaults: UserDefaults
    private lateinit var court: CourtSessionNotification
    private val events = mutableListOf<Pair<String, Map<String, String>>>()
    private val caseId = UUID.fromString("33333333-3333-4333-8333-333333333333")

    @Before fun setUp() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        defaults = UserDefaults.inMemory()
        court = CourtSessionNotification(context, defaults, now = { clock })
        Analytics.sink = { e, p -> events.add(e to p) }
    }

    private fun posted(): List<Notification> = shadowOf(manager).allNotifications

    @Test fun demoSummonsIsAnOngoingCountdown() {
        court.startDemo("summons")
        val n = posted().single()
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(CourtSessionNotification.channelId, n.channelId)
        assertEquals("You've been summoned", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("The court awaits your plea", n.extras.getString(Notification.EXTRA_TEXT))
        assertEquals("Case #021", n.extras.getString(Notification.EXTRA_SUB_TEXT))
        assertTrue(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(clock.plusSeconds(23 * 3600 + 41 * 60).toEpochMilli(), n.`when`)
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
        assertEquals("live_activity_started" to mapOf("kind" to "summons", "source" to "demo"), events.single())
        assertEquals(listOf("00000000-0000-4000-8000-000000000021:summons"), court.sessions().map { it.activityId })
    }

    @Test fun updateThenEndOnAClosedCase() {
        val attrs = PleadCaseActivityAttributes(caseId, 14, PleadCaseActivityAttributes.Kind.verdict)
        val id = court.request(attrs, PleadActivityCopy.state(CourtSessionPhase.deliberating, clock.plusSeconds(1800)), "local")!!
        court.apply(LiveActivityPlanner.Action.update(id, PleadActivityCopy.state(CourtSessionPhase.verdictReady, null)))
        val n = posted().single()
        assertEquals("The judge has ruled", n.extras.getString(Notification.EXTRA_TITLE))
        assertFalse(n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER))
        assertEquals(CourtSessionPhase.verdictReady, court.sessions().single().contentState?.phase)
        court.apply(LiveActivityPlanner.Action.end(id, LiveActivityPlanner.finalState(CourtSessionPhase.verdictReady), LiveActivityPlanner.EndReason.verdictOpened))
        assertTrue(posted().isEmpty())
        assertTrue(court.sessions().isEmpty())
        assertTrue(court.finished.contains(id))
        assertEquals("live_activity_ended" to mapOf("kind" to "verdict", "reason" to "verdict_opened"), events.last())
    }

    @Test fun aPleaLingersAsConfirmation() {
        val attrs = PleadCaseActivityAttributes(caseId, 14, PleadCaseActivityAttributes.Kind.summons)
        val id = court.request(attrs, PleadActivityCopy.state(CourtSessionPhase.summoned, clock.plusSeconds(3600)), "local")!!
        court.apply(LiveActivityPlanner.Action.end(id, PleadActivityCopy.state(CourtSessionPhase.pleaEntered, null), LiveActivityPlanner.EndReason.pleaEntered))
        val n = posted().single()
        assertEquals("Plea entered", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(15 * 60_000L, n.timeoutAfter)
    }

    @Test fun signOutEndsEverything() = runBlocking {
        court.startDemo("summons")
        court.startDemo("verdict")
        assertEquals(2, posted().size)
        court.userChanged(null)
        assertTrue(posted().isEmpty())
        assertTrue(court.sessions().isEmpty())
    }

    @Test fun nothingWhenNotificationsAreOff() {
        val off = CourtSessionNotification(context, defaults, now = { clock }, activitiesEnabled = { false })
        off.startDemo("summons")
        assertTrue(posted().isEmpty())
        assertTrue(off.sessions().isEmpty())
    }

    @Test fun pushesDriveTheSessionWhenTheAppIsNotRunning() {
        val summons = mapOf("kind" to "summons", "case_id" to caseId.toString(), "title" to "You've been summoned")
        court.pushReceived(summons, appRunning = true)
        assertTrue(posted().isEmpty())
        court.pushReceived(summons, appRunning = false)
        val n = posted().single()
        assertEquals("You've been summoned", n.extras.getString(Notification.EXTRA_TITLE))
        assertNull(n.extras.getString(Notification.EXTRA_SUB_TEXT)) // no case number in a push
        court.pushReceived(summons, appRunning = false) // already running: not restarted
        assertEquals(1, court.sessions().size)

        court.pushReceived(mapOf("kind" to "verdict_soon", "case_id" to caseId.toString(), "title" to "x"), appRunning = false)
        court.pushReceived(mapOf("kind" to "verdict_ready", "case_id" to caseId.toString(), "title" to "x"), appRunning = false)
        val verdict = court.sessions().first { it.kind == "verdict" }
        assertEquals(CourtSessionPhase.verdictReady, verdict.contentState?.phase)
        // Silent pushes never start anything.
        court.pushReceived(mapOf("content_available" to "1", "case_id" to UUID.randomUUID().toString(), "kind" to "summons"), appRunning = false)
        assertEquals(2, court.sessions().size)
    }

    /** Product decision (CONTRACTS-v2 amendment ba): alerting section and status-bar icon, never a noise. */
    @Test fun channelIsDefaultImportanceButSilent() {
        manager.createNotificationChannel(android.app.NotificationChannel("court_session", "Court in session", NotificationManager.IMPORTANCE_LOW))
        CourtSessionNotification.ensureChannel(context)
        assertNull(manager.getNotificationChannel("court_session")) // the old low-importance channel is gone
        val channel = manager.getNotificationChannel(CourtSessionNotification.channelId)
        assertEquals("court_session_v2", channel.id)
        assertEquals("Court in session", channel.name.toString())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
        assertEquals(Notification.VISIBILITY_PUBLIC, channel.lockscreenVisibility)
    }

    /** The upgrade path: the launch-time setup (PleadApplication.onCreate) replaces the old channel before any post. */
    @Test fun launchSetsUpTheCourtSessionChannelAndDeletesTheOldOne() {
        // Robolectric starts the manifest's PleadApplication, so onCreate has already run: the channel exists.
        assertTrue(context is app.plead.android.app.PleadApplication)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("court_session_v2").importance)
        // An upgraded install: only the first build's low-importance channel.
        manager.deleteNotificationChannel("court_session_v2")
        manager.createNotificationChannel(android.app.NotificationChannel("court_session", "Court in session", NotificationManager.IMPORTANCE_LOW))
        app.plead.android.app.PleadApplication.setUpNotificationChannels(context)
        assertNull(manager.getNotificationChannel("court_session"))
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("court_session_v2").importance)
        assertNotNull(manager.getNotificationChannel("summons")) // the push categories too
        assertTrue(posted().isEmpty())
    }

    @Test fun postsAreSilentAndAlertOnce() {
        court.startDemo("summons")
        val n = posted().single()
        assertEquals("court_session_v2", n.channelId)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNull(n.sound)
        assertNull(n.vibrate)
        // NotificationCompat's setSilent: no sound / vibration and only a (never posted) summary may alert.
        assertEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
        @Suppress("DEPRECATION")
        assertEquals(Notification.PRIORITY_DEFAULT, n.priority)
    }

    @Test fun copyHelpersMatchTheLiveActivity() {
        assertEquals("021", CourtSessionNotification.number(21))
        assertEquals("1204", CourtSessionNotification.number(1204))
        assertNull(CourtSessionNotification.detail(PleadActivityCopy.state(CourtSessionPhase.ended, null).copy(detail = "Court adjourned")))
        val s = PleadActivityCopy.state(CourtSessionPhase.deliberating, clock.plusSeconds(60))
        assertEquals("Ruling in", CourtSessionNotification.countdown(s, clock)?.first)
        assertNull(CourtSessionNotification.countdown(s, clock.plusSeconds(61)))
    }
}
