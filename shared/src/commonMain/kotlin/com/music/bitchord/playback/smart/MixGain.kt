package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The fader curves a blend rides. Equal-power, so the two tracks sum flat,
 * and a filter fall that is the same curve bent earlier. Both are continuous:
 * nothing in here steps.
 */
object MixGain {
    /** Minimum headroom trim; only -0.5 dB (native -1 dBTP limiter guards peaks). */
    const val HEADROOM_FLOOR = 0.94f

    fun rise(progress: Float): Float = sin(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    fun fall(progress: Float): Float = cos(progress.coerceIn(0f, 1f) * PI.toFloat() / 2f)

    /** Power sum of the two faders at [progress]; exactly 1 on a pure equal-power fade. */
    fun powerSum(progress: Float): Float {
        val r = rise(progress); val f = fall(progress)
        return r * r + f * f
    }

    /**
     * Headroom trim for the sum of two faders. djmix9: floored at
     * [HEADROOM_FLOOR] so a midpoint no longer costs ~3 dB; the native
     * -1 dBTP limiter handles true peaks.
     */
    fun headroomTrim(incomingGain: Float, outgoingGain: Float): Float {
        val sum = incomingGain + outgoingGain
        return if (sum <= 1f) 1f else (1f / kotlin.math.sqrt(sum)).coerceAtLeast(HEADROOM_FLOOR)
    }

    /** A filter ride. The low-pass is doing the tonal work; the fader just leaves. */
    fun filterFall(progress: Float): Float = fall(progress.coerceIn(0f, 1f).pow(1.20f))

    /**
     * Sampled equal-power envelope points for [rise] over [from]..[to]
     * (mix-clock seconds). The native renderer ramps gains per sample from
     * these knots; sampling the sin/cos law keeps the blend power-flat
     * instead of the old linear plateaus that overshoot then dip.
     */
    fun risePoints(from: Double, to: Double, steps: Int = 16): Pair<DoubleArray, FloatArray> {
        val n = steps.coerceAtLeast(1)
        val times = DoubleArray(n + 1) { from + (to - from) * it / n }
        val values = FloatArray(n + 1) { rise((it.toFloat() / n).coerceIn(0f, 1f)) }
        return times to values
    }

    /**
     * djmix25 P1.12 learned EQ/fader template (djmix-analysis from stats,
     * Kim et al. ISMIR 2020): the bass-swap moment and fader shape per
     * template, as a small 9-breakpoint table. [swapBeats] is the bass-swap
     * width in beats; [breakpoints] are the outgoing fader gains at
     * progress 0, 0.125, ..., 1.0.
     */
    data class FaderParams(
        val template: String,
        val swapBeats: Double,
        val breakpoints: FloatArray,
    )

    fun faderTemplate(progress: Float, params: FaderParams): Float {
        val bp = params.breakpoints
        if (bp.isEmpty()) return fall(progress)
        val x = progress.coerceIn(0f, 1f) * (bp.size - 1)
        val i = kotlin.math.floor(x).toInt().coerceIn(0, bp.size - 2)
        val f = x - i
        val a = bp[i]; val b = bp[i + 1]
        return (a + (b - a) * f).coerceIn(0f, 1f)
    }

    /** Canned templates; swap track kept at 1 beat for "club" (legacy). */
    fun faderTemplateFor(template: String): FaderParams = when (template) {
        // Club: equal-power, 1-beat swap at mid-ramp (legacy Clock.swapBar).
        "club" -> FaderParams("club", 1.0, floatArrayOf(1f, 1f, 0.98f, 0.92f, 0.78f, 0.58f, 0.36f, 0.16f, 0f))
        // Lyrical: late, tight swap to protect the vocal (0.5 beat, later).
        "lyrical" -> FaderParams("lyrical", 0.5, floatArrayOf(1f, 1f, 1f, 0.99f, 0.94f, 0.78f, 0.5f, 0.22f, 0f))
        // Radio: early swap, gentle fader.
        "radio" -> FaderParams("radio", 0.5, floatArrayOf(1f, 0.99f, 0.96f, 0.88f, 0.72f, 0.52f, 0.32f, 0.14f, 0f))
        // Ambient: wide swap, slow fader.
        "ambient" -> FaderParams("ambient", 2.0, floatArrayOf(1f, 1f, 1f, 0.98f, 0.92f, 0.78f, 0.52f, 0.26f, 0f))
        else -> FaderParams("club", 1.0, floatArrayOf(1f, 1f, 0.98f, 0.92f, 0.78f, 0.58f, 0.36f, 0.16f, 0f))
    }

    /** Sampled equal-power [fall] points over [from]..[to]. See [risePoints]. */
    fun fallPoints(from: Double, to: Double, steps: Int = 16): Pair<DoubleArray, FloatArray> {
        val n = steps.coerceAtLeast(1)
        val times = DoubleArray(n + 1) { from + (to - from) * it / n }
        val values = FloatArray(n + 1) { fall((it.toFloat() / n).coerceIn(0f, 1f)) }
        return times to values
    }
}
