// Port of ArgueWinUITests/DocketTests.swift. The Cases tab as the docket (CONTRACTS-v2 amendment af): Open / Closed
// with data-derived counts, action-required files first, the action row runs the flow, the card opens the record.
package app.plead.android.features.shell

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocketComposeTests : ShellUITestCase() {

    private val fileTag = Regex("casefile\\.[0-9]+")

    /** Every case file's open button ("casefile.16"), not its action row ("casefile.16.action"). */
    private val files = SemanticsMatcher("case file") { n -> tag(n)?.let(fileTag::matches) == true }

    private fun filesWhere(predicate: (String) -> Boolean) =
        files and SemanticsMatcher("case file label") { n -> predicate(label(n)) }

    /** A segment of `docket.sections` whose label matches [pattern]. */
    private fun section(pattern: Regex) = SemanticsMatcher("docket section ~ $pattern") { n ->
        labels(n).any(pattern::matches)
    } and hasAnyAncestor(hasTestTag("docket.sections"))

    private fun sectionLabels(): List<String> =
        nodes(SemanticsMatcher("any") { true } and hasAnyAncestor(hasTestTag("docket.sections"))).map(::label)

    private fun openDocket(vararg args: Pair<String, String>) {
        launch(listOf("AWTab" to "cases") + args)
        waitFor("The docket")
    }

    private fun caseDetail() = navigationBar("Case #")

    @Test fun testCountsAndActionRequiredFirst() {
        openDocket()
        // Counts come from the data: the default demo docket has open and closed files.
        val open = section(Regex("Open [0-9]+"))
        val closed = section(Regex("Closed [0-9]+"))
        assertTrue("No 'Open N' segment: ${sectionLabels()}", waitUntil { exists(open) })
        assertTrue("No 'Closed N' segment", exists(closed))
        assertNotEquals("Open 0", label(node(open)))

        // The topmost file needs me.
        assertTrue("No case files", waitUntil(5_000) { exists(files) })
        val top: SemanticsNode? = nodes(files).minByOrNull { top(it) }
        assertTrue("First file does not need me: ${top?.let(::label) ?: "none"}", top?.let(::label)?.contains("needs you") == true)

        // The win–loss record is gone from this tab.
        assertFalse("The record is still on the docket", exists("Your record"))
    }

    @Test fun testActionRowOpensDefence() {
        openDocket()
        // #016 The Spoiler: my defence is due.
        val action = swipeThrough("casefile.16.action")
        assertEquals("File your defence", label(node(action)))
        tap(action, "casefile.16.action")
        assertTrue("Defence flow never opened", waitUntil { exists(navigationBar("Your defence")) })
    }

    @Test fun testCardOpensDetail() {
        openDocket()
        assertTrue("No case files", waitUntil(5_000) { exists(files) })
        tap(files, "first case file")
        assertTrue("Case detail never opened", waitUntil { exists(caseDetail()) })
    }

    @Test fun testClosedShowsVerdictsAndSettlements() {
        openDocket("AWDemoStore" to "settled")
        val closed = section(Regex("Closed .*"))
        tap(closed, "Closed segment")
        assertTrue("Closed never selected", waitUntil(5_000) { isSelected(node(closed)) })

        val verdict = filesWhere { it.contains("Verdict delivered") }
        assertTrue("No verdict files: ${nodes(files).map(::label)}", waitUntil(5_000) { exists(verdict) })
        // No open-case wording on the closed side (the Open list cross-fades out over 200 ms).
        assertTrue(
            "An open file leaked into Closed: ${nodes(files).map(::label)}",
            waitUntil(2_000) { !exists(filesWhere { it.contains("needs you") }) },
        )

        tap(verdict, "a verdict file")
        assertTrue("Case detail never opened", waitUntil { exists(caseDetail()) })
    }

    @Test fun testEmptyDocket() {
        openDocket("AWDemoStore" to "empty")
        waitFor("No open cases")
        waitFor("Bring a new case")
    }
}
