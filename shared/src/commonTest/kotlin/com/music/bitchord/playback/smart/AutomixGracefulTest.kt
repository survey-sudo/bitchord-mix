package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** djmix9: graceful fallbacks, break outlines, flat overlaps. */
class AutomixGracefulTest {
    @Test fun breakOutroPrefersQuietTail() {
        // 105 BPM, loud chorus then an 8-bar quiet break outro (Beautiful case).
        val beat = 60.0 / 105.0
        val bar = 4 * beat
        val curve = ArrayList<EnergySample>()
        var t = 0.0
        repeat(16) { i ->
            repeat(8) { curve += EnergySample(t, if (i < 8) 0.9 else 0.18); t += bar / 8 }
        }
        val contentEnd = 16 * bar
        val analysis = TrackAnalysis(
            duration = contentEnd, contentEndTime = contentEnd, bpm = 105.0,
            beatConfidence = 0.8, energyCurve = curve,
        )
        val candidate = detectBreakOutro(analysis, contentEnd, beat)
        assertNotNull(candidate, "quiet 8-bar tail must yield a break outro")
        assertEquals("break_outro", candidate.type)
        assertEquals(8 * bar, candidate.time, 0.3)
    }

    @Test fun breakOutroRejectsLoudTail() {
        val beat = 60.0 / 105.0
        val bar = 4 * beat
        val curve = ArrayList<EnergySample>()
        var t = 0.0
        repeat(16) { curve += EnergySample(t, 0.95); t += bar / 8 }
        val contentEnd = 16 * bar
        val analysis = TrackAnalysis(
            duration = contentEnd, contentEndTime = contentEnd, bpm = 105.0,
            beatConfidence = 0.8, energyCurve = curve,
        )
        assertNull(detectBreakOutro(analysis, contentEnd, beat))
    }

    @Test fun headroomTrimStaysAboveFloor() {
        assertEquals(0.85f, MixGain.headroomTrim(1f, 1f), 0.001f)
        assertEquals(1f, MixGain.headroomTrim(0.4f, 0.4f), 0.001f)
    }

    @Test fun equalPowerSumsFlat() {
        for (p in listOf(0f, 0.17f, 0.25f, 0.5f, 0.75f, 0.83f, 1f)) {
            assertEquals(1.0f, MixGain.powerSum(p), 0.0001f, "power sum at $p")
        }
    }

    @Test fun loudnessMatchIsSymmetric() {
        val sr = 44100
        val loud = FloatArray(sr) { 0.5f }
        val quiet = FloatArray(sr) { 0.05f }
        val g1 = RegionalLoudness.matchGain(loud, loud, 0, sr, quiet, quiet, 0, sr, sr)
        val g2 = RegionalLoudness.matchGain(quiet, quiet, 0, sr, loud, loud, 0, sr, sr)
        assertNotNull(g1); assertNotNull(g2)
        // Within +-3 dB: 0.708..1.412.
        assertEquals(true, g1 > 0.70f && g1 < 1.42f, "g1=$g1")
        assertEquals(true, g2 > 0.70f && g2 < 1.42f, "g2=$g2")
        assertEquals(true, abs(g1 * g2 - 1.0f) < 0.08f, "g1*g2 should be ~1: ${g1 * g2}")
    }

    @Test fun shortTrackStillPlans() {
        val plan = planTransition(
            analysis = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "a", duration = 22.0, bpm = 105.0, beatConfidence = 0.8),
            nextAnalysis = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "b", duration = 200.0, bpm = 105.0, beatConfidence = 0.8),
            currentTrack = TransitionTrackInfo(id = "a", durationMs = 22000L),
            nextTrack = TransitionTrackInfo(id = "b", durationMs = 200000L),
            currentTime = 10.0, duration = 22.0, fadeSeconds = 6.0, mode = CrossfadeMode.SMART,
        )
        assertFalse(plan.blocked, "22s track must not hit the short-duration guard: ${plan.reason}")
    }
}
