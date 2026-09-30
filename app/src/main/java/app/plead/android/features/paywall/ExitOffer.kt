// Port of ArgueWin/Features/Paywall/ExitOffer.swift. Swift `Decimal` → `BigDecimal`; the billing layer hands over
// `ExitOfferPrices` (micros + localized strings), from which `ExitOfferState.from(_:)` builds the state.
package app.plead.android.features.paywall

import app.plead.android.services.ExitOfferPrices
import java.math.BigDecimal
import java.math.RoundingMode

/** Which screen the full-screen gate shows (docs/paywall-brief/exit/BRIEF.md §6). */
enum class PaywallStage { standard, exitOffer }

/**
 * Everything the exit offer renders. Built only from Google Play / RevenueCat values
 * (`PurchasesService.loadExitOffer()`); `null` anywhere upstream means "no exit offer".
 */
class ExitOfferState private constructor(
    /** Always true for a state the billing layer returns; kept to mirror the brief's model. */
    val isEligible: Boolean,
    /** Localized price of `plead.yearly`, e.g. "£49.99" (shown struck through). */
    val standardAnnualPrice: String,
    /**
     * Localized standard price of `plead.discount`, e.g. "£24.99" (amendment ao: no intro offer; it renews
     * at this same price every year).
     */
    val offerAnnualPrice: String,
    /** "[offer]/year. Renews automatically. Cancel anytime." */
    val renewalCopy: String,
    /** Discount derived from the store's decimal prices, rounded to a whole percent (never assumed). */
    val discountPercent: Int,
) {
    /** "50% OFF" (or whatever the real rounded discount is). */
    val discountLabel: String get() = "$discountPercent% OFF"

    /** "CLAIM 50% OFF" */
    val ctaTitle: String get() = "CLAIM $discountLabel"

    /** "Before you go, take 50% off Plead." */
    val subheadline: String get() = "Before you go, take $discountPercent% off Plead."

    override fun equals(other: Any?): Boolean = other is ExitOfferState &&
        isEligible == other.isEligible && standardAnnualPrice == other.standardAnnualPrice &&
        offerAnnualPrice == other.offerAnnualPrice && renewalCopy == other.renewalCopy &&
        discountPercent == other.discountPercent

    override fun hashCode(): Int = listOf(isEligible, standardAnnualPrice, offerAnnualPrice, renewalCopy, discountPercent).hashCode()

    companion object {
        /** Returns null when the prices don't describe a real discount (Swift's failable `init?`). */
        operator fun invoke(
            standardPrice: BigDecimal,
            standardLocalized: String,
            offerPrice: BigDecimal,
            offerLocalized: String,
        ): ExitOfferState? {
            val pct = ExitOfferMath.discountPercent(standard = standardPrice, offer = offerPrice) ?: return null
            return ExitOfferState(
                isEligible = true,
                standardAnnualPrice = standardLocalized,
                offerAnnualPrice = offerLocalized,
                renewalCopy = ExitOfferMath.renewalCopy(offer = offerLocalized),
                discountPercent = pct,
            )
        }

        /** The billing layer's prices (micros are exact: 1 unit = 1 000 000 micros). */
        fun from(prices: ExitOfferPrices): ExitOfferState? = invoke(
            standardPrice = BigDecimal.valueOf(prices.standardPriceMicros, 6),
            standardLocalized = prices.standardLocalized,
            offerPrice = BigDecimal.valueOf(prices.offerPriceMicros, 6),
            offerLocalized = prices.offerLocalized,
        )

        /**
         * DEBUG / demo / preview placeholder only: from `src/debug` `PreviewPrices` (null in a release build, which
         * never renders hardcoded prices).
         */
        val preview: ExitOfferState? get() = ExitOfferPrices.preview?.let(::from)
    }
}

/** Pure helpers behind the exit offer. Unit-tested in `ExitOfferTests`. */
object ExitOfferMath {
    private val hundred = BigDecimal(100)

    /**
     * Whole-percent discount of [offer] against [standard], rounded half-up. null when there is no
     * real discount (offer ≥ standard, non-positive prices), so the UI can never claim one.
     */
    fun discountPercent(standard: BigDecimal, offer: BigDecimal): Int? {
        if (standard.signum() <= 0 || offer.signum() < 0 || offer >= standard) return null
        val raw = (standard - offer).multiply(hundred).divide(standard, 12, RoundingMode.HALF_UP)
        val pct = raw.setScale(0, RoundingMode.HALF_UP).toInt()
        return if (pct in 1..100) pct else null
    }

    fun discountPercent(standard: Double, offer: Double): Int? =
        discountPercent(BigDecimal.valueOf(standard), BigDecimal.valueOf(offer))

    /**
     * Renewal disclosure shown directly beneath the CTA (brief §3 I; amendment ao: the offer product's own
     * standard price, which is also what it renews at).
     */
    fun renewalCopy(offer: String): String = "$offer/year. Renews automatically. Cancel anytime."
}

/** What X on the standard paywall does (brief §5). */
enum class PaywallCloseDecision {
    showExitOffer,
    dismiss,
    ;

    companion object {
        /** Eligible offer → exit offer, every time (amendment aa: no frequency cap); otherwise dismiss as normal. */
        fun onCloseStandard(offer: ExitOfferState?): PaywallCloseDecision {
            if (offer == null || !offer.isEligible) return dismiss
            return showExitOffer
        }
    }
}
