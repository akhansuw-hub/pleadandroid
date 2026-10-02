// Port of ArgueWin/Courtroom/CourtStyle.swift: courtroom-local styling built on designsystem/Tokens.kt (Revision 2:
// romantic pixel-art courtroom). Gold is spent only on the scales, the verdict and an urgent deadline.
// `CourtFont` lives in designsystem/Typography.kt (wave 1); this file holds `CourtColor`, the chips, the stamp, the
// court button, `ScalesGlyph`, `goldFrame`, and the small SwiftUI bridges the courtroom files share (Dynamic Type caps,
// `.position`, `ViewThatFits`, `minimumScaleFactor`, accessibility sort priority, haptics).
package app.plead.android.courtroom

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role as SemanticsRole
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.CourtFont
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.fixedSp
import app.plead.android.models.ObjectionRuling
import app.plead.android.models.Role
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.wrapContentSize

object CourtColor {
    /** Dock / dark panels. */
    val panel = PleadColor.mahogany

    /** A hair lighter than mahogany for panel rims and inset fields. */
    val panelRim = Color(hex = 0x7A3A2E)
    val panelInset = Color(hex = 0x4A1C17)

    /** Secondary text on mahogany (≈ 8:1 on #5E251F). */
    val creamSoft = Color(hex = 0xF1D9CC)

    /** Tertiary text on mahogany (≈ 5:1). */
    val creamMuted = Color(hex = 0xD9B6A6)

    /** Scene dim for overlays (deliberation, safety, verdict). */
    val dim = PleadColor.cocoa

    /** Neutral system card (safety valve): no courtroom persona styling. */
    val neutralCard = Color(hex = 0xFAF9F7)
    val neutralText = Color(hex = 0x262322)
    val neutralSubtle = Color(hex = 0x5F5A57)
    val neutralStroke = Color(hex = 0xE3DFDB)

    /**
     * Deadline text under two hours (countdowns). Brand gold #C99558 is exactly 4.50:1 on mahogany, with no margin;
     * this lighter gold is 5.46:1 on mahogany #5E251F and 6.54:1 on the inset #4A1C17 (WCAG AA for text under 18 pt).
     */
    val deadlineGold = Color(hex = 0xD9A66A)

    /** Warm floor glow behind the party who has the floor. */
    val lampGlow = Color(hex = 0xFFD9A8)
}

// MARK: - Chips

/** Role chip: burgundy PLAINTIFF / walnut DEFENDANT, cream text. */
@Composable
fun CourtRoleChip(role: Role, modifier: Modifier = Modifier) {
    Text(
        CourtroomLogic.roleTitle(role).uppercase(),
        style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
        color = PleadColor.cream,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            // Swift `.fixedSize()`: always the chip's own width, never clipped by a narrow name tag.
            .wrapContentSize(unbounded = true)
            .clearAndSetSemantics { }
            .background(PleadColor.role(role), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

/** Phase / exhibit chip on a bubble (outline, tinted by the bubble's text colour). */
@Composable
fun CourtPhaseChip(title: String, modifier: Modifier = Modifier, tint: Color = PleadColor.walnut) {
    Text(
        title.uppercase(),
        style = CourtFont.legal.copy(letterSpacing = PleadType.capsTracking.sp),
        color = tint,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clearAndSetSemantics { }
            .border(1.dp, tint.copy(alpha = tint.alpha * 0.45f), RoundedCornerShape(5.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

// MARK: - Stamp

/**
 * Rubber stamp (OBJECTION / SUSTAINED / OVERRULED). In the scene (with `motion` and `landKey`) it slams in once per
 * launch: scale 1.3 → 1 with a small overshoot, ≈ 220 ms, and a light haptic for a ruling (amendment x); Reduce Motion
 * fades it in. Elsewhere it simply appears. On its own it reads "Sustained" / "Overruled" / "Objection".
 */
object CourtStamp {
    enum class Size { regular, small, mini }

    /** Swift `CourtStamp.ruling(_:size:landKey:motion:)`. */
    @Composable
    fun ruling(
        r: ObjectionRuling,
        modifier: Modifier = Modifier,
        size: Size = Size.regular,
        landKey: String? = null,
        motion: CourtMotionDirector? = null,
    ) {
        val angle = if (size == Size.regular) 8f else -6f
        if (r == ObjectionRuling.sustained) {
            CourtStamp(text = "SUSTAINED", color = PleadColor.burgundy, modifier = modifier, angle = angle, size = size, landKey = landKey, motion = motion)
        } else {
            CourtStamp(text = "OVERRULED", color = PleadColor.walnut, modifier = modifier, angle = angle, size = size, landKey = landKey, motion = motion)
        }
    }
}

/** Swift `String.capitalized`: every word's first letter upper-cased, the rest lower-cased. */
fun String.swiftCapitalized(): String =
    split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

@Composable
fun CourtStamp(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    angle: Float = -10f,
    /** `small` sits inside the full easel card; `mini` on the easel thumbnail. */
    size: CourtStamp.Size = CourtStamp.Size.regular,
    /** Scene only: identifies this stamp (exhibit + ruling) so it lands once, and the director that claims it. */
    landKey: String? = null,
    motion: CourtMotionDirector? = null,
) {
    val font: TextStyle = when (size) {
        CourtStamp.Size.regular -> CourtFont.stamp.cappedAt(DynamicTypeSize.large)
        CourtStamp.Size.small -> TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = fixedSp(10f))
        CourtStamp.Size.mini -> TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Black, fontSize = fixedSp(7.5f))
    }
    val line: Dp = when (size) {
        CourtStamp.Size.regular -> 2.5.dp
        CourtStamp.Size.small -> 1.8.dp
        CourtStamp.Size.mini -> 1.3.dp
    }
    val pending = landKey?.let { motion?.hasShownStamp(it) == false } ?: false
    Text(
        text,
        style = font.copy(letterSpacing = (if (size == CourtStamp.Size.regular) 1.6f else 0.8f).sp),
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = modifier
            .clearAndSetSemantics { contentDescription = text.swiftCapitalized() }
            .courtLanding(
                pending = pending,
                fromScale = CourtMotionTiming.stampFromScale,
                duration = CourtMotionTiming.stamp,
                bounce = 0.35f,
                claim = { landKey?.let { motion?.stampShown(key = it, text = text) ?: false } ?: false },
            )
            .rotate(angle)
            .background(PleadColor.paperWhite.copy(alpha = 0.82f), RoundedCornerShape(5.dp))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(5.dp))
            .padding(if (size == CourtStamp.Size.regular) 2.dp else 1.dp)
            .border(line, color, RoundedCornerShape(4.dp))
            .padding(
                horizontal = when (size) {
                    CourtStamp.Size.regular -> 8.dp
                    CourtStamp.Size.small -> 5.dp
                    CourtStamp.Size.mini -> 3.dp
                },
                vertical = if (size == CourtStamp.Size.regular) 3.dp else 1.5.dp,
            ),
    )
}

// MARK: - Buttons

/** Rounded-rectangle court button. Primary = burgundy fill, cream text (spec §10). */
data class CourtButtonStyle(
    val kind: Kind = Kind.primary,
    val fullWidth: Boolean = false,
    /** 46 by default; the dock drops to 44 (still the minimum touch target) on small phones. */
    val minHeight: Dp = 46.dp,
) {
    enum class Kind { primary, secondary, onParchment }

    val foreground: Color
        get() = when (kind) {
            Kind.primary, Kind.secondary -> PleadColor.cream
            Kind.onParchment -> PleadColor.cocoa
        }
    val background: Color
        get() = when (kind) {
            Kind.primary -> PleadColor.burgundy
            Kind.secondary, Kind.onParchment -> Color.Transparent
        }
}

/**
 * SwiftUI `Button { } .buttonStyle(CourtButtonStyle(...))`. The label's `Text`/`Icon` pick up the style's font and
 * colour through `LocalTextStyle` / `LocalContentColor`. [onClickLabel] is the accessibility hint.
 */
@Composable
fun CourtButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: CourtButtonStyle = CourtButtonStyle(),
    enabled: Boolean = true,
    onClickLabel: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val reduceMotion = accessibilityReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && !reduceMotion) 0.98f else 1f,
        animationSpec = if (reduceMotion) tween(0) else tween(120),
        label = "courtButtonScale",
    )
    val shape = RoundedCornerShape(PleadRadius.button)
    val stroke = when (style.kind) {
        CourtButtonStyle.Kind.primary -> Color.White.copy(alpha = 0.16f) to 1.dp
        CourtButtonStyle.Kind.secondary -> PleadColor.cream.copy(alpha = 0.4f) to 1.5.dp
        CourtButtonStyle.Kind.onParchment -> PleadColor.walnut.copy(alpha = 0.45f) to 1.5.dp
    }
    val font = if (style.kind == CourtButtonStyle.Kind.primary) CourtFont.button else CourtFont.buttonSecondary
    CompositionLocalProvider(LocalTextStyle provides font, LocalContentColor provides style.foreground) {
        Row(
            modifier = modifier
                .then(if (style.fullWidth) Modifier.fillMaxWidth() else Modifier)
                .defaultMinSize(minHeight = style.minHeight)
                .scale(scale)
                .alpha(if (enabled) 1f else 0.45f)
                .alpha(if (pressed) 0.85f else 1f)
                .background(style.background, shape)
                .border(stroke.second, stroke.first, shape)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClickLabel = onClickLabel,
                    role = SemanticsRole.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

// MARK: - Pixel glyphs

/** Tiny pixel-art scales of justice, drawn on a 9×7 grid. Gold by default. */
@Composable
fun ScalesGlyph(modifier: Modifier = Modifier, color: Color = PleadColor.gold, size: Dp = 14.dp) {
    Canvas(modifier.size(width = size, height = size * 7f / 9f).clearAndSetSemantics { }) {
        val cell = this.size.width / 9f
        ScalesGlyphRows.rows.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                if (ch == 'X') {
                    drawRect(color, topLeft = Offset(c * cell, r * cell), size = Size(cell + 0.1f * density, cell + 0.1f * density))
                }
            }
        }
    }
}

object ScalesGlyphRows {
    val rows = listOf(
        "....X....",
        "XXXXXXXXX",
        "X...X...X",
        "X...X...X",
        "XX..X..XX",
        "....X....",
        "..XXXXX..",
    )
}

/** Continuous-corner rectangle with a thin inner gold hairline: the verdict / nameplate frame. */
fun Modifier.goldFrame(radius: Dp = PleadRadius.card, fill: Color = PleadColor.mahogany): Modifier =
    this
        .background(fill, RoundedCornerShape(radius))
        .drawWithContent {
            drawContent()
            // `.overlay(shape.inset(by: 4).strokeBorder(gold 0.7, 1))` then `.overlay(shape.strokeBorder(black 0.25, 1))`.
            strokeBorder(radius.toPx(), inset = 4.dp.toPx(), width = 1.dp.toPx(), color = PleadColor.gold.copy(alpha = 0.7f))
            strokeBorder(radius.toPx(), inset = 0f, width = 1.dp.toPx(), color = Color.Black.copy(alpha = 0.25f))
        }

/**
 * SwiftUI `RoundedRectangle(cornerRadius: r).inset(by: inset).strokeBorder(color, lineWidth: width)`: a stroke drawn
 * inside the (inset) rounded rect.
 */
fun DrawScope.strokeBorder(radius: Float, inset: Float, width: Float, color: Color) {
    val half = width / 2f
    val o = inset + half
    if (size.width - 2 * o <= 0f || size.height - 2 * o <= 0f) return
    drawRoundRect(
        color = color,
        topLeft = Offset(o, o),
        size = Size(size.width - 2 * o, size.height - 2 * o),
        cornerRadius = CornerRadius((radius - o).coerceAtLeast(0f)),
        style = Stroke(width),
    )
}

// MARK: - SwiftUI bridges shared by the courtroom files

/**
 * SwiftUI `DynamicTypeSize`, as the Android font scale that matches it (iOS body point size / 17): the courtroom caps
 * regions at these sizes (`.dynamicTypeSize(...DynamicTypeSize.xLarge)`).
 */
enum class DynamicTypeSize(val scale: Float) {
    xSmall(14f / 17f), small(15f / 17f), medium(16f / 17f), large(1f), xLarge(19f / 17f), xxLarge(21f / 17f),
    xxxLarge(23f / 17f), accessibility1(28f / 17f), accessibility2(33f / 17f), accessibility3(40f / 17f),
    accessibility4(47f / 17f), accessibility5(53f / 17f);

    val isAccessibilitySize: Boolean get() = this >= accessibility1

    companion object {
        /** The size whose scale is nearest to (at most) [fontScale]. */
        fun of(fontScale: Float): DynamicTypeSize = entries.lastOrNull { it.scale <= fontScale + 0.01f } ?: xSmall
    }
}

/** SwiftUI `@Environment(\.dynamicTypeSize)`. */
@Composable
@ReadOnlyComposable
fun dynamicTypeSize(): DynamicTypeSize = DynamicTypeSize.of(LocalDensity.current.fontScale)

/** SwiftUI `.dynamicTypeSize(...max)` on a subtree: the font scale inside never exceeds [max]. */
@Composable
fun DynamicTypeCap(max: DynamicTypeSize, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= max.scale) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, max.scale), content = content)
    }
}

/** A text style whose size never follows the font scale past [max] (a one-`Text` `.dynamicTypeSize(...max)`). */
@Composable
@ReadOnlyComposable
fun TextStyle.cappedAt(max: DynamicTypeSize): TextStyle {
    val fs = LocalDensity.current.fontScale
    if (fs <= max.scale || fontSize == TextUnit.Unspecified) return this
    return copy(fontSize = (fontSize.value * max.scale / fs).sp)
}

/**
 * SwiftUI `.position(x:y:)` inside a full-size container: this child fills the container and places its content
 * (measured at its own size, up to the container) centred on (x, y) in dp.
 */
fun Modifier.position(x: Dp, y: Dp): Modifier = layout { measurable, constraints ->
    val p = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
    val w = if (constraints.hasBoundedWidth) constraints.maxWidth else p.width
    val h = if (constraints.hasBoundedHeight) constraints.maxHeight else p.height
    layout(w, h) { p.place((x.toPx() - p.width / 2f).roundToInt(), (y.toPx() - p.height / 2f).roundToInt()) }
}

fun Modifier.position(x: Float, y: Float): Modifier = position(x.dp, y.dp)

/** SwiftUI `.frame(width:height:)` of a rect then `.position` at its centre: the child laid out exactly in [rect] (dp). */
fun Modifier.frameIn(rect: androidx.compose.ui.geometry.Rect): Modifier = layout { measurable, constraints ->
    val w = rect.width.dp.roundToPx().coerceAtLeast(0)
    val h = rect.height.dp.roundToPx().coerceAtLeast(0)
    val p = measurable.measure(Constraints.fixed(w, h))
    val cw = if (constraints.hasBoundedWidth) constraints.maxWidth else w
    val ch = if (constraints.hasBoundedHeight) constraints.maxHeight else h
    layout(cw, ch) { p.place(rect.left.dp.roundToPx(), rect.top.dp.roundToPx()) }
}

/**
 * SwiftUI `ViewThatFits(in: .horizontal)`: the first candidate whose natural width fits the offered width (the last
 * one otherwise).
 */
@Composable
fun ViewThatFits(modifier: Modifier = Modifier, candidates: List<@Composable () -> Unit>) {
    SubcomposeLayout(modifier) { constraints ->
        var chosen = candidates.lastIndex
        for (i in candidates.indices) {
            val m = subcompose("probe$i", candidates[i]).map { it.measure(Constraints(maxHeight = constraints.maxHeight)) }
            val w = m.maxOfOrNull { it.width } ?: 0
            if (!constraints.hasBoundedWidth || w <= constraints.maxWidth) {
                chosen = i
                break
            }
        }
        val placeables = subcompose("chosen", candidates[chosen]).map { it.measure(constraints.copy(minHeight = 0)) }
        val w = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val h = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(w, h) { placeables.forEach { it.place(0, 0) } }
    }
}

/**
 * `Text` with SwiftUI's `.minimumScaleFactor(_:)`: the font shrinks (down to `minimumScaleFactor` of its size) to fit
 * `maxLines` in the offered width before it truncates.
 */
@Composable
fun ScaledText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minimumScaleFactor: Float = 1f,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    val resolved = if (textAlign != null) style.copy(color = color, textAlign = textAlign) else style.copy(color = color)
    if (minimumScaleFactor >= 1f || style.fontSize == TextUnit.Unspecified) {
        BasicText(text, modifier, style = resolved, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    } else {
        val max = style.fontSize
        val min = (max.value * minimumScaleFactor).sp
        BasicText(
            text, modifier, style = resolved, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            autoSize = TextAutoSize.StepBased(minFontSize = min, maxFontSize = max, stepSize = 0.25.sp),
        )
    }
}

/**
 * A word on a fixed-size sign or plaque (the easel's CLOSED card): always one line, shrinking to fit the space it
 * is given (SwiftUI `.lineLimit(1).minimumScaleFactor`), so it never breaks mid-word at a narrow width or a large
 * system font size. Letter spacing in `em` shrinks with it. Give it bounded width / height (the sign's inside).
 */
@Composable
fun CourtSignText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    minimumScaleFactor: Float = CourtSignText.minimumScaleFactor,
    onTextLayout: (androidx.compose.ui.text.TextLayoutResult) -> Unit = {},
) {
    val max = style.fontSize
    BasicText(
        text,
        modifier,
        onTextLayout = onTextLayout,
        style = style.copy(color = color, textAlign = TextAlign.Center),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        autoSize = TextAutoSize.StepBased(minFontSize = (max.value * minimumScaleFactor).sp, maxFontSize = max, stepSize = 0.25.sp),
    )
}

object CourtSignText {
    /** Low enough for a 21 sp word on the 360 dp easel at a 200% system font size. */
    const val minimumScaleFactor: Float = 0.25f
}

/**
 * SwiftUI `.accessibilitySortPriority(p)`: TalkBack visits higher priorities first (Compose traversal index is
 * ascending, so it is negated).
 */
fun Modifier.accessibilitySortPriority(priority: Float): Modifier = semantics { traversalIndex = -priority }

/** `.accessibilityHidden(true)`. */
fun Modifier.accessibilityHidden(): Modifier = clearAndSetSemantics { }

/** `.accessibilityElement(children: .ignore).accessibilityLabel(label)`. */
fun Modifier.accessibilityElement(label: String): Modifier = clearAndSetSemantics { contentDescription = label }

/** `.accessibilityAddTraits(.isButton)` on a non-clickable element. */
fun Modifier.accessibilityButton(): Modifier = semantics { role = SemanticsRole.Button }

/**
 * `UIImpactFeedbackGenerator(style: .light).impactOccurred()` (PORT.md §2 haptics): the courtroom's ruling stamps and
 * the judgement delivery moment.
 */
object CourtHaptics {
    fun light(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

// MARK: - Full-screen geometry and sheets (shared by the scene, the empty state and the verdict sequence)

/**
 * SwiftUI `EdgeInsets` of the full-screen court (dp): `top` = status bar, `bottom` = tab bar + navigation bar (the
 * scene draws under both, as iOS draws under the status bar, the Dynamic Island and the translucent tab bar).
 * Every full-screen courtroom composable is laid out in the FULL size and told these insets, mirroring iOS
 * `GeometryReader` + `safeAreaInsets` (`full = size + insets`).
 */
data class CourtInsets(val top: Float = 0f, val bottom: Float = 0f, val leading: Float = 0f, val trailing: Float = 0f) {
    companion object {
        val zero = CourtInsets()
    }
}

/**
 * SwiftUI `.sheet { NavigationStack { … } }` / `.presentationDetents([.medium, .large])`: a Material bottom sheet on
 * [containerColor]. [largeOnly] = `.large` only (opens fully expanded).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourtSheet(
    onDismiss: () -> Unit,
    containerColor: Color = PleadColor.cream,
    largeOnly: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = largeOnly)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = containerColor,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        content = content,
    )
}

/**
 * The inline navigation bar of an iOS sheet (`.navigationTitle(...)` + `.navigationBarTitleDisplayMode(.inline)` and a
 * Cancel / Done toolbar item): centred semibold title, text buttons tinted burgundy (cream on the dark transcript bar).
 */
@Composable
fun CourtSheetTopBar(
    title: String,
    modifier: Modifier = Modifier,
    trailingTitle: String? = null,
    onTrailing: (() -> Unit)? = null,
    leadingTitle: String? = null,
    onLeading: (() -> Unit)? = null,
    background: Color = Color.Transparent,
    tint: Color = PleadColor.burgundy,
    titleColor: Color = PleadColor.cocoa,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(background)
            .defaultMinSize(minHeight = 52.dp)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
            color = titleColor,
            maxLines = 1,
            modifier = Modifier.semantics { heading() },
        )
        if (leadingTitle != null && onLeading != null) {
            TextButton(onClick = onLeading, modifier = Modifier.align(Alignment.CenterStart)) {
                Text(leadingTitle, color = tint, style = TextStyle(fontFamily = FontFamily.Default, fontSize = 17.sp))
            }
        }
        if (trailingTitle != null && onTrailing != null) {
            TextButton(onClick = onTrailing, modifier = Modifier.align(Alignment.CenterEnd)) {
                Text(trailingTitle, color = tint, style = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 17.sp))
            }
        }
    }
}
