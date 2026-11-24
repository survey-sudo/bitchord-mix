package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.DjLevel as CoreDjLevel
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.TimeRangeMs
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.planner.PairPlanner
import com.music.bitchord.djmix.core.planner.PlannerRequest
import com.music.bitchord.playback.djmix.store.TrackAnalysisV3Mapper
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhraseMaps
import com.music.bitchord.playback.smart.PhrasePlanner
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.alignTempoOctave
import com.music.bitchord.playback.smart.camelotCode
import com.music.bitchord.playback.smart.choreo.ChoreoScript
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.choreo.RecipeId
import com.music.bitchord.playback.smart.choreo.StructureView
import com.music.bitchord.playback.smart.entryKeyConfidence
import com.music.bitchord.playback.smart.entryKeyForMix
import com.music.bitchord.playback.smart.exitKeyConfidence
import com.music.bitchord.playback.smart.exitKeyForMix
import com.music.bitchord.playback.smart.regionalCamelotSteps
import com.music.bitchord.playback.smart.shouldDeclineToMix
import kotlin.math.abs
import kotlin.math.roundToInt
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
    val refusalReason: RefusalReason? = null,
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
        if (input.preferStraight && (!outA.isUsable || !inA.isUsable)) {
            return straightFallback(input)
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
            excludedRecipeIds = input.excludedRecipes.map { it.code }.toSet(),
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

        return buildConcreteFromPlanV1(outcome.value, input, outDur, inDur)
    }

    private fun buildConcreteFromPlanV1(
        v1: TransitionPlanV1,
        input: PlanInput,
        outDur: Double,
        inDur: Double,
    ): SelectedPlan? {
        val outA = input.outAnalysis
        val inA = input.inAnalysis
        val exitSec = v1.frozenExitMs / 1000.0
        val entrySec = v1.frozenEntryMs / 1000.0
        val barSec = (v1.barDurationMs / 1000.0).coerceAtLeast(0.5)
        val overlapBars = (v1.overlapDurationMs.toDouble() / v1.barDurationMs.coerceAtLeast(500L))
            .roundToInt().coerceIn(1, 16)
        val requestedRecipe = RecipeId.fromCode(v1.recipeId) ?: fallbackChoreoRecipeId(v1)
        val effectiveNoStem = input.preferNoStemVariant || !input.stemsExpected || v1.requiredStems.isEmpty()

        // Try matching LOOP geometry via candidate builder only when aligned to PairPlanner's frozen window.
        if (!input.preferStraight && v1.geometry == TransitionGeometry.LOOP) {
            val crown = LoopMix.plan(
                out = outA,
                outDuration = outDur,
                inn = inA,
                inDuration = inDur,
                earliestOutSec = (exitSec - barSec).coerceAtLeast(input.positionSec + 0.5),
                sungOut = input.sungOut,
                sungIn = input.sungIn,
                playableOutDurationSec = outDur,
            )
            if (crown != null && abs(crown.out.loopStart - exitSec) <= 1.5 * barSec &&
                abs(crown.inn.loopStart - entrySec) <= 2.0 * barSec
            ) {
                val stamped = stamp(crown, outA, inA)
                val prep = ChoreoSessionGlue.prepare(
                    plan = stamped,
                    script = null,
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = input.outSongMap,
                    inSongMap = input.inSongMap,
                    level = input.djLevel,
                    outId = input.outTrack.id,
                    inId = input.inTrack.id,
                    sampleRate = input.sampleRate,
                    albumOrder = input.albumOrder,
                    stemsExpected = !effectiveNoStem,
                    excludedRecipes = input.excludedRecipes,
                    capsOverride = input.caps,
                )?.let { base ->
                    ChoreoSessionGlue.Prep(
                        plan = base.plan,
                        forced = base.forced,
                        forcedRecipe = requestedRecipe,
                        caps = base.caps,
                        level = base.level,
                        outStructure = base.outStructure,
                        inStructure = base.inStructure,
                        pairKey = base.pairKey,
                        albumOrder = base.albumOrder,
                        note = base.note,
                        sampleRate = base.sampleRate,
                        straightRateMode = base.straightRateMode,
                        excludedRecipes = base.excludedRecipes,
                    )
                }
                if (prep != null && prep.plan.handoff == null) {
                    return SelectedPlan(
                        plan = prep.plan,
                        phraseScript = null,
                        v2Prep = prep,
                        exitWindowSec = prep.plan.out.loopStart..(prep.plan.out.loopStart + LoopMix.Clock(prep.plan).releaseAt),
                        entryCueSec = prep.plan.inn.loopStart,
                        pathHint = "v2-native",
                        transitionPlanV1 = alignV1Timing(v1, prep.plan),
                    )
                }
            }
        }

        // Try matching BRIDGE geometry via PhrasePlanner / LoopMix.bridge only when aligned to PairPlanner's window.
        if (!input.preferStraight && v1.geometry == TransitionGeometry.BRIDGE) {
            val steps = regionalCamelotSteps(outA, inA)
            val phraseChoice = if (PhraseConfig.PHRASE_PLANNER_ENABLED && !shouldDeclineToMix(outA, inA)) {
                runCatching {
                    PhrasePlanner.select(
                        input.outSongMap,
                        input.inSongMap,
                        steps,
                        outA.bpm,
                        inA.bpm,
                        earliestExitSec = (exitSec - barSec).coerceAtLeast(input.positionSec + 0.5),
                        playableOutDurationSec = outDur,
                        outAnalysis = outA,
                        inAnalysis = inA,
                    )
                }.getOrNull()
            } else null
            if (phraseChoice != null) {
                val script = TransitionMoves.compile(
                    phraseChoice, outA.bpm, inA.bpm,
                    outgoingId = input.outTrack.id,
                    incomingId = input.inTrack.id,
                )
                if (abs(script.plan.out.loopStart - exitSec) <= 1.5 * barSec &&
                    abs(script.plan.inn.loopStart - entrySec) <= 2.0 * barSec
                ) {
                    val stamped = stamp(script.plan, outA, inA)
                    val base = ChoreoSessionGlue.prepare(
                        plan = stamped,
                        script = script,
                        outAnalysis = outA,
                        inAnalysis = inA,
                        outSongMap = input.outSongMap,
                        inSongMap = input.inSongMap,
                        level = input.djLevel,
                        outId = input.outTrack.id,
                        inId = input.inTrack.id,
                        sampleRate = input.sampleRate,
                        albumOrder = input.albumOrder,
                        stemsExpected = !effectiveNoStem,
                        excludedRecipes = input.excludedRecipes,
                        capsOverride = input.caps,
                    )
                    if (base != null && base.plan.handoff == null) {
                        val prep = ChoreoSessionGlue.Prep(
                            plan = base.plan,
                            forced = base.forced,
                            forcedRecipe = requestedRecipe,
                            caps = base.caps,
                            level = base.level,
                            outStructure = base.outStructure,
                            inStructure = base.inStructure,
                            pairKey = base.pairKey,
                            albumOrder = base.albumOrder,
                            note = base.note,
                            sampleRate = base.sampleRate,
                            straightRateMode = base.straightRateMode,
                            excludedRecipes = base.excludedRecipes,
                        )
                        return SelectedPlan(
                            plan = prep.plan,
                            phraseScript = script,
                            v2Prep = prep,
                            exitWindowSec = prep.plan.out.loopStart..(prep.plan.out.loopStart + LoopMix.Clock(prep.plan).releaseAt),
                            entryCueSec = prep.plan.inn.loopStart,
                            pathHint = "v2-native",
                            transitionPlanV1 = alignV1Timing(v1, prep.plan),
                        )
                    }
                }
            }
            val bridge = LoopMix.bridge(
                out = outA,
                outDuration = outDur,
                inn = inA,
                inDuration = inDur,
                earliestOutSec = (exitSec - barSec).coerceAtLeast(input.positionSec + 0.5),
                sungOut = input.sungOut,
                sungIn = input.sungIn,
                playableOutDurationSec = outDur,
            )
            if (bridge != null && bridge.handoff == null &&
                abs(bridge.out.loopStart - exitSec) <= 1.5 * barSec &&
                abs(bridge.inn.loopStart - entrySec) <= 2.0 * barSec
            ) {
                val stamped = stamp(bridge, outA, inA)
                val base = ChoreoSessionGlue.prepare(
                    plan = stamped,
                    script = null,
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = input.outSongMap,
                    inSongMap = input.inSongMap,
                    level = input.djLevel,
                    outId = input.outTrack.id,
                    inId = input.inTrack.id,
                    sampleRate = input.sampleRate,
                    albumOrder = input.albumOrder,
                    stemsExpected = !effectiveNoStem,
                    excludedRecipes = input.excludedRecipes,
                    capsOverride = input.caps,
                )
                if (base != null) {
                    val prep = ChoreoSessionGlue.Prep(
                        plan = base.plan,
                        forced = base.forced,
                        forcedRecipe = requestedRecipe,
                        caps = base.caps,
                        level = base.level,
                        outStructure = base.outStructure,
                        inStructure = base.inStructure,
                        pairKey = base.pairKey,
                        albumOrder = base.albumOrder,
                        note = base.note,
                        sampleRate = base.sampleRate,
                        straightRateMode = base.straightRateMode,
                        excludedRecipes = base.excludedRecipes,
                    )
                    return SelectedPlan(
                        plan = prep.plan,
                        phraseScript = null,
                        v2Prep = prep,
                        exitWindowSec = prep.plan.out.loopStart..(prep.plan.out.loopStart + LoopMix.Clock(prep.plan).releaseAt),
                        entryCueSec = prep.plan.inn.loopStart,
                        pathHint = "v2-native",
                        transitionPlanV1 = alignV1Timing(v1, prep.plan),
                    )
                }
            }
        }

        // Anchored straight geometry preserving PairPlanner's {frozenExitMs, frozenEntryMs, recipe}.
        return buildStraightFromV1(v1, input, outDur, inDur, overlapBars, requestedRecipe)
            ?: straightFallback(input)?.copy(transitionPlanV1 = v1)
    }

    private fun buildStraightFromV1(
        v1: TransitionPlanV1,
        input: PlanInput,
        outDur: Double,
        inDur: Double,
        requestedBars: Int,
        requestedRecipe: RecipeId,
    ): SelectedPlan? {
        val outA = input.outAnalysis
        val inA = input.inAnalysis
        val outBpm = outA.tailBeats?.bpm?.takeIf { it.isFinite() && it > 1.0 }
            ?: outA.bpm.takeIf { it.isFinite() && it > 1.0 } ?: 120.0
        val inBpm = inA.headBeats?.bpm?.takeIf { it.isFinite() && it > 1.0 }
            ?: inA.bpm.takeIf { it.isFinite() && it > 1.0 } ?: outBpm
        val rateMode = StraightMixGeometry.rateMode(outA, inA, input.djLevel)
        val folded = alignTempoOctave(outBpm, inBpm)
        val fold = if (rateMode == StraightRateMode.BEAT_MATCH) inBpm / folded else 1.0
        val exitSec = (v1.frozenExitMs / 1000.0).coerceAtLeast(0.0)
        val entrySec = (v1.frozenEntryMs / 1000.0).coerceAtLeast(0.0)
        val outStruct = StructureView.best(input.outSongMap, outA)
        val inStruct = StructureView.best(input.inSongMap, inA)
        val recipeForStraight = if (requestedRecipe == RecipeId.C5) {
            RecipeId.C2
        } else requestedRecipe

        var bars = requestedBars.coerceIn(1, 16)
        var fitted: LoopMix.Plan? = null
        while (bars >= 1) {
            val enter = if (bars >= 4) 1 else 0
            val exit = if (bars >= 2) 1 else 0
            val cand = LoopMix.Plan(
                out = LoopMix.Pick(exitSec, exitSec, 0, 60.0 / outBpm, outBpm, 0.0, LoopMix.Check.MASK),
                inn = LoopMix.Pick(entrySec, entrySec, 0, 60.0 / inBpm, inBpm, 0.0, LoopMix.Check.MASK),
                fold = fold,
                enterBars = enter,
                rampBars = bars - enter - exit,
                exitBars = exit,
                totalBars = bars,
                outLoops = false,
                inLoops = false,
                nativeIncoming = rateMode == StraightRateMode.RATE_ONE,
                targetBpmOverride = if (rateMode == StraightRateMode.RATE_ONE) 0.0 else null,
                rung = v1.recipeName.ifBlank { "Straight" },
                alternativeKind = "v2-straight",
            )
            val span = LoopMix.Clock(cand).releaseAt
            val req = runCatching {
                LoopMixSession.decodeRequirements(cand, input.sampleRate, outA, inA, rateMode, input.djLevel)
            }.getOrNull()
            if (req != null && exitSec + span <= outDur + 0.5 && entrySec < inDur) {
                fitted = cand
                break
            }
            bars--
        }
        val placed = fitted ?: return null
        val stamped = stamp(placed, outA, inA)
        val prep = ChoreoSessionGlue.Prep(
            plan = stamped,
            forced = null,
            forcedRecipe = recipeForStraight,
            caps = input.caps,
            level = input.djLevel,
            outStructure = outStruct,
            inStructure = inStruct,
            pairKey = "${input.outTrack.id}->${input.inTrack.id}",
            albumOrder = input.albumOrder,
            note = "core-planner:${v1.recipeId}:${rateMode.name.lowercase()}",
            sampleRate = input.sampleRate,
            straightRateMode = rateMode,
            excludedRecipes = input.excludedRecipes,
        )
        val previewScript = ChoreoSessionGlue.withoutStems(
            prep, stamped, LoopMix.Clock(stamped), input.sampleRate, outA, inA,
            input.sungOut, input.sungIn, recipeForStraight,
        ) ?: ChoreoSessionGlue.withoutStems(
            prep, stamped, LoopMix.Clock(stamped), input.sampleRate, outA, inA,
            input.sungOut, input.sungIn, null,
        ) ?: return null
        val span = LoopMix.Clock(stamped).releaseAt
        val alignedV1 = v1.copy(
            frozenExitMs = (stamped.out.loopStart * 1000.0).roundToLong(),
            frozenEntryMs = (stamped.inn.loopStart * 1000.0).roundToLong(),
            overlapDurationMs = (span * 1000.0).roundToLong().coerceAtLeast(500L),
        )
        return SelectedPlan(
            plan = stamped,
            v2Prep = prep,
            entryCueSec = stamped.inn.loopStart,
            exitWindowSec = stamped.out.loopStart..(stamped.out.loopStart + span),
            pathHint = "v2-straight",
            straightRateMode = rateMode,
            fallbackReason = if (v1.isLastResort) "last-resort-musical-exit" else "",
            previewScript = previewScript,
            transitionPlanV1 = alignedV1,
        )
    }

    private fun alignV1Timing(v1: TransitionPlanV1, plan: LoopMix.Plan): TransitionPlanV1 {
        val clock = LoopMix.Clock(plan)
        val exitMs = (plan.out.loopStart * 1000.0).roundToLong().coerceAtLeast(0L)
        val entryMs = (plan.inn.loopStart * 1000.0).roundToLong().coerceAtLeast(0L)
        val overlapMs = (clock.releaseAt * 1000.0).roundToLong().coerceAtLeast(1L)
        return v1.copy(
            frozenExitMs = exitMs,
            frozenEntryMs = entryMs,
            swapDownbeatMs = v1.swapDownbeatMs.coerceIn(exitMs, exitMs + overlapMs),
            overlapDurationMs = overlapMs,
        )
    }

    private fun fallbackChoreoRecipeId(v1: TransitionPlanV1): RecipeId = when (v1.recipeId) {
        "S6" -> RecipeId.S2
        "C8" -> RecipeId.C2
        "C9" -> RecipeId.C1
        "F9" -> RecipeId.F7
        "F10" -> RecipeId.F1
        "F11" -> RecipeId.F5
        else -> when (v1.djLevel) {
            CoreDjLevel.SUBTLE -> RecipeId.S4
            CoreDjLevel.CLUB -> RecipeId.C2
            CoreDjLevel.FESTIVAL -> RecipeId.F5
        }
    }

    private fun stamp(plan: LoopMix.Plan, out: TrackAnalysis, inn: TrackAnalysis): LoopMix.Plan {
        val steps = regionalCamelotSteps(out, inn)
        val left = camelotCode(exitKeyForMix(out))
        val right = camelotCode(entryKeyForMix(inn))
        val keys = if (left != null && right != null) "$left→$right" else plan.keys
        val outConf = exitKeyConfidence(out)
        val inConf = entryKeyConfidence(inn)
        val keyClash = steps != null && steps > 2 && outConf >= 0.5 && inConf >= 0.5
        val clash = plan.clash || (plan.rung != "Echo" && keyClash)
        val carve = plan.carve || (plan.rung == "Beat blend" && keyClash)
        return plan.copy(clash = clash, carve = carve, keys = keys)
    }
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
    )
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
            beatsMs = beats,
            downbeatsMs = if (v3.downbeatsMs.isEmpty()) downs else v3.downbeatsMs,
        )
    }
    return v3
}
