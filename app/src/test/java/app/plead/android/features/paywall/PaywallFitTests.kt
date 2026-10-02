// Real-device fix 2026-10-02: the purchase button sat half under the pinned legal footer on a 3-button-navigation
// phone. These pin the fitting rule (PaywallFit) and render the paywall, the exit offer and the partner-paid state at
// short Android screen sizes with simulated status / navigation bars, asserting the whole button is above the fold.
package app.plead.android.features.paywall

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.plead.android.app.LaunchArguments
import app.plead.android.designsystem.LocalReduceMotion
import app.plead.android.designsystem.PleadTheme
import app.plead.android.services.PreviewData
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The pure rule. */
class PaywallFitRuleTests {
    private fun fit(available: Float, full: Float, cta: Float = full - 40f, compact: Boolean = false) =
        PaywallFit.fit(standard = 200f, softFloor = 170f, hardFloor = 70f, available = available, fullNeed = full, ctaNeed = cta, compact = compact)

    @Test fun fitsAsOnIosKeepsTheIosHero() {
        assertEquals(PaywallFit.Result(200f, wantsCompact = false), fit(available = 900f, full = 650f))
    }

    @Test fun firstGivesUpGalleryDownToTheSoftFloor() {
        assertEquals(PaywallFit.Result(180f, wantsCompact = false), fit(available = 830f, full = 650f))
    }

    @Test fun belowTheSoftFloorAsksForTheCompactBody() {
        val r = fit(available = 780f, full = 650f)
        assertTrue(r.wantsCompact)
        assertEquals(170f, r.hero) // the CTA (610) fits with the soft floor meanwhile
    }

    @Test fun compactFitsTheWholeBlockAboveTheHardFloor() {
        assertEquals(PaywallFit.Result(130f, wantsCompact = false), fit(available = 780f, full = 650f, compact = true))
    }

    @Test fun compactThenKeepsOnlyTheButtonInView() {
        // Whole block needs a 30 pt hero (below the hard floor); the button alone allows 70.
        assertEquals(PaywallFit.Result(70f, wantsCompact = false), fit(available = 680f, full = 650f, cta = 610f, compact = true))
        // Even the button can't fit: the hard floor, and the page scrolls.
        assertEquals(PaywallFit.Result(70f, wantsCompact = false), fit(available = 600f, full = 650f, cta = 610f, compact = true))
    }

    @Test fun neverTallerThanTheIosHero() {
        val r = PaywallFit.fit(standard = 150f, softFloor = 170f, hardFloor = 70f, available = 700f, fullNeed = 600f, ctaNeed = 560f, compact = false)
        assertTrue(r.hero <= 150f)
    }

    @Test fun softFloorKeepsTheJudgeBelowTheStatusBar() {
        // 360 dp wide: the judge's frame top is 141.5 dp above the art's bottom edge.
        assertEquals(24f + 141.53f + PaywallFit.judgeClearance, PaywallFit.softFloor(360f, 24f), 0.1f)
        assertEquals(24f + PaywallFit.hardFloorBelowInset, PaywallFit.hardFloor(24f))
    }

    @Test fun squeezedHeroMovesTheArtSoTheJudgeStaysInView() {
        val p = PaywallCourtroomPlacement(hero = androidx.compose.ui.geometry.Size(360f, 80f), keepClearTop = 28f)
        val judgeTop = p.art.top + PaywallFit.judgeTopPixel * p.pointsPerPixel
        assertEquals(28f, judgeTop, 0.01f)
        assertTrue("the art still covers the hero's top", p.art.top <= 0f)
        // Default (iOS) placement: bottom-aligned.
        val ios = PaywallCourtroomPlacement(hero = androidx.compose.ui.geometry.Size(402f, 245f))
        assertEquals(245f, ios.art.bottom, 0.01f)
    }

    @Test fun compactGridStaysFourUpOnlyWhileTilesKeepTheirWidth() {
        assertTrue(BenefitGrid.usesTwoByTwo(width = 320f, fontScale = 1f)) // iOS rule unchanged
        assertFalse(BenefitGrid.usesTwoByTwo(width = 320f, fontScale = 1f, compact = true)) // 75.5 dp tiles
        assertTrue(BenefitGrid.usesTwoByTwo(width = 300f, fontScale = 1f, compact = true)) // 70.5 dp tiles
        assertTrue(BenefitGrid.usesTwoByTwo(width = 360f, fontScale = 1.1f, compact = true)) // large text
    }
}

/** Rendered at Android screen sizes with simulated system bars. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaywallFitLayoutTests {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val cta = "START 3-DAY FREE TRIAL"

    @Before fun demo() {
        LaunchArguments.set(mapOf("AWDemo" to "YES", "AWPaywallOpening" to "NO"))
        PaywallOpeningRule.shownThisLaunch = false
    }

    @After fun reset() {
        LaunchArguments.set(emptyMap())
        PaywallOpeningRule.shownThisLaunch = false
        RuntimeEnvironment.setFontScale(1f)
    }

    /** Renders [content] with the given status / navigation bar heights (dp) applied as window insets. */
    private fun show(statusDp: Int, navDp: Int, fontScale: Float = 1f, content: @androidx.compose.runtime.Composable () -> Unit) {
        RuntimeEnvironment.setFontScale(fontScale)
        rule.setContent { CompositionLocalProvider(LocalReduceMotion provides true) { PleadTheme { content() } } }
        rule.runOnUiThread {
            val view = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val d = view.resources.displayMetrics.density
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (statusDp * d).roundToInt(), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (navDp * d).roundToInt()))
                .build()
            ViewCompat.dispatchApplyWindowInsets(view, insets)
        }
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
    }

    private fun SemanticsNodeInteraction.dpBounds(): Rect {
        val b = fetchSemanticsNode().boundsInRoot
        val d = rule.density.density
        return Rect(b.left / d, b.top / d, b.right / d, b.bottom / d)
    }

    private fun screenHeightDp(): Float {
        val view = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        return view.height / view.resources.displayMetrics.density
    }

    private fun button(title: String) = rule.onNode(hasContentDescription(title) and hasClickAction())

    /** The node's on-screen bounds, asserting none of it is clipped away (an off-screen node reports empty bounds). */
    private fun SemanticsNodeInteraction.wholeOnScreen(name: String): Rect {
        val node = fetchSemanticsNode()
        val visible = dpBounds()
        val laidOut = node.size.height / rule.density.density
        assertTrue("$name is laid out", laidOut > 0f)
        assertEquals("$name fully on screen (visible ${visible.height} of $laidOut dp)", laidOut, visible.height, 0.5f)
        return visible
    }

    /** The standard paywall: the whole CTA is above the pinned footer (and its fade), all three plans present. */
    private fun assertStandardFits(statusDp: Int, navDp: Int, fontScale: Float = 1f) {
        val model = PreviewData.model(store = PreviewData.unpaidStore())
        show(statusDp, navDp, fontScale) { PaywallGate(model) }
        val button = button(cta).wholeOnScreen(cta)
        val footer = rule.onNode(hasText("Restore Purchases") and hasClickAction()).dpBounds()
        assertTrue("CTA top ${button.top} on screen", button.top >= 0f)
        assertTrue(
            "CTA bottom ${button.bottom} must clear the footer (${footer.top}) and its ${PaywallCompact.footerFade} fade",
            button.bottom <= footer.top - 2f - PaywallCompact.footerFade.value + 0.5f,
        )
        assertTrue("footer above the navigation bar", footer.bottom <= screenHeightDp() - navDp + 0.5f)
        for (plan in listOf("Annual, best value", "Monthly", "Weekly")) {
            rule.onNode(hasContentDescription(plan, substring = true) and hasClickAction()).assertExists()
        }
    }

    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    @Test fun pixel7GestureNavigation() = assertStandardFits(statusDp = 24, navDp = 24)

    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    @Test fun pixel7ThreeButtonNavigation() = assertStandardFits(statusDp = 24, navDp = 48)

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun phone360x760ThreeButtonNavigation() = assertStandardFits(statusDp = 24, navDp = 48)

    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    @Test fun phone360x640ThreeButtonNavigation() = assertStandardFits(statusDp = 24, navDp = 48)

    /** The user's phone (moto g60s): 432 × 984 dp, 3-button navigation, font size 130%. */
    @Config(qualifiers = "w432dp-h984dp-xxhdpi")
    @Test fun userPhoneAtLargerFontSize() = assertStandardFits(statusDp = 30, navDp = 48, fontScale = 1.3f)

    /** Larger text on a small phone may scroll, but the CTA is reachable and then sits fully above the footer. */
    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun hugeTextScrollsToAWholeButton() {
        val model = PreviewData.model(store = PreviewData.unpaidStore())
        show(statusDp = 24, navDp = 48, fontScale = 2f) { PaywallGate(model) }
        button(cta).performScrollTo()
        rule.waitForIdle()
        val button = button(cta).wholeOnScreen(cta)
        val footer = rule.onNode(hasText("Restore Purchases", substring = true) and hasClickAction()).dpBounds()
        assertTrue("CTA bottom ${button.bottom} clears the footer ${footer.top}", button.bottom <= footer.top + 0.5f)
        assertTrue(button.top >= 0f)
    }

    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    @Test fun exitOfferButtonAboveTheNavigationBar() {
        LaunchArguments.set(mapOf("AWDemo" to "YES", "AWPaywallOpening" to "NO", "AWSheet" to "exitOffer"))
        val model = PreviewData.model(store = PreviewData.unpaidStore())
        show(statusDp = 24, navDp = 48) { PaywallGate(model) }
        val button = button("CLAIM 50% OFF").wholeOnScreen("CLAIM 50% OFF")
        assertTrue("CLAIM bottom ${button.bottom}", button.bottom <= screenHeightDp() - 48f + 0.5f)
        assertTrue(button.top >= 0f)
    }

    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    @Test fun partnerPaidContinueAboveTheNavigationBar() {
        val model = PreviewData.partnerPaidModel()
        show(statusDp = 24, navDp = 48) { PaywallGate(model) }
        val button = button("CONTINUE").wholeOnScreen("CONTINUE")
        assertTrue("CONTINUE bottom ${button.bottom}", button.bottom <= screenHeightDp() - 48f + 0.5f)
    }

    /** One-line text shrinks to fit instead of clipping ("BEST VALU"), and stops at its minimum. */
    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun oneLineTextShrinksToFit() {
        lateinit var measurer: androidx.compose.ui.text.TextMeasurer
        rule.setContent { measurer = androidx.compose.ui.text.rememberTextMeasurer() }
        rule.waitForIdle()
        val style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        val full = measurer.measure("BEST VALUE", style, maxLines = 1, softWrap = false).size.width
        assertEquals(20.sp, ScaledText.oneLineFontSize(measurer, "BEST VALUE", style, Constraints.Infinity, 0.6f))
        assertEquals(20.sp, ScaledText.oneLineFontSize(measurer, "BEST VALUE", style, full, 0.6f))
        val shrunk = ScaledText.oneLineFontSize(measurer, "BEST VALUE", style, (full * 0.8f).toInt(), 0.6f)
        assertTrue(shrunk.value < 20f && shrunk.value >= 12f)
        assertTrue(measurer.measure("BEST VALUE", style.copy(fontSize = shrunk), maxLines = 1, softWrap = false).size.width <= full * 0.8f)
        assertEquals(12f, ScaledText.oneLineFontSize(measurer, "BEST VALUE", style, full / 4, 0.6f).value, 0.01f)
        assertFalse(PaywallCTA.fitsOneLine(measurer, "START 3-DAY FREE TRIAL", style, full / 2))
    }
}
