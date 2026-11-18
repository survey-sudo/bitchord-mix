package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StructureTimingCoverageTest {
    @Test
    fun sparseHeadAndTailKeepTheirActualBarIndices() {
        val observed = doubleArrayOf(0.25, 2.25, 4.25, 100.25, 102.25)
        val grid = StructureAnalyzer.completeBarGrid(observed, 2.0, 104.25)
        assertEquals(100.25, grid.times[50], 1e-9)
        assertEquals(102.25, grid.times[51], 1e-9)
        assertTrue(grid.times.toList().zipWithNext().all { (a, b) -> b > a && b - a <= 2.0 + 1e-9 })
        assertTrue(grid.coverage[50])
        assertFalse(grid.coverage[25])
        assertFalse(grid.stable, "sparse evidence cannot establish whole-track stability")

        val analysis = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, trackId = "sparse", duration = 104.25,
            bpm = 120.0, beatInterval = 0.5, beatConfidence = 0.9,
            downbeats = observed.toList(), quality = TrackAnalysis.QUALITY_FULL,
            vocalWindows = listOf(
                CurveWindow(0.25, 10.0, FloatArray(40)),
                CurveWindow(100.25, 10.0, FloatArray(40) { 0.8f }),
            ),
        )
        val map = SongMaps.fromAnalysis("sparse", analysis)!!
        assertEquals(100.25, map.barTime(50), 1e-9)
        assertTrue(map.sungBars[50])
        assertEquals(0.0, map.vocalEnergyMeasuredAt(0))
        assertNull(map.vocalEnergyMeasuredAt(25))
        assertTrue(map.vocalEnergyMeasuredAt(50)!! > 0.7)
        assertEquals(map, map.copy())
        assertEquals(map.hashCode(), map.copy().hashCode())
        val newCoverage = map.vocalCoverage.copyOf().also { it[25] = true }
        assertNotEquals(map, map.copy(vocalCoverage = newCoverage))
        assertNotEquals(map, map.copy(downbeats = map.downbeats.copyOf().also { it[0] += 0.1 }))
    }

    @Test
    fun gridCompletionIsBoundedAndRejectsInvalidTimescale() {
        val bounded = StructureAnalyzer.completeBarGrid(doubleArrayOf(0.25, 2.25, 1e12), 2.0, 1e12)
        assertTrue(bounded.times.size <= 513)
        assertTrue(bounded.times.all { it.isFinite() })
        assertTrue(bounded.times.toList().zipWithNext().all { (a, b) -> b > a })
        assertFailsWith<IllegalArgumentException> {
            StructureAnalyzer.completeBarGrid(doubleArrayOf(0.0), Double.NaN, 20.0)
        }
    }

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
