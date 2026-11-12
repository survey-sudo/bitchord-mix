package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GridRepairTest {
    @Test
    fun aWindowOneBeatLateIsShiftedBackWhenTheTempoAgrees() {
        val beat = 0.5
        val first = (0 until 16).map { it * beat }
        val second = (8 until 24).map { it * beat + beat }
        val joined = GridRepair.reconcile(
            listOf(
                GridRepair.Window(first, first.filterIndexed { index, _ -> index % 4 == 0 }, 120.0),
                GridRepair.Window(second, second.filterIndexed { index, _ -> index % 4 == 0 }, 120.0),
            ),
        )
        assertTrue(joined.beats.size >= 16)
        val gaps = joined.beats.zipWithNext { left, right -> right - left }
        assertTrue(gaps.all { abs(it - beat) < 0.05 }, gaps.toString())
    }

    @Test
    fun beatsSnapOntoOnsetsByTheMedianError() {
        val beats = (0 until 16).map { 1.0 + it * 0.5 }
        val onsets = beats.map { it + 0.02 }
        val snapped = GridRepair.snapToOnsets(beats, onsets)
        assertEquals(0.02, snapped[0] - beats[0], 1e-9)
        assertEquals(0.02, snapped[15] - beats[15], 1e-9)
    }

    @Test
    fun aScatteredOnsetFieldDoesNotMoveTheGrid() {
        val beats = (0 until 16).map { 1.0 + it * 0.5 }
        val onsets = beats.mapIndexed { index, beat -> beat + if (index % 2 == 0) 0.2 else -0.2 }
        assertEquals(beats, GridRepair.snapToOnsets(beats, onsets))
    }
}
