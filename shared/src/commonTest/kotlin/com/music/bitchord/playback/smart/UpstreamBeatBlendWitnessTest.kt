package com.music.bitchord.playback.smart

import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Portable vc87 baseline witness: only APIs already public in vc87 belong here.
 * BPM/confidence/duration numbers were observed for I SPY -> FORMULA. All local
 * grids, envelopes and contrasting/disjoint vocal corridors below are SYNTHETIC.
 * This is neither a replay of saved private analysis nor a PCM/audio validation.
 * Track identities deliberately have no relationship to the observed recording IDs.
 */
class UpstreamBeatBlendWitnessTest {
    private fun track(id: String, bpm: Double, confidence: Double, duration: Double, voice: Float, key: String): TrackAnalysis {
        val beat = 60.0 / bpm
        val beats = List((duration / beat).toInt()) { it * beat }
        val down = beats.filterIndexed { i, _ -> i % 4 == 0 }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, quality = TrackAnalysis.QUALITY_FULL,
            trackId = id, duration = duration, bpm = bpm, beatInterval = beat,
            beatConfidence = confidence, downbeats = down, firstBeat = 0.0,
            headBeats = BeatSide(beats.take(96), down.take(24), bpm, confidence),
            tailBeats = BeatSide(beats.takeLast(96), down.takeLast(24), bpm, confidence),
            key = key, keyConfidence = 0.05, keyTail = key, keyHead = key,
            keyTailConfidence = 0.05, keyHeadConfidence = 0.05,
            audibleStartTime = 0.0, contentEndTime = duration,
            energyCurve = List(ceil(duration).toInt() + 1) { EnergySample(it.toDouble(), 1.0) },
            vocalActivityMask = List(ceil(duration).toInt() + 1) { voice.toDouble() },
            vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(ceil(duration * 20).toInt()) { voice })),
            vocalProbability = 0.94,
        )
    }

    private fun plan(out: TrackAnalysis, inn: TrackAnalysis): TransitionPlan = planTransition(
        analysis = out, nextAnalysis = inn,
        currentTrack = TransitionTrackInfo(out.trackId, (out.duration * 1000).toLong()),
        nextTrack = TransitionTrackInfo(inn.trackId, (inn.duration * 1000).toLong()),
        currentTime = 10.0, duration = out.duration, mode = CrossfadeMode.SMART,
        advanced = true, mixWindow = true,
    )

    private fun assertStraightBlend(plan: TransitionPlan) {
        assertEquals(TransitionStyle.DJ_BLEND, plan.transitionStyle, plan.reason)
        assertEquals(0.0, plan.echoSeconds, 0.00001)
        assertEquals(DjEffect.NONE, plan.djEffect)
        assertEquals(1.0952, plan.incomingPlaybackRate, 0.0006)
        assertEquals(1.0, plan.outgoingPlaybackRate, 0.00001)
        assertFalse(plan.phaseLock)
        assertFalse(plan.reason.contains("decline-to-mix"))
        assertTrue(plan.fadeSeconds >= 4 * 4 * plan.beatSeconds, "a continuous bar overlap: $plan")
        assertTrue(plan.fadeSeconds > 6.0, "must not be the old four-second decline")
        assertEquals(plan.fadeSeconds, plan.transitionEnd - plan.transitionStart, 0.00001)
    }

    @Test
    fun observedTempoPairCanUseOrdinaryBlendOnSyntheticCoveredVocalCorridors() {
        val out = track("witness-out", 142.857142857, 0.93622, 185.021, 0.85f, "B major")
        val inn = track("witness-in", 130.434782608, 0.95, 188.021, 0.02f, "B minor")
        // One measured singer over a measured bed must not decline because both global scores are high.
        val contrasting = plan(out, inn)
        assertStraightBlend(contrasting)
        assertFalse(contrasting.mixSummary.contains("clash"), contrasting.mixSummary)

        // Both local corridors contain a singer, but their leads occupy different
        // wall-clock halves after the actual incoming rate and source cue mapping.
        val outMid = (contrasting.transitionStart + contrasting.transitionEnd) / 2
        val inMid = contrasting.incomingCueTime + contrasting.fadeSeconds * contrasting.incomingPlaybackRate / 2
        val disjointOut = out.copy(
            vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(ceil(out.duration * 20).toInt()) {
                if (it / 20.0 < outMid) 0.85f else 0.02f
            })),
            vocalActivityMask = out.energyCurve.map { if (it.time < outMid) 0.85 else 0.02 },
        )
        val disjointIn = inn.copy(
            vocalWindows = listOf(CurveWindow(0.0, 20.0, FloatArray(ceil(inn.duration * 20).toInt()) {
                if (it / 20.0 >= inMid) 0.85f else 0.02f
            })),
            vocalActivityMask = inn.energyCurve.map { if (it.time >= inMid) 0.85 else 0.02 },
        )
        assertStraightBlend(plan(disjointOut, disjointIn))
    }
}
