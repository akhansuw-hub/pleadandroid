// Port of ArgueWin/Features/Onboarding/OnboardingContainer.swift. Onboarding visual system (docs/onboarding-brief,
// flow-overview-a.png): the same palette as the paywall that follows it, so onboarding → gate reads as one piece.
// Shape rule: actions 16 · cards 20 · inputs 14 · numbered markers 12 · avatar tiles are circles.
// Colour rule: Court Burgundy is the one accent (CTAs, selection, progress); gold only for markers,
// the docket and the closing line; blush only in illustrations.
package app.plead.android.features.onboarding

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.plead.android.app.AppModel
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.push.rememberRequestReview
import app.plead.android.R
import app.plead.android.designsystem.Color
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.SFSymbol
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.pleadShadow
import app.plead.android.features.paywall.PaywallPalette

object OnboardingPalette {
    // `PaywallPalette` (Features/Paywall/PaywallPalette.swift) values, inlined so onboarding does not depend on wave 3c.
    val burgundy = Color(hex = 0x7C3042)       // PaywallPalette.courtBurgundy
    val wine = Color(hex = 0x541F2C)           // PaywallPalette.deepWine
    val mahogany = Color(hex = 0x704735)       // PaywallPalette.mahogany
    val cocoa = Color(hex = 0x3B2425)          // PaywallPalette.darkCocoa
    val cream = Color(hex = 0xFFF6ED)          // PaywallPalette.warmCream
    val paper = Color(hex = 0xFFFDFC)          // PaywallPalette.paperWhite
    val parchment = Color(hex = 0xF3E4D6)      // PaywallPalette.parchment
    val blush = Color(hex = 0xEAA0A4)          // PaywallPalette.romanceBlush
    val gold = Color(hex = 0xCA9858)           // PaywallPalette.courtGold
    val goldLight = Color(hex = 0xF3C76A)      // PaywallPalette.goldLight
    val border = Color(hex = 0xE9D5C6)
    val secondaryText = Color(hex = 0x3B2425, opacity = 0.74f)
    // Amendment ak (onboarding redesign 2.0) additions.
    /** Soft rose accent (the second step / side of a case). */
    val rose = Color(hex = 0xC66C78)           // PaywallPalette.softRose
    val coral = Color(hex = 0xE85E68)          // PaywallPalette.coral
}

object OnboardingRadius {
    val action: Dp = 16.dp
    val card: Dp = 20.dp
    val input: Dp = 14.dp
    val marker: Dp = 12.dp
}

// MARK: - Progress

/** Continuous progress bar (Settings' setup card). Onboarding itself uses `OnboardingProgressRail`. */
@Composable
fun OnboardingProgressBar(progress: Double, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState(progress.toFloat(), tween(300, easing = androidx.compose.animation.core.CubicBezierEasing(0f, 0f, 0.58f, 1f)), label = "progress")
    BoxWithConstraints(
        modifier.fillMaxWidth().height(8.dp).semantics(mergeDescendants = true) {
            contentDescription = "Setup progress"
            stateDescription = "${Math.round(progress * 100)} percent"
        },
    ) {
        val full = maxWidth
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(OnboardingPalette.parchment))
        Box(
            Modifier.width(maxOf(10.dp, full * animated)).fillMaxHeight().clip(RoundedCornerShape(50)).background(OnboardingPalette.burgundy),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Box(Modifier.padding(end = 1.dp).size(6.dp).background(OnboardingPalette.gold, CircleShape))
        }
    }
}

// MARK: - Page scaffold

/**
 * A scrolling page with its CTAs pinned above the navigation bar (and the keyboard). Short content can be
 * vertically centred in the space above the CTAs, like the mockups.
 */
@Composable
fun OnboardingPage(
    modifier: Modifier = Modifier,
    spacing: Dp = PleadSpacing.xl,
    centered: Boolean = false,
    /** When the CTAs settle (inside the onboarding container only; tappable from the first frame). */
    footerDelay: Double = OnboardingMotionTokens.ctaDelay,
    content: @Composable ColumnScope.() -> Unit,
    footer: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().imePadding()) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val minHeight = maxHeight
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(
                    Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .heightIn(min = minHeight)
                        .padding(horizontal = PleadSpacing.xl)
                        .padding(top = PleadSpacing.m, bottom = PleadSpacing.l),
                    verticalArrangement = if (centered) Arrangement.spacedBy(spacing, Alignment.CenterVertically) else Arrangement.spacedBy(spacing),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = content,
                )
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(
                Modifier
                    .onboardingFooterReveal(footerDelay)
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .padding(horizontal = PleadSpacing.xl)
                    .padding(vertical = PleadSpacing.s)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(PleadSpacing.m),
                content = footer,
            )
        }
    }
}

/**
 * Onboarding screens reveal their CTAs last (never blocking: they hit-test from the first frame).
 * Other users of `OnboardingPage` (Secure account) sit outside the container and stay static.
 */
@Composable
private fun Modifier.onboardingFooterReveal(delay: Double): Modifier =
    if (LocalPleadRevealID.current != null) pleadReveal(PleadRevealKind.cta, delay = delay - OnboardingMotionTokens.ctaDelay) else this

/** Screen title: one display size per screen, Deep Wine, rounded heavy, Dynamic Type. */
@Composable
fun OnboardingTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TextStyle(fontSize = TextStyleKind.title.defaultSize.sp, fontWeight = FontWeight.ExtraBold),
        color = OnboardingPalette.wine,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().semantics { heading() }.testTag("onboarding.title"),
    )
}

@Composable
fun OnboardingSubtitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TextStyle(fontSize = TextStyleKind.body.defaultSize.sp, fontWeight = FontWeight.Medium),
        color = OnboardingPalette.secondaryText,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

/** Small uppercase form label ("YOUR NAME"). */
@Composable
fun OnboardingFieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = TextStyle(fontSize = TextStyleKind.caption.defaultSize.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp),
        color = OnboardingPalette.cocoa.copy(alpha = 0.7f),
        modifier = modifier.fillMaxWidth().semantics { heading() },
    )
}

/** Gold-dot bullet line (permission benefits). */
@Composable
fun OnboardingBullet(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(PleadSpacing.m)) {
        Box(Modifier.padding(top = 6.dp).size(9.dp).background(OnboardingPalette.gold, CircleShape))
        Text(
            text,
            style = TextStyle(fontSize = TextStyleKind.callout.defaultSize.sp, fontWeight = FontWeight.SemiBold),
            color = OnboardingPalette.cocoa,
            modifier = Modifier.weight(1f),
        )
    }
}

// MARK: - Buttons

/** The shared branded CTA: Court Burgundy, cream uppercase label, `PleadPressStyle` press (0.97, spring back). */
@Composable
fun OnboardingPrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    trailingArrow: Boolean = false,
    isLoading: Boolean = false,
    /** UI-test identifier (screens with their own id pass it, e.g. `onboarding.summonsIntro.continue`). */
    identifier: String = "onboarding.primary",
    enabled: Boolean = true,
    action: () -> Unit,
) {
    OnboardingButtonStyle(
        kind = OnboardingButtonStyle.Kind.primary,
        enabled = enabled && !isLoading,
        onClick = action,
        modifier = modifier.semantics { contentDescription = title.swiftCapitalized() }.testTag(identifier),
    ) {
        if (isLoading) {
            CircularProgressIndicator(color = OnboardingPalette.cream, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        } else {
            Text(title.uppercase())
            if (trailingArrow) Icon(SFSymbol.icon("arrow.right"), contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/** Quiet secondary: paper white with a hairline, dark cocoa label. */
@Composable
fun OnboardingSecondaryButton(
    title: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    action: () -> Unit,
) {
    OnboardingButtonStyle(
        kind = OnboardingButtonStyle.Kind.secondary,
        enabled = enabled && !isLoading,
        onClick = action,
        modifier = modifier.semantics { contentDescription = title.swiftCapitalized() }.testTag("onboarding.secondary"),
    ) {
        if (isLoading) CircularProgressIndicator(color = OnboardingPalette.burgundy, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        else Text(title.uppercase())
    }
}

/** Swift `OnboardingButtonStyle`: the rounded-rect label with press, disabled and shadow states. */
object OnboardingButtonStyle {
    enum class Kind { primary, secondary }

    val labelStyle: TextStyle = TextStyle(
        fontSize = TextStyleKind.headline.defaultSize.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, textAlign = TextAlign.Center,
    )
}

@Composable
fun OnboardingButtonStyle(
    kind: OnboardingButtonStyle.Kind,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Primary fill (Court Burgundy; the Welcome hero button uses Deep Wine, per the welcome brief). */
    fill: Color = OnboardingPalette.burgundy,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(OnboardingRadius.action)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val primary = kind == OnboardingButtonStyle.Kind.primary
    Box(
        modifier
            .pleadPress(pressed)
            .graphicsLayer { alpha = if (enabled) 1f else 0.42f }
            .pleadShadow(if (primary && enabled) fill.copy(alpha = 0.22f) else Color.Transparent, radius = 8.dp, y = 4.dp, shape = shape)
            .fillMaxWidth()
            .defaultMinSize(minHeight = if (primary) 56.dp else 52.dp)
            .clip(shape)
            .background(if (primary) fill else OnboardingPalette.paper, shape)
            .then(if (!primary) Modifier.border(1.dp, OnboardingPalette.border, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = PleadSpacing.l),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides (if (primary) OnboardingPalette.cream else OnboardingPalette.cocoa),
            androidx.compose.material3.LocalTextStyle provides OnboardingButtonStyle.labelStyle.copy(color = if (primary) OnboardingPalette.cream else OnboardingPalette.cocoa),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(PleadSpacing.s), verticalAlignment = Alignment.CenterVertically) { content() }
        }
    }
}

// MARK: - Hero

/**
 * Reusable pixel-art container: nearest-neighbour scaling (never blurred), rounded card crop.
 * The asset is replaceable (`OnboardingCourtroomHero` → `R.drawable.onboarding_courtroom_hero`) without layout changes.
 */
@Composable
fun PixelHeroView(
    height: Dp,
    modifier: Modifier = Modifier,
    imageRes: Int = R.drawable.onboarding_courtroom_hero,
    accessibilityText: String = "A pixel-art courtroom: the judge at the bench, a couple at the two stands, spectators in the gallery.",
) {
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .pleadShadow(OnboardingPalette.cocoa.copy(alpha = 0.12f), radius = 12.dp, y = 6.dp, shape = shape)
            .clip(shape)
            .border(1.dp, OnboardingPalette.mahogany.copy(alpha = 0.25f), shape)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityText
                role = Role.Image
            },
    ) {
        Image(
            bitmap = ImageBitmap.imageResource(imageRes),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// MARK: - Share sheet

/**
 * The system share sheet (iOS `UIActivityViewController`), presented once when this enters the composition; the
 * invite step advances when it closes. Android's chooser does not report whether a target was picked, so
 * [onComplete] always receives `false` (every Swift call site ignores the flag).
 */
@Composable
fun ActivityShareSheet(items: List<Any>, onComplete: (completed: Boolean) -> Unit = {}) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        onComplete(result.resultCode == Activity.RESULT_OK)
    }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val text = items.map { it.toString() }.distinct().fold("") { acc, s -> if (acc.contains(s)) acc else if (acc.isEmpty()) s else "$acc\n$s" }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { launcher.launch(Intent.createChooser(send, null)) }.onFailure {
            runCatching { context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            onComplete(false)
        }
    }
}

// MARK: - Container

/**
 * Owns the flow chrome: the persistent progress rail, back (chevron + the system back gesture), and the page
 * transition (`OnboardingPageTransition`: outgoing 12 pt left + fade, incoming from 16 pt right, 0.34 s; back
 * mirrored; a plain crossfade under Reduce Motion). The rail sits outside the transitioning page, so only its active
 * gold segment moves. Each entry gets a fresh `LocalPleadRevealID`: the screen's layers assemble once per entry.
 * Nothing is disabled while a page or a reveal animates. Amendment ak: the top bar is the court progress rail
 * (`CourtProgressRail`), shown on every screen including Welcome (where back is hidden).
 * Android: iOS's left-edge swipe back is the system back gesture (`BackHandler`, only while back is offered).
 */
object OnboardingContainer {
    /** Top-bar height reserved on every screen (under the status bar). */
    val chromeHeight: Dp = 44.dp

    /**
     * Screens that draw edge to edge under the status bar and the top bar (amendment ai: the summons explainer's
     * courtroom hero). They get no top padding and are not clipped to the safe area; they keep their own text
     * clear of the chrome. The top bar turns cream over their dark art.
     */
    fun isFullBleed(step: OnboardingStep): Boolean = step == OnboardingStep.summonsIntro

    /** Amendment ak: every screen, Welcome included, carries the court progress rail (Welcome has no back). */
    @Suppress("UNUSED_PARAMETER")
    fun showsTopBar(step: OnboardingStep): Boolean = true

    /** The rail's 0-based dot for `step` among `steps` (one dot per active screen, Welcome first). */
    fun railIndex(step: OnboardingStep, steps: List<OnboardingStep> = OnboardingStep.activeSteps()): Int = step.position(steps) - 1

    val pageDuration: Double = OnboardingMotionTokens.screenTransition

    /** UserDefaults key: the rating prompt was asked for on this install (amendment al). */
    const val ratingPromptRequestedKey = "ratingPromptRequested"
}

@Composable
fun OnboardingContainer(app: AppModel, modifier: Modifier = Modifier) {
    val model = app.onboardingModel
    val reduceMotion = accessibilityReduceMotion()
    val density = LocalDensity.current
    // Amendment al: the store's own rating prompt, once per install, after the mock trial is watched to the end
    // (Google Play In-App Review where iOS calls `requestReview`; `AWNoReviewPrompt YES` never asks).
    val requestReview = rememberRequestReview()
    val step = model.step
    // Bumped on every screen entry; each reveal fires once per value.
    var entry by remember { mutableIntStateOf(0) }
    var shownStep by remember { mutableStateOf(step) }
    if (shownStep != step) {
        shownStep = step
        entry += 1
    }

    LaunchedEffect(Unit) { app.onboardingInProgress = true }
    LaunchedEffect(step) { model.screenAppeared(step) }
    // Amendment as: an invite link mid-onboarding opens the partner step's code entry (which consumes it).
    // Without a profile yet the code waits: the identity step leads to the partner step anyway.
    val pendingJoin = app.links.pendingJoinCode
    LaunchedEffect(pendingJoin) {
        if (pendingJoin == null || app.store.me == null || app.store.couple?.isLinked == true || model.step == OnboardingStep.partner) return@LaunchedEffect
        model.go(OnboardingStep.partner)
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) app.refreshPermissions() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(enabled = model.canGoBack) { model.back() }

    Box(modifier.fillMaxSize()) {
        OnboardingShellBackground(Modifier.matchParentSize())
        AnimatedContent(
            targetState = step,
            transitionSpec = { onboardingPage(forward = model.direction == OnboardingModel.Direction.forward, reduceMotion = reduceMotion, density = density) },
            label = "onboardingPage",
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            val fullBleed = OnboardingContainer.isFullBleed(page)
            CompositionLocalProvider(LocalPleadRevealID provides entry) {
                Box(
                    Modifier.fillMaxSize().then(
                        if (OnboardingContainer.showsTopBar(page) && !fullBleed) {
                            Modifier.statusBarsPadding().padding(top = OnboardingContainer.chromeHeight).clipToBounds()
                        } else {
                            Modifier
                        },
                    ),
                ) {
                    OnboardingScreen(app, model, page) { requestReview() }
                }
            }
        }
        OnboardingTopBar(model, step, Modifier.statusBarsPadding())
    }
}

@Composable
private fun OnboardingScreen(app: AppModel, model: OnboardingModel, step: OnboardingStep, askForRating: () -> Unit) {
    when (step) {
        OnboardingStep.welcome -> OnboardingWelcomeView(app)
        // Amendments y + ab: the scene brings its own controls (the invitation's START MOCK TRIAL, tap to advance,
        // SKIP DEMO throughout, CONTINUE after the verdict); the container only keeps the top bar. Skip skips the
        // demo, not onboarding: both exits land on the summons explainer (amendment ai), whose back returns here.
        // Every entry composes a fresh `MockTrialDemoView` (AnimatedContent keys the page), so re-entering opens on
        // the invitation again, and `mockTrialAppeared()` (`onboarding_mock_trial_viewed`) fires as it appears.
        OnboardingStep.mockTrial -> {
            LaunchedEffect(Unit) { model.mockTrialAppeared() }
            MockTrialDemoView(
                onContinue = {
                    model.completeMockTrial()
                    askForRating()
                },
                onSkip = { model.skipMockTrial() },
                onAdvance = { model.mockTrialAdvanced(it) },
            )
        }
        // Amendment ai: explains the process only (CONTINUE → How Plead Works); no data, network or AI call.
        OnboardingStep.summonsIntro -> SummonsIntroView(app)
        OnboardingStep.howItWorks -> HowPleadWorksView(app)
        // Amendment ak: Meet the AI Court and Example Cases are the redesigned panel and docket screens.
        OnboardingStep.aiCourt -> CourtPanelScreen(app)
        OnboardingStep.examples -> CaseDocketScreen(app)
        OnboardingStep.identity -> CourtIdentityView(app)
        OnboardingStep.partner -> PartnerSetupView(app)
        OnboardingStep.notifications -> NotificationPermissionView(app)
        OnboardingStep.widgets -> WidgetSetupEducationView(app)
        // iOS: always shown as the Privacy screen, asking for ATT (amendments ak, at). Android never routes here
        // (amendment az: no ATT; `OnboardingStep.skipsTracking`), but a stray jump still renders the screen.
        OnboardingStep.tracking -> PrivacyScreenView(app)
        OnboardingStep.ready -> OnboardingCompleteView(app)
    }
}

@Composable
private fun OnboardingTopBar(model: OnboardingModel, step: OnboardingStep, modifier: Modifier = Modifier) {
    val onDark = OnboardingContainer.isFullBleed(step)
    val canGoBack = OnboardingFlow.canGoBack(step, model.activeSteps)
    Row(
        modifier.fillMaxWidth().height(OnboardingContainer.chromeHeight).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(44.dp)
                .graphicsLayer { alpha = if (canGoBack) 1f else 0f }
                .then(
                    if (canGoBack) {
                        Modifier.clickable { model.back() }.semantics { contentDescription = "Back"; role = Role.Button }.testTag("onboarding.back")
                    } else {
                        Modifier.clearAndSetSemantics { }
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                SFSymbol.icon("chevron.left"),
                contentDescription = null,
                tint = if (onDark) OnboardingPalette.cream else OnboardingPalette.wine,
                modifier = Modifier.size(22.dp),
            )
        }
        CourtProgressRail(
            index = OnboardingContainer.railIndex(step, model.activeSteps),
            count = model.activeSteps.size,
            onDark = onDark,
            modifier = Modifier.weight(1f).padding(horizontal = PleadSpacing.s),
        )
        // Mirror the chevron so the bar is optically centred.
        Spacer(Modifier.size(44.dp))
    }
}
