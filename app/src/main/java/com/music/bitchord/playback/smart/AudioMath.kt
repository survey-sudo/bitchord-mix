package com.music.bitchord.playback.smart

import androidx.media3.common.util.UnstableApi
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Everything a decoded mono window contributes once its PCM buffers are released.
 * [seconds] is what was actually decoded, which for a partially cached file
 * may be less than requested.
 */
@UnstableApi
internal class AnalysisRegion(
    val grid: BeatTracker.Grid?,
    val seconds: Double,
    val features: TrackFeatures.Features? = null,
    val start: Double = 0.0,
    val vocalWindow: CurveWindow? = null,
    val levelWindow: CurveWindow? = null,
    val instrumentalWindow: CurveWindow? = null,
)

/** One beat list across windows: reconciled cleanly across overlapping boundaries. */
internal class SeamedBeats(
    val beats: List<Double>,
    val downbeats: List<Double>,
    val confidence: Double,
)

/** A window's 22.05 kHz model audio, flux onsets, level curve, and optional DSP features. */
internal class MonoInputs(
    val forModel: FloatArray?,
    val derived: TrackFeatures.Features?,
    val onsets: List<Double>,
    val levelWindow: CurveWindow?,
)

/**
 * Window partitioning, beat-seam reconciliation, onset snapping, and mono PCM feature
 * extraction helpers extracted from [TrackAnalyzer] (M9 slice T-4).
 */
@UnstableApi
internal object AudioMath {

    /** Overlap between neighbouring fallback windows, so a beat on a seam is seen from both sides. */
    internal const val WINDOW_OVERLAP_SECONDS = 1.0

    /** Fallback window partitioning for tracks exceeding the whole-model length cap. */
    internal fun modelWindows(headEnd: Double, tailStart: Double, duration: Double, span: Double): List<Pair<Double, Double>> {
        val spans = if (tailStart <= headEnd) {
            listOf(0.0 to duration)
        } else {
            listOf(0.0 to headEnd, tailStart to duration)
        }
        val windows = ArrayList<Pair<Double, Double>>()
        for ((from, to) in spans) {
            val length = to - from
            if (length < 1.0) continue
            if (length <= span) {
                windows += from to to
                continue
            }
            val count = ceil((length - WINDOW_OVERLAP_SECONDS) / (span - WINDOW_OVERLAP_SECONDS) - 1e-6).toInt()
            val stride = (length - span) / (count - 1)
            for (index in 0 until count) {
                val start = from + index * stride
                windows += start to minOf(to, start + span)
            }
        }
        return windows
    }

    /** Reconciles beat and downbeat lists across overlapping fallback analysis windows. */
    internal fun seamBeats(regions: List<AnalysisRegion>): SeamedBeats {
        val graded = regions.filter { it.grid != null }.sortedBy { it.start }
        if (graded.isEmpty()) return SeamedBeats(emptyList(), emptyList(), 0.0)
        var weighted = 0.0
        var count = 0
        for (region in graded) {
            val grid = region.grid!!
            weighted += grid.beatConfidence * grid.beats.size
            count += grid.beats.size
        }
        val joined = GridRepair.reconcile(
            graded.map { region ->
                val grid = region.grid!!
                GridRepair.Window(grid.beats, grid.downbeats, grid.bpm, grid.beatConfidence)
            },
        )
        return SeamedBeats(joined.beats, joined.downbeats, if (count > 0) weighted / count else 0.0)
    }

    /** The model's grid over [from]..[to], or null where it found too few beats there. */
    internal fun beatSide(all: SeamedBeats, from: Double, to: Double): BeatSide? {
        val beats = all.beats.filter { it >= from && it <= to }
        val bpm = BeatTracker.tempoFromBeats(beats)
        if (bpm <= 0) return null
        return BeatSide(
            beats = beats,
            downbeats = all.downbeats.filter { it >= from && it <= to },
            bpm = bpm,
            confidence = all.confidence,
        )
    }

    /** Converts a [BeatSide] back to a single [BeatTracker.Grid]. */
    internal fun BeatSide.toGrid(): BeatTracker.Grid = BeatTracker.Grid(
        beats = beats,
        downbeats = downbeats,
        bpm = bpm,
        beatInterval = 60 / bpm,
        firstBeat = beats.first(),
        beatConfidence = confidence,
    )

    /**
     * Pulls the model's beats onto spectral-flux onsets, at most 35 ms, and
     * lowers confidence when the onsets are there but do not lock to the grid.
     */
    internal fun snapGrid(grid: BeatTracker.Grid, onsets: List<Double>): BeatTracker.Grid {
        if (onsets.size < 8) return grid
        val beats = GridRepair.snapToOnsets(grid.beats, onsets)
        val downs = GridRepair.snapToOnsets(grid.downbeats, onsets)
        val coherence = GridRepair.phaseCoherence(beats, onsets)
        var confidence = grid.beatConfidence
        if (coherence in 0.0..0.42 && beats.size >= 16 && onsetCoverage(beats, onsets) >= 0.5) {
            confidence = (confidence * (coherence / 0.42)).coerceIn(0.0, 1.0)
        }
        val tempo = BeatTracker.tempoFromBeats(beats)
        val interval = if (tempo > 0) 60.0 / tempo else grid.beatInterval
        val bpm = if (tempo > 0) tempo else grid.bpm
        return grid.copy(
            beats = beats,
            downbeats = downs,
            bpm = bpm,
            beatInterval = interval,
            firstBeat = beats.firstOrNull() ?: grid.firstBeat,
            beatConfidence = confidence,
        )
    }

    /** Fraction of [beats] with an onset within 70 ms. Sparse flux does not get to reject a grid. */
    internal fun onsetCoverage(beats: List<Double>, onsets: List<Double>): Double {
        if (beats.isEmpty() || onsets.isEmpty()) return 0.0
        var hits = 0
        for (beat in beats) {
            hits += if (nearestOnset(onsets, beat)?.let { abs(it - beat) <= 0.070 } == true) 1 else 0
        }
        return hits.toDouble() / beats.size
    }

    /** Nearest timestamp in sorted [onsets] to [beat], or null when [onsets] is empty. */
    internal fun nearestOnset(onsets: List<Double>, beat: Double): Double? {
        if (onsets.isEmpty()) return null
        var low = 0
        var high = onsets.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (onsets[mid] < beat) low = mid + 1 else high = mid
        }
        val after = onsets[low]
        val before = onsets.getOrNull(low - 1) ?: return after
        return if (abs(before - beat) <= abs(after - beat)) before else after
    }

    /** Half-wave spectral flux onsets over mono PCM. Times are track seconds. */
    internal fun fluxOnsets(pcm: AudioDecoder.Pcm, start: Double): List<Double> {
        val rate = pcm.sampleRate
        if (rate <= 0.0 || pcm.samples.isEmpty()) return emptyList()
        val hop = (rate / 200.0).toInt().coerceAtLeast(1)
        val frames = pcm.samples.size / hop
        if (frames < 8) return emptyList()
        val flux = FloatArray(frames)
        var previous = 0.0
        for (index in 0 until frames) {
            var sum = 0.0
            var n = 0
            var sample = index * hop
            val end = min(pcm.samples.size, sample + hop)
            while (sample < end) {
                val v = pcm.samples[sample]
                sum += v * v
                n++
                sample += 4
            }
            val energy = if (n == 0) 0.0 else sqrt(sum / n)
            val delta = energy - previous
            flux[index] = if (delta > 0) delta.toFloat() else 0f
            previous = energy
        }
        var mean = 0.0
        for (value in flux) mean += value
        mean /= frames
        var variance = 0.0
        for (value in flux) {
            val d = value - mean
            variance += d * d
        }
        val threshold = mean + sqrt(variance / frames)
        val onsets = ArrayList<Double>()
        for (index in 1 until frames - 1) {
            if (flux[index] >= threshold && flux[index] >= flux[index - 1] && flux[index] > flux[index + 1]) {
                onsets += start + index * hop / rate
            }
        }
        return onsets
    }

    /** RMS level over mono PCM in frames of 1 / [LEVEL_FRAME_RATE] s, on the [levelFromRms] scale. */
    internal fun levelCurve(pcm: AudioDecoder.Pcm, actualStart: Double): CurveWindow? {
        val frame = (pcm.sampleRate / LEVEL_FRAME_RATE).toInt()
        if (frame <= 0) return null
        val count = pcm.samples.size / frame
        if (count <= 0) return null
        val values = FloatArray(count)
        for (index in 0 until count) {
            var sum = 0.0
            var n = 0
            var sample = index * frame
            val end = min(pcm.samples.size, sample + frame)
            while (sample < end) {
                val v = pcm.samples[sample]
                sum += v * v
                n++
                sample += 4
            }
            values[index] = if (n == 0) 0f else levelFromRms(sqrt(sum / n)).toFloat()
        }
        return CurveWindow(actualStart, LEVEL_FRAME_RATE, values)
    }

    /** Resamples and derives level, onset, and scalar feature inputs for one decoded mono window. */
    internal fun monoWindowInputs(
        pcm: AudioDecoder.Pcm,
        actualStart: Double,
        seconds: Double,
        deriveFeatures: Boolean,
        computeLevel: Boolean,
    ): MonoInputs {
        val levelWindow = if (computeLevel) levelCurve(pcm, actualStart) else null
        val onsets = fluxOnsets(pcm, actualStart)
        val forModel = if (abs(pcm.sampleRate - MelSpectrogram.sampleRate) > 1.0) {
            MelSpectrogram.resample(pcm.samples, pcm.sampleRate, MelSpectrogram.sampleRate)
        } else {
            pcm.samples
        }
        val derived = if (deriveFeatures) {
            val forFeatures = if (abs(pcm.sampleRate - TrackFeatures.sampleRate) > 1.0) {
                TrackFeatures.resample(pcm.samples, pcm.sampleRate, TrackFeatures.sampleRate)
            } else {
                pcm.samples
            }
            forFeatures?.let { TrackFeatures.analyze(it, seconds) }
        } else {
            null
        }
        return MonoInputs(forModel, derived, onsets, levelWindow)
    }
}
