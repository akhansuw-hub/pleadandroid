// Port of ArgueWin/Features/Us/UsSummary.swift.
//
// Pure rules behind the Us tab (CONTRACTS-v2 amendment af): the partnership state, the pairing line and the
// record. No store, no Compose: UsTests covers every branch.
package app.plead.android.features.us

import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Couple
import app.plead.android.models.JudgePersona
import app.plead.android.models.Profile
import app.plead.android.models.Verdict
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** Which top card Us shows: the pair (portraits, "Sam & Alex", pairing line) or the invite state. */
enum class UsPartnership {
    paired,

    /** No linked partner yet: a clear invite, never an empty pair card. */
    invite;

    companion object {
        /** Mirrors `CaseStore.isSolo`: a partner profile AND a linked couple make a pair. */
        fun make(partner: Profile?, couple: Couple?): UsPartnership =
            if (partner != null && couple?.isLinked == true) paired else invite
    }
}

/** "In court together since 29 May 2026". */
object UsPairing {
    /** `Couple.togetherSince` (the date they chose in onboarding), else `linkedAt`; null when neither exists. */
    fun since(couple: Couple?): Instant? = couple?.togetherSince ?: couple?.linkedAt

    /** Null when there is no date: the line is omitted, never faked. */
    fun line(couple: Couple?, locale: Locale = Locale.getDefault(), timeZone: ZoneId = ZoneId.systemDefault()): String? {
        if (couple == null) return null
        val date = since(couple) ?: return null
        // `together_since` is a Postgres `date`, decoded as UTC midnight: read it in UTC so it never shows as the
        // previous day west of Greenwich. A local date (demo / just picked) or `linkedAt` uses the device zone.
        val isDateOnly = couple.togetherSince != null && isUTCMidnight(date)
        return "In court together since ${format(date, locale, if (isDateOnly) ZoneOffset.UTC else timeZone)}"
    }

    /** "d MMMM yyyy" in the given locale ("29 May 2026"). */
    fun format(date: Instant, locale: Locale = Locale.getDefault(), timeZone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("d MMMM yyyy", locale).format(date.atZone(timeZone))

    private fun isUTCMidnight(date: Instant): Boolean {
        val t = date.atZone(ZoneOffset.UTC)
        return t.hour == 0 && t.minute == 0 && t.second == 0 && t.nano == 0
    }
}

/** The couple's record: decided cases only. */
data class UsRecord(val mine: Int = 0, val partners: Int = 0, val ties: Int = 0) {
    val heard: Int get() = mine + partners + ties

    /** "No cases heard yet" / "1 case heard" / "8 cases heard". */
    val lead: String
        get() = when (heard) {
            0 -> "No cases heard yet"
            1 -> "1 case heard"
            else -> "$heard cases heard"
        }

    companion object {
        /**
         * The record rule. A case counts only when the court has decided it:
         * - it belongs to the current couple (history with an ex stays out);
         * - its status is `verdict`, `closed`, `closed_guilty` or `closed_default` (a verdict has been delivered);
         * - it has a decision: its latest verdict row is a tie (→ ties) or names a winner (→ me / partner), or, with no
         *   verdict row loaded, a guilty plea / default judgement (both verdicts for the plaintiff).
         * Never counted: every open or pending status (drafting … awaiting verdict, appeal), `closed_settled` (settled out
         * of court is neither a win nor a loss, amendment n), `mistrial` (stopped: withdrawn, unlinked, safety valve),
         * and a decided status with no decision on record.
         */
        fun tally(cases: List<Case>, verdicts: List<Verdict>, me: UUID?, coupleId: UUID?): UsRecord {
            var mine = 0
            var partners = 0
            var ties = 0
            if (me == null) return UsRecord()
            for (kase in cases) {
                if (!decided(kase.status) || !(coupleId?.let { it == kase.coupleId } ?: true)) continue
                val latest = verdicts.filter { it.caseId == kase.id }.maxByOrNull { it.createdAt }
                if (latest != null) {
                    if (latest.isTie) { ties += 1; continue }
                    val winner = latest.winnerId
                    if (winner != null) {
                        if (winner == me) mine += 1 else partners += 1
                        continue
                    }
                }
                if (kase.status == CaseStatus.closedGuilty || kase.status == CaseStatus.closedDefault) {
                    if (kase.plaintiffId == me) mine += 1 else partners += 1
                }
            }
            return UsRecord(mine, partners, ties)
        }

        /** Statuses whose verdict has been delivered. */
        fun decided(status: CaseStatus): Boolean = when (status) {
            CaseStatus.verdict, CaseStatus.closed, CaseStatus.closedGuilty, CaseStatus.closedDefault -> true
            else -> false
        }
    }
}

/** The presiding-judge list. */
object UsJudges {
    /** Personas that can preside today. Only Wigsworth sits; the others stay "Coming soon" (CONTRACTS-v2 §paid app). */
    val available: Set<JudgePersona> = setOf(JudgePersona.wigsworth)

    fun isAvailable(persona: JudgePersona): Boolean = available.contains(persona)

    /** The couple's persona, or Wigsworth when there is no couple yet. */
    fun presiding(couple: Couple?): JudgePersona = couple?.judgePersona ?: JudgePersona.wigsworth

    /** The selected persona first, then the rest in enum order. */
    fun ordered(presiding: JudgePersona): List<JudgePersona> = listOf(presiding) + JudgePersona.entries.filter { it != presiding }
}
