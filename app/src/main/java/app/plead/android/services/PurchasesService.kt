// Port of ArgueWin/Services/PurchasesService.swift. Android: RevenueCat `purchases-android` on Google Play (amendment
// az), entitlement `premium`, default offering + `exit_offer`. Every price string comes from `StoreProduct`.
@file:Suppress("EnumEntryName")

package app.plead.android.services

import android.app.Activity
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.plead.android.BuildConfig
import app.plead.android.app.DemoHarness
import app.plead.android.app.PleadApplication
import app.plead.android.models.EdgeError
import com.revenuecat.purchases.LogLevel
import com.revenuecat.purchases.Offering
import com.revenuecat.purchases.Package
import com.revenuecat.purchases.PurchaseParams
import com.revenuecat.purchases.Purchases
import com.revenuecat.purchases.PurchasesConfiguration
import com.revenuecat.purchases.PurchasesErrorCode
import com.revenuecat.purchases.PurchasesException
import com.revenuecat.purchases.PurchasesTransactionException
import com.revenuecat.purchases.awaitGetProducts
import com.revenuecat.purchases.awaitLogIn
import com.revenuecat.purchases.awaitLogOut
import com.revenuecat.purchases.awaitOfferings
import com.revenuecat.purchases.awaitPurchase
import com.revenuecat.purchases.awaitRestore
import com.revenuecat.purchases.models.Period
import com.revenuecat.purchases.models.StoreProduct
import java.lang.ref.WeakReference
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * One sellable plan as the paywall shows it. Every string comes from Google Play (via RevenueCat);
 * nothing here is invented except the DEBUG/preview fallback below.
 */
data class PaywallProduct(
    val id: String,
    /** Localized price, e.g. "£9.99". */
    val price: String,
    val period: Period,
    /**
     * Length of the introductory free trial in days, when the product has one configured.
     * Only ever set for the annual product; whether the user may take it is `PaywallProducts.annualTrialEligible`.
     */
    val freeTrialDays: Int? = null,
) {
    enum class Period(val rawValue: String) { week("week"), month("month"), year("year") }

    /** "£9.99 / week" (plan card). */
    val perPeriod: String get() = "$price / ${period.rawValue}"

    /** "£9.99/week" (CTA + disclosure). */
    val perPeriodCompact: String get() = "$price/${period.rawValue}"
}

/**
 * What the paywall needs to render: the three plans (any may be missing: that plan is hidden) and the annual
 * trial eligibility.
 */
data class PaywallProducts(
    val weekly: PaywallProduct?,
    val monthly: PaywallProduct? = null,
    val annual: PaywallProduct?,
    /**
     * True only when the annual product's default subscription option has a free-trial phase for this Google
     * account (Google Play only offers the trial to eligible users; RevenueCat's iOS eligibility check has no
     * Android counterpart). Never assumed. Monthly and weekly never carry a trial.
     */
    val annualTrialEligible: Boolean,
) {
    fun product(plan: PurchasesService.Plan): PaywallProduct? = when (plan) {
        PurchasesService.Plan.annual -> annual
        PurchasesService.Plan.monthly -> monthly
        PurchasesService.Plan.weekly -> weekly
    }

    /** The plans to show, in display order (Annual → Monthly → Weekly). A plan whose product didn't load is hidden. */
    val availablePlans: List<PurchasesService.Plan> get() = PurchasesService.Plan.entries.filter { product(it) != null }

    /** Annual when it loaded, otherwise the next plan down (monthly, then weekly). */
    val defaultPlan: PurchasesService.Plan? get() = availablePlans.firstOrNull()

    /** `plan` if it is on sale, otherwise the default. Keeps a selection valid when products change. */
    fun resolve(plan: PurchasesService.Plan): PurchasesService.Plan = if (product(plan) != null) plan else (defaultPlan ?: plan)

    val isEmpty: Boolean get() = availablePlans.isEmpty()

    companion object {
        val empty = PaywallProducts(weekly = null, monthly = null, annual = null, annualTrialEligible = false)

        /**
         * DEBUG / preview / demo only. The prices live in `src/debug` (`PreviewPrices`); a release build has no price
         * literal at all and gets [empty] (it shows the loading / retry state instead).
         * `plans` limits which products "loaded" (`AWProducts annual,weekly`).
         */
        fun preview(trialEligible: Boolean = true, plans: Set<PurchasesService.Plan> = PurchasesService.Plan.entries.toSet()): PaywallProducts =
            PreviewPrices.products(trialEligible, plans) ?: empty
    }
}

/**
 * The exit offer's two prices as the store reports them: the standard annual price (struck through) and the
 * `plead.discount` price, localized, plus micros for the discount maths. Swift builds `ExitOfferState` (wave 3c,
 * Features/Paywall/ExitOffer.swift) straight from these four values; on Android the paywall builds it from this type,
 * so the billing layer does not depend on the paywall feature.
 */
data class ExitOfferPrices(
    val standardPriceMicros: Long,
    val standardLocalized: String,
    val offerPriceMicros: Long,
    val offerLocalized: String,
) {
    /** A real discount (the offer is cheaper than the standard annual). */
    val isDiscount: Boolean get() = offerPriceMicros in 1 until standardPriceMicros

    companion object {
        /** DEBUG / demo only (`AWExitOffer`): from `src/debug` `PreviewPrices`; null in a release build. */
        val preview: ExitOfferPrices? get() = PreviewPrices.exitOffer
    }
}

/**
 * RevenueCat wrapper. App user ID = Supabase auth uid.
 * NOTE: entitlement truth for the app is `couples.premium_until` (see CaseStore.isPremium);
 * RevenueCat is used only to sell / restore. The webhook updates the couple row for both partners.
 */
class PurchasesService(private val application: Application? = PleadApplication.contextOrNull as? Application) {
    /** Display order: Annual → Monthly → Weekly. */
    enum class Plan(val rawValue: String) {
        annual("annual"), monthly("monthly"), weekly("weekly");

        companion object {
            fun fromRaw(raw: String?): Plan? = entries.firstOrNull { it.rawValue == raw }
        }
    }

    enum class LoadState { idle, loading, loaded, failed }

    var isConfigured: Boolean by mutableStateOf(false)
        private set
    var products: PaywallProducts by mutableStateOf(PaywallProducts.empty)
        private set
    var loadState: LoadState by mutableStateOf(LoadState.idle)
        private set
    var isPurchasing: Boolean by mutableStateOf(false)
        private set

    /**
     * This user bought or restored during this session: the couple's premium is theirs, so the
     * gate must not greet them with "your partner already unlocked".
     */
    var unlockedThisSession: Boolean by mutableStateOf(false)
        private set

    val offeringsLoaded: Boolean get() = loadState == LoadState.loaded || loadState == LoadState.failed

    private var packages: MutableMap<Plan, Package> = mutableMapOf()
    private var storeProducts: MutableMap<Plan, StoreProduct> = mutableMapOf()
    private var exitOfferPackage: Package? = null
    private var exitOfferProduct: StoreProduct? = null

    /** Cached result of `loadExitOffer()` for this session (null = not eligible / not configured). */
    var exitOffer: ExitOfferPrices? by mutableStateOf(null)
        private set
    var exitOfferLoaded: Boolean = false
        private set

    /**
     * The Supabase uid RevenueCat was last identified with (app user id = uid). Set as soon as a
     * session exists, including the anonymous one created at THAT'S ME, so a purchase attaches to it;
     * linking Google / Apple / an email keeps the uid, so it never changes then (amendment p).
     */
    var identifiedUserId: UUID? by mutableStateOf(null)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    init {
        // Demo harness / previews have no RevenueCat key: show the fallback prices so the screen is reviewable.
        if (BuildConfig.DEBUG && (AppConfig.revenueCatAPIKey == null || DemoHarness.isDemo)) {
            // `AWProducts annual,weekly`: only these "loaded" (the others are hidden, as when the store fails one).
            val plans = DemoHarness.products?.mapNotNull(Plan::fromRaw)?.toSet() ?: Plan.entries.toSet()
            if (DemoHarness.pricesLoading) {
                // `AWPricesLoading YES`: metadata never arrives (the neutral skeleton state).
                loadState = LoadState.loading
            } else {
                products = PaywallProducts.preview(trialEligible = DemoHarness.trialEligible, plans = plans)
                loadState = LoadState.loaded
            }
            exitOffer = if (DemoHarness.exitOfferEligible) ExitOfferPrices.preview else null
            exitOfferLoaded = true
        }
    }

    fun configure(appUserID: UUID?) {
        if (appUserID != null) identifiedUserId = appUserID
        val key = AppConfig.revenueCatAPIKey ?: return
        val app = application ?: return
        if (!isConfigured) {
            val builder = PurchasesConfiguration.Builder(app, key)
            appUserID?.let { builder.appUserID(it.toString().lowercase()) }
            Purchases.logLevel = LogLevel.WARN
            Purchases.configure(builder.build())
            isConfigured = true
            syncAttribution()
        } else if (appUserID != null) {
            scope.launch {
                runCatching { Purchases.sharedInstance.awaitLogIn(appUserID.toString().lowercase()) }
                syncAttribution()
            }
        }
    }

    /**
     * RevenueCat ↔ AppsFlyer (amendment at): device identifiers and the AppsFlyer id as subscriber attributes, so
     * RevenueCat's AppsFlyer integration attributes purchases server-side. Called after configure / logIn.
     * No-op while AppsFlyer is off.
     */
    fun syncAttribution() {
        if (!isConfigured) return
        val uid = Attribution.appsFlyerUID ?: return
        Purchases.sharedInstance.collectDeviceIdentifiers()
        Purchases.sharedInstance.setAppsflyerID(uid)
    }

    suspend fun logOut() {
        unlockedThisSession = false
        identifiedUserId = null
        if (isConfigured) {
            exitOffer = null; exitOfferLoaded = false; exitOfferPackage = null; exitOfferProduct = null
        }
        if (!isConfigured || Purchases.sharedInstance.isAnonymous) return
        runCatching { Purchases.sharedInstance.awaitLogOut() }
    }

    /**
     * Loads the three plans: packages of the current offering matched by product id, then by package type
     * (annual / monthly / weekly), then a direct product fetch for whatever is still missing. A plan
     * that can't be loaded stays null (the paywall hides it). The annual free trial is offered only when its
     * default subscription option starts with a free phase (Google Play only returns eligible offers).
     */
    suspend fun loadOfferings() {
        if (!isConfigured) {
            if (loadState == LoadState.idle) loadState = LoadState.failed
            return
        }
        loadState = LoadState.loading
        packages = mutableMapOf(); storeProducts = mutableMapOf()
        val offering = runCatching { Purchases.sharedInstance.awaitOfferings().current }.getOrNull()
        if (offering != null) {
            for (p in offering.availablePackages) {
                Plan.entries.firstOrNull { productID(it) == baseId(p.product) }?.let { packages[it] = p }
            }
            if (packages[Plan.annual] == null) offering.annual?.let { packages[Plan.annual] = it }
            if (packages[Plan.monthly] == null) offering.monthly?.let { packages[Plan.monthly] = it }
            if (packages[Plan.weekly] == null) offering.weekly?.let { packages[Plan.weekly] = it }
            for ((plan, p) in packages) storeProducts[plan] = p.product
        }
        val missing = Plan.entries.filter { storeProducts[it] == null }
        if (missing.isNotEmpty()) {
            val raw = runCatching { Purchases.sharedInstance.awaitGetProducts(missing.map(::productID)) }.getOrDefault(emptyList())
            for (plan in missing) raw.firstOrNull { baseId(it) == productID(plan) }?.let { storeProducts[plan] = it }
        }

        val eligible = storeProducts[Plan.annual]?.let(::freeTrialDays) != null
        products = PaywallProducts(
            weekly = storeProducts[Plan.weekly]?.let { product(it, Plan.weekly) },
            monthly = storeProducts[Plan.monthly]?.let { product(it, Plan.monthly) },
            annual = storeProducts[Plan.annual]?.let { product(it, Plan.annual) },
            annualTrialEligible = eligible,
        )
        loadState = if (products.isEmpty) LoadState.failed else LoadState.loaded
    }

    // MARK: Exit offer

    /**
     * The exit offer, or null when it must not be shown. Offering `exit_offer` (fallback: product
     * `plead.discount`). Amendment ao: `plead.discount` has no intro offer or trial; its own standard annual
     * price (~50% below `plead.yearly`, renewing at that price) is the offer price, and any intro discount is
     * ignored and never advertised. Struck-through standard price = `plead.yearly`. Shown only when both
     * products load and the offer is a real discount. Cached per session.
     */
    suspend fun loadExitOffer(): ExitOfferPrices? {
        if (exitOfferLoaded) return exitOffer
        if (!isConfigured) return null
        var pkg: Package? = null
        val offering: Offering? = runCatching { Purchases.sharedInstance.awaitOfferings().getOffering(exitOfferingID) }.getOrNull()
        if (offering != null) {
            pkg = offering.availablePackages.firstOrNull { baseId(it.product) == annualOfferID } ?: offering.annual ?: offering.availablePackages.firstOrNull()
        }
        var product = pkg?.product
        if (product == null) {
            product = runCatching { Purchases.sharedInstance.awaitGetProducts(listOf(annualOfferID)) }.getOrDefault(emptyList()).firstOrNull()
        }
        var state: ExitOfferPrices? = null
        if (product != null) {
            if (storeProducts[Plan.annual] == null) loadOfferings()
            // No annual product, no comparison: never measure the offer against itself.
            val standard = storeProducts[Plan.annual]
            if (standard != null && baseId(standard) != baseId(product)) {
                val s = basePrice(standard)
                val o = basePrice(product)
                state = ExitOfferPrices(s.amountMicros, s.formatted, o.amountMicros, o.formatted).takeIf { it.isDiscount }
            }
        }
        exitOfferPackage = pkg
        exitOfferProduct = product
        exitOffer = state
        exitOfferLoaded = true
        return state
    }

    /**
     * Where Google Play's purchase sheet is shown from (RevenueCat needs an Activity). Set by MainActivity.
     */
    var activity: WeakReference<Activity>? = null

    /**
     * Buys the exit-offer product `plead.discount` at its own standard price. Never the standard annual.
     * Returns true when the purchase completed (not cancelled).
     */
    suspend fun purchaseExitOffer(): Boolean {
        val activity = activity?.get()
        if (!isConfigured || exitOffer == null || (exitOfferPackage == null && exitOfferProduct == null) || activity == null) {
            throw storeUnavailable()
        }
        isPurchasing = true
        try {
            val params = exitOfferPackage?.let { PurchaseParams.Builder(activity, it) } ?: PurchaseParams.Builder(activity, exitOfferProduct!!)
            Purchases.sharedInstance.awaitPurchase(params.build())
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled || e.code == PurchasesErrorCode.PurchaseCancelledError) return false
            throw e
        } finally {
            isPurchasing = false
        }
        unlockedThisSession = true
        return true
    }

    /** Returns true when the purchase completed (not cancelled). */
    suspend fun purchase(plan: Plan): Boolean {
        val activity = activity?.get()
        if (!isConfigured || (packages[plan] == null && storeProducts[plan] == null) || activity == null) {
            throw storeUnavailable()
        }
        isPurchasing = true
        try {
            val params = packages[plan]?.let { PurchaseParams.Builder(activity, it) } ?: PurchaseParams.Builder(activity, storeProducts[plan]!!)
            Purchases.sharedInstance.awaitPurchase(params.build())
        } catch (e: PurchasesTransactionException) {
            if (e.userCancelled || e.code == PurchasesErrorCode.PurchaseCancelledError) return false
            throw e
        } finally {
            isPurchasing = false
        }
        unlockedThisSession = true
        return true
    }

    /** True when the Google account holds an active `premium` entitlement. */
    suspend fun restore(): Boolean {
        if (!isConfigured) return false
        isPurchasing = true
        try {
            val info = Purchases.sharedInstance.awaitRestore()
            val active = info.entitlements[entitlementID]?.isActive == true
            if (active) unlockedThisSession = true
            return active
        } catch (e: PurchasesException) {
            throw e
        } finally {
            isPurchasing = false
        }
    }

    /** Demo harness: a "purchase" that just marks this session as the payer. */
    fun demoMarkUnlocked() {
        unlockedThisSession = true
    }

    private fun storeUnavailable() =
        EdgeError(code = "store_unavailable", message = "Google Play isn't available right now. Please try again later.")

    companion object {
        const val weeklyID = "plead.weekly"
        const val monthlyID = "plead.monthly"
        const val annualID = "plead.yearly"

        fun productID(plan: Plan): String = when (plan) {
            Plan.annual -> annualID
            Plan.monthly -> monthlyID
            Plan.weekly -> weeklyID
        }

        const val entitlementID = "premium"

        /** Exit offer (CONTRACTS-v2 amendments f, ao): same-group product whose standard annual price is ~50% below `plead.yearly`. */
        const val annualOfferID = "plead.discount"
        const val exitOfferingID = "exit_offer"

        /** Google Play subscription ids come back as `productId:basePlanId`; the plan is keyed by the product id. */
        fun baseId(product: StoreProduct): String = product.id.substringBefore(':')

        /** The recurring (full) price of the default option, or the product price. Never an intro / trial phase. */
        private fun basePrice(p: StoreProduct) = p.defaultOption?.fullPricePhase?.price ?: p.price

        /** Days of the default option's free phase (Google Play only returns offers this account is eligible for). */
        fun freeTrialDays(p: StoreProduct): Int? {
            val free = p.defaultOption?.freePhase ?: return null
            val period = free.billingPeriod
            return when (period.unit) {
                Period.Unit.DAY -> period.value
                Period.Unit.WEEK -> period.value * 7
                else -> null
            }
        }

        private fun product(p: StoreProduct, plan: Plan): PaywallProduct {
            val fallback = when (plan) {
                Plan.annual -> PaywallProduct.Period.year
                Plan.monthly -> PaywallProduct.Period.month
                Plan.weekly -> PaywallProduct.Period.week
            }
            val period = when (p.period?.unit) {
                Period.Unit.WEEK -> PaywallProduct.Period.week
                Period.Unit.MONTH -> PaywallProduct.Period.month
                Period.Unit.YEAR -> PaywallProduct.Period.year
                else -> fallback
            }
            // Only the annual plan may advertise a trial (brief §1): monthly / weekly never carry trial copy,
            // even if an intro offer were misconfigured on them.
            val trialDays = if (plan == Plan.annual) freeTrialDays(p) else null
            return PaywallProduct(id = baseId(p), price = basePrice(p).formatted, period = period, freeTrialDays = trialDays)
        }
    }
}
