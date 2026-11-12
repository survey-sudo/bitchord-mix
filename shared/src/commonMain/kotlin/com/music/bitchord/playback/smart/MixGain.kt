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
    /** Minimum headroom trim; keeps the mid-blend dip within ~1.5 dB. */
    const val HEADROOM_FLOOR = 0.85f

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
    fun filterFall(progress: Float): Float = fall(progress.coerceIn(0f, 1f).pow(1.6f))
}
