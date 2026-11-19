package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.ceil

/** Actual session knot fixtures, not the transport-only knotTrackPositions helper. */
internal object LoopCrownFixtures {
    const val SAMPLE_RATE = 48_000

    fun plan(outBpm: Double = 120.0, inBpm: Double = outBpm, fold: Double = 1.0): LoopMix.Plan {
        fun pick(bpm: Double) = LoopMix.Pick(4.0, 4.0 + 16 * 60 / bpm, 4, 60 / bpm,
            bpm, 0.0, LoopMix.Check.MASK)
        val ramp = ceil(abs(outBpm - inBpm / fold) / 2.5).toInt().coerceAtLeast(2)
        val inLoopOutBars = 4.0 / fold
        val total = ceil((LoopMix.ENTER_BARS + ramp + LoopMix.EXIT_BARS) / inLoopOutBars) * inLoopOutBars
        val totalBars = total.toInt()
        return LoopMix.Plan(pick(outBpm), pick(inBpm), fold, LoopMix.ENTER_BARS, ramp,
            totalBars - LoopMix.ENTER_BARS - ramp, totalBars)
    }

    fun knots(plan: LoopMix.Plan, clock: LoopMix.Clock = LoopMix.Clock(plan),
        outAnalysis: TrackAnalysis? = null) = LoopMixSession.buildDeckKnots(
        plan, clock, SAMPLE_RATE, outAnalysis, null, false, clock.releaseAt, 1.0,
    )

    // All observed intervals pass the existing +/-18% grid rule, and the
        // declared four-bar body is exact. A late +17%-legal local beat at a
    // nominally safe 1.333x ramp emits an out-of-envelope actual session rate.
    fun irregularOut(plan: LoopMix.Plan): TrackAnalysis {
        val b = plan.out.beatSec
        val start = plan.out.loopStart
        // Repeat the measured per-bar beat pattern across the entire four-bar
        // crown. The late ramp then encounters the same real local interval.
        val beats = (0..plan.out.beats).map { index ->
            val bar = index / 4
            val beat = index % 4
            val offset = when (beat) { 0 -> 0.0; 1 -> 1.17; 2 -> 2.11; 3 -> 3.05; else -> 0.0 }
            start + (bar * 4 + offset) * b
        }
        return TrackAnalysis(tailBeats = BeatSide(beats, emptyList(), plan.out.bpm, .9))
    }

    fun qualify(plan: LoopMix.Plan, knots: LoopMixSession.Companion.DeckKnots,
        clock: LoopMix.Clock = LoopMix.Clock(plan)) =
        LoopMixSession.qualifyCrownKnots(plan, clock, SAMPLE_RATE, knots)
}

class LoopCrownKnotQualificationTest {
    @Test fun nominallySafePlanRefusesIrregularActualSessionKnots() {
        val p = LoopCrownFixtures.plan(120.0, 160.0)
        assertTrue(LoopMix.qualifyCrown(p).accepted)
        val k = LoopCrownFixtures.knots(p, outAnalysis = LoopCrownFixtures.irregularOut(p))
        assertEquals(p.out.loopStart + 1.17 * p.out.beatSec, k.outPositionsSec[1], 1e-10)
        val q = LoopCrownFixtures.qualify(p, k)
        assertFalse(q.accepted)
        assertEquals("crown_actual_out_rate", q.refusal)
        assertTrue(requireNotNull(q.outMaxRate) > LoopMix.CROWN_MAX_EXECUTION_RATE)
        assertTrue(requireNotNull(q.inMinRate).isFinite())
    }

    @Test fun repeatedLoopBoundaryIsCheckedNotJustObservedInteriorGrid() {
        val p = LoopCrownFixtures.plan(120.0, 96.0)
        assertTrue(LoopMix.qualifyCrown(p).accepted)
        // Phase-corrected first knot is exactly loopStart. The tracked beats
        // have a permitted +70 ms phase, leaving a shorter wrap interval.
        // A permitted 5% gradual local drift still ends at each beat's
        // expected phase within the tracker gate. The repeated loop wraps at
        // its literal 8s boundary, making only the wrap interval too slow.
        val observed = (0..p.out.beats).map { i -> p.out.loopStart + i * p.out.beatSec * 1.05 }
        val analysis = TrackAnalysis(tailBeats = BeatSide(observed, emptyList(), p.out.bpm, .9))
        val c = LoopMix.Clock(p)
        val k = LoopCrownFixtures.knots(p, c, analysis)
        val boundary = (1..c.beats).first { i ->
            i % p.out.beats == 0 && LoopCrownFixtures.SAMPLE_RATE *
                (k.outPositionsSec[i] - k.outPositionsSec[i - 1]) /
                (k.outFrames[i] - k.outFrames[i - 1]) < LoopMix.CROWN_MIN_EXECUTION_RATE
        }
        assertEquals(0, boundary % p.out.beats)
        assertEquals("crown_actual_out_rate", LoopCrownFixtures.qualify(p, k, c).refusal)
    }

    @Test fun reciprocalExecutionBoundariesAndWiderFoldsRemainAccepted() {
        for (p in listOf(
            LoopCrownFixtures.plan(120.0, 90.0), // OUT .75, IN 4/3
            LoopCrownFixtures.plan(120.0, 160.0), // OUT 4/3, IN .75
            LoopCrownFixtures.plan(120.0, 134.0),
            LoopCrownFixtures.plan(111.0, 169.0, 2.0),
            LoopCrownFixtures.plan(170.0, 80.0, .5),
        )) {
            assertTrue("nominal ${p.out.bpm}->${p.inn.bpm}/${p.fold}", LoopMix.qualifyCrown(p).accepted)
            val q = LoopCrownFixtures.qualify(p, LoopCrownFixtures.knots(p))
            assertTrue("actual ${p.out.bpm}->${p.inn.bpm}/${p.fold}: ${q.refusal}", q.accepted)
            assertNull(q.refusal)
            for (rate in listOf(q.outMinRate, q.outMaxRate, q.inMinRate, q.inMaxRate)) {
                val finite = requireNotNull(rate)
                assertTrue(finite.isFinite())
                assertTrue(finite >= .75 - 1e-9 && finite <= 1.35 + 1e-9)
            }
        }
        // 1.35x OUT alone looks bounded, but the reciprocal IN is below .75.
        assertFalse(LoopMix.qualifyCrown(LoopCrownFixtures.plan(120.0, 162.0)).accepted)
        assertFalse(LoopMix.qualifyCrown(LoopCrownFixtures.plan(120.0, 89.9)).accepted)
    }

    @Test fun actualRateEndpointsAreInclusiveWithoutWideningTheEnvelope() {
        val p = LoopCrownFixtures.plan()
        fun withRate(outRate: Double, inRate: Double): LoopMixSession.Companion.CrownKnotQualification {
            val k = LoopCrownFixtures.knots(p)
            for (i in k.outFrames.indices) k.outPositionsSec[i] = p.out.loopStart +
                k.outFrames[i] / LoopCrownFixtures.SAMPLE_RATE * outRate
            for (i in k.inFrames.indices) k.inPositionsSec[i] = p.inn.loopStart +
                k.inFrames[i] / LoopCrownFixtures.SAMPLE_RATE * inRate
            return LoopCrownFixtures.qualify(p, k)
        }
        val atBoundary = withRate(.75, 1.35)
        assertTrue(atBoundary.accepted)
        assertEquals(.75, requireNotNull(atBoundary.outMinRate), 1e-10)
        assertEquals(1.35, requireNotNull(atBoundary.inMaxRate), 1e-10)
        assertEquals("crown_actual_out_rate", withRate(.75 - 1e-6, 1.0).refusal)
        assertEquals("crown_actual_in_rate", withRate(1.0, 1.35 + 1e-6).refusal)
    }

    @Test fun invalidOutputTimesAndSourceCoordinatesCannotBecomeGoodKnots() {
        val p = LoopCrownFixtures.plan()
        for (out in listOf(true, false)) {
            for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
                val k = LoopCrownFixtures.knots(p)
                (if (out) k.outFrames else k.inFrames)[2] = bad
                val q = LoopCrownFixtures.qualify(p, k)
                assertFalse(q.accepted)
                assertEquals("crown_actual_${if (out) "out" else "in"}_geometry", q.refusal)
                assertTrue(listOf(q.outMinRate, q.outMaxRate, q.inMinRate, q.inMaxRate)
                    .all { it == null || it.isFinite() })
                val positions = LoopCrownFixtures.knots(p)
                (if (out) positions.outPositionsSec else positions.inPositionsSec)[2] = bad
                assertFalse(LoopCrownFixtures.qualify(p, positions).accepted)
            }
            for (reverse in listOf(false, true)) {
                val k = LoopCrownFixtures.knots(p)
                val frames = if (out) k.outFrames else k.inFrames
                frames[2] = frames[1] - if (reverse) 1.0 else 0.0
                assertFalse(LoopCrownFixtures.qualify(p, k).accepted)
            }
        }
        val c = LoopMix.Clock(p)
        val k = LoopCrownFixtures.knots(p, c)
        assertFalse(LoopMixSession.qualifyCrownKnots(p, c, 0, k).accepted)
        assertFalse(LoopCrownFixtures.qualify(p, LoopMixSession.Companion.DeckKnots(
            DoubleArray(0), DoubleArray(0), k.inFrames, k.inPositionsSec), c).accepted)
        assertFalse(LoopCrownFixtures.qualify(p, LoopMixSession.Companion.DeckKnots(
            k.outFrames, k.outPositionsSec.copyOf(k.outPositionsSec.size - 1), k.inFrames, k.inPositionsSec), c).accepted)
        k.inPositionsSec[0] += .01
        assertEquals("crown_actual_in_geometry", LoopCrownFixtures.qualify(p, k, c).refusal)
    }

    @Test fun onlyDeclaredInitialIncomingSilentHoldIsExempt() {
        // One full incoming loop held before its start consumes the matching
        // source loop count; a shorter arbitrary hold would violate the
        // existing whole-IN-loop release contract.
        val p = LoopCrownFixtures.plan().copy(incomingHoldSec = 8.0)
        val c = LoopMix.Clock(p)
        val k = LoopCrownFixtures.knots(p, c)
        assertEquals(k.inPositionsSec[0], k.inPositionsSec[2], 0.0)
        assertTrue(LoopCrownFixtures.qualify(p, k, c).accepted)
        assertEquals(1.0, requireNotNull(LoopCrownFixtures.qualify(p, k, c).inMinRate), 1e-10)
        val undeclared = p.copy(incomingHoldSec = 0.0)
        assertEquals("crown_actual_in_nonadvancing", LoopCrownFixtures.qualify(undeclared, k).refusal)
        for (out in listOf(false, true)) for (backwards in listOf(false, true)) {
            val changed = LoopCrownFixtures.knots(p, c)
            val positions = if (out) changed.outPositionsSec else changed.inPositionsSec
            positions[20] = positions[19] - if (backwards) .01 else 0.0
            assertEquals("crown_actual_${if (out) "out" else "in"}_nonadvancing",
                LoopCrownFixtures.qualify(p, changed, c).refusal)
        }
        val outHold = LoopCrownFixtures.knots(p, c)
        outHold.outPositionsSec[1] = outHold.outPositionsSec[0]
        assertEquals("crown_actual_out_nonadvancing", LoopCrownFixtures.qualify(p, outHold, c).refusal)
        val beyondDeclared = LoopCrownFixtures.knots(p, c)
        beyondDeclared.inPositionsSec[17] = p.inn.loopStart
        assertEquals("crown_actual_in_nonadvancing", LoopCrownFixtures.qualify(p, beyondDeclared, c).refusal)
        val neverActive = LoopCrownFixtures.knots(p, c)
        neverActive.inPositionsSec.fill(p.inn.loopStart)
        val foreverHold = p.copy(incomingHoldSec = c.elementAt + 1.0)
        assertEquals("crown_actual_in_nonadvancing", LoopCrownFixtures.qualify(foreverHold, neverActive).refusal)
    }

    @Test fun incomingNativeRateReleaseAndSettleAreIncluded() {
        for (p in listOf(LoopCrownFixtures.plan(111.0, 169.0, 2.0),
            LoopCrownFixtures.plan(170.0, 80.0, .5))) {
            val c = LoopMix.Clock(p)
            val k = LoopCrownFixtures.knots(p, c)
            assertTrue(LoopCrownFixtures.qualify(p, k, c).accepted)
            for (i in c.beats + 1 until k.inFrames.size) {
                assertEquals(1.0, LoopCrownFixtures.SAMPLE_RATE *
                    (k.inPositionsSec[i] - k.inPositionsSec[i - 1]) /
                    (k.inFrames[i] - k.inFrames[i - 1]), 1e-9)
            }
            val g = LoopMixSession.incomingGeometry(p, c, k)
            assertTrue(g.loops)
            assertEquals(c.elementTrackTime, g.handoverTrack, 1e-8)
            k.inPositionsSec[k.inPositionsSec.lastIndex] += 1.0
            assertEquals("crown_actual_in_rate", LoopCrownFixtures.qualify(p, k, c).refusal)
        }
    }

    @Test fun nonCrownRoutesDoNotAcquireAnActualKnotGate() {
        val p = LoopCrownFixtures.plan().copy(inLoops = false, rung = "Echo", nativeIncoming = true)
        val empty = LoopMixSession.Companion.DeckKnots(DoubleArray(0), DoubleArray(0), DoubleArray(0), DoubleArray(0))
        val q = LoopCrownFixtures.qualify(p, empty)
        assertTrue(q.accepted)
        assertNull(q.outMinRate)
        assertNull(q.inMinRate)
    }

    @Test fun buildRefusesActualKnotsBeforeDecodeOrRendererConstruction() {
        val p = LoopCrownFixtures.plan(120.0, 160.0)
        val decoded = AtomicInteger()
        val refusals = mutableListOf<String>()
        val session = LoopMixSession.build(p, "out", "in", LoopCrownFixtures.SAMPLE_RATE, 1f,
            outAnalysis = LoopCrownFixtures.irregularOut(p), onRefusal = { refusals += it },
        ) { _, _, _ -> decoded.incrementAndGet(); error("Refused geometry must not decode") }
        assertNull(session)
        assertEquals(0, decoded.get())
        assertEquals(listOf("crown_actual_out_rate"), refusals)
    }

    @Test fun buildReportsNominalRefusalBeforeRiskyClockGeometryOrDecode() {
        val p = LoopCrownFixtures.plan().copy(fold = Double.NaN)
        val nominal = LoopMix.qualifyCrown(p)
        assertFalse(nominal.accepted)
        val refusals = mutableListOf<String>()
        val session = LoopMixSession.build(p, "out", "in", LoopCrownFixtures.SAMPLE_RATE, 1f,
            onRefusal = { refusals += it }, decode = { _, _, _ -> error("Invalid fold must not decode") })
        assertNull(session)
        assertEquals(listOf("crown_nominal_${nominal.refusal}"), refusals)
    }
}
