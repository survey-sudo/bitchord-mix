package com.music.bitchord.djmix.analysis

import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.analysis.cli.PcmIdentity

/** Host orchestration of playback DSP/model/structure sources; never fabricates a ready analysis. */
object AnalysisPipeline {
    /** Full production evidence stays in memory alongside its deliberately smaller export contract. */
    data class ProductionAnalysis(
        val analysis: TrackAnalysis,
        val songMap: SongMap?,
        val exported: TrackAnalysisV3,
        val execution: AnalysisExecutionEvidence? = null,
    )

    fun analyze(
        trackId: String,
        pcm: FloatArray,
        sampleRate: Int,
        durationSeconds: Double = pcm.size.toDouble() / sampleRate.coerceAtLeast(1),
        beatBackend: BeatBackend? = null,
        vocalBackend: VocalBackend? = null,
        thermalPolicy: ThermalPolicy = NoOpThermalPolicy,
        logSink: LogSink = StdLogSink,
        nowMs: () -> Long = { System.currentTimeMillis() },
        modelVersions: Map<String, String> = emptyMap(),
        contentHash: String = PcmIdentity.mono(pcm, sampleRate),
    ): TrackAnalysisV3 = analyzeProduction(
        trackId, pcm, sampleRate, durationSeconds, beatBackend, vocalBackend,
        thermalPolicy, logSink, nowMs, modelVersions, contentHash,
    ).exported

    fun analyzeProduction(
        trackId: String,
        pcm: FloatArray,
        sampleRate: Int,
        durationSeconds: Double = pcm.size.toDouble() / sampleRate.coerceAtLeast(1),
        beatBackend: BeatBackend? = null,
        vocalBackend: VocalBackend? = null,
        thermalPolicy: ThermalPolicy = NoOpThermalPolicy,
        logSink: LogSink = StdLogSink,
        nowMs: () -> Long = { System.currentTimeMillis() },
        modelVersions: Map<String, String> = emptyMap(),
        contentHash: String = PcmIdentity.mono(pcm, sampleRate),
    ): ProductionAnalysis {
        require(trackId.isNotBlank() && contentHash.matches(Regex("[0-9a-f]{64}"))) { "A track id and SHA-256 content identity are required" }
        val result = WholeTrackPipeline(beatBackend, vocalBackend, thermalPolicy, logSink, nowMs)
            .analyze(trackId, pcm, sampleRate.toDouble(), durationSeconds)
        check(!result.aborted) { "Analysis deferred by thermal policy" }
        val analysis = checkNotNull(result.analysis) { "Production analysis did not complete" }
        return ProductionAnalysis(
            analysis, result.songMap,
            TrackAnalysisV3Mapper.toV3(trackId, contentHash, analysis, result.songMap, modelVersions),
            result.execution,
        )
    }
}
