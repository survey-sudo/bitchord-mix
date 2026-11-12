package com.music.bitchord.playback.loopmix

import android.util.Log
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.RegionalLoudness
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToLong

/** A stretch of a song decoded for a deck: planar, at the renderer's rate, from track second [start]. */
class LoopRegion(val left: FloatArray, val right: FloatArray, val start: Double)

/**
 * One loop mix, from planning to the incoming player's takeover: the plan and
 * its clock, the renderer with both decks loaded, and the two sinks' ports.
 *
 * Every time here is mix seconds — 0 being the moment the outgoing song reaches
 * its loop start — unless it says track seconds.
 */
class LoopMixSession private constructor(
    val plan: LoopMix.Plan,
    val clock: LoopMix.Clock,
    val outId: String,
    val inId: String,
    val renderer: LoopMixRenderer,
    val sampleRate: Int,
) {
    /** Track microseconds of the outgoing loop start: where the sink splices. */
    val takeoverUs: Long = (plan.out.loopStart * 1_000_000).roundToLong()

    /** Track second the incoming player becomes audible at. */
    val handoverTrack: Double = clock.elementTrackTime

    /** Where the incoming player starts (silent), and the mix second it starts at. */
    var inStartTrack: Double = 0.0
        private set
    var inStartAt: Double = 0.0
        private set

    /**
     * Null until [attach]. A primed session is closed when the queue changes,
     * which is before either sink exists. Reading a port that was never
     * attached must not throw.
     */
    var outPort: LoopMixPort? = null
        private set
    var inPort: LoopMixPort? = null
        private set

    private val closed = AtomicBoolean(false)

    /** The residual offset after alignment, ms (player minus deck); NaN until measured. */
    var alignedErrorMs: Double = Double.NaN
        private set

    fun attach(out: LoopMixPort, inn: LoopMixPort) {
        outPort = out
        inPort = inn
        val firstFrame = renderer.firstFrame()
        out.armOutgoing(LoopMixPort.OutArm(renderer, takeoverUs, sampleRate, firstFrame))
    }

    /**
     * Mix seconds now, read off the outgoing sink's own clock (what the
     * listener is hearing); null before it has a reading.
     */
    fun mixSeconds(nowNanos: Long, playing: Boolean): Double? =
        outPort?.presentedSeconds(nowNanos, playing)?.let { it - plan.out.loopStart }

    /**
     * Where the incoming player has to start: [STARTUP_LEAD] into the mix,
     * silent, at the track position that would put it on the deck's own
     * timeline. Before the song's start it starts at 0, and the silence the
     * alignment inserts makes up the rest.
     */
    fun planIncomingStart() {
        inStartAt = STARTUP_LEAD
        inStartTrack = max(0.0, handoverTrack - (clock.elementAt - inStartAt))
    }

    /**
     * How far the incoming player's audio is ahead of where the deck will be,
     * in seconds, measured now on both sinks' clocks; null without readings.
     * After the release the deck plays its song at rate 1 from its loop end, so
     * at mix second m it is at handoverTrack - (elementAt - m) — before the
     * release too, as far as the player's timeline is concerned.
     */
    fun offsetNow(nowNanos: Long): Double? {
        val m = mixSeconds(nowNanos, playing = true) ?: return null
        val player = inPort?.presentedSeconds(nowNanos, playing = true) ?: return null
        val deck = handoverTrack - (clock.elementAt - m)
        return player - deck
    }

    /** Asks the incoming sink to cancel [offsetSeconds] (player ahead: silence inserted; behind: frames dropped). */
    fun correct(offsetSeconds: Double) {
        val port = inPort ?: return
        val frames = (offsetSeconds * port.sampleRate.coerceAtLeast(1)).roundToLong()
        if (frames != 0L) port.requestShift(-frames)
    }

    fun recordAligned(errorSeconds: Double) {
        alignedErrorMs = errorSeconds * 1000
    }

    /** Idempotent. Disarms only the ports that were attached, then closes the renderer once. */
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        outPort?.disarm()
        inPort?.disarm()
        outPort = null
        inPort = null
        renderer.close()
    }

    companion object {
        private const val TAG = "BitChordLoopMix"

        /** Mix seconds into the mix at which the incoming player starts, silent. */
        const val STARTUP_LEAD = 0.5

        /** Seconds of each song decoded either side of what the decks play. */
        private const val PREROLL_SECONDS = 1.0
        private const val TAIL_SECONDS = 3.0

        /**
         * Builds the renderer for [plan] at [sampleRate], decoding the two
         * regions through [decode] (track seconds from..to, resampled to
         * [sampleRate]); null when either won't decode. Blocking: call off the
         * main thread. [inLevel] scales the incoming deck (loudness matching).
         */
        fun build(
            plan: LoopMix.Plan,
            outId: String,
            inId: String,
            sampleRate: Int,
            inLevel: Float,
            highQuality: Boolean = true,
            script: com.music.bitchord.playback.smart.PhraseScript? = null,
            decode: (side: LoopMix.Side, from: Double, to: Double) -> LoopRegion?,
        ): LoopMixSession? {
            if (!LoopMixRenderer.available || sampleRate <= 0) return null
            val clock = LoopMix.Clock(plan)
            // A loop only needs the loop plus the 0.5 s the seam crossfade reads
            // past it. A straight deck has to hold the whole phrase.
            val outTo = if (plan.outLoops) {
                plan.out.loopEnd + 0.5
            } else {
                plan.out.loopStart + plan.totalBars * 4 * plan.out.beatSec + 0.5
            }
            val inTail = if (plan.echoTail) TAIL_SECONDS + plan.out.beatSec * 8 else TAIL_SECONDS
            val outRegion = decode(
                LoopMix.Side.OUT,
                plan.out.loopStart - PREROLL_SECONDS,
                outTo,
            ) ?: return null
            val inRegion = decode(
                LoopMix.Side.IN,
                plan.inn.loopStart - PREROLL_SECONDS,
                clock.elementTrackTime + inTail,
            ) ?: return null
            if (outRegion.start > plan.out.loopStart || inRegion.start > plan.inn.loopStart) {
                Log.w(TAG, "a region starts after its loop: out ${outRegion.start}, in ${inRegion.start}")
                return null
            }
            val renderer = LoopMixRenderer(sampleRate)
            if (!renderer.isValid) return null
            val sr = sampleRate.toDouble()
            val beats = clock.beats

            fun samplesOf(region: LoopRegion, trackSeconds: Double) = (trackSeconds - region.start) * sr

            // K-weighted loudness of the outgoing exit window against the incoming
            // entry window. The whole-track ratio is only the fallback when a
            // window is too quiet to measure. Either way the gain stays in −7..+5 dB.
            val outEndSec = if (plan.outLoops) plan.out.loopEnd
            else plan.out.loopStart + plan.totalBars * 4 * plan.out.beatSec
            val inEndSec = plan.inn.loopStart + 8.0 * plan.inn.beatSec
            val matched = RegionalLoudness.matchGain(
                outLeft = outRegion.left,
                outRight = outRegion.right,
                outFrom = samplesOf(outRegion, plan.out.loopStart).toInt(),
                outTo = samplesOf(outRegion, outEndSec).toInt(),
                inLeft = inRegion.left,
                inRight = inRegion.right,
                inFrom = samplesOf(inRegion, plan.inn.loopStart).toInt(),
                inTo = samplesOf(inRegion, inEndSec).toInt(),
                sampleRate = sampleRate,
            )
            val level = (matched ?: inLevel).coerceIn(0.4467f, 1.7783f)

            val echoHold = if (plan.echoTail) plan.out.beatSec * 8 else 0.0
            // Outgoing: its loop, knot by knot, looping forever (it stops at the release).
            val outKnotFrames = DoubleArray(beats + 1) { clock.beatTimes[it] * sr }
            val outKnotPositions = DoubleArray(beats + 1) { samplesOf(outRegion, clock.outPositionAt(it)) }
            val outLoopEnd = if (plan.outLoops) plan.out.loopEnd else plan.out.loopStart
            renderer.setDeck(
                LoopMixRenderer.OUT,
                LoopMixRenderer.DeckSetup(
                    left = outRegion.left,
                    right = outRegion.right,
                    regionStart = outRegion.start,
                    loopStart = samplesOf(outRegion, plan.out.loopStart).roundToLong(),
                    loopEnd = samplesOf(outRegion, outLoopEnd).roundToLong(),
                    releaseAt = 1e18,
                    knotFrames = outKnotFrames,
                    knotPositions = outKnotPositions,
                    runOn = false,
                    stopAt = clock.releaseAt + 0.05 + echoHold,
                    preroll = 0.4,
                    level = 1f,
                ),
            )
            // Incoming: its loop until the release, then on from the loop end at rate 1.
            val inKnotFrames = DoubleArray(beats + 1) { clock.beatTimes[it] * sr }
            val inKnotPositions = DoubleArray(beats + 1) {
                val at = if (plan.nativeIncoming) {
                    val hold = script?.incomingHoldSec ?: 0.0
                    val played = (clock.beatTimes[it] - hold).coerceAtLeast(0.0)
                    plan.inn.loopStart + played
                } else {
                    clock.inPositionAt(it)
                }
                samplesOf(inRegion, at)
            }
            val inLoopEnd = if (plan.inLoops) plan.inn.loopEnd else plan.inn.loopStart
            renderer.setDeck(
                LoopMixRenderer.IN,
                LoopMixRenderer.DeckSetup(
                    left = inRegion.left,
                    right = inRegion.right,
                    regionStart = inRegion.start,
                    loopStart = samplesOf(inRegion, plan.inn.loopStart).roundToLong(),
                    loopEnd = samplesOf(inRegion, inLoopEnd).roundToLong(),
                    releaseAt = samplesOf(inRegion, clock.inReleasePosition),
                    knotFrames = inKnotFrames,
                    knotPositions = inKnotPositions,
                    runOn = true,
                    stopAt = clock.elementAt + 2.0,
                    preroll = 0.4,
                    level = level,
                ),
            )
            if (plan.echoTail) {
                val delay = script?.echoDelaySec?.takeIf { it > 0.01 } ?: plan.out.beatSec * 0.75
                val feedback = script?.echoFeedback?.takeIf { it > 0f } ?: 0.44f
                renderer.setEcho(LoopMixRenderer.OUT, delay, feedback, 0.55f)
                // The delay hears dry × send. Send stays shut through pre-roll
                // and opens across the last beat, so the repeats start on the
                // release instead of ringing the whole phrase.
                val open = (clock.releaseAt - plan.out.beatSec).coerceAtLeast(0.0)
                renderer.setEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND,
                    doubleArrayOf(0.0, open, clock.releaseAt),
                    floatArrayOf(0f, 0f, 0.6f),
                )
            }

            // Gains: the outgoing deck takes over from its song, the incoming loop
            // rises over the entry and the ramp, the outgoing one falls away.
            val h = LoopMix.HANDOVER_SEC
            renderer.setEnvelope(
                LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                doubleArrayOf(-h, 0.0, clock.enterEnd, clock.rampEnd, clock.releaseAt),
                floatArrayOf(0f, 1f, 1f, 0.6f, 0f),
            )
            renderer.setEnvelope(
                LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                doubleArrayOf(0.0, clock.enterEnd, clock.rampEnd),
                floatArrayOf(0f, 0.55f, 1f),
            )
            // Bass: the incoming loop comes in without it; they swap over one bar mid-ramp.
            val swapFrom = clock.handoffAt - clock.swapBar / 2
            val swapTo = clock.handoffAt + clock.swapBar / 2
            val cut = LoopMix.BASS_CUT_DB.toFloat()
            renderer.setEnvelope(
                LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB,
                doubleArrayOf(swapFrom, swapTo), floatArrayOf(0f, cut),
            )
            renderer.setEnvelope(
                LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB,
                doubleArrayOf(swapFrom, swapTo), floatArrayOf(cut, 0f),
            )
            when {
                plan.carve -> {
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                        doubleArrayOf(clock.enterEnd, clock.rampEnd),
                        floatArrayOf(20000f, 1200f),
                        exponential = true,
                    )
                    renderer.setEnvelope(
                        LoopMixRenderer.IN, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                        doubleArrayOf(0.0, clock.rampEnd, clock.releaseAt),
                        floatArrayOf(650f, 650f, 20f),
                        exponential = true,
                    )
                }
                plan.clash && plan.outLoops -> {
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                        doubleArrayOf(0.0, clock.releaseAt),
                        floatArrayOf(2800f, 2800f),
                        exponential = true,
                    )
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                        doubleArrayOf(0.0, clock.rampEnd, clock.releaseAt),
                        floatArrayOf(380f, 380f, LoopMix.HIGH_PASS_TO_HZ.toFloat()),
                        exponential = true,
                    )
                }
                else -> {
                    // The outgoing loop drains away through a rising high-pass once the tempo has arrived.
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                        doubleArrayOf(clock.rampEnd, clock.releaseAt),
                        floatArrayOf(LoopMix.HIGH_PASS_FROM_HZ.toFloat(), LoopMix.HIGH_PASS_TO_HZ.toFloat()),
                        exponential = true,
                    )
                }
            }
            if (script != null) {
                applyPhraseLanes(renderer, clock, plan, script)
                renderer.setIsolate(LoopMixRenderer.OUT, true)
                renderer.setIsolate(LoopMixRenderer.IN, true)
            }
            renderer.setHighQuality(highQuality)
            renderer.prepare()
            // The incoming deck hands over to its own player at this track time.
            // A straight deck (Echo, Drop, beat blend) fades on that sample. A
            // looping deck fades on the unwrapped position after the release.
            // Either way the deck is silent 25 ms later, so it does not keep
            // playing under the player.
            renderer.setFadeOut(LoopMixRenderer.IN, clock.elementTrackTime)
            return LoopMixSession(plan, clock, outId, inId, renderer, sampleRate).also { it.planIncomingStart() }
        }

        /**
         * Score-sheet lanes, in beats relative to the landing, written over the
         * generic blend. Gain is converted from dB. A value at or under −80 dB
         * is silence, which the renderer treats as a mute.
         */
        private fun applyPhraseLanes(
            renderer: LoopMixRenderer,
            clock: LoopMix.Clock,
            plan: LoopMix.Plan,
            script: com.music.bitchord.playback.smart.PhraseScript,
        ) {
            fun seconds(beatFromLanding: Double): Double {
                val landing = (plan.rampBars + plan.exitBars).coerceAtLeast(1) * 4.0
                val beat = landing + beatFromLanding
                if (beat <= 0.0) return beat * plan.out.beatSec
                val last = clock.beatTimes.lastIndex
                val k = beat.toInt().coerceIn(0, (last - 1).coerceAtLeast(0))
                val frac = (beat - k).coerceIn(0.0, 1.0)
                val t0 = clock.beatTimes[k]
                val t1 = clock.beatTimes[(k + 1).coerceAtMost(last)]
                return t0 + (t1 - t0) * frac
            }
            fun db(value: Float): Float = if (value <= -80f) 0f else 10.0.pow(value / 20.0).toFloat()
            fun put(deck: Int, kind: Int, lane: com.music.bitchord.playback.smart.Lane, asDb: Boolean, exponential: Boolean = lane.exponential) {
                if (lane.beats.isEmpty()) return
                val times = DoubleArray(lane.beats.size) { seconds(lane.beats[it]) }
                val values = FloatArray(lane.values.size) { if (asDb) db(lane.values[it]) else lane.values[it] }
                renderer.setEnvelope(deck, kind, times, values, exponential)
            }
            put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN, script.outGain, asDb = true)
            put(LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN, script.inGain, asDb = true)
            put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB, script.outLow, asDb = false)
            put(LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB, script.inLow, asDb = false)
            put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ, script.outHp, asDb = false, exponential = true)
            put(LoopMixRenderer.IN, LoopMixRenderer.KIND_HIGH_PASS_HZ, script.inHp, asDb = false, exponential = true)
            if (plan.echoTail) put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND, script.echoSend, asDb = false)
        }

        /** Within this, a handover is heard as one sound; past it, the correction runs again. */
        const val ALIGNED_SECONDS = 0.0015

        fun aligned(errorSeconds: Double) = abs(errorSeconds) <= ALIGNED_SECONDS
    }
}
