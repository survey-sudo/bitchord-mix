package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * vc69 P2 beat-flow ranking: Orchard transitionFlow.js beatFlow ported onto
 * the low-band energy curve.
 */
class BeatFlowTest {

    private fun steady(bass: Double, duration: Double = 100.0): TrackAnalysis {
        val curve = (0 until 200).map { EnergySample(it * duration / 200.0, bass) }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "t",
            duration = duration,
            beatInterval = 0.5,
            lowEnergyCurve = curve,
            firstBeat = 0.5,
        )
    }

    @Test
    fun unmeasuredPairsAreNeutral() {
        val bare = TrackAnalysis(status = TrackAnalysis.STATUS_READY, trackId = "bare")
        val result = BeatFlow.beatFlow(bare, bare, 90.0, 0.0, 8.0)
        assertEquals(0.5, result.score, 1e-9)
        assertEquals(0.0, result.coverage, 0.0)
    }

    @Test
    fun steadyBeatOnBothSidesScoresHigh() {
        val out = steady(1.0)
        val inn = steady(1.0)
        val result = BeatFlow.beatFlow(out, inn, 90.0, 0.0, 8.0, matched = true)
        assertTrue(result.coverage > 0.0)
        assertTrue(result.score > 0.5, "steady beat flow must beat neutral: " + result.score)
        assertEquals(1.0, result.sharedBeat, 1e-9)
    }

    @Test
    fun beatlessIncomingPenaltyIsMeasured() {
        val out = steady(1.0)
        // Incoming starts beatless for its first half.
        val quiet = (0 until 200).map { i ->
            val time = i * 100.0 / 200.0
            EnergySample(time, if (time < 50.0) 0.05 else 1.0)
        }
        val inn = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, trackId = "i", duration = 100.0,
            beatInterval = 0.5, lowEnergyCurve = quiet, firstBeat = 0.5,
        )
        val result = BeatFlow.beatFlow(out, inn, 90.0, 0.0, 8.0, matched = true)
        assertTrue(result.waitSeconds > 0.0, "must wait for the beat: " + result.waitSeconds)
    }

    @Test
    fun rankScoreBlendsConfidenceAndFlow() {
        val strong = BeatFlow.FlowResult(score = 1.0, coverage = 1.0)
        val neutral = BeatFlow.FlowResult(score = 0.9, coverage = 0.0)
        assertTrue(BeatFlow.rankScore(0.72, strong) > BeatFlow.rankScore(0.72, neutral))
        assertEquals(0.72 * 0.82 + 0.5 * 0.18, BeatFlow.rankScore(0.72, neutral), 1e-9)
    }

    @Test
    fun presenceIsNullWithoutCurveAndFullAtReference() {
        assertEquals(null, BeatFlow.beatPresenceAt(steady(1.0).copy(lowEnergyCurve = emptyList()), 40.0))
        val presence = BeatFlow.beatPresenceAt(steady(1.0), 40.0)
        assertTrue(presence != null && presence >= 0.9, "presence: " + presence)
    }

    @Test
    fun wholeBeatsFloorsToSoloTakeBelowMinimum() {
        assertEquals(0.0, BeatFlow.wholeBeats(3.5, 0.5, 4.0), 0.0)
        assertEquals(4.0, BeatFlow.wholeBeats(4.2, 0.5, 4.0), 1e-9)
    }
}
