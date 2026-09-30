// Plead — design tokens, Revision 2 (romantic pixel-art courtroom: mahogany, burgundy, cream, gold).
// Port of ArgueWin/DesignSystem/Tokens.swift (`AWColor` → `PleadColor`, `AWRadius` → `PleadRadius`, …).
// Rule for agents: ADD tokens, do not edit these definitions (PORT.md §5, additive-only).
package app.plead.android.designsystem

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plead.android.models.Role

/** Swift `Color(hex:opacity:)`: 0xRRGGBB, sRGB. */
fun Color(hex: Int, opacity: Float = 1f): Color = Color(
    red = ((hex shr 16) and 0xFF) / 255f,
    green = ((hex shr 8) and 0xFF) / 255f,
    blue = (hex and 0xFF) / 255f,
    alpha = opacity,
)

object PleadColor {
    // Brand (spec v2 §10)
    val burgundy = Color(hex = 0x8A2C2A)    // brand, primary actions, selected nav, case status
    val mahogany = Color(hex = 0x5E251F)    // judge bubbles, deep courtroom accents, dark surfaces
    val cocoa = Color(hex = 0x2A1310)       // primary text, icons
    val walnut = Color(hex = 0x764534)      // secondary wood, defendant accents, dividers
    val blush = Color(hex = 0xEB9996)       // hearts, couple moments
    val terracotta = Color(hex = 0xCA7356)  // secondary emphasis, badges
    val gold = Color(hex = 0xC99558)        // scales, premium, verdict details (rare)
    val cream = Color(hex = 0xFFF7F0)       // main app background
    val parchment = Color(hex = 0xF3E4D6)   // case files, evidence cards
    val paperWhite = Color(hex = 0xFFFDFC)  // speech bubbles, elevated cards

    // Semantic aliases
    val background = cream
    val card = paperWhite
    val text = cocoa
    val subtleText = Color(hex = 0x7A5B52)
    val separator = Color(hex = 0xE8D6C8)
    val success = Color(hex = 0x5E8C61)
    val danger = burgundy

    /** Court tab atmosphere */
    val courtBackdrop = Color(hex = 0x3A1713)
    val courtCarpet = Color(hex = 0x6E2422)

    fun role(r: Role): Color = if (r == Role.plaintiff) burgundy else walnut

    @Suppress("UNUSED_PARAMETER")
    fun onRole(r: Role): Color = cream

    // Asset-catalog colorsets (Assets.xcassets; also generated into res/values/asset_colors.xml)
    /** `AccentColor.colorset`: the app tint. */
    val accentColor = Color(hex = 0x8A2C2A)
    /** `LaunchBackground.colorset`: launch screen / splash background. */
    val launchBackground = Color(hex = 0xFFF7F0)
}

object PleadRadius {
    val card: Dp = 20.dp
    val tile: Dp = 14.dp
    val bubble: Dp = 16.dp
    val button: Dp = 14.dp
    val chip: Dp = 999.dp
}

object PleadSpacing {
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Swift `AWFont` (legacy UI font helpers; new code uses `PleadType`). SF Rounded → the system sans. */
object PleadFont {
    /** Rounded geometric sans for UI. */
    fun ui(size: Float, weight: FontWeight = FontWeight.Normal): TextStyle =
        TextStyle(fontFamily = FontFamily.Default, fontWeight = weight, fontSize = size.sp)

    val hero = ui(32f, FontWeight.ExtraBold)
    val title = ui(24f, FontWeight.Bold)
    val headline = ui(17f, FontWeight.Bold)
    val body = ui(16f, FontWeight.Medium)
    val caption = ui(13f, FontWeight.SemiBold)

    /** Small-caps style legal labels (EXHIBIT A, PLAINTIFF, ALL RISE): use with 1.5 tracking and uppercased text. */
    val legalLabel = ui(12f, FontWeight.ExtraBold)

    /** Restrained serif for short rulings and exhibit labels. */
    fun serif(size: Float = 16f, italic: Boolean = true): TextStyle = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        fontSize = size.sp,
    )

    fun ruling(size: Float = 16f): TextStyle = serif(size)
}

/** Swift `AWMotion`: `.easeInOut(0.25)`, `.easeOut(0.3)`. */
object PleadMotion {
    /** SwiftUI `.easeInOut` / `.easeOut` / `.easeIn` curves (CSS ease-in-out etc.). */
    val easeInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
    val easeOut = CubicBezierEasing(0f, 0f, 0.58f, 1f)
    val easeIn = CubicBezierEasing(0.42f, 0f, 1f, 1f)

    fun <T> fade(): TweenSpec<T> = tween(durationMillis = 250, easing = easeInOut)
    fun <T> bubble(): TweenSpec<T> = tween(durationMillis = 300, easing = easeOut)
    fun <T> gentle(): TweenSpec<T> = tween(durationMillis = 250, easing = easeInOut)
}

object PleadCopy {
    const val tagline = "Same people. More perspective."
    const val hero = "Someone needs to settle this."
    const val heroCTA = "SUMMON YOUR PARTNER"
    const val principle = "Not about winning. About understanding."
    const val paywallTitle = "One subscription. Both of you."
    const val legallyBound = "YOU ARE NOW LEGALLY BOUND"
    const val legallyBoundDisclaimer = "This is not legally binding."
    const val allRise = "ALL RISE"
    const val decisionReached = "THE COURT HAS REACHED A DECISION"
    const val deliberating = "THE COURT IS DELIBERATING"

    /** Deliberation theatre lines, indexed by `Case.panelProgress` 0...4. */
    val deliberationStatus: List<String> = listOf(
        "Awaiting the record",
        "Juror 01: Evidence reviewed",
        "Juror 02: Testimony reviewed",
        "Juror 03: Considering remedy",
        "Presiding judge: Preparing the ruling",
    )
}
