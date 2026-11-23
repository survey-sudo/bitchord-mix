/*
 * Regional loudness for an Automix handover.
 *
 * K-weighting follows ITU-R BS.1770: a high shelf and a high-pass, then the
 * mean square of both channels, gated in 400 ms blocks. The number is used
 * only to set one gain, clamped to −7..+5 dB, so the two windows meet.
 *
 * Copyright (C) 2026 Kushagra Singh. GPL-3.0-or-later, with the rest of BitChord.
 */

package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

object RegionalLoudness {
    // tightened to -3.5..+4.5 dB so a louder incoming is not pulled down -6 dB.
    // Quiet masters match energy; loud masters are capped by peak headroom in the caller.
    private const val MIN_DB = -3.5
    private const val MAX_DB = 4.5

    /**
     * Gain for the incoming window so its loudness matches [out], or null when
     * either window is too short or too quiet to trust. The result is clamped
     * to about −7..+5 dB.
     */
    fun matchGain(
        outLeft: FloatArray,
        outRight: FloatArray,
        outFrom: Int,
        outTo: Int,
        inLeft: FloatArray,
        inRight: FloatArray,
        inFrom: Int,
        inTo: Int,
        sampleRate: Int,
    ): Float? {
        val out = lufs(outLeft, outRight, outFrom, outTo, sampleRate) ?: return null
        val inn = lufs(inLeft, inRight, inFrom, inTo, sampleRate) ?: return null
        val db = (out - inn).coerceIn(MIN_DB, MAX_DB)
        return 10.0.pow(db / 20.0).toFloat()
    }

    /** Gated K-weighted loudness of [from, to), or null when the window cannot be measured. */
    fun lufs(left: FloatArray, right: FloatArray, from: Int, to: Int, sampleRate: Int): Double? {
        if (sampleRate <= 0) return null
        val start = from.coerceAtLeast(0)
        val end = minOf(to, left.size, right.size)
        if (end - start < sampleRate / 5) return null
        val shelf = shelf(sampleRate)
        val highPass = highPass(sampleRate)
        val count = end - start
        val filteredL = FloatArray(count)
        val filteredR = FloatArray(count)
        var i = 0
        while (i < count) {
            val l = highPass.tick(0, shelf.tick(0, left[start + i]))
            val r = highPass.tick(1, shelf.tick(1, right[start + i]))
            filteredL[i] = l
            filteredR[i] = r
            i++
        }
        val block = (0.4 * sampleRate).toInt().coerceAtLeast(1)
        val powers = ArrayList<Double>()
        var at = 0
        while (at + block <= count) {
            var sum = 0.0
            var n = at
            val stop = at + block
            while (n < stop) {
                val l = filteredL[n].toDouble()
                val r = filteredR[n].toDouble()
                sum += l * l + r * r
                n++
            }
            powers += sum / block
            at += block
        }
        if (powers.isEmpty()) {
            var sum = 0.0
            for (n in 0 until count) {
                val l = filteredL[n].toDouble()
                val r = filteredR[n].toDouble()
                sum += l * l + r * r
            }
            powers += sum / count
        }
        val absolute = 10.0.pow((-70.0 + 0.691) / 10.0)
        val above = powers.filter { it >= absolute }
        val gated = if (above.isEmpty()) powers else {
            val mean = above.sum() / above.size
            val relative = mean * 10.0.pow(-10.0 / 10.0)
            val kept = above.filter { it >= relative }
            if (kept.isEmpty()) above else kept
        }
        val mean = gated.sum() / gated.size
        if (mean <= 1e-12) return null
        val lufs = -0.691 + 10.0 * ln(mean) / ln(10.0)
        return if (lufs < -50.0) null else lufs
    }

    private class Biquad(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float) {
        private val z1 = FloatArray(2)
        private val z2 = FloatArray(2)
        fun tick(channel: Int, x: Float): Float {
            val y = b0 * x + z1[channel]
            z1[channel] = b1 * x - a1 * y + z2[channel]
            z2[channel] = b2 * x - a2 * y
            return y
        }
    }

    /** BS.1770 high shelf, about +4 dB from 1.7 kHz. */
    private fun shelf(sampleRate: Int): Biquad {
        val f0 = 1681.974450955533
        val gain = 3.999843853973347
        val q = 0.7071752369554196
        val k = tan(PI * f0 / sampleRate)
        val vh = 10.0.pow(gain / 20.0)
        val vb = vh.pow(0.4996667741545416)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            b0 = ((vh + vb * k / q + k * k) / a0).toFloat(),
            b1 = (2.0 * (k * k - vh) / a0).toFloat(),
            b2 = ((vh - vb * k / q + k * k) / a0).toFloat(),
            a1 = (2.0 * (k * k - 1.0) / a0).toFloat(),
            a2 = ((1.0 - k / q + k * k) / a0).toFloat(),
        )
    }

    /** BS.1770 high-pass, about 38 Hz. */
    private fun highPass(sampleRate: Int): Biquad {
        val f0 = 38.13547087602444
        val q = 0.5003270373238773
        val k = tan(PI * f0 / sampleRate)
        val a0 = 1.0 + k / q + k * k
        return Biquad(
            b0 = (1.0 / a0).toFloat(),
            b1 = (-2.0 / a0).toFloat(),
            b2 = (1.0 / a0).toFloat(),
            a1 = (2.0 * (k * k - 1.0) / a0).toFloat(),
            a2 = ((1.0 - k / q + k * k) / a0).toFloat(),
        )
    }
}
