package com.music.bitchord.playback.smart.choreo

import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.SectionKind
import kotlin.math.max
import kotlin.math.min

/**
 * What a move needs before it may be scored. Checked against a
 * [PairContext]; the first failing requirement is the reason it was skipped.
 */
data class Requirements(
    /** 0 = none, 2 = vocal/instrumental split, 4 = full stems. Per deck. */
    val outStems: Int = 0,
    val inStems: Int = 0,
    /** [ChoreoCaps] bits that must be present. */
    val caps: Int = 0,
    val beatMatched: Boolean = false,
    val minBeatConf: Double = 0.0,
    /** Bars from incoming audible to release the move needs. */
    val minOverlapBars: Double = 0.0,
    /** Highest folded tempo gap the move tolerates (0.08 = 8%). */
    val maxTempoGap: Double = Double.POSITIVE_INFINITY,
    /** The move only makes sense with a compatible key (and confident keys). */
    val harmonicCompatible: Boolean = false,
    val outLoops: Boolean = false,
)

/** One builder call's result: lanes + FX, before scoring/guarding. */
class Built(
    val outLanes: Map<EnvKind, Env>,
    val inLanes: Map<EnvKind, Env>,
    val outFx: DeckFx,
    val inFx: DeckFx,
    val outUsesStems: Boolean,
    val inUsesStems: Boolean,
    val isolate: Boolean,
    val note: String,
)

/**
 * The catalogue: requirements, swap-beat windows and envelope builders for
 * each [Move]. All builders write every vc80 kind on both decks (so a script
 * fully owns the transition) and start the outgoing deck neutral at t0 and
 * leave the incoming deck neutral by the release.
 */
object MoveCatalog {
    const val KILL_DB = -24f
    /** A muffled incoming keeps some low end (the low-pass already thins it). */
    const val MUFFLE_LOW_DB = -12f

    fun requirementsOf(move: Move): Requirements = when (move) {
        Move.SIMPLE_FADE -> Requirements()
        Move.APPLE_STYLE_SMOOTH -> Requirements(beatMatched = true, minBeatConf = 0.45, minOverlapBars = 2.0, maxTempoGap = 0.08)
        Move.STEM_BASS_SWAP -> Requirements(beatMatched = true, minBeatConf = 0.45, minOverlapBars = 2.0, maxTempoGap = 0.08)
        Move.DRUMS_FIRST -> Requirements(outStems = 4, inStems = 4, caps = ChoreoCaps.STEMS, beatMatched = true, minBeatConf = 0.5, minOverlapBars = 4.0, maxTempoGap = 0.06)
        Move.DRUM_SWAP -> Requirements(outStems = 4, inStems = 4, caps = ChoreoCaps.STEMS, beatMatched = true, minBeatConf = 0.55, minOverlapBars = 4.0, maxTempoGap = 0.06)
        Move.ACAPELLA_OVER_INSTRUMENTAL -> Requirements(outStems = 2, caps = ChoreoCaps.STEMS, beatMatched = true, minBeatConf = 0.5, minOverlapBars = 4.0, maxTempoGap = 0.06, harmonicCompatible = true)
        Move.INSTRUMENTAL_BED -> Requirements(beatMatched = true, minBeatConf = 0.45, minOverlapBars = 2.0, maxTempoGap = 0.06)
        Move.MUFFLE_IN -> Requirements(beatMatched = true, minBeatConf = 0.45, minOverlapBars = 3.0, maxTempoGap = 0.08)
        Move.FILTER_WASH_ECHO_OUT -> Requirements(minBeatConf = 0.4, minOverlapBars = 0.0)
        Move.LOOP_EXTEND -> Requirements(beatMatched = true, minBeatConf = 0.5, minOverlapBars = 4.0, maxTempoGap = 0.08, outLoops = true)
        Move.EDM_BUILD_DROP -> Requirements(minBeatConf = 0.5, maxTempoGap = 0.08)
        Move.BRAKE_CUT -> Requirements(caps = ChoreoCaps.BRAKE, minBeatConf = 0.35)
        Move.PHASER_DISSOLVE -> Requirements(caps = ChoreoCaps.PHASER, beatMatched = true, minBeatConf = 0.5, minOverlapBars = 4.0, maxTempoGap = 0.08)
        Move.FLANGER_DISSOLVE -> Requirements(beatMatched = true, minBeatConf = 0.5, minOverlapBars = 4.0, maxTempoGap = 0.08)
        Move.PUMP_BLEND -> Requirements(caps = ChoreoCaps.PUMP, beatMatched = true, minBeatConf = 0.55, minOverlapBars = 4.0, maxTempoGap = 0.06)
    }

    /** Null when [move] may be scored here; otherwise why not. */
    fun reject(move: Move, c: PairContext): String? {
        val inputs = c.inputs
        if (!inputs.level.atLeast(move.minLevel)) return "level ${inputs.level} < ${move.minLevel}"
        if (inputs.albumOrder && move != Move.SIMPLE_FADE && move != Move.APPLE_STYLE_SMOOTH) return "album order"
        val r = requirementsOf(move)
        if (!ChoreoCaps.has(inputs.caps, r.caps)) return "renderer lacks caps ${r.caps}"
        if (r.outStems > 0 && !stemsOk(inputs.outStems, r.outStems, inputs)) return "outgoing stems < ${r.outStems}"
        if (r.inStems > 0 && !stemsOk(inputs.inStems, r.inStems, inputs)) return "incoming stems < ${r.inStems}"
        if (r.beatMatched && !c.beatMatched) return "not beat-matched"
        if (c.beatConf < r.minBeatConf && !(move == Move.FILTER_WASH_ECHO_OUT && c.outBeatConf >= r.minBeatConf) &&
            !(move == Move.BRAKE_CUT && c.outBeatConf >= r.minBeatConf)
        ) return "beat confidence ${c.beatConf}"
        if (c.overlapBars + 1e-9 < r.minOverlapBars) return "overlap ${c.overlapBars} bars"
        if (c.tempoGap > r.maxTempoGap && r.beatMatched) return "tempo gap ${c.tempoGap}"
        if (r.harmonicCompatible && !c.harmony.compatible) return "keys ${c.harmony}"
        if (r.outLoops && !c.plan.outLoops) return "outgoing does not loop"
        return when (move) {
            Move.ACAPELLA_OVER_INSTRUMENTAL -> {
                val outV = c.mean(c.inAudibleAt, c.release) { c.outVocalAt(it) } ?: 0.0
                val inV = c.mean(c.inAudibleAt, c.release) { c.inVocalAt(it) } ?: 1.0
                when {
                    outV < 0.25 -> "outgoing not singing ($outV)"
                    inV > 0.2 && inputs.inStems?.vocalSplit != true -> "incoming intro sings ($inV)"
                    else -> null
                }
            }
            Move.INSTRUMENTAL_BED -> {
                if (inputs.outStems == null && !inputs.stemsExpected && c.inVocalEntry(c.inAudibleAt, c.release) == null) "no incoming vocal entry" else null
            }
            Move.EDM_BUILD_DROP -> {
                if (c.genre != GenreClass.DANCE) "not a dance pair"
                else if (dropBeat(c) == null) "no incoming drop in the window"
                else null
            }
            Move.BRAKE_CUT -> if (c.tempoGap <= 0.08 && c.genre != GenreClass.HIPHOP) "no genre/tempo jump" else null
            Move.PUMP_BLEND -> if (c.genre != GenreClass.DANCE) "pump is for house/EDM" else null
            else -> null
        }
    }

    private fun stemsOk(view: StemView?, need: Int, inputs: ChoreoInputs): Boolean {
        if (!AutomixV2Choreo.useStems) return false
        if (view != null) return view.stemCount >= need
        return inputs.stemsExpected && inputs.stemsExpectedCount >= need
    }

    // ---- swap-beat windows -------------------------------------------------------------------

    /** Candidate swap beats (bar lines on the mix clock) for [move]. */
    fun swapBeats(move: Move, c: PairContext): List<Int> {
        val kIn = c.kIn
        val kRel = c.kRel
        val out = ArrayList<Int>()
        if (move == Move.EDM_BUILD_DROP) {
            dropBeat(c)?.let { out += it }
            return out
        }
        val (lo, hi) = if (move.cut) {
            val first = max(kIn, if (move == Move.FILTER_WASH_ECHO_OUT || move == Move.BRAKE_CUT) 4 else 8)
            val last = kRel - 1
            first to last
        } else if (move == Move.SIMPLE_FADE) {
            kIn to kRel
        } else {
            val first = kIn + 4
            val last = kRel - 4
            val mid = kIn + ((kRel - kIn) / 8) * 4
            if (first <= last) first to last else mid to mid
        }
        if (lo > hi) {
            if (move.cut && kIn <= kRel - 1) out += kIn.coerceAtLeast(0)
            return out
        }
        var k = lo + ((4 - ((lo - kIn) % 4)) % 4)
        while (k <= hi) {
            out += k
            k += 4
        }
        if (out.isEmpty()) out += lo
        return out
    }

    /** The mix beat whose incoming position is a drop, if one sits on a bar line inside the window. */
    fun dropBeat(c: PairContext): Int? {
        val drops = c.inputs.inStructure.drops
        if (drops.isEmpty()) return null
        val firstAllowed = max(c.kIn, 8)
        var k = firstAllowed
        while (k <= c.kRel - 1) {
            val t = c.t(k)
            val p = c.inPos(t)
            if (p != null) {
                val tol = max(0.06, c.beatSecAt(k) * 0.25)
                if (drops.any { kotlin.math.abs(it - p) <= tol }) return k
            }
            k++
        }
        return null
    }

    // ---- envelope builders -------------------------------------------------------------------

    fun build(move: Move, c: PairContext, swapBeat: Int): Built {
        val L = Lanes(c, swapBeat)
        return when (move) {
            Move.SIMPLE_FADE -> L.simpleFade()
            Move.APPLE_STYLE_SMOOTH -> L.appleSmooth()
            Move.STEM_BASS_SWAP -> L.bassSwap(fx = null)
            Move.DRUMS_FIRST -> L.drumsFirst()
            Move.DRUM_SWAP -> L.drumSwap()
            Move.ACAPELLA_OVER_INSTRUMENTAL -> L.acapella()
            Move.INSTRUMENTAL_BED -> L.instrumentalBed()
            Move.MUFFLE_IN -> L.muffleIn()
            Move.FILTER_WASH_ECHO_OUT -> L.echoOut()
            Move.LOOP_EXTEND -> L.loopExtend()
            Move.EDM_BUILD_DROP -> L.buildDrop()
            Move.BRAKE_CUT -> L.brakeCut()
            Move.PHASER_DISSOLVE -> L.bassSwap(fx = Move.PHASER_DISSOLVE)
            Move.FLANGER_DISSOLVE -> L.bassSwap(fx = Move.FLANGER_DISSOLVE)
            Move.PUMP_BLEND -> L.bassSwap(fx = Move.PUMP_BLEND)
        }
    }

    /**
     * Lane writer for one (context, swap beat). S = swap second, A = incoming
     * audible, R = release, b = beat seconds at S.
     */
    private class Lanes(val c: PairContext, val k: Int) {
        val S: Double = c.t(k)
        val A: Double = c.inAudibleAt
        val R: Double = c.release
        val b: Double = c.beatSecAt(k)
        val bar: Double = b * 4
        val depth: Float = c.inputs.level.fxDepth
        val h: Double = LoopMix.HANDOVER_SEC
        /** Bass swap ramp: 1/8 beat, inside the 1/16..1/4 beat DJ range. */
        val w: Double = (b / 8).coerceIn(0.02, 0.12)
        val outStems: StemView? = c.inputs.outStems
        val inStems: StemView? = c.inputs.inStems
        val outFull: Boolean = outStems?.full == true
        val inFull: Boolean = inStems?.full == true
        val outSplit: Boolean = outStems?.vocalSplit == true
        val inSplit: Boolean = inStems?.vocalSplit == true
        val o = HashMap<EnvKind, Env>()
        val i = HashMap<EnvKind, Env>()
        var outFx = DeckFx.NONE
        var inFx = DeckFx.NONE
        var isolate = false
        var outStemsUsed = false
        var inStemsUsed = false

        /** A time at or before the release, so the incoming is neutral by then. */
        fun byR(t: Double): Double = min(t, R - 0.005)

        fun outGainHoldThenFall(fallFrom: Double, fallTo: Double) {
            val from = fallFrom.coerceIn(0.0, R)
            val to = max(from + 0.01, min(fallTo, R))
            o[EnvKind.GAIN] = EnvBuilder().at(-h, 0f).at(0.0, 1f).at(from, 1f).fall(from, to).build()
        }

        fun inGainRise(from: Double, to: Double, peak: Float = 1f, holdUntil: Double? = null) {
            val a = max(from, A)
            val z = byR(max(to, a + 0.01))
            val eb = EnvBuilder().at(0.0, 0f)
            if (a > 0.0) eb.at(a, 0f)
            eb.rise(a, z, 0f, peak)
            if (holdUntil != null && peak < 1f) {
                val hu = byR(max(holdUntil, z + 0.01))
                eb.at(hu, peak).at(byR(hu + b), 1f)
            } else if (peak < 1f) {
                eb.at(byR(z + b), 1f)
            }
            i[EnvKind.GAIN] = eb.build()
        }

        /** Swap the lows at S on both decks: EQ shelf always (belt and braces), bass stems when present. */
        fun swapLows(inLowBefore: Float = KILL_DB) {
            val a = S - w / 2
            val z = S + w / 2
            o[EnvKind.LOW_DB] = EnvBuilder().at(0.0, 0f).at(a, 0f).at(z, KILL_DB).build()
            i[EnvKind.LOW_DB] = EnvBuilder().at(0.0, inLowBefore).at(a, inLowBefore).at(byR(z), 0f).build()
            if (outFull) {
                o[EnvKind.STEM_BASS] = EnvBuilder().at(0.0, 1f).at(a, 1f).at(z, 0f).build()
                outStemsUsed = true
            }
            if (inFull) {
                i[EnvKind.STEM_BASS] = EnvBuilder().at(0.0, 0f).at(a, 0f).at(byR(z), 1f).build()
                inStemsUsed = true
            }
        }

        fun finish(note: String): Built {
            for (kind in EnvKind.LEGACY) {
                if (!o.containsKey(kind)) o[kind] = Env.constant(kind.neutral, kind.exponential)
                if (!i.containsKey(kind)) i[kind] = Env.constant(kind.neutral, kind.exponential)
            }
            if (outStemsUsed) for (kind in STEM_KINDS) if (!o.containsKey(kind)) o[kind] = Env.constant(1f)
            if (inStemsUsed) for (kind in STEM_KINDS) if (!i.containsKey(kind)) i[kind] = Env.constant(1f)
            return Built(o, i, outFx, inFx, outStemsUsed, inStemsUsed, isolate, note)
        }

        // -- moves ------------------------------------------------------------------------------

        fun simpleFade(): Built {
            // Ballad-length equal-power fade (4..10 s) ending at the release; no EQ, no FX.
            val len = min(10.0, max(4.0, (R - A) * 0.8))
            val fs = max(A, R - len)
            o[EnvKind.GAIN] = EnvBuilder().at(-h, 0f).at(0.0, 1f).at(max(0.0, fs), 1f).fall(max(0.0, fs) + 1e-3, R, steps = 16).build()
            val eb = EnvBuilder().at(0.0, 0f)
            if (fs > 0.0) eb.at(fs, 0f)
            eb.rise(fs + 1e-3, byR(R), steps = 16)
            i[EnvKind.GAIN] = eb.build()
            return finish("fade ${r1(R - fs)} s")
        }

        fun appleSmooth(): Built {
            val bs = max(A, c.t(c.kIn))
            o[EnvKind.GAIN] = EnvBuilder().at(-h, 0f).at(0.0, 1f).at(bs, 1f).fall(bs + 1e-3, R, steps = 16).build()
            val eb = EnvBuilder().at(0.0, 0f)
            if (bs > 0.0) eb.at(bs, 0f)
            eb.rise(bs + 1e-3, byR(R), steps = 16)
            i[EnvKind.GAIN] = eb.build()
            swapLows()
            o[EnvKind.HIGH_PASS_HZ] = EnvBuilder(true).at(0.0, 20f).at(S, 20f).at(R, 150f).build()
            return finish("equal-power ${r1(R - bs)} s, EQ swap @${r1(S)}")
        }

        fun bassSwap(fx: Move?): Built {
            inGainRise(A, min(A + 2 * bar, S), peak = 0.85f, holdUntil = S)
            outGainHoldThenFall(S, R)
            swapLows()
            o[EnvKind.HIGH_PASS_HZ] = EnvBuilder(true).at(0.0, 20f).at(S, 20f).at(R, 200f + 200f * depth).build()
            val fxFrom = max(A, S - 2 * bar)
            when (fx) {
                Move.PHASER_DISSOLVE -> {
                    outFx = DeckFx(phaser = true, phaserRateBeats = 8.0, phaserDepth = 0.7f * depth, phaserFeedback = 0.45f)
                    o[EnvKind.PHASER_WET] = EnvBuilder().at(0.0, 0f).at(fxFrom, 0f).at(S, 0.5f * depth).at(R, 0.5f * depth).build()
                }
                Move.FLANGER_DISSOLVE -> {
                    outFx = DeckFx(
                        flanger = true, flangerLfoHz = LoopMix.lfoHzForBeatSec(b),
                        flangerDepthSec = LoopMix.FLANGER_DEPTH_COLOUR_SEC, flangerFeedback = 0.55f,
                        flangerWet = min(0.5f, LoopMix.FLANGER_WET * depth),
                    )
                    o[EnvKind.FLANGER_WET] = EnvBuilder().at(0.0, 0f).at(fxFrom, 0f).at(S, 1f).at(R, 1f).build()
                }
                Move.PUMP_BLEND -> {
                    outFx = DeckFx(pump = true, pumpBeatSec = b)
                    o[EnvKind.PUMP_DEPTH] = EnvBuilder().at(0.0, 0f).at(A, 0f).at(S, 0.65f * depth).at(R, 0.65f * depth).build()
                }
                else -> Unit
            }
            val tag = when (fx) {
                Move.PHASER_DISSOLVE -> "phaser"
                Move.FLANGER_DISSOLVE -> "flanger"
                Move.PUMP_BLEND -> "pump"
                else -> if (outFull || inFull) "stems" else "eq"
            }
            return finish("bass swap @${r1(S)} ($tag)")
        }

        fun drumsFirst(): Built {
            // Incoming drums ride in under the outgoing; the rest follows on the swap downbeat.
            inGainRise(A, min(A + bar, S), peak = 0.9f, holdUntil = S)
            outGainHoldThenFall(S, R)
            swapLows()
            outStemsUsed = true
            inStemsUsed = true
            o[EnvKind.STEM_DRUMS] = EnvBuilder().at(0.0, 1f).at(A, 1f).at(min(A + bar, S), 0.4f).at(S - w / 2, 0.4f).at(S + w / 2, 0f).build()
            i[EnvKind.STEM_DRUMS] = EnvBuilder().at(0.0, 1f).build()
            i[EnvKind.STEM_OTHER] = EnvBuilder().at(0.0, 0f).at(S, 0f).at(byR(S + b), 1f).build()
            i[EnvKind.STEM_VOCALS] = EnvBuilder().at(0.0, 0f).at(S, 0f).at(byR(S + bar), 1f).build()
            return finish("drums first from ${r1(A)}, swap @${r1(S)}")
        }

        fun drumSwap(): Built {
            val kD = max(c.kIn, k - 8)
            val d = c.t(kD)
            inGainRise(A, min(A + bar, S), peak = 0.75f, holdUntil = S)
            outGainHoldThenFall(S, R)
            swapLows()
            outStemsUsed = true
            inStemsUsed = true
            o[EnvKind.STEM_DRUMS] = EnvBuilder().at(0.0, 1f).at(d - w / 2, 1f).at(d + w / 2, 0f).build()
            i[EnvKind.STEM_DRUMS] = EnvBuilder().at(0.0, 0f).at(d - w / 2, 0f).at(byR(d + w / 2), 1f).build()
            i[EnvKind.STEM_VOCALS] = EnvBuilder().at(0.0, 0f).at(S, 0f).at(byR(S + 2 * b), 1f).build()
            return finish("drums swap @${r1(d)}, bass @${r1(S)}")
        }

        fun acapella(): Built {
            // Outgoing vocal rides over the incoming instrumental; its own band leaves under it.
            val vocalOut = max(S + b, R - bar)
            i[EnvKind.GAIN] = EnvBuilder().at(0.0, 0f).at(A, 0f).rise(A + 1e-3, byR(A + bar)).build()
            o[EnvKind.GAIN] = EnvBuilder().at(-h, 0f).at(0.0, 1f).at(vocalOut, 1f).fall(vocalOut + 1e-3, R).build()
            swapLows()
            outStemsUsed = true
            o[EnvKind.STEM_OTHER] = EnvBuilder().at(0.0, 1f).at(A, 1f).fall(A + 1e-3, S).build()
            if (outFull) {
                o[EnvKind.STEM_DRUMS] = EnvBuilder().at(0.0, 1f).at(A, 1f).at(A + b, 0f).build()
            }
            o[EnvKind.HIGH_PASS_HZ] = EnvBuilder(true).at(0.0, 20f).at(S, 20f).at(byR(S + b), 120f).build()
            if (inSplit) {
                inStemsUsed = true
                i[EnvKind.STEM_VOCALS] = EnvBuilder().at(0.0, 0f).at(vocalOut, 0f).at(byR(R - 0.01), 1f).build()
            }
            // One FX: the acapella echoes out on its last beat (the deck carries only the vocal by then).
            val delay = if (c.outBpm > 120.0) b * 0.5 else b
            outFx = DeckFx(echoDelaySec = delay, echoFeedback = 0.5f + 0.1f * depth, echoWet = 0.5f)
            o[EnvKind.ECHO_SEND] = EnvBuilder().at(0.0, 0f).at(max(0.0, R - 1.5 * b), 0f).at(R - 0.3 * b, 0.8f * depth).at(R, 0f).build()
            return finish("acapella over instrumental, swap @${r1(S)}")
        }

        fun instrumentalBed(): Built {
            val entry = c.inVocalEntry(A, R) ?: (A + bar)
            val vFrom = max(A, entry - bar)
            val vTo = max(vFrom + 0.05, entry - 0.05)
            inGainRise(A, min(A + b * 2, S), peak = 1f)
            outGainHoldThenFall(S, R)
            swapLows(inLowBefore = KILL_DB)
            if (outSplit) {
                outStemsUsed = true
                o[EnvKind.STEM_VOCALS] = EnvBuilder().at(0.0, 1f).at(vFrom, 1f).fall(vFrom + 1e-3, vTo).build()
            } else {
                // Stemless fallback: carve the outgoing mids away before the incoming line.
                isolate = true
                o[EnvKind.MID_DB] = EnvBuilder().at(0.0, 0f).at(vFrom, 0f).at(vTo, -9f).build()
            }
            if (inFull) {
                inStemsUsed = true
                i[EnvKind.STEM_DRUMS] = EnvBuilder().at(0.0, 0f).at(S, 0f).at(byR(S + b * 0.25), 1f).build()
                i[EnvKind.STEM_OTHER] = EnvBuilder().at(0.0, 0.3f).at(S, 0.3f).at(byR(S + b), 1f).build()
            } else {
                i[EnvKind.HIGH_PASS_HZ] = EnvBuilder(true).at(0.0, 280f).at(S, 280f).at(byR(S + b), 20f).build()
            }
            return finish("instrumental bed under vocal @${r1(entry)}, swap @${r1(S)}")
        }

        fun muffleIn(): Built {
            val len = min(S - A, 8 * bar).coerceAtLeast(min(S - A, 2 * bar))
            val ms = S - len
            i[EnvKind.GAIN] = EnvBuilder().at(0.0, 0f).at(A, 0f).rise(A + 1e-3, byR(min(ms + bar, S)), 0f, 0.9f).at(byR(S), 1f).build()
            i[EnvKind.LOW_PASS_HZ] = EnvBuilder(true).at(0.0, 300f).at(ms, 300f).at(ms + len * 0.7, 2500f).at(byR(S), 20000f).build()
            i[EnvKind.FILTER_RES] = EnvBuilder().at(0.0, 1f).at(ms, 1f).at(ms + len * 0.6, 1f + 1.4f * depth).at(byR(S), 1f).build()
            o[EnvKind.HIGH_PASS_HZ] = EnvBuilder(true).at(0.0, 20f).at(max(ms, 0.0), 20f).at(S, 300f + 500f * depth).build()
            o[EnvKind.FILTER_RES] = EnvBuilder().at(0.0, 1f).at(max(ms, 0.0), 1f).at(S, 1f + 0.8f * depth).at(R, 1f).build()
            outGainHoldThenFall(S, min(R, S + 2 * bar))
            swapLows(inLowBefore = MUFFLE_LOW_DB)
            outFx = DeckFx(svfQ = 0.8f + 0.3f * depth)
            return finish("demuffle ${r1(len)} s into @${r1(S)}")
        }

        fun echoOut(): Built {
            // Wash: HP 20 Hz -> ~1.2 kHz over up to 8 bars, echo on the last beat, dry cut on the 1.
            val len = min(S, 8 * bar).coerceAtLeast(min(S, bar))
            val ws = max(0.0, S - len)
            o[EnvKind.HIGH_PASS_HZ] = EnvBuilder(true).at(0.0, 20f).at(ws, 20f).at(S, 700f + 600f * depth).build()
            o[EnvKind.GAIN] = EnvBuilder().at(-h, 0f).at(0.0, 1f).at(S - 0.1, 1f).at(S, 0f).build()
            o[EnvKind.LOW_DB] = EnvBuilder().at(0.0, 0f).at(S - 0.1, 0f).at(S, KILL_DB).build()
            val delay = if (c.outBpm > 125.0) b * 0.5 else b
            val fb = (0.5f + 0.12f * depth).coerceAtMost(0.62f)
            var fx = DeckFx(echoDelaySec = delay, echoFeedback = fb, echoWet = 0.5f)
            o[EnvKind.ECHO_SEND] = EnvBuilder().at(0.0, 0f).at(max(0.0, S - 1.5 * b), 0f).at(S - 0.02, 0.85f).at(S + 0.12, 0f).build()
            if (c.inputs.level.maxFx >= 2) {
                fx = fx.copy(reverb = true, reverbWet = 0.35f * depth)
                o[EnvKind.REVERB_SEND] = EnvBuilder().at(0.0, 0f).at(ws, 0f).at(S - 0.02, 0.5f).at(S + 0.12, 0f).build()
            }
            outFx = fx
            i[EnvKind.GAIN] = EnvBuilder().at(0.0, 0f).at(max(A, S - 0.02), 0f).rise(max(A, S - 0.02) + 1e-3, byR(S + 0.5 * b), steps = 8).build()
            i[EnvKind.LOW_PASS_HZ] = EnvBuilder(true).at(0.0, 900f).at(S, 900f).at(byR(S + b), 20000f).build()
            return finish("echo out on @${r1(S)} (${if (delay < b) "1/2" else "1"} beat)")
        }

        fun loopExtend(): Built {
            // The outgoing loop holds; the incoming rides in thin under it, bass on a late phrase downbeat.
            val eb = EnvBuilder().at(0.0, 0f)
            if (A > 0.0) eb.at(A, 0f)
            eb.rise(A + 1e-3, byR(S), 0f, 0.85f, steps = 16).at(byR(S + b), 1f)
            i[EnvKind.GAIN] = eb.build()
            i[EnvKind.HIGH_PASS_HZ] = EnvBuilder(true).at(0.0, 160f).at(max(A, S - bar), 160f).at(byR(S), 20f).build()
            o[EnvKind.HIGH_PASS_HZ] = EnvBuilder(true).at(0.0, 20f).at(max(A, 0.0), 20f).at(S, 90f).at(R, 400f).build()
            outGainHoldThenFall(S, R)
            swapLows()
            return finish("loop extend ${c.plan.out.bars}-bar outro, swap @${r1(S)}")
        }

        fun buildDrop(): Built {
            // Outgoing rolls and rises into the incoming DROP; the drop lands dry on the 1.
            val buildBars = if (c.inputs.level == DjLevel.SUBTLE) 4 else 8
            val bs = max(0.0, S - buildBars * bar)
            val rollBeats = if (c.inputs.level == DjLevel.FESTIVAL && S >= 16 * b) 16.0 else 8.0
            var fx = DeckFx(
                loopRoll = true,
                rollStartSec = max(0.0, S - rollBeats * b),
                rollBeatSec = b,
                rollReleaseSec = S,
                rollTotalBeats = rollBeats,
                svfQ = (0.95f + 0.4f * depth).coerceAtMost(1.4f),
            )
            var budget = c.inputs.level.maxFx
            if (budget > 0 && ChoreoCaps.has(c.inputs.caps, ChoreoCaps.RISER)) {
                fx = fx.copy(riser = true, riserBeatSec = b, riserLengthBeats = (S - bs) / b)
                o[EnvKind.RISER_LEVEL] = EnvBuilder().at(0.0, 0f).at(bs, 0f).at(S - 0.02, 0.7f * depth).at(S, 0f).build()
                budget--
            }
            if (budget > 0 && ChoreoCaps.has(c.inputs.caps, ChoreoCaps.GATE)) {
                fx = fx.copy(gate = true, gateBeatSec = b, gateDivision = 4f, gateSwing = 0f)
                o[EnvKind.GATE_DEPTH] = EnvBuilder().at(0.0, 0f).at(max(0.0, S - 2 * bar), 0f).at(S - 0.05, 0.8f * depth).at(S, 0f).build()
                budget--
            }
            if (budget > 0) {
                fx = fx.copy(
                    flanger = true, flangerLfoHz = LoopMix.lfoHzForBeatSec(b),
                    flangerDepthSec = LoopMix.FLANGER_DEPTH_BUILDUP_SEC, flangerFeedback = LoopMix.FLANGER_FEEDBACK,
                    flangerWet = min(0.5f, LoopMix.FLANGER_WET),
                )
                o[EnvKind.FLANGER_WET] = EnvBuilder().at(0.0, 0f).at(bs, 0f).at(S - 0.02, depth).at(S, 0f).build()
            }
            outFx = fx
            o[EnvKind.HIGH_PASS_HZ] = EnvBuilder(true).at(0.0, 20f).at(bs, 30f).at(S - 0.01, 400f + 500f * depth).build()
            o[EnvKind.FILTER_RES] = EnvBuilder().at(0.0, 1f).at(bs, 1f).at(S - 0.01, 1f + 1.2f * depth).at(S, 1f).build()
            o[EnvKind.GAIN] = EnvBuilder().at(-h, 0f).at(0.0, 1f).at(S - 0.03, 1f).at(S, 0f).build()
            o[EnvKind.LOW_DB] = EnvBuilder().at(0.0, 0f).at(S - 0.03, 0f).at(S, KILL_DB).build()
            i[EnvKind.GAIN] = EnvBuilder().at(0.0, 0f).at(max(A, S - 0.012), 0f).at(byR(S + 0.004), 1f).build()
            return finish("build ${buildBars} bars + roll ${rollBeats.toInt()} beats into drop @${r1(S)}")
        }

        fun brakeCut(): Built {
            val len = (b * 1.5).coerceIn(0.3, 1.2)
            val bs = max(0.0, S - len)
            outFx = DeckFx(brake = true, brakeStartSec = bs, brakeLengthSec = S - bs)
            o[EnvKind.BRAKE] = EnvBuilder().at(0.0, 0f).at(bs, 0f).at(S, 1f).build()
            o[EnvKind.GAIN] = EnvBuilder().at(-h, 0f).at(0.0, 1f).at(S - 0.02, 1f).at(S, 0f).build()
            i[EnvKind.GAIN] = EnvBuilder().at(0.0, 0f).at(max(A, S - 0.015), 0f).at(byR(S + 0.005), 1f).build()
            return finish("brake ${r1(S - bs)} s, cut on @${r1(S)}")
        }

        private fun r1(v: Double): String = ((v * 10.0).toLong() / 10.0).toString()
    }

    private val STEM_KINDS = listOf(EnvKind.STEM_VOCALS, EnvKind.STEM_DRUMS, EnvKind.STEM_BASS, EnvKind.STEM_OTHER)

    /** Section roles that make a good exit (outgoing at the swap) and entry (incoming at its audible start). */
    fun exitRole(kind: SectionKind?): Double = when (kind) {
        SectionKind.OUTRO, SectionKind.INST -> 1.0
        SectionKind.CHORUS, SectionKind.DROP -> 0.6
        SectionKind.BRIDGE, SectionKind.BUILD -> 0.55
        null -> 0.5
        SectionKind.VERSE, SectionKind.PRE_CHORUS -> 0.25
        else -> 0.5
    }

    fun entryRole(kind: SectionKind?, move: Move): Double = when (kind) {
        SectionKind.INTRO, SectionKind.INST -> 1.0
        SectionKind.BUILD -> if (move == Move.EDM_BUILD_DROP || move == Move.LOOP_EXTEND) 1.0 else 0.8
        SectionKind.DROP -> if (move.cut) 1.0 else 0.5
        null -> 0.5
        SectionKind.VERSE -> if (move == Move.INSTRUMENTAL_BED) 0.9 else 0.35
        SectionKind.CHORUS -> if (move.cut) 0.7 else 0.35
        else -> 0.5
    }
}
