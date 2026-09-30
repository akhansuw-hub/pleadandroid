// Components.swift subjects: `Countdown` formatting (ModelDecodingTests.clockCountdownFormat and the
// `Countdown.clock` line of SecurityAlignmentTests.CourtOutageTests), CaseStatus presentation, exhibit copy.
package app.plead.android.designsystem

import app.plead.android.models.CaseStatus
import app.plead.android.models.ExhibitLabel
import app.plead.android.models.ExhibitType
import app.plead.android.models.ObjectionRuling
import org.junit.Assert.assertEquals
import org.junit.Test

class ComponentsTests {
    // ModelDecodingTests.swift
    @Test fun clockCountdownFormat() {
        assertEquals("03:42:16", Countdown.clock(3.0 * 3600 + 42 * 60 + 16))
        assertEquals("00:00:00", Countdown.clock(0.0))
        assertEquals("1d 00:01:01", Countdown.clock(86_400.0 + 61))
    }

    // SecurityAlignmentTests.swift CourtOutageTests.overdueDeliberationNeverCountsNegative (the Countdown line).
    @Test fun overdueDeliberationNeverCountsNegative() {
        assertEquals("00:00:00", Countdown.clock(-3.0 * 3600))
    }

    @Test fun compactCountdownFormat() {
        assertEquals("Now", Countdown.format(0.0))
        assertEquals("Now", Countdown.format(-5.0))
        assertEquals("2d 4h", Countdown.format(2.0 * 86_400 + 4 * 3600 + 59))
        assertEquals("3h 12m", Countdown.format(3.0 * 3600 + 12 * 60 + 30))
        assertEquals("08:41", Countdown.format(8.0 * 60 + 41.9))
    }

    /** Expected strings are the iOS `DateComponentsFormatter` output for the same inputs (run on macOS). */
    @Test fun spokenCountdown() {
        val ios = mapOf(
            13336.0 to "3 hours, 42 minutes", 90.0 to "1 minute, 30 seconds", 86461.0 to "1 day, 1 minute",
            45.0 to "45 seconds", 3600.0 to "60 minutes", 3601.0 to "1 hour", 3659.0 to "1 hour",
            7199.0 to "1 hour, 59 minutes", 86400.0 to "1 day", 90061.0 to "1 day, 1 hour", 0.4 to "0 seconds",
            59.6 to "59 seconds", 13365.0 to "3 hours, 42 minutes", 172799.0 to "2 days",
        )
        assertEquals("Now", Countdown.spoken(0.0))
        for ((seconds, text) in ios) assertEquals("spoken($seconds)", text, Countdown.spoken(seconds))
    }

    @Test fun statusShortTitles() {
        val expected = mapOf(
            CaseStatus.drafting to "Drafting", CaseStatus.summoned to "Summoned", CaseStatus.defence to "Defence",
            CaseStatus.scheduling to "Scheduling", CaseStatus.trial to "In trial", CaseStatus.deliberating to "Deliberating",
            CaseStatus.awaitingVerdict to "Deliberating", CaseStatus.verdict to "Verdict", CaseStatus.appeal to "Appeal",
            CaseStatus.closed to "Closed", CaseStatus.closedGuilty to "Guilty plea", CaseStatus.closedDefault to "Default",
            CaseStatus.closedSettled to "Settled", CaseStatus.mistrial to "Mistrial",
        )
        assertEquals(CaseStatus.entries.size, expected.size)
        for ((status, title) in expected) assertEquals(title, status.shortTitle)
    }

    @Test fun ribbonColours() {
        assertEquals(PleadColor.burgundy, CaseStatus.trial.ribbonColor)
        assertEquals(PleadColor.burgundy, CaseStatus.summoned.ribbonColor)
        for (s in listOf(CaseStatus.deliberating, CaseStatus.awaitingVerdict, CaseStatus.verdict, CaseStatus.appeal)) {
            assertEquals(PleadColor.mahogany, s.ribbonColor)
        }
        for (s in listOf(CaseStatus.closed, CaseStatus.closedGuilty, CaseStatus.closedDefault, CaseStatus.closedSettled)) {
            assertEquals(PleadColor.walnut, s.ribbonColor)
        }
        assertEquals(PleadColor.subtleText, CaseStatus.mistrial.ribbonColor)
    }

    @Test fun exhibitCopy() {
        assertEquals("voice note", ExhibitTileCopy.typeName(ExhibitType.voice))
        assertEquals("Text Quote", capitalized(ExhibitTileCopy.typeName(ExhibitType.text)))
        assertEquals(
            "Exhibit A, receipt. The promise. said he'd be home. Objection sustained",
            ExhibitTileCopy.accessibilityLabel(ExhibitLabel("A"), "receipt", "The promise", "said he'd be home", null, ObjectionRuling.sustained),
        )
    }

    @Test fun buttonStyleColours() {
        assertEquals(PleadColor.cream, AWButtonStyle.aw(AWButtonKind.primary).foreground)
        assertEquals(PleadColor.burgundy, AWButtonStyle.aw(AWButtonKind.primary).background)
        assertEquals(PleadColor.burgundy, AWButtonStyle.aw(AWButtonKind.secondary).foreground)
        assertEquals(PleadColor.paperWhite, AWButtonStyle.aw(AWButtonKind.secondary).background)
        assertEquals(PleadColor.mahogany, AWButtonStyle.aw(AWButtonKind.onDark).foreground)
        assertEquals(PleadColor.cream, AWButtonStyle.aw(AWButtonKind.onDark).background)
    }

    @Test fun everySFSymbolTheAppUsesIsMapped() {
        val used = listOf(
            "arrow.right", "exclamationmark.circle.fill", "receipt", "quote.opening", "iphone", "photo", "hourglass",
            "calendar", "scalemass", "checkmark.seal", "hands.and.sparkles", "stop.circle", "heart.fill",
        )
        for (name in used) assertEquals("$name is mapped", true, SFSymbol.map.containsKey(name))
    }
}
