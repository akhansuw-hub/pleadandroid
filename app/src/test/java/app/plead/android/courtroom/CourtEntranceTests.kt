// Port of ArgueWinTests/CourtEntranceTests.swift: the shared court entrance (CONTRACTS-v2 amendment ac, motion brief
// §17): the timeline and its windows on a fake clock, missing parties, finish / skip / restore, late walk-ins, Reduce
// Motion, cancellation, the gavel.
package app.plead.android.courtroom

import androidx.compose.ui.geometry.Offset
import app.plead.android.models.JudgePersona
import app.plead.android.models.Role
import app.plead.android.services.PreviewData
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CourtEntranceTests {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    data class Snap(
        val t: Double,
        val phase: CourtEntrancePhase,
        val revealed: Boolean,
        val label: Boolean,
        val judge: CourtEntrancePose,
        val plaintiff: CourtEntrancePose,
        val defendant: CourtEntrancePose,
        val audience: Double,
        val gavel: Int,
    )

    /** Sleeps return at once (after a yield); the director reports each keyframe's time. */
    private fun director(plaintiff: Boolean = true, defendant: Boolean = true, reduceMotion: Boolean = false, suspend: Boolean = false) =
        CourtEntranceDirector(hasPlaintiff = plaintiff, hasDefendant = defendant, reduceMotion = reduceMotion, sleep = {
            if (suspend) awaitCancellation()
            yield()
        })

    private fun record(d: CourtEntranceDirector): () -> List<Snap> {
        val snaps = mutableListOf<Snap>()
        d.onKeyframe = { t ->
            snaps.add(Snap(t, d.phase, d.roomRevealed, d.caseLabelVisible, d.judge, d.plaintiff, d.defendant, d.audienceSettle, d.gavelTaps))
        }
        return { snaps.toList() }
    }

    // MARK: Timeline

    @Test fun timelineRunsInOrderInsideItsWindows() = runTest(dispatcher) {
        val d = director()
        var ready = 0
        d.onReady = { ready += 1 }
        val snaps = record(d)
        assertEquals(CourtEntrancePhase.preparing, d.phase)
        d.start()
        assertTrue(d.phase == CourtEntrancePhase.entering && !d.roomRevealed)
        d.join()
        val s = snaps()
        assertTrue(s.isNotEmpty())

        // 0–250 ms: the room and the case label first.
        assertTrue(s.first().t == 0.0 && s.first().revealed && s.first().label)

        // 250–950 ms: both parties walk in from their sides, then stand.
        for ((role, sign) in listOf(Role.plaintiff to -1f, Role.defendant to 1f)) {
            val poses = s.map { it.t to if (role == Role.plaintiff) it.plaintiff else it.defendant }
            for ((t, p) in poses) if (t < CourtEntranceTiming.partiesStart - 0.001) assertFalse("$role visible at $t", p.visible)
            val walking = poses.filter { it.second.walkFrame > 0 }
            assertTrue("$role walk frames", walking.size >= 4)
            for ((t, p) in walking) {
                assertTrue("$role walks at $t", t >= CourtEntranceTiming.partiesStart - 0.001 && t < CourtEntranceTiming.partiesEnd)
                assertTrue(p.visible && p.offset.x * sign >= 0 && p.offset.y == 0f)
                assertTrue(p.walkFrame in 1..CourtEntrancePose.walkFrames)
            }
            assertTrue("the walk cycles", walking.map { it.second.walkFrame }.toSet().size >= 3)
            assertEquals(CourtEntrancePose.offstageDistance, abs(walking.first().second.offset.x), 0.0001f)
            // Monotonic approach to the podium.
            val xs = walking.map { abs(it.second.offset.x) }
            assertTrue(xs.zipWithNext().all { (a, b) -> a >= b })
            for ((t, p) in poses) if (t >= CourtEntranceTiming.partiesEnd - 0.001) assertEquals("$role standing at $t", CourtEntrancePose.standing, p)
        }

        // 950–1600 ms: audience settles, judge walks to the bench.
        for (x in s) if (x.t < CourtEntranceTiming.judgeStart - 0.001) {
            assertTrue("judge / audience early at ${x.t}", x.audience == 0.0 && !x.judge.visible)
        }
        val audience = s.map { it.audience }
        assertTrue(audience.zipWithNext().all { (a, b) -> a <= b })
        val judgeWalk = s.filter { it.judge.walkFrame > 0 }
        assertTrue(judgeWalk.size >= 3)
        for (x in judgeWalk) {
            assertTrue(x.t >= CourtEntranceTiming.judgeStart - 0.001 && x.t < CourtEntranceTiming.judgeEnd)
            assertTrue(x.judge.offset.x >= 0 && x.judge.offset.x <= CourtEntrancePose.judgeApproach)
        }
        for (x in s) if (x.t >= CourtEntranceTiming.judgeEnd - 0.001) assertTrue(x.judge == CourtEntrancePose.standing && x.audience == 1.0)

        // 1600–2200 ms: one gavel tap after the judge is in place, then ready.
        val tap = s.firstOrNull { it.gavel == 1 }
        assertNotNull(tap)
        assertTrue(tap!!.t >= CourtEntranceTiming.judgeEnd && tap.t < CourtEntranceTiming.ready)
        assertEquals(CourtEntrancePose.standing, tap.judge)
        assertEquals(CourtEntranceTiming.ready, s.first { it.phase == CourtEntrancePhase.ready }.t, 1e-9)
        assertEquals(CourtEntranceDirector.totalDuration, s.last().t, 1e-9)
        assertTrue(d.phase == CourtEntrancePhase.ready && d.gavelTaps == 1 && ready == 1)
        assertFalse(d.isRunning)
    }

    @Test fun missingPlaintiffNeverEnters() = runTest(dispatcher) {
        val d = director(plaintiff = false)
        val snaps = record(d)
        d.start()
        d.join()
        for (x in snaps()) {
            assertTrue(!x.plaintiff.visible && x.plaintiff.walkFrame == 0 && x.plaintiff.offset == Offset.Zero)
        }
        assertTrue(snaps().any { it.defendant.walkFrame > 0 })
        assertTrue(d.plaintiff == CourtEntrancePose.hidden && d.defendant == CourtEntrancePose.standing && d.judge == CourtEntrancePose.standing)
        // Snapping never brings the empty podium's party in either.
        d.finish()
        assertEquals(CourtEntrancePose.hidden, d.plaintiff)
        d.restoreOccupied()
        assertTrue(d.plaintiff == CourtEntrancePose.hidden && !d.isPresent(Role.plaintiff))
    }

    // MARK: finish / skip / restore

    @Test fun finishSnapsAndReportsReadyOnce() = runTest(dispatcher) {
        val d = director(suspend = true)
        var ready = 0
        d.onReady = { ready += 1 }
        d.start()
        assertEquals(CourtEntrancePhase.entering, d.phase)
        d.finish()
        assertTrue(d.phase == CourtEntrancePhase.ready && ready == 1)
        assertTrue(d.judge == CourtEntrancePose.standing && d.plaintiff == CourtEntrancePose.standing && d.defendant == CourtEntrancePose.standing)
        assertTrue(d.roomRevealed && d.caseLabelVisible && d.audienceSettle == 1.0)
        assertEquals(0, d.gavelTaps)
        d.finish()
        assertEquals(1, ready)
        d.join()
        assertFalse(d.isRunning)
    }

    @Test fun skipSnapsWithoutReady() = runTest(dispatcher) {
        val d = director(suspend = true)
        var ready = 0
        d.onReady = { ready += 1 }
        d.start()
        d.skip()
        assertTrue(d.phase == CourtEntrancePhase.skipped && ready == 0)
        assertTrue(d.judge == CourtEntrancePose.standing && d.plaintiff == CourtEntrancePose.standing && d.defendant == CourtEntrancePose.standing && d.audienceSettle == 1.0)
        d.join()
        assertTrue(ready == 0 && d.gavelTaps == 0)
    }

    @Test fun restoreIsInstantWithoutGavel() {
        val d = director(defendant = false)
        var ready = 0
        d.onReady = { ready += 1 }
        d.restoreOccupied()
        assertTrue(d.phase == CourtEntrancePhase.ready && ready == 1)
        assertTrue(d.roomRevealed && d.caseLabelVisible && d.audienceSettle == 1.0)
        assertTrue(d.judge == CourtEntrancePose.standing && d.plaintiff == CourtEntrancePose.standing && d.defendant == CourtEntrancePose.hidden)
        assertTrue(d.gavelTaps == 0 && !d.isRunning)
        d.restoreOccupied()
        assertEquals(1, ready)
    }

    // MARK: Late arrival

    @Test fun walkInTouchesOnlyThatParty() = runTest(dispatcher) {
        val d = director(defendant = false)
        d.restoreOccupied()
        val snaps = record(d)
        d.walkIn(plaintiff = false)
        d.join()
        val s = snaps()
        assertEquals(CourtEntranceTiming.walkIn, s.last().t, 1e-9)
        assertTrue(s.any { it.defendant.walkFrame > 0 && it.defendant.offset.x > 0 })
        for (x in s) {
            assertTrue(x.judge == CourtEntrancePose.standing && x.plaintiff == CourtEntrancePose.standing && x.audience == 1.0 && x.gavel == 0 && x.phase == CourtEntrancePhase.ready)
            assertTrue(x.revealed && x.label)
        }
        assertEquals(CourtEntrancePose.offstageDistance, abs(s.first().defendant.offset.x), 0.0001f)
        assertTrue(d.defendant == CourtEntrancePose.standing && d.isPresent(Role.defendant))
        // Already in the room: a second walk-in is ignored.
        val before = s.size
        d.walkIn(plaintiff = false)
        d.join()
        assertEquals(before, snaps().size)
    }

    // MARK: Reduce Motion

    @Test fun reduceMotionFadesWithoutWalkingOrOffsets() = runTest(dispatcher) {
        val d = director(reduceMotion = true)
        var ready = 0
        d.onReady = { ready += 1 }
        val snaps = record(d)
        d.start()
        // Occupied positions, faded out, before the first keyframe (opacity only).
        for (p in listOf(d.judge, d.plaintiff, d.defendant)) assertTrue(p.offset == Offset.Zero && p.walkFrame == 0 && p.opacity == 0.0)
        d.join()
        for (x in snaps()) for (p in listOf(x.judge, x.plaintiff, x.defendant)) assertTrue(p.offset == Offset.Zero && p.walkFrame == 0)
        assertEquals(CourtEntrancePose.standing, snaps().first().judge)
        assertTrue(d.gavelTaps == 1 && d.phase == CourtEntrancePhase.ready && ready == 1)
        assertTrue((snaps().lastOrNull()?.t ?: 9.0) <= CourtEntranceTiming.reduceMotionFade + 0.001)
    }

    // MARK: Cancellation & the gavel

    @Test fun stopCancels() = runTest(dispatcher) {
        val d = director(suspend = true)
        var ready = 0
        d.onReady = { ready += 1 }
        d.start()
        assertTrue(d.isRunning)
        d.stop()
        d.join()
        assertTrue(!d.isRunning && d.phase == CourtEntrancePhase.entering && ready == 0 && d.gavelTaps == 0)
    }

    @Test fun oneGavelTapPerRun() = runTest(dispatcher) {
        val d = director()
        d.start()
        d.join()
        assertEquals(1, d.gavelTaps)
        d.start()
        d.join()
        assertEquals(2, d.gavelTaps)
        d.restoreOccupied()
        d.finish()
        assertEquals(2, d.gavelTaps)
    }

    @Test fun seekFreezesTheSequence() {
        val d = director()
        d.seek(0.6)
        assertTrue(d.phase == CourtEntrancePhase.entering && !d.isRunning)
        assertTrue(d.plaintiff.walkFrame > 0 && d.plaintiff.offset.x < 0)
        assertTrue(d.defendant.walkFrame > 0 && d.defendant.offset.x > 0)
        assertTrue(!d.judge.visible && d.audienceSettle == 0.0)
        d.seek(1.3)
        assertTrue(d.plaintiff == CourtEntrancePose.standing && d.judge.walkFrame > 0 && d.audienceSettle > 0 && d.audienceSettle < 1)
        d.seek(0.1)
        assertTrue(d.roomRevealed && !d.plaintiff.visible && !d.judge.visible)
    }

    // MARK: Walk frames

    @Test fun judgeWalkFramesSwingTheHemOnly() {
        for (p in JudgePersona.entries) {
            val base = JudgeSprite.rows(p, eyesClosed = false, mouthOpen = false)
            assertEquals(base, JudgeSprite.rows(p, eyesClosed = false, mouthOpen = false, walkFrame = 2))
            assertEquals(base, JudgeSprite.rows(p, eyesClosed = false, mouthOpen = false, walkFrame = 4))
            for (f in listOf(1, 3)) {
                val w = JudgeSprite.rows(p, eyesClosed = false, mouthOpen = false, walkFrame = f)
                assertEquals(base.size, w.size)
                for (r in base.indices) if (r < base.size - 2) assertEquals("$p frame $f row $r", base[r], w[r])
                assertTrue(w[base.size - 1] != base[base.size - 1])
            }
            val left = JudgeSprite.rows(p, eyesClosed = false, mouthOpen = false, walkFrame = 1)
            val right = JudgeSprite.rows(p, eyesClosed = false, mouthOpen = false, walkFrame = 3)
            assertTrue(left.last() != right.last())
        }
        assertTrue(CourtWalkCycle.bob(0) == 0 && CourtWalkCycle.bob(1) == 0 && CourtWalkCycle.bob(2) == 1 &&
            CourtWalkCycle.bob(3) == 0 && CourtWalkCycle.bob(4) == 1)
        assertTrue(CourtWalkCycle.normalized(5) == 1 && CourtWalkCycle.normalized(0) == 0)
    }

    @Test fun avatarWalkFramesSwingTheHemOnly() {
        for (a in listOf(PreviewData.me.avatar, PreviewData.partner.avatar, CourtFixtures.aria.avatar, CourtFixtures.sam.avatar)) {
            val base = CourtAvatarSprite.grid(a, eyesClosed = false, mouthOpen = false)
            for (f in 1..4) {
                val w = CourtAvatarSprite.grid(a, eyesClosed = false, mouthOpen = false, walkFrame = f)
                val rows = mutableSetOf<Int>()
                for (r in base.indices) for (c in base[r].indices) if (base[r][c] != w[r][c]) rows.add(r)
                if (f == 1 || f == 3) assertTrue("frame $f: $rows", rows.isNotEmpty() && setOf(14, 15).containsAll(rows))
                else assertTrue(rows.isEmpty())
            }
            CourtAvatarSprite.preload(a)
        }
        JudgeSprite.preload(JudgePersona.wigsworth)
    }

    @Test fun offsetsUseTheSharedConstants() {
        assertEquals(-CourtEntrancePose.offstageDistance, CourtEntrancePose.offstage(Role.plaintiff).x)
        assertEquals(CourtEntrancePose.offstageDistance, CourtEntrancePose.offstage(Role.defendant).x)
        assertEquals(120f, CourtEntrancePose.offstageDistance)
        assertEquals(CourtEntranceTiming.ready, CourtEntranceDirector.totalDuration, 0.0)
        assertTrue(abs(CourtEntranceTiming.partiesEnd - CourtEntranceTiming.partiesStart - CourtEntranceTiming.walkIn) < 1e-9)
    }
}
