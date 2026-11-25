package com.music.bitchord.djmix.analysis

import com.music.bitchord.playback.smart.Pcm
import com.music.bitchord.playback.smart.AudioMath
import com.music.bitchord.playback.smart.BeatTracker

class HeadAnalyzer(
    private val beatBackend: BeatBackend? = null,
    private val vocalBackend: VocalBackend? = null,
    private val logSink: LogSink? = null,
) {
    fun analyzeHead(trackId: String, pcm: FloatArray, sampleRate: Double, durationSeconds: Double): TrackAnalysis? {
        val span = BeatTracker.WINDOW_SECONDS
        val headLen = (span * sampleRate).toInt().coerceAtMost(pcm.size)
        if (headLen <= 0) return null
        val headPcm = pcm.copyOf(headLen)
        val grid = beatBackend?.track(headPcm, sampleRate, 0.0)
        val features = AudioMath.monoWindowInputs(Pcm(headPcm, sampleRate), 0.0, headLen / sampleRate,
            deriveFeatures = true, computeLevel = true).derived
        if (grid == null && features == null) return null
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = durationSeconds,
            quality = TrackAnalysis.QUALITY_HEAD,
            bpm = grid?.bpm ?: features?.bpm ?: 0.0,
            beatInterval = grid?.beatInterval ?: features?.beatInterval ?: 0.0,
            beatConfidence = grid?.beatConfidence ?: features?.beatConfidence ?: 0.0,
            downbeats = grid?.downbeats ?: features?.downbeats.orEmpty(),
            firstBeat = grid?.firstBeat ?: features?.firstBeat ?: 0.0,
            key = features?.key.orEmpty(),
            keyConfidence = features?.keyConfidence ?: 0.0,
            headBeats = grid?.let { BeatSide(it.beats, it.downbeats, it.bpm, it.beatConfidence) },
        )
    }
}
