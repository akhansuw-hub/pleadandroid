// The CaseFlow-dependent half of ArgueWin/DesignSystem/CourtFile.swift (owed by wave 2b, landed with wave 3d):
// `CaseFileStatus.make(for:me:…)` (both overloads, the status matrix in designsystem/CourtFile.kt's header) and the
// `CaseStore` case-file extension (`caseFileStatus(for:now:)`, `caseFileParties(for:)`, `caseFileAction(for:)`).
// A new file so the design system stays free of services; the names are the Swift ones.
package app.plead.android.services

import app.plead.android.designsystem.CaseFileStatus
import app.plead.android.designsystem.CaseFileStatus.Kind as K
import app.plead.android.designsystem.Kase
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Judgement
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.TrialPhase
import java.time.Instant
import java.util.UUID

/**
 * Derived from the case as `me` sees it at `now` ("your partner" for the other party's name, court flow only).
 * Prefer [CaseStore.caseFileStatus], which adds the partner's name, judgement and settlement.
 */
fun CaseFileStatus.Companion.make(kase: Kase, me: UUID?, now: Instant = Instant.now()): CaseFileStatus =
    make(kase, me, partnerName = null, now = now)

/**
 * The full derivation: the partner's name, and the judgement / settlement layered over the court flow
 * exactly as `Case.nextAction(userId, judgement, settlement, latestOffer)` does.
 */
fun CaseFileStatus.Companion.make(
    kase: Kase,
    me: UUID?,
    partnerName: String?,
    judgement: Judgement? = null,
    settlement: Settlement? = null,
    latestOffer: SettlementOffer? = null,
    now: Instant = Instant.now(),
): CaseFileStatus {
    val partner = partnerName?.takeIf { it.isNotEmpty() } ?: "your partner"
    val Partner = partner.take(1).uppercase() + partner.drop(1)

    when (kase.status) {
        CaseStatus.closedSettled -> return CaseFileStatus(K.settled, "Settled outside court", "View the settlement", null)
        CaseStatus.mistrial -> return CaseFileStatus(K.stopped, "Trial stopped", "View the record", null)
        CaseStatus.closed, CaseStatus.closedGuilty, CaseStatus.closedDefault ->
            return CaseFileStatus(K.closed, "Verdict delivered", "View the ruling", null)
        else -> Unit
    }

    fun needs(step: String, deadline: Instant?): CaseFileStatus {
        val left = deadline?.let { remaining(it, now) }
        return CaseFileStatus(K.needsYou, left?.let { "Needs you · $it left" } ?: "Needs you", step, deadline)
    }
    fun waiting(step: String, deadline: Instant? = null, message: String? = null): CaseFileStatus =
        CaseFileStatus(K.waiting, message ?: "Waiting for $partner", step, deadline)
    fun deliberating(): CaseFileStatus {
        val at = kase.scheduledReadingAt?.takeIf { it.isAfter(now) }
        val left = at?.let { remaining(it, now) }
        return CaseFileStatus(K.deliberating, "Judge is deliberating", left?.let { "Awaiting verdict · due in $it" } ?: "Awaiting verdict", at)
    }

    // Not a party (or signed out): a neutral reading of the stage.
    val myRole = me?.let { kase.role(it) }
    if (me == null || myRole == null) {
        return when (kase.status) {
            CaseStatus.scheduling -> CaseFileStatus(K.scheduling, "Scheduling", "Agree the trial time", kase.deadlineAt)
            CaseStatus.deliberating, CaseStatus.awaitingVerdict -> deliberating()
            else -> CaseFileStatus(K.waiting, kase.statusTitle, "", kase.deadlineAt)
        }
    }

    return when (kase.nextAction(me, judgement, settlement, latestOffer, now)) {
        CaseAction.enterPlea -> needs("Enter your plea", kase.deadlineAt)
        CaseAction.requestDefault -> needs("Request default judgment", kase.deadlineAt)
        CaseAction.awaitPlea -> waiting("$Partner to enter a plea", kase.deadlineAt)
        CaseAction.fileDefence -> needs("File your defence", kase.deadlineAt)
        CaseAction.awaitDefence -> waiting("$Partner to file their defence", kase.deadlineAt)
        CaseAction.respondToTime -> needs("Agree the trial time", kase.deadlineAt)
        CaseAction.awaitTime -> CaseFileStatus(K.scheduling, "Scheduling", "Waiting for $partner to agree", kase.deadlineAt)
        CaseAction.yourTurnInCourt -> {
            val phase = kase.phase
            val step = when {
                phase == TrialPhase.crossExamination -> "Answer the judge"
                phase != null && phase.speakingSide == myRole.other -> "Object or let it stand"
                else -> "Your turn in court"
            }
            needs(step, kase.deadlineAt)
        }
        CaseAction.watchCourt -> {
            val owner = kase.phaseTurnOwner
            val phase = kase.phase
            if (owner == null || owner == myRole || phase == null) {
                waiting("The judge has the floor", kase.deadlineAt, message = "In court")
            } else {
                val activity = when (phase) {
                    TrialPhase.plaintiffOpening, TrialPhase.defendantOpening -> "is giving their opening"
                    TrialPhase.plaintiffClosing, TrialPhase.defendantClosing -> "is giving their closing"
                    TrialPhase.crossExamination -> "is answering the judge"
                    TrialPhase.plaintiffExhibits, TrialPhase.defendantExhibits ->
                        if (phase.speakingSide == owner) "is presenting evidence" else "is considering an objection"
                }
                waiting("$Partner $activity", kase.deadlineAt)
            }
        }
        CaseAction.awaitVerdict -> deliberating()
        CaseAction.hearVerdict -> needs("Hear the verdict", null)
        CaseAction.chooseJudgement -> needs("Choose the judgement", null)
        CaseAction.acceptJudgement -> needs("Accept the judgement", null)
        CaseAction.awaitJudgementChoice ->
            if (judgement?.isCourtChosen == true) {
                waiting("The court is choosing the judgement", message = "Judgement pending")
            } else {
                waiting("$Partner is choosing the judgement")
            }
        CaseAction.markJudgementServed -> waiting("Mark it served once it's done", judgement?.dueAt, message = "Judgement outstanding")
        CaseAction.respondToSettlement -> needs("Respond to the settlement offer", null)
        CaseAction.awaitSettlement -> waiting("$Partner to answer your settlement offer")
        // Unreachable for open cases (closed states return above); keep a truthful fallback.
        CaseAction.markSettlementFulfilled, CaseAction.viewRecord -> CaseFileStatus(K.waiting, kase.statusTitle, "", null)
    }
}

// MARK: - CaseStore

/** The case-file status for the signed-in user, with the partner's name, judgement and settlement. */
fun CaseStore.caseFileStatus(kase: Case, now: Instant = Instant.now()): CaseFileStatus {
    val other = opponentId(kase)?.let { name(it, fallback = "your partner") }
    return CaseFileStatus.make(
        kase, me?.id, partnerName = other, judgement = judgement(kase.id),
        settlement = settlement(kase.id), latestOffer = latestOffer(kase.id), now = now,
    )
}

/** "Sam v. Alex" (plaintiff first). */
fun CaseStore.caseFileParties(kase: Case): String =
    "${name(kase.plaintiffId, fallback = "Plaintiff")} v. ${name(kase.defendantId, fallback = "Defendant")}"

/**
 * The direct action a case file offers (null when the card only opens the case): the user's next step
 * when it needs them and `AppRouter.open(action, kase)` has a flow for it.
 */
fun CaseStore.caseFileAction(kase: Case): CaseAction? {
    if (!kase.status.isOpen || me == null) return null
    return when (val action = nextAction(kase)) {
        CaseAction.enterPlea, CaseAction.fileDefence, CaseAction.respondToTime, CaseAction.yourTurnInCourt, CaseAction.hearVerdict,
        CaseAction.chooseJudgement, CaseAction.acceptJudgement, CaseAction.respondToSettlement -> action
        else -> null
    }
}
