package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A source event and the instant at which the renderer must play it. */
data class HandoffPoint(val sourceSec: Double, val mixSec: Double)

data class MusicalHandoff(
    val outCaptureStart: HandoffPoint,
    val outSendClose: HandoffPoint,
    val outDryFallStart: HandoffPoint,
    val outDryEnd: HandoffPoint,
    val inCueSourceSec: Double,
    val inHoldMixSec: Double,
    val inAudibleStart: HandoffPoint,
    val inFullLevelMixSec: Double,
    val inLanding: HandoffPoint,
    val wetRetireMixSec: Double,
)

enum class HandoffStatus { QUALIFIED, UNKNOWN, UNSAFE }

data class HandoffQualification(
    val status: HandoffStatus,
    val reason: String?,
    val outCutMaskCoverage: Double?,
    val inEntryMaskCoverage: Double?,
    val overlapMaskCoverage: Double?,
)

/** Native timing is unity speed on both decks. The cue is parked until hold. */
fun nativeTiming(
    outStartSourceSec: Double,
    outCaptureStartSourceSec: Double,
    outDryFallStartSourceSec: Double,
    outDryEndSourceSec: Double,
    inCueSourceSec: Double,
    inAudibleSourceSec: Double,
    inLandingSourceSec: Double,
    landingMixSec: Double,
    incomingOpenSec: Double,
    wetDrainSec: Double,
): MusicalHandoff? {
    val values = listOf(outStartSourceSec, outCaptureStartSourceSec, outDryFallStartSourceSec,
        outDryEndSourceSec, inCueSourceSec, inAudibleSourceSec, inLandingSourceSec,
        landingMixSec, incomingOpenSec, wetDrainSec)
    if (values.any { !it.isFinite() || it < 0.0 } ||
        outStartSourceSec > outCaptureStartSourceSec ||
        outCaptureStartSourceSec >= outDryFallStartSourceSec ||
        outDryFallStartSourceSec > outDryEndSourceSec ||
        inCueSourceSec > inAudibleSourceSec || inAudibleSourceSec > inLandingSourceSec
    ) return null
    val hold = landingMixSec - (inLandingSourceSec - inCueSourceSec)
    val audible = hold + inAudibleSourceSec - inCueSourceSec
    val full = audible + incomingOpenSec
    val dryEnd = outDryEndSourceSec - outStartSourceSec
    val retire = max(max(dryEnd, landingMixSec), full) + wetDrainSec
    if (!hold.isFinite() || hold < 0.0 || !audible.isFinite() || !full.isFinite() ||
        full > landingMixSec + 1e-9 || !retire.isFinite()
    ) return null
    fun out(source: Double) = HandoffPoint(source, source - outStartSourceSec)
    return MusicalHandoff(out(outCaptureStartSourceSec), out(outDryEndSourceSec),
        out(outDryFallStartSourceSec), out(outDryEndSourceSec), inCueSourceSec, hold,
        HandoffPoint(inAudibleSourceSec, audible), full,
        HandoffPoint(inLandingSourceSec, landingMixSec), retire)
}

/**
 * Qualifies the intervals that will actually be heard, not a setup point.
 * Singing in the captured phrase is allowed; removing a certain word is not.
 * Missing, partial or non-finite model frames never count as clean evidence.
 */
fun qualifyMusicalHandoff(
    handoff: MusicalHandoff,
    outAnalysis: TrackAnalysis?,
    inAnalysis: TrackAnalysis?,
    sungOut: List<LoopMix.Span>?,
    sungIn: List<LoopMix.Span>?,
): HandoffQualification {
    val h = handoff
    fun result(status: HandoffStatus, reason: String?, out: Double? = null,
        inn: Double? = null, overlap: Double? = null) =
        HandoffQualification(status, reason, out, inn, overlap)
    val start = h.outCaptureStart.sourceSec - h.outCaptureStart.mixSec
    val rebuilt = nativeTiming(start, h.outCaptureStart.sourceSec, h.outDryFallStart.sourceSec,
        h.outDryEnd.sourceSec, h.inCueSourceSec, h.inAudibleStart.sourceSec,
        h.inLanding.sourceSec, h.inLanding.mixSec, h.inFullLevelMixSec - h.inAudibleStart.mixSec,
        h.wetRetireMixSec - max(max(h.outDryEnd.mixSec, h.inLanding.mixSec), h.inFullLevelMixSec))
    fun same(a: Double, b: Double) = a.isFinite() && b.isFinite() && abs(a - b) <= 1e-8
    fun samePoint(a: HandoffPoint, b: HandoffPoint) = same(a.sourceSec, b.sourceSec) && same(a.mixSec, b.mixSec)
    if (rebuilt == null || !samePoint(h.outSendClose, rebuilt.outSendClose) ||
        !samePoint(h.outDryFallStart, rebuilt.outDryFallStart) || !samePoint(h.outDryEnd, rebuilt.outDryEnd) ||
        !same(h.inHoldMixSec, rebuilt.inHoldMixSec) || !samePoint(h.inAudibleStart, rebuilt.inAudibleStart)
    ) return result(HandoffStatus.UNSAFE, "invalid-handoff-geometry")
    if ((outAnalysis?.duration?.takeIf { it > 0.0 }?.let { h.outDryEnd.sourceSec > it + 1e-8 } == true) ||
        (inAnalysis?.duration?.takeIf { it > 0.0 }?.let { h.inLanding.sourceSec > it + 1e-8 } == true)
    ) return result(HandoffStatus.UNSAFE, "handoff-outside-track")

    // A zero-width cut at the physical end consumes every source event. This
    // deliberate fallback does not certify the unmeasured mask as clean.
    val naturalEnd = outAnalysis != null && outAnalysis.duration > 0.0 &&
        same(h.outDryFallStart.sourceSec, outAnalysis.duration) && same(h.outDryEnd.sourceSec, outAnalysis.duration)
    val cutFrom = max(0.0, h.outDryFallStart.sourceSec - 0.08)
    val cutTo = min(h.outDryEnd.sourceSec + 0.08,
        outAnalysis?.duration?.takeIf { it > 0.0 } ?: (h.outDryEnd.sourceSec + 0.08))
    if (!naturalEnd && sungOut.orEmpty().any { it.start < cutTo && it.certainUntil > cutFrom }) {
        return result(HandoffStatus.UNSAFE, "out-cut-certain-lyric")
    }
    data class Evidence(val coverage: Double?, val active: List<Pair<Double, Double>>)
    fun evidence(a: TrackAnalysis?, from: Double, to: Double): Evidence {
        if (to <= from) return Evidence(1.0, emptyList())
        val spans = ArrayList<Pair<Double, Double>>()
        val active = ArrayList<Pair<Double, Double>>()
        for (w in a?.vocalWindows.orEmpty()) {
            if (!w.startTime.isFinite() || !w.frameRate.isFinite() || w.frameRate <= 0.0) continue
            for (i in w.values.indices) {
                val v = w.values[i]
                if (!v.isFinite() || v < 0f || v > 1f) continue
                val left = max(from, w.startTime + i / w.frameRate)
                val right = min(to, w.startTime + (i + 1) / w.frameRate)
                if (right <= left) continue
                spans += left to right
                if (v > VOCAL_ACTIVE_THRESHOLD) active += left to right
            }
        }
        if (spans.isEmpty()) return Evidence(null, active)
        var end = from
        var covered = 0.0
        for ((left, right) in spans.sortedBy { it.first }) {
            covered += max(0.0, right - max(left, end))
            end = max(end, right)
        }
        return Evidence((covered / (to - from)).coerceIn(0.0, 1.0), active)
    }
    val capture = evidence(outAnalysis, h.outCaptureStart.sourceSec, h.outDryEnd.sourceSec)
    val cut = evidence(outAnalysis, cutFrom, cutTo)
    val entryEnd = max(h.inLanding.sourceSec, h.inAudibleStart.sourceSec + 0.25)
    val entry = evidence(inAnalysis, h.inAudibleStart.sourceSec, entryEnd)
    val overlapFrom = h.inAudibleStart.mixSec
    val overlapTo = if (h.outDryEnd.mixSec <= overlapFrom + 1e-8) overlapFrom else h.outDryEnd.mixSec
    val overlapOut = evidence(outAnalysis, start + overlapFrom, start + overlapTo)
    val overlapIn = evidence(inAnalysis, h.inAudibleStart.sourceSec,
        h.inAudibleStart.sourceSec + max(0.0, overlapTo - overlapFrom))
    val outCoverage = if (cut.coverage == null || capture.coverage == null) null
        else min(cut.coverage, capture.coverage)
    val overlapCoverage = if (overlapOut.coverage == null || overlapIn.coverage == null) null
        else min(overlapOut.coverage, overlapIn.coverage)
    fun verdict(status: HandoffStatus, reason: String?) = result(status, reason,
        outCoverage, entry.coverage, overlapCoverage)
    if (!naturalEnd && cut.active.isNotEmpty()) return verdict(HandoffStatus.UNSAFE, "out-cut-measured-vocal")
    val openingSourceEnd = h.inAudibleStart.sourceSec + h.inFullLevelMixSec - h.inAudibleStart.mixSec
    if (h.inFullLevelMixSec > h.inAudibleStart.mixSec + 1e-8) {
        val markedAttack = listOfNotNull(inAnalysis?.audibleStartTime, inAnalysis?.pickupTime)
            .any { it.isFinite() && it >= h.inAudibleStart.sourceSec - 1e-8 && it < openingSourceEnd }
        val sungOpening = sungIn.orEmpty().any {
            it.start < openingSourceEnd && it.certainUntil > h.inAudibleStart.sourceSec
        }
        val measuredOpening = evidence(inAnalysis, h.inAudibleStart.sourceSec, openingSourceEnd).active.isNotEmpty()
        if (markedAttack || sungOpening || measuredOpening) {
            return verdict(HandoffStatus.UNSAFE, "in-entry-protected-attack")
        }
    }
    // Certainty from lyrics is an additional veto, never a substitute for coverage.
    fun activeMix(e: Evidence, lyrics: List<LoopMix.Span>?, sourceAtMixZero: Double): List<Pair<Double, Double>> =
        (e.active + lyrics.orEmpty().map { it.start to it.certainUntil }).mapNotNull { (left, right) ->
            val a = max(overlapFrom, left - sourceAtMixZero)
            val b = min(overlapTo, right - sourceAtMixZero)
            if (a < b) a to b else null
        }
    val activeOut = activeMix(overlapOut, sungOut, start)
    val activeIn = activeMix(overlapIn, sungIn, h.inAudibleStart.sourceSec - overlapFrom)
    if (activeOut.any { a -> activeIn.any { b -> max(a.first, b.first) < min(a.second, b.second) - 1e-9 } }) {
        return verdict(HandoffStatus.UNSAFE, "handoff-dual-vocal")
    }
    if (outCoverage == null || outCoverage < 1.0 - 1e-8 ||
        entry.coverage == null || entry.coverage < 1.0 - 1e-8 ||
        overlapCoverage == null || overlapCoverage < 1.0 - 1e-8
    ) return verdict(HandoffStatus.UNKNOWN, "handoff-mask-coverage")
    return verdict(HandoffStatus.QUALIFIED, null)
}
