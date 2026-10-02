// The selected-tab pill (iOS 26's selection capsule, app/TabBarPill.kt): with the pill drawn, each tab still reports
// selected when tapped, and the pill settles centred behind the selected tab, inside the bar, the same size on every
// tab.
package app.plead.android.features.shell

import androidx.compose.ui.semantics.SemanticsNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.plead.android.support.PleadComposeTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class TabBarPillComposeTests : PleadComposeTestCase() {

    private val tabs = listOf("Home", "Cases", "Court", "Us")
    private val oneDp get() = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density

    private fun pill(): SemanticsNode = node(tag("tabBarPill"))

    private fun centreX(node: SemanticsNode): Float = node.positionInRoot.x + node.size.width / 2f

    /** The pill has finished sliding and sits centred behind [tab]. */
    private fun pillSettledOn(tab: String): Boolean = abs(centreX(pill()) - centreX(node(tabButton(tab)))) <= oneDp

    @Test fun testPillFollowsTheSelectedTab() {
        launch()
        assertTabs()
        assertTrue("The pill is not behind Home", waitUntil { pillSettledOn("Home") })
        val bar = node(tag("tabBar"))
        val size = pill().size

        for (tab in listOf("Cases", "Court", "Us", "Home", "Court")) {
            tap(tabButton(tab), tab)
            assertTrue("$tab never selected", waitUntil { isSelected(node(tabButton(tab))) })
            for (other in tabs - tab) assertFalse("$other still selected with $tab", isSelected(node(tabButton(other))))
            assertTrue("The pill did not settle behind $tab", waitUntil { pillSettledOn(tab) })

            val p = pill()
            assertEquals("Pill size changed on $tab", size, p.size)
            assertTrue("Pill leaves the bar on $tab", p.positionInRoot.x >= bar.positionInRoot.x &&
                p.positionInRoot.x + p.size.width <= bar.positionInRoot.x + bar.size.width)
            assertTrue("Pill above the bar on $tab", p.positionInRoot.y >= top(bar))
            assertTrue("Pill below the bar on $tab", bottom(p) <= bottom(bar))
            // Inside its tab's touch target (48 dp at least, the target stays the full item).
            val item = node(tabButton(tab))
            assertTrue("$tab target under 48 dp", item.size.height >= 48 * oneDp - 1 && item.size.width >= 48 * oneDp)
            assertTrue("Pill wider than $tab's slot", p.size.width <= item.size.width)
        }
    }

    @Test fun testPillStartsOnTheLaunchTab() {
        launch("AWTab" to "court")
        assertTabs()
        assertTrue("Court not selected", waitUntil { isSelected(node(tabButton("Court"))) })
        assertTrue("The pill is not behind Court", waitUntil { pillSettledOn("Court") })
    }
}
