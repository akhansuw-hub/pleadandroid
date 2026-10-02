// `.lineLimit(1).minimumScaleFactor(…)` emulation shared by the Glance widgets and the onboarding widget illustration.
package app.plead.android.widgets

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WidgetTextFitTests {
    @Test fun scaledSizeKeepsFittingText() {
        assertEquals(15f, WidgetTextFit.scaledSize(15f, measuredPx = 300f, availablePx = 300f, minScale = 0.75f))
        assertEquals(15f, WidgetTextFit.scaledSize(15f, measuredPx = 100f, availablePx = 300f, minScale = 0.75f))
    }

    @Test fun scaledSizeShrinksInProportionToAQuarterSp() {
        // 300 / 330 = 0.909 → 13.636 sp → 13.5 sp.
        assertEquals(13.5f, WidgetTextFit.scaledSize(15f, measuredPx = 330f, availablePx = 300f, minScale = 0.75f))
    }

    @Test fun scaledSizeStopsAtTheMinimumScale() {
        assertEquals(11.25f, WidgetTextFit.scaledSize(15f, measuredPx = 900f, availablePx = 300f, minScale = 0.75f))
    }

    @Test fun onboardingTitleFitsTheSmallWidget() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val density = Density(2.625f)
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, androidx.compose.ui.unit.LayoutDirection.Ltr)
        val style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 15.sp)
        val title = "The Dinner Incident"
        val natural = measurer.measure(title, style, softWrap = false, maxLines = 1).size.width
        // Room to spare: the style is unchanged.
        assertEquals(style, WidgetTextFit.fittedStyle(measurer, title, style, natural + 10, 0.75f))
        // 10% short: shrunk until the title fits on one line, not below 0.75.
        val narrow = (natural * 0.9f).toInt()
        val fitted = WidgetTextFit.fittedStyle(measurer, title, style, narrow, 0.75f)
        assertTrue(fitted.fontSize.value < 15f && fitted.fontSize.value >= 11.25f)
        val width = measurer.measure(title, fitted, softWrap = false, maxLines = 1).size.width
        assertTrue("$width > $narrow", width <= narrow)
    }
}
