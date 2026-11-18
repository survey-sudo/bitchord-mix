package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Beat-synced colour on one sink: flanger, a small plate, a loop roll, and
 * a mid scoop for the vocal handoff.
 *
 * [configure] allocates. [process] does not. Delays are fixed once the
 * sample rate is known. The plate is an original Schroeder comb/allpass,
 * not a copy of a published reverb library.
 */
class DjColour {
    private var sampleRate = 0
    private var channels = 0
    private val noSettings = effectSettings(DjEffect.NONE)
    private val rollSettings = effectSettings(DjEffect.LOOP_ROLL)
    private val flangeSettings = effectSettings(DjEffect.FLANGER)
    private val plateSettings = effectSettings(DjEffect.REVERB)
    private val echoSettings = effectSettings(DjEffect.ECHO)
    @Volatile private var effect = DjEffect.NONE
    @Volatile private var settings = noSettings
    private val reverbDecaySettings = plateSettings.copy(wet = 0f)
    @Volatile private var amount = 0f
    @Volatile private var scoop = 0f
    @Volatile private var requestedBeatFrames = 0
    @Volatile private var beatFrames = 0

    private var flange = FloatArray(0)
    private var flangePos = 0
    private var flangePhase = 0.0

    private var combs = Array(4) { FloatArray(0) }
    private var combPos = IntArray(4)
    private var allpass = Array(2) { FloatArray(0) }
    private var allpassPos = IntArray(2)
    private var revLow = 0f
    private var reverbWet = 0f

    private var echo = FloatArray(0)
    private var echoPos = 0

    private var roll = FloatArray(0)
    /** Separate from the live history: a writer wrap can never overwrite a frozen body. */
    private var rollBody = FloatArray(0)
    private var rollPos = 0
    private var rollLen = 0
    private var rollBodyLen = 0
    private var rollBodyBeatFrames = 0
    @Volatile private var rollRecorded = 0
    @Volatile private var rollFramesWritten = 0L
    @Volatile private var rollBeatOrigin = 0L
    private var rollBodyStart = 0L
    private var rollCursor = 0
    private var rollSince = 0
    private var rollWet = 0f
    @Volatile private var rolling = false

    val activeEffect: DjEffect get() = effect
    internal val rollBodyFrames: Int get() = rollBodyLen
    internal val rollLoopFrames: Int get() = rollLen
    internal val rollBodyStartFrame: Long get() = rollBodyStart

    companion object {
        const val MIN_ROLL_BPM = 70.0
        const val MAX_ROLL_BPM = 150.0

        /** Unsupported timing is NONE, never a truncated roll advertised as four beats. */
        fun supportsRoll(beatSeconds: Float): Boolean {
            if (!beatSeconds.isFinite() || beatSeconds <= 0f) return false
            val bpm = 60.0 / beatSeconds
            return bpm >= MIN_ROLL_BPM - 1e-4 && bpm <= MAX_ROLL_BPM + 1e-4
        }
    }

    private var scoopB0 = 1f
    private var scoopB1 = 0f
    private var scoopB2 = 0f
    private var scoopA1 = 0f
    private var scoopA2 = 0f
    private var scoopX1 = FloatArray(2)
    private var scoopX2 = FloatArray(2)
    private var scoopY1 = FloatArray(2)
    private var scoopY2 = FloatArray(2)

    fun configure(sampleRate: Int, channels: Int) {
        if (sampleRate < 8000 || channels !in 1..2) return
        if (this.sampleRate == sampleRate && this.channels == channels && flange.isNotEmpty()) return
        this.sampleRate = sampleRate
        this.channels = channels
        val flangeFrames = (sampleRate * 0.020f).toInt().coerceAtLeast(8)
        flange = FloatArray(flangeFrames * channels)
        flangePos = 0
        val scale = sampleRate / 44100f
        val combLengths = intArrayOf(1116, 1188, 1277, 1356).map { (it * scale).toInt().coerceAtLeast(32) }
        combs = Array(4) { FloatArray(combLengths[it]) }
        combPos = IntArray(4)
        val allpassLengths = intArrayOf(225, 556).map { (it * scale).toInt().coerceAtLeast(16) }
        allpass = Array(2) { FloatArray(allpassLengths[it]) }
        allpassPos = IntArray(2)
        // Eight beats at the slowest supported tempo leave room for a complete
        // four-beat body plus alignment history. No allocation in set/process.
        val rollFrames = ceil(sampleRate * 8.0 * 60.0 / MIN_ROLL_BPM).toInt() + 8
        roll = FloatArray(rollFrames * channels)
        rollBody = FloatArray((ceil(sampleRate * 4.0 * 60.0 / MIN_ROLL_BPM).toInt() + 4) * channels)
        rollPos = 0
        rollRecorded = 0
        rollFramesWritten = 0L
        rollBeatOrigin = 0L
        rollBodyStart = 0L
        rollBodyLen = 0
        rollLen = 0
        rollCursor = 0
        rollSince = 0
        rollWet = 0f
        rolling = false
        beatFrames = 0
        requestedBeatFrames = 0
        echo = FloatArray(sampleRate * channels)
        echoPos = 0
    }

    /** [scoopAmount] is the vocal-handoff mid cut, 0..1, independent of [effect]. */
    fun set(effect: DjEffect, amount: Float, beatSeconds: Float, scoopAmount: Float): DjEffect {
        var chosen = if (effect == DjEffect.LOOP_ROLL && !supportsRoll(beatSeconds)) DjEffect.NONE else effect
        this.amount = if (amount.isFinite()) amount.coerceIn(0f, 1f) else 0f
        this.scoop = if (scoopAmount.isFinite()) scoopAmount.coerceIn(0f, 1f) else 0f
        if (sampleRate > 0) bakeScoop()
        val frames = if (beatSeconds.isFinite() && beatSeconds > 0f && sampleRate > 0) {
            (beatSeconds.toDouble() * sampleRate).takeIf { it <= Int.MAX_VALUE }?.roundToInt() ?: 0
        } else 0
        if (frames > 0) requestedBeatFrames = frames
        val phase = if (frames > 0 && frames == beatFrames) ((rollFramesWritten - rollBeatOrigin) % frames).toInt() else 0
        if (chosen == DjEffect.LOOP_ROLL && this.amount > 0f &&
            (frames <= 0 || channels <= 0 || 4L * frames > rollBody.size / channels.coerceAtLeast(1) ||
                (!rolling && 4L * frames + phase > rollRecorded))
        ) {
            chosen = DjEffect.NONE
            this.amount = 0f
        }
        settings = when (chosen) {
            DjEffect.NONE -> noSettings
            DjEffect.LOOP_ROLL -> rollSettings
            DjEffect.FLANGER -> flangeSettings
            DjEffect.REVERB -> plateSettings
            DjEffect.ECHO -> echoSettings
        }
        this.effect = chosen
        return chosen
    }

    private fun freezeRoll(): Boolean {
        if (beatFrames <= 0 || channels <= 0 || roll.isEmpty()) return false
        val length = 4 * beatFrames
        val ringFrames = roll.size / channels
        val phase = ((rollFramesWritten - rollBeatOrigin) % beatFrames).toInt()
        if (length > rollBody.size / channels || length + phase > rollRecorded) return false
        val anchor = ((rollPos - phase - length) % ringFrames + ringFrames) % ringFrames
        val first = minOf(length, ringFrames - anchor)
        roll.copyInto(rollBody, 0, anchor * channels, (anchor + first) * channels)
        if (first < length) roll.copyInto(rollBody, first * channels, 0, (length - first) * channels)
        rollBodyStart = rollFramesWritten - phase - length
        rollBodyLen = length
        rollBodyBeatFrames = beatFrames
        rollLen = length
        // Preserve phase when the control tick arrives between beats; the frozen
        // body starts/ends on beats, not at an arbitrary 250 ms control boundary.
        rollCursor = phase
        rollSince = 0
        rollWet = 0f
        rolling = true
        return true
    }

    fun park() {
        effect = DjEffect.NONE
        settings = noSettings
        amount = 0f
        scoop = 0f
        rolling = false
        flange.fill(0f)
        for (comb in combs) comb.fill(0f)
        for (pass in allpass) pass.fill(0f)
        roll.fill(0f)
        rollBody.fill(0f)
        rollPos = 0
        rollRecorded = 0
        rollFramesWritten = 0L
        rollBeatOrigin = 0L
        rollBodyStart = 0L
        rollBodyLen = 0
        rollLen = 0
        rollCursor = 0
        rollSince = 0
        rollWet = 0f
        beatFrames = 0
        requestedBeatFrames = 0
        echo.fill(0f)
        echoPos = 0
        revLow = 0f
        reverbWet = 0f
        scoopX1.fill(0f)
        scoopX2.fill(0f)
        scoopY1.fill(0f)
        scoopY2.fill(0f)
    }

    private fun bakeScoop() {
        val f = 1200.0 / sampleRate
        val q = 0.7
        val gain = 1.0 - 0.75 * scoop
        val w = 2.0 * PI * f
        val alpha = sin(w) / (2.0 * q)
        val cos = kotlin.math.cos(w)
        val a0 = 1.0 + alpha
        scoopB0 = ((1.0 + alpha * gain) / a0).toFloat()
        scoopB1 = ((-2.0 * cos) / a0).toFloat()
        scoopB2 = ((1.0 - alpha * gain) / a0).toFloat()
        scoopA1 = ((-2.0 * cos) / a0).toFloat()
        scoopA2 = ((1.0 - alpha) / a0).toFloat()
    }

    fun process(samples: FloatArray, frames: Int, channels: Int) {
        if (frames <= 0 || this.channels != channels || sampleRate <= 0) return
        val requested = requestedBeatFrames
        if (requested > 0 && requested != beatFrames) {
            beatFrames = requested
            rollBeatOrigin = rollFramesWritten
        }
        // Capture on the audio thread at one frame boundary, never racing the
        // history writer from set(). The two copyInto calls allocate nothing.
        if (effect == DjEffect.LOOP_ROLL && amount > 0f && !rolling && !freezeRoll()) {
            effect = DjEffect.NONE
            amount = 0f
            settings = noSettings
        }
        if (effect == DjEffect.NONE && scoop <= 0.001f && reverbWet <= 1e-4f && !rolling) {
            for (frame in 0 until frames) recordRoll(samples, frame * channels, channels)
            return
        }
        for (frame in 0 until frames) {
            val base = frame * channels
            recordRoll(samples, base, channels)
            if (rolling) roll(samples, base, channels)
            if (scoop > 0.001f) {
                for (c in 0 until channels) samples[base + c] = scoop(samples[base + c], c)
            }
            when (effect) {
                DjEffect.FLANGER -> if (amount > 0.001f) flange(samples, base, channels, settings)
                DjEffect.REVERB -> if (amount > 0.001f || reverbWet > 1e-4f) reverb(samples, base, channels, settings)
                DjEffect.LOOP_ROLL -> Unit
                DjEffect.ECHO -> if (amount > 0.001f) echo(samples, base, channels, settings)
                else -> if (reverbWet > 1e-4f) reverb(samples, base, channels, reverbDecaySettings)
            }
        }
    }

    private fun scoop(input: Float, channel: Int): Float {
        val y = scoopB0 * input + scoopB1 * scoopX1[channel] + scoopB2 * scoopX2[channel] -
            scoopA1 * scoopY1[channel] - scoopA2 * scoopY2[channel]
        scoopX2[channel] = scoopX1[channel]
        scoopX1[channel] = input
        scoopY2[channel] = scoopY1[channel]
        scoopY1[channel] = y
        return y
    }

    private var flangeDelay = 1.0
    private var flangeWet = 0f

    private fun flange(samples: FloatArray, base: Int, channels: Int, settings: EffectSettings) {
        val frames = flange.size / channels
        if (frames < 4) return
        val beats = settings.beats.coerceAtLeast(1f)
        val period = beats * beatFrames.coerceAtLeast(sampleRate / 4)
        flangePhase += 2.0 * PI / period
        if (flangePhase > 2.0 * PI) flangePhase -= 2.0 * PI
        val target = sampleRate * (0.001 + 0.005 * (0.5 + 0.5 * sin(flangePhase)))
        val coeff = 1.0 - kotlin.math.exp(-1.0 / (0.005 * sampleRate))
        flangeDelay += (target - flangeDelay) * coeff
        val delay = flangeDelay.coerceIn(1.0, frames - 2.0)
        val i0 = delay.toInt()
        val frac = (delay - i0).toFloat()
        val wetTarget = settings.wet * amount
        val ramp = (beatFrames.coerceAtLeast(1) * 8).coerceAtLeast(1)
        flangeWet += (wetTarget - flangeWet) / ramp
        for (c in 0 until channels) {
            val tap0 = flangePos - i0
            val tap1 = tap0 - 1
            val index0 = if (tap0 >= 0) tap0 else tap0 + frames
            val index1 = if (tap1 >= 0) tap1 else tap1 + frames
            val delayed = flange[index0 * channels + c] * (1f - frac) + flange[index1 * channels + c] * frac
            val dry = samples[base + c]
            flange[flangePos * channels + c] = dry + delayed * settings.feedback
            samples[base + c] = dry * (1f - flangeWet) + delayed * flangeWet
        }
        flangePos++
        if (flangePos >= frames) flangePos = 0
    }

    /** A one-beat throw. The dry signal stays; this does not muffle it. */
    private fun echo(samples: FloatArray, base: Int, channels: Int, settings: EffectSettings) {
        if (echo.isEmpty() || beatFrames <= 0) return
        val frames = echo.size / channels
        val delay = beatFrames.coerceIn(1, frames - 1)
        val wet = settings.wet * amount
        for (c in 0 until channels) {
            val tap = echoPos - delay
            val index = if (tap >= 0) tap else tap + frames
            val delayed = echo[index * channels + c]
            val dry = samples[base + c]
            echo[echoPos * channels + c] = dry + delayed * settings.feedback
            samples[base + c] = dry + delayed * wet
        }
        echoPos++
        if (echoPos >= frames) echoPos = 0
    }

    private fun reverb(samples: FloatArray, base: Int, channels: Int, settings: EffectSettings) {
        var mono = 0f
        for (c in 0 until channels) mono += samples[base + c]
        mono /= channels
        val coeff = 1f - kotlin.math.exp(-2.0 * PI * 300.0 / sampleRate).toFloat()
        revLow += (mono - revLow) * coeff
        val send = mono - revLow
        var wet = 0f
        for (i in combs.indices) {
            val buf = combs[i]
            val at = combPos[i]
            val y = buf[at]
            buf[at] = send + y * settings.feedback
            combPos[i] = if (at + 1 == buf.size) 0 else at + 1
            wet += y
        }
        wet *= 0.25f
        for (i in allpass.indices) {
            val buf = allpass[i]
            val at = allpassPos[i]
            val y = buf[at]
            val out = -wet + y
            buf[at] = wet + y * 0.5f
            allpassPos[i] = if (at + 1 == buf.size) 0 else at + 1
            wet = out
        }
        val ramp = (sampleRate * 0.15f).toInt().coerceAtLeast(1)
        val wetTarget = settings.wet * amount
        reverbWet += (wetTarget - reverbWet) / ramp
        val mix = reverbWet
        for (c in 0 until channels) samples[base + c] = samples[base + c] * (1f - mix) + wet * mix
    }

    private fun recordRoll(samples: FloatArray, base: Int, channels: Int) {
        if (roll.isEmpty()) return
        val frames = roll.size / channels
        for (c in 0 until channels) roll[rollPos * channels + c] = samples[base + c]
        rollPos++
        if (rollPos >= frames) rollPos = 0
        if (rollRecorded < frames) rollRecorded++
        rollFramesWritten++
    }

    private fun roll(samples: FloatArray, base: Int, channels: Int) {
        if (rollLen <= 1 || rollBodyLen <= 1) return
        val target = if (effect == DjEffect.LOOP_ROLL) amount * 0.7f else 0f
        val rampFrames = (sampleRate * 0.010f).toInt().coerceAtLeast(1)
        val step = 0.7f / rampFrames
        rollWet = when {
            rollWet < target -> minOf(target, rollWet + step)
            else -> maxOf(target, rollWet - step)
        }
        val seamFrames = minOf((sampleRate * 0.005f).toInt().coerceAtLeast(1), rollLen / 2)
        val into = if (rollCursor < seamFrames) {
            (0.5 * (1.0 - kotlin.math.cos(PI * rollCursor / seamFrames))).toFloat()
        } else 1f
        for (c in 0 until channels) {
            val head = rollBody[rollCursor * channels + c]
            val looped = if (rollCursor < seamFrames) {
                val tail = rollBody[(rollLen - seamFrames + rollCursor) * channels + c]
                tail * (1f - into) + head * into
            } else head
            samples[base + c] = samples[base + c] * (1f - rollWet) + looped * rollWet
        }
        rollCursor++
        rollSince++
        if (rollCursor >= rollLen) {
            rollCursor = 0
            val floor = (rollBodyBeatFrames / 2).coerceAtLeast(1)
            if (rollSince >= rollBodyBeatFrames && rollLen > floor) {
                rollLen = (rollLen / 2).coerceAtLeast(floor)
                rollSince = 0
            }
        }
        if (target <= 0f && rollWet <= 1e-4f) rolling = false
    }
}
