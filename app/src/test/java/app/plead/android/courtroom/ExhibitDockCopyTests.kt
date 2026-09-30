// Port of `ExhibitDockCopyTests` (ArgueWinTests/ModelDecodingTests.swift), owed by wave 1 to wave 3a: the presenter
// dock's copy in CourtroomLogic.
package app.plead.android.courtroom

import app.plead.android.models.Exhibit
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExhibitDockCopyTests {
    private fun ex(label: ExhibitLabel, caption: String) =
        Exhibit(id = UUID.randomUUID(), caseId = UUID.randomUUID(), ownerId = UUID.randomUUID(), label = label, type = ExhibitType.photo, caption = caption)

    @Test fun defaultPresentLine() {
        assertEquals("The court is directed to Exhibit A: The empty fridge.", CourtroomLogic.defaultPresentLine(ex(ExhibitLabel.A, "The empty fridge")))
        assertEquals("The court is directed to Exhibit AA: Again?", CourtroomLogic.defaultPresentLine(ex(ExhibitLabel.at(26), "Again?")))
        assertEquals("The court is directed to Exhibit B.", CourtroomLogic.defaultPresentLine(ex(ExhibitLabel.B, " ")))
    }

    @Test fun dockLoadsNextUnlessChosen() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val c = UUID.randomUUID()
        assertEquals(a, CourtroomLogic.dockExhibitId(available = listOf(a, b, c), chosen = null))
        assertEquals(c, CourtroomLogic.dockExhibitId(available = listOf(a, b, c), chosen = c))
        assertEquals(a, CourtroomLogic.dockExhibitId(available = listOf(a, b), chosen = c))
        assertNull(CourtroomLogic.dockExhibitId(available = emptyList(), chosen = c))
    }

    @Test fun objectionWindowLineIsPlain() {
        assertEquals(
            "Alex showed Exhibit B. Object if it's unfair, or let it stand.",
            CourtroomLogic.objectionWindowLine(partnerName = "Alex", exhibit = ex(ExhibitLabel.B, "x")),
        )
        assertEquals("Show Exhibit A", CourtroomLogic.showButtonTitle(ex(ExhibitLabel.A, "x")))
    }
}
