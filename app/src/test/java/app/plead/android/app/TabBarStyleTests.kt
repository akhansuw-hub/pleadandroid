package app.plead.android.app

import androidx.compose.ui.graphics.Color
import app.plead.android.designsystem.PleadColor
import org.junit.Assert.assertEquals
import org.junit.Test

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

    private fun Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
