package com.music.bitchord.playback.loopmix

import android.util.Log
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LoopMixDuck
import com.music.bitchord.playback.smart.MixGain
import com.music.bitchord.playback.smart.TransitionAlternatives
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.ReleaseSplice
import com.music.bitchord.playback.smart.RegionalLoudness
import com.music.bitchord.playback.smart.TrackAnalysis
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Instrumental stem for one channel: dry minus vocal, sample-exact.
 * The native OLA is sample-aligned (same framing, pad-centred, renormalised
 * by win_sq), so a single vocal-stem inference suffices; no second Wiener
 * pass is needed for the instrumental. Null when the vocal stem is not
 * sample-aligned with the dry channel (caller keeps the dry region).
 */
internal fun dryMinusVocal(dry: FloatArray, vocal: FloatArray): FloatArray? =
    if (dry.size != vocal.size) null else FloatArray(dry.size) { dry[it] - vocal[it] }

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
    /** Track second the incoming player becomes audible at (knot-exact). */
    val handoverTrack: Double = clock.elementTrackTime,
) {
    /** Track microseconds of the outgoing loop start: where the sink splices. */
    val takeoverUs: Long = (plan.out.loopStart * 1_000_000).roundToLong()

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
        val (startAt, startTrack) = incomingStartFor(clock, handoverTrack)
        inStartAt = startAt
        inStartTrack = startTrack
    }

    /**
     * How far the incoming player's audio is ahead of where the deck will be,
     * in seconds, measured now on both sinks' clocks; null without readings.
     * After the release the deck plays its song at rate 1 from its loop end, so
     * at mix second m it is at handoverTrack - (elementAt - m) — before the
     * release too, as far as the player's timeline is concerned.
     */
    fun offsetNow(nowNanos: Long): Double? {
        val deck = incomingTrackNow(nowNanos, outgoingPlaying = true) ?: return null
        val player = inPort?.presentedSeconds(nowNanos, playing = true) ?: return null
        return player - deck
    }

    /** The decoder's rate-1 timeline, anchored to the actual renderer knot. */
    fun incomingTrackNow(nowNanos: Long, outgoingPlaying: Boolean): Double? =
        mixSeconds(nowNanos, outgoingPlaying)?.let { handoverTrack + (it - clock.elementAt) }

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

        /**
         * Measured energy-weighted clash above which the outgoing vocal is
         * stem-ducked.
         *
         * 0.12, not 0.05: E is a weighted product, so a soft verse over a soft
         * verse lands at 0.04-0.16 (0.09 canonical) — exactly the band the
         * planner deliberately lets through the veto. Ducking that to a 0.15
         * floor carves a -16.5 dB hole into a clean handover. Above ~0.12 the
         * pair is two real voices, and there a solvable gain curve beats two
         * intelligible leads. Deliberately distinct from
         * VOCAL_CLASH_TOLERANCE (0.05), which is the planner's rendering
         * target for "reads as clean", a stricter question than "worth
         * ducking".
         */
        const val STEM_DUCK_TRIGGER = LoopMixDuck.TRIGGER

        /**
         * Residual outgoing vocal gain after the duck. 0.15, not silence: a
         * fully removed vocal under an instrument bed reads as karaoke, which
         * is a worse artefact than a quiet collision. At the Wiener curve this
         * floor keeps about 2% energy, but as a gain on the stem it keeps the
         * phrase present and audible.
         */
        const val STEM_DUCK_FLOOR = LoopMixDuck.MIN_FLOOR

        /** Two-thread daemon pool for parallel OUT+IN region decode (vc73). */
        private val DECODE_EXECUTOR: java.util.concurrent.ExecutorService =
            java.util.concurrent.Executors.newFixedThreadPool(
                2,
                java.util.concurrent.ThreadFactory { r -> Thread(r, "loopmix-decode").apply { isDaemon = true } },
            )

        /** Mix seconds into the mix at which the incoming player starts, silent. */
        const val STARTUP_LEAD = 0.5

        /**
         * The incoming player's start, computed from a plan clock alone:
         * startAt into the mix, at track position startTrack. The hold
         * branch can land the start after handoffAt (the vc64 freeze); the
         * clamp keeps at least STARTUP_LEAD of decode head-room before
         * elementAt so driveLoopMix cannot run out of mix before the start
         * (djmix23, vc65). Pure JVM, no renderer needed.
         */
        fun incomingStartFor(
            clock: LoopMix.Clock,
            handoverTrack: Double = clock.elementTrackTime,
        ): Pair<Double, Double> {
            val hold = clock.incomingHoldSec
            val element = clock.elementAt
            val base = if (hold > 1e-9) {
                minOf(max(STARTUP_LEAD, hold - 1.5), max(STARTUP_LEAD, element - 3.0))
            } else {
                STARTUP_LEAD
            }
            val tBase = handoverTrack - (element - base)
            var inStartAt = if (hold > 1e-9 && tBase < 0.0 && handoverTrack >= 2.5) {
                max(base, (element - handoverTrack).coerceAtLeast(STARTUP_LEAD))
            } else {
                base
            }
            inStartAt = clampIncomingStartAt(inStartAt, element, STARTUP_LEAD)
            val inStartTrack = max(0.0, handoverTrack - (element - inStartAt))
            return inStartAt to inStartTrack
        }

        /** Latest cue: at least [startupLead] before elementAt when feasible. */
        fun clampIncomingStartAt(inStartAt: Double, elementAt: Double, startupLead: Double = STARTUP_LEAD): Double {
            val latest = (elementAt - startupLead).coerceAtLeast(startupLead)
            return min(inStartAt, latest)
        }

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
            script: PhraseScript? = null,
            separateStem: ((side: LoopMix.Side, left: FloatArray, right: FloatArray, sampleRate: Int, keepVocals: Boolean) -> Pair<FloatArray, FloatArray>?)? = null,
            separateVocalStem: ((side: LoopMix.Side, left: FloatArray, right: FloatArray, sampleRate: Int) -> Pair<FloatArray, FloatArray>?)? = null,
            separateVocalPair: ((side: LoopMix.Side, left: FloatArray, right: FloatArray, sampleRate: Int) -> Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>?)? = null,
            outAnalysis: TrackAnalysis? = null,
            inAnalysis: TrackAnalysis? = null,
            sungOut: List<LoopMix.Span>? = null,
            sungIn: List<LoopMix.Span>? = null,
            decode: (side: LoopMix.Side, from: Double, to: Double) -> LoopRegion?,
        ): LoopMixSession? {

            if (!LoopMixRenderer.available || sampleRate <= 0) return null
            // djmix18: the script hold is the truth for the handover: pass it into the
            // clock so clock.handoverTrackSec, the IN knots and setFadeOut share ONE
            // timestamp (was: Clock(plan) defaulted to plan.incomingHoldSec while IN knots
            // read script?.incomingHoldSec — up to seconds apart on phrase plans).
            val effectiveHoldSec = script?.incomingHoldSec ?: plan.incomingHoldSec
            val effectivePlan = if (effectiveHoldSec != plan.incomingHoldSec) {
                plan.copy(incomingHoldSec = effectiveHoldSec)
            } else plan
            val clock = LoopMix.Clock(effectivePlan)
            // Nominal bounds shared with fast-ready checks. Actual tracked
            // knots below may extend the physical decode request before build.
            val bounds = LoopMix.regionBounds(effectivePlan)
            val echoHoldEarly = if (effectivePlan.echoTail) effectivePlan.out.beatSec * 8 else 0.0
            val outStopMixSec = clock.releaseAt + 0.05 + echoHoldEarly + effectivePlan.exitTailSeconds
            val tailRateEarly = if (clock.beats >= 1) {
                val b0 = clock.beatTimes[clock.beats - 1]
                val b1 = clock.beatTimes[clock.beats]
                (effectivePlan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
            } else 1.0
            val outTo = bounds.outTo
            val inTail = bounds.inTo - clock.elementTrackTime
            // Resolve actual tracked knots before decoding. A local grid can
            // extend beyond nominal Clock bounds; decode that physical passage
            // once, then reject an incomplete decoder result below.
            val outHasTailKnot = !effectivePlan.outLoops && outStopMixSec > clock.releaseAt + 1e-6
            val knots = buildDeckKnots(effectivePlan, clock, sampleRate, outAnalysis, inAnalysis,
                outHasTailKnot, outStopMixSec, tailRateEarly)
            val incomingGeometry = incomingGeometry(effectivePlan, clock, knots)
            val actualInTo = maxOf(bounds.inTo, incomingGeometry.handoverTrack + inTail,
                incomingGeometry.requiredEnd)
            // Preserve straight OUT's existing real-EOF/effect-drain contract.
            // Only repeated OUT audio needs a complete loop body and seam.
            val requiredOutEnd = if (effectivePlan.outLoops) effectivePlan.out.loopEnd + 0.008 else null
            val actualOutTo = outTo
            val (outDecoded, inDecoded) = try {
                val outFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    { decode(LoopMix.Side.OUT, bounds.outFrom, actualOutTo) },
                    DECODE_EXECUTOR,
                )
                val inFuture = java.util.concurrent.CompletableFuture.supplyAsync(
                    { decode(LoopMix.Side.IN, bounds.inFrom, actualInTo) },
                    DECODE_EXECUTOR,
                )
                outFuture.get() to inFuture.get()
            } catch (e: Exception) {
                decode(LoopMix.Side.OUT, bounds.outFrom, actualOutTo) to decode(LoopMix.Side.IN, bounds.inFrom, actualInTo)
            }
            var outRegion = outDecoded ?: return null
            var inRegion = inDecoded ?: return null
            if (outRegion.start > plan.out.loopStart || inRegion.start > plan.inn.loopStart) {
                Log.w(TAG, "a region starts after its loop: out ${outRegion.start}, in ${inRegion.start}")
                return null
            }
            if (!coversOutgoingStartAndLoop(outRegion, sampleRate, effectivePlan) ||
                !coversPhysical(inRegion, sampleRate, effectivePlan.inn.loopStart, incomingGeometry.requiredEnd)
            ) {
                Log.i(TAG, "loop-domain rejected: incomplete physical PCM out=${outRegion.left.size}/${outRegion.right.size} in=${inRegion.left.size}/${inRegion.right.size} requiredOut=$requiredOutEnd requiredIn=${incomingGeometry.requiredEnd}")
                return null
            }
            Log.i(TAG, "loop-domain outId=$outId inId=$inId rung=${effectivePlan.rung} script=${script != null} outLoops=${effectivePlan.outLoops} inLoops=${incomingGeometry.loops} loopStart=${effectivePlan.inn.loopStart} loopEnd=${effectivePlan.inn.loopEnd} releaseUnwrapped=${incomingGeometry.releaseTrack} lastUnwrapped=${knots.inPositionsSec.last()} handoverPhysical=${incomingGeometry.handoverTrack} requestedInFrom=${bounds.inFrom} requestedInTo=$actualInTo decodedInFrom=${inRegion.start} decodedInTo=${inRegion.start + inRegion.left.size.toDouble() / sampleRate} requiredInTo=${incomingGeometry.requiredEnd} requestedOutFrom=${bounds.outFrom} requestedOutTo=$actualOutTo decodedOutFrom=${outRegion.start} decodedOutTo=${outRegion.start + outRegion.left.size.toDouble() / sampleRate} requiredOutTo=$requiredOutEnd")
            // vc67: on-device umxhq Wiener stem separation for the acapella rung.
            // OUT carries the vocal stem (0.88 vocal + 0.12 dry air); IN carries the
            // instrumental when windowed stems are enabled. Worker thread only; null
            // falls back to the dry region so the build never fails on separation.
            if (effectivePlan.acapella && separateStem != null) {
                if (outRegion.left.size / sampleRate.toDouble() <= 21.5) {
                    val split = runCatching {
                        separateStem(LoopMix.Side.OUT, outRegion.left, outRegion.right, sampleRate, true)
                    }.getOrNull()
                    if (split != null && split.first.size == outRegion.left.size && split.second.size == outRegion.right.size) {
                        val bl = FloatArray(outRegion.left.size) { 0.88f * split.first[it] + 0.12f * outRegion.left[it] }
                        val br = FloatArray(outRegion.right.size) { 0.88f * split.second[it] + 0.12f * outRegion.right[it] }
                        outRegion = LoopRegion(bl, br, outRegion.start)
                    }
                }
                val stemsOn = runCatching { com.music.bitchord.data.settings.AppSettings.automixWindowedStemsEnabled.value }.getOrDefault(false)
                if (stemsOn && inRegion.left.size / sampleRate.toDouble() <= 21.5) {
                    val splitIn = runCatching {
                        separateStem(LoopMix.Side.IN, inRegion.left, inRegion.right, sampleRate, false)
                    }.getOrNull()
                    if (splitIn != null && splitIn.first.size == inRegion.left.size && splitIn.second.size == inRegion.right.size) {
                        inRegion = LoopRegion(splitIn.first, splitIn.second, inRegion.start)
                    }
                }
            }
            var outStemSeparated = false
            var inStemSeparated = false
            // Stem-duck is default-on, gated only by feasibility. The old
            // settings check (automixWindowedStemsEnabled) has always defaulted
            // true, so the real gate was the boolean below it: separation only
            // ran when the count-based test fired, which is exactly the noisy
            // case. Feasibility is the 21.5 s window and a separator that
            // actually answers; both are checked where they arise below.
            if (!effectivePlan.acapella && (separateVocalPair != null || separateVocalStem != null)) {
                val inWinFrom = plan.inn.loopStart
                val inWinTo = maxOf(inWinFrom + 1.0, clock.elementTrackTime)
                val inMask = if (!inAnalysis?.vocalWindows.isNullOrEmpty()) {
                    LoopMix.maskStats(inAnalysis!!.vocalWindows, inWinFrom, inWinTo)
                } else null
                val inSungTouching = sungIn?.any { it.certainUntil > inWinFrom + 0.05 && it.start < inWinTo - 0.1 } == true
                val scriptLead = (script?.choice?.entry?.leadInVocal ?: 0f) >= 0.2f
                val inHasEarlyVocal = (inMask != null && (inMask.mean >= 0.25 || inMask.peak >= 0.45)) || inSungTouching || scriptLead
                val outVocalEndSec = if (plan.outLoops) plan.out.loopEnd else plan.out.loopStart + plan.totalBars * 4 * plan.out.beatSec
                val outMask = if (!outAnalysis?.vocalWindows.isNullOrEmpty()) {
                    LoopMix.maskStats(outAnalysis!!.vocalWindows, plan.out.loopStart, outVocalEndSec)
                } else null
                val outSung = !sungOut.isNullOrEmpty()
                val outHasVocal = (outMask != null && outMask.mean >= 0.20) || outSung || effectivePlan.carve || effectivePlan.clash || effectivePlan.muffle || ((outAnalysis?.vocalProbability ?: 0.0) >= 0.35)
                // The measured collision decides where it was measured; the
                // legacy boolean gate is the fallback where it was not. A duck
                // decision taken from the boolean would re-litigate the veto
                // the planner already made with better data.
                val clashE = script?.choice?.vocalClashEnergy?.takeIf { it.isFinite() }
                    ?: if (outAnalysis != null && inAnalysis != null &&
                        outAnalysis.vocalWindows.isNotEmpty() && inAnalysis.vocalWindows.isNotEmpty()
                    ) {
                        val outBeatSec = effectivePlan.out.beatSec
                        val inBeatSec = effectivePlan.inn.beatSec
                        val fold = effectivePlan.fold
                        val rate = if (outBeatSec.isFinite() && outBeatSec > 0.0 &&
                            inBeatSec.isFinite() && inBeatSec > 0.0 &&
                            fold.isFinite() && fold > 0.0
                        ) {
                            (inBeatSec * fold) / outBeatSec
                        } else 1.0
                        com.music.bitchord.playback.smart.weightedSimultaneousVocal(
                            outAnalysis, inAnalysis, effectivePlan.out.loopStart, outVocalEndSec,
                            effectivePlan.inn.loopStart, rate,
                        )?.takeIf { it.isFinite() }
                    } else null
                val duck = LoopMixDuck.decide(
                    clashE = clashE,
                    outHasVocal = outHasVocal,
                    inHasEarlyVocal = inHasEarlyVocal,
                    fallbackTrigger = effectivePlan.carve || effectivePlan.clash || effectivePlan.muffle,
                )
                if (duck.wantDuck) {
                    Log.i(TAG, "stem-duck out=${effectivePlan.out.loopStart} in=${plan.inn.loopStart} clashE=$clashE outFloor=${duck.outFloor} inFloor=${duck.inFloor} duckIn=${duck.duckIncoming}")
                    val beatSec73 = effectivePlan.out.beatSec.takeIf { it > 0 } ?: 0.5
                    if (outRegion.left.size / sampleRate.toDouble() <= 21.5) {
                        // vc74 (djmix32): single-pass contract - only the vocal stem is
                        // inferred; the instrumental is dry minus vocal locally (the
                        // native OLA is sample-aligned: same framing, pad-centred,
                        // renormalised by win_sq), so no second Wiener pass per deck.
                        // vc77: prefer the exact (voc, inst) pair from VocalTracker
                        // (no rate round-trip residual); fall back to stem + local
                        // subtraction when the pair is absent or mis-sized.
                        val (voc, instL, instR) = run {
                            val pair = if (separateVocalPair != null) {
                                runCatching { separateVocalPair(LoopMix.Side.OUT, outRegion.left, outRegion.right, sampleRate) }.getOrNull()
                            } else null
                            if (pair != null &&
                                pair.first.first.size == outRegion.left.size && pair.first.second.size == outRegion.right.size &&
                                pair.second.first.size == outRegion.left.size && pair.second.second.size == outRegion.right.size
                            ) {
                                Triple(pair.first, pair.second.first, pair.second.second)
                            } else {
                                val stemVoc = if (separateVocalStem != null) runCatching { separateVocalStem(LoopMix.Side.OUT, outRegion.left, outRegion.right, sampleRate) }.getOrNull() else null
                                val stemInstL = if (stemVoc != null) dryMinusVocal(outRegion.left, stemVoc.first) else null
                                val stemInstR = if (stemVoc != null) dryMinusVocal(outRegion.right, stemVoc.second) else null
                                Triple(stemVoc, stemInstL, stemInstR)
                            }
                        }
                        if (voc != null && instL != null && instR != null) {
                            var fadeStartSec = maxOf(0.0, clock.handoffAt - 2 * beatSec73)
                            var fadeEndSec = minOf(clock.releaseAt, clock.handoffAt)
                            if (fadeEndSec <= fadeStartSec) {
                                fadeStartSec = 0.0
                                fadeEndSec = clock.handoffAt
                            }
                            val fadeStartSample = ((fadeStartSec + plan.out.loopStart - outRegion.start) * sampleRate.toDouble()).roundToLong().coerceIn(0, outRegion.left.size.toLong())
                            val fadeEndSample = ((fadeEndSec + plan.out.loopStart - outRegion.start) * sampleRate.toDouble()).roundToLong().coerceIn(fadeStartSample, outRegion.left.size.toLong())
                            val fadeLen = (fadeEndSample - fadeStartSample).coerceAtLeast(1)
                            val newLeft = FloatArray(outRegion.left.size)
                            val newRight = FloatArray(outRegion.right.size)
                            for (i in outRegion.left.indices) {
                                val g: Float = when {
                                    i < fadeStartSample -> 1.0f
                                    i >= fadeEndSample -> duck.outFloor
                                    else -> {
                                        val u = (i - fadeStartSample).toDouble() / fadeLen.toDouble()
                                        val c = 0.5 * (1.0 + cos(PI * u))
                                        (duck.outFloor + (1.0f - duck.outFloor) * c).toFloat()
                                    }
                                }
                                newLeft[i] = instL[i] + g * voc.first[i]
                                newRight[i] = instR[i] + g * voc.second[i]
                            }
                            outRegion = LoopRegion(newLeft, newRight, outRegion.start)
                            outStemSeparated = true
                        }
                    }
                    // Asymmetric: the incoming vocal is ducked only when the
                    // collision is severe (measured E > 0.6) or its line lands
                    // in the opening beats, because a gratuitously ducked entry
                    // reads as a karaoke intro. The outgoing side is always
                    // ducked: the listener is following it.
                    // Note: for measured plans the veto already refuses E >
                    // 0.20, so the E > 0.6 branch only fires on carve templates
                    // that skip the veto — in practice the incoming duck rests
                    // on inHasEarlyVocal.
                    if (duck.duckIncoming && inRegion.left.size / sampleRate.toDouble() <= 21.5) {
                        // vc77: exact pair first (see OUT above), stem fallback.
                        val (vocIn, instInL, instInR) = run {
                            val pair = if (separateVocalPair != null) {
                                runCatching { separateVocalPair(LoopMix.Side.IN, inRegion.left, inRegion.right, sampleRate) }.getOrNull()
                            } else null
                            if (pair != null &&
                                pair.first.first.size == inRegion.left.size && pair.first.second.size == inRegion.right.size &&
                                pair.second.first.size == inRegion.left.size && pair.second.second.size == inRegion.right.size
                            ) {
                                Triple(pair.first, pair.second.first, pair.second.second)
                            } else {
                                val stemVoc = if (separateVocalStem != null) runCatching { separateVocalStem(LoopMix.Side.IN, inRegion.left, inRegion.right, sampleRate) }.getOrNull() else null
                                val stemInstL = if (stemVoc != null) dryMinusVocal(inRegion.left, stemVoc.first) else null
                                val stemInstR = if (stemVoc != null) dryMinusVocal(inRegion.right, stemVoc.second) else null
                                Triple(stemVoc, stemInstL, stemInstR)
                            }
                        }
                        if (vocIn != null && instInL != null && instInR != null) {
                            val duckStartTrack = clock.inTrackTimeAt((clock.handoffAt - beatSec73).coerceAtLeast(0.0))
                            val duckEndTrack = clock.inTrackTimeAt(clock.handoffAt + 1.5 * beatSec73)
                            val duckStartSample = ((duckStartTrack - inRegion.start) * sampleRate.toDouble()).roundToLong().coerceIn(0, inRegion.left.size.toLong())
                            val duckEndSample = ((duckEndTrack - inRegion.start) * sampleRate.toDouble()).roundToLong().coerceIn(duckStartSample, inRegion.left.size.toLong())
                            val duckLen = (duckEndSample - duckStartSample).coerceAtLeast(1)
                            val newLeft = FloatArray(inRegion.left.size)
                            val newRight = FloatArray(inRegion.right.size)
                            for (i in inRegion.left.indices) {
                                val g: Float = when {
                                    i < duckStartSample -> duck.inFloor
                                    i >= duckEndSample -> 1.0f
                                    else -> {
                                        val u = (i - duckStartSample).toDouble() / duckLen.toDouble()
                                        val c = 0.5 * (1.0 - cos(PI * u))
                                        (duck.inFloor + (1.0f - duck.inFloor) * c).toFloat()
                                    }
                                }
                                newLeft[i] = instInL[i] + g * vocIn.first[i]
                                newRight[i] = instInR[i] + g * vocIn.second[i]
                            }
                            inRegion = LoopRegion(newLeft, newRight, inRegion.start)
                            inStemSeparated = true
                        }
                    }
                }
            }
            val renderer = LoopMixRenderer(sampleRate)
            if (!renderer.isValid) return null
            val sr = sampleRate.toDouble()
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
            // djmix14 peak-safe regional loudness: -6.0..+4.5 dB (was +-3 dB),
            // capped by peak headroom so loud masters never slam the limiter
            // while quiet tracks still match energy. Peak is the max abs over
            // the incoming region; boosting above 1.0 is capped at 0.92/peak.
            // Cutting (level<1) is never capped.
            val rawGain = (matched ?: inLevel).coerceIn(0.501f, 1.679f)
            var inPeak = 0f
            for (v in inRegion.left) { val a = abs(v); if (a > inPeak) inPeak = a }
            for (v in inRegion.right) { val a = abs(v); if (a > inPeak) inPeak = a }
            val level = if (rawGain > 1.0f && inPeak > 1e-4f) {
                minOf(rawGain, 0.92f / inPeak).coerceIn(0.501f, 1.679f)
            } else {
                rawGain
            }

            val echoHold = echoHoldEarly
            val outStopAtSec = outStopMixSec
            val outKnotFrames = knots.outFrames
            val outKnotPositions = DoubleArray(knots.outPositionsSec.size) {
                samplesOf(outRegion, knots.outPositionsSec[it])
            }
            val outLoopEnd = if (effectivePlan.outLoops) effectivePlan.out.loopEnd else effectivePlan.out.loopStart
            renderer.setDeck(
                LoopMixRenderer.OUT,
                LoopMixRenderer.DeckSetup(
                    left = outRegion.left,
                    right = outRegion.right,
                    regionStart = outRegion.start,
                    loopStart = samplesOf(outRegion, effectivePlan.out.loopStart).roundToLong(),
                    loopEnd = samplesOf(outRegion, outLoopEnd).roundToLong(),
                    releaseAt = 1e18,
                    knotFrames = outKnotFrames,
                    knotPositions = outKnotPositions,
                    runOn = false,
                    stopAt = clock.releaseAt + 0.05 + echoHold + effectivePlan.exitTailSeconds,
                    preroll = 0.4,
                    level = 1f,
                ),
            )
            val inKnotFrames = knots.inFrames
            val inKnotPositions = DoubleArray(knots.inPositionsSec.size) {
                samplesOf(inRegion, knots.inPositionsSec[it])
            }
            val inLoopEnd = if (incomingGeometry.loops) effectivePlan.inn.loopEnd else effectivePlan.inn.loopStart
            val inReleaseTrackSec = incomingGeometry.releaseTrack
            val handoverTrackSec = incomingGeometry.handoverTrack
            renderer.setDeck(
                LoopMixRenderer.IN,
                LoopMixRenderer.DeckSetup(
                    left = inRegion.left,
                    right = inRegion.right,
                    regionStart = inRegion.start,
                    loopStart = samplesOf(inRegion, effectivePlan.inn.loopStart).roundToLong(),
                    loopEnd = samplesOf(inRegion, inLoopEnd).roundToLong(),
                    releaseAt = samplesOf(inRegion, inReleaseTrackSec),
                    knotFrames = inKnotFrames,
                    knotPositions = inKnotPositions,
                    runOn = true,
                    stopAt = clock.elementAt + 2.0,
                    preroll = 0.4,
                    level = level,
                ),
            )

            val nativeEchoOut = effectivePlan.echoTail && effectivePlan.rung == "Echo" && script == null
            if (nativeEchoOut) {
                val echoEnv = computeEchoOutEnvelopes(effectivePlan, clock, outStopAtSec)
                renderer.setEcho(LoopMixRenderer.OUT, echoEnv.delaySec, echoEnv.feedback, 0.55f)
                renderer.setEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND,
                    echoEnv.echoSendTimes, echoEnv.echoSendValues,
                )
                renderer.setReverb(
                    LoopMixRenderer.OUT,
                    roomSize = 2.2f, damping = PhraseConfig.REVERB_DAMPING, wet = PhraseConfig.REVERB_WET,
                )
                renderer.setEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_REVERB_SEND,
                    echoEnv.reverbSendTimes, echoEnv.reverbSendValues,
                )
                renderer.setEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                    echoEnv.outGainTimes, echoEnv.outGainValues,
                )
                renderer.setEnvelope(
                    LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                    echoEnv.inGainTimes, echoEnv.inGainValues,
                )
                renderer.setEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB,
                    echoEnv.outLowTimes, echoEnv.outLowValues,
                )
                renderer.setEnvelope(
                    LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB,
                    echoEnv.inLowTimes, echoEnv.inLowValues,
                )
                renderer.setEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                    echoEnv.outHpTimes, echoEnv.outHpValues,
                    exponential = true,
                )
                renderer.setEnvelope(
                    LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_PASS_HZ,
                    echoEnv.inLpTimes, echoEnv.inLpValues,
                    exponential = true,
                )
            } else {
                if (effectivePlan.echoTail) {
                    val outBpmForEcho = if (effectivePlan.out.bpm > 0) effectivePlan.out.bpm else 120.0
                    val delayBeats = if (outBpmForEcho > PhraseConfig.ECHO_BPM_SPLIT) {
                        PhraseConfig.ECHO_DELAY_BEATS_FAST
                    } else {
                        PhraseConfig.ECHO_DELAY_BEATS
                    }
                    val delay = script?.echoDelaySec?.takeIf { it > 0.01 } ?: (effectivePlan.out.beatSec * delayBeats)
                    val feedback = script?.echoFeedback?.takeIf { it > 0f } ?: PhraseConfig.ECHO_FEEDBACK
                    renderer.setEcho(LoopMixRenderer.OUT, delay, feedback, 0.55f)
                    val open = (clock.releaseAt - effectivePlan.out.beatSec).coerceAtLeast(0.0)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND,
                        doubleArrayOf(0.0, open, clock.releaseAt),
                        floatArrayOf(0f, 0f, 0.6f),
                    )
                }
                val wantFlanger = (script?.flanger == true) || effectivePlan.flanger
                if (wantFlanger) {
                    val beatSec = effectivePlan.out.beatSec.coerceAtLeast(0.1)
                    val lfoHz = LoopMix.lfoHzForBeatSec(beatSec)
                    val isBuildup = effectivePlan.buildupRoll
                    val depth = if (isBuildup) LoopMix.FLANGER_DEPTH_BUILDUP_SEC else LoopMix.FLANGER_DEPTH_COLOUR_SEC
                    renderer.setFlanger(
                        LoopMixRenderer.OUT,
                        lfoHz = lfoHz,
                        depthSeconds = depth,
                        feedback = LoopMix.FLANGER_FEEDBACK,
                        wet = LoopMix.FLANGER_WET,
                    )
                    val rampEnd = clock.releaseAt
                    val rampStart = (rampEnd - 8.0 * beatSec).coerceAtLeast(clock.enterEnd)
                    val tail = rampEnd + minOf(beatSec * 2.0, 0.8)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_FLANGER_WET,
                        doubleArrayOf(rampStart, rampEnd, tail),
                        floatArrayOf(0.15f, 1.0f, 0.0f),
                    )
                }
                if (effectivePlan.buildupRoll) {
                    val beatSec = effectivePlan.out.beatSec.coerceAtLeast(0.1)
                    val totalBeats = clock.beats.toDouble()
                    val rollBeats = if (totalBeats >= 16.0) 16.0 else 8.0
                    val rollStartSec = (clock.releaseAt - rollBeats * beatSec).coerceAtLeast(0.0)
                    renderer.setLoopRoll(
                        LoopMixRenderer.OUT,
                        enabled = true,
                        rollStartSec = rollStartSec,
                        beatSec = beatSec,
                        releaseSec = clock.releaseAt,
                        totalRollBeats = rollBeats,
                    )
                    val hpEnd = effectivePlan.hpSweepEndHz.takeIf { it > 0f } ?: LoopMix.BUILDUP_HP_END_HZ
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                        doubleArrayOf(rollStartSec, clock.releaseAt),
                        floatArrayOf(30f, hpEnd),
                        exponential = true,
                    )
                    renderer.setSvfQ(LoopMixRenderer.OUT, LoopMix.BUILDUP_HP_Q)
                }
                if (effectivePlan.reverbWash) {
                    val reverbDamping = if (effectivePlan.buildupRoll) PhraseConfig.REVERB_DAMPING_EDM else PhraseConfig.REVERB_DAMPING
                    val reverbWet = if (effectivePlan.buildupRoll) PhraseConfig.REVERB_WET_EDM else PhraseConfig.REVERB_WET
                    renderer.setReverb(
                        LoopMixRenderer.OUT,
                        roomSize = 2.2f, damping = reverbDamping, wet = reverbWet,
                    )
                    val open = (clock.releaseAt - effectivePlan.out.beatSec).coerceAtLeast(0.0)
                    // vc77: taper the send back to zero by outStopMixSec so
                    // the wash never hard-cuts at stopAt.
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_REVERB_SEND,
                        doubleArrayOf(open, clock.releaseAt, outStopMixSec),
                        floatArrayOf(0f, 0.65f, 0f),
                    )
                }

                val h = LoopMix.HANDOVER_SEC
                val mixDe = clock.rampEnd - clock.enterEnd
                if (mixDe > 1e-6) {
                    val outFall = MixGain.fallPoints(clock.rampEnd, clock.releaseAt)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                        doubleArrayOf(-h, 0.0, clock.enterEnd, *outFall.first.sliceArray(1 until outFall.first.size)),
                        floatArrayOf(0f, 1f, 1f, *outFall.second.sliceArray(1 until outFall.second.size)),
                    )
                    val inRise = MixGain.risePoints(0.0, clock.rampEnd)
                    renderer.setEnvelope(
                        LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                        doubleArrayOf(*inRise.first, clock.releaseAt),
                        floatArrayOf(*inRise.second, 1f),
                    )
                } else {
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
                }
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
                if (effectivePlan.muffle && !outStemSeparated) {
                    // Muffle: steep low-pass closes over the outgoing deck
                    // across the entry portion, then reopens floor->open
                    // across the return leg. Breakpoints come from the
                    // family registry builder: strictly increasing times,
                    // closing full-band at releaseAt, no collapse on the
                    // degenerate enterEnd == rampEnd clock.
                    val (times, vals) = TransitionAlternatives.muffleEnvelope(clock)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                        times, vals,
                        exponential = true,
                    )
                }
                if (effectivePlan.beatlessBridge) {
                    // Beatless bridge: ambient hold on both decks, then both
                    // leave park->open over the return leg so the surviving
                    // deck is full-band at releaseAt.
                    val (times, vals) = TransitionAlternatives.bridgeEnvelope(clock)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                        times, vals,
                        exponential = true,
                    )
                    renderer.setEnvelope(
                        LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_PASS_HZ,
                        times, vals,
                        exponential = true,
                    )
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_REVERB_SEND,
                        doubleArrayOf(0.0, clock.releaseAt),
                        floatArrayOf(0.3f, 0.6f),
                    )
                }
                if (effectivePlan.dropIn) {
                    // Drop-in explicit arm: the incoming deck enters
                    // unfiltered by definition (no low-pass/high-pass curve
                    // on either deck). Bass handoff stays on KIND_LOW_DB
                    // already programmed above; never fall into the generic
                    // high-pass branch below.
                }
                if (effectivePlan.muffle || effectivePlan.beatlessBridge) {
                    // vc69 P1 release-to-dry splice: at the handback the wet
                    // deck releases into the dry one over a 0.25 s cosine
                    // window instead of the generic fall, so the handoff
                    // carries no audible splice artifact.
                    val splice = ReleaseSplice.envelopeArrays(clock.releaseAt, ReleaseSplice.SPLICE_SECONDS, 4)
                    val spliceDbValues = FloatArray(splice.second.size) { splice.second[it].toFloat() }
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                        splice.first, spliceDbValues,
                    )
                }
                val beatBlendPocket = effectivePlan.rung == "Beat blend"
                if (outStemSeparated) {
                    // vc73: stems already separate vocals; leave full-band. KIND_LOW_DB bass swap does the work.
                } else {
                when {
                    effectivePlan.dropIn -> {
                        // Drop-in explicit arm (see also the dropIn envelope
                        // block above): no low-pass/high-pass curve on either
                        // deck. The incoming enters unfiltered; bass changes
                        // hands on KIND_LOW_DB. This branch is intentional,
                        // not a fallthrough into the generic high-pass.
                    }
                    effectivePlan.carve -> {
                        renderer.setEnvelope(
                            LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                            doubleArrayOf(clock.enterEnd, clock.rampEnd),
                            floatArrayOf(20000f, 5500f),
                            exponential = true,
                        )
                        renderer.setEnvelope(
                            LoopMixRenderer.IN, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                            doubleArrayOf(0.0, clock.rampEnd, clock.releaseAt),
                            floatArrayOf(220f, 220f, 20f),
                            exponential = true,
                        )
                        if (effectivePlan.clash && !effectivePlan.buildupRoll) {
                            renderer.setEnvelope(
                                LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                                doubleArrayOf(clock.rampEnd, clock.releaseAt),
                                floatArrayOf(LoopMix.HIGH_PASS_FROM_HZ.toFloat(), LoopMix.HIGH_PASS_TO_HZ.toFloat()),
                                exponential = true,
                            )
                        }
                    }
                    beatBlendPocket -> {
                        // vc73: no LP/HP muffle. KIND_LOW_DB bass swap + computeVocalMidCarve (KIND_MID_DB) keep it clear.
                    }
                    effectivePlan.clash && effectivePlan.outLoops -> {
                        renderer.setEnvelope(
                            LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                            doubleArrayOf(clock.handoffAt, clock.releaseAt),
                            floatArrayOf(20000f, 5000f),
                            exponential = true,
                        )
                        renderer.setEnvelope(
                            LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                            doubleArrayOf(clock.handoffAt, clock.releaseAt),
                            floatArrayOf(20f, 220f),
                            exponential = true,
                        )
                    }
                    else -> {
                        renderer.setEnvelope(
                            LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                            doubleArrayOf(clock.rampEnd, clock.releaseAt),
                            floatArrayOf(LoopMix.HIGH_PASS_FROM_HZ.toFloat(), LoopMix.HIGH_PASS_TO_HZ.toFloat()),
                            exponential = true,
                        )
                    }
                }
                }
                if (script != null) {
                    applyPhraseLanes(renderer, clock, effectivePlan, script)
                    renderer.setIsolate(LoopMixRenderer.OUT, true)
                    renderer.setIsolate(LoopMixRenderer.IN, true)
                } else if (effectivePlan.carve || effectivePlan.clash || effectivePlan.rung == "Beat blend") {
                    renderer.setIsolate(LoopMixRenderer.OUT, true)
                    renderer.setIsolate(LoopMixRenderer.IN, true)
                }
                val midCarve = computeVocalMidCarve(effectivePlan, clock, script, inAnalysis, sungIn)
                if (midCarve.apply) {
                    renderer.setIsolate(LoopMixRenderer.OUT, true)
                    renderer.setIsolate(LoopMixRenderer.IN, true)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_MID_DB,
                        midCarve.outTimes, midCarve.outDb,
                    )
                    renderer.setEnvelope(
                        LoopMixRenderer.IN, LoopMixRenderer.KIND_MID_DB,
                        midCarve.inTimes, midCarve.inDb,
                    )
                }
            }
            renderer.setHighQuality(highQuality)
            renderer.prepare()
            renderer.setFadeOut(LoopMixRenderer.IN, handoverTrackSec)
            return LoopMixSession(effectivePlan, clock, outId, inId, renderer, sampleRate, handoverTrackSec).also { it.planIncomingStart() }
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
            put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_MID_DB, script.outMid, asDb = false)
            put(LoopMixRenderer.IN, LoopMixRenderer.KIND_MID_DB, script.inMid, asDb = false)
            put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_DB, script.outHigh, asDb = false)
            put(LoopMixRenderer.IN, LoopMixRenderer.KIND_HIGH_DB, script.inHigh, asDb = false)
            put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ, script.outHp, asDb = false, exponential = true)
            put(LoopMixRenderer.IN, LoopMixRenderer.KIND_HIGH_PASS_HZ, script.inHp, asDb = false, exponential = true)
            if (plan.echoTail) put(LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND, script.echoSend, asDb = false)
        }

        internal class DeckKnots(
            val outFrames: DoubleArray,
            val outPositionsSec: DoubleArray,
            val inFrames: DoubleArray,
            val inPositionsSec: DoubleArray,
        )

        internal class VocalMidCarve(
            val apply: Boolean,
            val outTimes: DoubleArray = DoubleArray(0),
            val outDb: FloatArray = FloatArray(0),
            val inTimes: DoubleArray = DoubleArray(0),
            val inDb: FloatArray = FloatArray(0),
        )

        internal class EchoOutEnvelopes(
            val delaySec: Double,
            val feedback: Float,
            val swapAtSec: Double,
            val echoSendTimes: DoubleArray,
            val echoSendValues: FloatArray,
            val reverbSendTimes: DoubleArray,
            val reverbSendValues: FloatArray,
            val outGainTimes: DoubleArray,
            val outGainValues: FloatArray,
            val inGainTimes: DoubleArray,
            val inGainValues: FloatArray,
            val outLowTimes: DoubleArray,
            val outLowValues: FloatArray,
            val inLowTimes: DoubleArray,
            val inLowValues: FloatArray,
            val outHpTimes: DoubleArray,
            val outHpValues: FloatArray,
            val inLpTimes: DoubleArray,
            val inLpValues: FloatArray,
        )

        internal fun trackedBeatsInWindow(
            sideBeats: List<Double>?,
            anchorStart: Double,
            count: Int,
            beatSec: Double,
        ): DoubleArray? {
            if (sideBeats.isNullOrEmpty() || count <= 0 || !beatSec.isFinite() || beatSec <= 0.05) return null
            var bestIdx = -1
            var bestDist = Double.POSITIVE_INFINITY
            for (i in sideBeats.indices) {
                val b = sideBeats[i]
                if (!b.isFinite()) continue
                val d = abs(b - anchorStart)
                if (d < bestDist) {
                    bestDist = d
                    bestIdx = i
                }
            }
            val maxPhaseGate = minOf(0.08, maxOf(LoopMix.PHASE_GATE_MS / 1000.0, beatSec * 0.15))
            if (bestIdx < 0 || bestDist > maxPhaseGate || bestIdx + count >= sideBeats.size) return null
            val out = DoubleArray(count + 1)
            out[0] = anchorStart
            for (k in 1..count) {
                val prev = sideBeats[bestIdx + k - 1]
                val cur = sideBeats[bestIdx + k]
                if (!cur.isFinite() || cur <= prev) return null
                val dt = cur - prev
                if (dt <= 0.05 || abs(dt / beatSec - 1.0) > 0.18) return null
                out[k] = cur
            }
            return out
        }

        internal data class IncomingGeometry(
            val loops: Boolean,
            val releaseTrack: Double,
            val handoverTrack: Double,
            val requiredEnd: Double,
        )

        /** Coordinates here remain track seconds; native subtracts region.start. */
        internal fun incomingGeometry(plan: LoopMix.Plan, clock: LoopMix.Clock, knots: DeckKnots): IncomingGeometry {
            val release = knots.inPositionsSec[clock.beats]
            val loops = plan.inLoops && !plan.nativeIncoming && release >= plan.inn.loopEnd
            fun physical(position: Double) = if (loops) LoopMix.physicalTrackTime(
                position, plan.inn.loopStart, plan.inn.loopEnd, release,
            ) else position
            val handover = physical(knots.inPositionsSec.last())
            // Entire repeated body plus the 8 ms seam continuation must exist;
            // post-release music extends through the 25 ms renderer fade.
            val required = maxOf(knots.inPositionsSec.maxOf(::physical), handover + 0.025,
                if (loops) plan.inn.loopEnd + 0.008 else plan.inn.loopStart)
            return IncomingGeometry(loops, release, handover, required)
        }

        internal fun coversPhysical(region: LoopRegion, sampleRate: Int, requiredStart: Double, requiredEnd: Double): Boolean =
            sampleRate > 0 && region.start.isFinite() && requiredStart.isFinite() && requiredEnd.isFinite() &&
                region.left.isNotEmpty() && region.left.size == region.right.size && region.start <= requiredStart &&
                region.start + region.left.size.toDouble() / sampleRate + 1.0 / sampleRate >= requiredEnd

        /** OUT loop completeness only; straight OUT may reach real EOF during FX drain. */
        internal fun coversOutgoingStartAndLoop(region: LoopRegion, sampleRate: Int, plan: LoopMix.Plan): Boolean =
            coversPhysical(region, sampleRate, plan.out.loopStart,
                if (plan.outLoops) plan.out.loopEnd + 0.008 else plan.out.loopStart) &&
                region.start + region.left.size.toDouble() / sampleRate > plan.out.loopStart

        internal fun buildDeckKnots(
            plan: LoopMix.Plan,
            clock: LoopMix.Clock,
            sampleRate: Int,
            outAnalysis: TrackAnalysis?,
            inAnalysis: TrackAnalysis?,
            outHasTailKnot: Boolean,
            outStopAtSec: Double,
            tailRateEarly: Double,
        ): DeckKnots {
            val sr = sampleRate.toDouble()
            val beats = clock.beats
            val totalInBeats = beats + clock.settleBeats

            val outFrames = DoubleArray(beats + 1 + if (outHasTailKnot) 1 else 0) { i ->
                if (i < beats + 1) clock.beatTimes[i] * sr else outStopAtSec * sr
            }
            val outSideBeats = outAnalysis?.tailBeats?.beats?.takeIf { it.isNotEmpty() }
                ?: outAnalysis?.headBeats?.beats
            val outLoopTracked = if (plan.outLoops && plan.out.beats > 0) {
                trackedBeatsInWindow(outSideBeats, plan.out.loopStart, plan.out.beats, plan.out.beatSec)
            } else null
            val outStraightTracked = if (!plan.outLoops) {
                trackedBeatsInWindow(outSideBeats, plan.out.loopStart, beats, plan.out.beatSec)
            } else null
            val outPositionsSec = DoubleArray(beats + 1 + if (outHasTailKnot) 1 else 0) { i ->
                if (i < beats + 1) {
                    when {
                        outLoopTracked != null -> {
                            val loopSpan = plan.out.loopEnd - plan.out.loopStart
                            outLoopTracked[i % plan.out.beats] + (i / plan.out.beats) * loopSpan
                        }
                        outStraightTracked != null -> outStraightTracked[i]
                        else -> clock.outPositionAt(i)
                    }
                } else {
                    val lastPos = when {
                        outStraightTracked != null -> outStraightTracked[beats]
                        else -> plan.out.loopStart + clock.beats * plan.out.beatSec
                    }
                    lastPos + (outStopAtSec - clock.releaseAt) * tailRateEarly
                }
            }

            val inFrames = DoubleArray(totalInBeats + 1) { clock.beatTimes[it] * sr }
            val inPositionsSec = DoubleArray(totalInBeats + 1) { k ->
                val mixSec = clock.beatTimes[k]
                when {
                    clock.incomingHoldSec > 1e-9 -> clock.inUnwrappedTimeAt(mixSec)
                    plan.nativeIncoming -> plan.inn.loopStart + mixSec
                    k <= beats -> clock.inPositionAt(k)
                    else -> clock.inPositionAt(beats) + (mixSec - clock.releaseAt)
                }
            }

            val inSideBeats = inAnalysis?.headBeats?.beats?.takeIf { it.isNotEmpty() }
                ?: inAnalysis?.tailBeats?.beats
            if (plan.rung != "Echo" && !plan.inLoops && inSideBeats != null) {
                val holdIdx = if (clock.incomingHoldSec > 1e-9) {
                    (0..totalInBeats).firstOrNull { clock.beatTimes[it] >= clock.incomingHoldSec - 1e-6 } ?: beats
                } else 0
                val activeBeats = (totalInBeats - holdIdx).coerceAtLeast(0)
                val expectedInCount = kotlin.math.round(activeBeats * plan.fold).toInt()
                val inTracked = if (expectedInCount > 0) {
                    trackedBeatsInWindow(inSideBeats, plan.inn.loopStart, expectedInCount, plan.inn.beatSec)
                } else null
                if (inTracked != null) {
                    for (k in 0..holdIdx.coerceAtMost(totalInBeats)) {
                        inPositionsSec[k] = plan.inn.loopStart
                    }
                    for (k in (holdIdx + 1)..totalInBeats) {
                        val f = (k - holdIdx) * plan.fold
                        val idx0 = kotlin.math.floor(f).toInt().coerceIn(0, expectedInCount)
                        val frac = (f - idx0).coerceIn(0.0, 1.0)
                        inPositionsSec[k] = if (idx0 < expectedInCount) {
                            inTracked[idx0] + (inTracked[idx0 + 1] - inTracked[idx0]) * frac
                        } else {
                            inTracked[expectedInCount]
                        }
                    }
                }
            }

            // Smooth post-handoff tempo relaxation over settleBeats toward 1.000x
            // when Song B was stretched or followed a slightly drifting local grid.
            if (!plan.inLoops && clock.settleBeats > 0 && beats >= 1 && plan.rung != "Echo") {
                val dtLast = (clock.beatTimes[beats] - clock.beatTimes[beats - 1]).coerceAtLeast(1e-6)
                val endRate = ((inPositionsSec[beats] - inPositionsSec[beats - 1]) / dtLast).coerceIn(0.85, 1.15)
                if (abs(endRate - 1.0) > 1e-4) {
                    val sb = clock.settleBeats.coerceAtLeast(1)
                    for (s in 1..sb) {
                        val k = beats + s
                        val dt = (clock.beatTimes[k] - clock.beatTimes[k - 1]).coerceAtLeast(1e-6)
                        val u = s.toDouble() / sb
                        val ease = 0.5 * (1.0 - kotlin.math.cos(kotlin.math.PI * u))
                        val rateK = endRate + (1.0 - endRate) * ease
                        inPositionsSec[k] = inPositionsSec[k - 1] + dt * rateK
                    }
                }
            }

            return DeckKnots(outFrames, outPositionsSec, inFrames, inPositionsSec)
        }

        internal fun computeVocalMidCarve(
            plan: LoopMix.Plan,
            clock: LoopMix.Clock,
            script: PhraseScript?,
            inAnalysis: TrackAnalysis?,
            sungIn: List<LoopMix.Span>?,
        ): VocalMidCarve {
            if (plan.rung == "Echo") return VocalMidCarve(apply = false)
            val scriptInVocal = script != null && (
                script.choice.entry.leadInVocal >= 0.2f ||
                    script.choice.vocalOverlapBeats >= 1.0 ||
                    script.choice.template in setOf(MixTemplate.M4, MixTemplate.M5, MixTemplate.VOCAL_OVER_OUTRO)
                )
            val inWinFrom = plan.inn.loopStart
            val inWinTo = maxOf(inWinFrom + 2.0, clock.elementTrackTime)
            val earliestSung = sungIn
                ?.filter { it.certainUntil > inWinFrom + 0.05 && it.start < inWinTo - 0.1 }
                ?.minOfOrNull { max(inWinFrom, it.start) }
            val maskIn = if (!inAnalysis?.vocalWindows.isNullOrEmpty()) {
                LoopMix.maskStats(inAnalysis!!.vocalWindows, inWinFrom, inWinTo)
            } else null
            val maskInVocal = maskIn != null && (maskIn.share >= 0.15 || maskIn.longestRunSec >= 0.30)
            val probInVocal = sungIn == null && inAnalysis != null &&
                inAnalysis.vocalWindows.isEmpty() && inAnalysis.vocalProbability >= 0.45 && !plan.inLoops
            val incomingHasVocal = scriptInVocal || earliestSung != null || maskInVocal || probInVocal
            if (!incomingHasVocal) return VocalMidCarve(apply = false)

            // Do not clobber applyPhraseLanes if the script already wrote a mid carve.
            if (script != null && script.outMid.values.any { it <= -4.5f }) {
                return VocalMidCarve(apply = false)
            }

            val beatSec = plan.out.beatSec.coerceAtLeast(0.1)
            val release = clock.releaseAt.coerceAtLeast(0.25)
            val targetSec = when {
                earliestSung != null -> (clock.incomingHoldSec + (earliestSung - inWinFrom)).coerceIn(0.2, release)
                script != null -> {
                    val landingIdx = ((plan.rampBars + plan.exitBars).coerceAtLeast(1) * 4)
                        .coerceIn(1, clock.beatTimes.lastIndex)
                    clock.beatTimes[landingIdx].coerceIn(0.2, release)
                }
                else -> clock.handoffAt.coerceIn(minOf(2.0 * beatSec, release * 0.5), release)
            }
            val scoopDur = (4.0 * beatSec).coerceAtLeast(0.5)
            val scoopEnd = targetSec.coerceIn(0.2, release)
            val scoopStart = (scoopEnd - scoopDur).coerceAtLeast(0.0)
            val holdUntil = release.coerceAtLeast(scoopEnd + 0.01)
            val (outTimes, outDb) = if (scoopStart > 0.02) {
                doubleArrayOf(0.0, scoopStart, scoopEnd, holdUntil) to floatArrayOf(0f, 0f, -6f, -6f)
            } else {
                doubleArrayOf(0.0, scoopEnd, holdUntil) to floatArrayOf(0f, -6f, -6f)
            }
            return VocalMidCarve(
                apply = true,
                outTimes = outTimes,
                outDb = outDb,
                inTimes = doubleArrayOf(0.0, holdUntil),
                inDb = floatArrayOf(0f, 0f),
            )
        }

        internal fun computeEchoOutEnvelopes(
            plan: LoopMix.Plan,
            clock: LoopMix.Clock,
            outStopAtSec: Double,
        ): EchoOutEnvelopes {
            val beatSec = plan.out.beatSec.coerceAtLeast(0.1)
            val outBpm = if (plan.out.bpm > 1.0) plan.out.bpm else 60.0 / beatSec
            val delayBeats = if (outBpm > PhraseConfig.ECHO_BPM_SPLIT) {
                PhraseConfig.ECHO_DELAY_BEATS_FAST
            } else {
                PhraseConfig.ECHO_DELAY_BEATS
            }
            val delaySec = beatSec * delayBeats
            val feedback = PhraseConfig.ECHO_FEEDBACK_LONG.coerceIn(0.50f, 0.58f)
            val swapAt = clock.enterEnd.coerceAtLeast(beatSec)
            val preChargeStart = (swapAt - 1.75 * beatSec).coerceAtLeast(0.0)
            val cutEnd = swapAt + 0.10
            val sendClose = swapAt + 0.12
            val tailEnd = (swapAt + 4.0 * beatSec).coerceAtLeast(clock.releaseAt)
            val inOpenEnd = minOf(tailEnd, swapAt + 0.75 * beatSec)
            val releaseEnd = clock.releaseAt.coerceAtLeast(cutEnd + 0.01)
            val stopEnd = outStopAtSec.coerceAtLeast(releaseEnd + 0.02)

            val (echoTimes, echoVals) = if (preChargeStart > 0.01) {
                doubleArrayOf(0.0, preChargeStart, swapAt, sendClose) to floatArrayOf(0f, 0f, 0.90f, 0f)
            } else {
                doubleArrayOf(0.0, swapAt, sendClose) to floatArrayOf(0f, 0.90f, 0f)
            }
            val (revTimes, revVals) = if (preChargeStart > 0.01) {
                doubleArrayOf(0.0, preChargeStart, swapAt, sendClose) to floatArrayOf(0f, 0f, 0.28f, 0f)
            } else {
                doubleArrayOf(0.0, swapAt, sendClose) to floatArrayOf(0f, 0.28f, 0f)
            }
            val inRise = MixGain.risePoints(swapAt, inOpenEnd, steps = 8)
            val inGainTimes = doubleArrayOf(0.0, *inRise.first, clock.releaseAt.coerceAtLeast(inOpenEnd + 0.01))
            val inGainValues = floatArrayOf(0f, *inRise.second, 1f)
            val preHp = (swapAt - 0.1 * beatSec).coerceAtLeast(0.05)

            return EchoOutEnvelopes(
                delaySec = delaySec,
                feedback = feedback,
                swapAtSec = swapAt,
                echoSendTimes = echoTimes,
                echoSendValues = echoVals,
                reverbSendTimes = revTimes,
                reverbSendValues = revVals,
                outGainTimes = doubleArrayOf(-LoopMix.HANDOVER_SEC, 0.0, swapAt, cutEnd, releaseEnd, stopEnd),
                outGainValues = floatArrayOf(0f, 1f, 1f, 0f, 0f, 0f),
                inGainTimes = inGainTimes,
                inGainValues = inGainValues,
                outLowTimes = doubleArrayOf(swapAt, cutEnd),
                outLowValues = floatArrayOf(0f, LoopMix.BASS_CUT_DB.toFloat()),
                inLowTimes = doubleArrayOf(0.0, swapAt),
                inLowValues = floatArrayOf(0f, 0f),
                outHpTimes = doubleArrayOf(0.0, preHp, swapAt, tailEnd),
                outHpValues = floatArrayOf(20f, 20f, 120f, 1600f),
                inLpTimes = doubleArrayOf(0.0, swapAt, inOpenEnd),
                inLpValues = floatArrayOf(300f, 300f, 20000f),
            )
        }

        /** Within this, a handover is heard as one sound; past it, the correction runs again. */
        const val ALIGNED_SECONDS = 0.012

        fun aligned(errorSeconds: Double) = abs(errorSeconds) <= ALIGNED_SECONDS
    }
}
