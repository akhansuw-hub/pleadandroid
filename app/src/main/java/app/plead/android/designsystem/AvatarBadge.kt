// Port of ArgueWin/DesignSystem/AvatarBadge.swift: the framed avatar tile, the empty partner tile and the couple pair.
package app.plead.android.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import app.plead.android.models.Avatar

/** Standard avatar sizes. */
object AvatarSize {
    val s: Dp = 28.dp
    val m: Dp = 44.dp
    val l: Dp = 72.dp
    val xl: Dp = 128.dp
}

/**
 * A pixel avatar on a framed parchment tile, optionally marked "YOU".
 * The one way the app shows a person outside the courtroom.
 */
@Composable
fun AvatarBadge(
    avatar: Avatar,
    modifier: Modifier = Modifier,
    size: Dp = AvatarSize.m,
    framed: Boolean = true,
    isYou: Boolean = false,
    dimmed: Boolean = false,
) {
    val radius = min(PleadRadius.tile, size * 0.24f)
    val shape = RoundedCornerShape(radius)
    val label = if (isYou) "Your avatar" else PixelAvatar.description(avatar)
    Box(modifier.size(size).clearAndSetSemantics { contentDescription = label }) {
        Box(
            Modifier
                .size(size)
                .saturation(if (dimmed) 0.2f else 1f)
                .alpha(if (dimmed) 0.55f else 1f)
                .clip(if (framed) shape else RectangleShape)
                .then(
                    if (framed) {
                        Modifier
                            .background(Brush.verticalGradient(listOf(PleadColor.parchment, Color(hex = 0xEAD6C4))), shape)
                            .border(if (size > 60.dp) 1.5.dp else 1.dp, PleadColor.walnut.copy(alpha = 0.28f), shape)
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            PixelAvatarView(
                avatar = avatar,
                size = if (framed) size * 0.86f else size,
                modifier = Modifier.offset(y = if (framed) size * 0.02f else 0.dp),
            )
        }
        if (isYou) {
            Text(
                "YOU",
                style = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = fixedSp(max(8.dp, size * 0.1f).value),
                    letterSpacing = fixedSp(1f),
                ),
                color = PleadColor.cream,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = 7.dp)
                    .background(PleadColor.burgundy, CircleShape)
                    .border(1.5.dp, PleadColor.cream, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Empty tile for a partner who hasn't joined yet. */
@Composable
fun AvatarPlaceholder(modifier: Modifier = Modifier, size: Dp = AvatarSize.m) {
    val radius = min(PleadRadius.tile, size * 0.24f)
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = "Partner not linked yet" }
            .background(PleadColor.parchment.copy(alpha = 0.5f), RoundedCornerShape(radius))
            .drawBehind {
                // strokeBorder: the 1.5 dp dashed stroke sits inside the tile.
                val w = 1.5.dp.toPx()
                drawRoundRect(
                    color = PleadColor.walnut.copy(alpha = 0.35f),
                    topLeft = Offset(w / 2, w / 2),
                    size = androidx.compose.ui.geometry.Size(this.size.width - w, this.size.height - w),
                    cornerRadius = CornerRadius(radius.toPx() - w / 2),
                    style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // SF Symbol "questionmark", heavy rounded.
        Text(
            "?",
            style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = fixedSp((size * 0.32f).value)),
            color = PleadColor.walnut.copy(alpha = 0.5f),
        )
    }
}

/** Both partners side by side with a blush heart between (a couple moment). */
@Composable
fun AvatarPair(
    me: Avatar?,
    partner: Avatar?,
    modifier: Modifier = Modifier,
    size: Dp = AvatarSize.l,
    showHeart: Boolean = true,
    markYou: Boolean = false,
) {
    Row(
        modifier.semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(size * 0.14f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (me != null) AvatarBadge(me, size = size, isYou = markYou) else AvatarPlaceholder(size = size)
        if (showHeart) {
            Icon(Icons.Filled.Favorite, contentDescription = null, tint = PleadColor.blush, modifier = Modifier.size(size * 0.24f))
        }
        if (partner != null) AvatarBadge(partner, size = size) else AvatarPlaceholder(size = size)
    }
}

@Preview(name = "Avatar badges", widthDp = 402)
@Composable
private fun AvatarBadgePreview() {
    Column(
        Modifier.background(PleadColor.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarBadge(Avatar.default, size = AvatarSize.s)
            AvatarBadge(Avatar.default, size = AvatarSize.m, isYou = true)
            AvatarBadge(Avatar(skin = 4, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.hoodie), size = AvatarSize.l)
            AvatarPlaceholder(size = AvatarSize.l)
        }
        AvatarBadge(Avatar(skin = 1, hair = 3, hairstyle = Avatar.Hairstyle.ponytail, top = 3, outfit = Avatar.Outfit.dress), size = AvatarSize.xl, isYou = true)
        AvatarPair(me = Avatar.default, partner = Avatar(skin = 4, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.hoodie), markYou = true)
    }
}
