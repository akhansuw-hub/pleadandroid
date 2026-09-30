// Port of ArgueWinTests/OnboardingTests.swift: `OnboardingFlowTests`, `OnboardingModelTests`, `OnboardingMockTrialTests`.
// `PermissionRoutingTests` (services/LiveActivityTests.kt), `OnboardingGateTests` (app/AppGateTests.kt),
// `OnboardingCompletionTests` (app/AppModelTests.kt) and `welcomeLogoMatchesTheEndCardPlacement`
// (designsystem/PleadWordmarkTests.kt) were ported by wave 2a/2b.
// Android (amendment az, no ATT): the Privacy & tracking step is not active, so the flow has eleven screens. Tests
// whose iOS expectations depend on it keep their names and assert the Android flow (the iOS values are noted).
package app.plead.android.features.onboarding

import app.plead.android.models.Avatar
import app.plead.android.models.Profile
import app.plead.android.services.PreviewData
import app.plead.android.services.UserDefaults
import app.plead.android.services.uuidString
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private typealias S = OnboardingStep

/** A throwaway defaults suite per test, so persistence tests never touch the app's real state. */
private fun freshDefaults(): UserDefaults = UserDefaults.inMemory()

private fun profile(id: UUID, name: String, avatar: Avatar = Avatar.default, partnerNameTemp: String? = null) =
    Profile(id = id, displayName = name, avatar = avatar, partnerNameTemp = partnerNameTemp)

/** Brief §2 order, §11 resume, amendment g skip rules. */
class OnboardingFlowTests {
    /**
     * The enum keeps all twelve screens (raw values are persisted); `.tracking` is the Privacy screen (amendment ak);
     * the Mock Trial Demo is shown second (amendment y), the summons explainer third (amendment ai).
     */
    @Test fun twelveScreensInTheBriefsOrder() {
        assertEquals(12, S.allCases.size)
        assertEquals(S.displayOrder, S.allCases)
        assertEquals(
            listOf("welcome", "mock_trial", "summons_intro", "how_it_works", "ai_court", "examples", "identity", "partner", "notifications", "tracking", "widgets", "ready"),
            S.allCases.map { it.screenId },
        )
        assertEquals(9, S.tracking.rawValue)
        assertEquals(10, S.ready.rawValue)
    }

    /** Amendment y + t + ai: persisted raw values never shift. */
    @Test fun rawValuesAreStable() {
        assertEquals(11, S.mockTrial.rawValue)
        assertEquals(12, S.summonsIntro.rawValue)
        assertEquals((1..12).toList(), S.allCases.map { it.rawValue }.sorted())
        val original = listOf(S.welcome, S.howItWorks, S.aiCourt, S.examples, S.identity, S.partner, S.notifications, S.widgets, S.tracking, S.ready)
        assertEquals((1..10).toList(), original.map { it.rawValue })
        assertEquals(S.mockTrial, S.fromRaw(11))
        assertEquals(S.summonsIntro, S.fromRaw(12))
        assertEquals(S.welcome, S.persisted(0))
        assertEquals(S.ready, S.persisted(42))
    }

    /** Ordering follows display order, not raw value. */
    @Test fun orderingFollowsDisplayOrder() {
        assertTrue(S.welcome before S.mockTrial)
        assertTrue(S.mockTrial before S.summonsIntro)
        assertTrue(S.summonsIntro before S.howItWorks)
        assertTrue(S.mockTrial before S.ready)
        assertEquals(S.displayOrder, S.allCases.sortedBy { it.displayIndex })
        assertEquals(S.mockTrial, S.welcome.next())
        assertEquals(S.summonsIntro, S.mockTrial.next())
        assertEquals(S.welcome, S.mockTrial.previous())
        assertEquals(S.mockTrial, S.summonsIntro.previous())
        assertEquals(S.summonsIntro, S.howItWorks.previous())
        assertEquals("mock_trial", S.mockTrial.screenId)
        assertTrue(S.mockTrial.showsChrome)
        assertTrue(S.mockTrial.isPreAuth)
        assertTrue(OnboardingFlow.canAdvance(S.mockTrial, ""))
    }

    /**
     * iOS (amendments ak, at): the Privacy screen is always shown, twelve active screens. Android (amendment az): no
     * ATT, so Privacy & tracking is skipped: eleven active screens, Notifications → Widgets → Court Is Ready.
     */
    @Test fun privacyIsAlwaysShown() {
        assertTrue(S.skipsTracking)
        assertEquals(11, S.activeSteps().size)
        assertFalse(S.activeSteps().contains(S.tracking))
        assertEquals(S.widgets, S.notifications.next())
        assertEquals(S.widgets, S.tracking.next())
        assertEquals(S.ready, S.widgets.next())
        assertEquals(S.widgets, S.ready.previous())
        assertEquals("1 OF 11", S.welcome.positionLabel())
        assertEquals("2 OF 11", S.mockTrial.positionLabel())
        assertEquals("3 OF 11", S.summonsIntro.positionLabel())
        assertEquals("4 OF 11", S.howItWorks.positionLabel())
        assertEquals("10 OF 11", S.tracking.positionLabel()) // a stray `.tracking` reports the next active screen
        assertEquals("10 OF 11", S.widgets.positionLabel())
        assertEquals("11 OF 11", S.ready.positionLabel())
    }

    @Test fun activeStepsInTheBriefsOrder() {
        val steps = S.activeSteps()
        // Android: Notifications → Widgets → Court Is Ready (iOS puts Privacy & tracking between the first two).
        val expected = listOf(S.welcome, S.mockTrial, S.summonsIntro, S.howItWorks, S.aiCourt, S.examples, S.identity, S.partner, S.notifications, S.widgets, S.ready)
        assertEquals(expected, steps)
        assertEquals(11, steps.size)
        assertEquals(S.mockTrial, S.welcome.next(steps))
        assertEquals(S.summonsIntro, S.mockTrial.next(steps))
        assertEquals(S.howItWorks, S.summonsIntro.next(steps))
        assertEquals(S.summonsIntro, S.howItWorks.previous(steps))
        assertEquals(S.widgets, S.notifications.next(steps))
        assertEquals(S.notifications, S.widgets.previous(steps))
        assertEquals(S.ready, S.widgets.next(steps))
        assertEquals(S.widgets, S.ready.previous(steps))
        assertNull(S.welcome.previous(steps))
        assertNull(S.ready.next(steps))
        assertEquals(S.partner, S.identity.next(steps))
        assertEquals(1.0, S.ready.progress(steps), 0.0)
        // The iOS order is still what `displayOrder` holds (shared with the persisted raw values).
        assertEquals(S.tracking, S.notifications.next(S.displayOrder))
    }

    @Test fun welcomeFooterCountsTheActiveSteps() {
        val steps = S.activeSteps()
        val count = steps.size
        assertEquals("1 OF $count", S.welcome.positionLabel(steps))
        assertEquals("1 OF 11", S.welcome.positionLabel(steps))
        assertEquals("2 OF 11", S.mockTrial.positionLabel(steps))
        assertEquals("3 OF 11", S.summonsIntro.positionLabel(steps))
        assertEquals("4 OF $count", S.howItWorks.positionLabel(steps))
        assertEquals("10 OF 11", S.widgets.positionLabel(steps))
        assertEquals(8, OnboardingProgressRail.activeIndex(S.widgets, steps))
        assertEquals(0, OnboardingProgressRail.activeIndex(S.mockTrial, steps))
        assertEquals(1, OnboardingProgressRail.activeIndex(S.summonsIntro, steps))
        assertEquals(2, OnboardingProgressRail.activeIndex(S.howItWorks, steps))
        assertEquals("$count OF $count", S.ready.positionLabel(steps))
        assertEquals(10, OnboardingProgressRail.segmentCount(steps))
        assertEquals(count - 2, OnboardingProgressRail.activeIndex(S.ready, steps))
    }

    @Test fun welcomeHasNoChromeLaterScreensDo() {
        assertFalse(S.welcome.showsChrome)
        for (step in S.allCases.drop(1)) assertTrue(step.showsChrome)
    }

    @Test fun backIsOfferedEverywhereButWelcome() {
        val steps = S.activeSteps()
        assertFalse(OnboardingFlow.canGoBack(S.welcome, steps))
        for (step in steps.drop(1)) assertTrue(OnboardingFlow.canGoBack(step, steps))
    }

    /** Amendment p: no login on screen 5. A name is all THAT'S ME needs (it creates the session itself). */
    @Test fun identityNeedsOnlyAName() {
        assertTrue(OnboardingFlow.canAdvance(S.identity, "Arif"))
        assertFalse(OnboardingFlow.canAdvance(S.identity, "   "))
        assertTrue(OnboardingFlow.canAdvance(S.howItWorks, ""))
        assertFalse(OnboardingFlow.canAdvance(S.ready, "Arif"))
    }

    @Test fun resumeStepRules() {
        val r = OnboardingFlow
        // Fresh install.
        assertEquals(S.welcome, r.resumeStep(user = null, device = null, signedIn = false, hasProfile = false))
        // Killed on screen 3 before signing in.
        assertEquals(S.aiCourt, r.resumeStep(user = null, device = 3, signedIn = false, hasProfile = false))
        // Signed out can never be past identity, whatever was saved.
        assertEquals(S.identity, r.resumeStep(user = null, device = 8, signedIn = false, hasProfile = false))
        // Signed in without a saved profile: back to identity.
        assertEquals(S.identity, r.resumeStep(user = 7, device = 5, signedIn = true, hasProfile = false))
        // Signed in with a profile: the user's own step.
        assertEquals(S.notifications, r.resumeStep(user = 7, device = 5, signedIn = true, hasProfile = true))
        // No per-user step yet: the device's.
        assertEquals(S.identity, r.resumeStep(user = null, device = 5, signedIn = true, hasProfile = true))
        // Garbage is clamped.
        assertEquals(S.ready, r.resumeStep(user = 42, device = null, signedIn = true, hasProfile = true))
        assertEquals(S.welcome, r.resumeStep(user = -1, device = null, signedIn = true, hasProfile = true))
        // Amendment y: a saved Mock Trial (raw 11) resumes on the demo, signed in or not (it precedes identity).
        assertEquals(S.mockTrial, r.resumeStep(user = null, device = 11, signedIn = false, hasProfile = false))
        assertEquals(S.mockTrial, r.resumeStep(user = 11, device = null, signedIn = true, hasProfile = true))
        assertEquals(S.mockTrial, r.resumeStep(user = 11, device = null, signedIn = true, hasProfile = false))
    }

    /** iOS: a saved Tracking step (9) resumes on the Privacy screen. Android: on the next active screen, Widgets. */
    @Test fun resumeOntoThePrivacyStep() {
        val steps = S.activeSteps()
        val r = OnboardingFlow
        assertEquals(S.widgets, r.resumeStep(user = 9, device = null, signedIn = true, hasProfile = true, steps = steps))
        assertEquals(S.identity, r.resumeStep(user = 9, device = 9, signedIn = false, hasProfile = false, steps = steps))
        assertEquals(S.widgets, r.resumeStep(user = 8, device = null, signedIn = true, hasProfile = true, steps = steps))
        assertEquals(S.ready, r.resumeStep(user = 42, device = null, signedIn = true, hasProfile = true, steps = steps))
        // With the iOS step list the rule is the iOS one.
        assertEquals(S.tracking, r.resumeStep(user = 9, device = null, signedIn = true, hasProfile = true, steps = S.displayOrder))
    }

    /**
     * Amendment at: under the old order a step after the moved tracking screen resumes on it until it has been
     * passed once. Android has no tracking step, so nothing is held back; the iOS rule still holds for its step list.
     */
    @Test fun oldOrderResumeNeverSkipsTracking() {
        fun resume(raw: Int, seen: Boolean, steps: List<OnboardingStep> = S.activeSteps()) =
            OnboardingFlow.resumeStep(user = raw, device = null, signedIn = true, hasProfile = true, trackingSeen = seen, steps = steps)
        assertEquals(S.widgets, resume(S.widgets.rawValue, seen = false))
        assertEquals(S.ready, resume(S.ready.rawValue, seen = false))
        assertEquals(S.notifications, resume(S.notifications.rawValue, seen = false))
        assertEquals(S.partner, resume(S.partner.rawValue, seen = false))
        // The iOS list (Privacy active).
        val ios = S.displayOrder
        assertEquals(S.tracking, resume(S.widgets.rawValue, seen = false, steps = ios))
        assertEquals(S.tracking, resume(S.ready.rawValue, seen = false, steps = ios))
        assertEquals(S.tracking, resume(42, seen = false, steps = ios))
        assertEquals(S.tracking, resume(S.tracking.rawValue, seen = false, steps = ios))
        assertEquals(S.notifications, resume(S.notifications.rawValue, seen = false, steps = ios))
        assertEquals(S.widgets, resume(S.widgets.rawValue, seen = true, steps = ios))
        assertEquals(S.ready, resume(S.ready.rawValue, seen = true, steps = ios))
        // Signed out / no profile still clamps to identity first.
        assertEquals(S.identity, OnboardingFlow.resumeStep(user = 8, device = 8, signedIn = false, hasProfile = false, trackingSeen = false))
    }

    @Test fun existingAccountsSkip() {
        assertTrue(OnboardingFlow.isComplete(serverCompletedAt = Instant.now(), localFlag = false, hasCases = false))
        assertTrue(OnboardingFlow.isComplete(serverCompletedAt = null, localFlag = true, hasCases = false))
        assertTrue(OnboardingFlow.isComplete(serverCompletedAt = null, localFlag = false, hasCases = true))
        assertFalse(OnboardingFlow.isComplete(serverCompletedAt = null, localFlag = false, hasCases = false))
    }

    @Test fun docketPreview() {
        assertEquals("ARIF v. SOPHIE", OnboardingFlow.docketTitle(me = "Arif", partner = "Sophie"))
        assertEquals("ARIF v. YOUR PARTNER", OnboardingFlow.docketTitle(me = " arif ", partner = "  "))
        assertEquals("YOU v. SOPHIE", OnboardingFlow.docketTitle(me = "", partner = "Sophie"))
    }

    @Test fun eightDistinctPresetAvatars() {
        assertEquals(8, OnboardingAvatars.presets.size)
        assertEquals(8, OnboardingAvatars.presets.toSet().size)
        // Kept in sync by hand with the wave 2a copy used by AppModel's analytics.
        assertEquals(PreviewData.onboardingAvatarPresets, OnboardingAvatars.presets)
    }
}

/** The step machine with persistence: advance / back / kill-and-relaunch / sign-in hand-over. */
class OnboardingModelTests {
    @Test fun advanceBackAndResumeAfterRelaunch() {
        val d = freshDefaults()
        val m = OnboardingModel(d)
        assertEquals(S.welcome, m.step)
        m.advance()
        assertEquals(S.mockTrial, m.step)
        m.advance()
        assertEquals(S.summonsIntro, m.step)
        m.advance(); m.advance(); m.advance()
        assertEquals(S.examples, m.step)
        m.back()
        assertEquals(S.aiCourt, m.step)
        assertEquals(OnboardingModel.Direction.backward, m.direction)
        // "Kill" the app: a new model on the same defaults resumes on the last step.
        assertEquals(S.aiCourt, OnboardingModel(d).step)
    }

    /** iOS (amendment at): Notifications → Privacy & tracking → Widgets. Android: Notifications → Widgets → Ready. */
    @Test fun notificationsAdvancesToPrivacyThenWidgets() {
        val m = OnboardingModel(freshDefaults())
        assertEquals(11, m.activeSteps.size)
        m.go(S.notifications)
        assertEquals("9 OF 11", m.positionLabel)
        assertFalse(m.trackingSeen)
        m.advance()
        assertEquals(S.widgets, m.step)
        assertEquals("10 OF 11", m.positionLabel)
        // Passing the Privacy position leaves the state an iOS user who answered ATT has.
        assertTrue(m.trackingSeen)
        m.advance()
        assertEquals(S.ready, m.step)
        m.back()
        assertEquals(S.widgets, m.step)
        m.back()
        assertEquals(S.notifications, m.step)
        // Walking the whole flow visits exactly the active steps.
        m.go(S.welcome)
        val visited = mutableListOf(m.step)
        while (m.step != S.ready) {
            m.advance()
            visited += m.step
        }
        assertEquals(m.activeSteps, visited)
        assertEquals(1.0, m.progress, 0.0)
        // A jump to Privacy lands on the next active screen.
        m.go(S.tracking)
        assertEquals(S.widgets, m.step)
    }

    /**
     * Signed-out demo runs: THAT'S ME always signs in as `PreviewData.anonId`, so the reset must also wipe
     * that user's saved step, captured fields and "completed" flag, or the second run skips screens 6–10.
     */
    @Test fun demoResetClearsTheFixedDemoUser() {
        val d = freshDefaults()
        val demo = PreviewData.anonId
        val first = OnboardingModel(d)
        first.userChanged(demo, profile(demo, "Arif"))
        first.go(S.ready)
        first.togetherSince = Instant.ofEpochSecond(1_707_868_800)
        first.markCompleted()
        assertTrue(first.isCompleted(demo))

        // Next launch: signed out, the harness resets with the demo user's scope.
        val second = OnboardingModel(d)
        second.resetForDemo(listOf(demo))
        assertEquals(S.welcome, second.step)
        assertFalse(second.isCompleted(demo))
        for (key in OnboardingModel.Key.entries) assertFalse(d.has(key.key(demo.uuidString)))
        // THAT'S ME signs in as the same user again: onboarding continues instead of being skipped.
        second.go(S.identity)
        second.userChanged(demo, profile(demo, "Arif"))
        assertFalse(second.completedForUser)
        assertEquals(S.identity, second.step)
        assertNull(second.togetherSince)

        // Without the extra scope the old behaviour is kept (only device + current user are cleared).
        val other = UUID.randomUUID()
        val third = OnboardingModel(d)
        third.userChanged(other, profile(other, "B"))
        third.markCompleted()
        val fourth = OnboardingModel(d)
        fourth.resetForDemo(emptyList())
        assertTrue(fourth.isCompleted(other))
    }

    @Test fun backStopsAtWelcomeAndAdvanceStopsAtReady() {
        val m = OnboardingModel(freshDefaults())
        m.back()
        assertEquals(S.welcome, m.step)
        m.go(S.ready)
        m.advance()
        assertEquals(S.ready, m.step)
    }

    @Test fun signingInOnIdentityKeepsTheStepAndCarriesAnswers() {
        val m = OnboardingModel(freshDefaults())
        m.go(S.identity)
        m.displayName = "Arif"
        m.avatar = OnboardingAvatars.presets[3]
        m.userChanged(UUID.randomUUID(), null)
        assertEquals(S.identity, m.step)
        assertEquals("Arif", m.displayName)
        assertEquals(OnboardingAvatars.presets[3], m.avatar)
    }

    @Test fun signedInUserResumesTheirOwnStep() {
        val d = freshDefaults()
        val uid = UUID.randomUUID()
        val p = profile(uid, "Arif")
        val m = OnboardingModel(d)
        m.userChanged(uid, p)
        m.go(S.notifications)
        m.togetherSince = Instant.ofEpochSecond(1_707_868_800)
        // Relaunch: starts signed out (device scope), then the session restores.
        val relaunched = OnboardingModel(d)
        relaunched.userChanged(uid, p)
        assertEquals(S.notifications, relaunched.step)
        assertEquals(Instant.ofEpochSecond(1_707_868_800), relaunched.togetherSince)
    }

    @Test fun withoutAProfileTheUserIsSentBackToIdentity() {
        val d = freshDefaults()
        val uid = UUID.randomUUID()
        val m = OnboardingModel(d)
        m.userChanged(uid, profile(uid, "Arif"))
        m.go(S.widgets)
        val relaunched = OnboardingModel(d)
        relaunched.userChanged(uid, null)
        assertEquals(S.identity, relaunched.step)
    }

    @Test fun profilePrefillsIdentityWhenNothingWasTyped() {
        val uid = UUID.randomUUID()
        val avatar = Avatar(skin = 5, hair = 2, hairstyle = Avatar.Hairstyle.bun, top = 3, outfit = Avatar.Outfit.suit)
        val m = OnboardingModel(freshDefaults())
        // Amendment aw: a legacy `partner_name_temp` on the profile is ignored (the model has no partner name).
        m.userChanged(uid, profile(uid, "Sophie", avatar, partnerNameTemp = "Arif"))
        assertEquals("Sophie", m.displayName)
        assertEquals(avatar, m.avatar)
    }

    @Test fun completionIsPerUser() {
        val m = OnboardingModel(freshDefaults())
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        m.userChanged(a, profile(a, "A"))
        assertFalse(m.completedForUser)
        m.markCompleted()
        assertTrue(m.completedForUser)
        m.userChanged(b, profile(b, "B"))
        assertFalse(m.completedForUser)
        m.userChanged(a, profile(a, "A"))
        assertTrue(m.completedForUser)
    }

    /** Android: the avatar persists as JSON and `togetherSince` as seconds since 1970 under the same keys. */
    @Test fun fieldsPersistUnderTheSharedKeys() {
        val d = freshDefaults()
        val m = OnboardingModel(d)
        m.displayName = "Arif"
        m.avatar = OnboardingAvatars.presets[2]
        m.togetherSince = Instant.ofEpochSecond(1_707_868_800)
        assertEquals("Arif", d.string("onboarding.device.name"))
        assertEquals(1_707_868_800.0, d.doubleOrNull("onboarding.device.togetherSince")!!, 0.0)
        val relaunched = OnboardingModel(d)
        assertEquals(OnboardingAvatars.presets[2], relaunched.avatar)
        assertEquals("Arif", relaunched.displayName)
    }
}

/** Amendment y: the Mock Trial Demo step (persistence, exits, analytics through the model's sink). */
class OnboardingMockTrialTests {
    private class Log {
        val events = mutableListOf<Pair<String, Map<String, String>>>()
        fun props(name: String): List<Map<String, String>> = events.filter { it.first == name }.map { it.second }
    }

    private fun model(d: UserDefaults = freshDefaults()): Pair<OnboardingModel, Log> {
        val m = OnboardingModel(d)
        val log = Log()
        m.analyticsSink = { e, p -> log.events += e to p }
        return m to log
    }

    @Test fun positionLabelsWithTheDemo() {
        val (m, _) = model()
        val n = 11 // iOS: 12
        assertEquals("1 OF $n", m.positionLabel)
        m.advance()
        assertEquals(S.mockTrial, m.step)
        assertEquals("2 OF $n", m.positionLabel)
        m.completeMockTrial()
        assertEquals(S.summonsIntro, m.step)
        assertEquals("3 OF $n", m.positionLabel)
        m.advance()
        assertEquals(S.howItWorks, m.step)
        assertEquals("4 OF $n", m.positionLabel)
    }

    @Test fun welcomeLeadsToTheDemoAndBackReturnsToIt() {
        val (m, _) = model()
        m.advance()
        assertEquals(S.mockTrial, m.step)
        assertTrue(m.canGoBack)
        m.completeMockTrial()
        assertEquals(S.summonsIntro, m.step)
        m.back()
        assertEquals(S.mockTrial, m.step)
        assertEquals(OnboardingModel.Direction.backward, m.direction)
        m.back()
        assertEquals(S.welcome, m.step)
    }

    @Test fun continueAdvancesToTheSummonsExplainer() {
        val (m, log) = model()
        m.go(S.mockTrial)
        m.screenAppeared(S.mockTrial)
        m.mockTrialAppeared()
        for (beat in MockTrialBeat.entries.drop(1)) m.mockTrialAdvanced(beat)
        m.completeMockTrial()
        assertEquals(S.summonsIntro, m.step)
        assertEquals(OnboardingModel.Direction.forward, m.direction)
        assertEquals(1, log.props(OnboardingEvent.mockTrialViewed).size)
        // Amendment aj: every beat after the opening, by its analytics name.
        assertEquals(
            listOf(
                "plaintiff_opening", "plaintiff_evidence", "defendant_opening", "defendant_evidence", "cross_examination_1",
                "cross_examination_2", "cross_examination_3", "cross_examination_follow_up", "closings", "deliberation",
                "verdict", "judgement", "closed",
            ).map { mapOf("beat" to it) },
            log.props(OnboardingEvent.mockTrialAdvanced),
        )
        assertEquals(listOf(emptyMap<String, String>()), log.props(OnboardingEvent.mockTrialCompleted))
        assertTrue(log.props(OnboardingEvent.mockTrialSkipped).isEmpty())
        // The shared per-step events carry mock_trial too.
        assertTrue(log.props(OnboardingEvent.screenViewed).contains(mapOf("screen_id" to "mock_trial")))
        assertEquals(listOf(mapOf("screen_id" to "mock_trial")), log.props(OnboardingEvent.continueTapped))
    }

    @Test fun skipAdvancesToTheSummonsExplainerWithTheCurrentBeat() {
        val (m, log) = model()
        m.go(S.mockTrial)
        m.mockTrialAppeared()
        m.mockTrialAdvanced(MockTrialBeat.plaintiffOpening)
        m.mockTrialAdvanced(MockTrialBeat.plaintiffEvidence)
        m.mockTrialAdvanced(MockTrialBeat.defendantOpening)
        m.skipMockTrial()
        assertEquals(S.summonsIntro, m.step)
        assertEquals(listOf(mapOf("beat" to "defendant_opening")), log.props(OnboardingEvent.mockTrialSkipped))
        assertTrue(log.props(OnboardingEvent.mockTrialCompleted).isEmpty())
        // Skip means skip the demo, not onboarding; it is not a "continue".
        assertTrue(log.props(OnboardingEvent.continueTapped).isEmpty())
    }

    /** Amendment aj: SKIP DEMO from any beat reports that beat. */
    @Test fun skipFromAnyBeatReportsIt() {
        for (beat in MockTrialBeat.entries) {
            val (m, log) = model()
            m.go(S.mockTrial)
            m.mockTrialAppeared()
            for (b in MockTrialBeat.entries.drop(1)) if (b.rawValue <= beat.rawValue) m.mockTrialAdvanced(b)
            m.skipMockTrial()
            assertEquals(S.summonsIntro, m.step)
            assertEquals(listOf(mapOf("beat" to beat.analyticsName)), log.props(OnboardingEvent.mockTrialSkipped))
        }
    }

    @Test fun skipBeforeAnyBeatReportsTheOpening() {
        val (m, log) = model()
        m.go(S.mockTrial)
        m.mockTrialAppeared()
        m.skipMockTrial()
        assertEquals(S.summonsIntro, m.step)
        assertEquals(listOf(mapOf("beat" to "opening")), log.props(OnboardingEvent.mockTrialSkipped))
    }

    /** A second tap while the page transition commits (or a late autoplay beat) does nothing. */
    @Test fun exitsAreIgnoredOnceTheStepMovedOn() {
        val (m, log) = model()
        m.go(S.mockTrial)
        m.completeMockTrial()
        m.completeMockTrial()
        m.skipMockTrial()
        m.mockTrialAdvanced(MockTrialBeat.verdict)
        assertEquals(S.summonsIntro, m.step)
        assertEquals(1, log.props(OnboardingEvent.mockTrialCompleted).size)
        assertTrue(log.props(OnboardingEvent.mockTrialSkipped).isEmpty())
        assertTrue(log.props(OnboardingEvent.mockTrialAdvanced).isEmpty())
    }

    /** Re-entering the demo (back from the summons explainer) replays from the opening and reports a new view. */
    @Test fun reenteringReplaysFromTheOpening() {
        val (m, log) = model()
        m.go(S.mockTrial)
        m.mockTrialAppeared()
        m.mockTrialAdvanced(MockTrialBeat.deliberation)
        m.completeMockTrial()
        m.back()
        m.mockTrialAppeared()
        assertEquals(MockTrialBeat.opening, m.mockTrialBeat)
        m.skipMockTrial()
        assertEquals(2, log.props(OnboardingEvent.mockTrialViewed).size)
        assertEquals(listOf(mapOf("beat" to "opening")), log.props(OnboardingEvent.mockTrialSkipped))
    }

    /** Leaving mid-demo and reopening lands on the demo again (signed out: device scope). */
    @Test fun killedMidDemoResumesOnTheDemo() {
        val d = freshDefaults()
        val (m, _) = model(d)
        m.advance()
        m.mockTrialAppeared()
        m.mockTrialAdvanced(MockTrialBeat.defendantOpening)
        assertEquals(11, d.integer(OnboardingModel.Key.step.key(null)))
        val relaunched = OnboardingModel(d)
        assertEquals(S.mockTrial, relaunched.step)
        assertEquals(MockTrialBeat.opening, relaunched.mockTrialBeat)
        relaunched.skipMockTrial()
        assertEquals(S.summonsIntro, relaunched.step)
        assertEquals(S.summonsIntro, OnboardingModel(d).step)
    }

    /** Signing in never moves backwards by raw value: a saved Mock Trial (11) is behind Identity (5). */
    @Test fun signInComparesStepsInDisplayOrder() {
        val d = freshDefaults()
        val uid = UUID.randomUUID()
        val p = profile(uid, "Arif")
        val m = OnboardingModel(d)
        m.userChanged(uid, p)
        m.go(S.mockTrial)
        // Relaunch signed out, walk to identity, then the session restores with the older saved demo step.
        val relaunched = OnboardingModel(d)
        relaunched.go(S.identity)
        relaunched.userChanged(uid, p)
        assertEquals(S.identity, relaunched.step)
        // Old rule (max raw value) would have jumped back to the demo.
        assertNotEquals(S.mockTrial, relaunched.step)
    }

    /** A signed-in user saved on the demo resumes on the demo. */
    @Test fun signedInUserResumesOnTheDemo() {
        val d = freshDefaults()
        val uid = UUID.randomUUID()
        val p = profile(uid, "Arif")
        val m = OnboardingModel(d)
        m.userChanged(uid, p)
        m.go(S.mockTrial)
        assertEquals(11, d.integer(OnboardingModel.Key.step.key(uid.uuidString)))
        val relaunched = OnboardingModel(d)
        relaunched.userChanged(uid, p)
        assertEquals(S.mockTrial, relaunched.step)
    }

    @Test fun analyticsNamesMatchTheBrief() {
        assertEquals("onboarding_mock_trial_viewed", OnboardingEvent.mockTrialViewed)
        assertEquals("onboarding_mock_trial_advanced", OnboardingEvent.mockTrialAdvanced)
        assertEquals("onboarding_mock_trial_completed", OnboardingEvent.mockTrialCompleted)
        assertEquals("onboarding_mock_trial_skipped", OnboardingEvent.mockTrialSkipped)
    }
}
