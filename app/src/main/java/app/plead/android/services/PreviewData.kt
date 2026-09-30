// Port of ArgueWin/Preview Content/PreviewData.swift: fixtures for previews, tests and the demo harness (`AWDemo`).
// Two profiles, a linked couple, cases in several states, exhibits, turns and verdicts. No network.
package app.plead.android.services

import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.models.Avatar
import app.plead.android.models.Case
import app.plead.android.models.CaseStatus
import app.plead.android.models.Couple
import app.plead.android.models.Exhibit
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import app.plead.android.models.AICall
import app.plead.android.models.JSONValue
import app.plead.android.models.Judgement
import app.plead.android.models.JudgementOption
import app.plead.android.models.JudgementOptionSet
import app.plead.android.models.JudgementOptionType
import app.plead.android.models.JudgementStatus
import app.plead.android.models.JurorReview
import app.plead.android.models.JurorRole
import app.plead.android.models.ObjectionReason
import app.plead.android.models.ObjectionRuling
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

object PreviewData {
    val meId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    val partnerId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    val coupleId: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")

    /**
     * The Supabase anonymous user THAT'S ME creates in demo runs (amendment p). Signing in to the
     * existing account instead (identity conflict) switches to `meId`.
     */
    val anonId: UUID = UUID.fromString("44444444-4444-4444-4444-444444444444")

    const val hour: Long = 3600
    const val day: Long = 86_400

    /** Swift `.now.addingTimeInterval(seconds)`. */
    private fun now(seconds: Long = 0): Instant = Instant.now().plusSeconds(seconds)

    val meAvatar = Avatar(skin = 1, hair = 2, hairstyle = Avatar.Hairstyle.ponytail, top = 0, outfit = Avatar.Outfit.hoodie)
    val partnerAvatar = Avatar(skin = 3, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.shirt)

    /** An established account: onboarding finished long ago (skips the onboarding flow). */
    val me = Profile(
        id = meId, displayName = "Sam", avatar = meAvatar, coupleId = coupleId,
        onboardingCompletedAt = now(-120 * day), createdAt = now(-120 * day),
    )

    /**
     * A brand-new account mid-onboarding (demo `AWOnboardStep 6…9`): "Arif", partner not yet linked
     * (amendment aw: no typed partner name).
     */
    val onboardingMe = Profile(id = meId, displayName = "Arif", avatar = Avatar(skin = 3, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.hoodie))
    val partner = Profile(id = partnerId, displayName = "Alex", avatar = partnerAvatar, coupleId = coupleId, createdAt = now(-119 * day))

    /** Plead is a paid app: the default fixture couple is subscribed (paid by the partner). */
    val couple = Couple(
        id = coupleId, inviteCode = "KX7P2Q", inviteExpiresAt = now(-100 * day),
        premiumUntil = now(300 * day), payerUserId = partnerId,
        linkedAt = now(-118 * day), createdAt = now(-120 * day),
    )
    val premiumCouple = couple

    /** Linked but not subscribed: lands on the paywall gate. */
    val unpaidCouple: Couple = couple.copy(premiumUntil = null, payerUserId = null)
    val unlinkedCouple = Couple(id = coupleId, inviteCode = "KX7P2Q", inviteExpiresAt = now(6 * day))

    // MARK: Cases

    fun id(n: Int): UUID = UUID.fromString("AAAAAAAA-0000-0000-0000-%012d".format(n))

    val summonedCase = Case(
        id = id(15), coupleId = coupleId, caseNumber = 15, title = "The Thermostat Incident",
        plaintiffId = partnerId, defendantId = meId, status = CaseStatus.summoned,
        charge = "The defendant set the thermostat to 17°C \"to save the planet\" while the plaintiff was wearing a hat indoors.",
        remedyRequested = "Thermostat at 21°C for a week, and the defendant makes the hot chocolate.",
        deadlineAt = now(50 * hour), createdAt = now(-22 * hour), updatedAt = now(-22 * hour),
    )

    val trialCase = Case(
        id = id(14), coupleId = coupleId, caseNumber = 14, title = "The Last Slice",
        plaintiffId = meId, defendantId = partnerId, status = CaseStatus.trial, phase = TrialPhase.plaintiffExhibits, phaseTurnOwner = Role.plaintiff,
        charge = "The defendant ate the last slice of pizza, clearly labelled with the plaintiff's name.",
        remedyRequested = "Defendant buys the next pizza and gives up the crust rights.",
        plea = Plea.notGuilty, proposedTrialAt = now(20 * hour), proposalCount = 0,
        trialAt = now(20 * hour), deadlineAt = now(9 * hour),
        createdAt = now(-3 * day), updatedAt = now(-1 * hour),
    )

    val schedulingCase = Case(
        id = id(13), coupleId = coupleId, caseNumber = 13, title = "The Dishwasher Doctrine",
        plaintiffId = meId, defendantId = partnerId, status = CaseStatus.scheduling,
        charge = "Bowls on the top rack. Again.", remedyRequested = "A laminated loading diagram, signed.",
        plea = Plea.notGuilty, proposedTrialAt = now(30 * hour), proposalCount = 0,
        deadlineAt = now(20 * hour), createdAt = now(-2 * day), updatedAt = now(-3 * hour),
    )

    val defenceCase = Case(
        id = id(16), coupleId = coupleId, caseNumber = 16, title = "The Spoiler",
        plaintiffId = partnerId, defendantId = meId, status = CaseStatus.defence,
        charge = "Revealed the ending of the show 'by accident' via a meaningful look.",
        remedyRequested = "Defendant watches the next season blindfolded until the plaintiff catches up.",
        plea = Plea.notGuilty, deadlineAt = now(30 * hour), createdAt = now(-1 * day), updatedAt = now(-2 * hour),
    )

    val awaitingCase: Case = trialCase.copy(
        status = CaseStatus.awaitingVerdict, phase = null, phaseTurnOwner = null, trialAt = now(40 * 60),
        panelProgress = 4, deliberatingAt = now(-20 * 60),
    )

    /** Case #021 mid-deliberation: jurors 01 and 02 have reported (panel_progress = 2). */
    val deliberatingCase = Case(
        id = id(21), coupleId = coupleId, caseNumber = 21, title = "The Instagram Like Incident",
        plaintiffId = meId, defendantId = partnerId, status = CaseStatus.deliberating,
        charge = "The defendant liked an ex's beach photo from 2019 at 01:12 and called it \"a thumb slip\".",
        remedyRequested = "Defendant unfollows, and plans Saturday's date night start to finish.",
        plea = Plea.notGuilty, proposedTrialAt = now(3 * hour + 42 * 60 + 16), proposalCount = 0,
        trialAt = now(3 * hour + 42 * 60 + 16), panelProgress = 2, deliberatingAt = now(-2 * 60),
        createdAt = now(-2 * day), updatedAt = now(-60),
    )

    val wonCase = Case(
        id = id(12), coupleId = coupleId, caseNumber = 12, title = "The Great Duvet Heist",
        plaintiffId = meId, defendantId = partnerId, status = CaseStatus.closed,
        charge = "The defendant stole the entire duvet at 3am and denied it at breakfast.",
        remedyRequested = "Defendant sleeps with the spare blanket for three nights.",
        plea = Plea.notGuilty, trialAt = now(-9 * day), verdictAt = now(-9 * day),
        createdAt = now(-12 * day), updatedAt = now(-8 * day), closedAt = now(-8 * day),
    )

    /** Guilty plea: Alex (plaintiff) chose the judgement; I accepted but it is now two days overdue. */
    val guiltyCase = Case(
        id = id(11), coupleId = coupleId, caseNumber = 11, title = "The Unreplied Text",
        plaintiffId = partnerId, defendantId = meId, status = CaseStatus.closedGuilty,
        charge = "Left on read for four hours while posting stories.", remedyRequested = "Breakfast in bed on Sunday.",
        plea = Plea.guilty, createdAt = now(-6 * day), updatedAt = now(-6 * day), closedAt = now(-6 * day),
    )

    /** A tie closed yesterday: the court chose a compromise for both (amendment l), due in 3 days. */
    val dinnerTieCase = Case(
        id = id(17), coupleId = coupleId, caseNumber = 17, title = "The Dinner Reservation",
        plaintiffId = meId, defendantId = partnerId, status = CaseStatus.closed,
        charge = "Booked the Italian place without asking, again.", remedyRequested = "The next booking is mine to make.",
        plea = Plea.notGuilty, trialAt = now(-2 * day), verdictAt = now(-2 * day),
        createdAt = now(-5 * day), updatedAt = now(-1 * day), closedAt = now(-1 * day),
    )

    val tiedCase = Case(
        id = id(10), coupleId = coupleId, caseNumber = 10, title = "The Playlist Dispute",
        plaintiffId = partnerId, defendantId = meId, status = CaseStatus.closed,
        charge = "Skipped every song on the road-trip playlist.", remedyRequested = "Full DJ rights for a month.",
        plea = Plea.notGuilty, createdAt = now(-30 * day), updatedAt = now(-28 * day), closedAt = now(-28 * day),
    )

    val mistrialCase = Case(
        id = id(9), coupleId = coupleId, caseNumber = 9, title = "The Houseplant Neglect",
        plaintiffId = meId, defendantId = partnerId, status = CaseStatus.mistrial,
        charge = "Did not water Gerald.", remedyRequested = "A new Gerald.",
        createdAt = now(-40 * day), updatedAt = now(-39 * day), closedAt = now(-39 * day),
    )

    val olderClosed: List<Case> = (5..8).map { n ->
        Case(
            id = id(n), coupleId = coupleId, caseNumber = n,
            title = listOf("The Parking Spot", "The Borrowed Hoodie", "The Alarm Snooze", "The Takeaway Order")[n - 5],
            plaintiffId = if (n % 2 == 0) meId else partnerId, defendantId = if (n % 2 == 0) partnerId else meId, status = CaseStatus.closed,
            charge = "See exhibits.", remedyRequested = "An apology in writing.",
            createdAt = now(-(60L + n) * day), updatedAt = now(-(58L + n) * day), closedAt = now(-(58L + n) * day),
        )
    }

    val allCases: List<Case> = listOf(
        defenceCase, summonedCase, trialCase, schedulingCase, deliberatingCase, wonCase, dinnerTieCase, guiltyCase, tiedCase, mistrialCase,
    ) + olderClosed

    // MARK: Exhibits

    val exhibits: List<Exhibit> = listOf(
        Exhibit(
            id = id(101), caseId = wonCase.id, ownerId = meId, label = ExhibitLabel.A, type = ExhibitType.receipt, caption = "Timeline",
            body = "12 Sept, 03:04: woke up with zero duvet", weight = 3, occurredAt = now(-13 * day), sort = 0,
        ),
        Exhibit(
            id = id(102), caseId = wonCase.id, ownerId = meId, label = ExhibitLabel.B, type = ExhibitType.photo,
            storagePath = "${coupleId.uuidString}/${wonCase.id.uuidString}/${id(102).uuidString}",
            caption = "The duvet burrito", weight = 2, sort = 1,
        ),
        Exhibit(
            id = id(103), caseId = wonCase.id, ownerId = partnerId, label = ExhibitLabel.A, type = ExhibitType.text, caption = "Their own words",
            body = "I run cold, it's medical", objectionReason = ObjectionReason.speculation, objectionRuling = ObjectionRuling.sustained, weight = 0, sort = 0,
        ),
        Exhibit(
            id = id(104), caseId = trialCase.id, ownerId = meId, label = ExhibitLabel.A, type = ExhibitType.photo,
            storagePath = "${coupleId.uuidString}/${trialCase.id.uuidString}/${id(104).uuidString}",
            caption = "The labelled box", sort = 0,
        ),
        Exhibit(
            id = id(105), caseId = trialCase.id, ownerId = meId, label = ExhibitLabel.B, type = ExhibitType.text, caption = "The group chat",
            body = "\"Don't touch my slice\" — sent 19:02", sort = 1,
        ),
        Exhibit(
            id = id(106), caseId = trialCase.id, ownerId = partnerId, label = ExhibitLabel.A, type = ExhibitType.receipt, caption = "The hunger",
            body = "14 Sept, 23:40: hadn't eaten since lunch", sort = 0,
        ),
    )

    // MARK: Turns

    val turns: List<Turn> = listOf(
        Turn(
            id = id(201), caseId = wonCase.id, phase = null, speaker = Speaker.defendant,
            body = "I was asleep. You cannot steal in your sleep. That's not how theft works.",
            meta = JSONValue.Obj(mapOf("kind" to JSONValue.Str("defence"))), createdAt = now(-11 * day),
        ),
        Turn(
            id = id(202), caseId = wonCase.id, phase = TrialPhase.plaintiffOpening, speaker = Speaker.judge,
            body = "The court is in session. The plaintiff will open. Briefly, one hopes.", aiCall = AICall.phaseLine, createdAt = now(-10 * day),
        ),
        Turn(
            id = id(203), caseId = wonCase.id, phase = TrialPhase.plaintiffOpening, speaker = Speaker.plaintiff,
            body = "At 3:04am I awoke in the arctic. The defendant was a duvet burrito.", createdAt = now(-10 * day + 60),
        ),
        Turn(
            id = id(204), caseId = wonCase.id, phase = TrialPhase.defendantOpening, speaker = Speaker.defendant,
            body = "I run cold. This is a medical matter, not a criminal one.", createdAt = now(-10 * day + 120),
        ),
        Turn(
            id = id(205), caseId = wonCase.id, phase = TrialPhase.crossExamination, speaker = Speaker.judge,
            body = "1. Has the defendant ever woken up without the duvet? 2. Is there a spare blanket in the house?", aiCall = AICall.crossExamine,
            meta = JSONValue.Obj(
                mapOf(
                    "questions" to JSONValue.Arr(listOf(JSONValue.Str("Has the defendant ever woken up without the duvet?"), JSONValue.Str("Is there a spare blanket in the house?"))),
                    "side" to JSONValue.Str("defendant"),
                ),
            ),
            createdAt = now(-10 * day + 180),
        ),
        Turn(
            id = id(206), caseId = trialCase.id, phase = TrialPhase.plaintiffOpening, speaker = Speaker.judge,
            body = "The court notes a pizza box. The plaintiff may begin.", aiCall = AICall.phaseLine, createdAt = now(-5 * hour),
        ),
        Turn(
            id = id(207), caseId = trialCase.id, phase = TrialPhase.plaintiffOpening, speaker = Speaker.plaintiff,
            body = "It had my name on it. In marker. Underlined.", createdAt = now(-4 * hour),
        ),
        Turn(
            id = id(208), caseId = trialCase.id, phase = TrialPhase.defendantOpening, speaker = Speaker.defendant,
            body = "The name was on the box, not the slice. The slice was unlabelled.", createdAt = now(-3 * hour),
        ),
    )

    // MARK: Verdicts

    private fun votes(p: Double, d: Double, t: Double) =
        JSONValue.Obj(mapOf("plaintiff" to JSONValue.Num(p), "defendant" to JSONValue.Num(d), "tie" to JSONValue.Num(t)))

    val verdicts: List<Verdict> = listOf(
        Verdict(
            id = id(301), caseId = wonCase.id, kind = VerdictKind.ruling, winnerId = meId, isTie = false,
            recap = "The plaintiff says the duvet was taken wholesale at 3am. The defendant pleads a medical need for warmth and a lack of intent.",
            findings = listOf(
                VerdictFinding(exhibitId = id(101), label = "Exhibit A", side = Role.plaintiff, finding = "The court finds the 03:04 timestamp damning.", weight = 3),
                VerdictFinding(exhibitId = id(102), label = "Exhibit B", side = Role.plaintiff, finding = "The burrito photograph speaks for itself.", weight = 2),
                VerdictFinding(exhibitId = id(103), label = "Exhibit A", side = Role.defendant, finding = "Objection sustained; the medical claim is speculation.", weight = 0),
            ),
            sentence = "The defendant shall sleep with the spare blanket for three nights.",
            closingLine = "The court notes that warmth, like justice, must be shared.",
            panelSplit = "2-1", panelVotes = votes(2.0, 1.0, 0.0),
            confidenceLabel = "medium", modelRef = "claude-sonnet-5", promptVersion = "verdict-v2",
        ),
        Verdict(
            id = id(302), caseId = tiedCase.id, kind = VerdictKind.ruling, winnerId = null, isTie = true,
            recap = "Both parties skipped songs. Both parties are ridiculous.", findings = emptyList(),
            sentence = "Alternate songs for the next road trip.", closingLine = "The court finds you both guilty of poor taste.",
            panelSplit = "1-1-1", panelVotes = votes(1.0, 1.0, 1.0),
            confidenceLabel = "low",
        ),
        Verdict(
            id = id(303), caseId = guiltyCase.id, kind = VerdictKind.guilty, winnerId = partnerId, isTie = false,
            recap = "The defendant pleaded guilty.", findings = emptyList(),
            sentence = DemoTrialSimulator.judgementPendingSentence, closingLine = "Honesty is noted. Breakfast is expected.",
        ),
        Verdict(
            id = id(304), caseId = dinnerTieCase.id, kind = VerdictKind.ruling, winnerId = null, isTie = true,
            recap = "One party booked without asking; the other never books at all. The panel could not separate them.", findings = emptyList(),
            sentence = DemoTrialSimulator.judgementPendingSentence, closingLine = "The court suggests a table for two, chosen together.",
            panelSplit = "1-1-1", panelVotes = votes(1.0, 1.0, 1.0),
            confidenceLabel = "low", createdAt = now(-2 * day),
        ),
    ) + olderClosed.map { c ->
        Verdict(
            id = UUID.randomUUID(), caseId = c.id, kind = VerdictKind.ruling, winnerId = if (c.caseNumber % 3 == 0) meId else partnerId, isTie = false,
            recap = "", findings = emptyList(), sentence = "An apology in writing.", closingLine = "So ordered.",
        )
    }

    // MARK: Juror reviews (the 2-1 panel behind the duvet verdict)

    private fun summary(text: String) = JSONValue.Obj(mapOf("summary" to JSONValue.Str(text)))

    val jurorReviews: List<JurorReview> = listOf(
        JurorReview(
            id = id(401), caseId = wonCase.id, jurorRole = JurorRole.evidence,
            findings = summary("The 03:04 timeline and the burrito photograph carry the plaintiff's case; the defence offered no exhibit of its own."),
            preferredWinnerId = meId, isTie = false, confidence = 0.82,
        ),
        JurorReview(
            id = id(402), caseId = wonCase.id, jurorRole = JurorRole.consistency,
            findings = summary("\"I was asleep\" and \"I run cold, it's medical\" cannot both explain the same duvet."),
            preferredWinnerId = meId, isTie = false, confidence = 0.71,
        ),
        JurorReview(
            id = id(403), caseId = wonCase.id, jurorRole = JurorRole.fairness,
            findings = summary("A spare blanket exists. Shared custody of the duvet would be the proportionate remedy."),
            preferredWinnerId = partnerId, isTie = false, confidence = 0.55,
        ),
    )

    val exhibitURLs: Map<UUID, URI> = mapOf(
        id(102) to URI("https://picsum.photos/seed/duvet/400"),
        id(104) to URI("https://picsum.photos/seed/pizza/400"),
    )

    // MARK: Court judgement (amendment j)

    /** "The Last Slice" (#014) with its verdict just revealed: the judgement flow's demo case. */
    val judgementCase: Case = trialCase.copy(
        status = CaseStatus.verdict, phase = null, phaseTurnOwner = null, panelProgress = 4, deadlineAt = null,
        verdictAt = now(-12 * 60), updatedAt = now(-12 * 60),
    )

    fun judgementVerdict(iWon: Boolean): Verdict = Verdict(
        id = id(310), caseId = judgementCase.id, kind = VerdictKind.ruling, winnerId = if (iWon) meId else partnerId, isTie = false,
        recap = "The plaintiff says the labelled slice was eaten. The defendant says the label was on the box, not the slice.",
        findings = listOf(
            VerdictFinding(exhibitId = id(105), label = "Exhibit B", side = Role.plaintiff, finding = "\"Don't touch my slice\" leaves little room for interpretation.", weight = 3),
        ),
        sentence = DemoTrialSimulator.judgementPendingSentence, closingLine = "Justice is served. So, hopefully, is dinner.",
        panelSplit = "2-1", panelVotes = votes(if (iWon) 2.0 else 1.0, if (iWon) 1.0 else 2.0, 0.0),
        confidenceLabel = "medium", createdAt = now(-12 * 60),
    )

    /** Round 0 for a food case (brief §3), addressed to the loser. */
    fun foodOptions(winner: String): List<JudgementOption> = listOf(
        JudgementOption(id = "replace_slice", title = "Replace the last slice", detail = "Buy $winner a fresh pizza within 3 days.", type = JudgementOptionType.directRemedy, dueDays = 3),
        JudgementOption(id = "dinner_out", title = "Take $winner out for dinner this week", detail = "Plan and book dinner out for the two of you within 7 days.", type = JudgementOptionType.effort, dueDays = 7),
        JudgementOption(id = "favourite_meal", title = "Cook $winner's favourite meal", detail = "Cook $winner's favourite meal at home within 5 days.", type = JudgementOptionType.effort, dueDays = 5),
        JudgementOption(id = "next_takeaway", title = "$winner chooses the next takeaway", detail = "Let $winner choose the next takeaway, with no veto.", type = JudgementOptionType.privilege, dueDays = 7),
    )

    fun judgementFixture(status: JudgementStatus, iWon: Boolean = true, caseId: UUID = judgementCase.id): Judgement {
        val chooser = if (iWon) meId else partnerId
        var j = Judgement(
            caseId = caseId, verdictId = id(310), theme = "food", status = status, chooserId = chooser,
            createdAt = now(-12 * 60), updatedAt = now(-5 * 60),
        )
        if (status == JudgementStatus.pendingSelection) return j
        j = j.copy(
            selected = foodOptions(if (iWon) me.displayName else partner.displayName)[1],
            selectedBy = chooser, selectedAt = now(-8 * 60), deliveredTurnId = id(209), dueAt = now(4 * day),
        )
        if (status == JudgementStatus.accepted || status == JudgementStatus.served) j = j.copy(acceptedAt = now(-6 * 60))
        if (status == JudgementStatus.served) j = j.copy(servedAt = now(-60), servedBy = chooser)
        if (status == JudgementStatus.declined) j = j.copy(declinedAt = now(-6 * 60))
        return j
    }

    /** Every status, for the status-card preview. */
    val judgementStates: List<Judgement> get() = JudgementStatus.entries.map { judgementFixture(it, iWon = it != JudgementStatus.delivered) }

    /** The judge's delivery turn (brief screen C template). */
    fun deliveryTurn(iWon: Boolean): Turn {
        val winner = if (iWon) me.displayName else partner.displayName
        val loser = if (iWon) partner.displayName else me.displayName
        return Turn(
            id = id(209), caseId = judgementCase.id, speaker = Speaker.judge,
            body = "The court finds for $winner. The prevailing party has selected their judgement. $loser is hereby ordered to plan and book dinner out for the two of you within 7 days. The court considers this matter settled.",
            aiCall = AICall.judgementDelivery, meta = JSONValue.Obj(mapOf("judgement" to JSONValue.Bool(true))), createdAt = now(-8 * 60),
        )
    }

    /** The duvet case's judgement: chosen, accepted and served (the docket's gold SERVED stamp). */
    val wonCaseJudgement: Judgement = Judgement(
        caseId = wonCase.id, theme = "sleep", status = JudgementStatus.served, chooserId = meId,
        selected = JudgementOption(
            id = "spare_blanket", title = "Spare blanket for three nights",
            detail = "Sleep with the spare blanket for the next 3 nights.", type = JudgementOptionType.directRemedy, dueDays = 3,
        ),
        selectedBy = meId, selectedAt = now(-9 * day), createdAt = now(-9 * day),
        acceptedAt = now(-9 * day + hour), servedAt = now(-6 * day), servedBy = partnerId,
        dueAt = now(-6 * day), updatedAt = now(-6 * day),
    )

    /** The tie on #017: court-chosen (`chooser_id` / `selected_by` null), delivered, due in 3 days. */
    val dinnerTieJudgement: Judgement = Judgement(
        caseId = dinnerTieCase.id, verdictId = id(304), theme = "food", status = JudgementStatus.delivered, chooserId = null,
        selected = JudgementOption(
            id = "cook_together", title = "Cook dinner together",
            detail = "Cook one dinner together this week.", type = JudgementOptionType.compromise, dueDays = 7,
        ),
        selectedBy = null, selectedAt = now(-2 * day), deliveredTurnId = id(210),
        createdAt = now(-2 * day), dueAt = now(3 * day + hour), updatedAt = now(-2 * day),
    )

    /** The court's own delivery on the #017 tie. */
    val dinnerTieDeliveryTurn = Turn(
        id = id(210), caseId = dinnerTieCase.id, speaker = Speaker.judge,
        body = "The court could not separate you. It has chosen a resolution for you both. Both parties are hereby ordered to cook one dinner together this week. The court considers this matter settled.",
        aiCall = AICall.judgementDelivery, meta = JSONValue.Obj(mapOf("judgement" to JSONValue.Bool(true))), createdAt = now(-2 * day),
    )

    /** The guilty plea on #011: Alex chose "take them out for dinner"; I accepted; two days overdue. */
    val guiltyOverdueJudgement: Judgement = Judgement(
        caseId = guiltyCase.id, verdictId = id(303), theme = "food", status = JudgementStatus.accepted, chooserId = partnerId,
        selected = JudgementOption(
            id = "dinner_out", title = "Take Alex out for dinner",
            detail = "Plan and book dinner out for the two of you within 3 days.", type = JudgementOptionType.effort, dueDays = 3,
        ),
        selectedBy = partnerId, selectedAt = now(-5 * day), createdAt = now(-6 * day),
        acceptedAt = now(-5 * day + hour), dueAt = now(-2 * day - hour), updatedAt = now(-5 * day + hour),
    )

    /** Judgements in the default fixtures: served (#012), court-chosen tie due (#017), overdue (#011). */
    val fulfilmentJudgements: List<Judgement> = listOf(wonCaseJudgement, dinnerTieJudgement, guiltyOverdueJudgement)

    /**
     * `AWDemoStore judgement` (I won, choosing), `judgementLoser` (Alex is choosing), and the later
     * states for screenshots.
     */
    fun judgementStore(iWon: Boolean = true, status: JudgementStatus = JudgementStatus.pendingSelection, withOptions: Boolean = true): CaseStore {
        val cases = listOf(judgementCase, wonCase, guiltyCase, tiedCase, mistrialCase) + olderClosed
        val winner = if (iWon) me.displayName else partner.displayName
        val options = JudgementOptionSet(
            id = id(320), caseId = judgementCase.id, round = 0, options = foodOptions(winner),
            generatedFor = if (iWon) meId else partnerId, createdAt = now(-11 * 60),
        )
        return CaseStore(
            preview = me, partner = partner, couple = couple, cases = cases, exhibits = exhibits,
            turns = turns + (if (status == JudgementStatus.pendingSelection) emptyList() else listOf(deliveryTurn(iWon))),
            verdicts = verdicts + judgementVerdict(iWon), jurorReviews = jurorReviews, exhibitURLs = exhibitURLs,
            judgements = listOf(judgementFixture(status, iWon), wonCaseJudgement, guiltyOverdueJudgement),
            judgementOptions = if (withOptions) listOf(options) else emptyList(),
        )
    }

    // MARK: Settle Outside Court (amendment n)

    val settlementId: UUID = id(500)

    /** "The Last Slice" (#014) in trial with a settlement pending: the court's timer is paused. */
    val settlementTrialCase: Case = trialCase.copy(deadlineAt = null, settlementId = settlementId)

    /** Offer terms per round (round 1 from Alex, the counters alternate). */
    val settlementTerms: List<Triple<String, String, Int>> = listOf(
        Triple("Buy a fresh pizza and do the dishes tonight.", "food", 2),
        Triple("Buy a fresh pizza. The crusts are yours.", "custom", 3),
        Triple("Pizza night this weekend, my treat, you pick the toppings.", "food", 5),
    )

    /** Who proposed a round when the offer on the table (round `current`) is addressed to me. */
    fun proposer(of: Int, current: Int): UUID = if ((current - of) % 2 == 0) partnerId else meId

    fun settlementOffer(round: Int, current: Int? = null): SettlementOffer {
        val t = settlementTerms[maxOf(0, minOf(round, 3) - 1)]
        return SettlementOffer(
            id = id(510 + round), settlementId = settlementId, roundNumber = round,
            proposedBy = proposer(round, current ?: round), body = t.first,
            source = if (t.second == "custom") SettlementSource.custom else SettlementSource.ai, category = t.second, dueDays = t.third,
            createdAt = now((round - 4) * 20L * 60), expiresAt = now(11 * hour + 40 * 60),
        )
    }

    /** Round `round` is on the table and awaits MY response. */
    fun settlementPending(round: Int): Settlement = Settlement(
        id = settlementId, caseId = trialCase.id, status = if (round == 1) SettlementStatus.proposed else SettlementStatus.countered,
        initiatedBy = proposer(1, round), currentRound = round, entryPoint = "trial",
        pausedFromStatus = CaseStatus.trial, pausedPhase = trialCase.phase, pausedTurnOwner = trialCase.phaseTurnOwner,
        pausedRemainingSeconds = (9 * hour).toInt(), expiresAt = now(11 * hour + 40 * 60),
        createdAt = now(-70 * 60), updatedAt = now(-5 * 60),
    )

    /** `AWDemoStore settlementOffer` (round 1) / `settlementFinal` (round 3): an offer awaits me. */
    fun settlementOfferStore(round: Int = 1): CaseStore = store(
        cases = allCases.map { if (it.id == trialCase.id) settlementTrialCase else it },
        settlements = listOf(settlementPending(round)),
        settlementOffers = (1..round).map { settlementOffer(it, round) },
    )

    /** #028 "The Dinner Disaster": settled out of court two days ago; the agreement is due in 3 days. */
    val settledCase = Case(
        id = id(28), coupleId = coupleId, caseNumber = 28, title = "The Dinner Disaster",
        plaintiffId = partnerId, defendantId = meId, status = CaseStatus.closedSettled,
        charge = "Changed the dinner order without checking first. The replacement was inedible.",
        remedyRequested = "A replacement dinner, chosen by the plaintiff.",
        createdAt = now(-3 * day), updatedAt = now(-2 * day), closedAt = now(-2 * day),
    )

    val settledOffer = SettlementOffer(
        id = id(530), settlementId = id(520), roundNumber = 2, proposedBy = meId,
        body = "Replace the meal and plan dinner this weekend.", source = SettlementSource.ai, category = "food", dueDays = 5,
        createdAt = now(-2 * day - hour),
    )

    val settledSettlement: Settlement = Settlement(
        id = id(520), caseId = settledCase.id, status = SettlementStatus.accepted, initiatedBy = partnerId, currentRound = 2,
        entryPoint = "summons", pausedFromStatus = CaseStatus.summoned, pausedRemainingSeconds = (40 * hour).toInt(),
        createdAt = now(-2 * day - 2 * hour), updatedAt = now(-2 * day),
        acceptedAt = now(-2 * day), acceptedOfferId = settledOffer.id, dueAt = now(3 * day + hour),
    )

    val settledFlavourTurn = Turn(
        id = id(531), caseId = settledCase.id, speaker = Speaker.judge,
        body = "The parties have spared the court the trouble. Miracles do happen.",
        meta = JSONValue.Obj(mapOf("settlement" to JSONValue.Bool(true))), createdAt = now(-2 * day),
    )

    /** `AWDemoStore settled`: #028 closed as settled, fulfilment outstanding (plus the usual docket). */
    fun settledStore(): CaseStore = store(
        cases = allCases + settledCase, turns = listOf(settledFlavourTurn),
        settlements = listOf(settledSettlement),
        settlementOffers = listOf(
            SettlementOffer(
                id = id(529), settlementId = id(520), roundNumber = 1, proposedBy = partnerId,
                body = "Replace the meal.", source = SettlementSource.ai, category = "food", dueDays = 2,
                createdAt = now(-2 * day - 2 * hour),
            ),
            settledOffer,
        ),
    )

    // MARK: Stores & models

    fun store(
        couple: Couple = PreviewData.couple,
        cases: List<Case> = allCases,
        turns: List<Turn> = emptyList(),
        settlements: List<Settlement> = emptyList(),
        settlementOffers: List<SettlementOffer> = emptyList(),
    ): CaseStore {
        val ids = cases.map { it.id }.toSet()
        return CaseStore(
            preview = me, partner = partner, couple = couple, cases = cases, exhibits = exhibits,
            turns = PreviewData.turns + dinnerTieDeliveryTurn + turns, verdicts = verdicts, jurorReviews = jurorReviews, exhibitURLs = exhibitURLs,
            judgements = fulfilmentJudgements.filter { ids.contains(it.caseId) },
            settlements = settlements, settlementOffers = settlementOffers,
        )
    }

    fun premiumStore(): CaseStore = store(couple = premiumCouple)

    /** Linked couple with no subscription (paywall gate). */
    fun unpaidStore(): CaseStore = store(couple = unpaidCouple, cases = emptyList())

    fun soloStore(): CaseStore = CaseStore(preview = me.copy(coupleId = coupleId), partner = null, couple = unlinkedCouple)

    fun emptyStore(): CaseStore = store(cases = emptyList())

    /** Onboarding screens 7–9: profile saved, couple created (invite open), partner not linked yet. */
    fun onboardingSoloStore(): CaseStore = CaseStore(
        preview = onboardingMe.copy(coupleId = coupleId), partner = null,
        couple = unlinkedCouple.copy(togetherSince = LocalDate.of(2024, 2, 14).atStartOfDay(ZoneId.systemDefault()).toInstant()),
    )

    /** Home / Court demo: case #021 deliberating plus the closed history. */
    fun deliberatingStore(): CaseStore = store(cases = listOf(deliberatingCase, wonCase, guiltyCase, tiedCase, mistrialCase) + olderClosed)

    /** Joined a couple the partner already paid for: the gate greets with "your partner already unlocked". */
    fun partnerPaidModel(defaults: UserDefaults = UserDefaults.inMemory()): AppModel {
        val m = model(store(couple = couple, cases = emptyList()), defaults = defaults)
        m.gateLatched = true
        return m
    }

    fun model(store: CaseStore = store(), signedIn: Boolean = true, defaults: UserDefaults = UserDefaults.inMemory()): AppModel = AppModel(
        auth = AuthService(previewUserId = if (signedIn) meId else null, backend = demoAuthBackend(if (signedIn) meId else null)),
        store = store, purchases = PurchasesService(), push = PushService(), defaults = defaults,
    )

    /**
     * In-memory auth for demo runs: THAT'S ME signs in anonymously as `anonId`; "sign in instead"
     * lands on `meId`. `AWSecureConflict YES` makes linking report "already has a Plead account".
     */
    fun demoAuthBackend(current: UUID?, anonymous: Boolean = false): LocalAuthBackend = LocalAuthBackend(
        current = current?.let { AuthUserState(id = it, isAnonymous = anonymous, email = null) },
        anonymousId = anonId, existingAccountId = meId,
        identityInUse = DemoHarness.secureConflict,
    )

    /** The eight preset court identities (onboarding screen 5); index 0 is the default. Mirror of `OnboardingAvatars`. */
    val onboardingAvatarPresets: List<Avatar> = listOf(
        Avatar(skin = 1, hair = 5, hairstyle = Avatar.Hairstyle.long, top = 3, outfit = Avatar.Outfit.dress),
        Avatar(skin = 3, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.hoodie),
        Avatar(skin = 0, hair = 4, hairstyle = Avatar.Hairstyle.ponytail, top = 0, outfit = Avatar.Outfit.tee),
        Avatar(skin = 4, hair = 0, hairstyle = Avatar.Hairstyle.buzz, top = 6, outfit = Avatar.Outfit.shirt),
        Avatar(skin = 2, hair = 1, hairstyle = Avatar.Hairstyle.bun, top = 4, outfit = Avatar.Outfit.dress),
        Avatar(skin = 5, hair = 0, hairstyle = Avatar.Hairstyle.short, top = 7, outfit = Avatar.Outfit.suit),
        Avatar(skin = 1, hair = 3, hairstyle = Avatar.Hairstyle.short, top = 1, outfit = Avatar.Outfit.hoodie),
        Avatar(skin = 2, hair = 2, hairstyle = Avatar.Hairstyle.long, top = 2, outfit = Avatar.Outfit.shirt),
    )

    /**
     * Amendment p: an anonymous user ("Arif") whose couple just became premium (bought on this phone):
     * the gate shows SecureAccountView.
     */
    fun anonymousPaidModel(defaults: UserDefaults = UserDefaults.inMemory()): AppModel {
        val me = onboardingMe.copy(id = anonId, avatar = onboardingAvatarPresets[0], coupleId = coupleId, onboardingCompletedAt = Instant.now())
        val paid = couple.copy(payerUserId = anonId)
        val store = CaseStore(preview = me, partner = partner, couple = paid)
        val auth = AuthService(previewUserId = anonId, anonymous = true, backend = demoAuthBackend(anonId, anonymous = true))
        return AppModel(auth = auth, store = store, purchases = PurchasesService(), push = PushService(), defaults = defaults)
    }
}
