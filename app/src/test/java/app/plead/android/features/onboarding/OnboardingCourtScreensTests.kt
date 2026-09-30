// Port of ArgueWinTests/OnboardingCourtScreensTests.swift: onboarding redesign 2.0 (CONTRACTS-v2 amendment ak),
// Meet the AI Court (`CourtPanelScreenTests`) and Example Cases (`CaseDocketScreenTests`).
package app.plead.android.features.onboarding

import app.plead.android.models.JudgePersona
import app.plead.android.models.JurorRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourtPanelScreenTests {
    /** Amendment am: exact copy; the headline stays the one the onboarding UI tests look for, the CTA is CONTINUE. */
    @Test fun copyMatchesAmendmentAmAndTheUITests() {
        assertEquals("MEET THE AI COURT", CourtPanelScreen.eyebrow.uppercase())
        assertEquals("One judge. Multiple opinions.", CourtPanelScreen.headline)
        assertEquals("Multiple AI jurors review both sides before Judge Wigsworth gives the final ruling.", CourtPanelScreen.copy)
        assertEquals("Judge Wigsworth considers their findings and delivers the final ruling.", CourtPanelScreen.rulingLine)
        assertEquals("Continue", CourtPanelScreen.cta)
        assertEquals("CONTINUE", CourtPrimaryButton.visibleTitle(CourtPanelScreen.cta).uppercase())
        assertTrue(JudgeHero.accessibilityLabel(JudgePersona.wigsworth).startsWith("Judge Wigsworth"))
    }

    @Test fun jurorsAreEvidenceConsistencyFairnessInOrder() {
        assertEquals(listOf(JurorRole.evidence, JurorRole.consistency, JurorRole.fairness), CourtPanelScreen.jurors.map { it.role })
        assertEquals(listOf("EVIDENCE", "CONSISTENCY", "FAIRNESS"), CourtPanelScreen.jurors.map { it.title.uppercase() })
        assertEquals(
            listOf("What do the receipts actually prove?", "Whose story holds together?", "What would be a reasonable outcome?"),
            CourtPanelScreen.jurors.map { it.line },
        )
        assertEquals("Evidence juror. What do the receipts actually prove?", CourtPanelScreen.jurors[0].accessibilityLabel)
    }

    /** Amendment am: the screen is on cream, so the container no longer darkens behind it. */
    @Test fun meetTheCourtSitsOnCream() {
        assertFalse(OnboardingContainer.isFullBleed(OnboardingStep.aiCourt))
    }

    /**
     * Judge first, then headline and copy, then each juror in turn with its light after it settles; the ruling line
     * and CTA last. Stagger 100–150 ms, durations 300–450 ms (amendment ak).
     */
    @Test fun motionAssemblesJudgeThenJurorsThenCTA() {
        val L = CourtPanelTimeline
        val n = CourtPanelScreen.jurors.size
        assertTrue(L.judge < L.headline && L.headline <= L.copy && L.copy < L.juror(0))
        for (i in 0 until n) {
            assertTrue(L.light(i) > L.jurorSettled(i))
            if (i > 0) {
                val stagger = L.juror(i) - L.juror(i - 1)
                assertTrue(stagger >= 0.1 - 1e-9 && stagger <= 0.15)
                assertTrue(L.light(i) > L.light(i - 1))
            }
        }
        assertTrue(L.ruling(n) > L.light(n - 1))
        assertTrue(L.cta(n) > L.ruling(n))
        assertTrue(CourtPanelTokens.jurorDuration in 0.3..0.45)
        assertTrue(CourtPanelTokens.judgeDuration in 0.3..0.45)
        assertEquals(Math.round(CourtPanelTokens.judgeCell).toFloat(), CourtPanelTokens.judgeCell)
    }
}

class CaseDocketScreenTests {
    @Test fun copyMatchesTheUITests() {
        assertEquals("What's going to court first?", CaseDocketScreen.headline)
        assertEquals("Continue", CaseDocketScreen.cta)
    }

    @Test fun threeExampleCasesFromTheBrief() {
        val cases = CaseDocketScreen.cases
        assertEquals(listOf("The Spoiler", "The Late Reply", "The Last Slice"), cases.map { it.title })
        assertEquals(listOf("CASE #016", "CASE #021", "CASE #014"), cases.map { it.caseLabel })
        assertEquals(
            listOf("Is a meaningful look a spoiler?", "Is 6 hours too long to text back?", "Was the last slice fair game?"),
            cases.map { it.question },
        )
        assertEquals(listOf("TV & film", "Texting", "Food"), cases.map { it.category })
        assertEquals(listOf("IN TRIAL", "DELIBERATING", "VERDICT"), cases.map { it.status.stamp })
        assertEquals(cases.size, cases.map { it.id }.toSet().size)
        for (c in cases) assertNotEquals(c.plaintiff, c.defendant)
    }

    @Test fun eachCardIsOneSpokenElement() {
        assertEquals("Case 16, The Spoiler. Is a meaningful look a spoiler? TV & film. In trial.", CaseDocketScreen.cases[0].accessibilityLabel)
    }

    /** Files enter one after another; each stamp lands after its file has settled; the CTA comes last. */
    @Test fun stampsFollowTheirCardsAndTheCTAComesLast() {
        val n = CaseDocketScreen.cases.size
        for (i in 0 until n) {
            val card = PleadRevealParameters.make(PleadRevealKind.card, index = i, reduceMotion = false)
            assertTrue(CaseDocketScreen.stampDelay(i) > card.end)
            if (i > 0) assertTrue(CaseDocketScreen.stampDelay(i) > CaseDocketScreen.stampDelay(i - 1))
        }
        assertTrue(CaseDocketScreen.ctaAt > CaseDocketScreen.stampDelay(n - 1))
    }
}
