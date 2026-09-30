// Port of ArgueWinTests/SummonsIntroTests.swift: the summons explainer after the mock trial (CONTRACTS-v2 amendment ai).
// Android (amendment az, no ATT): the Privacy step is not active, so the flow has eleven screens (iOS: twelve).
package app.plead.android.features.onboarding

import androidx.compose.ui.geometry.Size
import app.plead.android.models.Profile
import app.plead.android.services.UserDefaults
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private fun freshDefaults(): UserDefaults = UserDefaults.inMemory()

private class SummonsEventLog {
    val events = mutableListOf<Pair<String, Map<String, String>>>()
    fun record(e: String, p: Map<String, String>) { events.add(e to p) }
    fun props(name: String): List<Map<String, String>> = events.filter { it.first == name }.map { it.second }
}

class SummonsIntroCopyTests {
    /** The amendment's exact strings. */
    @Test fun exactCopy() {
        assertEquals("THE DEMO IS OVER", SummonsIntroView.eyebrow)
        assertEquals("Your turn to summon them.", SummonsIntroView.headline)
        assertEquals("WHEN TO CALL COURT", SummonsIntroView.sheetEyebrow)
        assertEquals("For the little arguments you can't quite settle yourselves.", SummonsIntroView.lead)
        assertEquals("HOW IT WORKS", SummonsIntroView.howEyebrow)
        assertEquals("Link your partner, file a case and serve a summons. They answer before the judge rules.", SummonsIntroView.explanation)
        assertEquals("CONTINUE", SummonsIntroView.cta)
        assertEquals("COURT SUMMONS", SummonsIntroView.cardKicker + " " + SummonsIntroView.cardTitle)
        assertEquals("YOU vs PARTNER", SummonsIntroView.cardParties)
    }

    /** Copy rules: the product is Plead, never "Premium", no emoji. */
    @Test fun copyRules() {
        val all = listOf(
            SummonsIntroView.eyebrow, SummonsIntroView.headline, SummonsIntroView.sheetEyebrow, SummonsIntroView.lead,
            SummonsIntroView.howEyebrow, SummonsIntroView.explanation, SummonsIntroView.cta, SummonsIntroView.heroAccessibilityText,
        )
        for (s in all) {
            assertFalse(s.contains("arguewin", ignoreCase = true))
            assertFalse(s.contains("premium", ignoreCase = true))
            assertFalse(s.codePoints().anyMatch { it >= 0x1F000 || it in 0x2600..0x27BF })
        }
    }

    /** Motion: a short settle (250–350 ms); Reduce Motion is a plain fade. */
    @Test fun settleTiming() {
        assertTrue(SummonsIntroView.settleDuration in 0.25..0.35)
    }
}

class SummonsIntroStepTests {
    @Test fun stepModel() {
        assertEquals(12, OnboardingStep.summonsIntro.rawValue)
        assertEquals(OnboardingStep.summonsIntro, OnboardingStep.fromRaw(12))
        assertEquals(OnboardingStep.summonsIntro, OnboardingStep.persisted(12))
        assertEquals("summons_intro", OnboardingStep.summonsIntro.screenId)
        assertTrue(OnboardingStep.summonsIntro.showsChrome)
        assertTrue(OnboardingStep.summonsIntro.isPreAuth)
        assertTrue(OnboardingFlow.canAdvance(OnboardingStep.summonsIntro, displayName = ""))
        assertTrue(OnboardingContainer.isFullBleed(OnboardingStep.summonsIntro))
        assertEquals(listOf(OnboardingStep.summonsIntro), OnboardingStep.allCases.filter { OnboardingContainer.isFullBleed(it) })
    }

    @Test fun orderAndCounts() {
        val steps = OnboardingStep.activeSteps()
        assertEquals(11, steps.size) // Android: no Privacy & tracking step (iOS 12, amendment ak)
        assertEquals(listOf(OnboardingStep.welcome, OnboardingStep.mockTrial, OnboardingStep.summonsIntro, OnboardingStep.howItWorks), steps.take(4))
        assertEquals(OnboardingStep.summonsIntro, OnboardingStep.mockTrial.next(steps))
        assertEquals(OnboardingStep.howItWorks, OnboardingStep.summonsIntro.next(steps))
        assertEquals(OnboardingStep.mockTrial, OnboardingStep.summonsIntro.previous(steps))
        assertEquals(OnboardingStep.summonsIntro, OnboardingStep.howItWorks.previous(steps))
        assertEquals("3 OF ${steps.size}", OnboardingStep.summonsIntro.positionLabel(steps))
        assertEquals(1, OnboardingProgressRail.activeIndex(OnboardingStep.summonsIntro, steps))
    }

    @Test fun resumeOntoTheExplainer() {
        assertEquals(OnboardingStep.summonsIntro, OnboardingFlow.resumeStep(user = null, device = 12, signedIn = false, hasProfile = false))
        assertEquals(OnboardingStep.summonsIntro, OnboardingFlow.resumeStep(user = 12, device = null, signedIn = true, hasProfile = true))
        assertEquals(OnboardingStep.summonsIntro, OnboardingFlow.resumeStep(user = 12, device = null, signedIn = true, hasProfile = false))
    }
}

class SummonsIntroFlowTests {
    private fun model(d: UserDefaults = freshDefaults()): Pair<OnboardingModel, SummonsEventLog> {
        val m = OnboardingModel(d)
        val log = SummonsEventLog()
        m.analyticsSink = { e, p -> log.record(e, p) }
        return m to log
    }

    @Test fun completingTheMockTrialLandsOnTheExplainer() {
        val (m, _) = model()
        m.go(OnboardingStep.mockTrial)
        m.mockTrialAppeared()
        m.mockTrialAdvanced(MockTrialBeat.verdict)
        m.completeMockTrial()
        assertEquals(OnboardingStep.summonsIntro, m.step)
        assertEquals(OnboardingModel.Direction.forward, m.direction)
    }

    @Test fun skippingTheMockTrialLandsOnTheExplainer() {
        val (m, _) = model()
        m.go(OnboardingStep.mockTrial)
        m.mockTrialAppeared()
        m.skipMockTrial()
        assertEquals(OnboardingStep.summonsIntro, m.step)
    }

    /** CONTINUE → How Plead Works; back from there returns here; back from here returns to the demo. */
    @Test fun continueAndBack() {
        val (m, log) = model()
        m.go(OnboardingStep.summonsIntro)
        m.screenAppeared(OnboardingStep.summonsIntro)
        m.advance()
        assertEquals(OnboardingStep.howItWorks, m.step)
        m.back()
        assertEquals(OnboardingStep.summonsIntro, m.step)
        assertEquals(OnboardingModel.Direction.backward, m.direction)
        m.back()
        assertEquals(OnboardingStep.mockTrial, m.step)
        // The shared per-step events only: no new event names.
        assertEquals(listOf(mapOf("screen_id" to "summons_intro")), log.props(OnboardingEvent.screenViewed))
        assertEquals(listOf(mapOf("screen_id" to "summons_intro")), log.props(OnboardingEvent.continueTapped))
        val names = log.events.map { it.first }.toSet()
        assertTrue(setOf(OnboardingEvent.started, OnboardingEvent.screenViewed, OnboardingEvent.continueTapped).containsAll(names))
    }

    /** Killed on the explainer: relaunch resumes on it (device scope, signed out). */
    @Test fun killedOnTheExplainerResumesThere() {
        val d = freshDefaults()
        val (m, _) = model(d)
        m.go(OnboardingStep.mockTrial)
        m.skipMockTrial()
        assertEquals(12, d.int(OnboardingModel.Key.step.key(null)))
        val relaunched = OnboardingModel(d)
        assertEquals(OnboardingStep.summonsIntro, relaunched.step)
        relaunched.advance()
        assertEquals(OnboardingStep.howItWorks, relaunched.step)
    }

    /** Signing in on a later screen never jumps back to the explainer (display order, not raw value 12). */
    @Test fun signInComparesInDisplayOrder() {
        val d = freshDefaults()
        val uid = UUID.randomUUID()
        val profile = Profile(id = uid, displayName = "Arif")
        val m = OnboardingModel(d)
        m.userChanged(uid, profile)
        m.go(OnboardingStep.summonsIntro)
        val relaunched = OnboardingModel(d)
        relaunched.go(OnboardingStep.identity)
        relaunched.userChanged(uid, profile)
        assertEquals(OnboardingStep.identity, relaunched.step)
    }
}

/** The composition rules on both phone sizes (points: iPhone 17 Pro 402×874, iPhone 16e 390×844, plus a Pixel 7 in dp). */
class SummonsIntroLayoutTests {
    private val phones = listOf(
        Triple(Size(402f, 874f), 62f, 34f),
        Triple(Size(390f, 844f), 47f, 34f),
        Triple(Size(411f, 914f), 24f, 24f), // Android-only: Pixel 7 (412×915 dp, gesture navigation)
    )

    /**
     * Default text: headline (eyebrow + 2 lines ≈ 100 pt) on the art, clear of the judge; the art covers the top edge
     * and fills the width; the sheet does not scroll; CONTINUE clears the home indicator.
     */
    @Test fun defaultSizesKeepTheHeadlineOnTheWall() {
        for ((size, top, bottom) in phones) {
            val l = SummonsIntroLayout(size, safeTop = top, safeBottom = bottom, headlineHeight = 100f, copyHeight = 190f, footerHeight = 0f)
            assertTrue(l.headlineInHero)
            assertTrue(l.headlineTop >= top + OnboardingContainer.chromeHeight.value)
            assertTrue(l.headlineBottom + SummonsIntroLayout.judgeClearance <= l.judgeTop)
            assertTrue(l.artOffsetY <= 0)
            assertTrue(l.artSize.width >= size.width - 0.5f)
            assertFalse(l.sheetScrolls)
            assertTrue(l.buttonBottomPadding >= bottom)
            // The podiums' hearts (art row ≈ 0.61) are above the sheet.
            assertTrue(l.artOffsetY + 0.61f * l.artSize.height < l.sheetTop)
            assertTrue(l.scrimBottom <= l.judgeTop)
        }
    }

    /**
     * Large text: the headline would reach the judge, so it drops onto the sheet; the hero stops at its minimum (judge
     * still below the chrome) and the sheet scrolls.
     */
    @Test fun largeTextDropsTheHeadlineOntoTheSheet() {
        for ((size, top, bottom) in phones) {
            val l = SummonsIntroLayout(size, safeTop = top, safeBottom = bottom, headlineHeight = 260f, copyHeight = 700f, footerHeight = 130f)
            assertFalse(l.headlineInHero)
            assertTrue(l.sheetScrolls)
            assertTrue(l.judgeTop >= top + OnboardingContainer.chromeHeight.value)
            assertTrue(l.sheetTop > l.judgeTop)
            assertTrue(l.scrollHeight > 0)
        }
    }
}
