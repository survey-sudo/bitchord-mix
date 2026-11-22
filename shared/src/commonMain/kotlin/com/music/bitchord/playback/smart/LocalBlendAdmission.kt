package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** A bounded exception for one ordinary blend, never permission to loop or to pulse a live deck. */
object LocalBlendAdmission {
    const val MARKER = "upstream-local-blend"

    data class Verdict(
        val admitted: Boolean,
        val refusal: String? = null,
        /** Mean product of measured source vocal presence, without assumed fader attenuation. Null is unknown. */
        val weightedCollision: Double? = null,
        val simultaneousFraction: Double? = null,
    )

    /** The original upstream physical rate window, independent of the fork's coarse 8% preference. */
    fun gridRefusal(out: TrackAnalysis, inn: TrackAnalysis): String? {
        for ((analysis, tail) in listOf(out to true, inn to false)) {
            val side = if (tail) analysis.tailBeats else analysis.headBeats
            val confidence = side?.confidence ?: analysis.beatConfidence
            if (!confidence.isFinite() || confidence < MIN_BEATMATCH_CONFIDENCE) return "weak-join-grid"
            val beats = side?.beats.orEmpty()
            if (beats.isNotEmpty() && (beats.size < 8 || beats.any { !it.isFinite() } ||
                    beats.zipWithNext().any { (a, b) -> b <= a } || fittedBpm(beats, tail) == null)) {
                return "unfit-join-grid"
            }
            if (!joinTempo(analysis, tail).isFinite() || joinTempo(analysis, tail) <= 0.0) return "unknown-join-tempo"
            // A fallback BPM still needs a measured grid, rather than catalog metadata alone.
            if (beats.isEmpty()) {
                val downbeats = if (tail) analysis.downbeats.takeLast(8) else analysis.downbeats.take(8)
                if (downbeats.size < 8) return "missing-join-grid"
                val bar = 240.0 / joinTempo(analysis, tail)
                if (downbeats.any { !it.isFinite() || it < 0.0 } ||
                    downbeats.zipWithNext().any { (a, b) -> b <= a || abs((b - a) / bar - 1.0) > 0.06 }) {
                    return "unfit-join-grid"
                }
            }
        }
        return if (!stretchReachable(joinTempo(out, true), joinTempo(inn, false))) "outside-upstream-rate-window" else null
    }

    private fun joinTempo(analysis: TrackAnalysis, tail: Boolean): Double =
        joinBpm(fittedBpm((if (tail) analysis.tailBeats else analysis.headBeats)?.beats.orEmpty(), tail), analysis.bpm)

    fun isQualified(
        plan: TransitionPlan,
        out: TrackAnalysis,
        inn: TrackAnalysis,
        lyricsOut: List<LyricCue> = emptyList(),
        lyricsIn: List<LyricCue> = emptyList(),
    ): Boolean = MARKER in plan.policyReasons && evaluate(plan, out, inn, lyricsOut, lyricsIn).admitted

    /** Measures the candidate's final source spans, after cue caps and DJ structure/overlap decoration. */
    fun evaluate(
        plan: TransitionPlan,
        out: TrackAnalysis,
        inn: TrackAnalysis,
        lyricsOut: List<LyricCue> = emptyList(),
        lyricsIn: List<LyricCue> = emptyList(),
    ): Verdict {
        fun refuse(reason: String) = Verdict(false, reason)
        gridRefusal(out, inn)?.let { return refuse(it) }
        if (plan.blocked || plan.transitionStyle != TransitionStyle.DJ_BLEND) return refuse("not-ordinary-blend")
        val span = plan.transitionEnd - plan.transitionStart
        val rate = plan.incomingPlaybackRate
        val expectedRate = incomingRateBetween(joinTempo(out, true), joinTempo(inn, false))
        if (!span.isFinite() || span <= 0.0 || !plan.transitionStart.isFinite() || plan.transitionStart < 0.0 ||
            !plan.incomingCueTime.isFinite() || plan.incomingCueTime < 0.0 || abs(span - plan.fadeSeconds) > 0.05
        ) return refuse("invalid-source-span")
        if (!rate.isFinite() || rate !in MIN_INCOMING_RATE..MAX_INCOMING_RATE ||
            abs(rate - expectedRate) > 0.001 || plan.outgoingPlaybackRate != 1.0 || plan.phaseLock
        ) return refuse("executed-rate-mismatch")
        // Upstream's advanced sameBeatBlend accelerated B (or left it at 1x).
        // Reachability below 1x is context, not permission for this restoration.
        if (rate < 1.0 || expectedRate < 1.0) return refuse("upstream-incoming-slowdown")
        if ((out.duration > 0.0 && plan.transitionEnd > out.duration + 0.05) ||
            (inn.duration > 0.0 && plan.incomingCueTime + span * rate > inn.duration + 0.05)
        ) return refuse("source-beyond-duration")
        val left = camelotCode(exitKeyForMix(out)).takeIf { exitKeyConfidence(out) >= 0.50 }
        val right = camelotCode(entryKeyForMix(inn)).takeIf { entryKeyConfidence(inn) >= 0.50 }
        if (left != null && right != null && !camelotCompatible(left, right)) return refuse("known-key-clash")
        val a = CoveredVocal(out)
        val b = CoveredVocal(inn)
        if (!a.covers(plan.transitionStart, plan.transitionEnd)) return refuse("unknown-out-vocal-coverage")
        if (!b.covers(plan.incomingCueTime, plan.incomingCueTime + span * rate)) return refuse("unknown-in-vocal-coverage")
        val count = ceil(span * 20.0).toInt().coerceIn(1, 2400)
        val dt = span / count
        var weighted = 0.0
        var both = 0
        var leadRun = 0.0
        var longestLeadRun = 0.0
        for (i in 0 until count) {
            val p = (i + 0.5) / count
            val outTime = plan.transitionStart + p * span
            val inTime = plan.incomingCueTime + p * span * rate
            // Timed lyrics can contradict a quiet model frame; they never turn an uncovered frame into evidence.
            val av = maxOf(a.at(outTime), if (lyricsOut.any { outTime >= it.startSec && outTime < it.endSec }) 0.8 else 0.0)
            val bv = maxOf(b.at(inTime), if (lyricsIn.any { inTime >= it.startSec && inTime < it.endSec }) 0.8 else 0.0)
            // Conservative source-presence weighting. Do not assume a fader law:
            // classic holds/carves/bass swaps need not follow equal-power gains.
            weighted += av * bv
            if (av >= VOCAL_ACTIVE_THRESHOLD && bv >= VOCAL_ACTIVE_THRESHOLD) both++
            leadRun = if (p in 0.15..0.85 && av >= 0.6 && bv >= 0.6) leadRun + dt else 0.0
            longestLeadRun = maxOf(longestLeadRun, leadRun)
        }
        val collision = weighted / count
        val fraction = both.toDouble() / count
        // Beat confidence cannot compensate for two measured leads. The classic carve/fallback remains available.
        val refusal = when {
            longestLeadRun >= 1.0 -> "simultaneous-lead-collision"
            // The planner's existing 0.18 vocal-safe fallback boundary is stricter
            // than the 0.20 clash veto. A coarse-decline override earns no extra
            // fader attenuation credit; unknown coverage is refused above.
            collision > MAX_SOURCE_COLLISION -> "weighted-vocal-collision"
            else -> null
        }
        return Verdict(refusal == null, refusal, collision, fraction)
    }

    private const val MAX_SOURCE_COLLISION = 0.18

    private class CoveredVocal(private val analysis: TrackAnalysis) {
        private val windows = analysis.vocalWindows.filter { w ->
            w.startTime.isFinite() && w.frameRate.isFinite() && w.frameRate > 0.0 &&
                w.values.isNotEmpty() && w.values.all { it.isFinite() && it in 0f..1f }
        }.sortedBy { it.startTime }

        fun covers(from: Double, to: Double): Boolean {
            if (analysis.vocalWindows.isNotEmpty()) {
                var coveredTo = from
                for (w in windows) {
                    if (w.endTime <= coveredTo) continue
                    if (w.startTime > coveredTo + 1e-6) return false
                    coveredTo = w.endTime
                    if (coveredTo >= to - 1e-6) return true
                }
                return false
            }
            val curve = analysis.energyCurve
            return curve.size >= 2 && analysis.vocalActivityMask.size == curve.size &&
                analysis.vocalActivityMask.all { it.isFinite() && it in 0.0..1.0 } &&
                curve.all { it.time.isFinite() } && curve.first().time <= from && curve.last().time >= to &&
                curve.zipWithNext().all { (a, b) -> b.time > a.time && b.time - a.time <= 2.0 }
        }

        fun at(time: Double): Double {
            if (analysis.vocalWindows.isNotEmpty()) {
                // Prefer the strongest overlapping measurement; a late quiet window cannot hide a singer.
                return windows.filter { time >= it.startTime && time < it.endTime }.maxOf { w ->
                    w.values[floor((time - w.startTime) * w.frameRate).toInt().coerceIn(w.values.indices)].toDouble()
                }
            }
            val index = analysis.energyCurve.binarySearch { it.time.compareTo(time) }
            val at = if (index >= 0) index else (-index - 2).coerceAtLeast(0)
            return analysis.vocalActivityMask[at]
        }
    }
}
