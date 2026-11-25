package com.music.bitchord.djmix.analysis

import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.MelSpectrogram
import com.music.bitchord.playback.smart.VocalPresence

/** Request-local successful inference, with file verification supplied only by the production loader. */
data class ModelExecutionEvidence(
    val modelTag: String,
    val modelPath: String,
    val backend: String,
    val inputFrames: Int,
    val coveredFrames: Int,
    val modelSha256: String? = null,
)

data class AnalysisExecutionEvidence(
    val coverage: String,
    val beat: ModelExecutionEvidence? = null,
    val vocal: ModelExecutionEvidence? = null,
    /** The backend return, before AudioMath snapping or V3 mapping. */
    val beatTimesSec: List<Double> = emptyList(),
)

data class BeatExecutionResult(val grid: BeatTracker.Grid?, val execution: ModelExecutionEvidence? = null)
data class VocalExecutionResult(val presence: FloatArray?, val execution: ModelExecutionEvidence? = null)

/** Injectable I/O boundaries; real implementations consume the same shared spectrogram. */
interface BeatBackend {
    fun track(pcm: FloatArray, sampleRate: Double, offsetSeconds: Double): BeatTracker.Grid?
    fun track(spectrogram: MelSpectrogram.Spectrogram, offsetSeconds: Double = 0.0): BeatTracker.Grid? =
        error("Whole-track analysis requires a shared-spectrogram beat backend")
    fun trackWithExecution(spectrogram: MelSpectrogram.Spectrogram, offsetSeconds: Double = 0.0): BeatExecutionResult =
        BeatExecutionResult(track(spectrogram, offsetSeconds))
    fun release() {}
}

interface VocalBackend {
    fun track(pcm: FloatArray, sampleRate: Double): FloatArray?
    fun track(spectrogram: MelSpectrogram.Spectrogram): FloatArray? =
        error("Whole-track analysis requires a shared-spectrogram vocal backend")
    fun trackWithExecution(spectrogram: MelSpectrogram.Spectrogram): VocalExecutionResult =
        VocalExecutionResult(track(spectrogram))
    fun release() {}
}

/** Adapts production inference, without changing chunking, peaks, models or frontend. */
fun BeatTracker.asBackend(): BeatBackend = object : BeatBackend {
    override fun track(pcm: FloatArray, sampleRate: Double, offsetSeconds: Double): BeatTracker.Grid? {
        val mono = if (sampleRate == MelSpectrogram.sampleRate) pcm
            else MelSpectrogram.resample(pcm, sampleRate) ?: return null
        return MelSpectrogram.compute(mono)?.let { this@asBackend.track(it, offsetSeconds) }
    }
    override fun track(spectrogram: MelSpectrogram.Spectrogram, offsetSeconds: Double) = this@asBackend.track(spectrogram, offsetSeconds)
    override fun trackWithExecution(spectrogram: MelSpectrogram.Spectrogram, offsetSeconds: Double): BeatExecutionResult {
        var execution: ModelExecutionEvidence? = null
        val grid = this@asBackend.trackWithExecution(spectrogram, offsetSeconds) {
            execution = ModelExecutionEvidence(it.modelTag, it.modelPath, it.backend, it.inputFrames, it.coveredFrames)
        }
        return BeatExecutionResult(grid, execution.takeIf { grid != null })
    }
    override fun release() = this@asBackend.release()
}

fun VocalPresence.asBackend(): VocalBackend = object : VocalBackend {
    override fun track(pcm: FloatArray, sampleRate: Double): FloatArray? {
        val mono = if (sampleRate == MelSpectrogram.sampleRate) pcm
            else MelSpectrogram.resample(pcm, sampleRate) ?: return null
        return MelSpectrogram.compute(mono)?.let { this@asBackend.track(it) }
    }
    override fun track(spectrogram: MelSpectrogram.Spectrogram) = this@asBackend.track(spectrogram)
    override fun trackWithExecution(spectrogram: MelSpectrogram.Spectrogram): VocalExecutionResult {
        var execution: ModelExecutionEvidence? = null
        val presence = this@asBackend.trackWithExecution(spectrogram) {
            execution = ModelExecutionEvidence(it.modelTag, it.modelPath, it.backend, it.inputFrames, it.coveredFrames)
        }
        return VocalExecutionResult(presence, execution.takeIf { presence != null })
    }
    override fun release() = this@asBackend.release()
}
