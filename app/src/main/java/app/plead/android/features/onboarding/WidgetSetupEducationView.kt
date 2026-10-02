// Port of ArgueWin/Features/Onboarding/WidgetSetupEducationView.swift.
// MARK: 9 · Widgets & Live Activities (CONTRACTS-v2 amendments r + ak)
//
// Education, not a permission: widgets are placed by the user, so SHOW ME HOW opens setup guidance
// (`WidgetSetupInstructionsSheet`) and NOT NOW simply moves on. When the app resumes while this screen (or its
// sheet) is up, the launcher is asked whether a Plead widget now exists (`WidgetSetupService`); if so a small success
// line replaces the CTAs. There is no fake system dialog anywhere on this screen.
//
// Android (PORT.md §2, amendment az): widgets are added from the Home Screen's widget picker (touch and hold an empty
// spot → Widgets → Plead), and Live Activities are replaced by the ongoing "court in session" notification, called
// "live updates" in the copy; its availability is whether Plead's notifications are enabled. Only the strings that
// describe iOS mechanics changed (see STATUS.md, wave 3b); everything else is verbatim.
package app.plead.android.features.onboarding

import kotlin.math.roundToInt
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.layout.layout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.designsystem.pleadShadow
import app.plead.android.services.CourtSessionPhase
import app.plead.android.services.CourtSessionState
import app.plead.android.services.PleadActivityCopy
import app.plead.android.services.PleadCaseActivityAttributes
import app.plead.android.services.WidgetPrivacyMode
import app.plead.android.services.WidgetSnapshot
import app.plead.android.services.WidgetState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch
import app.plead.android.designsystem.Color as HexColor
import app.plead.android.widgets.PixelJudgeGlyph
import app.plead.android.widgets.PixelJudgeGlyphCanvas
import app.plead.android.widgets.PleadWidgetPalette
import app.plead.android.widgets.WidgetFitText

object WidgetsCopy {
    /** iOS "Widgets & Live Activities". */
    const val eyebrow = "Widgets & Live Updates"
    const val headline = "Court follows you."
    const val subtitle = "Keep summons, verdicts and active cases in view without opening Plead."
    /** iOS "Live Activities" → "Live Updates" (the ongoing court-in-session notification). */
    val pills = listOf("Lock Screen", "Home Screen", "Live Updates")
    /** iOS "You add widgets yourself, from the Home Screen or Lock Screen." */
    const val widgetsNote = "You add widgets yourself: touch and hold an empty spot on your Home Screen, then tap Widgets."
    /** iOS "Live Activities are on. …". */
    const val liveActivitiesOn = "Live updates are on. Summons and verdicts can appear on your Lock Screen."
    /** iOS "Live Activities are off for Plead. …". */
    const val liveActivitiesOff = "Live updates are off for Plead. You can turn them on in Settings."

    fun availability(liveActivitiesEnabled: Boolean): String =
        "$widgetsNote ${if (liveActivitiesEnabled) liveActivitiesOn else liveActivitiesOff}"
}

/**
 * Motion (amendment ak): header, then the device rises into view, the live update appears inside it, the
 * Home Screen widget floats in last; pills and the availability line follow, the CTAs settle. Nothing loops.
 */
@Composable
fun WidgetSetupEducationView(app: AppModel, modifier: Modifier = Modifier) {
    val model = app.onboardingModel
    val scope = rememberCoroutineScope()
    // DEBUG `AWWidgetSheet`: open the sheet on arrival.
    var showingInstructions by rememberSaveable { mutableStateOf(DemoHarness.isDemo && DemoHarness.widgetSheet != null) }

    LaunchedEffect(Unit) { model.widgetEducationAppeared() }
    // Back from the Home Screen (the sheet may still be up): did they add one?
    var firstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (firstResume) firstResume = false else scope.launch { model.refreshWidgetSetup() }
        onPauseOrDispose { }
    }

    OnboardingShell(
        modifier = modifier,
        hero = {
            PermissionScreenHeader(eyebrow = WidgetsCopy.eyebrow, headline = WidgetsCopy.headline, subtitle = WidgetsCopy.subtitle)
        },
        content = {
            item { WidgetEducationPreview() }
            item {
                WidgetSurfacePills(Modifier.pleadReveal(PleadRevealKind.body, index = 3, delay = OnboardingMotionTokens.cardDelay))
            }
            item {
                Text(
                    WidgetsCopy.availability(model.liveActivitiesEnabled),
                    style = PleadType.metadata,
                    color = OnboardingPalette.secondaryText,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().pleadReveal(PleadRevealKind.body, index = 4, delay = OnboardingMotionTokens.cardDelay),
                )
            }
            if (model.widgetSetupComplete) {
                item {
                    AnimatedVisibility(visible = true, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) { WidgetAddedRow() }
                }
            }
        },
        cta = {
            if (model.widgetSetupComplete) {
                CourtPrimaryButton(title = "Continue", identifier = "onboarding.widgets.continue") { model.advance() }
            } else {
                CourtPrimaryButton(title = "Show me how", identifier = "onboarding.widgets.showMe") { showingInstructions = true }
                OnboardingSecondaryButton("Not now") { model.skipWidgetEducation() }
            }
        },
    )

    if (showingInstructions) {
        WidgetSetupInstructionsSheet(app, onDismiss = { showingInstructions = false })
    }
}

/** LOCK SCREEN · HOME SCREEN · LIVE UPDATES. Wraps to a column when the text is large (SwiftUI `ViewThatFits`). */
@Composable
private fun WidgetSurfacePills(modifier: Modifier = Modifier) {
    val spacing = PleadSpacing.s
    Layout(
        content = {
            for (label in WidgetsCopy.pills) {
                Text(
                    label.uppercase(),
                    style = PleadType.labelCaps,
                    color = OnboardingPalette.burgundy,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .background(OnboardingPalette.parchment, CircleShape)
                        .border(1.dp, OnboardingPalette.border, CircleShape)
                        .padding(horizontal = PleadSpacing.s + 2.dp, vertical = PermissionScreenTokens.pillVertical),
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = "Works on the Lock Screen, the Home Screen and as live updates." },
    ) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val rowWidth = placeables.sumOf { it.width } + gap * (placeables.size - 1).coerceAtLeast(0)
        val width = constraints.maxWidth
        if (rowWidth <= width) {
            val height = placeables.maxOfOrNull { it.height } ?: 0
            layout(width, height) {
                var x = (width - rowWidth) / 2
                placeables.forEach { p ->
                    p.place(x, (height - p.height) / 2)
                    x += p.width + gap
                }
            }
        } else {
            val height = placeables.sumOf { it.height } + gap * (placeables.size - 1).coerceAtLeast(0)
            layout(width, height) {
                var y = 0
                placeables.forEach { p ->
                    p.place((width - p.width) / 2, y)
                    y += p.height + gap
                }
            }
        }
    }
}

/** A widget was detected after the setup guidance. */
@Composable
private fun WidgetAddedRow(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(OnboardingRadius.card)
    Row(
        modifier
            .fillMaxWidth()
            .background(OnboardingPalette.paper, shape)
            .border(1.5.dp, OnboardingPalette.gold, shape)
            .padding(PleadSpacing.m + 2.dp)
            .clearAndSetSemantics { contentDescription = "Widget added. Plead will keep the court close." },
        horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(SFSymbol.icon("checkmark.circle.fill"), contentDescription = null, tint = OnboardingPalette.gold, modifier = Modifier.size(20.dp))
        Text(
            "Widget added. Plead will keep the court close.",
            style = TextStyle(fontSize = TextStyleKind.subheadline.defaultSize.sp, fontWeight = FontWeight.Bold),
            color = OnboardingPalette.wine,
            modifier = Modifier.weight(1f),
        )
    }
}

// MARK: - The preview (illustrative)

/**
 * A large Lock Screen mockup with the summons live update (the same layout as the court-session notification /
 * iOS `PleadActivityLockScreenView`) under the clock, and a small Home Screen widget overlapping its edge for depth.
 * Rendered at the real sizes, then scaled down, so it matches what people will actually see.
 * Entrance: the device rises, the live update appears inside it, the widget floats in last.
 */
object WidgetEducationPreview {
    /** The banner's size at real size (two-line headline). */
    val activityRealWidth: Dp = 374.dp
    val activityHeight: Dp = 112.dp
    val activityScale: Float
        get() = (PermissionScreenTokens.phoneWidth - (PermissionScreenTokens.phoneBezel + PleadSpacing.s) * 2) / activityRealWidth
    val widgetRealSize: Dp = 170.dp

    /**
     * The composition is laid out at its iOS size (320 × 318) and scaled down to the width it is offered when that is
     * narrower (phones under ~368 dp: 320 dp of hero inside the 24 dp gutters), keeping its proportions. iOS never
     * needs it (its narrowest phone offers 342 pt); Android's 360 dp and smaller phones do.
     */
    fun heroScale(availableWidthPx: Int, heroWidthPx: Int): Float =
        if (heroWidthPx <= 0 || availableWidthPx >= heroWidthPx) 1f else availableWidthPx.toFloat() / heroWidthPx

    const val accessibilityLabel =
        "Preview: a Plead live update on the Lock Screen saying you've been summoned, and a small Plead widget on the Home Screen."
}

@Composable
fun WidgetEducationPreview(modifier: Modifier = Modifier) {
    val T = PermissionScreenTokens
    // Fixed reference moment so the countdown reads the same on every launch (never ticks: no looping motion).
    val now = remember { Instant.now() }
    val base = LocalDensity.current
    // `.environment(\.dynamicTypeSize, .large)`: the mockup ignores the system font scale.
    CompositionLocalProvider(LocalDensity provides Density(base.density, 1f)) {
        Box(
            modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = WidgetEducationPreview.accessibilityLabel
                    role = Role.Image
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.heroFit(T.heroWidth, T.heroHeight)) {
                LockScreenMock(now, Modifier.pleadReveal(PleadRevealKind.card, index = 0))
                val w = WidgetEducationPreview.widgetRealSize * T.homeWidgetScale
                HomeWidgetMock(
                    now,
                    Modifier
                        .offset(x = T.heroWidth - w, y = T.homeWidgetTop)
                        .pleadReveal(PleadRevealKind.card, index = 2, offset = T.widgetFloat),
                )
            }
        }
    }
}

@Composable
private fun LockScreenMock(now: Instant, modifier: Modifier = Modifier) {
    val T = PermissionScreenTokens
    val shape = RoundedCornerShape(T.phoneRadius)
    val dateFormat = remember {
        val locale = Locale.getDefault()
        val pattern = runCatching { android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM") }.getOrNull() ?: "EEEE d MMMM"
        DateTimeFormatter.ofPattern(pattern, locale)
    }
    Column(
        modifier
            .size(T.phoneWidth, T.phoneHeight)
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.22f), radius = 14.dp, y = 8.dp, shape = shape)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(PleadWidgetPalette.deepWine, HexColor(hex = 0x24141A))), shape)
            .border(T.phoneBezel, HexColor(hex = 0x1B1012), shape),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(Modifier.padding(top = PleadSpacing.s + 2.dp).size(70.dp, 20.dp).background(Color.Black, CircleShape))
        Text(
            dateFormat.format(now.atZone(ZoneId.systemDefault())),
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(top = PleadSpacing.s),
        )
        Text(
            "9:41",
            style = TextStyle(fontSize = 54.sp, fontWeight = FontWeight.Bold),
            color = Color.White.copy(alpha = 0.92f),
            modifier = Modifier.padding(bottom = PleadSpacing.s),
        )
        LiveActivityMock(now, Modifier.pleadReveal(PleadRevealKind.card, index = 1, scale = 0.96f))
        Spacer(Modifier.weight(1f))
        Row(Modifier.fillMaxWidth().padding(horizontal = PleadSpacing.l + 2.dp).padding(bottom = PleadSpacing.l)) {
            LockButton("flashlight.off.fill")
            Spacer(Modifier.weight(1f))
            LockButton("camera.fill")
        }
    }
}

@Composable
private fun LockButton(symbol: String) {
    Box(Modifier.size(36.dp).background(Color.White.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
        Icon(permissionSymbol(symbol), contentDescription = null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(16.dp))
    }
}

/** The live update banner at its real width, scaled into the mock. */
@Composable
private fun LiveActivityMock(now: Instant, modifier: Modifier = Modifier) {
    val scale = WidgetEducationPreview.activityScale
    val realW = WidgetEducationPreview.activityRealWidth
    val realH = WidgetEducationPreview.activityHeight
    val attributes = PleadCaseActivityAttributes(caseId = WidgetSnapshot.sampleCaseId, caseNumber = 21, kind = PleadCaseActivityAttributes.Kind.summons)
    val state = PleadActivityCopy.state(CourtSessionPhase.summoned, deadlineAt = now.plusSeconds(23 * 3600L + 59 * 60L + 10))
    Box(modifier.size(realW * scale, realH * scale), contentAlignment = Alignment.Center) {
        OnboardingActivityBanner(
            attributes, state, now,
            Modifier
                .requiredSize(realW, realH)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(22.dp / scale))
                .background(PleadWidgetPalette.warmCream),
        )
    }
}

/**
 * The small Home Screen widget at its real size (170 pt), scaled. A faithful copy of `SmallWidgetView`
 * (Shared/WidgetViews/SmallWidgetView.swift): the Glance widget itself (wave 3f) can't be composed in the app.
 */
@Composable
private fun HomeWidgetMock(now: Instant, modifier: Modifier = Modifier) {
    val size = WidgetEducationPreview.widgetRealSize
    val scale = PermissionScreenTokens.homeWidgetScale
    val snapshot = remember(now) { WidgetSnapshot.sample(WidgetState.summoned, WidgetPrivacyMode.detailed, now) }
    val shape = RoundedCornerShape(26.dp)
    Box(
        modifier
            .size(size * scale)
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.25f), radius = 12.dp, y = 6.dp, shape = RoundedCornerShape(26.dp * scale)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .requiredSize(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(shape)
                .background(PleadWidgetPalette.warmCream, shape)
                .border(3.dp, Color.White.copy(alpha = 0.9f), shape)
                .padding(PleadSpacing.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically),
        ) {
            PixelJudgeGlyphCanvas(PixelJudgeGlyph.Kind.bench, size = 70.dp)
            Box(Modifier.fillMaxWidth().padding(horizontal = 6.dp).height(1.5.dp).background(PleadWidgetPalette.romanceBlush))
            val p = snapshot.primary
            if (p != null) {
                // `.lineLimit(1).minimumScaleFactor(0.75)`, as the real small widget (widgets/WidgetTextFit).
                WidgetFitText(
                    p.caseTitle,
                    style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 15.sp),
                    color = PleadWidgetPalette.deepWine,
                    minScale = 0.75f,
                )
                Row(
                    Modifier.background(PleadWidgetPalette.chipFill, CircleShape).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PixelJudgeGlyphCanvas(PixelJudgeGlyph.Kind.gavel, size = 12.dp)
                    Text(
                        WidgetSnapshot.statusChip(p.state),
                        style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = PleadWidgetPalette.courtBurgundy,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The lock-screen live update layout, mirrored from the court-session notification / iOS `PleadActivityLockScreenView`:
 * burgundy accent bar, pixel Judge Wigsworth, deep-wine headline, case number, generic detail and a (frozen) plea
 * countdown. Built from the same shared pieces (`PixelJudgeGlyph`, `PleadWidgetPalette`, `PleadActivityCopy`).
 */
@Composable
fun OnboardingActivityBanner(
    attributes: PleadCaseActivityAttributes,
    state: CourtSessionState,
    now: Instant,
    modifier: Modifier = Modifier,
) {
    Row(modifier) {
        Box(Modifier.width(6.dp).fillMaxHeight().background(PleadWidgetPalette.courtBurgundy))
        Row(
            Modifier.weight(1f).fillMaxHeight().padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PixelJudgeGlyphCanvas(PixelJudgeGlyph.Kind.judge, size = 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    state.headline.uppercase(),
                    style = TextStyle(fontSize = TextStyleKind.headline.defaultSize.sp, fontWeight = FontWeight.Black),
                    color = PleadWidgetPalette.deepWine,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Case #%03d".format(attributes.caseNumber),
                    style = TextStyle(fontSize = TextStyleKind.caption.defaultSize.sp, fontWeight = FontWeight.Bold),
                    color = PleadWidgetPalette.courtBurgundy,
                )
                state.detail?.let { detail ->
                    Text(
                        detail,
                        style = TextStyle(fontSize = TextStyleKind.subheadline.defaultSize.sp, fontWeight = FontWeight.Medium),
                        color = PleadWidgetPalette.darkCocoa,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val deadline = state.deadlineAt
            val label = PleadActivityCopy.countdownLabel(state.phase)
            if (deadline != null && label != null) {
                Column(Modifier.widthIn(min = 96.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        label,
                        style = TextStyle(fontSize = TextStyleKind.caption2.defaultSize.sp, fontWeight = FontWeight.SemiBold),
                        color = PleadWidgetPalette.darkCocoa.copy(alpha = 0.8f),
                    )
                    Text(
                        OnboardingActivityBanner.frozenTimer(now, deadline),
                        style = TextStyle(fontSize = TextStyleKind.headline.defaultSize.sp, fontWeight = FontWeight.ExtraBold).monospacedDigit(),
                        color = PleadWidgetPalette.courtBurgundy,
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
    }
}

object OnboardingActivityBanner {
    /** SwiftUI `Text(timerInterval: now...deadline, pauseTime: now, countsDown: true)`: "23:59:10". */
    fun frozenTimer(now: Instant, deadline: Instant): String {
        val s = Duration.between(now, deadline).seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}

/** Lays the hero out at [width] × [height] and scales it (both axes, about its centre) to fit a narrower width. */
private fun Modifier.heroFit(width: Dp, height: Dp): Modifier = layout { measurable, constraints ->
    val w = width.roundToPx()
    val h = height.roundToPx()
    val scale = if (constraints.hasBoundedWidth) WidgetEducationPreview.heroScale(constraints.maxWidth, w) else 1f
    val placeable = measurable.measure(Constraints.fixed(w, h))
    val outW = (w * scale).roundToInt()
    val outH = (h * scale).roundToInt()
    layout(outW, outH) {
        placeable.placeWithLayer((outW - w) / 2, (outH - h) / 2) {
            scaleX = scale
            scaleY = scale
        }
    }
}
