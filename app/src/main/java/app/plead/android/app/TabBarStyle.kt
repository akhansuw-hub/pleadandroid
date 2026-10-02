// The tab bar's colours per tab (MainTabScreen.PleadTabBar). iOS configures one paper-white bar
// (AppDelegate.styleNavigationBars), but the Court tab switches the window to the dark scheme (ArgueWinApp
// `preferredColorScheme(model.wantsLightStatusBar ? .dark : .light)`), so on iOS 26 the Liquid Glass bar renders dark
// over the courtroom with light items and a lighter burgundy for the selected tab. The court values are sampled from
// the iOS capture (docs/screenshots/v2/court-clean-1-17pro.png).
package app.plead.android.app

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import app.plead.android.designsystem.PleadColor

@Immutable
data class TabBarStyle(
    val background: Color,
    /** The hairline on top of the bar (UITabBarAppearance `shadowColor`; the dark glass bar shows none). */
    val separator: Color,
    val unselected: Color,
    val selected: Color,
) {
    companion object {
        /** `styleNavigationBars`: paper white at 96 %, separator hairline, cocoa at 72 %, burgundy selected. */
        val light = TabBarStyle(
            background = PleadColor.paperWhite.copy(alpha = 0.96f),
            separator = PleadColor.separator,
            unselected = PleadColor.cocoa.copy(alpha = 0.72f),
            selected = PleadColor.burgundy,
        )

        /** The bar as iOS renders it over the Court (dark scheme): deep mahogany glass, blush items. */
        val court = TabBarStyle(
            background = Color(0xFF401B17),
            separator = Color(0xFF401B17),
            unselected = Color(0xFFFFECE8),
            selected = Color(0xFFBC4A46),
        )

        fun forTab(tab: AppTab): TabBarStyle = if (tab == AppTab.court) court else light
    }
}
