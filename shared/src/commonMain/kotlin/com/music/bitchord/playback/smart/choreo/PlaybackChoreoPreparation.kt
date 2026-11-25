package com.music.bitchord.playback.smart.choreo

import com.music.bitchord.playback.djmix.StraightRateMode
import com.music.bitchord.playback.loopmix.DeckKnots
import com.music.bitchord.playback.loopmix.PlaybackDeckGeometry
import com.music.bitchord.playback.loopmix.PlaybackGeometryQualification
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlin.math.abs
import kotlin.math.max

/** Production preparation and knot-verified retiming with explicit settings and history. */
object PlaybackChoreoPreparation {
    data class Configuration(
        val enabled: Boolean,
        val overridePhraseScripts: Boolean,
        val retimeEntry: Boolean,
        val seed: Long,
        /** The prior knotsFor default, independent of the requested choreography level. */
        val geometryLevel: DjLevel,
    )

    data class History(val moves: List<Move>, val recipes: List<RecipeId>)

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
        caps: Int,
        configuration: Configuration,
        history: History,
    ): Prep? {
        if (!configuration.enabled) return null
        if (script != null && !configuration.overridePhraseScripts) return null
        val outStructure = StructureView.best(outSongMap, outAnalysis)
        val inStructure = StructureView.best(inSongMap, inAnalysis)
        val pairKey = "$outId->$inId"
        var chosen = plan
        var forced: Move? = null
        var forcedRecipe: RecipeId? = null
        var note = ""
        val hold = script?.incomingHoldSec ?: plan.incomingHoldSec
        val held = if (hold != plan.incomingHoldSec) plan.copy(incomingHoldSec = hold) else plan
        val recentRecipes = history.recipes
        val inputs = ChoreoInputs(
            plan = held, clock = LoopMix.Clock(held), out = outAnalysis, inn = inAnalysis,
            outStructure = outStructure, inStructure = inStructure, caps = caps, level = level,
            pairKey = pairKey, history = history.moves,
            recipeHistory = recentRecipes, seed = configuration.seed, albumOrder = albumOrder,
            stemsExpected = stemsExpected,
            excludedRecipes = excludedRecipes,
        )
        // Straight recipes finalize their vc91 cue and physical coverage before decode.
        val start = if (straightRateMode == null) dropRetimeStart(inputs, configuration.retimeEntry) else null
        if (start != null) {
            val verified = verifyRetime(plan, hold, start, inputs, sampleRate, outAnalysis, inAnalysis, configuration.geometryLevel)
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
        return Prep(chosen, forced, forcedRecipe, caps, level, outStructure, inStructure, pairKey, albumOrder, note,
            sampleRate, straightRateMode, excludedRecipes)
    }

    /**
     * Moves the incoming start until the knot-mapped incoming position at the
     * target beat is within 30 ms of the drop (tracked beats snap the start,
     * so it can take two steps); null when it will not converge.
     */
    fun verifyRetime(
        plan: LoopMix.Plan,
        hold: Double,
        start0: Double,
        inputs: ChoreoInputs,
        sampleRate: Int,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
        geometryLevel: DjLevel,
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
            val knots = knotsFor(held, clock, sampleRate, outAnalysis, inAnalysis, level = geometryLevel)
            val pos = inClock(held, clock, knots, sampleRate).at(clock.beatTimes[k]) ?: return null
            val err = pos - drop
            if (abs(err) <= 0.03) return candidate
            start -= err
        }
        return null
    }

    /**
     * Pass 1 (before decode): where the incoming should start so that one of
     * its drops lands on a bar line of this clock, for C7 / F1 / F8. Track
     * seconds of the new incoming start, or null to keep the plan's cue.
     * Only ever skips forward into the incoming intro, by at most
     * [DjLevel.maxEntrySkipSec]; the outgoing geometry is never touched.
     */
    fun dropRetimeStart(inputs: ChoreoInputs, retimeEntry: Boolean): Double? {
        if (!retimeEntry) return null
        val level = inputs.level
        if (level.maxEntrySkipSec <= 0.0 || level == DjLevel.SUBTLE) return null
        val plan = inputs.plan
        if (!plan.nativeIncoming || plan.inLoops) return null
        val c = PairContext(inputs)
        if ((c.genre != GenreClass.DANCE && c.genre != GenreClass.POP) ||
            c.beatConf < 0.5 || c.tempoGap > level.maxStretch
        ) return null
        if (MoveCatalog.dropBeat(c) != null) return null
        val drops = inputs.inStructure.drops.sorted()
        if (drops.isEmpty()) return null
        val grid = if (plan.fold < 1.0) 8 else 4
        val first = max(c.kIn, 8)
        val duration = inputs.inn?.duration ?: 0.0
        for (d in drops) {
            if (duration > 0.0 && d > duration - 20.0) continue
            var k = c.kRel - 1
            while (k >= first) {
                if ((k - c.kIn) % grid == 0) {
                    val start = d - (c.t(k) - c.inAudibleAt)
                    val skip = start - plan.inn.loopStart
                    if (start >= 0.0 && skip > 0.0 && skip <= level.maxEntrySkipSec) return start
                }
                k--
            }
        }
        return null
    }

    fun outgoingStopAt(plan: LoopMix.Plan, clock: LoopMix.Clock): Double {
        val echoHold = if (plan.echoTail) plan.out.beatSec * 8 else 0.0
        return clock.releaseAt + 0.05 + echoHold + plan.exitTailSeconds
    }

    /** The production deck knots used for retime verification and choreography clocks. */
    fun knotsFor(
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        sampleRate: Int,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
        straightRateMode: StraightRateMode? = null,
        level: DjLevel,
    ) = run {
        val outStop = outgoingStopAt(plan, clock)
        val tailRate = if (clock.beats >= 1) {
            val b0 = clock.beatTimes[clock.beats - 1]
            val b1 = clock.beatTimes[clock.beats]
            (plan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        val outHasTailKnot = !plan.outLoops && outStop > clock.releaseAt + 1e-6
        PlaybackDeckGeometry.buildDeckKnots(plan, clock, sampleRate, outAnalysis, inAnalysis, outHasTailKnot, outStop, tailRate,
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

    fun outClock(plan: LoopMix.Plan, knots: DeckKnots, sampleRate: Int): TrackClock {
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

    fun inClock(plan: LoopMix.Plan, clock: LoopMix.Clock, knots: DeckKnots, sampleRate: Int): TrackClock {
        val frames = knots.inFrames
        val positions = knots.inPositionsSec
        val sr = sampleRate.toDouble()
        val geometry = PlaybackGeometryQualification.incomingGeometry(plan, clock, knots)
        return TrackClock { mix ->
            if (mix < 0.0) null
            else interpolate(frames, positions, mix * sr)?.let { u ->
                if (geometry.loops) LoopMix.physicalTrackTime(u, plan.inn.loopStart, plan.inn.loopEnd, geometry.releaseTrack) else u
            }
        }
    }
}
