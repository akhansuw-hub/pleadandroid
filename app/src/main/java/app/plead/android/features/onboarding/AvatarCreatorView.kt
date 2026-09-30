// Port of ArgueWin/Features/Onboarding/AvatarCreatorView.swift (AvatarCreatorView, SwatchRow, StyleGrid,
// EditAvatarView). SwiftUI `@Binding var avatar` → `avatar` + `onAvatarChange`. Also holds the small sheet chrome
// (`OnboardingSheetBar`: the NavigationStack toolbar of iOS sheets) and `FeedbackOnChange` (`sensoryFeedback`),
// shared by the identity, partner and link screens.
package app.plead.android.features.onboarding

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.AWButton
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.AWButtonStyle
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.InlineError
import app.plead.android.designsystem.PixelAvatar
import app.plead.android.designsystem.PixelAvatarView
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadFont
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.SectionLabel
import app.plead.android.designsystem.awBackground
import app.plead.android.designsystem.awBottomBar
import app.plead.android.designsystem.title
import app.plead.android.models.Avatar
import app.plead.android.services.CaseStore
import kotlinx.coroutines.launch

/**
 * Pixel avatar creator: skin tone (6), hair colour (6), hairstyle (6), top colour (8), outfit (5),
 * a live 128 pt preview and Randomise. Used in onboarding and in "Edit avatar" (Settings / Us).
 */
@Composable
fun AvatarCreatorView(avatar: Avatar, onAvatarChange: (Avatar) -> Unit, modifier: Modifier = Modifier) {
    var rolls by remember { mutableIntStateOf(0) }
    FeedbackOnChange(avatar, HapticFeedbackConstants.CLOCK_TICK) // `.sensoryFeedback(.selection, trigger: avatar)`
    FeedbackOnChange(rolls, HapticFeedbackConstants.KEYBOARD_TAP) // `.impact(weight: .light)` on Randomise
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(PleadSpacing.xl)) {
        AvatarCreatorPreview(avatar) {
            var next = Avatar.random()
            var guardCount = 0
            while (next == avatar && guardCount < 5) {
                next = Avatar.random(); guardCount += 1
            }
            onAvatarChange(next)
            rolls += 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.l)) {
            CreatorOption("Skin tone") {
                SwatchRow(Avatar.skinTones, avatar.skin, "Skin tone") { onAvatarChange(avatar.copy(skin = it)) }
            }
            CreatorOption("Hair colour") {
                SwatchRow(Avatar.hairColours, avatar.hair, "Hair colour") { onAvatarChange(avatar.copy(hair = it)) }
            }
            CreatorOption("Hairstyle") {
                StyleGrid(Avatar.Hairstyle.entries, avatar.hairstyle, { it.title }, { avatar.copy(hairstyle = it) }) {
                    onAvatarChange(avatar.copy(hairstyle = it))
                }
            }
            CreatorOption("Top colour") {
                SwatchRow(Avatar.topColours, avatar.top, "Top colour") { onAvatarChange(avatar.copy(top = it)) }
            }
            CreatorOption("Outfit") {
                StyleGrid(Avatar.Outfit.entries, avatar.outfit, { it.title }, { avatar.copy(outfit = it) }) {
                    onAvatarChange(avatar.copy(outfit = it))
                }
            }
        }
    }
}

@Composable
private fun AvatarCreatorPreview(avatar: Avatar, randomise: () -> Unit) {
    val shape = RoundedCornerShape(PleadRadius.card)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        Box(
            Modifier
                .size(168.dp)
                .background(Brush.verticalGradient(listOf(PleadColor.parchment, Color(hex = 0xE8D2BE))), shape)
                .border(1.5.dp, PleadColor.walnut.copy(alpha = 0.3f), shape)
                .clearAndSetSemantics { contentDescription = "Preview. ${PixelAvatar.description(avatar)}" },
            contentAlignment = Alignment.BottomCenter,
        ) {
            // A little podium under the figure, echoing the courtroom.
            Box(
                Modifier.width(120.dp).height(14.dp)
                    .background(PleadColor.walnut.copy(alpha = 0.9f), RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)),
            )
            PixelAvatarView(avatar, Modifier.padding(bottom = 10.dp), size = 128.dp)
        }
        AWButton(onClick = randomise, style = AWButtonStyle.aw(AWButtonKind.secondary, fullWidth = false)) {
            Icon(SFSymbol.icon("dice"), contentDescription = null, modifier = Modifier.size(20.dp))
            Text("Randomise")
        }
    }
}

@Composable
private fun CreatorOption(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        SectionLabel(title)
        content()
    }
}

/** A row of colour swatches (44 pt targets). Selected = burgundy ring. */
@Composable
private fun SwatchRow(hexes: List<Int>, selection: Int, name: String, onSelect: (Int) -> Unit) {
    val diameter = if (hexes.size > 6) 30.dp else 34.dp
    val shape = RoundedCornerShape(PleadRadius.tile)
    Row(
        Modifier.fillMaxWidth()
            .background(PleadColor.paperWhite, shape)
            .border(1.dp, PleadColor.separator, shape)
            .padding(vertical = PleadSpacing.xs),
    ) {
        hexes.forEachIndexed { i, hex ->
            val selected = i == selection
            Box(
                Modifier.weight(1f).defaultMinSize(minHeight = 44.dp)
                    .clickable { onSelect(i) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "$name ${i + 1} of ${hexes.size}"
                        role = Role.Button
                        this.selected = selected
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .border(2.5.dp, if (selected) PleadColor.burgundy else Color.Transparent, CircleShape)
                        .padding(4.dp)
                        .size(diameter)
                        .background(Color(hex = hex), CircleShape)
                        .border(1.dp, PleadColor.cocoa.copy(alpha = 0.18f), CircleShape),
                )
            }
        }
    }
}

/** Grid of tiles previewing each option on the current avatar. */
@Composable
private fun <T> StyleGrid(options: List<T>, selection: T, title: (T) -> String, previewAvatar: (T) -> Avatar, onSelect: (T) -> Unit) {
    val shape = RoundedCornerShape(PleadRadius.tile)
    Column(verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
                row.forEach { option ->
                    val selected = option == selection
                    Column(
                        Modifier.weight(1f)
                            .background(if (selected) PleadColor.parchment else PleadColor.paperWhite, shape)
                            .border(if (selected) 2.dp else 1.dp, if (selected) PleadColor.burgundy else PleadColor.separator, shape)
                            .clickable { onSelect(option) }
                            .semantics(mergeDescendants = true) {
                                contentDescription = title(option)
                                role = Role.Button
                                this.selected = selected
                            }
                            .padding(vertical = PleadSpacing.s),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        PixelAvatarView(previewAvatar(option), size = 48.dp)
                        Text(title(option), style = PleadFont.caption, color = if (selected) PleadColor.burgundy else PleadColor.cocoa)
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * "Edit avatar" — pushed from Settings and Us. Allowed solo (spec §3). [onDismiss] is the navigation pop;
 * [onCancel] shows a Cancel action in the bar (the link step presents it as a sheet).
 */
@Composable
fun EditAvatarView(store: CaseStore, onDismiss: () -> Unit, modifier: Modifier = Modifier, onCancel: (() -> Unit)? = null) {
    var avatar by remember { mutableStateOf(store.me?.avatar ?: Avatar.default) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val changed = avatar != store.me?.avatar
    FeedbackOnChange(saved, HapticFeedbackConstants.CONTEXT_CLICK)

    Column(modifier.fillMaxSize().awBackground()) {
        OnboardingSheetBar(
            title = "Edit avatar",
            leading = onCancel?.let { cancel -> { SheetBarAction("Cancel", onClick = cancel) } },
        )
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(PleadSpacing.l),
            verticalArrangement = Arrangement.spacedBy(PleadSpacing.l),
        ) {
            AvatarCreatorView(avatar, { avatar = it })
            InlineError(error)
        }
        Box(Modifier.awBottomBar()) {
            AWButton(
                onClick = {
                    if (!changed || saving) return@AWButton
                    saving = true; error = null
                    scope.launch {
                        try {
                            store.setAvatar(avatar); saved = true; onDismiss()
                        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            error = "Couldn't save your avatar. Try again."
                        }
                        saving = false
                    }
                },
                style = AWButtonStyle.aw(),
                enabled = changed && !saving,
            ) {
                if (saving) {
                    androidx.compose.material3.CircularProgressIndicator(color = PleadColor.cream, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Text("Save avatar")
                }
            }
        }
    }
}

// MARK: - Sheet chrome and feedback (shared by the onboarding screens)

/** An inline-title bar with optional leading / trailing actions (the toolbar of an iOS sheet's NavigationStack). */
@Composable
internal fun OnboardingSheetBar(
    title: String,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = PleadSpacing.s)) {
        Box(Modifier.align(Alignment.CenterStart)) { leading?.invoke() }
        Text(
            title,
            style = PleadFont.headline,
            color = PleadColor.cocoa,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).semantics { heading() },
        )
        Box(Modifier.align(Alignment.CenterEnd)) { trailing?.invoke() }
    }
}

@Composable
internal fun SheetBarAction(text: String, bold: Boolean = false, color: Color = PleadColor.burgundy, onClick: () -> Unit) {
    Box(
        Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 44.dp).clickable(onClick = onClick)
            .semantics { role = Role.Button }.padding(horizontal = PleadSpacing.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = PleadFont.body.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal), color = color)
    }
}

/** SwiftUI `.sensoryFeedback(_, trigger:)`: one haptic whenever [trigger] changes (not on first composition). */
@Composable
internal fun FeedbackOnChange(trigger: Any?, constant: Int) {
    val view = LocalView.current
    var previous by remember { mutableStateOf<Any?>(NoValue) }
    LaunchedEffect(trigger) {
        if (previous !== NoValue && previous != trigger) view.performHapticFeedback(constant)
        previous = trigger
    }
}

private object NoValue
