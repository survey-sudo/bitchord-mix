package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LoopIncomingStartWatchdogTest {
    private fun pick(start: Double, end: Double, bars: Int): LoopMix.Pick {
        val beatSec = (end - start) / (bars * 4)
        return LoopMix.Pick(start, end, bars, beatSec, 60.0 / beatSec, 0.0, LoopMix.Check.MASK)
    }

    private fun recordedCrownPlan(hold: Double = 0.0) = LoopMix.Plan(
        out = pick(171.561, 175.546, 2),
        inn = pick(5.365, 15.36, 4),
        fold = 1.0,
        enterBars = 4,
        rampBars = 10,
        exitBars = 2,
        totalBars = 16,
        outLoops = true,
        inLoops = true,
        incomingHoldSec = hold,
    )

    // The existing hold-aware base cue remains valid when its track coordinate is nonnegative.
    private fun baseStartAt(clock: LoopMix.Clock): Double = if (clock.incomingHoldSec > 1e-9) {
        minOf(
            maxOf(LoopMixSession.STARTUP_LEAD, clock.incomingHoldSec - 1.5),
            maxOf(LoopMixSession.STARTUP_LEAD, clock.elementAt - 3.0),
        )
    } else {
        LoopMixSession.STARTUP_LEAD
    }

    private fun assertFinalJoin(
        clock: LoopMix.Clock,
        handover: Double,
        startAt: Double,
        startTrack: Double,
        context: String,
    ) {
        assertTrue("$context: source starts at a nonnegative track position", startTrack >= 0.0)
        assertEquals(
            "$context: rate-1 source must reach the actual final handover coordinate",
            handover,
            startTrack + (clock.elementAt - startAt),
            1e-9,
        )
        assertTrue(
            "$context: preserve decode lead before the element",
            startAt <= clock.elementAt - LoopMixSession.STARTUP_LEAD + 1e-9,
        )
    }

    private fun assertDeferredStart(clock: LoopMix.Clock, handover: Double, context: String) {
        val base = baseStartAt(clock)
        assertTrue("$context: eligible handover", handover >= 2.5)
        assertTrue("$context: base cue needs negative source time", handover + base - clock.elementAt < 0.0)
        val (startAt, startTrack) = LoopMixSession.incomingStartFor(clock, handover)
        assertEquals("$context: wait until the linear source timeline reaches zero", clock.elementAt - handover, startAt, 1e-9)
        assertTrue("$context: deferral cannot advance the base cue", startAt >= base)
        assertFinalJoin(clock, handover, startAt, startTrack, context)
    }

    @Test
    fun recordedUnheldCrownDefersNegativeSourceStartToFinalJoinTimeline() {
        // vc88 incoming pEYv8vuuSpU stalled at 1.684/1.662 s, then timed out after 10 s.
        val clock = LoopMix.Clock(recordedCrownPlan())
        val handover = clock.elementTrackTime
        assertEquals(0.0, clock.incomingHoldSec, 0.0)
        assertEquals(20.3575, handover, 1e-9)
        assertTrue("recorded base cue creates more than a watchdog window of silence", handover + baseStartAt(clock) - clock.elementAt < -10.0)

        val (startAt, startTrack) = LoopMixSession.incomingStartFor(clock)
        assertEquals(clock.elementAt - handover, startAt, 1e-9)
        assertFinalJoin(clock, handover, startAt, startTrack, "recorded unheld crown")
    }

    @Test
    fun recordedCrownScheduledSourceAdvancesBeyondTenSecondWatchdogWindow() {
        val clock = LoopMix.Clock(recordedCrownPlan())
        val handover = clock.elementTrackTime
        val (startAt, startTrack) = LoopMixSession.incomingStartFor(clock)
        val stepSec = 0.25
        val ticks = 48 // Twelve virtual seconds of source consumption; no sleeps or player fake.
        assertTrue("exercise the silent incoming schedule before the final join", startAt + ticks * stepSec < clock.elementAt)

        // offsetNow/correct insert silence when the source is ahead of H + (m - elementAt).
        // Model only that ideal alignment debt and rate-1 input consumption, not Media3 itself.
        val targetAtStart = handover + (startAt - clock.elementAt)
        val insertionDebtSec = (startTrack - targetAtStart).coerceAtLeast(0.0)
        var previousTrack = startTrack
        for (tick in 1..ticks) {
            val elapsed = tick * stepSec
            val sourceTrack = startTrack + (elapsed - insertionDebtSec).coerceAtLeast(0.0)
            assertTrue("source must advance at virtual elapsed=$elapsed, debt=$insertionDebtSec", sourceTrack > previousTrack)
            assertEquals("rate-1 source progress at elapsed=$elapsed", stepSec, sourceTrack - previousTrack, 1e-9)
            assertEquals("no ideal alignment insertion debt at elapsed=$elapsed", 0.0, insertionDebtSec, 1e-9)
            assertEquals("source follows the linear final-join target at elapsed=$elapsed", handover + (startAt + elapsed - clock.elementAt), sourceTrack, 1e-9)
            previousTrack = sourceTrack
        }
        assertEquals(12.0, previousTrack - startTrack, 1e-9)
    }

    @Test
    fun eligibleHeldAndUnheldStartsDeferUsingDefaultAndOverriddenHandovers() {
        for (hold in listOf(0.0, 1e-10, 1.25, 4.0, 12.0)) {
            val clock = LoopMix.Clock(recordedCrownPlan(hold))
            for (handover in listOf(2.5, 3.0, 8.0, clock.elementTrackTime, clock.elementTrackTime + 1.25).distinct()) {
                assertDeferredStart(clock, handover, "hold=$hold handover=$handover")
            }
        }
    }

    @Test
    fun trackedGeometryOverrideAnchorsTheDeferredStartToItsActualFinalKnot() {
        val plan = LoopMix.Plan(
            out = pick(171.561, 176.561, 2),
            inn = pick(1.0, 9.0, 4),
            fold = 1.0,
            enterBars = 4,
            rampBars = 10,
            exitBars = 2,
            totalBars = 16,
            inLoops = false,
        )
        val clock = LoopMix.Clock(plan)
        val trackedBeatSec = 0.49
        val tracked = List(clock.beats + clock.settleBeats + 1) { plan.inn.loopStart + it * trackedBeatSec }
        val analysis = TrackAnalysis(headBeats = BeatSide(tracked, emptyList(), 60.0 / trackedBeatSec, 0.9))
        val knots = LoopMixSession.buildDeckKnots(plan, clock, 48_000, null, analysis, false, clock.releaseAt, 1.0)
        val geometry = LoopMixSession.incomingGeometry(plan, clock, knots)

        assertFalse(geometry.loops)
        assertEquals(knots.inPositionsSec.last(), geometry.handoverTrack, 1e-9)
        assertTrue("tracked final knot differs from the nominal clock", abs(geometry.handoverTrack - clock.elementTrackTime) > 0.01)
        assertDeferredStart(clock, geometry.handoverTrack, "tracked final-knot override")
        val (actualStartAt, _) = LoopMixSession.incomingStartFor(clock, geometry.handoverTrack)
        val (nominalStartAt, _) = LoopMixSession.incomingStartFor(clock)
        assertTrue("the override must affect scheduling", abs(actualStartAt - nominalStartAt) > 0.01)
    }

    @Test
    fun alreadyNonnegativeStartsKeepTheirOriginalBaseCueAndTrackPosition() {
        for (hold in listOf(0.0, 1e-10, 1.25, 4.0, 12.0)) {
            val plan = recordedCrownPlan(hold)
            val clock = LoopMix.Clock(plan)
            val base = baseStartAt(clock)
            for (initialTrack in listOf(0.0, 0.125, 5.0, 20.0)) {
                val handover = clock.elementAt - base + initialTrack
                val context = "hold=$hold initialTrack=$initialTrack"
                val (startAt, startTrack) = LoopMixSession.incomingStartFor(clock, handover)
                assertEquals("$context: retain base cue", base, startAt, 1e-9)
                assertEquals("$context: retain initial track position", initialTrack, startTrack, 1e-9)
                assertFinalJoin(clock, handover, startAt, startTrack, context)
            }

            val laterPick = plan.inn.copy(loopStart = plan.inn.loopStart + 60.0, loopEnd = plan.inn.loopEnd + 60.0)
            val laterClock = LoopMix.Clock(plan.copy(inn = laterPick))
            val expectedTrack = laterClock.elementTrackTime - (laterClock.elementAt - base)
            assertTrue("default handover has a nonnegative base coordinate", expectedTrack >= 0.0)
            val (startAt, startTrack) = LoopMixSession.incomingStartFor(laterClock)
            assertEquals(base, startAt, 1e-9)
            assertEquals(expectedTrack, startTrack, 1e-9)
            assertFinalJoin(laterClock, laterClock.elementTrackTime, startAt, startTrack, "default handover hold=$hold")
        }
    }
}
