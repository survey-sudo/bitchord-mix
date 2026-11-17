package com.music.bitchord.playback.loopmix

import androidx.media3.common.C
import java.util.concurrent.atomic.AtomicLong

/**
 * One player's side of the loop mix, inside its audio sink
 * ([com.music.bitchord.playback.audio.PrecisionAudioSink]).
 *
 * **Outgoing** ([armOutgoing]): at the decoded frame where the song reaches its
 * loop start, the sink crosses from the song's own audio to the [LoopMixRenderer]
 * over 25 ms — sample-exact, because both are counted in the same frames — and
 * from then on writes the renderer's mix and stops consuming the song's input.
 * The player keeps playing (its sink always has data), but its song is
 * finished: the mix plays on its AudioTrack until the incoming player takes
 * over and the controller retires this one.
 *
 * **Incoming** ([armIncoming]): the song is silent up to [InArm.handoverUs] and
 * fades in over 25 ms there, while the renderer's incoming deck fades out at
 * the same track position. Before that, frames may be dropped or silence
 * inserted ([requestShift]) to put this player exactly in step with the deck;
 * the sink keeps its own clock continuous through that and reports the true
 * track position back ([positionShiftUs]).
 *
 * Written by the controller's thread ([armOutgoing], [armIncoming],
 * [requestShift], [disarm]) and the playback thread (everything else).
 */
class LoopMixPort {

    class OutArm(
        val renderer: LoopMixRenderer,
        /** Track microseconds of the loop start: mix frame 0. */
        val takeoverUs: Long,
        val sampleRate: Int,
        /** The first mix frame the renderer needs to see (its decks' pre-roll). */
        val firstFrame: Long,
    )

    class InArm(
        /** Track microseconds at which this player becomes audible. */
        val handoverUs: Long,
    )

    @Volatile var outArm: OutArm? = null
        private set

    @Volatile var inArm: InArm? = null
        private set

    /** Bumped for every arm and disarm, so the sink drops state that belonged to an older one. */
    @Volatile var generation: Int = 0
        private set

    fun armOutgoing(arm: OutArm) {
        inArm = null
        outArm = arm
        generation++
        invalidatePosition()
    }

    fun armIncoming(arm: InArm) {
        outArm = null
        inArm = arm
        pendingShift.set(0)
        generation++
        invalidatePosition()
    }

    fun disarm() {
        outArm = null
        inArm = null
        pendingShift.set(0)
        generation++
        invalidatePosition()
    }

    // ---- What the sink publishes ---------------------------------------------------------

    /** The sink's format, as last configured: a loop mix needs linear PCM, stereo. */
    @Volatile var sampleRate: Int = 0
    @Volatile var channelCount: Int = 0
    @Volatile var precision: Boolean = false

    val supportsLoopMix: Boolean get() = precision && channelCount == 2 && sampleRate > 0

    /** The renderer timebase's offset: track µs = presentation µs - this. */
    @Volatile var streamOffsetUs: Long = 0L

    /** True from the block where the outgoing sink switched to the renderer. */
    @Volatile var tookOver: Boolean = false

    /** Set by a flush or a reset: whatever was armed is gone (a seek, a stop). */
    @Volatile var interrupted: Boolean = false

    /**
     * The last position reading, in track µs, and when it was taken
     * ([System.nanoTime]); the player's own clock, at sub-millisecond
     * resolution, read on every renderer tick.
     */
    private val positionEpoch = AtomicLong()
    val clockEpoch: Long get() = positionEpoch.get()
    @Volatile private var positionSequence = 0L
    @Volatile private var observedGeneration = -1
    @Volatile private var observedEpoch = -1L
    @Volatile private var observedUs = C.TIME_UNSET
    @Volatile private var observedNanos = 0L

    val positionUs: Long get() = if (observedGeneration == generation && observedEpoch == clockEpoch) observedUs else C.TIME_UNSET
    val positionNanos: Long get() = if (observedGeneration == generation && observedEpoch == clockEpoch) observedNanos else 0L

    /** Also called on flush, including startup flushes that leave the arm intact. */
    fun invalidatePosition() {
        positionEpoch.incrementAndGet()
    }

    /** Single playback-thread writer; no lock or allocation on the audio path. */
    fun publishPosition(positionUs: Long, nowNanos: Long, armGeneration: Int, epoch: Long) {
        if (armGeneration != generation || epoch != clockEpoch) return
        positionSequence++
        observedUs = positionUs
        observedNanos = nowNanos
        observedGeneration = armGeneration
        observedEpoch = epoch
        positionSequence++
    }

    /** Control-thread snapshot: contention is transient, invalid/stale clocks are unavailable. */
    internal data class PositionReading(val seconds: Double?, val contended: Boolean = false)

    internal fun readPresentedPosition(nowNanos: Long, playing: Boolean): PositionReading {
        repeat(3) {
            val armGeneration = generation
            val epoch = clockEpoch
            val sequence = positionSequence
            if ((sequence and 1L) != 0L) return@repeat
            val position = observedUs
            val nanos = observedNanos
            val observationGeneration = observedGeneration
            val observationEpoch = observedEpoch
            if (sequence != positionSequence) return@repeat
            if (armGeneration != generation || epoch != clockEpoch ||
                observationGeneration != armGeneration || observationEpoch != epoch || position == C.TIME_UNSET
            ) return POSITION_UNAVAILABLE
            val age = nowNanos - nanos
            // Publication can follow the common reference instant: project it
            // backwards, bounded just as strictly as an older observation.
            if (age < -POSITION_FRESHNESS_NANOS || age > POSITION_FRESHNESS_NANOS) return POSITION_UNAVAILABLE
            val elapsed = if (playing) age / 1e9 else 0.0
            return PositionReading(position / 1e6 + elapsed)
        }
        return POSITION_CONTENDED
    }

    /** Where the presented audio is now, in track seconds, extrapolated from the last reading. */
    fun presentedSeconds(nowNanos: Long, playing: Boolean): Double? = readPresentedPosition(nowNanos, playing).seconds

    // ---- The incoming side's alignment ------------------------------------------------------

    /** Frames to drop (positive) or of silence to insert (negative), applied before the handover. */
    val pendingShift = AtomicLong()

    /** The track position the sink has processed up to, µs: a shift lands there, not where it's heard. */
    @Volatile var processedUs: Long = C.TIME_UNSET

    fun requestShift(frames: Long) {
        pendingShift.addAndGet(frames)
    }

    // ---- The splice, checked ---------------------------------------------------------------

    /**
     * The song's own audio and the renderer's, mono, over the [SPLICE_CAPTURE]
     * frames before mix frame 0 — the same stretch of the same song from two
     * decoders. [spliceLag] says how far apart they really are, which is the
     * one thing that can make the takeover audible.
     */
    val spliceSong = FloatArray(SPLICE_CAPTURE)
    val spliceDeck = FloatArray(SPLICE_CAPTURE)
    @Volatile var spliceCaptured = false

    fun resetSplice() {
        spliceCaptured = false
        spliceSong.fill(0f)
        spliceDeck.fill(0f)
    }

    /**
     * The lag (frames, positive: the deck late) at which the deck's pre-roll
     * best matches the song it takes over from, and the correlation there.
     * Null before the capture, or for silence.
     */
    fun spliceLag(maxLag: Int = 960): Pair<Int, Double>? {
        if (!spliceCaptured) return null
        var best = 0
        var bestScore = Double.NEGATIVE_INFINITY
        var bestNorm = 0.0
        for (lag in -maxLag..maxLag) {
            var dot = 0.0
            var song = 0.0
            var deck = 0.0
            val from = maxOf(0, -lag)
            val to = minOf(SPLICE_CAPTURE, SPLICE_CAPTURE - lag)
            for (i in from until to) {
                val a = spliceSong[i].toDouble()
                val b = spliceDeck[i + lag].toDouble()
                dot += a * b
                song += a * a
                deck += b * b
            }
            val norm = kotlin.math.sqrt(song * deck)
            if (norm > 0 && dot / norm > bestScore) {
                bestScore = dot / norm
                best = lag
                bestNorm = norm
            }
        }
        return if (bestNorm > 0) best to bestScore else null
    }

    companion object {
        // Media3's documented ~280 ms polling interval plus 120 ms scheduling
        // margin. Still rejects stalled observations instead of advancing forever.
        const val POSITION_FRESHNESS_NANOS = 400_000_000L
        private val POSITION_UNAVAILABLE = PositionReading(null)
        private val POSITION_CONTENDED = PositionReading(null, contended = true)
        const val SPLICE_CAPTURE = 8192
    }
}
