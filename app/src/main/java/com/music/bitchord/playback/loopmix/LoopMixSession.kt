package com.music.bitchord.playback.loopmix

import android.util.Log
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LoopMixDuck
import com.music.bitchord.playback.smart.MixGain
import com.music.bitchord.playback.smart.TransitionAlternatives
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.RegionalLoudness
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.HandoffStatus
import com.music.bitchord.playback.smart.qualifyMusicalHandoff
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
    /** Actual native deck configuration; unknown only for manually constructed sessions. */
    val configuredOutLoops: Boolean? = null,
    val configuredInLoops: Boolean? = null,
    /** Source of the emitted knots, independent of planner anchor evidence. */
    val outGridSource: GridSource = GridSource.UNKNOWN,
    val inGridSource: GridSource = GridSource.UNKNOWN,
) {
    /** Bounded attribution; holds/tail/settle retain their existing clock geometry. */
    enum class GridSource(val telemetryValue: String) {
        UNKNOWN("unknown"), TRACKED("tracked"), CLOCK("clock"), NATIVE_CLOCK("native-clock"),
    }

    /** Preserve the prior manual-session constructor; actual geometry is unknown for this path. */
    private constructor(
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        outId: String,
        inId: String,
        renderer: LoopMixRenderer,
        sampleRate: Int,
        handoverTrack: Double,
    ) : this(plan, clock, outId, inId, renderer, sampleRate, handoverTrack,
        configuredOutLoops = null, configuredInLoops = null)

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
            var inStartAt = if (tBase < 0.0 && handoverTrack >= 2.5) {
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
        private const val PREROLL_SECONDS = 1.5
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
            onRefusal: ((String) -> Unit)? = null,
            onEnvelopeInstalled: ((deck: Int, kind: Int, times: DoubleArray, values: FloatArray) -> Unit)? = null,
            decode: (side: LoopMix.Side, from: Double, to: Double) -> LoopRegion?,
        ): LoopMixSession? {

            fun refuse(reason: String): LoopMixSession? {
                onRefusal?.invoke(reason)
                return null
            }
            if (sampleRate <= 0) return refuse("invalid_sample_rate")
            val crown = plan.outLoops && plan.inLoops
            // Preserve the early capability gate for non-crown routes. Crowns
            // qualify first, even without JNI, so a geometry refusal is not
            // misattributed to renderer capability (and never reaches decode).
            if (!crown && !LoopMixRenderer.available) return refuse("renderer_unavailable")
            // djmix18: the script hold is the truth for the handover: pass it into the
            // clock so clock.handoverTrackSec, the IN knots and setFadeOut share ONE
            // timestamp (was: Clock(plan) defaulted to plan.incomingHoldSec while IN knots
            // read script?.incomingHoldSec — up to seconds apart on phrase plans).
            val naturalHandoff = plan.handoff != null && plan.alternativeKind == "natural-end" &&
                !plan.echoTail && script == null
            val contractEcho = naturalHandoff || (plan.handoff != null && plan.echoTail &&
                ((plan.rung == "Echo" && script == null) || script?.choice?.template == MixTemplate.M6))
            val handoff = plan.handoff.takeIf { contractEcho }
            if (handoff != null && (!plan.incomingHoldSec.isFinite() || abs(plan.incomingHoldSec - handoff.inHoldMixSec) > 1e-8 ||
                    (script != null && (!script.incomingHoldSec.isFinite() ||
                        abs(script.incomingHoldSec - handoff.inHoldMixSec) > 1e-8)))) {
                return refuse("handoff_hold_mismatch")
            }
            val effectiveHoldSec = handoff?.inHoldMixSec ?: script?.incomingHoldSec ?: plan.incomingHoldSec
            val effectivePlan = if (effectiveHoldSec != plan.incomingHoldSec || (plan.handoff != null && !contractEcho)) {
                plan.copy(incomingHoldSec = effectiveHoldSec, handoff = handoff)
            } else plan
            if (crown) {
                val nominal = LoopMix.qualifyCrown(effectivePlan)
                if (!nominal.accepted) return refuse("crown_nominal_${nominal.refusal ?: "invalid"}")
            }
            val clock = LoopMix.Clock(effectivePlan)
            if (handoff != null) {
                val qualification = qualifyMusicalHandoff(handoff, outAnalysis, inAnalysis, sungOut, sungIn)
                Log.i(TAG, "handoff status=${qualification.status} reason=${qualification.reason} natural=$naturalHandoff outCoverage=${qualification.outCutMaskCoverage} inCoverage=${qualification.inEntryMaskCoverage} overlapCoverage=${qualification.overlapMaskCoverage}")
                if (qualification.status == HandoffStatus.UNSAFE) {
                    return refuse("handoff_${qualification.reason}")
                }
                if (qualification.status == HandoffStatus.UNKNOWN && !naturalHandoff) {
                    return refuse("handoff_unmeasured_capture")
                }
                if (naturalHandoff && (handoff.outDryFallStart != handoff.outDryEnd ||
                        abs(handoff.wetRetireMixSec - handoff.outDryEnd.mixSec) > 1e-8 || plan.reverbWash ||
                        handoff.inCueSourceSec != handoff.inAudibleStart.sourceSec ||
                        handoff.inAudibleStart.mixSec < handoff.outDryEnd.mixSec - 1e-8 ||
                        abs(handoff.inFullLevelMixSec - handoff.inAudibleStart.mixSec) > 1e-8)) {
                    return refuse("handoff_natural_geometry")
                }
                if (effectivePlan.outLoops || effectivePlan.inLoops || !effectivePlan.nativeIncoming ||
                    abs(effectivePlan.inn.loopStart - handoff.inCueSourceSec) > 1e-8 ||
                    abs(effectivePlan.out.loopStart - (handoff.outDryEnd.sourceSec - handoff.outDryEnd.mixSec)) > 1e-8 ||
                    handoff.inLanding.mixSec > clock.releaseAt + 1e-8 ||
                    handoff.outDryEnd.mixSec > clock.releaseAt + 1e-8) {
                    return refuse("handoff_plan_geometry")
                }
            }
            // Contract request starts are clamped to physical source zero by
            // regionBounds, once for both parallel and fallback decode and
            // identically for the controller's cache-ready query.
            // Nominal bounds shared with fast-ready checks. Actual tracked
            // knots below may extend the physical decode request before build.
            val bounds = LoopMix.regionBounds(effectivePlan)
            val echoHoldEarly = if (effectivePlan.echoTail) effectivePlan.out.beatSec * 8 else 0.0
            val outStopMixSec = handoff?.wetRetireMixSec
                ?: (clock.releaseAt + 0.05 + echoHoldEarly + effectivePlan.exitTailSeconds)
            if (handoff != null && handoff.wetRetireMixSec > clock.releaseAt + 1e-8) {
                return refuse("handoff_render_budget")
            }
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
            if (handoff != null) {
                qualifyHandoffKnots(effectivePlan, clock, sampleRate, knots)?.let { return refuse(it) }
            }
            if (crown) {
                val actual = qualifyCrownKnots(effectivePlan, clock, sampleRate, knots)
                if (!actual.accepted) return refuse(requireNotNull(actual.refusal))
                if (!LoopMixRenderer.available) return refuse("renderer_unavailable")
            }
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
            var outRegion = outDecoded ?: return refuse("decode_out_unavailable")
            var inRegion = inDecoded ?: return refuse("decode_in_unavailable")
            if (outRegion.start > plan.out.loopStart || inRegion.start > plan.inn.loopStart) {
                Log.w(TAG, "a region starts after its loop: out ${outRegion.start}, in ${inRegion.start}")
                return refuse("pcm_starts_after_loop")
            }
            if (!coversOutgoingStartAndLoop(outRegion, sampleRate, effectivePlan) ||
                !coversPhysical(inRegion, sampleRate, effectivePlan.inn.loopStart, incomingGeometry.requiredEnd)
            ) {
                Log.i(TAG, "loop-domain rejected: incomplete physical PCM out=${outRegion.left.size}/${outRegion.right.size} in=${inRegion.left.size}/${inRegion.right.size} requiredOut=$requiredOutEnd requiredIn=${incomingGeometry.requiredEnd}")
                return refuse("pcm_incomplete_physical_coverage")
            }
            if (handoff != null && !coversHandoffPcm(effectivePlan, sampleRate, knots, outRegion, inRegion)) {
                return refuse("handoff_pcm_before_protected_event")
            }
            Log.i(TAG, "loop-domain outId=$outId inId=$inId rung=${effectivePlan.rung} script=${script != null} outLoops=${effectivePlan.outLoops} inLoops=${incomingGeometry.loops} loopStart=${effectivePlan.inn.loopStart} loopEnd=${effectivePlan.inn.loopEnd} releaseUnwrapped=${incomingGeometry.releaseTrack} lastUnwrapped=${knots.inPositionsSec.last()} handoverPhysical=${incomingGeometry.handoverTrack} requestedInFrom=${bounds.inFrom} requestedInTo=$actualInTo decodedInFrom=${inRegion.start} decodedInTo=${inRegion.start + inRegion.left.size.toDouble() / sampleRate} requiredInTo=${incomingGeometry.requiredEnd} requestedOutFrom=${bounds.outFrom} requestedOutTo=$actualOutTo decodedOutFrom=${outRegion.start} decodedOutTo=${outRegion.start + outRegion.left.size.toDouble() / sampleRate} requiredOutTo=$requiredOutEnd")
            // vc67: on-device umxhq Wiener stem separation for the acapella rung.
            // OUT carries the vocal stem (0.88 vocal + 0.12 dry air); IN carries the
            // instrumental when windowed stems are enabled. Worker thread only; null
            // falls back to the dry region so the build never fails on separation.
            if (handoff == null && effectivePlan.acapella && separateStem != null) {
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
            // Contract qualification already rejects source-aligned simultaneous
            // vocal intervals. Its valid pickups (including disjoint bursts)
            // must not be re-ducked using the legacy whole-window projection.
            if (handoff == null && !effectivePlan.acapella && (separateVocalPair != null || separateVocalStem != null)) {
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
            if (!renderer.isValid) return refuse("renderer_build_failed")
            val sr = sampleRate.toDouble()
            fun samplesOf(region: LoopRegion, trackSeconds: Double) = (trackSeconds - region.start) * sr
            fun installEnvelope(deck: Int, kind: Int, times: DoubleArray, values: FloatArray,
                exponential: Boolean = false) {
                renderer.setEnvelope(deck, kind, times, values, exponential)
                onEnvelopeInstalled?.invoke(deck, kind, times.copyOf(), values.copyOf())
            }

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
                    stopAt = handoff?.wetRetireMixSec ?: (clock.releaseAt + 0.05 + echoHold + effectivePlan.exitTailSeconds),
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

            val nativeEchoOut = contractEcho || (effectivePlan.echoTail && effectivePlan.rung == "Echo" && script == null)
            val contractM6 = handoff != null && script?.choice?.template == MixTemplate.M6
            if (nativeEchoOut) {
                val outPcmEndSec = if (effectivePlan.outLoops) Double.POSITIVE_INFINITY else {
                    straightOutPcmEndSec(outKnotFrames, outKnotPositions, outRegion.left.size, sr)
                }
                val echoEnv = computeEchoOutEnvelopes(effectivePlan, clock, outStopAtSec, outPcmEndSec, sampleRate)
                if (!naturalHandoff) renderer.setEcho(LoopMixRenderer.OUT, echoEnv.delaySec, echoEnv.feedback, 0.55f)
                if (!contractM6) installEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND,
                    echoEnv.echoSendTimes, echoEnv.echoSendValues,
                )
                if (!naturalHandoff) renderer.setReverb(
                    LoopMixRenderer.OUT,
                    roomSize = 2.2f, damping = PhraseConfig.REVERB_DAMPING, wet = PhraseConfig.REVERB_WET,
                )
                installEnvelope(
                    LoopMixRenderer.OUT, LoopMixRenderer.KIND_REVERB_SEND,
                    echoEnv.reverbSendTimes, echoEnv.reverbSendValues,
                )
                if (!contractM6) {
                    installEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_GAIN,
                        echoEnv.outGainTimes, echoEnv.outGainValues)
                    installEnvelope(LoopMixRenderer.IN, LoopMixRenderer.KIND_GAIN,
                        echoEnv.inGainTimes, echoEnv.inGainValues)
                    installEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_DB,
                        echoEnv.outLowTimes, echoEnv.outLowValues)
                    installEnvelope(LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_DB,
                        echoEnv.inLowTimes, echoEnv.inLowValues)
                    installEnvelope(LoopMixRenderer.OUT, LoopMixRenderer.KIND_HIGH_PASS_HZ,
                        echoEnv.outHpTimes, echoEnv.outHpValues, exponential = true)
                }
                installEnvelope(LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_PASS_HZ,
                    echoEnv.inLpTimes, echoEnv.inLpValues, exponential = true)
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
                    // Taper to explicit 0.0 by release+2 beats so the native
                    // echoWetRetireStart fires musically (positive-to-zero
                    // release) instead of falling back to stopAt-1s.
                    val echoBeatSec = effectivePlan.out.beatSec.coerceAtLeast(0.1)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_ECHO_SEND,
                        doubleArrayOf(0.0, open, clock.releaseAt, clock.releaseAt + 2.0 * echoBeatSec),
                        floatArrayOf(0f, 0f, 0.6f, 0f),
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
                    // Muffle DYING OUT: monotonic C1 raised-cosine close
                    // open->floor (700 Hz) over the whole span, ending at the
                    // floor at releaseAt while the OUT fader falls to zero.
                    // No 20 kHz reopen right before death (no brightness
                    // blast). Breakpoints come from the family registry
                    // builder sampled on the beat grid, strictly increasing.
                    val (times, vals) = TransitionAlternatives.muffleEnvelope(clock)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                        times, vals,
                        exponential = true,
                    )
                }
                if (effectivePlan.beatlessBridge) {
                    // Beatless bridge: dying OUT closes monotonically to park
                    // (no 20 kHz reopen); survivor IN parks then reopens
                    // park->open over the proportional C1 return leg so it
                    // is full-band at releaseAt.
                    val (outTimes, outVals) = TransitionAlternatives.bridgeDeadOutEnvelope(clock)
                    val (inTimes, inVals) = TransitionAlternatives.bridgeEnvelope(clock)
                    renderer.setEnvelope(
                        LoopMixRenderer.OUT, LoopMixRenderer.KIND_LOW_PASS_HZ,
                        outTimes, outVals,
                        exponential = true,
                    )
                    renderer.setEnvelope(
                        LoopMixRenderer.IN, LoopMixRenderer.KIND_LOW_PASS_HZ,
                        inTimes, inVals,
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
                // Muffle/bridge OUT keeps the planned MixGain fallPoints
                // (rampEnd -> releaseAt) fall to zero: no unity-gain splice
                // override here (the old ReleaseSplice 1.0-until-releaseAt
                // holds the dying deck loud right before the handoff). The
                // ReleaseSplice class is kept for genuine survivor-stem
                // splices elsewhere; it is simply not used to hold a dying
                // OUT at unity.
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
                    applyPhraseLanes(renderer, clock, effectivePlan, script, contractM6, ::installEnvelope)
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
            // M6's explicit contract has a generic Echo effects setup above,
            // but the score-sheet lanes are the one authoritative set of
            // overlapping gain/send/filter envelopes. Install them after the
            // non-overlapping contract effect sends, before native prepare.
            if (nativeEchoOut && contractM6 && script != null) {
                applyPhraseLanes(renderer, clock, effectivePlan, script, contractM6, ::installEnvelope)
                renderer.setIsolate(LoopMixRenderer.OUT, true)
                renderer.setIsolate(LoopMixRenderer.IN, true)
            }
            renderer.setHighQuality(highQuality)
            renderer.prepare()
            renderer.setFadeOut(LoopMixRenderer.IN, handoverTrackSec)
            return LoopMixSession(effectivePlan, clock, outId, inId, renderer, sampleRate, handoverTrackSec,
                configuredOutLoops = effectivePlan.outLoops, configuredInLoops = incomingGeometry.loops,
                outGridSource = knots.outGridSource, inGridSource = knots.inGridSource,
            ).also { it.planIncomingStart() }
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
            contractM6: Boolean = false,
            install: (Int, Int, DoubleArray, FloatArray, Boolean) -> Unit =
                { deck, kind, times, values, exponential -> renderer.setEnvelope(deck, kind, times, values, exponential) },
        ) {
            val handoff = plan.handoff.takeIf { contractM6 }
            val landingBeat = handoff?.let {
                val (beat, fraction) = clock.beatAt(it.inLanding.mixSec)
                beat + fraction
            }
            fun clockBeatTime(beat: Double): Double {
                val times = clock.beatTimes
                if (beat <= 0.0) return beat * (times[1] - times[0])
                val last = times.lastIndex
                if (beat >= last) return times[last] + (beat - last) * (times[last] - times[last - 1])
                val k = beat.toInt()
                val fraction = beat - k
                return times[k] + (times[k + 1] - times[k]) * fraction
            }
            fun seconds(beatFromLanding: Double): Double {
                if (handoff != null) return clockBeatTime(requireNotNull(landingBeat) + beatFromLanding)
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
                val values = FloatArray(lane.values.size) { if (asDb) db(lane.values[it]) else lane.values[it] }
                if (handoff == null) {
                    val times = DoubleArray(lane.beats.size) { seconds(lane.beats[it]) }
                    install(deck, kind, times, values, exponential)
                    return
                }
                val mappedBeats = lane.beats.copyOf()
                val points = mappedBeats.indices.map { seconds(mappedBeats[it]) to values[it] }.toMutableList()
                if (handoff != null && deck == LoopMixRenderer.OUT && kind == LoopMixRenderer.KIND_GAIN) {
                    // Keep the silent native preroll that the generic contract
                    // used to provide, then let the M6 lane own the actual cut.
                    points += (-LoopMix.HANDOVER_SEC to 0f)
                    points += (handoff.wetRetireMixSec to 0f)
                } else if (handoff != null && deck == LoopMixRenderer.IN && kind == LoopMixRenderer.KIND_GAIN) {
                    // M6 encodes its landing point first, then the earlier cue
                    // activation. Put events into renderer time order and make
                    // the landing full-level, as the contract requires.
                    points += (-LoopMix.HANDOVER_SEC to 0f)
                    points += (0.0 to 0f)
                    points += (handoff.inLanding.mixSec to 1f)
                }
                val ordered = points.sortedBy { it.first }.fold(mutableListOf<Pair<Double, Float>>()) { acc, point ->
                    if (acc.isNotEmpty() && abs(acc.last().first - point.first) <= 1e-12) {
                        acc[acc.lastIndex] = point
                    } else acc += point
                    acc
                }
                val times = DoubleArray(ordered.size) { ordered[it].first }
                val orderedValues = FloatArray(ordered.size) { ordered[it].second }
                install(deck, kind, times, orderedValues, exponential)
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
            val outGridSource: GridSource = GridSource.UNKNOWN,
            val inGridSource: GridSource = GridSource.UNKNOWN,
        )

        /** Interpolate the exact arrays supplied to native, in source seconds. */
        internal fun knotSourceAt(frames: DoubleArray, positions: DoubleArray, mixSec: Double, sampleRate: Int): Double? {
            if (sampleRate <= 0 || !mixSec.isFinite() || frames.size != positions.size || frames.size < 2 ||
                frames.any { !it.isFinite() } || positions.any { !it.isFinite() } ||
                (1 until frames.size).any { frames[it] <= frames[it - 1] }) return null
            val frame = mixSec * sampleRate
            if (frame < frames.first() - 1e-6 || frame > frames.last() + 1e-6) return null
            val right = frames.indexOfFirst { it >= frame }
            if (right <= 0) return positions.first()
            val f = (frame - frames[right - 1]) / (frames[right] - frames[right - 1])
            return positions[right - 1] + f * (positions[right] - positions[right - 1])
        }

        /** A contract is checked against emitted coordinates before any decoder or JNI work. */
        internal fun qualifyHandoffKnots(plan: LoopMix.Plan, clock: LoopMix.Clock, sampleRate: Int, knots: DeckKnots): String? {
            val h = plan.handoff ?: return null
            if (qualifyMusicalHandoff(h, null, null, null, null).status == HandoffStatus.UNSAFE) {
                return "handoff_invalid_geometry"
            }
            if (sampleRate <= 0 || plan.outLoops || plan.inLoops || !plan.nativeIncoming ||
                abs(clock.incomingHoldSec - h.inHoldMixSec) > 1e-8 ||
                h.inLanding.mixSec > clock.releaseAt + 1e-8 || h.outDryEnd.mixSec > clock.releaseAt + 1e-8) {
                return "handoff_plan_geometry"
            }
            val tolerance = 0.5 / sampleRate
            fun agrees(frames: DoubleArray, positions: DoubleArray, source: (Double) -> Double): Boolean {
                if (knotSourceAt(frames, positions, 0.0, sampleRate) == null || frames.first() != 0.0) return false
                return frames.indices.all { abs(positions[it] - source(frames[it] / sampleRate)) <= tolerance }
            }
            if (!agrees(knots.outFrames, knots.outPositionsSec) { plan.out.loopStart + it }) return "handoff_out_knot_mapping"
            if (!agrees(knots.inFrames, knots.inPositionsSec) {
                    h.inCueSourceSec + (it - h.inHoldMixSec).coerceAtLeast(0.0)
                }) return "handoff_in_knot_mapping"
            if (knotSourceAt(knots.inFrames, knots.inPositionsSec, h.inHoldMixSec, sampleRate)
                    ?.let { abs(it - h.inCueSourceSec) > tolerance } != false) return "handoff_in_hold_mapping"
            val outEvents = listOf(h.outCaptureStart, h.outDryFallStart, h.outDryEnd, h.outSendClose)
            val inEvents = listOf(h.inAudibleStart, h.inLanding)
            if (outEvents.any { p ->
                    knotSourceAt(knots.outFrames, knots.outPositionsSec, p.mixSec, sampleRate)
                        ?.let { abs(it - p.sourceSec) > tolerance } != false
                }) return "handoff_out_event_mapping"
            if (inEvents.any { p ->
                    knotSourceAt(knots.inFrames, knots.inPositionsSec, p.mixSec, sampleRate)
                        ?.let { abs(it - p.sourceSec) > tolerance } != false
                }) return "handoff_in_event_mapping"
            return null
        }

        internal fun coversHandoffPcm(plan: LoopMix.Plan, sampleRate: Int, knots: DeckKnots,
            out: LoopRegion, inn: LoopRegion): Boolean {
            val h = plan.handoff ?: return true
            val dryEnd = knotSourceAt(knots.outFrames, knots.outPositionsSec, h.outDryEnd.mixSec, sampleRate) ?: return false
            val entry = knotSourceAt(knots.inFrames, knots.inPositionsSec, h.inAudibleStart.mixSec, sampleRate) ?: return false
            val landing = knotSourceAt(knots.inFrames, knots.inPositionsSec, h.inLanding.mixSec, sampleRate) ?: return false
            // OUT may exhaust after dry end, while the effect continues to drain.
            return coversPhysical(out, sampleRate, h.outCaptureStart.sourceSec, dryEnd) &&
                coversPhysical(inn, sampleRate, minOf(h.inCueSourceSec, entry), landing + 1.0 / sampleRate)
        }

        internal data class CrownKnotQualification(
            val accepted: Boolean,
            val refusal: String? = null,
            val outMinRate: Double? = null,
            val outMaxRate: Double? = null,
            val inMinRate: Double? = null,
            val inMaxRate: Double? = null,
        )

        /**
         * Qualify the actual buildDeckKnots output, in unwrapped track seconds,
         * before PCM/JNI. This deliberately does not repair or clamp a knot.
         * Native prepare() finds the first position > 0.5 sample from p0 and
         * stays silent up to the preceding knot (renderer.cpp:673-689, 778-785).
         * Only a declared IN hold at p0 may use that initial activation contract;
         * after the first advance, neither deck may park or move backwards.
         */
        internal fun qualifyCrownKnots(
            plan: LoopMix.Plan,
            clock: LoopMix.Clock,
            sampleRate: Int,
            knots: DeckKnots,
        ): CrownKnotQualification {
            if (!plan.outLoops || !plan.inLoops) return CrownKnotQualification(true)
            if (sampleRate <= 0 || !clock.incomingHoldSec.isFinite() || clock.incomingHoldSec < 0.0) {
                return CrownKnotQualification(false, "crown_actual_geometry")
            }
            data class DeckResult(val refusal: String?, val minRate: Double?, val maxRate: Double?)
            fun deck(frames: DoubleArray, positions: DoubleArray, start: Double,
                expectedSize: Int, side: String, initialHoldSec: Double): DeckResult {
                var low: Double? = null
                var high: Double? = null
                fun result(reason: String?) = DeckResult(reason?.let { "crown_actual_${side}_$it" }, low, high)
                if (frames.size != expectedSize || frames.size != positions.size || frames.size < 2 ||
                    !start.isFinite() || start < 0.0 || frames.first() != 0.0 || positions.first() != start ||
                    frames.any { !it.isFinite() || it < 0.0 } ||
                    positions.any { !it.isFinite() || it < start }
                ) return result("geometry")
                var active = false
                for (i in 1 until frames.size) {
                    val outputAdvance = frames[i] - frames[i - 1]
                    val sourceAdvance = positions[i] - positions[i - 1]
                    if (!outputAdvance.isFinite() || outputAdvance <= 0.0 || !sourceAdvance.isFinite()) {
                        return result("geometry")
                    }
                    if (sourceAdvance == 0.0 && !active && initialHoldSec > 1e-9 &&
                        positions[i] == start && frames[i] / sampleRate <= initialHoldSec
                    ) continue
                    if (sourceAdvance <= 0.0) return result("nonadvancing")
                    active = true
                    val rate = sampleRate.toDouble() * sourceAdvance / outputAdvance
                    if (!rate.isFinite()) return result("geometry")
                    low = low?.let { minOf(it, rate) } ?: rate
                    high = high?.let { maxOf(it, rate) } ?: rate
                    // Round-off at an exact endpoint is not a new rate envelope.
                    if (rate < LoopMix.CROWN_MIN_EXECUTION_RATE - 1e-9 ||
                        rate > LoopMix.CROWN_MAX_EXECUTION_RATE + 1e-9
                    ) return result("rate")
                }
                return result(if (active) null else "nonadvancing")
            }
            val out = deck(knots.outFrames, knots.outPositionsSec, plan.out.loopStart,
                clock.beats + 1, "out", 0.0)
            val inn = deck(knots.inFrames, knots.inPositionsSec, plan.inn.loopStart,
                clock.beats + clock.settleBeats + 1, "in", clock.incomingHoldSec)
            val reason = out.refusal ?: inn.refusal
            return CrownKnotQualification(reason == null, reason,
                out.minRate, out.maxRate, inn.minRate, inn.maxRate)
        }

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
            val handoff = plan.handoff
            val release = handoff?.let {
                it.inCueSourceSec + max(0.0, clock.releaseAt - it.inHoldMixSec)
            } ?: knots.inPositionsSec[clock.beats]
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

            if (plan.handoff != null) {
                val h = requireNotNull(plan.handoff)
                val outTimes = (clock.beatTimes.filter { it <= outStopAtSec } + listOf(0.0,
                    h.outCaptureStart.mixSec, h.outDryFallStart.mixSec, h.outDryEnd.mixSec,
                    h.outSendClose.mixSec, outStopAtSec)).distinct().sorted()
                val inTimes = (clock.beatTimes.toList() + listOf(0.0, h.inHoldMixSec,
                    h.inAudibleStart.mixSec, h.inFullLevelMixSec, h.inLanding.mixSec)).distinct().sorted()
                return DeckKnots(DoubleArray(outTimes.size) { outTimes[it] * sr },
                    DoubleArray(outTimes.size) { plan.out.loopStart + outTimes[it] },
                    DoubleArray(inTimes.size) { inTimes[it] * sr },
                    DoubleArray(inTimes.size) { h.inCueSourceSec + max(0.0, inTimes[it] - h.inHoldMixSec) },
                    GridSource.NATIVE_CLOCK, GridSource.NATIVE_CLOCK)
            }

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
            var inGridSource = GridSource.CLOCK
            val inPositionsSec = DoubleArray(totalInBeats + 1) { k ->
                val mixSec = clock.beatTimes[k]
                when {
                    clock.incomingHoldSec > 1e-9 -> clock.inUnwrappedTimeAt(mixSec)
                    plan.nativeIncoming -> {
                        inGridSource = GridSource.NATIVE_CLOCK
                        plan.inn.loopStart + mixSec
                    }
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
                    inGridSource = GridSource.TRACKED
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

            return DeckKnots(outFrames, outPositionsSec, inFrames, inPositionsSec,
                outGridSource = if (outLoopTracked != null || outStraightTracked != null) GridSource.TRACKED else GridSource.CLOCK,
                inGridSource = inGridSource)
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

        /** Invert the emitted straight-deck knots at the last decoded OUT sample. */
        private fun straightOutPcmEndSec(
            frames: DoubleArray,
            positions: DoubleArray,
            pcmSize: Int,
            sampleRate: Double,
        ): Double {
            val end = pcmSize - 1.0
            val i = positions.indexOfFirst { it >= end }
            if (i < 0) return Double.POSITIVE_INFINITY // PCM covers all the emitted knots.
            if (i == 0) return frames.first() / sampleRate
            val fraction = (end - positions[i - 1]) / (positions[i] - positions[i - 1])
            return (frames[i - 1] + (frames[i] - frames[i - 1]) * fraction) / sampleRate
        }

        internal fun computeEchoOutEnvelopes(
            plan: LoopMix.Plan,
            clock: LoopMix.Clock,
            outStopAtSec: Double,
            outPcmEndSec: Double = Double.POSITIVE_INFINITY,
            sampleRate: Int = 48000,
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
            plan.handoff?.let { h ->
                // Physical coverage was checked against the emitted knots in
                // build; a shorter decoder result may never resize this lane.
                val fall = if (h.outDryFallStart.mixSec == h.outDryEnd.mixSec) {
                    // The last available source sample stays at unity; the
                    // first sample at EOF is zero, satisfying native's exit
                    // gate without fading any earlier source samples.
                    doubleArrayOf(max(0.0, h.outDryEnd.mixSec - 1.0 / sampleRate),
                        h.outDryEnd.mixSec) to floatArrayOf(1f, 0f)
                } else MixGain.fallPoints(h.outDryFallStart.mixSec, h.outDryEnd.mixSec, steps = 8).also {
                    // Float cos(pi/2) leaves a small residual. The contracted
                    // dry endpoint is exact silence, including its bass lane.
                    it.second[it.second.lastIndex] = 0f
                }
                val rise = if (h.inAudibleStart.mixSec == h.inFullLevelMixSec) {
                    // Open over the preceding output sample while the source
                    // cue is still held. Native's exact-point interpolation
                    // must reach 1 on the very first attack sample.
                    doubleArrayOf(max(0.0, h.inAudibleStart.mixSec - 1.0 / sampleRate),
                        h.inAudibleStart.mixSec) to floatArrayOf(0f, 1f)
                } else MixGain.risePoints(h.inAudibleStart.mixSec, h.inFullLevelMixSec, steps = 8)
                val sendTimes = doubleArrayOf(0.0, h.outCaptureStart.mixSec,
                    h.outDryFallStart.mixSec, h.outSendClose.mixSec)
                val entryTimes = if (h.inAudibleStart.mixSec == 0.0 && h.inFullLevelMixSec == 0.0) {
                    doubleArrayOf(0.0, clock.elementAt)
                } else doubleArrayOf(-LoopMix.HANDOVER_SEC, *rise.first, clock.elementAt)
                val entryValues = if (h.inAudibleStart.mixSec == 0.0 && h.inFullLevelMixSec == 0.0) {
                    floatArrayOf(1f, 1f)
                } else floatArrayOf(0f, *rise.second, 1f)
                return EchoOutEnvelopes(delaySec, feedback, h.outDryFallStart.mixSec,
                    sendTimes, if (plan.echoTail) floatArrayOf(0f, 0f, 0.90f, 0f) else FloatArray(4),
                    sendTimes, if (plan.echoTail) floatArrayOf(0f, 0f, 0.28f, 0f) else FloatArray(4),
                    doubleArrayOf(-LoopMix.HANDOVER_SEC, 0.0, *fall.first, outStopAtSec),
                    floatArrayOf(0f, 1f, *fall.second, 0f), entryTimes, entryValues,
                    fall.first, FloatArray(fall.second.size) { (LoopMix.BASS_CUT_DB * (1f - fall.second[it])).toFloat() },
                    doubleArrayOf(0.0, clock.elementAt), floatArrayOf(0f, 0f),
                    doubleArrayOf(0.0, h.outDryFallStart.mixSec, h.outDryEnd.mixSec, h.wetRetireMixSec),
                    floatArrayOf(20f, 20f, 120f, 1600f),
                    doubleArrayOf(0.0, clock.elementAt), floatArrayOf(20000f, 20000f))
            }
            val swapAt = clock.enterEnd.coerceAtLeast(beatSec)
            // Raised-cosine bell: rise over ~0.75 beat to the peak at swapAt,
            // brief hold, then cosine decay over ~0.75 beat to zero at the
            // beat boundary swapAt+beatSec (replaces the 0.90->0 in 120 ms
            // sendClose cliff).
            val riseStart = (swapAt - 0.75 * beatSec).coerceAtLeast(0.0)
            val peakEnd = swapAt + 0.25 * beatSec
            val bellEnd = swapAt + beatSec
            val sendClose = bellEnd
            val tailEnd = (swapAt + 4.0 * beatSec).coerceAtLeast(clock.releaseAt)
            val inOpenEnd = minOf(tailEnd, swapAt + 0.75 * beatSec)
            // Incoming LP reopen: 600 Hz start over ~3.0 beats (2.5-4.0
            // class) with the shared C1 shape (replaces 300->20000 snatch).
            val inLpOpenEnd = minOf(tailEnd, swapAt + 3.0 * beatSec)
            // Give straight OUT's dry and bass lanes the same window as IN's rise.
            // When PCM runs short, fit just the OUT lanes to the available audio.
            val (fallTimes, fallGains) = if (plan.outLoops) {
                doubleArrayOf(swapAt, swapAt + 0.10) to floatArrayOf(1f, 0f)
            } else {
                val fadeEnd = minOf(inOpenEnd, outPcmEndSec).coerceAtLeast(0.0)
                // If OUT is already exhausted at swap, taper its last available audio.
                val fadeStart = if (fadeEnd - swapAt >= 1e-6) swapAt else (fadeEnd - 0.10).coerceAtLeast(0.0)
                if (fadeEnd <= 1e-6) {
                    doubleArrayOf(0.0) to floatArrayOf(0f)
                } else {
                    val (times, gains) = MixGain.fallPoints(fadeStart, fadeEnd, steps = 8)
                    gains[gains.lastIndex] = 0f
                    times to gains
                }
            }
            val fadeOutLow = FloatArray(fallGains.size) { i ->
                (LoopMix.BASS_CUT_DB * (1f - fallGains[i])).toFloat()
            }
            val gainLeadTimes = if (fallTimes.first() > 0.0) doubleArrayOf(-LoopMix.HANDOVER_SEC, 0.0)
                else doubleArrayOf(-LoopMix.HANDOVER_SEC)
            val gainLeadValues = if (gainLeadTimes.size == 2) floatArrayOf(0f, 1f) else floatArrayOf(0f)
            val releaseEnd = clock.releaseAt.coerceAtLeast(fallTimes.last() + 0.01)
            val stopEnd = outStopAtSec.coerceAtLeast(releaseEnd + 0.02)

            // Sampled raised-cosine bell (rise ~0.75 beat, hold, cosine decay
            // ~0.75 beat to the beat boundary swapAt+beatSec). Native gain
            // interpolation is linear per segment, so sampling the cosine
            // keeps the send/reverb bells C1 at the ends.
            fun bell(peak: Float): Pair<DoubleArray, FloatArray> {
                val riseSteps = 4
                val decaySteps = 4
                val rt = ArrayList<Double>()
                val rv = ArrayList<Float>()
                rt.add(0.0)
                rv.add(0f)
                for (i in 1..riseSteps) {
                    val u = i.toDouble() / riseSteps.toDouble()
                    val s = 0.5 * (1.0 - cos(PI * u))
                    val t = riseStart + (swapAt - riseStart) * u
                    if (t > rt.last()) {
                        rt.add(t)
                        rv.add((peak * s).toFloat())
                    }
                }
                if (peakEnd > rt.last()) {
                    rt.add(peakEnd)
                    rv.add(peak)
                }
                for (i in 1..decaySteps) {
                    val u = i.toDouble() / decaySteps.toDouble()
                    val s = 0.5 * (1.0 - cos(PI * u))
                    val t = peakEnd + (bellEnd - peakEnd) * u
                    if (t > rt.last()) {
                        rt.add(t)
                        rv.add((peak * (1.0 - s)).toFloat())
                    }
                }
                if (rv.isNotEmpty()) rv[rv.lastIndex] = 0f
                val tArr = DoubleArray(rt.size) { rt[it] }
                val vArr = FloatArray(rv.size) { rv[it] }
                return tArr to vArr
            }
            val (echoTimes, echoVals) = bell(0.90f)
            val (revTimes, revVals) = bell(0.28f)
            // Incoming LP sampled C1 reopen 600 -> 20000 Hz over ~3.0 beats.
            val inLpTimesList = ArrayList<Double>()
            val inLpValsList = ArrayList<Float>()
            inLpTimesList.add(0.0)
            inLpValsList.add(600f)
            if (swapAt > 1e-9) {
                if (swapAt > inLpTimesList.last()) {
                    inLpTimesList.add(swapAt)
                    inLpValsList.add(600f)
                }
            }
            val lpSteps = 8
            val lpStartHz = 600.0
            val lpOpenHz = 20000.0
            for (i in 1..lpSteps) {
                val u = i.toDouble() / lpSteps.toDouble()
                val s = 0.5 * (1.0 - cos(PI * u))
                val t = swapAt + (inLpOpenEnd - swapAt) * u
                val v = (lpStartHz * (lpOpenHz / lpStartHz).pow(s)).toFloat()
                if (t > inLpTimesList.last()) {
                    inLpTimesList.add(t)
                    inLpValsList.add(v)
                }
            }
            val inLpTimesSampled = DoubleArray(inLpTimesList.size) { inLpTimesList[it] }
            val inLpValuesSampled = FloatArray(inLpValsList.size) { inLpValsList[it] }
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
                outGainTimes = doubleArrayOf(*gainLeadTimes, *fallTimes, releaseEnd, stopEnd),
                outGainValues = floatArrayOf(*gainLeadValues, *fallGains, 0f, 0f),
                inGainTimes = inGainTimes,
                inGainValues = inGainValues,
                outLowTimes = fallTimes,
                outLowValues = fadeOutLow,
                inLowTimes = doubleArrayOf(0.0, swapAt),
                inLowValues = floatArrayOf(0f, 0f),
                outHpTimes = doubleArrayOf(0.0, preHp, swapAt, tailEnd),
                outHpValues = floatArrayOf(20f, 20f, 120f, 1600f),
                inLpTimes = inLpTimesSampled,
                inLpValues = inLpValuesSampled,
            )
        }

        /** Within this, a handover is heard as one sound; past it, the correction runs again. */
        const val ALIGNED_SECONDS = 0.012

        fun aligned(errorSeconds: Double) = abs(errorSeconds) <= ALIGNED_SECONDS
    }
}
