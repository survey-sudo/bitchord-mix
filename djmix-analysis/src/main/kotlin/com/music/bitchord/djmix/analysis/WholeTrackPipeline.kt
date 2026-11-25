package com.music.bitchord.djmix.analysis

import com.music.bitchord.playback.smart.Pcm
import com.music.bitchord.playback.smart.AudioMath
import com.music.bitchord.playback.smart.AudioMath.toGrid
import com.music.bitchord.playback.smart.AnalysisRegion
import com.music.bitchord.playback.smart.BeatTracker
import com.music.bitchord.playback.smart.MelSpectrogram
import com.music.bitchord.playback.smart.SeamedBeats
import com.music.bitchord.playback.smart.TrackFeatures
import com.music.bitchord.playback.smart.VocalPresence
import com.music.bitchord.playback.smart.heuristicVocalOnModelScale
import kotlin.math.abs

/** Host decode adapter around the exact playback feature, grid, vocal and structure algorithms. */
class WholeTrackPipeline(
    private val beatBackend: BeatBackend? = null,
    private val vocalBackend: VocalBackend? = null,
    private val thermalPolicy: ThermalPolicy = NoOpThermalPolicy,
    private val logSink: LogSink? = null,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    data class WholeTrack(
        val analysis: TrackAnalysis?, val decodedShort: Boolean = false, val aborted: Boolean = false,
        val songMap: SongMap? = null,
        val execution: AnalysisExecutionEvidence? = null,
    )

    fun analyze(trackId: String, pcm: FloatArray, sampleRate: Double, durationSeconds: Double): WholeTrack {
        require(sampleRate.isFinite() && sampleRate > 0 && pcm.isNotEmpty() && pcm.all { it.isFinite() }) { "Invalid/non-finite analysis PCM" }
        val duration = pcm.size / sampleRate
        require(durationSeconds.isFinite() && durationSeconds > 0 && abs(durationSeconds - duration) <= 1.0 / sampleRate) {
            "Analysis duration must match decoded PCM"
        }
        if (!thermalPolicy.isCool()) return WholeTrack(null, aborted = true)
        val beat = checkNotNull(beatBackend) { "Nirovi backend is required for real audition" }
        val vocal = checkNotNull(vocalBackend) { "Syvenu backend is required for real audition" }
        check(TrackFeatures.available && MelSpectrogram.available) { "Production native analyzer is unavailable; build the host JNI library" }
        val started = nowMs()
        val decoded = Pcm(pcm, sampleRate)
        val featurePcm = if (sampleRate == TrackFeatures.sampleRate) pcm else
            checkNotNull(TrackFeatures.resample(pcm, sampleRate)) { "Native feature resampling failed" }
        val features = checkNotNull(TrackFeatures.analyze(featurePcm, duration)) { "Native feature analysis failed" }
        val span = BeatTracker.WINDOW_SECONDS
        val headEnd = minOf(duration, 2 * span - AudioMath.WINDOW_OVERLAP_SECONDS)
        val tailStart = maxOf(0.0, duration - (3 * span - 2 * AudioMath.WINDOW_OVERLAP_SECONDS))
        var fullGrid: BeatTracker.Grid? = null
        var presence: FloatArray? = null
        var execution = AnalysisExecutionEvidence(coverage = "head-tail")
        val vocalWindows: List<CurveWindow>
        val levelWindows: List<CurveWindow>
        val allBeats: SeamedBeats
        if (duration <= AnalysisConfig.MAX_WHOLE_MODEL_SECONDS) {
            val modelPcm = if (sampleRate == MelSpectrogram.sampleRate) pcm else
                checkNotNull(MelSpectrogram.resample(pcm, sampleRate)) { "Native model resampling failed" }
            val spec = checkNotNull(MelSpectrogram.compute(modelPcm)) { "Native mel frontend failed" }
            val beatResult = beat.trackWithExecution(spec)
            val rawGrid = checkNotNull(beatResult.grid) { "Nirovi returned no usable grid" }
            val rawBeatTimes = rawGrid.beats.toList()
            fullGrid = rawGrid
                .let { AudioMath.snapGrid(it, AudioMath.fluxOnsets(decoded, 0.0)) }
            val vocalResult = vocal.trackWithExecution(spec)
            presence = checkNotNull(vocalResult.presence) { "Syvenu returned no presence curve" }
            require(presence.size == spec.frames && presence.all { it.isFinite() && it in 0f..1f }) { "Invalid Syvenu curve" }
            execution = AnalysisExecutionEvidence(
                coverage = if (beatResult.execution?.let { it.inputFrames == spec.frames && it.coveredFrames == spec.frames } == true &&
                    vocalResult.execution?.let { it.inputFrames == spec.frames && it.coveredFrames == spec.frames } == true) "whole-file" else "incomplete",
                beat = beatResult.execution, vocal = vocalResult.execution, beatTimesSec = rawBeatTimes,
            )
            allBeats = SeamedBeats(fullGrid.beats, fullGrid.downbeats, fullGrid.beatConfidence)
            vocalWindows = listOf(VocalPresence.toCurveWindow(presence))
            levelWindows = listOfNotNull(AudioMath.levelCurve(decoded, 0.0))
        } else {
            // Playback's >8 min policy measures head and tail, without pretending the middle was observed.
            val regions = AudioMath.modelWindows(headEnd, tailStart, duration, span).map { (from, to) ->
                checkNotNull(analyzeMonoWindow(pcm, sampleRate, from, to)) { "Head/tail window analysis failed" }
            }
            allBeats = AudioMath.seamBeats(regions)
            vocalWindows = regions.mapNotNull { it.vocalWindow }
            levelWindows = regions.mapNotNull { it.levelWindow }
        }
        val head = AudioMath.beatSide(allBeats, 0.0, headEnd)
        val tail = AudioMath.beatSide(allBeats, tailStart, duration)
        val leading = checkNotNull(fullGrid ?: tail?.toGrid() ?: head?.toGrid()) { "No model beat evidence" }
        val downs = allBeats.downbeats.ifEmpty { features.downbeats }.sorted()
        val mask = VocalPresence.toVocalActivityMask(windows = vocalWindows, energyCurve = features.energyCurve, neutralValue = 0.2)
        val analysis = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, trackId = trackId, duration = duration, quality = TrackAnalysis.QUALITY_FULL,
            bpm = leading.bpm, beatInterval = leading.beatInterval, beatConfidence = leading.beatConfidence,
            downbeats = downs, firstBeat = leading.firstBeat, phraseBoundaries = features.phraseBoundaries,
            key = features.key, keyConfidence = features.keyConfidence, keyHead = features.keyHead, keyTail = features.keyTail,
            keyHeadConfidence = features.keyHeadConfidence, keyTailConfidence = features.keyTailConfidence,
            audibleStartTime = features.audibleStartTime, pickupTime = features.pickupTime, introEndTime = features.introEndTime,
            outroStartTime = features.outroStartTime, contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: duration,
            mixInTime = features.mixInTime, mixOutTime = features.mixOutTime,
            mixInCandidates = features.mixInCandidates, mixOutCandidates = features.mixOutCandidates,
            energyCurve = features.energyCurve, lowEnergyCurve = features.lowEnergyCurve,
            vocalActivityMask = mask, vocalProbability = features.vocalProbability,
            headBeats = head, tailBeats = tail, vocalWindows = vocalWindows, levelWindows = levelWindows,
            vocalPresence50fps = presence?.toList().orEmpty(),
        )
        val cap = (TrackFeatures.sampleRate * AnalysisConfig.MAX_WHOLE_MODEL_SECONDS).toInt()
        val structurePcm = if (featurePcm.size > cap) featurePcm.copyOf(cap) else featurePcm
        val syvenuMask = presence?.let { VocalPresence.toProportionalMask(it, structurePcm.size / TrackFeatures.sampleRate).takeIf { it.isNotEmpty() } }
        // Export refuses a failed structure pass. Playback may degrade; evidence exports must say it failed.
        val map = StructureAnalyzer.fromPcm(
            trackKey = trackId, pcm = structurePcm, sampleRate = TrackFeatures.sampleRate.toInt(), bpm = leading.bpm.coerceAtLeast(1.0),
            mask = syvenuMask, partial = featurePcm.size > cap, startedMs = nowMs(), now = nowMs,
            fullTrackDurationSec = duration, downbeatsHint = downs.toDoubleArray().takeIf { it.isNotEmpty() },
            v2Vocal = syvenuMask ?: features.vocalActivityMask.map { heuristicVocalOnModelScale(it).toFloat() }.toFloatArray(),
        )
        logSink?.info("analysis.ms=${nowMs() - started} model=nirovi vocals=syvenu track=$trackId")
        return WholeTrack(analysis, songMap = SongMapEnrichment.enrich(map, analysis), execution = execution)
    }

    internal fun analyzeMonoWindow(pcm: FloatArray, sampleRate: Double, startSeconds: Double, endSeconds: Double): AnalysisRegion? {
        if (!startSeconds.isFinite() || !endSeconds.isFinite() || endSeconds <= startSeconds || sampleRate <= 0) return null
        val from = (startSeconds * sampleRate).toInt().coerceIn(0, pcm.size)
        val to = (endSeconds * sampleRate).toInt().coerceIn(from, pcm.size)
        if (to - from < sampleRate.toInt()) return null
        val seconds = (to - from) / sampleRate
        val inputs = AudioMath.monoWindowInputs(Pcm(pcm.copyOfRange(from, to), sampleRate), startSeconds, seconds, false, true)
        val spec = inputs.forModel?.let { MelSpectrogram.compute(it) } ?: return null
        val grid = beatBackend?.track(spec, startSeconds) ?: return null
        val presence = vocalBackend?.track(spec) ?: return null
        return AnalysisRegion(AudioMath.snapGrid(grid, inputs.onsets), seconds, start = startSeconds,
            vocalWindow = VocalPresence.toCurveWindow(presence, startTime = startSeconds), levelWindow = inputs.levelWindow)
    }
}
