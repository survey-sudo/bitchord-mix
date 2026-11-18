/*
 * Ported from Orchard (https://github.com/SFG5453/Orchard).
 *
 * Copyright (C) 2026 SFG545 (original Orchard implementation)
 * Copyright (C) 2026 Kushagra Singh (BitChord adaptation)
 *
 * Orchard's original source is licensed under the GNU Affero General Public
 * License, version 3 or later. Per AGPLv3 section 13, this file is combined
 * here into BitChord -- a work licensed under the GNU General Public
 * License, version 3 or later -- and remains itself governed by the AGPLv3
 * as part of that combination.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.bitchord.playback.smart

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.floor

/** The linear-frequency STFT front end open-unmix was trained on. */
object VocalSpectrogram {
    val available: Boolean get() = MelSpectrogram.available
    val bins: Int by lazy { if (available) nativeBins() else 2049 }
    val sampleRate: Double by lazy { if (available) nativeSampleRate() else 44_100.0 }
    val hop: Int by lazy { if (available) nativeHop() else 1024 }
    val fftSize: Int by lazy { if (available) nativeFftSize() else 4096 }
    val frameRate: Double get() = sampleRate / hop

    /**
     * Computes the magnitude STFT for planar stereo at [sampleRate].
     *
     * Returns null when the library is missing, the rate is wrong, or the input is shorter than one
     * padded frame, all of which mean "no mask available" rather than an error.
     */
    fun compute(left: FloatArray, right: FloatArray, rate: Double = sampleRate): Spectrogram? {
        if (!available || left.isEmpty() || left.size != right.size) return null
        val values = nativeCompute(left, right, rate)
        if (values.isEmpty()) return null
        return Spectrogram(values, frames = values.size / (CHANNELS * bins), bins = bins)
    }

    /**
     * Bin-major, flattened: channel c, bin b, frame f is at `(c * bins + b) * frames + f`. That
     * ordering is not the natural one for an STFT computed a frame at a time; it is chosen to
     * match the model's `[1, 2, bins, frames]` tensor exactly, so nothing has to transpose.
     */
    /**
     * Windowed Wiener stem separation at 44.1 kHz. Returns planar outLeft+outRight
     * (length 2 * left.size), or empty when native is unavailable or shapes mismatch.
     */
    fun separate(
        left: FloatArray,
        right: FloatArray,
        rate: Double,
        targetMag: FloatArray,
        targetFrames: Int,
        keepVocals: Boolean,
    ): FloatArray {
        if (!available || left.isEmpty() || left.size != right.size) return FloatArray(0)
        val out = nativeSeparate(left, right, rate, targetMag, targetFrames, keepVocals)
        if (out.size != 2 * left.size) return FloatArray(0)
        return out
    }
 
    data class Spectrogram(val values: FloatArray, val frames: Int, val bins: Int) {
        override fun equals(other: Any?): Boolean =
            this === other || (other is Spectrogram && frames == other.frames &&
                bins == other.bins && values.contentEquals(other.values))

        override fun hashCode(): Int = 31 * (31 * values.contentHashCode() + frames) + bins
    }

    const val CHANNELS = 2

    @JvmStatic private external fun nativeCompute(left: FloatArray, right: FloatArray, rate: Double): FloatArray
    @JvmStatic private external fun nativeSeparate(left: FloatArray, right: FloatArray, rate: Double, targetMag: FloatArray, targetFrames: Int, keepVocals: Boolean): FloatArray
    @JvmStatic private external fun nativeBins(): Int
    @JvmStatic private external fun nativeSampleRate(): Double
    @JvmStatic private external fun nativeHop(): Int
    @JvmStatic private external fun nativeFftSize(): Int
}

/**
 * Pure complement helper at the model rate.
 *
 * [dryLeft]/[dryRight] and [vocLeft]/[vocRight] are all at [modelRate]
 * (typically 44.1 kHz). The instrumental complement is computed *there*,
 * `dry - voc`, and only then are vocals and instrumental both resampled
 * to [rate] via [resample]. Computing the complement in the model domain
 * keeps `voc + inst == dry` exact before resampling; resampling first and
 * subtracting at [rate] would fold resampler error into the stem.
 *
 * Returns `(vocals, instrumental)` at [rate], each planar left/right sized
 * to `floor(dry.size * rate / modelRate)`, or null on size mismatch / resample
 * failure. Extracted so JVM unit tests can exercise the 44.1k<->48k
 * subtraction without the native ONNX library.
 */
internal fun complementAtModelRate(
    dryLeft: FloatArray,
    dryRight: FloatArray,
    vocLeft: FloatArray,
    vocRight: FloatArray,
    rate: Double,
    modelRate: Double = VocalSpectrogram.sampleRate,
    resample: (FloatArray, Double, Double) -> FloatArray?,
): Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>? {
    if (dryLeft.isEmpty() || dryLeft.size != dryRight.size ||
        dryLeft.size != vocLeft.size || dryLeft.size != vocRight.size
    ) return null
    if (rate <= 0 || modelRate <= 0) return null
    val instLeft44 = FloatArray(dryLeft.size) { dryLeft[it] - vocLeft[it] }
    val instRight44 = FloatArray(dryRight.size) { dryRight[it] - vocRight[it] }
    if (rate == modelRate) {
        return (vocLeft to vocRight) to (instLeft44 to instRight44)
    }
    // Native resampler sizes output as floor(size * ratio)
    // (native/analyzer/resampler.cpp), not round: e.g. floor(100 * 48000 /
    // 44100) = 108, where round would give 109 and reject valid output.
    val targetSize = floor(dryLeft.size * rate / modelRate).toInt().coerceAtLeast(1)
    val vocBackLeft = resample(vocLeft, modelRate, rate) ?: return null
    val vocBackRight = resample(vocRight, modelRate, rate) ?: return null
    val instBackLeft = resample(instLeft44, modelRate, rate) ?: return null
    val instBackRight = resample(instRight44, modelRate, rate) ?: return null
    // Exact-size pair contract: a short/long resample answer is malformed,
    // not a rounding to be zero-padded/truncated to targetSize. Return null
    // so callers fall back instead of trusting a padded/truncated stem.
    if (vocBackLeft.size != targetSize || vocBackRight.size != targetSize ||
        instBackLeft.size != targetSize || instBackRight.size != targetSize
    ) return null
    return (vocBackLeft to vocBackRight) to (instBackLeft to instBackRight)
}

/**
 * Bounded endpoint completion for the dual-stem round trip.
 *
 * The native resampler sizes output as `floor(size * ratio)`, so the
 * 48k -> 44.1k -> 48k round trip deterministically loses a bounded number
 * of endpoint samples: with `r = modelRate / rate`, `M = floor(N*r)` and
 * `T = floor(M/r) <= N`, and `N - T < 1 + rate/modelRate`, i.e. at most
 * `ceil(rate/modelRate)` samples (2 at 48k/44.1k). This helper accepts
 * only that predicted loss (`0..ceil(rate/modelRate)`), not arbitrary
 * padding: any longer, grossly shorter, empty, or channel-mismatched
 * stem is malformed and returns null so callers fall back.
 *
 * For the accepted missing tail, vocal is extended with its last sample
 * (deterministic hold) and instrumental tail is set to `dry - extended
 * vocal`, so `inst + g*voc` at duck gain `g = 1` reconstructs `dry`
 * exactly on the completed samples (and the head keeps the resampled
 * complement, where `voc + inst` is the resampled dry). This is endpoint
 * completion for double-floor resampling, not arbitrary zero-padding.
 * Returns exact dry-size arrays, or null on any other size.
 */
internal fun completeDualStemEndpoint(
    vocals: Pair<FloatArray, FloatArray>,
    instrumental: Pair<FloatArray, FloatArray>,
    dryLeft: FloatArray,
    dryRight: FloatArray,
    rate: Double,
    modelRate: Double,
): Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>? {
    if (rate <= 0 || modelRate <= 0) return null
    if (dryLeft.isEmpty() || dryLeft.size != dryRight.size) return null
    val vocLeft = vocals.first
    val vocRight = vocals.second
    val instLeft = instrumental.first
    val instRight = instrumental.second
    if (vocLeft.isEmpty() || vocRight.isEmpty() ||
        instLeft.isEmpty() || instRight.isEmpty()
    ) return null
    val stemSize = vocLeft.size
    if (vocRight.size != stemSize || instLeft.size != stemSize || instRight.size != stemSize) {
        return null
    }
    val targetSize = dryLeft.size
    val loss = targetSize - stemSize
    val maxLoss = ceil(rate / modelRate).toInt()
    if (loss < 0 || loss > maxLoss) return null
    if (loss == 0) {
        return (vocLeft to vocRight) to (instLeft to instRight)
    }
    fun completeChannel(voc: FloatArray, inst: FloatArray, dry: FloatArray): Pair<FloatArray, FloatArray> {
        val vocExt = voc.copyOf(targetSize)
        val instExt = inst.copyOf(targetSize)
        val lastVoc = voc[stemSize - 1]
        for (i in stemSize until targetSize) {
            vocExt[i] = lastVoc
            instExt[i] = dry[i] - vocExt[i]
        }
        return vocExt to instExt
    }
    val (vocLeftExt, instLeftExt) = completeChannel(vocLeft, instLeft, dryLeft)
    val (vocRightExt, instRightExt) = completeChannel(vocRight, instRight, dryRight)
    return (vocLeftExt to vocRightExt) to (instLeftExt to instRightExt)
}

/**
 * Vocal-presence tracking with open-unmix's "vocals" target (Stöter & Liutkus, Inria/SigSep).
 *
 * The transition policy uses this to avoid mixing two vocals over each other: a blend where both
 * tracks are singing is the one case that reliably sounds wrong however well the beats line up.
 *
 * Chosen because its **weights** are MIT, confirmed on the Zenodo deposit rather than inferred from
 * the code repository. Meta's htdemucs separates better but releases its pretrained weights under
 * CC-BY-NC-4.0, which a distributed application cannot ship, and its ONNX export additionally has
 * unresolved blockers around complex-valued STFT ops.
 *
 * Only the vocals target is used. open-unmix trains four independent checkpoints; BitChord needs to
 * know how much vocal content is present at an instant, not to reconstruct four stems.
 */
class VocalTracker(
    /**
     * Where the model lives, resolved lazily.
     *
     * A path rather than a [Context]: this is the only thing the tracker ever
     * wanted the platform for, and each application already knows how to put
     * its own copy of the weights somewhere readable — unpacked from assets on
     * Android, from the jar's resources on the desktop.
     */
    private val modelPath: () -> String,
    /** How many threads inference may use; the vocal-activity pass is the expensive one. */
    private val inferenceThreads: () -> Int,
    /** NPU (vc69): QNN configuration, vendor detection, backend path, and context cache dir. Defaults are CPU-safe. */
    private val npuConfig: () -> NpuSupport.NpuConfig = { NpuSupport.NpuConfig() },
    private val npuVendor: () -> NpuSupport.Vendor = { NpuSupport.Vendor.NONE },
    private val npuBackendPath: () -> String? = { null },
    private val npuCacheDir: () -> File? = { null },
) {

    @Volatile private var session: OrtSession? = null
    @Volatile private var sessionThreads = 0
    private val lock = Any()

    private fun session(): OrtSession? {
        val threads = inferenceThreads()
        session?.takeIf { sessionThreads == threads }?.let { return it }
        synchronized(lock) {
            session?.takeIf { sessionThreads == threads }?.let { return it }
            runCatching { session?.close() }
            session = null
            return runCatching {
                val file = File(modelPath())
                // vc84: umxhq's dynamic-quant/LSTM graph stays on CPU, even when beat QNN is enabled.
                NpuSession.open(file.absolutePath, threads, npuConfig().copy(enabled = false), NpuSupport.Vendor.NONE, null, null)
                    .also {
                        session = it
                        sessionThreads = threads
                    }
            }.onFailure { AnalysisLog.warn("Vocal model unavailable; no mask will be produced", it) }
                .getOrNull()
        }
    }

    /**
     * Returns one vocal-presence value per STFT frame in [0, 1], or null when unavailable.
     *
     * The model's input width is fixed at [FIXED_FRAMES] (~22.8 s), which was chosen upstream to
     * cover a transition overlap plus padding. Shorter input is zero-padded; longer is refused
     * rather than chunked, because a transition never needs more than one window.
     */
    fun track(left: FloatArray, right: FloatArray, rate: Double): FloatArray? {
        if (!VocalSpectrogram.available) return null
        val resampledLeft = MelSpectrogram.resample(left, rate, VocalSpectrogram.sampleRate) ?: return null
        val resampledRight = MelSpectrogram.resample(right, rate, VocalSpectrogram.sampleRate) ?: return null

        val started = System.currentTimeMillis()
        val spectrogram = VocalSpectrogram.compute(resampledLeft, resampledRight) ?: return null
        if (spectrogram.frames > FIXED_FRAMES) {
            AnalysisLog.warn("Window of ${spectrogram.frames} frames exceeds the model's $FIXED_FRAMES")
            return null
        }
        val active = session() ?: return null

        return runCatching {
            val bins = spectrogram.bins
            // Direct, and sized for the model rather than for the input, so the
            // ~16MB of mix never lands on the Java heap and ORT reads it where it
            // lies instead of copying it into native memory. Both matter: this
            // runs on devices whose whole Java heap is 256MB, and the two copies
            // this replaces were together enough to end the process.
            val backing = ByteBuffer
                .allocateDirect(VocalSpectrogram.CHANNELS * bins * FIXED_FRAMES * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            fillFixedFrames(backing.asFloatBuffer(), spectrogram.values, bins, spectrogram.frames)
            val environment = OrtEnvironment.getEnvironment()
            val shape = longArrayOf(1, VocalSpectrogram.CHANNELS.toLong(), bins.toLong(), FIXED_FRAMES.toLong())

            // A fresh view per reader rather than rewinding one: FloatBuffer's own
            // rewind() and position(int) are Java 9 covariant overrides that
            // Android's FloatBuffer does not declare, so they compile against the
            // current SDK and throw NoSuchMethodError on the API 28 devices this
            // has to run on. asFloatBuffer() hands back a view at position 0 and
            // has been there since API 1.
            OnnxTensor.createTensor(environment, backing.asFloatBuffer(), shape).use { tensor ->
                active.run(mapOf(active.inputNames.first() to tensor)).use { outputs ->
                    // The output tensor's buffer, not `outputs.get(0).value`: that
                    // property boxes this [1, 2, bins, FIXED_FRAMES] result into
                    // one FloatArray per bin per channel — 4098 objects and ~16MB
                    // per call — when the only thing read from it is a band
                    // average.
                    val target = (outputs.get(0) as OnnxTensor).floatBuffer
                    val curve = reduceToBandCurve(backing.asFloatBuffer(), target, bins, spectrogram.frames)
                    AnalysisLog.warn("vocal mask ${spectrogram.frames} frames in " +
                            "${System.currentTimeMillis() - started}ms",
                    )
                    curve
                }
            }
        }.onFailure { AnalysisLog.warn("Vocal inference failed", it) }.getOrNull()
    }

    /**
     * Writes the spectrogram into the model's fixed width, zero-padding each bin's tail.
     *
     * The stride changes as well as the length: the source is stored at `frames` per bin and the
     * model wants [FIXED_FRAMES], so this is a re-stride rather than an append.
     *
     * Sequential relative puts only. Seeking to each bin's start would be the obvious way to write
     * it, but that needs `FloatBuffer.position(int)`, which Android declares on `Buffer` alone;
     * writing the pad out explicitly keeps every call on a member that has existed since API 1.
     */
    private fun fillFixedFrames(into: FloatBuffer, values: FloatArray, bins: Int, frames: Int) {
        if (frames == FIXED_FRAMES) {
            into.put(values)
            return
        }
        val pad = FloatArray(FIXED_FRAMES - frames)
        for (channel in 0 until VocalSpectrogram.CHANNELS) {
            for (bin in 0 until bins) {
                into.put(values, (channel * bins + bin) * frames, frames)
                into.put(pad)
            }
        }
    }

    /**
     * Averages `mask = target / (mix + eps)` across a frequency band, then across channels.
     *
     * Band-averaging rather than a full per-bin mask: the only consumer is a single number per
     * instant (how vocal this moment is), so per-bin resolution would be work with no reader.
     * Only the frames carrying real audio are reduced; the padded tail's mask is meaningless and
     * folding it in would drag every short window toward silence.
     */
    private fun reduceToBandCurve(
        mix: FloatBuffer,
        target: FloatBuffer,
        bins: Int,
        usableFrames: Int,
    ): FloatArray {
        val lowBin = floor(LOW_HZ * VocalSpectrogram.fftSize / VocalSpectrogram.sampleRate)
            .toInt().coerceAtLeast(0)
        val highBin = ceil(HIGH_HZ * VocalSpectrogram.fftSize / VocalSpectrogram.sampleRate)
            .toInt().coerceAtMost(bins - 1)
        if (highBin <= lowBin || usableFrames <= 0) return FloatArray(0)

        val curve = FloatArray(usableFrames)
        for (frame in 0 until usableFrames) {
            var sum = 0.0
            var count = 0
            for (channel in 0 until VocalSpectrogram.CHANNELS) {
                for (bin in lowBin..highBin) {
                    // Both buffers carry the model's [1, 2, bins, FIXED_FRAMES]
                    // layout, so one index reads the same cell of each. Absolute
                    // get, so neither view's position matters.
                    val index = (channel * bins + bin) * FIXED_FRAMES + frame
                    val mixValue = mix.get(index)
                    if (mixValue <= 1e-6f) continue
                    val ratio = target.get(index) / mixValue
                    sum += ratio.coerceIn(0f, 1f)
                    count += 1
                }
            }
            curve[frame] = if (count > 0) (sum / count).toFloat() else 0f
        }
        return curve
    }

    /**
     * True on-device umxhq Wiener stem separation over one transition window.
     * Returns separated pair, or null (safe fallback: caller keeps dry signal).
     */
    fun separateWindow(
        left: FloatArray,
        right: FloatArray,
        rate: Double,
        keepVocals: Boolean,
    ): Pair<FloatArray, FloatArray>? {
        if (!VocalSpectrogram.available || left.isEmpty() || left.size != right.size) return null
        val sr = VocalSpectrogram.sampleRate
        val resLeft = MelSpectrogram.resample(left, rate, sr) ?: return null
        val resRight = MelSpectrogram.resample(right, rate, sr) ?: return null
        val spec = VocalSpectrogram.compute(resLeft, resRight, sr) ?: return null
        if (spec.frames > FIXED_FRAMES) return null
        val bins = spec.bins
        val frames = spec.frames
        val active = session() ?: return null
        return runCatching {
            val backing = ByteBuffer
                .allocateDirect(VocalSpectrogram.CHANNELS * bins * FIXED_FRAMES * Float.SIZE_BYTES)
                .order(ByteOrder.nativeOrder())
            fillFixedFrames(backing.asFloatBuffer(), spec.values, bins, frames)
            val environment = OrtEnvironment.getEnvironment()
            val shape = longArrayOf(1, VocalSpectrogram.CHANNELS.toLong(), bins.toLong(), FIXED_FRAMES.toLong())
            OnnxTensor.createTensor(environment, backing.asFloatBuffer(), shape).use { tensor ->
                active.run(mapOf(active.inputNames.first() to tensor)).use { outputs ->
                    val target = (outputs.get(0) as OnnxTensor).floatBuffer
                    val compact = FloatArray(VocalSpectrogram.CHANNELS * bins * frames)
                    for (c in 0 until VocalSpectrogram.CHANNELS) {
                        for (b in 0 until bins) {
                            val srcBase = (c * bins + b) * FIXED_FRAMES
                            val dstBase = (c * bins + b) * frames
                            for (f in 0 until frames) {
                                compact[dstBase + f] = target.get(srcBase + f)
                            }
                        }
                    }
                    val out = VocalSpectrogram.separate(resLeft, resRight, sr, compact, frames, keepVocals)
                    if (out.isEmpty()) return@runCatching null
                    val outLeft44 = out.copyOfRange(0, resLeft.size)
                    val outRight44 = out.copyOfRange(resLeft.size, out.size)
                    if (rate == sr) {
                        outLeft44 to outRight44
                    } else {
                        val backLeft = MelSpectrogram.resample(outLeft44, sr, rate) ?: return@runCatching null
                        val backRight = MelSpectrogram.resample(outRight44, sr, rate) ?: return@runCatching null
                        fun fixed(a: FloatArray): FloatArray = if (a.size == left.size) a else a.copyOf(left.size)
                        fixed(backLeft) to fixed(backRight)
                    }
                }
            }
        }.getOrNull()
    }
 
    /**
     * Sample-exact dual-stem separation: vocals + instrumental from one
     * ONNX pass. Resampled at the model rate, one VocalSpectrogram.separate
     * call (keepVocals true) on the compact target, the instrumental
     * complement computed in the 44.1 kHz model domain, then both stems
     * resampled back to left.size (bounded endpoint completion for the
     * double-floor round trip), or null on size mismatch.
     */
    fun separateBothWindows(
        left: FloatArray,
        right: FloatArray,
        rate: Double,
    ): Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>? {
        if (!VocalSpectrogram.available || left.isEmpty() || left.size != right.size) return null
        val sr = VocalSpectrogram.sampleRate
        val resLeft = MelSpectrogram.resample(left, rate, sr) ?: return null
        val resRight = MelSpectrogram.resample(right, rate, sr) ?: return null
        val spec = VocalSpectrogram.compute(resLeft, resRight, sr) ?: return null
        if (spec.frames > FIXED_FRAMES) return null
        val bins = spec.bins
        val frames = spec.frames
        val active = session() ?: return null
        return runCatching {
            val backing = java.nio.ByteBuffer
                .allocateDirect(VocalSpectrogram.CHANNELS * bins * FIXED_FRAMES * Float.SIZE_BYTES)
                .order(java.nio.ByteOrder.nativeOrder())
            fillFixedFrames(backing.asFloatBuffer(), spec.values, bins, frames)
            val environment = OrtEnvironment.getEnvironment()
            val shape = longArrayOf(1, VocalSpectrogram.CHANNELS.toLong(), bins.toLong(), FIXED_FRAMES.toLong())
            OnnxTensor.createTensor(environment, backing.asFloatBuffer(), shape).use { tensor ->
                active.run(mapOf(active.inputNames.first() to tensor)).use { outputs ->
                    val target = (outputs.get(0) as OnnxTensor).floatBuffer
                    val compact = FloatArray(VocalSpectrogram.CHANNELS * bins * frames)
                    for (c in 0 until VocalSpectrogram.CHANNELS) {
                        for (b in 0 until bins) {
                            val srcBase = (c * bins + b) * FIXED_FRAMES
                            val dstBase = (c * bins + b) * frames
                            for (f in 0 until frames) {
                                compact[dstBase + f] = target.get(srcBase + f)
                            }
                        }
                    }
                    val out = VocalSpectrogram.separate(resLeft, resRight, sr, compact, frames, true)
                    if (out.isEmpty()) return@runCatching null
                    val outLeft44 = out.copyOfRange(0, resLeft.size)
                    val outRight44 = out.copyOfRange(resLeft.size, out.size)
                    val (vocals, instrumental) = complementAtModelRate(
                        resLeft, resRight, outLeft44, outRight44, rate, sr, MelSpectrogram::resample,
                    ) ?: return@runCatching null
                    completeDualStemEndpoint(vocals, instrumental, left, right, rate, sr)
                        ?: return@runCatching null
                }
            }
        }.getOrNull()
    }

    fun release() {
        synchronized(lock) {
            runCatching { session?.close() }
            session = null
            sessionThreads = 0
        }
    }

    companion object {
        private const val TAG = "BitChordVocalTracker"
        /** The weights this tracker needs, named once for every build that ships them. */
        const val MODEL_ASSET = "vocals_umxhq_int8.onnx"
        /** The model's fixed input width, ~22.8 s, chosen upstream to cover a transition overlap. */
        const val FIXED_FRAMES = 960

        /** The band a vocal actually occupies; below and above it the mask says little. */
        private const val LOW_HZ = 200.0
        private const val HIGH_HZ = 4000.0
    }
}
