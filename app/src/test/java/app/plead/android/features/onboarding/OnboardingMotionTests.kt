// Port of ArgueWinTests/OnboardingMotionTests.swift: amendment r · onboarding motion system
// (docs/onboarding-motion-brief/BRIEF.md §2, §3, §5). Android: eleven active steps (no Privacy & tracking step,
// amendment az), so the rail has ten segments; the iOS expectations are noted beside each Android one.
package app.plead.android.features.onboarding

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OnboardingMotionTests {
    private val T = OnboardingMotionTokens

    @Test fun tokensMatchTheAmendment() {
        assertEquals(0.32, T.headlineDuration, 0.0)
        assertEquals(0.14, T.bodyDelay, 0.0)
        assertEquals(0.28, T.bodyDuration, 0.0)
        assertEquals(0.36..0.44, T.cardDuration)
        assertEquals(0.24, T.cardDelay, 0.0)
        assertEquals(0.12, T.cardStagger, 0.0)
        assertEquals(0.60, T.ctaDelay, 0.0)
        assertEquals(0.28, T.ctaDuration, 0.0)
        assertEquals(0.34, T.screenTransition, 0.0)
        assertEquals(0.97f, T.pressScale)
        assertEquals(listOf(0.97f, 1.03f, 1.0f), T.avatarSpring)
        assertEquals(0.4, T.highlightPulse, 0.0)
        assertTrue(T.notificationDrop in 12f..16f)
        assertEquals(12f, T.docketShift)
        assertEquals(0.96f, T.judgeStartScale)
    }

    @Test fun revealCadencePerKind() {
        val h = PleadRevealParameters.make(PleadRevealKind.headline, reduceMotion = false)
        assertTrue(h.delay == 0.0 && h.duration == 0.32 && h.offset == Offset(0f, 10f) && h.startScale == 1f && !h.springy)
        val b = PleadRevealParameters.make(PleadRevealKind.body, reduceMotion = false)
        assertTrue(b.delay == 0.14 && b.duration == 0.28 && b.offset == Offset(0f, 8f))
        val c = PleadRevealParameters.make(PleadRevealKind.card, reduceMotion = false)
        assertTrue(c.delay == 0.24 && c.duration in T.cardDuration && c.offset == Offset(0f, 12f))
        assertTrue(c.startScale == 0.98f && c.springy)
        val cta = PleadRevealParameters.make(PleadRevealKind.cta, reduceMotion = false)
        assertTrue(cta.delay == 0.60 && cta.duration == 0.28 && cta.offset == Offset(0f, 8f))
        assertTrue(cta.delay in 0.55..0.65)
        // Everything starts hit-testable (never opacity 0).
        for (kind in PleadRevealKind.entries) {
            assertTrue(PleadRevealParameters.make(kind, reduceMotion = false).startOpacity > 0)
        }
    }

    @Test fun cardsStaggerByIndex() {
        for (i in 0 until 5) {
            val p = PleadRevealParameters.make(PleadRevealKind.card, index = i, reduceMotion = false)
            assertTrue(abs(p.delay - (0.24 + 0.12 * i)) < 1e-9)
        }
        assertTrue(T.cardStagger in 0.10..0.14)
        // Parts inside a card follow it: number → title → line.
        val parts = (0 until 3).map { PleadRevealParameters.make(PleadRevealKind.card, index = 1, part = it, reduceMotion = false) }
        assertEquals(PleadRevealParameters.make(PleadRevealKind.card, index = 1, reduceMotion = false).delay, parts[0].delay, 0.0)
        assertTrue(parts[0].delay < parts[1].delay && parts[1].delay < parts[2].delay)
        assertTrue(parts.all { !it.springy && it.startScale == 1f })
    }

    @Test fun reduceMotionIsOpacityOnly() {
        for (kind in PleadRevealKind.entries) {
            for (i in 0 until 4) {
                val p = PleadRevealParameters.make(kind, index = i, reduceMotion = true)
                assertTrue(p.isOpacityOnly)
                assertTrue(p.offset == Offset.Zero && p.startScale == 1f && !p.springy)
                assertTrue(p.startOpacity < 1)
            }
        }
        // Even explicit per-screen overrides are stripped.
        val docket = PleadRevealParameters.make(PleadRevealKind.card, index = 1, reduceMotion = true, offset = Offset(12f, 0f), scale = 0.96f, spring = true)
        assertTrue(docket.isOpacityOnly)
        assertEquals(1f, PleadPressStyle.scale(isPressed = true, reduceMotion = true))
    }

    @Test fun pressStyleScale() {
        assertEquals(0.97f, PleadPressStyle.scale(isPressed = true, reduceMotion = false))
        assertEquals(1f, PleadPressStyle.scale(isPressed = false, reduceMotion = false))
    }

    @Test fun howPleadWorksFinishesWithin085() {
        val cards = HowPleadWorksView.steps.size
        assertEquals(3, cards)
        val lastParts = T.cardPartsEnd(index = cards - 1, parts = 3)
        val lastCard = PleadRevealParameters.make(PleadRevealKind.card, index = cards - 1, reduceMotion = false).end
        val headline = PleadRevealParameters.make(PleadRevealKind.headline, reduceMotion = false).end
        val total = maxOf(lastParts, lastCard, headline)
        assertTrue(total <= 0.85)
        assertTrue(total >= 0.75)
    }

    @Test fun containerTransitionDirection() {
        val forward = OnboardingPageTransition.offsets(forward = true)
        assertEquals(16f, forward.insertion) // next screen from the right
        assertEquals(-12f, forward.removal) // old screen drifts left
        val back = OnboardingPageTransition.offsets(forward = false)
        assertEquals(-16f, back.insertion)
        assertEquals(12f, back.removal)
        assertTrue(abs(forward.insertion) in 14f..18f && abs(forward.removal) in 10f..14f)
    }

    @Test fun progressRailSegments() {
        assertEquals(OnboardingStep.activeSteps().size - 1, OnboardingProgressRail.segmentCount)
        assertNull(OnboardingProgressRail.activeIndex(OnboardingStep.welcome))
        // Amendment y + ai: the Mock Trial Demo is the first railed screen, the summons explainer the second.
        assertEquals(0, OnboardingProgressRail.activeIndex(OnboardingStep.mockTrial))
        assertEquals(1, OnboardingProgressRail.activeIndex(OnboardingStep.summonsIntro))
        assertEquals(2, OnboardingProgressRail.activeIndex(OnboardingStep.howItWorks))
        assertEquals(OnboardingProgressRail.segmentCount - 1, OnboardingProgressRail.activeIndex(OnboardingStep.ready))
        // iOS: 12 steps → 11 segments, Privacy 8, Widgets 9, Ready 10. Android (amendment az, no Privacy & tracking
        // step): 11 steps → 10 segments; a stray `.tracking` reports the next active screen (Widgets).
        assertEquals(10, OnboardingProgressRail.segmentCount)
        assertEquals(8, OnboardingProgressRail.activeIndex(OnboardingStep.tracking))
        assertEquals(8, OnboardingProgressRail.activeIndex(OnboardingStep.widgets))
        assertEquals(9, OnboardingProgressRail.activeIndex(OnboardingStep.ready))
    }

    @Test fun screenMomentsAreOrdered() {
        // Screens 3–4 moved to CourtPanelScreen / CaseDocketScreen (amendment ak); their timelines are covered in
        // OnboardingCourtScreensTests.
        // Screen 6: CTAs settle after the docket.
        assertTrue(PartnerSetupView.docketSettled <= T.ctaDelay + 0.1)
        // Screen 10: user → partner → session line + gavel → CTA.
        val r = OnboardingMotionTokens.Ready
        assertTrue(r.userEnter < r.partnerEnter)
        assertTrue(r.partnerEnter + r.enterDuration <= r.session)
        assertTrue(r.session <= r.gavel && r.gavel <= r.cta)
    }

    @Test fun hapticsOnlyForTheBriefsMoments() {
        assertEquals(
            setOf(
                OnboardingHaptics.Moment.avatarSelected, OnboardingHaptics.Moment.partnerLinked, OnboardingHaptics.Moment.notificationsEnabled,
                OnboardingHaptics.Moment.gavel, OnboardingHaptics.Moment.completion,
            ),
            OnboardingHaptics.Moment.entries.toSet(),
        )
        for (m in OnboardingHaptics.Moment.entries) assertTrue(OnboardingHaptics.intensity(m) <= 1)
    }

    @Test fun inCourtPreviewLabel() {
        assertEquals("IN COURT: ARIF", InCourtPreview.label(" arif "))
        assertEquals("IN COURT: YOU", InCourtPreview.label(""))
    }
}
