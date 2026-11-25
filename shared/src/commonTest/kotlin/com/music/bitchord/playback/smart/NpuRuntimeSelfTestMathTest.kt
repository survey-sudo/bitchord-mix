package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals

class NpuRuntimeSelfTestMathTest {
    @Test
    fun compareLogitsRejectsNonFiniteValuesAndBadCounts() {
        val posInf = Float.POSITIVE_INFINITY
        val negInf = Float.NEGATIVE_INFINITY
        for (pair in listOf(
            floatArrayOf(Float.NaN, 0f),
            floatArrayOf(posInf, posInf),
            floatArrayOf(posInf, negInf),
            floatArrayOf(negInf, negInf),
        )) {
            val diff = NpuRuntime.compareLogits(floatArrayOf(pair[0]), floatArrayOf(pair[1]))
            assertEquals(1, diff.count)
            assertEquals(Double.POSITIVE_INFINITY, diff.maxAbs)
            assertEquals(Double.POSITIVE_INFINITY, diff.meanAbs)
            assertEquals(NpuVerdict.MISMATCH, NpuRuntime.judgeSelfTest(diff, 1.0, 1.0, 2.0))
        }
        assertEquals(0, NpuRuntime.compareLogits(floatArrayOf(), floatArrayOf()).count)
    }

    @Test
    fun judgeSelfTestFailsClosedForInvalidCountsAndTimings() {
        val good = NpuRuntime.LogitDiff(maxAbs = 0.1, meanAbs = 0.01, count = 10)
        val badCounts = good.copy(count = 0)
        val negativeCounts = good.copy(count = -1)
        for (diff in listOf(badCounts, negativeCounts)) {
            assertEquals(NpuVerdict.MISMATCH, NpuRuntime.judgeSelfTest(diff, 1.0, 1.0, 2.0))
        }
        for (time in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            assertEquals(NpuVerdict.MISMATCH, NpuRuntime.judgeSelfTest(good, 1.0, time, 2.0))
            assertEquals(NpuVerdict.MISMATCH, NpuRuntime.judgeSelfTest(good, 1.0, 1.0, time))
        }
    }

    @Test
    fun judgeSelfTestRequiresFinitePeakAgreementAndAcceptsValidParity() {
        val good = NpuRuntime.LogitDiff(maxAbs = 0.1, meanAbs = 0.01, count = 10)
        for (peak in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.1, 1.1)) {
            assertEquals(NpuVerdict.MISMATCH, NpuRuntime.judgeSelfTest(good, peak, 1.0, 2.0))
        }
        assertEquals(NpuVerdict.PASS, NpuRuntime.judgeSelfTest(good, 1.0, 1.0, 2.0))
        assertEquals(NpuVerdict.SLOW, NpuRuntime.judgeSelfTest(good, 1.0, 2.0, 1.0))
    }
}
