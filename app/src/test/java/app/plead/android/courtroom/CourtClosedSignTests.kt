// Real-device fix 2026-10-02: the Court tab's CLOSED sign wrapped as "CLOSE / D" on a 360 dp phone. The sign is a
// fixed share of the screen, so its word must stay on one line and fit inside the sign at every width and font size.
package app.plead.android.courtroom

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.PleadTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w412dp-h915dp-xxhdpi")
class CourtClosedSignTests {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Case(val width: Float, val height: Float, val fontScale: Float)

    private var case by mutableStateOf(Case(412f, 915f, 1f))
    private var result: TextLayoutResult? = null
    private var started = false

    /** Lays the sign out for a courtroom [width] × [height] dp at [fontScale] (one composition, re-laid per case). */
    private fun layout(width: Float, height: Float, fontScale: Float): Pair<TextLayoutResult, androidx.compose.ui.geometry.Rect> {
        result = null
        case = Case(width, height, fontScale)
        if (!started) {
            started = true
            rule.setContent {
                val c = case
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, c.fontScale)) {
                    PleadTheme {
                        val zones = CourtroomZones(Size(c.width, c.height))
                        val sign = CourtroomEmptyState.signRect(zones.rect(CourtroomZones.easel))
                        Box(Modifier.size(c.width.dp, c.height.dp)) {
                            key(c) { CourtroomClosedSign(sign, onTextLayout = { result = it }) }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        val sign = CourtroomEmptyState.signRect(CourtroomZones(Size(width, height)).rect(CourtroomZones.easel))
        return requireNotNull(result) to sign
    }

    private fun assertOneLineInside(width: Float, height: Float, fontScale: Float) {
        val (text, sign) = layout(width, height, fontScale)
        val d = rule.density.density
        val label = "${width}x$height @ $fontScale"
        assertEquals("$label: one line", 1, text.lineCount)
        assertFalse("$label: nothing clipped", text.hasVisualOverflow)
        assertTrue(
            "$label: text ${text.size.width / d} dp inside the ${sign.width} dp sign's gold rule",
            text.size.width / d <= sign.width - 2 * CourtroomEmptyState.signInset + 0.5f,
        )
        assertEquals(CourtroomEmptyState.signText, text.layoutInput.text.text)
    }

    @Test fun narrowPhones() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            assertOneLineInside(360f, 640f, scale)
            assertOneLineInside(360f, 760f, scale)
            assertOneLineInside(320f, 570f, scale)
        }
    }

    @Test fun pixel7AndTheUsersPhone() {
        for (scale in listOf(1f, 1.3f, 2f)) {
            assertOneLineInside(412f, 915f, scale)
            assertOneLineInside(432f, 984f, scale)
        }
    }

    /** Where it fits the word keeps the iOS size (displayM, 21). */
    @Test fun keepsTheIosSizeWhereItFits() {
        val (text, _) = layout(432f, 984f, 1f)
        assertEquals(21f, text.layoutInput.style.fontSize.value, 0.01f)
    }

    /** The sign is art of a fixed size: a larger system font doesn't make the word bigger than what fits. */
    @Test fun doesNotGrowWithTheSystemFontSize() {
        val (normal, _) = layout(360f, 760f, 1f)
        val normalSize = normal.layoutInput.style.fontSize.value * 1f
        for (scale in listOf(1.3f, 2f)) {
            val (big, _) = layout(360f, 760f, scale)
            assertTrue("effective size at $scale", big.layoutInput.style.fontSize.value * scale <= normalSize + 0.5f)
        }
    }

    /** The info card keeps the iOS place unless it would cover the sign; then it moves down only as far as fits. */
    @Test fun infoCardClearsTheSignWhenThereIsRoom() {
        // Room: iOS centre 470, card 150 tall → top 395 would cover a sign ending at 420; it moves to 428.
        assertEquals(428f, CourtroomEmptyState.cardTop(iosCentre = 470f, height = 150f, signBottom = 420f, bottomLimit = 640f), 0.01f)
        // Not covering: the iOS place.
        assertEquals(395f, CourtroomEmptyState.cardTop(iosCentre = 470f, height = 150f, signBottom = 380f, bottomLimit = 640f), 0.01f)
        // Little room: as low as the tab bar allows (482 - 8 - 150 = 324 < iOS 395): never moves up.
        assertEquals(395f, CourtroomEmptyState.cardTop(iosCentre = 470f, height = 150f, signBottom = 420f, bottomLimit = 482f), 0.01f)
        assertEquals(410f, CourtroomEmptyState.cardTop(iosCentre = 470f, height = 150f, signBottom = 420f, bottomLimit = 568f), 0.01f)
    }
}
