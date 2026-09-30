// Port of ArgueWinTests/ColdOpenCoordinatorTests.swift.
package app.plead.android.features.coldopen

import androidx.compose.ui.geometry.Offset
import app.plead.android.services.MainDispatcherRule
import app.plead.android.services.UserDefaults
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** CONTRACTS-v2 amendment h: when the cold open plays, and that it always completes. */
@OptIn(ExperimentalCoroutinesApi::class)
class ColdOpenCoordinatorTests {
    @get:Rule val main = MainDispatcherRule()

    private fun freshState(): AppLaunchState = AppLaunchState(UserDefaults.inMemory())

    private fun input(
        launch: ColdOpenCoordinator.LaunchKind = ColdOpenCoordinator.LaunchKind.cold,
        hasSeenColdOpen: Boolean = false,
        reduceMotion: Boolean = false,
        forced: ColdOpenCoordinator.Mode? = null,
        demo: Boolean = false,
    ) = ColdOpenCoordinator.Input(launch, hasSeenColdOpen, reduceMotion, forced, demo)

    private fun decide(i: ColdOpenCoordinator.Input) = ColdOpenCoordinator.decide(i)

    private fun close(a: Double, b: Double, eps: Double = 0.0001) = abs(a - b) < eps

    // MARK: Decision table

    @Test fun firstLaunchPlaysFull() {
        assertEquals(ColdOpenCoordinator.Mode.full, decide(input(hasSeenColdOpen = false)))
    }

    @Test fun repeatLaunchPlaysSting() {
        assertEquals(ColdOpenCoordinator.Mode.sting, decide(input(hasSeenColdOpen = true)))
    }

    @Test fun reduceMotionFirstLaunchStillPlaysTheReducedFull() {
        assertEquals(ColdOpenCoordinator.Mode.full, decide(input(hasSeenColdOpen = false, reduceMotion = true)))
    }

    @Test fun reduceMotionOnceSeenSkips() {
        assertEquals(ColdOpenCoordinator.Mode.none, decide(input(hasSeenColdOpen = true, reduceMotion = true)))
    }

    @Test fun backgroundReturnNeverPlays() {
        assertEquals(ColdOpenCoordinator.Mode.none, decide(input(launch = ColdOpenCoordinator.LaunchKind.resume, hasSeenColdOpen = false)))
        assertEquals(ColdOpenCoordinator.Mode.none, decide(input(launch = ColdOpenCoordinator.LaunchKind.resume, hasSeenColdOpen = true)))
    }

    @Test fun demoRunsSkipUnlessForced() {
        assertEquals(ColdOpenCoordinator.Mode.none, decide(input(hasSeenColdOpen = false, demo = true)))
        assertEquals(ColdOpenCoordinator.Mode.full, decide(input(hasSeenColdOpen = true, forced = ColdOpenCoordinator.Mode.full, demo = true)))
    }

    @Test fun forcedFlagWins() {
        for (forced in ColdOpenCoordinator.Mode.entries) {
            for (seen in listOf(false, true)) {
                for (reduce in listOf(false, true)) {
                    for (launch in ColdOpenCoordinator.LaunchKind.entries) {
                        val i = input(launch = launch, hasSeenColdOpen = seen, reduceMotion = reduce, forced = forced)
                        assertEquals(forced, decide(i))
                    }
                }
            }
        }
    }

    // MARK: Persistence

    @Test fun firstLaunchThenRepeat() {
        val state = freshState()
        val c = ColdOpenCoordinator(launchState = state)
        assertEquals(ColdOpenCoordinator.Mode.full, c.launch(reduceMotion = false, preload = false))
        assertTrue(c.isPlaying)
        assertNotNull(state.lastLaunchDate)
        c.complete()
        assertFalse(c.isPlaying)
        assertTrue(state.hasSeenColdOpen)
        // Next cold launch.
        val next = ColdOpenCoordinator(launchState = state)
        assertEquals(ColdOpenCoordinator.Mode.sting, next.launch(reduceMotion = false, preload = false))
        val rm = ColdOpenCoordinator(launchState = state)
        assertEquals(ColdOpenCoordinator.Mode.none, rm.launch(reduceMotion = true, preload = false))
        assertFalse(rm.isPlaying)
    }

    @Test fun forcedFullReplaysEvenIfSeen() {
        val state = freshState()
        state.hasSeenColdOpen = true
        val c = ColdOpenCoordinator(launchState = state)
        assertEquals(ColdOpenCoordinator.Mode.full, c.launch(forced = ColdOpenCoordinator.Mode.full, reduceMotion = false, preload = false))
        assertTrue(c.isPlaying)
    }

    @Test fun forcedNoneNeverPlays() {
        val c = ColdOpenCoordinator(launchState = freshState())
        assertEquals(ColdOpenCoordinator.Mode.none, c.launch(forced = ColdOpenCoordinator.Mode.none, reduceMotion = false, preload = false))
        assertFalse(c.isPlaying)
    }

    // MARK: Completion

    @Test fun completionIsIdempotentAndFirstWins() {
        val c = ColdOpenCoordinator(launchState = freshState())
        c.begin(ColdOpenCoordinator.Mode.full, reduceMotion = false, preload = false)
        c.skip()
        c.complete(ColdOpenCoordinator.Completion.finished)
        assertEquals(ColdOpenCoordinator.Completion.skipped, c.completion)
        assertFalse(c.isPlaying)
    }

    @Test fun hardTimeoutAlwaysCompletes() = runTest {
        val c = ColdOpenCoordinator(launchState = freshState(), hardTimeout = 50.milliseconds, scope = this)
        c.begin(ColdOpenCoordinator.Mode.full, reduceMotion = false, preload = false)
        assertTrue(c.isPlaying)
        advanceTimeBy(300)
        runCurrent()
        assertFalse(c.isPlaying)
        assertEquals(ColdOpenCoordinator.Completion.timedOut, c.completion)
        assertTrue(c.launchState.hasSeenColdOpen)
    }

    @Test fun defaultHardTimeoutIsEightSeconds() {
        assertEquals(8.seconds, ColdOpenCoordinator.defaultHardTimeout)
        assertTrue(ColdOpenTimeline.duration(ColdOpenCoordinator.Mode.full, reduceMotion = false) < 8)
        assertTrue(ColdOpenTimeline.duration(ColdOpenCoordinator.Mode.full, reduceMotion = true) < 8)
    }

    @Test fun resumingFromBackgroundEndsAStalePlayback() {
        val c = ColdOpenCoordinator(launchState = freshState())
        c.begin(ColdOpenCoordinator.Mode.sting, reduceMotion = false, preload = false)
        c.resumedFromBackground()
        assertFalse(c.isPlaying)
    }

    // MARK: Timeline

    @Test fun timelineMatchesTheBrief() {
        val t = ColdOpenTimeline
        val f = ColdOpenTimeline.Full
        assertTrue(close(f.total, 5.8))
        assertTrue(close(ColdOpenTimeline.Sting.total, 1.2))
        assertTrue(close(ColdOpenTimeline.Reduced.total, 4.5))
        assertEquals(0.5, t.crossfade, 0.0)
        assertEquals(1.0, t.skipAllowedAfter, 0.0)
        // 1 · Courthouse 0–1.3, push-in 1.0 → 1.05.
        assertEquals(1.0, t.courthouseScale(0.0), 0.0)
        assertTrue(close(t.courthouseScale(1.3), 1.05))
        // 2 · Doors part over ~1.0 s, then a 0.3 s hold before the judge.
        assertTrue(close(f.doorsPart.endInclusive - f.doorsPart.start, 1.0))
        assertTrue(t.doorsOpen(f.doorsPart.start) == 0.0 && t.doorsOpen(f.doorsPart.endInclusive) == 1.0)
        assertTrue(abs((f.doorsHoldEnd - f.doorsPart.endInclusive) - 0.35) < 0.06)
        assertEquals(0.0, t.bloom(1.0), 0.0)
        assertTrue(t.bloom(2.0) <= 0.25 && t.bloom(2.0) > 0.2)
        // 3 · Judge push-in 2.8–3.8, holds to 4.0.
        assertEquals(1.0, t.judgeScale(2.8), 0.0)
        assertTrue(close(t.judgeScale(3.8), 1.12))
        assertTrue(close(t.judgeScale(3.99), 1.12))
        // 4 · Gavel: hard cut at 4.0, 8 fps raise/mid, impact held 250 ms, settle, rest.
        assertNull(t.gavelFrame(3.99))
        assertEquals(0, t.gavelFrame(4.0))
        assertEquals(1, t.gavelFrame(4.13))
        assertTrue(t.gavelFrame(4.25) == 2 && t.gavelFrame(4.49) == 2)
        assertTrue(close(f.impact, 4.25))
        assertEquals(3, t.gavelFrame(4.55))
        assertEquals(4, t.gavelFrame(4.9))
        // 5 · End card 5.0–5.8: logo 0.94 → 1.0 + fade over 0.5 s, then a 0.3 s hold.
        val logoStart = t.logoReveal(5.0, f.logoIn)
        assertTrue(close(logoStart.scale, 0.94) && logoStart.opacity == 0.0)
        val logoEnd = t.logoReveal(5.5, f.logoIn)
        assertTrue(logoEnd.scale == 1.0 && logoEnd.opacity == 1.0)
        assertTrue(close(f.total - f.logoIn.endInclusive, 0.3))
    }

    @Test fun reducedMotionCrossfadesEveryOneAndAHalfSeconds() {
        val r = ColdOpenTimeline.Reduced
        assertTrue(close((r.judgeIn.start + r.judgeIn.endInclusive) / 2, 1.5))
        assertTrue(close((r.endcardIn.start + r.endcardIn.endInclusive) / 2, 3.0))
    }

    @Test fun shakeStaysWithinFourPointsAndStops() {
        val impact = ColdOpenTimeline.Full.impact
        val d = ColdOpenTimeline.Full.shakeDuration
        var t = impact - 0.05
        while (t < impact + 0.3) {
            val s = ColdOpenTimeline.shake(t)
            assertTrue(sqrt(s.x * s.x + s.y * s.y) <= 4f)
            if (t < impact || t >= impact + d) assertEquals(Offset.Zero, s)
            t += 0.004
        }
    }

    @Test fun assetsListCoversEachMode() {
        assertTrue(ColdOpenAssets.names(ColdOpenCoordinator.Mode.none, reduceMotion = false).isEmpty())
        assertTrue(ColdOpenAssets.names(ColdOpenCoordinator.Mode.sting, reduceMotion = false).contains(ColdOpenAssets.endcard))
        val full = ColdOpenAssets.names(ColdOpenCoordinator.Mode.full, reduceMotion = false)
        assertTrue(full.contains(ColdOpenAssets.doorLeft) && full.contains("frame4_gavel_5"))
        assertFalse(ColdOpenAssets.names(ColdOpenCoordinator.Mode.full, reduceMotion = true).contains("frame4_gavel_1"))
    }
}
