package com.music.bitchord.djmix.analysis.cli

import com.music.bitchord.djmix.analysis.AnalysisPipeline
import com.music.bitchord.djmix.analysis.BeatTracker
import com.music.bitchord.djmix.analysis.ModelExecutionEvidence
import com.music.bitchord.djmix.analysis.NoOpThermalPolicy
import com.music.bitchord.djmix.analysis.VocalPresence
import com.music.bitchord.djmix.analysis.asBackend
import java.io.File

/** The exporter's verified production models, retaining complete evidence for fresh geometry. */
object ProductionAnalysisLoader {
    fun analyze(audio: WavReader.Stereo, trackId: String, modelDirectory: File): AnalysisPipeline.ProductionAnalysis {
        val beatModel = verifiedModel(modelDirectory, "nirovi_int8.ort", 7_948_784L,
            "7454d98ab6a88c0b7fe5e91c556b4766ebfabfe3d31ebd61187a033fed489387")
        val vocalModel = verifiedModel(modelDirectory, "syvenu.ort", 2_349_304L,
            "1866a5955ed6b0e80f807cb49f56af530121064b8ea5322026566126eb3cd4f3")
        val beatBackend = BeatTracker(modelPath = { beatModel.file.absolutePath }).asBackend()
        val vocalBackend = VocalPresence(modelPath = { vocalModel.file.absolutePath }).asBackend()
        try {
            val result = AnalysisPipeline.analyzeProduction(
                trackId = trackId, pcm = audio.mono(), sampleRate = audio.sampleRate,
                durationSeconds = audio.frames.toDouble() / audio.sampleRate,
                beatBackend = beatBackend, vocalBackend = vocalBackend, thermalPolicy = NoOpThermalPolicy,
                modelVersions = mapOf("beat" to "${beatModel.file.name}:sha256=${beatModel.sha256}",
                    "vocal" to "${vocalModel.file.name}:sha256=${vocalModel.sha256}", "structure" to "playback-StructureV2"),
                contentHash = PcmIdentity.stereo(audio),
            )
            return result.copy(execution = result.execution?.let {
                it.copy(beat = bindVerifiedModel(it.beat, beatModel, "nirovi"), vocal = bindVerifiedModel(it.vocal, vocalModel, "syvenu"))
            })
        } finally {
            beatBackend.release()
            vocalBackend.release()
        }
    }

    private data class VerifiedModel(val file: File, val sha256: String, val bytes: Long)

    private fun verifiedModel(directory: File, name: String, bytes: Long, sha256: String): VerifiedModel {
        val file = File(directory, name).absoluteFile
        require(file.isFile && file.length() == bytes) { "Missing or mismatched production model: $name" }
        val actualSha256 = PcmIdentity.file(file)
        require(actualSha256 == sha256) { "Missing or mismatched production model: $name" }
        return VerifiedModel(file, actualSha256, bytes)
    }

    private fun bindVerifiedModel(execution: ModelExecutionEvidence?, model: VerifiedModel, tag: String): ModelExecutionEvidence? {
        if (execution == null || execution.modelTag != tag || execution.modelPath != model.file.absolutePath) return null
        // The witness names the session's file, not a default tag. A changed/missing file cannot attest this export.
        val unchanged = runCatching {
            model.file.isFile && model.file.length() == model.bytes && PcmIdentity.file(model.file) == model.sha256
        }.getOrDefault(false)
        return execution.copy(modelSha256 = model.sha256).takeIf { unchanged }
    }
}
