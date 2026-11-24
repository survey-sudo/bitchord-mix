package com.music.bitchord.playback.smart.choreo

import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.SectionKind
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.VOCAL_ACTIVE_THRESHOLD
import com.music.bitchord.playback.smart.alignTempoOctave
import com.music.bitchord.playback.smart.camelotCode
import com.music.bitchord.playback.smart.entryKeyConfidence
import com.music.bitchord.playback.smart.entryKeyForMix
import com.music.bitchord.playback.smart.exitKeyConfidence
import com.music.bitchord.playback.smart.exitKeyForMix
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Track position of one deck at a mix second, or null where that deck is not sounding. */
fun interface TrackClock {
    fun at(mixSec: Double): Double?
}

/** Everything the choreographer reads. Only [plan] and [clock] are required. */
class ChoreoInputs(
    val plan: LoopMix.Plan,
    val clock: LoopMix.Clock,
    val out: TrackAnalysis? = null,
    val inn: TrackAnalysis? = null,
    val outStructure: StructureView = StructureView.fromAnalysis(out),
    val inStructure: StructureView = StructureView.fromAnalysis(inn),
    val outStems: StemView? = null,
    val inStems: StemView? = null,
    /** [ChoreoCaps] bits the renderer supports. */
    val caps: Int = 0,
    val level: DjLevel = AutomixV2Choreo.level,
    val pairKey: String = "",
    /** Recent moves, most recent last (from [AutomixV2Choreo.history]). */
    val history: List<Move> = emptyList(),
    /** Recent recipe IDs, most recent last (from [AutomixV2Choreo.history]). */
    val recipeHistory: List<RecipeId> = emptyList(),
    val seed: Long = AutomixV2Choreo.seed,
    val sungOut: List<LoopMix.Span>? = null,
    val sungIn: List<LoopMix.Span>? = null,
    /** The pair is an album played in order: Apple does not mix these, nor do we beyond a fade. */
    val albumOrder: Boolean = false,
    /**
     * Pass-1 (geometry) runs before stems exist: when true, moves that need
     * stems are judged as if the expected stems will arrive.
     */
    val stemsExpected: Boolean = false,
    val stemsExpectedCount: Int = 4,
    /** Actual deck positions (from the renderer knots) when the caller has them. */
    val outClock: TrackClock? = null,
    val inClock: TrackClock? = null,
    /** Preparation-only remix constraint; never changes the user's level or a running script. */
    val excludedRecipes: Set<RecipeId> = emptySet(),
)

/** Coarse genre class for blend-length, recipe weighting, and move preferences. */
enum class GenreClass { DANCE, HIPHOP, POP, ROCK, BALLAD, UNKNOWN }

/** Camelot relation between the outgoing exit key and the incoming entry key. */
enum class Harmony(val score: Double) {
    SAME(1.0), RELATIVE(0.9), ADJACENT(0.85), BOOST(0.7), NEAR(0.45), CLASH(0.1), UNKNOWN(0.6);

    val compatible: Boolean get() = this == SAME || this == RELATIVE || this == ADJACENT
}

/**
 * Interpretable pair features on the plan's clock. Pure; built once per
 * choreography call. Times are mix seconds unless named *Track*.
 */
class PairContext(val inputs: ChoreoInputs) {
    val plan: LoopMix.Plan = inputs.plan
    val clock: LoopMix.Clock = inputs.clock
    val out: TrackAnalysis? = inputs.out
    val inn: TrackAnalysis? = inputs.inn

    val beatTimes: DoubleArray = clock.beatTimes
    val kRel: Int = clock.beats
    val release: Double = clock.releaseAt
    val element: Double = clock.elementAt
    /** Incoming is silent (held at its cue) before this mix second. */
    val inAudibleAt: Double = max(0.0, clock.incomingHoldSec)
    val kIn: Int = run {
        var k = 0
        while (k < kRel && beatTimes[k] < inAudibleAt - 1e-3) k++
        k
    }
    val echoHold: Double = if (plan.echoTail) plan.out.beatSec * 8 else 0.0
    /** The outgoing deck renders nothing after this (exit gate); its gain must be 0 before. */
    val outStop: Double = release + 0.05 + echoHold + plan.exitTailSeconds
    val overlapBars: Double = (kRel - kIn) / 4.0

    val outBpm: Double = plan.out.bpm.takeIf { it > 1.0 } ?: (out?.bpm ?: 120.0)
    val inBpm: Double = plan.inn.bpm.takeIf { it > 1.0 } ?: (inn?.bpm ?: outBpm)

    /** True when the decks run in tempo (the ramp arrives at the incoming tempo). */
    val beatMatched: Boolean = run {
        val target = plan.targetBpm
        val inFolded = inBpm / plan.fold.takeIf { it > 0.0 }.let { it ?: 1.0 }
        target > 1.0 && abs(target - inFolded) / target <= 0.01
    }

    /** Stretch the plan applies somewhere in the mix, as a fraction (0.04 = 4%). */
    val stretchApplied: Double = if (beatMatched) abs(inBpm / (plan.fold.takeIf { it > 0.0 } ?: 1.0) / outBpm - 1.0) else 0.0

    /** Folded tempo gap of the pair itself, independent of the chosen geometry. */
    val tempoGap: Double = run {
        val a = out?.bpm?.takeIf { it > 1.0 } ?: outBpm
        val b = inn?.bpm?.takeIf { it > 1.0 } ?: inBpm
        val folded = alignTempoOctave(a, b)
        if (folded > 0.0) abs(folded / a - 1.0) else 1.0
    }

    val outBeatConf: Double = out?.tailBeats?.confidence?.takeIf { it > 0.0 } ?: (out?.beatConfidence ?: 0.0)
    val inBeatConf: Double = inn?.headBeats?.confidence?.takeIf { it > 0.0 } ?: (inn?.beatConfidence ?: 0.0)
    val beatConf: Double = min(outBeatConf, inBeatConf)

    val phraseConf: Double = min(inputs.outStructure.phraseConfidence, inputs.inStructure.phraseConfidence)

    val outKeyConf: Double = out?.let { exitKeyConfidence(it) } ?: 0.0
    val inKeyConf: Double = inn?.let { entryKeyConfidence(it) } ?: 0.0
    val harmony: Harmony = run {
        val a = out?.let { camelotCode(exitKeyForMix(it)) }
        val b = inn?.let { camelotCode(entryKeyForMix(it)) }
        if (a == null || b == null || min(outKeyConf, inKeyConf) < 0.35) Harmony.UNKNOWN else relation(a, b)
    }

    val genre: GenreClass = classify()

    val outVocalProb: Double = out?.vocalProbability ?: 0.0
    val inVocalProb: Double = inn?.vocalProbability ?: 0.0

    /** Seconds per beat around mix beat [k]. */
    fun beatSecAt(k: Int): Double {
        val i = k.coerceIn(0, beatTimes.size - 2)
        return (beatTimes[i + 1] - beatTimes[i]).takeIf { it > 0.05 } ?: plan.out.beatSec.coerceAtLeast(0.1)
    }

    fun t(k: Int): Double = beatTimes[k.coerceIn(0, beatTimes.size - 1)]

    fun outPos(mix: Double): Double? = inputs.outClock?.at(mix) ?: LoopMix.positionAt(clock, LoopMix.Side.OUT, mix)

    fun inPos(mix: Double): Double? {
        if (mix < inAudibleAt - 1e-6) return null
        inputs.inClock?.at(mix)?.let { return it }
        if (mix <= element) return LoopMix.positionAt(clock, LoopMix.Side.IN, mix)
        // Past the deck: the incoming player runs on at rate 1 from the handover.
        return clock.elementTrackTime + (mix - element)
    }

    // ---- activity probes -------------------------------------------------------------------

    /** Vocal activity 0..1 of the outgoing song at mix second [mix], stems first. */
    fun outVocalAt(mix: Double): Double? {
        val p = outPos(mix) ?: return null
        return vocalAt(out, inputs.outStems, inputs.sungOut, p)
    }

    fun inVocalAt(mix: Double): Double? {
        val p = inPos(mix) ?: return null
        return vocalAt(inn, inputs.inStems, inputs.sungIn, p)
    }

    /** Mean of [probe] over mix [a]..[b], sampled every half beat; null when nothing measured. */
    fun mean(a: Double, b: Double, probe: (Double) -> Double?): Double? {
        if (b <= a) return null
        val step = max(0.1, plan.out.beatSec * 0.5)
        var t = a
        var sum = 0.0
        var n = 0
        while (t < b) {
            val v = probe(t)
            if (v != null && v.isFinite()) {
                sum += v
                n++
            }
            t += step
        }
        return if (n == 0) null else sum / n
    }

    /** Mean simultaneous vocal (min of the two) over mix [a]..[b], 0 when unmeasured. */
    fun vocalClash(a: Double, b: Double): Double =
        mean(a, b) { m ->
            val o = outVocalAt(m)
            val i = inVocalAt(m)
            if (o == null || i == null) null else min(o, i)
        } ?: 0.0

    /** Mean simultaneous bass (min of the two levels) over mix [a]..[b], 0 when unmeasured. */
    fun bassClash(a: Double, b: Double): Double =
        mean(a, b) { m ->
            val o = outPos(m)?.let { bassAt(out, inputs.outStems, it) }
            val i = inPos(m)?.let { bassAt(inn, inputs.inStems, it) }
            if (o == null || i == null) null else min(o, i)
        } ?: 0.0

    /** Incoming / outgoing energy around the swap; 1 = level, < 0.6 = a crash. */
    fun energyRatio(swapMix: Double): Double {
        val o = out ?: return 1.0
        val i = inn ?: return 1.0
        val oEnd = outPos(max(0.0, swapMix - 0.01)) ?: return 1.0
        val oE = trackEnergy(o, oEnd - 8 * plan.out.beatSec, oEnd) ?: return 1.0
        val iStart = inPos(max(swapMix, inAudibleAt)) ?: return 1.0
        val iE = trackEnergy(i, iStart, iStart + 16 * plan.inn.beatSec) ?: return 1.0
        if (oE <= 1e-6) return 1.0
        return (iE / oE).coerceIn(0.0, 4.0)
    }

    /** First mix second in [from]..[to] where the incoming vocal is clearly singing, or null. */
    fun inVocalEntry(from: Double, to: Double): Double? {
        val step = max(0.1, plan.out.beatSec * 0.5)
        var t = max(from, inAudibleAt)
        while (t < to) {
            val v = inVocalAt(t)
            if (v != null && v >= 0.35) return t
            t += step
        }
        return null
    }

    fun outSectionAt(mix: Double): SectionKind? = outPos(mix)?.let { inputs.outStructure.kindAt(it) }
    fun inSectionAt(mix: Double): SectionKind? = inPos(mix)?.let { inputs.inStructure.kindAt(it) }

    // ---- private ---------------------------------------------------------------------------

    private fun classify(): GenreClass {
        val a = out
        val b = inn
        if (a == null || b == null) return GenreClass.UNKNOWN
        fun soft(x: TrackAnalysis): Boolean {
            val e = x.energyCurve
            if (e.size < 8) return false
            val peak = e.maxOf { it.energy }
            if (peak <= 0.0) return false
            val mean = e.sumOf { it.energy } / e.size
            return mean / peak < 0.45
        }
        val bothSteady = outBeatConf >= 0.6 && inBeatConf >= 0.6
        val dance = bothSteady && outBpm in 112.0..152.0 && inBpm in 112.0..152.0
        if (dance && (a.vocalProbability < 0.7 || b.vocalProbability < 0.7)) return GenreClass.DANCE
        if (beatConf < 0.35 || ((a.bpm < 92.0 || b.bpm < 92.0) && (soft(a) || soft(b)) && beatConf < 0.55)) return GenreClass.BALLAD
        val hipBpm = { x: Double -> x in 78.0..102.0 || x in 150.0..180.0 }
        if (hipBpm(outBpm) && hipBpm(inBpm) && a.vocalProbability >= 0.5 && b.vocalProbability >= 0.5) return GenreClass.HIPHOP
        if (dance) return GenreClass.DANCE
        if (!bothSteady && beatConf >= 0.35 && !soft(a) && !soft(b) && (a.vocalProbability < 0.7 || b.vocalProbability < 0.7)) {
            return GenreClass.ROCK
        }
        return GenreClass.POP
    }

    companion object {
        /** Camelot relation from two codes such as "8A" / "9B". */
        fun relation(a: String, b: String): Harmony {
            val pa = parse(a) ?: return Harmony.UNKNOWN
            val pb = parse(b) ?: return Harmony.UNKNOWN
            if (pa.first == pb.first) return if (pa.second == pb.second) Harmony.SAME else Harmony.RELATIVE
            val up = ((pb.first - pa.first) % 12 + 12) % 12
            val dist = min(up, 12 - up)
            if (pa.second == pb.second) {
                if (dist == 1) return Harmony.ADJACENT
                if (up == 2 || up == 7) return Harmony.BOOST
                if (dist == 2) return Harmony.NEAR
                return Harmony.CLASH
            }
            return if (dist == 1) Harmony.NEAR else Harmony.CLASH
        }

        private fun parse(code: String): Pair<Int, Char>? {
            if (code.length < 2) return null
            val letter = code.last()
            val n = code.dropLast(1).toIntOrNull() ?: return null
            if (letter != 'A' && letter != 'B' || n !in 1..12) return null
            return n to letter
        }

        /** Vocal 0..1 at track second [p]: stems, then certain lyrics, then the model mask, then the legacy mask. */
        fun vocalAt(a: TrackAnalysis?, stems: StemView?, sung: List<LoopMix.Span>?, p: Double): Double? {
            stems?.vocalShare(p - 0.25, p + 0.25)?.let { share ->
                // A vocal stem at a third of the energy is a lead vocal.
                return (share / 0.33).coerceIn(0.0, 1.0)
            }
            if (sung != null && sung.any { p >= it.start && p <= it.certainUntil }) return 1.0
            if (a == null) return null
            for (w in a.vocalWindows) {
                if (p < w.startTime || p >= w.endTime) continue
                val i = ((p - w.startTime) * w.frameRate).toInt().coerceIn(0, w.values.size - 1)
                val v = w.values[i].toDouble()
                return if (v > VOCAL_ACTIVE_THRESHOLD) v.coerceIn(0.0, 1.0) else v * 0.5
            }
            val curve = a.energyCurve
            val mask = a.vocalActivityMask
            if (curve.isNotEmpty() && mask.size == curve.size) {
                val i = nearestIndex(curve.map { it.time }, p) ?: return null
                return mask[i].coerceIn(0.0, 1.0)
            }
            return null
        }

        /** Bass level 0..1 at track second [p]: 4-stem share, else the low-band curve against its own peak. */
        fun bassAt(a: TrackAnalysis?, stems: StemView?, p: Double): Double? {
            stems?.bassShare(p - 0.25, p + 0.25)?.let { return (it / 0.3).coerceIn(0.0, 1.0) }
            val curve = a?.lowEnergyCurve ?: return null
            if (curve.size < 4) return null
            val peak = curve.maxOf { it.energy }
            if (peak <= 0.0) return null
            val i = nearestIndex(curve.map { it.time }, p) ?: return null
            return (curve[i].energy / peak).coerceIn(0.0, 1.0)
        }

        fun trackEnergy(a: TrackAnalysis, from: Double, to: Double): Double? {
            val pts = a.energyCurve.filter { it.time >= from && it.time <= to }
            if (pts.isEmpty()) {
                val i = nearestIndex(a.energyCurve.map { it.time }, (from + to) / 2) ?: return null
                return a.energyCurve[i].energy
            }
            return pts.sumOf { it.energy } / pts.size
        }

        private fun nearestIndex(times: List<Double>, p: Double): Int? {
            if (times.isEmpty()) return null
            var best = 0
            var bestD = Double.POSITIVE_INFINITY
            for (i in times.indices) {
                val d = abs(times[i] - p)
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            return if (bestD <= 2.0) best else null
        }
    }
}
