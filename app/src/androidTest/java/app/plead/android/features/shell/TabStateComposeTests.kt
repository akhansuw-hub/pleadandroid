// Tab state retention (iOS `TabView` keeps each tab alive): Home's scroll position and a case record pushed in Cases
// survive switching to another tab and back (MainTabScreen's saveable state holder + TabNavHost adopting its restored
// stack).
package app.plead.android.features.shell

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.support.PleadComposeTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TabStateComposeTests : PleadComposeTestCase() {

    /** A vertical scroll container that has somewhere to scroll (the tab's page, not a nested strip). */
    private val scroller = SemanticsMatcher("vertical scroller") { n ->
        n.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.let { it.maxValue() > 0f } == true
    }

    private fun scrollOffset(node: SemanticsNode): Float =
        node.config[SemanticsProperties.VerticalScrollAxisRange].value()

    /** The offset of the tallest scroll container on screen (the page). */
    private fun pageOffset(): Float = scrollOffset(nodes(scroller).maxBy { it.size.height })

    private fun scrollPage() {
        val page = nodes(scroller).maxBy { it.size.height }
        rule.onAllNodes(SemanticsMatcher("page") { it.id == page.id })[0].performTouchInput { swipeUp() }
        rule.waitForIdle()
    }

    private fun switchTo(tab: String) {
        tap(tabButton(tab), tab)
        assertTrue("$tab never selected", waitUntil { isSelected(node(tabButton(tab))) })
    }

    @Test fun testHomeKeepsItsScrollPositionAcrossTabs() {
        launch()
        assertTabs()
        assertTrue("Home has no scroll container", waitUntil { exists(scroller) })
        scrollPage()
        val scrolled = pageOffset()
        assertTrue("Home did not scroll ($scrolled)", scrolled > 0f)

        switchTo("Cases")
        waitFor("The docket")
        switchTo("Home")
        assertTrue("Home has no scroll container", waitUntil { exists(scroller) })
        assertEquals("Home's scroll position was not kept", scrolled, pageOffset(), 1f)
    }

    @Test fun testCasesKeepsThePushedRecordAcrossTabs() {
        launch("AWTab" to "cases")
        waitFor("The docket")
        val files = SemanticsMatcher("case file") { n -> tag(n)?.matches(Regex("casefile\\.[0-9]+")) == true }
        assertTrue("No case files", waitUntil(5_000) { exists(files) })
        tap(files, "first case file")
        assertTrue("Case detail never opened", waitUntil { exists(navigationBar("Case #")) })
        val title = label(node(navigationBar("Case #")))
        val path = model.router.casesPath
        assertTrue("Record scrolls", waitUntil { exists(scroller) })
        scrollPage()
        val scrolled = pageOffset()

        switchTo("Us")
        waitFor("Presiding judge")
        switchTo("Cases")
        assertTrue("The record was not kept", waitUntil { exists(navigationBar("Case #")) })
        assertEquals(title, label(node(navigationBar("Case #"))))
        assertEquals(path, model.router.casesPath)
        assertEquals("The record's scroll position was not kept", scrolled, pageOffset(), 1f)
    }
}
