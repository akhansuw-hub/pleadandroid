// Port of ArgueWin/Courtroom/VerdictMomentView.swift: the verdict sequence (spec v2 §7), over the dimmed courtroom:
//   1 ALL RISE → 2 THE COURT HAS REACHED A DECISION → 3 recap → 4 key findings (decisive exhibits, sustained
//   objections) → 5 winner or tie → 6 panel split + confidence → 7 closing line in persona → 8 judgement (amendment j:
//   the chooser gets CHOOSE JUDGEMENT, the other party is told the prevailing party is choosing; skipped when there is
//   no judgement) → result card.
// `verdict.sentence` is no longer shown (it now holds a fixed line); legacy verdicts without a judgement still show
// their real sentence on the result card. Tap anywhere (or Continue) to advance. Gold is spent here.
package app.plead.android.courtroom

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.plead.android.BuildConfig
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.PixelAvatarView
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadCopy
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.Profile
import app.plead.android.models.Role
import app.plead.android.models.Verdict
import app.plead.android.models.VerdictFinding
import app.plead.android.models.JudgementStatus
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Swift `VerdictMomentView` statics. */
object VerdictMomentView {
    enum class Step { allRise, decision, recap, findings, winner, panel, closing, judgement, result }
}

@Composable
fun VerdictMomentView(
    state: CourtroomState,
    verdict: Verdict,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** CHOOSE JUDGEMENT (the scene closes this cover, then asks the app to present the selection sheet). */
    onChooseJudgement: (() -> Unit)? = null,
    /** `initialStep` lets previews / the harness open mid-sequence. */
    initialStep: VerdictMomentView.Step = VerdictMomentView.Step.allRise,
    insets: CourtInsets = CourtInsets.zero,
) {
    val Step = VerdictMomentView.Step.entries
    val reduceMotion = accessibilityReduceMotion()
    /** Verdict choreography (amendment x, P2): its own clock; nothing under Reduce Motion. */
    val motion = remember { CourtMotionDirector() }
    var step by remember { mutableStateOf(initialStep) }
    /** When the winner step appeared (drives the one-shot confetti); null once it has finished. */
    var confettiStart by remember { mutableStateOf<Long?>(null) }
    var celebrated by remember { mutableStateOf(false) }
    var outcomePlayed by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** The steps this verdict plays (no judgement step without a judgement). */
    val steps = Step.filter { it != VerdictMomentView.Step.judgement || state.judgement != null }
    // A court-chosen resolution (tie, amendment l) has no chooser: Continue for both.
    val isChooser = state.judgement?.let { it.chooserId != null && it.chooserId == state.me.id && it.status == JudgementStatus.pendingSelection } ?: false
    val winnerRole: Role? = if (verdict.isTie) null else verdict.winnerId?.let { state.kase.role(it) }
    val winner: Profile? = state.profile(verdict.winnerId)

    fun advance() {
        val i = steps.indexOf(step)
        if (i < 0 || i + 1 >= steps.size) return
        step = steps[i + 1]
    }

    // Lifecycle: `scenePhase == .active` pauses / resumes the clock.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> motion.setActive(true)
                Lifecycle.Event.ON_PAUSE -> motion.setActive(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        motion.reduceMotion = reduceMotion
        motion.update(state)
        motion.setActive(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        motion.appear(analytics = false)
        motion.playVerdictOpening()
        onDispose { motion.disappear() }
    }
    LaunchedEffect(reduceMotion) { motion.reduceMotion = reduceMotion }
    LaunchedEffect(step) {
        // Confetti once per verdict, on the winner step, never on a tie; Reduce Motion gets a static scatter instead.
        if (step == VerdictMomentView.Step.winner && !verdict.isTie && winner != null && !celebrated && !reduceMotion) {
            celebrated = true
            val start = System.nanoTime()
            confettiStart = start
            scope.launch {
                delay(((ConfettiBurst.duration + 0.1) * 1000).toLong())
                if (confettiStart == start) confettiStart = null
            }
        }
        // The winner step: the winner hops once, the gallery reacts once.
        if (step.ordinal >= VerdictMomentView.Step.winner.ordinal && !outcomePlayed) {
            outcomePlayed = true
            motion.playVerdictOutcome(winner = winnerRole, tie = verdict.isTie)
        }
    }
    // `-AWAutoplay YES` (demo harness): step through to the winner headline unattended.
    LaunchedEffect(Unit) {
        if (!BuildConfig.DEBUG || !DemoHarness.autoplay) return@LaunchedEffect
        while (step.ordinal < VerdictMomentView.Step.winner.ordinal) {
            delay(1500)
            advance()
        }
    }

    DynamicTypeCap(DynamicTypeSize.xxxLarge) {
        BoxWithConstraints(
            modifier
                .fillMaxSize()
                .background(PleadColor.courtBackdrop)
                .pointerInput(Unit) { detectTapGestures { if (step != VerdictMomentView.Step.result) advance() } }
                .semantics { customActions = listOf(CustomAccessibilityAction("Continue") { advance(); true }) },
        ) {
            val full = Size(maxWidth.value, maxHeight.value)
            val z = CourtroomZones(full)
            CourtroomBackground(full)
            CourtCrowdLayer(z, motion)
            CourtGavelLayer(z, motion)
            CourtJudgeFigure(
                state.judgePersona, cell = z.judgeCell, motion = motion,
                modifier = Modifier.position(z.judgeFrame.center.x, z.judgeFrame.center.y).accessibilityHidden(),
            )
            for (role in listOf(Role.plaintiff, Role.defendant)) {
                CourtPodiumParty(
                    profile = state.profile(role), role = role, isMe = state.myRole == role,
                    hasFloor = step.ordinal >= VerdictMomentView.Step.winner.ordinal && (winnerRole == role || verdict.isTie),
                    zones = z, showsTag = false, motion = motion, modifier = Modifier.accessibilityHidden(),
                )
            }
            // Lights dim, with a soft pool on the bench.
            Box(
                Modifier
                    .fillMaxSize()
                    .accessibilityHidden()
                    .drawBehind {
                        val end = size.height * 0.7f
                        val start = 40.dp.toPx()
                        drawRect(
                            Brush.radialGradient(
                                0f to CourtColor.dim.copy(alpha = 0.35f),
                                (start / end).coerceIn(0f, 1f) to CourtColor.dim.copy(alpha = 0.35f),
                                1f to CourtColor.dim.copy(alpha = 0.86f),
                                center = Offset(size.width * 0.5f, size.height * CourtroomZones.judgeChair.center.y),
                                radius = end,
                            ),
                        )
                    },
            )

            VerdictContent(
                state = state, verdict = verdict, step = step, steps = steps, z = z, insets = insets,
                isChooser = isChooser, winner = winner, winnerRole = winnerRole, reduceMotion = reduceMotion,
                onChooseJudgement = onChooseJudgement, onDismiss = onDismiss, advance = ::advance,
            )

            val start = confettiStart
            if (start != null) {
                ConfettiBurst(startNanos = start, size = full, modifier = Modifier.fillMaxSize().accessibilityHidden())
            }
        }
    }
}

// MARK: Layout

@Composable
private fun VerdictContent(
    state: CourtroomState,
    verdict: Verdict,
    step: VerdictMomentView.Step,
    steps: List<VerdictMomentView.Step>,
    z: CourtroomZones,
    insets: CourtInsets,
    isChooser: Boolean,
    winner: Profile?,
    winnerRole: Role?,
    reduceMotion: Boolean,
    onChooseJudgement: (() -> Unit)?,
    onDismiss: () -> Unit,
    advance: () -> Unit,
) {
    // Everything sits below the bench so the judge stays visible above it.
    val top = max(z.y(CourtroomZones.bench.bottom) - 8f, insets.top + 40f)
    val available = z.size.height - top - insets.bottom - 96f
    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(top.dp))
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val spec = if (reduceMotion) tween<Float>(150, easing = LinearEasing) else PleadMotion.fade()
                fadeIn(spec) togetherWith fadeOut(spec)
            },
            label = "verdictStep",
            modifier = Modifier.padding(horizontal = PleadSpacing.l),
        ) { s ->
            when (s) {
                VerdictMomentView.Step.allRise -> AllRise(state)
                VerdictMomentView.Step.decision -> Decision(state)
                VerdictMomentView.Step.recap -> JudgeCard(state, chip = "Recap", text = verdict.recap, serif = false)
                VerdictMomentView.Step.findings -> Findings(state, verdict, maxHeight = available)
                VerdictMomentView.Step.winner -> WinnerHeadline(state, verdict, winner, winnerRole, reduceMotion)
                VerdictMomentView.Step.panel -> PanelStep(state, verdict)
                VerdictMomentView.Step.closing -> JudgeCard(state, chip = "Closing", text = "“${verdict.closingLine}”", serif = true)
                VerdictMomentView.Step.judgement -> Box(Modifier.padding(top = max(0f, z.avatarFrame(Role.plaintiff).bottom - top - 8f).dp)) {
                    JudgementStep(state, isChooser, onChooseJudgement)
                }
                VerdictMomentView.Step.result -> ResultCard(
                    state, verdict, isChooser, onChooseJudgement, onDismiss,
                    maxHeight = z.size.height - top - insets.bottom,
                )
            }
        }
        Spacer(Modifier.height(PleadSpacing.l))
        Spacer(Modifier.weight(1f))
        if (step != VerdictMomentView.Step.result) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PleadSpacing.l)
                    .padding(bottom = (insets.bottom + 12f).dp),
            ) {
                Text(
                    "Tap to continue · ${(steps.indexOf(step).takeIf { it >= 0 } ?: 0) + 1} of ${steps.size - 1}",
                    style = CourtFont.caption.monospacedDigit(), color = CourtColor.creamSoft,
                    modifier = Modifier.weight(1f),
                )
                CourtButton(onClick = advance) { Text("Continue") }
            }
        } else {
            Spacer(Modifier.height(insets.bottom.dp))
        }
    }
}

// MARK: Steps

@Composable
private fun GoldRule() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.widthIn(max = 260.dp).fillMaxWidth().accessibilityHidden(),
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(PleadColor.gold.copy(alpha = 0.7f)))
        ScalesGlyph(size = 18.dp)
        Box(Modifier.weight(1f).height(1.dp).background(PleadColor.gold.copy(alpha = 0.7f)))
    }
}

@Composable
private fun AllRise(state: CourtroomState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        modifier = Modifier.fillMaxWidth().padding(top = PleadSpacing.xxl),
    ) {
        GoldRule()
        Text(
            PleadCopy.allRise, style = CourtFont.displayXL.copy(letterSpacing = 4.sp), color = PleadColor.cream,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            "${state.judgePersona.displayName} presiding · ${state.kase.formattedNumber}",
            style = CourtFont.caseParties, color = CourtColor.creamSoft, textAlign = TextAlign.Center,
        )
        GoldRule()
    }
}

@Composable
private fun Decision(state: CourtroomState) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        modifier = Modifier.fillMaxWidth().padding(top = PleadSpacing.xxl),
    ) {
        GoldRule()
        // Sentence case: long phrases are never set in caps (typography brief §7).
        Text(
            CourtroomLogic.sentenceCase(PleadCopy.decisionReached), style = CourtFont.caseTitle, color = PleadColor.cream,
            textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
        )
        Text(state.kase.title, style = CourtFont.caseParties, color = CourtColor.creamSoft, textAlign = TextAlign.Center)
        GoldRule()
    }
}

@Composable
private fun JudgeCard(state: CourtroomState, chip: String, text: String, serif: Boolean) {
    val shape = RoundedCornerShape(PleadRadius.bubble)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .pleadShadow(Color.Black.copy(alpha = 0.4f), 12.dp, y = 6.dp, shape = shape)
            .background(PleadColor.mahogany, shape)
            .border(1.5.dp, PleadColor.cocoa, shape)
            .padding(PleadSpacing.l)
            .clearAndSetSemantics { contentDescription = "${state.judgePersona.displayName}, $chip: $text" },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ScalesGlyph(size = 13.dp)
            Text(state.judgePersona.displayName, style = CourtFont.judgeName, color = CourtColor.creamSoft, modifier = Modifier.weight(1f))
            CourtPhaseChip(chip, tint = CourtColor.creamSoft)
        }
        Text(text, style = if (serif) CourtFont.rulingLarge else CourtFont.judgeSpeech, color = PleadColor.cream)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(PleadColor.walnut.copy(alpha = 0.25f)))
}

@Composable
private fun Findings(state: CourtroomState, verdict: Verdict, maxHeight: Float) {
    val shape = RoundedCornerShape(PleadRadius.card)
    val sustained = CourtroomLogic.sustainedExhibits(state)
    Column(
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = max(maxHeight, 200f).dp)
            .pleadShadow(Color.Black.copy(alpha = 0.4f), 12.dp, y = 6.dp, shape = shape)
            .background(PleadColor.parchment, shape)
            .border(1.dp, PleadColor.walnut.copy(alpha = 0.5f), shape)
            .verticalScroll(rememberScrollState())
            .padding(PleadSpacing.l),
    ) {
        Text(
            "KEY FINDINGS", style = CourtFont.legalLarge.copy(letterSpacing = PleadType.capsTracking.sp), color = PleadColor.burgundy,
            modifier = Modifier.semantics { heading() },
        )
        verdict.findings.forEachIndexed { i, f ->
            if (i > 0) Divider()
            FindingRow(state, f)
        }
        if (sustained.isNotEmpty()) {
            Divider()
            sustained.forEach { ex ->
                val owner = state.profile(ex.ownerId)?.displayName ?: "A party"
                val reason = CourtroomLogic.stamps(ex, state.turns).objection?.title?.lowercase()
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.semantics(mergeDescendants = true) { },
                ) {
                    Text(
                        "SUSTAINED", style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp), color = PleadColor.burgundy,
                        modifier = Modifier.border(1.5.dp, PleadColor.burgundy, RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                    )
                    Text(
                        "Objection to $owner's ${ex.displayName}${reason?.let { " ($it)" } ?: ""}. Its weight was reduced.",
                        style = CourtFont.footnote, color = PleadColor.cocoa,
                    )
                }
            }
        }
    }
}

@Composable
private fun FindingRow(state: CourtroomState, f: VerdictFinding) {
    val owner = state.profile(f.side)
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "${f.label}, ${owner.displayName}'s evidence: ${f.finding} Weight ${f.weight} of 3."
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(f.label.uppercase(), style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp), color = PleadColor.burgundy)
            CourtRoleChip(f.side)
            Spacer(Modifier.weight(1f).defaultMinSize(minWidth = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                repeat(3) { i ->
                    Box(Modifier.size(9.dp).background(if (i < f.weight) PleadColor.gold else PleadColor.walnut.copy(alpha = 0.2f)))
                }
            }
        }
        Text(f.finding, style = CourtFont.judgeSpeech, color = PleadColor.cocoa)
    }
}

@Composable
private fun WinnerHeadline(state: CourtroomState, verdict: Verdict, winner: Profile?, winnerRole: Role?, reduceMotion: Boolean) {
    val won = !verdict.isTie && winner != null
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        modifier = Modifier.fillMaxWidth().padding(top = PleadSpacing.l),
    ) {
        GoldRule()
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.xl), modifier = Modifier.accessibilityHidden()) {
            if (verdict.isTie) {
                PixelAvatarView(state.profile(Role.plaintiff).avatar, size = 88.dp)
                PixelAvatarView(state.profile(Role.defendant).avatar, size = 88.dp)
            } else if (winner != null) {
                // A soft gold glow behind the winner (drawn behind, outside the avatar's bounds, like `.background`).
                PixelAvatarView(
                    winner.avatar, size = 112.dp,
                    modifier = Modifier.drawBehind {
                        val r = 100.dp.toPx()
                        drawCircle(
                            Brush.radialGradient(
                                0f to PleadColor.gold.copy(alpha = 0.45f),
                                (8.dp.toPx() / (96.dp.toPx())) to PleadColor.gold.copy(alpha = 0.45f),
                                (96f / 100f) to PleadColor.gold.copy(alpha = 0f),
                                center = center, radius = r,
                            ),
                            radius = r, center = center,
                        )
                    },
                )
            }
        }
        Box(contentAlignment = Alignment.Center) {
            if (won && reduceMotion) StaticConfetti(Modifier.matchParentSize().accessibilityHidden())
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                ScaledText(
                    CourtroomLogic.verdictHeadline(verdict, kase = state.kase),
                    style = CourtFont.displayXL.copy(
                        letterSpacing = 2.sp,
                        shadow = Shadow(Color.Black.copy(alpha = 0.45f), offset = Offset(0f, 2f), blurRadius = 6f),
                    ),
                    color = PleadColor.gold, minimumScaleFactor = 0.6f, maxLines = 2, textAlign = TextAlign.Center,
                    modifier = Modifier.semantics { heading() },
                )
                if (won && winner != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val isMeWinner = state.myRole?.let { state.profile(it).id == winner.id } == true
                        ScaledText(
                            if (isMeWinner) "${winner.displayName} (you)" else winner.displayName,
                            style = CourtFont.nameLarge, color = PleadColor.cream, minimumScaleFactor = 0.7f, maxLines = 1,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (winnerRole != null) CourtRoleChip(winnerRole)
                    }
                }
            }
        }
        Text(
            if (verdict.isTie) "Neither side carried the record." else "The court finds in their favour.",
            style = CourtFont.rulingItalic, color = if (won) CourtColor.creamSoft else PleadColor.cream, textAlign = TextAlign.Center,
        )
        GoldRule()
    }
}

@Composable
private fun PanelStep(state: CourtroomState, verdict: Verdict) {
    val votes = CourtroomLogic.panelVotes(verdict, kase = state.kase)
    val seats: List<Role?> = List(votes.plaintiff) { Role.plaintiff } + List(votes.defendant) { Role.defendant } + List(votes.tie) { null }
    val shape = RoundedCornerShape(PleadRadius.card)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        modifier = Modifier
            .fillMaxWidth()
            .pleadShadow(Color.Black.copy(alpha = 0.4f), 12.dp, y = 6.dp, shape = shape)
            .goldFrame()
            .padding(PleadSpacing.xl),
    ) {
        Text(
            CourtroomLogic.panelLine(verdict), style = CourtFont.legalLarge.copy(letterSpacing = PleadType.capsTracking.sp),
            color = PleadColor.gold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = "Jurors: ${votes.plaintiff} for the plaintiff, ${votes.defendant} for the defendant, ${votes.tie} for a tie."
            },
        ) {
            seats.forEachIndexed { i, seat ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "JUROR ${String.format(Locale.US, "%02d", i + 1)}",
                        style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp), color = CourtColor.creamSoft,
                    )
                    Box(
                        Modifier
                            .defaultMinSize(minWidth = 84.dp, minHeight = 34.dp)
                            .background(seat?.let { PleadColor.role(it) } ?: CourtColor.panelInset, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            seat?.let { CourtroomLogic.roleTitle(it) } ?: "Tie",
                            style = CourtFont.caption.copy(fontWeight = FontWeight.Bold), color = PleadColor.cream,
                        )
                    }
                }
            }
        }
        val conflict = verdict.majorityConflict
        if (!conflict.isNullOrEmpty()) {
            Text(conflict, style = CourtFont.judgeSpeech, color = CourtColor.creamSoft, textAlign = TextAlign.Center)
        }
        Text(
            "Three independent jurors reviewed the record. The presiding judge made the final call.",
            style = CourtFont.footnote, color = CourtColor.creamMuted, textAlign = TextAlign.Center,
        )
    }
}

/** Screen A: the winner is handed the choice; the other party learns it is being made. */
@Composable
private fun JudgementStep(state: CourtroomState, isChooser: Boolean, onChooseJudgement: (() -> Unit)?) {
    val copy = CourtroomLogic.judgementStepCopy(state)
    val j = state.judgement
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        modifier = Modifier.fillMaxWidth().padding(top = PleadSpacing.l),
    ) {
        GoldRule()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            Text(
                copy.headline,
                style = CourtFont.caseTitle.copy(shadow = Shadow(Color.Black.copy(alpha = 0.45f), offset = Offset(0f, 2f), blurRadius = 6f)),
                color = PleadColor.gold, textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
            )
            Text(copy.line, style = CourtFont.judgeSpeechLarge, color = PleadColor.cream, textAlign = TextAlign.Center)
        }
        if (isChooser && onChooseJudgement != null) {
            CourtButton(
                onClick = onChooseJudgement,
                style = CourtButtonStyle(fullWidth = true),
                onClickLabel = "Pick one of the court's prepared outcomes",
                modifier = Modifier.padding(horizontal = PleadSpacing.l),
            ) {
                Icon(SFSymbol.icon("hammer.fill"), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Choose judgement")
            }
            Text(
                "The court has prepared outcomes for this case. You can also choose later from the Court tab.",
                style = CourtFont.footnote, color = CourtColor.creamSoft, textAlign = TextAlign.Center,
            )
        } else if (j != null && j.selected != null) {
            // Rewatching after the choice was made: the judgement itself.
            JudgementDeliveryCard(j, now = state.now)
        } else if (!isChooser) {
            Text(
                if (j?.isCourtChosen == true) "You'll both be notified when the court delivers its resolution."
                else "You'll be notified when the court delivers its judgement.",
                style = CourtFont.footnote, color = CourtColor.creamSoft, textAlign = TextAlign.Center,
            )
        }
        GoldRule()
    }
}

// MARK: Result

@Composable
private fun ResultCard(
    state: CourtroomState,
    verdict: Verdict,
    isChooser: Boolean,
    onChooseJudgement: (() -> Unit)?,
    onDismiss: () -> Unit,
    maxHeight: Float,
) {
    val shape = RoundedCornerShape(PleadRadius.card)
    val radius = PleadRadius.card
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight.coerceAtLeast(200f).dp)
            .pleadShadow(Color.Black.copy(alpha = 0.45f), 20.dp, y = 8.dp, shape = shape)
            .background(PleadColor.parchment, shape)
            .drawWithContent {
                drawContent()
                strokeBorder(radius.toPx(), inset = 4.dp.toPx(), width = 1.dp.toPx(), color = PleadColor.gold.copy(alpha = 0.6f))
            }
            .verticalScroll(rememberScrollState())
            .padding(PleadSpacing.xl),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ScalesGlyph(size = 14.dp)
                Text(
                    "${state.kase.formattedNumber} · ${state.kase.title}".uppercase(),
                    style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp), color = PleadColor.walnut, textAlign = TextAlign.Center,
                )
            }
            Text(
                CourtroomLogic.verdictHeadline(verdict, kase = state.kase), style = CourtFont.displayXL, color = PleadColor.burgundy,
                textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() },
            )
            Text(
                CourtroomLogic.panelLine(verdict), style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
                color = PleadColor.gold, textAlign = TextAlign.Center,
                modifier = Modifier.background(PleadColor.mahogany, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
            modifier = Modifier
                .fillMaxWidth()
                .background(PleadColor.paperWhite, RoundedCornerShape(PleadRadius.tile))
                .padding(PleadSpacing.l),
        ) {
            val summary = CourtroomLogic.judgementSummary(state)
            val legacy = CourtroomLogic.legacySentence(verdict)
            if (summary != null) {
                Text("JUDGEMENT", style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp), color = PleadColor.burgundy)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "Judgement: $summary" },
                ) {
                    Text(summary, style = CourtFont.body, color = PleadColor.cocoa, modifier = Modifier.weight(1f, fill = false))
                    if (state.judgement?.status == JudgementStatus.served) {
                        Spacer(Modifier.weight(1f))
                        CourtServedStamp(small = true, modifier = Modifier.rotate(-6f))
                    }
                }
            } else if (legacy != null) {
                Text("SENTENCE", style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp), color = PleadColor.burgundy)
                Text(legacy, style = CourtFont.body, color = PleadColor.cocoa)
            }
            Text(
                "${state.judgePersona.displayName}: “${verdict.closingLine}”",
                style = CourtFont.ruling, color = PleadColor.walnut,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            if (isChooser && onChooseJudgement != null) {
                CourtButton(onClick = onChooseJudgement, style = CourtButtonStyle(fullWidth = true)) {
                    Icon(SFSymbol.icon("hammer.fill"), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Choose judgement")
                }
                CourtButton(onClick = onDismiss, style = CourtButtonStyle(kind = CourtButtonStyle.Kind.onParchment, fullWidth = true)) {
                    Text("Back to docket")
                }
            } else {
                CourtButton(onClick = onDismiss, style = CourtButtonStyle(fullWidth = true)) { Text("Back to docket") }
            }
            // v1.1: share card (render_share_card). Never includes exhibit content.
            CourtButton(
                onClick = {},
                style = CourtButtonStyle(kind = CourtButtonStyle.Kind.onParchment, fullWidth = true),
                enabled = false,
                onClickLabel = "Coming soon",
            ) {
                Icon(SFSymbol.icon("square.and.arrow.up"), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Share verdict card")
            }
            Text("Sharing arrives in a later version.", style = CourtFont.caption2, color = PleadColor.walnut)
        }
    }
}

// MARK: - Confetti

/**
 * One-shot celebration for the winner step: ~120 pixel squares (three sizes) in the court palette, falling from the top
 * with a little drift and spin for `duration` seconds, then gone. One Canvas draw per frame, no per-particle views;
 * never takes touches.
 */
object ConfettiBurst {
    const val duration: Double = 3.0
    val colors: List<Color> = listOf(PleadColor.gold, PleadColor.blush, PleadColor.terracotta, PleadColor.cream, PleadColor.burgundy, PleadColor.gold)

    class Particle(
        val x: Double, val y0: Double, val vy: Double, val vx: Double,
        val side: Double, val color: Int,
        val sway: Double, val swayRate: Double, val phase: Double, val spin: Double, val angle0: Double,
    )

    fun particles(size: Size, count: Int): List<Particle> {
        val rng = SplitMix(0xA59E_2026L)
        val sides = doubleArrayOf(5.0, 7.0, 10.0)
        return List(count) {
            Particle(
                x = rng.unitDouble() * size.width,
                y0 = -size.height * 0.3 + rng.unitDouble() * size.height * 0.4,
                vy = 170 + rng.unitDouble() * 140,
                vx = (rng.unitDouble() - 0.5) * 36,
                side = sides[(rng.unitDouble() * 3).toInt() % 3],
                color = (rng.unitDouble() * 6).toInt() % 6,
                sway = 6 + rng.unitDouble() * 16,
                swayRate = 1.6 + rng.unitDouble() * 2.4,
                phase = rng.unitDouble() * Math.PI * 2,
                spin = (rng.unitDouble() - 0.5) * 9,
                angle0 = rng.unitDouble() * Math.PI,
            )
        }
    }
}

@Composable
fun ConfettiBurst(startNanos: Long, size: Size, modifier: Modifier = Modifier, count: Int = 116) {
    val particles = remember(size, count) { ConfettiBurst.particles(size, count) }
    var t by remember { mutableDoubleStateOf((System.nanoTime() - startNanos) / 1e9) }
    LaunchedEffect(startNanos) {
        while (t <= ConfettiBurst.duration) {
            withFrameNanos { frame -> t = (frame - startNanos) / 1e9 }
        }
    }
    Canvas(modifier) {
        if (t < 0 || t > ConfettiBurst.duration) return@Canvas
        val fade = min(1.0, (ConfettiBurst.duration - t) / 0.6).toFloat()
        val gravity = 250.0
        val d = density
        for (p in particles) {
            val y = p.y0 + p.vy * t + 0.5 * gravity * t * t
            if (y <= -12 || y >= size.height + 12) continue
            val x = p.x + p.vx * t + p.sway * sin(p.phase + p.swayRate * t)
            val half = (p.side / 2 * d).toFloat()
            translate(left = (x * d).toFloat(), top = (y * d).toFloat()) {
                rotate(degrees = Math.toDegrees(p.angle0 + p.spin * t).toFloat(), pivot = Offset.Zero) {
                    // Flip on one axis as it tumbles, so squares flicker like paper.
                    scale(scaleX = 1f, scaleY = max(0.25, abs(cos(p.phase + t * 3))).toFloat(), pivot = Offset.Zero) {
                        drawRect(ConfettiBurst.colors[p.color], topLeft = Offset(-half, -half), size = Size(half * 2, half * 2), alpha = fade)
                    }
                }
            }
        }
    }
}

/** Reduce Motion: a few still gold squares scattered around the headline. */
@Composable
fun StaticConfetti(modifier: Modifier = Modifier) {
    val spots = remember {
        listOf(
            floatArrayOf(0.04f, 0.15f, 6f, 0.3f), floatArrayOf(0.12f, 0.85f, 4f, -0.2f), floatArrayOf(0.2f, -0.1f, 5f, 0.5f),
            floatArrayOf(0.8f, -0.05f, 6f, -0.4f), floatArrayOf(0.9f, 0.8f, 5f, 0.2f), floatArrayOf(0.97f, 0.25f, 4f, 0.6f),
            floatArrayOf(0.33f, 1.1f, 4f, 0.1f), floatArrayOf(0.68f, 1.12f, 5f, -0.3f),
        )
    }
    // `.padding(-14)`: the scatter spans 14 dp past each edge of the headline.
    Canvas(modifier) {
        val out = 14.dp.toPx()
        val w = size.width + 2 * out
        val h = size.height + 2 * out
        for (p in spots) {
            val s = p[2].dp.toPx()
            translate(left = -out + p[0] * w, top = -out + p[1] * h) {
                rotate(degrees = Math.toDegrees(p[3].toDouble()).toFloat(), pivot = Offset.Zero) {
                    drawRect(PleadColor.gold, topLeft = Offset(-s / 2, -s / 2), size = Size(s, s))
                }
            }
        }
    }
}

/** Tiny deterministic RNG so the burst looks the same every time (and in screenshots). Unsigned 64-bit, as Swift. */
class SplitMix(seed: Long) {
    private var state: ULong = seed.toULong()

    fun next(): Long {
        state += 0x9E3779B97F4A7C15uL
        var z = state
        z = (z xor (z shr 30)) * 0xBF58476D1CE4E5B9uL
        z = (z xor (z shr 27)) * 0x94D049BB133111EBuL
        return (z xor (z shr 31)).toLong()
    }

    /** Swift `unit()` (`CGFloat`, a Double on device): 53 random bits in [0, 1). */
    fun unitDouble(): Double = (next().toULong() shr 11).toDouble() / (1L shl 53).toDouble()

    fun unit(): Float = unitDouble().toFloat()
}
