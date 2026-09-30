// Port of ArgueWin/Courtroom/CourtHelp.swift: phase help sheets (CONTRACTS-v2 amendment ae, motion brief §19).
// INTERFACE IS FIXED; copy for `openingStatement` and `mockOpeningStatement` is exact from the brief. The mock trial
// uses `CourtHelpButton` + `CourtHelpSheetHost` as-is.
//
// Live court: `CourtDock` offers the topic for what the user can do right now (`CourtHelp.dockTopic`): the opening
// statement composer, the "Show your evidence" tray, the cross-examination answer, the objection window and the verdict
// ("The verdict is in"); `CourtDeliberationOverlay` offers `verdict` beside THE COURT IS DELIBERATING. The sheet is
// user-triggered only (debug `AWCourtHelp <topic>` opens it once for captures), local content only (no network, no
// AI), and it never touches the dock's draft, the deadline, the entrance or the case call.
@file:Suppress("EnumEntryName")

package app.plead.android.courtroom

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.PrimaryButton
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.italic
import app.plead.android.designsystem.monospacedDigit
import java.time.Duration
import java.time.Instant
import kotlin.math.min
import kotlinx.coroutines.delay

enum class CourtHelpTopic(val rawValue: String) {
    openingStatement("openingStatement"), mockOpeningStatement("mockOpeningStatement"), evidence("evidence"),
    crossExamination("crossExamination"), objection("objection"), verdict("verdict");

    val id: String get() = rawValue

    companion object {
        fun fromRaw(raw: String?): CourtHelpTopic? = entries.firstOrNull { it.rawValue == raw }
    }
}

data class CourtHelpContent(
    val title: String,
    val body: String,
    val example: String?,
    val button: String,
    /** TalkBack label for the info button ("What is an opening statement?"). */
    val accessibilityQuestion: String,
)

object CourtHelp {
    fun content(topic: CourtHelpTopic): CourtHelpContent = when (topic) {
        CourtHelpTopic.openingStatement -> CourtHelpContent(
            title = "What's an opening statement?",
            body = "It's your first chance to explain your side before the court looks at evidence. In a few sentences, say what happened, why it matters to you, and what you'd like the judge to decide. You can show proof later.",
            example = "Alex promised to save the last slice, but ate it. I'd like a replacement pizza.",
            button = "Got it", accessibilityQuestion = "What is an opening statement?",
        )
        CourtHelpTopic.mockOpeningStatement -> CourtHelpContent(
            title = "What's an opening statement?",
            body = "This is where a side briefly tells the judge what happened and what they want. Sam goes first in this demo. Just watch how the trial unfolds.",
            example = null, button = "Got it", accessibilityQuestion = "What is an opening statement?",
        )
        // The exhibits phase: the proof filed with the case, shown one piece at a time with an optional line, each open
        // to one objection; "Rest" hands over (CONTRACTS v1 "Rest / pass semantics").
        CourtHelpTopic.evidence -> CourtHelpContent(
            title = "What counts as evidence?",
            body = "Evidence is the proof you added to this case: a photo, screenshot, receipt or message. Show it one piece at a time and add a line on why it matters, then rest when you're done. Your partner can object to each piece.",
            example = "Exhibit A: the empty pizza box, photographed at 11pm, an hour after Alex promised to save me a slice.",
            button = "Got it", accessibilityQuestion = "What is evidence?",
        )
        // The judge posts one bubble per side with 2–3 questions; each side answers in one turn.
        CourtHelpTopic.crossExamination -> CourtHelpContent(
            title = "What's cross-examination?",
            body = "The judge has asked you two or three short questions about the case. Answer them all in one reply, plainly and honestly; there are no trick questions. Your answers go on the record the court reviews.",
            example = "1. Yes, I knew the slice was being saved.\n2. I thought it was for anyone.",
            button = "Got it", accessibilityQuestion = "What is cross-examination?",
        )
        // One object-or-pass window per exhibit; the objector picks one of five fixed reasons; a sustained objection
        // lowers that exhibit's weight (CONTRACTS-v2 "record citations").
        CourtHelpTopic.objection -> CourtHelpContent(
            title = "What's an objection?",
            body = "Your partner has just shown a piece of evidence. If you think it's unfair, object and pick a reason, such as irrelevant or out of context; otherwise let it stand. The judge rules: sustained means it counts for less, overruled means it stands.",
            example = "Alex shows a photo from last year's party. You object: Irrelevant.",
            button = "Got it", accessibilityQuestion = "What is an objection?",
        )
        // Deliberation: three jurors, then the presiding judge; the winner chooses the judgement, a tie gets a
        // court-chosen compromise (amendments j, l).
        CourtHelpTopic.verdict -> CourtHelpContent(
            title = "What happens at the verdict?",
            body = "The record is closed, so nothing more can be added. Three jurors review it, then the presiding judge announces who the court finds for and why. The winner then picks the court's judgement; in a tie, the court picks a fair compromise.",
            example = "The court finds for Sam. Sam picks: Alex buys the next pizza this week.",
            button = "Got it", accessibilityQuestion = "What is a verdict?",
        )
    }

    // MARK: Where the live court offers help

    /**
     * The topic the live dock offers for what I can do right now, or null. Only while that phase or input is active:
     * never while the case is being called (the gate turns the dock to "the judge has the floor"), never under a pending
     * settlement or a judgement step, never for a stopped case, waiting, closings or the gallery.
     */
    fun dockTopic(mode: DockMode, judgement: JudgementDockMode?, settlement: SettlementDockMode?): CourtHelpTopic? {
        if (judgement != null || settlement != null) return null
        return when (mode) {
            is DockMode.compose -> when (mode.kind) {
                ComposeKind.opening -> CourtHelpTopic.openingStatement
                is ComposeKind.presentExhibits -> CourtHelpTopic.evidence
                is ComposeKind.crossAnswer -> CourtHelpTopic.crossExamination
                ComposeKind.closing -> null
            }
            is DockMode.objectionWindow -> CourtHelpTopic.objection
            DockMode.deliberating, DockMode.verdictIn -> CourtHelpTopic.verdict
            DockMode.notInSession, DockMode.spectator, DockMode.judgeHasFloor, is DockMode.waiting, DockMode.stopped -> null
        }
    }

    /** `dockTopic` for a court state, applying the case-call gate the dock applies (amendment ad). */
    fun dockTopic(s: CourtroomState, callingCase: Boolean = false): CourtHelpTopic? {
        val mode = CourtCaseCallGate.dockMode(CourtroomLogic.dockMode(s), calling = callingCase)
        return dockTopic(mode, CourtroomLogic.judgementDockMode(s), CourtroomLogic.settlementDockMode(s))
    }

    /**
     * The deadline the sheet keeps visible: the same target the dock's countdown shows (null when it shows none).
     * Presenting help never changes it: it is read from the case, not from the sheet.
     */
    fun deadline(s: CourtroomState): Instant? {
        if (CourtroomLogic.judgementDockMode(s) != null || CourtroomLogic.settlementDockMode(s) != null ||
            CourtroomLogic.dockMode(s) == DockMode.stopped
        ) {
            return null
        }
        return CourtroomLogic.countdownTarget(s)
    }

    /**
     * `AWCourtHelp openingStatement|evidence|crossExamination|objection|verdict`: open that sheet once per launch, as
     * soon as the court offers it (captures only; the product never auto-opens help). Always null in release builds.
     */
    val debugRequested: CourtHelpTopic? get() = CourtHelpTopic.fromRaw(DemoHarness.courtHelp)
    var debugOpened = false
}

/**
 * Which help sheet is up (null = none). It holds only the topic: the dock's draft, its deadline and the court's
 * entrance / case call live elsewhere and are never touched by opening or closing help.
 */
class CourtHelpPresentation {
    var topic: CourtHelpTopic? by mutableStateOf(null)

    /** Opens `topic` only if the court currently offers it (a stale tap after the phase moved on does nothing). */
    fun open(topic: CourtHelpTopic, offered: CourtHelpTopic?): Boolean {
        if (topic != offered) return false
        this.topic = topic
        return true
    }

    fun dismiss() {
        topic = null
    }
}

/** Statics of `CourtHelpButton`. */
object CourtHelpButton {
    /** Minimum tap target (points), both axes. */
    const val tapTarget: Float = 44f
}

/**
 * The 44 dp information button that sits beside a phase label or action: a small `info.circle` that takes the
 * surrounding label colour ([tint]; the dock uses its secondary cream).
 */
@Composable
fun CourtHelpButton(
    topic: CourtHelpTopic,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
    action: () -> Unit,
) {
    // `@ScaledMetric(relativeTo: .body) var glyph = 17`, capped at 26.
    val glyph = min(17f * LocalDensity.current.fontScale, 26f)
    Box(
        modifier
            .defaultMinSize(minWidth = CourtHelpButton.tapTarget.dp, minHeight = CourtHelpButton.tapTarget.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClickLabel = "Opens a short explanation",
                role = SemanticsRole.Button,
                onClick = action,
            )
            .semantics { contentDescription = CourtHelp.content(topic).accessibilityQuestion }
            .testTag("court.help.${topic.rawValue}"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(SFSymbol.icon("info.circle"), contentDescription = null, tint = tint, modifier = Modifier.size(glyph.dp))
    }
}

/**
 * The bottom sheet on warm cream: an optional ticking deadline and a Close (X) on top, the title, the plain-English
 * body, one example on a paper-white card, and "Got it". TalkBack reads title → body → example → Got it.
 */
@Composable
fun CourtHelpSheet(
    topic: CourtHelpTopic,
    deadline: Instant? = null,
    deadlineLabel: String = "left",
    dismiss: () -> Unit,
) {
    val c = CourtHelp.content(topic)
    Column(
        Modifier
            .fillMaxWidth()
            .background(PleadColor.cream)
            .navigationBarsPadding()
            .testTag("court.help.sheet"),
    ) {
        // Top bar
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = PleadSpacing.xl, end = PleadSpacing.m, top = PleadSpacing.m)
                .accessibilitySortPriority(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
        ) {
            if (deadline != null) CourtHelpDeadline(target = deadline, label = deadlineLabel)
            Spacer(Modifier.weight(1f))
            Box(
                Modifier
                    .size(44.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = SemanticsRole.Button,
                        onClick = dismiss,
                    )
                    .clearAndSetSemantics { contentDescription = "Close" }
                    .testTag("court.help.close"),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(30.dp).background(PleadColor.parchment, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(SFSymbol.icon("xmark"), contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(14.dp))
                }
            }
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .fillMaxWidth()
                .padding(start = PleadSpacing.xl, end = PleadSpacing.xl, bottom = PleadSpacing.m)
                .accessibilitySortPriority(3f),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        ) {
            Text(
                c.title,
                style = PleadType.titleL,
                color = PleadColor.cocoa,
                modifier = Modifier.semantics { heading() }.testTag("court.help.title").accessibilitySortPriority(6f),
            )
            Text(
                c.body,
                style = PleadType.body,
                color = PleadColor.cocoa,
                modifier = Modifier.testTag("court.help.body").accessibilitySortPriority(5f),
            )
            val example = c.example
            if (example != null) {
                ExampleCard(example, Modifier.padding(top = PleadSpacing.xs).accessibilitySortPriority(4f))
            }
        }
        PrimaryButton(
            c.button,
            modifier = Modifier
                .testTag("court.help.gotIt")
                .padding(start = PleadSpacing.xl, end = PleadSpacing.xl, top = PleadSpacing.s, bottom = PleadSpacing.l)
                .accessibilitySortPriority(2f),
            action = dismiss,
        )
    }
}

@Composable
private fun ExampleCard(example: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(PleadRadius.tile)
    Column(
        modifier
            .fillMaxWidth()
            .background(PleadColor.paperWhite, shape)
            .border(1.5.dp, PleadColor.parchment, shape)
            .padding(PleadSpacing.l)
            .clearAndSetSemantics { contentDescription = "Example: $example" }
            .testTag("court.help.example"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("EXAMPLE", style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp), color = PleadColor.burgundy)
        Text(example, style = PleadType.body.italic(), color = PleadColor.cocoa)
    }
}

/** The turn's remaining time, still ticking while help is open (the same target the dock counts down to). */
@Composable
private fun CourtHelpDeadline(target: Instant, label: String) {
    // `TimelineView(.periodic(from: .now, by: 1))`.
    val now by produceState(Instant.now(), target) {
        while (true) {
            value = Instant.now()
            delay(1000)
        }
    }
    val remaining = Duration.between(now, target).toMillis() / 1000.0
    val color = if (remaining < 2 * 3600) PleadColor.burgundy else PleadColor.walnut
    DynamicTypeCap(DynamicTypeSize.accessibility1) {
        Row(
            Modifier
                .background(PleadColor.parchment, RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 5.dp)
                .clearAndSetSemantics {
                    contentDescription = if (label == "left") "Time left" else "Time $label"
                    stateDescription = CourtroomLogic.spokenCountdown(remaining)
                }
                .testTag("court.help.deadline"),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(SFSymbol.icon("hourglass"), contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Text(
                "${CourtroomLogic.formatRemaining(remaining)} $label",
                style = PleadType.labelCaps.monospacedDigit(),
                color = color,
                maxLines = 1,
            )
        }
    }
}

/**
 * SwiftUI `.courtHelpSheet(topic:deadline:deadlineLabel:)`: presents `CourtHelpSheet` for the presentation's topic
 * (null = hidden), as a swipe-dismissible bottom sheet. Accessibility text sizes open full height.
 */
@Composable
fun CourtHelpSheetHost(presentation: CourtHelpPresentation, deadline: Instant? = null, deadlineLabel: String = "left") {
    val t = presentation.topic ?: return
    CourtSheet(
        onDismiss = { presentation.dismiss() },
        containerColor = PleadColor.cream,
        largeOnly = dynamicTypeSize().isAccessibilitySize,
    ) {
        CourtHelpSheet(topic = t, deadline = deadline, deadlineLabel = deadlineLabel) { presentation.dismiss() }
    }
}
