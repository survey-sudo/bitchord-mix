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
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

/**
 * Beat and downbeat tracking, defaulting to Nirovi (`nirovi_int8.ort`) with Beat This!
 * (`beat_this_final0_q8.onnx`) as explicit fallback when Nirovi is unavailable or fails.
 *
 * Both models share the identical [MelSpectrogram] front end (22,050 Hz, FFT 1024, hop 441 / 50 fps,
 * 128 Slaney mel bands 30–11,000 Hz, `log1p(1000 * mag)`).
 *
 * Everything here is optional. A missing model, an unreadable graph, a rate mismatch, too few
 * peaks, all resolve to null, and the caller keeps whatever it had. A missing beat tracker
 * degrades transitions; a throwing one would break playback.
 */
class BeatTracker(
    /**
     * Where the primary model lives, resolved lazily (bundled `nirovi_int8.ort` by default).
     */
    private val modelPath: () -> String,
    /** How many threads inference may use (Nirovi uses 2 intra-op threads). */
    private val inferenceThreads: () -> Int = { DEFAULT_INTRA_OP_THREADS },
    /** NPU (vc69): QNN configuration, vendor detection, backend path, and context cache dir. Defaults are CPU-safe. */
    private val npuConfig: () -> NpuSupport.NpuConfig = { NpuSupport.NpuConfig() },
    private val npuVendor: () -> NpuSupport.Vendor = { NpuSupport.Vendor.NONE },
    private val npuBackendPath: () -> String? = { null },
    private val npuCacheDir: () -> File? = { null },
    /** Optional fallback model path (e.g. downloaded `beat_this_final0_q8.onnx` from `models-v1`). */
    private val fallbackModelPath: () -> String? = { null },
) {

    /** A tracked grid on the analysed audio's own timeline, in seconds. */
    data class Grid(
        val beats: List<Double>,
        val downbeats: List<Double>,
        val bpm: Double,
        val beatInterval: Double,
        val firstBeat: Double,
        val beatConfidence: Double,
    )

    /** A witness owned by one successful track call, never by a previously opened session. */
    data class Execution(
        val modelTag: String,
        val modelPath: String,
        val backend: String,
        val inputFrames: Int,
        val coveredFrames: Int,
    )

    @Volatile private var session: OpenedSession? = null
    @Volatile private var sessionThreads = 0

    /** Model tag recorded on the last session open / inference (`"nirovi"` or `"beat_this"`). */
    @Volatile var lastModelTag: String = MODEL_TAG_NIROVI
        private set

    private val lock = Any()

    private data class OpenedSession(
        val session: OrtSession,
        val isFallback: Boolean,
        val tag: String,
        val modelPath: String,
    )

    /** Parsing the graph is far too expensive to repeat per track, so one session is kept. */
    private fun session(forceFallback: Boolean = false): OpenedSession? {
        val threads = inferenceThreads().coerceIn(1, 4)
        if (!forceFallback) {
            session?.takeIf { sessionThreads == threads }?.let { return it }
        }
        synchronized(lock) {
            if (!forceFallback) {
                session?.takeIf { sessionThreads == threads }?.let { return it }
            }
            session?.let { runCatching { NpuSession.close(it.session) } }
            session = null

            val candidates = buildList {
                if (!forceFallback) {
                    runCatching { modelPath() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { add(it to false) }
                }
                runCatching { fallbackModelPath() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { add(it to true) }
            }.distinctBy { it.first }

            for ((path, isFallback) in candidates) {
                val file = File(path)
                if (!file.isFile || file.length() <= 0L) continue
                val opened = try {
                    NpuSession.open(
                        file.absolutePath, threads, npuConfig(), npuVendor(), npuBackendPath(), npuCacheDir(),
                        acceptsFixedShape = true,
                    )
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    warnSafely("Beat model open failed for ${file.name}", failure)
                    null
                }
                if (opened != null) {
                    val tag = modelTagFor(file, isFallback)
                    val openedSession = OpenedSession(opened, isFallback, tag, file.absolutePath)
                    session = openedSession
                    sessionThreads = threads
                    lastModelTag = tag
                    return openedSession
                }
            }
            warnSafely("Beat model unavailable; falling back to no grid")
            return null
        }
    }

    /**
     * Tracks [pcm], which must already be mono at [MelSpectrogram.sampleRate].
     *
     * [offsetSeconds] is added to every returned time, so a grid tracked on a decoded region maps
     * back onto the full track's timeline rather than starting at zero.
     */
    fun track(pcm: FloatArray, offsetSeconds: Double = 0.0): Grid? {
        val melStarted = System.currentTimeMillis()
        val spectrogram = MelSpectrogram.compute(pcm) ?: return null
        val melMs = System.currentTimeMillis() - melStarted
        return track(spectrogram, offsetSeconds, melMs)
    }

    /**
     * Tracks a precomputed [MelSpectrogram.Spectrogram] so downstream models such as [VocalPresence]
     * can share the exact same spectrogram with zero duplicate STFT/mel work.
     */
    fun track(
        spectrogram: MelSpectrogram.Spectrogram,
        offsetSeconds: Double = 0.0,
        melMs: Long = 0L,
    ): Grid? = trackObserved(spectrogram, offsetSeconds, melMs, null)

    /** Reports only the session that produced this call's usable raw grid. */
    fun trackWithExecution(
        spectrogram: MelSpectrogram.Spectrogram,
        offsetSeconds: Double = 0.0,
        onExecution: (Execution) -> Unit,
    ): Grid? = trackObserved(spectrogram, offsetSeconds, 0L, onExecution)

    private fun trackObserved(
        spectrogram: MelSpectrogram.Spectrogram,
        offsetSeconds: Double,
        melMs: Long,
        onExecution: ((Execution) -> Unit)?,
    ): Grid? {
        synchronized(lock) {
        if (spectrogram.frames <= 0 || spectrogram.mels <= 0) return null
        var active = session() ?: return null

        val beatLogits = FloatArray(spectrogram.frames)
        val downbeatLogits = FloatArray(spectrogram.frames)
        var coveredFrames = 0
        val recordCoverage: ((Int) -> Unit)? = if (onExecution == null) null else { count -> coveredFrames = count }
        val inferStarted = System.currentTimeMillis()
        if (!infer(active.session, spectrogram, beatLogits, downbeatLogits, recordCoverage)) {
            if (!active.isFallback) {
                val fallback = session(forceFallback = true)
                if (fallback != null) {
                    active = fallback
                    beatLogits.fill(0f)
                    downbeatLogits.fill(0f)
                    if (!infer(active.session, spectrogram, beatLogits, downbeatLogits, recordCoverage)) return null
                } else {
                    return null
                }
            } else {
                return null
            }
        }
        val inferMs = System.currentTimeMillis() - inferStarted
        warnSafely("analysis.ms=$inferMs model=${active.tag} mel=${melMs}ms (${spectrogram.frames} frames)")

        convertProbabilitiesToLogitsIfNeeded(beatLogits)
        convertProbabilitiesToLogitsIfNeeded(downbeatLogits)

        val fps = MelSpectrogram.frameRate
        val beatFrames = pickPeaks(beatLogits)
        val beats = beatFrames.map { it / fps + offsetSeconds }
        if (beats.size < MIN_BEATS) return null

        val bpm = tempoFromBeats(beats)
        if (bpm <= 0) return null

        // Every downbeat is a beat. The two heads are predicted independently, so their peaks can
        // land a frame apart; snapping each downbeat onto the nearest beat keeps the bar grid a
        // strict subset of the beat grid, which is what the planner assumes when it snaps a
        // transition to a downbeat.
        val downbeats = pickPeaks(downbeatLogits)
            .map { it / fps + offsetSeconds }
            .map { time -> beats.minByOrNull { abs(it - time) } ?: beats.first() }
            .distinct()
            .sorted()

        val grid = Grid(
            beats = beats,
            downbeats = downbeats,
            bpm = bpm,
            beatInterval = 60 / bpm,
            firstBeat = beats.first(),
            beatConfidence = gridConfidence(
                beats,
                beatFrames.map { frame -> beatLogits.getOrElse(frame.roundToInt()) { 0f }.toDouble() },
            ),
        )
        if (!grid.bpm.isFinite() || !grid.beatInterval.isFinite() || !grid.firstBeat.isFinite() ||
            !grid.beatConfidence.isFinite() || grid.beats.any { !it.isFinite() } ||
            grid.downbeats.any { !it.isFinite() }
        ) return null
        onExecution?.invoke(Execution(active.tag, active.modelPath, NpuSession.describe(active.session), spectrogram.frames, coveredFrames))
        return grid
        }
    }

    /**
     * Runs the model over the spectrogram in 1500-frame chunks with [BORDER_FRAMES] borders,
     * zero-padding short inputs or fixed-shape graphs.
     *
     * Inspected contracts:
     * - `nirovi_int8.ort`: input `input_spectrogram` float32`[1, 1500, 128]` (static),
     *   outputs `beat` float32`[1, 1500]` and `downbeat` float32`[1, 1500]` (logits).
     * - `beat_this_final0_q8.onnx`: dynamic `[1, frames, 128]`, outputs `beat` / `downbeat`.
     */
    private fun infer(
        session: OrtSession,
        spectrogram: MelSpectrogram.Spectrogram,
        beatLogits: FloatArray,
        downbeatLogits: FloatArray,
        recordCoverage: ((Int) -> Unit)? = null,
    ): Boolean {
        return try {
        val environment = OrtEnvironment.getEnvironment()
        val mels = spectrogram.mels
        val inputNames = session.inputNames.toList()
        val inputName = inputNames.firstOrNull { it.equals("input_spectrogram", ignoreCase = true) }
            ?: inputNames.first()
        val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
        val inputShape = inputInfo?.shape ?: return false
        if (inputInfo.type != OnnxJavaType.FLOAT || inputShape.size !in 2..3) return false
        if (inputShape.size == 3 && inputShape[0] > 0L && inputShape[0] != 1L) return false

        if (inputShape != null && inputShape.isNotEmpty()) {
            val expectedMels = inputShape.last()
            if (expectedMels > 0 && expectedMels != mels.toLong()) {
                warnSafely("Beat model mel mismatch: expected $expectedMels, got $mels")
                return false
            }
        }

        val inputRank = inputShape.size
        val fixed = fixedFramesFromShape(inputShape)
        val chunkFrames = fixed ?: CHUNK_FRAMES
        val stride = (chunkFrames - 2 * BORDER_FRAMES).coerceAtLeast(1)

        val outNames = session.outputNames.toList()
        if (outNames.size < 2) return false
        val beatOutIdx = outNames.indexOfFirst { it.equals("beat", ignoreCase = true) }.let { if (it >= 0) it else 0 }
        val downOutIdx = outNames.indexOfFirst { it.equals("downbeat", ignoreCase = true) }.let {
            if (it >= 0) it else 1
        }
        if (beatOutIdx == downOutIdx) return false

        var coveredFrames = 0
        var start = 0
        while (start < spectrogram.frames) {
            // Fixed shape (e.g. Nirovi [1, 1500, 128]): align tail chunk to end when audio is >= chunkFrames
            // so edge frames keep real audio context; shorter tracks/regions are zero-padded to chunkFrames.
            if (fixed != null && start > 0 && start + chunkFrames > spectrogram.frames) {
                start = max(0, spectrogram.frames - chunkFrames)
            }
            val length = min(chunkFrames, spectrogram.frames - start)
            if (length <= 2 * BORDER_FRAMES && start > 0) break

            val fedFrames = if (fixed != null) chunkFrames else length
            val elementCount = fedFrames.toLong() * mels.toLong()
            if (elementCount <= 0L || elementCount > Int.MAX_VALUE) return false
            val chunk = FloatArray(elementCount.toInt())
            spectrogram.values.copyInto(chunk, 0, start * mels, (start + length) * mels)
            val shape = if (inputRank == 2) {
                longArrayOf(fedFrames.toLong(), mels.toLong())
            } else {
                longArrayOf(1L, fedFrames.toLong(), mels.toLong())
            }

            OnnxTensor.createTensor(environment, FloatBuffer.wrap(chunk), shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { outputs ->
                    val keepFrom = if (start == 0) 0 else BORDER_FRAMES
                    val keepTo = if (start + length >= spectrogram.frames) length else length - BORDER_FRAMES
                    if (outputs.size() >= 2) {
                        val beatTensor = outputs.get(beatOutIdx) as? OnnxTensor ?: return false
                        val downTensor = outputs.get(downOutIdx) as? OnnxTensor ?: return false
                        if (!validFrameTensor(beatTensor, fedFrames) || !validFrameTensor(downTensor, fedFrames)) return false
                        val beatBuf = beatTensor.floatBuffer
                        val downBuf = downTensor.floatBuffer
                        for (index in keepFrom until keepTo) {
                            val target = start + index
                            if (target >= beatLogits.size) break
                            val beat = beatBuf.get(index)
                            val down = downBuf.get(index)
                            if (!beat.isFinite() || !down.isFinite()) return false
                            beatLogits[target] = beat
                            downbeatLogits[target] = down
                        }
                    } else {
                        val packed = outputs.get(0) as OnnxTensor
                        if (packed.info.type != OnnxJavaType.FLOAT) return false
                        val packedShape = packed.info.shape
                        if (!validPackedTensor(packedShape, fedFrames)) return false
                        val buf = packed.floatBuffer
                        if (packedShape.lastOrNull() == 2L) {
                            for (index in keepFrom until keepTo) {
                                val target = start + index
                                if (target >= beatLogits.size) break
                                val beat = buf.get(index * 2)
                                val down = buf.get(index * 2 + 1)
                                if (!beat.isFinite() || !down.isFinite()) return false
                                beatLogits[target] = beat
                                downbeatLogits[target] = down
                            }
                        } else {
                            return false
                        }
                    }
                    // Observe the contiguous validated output prefix; never count zero-filled gaps.
                    if (keepTo > keepFrom && start + keepFrom <= coveredFrames) {
                        coveredFrames = max(coveredFrames, min(start + keepTo, spectrogram.frames))
                    }
                }
            }

            if (start + length >= spectrogram.frames) break
            start += stride
        }
        val succeeded = beatLogits.all { it.isFinite() } && downbeatLogits.all { it.isFinite() }
        if (succeeded) recordCoverage?.invoke(coveredFrames)
        succeeded
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            warnSafely("Beat inference failed (model=$lastModelTag)", failure)
            false
        }
    }

    fun release() {
        synchronized(lock) {
            session?.let { runCatching { NpuSession.close(it.session) } }
            session = null
            sessionThreads = 0
        }
    }

    private fun warnSafely(message: String, failure: Throwable? = null) {
        try {
            if (failure == null) AnalysisLog.warn(message) else AnalysisLog.warn(message, failure)
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
        }
    }

    companion object {
        private const val TAG = "BitChordBeatTracker"

        const val MODEL_TAG_NIROVI = "nirovi"
        const val MODEL_TAG_BEAT_THIS = "beat_this"

        /** Bundled CPU Nirovi model (AE3 M2-A). */
        const val NIROVI_MODEL_ASSET = "nirovi_int8.ort"

        /** On-demand Beat This! fallback model (`models-v1`). */
        const val BEAT_THIS_MODEL_ASSET = "beat_this_final0_q8.onnx"

        /** Default bundled beat/downbeat asset. */
        const val MODEL_ASSET = NIROVI_MODEL_ASSET

        /** The broken earlier export, whose unpacked copy is deleted on first use of the new one. */
        const val RETIRED_MODEL_ASSET = "beat_this_int8.onnx"

        /** The window the model was trained on, and the margin discarded from each chunk's edges. */
        const val CHUNK_FRAMES = 1500
        const val BORDER_FRAMES = 6
        const val DEFAULT_INTRA_OP_THREADS = 2

        /**
         * Window length that costs exactly one inference. A window longer than this splits into
         * two chunks that mostly overlap, paying twice for barely more audio.
         */
        const val WINDOW_SECONDS = (CHUNK_FRAMES - 2 * BORDER_FRAMES) / 50.0

        /** A frame is a beat when it is the maximum of a seven-frame window and its logit positive. */
        private const val PEAK_WINDOW = 7
        private const val MIN_BEATS = 8

        /** Plausible musical tempo, used only to reject a grid the model clearly did not find. */
        private const val MIN_TEMPO = 40.0
        private const val MAX_TEMPO = 220.0

        private fun modelTagFor(file: File, isFallback: Boolean): String =
            if (isFallback || file.name.contains("beat_this", ignoreCase = true)) {
                MODEL_TAG_BEAT_THIS
            } else {
                MODEL_TAG_NIROVI
            }

        private fun fixedFramesFromShape(shape: LongArray?): Int? {
            if (shape == null) return null
            if (shape.size !in 2..3 || (shape.size == 3 && shape[0] > 0L && shape[0] != 1L)) return null
            val frameDim = when {
                shape.size >= 3 -> shape[1]
                shape.size == 2 -> shape[0]
                else -> -1L
            }
            return frameDim.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
        }

        private fun validFrameTensor(tensor: OnnxTensor, frames: Int): Boolean {
            if (tensor.info.type != OnnxJavaType.FLOAT) return false
            val shape = tensor.info.shape
            if (shape.size !in 1..2 || (shape.size == 2 && shape[0] > 0L && shape[0] != 1L)) return false
            val count = shape.lastOrNull() ?: return false
            return count >= frames.toLong() && count <= Int.MAX_VALUE.toLong() &&
                tensor.floatBuffer.remaining() >= frames
        }

        private fun validPackedTensor(shape: LongArray, frames: Int): Boolean {
            if (shape.size !in 2..3 || shape.lastOrNull() != 2L) return false
            if (shape.size == 3 && shape[0] > 0L && shape[0] != 1L) return false
            return shape[shape.size - 2] >= frames.toLong()
        }

        /**
         * Nirovi and Beat This! emit raw logits (non-beat frames are negative). If a graph ever
         * emits sigmoid probabilities in `[0, 1]` instead, convert them to logits so [pickPeaks]
         * and [gridConfidence] stay calibrated.
         */
        internal fun convertProbabilitiesToLogitsIfNeeded(values: FloatArray) {
            if (values.isEmpty()) return
            var minVal = Float.POSITIVE_INFINITY
            var maxVal = Float.NEGATIVE_INFINITY
            for (v in values) {
                if (!v.isFinite()) continue
                if (v < minVal) minVal = v
                if (v > maxVal) maxVal = v
            }
            if (minVal >= 0f && maxVal <= 1f && maxVal > 0f) {
                for (i in values.indices) {
                    val p = values[i].coerceIn(1e-6f, 1f - 1e-6f)
                    values[i] = ln(p / (1f - p))
                }
            }
        }

        private fun median(values: List<Double>): Double {
            if (values.isEmpty()) return 0.0
            val sorted = values.sorted()
            return sorted[sorted.size / 2]
        }

        /**
         * Frame indices that are local maxima over [PEAK_WINDOW] and positive, with runs of
         * adjacent peaks collapsed to their mean, then refined to sub-frame resolution.
         *
         * The refinement is not upstream's. The model's frame rate is 50 Hz, so an integer peak
         * quantizes every beat to 20 ms, fine for drawing a grid, but a large share of the flam
         * budget when beat-matching two tracks. Fitting a parabola through the peak and its
         * neighbours recovers where the maximum actually sits.
         */
        fun pickPeaks(logits: FloatArray): List<Double> {
            val half = PEAK_WINDOW / 2
            val peaks = ArrayList<Int>()
            for (index in logits.indices) {
                if (logits[index] <= 0f) continue
                var isMaximum = true
                for (offset in -half..half) {
                    val neighbour = index + offset
                    if (neighbour < 0 || neighbour >= logits.size) continue
                    if (logits[neighbour] > logits[index]) {
                        isMaximum = false
                        break
                    }
                }
                if (isMaximum) peaks += index
            }

            // Collapse adjacent frames that tied for the maximum onto their mean.
            val deduped = ArrayList<Int>()
            var index = 0
            while (index < peaks.size) {
                var mean = peaks[index].toDouble()
                var count = 1
                while (index + 1 < peaks.size && peaks[index + 1] - mean <= 1) {
                    index += 1
                    count += 1
                    mean += (peaks[index] - mean) / count
                }
                deduped += mean.roundToInt()
                index += 1
            }

            return deduped.map { frame ->
                if (frame <= 0 || frame + 1 >= logits.size) return@map frame.toDouble()
                val left = logits[frame - 1].toDouble()
                val centre = logits[frame].toDouble()
                val right = logits[frame + 1].toDouble()
                val denominator = left - 2 * centre + right
                if (abs(denominator) <= 1e-9) return@map frame.toDouble()
                frame + (0.5 * (left - right) / denominator).coerceIn(-0.5, 0.5)
            }
        }

        /** Median inter-beat interval as a tempo, or 0 when it is not a plausible one. */
        fun tempoFromBeats(beats: List<Double>): Double {
            if (beats.size < MIN_BEATS) return 0.0
            val gaps = beats.zipWithNext { left, right -> right - left }
            val rough = median(gaps)
            if (rough <= 0) return 0.0
            // A second pass over gaps close to the first estimate, so a few dropped beats do not
            // drag the interval.
            val kept = gaps.filter { abs(it - rough) <= rough * 0.2 }
            val interval = median(if (kept.size >= 4) kept else gaps)
            if (interval <= 0) return 0.0
            val bpm = 60 / interval
            return if (bpm in MIN_TEMPO..MAX_TEMPO) bpm else 0.0
        }

        /**
         * How far the grid can be trusted, 0..1: regularity of the spacing and decisiveness of
         * the peaks. This is the number the whole transition policy gates on, so it is deliberately
         * capped below 1: a model is evidence, not proof.
         */
        fun gridConfidence(beats: List<Double>, peakLogits: List<Double>): Double {
            if (beats.size < MIN_BEATS) return 0.0
            val gaps = beats.zipWithNext { left, right -> right - left }
            val interval = median(gaps)
            if (interval <= 0) return 0.0

            // Fraction of gaps that are one beat rather than a hole in the grid.
            val regular = gaps.count { abs(it - interval) <= interval * 0.1 }.toDouble() / gaps.size
            // Logits are unbounded; a median around 2 is a decisive peak, around 0 is not.
            val strength = 1 / (1 + exp(-(median(peakLogits) - 0.5)))
            return max(0.0, min(0.95, 0.35 + 0.4 * regular + 0.25 * strength))
        }
    }
}
