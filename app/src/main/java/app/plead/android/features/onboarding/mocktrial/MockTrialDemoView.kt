// Port of ArgueWin/Features/Onboarding/MockTrial/MockTrialDemoView.swift (CONTRACTS-v2 amendments y, ab–ae, aj):
// the demo view, its controls and `MockTrialInvitation` are 1:1. The stage is interim: `MockTrialStage`
// (MockTrialScene.swift: the painted courtroom card with sprites, speech bubbles, easel exhibits, deliberation and
// judgement overlays, driven by the shared court entrance) is built on wave 3a's courtroom engine and is owed after
// the 3a merge. Until then `MockTrialTranscriptStage` plays the same script, beats and timings over the painted room
// with the judge at the bench, as cards (see MockTrialPlayer.kt).
package app.plead.android.features.onboarding

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.ScalesMark
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.JudgePersona
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Last Slice, a compressed full case played in the courtroom world. `onContinue` fires from I'M READY FOR
 * COURT on CASE CLOSED; `onSkip` from SKIP DEMO; `onAdvance` on every beat change (for analytics/persistence).
 *
 * Amendment aj: the step opens on the intro (SEE HOW A PLEAD TRIAL WORKS, the case card) and nothing plays until
 * START MOCK TRIAL. The fourteen beats then play: session + claim, both openings and exhibits, cross-examination,
 * closings, deliberation, verdict, judgement and CASE CLOSED (≈ 55 s of autoplay; a tap completes a beat, the next
 * tap moves on). The button slot holds START MOCK TRIAL on the intro, the beat progress during playback and I'M READY
 * FOR COURT on CASE CLOSED, so SKIP DEMO never moves and is always there.
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
    val reduceMotion = accessibilityReduceMotion()
    val context = LocalContext.current
    val talkBack = remember(context) {
        (context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager)?.isTouchExplorationEnabled == true
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    player.onBeatChange = onAdvance
    player.onSkip = onSkip
    player.onContinue = onContinue

    LaunchedEffect(reduceMotion) { player.reduceMotion = reduceMotion }
    LaunchedEffect(talkBack) { player.autoplay = !talkBack }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> player.setActive(true)
                Lifecycle.Event.ON_PAUSE -> player.setActive(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            player.stop()
        }
    }
    LaunchedEffect(Unit) {
        // Captures (`AWMockTrialBeat`, see DemoHarness): a beat name lands on that beat, settled, autoplay off;
        // `caseCall` = the opening beat; `start` / `entrance` press START 2.5 s after launch; `invitation` is the default.
        when (val raw = DemoHarness.mockTrialBeat) {
            null, "invitation" -> Unit
            "caseCall" -> player.debugHold(MockTrialBeat.opening)
            "start", "entrance" -> {
                scope.launch {
                    delay(2500)
                    if (raw == "entrance") player.autoplay = false
                    player.start()
                }
                return@LaunchedEffect
            }
            else -> MockTrialBeat.fromFlag(raw)?.let(player::debugHold)
        }
        // The intro waits for START MOCK TRIAL; coming back past it resumes playback.
        if (player.phase != MockTrialPhase.invitation) player.start()
    }

    Column(
        modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { player.advance() }
            .testTag("onboarding.mockTrial"),
    ) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (player.phase == MockTrialPhase.invitation || player.phase == MockTrialPhase.entrance) {
                MockTrialInvitation(leaving = player.courtShown)
            }
            val shown = player.courtShown
            val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (reduceMotion) MockTrialTiming.sceneSettle.millis() else 250), label = "stage")
            if (shown) {
                MockTrialTranscriptStage(
                    player,
                    Modifier
                        .graphicsLayer {
                            this.alpha = alpha
                            val s = if (reduceMotion) 1f else 1.015f + (1f - 1.015f) * alpha
                            scaleX = s
                            scaleY = s
                        }
                        .padding(horizontal = PleadSpacing.l)
                        .padding(top = PleadSpacing.xs),
                )
            }
        }
        MockTrialControls(player, reduceMotion)
    }
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

// MARK: - Interim stage

/** The painted room with Judge Wigsworth at the bench, then the current beat's label, tooltip and parts as cards. */
@Composable
private fun MockTrialTranscriptStage(player: MockTrialPlayer, modifier: Modifier = Modifier) {
    val step = player.step
    val shape = RoundedCornerShape(OnboardingKitTokens.Radius.scene)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        BoxWithConstraints(Modifier.fillMaxWidth().height(210.dp).clip(shape).background(OnboardingPalette.wine)) {
            val size = Size(maxWidth.value, maxHeight.value)
            val zones = CourtroomZones(size)
            CourtroomBackground(size)
            JudgeSprite(
                persona = JudgePersona.wigsworth,
                cell = zones.judgeCell,
                modifier = Modifier.offset(zones.judgeFrame.left.dp, zones.judgeFrame.top.dp),
            )
            Text(
                MockTrialScript.caseChip,
                style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
                color = OnboardingPalette.cream,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    .background(OnboardingPalette.wine.copy(alpha = 0.82f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
        ) {
            if (player.isClosed) {
                ClosedCard()
                return@Column
            }
            step.label?.let {
                Text(it, style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp), color = OnboardingPalette.burgundy,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = MockTrialScript.sentence(it) })
            }
            step.tooltip?.let { Text(it, style = PleadType.metadata, color = OnboardingPalette.secondaryText) }
            if (player.currentBeat == MockTrialBeat.deliberation && player.visibleParts.isNotEmpty()) {
                Text(MockTrialScript.deliberationTitle, style = PleadType.labelCaps, color = OnboardingPalette.wine)
            }
            for (part in player.visibleParts) PartCard(part, player)
        }
    }
}

@Composable
private fun PartCard(part: MockTrialPart, player: MockTrialPlayer) {
    val cardShape = RoundedCornerShape(OnboardingRadius.input)
    when (part) {
        is MockTrialPart.say -> {
            val line = part.value
            val judge = line.speaker == MockTrialSpeaker.judge || line.speaker == MockTrialSpeaker.court
            Column(
                Modifier.fillMaxWidth()
                    .background(if (judge) OnboardingPalette.wine else OnboardingPalette.paper, cardShape)
                    .border(1.dp, if (judge) Color.Transparent else OnboardingPalette.border, cardShape)
                    .padding(PleadSpacing.m)
                    .clearAndSetSemantics { contentDescription = line.accessibilityText },
            ) {
                Text(line.speakerName.uppercase(), style = PleadType.labelCaps, color = if (judge) OnboardingPalette.goldLight else OnboardingPalette.burgundy)
                Text(line.text, style = PleadType.judgeSpeech, color = if (judge) OnboardingPalette.cream else OnboardingPalette.cocoa)
            }
        }
        MockTrialPart.claim -> PlainCard("${MockTrialScript.claimLabel} · ${MockTrialScript.claimText}", "${MockTrialScript.sentence(MockTrialScript.claimLabel)}: ${MockTrialScript.claimText}")
        is MockTrialPart.exhibit -> {
            val e = MockTrialScript.exhibit(part.id)
            Column(
                Modifier.fillMaxWidth().background(OnboardingPalette.parchment, cardShape).padding(PleadSpacing.m)
                    .clearAndSetSemantics { contentDescription = e.accessibilityText },
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs),
            ) {
                Text(e.label, style = PleadType.labelCaps, color = OnboardingPalette.burgundy)
                for (m in e.messages) Text("${m.speakerName}: ${m.text}", style = PleadType.body, color = OnboardingPalette.cocoa)
                e.caption?.let { Text(it, style = PleadType.body, color = OnboardingPalette.cocoa) }
            }
        }
        is MockTrialPart.panelRow -> {
            val r = MockTrialScript.deliberationRow(part.index)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                Text(r.name, style = PleadType.bodyStrong, color = OnboardingPalette.wine)
                Text(r.status + if (r.done) " ✓" else "", style = PleadType.body, color = OnboardingPalette.cocoa, modifier = Modifier.weight(1f))
            }
        }
        MockTrialPart.verdictCard -> Column(
            Modifier.fillMaxWidth().background(OnboardingPalette.wine, cardShape).padding(PleadSpacing.l)
                .clearAndSetSemantics { contentDescription = "${MockTrialScript.sentence(MockTrialScript.verdictTitle)}. ${MockTrialScript.verdictReason}" },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs),
        ) {
            Text(MockTrialScript.verdictRibbon, style = PleadType.labelCaps, color = OnboardingPalette.goldLight)
            Text(MockTrialScript.verdictTitle, style = PleadType.displayM, color = OnboardingPalette.cream)
            Text(MockTrialScript.verdictReason, style = PleadType.body, color = OnboardingPalette.cream.copy(alpha = 0.86f), textAlign = TextAlign.Center)
        }
        MockTrialPart.options -> Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.xs)) {
            val selected = player.shows(MockTrialPart.select)
            MockTrialScript.judgementOptions.forEachIndexed { i, option ->
                val chosen = selected && i == MockTrialScript.judgementChoice
                Text(
                    option,
                    style = PleadType.bodyStrong,
                    color = if (chosen) OnboardingPalette.cream else OnboardingPalette.cocoa,
                    modifier = Modifier.fillMaxWidth()
                        .background(if (chosen) OnboardingPalette.burgundy else OnboardingPalette.paper, cardShape)
                        .border(1.dp, if (chosen) OnboardingPalette.burgundy else OnboardingPalette.border, cardShape)
                        .padding(PleadSpacing.m),
                )
            }
        }
        MockTrialPart.select -> Unit
    }
}

@Composable
private fun PlainCard(text: String, spoken: String) {
    Text(
        text,
        style = PleadType.bodyStrong,
        color = OnboardingPalette.cocoa,
        modifier = Modifier.fillMaxWidth().background(OnboardingPalette.paper, RoundedCornerShape(OnboardingRadius.input))
            .border(1.dp, OnboardingPalette.border, RoundedCornerShape(OnboardingRadius.input)).padding(PleadSpacing.m)
            .clearAndSetSemantics { contentDescription = spoken },
    )
}

@Composable
private fun ClosedCard() {
    Column(
        Modifier.fillMaxWidth().padding(top = PleadSpacing.l).clearAndSetSemantics { contentDescription = MockTrialScript.closedAccessibilityText },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
    ) {
        PleadStamp(MockTrialScript.closedStamp, delay = 0.0)
        Text(MockTrialScript.closedTitle, style = PleadType.displayM, color = OnboardingPalette.wine, textAlign = TextAlign.Center)
        for (l in MockTrialScript.closedLines) Text(l, style = PleadType.body, color = OnboardingPalette.cocoa, textAlign = TextAlign.Center)
    }
}

