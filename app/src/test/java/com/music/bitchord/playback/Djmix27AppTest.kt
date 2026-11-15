package com.music.bitchord.playback

import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionAlternatives
import com.music.bitchord.playback.smart.TransitionEvidenceCache
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.TransitionTrackInfo
import com.music.bitchord.playback.smart.CrossfadeMode
import com.music.bitchord.playback.smart.planTransition
import com.music.bitchord.playback.smart.shouldDeclineToMix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc69 app suite (P0/P1/P2/NPU integration): decline rescues end to end
 * through planTransition, LoopMix flagged rungs for declined pairs, the
 * evidence cache across planning, and depth curve decoration. Pure Kotlin;
 * the native renderer is not needed (no native asserts run in this process).
 */
class Djmix27AppTest {

    private fun curves(duration: Double, valueAt: (Double) -> Double, step: Double = 1.0): List<EnergySample> {
        val count = (duration / step).toInt() + 1
        return (0 until count).map { EnergySample(it * step, valueAt(it * step)) }
    }

    private fun build(
        id: String,
        bpm: Double,
        key: String,
        keyConfidence: Double = 0.9,
        vocalProbability: Double = 0.9,
        bassAt: (Double) -> Double = { 1.0 },
        maskAt: (Double) -> Double = { 0.9 },
    ): TrackAnalysis {
        val duration = 200.0
        val beat = 60.0 / bpm
        val downbeats = buildList {
            var t = 0.5
            while (t <= duration) {
                add(t)
                t += 4.0 * beat
            }
        }
        val energy = curves(duration, { it -> 0.8 })
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = 0.9,
            downbeats = downbeats,
            firstBeat = 2.5,
            key = key,
            keyConfidence = keyConfidence,
            contentEndTime = duration - 4.0,
            energyCurve = energy,
            vocalActivityMask = energy.map { maskAt(it.time) },
            vocalProbability = vocalProbability,
            lowEnergyCurve = curves(duration, bassAt),
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    private fun plan(out: TrackAnalysis, inn: TrackAnalysis): TransitionPlan = planTransition(
        analysis = out,
        nextAnalysis = inn,
        currentTrack = TransitionTrackInfo(out.trackId, 200_000L),
        nextTrack = TransitionTrackInfo(inn.trackId, 200_000L),
        currentTime = 10.0,
        duration = 200.0,
        mode = CrossfadeMode.SMART,
    )

    @Test
    fun muffleSurvivesPlanTransitionEndToEnd() {
        val out = build("out", 120.0, "C major")
        val inn = build("inn", 109.0, "C major", maskAt = { if (it < 5.5) 0.1 else 0.9 })
        assertTrue(shouldDeclineToMix(out, inn))
        val p = plan(out, inn)
        assertEquals(TransitionStyle.MUFFLE, p.transitionStyle)
        assertEquals(8, p.muffleLeadBeats)
        assertTrue("muffle return span", p.exitBeats > 0)
        assertEquals("muffle", p.alternativeKind)
    }

    @Test
    fun dropInSurvivesPlanTransitionEndToEnd() {
        val out = build("out", 120.0, "C major", vocalProbability = 0.1, maskAt = { 0.0 })
        val inn = build("inn", 109.0, "Eb major", vocalProbability = 0.1, maskAt = { 0.0 })
        val p = plan(out, inn)
        assertEquals(TransitionStyle.DROP_IN, p.transitionStyle)
        assertEquals("drop-in", p.alternativeKind)
    }

    @Test
    fun beatlessBridgeSurvivesPlanTransitionEndToEnd() {
        val out = build("out", 120.0, "C major", bassAt = { if (it >= 180.0) 0.05 else 1.0 }, maskAt = { 0.0 })
        val inn = build("inn", 94.0, "C major", bassAt = { if (it < 8.0) 0.05 else 1.0 }, maskAt = { 0.0 })
        val p = plan(out, inn)
        assertEquals(TransitionStyle.BEATLESS_BRIDGE, p.transitionStyle)
        assertTrue("bridge return span", p.exitBeats > 0)
        assertEquals("beatless-bridge", p.alternativeKind)
    }

    @Test
    fun declineWithoutCurvesKeepsBoundedFourSecondFilter() {
        val out = build("out", 120.0, "C major").copy(lowEnergyCurve = emptyList())
        val inn = build("inn", 94.0, "F major", vocalProbability = 0.9).copy(lowEnergyCurve = emptyList())
        val p = plan(out, inn)
        assertEquals(TransitionStyle.DJ_FILTER, p.transitionStyle)
        assertEquals(4.0, p.fadeSeconds, 1e-9)
        assertTrue(p.reason.contains("decline-to-mix"))
    }

    @Test
    fun loopBridgeFlagsMuffleForDeclinedBandPair() {
        val out = build("out", 120.0, "C major")
        val inn = build("inn", 109.0, "C major", maskAt = { if (it < 5.5) 0.1 else 0.9 })
        assertTrue(shouldDeclineToMix(out, inn))
        val bridgedNonNull = LoopMix.bridge(out, 200.0, inn, 200.0, 0.0)!!
        assertNotNull(bridgedNonNull)
        assertTrue("rung=" + bridgedNonNull.rung, bridgedNonNull.muffle || bridgedNonNull.beatlessBridge)
        assertFalse(bridgedNonNull.beatlessBridge)
        assertEquals("Muffle", bridgedNonNull.rung)
        // No curves: the classic Echo rung, exactly as before vc69.
        val bare = LoopMix.bridge(
            out.copy(lowEnergyCurve = emptyList()),
            200.0,
            inn.copy(lowEnergyCurve = emptyList()),
            200.0,
            0.0,
        )!!
        assertNotNull(bare)
        assertFalse(bare.muffle)
        assertFalse(bare.beatlessBridge)
        assertEquals("Echo", bare.rung)
    }

    @Test
    fun loopBridgeFlagsBridgeForBeatlessEdges() {
        val out = build("out", 120.0, "C major", bassAt = { if (it >= 180.0) 0.05 else 1.0 }, maskAt = { 0.0 })
        val inn = build("inn", 94.0, "C major", bassAt = { if (it < 8.0) 0.05 else 1.0 }, maskAt = { 0.0 })
        assertTrue(shouldDeclineToMix(out, inn))
        val bridgedNonNull = LoopMix.bridge(out, 200.0, inn, 200.0, 0.0)!!
        assertNotNull(bridgedNonNull)
        assertTrue("rung=" + bridgedNonNull.rung, bridgedNonNull.beatlessBridge)
        assertEquals("Bridge", bridgedNonNull.rung)
    }

    @Test
    fun planningRecordsTransitionEvidence() {
        TransitionEvidenceCache.clear()
        val out = build("out", 120.0, "C major", vocalProbability = 0.1, maskAt = { 0.0 })
        val inn = build("inn", 120.0, "C major", vocalProbability = 0.1, maskAt = { 0.0 })
        plan(out, inn)
        val recent = TransitionEvidenceCache.recent(1)
        assertTrue("steady planning must record evidence", recent.isNotEmpty())
        assertEquals("DJ_BLEND", recent[0].style)
        TransitionEvidenceCache.clear()
    }

    @Test
    fun depthDecoratesBlendPlans() {
        val out = build("out", 120.0, "C major", vocalProbability = 0.1, maskAt = { 0.0 })
        val inn = build("inn", 120.0, "C major", vocalProbability = 0.1, maskAt = { 0.0 })
        val p = plan(out, inn)
        assertTrue("silent masks must leave the ride unspent", p.vocalDepthCurve.isEmpty())
        val loudOut = build("out", 120.0, "C major", vocalProbability = 0.9)
        val loudIn = build("inn", 120.0, "C major", vocalProbability = 0.9)
        val loudP = plan(loudOut, loudIn)
        assertTrue("measured vocals must decorate the blend", loudP.vocalDepthCurve.isNotEmpty())
        assertTrue(loudP.vocalDepthCurve.zipWithNext().all { (a, b) -> b >= a - 1e-9 })
    }

    @Test
    fun alternativeGateHelpersMatchSelections() {
        val out = build("out", 120.0, "C major", bassAt = { if (it >= 180.0) 0.05 else 1.0 }, maskAt = { 0.0 })
        val inn = build("inn", 94.0, "C major", bassAt = { if (it < 8.0) 0.05 else 1.0 }, maskAt = { 0.0 })
        assertTrue(TransitionAlternatives.beatlessBridgeFits(out, inn, 196.0))
        assertFalse(TransitionAlternatives.muffleFits(out, inn, 0.5, 196.0))
    }

    @Test
    fun muffleClockGeometryStaysOnTheTakeover() {
        val out = build("out", 120.0, "C major")
        val inn = build("inn", 109.0, "C major", maskAt = { if (it < 5.5) 0.1 else 0.9 })
        val bridgedNonNull = LoopMix.bridge(out, 200.0, inn, 200.0, 0.0)!!
        assertNotNull(bridgedNonNull)
        val clock = LoopMix.Clock(bridgedNonNull)
        assertTrue(clock.releaseAt > 0.0)
        assertTrue("muffle clock must fit the mix window", clock.releaseAt <= 30.0)
    }

    @Test
    fun declinedPairIsNotHijackedByPhrasePlannerInNativeBridge() {
        val out = build("out", 120.0, "Ab major")
        val inn = build("inn", 109.0, "E major")
        assertTrue(shouldDeclineToMix(out, inn))
        assertFalse(shouldRunPhrasePlannerForBridge(out, inn))

        val compatible = build("compat", 120.0, "Ab major")
        assertFalse(shouldDeclineToMix(out, compatible))
        assertTrue(shouldRunPhrasePlannerForBridge(out, compatible))
    }
}
