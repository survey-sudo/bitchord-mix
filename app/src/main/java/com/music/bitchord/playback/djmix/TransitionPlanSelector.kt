package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.DjLevel as CoreDjLevel
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.TimeRangeMs
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.planner.PairPlanner
import com.music.bitchord.djmix.core.planner.PlannerRequest
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.playback.djmix.store.TrackAnalysisV3Mapper
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.PhraseMaps
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.ChoreoScript
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.choreo.RecipeId
import kotlin.math.roundToLong

/** Frozen planning inputs supplied to [TransitionPlanSelector] for a `(outTrack, inTrack)` pair. */
data class PlanInput(
    val outTrack: PrepTrack,
    val inTrack: PrepTrack,
    val outAnalysis: TrackAnalysis,
    val inAnalysis: TrackAnalysis,
    val outSongMap: SongMap?,
    val inSongMap: SongMap?,
    val sungOut: List<LoopMix.Span>?,
    val sungIn: List<LoopMix.Span>?,
    val djLevel: DjLevel,
    val sampleRate: Int,
    val positionSec: Double,
    val albumOrder: Boolean,
    val stemsExpected: Boolean,
    val preferStraight: Boolean = false,
    val excludedRecipes: Set<RecipeId> = emptySet(),
    val fallbackSeconds: Double = 8.0,
    val lyricsOut: List<LyricCue> = emptyList(),
    val lyricsIn: List<LyricCue> = emptyList(),
    val caps: Int = 0,
    val recentRecipeIds: List<String> = emptyList(),
    val transitionsSinceLastAbrupt: Int = 999,
    val minExitMs: Long? = null,
    val preferNoStemVariant: Boolean = false,
    val tasteWeights: Map<String, Double> = emptyMap(),
    val machineGeneration: Long = 0L,
    val outDecodeRangeMs: TimeRangeMs? = null,
    val inDecodeRangeMs: TimeRangeMs? = null,
    val excludedRecipeIds: Set<String> = emptySet(),
)

/** Selected transition geometry, concrete `LoopMix.Plan`, and frozen V1 contract for [PairPreparer]. */
data class SelectedPlan(
    val plan: LoopMix.Plan?,
    val phraseScript: PhraseScript? = null,
    val v2Prep: ChoreoSessionGlue.Prep? = null,
    val exitWindowSec: ClosedFloatingPointRange<Double>? = null,
    val entryCueSec: Double? = null,
    val pathHint: String = "v2-native",
    val fallbackReason: String = "",
    val straightRateMode: StraightRateMode? = null,
    val previewScript: ChoreoScript? = null,
    val transitionPlanV1: TransitionPlanV1? = null,
    /** Exact registry value selected by [transitionPlanV1]; never a legacy recipe alias. */
    val frozenRecipe: RecipeV2? = null,
    val refusalReason: RefusalReason? = null,
    /** Exact projected inputs passed to PairPlanner; absent for paths that did not use it. */
    val plannerOutAnalysis: TrackAnalysisV3? = null,
    val plannerInAnalysis: TrackAnalysisV3? = null,
)

/** Strategy interface that selects and freezes a transition plan from [PlanInput]. */
fun interface TransitionPlanSelector {
    fun select(input: PlanInput): SelectedPlan?
}

/**
 * Sole transition planner: consults `:djmix-core` [PairPlanner] first for `{exit window, entry cue,
 * geometry, recipe, requiredStems}`, then constructs the concrete `LoopMix.Plan` and
 * [ChoreoSessionGlue.Prep] aligned strictly to that frozen decision.
 */
class DefaultTransitionPlanSelector(
    private val straightFallback: (PlanInput) -> SelectedPlan? = { StraightMixGeometry.select(it, it.caps) },
) : TransitionPlanSelector {
    override fun select(input: PlanInput): SelectedPlan? {
        val outA = input.outAnalysis
        val inA = input.inAnalysis
        val classic = StraightMixGeometry.classicPlan(input)
        if (StraightMixGeometry.policyExcluded(classic, input.albumOrder)) {
            return SelectedPlan(
                plan = null,
                fallbackReason = if (input.albumOrder) "album-order-excluded" else classic.reason,
                refusalReason = if (input.albumOrder) RefusalReason.ALBUM_ORDER_EXCLUDED else null,
            )
        }
        val outDur = input.outTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: outA.duration
        val inDur = input.inTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: inA.duration
        val excludedRecipeIds = input.excludedRecipeIds + input.excludedRecipes.map { it.code }
        if (input.preferStraight && (!outA.isUsable || !inA.isUsable)) {
            val fallback = straightFallback(input) ?: return null
            val recipeId = fallback.transitionPlanV1?.recipeId ?: fallback.previewScript?.recipeId
            if (recipeId != null && recipeId in excludedRecipeIds) return SelectedPlan(
                plan = null, fallbackReason = "straight_recipe_excluded:$recipeId",
                refusalReason = RefusalReason.CHOREOGRAPHY_UNAVAILABLE,
            )
            return fallback
        }

        val outV3 = toV3ForPlanner(input.outTrack.id, outA, PhraseMaps.get(input.outTrack.id), input.outSongMap, outDur)
        val inV3 = toV3ForPlanner(input.inTrack.id, inA, PhraseMaps.get(input.inTrack.id), input.inSongMap, inDur)
        val positionMs = (input.positionSec * 1000.0).roundToLong().coerceAtLeast(0L)
        val plannerReq = PlannerRequest(
            generation = input.machineGeneration.coerceAtLeast(0L),
            outTrack = outV3,
            inTrack = inV3,
            outTitle = input.outTrack.title,
            inTitle = input.inTrack.title,
            djLevel = toCoreDjLevel(input.djLevel),
            positionMs = positionMs,
            minExitMs = input.minExitMs,
            recentRecipeIds = input.recentRecipeIds,
            transitionsSinceLastAbrupt = input.transitionsSinceLastAbrupt,
            excludedRecipeIds = excludedRecipeIds,
            preferNoStemVariant = input.preferNoStemVariant || !input.stemsExpected,
            tasteWeights = input.tasteWeights,
            outDecodeRangeMs = input.outDecodeRangeMs,
            inDecodeRangeMs = input.inDecodeRangeMs,
        )

        var outcome = PairPlanner.plan(plannerReq)
        if (outcome is MixOutcome.Refused && outcome.reason == RefusalReason.NO_ELIGIBLE_BOUNDARY) {
            val lastResort = PairPlanner.planLastResortMusicalExit(plannerReq)
            if (lastResort is MixOutcome.Ok) {
                outcome = lastResort
            }
        }
        if (outcome !is MixOutcome.Ok) {
            val refused = outcome as? MixOutcome.Refused
            return SelectedPlan(
                plan = null,
                fallbackReason = refused?.detail ?: "no-v2-plan",
                refusalReason = refused?.reason ?: RefusalReason.NO_ELIGIBLE_BOUNDARY,
            )
        }

        // Also enforce exclusions on the last-resort result: it must not revive
        // a recipe excluded by the current remix request.
        if (outcome.value.recipeId in plannerReq.excludedRecipeIds) return SelectedPlan(
            plan = null, fallbackReason = "canonical_recipe_excluded:${outcome.value.recipeId}",
            refusalReason = RefusalReason.CHOREOGRAPHY_UNAVAILABLE,
        )

        return buildConcreteFromPlanV1(outcome.value, input, outDur, inDur)?.copy(
            plannerOutAnalysis = outV3,
            plannerInAnalysis = inV3,
        )
    }

    internal fun buildConcreteFromPlanV1(
        v1: TransitionPlanV1,
        input: PlanInput,
        outDur: Double,
        inDur: Double,
    ): SelectedPlan? {
        val concrete = PlaybackPlanConcretizer.buildConcreteFromPlanV1(
            v1, input.toPlaybackInput(), outDur, inDur,
            configuration = ChoreoSessionGlue.preparationConfiguration(),
            history = ChoreoSessionGlue.preparationHistory("${input.outTrack.id}->${input.inTrack.id}"),
            onPreparationFailure = ChoreoSessionGlue::recordPreparationFailure,
        ) ?: return null
        val prep = concrete.v2Prep?.let(ChoreoSessionGlue::fromSharedPreparation)
        val plan = concrete.plan
        val previewScript = concrete.previewRecipe?.let { legacyRecipe ->
            if (prep == null || plan == null) null else ChoreoSessionGlue.withoutStems(
                prep, plan, LoopMix.Clock(plan), input.sampleRate, input.outAnalysis, input.inAnalysis,
                input.sungOut, input.sungIn, legacyRecipe, requirePlannedRecipe = true,
            )
        }
        return SelectedPlan(
            plan = plan, phraseScript = concrete.phraseScript, v2Prep = prep,
            exitWindowSec = concrete.exitWindowSec, entryCueSec = concrete.entryCueSec,
            pathHint = concrete.pathHint, fallbackReason = concrete.fallbackReason,
            straightRateMode = concrete.straightRateMode, previewScript = previewScript,
            transitionPlanV1 = concrete.transitionPlanV1, frozenRecipe = concrete.frozenRecipe,
            refusalReason = concrete.refusalReason,
        )
    }

    /** Same source-to-millisecond projection as ScriptExecutionGuard, including tracked knots. */
    internal fun matchesFrozenSources(
        v1: TransitionPlanV1, plan: LoopMix.Plan, input: PlanInput, rateMode: StraightRateMode? = null,
    ): Boolean = PlaybackPlanConcretizer.matchesFrozenSources(v1, plan, input.toPlaybackInput(), rateMode)

    /** Validate before PlanFrozen: no shortened bars, shifted hold, or incompatible knot clock. */
    internal fun matchesFrozenTiming(
        v1: TransitionPlanV1, plan: LoopMix.Plan, input: PlanInput, rateMode: StraightRateMode,
    ): Boolean = PlaybackPlanConcretizer.matchesFrozenTiming(v1, plan, input.toPlaybackInput(), rateMode)

    private fun PlanInput.toPlaybackInput(): PlaybackPlanConcretizer.Input = PlaybackPlanConcretizer.Input(
        outId = outTrack.id, inId = inTrack.id, outAnalysis = outAnalysis, inAnalysis = inAnalysis,
        outSongMap = outSongMap, inSongMap = inSongMap, sungOut = sungOut, sungIn = sungIn,
        djLevel = djLevel, sampleRate = sampleRate, positionSec = positionSec,
        albumOrder = albumOrder, stemsExpected = stemsExpected, preferStraight = preferStraight,
        excludedRecipes = excludedRecipes, caps = caps, preferNoStemVariant = preferNoStemVariant,
    )
}

/** Maps runtime [DjLevel] to `:djmix-core` [CoreDjLevel]. */
internal fun toCoreDjLevel(level: DjLevel): CoreDjLevel = when (level) {
    DjLevel.SUBTLE -> CoreDjLevel.SUBTLE
    DjLevel.CLUB -> CoreDjLevel.CLUB
    DjLevel.FESTIVAL -> CoreDjLevel.FESTIVAL
}

/** Classifies [TrackAnalysis] completeness into `:djmix-core` [AnalysisLevel]. */
internal fun analysisLevelOf(analysis: TrackAnalysis): AnalysisLevel = when {
    !analysis.isUsable -> AnalysisLevel.NONE
    analysis.quality == TrackAnalysis.QUALITY_FULL -> AnalysisLevel.FULL
    else -> AnalysisLevel.HEAD_TAIL
}

/** Converts app [TrackAnalysis] + maps to [TrackAnalysisV3] with synthesized beat fallback when needed. */
internal fun toV3ForPlanner(
    trackId: String,
    analysis: TrackAnalysis,
    phraseMap: SongMap?,
    songMap: SongMap?,
    fallbackDurationSec: Double,
): TrackAnalysisV3 {
    val durSec = fallbackDurationSec.takeIf { it.isFinite() && it > 1.0 } ?: analysis.duration
    val normalizedAnalysis = if (durSec > 1.0 && durSec != analysis.duration) {
        analysis.copy(duration = durSec)
    } else analysis
    val v3 = TrackAnalysisV3Mapper.toV3(
        trackId = trackId,
        contentHash = "",
        analysis = normalizedAnalysis,
        phraseMap = phraseMap,
        songMap = songMap,
        modelVersions = emptyMap(),
    ).snapshotForPlanner()
    if (normalizedAnalysis.isUsable && v3.beatsMs.isEmpty() && v3.bpm > 1.0 && v3.durationMs > 0L) {
        val beatMs = (60_000.0 / v3.bpm).roundToLong().coerceAtLeast(250L)
        val firstMs = (normalizedAnalysis.firstBeat.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
            .let { (it * 1000.0).roundToLong().coerceIn(0L, v3.durationMs) }
        val beats = ArrayList<Long>()
        val downs = ArrayList<Long>()
        var t = firstMs
        var idx = 0
        while (t <= v3.durationMs && beats.size < 2048) {
            beats += t
            if (idx % 4 == 0) downs += t
            t += beatMs
            idx++
        }
        return v3.copy(
            analysisLevel = if (v3.analysisLevel == AnalysisLevel.NONE) AnalysisLevel.HEAD_TAIL else v3.analysisLevel,
            beatsMs = beats.toList(),
            downbeatsMs = if (v3.downbeatsMs.isEmpty()) downs.toList() else v3.downbeatsMs,
        )
    }
    return v3
}

/** Detach every V3 collection before planning so later analysis/map mutation cannot relabel a plan. */
private fun TrackAnalysisV3.snapshotForPlanner(): TrackAnalysisV3 = copy(
    beatsMs = beatsMs.toList(),
    downbeatsMs = downbeatsMs.toList(),
    shortTermLufsCurve = shortTermLufsCurve.toList(),
    energyCurve = energyCurve.toList(),
    vocalPresence50fps = vocalPresence50fps.toList(),
    modelVersions = modelVersions.toMap(),
    structure = structure.copy(
        sections = structure.sections.toList(),
        phrases = structure.phrases.toList(),
        builds = structure.builds.toList(),
        drops = structure.drops.toList(),
        breakdowns = structure.breakdowns.toList(),
        chorusFamilies = structure.chorusFamilies.map { it.copy(occurrenceStartMs = it.occurrenceStartMs.toList()) },
    ),
)
