// Port of ArgueWin/Features/Settlement/SettlementComponents.swift.
//
// Settle Outside Court (CONTRACTS-v2 amendment n, brief §4 / §13). Calmer than the courtroom: cream,
// parchment and walnut, burgundy only for the primary action, gold only on the seal's ring and the
// FULFILLED stamp. No winner / loser language anywhere in this folder, and never the word SERVED.
package app.plead.android.features.settlement

import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.HourglassEmpty
import app.plead.android.designsystem.Chip
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.LegalLabel
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.ScallopShape
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.fixedSp
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.designsystem.pleadShadow
import app.plead.android.designsystem.swiftSpring
import app.plead.android.models.SettlementOffer
import app.plead.android.models.SettlementSuggestion
import app.plead.android.services.SettlementRules
import java.time.Duration
import java.time.Instant
import kotlin.math.max
import kotlinx.coroutines.delay

// MARK: - Seal

/**
 * Parchment wax seal with a signature mark: the settlement counterpart of the burgundy `SummonsSeal`.
 * [caption]: optional ring text ("SETTLED") for the large accepted seal.
 */
@Composable
fun SettlementSeal(modifier: Modifier = Modifier, size: Dp = 56.dp, caption: String? = null, stamped: Boolean = false) {
    val reduceMotion = accessibilityReduceMotion()
    var landed by rememberSaveable { mutableStateOf(false) }
    val settled = landed || reduceMotion || !stamped
    val progress = remember { Animatable(if (settled) 1f else 0f) }
    val view = LocalView.current
    LaunchedEffect(Unit) {
        if (!stamped || reduceMotion) {
            landed = true
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        if (landed) return@LaunchedEffect
        delay(200)
        landed = true
        Haptics.impactMedium(view)   // `.sensoryFeedback(.impact(weight: .medium), trigger: landed)` when stamped
        progress.animateTo(1f, swiftSpring(duration = 0.4f, bounce = 0.18f))
    }
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics { }
            .graphicsLayer {
                rotationZ = -6f
                val p = progress.value
                val s = 1.35f + (1f - 1.35f) * p
                scaleX = s
                scaleY = s
                alpha = p.coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val outline = ScallopShape(bumps = 16).createOutline(this.size, layoutDirection, this)
            val path = (outline as Outline.Generic).path
            val end = size.toPx()
            val start = 2.dp.toPx() / end
            drawPath(
                path,
                brush = Brush.radialGradient(
                    0f to PleadColor.paperWhite,
                    start to PleadColor.paperWhite,
                    (start + (1f - start) / 2f) to PleadColor.parchment,
                    1f to Color(hex = 0xE6CFB9),
                    center = Offset.Zero,
                    radius = end,
                ),
            )
            drawPath(path, color = PleadColor.walnut.copy(alpha = 0.35f), style = Stroke(width = max(1.dp.toPx(), end * 0.015f)))
            // Gold dashed ring, inset 14 % (strokeBorder: inside the circle).
            val inset = this.size.width * 0.14f
            val lw = max(1.dp.toPx(), end * 0.018f)
            val dash = end * 0.035f
            drawCircle(
                color = PleadColor.gold.copy(alpha = 0.9f),
                radius = (this.size.width - 2 * inset) / 2 - lw / 2,
                style = Stroke(width = lw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(size * 0.02f)) {
            // SF Symbol "signature" at `.font(.system(size: size * (caption == nil ? 0.34 : 0.24), weight: .semibold))`.
            SignatureGlyph(pointSize = size * (if (caption == null) 0.34f else 0.24f), tint = PleadColor.walnut)
            if (caption != null) {
                // Art-proportional seal label.
                Text(
                    caption.uppercase(),
                    style = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.Bold,
                        fontSize = fixedSp((size * 0.075f).value),
                        letterSpacing = fixedSp(PleadType.capsTracking),
                        textAlign = TextAlign.Center,
                    ),
                    color = PleadColor.walnut,
                    maxLines = 2,
                    modifier = Modifier.width(size * 0.56f),
                )
            }
        }
    }
}

// MARK: - Suggestion card

/** One radio card: kind label (QUICK COMPROMISE…), the terms, category chip and "within N days". */
@Composable
fun SettlementSuggestionCard(
    suggestion: SettlementSuggestion,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    val shape = RoundedCornerShape(PleadRadius.tile)
    val borderWidth by animateFloatAsState(if (isSelected) 2f else 1f, tween(150, easing = PleadMotion.easeOut), label = "cardBorder")
    val label = listOfNotNull(
        suggestion.kind.title,
        suggestion.body,
        suggestion.category?.takeIf { it.isNotEmpty() }?.let(SettlementCopy::categoryTitle),
        SettlementRules.dueLine(suggestion.dueDays),
    ).joinToString(", ")
    Row(
        modifier
            .fillMaxWidth()
            .clearAndSetSemantics {
                contentDescription = label + if (suggestion.generic == true) ". A general suggestion" else ""
                selected = isSelected
                onClick { action(); true }
            }
            .pressScaleClickable(enabled = enabled, onClick = action)
            .background(if (isSelected) PleadColor.paperWhite else PleadColor.paperWhite.copy(alpha = 0.7f), shape)
            .border(borderWidth.dp, if (isSelected) PleadColor.burgundy else PleadColor.walnut.copy(alpha = 0.18f), shape)
            .padding(PleadSpacing.l),
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
            LegalLabel(suggestion.kind.title, color = PleadColor.walnut, size = 11f)
            Text(suggestion.body, style = PleadType.bodyMedium, color = PleadColor.cocoa)
            Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) {
                val category = suggestion.category
                if (category != null && category.isNotEmpty()) {
                    Chip(SettlementCopy.categoryTitle(category), foreground = PleadColor.walnut)
                }
                IconLabel(SettlementRules.dueLine(suggestion.dueDays), Icons.Outlined.CalendarMonth)
            }
        }
        SettlementRadio(isSelected = isSelected)
    }
}

/** The radio circle on the trailing edge (brief mockup). */
@Composable
fun SettlementRadio(isSelected: Boolean, modifier: Modifier = Modifier, size: Dp = 26.dp) {
    Box(modifier.size(size).clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxSize()
                .border(2.dp, if (isSelected) PleadColor.burgundy else PleadColor.walnut.copy(alpha = 0.45f), CircleShape),
        )
        if (isSelected) Box(Modifier.fillMaxSize().padding(6.dp).background(PleadColor.burgundy, CircleShape))
    }
}

// MARK: - Offer card

/**
 * The terms on the table: who proposed them, the body, and the due window.
 * [heading]: "ALEX'S OFFER" / "YOUR OFFER".
 */
@Composable
fun SettlementOfferCard(
    offer: SettlementOffer,
    heading: String,
    modifier: Modifier = Modifier,
    round: Int? = null,
    expiresAt: Instant? = null,
    emphasised: Boolean = true,
) {
    val shape = RoundedCornerShape(PleadRadius.card)
    var now by remember { mutableStateOf(Instant.now()) }
    if (expiresAt != null) {
        LaunchedEffect(expiresAt) {
            while (true) {
                now = Instant.now()
                delay(1000)
            }
        }
    }
    Column(
        modifier
            .fillMaxWidth()
            .pleadShadow(PleadColor.cocoa.copy(alpha = 0.05f), radius = 10.dp, y = 3.dp, shape = shape)
            .background(PleadColor.paperWhite, shape)
            .border(1.dp, PleadColor.walnut.copy(alpha = 0.2f), shape)
            .padding(PleadSpacing.l + 2.dp)
            .semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LegalLabel(heading, color = PleadColor.burgundy, size = 11f)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.width(PleadSpacing.s))
            if (round != null) Chip(SettlementRules.roundLine(round), foreground = PleadColor.walnut)
        }
        SelectionContainer {
            // Terms are the couple's words: the UI sans (medium), never Fraunces.
            Text(offer.body, style = if (emphasised) SettlementType.termsLarge else SettlementType.terms, color = PleadColor.cocoa)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m), verticalAlignment = Alignment.CenterVertically) {
            IconLabel(SettlementRules.dueLine(offer.dueDays).capitalizedFirst, Icons.Outlined.CalendarMonth)
            if (expiresAt != null && expiresAt.isAfter(now)) {
                IconLabel(
                    "Answer within ${SettlementCopy.relative(now, expiresAt)}",
                    Icons.Outlined.HourglassEmpty,
                    style = PleadType.metadata.monospacedDigit(),
                    maxLines = 1,
                )
            }
        }
    }
}

// MARK: - Entry button

/**
 * "Settle Outside Court": the calm third path under the pleas. Parchment on the mahogany summons,
 * never a third burgundy button.
 */
@Composable
fun SettlementEntryButton(
    modifier: Modifier = Modifier,
    title: String = "Settle Outside Court",
    onDark: Boolean = true,
    action: () -> Unit,
) {
    val shape = RoundedCornerShape(PleadRadius.button)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = accessibilityReduceMotion()
    val scale by animateFloatAsState(if (pressed && !reduceMotion) 0.97f else 1f, tween(120, easing = PleadMotion.easeOut), label = "entryPress")
    val fg = if (onDark) PleadColor.parchment else PleadColor.walnut
    val bg = if (onDark) PleadColor.parchment.copy(alpha = if (pressed) 0.16f else 0.09f) else PleadColor.parchment
    val stroke = if (onDark) PleadColor.parchment.copy(alpha = 0.28f) else PleadColor.walnut.copy(alpha = 0.25f)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .background(bg, shape)
            .drawBehind {
                val r = PleadRadius.button.toPx()
                val w = 1.dp.toPx()
                drawRoundRect(
                    color = stroke,
                    topLeft = Offset(w / 2, w / 2),
                    size = Size(size.width - w, size.height - w),
                    cornerRadius = CornerRadius(r, r),
                    style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = "Propose a compromise before the court hears the case", onClick = action),
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SignatureGlyph(pointSize = 15.dp, tint = fg)   // `Image(systemName: "signature").font(.system(size: 15, weight: .semibold))`
        Text(title, style = PleadType.uiButtonSecondary, color = fg)
    }
}

// MARK: - Copy

object SettlementCopy {
    const val helper = "Agreement only takes effect if both partners accept."
    const val flavour = "The parties have spared the court the trouble. Miracles do happen."
    const val noHardFeelings = "No hard feelings. The court will hear it."
    const val noWinner = "No winner. No loser. Case closed."
    const val courtWaits = "The court will wait. Timers are paused while you talk."
    const val finalRound = "Accept it, or the case returns to court."
    const val safetyHint = "Keep it kind, small and doable within a week. No money, passwords or tracking."

    /** "food" → "Food", "general" → "General". */
    fun categoryTitle(raw: String): String = raw.replace("_", " ").capitalizedFirst

    /**
     * SwiftUI `Text(date, style: .relative)` for a future date: the two largest units, abbreviated
     * ("11 hr, 59 min", "4 min, 12 sec", "1 day, 2 hr").
     */
    fun relative(now: Instant, to: Instant): String {
        val total = max(0L, Duration.between(now, to).seconds)
        val d = total / 86_400
        val h = (total % 86_400) / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        fun unit(n: Long, one: String, many: String) = "$n ${if (n == 1L) one else many}"
        return when {
            d > 0 -> listOfNotNull(unit(d, "day", "days"), if (h > 0) "$h hr" else null).joinToString(", ")
            h > 0 -> listOfNotNull("$h hr", if (m > 0) "$m min" else null).joinToString(", ")
            m > 0 -> listOfNotNull("$m min", if (s > 0) "$s sec" else null).joinToString(", ")
            else -> "$s sec"
        }
    }
}

/** First letter uppercased, the rest untouched. */
val String.capitalizedFirst: String get() = take(1).uppercase() + drop(1)

// MARK: - Type

/**
 * Settlement type from the Plead tokens (amendment u). Terms are the couple's own words, so they
 * stay the UI sans (medium, never Fraunces bold); headings are Fraunces, labels tracked caps.
 */
object SettlementType {
    val terms: TextStyle = PleadType.text(18f, FontWeight.Medium, TextStyleKind.headline)
    val termsLarge: TextStyle = PleadType.text(20f, FontWeight.Medium, TextStyleKind.title3)
}

