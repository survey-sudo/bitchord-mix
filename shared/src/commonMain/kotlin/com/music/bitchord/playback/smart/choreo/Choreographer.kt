package com.music.bitchord.playback.smart.choreo

import com.music.bitchord.playback.smart.LoopMix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One ranked (swap beat, move) candidate on the plan's fixed exit window. */
class Candidate(
    val move: Move,
    val swapBeat: Int,
    val swapAt: Double,
    val score: ScoreBreakdown,
) {
    val total: Double get() = score.total
}

/**
 * Pair scoring. Every term is a small interpretable number; see
 * work/E/NOTES.md "Scoring formula". Cue/pair terms are weighted heaviest:
 * DJtransGAN's expert listeners blamed pairing and cue choice more than the
 * mixing itself.
 */
object PairScorer {
    const val W_CUE = 1.0
    const val W_HARM = 0.6
    const val W_ENERGY = 0.5
    const val W_VOCAL = 1.4
    const val W_BASS = 0.6
    const val W_STRETCH = 0.8
    const val REPEAT_ONCE = 0.15
    const val REPEAT_TWICE = 0.8
    const val JITTER = 0.04

    /** The pair is not one to beat-mix: fade (or cut) territory. */
    fun incompatible(c: PairContext): Boolean =
        c.inputs.albumOrder || !c.beatMatched || c.tempoGap > 0.08 || c.beatConf < 0.4 ||
            c.genre == GenreClass.BALLAD ||
            (c.harmony == Harmony.CLASH && c.outVocalProb >= 0.45 && c.inVocalProb >= 0.45)

    fun fit(m: Move, c: PairContext): Double {
        val bad = incompatible(c)
        val dance = c.genre == GenreClass.DANCE
        val subtle = c.inputs.level == DjLevel.SUBTLE
        return when (m) {
            Move.SIMPLE_FADE -> when {
                c.inputs.albumOrder -> 1.2
                c.genre == GenreClass.BALLAD -> 1.05
                bad -> 1.0
                else -> 0.2
            }
            Move.APPLE_STYLE_SMOOTH -> if (bad) 0.15 else 0.6 + (if (c.genre == GenreClass.POP || c.genre == GenreClass.UNKNOWN) 0.1 else 0.0) + (if (subtle) 0.05 else 0.0)
            Move.STEM_BASS_SWAP -> {
                val stems = (c.inputs.outStems?.full == true) || (c.inputs.inStems?.full == true)
                (if (stems) 0.75 else 0.6) + (if (dance) 0.1 else 0.0) - (if (bad) 0.4 else 0.0)
            }
            Move.DRUMS_FIRST -> {
                val intro = c.inSectionAt(c.inAudibleAt + 0.01)
                0.68 + (if (intro == com.music.bitchord.playback.smart.SectionKind.INTRO) 0.08 else 0.0) + (if (dance) 0.08 else 0.0)
            }
            Move.DRUM_SWAP -> 0.66 + (if (dance || c.genre == GenreClass.HIPHOP) 0.1 else 0.0)
            Move.ACAPELLA_OVER_INSTRUMENTAL -> 0.8 + (if (c.harmony == Harmony.SAME) 0.1 else 0.0)
            Move.INSTRUMENTAL_BED -> {
                val entry = c.inVocalEntry(c.inAudibleAt, c.release)
                val base = if (entry != null) 0.82 else 0.3
                if (c.inputs.outStems?.vocalSplit == true || c.inputs.stemsExpected) base else base * 0.75
            }
            Move.MUFFLE_IN -> 0.6 + (if (c.harmony == Harmony.NEAR || c.harmony == Harmony.UNKNOWN) 0.08 else 0.0) +
                (if (c.genre == GenreClass.POP) 0.05 else 0.0)
            Move.FILTER_WASH_ECHO_OUT -> when {
                c.genre == GenreClass.HIPHOP -> 0.85
                bad && subtle -> 0.6
                bad -> 1.05
                else -> 0.4
            }
            Move.LOOP_EXTEND -> 0.7 + (if (dance) 0.1 else 0.0) +
                (if (c.inSectionAt(c.inAudibleAt + 0.01) == com.music.bitchord.playback.smart.SectionKind.BUILD) 0.1 else 0.0)
            // Only offered when a drop sits on a bar line of the window: mixing into the drop is the DJ answer.
            Move.EDM_BUILD_DROP -> 1.2
            Move.BRAKE_CUT -> 0.75
            Move.PHASER_DISSOLVE, Move.FLANGER_DISSOLVE -> 0.45 + (if (dance) 0.1 else 0.0)
            Move.PUMP_BLEND -> 0.7 + (if (c.overlapBars >= 8.0) 0.1 else 0.0)
        }
    }

    private fun harmonicSensitivity(m: Move): Double = when (m) {
        Move.SIMPLE_FADE -> 0.2
        Move.FILTER_WASH_ECHO_OUT, Move.BRAKE_CUT, Move.EDM_BUILD_DROP -> 0.25
        Move.MUFFLE_IN -> 0.6
        Move.DRUMS_FIRST -> 0.5
        Move.DRUM_SWAP -> 0.7
        Move.PHASER_DISSOLVE, Move.FLANGER_DISSOLVE -> 0.8
        Move.ACAPELLA_OVER_INSTRUMENTAL -> 1.5
        Move.LOOP_EXTEND, Move.PUMP_BLEND -> 1.2
        else -> 1.0
    }

    private fun vocalExposure(m: Move, c: PairContext): Double = when (m) {
        Move.SIMPLE_FADE, Move.APPLE_STYLE_SMOOTH -> 1.0
        Move.LOOP_EXTEND -> 0.9
        Move.STEM_BASS_SWAP, Move.PHASER_DISSOLVE, Move.FLANGER_DISSOLVE, Move.PUMP_BLEND -> 0.8
        Move.MUFFLE_IN -> 0.45
        Move.DRUM_SWAP -> 0.35
        Move.DRUMS_FIRST -> 0.3
        Move.ACAPELLA_OVER_INSTRUMENTAL -> 0.1
        Move.INSTRUMENTAL_BED -> if (c.inputs.outStems?.vocalSplit == true || c.inputs.stemsExpected) 0.1 else 0.4
        Move.FILTER_WASH_ECHO_OUT, Move.BRAKE_CUT, Move.EDM_BUILD_DROP -> 0.3
    }

    private fun show(m: Move): Double = when (m) {
        Move.EDM_BUILD_DROP, Move.PHASER_DISSOLVE, Move.FLANGER_DISSOLVE, Move.PUMP_BLEND -> 1.0
        Move.BRAKE_CUT -> 1.5
        Move.DRUM_SWAP -> 0.8
        Move.ACAPELLA_OVER_INSTRUMENTAL -> 0.6
        Move.MUFFLE_IN -> 0.3
        else -> 0.0
    }

    /** Phrase/section alignment of a swap on mix beat [k], 0..1. */
    fun cue(m: Move, c: PairContext, k: Int): Double {
        val roleOut = MoveCatalog.exitRole(c.outSectionAt(max(0.0, c.t(k) - 0.01)))
        val roleIn = MoveCatalog.entryRole(c.inSectionAt(c.inAudibleAt + 0.01), m)
        if (m == Move.SIMPLE_FADE) return 0.4 + 0.15 * roleOut + 0.15 * roleIn
        val t = c.t(k)
        val b = c.beatSecAt(k)
        val tol = max(0.06, b * 0.25)
        val outAlign = run {
            val p = c.outPos(max(0.0, t - 1e-3))
            when {
                p != null && c.inputs.outStructure.boundaryNear(p, tol) -> 1.0
                k % 16 == 0 -> 0.5
                else -> 0.3
            }
        }
        val rel = k - c.kIn
        val inAlign = run {
            val p = c.inPos(t)
            when {
                p != null && c.inputs.inStructure.boundaryNear(p, tol) -> 1.0
                rel % 16 == 0 -> 0.6
                rel % 8 == 0 -> 0.45
                else -> 0.3
            }
        }
        val span = (c.release - c.inAudibleAt).coerceAtLeast(1e-3)
        val frac = ((t - c.inAudibleAt) / span).coerceIn(0.0, 1.0)
        val pref = when (m) {
            Move.FILTER_WASH_ECHO_OUT, Move.BRAKE_CUT -> 0.0
            Move.EDM_BUILD_DROP -> frac
            Move.LOOP_EXTEND, Move.PUMP_BLEND -> 0.75
            Move.MUFFLE_IN, Move.INSTRUMENTAL_BED -> 0.7
            else -> 0.6
        }
        val placement = 1.0 - abs(frac - pref)
        return 0.35 * inAlign + 0.25 * outAlign + 0.15 * roleOut + 0.15 * roleIn + 0.10 * placement
    }

    fun score(m: Move, c: PairContext, k: Int): ScoreBreakdown {
        val t = c.t(k)
        val b = c.beatSecAt(k)
        val f = fit(m, c)
        val cueV = W_CUE * cue(m, c, k)
        val harm = W_HARM * harmonicSensitivity(m) * (c.harmony.score - 0.6) +
            (if (m == Move.EDM_BUILD_DROP && c.harmony == Harmony.BOOST) 0.1 else 0.0)

        val r = c.energyRatio(t)
        val crash = max(0.0, 0.6 - r) / 0.6
        val lift = max(0.0, min(r, 1.6) - 1.1) / 0.5
        val crashSens = when (m) {
            Move.SIMPLE_FADE -> 0.3
            Move.FILTER_WASH_ECHO_OUT, Move.BRAKE_CUT -> 0.5
            else -> 1.0
        }
        val liftAff = when (m) {
            Move.EDM_BUILD_DROP -> 1.0
            Move.DRUM_SWAP -> 0.6
            Move.BRAKE_CUT, Move.PUMP_BLEND -> 0.4
            else -> 0.1
        }
        val energy = W_ENERGY * (-crash * crashSens + 0.4 * lift * liftAff)

        val (va, vb) = if (m.cut) (t - b) to (t + b) else if (m == Move.SIMPLE_FADE) max(c.inAudibleAt, c.release - 10.0) to c.release else c.inAudibleAt to c.release
        var vocal = W_VOCAL * c.vocalClash(va, vb) * vocalExposure(m, c)
        if (m.cut) {
            // Fast vocal-to-vocal handoffs were the least liked in DJtransGAN's listening test.
            val before = c.mean(max(0.0, t - 2 * b), t) { c.outVocalAt(it) } ?: 0.0
            val after = c.mean(t, min(c.element, t + 2 * b)) { c.inVocalAt(it) } ?: 0.0
            vocal += 0.5 * min(before, after)
        }

        val bc = c.bassClash(c.inAudibleAt, c.release)
        val bassExposure = when {
            m.swapsBass || m.cut -> 0.05
            m == Move.SIMPLE_FADE -> min(1.0, min(10.0, c.release - c.inAudibleAt) / 6.0)
            else -> 0.5
        }
        val bass = W_BASS * bc * bassExposure

        val over = max(0.0, c.stretchApplied - 0.03) / 0.05
        val overlapSec = c.release - c.inAudibleAt
        val stretchExposure = when {
            m.cut -> 0.2
            m == Move.SIMPLE_FADE -> if (c.beatMatched) 0.8 else 0.0
            else -> min(1.0, overlapSec / 16.0)
        } * (if (c.genre == GenreClass.DANCE) 0.5 else 1.0)
        val stretch = W_STRETCH * over * stretchExposure

        val conf = when (m) {
            Move.SIMPLE_FADE -> 0.4 * max(0.0, 0.6 - c.beatConf)
            Move.FILTER_WASH_ECHO_OUT, Move.BRAKE_CUT -> 0.2 * (c.outBeatConf - 0.6)
            // The drop is an incoming-side event: its phrase grid is what has to be right.
            Move.EDM_BUILD_DROP -> 0.4 * (c.beatConf - 0.6) + 0.3 * (c.inputs.inStructure.phraseConfidence - 0.5)
            Move.DRUM_SWAP, Move.LOOP_EXTEND, Move.MUFFLE_IN, Move.DRUMS_FIRST ->
                0.4 * (c.beatConf - 0.6) + 0.3 * (c.phraseConf - 0.5)
            else -> 0.4 * (c.beatConf - 0.6)
        }
        val level = c.inputs.level.showBias * show(m) +
            (if (c.inputs.level == DjLevel.SUBTLE && (m == Move.SIMPLE_FADE || m == Move.APPLE_STYLE_SMOOTH)) 0.05 else 0.0)

        val recent = c.inputs.history
        val variety = when {
            recent.size >= 2 && recent[recent.size - 1] == m && recent[recent.size - 2] == m -> REPEAT_TWICE
            recent.isNotEmpty() && recent[recent.size - 1] == m -> REPEAT_ONCE
            else -> 0.0
        }
        val jitter = jitter(c.inputs.seed, c.inputs.pairKey, m)
        return ScoreBreakdown(f, cueV, harm, energy, vocal, bass, stretch, conf, level, variety, jitter)
    }

    /** Deterministic 0..[JITTER) from the seed, the pair and the move. */
    fun jitter(seed: Long, pairKey: String, m: Move): Double {
        var h = seed xor -7046029254386353131L
        for (ch in pairKey) h = (h xor ch.code.toLong()) * 0x100000001B3L
        h = (h xor m.ordinal.toLong()) * 0x100000001B3L
        h = h xor (h ushr 29)
        val unit = ((h ushr 11) and 0xFFFFF).toDouble() / 0xFFFFF.toDouble()
        return unit * JITTER
    }
}

/**
 * Hard safety checks on a built script. Any failure discards the script and
 * the caller keeps the vc80 envelopes.
 */
object ChoreoGuard {
    /** Low-band level above which a bass counts as present (-6 dB). */
    const val BASS_PRESENT = 0.5f
    /** Both lows at this level means a doubled bass. */
    const val BASS_FULL = 0.9f

    fun check(move: Move, built: Built, c: PairContext): String? {
        for ((kind, env) in built.outLanes) if (!sane(env)) return "out $kind not monotonic/finite"
        for ((kind, env) in built.inLanes) if (!sane(env)) return "in $kind not monotonic/finite"
        // Outgoing must equal the dry player at the splice (t0).
        for ((kind, env) in built.outLanes) {
            if (kind.isStem && !built.outUsesStems) continue
            if (!neutral(kind, env.at(0.0))) return "out $kind not neutral at t0 (${env.at(0.0)})"
        }
        // Incoming must equal its player from the release on (the handover is identical audio).
        for ((kind, env) in built.inLanes) {
            if (kind.isStem && !built.inUsesStems) continue
            if (!neutral(kind, env.at(c.release))) return "in $kind not neutral at release (${env.at(c.release)})"
            if (!neutral(kind, env.at(c.element))) return "in $kind not neutral at handover"
            if (!neutral(kind, env.lastValue)) return "in $kind ends non-neutral"
        }
        val outGain = built.outLanes[EnvKind.GAIN] ?: return "no out gain"
        if (outGain.lastValue > 1e-3f) return "outgoing never reaches silence"
        if (outGain.lastTime > c.outStop + 1e-6) return "outgoing gain runs past its stop"
        val inGain = built.inLanes[EnvKind.GAIN] ?: return "no in gain"
        if (c.inAudibleAt > 0.05 && inGain.at(c.inAudibleAt - 0.02) > 1e-3f) return "incoming audible during its hold"
        val fx = built.outFx.fxCount + built.inFx.fxCount
        if (fx > c.inputs.level.maxFx) return "fx budget $fx > ${c.inputs.level.maxFx}"
        if (move.swapsBass) bassViolation(built, c)?.let { return it }
        return null
    }

    /**
     * The low end may never be doubled: never both lows full, and never both
     * above -6 dB for more than one beat (pro-DJ rule).
     */
    fun bassViolation(built: Built, c: PairContext): String? {
        val step = 0.002
        var t = max(0.0, c.inAudibleAt)
        var runStart = -1.0
        val beat = c.plan.out.beatSec.coerceAtLeast(0.1)
        while (t <= c.release) {
            val lo = lowLevel(built.outLanes, built.outUsesStems, t)
            val li = lowLevel(built.inLanes, built.inUsesStems, t)
            if (lo >= BASS_FULL && li >= BASS_FULL) return "bass doubled at ${t}s"
            if (lo > BASS_PRESENT && li > BASS_PRESENT) {
                if (runStart < 0) runStart = t
                if (t - runStart > beat) return "both lows above -6 dB for over a beat at ${runStart}s"
            } else {
                runStart = -1.0
            }
            t += step
        }
        return null
    }

    /** Effective low-band level of one deck: gain x bass stem x low shelf. */
    fun lowLevel(lanes: Map<EnvKind, Env>, stems: Boolean, t: Double): Float {
        val g = lanes[EnvKind.GAIN]?.at(t) ?: 1f
        val s = if (stems) (lanes[EnvKind.STEM_BASS]?.at(t) ?: 1f) else 1f
        val shelf = dbToLin(lanes[EnvKind.LOW_DB]?.at(t) ?: 0f)
        return g * s * shelf
    }

    private fun sane(env: Env): Boolean {
        for (i in env.times.indices) {
            if (!env.times[i].isFinite() || !env.values[i].isFinite()) return false
            if (i > 0 && env.times[i] <= env.times[i - 1]) return false
        }
        return true
    }

    fun neutral(kind: EnvKind, v: Float): Boolean = when (kind) {
        EnvKind.GAIN -> abs(v - 1f) <= 0.02f
        EnvKind.LOW_DB, EnvKind.MID_DB, EnvKind.HIGH_DB -> abs(v) <= 0.5f
        EnvKind.HIGH_PASS_HZ -> v <= 35f
        EnvKind.LOW_PASS_HZ -> v >= 16000f
        EnvKind.FILTER_RES -> abs(v - 1f) <= 0.05f
        EnvKind.STEM_VOCALS, EnvKind.STEM_DRUMS, EnvKind.STEM_BASS, EnvKind.STEM_OTHER -> v >= 0.98f
        else -> v <= 0.02f
    }
}

/**
 * Entry point. [choose] ranks every allowed (swap beat, move) pair on the
 * plan's exit window, builds the best one that passes [ChoreoGuard], and
 * returns it; null keeps the vc80 envelopes.
 */
object Choreographer {
    /** Every allowed candidate, best first, plus why the others were skipped. */
    fun rank(inputs: ChoreoInputs, skipped: MutableList<String>? = null): List<Candidate> {
        val c = PairContext(inputs)
        if (c.release <= 0.5 || c.kRel < 1) return emptyList()
        val out = ArrayList<Candidate>()
        for (m in Move.values()) {
            val why = MoveCatalog.reject(m, c)
            if (why != null) {
                skipped?.add("${m.name}: $why")
                continue
            }
            val beats = MoveCatalog.swapBeats(m, c)
            if (beats.isEmpty()) {
                skipped?.add("${m.name}: no swap beat")
                continue
            }
            var best: Candidate? = null
            for (k in beats) {
                val s = PairScorer.score(m, c, k)
                val cand = Candidate(m, k, c.t(k), s)
                if (best == null || cand.total > best.total + 1e-9) best = cand
            }
            if (best != null) out += best
        }
        return out.sortedWith(compareByDescending<Candidate> { it.total }.thenBy { it.move.ordinal })
    }

    /** The choreographed script for [inputs], or null (keep vc80). [forced] restricts to one move. */
    fun choose(inputs: ChoreoInputs, forced: Move? = null, log: MutableList<String>? = null): ChoreoScript? {
        val c = PairContext(inputs)
        val ranked = rank(inputs, log).let { list -> if (forced != null) list.filter { it.move == forced } else list }
        for (cand in ranked) {
            if (forced == null && cand.total < AutomixV2Choreo.minScore) {
                log?.add("${cand.move.name}: score ${cand.total} below floor")
                break
            }
            val built = runCatching { MoveCatalog.build(cand.move, c, cand.swapBeat) }.getOrElse {
                log?.add("${cand.move.name}: build threw ${it::class.simpleName}")
                null
            } ?: continue
            val bad = ChoreoGuard.check(cand.move, built, c)
            if (bad != null) {
                log?.add("${cand.move.name}: guard $bad")
                continue
            }
            return ChoreoScript(
                move = cand.move,
                level = inputs.level,
                swapAt = cand.swapAt,
                swapBeat = cand.swapBeat,
                outLanes = built.outLanes,
                inLanes = built.inLanes,
                outFx = built.outFx,
                inFx = built.inFx,
                outUsesStems = built.outUsesStems,
                inUsesStems = built.inUsesStems,
                isolate = built.isolate,
                score = cand.score,
                reason = built.note,
            )
        }
        return null
    }

    /**
     * Pass 1 (before decode): where the incoming should start so that one of
     * its drops lands on a bar line of this clock, for EDM_BUILD_DROP. Track
     * seconds of the new incoming start, or null to keep the plan's cue.
     * Only ever skips forward into the incoming intro, by at most
     * [DjLevel.maxEntrySkipSec]; the outgoing geometry is never touched.
     */
    fun dropRetimeStart(inputs: ChoreoInputs): Double? {
        if (!AutomixV2Choreo.retimeEntry) return null
        val level = inputs.level
        if (level.maxEntrySkipSec <= 0.0 || !level.atLeast(Move.EDM_BUILD_DROP.minLevel)) return null
        val plan = inputs.plan
        if (!plan.nativeIncoming || plan.inLoops) return null
        val c = PairContext(inputs)
        if (c.genre != GenreClass.DANCE || c.beatConf < 0.5 || c.tempoGap > 0.08) return null
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

    /** [plan] with its (straight, native) incoming cue moved to [newStart]. */
    fun withIncomingStart(plan: LoopMix.Plan, newStart: Double): LoopMix.Plan =
        plan.copy(inn = plan.inn.copy(loopStart = newStart, loopEnd = newStart, anchorBeats = null))

    /** One log line for telemetry. */
    fun describe(script: ChoreoScript): String =
        "choreo ${script.move.name} @${((script.swapAt * 100).toLong() / 100.0)}s beat ${script.swapBeat} " +
            "level=${script.level} stems=${script.outUsesStems}/${script.inUsesStems} · ${script.reason} · ${script.score}"
}
