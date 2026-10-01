// Port of ArgueWinUITests/HomeTests.swift. Home (CONTRACTS-v2 amendment af): the lead is the earliest action the user
// must take, its button names the action and opens the existing flow; with no open case, "Bring a new case" leads.
package app.plead.android.features.shell

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.support.PleadComposeTestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeComposeTests : PleadComposeTestCase() {

    private val primaryLabel = hasTestTag("home.primary.label")

    /** The urgent label as read aloud ("YOUR TURN IN COURT" + the countdown + the supporting line). */
    private fun primaryLabelText(): String = fullLabel(node(primaryLabel))

    /** The button inside the primary file whose label is exactly [title]. */
    private fun actionButton(title: String) = button(
        SemanticsMatcher("label == \"$title\"") { n -> labels(n).any { it.equals(title, ignoreCase = true) } },
    )

    /**
     * Default demo store: several actions are due; the nearest real deadline (The Last Slice, my turn, 9 h) leads with
     * its urgent label, above the new-case panel, and its button opens the court.
     */
    @Test fun testDefaultStoreLeadsWithTheDueAction() {
        launch()
        assertTabs()
        assertTrue("No urgent label on Home", waitUntil { exists(primaryLabel) })
        assertTrue("Urgent label reads: ${primaryLabelText()}", primaryLabelText().contains("Your turn in court", ignoreCase = true))

        // The due case leads; starting a new case is secondary (below it).
        val newCase = hasTestTag("home.newCase")
        assertTrue("No new-case panel", exists(newCase))
        assertTrue(
            "The new-case panel sits above the due case",
            top(node(primaryLabel)) < top(node(newCase)),
        )
        assertFalse("The record is still on Home", exists(containing("Your record")))

        // The action names the real step and opens the existing flow (the court, on this case).
        tap(actionButton("Your turn in court"), "Your turn in court")
        assertTrue("The court tab never opened", waitUntil { exists(tabButton("Court")) && isSelected(node(tabButton("Court"))) })
    }

    /**
     * A defence due leads with YOUR DEFENCE IS DUE and "File your defence", which opens the defence flow. Needs a demo
     * store whose most urgent step is the defence (`AWDemoStore defence`); skipped until DemoHarness provides one (the
     * default store's nearest step is a trial turn), as on iOS.
     */
    @Test fun testDefenceDueOpensTheDefenceFlow() {
        launch("AWDemoStore" to "defence")
        assertTabs()
        assertTrue("No urgent label on Home", waitUntil { exists(primaryLabel) })
        assumeTrue(
            "AWDemoStore defence is not in DemoHarness yet; Home leads with: ${primaryLabelText()}",
            primaryLabelText().contains("Your defence is due", ignoreCase = true),
        )
        tap(actionButton("File your defence"), "File your defence")
        assertTrue("The defence flow never opened", waitUntil { exists(navigationBar("Your defence")) })
    }

    /** No open case: "Bring a new case" leads, with Summon your partner, and nothing urgent is shown. */
    @Test fun testEmptyStoreLeadsWithBringANewCase() {
        launch("AWDemoStore" to "empty")
        assertTabs()
        waitFor("Bring a new case")
        assertFalse("An urgent label with no open case", exists(primaryLabel))
        val summon = hasTestTag("home.summon")
        assertTrue("Summon your partner is not reachable", waitUntil(5_000) { isHittable(summon) })
        assertTrue(top(node(hasTestTag("home.greeting"))) < top(node(hasTestTag("home.newCase"))))
        tap(summon, "Summon your partner")
        // iOS: the filing sheet covers Home (`home.newCase` stops being hittable). A Compose bottom sheet is its own
        // window and leaves Home's nodes "displayed", so the sheet's own nav title ("Summon Alex") is the check.
        assertTrue("Filing a case never opened", waitUntil { exists(navigationBar("Summon ")) })
    }
}
