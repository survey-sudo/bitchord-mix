package com.music.bitchord.playback.smart.choreo

import android.util.Log
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.StemProvider
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
 *  2. [choreograph] (after decode): stems through [StemProvider] when the
 *     renderer can play them, then [Choreographer.choose] on the real knots.
 *
 * Every step is runCatching'd; any failure returns null and the vc80
 * envelopes stand.
 */
internal object ChoreoSessionGlue {
    private const val TAG = "BitChordChoreo"

    class Prep(
        val plan: LoopMix.Plan,
        val forced: Move?,
        val caps: Int,
        val level: DjLevel,
        val outStructure: StructureView,
        val inStructure: StructureView,
        val pairKey: String,
        val albumOrder: Boolean,
        val note: String,
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
    ): Prep? = runCatching {
        if (!AutomixV2Choreo.enabled) return null
        if (script != null && !AutomixV2Choreo.overridePhraseScripts) return null
        val caps = ChoreoRendererBridge.capabilities()
        val outStructure = StructureView.best(outSongMap, outAnalysis)
        val inStructure = StructureView.best(inSongMap, inAnalysis)
        val pairKey = "$outId->$inId"
        var chosen = plan
        var forced: Move? = null
        var note = ""
        val hold = script?.incomingHoldSec ?: plan.incomingHoldSec
        val held = if (hold != plan.incomingHoldSec) plan.copy(incomingHoldSec = hold) else plan
        val inputs = ChoreoInputs(
            plan = held, clock = LoopMix.Clock(held), out = outAnalysis, inn = inAnalysis,
            outStructure = outStructure, inStructure = inStructure, caps = caps, level = level,
            pairKey = pairKey, history = AutomixV2Choreo.history.recent(pairKey), albumOrder = albumOrder,
            stemsExpected = stemsExpected,
        )
        val start = Choreographer.dropRetimeStart(inputs)
        if (start != null) {
            val verified = verifyRetime(plan, hold, start, inputs, sampleRate, outAnalysis, inAnalysis)
            if (verified != null) {
                chosen = verified
                forced = Move.EDM_BUILD_DROP
                note = "retimed incoming ${plan.inn.loopStart} -> ${verified.inn.loopStart} for a drop"
            }
        }
        Prep(chosen, forced, caps, level, outStructure, inStructure, pairKey, albumOrder, note)
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
            val pos = inClock(held, clock, knots).at(clock.beatTimes[k]) ?: return null
            val err = pos - drop
            if (abs(err) <= 0.03) return candidate
            start -= err
        }
        return null
    }

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
        stemProvider: StemProvider?,
        // AUTOMIX-V2(orch): B caches stems per track, so each deck gets its own provider.
        inStemProvider: StemProvider? = null,
    ): Result? = runCatching {
        val knots = knotsFor(plan, clock, sampleRate, outAnalysis, inAnalysis)
        val inProvider = inStemProvider ?: stemProvider
        val wantStems = AutomixV2Choreo.useStems && ChoreoCaps.has(prep.caps, ChoreoCaps.STEMS) &&
            (stemProvider != null || inProvider != null)
        val outStems = if (wantStems && stemProvider != null) separate(stemProvider, outRegion, sampleRate) else null
        val inStems = if (wantStems && inProvider != null) separate(inProvider, inRegion, sampleRate) else null
        fun inputs(withStems: Boolean) = ChoreoInputs(
            plan = plan, clock = clock, out = outAnalysis, inn = inAnalysis,
            outStructure = prep.outStructure, inStructure = prep.inStructure,
            outStems = if (withStems) StemAdapter.view(outStems) else null,
            inStems = if (withStems) StemAdapter.view(inStems) else null,
            caps = prep.caps, level = prep.level, pairKey = prep.pairKey,
            history = AutomixV2Choreo.history.recent(prep.pairKey), seed = AutomixV2Choreo.seed,
            sungOut = sungOut, sungIn = sungIn, albumOrder = prep.albumOrder,
            outClock = outClock(plan, knots, sampleRate), inClock = inClock(plan, clock, knots),
        )
        val log = ArrayList<String>()
        val script = Choreographer.choose(inputs(true), prep.forced, log)
            ?: Choreographer.choose(inputs(true), null, log)
            ?: return null.also { Log.i(TAG, "no choreography: ${log.joinToString("; ")}") }
        Log.i(TAG, Choreographer.describe(script) + (if (prep.note.isNotEmpty()) " · ${prep.note}" else ""))
        Result(script, if (script.outUsesStems) outStems else null, if (script.inUsesStems) inStems else null)
    }.onFailure { Log.w(TAG, "choreograph failed", it) }.getOrNull()

    /** Same as [choreograph]'s choice but with stems off: used when the renderer refused a StemSet. */
    fun withoutStems(
        prep: Prep,
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        sampleRate: Int,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
    ): ChoreoScript? = runCatching {
        val knots = knotsFor(plan, clock, sampleRate, outAnalysis, inAnalysis)
        val inputs = ChoreoInputs(
            plan = plan, clock = clock, out = outAnalysis, inn = inAnalysis,
            outStructure = prep.outStructure, inStructure = prep.inStructure,
            caps = prep.caps and ChoreoCaps.STEMS.inv(), level = prep.level, pairKey = prep.pairKey,
            history = AutomixV2Choreo.history.recent(prep.pairKey), sungOut = sungOut, sungIn = sungIn,
            albumOrder = prep.albumOrder,
            outClock = outClock(plan, knots, sampleRate), inClock = inClock(plan, clock, knots),
        )
        Choreographer.choose(inputs, prep.forced) ?: Choreographer.choose(inputs, null)
    }.getOrNull()

    private fun separate(provider: StemProvider, region: LoopRegion, sampleRate: Int): StemSet? {
        val seconds = region.left.size / sampleRate.toDouble()
        if (seconds > AutomixV2Choreo.MAX_STEM_REGION_SEC) return null
        val stems = runCatching { provider.separate(region.left, region.right, sampleRate, region.start) }
            .onFailure { Log.w(TAG, "stem separation failed", it) }
            .getOrNull() ?: return null
        return if (StemAdapter.matchesRegion(stems, region.left.size, sampleRate)) stems else null
    }

    /** The knots LoopMixSession.build lays for [plan] (same formulas as build). */
    private fun knotsFor(
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        sampleRate: Int,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
    ) = run {
        val echoHold = if (plan.echoTail) plan.out.beatSec * 8 else 0.0
        val outStop = clock.releaseAt + 0.05 + echoHold + plan.exitTailSeconds
        val tailRate = if (clock.beats >= 1) {
            val b0 = clock.beatTimes[clock.beats - 1]
            val b1 = clock.beatTimes[clock.beats]
            (plan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        val outHasTailKnot = !plan.outLoops && outStop > clock.releaseAt + 1e-6
        LoopMixSession.buildDeckKnots(plan, clock, sampleRate, outAnalysis, inAnalysis, outHasTailKnot, outStop, tailRate)
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

    private fun inClock(plan: LoopMix.Plan, clock: LoopMix.Clock, knots: LoopMixSession.Companion.DeckKnots): TrackClock {
        val frames = knots.inFrames
        val positions = knots.inPositionsSec
        val sr = if (clock.beats >= 1 && clock.beatTimes[1] > 0.0) frames[1] / clock.beatTimes[1] else 48_000.0
        val geometry = LoopMixSession.incomingGeometry(plan, clock, knots)
        return TrackClock { mix ->
            if (mix < 0.0) null
            else interpolate(frames, positions, mix * sr)?.let { u ->
                if (geometry.loops) LoopMix.physicalTrackTime(u, plan.inn.loopStart, plan.inn.loopEnd, geometry.releaseTrack) else u
            }
        }
    }
}
