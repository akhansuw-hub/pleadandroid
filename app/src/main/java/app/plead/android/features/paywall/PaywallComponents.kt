// Port of ArgueWin/Features/Paywall/PaywallComponents.swift.
//
// Type: SwiftUI `.system(<style>, design: .rounded, weight:)` → the default sans at the text style's size (PORT.md
// §2: SF Pro Rounded has no Android equivalent), scaled with the system font size like Dynamic Type.
// `minimumScaleFactor` → `BasicText(autoSize = TextAutoSize.StepBased(...))`.
package app.plead.android.features.paywall

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.R
import app.plead.android.designsystem.PleadLogo
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.designsystem.pleadShadow
import app.plead.android.designsystem.swiftSpring
import app.plead.android.services.PaywallProduct
import app.plead.android.services.PurchasesService

// MARK: - Type helpers

/** SwiftUI `.system(style, design: .rounded, weight:)`. */
internal fun roundedFont(style: TextStyleKind, weight: FontWeight): TextStyle =
    TextStyle(fontFamily = FontFamily.Default, fontWeight = weight, fontSize = style.defaultSize.sp)

/** SwiftUI's `.heavy` (800); `.black` is [FontWeight.Black]. */
internal val FontWeightHeavy = FontWeight.ExtraBold

/** Dynamic Type sizes as Android font scales (iOS body 17 pt → xLarge 19, xxLarge 21, xxxLarge 23, AX1 28). */
object DynamicTypeScale {
    const val xLarge = 1.1f
    const val xxLarge = 1.3f
    const val xxxLarge = 1.35f

    /** `DynamicTypeSize.isAccessibilitySize` (same threshold as wave 2b's CaseFileCard). */
    const val accessibility = 1.6f
}

@Composable
internal fun fontScale(): Float = LocalDensity.current.fontScale

/** `.dynamicTypeSize(...limit)`: caps the font scale for [content]. */
@Composable
internal fun CappedTypeSize(limit: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    if (density.fontScale <= limit) {
        content()
    } else {
        CompositionLocalProvider(LocalDensity provides Density(density.density, limit), content = content)
    }
}

/**
 * Text with SwiftUI's `.lineLimit(maxLines).minimumScaleFactor(minScale)`: shrinks (down to [minScale]) before it
 * truncates. One line (`maxLines = 1`) is sized explicitly: the largest 0.5 sp step that fits the width on one line
 * (never a wrap, never a dropped word); `BasicText`'s auto-size didn't step down for unwrapped text, so it clipped
 * ("BEST VALU", "…FREE TRIAI" on a 360 dp phone at a large font size). Several lines use the auto-size.
 */
@Composable
internal fun ScaledText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    minScale: Float = 1f,
    textAlign: TextAlign = TextAlign.Center,
    /** One line only: when even [minScale] doesn't fit, wrap onto two lines at that size instead of clipping. */
    wrapsWhenTooLong: Boolean = false,
) {
    val full = style.fontSize
    if (maxLines == 1 && minScale < 1f) {
        BoxWithConstraints(modifier, propagateMinConstraints = true) {
            val measurer = rememberTextMeasurer()
            val size = remember(text, style, constraints.maxWidth, minScale) {
                ScaledText.oneLineFontSize(measurer, text, style, constraints.maxWidth, minScale)
            }
            val sized = style.copy(fontSize = size, textAlign = textAlign)
            val wraps = wrapsWhenTooLong && remember(text, sized, constraints.maxWidth) {
                !ScaledText.fitsOneLine(measurer, text, sized, constraints.maxWidth)
            }
            BasicText(
                text = text,
                style = sized,
                color = ColorProducer { color },
                maxLines = if (wraps) 2 else 1,
                softWrap = wraps,
                overflow = TextOverflow.Clip,
            )
        }
        return
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(textAlign = textAlign),
        color = ColorProducer { color },
        maxLines = maxLines,
        softWrap = maxLines > 1,
        autoSize = if (minScale < 1f) TextAutoSize.StepBased(minFontSize = full * minScale, maxFontSize = full, stepSize = 0.5.sp) else null,
    )
}

internal object ScaledText {
    /** The largest size from `style.fontSize` down to `minScale` of it (0.5 sp steps) whose one line fits [maxWidth] px. */
    fun oneLineFontSize(measurer: TextMeasurer, text: String, style: TextStyle, maxWidth: Int, minScale: Float): TextUnit {
        val full = style.fontSize
        if (maxWidth == Constraints.Infinity || !full.isSp) return full
        val min = full.value * minScale
        var size = full.value
        while (size > min) {
            val width = measurer.measure(text, style.copy(fontSize = size.sp), maxLines = 1, softWrap = false).size.width
            if (width <= maxWidth) return size.sp
            size -= 0.5f
        }
        return min.sp
    }

    fun fitsOneLine(measurer: TextMeasurer, text: String, style: TextStyle, maxWidth: Int): Boolean =
        maxWidth == Constraints.Infinity ||
            measurer.measure(text, style, maxLines = 1, softWrap = false).size.width <= maxWidth
}

/** Press feedback: scale 0.97 on press-in, 120 ms (Swift `PaywallPressStyle`). */
@Composable
internal fun Modifier.paywallPress(interaction: MutableInteractionSource): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = accessibilityReduceMotion()
    val scale by animateFloatAsState(if (pressed && !reduceMotion) 0.97f else 1f, tween(120), label = "paywallPress")
    return this.scale(scale)
}

// MARK: - Hero

/**
 * The courtroom: judge centered, partners left and right, spectators behind. Crisp (nearest-neighbour)
 * scaling, bottom edge dissolving into the cream UI. Sits under the status bar. (The single-image hero the living
 * `PaywallCourtroomHero` replaced; kept like iOS.)
 */
@Composable
fun PaywallHero(height: Dp, modifier: Modifier = Modifier) {
    val resources = LocalResources.current
    val art: ImageBitmap = remember(resources) { ImageBitmap.imageResource(resources, R.drawable.paywall_courtroom) }
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .paywallHeroFade()
            .clearAndSetSemantics {
                contentDescription = "A pixel-art courtroom: the judge at the bench, a couple at the two stands, spectators in the gallery."
                role = Role.Image
            },
    ) {
        Image(
            painter = remember(art) { BitmapPainter(art, filterQuality = FilterQuality.None) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.BottomCenter,
            modifier = Modifier.fillMaxWidth().height(height),
        )
    }
}

/** 44 pt circular close: cream fill, dark-cocoa X, soft shadow. */
@Composable
fun PaywallCloseButton(
    modifier: Modifier = Modifier,
    hint: String = "Back to inviting your partner",
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .paywallPress(interaction)
            .size(44.dp)
            .pleadShadow(PaywallPalette.darkCocoa.copy(alpha = 0.22f), radius = 6.dp, y = 2.dp, shape = CircleShape)
            .clip(CircleShape)
            .background(PaywallPalette.warmCream, CircleShape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClickLabel = hint, onClick = action)
            .semantics { contentDescription = "Close" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Close, contentDescription = null, tint = PaywallPalette.darkCocoa, modifier = Modifier.size(18.dp))
    }
}

// MARK: - Brand header

/**
 * The Plead logo (`PleadLogo`, heart-accent wordmark + strapline), headline and the couple sub-line.
 * [logoMarkOpacity]: amendment q: hidden while the opening's flying lockup is over the slot; 1 once the header owns
 * the logo. [onLogoBounds]: reports the logo's mark frame (in root coordinates) as the opening's landing slot.
 */
@Composable
fun PaywallBrandHeader(
    modifier: Modifier = Modifier,
    showsHeadline: Boolean = true,
    logoMarkOpacity: Double = 1.0,
    onLogoBounds: ((Rect) -> Unit)? = null,
    /** Android, short screens (`PaywallFit` step 2): a smaller logo; the text keeps its size. */
    compact: Boolean = false,
) {
    val scale = fontScale()
    val accessibilitySize = scale >= DynamicTypeScale.accessibility
    // `@ScaledMetric(relativeTo: .largeTitle) var logoWidth = 120`, capped at 170.
    val logoWidth = minOf((if (compact) PaywallCompact.logoWidth else 120f) * scale, 170f).dp
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Column(
            Modifier.paywallEntrance(PaywallEntranceLayer.brand),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            PleadLogo(
                modifier = Modifier.padding(bottom = 2.dp).semantics { heading() },
                strapline = true,
                width = logoWidth,
                markOpacity = logoMarkOpacity,
                onMarkBounds = onLogoBounds,
            )
            if (showsHeadline) {
                // One line like the mockup; wraps instead of shrinking at accessibility sizes.
                ScaledText(
                    PaywallCopy.headline,
                    style = roundedFont(TextStyleKind.title, FontWeightHeavy),
                    color = PaywallPalette.deepWine,
                    maxLines = if (accessibilitySize) 3 else 1,
                    minScale = if (accessibilitySize) 1f else 0.7f,
                    modifier = Modifier.fillMaxWidth().semantics { heading() },
                )
            }
        }
        if (showsHeadline) {
            Text(
                PaywallCopy.sub,
                style = roundedFont(TextStyleKind.subheadline, FontWeight.Medium),
                color = PaywallPalette.darkCocoa.copy(alpha = 0.72f),
                textAlign = TextAlign.Center,
                modifier = Modifier.paywallEntrance(PaywallEntranceLayer.sub),
            )
        }
    }
}

// MARK: - Benefits

/**
 * Four compact tiles: 4-up on regular phones, 2×2 on narrow widths or large text. Each shows its pixel icon and
 * title; the supporting line is read by TalkBack (it doesn't fit a 4-up tile without compressing the type).
 */
@Composable
fun BenefitGrid(
    modifier: Modifier = Modifier,
    benefits: List<PaywallBenefit> = PaywallCopy.benefits,
    /** Android, short screens (`PaywallFit` step 2). */
    compact: Boolean = false,
) {
    CappedTypeSize(DynamicTypeScale.xxLarge) {
        BoxWithConstraints(modifier.fillMaxWidth()) {
            val grid = BenefitGrid.usesTwoByTwo(width = maxWidth.value, fontScale = fontScale(), compact = compact)
            val columns = if (grid) 2 else 4
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                benefits.withIndex().chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { (i, benefit) ->
                            BenefitCard(
                                benefit = benefit,
                                horizontal = grid,
                                modifier = Modifier.weight(1f).fillMaxHeight().paywallEntrance(PaywallEntranceLayer.tile, index = i),
                            )
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

object BenefitGrid {
    /** 2×2 below this width (per tile ≈ 72 pt), or from xLarge text. */
    fun usesTwoByTwo(width: Float, fontScale: Float): Boolean = width < 330f || fontScale >= DynamicTypeScale.xLarge

    /** Compact (short screens): 4-up down to [PaywallCompact.minFourUpTile] per tile. */
    fun usesTwoByTwo(width: Float, fontScale: Float, compact: Boolean): Boolean {
        if (!compact) return usesTwoByTwo(width, fontScale)
        return (width - 3 * 6f) / 4f < PaywallCompact.minFourUpTile || fontScale >= DynamicTypeScale.xLarge
    }
}

/** A tiny game perk: pixel icon over (or, in the 2×2 layout, beside) a two-line title. */
@Composable
fun BenefitCard(benefit: PaywallBenefit, modifier: Modifier = Modifier, horizontal: Boolean = false) {
    val label: @Composable (TextAlign, Modifier) -> Unit = { align, m ->
        Text(
            benefit.title,
            style = roundedFont(TextStyleKind.caption, FontWeight.SemiBold),
            color = PaywallPalette.darkCocoa,
            maxLines = 2,
            textAlign = align,
            modifier = m,
        )
    }
    Box(
        modifier
            .background(PaywallPalette.perkFill, RoundedCornerShape(PaywallRadius.perk))
            .padding(vertical = 5.dp)
            .clearAndSetSemantics {
                contentDescription = benefit.title
                stateDescription = benefit.detail
            },
        contentAlignment = Alignment.Center,
    ) {
        if (horizontal) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PerkIconView(benefit.icon, Modifier.size(34.dp, 28.dp))
                label(TextAlign.Start, Modifier.weight(1f))
            }
        } else {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                PerkIconView(benefit.icon, Modifier.size(34.dp, 26.dp))
                label(TextAlign.Center, Modifier)
            }
        }
    }
}

// MARK: - Plans

/**
 * One plan card (Annual → Monthly → Weekly). Annual: #FFF1EB fill, BEST VALUE pill, "3 DAYS FREE" only when the
 * store confirms eligibility. Monthly / Weekly: paper white, localized price per period, never trial copy.
 * Selection changes the border (2.5 pt burgundy) and the filled selector only.
 */
@Composable
fun SubscriptionOption(
    plan: PurchasesService.Plan,
    product: PaywallProduct,
    trialEligible: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val isAnnual = plan == PurchasesService.Plan.annual
    val trialDays = if (isAnnual && trialEligible) product.freeTrialDays else null
    val shape = RoundedCornerShape(PaywallRadius.card)
    val interaction = remember { MutableInteractionSource() }
    // Selecting a plan: 0.25 s spring on the border only.
    val borderColor by animateColorAsState(
        if (isSelected) PaywallPalette.courtBurgundy else PaywallPalette.planBorder, swiftSpring(0.25f), label = "planBorder",
    )
    val borderWidth by animateDpAsState(if (isSelected) 2.5.dp else 1.5.dp, swiftSpring(0.25f), label = "planBorderWidth")
    val name = when (plan) {
        PurchasesService.Plan.annual -> "Annual, best value"
        PurchasesService.Plan.monthly -> "Monthly"
        PurchasesService.Plan.weekly -> "Weekly"
    }
    val accessibilityText = if (trialDays != null) {
        "$name. $trialDays days free, then ${product.perPeriod}."
    } else {
        "$name. ${product.perPeriod}."
    }
    Row(
        modifier
            .paywallPress(interaction)
            .fillMaxWidth()
            .defaultMinSize(minHeight = 54.dp)
            .clip(shape)
            .background(if (isAnnual) PaywallPalette.annualFill else PaywallPalette.paperWhite, shape)
            .border(borderWidth, borderColor, shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = action)
            .clearAndSetSemantics {
                contentDescription = accessibilityText
                role = Role.Button
                selected = isSelected
                onClick { action(); true }
            }
            .padding(start = 16.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(isSelected = isSelected)
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(PaywallCopy.planName(plan), style = roundedFont(TextStyleKind.title3, FontWeightHeavy), color = PaywallPalette.deepWine)
                // Android: the pill gives way (its text shrinks) rather than clipping to "BEST VA" on a 360 dp phone at
                // a large font size.
                if (isAnnual) BestValuePill(Modifier.weight(1f, fill = false))
            }
            if (trialDays != null) {
                ScaledText(
                    "$trialDays DAYS FREE",
                    style = roundedFont(TextStyleKind.title2, FontWeightHeavy),
                    color = PaywallPalette.coral,
                    maxLines = 1,
                    minScale = 0.85f,
                    textAlign = TextAlign.Start,
                )
                Text(
                    "Then ${product.perPeriod}.",
                    style = roundedFont(TextStyleKind.footnote, FontWeight.Medium).monospacedDigit(),
                    color = PaywallPalette.darkCocoa.copy(alpha = 0.7f),
                )
            } else {
                Text(
                    product.perPeriod,
                    style = roundedFont(TextStyleKind.body, FontWeight.Bold).monospacedDigit(),
                    color = PaywallPalette.darkCocoa.copy(alpha = 0.85f),
                )
            }
        }
        val ornament = when (plan) {
            PurchasesService.Plan.annual -> PaywallSprites.scales
            PurchasesService.Plan.monthly -> null
            PurchasesService.Plan.weekly -> null
        }
        if (ornament != null) PlanOrnament(ornament)
    }
}

/** A plan card's neutral placeholder while store metadata loads: no names, no prices, nothing guessed. */
@Composable
fun PlanSkeletonCard(modifier: Modifier = Modifier, tall: Boolean = false) {
    val shape = RoundedCornerShape(PaywallRadius.card)
    Row(
        modifier
            .fillMaxWidth()
            .height(if (tall) 76.dp else 54.dp)
            .background(PaywallPalette.paperWhite, shape)
            .border(1.5.dp, PaywallPalette.planBorder, shape)
            .clearAndSetSemantics { }
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(30.dp).border(2.dp, PaywallPalette.parchment, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(84.dp, 12.dp).background(PaywallPalette.parchment, CircleShape))
            Box(
                Modifier
                    .size(if (tall) 132.dp else 104.dp, if (tall) 18.dp else 12.dp)
                    .background(PaywallPalette.parchment.copy(alpha = 0.75f), CircleShape),
            )
        }
    }
}

@Composable
fun RadioMark(isSelected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(30.dp)
            .background(if (isSelected) PaywallPalette.courtBurgundy else PaywallPalette.paperWhite, CircleShape)
            .border(if (isSelected) 3.dp else 2.dp, if (isSelected) PaywallPalette.romanceBlush else PaywallPalette.parchment, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            Icon(Icons.Rounded.Check, contentDescription = null, tint = PaywallPalette.warmCream, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun BestValuePill(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(PaywallPalette.goldLight.copy(alpha = 0.55f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelGlyph(PaywallSprites.crown, Modifier.size(13.dp, 10.dp))
        ScaledText(
            PaywallCopy.bestValue,
            style = roundedFont(TextStyleKind.caption2, FontWeightHeavy),
            color = PaywallPalette.deepWine,
            maxLines = 1,
            minScale = 0.6f,
            textAlign = TextAlign.Start,
            wrapsWhenTooLong = true,
        )
    }
}

/** Small pixel ornament on the right of a plan card (scales / calendar) with two sparkles. */
@Composable
private fun PlanOrnament(sprite: PixelSprite) {
    Box(Modifier.padding(end = 6.dp).size(44.dp, 38.dp)) {
        PixelGlyph(sprite, Modifier.size(44.dp, 38.dp))
        PixelGlyph(PaywallSprites.sparkle, Modifier.size(7.dp).align(Alignment.TopStart).offset(x = (-9).dp, y = 2.dp))
        PixelGlyph(PaywallSprites.sparkle, Modifier.size(7.dp).align(Alignment.TopEnd).offset(x = 8.dp, y = (-4).dp))
    }
}

// MARK: - CTA

/** The burgundy call to action: pixel heart, title, arrow. Grows (never clips) at large text sizes. */
@Composable
fun PaywallCTA(
    state: PaywallCTAState,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    action: () -> Unit,
) = PaywallCTA(title = state.title, modifier = modifier, isLoading = isLoading, enabled = enabled, action = action)

@Composable
fun PaywallCTA(
    title: String,
    modifier: Modifier = Modifier,
    showsHeart: Boolean = true,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val accessibilitySize = fontScale() >= DynamicTypeScale.accessibility
    val shape = RoundedCornerShape(PaywallRadius.card)
    val interaction = remember { MutableInteractionSource() }
    val arrow: ImageVector = Icons.AutoMirrored.Rounded.ArrowForward
    Row(
        modifier
            .paywallPress(interaction)
            .alpha(if (enabled || isLoading) 1f else 0.55f)
            .fillMaxWidth()
            .heightIn(min = PaywallLayout.ctaHeight.dp)
            .pleadShadow(PaywallPalette.deepWine.copy(alpha = 0.25f), radius = 10.dp, y = 5.dp, shape = shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(PaywallPalette.courtBurgundy, PaywallPalette.deepWine)), shape)
            .border(1.dp, PaywallPalette.romanceBlush.copy(alpha = 0.35f), shape)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = action)
            .semantics { contentDescription = title.replace("—", ",") }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showsHeart) PixelGlyph(PaywallSprites.heart, Modifier.size(20.dp, 17.dp))
        BoxWithConstraints(Modifier.weight(1f).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
            // One line normally; at accessibility sizes it wraps and the button grows. Android: also when even 75%
            // can't fit one line (a 360 dp phone at a 130% font size), where it used to clip ("…FREE TRIAI").
            val style = roundedFont(TextStyleKind.title3, FontWeightHeavy)
            val measurer = rememberTextMeasurer()
            val wraps = accessibilitySize || !PaywallCTA.fitsOneLine(measurer, title, style, constraints.maxWidth)
            ScaledText(
                title,
                style = style,
                color = PaywallPalette.warmCream,
                maxLines = if (wraps) 4 else 1,
                minScale = if (wraps) 1f else PaywallCTA.minimumScaleFactor,
                modifier = Modifier.fillMaxWidth().alpha(if (isLoading) 0f else 1f),
            )
            if (isLoading) {
                CircularProgressIndicator(color = PaywallPalette.warmCream, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        }
        Icon(arrow, contentDescription = null, tint = PaywallPalette.warmCream, modifier = Modifier.size(22.dp))
    }
}

object PaywallCTA {
    /** Swift `.minimumScaleFactor(0.75)` on the title. */
    const val minimumScaleFactor: Float = 0.75f

    /** Whether [title] fits one line [maxWidth] px wide at the smallest size the button shrinks it to. */
    fun fitsOneLine(measurer: TextMeasurer, title: String, style: TextStyle, maxWidth: Int): Boolean {
        if (maxWidth == Constraints.Infinity) return true
        val smallest = style.copy(fontSize = style.fontSize * minimumScaleFactor)
        return measurer.measure(title, smallest, maxLines = 1, softWrap = false).size.width <= maxWidth
    }
}

/** "One subscription covers both of you." Always visible above the footer. */
@Composable
fun CoupleAccessLine(modifier: Modifier = Modifier) {
    Text(
        PaywallCopy.coupleAccess,
        style = roundedFont(TextStyleKind.subheadline, FontWeight.SemiBold),
        color = PaywallPalette.deepWine,
        textAlign = TextAlign.Center,
        modifier = modifier,
    )
}

// MARK: - Footer

/** Restore Purchases · Terms · Privacy — quiet, 13 pt muted cocoa, 32 pt tap targets. */
@Composable
fun PaywallLegalFooter(
    onRestore: () -> Unit,
    onTerms: () -> Unit,
    onPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
    restoring: Boolean = false,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        FooterLink(if (restoring) "Restoring…" else "Restore Purchases", enabled, onRestore)
        FooterDivider()
        FooterLink("Terms", enabled, onTerms)
        FooterDivider()
        FooterLink("Privacy", enabled, onPrivacy)
    }
}

@Composable
private fun FooterDivider() {
    Text(
        "|",
        style = roundedFootnote(),
        color = PaywallPalette.mutedCocoa.copy(alpha = PaywallPalette.mutedCocoa.alpha * 0.5f),
        modifier = Modifier.padding(horizontal = 10.dp).clearAndSetSemantics { },
    )
}

@Composable
private fun FooterLink(title: String, enabled: Boolean, action: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 32.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = action)
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        ScaledText(title, style = roundedFootnote(), color = PaywallPalette.mutedCocoa, maxLines = 1, minScale = 0.8f, wrapsWhenTooLong = true)
    }
}

/** `.font(.footnote)`: the default (non-rounded) footnote. */
internal fun roundedFootnote(weight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(fontFamily = FontFamily.Default, fontWeight = weight, fontSize = TextStyleKind.footnote.defaultSize.sp)
