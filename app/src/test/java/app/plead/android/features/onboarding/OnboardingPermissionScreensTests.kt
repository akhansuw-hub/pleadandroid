// Port of ArgueWinTests/OnboardingPermissionScreensTests.swift: onboarding redesign 2.0, part 4 (CONTRACTS-v2
// amendment ak): Court Notices, Widgets & Live Activities, Privacy; amendment at: Privacy & tracking asks for ATT.
// Android (amendment az): no ATT, so the Privacy & tracking step is not in the flow (the screen and its copy stay);
// the widgets screen talks about "Live Updates" (the ongoing court-session notification) instead of Live Activities.
// The iOS-only checks (Info.plist usage string, privacy manifests) have no Android subject and are not ported;
// the source guard reads the Kotlin files.
package app.plead.android.features.onboarding

import app.plead.android.services.NotificationPermissionService
import app.plead.android.services.NotificationStatus
import app.plead.android.services.PermissionCTA
import app.plead.android.services.TrackingPermissionService
import app.plead.android.services.TrackingStatus
import app.plead.android.services.UserDefaults
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias Moment = SummonsNotificationPreview.Moment

class OnboardingPermissionScreensTests {
    // MARK: Copy

    @Test fun courtNoticesCopy() {
        assertEquals("Don't miss your summons.", CourtNoticesCopy.headline)
        assertEquals("NO NOTIFICATION SPAM", CourtNoticesCopy.spamTitle.uppercase())
        assertEquals(listOf("Summoned", "Verdict ready", "Judgement due"), SummonsNotificationPreview.Moment.entries.map { it.displayName })
    }

    /**
     * The previews use the real generic (lock-screen-safe) push lines from `_shared/copy.ts` PUSH_COPY:
     * summons, verdict_ready, judgement_reminder. No case or partner names, no emojis.
     */
    @Test fun noticePreviewsUseTheRealGenericPushCopy() {
        assertEquals("You've been summoned", Moment.summoned.title)
        assertEquals("Your partner has filed a case. The court awaits your plea.", Moment.summoned.body)
        assertEquals("All rise", Moment.verdictReady.title)
        assertEquals("The judge has ruled. Your verdict is ready.", Moment.verdictReady.body)
        assertEquals("Judgement due soon", Moment.judgementDue.title)
        assertEquals("The court's judgement is still outstanding.", Moment.judgementDue.body)
        for (x in Moment.entries) assertFalse("$x has an emoji", hasEmoji(x.title + x.body))
    }

    @Test fun widgetsCopy() {
        assertEquals("Court follows you.", WidgetsCopy.headline)
        // iOS: ["Lock Screen", "Home Screen", "Live Activities"].
        assertEquals(listOf("Lock Screen", "Home Screen", "Live Updates"), WidgetsCopy.pills)
        // Availability comes from the system state; no permission flow is implied.
        assertTrue(WidgetsCopy.availability(liveActivitiesEnabled = true).contains(WidgetsCopy.liveActivitiesOn))
        assertTrue(WidgetsCopy.availability(liveActivitiesEnabled = false).contains(WidgetsCopy.liveActivitiesOff))
        for (text in listOf(WidgetsCopy.availability(true), WidgetsCopy.availability(false))) {
            assertFalse(text.lowercase().contains("permission"))
            assertFalse(text.lowercase().contains("allow"))
        }
    }

    @Test fun privacyCopy() {
        assertEquals("Your arguments stay between you.", PrivacyCopy.headline)
        assertEquals("Continue", PrivacyCopy.cta)
        val items = PrivacyCopy.assurances
        assertEquals(listOf("PRIVATE CASES", "YOUR EVIDENCE", "AD MEASUREMENT"), items.map { it.title.uppercase() })
        // Only what the product does: no encryption, retention, AI-training or sale claims.
        val all = (listOf(PrivacyCopy.subtitle, PrivacyCopy.promptNote) + items.map { it.body }).joinToString(" ").lowercase()
        for (banned in listOf("encrypt", "retain", "retention", "delete", "train", "sell", "sold", "never shared", "end-to-end")) {
            assertFalse("Privacy copy claims '$banned'", all.contains(banned))
        }
        // Amendment at: honest about tracking, and never claims there is none.
        assertEquals(
            "If you allow tracking, it helps us understand which ads bring people to Plead. Your cases and evidence are never used for advertising.",
            items[2].body,
        )
        assertFalse(all.contains("doesn't track"))
        assertFalse(all.contains("does not track"))
    }

    /** No incentive, no pressure, nothing that mimics the system alert's buttons. */
    @Test fun trackingCopyNeverIncentivisesOrMimicsTheAlert() {
        val texts = (listOf(PrivacyCopy.cta, PrivacyCopy.promptNote, PrivacyCopy.trackingAllowed, PrivacyCopy.trackingOff) + PrivacyCopy.assurances.map { it.body })
            .map { it.lowercase() }
        for (t in texts) {
            for (banned in listOf("ask app not to track", "free", "reward", "unlock", "discount", "bonus", "required", "must allow", "please allow")) {
                assertFalse("'$t' contains '$banned'", t.contains(banned))
            }
        }
        assertNotEquals("allow", PrivacyCopy.cta.lowercase())
        assertEquals("Apple will ask next. Plead works the same whichever you choose.", PrivacyCopy.promptNote)
    }

    /** Plead, never ArgueWin or "Premium", in anything these screens show. */
    @Test fun brandRules() {
        val texts = listOf(
            CourtNoticesCopy.eyebrow, CourtNoticesCopy.headline, CourtNoticesCopy.subtitle, CourtNoticesCopy.spamTitle,
            CourtNoticesCopy.spamBody, CourtNoticesCopy.enable, WidgetsCopy.eyebrow, WidgetsCopy.headline,
            WidgetsCopy.subtitle, WidgetsCopy.availability(true), WidgetsCopy.availability(false), PrivacyCopy.eyebrow, PrivacyCopy.headline,
            PrivacyCopy.subtitle, PrivacyCopy.promptNote, PrivacyCopy.trackingAllowed, PrivacyCopy.trackingOff,
        ) + PrivacyCopy.assurances.flatMap { listOf(it.title, it.body) } +
            SummonsNotificationPreview.Moment.entries.flatMap { listOf(it.title, it.body) } + WidgetsCopy.pills
        for (t in texts) {
            assertFalse(t, t.lowercase().contains("arguewin"))
            assertFalse(t, t.lowercase().contains("premium"))
            assertFalse(t, hasEmoji(t))
        }
    }

    // MARK: Privacy (iOS: always present)

    /** iOS: Privacy is always a step, right after Notifications. Android (amendment az): never a step. */
    @Test fun privacyIsAlwaysAStep() {
        val steps = OnboardingStep.activeSteps()
        assertEquals(11, steps.size)
        assertFalse(steps.contains(OnboardingStep.tracking))
        assertEquals(9, OnboardingStep.tracking.rawValue)
        assertEquals(
            listOf(OnboardingStep.partner, OnboardingStep.notifications, OnboardingStep.widgets, OnboardingStep.ready),
            steps.takeLast(4),
        )
        // The iOS order is kept in `displayOrder` (shared raw values).
        assertEquals(OnboardingStep.notifications, OnboardingStep.tracking.previous(OnboardingStep.displayOrder))
        assertEquals(OnboardingStep.widgets, OnboardingStep.tracking.next(OnboardingStep.displayOrder))
    }

    // MARK: ATT (amendment at)

    /** The ATT prompt only from the CTA and only while undecided (Android is never undecided). */
    @Test fun trackingPromptOnlyFromTheCTAWhileUndecided() {
        val r = OnboardingPermissionRules
        for (status in TrackingStatus.entries) assertFalse(r.requestsTracking(OnboardingPermissionRules.Trigger.appear, status))
        assertTrue(r.requestsTracking(OnboardingPermissionRules.Trigger.primaryTap, TrackingStatus.notDetermined))
        for (status in listOf(TrackingStatus.denied, TrackingStatus.authorized, TrackingStatus.restricted)) {
            assertFalse(r.requestsTracking(OnboardingPermissionRules.Trigger.primaryTap, status))
        }
        assertEquals(PermissionCTA.requestNative, TrackingPermissionService.cta(TrackingStatus.notDetermined))
        for (s in listOf(TrackingStatus.denied, TrackingStatus.authorized, TrackingStatus.restricted)) {
            assertEquals(PermissionCTA.continueOnly, TrackingPermissionService.cta(s))
        }
        // Android: the service is always decided, so the Privacy screen never asks.
        assertFalse(r.requestsTracking(OnboardingPermissionRules.Trigger.primaryTap, TrackingPermissionService(UserDefaults.inMemory()).status))
    }

    /** iOS: CONTINUE on Privacy lands on Widgets. Android: Notifications leads straight to Widgets. */
    @Test fun privacyContinueAdvancesToWidgets() {
        val m = OnboardingModel(UserDefaults.inMemory())
        m.go(OnboardingStep.notifications)
        m.advance()
        assertEquals(OnboardingStep.widgets, m.step)
        m.go(OnboardingStep.tracking)
        assertEquals(OnboardingStep.widgets, m.step)
    }

    /** `att_status` persists like the notification status; on Android it is always `authorized` (amendment az). */
    @Test fun trackingStatusPersists() {
        val d = UserDefaults.inMemory()
        assertEquals("att_status", TrackingPermissionService.storageKey)
        assertEquals(TrackingStatus.authorized, TrackingPermissionService(d).status)
        assertEquals("authorized", d.string("att_status"))
        d.set("denied", "att_status")
        assertEquals(TrackingStatus.authorized, TrackingPermissionService(d).status)
    }

    // MARK: Notification prompt never on appear

    @Test fun notificationPromptOnlyFromTheCTA() {
        val r = OnboardingPermissionRules
        for (status in NotificationStatus.entries) assertFalse(r.requestsNotifications(OnboardingPermissionRules.Trigger.appear, status))
        assertTrue(r.requestsNotifications(OnboardingPermissionRules.Trigger.primaryTap, NotificationStatus.notDetermined))
        for (status in NotificationStatus.entries.filter { it != NotificationStatus.notDetermined }) {
            assertFalse(r.requestsNotifications(OnboardingPermissionRules.Trigger.primaryTap, status))
        }
        assertEquals(PermissionCTA.requestNative, NotificationPermissionService.cta(NotificationStatus.notDetermined))
    }

    /**
     * Source guard: in PermissionViews.kt the only `.request(` calls sit inside the CTA handlers (`allow` /
     * `proceed`), never in the composables (so never on appear / in a LaunchedEffect).
     */
    @Test fun permissionRequestsLiveOnlyInCTAHandlers() {
        val dir = sourceDir()
        val src = File(dir, "PermissionViews.kt").readText()
        val handlers = src.indexOf("private fun allow(")
        assertTrue("allow() handler not found", handlers > 0)
        assertTrue(src.indexOf("private fun proceed(") > handlers)
        // Code only: comments may name the service call.
        val before = src.substring(0, handlers).lines().filterNot { it.trim().let { l -> l.startsWith("//") || l.startsWith("*") || l.startsWith("/**") } }.joinToString("\n")
        assertFalse("a permission is requested outside the CTA handlers", before.contains(".request("))
        val after = src.substring(handlers)
        assertFalse("a handler runs in a LaunchedEffect", after.contains("LaunchedEffect"))
        // The widgets screen never asks for anything.
        val widgets = File(dir, "WidgetSetupEducationView.kt").readText()
        assertFalse(widgets.contains("requestPermission"))
        assertFalse(widgets.contains(".request("))
    }

    private fun sourceDir(): File {
        val rel = "src/main/java/app/plead/android/features/onboarding"
        return listOf(File(rel), File("app/$rel"), File("android/app/$rel")).first { it.isDirectory }
    }

    private fun hasEmoji(s: String): Boolean = s.codePoints().anyMatch { it >= 0x1F300 || it in 0x2600..0x27BF || it == 0xFE0F }
}
