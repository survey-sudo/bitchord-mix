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
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CancellationException

/**
 * Vocal-presence detection with Syvenu (`syvenu.ort`).
 *
 * Consumes the exact same [MelSpectrogram.Spectrogram] already computed for [BeatTracker]
 * (22,050 Hz, FFT 1024, hop 441 / 50 fps, 128 Slaney mel bands, `log1p(1000 * mag)`),
 * eliminating the open-unmix LSTM pass from track analysis entirely while emitting 0..1
 * frame-level vocal probabilities at 50 fps.
 */
class VocalPresence(
    private val modelPath: () -> String,
    private val inferenceThreads: () -> Int = { DEFAULT_INTRA_OP_THREADS },
    private val npuConfig: () -> NpuSupport.NpuConfig = { NpuSupport.NpuConfig() },
    private val npuVendor: () -> NpuSupport.Vendor = { NpuSupport.Vendor.NONE },
    private val npuBackendPath: () -> String? = { null },
    private val npuCacheDir: () -> File? = { null },
) {

    /** A witness owned by one successful track call. Session open alone never emits it. */
    data class Execution(
        val modelTag: String,
        val modelPath: String,
        val backend: String,
        val inputFrames: Int,
        val coveredFrames: Int,
    )

    private data class OpenedSession(val session: OrtSession, val modelPath: String)

    @Volatile private var session: OpenedSession? = null
    @Volatile private var sessionThreads = 0
    private val lock = Any()

    private fun session(): OpenedSession? {
        val threads = inferenceThreads().coerceIn(1, 4)
        session?.takeIf { sessionThreads == threads }?.let { return it }
        synchronized(lock) {
            session?.takeIf { sessionThreads == threads }?.let { return it }
            runCatching { session?.session?.close() }
            session = null
            return runCatching {
                val file = File(modelPath())
                if (!file.isFile || file.length() <= 0L) return@runCatching null
                NpuSession.open(
                    file.absolutePath, threads, npuConfig(), npuVendor(), npuBackendPath(), npuCacheDir(),
                ).let { OpenedSession(it, file.absolutePath) }.also {
                    session = it
                    sessionThreads = threads
                }
            }.onFailure {
                if (it is CancellationException) throw it
                warnSafely("Syvenu vocal presence model unavailable", it)
            }
                .getOrNull()
        }
    }

    /** Convenience entry point for mono 22,050 Hz PCM. Prefer [track(Spectrogram)] when sharing with [BeatTracker]. */
    fun track(pcm: FloatArray): FloatArray? {
        val spectrogram = MelSpectrogram.compute(pcm) ?: return null
        return track(spectrogram)
    }

    /**
     * Runs Syvenu over [spectrogram] in 1500-frame chunks with [BORDER_FRAMES] (250-frame) inner borders.
     *
     * Inspected contract for `syvenu.ort`:
     * - Input: `mel` float32`[1, frames, 128]`
     * - Output: `presence` float32`[1, frames]` (probabilities already sigmoid-normalized in `0..1` at 50 fps).
     */
    fun track(spectrogram: MelSpectrogram.Spectrogram): FloatArray? = trackObserved(spectrogram, null)

    /** Reports the successful session together with coverage from this invocation only. */
    fun trackWithExecution(
        spectrogram: MelSpectrogram.Spectrogram,
        onExecution: (Execution) -> Unit,
    ): FloatArray? = trackObserved(spectrogram, onExecution)

    private fun trackObserved(
        spectrogram: MelSpectrogram.Spectrogram,
        onExecution: ((Execution) -> Unit)?,
    ): FloatArray? {
        synchronized(lock) {
        if (spectrogram.frames <= 0 || spectrogram.mels <= 0) return null
        val expectedSize = spectrogram.frames.toLong() * spectrogram.mels.toLong()
        if (expectedSize > Int.MAX_VALUE || spectrogram.values.size.toLong() < expectedSize) return null
        val active = session() ?: return null
        val presence = FloatArray(spectrogram.frames)
        var coveredFrames = 0
        val recordCoverage: ((Int) -> Unit)? = if (onExecution == null) null else { count -> coveredFrames = count }
        val started = System.currentTimeMillis()
        if (!infer(active.session, spectrogram, presence, recordCoverage)) return null
        warnSafely(
            "syvenu.ms=${System.currentTimeMillis() - started} frames=${spectrogram.frames}",
        )
        onExecution?.invoke(Execution("syvenu", active.modelPath, NpuSession.describe(active.session), spectrogram.frames, coveredFrames))
        return presence
        }
    }

    private fun infer(
        session: OrtSession,
        spectrogram: MelSpectrogram.Spectrogram,
        presence: FloatArray,
        recordCoverage: ((Int) -> Unit)? = null,
    ): Boolean {
        return try {
        val environment = OrtEnvironment.getEnvironment()
        val mels = spectrogram.mels
        val inputNames = session.inputNames.toList()
        val inputName = inputNames.firstOrNull { it.equals("mel", ignoreCase = true) }
            ?: inputNames.first()
        val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
        val inputShape = inputInfo?.shape ?: return false
        if (inputInfo.type != OnnxJavaType.FLOAT || inputShape.size != 3 ||
            (inputShape[0] > 0L && inputShape[0] != 1L)
        ) return false
        val expectedMels = inputShape[2]
        if (expectedMels > 0 && expectedMels != mels.toLong()) {
            warnSafely("Syvenu mel mismatch: expected $expectedMels, got $mels")
            return false
        }

        val outNames = session.outputNames.toList()
        if (outNames.isEmpty()) return false
        val outIdx = outNames.indexOfFirst { it.equals("presence", ignoreCase = true) }.let { if (it >= 0) it else 0 }

        val fixed = NpuSession.fixedInputFrames(session)
        val chunkFrames = fixed ?: CHUNK_FRAMES
        val stride = (chunkFrames - 2 * BORDER_FRAMES).coerceAtLeast(1)

        var coveredFrames = 0
        var start = 0
        while (start < spectrogram.frames) {
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
            val shape = longArrayOf(1L, fedFrames.toLong(), mels.toLong())

            OnnxTensor.createTensor(environment, FloatBuffer.wrap(chunk), shape).use { tensor ->
                session.run(mapOf(inputName to tensor)).use { outputs ->
                    val tensor = outputs.get(outIdx) as? OnnxTensor ?: return false
                    val outputShape = tensor.info.shape
                    if (tensor.info.type != OnnxJavaType.FLOAT || outputShape.size !in 1..2 ||
                        (outputShape.size == 2 && outputShape[0] > 0L && outputShape[0] != 1L) ||
                        (outputShape.last() > 0L && outputShape.last() < fedFrames.toLong())
                    ) return false
                    val outBuf = tensor.floatBuffer
                    if (outBuf.remaining() < fedFrames) return false
                    val keepFrom = if (start == 0) 0 else BORDER_FRAMES
                    val keepTo = if (start + length >= spectrogram.frames) length else length - BORDER_FRAMES
                    for (index in keepFrom until keepTo) {
                        val target = start + index
                        if (target >= presence.size) break
                        val v = outBuf.get(index)
                        if (!v.isFinite()) return false
                        presence[target] = if (v.isFinite()) v.coerceIn(0f, 1f) else 0f
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
        val succeeded = presence.all { it.isFinite() }
        if (succeeded) recordCoverage?.invoke(coveredFrames)
        succeeded
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            warnSafely("Syvenu inference failed", failure)
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
        const val MODEL_ASSET = "syvenu.ort"
        const val CHUNK_FRAMES = 1500
        const val BORDER_FRAMES = 250
        const val DEFAULT_INTRA_OP_THREADS = 2

        /** Wraps Syvenu's 50 fps output as a [CurveWindow] for [TrackAnalysis.vocalWindows] and [SongMaps]. */
        fun toCurveWindow(
            presence50fps: FloatArray,
            startTime: Double = 0.0,
            frameRate: Double = MelSpectrogram.frameRate,
        ): CurveWindow = CurveWindow(startTime = startTime, frameRate = frameRate, values = presence50fps)

        /**
         * Resamples 50 fps [presence50fps] onto [energyCurve] sample times to populate
         * [TrackAnalysis.vocalActivityMask]. Uncovered samples remain [neutralValue] (below
         * [VOCAL_ACTIVE_THRESHOLD]) so unmeasured regions never trip vocal clash gates.
         */
        fun toVocalActivityMask(
            presence50fps: FloatArray,
            energyCurve: List<EnergySample>,
            startTime: Double = 0.0,
            neutralValue: Double = 0.2,
            frameRate: Double = MelSpectrogram.frameRate,
        ): List<Double> {
            if (energyCurve.isEmpty()) return emptyList()
            if (presence50fps.isEmpty() || !frameRate.isFinite() || frameRate <= 0.0) {
                return List(energyCurve.size) { neutralValue }
            }
            return toVocalActivityMask(
                windows = listOf(toCurveWindow(presence50fps, startTime, frameRate)),
                energyCurve = energyCurve,
                neutralValue = neutralValue,
            )
        }

        /** Multi-window variant of [toVocalActivityMask] for capped head/tail passes on very long tracks. */
        fun toVocalActivityMask(
            windows: List<CurveWindow>,
            energyCurve: List<EnergySample>,
            neutralValue: Double = 0.2,
        ): List<Double> {
            if (energyCurve.isEmpty()) return emptyList()
            if (windows.isEmpty()) return List(energyCurve.size) { neutralValue }
            val out = DoubleArray(energyCurve.size) { neutralValue }
            for (index in energyCurve.indices) {
                val time = energyCurve[index].time
                if (!time.isFinite()) continue
                val tFrom = if (index > 0) 0.5 * (energyCurve[index - 1].time + time) else time - 0.5
                val tTo = if (index + 1 < energyCurve.size) 0.5 * (time + energyCurve[index + 1].time) else time + 0.5
                var sum = 0.0
                var count = 0
                for (w in windows) {
                    if (w.values.isEmpty() || !w.frameRate.isFinite() || w.frameRate <= 0.0) continue
                    val fromIdx = kotlin.math.floor((tFrom - w.startTime) * w.frameRate).toInt().coerceAtLeast(0)
                    val toIdx = kotlin.math.ceil((tTo - w.startTime) * w.frameRate).toInt().coerceAtMost(w.values.size)
                    if (toIdx > fromIdx) {
                        for (f in fromIdx until toIdx) {
                            val v = w.values[f].toDouble()
                            if (v.isFinite()) {
                                sum += v.coerceIn(0.0, 1.0)
                                count++
                            }
                        }
                    } else if (time >= w.startTime && time <= w.endTime) {
                        val nearest = ((time - w.startTime) * w.frameRate).toInt().coerceIn(0, w.values.lastIndex)
                        val v = w.values[nearest].toDouble()
                        if (v.isFinite()) {
                            sum += v.coerceIn(0.0, 1.0)
                            count++
                        }
                    }
                }
                if (count > 0) out[index] = (sum / count).coerceIn(0.0, 1.0)
            }
            return out.toList()
        }

        /**
         * Builds a proportional `[0, durationSeconds)` mask (`index = t / durationSeconds * size`)
         * expected by [com.music.bitchord.playback.smart.structure.BarFeatureExtractor.vocalPerBar]
         * and [StructureAnalyzer].
         */
        fun toProportionalMask(
            presence50fps: FloatArray,
            durationSeconds: Double,
            startTime: Double = 0.0,
            targetLength: Int = presence50fps.size,
            frameRate: Double = MelSpectrogram.frameRate,
        ): FloatArray {
            if (presence50fps.isEmpty() || !durationSeconds.isFinite() || durationSeconds <= 0.0 ||
                !frameRate.isFinite() || frameRate <= 0.0
            ) return FloatArray(0)
            val size = targetLength.coerceAtLeast(1)
            val out = FloatArray(size)
            for (i in 0 until size) {
                val t0 = i.toDouble() / size * durationSeconds
                val t1 = (i + 1).toDouble() / size * durationSeconds
                val a = ((t0 - startTime) * frameRate).toInt().coerceIn(0, presence50fps.size)
                val b = ((t1 - startTime) * frameRate).toInt().coerceIn(a, presence50fps.size)
                if (b > a) {
                    var sum = 0f
                    for (f in a until b) sum += presence50fps[f]
                    out[i] = (sum / (b - a)).coerceIn(0f, 1f)
                } else {
                    val mid = (((0.5 * (t0 + t1)) - startTime) * frameRate).toInt()
                    if (mid in presence50fps.indices) {
                        out[i] = presence50fps[mid].coerceIn(0f, 1f)
                    }
                }
            }
            return out
        }

        /** Computes per-bar mean vocal energy (`0..1`) directly on [downbeats] for [StructureV2] / [SongMap]. */
        fun perBarVocalEnergy(
            presence50fps: FloatArray,
            downbeats: DoubleArray,
            barSec: Double,
            startTime: Double = 0.0,
            frameRate: Double = MelSpectrogram.frameRate,
        ): FloatArray {
            if (presence50fps.isEmpty() || downbeats.isEmpty() || !frameRate.isFinite() || frameRate <= 0.0) {
                return FloatArray(0)
            }
            return FloatArray(downbeats.size) { bar ->
                val t0 = downbeats[bar]
                val t1 = if (bar + 1 < downbeats.size) downbeats[bar + 1] else t0 + barSec
                val a = ((t0 - startTime) * frameRate).toInt().coerceIn(0, presence50fps.size)
                val b = ((t1 - startTime) * frameRate).toInt().coerceIn(a, presence50fps.size)
                if (b > a) {
                    var sum = 0f
                    for (f in a until b) sum += presence50fps[f]
                    (sum / (b - a)).coerceIn(0f, 1f)
                } else {
                    val idx = ((t0 - startTime) * frameRate).toInt()
                    if (idx in presence50fps.indices) presence50fps[idx].coerceIn(0f, 1f) else 0f
                }
            }
        }
    }
}
