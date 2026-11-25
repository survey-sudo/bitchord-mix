package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.recipes.RecipeRegistry
import com.music.bitchord.playback.loopmix.PlaybackDeckGeometry
import com.music.bitchord.playback.loopmix.PlaybackGeometryQualification
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhrasePlanner
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.alignTempoOctave
import com.music.bitchord.playback.smart.camelotCode
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.choreo.PlaybackChoreoPreparation
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

/** The production concrete-plan builder for an already frozen core planner decision. */
object PlaybackPlanConcretizer {
    data class Input(
        val outId: String,
        val inId: String,
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
        val caps: Int = 0,
        val preferNoStemVariant: Boolean = false,
    )

    data class ConcretePlan(
        val plan: LoopMix.Plan?,
        val phraseScript: PhraseScript? = null,
        val v2Prep: PlaybackChoreoPreparation.Prep? = null,
        val exitWindowSec: ClosedFloatingPointRange<Double>? = null,
        val entryCueSec: Double? = null,
        val pathHint: String = "v2-native",
        val fallbackReason: String = "",
        val straightRateMode: StraightRateMode? = null,
        /** Optional legacy display preview; canonical execution still uses the frozen recipe. */
        val previewRecipe: RecipeId? = null,
        val transitionPlanV1: TransitionPlanV1? = null,
        val frozenRecipe: RecipeV2? = null,
        val refusalReason: RefusalReason? = null,
    )

    fun buildConcreteFromPlanV1(
        v1: TransitionPlanV1,
        input: Input,
        outDur: Double,
        inDur: Double,
        configuration: PlaybackChoreoPreparation.Configuration,
        history: PlaybackChoreoPreparation.History,
        onPreparationFailure: (Throwable) -> Unit = {},
    ): ConcretePlan? {
        val outA = input.outAnalysis
        val inA = input.inAnalysis
        val exitSec = v1.frozenExitMs / 1000.0
        val barSec = (v1.barDurationMs / 1000.0).coerceAtLeast(0.5)
        val overlapBars = (v1.overlapDurationMs.toDouble() / v1.barDurationMs.coerceAtLeast(500L))
            .roundToInt().coerceIn(1, 16)
        val requestedRecipe = RecipeId.fromCode(v1.recipeId)
        val frozenRecipe = RecipeRegistry.byId(v1.recipeId)
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
            if (crown != null && matchesFrozenSources(v1, crown, input)) {
                val stamped = stamp(crown, outA, inA)
                val prep = runCatching { PlaybackChoreoPreparation.prepare(
                    plan = stamped,
                    script = null,
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = input.outSongMap,
                    inSongMap = input.inSongMap,
                    level = input.djLevel,
                    outId = input.outId,
                    inId = input.inId,
                    sampleRate = input.sampleRate,
                    albumOrder = input.albumOrder,
                    stemsExpected = !effectiveNoStem,
                    excludedRecipes = input.excludedRecipes,
                    caps = input.caps,
                    configuration = configuration, history = history,
                ) }.onFailure(onPreparationFailure).getOrNull()?.let { base ->
                    PlaybackChoreoPreparation.Prep(
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
                if (prep != null && prep.plan.handoff == null && matchesFrozenSources(v1, prep.plan, input)) {
                    return ConcretePlan(
                        plan = prep.plan,
                        phraseScript = null,
                        v2Prep = prep,
                        exitWindowSec = prep.plan.out.loopStart..(prep.plan.out.loopStart + LoopMix.Clock(prep.plan).releaseAt),
                        entryCueSec = prep.plan.inn.loopStart,
                        pathHint = "v2-native",
                        transitionPlanV1 = freezeRuntimeRecipeParams(v1, prep.plan),
                        frozenRecipe = frozenRecipe,
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
                    outgoingId = input.outId,
                    incomingId = input.inId,
                )
                if (matchesFrozenSources(v1, script.plan, input)) {
                    val stamped = stamp(script.plan, outA, inA)
                    val base = runCatching { PlaybackChoreoPreparation.prepare(
                        plan = stamped,
                        script = script,
                        outAnalysis = outA,
                        inAnalysis = inA,
                        outSongMap = input.outSongMap,
                        inSongMap = input.inSongMap,
                        level = input.djLevel,
                        outId = input.outId,
                        inId = input.inId,
                        sampleRate = input.sampleRate,
                        albumOrder = input.albumOrder,
                        stemsExpected = !effectiveNoStem,
                        excludedRecipes = input.excludedRecipes,
                        caps = input.caps,
                        configuration = configuration, history = history,
                    ) }.onFailure(onPreparationFailure).getOrNull()
                    if (base != null && base.plan.handoff == null && matchesFrozenSources(v1, base.plan, input)) {
                        val prep = PlaybackChoreoPreparation.Prep(
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
                        return ConcretePlan(
                            plan = prep.plan,
                            phraseScript = script,
                            v2Prep = prep,
                            exitWindowSec = prep.plan.out.loopStart..(prep.plan.out.loopStart + LoopMix.Clock(prep.plan).releaseAt),
                            entryCueSec = prep.plan.inn.loopStart,
                            pathHint = "v2-native",
                            transitionPlanV1 = freezeRuntimeRecipeParams(v1, prep.plan),
                            frozenRecipe = frozenRecipe,
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
            if (bridge != null && bridge.handoff == null && matchesFrozenSources(v1, bridge, input)) {
                val stamped = stamp(bridge, outA, inA)
                val base = runCatching { PlaybackChoreoPreparation.prepare(
                    plan = stamped,
                    script = null,
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = input.outSongMap,
                    inSongMap = input.inSongMap,
                    level = input.djLevel,
                    outId = input.outId,
                    inId = input.inId,
                    sampleRate = input.sampleRate,
                    albumOrder = input.albumOrder,
                    stemsExpected = !effectiveNoStem,
                    excludedRecipes = input.excludedRecipes,
                    caps = input.caps,
                    configuration = configuration, history = history,
                ) }.onFailure(onPreparationFailure).getOrNull()
                if (base != null && matchesFrozenSources(v1, base.plan, input)) {
                    val prep = PlaybackChoreoPreparation.Prep(
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
                    return ConcretePlan(
                        plan = prep.plan,
                        phraseScript = null,
                        v2Prep = prep,
                        exitWindowSec = prep.plan.out.loopStart..(prep.plan.out.loopStart + LoopMix.Clock(prep.plan).releaseAt),
                        entryCueSec = prep.plan.inn.loopStart,
                        pathHint = "v2-native",
                        transitionPlanV1 = freezeRuntimeRecipeParams(v1, prep.plan),
                        frozenRecipe = frozenRecipe,
                    )
                }
            }
        }

        // A frozen loop/bridge cannot be replaced by a straight plan or a different recipe.
        return (if (v1.geometry == TransitionGeometry.STRAIGHT) {
            buildStraightFromV1(v1, input, outDur, inDur, overlapBars, requestedRecipe)
        } else null) ?: ConcretePlan(
            plan = null,
            transitionPlanV1 = v1,
            frozenRecipe = frozenRecipe,
            fallbackReason = "canonical_frozen_geometry_unavailable:${v1.recipeId}",
            refusalReason = RefusalReason.HANDOFF_INVALID_GEOMETRY,
        )
    }

    private fun buildStraightFromV1(
        v1: TransitionPlanV1,
        input: Input,
        outDur: Double,
        inDur: Double,
        requestedBars: Int,
        requestedRecipe: RecipeId?,
    ): ConcretePlan? {
        val outA = input.outAnalysis
        val inA = input.inAnalysis
        val outBpm = outA.tailBeats?.bpm?.takeIf { it.isFinite() && it > 1.0 }
            ?: outA.bpm.takeIf { it.isFinite() && it > 1.0 } ?: 120.0
        val inBpm = inA.headBeats?.bpm?.takeIf { it.isFinite() && it > 1.0 }
            ?: inA.bpm.takeIf { it.isFinite() && it > 1.0 } ?: outBpm
        val rateMode = StraightRatePolicy.rateMode(outA, inA, input.djLevel)
        val folded = alignTempoOctave(outBpm, inBpm)
        val fold = if (rateMode == StraightRateMode.BEAT_MATCH) inBpm / folded else 1.0
        val exitSec = (v1.frozenExitMs / 1000.0).coerceAtLeast(0.0)
        val entrySec = (v1.frozenEntryMs / 1000.0).coerceAtLeast(0.0)
        val outStruct = StructureView.best(input.outSongMap, outA)
        val inStruct = StructureView.best(input.inSongMap, inA)
        // The canonical planner recipe is authoritative. Do not silently replace a
        // newly-added recipe with an older shared-runtime alias (ADR 0011/R2).
        val recipeForStraight = requestedRecipe

        val bars = requestedBars
        if (bars !in 1..16 || v1.barDurationMs <= 0L ||
            v1.overlapDurationMs != bars * v1.barDurationMs
        ) return null
        // PairPlanner freezes wall-clock bars at OUT tempo. Keep that master clock
        // throughout the overlap; beat-match IN to it, then the existing IN settle
        // knots return to rate one. A tempo ramp would change the frozen release.
        val clockBpm = 240_000.0 / v1.barDurationMs
        val enter = if (bars >= 4) 1 else 0
        val exit = if (bars >= 2) 1 else 0
        val placed = LoopMix.Plan(
            out = LoopMix.Pick(exitSec, exitSec, 0, 60.0 / outBpm, clockBpm, 0.0, LoopMix.Check.MASK),
            inn = LoopMix.Pick(entrySec, entrySec, 0, 60.0 / inBpm, inBpm, 0.0, LoopMix.Check.MASK),
            fold = fold,
            enterBars = enter,
            rampBars = bars - enter - exit,
            exitBars = exit,
            totalBars = bars,
            outLoops = false,
            inLoops = false,
            nativeIncoming = rateMode == StraightRateMode.RATE_ONE,
            targetBpmOverride = clockBpm,
            incomingHoldSec = v1.recipeParams["incomingHoldSec"] ?: 0.0,
            rung = v1.recipeName.ifBlank { "Straight" },
            alternativeKind = "v2-straight",
        )
        val req = runCatching {
            PlaybackGeometryQualification.decodeRequirements(placed, input.sampleRate, outA, inA, rateMode, input.djLevel)
        }.getOrNull() ?: return null
        if (exitSec + v1.overlapDurationMs / 1000.0 > outDur + 0.5 ||
            entrySec >= inDur || req.inTo > inDur ||
            !matchesFrozenTiming(v1, placed, input, rateMode)
        ) return null
        val stamped = stamp(placed, outA, inA)
        if (!matchesFrozenSources(v1, stamped, input, rateMode)) return null
        val prep = PlaybackChoreoPreparation.Prep(
            plan = stamped,
            forced = null,
            forcedRecipe = recipeForStraight,
            caps = input.caps,
            level = input.djLevel,
            outStructure = outStruct,
            inStructure = inStruct,
            pairKey = "${input.outId}->${input.inId}",
            albumOrder = input.albumOrder,
            note = "core-planner:${v1.recipeId}:${rateMode.name.lowercase()}",
            sampleRate = input.sampleRate,
            straightRateMode = rateMode,
            excludedRecipes = input.excludedRecipes,
        )
        val span = LoopMix.Clock(stamped).releaseAt
        return ConcretePlan(
            plan = stamped,
            v2Prep = prep,
            entryCueSec = stamped.inn.loopStart,
            exitWindowSec = stamped.out.loopStart..(stamped.out.loopStart + span),
            pathHint = "v2-straight",
            straightRateMode = rateMode,
            fallbackReason = if (v1.isLastResort) "last-resort-musical-exit" else "",
            previewRecipe = recipeForStraight,
            transitionPlanV1 = freezeRuntimeRecipeParams(v1, stamped),
            frozenRecipe = RecipeRegistry.byId(v1.recipeId),
        )
    }

    /** Same source-to-millisecond projection as ScriptExecutionGuard, including tracked knots. */
    fun matchesFrozenSources(
        v1: TransitionPlanV1, plan: LoopMix.Plan, input: Input, rateMode: StraightRateMode? = null,
    ): Boolean = runCatching {
        val clock = LoopMix.Clock(plan)
        val outStop = clock.releaseAt + 0.05 +
            (if (plan.echoTail) plan.out.beatSec * 8 else 0.0) + plan.exitTailSeconds
        val tailRate = if (clock.beats > 0) {
            (plan.out.beatSec / (clock.beatTimes[clock.beats] - clock.beatTimes[clock.beats - 1])
                .coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        val knots = PlaybackDeckGeometry.buildDeckKnots(
            plan, clock, input.sampleRate, input.outAnalysis, input.inAnalysis,
            !plan.outLoops && outStop > clock.releaseAt + 1e-6, outStop, tailRate, rateMode, input.djLevel,
        )
        (knots.outPositionsSec.first() * 1000.0).roundToLong() == v1.frozenExitMs &&
            (knots.inPositionsSec.first() * 1000.0).roundToLong() == v1.frozenEntryMs
    }.getOrDefault(false)

    /** Validate before PlanFrozen: no shortened bars, shifted hold, or incompatible knot clock. */
    fun matchesFrozenTiming(
        v1: TransitionPlanV1, plan: LoopMix.Plan, input: Input, rateMode: StraightRateMode,
    ): Boolean = runCatching {
        val clock = LoopMix.Clock(plan)
        val release = v1.overlapDurationMs / 1000.0
        if (v1.barDurationMs <= 0L || v1.overlapDurationMs <= 0L ||
            abs(clock.releaseAt - release) > 1e-9 ||
            abs(clock.incomingHoldSec - (v1.recipeParams["incomingHoldSec"] ?: 0.0)) > 1e-9 ||
            !clock.incomingHoldSec.isFinite() || clock.incomingHoldSec !in 0.0..release
        ) return@runCatching false
        // Building the actual straight knots enforces the existing DJ-level rate
        // limits, including the post-overlap settle, before the plan is published.
        val knots = PlaybackDeckGeometry.buildDeckKnots(
            plan, clock, input.sampleRate, input.outAnalysis, input.inAnalysis,
            true, release + 0.05 + plan.exitTailSeconds, 1.0, rateMode, input.djLevel,
        )
        clock.beatTimes.all { it.isFinite() } &&
            abs(knots.inFrames.last() / input.sampleRate - clock.elementAt) <= 1e-9 &&
            matchesFrozenSources(v1, plan, input, rateMode)
    }.getOrDefault(false)

    fun stamp(plan: LoopMix.Plan, out: TrackAnalysis, inn: TrackAnalysis): LoopMix.Plan {
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

    fun freezeRuntimeRecipeParams(v1: TransitionPlanV1, plan: LoopMix.Plan): TransitionPlanV1 =
        v1.copy(recipeParams = v1.recipeParams + ("incomingHoldSec" to plan.incomingHoldSec))
}
