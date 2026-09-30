// Port of ArgueWin/Courtroom/CourtStage.swift: the parties at their podiums — a pixel avatar standing behind each
// painted podium, with a parchment name tag (name + role chip) on the podium front. The side holding the floor gets a
// warm lamp glow. Motion (amendment x) comes from `CourtMotionDirector`: idle bob / blink, the talk loop and a softly
// highlighted name tag while this side is speaking.
package app.plead.android.courtroom

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import app.plead.android.models.Profile
import app.plead.android.models.Role
import kotlin.math.roundToInt

@Composable
fun CourtPodiumParty(
    profile: Profile,
    role: Role,
    isMe: Boolean,
    hasFloor: Boolean,
    zones: CourtroomZones,
    modifier: Modifier = Modifier,
    /** The verdict sequence hides the tags so its headlines own the lower half. */
    showsTag: Boolean = true,
    /** Reports the name tag's measured height (the scene keeps the easel and bubbles above it). */
    onTagHeight: ((Float) -> Unit)? = null,
    /** Poses and the speaking state (null = static, e.g. previews). */
    motion: CourtMotionDirector? = null,
    /**
     * Entrance pose (amendment ac) for the figure only: hidden while this side has not joined or before it walks in;
     * the name tag and the accessibility label stay (null = standing).
     */
    entrancePose: CourtEntrancePose? = null,
) {
    val reduceMotion = accessibilityReduceMotion()
    val density = LocalDensity.current
    val a = zones.avatarFrame(role)
    val podium = zones.rect(CourtroomZones.podium(role))
    val speaking = motion?.state(role) == CharacterState.speaking
    val label = accessibilityText(profile, role, isMe, hasFloor)
    Box(modifier.fillMaxSize()) {
        if (hasFloor && (entrancePose?.visible ?: true)) {
            // Lamp glow behind the speaker (static).
            val glow = Rect(
                offset = Offset(a.center.x - a.width * 0.95f, a.center.y + a.height * 0.1f - a.height * 0.8f),
                size = Size(a.width * 1.9f, a.height * 1.6f),
            )
            Canvas(Modifier.frameIn(glow).clearAndSetSemantics { }) {
                val c = Offset(size.width / 2f, size.height / 2f)
                drawOval(
                    Brush.radialGradient(
                        0f to CourtColor.lampGlow.copy(alpha = 0.55f),
                        1f to CourtColor.lampGlow.copy(alpha = 0f),
                        center = c,
                        radius = (a.width * 0.95f).dp.toPx(),
                    ),
                )
            }
        }
        CourtPartyFigure(
            avatar = profile.avatar,
            size = a.width,
            role = role,
            motion = motion,
            walkFrame = entrancePose?.walkFrame ?: 0,
            modifier = Modifier
                .frameIn(a)
                .courtEntrancePose(entrancePose, reduceMotion = reduceMotion)
                .then(if (showsTag) Modifier.clearAndSetSemantics { } else Modifier.clearAndSetSemantics { contentDescription = label }),
        )

        if (showsTag) {
            val c = CourtPodiumParty.tagCenter(zones, role)
            Box(
                Modifier.position(c.x, c.y),
            ) {
                NameTag(
                    profile = profile, role = role, isMe = isMe, hasFloor = hasFloor, speaking = speaking, reduceMotion = reduceMotion,
                    modifier = Modifier
                        .widthIn(max = (podium.width - 6f).coerceAtLeast(0f).dp)
                        .onSizeChanged { onTagHeight?.invoke(with(density) { it.height.toDp().value }) }
                        .clearAndSetSemantics { contentDescription = label },
                )
            }
        }
    }
}

object CourtPodiumParty {
    /** Centre of the name tag on the podium front. */
    fun tagCenter(z: CourtroomZones, role: Role): Offset {
        val podium = z.rect(CourtroomZones.podium(role))
        return Offset(podium.center.x, podium.top + podium.height * 0.34f)
    }
}

@Composable
private fun NameTag(
    profile: Profile,
    role: Role,
    isMe: Boolean,
    hasFloor: Boolean,
    speaking: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    // Speaking: the tag glows softly in the side's colour until the reveal completes.
    val glow by animateFloatAsState(
        if (speaking) 0.55f else 0f,
        tween(((if (reduceMotion) 0.15 else CourtMotionTiming.characterChange) * 1000).roundToInt(), easing = app.plead.android.designsystem.PleadMotion.easeOut),
        label = "tagGlow",
    )
    DynamicTypeCap(DynamicTypeSize.xLarge) {
        Column(
            modifier
                .pleadShadow(PleadColor.role(role).copy(alpha = glow), radius = 6.dp, shape = shape)
                .pleadShadow(Color.Black.copy(alpha = 0.25f), radius = 2.dp, y = 1.dp, shape = shape)
                .background(PleadColor.parchment, shape)
                .border(
                    if (hasFloor) 2.dp else 1.dp,
                    if (hasFloor) PleadColor.role(role) else PleadColor.walnut.copy(alpha = 0.5f),
                    shape,
                )
                .padding(horizontal = 8.dp, vertical = 5.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            ScaledText(
                if (isMe) "${profile.displayName} (you)" else profile.displayName,
                style = CourtFont.partyName,
                color = PleadColor.cocoa,
                minimumScaleFactor = 0.75f,
                maxLines = 1,
            )
            CourtRoleChip(role)
        }
    }
}

private fun accessibilityText(profile: Profile, role: Role, isMe: Boolean, hasFloor: Boolean): String {
    var s = "${CourtroomLogic.roleTitle(role)}: ${profile.displayName}"
    if (isMe) s += ", you"
    if (hasFloor) s += ". Has the floor."
    return s
}
