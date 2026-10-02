// Letter spacing parity (PleadTracking): iOS text has no added tracking unless the Swift applies `.tracking` /
// `.kerning`. Material 3's type scale tracks body text by 0.5 sp, and a Compose `Text` without a style of its own
// inherits it from `LocalTextStyle`. These tests pin the rule: the theme's styles (the default text style included)
// and every PleadType / CourtFont token are untracked, except the tokens listed in `PleadTracking.tracked`.
package app.plead.android.designsystem

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
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
class LetterSpacingTests {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Every `TextStyle` property of a token object (`PleadType`, `CourtFont`, `PleadFont`), by "Object.name". */
    private fun tokens(owner: Any, prefix: String): Map<String, TextStyle> =
        owner.javaClass.declaredMethods
            .filter { it.parameterCount == 0 && it.returnType == TextStyle::class.java && it.name.startsWith("get") }
            .associate { m ->
                m.isAccessible = true
                "$prefix.${m.name.removePrefix("get").replaceFirstChar { it.lowercase() }}" to m.invoke(owner) as TextStyle
            }

    @Test fun themeTypographyIsUntracked() {
        assertEquals(15, PleadThemeTypography.all.size)
        for ((name, style) in PleadThemeTypography.all) assertEquals("Material $name", 0.sp, style.letterSpacing)
        assertEquals(0.sp, PleadThemeTypography.defaultTextStyle.letterSpacing)
        // Only the tracking changed: Material's sizes, weights and line heights are kept.
        val material = androidx.compose.material3.Typography()
        assertEquals(material.bodyLarge.fontSize, PleadThemeTypography.typography.bodyLarge.fontSize)
        assertEquals(material.bodyLarge.lineHeight, PleadThemeTypography.typography.bodyLarge.lineHeight)
        assertEquals(material.labelLarge.fontWeight, PleadThemeTypography.typography.labelLarge.fontWeight)
    }

    @Test fun everyTokenIsUntrackedUnlessSwiftTracksIt() {
        val all = tokens(PleadType, "PleadType") + tokens(CourtFont, "CourtFont")
        // Sanity: the reflection sees the tokens (not an empty map that passes trivially).
        assertTrue(all.size >= 50)
        assertTrue("PleadType.body" in all && "CourtFont.judgeName" in all && "PleadType.labelCapsTracked" in all)
        for ((name, style) in all) {
            val expected = PleadTracking.tracked[name]?.sp ?: 0.sp
            assertEquals("$name letter spacing", expected, style.letterSpacing)
        }
        // The table mirrors Swift: `pleadLabelCaps()` = labelCaps + `.tracking(PleadType.capsTracking)` (1.2 pt).
        assertEquals(mapOf("PleadType.labelCapsTracked" to 1.2f), PleadTracking.tracked)
        // Builders used by call sites are untracked too.
        assertEquals(0.sp, PleadType.ui(13f, androidx.compose.ui.text.font.FontWeight.Bold, TextStyleKind.footnote).letterSpacing)
        assertEquals(0.sp, PleadType.text(13f, relativeTo = TextStyleKind.footnote).letterSpacing)
        assertEquals(0.sp, PleadType.display(21f, relativeTo = TextStyleKind.title2).letterSpacing)
        assertEquals(0.sp, CourtFont.display(15f, androidx.compose.ui.text.font.FontWeight.SemiBold, relativeTo = TextStyleKind.subheadline).letterSpacing)
    }

    @Test fun legacyFontTokensAddNoTracking() {
        // PleadFont (Swift AWFont, Tokens.kt) leaves letter spacing unspecified: 0 when drawn with an explicit style.
        for ((name, style) in tokens(PleadFont, "PleadFont")) {
            assertTrue("$name letter spacing ${style.letterSpacing}", style.letterSpacing.value == 0f || style.letterSpacing == androidx.compose.ui.unit.TextUnit.Unspecified)
        }
    }

    private val sample = "Judge Wigsworth presides over the case"

    /** A `Text` with no style of its own under PleadTheme measures exactly as one with `letterSpacing = 0.sp`. */
    @Test fun unstyledTextMeasuresAsUntracked() {
        val widths = mutableMapOf<String, Int>()
        fun record(key: String) = { r: TextLayoutResult -> widths[key] = r.size.width }
        rule.setContent {
            Column {
                PleadTheme {
                    assertEquals(0.sp, LocalTextStyle.current.letterSpacing)
                    assertEquals(0.sp, CourtFont.miniLabel.letterSpacing)
                    Text(sample, onTextLayout = record("theme"))
                    Text(sample, letterSpacing = 0.sp, onTextLayout = record("zero"))
                    TextButton(onClick = {}) { Text(sample, onTextLayout = record("button")) }
                    TextButton(onClick = {}) { Text(sample, letterSpacing = 0.sp, onTextLayout = record("buttonZero")) }
                }
                // The stock Material 3 theme, for the before/after record (bodyLarge tracks 0.5 sp, labelLarge 0.1 sp).
                MaterialTheme {
                    Text(sample, onTextLayout = record("material"))
                    TextButton(onClick = {}) { Text(sample, onTextLayout = record("materialButton")) }
                }
            }
        }
        rule.waitForIdle()
        println("LetterSpacingTests widths (px at xxhdpi): $widths")
        assertEquals(widths["zero"], widths["theme"])
        assertEquals(widths["buttonZero"], widths["button"])
        // 0.5 sp × 38 characters ≈ 19 sp wider with Material's tracking.
        assertTrue(widths.getValue("material") > widths.getValue("theme") + 40)
        assertTrue(widths.getValue("materialButton") > widths.getValue("button"))
    }

    /** Explicitly styled text never picked the theme's tracking up (an explicit style replaces LocalTextStyle). */
    @Test fun styledTextIsTheSameUnderEitherTheme() {
        val widths = mutableMapOf<String, Int>()
        rule.setContent {
            Column {
                PleadTheme { Text(sample, style = CourtFont.judgeName, onTextLayout = { widths["plead"] = it.size.width }) }
                MaterialTheme { Text(sample, style = CourtFont.judgeName, onTextLayout = { widths["material"] = it.size.width }) }
            }
        }
        rule.waitForIdle()
        assertEquals(widths["material"], widths["plead"])
    }
}
