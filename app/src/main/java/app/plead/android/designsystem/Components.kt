// Port of ArgueWin/DesignSystem/Components.swift.
//
// Shape rule (locked, CONTRACTS-v2 §5): cards 20 · tiles / inputs / buttons 14 · bubbles 16 · chips are capsules.
// Actions are rounded rectangles, never pills. Capsules are only for small chips.
// Colour rule: burgundy is THE accent (primary actions, selection, status). Gold is spent only on
// verdicts, scales, premium and the CLOSED stamp. Blush only for hearts / couple moments.
// Surfaces: cream background, paper-white cards, parchment for legal documents (case files, exhibits).
//
// SwiftUI → Compose: view modifiers are `Modifier` extensions with the same names (`awCard`, `awCourtFile`,
// `awInput`, `awBottomBar`, `awBackground`); `ButtonStyle.aw(...)` is `AWButton(style = AWButtonStyle.aw(...))`;
// types with statics (`Countdown`, `ScalesShape`) are an `object` next to the composable of the same name.
package app.plead.android.designsystem

import android.graphics.BitmapFactory
import android.text.format.DateFormat
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.models.Avatar
import app.plead.android.models.CaseStatus
import app.plead.android.models.Exhibit
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import app.plead.android.models.ObjectionRuling
import app.plead.android.models.Role
import app.plead.android.models.TrialPhase
import coil3.compose.SubcomposeAsyncImage
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

// MARK: - Surfaces

/** Warm cream app background, edge to edge. */
fun Modifier.awBackground(): Modifier = background(PleadColor.background)

/** Standard paper-white card (20 dp, hairline border, soft elevation). */
fun Modifier.awCard(padding: Dp = PleadSpacing.l): Modifier {
    val shape = RoundedCornerShape(PleadRadius.card)
    return this
        .fillMaxWidth()
        .pleadShadow(PleadColor.cocoa.copy(alpha = 0.05f), radius = 10.dp, y = 3.dp, shape = shape)
        .background(PleadColor.card, shape)
        .border(1.dp, PleadColor.separator.copy(alpha = 0.7f), shape)
        .padding(padding)
}

/** Parchment "court file" surface for legal documents (case rows, filings, exhibits). */
fun Modifier.awCourtFile(padding: Dp = PleadSpacing.l): Modifier {
    val shape = RoundedCornerShape(PleadRadius.card)
    return this
        .fillMaxWidth()
        .pleadShadow(PleadColor.cocoa.copy(alpha = 0.05f), radius = 8.dp, y = 2.dp, shape = shape)
        .background(PleadColor.parchment, shape)
        .border(1.dp, PleadColor.walnut.copy(alpha = 0.22f), shape)
        .padding(padding)
}

@Composable
fun AWCard(modifier: Modifier = Modifier, padding: Dp = PleadSpacing.l, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.awCard(padding), horizontalAlignment = Alignment.Start, content = content)
}

// MARK: - Buttons

enum class AWButtonKind { primary, secondary, onDark, plain }

/** Rounded-rectangle button style. Primary = burgundy fill with cream text. */
data class AWButtonStyle(val kind: AWButtonKind = AWButtonKind.primary, val fullWidth: Boolean = true, val large: Boolean = false) {
    val foreground: Color
        get() = when (kind) {
            AWButtonKind.primary -> PleadColor.cream
            AWButtonKind.secondary, AWButtonKind.plain -> PleadColor.burgundy
            AWButtonKind.onDark -> PleadColor.mahogany
        }

    val background: Color
        get() = when (kind) {
            AWButtonKind.primary -> PleadColor.burgundy
            AWButtonKind.secondary -> PleadColor.paperWhite
            AWButtonKind.onDark -> PleadColor.cream
            AWButtonKind.plain -> Color.Transparent
        }

    val textStyle: TextStyle
        get() = if (large) {
            PleadType.ui(18f, FontWeight.Bold, relativeTo = TextStyleKind.headline).copy(letterSpacing = 0.6.sp)
        } else {
            PleadType.uiButton
        }

    companion object {
        /** Swift `.buttonStyle(.aw(kind, fullWidth:, large:))`. */
        fun aw(kind: AWButtonKind = AWButtonKind.primary, fullWidth: Boolean = true, large: Boolean = false) =
            AWButtonStyle(kind, fullWidth, large)
    }
}

/**
 * A `Button` with [AWButtonStyle]: press scales to 0.97 (not under Reduce Motion, easeOut 0.12 s), disabled
 * fades to 45 %, primary casts a soft burgundy shadow.
 */
@Composable
fun AWButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AWButtonStyle = AWButtonStyle.aw(),
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val reduceMotion = accessibilityReduceMotion()
    val scale by animateFloatAsState(
        targetValue = if (pressed && !reduceMotion) 0.97f else 1f,
        animationSpec = tween(120, easing = PleadMotion.easeOut),
        label = "awButtonPress",
    )
    val shape = RoundedCornerShape(PleadRadius.button)
    val border = when (style.kind) {
        AWButtonKind.secondary -> Modifier.border(1.dp, PleadColor.burgundy.copy(alpha = 0.28f), shape)
        AWButtonKind.primary -> Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), shape)
        else -> Modifier
    }
    Row(
        modifier
            .scale(scale)
            .alpha(if (enabled) 1f else 0.45f)
            .pleadShadow(
                if (style.kind == AWButtonKind.primary && enabled) PleadColor.burgundy.copy(alpha = 0.22f) else Color.Transparent,
                radius = 8.dp, y = 4.dp, shape = shape,
            )
            .then(if (style.fullWidth) Modifier.fillMaxWidth() else Modifier)
            .defaultMinSize(minHeight = if (style.large) 60.dp else 50.dp)
            .clip(shape)
            .background(style.background, shape)
            .then(border)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(horizontal = PleadSpacing.xl, vertical = if (style.large) 18.dp else 14.dp),
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides style.foreground,
            androidx.compose.material3.LocalTextStyle provides style.textStyle.copy(color = style.foreground, textAlign = TextAlign.Center),
        ) {
            content()
        }
    }
}

/** The primary action button, with an optional trailing symbol and an in-place loading state. */
@Composable
fun PrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    systemImage: String? = null,
    kind: AWButtonKind = AWButtonKind.primary,
    fullWidth: Boolean = true,
    large: Boolean = false,
    isLoading: Boolean = false,
    action: () -> Unit,
) {
    val style = AWButtonStyle.aw(kind, fullWidth = fullWidth, large = large)
    AWButton(
        onClick = action,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = title },
        style = style,
        enabled = !isLoading,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = if (kind == AWButtonKind.primary) PleadColor.cream else PleadColor.burgundy,
                strokeWidth = 2.dp,
                modifier = Modifier.size(20.dp),
            )
        } else {
            Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics { })
            if (systemImage != null) {
                Icon(SFSymbol.icon(systemImage), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// MARK: - Labels & chips

/** Small-caps legal label: "EXHIBIT A", "PLAINTIFF", "THE COURT IS DELIBERATING". */
@Composable
fun LegalLabel(text: String, modifier: Modifier = Modifier, color: Color = PleadColor.burgundy, size: Float = 12f) {
    Text(
        text.uppercase(),
        modifier = modifier,
        style = PleadType.ui(size, FontWeight.Bold, relativeTo = TextStyleKind.caption).copy(letterSpacing = PleadType.capsTracking.sp),
        color = color,
    )
}

/** Small capsule chip. Capsules are reserved for chips. */
@Composable
fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    foreground: Color = PleadColor.burgundy,
    background: Color? = null,
    systemImage: String? = null,
) {
    Row(
        modifier
            .background(background ?: foreground.copy(alpha = 0.1f), CircleShape)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (systemImage != null) {
            Icon(SFSymbol.icon(systemImage), contentDescription = null, tint = foreground, modifier = Modifier.size(11.dp))
        }
        Text(
            text.uppercase(),
            style = PleadType.labelCaps.copy(letterSpacing = 0.6.sp),
            color = foreground,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** PLAINTIFF (burgundy) / DEFENDANT (walnut) role chip. */
@Composable
fun RoleChip(role: Role, modifier: Modifier = Modifier) {
    val title = if (role == Role.plaintiff) "Plaintiff" else "Defendant"
    Chip(
        title,
        modifier = modifier.clearAndSetSemantics { contentDescription = title },
        foreground = PleadColor.cream,
        background = PleadColor.role(role),
    )
}

/** Small phase marker ("Opening", "Exhibits"...). */
@Composable
fun PhaseChip(text: String, modifier: Modifier = Modifier, tint: Color = PleadColor.walnut) {
    Chip(text, modifier = modifier, foreground = tint)
}

@Composable
fun PhaseChip(phase: TrialPhase, modifier: Modifier = Modifier) {
    PhaseChip(phase.chipTitle, modifier = modifier)
}

/** Compact status chip for inline use (case detail header). */
@Composable
fun StatusChip(status: CaseStatus, modifier: Modifier = Modifier) {
    Chip(
        status.shortTitle,
        modifier = modifier.clearAndSetSemantics { contentDescription = "Status: ${status.shortTitle}" },
        foreground = if (status.isOpen) PleadColor.cream else PleadColor.walnut,
        background = if (status.isOpen) status.ribbonColor else PleadColor.walnut.copy(alpha = 0.12f),
    )
}

/** The burgundy status ribbon pinned to the top-left of a court file, with a swallow-tail end. */
@Composable
fun StatusRibbon(text: String, modifier: Modifier = Modifier, color: Color = PleadColor.burgundy) {
    Text(
        text.uppercase(),
        style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
        color = PleadColor.cream,
        modifier = modifier
            .clearAndSetSemantics { contentDescription = "Status: $text" }
            .background(color, RibbonShape)
            .padding(start = 12.dp, end = 20.dp, top = 5.dp, bottom = 5.dp),
    )
}

object RibbonShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = Path()
        val notch = size.height * 0.32f
        p.moveTo(0f, 0f)
        p.lineTo(size.width, 0f)
        p.lineTo(size.width - notch, size.height / 2)
        p.lineTo(size.width, size.height)
        p.lineTo(0f, size.height)
        p.close()
        return Outline.Generic(p)
    }
}

/** The gold rubber stamp on completed cases. */
@Composable
fun ClosedStamp(modifier: Modifier = Modifier, text: String = "Closed", color: Color = PleadColor.gold) {
    Box(
        modifier
            .clearAndSetSemantics { contentDescription = text }
            .rotate(-9f)
            .border(2.dp, color, RoundedCornerShape(5.dp))
            .padding(3.dp)
            .border(0.75.dp, color.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(
            text.uppercase(),
            style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 2.sp),
            color = color,
        )
    }
}

// MARK: - Scales

/** Scales of justice line mark. Gold by default: gold is reserved for scales, verdicts and premium. */
@Composable
fun ScalesMark(modifier: Modifier = Modifier, size: Dp = 20.dp, color: Color = PleadColor.gold) {
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        drawPath(
            ScalesShape.path(this.size),
            color = color,
            style = Stroke(width = max(1.2.dp.toPx(), this.size.width * 0.075f), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

object ScalesShape {
    fun path(r: Size): Path {
        val p = Path()
        val w = r.width
        val h = r.height
        // Post and base
        p.moveTo(w * 0.5f, h * 0.12f); p.lineTo(w * 0.5f, h * 0.88f)
        p.moveTo(w * 0.3f, h * 0.9f); p.lineTo(w * 0.7f, h * 0.9f)
        // Beam
        p.moveTo(w * 0.12f, h * 0.24f); p.lineTo(w * 0.88f, h * 0.24f)
        // Hangers + pans
        for (cx in listOf(0.2f, 0.8f)) {
            p.moveTo(w * cx, h * 0.24f); p.lineTo(w * (cx - 0.12f), h * 0.56f)
            p.moveTo(w * cx, h * 0.24f); p.lineTo(w * (cx + 0.12f), h * 0.56f)
            p.moveTo(w * (cx - 0.15f), h * 0.56f)
            p.quadraticTo(w * cx, h * 0.74f, w * (cx + 0.15f), h * 0.56f)
            p.close()
        }
        return p
    }
}

// MARK: - Countdown

/** Countdown formatting (Swift `Countdown` statics). `.compact` = "2d 4h" / "3h 12m" / "08:41"; `.clock` = "03:42:16". */
object Countdown {
    enum class Style { compact, clock }

    fun format(seconds: Double): String {
        if (seconds <= 0) return "Now"
        val s = seconds.toInt()
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        if (d > 0) return "${d}d ${h}h"
        if (h > 0) return "${h}h ${m}m"
        return String.format(Locale.ROOT, "%02d:%02d", m, sec)
    }

    fun clock(seconds: Double): String {
        if (seconds <= 0) return "00:00:00"
        val s = seconds.toInt()
        val d = s / 86_400
        val h = (s % 86_400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        val hms = String.format(Locale.ROOT, "%02d:%02d:%02d", h, m, sec)
        return if (d > 0) "${d}d $hms" else hms
    }

    /**
     * Spoken form (Swift `DateComponentsFormatter`, `.full`, at most two units, zero units dropped): days / hours /
     * minutes over an hour, minutes / seconds below.
     */
    fun spoken(seconds: Double): String {
        if (seconds <= 0) return "Now"
        val s = seconds.toLong()
        // Allowed units, largest first: (name, size in seconds, roll-over into the unit above).
        val units: List<Triple<String, Long, Long>> = if (seconds > 3600) {
            listOf(Triple("day", 86_400L, Long.MAX_VALUE), Triple("hour", 3600L, 24L), Triple("minute", 60L, 60L))
        } else {
            listOf(Triple("minute", 60L, Long.MAX_VALUE), Triple("second", 1L, 60L))
        }
        // Components truncated below the smallest allowed unit.
        var rest = s
        val values = units.map { (_, size, _) -> (rest / size).also { rest %= size } }.toMutableList()
        val nonZero = values.indices.filter { values[it] > 0 }
        if (nonZero.isEmpty()) return "0 ${units.last().first}s"
        // DateComponentsFormatter keeps the first two non-zero units and rounds the dropped remainder into the last.
        if (nonZero.size > 2) {
            val last = nonZero[1]
            val dropped = (last + 1 until values.size).sumOf { values[it] * units[it].second }
            for (i in last + 1 until values.size) values[i] = 0
            if (dropped * 2 >= units[last].second) {
                values[last] += 1
                var i = last
                while (i > 0 && values[i] >= units[i].third) {
                    values[i] -= units[i].third
                    values[i - 1] += 1
                    i -= 1
                }
            }
        }
        return values.indices.filter { values[it] > 0 }.take(2)
            .joinToString(", ") { i -> "${values[i]} ${units[i].first}${if (values[i] == 1L) "" else "s"}" }
    }
}

/** Live countdown to [target], ticking every second. */
@Composable
fun Countdown(
    target: Instant,
    modifier: Modifier = Modifier,
    font: TextStyle = PleadType.timerSmall,
    style: Countdown.Style = Countdown.Style.compact,
    color: Color = androidx.compose.material3.LocalContentColor.current,
) {
    val now by produceState(Instant.now()) {
        while (true) {
            value = Instant.now()
            delay(1000 - (System.currentTimeMillis() % 1000))
        }
    }
    val remaining = Duration.between(now, target).toMillis() / 1000.0
    NumericText(
        text = if (style == Countdown.Style.clock) Countdown.clock(remaining) else Countdown.format(remaining),
        style = font.monospacedDigit(),
        color = color,
        countsDown = true,
        modifier = modifier.clearAndSetSemantics { contentDescription = Countdown.spoken(remaining) },
    )
}

// MARK: - Record

/** "Your record: 7 – 5" with both avatars. */
@Composable
fun RecordLine(
    mine: Int,
    partners: Int,
    modifier: Modifier = Modifier,
    ties: Int = 0,
    me: Avatar? = null,
    partner: Avatar? = null,
) {
    val label = "Your record: $mine wins, $partners losses${if (ties > 0) ", $ties ties" else ""}"
    Row(
        modifier.clearAndSetSemantics { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (me != null) AvatarBadge(me, size = 34.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("Your record", style = PleadFont.caption, color = PleadColor.subtleText)
            NumericText(
                "$mine – $partners",
                style = PleadFont.ui(24f, FontWeight.ExtraBold).monospacedDigit(),
                color = PleadColor.cocoa,
            )
        }
        if (ties > 0) {
            Text("$ties ${if (ties == 1) "tie" else "ties"}", style = PleadFont.caption, color = PleadColor.subtleText)
        }
        if (partner != null) AvatarBadge(partner, size = 34.dp)
    }
}

// MARK: - Summons seal

/** Scalloped burgundy wax seal with a gold ring, stamped on the summons. */
@Composable
fun SummonsSeal(modifier: Modifier = Modifier, size: Dp = 160.dp, stamped: Boolean = true) {
    val reduceMotion = accessibilityReduceMotion()
    var landed by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (!stamped || reduceMotion) 1f else 0f) }
    val view = LocalView.current
    LaunchedEffect(Unit) {
        if (!stamped || reduceMotion) {
            landed = true
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        delay(150)
        landed = true
        progress.animateTo(1f, swiftSpring(duration = 0.35f, bounce = 0.2f))
    }
    // `.sensoryFeedback(.impact(weight: .medium), trigger: landed)`.
    LaunchedEffect(landed) {
        if (landed) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }
    val sizePx = size
    Box(
        modifier
            .size(size)
            .clearAndSetSemantics { contentDescription = "Court summons seal" }
            .graphicsLayer {
                rotationZ = -8f
                val p = progress.value
                val s = 1.5f + (1f - 1.5f) * p
                scaleX = s
                scaleY = s
                alpha = p.coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val outline = ScallopShape(bumps = 18).createOutline(this.size, layoutDirection, this)
            val end = sizePx.toPx()
            val start = 4.dp.toPx() / end
            drawPath(
                (outline as Outline.Generic).path,
                brush = Brush.radialGradient(
                    0f to Color(hex = 0xA63A36),
                    start to Color(hex = 0xA63A36),
                    (start + (1f - start) / 2f) to PleadColor.burgundy,
                    1f to PleadColor.mahogany,
                    center = Offset.Zero,
                    radius = end,
                ),
            )
            // Gold dashed ring, inset 14 % (strokeBorder: the 2 dp stroke sits inside the circle).
            val inset = this.size.width * 0.14f
            val lw = 2.dp.toPx()
            drawCircle(
                color = PleadColor.gold.copy(alpha = 0.85f),
                radius = (this.size.width - 2 * inset) / 2 - lw / 2,
                style = Stroke(width = lw, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(size * 0.03f)) {
            ScalesMark(size = size * 0.3f, color = PleadColor.gold)
            Text(
                "SUMMONS",
                style = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = fixedSp((size * 0.09f).value),
                    letterSpacing = fixedSp(1.5f),
                ),
                color = PleadColor.cream,
            )
        }
    }
}

class ScallopShape(val bumps: Int) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val cx = size.width / 2
        val cy = size.height / 2
        val r = min(size.width, size.height) / 2
        val p = Path()
        val steps = 360
        for (i in 0..steps) {
            val t = i.toDouble() / steps * 2 * PI
            val rr = r * (0.93 + 0.07 * abs(cos(bumps * t / 2)))
            val x = (cx + cos(t) * rr).toFloat()
            val y = (cy + sin(t) * rr).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        return Outline.Generic(p)
    }
}

// MARK: - Exhibit tile

/**
 * Thumbnail + serif label + caption. Works for saved exhibits (signed URL) and drafts (image data).
 * `body_` keeps the Swift name (`body` is taken in SwiftUI). The `draft:` overload lives with `DraftExhibit`
 * (services, wave 2a): `ExhibitTile(label, type = draft.type, caption = draft.caption, body_ = draft.body,
 * imageData = draft.imageData, occurredAt = draft.occurredAt, ownerRole = ownerRole)`.
 */
@Composable
fun ExhibitTile(
    label: ExhibitLabel,
    type: ExhibitType,
    caption: String,
    modifier: Modifier = Modifier,
    body_: String? = null,
    imageURL: String? = null,
    imageData: ByteArray? = null,
    ruling: ObjectionRuling? = null,
    occurredAt: Instant? = null,
    ownerRole: Role? = null,
) {
    val typeName = ExhibitTileCopy.typeName(type)
    val a11y = ExhibitTileCopy.accessibilityLabel(label, typeName, caption, body_, occurredAt, ruling)
    Column(modifier.clearAndSetSemantics { contentDescription = a11y }, verticalArrangement = Arrangement.spacedBy(PleadSpacing.s)) {
        val tileShape = RoundedCornerShape(PleadRadius.tile)
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(tileShape)
                    .border(1.dp, PleadColor.walnut.copy(alpha = 0.2f), tileShape),
            ) {
                ExhibitThumbnail(type = type, caption = caption, body = body_, imageURL = imageURL, imageData = imageData)
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .defaultMinSize(minWidth = 26.dp, minHeight = 26.dp)
                    .background(ownerRole?.let { PleadColor.role(it) } ?: PleadColor.mahogany, RoundedCornerShape(7.dp))
                    .padding(horizontal = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label.rawValue,
                    style = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                    color = PleadColor.cream,
                )
            }
            if (ruling != null) {
                val tint = if (ruling == ObjectionRuling.sustained) PleadColor.burgundy else PleadColor.walnut
                Text(
                    if (ruling == ObjectionRuling.sustained) "SUSTAINED" else "OVERRULED",
                    style = PleadFont.ui(9.5f, FontWeight.ExtraBold),
                    color = tint,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .rotate(-8f)
                        .background(PleadColor.paperWhite.copy(alpha = 0.9f), RoundedCornerShape(4.dp))
                        .border(1.5.dp, tint, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "Exhibit ${label.rawValue}",
                style = PleadFont.serif(12f, italic = false).copy(fontWeight = FontWeight.SemiBold),
                color = PleadColor.walnut,
            )
            Text(
                if (caption.isEmpty()) capitalized(typeName) else caption,
                style = PleadFont.caption,
                color = PleadColor.cocoa,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (occurredAt != null) {
                Text(
                    ExhibitTileCopy.dayMonthTime(occurredAt),
                    style = PleadFont.ui(11f, FontWeight.Medium).monospacedDigit(),
                    color = PleadColor.subtleText,
                )
            }
        }
    }
}

/** [ExhibitTile] for a saved exhibit (Swift `init(exhibit:url:ownerRole:)`). */
@Composable
fun ExhibitTile(exhibit: Exhibit, url: String?, ownerRole: Role?, modifier: Modifier = Modifier) {
    ExhibitTile(
        label = exhibit.label,
        type = exhibit.type,
        caption = exhibit.caption,
        modifier = modifier,
        body_ = exhibit.body,
        imageURL = url,
        ruling = exhibit.objectionRuling,
        occurredAt = exhibit.occurredAt,
        ownerRole = ownerRole,
    )
}

/** The copy an [ExhibitTile] shows or speaks. */
object ExhibitTileCopy {
    fun typeName(type: ExhibitType): String = when (type) {
        ExhibitType.photo -> "photo"
        ExhibitType.screenshot -> "screenshot"
        ExhibitType.voice -> "voice note"
        ExhibitType.text -> "text quote"
        ExhibitType.receipt -> "receipt"
    }

    /** `.dateTime.day().month(.abbreviated).hour().minute()` in the user's locale ("12 Sept, 21:14"). */
    fun dayMonthTime(date: Instant, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMMjmm"), locale).format(date.atZone(zone))

    /** `formatted(date: .abbreviated, time: .shortened)` ("12 Sept 2026 at 21:14"). */
    fun abbreviatedDateTime(date: Instant, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMdjmm"), locale).format(date.atZone(zone))

    fun accessibilityLabel(
        label: ExhibitLabel,
        typeName: String,
        caption: String,
        body: String?,
        occurredAt: Instant?,
        ruling: ObjectionRuling?,
    ): String = "Exhibit ${label.rawValue}, $typeName. $caption" +
        (body?.let { ". $it" } ?: "") +
        (occurredAt?.let { ". Dated ${abbreviatedDateTime(it)}" } ?: "") +
        (ruling?.let { ". Objection ${it.rawValue}" } ?: "")
}

/**
 * The picture part of an exhibit: image (data or signed URL), or a quote / receipt card.
 * `compact` drops the text preview to a single glyph for small (≤ 72 dp) thumbnails.
 */
@Composable
fun ExhibitThumbnail(
    type: ExhibitType,
    caption: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    imageURL: String? = null,
    imageData: ByteArray? = null,
    compact: Boolean = false,
) {
    val bitmap: ImageBitmap? = remember(imageData) {
        imageData?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(modifier.fillMaxSize()) {
        when {
            bitmap != null -> Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            imageURL != null -> SubcomposeAsyncImage(
                model = imageURL,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = {
                    Box(Modifier.fillMaxSize().background(PleadColor.parchment), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = PleadColor.subtleText, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                },
                error = { ThumbnailPlaceholder("photo", compact) },
            )
            type == ExhibitType.text || type == ExhibitType.receipt -> {
                val symbol = if (type == ExhibitType.receipt) "receipt" else "quote.opening"
                val bg = if (type == ExhibitType.receipt) PleadColor.paperWhite else PleadColor.parchment
                if (compact) {
                    Box(Modifier.fillMaxSize().background(bg), contentAlignment = Alignment.Center) {
                        Icon(SFSymbol.icon(symbol), contentDescription = null, tint = PleadColor.walnut.copy(alpha = 0.75f), modifier = Modifier.size(22.dp))
                    }
                } else {
                    Box(Modifier.fillMaxSize().background(bg)) {
                        Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp, top = 36.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(SFSymbol.icon(symbol), contentDescription = null, tint = PleadColor.walnut.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
                            Text(
                                body ?: caption,
                                style = if (type == ExhibitType.receipt) {
                                    PleadFont.ui(11.5f, FontWeight.SemiBold).copy(fontFamily = FontFamily.Monospace)
                                } else {
                                    PleadFont.serif(13f)
                                },
                                color = PleadColor.cocoa,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
            else -> ThumbnailPlaceholder(if (type == ExhibitType.screenshot) "iphone" else "photo", compact)
        }
    }
}

@Composable
private fun ThumbnailPlaceholder(symbol: String, compact: Boolean) {
    Box(Modifier.fillMaxSize().background(PleadColor.parchment), contentAlignment = Alignment.Center) {
        // `.title3` / `.title2` glyphs.
        Icon(SFSymbol.icon(symbol), contentDescription = null, tint = PleadColor.walnut.copy(alpha = 0.6f), modifier = Modifier.size(if (compact) 22.dp else 26.dp))
    }
}

// MARK: - Weight dots

@Composable
fun WeightDots(weight: Int, modifier: Modifier = Modifier) {
    Row(modifier.clearAndSetSemantics { contentDescription = "Weight $weight of 3" }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(3) { i ->
            Box(Modifier.size(7.dp).background(if (i < weight) PleadColor.burgundy else PleadColor.separator, CircleShape))
        }
    }
}

// MARK: - Forms

/** Form input container: 14 dp tile radius, paper-white. Text fields inside use [AWInputStyle.textStyle]. */
object AWInputStyle {
    /** `.font(AWFont.body).foregroundStyle(AWColor.cocoa)`. */
    val textStyle: TextStyle = PleadFont.body.copy(color = PleadColor.cocoa)
    val padding: Dp = PleadSpacing.m + 2.dp
}

fun Modifier.awInput(): Modifier {
    val shape = RoundedCornerShape(PleadRadius.tile)
    return this
        .background(PleadColor.paperWhite, shape)
        .border(1.dp, PleadColor.separator, shape)
        .padding(AWInputStyle.padding)
}

/** Error text shown inline under a form or button. */
@Composable
fun InlineError(message: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        val last = remember { mutableStateOf("") }
        if (message != null) last.value = message
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(SFSymbol.icon("exclamationmark.circle.fill"), contentDescription = null, tint = PleadColor.danger, modifier = Modifier.size(14.dp))
            Text(last.value, style = PleadFont.caption, color = PleadColor.danger)
        }
    }
}

/** Section label used across forms and lists. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = PleadFont.caption.copy(letterSpacing = 0.6.sp),
        color = PleadColor.subtleText,
        modifier = modifier.fillMaxWidth().semantics { heading() },
    )
}

/** Bottom-pinned action bar background (cream with a hairline shadow), for a screen's bottom slot. */
fun Modifier.awBottomBar(): Modifier = this
    .fillMaxWidth()
    .pleadShadow(PleadColor.cocoa.copy(alpha = 0.06f), radius = 6.dp, y = (-2).dp)
    .background(PleadColor.background)
    .navigationBarsPadding()
    .padding(horizontal = PleadSpacing.xl)
    .padding(top = PleadSpacing.s, bottom = PleadSpacing.s)

// MARK: - Case status presentation

val CaseStatus.shortTitle: String
    get() = when (this) {
        CaseStatus.drafting -> "Drafting"
        CaseStatus.summoned -> "Summoned"
        CaseStatus.defence -> "Defence"
        CaseStatus.scheduling -> "Scheduling"
        CaseStatus.trial -> "In trial"
        CaseStatus.deliberating -> "Deliberating"
        CaseStatus.awaitingVerdict -> "Deliberating"
        CaseStatus.verdict -> "Verdict"
        CaseStatus.appeal -> "Appeal"
        CaseStatus.closed -> "Closed"
        CaseStatus.closedGuilty -> "Guilty plea"
        CaseStatus.closedDefault -> "Default"
        CaseStatus.closedSettled -> "Settled"
        CaseStatus.mistrial -> "Mistrial"
    }

/** Ribbon colour: burgundy for live cases, mahogany while the court deliberates, walnut once closed. */
val CaseStatus.ribbonColor: Color
    get() = when (this) {
        CaseStatus.deliberating, CaseStatus.awaitingVerdict, CaseStatus.verdict, CaseStatus.appeal -> PleadColor.mahogany
        CaseStatus.closed, CaseStatus.closedGuilty, CaseStatus.closedDefault, CaseStatus.closedSettled -> PleadColor.walnut
        CaseStatus.mistrial -> PleadColor.subtleText
        else -> PleadColor.burgundy
    }

// MARK: - Preview

@Preview(name = "Components", widthDp = 402, heightDp = 1400)
@Composable
private fun ComponentsPreview() {
    ComponentsPreviewContent()
}

/** The Swift `#Preview("Components")` body (also the top of the component gallery). */
@Composable
fun ComponentsPreviewContent(modifier: Modifier = Modifier) {
    Column(
        modifier
            .awBackground()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PrimaryButton("SUMMON YOUR PARTNER", large = true) {}
        PrimaryButton("Secondary", kind = AWButtonKind.secondary) {}
        PrimaryButton("Loading", isLoading = true) {}
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip(CaseStatus.summoned); StatusChip(CaseStatus.deliberating); StatusChip(CaseStatus.closed)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RoleChip(Role.plaintiff); RoleChip(Role.defendant) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusRibbon("In trial"); ClosedStamp(); ScalesMark(size = 32.dp)
        }
        Countdown(target = remember { Instant.now().plusSeconds(13_336) }, style = Countdown.Style.clock)
        Box(Modifier.awCard()) {
            RecordLine(
                mine = 7, partners = 5, me = Avatar.default,
                partner = Avatar(skin = 4, hair = 0, hairstyle = Avatar.Hairstyle.curly, top = 5, outfit = Avatar.Outfit.hoodie),
            )
        }
        SummonsSeal()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ExhibitTile(
                label = ExhibitLabel("A"), type = ExhibitType.receipt, caption = "The promise",
                body_ = "12 Sept, 21:14: said he'd be home by 9", ownerRole = Role.plaintiff, modifier = Modifier.weight(1f),
            )
            ExhibitTile(
                label = ExhibitLabel("B"), type = ExhibitType.text, caption = "Quote",
                body_ = "It's not cold, you're just dramatic", ownerRole = Role.defendant, modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.width(1.dp))
    }
}

