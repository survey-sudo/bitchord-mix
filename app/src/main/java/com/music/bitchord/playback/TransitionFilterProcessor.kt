package com.music.bitchord.playback

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.playback.audio.AudioBlock
import com.music.bitchord.playback.audio.FloatAudioProcessor
import com.music.bitchord.playback.audio.PcmBoundary
import com.music.bitchord.playback.audio.PitchStretch
import com.music.bitchord.playback.smart.DjColour
import com.music.bitchord.playback.smart.DjEffect
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The filter a track rides through a Automix transition: a low-pass that can
 * close over the outgoing track, and a high-pass that can lift the low end out
 * of one side of a blend.
 *
 * ## The filter
 *
 * A topology-preserving (trapezoidal-integrator) state-variable filter, two
 * second-order sections cascaded to a 24 dB/octave Butterworth response.
 */
@UnstableApi
class TransitionFilterProcessor : BaseAudioProcessor() {

    @Volatile
    private var targetLowPassHz: Float = OPEN_HZ

    @Volatile
    private var targetHighPassHz: Float = OFF_HZ

    /** Vocal-presence peaking EQ target (dB, -9..0). Written off-thread. */
    @Volatile
    private var targetMidGainDb: Float = 0f

    private var channelCount = 0
    private var sampleRate = 0

    private var currentLowPassHz = OPEN_HZ
    private var currentHighPassHz = OFF_HZ
    private var currentMidGainDb = 0f

    /** Two integrator states per second-order section, per channel. */
    private var lowState = FloatArray(0)
    private var highState = FloatArray(0)

    /** Peaking-EQ history (x1, x2, y1, y2) per channel. Sized only in configure. */
    private var midState = FloatArray(0)
    private var midB0 = 1f
    private var midB1 = 0f
    private var midB2 = 0f
    private var midA1 = 0f
    private var midA2 = 0f

    private val lowA1 = FloatArray(STAGES)
    private val lowA2 = FloatArray(STAGES)
    private val lowA3 = FloatArray(STAGES)
    private val highA1 = FloatArray(STAGES)
    private val highA2 = FloatArray(STAGES)
    private val highA3 = FloatArray(STAGES)
    private val highK = FloatArray(STAGES)

    /** Whether the integrators hold anything since they were last cleared. */
    private var filterStateDirty = false

    /**
     * How far ahead of the speaker this filter is running, in media
     * microseconds; written by the sink, 0 when it has nothing to say.
     *
     * The filter runs where audio is *written*, not where it is heard, and on a
     * float route the output buffer behind it holds about four seconds — Media3
     * sizes it for 8x playback once the AudioTrack is doing the speed. A cutoff
     * set now is therefore heard seconds from now, while a fader set now is
     * heard at once. [CrossfadeController] reads this to aim each filter at the
     * point in the blend its audio will actually be played.
     */
    @Volatile
    var leadUs: Long = 0L

    /**
     * This sink's side of the Automix loop mix. Carried here, on the processor
     * that already trades roles with its player at every handoff, so the
     * controller reaches the right sink by the same route as the filters.
     */
    val loop = com.music.bitchord.playback.loopmix.LoopMixPort()

    // ---- Echo out ------------------------------------------------------------
    //
    // A feedback delay after the filters, for Advanced Automix's echo out: the
    // outgoing track's last beat keeps repeating, darker and thinner each
    // time, after its dry signal is gone. Everything here is idle — not one
    // sample touched — until [setEcho] engages it, and the ring is only ever
    // allocated at the size a transition actually asks for.

    /** Written by the controller; read once per block on the audio thread. */
    @Volatile private var echoEngaged = false
    @Volatile private var echoTargetSend = 0f
    @Volatile private var echoTargetDry = 1f
    @Volatile private var echoSeconds = 0f

    /** Bumped for every new echo; the audio thread clears its ring when it sees a new one. */
    @Volatile private var echoGeneration = 0

    /** A ring allocated off the audio thread, waiting to be adopted by it. */
    @Volatile private var pendingEchoRing: FloatArray? = null

    /** The format as last configured, readable from the controller's thread for sizing the ring. */
    @Volatile private var echoFormatRate = 0
    @Volatile private var echoFormatChannels = 0

    private var echoRing = FloatArray(0)
    private var echoSeenGeneration = 0
    private var echoDelayFrames = 0
    private var echoPosition = 0
    private var echoSend = 0f
    private var echoDry = 1f
    private var echoLow = FloatArray(0)
    private var echoHigh = FloatArray(0)
    private var echoLowCoefficient = 0f
    private var echoHighCoefficient = 0f

    /**
     * Aims the filter. [lowPassHz] at or above [OPEN_HZ] and [highPassHz] at or
     * below [OFF_HZ] mean "not filtering", which is the state this returns to
     * between transitions.
     */
    fun setCutoffs(lowPassHz: Float, highPassHz: Float) {
        targetLowPassHz = lowPassHz.coerceIn(MIN_HZ, OPEN_HZ)
        targetHighPassHz = highPassHz.coerceIn(OFF_HZ, MAX_HIGH_PASS_HZ)
    }

    /** Vocal-presence mid dip (-9..0 dB, 1500 Hz peaking, Q=0.707). Smoothed, bypassed near 0. */
    fun setMidGainDb(gainDb: Float) {
        targetMidGainDb = gainDb.coerceIn(-9f, 0f)
    }

    /** Parks both filters and clears the mid dip. Glided, not snapped. */
    fun open() {
        setCutoffs(OPEN_HZ, OFF_HZ)
        targetMidGainDb = 0f
    }

    val isFiltering: Boolean
        get() = targetLowPassHz < OPEN_HZ || targetHighPassHz > OFF_HZ ||
            currentLowPassHz < OPEN_HZ - SETTLED_HZ || currentHighPassHz > OFF_HZ + SETTLED_HZ ||
            targetMidGainDb < -0.05f || currentMidGainDb < -0.05f

    /**
     * Engages the echo out, or moves it: [delaySeconds] between repeats, [send]
     * how much of the signal is fed into the delay, [dry] how much of it is
     * passed through. Both glide across the next block rather than stepping.
     *
     * A new [delaySeconds] starts a new echo. The ring for it is allocated
     * here, on the caller's thread, and only when the one already held is too
     * small — so the audio thread never allocates, and a phone that never
     * hears an echo out never holds one.
     */
    fun setEcho(delaySeconds: Float, send: Float, dry: Float) {
        if (delaySeconds != echoSeconds || !echoEngaged) {
            val frames = (delaySeconds.coerceIn(0f, MAX_ECHO_SECONDS) * echoFormatRate).toInt()
            val needed = frames * echoFormatChannels
            if (needed > echoRing.size) pendingEchoRing = FloatArray(needed)
            echoSeconds = delaySeconds
            echoGeneration++
        }
        echoTargetSend = send.coerceIn(0f, 1f)
        echoTargetDry = dry.coerceIn(0f, 1f)
        echoEngaged = true
    }

    /**
     * Drops the echo and puts the dry signal back. For the end of a
     * transition, when the track it belonged to is gone or going: a tail
     * still ringing is cut, not faded.
     */
    fun parkEcho() {
        echoEngaged = false
        echoTargetSend = 0f
        echoTargetDry = 1f
    }

    /**
     * Configures the Float32 DSP engine for [sampleRate] and [channelCount].
     */
    fun configure(sampleRate: Int, channelCount: Int) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        val requiredSize = channelCount * STAGES * 2
        if (lowState.size != requiredSize) {
            lowState = FloatArray(requiredSize)
            highState = FloatArray(requiredSize)
        }
        if (midState.size != channelCount * 4) {
            midState = FloatArray(channelCount * 4)
        }
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
        currentMidGainDb = targetMidGainDb
        if (echoLow.size != channelCount) {
            echoLow = FloatArray(channelCount)
            echoHigh = FloatArray(channelCount)
        }
        echoLowCoefficient = onePole(ECHO_LOW_PASS_HZ, sampleRate)
        echoHighCoefficient = onePole(ECHO_HIGH_PASS_HZ, sampleRate)
        // A new format invalidates the ring's length in frames.
        echoFormatRate = sampleRate
        echoFormatChannels = channelCount
        echoSeenGeneration = echoGeneration - 1
        if (stretchHandle != 0L && channelCount in 1..2) {
            PitchStretch.configure(stretchHandle, channelCount, sampleRate)
            PitchStretch.setTempo(stretchHandle, beatmatchTempo)
        }
        if (channelCount in 1..2) colour.configure(sampleRate, channelCount)
    }

    private val colour = DjColour()

    /**
     * Beat-synced flanger, plate, loop roll, or vocal-mid scoop. [amount] and
     * [scoop] are already the wet gains; 0 leaves that path idle. No allocation.
     */
    fun setColour(effect: DjEffect, amount: Float, beatSeconds: Float, scoop: Float) {
        colour.set(effect, amount, beatSeconds, scoop)
    }

    fun parkColour() = colour.park()

    /**
     * Releases colour without hard-zeroing it: [DjColour.set] lets the plate
     * smoother ramp its wet gain to zero over ~150 ms. [parkColour] stays
     * for silent/flush teardown ([onReset]) where no tail is audible.
     */
    fun releaseColour() {
        colour.set(DjEffect.NONE, 0f, 0f, 0f)
    }

    /**
     * Processes interleaved Float32 audio samples in [block] in-place.
     * Preserves dynamic headroom without clamping to [-1.0f, +1.0f].
     */
    fun process(block: AudioBlock) {
        val frameCount = block.frameCount
        if (frameCount == 0 || channelCount < 1 || sampleRate <= 0) return
        filter(block, frameCount)
        if (echoEngaged) echo(block, frameCount)
        colour.process(block.samples, frameCount, channelCount)
    }

    /**
     * The beatmatch tempo AudioTrack is about to apply to this sink. 1 means
     * the blend is not stretching this side. The correction is the inverse, so
     * the tempo change keeps the original pitch.
     */
    @Suppress("UNUSED_PARAMETER")
    fun setBeatmatchTempo(tempo: Float) {
        // The stretcher is not engaged. A 1:1 transpose makes Signalsmith's
        // time factor the STFT hop over one sample, about a thousand times,
        // which is the runaway pitch and tempo. Playback speed is capped in
        // the controller and is the only tempo control.
        beatmatchTempo = 1f
    }

    private var stretchHandle = 0L
    @Volatile private var beatmatchTempo = 1f

    private fun filter(block: AudioBlock, frameCount: Int) {
        val targetLow = targetLowPassHz
        val targetHigh = targetHighPassHz
        val targetMid = targetMidGainDb
        val parkedLowHigh = targetLow >= OPEN_HZ && targetHigh <= OFF_HZ &&
            currentLowPassHz >= OPEN_HZ - SETTLED_HZ && currentHighPassHz <= OFF_HZ + SETTLED_HZ
        val parkedMid = targetMid > -0.05f && currentMidGainDb > -0.05f
        if (parkedLowHigh && parkedMid) {
            clearStateIfNeeded()
            return
        }
        filterStateDirty = true

        var remaining = frameCount
        var frameOffset = 0
        while (remaining > 0) {
            val subBlock = min(remaining, GLIDE_FRAMES)
            currentLowPassHz = glide(currentLowPassHz, targetLow)
            currentHighPassHz = glide(currentHighPassHz, targetHigh)
            currentMidGainDb = glideMid(currentMidGainDb, targetMid)
            val lowOn = currentLowPassHz < OPEN_HZ - SETTLED_HZ
            val highOn = currentHighPassHz > OFF_HZ + SETTLED_HZ
            val midOn = !(currentMidGainDb > -0.05f && targetMid > -0.05f)
            if (lowOn) updateLowCoefficients()
            if (highOn) updateHighCoefficients()
            if (midOn) updateMidCoefficients()

            for (f in 0 until subBlock) {
                val baseIdx = (frameOffset + f) * channelCount
                for (channel in 0 until channelCount) {
                    var sample = block.samples[baseIdx + channel]
                    if (lowOn) sample = lowPass(channel, sample)
                    if (highOn) sample = highPass(channel, sample)
                    if (midOn && midState.size == channelCount * 4) sample = midPeaking(channel, sample)
                    // Headroom preserved: no clamping to [-1.0f, +1.0f]
                    block.samples[baseIdx + channel] = sample
                }
            }
            frameOffset += subBlock
            remaining -= subBlock
        }
    }

    /**
     * 16-bit PCM only.
     */
    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount < 1) {
            Log.w(
                TAG,
                "Transition filtering inactive: encoding=${inputAudioFormat.encoding} " +
                    "channels=${inputAudioFormat.channelCount} is not 16-bit PCM",
            )
            return AudioProcessor.AudioFormat.NOT_SET
        }
        configure(inputAudioFormat.sampleRate, inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun onFlush() {
        lowState.fill(0f)
        highState.fill(0f)
        midState.fill(0f)
        // Re-arm the next ride at its intended target: onFlush runs before a
        // new ride/idle (including from configure/reset in the DSP chain),
        // so current must start from the target cutoff, not a stale glide.
        currentLowPassHz = targetLowPassHz
        currentHighPassHz = targetHighPassHz
        currentMidGainDb = targetMidGainDb
        filterStateDirty = false
        // Whatever the ring holds is from before the seek; start it clean.
        echoSeenGeneration = echoGeneration - 1
    }

    override fun onReset() {
        parkEcho()
        parkColour()
        echoSeenGeneration = echoGeneration - 1
        targetLowPassHz = OPEN_HZ
        targetHighPassHz = OFF_HZ
        targetMidGainDb = 0f
        currentMidGainDb = 0f
        lowState = FloatArray(0)
        highState = FloatArray(0)
        midState = FloatArray(0)
        channelCount = 0
        sampleRate = 0
    }

    override fun queueInput(inputBuffer: java.nio.ByteBuffer) {
        val bytesPerFrame = BYTES_PER_SAMPLE * channelCount
        if (bytesPerFrame == 0) return
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount == 0) return
        val outputBuffer = replaceOutputBuffer(frameCount * bytesPerFrame)

        val targetLow = targetLowPassHz
        val targetHigh = targetHighPassHz
        val targetMid = targetMidGainDb
        val parkedLowHigh = targetLow >= OPEN_HZ && targetHigh <= OFF_HZ &&
            currentLowPassHz >= OPEN_HZ - SETTLED_HZ && currentHighPassHz <= OFF_HZ + SETTLED_HZ
        val parkedMid = targetMid > -0.05f && currentMidGainDb > -0.05f
        if (parkedLowHigh && parkedMid) {
            clearStateIfNeeded()
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }
        filterStateDirty = true

        inputBuffer.order(ByteOrder.nativeOrder())
        outputBuffer.order(ByteOrder.nativeOrder())

        val invScale = 1.0f / 32768.0f
        var remaining = frameCount
        while (remaining > 0) {
            val subBlock = min(remaining, GLIDE_FRAMES)
            currentLowPassHz = glide(currentLowPassHz, targetLow)
            currentHighPassHz = glide(currentHighPassHz, targetHigh)
            currentMidGainDb = glideMid(currentMidGainDb, targetMid)
            val lowOn = currentLowPassHz < OPEN_HZ - SETTLED_HZ
            val highOn = currentHighPassHz > OFF_HZ + SETTLED_HZ
            val midOn = !(currentMidGainDb > -0.05f && targetMid > -0.05f)
            if (lowOn) updateLowCoefficients()
            if (highOn) updateHighCoefficients()
            if (midOn) updateMidCoefficients()

            repeat(subBlock) {
                for (channel in 0 until channelCount) {
                    var sample = inputBuffer.short.toFloat() * invScale
                    if (lowOn) sample = lowPass(channel, sample)
                    if (highOn) sample = highPass(channel, sample)
                    if (midOn && midState.size == channelCount * 4) sample = midPeaking(channel, sample)
                    outputBuffer.putShort(PcmBoundary.clamp16FromFloat(sample))
                }
            }
            remaining -= subBlock
        }
        outputBuffer.flip()
    }

    /**
     * Forgets the integrators once the filter parks.
     *
     * Parking skips the filter without running it, so whatever the integrators
     * held when the last transition ended would otherwise sit there until the
     * next one — minutes later — and be the first thing that transition's
     * filter output: a step of stale signal at the head of the blend, heard as
     * a click. Zeroed state is a filter at rest, which is what an unused one is.
     */
    private fun clearStateIfNeeded() {
        if (!filterStateDirty) return
        lowState.fill(0f)
        highState.fill(0f)
        midState.fill(0f)
        filterStateDirty = false
    }

    // ---- Echo ----------------------------------------------------------------

    /**
     * Runs the echo over [block] in place: the input goes out at [echoDry] and
     * into the ring at [echoSend], and what comes back round is added at
     * [ECHO_WET] and fed in again at [ECHO_FEEDBACK] through a one-pole
     * low-pass and high-pass. Those two are what make each repeat darker and
     * thinner than the last — a tape echo, not a copy machine — and keep the
     * low end from piling up in the loop.
     *
     * One read, one write and two one-pole filters per sample, with the send
     * and dry gains ramped linearly across the block so a 30ms control tick is
     * never heard as a step.
     */
    private fun echo(block: AudioBlock, frameCount: Int) {
        if (echoSeenGeneration != echoGeneration) startEcho()
        val channels = channelCount
        val delay = echoDelayFrames
        val ring = echoRing
        val samples = block.samples
        val targetSend = echoTargetSend
        val targetDry = echoTargetDry
        val sendStep = (targetSend - echoSend) / frameCount
        val dryStep = (targetDry - echoDry) / frameCount
        var send = echoSend
        var dry = echoDry
        if (delay <= 0) {
            // No ring to run: honour the dry gain alone, so the track still leaves.
            for (f in 0 until frameCount) {
                dry += dryStep
                val base = f * channels
                for (c in 0 until channels) samples[base + c] *= dry
            }
            echoSend = targetSend
            echoDry = targetDry
            return
        }
        val lowCoefficient = echoLowCoefficient
        val highCoefficient = echoHighCoefficient
        val low = echoLow
        val high = echoHigh
        var position = echoPosition
        for (f in 0 until frameCount) {
            send += sendStep
            dry += dryStep
            val base = f * channels
            val ringBase = position * channels
            for (c in 0 until channels) {
                val input = samples[base + c]
                val delayed = ring[ringBase + c]
                val darker = low[c] + lowCoefficient * (delayed - low[c])
                low[c] = darker
                val rumble = high[c] + highCoefficient * (darker - high[c])
                high[c] = rumble
                ring[ringBase + c] = input * send + (darker - rumble) * ECHO_FEEDBACK
                samples[base + c] = input * dry + delayed * ECHO_WET
            }
            if (++position == delay) position = 0
        }
        echoPosition = position
        echoSend = targetSend
        echoDry = targetDry
    }

    /** A new echo, on the audio thread: adopt any ring waiting for it and clear what it will use. */
    private fun startEcho() {
        echoSeenGeneration = echoGeneration
        pendingEchoRing?.let {
            echoRing = it
            pendingEchoRing = null
        }
        val channels = channelCount.coerceAtLeast(1)
        echoDelayFrames = (echoSeconds * sampleRate).toInt().coerceIn(0, echoRing.size / channels)
        echoRing.fill(0f, 0, echoDelayFrames * channels)
        echoLow.fill(0f)
        echoHigh.fill(0f)
        echoPosition = 0
        echoSend = 0f
        echoDry = 1f
    }

    // ---- Filter ------------------------------------------------------------

    private fun glide(current: Float, target: Float): Float {
        val from = ln(current.coerceAtLeast(MIN_HZ))
        val to = ln(target.coerceAtLeast(MIN_HZ))
        return exp(from + (to - from) * GLIDE_RATE)
    }

    private fun glideMid(current: Float, target: Float): Float {
        val diff = target - current
        if (abs(diff) < 1e-4f) return target
        return current + diff * GLIDE_RATE
    }

    /** RBJ peaking coefficients for the current mid gain (no allocation). */
    private fun updateMidCoefficients() {
        if (sampleRate <= 0) {
            midB0 = 1f; midB1 = 0f; midB2 = 0f; midA1 = 0f; midA2 = 0f
            return
        }
        val a = Math.pow(10.0, (currentMidGainDb / 40.0).toDouble()).toFloat()
        val w0 = 2.0 * Math.PI * (MID_CENTER_HZ / sampleRate)
        val cosW0 = cos(w0).toFloat()
        val sinW0 = sin(w0).toFloat()
        val alpha = sinW0 / (2f * MID_Q)
        val b0 = 1f + alpha * a
        val b1 = -2f * cosW0
        val b2 = 1f - alpha * a
        val a0 = 1f + alpha / a
        val a1 = -2f * cosW0
        val a2 = 1f - alpha / a
        midB0 = b0 / a0
        midB1 = b1 / a0
        midB2 = b2 / a0
        midA1 = a1 / a0
        midA2 = a2 / a0
    }

    /** Direct-Form-I peaking section; state is [midState] (x1,x2,y1,y2 per channel). */
    private fun midPeaking(channel: Int, input: Float): Float {
        val i = channel * 4
        val x1 = midState[i]
        val x2 = midState[i + 1]
        val y1 = midState[i + 2]
        val y2 = midState[i + 3]
        val y = midB0 * input + midB1 * x1 + midB2 * x2 - midA1 * y1 - midA2 * y2
        midState[i] = input
        midState[i + 1] = x1
        midState[i + 2] = y
        midState[i + 3] = y1
        return y
    }

    private fun usableCutoff(hz: Float): Float =
        hz.coerceIn(MIN_HZ, sampleRate * MAX_CUTOFF_FRACTION)

    private fun updateLowCoefficients() {
        if (sampleRate <= 0) return
        val g = tan(Math.PI * usableCutoff(currentLowPassHz) / sampleRate).toFloat()
        for (stage in 0 until STAGES) {
            val k = 1f / BUTTERWORTH_Q[stage]
            val a1 = 1f / (1f + g * (g + k))
            lowA1[stage] = a1
            lowA2[stage] = g * a1
            lowA3[stage] = g * (g * a1)
        }
    }

    private fun updateHighCoefficients() {
        if (sampleRate <= 0) return
        val g = tan(Math.PI * usableCutoff(currentHighPassHz) / sampleRate).toFloat()
        for (stage in 0 until STAGES) {
            val k = 1f / BUTTERWORTH_Q[stage]
            val a1 = 1f / (1f + g * (g + k))
            highA1[stage] = a1
            highA2[stage] = g * a1
            highA3[stage] = g * (g * a1)
            highK[stage] = k
        }
    }

    private fun lowPass(channel: Int, input: Float): Float {
        var value = input
        for (stage in 0 until STAGES) {
            val i = (channel * STAGES + stage) * 2
            val ic1 = lowState[i]
            val ic2 = lowState[i + 1]
            val v3 = value - ic2
            val v1 = lowA1[stage] * ic1 + lowA2[stage] * v3
            val v2 = ic2 + lowA2[stage] * ic1 + lowA3[stage] * v3
            lowState[i] = 2f * v1 - ic1
            lowState[i + 1] = 2f * v2 - ic2
            value = v2
        }
        return value
    }

    private fun highPass(channel: Int, input: Float): Float {
        var value = input
        for (stage in 0 until STAGES) {
            val i = (channel * STAGES + stage) * 2
            val ic1 = highState[i]
            val ic2 = highState[i + 1]
            val v3 = value - ic2
            val v1 = highA1[stage] * ic1 + highA2[stage] * v3
            val v2 = ic2 + highA2[stage] * ic1 + highA3[stage] * v3
            highState[i] = 2f * v1 - ic1
            highState[i + 1] = 2f * v2 - ic2
            value -= highK[stage] * v1 + v2
        }
        return value
    }

    companion object {
        private const val TAG = "BitChordTransitionFilter"

        /** A low-pass at or above this is doing nothing audible, so it counts as off. */
        const val OPEN_HZ = 20_000f

        /** A high-pass at or below this is doing nothing audible, so it counts as off. */
        const val OFF_HZ = 20f

        /** Nothing musical wants the low end lifted above this, and a typo shouldn't be able to. */
        const val MAX_HIGH_PASS_HZ = 2_000f

        private const val MIN_HZ = 10f
        private const val BYTES_PER_SAMPLE = 2

        /** Two cascaded second-order sections: 24 dB/octave, the usual DJ-filter slope. */
        private const val STAGES = 2

        /** Section Qs for a maximally flat (Butterworth) fourth-order response. */
        private val BUTTERWORTH_Q = floatArrayOf(0.54120f, 1.30656f)

        /** Frames between coefficient updates. ~1.5 ms at 44.1 kHz. */
        private const val GLIDE_FRAMES = 64

        /** Per-sub-block glide fraction. ~30 ms time constant, just under one fade tick. */
        private const val GLIDE_RATE = 0.05f

        /** How close to a parked value counts as parked, so a glide terminates. */
        private const val SETTLED_HZ = 1f

        /** Vocal-presence peaking center and Q (RBJ, no allocation). */
        private const val MID_CENTER_HZ = 1500f
        private const val MID_Q = 0.707f

        /** Keeps `tan` away from its pole at Nyquist. */
        private const val MAX_CUTOFF_FRACTION = 0.45f

        /**
         * Echo feedback: each repeat at 40% of the one before, so
         * [com.music.bitchord.playback.smart.ECHO_REPEATS] of them take it to
         * about -40 dB — the tail the planner reserves room for. Kept short on
         * purpose: a long run of repeats over the incoming song was heard as
         * the outgoing one glitching.
         */
        private const val ECHO_FEEDBACK = 0.4f

        /** How loud the repeats come back against the dry signal they echo. */
        private const val ECHO_WET = 0.5f

        /** Corners of the loop's one-pole filters: each repeat loses air above and rumble below. */
        private const val ECHO_LOW_PASS_HZ = 3_500f
        private const val ECHO_HIGH_PASS_HZ = 250f

        /** Mirrors [com.music.bitchord.playback.smart.MAX_ECHO_SECONDS]; bounds the ring. */
        private const val MAX_ECHO_SECONDS = 1f

        /** The coefficient of a one-pole smoother with its corner at [hz]. */
        private fun onePole(hz: Float, sampleRate: Int): Float =
            if (sampleRate <= 0) 0f else (1.0 - exp(-2.0 * Math.PI * hz / sampleRate)).toFloat()
    }
}

/**
 * The two filters a transition rides: one over the track arriving, one over the
 * track leaving.
 *
 * An interface rather than the processors themselves so [CrossfadeController]
 * stays testable without an audio sink, and so it never has to know that
 * "incoming" and "outgoing" are two different ExoPlayers whose roles swap at the
 * lap.
 */
interface TransitionFilters {
    /** The track fading up — the session player, once the lap has handed the queue over. */
    fun incoming(lowPassHz: Float, highPassHz: Float)

    /** The track fading out — the ghost player. */
    fun outgoing(lowPassHz: Float, highPassHz: Float)

    /**
     * How far ahead of the speaker each side's filter is running, in that
     * track's media milliseconds — see [TransitionFilterProcessor.leadUs].
     * Zero, the default, means "applied as it is heard".
     */
    fun incomingLeadMs(): Long = 0L
    fun outgoingLeadMs(): Long = 0L

    /** Echo out on each side's sink — see [TransitionFilterProcessor.setEcho]. */
    fun incomingEcho(delaySeconds: Float, send: Float, dry: Float) = Unit
    fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) = Unit

    /** Drops any echo on either sink. Called whenever a transition ends, however it ended. */
    fun parkEchoes() = Unit

    /**
     * Releases colour smoothly on both sinks (see
     * [TransitionFilterProcessor.releaseColour]). Called before [parkEchoes]
     * at a teardown so an audible reverb tail decays instead of hard-cutting;
     * [parkEcho] stays hard-parked in [parkEchoes].
     */
    fun releaseColour() = Unit

    /**
     * Beatmatch tempo about to be applied as a playback rate on this side.
     * The sink pitch-corrects by the inverse. 1 clears it.
     */
    fun incomingTempo(rate: Float) = Unit
    fun outgoingTempo(rate: Float) = Unit

    /** Flanger, plate, loop roll, or a vocal-mid scoop on this side's sink. */
    fun incomingColour(effect: com.music.bitchord.playback.smart.DjEffect, amount: Float, beatSeconds: Float, scoop: Float) = Unit
    fun outgoingColour(effect: com.music.bitchord.playback.smart.DjEffect, amount: Float, beatSeconds: Float, scoop: Float) = Unit

    /** Vocal-presence mid dip (-9..0 dB). No-op by default. */
    fun incomingMidDb(gainDb: Float) = Unit
    fun outgoingMidDb(gainDb: Float) = Unit

    /** Parks both. Called whenever a transition ends, however it ended. */
    fun open() {
        incoming(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        incomingMidDb(0f)
        outgoingMidDb(0f)
    }

    /** For callers with no audio sink to filter — tests, and the default wiring. */
    object None : TransitionFilters {
        override fun incoming(lowPassHz: Float, highPassHz: Float) = Unit
        override fun outgoing(lowPassHz: Float, highPassHz: Float) = Unit
    }
}
