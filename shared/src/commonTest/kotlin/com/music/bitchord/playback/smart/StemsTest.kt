package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** AUTOMIX-V2(B): the pure mixture-consistency and chunking math every stem path relies on. */
class StemsTest {

    private fun tone(n: Int, f: Double, amp: Float) = FloatArray(n) { (amp * sin(it * f)).toFloat() }

    @Test
    fun foldMakesStemsSumToDryExactly() {
        val dry = tone(10_000, 0.013, 0.8f)
        // Deliberately inconsistent estimates: they sum to 0.93 * dry plus a wobble.
        val vocals = FloatArray(dry.size) { dry[it] * 0.4f + 0.01f * sin(it * 0.7).toFloat() }
        val drums = FloatArray(dry.size) { dry[it] * 0.3f }
        val bass = FloatArray(dry.size) { dry[it] * 0.2f }
        val other = FloatArray(dry.size) { dry[it] * 0.03f }
        val residual = StemMath.foldResidualIntoOther(dry, vocals, drums, bass, other)
        for (i in dry.indices) {
            val sum = vocals[i] + drums[i] + bass[i] + other[i]
            assertEquals(dry[i], sum, 1e-6f, "sample $i")
            assertTrue(other[i].isFinite())
        }
        assertTrue(residual > 0.0 && residual < 0.05, "residual ratio $residual")
    }

    @Test
    fun foldWithMissingStemsIsInstrumentalComplement() {
        val dry = tone(2_000, 0.05, 0.5f)
        val vocals = FloatArray(dry.size) { dry[it] * 0.25f }
        val other = FloatArray(dry.size)
        StemMath.foldResidualIntoOther(dry, vocals, null, null, other)
        for (i in dry.indices) assertEquals(dry[i], vocals[i] + other[i], 1e-7f)
        // Silence is no residual, not a NaN.
        val silent = FloatArray(100)
        assertEquals(0.0, StemMath.foldResidualIntoOther(silent, null, null, null, FloatArray(100)))
    }

    @Test
    fun chunkPlanMatchesNativeGeometryAndWeightsPartitionUnity() {
        val total = 30 * 44_100
        val useful = 485_100
        val starts = StemMath.chunkStarts(total, useful, 0.12)
        assertEquals(3, starts.size, "30 s in 11 s SCNet chunks")
        assertEquals(0, starts.first())
        assertEquals(total, starts.last() + useful)
        var n = 0
        while (n < total) {
            var sum = 0.0
            for (j in starts.indices) {
                if (n >= starts[j] && n < starts[j] + useful) sum += StemMath.chunkWeight(starts, useful, j, n - starts[j])
            }
            assertTrue(abs(sum - 1.0) < 1e-5, "weights at $n sum to $sum")
            n += 4_999
        }
        assertEquals(1, StemMath.chunkStarts(1_000, useful, 0.12).size)
        assertEquals(0, StemMath.chunkStarts(0, useful, 0.12).size)
    }

    @Test
    fun fingerprintSeparatesDifferentAudio() {
        val a = tone(50_000, 0.01, 0.5f)
        assertEquals(StemMath.fingerprint(a, a), StemMath.fingerprint(a.copyOf(), a.copyOf()))
        // Not every sample is hashed, but the length and the lattice samples are.
        val shorter = a.copyOf(a.size - 1)
        assertTrue(StemMath.fingerprint(a, a) != StemMath.fingerprint(shorter, shorter))
        val stride = a.size / 4096
        val moved = a.copyOf().also { it[stride * 100] += 0.25f }
        assertTrue(StemMath.fingerprint(a, a) != StemMath.fingerprint(moved, a))
    }
}
