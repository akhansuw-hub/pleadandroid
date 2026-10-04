// Port of ArgueWin/Features/Onboarding/About/AboutYouTwoScreens.swift. "About you two" screens 1–5 (CONTRACTS-v2
// amendment bd, mockup docs/onboarding-about-brief/mockup.png).
//
//   hero      AboutHeroScene (pixel courtroom, one idle scene per screen), full width under the top bar
//   copy      ABOUT YOU TWO eyebrow · Fraunces title · "Pick one" / "Choose all that fit"
//   options   radio rows (1, 3, 4) · two-column icon tiles (2) · icon rows (5); selected = Court Burgundy, cream text
//   CTA       CONTINUE →, pinned above the navigation bar, disabled until answered (nothing preselected, no Skip)
//
// Motion (AboutMotion tokens): hero fades in, eyebrow → title → hint rise, options spring in 0.07 s apart, CTA last;
// a tap sweeps the burgundy fill from the leading edge, pops the radio dot / check, micro-springs the option and fires a
// light haptic; CONTINUE lifts from disabled to enabled with a one-off glint. The rail segment fills as the screen
// arrives (container top bar). Reduce Motion: opacity only (fades and crossfades), no sweep, pop, lift or glint.
//
// Android (short screens, 3-button navigation, large fonts; the PaywallFit approach): iOS scrolls the hero away with
// the content; here `AboutScaffold` gives the hero's height back first (down to `AboutMotion.heroMin`), then the
// content scrolls, and the CTA stays pinned and whole above the navigation bar.
package app.plead.android.features.onboarding.about

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.app.AppModel
import app.plead.android.app.PleadApplication
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.pleadShadow
import app.plead.android.features.onboarding.CourtLayerStyle
import app.plead.android.features.onboarding.CourtPrimaryButton
import app.plead.android.features.onboarding.OnboardingKitTokens
import app.plead.android.features.onboarding.OnboardingModel
import app.plead.android.features.onboarding.OnboardingPalette
import app.plead.android.features.onboarding.PleadPressStyle
import app.plead.android.features.onboarding.courtLayer
import app.plead.android.features.onboarding.onboardingModel
import app.plead.android.features.onboarding.pleadMicroSpring
import app.plead.android.features.onboarding.pleadPress
import kotlin.math.roundToInt

object AboutMotion {
    /** Options enter this far apart (after the hint). */
    const val optionStagger: Double = 0.07
    /** Fill sweep on select / clear on deselect. */
    const val sweepIn: Double = 0.30
    const val sweepOut: Double = 0.18
    /** Radio dot / check pop: `.spring(response: 0.30, dampingFraction: 0.52)`. */
    const val popResponse: Float = 0.30f
    const val popDamping: Float = 0.52f
    /** CONTINUE lifting from disabled to enabled: `.spring(response: 0.42, dampingFraction: 0.62)`. */
    const val ctaLiftResponse: Float = 0.42f
    const val ctaLiftDamping: Float = 0.62f
    const val ctaDrop: Float = 4f
    const val ctaDisabledScale: Float = 0.98f
    const val glint: Double = 0.65
    /** Rail segment fill as the screen arrives. */
    const val railFill: Double = 0.45
    const val railDelay: Double = 0.12

    val gutter: Dp = 20.dp
    val optionHeight: Dp = 52.dp
    val tileHeight: Dp = 78.dp
    val optionSpacing: Dp = 9.dp
    val radius: Dp = 14.dp

    /** Android: the least the hero gives back to before the content scrolls (the bench, judge and desks still read). */
    val heroMin: Dp = 112.dp

    /** `.timingCurve(0.22, 1, 0.36, 1)`. */
    val easeOutQuint = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
}

/** Light haptics of the six screens (iOS impact generators → `Vibrator` amplitude where available, view haptics otherwise). */
object AboutHaptics {
    /** An option tapped: `.impact(weight: .light, intensity: 0.7)`. */
    fun select(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** The oath hold's ticks: light then medium, intensity 0.35 → 1.0 with the fill `f`. */
    fun holdTick(view: View, f: Double) {
        val v = vibrator()
        val intensity = (0.35 + 0.65 * f).coerceIn(0.0, 1.0)
        if (v != null && v.hasAmplitudeControl()) {
            val ms = if (f < 0.5) 12L else 18L
            runCatching { v.vibrate(VibrationEffect.createOneShot(ms, (intensity * 255).roundToInt().coerceIn(1, 255))) }
        } else {
            view.performHapticFeedback(if (f < 0.5) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /** Seconds to the next hold tick at fill `f` (0.26 s → 0.10 s). */
    fun holdInterval(f: Double): Double = 0.26 - 0.16 * f

    /** The strike: a heavy impact. */
    fun strike(view: View) {
        val v = vibrator()
        if (v != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)) }.onFailure {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** The success notification after the strike. */
    fun success(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        else view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private fun vibrator(): Vibrator? {
        val context = PleadApplication.contextOrNull ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()?.takeIf { it.hasVibrator() }
    }
}

// MARK: - Fitting layout

/**
 * The hero height the screen can afford (pure; unit-tested): iOS's `AboutHeroLayout.height(forWidth:)`, less whatever
 * the content and the CTA need, never below `AboutMotion.heroMin` (past that the content scrolls instead).
 */
fun aboutHeroHeight(availableDp: Float, widthDp: Float, contentDp: Float, ctaDp: Float): Float {
    val ideal = AboutHeroLayout.height(widthDp)
    val free = availableDp - contentDp - ctaDp
    return free.coerceIn(minOf(AboutMotion.heroMin.value, ideal), ideal)
}

/**
 * Hero on top (full width), content under it (scrolls when it must), CTA pinned above the navigation bar. The content's
 * natural height is read through intrinsics, so the hero gives height back before anything scrolls.
 */
@Composable
fun AboutScaffold(
    hero: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    cta: @Composable ColumnScope.() -> Unit,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment.Horizontal = Alignment.Start,
) {
    val scroll = rememberScrollState()
    Layout(
        contents = listOf(
            { Box(Modifier.testTag("onboarding.about.heroFrame")) { hero() } },
            {
                // The content column gets the viewport as its minimum height (verticalScroll keeps the minimum, the
                // wrap drops only the width's), so a screen can centre a block in the space left (the oath's card).
                Column(
                    Modifier
                        .verticalScroll(scroll)
                        .fillMaxWidth()
                        .wrapContentWidth(Alignment.CenterHorizontally)
                        .widthIn(max = OnboardingKitTokens.Spacing.maxWidth)
                        .fillMaxWidth()
                        .padding(horizontal = AboutMotion.gutter)
                        .padding(bottom = 14.dp),
                    horizontalAlignment = contentAlignment,
                    content = content,
                )
            },
            {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier
                            .widthIn(max = OnboardingKitTokens.Spacing.maxWidth)
                            .fillMaxWidth()
                            .padding(horizontal = AboutMotion.gutter)
                            .padding(top = 10.dp, bottom = OnboardingKitTokens.Spacing.ctaBottom)
                            .navigationBarsPadding(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        content = cta,
                    )
                }
            },
        ),
        modifier = modifier,
    ) { (heroM, contentM, ctaM), constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val ctaP = ctaM.first().measure(Constraints(minWidth = w, maxWidth = w, maxHeight = h))
        val natural = contentM.first().maxIntrinsicHeight(w)
        val heroH = aboutHeroHeight(h / density, w / density, natural / density, ctaP.height / density).dp.roundToPx()
            .coerceIn(0, (h - ctaP.height).coerceAtLeast(0))
        val heroP = heroM.first().measure(Constraints.fixed(w, heroH))
        val contentH = (h - heroH - ctaP.height).coerceAtLeast(0)
        val contentP = contentM.first().measure(Constraints(minWidth = w, maxWidth = w, minHeight = contentH, maxHeight = contentH))
        layout(w, h) {
            heroP.place(0, 0)
            contentP.place(0, heroH)
            ctaP.place(0, h - ctaP.height)
        }
    }
}

// MARK: - Screen

@Composable
fun AboutQuestionScreen(app: AppModel, question: AboutQuestion) {
    AboutQuestionScreen(app.onboardingModel, question)
}

@Composable
fun AboutQuestionScreen(model: OnboardingModel, question: AboutQuestion) {
    val view = LocalView.current
    val canContinue = model.canAdvanceAboutYouTwo(question.step)
    AboutScaffold(
        modifier = Modifier.fillMaxSize().testTag("onboarding.about.${question.rawValue}"),
        hero = {
            AboutHeroScene(AboutHeroKind.forStep(question.step), Modifier.fillMaxSize().courtLayer(0, CourtLayerStyle.fade))
        },
        content = {
            AboutCopyBlock(AboutQuestion.eyebrow, question.title, question.hint, titleIdentifier = "onboarding.about.title")
            Spacer(Modifier.height(14.dp))
            AboutOptions(model, question) { AboutHaptics.select(view) }
        },
        cta = {
            AboutContinueButton(enabled = canContinue, layer = 3, delay = AboutMotion.optionStagger * question.options.size) {
                model.advance()
            }
        },
    )
}

@Composable
private fun AboutOptions(model: OnboardingModel, question: AboutQuestion, onTap: () -> Unit) {
    @Composable
    fun option(o: AboutQuestion.Option, index: Int, modifier: Modifier = Modifier) {
        AboutOptionButton(
            option = o, layout = question.layout, multi = question.isMultiSelect,
            selected = model.isSelected(o.id, question),
            identifier = "onboarding.about.${question.rawValue}.${o.id}",
            modifier = modifier.courtLayer(2, CourtLayerStyle.spring, delay = AboutMotion.optionStagger * (index + 1)),
        ) {
            model.select(o.id, question)
            onTap()
        }
    }
    when (question.layout) {
        AboutQuestion.Layout.radioRows, AboutQuestion.Layout.iconRows ->
            Column(verticalArrangement = Arrangement.spacedBy(AboutMotion.optionSpacing)) {
                question.options.forEachIndexed { i, o -> option(o, i) }
            }
        AboutQuestion.Layout.iconTiles ->
            Column(verticalArrangement = Arrangement.spacedBy(AboutMotion.optionSpacing)) {
                question.options.chunked(2).forEachIndexed { r, pair ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(AboutMotion.optionSpacing)) {
                        pair.forEachIndexed { c, o -> option(o, r * 2 + c, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
    }
}

// MARK: - Copy

/** Eyebrow · title · hint, entering one after another (layers 1, 1 + 0.06 s, 2). */
@Composable
fun AboutCopyBlock(
    eyebrow: String,
    title: String,
    hint: String? = null,
    center: Boolean = false,
    titleIdentifier: String = "onboarding.about.title",
) {
    val align = if (center) TextAlign.Center else TextAlign.Start
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (center) Alignment.CenterHorizontally else Alignment.Start) {
        Text(
            eyebrow,
            style = PleadType.labelCaps.copy(letterSpacing = PleadType.capsTracking.sp),
            color = OnboardingPalette.burgundy,
            textAlign = align,
            modifier = Modifier.padding(top = 16.dp).clearAndSetSemantics { }.courtLayer(1),
        )
        Text(
            title,
            style = PleadType.display(28f, weight = androidx.compose.ui.text.font.FontWeight.SemiBold, relativeTo = TextStyleKind.title)
                .let { it.copy(lineHeight = (it.fontSize.value * 1.19f - 2f).sp) },
            color = OnboardingPalette.wine,
            textAlign = align,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .testTag(titleIdentifier)
                .semantics { heading() }
                .courtLayer(1, delay = 0.06),
        )
        if (hint != null) {
            Text(
                hint,
                style = PleadType.metadata,
                color = OnboardingPalette.secondaryText,
                textAlign = align,
                modifier = Modifier.padding(top = 4.dp).courtLayer(2),
            )
        }
    }
}

// MARK: - Options

@Composable
fun AboutOptionButton(
    option: AboutQuestion.Option,
    layout: AboutQuestion.Layout,
    multi: Boolean,
    selected: Boolean,
    identifier: String,
    modifier: Modifier = Modifier,
    action: () -> Unit,
) {
    val reduceMotion = accessibilityReduceMotion()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val radius = if (layout == AboutQuestion.Layout.iconTiles) 16.dp else AboutMotion.radius
    val shape = RoundedCornerShape(radius)
    // The burgundy fill sweeps in from the leading edge (Reduce Motion: crossfades).
    val sweep by animateFloatAsState(
        if (selected) 1f else 0f,
        if (selected) tween((AboutMotion.sweepIn * 1000).toInt(), easing = AboutMotion.easeOutQuint)
        else tween((AboutMotion.sweepOut * 1000).toInt(), easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)),
        label = "aboutSweep",
    )
    val label by animateColorAsState(if (selected) OnboardingPalette.cream else OnboardingPalette.cocoa, tween(200), label = "aboutLabel")
    val pick = if (multi) {
        Modifier.toggleable(value = selected, interactionSource = interaction, indication = null, role = Role.Checkbox) { action() }
    } else {
        Modifier.selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.RadioButton) { action() }
    }
    val body: @Composable () -> Unit = {
        when (layout) {
            AboutQuestion.Layout.radioRows, AboutQuestion.Layout.iconRows -> Row(
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = if (layout == AboutQuestion.Layout.iconRows) AboutMotion.optionHeight + 2.dp else AboutMotion.optionHeight)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                AboutMarker(multi, selected, reduceMotion)
                option.icon?.let { AboutOptionIconView(it, size = 26.dp) }
                OptionTitle(option.title, label, TextAlign.Start, Modifier.weight(1f))
            }
            AboutQuestion.Layout.iconTiles -> Box(Modifier.fillMaxSize().defaultMinSize(minHeight = AboutMotion.tileHeight)) {
                Column(
                    Modifier.fillMaxWidth().align(Alignment.Center).padding(start = 10.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    option.icon?.let { AboutOptionIconView(it, size = 30.dp) }
                    OptionTitle(option.title, label, TextAlign.Center)
                }
                AboutMarker(multi, selected, reduceMotion, Modifier.padding(10.dp))
            }
        }
    }
    Box(
        modifier
            .pleadPress(pressed)
            .pleadMicroSpring(trigger = selected, active = selected)
            .pleadShadow(
                if (selected) OnboardingPalette.burgundy.copy(alpha = 0.22f) else OnboardingPalette.cocoa.copy(alpha = 0.04f),
                radius = if (selected) 7.dp else 3.dp, y = if (selected) 3.dp else 1.dp, shape = shape,
            )
            .clip(shape)
            .drawBehind {
                drawRect(OnboardingPalette.paper)
                if (reduceMotion) {
                    drawRect(OnboardingPalette.burgundy, alpha = sweep.coerceIn(0f, 1f))
                } else if (sweep > 0.001f) {
                    drawRect(OnboardingPalette.burgundy, size = Size(size.width * sweep.coerceIn(0f, 1f), size.height))
                }
            }
            .drawWithContent {
                drawContent()
                val stroke = 1.dp.toPx()
                val r = radius.toPx()
                drawRoundRect(
                    if (selected) OnboardingPalette.wine.copy(alpha = 0.6f) else OnboardingPalette.border,
                    topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(r - stroke / 2), style = Stroke(stroke),
                )
            }
            .then(pick)
            .semantics { contentDescription = option.title }
            .testTag(identifier),
    ) { body() }
}

@Composable
private fun OptionTitle(text: String, color: Color, align: TextAlign, modifier: Modifier = Modifier) {
    Text(
        text,
        style = PleadType.ui(16f, androidx.compose.ui.text.font.FontWeight.SemiBold, relativeTo = TextStyleKind.body),
        color = color,
        textAlign = align,
        modifier = modifier.clearAndSetSemantics { },
    )
}

/** Radio (pick one) or checkbox (choose all): the dot / check pops in on select. */
@Composable
private fun AboutMarker(multi: Boolean, selected: Boolean, reduceMotion: Boolean, modifier: Modifier = Modifier) {
    val spec = if (reduceMotion) tween<Float>(200, easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))
    else spring(dampingRatio = AboutMotion.popDamping, stiffness = PleadPressStyle.springStiffness(AboutMotion.popResponse))
    val on by animateFloatAsState(if (selected) 1f else 0f, spec, label = "aboutMarker")
    val ringColor by animateColorAsState(if (selected) OnboardingPalette.cream else OnboardingPalette.cocoa.copy(alpha = 0.35f), tween(200), label = "aboutRing")
    Canvas(modifier.size(22.dp).clearAndSetSemantics { }) {
        val line = 1.5.dp.toPx()
        val opacity = on.coerceIn(0f, 1f)
        if (multi) {
            val r = CornerRadius(5.dp.toPx())
            drawRoundRect(if (selected) OnboardingPalette.cream else OnboardingPalette.paper, cornerRadius = r)
            drawRoundRect(ringColor, topLeft = Offset(line / 2, line / 2), size = Size(size.width - line, size.height - line), cornerRadius = r, style = Stroke(line))
            // `Image(systemName: "checkmark")` 12 pt black, Court Burgundy.
            val s = if (reduceMotion) 1f else 0.2f + 0.8f * on
            if (opacity > 0f) scale(s) {
                val w = size.width
                val path = Path().apply {
                    moveTo(w * 0.27f, w * 0.52f)
                    lineTo(w * 0.44f, w * 0.68f)
                    lineTo(w * 0.74f, w * 0.33f)
                }
                drawPath(path, OnboardingPalette.burgundy, alpha = opacity, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        } else {
            drawCircle(ringColor, radius = size.minDimension / 2 - line / 2, style = Stroke(line))
            val s = if (reduceMotion) 1f else 0.1f + 0.9f * on
            if (opacity > 0f) drawCircle(OnboardingPalette.cream, radius = (size.minDimension / 2 - 5.5.dp.toPx()) * s, alpha = opacity)
        }
    }
}

// MARK: - CTA

/** CONTINUE →: disabled (dimmed, 4 dp lower, 98 %) until answered, then lifts into place with a spring and one glint. */
@Composable
fun AboutContinueButton(enabled: Boolean, modifier: Modifier = Modifier, layer: Int = 3, delay: Double = 0.0, action: () -> Unit) {
    val reduceMotion = accessibilityReduceMotion()
    val lift by animateFloatAsState(
        if (enabled || reduceMotion) 0f else 1f,
        if (reduceMotion) tween(200) else spring(dampingRatio = AboutMotion.ctaLiftDamping, stiffness = PleadPressStyle.springStiffness(AboutMotion.ctaLiftResponse)),
        label = "aboutCtaLift",
    )
    val glint = remember { Animatable(-1f) }
    var seen by remember { mutableIntStateOf(0) }
    LaunchedEffect(enabled) {
        seen += 1
        if (!enabled || reduceMotion || seen == 1) return@LaunchedEffect
        glint.snapTo(-0.4f)
        kotlinx.coroutines.delay(120)
        glint.animateTo(1.1f, tween((AboutMotion.glint * 1000).toInt(), easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)))
    }
    CourtPrimaryButton(
        title = "CONTINUE →",
        identifier = "onboarding.about.continue",
        enabled = enabled,
        action = action,
        modifier = modifier
            .padding(top = 0.dp)
            .courtLayer(layer, delay = delay)
            .graphicsLayer {
                translationY = (AboutMotion.ctaDrop * lift).dp.toPx()
                val s = 1f - (1f - AboutMotion.ctaDisabledScale) * lift
                scaleX = s
                scaleY = s
            }
            .drawWithContent {
                drawContent()
                // One light glint across the button as it becomes available.
                val g = glint.value
                if (g > -1f && g < 1.1f) {
                    val w = size.width * 0.35f
                    val x = g * size.width
                    val r = OnboardingKitTokens.Radius.button.toPx()
                    drawRoundRect(
                        Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.28f), Color.Transparent), startX = x, endX = x + w),
                        cornerRadius = CornerRadius(r),
                    )
                }
            },
    )
}

// MARK: - Six-segment rail

/**
 * The "About you two" rail in the top bar (amendment bd): six capsules, done ones Court Burgundy, upcoming hairline
 * border colour; the current segment fills from the leading edge as its screen arrives (and drains when going back past it).
 */
@Composable
fun AboutProgressRail(index: Int, modifier: Modifier = Modifier, count: Int = 6) {
    val reduceMotion = accessibilityReduceMotion()
    val spec = if (reduceMotion) tween<Float>((OnboardingKitTokens.Motion.fade * 1000).toInt(), easing = CubicBezierEasing(0f, 0f, 0.58f, 1f))
    else tween((AboutMotion.railFill * 1000).toInt(), easing = AboutMotion.easeOutQuint)
    // `shown` starts one behind on the first appearance, then fills to `index` after `railDelay`.
    var shown by remember { mutableIntStateOf(index - 1) }
    var appeared by remember { mutableIntStateOf(0) }
    LaunchedEffect(index) {
        if (appeared == 0) {
            appeared = 1
            kotlinx.coroutines.delay((AboutMotion.railDelay * 1000).toLong())
        }
        shown = index
    }
    Row(
        modifier
            .widthIn(max = 260.dp)
            .fillMaxWidth()
            .height(5.dp)
            .testTag(AboutProgressRail.identifier)
            .clearAndSetSemantics {
                contentDescription = "Progress"
                stateDescription = AboutProgressRail.accessibilityText(index, count)
            },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(count) { i ->
            val fill by animateFloatAsState(if (i <= shown) 1f else 0f, spec, label = "aboutRail$i")
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(50))
                    .background(OnboardingPalette.border)
                    .drawBehind {
                        if (reduceMotion) {
                            drawRect(OnboardingPalette.burgundy, alpha = fill.coerceIn(0f, 1f))
                        } else if (fill > 0.001f) {
                            drawRoundRect(OnboardingPalette.burgundy, size = Size(size.width * fill, size.height), cornerRadius = CornerRadius(size.height / 2))
                        }
                    },
            )
        }
    }
}

object AboutProgressRail {
    const val identifier = "onboarding.about.progress"
    fun accessibilityText(index: Int, count: Int = 6): String = "Question ${minOf(index, count - 1) + 1} of $count"
}
