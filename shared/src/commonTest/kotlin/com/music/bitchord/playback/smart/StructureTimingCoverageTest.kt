package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StructureTimingCoverageTest {
    @Test
    fun measuredPhaseAndMeterAreRetained() {
        val map = StructureAnalyzer.fromPcm(
            trackKey = "phase",
            pcm = FloatArray(22050 * 12),
            sampleRate = 22050,
            bpm = 120.0,
            downbeats = DoubleArray(8) { 0.25 + it * 2.0 },
            beatsPerBar = 3,
            gridConfidence = 0.91f,
            tempoStable = false,
            gridMeasured = true,
        )
        assertEquals(3, map.beatsPerBar)
        assertEquals(0.25, map.downbeats.first(), 0.0001)
        assertEquals(0.91f, map.gridConfidence)
        assertFalse(map.tempoStable)
        assertTrue(map.coarse)
        assertTrue(map.gridMeasured)
    }

    @Test
    fun syntheticGridCannotClaimMeasuredConfidence() {
        val map = StructureAnalyzer.fromPcm(
            "synthetic", FloatArray(22050 * 8), 22050, 120.0,
            gridConfidence = 0.99f, gridMeasured = false,
        )
        assertTrue(map.gridConfidence <= 0.35f)
        assertFalse(map.gridMeasured)
    }

    @Test
    fun measuredMaskMergeLeavesNeutralGapsUntouched() {
        val merged = SongMaps.mergeMeasuredMasks(
            8,
            listOf(doubleArrayOf(-1.0, 0.2, 0.2, -1.0, -1.0, -1.0, -1.0, -1.0),
                doubleArrayOf(-1.0, -1.0, -1.0, 0.8, 0.8, -1.0, -1.0, -1.0)),
            -1.0,
        )!!
        assertEquals(-1.0, merged[0])
        assertEquals(-1.0, merged[6])
        assertTrue(merged[2] in 0.19..0.21)
        assertTrue(merged[3] in 0.19..0.81)
    }
}
