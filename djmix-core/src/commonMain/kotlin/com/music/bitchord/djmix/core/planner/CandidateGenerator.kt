package com.music.bitchord.djmix.core.planner

import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.RecipeFxType
import com.music.bitchord.djmix.core.contracts.SectionType
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Concrete geometric candidate generated across `LOOP`, `BRIDGE`, and `STRAIGHT`
 * arrangements prior to placement validation and scoring.
 */
data class TransitionCandidate(
    val exitMs: Long,
    val entryMs: Long,
    val swapMs: Long,
    val overlapBars: Int,
    val overlapDurationMs: Long,
    val barDurationMs: Long,
    val geometry: TransitionGeometry,
    val exitReasonTag: String = "",
    val entryReasonTag: String = "",
    val isDropAligned: Boolean = false,
)

/** Exit/entry cue anchor paired with its placement tag and optional drop alignment target. */
private data class CueAnchor(
    val timeMs: Long,
    val tag: String,
    val dropMs: Long? = null,
    val preRollBars: Int? = null,
)

/**
 * Generates geometric candidates `(exitMs, entryMs, swapMs, overlapBars, geometry)` across
 * `LOOP`, `BRIDGE`, and `STRAIGHT` geometries while respecting `minExitMs` and `positionMs` +
 * commit-lead margin.
 */
internal object CandidateGenerator {

    /**
     * Collects valid phrase-boundary downbeats on [track] at or after [earliestMs] and before
     * [safeEndMs]. Falls back to synthetic `phraseBars`-strided downbeats when explicit phrase
     * markers are absent.
     */
    fun phraseDownbeats(
        track: TrackAnalysisV3,
        earliestMs: Long,
        safeEndMs: Long,
        barMs: Long,
    ): List<Long> {
        val phraseBars = TrackSignalOps.phraseBars(track)
        val raw = ArrayList<Long>()
        for (phrase in track.structure.phrases) {
            val snapped = TrackSignalOps.snapToDownbeat(track, phrase.timeMs, barMs)
            if (snapped in earliestMs until safeEndMs) raw += snapped
        }
        for (section in track.structure.sections) {
            if (section.type == SectionType.SILENCE) continue
            val startSnapped = TrackSignalOps.snapToDownbeat(track, section.startMs, barMs)
            if (startSnapped in earliestMs until safeEndMs) raw += startSnapped
            if (section.type == SectionType.CHORUS || section.type == SectionType.DROP ||
                section.type == SectionType.BREAKDOWN
            ) {
                val endSnapped = TrackSignalOps.snapToDownbeat(track, section.endMs, barMs)
                if (endSnapped in earliestMs until safeEndMs) raw += endSnapped
            }
        }
        val downs = TrackSignalOps.downbeatList(track, barMs)
        val anchor = track.structure.phraseAnchorBar.coerceAtLeast(0)
        for (i in downs.indices) {
            if (((i - anchor) % phraseBars + phraseBars) % phraseBars == 0) {
                val t = downs[i]
                if (t in earliestMs until safeEndMs) raw += t
            }
        }
        val distinct = raw.distinct().sorted()
        if (distinct.isNotEmpty()) return distinct
        return downs.filter { it in earliestMs until safeEndMs }
    }

    /**
     * Finds the last phrase downbeat (or last bar downbeat inside the final phrase if the
     * playhead already passed the last phrase start) strictly after [afterMs] and leaving at
     * least [minTailMs] before [safeEndMs]. Never returns [afterMs] itself.
     */
    fun lastPhraseDownbeatAfter(
        track: TrackAnalysisV3,
        afterMs: Long,
        safeEndMs: Long,
        barMs: Long,
    ): Long? {
        val minTailMs = (barMs / 2L).coerceIn(500L, 1_500L)
        val maxStartMs = safeEndMs - minTailMs
        if (maxStartMs <= afterMs) return null
        val phrases = phraseDownbeats(track, afterMs + 1L, maxStartMs + 1L, barMs)
        if (phrases.isNotEmpty()) return phrases.last()
        val downs = TrackSignalOps.downbeatList(track, barMs)
        return downs.lastOrNull { it > afterMs && it <= maxStartMs }
    }

    /**
     * Generates candidates for one [recipe] under [request], across `LOOP`, `BRIDGE`, and
     * `STRAIGHT` geometries.
     */
    fun generateForRecipe(
        request: PlannerRequest,
        recipe: RecipeCatalogEntry,
        outBarMs: Long,
        inBarMs: Long,
        tempoDeltaPct: Double,
    ): List<TransitionCandidate> {
        val outTrack = request.outTrack
        val inTrack = request.inTrack
        val outSafeEnd = TrackSignalOps.safeOutEndMs(outTrack)
        val inSafeEnd = TrackSignalOps.safeInEndMs(inTrack)
        val commitLeadMs = TrackSignalOps.commitLeadMs(outBarMs)
        val earliestExitMs = max(request.positionMs + commitLeadMs + 1L, request.minExitMs ?: 0L)
        if (earliestExitMs >= outSafeEnd) return emptyList()

        val exitAnchors = collectExitAnchors(outTrack, earliestExitMs, outSafeEnd, outBarMs, recipe)
        if (exitAnchors.isEmpty()) return emptyList()

        val entryAnchors = collectEntryAnchors(
            inTrack = inTrack,
            inBarMs = inBarMs,
            inSafeEnd = inSafeEnd,
            djLevel = request.djLevel,
            recipe = recipe,
        )
        if (entryAnchors.isEmpty()) return emptyList()

        val geometries = selectGeometries(request, recipe, tempoDeltaPct)
        val candidates = ArrayList<TransitionCandidate>(exitAnchors.size * entryAnchors.size * 2)

        for (exit in exitAnchors) {
            for (entry in entryAnchors) {
                val barOptions = overlapBarOptions(recipe, entry.preRollBars, outSafeEnd - exit.timeMs, outBarMs)
                for (bars in barOptions) {
                    val overlapMs = bars * outBarMs
                    val swapMs = computeSwapMs(recipe, exit.timeMs, bars, overlapMs, outBarMs, entry.preRollBars)
                    for (geometry in geometries) {
                        if (geometry == TransitionGeometry.LOOP && !isLoopEligible(outTrack, exit.timeMs, recipe)) {
                            continue
                        }
                        candidates += TransitionCandidate(
                            exitMs = exit.timeMs,
                            entryMs = entry.timeMs,
                            swapMs = swapMs,
                            overlapBars = bars,
                            overlapDurationMs = overlapMs,
                            barDurationMs = outBarMs,
                            geometry = geometry,
                            exitReasonTag = exit.tag,
                            entryReasonTag = entry.tag,
                            isDropAligned = entry.dropMs != null,
                        )
                    }
                }
            }
        }
        return candidates
    }

    private fun collectExitAnchors(
        outTrack: TrackAnalysisV3,
        earliestExitMs: Long,
        outSafeEnd: Long,
        outBarMs: Long,
        recipe: RecipeCatalogEntry,
    ): List<CueAnchor> {
        val phrases = phraseDownbeats(outTrack, earliestExitMs, outSafeEnd, outBarMs)
        val anchors = if (phrases.isNotEmpty()) phrases else {
            TrackSignalOps.downbeatList(outTrack, outBarMs).filter { it in earliestExitMs until outSafeEnd }
        }
        val lastChorusOrDropEnd = outTrack.structure.sections
            .filter {
                it.type == SectionType.CHORUS || it.type == SectionType.POST_CHORUS ||
                    it.type == SectionType.HOOK || it.type == SectionType.DROP
            }
            .maxOfOrNull { it.endMs } ?: 0L
        val outro = outTrack.structure.sections.firstOrNull { it.type == SectionType.OUTRO }
        val phraseBars = TrackSignalOps.phraseBars(outTrack)
        val phraseMs = phraseBars * outBarMs

        return anchors.take(24).map { t ->
            val sec = TrackSignalOps.sectionAt(outTrack, t)
            val inOutroAfterChorus = outro != null && t >= outro.startMs && t < outro.endMs && t >= lastChorusOrDropEnd
            val atDrop = recipe.requiresOutDrop && outTrack.structure.drops.any { abs(it.timeMs - t) <= outBarMs }
            val atBreakdownEnd = outTrack.structure.breakdowns.any { abs(it.endMs - t) <= outBarMs / 2L }
            val nextPhraseMaxVocal = TrackSignalOps.maxVocalInWindow(outTrack, t, t + phraseMs)
            val tag = when {
                atDrop -> "Exit on outgoing drop"
                inOutroAfterChorus && lastChorusOrDropEnd > 0L -> "Exit after final chorus"
                sec?.type == SectionType.OUTRO || sec?.type == SectionType.FADE_OUT -> "Exit in outro"
                atBreakdownEnd -> "Exit at breakdown end"
                nextPhraseMaxVocal != null && nextPhraseMaxVocal < TrackSignalOps.VOCAL_SILENT_THRESHOLD ->
                    "Exit on vocal-free phrase"
                else -> "Exit on phrase downbeat"
            }
            CueAnchor(t, tag)
        }
    }

    private fun collectEntryAnchors(
        inTrack: TrackAnalysisV3,
        inBarMs: Long,
        inSafeEnd: Long,
        djLevel: DjLevel,
        recipe: RecipeCatalogEntry,
    ): List<CueAnchor> {
        val anchors = ArrayList<CueAnchor>()
        val introFloor = inTrack.silentIntroEndMs.coerceAtLeast(0L)
        val firstIntroDownbeat = TrackSignalOps.firstDownbeatAtOrAfter(inTrack, introFloor, inBarMs)

        if (recipe.isDropRecipe && djLevel != DjLevel.SUBTLE) {
            val maxCapBars = if (djLevel == DjLevel.CLUB) 32 else 64
            val levelCapBars = min(maxCapBars, recipe.maxPreRollBars)
            for (drop in inTrack.structure.drops.sortedBy { it.timeMs }) {
                if (drop.timeMs < firstIntroDownbeat || drop.timeMs >= inSafeEnd) continue
                val introToDropBars = ((drop.timeMs - firstIntroDownbeat).toDouble() / inBarMs).roundToInt()
                if (introToDropBars > maxCapBars) continue
                val preRollCandidates = listOf(recipe.defaultOverlapBars, 8, 4, introToDropBars)
                    .distinct()
                    .filter { it in 2..levelCapBars && it <= introToDropBars }
                for (preRollBars in preRollCandidates) {
                    val rawEntry = (drop.timeMs - preRollBars * inBarMs).coerceAtLeast(introFloor)
                    val entryMs = TrackSignalOps.snapToDownbeat(inTrack, rawEntry, inBarMs)
                        .coerceAtLeast(firstIntroDownbeat)
                    val actualPreRollBars = ((drop.timeMs - entryMs).toDouble() / inBarMs).roundToInt()
                    if (entryMs < drop.timeMs && actualPreRollBars in 1..maxCapBars) {
                        anchors += CueAnchor(
                            timeMs = entryMs,
                            tag = "Entry aligned to drop",
                            dropMs = drop.timeMs,
                            preRollBars = actualPreRollBars,
                        )
                    }
                }
            }
            if (recipe.requiresInDrop) return anchors.distinctBy { it.timeMs to it.dropMs }
        }

        if (firstIntroDownbeat < inSafeEnd) {
            val tag = if (introFloor > 0L) "Entry at intro bar 1 (post-silence)" else "Entry at intro bar 1"
            anchors += CueAnchor(firstIntroDownbeat, tag)
        }

        val firstVocalMs = TrackSignalOps.firstVocalEntryMs(inTrack, firstIntroDownbeat)
        if (firstVocalMs != null) {
            val preVocalPhrase = phraseDownbeats(inTrack, firstIntroDownbeat, firstVocalMs + 1L, inBarMs)
                .lastOrNull { it <= firstVocalMs && (it - firstIntroDownbeat) <= 16L * inBarMs }
            if (preVocalPhrase != null && preVocalPhrase != firstIntroDownbeat && preVocalPhrase < inSafeEnd) {
                anchors += CueAnchor(preVocalPhrase, "Entry on phrase before vocal")
            }
        }

        if (recipe.preferBreakdownExit || recipe.id == "S6") {
            for (bd in inTrack.structure.breakdowns) {
                val bdStart = TrackSignalOps.snapToDownbeat(inTrack, bd.startMs, inBarMs)
                if (bdStart >= firstIntroDownbeat && bdStart < inSafeEnd &&
                    (bdStart - firstIntroDownbeat) <= 32L * inBarMs
                ) {
                    anchors += CueAnchor(bdStart, "Entry at incoming breakdown")
                }
            }
        }

        return anchors.distinctBy { it.timeMs to it.dropMs }
    }

    private fun selectGeometries(
        request: PlannerRequest,
        recipe: RecipeCatalogEntry,
        tempoDeltaPct: Double,
    ): List<TransitionGeometry> {
        val outTrack = request.outTrack
        val inTrack = request.inTrack
        val healthyGrid = min(outTrack.gridConfidence, inTrack.gridConfidence) >= TrackSignalOps.LOW_GRID_CONFIDENCE_THRESHOLD
        val tempoFitsLevel = tempoDeltaPct <= request.djLevel.maxStretch * 100.0
        val tempoFitsRecipe = tempoDeltaPct <= recipe.maxTempoDeltaPct
        val canBeatMatch = recipe.requiresBeatMatch && healthyGrid && tempoFitsLevel && tempoFitsRecipe

        if (recipe.requiresOutLoop) {
            return if (canBeatMatch) listOf(TransitionGeometry.LOOP) else emptyList()
        }
        return if (canBeatMatch) {
            if (recipe.isCut) {
                buildList {
                    add(TransitionGeometry.STRAIGHT)
                    if (RecipeFxType.LOOP in recipe.fxTags) add(TransitionGeometry.LOOP)
                }
            } else {
                listOf(TransitionGeometry.BRIDGE, TransitionGeometry.LOOP)
            }
        } else {
            listOf(TransitionGeometry.STRAIGHT)
        }
    }

    private fun isLoopEligible(track: TrackAnalysisV3, exitMs: Long, recipe: RecipeCatalogEntry): Boolean {
        if (track.gridConfidence < TrackSignalOps.LOW_GRID_CONFIDENCE_THRESHOLD) return false
        val sec = TrackSignalOps.sectionAt(track, exitMs)
        if (sec?.loopable == true) return true
        val vocal = TrackSignalOps.maxVocalInWindow(track, exitMs, exitMs + 2_000L)
        return (vocal == null || vocal <= TrackSignalOps.VOCAL_SILENT_THRESHOLD) &&
            (recipe.requiresOutLoop || sec?.type == SectionType.OUTRO || sec?.type == SectionType.INSTRUMENTAL)
    }

    private fun overlapBarOptions(
        recipe: RecipeCatalogEntry,
        dropPreRollBars: Int?,
        remainingOutMs: Long,
        outBarMs: Long,
    ): List<Int> {
        val maxFittingBars = (remainingOutMs / outBarMs).toInt().coerceAtLeast(1)
        val raw = buildList {
            if (dropPreRollBars != null) {
                add(dropPreRollBars)
            } else {
                add(recipe.defaultOverlapBars)
                if (recipe.defaultOverlapBars > 8) add(8)
                if (recipe.minOverlapBars <= 4 && recipe.defaultOverlapBars > 4) add(4)
                add(min(recipe.defaultOverlapBars, maxFittingBars))
            }
        }
        return raw.distinct().filter { it >= 1 }
    }

    private fun computeSwapMs(
        recipe: RecipeCatalogEntry,
        exitMs: Long,
        overlapBars: Int,
        overlapMs: Long,
        outBarMs: Long,
        dropPreRollBars: Int?,
    ): Long {
        if (dropPreRollBars != null) {
            return (exitMs + dropPreRollBars * outBarMs).coerceIn(exitMs, exitMs + overlapMs)
        }
        if (recipe.isCut) {
            val cutBars = (overlapBars - 1).coerceAtLeast(1)
            return (exitMs + cutBars * outBarMs).coerceAtMost(exitMs + overlapMs)
        }
        val swapBars = (overlapBars / 2).coerceAtLeast(1)
        return (exitMs + swapBars * outBarMs).coerceAtMost(exitMs + overlapMs)
    }
}
