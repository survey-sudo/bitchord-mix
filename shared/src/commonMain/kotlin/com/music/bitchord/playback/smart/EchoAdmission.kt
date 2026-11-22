package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Positive musical evidence for an automatic Echo, in addition to handoff safety.
 * A clean vocal mask or a plausible whole-track BPM is not a musical exit.
 * This does not promise listening quality or replace qualifyMusicalHandoff.
 */
object EchoAdmission {
    fun musicalRefusal(handoff: MusicalHandoff, out: TrackAnalysis?, inn: TrackAnalysis?): String? {
        if (out == null || inn == null) return "echo-missing-local-analysis"
        val a = localGrid(out.tailBeats, handoff.outCaptureStart.sourceSec,
            handoff.outDryEnd.sourceSec) ?: return "echo-unmeasured-exit-grid"
        val b = localGrid(inn.headBeats, handoff.inAudibleStart.sourceSec,
            max(handoff.inLanding.sourceSec, handoff.inAudibleStart.sourceSec + .25))
            ?: return "echo-unmeasured-entry-grid"
        // The renderer's delay must agree with the measured local pulse, not
        // merely with an unrelated track-wide tempo estimate.
        if (!tempoAgrees(out.bpm, a) || !tempoAgrees(inn.bpm, b)) return "echo-local-tempo-mismatch"
        val exitAnchors = out.phraseBoundaries + out.mixOutCandidates.map { it.time } +
            listOf(out.mixOutTime) + out.tailBeats?.downbeats.orEmpty()
        if (!anchored(handoff.outDryEnd.sourceSec, exitAnchors, a)) return "echo-no-measured-exit"
        val entryAnchors = inn.headBeats?.downbeats.orEmpty() +
            listOfNotNull(inn.pickupTime, inn.audibleStartTime)
        if (!anchored(handoff.inAudibleStart.sourceSec, entryAnchors, b) &&
            !anchored(handoff.inLanding.sourceSec, inn.headBeats?.downbeats.orEmpty(), b)) {
            return "echo-no-measured-entry"
        }
        return null
    }

    private fun tempoAgrees(bpm: Double, beat: Double): Boolean =
        bpm.isFinite() && bpm > 1.0 && abs((60.0 / bpm) / beat - 1.0) <= .12

    private fun anchored(time: Double, anchors: List<Double>, beat: Double): Boolean =
        time.isFinite() && time >= 0.0 && anchors.any {
            it.isFinite() && it >= 0.0 && abs(it - time) <= min(.12, .2 * beat)
        }

    /** Returns the local measured beat length; never synthesizes a grid. */
    private fun localGrid(side: BeatSide?, from: Double, to: Double): Double? {
        side ?: return null
        if (!from.isFinite() || !to.isFinite() || from < 0.0 || to < from ||
            !side.confidence.isFinite() || side.confidence < MIN_BEATMATCH_CONFIDENCE ||
            !side.bpm.isFinite() || side.bpm <= 1.0 || side.beats.size < 4 ||
            side.beats.any { !it.isFinite() || it < 0.0 } ||
            side.beats.zipWithNext().any { (a, b) -> b <= a }) return null
        val expected = 60.0 / side.bpm
        // A cold entry can have an instantaneous landing. Retain enough
        // adjacent measured beats to prove its pulse without inventing a grid.
        val local = side.beats.filter { it >= max(0.0, from - 2 * expected) && it <= to + 4 * expected }
        if (local.size < 4 || local.first() > from + expected + 1e-8 ||
            local.last() < to - expected - 1e-8) return null
        val gaps = local.zipWithNext { a, b -> b - a }.sorted()
        val median = gaps[gaps.size / 2]
        if (median <= 0.0 || abs(median / expected - 1.0) > .12 ||
            gaps.any { abs(it / median - 1.0) > .20 }) return null
        return median
    }
}
