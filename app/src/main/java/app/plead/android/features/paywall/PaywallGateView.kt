// Port of ArgueWin/Features/Paywall/PaywallGateView.swift.
package app.plead.android.features.paywall

import androidx.compose.ui.unit.Dp
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import app.plead.android.BuildConfig
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.services.Analytics
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What RootScreen mounts for `AppGate.Destination.paywall` / `.partnerPaid` (Swift RootView:
 * `PaywallGateView(userId: model.auth.userId)`).
 */
@Composable
fun PaywallGate(model: AppModel, modifier: Modifier = Modifier) {
    PaywallGateView(model = model, userId = model.auth.userId, modifier = modifier)
}

/**
 * The full-screen gate container: the standard paywall, or (when eligible) the exit offer in its place after X.
 */
@Composable
fun PaywallGateView(model: AppModel, userId: UUID?, modifier: Modifier = Modifier) {
    val store = model.store
    val purchases = model.purchases
    val scope = rememberCoroutineScope()
    val reduceMotion = accessibilityReduceMotion()

    var stage by remember { mutableStateOf(PaywallGateView.initialStage) }
    var resolving by remember { mutableStateOf(false) }
    // The standard hero's height as last laid out (Android: PaywallFit can shorten it on short screens), so the exit
    // offer's hero starts exactly where the outgoing one is.
    val standardHero = remember { arrayOfNulls<Dp>(1) }

    fun closeStandard() {
        if (resolving) return
        resolving = true
        scope.launch {
            val offer = purchases.loadExitOffer()?.let(ExitOfferState::from)
            resolving = false
            if (offer != null) Analytics.track("exit_offer_eligible", mapOf("percent" to offer.discountPercent.toString()))
            when (PaywallCloseDecision.onCloseStandard(offer = offer)) {
                PaywallCloseDecision.showExitOffer -> stage = PaywallStage.exitOffer
                PaywallCloseDecision.dismiss -> model.closeGate()
            }
        }
    }

    // Prefetch so X decides instantly.
    LaunchedEffect(Unit) { purchases.loadExitOffer() }
    // `AWAutoCloseAfter 2.5` (demo): X is tapped once after that many seconds, to record standard → exit offer.
    LaunchedEffect(Unit) {
        val after = if (BuildConfig.DEBUG) DemoHarness.autoCloseAfter else 0.0
        if (after <= 0 || stage != PaywallStage.standard) return@LaunchedEffect
        delay((after * 1000).toLong())
        closeStandard()
    }

    val offer = purchases.exitOffer?.let(ExitOfferState::from)
    val showsExitOffer = stage == PaywallStage.exitOffer && !store.isPremium && offer != null
    // Standard → exit offer (amendment s): a 0.3 s cross-dissolve with the hero held in place. The exit offer
    // arrives at full opacity with a hero identical in size and position to the one going out, and no background of
    // its own; the standard paywall dissolves out beneath it while the exit content assembles in layers. Declining
    // returns to the gated state; nothing unlocks without an entitlement.
    val crossfade = (PaywallEntranceTokens.exitCrossfade * 1000).roundToInt()
    val standardAlpha by animateFloatAsState(
        if (showsExitOffer) 0f else 1f,
        tween(crossfade, easing = if (reduceMotion) LinearEasing else FastOutSlowInEasing),
        label = "standardPaywall",
    )

    Box(modifier.fillMaxSize().background(PaywallPalette.warmCream)) {
        if (!showsExitOffer || standardAlpha > 0f) {
            PaywallView(
                model = model,
                onClose = ::closeStandard,
                openingStage = stage,
                userId = userId,
                onHeroHeight = { standardHero[0] = it },
                modifier = Modifier.zIndex(0f).alpha(standardAlpha),
            )
        }
        if (showsExitOffer && offer != null) {
            ExitOfferPaywallView(model = model, offer = offer, modifier = Modifier.zIndex(1f), initialHeroHeight = standardHero[0]) {
                model.closeGate()
            }
        }
    }
}

object PaywallGateView {
    val initialStage: PaywallStage
        get() {
            if (BuildConfig.DEBUG && DemoHarness.isDemo && DemoHarness.sheet == DemoHarness.Sheet.exitOffer) return PaywallStage.exitOffer
            return PaywallStage.standard
        }
}
