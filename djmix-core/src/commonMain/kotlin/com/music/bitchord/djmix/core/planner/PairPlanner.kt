package com.music.bitchord.djmix.core.planner

import com.music.bitchord.djmix.core.contracts.AbruptnessClass
import com.music.bitchord.djmix.core.contracts.CandidateScoreSummary
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RecipeFxType
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.TempoKnot
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import kotlin.math.min

/**
 * Pure Automix Engine 3 pair planner (`:djmix-core/planner`).
 *
 * Unifies candidate generation, Part A §4 placement rules, vc91 handoff PCM qualification,
 * ADR 0010 musicality-first & strict brake/spinback rationing, variety rotation, and
 * bounded learned taste weights into a single deterministic decision returning
 * [MixOutcome.Ok] with a [TransitionPlanV1] or a typed [MixOutcome.Refused].
 */
object PairPlanner {

    private const val ABRUPT_COOLDOWN_TRANSITIONS: Int = 8
    private const val VARIETY_RECENT_WINDOW: Int = 3
    private const val FX_HISTORY_WINDOW: Int = 6
    private const val TASTE_WEIGHT_CAP: Double = 0.30

    private val REFUSAL_PRIORITY: List<RefusalReason> = listOf(
        RefusalReason.HANDOFF_PCM_BEFORE_PROTECTED_EVENT,
        RefusalReason.EXIT_MID_VOCAL,
        RefusalReason.ENTRY_MID_VOCAL,
        RefusalReason.EXIT_INSIDE_BUILD,
        RefusalReason.BASS_CLASH,
        RefusalReason.ABRUPT_DISALLOWED,
        RefusalReason.LOW_GRID_CONFIDENCE,
        RefusalReason.TEMPO_GAP_EXCEEDED,
        RefusalReason.HARMONY_INCOMPATIBLE,
        RefusalReason.NO_INCOMING_DROP,
        RefusalReason.INSUFFICIENT_OVERLAP,
        RefusalReason.NO_ELIGIBLE_BOUNDARY,
    )

    private data class EvaluatedRecipeCandidate(
        val recipe: RecipeCatalogEntry,
        val candidate: TransitionCandidate,
        val totalScore: Double,
        val tasteAdjustment: Double,
        val placementReasons: List<String>,
        val summaryReason: String,
    )

    /**
     * Evaluates all eligible recipes in [catalog] across `LOOP`, `BRIDGE`, and `STRAIGHT`
     * geometries and returns the highest-scoring qualified [TransitionPlanV1], or a typed
     * [MixOutcome.Refused] explaining why no candidate can be safely frozen.
     */
    fun plan(
        request: PlannerRequest,
        catalog: List<RecipeCatalogEntry> = BuiltInRecipeCatalog.entries,
    ): MixOutcome<TransitionPlanV1> {
        if (!request.outTrack.isUsable || !request.inTrack.isUsable) {
            return MixOutcome.Refused(
                RefusalReason.WAITING_ON_ANALYSIS,
                "Track analysis missing or unusable for ${request.outTrack.trackId} -> ${request.inTrack.trackId}",
            )
        }
        val outSafeEnd = TrackSignalOps.safeOutEndMs(request.outTrack)
        val inSafeEnd = TrackSignalOps.safeInEndMs(request.inTrack)
        if (outSafeEnd < 4_000L || inSafeEnd - request.inTrack.silentIntroEndMs < 4_000L) {
            return MixOutcome.Refused(RefusalReason.TRACK_TOO_SHORT, "Track audible span too short for overlap")
        }

        val outBarMs = TrackSignalOps.barDurationMs(request.outTrack)
        val inBarMs = TrackSignalOps.barDurationMs(request.inTrack)
        val tempoDeltaPct = TrackSignalOps.tempoDeltaPct(request.outTrack.bpm, request.inTrack.bpm)
        val gridConf = min(request.outTrack.gridConfidence, request.inTrack.gridConfidence)
        val outGenre = TrackSignalOps.resolveGenre(request.outGenus, request.outTrack)
        val inGenre = TrackSignalOps.resolveGenre(request.inGenus, request.inTrack)
        val genreChanged = outGenre != null && inGenre != null && outGenre != inGenre
        val harmonyScore = CamelotKey.compatibilityScore(
            request.outTrack.keyCamelot,
            request.inTrack.keyCamelot,
            request.outTrack.keyConfidence,
            request.inTrack.keyConfidence,
        )
        val recentFxCounts = countRecentFx(request.recentRecipeIds, catalog)

        val bestPerRecipe = ArrayList<EvaluatedRecipeCandidate>()
        val activeLevelRefusals = ArrayList<Pair<RefusalReason, String>>()
        val anyLevelRefusals = ArrayList<Pair<RefusalReason, String>>()

        for (recipe in catalog) {
            if (!recipe.enabled || recipe.id in request.excludedRecipeIds) continue

            val recipeRefusal = checkRecipeEligibility(
                request = request,
                recipe = recipe,
                tempoDeltaPct = tempoDeltaPct,
                gridConf = gridConf,
                genreChanged = genreChanged,
            )
            if (recipeRefusal != null) {
                anyLevelRefusals += recipeRefusal
                if (request.djLevel.atLeast(recipe.level) || recipe.abruptness == AbruptnessClass.ABRUPT) {
                    activeLevelRefusals += recipeRefusal
                }
                continue
            }

            if (!request.djLevel.atLeast(recipe.level)) {
                continue
            }

            val candidates = CandidateGenerator.generateForRecipe(
                request = request,
                recipe = recipe,
                outBarMs = outBarMs,
                inBarMs = inBarMs,
                tempoDeltaPct = tempoDeltaPct,
            )
            if (candidates.isEmpty()) {
                activeLevelRefusals += RefusalReason.NO_ELIGIBLE_BOUNDARY to "${recipe.id}: no candidate boundary"
                continue
            }

            var bestForRecipe: EvaluatedRecipeCandidate? = null
            var bestCandidateRefusal: Pair<RefusalReason, String>? = null

            for (candidate in candidates) {
                when (val verdict = PlacementEvaluator.evaluate(request, candidate, recipe, tempoDeltaPct, harmonyScore)) {
                    is PlacementVerdict.Rejected -> {
                        if (bestCandidateRefusal == null ||
                            priorityOf(verdict.reason) < priorityOf(bestCandidateRefusal.first)
                        ) {
                            bestCandidateRefusal = verdict.reason to verdict.detail
                        }
                    }
                    is PlacementVerdict.Accepted -> {
                        val recipeScore = scoreRecipe(
                            request = request,
                            recipe = recipe,
                            candidate = candidate,
                            placementScore = verdict.placementScore,
                            tempoDeltaPct = tempoDeltaPct,
                            gridConf = gridConf,
                            genreChanged = genreChanged,
                            outGenre = outGenre,
                            recentFxCounts = recentFxCounts,
                        )
                        if (bestForRecipe == null || recipeScore.totalScore > bestForRecipe.totalScore + 1e-9) {
                            bestForRecipe = recipeScore.copy(placementReasons = verdict.reasons)
                        }
                    }
                }
            }

            if (bestForRecipe != null) {
                bestPerRecipe += bestForRecipe
            } else if (bestCandidateRefusal != null) {
                activeLevelRefusals += bestCandidateRefusal
            }
        }

        if (bestPerRecipe.isEmpty()) {
            val chosen = pickPrimaryRefusal(activeLevelRefusals.ifEmpty { anyLevelRefusals })
            return MixOutcome.Refused(chosen.first, chosen.second)
        }

        val ranked = bestPerRecipe.sortedWith(
            compareByDescending<EvaluatedRecipeCandidate> { it.totalScore }
                .thenByDescending { if (it.recipe.level == request.djLevel) 1 else 0 }
                .thenBy { it.recipe.id },
        )
        val winner = ranked.first()
        val topScores = ranked.take(3).map {
            CandidateScoreSummary(
                recipeId = it.recipe.id,
                recipeName = it.recipe.displayName,
                score = round3(it.totalScore),
                reason = it.summaryReason,
            )
        }

        val nextEligiblePhraseMs = findNextEligiblePhraseMs(request, winner.candidate.exitMs, outBarMs)
        val lastPhraseDownbeatMs = CandidateGenerator.lastPhraseDownbeatAfter(
            track = request.outTrack,
            afterMs = request.positionMs,
            safeEndMs = outSafeEnd,
            barMs = outBarMs,
        )

        return MixOutcome.Ok(
            buildPlan(
                request = request,
                winner = winner,
                tempoDeltaPct = tempoDeltaPct,
                topScores = topScores,
                isLastResort = false,
                nextEligiblePhraseMs = nextEligiblePhraseMs,
                lastPhraseDownbeatMs = lastPhraseDownbeatMs,
            ),
        )
    }

    /**
     * Builds a clean no-stem echo-out or filter-wash on the **last downbeat of the last phrase**
     * landing the incoming track on its intro downbeat (`isLastResort = true`). Never starts at
     * [PlannerRequest.positionMs].
     */
    fun planLastResortMusicalExit(request: PlannerRequest): MixOutcome<TransitionPlanV1> {
        if (!request.outTrack.isUsable || !request.inTrack.isUsable) {
            return MixOutcome.Refused(RefusalReason.WAITING_ON_ANALYSIS, "Track analysis missing or unusable")
        }
        val outTrack = request.outTrack
        val inTrack = request.inTrack
        val outBarMs = TrackSignalOps.barDurationMs(outTrack)
        val inBarMs = TrackSignalOps.barDurationMs(inTrack)
        val outSafeEnd = TrackSignalOps.safeOutEndMs(outTrack)
        val inSafeEnd = TrackSignalOps.safeInEndMs(inTrack)

        val lastDownbeatMs = CandidateGenerator.lastPhraseDownbeatAfter(
            track = outTrack,
            afterMs = request.positionMs,
            safeEndMs = outSafeEnd,
            barMs = outBarMs,
        ) ?: return MixOutcome.Refused(
            RefusalReason.NO_ELIGIBLE_BOUNDARY,
            "No downbeat remains past position ${request.positionMs}ms before safe end ${outSafeEnd}ms",
        )

        val remainingOutMs = outSafeEnd - lastDownbeatMs
        val overlapBars = min(4, (remainingOutMs / outBarMs).toInt()).coerceAtLeast(1)
        val overlapMs = min(overlapBars * outBarMs, remainingOutMs).coerceAtLeast(1L)
        val swapMs = (lastDownbeatMs + min(outBarMs, overlapMs)).coerceAtMost(lastDownbeatMs + overlapMs)

        val entryMs = TrackSignalOps.firstDownbeatAtOrAfter(
            inTrack,
            inTrack.silentIntroEndMs.coerceAtLeast(0L),
            inBarMs,
        )
        if (entryMs >= inSafeEnd) {
            return MixOutcome.Refused(
                RefusalReason.HANDOFF_PCM_BEFORE_PROTECTED_EVENT,
                "Incoming intro downbeat ${entryMs}ms exceeds safe end ${inSafeEnd}ms",
            )
        }

        val recipe = selectLastResortRecipe(request)
            ?: return MixOutcome.Refused(
                RefusalReason.CHOREOGRAPHY_UNAVAILABLE,
                "No allowed last-resort recipe for ${request.djLevel} (excluded=${request.excludedRecipeIds.sorted().joinToString(",")})",
            )
        val candidate = TransitionCandidate(
            exitMs = lastDownbeatMs,
            entryMs = entryMs,
            swapMs = swapMs,
            overlapBars = overlapBars,
            overlapDurationMs = overlapMs,
            barDurationMs = outBarMs,
            geometry = TransitionGeometry.STRAIGHT,
            exitReasonTag = "Last phrase downbeat",
            entryReasonTag = "Entry at intro bar 1",
        )

        PlacementEvaluator.qualifyHandoff(request, candidate, recipe, inRate = 1.0)?.let { refusal ->
            return MixOutcome.Refused(RefusalReason.HANDOFF_PCM_BEFORE_PROTECTED_EVENT, refusal)
        }

        val summary = CandidateScoreSummary(
            recipeId = recipe.id,
            recipeName = recipe.displayName,
            score = 0.5,
            reason = "${recipe.id} ${recipe.displayName} (last resort)",
        )
        val evaluated = EvaluatedRecipeCandidate(
            recipe = recipe,
            candidate = candidate,
            totalScore = 0.5,
            tasteAdjustment = 0.0,
            placementReasons = listOf("Last-resort musical exit on final phrase downbeat · Entry at intro bar 1"),
            summaryReason = summary.reason,
        )

        return MixOutcome.Ok(
            buildPlan(
                request = request,
                winner = evaluated,
                tempoDeltaPct = TrackSignalOps.tempoDeltaPct(outTrack.bpm, inTrack.bpm),
                topScores = listOf(summary),
                isLastResort = true,
                nextEligiblePhraseMs = null,
                lastPhraseDownbeatMs = lastDownbeatMs,
            ).copy(requiredStems = emptySet(), hasNoStemVariant = true),
        )
    }

    private fun checkRecipeEligibility(
        request: PlannerRequest,
        recipe: RecipeCatalogEntry,
        tempoDeltaPct: Double,
        gridConf: Float,
        genreChanged: Boolean,
    ): Pair<RefusalReason, String>? {
        if (recipe.abruptness == AbruptnessClass.ABRUPT) {
            if (request.djLevel != DjLevel.FESTIVAL) {
                return RefusalReason.ABRUPT_DISALLOWED to "${recipe.id}: abrupt moves are Festival-only"
            }
            if (gridConf < TrackSignalOps.LOW_GRID_CONFIDENCE_THRESHOLD) {
                return RefusalReason.ABRUPT_DISALLOWED to "${recipe.id}: abrupt moves never run as low-confidence fallback"
            }
            val jumpAllowed = tempoDeltaPct > 10.0 || (tempoDeltaPct > 8.0 && genreChanged)
            if (!jumpAllowed) {
                return RefusalReason.ABRUPT_DISALLOWED to
                    "${recipe.id}: requires |ΔBPM| > 10% or (> 8% with genre change), got ${round3(tempoDeltaPct)}%"
            }
            if (request.transitionsSinceLastAbrupt < ABRUPT_COOLDOWN_TRANSITIONS) {
                return RefusalReason.ABRUPT_DISALLOWED to
                    "${recipe.id}: cooldown active (${request.transitionsSinceLastAbrupt}/$ABRUPT_COOLDOWN_TRANSITIONS)"
            }
        }

        if (!request.djLevel.atLeast(recipe.level)) {
            return null
        }

        if (gridConf < recipe.minGridConfidence) {
            return RefusalReason.LOW_GRID_CONFIDENCE to
                "${recipe.id}: grid confidence $gridConf < ${recipe.minGridConfidence}"
        }

        if (recipe.requiresBeatMatch) {
            val maxStretchPct = min(recipe.maxTempoDeltaPct, request.djLevel.maxStretch * 100.0)
            if (tempoDeltaPct > maxStretchPct) {
                return RefusalReason.TEMPO_GAP_EXCEEDED to
                    "${recipe.id}: tempo delta ${round3(tempoDeltaPct)}% > ${round3(maxStretchPct)}%"
            }
        }

        if (recipe.requiresHarmony && !CamelotKey.areCompatible(
                request.outTrack.keyCamelot,
                request.inTrack.keyCamelot,
                request.outTrack.keyConfidence,
                request.inTrack.keyConfidence,
            )
        ) {
            return RefusalReason.HARMONY_INCOMPATIBLE to "${recipe.id}: incompatible Camelot keys"
        }

        if (recipe.isDropRecipe && recipe.requiresInDrop && request.inTrack.structure.drops.isEmpty()) {
            return RefusalReason.NO_INCOMING_DROP to "${recipe.id}: no incoming drop event"
        }

        if (recipe.requiresOutDrop && request.outTrack.structure.drops.isEmpty()) {
            return RefusalReason.NO_INCOMING_DROP to "${recipe.id}: requires outgoing drop event"
        }

        return null
    }

    private fun scoreRecipe(
        request: PlannerRequest,
        recipe: RecipeCatalogEntry,
        candidate: TransitionCandidate,
        placementScore: Double,
        tempoDeltaPct: Double,
        gridConf: Float,
        genreChanged: Boolean,
        outGenre: String?,
        recentFxCounts: Map<RecipeFxType, Int>,
    ): EvaluatedRecipeCandidate {
        val levelMatchBonus = when {
            recipe.level == request.djLevel -> 0.35
            request.djLevel == DjLevel.FESTIVAL && recipe.id == "C2" -> 0.25
            request.djLevel == DjLevel.CLUB && recipe.level == DjLevel.SUBTLE -> 0.05
            else -> 0.0
        }

        // ADR 0010 Musicality-first: smooth/blend/swap/sweep/riser recipes ALWAYS beat
        // abrupt cuts whenever tempo <= 12% and grid confidence is healthy.
        val healthyGrid = gridConf >= TrackSignalOps.LOW_GRID_CONFIDENCE_THRESHOLD
        val musicalityBonus = when {
            recipe.abruptness != AbruptnessClass.ABRUPT && tempoDeltaPct <= 12.0 && healthyGrid -> 0.45
            recipe.abruptness == AbruptnessClass.ABRUPT && tempoDeltaPct <= 12.0 -> -0.25
            recipe.abruptness == AbruptnessClass.ABRUPT && (tempoDeltaPct > 12.0 || (tempoDeltaPct > 10.0 && genreChanged)) -> 0.20
            else -> 0.0
        }

        // Low-confidence Festival fallback preference: F5 Tunnel or C2 Filter wash + echo beat S4/S5.
        val lowConfFallbackBonus = if (!healthyGrid && request.djLevel == DjLevel.FESTIVAL) {
            when (recipe.id) {
                "F5" -> 0.40
                "C2" -> 0.32
                else -> -0.25
            }
        } else {
            0.0
        }

        // Keep S5 simple fade for low confidence, incompatible tempo, or ballads.
        val balladOrIncompatible = !healthyGrid || tempoDeltaPct > request.djLevel.maxStretch * 100.0 ||
            outGenre == "BALLAD"
        val recipeFitBonus = when {
            recipe.id == "S5" && !balladOrIncompatible -> -0.35
            recipe.id == "S5" && balladOrIncompatible -> 0.30
            recipe.id == "S4" && !balladOrIncompatible -> -0.10
            recipe.id == "F5" && healthyGrid && tempoDeltaPct <= request.djLevel.maxStretch * 100.0 -> 0.08
            else -> 0.14
        }

        val genreBonus = outGenre?.let { recipe.genreAffinity[it] } ?: (recipe.genreAffinity["UNKNOWN"] ?: 0.0)

        // Variety: penalize last 3 recipes and boost underrepresented fxTags.
        val recent3 = request.recentRecipeIds.takeLast(VARIETY_RECENT_WINDOW)
        val repeatPenalty = when {
            request.recentRecipeIds.lastOrNull() == recipe.id -> 0.38
            recipe.id in recent3 -> 0.22
            else -> 0.0
        }
        val unseenFxCount = recipe.fxTags.count { (recentFxCounts[it] ?: 0) == 0 }
        val fxDiversityBoost = if (recipe.fxTags.isNotEmpty()) {
            min(0.12, unseenFxCount * 0.05)
        } else {
            0.0
        }

        // Bounded learned taste weight in [-0.3, +0.3] (Part B8).
        val rawTaste = request.tasteWeights[recipe.id] ?: 0.0
        val tasteAdjustment = if (rawTaste.isFinite()) rawTaste.coerceIn(-TASTE_WEIGHT_CAP, TASTE_WEIGHT_CAP) else 0.0

        val dropBoost = if (candidate.isDropAligned && recipe.isDropRecipe) 0.18 else 0.0

        val total = placementScore + levelMatchBonus + musicalityBonus + lowConfFallbackBonus +
            recipeFitBonus + genreBonus + fxDiversityBoost + dropBoost + tasteAdjustment - repeatPenalty

        val fxSummary = if (recipe.fxTags.isEmpty()) "dry" else recipe.fxTags.joinToString("+") { it.name.lowercase() }
        return EvaluatedRecipeCandidate(
            recipe = recipe,
            candidate = candidate,
            totalScore = total,
            tasteAdjustment = tasteAdjustment,
            placementReasons = emptyList(),
            summaryReason = "${recipe.id} ${recipe.displayName} ($fxSummary)",
        )
    }

    private fun countRecentFx(
        recentRecipeIds: List<String>,
        catalog: List<RecipeCatalogEntry>,
    ): Map<RecipeFxType, Int> {
        if (recentRecipeIds.isEmpty()) return emptyMap()
        val byId = catalog.associateBy { it.id }
        val counts = LinkedHashMap<RecipeFxType, Int>()
        for (id in recentRecipeIds.takeLast(FX_HISTORY_WINDOW)) {
            val fxSet = byId[id]?.fxTags ?: BuiltInRecipeCatalog.byId(id)?.fxTags ?: continue
            for (fx in fxSet) {
                counts[fx] = (counts[fx] ?: 0) + 1
            }
        }
        return counts
    }

    private fun findNextEligiblePhraseMs(
        request: PlannerRequest,
        currentExitMs: Long,
        outBarMs: Long,
    ): Long? {
        val outTrack = request.outTrack
        val outSafeEnd = TrackSignalOps.safeOutEndMs(outTrack)
        val outBeatMs = TrackSignalOps.beatDurationMs(outTrack)
        val minRemainingMs = 2L * outBarMs
        val phrases = CandidateGenerator.phraseDownbeats(
            track = outTrack,
            earliestMs = currentExitMs + 1L,
            safeEndMs = outSafeEnd - minRemainingMs,
            barMs = outBarMs,
        )
        return phrases.firstOrNull { boundary ->
            if (TrackSignalOps.isInsideBuild(outTrack, boundary)) return@firstOrNull false
            val vocal = TrackSignalOps.maxVocalInWindow(outTrack, boundary - outBeatMs, boundary + outBeatMs)
            vocal == null || vocal <= TrackSignalOps.VOCAL_MID_LINE_THRESHOLD
        }
    }

    private fun selectLastResortRecipe(request: PlannerRequest): RecipeCatalogEntry? {
        val preferredIds = when (request.djLevel) {
            DjLevel.SUBTLE -> listOf("S4", "S5")
            DjLevel.CLUB, DjLevel.FESTIVAL -> listOf("C2", "S4", "S5")
        }
        for (id in preferredIds) {
            if (id !in request.excludedRecipeIds) {
                BuiltInRecipeCatalog.byId(id)?.let { return it }
            }
        }
        return null
    }

    private fun buildPlan(
        request: PlannerRequest,
        winner: EvaluatedRecipeCandidate,
        tempoDeltaPct: Double,
        topScores: List<CandidateScoreSummary>,
        isLastResort: Boolean,
        nextEligiblePhraseMs: Long?,
        lastPhraseDownbeatMs: Long?,
    ): TransitionPlanV1 {
        val cand = winner.candidate
        val recipe = winner.recipe
        val outTrack = request.outTrack
        val inTrack = request.inTrack
        val targetBpm = if (recipe.requiresBeatMatch && inTrack.bpm > 1.0 && outTrack.bpm > 1.0) {
            inTrack.bpm / TrackSignalOps.alignedFold(outTrack.bpm, inTrack.bpm)
        } else {
            outTrack.bpm
        }
        val swapOffsetMs = (cand.swapMs - cand.exitMs).coerceIn(0L, cand.overlapDurationMs)
        val knots = listOf(
            TempoKnot(
                mixTimeMs = 0L,
                trackPosMs = cand.exitMs,
                bpm = outTrack.bpm,
                exactMixSec = 0.0,
                exactTrackPosSec = cand.exitMs / 1000.0,
            ),
            TempoKnot(
                mixTimeMs = swapOffsetMs,
                trackPosMs = cand.swapMs,
                bpm = (outTrack.bpm + targetBpm) * 0.5,
                exactMixSec = swapOffsetMs / 1000.0,
                exactTrackPosSec = cand.swapMs / 1000.0,
            ),
            TempoKnot(
                mixTimeMs = cand.overlapDurationMs,
                trackPosMs = cand.exitMs + cand.overlapDurationMs,
                bpm = targetBpm,
                exactMixSec = cand.overlapDurationMs / 1000.0,
                exactTrackPosSec = (cand.exitMs + cand.overlapDurationMs) / 1000.0,
            ),
        )
        val stems = if (request.preferNoStemVariant) emptySet() else recipe.requiredStems
        return TransitionPlanV1(
            planId = "plan-${request.generation}-${recipe.id}-${cand.exitMs}",
            generation = request.generation,
            outTrackId = outTrack.trackId,
            inTrackId = inTrack.trackId,
            outTitle = request.outTitle,
            inTitle = request.inTitle,
            djLevel = request.djLevel,
            geometry = cand.geometry,
            frozenExitMs = cand.exitMs,
            frozenEntryMs = cand.entryMs,
            swapDownbeatMs = cand.swapMs,
            overlapDurationMs = cand.overlapDurationMs,
            barDurationMs = cand.barDurationMs,
            tempoKnots = knots,
            recipeId = recipe.id,
            recipeName = recipe.displayName,
            recipeDescription = recipe.description,
            recipeParams = mapOf(
                "overlapBars" to cand.overlapBars.toDouble(),
                "tempoDeltaPct" to round3(tempoDeltaPct),
            ),
            requiredStems = stems,
            hasNoStemVariant = recipe.hasNoStemVariant,
            placementReasons = winner.placementReasons,
            topScores = topScores,
            tasteAdjustment = round3(winner.tasteAdjustment),
            isLastResort = isLastResort,
            nextEligiblePhraseMs = nextEligiblePhraseMs,
            lastPhraseDownbeatMs = lastPhraseDownbeatMs,
        )
    }

    private fun pickPrimaryRefusal(refusals: List<Pair<RefusalReason, String>>): Pair<RefusalReason, String> {
        if (refusals.isEmpty()) {
            return RefusalReason.NO_ELIGIBLE_BOUNDARY to "No eligible transition candidate found"
        }
        return refusals.minBy { priorityOf(it.first) }
    }

    private fun priorityOf(reason: RefusalReason): Int {
        val idx = REFUSAL_PRIORITY.indexOf(reason)
        return if (idx >= 0) idx else REFUSAL_PRIORITY.size
    }

    private fun round3(v: Double): Double = ((v * 1000.0).toLong()) / 1000.0
}
