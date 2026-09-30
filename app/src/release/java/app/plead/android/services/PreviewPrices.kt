// Release builds: no preview prices (Swift `#if DEBUG`). Prices only ever come from Google Play via RevenueCat.
package app.plead.android.services

object PreviewPrices {
    @Suppress("UNUSED_PARAMETER")
    fun products(trialEligible: Boolean, plans: Set<PurchasesService.Plan>): PaywallProducts? = null

    val exitOffer: ExitOfferPrices? = null
}
