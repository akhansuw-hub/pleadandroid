// Port of ArgueWin/Courtroom/CourtroomPreviewData.swift: courtroom preview fixtures (self-contained; the app has its
// own `PreviewData`). "Case #14 · The Thermostat Incident": Aria (plaintiff) v Sam (defendant), as pixel avatars.
//
// Kotlin `object` vals initialise in declaration order (Swift `static let` is lazy), so the verdicts come before the
// scenarios that use them and `all` comes last.
package app.plead.android.courtroom

import app.plead.android.models.AICall
import app.plead.android.models.Avatar
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Exhibit
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import app.plead.android.models.JSONValue
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementOption
import app.plead.android.models.JudgementOptionType
import app.plead.android.models.JudgementStatus
import app.plead.android.models.Plea
import app.plead.android.models.Profile
import app.plead.android.models.Role
import app.plead.android.models.Settlement
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementSource
import app.plead.android.models.SettlementStatus
import app.plead.android.models.Speaker
import app.plead.android.models.TrialPhase
import app.plead.android.models.Turn
import app.plead.android.models.Verdict
import app.plead.android.models.VerdictFinding
import app.plead.android.models.VerdictKind
import java.net.URI
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.UUID

object CourtFixtures {
    fun uuid(n: Int): UUID = UUID.fromString(String.format(Locale.US, "00000000-0000-0000-0000-%012X", n))

    val coupleId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000C001")
    val caseId: UUID = UUID.fromString("00000000-0000-0000-0000-00000000CA5E")
    val aria = Profile(
        id = UUID.fromString("00000000-0000-0000-0000-0000000A41A0"), displayName = "Aria",
        avatar = Avatar(skin = 1, hair = 2, hairstyle = Avatar.Hairstyle.long, top = 3, outfit = Avatar.Outfit.dress), coupleId = coupleId,
    )
    val sam = Profile(
        id = UUID.fromString("00000000-0000-0000-0000-00000000005A"), displayName = "Sam",
        avatar = Avatar(skin = 3, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.hoodie), coupleId = coupleId,
    )

    val base: Instant = Instant.now().minusSeconds(3 * 3600)

    private fun now(offsetSeconds: Double = 0.0): Instant = Instant.now().plusMillis((offsetSeconds * 1000).toLong())

    fun sept12(h: Int, m: Int): Instant = LocalDateTime.of(2026, 9, 12, h, m).atZone(ZoneId.systemDefault()).toInstant()

    // MARK: Exhibits

    val exA = Exhibit(id = uuid(0xA), caseId = caseId, ownerId = aria.id, label = ExhibitLabel.A, type = ExhibitType.screenshot,
        caption = "Thermostat app: 26° at 21:14", occurredAt = sept12(21, 14), sort = 0)
    val exB = Exhibit(id = uuid(0xB), caseId = caseId, ownerId = aria.id, label = ExhibitLabel.B, type = ExhibitType.receipt,
        caption = "The 'just for a minute' receipt",
        body = "turned it up to 26 \"just for a minute\". Still 26 at 23:40.", occurredAt = sept12(21, 14), sort = 1)
    val exC = Exhibit(id = uuid(0xC), caseId = caseId, ownerId = aria.id, label = ExhibitLabel.C, type = ExhibitType.text,
        caption = "Direct quote, Tuesday",
        body = "\"I'm not cold, you're just dramatic.\"", sort = 2)
    val exD = Exhibit(id = uuid(0xD), caseId = caseId, ownerId = sam.id, label = ExhibitLabel.A, type = ExhibitType.photo,
        caption = "Aria wearing three jumpers indoors", sort = 0)
    val exE = Exhibit(id = uuid(0xE), caseId = caseId, ownerId = sam.id, label = ExhibitLabel.B, type = ExhibitType.text,
        caption = "Energy bill note", body = "\"Heating is 40% of this month's bill.\"", sort = 1)
    val exhibits = listOf(exA, exB, exC, exD, exE)

    val exhibitURLs: Map<UUID, URI> = mapOf(
        exA.id to URI("https://picsum.photos/seed/arguewin-thermostat/800/600"),
        exD.id to URI("https://picsum.photos/seed/arguewin-jumpers/800/600"),
    )

    // MARK: Turns (the whole trial, in order)

    private fun str(s: String) = JSONValue.Str(s)
    private fun obj(vararg pairs: Pair<String, JSONValue>) = JSONValue.Obj(mapOf(*pairs))

    val allTurns: List<Turn> = run {
        var i = 0
        fun t(
            phase: TrialPhase?,
            speaker: Speaker,
            body: String,
            exhibit: Exhibit? = null,
            call: AICall? = null,
            meta: Map<String, JSONValue> = emptyMap(),
        ): Turn {
            i += 1
            return Turn(id = uuid(0x1000 + i), caseId = caseId, phase = phase, speaker = speaker, body = body, exhibitId = exhibit?.id,
                aiCall = call, meta = JSONValue.Obj(meta), createdAt = base.plusSeconds(i * 300L))
        }
        listOf(
            t(TrialPhase.plaintiffOpening, Speaker.judge, "Order! Case #14, the Thermostat Incident, is now in session. The court notes the room is a balmy 26 degrees. The plaintiff may open.", call = AICall.phaseLine),
            t(TrialPhase.plaintiffOpening, Speaker.plaintiff, "Your Honour, every night the thermostat creeps up to 26. I've asked nicely. I've asked less nicely. I am sweating in my own home."),
            t(TrialPhase.defendantOpening, Speaker.judge, "The court thanks the plaintiff for that heated opening. The defendant may respond.", call = AICall.phaseLine),
            t(TrialPhase.defendantOpening, Speaker.defendant, "I run cold. That's not a crime. Also Aria owns eleven fleeces, which the court will hear about."),
            t(TrialPhase.plaintiffExhibits, Speaker.judge, "The plaintiff will present her first exhibit.", call = AICall.phaseLine),
            t(TrialPhase.plaintiffExhibits, Speaker.plaintiff, "Exhibit A: 21:14, thermostat at 26. The timestamp speaks for itself.", exhibit = exA),
            t(TrialPhase.plaintiffExhibits, Speaker.defendant, "That was one night. It's out of context.", exhibit = exA,
                meta = mapOf("objection" to obj("reason" to str("out_of_context")))),
            t(TrialPhase.plaintiffExhibits, Speaker.judge, "Overruled. The court finds the context abundantly warm.", exhibit = exA, call = AICall.objectionRuling,
                meta = mapOf("objection" to obj("reason" to str("out_of_context"), "ruling" to str("overruled")))),
            t(TrialPhase.plaintiffExhibits, Speaker.plaintiff, "Exhibit B: the 'just for a minute' receipt. It was not a minute.", exhibit = exB),
            t(TrialPhase.plaintiffExhibits, Speaker.defendant, "", exhibit = exB, meta = mapOf("pass" to JSONValue.Bool(true))),
            t(TrialPhase.plaintiffExhibits, Speaker.plaintiff, "No further exhibits, Your Honour. The plaintiff rests."),
            t(TrialPhase.defendantExhibits, Speaker.judge, "The defendant will now present his exhibits.", call = AICall.phaseLine),
            t(TrialPhase.defendantExhibits, Speaker.defendant, "Exhibit A: the plaintiff, indoors, in three jumpers. Who's really cold here?", exhibit = exD),
            t(TrialPhase.defendantExhibits, Speaker.plaintiff, "My mum told me he took that photo on purpose to use in court.", exhibit = exD,
                meta = mapOf("objection" to obj("reason" to str("hearsay")))),
            t(TrialPhase.defendantExhibits, Speaker.judge, "Sustained. The court cannot rule on what the plaintiff's mother allegedly overheard, however plausible.", exhibit = exD, call = AICall.objectionRuling,
                meta = mapOf("objection" to obj("reason" to str("hearsay"), "ruling" to str("sustained")))),
            t(TrialPhase.defendantExhibits, Speaker.defendant, "The defence rests."),
            t(TrialPhase.crossExamination, Speaker.judge, "Questions for the plaintiff.", call = AICall.crossExamine,
                meta = mapOf("side" to str("plaintiff"), "questions" to JSONValue.Arr(listOf(
                    str("If you are so warm, why the three jumpers?"),
                    str("Have you ever touched the thermostat yourself?"),
                    str("What temperature would you accept, in writing?"),
                )))),
            t(TrialPhase.crossExamination, Speaker.plaintiff, "1. Fashion. 2. Only to turn it DOWN. 3. 20, and I'll put it on the fridge."),
            t(TrialPhase.crossExamination, Speaker.judge, "Questions for the defendant.", call = AICall.crossExamine,
                meta = mapOf("side" to str("defendant"), "questions" to JSONValue.Arr(listOf(
                    str("Was it, in fact, 'just for a minute'?"),
                    str("Have you considered a jumper of your own?"),
                )))),
            t(TrialPhase.crossExamination, Speaker.defendant, "It was more of a long minute. And jumpers are itchy."),
            t(TrialPhase.plaintiffClosing, Speaker.judge, "Closing statements. The plaintiff first.", call = AICall.phaseLine),
            t(TrialPhase.plaintiffClosing, Speaker.plaintiff, "20 degrees. That's all I'm asking. For peace, and for my pores."),
            t(TrialPhase.defendantClosing, Speaker.defendant, "I'll compromise at 22 and I'm keeping the fluffy socks."),
            t(null, Speaker.judge, "The court has heard enough. Judgment will be delivered at the appointed hour.", call = AICall.phaseLine),
        )
    }

    // MARK: Verdicts

    val verdict = Verdict(
        id = uuid(0x3000), caseId = caseId, kind = VerdictKind.ruling, winnerId = aria.id, isTie = false,
        recap = "The plaintiff says the heating lives at 26 and she lives in a sauna. The defendant says he runs cold and the plaintiff owns eleven fleeces.",
        findings = listOf(
            VerdictFinding(exhibitId = exA.id, label = "Exhibit A", side = Role.plaintiff, finding = "The screenshot: 26 degrees at 21:14. The court finds the timestamp damning.", weight = 3),
            VerdictFinding(exhibitId = exB.id, label = "Exhibit B", side = Role.plaintiff, finding = "The receipt: 'just for a minute' lasted two hours and twenty-six minutes.", weight = 2),
            VerdictFinding(exhibitId = exD.id, label = "Exhibit A (defence)", side = Role.defendant, finding = "The jumper photo: objection sustained. The court gives it little warmth.", weight = 1),
        ),
        sentence = "The thermostat is set to 21° for seven days. The defendant receives one new jumper, of his choosing.",
        closingLine = "The court is adjourned. Somebody open a window.",
        panelSplit = "2-1",
        panelVotes = JSONValue.Obj(mapOf("plaintiff" to JSONValue.Num(2.0), "defendant" to JSONValue.Num(1.0), "tie" to JSONValue.Num(0.0))),
        confidenceLabel = "high",
    )

    val tieVerdict = Verdict(
        id = uuid(0x3001), caseId = caseId, kind = VerdictKind.ruling, winnerId = null, isTie = true,
        recap = verdict.recap, findings = verdict.findings,
        sentence = "Both parties will meet at 22° and share the fluffy socks on alternate days.",
        closingLine = "The court finds you both ridiculous, and very well matched.",
        panelSplit = "1-1-1",
        panelVotes = JSONValue.Obj(mapOf("plaintiff" to JSONValue.Num(1.0), "defendant" to JSONValue.Num(1.0), "tie" to JSONValue.Num(1.0))),
        confidenceLabel = "low",
    )

    // MARK: Case

    private const val defaultDeadline: Double = 11 * 3600.0 + 42 * 60 + 5

    fun kase(status: CaseStatus, phase: TrialPhase?, owner: Role?, deadlineIn: Double? = defaultDeadline): Case = Case(
        id = caseId, coupleId = coupleId, caseNumber = 14, title = "The Thermostat Incident",
        plaintiffId = aria.id, defendantId = sam.id, status = status, phase = phase, phaseTurnOwner = owner,
        charge = "Repeatedly setting the heating to 26°.", remedyRequested = "Thermostat locked at 20°.",
        plea = Plea.notGuilty, trialAt = now(2 * 3600.0 + 14 * 60),
        deadlineAt = deadlineIn?.let { now(it) }, createdAt = base,
    )

    fun state(
        phase: TrialPhase?,
        owner: Role?,
        turns: Int,
        status: CaseStatus = CaseStatus.trial,
        verdict: Verdict? = null,
        progress: Int = 0,
        deadlineIn: Double? = defaultDeadline,
    ): CourtroomState {
        var k = kase(status, phase, owner, deadlineIn)
        if (status == CaseStatus.deliberating || status == CaseStatus.awaitingVerdict) {
            k = k.copy(trialAt = nextThursdayEvening, panelProgress = progress)
        }
        return CourtroomState(
            kase = k, turns = allTurns.take(turns), exhibits = exhibits, me = aria, partner = sam, myRole = Role.plaintiff,
            exhibitURLs = exhibitURLs, now = Instant.now(), verdict = verdict, deliberationProgress = progress,
        )
    }

    /** Thursday 20:00 after today (for "Ruling at Thursday, 8:00 PM"). */
    val nextThursdayEvening: Instant
        get() {
            val zone = ZoneId.systemDefault()
            val nowLocal = LocalDateTime.now(zone)
            var d = nowLocal.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.THURSDAY)).atTime(20, 0)
            if (!d.isAfter(nowLocal)) d = d.plusWeeks(1)
            return d.atZone(zone).toInstant()
        }

    // MARK: Scenarios

    /** My (plaintiff's) opening. */
    val openingMyTurn = state(TrialPhase.plaintiffOpening, owner = Role.plaintiff, turns = 1)

    /** Sam is writing his opening. */
    val waitingForPartner = state(TrialPhase.defendantOpening, owner = Role.defendant, turns = 3)

    /** I'm presenting exhibits (A, B, C available). */
    val presentExhibits = state(TrialPhase.plaintiffExhibits, owner = Role.plaintiff, turns = 5)

    /** Exhibit A is on the easel (objected, overruled); my turn to present the next one. */
    val presenting = state(TrialPhase.plaintiffExhibits, owner = Role.plaintiff, turns = 8)

    /** Sam presented his photo; I may object or pass. */
    val objectionWindow = state(TrialPhase.defendantExhibits, owner = Role.plaintiff, turns = 13)

    /** Judge's objection ruling just landed. */
    val afterRuling = state(TrialPhase.defendantExhibits, owner = Role.defendant, turns = 15)

    /** Sam rested after the sustained objection (his Exhibit A is no longer the live subject). */
    val defenceRests = state(TrialPhase.defendantExhibits, owner = null, turns = 16)

    /** My closing is in; Sam is writing his. */
    val closing = state(TrialPhase.defendantClosing, owner = Role.defendant, turns = 22)

    /** Judge asked me questions. */
    val crossExam = state(TrialPhase.crossExamination, owner = Role.plaintiff, turns = 17)

    /** Record closed; jurors 01 and 02 are done. */
    val deliberating = state(null, owner = null, turns = allTurns.size, status = CaseStatus.deliberating, progress = 2)

    /** Ruling written and sealed until trial_at. */
    val awaitingVerdict = state(null, owner = null, turns = allTurns.size, status = CaseStatus.awaitingVerdict, progress = 4)

    /** Verdict revealed. */
    val verdictIn = state(null, owner = null, turns = allTurns.size, status = CaseStatus.verdict, verdict = verdict)

    /** Tie verdict. */
    val verdictTie = state(null, owner = null, turns = allTurns.size, status = CaseStatus.verdict, verdict = tieVerdict)

    /** Safety valve fired. */
    val safetyNotice: CourtroomState = run {
        val s = state(TrialPhase.plaintiffClosing, owner = null, turns = 21)
        s.copy(turns = s.turns + Turn(
            id = uuid(0x2000), caseId = caseId, phase = TrialPhase.plaintiffClosing, speaker = Speaker.judge,
            body = "I'm stepping out of the game here. What was just said sounds like it might be more than a playful argument, and that isn't something an app should rule on. This case has been stopped.",
            aiCall = AICall.safety, meta = JSONValue.Obj(mapOf("safety" to JSONValue.Bool(true))), createdAt = Instant.now(),
        ))
    }

    /** The same scene seen from Sam's phone. */
    fun asPartner(s: CourtroomState): CourtroomState = s.copy(me = s.partner, partner = s.me, myRole = s.myRole?.other)

    // MARK: Settle Outside Court (amendment n)

    val settlementId: UUID = uuid(0x5000)

    fun settlementRow(status: SettlementStatus, round: Int, initiator: Profile = sam): Settlement = Settlement(
        id = settlementId, caseId = caseId, status = status, initiatedBy = initiator.id, currentRound = round,
        entryPoint = "trial", pausedFromStatus = CaseStatus.trial, pausedPhase = TrialPhase.plaintiffExhibits,
        pausedTurnOwner = Role.plaintiff, pausedRemainingSeconds = 6 * 3600, expiresAt = now(11 * 3600.0),
        acceptedAt = if (status.isAgreed) now(-600.0) else null,
        dueAt = if (status.isAgreed) now(3 * 86_400.0 + 3600) else null,
        acceptedOfferId = if (status.isAgreed) uuid(0x5001 + round) else null,
        createdAt = now(-2 * 3600.0),
    )

    fun offer(round: Int, by: Profile, body: String, source: SettlementSource = SettlementSource.ai): SettlementOffer = SettlementOffer(
        id = uuid(0x5001 + round), settlementId = settlementId, roundNumber = round, proposedBy = by.id, body = body,
        source = source, category = "home", dueDays = 3, createdAt = now((-3 + round) * 1800.0),
        expiresAt = now(11 * 3600.0),
    )

    /** Mid-exhibits, a settlement is pending: timers paused (`deadline_at` null), `settlement_id` set. */
    fun settlementState(settlement: Settlement, offer: SettlementOffer): CourtroomState {
        val s = state(TrialPhase.plaintiffExhibits, owner = Role.plaintiff, turns = 8, deadlineIn = null)
        return s.copy(kase = s.kase.copy(settlementId = settlement.id), settlement = settlement, settlementOffer = offer)
    }

    /** Round 2: Sam countered; the offer waits for me (Aria). Open settlement → response sheet. */
    val settlementPending = settlementState(
        settlementRow(SettlementStatus.countered, round = 2, initiator = aria),
        offer = offer(2, by = sam, body = "Thermostat at 22° and I buy the fluffy socks.", source = SettlementSource.custom),
    )

    /** Round 1: my offer is out; Sam hasn't answered. Open settlement → the room's waiting view. */
    val settlementPendingMine = settlementState(
        settlementRow(SettlementStatus.proposed, round = 1, initiator = aria),
        offer = offer(1, by = aria, body = "Heating at 21° all week + I make the cocoa."),
    )

    /** Accepted: the case closed as `closed_settled`, the judge's flavour turn on the record. */
    val settled: CourtroomState = run {
        val s = state(null, owner = null, turns = 8, status = CaseStatus.closedSettled, deadlineIn = null)
        s.copy(
            kase = s.kase.copy(closedAt = now(-600.0)),
            settlement = settlementRow(SettlementStatus.accepted, round = 2, initiator = aria),
            settlementOffer = offer(2, by = sam, body = "Thermostat at 22° and I buy the fluffy socks.", source = SettlementSource.custom),
            turns = s.turns + Turn(id = uuid(0x5100), caseId = caseId, speaker = Speaker.judge, body = CourtroomLogic.settledJudgeLine,
                aiCall = AICall.phaseLine, createdAt = now(-590.0)),
        )
    }

    /** My turn presenting exhibits, and the store allows a settlement: the case-actions menu shows. */
    val settleable: CourtroomState = presenting.copy(canProposeSettlement = true)

    // MARK: Judgement (amendment j)

    val thermostatOption = JudgementOption(
        id = "thermostat_21", title = "Thermostat at 21° all week",
        detail = "Keep the heating at 21° every evening for the next 7 days.", type = JudgementOptionType.directRemedy, dueDays = 7,
    )
    val socksOption = JudgementOption(
        id = "sock_treaty", title = "The 22° sock treaty",
        detail = "Meet at 22° and share the fluffy socks on alternate nights.", type = JudgementOptionType.compromise, dueDays = 7,
    )

    /** Sunday at 20:00 after today ("Due: Sunday"). */
    val nextSunday: Instant
        get() {
            val zone = ZoneId.systemDefault()
            val from = LocalDateTime.now(zone).plusDays(2)
            var d = from.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)).atTime(20, 0)
            if (!d.isAfter(from)) d = d.plusWeeks(1)
            return d.atZone(zone).toInstant()
        }

    /** The judge's delivery turn (in persona; the scene falls back to the template without it). */
    val deliveryTurn = Turn(
        id = uuid(0x4000), caseId = caseId, speaker = Speaker.judge,
        body = "The court finds for Aria. The prevailing party has selected their judgement. Sam is hereby ordered to keep the heating at 21° every evening for the next seven days. The court considers this matter settled.",
        aiCall = AICall.judgementDelivery, createdAt = now(-3500.0),
    )

    /** The court's own delivery on a tie (amendment l): nobody chose. */
    val tieDeliveryTurn = Turn(
        id = uuid(0x4001), caseId = caseId, speaker = Speaker.judge,
        body = "The court could not separate you. It has chosen a resolution for you both. Both parties are hereby ordered to meet at 22° and share the fluffy socks on alternate nights. The court considers this matter settled.",
        aiCall = AICall.judgementDelivery, createdAt = now(-3500.0),
    )

    /** `chooser: null` = the court chose (tie, amendment l). */
    fun judgement(status: JudgementStatus, option: JudgementOption? = thermostatOption, chooser: Profile? = aria): Judgement {
        val selected = if (status == JudgementStatus.pendingSelection) null else option
        return Judgement(
            caseId = caseId, verdictId = verdict.id, theme = "home", status = status, chooserId = chooser?.id,
            selected = selected, selectedBy = if (selected == null) null else chooser?.id,
            selectedAt = if (selected == null) null else now(-3600.0),
            deliveredTurnId = if (selected == null) null else (if (chooser == null) tieDeliveryTurn.id else deliveryTurn.id),
            acceptedAt = if (chooser != null && (status == JudgementStatus.accepted || status == JudgementStatus.served)) now(-1800.0) else null,
            declinedAt = if (status == JudgementStatus.declined) now(-1800.0) else null,
            servedAt = if (status == JudgementStatus.served) now(-600.0) else null,
            servedBy = if (status == JudgementStatus.served) sam.id else null,
            dueAt = if (selected == null) null else nextSunday,
        )
    }

    fun judgementState(
        status: JudgementStatus,
        caseStatus: CaseStatus = CaseStatus.verdict,
        verdict: Verdict = this.verdict,
        option: JudgementOption? = thermostatOption,
        withTurn: Boolean = true,
    ): CourtroomState {
        val s = state(null, owner = null, turns = allTurns.size, status = caseStatus, verdict = verdict, deadlineIn = null)
        val tie = verdict.isTie
        val j = judgement(status, option = if (tie) socksOption else option, chooser = if (tie) null else aria)
        val turns = if (status != JudgementStatus.pendingSelection && withTurn) s.turns + (if (tie) tieDeliveryTurn else deliveryTurn) else s.turns
        return s.copy(judgement = j, turns = turns)
    }

    /** Revealed; I (Aria, the winner) choose. */
    val judgementPending = judgementState(JudgementStatus.pendingSelection)

    /** Revealed; Sam's phone while Aria chooses. */
    val judgementPendingOther = asPartner(judgementPending)

    /** Tie, before the court has picked (options still generating): nobody chooses. */
    val judgementTiePending = judgementState(JudgementStatus.pendingSelection, verdict = tieVerdict)

    /** Tie: the court chose and delivered a compromise (Sam's phone): Mark as served, or quietly decline. */
    val judgementTieDelivered = asPartner(judgementState(JudgementStatus.delivered, verdict = tieVerdict))

    /** Tie delivered before the delivery turn arrived: the scene reads the tie template. */
    val judgementTieTemplate = judgementState(JudgementStatus.delivered, verdict = tieVerdict, withTurn = false)

    /** Tie resolution served. */
    val judgementTieServed = judgementState(JudgementStatus.served, caseStatus = CaseStatus.closed, verdict = tieVerdict)

    /** Delivered, on Sam's (the loser's) phone: accept or decline. */
    val judgementDelivered = asPartner(judgementState(JudgementStatus.delivered))

    /** Delivered, on Aria's phone (template text: the delivery turn hasn't arrived yet). */
    val judgementDeliveredWinner = judgementState(JudgementStatus.delivered, withTurn = false)

    /** Accepted, case closed; Sam can mark it served. */
    val judgementAccepted = asPartner(judgementState(JudgementStatus.accepted, caseStatus = CaseStatus.closed))

    /** Served, case closed. */
    val judgementServed = judgementState(JudgementStatus.served, caseStatus = CaseStatus.closed)

    /** Declined (Aria's phone). */
    val judgementDeclined = judgementState(JudgementStatus.declined, caseStatus = CaseStatus.closed)

    val actions: CourtroomActions get() = CourtroomActions.noop

    val all: List<Pair<String, CourtroomState>> = listOf(
        "opening" to openingMyTurn, "waiting" to waitingForPartner, "present" to presentExhibits,
        "presenting" to presenting, "objection" to objectionWindow, "ruling" to afterRuling, "rests" to defenceRests,
        "cross" to crossExam, "closing" to closing,
        "deliberating" to deliberating, "awaiting" to awaitingVerdict, "verdict" to verdictIn, "tie" to verdictTie,
        "safety" to safetyNotice,
        "jpending" to judgementPending, "jpendingother" to judgementPendingOther, "jtie" to judgementTiePending,
        "jtiedelivered" to judgementTieDelivered, "jtietemplate" to judgementTieTemplate, "jtieserved" to judgementTieServed,
        "jdelivered" to judgementDelivered, "jdeliveredwinner" to judgementDeliveredWinner,
        "jaccepted" to judgementAccepted, "jserved" to judgementServed, "jdeclined" to judgementDeclined,
        "settlementPending" to settlementPending, "settlementPendingMine" to settlementPendingMine, "settled" to settled,
        "settleable" to settleable,
    )
}
