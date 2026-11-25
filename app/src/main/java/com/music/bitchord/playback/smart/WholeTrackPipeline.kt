package com.music.bitchord.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.smart.AudioMath.toGrid
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.max

/**
 * Outcome of a whole-track Pass-1 + Pass-2 analysis attempt.
 *
 * [decodedShort] distinguishes a truncated/holey copy (counted toward retry strikes) from
 * an unusable/missing copy.
 */
internal class WholeTrack(
    val analysis: TrackAnalysis?,
    val decodedShort: Boolean = false,
    val aborted: Boolean = false,
)

/**
 * Decoded mono track reduced to the buffers needed by downstream stages, so the full-rate
 * PCM buffer dies before mel, ONNX, or structure runs.
 */
internal class WholeTrackAudio(
    val decodedShort: Boolean = false,
    val decodedSeconds: Double = 0.0,
    val structRate: Double = TrackFeatures.sampleRate,
    val featureSamples: FloatArray? = null,
    var modelSamples: FloatArray? = null,
    val levelWindow: CurveWindow? = null,
    val onsets: List<Double> = emptyList(),
    val decodeMs: Long = 0L,
)

/** Per-pass wall timings, including opening/windowed work; never shared between passes. */
internal class AnalysisStageTimings {
    var decodeMs = 0L
    var melMs = 0L
    var niroviMs = 0L
    var syvenuMs = 0L

    fun log(trackId: String, level: String, structureMs: Long = 0L, totalMs: Long) {
        Log.i(
            "BitChordTrackAnalyzer",
            "analysis.stage track=$trackId level=$level decode=$decodeMs mel=$melMs " +
                "nirovi=$niroviMs syvenu=$syvenuMs structure=$structureMs total=$totalMs",
        )
    }
}

/**
 * Whole-track mono decode, Nirovi/Syvenu shared-spectrogram inference, StructureV2 enrichment,
 * and single-window analysis pipeline extracted from [TrackAnalyzer] (M9 slice T-5).
 */
@UnstableApi
internal class WholeTrackPipeline(
    private val context: Context,
    private val cache: AudioCache,
    private val tracker: BeatTracker,
    private val vocalPresence: VocalPresence,
    private val beatLock: Any,
    private val presenceLock: Any,
    private val badRenditions: MutableSet<String>,
    private val triedRenditions: MutableSet<String>,
    private val shortDecodes: ConcurrentHashMap<String, Int>,
    private val discarded: ConcurrentHashMap<String, Int>,
    private val isStopped: () -> Boolean,
    private val isOverheated: () -> Boolean,
    private val playbackFirst: () -> Unit,
    private val yieldOrStopped: () -> Boolean,
    private val canUseWindowedStems: () -> Boolean,
    private val elapsedNow: () -> Long,
    private val copyToRead: (String, Uri, Double) -> AudioCopy?,
) {
    /**
     * Decodes the whole track mono once, verifies completeness against container
     * duration, and produces resampled feature/model buffers plus level & flux onsets.
     */
    private fun decodeWholeTrack(
        trackId: String,
        uri: Uri,
        copy: AudioCopy,
        effectiveDuration: Double,
    ): WholeTrackAudio {
        val structRate = TrackFeatures.sampleRate
        val decodeStarted = elapsedNow()
        val decoded = copy.open()?.use { AudioDecoder.decodeRegion(it, 0.0, effectiveDuration) { yieldOrStopped() } }
            ?: return WholeTrackAudio()
        val (pcm, _) = decoded
        val decodeMs = elapsedNow() - decodeStarted

        // A decode that stops early is indistinguishable downstream from trailing silence;
        // refuse rather than publishing false mix-out anchors.
        val decodedSeconds = if (pcm.sampleRate > 0) pcm.samples.size / pcm.sampleRate else 0.0
        if (decodedSeconds < effectiveDuration * MIN_DECODED_FRACTION) {
            Log.w(
                TAG,
                "Analysis of $trackId refused: ${copy.key} decoded " +
                    "${"%.1f".format(Locale.ROOT, decodedSeconds)}s of a " +
                    "${"%.1f".format(Locale.ROOT, effectiveDuration)}s " +
                    if (copy.rendition != null) "container — cached with holes?" else "container — truncated file?",
            )
            copy.rendition?.let { rendition ->
                badRenditions.add(rendition.key)
                if (rendition.isComplete && (discarded[rendition.key] ?: 0) < MAX_RENDITION_DISCARDS &&
                    cache.discardBadRendition(uri, rendition.key)
                ) {
                    discarded.merge(rendition.key, 1, Int::plus)
                    badRenditions.remove(rendition.key)
                    triedRenditions.remove(rendition.key)
                    shortDecodes.remove(trackId)
                }
            }
            return WholeTrackAudio(decodedShort = true)
        }

        val featureSamples = if (abs(pcm.sampleRate - structRate) > 1.0) {
            TrackFeatures.resample(pcm.samples, pcm.sampleRate, structRate) ?: return WholeTrackAudio()
        } else {
            pcm.samples
        }

        val wholeModelScope = decodedSeconds <= MAX_WHOLE_MODEL_SECONDS
        val modelSamples = if (wholeModelScope) {
            if (abs(pcm.sampleRate - MelSpectrogram.sampleRate) > 1.0) {
                MelSpectrogram.resample(pcm.samples, pcm.sampleRate, MelSpectrogram.sampleRate)
            } else {
                pcm.samples
            }
        } else {
            null
        }
        val levelWindow = if (wholeModelScope) AudioMath.levelCurve(pcm, 0.0) else null
        val onsets = if (wholeModelScope) AudioMath.fluxOnsets(pcm, 0.0) else emptyList()

        return WholeTrackAudio(
            decodedShort = false,
            decodedSeconds = decodedSeconds,
            structRate = structRate,
            featureSamples = featureSamples,
            modelSamples = modelSamples,
            levelWindow = levelWindow,
            onsets = onsets,
            decodeMs = decodeMs,
        )
    }

    /** Builds and stores [SongMap] with Syvenu vocal evidence and Nirovi bar grid. */
    private fun buildPhraseMap(
        trackId: String,
        featureSamples: FloatArray,
        structRate: Double,
        bpm: Double,
        effectiveDuration: Double,
        downbeatsHint: DoubleArray?,
        presence50fps: FloatArray?,
        dspMask: List<Double>,
    ): Pair<SongMap?, Long> {
        if (skipPhraseStructure()) return null to 0L
        val started = elapsedNow()
        val fullCap = (structRate * MAX_WHOLE_MODEL_SECONDS).toInt().coerceAtLeast(1)
        val fullPcm = if (featureSamples.size > fullCap) featureSamples.copyOf(fullCap) else featureSamples
        val fullPcmDuration = if (structRate > 0.0) fullPcm.size / structRate else 0.0
        val syvenuMask = presence50fps?.let {
            VocalPresence.toProportionalMask(
                presence50fps = it,
                durationSeconds = fullPcmDuration,
                startTime = 0.0,
            ).takeIf { arr -> arr.isNotEmpty() }
        }
        val dspV2Vocal = dspMask
            .map { heuristicVocalOnModelScale(it).toFloat() }
            .toFloatArray()
            .takeIf { it.isNotEmpty() }
        val map = runCatching {
            StructureAnalyzer.fromPcm(
                trackKey = trackId,
                pcm = fullPcm,
                sampleRate = structRate.toInt(),
                bpm = bpm.coerceAtLeast(1.0),
                mask = syvenuMask,
                partial = featureSamples.size > fullCap,
                startedMs = started,
                now = { elapsedNow() },
                fullTrackDurationSec = effectiveDuration,
                downbeatsHint = downbeatsHint,
                v2Vocal = syvenuMask ?: dspV2Vocal,
            )
        }.onFailure { Log.i("BitChordPhrase", "structure skipped: ${it.message}") }.getOrNull()
        val correctedMap = if (map != null && !map.partial && effectiveDuration > (fullPcm.size / structRate) + 1.0) {
            map.copy(partial = true)
        } else map
        val structWallMs = elapsedNow() - started
        if (correctedMap != null) {
            PhraseMaps.put(trackId, correctedMap)
            Log.i(
                "BitChordPhrase",
                "structMs=${correctedMap.structMs} structWallMs=$structWallMs " +
                    "bars=${correctedMap.sections.lastOrNull()?.endBar ?: 0} partial=${correctedMap.partial} " +
                    "duration=${correctedMap.durationSec} contentEnd=${correctedMap.contentEndSec}",
            )
        }
        return correctedMap to structWallMs
    }

    /** Severe heat or battery saver: keep the scalar map and skip the PCM structure pass. */
    private fun skipPhraseStructure(): Boolean {
        if (isOverheated()) return true
        val power = context.getSystemService(PowerManager::class.java)
        return power?.isPowerSaveMode == true
    }

    /**
     * The whole-track pass.
     *
     * Pass-1 (high priority): a Nirovi grid + lightweight DSP on the opening mono
     * window so [onGridReady] publishes a usable intermediate in under a second.
     * Pass-2 (low priority): single mono decode for whole-track [TrackFeatures],
     * shared [MelSpectrogram] into Nirovi ([BeatTracker]) and Syvenu ([VocalPresence]),
     * and [StructureAnalyzer] / [StructureV2] enrichment. Zero UMX work runs here.
     */
    fun analyze(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        onGridReady: (Double, BeatTracker.Grid?, TrackFeatures.Features?, CurveWindow?, TrackAnalysisProvenance) -> Unit = { _, _, _, _, _ -> },
        shouldAbort: () -> Boolean = { false },
    ): WholeTrack {
        val started = elapsedNow()
        val timings = AnalysisStageTimings()
        if (!AutomixModels.isReady()) return WholeTrack(null, aborted = true)
        val copy = copyToRead(trackId, uri, durationSeconds) ?: return WholeTrack(null)
        // Recorded before the outcome is known: a missing analysis stays unretryable
        // per copy key, while bad/short copies are handled explicitly below.
        triedRenditions.add(copy.key)
        val openSource = copy.open

        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId: ${copy.key} has no readable duration")
            return WholeTrack(empty(trackId, 0.0, copy.provenance))
        }

        val span = BeatTracker.WINDOW_SECONDS
        val headEnd = minOf(effectiveDuration, HEAD_MODEL_WINDOWS * span - (HEAD_MODEL_WINDOWS - 1) * AudioMath.WINDOW_OVERLAP_SECONDS)
        val tailStart = max(0.0, effectiveDuration - (TAIL_MODEL_WINDOWS * span - (TAIL_MODEL_WINDOWS - 1) * AudioMath.WINDOW_OVERLAP_SECONDS))

        // Pass-1 before whole-track Pass-2: mono opening grid + light DSP (no vocal pass).
        if (!isStopped()) {
            playbackFirst()
            val preHead = analyzeMonoWindow(openSource, 0.0, minOf(effectiveDuration, span), deriveFeatures = true, timings = timings)
            if (preHead != null) {
                onGridReady(effectiveDuration, preHead.grid, preHead.features, preHead.levelWindow, copy.provenance)
            }
        }
        if (isStopped()) return WholeTrack(null)
        if (shouldAbort()) return WholeTrack(null, aborted = true)

        playbackFirst()
        val audio = decodeWholeTrack(trackId, uri, copy, effectiveDuration)
        timings.decodeMs += audio.decodeMs
        if (isStopped()) return WholeTrack(null)
        if (audio.decodedShort) return WholeTrack(null, decodedShort = true)
        val featureSamples = audio.featureSamples ?: return WholeTrack(empty(trackId, effectiveDuration, copy.provenance))
        if (shouldAbort()) return WholeTrack(null, aborted = true)

        val features = TrackFeatures.analyze(featureSamples, effectiveDuration)
            ?: return WholeTrack(empty(trackId, effectiveDuration, copy.provenance))
        if (isStopped()) return WholeTrack(null)
        if (shouldAbort()) return WholeTrack(null, aborted = true)

        var melMs = 0L
        var fullGrid: BeatTracker.Grid? = null
        var presence50fps: FloatArray? = null
        val allBeats: SeamedBeats
        val vocalWindows: List<CurveWindow>
        val levelWindows: List<CurveWindow>
        val instrumentalWindows: List<CurveWindow>
        val vocalActivityMask: List<Double>
        val vocalPresence50fps: List<Float>

        val modelPcm = audio.modelSamples
        if (modelPcm != null) {
            // Whole-track single pass (<= 480 s): compute 50 fps mel spectrogram once and
            // drop the 22.05 kHz PCM buffer before Nirovi + Syvenu run on the same spectrogram.
            val specStarted = elapsedNow()
            val spec = MelSpectrogram.compute(modelPcm)
            melMs = elapsedNow() - specStarted
            timings.melMs += melMs
            audio.modelSamples = null

            if (isStopped()) return WholeTrack(null)
            if (shouldAbort()) return WholeTrack(null, aborted = true)

            val rawGrid = spec?.let {
                synchronized(beatLock) {
                    if (yieldOrStopped()) null else {
                        val inferStarted = elapsedNow()
                        tracker.track(it, offsetSeconds = 0.0, melMs = melMs).also {
                            timings.niroviMs += elapsedNow() - inferStarted
                        }
                    }
                }
            }
            fullGrid = rawGrid?.let { AudioMath.snapGrid(it, audio.onsets) }

            if (isStopped()) return WholeTrack(null)
            if (shouldAbort()) return WholeTrack(null, aborted = true)

            presence50fps = spec?.let {
                synchronized(presenceLock) {
                    if (yieldOrStopped()) null else {
                        val inferStarted = elapsedNow()
                        vocalPresence.track(it).also { timings.syvenuMs += elapsedNow() - inferStarted }
                    }
                }
            }
            if (isStopped()) return WholeTrack(null)
            if (shouldAbort()) return WholeTrack(null, aborted = true)

            allBeats = fullGrid?.let { SeamedBeats(it.beats, it.downbeats, it.beatConfidence) }
                ?: SeamedBeats(emptyList(), emptyList(), 0.0)
            vocalWindows = listOfNotNull(
                presence50fps?.let { VocalPresence.toCurveWindow(it, startTime = 0.0) },
            )
            levelWindows = listOfNotNull(audio.levelWindow)
            instrumentalWindows = if (presence50fps != null && canUseWindowedStems()) {
                listOf(
                    CurveWindow(
                        startTime = 0.0,
                        frameRate = MelSpectrogram.frameRate,
                        values = FloatArray(presence50fps.size) { (1f - presence50fps[it]).coerceIn(0f, 1f) },
                    ),
                )
            } else {
                emptyList()
            }
            vocalActivityMask = presence50fps?.let {
                VocalPresence.toVocalActivityMask(
                    presence50fps = it,
                    energyCurve = features.energyCurve,
                    startTime = 0.0,
                    neutralValue = NEUTRAL_VOCAL,
                )
            } ?: features.vocalActivityMask.map(::heuristicVocalOnModelScale)
            vocalPresence50fps = presence50fps?.toList().orEmpty()
        } else {
            // Tracks over 8 minutes use head + tail mono windows so mel tensors stay small.
            val regions = ArrayList<AnalysisRegion>()
            for ((from, to) in AudioMath.modelWindows(headEnd, tailStart, effectiveDuration, span)) {
                if (isStopped()) return WholeTrack(null)
                if (shouldAbort()) return WholeTrack(null, aborted = true)
                analyzeMonoWindow(openSource, from, to, deriveFeatures = false, includeVocals = true, timings = timings)
                    ?.let(regions::add)
            }
            if (isStopped()) return WholeTrack(null)
            allBeats = AudioMath.seamBeats(regions)
            vocalWindows = regions.mapNotNull { it.vocalWindow }
            levelWindows = regions.mapNotNull { it.levelWindow }
            instrumentalWindows = regions.mapNotNull { it.instrumentalWindow }
            vocalActivityMask = VocalPresence.toVocalActivityMask(
                windows = vocalWindows,
                energyCurve = features.energyCurve,
                neutralValue = NEUTRAL_VOCAL,
            ).takeIf { vocalWindows.isNotEmpty() }
                ?: features.vocalActivityMask.map(::heuristicVocalOnModelScale)
            vocalPresence50fps = emptyList()
        }

        val headSide = AudioMath.beatSide(allBeats, 0.0, headEnd)
        val tailSide = AudioMath.beatSide(allBeats, tailStart, effectiveDuration)
        val leading = fullGrid ?: tailSide?.toGrid() ?: headSide?.toGrid()
        val downbeats = allBeats.downbeats.ifEmpty { features.downbeats }.sorted()

        val (structureMap, structWallMs) = buildPhraseMap(
            trackId = trackId,
            featureSamples = featureSamples,
            structRate = audio.structRate,
            bpm = leading?.bpm ?: features.bpm,
            effectiveDuration = effectiveDuration,
            downbeatsHint = downbeats.toDoubleArray().takeIf { it.isNotEmpty() },
            presence50fps = presence50fps,
            dspMask = features.vocalActivityMask,
        )

        val vocalMode = when {
            presence50fps != null -> "syvenu-whole"
            vocalWindows.isNotEmpty() -> "syvenu-windowed"
            else -> "dsp"
        }
        Log.d(
            TAG,
            "Analysed $trackId: bpm=${leading?.bpm ?: features.bpm} " +
                "conf=${leading?.beatConfidence ?: features.beatConfidence} " +
                "key=${features.key} contentEnd=${features.contentEndTime} " +
                "mixOutCandidates=${features.mixOutCandidates.size} " +
                "grid=${if (leading != null) tracker.lastModelTag else "dsp"} " +
                "beats=${headSide?.beats?.size ?: 0}+${tailSide?.beats?.size ?: 0} " +
                "downbeats=${headSide?.downbeats?.size ?: 0}+${tailSide?.downbeats?.size ?: 0} " +
                "vocalMask=$vocalMode vocalWindows=${vocalWindows.size}",
        )

        val analysis = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = effectiveDuration,
            quality = TrackAnalysis.QUALITY_FULL,
            contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: effectiveDuration,
            bpm = leading?.bpm ?: features.bpm,
            beatInterval = leading?.beatInterval ?: features.beatInterval,
            beatConfidence = leading?.beatConfidence ?: features.beatConfidence,
            downbeats = downbeats,
            firstBeat = leading?.firstBeat ?: headSide?.beats?.firstOrNull() ?: features.firstBeat,
            phraseBoundaries = features.phraseBoundaries,
            key = features.key,
            keyConfidence = features.keyConfidence,
            // djmix25: per-region keys from the dual-profile native pass.
            keyHead = features.keyHead,
            keyTail = features.keyTail,
            keyHeadConfidence = features.keyHeadConfidence,
            keyTailConfidence = features.keyTailConfidence,
            audibleStartTime = features.audibleStartTime,
            pickupTime = features.pickupTime,
            introEndTime = features.introEndTime,
            outroStartTime = features.outroStartTime,
            mixInTime = features.mixInTime,
            mixOutTime = features.mixOutTime,
            mixInCandidates = features.mixInCandidates,
            mixOutCandidates = features.mixOutCandidates,
            energyCurve = features.energyCurve,
            lowEnergyCurve = features.lowEnergyCurve,
            vocalActivityMask = vocalActivityMask,
            vocalProbability = features.vocalProbability,
            headBeats = headSide,
            tailBeats = tailSide,
            vocalWindows = vocalWindows,
            levelWindows = levelWindows,
            instrumentalWindows = instrumentalWindows,
            vocalPresence50fps = vocalPresence50fps,
            provenance = copy.provenance,
        )
        if (structureMap != null) {
            val enriched = SongMapEnrichment.enrich(structureMap, analysis)
            if (enriched !== structureMap) {
                PhraseMaps.put(trackId, enriched)
            } else if (analysis.vocalWindows.isNotEmpty() && analysis.bpm > 0.0 && structureMap.bpm > 0.0 &&
                abs(structureMap.bpm - analysis.bpm) / analysis.bpm > 0.02
            ) {
                Log.d(TAG, "SongMap enrichment skipped for $trackId: bpm mismatch base=${structureMap.bpm} analysis=${analysis.bpm}")
            }
        }
        timings.log(trackId, "FULL", structureMs = structWallMs, totalMs = elapsedNow() - started)
        return WholeTrack(analysis)
    }

    /**
     * Decodes one mono window and runs Nirovi ([BeatTracker]) and optionally
     * Syvenu ([VocalPresence]) on the exact same [MelSpectrogram].
     *
     * Full-rate PCM is reduced in [AudioMath.monoWindowInputs] before mel/ONNX allocations start,
     * keeping peak memory minimal on mid-tier devices.
     */
    fun analyzeMonoWindow(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        deriveFeatures: Boolean = false,
        includeVocals: Boolean = false,
        timings: AnalysisStageTimings? = null,
    ): AnalysisRegion? {
        playbackFirst()
        val decodeStarted = elapsedNow()
        val decoded = openSource()?.use { AudioDecoder.decodeRegion(it, startSeconds, endSeconds) { yieldOrStopped() } }
            ?: run {
                Log.d(TAG, "Region [$startSeconds, $endSeconds) would not open")
                return null
            }
        timings?.let { it.decodeMs += elapsedNow() - decodeStarted }
        val (pcm, actualStart) = decoded
        if (pcm.samples.size < pcm.sampleRate) {
            Log.d(TAG, "Region [$startSeconds, $endSeconds) decoded ${pcm.samples.size} frames; too few")
            return null
        }

        val seconds = pcm.samples.size / pcm.sampleRate
        val inputs = AudioMath.monoWindowInputs(pcm, actualStart, seconds, deriveFeatures, computeLevel = deriveFeatures || includeVocals)

        playbackFirst()
        val specStarted = elapsedNow()
        val spec = inputs.forModel?.let { MelSpectrogram.compute(it) }
        val melMs = elapsedNow() - specStarted
        if (inputs.forModel != null) timings?.let { it.melMs += melMs }

        val rawGrid = spec?.let {
            synchronized(beatLock) {
                if (yieldOrStopped()) null else {
                    val inferStarted = elapsedNow()
                    tracker.track(it, offsetSeconds = actualStart, melMs = melMs).also {
                        timings?.let { it.niroviMs += elapsedNow() - inferStarted }
                    }
                }
            }
        }
        val vocalValues = if (includeVocals && spec != null) {
            synchronized(presenceLock) {
                if (yieldOrStopped()) null else {
                    val inferStarted = elapsedNow()
                    vocalPresence.track(spec).also {
                        timings?.let { it.syvenuMs += elapsedNow() - inferStarted }
                    }
                }
            }
        } else {
            null
        }
        return AnalysisRegion(
            grid = rawGrid?.let { AudioMath.snapGrid(it, inputs.onsets) },
            seconds = seconds,
            features = inputs.derived,
            start = actualStart,
            vocalWindow = vocalValues?.let { VocalPresence.toCurveWindow(it, startTime = actualStart) },
            levelWindow = inputs.levelWindow,
            instrumentalWindow = if (vocalValues != null && canUseWindowedStems()) {
                CurveWindow(
                    actualStart,
                    MelSpectrogram.frameRate,
                    FloatArray(vocalValues.size) { (1f - vocalValues[it]).coerceIn(0f, 1f) },
                )
            } else {
                null
            },
        )
    }

    /** Recorded ready-but-empty so a track that cannot be decoded is not retried every tick. */
    private fun empty(trackId: String, durationSeconds: Double, provenance: TrackAnalysisProvenance) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
        provenance = provenance,
    )

    private companion object {
        const val TAG = "BitChordTrackAnalyzer"
        const val NEUTRAL_VOCAL = 0.2
        const val MAX_WHOLE_MODEL_SECONDS = 480.0
        const val HEAD_MODEL_WINDOWS = 2
        const val TAIL_MODEL_WINDOWS = 3
        const val MIN_DECODED_FRACTION = 0.95
        const val MAX_RENDITION_DISCARDS = 1
    }
}
