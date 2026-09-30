// Port of ArgueWin/App/RootView.swift (RootView, LaunchView, LoadFailedView).
package app.plead.android.app

import android.app.Activity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import app.plead.android.R
import app.plead.android.designsystem.PleadColor
import app.plead.android.designsystem.PleadFont
import app.plead.android.designsystem.PleadMotion
import app.plead.android.designsystem.PleadRadius
import app.plead.android.designsystem.PleadSpacing
import app.plead.android.designsystem.PleadType
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowInsetsCompat
import app.plead.android.designsystem.ComponentGallery
import app.plead.android.designsystem.showsComponentGallery
import app.plead.android.features.account.SecureAccountView
import app.plead.android.features.coldopen.ColdOpenCoordinator
import app.plead.android.features.coldopen.ColdOpenTimeline
import app.plead.android.features.coldopen.ColdOpenView
import app.plead.android.features.onboarding.LinkCoupleView
import app.plead.android.features.onboarding.LinkedCelebrationView
import app.plead.android.features.onboarding.OnboardingContainer
import app.plead.android.features.paywall.PaywallGate

/**
 * Top-level switch on the gate ([AppGate]). Each phase change is a one-way door (replace, not push):
 * finished onboarding can never be navigated back into, and the paywall is a full-screen root
 * state (not a sheet) while the couple is unpaid, so there is no path into the tabs around it. Once paid,
 * an anonymous session meets SecureAccountView (also a root state, no close) before the tabs.
 */
@Composable
fun RootScreen(model: AppModel) {
    val phase = model.phase
    val coldOpenPlaying = model.coldOpen.isPlaying
    val reduceMotion = rememberReduceMotion()

    // Light status-bar icons over the dark Court tab (iOS `preferredColorScheme(.dark)` for the window only).
    val view = LocalView.current
    val lightStatusBar = model.wantsLightStatusBar
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !lightStatusBar
    }

    LaunchedEffect(phase) {
        // Latch the gate: if the couple turns premium while it's showing (the partner paid), the
        // user is greeted with "your partner already unlocked" rather than dropped into the tabs.
        if (phase == AppGate.Destination.paywall) model.gateLatched = true
        // Screen 9's "file your first case" / "invite my partner", once the gate lets us in.
        if (phase == AppGate.Destination.tabs) model.applyOnboardingExit()
    }

    // DEBUG `AWSheet gallery`: the design-system component gallery instead of the app.
    if (DemoHarness.showsComponentGallery) {
        ComponentGallery()
        return
    }

    // Status bar hidden while the cold open plays (iOS `.statusBarHidden(model.coldOpen.isPlaying)`).
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (coldOpenPlaying) controller.hide(WindowInsetsCompat.Type.statusBars()) else controller.show(WindowInsetsCompat.Type.statusBars())
    }

    Box(Modifier.fillMaxSize().background(PleadColor.background)) {
        // The gate's destination is only mounted once the cold open ends (so nothing, summons covers or onboarding,
        // can appear over it); the cold open then fades away above it (ColdOpenTimeline.crossfade, 0.5 s).
        if (!coldOpenPlaying) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    val spec = tween<Float>(durationMillis = if (reduceMotion) 0 else 250, easing = PleadMotion.easeOut)
                    fadeIn(spec) togetherWith fadeOut(spec)
                },
                label = "gate",
            ) { destination -> Gate(model, destination) }
        }
        val coldOpen = model.coldOpen
        if (coldOpen is ColdOpenCoordinator) {
            AnimatedVisibility(
                visible = coldOpenPlaying,
                enter = EnterTransition.None,
                exit = fadeOut(tween((ColdOpenTimeline.crossfade * 1000).toInt(), easing = FastOutSlowInEasing)),
                // Fade the flattened frame, not each layer (iOS `.compositingGroup()`).
                modifier = Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
            ) { ColdOpenView(coldOpen) }
        }

        // The "legally bound" celebration when the couple links (a full-screen cover on iOS).
        if (model.store.linkCelebration && !coldOpenPlaying) {
            Dialog(
                onDismissRequest = {},
                properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, dismissOnBackPress = false),
            ) {
                LinkedCelebrationView(model.store) {
                    model.store.linkCelebration = false
                    model.finishLinkStep()
                }
            }
        }
    }
}

@Composable
private fun Gate(model: AppModel, destination: AppGate.Destination) {
    when (destination) {
        AppGate.Destination.launching -> LaunchView()
        AppGate.Destination.onboarding -> OnboardingContainer(model)
        AppGate.Destination.linkCouple -> LinkCoupleView(model)
        AppGate.Destination.paywall, AppGate.Destination.partnerPaid -> PaywallGate(model)
        // Amendment p: the gate resolved while the session is anonymous. Not skippable.
        AppGate.Destination.secureAccount -> SecureAccountView(model)
        AppGate.Destination.tabs -> Box(Modifier.fillMaxSize()) {
            MainTabScreen(model)
            MainTabEffects(model)
        }
        AppGate.Destination.loadFailed -> LoadFailedView(model)
    }
}

/** Held until the session and first fetch resolve — never flashes sign-in before Home. */
@Composable
fun LaunchView() {
    Column(
        Modifier.fillMaxSize().background(PleadColor.background).semantics(mergeDescendants = true) { contentDescription = "Plead is loading" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // `PleadLogo(variant: .primary, width: 200)`: the 220×110 pt wordmark asset (wave 2b's PleadLogo replaces this).
        Image(
            painter = painterResource(R.drawable.plead_wordmark),
            contentDescription = null,
            modifier = Modifier.width(200.dp).height(100.dp),
        )
        Spacer(Modifier.height(PleadSpacing.l + PleadSpacing.s))
        CircularProgressIndicator(color = PleadColor.burgundy, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
    }
}

@Composable
fun LoadFailedView(model: AppModel) {
    val scope = rememberCoroutineScope()
    var retrying by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().background(PleadColor.background).padding(PleadSpacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PleadSpacing.l, Alignment.CenterVertically),
    ) {
        // `ScalesMark(size: 72, color: AWColor.walnut)` (wave 2b's ScalesMark replaces the asset).
        Image(
            painter = painterResource(R.drawable.plead_scales),
            contentDescription = null,
            colorFilter = ColorFilter.tint(PleadColor.walnut),
            modifier = Modifier.size(72.dp),
        )
        Text("The court is unreachable", style = PleadFont.title, color = PleadColor.cocoa, textAlign = TextAlign.Center)
        Text(
            model.store.loadError ?: "Check your connection and try again.",
            style = PleadFont.body, color = PleadColor.subtleText, textAlign = TextAlign.Center,
        )
        // `PrimaryButton("Try again", isLoading:)` (wave 2b's PrimaryButton replaces this).
        Box(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).clip(RoundedCornerShape(PleadRadius.button))
                .background(PleadColor.burgundy)
                .clickable(enabled = !retrying) {
                    retrying = true
                    scope.launch {
                        model.retryLoad()
                        retrying = false
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (retrying) CircularProgressIndicator(color = PleadColor.cream, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            else Text("Try again", style = PleadFont.headline, color = PleadColor.cream)
        }
        Box(
            Modifier.defaultMinSize(minHeight = 44.dp).clickable { scope.launch { model.signOut() } },
            contentAlignment = Alignment.Center,
        ) {
            Text("Sign out", style = PleadFont.headline, color = PleadColor.subtleText)
        }
    }
}

/** iOS `accessibilityReduceMotion`: system animations off, or `AWDemoReduceMotion YES`. */
@Composable
private fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { PleadApplication.systemReduceMotion(context) }
}
