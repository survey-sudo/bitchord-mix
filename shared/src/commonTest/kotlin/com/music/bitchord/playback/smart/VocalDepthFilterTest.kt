package com.music.bitchord.playback.smart

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * vc69 P1 vocal-depth filter ride: the Orchard running-maximum depth model
 * (earmark dsp filters.rs) fed by vocal presence over the overlap.
 */
class VocalDepthFilterTest {

    private fun vocally(presence: Double, duration: Double = 10.0): TrackAnalysis {
        val curve = (0..20).map { EnergySample(it * duration / 20.0, 0.8) }
        val mask = curve.map { presence }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "t",
            duration = duration,
            vocalActivityMask = mask,
            energyCurve = curve,
            vocalProbability = presence,
        )
    }

    @Test
    fun presenceInterpolatesOnTheMaskGrid() {
        val curve = listOf(EnergySample(0.0, 0.8), EnergySample(10.0, 0.8))
        val analysis = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "g",
            duration = 10.0,
            vocalActivityMask = listOf(0.0, 1.0),
            energyCurve = curve,
        )
        assertEquals(0.0, VocalDepthFilter.presenceAt(analysis, 0.0) ?: -1.0, 1e-9)
        assertEquals(0.5, VocalDepthFilter.presenceAt(analysis, 5.0) ?: -1.0, 1e-9)
        assertEquals(1.0, VocalDepthFilter.presenceAt(analysis, 10.0) ?: -1.0, 1e-9)
    }

    @Test
    fun presenceIsNullWithoutEvidence() {
        val bare = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "bare")
        assertEquals(null, VocalDepthFilter.presenceAt(bare, 3.0))
        assertEquals(null, VocalDepthFilter.presenceOrScalar(bare, 3.0))
    }

    @Test
    fun depthThresholdsAndScalesPresence() {
        assertEquals(0.0, VocalDepthFilter.depthForPresence(0.0), 1e-9)
        assertEquals(0.0, VocalDepthFilter.depthForPresence(0.35), 1e-9)
        assertEquals(1.0, VocalDepthFilter.depthForPresence(0.75), 1e-9)
        // Unmeasured spends fully: legacy rides are untouched by no evidence.
        assertEquals(1.0, VocalDepthFilter.depthForPresence(null), 1e-9)
    }

    @Test
    fun runningMaxNeverReopens() {
        val curve = VocalDepthFilter.runningMax(listOf(0.2, 0.9, 0.1, 0.4))
        assertEquals(listOf(0.2, 0.9, 0.9, 0.9), curve)
    }

    @Test
    fun travelIsGeometricInOctaves() {
        val origin = 20000.0
        val target = 700.0
        assertEquals(origin, VocalDepthFilter.scaleTravel(origin, target, 0.0), 1e-9)
        assertEquals(target, VocalDepthFilter.scaleTravel(origin, target, 1.0), 1e-9)
        assertEquals(origin * sqrt(target / origin), VocalDepthFilter.scaleTravel(origin, target, 0.5), 1e-6)
    }

    @Test
    fun curveIsEmptyWithoutEvidenceAndFullWhenPresent() {
        val silent = vocally(0.0)
        val loud = vocally(0.9)
        assertTrue(VocalDepthFilter.depthCurve(silent, silent, 0.0, 10.0, 0.0).isEmpty() ||
            VocalDepthFilter.depthCurve(silent, silent, 0.0, 10.0, 0.0).all { it == 0.0 })
        val curve = VocalDepthFilter.depthCurve(loud, loud, 0.0, 10.0, 0.0)
        assertTrue(curve.isNotEmpty())
        assertTrue(curve.all { it >= 0.5 }, "loud leads must spend depth")
        val maxed = VocalDepthFilter.runningMax(curve)
        assertTrue(maxed.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun emptyCurveReadsAsFullSpend() {
        assertEquals(1.0, VocalDepthFilter.valueAtCurve(emptyList(), 0.5), 1e-9)
        assertEquals(0.25, VocalDepthFilter.valueAtCurve(listOf(0.25), 0.9), 1e-9)
    }

    @Test
    fun valueAtCurveInterpolates() {
        val curve = listOf(0.0, 1.0)
        assertEquals(0.5, VocalDepthFilter.valueAtCurve(curve, 0.5), 1e-9)
        assertEquals(0.0, VocalDepthFilter.valueAtCurve(curve, 0.0), 1e-9)
        assertEquals(1.0, VocalDepthFilter.valueAtCurve(curve, 1.0), 1e-9)
    }

    @Test
    fun withVocalDepthFillsBlendPlansOnly() {
        val loud = vocally(0.9)
        val plan = TransitionPlan(
            transitionStyle = TransitionStyle.DJ_BLEND,
            transitionStart = 0.0,
            transitionEnd = 10.0,
            fadeSeconds = 10.0,
            incomingCueTime = 0.0,
        )
        val dressed = VocalDepthFilter.withVocalDepth(plan, loud, loud)
        assertTrue(dressed.vocalDepthCurve.isNotEmpty())
        val muzzled = plan.copy(transitionStyle = TransitionStyle.MUFFLE)
        assertEquals(emptyList(), VocalDepthFilter.withVocalDepth(muzzled, loud, loud).vocalDepthCurve)
    }
}
