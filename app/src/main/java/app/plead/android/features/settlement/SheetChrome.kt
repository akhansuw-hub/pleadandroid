// Android-only chrome shared by the wave-3e sheets (settlement, judgement, settings). No Swift twin: these are the
// SwiftUI primitives those screens lean on (`NavigationStack` + toolbar Cancel, `.confirmationDialog`, `.alert`,
// `.sensoryFeedback`, `.buttonStyle(.aw(...))` with a disabled state, a pressed-scale `ButtonStyle`).
package app.plead.android.features.settlement

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.AWButton
import app.plead.android.designsystem.AWButtonKind
import app.plead.android.designsystem.AWButtonStyle
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.features.casedetail.InteractiveDismissDisabled

// MARK: - Sheet chrome

/**
 * A sheet's `NavigationStack` with an inline title and a leading cancellation item (Swift `ToolbarItem(placement:
 * .cancellationAction)`), plus an optional trailing confirmation item. While [dismissDisabled] the system back gesture
 * is swallowed (`interactiveDismissDisabled`); the host's swipe-down should also honour it.
 */
@Composable
fun SheetScaffold(
    cancelTitle: String?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    cancelEnabled: Boolean = true,
    confirmTitle: String? = null,
    onConfirm: () -> Unit = {},
    dismissDisabled: Boolean = false,
    background: Color = PleadColor.background,
    bottomBar: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(enabled = dismissDisabled) {}
    // The host's swipe-down / scrim dismissal honours it too (CaseSheetHost).
    InteractiveDismissDisabled(dismissDisabled)
    Column(modifier.fillMaxSize().background(background)) {
        Box(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = PleadSpacing.s)) {
            if (cancelTitle != null) {
                TextButton(onClick = onCancel, enabled = cancelEnabled, modifier = Modifier.align(Alignment.CenterStart)) {
                    Text(cancelTitle, style = PleadType.text(17f, FontWeight.Normal, TextStyleKind.body), color = PleadColor.cocoa.copy(alpha = if (cancelEnabled) 1f else 0.4f))
                }
            }
            if (title != null) {
                Text(
                    title,
                    style = PleadType.navTitle,
                    color = PleadColor.cocoa,
                    modifier = Modifier.align(Alignment.Center).semantics { heading() },
                )
            }
            if (confirmTitle != null) {
                TextButton(onClick = onConfirm, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text(confirmTitle, style = PleadType.text(17f, FontWeight.SemiBold, TextStyleKind.body), color = PleadColor.burgundy)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth(), content = content)
        bottomBar?.invoke()
    }
}

/** Full-screen cover chrome (status bar inset) for a cover presented from a sheet. */
@Composable
fun CoverScaffold(modifier: Modifier = Modifier, background: Color = PleadColor.background, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxSize().background(background).statusBarsPadding(), content = content)
}

// MARK: - Dialogs

/** One button of a [ConfirmationDialog]. `role = cancel` sits last and only dismisses. */
data class DialogAction(val title: String, val destructive: Boolean = false, val cancel: Boolean = false, val action: () -> Unit = {})

/**
 * SwiftUI `.confirmationDialog(title, isPresented:, titleVisibility: .visible) { buttons } message: { Text }` as a
 * Material dialog. A confirmation dialog always offers a way out: when no cancel action is given, "Cancel" is added.
 */
@Composable
fun ConfirmationDialog(
    visible: Boolean,
    title: String,
    message: String?,
    actions: List<DialogAction>,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val cancel = actions.firstOrNull { it.cancel } ?: DialogAction("Cancel", cancel = true)
    val others = actions.filter { !it.cancel }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PleadColor.paperWhite,
        title = { Text(title, style = PleadType.titleM, color = PleadColor.cocoa) },
        text = message?.let { { Text(it, style = PleadType.body, color = PleadColor.subtleText) } },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                others.forEach { a ->
                    TextButton(onClick = { onDismiss(); a.action() }) {
                        Text(a.title, style = PleadType.uiButtonSecondary, color = if (a.destructive) PleadColor.danger else PleadColor.burgundy)
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(); cancel.action() }) {
                Text(cancel.title, style = PleadType.uiButtonSecondary, color = PleadColor.cocoa)
            }
        },
    )
}

/** SwiftUI `.alert(message, isPresented:) { Button("OK") {} }`. */
@Composable
fun MessageAlert(message: String?, onDismiss: () -> Unit) {
    if (message == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = PleadColor.paperWhite,
        title = { Text(message, style = PleadType.titleM, color = PleadColor.cocoa) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK", style = PleadType.uiButtonSecondary, color = PleadColor.burgundy) } },
    )
}

// MARK: - Buttons

/**
 * `PrimaryButton(title, systemImage:, kind:, isLoading:)` with SwiftUI's `.disabled(...)` and any icon: the design
 * system's `PrimaryButton` has no disabled state and resolves only mapped SF Symbols.
 */
@Composable
fun ActionButton(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    kind: AWButtonKind = AWButtonKind.primary,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    loadingLabel: String? = null,
    onClick: () -> Unit,
) {
    val style = AWButtonStyle.aw(kind)
    AWButton(
        onClick = { if (!isLoading) onClick() },
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = loadingLabel?.takeIf { isLoading } ?: title },
        style = style,
        enabled = enabled,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = if (kind == AWButtonKind.primary) PleadColor.cream else PleadColor.burgundy,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics { })
            if (icon != null) Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/** A quiet full-width text action (≥ 44 dp): "Decline", "REJECT — SEE YOU IN COURT". */
@Composable
fun QuietTextButton(
    title: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = PleadType.ui(15f, FontWeight.SemiBold, TextStyleKind.subheadline),
    color: Color = PleadColor.subtleText,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .alpha(if (enabled || isLoading) 1f else 0.45f)
            .clickable(enabled = enabled && !isLoading, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = color, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(title, style = style, color = color, textAlign = TextAlign.Center)
        }
    }
}

/**
 * A `ButtonStyle` that scales the label on press (not under Reduce Motion) and optionally dims it: the settlement
 * cards' `SettlementPressStyle` (0.98) and the docket's `CourtFileButtonStyle` (0.98, opacity 0.92).
 */
@Composable
fun Modifier.pressScaleClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.98f,
    pressedAlpha: Float = 1f,
    role: Role = Role.Button,
    onClick: () -> Unit,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = accessibilityReduceMotion()
    val scale by animateFloatAsState(
        if (pressed && !reduceMotion) pressedScale else 1f,
        tween(120, easing = PleadMotion.easeOut),
        label = "pressScale",
    )
    return this
        .scale(scale)
        .alpha(if (pressed) pressedAlpha else 1f)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = role, onClick = onClick)
}

// MARK: - Haptics

/** SwiftUI `.sensoryFeedback`: the same moments, the closest Android constants. */
object Haptics {
    fun success(view: View) {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun selection(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    fun impactMedium(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    fun warning(view: View) {
        view.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS)
    }
}

/** `.sensoryFeedback(.success, trigger:)` that fires when [trigger] changes (skipping the first composition). */
@Composable
fun SuccessFeedback(trigger: Any?, fire: Boolean = true) {
    val view = LocalView.current
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        if (fire) Haptics.success(view)
    }
}

/** `.sensoryFeedback(.selection, trigger:)`. */
@Composable
fun SelectionFeedback(trigger: Any?) {
    val view = LocalView.current
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        Haptics.selection(view)
    }
}

/** A horizontal icon + text row (SwiftUI `Label`). */
@Composable
fun IconLabel(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = PleadType.metadata,
    color: Color = PleadColor.subtleText,
    iconColor: Color = color,
    maxLines: Int = Int.MAX_VALUE,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(style.fontSize.value.dp))
        Text(text, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
