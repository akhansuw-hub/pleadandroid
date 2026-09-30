// DEBUG builds only (Swift `#if DEBUG` in PurchasesService.swift / ExitOffer.swift): the preview prices the demo
// harness and previews render. The release source set has a price-free twin, so no price literal ships.
package app.plead.android.services

object PreviewPrices {
    /**
     * £49.99 / year and £9.99 / week are the brief's prices. **£14.99 / month is a DEMO PLACEHOLDER**: the brief
     * deliberately leaves the monthly price to the store, so the real value only ever comes from Google Play.
     */
    fun products(trialEligible: Boolean, plans: Set<PurchasesService.Plan>): PaywallProducts? = PaywallProducts(
        weekly = if (plans.contains(PurchasesService.Plan.weekly)) {
            PaywallProduct(PurchasesService.weeklyID, "£9.99", PaywallProduct.Period.week, null)
        } else null,
        monthly = if (plans.contains(PurchasesService.Plan.monthly)) {
            PaywallProduct(PurchasesService.monthlyID, demoMonthlyPlaceholderPrice, PaywallProduct.Period.month, null)
        } else null,
        annual = if (plans.contains(PurchasesService.Plan.annual)) {
            PaywallProduct(PurchasesService.annualID, "£49.99", PaywallProduct.Period.year, 3)
        } else null,
        annualTrialEligible = trialEligible,
    )

    /** DEMO ONLY: a stand-in until `plead.monthly` is priced in the store. Never shipped. */
    const val demoMonthlyPlaceholderPrice = "£14.99"

    /** The brief's exit offer: £49.99 struck through, £24.99 (demo / previews only). */
    val exitOffer: ExitOfferPrices? = ExitOfferPrices(49_990_000, "£49.99", 24_990_000, "£24.99")
}
