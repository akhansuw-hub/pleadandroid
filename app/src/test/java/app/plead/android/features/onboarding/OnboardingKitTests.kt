// Port of ArgueWinTests/OnboardingKitTests.swift: amendment ak · the onboarding redesign 2.0 kit
// (docs/onboarding-redesign-2-brief/BRIEF.md).
package app.plead.android.features.onboarding

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OnboardingKitTests {
    private val M = OnboardingKitTokens.Motion

    @Test fun motionTokensSitInTheBriefsRanges() {
        assertTrue(M.rise in 6f..12f)
        assertTrue(M.smallRise in 6f..12f)
        assertTrue(M.springRise in 6f..12f)
        assertTrue(M.stagger in 0.100..0.150)
        for (d in listOf(M.duration, M.shortDuration, M.longDuration)) assertTrue(d in 0.300..0.450)
        assertTrue(M.maxTilt <= 1.5 && M.maxTilt > 0)
        assertEquals(0.0, M.delay(0), 0.0)
        assertTrue(abs(M.delay(3) - 3 * M.stagger) < 1e-9)
    }

    @Test fun layerParametersPerStyle() {
        val rise = M.parameters(layer = 2, style = CourtLayerStyle.rise, reduceMotion = false)
        assertTrue(rise.offset == Offset(0f, M.rise) && rise.startScale == 1f && !rise.springy)
        assertTrue(abs(rise.delay - 2 * M.stagger) < 1e-9)
        val spring = M.parameters(layer = 1, style = CourtLayerStyle.spring, reduceMotion = false)
        assertTrue(spring.springy && spring.startScale < 1 && spring.duration in 0.300..0.450)
        val fade = M.parameters(layer = 1, style = CourtLayerStyle.fade, reduceMotion = false)
        assertTrue(fade.isOpacityOnly)
        // Reduce Motion: every style is a fade on the same clock; nothing starts fully transparent (hit-testable).
        for (style in CourtLayerStyle.entries) {
            val p = M.parameters(layer = 4, style = style, reduceMotion = true, extraDelay = 0.5)
            assertTrue(p.isOpacityOnly)
            assertTrue(abs(p.delay - (4 * M.stagger + 0.5)) < 1e-9)
            assertTrue(p.startOpacity > 0)
        }
    }

    @Test fun layoutTokens() {
        assertTrue(OnboardingKitTokens.Size.buttonHeight >= 54.dp)
        assertTrue(OnboardingKitTokens.Size.secondaryHeight >= 44.dp)
        assertTrue(OnboardingKitTokens.Spacing.gutter >= 16.dp)
        assertTrue(OnboardingKitTokens.Size.readySceneTop < OnboardingKitTokens.Size.readySceneBottom)
    }

    @Test fun shellCTAEntrance() {
        val shell = OnboardingShellLayout
        assertEquals(6, shell.ctaEntranceLayer(autoLayers = true, ctaLayer = null, ctaDelay = null, contentCount = 5))
        assertNull(shell.ctaEntranceLayer(autoLayers = false, ctaLayer = null, ctaDelay = null, contentCount = 5))
        assertEquals(4, shell.ctaEntranceLayer(autoLayers = false, ctaLayer = 4, ctaDelay = null, contentCount = 5))
        assertEquals(0, shell.ctaEntranceLayer(autoLayers = true, ctaLayer = 4, ctaDelay = 1.2, contentCount = 5))
    }

    @Test fun railAccessibility() {
        assertEquals("Step 1 of 12", CourtProgressRail.accessibilityText(index = 0, count = 12))
        assertEquals("Step 12 of 12", CourtProgressRail.accessibilityText(index = 11, count = 12))
        // One dot per active screen, Welcome first, Court Is Ready last.
        val steps = OnboardingStep.activeSteps()
        assertEquals(0, OnboardingContainer.railIndex(OnboardingStep.welcome, steps))
        assertEquals(1, OnboardingContainer.railIndex(OnboardingStep.mockTrial, steps))
        assertEquals(steps.size - 1, OnboardingContainer.railIndex(OnboardingStep.ready, steps))
        assertTrue(steps.all(OnboardingContainer::showsTopBar))
    }

    @Test fun buttonIdentifierPassthrough() {
        assertEquals("onboarding.begin", CourtPrimaryButton.resolvedIdentifier("onboarding.begin"))
        assertEquals("onboarding.primary", CourtPrimaryButton.resolvedIdentifier(null))
        // `CourtSecondaryButton(identifier:)` is passed straight to `testTag` (no resolution step on Android).
        assertEquals("File my first case", CourtPrimaryButton.spokenTitle("FILE MY FIRST CASE →"))
        assertEquals("FILE MY FIRST CASE", CourtPrimaryButton.visibleTitle("FILE MY FIRST CASE →"))
        assertEquals("Settle arguments. Let AI judge.", CourtHeadline.spoken("Settle arguments.\nLet AI judge."))
    }

    @Test fun folderTiltIsClamped() {
        assertEquals(1.5, CaseStepCard.clampedTilt(4.0), 0.0)
        assertEquals(-1.5, CaseStepCard.clampedTilt(-4.0), 0.0)
        assertEquals(0.8, CaseStepCard.clampedTilt(0.8), 0.0)
        for (look in HowPleadWorksView.looks) assertTrue(abs(look.tilt) <= M.maxTilt)
    }

    @Test fun screensUseTheBriefsCopy() {
        assertEquals("Settle arguments. Let AI judge.", CourtHeadline.spoken(OnboardingWelcomeView.headline))
        assertEquals("Turn any disagreement into a case. Present both sides and let the court decide.", OnboardingWelcomeView.supporting)
        assertEquals(listOf("File the Case", "Plead Both Sides", "Get a Verdict"), OnboardingWelcomeView.steps.map { it.title })
        assertEquals("ENTER THE COURT", OnboardingWelcomeView.cta)
        assertEquals("onboarding.begin", OnboardingWelcomeView.ctaIdentifier)
        assertEquals(HowPleadWorksView.looks.size, HowPleadWorksView.steps.size)
        assertEquals("Court is now in session", OnboardingCompleteView.headline)
        assertEquals("FILE MY FIRST CASE →", OnboardingCompleteView.primaryTitle)
        assertEquals("EXPLORE PLEAD", OnboardingCompleteView.secondaryTitle)
    }

    @Test fun readyTimelineAssemblesThenStrikes() {
        val r = OnboardingMotionTokens.Ready
        assertTrue(r.judgeEnter < r.userEnter && r.userEnter < r.partnerEnter)
        assertTrue(r.settled < r.gavelRaise && r.gavelRaise < r.gavel)
        // The headline lands with the strike, then the copy, then the CTAs.
        assertTrue(r.session == r.gavel && r.gavel < r.subtitle && r.subtitle <= r.cta)
        assertTrue(r.figureRise in 6f..12f)
    }

    @Test fun readyPodiumNames() {
        assertEquals("Sophie", OnboardingCompleteView.podiumName("  Sophie ", fallback = "Your partner"))
        assertEquals("Your partner", OnboardingCompleteView.podiumName("", fallback = "Your partner"))
        assertEquals("Your partner", OnboardingCompleteView.podiumName(null, fallback = "Your partner"))
        val mine = OnboardingAvatars.presets[0]
        assertNotEquals(mine, OnboardingCompleteView.partnerStandIn(mine))
    }
}
