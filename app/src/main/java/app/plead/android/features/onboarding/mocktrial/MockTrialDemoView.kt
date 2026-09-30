// Port of ArgueWin/Features/Onboarding/MockTrial/MockTrialDemoView.swift (CONTRACTS-v2 amendments y, ab–ae, aj): the
// demo view (the intro, then `MockTrialStage` driven by `MockTrialPlayer` and a private ambient `CourtMotionDirector`),
// its controls, the amendment ae help sheet and `MockTrialInvitation`. The `AWMockTrialBeat` capture flag (debug builds)
// works as on iOS.
package app.plead.android.features.onboarding

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.plead.android.BuildConfig
import app.plead.android.app.DemoHarness
import app.plead.android.courtroom.CourtHelpPresentation
import app.plead.android.courtroom.CourtHelpSheetHost
import app.plead.android.courtroom.CourtHelpTopic
import app.plead.android.courtroom.CourtMotionDirector
import app.plead.android.courtroom.CourtRevealMemory
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.ScalesMark
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * The Last Slice, a compressed full case played in the courtroom world. `onContinue` fires from I'M READY FOR COURT
 * on CASE CLOSED; `onSkip` from SKIP DEMO; `onAdvance` on every beat change (for analytics/persistence).
 *
 * Amendment aj: the step opens on the intro (SEE HOW A PLEAD TRIAL WORKS, the case card) and nothing plays until START
 * MOCK TRIAL. The shared court entrance (amendment ac) then opens the court, and the fourteen beats play: session +
 * claim, both openings and exhibits, cross-examination, closings, deliberation, verdict, judgement and CASE CLOSED
 * (≈ 55 s of autoplay; a tap completes a beat, the next tap moves on).
 *
 * Layout: the intro, then the courtroom card, fill the top (under the container's progress bar); the controls sit at
 * the bottom above the navigation bar. The button slot holds START MOCK TRIAL on the intro, the beat progress during
 * playback and I'M READY FOR COURT on CASE CLOSED, so SKIP DEMO never moves and is always there.
 */
@Composable
fun MockTrialDemoView(
    onContinue: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    onAdvance: (MockTrialBeat) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val player = remember { MockTrialPlayer(scope = scope) }
    // Ambient idles only (blinks, 1 px settles, audience bob). A private director with its own reveal memory; nothing
    // here claims reveals, so it sends no courtroom analytics. Starts with the entrance.
    val ambient = remember { CourtMotionDirector(memory = CourtRevealMemory(), scope = scope) }
    var ambientStarted by remember { mutableStateOf(false) }
    // Amendment ae: the OPENING STATEMENT help sheet (only ever opened by the user; pauses playback).
    val help = remember { CourtHelpPresentation() }
    val reduceMotion = accessibilityReduceMotion()
    val talkBack = rememberTalkBackEnabled()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current

    fun announce(text: String) {
        @Suppress("DEPRECATION")
        view.announceForAccessibility(text)
    }

    fun startAmbient() {
        if (ambientStarted) return
        ambientStarted = true
        ambient.appear(analytics = false)
    }

    val advanceFlow by rememberUpdatedState(onAdvance)
    player.onBeatChange = { beat ->
        advanceFlow(beat)
        if (talkBack) announce(MockTrialScript.accessibilityText(beat))
    }
    player.onSkip = onSkip
    player.onContinue = onContinue

    DisposableEffect(Unit) {
        val active = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        player.reduceMotion = reduceMotion
        // Under TalkBack each beat waits for the user (autoplay is never mandatory).
        player.autoplay = !talkBack
        player.setActive(active)
        ambient.reduceMotion = reduceMotion
        ambient.setActive(active)
        var harness = false
        if (BuildConfig.DEBUG) {
            // Captures (`AWMockTrialBeat`, see DemoHarness): a beat name lands on that beat, settled, autoplay off;
            // `caseCall` = the opening beat (the session line + claim card); `start` presses START 2.5 s after launch
            // and autoplays to CASE CLOSED; `entrance` presses START 2.5 s after launch with autoplay off (the
            // entrance plays, then the opening holds); `invitation` is the default.
            when (val raw = DemoHarness.mockTrialBeat) {
                null, "invitation" -> Unit
                "caseCall" -> player.debugHold(MockTrialBeat.opening)
                "start" -> {
                    harness = true
                    scope.launch {
                        delay(2500)
                        player.start()
                    }
                }
                "entrance" -> {
                    harness = true
                    // After the launch splash has cleared (the load-in would otherwise play underneath it).
                    scope.launch {
                        delay(2500)
                        player.debugStartEntrance()
                    }
                }
                else -> MockTrialBeat.fromFlag(raw)?.let(player::debugHold)
            }
        }
        // The intro waits for START MOCK TRIAL; coming back past it resumes playback.
        if (!harness && player.phase != MockTrialPhase.invitation) {
            player.start()
            startAmbient()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    player.setActive(true); ambient.setActive(true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    player.setActive(false); ambient.setActive(false)
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.stop()
            ambient.disappear()
            ambientStarted = false
        }
    }
    OnChange(reduceMotion) { rm ->
        player.reduceMotion = rm
        ambient.reduceMotion = rm
    }
    OnChange(talkBack) { tb -> player.autoplay = !tb }
    // Opening the sheet pauses where it is; Got it / swipe / Close resumes the same beat.
    LaunchedEffect(help) {
        snapshotFlow { help.topic }.drop(1).collect { topic ->
            if (topic != null) player.pauseForHelp() else player.resumeFromHelp()
        }
    }
    // The director's gavel tap at the end of the entrance plays on the pixel gavel.
    LaunchedEffect(player) {
        var old = 0
        snapshotFlow { player.entrance?.gavelTaps ?: 0 }.collect { new ->
            if (new > old) player.entranceGavel()
            old = new
        }
    }
    LaunchedEffect(player) {
        snapshotFlow { player.phase }.drop(1).collect { phase ->
            if (phase != MockTrialPhase.invitation) startAmbient()
            // TalkBack: the intro (and its focused button) is gone; read the judge's session line and the claim.
            if (talkBack && phase == MockTrialPhase.trial) announce(MockTrialScript.accessibilityText(player.currentBeat))
        }
    }

    Column(
        modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { player.advance() }
            .testTag("onboarding.mockTrial"),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (player.phase != MockTrialPhase.trial) {
                MockTrialInvitation(leaving = player.courtShown)
            }
            // The courtroom card: absent on the intro, fades in on START under the entrance's room dim (the director
            // then reveals the room and walks everyone in); a plain fade under Reduce Motion.
            val shown = player.courtShown
            val alpha by animateFloatAsState(
                if (shown) 1f else 0f,
                tween((if (reduceMotion) MockTrialTiming.sceneSettle else 0.25).millis(), easing = PleadMotion.easeOut),
                label = "stage",
            )
            MockTrialStage(
                player = player,
                ambient = ambient,
                onAccessibilityAdvance = { player.advance() },
                onHelp = { help.topic = CourtHelpTopic.mockOpeningStatement },
                modifier = Modifier
                    .padding(horizontal = PleadSpacing.l)
                    .padding(top = PleadSpacing.xs)
                    .graphicsLayer {
                        this.alpha = alpha
                        val s = if (shown || reduceMotion) 1f else 1.015f
                        scaleX = s
                        scaleY = s
                    }
                    .then(if (shown) Modifier else Modifier.clearAndSetSemantics { }),
            )
        }
        MockTrialControls(player, reduceMotion)
    }
    CourtHelpSheetHost(help)
}

/** Swift `.onChange(of:)`: runs [action] when [value] changes after the first composition (never for the initial value). */
@Composable
private fun <T> OnChange(value: T, action: (T) -> Unit) {
    var last by remember { mutableStateOf(value) }
    LaunchedEffect(value) {
        if (value != last) {
            last = value
            action(value)
        }
    }
}

/** Swift `@Environment(\.accessibilityVoiceOverEnabled)`: TalkBack (touch exploration) is on, updated live. */
@Composable
private fun rememberTalkBackEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

@Composable
private fun MockTrialControls(player: MockTrialPlayer, reduceMotion: Boolean) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = PleadSpacing.xl)
                .padding(top = PleadSpacing.m, bottom = PleadSpacing.xs)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                val progressAlpha by animateFloatAsState(if (player.isClosed || !player.courtShown) 0f else 1f, tween(200), label = "beatProgress")
                BeatProgress(player.currentBeat, reduceMotion, Modifier.graphicsLayer { alpha = progressAlpha }.heightIn(min = 44.dp))
                androidx.compose.animation.AnimatedVisibility(player.isInvitation, enter = fadeIn(), exit = fadeOut(tween(MockTrialTiming.invitationFade.millis()))) {
                    OnboardingButtonStyle(
                        kind = OnboardingButtonStyle.Kind.primary,
                        enabled = true,
                        onClick = { player.start() },
                        modifier = Modifier.semantics { contentDescription = "Start mock trial" }.testTag("onboarding.mockTrial.start"),
                    ) { Text("Start mock trial".uppercase()) }
                }
                androidx.compose.animation.AnimatedVisibility(player.isClosed, enter = fadeIn(tween(if (reduceMotion) 150 else 340, delayMillis = if (reduceMotion) 0 else 250)), exit = fadeOut()) {
                    OnboardingButtonStyle(
                        kind = OnboardingButtonStyle.Kind.primary,
                        enabled = true,
                        onClick = { player.finish() },
                        modifier = Modifier.semantics { contentDescription = "I'm ready for court" }.testTag("onboarding.mockTrial.continue"),
                    ) { Text("I'm ready for court".uppercase()) }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 44.dp)
                    .clickable { player.skip() }
                    .semantics { contentDescription = "Skip demo" }
                    .testTag("onboarding.mockTrial.skip"),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Skip demo".uppercase(),
                    style = TextStyle(fontSize = TextStyleKind.footnote.defaultSize.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp),
                    color = OnboardingPalette.burgundy.copy(alpha = 0.85f),
                    modifier = Modifier.clearAndSetSemantics { },
                )
            }
        }
    }
}

/** One small mark per beat (decorative; the stage carries the words). */
@Composable
private fun BeatProgress(current: MockTrialBeat, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (b in MockTrialBeat.entries) {
            val w by animateFloatAsState(if (b == current) 16f else 6f, if (reduceMotion) tween(0) else tween(200), label = "beatMark")
            Box(
                Modifier.width(w.dp).height(6.dp).clip(RoundedCornerShape(50))
                    .background(if (b.rawValue <= current.rawValue) OnboardingPalette.burgundy else OnboardingPalette.burgundy.copy(alpha = 0.18f)),
            )
        }
    }
}

// MARK: - Invitation

/**
 * The intro (amendment aj §1): cream page, SEE HOW A PLEAD TRIAL WORKS eyebrow in court gold (the page's
 * heading), one line of body, and the case card ("Case: The Last Slice · Sam v. Alex"). No courtroom yet, no
 * dialogue, no pricing. The CTA and SKIP DEMO live in the demo view's controls. On START it fades (150 ms).
 */
object MockTrialInvitation {
    val eyebrow = MockTrialScript.introEyebrow
    val body = MockTrialScript.introBody
    val caseCard = MockTrialScript.introCard
}

@Composable
fun MockTrialInvitation(modifier: Modifier = Modifier, leaving: Boolean = false) {
    val alpha by animateFloatAsState(if (leaving) 0f else 1f, tween(MockTrialTiming.invitationFade.millis()), label = "invitation")
    BoxWithConstraints(modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }) {
        val minHeight = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(min = minHeight)
                    .padding(horizontal = PleadSpacing.xl, vertical = PleadSpacing.l),
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.m), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        MockTrialInvitation.eyebrow,
                        style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
                        color = OnboardingPalette.gold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .clearAndSetSemantics {
                                contentDescription = "See how a Plead trial works"
                                heading()
                            }
                            .testTag("onboarding.mockTrial.title"),
                    )
                    Text(MockTrialInvitation.body, style = PleadType.displayM, color = OnboardingPalette.wine, textAlign = TextAlign.Center)
                }
                InvitationCard()
            }
        }
    }
}

@Composable
private fun InvitationCard() {
    val shape = RoundedCornerShape(OnboardingRadius.card)
    Box(
        Modifier
            .fillMaxWidth()
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.08f), radius = 10.dp, y = 4.dp, shape = shape)
            .background(OnboardingPalette.paper, shape)
            .border(1.dp, OnboardingPalette.border, shape)
            .padding(4.dp)
            .border(1.dp, OnboardingPalette.gold.copy(alpha = 0.45f), RoundedCornerShape(OnboardingRadius.card - 4.dp))
            .clearAndSetSemantics { contentDescription = MockTrialInvitation.caseCard }
            .testTag("onboarding.mockTrial.upNext"),
    ) {
        Box(Modifier.align(Alignment.CenterStart).padding(start = 4.dp).width(4.dp).height(48.dp).background(OnboardingPalette.burgundy))
        Column(
            Modifier.fillMaxWidth().padding(PleadSpacing.l - 4.dp),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ScalesMark(size = 20.dp, color = OnboardingPalette.burgundy)
            // Same words; the parties stay together on one line when the card wraps.
            Text(
                MockTrialInvitation.caseCard.replace("Sam v. Alex", "Sam v. Alex"),
                style = TextStyle(fontSize = TextStyleKind.title3.defaultSize.sp, fontWeight = FontWeight.Bold),
                color = OnboardingPalette.cocoa,
                textAlign = TextAlign.Center,
            )
        }
    }
}
