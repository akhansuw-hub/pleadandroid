// Port of ArgueWinUITests/UsTabTests.swift. The Us tab (CONTRACTS-v2 amendment af): pairing line, the record lead,
// the presiding-judge selection, and the invite state without a partner.
package app.plead.android.features.shell

import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.plead.android.support.PleadComposeTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UsTabComposeTests : PleadComposeTestCase() {

    private fun tagged(tag: String) = hasTestTag(tag)

    @Test fun testPairRecordAndPresidingJudge() {
        launch("AWTab" to "us")
        assertTabs()
        waitFor("us.title")
        val pairing = label(node(tagged("us.pairing")))
        assertTrue("Pairing line was \"$pairing\"", pairing.startsWith("In court together since "))
        assertFalse("The old pairing line is still on screen", exists(containing("Legally bound since")))
        waitFor("us.record.lead")
        val lead = label(node(tagged("us.record.lead")))
        assertTrue("Record lead was \"$lead\"", lead.endsWith("heard") || lead.endsWith("heard yet"))

        // Wigsworth presides; the other personas are listed in the same treatment but are not selectable yet
        // (CONTRACTS-v2: personas other than Wigsworth remain "coming soon"), so tapping one leaves the selection.
        val wigsworth = swipeThrough("us.judge.wigsworth")
        assertTrue("Wigsworth should be the selected judge", isSelected(node(wigsworth)))
        assertTrue(stateDescription(node(wigsworth)).contains("Presiding"))
        swipeThrough("us.judge.chaos") // the last row: reachable (hittable) above the tab bar
        val bluntRow = tagged("us.judge.blunt")
        assertFalse(isSelected(node(bluntRow)))
        assertTrue(stateDescription(node(bluntRow)).contains("Coming soon"))
        first(bluntRow).performClick()
        assertTrue("Selection moved off Wigsworth", waitUntil(2_000) { isSelected(node(tagged("us.judge.wigsworth"))) })
        assertFalse("A coming-soon judge became selected", isSelected(node(tagged("us.judge.blunt"))))

        // The last row sits above the tab bar (1 dp of slack, as iOS allows 1 pt).
        val oneDp = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val chaosBottom = bottom(node(tagged("us.judge.chaos")))
        val tabBarTop = top(node(tagged("tabBar")))
        assertTrue("The last judge row is under the tab bar ($chaosBottom > $tabBarTop)", chaosBottom <= tabBarTop + oneDp)
        // Settle at the bottom of the list (iOS also attaches a screenshot here: "us-judges-scrolled").
        first(hasScrollAction() and hasAnyDescendant(tagged("us.judge.chaos"))).performTouchInput { swipeUp() }
        // Let the fling and the overscroll stretch settle on the test clock. Left mid-stretch at the end of the test,
        // the edge effect redraws every frame and ActivityScenario.close() (waitForIdleSync) never returns on a device.
        rule.waitForIdle()
    }

    /** A paid user whose partner has not joined: link step → "Continue on my own" → demo purchase → tabs → Us. */
    @Test fun testNoPartnerShowsInvite() {
        launch("AWDemoStore" to "solo")
        if (waitUntil(5_000) { exists("Continue on my own") }) {
            tap("Continue on my own")
            // The link step cross-fades out; its "Continue on my own" would otherwise also match the CTA below.
            waitForGone("Continue on my own")
        }
        tap(purchaseCTA(), "purchase CTA")
        if (waitUntil(3_000) { exists("secure.apple") }) secureAccountWithApple()
        assertTabs()
        first(tabButton("Us")).performClick()
        waitFor("us.invite.title")
        waitFor("us.invite")
        waitFor("us.editAvatar")
        assertFalse("A pairing line without a partner", exists("us.pairing"))
        waitFor("us.record.lead")
        assertEquals("No cases heard yet", label(node(tagged("us.record.lead"))))
        // iOS attaches a screenshot here ("us-solo"); the Compose run has no attachment store.
    }
}
