package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * vc69 P1 release-to-dry splice: 0.25 s cosine handback (Orchard stage.rs
 * native edge) that never clicks at the seam.
 */
class ReleaseSpliceTest {

    @Test
    fun spliceWeightHoldsThenFallsCosine() {
        assertEquals(1.0, ReleaseSplice.spliceWeight(0, 100), 1e-9)
        assertEquals(1.0, ReleaseSplice.spliceWeight(-5, 100), 1e-9)
        assertEquals(0.0, ReleaseSplice.spliceWeight(100, 100), 1e-9)
        assertEquals(0.0, ReleaseSplice.spliceWeight(150, 100), 1e-9)
        // Cosine midpoint of the window is half-level, and the ends are flat.
        assertEquals(0.5, ReleaseSplice.spliceWeight(50, 100), 1e-9)
        assertTrue(ReleaseSplice.spliceWeight(25, 100) > ReleaseSplice.spliceWeight(75, 100))
    }

    @Test
    fun spliceGainRespectsWindow() {
        assertEquals(1.0, ReleaseSplice.spliceGain(2.0, 2.0), 1e-9)
        assertEquals(1.0, ReleaseSplice.spliceGain(1.9, 2.0), 1e-9)
        assertEquals(0.0, ReleaseSplice.spliceGain(2.25, 2.0), 1e-9)
        assertEquals(0.0, ReleaseSplice.spliceGain(3.0, 2.0), 1e-9)
        assertEquals(0.5, ReleaseSplice.spliceGain(2.125, 2.0), 1e-9)
    }

    @Test
    fun envelopePointsHoldUntilReleaseAndEndAtZero() {
        val points = ReleaseSplice.envelopePoints(releaseSec = 10.0, widthSec = 0.25, steps = 4)
        assertEquals(1.0, points.first().second, 1e-9)
        assertEquals(0.0, points.last().second, 1e-9)
        assertEquals(10.25, points.last().first, 1e-9)
        var previous = Double.POSITIVE_INFINITY
        for (point in points) {
            assertTrue(point.second <= previous)
            previous = point.second
        }
    }

    @Test
    fun envelopeArraysAreShapedForTheRenderer() {
        val (times, values) = ReleaseSplice.envelopeArrays(releaseSec = 4.0)
        assertEquals(times.size, values.size)
        assertEquals(values.last().toDouble(), 0.0, 1e-9)
    }

    @Test
    fun spliceWidthMatchesOrchardNativeEdge() {
        assertEquals(0.25, ReleaseSplice.SPLICE_SECONDS, 1e-9)
        assertEquals(12000L, ReleaseSplice.spliceFrames(48_000))
    }
}
