package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
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

    /**
     * Floor-sized stand-in for the native resampler, which sizes output as
     * `floor(size * ratio)` (native/analyzer/resampler.cpp), not round.
     */
    private fun linearResample(samples: FloatArray, inputRate: Double, outputRate: Double): FloatArray? {
        if (samples.isEmpty() || inputRate <= 0 || outputRate <= 0) return null
        if (inputRate == outputRate) return samples.copyOf()
        val outSize = floor(samples.size * outputRate / inputRate).toInt().coerceAtLeast(1)
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

    @Test
    fun complementAtModelRateAcceptsNativeFloorSizeAt48000() {
        val dry = sine(100, 440.0, 44_100.0)
        val voc = FloatArray(100) { 0.1f }
        // Native floor(100 * 48000 / 44100) = 108, not round's 109.
        val result = assertNotNull(
            complementAtModelRate(dry, dry, voc, voc, 48_000.0, 44_100.0, ::linearResample),
        )
        assertEquals(108, result.first.first.size)
        assertEquals(108, result.first.second.size)
        assertEquals(108, result.second.first.size)
        assertEquals(108, result.second.second.size)
    }

    @Test
    fun complementAtModelRateRejectsShortResampleOutputWithoutPadding() {
        val dry = sine(100, 440.0, 44_100.0)
        val voc = FloatArray(100) { 0.1f }
        // Exact native floor size is still accepted for the same inputs.
        assertNotNull(complementAtModelRate(dry, dry, voc, voc, 48_000.0, 44_100.0, ::linearResample))
        // Floor target is 108. A 107 answer is malformed pair output:
        // reject it rather than zero-padding to 108.
        val short: (FloatArray, Double, Double) -> FloatArray? = { _, _, _ -> FloatArray(107) { 0.1f } }
        assertNull(complementAtModelRate(dry, dry, voc, voc, 48_000.0, 44_100.0, short))
    }

    @Test
    fun complementAtModelRateRejectsLongResampleOutputWithoutTruncation() {
        val dry = sine(100, 440.0, 44_100.0)
        val voc = FloatArray(100) { 0.1f }
        // 109 is the old round size and is long under the floor target.
        val long: (FloatArray, Double, Double) -> FloatArray? = { _, _, _ -> FloatArray(109) { 0.1f } }
        assertNull(complementAtModelRate(dry, dry, voc, voc, 48_000.0, 44_100.0, long))
    }

    @Test
    fun complementAtModelRateRejectsSingleMalformedStem() {
        val dry = sine(100, 440.0, 44_100.0)
        val voc = FloatArray(100) { 0.1f }
        var calls = 0
        val oneBad: (FloatArray, Double, Double) -> FloatArray? = { samples, inRate, outRate ->
            calls++
            val valid = linearResample(samples, inRate, outRate)
            // Only the last stem (inst right) is 107, one short of the
            // floor target 108 for 100 samples at 44.1k -> 48k.
            if (calls == 4) valid?.copyOf(valid.size - 1) else valid
        }
        assertNull(complementAtModelRate(dry, dry, voc, voc, 48_000.0, 44_100.0, oneBad))
        assertEquals(4, calls)
    }

    private fun stemPair(dryLeft: FloatArray, dryRight: FloatArray, stemSize: Int): Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>> {
        // Voc/inst split dry 70/30 so head voc+inst == dry exactly.
        val vocLeft = FloatArray(stemSize) { dryLeft[it] * 0.7f }
        val vocRight = FloatArray(stemSize) { dryRight[it] * 0.7f }
        val instLeft = FloatArray(stemSize) { dryLeft[it] * 0.3f }
        val instRight = FloatArray(stemSize) { dryRight[it] * 0.3f }
        return (vocLeft to vocRight) to (instLeft to instRight)
    }

    @Test
    fun completeDualStemEndpointAcceptsBoundedLossAt100() {
        val dryLeft = sine(100, 440.0, 48_000.0)
        val dryRight = sine(100, 330.0, 48_000.0, amp = 0.6f)
        // Full round trip: floor(floor(100*44100/48000)*48000/44100)=99, loss 1 <= 2.
        val (vocals, instrumental) = stemPair(dryLeft, dryRight, 99)
        val result = assertNotNull(
            completeDualStemEndpoint(vocals, instrumental, dryLeft, dryRight, 48_000.0, 44_100.0),
        )
        val (vocExt, instExt) = result
        assertEquals(100, vocExt.first.size)
        assertEquals(100, vocExt.second.size)
        assertEquals(100, instExt.first.size)
        assertEquals(100, instExt.second.size)
        // Tail: vocal held, instrumental = dry - vocal, so g=1 reconstructs dry.
        assertEquals(vocals.first[98], vocExt.first[99], 1e-6f)
        assertEquals(dryLeft[99] - vocExt.first[99], instExt.first[99], 1e-6f)
        for (i in dryLeft.indices) {
            assertEquals(dryLeft[i], vocExt.first[i] + instExt.first[i], 1e-6f, "left at $i")
            assertEquals(dryRight[i], vocExt.second[i] + instExt.second[i], 1e-6f, "right at $i")
        }
    }

    @Test
    fun completeDualStemEndpointAcceptsBoundedLossAt4801() {
        val dryLeft = sine(4801, 440.0, 48_000.0)
        val dryRight = sine(4801, 330.0, 48_000.0, amp = 0.6f)
        // floor(floor(4801*44100/48000)*48000/44100)=4800, loss 1.
        val (vocals, instrumental) = stemPair(dryLeft, dryRight, 4800)
        val result = assertNotNull(
            completeDualStemEndpoint(vocals, instrumental, dryLeft, dryRight, 48_000.0, 44_100.0),
        )
        assertEquals(4801, result.first.first.size)
        assertEquals(4801, result.second.second.size)
        assertEquals(vocals.first[4799], result.first.first[4800], 1e-6f)
        assertEquals(dryLeft[4800] - result.first.first[4800], result.second.first[4800], 1e-6f)
    }

    @Test
    fun completeDualStemEndpointAcceptsMaxLoss2() {
        val dryLeft = sine(148, 440.0, 48_000.0)
        val dryRight = sine(148, 330.0, 48_000.0, amp = 0.6f)
        // floor(floor(148*44100/48000)*48000/44100)=146, loss 2 == ceil(48000/44100).
        val (vocals, instrumental) = stemPair(dryLeft, dryRight, 146)
        val result = assertNotNull(
            completeDualStemEndpoint(vocals, instrumental, dryLeft, dryRight, 48_000.0, 44_100.0),
        )
        assertEquals(148, result.first.first.size)
        assertEquals(148, result.second.first.size)
    }

    @Test
    fun completeDualStemEndpointRejectsLossAtLeast3() {
        val dryLeft = sine(100, 440.0, 48_000.0)
        val dryRight = sine(100, 330.0, 48_000.0, amp = 0.6f)
        // Loss 3 exceeds ceil(48000/44100)=2: arbitrary truncation, not endpoint loss.
        val (vocals, instrumental) = stemPair(dryLeft, dryRight, 97)
        assertNull(completeDualStemEndpoint(vocals, instrumental, dryLeft, dryRight, 48_000.0, 44_100.0))
    }

    @Test
    fun completeDualStemEndpointRejectsOversizedEmptyAndMismatch() {
        val dryLeft = sine(100, 440.0, 48_000.0)
        val dryRight = sine(100, 330.0, 48_000.0, amp = 0.6f)
        // Oversized: negative loss.
        val (bigVoc, bigInst) = stemPair(dryLeft, dryRight, 99)
        val bigVocals = bigVoc.first.copyOf(101) to bigVoc.second.copyOf(101)
        val oversizedInstrumental = bigInst.first.copyOf(101) to bigInst.second.copyOf(101)
        assertNull(completeDualStemEndpoint(bigVocals, oversizedInstrumental, dryLeft, dryRight, 48_000.0, 44_100.0))
        // Empty stems.
        val empty = FloatArray(0)
        assertNull(
            completeDualStemEndpoint(empty to empty, empty to empty, dryLeft, dryRight, 48_000.0, 44_100.0),
        )
        // Mismatched channels: one stem 98, rest 99.
        val (vocals, instrumental) = stemPair(dryLeft, dryRight, 99)
        val mismatched = vocals.first.copyOf(98) to vocals.second
        assertNull(
            completeDualStemEndpoint(mismatched, instrumental, dryLeft, dryRight, 48_000.0, 44_100.0),
        )
        // Dry mismatch.
        assertNull(
            completeDualStemEndpoint(
                vocals, instrumental, dryLeft, dryRight.copyOf(99), 48_000.0, 44_100.0,
            ),
        )
    }
}
