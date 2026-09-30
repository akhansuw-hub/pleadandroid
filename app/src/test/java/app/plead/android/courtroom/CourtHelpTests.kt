// Port of ArgueWinTests/CourtHelpTests.swift: phase help sheets (CONTRACTS-v2 amendment ae, motion brief §19): the exact
// opening-statement copy (live and mock), every topic's shape, which topic the live dock offers per phase / state, and
// that opening or closing help never touches the draft or the deadline.
package app.plead.android.courtroom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourtHelpTests {

    // MARK: Copy

    @Test fun liveOpeningStatementCopyIsExact() {
        val c = CourtHelp.content(CourtHelpTopic.openingStatement)
        assertEquals("What's an opening statement?", c.title)
        assertEquals(
            "It's your first chance to explain your side before the court looks at evidence. In a few sentences, say what happened, why it matters to you, and what you'd like the judge to decide. You can show proof later.",
            c.body,
        )
        assertEquals("Alex promised to save the last slice, but ate it. I'd like a replacement pizza.", c.example)
        assertEquals("Got it", c.button)
        assertEquals("What is an opening statement?", c.accessibilityQuestion)
    }

    @Test fun mockOpeningStatementCopyIsExact() {
        val c = CourtHelp.content(CourtHelpTopic.mockOpeningStatement)
        assertEquals("What's an opening statement?", c.title)
        assertEquals(
            "This is where a side briefly tells the judge what happened and what they want. Sam goes first in this demo. Just watch how the trial unfolds.",
            c.body,
        )
        assertNull(c.example)
        assertEquals("Got it", c.button)
        assertEquals("What is an opening statement?", c.accessibilityQuestion)
    }

    @Test fun everyTopicHasTitleBodyButtonAndAQuestion() {
        for (topic in CourtHelpTopic.entries) {
            val c = CourtHelp.content(topic)
            assertTrue(c.title.isNotEmpty() && c.title.endsWith("?"))
            assertTrue(c.body.isNotEmpty())
            assertEquals("Got it", c.button)
            assertTrue(c.accessibilityQuestion.startsWith("What is"))
            // Short: about three sentences, one example at most, plain words (no emoji, no product-name slips).
            val sentences = c.body.split('.', '?', '!').filter { it.trim().isNotEmpty() }
            assertTrue(sentences.size <= 4)
            for (text in listOf(c.title, c.body, c.example ?: "")) {
                assertFalse(text.contains("arguewin", ignoreCase = true))
                assertFalse(text.contains("premium", ignoreCase = true))
                assertFalse(text.codePoints().anyMatch { it > 0x238C && (Character.getType(it) == Character.OTHER_SYMBOL.toInt()) })
            }
            // Live topics carry one example; the mock one explains instead of asking the viewer to type.
            assertEquals(topic != CourtHelpTopic.mockOpeningStatement, c.example != null)
        }
    }

    @Test fun infoButtonIsAtLeast44Points() {
        assertTrue(CourtHelpButton.tapTarget >= 44f)
    }

    @Test fun topicsKeepTheirIdentifiersAndRawValues() {
        assertEquals(
            listOf("openingStatement", "mockOpeningStatement", "evidence", "crossExamination", "objection", "verdict"),
            CourtHelpTopic.entries.map { it.rawValue },
        )
        assertEquals("evidence", CourtHelpTopic.evidence.id)
    }

    // MARK: Where the dock offers help

    @Test fun dockOffersTheTopicForWhatICanDoNow() {
        assertEquals(CourtHelpTopic.openingStatement, CourtHelp.dockTopic(CourtFixtures.openingMyTurn))
        assertEquals(CourtHelpTopic.evidence, CourtHelp.dockTopic(CourtFixtures.presentExhibits))
        assertEquals(CourtHelpTopic.evidence, CourtHelp.dockTopic(CourtFixtures.presenting))
        assertEquals(CourtHelpTopic.crossExamination, CourtHelp.dockTopic(CourtFixtures.crossExam))
        assertEquals(CourtHelpTopic.objection, CourtHelp.dockTopic(CourtFixtures.objectionWindow))
        assertEquals(CourtHelpTopic.verdict, CourtHelp.dockTopic(CourtFixtures.deliberating))
        assertEquals(CourtHelpTopic.verdict, CourtHelp.dockTopic(CourtFixtures.awaitingVerdict))
        assertEquals(CourtHelpTopic.verdict, CourtHelp.dockTopic(CourtFixtures.verdictIn))
    }

    @Test fun noHelpWhenThereIsNothingToExplainRightNow() {
        // Waiting for the partner, the judge has the floor, closings, a stopped case, the gallery.
        assertNull(CourtHelp.dockTopic(CourtFixtures.waitingForPartner))
        assertNull(CourtHelp.dockTopic(CourtFixtures.closing))
        assertNull(CourtHelp.dockTopic(CourtFixtures.defenceRests))
        assertNull(CourtHelp.dockTopic(CourtFixtures.safetyNotice))
        val gallery = CourtFixtures.openingMyTurn.copy(myRole = null)
        assertNull(CourtHelp.dockTopic(gallery))
        // A judgement step or a pending settlement takes over the dock: no phase help there.
        assertNull(CourtHelp.dockTopic(CourtFixtures.judgementPending))
        assertNull(CourtHelp.dockTopic(CourtFixtures.settlementPending))
        // Pure mapping.
        assertNull(CourtHelp.dockTopic(mode = DockMode.compose(ComposeKind.closing), judgement = null, settlement = null))
        assertNull(CourtHelp.dockTopic(mode = DockMode.stopped, judgement = null, settlement = null))
        assertNull(CourtHelp.dockTopic(mode = DockMode.verdictIn, judgement = JudgementDockMode.choose(tie = false), settlement = null))
    }

    @Test fun noHelpWhileTheCaseIsBeingCalled() {
        // Amendment ad: the first turn (and its help) appears only once the judge hands over the floor.
        assertNull(CourtHelp.dockTopic(CourtFixtures.openingMyTurn, callingCase = true))
        assertNull(CourtHelp.dockTopic(CourtFixtures.presentExhibits, callingCase = true))
        assertEquals(CourtHelpTopic.openingStatement, CourtHelp.dockTopic(CourtFixtures.openingMyTurn, callingCase = false))
        // Deliberation is not gated by the call.
        assertEquals(CourtHelpTopic.verdict, CourtHelp.dockTopic(CourtFixtures.deliberating, callingCase = true))
    }

    // MARK: Presentation leaves the court alone

    @Test fun helpNeverOpensByItself() {
        assertNull(CourtHelpPresentation().topic)
    }

    @Test fun opensOnlyTheOfferedTopic() {
        val p = CourtHelpPresentation()
        assertFalse(p.open(CourtHelpTopic.evidence, offered = CourtHelpTopic.openingStatement))
        assertNull(p.topic)
        assertTrue(p.open(CourtHelpTopic.openingStatement, offered = CourtHelpTopic.openingStatement))
        assertEquals(CourtHelpTopic.openingStatement, p.topic)
        p.dismiss()
        assertNull(p.topic)
    }

    @Test fun draftAndDeadlineSurviveOpeningAndClosingHelp() {
        val state = CourtFixtures.openingMyTurn
        val deadlineBefore = CourtroomLogic.countdownTarget(state)
        val modeBefore = CourtroomLogic.dockMode(state)
        // The dock's local state around a help round trip: the draft is a sibling of the presentation, never part of it.
        var draft = "Alex promised to save the last slice"
        val help = CourtHelpPresentation()
        help.open(CourtHelpTopic.openingStatement, offered = CourtHelp.dockTopic(state))
        assertEquals(CourtHelpTopic.openingStatement, help.topic)
        assertEquals("Alex promised to save the last slice", draft)
        draft += ", but ate it."
        help.dismiss()
        assertEquals("Alex promised to save the last slice, but ate it.", draft)
        // The deadline the sheet shows is the dock's own countdown target, and nothing about the case changed.
        assertEquals(deadlineBefore, CourtHelp.deadline(state))
        assertNotNull(deadlineBefore)
        assertEquals(deadlineBefore, CourtroomLogic.countdownTarget(state))
        assertEquals(modeBefore, CourtroomLogic.dockMode(state))
        assertEquals(CourtHelpTopic.openingStatement, CourtHelp.dockTopic(state))
    }

    @Test fun sheetDeadlineMatchesTheDockCountdown() {
        assertEquals(CourtFixtures.crossExam.kase.deadlineAt, CourtHelp.deadline(CourtFixtures.crossExam))
        assertEquals(CourtroomLogic.countdownTarget(CourtFixtures.deliberating), CourtHelp.deadline(CourtFixtures.deliberating))
        assertNull(CourtHelp.deadline(CourtFixtures.safetyNotice))
        assertNull(CourtHelp.deadline(CourtFixtures.settlementPending))
    }
}
