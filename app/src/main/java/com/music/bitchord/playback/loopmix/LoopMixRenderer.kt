package com.music.bitchord.playback.loopmix

import android.util.Log

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

    /** Only native measurements are substituted by controller JVM tests. */
    internal interface Readout {
        fun firstFrame(): Long
        fun heard(index: Int): Double
        fun exitGateViolations(): Int
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

    fun setDeck(index: Int, deck: DeckSetup) = nativeSetDeck(
        handle, index, deck.left, deck.right, deck.regionStart, deck.loopStart, deck.loopEnd, deck.releaseAt,
        deck.knotFrames, deck.knotPositions, deck.runOn, deck.stopAt, deck.preroll, deck.level,
    )

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
    fun render(frame: Long, interleaved: FloatArray, offset: Int, frames: Int) =
        nativeRender(handle, frame, interleaved, offset, frames)

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

    companion object {
        const val OUT = 0
        const val IN = 1
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

        val available: Boolean = runCatching { System.loadLibrary("bitchord_loopmix") }
            .onFailure { Log.w("BitChordLoopMix", "Loop mix renderer unavailable", it) }
            .isSuccess

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
