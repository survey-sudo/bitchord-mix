package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.djmix.StraightMixGeometry
import com.music.bitchord.playback.djmix.StraightRateMode
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.MixGain
import com.music.bitchord.playback.smart.MixTemplate
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.AutomixV2Choreo
import com.music.bitchord.playback.smart.choreo.DjLevel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow

/** Vocal mid-carve automation curves computed when incoming vocals overlap an active bed. */
internal class VocalMidCarve(
    val apply: Boolean,
    val outTimes: DoubleArray = DoubleArray(0),
    val outDb: FloatArray = FloatArray(0),
    val inTimes: DoubleArray = DoubleArray(0),
    val inDb: FloatArray = FloatArray(0),
)

/** Echo-out / dry-fall / filter envelope arrays for straight or handoff exits. */
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

/**
 * Deck tempo knot synthesis and carve/echo envelope builders extracted from
 * [LoopMixSession] (M9 slice L-2).
 */
internal object DeckKnotBuilder {

    /** Builds unwrapped frame-to-source-position knot maps for OUT and IN decks. */
    internal fun buildDeckKnots(
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        sampleRate: Int,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
        outHasTailKnot: Boolean,
        outStopAtSec: Double,
        tailRateEarly: Double,
        straightRateMode: StraightRateMode? = null,
        level: DjLevel = AutomixV2Choreo.level,
    ): LoopMixSession.Companion.DeckKnots {
        val sr = sampleRate.toDouble()
        val beats = clock.beats
        val totalInBeats = beats + clock.settleBeats

        if (straightRateMode != null) {
            require(!plan.outLoops && !plan.inLoops && plan.handoff == null)
            require(sampleRate > 0 && beats > 0 && clock.incomingHoldSec.isFinite() && clock.incomingHoldSec >= 0.0)
            if (straightRateMode == StraightRateMode.BEAT_MATCH) {
                require(StraightMixGeometry.rateMode(outAnalysis, inAnalysis, level) == StraightRateMode.BEAT_MATCH)
            }
            val outTimes = (clock.beatTimes.take(beats + 1) +
                if (outHasTailKnot) listOf(outStopAtSec) else emptyList()).distinct().sorted()
            // Insert the actual hold boundary, even when it is between beats.
            val inTimes = (clock.beatTimes.toList() + listOf(clock.incomingHoldSec))
                .filter { it <= clock.elementAt }.distinct().sorted()
            if (straightRateMode == StraightRateMode.RATE_ONE) {
                return LoopMixSession.Companion.DeckKnots(
                    DoubleArray(outTimes.size) { outTimes[it] * sr },
                    DoubleArray(outTimes.size) { plan.out.loopStart + outTimes[it] },
                    DoubleArray(inTimes.size) { inTimes[it] * sr },
                    DoubleArray(inTimes.size) { plan.inn.loopStart + max(0.0, inTimes[it] - clock.incomingHoldSec) },
                    LoopMixSession.GridSource.RATE_ONE, LoopMixSession.GridSource.RATE_ONE, sampleRate,
                )
            }
            fun progress(t: Double): Double {
                val (k, f) = clock.beatAt(t.coerceIn(0.0, clock.releaseAt))
                return minOf(beats.toDouble(), k + f)
            }
            fun trackedAt(grid: DoubleArray?, beat: Double, start: Double, step: Double): Double {
                if (grid == null) return start + beat * step
                val k = kotlin.math.floor(beat).toInt().coerceIn(0, grid.lastIndex)
                return if (k == grid.lastIndex) grid[k] else grid[k] + (grid[k + 1] - grid[k]) * (beat - k)
            }
            val outSide = outAnalysis?.tailBeats?.beats ?: outAnalysis?.headBeats?.beats
            val inSide = inAnalysis?.headBeats?.beats ?: inAnalysis?.tailBeats?.beats
            val trackedOut = LoopQualification.trackedBeatsInWindow(outSide, plan.out.loopStart, beats, plan.out.beatSec)
            val inCount = ceil((beats - progress(clock.incomingHoldSec)) * plan.fold).toInt().coerceAtLeast(1)
            val trackedIn = LoopQualification.trackedBeatsInWindow(inSide, plan.inn.loopStart, inCount, plan.inn.beatSec)
            fun outPositions(grid: DoubleArray?) = DoubleArray(outTimes.size) { i ->
                val t = outTimes[i]
                if (t <= clock.releaseAt) trackedAt(grid, progress(t), plan.out.loopStart, plan.out.beatSec)
                else {
                    val last = trackedAt(grid, beats.toDouble(), plan.out.loopStart, plan.out.beatSec)
                    val previous = trackedAt(grid, beats - 1.0, plan.out.loopStart, plan.out.beatSec)
                    last + (t - clock.releaseAt) * (last - previous) /
                        (clock.beatTimes[beats] - clock.beatTimes[beats - 1])
                }
            }
            fun inPositions(grid: DoubleArray?): DoubleArray {
                val result = DoubleArray(inTimes.size)
                val holdProgress = progress(clock.incomingHoldSec)
                val rel = trackedAt(grid, max(0.0, beats - holdProgress) * plan.fold, plan.inn.loopStart, plan.inn.beatSec)
                val prev = trackedAt(grid, max(0.0, beats - 1.0 - holdProgress) * plan.fold, plan.inn.loopStart, plan.inn.beatSec)
                val endRate = (rel - prev) / (clock.beatTimes[beats] - clock.beatTimes[beats - 1])
                var post = rel
                var lastT = clock.releaseAt
                for (i in inTimes.indices) {
                    val t = inTimes[i]
                    result[i] = if (t <= clock.releaseAt) {
                        trackedAt(grid, max(0.0, progress(t) - holdProgress) * plan.fold, plan.inn.loopStart, plan.inn.beatSec)
                    } else {
                        val u = ((t - clock.releaseAt) / (clock.elementAt - clock.releaseAt)).coerceIn(0.0, 1.0)
                        val rate = endRate + (1.0 - endRate) * (0.5 - 0.5 * cos(PI * u))
                        post += (t - lastT) * rate
                        lastT = t
                        post
                    }
                }
                return result
            }
            fun ratesFit(times: List<Double>, positions: DoubleArray, hold: Double): Boolean =
                (1 until positions.size).all { i ->
                    val advance = positions[i] - positions[i - 1]
                    if (advance == 0.0 && times[i] <= hold) true else {
                        val rate = advance / (times[i] - times[i - 1])
                        rate.isFinite() && rate >= 1.0 - level.maxStretch - 1e-9 && rate <= 1.0 + level.maxStretch + 1e-9
                    }
                }
            var op = outPositions(trackedOut)
            var ip = inPositions(trackedIn)
            val useOut = trackedOut != null && ratesFit(outTimes, op, 0.0)
            val useIn = trackedIn != null && ratesFit(inTimes, ip, clock.incomingHoldSec)
            if (!useOut) op = outPositions(null)
            if (!useIn) ip = inPositions(null)
            require(ratesFit(outTimes, op, 0.0) && ratesFit(inTimes, ip, clock.incomingHoldSec))
            return LoopMixSession.Companion.DeckKnots(
                DoubleArray(outTimes.size) { outTimes[it] * sr }, op,
                DoubleArray(inTimes.size) { inTimes[it] * sr }, ip,
                if (useOut) LoopMixSession.GridSource.TRACKED else LoopMixSession.GridSource.CLOCK,
                if (useIn) LoopMixSession.GridSource.TRACKED else LoopMixSession.GridSource.CLOCK,
                sampleRate,
            )
        }

        if (plan.handoff != null) {
            val h = requireNotNull(plan.handoff)
            val outTimes = (clock.beatTimes.filter { it <= outStopAtSec } + listOf(0.0,
                h.outCaptureStart.mixSec, h.outDryFallStart.mixSec, h.outDryEnd.mixSec,
                h.outSendClose.mixSec, outStopAtSec)).distinct().sorted()
            val inTimes = (clock.beatTimes.toList() + listOf(0.0, h.inHoldMixSec,
                h.inAudibleStart.mixSec, h.inFullLevelMixSec, h.inLanding.mixSec)).distinct().sorted()
            return LoopMixSession.Companion.DeckKnots(
                DoubleArray(outTimes.size) { outTimes[it] * sr },
                DoubleArray(outTimes.size) { plan.out.loopStart + outTimes[it] },
                DoubleArray(inTimes.size) { inTimes[it] * sr },
                DoubleArray(inTimes.size) { h.inCueSourceSec + max(0.0, inTimes[it] - h.inHoldMixSec) },
                LoopMixSession.GridSource.NATIVE_CLOCK, LoopMixSession.GridSource.NATIVE_CLOCK,
            )
        }

        val outFrames = DoubleArray(beats + 1 + if (outHasTailKnot) 1 else 0) { i ->
            if (i < beats + 1) clock.beatTimes[i] * sr else outStopAtSec * sr
        }
        val outSideBeats = outAnalysis?.tailBeats?.beats?.takeIf { it.isNotEmpty() }
            ?: outAnalysis?.headBeats?.beats
        val outLoopTracked = if (plan.outLoops && plan.out.beats > 0) {
            LoopQualification.trackedBeatsInWindow(outSideBeats, plan.out.loopStart, plan.out.beats, plan.out.beatSec)
        } else null
        val outStraightTracked = if (!plan.outLoops) {
            LoopQualification.trackedBeatsInWindow(outSideBeats, plan.out.loopStart, beats, plan.out.beatSec)
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
        var inGridSource = LoopMixSession.GridSource.CLOCK
        val inPositionsSec = DoubleArray(totalInBeats + 1) { k ->
            val mixSec = clock.beatTimes[k]
            when {
                clock.incomingHoldSec > 1e-9 -> clock.inUnwrappedTimeAt(mixSec)
                plan.nativeIncoming -> {
                    inGridSource = LoopMixSession.GridSource.NATIVE_CLOCK
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
                LoopQualification.trackedBeatsInWindow(inSideBeats, plan.inn.loopStart, expectedInCount, plan.inn.beatSec)
            } else null
            if (inTracked != null) {
                inGridSource = LoopMixSession.GridSource.TRACKED
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
                    val ease = 0.5 * (1.0 - cos(PI * u))
                    val rateK = endRate + (1.0 - endRate) * ease
                    inPositionsSec[k] = inPositionsSec[k - 1] + dt * rateK
                }
            }
        }

        return LoopMixSession.Companion.DeckKnots(
            outFrames, outPositionsSec, inFrames, inPositionsSec,
            outGridSource = if (outLoopTracked != null || outStraightTracked != null) LoopMixSession.GridSource.TRACKED else LoopMixSession.GridSource.CLOCK,
            inGridSource = inGridSource,
        )
    }

    /** Computes outgoing vocal-mid carve when incoming vocals overlap an active bed. */
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
    internal fun straightOutPcmEndSec(
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

    /** Builds echo/reverb send, dry gain, and filter sweeps for an echo-out exit. */
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
}
