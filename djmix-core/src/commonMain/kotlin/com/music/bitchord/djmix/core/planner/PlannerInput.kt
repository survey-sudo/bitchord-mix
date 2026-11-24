package com.music.bitchord.djmix.core.planner

import com.music.bitchord.djmix.core.contracts.AbruptnessClass
import com.music.bitchord.djmix.core.contracts.DeckStemRequirement
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.RecipeFamily
import com.music.bitchord.djmix.core.contracts.RecipeFxType
import com.music.bitchord.djmix.core.contracts.SectionType
import com.music.bitchord.djmix.core.contracts.StructureSectionV3
import com.music.bitchord.djmix.core.contracts.TimeRangeMs
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Pure input request for [PairPlanner.plan] and [PairPlanner.planLastResortMusicalExit].
 * Contains no Android types, I/O handles, or system clock references.
 */
@Serializable
data class PlannerRequest(
    val generation: Long,
    val outTrack: TrackAnalysisV3,
    val inTrack: TrackAnalysisV3,
    val outTitle: String = "",
    val inTitle: String = "",
    val djLevel: DjLevel,
    val positionMs: Long,
    val minExitMs: Long? = null,
    val outGenus: String? = null,
    val inGenus: String? = null,
    val recentRecipeIds: List<String> = emptyList(),
    val transitionsSinceLastAbrupt: Int = 999,
    val excludedRecipeIds: Set<String> = emptySet(),
    val preferNoStemVariant: Boolean = false,
    val tasteWeights: Map<String, Double> = emptyMap(),
    val outDecodeRangeMs: TimeRangeMs? = null,
    val inDecodeRangeMs: TimeRangeMs? = null,
) {
    init {
        require(generation >= 0L) { "generation must be non-negative, got $generation" }
        require(positionMs >= 0L) { "positionMs must be non-negative, got $positionMs" }
        require(transitionsSinceLastAbrupt >= 0) {
            "transitionsSinceLastAbrupt must be non-negative, got $transitionsSinceLastAbrupt"
        }
    }
}

/**
 * Lightweight planner descriptor for a transition recipe (`S1..S6`, `C1..C9`, `F1..F11`,
 * or JSON-backed `RecipeV2` overrides in M4).
 */
@Serializable
data class RecipeCatalogEntry(
    val id: String,
    val displayName: String,
    val description: String = "",
    val level: DjLevel,
    val family: RecipeFamily,
    val abruptness: AbruptnessClass,
    val fxTags: Set<RecipeFxType> = emptySet(),
    val requiredStems: Set<DeckStemRequirement> = emptySet(),
    val hasNoStemVariant: Boolean = true,
    val minGridConfidence: Float = 0.45f,
    val maxTempoDeltaPct: Double = level.maxStretch * 100.0,
    val isDropRecipe: Boolean = false,
    val defaultOverlapBars: Int = 8,
    val minPreRollBars: Int = 4,
    val maxPreRollBars: Int = 16,
    val requiresBeatMatch: Boolean = true,
    val requiresHarmony: Boolean = false,
    val requiresInDrop: Boolean = false,
    val requiresOutDrop: Boolean = false,
    val requiresOutLoop: Boolean = false,
    val swapsBass: Boolean = true,
    val isCut: Boolean = false,
    val minOverlapBars: Int = 2,
    val preferOutroExit: Boolean = true,
    val preferIntroEntry: Boolean = true,
    val preferBreakdownExit: Boolean = false,
    val avoidVocalExit: Boolean = true,
    val avoidVocalEntry: Boolean = true,
    val avoidBuildExit: Boolean = true,
    val preferPostSwapPhraseBars: Int = 4,
    val genreAffinity: Map<String, Double> = emptyMap(),
    val enabled: Boolean = true,
)

/**
 * Pure signal and grid helpers operating on [TrackAnalysisV3] timelines.
 */
internal object TrackSignalOps {
    const val CONTAINER_EOF_SAFETY_MS: Long = 60L
    const val VOCAL_FRAME_MS: Long = 20L // 50 fps Syvenu output
    const val LOW_GRID_CONFIDENCE_THRESHOLD: Float = 0.45f
    const val BASS_PRESENT_LINEAR: Float = 0.501f // > -6 dB relative amplitude
    const val VOCAL_SILENT_THRESHOLD: Float = 0.25f
    const val VOCAL_MID_LINE_THRESHOLD: Float = 0.50f
    const val MIN_COMMIT_LEAD_MS: Long = 6_000L
    const val COMMIT_LEAD_BARS: Int = 4

    fun safeOutEndMs(track: TrackAnalysisV3): Long =
        (min(track.audibleEndMs, track.durationMs) - CONTAINER_EOF_SAFETY_MS).coerceAtLeast(0L)

    fun safeInEndMs(track: TrackAnalysisV3): Long =
        (min(track.audibleEndMs, track.durationMs) - CONTAINER_EOF_SAFETY_MS).coerceAtLeast(0L)

    fun beatDurationMs(track: TrackAnalysisV3): Long =
        if (track.bpm > 1.0) (60_000.0 / track.bpm).roundToLong().coerceAtLeast(250L) else 500L

    fun barDurationMs(track: TrackAnalysisV3): Long {
        if (track.bpm > 1.0) return (240_000.0 / track.bpm).roundToLong().coerceAtLeast(1_000L)
        if (track.downbeatsMs.size >= 2) {
            val diffs = track.downbeatsMs.zipWithNext { a, b -> b - a }.filter { it in 800L..6_000L }.sorted()
            if (diffs.isNotEmpty()) return diffs[diffs.size / 2]
        }
        return 2_000L
    }

    fun phraseBars(track: TrackAnalysisV3): Int = when (track.structure.phraseLenBars) {
        4, 8, 16 -> track.structure.phraseLenBars
        else -> 8
    }

    fun commitLeadMs(barDurationMs: Long): Long = max(COMMIT_LEAD_BARS * barDurationMs, MIN_COMMIT_LEAD_MS)

    /** Octave-folded tempo ratio and percentage delta between [outBpm] and [inBpm]. */
    fun tempoDeltaPct(outBpm: Double, inBpm: Double): Double {
        if (outBpm <= 1.0 || inBpm <= 1.0) return 0.0
        val fold = alignedFold(outBpm, inBpm)
        val foldedIn = inBpm / fold
        return abs(foldedIn / outBpm - 1.0) * 100.0
    }

    fun alignedFold(outBpm: Double, inBpm: Double): Double {
        if (outBpm <= 0.0 || inBpm <= 0.0) return 1.0
        return doubleArrayOf(1.0, 2.0, 0.5).minBy { abs(ln((inBpm / it) / outBpm)) }
    }

    fun resolveGenre(explicit: String?, track: TrackAnalysisV3): String? {
        if (!explicit.isNullOrBlank()) return explicit.trim().uppercase()
        if (track.structure.mode.isNotBlank()) return track.structure.mode.trim().uppercase()
        return null
    }

    fun downbeatList(track: TrackAnalysisV3, barMs: Long): List<Long> {
        val raw = track.downbeatsMs.filter { it in 0L..track.durationMs }.distinct().sorted()
        if (raw.isNotEmpty()) return raw
        val beats = track.beatsMs.filter { it in 0L..track.durationMs }.distinct().sorted()
        if (beats.size >= 4) {
            return beats.filterIndexed { idx, _ -> idx % 4 == 0 }
        }
        if (track.durationMs <= 0L || barMs <= 0L) return emptyList()
        val count = ((track.durationMs / barMs) + 1L).toInt().coerceAtMost(512)
        return List(count) { it * barMs }
    }

    fun snapToDownbeat(track: TrackAnalysisV3, targetMs: Long, barMs: Long): Long {
        val downs = downbeatList(track, barMs)
        if (downs.isEmpty()) return targetMs.coerceAtLeast(0L)
        val nearest = downs.minByOrNull { abs(it - targetMs) } ?: return targetMs.coerceAtLeast(0L)
        return if (abs(nearest - targetMs) <= barMs / 2L) nearest else targetMs.coerceAtLeast(0L)
    }

    fun firstDownbeatAtOrAfter(track: TrackAnalysisV3, floorMs: Long, barMs: Long): Long {
        val downs = downbeatList(track, barMs)
        val hit = downs.firstOrNull { it >= floorMs }
        if (hit != null) return hit
        if (barMs <= 0L) return floorMs.coerceAtLeast(0L)
        return ceil(floorMs.toDouble() / barMs).toLong() * barMs
    }

    /** Max Syvenu vocal presence in `[fromMs, toMs]`, or null if uncovered. */
    fun maxVocalInWindow(track: TrackAnalysisV3, fromMs: Long, toMs: Long): Float? {
        val curve = track.vocalPresence50fps
        if (curve.isEmpty()) return null
        val start = fromMs.coerceAtLeast(0L)
        val end = toMs.coerceAtMost(track.durationMs)
        if (end <= start) return null
        val startIdx = (start / VOCAL_FRAME_MS).toInt()
        val endIdx = ((end - 1L) / VOCAL_FRAME_MS).toInt()
        if (startIdx >= curve.size || endIdx < 0) return null
        var peak = -1f
        for (i in startIdx.coerceAtLeast(0)..endIdx.coerceAtMost(curve.lastIndex)) {
            val v = curve[i]
            if (v.isFinite() && v in 0f..1f && v > peak) peak = v
        }
        return if (peak >= 0f) peak else null
    }

    fun meanVocalInWindow(track: TrackAnalysisV3, fromMs: Long, toMs: Long): Float? {
        val curve = track.vocalPresence50fps
        if (curve.isEmpty()) return null
        val start = fromMs.coerceAtLeast(0L)
        val end = toMs.coerceAtMost(track.durationMs)
        if (end <= start) return null
        val startIdx = (start / VOCAL_FRAME_MS).toInt()
        val endIdx = ((end - 1L) / VOCAL_FRAME_MS).toInt()
        if (startIdx >= curve.size || endIdx < 0) return null
        var sum = 0.0
        var count = 0
        for (i in startIdx.coerceAtLeast(0)..endIdx.coerceAtMost(curve.lastIndex)) {
            val v = curve[i]
            if (v.isFinite() && v in 0f..1f) {
                sum += v
                count++
            }
        }
        return if (count > 0) (sum / count).toFloat() else null
    }

    fun vocalAtMs(track: TrackAnalysisV3, timeMs: Long): Float? {
        val curve = track.vocalPresence50fps
        if (curve.isEmpty() || timeMs < 0L) return null
        val idx = (timeMs / VOCAL_FRAME_MS).toInt()
        if (idx !in curve.indices) return null
        val v = curve[idx]
        return if (v.isFinite() && v in 0f..1f) v else null
    }

    fun firstVocalEntryMs(track: TrackAnalysisV3, fromMs: Long): Long? {
        val curve = track.vocalPresence50fps
        if (curve.isNotEmpty()) {
            val startIdx = (fromMs.coerceAtLeast(0L) / VOCAL_FRAME_MS).toInt()
            for (i in startIdx until curve.size) {
                val v = curve[i]
                if (v.isFinite() && v > 0.35f) return i * VOCAL_FRAME_MS
            }
        }
        val vocalSection = track.structure.sections
            .filter { it.endMs > fromMs && it.vocal > 0.35f }
            .minByOrNull { it.startMs }
        return vocalSection?.startMs?.coerceAtLeast(fromMs)
    }

    fun sectionAt(track: TrackAnalysisV3, timeMs: Long): StructureSectionV3? =
        track.structure.sections.firstOrNull { timeMs >= it.startMs && timeMs < it.endMs }

    fun isInsideBuild(track: TrackAnalysisV3, timeMs: Long): Boolean {
        if (track.structure.builds.any { timeMs >= it.startMs && timeMs < it.endMs }) return true
        return track.structure.sections.any {
            it.type == SectionType.BUILD && timeMs >= it.startMs && timeMs < it.endMs
        }
    }

    fun isCleanPhraseOrSectionBoundary(track: TrackAnalysisV3, timeMs: Long, barMs: Long): Boolean {
        val tol = (beatDurationMs(track) / 3L).coerceAtLeast(150L)
        if (track.structure.phrases.any { abs(it.timeMs - timeMs) <= tol }) return true
        if (track.structure.sections.any { abs(it.startMs - timeMs) <= tol || abs(it.endMs - timeMs) <= tol }) {
            return true
        }
        if (track.structure.phrases.isEmpty() && track.structure.sections.isEmpty()) {
            val downs = downbeatList(track, barMs)
            val idx = downs.indexOfFirst { abs(it - timeMs) <= tol }
            if (idx >= 0) {
                val stride = phraseBars(track)
                val anchor = track.structure.phraseAnchorBar.coerceAtLeast(0)
                return ((idx - anchor) % stride + stride) % stride == 0
            }
        }
        return false
    }

    fun sectionBassLinear(section: StructureSectionV3?): Float {
        if (section == null) return 0f
        val raw = section.bass
        if (!raw.isFinite()) return 0f
        return if (raw < 0f) 10.0.pow(raw / 20.0).toFloat() else raw
    }

    fun energyAtMs(track: TrackAnalysisV3, timeMs: Long): Float? {
        sectionAt(track, timeMs)?.let { if (it.energy > 0f) return it.energy }
        val curve = track.energyCurve
        if (curve.isEmpty() || track.durationMs <= 0L) return null
        val idx = ((timeMs.toDouble() / track.durationMs) * curve.size).toInt().coerceIn(0, curve.lastIndex)
        val v = curve[idx]
        return if (v.isFinite()) v else null
    }

    fun meanEnergyInWindow(track: TrackAnalysisV3, fromMs: Long, toMs: Long): Double? {
        val curve = track.energyCurve
        if (curve.isNotEmpty() && track.durationMs > 0L) {
            val startIdx = ((fromMs.coerceAtLeast(0L).toDouble() / track.durationMs) * curve.size)
                .toInt().coerceIn(0, curve.lastIndex)
            val endIdx = ((toMs.coerceAtMost(track.durationMs).toDouble() / track.durationMs) * curve.size)
                .toInt().coerceIn(startIdx, curve.lastIndex)
            var sum = 0.0
            var n = 0
            for (i in startIdx..endIdx) {
                val v = curve[i]
                if (v.isFinite()) {
                    sum += v
                    n++
                }
            }
            if (n > 0) return sum / n
        }
        val overlapping = track.structure.sections.filter { it.endMs > fromMs && it.startMs < toMs && it.energy > 0f }
        if (overlapping.isEmpty()) return null
        return overlapping.map { it.energy.toDouble() }.average()
    }
}
