// Port of ArgueWin/Courtroom/CourtroomScene.swift: the courtroom, one full-screen static pixel-art scene (not a chat
// thread), with native UI layered over the painting at positions taken from `CourtroomZones`:
//   Banner (top)    the judge's bubble, in the burgundy banner between the status bar and the chair
//   Bench           judge sprite + nameplate (on the bench's top panel)
//   Stands          painted crowd (upper left / right)
//   Easel (middle)  the exhibit under discussion
//   Podiums         plaintiff lower-left, defendant lower-right (pixel avatars); party bubbles between the nameplate
//                   and the speaker (and above the easel card when one is up)
//   Dock            turn controls above the tab bar
// Overlays: deliberation status, safety notice, verdict sequence (full-screen cover).
// Judgement (amendment j), for a revealed case with a `judgement`:
//   pending   the judge's banner line ("The prevailing party is choosing…"); the dock offers the chooser CHOOSE JUDGEMENT
//   delivery  screen C: ALL RISE + the large delivery bubble in the banner, the judgement card in the easel's centre
//             column (never over the podium tags), accept / serve actions in the dock
// Everything is derived from `CourtroomState`; side effects go through `CourtroomActions`.
// Motion (amendment x): one `CourtMotionDirector` per scene drives the characters, the audience clusters and the gavel
// from a single clock; bubbles, exhibits and stamps play one-shot entrances.
// Entrance (amendment ac): `CourtLiveEntrance` plays the shared court entrance the first time a case opens in court;
// later opens restore the occupied court. Dialogue and the dock are live and tappable throughout; a tap on the stage
// finishes it.
// Case call (amendment ad): after the entrance (or on a restored court not yet called) the NOW HEARING card sits over
// the stage, then the judge's introduction (`CourtCaseCall.judgeLine`) takes the banner, then the AI judge's opening
// and the first turn. Until then the record's bubbles are held (masked, still read by TalkBack), the easel stays empty
// and the dock shows the judge has the floor. A tap on the stage advances it.
//
// Android layout: the scene is laid out in the FULL size it is given (the Court tab host extends it under the status
// bar and the tab bar) and told the `CourtInsets`, as iOS reads `GeometryReader` + `safeAreaInsets`. The keyboard
// lifts only the dock (iOS `.ignoresSafeArea(.keyboard)` on the stage).
package app.plead.android.courtroom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.plead.android.BuildConfig
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadCopy
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.AICall
import app.plead.android.models.CaseStatus
import app.plead.android.models.Exhibit
import app.plead.android.models.JudgementStatus
import app.plead.android.models.Role
import app.plead.android.models.Speaker
import app.plead.android.models.Turn
import app.plead.android.models.Verdict
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Swift `CourtroomScene` statics. */
object CourtroomScene {
    /**
     * The judge's bubble band (tail tip included): from below the status bar / camera down to just above the judge's
     * head, with the bubble body ending above the painted stands and flags.
     */
    fun judgeBand(z: CourtroomZones, insets: CourtInsets): Rect {
        val top = insets.top + 8f
        val crowdTop = z.y(CourtroomZones.standsLeft.top)
        val tip = min(z.judgeFrame.top - 4f, crowdTop + CourtBubbleShape.tailSize - 1f)
        return Rect(0f, top, z.size.width, top + max(tip - top, 72f))
    }

    /** The case nameplate, on the bench's top panel just under the judge (clear of the party bubbles below it). */
    fun nameplateFrame(z: CourtroomZones): Rect {
        val bench = z.rect(CourtroomZones.bench)
        val h = 26f
        val left = bench.center.x - bench.width / 2f - 22f
        val top = z.judgeFrame.bottom + 1f
        return Rect(left, top, left + bench.width + 44f, top + h)
    }
}

/** Swift `.onChange(of:)`: runs [action] when [value] changes (never on first composition). */
@Composable
private fun <T> OnChange(value: T, action: (T) -> Unit) {
    val last = remember { arrayOf<Any?>(value) }
    LaunchedEffect(value) {
        if (last[0] != value) {
            last[0] = value
            action(value)
        }
    }
}

@Composable
fun CourtroomScene(
    state: CourtroomState,
    actions: CourtroomActions,
    modifier: Modifier = Modifier,
    entrance: CourtEntranceMode = CourtEntranceMode.auto,
    insets: CourtInsets = CourtInsets.zero,
) {
    val reduceMotion = accessibilityReduceMotion()
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current
    // `init`: the entrance and the motion director are built once per scene (the host keys the scene by case id).
    val live = remember { CourtLiveEntrance(state = state, reduceMotion = reduceMotion, mode = entrance, scope = scope) }
    val motion = remember {
        // Bubbles appear before the scene's onAppear: hold their reveal from the first frame (amendment ac).
        CourtMotionDirector(scope = scope).apply {
            entranceHold = live.isEntering
            openingStep = live.caseCall.step
            introductionTurnId = live.caseCall.introductionTurnId
        }
    }
    val composerFocused = remember { mutableStateOf(false) }

    var showTranscript by remember { mutableStateOf(false) }
    var detailExhibit by remember { mutableStateOf<Exhibit?>(null) }
    var presentedVerdict by remember { mutableStateOf<Verdict?>(null) }
    var dismissedVerdictId by remember { mutableStateOf<UUID?>(null) }
    /** Measured dock height (so the easel card never hides under a tall composer). */
    var dockHeight by remember { mutableStateOf(190f) }
    /** Measured party bubble height (the easel card hangs below it when both are up). */
    var partyBubbleHeight by remember { mutableStateOf(64f) }
    /** Measured podium name-tag height (the middle band ends above the tags). */
    var tagHeight by remember { mutableStateOf(44f) }
    var showJudgementDetail by remember { mutableStateOf(false) }
    /** Height the dock may take above the tab bar without reaching the name tags (`CourtDockBudget`). */
    var dockBudget by remember { mutableStateOf<Float?>(null) }

    val isDeliberating = CourtroomLogic.isDeliberating(state)
    val safetyTurn: Turn? = state.turns.lastOrNull()?.takeIf { it.isSafetyNotice }
    val judgementStage = CourtroomLogic.judgementStage(state)

    fun presentVerdictIfNeeded() {
        val v = state.verdict ?: return
        if (state.kase.status != CaseStatus.verdict || dismissedVerdictId == v.id || presentedVerdict != null) return
        // Once the judgement has been delivered the Court tab opens on the delivery (screen C); the sequence stays one
        // tap away in the dock.
        val j = state.judgement
        if (j != null && j.status != JudgementStatus.pendingSelection) return
        presentedVerdict = v
    }

    fun deliveryHapticIfNeeded() {
        if (judgementStage != JudgementStage.delivery || presentedVerdict != null || safetyTurn != null) return
        JudgementHapticMemory.deliveryAppeared(caseId = state.kase.id, view = view)
    }

    /** CHOOSE JUDGEMENT from the verdict sequence: close the cover first so the app's selection sheet can present. */
    fun chooseFromVerdict(v: Verdict) {
        dismissedVerdictId = v.id
        presentedVerdict = null
        val choose = actions.chooseJudgement
        scope.launch {
            delay(450)
            choose()
        }
    }

    // MARK: Lifecycle (`onAppear` / `onDisappear`, scenePhase)

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(Unit) {
        JudgeSprite.preload(state.judgePersona)
        for (r in listOf(Role.plaintiff, Role.defendant)) CourtAvatarSprite.preload(state.profile(r).avatar)
        motion.entranceHold = live.isEntering
        motion.openingStep = live.caseCall.step
        motion.reduceMotion = reduceMotion
        motion.onRulingHaptic = { CourtHaptics.light(view) }
        motion.update(state)
        motion.setActive(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        motion.appear()
        live.begin()
        presentVerdictIfNeeded()
        deliveryHapticIfNeeded()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> motion.setActive(true)
                Lifecycle.Event.ON_PAUSE -> motion.setActive(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            live.end()
            motion.disappear()
        }
    }
    OnChange(state) { s ->
        motion.update(s)
        live.update(s)
    }
    OnChange(live.director.phase) { motion.entranceHold = live.isEntering }
    OnChange(live.caseCall.step) { step -> motion.openingStep = step }
    // The judge calls the court to order: the motion director's gavel (static under Reduce Motion).
    OnChange(live.director.gavelTaps) { motion.playEntranceGavel() }
    OnChange(reduceMotion) { rm -> motion.reduceMotion = rm }
    // The verdict sequence covers the scene: its own director runs; this clock pauses.
    OnChange(presentedVerdict == null) { uncovered ->
        if (uncovered) motion.appear(analytics = false) else motion.disappear()
        deliveryHapticIfNeeded()
    }
    OnChange(judgementStage) { deliveryHapticIfNeeded() }
    OnChange(state.kase.status) { presentVerdictIfNeeded() }
    OnChange(state.verdict?.id) { presentVerdictIfNeeded() }

    BoxWithConstraints(modifier.fillMaxSize().background(PleadColor.courtBackdrop)) {
        val full = Size(maxWidth.value, maxHeight.value)
        val z = remember(full) { CourtroomZones(full) }
        val dockTop = full.height - insets.bottom - (if (isDeliberating) 0f else dockHeight)
        val budget = CourtDockBudget.available(
            sceneHeight = full.height, bottomInset = insets.bottom,
            tagBottom = CourtDockBudget.tagBottom(z, tagHeight = tagHeight),
        )
        LaunchedEffect(budget) {
            // The keyboard shrinks the reader; the budget is about the resting scene.
            if (!composerFocused.value) dockBudget = budget
        }

        // The stage and its overlays. TalkBack reads the scene top to bottom (bench → judge → easel → party →
        // podiums), then the dock.
        Box(
            Modifier
                .fillMaxSize()
                .semantics { isTraversalGroup = true }
                .accessibilitySortPriority(2f)
                .pointerInput(Unit) {
                    detectTapGestures {
                        composerFocused.value = false
                        live.tap()
                    }
                }
                .pointerInput(Unit) {
                    // Pull-down transcript (`DragGesture(minimumDistance: 30)` simultaneous with the stage).
                    var total = Offset.Zero
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = { total = Offset.Zero; tracker.resetTracking() },
                        onDragEnd = {
                            val dx = total.x / density.density
                            val dy = total.y / density.density
                            val vy = tracker.calculateVelocity().y / density.density
                            if (abs(dy) >= 30f || abs(dx) >= 30f) {
                                if (abs(dy) > abs(dx) && (dy > 90f || dy + vy * 0.25f > 260f)) {
                                    composerFocused.value = false
                                    showTranscript = true
                                }
                            }
                        },
                    ) { change, drag ->
                        total += drag
                        tracker.addPosition(change.uptimeMillis, change.position)
                    }
                },
        ) {
            CourtStage(
                state = state, z = z, insets = insets, dockTop = dockTop, live = live, motion = motion,
                tagHeight = tagHeight, partyBubbleHeight = partyBubbleHeight, isDeliberating = isDeliberating,
                safetyTurn = safetyTurn, judgementStage = judgementStage, reduceMotion = reduceMotion,
                onTagHeight = { h -> if (abs(h - tagHeight) > 0.5f) tagHeight = h },
                onPartyBubbleHeight = { h -> if (abs(h - partyBubbleHeight) > 0.5f) partyBubbleHeight = h },
                onTranscript = { showTranscript = true },
                onExhibit = { detailExhibit = it },
                onJudgementDetail = { showJudgementDetail = true },
            )
            if (safetyTurn != null) {
                SafetyOverlay(safetyTurn, z, insets, Modifier.accessibilitySortPriority(200f))
            } else {
                AnimatedVisibility(
                    visible = isDeliberating,
                    enter = if (reduceMotion) fadeIn(snap()) else fadeIn(PleadMotion.fade()),
                    exit = if (reduceMotion) fadeOut(snap()) else fadeOut(PleadMotion.fade()),
                    modifier = Modifier.accessibilitySortPriority(85f),
                ) {
                    CourtDeliberationOverlay(state = state, zones = z, insets = insets, onTranscript = { showTranscript = true })
                }
            }
        }

        if (!isDeliberating) {
            // The keyboard lifts the dock only (it covers the tab bar), as iOS keeps the stage still.
            val ime = WindowInsets.ime.getBottom(density) / density.density
            val lift = max(0f, ime - insets.bottom)
            CourtDock(
                state = state,
                actions = actions,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = insets.bottom.dp)
                    .offset(y = (-lift).dp)
                    .onSizeChanged { if (!composerFocused.value) dockHeight = it.height / density.density }
                    .accessibilitySortPriority(1f),
                onHearVerdict = state.verdict?.let { v -> { presentedVerdict = v } },
                onTranscript = { showTranscript = true },
                focus = composerFocused,
                heightBudget = dockBudget,
                motion = motion,
                backgroundBleed = insets.bottom,
            )
        }
    }

    if (showTranscript) {
        CourtSheet(onDismiss = { showTranscript = false }, containerColor = PleadColor.courtBackdrop, largeOnly = true) {
            CourtTranscriptView(state = state, onDismiss = { showTranscript = false })
        }
    }
    detailExhibit?.let { ex ->
        CourtSheet(onDismiss = { detailExhibit = null }) {
            CourtExhibitDetail(state = state, exhibit = ex, onDismiss = { detailExhibit = null })
        }
    }
    if (showJudgementDetail) {
        CourtSheet(onDismiss = { showJudgementDetail = false }) {
            JudgementDetailSheet(state = state, onDismiss = { showJudgementDetail = false })
        }
    }
    presentedVerdict?.let { v ->
        val dismiss = {
            dismissedVerdictId = v.id
            presentedVerdict = null
        }
        Dialog(
            onDismissRequest = dismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            val top = WindowInsets.statusBars.getTop(density) / density.density
            val bottom = WindowInsets.navigationBars.getBottom(density) / density.density
            VerdictMomentView(
                state = state, verdict = v, onDismiss = dismiss,
                onChooseJudgement = { chooseFromVerdict(v) },
                insets = CourtInsets(top = top, bottom = bottom),
            )
        }
    }
}

// MARK: Stage

@Composable
private fun CourtStage(
    state: CourtroomState,
    z: CourtroomZones,
    insets: CourtInsets,
    dockTop: Float,
    live: CourtLiveEntrance,
    motion: CourtMotionDirector,
    tagHeight: Float,
    partyBubbleHeight: Float,
    isDeliberating: Boolean,
    safetyTurn: Turn?,
    judgementStage: JudgementStage?,
    reduceMotion: Boolean,
    onTagHeight: (Float) -> Unit,
    onPartyBubbleHeight: (Float) -> Unit,
    onTranscript: () -> Unit,
    onExhibit: (Exhibit) -> Unit,
    onJudgementDetail: () -> Unit,
) {
    val density = LocalDensity.current
    val judgeFrame = z.judgeFrame
    val bench = z.rect(CourtroomZones.bench)
    val nameplate = CourtroomScene.nameplateFrame(z)
    // Amendment ad: the first turn is made obvious (lit podium) only once the case has been called.
    val floor = if (live.holdsRecord) null else CourtroomLogic.activeRole(state)
    val jStage = if (safetyTurn == null) judgementStage else null
    // Amendment n: while a settlement is pending the judge waits (one calm banner line, the parties quiet); a settled
    // case shows the judge's flavour line and the seal on the easel.
    val sMode = if (safetyTurn == null) CourtroomLogic.settlementDockMode(state) else null
    val bubbles: CourtroomLogic.SceneBubbles = when {
        safetyTurn != null || isDeliberating || jStage == JudgementStage.delivery ->
            CourtroomLogic.SceneBubbles(judge = null, party = null, newestIsJudge = true)
        sMode != null -> CourtroomLogic.SceneBubbles(judge = CourtroomLogic.settlementJudgeTurn(state), party = null, newestIsJudge = true)
        // The winner is choosing: the judge's line up in the banner, the parties quiet.
        jStage is JudgementStage.pending -> CourtroomLogic.SceneBubbles(judge = pendingJudgeTurn(state), party = null, newestIsJudge = true)
        else -> CourtroomLogic.sceneBubbles(state.turns)
    }
    val partyTurn = bubbles.party
    val partyRole = partyTurn?.let { CourtroomLogic.role(it.speaker) }
    // Amendment ad: no evidence on the easel until the case has been called.
    val presentation = if (isDeliberating || safetyTurn != null || jStage != null || sMode != null) null
    else CourtCaseCallGate.easel(CourtroomLogic.easelPresentation(state), calling = live.holdsRecord)
    val easelExhibit = state.exhibit(presentation?.exhibitId)
    val band = MiddleBand(
        zones = z, nameplate = nameplate, dockTop = dockTop, tagHeight = tagHeight,
        metrics = MiddleBand.Metrics.of(dynamicTypeSize()),
        party = if (partyTurn != null && partyRole != null) {
            MiddleBand.Party(
                kind = CourtroomLogic.bubbleKind(partyTurn), role = partyRole,
                refersToExhibit = partyTurn.exhibitId != null && state.exhibit(partyTurn.exhibitId) != null,
            )
        } else null,
        hasExhibit = easelExhibit != null, exhibitLive = presentation?.isLive ?: false,
        measuredPartyHeight = partyBubbleHeight,
    )

    val ent = live.director
    Box(Modifier.fillMaxSize()) {
        CourtroomBackground(size = z.size)
        // Audience clusters over the painted stands (static at rest; the painting itself never moves). During the
        // entrance they settle into their seats one after another.
        CourtCrowdLayer(zones = z, motion = motion, settle = ent.audienceSettle)
        if (live.plays) {
            // The room reveal (0–250 ms): a dim over the painting lifts. The painting never moves.
            val dim = androidx.compose.animation.core.animateFloatAsState(
                if (ent.roomRevealed) 0f else 0.55f,
                animationSpec = tween((CourtEntranceTiming.reveal * 1000).toInt(), easing = PleadMotion.easeOut),
                label = "roomReveal",
            )
            Box(Modifier.fillMaxSize().background(CourtColor.dim.copy(alpha = dim.value)).accessibilityHidden())
        }

        // The judge leaves the bench to deliberate (empty chair), and after a safety stop.
        if (!isDeliberating && safetyTurn == null) {
            CourtGavelLayer(zones = z, motion = motion)
            CourtJudgeFigure(
                persona = state.judgePersona, cell = z.judgeCell, motion = motion, walkFrame = ent.judge.walkFrame,
                modifier = Modifier
                    .position(judgeFrame.center.x, judgeFrame.center.y)
                    .courtEntrancePose(ent.judge, reduceMotion = reduceMotion)
                    .accessibilitySortPriority(100f),
            )
        }
        val caseLabelAlpha = androidx.compose.animation.core.animateFloatAsState(
            if (ent.caseLabelVisible) 1f else 0f,
            animationSpec = tween((CourtEntranceTiming.reveal * 1000).toInt(), easing = PleadMotion.easeOut),
            label = "caseLabel",
        )
        Box(Modifier.fillMaxSize().alpha(caseLabelAlpha.value)) {
            PlaqueSeal(z)
            // Between the flags (its text scales down a touch rather than covering them).
            CourtNameplate(
                caseNumber = state.kase.caseNumber, persona = state.judgePersona,
                modifier = Modifier
                    .position(nameplate.center.x, nameplate.center.y)
                    .widthIn(max = max(bench.width, z.art.width * 0.4f).dp)
                    .accessibilitySortPriority(95f),
            )
        }

        for (role in listOf(Role.plaintiff, Role.defendant)) {
            CourtPodiumParty(
                profile = state.profile(role), role = role, isMe = state.myRole == role, hasFloor = floor == role, zones = z,
                modifier = Modifier.accessibilitySortPriority(if (role == Role.plaintiff) 61f else 60f),
                onTagHeight = onTagHeight, motion = motion, entrancePose = ent.pose(role),
            )
        }

        val rect = band.easelRect
        if (easelExhibit != null && rect != null) {
            val ex = easelExhibit
            val stamps = CourtroomLogic.stamps(ex, state.turns)
            val owner = state.profile(ex.ownerId)
            // First time this exhibit goes up (this launch): the card rises onto the easel, the label after.
            val fresh = motion.isFreshExhibit(ex.id)
            val isLive = presentation?.isLive ?: false
            key("${ex.id}-${band.fullCard}") {
                Box(
                    Modifier
                        .frameIn(rect)
                        .accessibilitySortPriority(80f)
                        .courtLanding(
                            pending = fresh, rise = CourtMotionTiming.exhibitRise, duration = CourtMotionTiming.exhibitDuration,
                            bounce = 0.22f, claim = { motion.exhibitRevealed(ex, live = isLive) },
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClickLabel = "Opens the full exhibit",
                        ) { onExhibit(ex) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (band.fullCard) {
                        CourtEasel(
                            exhibit = ex, url = state.exhibitURLs[ex.id], ownerName = owner?.displayName,
                            ownerRole = owner?.let { state.kase.role(it.id) },
                            objected = stamps.objected, objectionReason = stamps.objection, ruling = stamps.ruling,
                            motion = motion, labelLands = fresh, modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        CourtEaselMini(
                            exhibit = ex, url = state.exhibitURLs[ex.id], ownerName = owner?.displayName,
                            objected = stamps.objected, objectionReason = stamps.objection, ruling = stamps.ruling,
                            motion = motion,
                        )
                    }
                }
            }
        }

        if (jStage == JudgementStage.delivery) {
            DeliveryMoment(state, z, insets, band, onJudgementDetail)
        }
        if (sMode == SettlementDockMode.settled) {
            SettledSeal(state, band)
        }

        val older = 1f
        val call = live.caseCall
        if (call.step == CourtOpeningStep.caseCall && safetyTurn == null && !isDeliberating) {
            val jb = CourtroomScene.judgeBand(z, insets)
            CourtCaseCallCard(
                call = call.call, onTap = { live.tap() }, animatesIn = !call.frozen,
                modifier = Modifier
                    .position(z.size.width / 2f, jb.center.y)
                    .zIndex(4f)
                    .padding(horizontal = 24.dp)
                    .accessibilitySortPriority(92f),
            )
        }
        val judgeTurn = bubbles.judge
        if (call.step == CourtOpeningStep.judgeIntroduction && safetyTurn == null && !isDeliberating) {
            IntroductionBubble(state, z, insets, live, motion)
        } else if (judgeTurn != null) {
            // Up in the banner: below the camera, body clear of the stands and flags, tail pointing down at the judge's
            // head. Taps open the transcript for the rest.
            val jb = CourtroomScene.judgeBand(z, insets)
            val w = min(z.size.width - 32f, 400f)
            // At most 3 body lines (2 per numbered question); if the band is shorter than that (large text, short
            // screens) the text gives up lines rather than growing over the judge.
            key(judgeTurn.id) {
                val jm = remember(judgeTurn, state) { CourtBubbleModel(judgeTurn, state) }
                Box(
                    Modifier
                        .frameIn(Rect(z.size.width / 2f - w / 2f, jb.top, z.size.width / 2f + w / 2f, jb.bottom))
                        .alpha(if (bubbles.newestIsJudge) 1f else older)
                        .zIndex(if (bubbles.newestIsJudge) 3f else 2f)
                        .accessibilitySortPriority(90f),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    DynamicTypeCap(DynamicTypeSize.xxLarge) {
                        Box(
                            Modifier
                                .width(w.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = "Opens the transcript",
                                    onClick = onTranscript,
                                ),
                        ) {
                            CourtRevealBubble(model = jm, turns = state.turns, lineLimit = 3, motion = motion) { r ->
                                CourtBubbleView(
                                    model = jm, lineLimit = 3, tail = CourtTail.down, fillOpacity = 0.92f,
                                    questionLineLimit = 2, compressible = true, reveal = r,
                                )
                            }
                        }
                    }
                }
            }
        }
        val slot = band.bubble
        if (partyTurn != null && partyRole != null && slot != null) {
            val role = partyRole
            val tail: CourtTail? = when (val t = slot.tail) {
                MiddleBand.Tail.speakerCorner -> null
                is MiddleBand.Tail.side -> if (role == Role.plaintiff) CourtTail.sideLeading(t.y) else CourtTail.sideTrailing(t.y)
                MiddleBand.Tail.none -> CourtTail.none
            }
            val alignment = when {
                slot.centred -> Alignment.TopCenter
                role == Role.plaintiff -> Alignment.TopStart
                else -> Alignment.TopEnd
            }
            key(partyTurn.id) {
                val pm = remember(partyTurn, state) { CourtBubbleModel(partyTurn, state) }
                Box(
                    Modifier
                        .frameIn(slot.rect)
                        .alpha(if (bubbles.newestIsJudge) older else 1f)
                        .zIndex(if (bubbles.newestIsJudge) 2f else 3f)
                        .accessibilitySortPriority(70f),
                    contentAlignment = alignment,
                ) {
                    DynamicTypeCap(DynamicTypeSize.xLarge) {
                        Box(
                            Modifier
                                .width(slot.rect.width.dp)
                                .wrapContentHeight(Alignment.Top)
                                .onSizeChanged { onPartyBubbleHeight(it.height / density.density) }
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClickLabel = "Opens the transcript",
                                    onClick = onTranscript,
                                ),
                            contentAlignment = when {
                                slot.centred -> Alignment.TopCenter
                                role == Role.plaintiff -> Alignment.TopStart
                                else -> Alignment.TopEnd
                            },
                        ) {
                            CourtRevealBubble(
                                model = pm, turns = state.turns, lineLimit = if (slot.compact) 1 else slot.lines, motion = motion,
                            ) { r ->
                                CourtBubbleView(
                                    model = pm, lineLimit = slot.lines, tail = tail, compressible = true,
                                    compactParty = slot.compact, showsExhibitRow = slot.exhibitRow, reveal = r,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** The painted plaque, now free: a small engraved gold seal so it doesn't read as blank. */
@Composable
internal fun PlaqueSeal(z: CourtroomZones) {
    val plaque = z.rect(CourtroomZones.plaque)
    ScalesGlyph(
        color = PleadColor.mahogany.copy(alpha = 0.55f), size = max(12f, plaque.height * 0.55f).dp,
        modifier = Modifier.position(plaque.center.x, plaque.center.y).accessibilityHidden(),
    )
}

// MARK: Case call (amendment ad)

/**
 * The judge opens the case: `CourtCaseCall.judgeLine` in the judge's banner bubble (not part of the record: never in
 * the transcript). Line reveal + judge talk loop; a tap completes the reveal, the next moves on.
 */
@Composable
private fun IntroductionBubble(state: CourtroomState, z: CourtroomZones, insets: CourtInsets, live: CourtLiveEntrance, motion: CourtMotionDirector) {
    val call = live.caseCall
    val jb = CourtroomScene.judgeBand(z, insets)
    val w = min(z.size.width - 24f, 400f)
    val h = max(jb.height, 72f)
    val turn = remember(call.introductionTurnId, state.kase.id) {
        Turn(
            id = call.introductionTurnId, caseId = state.kase.id, phase = state.kase.phase, speaker = Speaker.judge,
            body = call.call.judgeLine, aiCall = AICall.phaseLine, createdAt = state.now,
        )
    }
    key(turn.id) {
        val m = remember(turn) { CourtBubbleModel(turn, state) }
        Box(
            Modifier
                .frameIn(Rect(z.size.width / 2f - w / 2f, jb.center.y - h / 2f, z.size.width / 2f + w / 2f, jb.center.y + h / 2f))
                .zIndex(3f)
                .testTag("court.caseCall.introduction")
                .accessibilitySortPriority(90f),
            contentAlignment = Alignment.BottomCenter,
        ) {
            DynamicTypeCap(DynamicTypeSize.xxLarge) {
                Box(Modifier.width(w.dp)) {
                    CourtRevealBubble(model = m, turns = state.turns, motion = motion, completeReveal = call.introductionRevealed) { r ->
                        CourtBubbleView(model = m, lineLimit = null, tail = CourtTail.down, fillOpacity = 0.94f, compressible = true, reveal = r)
                    }
                }
            }
        }
    }
}

// MARK: Judgement

/** The judge's banner line while the winner chooses (synthesised; not part of the record). */
private fun pendingJudgeTurn(state: CourtroomState): Turn = Turn(
    id = state.kase.id, caseId = state.kase.id, speaker = Speaker.judge, body = CourtroomLogic.pendingJudgeLine(state),
    aiCall = AICall.phaseLine, createdAt = state.now,
)

/**
 * Screen C: ALL RISE and the delivery in a large judge bubble filling the banner (down to just above the judge's head:
 * it may cover the painted stands and flags), and the judgement card in the easel's centre column between the podiums,
 * ending above the name tags. Tap either for full text.
 */
@Composable
private fun DeliveryMoment(state: CourtroomState, z: CourtroomZones, insets: CourtInsets, band: MiddleBand, onDetail: () -> Unit) {
    val text = CourtroomLogic.deliveryText(state)
    if (text != null) {
        val top = insets.top + 6f
        val tip = z.judgeFrame.top - 2f
        val w = min(z.size.width - 20f, 420f)
        val h = max(tip - top, 90f)
        val cy = (top + tip) / 2f
        Box(
            Modifier
                .frameIn(Rect(z.size.width / 2f - w / 2f, cy - h / 2f, z.size.width / 2f + w / 2f, cy + h / 2f))
                .zIndex(3f)
                .accessibilitySortPriority(90f),
            contentAlignment = Alignment.BottomCenter,
        ) {
            DynamicTypeCap(DynamicTypeSize.xLarge) {
                JudgementDeliveryBubble(
                    text = text, persona = state.judgePersona,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Opens the judgement in full",
                        onClick = onDetail,
                    ),
                )
            }
        }
    }
    val j = state.judgement
    if (j != null && j.selected != null) {
        val cardTop = band.top + 2f
        val floor = band.tagTop - MiddleBand.gap
        val h = min(floor - cardTop, 168f)
        if (h >= 92f) {
            val colL = band.column.start
            val colR = band.column.endInclusive
            Box(
                Modifier
                    .frameIn(Rect(colL, cardTop, colR, cardTop + h))
                    .zIndex(2f)
                    .accessibilitySortPriority(80f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClickLabel = "Opens the judgement in full",
                        onClick = onDetail,
                    ),
            ) {
                DynamicTypeCap(DynamicTypeSize.large) {
                    JudgementDeliveryCard(judgement = j, now = state.now, compact = true, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }
}

/**
 * Settled out of court: a parchment seal on the easel, in the centre column between the podiums, ending above the name
 * tags (never over them).
 */
@Composable
private fun SettledSeal(state: CourtroomState, band: MiddleBand) {
    val top = band.top + 2f
    val floor = band.tagTop - MiddleBand.gap
    val h = min(floor - top, 150f)
    if (h >= 72f) {
        Box(
            Modifier
                .frameIn(Rect(band.column.start, top, band.column.endInclusive, top + h))
                .zIndex(2f)
                .accessibilitySortPriority(80f),
        ) {
            DynamicTypeCap(DynamicTypeSize.large) {
                SettledSealCard(agreement = state.settlementOffer?.body, compact = h < 110f, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

// MARK: Safety overlay

@Composable
private fun SafetyOverlay(turn: Turn, z: CourtroomZones, insets: CourtInsets, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(CourtColor.dim.copy(alpha = 0.72f))) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            CourtSafetyCard(
                body = turn.body,
                modifier = Modifier
                    .padding(horizontal = PleadSpacing.l)
                    .padding(top = (insets.top + z.size.height * 0.18f).dp, bottom = 260.dp),
            )
        }
    }
}

// MARK: - Deliberation overlay

/**
 * THE COURT IS DELIBERATING: the room dims, the judge has left the bench, and four status lines
 * (`PleadCopy.deliberationStatus[1...4]`) light up as `panel_progress` advances. No character animation.
 */
@Composable
fun CourtDeliberationOverlay(
    state: CourtroomState,
    zones: CourtroomZones,
    insets: CourtInsets,
    modifier: Modifier = Modifier,
    onTranscript: (() -> Unit)? = null,
) {
    /** Amendment ae: the verdict help sheet (user-triggered only; the ruling countdown keeps ticking in it). */
    val help = remember { CourtHelpPresentation() }
    val progress = state.panelProgress
    val top = zones.y(CourtroomZones.easel.top) - 40f
    val helpTopic = CourtHelp.dockTopic(state)
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(CourtColor.dim.copy(alpha = 0.45f), CourtColor.dim.copy(alpha = 0.78f))))
                .accessibilityHidden(),
        )
        DynamicTypeCap(DynamicTypeSize.xxLarge) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = max(top - 60f, insets.top + 16f).dp)
                    .padding(horizontal = PleadSpacing.l)
                    .widthIn(max = 380.dp)
                    .fillMaxWidth()
                    .pleadShadow(Color.Black.copy(alpha = 0.4f), radius = 18.dp, y = 8.dp, shape = RoundedCornerShape(PleadRadius.card))
                    .goldFrame()
                    .padding(PleadSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ScalesGlyph(size = 22.dp)
                        // Beside the deliberation status, in the card's top corner (the title stays centred).
                        if (helpTopic != null) {
                            CourtHelpButton(
                                topic = helpTopic, tint = CourtColor.creamSoft,
                                modifier = Modifier.align(Alignment.CenterEnd).offset(x = 14.dp),
                            ) { help.open(helpTopic, offered = CourtHelp.dockTopic(state)) }
                        }
                    }
                    Text(
                        PleadCopy.deliberating,
                        style = CourtFont.legalLarge.copy(letterSpacing = 1.6.sp),
                        color = PleadColor.cream,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        if (progress == 0) PleadCopy.deliberationStatus[0] else "The record is closed. Nothing can be edited.",
                        style = CourtFont.footnote,
                        color = CourtColor.creamSoft,
                        textAlign = TextAlign.Center,
                    )
                }

                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(CourtColor.panelInset, RoundedCornerShape(PleadRadius.tile))
                        .padding(PleadSpacing.l),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    for (i in 1 until PleadCopy.deliberationStatus.size) {
                        StatusLine(
                            PleadCopy.deliberationStatus[i],
                            when {
                                i <= progress -> LineState.done
                                i == progress + 1 -> LineState.current
                                else -> LineState.pending
                            },
                        )
                    }
                }

                ProgressDots(progress)

                // Amendment av: the verdict is revealed the moment the panel has ruled, so the scene promises no date
                // and shows no countdown.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("The ruling will be read shortly", style = CourtFont.headline, color = PleadColor.cream, textAlign = TextAlign.Center)
                    Text("Any moment now", style = CourtFont.timer, color = CourtColor.creamSoft)
                }

                if (onTranscript != null) {
                    CourtButton(onClick = onTranscript, style = CourtButtonStyle(kind = CourtButtonStyle.Kind.secondary)) {
                        Text("Read the transcript")
                    }
                }
            }
        }
    }
    CourtHelpSheetHost(
        presentation = help,
        deadline = state.kase.trialAt?.takeIf { it.isAfter(state.now) },
        deadlineLabel = "until ruling",
    )
    if (BuildConfig.DEBUG) {
        LaunchedEffect(helpTopic) {
            val t = helpTopic ?: return@LaunchedEffect
            if (t != CourtHelp.debugRequested || CourtHelp.debugOpened) return@LaunchedEffect
            delay(800)
            CourtHelp.debugOpened = true
            help.open(t, offered = CourtHelp.dockTopic(state))
        }
    }
}

private enum class LineState { done, current, pending }

@Composable
private fun StatusLine(text: String, s: LineState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "$text. ${when (s) { LineState.done -> "Done"; LineState.current -> "In progress"; LineState.pending -> "Waiting" }}"
        },
    ) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
            when (s) {
                LineState.done -> {
                    Box(Modifier.size(18.dp).background(PleadColor.gold, CircleShape))
                    Icon(SFSymbol.icon("checkmark"), contentDescription = null, tint = PleadColor.mahogany, modifier = Modifier.size(11.dp))
                }
                LineState.current -> {
                    Box(Modifier.size(18.dp).border(2.dp, PleadColor.cream, CircleShape))
                    Box(Modifier.size(6.dp).background(PleadColor.cream, CircleShape))
                }
                LineState.pending -> Box(Modifier.size(18.dp).border(1.5.dp, CourtColor.creamMuted.copy(alpha = 0.5f), CircleShape))
            }
        }
        Text(
            text,
            style = if (s == LineState.pending) CourtFont.callout else CourtFont.callout.copy(fontWeight = FontWeight.Medium),
            color = if (s == LineState.pending) CourtColor.creamMuted.copy(alpha = 0.75f) else PleadColor.cream,
        )
    }
}

@Composable
private fun ProgressDots(progress: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clearAndSetSemantics { contentDescription = "Deliberation progress: $progress of 4" },
    ) {
        for (i in 1..4) {
            Box(
                Modifier
                    .width(26.dp)
                    .height(5.dp)
                    .background(if (i <= progress) PleadColor.gold else CourtColor.creamMuted.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
            )
        }
    }
}

// MARK: - Settled seal

/** The parchment "SETTLED OUT OF COURT" seal on the easel (amendment n): no winner, no crown. */
@Composable
fun SettledSealCard(agreement: String?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val shape = RoundedCornerShape(PleadRadius.tile)
    val label = listOfNotNull("Settled out of court", agreement?.let { "Agreement: $it" }).joinToString(". ")
    Column(
        modifier
            .pleadShadow(Color.Black.copy(alpha = 0.35f), radius = 8.dp, y = 4.dp, shape = shape)
            .background(PleadColor.parchment, shape)
            .border(1.5.dp, PleadColor.gold.copy(alpha = 0.7f), shape)
            .padding(if (compact) 8.dp else 12.dp)
            .clearAndSetSemantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScaledText(
            CourtroomLogic.settledSealTitle,
            style = (if (compact) CourtFont.sealSmall else CourtFont.seal).copy(letterSpacing = 1.6.sp),
            color = PleadColor.walnut,
            minimumScaleFactor = 0.8f,
            maxLines = 2,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .rotate(-4f)
                .border(2.dp, PleadColor.gold, RoundedCornerShape(5.dp))
                .padding(3.dp)
                .border(0.75.dp, PleadColor.gold.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                .padding(horizontal = 7.dp, vertical = 2.dp),
        )
        if (!agreement.isNullOrEmpty()) {
            ScaledText(
                agreement,
                style = CourtFont.footnote,
                color = PleadColor.cocoa,
                minimumScaleFactor = 0.9f,
                maxLines = if (compact) 1 else 3,
                textAlign = TextAlign.Center,
            )
        }
        if (!compact) {
            Text("No winner. No loser.", style = CourtFont.caption2, color = PleadColor.walnut.copy(alpha = 0.8f))
        }
    }
}
