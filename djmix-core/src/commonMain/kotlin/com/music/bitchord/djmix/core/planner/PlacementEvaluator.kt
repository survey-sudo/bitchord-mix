package com.music.bitchord.djmix.core.planner

import com.music.bitchord.djmix.core.contracts.AbruptnessClass
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.SectionType
import com.music.bitchord.djmix.core.contracts.StemKind
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Result of evaluating one `(candidate, recipe)` against Part A §4 and handoff PCM rules. */
internal sealed interface PlacementVerdict {
    data class Accepted(
        val placementScore: Double,
        val vocalOverlapSec: Double,
        val reasons: List<String>,
    ) : PlacementVerdict

    data class Rejected(
        val reason: RefusalReason,
        val detail: String,
    ) : PlacementVerdict
}

/**
 * Evaluates candidate placements against Part A §4 musical rules and vc91 handoff PCM
 * qualification BEFORE any plan is frozen.
 */
internal object PlacementEvaluator {

    private const val SAMPLE_STEP_MS: Long = 50L
    private const val HANDOVER_MARGIN_MS: Long = 25L

    fun evaluate(
        request: PlannerRequest,
        candidate: TransitionCandidate,
        recipe: RecipeCatalogEntry,
        tempoDeltaPct: Double,
        harmonyScore: Double,
    ): PlacementVerdict {
        val outTrack = request.outTrack
        val inTrack = request.inTrack
        val outBeatMs = TrackSignalOps.beatDurationMs(outTrack)
        val inBeatMs = TrackSignalOps.beatDurationMs(inTrack)
        val barMs = candidate.barDurationMs
        val phraseMs = TrackSignalOps.phraseBars(outTrack) * barMs

        // 1. Overlap length gate.
        if (candidate.overlapBars < recipe.minOverlapBars) {
            return PlacementVerdict.Rejected(
                RefusalReason.INSUFFICIENT_OVERLAP,
                "Overlap ${candidate.overlapBars} bars < min ${recipe.minOverlapBars}",
            )
        }

        // 2. Exit mid-vocal hard reject (Syvenu > 0.5 within +-1 beat of exit).
        if (recipe.avoidVocalExit) {
            val maxOutVocal = TrackSignalOps.maxVocalInWindow(
                outTrack,
                candidate.exitMs - outBeatMs,
                candidate.exitMs + outBeatMs,
            )
            if (maxOutVocal != null && maxOutVocal > TrackSignalOps.VOCAL_MID_LINE_THRESHOLD) {
                return PlacementVerdict.Rejected(
                    RefusalReason.EXIT_MID_VOCAL,
                    "Outgoing vocal $maxOutVocal > 0.5 near exit ${candidate.exitMs}ms",
                )
            }
        }

        // 3. Exit inside a BUILD section hard reject.
        if (recipe.avoidBuildExit && TrackSignalOps.isInsideBuild(outTrack, candidate.exitMs)) {
            return PlacementVerdict.Rejected(
                RefusalReason.EXIT_INSIDE_BUILD,
                "Exit ${candidate.exitMs}ms sits inside a BUILD section",
            )
        }

        // 4. Abrupt recipes must exit cleanly on a phrase or section boundary.
        if (recipe.abruptness == AbruptnessClass.ABRUPT &&
            !TrackSignalOps.isCleanPhraseOrSectionBoundary(outTrack, candidate.exitMs, barMs)
        ) {
            return PlacementVerdict.Rejected(
                RefusalReason.ABRUPT_DISALLOWED,
                "Abrupt exit ${candidate.exitMs}ms is not on a clean phrase/section boundary",
            )
        }

        // 5. Entry silent intro + mid-vocal hard reject at SUBTLE / CLUB.
        if (candidate.entryMs < inTrack.silentIntroEndMs) {
            return PlacementVerdict.Rejected(
                RefusalReason.HANDOFF_IN_ENTRY_PROTECTED_ATTACK,
                "Entry ${candidate.entryMs}ms lies inside silent intro (< ${inTrack.silentIntroEndMs}ms)",
            )
        }
        if (recipe.avoidVocalEntry && (request.djLevel == DjLevel.SUBTLE || request.djLevel == DjLevel.CLUB)) {
            val maxInVocal = TrackSignalOps.maxVocalInWindow(
                inTrack,
                candidate.entryMs - inBeatMs,
                candidate.entryMs + inBeatMs,
            )
            if (maxInVocal != null && maxInVocal > TrackSignalOps.VOCAL_MID_LINE_THRESHOLD) {
                return PlacementVerdict.Rejected(
                    RefusalReason.ENTRY_MID_VOCAL,
                    "Incoming vocal $maxInVocal > 0.5 near entry ${candidate.entryMs}ms at ${request.djLevel}",
                )
            }
        }

        // 6. Drop pre-roll cap (<= 32 bars at CLUB, <= 64 bars at FESTIVAL) & double-drop check.
        if (candidate.isDropAligned) {
            val inBarMs = TrackSignalOps.barDurationMs(inTrack)
            val firstIntro = TrackSignalOps.firstDownbeatAtOrAfter(inTrack, inTrack.silentIntroEndMs, inBarMs)
            val maxCap = when (request.djLevel) {
                DjLevel.SUBTLE -> 0
                DjLevel.CLUB -> 32
                DjLevel.FESTIVAL -> 64
            }
            val dropMs = candidate.entryMs + (candidate.swapMs - candidate.exitMs)
            val introToDropBars = ((dropMs - firstIntro).toDouble() / inBarMs).roundToInt()
            if (introToDropBars > maxCap || candidate.overlapBars > maxCap) {
                return PlacementVerdict.Rejected(
                    RefusalReason.NO_INCOMING_DROP,
                    "Drop pre-roll exceeds $maxCap-bar cap at ${request.djLevel}",
                )
            }
        }
        if (recipe.requiresOutDrop && outTrack.structure.drops.none { abs(it.timeMs - candidate.swapMs) <= barMs }) {
            return PlacementVerdict.Rejected(
                RefusalReason.NO_INCOMING_DROP,
                "Double drop swap ${candidate.swapMs}ms does not align with an outgoing drop",
            )
        }

        // 7. Handoff PCM bounds check BEFORE freezing (audibleEndMs + 60ms EOF + decode ranges).
        val stretchFactor = if (recipe.requiresBeatMatch && outTrack.bpm > 1.0 && inTrack.bpm > 1.0) {
            val foldedIn = inTrack.bpm / TrackSignalOps.alignedFold(outTrack.bpm, inTrack.bpm)
            (foldedIn / outTrack.bpm).coerceIn(0.75, 1.35)
        } else {
            1.0
        }
        qualifyHandoff(request, candidate, recipe, stretchFactor)?.let { refusal ->
            return PlacementVerdict.Rejected(
                RefusalReason.HANDOFF_PCM_BEFORE_PROTECTED_EVENT,
                refusal,
            )
        }

        // 8. Bass clash check (> -6 dB overlap for > 1 beat unless recipe swaps/cuts bass).
        val bassClashingMs = measureBassClashMs(
            outTrack = outTrack,
            inTrack = inTrack,
            exitMs = candidate.exitMs,
            entryMs = candidate.entryMs,
            overlapMs = candidate.overlapDurationMs,
            inRate = stretchFactor,
        )
        if (!recipe.swapsBass && !recipe.isCut && bassClashingMs > outBeatMs) {
            return PlacementVerdict.Rejected(
                RefusalReason.BASS_CLASH,
                "Both basslines > -6 dB for ${bassClashingMs}ms (> 1 beat ${outBeatMs}ms)",
            )
        }

        // 9. Compute Part A §4 placement, vocal-overlap, energy, tempo, and harmony score.
        return scoreAccepted(
            request = request,
            candidate = candidate,
            recipe = recipe,
            tempoDeltaPct = tempoDeltaPct,
            harmonyScore = harmonyScore,
            stretchFactor = stretchFactor,
            bassClashingMs = bassClashingMs,
            phraseMs = phraseMs,
            barMs = barMs,
        )
    }

    /**
     * Validates that exit, entry, and the full overlap stay inside `audibleEndMs` (with 60 ms
     * container EOF safety) and inside supplied `outDecodeRangeMs` / `inDecodeRangeMs`.
     * Returns null when valid, or a diagnostic refusal string.
     */
    fun qualifyHandoff(
        request: PlannerRequest,
        candidate: TransitionCandidate,
        recipe: RecipeCatalogEntry,
        inRate: Double = 1.0,
    ): String? {
        val outTrack = request.outTrack
        val inTrack = request.inTrack
        val outSafeEnd = TrackSignalOps.safeOutEndMs(outTrack)
        val inSafeEnd = TrackSignalOps.safeInEndMs(inTrack)

        val outOverlapEnd = candidate.exitMs + candidate.overlapDurationMs
        val outRequiredEnd = if (recipe.isCut) candidate.swapMs else outOverlapEnd
        if (candidate.exitMs < 0L || outOverlapEnd <= candidate.exitMs) {
            return "invalid outgoing span [${candidate.exitMs}, $outOverlapEnd]"
        }
        if (outRequiredEnd > outSafeEnd || outOverlapEnd > outSafeEnd) {
            return "outgoing overlap end ${outOverlapEnd}ms exceeds safe audible end ${outSafeEnd}ms"
        }

        val inOverlapSpan = (candidate.overlapDurationMs * inRate).toLong().coerceAtLeast(1L)
        val inRequiredEnd = candidate.entryMs + inOverlapSpan + HANDOVER_MARGIN_MS
        if (candidate.entryMs < inTrack.silentIntroEndMs) {
            return "incoming entry ${candidate.entryMs}ms precedes silentIntroEndMs ${inTrack.silentIntroEndMs}ms"
        }
        if (inRequiredEnd > inSafeEnd) {
            return "incoming overlap end ${inRequiredEnd}ms exceeds safe audible end ${inSafeEnd}ms"
        }

        request.outDecodeRangeMs?.let { range ->
            if (candidate.exitMs < range.startMs || outOverlapEnd > range.endMs) {
                return "outgoing [${candidate.exitMs}, $outOverlapEnd] outside decode range [${range.startMs}, ${range.endMs}]"
            }
        }
        request.inDecodeRangeMs?.let { range ->
            if (candidate.entryMs < range.startMs || inRequiredEnd > range.endMs) {
                return "incoming [${candidate.entryMs}, $inRequiredEnd] outside decode range [${range.startMs}, ${range.endMs}]"
            }
        }
        return null
    }

    private fun scoreAccepted(
        request: PlannerRequest,
        candidate: TransitionCandidate,
        recipe: RecipeCatalogEntry,
        tempoDeltaPct: Double,
        harmonyScore: Double,
        stretchFactor: Double,
        bassClashingMs: Long,
        phraseMs: Long,
        barMs: Long,
    ): PlacementVerdict.Accepted {
        val outTrack = request.outTrack
        val inTrack = request.inTrack
        val reasons = ArrayList<String>(4)

        // Exit quality: prefer first phrase in OUTRO after last chorus/drop, or vocal-free phrase.
        val lastChorusOrDropEnd = outTrack.structure.sections
            .filter {
                it.type == SectionType.CHORUS || it.type == SectionType.POST_CHORUS ||
                    it.type == SectionType.HOOK || it.type == SectionType.DROP
            }
            .maxOfOrNull { it.endMs } ?: 0L
        val outro = outTrack.structure.sections.firstOrNull { it.type == SectionType.OUTRO }
        val inOutroAfterChorus = outro != null && candidate.exitMs >= outro.startMs &&
            candidate.exitMs < outro.endMs && candidate.exitMs >= lastChorusOrDropEnd
        val nextPhraseVocalMax = TrackSignalOps.maxVocalInWindow(outTrack, candidate.exitMs, candidate.exitMs + phraseMs)
        val nextPhraseVocalFree = nextPhraseVocalMax != null && nextPhraseVocalMax < TrackSignalOps.VOCAL_SILENT_THRESHOLD

        var exitScore = 0.0
        if (inOutroAfterChorus && lastChorusOrDropEnd > 0L) {
            val offsetInsideOutro = (candidate.exitMs - max(outro!!.startMs, lastChorusOrDropEnd)).coerceAtLeast(0L)
            val earliestOutroBonus = if (offsetInsideOutro <= barMs) 0.12 else 0.04
            exitScore += 0.40 + earliestOutroBonus
        } else if (nextPhraseVocalFree) {
            exitScore += 0.28
        } else {
            val sec = TrackSignalOps.sectionAt(outTrack, candidate.exitMs)
            exitScore += when (sec?.type) {
                SectionType.OUTRO, SectionType.FADE_OUT -> 0.26
                SectionType.INSTRUMENTAL, SectionType.BREAKDOWN -> 0.20
                SectionType.CHORUS, SectionType.DROP -> 0.08
                else -> 0.0
            }
        }

        // Penalty for cutting before the final chorus/drop when an outro exists later.
        if (lastChorusOrDropEnd > 0L && candidate.exitMs < lastChorusOrDropEnd &&
            lastChorusOrDropEnd < TrackSignalOps.safeOutEndMs(outTrack) - barMs
        ) {
            exitScore -= 0.32
        }

        // Prefer leaving >= 1 full phrase of outgoing music after swap (for non-cuts).
        val postSwapMusicMs = (outTrack.audibleEndMs - candidate.swapMs).coerceAtLeast(0L)
        if (!recipe.isCut) {
            if (postSwapMusicMs >= phraseMs) {
                exitScore += 0.14
            } else {
                exitScore -= 0.22
            }
        }

        // Prefer EDM/FX/DROP exit at breakdown end.
        if ((recipe.preferBreakdownExit || recipe.id.startsWith("F")) &&
            outTrack.structure.breakdowns.any { abs(it.endMs - candidate.exitMs) <= barMs / 2L }
        ) {
            exitScore += 0.14
        }

        // Entry quality: intro bar 1, pre-vocal phrase, or drop downbeat.
        val inBarMs = TrackSignalOps.barDurationMs(inTrack)
        val firstIntro = TrackSignalOps.firstDownbeatAtOrAfter(inTrack, inTrack.silentIntroEndMs, inBarMs)
        var entryScore = 0.0
        if (candidate.isDropAligned) {
            entryScore += 0.34
        } else if (abs(candidate.entryMs - firstIntro) <= inBarMs / 2L) {
            entryScore += 0.28
        } else if (candidate.entryReasonTag.contains("before vocal")) {
            entryScore += 0.26
        } else {
            entryScore += 0.14
        }
        val skipSec = (candidate.entryMs - firstIntro).coerceAtLeast(0L) / 1000.0
        if (!candidate.isDropAligned && skipSec > 16.0) {
            entryScore -= min(0.20, (skipSec - 16.0) / 160.0)
        }

        // Vocal-overlap seconds (target 0) and bass overlap.
        val rawVocalOverlapSec = measureVocalOverlapSec(
            outTrack, inTrack, candidate.exitMs, candidate.entryMs, candidate.overlapDurationMs, stretchFactor,
        )
        val stemVocalMitigation = if (!request.preferNoStemVariant && recipe.requiredStems.any { it.stem == StemKind.VOCALS }) {
            0.35
        } else {
            1.0
        }
        val effectiveVocalOverlapSec = rawVocalOverlapSec * stemVocalMitigation
        val vocalPenalty = 0.22 * min(effectiveVocalOverlapSec, 4.0)

        val bassFraction = if (candidate.overlapDurationMs > 0L) {
            (bassClashingMs.toDouble() / candidate.overlapDurationMs).coerceIn(0.0, 1.0)
        } else 0.0
        val bassPenalty = if (recipe.swapsBass || recipe.isCut) 0.05 * bassFraction else 0.45 * bassFraction

        // Tempo fit, Camelot harmony, and energy trajectory.
        val tempoScore = if (!recipe.requiresBeatMatch) {
            0.15
        } else {
            val maxPct = max(1.0, request.djLevel.maxStretch * 100.0)
            0.30 * (1.0 - (tempoDeltaPct / maxPct).coerceIn(0.0, 1.0))
        }
        val harmWeight = if (recipe.requiresHarmony) 0.38 else 0.22
        val harmScore = harmWeight * (harmonyScore - 0.5)

        val outEnergy = TrackSignalOps.meanEnergyInWindow(
            outTrack, (candidate.swapMs - 4 * barMs).coerceAtLeast(0L), candidate.swapMs,
        )
        val inEnergy = TrackSignalOps.meanEnergyInWindow(
            inTrack, candidate.entryMs, candidate.entryMs + 8 * inBarMs,
        )
        val energyScore = if (outEnergy != null && inEnergy != null && outEnergy > 0.05) {
            val ratio = (inEnergy / outEnergy).coerceIn(0.2, 2.0)
            if (ratio < 0.6) -0.15 * ((0.6 - ratio) / 0.4) else 0.08 * min(1.0, (ratio - 0.9).coerceAtLeast(0.0) / 0.5)
        } else 0.0

        val vocalTag = when {
            rawVocalOverlapSec <= 0.25 -> "no vocal overlap"
            effectiveVocalOverlapSec <= 1.0 -> "minimal vocal overlap"
            else -> "vocal overlap ${(rawVocalOverlapSec * 10.0).toInt() / 10.0}s"
        }
        reasons += "${candidate.exitReasonTag} · ${candidate.entryReasonTag} · $vocalTag"

        val geometryBonus = when (candidate.geometry) {
            TransitionGeometry.LOOP -> if (recipe.requiresOutLoop || TrackSignalOps.sectionAt(outTrack, candidate.exitMs)?.loopable == true) 0.04 else -0.02
            TransitionGeometry.BRIDGE -> 0.03
            TransitionGeometry.STRAIGHT -> 0.0
        }

        val total = exitScore + entryScore + tempoScore + harmScore + energyScore + geometryBonus - vocalPenalty - bassPenalty
        return PlacementVerdict.Accepted(
            placementScore = total,
            vocalOverlapSec = rawVocalOverlapSec,
            reasons = reasons,
        )
    }

    private fun measureVocalOverlapSec(
        outTrack: TrackAnalysisV3,
        inTrack: TrackAnalysisV3,
        exitMs: Long,
        entryMs: Long,
        overlapMs: Long,
        inRate: Double,
    ): Double {
        if (outTrack.vocalPresence50fps.isEmpty() || inTrack.vocalPresence50fps.isEmpty() || overlapMs <= 0L) {
            return 0.0
        }
        var dt = 0L
        var overlapCount = 0
        var totalSamples = 0
        while (dt < overlapMs) {
            val outV = TrackSignalOps.vocalAtMs(outTrack, exitMs + dt)
            val inV = TrackSignalOps.vocalAtMs(inTrack, entryMs + (dt * inRate).toLong())
            if (outV != null && inV != null) {
                totalSamples++
                if (outV > TrackSignalOps.VOCAL_SILENT_THRESHOLD && inV > TrackSignalOps.VOCAL_SILENT_THRESHOLD) {
                    overlapCount++
                }
            }
            dt += SAMPLE_STEP_MS
        }
        if (totalSamples == 0) return 0.0
        return (overlapCount * SAMPLE_STEP_MS) / 1000.0
    }

    private fun measureBassClashMs(
        outTrack: TrackAnalysisV3,
        inTrack: TrackAnalysisV3,
        exitMs: Long,
        entryMs: Long,
        overlapMs: Long,
        inRate: Double,
    ): Long {
        if (overlapMs <= 0L) return 0L
        val outHasBass = outTrack.structure.sections.any {
            TrackSignalOps.sectionBassLinear(it) > TrackSignalOps.BASS_PRESENT_LINEAR
        }
        val inHasBass = inTrack.structure.sections.any {
            TrackSignalOps.sectionBassLinear(it) > TrackSignalOps.BASS_PRESENT_LINEAR
        }
        if (!outHasBass || !inHasBass) return 0L

        var dt = 0L
        var clashMs = 0L
        while (dt < overlapMs) {
            val outBass = TrackSignalOps.sectionBassLinear(TrackSignalOps.sectionAt(outTrack, exitMs + dt))
            val inBass = TrackSignalOps.sectionBassLinear(
                TrackSignalOps.sectionAt(inTrack, entryMs + (dt * inRate).toLong()),
            )
            if (outBass > TrackSignalOps.BASS_PRESENT_LINEAR && inBass > TrackSignalOps.BASS_PRESENT_LINEAR) {
                clashMs += min(SAMPLE_STEP_MS, overlapMs - dt)
            }
            dt += SAMPLE_STEP_MS
        }
        return clashMs
    }
}
