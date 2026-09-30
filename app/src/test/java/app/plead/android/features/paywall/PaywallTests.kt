// Port of ArgueWinTests/PaywallTests.swift, plus the Android source guards (no "Premium", no price literal outside
// the debug source set) and the PaywallCopy half of `websiteDomain` (ModelDecodingTests.swift).
package app.plead.android.features.paywall

import androidx.compose.ui.geometry.Size
import app.plead.android.services.PaywallProduct
import app.plead.android.services.PaywallProducts
import app.plead.android.services.PurchasesService
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Three-plan hard paywall (docs/paywall-brief/three-plan/BRIEF.md, CONTRACTS-v2 amendment s). */
class PaywallThreePlanTests {
    private val annual = PaywallProduct(id = PurchasesService.annualID, price = "£49.99", period = PaywallProduct.Period.year, freeTrialDays = 3)
    private val monthly = PaywallProduct(id = PurchasesService.monthlyID, price = "£12.49", period = PaywallProduct.Period.month, freeTrialDays = null)
    private val weekly = PaywallProduct(id = PurchasesService.weeklyID, price = "£9.99", period = PaywallProduct.Period.week, freeTrialDays = null)

    private fun all(eligible: Boolean = true) = PaywallProducts(weekly = weekly, monthly = monthly, annual = annual, annualTrialEligible = eligible)

    // MARK: CTA + disclosure, all four states

    @Test fun annualTrialEligible() {
        val s = PaywallCTAState.make(PurchasesService.Plan.annual, all())
        assertEquals(PaywallCTAState.annualTrial(price = "£49.99/year", trialDays = 3), s)
        assertEquals("START 3-DAY FREE TRIAL", s.title)
        assertEquals("Free for 3 days, then £49.99/year. Cancel anytime.", s.disclosure)
        assertTrue(s.isTrial)
    }

    @Test fun annualNotEligible() {
        val s = PaywallCTAState.make(PurchasesService.Plan.annual, all(eligible = false))
        assertEquals("CONTINUE WITH ANNUAL", s.title)
        assertEquals("£49.99/year. Cancel anytime.", s.disclosure)
        assertFalse(s.isTrial)
        assertTrue(!s.title.contains("free", ignoreCase = true) && !s.disclosure.contains("free", ignoreCase = true))
    }

    @Test fun monthlySelected() {
        val s = PaywallCTAState.make(PurchasesService.Plan.monthly, all())
        assertEquals("CONTINUE — £12.49/MONTH", s.title)
        assertEquals("£12.49/month. Cancel anytime.", s.disclosure)
    }

    @Test fun weeklySelected() {
        val s = PaywallCTAState.make(PurchasesService.Plan.weekly, all())
        assertEquals("CONTINUE — £9.99/WEEK", s.title)
        assertEquals("£9.99/week. Cancel anytime.", s.disclosure)
    }

    /** Monthly / weekly never carry trial language, even when the annual trial is available. */
    @Test fun noTrialLanguageForMonthlyOrWeekly() {
        for (plan in listOf(PurchasesService.Plan.monthly, PurchasesService.Plan.weekly)) {
            val s = PaywallCTAState.make(plan, all(eligible = true))
            assertFalse(s.isTrial)
            for (text in listOf(s.title, s.disclosure)) {
                assertFalse(text, text.contains("free", ignoreCase = true))
                assertFalse(text, text.contains("trial", ignoreCase = true))
            }
        }
    }

    /** A misconfigured trial on monthly still produces no trial copy. */
    @Test fun monthlyWithIntroOfferStillNoTrial() {
        val m = monthly.copy(freeTrialDays = 7)
        val s = PaywallCTAState.make(
            PurchasesService.Plan.monthly,
            PaywallProducts(weekly = weekly, monthly = m, annual = annual, annualTrialEligible = true),
        )
        assertEquals(PaywallCTAState.monthly(price = "£12.49/month"), s)
    }

    // MARK: Plans: hiding and default selection

    @Test fun allThreeInOrder() {
        assertEquals(listOf(PurchasesService.Plan.annual, PurchasesService.Plan.monthly, PurchasesService.Plan.weekly), all().availablePlans)
        assertEquals(PurchasesService.Plan.annual, all().defaultPlan)
    }

    @Test fun missingProductIsHidden() {
        val p = PaywallProducts(weekly = weekly, monthly = null, annual = annual, annualTrialEligible = true)
        assertEquals(listOf(PurchasesService.Plan.annual, PurchasesService.Plan.weekly), p.availablePlans)
        assertEquals(PaywallCTAState.unavailable, PaywallCTAState.make(PurchasesService.Plan.monthly, p))
    }

    @Test fun annualMissingFallsToMonthly() {
        val p = PaywallProducts(weekly = weekly, monthly = monthly, annual = null, annualTrialEligible = true)
        assertEquals(listOf(PurchasesService.Plan.monthly, PurchasesService.Plan.weekly), p.availablePlans)
        assertEquals(PurchasesService.Plan.monthly, p.defaultPlan)
        assertEquals(PurchasesService.Plan.monthly, p.resolve(PurchasesService.Plan.annual))
        assertEquals(PurchasesService.Plan.weekly, p.resolve(PurchasesService.Plan.weekly))
        assertEquals("CONTINUE — £12.49/MONTH", PaywallCTAState.make(p.resolve(PurchasesService.Plan.annual), p).title)
    }

    @Test fun annualAndMonthlyMissingFallsToWeekly() {
        val p = PaywallProducts(weekly = weekly, monthly = null, annual = null, annualTrialEligible = false)
        assertEquals(PurchasesService.Plan.weekly, p.defaultPlan)
        assertEquals(PurchasesService.Plan.weekly, p.resolve(PurchasesService.Plan.annual))
    }

    @Test fun nothingLoadedIsNeutral() {
        assertTrue(PaywallProducts.empty.isEmpty)
        assertNull(PaywallProducts.empty.defaultPlan)
        for (plan in PurchasesService.Plan.entries) {
            val s = PaywallCTAState.make(plan, PaywallProducts.empty)
            assertEquals(PaywallCTAState.unavailable, s)
            assertFalse(s.isPurchasable)
            assertTrue(s.disclosure.isEmpty())
        }
    }

    @Test fun productIds() {
        assertEquals("plead.yearly", PurchasesService.annualID)
        assertEquals("plead.monthly", PurchasesService.monthlyID)
        assertEquals("plead.weekly", PurchasesService.weeklyID)
        assertEquals(listOf("plead.yearly", "plead.monthly", "plead.weekly"), PurchasesService.Plan.entries.map(PurchasesService::productID))
        assertEquals("premium", PurchasesService.entitlementID)   // internal RevenueCat name only
    }

    @Test fun perPeriodStrings() {
        assertEquals("£12.49 / month", monthly.perPeriod)
        assertEquals("£12.49/month", monthly.perPeriodCompact)
    }

    /** DEBUG only: the preview prices live in `src/debug` (this suite runs as `testDebugUnitTest`). */
    @Test fun demoFallbackPrices() {
        val p = PaywallProducts.preview()
        assertEquals("£49.99", p.annual?.price)
        assertEquals("£9.99", p.weekly?.price)
        assertEquals("£14.99", p.monthly?.price)   // PreviewPrices.demoMonthlyPlaceholderPrice
        assertTrue(p.monthly?.freeTrialDays == null && p.weekly?.freeTrialDays == null)
        assertEquals(
            PurchasesService.Plan.monthly,
            PaywallProducts.preview(plans = setOf(PurchasesService.Plan.monthly, PurchasesService.Plan.weekly)).defaultPlan,
        )
    }

    // MARK: Copy

    @Test fun copyMatchesTheBrief() {
        assertEquals("TAKE YOUR CASE TO COURT", PaywallCopy.headline)
        assertEquals("One subscription unlocks Plead for both you and your partner. Only one of you needs to pay.", PaywallCopy.sub)
        assertEquals("One subscription covers both of you.", PaywallCopy.coupleAccess)
        assertEquals("Your partner's subscription covers you both.", PaywallCopy.partnerPaid)
        assertEquals(listOf("Unlimited cases", "Present evidence", "AI court", "One plan, two people"), PaywallCopy.benefits.map { it.title })
        assertEquals(
            listOf(
                "Take as many arguments to court as you need.",
                "Add screenshots, photos and receipts.",
                "Multiple AI jurors deliberate before the judge rules.",
                "Your linked partner is included at no extra cost.",
            ),
            PaywallCopy.benefits.map { it.detail },
        )
    }

    @Test fun noPremiumWordAnywhere() {
        val strings = PaywallCopy.allUserFacing.toMutableList()
        for (plan in PurchasesService.Plan.entries) {
            for (eligible in listOf(true, false)) {
                val s = PaywallCTAState.make(plan, all(eligible = eligible))
                strings += listOf(s.title, s.disclosure)
            }
        }
        val offer = ExitOfferState.preview!!   // DEBUG preview
        strings += listOf(offer.ctaTitle, offer.subheadline, offer.renewalCopy, offer.discountLabel)
        for (s in strings) assertFalse(s, s.contains("premium", ignoreCase = true))
    }

    @Test fun benefitTilesReflow() {
        assertFalse(BenefitGrid.usesTwoByTwo(width = 353f, fontScale = 1f))
        assertFalse(BenefitGrid.usesTwoByTwo(width = 350f, fontScale = 1f))
        assertTrue(BenefitGrid.usesTwoByTwo(width = 300f, fontScale = 1f))
        assertTrue(BenefitGrid.usesTwoByTwo(width = 353f, fontScale = DynamicTypeScale.xxLarge))
    }

    @Test fun layoutProportions() {
        assertTrue(PaywallLayout.heroFraction in 0.28f..0.34f)
        assertTrue(PaywallLayout.ctaHeight in 60f..66f)
        assertTrue(PaywallRadius.card.value in 20f..24f)
        // Amendment z: the 16e-class viewport gives the hero 25%.
        assertEquals(245f, PaywallLayout.heroHeight(874f), 0f)
        assertEquals(211f, PaywallLayout.heroHeight(844f * 1f), 0f)
    }
}

/** The entrance used when the bloom doesn't play (re-shows, exit offer, partner-paid). */
class PaywallEntranceTests {
    private fun p(layer: PaywallEntranceLayer, index: Int = 0, rm: Boolean = false) =
        PaywallEntrance.parameters(layer, index = index, reduceMotion = rm)

    private fun close(a: Double, b: Double) = abs(a - b) < 0.0001

    @Test fun delaysPerLayer() {
        assertTrue(close(p(PaywallEntranceLayer.hero).delay, 0.0))
        assertTrue(close(p(PaywallEntranceLayer.hero).duration, 0.36))
        assertTrue(close(p(PaywallEntranceLayer.brand).delay, 0.10))
        assertTrue(close(p(PaywallEntranceLayer.brand).duration, 0.32))
        assertTrue(close(p(PaywallEntranceLayer.sub).delay, 0.14))
        assertTrue(close(p(PaywallEntranceLayer.tile, 0).delay, 0.24))
        assertTrue(close(p(PaywallEntranceLayer.tile, 3).delay, 0.54))
        assertTrue(close(p(PaywallEntranceLayer.plan, 0).delay, 0.40))
        assertTrue(close(p(PaywallEntranceLayer.plan, 1).delay, 0.52))
        assertTrue(close(p(PaywallEntranceLayer.plan, 2).delay, 0.64))
        assertTrue(close(p(PaywallEntranceLayer.cta).delay, 0.60))
    }

    @Test fun footerIsLast() {
        val others = PaywallEntranceLayer.entries.filter { it != PaywallEntranceLayer.footer }.map {
            p(it, if (it == PaywallEntranceLayer.plan) 2 else if (it == PaywallEntranceLayer.tile) 3 else 0).delay
        }
        assertTrue(p(PaywallEntranceLayer.footer).delay > others.max())
    }

    @Test fun tilesAndPlansStagger() {
        assertTrue(close(p(PaywallEntranceLayer.tile, 1).delay - p(PaywallEntranceLayer.tile, 0).delay, 0.10))
        assertTrue(close(p(PaywallEntranceLayer.plan, 1).delay - p(PaywallEntranceLayer.plan, 0).delay, 0.12))
    }

    @Test fun heroScalesFrom098AndRisesFourToSix() {
        // Amendment v: a small fade + 4–6 pt rise (0.35–0.45 s), no large zoom; Reduce Motion drops the rise.
        val hero = p(PaywallEntranceLayer.hero)
        assertEquals(0.98f, hero.startScale, 0f)
        assertTrue(hero.offset.height in 4f..6f && hero.offset.width == 0f)
        assertTrue(hero.duration in 0.35..0.45)
        assertEquals(Size.Zero, p(PaywallEntranceLayer.hero, rm = true).offset)
    }

    @Test fun risesStayWithinEightToTwelve() {
        for (layer in PaywallEntranceLayer.entries.filter { it != PaywallEntranceLayer.hero }) {
            assertTrue("$layer", p(layer).offset.height in 8f..12f)
        }
    }

    @Test fun everyLayerStartsHittable() {
        for (layer in PaywallEntranceLayer.entries) {
            assertTrue("$layer: opacity 0 would drop it from hit-testing", p(layer).startOpacity > 0)
        }
    }

    @Test fun reduceMotionIsOpacityOnly() {
        for (layer in PaywallEntranceLayer.entries) {
            val rm = p(layer, 1, rm = true)
            assertTrue("$layer", rm.isOpacityOnly)
            // Same cadence.
            assertTrue(close(rm.delay, p(layer, 1).delay))
        }
    }

    @Test fun playsOnlyWhenTheBloomDoesNot() {
        assertTrue(PaywallEntrance.plays(opening = PaywallOpeningMode.none))
        assertFalse(PaywallEntrance.plays(opening = PaywallOpeningMode.full))
        assertFalse(PaywallEntrance.plays(opening = PaywallOpeningMode.fade))
    }

    /** Re-shows, the exit offer and partner-paid never bloom, so they assemble; `AWPaywallOpening NO` too. */
    @Test fun entranceCasesMatchTheOpeningRule() {
        fun mode(i: PaywallOpeningRule.Input) = PaywallOpeningRule.mode(i)
        assertTrue(PaywallEntrance.plays(mode(PaywallOpeningRule.Input(stage = PaywallStage.exitOffer))))
        assertTrue(PaywallEntrance.plays(mode(PaywallOpeningRule.Input(partnerPaid = true))))
        assertTrue(PaywallEntrance.plays(mode(PaywallOpeningRule.Input(shownThisLaunch = true))))
        assertTrue(PaywallEntrance.plays(mode(PaywallOpeningRule.Input(disabled = true))))
        assertFalse(PaywallEntrance.plays(mode(PaywallOpeningRule.Input())))
    }

    @Test fun wholeEntranceIsQuick() {
        assertTrue(PaywallEntrance.total() < 1.2)
        assertTrue(close(PaywallEntranceTokens.exitCrossfade, 0.3))
        assertEquals(listOf(0.97f, 1.03f, 1.0f), PaywallEntranceTokens.stampSpring)
    }

    @Test fun directorStates() {
        val d = PaywallEntranceDirector()
        assertEquals(PaywallEntranceState.pending, d.state)
        d.begin(animated = true)
        assertEquals(PaywallEntranceState(shown = true, animated = true), d.state)
        val b = PaywallEntranceDirector()
        b.begin(animated = false)
        assertEquals(PaywallEntranceState.settled, b.state)
    }

    // Android: the stamp lands halfway through the brand layer (0.10 + 0.32 / 2).
    @Test fun stampLandsWithTheBrandLayer() {
        assertTrue(close(ExitOfferStamp.landsAt, 0.26))
    }
}

/** `websiteDomain` (ModelDecodingTests.swift), the PaywallCopy half (amendment ap). */
class PaywallWebsiteDomainTests {
    @Test fun websiteDomain() {
        assertEquals("https://plead-drab.vercel.app/terms/", PaywallCopy.termsURL)
        assertEquals("https://plead-drab.vercel.app/privacy/", PaywallCopy.privacyURL)
    }
}

/**
 * Source guards (CLAUDE.md: never "Premium" in user-facing copy; no hard-coded price ships in a release build).
 * iOS checks the copy tables and `#if DEBUG` fences; Android checks the string literals of the Kotlin sources that
 * ship: `features/paywall/` for the "Premium" word, and every `src/main` + `src/release` source for a price.
 */
class PaywallSourceGuardTests {
    private fun sourceRoot(set: String): File =
        listOf(File("src/$set/java"), File("app/src/$set/java")).firstOrNull { it.exists() } ?: File("src/$set/java")

    private fun kotlinFiles(dir: File): List<File> =
        if (!dir.exists()) emptyList() else dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun paywallSourcesAreThere() {
        val files = kotlinFiles(File(sourceRoot("main"), "app/plead/android/features/paywall")).map { it.name }.toSet()
        val expected = setOf(
            "ExitOffer.kt", "ExitOfferPaywallView.kt", "PaywallComponents.kt", "PaywallCopy.kt", "PaywallCourtroomAnimator.kt",
            "PaywallCourtroomHero.kt", "PaywallCourtroomSprites.kt", "PaywallEntrance.kt", "PaywallGateView.kt", "PaywallOpening.kt",
            "PaywallOpeningHaptics.kt", "PaywallPalette.kt", "PaywallView.kt", "PixelGlyphs.kt",
        )
        assertTrue("missing ${expected - files}", files.containsAll(expected))
    }

    @Test fun noPremiumInPaywallLiterals() {
        val dir = File(sourceRoot("main"), "app/plead/android/features/paywall")
        for (file in kotlinFiles(dir)) {
            for (literal in KotlinLiterals.strings(file.readText())) {
                assertFalse("${file.name}: \"$literal\"", literal.contains("premium", ignoreCase = true))
            }
        }
    }

    @Test fun noPriceLiteralInShippingSources() {
        val price = Regex("""[£€]\s?\d|\$\d|\d[.,]\d{2}\s?[£€]""")
        val files = kotlinFiles(sourceRoot("main")) + kotlinFiles(sourceRoot("release"))
        assertTrue(files.size > 20)
        for (file in files) {
            for (literal in KotlinLiterals.strings(file.readText())) {
                assertFalse("${file.path}: \"$literal\"", price.containsMatchIn(literal))
            }
        }
    }

    @Test fun literalScannerSeesStringsNotComments() {
        val src = "// \"£1\" in a comment\n/* \"£2\" */ val a = \"x \\\"q\\\"\"; val c = '\"'; val r = \"\"\"raw\"\"\""
        assertEquals(listOf("x \"q\"", "raw"), KotlinLiterals.strings(src))
    }
}

/** A small Kotlin lexer for the guards: the contents of string literals, comments and char literals skipped. */
object KotlinLiterals {
    fun strings(src: String): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        val n = src.length
        while (i < n) {
            val c = src[i]
            when {
                src.startsWith("//", i) -> i = src.indexOf('\n', i).let { if (it < 0) n else it }
                src.startsWith("/*", i) -> i = src.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                src.startsWith("\"\"\"", i) -> {
                    val end = src.indexOf("\"\"\"", i + 3).let { if (it < 0) n else it }
                    out += src.substring(i + 3, end)
                    i = end + 3
                }
                c == '"' -> {
                    val sb = StringBuilder()
                    i++
                    while (i < n && src[i] != '"' && src[i] != '\n') {
                        if (src[i] == '\\' && i + 1 < n) {
                            sb.append(src[i + 1]); i += 2
                        } else {
                            sb.append(src[i]); i++
                        }
                    }
                    out += sb.toString()
                    i++
                }
                c == '\'' -> {
                    val end = if (i + 1 < n && src[i + 1] == '\\') src.indexOf('\'', i + 3) else i + 2
                    i = if (end < 0) n else end + 1
                }
                else -> i++
            }
        }
        return out
    }
}
