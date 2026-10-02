// Port of ArgueWinTests/ModelDecodingTests.swift → CaseFlowTests and DeepLinkTests, plus
// WidgetSnapshotTests → WidgetDeepLinkTests. (`clockCountdownFormat` tests `Countdown`, a design-system type: wave 2b.)
package app.plead.android.services

import app.plead.android.app.AppTab
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Role
import java.net.URI
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaseFlowTests {
    companion object {
        val p: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val d: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")

        fun kase(status: CaseStatus, proposals: Int = 0): Case = Case(
            id = UUID.randomUUID(), coupleId = UUID.randomUUID(), caseNumber = 1, title = "t", plaintiffId = p, defendantId = d,
            status = status, charge = "c", remedyRequested = "r", proposalCount = proposals,
        )
    }

    @Test fun whoeverDidNotProposeLastMayAccept() {
        val first = kase(CaseStatus.scheduling, proposals = 0) // defendant proposed with the defence
        assertTrue(first.canAcceptTime(Role.plaintiff))
        assertFalse(first.canAcceptTime(Role.defendant))
        assertTrue(first.canCounterPropose(Role.plaintiff))
        val countered = kase(CaseStatus.scheduling, proposals = 1) // plaintiff countered once
        assertTrue(countered.canAcceptTime(Role.defendant))
        assertFalse(countered.canAcceptTime(Role.plaintiff))
        assertFalse(countered.canCounterPropose(Role.plaintiff))
    }

    @Test fun nextActions() {
        assertEquals(CaseAction.enterPlea, kase(CaseStatus.summoned).nextAction(d))
        assertEquals(CaseAction.awaitPlea, kase(CaseStatus.summoned).nextAction(p))
        assertEquals(CaseAction.fileDefence, kase(CaseStatus.defence).nextAction(d))
        assertEquals(CaseAction.hearVerdict, kase(CaseStatus.verdict).nextAction(p))
        assertEquals(CaseAction.viewRecord, kase(CaseStatus.closedGuilty).nextAction(p))
    }

    @Test fun deliberatingFlow() {
        var c = kase(CaseStatus.deliberating).copy(trialAt = Instant.ofEpochSecond(1_790_400_000), panelProgress = 2)
        assertEquals(CaseAction.awaitVerdict, c.nextAction(p))
        assertEquals(CaseAction.awaitVerdict, c.nextAction(d))
        assertFalse(c.nextAction(p).isActionable)
        assertEquals("Verdict in", c.countdownTarget?.label)
        assertEquals(c.trialAt, c.countdownTarget?.date)
        assertTrue(c.status.isOpen && c.status.isInCourtroom && c.status.isDeliberating)
        assertFalse(c.isRevealed)
        assertEquals(2, c.deliberationStepsDone)
        assertEquals("Deliberating", c.statusTitle)
        c = c.copy(panelProgress = 9)
        assertEquals(4, c.deliberationStepsDone)
        // Verdict row written → awaiting_verdict: every status line is lit, still not revealed.
        val awaiting = kase(CaseStatus.awaitingVerdict).copy(panelProgress = 0)
        assertEquals(4, awaiting.deliberationStepsDone)
        assertFalse(awaiting.isRevealed)
        assertTrue(kase(CaseStatus.verdict).isRevealed)
        assertTrue(kase(CaseStatus.closed).isRevealed)
        assertFalse(kase(CaseStatus.mistrial).isRevealed)
    }

    @Test fun docketNumberIsZeroPadded() {
        var c = kase(CaseStatus.trial).copy(caseNumber = 21)
        assertEquals("Case #021", c.docketNumber)
        c = c.copy(caseNumber = 1234)
        assertEquals("Case #1234", c.docketNumber)
    }

    @Test fun trialWindow() {
        val now = Instant.ofEpochSecond(1_790_000_000)
        assertFalse(TrialWindow.isValid(now.plusSeconds(30 * 60), now))
        assertTrue(TrialWindow.isValid(now.plusSeconds(2 * 3600), now))
        assertFalse(TrialWindow.isValid(now.plusSeconds(8 * 86_400), now))
        assertTrue(TrialWindow.isValid(TrialWindow.defaultProposal(now), now))
    }

    @Test fun settlementRules() {
        val trial = kase(CaseStatus.trial).copy(phase = app.plead.android.models.TrialPhase.plaintiffClosing)
        assertFalse(SettlementRules.canPropose(trial, p, null))
        assertTrue(SettlementRules.canPropose(kase(CaseStatus.summoned), p, null))
        assertFalse(SettlementRules.canPropose(kase(CaseStatus.summoned), UUID.randomUUID(), null))
        assertEquals("Round 3 of 3", SettlementRules.roundLine(7))
        assertEquals("within 1 day", SettlementRules.dueLine(1))
        assertEquals("A disagreement about the Last Slice.", SettlementRules.contextLine(kase(CaseStatus.trial).copy(title = "The Last Slice!")))
        assertEquals("Keep money out of it. Settlements are small, doable actions.", SettlementRules.localSafetyIssue("Pay me £20"))
        assertEquals("No limits on friends or family. Keep it kind and doable.", SettlementRules.localSafetyIssue("You will never see them again"))
        assertNull(SettlementRules.localSafetyIssue("Cook dinner tonight"))
    }
}

class DeepLinkTests {
    private fun url(s: String) = URI(s)

    @Test fun universalJoinLink() {
        assertEquals(DeepLink.join("ABC123"), DeepLink.parse(url("https://www.plead-app.com/join/abc123")))
        assertEquals(DeepLink.join("ABC123"), DeepLink.parse(url("https://www.plead-app.com/join/abc123/")))
        assertNull(DeepLink.parse(url("https://evil.example/join/ABC123")))
        assertNull(DeepLink.parse(url("https://www.plead-app.com.evil.example/join/ABC123")))
        assertNull(DeepLink.parse(url("https://www.plead-app.com/join/ABC")))
    }

    /** Amendment bc: the website domain carries invites; links on the earlier hosts and the apex still parse. */
    @Test fun websiteDomain() {
        assertEquals("www.plead-app.com", AppConfig.universalLinkHost)
        assertEquals("https://www.plead-app.com/join/ABC123", AppConfig.inviteURL(code = "ABC123"))
        assertEquals(DeepLink.join("ABC123"), DeepLink.parse(url("https://plead-app.com/join/abc123")))
        assertEquals(DeepLink.join("ABC123"), DeepLink.parse(url("https://plead-drab.vercel.app/join/abc123")))
        assertEquals(DeepLink.join("ABC123"), DeepLink.parse(url("https://plead.app/join/abc123")))
        assertEquals(DeepLink.join("ABC123"), DeepLink.parse(url("https://www.plead.app/join/abc123")))
        assertNull(DeepLink.parse(url("https://plead-drab.vercel.app.evil.example/join/ABC123")))
    }

    @Test fun deliberationPushLandsInCourt() {
        val id = UUID.randomUUID()
        assertEquals(
            DeepLink.caseRoute(CaseRoute(id, CaseScreen.deliberation)),
            DeepLink.parse(push = mapOf("case_id" to id.uuidString, "screen" to "deliberation")),
        )
    }

    @Test fun pushPayload() {
        val id = UUID.randomUUID()
        assertEquals(DeepLink.caseRoute(CaseRoute(id, CaseScreen.court)), DeepLink.parse(push = mapOf("case_id" to id.uuidString, "screen" to "court")))
        assertNull(DeepLink.parse(push = mapOf("screen" to "court")))
    }

    @Test fun customSchemeCaseLink() {
        val id = UUID.randomUUID()
        assertEquals(DeepLink.caseRoute(CaseRoute(id, CaseScreen.summons)), DeepLink.parse(url("plead://case/${id.uuidString}?screen=summons")))
    }

    @Test fun authCallback() {
        val link = DeepLink.parse(url("plead://login-callback#access_token=x&refresh_token=y"))
        assertTrue(link is DeepLink.authCallback)
    }
}

class WidgetDeepLinkTests {
    @get:org.junit.Rule val main = MainDispatcherRule()

    private val id: UUID = UUID.fromString("0D1E2F30-4152-4637-8899-AABBCCDDEEFF")

    @Test fun pleadCaseLinks() {
        val expected = mapOf(
            "plea" to CaseScreen.summons, "turn" to CaseScreen.court, "settlement" to CaseScreen.settlement, "verdict" to CaseScreen.verdict,
            "judgement" to CaseScreen.judgement, "deliberation" to CaseScreen.deliberation, "unknown" to CaseScreen.detail,
        )
        for ((segment, screen) in expected) {
            val u = URI("plead://case/${id.toString().lowercase()}/$segment")
            assertEquals(segment, DeepLink.caseRoute(CaseRoute(id, screen)), DeepLink.parse(u))
        }
        assertEquals(DeepLink.caseRoute(CaseRoute(id, CaseScreen.detail)), DeepLink.parse(URI("plead://case/${id.uuidString}")))
        assertNull(DeepLink.parse(URI("plead://case/not-a-uuid/plea")))
    }

    @Test fun pleadTabLinks() {
        assertEquals(DeepLink.tab(AppTab.home), DeepLink.parse(URI("plead://home")))
        assertEquals(DeepLink.tab(AppTab.cases), DeepLink.parse(URI("plead://cases")))
        assertNull(DeepLink.parse(URI("plead://nowhere")))
    }

    @Test fun everyWidgetLinkParses() {
        for (state in WidgetState.entries) {
            val u = WidgetSnapshot.deepLink(id, state)
            val link = DeepLink.parse(u)
            if (state == WidgetState.none) {
                assertEquals(DeepLink.tab(AppTab.home), link)
            } else {
                val route = (link as DeepLink.caseRoute).route
                assertEquals(id, route.caseId)
                assertTrue("$state", route.screen != CaseScreen.detail)
            }
        }
        assertEquals(WidgetSnapshot.homeLink, WidgetSnapshot.deepLink(null, WidgetState.summoned))
    }

    @Test fun queryScreenLinksStillWork() { // amendment ah: the `?screen=` push form now lives on plead://
        assertEquals(DeepLink.caseRoute(CaseRoute(id, CaseScreen.verdict)), DeepLink.parse(URI("plead://case/${id.uuidString}?screen=verdict")))
        assertEquals(DeepLink.join("ABC123"), DeepLink.parse(URI("plead://join/abc123")))
        assertEquals(DeepLink.join("ABC123"), DeepLink.parse(URI("https://www.plead-app.com/join/ABC123")))
    }

    @Test fun pushPrefersDataLink() {
        val other = UUID.randomUUID()
        val link = DeepLink.parse(
            push = mapOf("case_id" to other.uuidString, "screen" to "court", "link" to "plead://case/${id.toString().lowercase()}/settlement"),
        )
        assertEquals(DeepLink.caseRoute(CaseRoute(id, CaseScreen.settlement)), link)
        // Old payloads (no link) keep working.
        assertEquals(DeepLink.caseRoute(CaseRoute(other, CaseScreen.court)), DeepLink.parse(push = mapOf("case_id" to other.uuidString, "screen" to "court")))
        assertEquals(DeepLink.caseRoute(CaseRoute(other, CaseScreen.detail)), DeepLink.parse(push = mapOf("link" to "garbage", "case_id" to other.uuidString)))
    }

    @Test fun routerHandlesTabs() {
        val router = DeepLinkRouter()
        router.handle(URI("plead://cases"))
        assertEquals(AppTab.cases, router.pendingTab)
        router.handle(URI("plead://case/${id.uuidString}/turn"))
        assertEquals(CaseRoute(id, CaseScreen.court), router.pendingCaseRoute)
    }
}
