// The mock trial's bubble exit (MockTrialBubbleExit.kt): Swift `.transition(.asymmetric(insertion: .identity,
// removal: .opacity))` under the stage's `.easeOut(duration: fadeBack)` / Reduce Motion `.easeOut(duration: 0.15)`.
package app.plead.android.features.onboarding

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockTrialBubbleExitTests {
    private data class Line(val id: String, val current: Boolean = true)

    private fun plan() = MockTrialExitPlan<Line> { it.id }
    private val alpha: (Line) -> Float = { if (it.current) 1f else MockTrialTiming.fadedBack.toFloat() }
    private fun rect(y: Float) = Rect(Offset(10f, y), Size(300f, 80f))

    @Test fun timingIsTheStageAnimation() {
        assertEquals(220, MockTrialBubbleExit.millis(reduceMotion = false))
        assertEquals(MockTrialTiming.fadeBack, MockTrialBubbleExit.seconds(false), 0.0)
        assertEquals(150, MockTrialBubbleExit.millis(reduceMotion = true))
    }

    @Test fun opacityEasesOutFromWhereItWasToZero() {
        assertEquals(1f, MockTrialBubbleExit.alpha(1f, 0, false), 1e-4f)
        assertEquals(0f, MockTrialBubbleExit.alpha(1f, 220, false), 1e-4f)
        assertEquals(0f, MockTrialBubbleExit.alpha(1f, 500, false), 1e-4f)
        assertEquals(0f, MockTrialBubbleExit.alpha(1f, 150, true), 1e-4f)
        // Ease-out: more than half gone at the midpoint.
        assertTrue(MockTrialBubbleExit.alpha(1f, 110, false) < 0.5f)
        // A faded-back line fades from its faded opacity.
        val faded = MockTrialTiming.fadedBack.toFloat()
        assertEquals(faded, MockTrialBubbleExit.alpha(faded, 0, false), 1e-4f)
        assertTrue(MockTrialBubbleExit.alpha(faded, 60, false) < faded)
    }

    @Test fun departedKeepsPreviousOrder() {
        assertEquals(listOf("a", "c"), MockTrialBubbleExit.departed(listOf("a", "b", "c"), listOf("b", "d")))
        assertEquals(emptyList<String>(), MockTrialBubbleExit.departed(listOf("a"), listOf("a", "b")))
    }

    @Test fun aLineThatLeavesItsSlotFadesWhereItStood() {
        val p = plan()
        assertFalse(p.sync(listOf(Line("j-0")), alpha))
        p.place("j-0", rect(40f))
        // The next line takes the slot: the old one departs at its bounds and opacity; the new one appears in place.
        assertTrue(p.sync(listOf(Line("j-1")), alpha))
        val d = p.departures.single()
        assertEquals("j-0", d.item.id)
        assertEquals(rect(40f), d.bounds)
        assertEquals(1f, d.from, 0f)
        // Recomposing with the same lines changes nothing.
        assertFalse(p.sync(listOf(Line("j-1")), alpha))
        assertTrue(p.finish(d.serial))
        assertTrue(p.departures.isEmpty())
    }

    @Test fun aFadedBackLineDepartsFromItsFadedOpacity() {
        val p = plan()
        p.sync(listOf(Line("x-0")), alpha)
        p.place("x-0", rect(0f))
        p.sync(listOf(Line("x-0", current = false)), alpha) // cross-examination: the previous pair, faded back
        p.sync(listOf(Line("y-0")), alpha)
        assertEquals(MockTrialTiming.fadedBack.toFloat(), p.departures.single().from, 1e-6f)
    }

    @Test fun theSlotGoingAwayFadesEveryLine() {
        val p = plan()
        p.sync(listOf(Line("a"), Line("b")), alpha) // closings: both sides
        p.place("a", rect(0f))
        p.place("b", rect(100f))
        assertTrue(p.sync(emptyList(), alpha)) // deliberation takes the room
        assertEquals(listOf("a", "b"), p.departures.map { it.item.id })
    }

    @Test fun aLineNeverLaidOutJustGoes() {
        val p = plan()
        p.sync(listOf(Line("a")), alpha)
        assertFalse(p.sync(emptyList(), alpha))
        assertTrue(p.departures.isEmpty())
    }

    @Test fun aLineThatComesBackCancelsItsExit() {
        val p = plan()
        p.sync(listOf(Line("a")), alpha)
        p.place("a", rect(0f))
        p.sync(emptyList(), alpha)
        assertEquals(1, p.departures.size)
        assertTrue(p.sync(listOf(Line("a")), alpha))
        assertTrue(p.departures.isEmpty())
    }

    @Test fun eachDepartureHasItsOwnKey() {
        val p = plan()
        repeat(2) {
            p.sync(listOf(Line("a")), alpha)
            p.place("a", rect(0f))
            p.sync(emptyList(), alpha)
        }
        // "a" left, came back (cancelling), left again: one live departure with a fresh serial.
        assertEquals(1, p.departures.size)
        assertEquals("exit-1", p.departures.single().id)
    }

    @Test fun stageOpacityMatchesSwift() {
        val beat = MockTrialBeat.entries.first { it.isCrossExamination }
        val line = MockTrialStage.lines(MockTrialStage.Slot.top, beat, 99).first()
        assertEquals(1f, MockTrialStage.opacity(line), 0f)
        assertEquals(MockTrialTiming.fadedBack.toFloat(), MockTrialStage.opacity(line.copy(current = false)), 0f)
    }
}
