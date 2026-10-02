// The judge bubble's header (CourtJudgeHeader): the judge's full name shows beside the longest phase chip
// (CROSS-EXAMINATION): on a Pixel 7 (411 dp) from the default font scale up to the bubble's cap (xxLarge), on a 360 dp
// phone at the default scale; the chip is never clipped. (On 360 dp at large text the name truncates, as Swift's
// `lineLimit(1)` does when the row is too narrow.) Swift: `Text(name).lineLimit(1); Spacer(minLength: 4); CourtPhaseChip(...).fixedSize()`.
package app.plead.android.courtroom

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.PleadTheme
import app.plead.android.models.JudgePersona
import app.plead.android.models.ObjectionRuling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h915dp-xxhdpi")
class CourtJudgeHeaderTests {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Header width inside the judge bubble: `min(screen − 32, 400)` minus the bubble's 14 + 14 padding. */
    private fun headerWidth(screenWidthDp: Float) = minOf(screenWidthDp - 32f, 400f) - 28f

    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()[0]
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    private fun render(width: Float, fontScale: Float, chip: String?, ruling: ObjectionRuling? = null) {
        rule.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale)) {
                PleadTheme {
                    // The scene caps the judge bubble at xxLarge (CourtroomScene: DynamicTypeCap(.xxLarge)).
                    DynamicTypeCap(DynamicTypeSize.xxLarge) {
                        Column {
                            CourtJudgeHeader(
                                name = JudgePersona.wigsworth.displayName, ruling = ruling, chip = chip,
                                modifier = Modifier.width(width.dp),
                            )
                            // The same header with all the room it wants: the chip / ruling box at its natural width.
                            Box(Modifier.wrapContentWidth(unbounded = true).testTag("natural")) {
                                CourtJudgeHeader(name = "", ruling = ruling, chip = chip)
                            }
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun assertWhole(tag: String, what: String) {
        val l = layout(tag)
        assertFalse("$what is ellipsized", l.isLineEllipsized(0))
        assertFalse("$what overflows", l.hasVisualOverflow)
        assertEquals("$what wraps", 1, l.lineCount)
    }

    /** The chip (or ruling box) in the header is as wide as when nothing constrains it: never clipped. */
    private fun assertChipUnclipped(what: String) {
        val nodes = rule.onAllNodesWithTag(CourtJudgeHeaderTags.chip, useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(2, nodes.size)
        assertEquals("$what is clipped", nodes[1].size.width, nodes[0].size.width)
    }

    private fun check(screenWidthDp: Float, fontScale: Float) {
        render(headerWidth(screenWidthDp), fontScale, chip = "Cross-examination")
        assertWhole(CourtJudgeHeaderTags.name, "Judge name (${screenWidthDp}dp, ×$fontScale)")
        assertChipUnclipped("Phase chip (${screenWidthDp}dp, ×$fontScale)")
    }

    @Test fun fullNameBesideCrossExaminationOnPixel7() = check(411f, 1f)

    @Test fun fullNameBesideCrossExaminationOnPixel7AtLargeText() = check(411f, 1.3f)

    @Test fun fullNameBesideCrossExaminationOn360dpPhone() = check(360f, 1f)


    @Test fun nameTakesTheRoomTheChipLeaves() {
        // Before the fix the name and a weighted Spacer split the leftover, so the name got half of it.
        render(headerWidth(411f), 1f, chip = "Cross-examination")
        val name = rule.onAllNodesWithTag(CourtJudgeHeaderTags.name, useUnmergedTree = true).fetchSemanticsNodes()[0].boundsInRoot
        val chip = rule.onAllNodesWithTag(CourtJudgeHeaderTags.chip, useUnmergedTree = true).fetchSemanticsNodes()[0].boundsInRoot
        val gap = with(rule.density) { (chip.left - name.right).toDp().value }
        // name → 6 (spacing) → Spacer(4) → 6 (spacing) → chip.
        assertEquals(16f, gap, 0.6f)
    }

    @Test fun rulingBoxKeepsTheFullName() {
        render(headerWidth(411f), 1f, chip = null, ruling = ObjectionRuling.overruled)
        assertWhole(CourtJudgeHeaderTags.name, "Judge name beside the ruling")
        assertChipUnclipped("Ruling box")
    }
}
