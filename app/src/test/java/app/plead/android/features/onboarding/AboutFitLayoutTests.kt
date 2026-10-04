// Amendment bd on short Android screens (the PaywallFit approach): every "About you two" screen keeps its whole CTA
// above the navigation bar, below the content's top; the hero gives height back first (never below
// `AboutMotion.heroMin`), and only then does the content scroll. Pixel 7 (gesture / 3-button), 360 × 640 and 360 × 760
// with 3-button navigation, and the user's phone size at 130 % text.
package app.plead.android.features.onboarding

import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.plead.android.app.LaunchArguments
import app.plead.android.designsystem.LocalReduceMotion
import app.plead.android.designsystem.PleadTheme
import app.plead.android.features.onboarding.about.AboutHeroLayout
import app.plead.android.features.onboarding.about.AboutMotion
import app.plead.android.services.PreviewData
import kotlin.math.roundToInt
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutFitLayoutTests {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun demo() = LaunchArguments.set(mapOf("AWDemo" to "YES"))

    @After fun reset() {
        LaunchArguments.set(emptyMap())
        RuntimeEnvironment.setFontScale(1f)
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

    private fun screenWidthDp(): Float {
        val view = rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
        return view.width / view.resources.displayMetrics.density
    }

    /** Walks the six screens in one composition (the container's own page switch), checking each. */
    private fun assertAllFit(navDp: Int, fontScale: Float = 1f, expectIdealHero: Boolean = false) {
        RuntimeEnvironment.setFontScale(fontScale)
        val app = PreviewData.model()
        val model = app.onboardingModel
        model.go(OnboardingStep.aboutFrequency)
        rule.setContent { CompositionLocalProvider(LocalReduceMotion provides true) { PleadTheme { OnboardingContainer(app) } } }
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
        for (step in OnboardingStep.aboutYouTwoSteps) {
            rule.runOnUiThread { model.go(step) }
            rule.mainClock.advanceTimeBy(3_000)
            rule.waitForIdle()
            val ctaTag = if (step == OnboardingStep.oath) "onboarding.oath.hold" else "onboarding.about.continue"
            val cta = rule.onNodeWithTag(ctaTag, useUnmergedTree = true)
            val node = cta.fetchSemanticsNode()
            val laidOut = node.size.height / rule.density.density
            val b = cta.dpBounds()
            assertEquals("$step CTA fully on screen", laidOut, b.height, 0.5f)
            assertTrue("$step CTA bottom ${b.bottom} above the navigation bar (${screenHeightDp() - navDp})", b.bottom <= screenHeightDp() - navDp + 0.5f)
            val hero = rule.onNodeWithTag("onboarding.about.heroFrame", useUnmergedTree = true).dpBounds()
            assertTrue("$step hero ${hero.height} ≥ heroMin", hero.height >= AboutMotion.heroMin.value - 0.5f)
            assertTrue("$step hero ${hero.height} ≤ the iOS height", hero.height <= AboutHeroLayout.height(screenWidthDp()) + 0.5f)
            if (expectIdealHero) assertEquals("$step hero at the iOS height", AboutHeroLayout.height(screenWidthDp()), hero.height, 1f)
            assertTrue("$step hero above the CTA", hero.bottom <= b.top)
            val titleTag = if (step == OnboardingStep.oath) "onboarding.oath.title" else "onboarding.about.title"
            val title = rule.onNodeWithTag(titleTag, useUnmergedTree = true).dpBounds()
            assertTrue("$step title starts under the hero", title.top >= hero.bottom - 0.5f)
            assertTrue("$step title starts above the CTA", title.top < b.top)
        }
    }

    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    @Test fun pixel7Gesture() = assertAllFit(navDp = 24, expectIdealHero = true)

    @Config(qualifiers = "w412dp-h915dp-xxhdpi")
    @Test fun pixel7ThreeButton() = assertAllFit(navDp = 48)

    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    @Test fun phone360x640ThreeButton() = assertAllFit(navDp = 48)

    @Config(qualifiers = "w360dp-h640dp-xxhdpi")
    @Test fun phone360x640ThreeButtonLargerText() = assertAllFit(navDp = 48, fontScale = 1.3f)

    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    @Test fun phone360x760ThreeButtonLargerText() = assertAllFit(navDp = 48, fontScale = 1.3f)

    @Config(qualifiers = "w432dp-h984dp-xxhdpi")
    @Test fun usersPhoneAtLargerText() = assertAllFit(navDp = 48, fontScale = 1.3f)
}
