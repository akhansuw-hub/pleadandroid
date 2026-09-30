// Port of ArgueWin/Features/Onboarding/OnboardingCompleteView.swift.
package app.plead.android.features.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.plead.android.app.AppModel
import app.plead.android.designsystem.PixelAvatarView
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.Avatar
import app.plead.android.models.JudgePersona
import app.plead.android.models.Profile
import app.plead.android.models.Role as PartyRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt
import app.plead.android.courtroom.CourtroomZones
import app.plead.android.courtroom.CourtroomBackground
import app.plead.android.courtroom.JudgeSprite
import app.plead.android.courtroom.GavelFrame
import app.plead.android.courtroom.CourtGavelLayer
import app.plead.android.courtroom.CourtGavelSprite
import app.plead.android.courtroom.CourtArtCrops

/**
 * Court Is Ready (amendment ak, brief image11): the payoff scene. The painted courtroom assembles with Judge
 * Wigsworth at the bench and both parties at their podiums (the user's avatar and name from onboarding, the
 * partner's once linked, else "Your partner"); the judge raises the gavel and strikes it (one haptic) as "Court is
 * now in session" lands; then the supporting line and the CTAs.
 *
 * FILE MY FIRST CASE → completes onboarding and opens the file-case sheet once the user is in the app (after the
 * paywall gate for an unpaid couple). EXPLORE PLEAD completes onboarding into the app through the existing
 * secondary exit (`.invite`: nothing extra opens for a linked couple).
 * Reduce Motion: every layer fades on the same clock and the gavel does not swing (the haptic still marks it).
 */
object OnboardingCompleteView {
    const val eyebrow = "Ready"
    const val cardEyebrow = "Your court is ready"
    const val headline = "Court is now in session"
    const val supporting = "Your first case is only one argument away."
    const val primaryTitle = "FILE MY FIRST CASE →"
    const val secondaryTitle = "EXPLORE PLEAD"
    const val primaryIdentifier = "onboarding.ready.fileCase"
    const val secondaryIdentifier = "onboarding.ready.explore"
    const val partnerFallback = "Your partner"
    const val meFallback = "You"

    /** The name on a podium: trimmed, or `fallback` when empty. */
    fun podiumName(name: String?, fallback: String): String {
        val t = OnboardingFlow.trimmedName(name ?: "")
        return t.ifEmpty { fallback }
    }

    /** A stand-in partner avatar that never matches the user's. */
    fun partnerStandIn(mine: Avatar): Avatar = OnboardingAvatars.presets.firstOrNull { it != mine } ?: mine
}

@Composable
fun OnboardingCompleteView(app: AppModel, modifier: Modifier = Modifier) {
    val V = OnboardingCompleteView
    val Ready = OnboardingMotionTokens.Ready
    val S = OnboardingKitTokens.Spacing
    val store = app.store
    val onboarding = app.onboardingModel
    val reduceMotion = accessibilityReduceMotion()
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf<AppModel.OnboardingExit?>(null) }
    var gavel by remember { mutableStateOf(GavelFrame.rest) }
    var struck by remember { mutableStateOf(false) }

    // Parties
    val linkedPartner: Profile? = if (store.couple?.isLinked == true) store.partner else null
    val myName = V.podiumName(store.me?.displayName ?: onboarding.displayName, V.meFallback)
    val myAvatar = store.me?.avatar ?: onboarding.avatar
    // Amendment aw: the linked partner's own name, else "Your partner" (onboarding no longer asks for a name).
    val partnerName = V.podiumName(linkedPartner?.displayName, V.partnerFallback)
    val partnerAvatar = linkedPartner?.avatar ?: V.partnerStandIn(myAvatar)

    // Raise → strike (haptic, with the headline) → return, once per entry. Reduce Motion: no swing.
    LaunchedEffect(Unit) {
        suspend fun wait(t: Double) = delay((maxOf(t, 0.0) * 1000).toLong())
        if (reduceMotion) {
            wait(Ready.gavel)
            struck = true
            return@LaunchedEffect
        }
        wait(Ready.gavelRaise)
        gavel = GavelFrame.raised
        wait(Ready.gavelRaiseDuration)
        gavel = GavelFrame.struck
        struck = true
        wait(Ready.gavelHold)
        gavel = GavelFrame.returning
        wait(Ready.gavelRaiseDuration)
        gavel = GavelFrame.rest
    }
    OnboardingHaptic(OnboardingHaptics.Moment.gavel, struck)
    OnboardingHaptic(OnboardingHaptics.Moment.completion, finishing)

    fun finish(exit: AppModel.OnboardingExit) {
        if (finishing != null) return
        finishing = exit
        scope.launch { app.completeOnboarding(exit) }
    }

    OnboardingShell(
        modifier = modifier,
        spacing = S.tight,
        ctaDelay = Ready.cta,
        hero = { CourtEyebrow(V.eyebrow, Modifier.courtLayer(0, style = CourtLayerStyle.fade)) },
        content = {
            item {
                Column(Modifier.fillMaxWidth()) {
                    ReadyCourtroom(
                        me = ReadyCourtroom.Party(myName, myAvatar),
                        partner = ReadyCourtroom.Party(partnerName, partnerAvatar),
                        gavel = gavel,
                    )
                    SessionCard(
                        Modifier
                            .overlapUp(OnboardingKitTokens.Size.readyCardOverlap)
                            .padding(horizontal = S.cardPadding),
                    )
                }
            }
        },
        cta = {
            CourtPrimaryButton(
                title = V.primaryTitle,
                identifier = V.primaryIdentifier,
                isLoading = finishing == AppModel.OnboardingExit.fileCase,
                enabled = finishing == null,
            ) { finish(AppModel.OnboardingExit.fileCase) }
            CourtSecondaryButton(
                title = V.secondaryTitle,
                identifier = V.secondaryIdentifier,
                isLoading = finishing == AppModel.OnboardingExit.invite,
                enabled = finishing == null,
            ) { finish(AppModel.OnboardingExit.invite) }
        },
    )
}

/** SwiftUI `VStack(spacing: -overlap)`: the content is laid out `overlap` higher and takes that much less height. */
private fun Modifier.overlapUp(overlap: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val o = overlap.roundToPx()
    layout(p.width, (p.height - o).coerceAtLeast(0)) { p.place(0, -o) }
}

@Composable
private fun SessionCard(modifier: Modifier = Modifier) {
    val V = OnboardingCompleteView
    val Ready = OnboardingMotionTokens.Ready
    val S = OnboardingKitTokens.Spacing
    val shape = RoundedCornerShape(OnboardingKitTokens.Radius.scene)
    Column(
        modifier
            .courtLayer(0, delay = Ready.gavelRaise)
            .fillMaxWidth()
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.12f), radius = 16.dp, y = 6.dp, shape = shape)
            .background(OnboardingPalette.paper, shape)
            .border(OnboardingKitTokens.Size.hairline, OnboardingPalette.border, shape)
            .padding(horizontal = S.cardPadding, vertical = S.section),
        verticalArrangement = Arrangement.spacedBy(S.tight),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CourtEyebrow(V.cardEyebrow, color = OnboardingPalette.gold)
        CourtHeadline(V.headline, Modifier.courtLayer(0, delay = Ready.session), size = CourtHeadline.Size.display)
        Box(
            Modifier
                .courtLayer(0, style = CourtLayerStyle.fade, delay = Ready.subtitle)
                .size(OnboardingKitTokens.Size.iconTile, 2.dp)
                .background(OnboardingPalette.gold, CircleShape)
                .clearAndSetSemantics { },
        )
        CourtSupportingText(V.supporting, Modifier.courtLayer(0, delay = Ready.subtitle))
    }
}

// MARK: - Courtroom

/**
 * The painted courtroom cropped to bench → podiums, with the pixel judge at the bench, a swinging pixel gavel
 * over the painted one, and both parties (avatar + name tag) at their podiums. Assembles room → judge → user →
 * partner (`OnboardingMotionTokens.Ready`). One TalkBack element.
 */
object ReadyCourtroom {
    data class Party(val name: String, val avatar: Avatar)

    /** Width / height of the window onto the art. */
    val aspect: Float
        get() = CourtroomZones.artAspect / (OnboardingKitTokens.Size.readySceneBottom - OnboardingKitTokens.Size.readySceneTop)

    fun accessibilityText(me: String, partner: String): String =
        "The courtroom is in session: Judge Wigsworth at the bench, $me and $partner at the two podiums."
}

@Composable
internal fun ReadyCourtroom(
    me: ReadyCourtroom.Party,
    partner: ReadyCourtroom.Party,
    modifier: Modifier = Modifier,
    gavel: GavelFrame = GavelFrame.rest,
) {
    val Ready = OnboardingMotionTokens.Ready
    val Z = OnboardingKitTokens.Size
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .aspectRatio(ReadyCourtroom.aspect)
            .clip(RoundedCornerShape(OnboardingKitTokens.Radius.scene))
            .clearAndSetSemantics {
                contentDescription = ReadyCourtroom.accessibilityText(me.name, partner.name)
                role = Role.Image
            }
            .testTag("onboarding.ready.courtroom"),
    ) {
        val w = maxWidth.value
        val art = Size(w, w / CourtroomZones.artAspect)
        val z = CourtroomZones(art)
        Box(
            Modifier
                .requiredSize(art.width.dp, art.height.dp)
                .offset(y = (-art.height * Z.readySceneTop).dp)
                .let { it },
        ) {
            CourtroomBackground(art, Modifier.courtLayer(0, style = CourtLayerStyle.fade))
            val f = z.judgeFrame
            Box(Modifier.offset(f.left.dp, f.top.dp).size(f.width.dp, f.height.dp).courtLayer(0, delay = Ready.judgeEnter)) {
                JudgeSprite(JudgePersona.wigsworth, cell = z.judgeCell)
            }
            ReadyGavel(z, gavel)
            ReadyParty(me, PartyRole.plaintiff, z, Modifier.courtLayer(0, delay = Ready.userEnter))
            ReadyParty(partner, PartyRole.defendant, z, Modifier.courtLayer(0, delay = Ready.partnerEnter))
        }
    }
}

/** The art is taller than the window: `requiredSize` centres it, so shift it back to the top before the offset. */
@Composable
private fun ReadyParty(p: ReadyCourtroom.Party, role: PartyRole, z: CourtroomZones, modifier: Modifier = Modifier) {
    val a = z.avatarFrame(role)
    val podium = z.rect(CourtroomZones.podium(role))
    Box(modifier.requiredSize(z.size.width.dp, z.size.height.dp)) {
        PixelAvatarView(p.avatar, Modifier.offset(a.left.dp, a.top.dp).size(a.width.dp, a.height.dp), size = a.width.dp)
        // Tag centred at (podium.midX, podium.minY + 0.34 × height), at most the podium's width.
        val cx = podium.center.x
        val cy = podium.top + podium.height * 0.34f
        val base = LocalDensity.current
        // `.dynamicTypeSize(...DynamicTypeSize.xLarge)`: cap the font scale.
        CompositionLocalProvider(LocalDensity provides Density(base.density, min(base.fontScale, 1.3f))) {
            val tagShape = RoundedCornerShape(OnboardingKitTokens.Radius.tab)
            Text(
                p.name,
                style = PleadType.ui(12f, FontWeight.Bold, relativeTo = TextStyleKind.caption),
                color = OnboardingPalette.cocoa,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val maxW = (podium.width.dp - OnboardingKitTokens.Spacing.tight).roundToPx().coerceAtLeast(0)
                        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = min(maxW, constraints.maxWidth)))
                        layout(constraints.maxWidth, constraints.maxHeight) {
                            placeable.place(
                                (cx.dp.toPx() - placeable.width / 2f).roundToInt(),
                                (cy.dp.toPx() - placeable.height / 2f).roundToInt(),
                            )
                        }
                    }
                    .pleadShadow(Color.Black.copy(alpha = 0.25f), radius = 2.dp, y = 1.dp, shape = tagShape)
                    .background(OnboardingPalette.parchment, tagShape)
                    .border(1.5.dp, if (role == PartyRole.plaintiff) OnboardingPalette.burgundy else OnboardingPalette.gold, tagShape)
                    .padding(horizontal = OnboardingKitTokens.Spacing.tight, vertical = OnboardingKitTokens.Spacing.ctaStack),
            )
        }
    }
}

/**
 * The judge's gavel: while it swings, the painted one is covered by its bench patch and a pixel gavel rotates
 * about the handle's end (raise → strike with impact pixels → return). Hidden at rest.
 */
@Composable
private fun ReadyGavel(zones: CourtroomZones, frame: GavelFrame) {
    val Ready = OnboardingMotionTokens.Ready
    val patch = zones.rect(CourtArtCrops.gavelPatchUnit)
    val cell = zones.art.width / CourtArtCrops.artPixels.width * CourtGavelSprite.artCell
    val size = CourtGavelSprite.size(cell)
    val originX = zones.x(CourtArtCrops.gavelOriginUnit.x)
    val originY = zones.y(CourtArtCrops.gavelOriginUnit.y)
    val angle by animateFloatAsState(
        CourtGavelLayer.angle(frame).toFloat(),
        tween(
            (((if (frame == GavelFrame.struck) Ready.gavelStrikeDuration else Ready.gavelRaiseDuration)) * 1000).toInt(),
            easing = PleadMotion.easeOut,
        ),
        label = "readyGavel",
    )
    Box(
        Modifier
            .requiredSize(zones.size.width.dp, zones.size.height.dp)
            .graphicsLayer { alpha = if (frame == GavelFrame.rest) 0f else 1f }
            .clearAndSetSemantics { },
    ) {
        CourtArtCrops.shared().gavelPatch?.let { img ->
            Canvas(Modifier.offset(patch.left.dp, patch.top.dp).size(patch.width.dp, patch.height.dp)) {
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
                .offset((originX - CourtGavelSprite.margin * cell).dp, originY.dp)
                .graphicsLayer {
                    rotationZ = angle
                    transformOrigin = CourtGavelSprite.pivot
                },
        )
        @Suppress("UNUSED_VARIABLE") val unused = size
    }
}

