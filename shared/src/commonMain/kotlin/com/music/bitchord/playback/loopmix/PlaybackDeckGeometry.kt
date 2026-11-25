package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.djmix.StraightRateMode
import com.music.bitchord.playback.djmix.StraightRatePolicy
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.DjLevel
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max

/** Production unwrapped deck geometry, independent of renderer and platform ownership. */
object PlaybackDeckGeometry {
    /** Builds unwrapped frame-to-source-position knot maps for OUT and IN decks. */
    fun buildDeckKnots(
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        sampleRate: Int,
        outAnalysis: TrackAnalysis?,
        inAnalysis: TrackAnalysis?,
        outHasTailKnot: Boolean,
        outStopAtSec: Double,
        tailRateEarly: Double,
        straightRateMode: StraightRateMode? = null,
        level: DjLevel,
    ): DeckKnots {
        val sr = sampleRate.toDouble()
        val beats = clock.beats
        val totalInBeats = beats + clock.settleBeats

        if (straightRateMode != null) {
            require(!plan.outLoops && !plan.inLoops && plan.handoff == null)
            require(sampleRate > 0 && beats > 0 && clock.incomingHoldSec.isFinite() && clock.incomingHoldSec >= 0.0)
            if (straightRateMode == StraightRateMode.BEAT_MATCH) {
                require(StraightRatePolicy.rateMode(outAnalysis, inAnalysis, level) == StraightRateMode.BEAT_MATCH)
            }
            val outTimes = (clock.beatTimes.take(beats + 1) +
                if (outHasTailKnot) listOf(outStopAtSec) else emptyList()).distinct().sorted()
            // Insert the actual hold boundary, even when it is between beats.
            val inTimes = (clock.beatTimes.toList() + listOf(clock.incomingHoldSec))
                .filter { it <= clock.elementAt }.distinct().sorted()
            if (straightRateMode == StraightRateMode.RATE_ONE) {
                return DeckKnots(
                    DoubleArray(outTimes.size) { outTimes[it] * sr },
                    DoubleArray(outTimes.size) { plan.out.loopStart + outTimes[it] },
                    DoubleArray(inTimes.size) { inTimes[it] * sr },
                    DoubleArray(inTimes.size) { plan.inn.loopStart + max(0.0, inTimes[it] - clock.incomingHoldSec) },
                    GridSource.RATE_ONE, GridSource.RATE_ONE, sampleRate,
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
            val trackedOut = PlaybackGeometryQualification.trackedBeatsInWindow(outSide, plan.out.loopStart, beats, plan.out.beatSec)
            val inCount = ceil((beats - progress(clock.incomingHoldSec)) * plan.fold).toInt().coerceAtLeast(1)
            val trackedIn = PlaybackGeometryQualification.trackedBeatsInWindow(inSide, plan.inn.loopStart, inCount, plan.inn.beatSec)
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
            return DeckKnots(
                DoubleArray(outTimes.size) { outTimes[it] * sr }, op,
                DoubleArray(inTimes.size) { inTimes[it] * sr }, ip,
                if (useOut) GridSource.TRACKED else GridSource.CLOCK,
                if (useIn) GridSource.TRACKED else GridSource.CLOCK,
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
            return DeckKnots(
                DoubleArray(outTimes.size) { outTimes[it] * sr },
                DoubleArray(outTimes.size) { plan.out.loopStart + outTimes[it] },
                DoubleArray(inTimes.size) { inTimes[it] * sr },
                DoubleArray(inTimes.size) { h.inCueSourceSec + max(0.0, inTimes[it] - h.inHoldMixSec) },
                GridSource.NATIVE_CLOCK, GridSource.NATIVE_CLOCK,
            )
        }

        val outFrames = DoubleArray(beats + 1 + if (outHasTailKnot) 1 else 0) { i ->
            if (i < beats + 1) clock.beatTimes[i] * sr else outStopAtSec * sr
        }
        val outSideBeats = outAnalysis?.tailBeats?.beats?.takeIf { it.isNotEmpty() }
            ?: outAnalysis?.headBeats?.beats
        val outLoopTracked = if (plan.outLoops && plan.out.beats > 0) {
            PlaybackGeometryQualification.trackedBeatsInWindow(outSideBeats, plan.out.loopStart, plan.out.beats, plan.out.beatSec)
        } else null
        val outStraightTracked = if (!plan.outLoops) {
            PlaybackGeometryQualification.trackedBeatsInWindow(outSideBeats, plan.out.loopStart, beats, plan.out.beatSec)
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
                PlaybackGeometryQualification.trackedBeatsInWindow(inSideBeats, plan.inn.loopStart, expectedInCount, plan.inn.beatSec)
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
                    val ease = 0.5 * (1.0 - cos(PI * u))
                    val rateK = endRate + (1.0 - endRate) * ease
                    inPositionsSec[k] = inPositionsSec[k - 1] + dt * rateK
                }
            }
        }

        return DeckKnots(
            outFrames, outPositionsSec, inFrames, inPositionsSec,
            outGridSource = if (outLoopTracked != null || outStraightTracked != null) GridSource.TRACKED else GridSource.CLOCK,
            inGridSource = inGridSource,
        )
    }
}
