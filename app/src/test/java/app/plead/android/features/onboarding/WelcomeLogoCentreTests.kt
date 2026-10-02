// Real-device fix 2026-10-02: the Plead logo on the first onboarding page sat at the left edge (the layered shell
// wrapped each child in a full-width, start-aligned box). It is centred on the screen, as on iOS.
package app.plead.android.features.onboarding

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.plead.android.designsystem.PleadTheme
import app.plead.android.services.PreviewData
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WelcomeLogoCentreTests {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @After fun reset() = RuntimeEnvironment.setFontScale(1f)

    private fun assertLogoCentred(navDp: Int, fontScale: Float = 1f) {
        RuntimeEnvironment.setFontScale(fontScale)
        val app = PreviewData.model()
        rule.setContent { PleadTheme { OnboardingWelcomeView(app) } }
        rule.runOnUiThread {
            val view = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
            val d = view.resources.displayMetrics.density
            ViewCompat.dispatchApplyWindowInsets(
                view,
                WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, (24 * d).roundToInt(), 0, 0))
                    .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, (navDp * d).roundToInt()))
                    .build(),
            )
        }
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        val d = rule.density.density
        val logo = rule.onNodeWithTag(OnboardingWelcomeView.logoTag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val root = rule.onNodeWithTag(OnboardingWelcomeView.logoTag, useUnmergedTree = true).fetchSemanticsNode().layoutInfo
        val screenWidth = rule.activity.findViewById<ViewGroup>(android.R.id.content).width / d
        assertTrue("logo laid out", logo.width > 0f && root.isPlaced)
        assertEquals("logo centre x (dp)", screenWidth / 2f, logo.center.x / d, 1f)
        assertTrue("logo near the top", logo.top / d < 200f)
    }

    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    @Test fun pixel7Gesture() = assertLogoCentred(navDp = 24)

    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    @Test fun pixel7ThreeButton() = assertLogoCentred(navDp = 48)

    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    @Test fun phone360x640() = assertLogoCentred(navDp = 48)

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun phone360x760() = assertLogoCentred(navDp = 48)

    @Config(qualifiers = "w432dp-h984dp-xxhdpi")
    @Test fun usersPhoneAtLargerText() = assertLogoCentred(navDp = 48, fontScale = 1.3f)
}
