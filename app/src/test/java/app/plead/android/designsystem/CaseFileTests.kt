// Port of the CourtFile.swift half of ArgueWinTests/CaseFileTests.swift (CONTRACTS-v2 amendment af): the
// countdown, the card copy and the closed docket order. The status-derivation tests (`CaseFileStatusTests`) and
// the tests that need PreviewData / CaseStore / CasesView (`countsFromData`, `demoDocketLeadsWithAnAction`,
// `actionFirstByNearestDeadlineThenWaiting`, `aWaitingCaseNeverDisplacesAnAction`) come with `CaseFileStatus.make`.
package app.plead.android.designsystem

import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val me = UUID.fromString("11111111-1111-1111-1111-111111111111")       // PreviewData.meId
private val partner = UUID.fromString("22222222-2222-2222-2222-222222222222")  // PreviewData.partnerId
// Swift `Date(timeIntervalSinceReferenceDate: 800_000_000)`.
private val now: Instant = Instant.ofEpochSecond(978_307_200L + 800_000_000L)
private const val hour: Long = 3600

private fun kase(n: Int = 1, status: CaseStatus, updated: Instant = now, closedAt: Instant? = null): Case =
    Case(
        id = UUID.randomUUID(), coupleId = UUID.randomUUID(), caseNumber = n, title = "The Spoiler", plaintiffId = partner,
        defendantId = me, status = status, charge = "c", remedyRequested = "r", createdAt = now, updatedAt = updated,
        closedAt = closedAt,
    )

class CaseFileCountdownTests {
    @Test fun compact() {
        assertEquals("1d 5h", CaseFileStatus.remaining(now.plusSeconds(29 * hour + 10), now))
        assertEquals("2d", CaseFileStatus.remaining(now.plusSeconds(48 * hour), now))
        assertEquals("3h 12m", CaseFileStatus.remaining(now.plusSeconds(3 * hour + 12 * 60), now))
        assertEquals("2h", CaseFileStatus.remaining(now.plusSeconds(2 * hour), now))
        assertEquals("42m", CaseFileStatus.remaining(now.plusSeconds(42 * 60 + 30), now))
        assertEquals("1m", CaseFileStatus.remaining(now.plusSeconds(20), now))
        assertNull(CaseFileStatus.remaining(now, now))
        assertNull(CaseFileStatus.remaining(now.minusSeconds(60), now))
    }

    @Test fun spoken() {
        assertEquals("1 day 5 hours", CaseFileStatus.spokenRemaining(now.plusSeconds(29 * hour), now))
        assertEquals("42 minutes", CaseFileStatus.spokenRemaining(now.plusSeconds(42 * 60), now))
        assertEquals("1 hour 1 minute", CaseFileStatus.spokenRemaining(now.plusSeconds(hour + 60), now))
    }
}

class CaseFileCardCopyTests {
    @Test fun caption() {
        assertEquals("CASE #16 · SAM v. ALEX", CaseFileCard.caption(16, "Sam v. Alex"))
    }

    @Test fun voiceOverSummary() {
        val s = CaseFileStatus(CaseFileStatus.Kind.needsYou, "Needs you · 1d 5h left", "File your defence", now.plusSeconds(29 * hour))
        assertEquals(
            "Case 16, The Spoiler, Sam versus Alex, needs you, 1 day 5 hours left, file your defence",
            CaseFileCard.accessibilitySummary(16, "The Spoiler", "Sam v. Alex", s, now = now),
        )
        val d = CaseFileStatus(CaseFileStatus.Kind.deliberating, "Judge is deliberating", "Awaiting verdict", null)
        assertEquals(
            "Case 21, T, Sam versus Alex, Judge is deliberating, awaiting verdict",
            CaseFileCard.accessibilitySummary(21, "T", "Sam v. Alex", d, now = now),
        )
    }
}

class CaseFileDocketTests {
    @Test fun closedNewestFirst() {
        val a = kase(1, CaseStatus.closed, closedAt = now.minusSeconds(3 * 86_400))
        val b = kase(2, CaseStatus.closedSettled, closedAt = now.minusSeconds(86_400))
        val c = kase(3, CaseStatus.mistrial, updated = now.minusSeconds(2 * 86_400))
        assertEquals(listOf(2, 3, 1), CaseFileDocket.sortClosed(listOf(a, b, c)).map { it.caseNumber })
    }

    /** Android-only: the Open order over hand-built statuses (the iOS tests derive them with `make`). */
    @Test fun openOrderByRankThenDeadlineThenRecency() {
        fun row(n: Int, kind: CaseFileStatus.Kind, deadline: Instant?, updated: Instant = now) =
            CaseFileRow(kase(n, CaseStatus.trial, updated = updated), CaseFileStatus(kind, "", "", deadline))
        val rows = listOf(
            row(6, CaseFileStatus.Kind.deliberating, now.plusSeconds(30 * 60)),
            row(4, CaseFileStatus.Kind.waiting, now.plusSeconds(hour)),
            row(3, CaseFileStatus.Kind.needsYou, null),
            row(1, CaseFileStatus.Kind.needsYou, now.plusSeconds(50 * hour)),
            row(5, CaseFileStatus.Kind.scheduling, null),
            row(2, CaseFileStatus.Kind.needsYou, now.plusSeconds(2 * hour)),
            row(7, CaseFileStatus.Kind.scheduling, null, updated = now.plusSeconds(1)),
        )
        assertEquals(listOf(2, 1, 3, 4, 7, 5, 6), CaseFileDocket.sortOpen(rows).map { it.kase.caseNumber })
    }

    @Test fun countsFromCases() {
        val all = listOf(kase(1, CaseStatus.trial), kase(2, CaseStatus.closed), kase(3, CaseStatus.mistrial), kase(4, CaseStatus.summoned))
        assertEquals(CaseFileDocket.Counts(open = 2, closed = 2), CaseFileDocket.counts(all))
        assertEquals(CaseFileDocket.Counts(0, 0), CaseFileDocket.counts(emptyList()))
    }

    @Test fun spokenMessageExpandsTheDot() {
        val waiting = CaseFileStatus(CaseFileStatus.Kind.deliberating, "Awaiting verdict · due in 2h", "", null)
        assertEquals("Awaiting verdict, due in 2h", waiting.spokenMessage(now))
        val needs = CaseFileStatus(CaseFileStatus.Kind.needsYou, "Needs you", "x", null)
        assertEquals("needs you", needs.spokenMessage(now))
    }
}
