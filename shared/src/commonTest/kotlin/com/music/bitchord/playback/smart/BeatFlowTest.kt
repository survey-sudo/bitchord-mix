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
    fun rankScorePromotesFlowWithCoverageLerp() {
        // w_flow = 0.18 + 0.27 * coverage, confidence takes the rest.
        val half = BeatFlow.FlowResult(score = 0.9, coverage = 0.5)
        val wHalf = 0.18 + 0.27 * 0.5
        assertEquals((1.0 - wHalf) * 0.72 + wHalf * 0.9, BeatFlow.rankScore(0.72, half), 1e-9)
        // Endpoints: full coverage is 0.55/0.45, zero coverage is Orchard's
        // 0.82/0.18 exactly.
        val full = BeatFlow.FlowResult(score = 0.9, coverage = 1.0)
        assertEquals(0.55 * 0.72 + 0.45 * 0.9, BeatFlow.rankScore(0.72, full), 1e-9)
        val none = BeatFlow.FlowResult(score = 0.9, coverage = 0.0)
        assertEquals(0.82 * 0.72 + 0.18 * 0.9, BeatFlow.rankScore(0.72, none), 1e-9)
        // No cliff: candidates differing by one sample of coverage must not
        // flip on formula alone. 17 samples is the denominator, so 1/17 apart
        // must move the rank by less than 0.02.
        val a = BeatFlow.rankScore(0.72, BeatFlow.FlowResult(score = 0.9, coverage = 8.0 / 17.0))
        val b = BeatFlow.rankScore(0.72, BeatFlow.FlowResult(score = 0.9, coverage = 9.0 / 17.0))
        assertTrue(kotlin.math.abs(a - b) < 0.02, "one sample of coverage must not flip rank: $a vs $b")
        // At full coverage a full flow swing is worth 0.45.
        val hi = BeatFlow.rankScore(0.72, BeatFlow.FlowResult(score = 1.0, coverage = 1.0))
        val lo = BeatFlow.rankScore(0.72, BeatFlow.FlowResult(score = 0.0, coverage = 1.0))
        assertEquals(0.45, hi - lo, 1e-9, "measured flow spans exactly 0.45")
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
