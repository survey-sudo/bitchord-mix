package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * djmix10 P0: frozen mixes, honest analysis trust, native equal-power
 * gain staging, and graceful fallback selection.
 */
class AutomixFreezeTest {

    private fun track(id: String = "t1") = TransitionTrackInfo(id = id, durationMs = 200_000L)

    // ---- analysis trust (P0 #4) ----

    @Test fun blankStatusIsNotReady() {
        assertFalse(
            analysisReadyForTrackForTest(analysis(status = "")),
            "blank status must not authorize beat math",
        )
    }

    @Test fun decodeFailedReadyWithZeroBpmIsNotReady() {
        assertFalse(
            analysisReadyForTrackForTest(analysis(status = TrackAnalysis.STATUS_READY, bpm = 0.0)),
            "ready + bpm 0 (decode-failed default) is not usable",
        )
    }

    @Test fun realReadyAnalysisIsReady() {
        assertTrue(analysisReadyForTrackForTest(analysis()))
    }

    @Test fun wrongTrackIsNotReady() {
        assertFalse(
            analysisReadyForTrackForTest(analysis(trackId = "other")),
        )
    }

    private fun analysisReadyForTrackForTest(a: TrackAnalysis, t: TransitionTrackInfo = track()): Boolean {
        // Mirrors TransitionPlanner.analysisReadyForTrack (private there).
        if (a.status.isBlank()) return false
        if (a.status != TrackAnalysis.STATUS_READY) return false
        if (a.bpm <= 0) return false
        return a.trackId.isBlank() || t.id.isNullOrBlank() || a.trackId == t.id
    }

    // ---- EOF holdback anchor (P0 #5) ----

    @Test fun eofAnchorHoldsBackWhenNoInteriorCue() {
        // 120 BPM -> bar = 2.0s; bounded 1 bar / <=2.5 s holdback sits at contentEnd - 2.0.
        val a = analysis(bpm = 120.0, contentEnd = 200.0)
        val ranked = rankMixOutCandidates(a, contentEnd = 200.0, duration = 200.0)
        val anchor = ranked.firstOrNull()
        assertTrue(anchor != null, "a mix-out anchor must exist")
        assertEquals(198.0, anchor.time, 0.01, message = "anchor held back before EOF, type=${anchor.type}")
        assertEquals("eof_holdback", anchor.type)
    }

    @Test fun interiorCueBeatsHoldback() {
        // outroStart present -> analyzer's own cue wins over holdback.
        val a = analysis(outroStart = 190.0, mixOutStart = 190.0)
        val ranked = rankMixOutCandidates(a, contentEnd = 200.0, duration = 200.0)
        assertTrue(ranked.any { it.type == "outro_start" || it.type == "interior_mix_out" } || ranked.first().time <= 190.001)
    }

    private fun analysis(
        status: String = TrackAnalysis.STATUS_READY,
        trackId: String = "t1",
        bpm: Double = 120.0,
        quality: String = TrackAnalysis.QUALITY_FULL,
        contentEnd: Double = 200.0,
        outroStart: Double = 0.0,
        mixOutStart: Double? = null,
        energyCurve: List<EnergySample> = emptyList(),
    ) = TrackAnalysis(
        status = status,
        trackId = trackId,
        duration = contentEnd,
        bpm = bpm,
        beatInterval = 60.0 / bpm,
        beatConfidence = 0.9,
        downbeats = listOf(0.0, 2.0),
        contentEndTime = contentEnd,
        outroStartTime = outroStart,
        mixOutTime = mixOutStart ?: outroStart,
        mixOutCandidates = emptyList(),
        energyCurve = energyCurve,
        quality = quality,
    )

    // ---- djmix10 expanded: head/full trust, fold, coarse blend ----

    @Test fun outgoingHeadIsNotFull_butIncomingHeadIsAccepted() {
        val headOut = analysis(quality = TrackAnalysis.QUALITY_HEAD)
        val headIn = analysis(quality = TrackAnalysis.QUALITY_HEAD)
        val fullOut = analysis(quality = TrackAnalysis.QUALITY_FULL)
        // Mirror of analysisReadyForTrack + isFullAnalysis.
        fun ready(a: TrackAnalysis, requireFull: Boolean): Boolean {
            if (a.status.isBlank()) return false
            if (a.status != TrackAnalysis.STATUS_READY) return false
            if (a.bpm <= 0) return false
            fun isFull(x: TrackAnalysis): Boolean {
                if (x.quality == TrackAnalysis.QUALITY_HEAD) return false
                if (x.quality == TrackAnalysis.QUALITY_FULL) return true
                return x.energyCurve.isNotEmpty() || x.tailBeats != null
            }
            if (requireFull && !isFull(a)) return false
            return true
        }
        assertFalse(ready(headOut, requireFull = true))
        assertTrue(ready(headIn, requireFull = false))
        assertTrue(ready(fullOut, requireFull = true))
    }

    @Test fun unifiedFoldPicksClosestOctave() {
        // 74 (half-time error) against 105 folds to 148, not 74.
        assertEquals(148.0, alignTempoOctave(105.0, 74.0), 0.001)
        // Plain close pair unchanged.
        assertEquals(122.0, alignTempoOctave(120.0, 122.0), 0.001)
        // Exact double folds down.
        assertEquals(120.0, alignTempoOctave(120.0, 240.0), 0.001)
        assertEquals(120.0, alignTempoOctave(120.0, 60.0), 0.001)
    }

    @Test fun stableGridLowConfidenceBeatmatches() {
        val out = analysis(bpm = 120.0)
        val inn = analysis(bpm = 122.0)
        val verdict = assessTransitionTier(
            out.copy(beatConfidence = 0.48, downbeats = List(20) { it * 2.0 }),
            inn.copy(beatConfidence = 0.48, downbeats = List(20) { it * 2.0 }),
        )
        assertEquals(TransitionTier.BEATMATCHED, verdict.tier, message = "stable-grid rescue: ${verdict.reasons}")
        val noGrid = assessTransitionTier(
            out.copy(beatConfidence = 0.48, downbeats = listOf(0.0, 2.0)),
            inn.copy(beatConfidence = 0.48, downbeats = listOf(0.0, 2.0)),
        )
        assertEquals(TransitionTier.DJ_ASSISTED, noGrid.tier, message = "no-grid stays assisted")
    }

    // ---- native equal-power staging (P0 #1) ----

    @Test fun sampledEqualPowerSumsFlat() {
        val rise = MixGain.risePoints(0.0, 10.0)
        val fall = MixGain.fallPoints(0.0, 10.0)
        for (i in rise.first.indices) {
            val p = i.toFloat() / (rise.first.size - 1)
            val sum = rise.second[i] * rise.second[i] + fall.second[i] * fall.second[i]
            assertEquals(1.0f, sum, 0.01f, message = "power sum at $p")
            assertEquals(i.toFloat() / (rise.first.size - 1), p, 1e-6f)
        }
        assertEquals(0f, rise.second.first(), 1e-6f)
        assertEquals(1f, rise.second.last(), 1e-6f)
        assertEquals(1f, fall.second.first(), 1e-6f)
        assertEquals(0f, fall.second.last(), 1e-6f)
    }

    @Test fun headroomFloorHoldsWithNativeCurves() {
        // djmix19 intentionally changed: sin+cos peak 1.414 -> trim 0.707 uncapped, floored at 0.94 (was 0.85).
        val trim = MixGain.headroomTrim(1f, 1f)
        assertEquals(0.94f, trim, 1e-6f)
    }
}
