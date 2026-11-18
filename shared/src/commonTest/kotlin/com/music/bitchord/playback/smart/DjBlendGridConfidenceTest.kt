package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DjBlendGridConfidenceTest {
    private fun track(
        id: String,
        bpm: Double,
        beatConfidence: Double,
        duration: Double = 180.0,
    ): TrackAnalysis {
        val beat = 60.0 / bpm
        val downbeats = List(((duration / (4.0 * beat)) + 1).toInt()) { it * 4.0 * beat + 0.5 }
        val energy = List(duration.toInt()) { EnergySample(it.toDouble(), 1.0) }
        val mask = List(duration.toInt()) { 0.05 }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = id,
            duration = duration,
            bpm = bpm,
            beatInterval = beat,
            beatConfidence = beatConfidence,
            downbeats = downbeats,
            phraseBoundaries = downbeats.filterIndexed { i, _ -> i % 8 == 0 },
            firstBeat = 0.5,
            key = "C major",
            keyConfidence = 0.9,
            audibleStartTime = 0.5,
            introEndTime = 10.0,
            contentEndTime = duration - 2.0,
            energyCurve = energy,
            vocalActivityMask = mask,
            vocalProbability = 0.05,
            quality = TrackAnalysis.QUALITY_FULL,
        )
    }

    @Test
    fun oneWeakGridCannotAuthorizeDjBlendInEitherDirection() {
        val strongOut = track("strong-out", 124.0, beatConfidence = 0.92)
        val weakIn = track("weak-in", 124.0, beatConfidence = MIN_DJ_CONFIDENCE / 2)
        val weakOut = track("weak-out", 124.0, beatConfidence = MIN_DJ_CONFIDENCE / 2)
        val strongIn = track("strong-in", 124.0, beatConfidence = 0.92)

        // Exercise the OR-bug site directly, bypassing plain-crossfade/WSOLA
        // early returns. The original OR authorizes both of these pairs.
        for ((a, b) in listOf(strongOut to weakIn, weakOut to strongIn)) {
            for (traits in listOf(null, PairTraits(a, b))) {
                assertEquals(TransitionStyle.DJ_FILTER,
                    mixPlan(a, b, 176.0, "content-end", b.duration, traits).transitionStyle)
            }
        }

        val outStrongPlan = planTransition(
            analysis = strongOut,
            nextAnalysis = weakIn,
            currentTrack = TransitionTrackInfo("strong-out", 180_000L),
            nextTrack = TransitionTrackInfo("weak-in", 180_000L),
            currentTime = 10.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertNotEquals(
            TransitionStyle.DJ_BLEND,
            outStrongPlan.transitionStyle,
            "confident outgoing must not grant DJ_BLEND over weak incoming (${outStrongPlan.reason})",
        )

        val inStrongPlan = planTransition(
            analysis = weakOut,
            nextAnalysis = strongIn,
            currentTrack = TransitionTrackInfo("weak-out", 180_000L),
            nextTrack = TransitionTrackInfo("strong-in", 180_000L),
            currentTime = 10.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertNotEquals(
            TransitionStyle.DJ_BLEND,
            inStrongPlan.transitionStyle,
            "confident incoming must not grant DJ_BLEND from weak outgoing (${inStrongPlan.reason})",
        )
    }

    @Test
    fun bothConfidentGridsAuthorizeDjBlend() {
        val out = track("out-ok", 124.0, beatConfidence = 0.92)
        val inn = track("in-ok", 124.5, beatConfidence = 0.90)
        assertEquals(TransitionStyle.DJ_BLEND,
            mixPlan(out, inn, 176.0, "content-end", inn.duration, PairTraits(out, inn)).transitionStyle)
        val plan = planTransition(
            analysis = out,
            nextAnalysis = inn,
            currentTrack = TransitionTrackInfo("out-ok", 180_000L),
            nextTrack = TransitionTrackInfo("in-ok", 180_000L),
            currentTime = 10.0,
            duration = 180.0,
            mode = CrossfadeMode.SMART,
            advanced = true,
        )
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle, "both strong grids should allow DJ_BLEND (${plan.reason})")
        assertTrue(plan.fadeSeconds >= 8.0)
    }

    @Test
    fun fxPolicyAlsoRequiresMeasuredConfidence() {
        assertEquals(DjEffect.NONE, FxPolicy.select("a", "b", true, false, 0.0, false, false, true, false))
    }
}
