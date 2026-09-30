// Plead typography tokens (CONTRACTS-v2 amendment u). One app, two voices:
// Fraunces for display/editorial moments, the system sans (iOS: SF Rounded / SF Pro Text) for UI and body.
// Port of ArgueWin/DesignSystem/Typography.swift and the `CourtFont` section of ArgueWin/Courtroom/CourtStyle.swift.
// Rule for agents: ADD usages; only the font-bundling agent edits `FrauncesFont`.
//
// Sizes are sp, so they scale with the system font size the way iOS text styles scale with Dynamic Type.
// SF Pro Rounded has no Android equivalent: rounded styles use the default sans (Roboto) with the same
// weights and sizes (PORT.md §2). Monospaced digits use the `tnum` font feature.
package app.plead.android.designsystem

import androidx.annotation.FontRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.plead.android.R
import kotlin.math.abs

/**
 * The bundled display serif. Names are the PostScript names of the static Fraunces instances (the same
 * eight TTFs as iOS, 72pt optical size, SOFT 0, WONK 1), in `res/font` under lower-case file names.
 * Android bundles the fonts in the APK, so they are always available ([isAvailable] is true); the
 * system-serif fallback path is kept for parity with iOS.
 */
object FrauncesFont {
    const val regular = "Fraunces72pt-Regular"
    const val medium = "Fraunces72pt-Medium"
    const val semibold = "Fraunces72pt-SemiBold"
    const val bold = "Fraunces72pt-Bold"
    const val italic = "Fraunces72pt-Italic"
    const val mediumItalic = "Fraunces72pt-MediumItalic"
    const val semiboldItalic = "Fraunces72pt-SemiBoldItalic"
    const val boldItalic = "Fraunces72pt-BoldItalic"

    /** Every bundled name, for verification (TypographyTests). */
    val allNames = listOf(regular, medium, semibold, bold, italic, mediumItalic, semiboldItalic, boldItalic)

    /** PostScript name → font resource. */
    val resources: Map<String, Int> = mapOf(
        regular to R.font.fraunces72pt_regular,
        medium to R.font.fraunces72pt_medium,
        semibold to R.font.fraunces72pt_semi_bold,
        bold to R.font.fraunces72pt_bold,
        italic to R.font.fraunces72pt_italic,
        mediumItalic to R.font.fraunces72pt_medium_italic,
        semiboldItalic to R.font.fraunces72pt_semi_bold_italic,
        boldItalic to R.font.fraunces72pt_bold_italic,
    )

    const val isAvailable: Boolean = true

    /** The whole family: Compose picks the face from `fontWeight` + `fontStyle` (heavy/black resolve to Bold). */
    val family: FontFamily = FontFamily(
        Font(R.font.fraunces72pt_regular, FontWeight.Normal, FontStyle.Normal),
        Font(R.font.fraunces72pt_medium, FontWeight.Medium, FontStyle.Normal),
        Font(R.font.fraunces72pt_semi_bold, FontWeight.SemiBold, FontStyle.Normal),
        Font(R.font.fraunces72pt_bold, FontWeight.Bold, FontStyle.Normal),
        Font(R.font.fraunces72pt_italic, FontWeight.Normal, FontStyle.Italic),
        Font(R.font.fraunces72pt_medium_italic, FontWeight.Medium, FontStyle.Italic),
        Font(R.font.fraunces72pt_semi_bold_italic, FontWeight.SemiBold, FontStyle.Italic),
        Font(R.font.fraunces72pt_bold_italic, FontWeight.Bold, FontStyle.Italic),
    )

    fun name(weight: FontWeight, italic: Boolean): String = when {
        weight.weight >= FontWeight.Bold.weight -> if (italic) boldItalic else bold
        weight == FontWeight.SemiBold -> if (italic) semiboldItalic else semibold
        weight == FontWeight.Medium -> if (italic) mediumItalic else medium
        italic -> FrauncesFont.italic
        else -> regular
    }

    @FontRes
    fun resource(weight: FontWeight, italic: Boolean): Int = resources.getValue(name(weight, italic))
}

/** Swift `Font.TextStyle` with its default (Large) point size, iOS HIG. */
enum class TextStyleKind(val defaultSize: Float) {
    largeTitle(34f), title(28f), title2(22f), title3(20f), headline(17f),
    body(17f), callout(16f), subheadline(15f), footnote(13f), caption(12f), caption2(11f);

    companion object {
        /** Order of `Font.TextStyle.defaultSizes` in Typography.swift (ties keep the first match unless preferred). */
        private val defaultSizes = listOf(largeTitle, title, title2, title3, headline, body, callout, subheadline, footnote, caption, caption2)

        /** The text style whose default size is nearest to `size`; `preferred` wins ties. */
        fun nearest(size: Float, preferring: TextStyleKind): TextStyleKind {
            var best = preferring
            var bestDistance = Float.MAX_VALUE
            for (style in defaultSizes) {
                val d = abs(style.defaultSize - size)
                if (d < bestDistance || (d == bestDistance && style == preferring)) {
                    best = style
                    bestDistance = d
                }
            }
            return best
        }
    }
}

/** `.monospacedDigit()`: tabular figures so countdowns don't jump every second. */
fun TextStyle.monospacedDigit(): TextStyle = copy(fontFeatureSettings = "tnum")

fun TextStyle.italic(): TextStyle = copy(fontStyle = FontStyle.Italic)

object PleadType {
    // MARK: Display (Fraunces → system serif fallback)

    @Suppress("UNUSED_PARAMETER")
    fun display(size: Float, weight: FontWeight = FontWeight.Bold, italic: Boolean = false, relativeTo: TextStyleKind): TextStyle {
        val style = if (italic) FontStyle.Italic else FontStyle.Normal
        if (FrauncesFont.isAvailable) {
            return TextStyle(fontFamily = FrauncesFont.family, fontWeight = weight, fontStyle = style, fontSize = size.sp)
        }
        return TextStyle(fontFamily = FontFamily.Serif, fontWeight = weight, fontStyle = style, fontSize = size.sp)
    }

    /** Verdict winner, major payoff screens. */
    val displayXL = display(32f, weight = FontWeight.Bold, relativeTo = TextStyleKind.largeTitle)

    /** Case titles ("The Spoiler"). */
    val displayL = display(28f, weight = FontWeight.Bold, relativeTo = TextStyleKind.title)

    /** Court headings, judge ruling titles. */
    val displayM = display(21f, weight = FontWeight.SemiBold, relativeTo = TextStyleKind.title2)
    val displayMItalic = display(21f, weight = FontWeight.SemiBold, italic = true, relativeTo = TextStyleKind.title2)

    /** "Alex v. Sam" under a case title. */
    val caseParties = display(17f, weight = FontWeight.Medium, italic = true, relativeTo = TextStyleKind.body)

    /** Judge speech bubbles: editorial, never heavier than medium. */
    val judgeSpeech = display(17f, weight = FontWeight.Normal, relativeTo = TextStyleKind.body)
    val judgeSpeechEmphasis = display(17f, weight = FontWeight.Medium, relativeTo = TextStyleKind.body)
    val caseTitle = displayL
    val courtDisplay = displayM

    // MARK: UI (SF Rounded → system sans)

    /**
     * The rounded UI font at the text style nearest to `size` (see [TextStyleKind.nearest]), exactly as iOS
     * resolves it: e.g. `ui(21, …, title2)` renders at 22 and `ui(19, …, headline)` at 20.
     */
    fun ui(size: Float, weight: FontWeight, relativeTo: TextStyleKind): TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = TextStyleKind.nearest(size, relativeTo).defaultSize.sp,
    )

    /** Filings, Your Turn, screen sections. */
    val titleL = ui(21f, FontWeight.Bold, relativeTo = TextStyleKind.title2)

    /** Buttons, card titles. */
    val titleM = ui(17f, FontWeight.SemiBold, relativeTo = TextStyleKind.headline)
    val uiTitle = titleL
    val uiButton = ui(17f, FontWeight.Bold, relativeTo = TextStyleKind.headline)
    val uiButtonSecondary = ui(17f, FontWeight.SemiBold, relativeTo = TextStyleKind.headline)

    /** Names, key statuses. */
    val bodyStrong = ui(16f, FontWeight.SemiBold, relativeTo = TextStyleKind.body)

    /** PLAINTIFF, DEFENDANT, OPENING, YOUR TURN — use with [capsTracking] and uppercased text ([labelCapsTracked]). */
    val labelCaps = ui(11f, FontWeight.Bold, relativeTo = TextStyleKind.caption)
    val uiLabel = labelCaps
    val navTitle = ui(16f, FontWeight.SemiBold, relativeTo = TextStyleKind.headline)

    /** Countdowns: monospaced digits so layout doesn't jump every second. */
    val timer = ui(19f, FontWeight.Bold, relativeTo = TextStyleKind.headline).monospacedDigit()
    val timerSmall = ui(15f, FontWeight.Bold, relativeTo = TextStyleKind.subheadline).monospacedDigit()

    // MARK: Dense / utility (SF Pro Text → system sans)

    /** The body font at the text style nearest to `size` (same scaling rules as [ui]). */
    fun text(size: Float, weight: FontWeight = FontWeight.Normal, relativeTo: TextStyleKind): TextStyle = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = TextStyleKind.nearest(size, relativeTo).defaultSize.sp,
    )

    /** Charge text, evidence descriptions, partner speech. */
    val body = text(16f, FontWeight.Normal, relativeTo = TextStyleKind.body)
    val bodyMedium = text(16f, FontWeight.Medium, relativeTo = TextStyleKind.body)

    /** Dates, case numbers, helper text. */
    val metadata = text(13f, FontWeight.Normal, relativeTo = TextStyleKind.footnote)
    val metadataMedium = text(13f, FontWeight.Medium, relativeTo = TextStyleKind.footnote)
    val caption = text(12f, FontWeight.Normal, relativeTo = TextStyleKind.caption)

    /** Tracking used with `labelCaps` (iOS points → sp). */
    const val capsTracking: Float = 1.2f

    /**
     * Swift `View.pleadLabelCaps()`: `labelCaps` + tracking. The caller upper-cases the text
     * (`text.uppercase()`), as `.textCase(.uppercase)` does.
     */
    val labelCapsTracked: TextStyle = labelCaps.copy(letterSpacing = capsTracking.sp)
}

/** A rounded (system sans) style at the default size of a text style: SwiftUI `Font.system(style, design: .rounded, weight:)`. */
private fun rounded(style: TextStyleKind, weight: FontWeight): TextStyle =
    TextStyle(fontFamily = FontFamily.Default, fontWeight = weight, fontSize = style.defaultSize.sp)

/** SwiftUI `Font.system(style)` (SF Pro Text): the default weight of every style but headline is regular. */
private fun system(style: TextStyleKind, weight: FontWeight = FontWeight.Normal): TextStyle =
    TextStyle(fontFamily = FontFamily.Default, fontWeight = weight, fontSize = style.defaultSize.sp)

/**
 * The courtroom's typography (port of `CourtFont` in CourtStyle.swift): the Plead tokens expressed against text
 * styles. Sizes match the tokens at the default text size. Fonts never morph between states.
 */
object CourtFont {
    // MARK: Display (Fraunces): judge speech, rulings, verdict and court headings.

    fun display(size: Float, weight: FontWeight, italic: Boolean = false, relativeTo: TextStyleKind): TextStyle {
        if (FrauncesFont.isAvailable) {
            return PleadType.display(size, weight = weight, italic = italic, relativeTo = relativeTo)
        }
        val f = TextStyle(fontFamily = FontFamily.Serif, fontWeight = weight, fontSize = relativeTo.defaultSize.sp)
        return if (italic) f.italic() else f
    }

    /** Verdict winner ("PLAINTIFF WINS"): PleadType.displayXL. */
    val displayXL = display(32f, FontWeight.Bold, relativeTo = TextStyleKind.largeTitle)

    /** PleadType.caseTitle (displayL). */
    val caseTitle = display(28f, FontWeight.Bold, relativeTo = TextStyleKind.title)

    /** PleadType.displayM: court headings, judgement / settlement titles. */
    val displayM = display(21f, FontWeight.SemiBold, relativeTo = TextStyleKind.title2)

    /** Compact court heading in the scene's centre column (judgement card title). */
    val displayS = display(15f, FontWeight.SemiBold, relativeTo = TextStyleKind.subheadline)
    val displayMItalic = display(21f, FontWeight.SemiBold, italic = true, relativeTo = TextStyleKind.title2)

    /** PleadType.judgeSpeech: the judge's bubble text (never heavier than medium). */
    val judgeSpeech = display(17f, FontWeight.Normal, relativeTo = TextStyleKind.body)

    /** The judge's numbered cross-examination questions: judgeSpeech one step down (16). */
    val judgeQuestion = display(16f, FontWeight.Normal, relativeTo = TextStyleKind.callout)

    /** PleadType.judgeSpeechEmphasis: rulings (SUSTAINED / OVERRULED lines, verdict lines). */
    val ruling = display(17f, FontWeight.Medium, relativeTo = TextStyleKind.body)

    /** Larger ruling line (verdict card, full easel). */
    val rulingLarge = display(20f, FontWeight.Medium, relativeTo = TextStyleKind.title3)

    /** Larger judge narration ("Sam chooses the judgement.") and its italic aside. */
    val judgeSpeechLarge = display(20f, FontWeight.Normal, relativeTo = TextStyleKind.title3)
    val rulingItalic = display(20f, FontWeight.Normal, italic = true, relativeTo = TextStyleKind.title3)

    /** The "SETTLED OUT OF COURT" seal on the easel (a short caps stamp, Fraunces bold). */
    val seal = display(14f, FontWeight.Bold, relativeTo = TextStyleKind.footnote)
    val sealSmall = display(12f, FontWeight.Bold, relativeTo = TextStyleKind.caption)

    /** PleadType.caseParties: "Alex v. Sam". */
    val caseParties = display(17f, FontWeight.Medium, italic = true, relativeTo = TextStyleKind.body)

    // MARK: UI (SF Rounded → system sans): names, chips, instructions, buttons, counters.

    /** Judge name in the bubble header / nameplate: semibold 12. */
    val caption = rounded(TextStyleKind.caption, FontWeight.SemiBold)
    val judgeName = caption

    /** Exhibit title: semibold 15. */
    val exhibitTitle = rounded(TextStyleKind.subheadline, FontWeight.SemiBold)

    /** PleadType.labelCaps (11, bold): PLAINTIFF / DEFENDANT, OPENING, YOUR TURN, EXHIBIT A. Uppercased + capsTracking. */
    val legal = rounded(TextStyleKind.caption2, FontWeight.Bold)
    val caption2 = legal

    /** A slightly larger caps label (ALL RISE, JUDGEMENT). */
    val legalLarge = rounded(TextStyleKind.footnote, FontWeight.Bold)

    /** Partner name (bodyStrong-ish): semibold 15. */
    val partyName = rounded(TextStyleKind.subheadline, FontWeight.SemiBold)

    /** A name under the verdict headline: semibold title2 (lighter than the headline). */
    val nameLarge = rounded(TextStyleKind.title2, FontWeight.SemiBold)

    /** The dock's instruction ("Show your evidence"): bold 17. */
    val headline = rounded(TextStyleKind.headline, FontWeight.Bold)
    val instruction = headline

    /** PleadType.uiButton / uiButtonSecondary. */
    val bodyBold = rounded(TextStyleKind.body, FontWeight.Bold)
    val button = bodyBold
    val buttonSecondary = rounded(TextStyleKind.body, FontWeight.SemiBold)

    /** Quiet text actions (Rest, Decline, Change). */
    val link = rounded(TextStyleKind.footnote, FontWeight.SemiBold)

    /** PleadType.titleL: screen sections (transcript). */
    val title = rounded(TextStyleKind.title2, FontWeight.Bold)

    /** PleadType.timer: monospaced digits so the layout never jumps. */
    val timer = rounded(TextStyleKind.title3, FontWeight.Bold).monospacedDigit()
    val timerSmall = rounded(TextStyleKind.subheadline, FontWeight.Bold).monospacedDigit()
    val stamp = rounded(TextStyleKind.footnote, FontWeight.Black)

    /** Fixed-size caps label painted onto the pixel art (easel mini frame): 10, does not scale with font size. */
    val miniLabel: TextStyle
        @Composable @ReadOnlyComposable
        get() {
            val fontScale = LocalDensity.current.fontScale
            return TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold, fontSize = (10f / fontScale).sp)
        }

    // MARK: Text (SF Pro Text → system sans): partner speech, exhibit bodies, metadata.

    /** PleadType.body-sized partner speech (16): user arguments are never Fraunces. */
    val callout = system(TextStyleKind.callout)
    val speech = callout

    /** Long body text (transcript lines, composer input). */
    val body = system(TextStyleKind.body)

    /** PleadType.metadata (13). */
    val footnote = system(TextStyleKind.footnote)
    val footnoteMedium = system(TextStyleKind.footnote, FontWeight.Medium)

    /** Caption-sized text (tight scene cards). */
    val small = system(TextStyleKind.caption)
}
