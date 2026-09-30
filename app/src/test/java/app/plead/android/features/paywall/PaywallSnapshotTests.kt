// Renders the paywall screens off-device (Robolectric native graphics) to
// `app/build/outputs/snapshots/paywall/*.png` (iPhone 17 Pro-sized window), so the port can be compared with
// docs/screenshots/v2 without the emulator. Asserts that each composes and renders; review the PNGs by eye.
package app.plead.android.features.paywall

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import app.plead.android.app.LaunchArguments
import app.plead.android.designsystem.LocalReduceMotion
import app.plead.android.designsystem.PleadTheme
import app.plead.android.services.PreviewData
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w402dp-h874dp-xxhdpi")
class PaywallSnapshotTests {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun demo() {
        // Demo prices (src/debug PreviewPrices), no bloom (the settled paywall assembles).
        LaunchArguments.set(mapOf("AWDemo" to "YES", "AWPaywallOpening" to "NO"))
        PaywallOpeningRule.shownThisLaunch = false
    }

    @After fun reset() {
        LaunchArguments.set(emptyMap())
        PaywallOpeningRule.shownThisLaunch = false
    }

    private fun snap(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            CompositionLocalProvider(LocalReduceMotion provides false) { PleadTheme { content() } }
        }
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        val root = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        val bitmap = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("build/outputs/snapshots/paywall").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test fun standard() {
        val model = PreviewData.model(store = PreviewData.unpaidStore())
        snap("paywall-standard") { PaywallGate(model) }
        rule.onNode(hasText(PaywallCopy.headline), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("START 3-DAY FREE TRIAL"), useUnmergedTree = true).assertExists()
        rule.onNode(hasText(PaywallCopy.coupleAccess), useUnmergedTree = true).assertExists()
    }

    @Test fun exitOffer() {
        LaunchArguments.set(mapOf("AWDemo" to "YES", "AWPaywallOpening" to "NO", "AWSheet" to "exitOffer"))
        val model = PreviewData.model(store = PreviewData.unpaidStore())
        snap("paywall-exit-offer") { PaywallGate(model) }
        rule.onNode(hasText("CLAIM 50% OFF"), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("No thanks, not now"), useUnmergedTree = true).assertExists()
    }

    @Test fun partnerPaid() {
        val model = PreviewData.partnerPaidModel()
        snap("paywall-partner-paid") { PaywallGate(model) }
        rule.onNode(hasText(PaywallCopy.partnerPaid), useUnmergedTree = true).assertExists()
    }

    @Test fun loadingPrices() {
        LaunchArguments.set(mapOf("AWDemo" to "YES", "AWPaywallOpening" to "NO", "AWPricesLoading" to "YES"))
        val model = PreviewData.model(store = PreviewData.unpaidStore())
        snap("paywall-loading") { PaywallGate(model) }
        rule.onNode(hasText("LOADING PRICES"), useUnmergedTree = true).assertExists()
    }

    @Test fun noTrialMonthlySelected() {
        LaunchArguments.set(mapOf("AWDemo" to "YES", "AWPaywallOpening" to "NO", "AWTrial" to "no", "AWPlan" to "monthly"))
        val model = PreviewData.model(store = PreviewData.unpaidStore())
        snap("paywall-monthly") { PaywallGate(model) }
        rule.onNode(hasText("CONTINUE — £14.99/MONTH"), useUnmergedTree = true).assertExists()
    }
}
