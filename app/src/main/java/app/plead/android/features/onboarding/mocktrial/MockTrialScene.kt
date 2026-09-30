// Port of ArgueWin/Features/Onboarding/MockTrial/MockTrialScene.swift — partial port: the pieces SummonsIntroView uses
// (`MockTrialPersonas`, `MockTrialPodium`); the stage/player are owed after the wave 3a merge.
// `CourtAvatarSprite` (Courtroom/CourtMotionViews.swift), `CourtRoleChip` (CourtStyle.swift), `CourtColor.lampGlow` and
// `CourtPodiumParty.tagCenter` (CourtStage.swift) are wave 3a's; interim copies live at the end of this file (internal)
// and are deleted with OnboardingBorrowedArt.kt once 3a's originals are merged.
package app.plead.android.features.onboarding

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.PixelAvatar
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.fixedSp
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.Avatar
import app.plead.android.models.JudgePersona
import app.plead.android.models.Role
import kotlin.math.floor
import kotlin.math.roundToInt
import app.plead.android.courtroom.CourtroomZones
import app.plead.android.courtroom.CourtWalkCycle
import app.plead.android.courtroom.CourtFigurePose
import app.plead.android.courtroom.CourtMotionTiming

/** The demo personas (the same pixel avatars as the app's demo couple, `PreviewData.me` / `.partner`). */
object MockTrialPersonas {
    val plaintiff = Avatar(skin = 1, hair = 2, hairstyle = Avatar.Hairstyle.ponytail, top = 0, outfit = Avatar.Outfit.hoodie)
    val defendant = Avatar(skin = 3, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.shirt)
    val judge: JudgePersona = JudgePersona.wigsworth

    fun avatar(r: Role): Avatar = if (r == Role.plaintiff) plaintiff else defendant
    fun name(r: Role): String = if (r == Role.plaintiff) MockTrialScript.plaintiffName else MockTrialScript.defendantName
}

// MARK: - Podium

/**
 * A party at their podium: the pixel avatar (blink from the ambient clock, talk frames from the player), a
 * warm lamp glow while speaking (and for the winner), and the parchment name tag with the role chip, which
 * glows softly in the side's colour while they speak. Mirrors `CourtPodiumParty`'s look. Positions are laid out in
 * the zones' coordinate space (dp, top-left origin), so place it in a Box the size of the zones.
 * Not ported yet: the shared entrance pose (`entrance: CourtEntrancePose`, the walk-in), owed with the stage.
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
    /**
     * The parchment name tag under the avatar. The summons explainer (amendment ai) shows the court at rest
     * without tags; the mock trial always shows them.
     */
    showsTag: Boolean = true,
) {
    val reduceMotion = accessibilityReduceMotion()
    val a = zones.avatarFrame(role)
    val podium = zones.rect(CourtroomZones.podium(role))
    val lit = speaking || winner
    val c = CourtPodiumParty.tagCenter(zones, role)
    val litAlpha by animateFloatAsState(
        if (lit) 1f else 0f,
        tween(if (reduceMotion) 150 else CourtMotionTiming.characterChange.millis(), easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)),
        label = "podiumLit",
    )
    val lift by animateFloatAsState(
        if (reduceMotion) 0f else pose.lift + hop,
        if (reduceMotion) tween(0) else tween((if (hop > 0) 0.12 else CourtMotionTiming.bobEase).millis(), easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)),
        label = "podiumLift",
    )
    Box(
        modifier
            .fillMaxSize()
            .clearAndSetSemantics { contentDescription = "${CourtPodiumParty.roleTitle(role)}: ${MockTrialPersonas.name(role)}" },
    ) {
        // Lamp glow.
        val glowW = a.width * 1.9f
        val glowH = a.height * 1.6f
        Canvas(
            Modifier
                .placeAt(a.center.x - glowW / 2, a.center.y + a.height * 0.1f - glowH / 2)
                .size(glowW.dp, glowH.dp)
                .graphicsLayer { alpha = litAlpha },
        ) {
            drawOval(
                Brush.radialGradient(
                    listOf(CourtPodiumParty.lampGlow.copy(alpha = 0.55f), CourtPodiumParty.lampGlow.copy(alpha = 0f)),
                    center = Offset(size.width / 2, size.height / 2),
                    radius = (a.width * 0.95f).dp.toPx(),
                ),
            )
        }
        CourtAvatarSprite(
            avatar = MockTrialPersonas.avatar(role),
            size = a.width,
            modifier = Modifier
                .placeAt(a.left, a.top - lift)
                .graphicsLayer {
                    val s = if (speaking && !reduceMotion) 1.02f else 1f
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(0.5f, 1f)
                },
            eyesClosed = if (reduceMotion) false else pose.eyesClosed,
            mouthOpen = mouthOpen,
        )
        if (showsTag) {
            MockTrialPodiumTag(
                role, lit = lit, speaking = speaking,
                modifier = Modifier.centeredAt(c.x, c.y).widthIn(max = (podium.width - 6).dp),
            )
        }
    }
}

@Composable
private fun MockTrialPodiumTag(role: Role, lit: Boolean, speaking: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier) {
        if (speaking) {
            Box(Modifier.matchParentSize().blur(6.dp).graphicsLayer { alpha = 0.55f }.background(PleadColor.role(role), shape))
        }
        Column(
            Modifier
                .pleadShadow(Color.Black.copy(alpha = 0.25f), radius = 2.dp, y = 1.dp, shape = shape)
                .background(PleadColor.parchment, shape)
                .border(if (lit) 2.dp else 1.dp, if (lit) PleadColor.role(role) else PleadColor.walnut.copy(alpha = 0.5f), shape)
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            // `.dynamicTypeSize(...large)`: the tag never grows past the default size.
            Text(
                MockTrialPersonas.name(role),
                style = CourtFont.partyName.copy(fontSize = fixedSp(CourtFont.partyName.fontSize.value)),
                color = PleadColor.cocoa,
                maxLines = 1,
            )
            CourtRoleChip(role)
        }
    }
}

// MARK: - Interim wave 3a pieces (delete after the merge)

/** `CourtPodiumParty` statics and `CourtColor.lampGlow` / `CourtroomLogic.roleTitle`. */
internal object CourtPodiumParty {
    val lampGlow = Color(hex = 0xFFD9A8)

    fun tagCenter(z: CourtroomZones, role: Role): Offset {
        val podium = z.rect(CourtroomZones.podium(role))
        return Offset(podium.center.x, podium.top + podium.height * 0.34f)
    }

    fun roleTitle(r: Role): String = if (r == Role.plaintiff) "Plaintiff" else "Defendant"
}

/** `CourtRoleChip`: PLAINTIFF / DEFENDANT on the side's colour. */
@Composable
internal fun CourtRoleChip(role: Role, modifier: Modifier = Modifier) {
    Text(
        CourtPodiumParty.roleTitle(role).uppercase(),
        style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
        color = PleadColor.cream,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .background(PleadColor.role(role), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
            .clearAndSetSemantics { },
    )
}

/**
 * `CourtAvatarSprite`: the pixel avatar with blink / talk frames and the walk hem swing, drawn on whole cells
 * (≥ 2 pt cells are floored, as iOS draws them). [size] in dp.
 */
@Composable
internal fun CourtAvatarSprite(
    avatar: Avatar,
    size: Float,
    modifier: Modifier = Modifier,
    eyesClosed: Boolean = false,
    mouthOpen: Boolean = false,
    walkFrame: Int = 0,
) {
    val grid = CourtAvatarSprite.grid(avatar, eyesClosed, mouthOpen, walkFrame)
    val outline = PixelAvatar.outline(grid)
    val bob = CourtWalkCycle.bob(walkFrame)
    val sizeDp = size
    Canvas(
        modifier
            .offset(y = (-bob * CourtAvatarSprite.cell(size)).dp)
            .size(size.dp)
            .clearAndSetSemantics { contentDescription = PixelAvatar.description(avatar) },
    ) {
        val n = PixelAvatar.side.toFloat()
        val raw = sizeDp / n
        val cell = if (raw >= 2) floor(raw) else raw
        val inset = (sizeDp - cell * n) / 2
        fun edge(i: Int): Float = ((inset + i * cell).dp.toPx()).roundToInt().toFloat()
        fun fill(r: Int, c: Int, color: Color) {
            val x = edge(c)
            val y = edge(r)
            drawRect(color, topLeft = Offset(x, y), size = Size(edge(c + 1) - x, edge(r + 1) - y))
        }
        for ((r, c) in outline) fill(r, c, PixelAvatar.outlineColor)
        grid.forEachIndexed { r, row -> row.forEachIndexed { c, color -> if (color != null) fill(r, c, color) } }
    }
}

internal object CourtAvatarSprite {
    val openMouth = Color(hex = 0x5A1A18)

    /** One grid cell at `size` (whole points when ≥ 2 pt, as drawn). */
    fun cell(size: Float): Float {
        val raw = size / PixelAvatar.side
        return if (raw >= 2) floor(raw) else raw
    }

    /**
     * The avatar grid with the eyes shut (skin over the eye cells) and / or the mouth open (a dark cell
     * row under the mouth), and on a walk step the outfit's hem (rows 14–15) swung one cell sideways.
     */
    fun grid(a: Avatar, eyesClosed: Boolean, mouthOpen: Boolean, walkFrame: Int = 0): List<List<Color?>> {
        val g = PixelAvatar.grid(a).map { it.toMutableList() }.toMutableList()
        if (g.size < 10 || g[5].size < 10) return g
        val skin = g[5][7] ?: return g
        if (eyesClosed) for (c in listOf(6, 9)) if (g[6][c] != null) g[6][c] = skin
        if (mouthOpen) for (c in listOf(7, 8)) if (g[9][c] != null) g[9][c] = openMouth
        val dir = CourtWalkCycle.step(walkFrame)
        if (dir != null && g.size >= 16) {
            for (r in 14..15) g[r] = CourtWalkCycle.shift(g[r], dir, null).toMutableList()
        }
        return g
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
