package com.music.bitchord.playback.loopmix

import android.util.Log
import com.music.bitchord.playback.smart.StemKind
import com.music.bitchord.playback.smart.StemSet
import kotlin.math.pow

/**
 * The native loop-mix renderer (native/loopmix/): two Signalsmith Stretch
 * decks, each a decoded region of one song, looped on whole bars and following
 * the mix's tempo ramp beat by beat, mixed to one stereo stream.
 *
 * Built and filled on a worker thread; after [prepare] only [render] (the
 * playback thread, inside the outgoing sink) and the small setters touch it.
 */
class LoopMixRenderer internal constructor(val sampleRate: Int, private val readout: Readout?) : AutoCloseable {
    constructor(sampleRate: Int) : this(sampleRate, null)

    /** Native measurements and optional rendering substituted by JVM tests. */
    internal interface Readout {
        fun firstFrame(): Long
        fun heard(index: Int): Double
        fun exitGateViolations(): Int
        fun render(frame: Long, interleaved: FloatArray, offset: Int, frames: Int) {
            error("This readout does not substitute rendering")
        }
    }


    @Volatile
    private var handle: Long = if (readout == null && available) nativeCreate(sampleRate) else 0L

    val isValid: Boolean get() = handle != 0L

    /** One deck: the region (planar, at [sampleRate]), its loop, and its beat knots. */
    class DeckSetup(
        val left: FloatArray,
        val right: FloatArray,
        /** Track seconds of sample 0. */
        val regionStart: Double,
        /** Loop bounds in region samples. */
        val loopStart: Long,
        val loopEnd: Long,
        /** Unwrapped region-sample position at which the loop lets go; huge for never. */
        val releaseAt: Double,
        /** Mix frames (0 = t0) and the unwrapped region-sample position heard at each. */
        val knotFrames: DoubleArray,
        val knotPositions: DoubleArray,
        /** After the last knot: carry on at rate 1 (true) or stop. */
        val runOn: Boolean,
        /** Mix seconds after which the deck renders nothing. */
        val stopAt: Double,
        val preroll: Double,
        val level: Float,
    )

    // AUTOMIX-V2(C): the region each deck holds, so setDeckStems can refuse a misaligned StemSet.
    private val deckFrames = IntArray(2) { -1 }
    private val deckRegionStart = DoubleArray(2) { Double.NaN }

    fun setDeck(index: Int, deck: DeckSetup) {
        if (index in 0..1) {
            deckFrames[index] = minOf(deck.left.size, deck.right.size)
            deckRegionStart[index] = deck.regionStart
        }
        nativeSetDeck(
            handle, index, deck.left, deck.right, deck.regionStart, deck.loopStart, deck.loopEnd, deck.releaseAt,
            deck.knotFrames, deck.knotPositions, deck.runOn, deck.stopAt, deck.preroll, deck.level,
        )
    }

    /**
     * Automation over mix seconds: [KIND_GAIN] (linear), [KIND_LOW_DB] (250 Hz shelf),
     * [KIND_HIGH_PASS_HZ], [KIND_LOW_PASS_HZ] (TPT low-pass).
     */
    fun setEnvelope(index: Int, kind: Int, times: DoubleArray, values: FloatArray, exponential: Boolean = false) =
        nativeSetEnvelope(handle, index, kind, times, values, exponential)

    /** Signalsmith's default preset when [high], the cheaper one otherwise. Call before [prepare]. */
    fun setHighQuality(high: Boolean) = nativeSetHighQuality(handle, high)

    /** Tempo-synced ping-pong on [index]. A non-positive delay turns it off. The tail is not faded with the dry signal. */
        fun setEcho(index: Int, delaySeconds: Double, feedback: Float, wet: Float) =
        nativeSetEcho(handle, index, delaySeconds, feedback, wet)

    /** Beat-synced flanger on [index]'s exit tail (wet comb rides the deck's gain, so it dissolves with it). */
    fun setFlanger(index: Int, lfoHz: Double, depthSeconds: Double, feedback: Float, wet: Float) =
        nativeSetFlanger(handle, index, lfoHz, depthSeconds, feedback, wet)

    /** How many times a deck went silent while still audible at its stopAt (djmix11 exit gate). */
    fun exitGateViolations(): Int = readout?.exitGateViolations() ?: nativeExitGateViolations(handle)

    /** LR4 isolator on a phrase mix. Off for the djmix6 envelopes. */
    fun setIsolate(index: Int, on: Boolean) = nativeSetIsolate(handle, index, on)

    fun prepare() = nativePrepare(handle)

    /** The earliest mix frame anything renders at; negative, before t0. */
    fun firstFrame(): Long = readout?.firstFrame() ?: nativeFirstFrame(handle)

    /** Renders [frames] stereo frames from mix frame [frame] into [interleaved] at frame [offset]. */
    fun render(frame: Long, interleaved: FloatArray, offset: Int, frames: Int) {
        if (readout != null) readout.render(frame, interleaved, offset, frames)
        else nativeRender(handle, frame, interleaved, offset, frames)
    }

    /** Fade [index] out over 25 ms once it plays track second [trackSeconds] after its release. */
    fun setFadeOut(index: Int, trackSeconds: Double) = nativeSetFadeOut(handle, index, trackSeconds)

    /** Track seconds [index] played at the end of the last rendered block. */
    fun heard(index: Int): Double = readout?.heard(index) ?: nativeHeard(handle, index)

    override fun close() {
        val h = handle
        handle = 0L
        if (h != 0L) nativeDestroy(h)
    }

    /** djmix24 beat-quantized loop roll (native tightening). */
    fun setLoopRoll(index: Int, enabled: Boolean, rollStartSec: Double, beatSec: Double, releaseSec: Double, totalRollBeats: Double) =
        nativeSetLoopRoll(handle, index, enabled, rollStartSec, beatSec, releaseSec, totalRollBeats)

    /** djmix24 stereo plate reverb wash (pre-allocated in prepare). */
    fun setReverb(index: Int, roomSize: Float, damping: Float, wet: Float) =
        nativeSetReverb(handle, index, roomSize, damping, wet)

    /** djmix24 Cytomic SVF Q (0.707 default, ~0.95 musical buildup). */
    fun setSvfQ(index: Int, q: Float) = nativeSetSvfQ(handle, index, q)

    // ---- AUTOMIX-V2(C) ---------------------------------------------------------------------

    /**
     * Per-deck stems. Call after [setDeck], before [prepare]. The deck then renders
     * `sum_k gain_k(t) * stem_k` (gains from [KIND_STEM_VOCALS]..[KIND_STEM_OTHER], linear,
     * default 1) through its normal chain: one stretcher, stems summed before it with the
     * gains evaluated at the moment each input sample is heard (latency-compensated).
     * OTHER is never uploaded: native derives it as dry - (V + D + B), so all-unity gains
     * are bit-identical to the dry deck. A 2-stem set (VOCALS + OTHER) works the same.
     *
     * Null, [AutomixV2Render.stems] off, or a set that does not match the deck's region
     * (rate, frame count, start within 1.5 samples) leaves the deck dry. Returns whether
     * stems were attached.
     */
    fun setDeckStems(index: Int, stems: StemSet?): Boolean {
        if (handle == 0L || index !in 0..1) return false
        nativeClearDeckStems(handle, index)
        if (stems == null || !AutomixV2Render.stems) return false
        val frames = deckFrames[index]
        if (frames <= 0 || stems.sampleRate != sampleRate || stems.frames != frames) return false
        val start = deckRegionStart[index]
        if (!start.isFinite() || kotlin.math.abs(stems.regionStartSec - start) > 1.5 / sampleRate) return false
        var attached = 0
        for ((kind, slot) in STEM_SLOTS) {
            val l = stems.left[kind] ?: continue
            val r = stems.right[kind] ?: continue
            if (l.size != frames || r.size != frames) {
                nativeClearDeckStems(handle, index)
                return false
            }
            if (nativeSetDeckStem(handle, index, slot, l, r)) attached++
        }
        if (attached == 0) nativeClearDeckStems(handle, index)
        return attached > 0
    }

    /** Phaser on [index]: LFO cycle [rateBeats] (1/16..64 beats, DJM PHASER), sweep [depth] 0..1, [feedback] -0.85..0.85. Wet = [KIND_PHASER_WET]. */
    fun setPhaser(index: Int, rateBeats: Double, depth: Float, feedback: Float) {
        if (handle != 0L && AutomixV2Render.fx) nativeSetPhaser(handle, index, rateBeats, depth, feedback)
    }

    /**
     * Beat gate (DJM TRANS / trance gate) on [index]: [division] gate steps per beat
     * (1/16..16; 4 = sixteenth notes, 50 % duty, 2 ms / 8 ms edges), [swing] 0..0.33.
     * A negative [division] selects stutter: the first step after the depth opens is
     * captured and repeated every step. Depth = [KIND_GATE_DEPTH]. [beatSec] is only the
     * fallback when the knots are not a beat grid.
     */
    fun setGate(index: Int, beatSec: Double, division: Float, swing: Float = 0f) {
        if (handle != 0L && AutomixV2Render.fx) nativeSetGate(handle, index, beatSec, division, swing)
    }

    /**
     * EDM noise riser on [index]: decorrelated white noise through a rising resonant band
     * (HPF 350 Hz -> 9 kHz) over [lengthBeats], with an accelerating tremolo. Starts where
     * [KIND_RISER_LEVEL] first rises above 0; level 1 is about -20 dBFS RMS. Not under the
     * deck's gain; it fades in the last 20 ms before stopAt.
     */
    fun setRiser(index: Int, beatSec: Double, lengthBeats: Double) {
        if (handle != 0L && AutomixV2Render.fx) nativeSetRiser(handle, index, beatSec, lengthBeats)
    }

    /**
     * Beat-locked sidechain-style pump on [index]: ducks the deck's low band (< 120 Hz) on
     * every beat (4 ms attack, recovered by 0.6 beat) so the other deck's kick never fights
     * this bass; [fullBand] ducks the whole deck (EDM pump, -10.5 dB at depth 1). Depth = [KIND_PUMP_DEPTH].
     */
    fun setPump(index: Int, beatSec: Double, fullBand: Boolean = false) {
        if (handle != 0L && AutomixV2Render.fx) nativeSetPump(handle, index, beatSec, fullBand)
    }

    /**
     * Vinyl brake (DJM VINYL BRAKE) or [spinback] on [index] from mix second [startSec] over
     * [lengthSec] (brake <= 8 s, spinback <= 3 s). A varispeed read of the deck's own output:
     * pitch falls with speed and the level follows stylus velocity to exactly 0. One-shot.
     * [KIND_BRAKE] (0..1 progress) overrides the linear timing when set. A completed brake
     * does not count as an exit-gate violation.
     */
    fun setBrake(index: Int, startSec: Double, lengthSec: Double, spinback: Boolean = false) {
        if (handle != 0L && AutomixV2Render.fx) nativeSetBrake(handle, index, startSec, lengthSec, spinback)
    }

    /**
     * K-weighted (BS.1770-4) loudness tracking: during overlaps each deck is nudged
     * (half-way, max 1.5 dB) toward the line between the two songs' loudness, and the summed
     * bus is held on that line ("no dip / no bump", -4..+3 dB, 8 dB/s, deliberate dips over
     * 8 LU ignored). [targetLufs] is the static normalisation reference (e.g. -14): a bump may
     * not pass max(A, B, target) + 1 LU; 0 or NaN = no cap. Turns on the true-peak limiter.
     */
    fun setLoudnessMatch(enabled: Boolean, targetLufs: Float) {
        if (handle != 0L) nativeSetLoudnessMatch(handle, enabled && AutomixV2Render.loudness, targetLufs)
    }

    /** 4x oversampled true-peak detection in the -1 dBTP limiter. Off = vc80 sample-peak limiter. */
    fun setTruePeakLimiter(enabled: Boolean) {
        if (handle != 0L) nativeSetTruePeak(handle, enabled)
    }

    /** EBU R128 short-term (3 s) loudness: [OUT], [IN] (programme, pre-fader) or [BUS] (output). -120 when unknown or off. */
    fun shortTermLufs(which: Int): Double = if (handle != 0L) nativeShortTermLufs(handle, which) else -120.0

    /** The bus loudness guard's gain right now, dB. */
    fun loudnessGuardDb(): Float = if (handle != 0L) nativeLoudnessGuardDb(handle) else 0f

    companion object {
        const val OUT = 0
        const val IN = 1
        /** [shortTermLufs] index of the output bus. */
        const val BUS = 2
        const val KIND_GAIN = 0
        const val KIND_LOW_DB = 1
        const val KIND_HIGH_PASS_HZ = 2
        const val KIND_LOW_PASS_HZ = 3
        /** 0..1 into the echo. Kind 4. Shut until the envelope says otherwise. */
        const val KIND_ECHO_SEND = 4
        /** djmix24 LR4 isolator mid gain (dB). Used for vocal handoff carve. */
        const val KIND_MID_DB = 5
        /** djmix24 LR4 isolator high gain (dB). */
        const val KIND_HIGH_DB = 6
        /** djmix24 flanger wet ramp (0..1 multiplier) + tail ring. */
        const val KIND_FLANGER_WET = 7
        /** djmix24 reverb wash send (0..1). */
        const val KIND_REVERB_SEND = 8

        // AUTOMIX-V2(C) envelope kinds (native v2::Kind). Linear 0..1 unless noted.
        /** Stem gains, linear, default 1 (values up to 4 accepted). Need [setDeckStems]. */
        const val KIND_STEM_VOCALS = 9
        const val KIND_STEM_DRUMS = 10
        const val KIND_STEM_BASS = 11
        const val KIND_STEM_OTHER = 12
        /** Q multiplier (0.25..8) on the high-/low-pass (and knob) filters: resonant sweeps. */
        const val KIND_FILTER_RES = 13
        /** Phaser wet 0..1 (needs [setPhaser]). */
        const val KIND_PHASER_WET = 14
        /** Gate / stutter depth 0..1 (needs [setGate]). */
        const val KIND_GATE_DEPTH = 15
        /** Riser level 0..2 (needs [setRiser]). */
        const val KIND_RISER_LEVEL = 16
        /** Pump depth 0..1 (needs [setPump]). */
        const val KIND_PUMP_DEPTH = 17
        /** Brake progress 0..1 (needs [setBrake]). */
        const val KIND_BRAKE = 18
        /** DJM single filter knob -1 (LPF, muffle) .. 0 (flat) .. +1 (HPF); combines with the HP/LP envelopes. */
        const val KIND_FILTER_KNOB = 19

        // Capability bits (native v2::Capability).
        const val CAP_STEMS = 1 shl 0
        const val CAP_FILTER_RES = 1 shl 1
        const val CAP_FILTER_KNOB = 1 shl 2
        const val CAP_PHASER = 1 shl 3
        const val CAP_GATE = 1 shl 4
        const val CAP_STUTTER = 1 shl 5
        const val CAP_RISER = 1 shl 6
        const val CAP_PUMP = 1 shl 7
        const val CAP_BRAKE = 1 shl 8
        const val CAP_SPINBACK = 1 shl 9
        const val CAP_LOUDNESS = 1 shl 10
        const val CAP_TRUE_PEAK = 1 shl 11

        /**
         * What this build's renderer implements (compile-time, mirrors native
         * v2::kCapabilities). Gate moves with [v2Capabilities], which also honours the
         * runtime flags and a library that failed to load.
         */
        const val V2_CAPABILITIES: Int = (1 shl 12) - 1

        /** [V2_CAPABILITIES] as the loaded native library reports it, masked by [AutomixV2Render]; 0 if unavailable. */
        fun v2Capabilities(): Int {
            if (!available) return 0
            val native = try {
                nativeV2Capabilities()
            } catch (e: UnsatisfiedLinkError) {
                Log.w("BitChordLoopMix", "v2 renderer entry points missing", e)
                0
            }
            var caps = native and V2_CAPABILITIES
            if (!AutomixV2Render.stems) caps = caps and CAP_STEMS.inv()
            if (!AutomixV2Render.fx) {
                caps = caps and (CAP_PHASER or CAP_GATE or CAP_STUTTER or CAP_RISER or CAP_PUMP or CAP_BRAKE or CAP_SPINBACK).inv()
            }
            if (!AutomixV2Render.loudness) caps = caps and CAP_LOUDNESS.inv()
            return caps
        }

        private val STEM_SLOTS: List<Pair<StemKind, Int>> = listOf(
            StemKind.VOCALS to 0,
            StemKind.DRUMS to 1,
            StemKind.BASS to 2,
        )

        val available: Boolean = runCatching { System.loadLibrary("bitchord_loopmix") }
            .onFailure { Log.w("BitChordLoopMix", "Loop mix renderer unavailable", it) }
            .isSuccess

        @JvmStatic private external fun nativeV2Capabilities(): Int
        @JvmStatic private external fun nativeSetDeckStem(handle: Long, index: Int, kind: Int, left: FloatArray, right: FloatArray): Boolean
        @JvmStatic private external fun nativeClearDeckStems(handle: Long, index: Int)
        @JvmStatic private external fun nativeSetPhaser(handle: Long, index: Int, rateBeats: Double, depth: Float, feedback: Float)
        @JvmStatic private external fun nativeSetGate(handle: Long, index: Int, beatSec: Double, division: Float, swing: Float)
        @JvmStatic private external fun nativeSetRiser(handle: Long, index: Int, beatSec: Double, lengthBeats: Double)
        @JvmStatic private external fun nativeSetPump(handle: Long, index: Int, beatSec: Double, fullBand: Boolean)
        @JvmStatic private external fun nativeSetBrake(handle: Long, index: Int, startSec: Double, lengthSec: Double, spinback: Boolean)
        @JvmStatic private external fun nativeSetLoudnessMatch(handle: Long, enabled: Boolean, targetLufs: Float)
        @JvmStatic private external fun nativeSetTruePeak(handle: Long, on: Boolean)
        @JvmStatic private external fun nativeShortTermLufs(handle: Long, which: Int): Double
        @JvmStatic private external fun nativeLoudnessGuardDb(handle: Long): Float
        @JvmStatic private external fun nativeCreate(sampleRate: Int): Long
        @JvmStatic private external fun nativeDestroy(handle: Long)
        @JvmStatic private external fun nativeSetDeck(
            handle: Long, index: Int, left: FloatArray, right: FloatArray, regionStart: Double,
            loopStart: Long, loopEnd: Long, releaseAt: Double, knotFrames: DoubleArray, knotPositions: DoubleArray,
            runOn: Boolean, stopAt: Double, preroll: Double, level: Float,
        )
        @JvmStatic private external fun nativeSetEnvelope(
            handle: Long, index: Int, kind: Int, times: DoubleArray, values: FloatArray, exponential: Boolean,
        )
        @JvmStatic private external fun nativeSetHighQuality(handle: Long, high: Boolean)
        @JvmStatic private external fun nativeSetEcho(handle: Long, index: Int, delaySeconds: Double, feedback: Float, wet: Float)
        @JvmStatic private external fun nativeSetIsolate(handle: Long, index: Int, on: Boolean)
        @JvmStatic private external fun nativePrepare(handle: Long)
        @JvmStatic private external fun nativeFirstFrame(handle: Long): Long
        @JvmStatic private external fun nativeRender(handle: Long, frame: Long, interleaved: FloatArray, offset: Int, frames: Int)
        @JvmStatic private external fun nativeSetFadeOut(handle: Long, index: Int, trackSeconds: Double)
        @JvmStatic private external fun nativeHeard(handle: Long, index: Int): Double
        @JvmStatic private external fun nativeSetFlanger(handle: Long, index: Int, lfoHz: Double, depthSeconds: Double, feedback: Float, wet: Float)
        @JvmStatic private external fun nativeSetLoopRoll(handle: Long, index: Int, enabled: Boolean, rollStartSec: Double, beatSec: Double, releaseSec: Double, totalRollBeats: Double)
        @JvmStatic private external fun nativeSetReverb(handle: Long, index: Int, roomSize: Float, damping: Float, wet: Float)
        @JvmStatic private external fun nativeSetSvfQ(handle: Long, index: Int, q: Float)
        @JvmStatic private external fun nativeExitGateViolations(handle: Long): Int
    }
}

/** The loop-mix renderer, under the name the rest of Automix uses for every rung on this clock. */
typealias AutomixRenderer = LoopMixRenderer

/**
 * AUTOMIX-V2(C) runtime switches for the renderer's v2 features. Every v2
 * feature is also inert until its setter or envelope is used, so with these on
 * and nothing called, the renderer is the vc80 renderer bit for bit. Turning a
 * switch off makes the matching wrappers no-ops (dry deck, no FX, no guard).
 */
object AutomixV2Render {
    @Volatile var stems: Boolean = true
    @Volatile var fx: Boolean = true
    @Volatile var loudness: Boolean = true
}

/**
 * AUTOMIX-V2(C) envelope builders for [LoopMixRenderer.setEnvelope]. Pure
 * functions; times are mix seconds, strictly increasing.
 *
 * Pick the crossfade law by how correlated the two decks are: [equalPower]
 * keeps the summed power flat for uncorrelated material (e.g. a harmonic or
 * vocal blend); for beatmatched, phase-aligned material amplitudes add, so use
 * [constantSum], usually with [bassSwap] (low band swaps fast, mids and highs
 * ride the slower fader).
 */
object LoopMixCurves {
    class Curve(val times: DoubleArray, val values: FloatArray)

    /** OUT (cos) and IN (sin) [LoopMixRenderer.KIND_GAIN] curves over [startSec, endSec]. */
    fun equalPower(startSec: Double, endSec: Double, points: Int = 17): Pair<Curve, Curve> {
        val n = points.coerceIn(2, 129)
        val end = if (endSec > startSec) endSec else startSec + 1e-3
        val t = DoubleArray(n) { startSec + (end - startSec) * it / (n - 1) }
        val out = FloatArray(n) { kotlin.math.cos(kotlin.math.PI / 2 * it / (n - 1)).toFloat() }
        val inn = FloatArray(n) { kotlin.math.sin(kotlin.math.PI / 2 * it / (n - 1)).toFloat() }
        out[n - 1] = 0f
        inn[n - 1] = 1f
        return Curve(t, out) to Curve(t.copyOf(), inn)
    }

    /** OUT (1 - u) and IN (u) [LoopMixRenderer.KIND_GAIN] curves: constant amplitude sum for beatmatched material. */
    fun constantSum(startSec: Double, endSec: Double): Pair<Curve, Curve> {
        val end = if (endSec > startSec) endSec else startSec + 1e-3
        val t = doubleArrayOf(startSec, end)
        return Curve(t, floatArrayOf(1f, 0f)) to Curve(t.copyOf(), floatArrayOf(0f, 1f))
    }

    /**
     * Bass swap on [LoopMixRenderer.KIND_LOW_DB]: OUT's low shelf falls to [killDb] while
     * IN's rises from it, over [swapSec, swapSec + lengthSec] (a quarter to one beat is a
     * DJ's swap). -26 dB is the DJM EQ floor.
     */
    fun bassSwap(swapSec: Double, lengthSec: Double, killDb: Float = -26f): Pair<Curve, Curve> {
        val len = lengthSec.coerceAtLeast(0.005)
        val kill = killDb.coerceIn(-40f, 0f)
        val t = doubleArrayOf(swapSec, swapSec + len / 2, swapSec + len)
        // Equal-power-ish in dB: OUT is already 6 dB down at the midpoint, IN is 6 dB up from the floor.
        val out = floatArrayOf(0f, -6f, kill)
        val inn = floatArrayOf(kill, -6f, 0f)
        if (kill > -6f) { out[1] = kill / 2; inn[1] = kill / 2 }
        return Curve(t, out) to Curve(t.copyOf(), inn)
    }

    /**
     * Kotlin mirror of native `v2::djmKnob`: [knob] -1..+1 -> (hpHz, lpHz, qBoost).
     * hpHz <= 20 and lpHz >= 18000 mean the filter is bypassed.
     */
    fun djmKnob(knob: Float): FloatArray {
        val k = if (knob.isNaN()) 0f else knob.coerceIn(-1f, 1f)
        val a = kotlin.math.abs(k)
        val dead = 0.04f
        if (a <= dead) return floatArrayOf(0f, 20000f, 1f)
        val c = ((a - dead) / (1f - dead)).toDouble().pow(1.4).toFloat()
        val q = 1f + 0.6f * c
        return if (k < 0f) {
            floatArrayOf(0f, (20000.0 * (150.0 / 20000.0).pow(c.toDouble())).toFloat(), q)
        } else {
            floatArrayOf((20.0 * (10000.0 / 20.0).pow(c.toDouble())).toFloat(), 20000f, q)
        }
    }

    /**
     * [LoopMixRenderer.KIND_FILTER_KNOB] muffle: the knob eases (sin^2) from 0 to -[depth]
     * over [startSec, endSec], so the low-pass closes gently and then dives. [demuffle]
     * reverses it (-depth back to 0). A positive [depth] muffles, a negative one thins
     * (high-pass). 0.7 lands the low-pass near 1.1 kHz; 0.9 near 300 Hz.
     */
    fun muffle(startSec: Double, endSec: Double, depth: Float = 0.7f, points: Int = 9, demuffle: Boolean = false): Curve {
        val n = points.coerceIn(2, 65)
        val end = if (endSec > startSec) endSec else startSec + 1e-3
        val d = depth.coerceIn(-1f, 1f)
        val t = DoubleArray(n) { startSec + (end - startSec) * it / (n - 1) }
        val v = FloatArray(n) {
            val u = it.toDouble() / (n - 1)
            val e = kotlin.math.sin(kotlin.math.PI / 2 * u).let { s -> s * s }
            val x = if (demuffle) 1.0 - e else e
            (-d * x).toFloat()
        }
        return Curve(t, v)
    }

    /** A 0 -> [peak] -> 0 send/wet envelope (phaser, gate, pump, riser): ramps of [rampSec] at both ends, held between. */
    fun window(startSec: Double, endSec: Double, peak: Float = 1f, rampSec: Double = 0.05): Curve {
        val end = if (endSec > startSec) endSec else startSec + 1e-3
        val ramp = minOf(rampSec.coerceAtLeast(1e-4), (end - startSec) * 0.45)
        return Curve(
            doubleArrayOf(startSec, startSec + ramp, end - ramp, end),
            floatArrayOf(0f, peak, peak, 0f),
        )
    }
}
