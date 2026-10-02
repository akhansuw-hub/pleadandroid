package app.plead.android.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.PleadColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class TabBarStyleTests {
    @Test fun courtIsDarkEveryOtherTabKeepsThePaperBar() {
        assertEquals(TabBarStyle.court, TabBarStyle.forTab(AppTab.court))
        for (tab in listOf(AppTab.home, AppTab.cases, AppTab.us)) assertEquals(TabBarStyle.light, TabBarStyle.forTab(tab))
    }

    /** `AppDelegate.styleNavigationBars`: paper white 96 %, separator, cocoa 72 %, burgundy selected. */
    @Test fun lightBarMatchesTheIOSAppearance() {
        val light = TabBarStyle.light
        assertEquals(PleadColor.paperWhite.copy(alpha = 0.96f), light.background)
        assertEquals(PleadColor.separator, light.separator)
        assertEquals(PleadColor.cocoa.copy(alpha = 0.72f), light.unselected)
        assertEquals(PleadColor.burgundy, light.selected)
    }

    /** The iOS 26 bar over the Court (sampled from court-clean-1-17pro.png): dark, light items, no hairline. */
    @Test fun courtBarIsDarkWithLightItems() {
        val court = TabBarStyle.court
        assertEquals(Color(0xFF401B17), court.background)
        assertEquals(court.background, court.separator)
        assertEquals(Color(0xFFFFECE8), court.unselected)
        assertEquals(Color(0xFFBC4A46), court.selected)
        assert(court.background.luminance() < 0.2f && court.unselected.luminance() > 0.8f)
    }

    // MARK: Selected-tab pill

    /** Composited over the iOS bar, the light pill lands on the sampled #EDE9DF (home.png), flat with no rim. */
    @Test fun lightPillIsCocoaTintOverTheBar() {
        val light = TabBarStyle.light
        assertEquals(PleadColor.cocoa.copy(alpha = 0.08f), light.pillFill)
        assertEquals(Color.Transparent, light.pillHighlight)
        assertClose(Color(0xFFEDE9DF), light.pillFill.compositeOver(Color(0xFFFFFBF3)))
        // Visible on the Android bar too: darker than the bar, still far lighter than the items.
        val onBar = light.pillFill.compositeOver(light.background.compositeOver(PleadColor.background))
        assertTrue(onBar.luminance() < light.background.luminance() && onBar.luminance() > 0.8f)
    }

    /** Over the Court bar the pill lands on the sampled #643B37 (court-clean-1-17pro.png), with a faint blush rim. */
    @Test fun courtPillIsLighterGlassOverTheDarkBar() {
        val court = TabBarStyle.court
        assertClose(Color(0xFF643B37), court.pillFill.compositeOver(court.background))
        assertTrue(court.pillFill.alpha in 0.1f..0.3f)
        assertTrue(court.pillHighlight.alpha in 0.01f..0.15f)
        // The selected burgundy still stands out on the pill.
        val pill = court.pillFill.compositeOver(court.background)
        assertTrue(abs(court.selected.luminance() - pill.luminance()) > 0.05f)
    }

    @Test fun pillMetricsMatchTheIOSCapsule() {
        assertEquals(4.dp, TabBarPillMetrics.horizontalInset)
        assertEquals(2.dp, TabBarPillMetrics.verticalInset)
        assertTrue(TabBarPillMetrics.springBounce in 0f..0.2f)
        assertTrue(TabBarPillMetrics.springDuration in 0.3f..0.6f)
    }

    /** Pixel 7 (411 dp at 2.625) and a 360 dp phone at 3x: a pill per tab, in its slot, same size, inside the row. */
    @Test fun pillBoundsPerTabStayInsideTheBar() {
        for ((widthDp, density) in listOf(411.43f to 2.625f, 360f to 3f)) {
            val width = widthDp * density
            val height = 49f * density
            val hInset = 4f * density
            val vInset = 2f * density
            val rects = (0 until 4).map { TabBarPillGeometry.bounds(it.toFloat(), 4, width, height, hInset, vInset) }
            val slot = width / 4
            rects.forEachIndexed { i, r ->
                assertEquals(slot - 2 * hInset, r.width, 0.01f)
                assertEquals(height - 2 * vInset, r.height, 0.01f)
                assertEquals(i * slot + hInset, r.left, 0.01f)
                assertEquals(vInset, r.top, 0.01f)
                assertEquals("centred on its tab", i * slot + slot / 2, r.center.x, 0.01f)
                assertTrue(r.left >= 0f && r.right <= width && r.top >= 0f && r.bottom <= height)
                assertEquals(r.height / 2, TabBarPillGeometry.cornerRadius(r), 0.01f)
            }
            // iOS: 98 x 52 pt; the docked Android pill is close on a Pixel 7 (~95 x 45 dp).
            if (widthDp > 400f) assertEquals(94.9f, rects[0].width / density, 0.5f)
            for (i in 1 until 4) assertTrue("no overlap", rects[i].left >= rects[i - 1].right)
        }
    }

    /** Mid-slide the pill is between the two tabs; a spring overshoot past an end tab is held at that tab. */
    @Test fun pillSlidesBetweenTabsAndNeverLeavesTheBar() {
        val width = 1080f
        val height = 128f
        val mid = TabBarPillGeometry.bounds(1.5f, 4, width, height, 10f, 5f)
        val a = TabBarPillGeometry.bounds(1f, 4, width, height, 10f, 5f)
        val b = TabBarPillGeometry.bounds(2f, 4, width, height, 10f, 5f)
        assertEquals((a.left + b.left) / 2, mid.left, 0.01f)
        assertEquals(a.width, mid.width, 0.01f)
        assertEquals(TabBarPillGeometry.bounds(3f, 4, width, height, 10f, 5f), TabBarPillGeometry.bounds(3.2f, 4, width, height, 10f, 5f))
        assertEquals(TabBarPillGeometry.bounds(0f, 4, width, height, 10f, 5f), TabBarPillGeometry.bounds(-0.2f, 4, width, height, 10f, 5f))
        // Degenerate sizes do not produce negative rects.
        val tiny = TabBarPillGeometry.bounds(0f, 4, 20f, 3f, 10f, 5f)
        assertTrue(tiny.width >= 0f && tiny.height >= 0f)
    }

    private fun assertClose(expected: Color, actual: Color, tolerance: Float = 3f / 255f) {
        assertEquals("red", expected.red, actual.red, tolerance)
        assertEquals("green", expected.green, actual.green, tolerance)
        assertEquals("blue", expected.blue, actual.blue, tolerance)
    }

    private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
