// Port of ArgueWin/Courtroom/CourtDock.swift: the dock, the turn composer above the tab bar. What it shows comes from
// `CourtroomLogic.dockMode(state)`.
package app.plead.android.courtroom

import android.annotation.SuppressLint

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.BuildConfig
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.ExhibitThumbnail
import app.plead.android.designsystem.PixelAvatarView
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.fixedSp
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.models.Avatar
import app.plead.android.models.EdgeError
import app.plead.android.models.Exhibit
import app.plead.android.models.ExhibitType
import app.plead.android.models.ObjectionReason
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Cases whose exhibit explainer has already been shown this session (in-memory on purpose: a fresh launch is a fresh
 * reminder).
 */
object ExhibitExplainerMemory {
    val shownCases: MutableSet<UUID> = mutableSetOf()

    /** How long the one-line tip stays up (screenshot harnesses may pin it), seconds. */
    var autoHide: Double = 4.0
}

/** Swift `CourtDock` statics. */
object CourtDock {
    fun judgementTitle(m: JudgementDockMode, state: CourtroomState): String = when (m) {
        is JudgementDockMode.choose -> if (m.tie) "Choose a resolution" else "Choose the court's judgement"
        is JudgementDockMode.awaitingChoice ->
            "Waiting for ${state.profile(state.judgement?.chooserId)?.displayName ?: state.partner.displayName}"
        JudgementDockMode.respond -> "Judgement entered"
        is JudgementDockMode.markServed -> if (m.awaitingAcceptance) "Judgement delivered" else "Judgement accepted"
        JudgementDockMode.awaitingCourt -> "The court is choosing"
        JudgementDockMode.courtResolution -> "The court has chosen a resolution"
        JudgementDockMode.served -> if (state.judgement?.isCourtChosen == true) "Resolution served" else "Judgement served"
        JudgementDockMode.declined -> if (state.judgement?.isCourtChosen == true) "Resolution declined" else "Judgement declined"
    }

    fun judgementSubtitle(m: JudgementDockMode, state: CourtroomState): String = when (m) {
        is JudgementDockMode.choose ->
            if (m.tie) "The court could not separate you. Pick a fair compromise." else "You won the case. The court has prepared outcomes for you."
        is JudgementDockMode.awaitingChoice -> CourtroomLogic.pendingJudgeLine(state)
        JudgementDockMode.respond -> "Accepting is a promise to each other, not a legal commitment."
        is JudgementDockMode.markServed ->
            if (m.awaitingAcceptance) "Waiting for ${state.partner.displayName} to accept. Either of you can mark it served."
            else "Mark it served once it's done."
        JudgementDockMode.awaitingCourt -> CourtroomLogic.pendingJudgeLine(state)
        JudgementDockMode.courtResolution -> "It binds you both. Either of you can mark it served."
        JudgementDockMode.served -> "This matter is settled."
        JudgementDockMode.declined -> "The court notes the judgement was declined. No further action is needed."
    }
}

/**
 * The dock's `@State` plus the values SwiftUI reads from the view (refreshed every composition), so the pieces below can
 * be small private composables.
 */
private class DockController(val scope: CoroutineScope) {
    // @State
    var draft by mutableStateOf("")
    var selectedExhibitId by mutableStateOf<UUID?>(null)
    var busy by mutableStateOf(false)
    var errorMessage by mutableStateOf<String?>(null)
    var showObjectionSheet by mutableStateOf(false)
    var showExplainer by mutableStateOf(false)
    var confirmDecline by mutableStateOf(false)

    /** Per dock-mode density, stepped down when the dock measures taller than `heightBudget`. */
    val densities = mutableStateMapOf<String, CourtDockBudget.Density>()

    /** Bumped once when the dock enters a state where I must act (drives the single pulse). */
    var pulse by mutableIntStateOf(0)

    /** Amendment ae: the phase help sheet that is up (user-triggered only). Never touches `draft` or the deadline. */
    val help = CourtHelpPresentation()

    // View inputs (set on every composition).
    lateinit var state: CourtroomState
    lateinit var actions: CourtroomActions
    var onHearVerdict: (() -> Unit)? = null
    var onTranscript: (() -> Unit)? = null
    lateinit var focus: MutableState<Boolean>
    var heightBudget: Float? = null
    var motion: CourtMotionDirector? = null
    var reduceMotion = false
    var dynamicType = DynamicTypeSize.large

    val mode: DockMode
        // Amendment ad: while the case is being called the judge has the floor; the first turn (and the "Show your
        // evidence" tray) appears only once the judge hands it over.
        get() = CourtCaseCallGate.dockMode(CourtroomLogic.dockMode(state), calling = motion?.caseCallHold == true)

    /** Judgement actions take over the dock once the verdict is revealed (amendment j). */
    val jMode: JudgementDockMode? get() = CourtroomLogic.judgementDockMode(state)

    /** Settle Outside Court (amendment n). */
    val sMode: SettlementDockMode? get() = CourtroomLogic.settlementDockMode(state)

    /** Amendment ae: the help topic for what I can do right now (null = no info button). */
    val helpTopic: CourtHelpTopic? get() = CourtHelp.dockTopic(mode = mode, judgement = jMode, settlement = sMode)

    /** Density key: coarse dock mode (so showing one exhibit after another keeps its layout). */
    val densityKey: String
        get() {
            sMode?.let { return "settle-${it == SettlementDockMode.settled}" }
            jMode?.let { return "judgement-${it.caseName}" }
            return when (val m = mode) {
                is DockMode.compose -> if (m.kind is ComposeKind.presentExhibits) "present" else "compose"
                is DockMode.objectionWindow -> "objection"
                DockMode.verdictIn -> "verdict"
                else -> "status"
            }
        }

    val density: CourtDockBudget.Density get() = densities[densityKey] ?: CourtDockBudget.Density.regular
    val isRegular: Boolean get() = density == CourtDockBudget.Density.regular
    val isTight: Boolean get() = density == CourtDockBudget.Density.tight

    fun measured(h: Float) {
        // While typing the keyboard owns the screen; measure the resting dock only.
        if (focus.value) return
        val next = CourtDockBudget.density(after = density, measured = h, budget = heightBudget)
        if (next == density) return
        densities[densityKey] = next
    }

    /** The dock is asking me to do something (a turn, an objection, the verdict, a judgement step). */
    val mustAct: Boolean
        get() {
            if (sMode != null) return false
            jMode?.let {
                return when (it) {
                    is JudgementDockMode.choose, JudgementDockMode.respond, is JudgementDockMode.markServed,
                    JudgementDockMode.courtResolution -> true
                    else -> false
                }
            }
            return mode.isMyTurn || mode == DockMode.verdictIn
        }

    /** One pulse when the state changes to one where I must act; then the dock stays still. */
    fun pulseIfNeeded() {
        // Amendment ac / ad: not while the court entrance runs or the case is being called.
        val motion = motion ?: return
        if (!mustAct || motion.openingHold || !motion.claimPulse("${state.kase.id}-$headerTitle")) return
        pulse += 1
    }

    val isPresenting: Boolean get() = (mode as? DockMode.compose)?.kind is ComposeKind.presentExhibits

    /**
     * The first-time tip shows once per case per launch, inside the dock (one line), and goes away after 4 s or on the
     * first action.
     */
    fun updateExplainer() {
        if (isPresenting) {
            if (!ExhibitExplainerMemory.shownCases.contains(state.kase.id)) {
                ExhibitExplainerMemory.shownCases.add(state.kase.id)
                showExplainer = true
                scope.launch {
                    delay((ExhibitExplainerMemory.autoHide * 1000).toLong())
                    dismissExplainer()
                }
            }
        } else {
            showExplainer = false
        }
    }

    fun dismissExplainer() {
        if (!showExplainer) return
        showExplainer = false
    }

    /** The YOUR TURN caps line is up (my turn, full or compact dock). */
    val showsYourTurn: Boolean get() = jMode == null && sMode == null && mode.isMyTurn && !isTight

    val headerAccessibilityLabel: String
        get() = if (jMode == null && sMode == null && mode.isMyTurn) "Your turn. $headerTitle" else headerTitle

    val headerTitle: String
        get() {
            sMode?.let { return CourtroomLogic.settlementDockCopy(it, partnerName = state.partner.displayName).title }
            jMode?.let { return CourtDock.judgementTitle(it, state) }
            return when (val m = mode) {
                DockMode.notInSession -> "Court is not in session"
                DockMode.spectator -> "You're in the public gallery"
                DockMode.judgeHasFloor -> "${state.judgePersona.displayName} has the floor"
                is DockMode.waiting -> "Waiting for ${state.profile(m.partner).displayName}"
                is DockMode.compose -> CourtroomLogic.composeTitle(m.kind)
                is DockMode.objectionWindow -> "Object, or let it stand?"
                DockMode.deliberating -> "The court is deliberating"
                DockMode.verdictIn -> "The verdict is in"
                DockMode.stopped -> "This case has been stopped"
            }
        }

    /** One full-width line under the header (the compose modes say it in the placeholder instead). */
    val headerSubtitle: String?
        get() {
            sMode?.let { return CourtroomLogic.settlementDockCopy(it, partnerName = state.partner.displayName).subtitle }
            jMode?.let { return CourtDock.judgementSubtitle(it, state) }
            if (mode.isMyTurn && CourtroomLogic.isPastDeadline(state)) {
                return "Time's up. The judge may move on without you."
            }
            return when (val m = mode) {
                is DockMode.waiting -> CourtroomLogic.waitingLine(m.activity, name = state.profile(m.partner).displayName)
                DockMode.judgeHasFloor -> "The court will call on you shortly."
                is DockMode.objectionWindow ->
                    CourtroomLogic.objectionWindowLine(partnerName = state.partner.displayName, exhibit = state.exhibit(m.exhibitId))
                // Amendment av: the verdict lands as soon as the panel has ruled; no scheduled time.
                DockMode.deliberating -> "The judgement will be announced soon."
                DockMode.verdictIn -> "All rise."
                DockMode.stopped -> "No verdict will be given. Take care of each other."
                else -> null
            }
        }

    /** Primary buttons keep a 44 dp target; the full layout gives them a little more. */
    val buttonHeight: Dp get() = if (isRegular) 46.dp else 44.dp

    /** Whether `content` draws anything (SwiftUI `EmptyView` takes no space and no spacing). */
    val hasContent: Boolean
        get() {
            if (sMode == SettlementDockMode.settled) return true
            jMode?.let {
                return when (it) {
                    is JudgementDockMode.choose, JudgementDockMode.respond, is JudgementDockMode.markServed,
                    JudgementDockMode.courtResolution -> true
                    else -> false
                }
            }
            return when (mode) {
                is DockMode.compose, is DockMode.objectionWindow -> true
                DockMode.verdictIn -> onHearVerdict != null
                else -> false
            }
        }

    // MARK: Actions

    fun canSubmit(): Boolean = !busy && draft.trim().isNotEmpty()

    fun submit(kind: ComposeKind) {
        val typed = draft.trim()
        if (kind is ComposeKind.presentExhibits) {
            val id = CourtroomLogic.dockExhibitId(available = kind.available, chosen = selectedExhibitId) ?: return
            val ex = state.exhibit(id) ?: return
            val body = typed.ifEmpty { CourtroomLogic.defaultPresentLine(ex) }
            val submitTurn = actions.submitTurn
            perform(clearDraft = true) { submitTurn(body, id) }
            return
        }
        if (typed.isEmpty()) return
        val submitTurn = actions.submitTurn
        perform(clearDraft = true) { submitTurn(typed, null) }
    }

    fun rest() {
        val role = state.myRole ?: return
        val typed = draft.trim()
        val body = typed.ifEmpty { CourtroomLogic.restLine(role) }
        val submitTurn = actions.submitTurn
        perform(clearDraft = true) { submitTurn(body, null) }
    }

    fun perform(clearDraft: Boolean = false, op: suspend () -> Unit) {
        if (busy) return
        dismissExplainer()
        busy = true
        errorMessage = null
        scope.launch {
            try {
                op()
                if (clearDraft) {
                    draft = ""
                    selectedExhibitId = null
                    focus.value = false
                }
            } catch (e: CancellationException) {
                busy = false
                throw e
            } catch (e: EdgeError) {
                errorMessage = e.message
            } catch (e: Exception) {
                errorMessage = e.localizedMessage ?: e.toString()
            }
            busy = false
        }
    }
}

@Composable
fun CourtDock(
    state: CourtroomState,
    actions: CourtroomActions,
    modifier: Modifier = Modifier,
    onHearVerdict: (() -> Unit)? = null,
    onTranscript: (() -> Unit)? = null,
    focus: MutableState<Boolean>,
    /** Height the dock may take without reaching the podium name tags (`CourtDockBudget`); null = no limit. */
    heightBudget: Float? = null,
    /** Motion (amendment x): claims the one-time "your turn" pulse per state. */
    motion: CourtMotionDirector? = null,
    /** How far the panel / banner background extends below the dock (iOS `.ignoresSafeArea(edges: .bottom)`). */
    backgroundBleed: Float = 0f,
) {
    val scope = rememberCoroutineScope()
    val c = remember { DockController(scope) }
    val reduceMotion = accessibilityReduceMotion()
    c.state = state
    c.actions = actions
    c.onHearVerdict = onHearVerdict
    c.onTranscript = onTranscript
    c.focus = focus
    c.heightBudget = heightBudget
    c.motion = motion
    c.reduceMotion = reduceMotion
    c.dynamicType = dynamicTypeSize()

    val mode = c.mode
    val sMode = c.sMode
    val headerTitle = c.headerTitle
    val helpTopic = c.helpTopic
    val focusManager = LocalFocusManager.current

    // `.onAppear { updateExplainer() }` + `.onChange(of: mode)`; the panel's `.onChange(of: mode)` too.
    var firstMode by remember { mutableStateOf(true) }
    LaunchedEffect(mode) {
        c.updateExplainer()
        if (!firstMode) {
            c.errorMessage = null
            val m = mode
            if (m is DockMode.compose && m.kind is ComposeKind.presentExhibits) {
                val sel = c.selectedExhibitId
                if (sel != null && !m.kind.available.contains(sel)) c.selectedExhibitId = null
            }
        }
        firstMode = false
    }
    // Another screen / text size: start again from the full layout.
    val lastBudget = remember { mutableStateOf(heightBudget) }
    LaunchedEffect(heightBudget) {
        if (abs((lastBudget.value ?: 0f) - (heightBudget ?: 0f)) > 1f) c.densities.clear()
        lastBudget.value = heightBudget
    }
    LaunchedEffect(headerTitle, motion?.openingHold ?: false) { c.pulseIfNeeded() }
    LaunchedEffect(focus.value) {
        if (focus.value) c.dismissExplainer() else focusManager.clearFocus()
    }
    // `-AWCourtHelp <topic>` (captures): open it once, as soon as the court offers that topic.
    val currentHelp by rememberUpdatedState(helpTopic)
    LaunchedEffect(helpTopic) {
        if (!BuildConfig.DEBUG) return@LaunchedEffect
        val t = helpTopic ?: return@LaunchedEffect
        if (t != CourtHelp.debugRequested || CourtHelp.debugOpened) return@LaunchedEffect
        delay(800)
        if (currentHelp != t) return@LaunchedEffect
        CourtHelp.debugOpened = true
        c.help.open(t, offered = currentHelp)
    }

    Box(modifier.semantics { isTraversalGroup = true }) {
        DynamicTypeCap(DynamicTypeSize.xxxLarge) {
            Crossfade(
                targetState = sMode is SettlementDockMode.pending,
                animationSpec = if (reduceMotion) tween(0) else PleadMotion.gentle(),
                label = "dockSettlement",
            ) { pending ->
                val p = sMode as? SettlementDockMode.pending
                if (pending && p != null) {
                    SettlementPendingBanner(
                        round = p.round, awaitingMe = p.awaitingMe,
                        subtitle = CourtroomLogic.settlementDockCopy(p, partnerName = state.partner.displayName).subtitle,
                        onTranscript = onTranscript, backgroundBleed = backgroundBleed, onOpen = actions.openSettlement,
                    )
                } else {
                    DockPanel(c, backgroundBleed)
                }
            }
        }
    }
    // Amendment ae: outside the xxxLarge clamp so the sheet follows the user's full text size.
    CourtHelpSheetHost(c.help, deadline = CourtHelp.deadline(state))

    val objectionMode = mode as? DockMode.objectionWindow
    val objectionExhibit = objectionMode?.let { state.exhibit(it.exhibitId) }
    if (c.showObjectionSheet && objectionExhibit != null) {
        CourtSheet(onDismiss = { c.showObjectionSheet = false }) {
            CourtObjectionSheet(
                exhibit = objectionExhibit, ownerName = state.partner.displayName,
                onPick = { reason ->
                    c.showObjectionSheet = false
                    val raise = actions.raiseObjection
                    c.perform { raise(objectionExhibit.id, reason) }
                },
                onDismiss = { c.showObjectionSheet = false },
            )
        }
    }
    if (c.confirmDecline) {
        val court = c.jMode == JudgementDockMode.courtResolution
        AlertDialog(
            onDismissRequest = { c.confirmDecline = false },
            title = { Text(if (court) "Decline the court's resolution?" else "Decline the court's judgement?") },
            text = { Text("Your partner will be told.") },
            confirmButton = {
                TextButton(onClick = {
                    c.confirmDecline = false
                    val respond = actions.respondJudgement
                    c.perform { respond(false) }
                }) { Text("Decline", color = PleadColor.burgundy) }
            },
            dismissButton = { TextButton(onClick = { c.confirmDecline = false }) { Text("Keep it", color = PleadColor.cocoa) } },
        )
    }
}

// MARK: - Panel

/** State change: the old controls leave quickly, the new ones fade in once the panel has resized. */
private fun stateContentTransition(reduceMotion: Boolean) =
    fadeIn(tween(180, delayMillis = if (reduceMotion) 0 else 140, easing = PleadMotion.easeOut)) togetherWith
        fadeOut(tween(80, easing = PleadMotion.easeOut))

@SuppressLint("UnusedContentLambdaTargetStateParameter") // the outgoing state leaves in 80 ms (Swift `.id(densityKey)`)
@Composable
private fun DockPanel(c: DockController, backgroundBleed: Float) {
    val density = LocalDensity.current
    val reduceMotion = c.reduceMotion
    val rim = remember { Animatable(0f) }
    val yourTurnScale = remember { Animatable(1f) }
    val pulse = c.pulse
    LaunchedEffect(pulse) {
        if (pulse == 0) return@LaunchedEffect
        val total = CourtMotionTiming.pulse * 1000
        launch {
            rim.snapTo(0f)
            rim.animateTo(0.9f, tween((total * 0.3).roundToInt(), easing = PleadMotion.easeInOut))
            rim.animateTo(0f, tween((total * 0.7).roundToInt(), easing = PleadMotion.easeInOut))
        }
        if (!reduceMotion) {
            yourTurnScale.animateTo(1.08f, tween((total * 0.3).roundToInt(), easing = PleadMotion.easeInOut))
            yourTurnScale.animateTo(1f, tween((total * 0.7).roundToInt(), easing = PleadMotion.easeInOut))
        }
    }
    val spacing = if (c.isRegular) PleadSpacing.s else if (c.isTight) PleadSpacing.xs else 6.dp
    val radius = PleadRadius.card
    val sub = c.headerSubtitle
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { c.measured(with(density) { it.height.toDp().value }) }
            .drawBehind {
                topRoundedPanel(
                    radius = radius.toPx(), bleed = backgroundBleed.dp.toPx(), fill = CourtColor.panel,
                    rim = CourtColor.panelRim, rimWidth = 2.dp.toPx(), shadow = Color.Black.copy(alpha = 0.35f),
                    shadowRadius = 10.dp.toPx(), shadowY = (-2).dp.toPx(),
                )
            }
            .drawWithContent {
                drawContent()
                // The single "your turn" pulse: the carved rim glows blush once, then the dock is still.
                if (rim.value > 0f) {
                    val r = radius.toPx()
                    val path = Path().apply {
                        addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height), topLeft = CornerRadius(r), topRight = CornerRadius(r)))
                    }
                    drawPath(
                        path,
                        brush = Brush.verticalGradient(listOf(PleadColor.blush, PleadColor.blush.copy(alpha = 0f)), startY = 0f, endY = size.height * 0.4f),
                        alpha = rim.value,
                        style = Stroke(2.5.dp.toPx()),
                    )
                }
            }
            .then(if (reduceMotion) Modifier else Modifier.animateContentSize(PleadMotion.gentle()))
            .padding(horizontal = PleadSpacing.l)
            .padding(top = if (c.isRegular) 10.dp else if (c.isTight) 6.dp else 8.dp,
                bottom = if (c.isRegular) PleadSpacing.s else if (c.isTight) PleadSpacing.xs else 6.dp),
    ) {
        DockHeader(c, yourTurnScale.value, Modifier.accessibilitySortPriority(3f))
        if (sub != null) {
            Spacer(Modifier.height(spacing))
            AnimatedContent(
                targetState = sub,
                transitionSpec = { stateContentTransition(reduceMotion) using SizeTransform(clip = false) },
                label = "dockSubtitle",
                modifier = Modifier.accessibilitySortPriority(2f),
            ) { text ->
                ScaledText(
                    text, style = CourtFont.footnote, color = CourtColor.creamSoft,
                    minimumScaleFactor = if (c.isRegular) 1f else 0.85f, maxLines = if (c.isRegular) 2 else 1,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        AnimatedVisibility(
            visible = c.showExplainer && c.isPresenting && !c.isTight,
            enter = fadeIn(if (reduceMotion) tween(0) else PleadMotion.gentle()),
            exit = fadeOut(if (reduceMotion) tween(0) else PleadMotion.gentle()),
            modifier = Modifier.accessibilitySortPriority(1f),
        ) {
            Column {
                Spacer(Modifier.height(spacing))
                ExplainerLine(c)
            }
        }
        if (c.hasContent) {
            Spacer(Modifier.height(spacing))
            AnimatedContent(
                targetState = c.densityKey,
                transitionSpec = { stateContentTransition(reduceMotion) using SizeTransform(clip = false) },
                label = "dockContent",
                modifier = Modifier.accessibilitySortPriority(4f),
            ) { _ ->
                DockContent(c)
            }
        }
        val error = c.errorMessage
        if (error != null) {
            Spacer(Modifier.height(spacing))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .accessibilitySortPriority(5f)
                    .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            ) {
                Icon(SFSymbol.icon("exclamationmark.circle.fill"), contentDescription = null, tint = PleadColor.blush, modifier = Modifier.size(16.dp))
                Text(error, style = CourtFont.footnote, color = PleadColor.blush)
            }
        }
    }
}

/** The panel's top-rounded body (and its `.shadow`), drawn [bleed] px past the bottom edge, with the carved rim. */
private fun DrawScope.topRoundedPanel(
    radius: Float,
    bleed: Float,
    fill: Color,
    rim: Color,
    rimWidth: Float,
    shadow: Color,
    shadowRadius: Float,
    shadowY: Float,
) {
    val path = Path().apply {
        addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height + bleed), topLeft = CornerRadius(radius), topRight = CornerRadius(radius)))
    }
    drawIntoCanvas { canvas ->
        val paint = Paint()
        val fp = paint.asFrameworkPaint()
        fp.color = fill.toArgb()
        fp.setShadowLayer(shadowRadius * 0.866f, 0f, shadowY, shadow.toArgb())
        canvas.drawPath(path, paint)
    }
    drawPath(path, rim, style = Stroke(rimWidth))
}

/** `.padding(.horizontal, -d)`: the child is laid out 2·d wider than it reports, overlapping its neighbours' gaps. */
private fun Modifier.overlapHorizontal(d: Dp): Modifier = layout { measurable, constraints ->
    val px = d.roundToPx()
    val p = measurable.measure(constraints.copy(minWidth = 0, maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * px else constraints.maxWidth))
    layout(max(0, p.width - 2 * px), p.height) { p.place(-px, 0) }
}

@Composable
private fun ExplainerLine(c: DockController) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = "Dismisses the tip", role = SemanticsRole.Button,
            ) { c.dismissExplainer() }
            .clearAndSetSemantics { contentDescription = "Tip: ${CourtroomLogic.exhibitExplainer}" },
    ) {
        Icon(SFSymbol.icon("lightbulb.fill"), contentDescription = null, tint = PleadColor.gold, modifier = Modifier.size(15.dp))
        ScaledText(
            CourtroomLogic.exhibitExplainerShort,
            style = if (c.isRegular) CourtFont.footnoteMedium else CourtFont.caption,
            color = PleadColor.cream, minimumScaleFactor = 0.85f, modifier = Modifier.weight(1f),
        )
    }
}

// MARK: Header (whose turn + countdown)

@SuppressLint("UnusedCrossfadeTargetStateParameter") // Swift `.id(headerTitle).transition(.opacity)`
@Composable
private fun DockHeader(c: DockController, yourTurnScale: Float, modifier: Modifier) {
    val reduceMotion = c.reduceMotion
    val title = c.headerTitle
    val helpTopic = c.helpTopic
    val ms = (CourtMotionTiming.stateTransition * 1000).roundToInt()
    val risePx = with(LocalDensity.current) { 6.dp.roundToPx() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 40.dp),
    ) {
        Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) {
            Crossfade(targetState = title, animationSpec = if (reduceMotion) tween(0) else tween(ms), label = "dockIcon") { _ ->
                HeaderIcon(c)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
            modifier = Modifier.weight(1f),
        ) {
            Box(Modifier.weight(1f, fill = false)) {
                AnimatedContent(
                    targetState = title,
                    transitionSpec = {
                        val spec = tween<Float>(ms, easing = PleadMotion.easeInOut)
                        if (reduceMotion) {
                            fadeIn(spec) togetherWith fadeOut(spec)
                        } else {
                            (fadeIn(spec) + slideInVertically(tween(ms, easing = PleadMotion.easeInOut)) { risePx }) togetherWith
                                fadeOut(spec)
                        }
                    },
                    label = "dockTitle",
                ) { t ->
                    HeaderTitleBlock(c, t, yourTurnScale)
                }
                // Amendment ae: on my turn the info button rides the YOUR TURN line, right above the phase label.
                if (c.showsYourTurn && helpTopic != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        YourTurnLabel(1f, Modifier.alpha(0f).accessibilityHidden())
                        Box(Modifier.height(16.dp), contentAlignment = Alignment.CenterStart) {
                            HelpButton(c, helpTopic, Modifier.wrapContentHeight(unbounded = true))
                        }
                    }
                }
            }
            if (helpTopic != null && !c.showsYourTurn) {
                HelpButton(c, helpTopic, Modifier.accessibilitySortPriority(-1f))
            }
        }
        HeaderTrailing(c)
    }
}

/** The info button beside the phase label (amendment ae). */
@Composable
private fun HelpButton(c: DockController, topic: CourtHelpTopic, modifier: Modifier = Modifier) {
    val focusManager = LocalFocusManager.current
    CourtHelpButton(topic, modifier = modifier.overlapHorizontal(10.dp), tint = CourtColor.creamSoft) {
        c.focus.value = false
        focusManager.clearFocus()
        c.help.open(topic, offered = c.helpTopic)
    }
}

@Composable
private fun YourTurnLabel(scale: Float, modifier: Modifier = Modifier) {
    Text(
        "YOUR TURN",
        style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
        color = PleadColor.blush,
        maxLines = 1,
        softWrap = false,
        modifier = modifier.graphicsLayer {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0f, 0.5f)
        },
    )
}

@Composable
private fun HeaderTitleBlock(c: DockController, title: String, yourTurnScale: Float) {
    val dts = c.dynamicType
    Column(
        verticalArrangement = Arrangement.spacedBy(1.dp),
        modifier = Modifier
            .testTag("court.dock.header")
            .clearAndSetSemantics {
                contentDescription = c.headerAccessibilityLabel
                heading()
            },
    ) {
        if (c.showsYourTurn) YourTurnLabel(if (c.reduceMotion) 1f else yourTurnScale)
        // One line (shrinking a touch) up to xLarge; larger text wraps to two lines rather than truncating.
        ScaledText(
            title, style = CourtFont.instruction, color = PleadColor.cream, minimumScaleFactor = 0.75f,
            maxLines = if (dts >= DynamicTypeSize.xxLarge) 2 else (if (c.isPresenting || c.mode.isMyTurn) 1 else 2),
        )
    }
}

/** 36 dp inset tile in a 44 dp target (the transcript / replay / case-actions buttons). */
@Composable
private fun DockIconButton(
    symbol: String,
    label: String,
    hint: String?,
    tint: Color = PleadColor.cream,
    tile: Color = CourtColor.panelInset,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = hint, role = SemanticsRole.Button, onClick = onClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(36.dp).background(tile, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
            Icon(SFSymbol.icon(symbol), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun HeaderTrailing(c: DockController) {
    val onHear = c.onHearVerdict
    if (c.jMode != null && onHear != null) {
        DockIconButton("play.rectangle", "Replay the verdict", null, onClick = onHear)
    }
    val target = CourtroomLogic.countdownTarget(c.state)
    if (c.jMode == null && c.sMode == null && c.mode != DockMode.stopped && target != null) {
        CourtCountdown(target, label = if (c.mode == DockMode.deliberating) "until ruling" else "left")
    }
    val onTranscript = c.onTranscript
    if (CourtroomLogic.showsProposeSettlement(c.state)) {
        // The case-actions menu takes the transcript button's slot (so the header keeps its width).
        CaseActionsMenu(c)
    } else if (onTranscript != null) {
        DockIconButton("text.bubble", "Transcript", "Everything said in court so far", onClick = onTranscript)
    }
}

/** The small "case actions" menu (ellipsis): Propose settlement, before closings only. */
@Composable
private fun CaseActionsMenu(c: DockController) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DockIconButton("ellipsis", "Case actions", "Propose a settlement, or read the transcript") { expanded = true }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Propose settlement") },
                leadingIcon = { Icon(SFSymbol.icon("hands.and.sparkles"), contentDescription = null) },
                onClick = {
                    expanded = false
                    c.actions.proposeSettlement()
                },
            )
            val onTranscript = c.onTranscript
            if (onTranscript != null) {
                DropdownMenuItem(
                    text = { Text("Transcript") },
                    leadingIcon = { Icon(SFSymbol.icon("text.bubble"), contentDescription = null) },
                    onClick = {
                        expanded = false
                        onTranscript()
                    },
                )
            }
        }
    }
}

@Composable
private fun InsetTile(fill: Color, content: @Composable () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(fill).accessibilityHidden(),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun JudgeTile(c: DockController) {
    InsetTile(CourtColor.panelInset) { JudgeSprite(c.state.judgePersona, cell = 2f) }
}

@Composable
private fun AvatarTile(avatar: Avatar) {
    InsetTile(CourtColor.panelInset) { PixelAvatarView(avatar, size = 32.dp) }
}

@Composable
private fun HeaderIcon(c: DockController) {
    val jMode = c.jMode
    when {
        c.sMode == SettlementDockMode.settled -> InsetTile(PleadColor.parchment) {
            Icon(SFSymbol.icon("checkmark.seal.fill"), contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(22.dp))
        }
        jMode != null -> when (jMode) {
            is JudgementDockMode.awaitingChoice -> AvatarTile(c.state.partner.avatar)
            JudgementDockMode.served -> InsetTile(CourtColor.panelInset) {
                Icon(SFSymbol.icon("checkmark.seal.fill"), contentDescription = null, tint = PleadColor.gold, modifier = Modifier.size(22.dp))
            }
            else -> JudgeTile(c)
        }
        else -> when (val m = c.mode) {
            is DockMode.waiting -> AvatarTile(c.state.profile(m.partner).avatar)
            is DockMode.compose, is DockMode.objectionWindow -> AvatarTile(c.state.me.avatar)
            DockMode.judgeHasFloor, DockMode.deliberating, DockMode.verdictIn -> JudgeTile(c)
            DockMode.stopped ->
                Icon(SFSymbol.icon("hand.raised"), contentDescription = null, tint = CourtColor.creamSoft, modifier = Modifier.size(22.dp))
            DockMode.notInSession, DockMode.spectator ->
                Icon(SFSymbol.icon("building.columns"), contentDescription = null, tint = CourtColor.creamSoft, modifier = Modifier.size(22.dp))
        }
    }
}

// MARK: Content

@Composable
private fun BusySpinner() {
    CircularProgressIndicator(color = PleadColor.cream, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
    Spacer(Modifier.width(6.dp))
}

@Composable
private fun DockContent(c: DockController) {
    val jMode = c.jMode
    when {
        c.sMode == SettlementDockMode.settled -> CourtButton(
            onClick = { c.actions.backToDocket() },
            style = CourtButtonStyle(kind = CourtButtonStyle.Kind.secondary, fullWidth = true, minHeight = c.buttonHeight),
            onClickLabel = "Opens the Cases tab",
        ) {
            Icon(SFSymbol.icon("folder"), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(CourtroomLogic.settlementButtonTitle(SettlementDockMode.settled))
        }
        jMode != null -> JudgementContent(c, jMode)
        else -> ModeContent(c)
    }
}

@Composable
private fun JudgementContent(c: DockController, m: JudgementDockMode) {
    when (m) {
        is JudgementDockMode.choose -> CourtButton(
            onClick = { c.actions.chooseJudgement() },
            style = CourtButtonStyle(fullWidth = true, minHeight = c.buttonHeight),
            onClickLabel = "Pick one of the court's prepared outcomes",
        ) {
            Icon(SFSymbol.icon("hammer.fill"), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Choose judgement")
        }
        JudgementDockMode.respond -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            CourtButton(
                onClick = {
                    val respond = c.actions.respondJudgement
                    c.perform { respond(true) }
                },
                style = CourtButtonStyle(fullWidth = true, minHeight = c.buttonHeight),
                enabled = !c.busy,
                onClickLabel = "Tell your partner you accept the court's judgement",
            ) {
                if (c.busy) BusySpinner()
                Text("Accept judgement")
            }
            QuietDecline(c)
        }
        is JudgementDockMode.markServed -> MarkServedButton(c)
        // Tie (amendment l): no Accept. Either partner marks it served, or quietly declines.
        JudgementDockMode.courtResolution -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            MarkServedButton(c)
            QuietDecline(c)
        }
        // The subtitle says it all ("This matter is settled."); the card in the scene opens the full text.
        JudgementDockMode.served, JudgementDockMode.declined, is JudgementDockMode.awaitingChoice, JudgementDockMode.awaitingCourt -> Unit
    }
}

@Composable
private fun MarkServedButton(c: DockController) {
    CourtButton(
        onClick = {
            val markServed = c.actions.markServed
            c.perform { markServed() }
        },
        style = CourtButtonStyle(fullWidth = true, minHeight = c.buttonHeight),
        enabled = !c.busy,
        onClickLabel = "Records that the judgement has been carried out",
    ) {
        if (c.busy) BusySpinner()
        Text("Mark as served")
    }
}

/** The quiet "Decline" text action under the primary button. */
@Composable
private fun QuietDecline(c: DockController) {
    Box(
        Modifier
            .defaultMinSize(minWidth = 88.dp, minHeight = 36.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !c.busy,
                onClickLabel = "Asks for confirmation", role = SemanticsRole.Button,
            ) { c.confirmDecline = true },
        contentAlignment = Alignment.Center,
    ) {
        Text("Decline", style = CourtFont.link.copy(textDecoration = TextDecoration.Underline), color = CourtColor.creamSoft)
    }
}

@Composable
private fun ModeContent(c: DockController) {
    when (val m = c.mode) {
        is DockMode.compose -> Composer(c, m.kind)
        // The exhibit itself is up on the easel (tap it for the full view).
        is DockMode.objectionWindow -> ObjectionButtons(c)
        DockMode.verdictIn -> {
            val onHear = c.onHearVerdict
            if (onHear != null) {
                CourtButton(
                    onClick = onHear,
                    style = CourtButtonStyle(fullWidth = true, minHeight = c.buttonHeight),
                    modifier = Modifier.testTag("court.hearVerdict"),
                ) { Text("Hear the verdict") }
            }
        }
        else -> Unit
    }
}

@Composable
private fun Composer(c: DockController, kind: ComposeKind) {
    if (kind is ComposeKind.presentExhibits) {
        ExhibitComposer(c, kind.available)
        return
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), modifier = Modifier.fillMaxWidth()) {
        val shape = RoundedCornerShape(PleadRadius.tile)
        Box(
            Modifier
                .weight(1f)
                .background(CourtColor.panelInset, shape)
                .border(1.dp, CourtColor.panelRim, shape)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            DockTextField(c, kind)
        }
        CourtButton(
            onClick = { c.submit(kind) },
            style = CourtButtonStyle(minHeight = c.buttonHeight),
            enabled = c.canSubmit(),
            modifier = Modifier.testTag("court.submit"),
        ) {
            if (c.busy) BusySpinner()
            Text("Submit")
        }
    }
}

@Composable
private fun DockTextField(c: DockController, kind: ComposeKind) {
    val present = kind.isPresent
    val font = (if (present && !c.isRegular) CourtFont.callout else CourtFont.body).copy(color = PleadColor.cream)
    val maxLines = if (present) (if (c.isRegular) 3 else if (c.isTight) 1 else 2) else (if (c.isRegular) 5 else 3)
    val label = if (present) "Commentary, optional" else CourtroomLogic.composeTitle(kind)
    BasicTextField(
        value = c.draft,
        onValueChange = { c.draft = it },
        enabled = !c.busy,
        textStyle = font,
        cursorBrush = SolidColor(PleadColor.blush),
        minLines = 1,
        maxLines = maxLines,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused != c.focus.value && (it.isFocused || c.focus.value)) c.focus.value = it.isFocused }
            .semantics { contentDescription = label }
            .testTag(if (present) "court.commentary" else "court.compose"),
        decorationBox = { inner ->
            Box {
                if (c.draft.isEmpty()) {
                    Text(CourtroomLogic.composePlaceholder(kind), style = font, color = CourtColor.creamMuted, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
                }
                inner()
            }
        },
    )
}

/**
 * Presenter's exhibits phase: the next exhibit is loaded (with the optional commentary in the same card); one tap shows
 * it. "N exhibits left" and a small Rest link share the button row.
 */
@Composable
private fun ExhibitComposer(c: DockController, available: List<UUID>) {
    val state = c.state
    val options = available.mapNotNull { state.exhibit(it) }
    val current = state.exhibit(CourtroomLogic.dockExhibitId(available = available, chosen = c.selectedExhibitId))
    Column(verticalArrangement = Arrangement.spacedBy(if (c.isRegular) 6.dp else 4.dp), modifier = Modifier.fillMaxWidth()) {
        if (current != null) {
            ExhibitCard(c, current, options.filter { it.id != current.id }, available, Modifier.accessibilitySortPriority(1f))
            // Show first for accessibility; "N left · Rest" shares the row (shortened before stacking).
            val candidates = buildList<@Composable () -> Unit> {
                if (c.isRegular) add { ShowRow(c, current, available, short = false) }
                add { ShowRow(c, current, available, short = true) }
                add {
                    Column {
                        ShowButton(c, current, available, Modifier.fillMaxWidth())
                        LeftAndRest(c, available.size, short = true)
                    }
                }
            }
            ViewThatFits(modifier = Modifier.fillMaxWidth().accessibilitySortPriority(2f), candidates = candidates)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { },
                ) {
                    Icon(SFSymbol.icon("checkmark.seal"), contentDescription = null, tint = CourtColor.creamSoft, modifier = Modifier.size(16.dp))
                    Text("All your evidence is in. Rest to hand over.", style = CourtFont.footnote, color = CourtColor.creamSoft)
                }
                CourtButton(
                    onClick = { c.rest() },
                    style = CourtButtonStyle(minHeight = c.buttonHeight),
                    enabled = !c.busy,
                    onClickLabel = "Tell the court you have no further evidence and hand over",
                    modifier = Modifier.testTag("court.rest"),
                ) { Text("Rest") }
            }
        }
    }
}

@Composable
private fun ShowRow(c: DockController, current: Exhibit, available: List<UUID>, short: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier.fillMaxWidth(),
    ) {
        LeftAndRest(c, available.size, short = short, modifier = Modifier.padding(end = PleadSpacing.s).accessibilitySortPriority(1f))
        ShowButton(c, current, available, Modifier.accessibilitySortPriority(2f))
    }
}

@Composable
private fun ShowButton(c: DockController, current: Exhibit, available: List<UUID>, modifier: Modifier = Modifier) {
    CourtButton(
        onClick = { c.submit(ComposeKind.presentExhibits(available)) },
        style = CourtButtonStyle(minHeight = c.buttonHeight),
        enabled = !c.busy,
        onClickLabel = "Puts ${current.displayName} before the court",
        modifier = modifier.testTag("court.show"),
    ) {
        if (c.busy) BusySpinner()
        Icon(SFSymbol.icon("hand.point.up.left.fill"), contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(CourtroomLogic.showButtonTitle(current), maxLines = 1, softWrap = false)
    }
}

@Composable
private fun LeftAndRest(c: DockController, count: Int, short: Boolean, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PleadSpacing.xs), modifier = modifier) {
        Text(
            if (short) CourtroomLogic.exhibitsLeftTiny(count) else CourtroomLogic.exhibitsLeftShort(count),
            style = CourtFont.footnote, color = CourtColor.creamSoft, maxLines = 1, softWrap = false,
            modifier = Modifier.clearAndSetSemantics { contentDescription = CourtroomLogic.exhibitsLeftLine(count) },
        )
        Text("·", style = CourtFont.footnote, color = CourtColor.creamMuted, modifier = Modifier.accessibilityHidden())
        Box(
            Modifier
                .defaultMinSize(minWidth = 36.dp, minHeight = 44.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !c.busy,
                    onClickLabel = "Tell the court you have no further evidence and hand over", role = SemanticsRole.Button,
                ) { c.rest() }
                .clearAndSetSemantics { contentDescription = "Rest" }
                .testTag("court.rest"),
            contentAlignment = Alignment.Center,
        ) {
            Text("Rest", style = CourtFont.link.copy(textDecoration = TextDecoration.Underline), color = PleadColor.cream)
        }
    }
}

private fun icon(t: ExhibitType): String = when (t) {
    ExhibitType.photo -> "photo"
    ExhibitType.screenshot -> "iphone"
    ExhibitType.voice -> "waveform"
    ExhibitType.text -> "quote.opening"
    // iOS `doc.plaintext`: the receipt glyph (SFSymbol map).
    ExhibitType.receipt -> "receipt"
}

/** One card: the loaded exhibit (thumbnail, label, caption, Change) over the optional commentary. */
@Composable
private fun ExhibitCard(c: DockController, ex: Exhibit, changeOptions: List<Exhibit>, available: List<UUID>, modifier: Modifier) {
    val shape = RoundedCornerShape(PleadRadius.tile)
    val caption = ex.caption.ifEmpty { ex.body ?: "" }
    val thumb = if (c.isRegular) 40.dp else if (c.isTight) 28.dp else 34.dp
    Column(modifier.fillMaxWidth().background(CourtColor.panelInset, shape).border(1.dp, CourtColor.panelRim, shape)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .padding(start = if (c.isRegular) PleadSpacing.s else 6.dp, end = PleadSpacing.s)
                .padding(vertical = if (c.isRegular) 2.dp else 0.dp),
        ) {
            ExhibitThumbnail(
                type = ex.type, caption = ex.caption, body = ex.body, imageURL = c.state.exhibitURLs[ex.id]?.toString(), compact = true,
                modifier = Modifier.size(thumb).clip(RoundedCornerShape(if (c.isRegular) 8.dp else 6.dp)).accessibilityHidden(),
            )
            Box(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "Next exhibit: ${ex.displayName}, $caption" }) {
                if (c.isTight) {
                    // One line: "Exhibit B · The 'just for a minute' receipt".
                    Text(
                        buildAnnotatedString {
                            withStyle(CourtFont.legal.toSpanStyle().copy(letterSpacing = PleadType.capsTracking.sp, color = PleadColor.gold)) {
                                append(ex.displayName.uppercase())
                            }
                            withStyle(CourtFont.exhibitTitle.toSpanStyle().copy(color = PleadColor.cream)) { append("  $caption") }
                        },
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Column {
                        Text(
                            ex.displayName.uppercase(), style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
                            color = PleadColor.gold, maxLines = 1,
                        )
                        Text(caption, style = CourtFont.exhibitTitle, color = PleadColor.cream, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (changeOptions.isNotEmpty()) {
                var expanded by remember { mutableStateOf(false) }
                Box {
                    Box(
                        Modifier
                            .defaultMinSize(minHeight = 44.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() }, indication = null,
                                onClickLabel = "Choose a different exhibit to show", role = SemanticsRole.Button,
                            ) { expanded = true }
                            .semantics { contentDescription = "Change exhibit" },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .heightIn(min = if (c.isRegular) 30.dp else 26.dp)
                                .border(1.dp, PleadColor.cream.copy(alpha = 0.45f), CircleShape)
                                .padding(horizontal = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("Change", style = CourtFont.link, color = PleadColor.cream, modifier = Modifier.clearAndSetSemantics { })
                        }
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        changeOptions.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text("${opt.displayName}: ${opt.caption}") },
                                leadingIcon = { Icon(SFSymbol.icon(icon(opt.type)), contentDescription = null) },
                                onClick = {
                                    expanded = false
                                    c.dismissExplainer()
                                    c.selectedExhibitId = opt.id
                                },
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CourtColor.panelRim))
        Box(Modifier.padding(horizontal = 12.dp, vertical = if (c.isRegular) 7.dp else if (c.isTight) 4.dp else 5.dp)) {
            DockTextField(c, ComposeKind.presentExhibits(available))
        }
    }
}

@Composable
private fun ObjectionButtons(c: DockController) {
    Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), modifier = Modifier.fillMaxWidth()) {
        CourtButton(
            onClick = {
                val m = c.mode
                if (m is DockMode.objectionWindow) {
                    val raise = c.actions.raiseObjection
                    c.perform { raise(m.exhibitId, null) }
                }
            },
            style = CourtButtonStyle(kind = CourtButtonStyle.Kind.secondary, fullWidth = true, minHeight = c.buttonHeight),
            enabled = !c.busy,
            onClickLabel = "Let the exhibit stand without objection",
            modifier = Modifier.weight(1f).testTag("court.letItStand").accessibilitySortPriority(1f),
        ) { Text("Let it stand", maxLines = 1) }
        CourtButton(
            onClick = { c.showObjectionSheet = true },
            style = CourtButtonStyle(fullWidth = true, minHeight = c.buttonHeight),
            enabled = !c.busy,
            onClickLabel = "Choose a reason to object",
            modifier = Modifier.weight(1f).testTag("court.object").accessibilitySortPriority(2f),
        ) {
            if (c.busy) BusySpinner()
            Text("Object", maxLines = 1)
        }
    }
}

// MARK: - Countdown

/**
 * Live countdown (ticks every second on screen). Cream normally; deadline gold when under two hours. TalkBack hears a
 * label ("Time left") and a value in whole minutes, so it updates once a minute.
 */
@Composable
fun CourtCountdown(target: Instant, modifier: Modifier = Modifier, label: String = "left") {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(target) {
        while (true) {
            now = Instant.now()
            delay(1000 - (System.currentTimeMillis() % 1000))
        }
    }
    val remaining = Duration.between(now, target).toMillis() / 1000.0
    val urgent = remaining < 2 * 3600
    // PleadType.timer at the brief's lower bound (18, scaled like title3), capped at ≈ the xxxLarge clamp.
    val fontScale = min(LocalDensity.current.fontScale, DynamicTypeSize.xxxLarge.scale)
    val timerSize = min(18f * fontScale, 24f)
    Column(
        horizontalAlignment = Alignment.End,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = if (label == "left") "Time left" else "Time $label"
            stateDescription = CourtroomLogic.spokenCountdown(remaining)
        },
    ) {
        Text(
            CourtroomLogic.formatRemaining(remaining),
            style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = fixedSp(timerSize)).monospacedDigit(),
            color = if (urgent) CourtColor.deadlineGold else PleadColor.cream,
            maxLines = 1,
            softWrap = false,
        )
        Text(label, style = CourtFont.caption2.cappedAt(DynamicTypeSize.xxxLarge), color = CourtColor.creamMuted, maxLines = 1, softWrap = false)
    }
}

// MARK: - Objection sheet

/** The five fixed objection reasons. */
object CourtObjectionSheet {
    fun detail(r: ObjectionReason): String = when (r) {
        ObjectionReason.irrelevant -> "It has nothing to do with the charge."
        ObjectionReason.hearsay -> "It's based on what someone else said."
        ObjectionReason.outOfContext -> "Something important has been left out."
        ObjectionReason.speculation -> "It's a guess, not evidence."
        ObjectionReason.notWhatHappened -> "That's not how it went."
    }
}

@Composable
fun CourtObjectionSheet(exhibit: Exhibit, ownerName: String, onPick: (ObjectionReason) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(PleadColor.cream)) {
        CourtSheetTopBar(title = "Objection", leadingTitle = "Cancel", onLeading = onDismiss)
        Column(
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(PleadSpacing.l),
        ) {
            Text(
                "Tell the judge why $ownerName's ${exhibit.displayName} shouldn't count. You can object once per exhibit.",
                style = CourtFont.footnote, color = PleadColor.walnut, modifier = Modifier.padding(bottom = PleadSpacing.xs),
            )
            ObjectionReason.entries.forEach { reason ->
                val shape = RoundedCornerShape(PleadRadius.tile)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(PleadColor.parchment, shape)
                        .clickable(onClickLabel = CourtObjectionSheet.detail(reason), role = SemanticsRole.Button) { onPick(reason) }
                        .padding(PleadSpacing.l),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(reason.title, style = CourtFont.buttonSecondary, color = PleadColor.cocoa)
                        Text(CourtObjectionSheet.detail(reason), style = CourtFont.footnote, color = PleadColor.walnut)
                    }
                    Icon(SFSymbol.icon("chevron.right"), contentDescription = null, tint = PleadColor.walnut, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

// MARK: - Settlement pending banner

/**
 * Settle Outside Court (amendment n): while an offer is pending the dock steps aside for a calm parchment banner. No
 * turn timer; one button opens the settlement (the app picks the response sheet or the waiting room).
 */
@Composable
fun SettlementPendingBanner(
    round: Int,
    awaitingMe: Boolean,
    subtitle: String,
    modifier: Modifier = Modifier,
    onTranscript: (() -> Unit)? = null,
    backgroundBleed: Float = 0f,
    onOpen: () -> Unit,
) {
    val radius = PleadRadius.card
    DynamicTypeCap(DynamicTypeSize.xxLarge) {
        Column(
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
            modifier = modifier
                .fillMaxWidth()
                .drawBehind {
                    val r = radius.toPx()
                    val path = Path().apply {
                        addRoundRect(RoundRect(Rect(0f, 0f, size.width, size.height + backgroundBleed.dp.toPx()), topLeft = CornerRadius(r), topRight = CornerRadius(r)))
                    }
                    drawIntoCanvas { canvas ->
                        val paint = Paint()
                        val fp = paint.asFrameworkPaint()
                        fp.color = PleadColor.parchment.toArgb()
                        fp.setShadowLayer(10.dp.toPx() * 0.866f, 0f, (-2).dp.toPx(), Color.Black.copy(alpha = 0.3f).toArgb())
                        canvas.drawPath(path, paint)
                    }
                    drawPath(path, PleadColor.gold.copy(alpha = 0.6f), style = Stroke(1.5.dp.toPx()))
                }
                .padding(horizontal = PleadSpacing.l)
                .padding(top = 12.dp, bottom = PleadSpacing.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
                Box(
                    Modifier.size(36.dp).background(PleadColor.paperWhite, RoundedCornerShape(10.dp)).accessibilityHidden(),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(SFSymbol.icon("hands.and.sparkles.fill"), contentDescription = null, tint = PleadColor.burgundy, modifier = Modifier.size(18.dp))
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                    modifier = Modifier.weight(1f).semantics(mergeDescendants = true) { },
                ) {
                    Text(
                        CourtroomLogic.settlementRoundLine(round).uppercase(),
                        style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp), color = PleadColor.gold,
                    )
                    ScaledText(
                        CourtroomLogic.settlementPendingTitle, style = CourtFont.instruction, color = PleadColor.cocoa,
                        minimumScaleFactor = 0.8f, maxLines = 2,
                    )
                }
                if (onTranscript != null) {
                    DockIconButton("text.bubble", "Transcript", null, tint = PleadColor.walnut, tile = PleadColor.paperWhite, onClick = onTranscript)
                }
            }
            Text(subtitle, style = CourtFont.footnote, color = PleadColor.walnut, maxLines = 2, overflow = TextOverflow.Ellipsis)
            CourtButton(
                onClick = onOpen,
                style = CourtButtonStyle(fullWidth = true),
                onClickLabel = if (awaitingMe) "Read the offer and respond" else "See your offer, or withdraw it",
            ) {
                // Title first, icon after ("Open settlement →").
                Text(CourtroomLogic.settlementButtonTitle(SettlementDockMode.pending(round = round, awaitingMe = awaitingMe)))
                Spacer(Modifier.width(6.dp))
                Icon(SFSymbol.icon("arrow.right"), contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}
