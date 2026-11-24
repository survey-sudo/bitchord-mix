package com.music.bitchord.playback.smart.choreo

import android.util.Log
import com.music.bitchord.playback.djmix.StraightRateMode
import com.music.bitchord.playback.loopmix.LoopMixSession
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
        if (!AutomixV2Choreo.enabled) return null
        if (script != null && !AutomixV2Choreo.overridePhraseScripts) return null
        val caps = capsOverride ?: ChoreoRendererBridge.capabilities()
        val outStructure = StructureView.best(outSongMap, outAnalysis)
        val inStructure = StructureView.best(inSongMap, inAnalysis)
        val pairKey = "$outId->$inId"
        var chosen = plan
        var forced: Move? = null
        var forcedRecipe: RecipeId? = null
        var note = ""
        val hold = script?.incomingHoldSec ?: plan.incomingHoldSec
        val held = if (hold != plan.incomingHoldSec) plan.copy(incomingHoldSec = hold) else plan
        val recentRecipes = AutomixV2Choreo.history.recentRecipes(pairKey)
        val inputs = ChoreoInputs(
            plan = held, clock = LoopMix.Clock(held), out = outAnalysis, inn = inAnalysis,
            outStructure = outStructure, inStructure = inStructure, caps = caps, level = level,
            pairKey = pairKey, history = AutomixV2Choreo.history.recent(pairKey),
            recipeHistory = recentRecipes, albumOrder = albumOrder,
            stemsExpected = stemsExpected,
            excludedRecipes = excludedRecipes,
        )
        // Straight recipes finalize their vc91 cue and physical coverage before decode.
        val start = if (straightRateMode == null) Choreographer.dropRetimeStart(inputs) else null
        if (start != null) {
            val verified = verifyRetime(plan, hold, start, inputs, sampleRate, outAnalysis, inAnalysis)
            if (verified != null) {
                chosen = verified
                forced = Move.EDM_BUILD_DROP
                forcedRecipe = when (level) {
                    DjLevel.CLUB -> RecipeId.C7
                    DjLevel.FESTIVAL -> {
                        val c = PairContext(inputs)
                        if (outStructure.drops.isNotEmpty() && c.tempoGap <= 0.04 && recentRecipes.lastOrNull() != RecipeId.F8) {
                            RecipeId.F8
                        } else {
                            RecipeId.F1
                        }
                    }
                    DjLevel.SUBTLE -> null
                }
                note = "retimed incoming ${plan.inn.loopStart} -> ${verified.inn.loopStart} for a drop"
            }
        }
        if (forcedRecipe in excludedRecipes) { forcedRecipe = null; forced = null }
        Prep(chosen, forced, forcedRecipe, caps, level, outStructure, inStructure, pairKey, albumOrder, note,
            sampleRate, straightRateMode, excludedRecipes)
    }.onFailure { Log.w(TAG, "prepare failed", it) }.getOrNull()

    /**
     * Moves the incoming start until the knot-mapped incoming position at the
     * target beat is within 30 ms of the drop (tracked beats snap the start,
     * so it can take two steps); null when it will not converge.
     */
    private fun verifyRetime(
        plan: LoopMix.Plan,
        hold: Double,
        start0: Double,
        inputs: ChoreoInputs,
        sampleRate: Int,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
    ): LoopMix.Plan? {
        val c0 = PairContext(inputs)
        // Recover which (beat, drop) dropRetimeStart aimed at: the drop is start0 + (t_k - A).
        var target: Pair<Int, Double>? = null
        for (k in c0.kRel - 1 downTo c0.kIn) {
            val d = start0 + (c0.t(k) - c0.inAudibleAt)
            if (inputs.inStructure.drops.any { abs(it - d) < 1e-6 }) {
                target = k to d
                break
            }
        }
        val (k, drop) = target ?: return null
        var start = start0
        repeat(4) {
            if (start < 0.0) return null
            val candidate = Choreographer.withIncomingStart(plan, start)
            val held = if (hold != candidate.incomingHoldSec) candidate.copy(incomingHoldSec = hold) else candidate
            val clock = LoopMix.Clock(held)
            val knots = knotsFor(held, clock, sampleRate, outAnalysis, inAnalysis)
            val pos = inClock(held, clock, knots, sampleRate).at(clock.beatTimes[k]) ?: return null
            val err = pos - drop
            if (abs(err) <= 0.03) return candidate
            start -= err
        }
        return null
    }

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
            ?: Choreographer.choose(inputs, prep.forced)
            ?: Choreographer.chooseRecipe(inputs, null)
    }.getOrNull()

    /** The knots LoopMixSession.build lays for [plan] (same formulas as build). */
    private fun knotsFor(
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        sampleRate: Int,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
        straightRateMode: StraightRateMode? = null,
        level: DjLevel = AutomixV2Choreo.level,
    ) = run {
        val echoHold = if (plan.echoTail) plan.out.beatSec * 8 else 0.0
        val outStop = clock.releaseAt + 0.05 + echoHold + plan.exitTailSeconds
        val tailRate = if (clock.beats >= 1) {
            val b0 = clock.beatTimes[clock.beats - 1]
            val b1 = clock.beatTimes[clock.beats]
            (plan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        val outHasTailKnot = !plan.outLoops && outStop > clock.releaseAt + 1e-6
        LoopMixSession.buildDeckKnots(plan, clock, sampleRate, outAnalysis, inAnalysis, outHasTailKnot, outStop, tailRate,
            straightRateMode, level)
    }

    private fun interpolate(frames: DoubleArray, positions: DoubleArray, frame: Double): Double? {
        if (frames.isEmpty() || frames.size != positions.size) return null
        if (frame <= frames[0]) return positions[0]
        val last = frames.size - 1
        if (frame >= frames[last]) return positions[last] + (frame - frames[last]) / maxOf(1.0, frames[last] - frames[maxOf(0, last - 1)]) *
            (positions[last] - positions[maxOf(0, last - 1)])
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

    private fun outClock(plan: LoopMix.Plan, knots: LoopMixSession.Companion.DeckKnots, sampleRate: Int): TrackClock {
        val sr = sampleRate.toDouble()
        val frames = knots.outFrames
        val positions = knots.outPositionsSec
        return TrackClock { mix ->
            if (mix < 0.0) null
            else interpolate(frames, positions, mix * sr)?.let { u ->
                if (plan.outLoops) LoopMix.physicalTrackTime(u, plan.out.loopStart, plan.out.loopEnd, 1e18) else u
            }
        }
    }

    private fun inClock(plan: LoopMix.Plan, clock: LoopMix.Clock, knots: LoopMixSession.Companion.DeckKnots, sampleRate: Int): TrackClock {
        val frames = knots.inFrames
        val positions = knots.inPositionsSec
        val sr = sampleRate.toDouble()
        val geometry = LoopMixSession.incomingGeometry(plan, clock, knots)
        return TrackClock { mix ->
            if (mix < 0.0) null
            else interpolate(frames, positions, mix * sr)?.let { u ->
                if (geometry.loops) LoopMix.physicalTrackTime(u, plan.inn.loopStart, plan.inn.loopEnd, geometry.releaseTrack) else u
            }
        }
    }
}
