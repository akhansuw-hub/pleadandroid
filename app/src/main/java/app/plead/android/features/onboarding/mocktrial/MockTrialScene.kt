// Port of ArgueWin/Features/Onboarding/MockTrial/MockTrialScene.swift — onboarding mock trial: the courtroom world
// (CONTRACTS-v2 amendments y + aj). The live courtroom's painting and sprites, composed for a card inside onboarding:
//   CourtroomBackground + CourtCrowdLayer   the painted room and its four audience clusters (ambient bob
//                                           from a private CourtMotionDirector; verdict hop from the player)
//   JudgeSprite (blink / talk frames)       Judge Wigsworth centred at the bench, gavel over the painted one
//   MockTrialPodium                         Sam (plaintiff, left) and Alex (defendant, right) at the podiums,
//                                           lamp glow + tag glow for whoever holds the floor
//   top band                                the case chip, or the phase plate (label + inline tooltip + the ae
//                                           help button for the opening statements), and the judge's lines
//                                           (tail down at the bench)
//   easel                                   EXHIBIT A (a text-message card) / EXHIBIT B (a photo-style card with a
//                                           pixel pizza box) rising onto the painted easel, EXHIBIT label after
//   lower band                              the CLAIM card, party lines under the podiums (tail up at the
//                                           speaker; closings side by side), the PLAINTIFF WINS card
//   overlays                                deliberation (the live `CourtDeliberationOverlay` look), the judgement
//                                           options (the `JudgementOptionCard` look), CASE CLOSED
// During the entrance (amendment ac) the shared `CourtEntranceDirector` drives the room dim, the case chip and each
// figure's pose; the stage applies them to its own figures here. The live scene's pieces that read a real case
// (`CourtEasel`, `CourtRevealBubble`, `CourtDeliberationOverlay`, `JudgementOptionCard`) need a `CourtroomState` /
// `Exhibit` / `JudgementOption` (and would send courtroom analytics), so the small equivalents here reuse their
// shapes, colours, stamps and timing tokens instead.
//
// Positions are in dp of the card's own coordinate space (iOS points, top-left origin), laid out with the courtroom's
// `Modifier.position` / `frameIn` bridges.
package app.plead.android.features.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.plead.android.courtroom.CourtArtCrops
import app.plead.android.courtroom.CourtAvatarSprite
import app.plead.android.courtroom.CourtBubbleShape
import app.plead.android.courtroom.CourtColor
import app.plead.android.courtroom.CourtCrowdLayer
import app.plead.android.courtroom.CourtEntrancePose
import app.plead.android.courtroom.CourtFigurePose
import app.plead.android.courtroom.CourtGavelLayer
import app.plead.android.courtroom.CourtGavelSprite
import app.plead.android.courtroom.CourtHelpButton
import app.plead.android.courtroom.CourtHelpTopic
import app.plead.android.courtroom.CourtMotionDirector
import app.plead.android.courtroom.CourtMotionTiming
import app.plead.android.courtroom.CourtPodiumParty
import app.plead.android.courtroom.CourtRevealPlan
import app.plead.android.courtroom.CourtRevealText
import app.plead.android.courtroom.CourtRoleChip
import app.plead.android.courtroom.CourtStamp
import app.plead.android.courtroom.CourtTail
import app.plead.android.courtroom.CourtTextReveal
import app.plead.android.courtroom.CourtroomBackground
import app.plead.android.courtroom.CourtroomLogic
import app.plead.android.courtroom.CourtroomZones
import app.plead.android.courtroom.DynamicTypeCap
import app.plead.android.courtroom.DynamicTypeSize
import app.plead.android.courtroom.GavelFrame
import app.plead.android.courtroom.JudgeSprite
import app.plead.android.courtroom.PlaqueSeal
import app.plead.android.courtroom.ScalesGlyph
import app.plead.android.courtroom.ScaledText
import app.plead.android.courtroom.bubbleBackground
import app.plead.android.courtroom.courtEntrancePose
import app.plead.android.courtroom.courtLanding
import app.plead.android.courtroom.frameIn
import app.plead.android.courtroom.goldFrame
import app.plead.android.courtroom.position
import app.plead.android.courtroom.strokeBorder
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import app.plead.android.designsystem.swiftSpring
import app.plead.android.models.Avatar
import app.plead.android.models.JudgePersona
import app.plead.android.models.Role
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** The demo personas (the same pixel avatars as the app's demo couple, `PreviewData.me` / `.partner`). */
object MockTrialPersonas {
    val plaintiff = Avatar(skin = 1, hair = 2, hairstyle = Avatar.Hairstyle.ponytail, top = 0, outfit = Avatar.Outfit.hoodie)
    val defendant = Avatar(skin = 3, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.shirt)
    val judge: JudgePersona = JudgePersona.wigsworth

    fun avatar(r: Role): Avatar = if (r == Role.plaintiff) plaintiff else defendant
    fun name(r: Role): String = if (r == Role.plaintiff) MockTrialScript.plaintiffName else MockTrialScript.defendantName
}

// MARK: - Layout

/** Where things go in a card of `size` (all derived from `CourtroomZones`), in dp. */
class MockTrialLayout(val size: Size) {
    val zones: CourtroomZones = CourtroomZones(size)

    companion object {
        const val chipTop: Float = 10f
        const val chipHeight: Float = 24f
        const val inset: Float = 12f

        /** Half the podium name tag's height (name + role chip), for the band under the tags. */
        const val tagHalfHeight: Float = 27f
    }

    val bubbleWidth: Float get() = min(size.width - inset * 2, 380f)

    /** The top band: the chip / phase plate, then the judge's lines down to just above the judge's head. */
    val topBand: Rect
        get() {
            val top = chipTop
            val bottom = zones.judgeFrame.top - 2
            return rect((size.width - bubbleWidth) / 2, top, bubbleWidth, max(bottom - top, 90f))
        }

    /** Kept for the judge's lines alone (under the chip): the top band less the chip. */
    val judgeBand: Rect
        get() {
            val t = topBand
            val top = chipTop + chipHeight + 6
            return rect(t.left, top, t.width, max(t.bottom - top, 60f))
        }

    /** Party lines, the claim and the verdict card: under the podium name tags, down to the card's bottom edge. */
    val lowerBand: Rect
        get() {
            val top = CourtPodiumParty.tagCenter(zones, Role.plaintiff).y + tagHalfHeight + 8
            val bottom = size.height - inset
            return rect((size.width - bubbleWidth) / 2, top, bubbleWidth, max(bottom - top, 80f))
        }

    /** The exhibit card on the painted easel: between the two avatars, from the bench top down to just above the name tags. */
    val easelRect: Rect
        get() {
            val pa = zones.avatarFrame(Role.plaintiff)
            val da = zones.avatarFrame(Role.defendant)
            val colL = pa.right - pa.width * 0.1f + 6
            val colR = da.left + da.width * 0.1f - 6
            val board = zones.rect(CourtroomZones.easel)
            val w = min(max(colR - colL, 150f), 250f)
            val top = zones.judgeFrame.bottom + 6
            val bottom = CourtPodiumParty.tagCenter(zones, Role.plaintiff).y - tagHalfHeight - 4
            return rect(board.center.x - w / 2, top, w, max(bottom - top, 110f))
        }

    /** The judgement panel: from the easel's top down to the card's bottom edge. */
    val judgementRect: Rect
        get() {
            val top = easelRect.top
            val bottom = size.height - inset
            return rect((size.width - bubbleWidth) / 2, top, bubbleWidth, max(bottom - top, 180f))
        }

    /** Where a party's tail points (the centre of its podium tag), as a fraction of a bubble spanning `frame`. */
    fun tailX(r: Role, frame: Rect): Float {
        val x = CourtPodiumParty.tagCenter(zones, r).x
        return min(max((x - frame.left) / frame.width, 0.1f), 0.9f)
    }

    fun tailX(r: Role): Float = tailX(r, lowerBand)

    private fun rect(x: Float, y: Float, w: Float, h: Float) = Rect(Offset(x, y), Size(w, h))
}

// MARK: - Stage

/** Statics of `MockTrialStage` (slots and the lines each shows). */
object MockTrialStage {
    const val cornerRadius: Float = 24f

    /** In-card text stops growing here; past it the bands scroll rather than clip. */
    val maxType = DynamicTypeSize.accessibility2

    enum class Slot { top, lower }

    /** A line on screen: the current beat's, or (cross-examination) the previous pair's, faded back while its slot has nothing new yet. */
    data class ShownLine(val line: MockTrialLine, val beat: MockTrialBeat, val index: Int, val current: Boolean) {
        val id: String get() = "${beat.rawValue}-$index"
    }

    fun slot(line: MockTrialLine): Slot = if (line.role == null) Slot.top else Slot.lower

    /** What a slot shows for `beat` with `partsShown` parts on screen. */
    fun lines(slot: Slot, beat: MockTrialBeat, partsShown: Int): List<ShownLine> {
        val step = MockTrialScript.step(beat)
        val mine = step.parts.take(partsShown).mapIndexedNotNull { i, p ->
            val l = p.line ?: return@mapIndexedNotNull null
            if (slot(l) != slot) null else ShownLine(l, beat, i, current = true)
        }
        if (mine.isNotEmpty()) return mine
        // Only cross-examination carries the previous pair (faded back) until the next line takes its slot;
        // elsewhere a new beat starts clean (cards, exhibits and plates own the room).
        if (!beat.isCrossExamination) return emptyList()
        val prevBeat = MockTrialBeat.fromRaw(beat.rawValue - 1) ?: return emptyList()
        if (!prevBeat.isCrossExamination) return emptyList()
        val prev = MockTrialScript.step(prevBeat)
        val last = prev.parts.mapIndexedNotNull { i, p ->
            val l = p.line ?: return@mapIndexedNotNull null
            if (slot(l) != slot) null else i to l
        }.lastOrNull() ?: return emptyList()
        return listOf(ShownLine(last.second, prevBeat, last.first, current = false))
    }
}

/** The courtroom card. Reads the player's beat and poses and the ambient director's idles; draws only. */
@Composable
fun MockTrialStage(
    player: MockTrialPlayer,
    ambient: CourtMotionDirector,
    modifier: Modifier = Modifier,
    onAccessibilityAdvance: () -> Unit = {},
    /** The OPENING STATEMENT help button beside the phase label (amendment ae). */
    onHelp: () -> Unit = {},
) {
    val reduceMotion = accessibilityReduceMotion()
    val shape = RoundedCornerShape(MockTrialStage.cornerRadius.dp)
    BoxWithConstraints(
        modifier
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.14f), radius = 12.dp, y = 6.dp, shape = shape)
            .clip(shape)
            .border(1.dp, OnboardingPalette.mahogany.copy(alpha = 0.35f), shape),
    ) {
        val size = Size(maxWidth.value, maxHeight.value)
        val layout = MockTrialLayout(size)
        val z = layout.zones
        val entering = player.phase == MockTrialPhase.entrance
        val beat = player.currentBeat
        Box(Modifier.fillMaxSize()) {
            CourtroomBackground(size)
            // Audience clusters: ambient bob (director) + one micro-reaction on the verdict (player).
            val crowdLift by animateFloatAsState(
                player.crowdLift,
                if (reduceMotion) tween(0) else tween(MockTrialTiming.crowdEase.millis(), easing = PleadMotion.easeOut),
                label = "mockCrowd",
            )
            val settle = player.audienceSettle
            CourtCrowdLayer(
                z, ambient,
                Modifier.graphicsLayer {
                    translationY = (-crowdLift + (if (reduceMotion) 0f else (1f - settle.toFloat()) * 3f)).dp.toPx()
                    alpha = (0.55 + 0.45 * settle).toFloat()
                },
            )
            MockTrialGavelLayer(z, player.gavel)
            MockTrialJudge(player, ambient, z, reduceMotion)
            PlaqueSeal(z)
            for (role in listOf(Role.plaintiff, Role.defendant)) {
                val sp = speaker(role)
                MockTrialPodium(
                    role, z,
                    speaking = player.activeSpeaker == sp,
                    winner = player.plaintiffWon && role == Role.plaintiff,
                    mouthOpen = player.mouthOpen(sp),
                    pose = ambient.pose(role),
                    hop = if (role == Role.plaintiff) player.winnerLift else 0f,
                    entrance = player.entrancePose(sp),
                )
            }
            // The entrance's room reveal: a dim over the painting that lifts (the background never moves).
            val roomDim by animateFloatAsState(
                if (player.roomRevealed) 0f else 0.55f,
                tween((if (entering && !reduceMotion) 0.25 else 0.15).millis(), easing = PleadMotion.easeOut),
                label = "roomDim",
            )
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = roomDim }.background(PleadColor.cocoa).clearAndSetSemantics { })
            // The verdict owns the room: a light dim under the card.
            val verdictDim by animateFloatAsState(
                if (beat == MockTrialBeat.verdict && player.shows(MockTrialPart.verdictCard)) 0.22f else 0f,
                fadeSpec(reduceMotion),
                label = "verdictDim",
            )
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = verdictDim }.background(PleadColor.cocoa).clearAndSetSemantics { })

            TopBand(player, layout, reduceMotion, onAccessibilityAdvance, onHelp)
            Easel(player, layout)
            LowerBand(player, layout, reduceMotion, onAccessibilityAdvance)
            JudgementPanelLayer(player, layout, reduceMotion)
            DeliberationLayer(player, layout)
            ClosedLayer(player, layout, reduceMotion)
        }
    }
}

private fun speaker(r: Role): MockTrialSpeaker = if (r == Role.plaintiff) MockTrialSpeaker.plaintiff else MockTrialSpeaker.defendant

/** The stage's shared fade (Swift `.animation(... value: currentBeat / partsShown)`). */
private fun fadeSpec(reduceMotion: Boolean) =
    tween<Float>((if (reduceMotion) 0.15 else MockTrialTiming.fadeBack).millis(), easing = PleadMotion.easeOut)

@Composable
private fun MockTrialJudge(player: MockTrialPlayer, ambient: CourtMotionDirector, z: CourtroomZones, reduceMotion: Boolean) {
    val frame = z.judgeFrame
    val pose = if (reduceMotion) CourtFigurePose() else ambient.judgePose
    val speaking = player.activeSpeaker == MockTrialSpeaker.judge && !reduceMotion
    val entrance = player.entrancePose(MockTrialSpeaker.judge)
    val walk = if (reduceMotion) 0 else entrance.walkFrame
    val lift by animateFloatAsState(
        pose.lift,
        if (reduceMotion) tween(0) else tween(CourtMotionTiming.bobEase.millis(), easing = PleadMotion.easeInOut),
        label = "mockJudgeLift",
    )
    val scale by animateFloatAsState(
        if (speaking) 1.02f else 1f,
        if (reduceMotion) tween(0) else tween(CourtMotionTiming.characterChange.millis(), easing = PleadMotion.easeOut),
        label = "mockJudgeLean",
    )
    Box(Modifier.fillMaxSize().courtEntrancePose(entrance, reduceMotion)) {
        JudgeSprite(
            persona = MockTrialPersonas.judge,
            cell = z.judgeCell,
            eyesClosed = pose.eyesClosed,
            mouthOpen = player.mouthOpen(MockTrialSpeaker.judge),
            walkFrame = walk,
            modifier = Modifier
                .position(frame.center.x, frame.center.y)
                .graphicsLayer {
                    translationY = (-lift).dp.toPx()
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .clearAndSetSemantics { },
        )
    }
}

// MARK: Scrolling bands

/** A band's ScrollView: `rect` in the card, content at least the band's height (top-aligned), type capped. */
@Composable
private fun BandScroll(
    rect: Rect,
    modifier: Modifier = Modifier,
    anchorBottom: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberScrollState()
    if (anchorBottom) {
        // `.defaultScrollAnchor(.bottom)`: the newest line stays in view.
        LaunchedEffect(state.maxValue) { state.scrollTo(state.maxValue) }
    }
    DynamicTypeCap(MockTrialStage.maxType) {
        Box(modifier.frameIn(rect)) {
            Column(Modifier.fillMaxSize().verticalScroll(state)) {
                Column(Modifier.width(rect.width.dp).heightIn(min = rect.height.dp), content = content)
            }
        }
    }
}

// MARK: Top band

@Composable
private fun TopBand(
    player: MockTrialPlayer,
    layout: MockTrialLayout,
    reduceMotion: Boolean,
    onAccessibilityAdvance: () -> Unit,
    onHelp: () -> Unit,
) {
    val band = layout.topBand
    val shown = if (player.entered) MockTrialStage.lines(MockTrialStage.Slot.top, player.currentBeat, player.partsShown) else emptyList()
    val label = if (player.entered) player.step.label else null
    val overlayBeat = player.currentBeat == MockTrialBeat.deliberation || player.currentBeat == MockTrialBeat.closed
    val plate = if (label != null && !overlayBeat && player.currentBeat != MockTrialBeat.judgement) label else null
    // Above the judgement dim ("So ordered." stays bright), under the deliberation / CASE CLOSED overlays.
    BandScroll(band, Modifier.zIndex(6.5f), anchorBottom = true) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Crossfade(plate, animationSpec = fadeSpec(reduceMotion), label = "phasePlate") { p ->
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (p != null) {
                        MockTrialPhasePlate(label = p, tooltip = player.step.tooltip, help = if (player.helpAvailable) onHelp else null)
                    } else {
                        MockTrialCaseChipOnStage(player.chipVisible, reduceMotion, maxWidth = band.width - 20)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(8.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!overlayBeat) {
                for (s in shown) {
                    key(s.id) {
                        val alpha by animateFloatAsState(
                            if (s.current) 1f else MockTrialTiming.fadedBack.toFloat(), fadeSpec(reduceMotion), label = "lineFade",
                        )
                        MockTrialBubble(
                            line = s.line,
                            isCurrent = s.current,
                            revealComplete = !s.current || player.revealComplete,
                            onAccessibilityAdvance = onAccessibilityAdvance,
                            modifier = Modifier.graphicsLayer { this.alpha = alpha },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MockTrialCaseChipOnStage(visible: Boolean, reduceMotion: Boolean, maxWidth: Float) {
    val alpha by animateFloatAsState(
        if (visible) 1f else 0f,
        if (reduceMotion) tween(150, easing = PleadMotion.easeOut) else swiftSpring(CourtMotionTiming.stamp.toFloat(), 0.2f),
        label = "chipAlpha",
    )
    val scale by animateFloatAsState(
        if (visible || reduceMotion) 1f else 0.9f,
        if (reduceMotion) tween(150, easing = PleadMotion.easeOut) else swiftSpring(CourtMotionTiming.stamp.toFloat(), 0.2f),
        label = "chipScale",
    )
    MockTrialCaseChip(
        Modifier
            .widthIn(max = maxWidth.coerceAtLeast(0f).dp)
            .graphicsLayer {
                this.alpha = alpha.coerceIn(0f, 1f)
                scaleX = scale
                scaleY = scale
            }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics { }),
    )
}

// MARK: Easel

@Composable
private fun Easel(player: MockTrialPlayer, layout: MockTrialLayout) {
    val rect = layout.easelRect
    val id = player.visibleParts.firstNotNullOfOrNull { (it as? MockTrialPart.exhibit)?.id } ?: return
    key(id) {
        DynamicTypeCap(DynamicTypeSize.xLarge) {
            MockTrialExhibitCard(
                exhibit = MockTrialScript.exhibit(id),
                modifier = Modifier
                    .zIndex(2f)
                    .frameIn(rect)
                    .courtLanding(
                        pending = true, rise = MockTrialTiming.exhibitRise,
                        duration = MockTrialTiming.exhibitDuration, bounce = 0.22f,
                    ),
            )
        }
    }
}

// MARK: Lower band

@Composable
private fun LowerBand(player: MockTrialPlayer, layout: MockTrialLayout, reduceMotion: Boolean, onAccessibilityAdvance: () -> Unit) {
    val band = layout.lowerBand
    val beat = player.currentBeat
    if (!player.entered || beat == MockTrialBeat.deliberation || beat == MockTrialBeat.judgement || beat == MockTrialBeat.closed) return
    val shown = MockTrialStage.lines(MockTrialStage.Slot.lower, beat, player.partsShown)
    val rise = with(LocalDensity.current) { 8.dp.roundToPx() }
    BandScroll(band, Modifier.zIndex(4f)) {
        when {
            beat == MockTrialBeat.opening -> {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    androidx.compose.animation.AnimatedVisibility(
                        player.shows(MockTrialPart.claim),
                        enter = if (reduceMotion) fadeIn(fadeSpec(true))
                        else fadeIn(fadeSpec(false)) + scaleIn(fadeSpec(false), initialScale = 0.96f) +
                            slideInVertically(tween(MockTrialTiming.fadeBack.millis(), easing = PleadMotion.easeOut)) { rise },
                        exit = fadeOut(fadeSpec(reduceMotion)),
                    ) {
                        MockTrialClaimCard(Modifier.widthIn(max = min(band.width, 360f).dp))
                    }
                }
            }
            beat == MockTrialBeat.verdict -> {
                if (player.shows(MockTrialPart.verdictCard)) MockTrialVerdictCard()
            }
            shown.size > 1 -> {
                // Closings: both sides at once, each under their own podium.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    for (s in shown) key(s.id) { PartyBubble(player, s, layout, half = true, reduceMotion, onAccessibilityAdvance) }
                }
            }
            shown.isNotEmpty() -> {
                val s = shown.first()
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = if (beat == MockTrialBeat.closings) Alignment.TopStart else Alignment.TopCenter,
                ) {
                    key(s.id) { PartyBubble(player, s, layout, half = beat == MockTrialBeat.closings, reduceMotion, onAccessibilityAdvance) }
                }
            }
        }
    }
}

@Composable
private fun PartyBubble(
    player: MockTrialPlayer,
    s: MockTrialStage.ShownLine,
    layout: MockTrialLayout,
    half: Boolean,
    reduceMotion: Boolean,
    onAccessibilityAdvance: () -> Unit,
) {
    val band = layout.lowerBand
    val role = s.line.role ?: Role.plaintiff
    val w = if (half) (band.width - 8) / 2 else band.width
    val frame = if (half) {
        Rect(Offset(if (role == Role.plaintiff) band.left else band.right - w, band.top), Size(w, band.height))
    } else {
        band
    }
    val alpha by animateFloatAsState(if (s.current) 1f else MockTrialTiming.fadedBack.toFloat(), fadeSpec(reduceMotion), label = "partyFade")
    MockTrialBubble(
        line = s.line,
        tailX = layout.tailX(role, frame),
        isCurrent = s.current,
        revealComplete = !s.current || player.revealComplete,
        onAccessibilityAdvance = onAccessibilityAdvance,
        modifier = Modifier.width(w.dp).graphicsLayer { this.alpha = alpha },
    )
}

// MARK: Judgement

@Composable
private fun JudgementPanelLayer(player: MockTrialPlayer, layout: MockTrialLayout, reduceMotion: Boolean) {
    val visible = player.entered && player.currentBeat == MockTrialBeat.judgement
    val rect = layout.judgementRect
    val rise = with(LocalDensity.current) { 10.dp.roundToPx() }
    AnimatedVisibility(
        visible,
        modifier = Modifier.zIndex(5f).fillMaxSize(),
        enter = fadeIn(fadeSpec(reduceMotion)),
        exit = fadeOut(fadeSpec(reduceMotion)),
    ) {
        Box(Modifier.fillMaxSize().background(PleadColor.cocoa.copy(alpha = 0.3f)).clearAndSetSemantics { })
    }
    AnimatedVisibility(
        visible,
        modifier = Modifier.zIndex(6f).fillMaxSize(),
        enter = if (reduceMotion) fadeIn(fadeSpec(true))
        else fadeIn(fadeSpec(false)) + slideInVertically(tween(MockTrialTiming.fadeBack.millis(), easing = PleadMotion.easeOut)) { rise },
        exit = fadeOut(fadeSpec(reduceMotion)),
    ) {
        Box(Modifier.fillMaxSize()) {
            BandScroll(rect) {
                MockTrialJudgementPanel(selected = player.shows(MockTrialPart.select))
            }
        }
    }
}

// MARK: Deliberation

@Composable
private fun DeliberationLayer(player: MockTrialPlayer, layout: MockTrialLayout) {
    val reduceMotion = accessibilityReduceMotion()
    AnimatedVisibility(
        player.entered && player.currentBeat == MockTrialBeat.deliberation,
        modifier = Modifier.zIndex(7f).fillMaxSize(),
        enter = fadeIn(fadeSpec(reduceMotion)),
        exit = fadeOut(fadeSpec(reduceMotion)),
    ) {
        OverlayScroll(
            layout,
            background = Brush.verticalGradient(listOf(CourtColor.dim.copy(alpha = 0.5f), CourtColor.dim.copy(alpha = 0.82f))),
        ) {
            MockTrialDeliberationPanel(ticked = player.partsShown, modifier = it)
        }
    }
}

// MARK: Case closed

@Composable
private fun ClosedLayer(player: MockTrialPlayer, layout: MockTrialLayout, reduceMotion: Boolean) {
    AnimatedVisibility(
        player.entered && player.currentBeat == MockTrialBeat.closed,
        modifier = Modifier.zIndex(8f).fillMaxSize(),
        enter = if (reduceMotion) fadeIn(fadeSpec(true)) else fadeIn(fadeSpec(false)) + scaleIn(fadeSpec(false), initialScale = 1.02f),
        exit = fadeOut(fadeSpec(reduceMotion)),
    ) {
        OverlayScroll(layout, background = androidx.compose.ui.graphics.SolidColor(PleadColor.cocoa.copy(alpha = 0.62f))) {
            MockTrialClosedCard(modifier = it)
        }
    }
}

/** A full-card overlay: a dim, and a card centred in a scroll view at most 360 wide with 16 on each side. */
@Composable
private fun OverlayScroll(layout: MockTrialLayout, background: Brush, card: @Composable (Modifier) -> Unit) {
    DynamicTypeCap(MockTrialStage.maxType) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(background).clearAndSetSemantics { })
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                Box(
                    Modifier.width(layout.size.width.dp).heightIn(min = layout.size.height.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    card(Modifier.padding(horizontal = 16.dp).widthIn(max = min(layout.size.width - 32, 360f).coerceAtLeast(0f).dp).fillMaxWidth())
                }
            }
        }
    }
}

// MARK: - Phase plate

/**
 * The phase label on a small mahogany plate at the top of the room (the bench nameplate's style), the inline tooltip
 * under it (`PleadType.caption`), and the amendment ae help button beside the label for the opening statements.
 * Replaces the case chip while a phase is labelled.
 */
@Composable
fun MockTrialPhasePlate(label: String, modifier: Modifier = Modifier, tooltip: String? = null, help: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(9.dp)
    // The plate stays a plate at accessibility sizes (the dialogue below it scrolls instead).
    DynamicTypeCap(DynamicTypeSize.xxxLarge) {
        Column(
            modifier
                .pleadShadow(Color.Black.copy(alpha = 0.3f), radius = 4.dp, y = 2.dp, shape = shape)
                .background(PleadColor.mahogany.copy(alpha = 0.95f), shape)
                .drawBehind {
                    strokeBorder(9.dp.toPx(), inset = 2.dp.toPx(), width = 1.dp.toPx(), color = PleadColor.gold.copy(alpha = 0.7f))
                    strokeBorder(9.dp.toPx(), inset = 0f, width = 1.dp.toPx(), color = PleadColor.cocoa)
                }
                .heightIn(min = MockTrialLayout.chipHeight.dp)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                ScalesGlyph(size = 10.dp)
                Text(
                    label,
                    style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
                    color = PleadColor.cream,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .semantics {
                            contentDescription = MockTrialScript.sentence(label)
                            heading()
                        }
                        .testTag("onboarding.mockTrial.phase"),
                )
                if (help != null) {
                    CourtHelpButton(
                        topic = CourtHelpTopic.mockOpeningStatement,
                        tint = OnboardingPalette.goldLight,
                        action = help,
                        // `.padding(.vertical, -12).padding(.trailing, -10)`: the 44 pt target overhangs the plate.
                        modifier = Modifier.layout { m, c ->
                            val p = m.measure(c)
                            val v = 12.dp.roundToPx()
                            val t = 10.dp.roundToPx()
                            layout((p.width - t).coerceAtLeast(0), (p.height - 2 * v).coerceAtLeast(0)) { p.place(0, -v) }
                        },
                    )
                }
            }
            if (tooltip != null) {
                Text(
                    tooltip,
                    style = PleadType.caption,
                    color = CourtColor.creamSoft,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("onboarding.mockTrial.tooltip"),
                )
            }
        }
    }
}

// MARK: - Bubble

/**
 * One line of dialogue in the courtroom's bubble language: the judge's mahogany bubble (Fraunces) pointing down at the
 * bench, or a party's paper bubble (system sans) pointing up at their podium. Name / role are up with the bubble; the
 * words follow 100 ms later, line by line (`CourtRevealText`). Enters with fade + scale 0.96 → 1 + 6 pt rise on a
 * restrained spring (≈ 220 ms). Reduce Motion: a short fade, text complete.
 */
@Composable
fun MockTrialBubble(
    line: MockTrialLine,
    modifier: Modifier = Modifier,
    tailX: Float = 0.5f,
    isCurrent: Boolean = true,
    /** The player completed the beat (a tap, or the help sheet): show every line now. */
    revealComplete: Boolean = false,
    onAccessibilityAdvance: () -> Unit = {},
) {
    val reduceMotion = accessibilityReduceMotion()
    val plan = remember(line.text) { CourtRevealPlan.make(body = line.text, questions = emptyList(), charsPerLine = 30) }
    val enter = remember { Animatable(0f) }
    val elapsed = remember { Animatable(0f) }
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (entered) return@LaunchedEffect
        entered = true
        if (reduceMotion) {
            elapsed.snapTo(1_000f)
            enter.animateTo(1f, tween(150, easing = PleadMotion.easeOut))
        } else {
            launch { enter.animateTo(1f, swiftSpring(MockTrialTiming.bubbleEntrance.toFloat(), 0.12f)) }
            if (revealComplete || !isCurrent) {
                elapsed.snapTo(1_000f)
            } else {
                elapsed.animateTo(plan.total.toFloat(), tween(plan.total.millis(), easing = LinearEasing))
            }
        }
    }
    LaunchedEffect(revealComplete) {
        if (!revealComplete || elapsed.value >= plan.total) return@LaunchedEffect
        elapsed.animateTo(plan.total.toFloat(), tween(120, easing = PleadMotion.easeOut))
        elapsed.snapTo(1_000f)
    }
    val reveal = CourtTextReveal(plan, elapsed.value.toDouble())
    val isJudge = line.role == null
    Box(
        modifier
            .graphicsLayer {
                val e = enter.value
                alpha = e.coerceIn(0f, 1f)
                val still = reduceMotion
                val s = if (still) 1f else MockTrialTiming.bubbleScale + (1f - MockTrialTiming.bubbleScale) * e
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, if (isJudge) 1f else 0f)
                translationY = if (still) 0f else ((1f - e) * MockTrialTiming.bubbleRise).dp.toPx()
            }
            .clearAndSetSemantics {
                if (isCurrent) {
                    contentDescription = line.accessibilityText
                    onClick(label = "Next part of the demo") { onAccessibilityAdvance(); true }
                }
            }
            .testTag(if (isCurrent) "onboarding.mockTrial.bubble" else "onboarding.mockTrial.previous"),
    ) {
        val role = line.role
        if (role == null) JudgeLineBubble(line, reveal) else PartyLineBubble(line, role, tailX, reveal)
    }
}

@Composable
private fun JudgeLineBubble(line: MockTrialLine, reveal: CourtTextReveal) {
    val shape = CourtBubbleShape(CourtTail.down)
    Column(
        Modifier
            .fillMaxWidth()
            .pleadShadow(Color.Black.copy(alpha = 0.3f), radius = 6.dp, y = 3.dp, shape = shape)
            .bubbleBackground(shape, PleadColor.mahogany.copy(alpha = 0.95f), PleadColor.cocoa, 1.5.dp)
            .padding(start = 14.dp, end = 14.dp, top = 9.dp, bottom = (10 + CourtBubbleShape.tailSize).dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            ScalesGlyph(size = 13.dp)
            Text(line.speakerName, style = CourtFont.judgeName, color = CourtColor.creamSoft, maxLines = 1)
        }
        CourtRevealText(line.text, style = CourtFont.judgeSpeech, color = PleadColor.cream, reveal = reveal)
    }
}

@Composable
private fun PartyLineBubble(line: MockTrialLine, role: Role, tailX: Float, reveal: CourtTextReveal) {
    // The courtroom bubble with its tail flipped to the top, pointing up at the speaker's podium.
    val shape = remember(tailX) { FlippedShape(CourtBubbleShape(CourtTail.down, tailX)) }
    Column(
        Modifier
            .fillMaxWidth()
            .pleadShadow(Color.Black.copy(alpha = 0.28f), radius = 6.dp, y = 3.dp, shape = shape)
            .bubbleBackground(shape, PleadColor.paperWhite, PleadColor.cocoa.copy(alpha = 0.85f), 1.5.dp)
            .padding(start = 13.dp, end = 13.dp, top = (9 + CourtBubbleShape.tailSize).dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(line.speakerName, style = CourtFont.partyName, color = PleadColor.cocoa, maxLines = 1, softWrap = false)
            CourtRoleChip(role)
            Spacer(Modifier.weight(1f))
        }
        CourtRevealText(line.text, style = CourtFont.speech, color = PleadColor.cocoa, reveal = reveal)
    }
}

/** `shape.scaleEffect(x: 1, y: -1)`: the same outline mirrored top to bottom. */
private class FlippedShape(private val base: Shape) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val o = base.createOutline(size, layoutDirection, density)
        val path = when (o) {
            is Outline.Generic -> o.path
            is Outline.Rounded -> androidx.compose.ui.graphics.Path().apply { addRoundRect(o.roundRect) }
            is Outline.Rectangle -> androidx.compose.ui.graphics.Path().apply { addRect(o.rect) }
        }
        val flipped = android.graphics.Path(path.asAndroidPath())
        flipped.transform(android.graphics.Matrix().apply { setScale(1f, -1f); postTranslate(0f, size.height) })
        return Outline.Generic(flipped.asComposePath())
    }
}

// MARK: - Claim card

/**
 * "CLAIM · Alex ate the final slice after agreeing to save it." on a court-file card (paper, fine warm border, a
 * burgundy edge accent), the NOW HEARING card's place in this flow. Enters with the case card's motion.
 */
@Composable
fun MockTrialClaimCard(modifier: Modifier = Modifier) {
    val r = PleadRadius.tile
    val shape = RoundedCornerShape(r)
    Box(
        modifier
            .fillMaxWidth()
            .pleadShadow(Color.Black.copy(alpha = 0.35f), radius = 10.dp, y = 5.dp, shape = shape)
            .background(PleadColor.paperWhite, shape)
            .drawBehind {
                strokeBorder(r.toPx(), inset = 3.dp.toPx(), width = 1.dp.toPx(), color = PleadColor.gold.copy(alpha = 0.45f))
                strokeBorder(r.toPx(), inset = 0f, width = 1.5.dp.toPx(), color = PleadColor.cocoa.copy(alpha = 0.8f))
            }
            .clearAndSetSemantics {
                contentDescription = "${MockTrialScript.sentence(MockTrialScript.claimLabel)}: ${MockTrialScript.claimText}"
            }
            .testTag("onboarding.mockTrial.claim"),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    MockTrialScript.claimLabel,
                    style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
                    color = PleadColor.burgundy,
                )
                Box(Modifier.weight(1f).height(1.dp).background(PleadColor.walnut.copy(alpha = 0.3f)))
                Text("#${MockTrialScript.caseNumber}", style = PleadType.labelCaps, color = PleadColor.walnut)
            }
            Text(MockTrialScript.claimText, style = CourtFont.rulingLarge, color = PleadColor.cocoa)
        }
        // The burgundy edge accent (`.overlay(alignment: .leading)`: it never sizes the card).
        Box(Modifier.matchParentSize().padding(start = 7.dp, top = 10.dp, bottom = 10.dp)) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(PleadColor.burgundy))
        }
    }
}

// MARK: - Exhibits

/**
 * An exhibit on the easel, in `CourtEasel`'s card language (paper surface, cocoa rim, serif EXHIBIT label that lands
 * just after the card rises). A: two text-message bubbles. B: a photo-style card (a pixel pizza box on a table, never
 * a real photo) with its caption.
 */
@Composable
fun MockTrialExhibitCard(exhibit: MockTrialExhibit, modifier: Modifier = Modifier) {
    val r = PleadRadius.tile
    val shape = RoundedCornerShape(r)
    Column(
        modifier
            .pleadShadow(Color.Black.copy(alpha = 0.35f), radius = 8.dp, y = 4.dp, shape = shape)
            .background(PleadColor.paperWhite, shape)
            .drawBehind { strokeBorder(r.toPx(), inset = 0f, width = 1.5.dp.toPx(), color = PleadColor.cocoa.copy(alpha = 0.85f)) }
            .clearAndSetSemantics { contentDescription = exhibit.accessibilityText }
            .testTag("onboarding.mockTrial.exhibit")
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            exhibit.label,
            style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
            color = PleadColor.burgundy,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.courtLanding(
                pending = true, fromScale = 1.25f, delay = MockTrialTiming.exhibitLabelDelay, duration = 0.2, bounce = 0.3f,
            ),
        )
        if (exhibit.messages.isEmpty()) ExhibitPhoto(exhibit) else ExhibitMessages(exhibit)
    }
}

object MockTrialExhibitCard {
    val sent = Color(hex = 0x2F7CF6)
    val received = Color(hex = 0xE7E1DC)
}

@Composable
private fun ColumnScope.ExhibitMessages(exhibit: MockTrialExhibit) {
    Column(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .background(PleadColor.parchment.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        for (m in exhibit.messages) {
            val mine = m.speaker == MockTrialSpeaker.plaintiff
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (mine) 18.dp else 0.dp, end = if (mine) 0.dp else 18.dp),
                horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    m.speakerName,
                    style = TextStyle(fontSize = TextStyleKind.caption2.defaultSize.sp, fontWeight = FontWeight.SemiBold),
                    color = PleadColor.walnut,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
                Text(
                    m.text,
                    style = TextStyle(fontSize = TextStyleKind.footnote.defaultSize.sp),
                    color = if (mine) Color.White else PleadColor.cocoa,
                    modifier = Modifier
                        .background(if (mine) MockTrialExhibitCard.sent else MockTrialExhibitCard.received, RoundedCornerShape(14.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.ExhibitPhoto(exhibit: MockTrialExhibit) {
    Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        MockTrialPizzaBox(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Color(hex = 0x5A3A2A)),
        )
        exhibit.caption?.let { Text(it, style = CourtFont.caption, color = PleadColor.cocoa) }
    }
}

/** A pixel pizza box seen from above: lid open, two slices left (drawn on a whole-cell grid, no filtering). */
@Composable
fun MockTrialPizzaBox(modifier: Modifier = Modifier) {
    Canvas(modifier.clearAndSetSemantics { }) {
        val cols = MockTrialPizzaBox.columns.toFloat()
        val rowsN = MockTrialPizzaBox.rows.size.toFloat()
        val wDp = size.width / density
        val hDp = size.height / density
        val cell = max(1f, floor(min((wDp - 12) / cols, (hDp - 8) / rowsN)))
        val ox = ((wDp - cell * cols) / 2).roundToInt()
        val oy = ((hDp - cell * rowsN) / 2).roundToInt()
        MockTrialPizzaBox.rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, ch ->
                val c = MockTrialPizzaBox.palette[ch] ?: return@forEachIndexed
                drawRect(
                    c,
                    topLeft = Offset((ox + x * cell) * density, (oy + y * cell) * density),
                    size = Size(cell * density, cell * density),
                )
            }
        }
    }
}

object MockTrialPizzaBox {
    val rows = listOf(
        "kkkkkkkkkkkkkkkkkkkk",
        "kLLLLLLLLLLLLLLLLLLk",
        "kLLLLLLLLLLLLLLLLLLk",
        "kLLLLLLLLLLLLLLLLLLk",
        "kkkkkkkkkkkkkkkkkkkk",
        "kKKKKKKKKKKKKKKKKKKk",
        "kKGGGGGGGGGGGGGGGGKk",
        "kKGCYGGGGGGGGGGYCGKk",
        "kKGCYPYGGGGGGYPYCGKk",
        "kKGCYYYPYGGYPYYYCGKk",
        "kKGCYPYGGGGGGYYPCGKk",
        "kKGCYGGGGGGGGGGYCGKk",
        "kKGGGGGGGGGGGGGGGGKk",
        "kkkkkkkkkkkkkkkkkkkk",
    )
    val palette: Map<Char, Color> = mapOf(
        'k' to Color(hex = 0x7A5230), 'K' to Color(hex = 0xC89A5E), 'L' to Color(hex = 0xE0BD86),
        'G' to Color(hex = 0xEED9B0), 'C' to Color(hex = 0xC97B3A), 'Y' to Color(hex = 0xF3C654), 'P' to Color(hex = 0xB23A2E),
    )
    const val columns = 20
}

// MARK: - Deliberation

/**
 * THE COURT IS DELIBERATING… over the dimmed room, in `CourtDeliberationOverlay`'s look (gold-framed mahogany card,
 * scales, the status rows on the inset panel with gold ticks). Rows tick in as the player reveals them; Reduce Motion
 * shows them all at once. TalkBack reads the panel as one element.
 */
@Composable
fun MockTrialDeliberationPanel(ticked: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
            .pleadShadow(Color.Black.copy(alpha = 0.4f), radius = 18.dp, y = 8.dp, shape = RoundedCornerShape(PleadRadius.card))
            .goldFrame()
            .clearAndSetSemantics { contentDescription = MockTrialScript.deliberationAccessibilityText }
            .testTag("onboarding.mockTrial.deliberation")
            .padding(PleadSpacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        ScalesGlyph(size = 22.dp)
        Text(
            MockTrialScript.deliberationTitle,
            style = CourtFont.legalLarge.copy(letterSpacing = 1.6.sp),
            color = PleadColor.cream,
            textAlign = TextAlign.Center,
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(CourtColor.panelInset, RoundedCornerShape(PleadRadius.tile))
                .padding(PleadSpacing.l),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (i in MockTrialScript.deliberationRows.indices) DeliberationRow(i, ticked)
        }
    }
}

private enum class RowState { done, current, pending }

@Composable
private fun DeliberationRow(i: Int, ticked: Int) {
    val r = MockTrialScript.deliberationRow(i)
    val s = if (i < ticked) (if (r.done) RowState.done else RowState.current) else RowState.pending
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.width(22.dp).padding(top = 1.dp), contentAlignment = Alignment.Center) {
            when (s) {
                RowState.done -> Box(Modifier.size(18.dp).background(PleadColor.gold, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(SFSymbol.icon("checkmark"), contentDescription = null, tint = PleadColor.mahogany, modifier = Modifier.size(11.dp))
                }
                RowState.current -> Box(Modifier.size(18.dp).border(2.dp, PleadColor.cream, CircleShape), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(6.dp).background(PleadColor.cream, CircleShape))
                }
                RowState.pending -> Box(Modifier.size(18.dp).border(1.5.dp, CourtColor.creamMuted.copy(alpha = 0.5f), CircleShape))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                r.name,
                style = CourtFont.callout.copy(fontWeight = FontWeight.SemiBold),
                color = if (s == RowState.pending) CourtColor.creamMuted.copy(alpha = 0.75f) else PleadColor.cream,
            )
            Text(
                r.status,
                style = CourtFont.footnote,
                color = if (s == RowState.pending) CourtColor.creamMuted.copy(alpha = 0.6f) else CourtColor.creamSoft,
            )
        }
    }
}

// MARK: - Verdict

/**
 * The verdict: a dedicated card under the podiums. Burgundy ribbon ("THE COURT"), PLAINTIFF WINS in Fraunces
 * displayXL, the reason below. Rises 16 pt + fades + scales from 0.94 on one soft spring (≈ 340 ms); the reason reveals
 * just after. Reduce Motion: a fade, text complete.
 */
@Composable
fun MockTrialVerdictCard(modifier: Modifier = Modifier) {
    val reduceMotion = accessibilityReduceMotion()
    val shown = remember { Animatable(0f) }
    val elapsed = remember { Animatable(0f) }
    val revealTotal = MockTrialTiming.judgementDelay + MockTrialTiming.lineStagger * 2 + MockTrialTiming.lineDuration
    LaunchedEffect(Unit) {
        if (reduceMotion) {
            elapsed.snapTo(1_000f)
            shown.animateTo(1f, tween(200, easing = PleadMotion.easeOut))
        } else {
            launch { shown.animateTo(1f, swiftSpring(MockTrialTiming.verdictCard.toFloat(), MockTrialTiming.verdictBounce.toFloat())) }
            elapsed.animateTo(revealTotal.toFloat(), tween(revealTotal.millis(), easing = LinearEasing))
        }
    }
    val plan = CourtRevealPlan(bodyDelay = MockTrialTiming.judgementDelay)
    val r = OnboardingRadius.card
    val shape = RoundedCornerShape(r)
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = shown.value
                alpha = p.coerceIn(0f, 1f)
                val s = if (reduceMotion) 1f else MockTrialTiming.verdictScale + (1f - MockTrialTiming.verdictScale) * p
                scaleX = s
                scaleY = s
                transformOrigin = TransformOrigin(0.5f, 0f)
                translationY = if (reduceMotion) 0f else ((1f - p) * MockTrialTiming.verdictRise).dp.toPx()
            }
            .pleadShadow(Color.Black.copy(alpha = 0.35f), radius = 12.dp, y = 6.dp, shape = shape)
            .clip(shape)
            .background(PleadColor.paperWhite)
            .drawWithBorders(r.value, goldInset = 3f, goldAlpha = 0.6f)
            .clearAndSetSemantics {
                contentDescription = "${MockTrialScript.sentence(MockTrialScript.verdictTitle)}. ${MockTrialScript.verdictReason}"
            }
            .testTag("onboarding.mockTrial.verdict"),
    ) {
        Row(
            Modifier.fillMaxWidth().background(OnboardingPalette.burgundy).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScalesGlyph(size = 12.dp)
            Text(MockTrialScript.verdictRibbon, style = PleadType.labelCapsTracked, color = PleadColor.cream)
            ScalesGlyph(size = 12.dp)
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ScaledText(
                MockTrialScript.verdictTitle,
                style = PleadType.displayXL,
                color = OnboardingPalette.wine,
                minimumScaleFactor = 0.5f,
                maxLines = 1,
            )
            Box(Modifier.width(44.dp).height(2.dp).background(PleadColor.gold, CircleShape))
            CourtRevealText(
                MockTrialScript.verdictReason,
                style = CourtFont.judgeQuestion,
                color = PleadColor.cocoa,
                reveal = CourtTextReveal(plan, elapsed.value.toDouble()),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The card rims drawn over the content: a gold hairline `goldInset` in, then the cocoa 1.5 pt rim. */
private fun Modifier.drawWithBorders(radius: Float, goldInset: Float, goldAlpha: Float): Modifier = drawWithContent {
    drawContent()
    strokeBorder(radius.dp.toPx(), inset = goldInset.dp.toPx(), width = 1.dp.toPx(), color = PleadColor.gold.copy(alpha = goldAlpha))
    strokeBorder(radius.dp.toPx(), inset = 0f, width = 1.5.dp.toPx(), color = PleadColor.cocoa.copy(alpha = 0.8f))
}

// MARK: - Judgement

/**
 * THE WINNER CHOOSES THE JUDGEMENT: the three options as radio cards in `JudgementOptionCard`'s styling (parchment,
 * walnut rim; the selected one paper-white with a burgundy rim and a filled radio). "Replace the pizza" highlights
 * itself after ~1.2 s (the player's `.select` part). Display only: nothing here is tappable (a tap anywhere moves the
 * demo on).
 */
@Composable
fun MockTrialJudgementPanel(selected: Boolean, modifier: Modifier = Modifier) {
    val r = OnboardingRadius.card
    val shape = RoundedCornerShape(r)
    Column(
        modifier
            .fillMaxWidth()
            .pleadShadow(Color.Black.copy(alpha = 0.35f), radius = 12.dp, y = 6.dp, shape = shape)
            .background(PleadColor.cream, shape)
            .drawWithBorders(r.value, goldInset = 3f, goldAlpha = 0.5f)
            .testTag("onboarding.mockTrial.judgement")
            .padding(PleadSpacing.m),
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.s),
    ) {
        Row(
            Modifier.padding(bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScalesGlyph(color = PleadColor.burgundy, size = 12.dp)
            Text(
                MockTrialScript.judgementLabel,
                style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
                color = PleadColor.burgundy,
                modifier = Modifier.semantics {
                    contentDescription = MockTrialScript.sentence(MockTrialScript.judgementLabel)
                    heading()
                },
            )
        }
        MockTrialScript.judgementOptions.forEachIndexed { i, title ->
            JudgementOption(title, isSelected = selected && i == MockTrialScript.judgementChoice)
        }
    }
}

@Composable
private fun JudgementOption(title: String, isSelected: Boolean) {
    val shape = RoundedCornerShape(PleadRadius.tile)
    val scale by animateFloatAsState(if (isSelected) 1.02f else 1f, swiftSpring(0.25f, 0.3f), label = "optionScale")
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .background(if (isSelected) PleadColor.paperWhite else PleadColor.parchment, shape)
            .border(if (isSelected) 2.dp else 1.dp, if (isSelected) PleadColor.burgundy else PleadColor.walnut.copy(alpha = 0.22f), shape)
            .clearAndSetSemantics {
                contentDescription = title
                selected = isSelected
            }
            .padding(horizontal = PleadSpacing.m, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(22.dp)
                .border(2.dp, if (isSelected) PleadColor.burgundy else PleadColor.walnut.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (isSelected) Box(Modifier.padding(5.dp).fillMaxSize().background(PleadColor.burgundy, CircleShape))
        }
        Text(title, style = PleadType.titleM, color = PleadColor.cocoa, modifier = Modifier.weight(1f))
    }
}

// MARK: - Case closed

/**
 * CASE CLOSED: the payoff card over the dimmed room. The scales glyph, the CASE CLOSED stamp (the court's stamp,
 * landing once), "That's a Plead trial." in Fraunces and the three lines. The CTA sits in the controls.
 */
@Composable
fun MockTrialClosedCard(modifier: Modifier = Modifier) {
    val r = OnboardingRadius.card
    val shape = RoundedCornerShape(r)
    Column(
        modifier
            .pleadShadow(Color.Black.copy(alpha = 0.4f), radius = 16.dp, y = 8.dp, shape = shape)
            .background(PleadColor.paperWhite, shape)
            .drawWithBorders(r.value, goldInset = 4f, goldAlpha = 0.55f)
            .clearAndSetSemantics {
                contentDescription = MockTrialScript.closedAccessibilityText
                heading()
            }
            .testTag("onboarding.mockTrial.closed")
            .padding(horizontal = PleadSpacing.l, vertical = PleadSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        ScalesGlyph(color = PleadColor.burgundy, size = 30.dp)
        CourtStamp(
            text = MockTrialScript.closedStamp,
            color = PleadColor.burgundy,
            angle = -5f,
            modifier = Modifier.courtLanding(
                pending = true, fromScale = CourtMotionTiming.stampFromScale, delay = 0.15,
                duration = CourtMotionTiming.stamp, bounce = 0.35f,
            ),
        )
        Text(
            MockTrialScript.closedTitle,
            style = PleadType.displayL,
            color = OnboardingPalette.wine,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("onboarding.mockTrial.closedTitle"),
        )
        Column(
            Modifier.fillMaxWidth().padding(horizontal = PleadSpacing.s),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (line in MockTrialScript.closedLines) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Icon(
                        SFSymbol.icon("checkmark"), contentDescription = null, tint = PleadColor.gold,
                        modifier = Modifier.padding(top = 3.dp).size(14.dp),
                    )
                    Text(line, style = PleadType.body, color = PleadColor.cocoa)
                }
            }
        }
    }
}

// MARK: - Podium

/**
 * A party at their podium: the pixel avatar (blink from the ambient clock, talk frames from the player), a warm lamp
 * glow while speaking (and for the winner), and the parchment name tag with the role chip, which glows softly in the
 * side's colour while they speak. Mirrors `CourtPodiumParty`'s look. Positions are laid out in the zones' coordinate
 * space (dp, top-left origin), so place it in a Box the size of the zones.
 */
@Composable
internal fun MockTrialPodium(
    role: Role,
    zones: CourtroomZones,
    modifier: Modifier = Modifier,
    speaking: Boolean = false,
    winner: Boolean = false,
    mouthOpen: Boolean = false,
    pose: CourtFigurePose = CourtFigurePose(),
    hop: Float = 0f,
    /** The shared entrance's pose for this party (walk-in); the podium and its name tag stay put. */
    entrance: CourtEntrancePose = CourtEntrancePose.standing,
    /**
     * The parchment name tag under the avatar. The summons explainer (amendment ai) shows the court at rest without
     * tags; the mock trial always shows them.
     */
    showsTag: Boolean = true,
) {
    val reduceMotion = accessibilityReduceMotion()
    val a = zones.avatarFrame(role)
    val podium = zones.rect(CourtroomZones.podium(role))
    val lit = speaking || winner
    val c = CourtPodiumParty.tagCenter(zones, role)
    val litSpec = tween<Float>((if (reduceMotion) 0.15 else CourtMotionTiming.characterChange).millis(), easing = PleadMotion.easeOut)
    val litAlpha by animateFloatAsState(if (lit) 1f else 0f, litSpec, label = "podiumLit")
    val lift by animateFloatAsState(
        if (reduceMotion) 0f else pose.lift + hop,
        if (reduceMotion) tween(0) else tween((if (hop > 0) 0.12 else CourtMotionTiming.bobEase).millis(), easing = PleadMotion.easeInOut),
        label = "podiumLift",
    )
    val lean by animateFloatAsState(
        if (speaking && !reduceMotion) 1.02f else 1f, litSpec, label = "podiumLean",
    )
    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics { contentDescription = "${CourtroomLogic.roleTitle(role)}: ${MockTrialPersonas.name(role)}" },
    ) {
        // Lamp glow.
        Canvas(
            Modifier
                .position(a.center.x, a.center.y + a.height * 0.1f)
                .size((a.width * 1.9f).dp, (a.height * 1.6f).dp)
                .graphicsLayer { alpha = litAlpha },
        ) {
            drawOval(
                Brush.radialGradient(
                    listOf(CourtColor.lampGlow.copy(alpha = 0.55f), CourtColor.lampGlow.copy(alpha = 0f)),
                    center = Offset(size.width / 2, size.height / 2),
                    radius = (a.width * 0.95f).dp.toPx(),
                ),
            )
        }
        Box(Modifier.fillMaxSize().courtEntrancePose(entrance, reduceMotion)) {
            CourtAvatarSprite(
                avatar = MockTrialPersonas.avatar(role),
                size = a.width,
                eyesClosed = if (reduceMotion) false else pose.eyesClosed,
                mouthOpen = mouthOpen,
                walkFrame = if (reduceMotion) 0 else entrance.walkFrame,
                modifier = Modifier
                    .position(a.center.x, a.center.y)
                    .graphicsLayer {
                        translationY = (-lift).dp.toPx()
                        scaleX = lean
                        scaleY = lean
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    },
            )
        }
        if (showsTag) {
            MockTrialPodiumTag(
                role, lit = lit, speaking = speaking, reduceMotion = reduceMotion,
                modifier = Modifier.position(c.x, c.y).widthIn(max = (podium.width - 6).coerceAtLeast(0f).dp),
            )
        }
    }
}

@Composable
private fun MockTrialPodiumTag(role: Role, lit: Boolean, speaking: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    val spec = tween<Float>((if (reduceMotion) 0.15 else CourtMotionTiming.characterChange).millis(), easing = PleadMotion.easeOut)
    val glow by animateFloatAsState(if (speaking) 0.55f else 0f, spec, label = "tagGlow")
    // `.dynamicTypeSize(...large)`: the tag never grows past the default size.
    DynamicTypeCap(DynamicTypeSize.large) {
        Column(
            modifier
                .pleadShadow(PleadColor.role(role).copy(alpha = glow), radius = 6.dp, shape = shape)
                .pleadShadow(Color.Black.copy(alpha = 0.25f), radius = 2.dp, y = 1.dp, shape = shape)
                .background(PleadColor.parchment, shape)
                .border(if (lit) 2.dp else 1.dp, if (lit) PleadColor.role(role) else PleadColor.walnut.copy(alpha = 0.5f), shape)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ScaledText(
                MockTrialPersonas.name(role),
                style = CourtFont.partyName,
                color = PleadColor.cocoa,
                minimumScaleFactor = 0.75f,
                maxLines = 1,
            )
            CourtRoleChip(role)
        }
    }
}

// MARK: - Gavel

/**
 * `CourtGavelLayer` driven by the player's frame instead of the live director: the painted gavel is covered by its
 * mirror patch of the bench while the pixel gavel swings raise → strike (+ impact pixels) → return.
 */
@Composable
fun MockTrialGavelLayer(zones: CourtroomZones, frame: GavelFrame, modifier: Modifier = Modifier) {
    val patch = zones.rect(CourtArtCrops.gavelPatchUnit)
    val cell = zones.art.width / CourtArtCrops.artPixels.width * CourtGavelSprite.artCell
    val size = CourtGavelSprite.size(cell)
    val origin = Offset(zones.x(CourtArtCrops.gavelOriginUnit.x), zones.y(CourtArtCrops.gavelOriginUnit.y))
    val crops = CourtArtCrops.shared()
    val angle by animateFloatAsState(
        CourtGavelLayer.angle(frame),
        tween(
            (if (frame == GavelFrame.struck) CourtMotionTiming.gavelStrike else CourtMotionTiming.gavelRaise).millis(),
            easing = PleadMotion.easeOut,
        ),
        label = "mockGavel",
    )
    Box(
        modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (frame == GavelFrame.rest) 0f else 1f }
            .clearAndSetSemantics { },
    ) {
        val img = crops.gavelPatch
        if (img != null) {
            Canvas(Modifier.frameIn(patch)) {
                drawImage(
                    img,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }
        CourtGavelSprite(
            cell = cell,
            impact = frame == GavelFrame.struck,
            modifier = Modifier
                .frameIn(Rect(Offset(origin.x - CourtGavelSprite.margin * cell, origin.y), size))
                .graphicsLayer {
                    rotationZ = angle
                    transformOrigin = CourtGavelSprite.pivot
                },
        )
    }
}

// MARK: - Case chip

/** "CASE DEMO · THE LAST SLICE CASE" on a small mahogany plate (the bench nameplate's style). */
@Composable
fun MockTrialCaseChip(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(7.dp)
    DynamicTypeCap(DynamicTypeSize.large) {
        Row(
            modifier
                .background(PleadColor.mahogany.copy(alpha = 0.95f), shape)
                .drawBehind {
                    strokeBorder(7.dp.toPx(), inset = 2.dp.toPx(), width = 1.dp.toPx(), color = PleadColor.gold.copy(alpha = 0.7f))
                    strokeBorder(7.dp.toPx(), inset = 0f, width = 1.dp.toPx(), color = PleadColor.cocoa)
                }
                .height(MockTrialLayout.chipHeight.dp)
                .padding(horizontal = 9.dp)
                .clearAndSetSemantics { contentDescription = "Case demo: ${MockTrialScript.caseTitle}" },
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScalesGlyph(size = 10.dp)
            ScaledText(
                MockTrialScript.caseChip,
                style = PleadType.labelCaps.copy(letterSpacing = 1.0.sp),
                color = PleadColor.cream,
                minimumScaleFactor = 0.7f,
                maxLines = 1,
            )
        }
    }
}

// MARK: - Layout helpers (SwiftUI `.position` / `.offset` in the zones' coordinate space)

/** Places the node's top-left at (x, y) dp inside its parent (a `ZStack(alignment: .topLeading)` + offset). */
internal fun Modifier.placeAt(x: Float, y: Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) { p.place(IntOffset(x.dp.roundToPx(), y.dp.roundToPx())) }
}

/** SwiftUI `.position(x:y:)`: centres the node on (x, y) dp inside its parent. */
internal fun Modifier.centeredAt(x: Float, y: Float): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    layout(p.width, p.height) { p.place(IntOffset(x.dp.roundToPx() - p.width / 2, y.dp.roundToPx() - p.height / 2)) }
}
