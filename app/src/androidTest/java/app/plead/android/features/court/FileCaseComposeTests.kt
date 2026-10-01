// Android-only coverage owed by docs/STATUS.md ("filing"): the File a case sheet (`AWSheet fileCase`, FileCaseView)
// step by step through the real tab shell: the charge (title + statement, Continue disabled until both are filled) →
// the evidence step (a quote exhibit added through the New exhibit sheet) → the remedy → the review → Serve summons,
// after which the sheet closes and the demo store holds the new summoned case.
//
// Not covered here: photo / screenshot exhibits go through the system Photo Picker (`PickVisualMedia`, docs/STATUS.md
// "Not 1:1"), which is outside the app's Compose hierarchy; the quote exhibit exercises the same list and labels.
package app.plead.android.features.court

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.models.CaseStatus
import app.plead.android.support.PleadComposeTestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileCaseComposeTests : PleadComposeTestCase() {
    @Test fun fileCaseSheetStepByStep() {
        val model = launch("AWSheet" to "fileCase")
        val title = "The Thermostat Incident"

        // Step 1 · The charge: Continue stays disabled until the title and the statement are filled.
        waitForContaining("Step 1 of 4")
        waitForContaining("Summon ${model.store.partner?.displayName ?: "your partner"}")
        first(element("Continue")).assertIsNotEnabled()
        fillField(0, title)
        first(element("Continue")).assertIsNotEnabled()
        fillField(1, "The defendant set the thermostat to 17°C while I was in the shower.")
        first(element("Continue")).assertIsEnabled()
        tap("Continue")

        // Step 2 · Evidence: optional; add one quote through the New exhibit sheet.
        waitForContaining("Step 2 of 4")
        tap("Add evidence")
        waitFor("New exhibit")
        tap("Quote")
        waitFor("The quote")
        fillField(0, "It's not cold, you're being dramatic")
        fillField(1, "Their exact words")
        tap("Add")
        waitForGone("New exhibit")
        waitFor("Exhibit A")
        waitForContaining("Their exact words")
        waitFor("Add more evidence")
        tap("Continue")

        // Step 3 · The remedy.
        waitForContaining("Step 3 of 4")
        first(element("Continue")).assertIsNotEnabled()
        fillField(0, "Defendant does the washing up for a week")
        tap("Continue")

        // Step 4 · Review, then serve.
        waitForContaining("Step 4 of 4")
        waitFor(title)
        waitFor("1 exhibit")
        tap("Serve summons")

        // Filed: the sheet closes and the case is summoned.
        assertTrue("The filing sheet never closed", waitUntil(10_000) { model.router.sheet == null })
        waitForGone("Serve summons")
        val filed = model.store.cases.firstOrNull { it.title == title }
        assertTrue("The new case is not in the store", filed != null)
        assertEquals(CaseStatus.summoned, filed!!.status)
    }

    /** Types into the [index]-th text field on screen (the form's fields in order; sheets stack, but only one has fields). */
    private fun fillField(index: Int, text: String) {
        assertTrue("Text field $index never appeared", waitUntil { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size > index })
        rule.onAllNodes(hasSetTextAction())[index].performTextReplacement(text)
    }
}
