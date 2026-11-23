package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.nativeTiming
import com.music.bitchord.playback.smart.padLateStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class LoopMixSessionCoversHandoffPcmTest {
    private val rate = 48_000

    private fun handoff() = requireNotNull(
        nativeTiming(0.0, 0.5, 5.0, 6.0, 1.0, 1.0, 1.0, 2.0, 0.0, 0.5)
    )

    private fun plan(): LoopMix.Plan {
        val h = handoff()
        return LoopMix.Plan(
            out = LoopMix.Pick(0.5, 0.5, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
            inn = LoopMix.Pick(1.0, 1.0, 4, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
            fold = 1.0, enterBars = 1, rampBars = 0, exitBars = 3, totalBars = 4,
            outLoops = false, inLoops = false, nativeIncoming = true,
            handoff = h, incomingHoldSec = h.inHoldMixSec,
        )
    }

    private fun knots(): LoopMixSession.Companion.DeckKnots {
        // Linear, monotonic source/mix mapping: source second == mix second.
        val n = 8 * rate
        val frames = DoubleArray(2) { (it * n).toDouble() }
        val positions = DoubleArray(2) { it * 8.0 }
        return LoopMixSession.Companion.DeckKnots(frames, positions, frames.copyOf(), positions.copyOf())
    }

    private fun outRegion(actualEnd: Double): LoopRegion {
        val start = 0.5
        val size = ((actualEnd - start) * rate).roundToInt()
        return LoopRegion(FloatArray(size) { 0.1f }, FloatArray(size) { 0.1f }, start)
    }

    private fun inRegion(): LoopRegion {
        val size = (2.0 * rate).roundToInt()
        return LoopRegion(FloatArray(size) { 0.1f }, FloatArray(size) { 0.1f }, 1.0)
    }

    private fun covers(outEnd: Double, tolerance: Double) =
        LoopMixSession.coversHandoffPcm(plan(), rate, knots(), outRegion(outEnd), inRegion(), tolerance)

    @Test
    fun naturalEof60msShortPassesWithTolerance() {
        assertTrue(covers(6.0 - 0.060, 0.08))
    }

    @Test
    fun naturalEof100msShortFailsWithTolerance() {
        assertFalse(covers(6.0 - 0.100, 0.08))
    }

    @Test
    fun echo5msShortFailsWithoutTolerance() {
        assertFalse(covers(6.0 - 0.005, 0.0))
    }

    @Test
    fun padLateStartPadsElevenMsAndReanchors() {
        val left = FloatArray(10) { 1f }
        val right = FloatArray(10) { 2f }
        val (pl, pr, start) = padLateStart(left, right, 1.821, 1.81, 48_000.0)
        val expectedPad = ((0.011) * 48_000.0).roundToInt()
        assertEquals(10 + expectedPad, pl.size)
        assertEquals(10 + expectedPad, pr.size)
        assertEquals(1.81, start, 0.0)
        for (i in 0 until expectedPad) {
            assertEquals(0f, pl[i], 0f)
            assertEquals(0f, pr[i], 0f)
        }
        assertEquals(1f, pl[expectedPad], 0f)
    }

    @Test
    fun padLateStartDoesNotPadBeyondEightyMs() {
        val left = floatArrayOf(1f)
        val right = floatArrayOf(2f)
        val (pl, pr, start) = padLateStart(left, right, 1.90, 1.81, 48_000.0)
        assertEquals(1, pl.size)
        assertEquals(1, pr.size)
        assertEquals(1.90, start, 0.0)
    }

    @Test
    fun padLateStartLeavesEarlyStartUntouched() {
        val left = floatArrayOf(1f)
        val right = floatArrayOf(2f)
        val (pl, pr, start) = padLateStart(left, right, 1.75, 1.81, 48_000.0)
        assertEquals(1, pl.size)
        assertEquals(1, pr.size)
        assertEquals(1.75, start, 0.0)
    }
}
