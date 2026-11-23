package com.music.bitchord.playback.smart.choreo

import kotlin.math.ln
import kotlin.math.exp
import kotlin.math.pow

/**
 * The move catalogue. [minLevel] is the lowest [DjLevel] that may use it;
 * requirements beyond that live in [MoveCatalog.requirementsOf].
 */
enum class Move(val label: String, val minLevel: DjLevel, val swapsBass: Boolean, val cut: Boolean) {
    /** Equal-power fade, no EQ, no FX: ballads, acoustic, incompatible tempo/key, low confidence. */
    SIMPLE_FADE("Fade", DjLevel.SUBTLE, swapsBass = false, cut = false),
    /** Apple-style: beat-matched equal-power blend with a gentle EQ bass handoff, no FX. */
    APPLE_STYLE_SMOOTH("Smooth blend", DjLevel.SUBTLE, swapsBass = true, cut = false),
    /** Bass stems swap on one phrase downbeat (EQ-kill fallback without stems). */
    STEM_BASS_SWAP("Bass swap", DjLevel.SUBTLE, swapsBass = true, cut = false),
    /** Incoming drums ride in first under the outgoing song; everything else follows on the swap. */
    DRUMS_FIRST("Drums first", DjLevel.SUBTLE, swapsBass = true, cut = false),
    /** Drums change hands on one downbeat, bass on the next phrase. */
    DRUM_SWAP("Drum swap", DjLevel.CLUB, swapsBass = true, cut = false),
    /** Outgoing vocal rides over the incoming instrumental (harmonic + vocal-clash guarded). */
    ACAPELLA_OVER_INSTRUMENTAL("Acapella", DjLevel.CLUB, swapsBass = true, cut = false),
    /** Outgoing instrumental stays under the incoming vocal entry. */
    INSTRUMENTAL_BED("Instrumental bed", DjLevel.SUBTLE, swapsBass = true, cut = false),
    /** Incoming starts low-passed and opens over a phrase (resonant) while outgoing high-passes out. */
    MUFFLE_IN("Muffle in", DjLevel.SUBTLE, swapsBass = true, cut = false),
    /** Outgoing washes up a high-pass with reverb, echoes out on the bar; incoming lands clean on the 1. */
    FILTER_WASH_ECHO_OUT("Echo out", DjLevel.SUBTLE, swapsBass = false, cut = true),
    /** The looping outgoing outro covers the incoming build; long blend on the loop. */
    LOOP_EXTEND("Loop extend", DjLevel.SUBTLE, swapsBass = true, cut = false),
    /** Outgoing loop-rolls with riser + gate into the incoming DROP downbeat. */
    EDM_BUILD_DROP("Build & drop", DjLevel.SUBTLE, swapsBass = false, cut = true),
    /** Vinyl brake / tape-stop on the outgoing, incoming on the 1: genre jumps. */
    BRAKE_CUT("Brake", DjLevel.FESTIVAL, swapsBass = false, cut = true),
    /** Outgoing dissolves through a phaser while the incoming blends in. */
    PHASER_DISSOLVE("Phaser", DjLevel.CLUB, swapsBass = true, cut = false),
    /** Outgoing dissolves through a jet flanger while the incoming blends in. */
    FLANGER_DISSOLVE("Flanger", DjLevel.CLUB, swapsBass = true, cut = false),
    /** House/EDM: outgoing pumps (sidechain feel) under the incoming kick, long blend. */
    PUMP_BLEND("Pump", DjLevel.CLUB, swapsBass = true, cut = false),
}

/**
 * Envelope kinds the choreographer writes. The app bridge maps each one to a
 * renderer kind; [v2] kinds are only written when the renderer's
 * V2_CAPABILITIES says so (stem kinds only when a StemSet is attached).
 */
enum class EnvKind(val neutral: Float, val exponential: Boolean, val v2: Boolean, val cap: Int) {
    GAIN(1f, false, false, 0),
    LOW_DB(0f, false, false, 0),
    MID_DB(0f, false, false, 0),
    HIGH_DB(0f, false, false, 0),
    HIGH_PASS_HZ(20f, true, false, 0),
    LOW_PASS_HZ(20000f, true, false, 0),
    ECHO_SEND(0f, false, false, 0),
    REVERB_SEND(0f, false, false, 0),
    FLANGER_WET(0f, false, false, 0),
    STEM_VOCALS(1f, false, true, ChoreoCaps.STEMS),
    STEM_DRUMS(1f, false, true, ChoreoCaps.STEMS),
    STEM_BASS(1f, false, true, ChoreoCaps.STEMS),
    STEM_OTHER(1f, false, true, ChoreoCaps.STEMS),
    FILTER_RES(1f, false, true, ChoreoCaps.FILTER_RES),
    PHASER_WET(0f, false, true, ChoreoCaps.PHASER),
    GATE_DEPTH(0f, false, true, ChoreoCaps.GATE),
    RISER_LEVEL(0f, false, true, ChoreoCaps.RISER),
    PUMP_DEPTH(0f, false, true, ChoreoCaps.PUMP),
    BRAKE(0f, false, true, ChoreoCaps.BRAKE),
    ;

    val isStem: Boolean get() = this == STEM_VOCALS || this == STEM_DRUMS || this == STEM_BASS || this == STEM_OTHER

    companion object {
        /** The vc80 kinds a script always writes on both decks so it fully owns the transition. */
        val LEGACY: List<EnvKind> = listOf(GAIN, LOW_DB, MID_DB, HIGH_DB, HIGH_PASS_HZ, LOW_PASS_HZ, ECHO_SEND, REVERB_SEND, FLANGER_WET)
    }
}

/**
 * Renderer capability bits as the choreographer reads them. The app bridge
 * translates `LoopMixRenderer.V2_CAPABILITIES` into this layout (one table,
 * see ChoreoRendererBridge) so a late renumbering in stream C is a one-line fix.
 */
object ChoreoCaps {
    const val STEMS = 1 shl 0
    const val FILTER_RES = 1 shl 1
    const val PHASER = 1 shl 2
    const val GATE = 1 shl 3
    const val RISER = 1 shl 4
    const val PUMP = 1 shl 5
    const val BRAKE = 1 shl 6
    const val LOUDNESS = 1 shl 7
    const val ALL = STEMS or FILTER_RES or PHASER or GATE or RISER or PUMP or BRAKE or LOUDNESS

    fun has(caps: Int, bit: Int): Boolean = bit == 0 || (caps and bit) == bit
}

/** One automation curve in mix seconds (t0 = the outgoing loop start). */
class Env(val times: DoubleArray, val values: FloatArray, val exponential: Boolean = false) {
    init {
        require(times.size == values.size && times.isNotEmpty()) { "env shape" }
    }

    /** Value at [t]: held outside the knots, linear (or log-domain for [exponential]) between. */
    fun at(t: Double): Float {
        if (t <= times[0]) return values[0]
        val last = times.size - 1
        if (t >= times[last]) return values[last]
        var lo = 0
        var hi = last
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid else hi = mid
        }
        val span = times[hi] - times[lo]
        val f = if (span <= 0.0) 1.0 else (t - times[lo]) / span
        val a = values[lo].toDouble()
        val b = values[hi].toDouble()
        return if (exponential && a > 0.0 && b > 0.0) {
            exp(ln(a) + (ln(b) - ln(a)) * f).toFloat()
        } else {
            (a + (b - a) * f).toFloat()
        }
    }

    val lastTime: Double get() = times[times.size - 1]
    val lastValue: Float get() = values[values.size - 1]

    companion object {
        fun constant(value: Float, exponential: Boolean = false): Env = Env(doubleArrayOf(0.0), floatArrayOf(value), exponential)
    }
}

/**
 * Builds an [Env] from points in any order of insertion that is already
 * time-sorted; a point at or before the previous time is nudged 0.1 ms later
 * so the native envelope always sees strictly increasing times.
 */
class EnvBuilder(private val exponential: Boolean = false) {
    private val t = ArrayList<Double>()
    private val v = ArrayList<Float>()

    fun at(time: Double, value: Float): EnvBuilder {
        if (!time.isFinite() || !value.isFinite()) return this
        val safe = if (t.isNotEmpty() && time <= t[t.size - 1]) t[t.size - 1] + 1e-4 else time
        t += safe
        v += value
        return this
    }

    /** Equal-power fall (cos) from [from] to [to], [gain0] -> 0, sampled [steps] times. */
    fun fall(from: Double, to: Double, gain0: Float = 1f, steps: Int = 12): EnvBuilder {
        val n = steps.coerceAtLeast(1)
        for (i in 0..n) {
            val p = i.toFloat() / n
            at(from + (to - from) * p, gain0 * kotlin.math.cos(p * kotlin.math.PI.toFloat() / 2f))
        }
        return this
    }

    /** Equal-power rise (sin) from [from] to [to], [g0] -> [g1], sampled [steps] times. */
    fun rise(from: Double, to: Double, g0: Float = 0f, g1: Float = 1f, steps: Int = 12): EnvBuilder {
        val n = steps.coerceAtLeast(1)
        for (i in 0..n) {
            val p = i.toFloat() / n
            val s = kotlin.math.sin(p * kotlin.math.PI.toFloat() / 2f)
            at(from + (to - from) * p, g0 + (g1 - g0) * s)
        }
        return this
    }

    val isEmpty: Boolean get() = t.isEmpty()

    fun build(): Env = if (t.isEmpty()) Env.constant(0f, exponential) else Env(t.toDoubleArray(), v.toFloatArray(), exponential)
}

/** Effect setup for one deck. Zero / false means off. */
data class DeckFx(
    val echoDelaySec: Double = 0.0,
    val echoFeedback: Float = 0f,
    val echoWet: Float = 0.55f,
    val reverb: Boolean = false,
    val reverbRoom: Float = 2.2f,
    val reverbDamping: Float = 0.55f,
    val reverbWet: Float = 0.35f,
    val flanger: Boolean = false,
    val flangerLfoHz: Double = 0.0,
    val flangerDepthSec: Double = 0.003,
    val flangerFeedback: Float = 0.5f,
    val flangerWet: Float = 0.4f,
    val phaser: Boolean = false,
    val phaserRateBeats: Double = 8.0,
    val phaserDepth: Float = 0.6f,
    val phaserFeedback: Float = 0.4f,
    val gate: Boolean = false,
    val gateBeatSec: Double = 0.5,
    /** Gate steps per beat (renderer: Float, 4 = 1/16 notes; negative = stutter). */
    val gateDivision: Float = 4f,
    val gateSwing: Float = 0f,
    val riser: Boolean = false,
    val riserBeatSec: Double = 0.5,
    val riserLengthBeats: Double = 16.0,
    val pump: Boolean = false,
    val pumpBeatSec: Double = 0.5,
    val brake: Boolean = false,
    val brakeStartSec: Double = 0.0,
    val brakeLengthSec: Double = 0.0,
    val loopRoll: Boolean = false,
    val rollStartSec: Double = 0.0,
    val rollBeatSec: Double = 0.5,
    val rollReleaseSec: Double = 0.0,
    val rollTotalBeats: Double = 8.0,
    /** Cytomic SVF Q for the deck's filters; null leaves the renderer default. */
    val svfQ: Float? = null,
) {
    /** How many audible effects this deck uses (the "1 FX per transition" budget). */
    val fxCount: Int
        get() = (if (echoDelaySec > 0.0) 1 else 0) + (if (reverb) 1 else 0) + (if (flanger) 1 else 0) +
            (if (phaser) 1 else 0) + (if (gate) 1 else 0) + (if (riser) 1 else 0) + (if (pump) 1 else 0) +
            (if (brake) 1 else 0)

    companion object {
        val NONE = DeckFx()
    }
}

/** Why a candidate scored what it did; every term is logged. */
data class ScoreBreakdown(
    val fit: Double,
    val cue: Double,
    val harmonic: Double,
    val energy: Double,
    val vocalClash: Double,
    val bassClash: Double,
    val stretch: Double,
    val confidence: Double,
    val level: Double,
    val variety: Double,
    val jitter: Double,
) {
    val total: Double
        get() = fit + cue + harmonic + energy - vocalClash - bassClash - stretch + confidence + level - variety + jitter

    override fun toString(): String =
        "fit=${r(fit)} cue=${r(cue)} harm=${r(harmonic)} energy=${r(energy)} vclash=-${r(vocalClash)} " +
            "bclash=-${r(bassClash)} stretch=-${r(stretch)} conf=${r(confidence)} level=${r(level)} " +
            "variety=-${r(variety)} jitter=${r(jitter)} total=${r(total)}"

    private fun r(v: Double): String = ((v * 1000.0).toLong() / 1000.0).toString()
}

/**
 * One chosen transition on the plan's clock: per-deck lanes in mix seconds,
 * per-deck effects, and whether stems were relied on.
 */
class ChoreoScript(
    val move: Move,
    val level: DjLevel,
    /** Mix second of the swap / landing downbeat. */
    val swapAt: Double,
    /** Mix beat index of [swapAt] on the plan clock. */
    val swapBeat: Int,
    val outLanes: Map<EnvKind, Env>,
    val inLanes: Map<EnvKind, Env>,
    val outFx: DeckFx,
    val inFx: DeckFx,
    /** Stem lanes on each deck are meaningful only when that deck renders a StemSet. */
    val outUsesStems: Boolean,
    val inUsesStems: Boolean,
    /** Both decks should run the LR4 isolator (mid/high lanes in use). */
    val isolate: Boolean,
    val score: ScoreBreakdown,
    val reason: String,
) {
    val label: String get() = move.label

    /** Value of [kind] on the given deck at [t], neutral when the script does not write it. */
    fun value(outDeck: Boolean, kind: EnvKind, t: Double): Float {
        val lane = (if (outDeck) outLanes else inLanes)[kind] ?: return kind.neutral
        return lane.at(t)
    }
}

internal fun dbToLin(db: Float): Float = if (db <= -80f) 0f else 10.0.pow(db / 20.0).toFloat()
