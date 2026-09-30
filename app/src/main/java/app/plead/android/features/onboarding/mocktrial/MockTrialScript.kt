// Port of ArgueWin/Features/Onboarding/MockTrial/MockTrialScript.swift: the onboarding mock trial script
// (CONTRACTS-v2 amendment aj: a compressed full case). Every beat is data here; the copy is exact from the amendment,
// do not paraphrase. Timing lives in `MockTrialTiming` (MockTrialPlayer, owed after the wave 3a merge).
package app.plead.android.features.onboarding

import app.plead.android.models.Role

/**
 * The twelve beats of amendment aj, in order. Cross-examination is one beat per question/answer pair plus
 * Sam's follow-up, so every pair is its own tap and its own `onboarding_mock_trial_advanced`.
 */
enum class MockTrialBeat(val rawValue: Int) {
    opening(0),
    plaintiffOpening(1), plaintiffEvidence(2),
    defendantOpening(3), defendantEvidence(4),
    crossExamination1(5), crossExamination2(6), crossExamination3(7), crossFollowUp(8),
    closings(9),
    deliberation(10),
    verdict(11),
    judgement(12),
    closed(13);

    val next: MockTrialBeat? get() = fromRaw(rawValue + 1)

    /** The `beat` prop of `onboarding_mock_trial_advanced` / `_skipped`. */
    val analyticsName: String
        get() = when (this) {
            opening -> "opening"
            plaintiffOpening -> "plaintiff_opening"
            plaintiffEvidence -> "plaintiff_evidence"
            defendantOpening -> "defendant_opening"
            defendantEvidence -> "defendant_evidence"
            crossExamination1 -> "cross_examination_1"
            crossExamination2 -> "cross_examination_2"
            crossExamination3 -> "cross_examination_3"
            crossFollowUp -> "cross_examination_follow_up"
            closings -> "closings"
            deliberation -> "deliberation"
            verdict -> "verdict"
            judgement -> "judgement"
            closed -> "closed"
        }

    /** The case name (`AWMockTrialBeat plaintiffOpening`). */
    val debugName: String get() = name

    val isCrossExamination: Boolean
        get() = this == crossExamination1 || this == crossExamination2 || this == crossExamination3 || this == crossFollowUp

    companion object {
        fun fromRaw(raw: Int): MockTrialBeat? = entries.firstOrNull { it.rawValue == raw }

        /** `AWMockTrialBeat` value: the case name (any case), the analytics name or the raw number. */
        fun fromFlag(flag: String): MockTrialBeat? {
            flag.toIntOrNull()?.let { n -> fromRaw(n)?.let { return it } }
            return entries.firstOrNull { it.debugName.lowercase() == flag.lowercase() || it.analyticsName == flag }
        }
    }
}

enum class MockTrialSpeaker { judge, plaintiff, defendant, court }

/** One spoken line: who says it, and the words. */
data class MockTrialLine(val speaker: MockTrialSpeaker, val text: String) {
    val speakerName: String
        get() = when (speaker) {
            MockTrialSpeaker.judge -> MockTrialScript.judgeName
            MockTrialSpeaker.plaintiff -> MockTrialScript.plaintiffName
            MockTrialSpeaker.defendant -> MockTrialScript.defendantName
            MockTrialSpeaker.court -> "The Court"
        }

    /** Which side a party line belongs to (null for the judge and the court). */
    val role: Role?
        get() = when (speaker) {
            MockTrialSpeaker.plaintiff -> Role.plaintiff
            MockTrialSpeaker.defendant -> Role.defendant
            MockTrialSpeaker.judge, MockTrialSpeaker.court -> null
        }

    /** TalkBack: speaker, then the words ("Sam: He ate…"). */
    val accessibilityText: String get() = "$speakerName: $text"
}

/** The two exhibits. */
enum class MockTrialExhibitID { a, b }

/** A mock exhibit: EXHIBIT A is a text-message card (two bubbles), EXHIBIT B a photo-style card with a caption. */
data class MockTrialExhibit(
    val id: MockTrialExhibitID,
    val label: String,
    /** Text messages, in order (EXHIBIT A). */
    val messages: List<MockTrialLine>,
    /** Under the picture (EXHIBIT B). */
    val caption: String?,
) {
    /** "Exhibit A: text message from Sam: Save me the last slice ❤️ Alex: Yeah of course." */
    val accessibilityText: String
        get() {
            val name = label.swiftCapitalized()
            val first = messages.firstOrNull()
            if (first != null) {
                val rest = messages.drop(1).joinToString("") { " ${it.speakerName}: ${it.text}" }
                return "$name: text message from ${first.speakerName}: ${first.text}$rest"
            }
            return "$name: photo. ${caption ?: ""}"
        }
}

/** What appears during a beat, in order (the player reveals them one by one on `MockTrialTiming.partTimes`). */
sealed class MockTrialPart {
    data class say(val value: MockTrialLine) : MockTrialPart()
    data object claim : MockTrialPart()
    data class exhibit(val id: MockTrialExhibitID) : MockTrialPart()
    /** One deliberation panel row (index into `MockTrialScript.deliberationRows`). */
    data class panelRow(val index: Int) : MockTrialPart()
    data object verdictCard : MockTrialPart()
    data object options : MockTrialPart()
    /** The winner's pick highlights itself. */
    data object select : MockTrialPart()

    val line: MockTrialLine? get() = (this as? say)?.value
}

/** One beat: its phase label (+ inline tooltip) and the parts it reveals. */
data class MockTrialStep(val beat: MockTrialBeat, val label: String?, val tooltip: String?, val parts: List<MockTrialPart>) {
    val lines: List<MockTrialLine> get() = parts.mapNotNull { it.line }
}

object MockTrialScript {
    const val caseChip = "CASE DEMO · THE LAST SLICE CASE"
    const val caseNumber = 14
    const val caseTitle = "The Last Slice Case"
    const val plaintiffName = "Sam"
    const val defendantName = "Alex"
    const val judgeName = "Judge Wigsworth"

    // MARK: 1. Intro (the invitation)

    const val introEyebrow = "SEE HOW A PLEAD TRIAL WORKS"
    const val introBody = "We'll take you through a quick mock case before you enter court for real."
    const val introCard = "Case: The Last Slice · Sam v. Alex"
    const val startCTA = "START MOCK TRIAL"
    const val skipCTA = "SKIP DEMO"

    // MARK: 2. Court enters session

    val sessionLine = MockTrialLine(MockTrialSpeaker.judge, "Court is now in session. Today we're hearing Sam v. Alex — The Last Slice.")
    const val claimLabel = "CLAIM"
    const val claimText = "Alex ate the final slice after agreeing to save it."
    /** "CLAIM · Alex ate the final slice after agreeing to save it." */
    val claim: String get() = "$claimLabel · $claimText"

    // MARK: 3–6. Openings and evidence

    const val plaintiffOpeningLabel = "OPENING STATEMENT · PLAINTIFF"
    const val openingTooltip = "Your opening statement tells the court what happened and why you believe you're right."
    val plaintiffOpeningLine = MockTrialLine(MockTrialSpeaker.plaintiff, "He ate the last slice of pizza after promising to save it for me. I'd like a replacement pizza.")

    val exhibitA = MockTrialExhibit(
        id = MockTrialExhibitID.a, label = "EXHIBIT A",
        messages = listOf(
            MockTrialLine(MockTrialSpeaker.plaintiff, "Save me the last slice ❤️"),
            MockTrialLine(MockTrialSpeaker.defendant, "Yeah of course."),
        ),
        caption = null,
    )
    val exhibitANoted = MockTrialLine(MockTrialSpeaker.judge, "Exhibit A is noted.")

    const val defendantOpeningLabel = "OPENING STATEMENT · DEFENDANT"
    val defendantOpeningLine = MockTrialLine(MockTrialSpeaker.defendant, "I thought she meant the last slice at the time. There were two left when I checked later.")

    val exhibitB = MockTrialExhibit(id = MockTrialExhibitID.b, label = "EXHIBIT B", messages = emptyList(), caption = "The box at 9pm: two slices left.")
    val exhibitBNoted = MockTrialLine(MockTrialSpeaker.judge, "Exhibit B is noted.")

    fun exhibit(id: MockTrialExhibitID): MockTrialExhibit = if (id == MockTrialExhibitID.a) exhibitA else exhibitB

    // MARK: 7. Cross-examination

    const val crossLabel = "CROSS-EXAMINATION"

    data class QA(val question: MockTrialLine, val answer: MockTrialLine)

    /** The judge's questions and Alex's answers, one pair per beat. */
    val crossExamination: List<QA> = listOf(
        QA(MockTrialLine(MockTrialSpeaker.judge, "Did you know Sam was expecting the slice?"),
            MockTrialLine(MockTrialSpeaker.defendant, "Yes. I thought there was still one for her.")),
        QA(MockTrialLine(MockTrialSpeaker.judge, "If the roles were reversed, would you be annoyed?"),
            MockTrialLine(MockTrialSpeaker.defendant, "Honestly, yes.")),
        QA(MockTrialLine(MockTrialSpeaker.judge, "What could you have done differently?"),
            MockTrialLine(MockTrialSpeaker.defendant, "Checked with her before eating it.")),
    )
    val followUp = MockTrialLine(MockTrialSpeaker.plaintiff, "There was only one left when I got home, and it was gone.")

    // MARK: 8. Closing statements

    const val closingLabel = "CLOSING STATEMENT"
    const val closingTooltip = "Your final chance to tell the court why it should rule in your favour."
    val plaintiffClosing = MockTrialLine(MockTrialSpeaker.plaintiff, "A promise is a promise.")
    val defendantClosing = MockTrialLine(MockTrialSpeaker.defendant, "It was an honest mistake.")

    // MARK: 9. Deliberation

    const val deliberationTitle = "THE COURT IS DELIBERATING…"
    val deliberationRows = listOf(
        "Evidence Juror · Reviewing evidence ✓",
        "Consistency Juror · Comparing both accounts ✓",
        "Fairness Juror · Considering the outcome ✓",
        "Judge Wigsworth · Preparing ruling…",
    )

    data class DeliberationRow(val name: String, val status: String, val done: Boolean)

    /** A row split for display: who, what, and whether it is ticked (a trailing "✓"). */
    fun deliberationRow(i: Int): DeliberationRow {
        val row = deliberationRows[i]
        val halves = row.split(" · ")
        val name = halves.firstOrNull() ?: row
        var status = halves.drop(1).joinToString(" · ")
        val done = status.endsWith(" ✓")
        if (done) status = status.dropLast(2)
        return DeliberationRow(name, status, done)
    }

    // MARK: 10. Verdict

    val decisionLine = MockTrialLine(MockTrialSpeaker.judge, "The court has reached a decision.")
    const val verdictRibbon = "THE COURT"
    const val verdictTitle = "PLAINTIFF WINS"
    const val verdictReason = "Alex knew the slice had been promised to Sam, and the evidence supports Sam's account."

    // MARK: 11. Judgement

    const val judgementLabel = "THE WINNER CHOOSES THE JUDGEMENT"
    val judgementOptions = listOf("Replace the pizza", "Cook Sam's favourite meal", "Sam chooses the next takeaway")
    /** The option that selects itself. */
    const val judgementChoice = 0
    val soOrdered = MockTrialLine(MockTrialSpeaker.judge, "So ordered.")

    // MARK: 12. Case closed

    const val closedStamp = "CASE CLOSED"
    const val closedTitle = "That's a Plead trial."
    val closedLines = listOf("Both sides are heard.", "AI considers the evidence.", "The court delivers a verdict.")
    const val readyCTA = "I'M READY FOR COURT"

    // MARK: Beats

    val steps: List<MockTrialStep> = listOf(
        MockTrialStep(MockTrialBeat.opening, null, null, listOf(MockTrialPart.say(sessionLine), MockTrialPart.claim)),
        MockTrialStep(MockTrialBeat.plaintiffOpening, plaintiffOpeningLabel, openingTooltip, listOf(MockTrialPart.say(plaintiffOpeningLine))),
        MockTrialStep(MockTrialBeat.plaintiffEvidence, null, null, listOf(MockTrialPart.exhibit(MockTrialExhibitID.a), MockTrialPart.say(exhibitANoted))),
        MockTrialStep(MockTrialBeat.defendantOpening, defendantOpeningLabel, null, listOf(MockTrialPart.say(defendantOpeningLine))),
        MockTrialStep(MockTrialBeat.defendantEvidence, null, null, listOf(MockTrialPart.exhibit(MockTrialExhibitID.b), MockTrialPart.say(exhibitBNoted))),
        MockTrialStep(MockTrialBeat.crossExamination1, crossLabel, null,
            listOf(MockTrialPart.say(crossExamination[0].question), MockTrialPart.say(crossExamination[0].answer))),
        MockTrialStep(MockTrialBeat.crossExamination2, crossLabel, null,
            listOf(MockTrialPart.say(crossExamination[1].question), MockTrialPart.say(crossExamination[1].answer))),
        MockTrialStep(MockTrialBeat.crossExamination3, crossLabel, null,
            listOf(MockTrialPart.say(crossExamination[2].question), MockTrialPart.say(crossExamination[2].answer))),
        MockTrialStep(MockTrialBeat.crossFollowUp, crossLabel, null, listOf(MockTrialPart.say(followUp))),
        MockTrialStep(MockTrialBeat.closings, closingLabel, closingTooltip, listOf(MockTrialPart.say(plaintiffClosing), MockTrialPart.say(defendantClosing))),
        MockTrialStep(MockTrialBeat.deliberation, null, null, deliberationRows.indices.map { MockTrialPart.panelRow(it) }),
        MockTrialStep(MockTrialBeat.verdict, null, null, listOf(MockTrialPart.say(decisionLine), MockTrialPart.verdictCard)),
        MockTrialStep(MockTrialBeat.judgement, judgementLabel, null, listOf(MockTrialPart.options, MockTrialPart.select, MockTrialPart.say(soOrdered))),
        MockTrialStep(MockTrialBeat.closed, null, null, emptyList()),
    )

    fun step(beat: MockTrialBeat): MockTrialStep = steps[beat.rawValue]

    /** Every user-visible string of the demo (tests check pricing / brand words against it). */
    val allCopy: List<String>
        get() {
            val out = mutableListOf(
                caseChip, introEyebrow, introBody, introCard, startCTA, skipCTA, claim, deliberationTitle,
                verdictRibbon, verdictTitle, verdictReason, closedStamp, closedTitle, readyCTA,
            )
            out += steps.flatMap { listOfNotNull(it.label, it.tooltip) + it.lines.map { l -> l.text } }
            out += exhibitA.messages.map { it.text } + listOf(exhibitA.label, exhibitB.label, exhibitB.caption ?: "")
            out += deliberationRows + judgementOptions + closedLines
            return out
        }

    // MARK: TalkBack

    /** What TalkBack announces when `beat` comes up: the label, then "speaker: line" for every part. */
    fun accessibilityText(beat: MockTrialBeat): String {
        val step = step(beat)
        val out = mutableListOf<String>()
        step.label?.let { out += sentence(it) + "." }
        step.tooltip?.let { out += it }
        if (beat == MockTrialBeat.deliberation) out += deliberationAccessibilityText
        for (part in step.parts) {
            when (part) {
                is MockTrialPart.say -> out += part.value.accessibilityText
                MockTrialPart.claim -> out += "${sentence(claimLabel)}: $claimText"
                is MockTrialPart.exhibit -> out += exhibit(part.id).accessibilityText
                MockTrialPart.verdictCard -> out += "${sentence(verdictTitle)}. $verdictReason"
                MockTrialPart.options -> out += "Options: ${judgementOptions.joinToString(", ")}."
                MockTrialPart.select -> out += "Chosen: ${judgementOptions[judgementChoice]}."
                is MockTrialPart.panelRow -> Unit
            }
        }
        if (beat == MockTrialBeat.closed) out += closedAccessibilityText
        return out.joinToString(" ")
    }

    /** The deliberation panel as one element. */
    val deliberationAccessibilityText: String
        get() {
            val rows = deliberationRows.indices.map { i ->
                val r = deliberationRow(i)
                val status = r.status.replace("…", "").lowercase()
                "${r.name}, $status${if (r.done) ", done" else ", in progress"}"
            }
            return "The court is deliberating. " + rows.joinToString(". ") + "."
        }

    val closedAccessibilityText: String
        get() = "Case closed. $closedTitle " + closedLines.joinToString(" ")

    /** "OPENING STATEMENT · PLAINTIFF" → "Opening statement, plaintiff" (TalkBack reads caps letter by letter). */
    fun sentence(caps: String): String {
        val s = caps.lowercase().replace(" · ", ", ")
        return s.take(1).uppercase() + s.drop(1)
    }
}
