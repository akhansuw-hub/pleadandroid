// Android-only coverage owed by docs/STATUS.md ("courtroom flows"): the Court tab renders every `AWCourtFixture` stage
// (CourtFixtureStage, amendment x) through the real tab shell without crashing, and the dock offers the primary control
// of that fixture's dock mode (computed with the same pure logic the dock uses, so the expectation follows the fixture):
//
//   compose(opening / cross answer / closing) → the composer (`court.compose`) and Submit (`court.submit`)
//   compose(present exhibits)                 → Show (`court.show`), or Rest (`court.rest`) with nothing left
//   objection window                          → Let it stand (`court.letItStand`) and Object (`court.object`)
//   deliberating                              → THE COURT IS DELIBERATING overlay
//   verdict in                                → the verdict sequence's dialog on ALL RISE with Continue
//   waiting / judge has the floor             → the dock header (no action is offered)
//
// The entrance and the case call play first on a fresh install (as the iOS opening test allows for), hence 40 s.
package app.plead.android.features.court

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.plead.android.courtroom.ComposeKind
import app.plead.android.courtroom.CourtroomLogic
import app.plead.android.courtroom.DockMode
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourtFixtureComposeTests {
    @get:Rule val rule = createComposeRule()
    private val app = PleadComposeApp(rule)

    @After fun tearDown() = app.tearDown()

    @Test fun fixtureOpeningShowsTheComposer() = assertFixture("opening")
    @Test fun fixtureEvidenceShowsItsDockControl() = assertFixture("evidence")
    @Test fun fixtureCrossShowsTheComposer() = assertFixture("cross")
    @Test fun fixtureRulingShowsItsDockControl() = assertFixture("ruling")
    /** `objection` is in DemoHarness's flag list; like iOS, any other name plays the replay. */
    @Test fun fixtureObjectionFallsBackToTheReplay() = assertFixture("objection")
    @Test fun fixtureDeliberationShowsTheOverlay() = assertFixture("deliberation")
    @Test fun fixtureVerdictOpensTheSequence() = assertFixture("verdict")
    @Test fun fixtureReplayRendersAndAdvances() = assertFixture("replay")

    /** Mounts the fixture, waits for its primary control, then lets the scene run a little and checks it is still up. */
    private fun assertFixture(name: String) {
        app.launch("AWTab" to "court", "AWCourtFixture" to name)
        val state = CourtFixtureStage.state(name, CourtFixtureStage.replayFrom)
        val mode = CourtroomLogic.dockMode(state)
        val expected = primaryControls(mode)
        for ((what, matcher) in expected) {
            assertTrue("Fixture $name ($mode): $what never appeared", app.waitForExistence(matcher, 40_000))
        }
        // Still rendering after the entrance / ambient motion has had a few seconds (no crash, nothing torn down).
        app.pause(3_000)
        val (what, matcher) = expected.first()
        // The replay moves on to the next turn every 1.6 s, so only its stage (the dock header) must remain.
        val still = if (name == "replay" || name == "objection") app.element("court.dock.header") else matcher
        assertTrue("Fixture $name: ${if (still === matcher) what else "the dock"} went away", app.waitForExistence(still, 5_000))
    }

    private fun primaryControls(mode: DockMode): List<Pair<String, SemanticsMatcher>> = when (mode) {
        is DockMode.compose -> when (val kind = mode.kind) {
            is ComposeKind.presentExhibits ->
                if (kind.available.isNotEmpty()) listOf("Show" to app.tag("court.show")) else listOf("Rest" to app.tag("court.rest"))
            else -> listOf("the composer" to app.tag("court.compose"), "Submit" to app.tag("court.submit"))
        }
        is DockMode.objectionWindow -> listOf("Let it stand" to app.tag("court.letItStand"), "Object" to app.tag("court.object"))
        DockMode.deliberating -> listOf("the deliberation overlay" to app.containing("THE COURT IS DELIBERATING"))
        DockMode.verdictIn -> listOf(
            "ALL RISE" to (app.element("ALL RISE") and hasAnyAncestor(isDialog())),
            "Continue" to (app.element("Continue") and PleadComposeApp.clickable and hasAnyAncestor(isDialog())),
        )
        else -> listOf("the dock header" to app.element("court.dock.header"))
    }
}
