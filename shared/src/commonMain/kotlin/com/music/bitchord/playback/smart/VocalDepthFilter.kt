package com.music.bitchord.playback.smart

import kotlin.math.pow

/**
 * Vocal-depth-modulated filter ride (vc69 P1). Orchard spends a filter sweep
 * only as far as the material justifies: a depth curve scales how much of
 * the cutoff travel actually happens (earmark dsp filters.rs: depth 0.5 is
 * half the octaves, applied as a running maximum so the filter never
 * reopens mid-transition). BitChord measures that depth from the vocal
 * presence the Syvenu vocal model already produced: the louder the lead over a
 * given slice of the overlap, the deeper the outgoing low-pass and incoming
 * high-pass cuts at that instant. When nothing was measured, depth stays at
 * its neutral full spend and unmeasured pairs render exactly as before.
 */
object VocalDepthFilter {
    /** Vocal activity where a line is clearly in progress (transitionFlow.js VOICE_FLOOR). */
    const val VOICE_FLOOR = 0.35
    /** Vocal activity span from floor to full voice (transitionFlow.js VOICE_SPAN). */
    const val VOICE_SPAN = 0.4

    /**
     * Vocal presence (0..1) at one track second, interpolated from the mask
     * indexed against the energy curve sample times. Null when the mask is
     * empty or misaligned: absence of evidence, not a silent song.
     */
    fun presenceAt(analysis: TrackAnalysis, time: Double): Double? {
        val mask = analysis.vocalActivityMask
        val curve = analysis.energyCurve
        if (mask.isEmpty() || mask.size != curve.size || !time.isFinite()) return null
        if (curve.size == 1) return mask[0].coerceIn(0.0, 1.0)
        var before = 0
        var after = curve.size - 1
        // Linear scan from the top is fine: curves are a few hundred points
        // and the planner asks for a handful of samples per transition.
        for (index in curve.indices.reversed()) {
            if (time >= curve[index].time) {
                before = index
                after = minOf(curve.size - 1, index + 1)
                break
            }
        }
        val t0 = curve[before].time
        val t1 = curve[after].time
        val v0 = mask[before]
        val v1 = mask[after]
        val span = t1 - t0
        if (span <= 0.0) return v0.coerceIn(0.0, 1.0)
        val fraction = ((time - t0) / span).coerceIn(0.0, 1.0)
        return (v0 + (v1 - v0) * fraction).coerceIn(0.0, 1.0)
    }

    /**
     * Presence fallback chain for one track: the mask sample, else the mean
     * of the open-unmix windows covering the instant, else the whole-track
     * probability as a constant. Null only when all three are absent.
     */
    fun presenceOrScalar(analysis: TrackAnalysis, time: Double): Double? {
        presenceAt(analysis, time)?.let { return it }
        for (window in analysis.vocalWindows) {
            if (window.values.isEmpty() || !window.frameRate.isFinite() || window.frameRate <= 0.0) continue
            if (time >= window.startTime && time <= window.endTime) {
                val index = ((time - window.startTime) * window.frameRate).toInt()
                    .coerceIn(0, window.values.size - 1)
                val value = window.values[index].toDouble()
                if (value.isFinite()) return value.coerceIn(0.0, 1.0)
            }
        }
        if (analysis.vocalProbability.isFinite() && analysis.vocalProbability > 0.0) {
            return analysis.vocalProbability.coerceIn(0.0, 1.0)
        }
        return null
    }

    /**
     * Filter depth justified by one side presence: thresholded and scaled to
     * 0..1, so silence spends none of the travel and a full lead spends all
     * of it. Unmeasured presence spends fully (the engine cannot know dummy
     * silence, so the ride keeps its historical behavior).
     */
    fun depthForPresence(presence: Double?): Double {
        if (presence == null || !presence.isFinite()) return 1.0
        return ((presence - VOICE_FLOOR) / VOICE_SPAN).coerceIn(0.0, 1.0)
    }

    /**
     * Depth at one overlap fraction: the deeper of the two songs at the
     * corresponding source instants. Outgoing source time runs with the
     * outgoing rate, incoming with the incoming rate from its cue.
     */
    fun depthAt(
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        fraction: Double,
        outStart: Double,
        outEnd: Double,
        inCue: Double,
        inRate: Double = 1.0,
    ): Double {
        val outTime = outStart + fraction * (outEnd - outStart)
        val rate = if (inRate.isFinite() && inRate > 0.0) inRate else 1.0
        val inTime = inCue + fraction * (outEnd - outStart) * rate
        val outDepth = depthForPresence(presenceOrScalar(outgoing, outTime))
        val inDepth = depthForPresence(presenceOrScalar(incoming, inTime))
        return maxOf(outDepth, inDepth)
    }

    /**
     * Depth curve over the overlap, sampled at the given count (default 24,
     * one loner than the beat-flow sampler). Empty when nothing is measured
     * on either side: callers treat empty as full spend.
     */
    fun depthCurve(
        outgoing: TrackAnalysis,
        incoming: TrackAnalysis,
        outStart: Double,
        outEnd: Double,
        inCue: Double,
        inRate: Double = 1.0,
        samples: Int = 24,
    ): List<Double> {
        if (outEnd <= outStart || samples < 1) return emptyList()
        if (outgoing.vocalActivityMask.isEmpty() && incoming.vocalActivityMask.isEmpty() &&
            outgoing.vocalWindows.isEmpty() && incoming.vocalWindows.isEmpty() &&
            outgoing.vocalProbability <= 0.0 && incoming.vocalProbability <= 0.0
        ) {
            return emptyList()
        }
        val count = if (samples < 2) 2 else samples
        val curve = ArrayList<Double>(count)
        for (step in 0..count) {
            curve.add(depthAt(outgoing, incoming, step.toDouble() / count.toDouble(), outStart, outEnd, inCue, inRate))
        }
        if (curve.all { it == 0.0 }) return emptyList()
        return curve
    }

    /**
     * Running maximum over a depth curve: a filter that reopens
     * mid-transition sounds like a mistake, so depth only ever deepens.
     * Orchard applies this inside the sweep planner; BitChord pre-applies it
     * here so the plan carries the curve the renderer must spend.
     */
    fun runningMax(curve: List<Double>): List<Double> {
        if (curve.isEmpty()) return curve
        val out = ArrayList<Double>(curve.size)
        var reached = Double.NEGATIVE_INFINITY
        for (value in curve) {
            val clamped = if (value.isFinite()) value else 0.0
            reached = maxOf(reached, clamped.coerceIn(0.0, 1.0))
            out.add(reached)
        }
        return out
    }

    /**
     * Geometric travel from the cutoff origin: depth 0.5 is half the
     * octaves, not half the hertz (earmark dsp filters.rs scale_travel).
     * Matches the glide used by the live filter ride.
     */
    fun scaleTravel(originHz: Double, targetHz: Double, depth: Double): Double {
        if (originHz <= 0.0 || targetHz <= 0.0 || !originHz.isFinite() || !targetHz.isFinite()) return targetHz
        val d = depth.coerceIn(0.0, 1.0)
        return originHz * (targetHz / originHz).pow(d)
    }

    /**
     * Interpolated depth at one overlap fraction, pre-running-maxed by the
     * caller. Empty curve reads as full spend (verbatim legacy behavior).
     */
    fun valueAtCurve(curve: List<Double>, fraction: Double): Double {
        if (curve.isEmpty()) return 1.0
        if (curve.size == 1) return curve[0]
        val position = fraction.coerceIn(0.0, 1.0) * (curve.size - 1).toDouble()
        val index = position.toInt().coerceAtMost(curve.size - 2)
        val local = position - index.toDouble()
        return curve[index] + (curve[index + 1] - curve[index]) * local
    }

    /**
     * Decorates a plan with its vocal-depth curve over its own overlap, when
     * the analyses carry vocal evidence. Plans that already carry a curve,
     * or carry none, are returned unchanged.
     */
    fun withVocalDepth(plan: TransitionPlan, outgoing: TrackAnalysis, incoming: TrackAnalysis): TransitionPlan {
        if (plan.vocalDepthCurve.isNotEmpty() || plan.fadeSeconds <= 0.0) return plan
        if (plan.transitionStyle != TransitionStyle.DJ_BLEND &&
            plan.transitionStyle != TransitionStyle.DJ_FILTER &&
            plan.transitionStyle != TransitionStyle.EQUAL_POWER
        ) {
            return plan
        }
        val curve = depthCurve(
            outgoing = outgoing,
            incoming = incoming,
            outStart = plan.transitionStart,
            outEnd = plan.transitionEnd,
            inCue = plan.incomingCueTime,
            inRate = plan.incomingPlaybackRate,
        )
        if (curve.isEmpty()) return plan
        return plan.copy(vocalDepthCurve = runningMax(curve))
    }
}
