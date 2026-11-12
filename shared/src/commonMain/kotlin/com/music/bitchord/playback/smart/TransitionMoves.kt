package com.music.bitchord.playback.smart

import kotlin.math.min

/**
 * One automation lane. Times are master beats relative to the landing L = 0.
 * Values are dB for gains and band shelves, Hz for filters, or 0..1 for sends.
 */
class Lane(
    val beats: DoubleArray,
    val values: FloatArray,
    val shape: String = "lin",
    val exponential: Boolean = false,
)

/**
 * The score sheet for one chosen move, plus the [LoopMix.Plan] the renderer
 * already knows how to play. Lanes replace the generic blend envelopes.
 */
class PhraseScript(
    val choice: PhraseChoice,
    val plan: LoopMix.Plan,
    val outGain: Lane,
    val inGain: Lane,
    val outLow: Lane,
    val inLow: Lane,
    val outMid: Lane,
    val inMid: Lane,
    val outHigh: Lane,
    val inHigh: Lane,
    val outHp: Lane,
    val inHp: Lane,
    val outLp: Lane,
    val echoSend: Lane,
    val echoDelaySec: Double,
    val echoFeedback: Float,
    /** Mix seconds, on the plan's clock, before which B stays at its cue. */
    val incomingHoldSec: Double,
    val flanger: Boolean,
    val bTrimDb: Float,
    val phaseMs: Double,
)

/**
 * Compiles a [PhraseChoice] into a plan and the lanes of its score sheet.
 * Policy b (the default) ramps A toward B before B is heard. B is at 1.000×
 * from the moment it is audible, which is at or before two beats prior to L.
 */
object TransitionMoves {
    const val MUTE_DB = -80f
    const val KILL_DB = -40f

    fun compile(
        choice: PhraseChoice,
        outBpm: Double,
        inBpm: Double,
        flanger: Boolean = false,
    ): PhraseScript {
        val legacy = choice.legacyLoop
        if (legacy != null) {
            return sheet(choice, legacy, flanger, holdSec = 0.0, echoDelaySec = 0.0, echoFeedback = 0f)
        }
        val outBeat = if (outBpm > 1.0) 60.0 / outBpm else 0.5
        val inBeat = if (inBpm > 1.0) 60.0 / inBpm else outBeat
        val bar = outBeat * 4.0
        val prep = choice.tempoPrepBars.coerceAtLeast(0)
        val overlap = choice.overlapBars.coerceAtLeast(if (choice.template == MixTemplate.M6 || choice.template == MixTemplate.CUT_ON_ONE || choice.template == MixTemplate.M10) 0 else 1)
        val exit = if (overlap == 0) 1 else overlap
        val ramp = if (prep > 0) prep else if (overlap == 0) 0 else 1
        val total = (ramp + exit).coerceAtLeast(1)
        val release = choice.exit.releaseSec
        val start = (release - total * bar).coerceAtLeast(0.0)
        val looping = choice.template == MixTemplate.M2
        val loopLen = 4.0 * outBeat
        val outPick = if (looping) {
            LoopMix.Pick(
                loopStart = (release - loopLen).coerceAtLeast(0.0),
                loopEnd = release,
                bars = 4,
                beatSec = outBeat,
                bpm = outBpm,
                vocal = 0.0,
                checkedBy = LoopMix.Check.MASK,
            )
        } else {
            LoopMix.Pick(start, start, 4, outBeat, outBpm, 0.0, LoopMix.Check.MASK)
        }
        val echo = choice.echoGated || choice.template == MixTemplate.M6
        val delayBeats = PhraseConfig.echoDelayBeats(inBpm, choice.template == MixTemplate.ROLLING).second
        val delaySec = if (echo) delayBeats * inBeat else 0.0
        val plan = LoopMix.Plan(
            out = outPick,
            inn = LoopMix.Pick(choice.entry.startSec, choice.entry.startSec, 4, inBeat, inBpm, 0.0, LoopMix.Check.MASK),
            fold = 1.0,
            enterBars = 0,
            rampBars = ramp,
            exitBars = exit,
            totalBars = total,
            outLoops = looping,
            inLoops = false,
            nativeIncoming = true,
            echoTail = echo,
            carve = choice.template == MixTemplate.M4 || choice.template == MixTemplate.M5 || choice.template == MixTemplate.VOCAL_OVER_OUTRO,
            rung = choice.rung,
        )
        val hold = ramp * bar
        return sheet(choice, plan, flanger, hold, delaySec, PhraseConfig.ECHO_FEEDBACK)
    }

    /** Beats from t0 to the landing, for a compiled plan: the ramp, then the overlap. */
    fun landingBeat(script: PhraseScript): Double {
        val plan = script.plan
        return (plan.rampBars + plan.exitBars).coerceAtLeast(1) * 4.0
    }

    private fun sheet(
        choice: PhraseChoice,
        plan: LoopMix.Plan,
        flanger: Boolean,
        holdSec: Double,
        echoDelaySec: Double,
        echoFeedback: Float,
    ): PhraseScript {
        val o = choice.overlapBars.coerceAtLeast(0)
        val half = -o / 2.0 * 4.0
        val start = -o * 4.0
        val kill = KILL_DB
        val mute = MUTE_DB
        val (outGain, inGain, outLow, inLow, outMid, inMid, outHigh, inHigh, outHp, echoSend) = when (choice.template) {
            MixTemplate.M1, MixTemplate.M3 -> lanes(
                outGain = lane(start, 0f, half, 0f, -o.toDouble(), -9f, 1.0, mute, shape = "ep"),
                inGain = lane(start, mute, start + o.toDouble(), -9f, half, -3f, -o.toDouble(), 0f, shape = "ep"),
                outLow = lane(start, 0f, half, 0f, half + 0.01, kill),
                inLow = lane(start, kill, half, kill, half + 0.01, 0f),
                outMid = lane(start, 0f, 0.0, 0f),
                inMid = lane(start, 0f, 0.0, 0f),
                outHigh = lane(start, 0f, 0.0, 0f),
                inHigh = lane(start, -6f, start / 2, -3f, half, 0f),
                outHp = lane(half, 20f, -2.0, 180f, 0.0, 250f, exponential = true),
                echoSend = lane(start, 0f, 0.0, 0f),
            )
            MixTemplate.M4 -> lanes(
                outGain = lane(-choice.entry.leadInBars * 4.0, 0f, 4.0, mute, shape = "ep"),
                inGain = lane(-choice.entry.leadInBars * 4.0, -6f, 0.0, 0f, shape = "ep"),
                outLow = lane(-4.0, 0f, 0.0, kill),
                inLow = lane(-choice.entry.leadInBars * 4.0, kill, 0.0, 0f),
                outMid = lane(0.0, -4.5f, 2.0, -9f, 4.0, mute),
                inMid = lane(0.0, 0f),
                outHigh = lane(0.0, -3f, 2.0, -9f, 4.0, mute),
                inHigh = lane(0.0, 0f),
                outHp = lane(0.0, 150f, 2.0, 300f, 4.0, 400f, exponential = true),
                echoSend = lane(0.0, 0f),
            )
            MixTemplate.M6 -> lanes(
                outGain = lane(-4.0, 0f, 0.0, mute),
                inGain = lane(-choice.entry.pickupBeats.toDouble(), 0f, 0.0, 0f),
                outLow = lane(0.0, 0f),
                inLow = lane(0.0, 0f),
                outMid = lane(0.0, 0f),
                inMid = lane(0.0, 0f),
                outHigh = lane(0.0, 0f),
                inHigh = lane(0.0, 0f),
                outHp = lane(0.0, 20f),
                echoSend = lane(-1.0, 0f, 0.0, PhraseConfig.ECHO_SEND_PEAK, shape = "cos"),
            )
            MixTemplate.M7, MixTemplate.ENERGY_LIFT -> lanes(
                outGain = lane(-16.0, 0f, 0.0, 0f, 4.0, mute, shape = "ep"),
                inGain = lane(-8.0, mute, 0.0, 0f, shape = "ep"),
                outLow = lane(-2.0, 0f, 0.0, kill),
                inLow = lane(-8.0, kill, 0.0, 0f),
                outMid = lane(0.0, 0f, 4.0, mute),
                inMid = lane(-8.0, -8f, 0.0, 0f),
                outHigh = lane(0.0, 0f, 4.0, mute),
                inHigh = lane(0.0, 0f),
                outHp = lane(-16.0, 20f, 0.0, 400f, exponential = true),
                echoSend = lane(-1.0, 0f, 0.0, 0.2f),
            )
            MixTemplate.M9, MixTemplate.RELAXED -> lanes(
                outGain = lane(-o * 4.0, 0f, 0.0, 0f),
                inGain = lane(-min(o, 16) * 4.0, mute, -4.0, -12f, 0.0, 0f, shape = "ep"),
                outLow = lane(0.0, 0f),
                inLow = lane(-min(o, 16) * 4.0, kill, 0.0, 0f),
                outMid = lane(0.0, 0f),
                inMid = lane(0.0, 0f),
                outHigh = lane(0.0, 0f),
                inHigh = lane(0.0, 0f),
                outHp = lane(-o * 4.0, 20f, 0.0, 200f, exponential = true),
                echoSend = lane(0.0, 0f),
            )
            MixTemplate.ROLLING -> lanes(
                outGain = lane(-8.0 * 4, 0f, 0.0, mute, shape = "ep"),
                inGain = lane(-8.0 * 4, mute, 0.0, 0f, shape = "ep"),
                outLow = lane(-4.0, 0f, 0.0, kill),
                inLow = lane(-8.0 * 4, kill, 0.0, 0f),
                outMid = lane(0.0, 0f),
                inMid = lane(-8.0 * 4, -8f, 0.0, 0f),
                outHigh = lane(0.0, 0f),
                inHigh = lane(0.0, 0f),
                outHp = lane(0.0, 20f),
                echoSend = lane(-1.0, 0f, 0.0, PhraseConfig.ECHO_SEND_PEAK, shape = "cos"),
            )
            MixTemplate.VOCAL_OVER_OUTRO -> lanes(
                outGain = lane(-8.0, 0f, 0.0, 0f),
                inGain = lane(-8.0, -6f, 0.0, 0f),
                outLow = lane(0.0, 0f),
                inLow = lane(-8.0, kill, 0.0, 0f),
                outMid = lane(-8.0, -4f, 0.0, -4f),
                inMid = lane(0.0, 0f),
                outHigh = lane(0.0, 0f),
                inHigh = lane(0.0, 0f),
                outHp = lane(0.0, 20f),
                echoSend = lane(0.0, 0f),
            )
            else -> lanes(
                outGain = lane(-4.0, 0f, 0.0, mute, shape = "ep"),
                inGain = lane(-4.0, mute, 0.0, 0f, shape = "ep"),
                outLow = lane(0.0, 0f),
                inLow = lane(0.0, 0f),
                outMid = lane(0.0, 0f),
                inMid = lane(0.0, 0f),
                outHigh = lane(0.0, 0f),
                inHigh = lane(0.0, 0f),
                outHp = lane(0.0, 20f),
                echoSend = lane(0.0, 0f),
            )
        }
        val phase = if (o >= PhraseConfig.PHASE_GATE_LONG_BARS) PhraseConfig.PHASE_GATE_LONG_MS.toDouble()
        else PhraseConfig.PHASE_GATE_SHORT_MS.toDouble()
        return PhraseScript(
            choice = choice,
            plan = plan,
            outGain = outGain,
            inGain = inGain,
            outLow = outLow,
            inLow = inLow,
            outMid = outMid,
            inMid = inMid,
            outHigh = outHigh,
            inHigh = inHigh,
            outHp = outHp,
            inHp = lane(0.0, 20f),
            outLp = lane(0.0, 20000f),
            echoSend = echoSend,
            echoDelaySec = echoDelaySec,
            echoFeedback = echoFeedback,
            incomingHoldSec = holdSec,
            flanger = flanger && (choice.template == MixTemplate.M7 || choice.template == MixTemplate.M8),
            bTrimDb = 0f,
            phaseMs = phase,
        )
    }

    private data class Pack(
        val outGain: Lane, val inGain: Lane, val outLow: Lane, val inLow: Lane,
        val outMid: Lane, val inMid: Lane, val outHigh: Lane, val inHigh: Lane,
        val outHp: Lane, val echoSend: Lane,
    )

    private fun lanes(
        outGain: Lane, inGain: Lane, outLow: Lane, inLow: Lane,
        outMid: Lane, inMid: Lane, outHigh: Lane, inHigh: Lane,
        outHp: Lane, echoSend: Lane,
    ) = Pack(outGain, inGain, outLow, inLow, outMid, inMid, outHigh, inHigh, outHp, echoSend)

    private fun lane(vararg pairs: Number, shape: String = "lin", exponential: Boolean = false): Lane {
        val beats = DoubleArray(pairs.size / 2)
        val values = FloatArray(pairs.size / 2)
        var i = 0
        while (i + 1 < pairs.size) {
            beats[i / 2] = pairs[i].toDouble()
            values[i / 2] = pairs[i + 1].toFloat()
            i += 2
        }
        return Lane(beats, values, shape, exponential)
    }

    fun phraseLine(choice: PhraseChoice): String {
        val exit = choice.exit.sectionFn.name.lowercase()
        val entry = choice.entry.landingFn.name.lowercase()
        return "phrase · ${choice.template.name} ${choice.rung} ${choice.overlapBars}b · " +
            "A $exit @bar ${choice.exit.releaseBar} · B $entry @bar ${choice.entry.landingBar} · " +
            "swap @${choice.swapBar.toInt()} · ${choice.tempoPolicy} · ${choice.swapRampName} ${choice.swapRampMs} ms · " +
            "${choice.echoDelayName} ${choice.echoDelayBeats}"
    }

    /** Section text the expanded diagnostics already show, as "outro → intro". */
    fun sectionLine(choice: PhraseChoice): String =
        "${choice.exit.sectionFn.name.lowercase()} → ${choice.entry.landingFn.name.lowercase()}"

    fun scriptJson(
        script: PhraseScript,
        u: Int,
        g: Int,
        l: Int,
        bpmA: Double = 0.0,
        bpmB: Double = 0.0,
        camelot: Int? = null,
        lockedAt: Double? = null,
        slides: Int = 0,
    ): String {
        val c = script.choice
        val barB = if (bpmB > 1.0) 4.0 * 60.0 / bpmB else 0.0
        val landingB = c.entry.startSec + (c.entry.landingBar - c.entry.startBar) * barB
        return buildString {
            append("{\"template\":\"${c.template.name}\",\"rung\":\"${c.rung}\"")
            append(",\"score\":${fmt(c.score)}")
            append(",\"conf\":{\"struct\":${fmt(c.struct)},\"vocal\":${fmt(c.vocalScore)},\"phrase\":${fmt(c.phraseScore)}")
            append(",\"energy\":${fmt(c.energyScore)},\"length\":${fmt(c.lengthScore)},\"key\":${fmt(c.keyScore)},\"tempo\":${fmt(c.tempoScore)}}")
            append(",\"exit\":{\"type\":\"${c.exit.kind}\",\"section\":\"${c.exit.sectionFn}\",\"bar\":${c.exit.releaseBar}")
            append(",\"sec\":${fmt(c.exit.releaseSec)},\"energy\":${c.exit.energyLevel}}")
            append(",\"entry\":{\"type\":\"${c.entry.kind}\",\"section\":\"${c.entry.landingFn}\",\"bar\":${c.entry.landingBar}")
            append(",\"sec\":${fmt(landingB)},\"energy\":${c.entry.energyLevel},\"pickup\":${fmt(c.entry.pickupBeats.toDouble())}}")
            append(",\"L\":{\"aSec\":${fmt(c.exit.releaseSec)},\"bSec\":${fmt(landingB)}}")
            append(",\"P\":${c.overlapBars},\"T\":${fmt(c.tailBeats)}")
            append(",\"bpm\":{\"a\":${fmt(bpmA)},\"b\":${fmt(bpmB)}}")
            append(",\"aRampBars\":${c.tempoPrepBars},\"camelot\":${camelot ?: -1}")
            append(",\"tempoPolicy\":\"${c.tempoPolicy}\",\"prepBars\":${c.tempoPrepBars}")
            append(",\"swapRamp\":\"${c.swapRampName}\",\"swapRampMs\":${c.swapRampMs}")
            append(",\"echo\":\"${c.echoDelayName}\",\"echoBeats\":${fmt(c.echoDelayBeats)}")
            append(",\"vocalOverlapBeats\":${fmt(c.vocalOverlapBeats)},\"bTrimDb\":${fmt(script.bTrimDb.toDouble())}")
            append(",\"phaseMs\":${fmt(script.phaseMs)},\"lockedAt\":${fmt(lockedAt ?: -1.0)},\"slides\":$slides")
            append(",\"u\":$u,\"g\":$g,\"l\":$l,\"v\":${fmt(c.vocalProduct.toDouble())}}")
        }
    }

    private fun fmt(v: Double): String = ((v * 1000.0).toLong() / 1000.0).toString()
}
