// The judge bubble's header (CourtJudgeHeader): the judge's full name shows beside the longest phase chip
// (CROSS-EXAMINATION) on a Pixel 7 (411 dp) and on a 360 dp phone, at font scales 1, 1.15, 1.3 and past the bubble's
// cap (xxLarge); the chip is never clipped. Swift: `Text(name).lineLimit(1); Spacer(minLength: 4); CourtPhaseChip(...).fixedSize()`.
// Where iOS would truncate the name (360 dp at large text) the header shrinks the name and chip text together, down to
// 0.8, then wraps the chip under the name (an Android-only decision); the Pixel 7 layout never changes.
package app.plead.android.courtroom

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.plead.android.designsystem.PleadTheme
import app.plead.android.models.JudgePersona
import app.plead.android.models.ObjectionRuling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class CourtJudgeHeaderTests {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** Header width inside the judge bubble: `min(screen − 32, 400)` minus the bubble's 14 + 14 padding. */
    private fun headerWidth(screenWidthDp: Float) = minOf(screenWidthDp - 32f, 400f) - 28f

    private data class Params(val width: Float, val fontScale: Float, val chip: String?, val ruling: ObjectionRuling?)

    private var params by mutableStateOf<Params?>(null)

    private fun layout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes()[0]
            .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(results)
        return results.first()
    }

    /** One `setContent` per test; later renders change the parameters. */
    private fun render(width: Float, fontScale: Float, chip: String?, ruling: ObjectionRuling? = null) {
        val first = params == null
        params = Params(width, fontScale, chip, ruling)
        if (first) {
            rule.setContent {
                val p = params ?: return@setContent
                val d = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(d.density, p.fontScale)) {
                    PleadTheme {
                        // The scene caps the judge bubble at xxLarge (CourtroomScene: DynamicTypeCap(.xxLarge)).
                        DynamicTypeCap(DynamicTypeSize.xxLarge) {
                            Column {
                                CourtJudgeHeader(
                                    name = JudgePersona.wigsworth.displayName, ruling = p.ruling, chip = p.chip,
                                    modifier = Modifier.width(p.width.dp).testTag("header"),
                                )
                                // The same header with all the room it wants: the chip / ruling box at its natural width.
                                Box(Modifier.wrapContentWidth(unbounded = true).testTag("natural")) {
                                    CourtJudgeHeader(name = "", ruling = p.ruling, chip = p.chip)
                                }
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

    private fun chipNodes() = rule.onAllNodesWithTag(CourtJudgeHeaderTags.chip, useUnmergedTree = true).fetchSemanticsNodes()

    /** The chip (or ruling box) in the header is as wide as when nothing constrains it: never clipped. */
    private fun assertChipUnclipped(what: String) {
        val nodes = chipNodes()
        assertEquals(2, nodes.size)
        assertEquals("$what is clipped", nodes[1].size.width, nodes[0].size.width)
    }

    /**
     * Narrow rows: the chip shows whole (at least [CourtJudgeHeaderFit.minimumScaleFactor] of its natural width, which
     * is all shrinking its text can take off) and inside the header's bounds.
     */
    private fun assertChipWhole(what: String) {
        val nodes = chipNodes()
        assertEquals(2, nodes.size)
        val chip = nodes[0]
        assertTrue(
            "$what is clipped (${chip.size.width} of ${nodes[1].size.width})",
            chip.size.width >= (nodes[1].size.width * CourtJudgeHeaderFit.minimumScaleFactor).toInt() - 2,
        )
        val header = rule.onAllNodesWithTag("header", useUnmergedTree = true).fetchSemanticsNodes()[0].boundsInRoot
        assertTrue("$what runs past the header (${chip.boundsInRoot.right} > ${header.right})", chip.boundsInRoot.right <= header.right + 1f)
        assertTrue("$what runs below the header", chip.boundsInRoot.bottom <= header.bottom + 1f)
    }

    /** Name and chip on one row (vertically overlapping). */
    private fun assertOneRow(what: String) {
        val name = rule.onAllNodesWithTag(CourtJudgeHeaderTags.name, useUnmergedTree = true).fetchSemanticsNodes()[0].boundsInRoot
        val chip = chipNodes()[0].boundsInRoot
        assertTrue("$what: chip not beside the name", chip.top < name.bottom && name.top < chip.bottom)
    }

    private fun check(screenWidthDp: Float, fontScale: Float) {
        render(headerWidth(screenWidthDp), fontScale, chip = "Cross-examination")
        assertWhole(CourtJudgeHeaderTags.name, "Judge name (${screenWidthDp}dp, ×$fontScale)")
        assertChipUnclipped("Phase chip (${screenWidthDp}dp, ×$fontScale)")
    }

    /** Every scale the task covers: 1, 1.15, 1.3 and far past the bubble's xxLarge cap (2.0 renders at the cap). */
    private val scales = listOf(1f, 1.15f, 1.3f, 2f)

    @Test fun fullNameBesideCrossExaminationOnPixel7() = check(411f, 1f)

    @Test fun fullNameBesideCrossExaminationOnPixel7AtLargeText() = check(411f, 1.3f)

    @Test fun fullNameBesideCrossExaminationOn360dpPhone() = check(360f, 1f)

    /** Pixel 7: the one-row header at full size at every scale (unchanged by the narrow-phone fit). */
    @Test fun pixel7KeepsTheOneRowHeaderAtEveryScale() {
        for (scale in scales) {
            render(headerWidth(411f), scale, chip = "Cross-examination")
            assertWhole(CourtJudgeHeaderTags.name, "Judge name (411dp, ×$scale)")
            assertChipUnclipped("Phase chip (411dp, ×$scale)")
            assertOneRow("411dp, ×$scale")
        }
    }

    /** 360 dp: the full name is readable at every scale, and the chip is whole and inside the header. */
    @Test fun fullNameOn360dpPhoneAtEveryScale() {
        for (scale in scales) {
            render(headerWidth(360f), scale, chip = "Cross-examination")
            assertWhole(CourtJudgeHeaderTags.name, "Judge name (360dp, ×$scale)")
            assertChipWhole("Phase chip (360dp, ×$scale)")
            println("CourtJudgeHeader 360dp ×$scale: chip ${chipNodes()[0].size.width}/${chipNodes()[1].size.width} px, one row: " +
                runCatching { assertOneRow(""); true }.getOrDefault(false))
        }
        // At the default scale nothing shrinks.
        render(headerWidth(360f), 1f, chip = "Cross-examination")
        assertChipUnclipped("Phase chip (360dp, ×1)")
        assertOneRow("360dp, ×1")
    }

    @Test fun rulingBoxKeepsTheFullNameOn360dpAtEveryScale() {
        for (scale in scales) {
            render(headerWidth(360f), scale, chip = null, ruling = ObjectionRuling.overruled)
            assertWhole(CourtJudgeHeaderTags.name, "Judge name beside the ruling (360dp, ×$scale)")
            assertChipWhole("Ruling box (360dp, ×$scale)")
        }
    }

    @Test fun fitChoosesTheLargestScaleThatFitsThenWraps() {
        // width(s) = 100 fixed + 300 × s of text.
        val width = { s: Float -> (100 + 300 * s).toInt() }
        assertEquals(CourtJudgeHeaderFit.Layout.normal, CourtJudgeHeaderFit.choose(400, width))
        val fit = CourtJudgeHeaderFit.choose(370, width)
        assertFalse(fit.wrap)
        assertEquals(0.9f, fit.scale, 0.001f)
        assertEquals(CourtJudgeHeaderFit.Layout(0.8f, wrap = false), CourtJudgeHeaderFit.choose(340, width))
        assertEquals(CourtJudgeHeaderFit.Layout(1f, wrap = true), CourtJudgeHeaderFit.choose(339, width))
    }

    @Test fun nameTakesTheRoomTheChipLeaves() {
        // Before the fix the name and a weighted Spacer split the leftover, so the name got half of it.
        render(headerWidth(411f), 1f, chip = "Cross-examination")
        val name = rule.onAllNodesWithTag(CourtJudgeHeaderTags.name, useUnmergedTree = true).fetchSemanticsNodes()[0].boundsInRoot
        val chip = chipNodes()[0].boundsInRoot
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
