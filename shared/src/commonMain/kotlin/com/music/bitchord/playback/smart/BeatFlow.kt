package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Beat-flow continuity ranking: how well the beat the listener
 * already hears carries through a candidate overlap and past the handoff.
 * Ported from Orchard transitionFlow.js beatFlow (FLOW_SAMPLES 16, equal
 * power weighting, deficit and late-drop accounting). BitChord has no bass
 * curve per se; the low-band energy curve plays that role, normalized
 * against each track own reference so presence reads in the same 0..1 terms
 * Orchard uses for bass levels. Every measure is neutral (0.5) when the
 * worker sent no curves, exactly like Orchard.
 *
 * Scores feed the alternative-transition ranker: when several rescue
 * techniques fit a declined pair, the one whose beat the listener is
 * already hearing survives the overlap wins, which is the same judgment
 * Orchard makes with its flow weight (0.18) against the coarse frame
 * measures it supersedes.
 */
object BeatFlow {
    /** Overlap samples per candidate (transitionFlow.js FLOW_SAMPLES). */
    const val FLOW_SAMPLES = 16
    /** Bass level under which a passage carries no kick or bassline. */
    const val BASS_SILENT = 0.1
    /** Bass level at which the beat counts as fully in. */
    const val BASS_FULL = 0.5
    /** Beat presence counted as heard for the shared-beat ratio. */
    const val SHARED_BEAT = 0.5
    /** Waiting this long for the beat after the old song leaves is a full failure. */
    const val MAX_WAIT_SECONDS = 8.0
    /** Seconds of incoming passed its first beat that skipping charges against. */
    const val MAX_SKIP_SECONDS = 45.0
    /** A beat that stopped this many beats before counts as the listeners beat. */
    const val RECENT_BEATS = 8

    /** One candidate overlap scored for beat continuity. Neutral without curves. */
    data class FlowResult(
        val score: Double = 0.5,
        val coverage: Double = 0.0,
        val sharedBeat: Double = 0.0,
        val lateDrop: Double = 0.0,
        val meanDeficit: Double = 0.0,
        val worstDeficit: Double = 0.0,
        val waitSeconds: Double = 0.0,
        val skip: Double = 0.0,
    )

    /** Mean low-band energy on a track between two seconds, or null without a curve. */
    fun meanCurve(curve: List<EnergySample>, from: Double, until: Double): Double? {
        if (curve.isEmpty() || until <= from) return null
        var sum = 0.0
        var count = 0
        for (point in curve) {
            if (point.time >= from && point.time < until && point.energy.isFinite() && point.energy >= 0.0) {
                sum += point.energy
                count++
            }
        }
        return if (count == 0) null else sum / count
    }

    /** The track own loud-end reference for bass levels: upper decile, at least a quarter of max. */
    fun bassReference(analysis: TrackAnalysis): Double? {
        val energies = analysis.lowEnergyCurve.map { it.energy }.filter { it.isFinite() && it >= 0.0 }.sorted()
        if (energies.isEmpty()) return null
        val upperDecile = energies[(energies.lastIndex * 0.9).toInt()]
        val reference = max(upperDecile, (energies.lastOrNull() ?: 0.0) * 0.25)
        return reference.takeIf { it > 1e-9 }
    }

    /**
     * Beat presence near a track second: 0 in a beatless passage, 1 once
     * kick and bass are in. Null without a low-band curve.
     */
    fun beatPresenceAt(analysis: TrackAnalysis, time: Double): Double? {
        val curve = analysis.lowEnergyCurve
        if (curve.isEmpty() || !time.isFinite()) return null
        val reference = bassReference(analysis) ?: return null
        val level = meanCurve(curve, time - 0.5, time + 0.5) ?: return null
        val presence = ((level / reference) - BASS_SILENT) / (BASS_FULL - BASS_SILENT)
        return presence.coerceIn(0.0, 1.0)
    }

    /** Track time where the beat first comes in. Null when never or unmeasured. */
    fun firstBeat(analysis: TrackAnalysis): Double? {
        if (analysis.lowEnergyCurve.isEmpty()) return null
        var time = analysis.firstBeat
        val end = curveEnd(analysis)
        if (end == null) return null
        while (time + 0.5 <= end) {
            if ((beatPresenceAt(analysis, time) ?: 0.0) >= 0.5) return time
            time += 0.5
        }
        return null
    }

    private fun curveEnd(analysis: TrackAnalysis): Double? {
        val curve = analysis.lowEnergyCurve
        if (curve.isEmpty()) return null
        return curve.maxOfOrNull { it.time }
    }

    private fun recentBeat(analysis: TrackAnalysis, time: Double, beatInterval: Double): Double? {
        var level = beatPresenceAt(analysis, time) ?: return null
        if (beatInterval <= 0.0 || !beatInterval.isFinite()) return level
        for (beat in 1..RECENT_BEATS) {
            level = max(level, beatPresenceAt(analysis, time - beat * beatInterval) ?: 0.0)
        }
        return level
    }

    private fun within(fraction: Double, start: Double, end: Double): Double =
        if (end > start) ((fraction - start) / (end - start)).coerceIn(0.0, 1.0)
        else if (fraction >= start) 1.0 else 0.0

    /**
     * Scores one candidate overlap. outStart/inCue are source times, duration
     * the overlap length in outgoing seconds, rates the playback rates.
     * A short unmatched fade is only sampled at its end (Orchard samples
     * once when not beatmatched): callers pass the matched flag for the
     * dense sampling the gate needs.
     */
    fun beatFlow(
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        outStart: Double,
        inCue: Double,
        durationSeconds: Double,
        outRate: Double = 1.0,
        inRate: Double = 1.0,
        anchorTime: Double? = null,
        matched: Boolean = false,
    ): FlowResult {
        if (outgoing.lowEnergyCurve.isEmpty() || incoming.lowEnergyCurve.isEmpty() || !(durationSeconds > 0.0)) {
            return FlowResult()
        }
        val reference = recentBeat(outgoing, outStart, outgoing.beatInterval) ?: return FlowResult()
        var deficit = 0.0
        var worst = 0.0
        var shared = 0
        var known = 0
        var late = 0
        var secondHalf = 0
        val outStep = if (outRate.isFinite() && outRate > 0.0) outRate else 1.0
        val inStep = if (inRate.isFinite() && inRate > 0.0) inRate else 1.0
        val steps = if (matched) FLOW_SAMPLES else 0
        val from = if (matched) 0 else 1
        val to = maxOf(1, steps)
        for (index in from..to) {
            val fraction = if (matched) index.toDouble() / steps.toDouble() else 1.0
            val moment = fraction * durationSeconds
            val left = beatPresenceAt(outgoing, outStart + moment * outStep)
            val right = beatPresenceAt(incoming, inCue + moment * inStep)
            if (left == null || right == null) continue
            val outWeight = cos(within(fraction, 0.0, 1.0) * PI / 2.0)
            val inWeight = sin(within(fraction, 0.0, 1.0) * PI / 2.0)
            val heard = min(1.0, outWeight * left + inWeight * right)
            val loss = max(0.0, reference - heard)
            deficit += loss
            worst = max(worst, loss)
            if (matched && fraction >= 0.5) {
                secondHalf++
                if (right < 0.5) late++
            }
            if (left >= SHARED_BEAT && right >= SHARED_BEAT) shared++
            known++
        }
        if (known == 0) return FlowResult()
        val inEnd = inCue + durationSeconds * inStep
        var waitSeconds = 0.0
        var scan = inEnd
        while (waitSeconds < MAX_WAIT_SECONDS) {
            val presence = beatPresenceAt(incoming, scan) ?: break
            if (presence >= 0.5) break
            waitSeconds += 0.5
            scan += 0.5
        }
        val meanDeficit = deficit / known.toDouble()
        val lateDrop = if (secondHalf > 0) (late.toDouble() / secondHalf) * reference else 0.0
        val start = firstBeat(incoming) ?: 0.0
        val anchor = anchorTime ?: inEnd
        val skip = ((anchor - start).coerceIn(0.0, MAX_SKIP_SECONDS) / MAX_SKIP_SECONDS)
        // meanDeficit and worst are two readings of the same deficit, so
        // weighting them 0.5/0.5 charged it twice. The worst sample is what the
        // ear notices; the mean is corroboration. 0.7/0.3 keeps the mean
        // primary without letting a single sample decide the candidate.
        val penalty = meanDeficit * 0.7 + worst * 0.3 + lateDrop * 0.2 +
            minOf(waitSeconds / MAX_WAIT_SECONDS, 1.0) * 0.6 + minOf(skip, 1.0) * 0.4
        return FlowResult(
            score = (1.0 - penalty).coerceIn(0.0, 1.0),
            coverage = known.toDouble() / (FLOW_SAMPLES + 1).toDouble(),
            sharedBeat = shared.toDouble() / known.toDouble(),
            lateDrop = lateDrop,
            meanDeficit = meanDeficit,
            worstDeficit = worst,
            waitSeconds = waitSeconds,
            skip = skip,
        )
    }

    /**
     * Ranking score for as many candidate overlaps as fit.
     *
     * Orchard weights beat flow 0.18 against the coarse frame measures, and
     * that is the split at zero coverage (0.82 confidence, 0.18 flow). As
     * coverage rises the flow becomes a real measurement rather than a
     * quarter-filled guess, and it is the more audible of the two signals —
     * beat-tracker confidence saturates and is wrong on hand-played drums,
     * while flow is how the beat the listener is already hearing survives the
     * overlap. So the flow weight grows linearly with coverage,
     * 0.18 + 0.27 * coverage, reaching 0.45 at full coverage while confidence
     * demotes to 0.55.
     *
     * Linear rather than a step at half coverage: a step inverts near-identical
     * candidates on formula alone, and candidates arrive with hard-coded
     * confidences (0.72, 0.66) where one sample of coverage difference should
     * never flip the winner.
     */
    fun rankScore(confidence: Double, flow: FlowResult): Double {
        val safeFlow = if (flow.coverage > 0.0) flow.score else 0.5
        val safeConfidence = if (confidence.isFinite()) confidence.coerceIn(0.0, 1.0) else 0.0
        val safeFlowClamped = safeFlow.coerceIn(0.0, 1.0)
        val cov = if (flow.coverage.isFinite()) flow.coverage.coerceIn(0.0, 1.0) else 0.0
        val flowWeight = 0.18 + 0.27 * cov
        val confidenceWeight = 1.0 - flowWeight
        return confidenceWeight * safeConfidence + flowWeight * safeFlowClamped
    }

    /** Whole-beat duration for a beatless run: floor to whole beats, capped. */
    fun wholeBeats(durationSeconds: Double, beatInterval: Double, minimumSeconds: Double = 4.0): Double {
        if (beatInterval <= 0.0 || !beatInterval.isFinite()) return 0.0
        val beats = floor(durationSeconds / beatInterval).toLong()
        val whole = beats * beatInterval
        return if (whole < minimumSeconds) 0.0 else whole
    }
}
