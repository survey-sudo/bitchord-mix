package com.music.bitchord.playback.smart.choreo

import android.util.Log
import com.music.bitchord.djmix.core.contracts.FxCapability
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.RenderGeometryV1
import com.music.bitchord.playback.djmix.StraightRateMode
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.recipes.RecipeInterpreter
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.DeckKnots
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.StemSet
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlin.math.abs

/**
 * AUTOMIX-V2(E): LoopMixSession <-> choreographer glue. Two passes:
 *
 *  1. [prepare] (before decode): caps, structure views, and the optional
 *     drop re-time of a straight native incoming cue (EDM_BUILD_DROP). The
 *     outgoing geometry is never touched. The re-time is verified against the
 *     real deck knots ([LoopMixSession.buildDeckKnots]) so the drop lands on
 *     the beat the renderer will actually play.
 *  2. [choreograph] (after decode): pure CPU choreography on pre-separated
 *     stems ([preparedOutStems]/[preparedInStems]) or [preparedScript] with
 *     zero ONNX inference at build time, falling back cleanly when stems are
 *     not ready.
 *
 * A null result is a refusal, not an executed v2 transition. Production builders
 * must not leave legacy envelopes installed and report v2 success.
 */
object ChoreoSessionGlue {
    private const val TAG = "BitChordChoreo"

    class Prep(
        val plan: LoopMix.Plan,
        val forced: Move?,
        val forcedRecipe: RecipeId? = null,
        val caps: Int,
        val level: DjLevel,
        val outStructure: StructureView,
        val inStructure: StructureView,
        val pairKey: String,
        val albumOrder: Boolean,
        val note: String,
        val sampleRate: Int = 0,
        val straightRateMode: StraightRateMode? = null,
        val excludedRecipes: Set<RecipeId> = emptySet(),
    )

    class Result(
        val script: ChoreoScript,
        val outStems: StemSet?,
        val inStems: StemSet?,
    )

    fun prepare(
        plan: LoopMix.Plan,
        script: PhraseScript?,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
        outSongMap: SongMap?,
        inSongMap: SongMap?,
        level: DjLevel,
        outId: String,
        inId: String,
        sampleRate: Int,
        albumOrder: Boolean,
        stemsExpected: Boolean,
        straightRateMode: StraightRateMode? = null,
        excludedRecipes: Set<RecipeId> = emptySet(),
        capsOverride: Int? = null,
    ): Prep? = runCatching {
        val configuration = preparationConfiguration()
        if (!configuration.enabled) return null
        if (script != null && !configuration.overridePhraseScripts) return null
        val caps = capsOverride ?: ChoreoRendererBridge.capabilities()
        PlaybackChoreoPreparation.prepare(
            plan = plan, script = script, outAnalysis = outAnalysis, inAnalysis = inAnalysis,
            outSongMap = outSongMap, inSongMap = inSongMap, level = level,
            outId = outId, inId = inId, sampleRate = sampleRate, albumOrder = albumOrder,
            stemsExpected = stemsExpected, straightRateMode = straightRateMode,
            excludedRecipes = excludedRecipes, caps = caps,
            configuration = configuration, history = preparationHistory("$outId->$inId"),
        )?.let(::fromSharedPreparation)
    }.onFailure(::recordPreparationFailure).getOrNull()

    internal fun preparationConfiguration(): PlaybackChoreoPreparation.Configuration =
        PlaybackChoreoPreparation.Configuration(
            enabled = AutomixV2Choreo.enabled,
            overridePhraseScripts = AutomixV2Choreo.overridePhraseScripts,
            retimeEntry = AutomixV2Choreo.retimeEntry,
            seed = AutomixV2Choreo.seed,
            geometryLevel = AutomixV2Choreo.level,
        )

    internal fun preparationHistory(pairKey: String): PlaybackChoreoPreparation.History {
        val recipes = AutomixV2Choreo.history.recentRecipes(pairKey)
        return PlaybackChoreoPreparation.History(AutomixV2Choreo.history.recent(pairKey), recipes)
    }

    internal fun recordPreparationFailure(error: Throwable) {
        Log.w(TAG, "prepare failed", error)
    }

    internal fun fromSharedPreparation(prep: PlaybackChoreoPreparation.Prep): Prep = Prep(
        plan = prep.plan, forced = prep.forced, forcedRecipe = prep.forcedRecipe,
        caps = prep.caps, level = prep.level, outStructure = prep.outStructure,
        inStructure = prep.inStructure, pairKey = prep.pairKey, albumOrder = prep.albumOrder,
        note = prep.note, sampleRate = prep.sampleRate, straightRateMode = prep.straightRateMode,
        excludedRecipes = prep.excludedRecipes,
    )

    /**
     * Identity key for a [LoopMix.Plan]: the geometry the choreographer
     * actually scored (both picks, fold, bar counts, loop/native flags).
     * [LoopMix.Plan] carries no generation/planId of its own (that identity
     * lives on `PairPreparer`'s generation / `Prep.pairKey` outside this
     * glue), so identity here is this geometry key plus `Prep.pairKey`
     * checked by callers.
     */
    fun planKey(plan: LoopMix.Plan): String = buildString {
        append(plan.out.loopStart).append('/').append(plan.out.loopEnd).append('/')
        append(plan.out.bars).append('/').append(plan.out.beatSec).append('/')
        append(plan.out.bpm).append('|')
        append(plan.inn.loopStart).append('/').append(plan.inn.loopEnd).append('/')
        append(plan.inn.bars).append('/').append(plan.inn.beatSec).append('/')
        append(plan.inn.bpm).append('|')
        append(plan.fold).append('/').append(plan.enterBars).append('/')
        append(plan.rampBars).append('/').append(plan.exitBars).append('/')
        append(plan.totalBars).append('/').append(plan.outLoops).append('/')
        append(plan.inLoops).append('/').append(plan.nativeIncoming)
    }

    /**
     * True when [plan] is the plan [prepPlan] was prepared from. Identity
     * (`===`) fast-paths the common case; otherwise the [planKey] geometry
     * must match. Deliberately ignores `incomingHoldSec`/`handoff` copies:
     * `LoopMixSession` derives an `effectivePlan` copy with a different hold
     * between prepare and choreograph, and structural `Plan ==` rejected
     * exactly those legitimate copies.
     */
    fun isSamePlan(prepPlan: LoopMix.Plan, plan: LoopMix.Plan): Boolean =
        prepPlan === plan || planKey(prepPlan) == planKey(plan)

    fun choreograph(
        prep: Prep,
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        sampleRate: Int,
        outRegion: LoopRegion,
        inRegion: LoopRegion,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
        preparedOutStems: StemSet? = null,
        preparedInStems: StemSet? = null,
        preparedScript: ChoreoScript? = null,
    ): Result? = runCatching {
        if (!isSamePlan(prep.plan, plan) || (prep.sampleRate > 0 && prep.sampleRate != sampleRate)) return null
        val wantStems = AutomixV2Choreo.useStems && ChoreoCaps.has(prep.caps, ChoreoCaps.STEMS)
        val outStems = if (wantStems && StemAdapter.matchesRegion(preparedOutStems, outRegion.left.size, sampleRate) &&
            preparedOutStems != null && abs(preparedOutStems.regionStartSec - outRegion.start) <= 0.5 / sampleRate) {
            preparedOutStems
        } else null
        val inStems = if (wantStems && StemAdapter.matchesRegion(preparedInStems, inRegion.left.size, sampleRate) &&
            preparedInStems != null && abs(preparedInStems.regionStartSec - inRegion.start) <= 0.5 / sampleRate) {
            preparedInStems
        } else null
        if (preparedScript != null &&
            preparedScript.level == prep.level &&
            RecipeId.fromCode(preparedScript.recipeId) !in prep.excludedRecipes &&
            (!preparedScript.outUsesStems || outStems != null) &&
            (!preparedScript.inUsesStems || inStems != null)
        ) {
            Log.i(TAG, Choreographer.describe(preparedScript) + (if (prep.note.isNotEmpty()) " · ${prep.note}" else ""))
            return@runCatching Result(
                preparedScript,
                if (preparedScript.outUsesStems) outStems else null,
                if (preparedScript.inUsesStems) inStems else null,
            )
        }
        val knots = knotsFor(plan, clock, sampleRate, outAnalysis, inAnalysis, prep.straightRateMode, prep.level)
        fun inputs(withStems: Boolean) = ChoreoInputs(
            plan = plan, clock = clock, out = outAnalysis, inn = inAnalysis,
            outStructure = prep.outStructure, inStructure = prep.inStructure,
            outStems = if (withStems) StemAdapter.view(outStems) else null,
            inStems = if (withStems) StemAdapter.view(inStems) else null,
            caps = prep.caps, level = prep.level, pairKey = prep.pairKey,
            history = AutomixV2Choreo.history.recent(prep.pairKey),
            recipeHistory = AutomixV2Choreo.history.recentRecipes(prep.pairKey),
            seed = AutomixV2Choreo.seed,
            sungOut = sungOut, sungIn = sungIn, albumOrder = prep.albumOrder,
            outClock = outClock(plan, knots, sampleRate), inClock = inClock(plan, clock, knots, sampleRate),
            excludedRecipes = prep.excludedRecipes,
        )
        val log = ArrayList<String>()
        val script = (if (prep.forcedRecipe != null) Choreographer.chooseRecipe(inputs(true), prep.forcedRecipe, log) else null)
            ?: Choreographer.choose(inputs(true), prep.forced, log)
            ?: Choreographer.chooseRecipe(inputs(true), null, log)
            ?: return null.also { Log.i(TAG, "no choreography: ${log.joinToString("; ")}") }
        Log.i(TAG, Choreographer.describe(script) + (if (prep.note.isNotEmpty()) " · ${prep.note}" else ""))
        Result(script, if (script.outUsesStems) outStems else null, if (script.inUsesStems) inStems else null)
    }.onFailure { Log.w(TAG, "choreograph failed", it) }.getOrNull()

    /** Same as [choreograph]'s choice (preferring [plannedRecipe] without stems), used when the renderer refused a StemSet. */
    fun withoutStems(
        prep: Prep,
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        sampleRate: Int,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
        plannedRecipe: RecipeId? = prep.forcedRecipe,
        requirePlannedRecipe: Boolean = false,
    ): ChoreoScript? = runCatching {
        val knots = knotsFor(plan, clock, sampleRate, outAnalysis, inAnalysis, prep.straightRateMode, prep.level)
        val inputs = ChoreoInputs(
            plan = plan, clock = clock, out = outAnalysis, inn = inAnalysis,
            outStructure = prep.outStructure, inStructure = prep.inStructure,
            caps = prep.caps and ChoreoCaps.STEMS.inv(), level = prep.level, pairKey = prep.pairKey,
            history = AutomixV2Choreo.history.recent(prep.pairKey),
            recipeHistory = AutomixV2Choreo.history.recentRecipes(prep.pairKey),
            sungOut = sungOut, sungIn = sungIn,
            albumOrder = prep.albumOrder,
            outClock = outClock(plan, knots, sampleRate), inClock = inClock(plan, clock, knots, sampleRate),
            excludedRecipes = prep.excludedRecipes,
        )
        (if (plannedRecipe != null) Choreographer.chooseRecipe(inputs, plannedRecipe) else null)
            ?: if (requirePlannedRecipe) {
                null
            } else {
                Choreographer.choose(inputs, prep.forced)
                    ?: Choreographer.chooseRecipe(inputs, null)
            }
    }.getOrNull()

    /**
     * Recompiles the frozen recipe's declared no-stem steps. This is deliberately
     * separate from legacy recipe selection: a missing stem may not change the
     * frozen recipe or plan identity at commit time.
     */
    fun compileSameRecipeNoStem(
        recipe: RecipeV2,
        plan: TransitionPlanV1,
        geometry: RenderGeometryV1,
        canonical: TransitionScriptV1,
        availableCapabilities: Set<FxCapability> = FxCapability.entries.toSet(),
    ): MixOutcome<TransitionScriptV1> {
        if (recipe.id != plan.recipeId) {
            return MixOutcome.Refused(
                com.music.bitchord.djmix.core.contracts.RefusalReason.CHOREOGRAPHY_UNAVAILABLE,
                "frozen_recipe_id_mismatch",
            )
        }
        if (canonical.planId != plan.planId || canonical.generation != plan.generation ||
            canonical.renderGeometry != geometry
        ) {
            return MixOutcome.Refused(
                com.music.bitchord.djmix.core.contracts.RefusalReason.HANDOFF_INVALID_GEOMETRY,
                "frozen_plan_identity_mismatch",
            )
        }
        return RecipeInterpreter.compileFrozen(
            recipe = recipe,
            plan = plan,
            geometry = geometry,
            outRegion = canonical.outRegion,
            inRegion = canonical.inRegion,
            useNoStemVariant = true,
            availableCapabilities = availableCapabilities,
            dropWindowMs = canonical.dropWindowMs,
        )
    }

    /** The production deck knots used by choreography, with the existing app level default. */
    private fun knotsFor(
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        sampleRate: Int,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
        straightRateMode: StraightRateMode? = null,
        level: DjLevel = AutomixV2Choreo.level,
    ): DeckKnots = PlaybackChoreoPreparation.knotsFor(
        plan, clock, sampleRate, outAnalysis, inAnalysis, straightRateMode, level,
    )

    private fun outClock(plan: LoopMix.Plan, knots: DeckKnots, sampleRate: Int): TrackClock =
        PlaybackChoreoPreparation.outClock(plan, knots, sampleRate)

    private fun inClock(plan: LoopMix.Plan, clock: LoopMix.Clock, knots: DeckKnots, sampleRate: Int): TrackClock =
        PlaybackChoreoPreparation.inClock(plan, clock, knots, sampleRate)

}
