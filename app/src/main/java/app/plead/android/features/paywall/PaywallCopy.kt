// Port of ArgueWin/Features/Paywall/PaywallCopy.swift.
package app.plead.android.features.paywall

import app.plead.android.services.PaywallProducts
import app.plead.android.services.PurchasesService

/**
 * CTA + disclosure for the selected plan (three-plan brief §3, amendment s). Pure, unit-tested in `PaywallTests`.
 * Every price and the trial come from Google Play via `PaywallProducts`; nothing is invented. Only Annual can ever
 * produce trial copy, and only when the store says this user is eligible (same render pass everywhere).
 */
sealed class PaywallCTAState {
    /** Annual selected and the store says this user may take the free trial. */
    data class annualTrial(val price: String, val trialDays: Int) : PaywallCTAState()

    /** Annual selected, no trial for this user. */
    data class annual(val price: String) : PaywallCTAState()

    /** Monthly selected (never a trial). */
    data class monthly(val price: String) : PaywallCTAState()

    /** Weekly selected (never a trial). */
    data class weekly(val price: String) : PaywallCTAState()

    /** Prices haven't loaded (or the store is unreachable, or the selected plan isn't on sale). */
    data object unavailable : PaywallCTAState()

    /** "START 3-DAY FREE TRIAL" · "CONTINUE WITH ANNUAL" · "CONTINUE — £14.99/MONTH" · "CONTINUE — £9.99/WEEK" */
    val title: String
        get() = when (this) {
            is annualTrial -> "START $trialDays-DAY FREE TRIAL"
            is annual -> "CONTINUE WITH ANNUAL"
            is monthly -> "CONTINUE — ${price.uppercase()}"
            is weekly -> "CONTINUE — ${price.uppercase()}"
            unavailable -> "LOADING PRICES"
        }

    /** Store renewal disclosure shown directly beneath the CTA. */
    val disclosure: String
        get() = when (this) {
            is annualTrial -> "Free for $trialDays days, then $price. Cancel anytime."
            is annual -> "$price. Cancel anytime."
            is monthly -> "$price. Cancel anytime."
            is weekly -> "$price. Cancel anytime."
            unavailable -> ""
        }

    val isPurchasable: Boolean get() = this != unavailable
    val isTrial: Boolean get() = this is annualTrial

    companion object {
        fun make(plan: PurchasesService.Plan, products: PaywallProducts): PaywallCTAState = when (plan) {
            PurchasesService.Plan.annual -> {
                val product = products.annual
                if (product == null) {
                    unavailable
                } else {
                    val days = product.freeTrialDays
                    if (products.annualTrialEligible && days != null && days > 0) {
                        annualTrial(price = product.perPeriodCompact, trialDays = days)
                    } else {
                        annual(price = product.perPeriodCompact)
                    }
                }
            }
            PurchasesService.Plan.monthly -> products.monthly?.let { monthly(price = it.perPeriodCompact) } ?: unavailable
            PurchasesService.Plan.weekly -> products.weekly?.let { weekly(price = it.perPeriodCompact) } ?: unavailable
        }
    }
}

/**
 * One benefit tile: a pixel icon, a short title, and a supporting line (shown on roomy layouts, otherwise read
 * out by TalkBack).
 */
data class PaywallBenefit(
    val icon: PerkIcon,
    val title: String,
    val detail: String,
) {
    val id: PerkIcon get() = icon
}

object PaywallCopy {
    const val strapline = "A HAPPIER KIND OF DEBATE"
    const val headline = "TAKE YOUR CASE TO COURT"
    const val sub = "One subscription unlocks Plead for both you and your partner. Only one of you needs to pay."
    const val coupleAccess = "One subscription covers both of you."
    const val partnerPaid = "Your partner's subscription covers you both."
    const val partnerPaidDetail = "There's nothing for you to pay."
    const val bestValue = "BEST VALUE"

    val benefits: List<PaywallBenefit> = listOf(
        PaywallBenefit(icon = PerkIcon.gavel, title = "Unlimited cases", detail = "Take as many arguments to court as you need."),
        PaywallBenefit(icon = PerkIcon.evidence, title = "Present evidence", detail = "Add screenshots, photos and receipts."),
        PaywallBenefit(icon = PerkIcon.aiJudge, title = "AI court", detail = "Multiple AI jurors deliberate before the judge rules."),
        PaywallBenefit(icon = PerkIcon.couple, title = "One plan, two people", detail = "Your linked partner is included at no extra cost."),
    )

    /** Plan card name. */
    fun planName(plan: PurchasesService.Plan): String = when (plan) {
        PurchasesService.Plan.annual -> "ANNUAL"
        PurchasesService.Plan.monthly -> "MONTHLY"
        PurchasesService.Plan.weekly -> "WEEKLY"
    }

    /** After a purchase / restore, while the backend's couple record catches up. */
    const val activating = "Payment received. Plead will unlock for both of you in a moment."
    const val restoredActivating = "Restored. Plead will unlock for both of you in a moment."

    /** Every user-facing string above (tests assert none says "Premium"). */
    val allUserFacing: List<String>
        get() = listOf(strapline, headline, sub, coupleAccess, partnerPaid, partnerPaidDetail, bestValue, activating, restoredActivating) +
            benefits.flatMap { listOf(it.title, it.detail) } + PurchasesService.Plan.entries.map(::planName)

    const val termsURL = "https://plead-drab.vercel.app/terms/"
    const val privacyURL = "https://plead-drab.vercel.app/privacy/"
}
