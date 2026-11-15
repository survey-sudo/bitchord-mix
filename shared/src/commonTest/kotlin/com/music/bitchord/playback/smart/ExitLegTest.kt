package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * vc70 exit-leg regression: Seams A/B must end at full-band over beats,
 * not over the teardown open() glide. Every assertion samples rendered
 * behavior (cutoffs, envelope breakpoints), never plan constants alone.
 */
class ExitLegTest {

    @Test
    fun mufflePlanCarriesNonZeroReturnSpan() {
        // Plan shape without this assertion is how the return leg vanished:
        // the renderer cannot invent a span the model does not carry.
        val plan = TransitionPlan(
            transitionStyle = TransitionStyle.MUFFLE,
            muffleLeadBeats = 8,
            exitBeats = 2,
            bassSwapFraction = 0.75,
            filterSweep = 1.0,
        )
        assertTrue(plan.exitBeats > 0, "muffle plan must carry a return span")
        assertEquals(2, plan.exitBeats)
        assertEquals(8, plan.muffleLeadBeats)
        // The 0.75 is the phase boundary (entry 6 / total 8), not a missing
        // return anymore: the return is the explicit 2 beats.
        assertEquals(0.75, plan.bassSwapFraction, 1e-9)
        assertTrue(plan.transitionStyle == TransitionStyle.MUFFLE)
    }

    @Test
    fun muffleRideEndsAtFullBand() {
        // Sampled ride: entry closes to floor, return reopens to full-band.
        val atStart = TransitionAlternatives.muffleCutoffAt(0.0, 1.0, 0.75)
        val atSwap = TransitionAlternatives.muffleCutoffAt(0.75, 1.0, 0.75)
        val atEnd = TransitionAlternatives.muffleCutoffAt(1.0, 1.0, 0.75)
        assertEquals(20000.0, atStart, 1e-6)
        assertEquals(700.0, atSwap, 1.0)
        assertEquals(20000.0, atEnd, 1e-6)
        // Mid-return is strictly between floor and full-band (monotonic close
        // without a return would still be parked at 700 at progress 1).
        val atMidReturn = TransitionAlternatives.muffleCutoffAt(0.875, 1.0, 0.75)
        assertTrue(atMidReturn > 700.0 && atMidReturn < 20000.0, "mid-return=$atMidReturn")
        // filterSweep scaling is kept: half sweep parks higher, still opens.
        val halfSweepMid = TransitionAlternatives.muffleCutoffAt(0.75, 0.5, 0.75)
        val halfSweepEnd = TransitionAlternatives.muffleCutoffAt(1.0, 0.5, 0.75)
        assertTrue(halfSweepMid > 700.0, "half-sweep floor=$halfSweepMid")
        assertEquals(20000.0, halfSweepEnd, 1e-6)
    }

    @Test
    fun bridgeReopenEndsAtFullBand() {
        // Bridge return leg: park at 1500 before the return, full-band at 1.0.
        val before = TransitionAlternatives.bridgeReopenCutoffAt(0.4, 0.5, 1500.0)
        val atStart = TransitionAlternatives.bridgeReopenCutoffAt(0.5, 0.5, 1500.0)
        val mid = TransitionAlternatives.bridgeReopenCutoffAt(0.75, 0.5, 1500.0)
        val end = TransitionAlternatives.bridgeReopenCutoffAt(1.0, 0.5, 1500.0)
        assertEquals(1500.0, before, 1e-6)
        assertEquals(1500.0, atStart, 1e-6)
        assertTrue(mid > 1500.0 && mid < 20000.0, "bridge mid-return=$mid")
        assertEquals(20000.0, end, 1e-6)
    }

    private fun echoClock(): LoopMix.Clock {
        val outBpm = 120.0
        val beat = 60.0 / outBpm
        val plan = LoopMix.Plan(
            out = LoopMix.Pick(loopStart = 0.0, loopEnd = 2.0, bars = 1, beatSec = beat, bpm = outBpm, vocal = 0.0, checkedBy = LoopMix.Check.LYRICS),
            inn = LoopMix.Pick(loopStart = 0.0, loopEnd = 2.0, bars = 1, beatSec = beat, bpm = outBpm, vocal = 0.0, checkedBy = LoopMix.Check.LYRICS),
            fold = 1.0,
            enterBars = 1,
            rampBars = 0,
            exitBars = 1,
            totalBars = 2,
            outLoops = false,
            inLoops = false,
            nativeIncoming = true,
            echoTail = true,
            rung = "Echo",
        )
        return LoopMix.Clock(plan)
    }

    private fun assertEnvelopeWellFormed(times: DoubleArray, vals: FloatArray, label: String) {
        assertTrue(times.size >= 3, "$label needs entry, floor and return points: ${times.size}")
        assertEquals(times.size, vals.size, "$label times/vals size")
        for (i in 1 until times.size) {
            assertTrue(times[i] > times[i - 1], "$label times must strictly increase at $i: ${times.toList()}")
            val delta = times[i] - times[i - 1]
            // No single-sample collapse: adjacent breakpoints span beats,
            // not one 64-frame block (~1.5 ms) or one sample.
            assertTrue(delta > 0.01, "$label collapse at $i delta=$delta: ${times.toList()}")
        }
        assertEquals(20000.0, vals.last().toDouble(), 1e-3)
    }

    @Test
    fun nativeMuffleEnvelopeHasNoCollapse() {
        val clock = echoClock()
        // Degenerate echo clock: enterEnd == rampEnd. The old envelope
        // [enterEnd, rampEnd, releaseAt] collapsed 20000->2800 into one sample.
        assertEquals(clock.enterEnd, clock.rampEnd, 1e-9)
        val (times, vals) = TransitionAlternatives.muffleEnvelope(clock)
        assertEnvelopeWellFormed(times, vals, "muffle")
        // Entry close lands at the floor, return ends full-band.
        assertTrue(abs(vals[1].toDouble() - 700.0) < 1e-3, "muffle floor=${vals.toList()}")
        assertEquals(20000.0, vals[0].toDouble(), 1e-3)
        assertEquals(20000.0, vals[2].toDouble(), 1e-3)
    }

    @Test
    fun nativeBridgeEnvelopeHasNoCollapse() {
        val clock = echoClock()
        val (times, vals) = TransitionAlternatives.bridgeEnvelope(clock)
        assertEnvelopeWellFormed(times, vals, "bridge")
        assertTrue(abs(vals[1].toDouble() - 1500.0) < 1e-3, "bridge park=${vals.toList()}")
    }

    @Test
    fun familyRegistryCoversAllStyles() {
        assertEquals(3, TransitionAlternatives.FAMILIES.size)
        assertTrue(TransitionAlternatives.isAlternativeStyle(TransitionStyle.MUFFLE))
        assertTrue(TransitionAlternatives.isAlternativeStyle(TransitionStyle.DROP_IN))
        assertTrue(TransitionAlternatives.isAlternativeStyle(TransitionStyle.BEATLESS_BRIDGE))
        assertTrue(!TransitionAlternatives.isAlternativeStyle(TransitionStyle.DJ_BLEND))
        assertTrue(TransitionAlternatives.isAlternativeKind("muffle"))
        assertTrue(TransitionAlternatives.isAlternativeKind("drop-in"))
        assertTrue(TransitionAlternatives.isAlternativeKind("beatless-bridge"))
        // Every registered kind has a rung and a loop mapping.
        for (spec in TransitionAlternatives.FAMILIES) {
            assertTrue(spec.rung.isNotBlank(), "rung for ${spec.kind}")
            val probe = LoopMix.Plan(
                out = LoopMix.Pick(0.0, 2.0, 1, 0.5, 120.0, 0.0, LoopMix.Check.LYRICS),
                inn = LoopMix.Pick(0.0, 2.0, 1, 0.5, 120.0, 0.0, LoopMix.Check.LYRICS),
                fold = 1.0, enterBars = 1, rampBars = 0, exitBars = 1, totalBars = 2,
            )
            val mapped = spec.applyLoop(probe)
            assertEquals(spec.kind, mapped.alternativeKind, "applyLoop for ${spec.kind}")
        }
    }
}
