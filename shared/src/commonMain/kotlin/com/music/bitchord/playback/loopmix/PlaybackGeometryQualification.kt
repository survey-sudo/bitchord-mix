package com.music.bitchord.playback.loopmix

import com.music.bitchord.playback.djmix.StraightRateMode
import com.music.bitchord.playback.smart.HandoffStatus
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.RegionalLoudness
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.qualifyMusicalHandoff
import kotlin.math.abs
import kotlin.math.max

/** Pure qualification, source-coordinate selection, and decoded PCM coverage. */
object PlaybackGeometryQualification {
    /** One regional, peak-safe gain shared by canonical geometry and native IN setup. */
    fun regionalIncomingLevel(
        plan: LoopMix.Plan, out: LoopRegion, inn: LoopRegion, sampleRate: Int, fallback: Float,
    ): Float {
        fun sample(region: LoopRegion, seconds: Double) = ((seconds - region.start) * sampleRate).toInt()
        val outEndSec = if (plan.outLoops) plan.out.loopEnd
            else plan.out.loopStart + plan.totalBars * 4 * plan.out.beatSec
        val inEndSec = plan.inn.loopStart + 8.0 * plan.inn.beatSec
        val matched = RegionalLoudness.matchGain(
            outLeft = out.left, outRight = out.right,
            outFrom = sample(out, plan.out.loopStart), outTo = sample(out, outEndSec),
            inLeft = inn.left, inRight = inn.right,
            inFrom = sample(inn, plan.inn.loopStart), inTo = sample(inn, inEndSec),
            sampleRate = sampleRate,
        )
        val rawGain = (matched ?: fallback).coerceIn(0.501f, 1.679f)
        var inPeak = 0f
        for (v in inn.left) { val a = abs(v); if (a > inPeak) inPeak = a }
        for (v in inn.right) { val a = abs(v); if (a > inPeak) inPeak = a }
        return if (rawGain > 1.0f && inPeak > 1e-4f) {
            minOf(rawGain, 0.92f / inPeak).coerceIn(0.501f, 1.679f)
        } else rawGain
    }

    /** Interpolate the exact arrays supplied to native, in source seconds. */
    fun knotSourceAt(frames: DoubleArray, positions: DoubleArray, mixSec: Double, sampleRate: Int): Double? {
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
    fun qualifyHandoffKnots(
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        sampleRate: Int,
        knots: DeckKnots,
    ): String? {
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

    /** Verifies decoded OUT and IN buffers cover the handoff contract bounds. */
    fun coversHandoffPcm(
        plan: LoopMix.Plan,
        sampleRate: Int,
        knots: DeckKnots,
        out: LoopRegion,
        inn: LoopRegion,
    ): Boolean {
        val h = plan.handoff ?: return true
        val dryEnd = knotSourceAt(knots.outFrames, knots.outPositionsSec, h.outDryEnd.mixSec, sampleRate) ?: return false
        val entry = knotSourceAt(knots.inFrames, knots.inPositionsSec, h.inAudibleStart.mixSec, sampleRate) ?: return false
        val landing = knotSourceAt(knots.inFrames, knots.inPositionsSec, h.inLanding.mixSec, sampleRate) ?: return false
        // OUT may exhaust after dry end, while the effect continues to drain.
        return coversPhysical(out, sampleRate, h.outCaptureStart.sourceSec, dryEnd) &&
            coversPhysical(inn, sampleRate, minOf(h.inCueSourceSec, entry), landing + 1.0 / sampleRate)
    }

    /**
     * Qualify the actual buildDeckKnots output, in unwrapped track seconds,
     * before PCM/JNI. This deliberately does not repair or clamp a knot.
     * Native prepare() finds the first position > 0.5 sample from p0 and
     * stays silent up to the preceding knot (renderer.cpp:673-689, 778-785).
     * Only a declared IN hold at p0 may use that initial activation contract;
     * after the first advance, neither deck may park or move backwards.
     */
    fun qualifyCrownKnots(
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
        return CrownKnotQualification(
            reason == null, reason,
            out.minRate, out.maxRate, inn.minRate, inn.maxRate,
        )
    }

    /** Extracts a contiguous tracked-beat array starting near [anchorStart] when coherent. */
    fun trackedBeatsInWindow(
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

    /** Coordinates here remain track seconds; native subtracts region.start. */
    fun incomingGeometry(
        plan: LoopMix.Plan,
        clock: LoopMix.Clock,
        knots: DeckKnots,
    ): IncomingGeometry {
        val handoff = plan.handoff
        val release = handoff?.let {
            it.inCueSourceSec + max(0.0, clock.releaseAt - it.inHoldMixSec)
        } ?: if (knots.sampleRate > 0) {
            requireNotNull(knotSourceAt(knots.inFrames, knots.inPositionsSec, clock.releaseAt, knots.sampleRate))
        } else knots.inPositionsSec[clock.beats]
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

    /** True when decoded stereo PCM [region] covers `[requiredStart, requiredEnd]` within 1 sample. */
    fun coversPhysical(region: LoopRegion, sampleRate: Int, requiredStart: Double, requiredEnd: Double): Boolean =
        sampleRate > 0 && region.start.isFinite() && requiredStart.isFinite() && requiredEnd.isFinite() &&
            region.left.isNotEmpty() && region.left.size == region.right.size && region.start <= requiredStart &&
            region.start + region.left.size.toDouble() / sampleRate + 1.0 / sampleRate >= requiredEnd

    /** OUT loop completeness only; straight OUT may reach real EOF during FX drain. */
    fun coversOutgoingStartAndLoop(region: LoopRegion, sampleRate: Int, plan: LoopMix.Plan): Boolean =
        coversPhysical(region, sampleRate, plan.out.loopStart,
            if (plan.outLoops) plan.out.loopEnd + 0.008 else plan.out.loopStart) &&
            region.start + region.left.size.toDouble() / sampleRate > plan.out.loopStart

    /** Exact build request, including actual tracked/straight knots and handover tail. */
    fun decodeRequirements(
        plan: LoopMix.Plan, sampleRate: Int, outAnalysis: TrackAnalysis?, inAnalysis: TrackAnalysis?,
        straightRateMode: StraightRateMode? = null, level: DjLevel,
    ): LoopMix.RegionBounds {
        val clock = LoopMix.Clock(plan)
        val nominal = LoopMix.regionBounds(plan)
        val stop = clock.releaseAt + 0.05 + (if (plan.echoTail) plan.out.beatSec * 8 else 0.0) + plan.exitTailSeconds
        val tailRate = if (clock.beats >= 1) plan.out.beatSec /
            (clock.beatTimes[clock.beats] - clock.beatTimes[clock.beats - 1]).coerceAtLeast(1e-6) else 1.0
        val knots = PlaybackDeckGeometry.buildDeckKnots(plan, clock, sampleRate, outAnalysis, inAnalysis,
            !plan.outLoops && stop > clock.releaseAt + 1e-6, stop, tailRate, straightRateMode, level)
        val geometry = incomingGeometry(plan, clock, knots)
        return LoopMix.RegionBounds(
            maxOf(0.0, nominal.outFrom),
            if (straightRateMode != null) knots.outPositionsSec.maxOrNull()!! + 0.5 else nominal.outTo,
            maxOf(0.0, nominal.inFrom),
            maxOf(nominal.inTo, geometry.handoverTrack + nominal.inTo - clock.elementTrackTime, geometry.requiredEnd),
        )
    }

    /** Check prepared PCM after the caller has computed the exact decode bounds. */
    fun preparedCoverageRefusal(
        plan: LoopMix.Plan, sampleRate: Int, out: LoopRegion, inn: LoopRegion,
        outAnalysis: TrackAnalysis?, bounds: LoopMix.RegionBounds,
    ): String? {
        val eof = outAnalysis?.duration?.takeIf { it.isFinite() && it > plan.out.loopStart }
        val outEnd = if (!plan.outLoops && eof != null) minOf(bounds.outTo, eof) else bounds.outTo
        if (!coversPhysical(out, sampleRate, bounds.outFrom, outEnd) ||
            !coversOutgoingStartAndLoop(out, sampleRate, plan)) return "prepared_out_pcm_incomplete"
        if (!coversPhysical(inn, sampleRate, bounds.inFrom, bounds.inTo)) return "prepared_in_pcm_incomplete"
        return null
    }
}
