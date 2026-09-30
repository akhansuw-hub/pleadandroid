// Port of ArgueWin/Courtroom/CourtroomLogic.swift: pure courtroom rules. No Compose, no side effects: everything here
// is derived from `CourtroomState` so it can be unit-tested.
//
// Swift enums with associated values are sealed classes whose subclasses keep the Swift case names
// (`DockMode.compose(ComposeKind.opening)`); Swift tuples are small data classes (`Stamps`, `PanelVotes`, …).
@file:Suppress("ClassName", "FunctionName")

package app.plead.android.courtroom

import app.plead.android.models.AICall
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Exhibit
import app.plead.android.models.ExhibitType
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementStatus
import app.plead.android.models.ObjectionReason
import app.plead.android.models.ObjectionRuling
import app.plead.android.models.Role
import app.plead.android.models.Settlement
import app.plead.android.models.Speaker
import app.plead.android.models.TrialPhase
import app.plead.android.models.Turn
import app.plead.android.models.Verdict
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** What the dock (turn composer) shows for the current user. */
sealed class DockMode {
    /** Case is not in the courtroom (drafting, closed, …). */
    data object notInSession : DockMode()

    /** The viewer isn't a party to the case. */
    data object spectator : DockMode()

    /** Trial is live but nobody owns the turn: the judge is speaking / ruling. */
    data object judgeHasFloor : DockMode()

    /** It's the partner's turn. */
    data class waiting(val partner: Role, val activity: WaitingActivity) : DockMode()

    /** It's my turn to write. */
    data class compose(val kind: ComposeKind) : DockMode()

    /** The other side just presented an exhibit; I may object once or pass. */
    data class objectionWindow(val exhibitId: UUID) : DockMode()

    /** Closings done, verdict not yet revealed. */
    data object deliberating : DockMode()

    /** Verdict revealed. */
    data object verdictIn : DockMode()

    /** The safety valve fired: the case is stopped (mistrial). Calm copy, no actions. */
    data object stopped : DockMode()

    val isMyTurn: Boolean get() = this is compose || this is objectionWindow
}

sealed class ComposeKind {
    data object opening : ComposeKind()

    /** My exhibits phase. `available` = my unpresented exhibits, in presentation order. */
    data class presentExhibits(val available: List<UUID>) : ComposeKind()

    /** Answer the judge's cross-examination questions (turn that asked them, if found). */
    data class crossAnswer(val questionTurnId: UUID?) : ComposeKind()
    data object closing : ComposeKind()

    /** Swift `private extension ComposeKind { var isPresent }` (CourtDock). */
    val isPresent: Boolean get() = this is presentExhibits
}

enum class WaitingActivity { opening, presentingExhibit, consideringObjection, answeringJudge, closing, other }

/** Visual family of a speech bubble. */
sealed class CourtBubbleKind {
    /** Judge line (phase line, cross-examination questions). */
    data object judge : CourtBubbleKind()

    /** Judge ruling on an objection; `ruling` stamps on the easel. */
    data class judgeRuling(val ruling: ObjectionRuling?) : CourtBubbleKind()

    /** A party speaking. */
    data class party(val role: Role) : CourtBubbleKind()

    /** A party objecting. */
    data class objection(val role: Role, val reason: ObjectionReason?) : CourtBubbleKind()

    /** A party declining to object. */
    data class pass(val role: Role) : CourtBubbleKind()

    /** Safety-valve notice: plain, no persona decoration. */
    data object safety : CourtBubbleKind()
}

/** Swift tuple `(objection:, objected:, ruling:)`. */
data class Stamps(val objection: ObjectionReason?, val objected: Boolean, val ruling: ObjectionRuling?)

/** Swift tuple `(plaintiff:, defendant:, tie:)`. */
data class PanelVotes(val plaintiff: Int, val defendant: Int, val tie: Int)

/** Swift tuple `(text:, imperative:)`. */
data class OrderClause(val text: String, val imperative: Boolean)

/** Swift tuple `(headline:, line:)`. */
data class StepCopy(val headline: String, val line: String)

/** Swift tuple `(title:, subtitle:)`. */
data class DockCopy(val title: String, val subtitle: String)

/** What the dock offers for the case's judgement (null from `judgementDockMode` = no judgement UI). */
sealed class JudgementDockMode {
    /** I'm the chooser and haven't chosen yet (`tie`: a compromise resolution, not a judgement). */
    data class choose(val tie: Boolean) : JudgementDockMode()

    /** My partner is choosing. */
    data class awaitingChoice(val tie: Boolean) : JudgementDockMode()

    /** Delivered to me (the other party): accept, or quietly decline. */
    data object respond : JudgementDockMode()

    /** Delivered (I chose it; my partner hasn't accepted yet) or accepted (either party). */
    data class markServed(val awaitingAcceptance: Boolean) : JudgementDockMode()

    /** Tie (amendment l), before the court has picked its resolution. */
    data object awaitingCourt : JudgementDockMode()

    /** Tie: the court chose and delivered a resolution. No accept step. */
    data object courtResolution : JudgementDockMode()

    /** Served: the matter is settled. */
    data object served : JudgementDockMode()

    /** The other party declined the judgement. */
    data object declined : JudgementDockMode()

    /** Swift `String(describing: jMode).prefix { $0 != "(" }` (the dock's density key). */
    val caseName: String
        get() = when (this) {
            is choose -> "choose"
            is awaitingChoice -> "awaitingChoice"
            respond -> "respond"
            is markServed -> "markServed"
            awaitingCourt -> "awaitingCourt"
            courtResolution -> "courtResolution"
            served -> "served"
            declined -> "declined"
        }
}

/** What the courtroom stage shows for the judgement. */
sealed class JudgementStage {
    /** Revealed, the winner is still choosing: a judge line in the banner. */
    data class pending(val chooserIsMe: Boolean, val tie: Boolean) : JudgementStage()

    /** Delivered or later (screen C): ALL RISE, the large delivery bubble and the judgement card. */
    data object delivery : JudgementStage()
}

/** What the dock shows for a settlement (null from `settlementDockMode` = no settlement UI). */
sealed class SettlementDockMode {
    /** An offer is on the table; the court waits (turn timer hidden). */
    data class pending(val round: Int, val awaitingMe: Boolean) : SettlementDockMode()

    /** The case closed as `closed_settled`: no verdict, no judgement, only "Back to docket". */
    data object settled : SettlementDockMode()
}

object CourtroomLogic {

    // MARK: Roles & speakers

    fun role(speaker: Speaker): Role? = when (speaker) {
        Speaker.plaintiff -> Role.plaintiff
        Speaker.defendant -> Role.defendant
        Speaker.judge -> null
    }

    /**
     * Who is in the room for a live case (amendment ac). I am, as I'm looking at the court; the other side has joined
     * once they have spoken in this trial. Past the trial (deliberation, verdict, closed) both are.
     */
    fun presentRoles(kase: Case, turns: List<Turn>, myRole: Role?): Set<Role> {
        if (kase.status != CaseStatus.trial) return setOf(Role.plaintiff, Role.defendant)
        val out = mutableSetOf<Role>()
        myRole?.let { out.add(it) }
        for (t in turns) role(t.speaker)?.let { out.add(it) }
        return out
    }

    /** The side currently holding the floor (lit podium), if any. */
    fun activeRole(s: CourtroomState): Role? {
        // While the parties talk settlement nobody has the floor (the court waits).
        if (s.kase.status != CaseStatus.trial || isSettlementPending(s)) return null
        return s.kase.phaseTurnOwner
    }

    /** Is the court deliberating (record closed, ruling not yet revealed)? */
    fun isDeliberating(s: CourtroomState): Boolean =
        s.kase.status == CaseStatus.deliberating || s.kase.status == CaseStatus.awaitingVerdict

    // MARK: Dock

    fun dockMode(s: CourtroomState): DockMode {
        if (s.turns.lastOrNull()?.isSafetyNotice == true ||
            (s.kase.status == CaseStatus.mistrial && s.turns.any { it.isSafetyNotice })
        ) {
            return DockMode.stopped
        }
        when (s.kase.status) {
            CaseStatus.deliberating, CaseStatus.awaitingVerdict -> return DockMode.deliberating
            CaseStatus.verdict -> return DockMode.verdictIn
            CaseStatus.trial -> Unit
            else -> return DockMode.notInSession
        }
        val me = s.myRole ?: return DockMode.spectator
        val phase = s.kase.phase
        val owner = s.kase.phaseTurnOwner
        if (phase == null || owner == null) return DockMode.judgeHasFloor

        if (owner != me) {
            return DockMode.waiting(partner = owner, activity = waitingActivity(phase, owner, s))
        }

        return when (phase) {
            TrialPhase.plaintiffOpening, TrialPhase.defendantOpening -> DockMode.compose(ComposeKind.opening)
            TrialPhase.plaintiffClosing, TrialPhase.defendantClosing -> DockMode.compose(ComposeKind.closing)
            TrialPhase.crossExamination ->
                DockMode.compose(ComposeKind.crossAnswer(questionTurnId = crossQuestionTurn(me, s.turns)?.id))
            TrialPhase.plaintiffExhibits, TrialPhase.defendantExhibits -> {
                if (phase.speakingSide == me) {
                    return DockMode.compose(ComposeKind.presentExhibits(available = unpresentedExhibits(s).map { it.id }))
                }
                val ex = pendingObjectionExhibit(s)
                if (ex != null) return DockMode.objectionWindow(exhibitId = ex.id)
                // I own the turn in the other side's exhibit phase but there's nothing to object to.
                DockMode.judgeHasFloor
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun waitingActivity(phase: TrialPhase, owner: Role, state: CourtroomState): WaitingActivity = when (phase) {
        TrialPhase.plaintiffOpening, TrialPhase.defendantOpening -> WaitingActivity.opening
        TrialPhase.plaintiffClosing, TrialPhase.defendantClosing -> WaitingActivity.closing
        TrialPhase.crossExamination -> WaitingActivity.answeringJudge
        TrialPhase.plaintiffExhibits, TrialPhase.defendantExhibits ->
            if (phase.speakingSide == owner) WaitingActivity.presentingExhibit else WaitingActivity.consideringObjection
    }

    // MARK: Exhibits

    /** Has this exhibit been put before the court? */
    fun isPresented(ex: Exhibit, turns: List<Turn>): Boolean =
        ex.presentedAt != null || turns.any { it.exhibitId == ex.id && it.speaker != Speaker.judge && !it.isObjection }

    private val presentationOrder = compareBy<Exhibit>({ it.sort }, { it.label.index })

    /** My exhibits not yet presented, in presentation order (sort, then label). */
    fun unpresentedExhibits(s: CourtroomState): List<Exhibit> =
        s.exhibits
            .filter { it.ownerId == s.me.id && !isPresented(it, s.turns) && it.type != ExhibitType.voice }
            .sortedWith(presentationOrder)

    /**
     * The exhibit the other side most recently presented, if I still have an objection window on it: it's their
     * exhibits phase, I own the turn, and no objection / pass has been recorded for it.
     */
    fun pendingObjectionExhibit(s: CourtroomState): Exhibit? {
        val me = s.myRole ?: return null
        val phase = s.kase.phase ?: return null
        if (s.kase.status != CaseStatus.trial) return null
        if (!(phase == TrialPhase.plaintiffExhibits || phase == TrialPhase.defendantExhibits)) return null
        if (phase.speakingSide != me.other || s.kase.phaseTurnOwner != me) return null
        val otherSpeaker = if (me.other == Role.plaintiff) Speaker.plaintiff else Speaker.defendant
        val presenting = s.turns.lastOrNull { it.speaker == otherSpeaker && it.exhibitId != null && !it.isObjection } ?: return null
        val ex = s.exhibit(presenting.exhibitId) ?: return null
        if (ex.objectionReason != null) return null
        val responded = s.turns.any { t ->
            t.exhibitId == ex.id && !t.createdAt.isBefore(presenting.createdAt) && t.id != presenting.id &&
                (t.isObjection || isPass(t)) && role(t.speaker) == me
        }
        return if (responded) null else ex
    }

    /** The exhibit on the easel: the one most recently referenced by any turn. */
    fun easelExhibitId(turns: List<Turn>): UUID? = turns.lastOrNull { it.exhibitId != null }?.exhibitId

    /** What the easel shows: which exhibit, and whether it is still the live subject. */
    data class EaselPresentation(
        val exhibitId: UUID,
        /**
         * Live = FULL card: the exhibit was just shown and the objection window is open, an objection on it awaits a
         * ruling, or the ruling on it just landed. Otherwise the easel keeps a MINI thumbnail of the last exhibit.
         */
        val isLive: Boolean,
    )

    /**
     * The easel exhibit (the one most recently referenced) and whether it is live. It stops being live as soon as a
     * party speaks about something else (e.g. "The defence rests."), the other side lets it stand, or the trial leaves
     * the exhibits phases.
     */
    fun easelPresentation(s: CourtroomState): EaselPresentation? {
        val i = s.turns.indexOfLast { it.exhibitId != null }
        if (i < 0) return null
        val t = s.turns[i]
        val id = t.exhibitId ?: return null
        val inExhibits = s.kase.status == CaseStatus.trial &&
            (s.kase.phase == TrialPhase.plaintiffExhibits || s.kase.phase == TrialPhase.defendantExhibits)
        val partySpokeSince = s.turns.drop(i + 1).any { it.speaker != Speaker.judge }
        val live = inExhibits && !isPass(t) && !partySpokeSince
        return EaselPresentation(exhibitId = id, isLive = live)
    }

    /**
     * Objection outcome for an exhibit, merging the exhibit row with what the turns say (turns usually arrive over
     * realtime before the exhibit row update).
     */
    fun stamps(ex: Exhibit, turns: List<Turn>): Stamps {
        val related = turns.filter { it.exhibitId == ex.id }
        val objectionTurn = related.lastOrNull { it.isObjection && it.speaker != Speaker.judge }
        val reason = ex.objectionReason ?: objectionTurn?.objectionReason
        val objected = reason != null || objectionTurn != null
        val ruling = ex.objectionRuling ?: related.lastOrNull { it.objectionRuling != null }?.objectionRuling
        return Stamps(reason, objected, if (objected) ruling else null)
    }

    // MARK: Turns → bubbles

    fun isPass(t: Turn): Boolean = t.meta["pass"]?.boolValue == true

    fun bubbleKind(t: Turn): CourtBubbleKind {
        if (t.isSafetyNotice) return CourtBubbleKind.safety
        return when (t.speaker) {
            Speaker.judge ->
                if (t.aiCall == AICall.objectionRuling || t.objectionRuling != null) {
                    CourtBubbleKind.judgeRuling(t.objectionRuling)
                } else {
                    CourtBubbleKind.judge
                }
            Speaker.plaintiff, Speaker.defendant -> {
                val r = if (t.speaker == Speaker.plaintiff) Role.plaintiff else Role.defendant
                when {
                    isPass(t) -> CourtBubbleKind.pass(r)
                    t.isObjection -> CourtBubbleKind.objection(r, t.objectionReason)
                    else -> CourtBubbleKind.party(r)
                }
            }
        }
    }

    /** Serif-italic judge lines (rulings / verdict). */
    fun isRulingText(t: Turn): Boolean {
        if (t.speaker != Speaker.judge) return false
        return t.aiCall == AICall.objectionRuling || t.aiCall == AICall.verdict || t.aiCall == AICall.appealRuling
    }

    /** Small chip text on a bubble. */
    fun chipTitle(t: Turn, exhibits: List<Exhibit>): String? {
        if (t.isSafetyNotice) return null
        when (bubbleKind(t)) {
            is CourtBubbleKind.objection -> return "Objection"
            is CourtBubbleKind.judgeRuling -> return "Ruling"
            else -> Unit
        }
        val id = t.exhibitId
        if (id != null) {
            exhibits.firstOrNull { it.id == id }?.let { return it.displayName }
        }
        return t.phase?.chipTitle
    }

    /** Cross-examination questions put to `role` (most recent judge turn that asked them). */
    fun crossQuestionTurn(role: Role, turns: List<Turn>): Turn? = turns.lastOrNull { t ->
        t.speaker == Speaker.judge && (t.aiCall == AICall.crossExamine || t.questions.isNotEmpty()) &&
            (t.crossSide == null || t.crossSide == role)
    }

    /** Questions to render for a judge turn: `meta.questions`, else numbered lines parsed from the body. */
    fun questions(t: Turn): List<String> {
        if (t.questions.isNotEmpty()) return t.questions
        if (t.aiCall != AICall.crossExamine) return emptyList()
        val lines = t.body.split('\n', '\r', ' ', ' ', '\u000B', '\u000C', '\u0085')
            .filter { it.isNotEmpty() }
            .map { it.trim { c -> c == ' ' || c == '\t' || Character.isSpaceChar(c) } }
        val numbered = lines.mapNotNull { line ->
            val first = line.firstOrNull() ?: return@mapNotNull null
            if (!first.isDigit()) return@mapNotNull null
            val stripped = line.dropWhile { it.isDigit() || it == '.' || it == ')' || it == ' ' }
            stripped.ifEmpty { null }
        }
        return if (numbered.size >= 2) numbered else emptyList()
    }

    /** Recent bubbles shown in-scene (oldest → newest). */
    fun recentTurns(turns: List<Turn>, limit: Int = 3): List<Turn> = turns.takeLast(limit)

    /**
     * The bubbles on stage right now: at most one judge line (top slot, above the bench) and one party line (above
     * that speaker's podium). A slot only shows a turn from the recent window so stale lines leave the scene; the
     * full history lives in the transcript.
     */
    data class SceneBubbles(
        val judge: Turn?,
        val party: Turn?,
        /** Which of the two is newest (drawn on top, full opacity). */
        val newestIsJudge: Boolean,
    )

    fun sceneBubbles(turns: List<Turn>, judgeWindow: Int = 4, partyWindow: Int = 3): SceneBubbles {
        val n = turns.size
        val judgeIdx = turns.indexOfLast { it.speaker == Speaker.judge && !it.isSafetyNotice }.takeIf { it >= 0 }
        val partyIdx = turns.indexOfLast { it.speaker != Speaker.judge }.takeIf { it >= 0 }
        val j = judgeIdx?.takeIf { n - it <= judgeWindow }
        val p = partyIdx?.takeIf { n - it <= partyWindow }
        return SceneBubbles(judge = j?.let { turns[it] }, party = p?.let { turns[it] }, newestIsJudge = (j ?: -1) > (p ?: -1))
    }

    /** Exhibits whose objection was sustained (verdict "key findings" footnote). */
    fun sustainedExhibits(s: CourtroomState): List<Exhibit> =
        s.exhibits.filter { stamps(it, s.turns).ruling == ObjectionRuling.sustained }.sortedWith(presentationOrder)

    // MARK: Deadlines

    /** What the dock countdown counts down to. */
    fun countdownTarget(s: CourtroomState): Instant? = when (s.kase.status) {
        CaseStatus.deliberating, CaseStatus.awaitingVerdict -> s.kase.trialAt ?: s.kase.verdictAt ?: s.kase.deadlineAt
        CaseStatus.trial -> if (isSettlementPending(s)) null else s.kase.deadlineAt
        else -> null
    }

    fun isPastDeadline(s: CourtroomState): Boolean {
        val d = s.kase.deadlineAt ?: return false
        return !s.now.isBefore(d)
    }

    /** "11:42:05", "42:05", or "0:00". Tabular-friendly. */
    fun formatRemaining(interval: Double): String {
        val total = max(0, floor(interval).toInt())
        val h = total / 3600
        val m = (total % 3600) / 60
        val sec = total % 60
        if (h >= 24) return "${h / 24}d ${h % 24}h"
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        return String.format(Locale.US, "%d:%02d", m, sec)
    }

    /**
     * VoiceOver value for a live countdown: whole minutes only, so it changes (and is re-read) once a minute, never
     * every second. "11 hours 42 minutes", "Less than a minute", "Time is up".
     */
    fun spokenCountdown(interval: Double): String {
        val total = max(0, interval.toInt())
        if (total == 0) return "Time is up"
        if (total < 60) return "Less than a minute"
        val d = total / 86_400
        val h = (total % 86_400) / 3600
        val m = (total % 3600) / 60
        val parts = mutableListOf<String>()
        if (d > 0) parts.add("$d day${if (d == 1) "" else "s"}")
        if (h > 0) parts.add("$h hour${if (h == 1) "" else "s"}")
        if (m > 0 && d == 0) parts.add("$m minute${if (m == 1) "" else "s"}")
        return parts.joinToString(" ")
    }

    /** VoiceOver phrase: "11 hours 42 minutes left". */
    fun spokenRemaining(interval: Double): String {
        val total = max(0, interval.toInt())
        if (total == 0) return "Time is up"
        val h = total / 3600
        val m = (total % 3600) / 60
        val parts = mutableListOf<String>()
        if (h > 0) parts.add("$h hour${if (h == 1) "" else "s"}")
        if (m > 0 || h == 0) parts.add("$m minute${if (m == 1) "" else "s"}")
        return parts.joinToString(" ") + " left"
    }

    // MARK: Copy

    fun phaseTitle(phase: TrialPhase): String = when (phase) {
        TrialPhase.plaintiffOpening -> "Plaintiff's opening"
        TrialPhase.defendantOpening -> "Defendant's opening"
        TrialPhase.plaintiffExhibits -> "Plaintiff's exhibits"
        TrialPhase.defendantExhibits -> "Defendant's exhibits"
        TrialPhase.crossExamination -> "Cross-examination"
        TrialPhase.plaintiffClosing -> "Plaintiff's closing"
        TrialPhase.defendantClosing -> "Defendant's closing"
    }

    fun waitingLine(activity: WaitingActivity, name: String): String = when (activity) {
        WaitingActivity.opening -> "$name is preparing their opening statement."
        WaitingActivity.presentingExhibit -> "$name is choosing an exhibit."
        WaitingActivity.consideringObjection -> "$name is deciding whether to object."
        WaitingActivity.answeringJudge -> "$name is answering the judge."
        WaitingActivity.closing -> "$name is writing their closing statement."
        WaitingActivity.other -> "$name has the floor."
    }

    fun composePlaceholder(kind: ComposeKind): String = when (kind) {
        ComposeKind.opening -> "Your opening statement…"
        is ComposeKind.presentExhibits -> "Say why it matters (optional)"
        is ComposeKind.crossAnswer -> "Answer every question in one reply…"
        ComposeKind.closing -> "Your closing statement…"
    }

    fun composeTitle(kind: ComposeKind): String = when (kind) {
        ComposeKind.opening -> "Your opening statement"
        is ComposeKind.presentExhibits -> "Show your evidence"
        is ComposeKind.crossAnswer -> "Answer the judge"
        ComposeKind.closing -> "Your closing statement"
    }

    /**
     * The exhibit the dock loads for the presenter: the chosen one if it's still unpresented, otherwise the next in
     * presentation order.
     */
    fun dockExhibitId(available: List<UUID>, chosen: UUID?): UUID? {
        if (chosen != null && available.contains(chosen)) return chosen
        return available.firstOrNull()
    }

    /** Default body when showing an exhibit without commentary. */
    fun defaultPresentLine(ex: Exhibit): String {
        val caption = ex.caption.trim()
        if (caption.isEmpty()) return "The court is directed to ${ex.displayName}."
        val end = if (caption.last() in ".!?") "" else "."
        return "The court is directed to ${ex.displayName}: $caption$end"
    }

    /** "Show Exhibit A". */
    fun showButtonTitle(ex: Exhibit): String = "Show ${ex.displayName}"

    /** The tightest form, beside the Show button on small phones: "2 left". */
    fun exhibitsLeftTiny(count: Int): String = if (count == 0) "All in" else "$count left"

    /** Presenter dock, short form beside the Rest link: "2 exhibits left". */
    fun exhibitsLeftShort(count: Int): String = when (count) {
        0 -> "All evidence in"
        1 -> "1 exhibit left"
        else -> "$count exhibits left"
    }

    /** Presenter dock subtitle: how much evidence is left. */
    fun exhibitsLeftLine(count: Int): String = when (count) {
        0 -> "All your evidence is in."
        1 -> "1 exhibit left to show."
        else -> "$count exhibits left to show."
    }

    const val restHint = "Done with your evidence? Rest to hand over."
    const val exhibitExplainer = "Show your evidence one piece at a time. Your partner can object to each."

    /** One-line form of the explainer for the compact dock. */
    const val exhibitExplainerShort = "One at a time. Your partner can object to each."

    /** Objection-window line in plain words: "Alex showed Exhibit B. Object if it's unfair, or let it stand." */
    fun objectionWindowLine(partnerName: String, exhibit: Exhibit?): String =
        "$partnerName showed ${exhibit?.displayName ?: "an exhibit"}. Object if it's unfair, or let it stand."

    /** Default body when resting without typing anything. */
    fun restLine(role: Role): String = "No further exhibits, Your Honour. The ${role.rawValue} rests."

    fun roleTitle(r: Role): String = if (r == Role.plaintiff) "Plaintiff" else "Defendant"

    /**
     * "THE COURT HAS REACHED A DECISION" → "The court has reached a decision" (long phrases are never set in caps;
     * typography brief §7).
     */
    fun sentenceCase(s: String): String {
        val lower = s.lowercase()
        return lower.take(1).uppercase() + lower.drop(1)
    }

    /** Verdict headline: "PLAINTIFF WINS" / "IT'S A TIE". */
    fun verdictHeadline(v: Verdict, kase: Case): String {
        if (v.isTie) return "IT'S A TIE"
        val w = v.winnerId ?: return "THE COURT HAS RULED"
        val r = kase.role(w) ?: return "THE COURT HAS RULED"
        return "${roleTitle(r).uppercase()} WINS"
    }

    /** "2-1 PANEL DECISION · HIGH CONFIDENCE". */
    fun panelLine(v: Verdict): String {
        val parts = mutableListOf(v.panelSplitHeadline)
        val c = v.confidenceLabel
        if (!c.isNullOrEmpty()) parts.add("${c.uppercase()} CONFIDENCE")
        return parts.joinToString(" · ")
    }

    /** Juror votes as (plaintiff, defendant, tie), from `panel_votes` or parsed from the split. */
    fun panelVotes(v: Verdict, kase: Case): PanelVotes {
        val votes = v.panelVotes
        if (votes != null) {
            val p = (votes["plaintiff"]?.numberValue ?: 0.0).toInt()
            val d = (votes["defendant"]?.numberValue ?: 0.0).toInt()
            val t = (votes["tie"]?.numberValue ?: 0.0).toInt()
            if (p + d + t > 0) return PanelVotes(p, d, t)
        }
        val split = v.panelSplit ?: return PanelVotes(0, 0, 0)
        val nums = split.split("-").filter { it.isNotEmpty() }.mapNotNull { it.toIntOrNull() }
        if (nums.size == 3) return PanelVotes(nums[0], nums[1], nums[2])
        if (nums.size != 2) return PanelVotes(0, 0, 0)
        val winnerRole = v.winnerId?.let { kase.role(it) }
        if (v.isTie || winnerRole == null) return PanelVotes(0, 0, nums[0] + nums[1])
        return if (winnerRole == Role.plaintiff) PanelVotes(nums[0], nums[1], 0) else PanelVotes(nums[1], nums[0], 0)
    }

    // MARK: - Winner-selected court judgement (CONTRACTS-v2 amendment j)

    /** The line every revealed verdict now carries in `sentence`; it is never shown as a sentence. */
    const val fixedJudgementSentence = "The prevailing party will choose the court's judgement."

    /** Is the case at the post-verdict stage where the judgement lives (revealed, not stopped)? */
    fun judgementApplies(s: CourtroomState): Boolean {
        if (s.judgement == null) return false
        if (s.turns.lastOrNull()?.isSafetyNotice == true) return false
        return s.kase.status == CaseStatus.verdict || s.kase.status == CaseStatus.closed
    }

    fun judgementDockMode(s: CourtroomState): JudgementDockMode? {
        if (!judgementApplies(s)) return null
        val j = s.judgement ?: return null
        if (s.myRole == null) return null
        if (j.isCourtChosen) {
            return when (j.status) {
                JudgementStatus.pendingSelection -> JudgementDockMode.awaitingCourt
                JudgementStatus.delivered, JudgementStatus.accepted -> JudgementDockMode.courtResolution
                JudgementStatus.served -> JudgementDockMode.served
                JudgementStatus.declined -> JudgementDockMode.declined
            }
        }
        val chooser = j.chooserId == s.me.id
        val tie = s.verdict?.isTie == true
        return when (j.status) {
            JudgementStatus.pendingSelection -> if (chooser) JudgementDockMode.choose(tie) else JudgementDockMode.awaitingChoice(tie)
            JudgementStatus.delivered -> if (chooser) JudgementDockMode.markServed(awaitingAcceptance = true) else JudgementDockMode.respond
            JudgementStatus.accepted -> JudgementDockMode.markServed(awaitingAcceptance = false)
            JudgementStatus.served -> JudgementDockMode.served
            JudgementStatus.declined -> JudgementDockMode.declined
        }
    }

    fun judgementStage(s: CourtroomState): JudgementStage? {
        if (!judgementApplies(s)) return null
        val j = s.judgement ?: return null
        if (j.status == JudgementStatus.pendingSelection) {
            return JudgementStage.pending(chooserIsMe = j.chooserId == s.me.id, tie = s.verdict?.isTie == true)
        }
        return if (deliveryText(s) == null) null else JudgementStage.delivery
    }

    /** The judge's delivery turn: the one the judgement row points at, else the latest `judgement_delivery` turn. */
    fun deliveryTurn(s: CourtroomState): Turn? {
        val id = s.judgement?.deliveredTurnId
        if (id != null) s.turns.firstOrNull { it.id == id }?.let { return it }
        return s.turns.lastOrNull { it.aiCall == AICall.judgementDelivery }
    }

    /**
     * What the judge reads out: the delivery turn if it has arrived, else the brief's template built from the
     * selected option. null before anything has been selected.
     */
    fun deliveryText(s: CourtroomState): String? {
        val t = deliveryTurn(s)
        if (t != null && t.body.trim().isNotEmpty()) return t.body
        val j = s.judgement ?: return null
        val selected = j.selected ?: return null
        val detail = selected.detail.ifEmpty { selected.title }
        if (j.isCourtChosen || s.verdict?.isTie == true) {
            return templatedTieDelivery(detail = detail, partyNames = listOf(s.me.displayName, s.partner.displayName))
        }
        val chooser = s.profile(j.chooserId) ?: s.me
        val winner = s.profile(s.verdict?.winnerId) ?: chooser
        val loser = if (winner.id == s.me.id) s.partner else s.me
        return templatedDelivery(
            winner = winner.displayName, loser = loser.displayName, chooser = chooser.displayName,
            detail = selected.detail.ifEmpty { selected.title },
            tie = s.verdict?.isTie == true, partyNames = listOf(s.me.displayName, s.partner.displayName),
        )
    }

    /**
     * Brief screen C: "The court finds for {winner}. The prevailing party has selected their judgement. {Loser} is
     * hereby ordered to {detail}. The court considers this matter settled." (they/them).
     */
    @Suppress("UNUSED_PARAMETER")
    fun templatedDelivery(
        winner: String,
        loser: String,
        chooser: String,
        detail: String,
        tie: Boolean,
        partyNames: List<String> = emptyList(),
    ): String {
        if (tie) return templatedTieDelivery(detail = detail, partyNames = partyNames + listOf(winner, loser))
        val clause = orderClause(detail, names = partyNames + listOf(winner, loser))
        val order = if (clause.imperative) "$loser is hereby ordered to ${clause.text}." else "The court orders: ${clause.text}."
        return "The court finds for $winner. The prevailing party has selected their judgement. $order The court considers this matter settled."
    }

    /**
     * Amendment l: on a tie the court itself chooses. "The court could not separate you. It has chosen a resolution for
     * you both. Both parties are hereby ordered to {detail}. The court considers this matter settled."
     */
    fun templatedTieDelivery(detail: String, partyNames: List<String> = emptyList()): String {
        val clause = orderClause(detail, names = partyNames)
        val order = if (clause.imperative) "Both parties are hereby ordered to ${clause.text}." else "The court orders: ${clause.text}."
        return "The court could not separate you. It has chosen a resolution for you both. $order The court considers this matter settled."
    }

    /** Swift `CharacterSet.punctuationCharacters`. */
    private fun isPunctuation(c: Char): Boolean = when (Character.getType(c).toByte()) {
        Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION -> true
        else -> false
    }

    /**
     * Turns an option detail into the tail of "is hereby ordered to …": trimmed, no final full stop, first letter
     * lowercased. Details that start with a name ("Aria chooses the next takeaway") can't follow "ordered to", so they
     * are quoted as a plain order instead (`imperative == false`).
     */
    fun orderClause(detail: String, names: List<String>): OrderClause {
        var d = detail.trim()
        while (d.isNotEmpty() && d.last() in ".!") d = d.dropLast(1)
        val first = d.firstOrNull() ?: return OrderClause("", false)
        val firstWord = d.split(" ").firstOrNull { it.isNotEmpty() } ?: d
        val bare = firstWord.trim { isPunctuation(it) }
        if (names.any { it.equals(bare, ignoreCase = true) } || bare.endsWith("'s")) {
            return OrderClause(d, false)
        }
        // Keep acronyms / "I" as written.
        val isAcronym = bare.length > 1 && bare == bare.uppercase() && bare.any { it.isLetter() }
        if (isAcronym || bare == "I") return OrderClause(d, true)
        return OrderClause(first.lowercase() + d.drop(1), true)
    }

    /** Swift `Date.formatted(.dateTime.day().month(.abbreviated))`: "3 Oct" (locale order where known). */
    fun dayMonthAbbreviated(at: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
        val pattern = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "dMMM") }.getOrNull() ?: "d MMM"
        return DateTimeFormatter.ofPattern(pattern, locale).format(at.atZone(zone))
    }

    /**
     * "Due: Sunday" from `dueAt` (Today / Tomorrow / weekday within the week / "3 Oct" further out; "Was due Sunday"
     * once past). null when there's no due date or the matter is closed.
     */
    fun dueLine(j: Judgement, now: Instant, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String? {
        val due = j.dueAt ?: return null
        if (!(j.status == JudgementStatus.delivered || j.status == JudgementStatus.accepted)) return null
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), due.atZone(zone).toLocalDate()).toInt()
        val weekday = DateTimeFormatter.ofPattern("EEEE", locale).format(due.atZone(zone))
        val short = dayMonthAbbreviated(due, zone, locale)
        return when {
            days < -6 -> "Was due $short"
            days < 0 -> "Was due $weekday"
            days == 0 -> "Due: Today"
            days == 1 -> "Due: Tomorrow"
            days in 2..6 -> "Due: $weekday"
            else -> "Due: $short"
        }
    }

    /**
     * Verdict result card / summary line: "Judgement: Thermostat at 21° · Delivered" or "Awaiting the winner's
     * choice". null when there is no judgement.
     */
    fun judgementSummary(s: CourtroomState): String? {
        val j = s.judgement ?: return null
        if (j.status == JudgementStatus.pendingSelection || j.selected == null) {
            return if (s.verdict?.isTie == true) "Awaiting a resolution" else JudgementStatus.pendingSelection.title
        }
        return "${j.selected?.title ?: "Judgement"} · ${j.status.title}"
    }

    /** The judge's banner line while the winner chooses (screen A's pending state, in the scene). */
    fun pendingJudgeLine(s: CourtroomState): String {
        val tie = s.verdict?.isTie == true
        val chooserIsMe = s.judgement?.chooserId == s.me.id
        if (tie || s.judgement?.isCourtChosen == true) {
            return "The court could not separate you. The court is choosing a resolution."
        }
        return if (chooserIsMe) "The prevailing party may now choose the court's judgement."
        else "The prevailing party is choosing the court's judgement."
    }

    /** Verdict sequence judgement step copy: (headline, line) for the viewer. */
    fun judgementStepCopy(s: CourtroomState): StepCopy {
        // Tie (amendment l): the court chooses for both; nobody is handed a choice.
        if (s.verdict?.isTie == true || s.judgement?.isCourtChosen == true) {
            return StepCopy("The court could not separate you.", "The court has chosen a resolution.")
        }
        val chooserIsMe = s.judgement?.chooserId == s.me.id
        return if (chooserIsMe) StepCopy("You won the case.", "Now choose the court's judgement.")
        else StepCopy("The court has ruled.", "The prevailing party is choosing the court's judgement.")
    }

    /** Legacy verdicts (before amendment j) carried a real sentence; show it only then. */
    fun legacySentence(v: Verdict): String? {
        val t = v.sentence.trim()
        return if (t.isEmpty() || t == fixedJudgementSentence) null else t
    }

    // MARK: - Settle Outside Court (CONTRACTS-v2 amendment n)

    /** The judge's banner line while a settlement is pending. */
    const val settlementPendingJudgeLine = "The parties are talking. The court will wait."

    /** The judge's flavour line on an accepted settlement (the backend writes it as a judge turn). */
    const val settledJudgeLine = "The parties have spared the court the trouble. Miracles do happen."

    /** The pending banner's title. */
    const val settlementPendingTitle = "Settlement pending · the court will wait"

    /** The seal on the easel for a settled case. */
    const val settledSealTitle = "SETTLED OUT OF COURT"

    /** A settlement is pending on this (open) case: its row says so, or the case points at one. */
    fun isSettlementPending(s: CourtroomState): Boolean {
        if (!s.kase.status.isOpen) return false
        val st = s.settlement
        if (st != null && st.caseId == s.kase.id) return st.isPending
        return s.kase.settlementId != null
    }

    /** "Round 2 of 3". */
    fun settlementRoundLine(round: Int): String =
        "Round ${min(max(round, 1), Settlement.maxRounds)} of ${Settlement.maxRounds}"

    /**
     * Who made the current offer: the latest offer's author, else (offer not loaded yet) the side whose turn it is by
     * round parity (round 1 = the initiator; each counter swaps roles).
     */
    fun currentProposer(s: CourtroomState): UUID? {
        val o = s.settlementOffer
        if (o != null && (s.settlement?.let { it.id == o.settlementId } ?: true)) return o.proposedBy
        val st = s.settlement ?: return null
        if (st.currentRound % 2 == 1) return st.initiatedBy
        return if (st.initiatedBy == s.me.id) s.partner.id else s.me.id
    }

    fun settlementDockMode(s: CourtroomState): SettlementDockMode? {
        if (s.turns.lastOrNull()?.isSafetyNotice == true) return null
        if (s.kase.status == CaseStatus.closedSettled) return SettlementDockMode.settled
        if (!isSettlementPending(s) || s.myRole == null) return null
        val round = s.settlement?.currentRound ?: s.settlementOffer?.roundNumber ?: 1
        val proposer = currentProposer(s)
        return SettlementDockMode.pending(round = round, awaitingMe = proposer?.let { it != s.me.id } ?: false)
    }

    /** Phases in which a settlement may still be proposed (before closing statements begin). */
    fun phaseAllowsSettlement(phase: TrialPhase?): Boolean = when (phase) {
        null -> true
        TrialPhase.plaintiffClosing, TrialPhase.defendantClosing -> false
        else -> true
    }

    /**
     * The dock's case-actions menu offers "Propose settlement": the store allows it, I'm a party, the trial is live and
     * before closings, nothing is pending and the case hasn't been stopped.
     */
    fun showsProposeSettlement(s: CourtroomState): Boolean {
        if (!s.canProposeSettlement || s.myRole == null || s.kase.status != CaseStatus.trial ||
            !phaseAllowsSettlement(s.kase.phase) || isSettlementPending(s)
        ) {
            return false
        }
        return dockMode(s) != DockMode.stopped
    }

    /** Dock copy for a settlement: (title, subtitle). */
    fun settlementDockCopy(m: SettlementDockMode, partnerName: String): DockCopy = when (m) {
        is SettlementDockMode.pending -> {
            val final = m.round >= Settlement.maxRounds
            if (m.awaitingMe) {
                DockCopy(
                    settlementPendingTitle,
                    if (final) "$partnerName sent a final offer. Accept it, or the case returns to court."
                    else "$partnerName sent an offer. Take your time.",
                )
            } else {
                DockCopy(settlementPendingTitle, "Your offer is with $partnerName. The case resumes if it isn't accepted.")
            }
        }
        SettlementDockMode.settled -> DockCopy("Settled out of court", "No winner. No loser. Case closed.")
    }

    /** "Open settlement" (the app decides: response sheet for the receiver, waiting room for the proposer). */
    fun settlementButtonTitle(m: SettlementDockMode): String = when (m) {
        is SettlementDockMode.pending -> "Open settlement"
        SettlementDockMode.settled -> "Back to docket"
    }

    /**
     * The judge's banner turn for a settlement: the synthesised "court will wait" line while pending; on a settled case
     * the backend's flavour turn (the latest judge line), else the fixed line.
     */
    fun settlementJudgeTurn(s: CourtroomState): Turn? = when (settlementDockMode(s)) {
        is SettlementDockMode.pending ->
            Turn(id = s.kase.id, caseId = s.kase.id, speaker = Speaker.judge, body = settlementPendingJudgeLine,
                aiCall = AICall.phaseLine, createdAt = s.now)
        SettlementDockMode.settled -> {
            // `.distantPast` when neither is known: every judge line qualifies.
            val since = s.settlement?.acceptedAt ?: s.kase.closedAt
            val floor = since?.minusSeconds(60)
            s.turns.lastOrNull { it.speaker == Speaker.judge && !it.isSafetyNotice && (floor == null || !it.createdAt.isBefore(floor)) }
                ?: Turn(id = s.kase.id, caseId = s.kase.id, speaker = Speaker.judge, body = settledJudgeLine,
                    aiCall = AICall.phaseLine, createdAt = s.now)
        }
        null -> null
    }
}
