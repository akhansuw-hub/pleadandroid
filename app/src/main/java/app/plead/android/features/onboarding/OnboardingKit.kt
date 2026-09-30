// Port of ArgueWin/Features/Onboarding/OnboardingKit.swift: the onboarding redesign 2.0 shared kit (CONTRACTS-v2
// amendment ak). INTERFACE IS FIXED: add members freely; never rename or remove.
//
// Everything a redesigned screen needs to look and move like the rest of the flow:
//   OnboardingKitTokens  spacing · radii · sizes · motion (the only numbers screens should need)
//   courtLayer(_:)       the layered entrance (hero → headline → copy → content → CTA), Reduce Motion = fades
//   OnboardingShell      hero / content / CTA pinned above the safe area; opt-in auto-layering of its children
//   CourtEyebrow · CourtHeadline · CourtSupportingText   the three text voices
//   CourtPrimaryButton · CourtSecondaryButton            the two actions
//   CourtSeal · CaseDocumentCard · CaseStepCard · CourtProgressRail · CourtKitIcon · CourtNumberBadge
//
// Compose mapping: `OnboardingShell`'s content is a `LayerListScope` (each `item {}` / element of `items` is one
// child, as SwiftUI's `Group(subviews:)` counts them); the `courtLayerIndex` / `courtOnDark` environment values are
// `LocalCourtLayerIndex` / `LocalCourtOnDark`.
package app.plead.android.features.onboarding

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.designsystem.PixelGrid
import app.plead.android.designsystem.PleadBrandColor
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadPixelArt
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.ScallopShape
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.fixedSp
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.designsystem.pleadShadow

// MARK: - Tokens

/** Centralised onboarding tokens (amendment ak: "no per-page magic numbers"). Colours live in `OnboardingPalette`. */
object OnboardingKitTokens {
    object Spacing {
        /** Screen side margin. */
        val gutter: Dp = 24.dp
        /** Below the top bar, before the hero. */
        val top: Dp = 8.dp
        /** Between the shell's blocks (hero, headline, copy, content). */
        val section: Dp = 18.dp
        /** Between stacked cards. */
        val stack: Dp = 12.dp
        /** Headline → supporting copy. */
        val tight: Dp = 8.dp
        /** Inside cards. */
        val cardPadding: Dp = 16.dp
        val cardInner: Dp = 12.dp
        /** Pinned CTA block. */
        val ctaTop: Dp = 12.dp
        val ctaBottom: Dp = 8.dp
        val ctaStack: Dp = 4.dp
        /** How far stacked folders overlap (How Plead Works). */
        val folderOverlap: Dp = 6.dp
        /** Readable column on wide screens. */
        val maxWidth: Dp = 560.dp
    }

    object Radius {
        val button: Dp = 16.dp
        val card: Dp = 18.dp
        val document: Dp = 14.dp
        val folder: Dp = 16.dp
        val tab: Dp = 7.dp
        val badge: Dp = 9.dp
        val scene: Dp = 24.dp
    }

    object Size {
        /** Primary CTA height (brief: ≥ 54 pt). */
        val buttonHeight: Dp = 56.dp
        val secondaryHeight: Dp = 44.dp
        val seal: Dp = 44.dp
        val documentSeal: Dp = 20.dp
        val badgeWidth: Dp = 34.dp
        val badgeHeight: Dp = 38.dp
        val icon: Dp = 22.dp
        val iconTile: Dp = 42.dp
        val folderTabWidth: Dp = 64.dp
        val folderTabHeight: Dp = 14.dp
        val railDot: Dp = 7.dp
        val railActiveDot: Dp = 10.dp
        val railLine: Dp = 1.5.dp
        val railHeight: Dp = 14.dp
        val hairline: Dp = 1.dp
        /** Welcome's compact logo block (brief: smaller than the old lockup, the hero line leads). */
        val welcomeLogo: Dp = 136.dp
        /** Court Is Ready: the courtroom window as a fraction of the painted art's height (bench → podiums). */
        const val readySceneTop: Float = 0.215f
        const val readySceneBottom: Float = 0.685f
        /** How far the session card overlaps the bottom of the courtroom. */
        val readyCardOverlap: Dp = 28.dp
    }

    object Motion {
        /** Upward travel of a rising layer (amendment ak: 6–12 pt). */
        const val rise: Float = 10f
        const val smallRise: Float = 6f
        const val springRise: Float = 12f
        /** Between layers (amendment ak: 100–150 ms). */
        const val stagger: Double = 0.12
        /** Standard layer duration (amendment ak: 300–450 ms). */
        const val duration: Double = 0.38
        const val shortDuration: Double = 0.30
        const val longDuration: Double = 0.45
        /** "Soft spring" for cards and folders. */
        const val springBounce: Double = 0.16
        const val cardStartScale: Float = 0.98f
        /** Largest tilt a card may carry (brief: "extremely small for accessibility"). */
        const val maxTilt: Double = 1.5
        /** The one heart pulse on Welcome. */
        const val heartPulseScale: Double = 1.22
        const val heartPulseUp: Double = 0.16
        const val heartPulseDown: Double = 0.34
        /** Reduce Motion crossfade. */
        const val fade: Double = 0.30
        /** Rail dot fill when the step changes. */
        const val rail: Double = 0.34

        /** Delay of entrance layer `layer` (0 = hero). */
        fun delay(layer: Int): Double = stagger * maxOf(layer, 0)

        /** The resolved entrance of layer `layer` in `style` (pure; unit-tested). Reduce Motion = opacity only. */
        fun parameters(layer: Int, style: CourtLayerStyle = CourtLayerStyle.rise, reduceMotion: Boolean, extraDelay: Double = 0.0): PleadRevealParameters {
            val p = PleadRevealParameters(
                delay = delay(layer) + extraDelay,
                duration = if (style == CourtLayerStyle.spring) longDuration else duration,
                offset = if (style == CourtLayerStyle.fade) Offset.Zero else Offset(0f, if (style == CourtLayerStyle.spring) springRise else rise),
                startScale = if (style == CourtLayerStyle.spring) cardStartScale else 1f,
                springy = style == CourtLayerStyle.spring,
            )
            if (reduceMotion) {
                p.offset = Offset.Zero
                p.startScale = 1f
                p.springy = false
            }
            return p
        }
    }
}

// MARK: - Layered entrance

/**
 * How a layer enters: a short upward fade (text), a soft spring rise (cards), or a plain fade (calm screens).
 * Reduce Motion turns every style into a fade with the same timing.
 */
enum class CourtLayerStyle { rise, spring, fade }

/**
 * The entrance layer a view sits in (set by `courtLayer`), so a nested accent (a seal, a status light) can
 * land just after its card: `OnboardingKitTokens.Motion.delay(LocalCourtLayerIndex.current + 1)`.
 */
val LocalCourtLayerIndex = compositionLocalOf { 0 }

/** Inside a dark chamber (`OnboardingShell(dark = true)`): kit text and controls switch to on-dark colours. */
val LocalCourtOnDark = compositionLocalOf { false }

/**
 * One-shot entrance for layer `index` (delay = index × stagger + `delay`). Fires once per screen entry
 * inside the onboarding container (`LocalPleadRevealID`); never blocks taps (starts at opacity 0.001).
 * Wrap the content in [CourtLayer] instead when descendants read `LocalCourtLayerIndex`.
 */
fun Modifier.courtLayer(index: Int, style: CourtLayerStyle = CourtLayerStyle.rise, delay: Double = 0.0): Modifier =
    revealLayer(
        { OnboardingKitTokens.Motion.parameters(index, style, accessibilityReduceMotion(), extraDelay = delay) },
        bounce = OnboardingKitTokens.Motion.springBounce,
    )

/** `.courtLayer(_:)` that also provides `LocalCourtLayerIndex` to its content (SwiftUI's environment write). */
@Composable
fun CourtLayer(index: Int, modifier: Modifier = Modifier, style: CourtLayerStyle = CourtLayerStyle.rise, delay: Double = 0.0, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalCourtLayerIndex provides index) {
        Box(modifier.courtLayer(index, style, delay), propagateMinConstraints = false) { content() }
    }
}

/** A stack whose children enter one layer apart, starting at `firstLayer` (an `items` list counts one per element). */
@Composable
fun CourtLayerStack(
    modifier: Modifier = Modifier,
    firstLayer: Int = 0,
    spacing: Dp = OnboardingKitTokens.Spacing.stack,
    style: CourtLayerStyle = CourtLayerStyle.spring,
    content: LayerListScope.() -> Unit,
) {
    val children = LayerListScope.build(content)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing), horizontalAlignment = Alignment.CenterHorizontally) {
        children.forEachIndexed { i, child -> CourtLayer(firstLayer + i, style = style) { child() } }
    }
}

// MARK: - Shell

/**
 * Hero on top, content below, CTA pinned to the bottom (above the navigation bar and the keyboard).
 *
 * Layered entrance (amendment ak): with `autoLayers = true` the hero is layer 0, each top-level content child the
 * next layer (`items` counts one per element, so cards stagger individually; `cardStyle` children spring),
 * and the CTA block comes last. Off by default so screens with their own choreography don't animate twice; they
 * can still place the CTA with `ctaLayer` / `ctaDelay`. Without a hero, content children still start at layer 1.
 */
@Composable
fun OnboardingShell(
    modifier: Modifier = Modifier,
    /** Dark burgundy chamber instead of cream. */
    dark: Boolean = false,
    /** Vertically centre short content in the space above the CTA. */
    centered: Boolean = false,
    /** Stagger hero → content children → CTA automatically. */
    autoLayers: Boolean = false,
    /** Entrance style of content children when `autoLayers` (text rises; pass `.spring` for card-only content). */
    contentStyle: CourtLayerStyle = CourtLayerStyle.rise,
    /** Spacing between content children. */
    spacing: Dp = OnboardingKitTokens.Spacing.section,
    /** CTA entrance layer; `null` = after the last content child (only when `autoLayers`). */
    ctaLayer: Int? = null,
    /** Absolute CTA delay, for screens with their own timeline (animates the CTA even without `autoLayers`). */
    ctaDelay: Double? = null,
    hero: (@Composable () -> Unit)? = null,
    content: LayerListScope.() -> Unit,
    cta: @Composable ColumnScope.() -> Unit,
) {
    val S = OnboardingKitTokens.Spacing
    val children = LayerListScope.build(content)
    CompositionLocalProvider(LocalCourtOnDark provides dark) {
        Box(modifier.fillMaxSize()) {
            OnboardingShellBackground(dark = dark, modifier = Modifier.matchParentSize())
            Column(Modifier.fillMaxSize().imePadding()) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val minHeight = maxHeight
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                        Column(
                            Modifier
                                .widthIn(max = S.maxWidth)
                                .fillMaxWidth()
                                .heightIn(min = minHeight)
                                .padding(horizontal = S.gutter)
                                .padding(top = S.top, bottom = S.section),
                            verticalArrangement = if (centered) Arrangement.spacedBy(spacing, Alignment.CenterVertically) else Arrangement.spacedBy(spacing),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            if (hero != null) Layered(autoLayers, 0, CourtLayerStyle.rise, hero)
                            children.forEachIndexed { i, child -> Layered(autoLayers, i + 1, contentStyle, child) }
                        }
                    }
                }
                val layer = OnboardingShellLayout.ctaEntranceLayer(autoLayers, ctaLayer, ctaDelay, children.size)
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val block = Modifier
                        .widthIn(max = S.maxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = S.gutter)
                        .padding(top = S.ctaTop, bottom = S.ctaBottom)
                        .navigationBarsPadding()
                    Column(
                        if (layer != null) block.courtLayer(layer, CourtLayerStyle.rise, delay = ctaDelay ?: 0.0) else block,
                        verticalArrangement = Arrangement.spacedBy(S.ctaStack),
                        content = cta,
                    )
                }
            }
        }
    }
}

@Composable
private fun Layered(autoLayers: Boolean, layer: Int, style: CourtLayerStyle, content: @Composable () -> Unit) {
    if (autoLayers) CourtLayer(layer, Modifier.fillMaxWidth(), style = style) { content() }
    else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) { content() }
}

/** `OnboardingShell`'s static rules (Swift statics on the generic struct). */
object OnboardingShellLayout {
    /** The CTA's entrance layer for `contentCount` content children, or null when the CTA doesn't animate. */
    fun ctaEntranceLayer(autoLayers: Boolean, ctaLayer: Int?, ctaDelay: Double?, contentCount: Int): Int? {
        if (ctaDelay != null) return 0
        if (ctaLayer != null) return ctaLayer
        return if (autoLayers) contentCount + 1 else null
    }
}

/** Cream page, or the dark burgundy chamber with a soft warm light from above. */
@Composable
fun OnboardingShellBackground(modifier: Modifier = Modifier, dark: Boolean = false) {
    if (dark) {
        Canvas(modifier.fillMaxSize()) {
            drawRect(OnboardingPalette.wine)
            drawRect(
                Brush.radialGradient(
                    colors = listOf(OnboardingPalette.burgundy.copy(alpha = 0.55f), Color.Transparent),
                    center = Offset(size.width / 2, 0f),
                    radius = 520.dp.toPx(),
                ),
            )
        }
    } else {
        Box(modifier.fillMaxSize().background(OnboardingPalette.cream))
    }
}

// MARK: - Text

/** Uppercase tracked court-state label ("HOW PLEAD WORKS"). Burgundy on cream, light gold on dark. */
@Composable
fun CourtEyebrow(text: String, modifier: Modifier = Modifier, color: Color? = null) {
    val onDark = LocalCourtOnDark.current
    Text(
        text.uppercase(),
        style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
        color = color ?: if (onDark) OnboardingPalette.goldLight else OnboardingPalette.burgundy,
        textAlign = TextAlign.Center,
        modifier = modifier.semantics { heading() },
    )
}

/** Editorial Fraunces headline. `\n` breaks lines visually; TalkBack reads it as one sentence. */
object CourtHeadline {
    enum class Size { display, title }

    fun font(size: Size): TextStyle = when (size) {
        Size.display -> PleadType.display(34f, weight = FontWeight.SemiBold, relativeTo = TextStyleKind.largeTitle)
        Size.title -> PleadType.display(28f, weight = FontWeight.SemiBold, relativeTo = TextStyleKind.title)
    }

    fun spoken(text: String): String = text.replace("\n", " ")
}

@Composable
fun CourtHeadline(
    text: String,
    modifier: Modifier = Modifier,
    size: CourtHeadline.Size = CourtHeadline.Size.title,
    identifier: String = "onboarding.title",
) {
    val onDark = LocalCourtOnDark.current
    Text(
        text,
        style = CourtHeadline.font(size),
        color = if (onDark) OnboardingPalette.cream else OnboardingPalette.wine,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = CourtHeadline.spoken(text)
                heading()
            }
            .testTag(identifier),
    )
}

/** Supporting line under a headline (system sans, font scale, wraps). */
@Composable
fun CourtSupportingText(text: String, modifier: Modifier = Modifier) {
    val onDark = LocalCourtOnDark.current
    Text(
        text,
        style = PleadType.body.copy(lineHeight = (PleadType.body.fontSize.value + 2 + 4).sp),
        color = if (onDark) OnboardingPalette.cream.copy(alpha = 0.82f) else OnboardingPalette.secondaryText,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

// MARK: - Buttons

/**
 * Primary court CTA ("ENTER THE COURT →"): deep burgundy, cream uppercase `PleadType.uiButton`, 56 pt tall,
 * optional trailing arrow (a "→" in the title is drawn as the arrow), press scale. TalkBack reads the title in
 * sentence case without the arrow; `identifier` defaults to `onboarding.primary`.
 */
object CourtPrimaryButton {
    const val defaultIdentifier = "onboarding.primary"

    fun resolvedIdentifier(identifier: String?): String = identifier ?: defaultIdentifier

    /** The visible title without a typed arrow. */
    fun visibleTitle(title: String): String = title.replace("→", "").trim(' ')

    /** "ENTER THE COURT →" → "Enter the court". */
    fun spokenTitle(title: String): String {
        val t = visibleTitle(title).lowercase()
        return t.take(1).uppercase() + t.drop(1)
    }
}

@Composable
fun CourtPrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    identifier: String? = null,
    arrow: Boolean = true,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    CourtPrimaryButtonStyle(
        enabled = enabled && !isLoading,
        onClick = action,
        modifier = modifier
            .semantics { contentDescription = CourtPrimaryButton.spokenTitle(title) }
            .testTag(CourtPrimaryButton.resolvedIdentifier(identifier)),
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = OnboardingPalette.cream, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(CourtPrimaryButton.visibleTitle(title).uppercase(), modifier = Modifier.clearAndSetSemantics { })
            if (arrow || title.contains("→")) {
                Icon(SFSymbol.icon("arrow.right"), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** Swift `CourtPrimaryButtonStyle`: the filled label with press, disabled and shadow states. */
@Composable
fun CourtPrimaryButtonStyle(enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val onDark = LocalCourtOnDark.current
    val shape = RoundedCornerShape(OnboardingKitTokens.Radius.button)
    val fill = if (onDark) OnboardingPalette.burgundy else OnboardingPalette.wine
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .pleadPress(pressed)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .pleadShadow(if (enabled) OnboardingPalette.wine.copy(alpha = 0.22f) else Color.Transparent, radius = 10.dp, y = 5.dp, shape = shape)
            .fillMaxWidth()
            .defaultMinSize(minHeight = OnboardingKitTokens.Size.buttonHeight)
            .clip(shape)
            .background(fill, shape)
            .border(
                OnboardingKitTokens.Size.hairline,
                if (onDark) OnboardingPalette.goldLight.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.06f),
                shape,
            )
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = OnboardingKitTokens.Spacing.cardPadding),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides OnboardingPalette.cream,
            LocalTextStyle provides PleadType.uiButton.copy(color = OnboardingPalette.cream, letterSpacing = 0.6.sp, textAlign = TextAlign.Center),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(OnboardingKitTokens.Spacing.tight), verticalAlignment = Alignment.CenterVertically) {
                content()
            }
        }
    }
}

/** Quiet secondary action under the primary ("EXPLORE PLEAD", "I'LL DO THIS LATER"): text only, 44 pt target. */
@Composable
fun CourtSecondaryButton(
    title: String,
    modifier: Modifier = Modifier,
    identifier: String = "onboarding.secondary",
    isLoading: Boolean = false,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val onDark = LocalCourtOnDark.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .pleadPress(pressed)
            .fillMaxWidth()
            .defaultMinSize(minHeight = OnboardingKitTokens.Size.secondaryHeight)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled && !isLoading, onClick = action)
            .semantics {
                role = Role.Button
                contentDescription = CourtPrimaryButton.spokenTitle(title)
            }
            .testTag(identifier),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = OnboardingPalette.burgundy, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(
                title.uppercase(),
                style = PleadType.ui(15f, FontWeight.Bold, relativeTo = TextStyleKind.subheadline).copy(letterSpacing = 0.8.sp),
                color = if (onDark) OnboardingPalette.cream else OnboardingPalette.burgundy,
                textAlign = TextAlign.Center,
                modifier = Modifier.clearAndSetSemantics { },
            )
        }
    }
}

// MARK: - Icons

/** A kit icon: an SF Symbol (mapped to Material by `SFSymbol`) or one of the brand's pixel glyphs. */
sealed class CourtKitIcon {
    data class system(val name: String) : CourtKitIcon()
    data object scales : CourtKitIcon()
    data object heart : CourtKitIcon()
}

@Composable
fun CourtKitIconView(
    icon: CourtKitIcon,
    modifier: Modifier = Modifier,
    color: Color = OnboardingPalette.burgundy,
    size: Dp = OnboardingKitTokens.Size.icon,
) {
    Box(modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        when (icon) {
            is CourtKitIcon.system -> Icon(SFSymbol.icon(icon.name), contentDescription = null, tint = color, modifier = Modifier.size(size * 0.82f))
            CourtKitIcon.scales -> PixelGrid(
                rows = PleadPixelArt.scales,
                colors = mapOf('g' to color, 'l' to color.copy(alpha = color.alpha * 0.7f), 's' to color.copy(alpha = color.alpha * 0.55f)),
                modifier = Modifier.fillMaxSize(),
            )
            CourtKitIcon.heart -> PixelGrid(
                rows = PleadPixelArt.heart,
                colors = mapOf('c' to PleadBrandColor.coral, 'h' to OnboardingPalette.paper, 'd' to OnboardingPalette.burgundy),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

// MARK: - Seal

/** Small wax court seal (burgundy, scalloped, gold ring, pixel scales) used on documents and the finale. */
@Composable
fun CourtSeal(
    modifier: Modifier = Modifier,
    size: Dp = OnboardingKitTokens.Size.seal,
    tint: Color = OnboardingPalette.burgundy,
    icon: CourtKitIcon = CourtKitIcon.scales,
) {
    val scallop = remember { ScallopShape(bumps = 14) }
    Box(
        modifier
            .size(size)
            .pleadShadow(OnboardingPalette.wine.copy(alpha = 0.18f), radius = size * 0.06f, y = size * 0.03f, shape = scallop)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize().clip(scallop).background(
                Brush.radialGradient(listOf(tint.copy(alpha = 0.85f), tint, OnboardingPalette.wine), center = Offset(0f, 0f), radius = with(androidx.compose.ui.platform.LocalDensity.current) { size.toPx() }),
            ),
        )
        Box(
            Modifier.fillMaxSize().padding(size * 0.16f)
                .border(maxOf(0.75.dp, size * 0.03f), OnboardingPalette.gold.copy(alpha = 0.85f), CircleShape),
        )
        CourtKitIconView(icon, color = OnboardingPalette.goldLight, size = size * 0.42f)
    }
}

// MARK: - Documents

/**
 * Miniature case document (Welcome's three steps): paper, fine warm border, a NO. badge, optional icon, title,
 * subtitle, a folded corner and a tiny seal, slight shadow. One TalkBack element.
 */
@Composable
fun CaseDocumentCard(
    number: Int,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: CourtKitIcon? = null,
    iconColor: Color = OnboardingPalette.burgundy,
    showsSeal: Boolean = true,
) {
    val S = OnboardingKitTokens.Spacing
    val Z = OnboardingKitTokens.Size
    val shape = RoundedCornerShape(OnboardingKitTokens.Radius.document)
    Box(
        modifier
            .fillMaxWidth()
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.07f), radius = 8.dp, y = 3.dp, shape = shape)
            .clip(shape)
            .background(OnboardingPalette.paper, shape)
            .border(Z.hairline, OnboardingPalette.border, shape)
            .clearAndSetSemantics { contentDescription = "Step $number. ${title.swiftCapitalized()}. $subtitle" },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = S.cardPadding, vertical = S.cardInner + 2.dp),
            horizontalArrangement = Arrangement.spacedBy(S.cardInner),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CourtNumberBadge(number)
            if (icon != null) CourtKitIconView(icon, color = iconColor)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title.uppercase(),
                    style = PleadType.ui(15f, FontWeight.ExtraBold, relativeTo = TextStyleKind.subheadline).copy(letterSpacing = 0.4.sp),
                    color = OnboardingPalette.wine,
                )
                Text(subtitle, style = PleadType.metadata, color = OnboardingPalette.secondaryText)
            }
            if (showsSeal) CourtSeal(Modifier.graphicsLayer { alpha = 0.9f }, size = Z.documentSeal)
        }
        FoldedCorner(Modifier.align(Alignment.TopEnd))
    }
}

/** "NO. / 01" docket badge. */
@Composable
fun CourtNumberBadge(number: Int, modifier: Modifier = Modifier, fill: Color = OnboardingPalette.burgundy) {
    Column(
        modifier
            .size(OnboardingKitTokens.Size.badgeWidth, OnboardingKitTokens.Size.badgeHeight)
            .background(fill, RoundedCornerShape(OnboardingKitTokens.Radius.badge))
            .clearAndSetSemantics { },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // `.dynamicTypeSize(...DynamicTypeSize.large)`: the badge does not grow past the default size.
        Text(
            "NO.",
            style = TextStyle(fontSize = fixedSp(7f), fontWeight = FontWeight.ExtraBold, letterSpacing = 0.6.sp),
            color = OnboardingPalette.cream.copy(alpha = 0.8f),
        )
        Text(
            "%02d".format(number),
            style = TextStyle(fontSize = fixedSp(15f), fontWeight = FontWeight.ExtraBold).monospacedDigit(),
            color = OnboardingPalette.cream,
        )
    }
}

/** A small dog-eared corner for paper cards. */
@Composable
private fun FoldedCorner(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        val s = this.size.width
        drawPath(Path().apply { moveTo(0f, 0f); lineTo(s, s); lineTo(0f, s); close() }, OnboardingPalette.parchment)
        drawPath(Path().apply { moveTo(0f, 0f); lineTo(s, 0f); lineTo(s, s); close() }, OnboardingPalette.cream)
    }
}

// MARK: - Folders

/**
 * Stacked case folder step (How Plead Works): a paper folder with a tab carrying "NO. 01" in the accent colour,
 * an accent icon tile, title and description. `tilt` is clamped to ±`maxTilt` (1.5°) and dropped under Reduce
 * Motion. One TalkBack element.
 */
object CaseStepCard {
    fun clampedTilt(t: Double): Double = t.coerceIn(-OnboardingKitTokens.Motion.maxTilt, OnboardingKitTokens.Motion.maxTilt)
}

@Composable
fun CaseStepCard(
    number: Int,
    systemImage: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    accent: Color = PleadColor.burgundy,
    tilt: Double = 0.0,
    /** Overrides `systemImage` with a brand glyph (e.g. `.scales`). */
    icon: CourtKitIcon? = null,
) {
    val S = OnboardingKitTokens.Spacing
    val Z = OnboardingKitTokens.Size
    val reduceMotion = accessibilityReduceMotion()
    val shape = remember { FolderShape(Z.folderTabWidth, Z.folderTabHeight, OnboardingKitTokens.Radius.folder, OnboardingKitTokens.Radius.tab) }
    Box(
        modifier
            .fillMaxWidth()
            .rotate(if (reduceMotion) 0f else CaseStepCard.clampedTilt(tilt).toFloat())
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.09f), radius = 10.dp, y = 4.dp, shape = shape)
            .background(OnboardingPalette.paper, shape)
            .border(Z.hairline, OnboardingPalette.border, shape)
            .clearAndSetSemantics { contentDescription = "Step $number. ${title.swiftCapitalized()}. $description" },
    ) {
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = S.cardPadding)
                .padding(top = Z.folderTabHeight + S.cardInner + 2.dp, bottom = S.cardPadding),
            horizontalArrangement = Arrangement.spacedBy(S.cardInner + 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                Modifier.size(Z.iconTile).background(accent, RoundedCornerShape(OnboardingKitTokens.Radius.badge)),
                contentAlignment = Alignment.Center,
            ) {
                CourtKitIconView(icon ?: CourtKitIcon.system(systemImage), color = OnboardingPalette.cream, size = Z.icon)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    title.uppercase(),
                    style = PleadType.ui(16f, FontWeight.ExtraBold, relativeTo = TextStyleKind.headline).copy(letterSpacing = 0.4.sp),
                    color = OnboardingPalette.wine,
                )
                Text(
                    description,
                    style = PleadType.text(15f, FontWeight.Normal, relativeTo = TextStyleKind.subheadline),
                    color = OnboardingPalette.secondaryText,
                )
            }
        }
        // The folder's tab label.
        Box(
            Modifier.padding(start = FolderShape.tabInset(OnboardingKitTokens.Radius.folder)).size(Z.folderTabWidth, Z.folderTabHeight),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "NO. ${"%02d".format(number)}",
                style = TextStyle(fontSize = fixedSp(9f), fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp),
                color = accent,
            )
        }
        // The folder's accent edge along the top of its body.
        Box(
            Modifier.fillMaxWidth()
                .padding(top = Z.folderTabHeight)
                .padding(horizontal = OnboardingKitTokens.Radius.folder)
                .height(2.dp)
                .background(accent.copy(alpha = 0.85f)),
        )
    }
}

/** A folder silhouette: a body with a tab on its top-left edge. */
class FolderShape(val tabWidth: Dp, val tabHeight: Dp, val radius: Dp, val tabRadius: Dp) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        with(density) {
            val th = tabHeight.toPx()
            val r = radius.toPx()
            val tr = tabRadius.toPx()
            val body = Path().apply {
                addRoundRect(RoundRect(Rect(0f, th, size.width, size.height), CornerRadius(r, r)))
            }
            val inset = tabInset(radius).toPx()
            val tab = Path().apply {
                addRoundRect(RoundRect(Rect(inset, 0f, inset + tabWidth.toPx(), th + r), CornerRadius(tr, tr)))
            }
            return Outline.Generic(Path.combine(PathOperation.Union, body, tab))
        }
    }

    companion object {
        fun tabInset(radius: Dp): Dp = radius * 0.75f
    }
}

// MARK: - Progress rail

/**
 * The court-like progress rail: small dots on a hairline, done steps gold, the current step a larger gold dot
 * with a soft halo, upcoming steps faint. Used by the container's top bar. `index` is 0-based.
 */
object CourtProgressRail {
    fun accessibilityText(index: Int, count: Int): String = "Step ${index + 1} of $count"
    const val identifier = "onboarding.progress"
}

@Composable
fun CourtProgressRail(index: Int, count: Int, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val Z = OnboardingKitTokens.Size
    val reduceMotion = accessibilityReduceMotion()
    val done = OnboardingPalette.gold
    val upcoming = if (onDark) OnboardingPalette.cream.copy(alpha = 0.35f) else OnboardingPalette.border
    val n = maxOf(count, 1)
    val current = index.coerceIn(0, n - 1)
    val animated by animateFloatAsState(
        current.toFloat(),
        if (reduceMotion) tween(OnboardingKitTokens.Motion.fade.millis(), easing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.58f, 1f))
        else tween(OnboardingKitTokens.Motion.rail.millis(), easing = OnboardingEaseOut),
        label = "courtRail",
    )
    Canvas(
        modifier
            .fillMaxWidth()
            .height(Z.railHeight)
            .clearAndSetSemantics { contentDescription = CourtProgressRail.accessibilityText(current, n) }
            .testTag(CourtProgressRail.identifier),
    ) {
        val active = Z.railActiveDot.toPx()
        val gap = if (n > 1) (size.width - active) / (n - 1) else 0f
        fun x(i: Float) = active / 2 + i * gap
        val cy = size.height / 2
        val line = Z.railLine.toPx()
        drawLine(upcoming, Offset(x(0f), cy), Offset(x((n - 1).toFloat()), cy), strokeWidth = line)
        drawLine(done, Offset(x(0f), cy), Offset(x(animated), cy), strokeWidth = line)
        for (i in 0 until n) {
            val isActive = i == current
            val c = Offset(x(i.toFloat()), cy)
            if (isActive) drawCircle(done.copy(alpha = 0.22f), radius = active * 1.9f / 2, center = c)
            val d = if (isActive) active else Z.railDot.toPx()
            drawCircle(if (i <= current) done else upcoming, radius = d / 2, center = c)
        }
    }
}
