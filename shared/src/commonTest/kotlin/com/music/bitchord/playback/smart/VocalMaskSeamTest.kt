package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class VocalMaskSeamTest {
    @Test
    fun overlappingMasksBlendWithoutFillingUnmeasuredGap() {
        val result = SongMaps.mergeMeasuredMasks(
            10,
            listOf(
                DoubleArray(10) { i -> if (i <= 5) 0.2 else -1.0 },
                DoubleArray(10) { i -> if (i >= 4) 0.8 else -1.0 },
            ),
            -1.0,
        )!!
        assertEquals(0.2, result[0], 0.0001)
        assertTrue(result[4] in 0.2..0.8)
        assertTrue(result[5] in 0.2..0.8)
        assertEquals(0.8, result[9], 0.0001)
    }

    @Test
    fun reversingMaskInputOrderDoesNotReverseTheCrossfade() {
        val head = DoubleArray(10) { i -> if (i <= 5) 0.2 else -1.0 }
        val tail = DoubleArray(10) { i -> if (i >= 4) 0.8 else -1.0 }
        val forward = SongMaps.mergeMeasuredMasks(10, listOf(head, tail), -1.0)!!
        val reversed = SongMaps.mergeMeasuredMasks(10, listOf(tail, head), -1.0)!!
        assertContentEquals(forward, reversed)
    }
}
