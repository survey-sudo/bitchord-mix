package com.music.bitchord.playback.djmix.store

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.ChorusFamilyV3
import com.music.bitchord.djmix.core.contracts.DropEventV3
import com.music.bitchord.djmix.core.contracts.PhraseBoundaryV3
import com.music.bitchord.djmix.core.contracts.SectionType
import com.music.bitchord.djmix.core.contracts.SongStructureV3
import com.music.bitchord.djmix.core.contracts.StructureSectionV3
import com.music.bitchord.djmix.core.contracts.TimeRangeMs
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.PhraseSection
import com.music.bitchord.playback.smart.SectionFn
import com.music.bitchord.playback.smart.SectionKind
import com.music.bitchord.playback.smart.SectionRole
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TrackAnalysisProvenance
import com.music.bitchord.playback.smart.structure.StructureResult
import kotlin.math.roundToLong

/**
 * Bidirectional mapper between the legacy in-memory analysis ([TrackAnalysis]
 * plus the ephemeral [PhraseMaps] / [SongMaps] caches, both of which hold a
 * [SongMap]) and the persisted AE3 contract ([TrackAnalysisV3], ADR 0009).
 *
 * There is no `PhraseMap` type in this codebase: `PhraseMaps` is the cache
 * object and its values are [SongMap], exactly like the `SongMaps` fallback
 * builder. [toV3] therefore takes the same [SongMap] for both cache slots and
 * folds whichever is present (preferring [songMap], then [phraseMap]).
 *
 * Lossy by design (documented, never silent):
 * - Mix candidates, per-region keys (`keyHead`/`keyTail`), low-energy curve,
 *   level windows, and `StructureResult` internals (chroma/timbre/nm) have no
 *   V3 field and are not persisted. [fromV3] restores empty/derived values.
 * - `integratedLufs` has no legacy source; it is the mean of the per-section
 *   LUFS when a map carries measured values, otherwise the -14 LUFS default.
 * - `shortTermLufsCurve` has no legacy source and is left empty.
 *
 * Provenance is not dropped: [fromV3] attaches the row's declared
 * `contentHash`/`modelVersions`/`schema` as a frozen
 * [com.music.bitchord.playback.smart.TrackAnalysisProvenance] so a later
 * store-row rewrite or model-registry change cannot re-label the instance.
 * Those values stay *declared* metadata: the app hash is a source/rendition
 * fingerprint (not a PCM digest) and model constants are not measured runtime
 * identity. [toV3] prefers the instance's frozen provenance. Reserved labelled
 * entries in the existing modelVersions map preserve its status/hash semantics
 * without changing the V3 contract. Unknown legacy declarations stay unknown.
 *
 * Pure and total: never throws on plausible inputs, never touches I/O.
 */
object TrackAnalysisV3Mapper {

    /** Song-map vocal gate mirrored from `SongMaps.VOCAL_BAR_GATE` (internal there). */
    private const val VOCAL_BAR_GATE = 0.22f

    /**
     * Folds [analysis] plus the [SongMap] held by the phrase/song caches into
     * a persisted [TrackAnalysisV3]. [trackId] wins when non-blank, then
     * `analysis.trackId`, then `songMap.trackKey`.
     */
    fun toV3(
        trackId: String,
        contentHash: String,
        analysis: TrackAnalysis,
        phraseMap: SongMap?,
        songMap: SongMap?,
        modelVersions: Map<String, String>,
    ): TrackAnalysisV3 {
        val map = songMap ?: phraseMap
        val resolvedTrackId = trackId.ifBlank { analysis.trackId.ifBlank { map?.trackKey.orEmpty() } }
        // Captured unknowns are authoritative too; explicit arguments cannot
        // backfill/relabel a restored instance using today's registry or source.
        val provenance = analysis.provenance?.detached()
            ?: TrackAnalysisProvenance.storeDeclared(contentHash, modelVersions, 3)
        val durationSec = when {
            analysis.duration > 0.0 -> analysis.duration
            map != null && map.durationSec > 0.0 -> map.durationSec
            else -> 0.0
        }
        val durationMs = (durationSec * 1000.0).roundToLong().coerceAtLeast(0L)
        val bpm = when {
            analysis.bpm > 0.0 -> analysis.bpm
            map != null && map.bpm > 0.0 -> map.bpm
            else -> 0.0
        }
        val gridConfidence = when {
            analysis.beatConfidence > 0.0 -> analysis.beatConfidence.toFloat()
            map != null -> map.gridConfidence
            else -> 0f
        }.coerceIn(0f, 1f)
        val key = analysis.key.ifBlank { analysis.keyHead }
        val keyConfidence = when {
            analysis.keyConfidence > 0.0 -> analysis.keyConfidence.toFloat()
            key == analysis.keyHead && analysis.keyHeadConfidence > 0.0 -> analysis.keyHeadConfidence.toFloat()
            else -> 0f
        }.coerceIn(0f, 1f)

        val downbeatsMs = toMsList(firstNonEmptySec(analysis.downbeats, map?.downbeats?.toList(), analysis.headBeats?.downbeats, analysis.tailBeats?.downbeats))
        val beatsMs = beatsMsOf(analysis, downbeatsMs, bpm)

        return TrackAnalysisV3(
            schema = provenance.declaredSchemaVersion?.takeIf { it == 3 } ?: 3,
            trackId = resolvedTrackId,
            contentHash = provenance.sourceHash.orEmpty(),
            durationMs = durationMs,
            analysisLevel = analysisLevelOf(analysis),
            bpm = bpm,
            gridConfidence = gridConfidence,
            beatsMs = beatsMs,
            downbeatsMs = downbeatsMs,
            keyCamelot = key.takeIf { it.isNotBlank() },
            keyConfidence = keyConfidence,
            integratedLufs = integratedLufsOf(map),
            shortTermLufsCurve = emptyList(),
            energyCurve = analysis.energyCurve.map { it.energy.toFloat() },
            vocalPresence50fps = analysis.vocalPresence50fps,
            structure = structureOf(analysis, map, downbeatsMs, bpm),
            silentIntroEndMs = analysis.audibleStartTime?.let { (it * 1000.0).roundToLong().coerceAtLeast(0L) } ?: 0L,
            audibleEndMs = ((analysis.contentEndTime.takeIf { it > 0.0 } ?: durationSec) * 1000.0).roundToLong()
                .coerceIn(0L, durationMs),
            modelVersions = provenance.toModelVersions(),
        )
    }

    /** Convenience overload for callers that only hold the folded [SongMap]. */
    fun toV3(
        trackId: String,
        contentHash: String,
        analysis: TrackAnalysis,
        songMap: SongMap?,
        modelVersions: Map<String, String>,
    ): TrackAnalysisV3 = toV3(trackId, contentHash, analysis, songMap, songMap, modelVersions)

    /** Project the exact instance's provenance; absent metadata remains explicitly unknown. */
    fun toV3(trackId: String, analysis: TrackAnalysis, songMap: SongMap?): TrackAnalysisV3 =
        toV3(trackId, "", analysis, songMap, songMap, emptyMap())

    /**
     * Restores a planner-compatible [TrackAnalysis] from a persisted [v3].
     * Fields with no V3 counterpart come back empty/derived (see class doc).
     *
     * The row's declared provenance rides along on the restored instance so the
     * frozen value cannot be replaced later by a different store row or by
     * currently installed model constants.
     */
    fun fromV3(v3: TrackAnalysisV3): TrackAnalysis {
        val durationSec = v3.durationMs / 1000.0
        val usable = v3.analysisLevel != AnalysisLevel.NONE && v3.bpm > 0.0
        val energyCurve = v3.energyCurve.mapIndexed { index, energy ->
            val time = if (v3.energyCurve.isEmpty()) 0.0 else durationSec * index / v3.energyCurve.size.toDouble()
            EnergySample(time = time, energy = energy.toDouble())
        }
        // vocalActivityMask is only read when its length matches energyCurve.
        val vocalActivityMask = if (energyCurve.isNotEmpty() && v3.vocalPresence50fps.isNotEmpty()) {
            energyCurve.map { sample ->
                val idx = (sample.time * 50.0).toInt().coerceIn(0, v3.vocalPresence50fps.lastIndex)
                v3.vocalPresence50fps[idx].toDouble()
            }
        } else {
            emptyList()
        }
        val vocalProbability = v3.vocalPresence50fps
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toDouble()
            ?: 0.0
        val (headBeats, tailBeats) = beatSidesOf(v3)

        return TrackAnalysis(
            status = if (usable) TrackAnalysis.STATUS_READY else "",
            trackId = v3.trackId,
            duration = durationSec,
            bpm = v3.bpm,
            beatInterval = if (v3.bpm > 0.0) 60.0 / v3.bpm else 0.0,
            beatConfidence = v3.gridConfidence.toDouble(),
            downbeats = v3.downbeatsMs.map { it / 1000.0 },
            phraseBoundaries = v3.structure.phrases.map { it.timeMs / 1000.0 },
            firstBeat = v3.beatsMs.firstOrNull()?.let { it / 1000.0 } ?: v3.downbeatsMs.firstOrNull()?.let { it / 1000.0 } ?: 0.0,
            key = v3.keyCamelot.orEmpty(),
            keyConfidence = v3.keyConfidence.toDouble(),
            audibleStartTime = v3.silentIntroEndMs.takeIf { it > 0L }?.let { it / 1000.0 },
            pickupTime = null,
            introEndTime = v3.structure.sections.firstOrNull { it.type == SectionType.INTRO }?.let { it.endMs / 1000.0 } ?: 0.0,
            contentEndTime = v3.audibleEndMs / 1000.0,
            outroStartTime = v3.structure.sections.lastOrNull { it.type == SectionType.OUTRO || it.type == SectionType.FADE_OUT }?.let { it.startMs / 1000.0 } ?: 0.0,
            mixInTime = 0.0,
            mixOutTime = 0.0,
            mixInCandidates = emptyList(),
            mixOutCandidates = emptyList(),
            energyCurve = energyCurve,
            lowEnergyCurve = emptyList(),
            vocalActivityMask = vocalActivityMask,
            vocalProbability = vocalProbability,
            headBeats = headBeats,
            tailBeats = tailBeats,
            vocalWindows = if (v3.vocalPresence50fps.isNotEmpty()) {
                listOf(CurveWindow(startTime = 0.0, frameRate = 50.0, values = v3.vocalPresence50fps.toFloatArray()))
            } else {
                emptyList()
            },
            levelWindows = emptyList(),
            quality = when (v3.analysisLevel) {
                AnalysisLevel.FULL -> TrackAnalysis.QUALITY_FULL
                AnalysisLevel.HEAD_TAIL -> TrackAnalysis.QUALITY_HEAD
                AnalysisLevel.NONE -> TrackAnalysis.QUALITY_NONE
            },
            keyHead = "",
            keyTail = "",
            keyHeadConfidence = 0.0,
            keyTailConfidence = 0.0,
            instrumentalWindows = emptyList(),
            vocalPresence50fps = v3.vocalPresence50fps,
            provenance = TrackAnalysisProvenance.storeDeclared(
                contentHash = v3.contentHash,
                modelVersions = v3.modelVersions,
                schemaVersion = v3.schema,
            ),
        )
    }

    /**
     * Rebuilds the [SongMap] legacy callers read from `PhraseMaps` from [v3].
     * Returns null only when there is nothing to rebuild (no duration/sections).
     */
    fun toPhraseMap(v3: TrackAnalysisV3): SongMap? {
        if (v3.durationMs <= 0L && v3.structure.sections.isEmpty()) return null
        val durationSec = v3.durationMs / 1000.0
        val barSec = if (v3.bpm > 1.0) 4.0 * 60.0 / v3.bpm else 0.0
        val barCount = if (barSec > 0.0) {
            (durationSec / barSec).toInt().coerceIn(0, 512)
        } else {
            0
        }
        val sections = v3.structure.sections.map { section ->
            val fn = sectionFnOf(section.type)
            PhraseSection(
                startBar = section.startBar,
                endBar = section.endBar.coerceAtLeast(section.startBar + 1),
                fn = fn,
                role = roleOf(fn),
                conf = section.confidence,
                group = section.familyId,
                repeatIndex = section.repeatIndex,
                energy = section.energy,
                energyLevel = (section.energy * 10f).roundToLong().toInt().coerceIn(0, 10),
                energyRel = 0f,
                energySlope = 0f,
                vocal = section.vocal,
                bass = section.bass,
                loopable = section.loopable,
            )
        }
        val (sungBars, vocalEnergy) = vocalBarsOf(v3, barSec, barCount)
        val firstVocalBar = sungBars.indexOfFirst { it }.takeIf { it >= 0 }
        val lastVocalBar = sungBars.indexOfLast { it }.takeIf { it >= 0 }
        return SongMap(
            trackKey = v3.trackId,
            durationSec = durationSec,
            bpm = v3.bpm,
            beatsPerBar = 4,
            downbeats = v3.downbeatsMs.map { it / 1000.0 }.toDoubleArray(),
            gridConfidence = v3.gridConfidence,
            tempoStable = v3.gridConfidence >= 0.45f,
            phraseAnchor = v3.structure.phraseAnchorBar,
            phraseLen = v3.structure.phraseLenBars.takeIf { it > 0 } ?: 8,
            anchors = v3.structure.phrases.map { it.barIndex }.distinct().sorted().toIntArray(),
            sections = sections,
            sungBars = sungBars,
            vocalEnergy = vocalEnergy,
            firstVocalBar = firstVocalBar,
            lastVocalBar = lastVocalBar,
            pickupBeats = if (firstVocalBar == 1) 1f else 0f,
            startKind = when {
                firstVocalBar == null -> com.music.bitchord.playback.smart.StartKind.INTRO_INSTRUMENTAL
                firstVocalBar == 0 -> com.music.bitchord.playback.smart.StartKind.COLD_VOCAL
                firstVocalBar == 1 -> com.music.bitchord.playback.smart.StartKind.VOCAL_PICKUP
                else -> com.music.bitchord.playback.smart.StartKind.INTRO_INSTRUMENTAL
            },
            endingKind = if (v3.structure.sections.lastOrNull()?.type == SectionType.OUTRO ||
                v3.structure.sections.lastOrNull()?.type == SectionType.FADE_OUT
            ) {
                com.music.bitchord.playback.smart.EndingKind.OUTRO_INSTRUMENTAL
            } else {
                com.music.bitchord.playback.smart.EndingKind.UNKNOWN
            },
            contentEndSec = v3.audibleEndMs / 1000.0,
            labelConfidence = v3.structure.labelConfidence,
            structMs = 0,
            partial = v3.analysisLevel == AnalysisLevel.HEAD_TAIL,
            coarse = v3.gridConfidence < 0.45f,
            activation = 1f,
            speechLike = false,
            drops = v3.structure.drops.map { it.timeMs / 1000.0 },
            builds = v3.structure.builds.map { it.startMs / 1000.0..it.endMs / 1000.0 },
            breakdowns = v3.structure.breakdowns.map { it.startMs / 1000.0..it.endMs / 1000.0 },
            chorusRepeats = v3.structure.chorusFamilies.map { it.occurrenceStartMs.map { ms -> ms / 1000.0 } },
            phraseConfidence = v3.structure.phraseConfidence.toDouble(),
            structureVersion = 1,
            structure = null,
        )
    }

    /** Alias kept for callers that think in `SongMaps` terms. */
    fun toSongMap(v3: TrackAnalysisV3): SongMap? = toPhraseMap(v3)

    // ---- helpers -----------------------------------------------------------

    private fun analysisLevelOf(analysis: TrackAnalysis): AnalysisLevel = when (analysis.quality) {
        TrackAnalysis.QUALITY_FULL -> AnalysisLevel.FULL
        TrackAnalysis.QUALITY_HEAD -> AnalysisLevel.HEAD_TAIL
        else -> if (analysis.status == TrackAnalysis.STATUS_READY && analysis.bpm > 0.0) {
            AnalysisLevel.HEAD_TAIL
        } else {
            AnalysisLevel.NONE
        }
    }

    private fun firstNonEmptySec(vararg lists: List<Double>?): List<Double> {
        for (list in lists) {
            if (!list.isNullOrEmpty()) return list.filter { it.isFinite() && it >= 0.0 }
        }
        return emptyList()
    }

    private fun toMsList(seconds: List<Double>): List<Long> =
        seconds.map { (it * 1000.0).roundToLong().coerceAtLeast(0L) }.distinct().sorted()

    /**
     * Legacy analysis keeps real beats only on the head/tail sides; the flat
     * list is their union. With no sides, expand each downbeat into its four
     * beats at the measured tempo so [TrackAnalysisV3.isUsable] can hold;
     * with no tempo either, fall back to the downbeats themselves.
     */
    private fun beatsMsOf(analysis: TrackAnalysis, downbeatsMs: List<Long>, bpm: Double): List<Long> {
        val sideBeats = (analysis.headBeats?.beats.orEmpty() + analysis.tailBeats?.beats.orEmpty())
            .filter { it.isFinite() && it >= 0.0 }
        if (sideBeats.isNotEmpty()) return toMsList(sideBeats)
        if (downbeatsMs.isEmpty()) return emptyList()
        if (bpm <= 1.0) return downbeatsMs
        val beatMs = (60_000.0 / bpm).roundToLong().coerceAtLeast(250L)
        val out = ArrayList<Long>()
        for ((index, downbeat) in downbeatsMs.withIndex()) {
            val next = downbeatsMs.getOrNull(index + 1)
            for (step in 0..3) {
                val at = downbeat + step * beatMs
                if (next == null || at < next) out += at
            }
        }
        return out.distinct().sorted()
    }

    private fun integratedLufsOf(map: SongMap?): Float {
        if (map == null) return -14f
        val measured = map.sections.map { it.lufs }.filter { it.isFinite() && it != -14f }
        return if (measured.isEmpty()) -14f else measured.average().toFloat()
    }

    private fun structureOf(
        analysis: TrackAnalysis,
        map: SongMap?,
        downbeatsMs: List<Long>,
        bpm: Double,
    ): SongStructureV3 {
        val v2: StructureResult? = map?.structure
        val sections = sectionsOf(map, v2)
        val phrases = phrasesOf(analysis, map, v2, downbeatsMs, bpm)
        val builds = rangesOf(map?.builds, v2?.builds?.map { barSpanToRange(it, v2) })
        val breakdowns = rangesOf(map?.breakdowns, v2?.breakdowns?.map { barSpanToRange(it, v2) })
        val drops = dropsOf(map, v2, downbeatsMs)
        val chorusFamilies = chorusFamiliesOf(map, v2)
        return SongStructureV3(
            sections = sections,
            phrases = phrases,
            builds = builds,
            drops = drops,
            breakdowns = breakdowns,
            chorusFamilies = chorusFamilies,
            phraseLenBars = map?.phraseLen?.takeIf { it > 0 } ?: 8,
            phraseAnchorBar = map?.phraseAnchor ?: 0,
            phraseConfidence = when {
                map != null && map.phraseConfidence > 0.0 -> map.phraseConfidence.toFloat()
                v2 != null -> v2.phraseConfidence.toFloat()
                else -> 0f
            }.coerceIn(0f, 1f),
            labelConfidence = when {
                map != null && map.labelConfidence > 0f -> map.labelConfidence
                v2 != null -> v2.labelConfidence.toFloat()
                else -> 0f
            }.coerceIn(0f, 1f),
            mode = v2?.mode?.name.orEmpty(),
            mood = v2?.mood?.name.orEmpty(),
        )
    }

    private fun sectionsOf(map: SongMap?, v2: StructureResult?): List<StructureSectionV3> {
        if (map != null && map.sections.isNotEmpty()) {
            return map.sections.map { section ->
                val startMs = (map.barTime(section.startBar) * 1000.0).roundToLong().coerceAtLeast(0L)
                val endMs = (map.barTime(section.endBar) * 1000.0).roundToLong().coerceAtLeast(startMs)
                StructureSectionV3(
                    startMs = startMs,
                    endMs = endMs,
                    startBar = section.startBar,
                    endBar = section.endBar,
                    type = sectionTypeOf(section.fn),
                    confidence = section.conf,
                    familyId = section.group,
                    repeatIndex = section.repeatIndex,
                    energy = section.energy,
                    vocal = section.vocal,
                    bass = section.bass,
                    loopable = section.loopable,
                )
            }
        }
        if (v2 != null && v2.sections.isNotEmpty()) {
            return v2.sections.map { section ->
                val startMs = (v2.barTime(section.startBar) * 1000.0).roundToLong().coerceAtLeast(0L)
                val endMs = (v2.barTime(section.endBar) * 1000.0).roundToLong().coerceAtLeast(startMs)
                StructureSectionV3(
                    startMs = startMs,
                    endMs = endMs,
                    startBar = section.startBar,
                    endBar = section.endBar,
                    type = sectionTypeOf(section.kind),
                    confidence = section.conf,
                    familyId = section.family,
                    repeatIndex = section.repeatIndex,
                    energy = 0f,
                    vocal = 0f,
                    bass = 0f,
                    loopable = false,
                )
            }
        }
        return emptyList()
    }

    private fun phrasesOf(
        analysis: TrackAnalysis,
        map: SongMap?,
        v2: StructureResult?,
        downbeatsMs: List<Long>,
        bpm: Double,
    ): List<PhraseBoundaryV3> {
        val out = ArrayList<PhraseBoundaryV3>()
        if (v2 != null && v2.boundaries.isNotEmpty()) {
            val reanchors = v2.reanchors.toSet()
            for ((index, bar) in v2.boundaries.withIndex()) {
                val timeMs = (v2.barTime(bar) * 1000.0).roundToLong().coerceAtLeast(0L)
                out += PhraseBoundaryV3(
                    timeMs = timeMs,
                    barIndex = bar,
                    confidence = if (index in v2.boundaryConfidence.indices) v2.boundaryConfidence[index] else 0f,
                    isReanchor = bar in reanchors,
                )
            }
        }
        if (map != null && map.anchors.isNotEmpty()) {
            for (bar in map.anchors) {
                val timeMs = (map.barTime(bar) * 1000.0).roundToLong().coerceAtLeast(0L)
                out += PhraseBoundaryV3(
                    timeMs = timeMs,
                    barIndex = bar,
                    confidence = map.phraseConfidence.toFloat().coerceIn(0f, 1f),
                    isReanchor = false,
                )
            }
        }
        if (analysis.phraseBoundaries.isNotEmpty()) {
            val barMs = if (bpm > 1.0) (240_000.0 / bpm).roundToLong() else 0L
            for (sec in analysis.phraseBoundaries) {
                if (!sec.isFinite() || sec < 0.0) continue
                val timeMs = (sec * 1000.0).roundToLong()
                val barIndex = if (barMs > 0L) (timeMs / barMs).toInt() else downbeatsMs.indexOfFirst { it > timeMs }.takeIf { it >= 0 } ?: 0
                out += PhraseBoundaryV3(timeMs = timeMs, barIndex = barIndex, confidence = 0f, isReanchor = false)
            }
        }
        // Structure-v2 boundaries win on a time clash; first occurrence kept.
        return out.distinctBy { it.timeMs }.sortedBy { it.timeMs }
    }

    private fun rangesOf(
        mapRanges: List<ClosedFloatingPointRange<Double>>?,
        v2Ranges: List<TimeRangeMs>?,
    ): List<TimeRangeMs> {
        val fromMap = mapRanges.orEmpty().mapNotNull { range ->
            val startMs = (range.start * 1000.0).roundToLong().coerceAtLeast(0L)
            val endMs = (range.endInclusive * 1000.0).roundToLong().coerceAtLeast(startMs)
            TimeRangeMs(startMs = startMs, endMs = endMs)
        }
        val merged = (fromMap + v2Ranges.orEmpty()).distinct().sortedBy { it.startMs }
        return merged
    }

    private fun dropsOf(map: SongMap?, v2: StructureResult?, downbeatsMs: List<Long>): List<DropEventV3> {
        val out = ArrayList<DropEventV3>()
        for (sec in map?.drops.orEmpty()) {
            if (!sec.isFinite() || sec < 0.0) continue
            val timeMs = (sec * 1000.0).roundToLong()
            val barIndex = downbeatsMs.indexOfFirst { it > timeMs }.takeIf { it >= 0 } ?: downbeatsMs.size
            out += DropEventV3(timeMs = timeMs, barIndex = barIndex, strength = 1f)
        }
        if (v2 != null) {
            for (drop in v2.drops) {
                val timeMs = (v2.barTime(drop.bar) * 1000.0).roundToLong().coerceAtLeast(0L)
                out += DropEventV3(timeMs = timeMs, barIndex = drop.bar, strength = drop.strength)
            }
        }
        return out.distinctBy { it.timeMs }.sortedBy { it.timeMs }
    }

    private fun chorusFamiliesOf(map: SongMap?, v2: StructureResult?): List<ChorusFamilyV3> {
        if (map != null && map.chorusRepeats.isNotEmpty()) {
            return map.chorusRepeats.mapIndexed { familyId, starts ->
                ChorusFamilyV3(
                    familyId = familyId,
                    occurrenceStartMs = starts.filter { it.isFinite() && it >= 0.0 }
                        .map { (it * 1000.0).roundToLong() }.sorted(),
                )
            }
        }
        if (v2 != null && v2.families.isNotEmpty()) {
            return v2.families.mapIndexed { familyId, bars ->
                ChorusFamilyV3(
                    familyId = familyId,
                    occurrenceStartMs = bars.map { (v2.barTime(it) * 1000.0).roundToLong().coerceAtLeast(0L) }.sorted(),
                )
            }
        }
        return emptyList()
    }

    private fun barSpanToRange(span: com.music.bitchord.playback.smart.structure.BarSpan, v2: StructureResult): TimeRangeMs {
        val startMs = (v2.barTime(span.startBar) * 1000.0).roundToLong().coerceAtLeast(0L)
        val endMs = (v2.barTime(span.endBar) * 1000.0).roundToLong().coerceAtLeast(startMs)
        return TimeRangeMs(startMs = startMs, endMs = endMs)
    }

    private fun beatSidesOf(v3: TrackAnalysisV3): Pair<com.music.bitchord.playback.smart.BeatSide?, com.music.bitchord.playback.smart.BeatSide?> {
        if (v3.beatsMs.isEmpty()) return null to null
        val midMs = v3.durationMs / 2L
        fun side(beats: List<Long>, downs: List<Long>) =
            if (beats.isEmpty()) {
                null
            } else {
                com.music.bitchord.playback.smart.BeatSide(
                    beats = beats.map { it / 1000.0 },
                    downbeats = downs.map { it / 1000.0 },
                    bpm = v3.bpm,
                    confidence = v3.gridConfidence.toDouble(),
                )
            }
        val headBeats = v3.beatsMs.filter { it < midMs }
        val tailBeats = v3.beatsMs.filter { it >= midMs }
        val headDowns = v3.downbeatsMs.filter { it < midMs }
        val tailDowns = v3.downbeatsMs.filter { it >= midMs }
        return side(headBeats, headDowns) to side(tailBeats, tailDowns)
    }

    private fun vocalBarsOf(v3: TrackAnalysisV3, barSec: Double, barCount: Int): Pair<BooleanArray, FloatArray> {
        if (barCount <= 0 || barSec <= 0.0) return BooleanArray(0) to FloatArray(0)
        val sung = BooleanArray(barCount)
        val energy = FloatArray(barCount)
        val framesPerBar = (barSec * 50.0).roundToLong().toInt().coerceAtLeast(1)
        for (bar in 0 until barCount) {
            val from = bar * framesPerBar
            val until = minOf(from + framesPerBar, v3.vocalPresence50fps.size)
            if (until <= from) continue
            var sum = 0f
            for (i in from until until) {
                val v = v3.vocalPresence50fps[i]
                if (v.isFinite()) sum += v.coerceIn(0f, 1f)
            }
            val mean = sum / (until - from)
            energy[bar] = mean
            sung[bar] = mean > VOCAL_BAR_GATE
        }
        return sung to energy
    }

    private fun sectionTypeOf(fn: SectionFn): SectionType = when (fn) {
        SectionFn.INTRO -> SectionType.INTRO
        SectionFn.VERSE -> SectionType.VERSE
        SectionFn.PRE_CHORUS -> SectionType.PRE_CHORUS
        SectionFn.CHORUS -> SectionType.CHORUS
        SectionFn.POST_CHORUS -> SectionType.POST_CHORUS
        SectionFn.BRIDGE -> SectionType.BRIDGE
        SectionFn.BREAK -> SectionType.BREAKDOWN
        SectionFn.BUILD -> SectionType.BUILD
        SectionFn.DROP -> SectionType.DROP
        SectionFn.INSTRUMENTAL -> SectionType.INSTRUMENTAL
        SectionFn.OUTRO -> SectionType.OUTRO
        SectionFn.FADE_OUT -> SectionType.FADE_OUT
        SectionFn.SILENCE -> SectionType.SILENCE
    }

    private fun sectionTypeOf(kind: SectionKind): SectionType = when (kind) {
        SectionKind.INTRO -> SectionType.INTRO
        SectionKind.VERSE -> SectionType.VERSE
        SectionKind.PRE_CHORUS -> SectionType.PRE_CHORUS
        SectionKind.CHORUS -> SectionType.CHORUS
        SectionKind.BRIDGE -> SectionType.BRIDGE
        SectionKind.INST -> SectionType.INSTRUMENTAL
        SectionKind.OUTRO -> SectionType.OUTRO
        SectionKind.BUILD -> SectionType.BUILD
        SectionKind.DROP -> SectionType.DROP
        SectionKind.BREAKDOWN -> SectionType.BREAKDOWN
        SectionKind.HOOK -> SectionType.HOOK
    }

    private fun sectionFnOf(type: SectionType): SectionFn = when (type) {
        SectionType.INTRO -> SectionFn.INTRO
        SectionType.VERSE -> SectionFn.VERSE
        SectionType.PRE_CHORUS -> SectionFn.PRE_CHORUS
        SectionType.CHORUS -> SectionFn.CHORUS
        SectionType.POST_CHORUS -> SectionFn.POST_CHORUS
        SectionType.HOOK -> SectionFn.CHORUS
        SectionType.BRIDGE -> SectionFn.BRIDGE
        SectionType.BREAKDOWN -> SectionFn.BREAK
        SectionType.BUILD -> SectionFn.BUILD
        SectionType.DROP -> SectionFn.DROP
        SectionType.INSTRUMENTAL -> SectionFn.INSTRUMENTAL
        SectionType.OUTRO -> SectionFn.OUTRO
        SectionType.FADE_OUT -> SectionFn.FADE_OUT
        SectionType.SILENCE -> SectionFn.SILENCE
    }

    private fun roleOf(fn: SectionFn): SectionRole = when (fn) {
        SectionFn.CHORUS, SectionFn.POST_CHORUS, SectionFn.DROP -> SectionRole.RELEASE
        SectionFn.PRE_CHORUS, SectionFn.BUILD -> SectionRole.TENSION
        SectionFn.VERSE, SectionFn.BRIDGE -> SectionRole.NARRATIVE
        else -> SectionRole.GROOVE
    }
}
