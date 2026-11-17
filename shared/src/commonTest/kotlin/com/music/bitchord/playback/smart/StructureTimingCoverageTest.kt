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
    fun dspPhaseGridRetainsPhaseButIsNotMeasured() {
        val map = StructureAnalyzer.fromPcm(
            "dsp-phase", FloatArray(22050 * 8), 22050, 120.0,
            downbeats = doubleArrayOf(0.25, 2.25, 4.25, 6.25),
            gridConfidence = 0.95f,
            gridMeasured = false,
        )
        assertEquals(0.25, map.downbeats.first(), 0.0001)
        assertTrue(map.gridConfidence <= 0.60f)
        assertFalse(map.gridMeasured)
        assertEquals("pcm:dsp-grid", map.provenance)
    }

    @Test
    fun nonUniformMeasuredGridPreservesDriftAndInstability() {
        val grid = doubleArrayOf(0.25, 2.25, 4.38, 6.38, 8.55, 10.55)
        val map = StructureAnalyzer.fromPcm(
            "drift", FloatArray(22050 * 12), 22050, 120.0,
            downbeats = grid,
            gridConfidence = 0.88f,
            tempoStable = false,
            gridMeasured = true,
        )
        assertEquals(0.25, map.downbeats[0], 0.0001)
        assertEquals(4.38, map.downbeats[2], 0.0001)
        assertFalse(map.tempoStable)
        assertEquals("pcm:model-grid", map.provenance)
    }

    @Test
    fun headAndTailWindowsDoNotMeasureTheMiddle() {
        val analysis = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = 64.0,
            bpm = 120.0,
            beatInterval = 0.5,
            vocalWindows = listOf(
                CurveWindow(0.0, 10.0, FloatArray(80) { 0.8f }),
                CurveWindow(60.0, 10.0, FloatArray(40) { 0.8f }),
            ),
        )
        val map = SongMaps.fromAnalysis("coverage", analysis)!!
        assertTrue(map.isVocalMeasured(0))
        assertTrue(map.isVocalMeasured(map.sungBars.lastIndex))
        assertFalse(map.isVocalMeasured(map.sungBars.size / 2))
    }

    @Test
    fun partialFinalBarRemainsOnThePhaseGrid() {
        val map = StructureAnalyzer.fromPcm(
            "partial-final-bar", FloatArray(22050 * 9), 22050, 120.0,
            downbeats = doubleArrayOf(0.25, 2.25, 4.25, 6.25, 8.25),
            gridConfidence = 0.8f,
            gridMeasured = true,
        )
        assertTrue(map.partial == false)
        assertEquals(8.25, map.downbeats.last(), 0.0001)
        assertTrue(map.downbeats.last() < map.durationSec)
        assertTrue(map.barTime(map.sungBars.size) > map.downbeats.last())
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
