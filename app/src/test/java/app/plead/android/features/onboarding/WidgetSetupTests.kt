// Port of ArgueWinTests/WidgetSetupTests.swift: screen 8 · Widgets & Live Activities (CONTRACTS-v2 amendment r).
// `WidgetSetupStepTests` and `WidgetSetupModelTests`; `WidgetSetupServiceTests` lives in services/ (wave 2a).
// Android (amendment az): no Privacy & tracking step, so Widgets follows Notifications and the flow has 11 steps;
// the iOS expectations are noted where they differ.
package app.plead.android.features.onboarding

import app.plead.android.models.Profile
import app.plead.android.services.UserDefaults
import app.plead.android.services.WidgetFamily
import app.plead.android.services.WidgetSetupService
import app.plead.android.services.uuidString
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class EventLog {
    val events = mutableListOf<Pair<String, Map<String, String>>>()
    val names: List<String> get() = events.map { it.first }
    fun props(name: String): List<Map<String, String>> = events.filter { it.first == name }.map { it.second }
}

private data class Signed(val model: OnboardingModel, val log: EventLog, val uid: UUID)

private fun signedInModel(
    d: UserDefaults,
    uid: UUID = UUID.randomUUID(),
    service: WidgetSetupService = WidgetSetupService.fixed(families = emptyList(), activitiesEnabled = true),
): Signed {
    val m = OnboardingModel(d)
    val log = EventLog()
    m.analyticsSink = { e, p -> log.events += e to p }
    m.widgetSetup = service
    m.userChanged(uid, Profile(id = uid, displayName = "Arif"))
    return Signed(m, log, uid)
}

class WidgetSetupStepTests {
    /** iOS (amendment at): Tracking → Widgets → Ready, "11 OF 12". Android: Notifications → Widgets → Ready, "10 OF 11". */
    @Test fun widgetsSitsBetweenTrackingAndReady() {
        val all = OnboardingStep.activeSteps()
        assertEquals(12, OnboardingStep.allCases.size)
        assertEquals(8, OnboardingStep.widgets.rawValue)
        assertEquals(OnboardingStep.widgets, OnboardingStep.notifications.next(all))
        assertEquals(OnboardingStep.notifications, OnboardingStep.widgets.previous(all))
        assertEquals(OnboardingStep.ready, OnboardingStep.widgets.next(all))
        assertEquals(10, OnboardingStep.ready.rawValue)
        assertEquals("10 OF 11", OnboardingStep.widgets.positionLabel(all))
        assertEquals("widgets", OnboardingStep.widgets.screenId)
        assertTrue(OnboardingStep.widgets.showsChrome)
        assertFalse(OnboardingStep.widgets.isPreAuth)
        assertTrue(OnboardingFlow.canAdvance(OnboardingStep.widgets, ""))
    }

    @Test fun resumeClampsToTheNewCount() {
        assertEquals(OnboardingStep.widgets, OnboardingFlow.resumeStep(user = 8, device = null, signedIn = true, hasProfile = true))
        assertEquals(OnboardingStep.ready, OnboardingFlow.resumeStep(user = 10, device = null, signedIn = true, hasProfile = true))
        assertEquals(OnboardingStep.ready, OnboardingFlow.resumeStep(user = 99, device = null, signedIn = true, hasProfile = true))
        assertEquals(OnboardingStep.identity, OnboardingFlow.resumeStep(user = 8, device = 8, signedIn = false, hasProfile = false))
    }
}

class WidgetSetupModelTests {
    @Test fun resumesOnTheWidgetsStepAfterRelaunch() {
        val d = UserDefaults.inMemory()
        val (m, _, uid) = signedInModel(d)
        m.go(OnboardingStep.notifications)
        m.advance()
        // iOS: .tracking first, then .widgets.
        assertEquals(OnboardingStep.widgets, m.step)
        val relaunched = OnboardingModel(d)
        relaunched.userChanged(uid, Profile(id = uid, displayName = "Arif"))
        assertEquals(OnboardingStep.widgets, relaunched.step)
        relaunched.advance()
        assertEquals(OnboardingStep.ready, relaunched.step)
        relaunched.back()
        assertEquals(OnboardingStep.widgets, relaunched.step)
    }

    /**
     * iOS (amendment at): saved on Widgets under the old order, a relaunch lands on the tracking step first. Android
     * has no tracking step: Widgets resumes directly, and remains remembered.
     */
    @Test fun oldOrderWidgetsResumeGoesThroughTrackingFirst() {
        val d = UserDefaults.inMemory()
        val uid = UUID.randomUUID()
        d.set(OnboardingStep.widgets.rawValue, OnboardingModel.Key.step.key(uid.uuidString))
        val relaunched = OnboardingModel(d)
        relaunched.userChanged(uid, Profile(id = uid, displayName = "Arif"))
        assertEquals(OnboardingStep.widgets, relaunched.step)
        val again = OnboardingModel(d)
        again.userChanged(uid, Profile(id = uid, displayName = "Arif"))
        assertEquals(OnboardingStep.widgets, again.step)
    }

    @Test fun notNowSetsTheSkipFlagAdvancesAndPersists() {
        val d = UserDefaults.inMemory()
        val (m, log, uid) = signedInModel(d)
        m.go(OnboardingStep.widgets)
        m.skipWidgetEducation()
        assertTrue(m.widgetEducationSkipped)
        assertEquals(OnboardingStep.ready, m.step)
        assertTrue(log.names.contains(OnboardingEvent.widgetEducationSkipped))
        // Skipping isn't a "continue".
        assertFalse(log.props(OnboardingEvent.continueTapped).contains(mapOf("screen_id" to "widgets")))
        val relaunched = OnboardingModel(d)
        relaunched.userChanged(uid, Profile(id = uid, displayName = "Arif"))
        assertTrue(relaunched.widgetEducationSkipped)
        // Per user: another account on the device starts fresh.
        val other = OnboardingModel(d)
        val otherId = UUID.randomUUID()
        other.userChanged(otherId, Profile(id = otherId, displayName = "Sam"))
        assertFalse(other.widgetEducationSkipped)
    }

    @Test fun viewingMarksSeenAndTracksOncePerSession() = runTest {
        val (m, log, _) = signedInModel(UserDefaults.inMemory())
        m.go(OnboardingStep.widgets)
        m.widgetEducationAppeared()
        m.widgetEducationAppeared()
        assertTrue(m.hasSeenWidgetEducation)
        assertEquals(1, log.names.count { it == OnboardingEvent.widgetEducationViewed })
        assertFalse(m.widgetSetupComplete)
    }

    @Test fun detectionShowsTheSuccessStateAndTracksTheFamily() = runTest {
        val d = UserDefaults.inMemory()
        val (m, log, uid) = signedInModel(
            d, service = WidgetSetupService.fixed(families = listOf(WidgetFamily.accessoryRectangular, WidgetFamily.systemSmall), activitiesEnabled = true),
        )
        m.go(OnboardingStep.widgets)
        m.refreshWidgetSetup()
        assertTrue(m.widgetSetupComplete)
        assertTrue(m.hasConfiguredPleadWidget)
        assertEquals(listOf(WidgetFamily.systemSmall, WidgetFamily.accessoryRectangular), m.detectedWidgetFamilies)
        assertEquals(listOf(mapOf("family" to "system_small")), log.props(OnboardingEvent.widgetDetectedAfterSetup))
        // Becoming active again doesn't re-fire.
        m.refreshWidgetSetup()
        assertEquals(1, log.props(OnboardingEvent.widgetDetectedAfterSetup).size)
        val relaunched = OnboardingModel(d)
        relaunched.userChanged(uid, Profile(id = uid, displayName = "Arif"))
        assertTrue(relaunched.widgetSetupComplete)
        assertTrue(relaunched.hasConfiguredPleadWidget)
    }

    @Test fun noWidgetMeansNoSuccessState() = runTest {
        val (m, log, _) = signedInModel(UserDefaults.inMemory())
        m.refreshWidgetSetup()
        assertFalse(m.widgetSetupComplete)
        assertFalse(m.hasConfiguredPleadWidget)
        assertTrue(log.props(OnboardingEvent.widgetDetectedAfterSetup).isEmpty())
    }

    @Test fun disabledLiveActivitiesAreDetectedOnce() = runTest {
        val (m, log, _) = signedInModel(UserDefaults.inMemory(), service = WidgetSetupService.fixed(families = emptyList(), activitiesEnabled = false))
        assertTrue(m.liveActivitiesEnabled) // assumed on until checked
        m.refreshWidgetSetup()
        m.refreshWidgetSetup()
        assertFalse(m.liveActivitiesEnabled)
        assertEquals(1, log.names.count { it == OnboardingEvent.liveActivityDisabledDetected })
    }

    @Test fun enabledLiveActivitiesFireNothing() = runTest {
        val (m, log, _) = signedInModel(UserDefaults.inMemory())
        m.refreshWidgetSetup()
        assertTrue(m.liveActivitiesEnabled)
        assertFalse(log.names.contains(OnboardingEvent.liveActivityDisabledDetected))
    }

    @Test fun instructionsSurfaceIsTracked() {
        val (m, log, _) = signedInModel(UserDefaults.inMemory())
        m.widgetInstructionsOpened(WidgetSetupSurface.home)
        m.widgetInstructionsOpened(WidgetSetupSurface.lock)
        assertEquals(listOf(mapOf("surface" to "home"), mapOf("surface" to "lock")), log.props(OnboardingEvent.widgetInstructionsOpened))
    }

    /** Every active step (iOS: all twelve; Android: eleven) reports viewed and continue. */
    @Test fun everyStepReportsViewedAndContinue() {
        val (m, log, _) = signedInModel(UserDefaults.inMemory())
        m.go(OnboardingStep.welcome)
        val steps = OnboardingStep.activeSteps()
        for (step in steps) {
            m.go(step)
            m.screenAppeared(step)
            m.advance()
        }
        val viewed = log.props(OnboardingEvent.screenViewed).mapNotNull { it["screen_id"] }
        val continued = log.props(OnboardingEvent.continueTapped).mapNotNull { it["screen_id"] }
        assertEquals(steps.map { it.screenId }.toSet(), viewed.toSet())
        // Ready has no "next": its CTA finishes onboarding instead.
        assertEquals(steps.dropLast(1).map { it.screenId }, continued)
    }

    @Test fun analyticsNamesMatchTheBrief() {
        assertEquals("onboarding_screen_viewed", OnboardingEvent.screenViewed)
        assertEquals("onboarding_continue_tapped", OnboardingEvent.continueTapped)
        assertEquals("widget_education_viewed", OnboardingEvent.widgetEducationViewed)
        assertEquals("widget_setup_instructions_opened", OnboardingEvent.widgetInstructionsOpened)
        assertEquals("widget_detected_after_setup", OnboardingEvent.widgetDetectedAfterSetup)
        assertEquals("widget_education_skipped", OnboardingEvent.widgetEducationSkipped)
        assertEquals("live_activity_disabled_detected", OnboardingEvent.liveActivityDisabledDetected)
        assertEquals(listOf("home", "lock"), WidgetSetupSurface.entries.map { it.rawValue })
    }
}
