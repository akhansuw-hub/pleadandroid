// Port of ArgueWinUITests/TrialFlowTests.swift: a full demo trial on the Court tab (the default demo store's trial case):
// exhibits, objection windows, cross-examination, closing → deliberation → verdict → judgement delivered; and the
// opening-statement help sheet (amendment ae) keeping the draft and the phase label.
//
// Android differences (docs/STATUS.md "Not 1:1"): the verdict sequence is a full-screen Dialog (iOS fullScreenCover),
// so "hittable" CHOOSE JUDGEMENT / Back to docket means the copy inside that dialog (the dock under it has its own);
// help and judgement sheets are Material bottom sheets. The courtroom's directors sleep on the composition's clock,
// which `waitUntil` keeps advancing, so every wait here polls (no fixed sleeps, no `waitForIdle` on its own).
package app.plead.android.features.court

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.support.PleadComposeTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrialFlowComposeTests : PleadComposeTestCase() {
    @Test fun testTrialRunsToVerdictAndJudgement() {
        launch("AWTab" to "court")
        assertTrue("The court dock never appeared", waitForExistence(element("court.dock.header"), DEFAULT_TIMEOUT_MS))

        // 1 · Drive the dock until the court retires (bounded: ≤ 60 s, ≤ 80 actions).
        val deliberating = containing("THE COURT IS DELIBERATING")
        val deadline = System.currentTimeMillis() + 60_000
        val actions = mutableListOf<String>()
        while (!exists(deliberating) && System.currentTimeMillis() < deadline && actions.size < 80) {
            val action = playDockOnce()
            if (action != null) actions += action else pause(300)
        }
        assertTrue("The court never retired. Actions: ${actions.joinToString(", ")}", waitForExistence(deliberating, 10_000))
        assertTrue("Never showed an exhibit: $actions", actions.contains("show"))

        // 2 · Verdict: the sequence opens on ALL RISE (~4 s deliberation + 3 s reveal at fast speed).
        waitFor("ALL RISE", timeoutMs = 25_000)
        // Continue through the sequence to CHOOSE JUDGEMENT (I won) or the result card (I didn't / tie).
        // (The dock under the dialog has its own "Choose judgement", so only the one in the verdict dialog counts.)
        val choose = inVerdict(element("Choose judgement"))
        val backToDocket = inVerdict(element("Back to docket"))
        val next = inVerdict(element("Continue"))
        var steps = 0
        while (hittable(choose) == null && hittable(backToDocket) == null && steps < 15) {
            var nextButton: SemanticsNodeInteraction? = null
            if (waitUntil(3_000) { hittable(next)?.also { nextButton = it } != null }) nextButton?.let { tryClick(it) }
            steps += 1
        }

        val chooseButton = hittable(choose)
        if (chooseButton != null) {
            // 3a · I'm the chooser: pick an outcome and DELIVER JUDGEMENT.
            chooseButton.performClick()
            tap(judgementOption, "first judgement option", timeoutMs = 15_000)
            tap(beginningWith("DELIVER "), "judgement.deliver")
        } else {
            // 3b · Alex won (or a tie): the simulated partner / the court delivers. Leave the sequence.
            tap(backToDocket, "Back to docket")
        }

        // 4 · Delivery (screen C): the judge's large bubble reads "All rise. <judge>: <judgement>".
        assertTrue("The judgement was never delivered", waitForExistence(beginningWith("All rise. "), 30_000))
    }

    /**
     * Amendment ae: on the plaintiff's opening turn, the info button beside "Your opening statement" opens the help
     * sheet; "Got it" returns to the same court with the draft and the phase label intact.
     */
    @Test fun testOpeningHelpKeepsDraftAndPhaseLabel() {
        launch("AWTab" to "court", "AWCourtFixture" to "opening")
        // The entrance and case call (amendment ad) play first on a fresh install; the composer follows.
        val compose = tag("court.compose")
        assertTrue("The opening composer never appeared", waitForExistence(compose, 40_000))
        waitUntil(10_000) { hittable(compose) != null }

        val draft = "Alex promised to save the last slice"
        val field = first(compose)
        field.performClick()
        field.performTextReplacement(draft)

        val info = tag("court.help.openingStatement")
        assertTrue("No help button beside the opening statement", waitForExistence(info, 5_000))
        val infoNode = first(info)
        assertEquals(
            "What is an opening statement?",
            infoNode.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull(),
        )
        infoNode.assertWidthIsAtLeast(44.dp)
        infoNode.assertHeightIsAtLeast(44.dp)
        infoNode.performClick()

        assertTrue("The help sheet did not open", waitForExistence(element("court.help.sheet"), 5_000))
        assertTrue(exists(containing("What's an opening statement?")))
        assertTrue("The example is missing", exists(containing("I'd like a replacement pizza")))
        val gotIt = tag("court.help.gotIt")
        assertTrue(waitForExistence(gotIt, 3_000))
        tap(gotIt, "court.help.gotIt")

        assertTrue("The help sheet did not close", waitForNonExistence(gotIt, 5_000))
        assertEquals("The draft was lost", draft, editableText(compose))
        val header = nodes(element("court.dock.header")).firstOrNull()
        assertTrue("The dock label disappeared", header != null)
        val headerLabel = header!!.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ") ?: ""
        assertTrue("Dock label changed: $headerLabel", headerLabel.contains("Your opening statement"))
        assertTrue("The help button disappeared after dismissal", exists(info))
    }

    /** One dock action, in priority order. Returns what it did, or null if nothing was actionable yet. */
    private fun playDockOnce(): String? {
        // The dock re-renders as the simulated partner and the judge act, so a control found a moment ago may be gone
        // by the time it is clicked (XCUITest re-resolves the element on tap); such a click counts as no action.
        hittable(tag("court.letItStand"))?.let { if (tryClick(it)) return "let it stand" }
        hittable(tag("court.show"))?.let { if (tryClick(it)) return "show" }
        hittable(tag("court.rest"))?.let { if (tryClick(it)) return "rest" }
        // Opening / cross-examination answer / closing: type, then Submit.
        val compose = hittable(tag("court.compose"))
        if (compose != null) {
            val kind = runCatching {
                compose.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull().orEmpty()
            }.getOrDefault("")
            val typed = runCatching {
                compose.performClick()
                compose.performTextReplacement("Your Honour, the record speaks for itself.")
            }.isSuccess
            if (!typed) return null
            val submit = tag("court.submit")
            if (waitUntil(2_000) { hittable(submit) != null }) hittable(submit)?.let { tryClick(it) }
            return kind.ifEmpty { "statement" }
        }
        return null
    }

    /** A node inside the verdict sequence's full-screen dialog (iOS: the cover is the hittable layer). */
    private fun inVerdict(matcher: SemanticsMatcher): SemanticsMatcher = matcher and hasClickAction() and hasAnyAncestor(isDialog())

    /**
     * iOS `judgement.option`: one of JudgementSelectionView's radio cards. They speak their option as one content
     * description with a selected state and a click action (no tag on Android), which nothing else on screen has
     * (the tab bar items are selectable but speak no content description).
     */
    private val judgementOption: SemanticsMatcher = SemanticsMatcher("judgement option card") { node ->
        node.config.contains(SemanticsProperties.Selected) &&
            node.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) &&
            !node.config.getOrNull(SemanticsProperties.ContentDescription).isNullOrEmpty() &&
            node.config.getOrNull(SemanticsProperties.Role) != androidx.compose.ui.semantics.Role.Tab
    }
}
