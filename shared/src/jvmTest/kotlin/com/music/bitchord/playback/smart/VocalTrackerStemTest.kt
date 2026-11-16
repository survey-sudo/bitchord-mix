package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure 44.1 kHz-domain complement tests for [complementAtModelRate].
 *
 * The native ONNX `.so` / model asset is not available on the JVM test
 * classpath, so a linear-interpolation resampler stands in for
 * `MelSpectrogram.resample`. That is sufficient for the invariants under
 * test: the complement is computed at the model rate, both stems are
 * resampled back, sizes match the dry signal at the target rate, and
 * `voc + inst` reconstructs the dry signal (linear resampling is a linear
 * operator, so resampling the two parts and summing resamples the sum).
 */
class VocalTrackerStemTest {

    private fun linearResample(samples: FloatArray, inputRate: Double, outputRate: Double): FloatArray? {
        if (samples.isEmpty() || inputRate <= 0 || outputRate <= 0) return null
        if (inputRate == outputRate) return samples.copyOf()
        val outSize = (samples.size * outputRate / inputRate).roundToInt().coerceAtLeast(1)
        val out = FloatArray(outSize)
        for (i in 0 until outSize) {
            val pos = i * inputRate / outputRate
            val idx = pos.toInt()
            val frac = (pos - idx).toFloat()
            val a = samples[idx]
            val b = samples[(idx + 1).coerceAtMost(samples.size - 1)]
            out[i] = a * (1f - frac) + b * frac
        }
        return out
    }

    private fun sine(size: Int, freq: Double, rate: Double, amp: Float = 0.8f): FloatArray =
        FloatArray(size) { i -> (amp * sin(2.0 * PI * freq * i / rate)).toFloat() }

    @Test
    fun complementAtModelRateIsIdentityAt44100() {
        val rate = 44_100.0
        val dryLeft = sine(4410, 440.0, rate)
        val dryRight = sine(4410, 330.0, rate, amp = 0.6f)
        val vocLeft = FloatArray(dryLeft.size) { dryLeft[it] * 0.7f }
        val vocRight = FloatArray(dryRight.size) { dryRight[it] * 0.7f }

        val result = assertNotNull(
            complementAtModelRate(dryLeft, dryRight, vocLeft, vocRight, rate, rate, ::linearResample),
        )
        val (vocals, instrumental) = result
        assertEquals(dryLeft.size, vocals.first.size)
        assertEquals(dryLeft.size, vocals.second.size)
        assertEquals(dryLeft.size, instrumental.first.size)
        assertEquals(dryLeft.size, instrumental.second.size)
        for (i in dryLeft.indices) {
            assertEquals(dryLeft[i] * 0.3f, instrumental.first[i], 1e-6f, "inst left at $i")
            assertEquals(dryLeft[i], vocals.first[i] + instrumental.first[i], 1e-6f, "voc+inst left at $i")
            assertEquals(dryRight[i], vocals.second[i] + instrumental.second[i], 1e-6f, "voc+inst right at $i")
        }
    }

    @Test
    fun complementAtModelRateResamplesBothStemsTo48000() {
        val modelRate = 44_100.0
        val rate = 48_000.0
        val dryLeft48 = sine(4800, 440.0, rate)
        val dryRight48 = sine(4800, 330.0, rate, amp = 0.6f)
        // Dry at the model rate, as separateBothWindows would have it.
        val dryLeft44 = assertNotNull(linearResample(dryLeft48, rate, modelRate))
        val dryRight44 = assertNotNull(linearResample(dryRight48, rate, modelRate))
        assertEquals(4410, dryLeft44.size)
        val vocLeft44 = FloatArray(dryLeft44.size) { dryLeft44[it] * 0.7f }
        val vocRight44 = FloatArray(dryRight44.size) { dryRight44[it] * 0.7f }

        val result = assertNotNull(
            complementAtModelRate(dryLeft44, dryRight44, vocLeft44, vocRight44, rate, modelRate, ::linearResample),
        )
        val (vocals, instrumental) = result
        // Size equality against the dry signal at the target rate.
        assertEquals(dryLeft48.size, vocals.first.size)
        assertEquals(dryLeft48.size, vocals.second.size)
        assertEquals(dryLeft48.size, instrumental.first.size)
        assertEquals(dryLeft48.size, instrumental.second.size)

        // voc + inst reconstructs the resampled dry signal: residual < -40 dB.
        for ((dry, voc, inst) in listOf(
            Triple(dryLeft48, vocals.first, instrumental.first),
            Triple(dryRight48, vocals.second, instrumental.second),
        )) {
            var residualEnergy = 0.0
            var dryEnergy = 0.0
            for (i in dry.indices) {
                val residual = (voc[i] + inst[i] - dry[i]).toDouble()
                residualEnergy += residual * residual
                dryEnergy += dry[i] * dry[i]
            }
            assertTrue(dryEnergy > 0.0)
            val ratio = residualEnergy / dryEnergy
            assertTrue(ratio < 1e-4, "residual energy ratio $ratio must be < 1e-4 (-40 dB)")
            // RMS form of the same invariant.
            assertTrue(sqrt(ratio) < 0.01, "residual RMS ratio ${sqrt(ratio)} must be < 0.01")
        }
    }

    @Test
    fun complementAtModelRateRejectsMismatchedSizes() {
        val dry = sine(100, 440.0, 44_100.0)
        val voc = FloatArray(99) { 0f }
        assertNull(complementAtModelRate(dry, dry, voc, dry, 44_100.0, 44_100.0, ::linearResample))
        assertNull(complementAtModelRate(FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), 44_100.0, 44_100.0, ::linearResample))
    }

    @Test
    fun complementAtModelRatePropagatesResampleFailure() {
        val dry = sine(100, 440.0, 44_100.0)
        val voc = FloatArray(100) { 0.1f }
        val failing: (FloatArray, Double, Double) -> FloatArray? = { _, _, _ -> null }
        assertNull(complementAtModelRate(dry, dry, voc, voc, 48_000.0, 44_100.0, failing))
        // Identity rate never resamples, so a failing resampler is unused.
        assertNotNull(complementAtModelRate(dry, dry, voc, voc, 44_100.0, 44_100.0, failing))
        // Sanity: complement values are dry minus voc.
        val result = assertNotNull(complementAtModelRate(dry, dry, voc, voc, 44_100.0, 44_100.0, failing))
        assertTrue(abs(result.second.first[0] - (dry[0] - voc[0])) < 1e-6f)
    }
}
