// Port of ArgueWinTests/PaywallOpeningTests.swift.
package app.plead.android.features.paywall

import app.plead.android.app.LaunchArguments
import app.plead.android.services.UserDefaults
import java.util.UUID
import kotlin.math.abs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** CONTRACTS-v2 Amendment q: the paywall opening's timing table, trigger rules and phase machine. */
class PaywallOpeningTimingTests {
    private val t = PaywallOpeningTiming

    @Test fun tableMatchesTheAmendment() {
        assertEquals(0.10, t.stillness, 0.0)
        assertEquals(0.80, t.bloom, 0.0)
        assertEquals(0.90, t.swell, 0.0)
        assertEquals(0.68, t.toBadge, 0.0)
        assertEquals(1.05, t.rays, 0.0)
        assertEquals(1.05, t.hold, 0.0)
        assertEquals(0.55, t.travel, 0.0)
        assertEquals(200f, t.markWidth, 0f)
        assertEquals(0.5f, t.markHeight, 0f)
        assertEquals(0.8, t.skippableAfter, 0.0)
        assertEquals(0.3, t.reducedFade, 0.0)
    }

    @Test fun totalIsAboutTwoPointFourSeconds() {
        assertTrue(abs(t.total - 2.38) < 0.0001)
        assertTrue(abs(t.schedule.last().at - t.total) < 0.0001)
    }

    /** The heart lands before the bloom has finished and near the swell's peak (78% of 0.9 s ≈ 0.70 s). */
    @Test fun beatsLineUp() {
        assertTrue(t.toBadge < t.bloom)
        assertTrue(abs(t.swell * PaywallOpeningHaptics.peakAt - t.toBadge) < 0.05)
        assertEquals(t.rays, t.hold, 0.0) // the light is still leaving when the travel starts, never after
        assertTrue(t.skippableAfter < t.total - t.travel) // skippable well before the travel
    }

    @Test fun scheduleIsInOrder() {
        val phases = t.schedule.map { it.phase }
        assertEquals(PaywallOpeningPhase.entries.toList(), phases)
        assertTrue(t.schedule.zipWithNext().all { (a, b) -> a.at < b.at })
        assertTrue(abs(t.schedule[2].at - 0.78) < 1e-9)
        assertTrue(abs(t.schedule[3].at - 1.83) < 1e-9)
    }

    @Test fun phaseAtTime() {
        assertEquals(PaywallOpeningPhase.still, t.phase(at = 0.0))
        assertEquals(PaywallOpeningPhase.bloom, t.phase(at = 0.3))
        assertEquals(PaywallOpeningPhase.badge, t.phase(at = 0.9))
        assertEquals(PaywallOpeningPhase.badge, t.phase(at = 1.5))
        assertEquals(PaywallOpeningPhase.travel, t.phase(at = 2.0))
        assertEquals(PaywallOpeningPhase.settled, t.phase(at = 3.0))
        // Monotonic over the whole run.
        val samples = (0..300).map { t.phase(at = it * 0.01) }
        assertTrue(samples.zipWithNext().all { (a, b) -> a <= b })
    }

    @Test fun heartHiddenBeforeBadgeVisibleAfter() {
        assertFalse(t.phase(at = t.stillness + t.toBadge - 0.01).heartVisible)
        assertTrue(t.phase(at = t.stillness + t.toBadge + 0.01).heartVisible)
        assertEquals(
            listOf(PaywallOpeningPhase.badge, PaywallOpeningPhase.travel, PaywallOpeningPhase.settled),
            PaywallOpeningPhase.entries.filter { it.heartVisible },
        )
    }

    @Test fun raysGoOutAndFade() {
        assertEquals(0.6f, PaywallOpeningRays.scale(0.0), 1e-6f)
        assertEquals(1.6f, PaywallOpeningRays.scale(1.0), 1e-6f)
        assertEquals(0.0, PaywallOpeningRays.fade(0.0), 0.0)
        assertEquals(1.0, PaywallOpeningRays.fade(0.2), 1e-12)
        assertEquals(0.0, PaywallOpeningRays.fade(1.0), 1e-12)
    }

    // Android: the swell's waveform follows the Core Haptics control curve.
    @Test fun swellWaveformPeaksAtSeventyEightPercent() {
        val (timings, amplitudes) = PaywallOpeningHaptics.waveform(t.swell)
        assertEquals((t.swell * 1000).toLong(), timings.sum())
        val peakIndex = amplitudes.indices.maxBy { amplitudes[it] }
        assertTrue(abs((peakIndex + 0.5) / amplitudes.size - PaywallOpeningHaptics.peakAt) < 0.05)
        assertTrue(amplitudes.first() < amplitudes[peakIndex] / 4)
        assertTrue(amplitudes.last() < amplitudes[peakIndex] / 4)
        assertTrue(amplitudes.max() <= (PaywallOpeningHaptics.peak * 255).toInt() + 1)
    }
}

class PaywallOpeningRuleTests {
    private fun i(
        stage: PaywallStage = PaywallStage.standard,
        partnerPaid: Boolean = false,
        seen: Boolean = false,
        shownThisLaunch: Boolean = false,
        forced: Boolean = false,
        reduceMotion: Boolean = false,
    ) = PaywallOpeningRule.Input(
        stage = stage, partnerPaid = partnerPaid, seen = seen, shownThisLaunch = shownThisLaunch,
        forced = forced, reduceMotion = reduceMotion,
    )

    @Before fun setUp() = LaunchArguments.set(emptyMap())

    @After fun tearDown() {
        PaywallOpeningRule.shownThisLaunch = false
        LaunchArguments.set(emptyMap())
    }

    @Test fun eligibility() {
        val cases = listOf(
            i() to PaywallOpeningMode.full,                                                   // first show
            i(seen = true) to PaywallOpeningMode.none,                                        // re-show on a later launch
            i(shownThisLaunch = true) to PaywallOpeningMode.none,                             // re-show (e.g. after a failed purchase)
            i(stage = PaywallStage.exitOffer) to PaywallOpeningMode.none,                     // never the exit offer
            i(stage = PaywallStage.exitOffer, forced = true) to PaywallOpeningMode.none,
            i(partnerPaid = true) to PaywallOpeningMode.none,                                 // never partner-paid
            i(partnerPaid = true, forced = true) to PaywallOpeningMode.none,
            i(reduceMotion = true) to PaywallOpeningMode.fade,                                // reduce motion: 0.3 s fade
            i(seen = true, reduceMotion = true) to PaywallOpeningMode.none,
            i(seen = true, forced = true) to PaywallOpeningMode.full,                         // AWPaywallOpening YES
            i(seen = true, shownThisLaunch = true, forced = true) to PaywallOpeningMode.full,
            i(seen = true, forced = true, reduceMotion = true) to PaywallOpeningMode.fade,
        )
        for ((input, expected) in cases) assertEquals("$input", expected, PaywallOpeningRule.mode(input))
    }

    @Test fun seenKeyIsPerUser() {
        val uid = UUID.fromString("0B7E2C9A-1111-2222-3333-444455556666")
        assertEquals("paywallOpeningSeen.0b7e2c9a-1111-2222-3333-444455556666", PaywallOpeningRule.seenKey(uid))
        assertEquals("paywallOpeningSeen.anon", PaywallOpeningRule.seenKey(null))
    }

    @Test fun consumeRecordsSeenOnlyWhenPlayed() {
        val defaults = UserDefaults.inMemory()
        val uid = UUID.randomUUID()
        PaywallOpeningRule.shownThisLaunch = false
        assertEquals(
            PaywallOpeningMode.none,
            PaywallOpeningRule.consume(uid, PaywallStage.exitOffer, partnerPaid = false, reduceMotion = false, defaults = defaults),
        )
        assertFalse(defaults.bool(PaywallOpeningRule.seenKey(uid)))
        assertEquals(
            PaywallOpeningMode.full,
            PaywallOpeningRule.consume(uid, PaywallStage.standard, partnerPaid = false, reduceMotion = false, defaults = defaults),
        )
        assertTrue(defaults.bool(PaywallOpeningRule.seenKey(uid)))
        // Second appearance in the same launch: skipped.
        assertEquals(
            PaywallOpeningMode.none,
            PaywallOpeningRule.consume(uid, PaywallStage.standard, partnerPaid = false, reduceMotion = false, defaults = defaults),
        )
        PaywallOpeningRule.shownThisLaunch = false
    }
}

class PaywallOpeningDirectorTests {
    private fun director(): PaywallOpeningDirector {
        val d = PaywallOpeningDirector()
        d.playsHaptics = false
        d.sleep = { }
        return d
    }

    @Test fun phasesAdvanceInOrder() = runTest {
        val d = director()
        assertEquals(PaywallOpeningPhase.still, d.phase)
        assertEquals(0.0, d.heart, 0.0)
        d.run()
        assertEquals(PaywallOpeningPhase.entries.toList(), d.entered)
        assertEquals(PaywallOpeningPhase.settled, d.phase)
        assertEquals(1.0, d.heart, 0.0)
        assertTrue(d.handedOver)
        assertEquals(1.0, d.contentOpacity, 0.0)
    }

    @Test fun sleepsFollowTheTable() = runTest {
        val d = director()
        val waits = mutableListOf<Double>()
        d.sleep = { waits.add(it) }
        d.run()
        val expected = listOf(0.10, 0.68, 1.05, 0.55)
        assertEquals(expected.size, waits.size)
        assertTrue(waits.zip(expected).all { (a, b) -> abs(a - b) < 0.01 })
    }

    @Test fun heartLayerHiddenUntilBadge() = runTest {
        val d = director()
        val heartAtPhase = mutableMapOf<PaywallOpeningPhase, Double>()
        d.sleep = { heartAtPhase[d.phase] = d.heart }
        d.run()
        assertEquals(0.0, heartAtPhase[PaywallOpeningPhase.still])
        assertEquals(0.0, heartAtPhase[PaywallOpeningPhase.bloom])
        assertEquals(1.0, heartAtPhase[PaywallOpeningPhase.badge])
        assertEquals(1.0, heartAtPhase[PaywallOpeningPhase.travel])
    }

    @Test fun contentHiddenUntilTravel() = runTest {
        val d = director()
        val contentAtPhase = mutableMapOf<PaywallOpeningPhase, Double>()
        d.sleep = { contentAtPhase[d.phase] = d.contentOpacity }
        d.run()
        assertEquals(0.0, contentAtPhase[PaywallOpeningPhase.badge])
        assertEquals(1.0, contentAtPhase[PaywallOpeningPhase.travel])
    }

    @Test fun noneAndFadeSettleWithoutTheChoreography() {
        val none = director()
        none.start(PaywallOpeningMode.none)
        assertTrue(none.handedOver && none.phase == PaywallOpeningPhase.settled && !none.isPlaying)
        val fade = director()
        fade.start(PaywallOpeningMode.fade)
        assertTrue(fade.handedOver && fade.contentOpacity == 1.0 && fade.entered.isEmpty())
    }

    @Test fun skipIgnoredBeforeItIsAllowed() {
        val d = director()
        d.start(PaywallOpeningMode.none)
        d.skip() // not playing: no-op
        assertEquals(PaywallOpeningPhase.settled, d.phase)
    }
}
