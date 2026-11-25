package com.music.bitchord.playback.djmix

import com.music.bitchord.playback.loopmix.DeckKnots
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.StemSet
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.AutomixV2Choreo
import com.music.bitchord.playback.smart.choreo.Candidate
import com.music.bitchord.playback.smart.choreo.ChoreoInputs
import com.music.bitchord.playback.smart.choreo.ChoreoScript
import com.music.bitchord.playback.smart.choreo.ChoreoRendererBridge
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.choreo.Choreographer
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.choreo.RecipeId
import com.music.bitchord.playback.smart.choreo.StemAdapter
import com.music.bitchord.playback.smart.choreo.StemView
import com.music.bitchord.playback.smart.choreo.StructureView
import com.music.bitchord.playback.smart.choreo.TrackClock
import com.music.bitchord.djmix.core.contracts.FxCapability
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.RenderGeometryV1
import com.music.bitchord.djmix.core.contracts.StemRegionRef
import com.music.bitchord.djmix.core.recipes.RecipeInterpreter
import com.music.bitchord.djmix.core.recipes.ScriptExecutionGuard

/**
 * Pure choreography script construction and knot interpolation helpers extracted from [PairPreparer]
 * (M9 slice P-4).
 */
internal object ChoreoScriptBuilder {

    internal data class CanonicalCompilation(
        val recipe: com.music.bitchord.djmix.core.contracts.RecipeV2,
        val geometry: com.music.bitchord.djmix.core.contracts.RenderGeometryV1,
        val script: TransitionScriptV1,
    )

    internal fun canonicalRegionRefs(
        outId: String, inId: String, out: LoopRegion, inn: LoopRegion, sampleRate: Int,
        geometry: RenderGeometryV1,
    ): Pair<AudioRegionRef, AudioRegionRef> =
        CanonicalGeometryBuilder.canonicalRegionRefs(outId, inId, out, inn, sampleRate, geometry)

    internal fun canonicalStemRefs(
        trackId: String, deck: DeckTarget, region: LoopRegion, stems: StemSet?, sampleRate: Int,
    ): List<StemRegionRef> = CanonicalGeometryBuilder.canonicalStemRefs(trackId, deck, region, stems, sampleRate)

    internal fun canonicalGeometry(
        plan: LoopMix.Plan, clock: LoopMix.Clock, knots: DeckKnots,
        out: LoopRegion, inn: LoopRegion, sampleRate: Int, inLevel: Float,
        highQuality: Boolean, outStopAtSec: Double,
    ): RenderGeometryV1 = CanonicalGeometryBuilder.canonicalGeometry(
        plan, clock, knots, out, inn, sampleRate, inLevel, highQuality, outStopAtSec,
    )

    internal fun installCanonicalScript(renderer: LoopMixRenderer, script: TransitionScriptV1) {
        ChoreoRendererBridge.apply(renderer, script)
    }

    /** Execution telemetry comes from installed canonical FX, never a legacy preview. */
    internal fun canonicalFxNames(script: TransitionScriptV1): List<String> = with(script.fxParams) {
        listOfNotNull(
            "Loop".takeIf { loop?.enabled == true }, "Roll".takeIf { roll?.enabled == true },
            "Sweep".takeIf { sweep?.enabled == true || incomingSweep?.enabled == true },
            "Echo".takeIf { echo?.enabled == true }, "Reverb".takeIf { reverb?.enabled == true },
            "Flanger".takeIf { flanger?.enabled == true }, "Phaser".takeIf { phaser?.enabled == true },
            "Riser".takeIf { riser?.enabled == true }, "Impact".takeIf { impact?.enabled == true },
            "Gate".takeIf { gate?.enabled == true }, "Pump".takeIf { pump?.enabled == true },
            "Brake".takeIf { brake?.enabled == true }, "TapeStop".takeIf { tapeStop?.enabled == true },
            "Spinback".takeIf { spinback?.enabled == true },
        )
    }

    /**
     * Compiles the frozen registry recipe against the exact prepared deck knots.
     * The legacy [ChoreoScript] remains a display/compatibility view only; it is
     * never used to regenerate canonical geometry after this point.
     */
    internal fun compileCanonical(
        planV1: TransitionPlanV1,
        frozenRecipe: com.music.bitchord.djmix.core.contracts.RecipeV2?,
        effectivePlan: LoopMix.Plan,
        outRegion: com.music.bitchord.playback.loopmix.LoopRegion,
        inRegion: com.music.bitchord.playback.loopmix.LoopRegion,
        outStems: StemSet?,
        inStems: StemSet?,
        sampleRate: Int,
        caps: Int,
        outAnalysis: TrackAnalysis? = null,
        inAnalysis: TrackAnalysis? = null,
        straightRateMode: StraightRateMode? = null,
        level: DjLevel = DjLevel.SUBTLE,
        highQuality: Boolean = true,
        inLevel: Float = 1f,
        useNoStemVariant: Boolean = false,
        preparedScript: TransitionScriptV1? = null,
        preparedGeometry: RenderGeometryV1? = null,
    ): MixOutcome<CanonicalCompilation> {
        val recipe = frozenRecipe ?: return MixOutcome.Refused(
            com.music.bitchord.djmix.core.contracts.RefusalReason.CHOREOGRAPHY_UNAVAILABLE,
            "frozen_recipe_missing:${planV1.recipeId}",
        )
        if (recipe.id != planV1.recipeId) return MixOutcome.Refused(
            com.music.bitchord.djmix.core.contracts.RefusalReason.CHOREOGRAPHY_UNAVAILABLE,
            "frozen_recipe_id_mismatch",
        )
        val clock = LoopMix.Clock(effectivePlan)
        val echoHold = if (effectivePlan.echoTail) effectivePlan.out.beatSec * 8 else 0.0
        val outStop = clock.releaseAt + 0.05 + echoHold + effectivePlan.exitTailSeconds
        val tailRate = if (clock.beats >= 1) {
            val b0 = clock.beatTimes[clock.beats - 1]
            val b1 = clock.beatTimes[clock.beats]
            (effectivePlan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        val knots = LoopMixSession.buildDeckKnots(
            effectivePlan, clock, sampleRate, outAnalysis, inAnalysis,
            !effectivePlan.outLoops && outStop > clock.releaseAt + 1e-6,
            outStop, tailRate, straightRateMode, level,
        )
        val geometry = canonicalGeometry(
            effectivePlan, clock, knots, outRegion, inRegion, sampleRate,
            inLevel, highQuality, outStop,
        )
        val (outRef, inRef) = canonicalRegionRefs(
            planV1.outTrackId, planV1.inTrackId, outRegion, inRegion, sampleRate, geometry,
        )
        val stems = canonicalStemRefs(
            planV1.outTrackId, com.music.bitchord.djmix.core.contracts.DeckTarget.OUT,
            outRegion, outStems, sampleRate,
        ) + canonicalStemRefs(
            planV1.inTrackId, com.music.bitchord.djmix.core.contracts.DeckTarget.IN,
            inRegion, inStems, sampleRate,
        )
        val frozenPlan = planV1
        preparedScript?.let { frozenScript ->
            if (preparedGeometry != geometry || frozenScript.renderGeometry != geometry ||
                frozenScript.planId != frozenPlan.planId || frozenScript.generation != frozenPlan.generation ||
                frozenScript.outRegion != outRef || frozenScript.inRegion != inRef ||
                (useNoStemVariant && frozenScript.stems.isNotEmpty()) ||
                (!useNoStemVariant && frozenScript.stems != stems)
            ) return MixOutcome.Refused(
                com.music.bitchord.djmix.core.contracts.RefusalReason.HANDOFF_INVALID_GEOMETRY,
                "prepared_canonical_script_mismatch",
            )
            return when (val guarded = ScriptExecutionGuard.validateFrozen(
                frozenScript, frozenPlan, FxCapability.fromMask(caps),
            )) {
                is MixOutcome.Ok -> MixOutcome.Ok(CanonicalCompilation(recipe, geometry, guarded.value))
                is MixOutcome.Refused -> guarded
                is MixOutcome.Failed -> guarded
            }
        }
        return when (val outcome = RecipeInterpreter.compileFrozen(
            recipe = recipe,
            plan = frozenPlan,
            geometry = geometry,
            outRegion = outRef,
            inRegion = inRef,
            stems = stems,
            useNoStemVariant = useNoStemVariant,
            availableCapabilities = FxCapability.fromMask(caps),
        )) {
            is MixOutcome.Ok -> MixOutcome.Ok(CanonicalCompilation(recipe, geometry, outcome.value))
            is MixOutcome.Refused -> outcome
            is MixOutcome.Failed -> outcome
        }
    }

    /** Picks the executable [ChoreoScript] and top-3 ranked [Candidate] list from final stem state. */
    internal fun chooseFinalScript(
        effectivePlan: LoopMix.Plan?,
        v2Prep: ChoreoSessionGlue.Prep?,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
        outStems: StemSet?,
        inStems: StemSet?,
        sampleRate: Int,
        caps: Int,
        level: DjLevel,
        outId: String,
        inId: String,
        albumOrder: Boolean,
        preferredScript: ChoreoScript? = null,
        frozenRecipeOnly: Boolean = false,
        frozenRecipeId: String? = null,
    ): Pair<ChoreoScript?, List<Candidate>> {
        if (effectivePlan == null || v2Prep == null) return null to emptyList()
        if (frozenRecipeOnly) {
            val frozen = preferredScript?.takeIf {
                it.level == level && (frozenRecipeId == null || it.recipeId.equals(frozenRecipeId, ignoreCase = true))
            }
            return frozen to frozen?.topCandidates.orEmpty()
        }
        val inputs = buildChoreoInputs(
            effectivePlan = effectivePlan,
            v2Prep = v2Prep,
            outA = outA,
            inA = inA,
            sungOut = sungOut,
            sungIn = sungIn,
            outStems = StemAdapter.view(outStems),
            inStems = StemAdapter.view(inStems),
            sampleRate = sampleRate,
            caps = caps,
            level = level,
            outId = outId,
            inId = inId,
            albumOrder = albumOrder,
            stemsExpected = outStems != null || inStems != null,
        )
        val ranked = Choreographer.rank(inputs).take(3)
        // Keep PREPARED identical to what will execute: the selector already validated this exact
        // script against this geometry and rate mode. Re-choosing here could hand the build a
        // different recipe than the one the preview promised.
        val planned = preferredScript?.takeIf {
            it.level == level && RecipeId.fromCode(it.recipeId) !in v2Prep.excludedRecipes &&
                (!it.outUsesStems || outStems != null) && (!it.inUsesStems || inStems != null)
        }
        val script = planned
            ?: (if (v2Prep.forcedRecipe != null) Choreographer.chooseRecipe(inputs, v2Prep.forcedRecipe) else null)
            ?: Choreographer.choose(inputs, v2Prep.forced)
            ?: Choreographer.chooseRecipe(inputs, null)
            ?: ChoreoSessionGlue.withoutStems(
                v2Prep, effectivePlan, LoopMix.Clock(effectivePlan), sampleRate,
                outA, inA, sungOut, sungIn, plannedRecipe = v2Prep.forcedRecipe,
            )
        return script to (script?.topCandidates ?: ranked)
    }

    /** Builds [ChoreoInputs] including mix-to-track clocks derived from the session deck knots. */
    internal fun buildChoreoInputs(
        effectivePlan: LoopMix.Plan,
        v2Prep: ChoreoSessionGlue.Prep?,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
        outStems: StemView?,
        inStems: StemView?,
        sampleRate: Int,
        caps: Int,
        level: DjLevel,
        outId: String,
        inId: String,
        albumOrder: Boolean,
        stemsExpected: Boolean,
        excludedRecipes: Set<RecipeId> = v2Prep?.excludedRecipes ?: emptySet(),
    ): ChoreoInputs {
        val clock = LoopMix.Clock(effectivePlan)
        val echoHold = if (effectivePlan.echoTail) effectivePlan.out.beatSec * 8 else 0.0
        val outStop = clock.releaseAt + 0.05 + echoHold + effectivePlan.exitTailSeconds
        val tailRate = if (clock.beats >= 1) {
            val b0 = clock.beatTimes[clock.beats - 1]
            val b1 = clock.beatTimes[clock.beats]
            (effectivePlan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        val outHasTailKnot = !effectivePlan.outLoops && outStop > clock.releaseAt + 1e-6
        val pairKey = "$outId->$inId"
        val outStruct = v2Prep?.outStructure ?: StructureView.best(null, outA)
        val inStruct = v2Prep?.inStructure ?: StructureView.best(null, inA)
        val (outClock, inClock) = runCatching {
            val knots = LoopMixSession.buildDeckKnots(
                effectivePlan, clock, sampleRate, outA, inA, outHasTailKnot, outStop, tailRate,
                v2Prep?.straightRateMode, level,
            )
            val geometry = LoopMixSession.incomingGeometry(effectivePlan, clock, knots)
            val sr = sampleRate.toDouble()
            val outTrk = TrackClock { mix ->
                if (mix < 0.0) null
                else interpolateKnots(knots.outFrames, knots.outPositionsSec, mix * sr)?.let { u ->
                    if (effectivePlan.outLoops) LoopMix.physicalTrackTime(u, effectivePlan.out.loopStart, effectivePlan.out.loopEnd, 1e18) else u
                }
            }
            val inTrk = TrackClock { mix ->
                if (mix < 0.0) null
                else interpolateKnots(knots.inFrames, knots.inPositionsSec, mix * sr)?.let { u ->
                    if (geometry.loops) LoopMix.physicalTrackTime(u, effectivePlan.inn.loopStart, effectivePlan.inn.loopEnd, geometry.releaseTrack) else u
                }
            }
            outTrk to inTrk
        }.getOrDefault(null to null)

        return ChoreoInputs(
            plan = effectivePlan,
            clock = clock,
            out = outA,
            inn = inA,
            outStructure = outStruct,
            inStructure = inStruct,
            outStems = outStems,
            inStems = inStems,
            caps = caps,
            level = level,
            pairKey = pairKey,
            history = AutomixV2Choreo.history.recent(pairKey),
            recipeHistory = AutomixV2Choreo.history.recentRecipes(pairKey),
            seed = AutomixV2Choreo.seed,
            sungOut = sungOut,
            sungIn = sungIn,
            albumOrder = albumOrder,
            stemsExpected = stemsExpected,
            outClock = outClock,
            inClock = inClock,
            excludedRecipes = excludedRecipes,
        )
    }

    /** Placeholder 4-stem view used during dry-run choreography stem-requirement probing. */
    internal fun dummyStemView(sampleRate: Int): StemView =
        StemView(
            stemCount = 4,
            confidence = 1f,
            regionStartSec = 0.0,
            sampleRate = sampleRate,
            frames = sampleRate * 60,
            rms = { _, _, _ -> 0.5 },
        )

    /** Linearly interpolates (and linearly extrapolates past ends) a frame-to-position knot curve. */
    internal fun interpolateKnots(frames: DoubleArray, positions: DoubleArray, frame: Double): Double? {
        if (frames.isEmpty() || frames.size != positions.size) return null
        if (frame <= frames[0]) return positions[0]
        val last = frames.size - 1
        if (frame >= frames[last]) {
            val denom = maxOf(1.0, frames[last] - frames[maxOf(0, last - 1)])
            return positions[last] + (frame - frames[last]) / denom *
                (positions[last] - positions[maxOf(0, last - 1)])
        }
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (frames[mid] <= frame) lo = mid else hi = mid
        }
        val span = frames[hi] - frames[lo]
        val f = if (span <= 0.0) 0.0 else (frame - frames[lo]) / span
        return positions[lo] + (positions[hi] - positions[lo]) * f
    }
}
