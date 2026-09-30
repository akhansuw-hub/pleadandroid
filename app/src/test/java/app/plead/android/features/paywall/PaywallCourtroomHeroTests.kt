// Port of ArgueWinTests/PaywallCourtroomHeroTests.swift. Swift `Task` + `Task.yield()` → a coroutine on the test
// scheduler (`runCurrent()` plays everything that is ready).
package app.plead.android.features.paywall

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import app.plead.android.R
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The living paywall courtroom (CONTRACTS-v2 amendment v): sprite rects/assets, placement, and the animator's loop. */
@OptIn(ExperimentalCoroutinesApi::class)
class PaywallCourtroomHeroTests {

    data class Step(val pose: CourtroomPose, val hold: Double, val at: Double)

    /**
     * A fake clock: every sleep records the pose on screen and its hold, advances time, and (after `limit` holds)
     * ends the loop by throwing, like a cancellation.
     */
    class Script {
        var t = 0.0
        val steps = mutableListOf<Step>()
        var limit = 200

        /** When set, sleeps suspend for real (until cancelled) after recording, instead of returning at once. */
        var suspend = false
        var cancelledSleeps = 0
        lateinit var animator: PaywallCourtroomAnimator

        fun make(
            scope: CoroutineScope,
            random: (ClosedFloatingPointRange<Double>) -> Double = { it.start },
        ): PaywallCourtroomAnimator {
            val a = PaywallCourtroomAnimator(
                scope = scope,
                sleep = { d ->
                    steps.add(Step(animator.pose, d, t))
                    if (suspend) {
                        try {
                            awaitCancellation()
                        } catch (e: CancellationException) {
                            cancelledSleeps += 1
                            throw e
                        }
                    }
                    t += d
                    if (steps.size >= limit) throw CancellationException("limit")
                },
                now = { t },
                random = random,
            )
            animator = a
            return a
        }
    }

    private fun TestScope.runLoop(
        limit: Int = 200,
        random: (ClosedFloatingPointRange<Double>) -> Double = { it.start },
    ): List<Step> {
        val s = Script()
        s.limit = limit
        val a = s.make(backgroundScope, random)
        a.start()
        runCurrent()
        assertFalse(a.isRunning)
        return s.steps
    }

    // MARK: Assets

    @Test fun rectsSitInsideTheSource() {
        val src = Rect(Offsetzero, PaywallCourtroomSprites.sourcePixels)
        for (c in PaywallCourtroomSprites.Character.entries) {
            val r = c.rect
            assertTrue("$c rect $r leaves the source", r.left >= src.left && r.top >= src.top && r.right <= src.right && r.bottom <= src.bottom)
            assertTrue(r.width > 0 && r.height > 0)
        }
        assertFalse(PaywallCourtroomSprites.Character.judge.rect.overlaps(PaywallCourtroomSprites.Character.plaintiff.rect))
        assertFalse(PaywallCourtroomSprites.Character.judge.rect.overlaps(PaywallCourtroomSprites.Character.defendant.rect))
    }

    @Test fun everyFrameResolvesOnItsCharactersCanvas() {
        val bg = png("paywall_courtroom_bg")
        assertEquals(PaywallCourtroomSprites.sourcePixels.width.toInt(), bg.first)
        assertEquals(PaywallCourtroomSprites.sourcePixels.height.toInt(), bg.second)
        assertEquals("the flattened art stays for the cold open fallback", 941, png("paywall_courtroom").first)
        assertEquals("paywall_courtroom_bg", drawableName(PaywallCourtroomSprites.backgroundDrawable))
        for (c in PaywallCourtroomSprites.Character.entries) {
            assertEquals(if (c == PaywallCourtroomSprites.Character.judge) 6 else 4, c.frameNames.size)
            c.frameNames.zip(c.frameDrawables).forEach { (name, id) ->
                val file = snake(name)
                // The drawable the scene draws is the asset of that name.
                assertEquals(name, file, drawableName(id))
                // Same canvas as the rect, at 3x, so a frame drawn at the rect's offset maps 1:1 onto the art.
                val (w, h) = png(file)
                assertEquals(name, c.rect.width.toInt(), w)
                assertEquals(name, c.rect.height.toInt(), h)
            }
        }
        assertEquals(3f, PaywallCourtroomSprites.sourceScale)
    }

    // MARK: Placement

    @Test fun placementMatchesTheOldAspectFillBottomAlignedHero() {
        // iPhone 17 Pro: 402 wide, hero 28% of 874 ≈ 245.
        val p = PaywallCourtroomPlacement(hero = Size(402f, 245f))
        assertTrue(abs(p.art.bottom - 245) < 0.001)                 // bottom-aligned
        assertTrue(abs(p.art.center.x - 201) < 0.001)               // centred
        assertTrue(p.art.width >= 402 && p.art.height >= 245)       // fills
        assertTrue(abs(p.art.width / p.art.height - 941f / 840f) < 0.0001)
        val r = p.inArt(PaywallCourtroomSprites.Character.judge.rect)
        assertTrue(abs(r.left - 326 * p.pointsPerPixel) < 0.001 && abs(r.width - 206 * p.pointsPerPixel) < 0.001)
        // A wide, short hero (tablet-ish) fills by width instead and still bottom-aligns.
        val wide = PaywallCourtroomPlacement(hero = Size(1000f, 300f))
        assertTrue(abs(wide.art.width - 1000) < 0.001 && abs(wide.art.bottom - 300) < 0.001)
    }

    // MARK: Loop

    @Test fun sequenceIsDeterministic() = runTest {
        val a = runLoop()
        val b = runLoop()
        assertTrue(a.size == b.size && a.size == 200)
        assertEquals(a.map { it.pose }, b.map { it.pose })
        assertEquals(a.map { it.hold }, b.map { it.hold })
    }

    @Test fun firstLoopFollowsTheBrief() = runTest {
        val steps = runLoop(limit = 40)
        val timing = PaywallCourtroomTiming
        // Entrance: everyone at rest, 0.4 s.
        assertTrue(steps[0].pose == CourtroomPose.rest && steps[0].hold == timing.entrance)
        // Plaintiff talks first (defendant idle), within 0.7–0.9 s.
        val talkFrames = timing.talkFrames.size
        val plaintiff = steps.subList(1, talkFrames + 1)
        assertTrue(plaintiff.all { it.pose.defendant == PartnerFrame.idle && it.pose.judge == JudgeFrame.idle1 })
        assertTrue(plaintiff.any { it.pose.plaintiff == PartnerFrame.talkA } && plaintiff.any { it.pose.plaintiff == PartnerFrame.talkB })
        val talked = plaintiff.sumOf { it.hold }
        assertTrue(talked > 0.7 - 1e-6 && talked < 0.9 + 1e-6)
        // Gap: the defendant reacts, 0.2–0.4 s.
        val gap = steps[talkFrames + 1]
        assertEquals(CourtroomPose(defendant = PartnerFrame.react), gap.pose)
        assertTrue(gap.hold in timing.gap)
        // Then the defendant talks, the plaintiff silent.
        val defendant = steps.subList(talkFrames + 2, 2 * talkFrames + 2)
        assertTrue(defendant.all { it.pose.plaintiff == PartnerFrame.idle })
        assertTrue(defendant.any { it.pose.defendant == PartnerFrame.talkA })
        // The judge reacts: a blink, then the first gavel strike, raise → strike → recoil.
        val rest = steps.subList(2 * talkFrames + 2, steps.size)
        val blink = rest.indexOfFirst { it.pose.judge == JudgeFrame.blink }
        val up = rest.indexOfFirst { it.pose.judge == JudgeFrame.gavelUp }
        assertTrue(blink in 0 until up)
        assertTrue(rest[up + 1].pose.judge == JudgeFrame.gavelDown && rest[up + 2].pose.judge == JudgeFrame.gavelRecoil)
        assertEquals(CourtroomPose.rest, rest[up + 3].pose)
        val swing = rest[up].hold + rest[up + 1].hold + rest[up + 2].hold
        assertTrue(swing in 0.35..0.5)
        // Nobody talks while the gavel swings, and no hold is faster than the pixel-art cadence (≤ 12 fps).
        assertTrue(rest.subList(up, up + 3).all { it.pose.plaintiff == PartnerFrame.idle && it.pose.defendant == PartnerFrame.idle })
        assertTrue(steps.all { it.hold >= timing.minimumFrame - 1e-9 })
    }

    @Test fun loopLengthAndGavelSpacing() = runTest {
        val picks: List<(ClosedFloatingPointRange<Double>) -> Double> = listOf({ it.start }, { it.endInclusive })
        for (pick in picks) {
            val steps = runLoop(limit = 400, random = pick)
            // Loops start where the plaintiff opens his mouth after everyone was at rest.
            val starts = (1 until steps.size)
                .filter { steps[it].pose.plaintiff == PartnerFrame.talkA && steps[it - 1].pose == CourtroomPose.rest }
                .map { steps[it].at }
            assertTrue(starts.size >= 4)
            starts.zipWithNext().forEach { (x, y) -> assertTrue("loop ${y - x} s", (y - x) in 3.0..6.5) }
            // The judge blinks every loop (3–6 s apart) and the gavel repeats no more than every 7 s.
            val blinks = steps.filter { it.pose.judge == JudgeFrame.blink }.map { it.at }
            blinks.zipWithNext().forEach { (x, y) -> assertTrue((y - x) in 3.0..6.5) }
            val gavels = steps.filter { it.pose.judge == JudgeFrame.gavelUp }.map { it.at }
            assertTrue(gavels.size >= 2)
            gavels.zipWithNext().forEach { (x, y) -> assertTrue(y - x >= 7) }
            // The breathing frame appears in the idle hold.
            assertTrue(steps.any { it.pose.judge == JudgeFrame.idle2 })
        }
    }

    @Test fun stopCancelsTheTaskAndRests() = runTest {
        val s = Script()
        s.suspend = true
        val a = s.make(backgroundScope)
        a.start()
        assertTrue(a.isRunning)
        runCurrent()
        assertEquals(1, s.steps.size)
        a.stop()
        assertFalse(a.isRunning)
        assertEquals(CourtroomPose.rest, a.pose)
        runCurrent()
        assertEquals(1, s.cancelledSleeps)           // the only sleeping coroutine observed the cancellation
        runCurrent()
        assertEquals(1, s.steps.size)                // and nothing ran after it
        assertEquals(CourtroomPose.rest, a.pose)
        a.stop()                                     // idempotent
        assertFalse(a.isRunning)
    }

    @Test fun startIsIdempotent() = runTest {
        val s = Script()
        s.suspend = true
        val a = s.make(backgroundScope)
        a.start(); a.start()
        runCurrent()
        assertEquals(1, s.steps.size)   // one coroutine, not two
        a.stop()
    }

    @Test fun reduceMotionNeverTalksOrSwings() = runTest {
        val s = Script()
        val a = s.make(backgroundScope)
        a.reduceMotion = true
        a.start()
        assertFalse(a.isRunning)
        a.gavelTap()
        runCurrent()
        assertTrue(s.steps.isEmpty())
        assertEquals(CourtroomPose.rest, a.pose)
        assertFalse(a.hasStruck)
        // Turning Reduce Motion on mid-loop stops it at rest.
        val s2 = Script()
        s2.suspend = true
        val b = s2.make(backgroundScope)
        b.start()
        runCurrent()
        assertEquals(1, s2.steps.size)
        b.reduceMotion = true
        assertTrue(!b.isRunning && b.pose == CourtroomPose.rest)
    }

    @Test fun gavelTapIsDebounced() = runTest {
        val s = Script()
        s.suspend = true
        val a = s.make(backgroundScope)
        a.start()
        runCurrent()
        assertEquals(1, s.steps.size)
        // Sleeps suspend here, so each accepted tap shows (and stays on) its first frame: the judge's blink.
        fun ups() = s.steps.count { it.pose.judge == JudgeFrame.blink }

        a.gavelTap()
        runCurrent()
        assertEquals(1, ups())
        s.t = 1.0                 // rapid switching: ignored
        a.gavelTap()
        s.t = 1.4
        a.gavelTap()
        runCurrent()
        assertEquals(1, ups())
        s.t = 1.6                 // past the 1.5 s cooldown
        a.gavelTap()
        runCurrent()
        assertEquals(2, ups())
        a.stop()
        // Stopped: ignored.
        s.t = 10.0
        a.gavelTap()
        runCurrent()
        assertTrue(ups() == 2 && !a.isRunning)
    }

    @Test fun gavelTapBlinksThenSwingsThenIdles() = runTest {
        val s = Script()
        s.limit = 12
        val a = s.make(backgroundScope)
        a.start()
        s.t = 5.0
        a.gavelTap()
        runCurrent()
        assertFalse(a.isRunning)
        val judge = s.steps.map { it.pose.judge }
        assertEquals(listOf(JudgeFrame.blink, JudgeFrame.gavelUp, JudgeFrame.gavelDown, JudgeFrame.gavelRecoil), judge.take(4))
        assertEquals(CourtroomPose.rest, s.steps[4].pose)           // straight into the idle hold, not the talk
        assertTrue(s.steps.take(4).all { it.pose.plaintiff == PartnerFrame.idle && it.pose.defendant == PartnerFrame.idle })
        val swing = s.steps[1].hold + s.steps[2].hold + s.steps[3].hold
        assertTrue(swing in 0.35..0.5)
    }

    @Test fun firstStrikeCallbackFiresOnce() = runTest {
        val s = Script()
        s.limit = 400
        val a = s.make(backgroundScope)
        var strikes = 0
        a.onFirstStrike = { strikes += 1 }
        a.start()
        runCurrent()
        assertFalse(a.isRunning)
        assertTrue(s.steps.count { it.pose.judge == JudgeFrame.gavelDown } >= 2)
        assertEquals(1, strikes)
    }

    @Test fun reduceMotionFollowsTheSystemOutsideTheDemo() {
        app.plead.android.app.LaunchArguments.set(emptyMap())
        assertTrue(PaywallCourtroomHero.reduceMotion(true))
        assertFalse(PaywallCourtroomHero.reduceMotion(false))
    }

    // MARK: Helpers

    private val Offsetzero = androidx.compose.ui.geometry.Offset.Zero

    /** "PaywallJudge_gavelUp" → "paywall_judge_gavel_up" (tools/android/import_assets.sh). */
    private fun snake(asset: String): String =
        asset.split("_").joinToString("_") { it.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase() }

    private fun drawableName(id: Int): String =
        R.drawable::class.java.fields.first { it.getInt(null) == id }.name

    /** Width and height from the PNG's IHDR chunk. */
    private fun png(name: String): Pair<Int, Int> {
        val file = listOf("src/main/res/drawable-nodpi/$name.png", "app/src/main/res/drawable-nodpi/$name.png")
            .map(::File).first { it.exists() }
        RandomAccessFile(file, "r").use { f ->
            f.seek(16)
            return f.readInt() to f.readInt()
        }
    }
}
