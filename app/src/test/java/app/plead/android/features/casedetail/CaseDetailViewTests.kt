// The CaseDetailView checks of ArgueWinTests/SecurityAlignmentTests.swift (`provisionalRulingFlag`,
// `defenceEvidenceIsSealedForThePlaintiffUntilFiled`), plus the record's verdict headline and panel preferences.
package app.plead.android.features.casedetail

import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.JSONValue
import app.plead.android.models.JurorReview
import app.plead.android.models.JurorRole
import app.plead.android.models.Role
import app.plead.android.models.Verdict
import app.plead.android.models.VerdictKind
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.PreviewData
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CaseDetailViewTests {
    @get:Rule val main = MainDispatcherRule()

    private val p = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val d = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun verdict(fallback: Boolean?, kind: VerdictKind = VerdictKind.ruling, winner: UUID? = null, tie: Boolean = true, split: String? = null) = Verdict(
        id = UUID.randomUUID(), caseId = UUID.randomUUID(), kind = kind, winnerId = winner, isTie = tie, recap = "",
        findings = emptyList(), sentence = "", closingLine = "", panelSplit = split, isFallback = fallback,
    )

    private fun kase(status: CaseStatus): Case = Case(
        id = UUID.randomUUID(), coupleId = UUID.randomUUID(), caseNumber = 1, title = "t", plaintiffId = p, defendantId = d,
        status = status, charge = "c", remedyRequested = "r",
    )

    @Test fun provisionalRulingFlag() {
        assertTrue(VerdictCard.isProvisional(verdict(true)))
        assertFalse(VerdictCard.isProvisional(verdict(false)))
        assertFalse(VerdictCard.isProvisional(verdict(null)))
        assertEquals("Provisional ruling · the full panel could not sit in time", VerdictCard.provisionalNote)
    }

    @Test fun defenceEvidenceIsSealedForThePlaintiffUntilFiled() {
        val c = kase(CaseStatus.defence)
        assertTrue(CaseDetailView.defenceEvidenceSealed(c, Role.plaintiff))
        assertFalse(CaseDetailView.defenceEvidenceSealed(c, Role.defendant))
        assertFalse(CaseDetailView.defenceEvidenceSealed(c.copy(status = CaseStatus.scheduling), Role.plaintiff))
    }

    @Test fun verdictHeadlines() {
        val c = kase(CaseStatus.closed)
        assertEquals("GUILTY PLEA · REMEDY GRANTED", VerdictCard.headline(verdict(null, kind = VerdictKind.guilty), c))
        assertEquals("DEFAULT JUDGMENT", VerdictCard.headline(verdict(null, kind = VerdictKind.default), c))
        assertEquals("TIE · 1-1-1 PANEL", VerdictCard.headline(verdict(null, split = "1-1-1"), c))
        assertEquals("PLAINTIFF WINS · 2-1 PANEL DECISION", VerdictCard.headline(verdict(null, winner = p, tie = false, split = "2-1"), c))
        assertEquals("DEFENDANT WINS", VerdictCard.headline(verdict(null, winner = d, tie = false), c))
    }

    @Test fun panelPreferences() {
        val store = PreviewData.store()
        val c = PreviewData.wonCase
        fun review(winner: UUID?, tie: Boolean = false) = JurorReview(
            id = UUID.randomUUID(), caseId = c.id, jurorRole = JurorRole.evidence, findings = JSONValue.Obj(emptyMap()),
            preferredWinnerId = winner, isTie = tie, confidence = 0.8,
        )
        assertEquals(PanelSection.Preference("Favoured a tie", null), PanelSection.preference(review(null, tie = true), c, store))
        assertEquals("Favoured you", PanelSection.preference(review(PreviewData.me.id), c, store).text)
        assertEquals("Favoured Alex", PanelSection.preference(review(PreviewData.partner.id), c, store).text)
    }
}
