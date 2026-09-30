// Port of ArgueWin/Services/CaseFlow.swift.
@file:Suppress("EnumEntryName")

package app.plead.android.services

import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementStatus
import app.plead.android.models.Role
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementStatus
import app.plead.android.models.TrialPhase
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

/** What the current user should do next on a case, derived purely from the row. */
enum class CaseAction {
    enterPlea,            // defendant, summoned
    awaitPlea,            // plaintiff, summoned
    requestDefault,       // plaintiff, summoned, deadline passed
    fileDefence,          // defendant, defence
    awaitDefence,         // plaintiff, defence
    respondToTime,        // whoever didn't propose last, scheduling
    awaitTime,            // whoever proposed last, scheduling
    yourTurnInCourt,      // trial, my turn
    watchCourt,           // trial, their turn
    awaitVerdict,         // deliberating / awaiting_verdict
    hearVerdict,          // verdict
    viewRecord,           // closed states
    // Winner-selected judgement (amendment j), layered over the revealed states by `nextAction(for:judgement:)`.
    chooseJudgement,      // chooser, pending_selection
    awaitJudgementChoice, // the other party, pending_selection (info only)
    acceptJudgement,      // the other party, delivered
    markJudgementServed,  // either party, accepted (and the chooser while delivered)
    // Settle Outside Court (amendment n), layered over the court flow by `nextAction(for:judgement:settlement:latestOffer:)`.
    respondToSettlement,      // an offer awaits my response (the court waits)
    awaitSettlement,          // I sent the current offer; waiting for my partner
    markSettlementFulfilled;  // settled out of court, agreement outstanding

    val title: String
        get() = when (this) {
            enterPlea -> "Enter your plea"
            awaitPlea -> "Waiting for a plea"
            requestDefault -> "Request default judgment"
            fileDefence -> "File your defence"
            awaitDefence -> "Waiting for the defence"
            respondToTime -> "Agree the trial time"
            awaitTime -> "View trial time"
            yourTurnInCourt -> "Your turn in court"
            watchCourt -> "Go to court"
            awaitVerdict -> "Watch the deliberation"
            hearVerdict -> "Hear the verdict"
            viewRecord -> "View the record"
            chooseJudgement -> "Choose the court's judgement"
            awaitJudgementChoice -> "The prevailing party is choosing the court's judgement."
            acceptJudgement -> "Accept the judgement"
            markJudgementServed -> "Mark as served"
            respondToSettlement -> "Respond to the settlement offer"
            awaitSettlement -> "Settlement offer sent · waiting"
            markSettlementFulfilled -> "Mark as fulfilled"
        }

    /** Settlement actions (the response sheet / fulfilment card carry them). */
    val isSettlement: Boolean
        get() = this == respondToSettlement || this == awaitSettlement || this == markSettlementFulfilled

    /** Judgement actions are shown by `JudgementStatusCard`; the record's own action button skips them. */
    val isJudgement: Boolean
        get() = this == chooseJudgement || this == awaitJudgementChoice || this == acceptJudgement || this == markJudgementServed

    /** One short line for the Home card ("Your turn", "Waiting for Alex's plea"). */
    fun stateLine(partner: String): String = when (this) {
        enterPlea -> "Your plea is due"
        awaitPlea -> "Waiting for $partner's plea"
        requestDefault -> "The plea deadline has passed"
        fileDefence -> "Your defence is due"
        awaitDefence -> "Waiting for $partner's defence"
        respondToTime -> "Agree the trial time"
        awaitTime -> "Waiting for $partner to agree a time"
        yourTurnInCourt -> "Your turn"
        watchCourt -> "$partner's turn"
        awaitVerdict -> "Verdict in"
        hearVerdict -> "The verdict is in"
        viewRecord -> "Closed"
        chooseJudgement -> "The court awaits your judgement"
        awaitJudgementChoice -> "The prevailing party is choosing the judgement"
        acceptJudgement -> "The court has delivered its judgement"
        markJudgementServed -> "Judgement outstanding"
        respondToSettlement -> "A settlement offer awaits you"
        awaitSettlement -> "Waiting for $partner to answer your offer"
        markSettlementFulfilled -> "Settlement agreement outstanding"
    }

    /** True when the button should look primary (the user can act now). */
    val isActionable: Boolean
        get() = when (this) {
            enterPlea, requestDefault, fileDefence, respondToTime, yourTurnInCourt, hearVerdict,
            chooseJudgement, acceptJudgement, markJudgementServed,
            respondToSettlement, markSettlementFulfilled -> true
            else -> false
        }
}

// MARK: - Case extensions

/**
 * Who made the latest trial-time proposal: the defendant files the first one with the
 * defence; the plaintiff may counter once (proposal_count >= 1).
 */
val Case.lastProposer: Role get() = if (proposalCount >= 1) Role.plaintiff else Role.defendant

/** Mirrors the backend: whoever did NOT make the latest proposal may accept. */
fun Case.canAcceptTime(`as`: Role?): Boolean {
    if (status != CaseStatus.scheduling || `as` == null) return false
    return `as` != lastProposer
}

/** Only the plaintiff counter-proposes, and only once. */
fun Case.canCounterPropose(`as`: Role?): Boolean =
    status == CaseStatus.scheduling && `as` == Role.plaintiff && proposalCount < 1

fun Case.nextAction(userId: UUID, now: Instant = Instant.now()): CaseAction {
    val role = role(userId)
    return when (status) {
        CaseStatus.drafting, CaseStatus.summoned -> {
            if (role == Role.defendant) return CaseAction.enterPlea
            val d = deadlineAt
            if (d != null && d.isBefore(now)) CaseAction.requestDefault else CaseAction.awaitPlea
        }
        CaseStatus.defence -> if (role == Role.defendant) CaseAction.fileDefence else CaseAction.awaitDefence
        CaseStatus.scheduling -> if (canAcceptTime(role)) CaseAction.respondToTime else CaseAction.awaitTime
        CaseStatus.trial -> if (phaseTurnOwner != null && phaseTurnOwner == role) CaseAction.yourTurnInCourt else CaseAction.watchCourt
        CaseStatus.deliberating, CaseStatus.awaitingVerdict -> CaseAction.awaitVerdict
        CaseStatus.verdict, CaseStatus.appeal -> CaseAction.hearVerdict
        CaseStatus.closed, CaseStatus.closedGuilty, CaseStatus.closedDefault, CaseStatus.closedSettled, CaseStatus.mistrial -> CaseAction.viewRecord
    }
}

/**
 * The full next step: settlement (amendment n) first, then the court judgement, then the court flow.
 * - A pending settlement replaces the court action: the receiver responds, the proposer waits.
 * - A settled case has no court action; an accepted (unfulfilled) agreement asks to be marked fulfilled.
 */
fun Case.nextAction(
    userId: UUID,
    judgement: Judgement?,
    settlement: Settlement?,
    latestOffer: SettlementOffer?,
    now: Instant = Instant.now(),
): CaseAction {
    if (settlement != null && settlement.caseId == id && role(userId) != null) {
        if (settlement.isPending && status.isOpen) {
            return if (SettlementRules.awaitsResponse(userId, settlement, latestOffer)) CaseAction.respondToSettlement else CaseAction.awaitSettlement
        }
        if (status == CaseStatus.closedSettled) {
            return if (settlement.status == SettlementStatus.accepted) CaseAction.markSettlementFulfilled else CaseAction.viewRecord
        }
    }
    if (status == CaseStatus.closedSettled) return CaseAction.viewRecord
    return nextAction(userId, judgement, now)
}

/**
 * The case action with the court judgement layered on top: once the verdict is revealed, a live
 * judgement (pending / delivered / accepted) drives the next step; served / declined fall back.
 */
fun Case.nextAction(userId: UUID, judgement: Judgement?, now: Instant = Instant.now()): CaseAction {
    val base = nextAction(userId, now)
    if (judgement == null || judgement.caseId != id || !isRevealed || role(userId) == null) return base
    // Tie (amendment l): the court chose; nobody chooses or accepts, either partner marks it served.
    if (judgement.isCourtChosen) {
        return when (judgement.status) {
            JudgementStatus.pendingSelection -> CaseAction.awaitJudgementChoice
            JudgementStatus.delivered, JudgementStatus.accepted -> CaseAction.markJudgementServed
            JudgementStatus.served, JudgementStatus.declined -> base
        }
    }
    val chooser = judgement.chooserId == userId
    return when (judgement.status) {
        JudgementStatus.pendingSelection -> if (chooser) CaseAction.chooseJudgement else CaseAction.awaitJudgementChoice
        JudgementStatus.delivered -> if (chooser) CaseAction.markJudgementServed else CaseAction.acceptJudgement
        JudgementStatus.accepted -> CaseAction.markJudgementServed
        JudgementStatus.served, JudgementStatus.declined -> base
    }
}

/** Swift's `(label: String, date: Date)` countdown tuple. */
data class CountdownTarget(val label: String, val date: Instant)

/** The moment the card's countdown points at, with a label. */
val Case.countdownTarget: CountdownTarget?
    get() = when (status) {
        CaseStatus.summoned -> deadlineAt?.let { CountdownTarget("Plea due", it) }
        CaseStatus.defence -> deadlineAt?.let { CountdownTarget("Defence due", it) }
        CaseStatus.scheduling -> (proposedTrialAt ?: deadlineAt)?.let { CountdownTarget("Proposed trial", it) }
        CaseStatus.trial -> deadlineAt?.let { CountdownTarget("Turn ends", it) }
        CaseStatus.deliberating, CaseStatus.awaitingVerdict -> (trialAt ?: verdictAt)?.let { CountdownTarget("Verdict in", it) }
        else -> null
    }

/** When the ruling is due to be read while deliberating (`trial_at`, else `verdict_at`). */
val Case.scheduledReadingAt: Instant? get() = if (status.isDeliberating) (trialAt ?: verdictAt) else null

/**
 * The reading time has passed but no verdict yet: during an outage a case may stay `deliberating`
 * for up to 6 h past `trial_at`. The UI then says "The court is still deliberating" instead of
 * a countdown.
 */
fun Case.isDeliberationOverdue(now: Instant = Instant.now()): Boolean {
    val at = scheduledReadingAt ?: return false
    return !at.isAfter(now)
}

/** Swift `Case.stillDeliberatingLine`: copy shown once `isDeliberationOverdue`. */
object CaseCopy {
    const val stillDeliberatingLine = "The court is still deliberating"
}

/** "Case #021" — zero-padded docket number (spec §9). */
val Case.docketNumber: String get() = "Case #%03d".format(caseNumber)

/** "Case #021 · The Instagram Like Incident" */
val Case.docketTitle: String get() = "$docketNumber · $title"

/** Revealed = verdict and juror reviews are readable (RLS opens them from `verdict` on). */
val Case.isRevealed: Boolean
    get() = when (status) {
        CaseStatus.verdict, CaseStatus.appeal, CaseStatus.closed, CaseStatus.closedGuilty, CaseStatus.closedDefault -> true
        else -> false
    }

/** The four deliberation status lines lit so far (spec §4 theatre labels, 1...4). */
val Case.deliberationStepsDone: Int
    get() = if (status == CaseStatus.awaitingVerdict) 4 else max(0, min(4, panelProgress))

val Case.statusTitle: String
    get() = when (status) {
        CaseStatus.drafting -> "Drafting"
        CaseStatus.summoned -> "Summoned"
        CaseStatus.defence -> "Defence"
        CaseStatus.scheduling -> "Scheduling"
        CaseStatus.trial -> "In trial"
        CaseStatus.deliberating -> "Deliberating"
        CaseStatus.awaitingVerdict -> "Awaiting verdict"
        CaseStatus.verdict -> "Verdict"
        CaseStatus.appeal -> "Appeal"
        CaseStatus.closed -> "Closed"
        CaseStatus.closedGuilty -> "Guilty plea"
        CaseStatus.closedDefault -> "Default judgment"
        CaseStatus.closedSettled -> "Settled out of court"
        CaseStatus.mistrial -> "Mistrial"
    }

// MARK: - Settle Outside Court rules (amendment n; the backend is authoritative)

object SettlementRules {
    /** Court states a settlement can be proposed from. */
    val proposableStatuses: Set<CaseStatus> = setOf(CaseStatus.summoned, CaseStatus.defence, CaseStatus.scheduling, CaseStatus.trial)

    /**
     * Either partner, while the case is summoned / in defence / scheduling / in trial before closing
     * statements begin, and only when no settlement is pending.
     */
    fun canPropose(kase: Case, userId: UUID?, settlement: Settlement?): Boolean {
        if (userId == null || kase.role(userId) == null || !proposableStatuses.contains(kase.status)) return false
        val phase = kase.phase
        if (kase.status == CaseStatus.trial && phase != null && !phaseAllowsSettlement(phase)) return false
        if (kase.settlementId != null) return false
        if (settlement != null && settlement.caseId == kase.id && settlement.isPending) return false
        return true
    }

    /** Closing statements end the settlement window. */
    fun phaseAllowsSettlement(phase: TrialPhase): Boolean {
        val all = TrialPhase.entries
        val i = all.indexOf(phase)
        val cut = all.indexOf(TrialPhase.plaintiffClosing)
        if (i < 0 || cut < 0) return false
        return i < cut
    }

    /**
     * Who proposed the current round: the latest offer's author, else derived from the round
     * (odd rounds = the initiator, even rounds = the other party; roles swap on every counter).
     */
    fun currentProposer(settlement: Settlement, latestOffer: SettlementOffer?, kase: Case? = null): UUID? {
        if (latestOffer != null && latestOffer.settlementId == settlement.id) return latestOffer.proposedBy
        if (settlement.currentRound % 2 == 1) return settlement.initiatedBy
        val k = kase ?: return null
        return if (settlement.initiatedBy == k.plaintiffId) k.defendantId else k.plaintiffId
    }

    /** An offer awaits `userId`'s response: the settlement is pending and its current offer is not mine. */
    fun awaitsResponse(userId: UUID?, settlement: Settlement?, latestOffer: SettlementOffer?): Boolean {
        if (userId == null || settlement == null || !settlement.isPending) return false
        if (latestOffer != null && latestOffer.settlementId == settlement.id) return latestOffer.proposedBy != userId
        // No offer loaded yet: odd rounds were proposed by the initiator.
        val initiatorProposed = settlement.currentRound % 2 == 1
        return if (initiatorProposed) settlement.initiatedBy != userId else settlement.initiatedBy == userId
    }

    /** A counter is allowed only below the 3-round cap. */
    fun canCounter(settlement: Settlement): Boolean = settlement.isPending && settlement.currentRound < Settlement.maxRounds

    /** `summons` or `trial` (the backend's `entry_point`; defence / scheduling count as trial). */
    fun entryPoint(status: CaseStatus): String = if (status == CaseStatus.summoned) "summons" else "trial"

    /** "Round 2 of 3". */
    fun roundLine(round: Int): String = "Round ${min(max(round, 1), Settlement.maxRounds)} of ${Settlement.maxRounds}"

    /** "within 3 days" / "within 1 day". */
    fun dueLine(days: Int): String = if (days == 1) "within 1 day" else "within $days days"

    const val bodyLimit = 200
    val dueDaysRange: IntRange = 1..7

    /** Custom terms default to a week (the backend's default too). */
    const val customDefaultDueDays = 7

    /** Neutral one-line context for the Settlement Room, derived locally (no AI call). */
    fun contextLine(kase: Case): String {
        var title = kase.title.trim()
        while (title.isNotEmpty() && ".!?".contains(title.last())) title = title.dropLast(1)
        if (title.startsWith("The ")) title = "the " + title.drop(4)
        if (title.isEmpty()) return "A disagreement worth settling."
        return "A disagreement about $title."
    }

    /**
     * An inline hint for terms the server's settlement denylist will likely refuse. Advisory only: the
     * server's denylist + safety classifier are authoritative, so this never blocks sending.
     */
    fun localSafetyIssue(text: String): String? {
        val words = text.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val joined = " " + words.joinToString(" ") + " "
        val money = listOf("money", "cash", "transfer", "venmo", "paypal", "debt", "fined", "loan", "gamble", "gambling")
        val access = listOf("password", "passwords", "passcode", "pin", "location", "track", "tracking", "spy", "unlock", "login")
        val harm = listOf("hit", "hurt", "punch", "slap", "kill", "threat", "humiliate", "sex", "sexual", "naked", "nude")
        val isolation = listOf("never see", "stop seeing", "block your", "ban you")
        if (words.any { it in money } || text.contains("£") || text.contains("$")) return "Keep money out of it. Settlements are small, doable actions."
        if (words.any { it in access }) return "No passwords, phones or tracking. Keep it kind and doable."
        if (words.any { it in harm }) return "That can't be part of a settlement. Keep it kind and doable."
        if (isolation.any { joined.contains(" $it ") }) return "No limits on friends or family. Keep it kind and doable."
        return null
    }
}

enum class CaseOutcome(val rawValue: String) {
    won("won"), lost("lost"), tied("tied");

    val id: String get() = rawValue
    val title: String get() = rawValue.replaceFirstChar { it.uppercase() }
}

data class WinTally(var mine: Int = 0, var partners: Int = 0, var ties: Int = 0)

/** Trial-time bounds from CONTRACTS.md: now+1h ... now+7d. */
object TrialWindow {
    fun range(now: Instant = Instant.now()): ClosedRange<Instant> = now.plusSeconds(3600)..now.plusSeconds(7 * 24 * 3600)

    fun defaultProposal(now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Instant {
        // Tomorrow 20:00 local, clamped into range.
        val tomorrow = ZonedDateTime.ofInstant(now, zone).plusDays(1)
        val eight = tomorrow.withHour(20).withMinute(0).withSecond(0).withNano(0).toInstant()
        val r = range(now)
        val low = r.start.plusSeconds(60)
        val clamped = if (eight.isBefore(low)) low else eight
        return if (clamped.isAfter(r.endInclusive)) r.endInclusive else clamped
    }

    fun isValid(date: Instant, now: Instant = Instant.now()): Boolean = date in range(now)
}
