// Sheet detent fitting (Swift `.presentationDetents([.medium, .large])`) and the record's scroll anchors
// (Swift `ScrollViewReader.scrollTo(id, anchor:)`).
package app.plead.android.features.casedetail

import org.junit.Assert.assertEquals
import org.junit.Test

class CaseScreenKitTests {
    @Test fun partialSheetContentFitsTheVisiblePart() {
        // Before anchors: the whole content box.
        assertEquals(2000, SheetDetentLayout.visibleHeight(2000, null))
        assertEquals(2000, SheetDetentLayout.visibleHeight(2000, Float.NaN))
        // Expanded (iOS `.large`): everything, so the bottom padding + navigation bar inset end the sheet.
        assertEquals(2000, SheetDetentLayout.visibleHeight(2000, 0f))
        // Partial (iOS `.medium`): only what is on screen, so the scroll's bottom padding sits above the screen edge.
        assertEquals(1000, SheetDetentLayout.visibleHeight(2000, 1000f))
        assertEquals(999, SheetDetentLayout.visibleHeight(2000, 1000.6f))
        // Hidden / mid-dismiss: never negative.
        assertEquals(0, SheetDetentLayout.visibleHeight(2000, 2200f))
    }

    @Test fun topAnchorIncludesTheColumnsPadding() {
        // The record column pads 16 dp (48 px at 3x) inside its scroll; anchors are measured inside that padding.
        assertEquals(1248, ScrollAnchors.target(top = 1200, height = 600, viewport = 2000, center = false, contentPaddingTop = 48, maxScroll = 5000))
        // Without padding, unchanged from before.
        assertEquals(1200, ScrollAnchors.target(1200, 600, 2000, center = false, contentPaddingTop = 0, maxScroll = 5000))
        // Clamped to the scroll range.
        assertEquals(900, ScrollAnchors.target(1200, 600, 2000, center = false, contentPaddingTop = 48, maxScroll = 900))
        assertEquals(0, ScrollAnchors.target(100, 600, 2000, center = true, contentPaddingTop = 0, maxScroll = 900))
    }

    @Test fun centreAnchor() {
        assertEquals(1500 + 300 - 1000, ScrollAnchors.target(1500, 600, 2000, center = true, contentPaddingTop = 0, maxScroll = 5000))
        assertEquals(1548 + 300 - 1000, ScrollAnchors.target(1500, 600, 2000, center = true, contentPaddingTop = 48, maxScroll = 5000))
    }
}
