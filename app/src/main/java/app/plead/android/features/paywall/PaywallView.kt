// Port of ArgueWin/Features/Paywall/PaywallView.swift.
package app.plead.android.features.paywall

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.plead.android.BuildConfig
import app.plead.android.app.AppModel
import app.plead.android.app.DemoHarness
import app.plead.android.designsystem.AvatarPair
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.models.EdgeError
import app.plead.android.services.Analytics
import app.plead.android.services.PurchasesService
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * The paid-app gate (docs/paywall-brief/three-plan/BRIEF.md, CONTRACTS-v2 amendment s). A full-screen root state,
 * not a sheet: shown by RootScreen while the couple is unpaid. It sells access to Plead itself (there is no free
 * tier). Close goes through `PaywallGateView` (exit offer if eligible, then back to the gated state), never into
 * the tabs. When the couple is already paid (the partner paid) it shows the partner-paid state + Continue, with no
 * purchase CTA. Entitlement truth is `couples.premium_until`.
 *
 *   PaywallView
 *     ├─ PaywallCourtroomHero                (scrolls; the living courtroom, amendment v)
 *     ├─ PaywallBrandHeader
 *     ├─ BenefitGrid / BenefitCard × 4
 *     ├─ SubscriptionOption × Annual → Monthly → Weekly (a plan that didn't load is hidden; skeletons until then)
 *     ├─ PaywallCTA · disclosure · CoupleAccessLine   (amendment z: scrolls with the plans)
 *     └─ pinned: PaywallLegalFooter
 *
 * Motion: the first standard appearance per user plays the bloom (`PaywallOpening`); every other appearance
 * assembles in layers (`PaywallEntrance`). Never both.
 *
 * [onClose]: X. The gate container (`PaywallGateView`) decides between the exit offer and dismissing.
 * [openingStage] / [userId]: amendment q: the gate passes these so the first standard appearance per user can play
 * the opening. Defaults (previews, anything else) never play it.
 */
@Composable
fun PaywallView(
    model: AppModel,
    modifier: Modifier = Modifier,
    onClose: (() -> Unit)? = null,
    openingStage: PaywallStage? = null,
    userId: UUID? = null,
    onHeroHeight: (androidx.compose.ui.unit.Dp) -> Unit = {},
) {
    val store = model.store
    val purchases = model.purchases
    val scope = rememberCoroutineScope()
    val reduceMotion = accessibilityReduceMotion()
    val haptics = LocalHapticFeedback.current
    val uriHandler = LocalUriHandler.current
    val density = LocalDensity.current

    var plan by remember { mutableStateOf(PaywallViewState.initialPlan) }
    // Counts the user's own plan picks (not the fallback when a plan fails to load): the courtroom's gavel tap.
    var planPicks by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var activating by remember { mutableStateOf(false) }
    var purchased by remember { mutableIntStateOf(0) }

    // onAppear: decide the opening once per appearance (the rule records "seen" as it decides).
    val openingMode = remember {
        PaywallOpeningRule.consume(
            userId = userId, stage = openingStage ?: PaywallStage.exitOffer,
            partnerPaid = store.isPremium, reduceMotion = reduceMotion,
        )
    }
    // The bloom (or its Reduce Motion fade) brings the settled paywall in; otherwise it assembles.
    val entranceAnimated = PaywallEntrance.plays(opening = openingMode)
    val opening = remember {
        PaywallOpeningDirector(scope).also { if (openingMode == PaywallOpeningMode.none) it.start(PaywallOpeningMode.none) }
    }
    val entrance = remember {
        PaywallEntranceDirector(if (entranceAnimated) PaywallEntranceState.pending else PaywallEntranceState.settled)
    }
    LaunchedEffect(Unit) {
        if (openingMode != PaywallOpeningMode.none) opening.start(openingMode)
        entrance.begin(animated = entranceAnimated)
    }
    DisposableEffect(Unit) { onDispose { opening.stop() } }
    // The partner's payment landed while the paywall was up: the content becomes the partner-paid state,
    // which assembles like any other re-show.
    var premiumObserved by remember { mutableStateOf(false) }
    LaunchedEffect(store.isPremium) {
        if (premiumObserved) entrance.replay() else premiumObserved = true
    }

    val products = purchases.products
    // The selection, falling back to the next plan down when the chosen product isn't on sale.
    val selectedPlan = products.resolve(plan)
    val cta = PaywallCTAState.make(plan = selectedPlan, products = products)
    val busy = purchases.isPurchasing || activating
    // Neutral skeletons until store metadata arrives (never a guessed price).
    val pricesPending = products.isEmpty

    LaunchedEffect(Unit) { if (!purchases.offeringsLoaded) purchases.loadOfferings() }
    LaunchedEffect(Unit) {
        if (!store.isPremium) {
            Analytics.track(
                "paywall_viewed",
                mapOf(
                    "plans" to products.availablePlans.joinToString(",") { it.rawValue },
                    "trial_eligible" to products.annualTrialEligible.toString(),
                ),
            )
        }
    }
    LaunchedEffect(products) {
        // Keep the stored selection on a plan that's actually on sale (annual missing → monthly → weekly).
        if (products.product(plan) == null) products.defaultPlan?.let { plan = it }
    }
    LaunchedEffect(purchased) { if (purchased > 0) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }
    var planObserved by remember { mutableStateOf(false) }
    LaunchedEffect(plan) {
        if (planObserved) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick) else planObserved = true
    }

    // MARK: Actions

    fun select(p: PurchasesService.Plan) {
        if (p == selectedPlan) return
        Analytics.track("paywall_plan_selected", mapOf("plan" to p.rawValue))
        plan = p
        planPicks += 1
    }

    fun buy() {
        // Couple access already active (the partner just paid): never sell it twice.
        if (!cta.isPurchasable || busy || store.isPremium) return
        error = null
        val chosen = selectedPlan
        val state = cta
        val props = mapOf("plan" to chosen.rawValue, "trial" to state.isTrial.toString())
        Analytics.track("paywall_cta_tapped", props)
        Analytics.track("purchase_started", props)
        if (BuildConfig.DEBUG && store.backend == null) {
            // Demo harness: no Google Play. Pretend the purchase went through and the webhook landed.
            purchases.demoMarkUnlocked()
            purchased += 1
            Analytics.track("purchase_completed", props + ("demo" to "true"))
            if (state.isTrial) Analytics.track("trial_started", mapOf("plan" to chosen.rawValue, "demo" to "true"))
            store.demoGrantPremium()
            return
        }
        scope.launch {
            try {
                if (!purchases.purchase(chosen)) {
                    Analytics.track("purchase_cancelled", props)
                    return@launch
                }
                purchased += 1
                Analytics.track("purchase_completed", props)
                if (state.isTrial) Analytics.track("trial_started", mapOf("plan" to chosen.rawValue))
                activating = true
                store.awaitPremium()
                activating = false
                if (!store.isPremium) error = PaywallCopy.activating
            } catch (e: CancellationException) {
                throw e
            } catch (e: EdgeError) {
                activating = false
                Analytics.track("purchase_failed", props + ("reason" to e.code))
                error = e.message
            } catch (e: Exception) {
                activating = false
                Analytics.track("purchase_failed", props + ("reason" to "error"))
                error = "The purchase didn't go through. You haven't been charged."
            }
        }
    }

    fun restore() {
        if (busy) return
        Analytics.track("restore_tapped")
        restoring = true
        error = null
        scope.launch {
            try {
                if (purchases.restore()) {
                    Analytics.track("restore_success")
                    activating = true
                    store.awaitPremium()
                    activating = false
                    if (!store.isPremium) error = PaywallCopy.restoredActivating
                } else {
                    Analytics.track("restore_failed", mapOf("reason" to "no_active_subscription"))
                    error = "No active subscription found for this Google account."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activating = false
                Analytics.track("restore_failed", mapOf("reason" to "error"))
                error = "Couldn't restore purchases. Try again."
            }
            restoring = false
        }
    }

    // MARK: Layout

    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var scrollOrigin by remember { mutableStateOf(Offset.Zero) }
    var logoSlotInRoot by remember { mutableStateOf<Rect?>(null) }
    // Laid out from the first frame (so the header slot can be measured) but hidden until the lockup travels; it
    // fades in beneath the travel (or over 0.3 s under Reduce Motion).
    val contentAlpha by animateFloatAsState(opening.contentOpacity.toFloat(), opening.arrivedSpec, label = "paywallContent")

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(PaywallPalette.warmCream)
            .onGloballyPositioned { rootOrigin = it.positionInRoot() },
    ) {
        // The window is edge to edge, so this is the full screen height (Swift adds the safe-area insets back).
        val viewport = maxHeight.value
        CompositionLocalProvider(LocalPaywallEntrance provides entrance.state) {
            Column(Modifier.fillMaxSize().alpha(contentAlpha)) {
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    // Android (real-device fix 2026-10-02): what is visible of the scroll area on first view. The
                    // legal footer is pinned below it; the partner-paid state has no footer, only the navigation bar.
                    val navInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
                    // (The unpaid page's last 12 dp dissolve into the footer, so they don't count as visible.)
                    val available = if (store.isPremium) maxHeight - navInset else maxHeight - PaywallCompact.footerFade
                    val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
                    var compact by remember(available, density.fontScale, store.isPremium) { mutableStateOf(false) }
                    Box(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState(), enabled = opening.handedOver)
                            .onGloballyPositioned { scrollOrigin = it.positionInRoot() },
                    ) {
                        // What the opening's bloom leaves behind: a soft blush glow under the header logo (present in
                        // the settled paywall too, so a skipped or re-shown paywall looks the same as one that played).
                        logoSlotInRoot?.let { slot ->
                            val radiusPx = slot.width * PaywallOpeningTiming.glowRadius
                            val radius = with(density) { radiusPx.toDp() }
                            val centre = slot.center - scrollOrigin
                            PaywallOpeningGlow(
                                radius = radius,
                                modifier = Modifier
                                    .offset { IntOffset((centre.x - radiusPx).roundToInt(), (centre.y - radiusPx).roundToInt()) }
                                    .paywallEntrance(PaywallEntranceLayer.brand),
                            )
                        }
                        // The hero gives back height only when the CTA would otherwise sit under the fold (PaywallFit).
                        PaywallFittedColumn(
                            available = available,
                            standardHero = PaywallLayout.heroHeight(viewport).dp,
                            topInset = topInset,
                            compact = compact,
                            onCompactNeeded = { compact = true },
                            onHeroHeight = onHeroHeight,
                            modifier = Modifier.fillMaxWidth(),
                            hero = {
                                // Amendment v: picking a plan may bring the gavel down once (debounced); purchase logic
                                // never waits on the animation.
                                PaywallCourtroomHero(
                                    height = PaywallLayout.heroHeight(viewport).dp,
                                    gavelTrigger = planPicks.toString(),
                                    modifier = Modifier.paywallEntrance(PaywallEntranceLayer.hero),
                                )
                            },
                            body = {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    // The logo sits fully below the hero's fade, keeping about an "e"-height of clear
                                    // cream between the mark and the courtroom art.
                                    Box(
                                        Modifier
                                            .widthIn(max = 520.dp)
                                            .fillMaxWidth()
                                            .padding(horizontal = 20.dp)
                                            .padding(top = 2.dp),
                                    ) {
                                        if (store.isPremium) {
                                            PartnerPaidContent(model)
                                        } else {
                                            val gap = if (compact) PaywallCompact.stackSpacing else 7.dp
                                            Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
                                                PaywallBrandHeader(
                                                    logoMarkOpacity = if (opening.handedOver) 1.0 else 0.0,
                                                    onLogoBounds = { logoSlotInRoot = it },
                                                    compact = compact,
                                                )
                                                BenefitGrid(compact = compact)
                                                Plans(
                                                    compact = compact,
                                                    pricesPending = pricesPending,
                                                    products = products,
                                                    selectedPlan = selectedPlan,
                                                    enabled = !busy,
                                                    onSelect = ::select,
                                                )
                                                // Amendment z: the CTA follows the plans instead of floating in a pinned bar.
                                                CtaBlock(
                                                    error = error,
                                                    showsRetry = purchases.loadState == PurchasesService.LoadState.failed && !cta.isPurchasable,
                                                    onRetry = { scope.launch { purchases.loadOfferings() } },
                                                    cta = cta,
                                                    isLoading = busy && !restoring,
                                                    enabled = cta.isPurchasable && !busy,
                                                    onBuy = ::buy,
                                                    modifier = Modifier.padding(top = if (compact) 0.dp else 5.dp),
                                                )
                                            }
                                        }
                                    }
                                    if (store.isPremium) androidx.compose.foundation.layout.Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
                                }
                            },
                        )
                    }
                    if (!store.isPremium) {
                        // Content scrolling underneath dissolves into the footer instead of meeting a hard edge.
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .height(PaywallCompact.footerFade)
                                .background(Brush.verticalGradient(listOf(PaywallPalette.warmCream.copy(alpha = 0f), PaywallPalette.warmCream))),
                        )
                    }
                }
                // Amendment z: only the legal footer stays pinned; the CTA scrolls with the plans.
                if (!store.isPremium) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(PaywallPalette.warmCream)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .padding(top = 2.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        PaywallLegalFooter(
                            restoring = restoring,
                            enabled = !busy,
                            onRestore = ::restore,
                            onTerms = { uriHandler.openUri(PaywallCopy.termsURL) },
                            onPrivacy = { uriHandler.openUri(PaywallCopy.privacyURL) },
                            modifier = Modifier
                                .widthIn(max = 520.dp)
                                .padding(horizontal = 20.dp)
                                .paywallEntrance(PaywallEntranceLayer.footer),
                        )
                    }
                }
            }
            if (!store.isPremium) {
                PaywallCloseButton(
                    enabled = !busy,
                    modifier = Modifier
                        .alpha(contentAlpha)
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(start = 16.dp, top = 4.dp),
                ) {
                    Analytics.track("paywall_standard_closed")
                    if (onClose != null) onClose() else model.closeGate()
                }
            }
        }

        // The flying lockup lands on the header logo's measured mark frame (same coordinate space, no guess).
        if (opening.isPlaying) {
            PaywallOpening(director = opening, slot = logoSlotInRoot?.translate(-rootOrigin))
            // Tap anywhere to skip (honoured after `skippableAfter`); nothing underneath is hittable meanwhile.
            Box(
                Modifier
                    .fillMaxSize()
                    .clearAndSetSemantics { }
                    .pointerInput(Unit) { detectTapGestures { opening.skip() } },
            )
        }
    }
}

/** Swift `PaywallView.initialPlan`. */
internal object PaywallViewState {
    val initialPlan: PurchasesService.Plan
        get() {
            if (BuildConfig.DEBUG) PurchasesService.Plan.fromRaw(DemoHarness.plan)?.let { return it }
            return PurchasesService.Plan.annual
        }
}

@Composable
private fun Plans(
    compact: Boolean,
    pricesPending: Boolean,
    products: app.plead.android.services.PaywallProducts,
    selectedPlan: PurchasesService.Plan,
    enabled: Boolean,
    onSelect: (PurchasesService.Plan) -> Unit,
) {
    AnimatedContent(
        targetState = pricesPending,
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) },
        label = "plans",
        modifier = Modifier.semantics { contentDescription = if (pricesPending) "Loading plans" else "Plans" },
    ) { pending ->
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) PaywallCompact.planSpacing else 6.dp)) {
            if (pending) {
                repeat(3) { i -> PlanSkeletonCard(tall = i == 0, modifier = Modifier.paywallEntrance(PaywallEntranceLayer.plan, index = i)) }
            } else {
                products.availablePlans.forEachIndexed { i, p ->
                    val product = products.product(p) ?: return@forEachIndexed
                    SubscriptionOption(
                        plan = p,
                        product = product,
                        trialEligible = p == PurchasesService.Plan.annual && products.annualTrialEligible,
                        isSelected = selectedPlan == p,
                        enabled = enabled,
                        modifier = Modifier.paywallEntrance(PaywallEntranceLayer.plan, index = i),
                    ) { onSelect(p) }
                }
            }
        }
    }
}

/** CTA + disclosure + couple line (and any purchase error / retry above them). */
@Composable
private fun CtaBlock(
    error: String?,
    showsRetry: Boolean,
    onRetry: () -> Unit,
    cta: PaywallCTAState,
    isLoading: Boolean,
    enabled: Boolean,
    onBuy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().paywallEntrance(PaywallEntranceLayer.cta).paywallFoldMark(PaywallFold.full),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (error != null) {
            Text(error, style = roundedFootnote(FontWeight.Medium), color = PaywallPalette.courtBurgundy, textAlign = TextAlign.Center)
        }
        if (showsRetry) {
            Box(
                Modifier
                    .heightIn(min = 44.dp)
                    .clickable(role = Role.Button, onClick = onRetry),
                contentAlignment = Alignment.Center,
            ) {
                Text("Couldn't reach Google Play. Try again", style = roundedFootnote(FontWeight.SemiBold), color = PaywallPalette.courtBurgundy)
            }
        }
        PaywallCTA(state = cta, isLoading = isLoading, enabled = enabled, action = onBuy, modifier = Modifier.paywallFoldMark(PaywallFold.cta))
        // Always one line tall, so the layout doesn't jump when prices arrive.
        Text(
            cta.disclosure.ifEmpty { " " },
            style = roundedFootnote(),
            color = PaywallPalette.mutedCocoa,
            textAlign = TextAlign.Center,
            modifier = if (cta.disclosure.isEmpty()) Modifier.clearAndSetSemantics { } else Modifier,
        )
        CoupleAccessLine()
    }
}

// MARK: Partner already paid

@Composable
private fun PartnerPaidContent(model: AppModel) {
    val store = model.store
    val shape = RoundedCornerShape(PaywallRadius.card)
    LaunchedEffect(Unit) { Analytics.track("paywall_partner_paid_viewed") }
    Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PaywallBrandHeader(showsHeadline = false)
        Column(
            Modifier
                .fillMaxWidth()
                .paywallEntrance(PaywallEntranceLayer.plan, index = 0)
                .background(PaywallPalette.annualFill, shape)
                .border(2.5.dp, PaywallPalette.courtBurgundy, shape)
                .semantics(mergeDescendants = true) { }
                .padding(vertical = 22.dp, horizontal = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            AvatarPair(me = store.me?.avatar, partner = store.partner?.avatar, size = 64.dp, markYou = true)
            Text(
                PaywallCopy.partnerPaid,
                style = roundedFont(TextStyleKind.title2, FontWeightHeavy),
                color = PaywallPalette.deepWine,
                textAlign = TextAlign.Center,
            )
            Text(
                PaywallCopy.partnerPaidDetail,
                style = roundedFont(TextStyleKind.subheadline, FontWeight.Medium),
                color = PaywallPalette.darkCocoa.copy(alpha = 0.75f),
                textAlign = TextAlign.Center,
            )
        }
        Column(
            Modifier.fillMaxWidth().paywallEntrance(PaywallEntranceLayer.cta).paywallFoldMark(PaywallFold.full),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaywallCTA(title = "CONTINUE", showsHeart = true, modifier = Modifier.paywallFoldMark(PaywallFold.cta)) { model.enterApp() }
            CoupleAccessLine()
        }
    }
}
