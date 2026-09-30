// The pure half of the widget port: PleadStatusProvider's timeline, the size → family mapping, the pixel sprites
// (Shared/PixelJudgeGlyph.swift) and the small helpers the family views use. Plain JVM.
package app.plead.android.widgets

import app.plead.android.services.WidgetFamily
import app.plead.android.services.WidgetPrivacyMode
import app.plead.android.services.WidgetSnapshot
import app.plead.android.services.WidgetState
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PleadWidgetTimelineTests {
    private val now: Instant = Instant.parse("2026-09-30T12:00:00Z")

    @Test fun timelineRefreshesJustAfterTheDeadline() {
        val snap = WidgetSnapshot.sample(WidgetState.summoned, now = now) // plea due in 5 h 12 min
        val t = PleadStatusProvider.timeline(snap, now)
        val deadline = snap.primary!!.deadlineAt!!
        assertEquals(listOf(now, deadline.plusSeconds(1)), t.entries.map { it.date })
        assertEquals(deadline.plusSeconds(61), t.refreshAfter)
        // Before the deadline the first entry shows; after it, the second (countdown line gone).
        assertEquals(now, PleadStatusProvider.current(t, now.plusSeconds(60)).date)
        assertEquals(deadline.plusSeconds(1), PleadStatusProvider.current(t, deadline.plusSeconds(2)).date)
    }

    @Test fun timelineWithoutDeadlineAsksAgainInAMinute() {
        val t = PleadStatusProvider.timeline(WidgetSnapshot.sample(WidgetState.verdictReady, now = now), now)
        assertEquals(1, t.entries.size)
        assertEquals(now.plusSeconds(60), t.refreshAfter)
        val none = PleadStatusProvider.timeline(null, now)
        assertNull(none.entries.single().snapshot)
    }

    @Test fun sizesMapToFamilies() {
        assertEquals(WidgetFamily.accessoryCircular, PleadWidgetContent.family(57f, 57f))
        assertEquals(WidgetFamily.accessoryRectangular, PleadWidgetContent.family(180f, 57f))
        assertEquals(WidgetFamily.systemSmall, PleadWidgetContent.family(150f, 170f))
        assertEquals(WidgetFamily.systemMedium, PleadWidgetContent.family(320f, 150f))
        // The same width thresholds as WidgetSetupService's pinned-widget detection.
        for (w in listOf(57, 150, 320)) assertEquals(WidgetFamily.fromSize(w), PleadWidgetContent.family(w.toFloat(), 170f))
        assertEquals(4, PleadWidgetContent.sizes.size)
    }

    @Test fun circularGlyphPerState() {
        assertEquals(PixelJudgeGlyph.Kind.face, WidgetState.summoned.circularGlyph)
        assertEquals(PixelJudgeGlyph.Kind.face, WidgetState.verdictReady.circularGlyph)
        assertEquals(PixelJudgeGlyph.Kind.gavel, WidgetState.yourTurn.circularGlyph)
        assertEquals(PixelJudgeGlyph.Kind.gavel, WidgetState.agreementDue.circularGlyph)
        assertEquals(PixelJudgeGlyph.Kind.heart, WidgetState.none.circularGlyph)
    }

    @Test fun circularBadgeAndLabel() {
        assertEquals("3", AccessoryCircularView.badge(3))
        assertEquals("9+", AccessoryCircularView.badge(12))
        assertEquals(
            "Plead. You've been summoned. 1 active case",
            AccessoryCircularView.label(WidgetSnapshot.sample(WidgetState.summoned, WidgetPrivacyMode.detailed, now)),
        )
        // Never the case title, even with detailed previews on.
        assertFalse(AccessoryCircularView.label(WidgetSnapshot.sample(WidgetState.summoned, WidgetPrivacyMode.detailed, now)).contains("Dinner"))
    }

    @Test fun deadlineLabelOnlyWhileAhead() {
        val p = WidgetSnapshot.sample(WidgetState.summoned, now = now).primary
        assertEquals("Plea due in", PleadDeadlineText.label(p, now))
        assertNull(PleadDeadlineText.label(p, p!!.deadlineAt!!.plusSeconds(1)))
        assertNull(PleadDeadlineText.label(WidgetSnapshot.sample(WidgetState.verdictReady, now = now).primary, now))
    }

    @Test fun benchShrinksOnSmallCells() {
        assertEquals(70f, SmallWidgetView.benchSize(146f), 0f)
        assertEquals(50f, SmallWidgetView.benchSize(114f), 0f)
        assertEquals(36f, SmallWidgetView.benchSize(60f), 0f)
        assertEquals(320f, MediumWidgetView.narrowWidth, 0f)
    }

    // MARK: Pixel sprites

    @Test fun spriteSizesIncludeTheOutline() {
        assertEquals(18 + 2, PleadPixelSprites.art(PixelJudgeGlyph.Kind.judge).rows)
        assertEquals(16 + 2, PleadPixelSprites.art(PixelJudgeGlyph.Kind.judge).columns)
        assertEquals(12 + 2, PleadPixelSprites.art(PixelJudgeGlyph.Kind.face).rows)
        assertEquals(23 + 2, PleadPixelSprites.art(PixelJudgeGlyph.Kind.bench).rows)
        assertEquals(26 + 2, PleadPixelSprites.art(PixelJudgeGlyph.Kind.bench).columns)
        assertEquals(6 + 2, PleadPixelSprites.art(PixelJudgeGlyph.Kind.heart).rows)
    }

    @Test fun outlineSurroundsEveryFilledCell() {
        val art = PleadPixelSprites.outlined(listOf("..", ".H"))
        val o = PleadPixelSprites.outline
        // Corners stay clear; the 4-neighbours of the filled cell are outlined.
        assertNull(art.cells[0][0])
        assertEquals(o, art.cells[1][2])
        assertEquals(o, art.cells[2][1])
        assertEquals(PleadPixelSprites.ink['H'], art.cells[2][2])
        assertEquals(o, art.cells[3][2])
        assertEquals(o, art.cells[2][3])
    }

    @Test fun benchStampsJudgeGavelAndHeartPlaque() {
        val bench = PleadPixelSprites.bench
        assertEquals(23, bench.size)
        assertTrue(bench.all { it.length == 26 })
        assertEquals("G".repeat(26), bench[15])
        assertEquals("d".repeat(26), bench[16])
        assertEquals('n', bench[17][1])
        assertEquals("....WWWWWWWW....", bench[0].substring(3, 19))
        // The heart plaque replaces its transparent cells with wood.
        assertTrue(bench[17].contains("mHHmHHm"))
        assertEquals("GmmmmG", bench[4].substring(19, 25))
    }

    @Test fun pixelArtRendersAtIntegerScales() {
        // Whole device pixels per cell, never below 1 (iOS: floor(raw × displayScale)).
        assertEquals(10, PleadPixelSprites.cellPixels(PixelJudgeGlyph.Kind.judge, 70f, 3f)) // 70/20 = 3.5 dp → 10 px
        assertEquals(1, PleadPixelSprites.cellPixels(PixelJudgeGlyph.Kind.bench, 12f, 1f))
        val (w, h) = PleadPixelSprites.pixelSize(PixelJudgeGlyph.Kind.bench, 70f, 3f)
        assertEquals(0, w % 28)
        assertEquals(0, h % 25)
        assertTrue(h <= 210)
    }

    @Test fun previewHarnessPages() {
        assertEquals("lock", WidgetPreviewHarness.page("YES"))
        assertNull(WidgetPreviewHarness.page("no"))
        assertEquals("states", WidgetPreviewHarness.page("States"))
        assertEquals(1.6f, WidgetPreviewHarness.forcedFontScale("ax1")!!, 0f)
        assertNull(WidgetPreviewHarness.forcedFontScale(null))
        assertEquals(8, WidgetPreviewHarness.allStates.size)
    }
}
