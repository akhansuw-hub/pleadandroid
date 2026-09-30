// Port of ArgueWin/Features/Paywall/PaywallPalette.swift.
package app.plead.android.features.paywall

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.Color
import kotlin.math.roundToInt

/**
 * Paywall-local palette (docs/paywall-brief/BRIEF.md §5). The rest of the app keeps Tokens.kt.
 * Balance target: ~60% cream / paper, 15% burgundy, 10% blush, 10% mahogany (hero art), 5% gold.
 */
object PaywallPalette {
    val courtBurgundy = Color(hex = 0x7C3042)   // brand, selected states, key borders
    val deepWine = Color(hex = 0x541F2C)        // headlines, strong text
    val mahogany = Color(hex = 0x704735)        // wood detail
    val darkCocoa = Color(hex = 0x3B2425)       // main text, icons
    val warmCream = Color(hex = 0xFFF6ED)       // screen background
    val paperWhite = Color(hex = 0xFFFDFC)      // cards
    val parchment = Color(hex = 0xF3E4D6)       // secondary panels, quiet borders
    val romanceBlush = Color(hex = 0xEAA0A4)    // couple / heart details
    val softRose = Color(hex = 0xC66C78)        // secondary accents
    val coral = Color(hex = 0xE85E68)           // "3 DAYS FREE", logo heart
    val courtGold = Color(hex = 0xCA9858)       // judicial accents
    val goldLight = Color(hex = 0xF3C76A)       // BEST VALUE pill / crown

    /** Annual (preferred) plan card fill. */
    val annualFill = Color(hex = 0xFFF1EB)

    /** Perk card fill: blush over cream. */
    val perkFill = Color(hex = 0xFCEBE4)

    /** Unselected plan card border: neutral, a shade deeper than parchment so the card edge reads on cream. */
    val planBorder = Color(hex = 0xE8D5C4)

    /** Legal / footer text. */
    val mutedCocoa: Color = Color(hex = 0x3B2425, opacity = 0.66f)
}

/** Shape rule for the paywall: plan cards and CTA 22, perk cards 16, pills are capsules, close is a circle. */
object PaywallRadius {
    val card: Dp = 22.dp
    val perk: Dp = 16.dp
}

/** Paywall proportions (amendment s): hero 28–34% of the screen, CTA 60–66 pt. */
object PaywallLayout {
    /**
     * Hero height as a fraction of the full screen height (the standard paywall and the exit offer share it, so
     * the hero holds still through the cross-dissolve).
     */
    const val heroFraction: Float = 0.28f
    const val ctaHeight: Float = 62f

    /** Amendment z: short viewports (iPhone 16e and smaller) give the hero 25% so the CTA stays above the fold. */
    const val compactHeroFraction: Float = 0.25f
    const val compactViewport: Float = 860f

    /** [viewport] and the result in points (dp). */
    fun heroHeight(viewport: Float): Float =
        (viewport * (if (viewport < compactViewport) compactHeroFraction else heroFraction)).roundToInt().toFloat()
}
