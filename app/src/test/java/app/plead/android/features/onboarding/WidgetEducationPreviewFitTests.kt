// The onboarding Widgets step's illustration (WidgetEducationPreview) fits the width it is offered: at its iOS size
// (320 × 318) where there is room, scaled down with its proportions on narrower phones (the Lock Screen mock and the
// overlapping Home Screen widget used to run past the right edge under ~368 dp).
package app.plead.android.features.onboarding

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.PleadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class WidgetEducationPreviewFitTests {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun heroScaleKeepsTheIOSSizeWhereItFits() {
        assertEquals(1f, WidgetEducationPreview.heroScale(960, 960), 0f)
        assertEquals(1f, WidgetEducationPreview.heroScale(1089, 960), 0f)   // Pixel 7: 363 dp of content
        assertEquals(312f / 320f, WidgetEducationPreview.heroScale(936, 960), 0.0001f)   // 360 dp phone
        assertEquals(272f / 320f, WidgetEducationPreview.heroScale(816, 960), 0.0001f)   // 320 dp phone
    }

    @Test fun heroScaleIsTheSmallerOfTheWidthAndHeightFits() {
        // No height limit: the width fit.
        assertEquals(1f, WidgetEducationPreview.heroScale(1089, 960, null, 954), 0f)
        // Pixel 7: room for the whole hero, unchanged.
        assertEquals(1f, WidgetEducationPreview.heroScale(1089, 960, 1200, 954), 0f)
        // Short screen: the height fit wins (uniform, so the proportions stay).
        assertEquals(477f / 954f, WidgetEducationPreview.heroScale(936, 960, 477, 954), 0.0001f)
        // Narrow but tall enough: the width fit wins.
        assertEquals(936f / 960f, WidgetEducationPreview.heroScale(936, 960, 2000, 954), 0.0001f)
        // Available height from the hero's top to the CTA's, less the margin; unknown until both are measured.
        assertEquals(null, WidgetEducationPreview.availableHeight(null, 1500f, 36f))
        assertEquals(564, WidgetEducationPreview.availableHeight(900f, 1500f, 36f))
        assertEquals(1, WidgetEducationPreview.availableHeight(1500f, 1400f, 36f))
    }

    /** Short screens: the whole composition fits the height it is given, at the iOS proportions. */
    @Test fun illustrationScalesToTheOfferedHeight() {
        var maxHeight by mutableFloatStateOf(318f)
        rule.setContent {
            PleadTheme {
                val px = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.dp.roundToPx() }
                Box(Modifier.width(312.dp)) { WidgetEducationPreview(Modifier.testTag("hero"), maxHeightPx = px) }
            }
        }
        for (h in listOf(318f, 250f, 200f, 150f)) {
            maxHeight = h
            rule.waitForIdle()
            val bounds = rule.onNodeWithTag("hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val scale = minOf(1f, 312f / 320f, h / 318f)
            val heightDp = with(rule.density) { bounds.height.toDp().value }
            assertEquals("hero height with $h dp available", 318f * scale, heightDp, 1f)
            assertTrue("hero taller than $h dp", heightDp <= h + 0.5f)
        }
    }

    /** Content widths of 320, 343, 360 dp and Pixel 7 phones (screen − 2 × 24 dp gutter), and the iOS 16e (342). */
    @Test fun illustrationScalesToTheOfferedWidth() {
        var width by mutableFloatStateOf(272f)
        rule.setContent {
            PleadTheme {
                Box(Modifier.width(width.dp)) { WidgetEducationPreview(Modifier.testTag("hero")) }
            }
        }
        for (w in listOf(272f, 295f, 312f, 342f, 363f)) {
            width = w
            rule.waitForIdle()
            val bounds = rule.onNodeWithTag("hero", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val scale = minOf(1f, w / 320f)
            val heightDp = with(rule.density) { bounds.height.toDp().value }
            val widthDp = with(rule.density) { bounds.width.toDp().value }
            assertEquals("hero height at $w dp", 318f * scale, heightDp, 1f)
            assertEquals("hero width at $w dp", w, widthDp, 0.5f)
        }
    }
}
