// Port of ArgueWinTests/TypographyTests.swift. CONTRACTS-v2 amendment u (Bundling): the Fraunces TTFs ship in
// the app (res/font) and every name in `FrauncesFont` resolves at runtime.
package app.plead.android.designsystem

import android.content.Context
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.content.res.ResourcesCompat
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TypographyTests {
    @Test fun everyFrauncesNameResolves() {
        assertEquals(8, FrauncesFont.allNames.size)
        assertEquals(8, FrauncesFont.allNames.toSet().size)
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (name in FrauncesFont.allNames) {
            val res = FrauncesFont.resources[name]
            assertNotNull("no font resource for \"$name\"", res)
            assertNotNull("font \"$name\" did not resolve", ResourcesCompat.getFont(context, res!!))
        }
    }

    @Test fun frauncesIsAvailable() {
        assertTrue(FrauncesFont.isAvailable)
    }

    /** iOS: registered via UIAppFonts and present in the bundle. Android: the same eight TTFs in res/font. */
    @Test fun fontsAreRegisteredFromTheAppBundle() {
        val dir = File("src/main/res/font")
        val files = dir.listFiles { f -> f.extension == "ttf" }?.map { it.name }.orEmpty()
        assertEquals(8, files.size)
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (res in FrauncesFont.resources.values) {
            val entry = context.resources.getResourceEntryName(res)
            assertTrue("$entry.ttf missing from res/font", files.contains("$entry.ttf"))
        }
    }

    @Test fun weightAndItalicMapToTheRightFace() {
        assertEquals(FrauncesFont.regular, FrauncesFont.name(FontWeight.Normal, italic = false))
        assertEquals(FrauncesFont.italic, FrauncesFont.name(FontWeight.Normal, italic = true))
        assertEquals(FrauncesFont.medium, FrauncesFont.name(FontWeight.Medium, italic = false))
        assertEquals(FrauncesFont.mediumItalic, FrauncesFont.name(FontWeight.Medium, italic = true))
        assertEquals(FrauncesFont.semibold, FrauncesFont.name(FontWeight.SemiBold, italic = false))
        assertEquals(FrauncesFont.semiboldItalic, FrauncesFont.name(FontWeight.SemiBold, italic = true))
        assertEquals(FrauncesFont.bold, FrauncesFont.name(FontWeight.Bold, italic = false))
        assertEquals(FrauncesFont.boldItalic, FrauncesFont.name(FontWeight.Bold, italic = true))
    }

    @Test fun displayTokenSmoke() {
        val font = PleadType.display(28f, weight = FontWeight.Bold, relativeTo = TextStyleKind.title)
        assertEquals(FrauncesFont.family, font.fontFamily)
        assertEquals(FontWeight.Bold, font.fontWeight)
        assertEquals(FontStyle.Normal, font.fontStyle)
        assertEquals(28.sp, font.fontSize)
        assertNotNull(PleadType.displayXL)
        assertNotNull(PleadType.caseTitle)
        assertNotNull(PleadType.judgeSpeech)
        assertNotNull(PleadType.caseParties)
    }

    // Android-only: the UI tokens resolve to the same point sizes iOS renders (nearest text style).

    @Test fun uiTokensUseTheNearestTextStyleSize() {
        assertEquals(22.sp, PleadType.titleL.fontSize)       // ui(21, title2) → title2 22
        assertEquals(17.sp, PleadType.titleM.fontSize)
        assertEquals(16.sp, PleadType.bodyStrong.fontSize)   // callout 16
        assertEquals(11.sp, PleadType.labelCaps.fontSize)    // caption2 11
        assertEquals(20.sp, PleadType.timer.fontSize)        // ui(19, headline) → title3 20
        assertEquals("tnum", PleadType.timer.fontFeatureSettings)
        assertEquals(15.sp, PleadType.timerSmall.fontSize)
        assertEquals(13.sp, PleadType.metadata.fontSize)
        assertEquals(TextStyleKind.headline, TextStyleKind.nearest(17f, TextStyleKind.headline))
        assertEquals(TextStyleKind.body, TextStyleKind.nearest(17f, TextStyleKind.body))
    }

    @Test fun courtFontSizes() {
        assertEquals(12.sp, CourtFont.caption.fontSize)
        assertEquals(11.sp, CourtFont.legal.fontSize)
        assertEquals(20.sp, CourtFont.timer.fontSize)
        assertEquals(FontWeight.Black, CourtFont.stamp.fontWeight)
        assertEquals(FrauncesFont.family, CourtFont.judgeSpeech.fontFamily)
        assertEquals(16.sp, CourtFont.judgeQuestion.fontSize)
    }
}
