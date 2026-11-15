package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.cos

/**
 * Release-to-dry splice (vc69 P1): a smooth crossfade between wet echo or
 * reverb tail and dry signal at transition boundaries, so the handback never
 * clicks. Orchard keeps native playback untouched around a glide and fades
 * into the rendered output over SPLICE_FRAMES (earmark render stage.rs,
 * 1024 frames) with a 0.25 s native edge. BitChord applies the same shape on
 * the mix clock: hold the wet signal at unity, then a cosine equal-power
 * fall to zero over 0.25 s, then dry signal only. All functions are scalar
 * and pure: no allocation, no locking, safe anywhere including the render
 * short path that programs envelope breakpoints on a worker thread.
 */
object ReleaseSplice {
    /** Width of the cosine handback window, in seconds (Orchard NATIVE_EDGE_SECONDS). */
    const val SPLICE_SECONDS = 0.25

    /** Frames in the splice window at the given sample rate. */
    fun spliceFrames(sampleRate: Int): Long =
        if (sampleRate > 0) (SPLICE_SECONDS * sampleRate).toLong() else 0L

    /**
     * Wet weight at one step of the fall: 1 at step zero, 0 at totalSteps,
     * cosine in between. A cosine has flat ends, so the splice meets both
     * full-wet and full-dry without a gain kink.
     */
    fun spliceWeight(stepIndex: Int, totalSteps: Int): Double {
        if (totalSteps <= 0) return 1.0
        if (stepIndex <= 0) return 1.0
        if (stepIndex >= totalSteps) return 0.0
        val t = stepIndex.toDouble() / totalSteps.toDouble()
        return 0.5 * (1.0 + cos(PI * t))
    }

    /**
     * Wet gain at mix second mixSec when the dry release starts at releaseSec
     * and the cosine fall spans widthSec. Unity before the release, zero once
     * the window has passed.
     */
    fun spliceGain(mixSec: Double, releaseSec: Double, widthSec: Double = SPLICE_SECONDS): Double {
        if (widthSec <= 0.0) return 0.0
        if (mixSec < releaseSec) return 1.0
        if (mixSec >= releaseSec + widthSec) return 0.0
        val t = (mixSec - releaseSec) / widthSec
        return 0.5 * (1.0 + cos(PI * t))
    }

    /**
     * Breakpoints for a wet-signal envelope that holds unity until releaseSec
     * and splices to zero over widthSec: (time, value) pairs. steps slices
     * the cosine, so a four-step splice reads as the linear three-point form
     * plus shaped interior points. Always ends at zero.
     */
    fun envelopePoints(releaseSec: Double, widthSec: Double = SPLICE_SECONDS, steps: Int = 4): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>(steps + 2)
        points.add(releaseSec to 1.0)
        val n = if (steps < 1) 1 else steps
        for (step in 1 until n) {
            val t = step.toDouble() / n.toDouble()
            points.add((releaseSec + widthSec * t) to (0.5 * (1.0 + cos(PI * t))))
        }
        points.add((releaseSec + widthSec) to 0.0)
        return points
    }

    /**
     * Envelope times/values arrays shaped for the native renderer
     * setEnvelope call: the release at releaseSec spliced dry over widthSec.
     */
    fun envelopeArrays(releaseSec: Double, widthSec: Double = SPLICE_SECONDS, steps: Int = 4): Pair<DoubleArray, DoubleArray> {
        val points = envelopePoints(releaseSec, widthSec, steps)
        val times = DoubleArray(points.size)
        val values = DoubleArray(points.size)
        for ((index, point) in points.withIndex()) {
            times[index] = point.first
            values[index] = point.second
        }
        return times to values
    }
}
