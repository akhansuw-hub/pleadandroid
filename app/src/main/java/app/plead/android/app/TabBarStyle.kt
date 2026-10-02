// The tab bar's colours per tab (MainTabScreen.PleadTabBar). iOS configures one paper-white bar
// (AppDelegate.styleNavigationBars), but the Court tab switches the window to the dark scheme (ArgueWinApp
// `preferredColorScheme(model.wantsLightStatusBar ? .dark : .light)`), so on iOS 26 the Liquid Glass bar renders dark
// over the courtroom with light items and a lighter burgundy for the selected tab. The court values are sampled from
// the iOS capture (docs/screenshots/v2/court-clean-1-17pro.png).
//
// The selected-tab pill (iOS 26 Liquid Glass selection, drawn by TabBarPill.kt) was measured on the 17 Pro captures
// (3x): a flat capsule 52 x 98 pt on the 62 pt floating bar, 4-5 pt in from the bar's edges, the selected icon and
// label centred on it. Light (home.png): bar #FFFBF3, pill #EDE9DF, no rim or shadow = cocoa at 8 % over the bar.
// Dark (court-clean-1-17pro.png): bar #401B17, pill #643B37 = #F4BBB7 at 20 % over the bar, with a faint lighter rim
// on the trailing edge (+12 levels, ~8 % blush).
package app.plead.android.app

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.PleadColor

@Immutable
data class TabBarStyle(
    val background: Color,
    /** The hairline on top of the bar (UITabBarAppearance `shadowColor`; the dark glass bar shows none). */
    val separator: Color,
    val unselected: Color,
    val selected: Color,
    /** The selected-tab pill's fill, translucent over [background]. */
    val pillFill: Color = Color.Transparent,
    /** The pill's glass rim, strongest on the trailing edge (transparent: no rim, as on the light iOS bar). */
    val pillHighlight: Color = Color.Transparent,
) {
    companion object {
        /** `styleNavigationBars`: paper white at 96 %, separator hairline, cocoa at 72 %, burgundy selected. */
        val light = TabBarStyle(
            background = PleadColor.paperWhite.copy(alpha = 0.96f),
            separator = PleadColor.separator,
            unselected = PleadColor.cocoa.copy(alpha = 0.72f),
            selected = PleadColor.burgundy,
            pillFill = PleadColor.cocoa.copy(alpha = 0.08f),
        )

        /** The bar as iOS renders it over the Court (dark scheme): deep mahogany glass, blush items. */
        val court = TabBarStyle(
            background = Color(0xFF401B17),
            separator = Color(0xFF401B17),
            unselected = Color(0xFFFFECE8),
            selected = Color(0xFFBC4A46),
            pillFill = Color(0xFFF4BBB7).copy(alpha = 0.20f),
            pillHighlight = Color(0xFFFFECE8).copy(alpha = 0.08f),
        )

        fun forTab(tab: AppTab): TabBarStyle = if (tab == AppTab.court) court else light
    }
}

/**
 * The selected-tab pill's shape. iOS draws it 4 pt in from the floating bar's edges; on the docked bar each pill sits
 * [horizontalInset] inside its tab's slot (so ~95 dp wide on a 411 dp phone, iOS 98 pt) and [verticalInset] inside the
 * 49 dp item row, whose height is fixed (`CourtTabLayout.tabBarHeight` measures the court against it). A capsule: the corner
 * radius is half the pill's height.
 */
object TabBarPillMetrics {
    val horizontalInset: Dp = 4.dp
    val verticalInset: Dp = 2.dp
    /** The rim's stroke width (only drawn where [TabBarStyle.pillHighlight] is visible). */
    val highlightWidth: Dp = 1.dp
    /** The slide: SwiftUI `.spring(duration:bounce:)` close to the system tab selection's. */
    const val springDuration: Float = 0.4f
    const val springBounce: Float = 0.15f
}
