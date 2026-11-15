package com.music.bitchord.playback.smart

import kotlin.math.PI
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
    private var effect = DjEffect.NONE
    private var amount = 0f
    private var scoop = 0f
    private var beatFrames = 0

    private var flange = FloatArray(0)
    private var flangePos = 0
    private var flangePhase = 0.0

    private var combs = Array(4) { FloatArray(0) }
    private var combPos = IntArray(4)
    private var allpass = Array(2) { FloatArray(0) }
    private var allpassPos = IntArray(2)
    private var revLow = 0f

    private var echo = FloatArray(0)
    private var echoPos = 0

    private var roll = FloatArray(0)
    private var rollPos = 0
    private var rollLen = 0
    private var rollAnchor = 0
    private var rollCursor = 0
    private var rollSince = 0
    private var rolling = false

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
        val rollFrames = sampleRate * 2
        roll = FloatArray(rollFrames * channels)
        rollPos = 0
        echo = FloatArray(sampleRate * channels)
        echoPos = 0
    }

    /** [scoopAmount] is the vocal-handoff mid cut, 0..1, independent of [effect]. */
    fun set(effect: DjEffect, amount: Float, beatSeconds: Float, scoopAmount: Float) {
        this.effect = effect
        this.amount = amount.coerceIn(0f, 1f)
        this.scoop = scoopAmount.coerceIn(0f, 1f)
        if (sampleRate > 0) bakeScoop()
        val frames = if (beatSeconds > 0f && sampleRate > 0) (beatSeconds * sampleRate).toInt() else 0
        if (frames > 0) beatFrames = frames
        if (effect != DjEffect.LOOP_ROLL || amount <= 0f) {
            rolling = false
        } else if (!rolling && beatFrames > 0) {
            rolling = true
            rollLen = beatFrames.coerceAtMost(roll.size / channels.coerceAtLeast(1))
            rollCursor = 0
            rollSince = 0
            val frames = roll.size / channels.coerceAtLeast(1)
            if (frames > 0) {
                rollAnchor = ((rollPos - rollLen) % frames + frames) % frames
            } else {
                rollAnchor = 0
            }
        }
    }

    fun park() {
        effect = DjEffect.NONE
        amount = 0f
        scoop = 0f
        rolling = false
        flange.fill(0f)
        for (comb in combs) comb.fill(0f)
        for (pass in allpass) pass.fill(0f)
        roll.fill(0f)
        echo.fill(0f)
        echoPos = 0
        revLow = 0f
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
        if (effect == DjEffect.NONE && scoop <= 0.001f) return
        val settings = effectSettings(effect)
        for (frame in 0 until frames) {
            val base = frame * channels
            if (scoop > 0.001f) {
                for (c in 0 until channels) samples[base + c] = scoop(samples[base + c], c)
            }
            when (effect) {
                DjEffect.FLANGER -> if (amount > 0.001f) flange(samples, base, channels, settings)
                DjEffect.REVERB -> if (amount > 0.001f) reverb(samples, base, channels, settings)
                DjEffect.LOOP_ROLL -> roll(samples, base, channels)
                DjEffect.ECHO -> if (amount > 0.001f) echo(samples, base, channels, settings)
                else -> Unit
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
        val mix = settings.wet * amount
        for (c in 0 until channels) samples[base + c] = samples[base + c] * (1f - mix) + wet * mix
    }

    private fun roll(samples: FloatArray, base: Int, channels: Int) {
        if (roll.isEmpty() || beatFrames <= 0) return
        val frames = roll.size / channels
        for (c in 0 until channels) roll[rollPos * channels + c] = samples[base + c]
        if (rolling && rollLen > 1 && amount > 0.001f && frames > 0) {
            val index = (rollAnchor + rollCursor) % frames
            val fadeFrames = (sampleRate * 0.005f).toInt().coerceAtLeast(1)
            val fade = if (rollCursor < fadeFrames) rollCursor.toFloat() / fadeFrames else 1f
            val wet = amount.coerceIn(0f, 1f) * fade
            for (c in 0 until channels) {
                val looped = roll[index * channels + c]
                val dry = samples[base + c]
                samples[base + c] = dry * (1f - wet) + looped * wet
            }
            rollCursor++
            rollSince++
            if (rollCursor >= rollLen) {
                rollCursor = 0
                val floor = (beatFrames / 2).coerceAtLeast(1)
                if (rollSince >= beatFrames && rollLen > floor) {
                    rollLen = (rollLen / 2).coerceAtLeast(floor)
                    rollSince = 0
                }
            }
        }
        rollPos++
        if (rollPos >= frames) rollPos = 0
    }
}
