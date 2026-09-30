// Port of ArgueWinTests/UsTests.swift. CONTRACTS-v2 amendment af: the Us tab's record (decided cases only),
// pairing line and no-partner state.
package app.plead.android.features.us

import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Couple
import app.plead.android.models.JudgePersona
import app.plead.android.models.Profile
import app.plead.android.models.Verdict
import app.plead.android.models.VerdictKind
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.PreviewData
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

private val me: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
private val partner: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
private val couple: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
private val exCouple: UUID = UUID.fromString("99999999-9999-9999-9999-999999999999")

private fun kase(status: CaseStatus, plaintiff: UUID = me, coupleId: UUID = couple): Case {
    val defendant = if (plaintiff == me) partner else me
    return Case(
        id = UUID.randomUUID(), coupleId = coupleId, caseNumber = (1..999).random(), title = "A case", plaintiffId = plaintiff,
        defendantId = defendant, status = status, charge = "c", remedyRequested = "r",
    )
}

private fun verdict(c: Case, winner: UUID?, tie: Boolean = false, kind: VerdictKind = VerdictKind.ruling, at: Instant = Instant.now()): Verdict =
    Verdict(
        id = UUID.randomUUID(), caseId = c.id, kind = kind, winnerId = winner, isTie = tie, recap = "", findings = emptyList(),
        sentence = "", closingLine = "", createdAt = at,
    )

class UsRecordTests {
    @Test fun countsOnlyDecidedVerdicts() {
        val won = kase(CaseStatus.closed)
        val lost = kase(CaseStatus.verdict)
        val tied = kase(CaseStatus.closed)
        val record = UsRecord.tally(
            listOf(won, lost, tied),
            listOf(verdict(won, me), verdict(lost, partner), verdict(tied, null, tie = true)),
            me, couple,
        )
        assertEquals(UsRecord(mine = 1, partners = 1, ties = 1), record)
        assertEquals(3, record.heard)
    }

    @Test fun pendingSettledAndStoppedNeverCount() {
        // Every open status (even one that somehow has a verdict row), settled and mistrial.
        val open = listOf(
            CaseStatus.drafting, CaseStatus.summoned, CaseStatus.defence, CaseStatus.scheduling, CaseStatus.trial,
            CaseStatus.deliberating, CaseStatus.awaitingVerdict, CaseStatus.appeal,
        )
        val cases = open.map { kase(it) } + listOf(kase(CaseStatus.closedSettled), kase(CaseStatus.mistrial))
        val verdicts = cases.map { verdict(it, me) }
        assertEquals(UsRecord(), UsRecord.tally(cases, verdicts, me, couple))
    }

    @Test fun pendingCaseDoesNotIncreaseTheTally() {
        val decided = kase(CaseStatus.closed)
        val before = UsRecord.tally(listOf(decided), listOf(verdict(decided, partner)), me, couple)
        val pending = kase(CaseStatus.awaitingVerdict)
        val after = UsRecord.tally(listOf(decided, pending), listOf(verdict(decided, partner)), me, couple)
        assertEquals(before, after)
        assertEquals(UsRecord(mine = 0, partners = 1, ties = 0), after)
    }

    @Test fun tiesCountAsTiesNotWins() {
        val a = kase(CaseStatus.closed)
        val b = kase(CaseStatus.verdict)
        val record = UsRecord.tally(listOf(a, b), listOf(verdict(a, null, tie = true), verdict(b, null, tie = true)), me, couple)
        assertEquals(UsRecord(mine = 0, partners = 0, ties = 2), record)
        assertEquals("2 cases heard", record.lead)
    }

    @Test fun guiltyPleaAndDefaultGoToThePlaintiff() {
        val guilty = kase(CaseStatus.closedGuilty, plaintiff = me)
        val defaulted = kase(CaseStatus.closedDefault, plaintiff = partner)
        assertEquals(UsRecord(mine = 1, partners = 1, ties = 0), UsRecord.tally(listOf(guilty, defaulted), emptyList(), me, couple))
    }

    @Test fun decidedStatusWithoutADecisionDoesNotCount() {
        assertEquals(0, UsRecord.tally(listOf(kase(CaseStatus.closed)), emptyList(), me, couple).heard)
    }

    @Test fun latestVerdictWins() {
        val c = kase(CaseStatus.closed)
        val old = verdict(c, partner, at = Instant.now().minusSeconds(100))
        val new = verdict(c, me, kind = VerdictKind.appeal, at = Instant.now())
        assertEquals(UsRecord(mine = 1, partners = 0, ties = 0), UsRecord.tally(listOf(c), listOf(new, old), me, couple))
    }

    @Test fun historyWithAnExStaysOut() {
        val ours = kase(CaseStatus.closed)
        val theirs = kase(CaseStatus.closed, coupleId = exCouple)
        assertEquals(1, UsRecord.tally(listOf(ours, theirs), listOf(verdict(ours, me), verdict(theirs, me)), me, couple).heard)
    }

    @Test fun noUserMeansNoRecord() {
        val c = kase(CaseStatus.closed)
        assertEquals(UsRecord(), UsRecord.tally(listOf(c), listOf(verdict(c, me)), null, couple))
    }

    @Test fun casesHeardPluralisation() {
        assertEquals("No cases heard yet", UsRecord().lead)
        assertEquals("1 case heard", UsRecord(mine = 1).lead)
        assertEquals("1 case heard", UsRecord(ties = 1).lead)
        assertEquals("8 cases heard", UsRecord(mine = 2, partners = 4, ties = 2).lead)
    }
}

class UsPairingTests {
    private val gb = Locale.UK
    private val utc = ZoneOffset.UTC
    private val la = ZoneId.of("America/Los_Angeles")

    private fun date(y: Int, m: Int, d: Int, hour: Int = 0, zone: ZoneId): Instant = LocalDateTime.of(y, m, d, hour, 0).atZone(zone).toInstant()
    private fun couple(together: Instant? = null, linked: Instant? = null) =
        Couple(id = UUID.randomUUID(), inviteCode = "KX7P2Q", inviteExpiresAt = Instant.now(), linkedAt = linked, togetherSince = together)

    @Test fun prefersTogetherSince() {
        val together = date(2024, 2, 14, zone = utc)
        val linked = date(2026, 5, 29, hour = 12, zone = utc)
        assertEquals(together, UsPairing.since(couple(together, linked)))
        assertEquals("In court together since 14 February 2024", UsPairing.line(couple(together, linked), gb, utc))
    }

    @Test fun fallsBackToLinkedAt() {
        val linked = date(2026, 5, 29, hour = 12, zone = utc)
        assertEquals(linked, UsPairing.since(couple(linked = linked)))
        assertEquals("In court together since 29 May 2026", UsPairing.line(couple(linked = linked), gb, utc))
    }

    @Test fun omittedWithoutADate() {
        assertNull(UsPairing.line(couple()))
        assertNull(UsPairing.line(null))
    }

    @Test fun dateColumnNeverSlipsADayWestOfGreenwich() {
        // `together_since` decodes as UTC midnight; Los Angeles must still read 29 May.
        val together = date(2026, 5, 29, zone = utc)
        assertEquals("In court together since 29 May 2026", UsPairing.line(couple(together = together), gb, la))
    }

    @Test fun linkedAtUsesTheDeviceZone() {
        // 03:00 UTC on 30 May is still 29 May in Los Angeles.
        val linked = date(2026, 5, 30, hour = 3, zone = utc)
        assertEquals("In court together since 29 May 2026", UsPairing.line(couple(linked = linked), gb, la))
    }

    @Test fun formatIsDayMonthYear() {
        assertEquals("1 September 2026", UsPairing.format(date(2026, 9, 1, hour = 12, zone = utc), Locale.US, utc))
    }
}

class UsPartnershipTests {
    @get:Rule val main = MainDispatcherRule()
    private val partnerProfile = Profile(id = partner, displayName = "Alex")
    private val linked = Couple(id = couple, inviteCode = "KX7P2Q", inviteExpiresAt = Instant.now(), linkedAt = Instant.now())
    private val unlinked = Couple(id = couple, inviteCode = "KX7P2Q", inviteExpiresAt = Instant.now())

    @Test fun pairedNeedsPartnerAndLink() {
        assertEquals(UsPartnership.paired, UsPartnership.make(partnerProfile, linked))
    }

    @Test fun noPartnerIsTheInviteState() {
        assertEquals(UsPartnership.invite, UsPartnership.make(null, unlinked))
        assertEquals(UsPartnership.invite, UsPartnership.make(null, null))
        assertEquals(UsPartnership.invite, UsPartnership.make(partnerProfile, unlinked))
        assertEquals(UsPartnership.invite, UsPartnership.make(null, linked))
    }

    @Test fun soloFixtureShowsInviteAndNoRecord() {
        val store = PreviewData.soloStore()
        assertEquals(UsPartnership.invite, UsPartnership.make(store.partner, store.couple))
        assertEquals("No cases heard yet", UsRecord.tally(store.cases, store.verdicts, store.me?.id, store.couple?.id).lead)
    }

    @Test fun onlyWigsworthPresidesToday() {
        assertTrue(UsJudges.isAvailable(JudgePersona.wigsworth))
        assertFalse(UsJudges.isAvailable(JudgePersona.blunt) || UsJudges.isAvailable(JudgePersona.sunny) || UsJudges.isAvailable(JudgePersona.chaos))
        assertEquals(JudgePersona.wigsworth, UsJudges.presiding(null))
        assertEquals(listOf(JudgePersona.wigsworth, JudgePersona.blunt, JudgePersona.sunny, JudgePersona.chaos), UsJudges.ordered(JudgePersona.wigsworth))
        assertEquals(JudgePersona.sunny, UsJudges.ordered(JudgePersona.sunny).first())
        assertEquals(JudgePersona.entries.size, UsJudges.ordered(JudgePersona.sunny).size)
    }
}
