// Port of ArgueWin/Features/Paywall/ExitOfferPaywallView.swift.
package app.plead.android.features.paywall

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.animateDpAsState
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.plead.android.BuildConfig
import app.plead.android.app.AppModel
import app.plead.android.designsystem.TextStyleKind
import app.plead.android.designsystem.accessibilityReduceMotion
import app.plead.android.designsystem.fixedSp
import app.plead.android.designsystem.monospacedDigit
import app.plead.android.models.EdgeError
import app.plead.android.services.Analytics
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The exit offer (docs/paywall-brief/exit/BRIEF.md, CONTRACTS-v2 amendments f, aa, ao).
 * Replaces the standard paywall inside the same full-screen gate after X, when eligible.
 * Annual only. X or "No thanks, not now" dismisses immediately (back to the gated state), no further upsell;
 * nothing unlocks without an entitlement. Arrives from the standard paywall by a 0.3 s cross-dissolve with the
 * hero held in place (`PaywallGateView`), then assembles in layers (`PaywallEntrance`); the EXIT OFFER stamp lands
 * with a 0.97 → 1.03 → 1 spring and one light haptic.
 *
 *   ExitOfferPaywallView
 *     ├─ ExitOfferHero
 *     ├─ ExitOfferStamp
 *     ├─ headline + sub
 *     ├─ JudgeQuoteCard
 *     ├─ DiscountHero
 *     ├─ AnnualDiscountCard
 *     ├─ CoupleAccessReminder
 *     ├─ ExitOfferCTA + renewal disclosure
 *     ├─ "No thanks, not now"
 *     └─ PaywallLegalFooter
 */
@Composable
fun ExitOfferPaywallView(
    model: AppModel,
    offer: ExitOfferState,
    modifier: Modifier = Modifier,
    /** The outgoing standard paywall's hero height (Android: it can differ on short screens, see PaywallFit). */
    initialHeroHeight: Dp? = null,
    onDismiss: () -> Unit,
) {
    val store = model.store
    val purchases = model.purchases
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val haptics = LocalHapticFeedback.current

    var error by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var activating by remember { mutableStateOf(false) }
    var purchased by remember { mutableIntStateOf(0) }
    val entrance = remember { PaywallEntranceDirector() }

    val busy = purchases.isPurchasing || activating
    val props = mapOf("percent" to offer.discountPercent.toString())

    LaunchedEffect(Unit) {
        Analytics.track("exit_offer_viewed", props)
        entrance.begin(animated = true)
    }
    LaunchedEffect(purchased) { if (purchased > 0) haptics.performHapticFeedback(HapticFeedbackType.Confirm) }

    // MARK: Actions

    fun decline(via: String) {
        if (busy) return
        Analytics.track("exit_offer_declined", props + ("via" to via))
        onDismiss()
    }

    fun claim() {
        if (busy) return
        error = null
        Analytics.track("exit_offer_accepted", props)
        Analytics.track("exit_offer_purchase_started", props)
        if (BuildConfig.DEBUG && store.backend == null) {
            // Demo harness: no Google Play. Pretend the purchase went through and the webhook landed.
            purchases.demoMarkUnlocked()
            purchased += 1
            Analytics.track("exit_offer_purchase_completed", props + ("demo" to "true"))
            store.demoGrantPremium()
            return
        }
        scope.launch {
            try {
                if (!purchases.purchaseExitOffer()) {
                    Analytics.track("exit_offer_purchase_failed", props + ("reason" to "cancelled"))
                    return@launch
                }
                purchased += 1
                Analytics.track("exit_offer_purchase_completed", props)
                activating = true
                store.awaitPremium()
                activating = false
                if (!store.isPremium) error = PaywallCopy.activating
            } catch (e: CancellationException) {
                throw e
            } catch (e: EdgeError) {
                activating = false
                Analytics.track("exit_offer_purchase_failed", props + ("reason" to e.code))
                error = e.message
            } catch (e: Exception) {
                activating = false
                Analytics.track("exit_offer_purchase_failed", props + ("reason" to "error"))
                error = "The purchase didn't go through. You haven't been charged."
            }
        }
    }

    fun restore() {
        if (busy) return
        Analytics.track("restore_tapped", mapOf("screen" to "exit_offer"))
        restoring = true
        error = null
        scope.launch {
            try {
                if (purchases.restore()) {
                    Analytics.track("restore_success", mapOf("screen" to "exit_offer"))
                    activating = true
                    store.awaitPremium()
                    activating = false
                    if (!store.isPremium) error = PaywallCopy.restoredActivating
                } else {
                    error = "No active subscription found for this Google account."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                activating = false
                error = "Couldn't restore purchases. Try again."
            }
            restoring = false
        }
    }

    // No background of its own: the gate's cream is behind both screens, so the standard paywall can dissolve
    // out beneath this one.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewport = maxHeight.value
        val visibleHeight = maxHeight
        val density = LocalDensity.current
        // Android (real-device fix 2026-10-02): the visible height above the navigation bar, and the status bar the
        // squeezed hero keeps the judge clear of (PaywallFit).
        val navInset = with(density) { WindowInsets.navigationBars.getBottom(density).toDp() }
        val topInset = with(density) { WindowInsets.statusBars.getTop(density).toDp() }
        var compact by remember(maxHeight, density.fontScale) { mutableStateOf(false) }
        var fitted by remember { mutableStateOf<Dp?>(null) }
        // Same height as the outgoing standard hero, and not animated, so it holds still through the cross-dissolve;
        // only when this screen fits a different hero (short screens) does it ease to it over the same 0.3 s.
        val handedOver = initialHeroHeight?.let { start ->
            animateDpAsState(
                fitted ?: start,
                tween((PaywallEntranceTokens.exitCrossfade * 1000).toInt(), easing = FastOutSlowInEasing),
                label = "exitHero",
            ).value
        }
        CompositionLocalProvider(LocalPaywallEntrance provides entrance.state) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PaywallFittedColumn(
                    available = visibleHeight - navInset,
                    standardHero = PaywallLayout.heroHeight(viewport).dp,
                    topInset = topInset,
                    compact = compact,
                    onCompactNeeded = { compact = true },
                    heroOverride = handedOver,
                    onHeroHeight = { if (fitted != it) fitted = it },
                    modifier = Modifier.fillMaxWidth(),
                    hero = { ExitOfferHero(height = PaywallLayout.heroHeight(viewport).dp) },
                    body = {
                        Column(
                            Modifier
                                .widthIn(max = 520.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp)
                                .offset(y = (-26).dp)
                                .padding(bottom = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(if (compact) PaywallCompact.exitStackSpacing else 10.dp),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                ExitOfferStamp(Modifier.paywallEntrance(PaywallEntranceLayer.brand))
                                ScaledText(
                                    "THE COURT HAS\nRECONSIDERED",
                                    style = roundedFont(TextStyleKind.title, FontWeightHeavy).copy(lineHeight = 1.1.em),
                                    color = PaywallPalette.deepWine,
                                    maxLines = 2,
                                    minScale = 0.8f,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .paywallEntrance(PaywallEntranceLayer.brand)
                                        .clearAndSetSemantics {
                                            contentDescription = "The court has reconsidered"
                                            heading()
                                        },
                                )
                                Text(
                                    offer.subheadline,
                                    style = roundedFont(TextStyleKind.subheadline, FontWeight.Medium),
                                    color = PaywallPalette.darkCocoa.copy(alpha = 0.72f),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.paywallEntrance(PaywallEntranceLayer.sub),
                                )
                            }
                            JudgeQuoteCard(Modifier.paywallEntrance(PaywallEntranceLayer.tile, index = 0))
                            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                DiscountHero(offer.discountLabel, Modifier.paywallEntrance(PaywallEntranceLayer.plan, index = 0))
                                AnnualDiscountCard(offer, Modifier.paywallEntrance(PaywallEntranceLayer.plan, index = 1))
                            }
                            CoupleAccessReminder(Modifier.paywallEntrance(PaywallEntranceLayer.plan, index = 2))
                            Column(
                                Modifier.fillMaxWidth().paywallEntrance(PaywallEntranceLayer.cta).paywallFoldMark(PaywallFold.full),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                error?.let {
                                    Text(it, style = roundedFootnote(FontWeight.Medium), color = PaywallPalette.courtBurgundy, textAlign = TextAlign.Center)
                                }
                                ExitOfferCTA(
                                    title = offer.ctaTitle,
                                    isLoading = busy && !restoring,
                                    enabled = !busy,
                                    action = ::claim,
                                    modifier = Modifier.paywallFoldMark(PaywallFold.cta),
                                )
                                Text(offer.renewalCopy, style = roundedFootnote(), color = PaywallPalette.mutedCocoa, textAlign = TextAlign.Center)
                            }
                            Column(
                                Modifier.fillMaxWidth().paywallEntrance(PaywallEntranceLayer.footer),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier
                                        .heightIn(min = 44.dp)
                                        .clickable(enabled = !busy, role = Role.Button) { decline("no_thanks") },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "No thanks, not now",
                                        style = roundedFont(TextStyleKind.subheadline, FontWeight.SemiBold),
                                        color = PaywallPalette.courtBurgundy,
                                    )
                                }
                                PaywallLegalFooter(
                                    restoring = restoring,
                                    enabled = !busy,
                                    onRestore = ::restore,
                                    onTerms = { uriHandler.openUri(PaywallCopy.termsURL) },
                                    onPrivacy = { uriHandler.openUri(PaywallCopy.privacyURL) },
                                )
                            }
                        }
                    },
                )
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
            PaywallCloseButton(
                hint = "Dismiss the offer",
                enabled = !busy,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 16.dp, top = 4.dp),
            ) { decline("close") }
        }
    }
}

// MARK: - Components

/**
 * The same living courtroom as the standard paywall (amendment v), same height, fading into cream. It starts at
 * rest (the animator's entrance hold), so it matches the outgoing hero through the cross-dissolve.
 */
@Composable
fun ExitOfferHero(height: Dp, modifier: Modifier = Modifier) {
    PaywallCourtroomHero(height = height, modifier = modifier)
}

/**
 * "EXIT OFFER" — a gold court stamp: double-ruled capsule, heavy tracked caps. Not a sale banner.
 * Lands once (with the brand layer) on a 0.97 → 1.03 → 1 spring and one light haptic; Reduce Motion: no spring.
 */
@Composable
fun ExitOfferStamp(modifier: Modifier = Modifier) {
    val entrance = LocalPaywallEntrance.current
    val reduceMotion = accessibilityReduceMotion()
    val view = LocalView.current
    val scale = remember { Animatable(1f) }
    var landed by remember { mutableIntStateOf(0) }
    LaunchedEffect(entrance) {
        if (!entrance.shown || !entrance.animated || landed != 0) return@LaunchedEffect
        delay((ExitOfferStamp.landsAt * 1000).toLong())
        landed += 1
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        if (reduceMotion) return@LaunchedEffect
        val s = PaywallEntranceTokens.stampSpring
        // Cubic throughout: a spring keyframe would inherit the dip's velocity and overshoot far past 0.97.
        scale.animateTo(s[0], tween(70, easing = FastOutSlowInEasing))
        scale.animateTo(s[1], tween(140, easing = FastOutSlowInEasing))
        scale.animateTo(s[2], tween(200, easing = FastOutSlowInEasing))
    }
    CappedTypeSize(DynamicTypeScale.xxxLarge) {
        Box(
            modifier
                .scale(scale.value)
                .rotate(-2f)
                .background(PaywallPalette.goldLight, CircleShape)
                .border(1.5.dp, PaywallPalette.courtGold, CircleShape)
                .drawBehind {
                    // The inner rule: 1 pt, 55% gold, inset 3 pt.
                    val inset = 3.dp.toPx()
                    val stroke = 1.dp.toPx()
                    val h = size.height - inset * 2
                    drawRoundRect(
                        color = PaywallPalette.courtGold.copy(alpha = 0.55f),
                        topLeft = Offset(inset + stroke / 2, inset + stroke / 2),
                        size = Size(size.width - inset * 2 - stroke, h - stroke),
                        cornerRadius = CornerRadius(h / 2, h / 2),
                        style = Stroke(stroke),
                    )
                }
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                "EXIT OFFER",
                style = roundedFont(TextStyleKind.caption, FontWeightHeavy).copy(letterSpacing = fixedSp(1.5f)),
                color = PaywallPalette.deepWine,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

object ExitOfferStamp {
    /** When the stamp lands: halfway through the brand layer's rise, when it has mostly faded in. */
    val landsAt: Double
        get() {
            val p = PaywallEntrance.parameters(PaywallEntranceLayer.brand, reduceMotion = false)
            return p.delay + p.duration * 0.5
        }
}

/** Paper-white card: gavel glyph in a blush disc, the judge's line, attribution. */
@Composable
fun JudgeQuoteCard(modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(PaywallRadius.perk)
    Row(
        modifier
            .fillMaxWidth()
            .background(PaywallPalette.paperWhite, shape)
            .border(1.5.dp, PaywallPalette.parchment, shape)
            .semantics(mergeDescendants = true) { }
            .padding(vertical = 10.dp, horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).background(PaywallPalette.perkFill, CircleShape), contentAlignment = Alignment.Center) {
            PixelGlyph(PaywallSprites.gavel, Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "“The court is prepared to be lenient.”",
                style = roundedFont(TextStyleKind.subheadline, FontWeight.SemiBold),
                color = PaywallPalette.darkCocoa,
            )
            Text(
                "— Judge Wigsworth",
                style = roundedFont(TextStyleKind.caption, FontWeight.Medium),
                color = PaywallPalette.mutedCocoa,
            )
        }
    }
}

/** "50% OFF" in Coral — the strongest type on the screen — with ANNUAL beneath. */
@Composable
fun DiscountHero(label: String, modifier: Modifier = Modifier) {
    // `@ScaledMetric(relativeTo: .largeTitle) var size = 54`, capped at 76.
    val size = minOf(54f * fontScale(), 76f)
    Column(
        modifier.clearAndSetSemantics { contentDescription = "$label, annual plan" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy((-6).dp),
    ) {
        ScaledText(
            label,
            style = roundedFont(TextStyleKind.largeTitle, FontWeight.Black).copy(fontSize = fixedSp(size)).monospacedDigit(),
            color = PaywallPalette.coral,
            maxLines = 1,
            minScale = 0.6f,
        )
        Text(
            "ANNUAL",
            style = roundedFont(TextStyleKind.title3, FontWeightHeavy).copy(letterSpacing = fixedSp(1f)),
            color = PaywallPalette.deepWine,
        )
    }
}

/** The single, selected annual card: standard price struck through, offer price prominent. */
@Composable
fun AnnualDiscountCard(offer: ExitOfferState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(PaywallRadius.card)
    Row(
        modifier
            .fillMaxWidth()
            .background(PaywallPalette.annualFill, shape)
            .border(2.5.dp, PaywallPalette.courtBurgundy, shape)
            .clearAndSetSemantics {
                contentDescription = "Annual, selected. Was ${offer.standardAnnualPrice}. ${offer.offerAnnualPrice} per year."
                selected = true
            }
            .padding(vertical = 10.dp, horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioMark(isSelected = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text("ANNUAL PLAN", style = roundedFont(TextStyleKind.headline, FontWeightHeavy), color = PaywallPalette.deepWine)
            Text(
                "for ${offer.discountPercent}% off",
                style = roundedFont(TextStyleKind.subheadline, FontWeight.Bold),
                color = PaywallPalette.coral,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                offer.standardAnnualPrice,
                style = roundedFont(TextStyleKind.subheadline, FontWeight.SemiBold).monospacedDigit()
                    .copy(textDecoration = TextDecoration.LineThrough),
                color = PaywallPalette.mutedCocoa,
            )
            ScaledText(
                offer.offerAnnualPrice,
                style = roundedFont(TextStyleKind.title, FontWeightHeavy).monospacedDigit(),
                color = PaywallPalette.deepWine,
                maxLines = 1,
                minScale = 0.7f,
                textAlign = TextAlign.End,
            )
            Text(
                "per year",
                style = roundedFont(TextStyleKind.caption, FontWeight.Medium),
                color = PaywallPalette.darkCocoa.copy(alpha = 0.7f),
            )
        }
    }
}

/** Compact blush card: one subscription, both partners. */
@Composable
fun CoupleAccessReminder(modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(PaywallPalette.perkFill, RoundedCornerShape(PaywallRadius.perk))
            .semantics(mergeDescendants = true) { }
            .padding(vertical = 10.dp, horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PixelGlyph(PaywallSprites.couple, Modifier.size(30.dp, 24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(PaywallCopy.coupleAccess, style = roundedFont(TextStyleKind.footnote, FontWeight.Bold), color = PaywallPalette.deepWine)
            Text(
                "Only one of you needs to pay.",
                style = roundedFont(TextStyleKind.caption, FontWeight.Medium),
                color = PaywallPalette.darkCocoa.copy(alpha = 0.7f),
            )
        }
    }
}

/** Full-width burgundy CTA — the only primary action on the screen. */
@Composable
fun ExitOfferCTA(title: String, modifier: Modifier = Modifier, isLoading: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
    PaywallCTA(title = title, modifier = modifier, showsHeart = true, isLoading = isLoading, enabled = enabled, action = action)
}
