package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.ceil

class LoopSourceGeometryTest {
    private fun plan(bars: Int = 1, fold: Double = 1.0, loops: Boolean = true,
        native: Boolean = false, hold: Double = 0.0): LoopMix.Plan {
        val pick = LoopMix.Pick(3.875, 3.875 + bars * 4 * .4775, bars, .4775,
            60 / .4775, 0.0, LoopMix.Check.MASK)
        return LoopMix.Plan(pick, pick, fold, 2, 4, 2, 8,
            inLoops = loops, nativeIncoming = native, incomingHoldSec = hold)
    }
    private fun knots(p: LoopMix.Plan, analysis: TrackAnalysis? = null) = LoopMix.Clock(p).let { c ->
        LoopMixSession.buildDeckKnots(p, c, 48_000, null, analysis, false, c.releaseAt, 1.0)
    }

    @Test fun repeatedOneBarUsesPhysicalHandoverAndFullPhysicalCoverage() {
        val p = plan()
        val c = LoopMix.Clock(p)
        val k = knots(p)
        val geometry = LoopMixSession.incomingGeometry(p, c, k)
        assertEquals(22.975, k.inPositionsSec.last(), 1e-9)
        assertEquals(9.605, geometry.handoverTrack, 1e-9)
        assertEquals(19.155, geometry.releaseTrack, 1e-9)
        val (startAt, startTrack) = LoopMixSession.incomingStartFor(c, geometry.handoverTrack)
        assertEquals(.5, startAt, 1e-9)
        assertEquals(0.0, startTrack, 1e-9) // negative track time is sink-aligned silence
        val bounds = LoopMix.regionBounds(p)
        val samples = ceil((bounds.inTo - bounds.inFrom) * 48_000).toInt()
        assertTrue(LoopMixSession.coversPhysical(LoopRegion(FloatArray(samples), FloatArray(samples), bounds.inFrom),
            48_000, p.inn.loopStart, geometry.requiredEnd))
        assertTrue(geometry.requiredEnd < k.inPositionsSec.last())
    }

    @Test fun heldKnotsRemainMonotonicAndNativeIncomingIsNeverDoubleMapped() {
        for (bars in listOf(1, 2, 4)) for (fold in listOf(.5, 1.0, 2.0))
        for (loop in listOf(false, true)) for (native in listOf(false, true)) {
            val p = plan(bars, fold, loop, native, 1.25)
            val c = LoopMix.Clock(p)
            val k = knots(p)
            val g = LoopMixSession.incomingGeometry(p, c, k)
            assertTrue(k.inPositionsSec.asList().zipWithNext().all { (a, b) -> b >= a })
            assertEquals(k.inPositionsSec[c.beats], g.releaseTrack, 1e-9)
            if (native || !loop) {
                assertFalse(g.loops)
                assertEquals(k.inPositionsSec.last(), g.handoverTrack, 1e-9)
            } else assertEquals(c.elementTrackTime, g.handoverTrack, 1e-8)
        }
    }

    @Test fun trackedFinalKnotAndRelaxationDefineActualCoverage() {
        val p = plan(loops = false)
        val tracked = List(70) { p.inn.loopStart + it * .49 }
        val analysis = TrackAnalysis(headBeats = BeatSide(tracked, emptyList(), 60 / .49, .9))
        val c = LoopMix.Clock(p)
        val k = knots(p, analysis)
        val g = LoopMixSession.incomingGeometry(p, c, k)
        assertEquals(tracked[c.beats], g.releaseTrack, 1e-9)
        assertEquals(k.inPositionsSec.last(), g.handoverTrack, 1e-9)
        assertTrue(g.handoverTrack > c.elementTrackTime)
        assertTrue(g.requiredEnd >= g.handoverTrack + .025)
        val lastRate = (k.inPositionsSec.last() - k.inPositionsSec[k.inPositionsSec.lastIndex - 1]) /
            ((k.inFrames.last() - k.inFrames[k.inFrames.lastIndex - 1]) / 48_000)
        assertEquals(1.0, lastRate, 1e-8)
    }

    @Test fun emptyUnequalTruncatedOrLateRegionsAreRejectedWithoutDemandingPreroll() {
        fun covers(left: Int, right: Int = left, start: Double = 3.0) =
            LoopMixSession.coversPhysical(LoopRegion(FloatArray(left), FloatArray(right), start), 1000, 3.0, 5.0)
        assertFalse(covers(0))
        assertFalse(covers(2100, 2099))
        assertFalse(covers(1900))
        assertFalse(covers(2100, start = 3.01))
        assertTrue(covers(2000)) // no 1 s decoder preroll required as audible music
    }

    @Test fun nativeAndScriptedEchoAllowRealSongEndDuringStoredEffectDrain() {
        // echoPlan's 1/0/1-bar geometry, beat=.5, 100 s song / rawStart95.7.
        val pick = LoopMix.Pick(95.7, 95.7, 1, .5, 120.0, 0.0, LoopMix.Check.MASK)
        val p = LoopMix.Plan(pick, pick.copy(loopStart = 0.0, loopEnd = 0.0),
            1.0, 1, 0, 1, 2, outLoops = false, inLoops = false, nativeIncoming = true,
            echoTail = true, rung = "Echo", targetBpmOverride = 120.0, incomingHoldSec = 2.0)
        val c = LoopMix.Clock(p)
        val stop = c.releaseAt + .05 + p.out.beatSec * 8
        val k = LoopMixSession.buildDeckKnots(p, c, 1000, null, null, true, stop, 1.0)
        assertEquals(103.75, k.outPositionsSec.last(), 1e-9)
        val full = LoopRegion(FloatArray(5300), FloatArray(5300), 94.7) // actual EOF100
        assertTrue(LoopMixSession.coversOutgoingStartAndLoop(full, 1000, p))
        // Compiled M6 zero-overlap geometry: content end99.5/start97.5,
        // release2, echoHold4, exitTail1, renderer stop7.05/final104.55.
        val compiled = p.copy(out = pick.copy(loopStart = 97.5, loopEnd = 97.5),
            enterBars = 0, rampBars = 0, totalBars = 1, exitTailSeconds = 1.0)
        val cc = LoopMix.Clock(compiled)
        val cs = cc.releaseAt + .05 + 4 + 1
        val ck = LoopMixSession.buildDeckKnots(compiled, cc, 1000, null, null, true, cs, 1.0)
        assertEquals(104.55, ck.outPositionsSec.last(), 1e-9)
        assertTrue(LoopMixSession.coversOutgoingStartAndLoop(full, 1000, compiled))
    }

    @Test fun outgoingRequiresValidStartingSourceAndCompleteLoopBodyButNotStraightTail() {
        val p = plan()
        val start = p.out.loopStart
        val short = LoopRegion(FloatArray(1200), FloatArray(1200), start - 1)
        assertFalse(LoopMixSession.coversOutgoingStartAndLoop(short, 1000, p))
        assertTrue(LoopMixSession.coversOutgoingStartAndLoop(short, 1000, p.copy(outLoops = false)))
        val noStart = LoopRegion(FloatArray(1000), FloatArray(1000), start - 1)
        assertFalse(LoopMixSession.coversOutgoingStartAndLoop(noStart, 1000, p.copy(outLoops = false)))
        val unequal = LoopRegion(FloatArray(4000), FloatArray(3999), start - 1)
        assertFalse(LoopMixSession.coversOutgoingStartAndLoop(unequal, 1000, p))
        val full = LoopRegion(FloatArray(4000), FloatArray(4000), start - 1)
        assertTrue(LoopMixSession.coversOutgoingStartAndLoop(full, 1000, p))
    }
}
