package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Late-scrub catch-up re-anchoring (djmix14). When the playhead has already
 * crossed [TransitionPlan.transitionStart] (scrubbed near EOF), the planner
 * would otherwise show a dead 0s countdown whose start is 15-20 s in the
 * past and never fire. This pure planner re-anchors a tight, beat-snapped
 * DJ move just ahead of the playhead so the scrub still mixes musically
 * instead of playing dry to EOF.
 *
 * Scalars only: BPM, downbeats, content-end and duration. No audio crosses.
 */
object CatchUpPlanner {
    /**
     * Re-anchors [plan] ahead of [currentPositionSec], or null when no
     * catch-up applies (not late, blocked, or too little left to mix).
     *
     * Lead is 0.9-1.5 s snapped to the next beat/downbeat after
     * current+0.8 s when a grid exists (0.6 s when only 2.0-2.5 s remain,
     * preserving room for a quick dissolve). Overlap shape:
     * - >=7.0 s and tempo delta <=8%: 4-bar DJ_BLEND (bass swap + mid carve)
     * - >=3.5 s or tempo >8%: 2-bar DJ_FILTER/ECHO_RIDE (filter + echo tail)
     * - 1.2-3.5 s (djmix15, was 1.6): 1-bar quick blend/filter at full
     *   available overlap — 0.6 s lead + 1.2-1.6 s DJ filter dissolve where
     *   a 2.5 s floor would have played dry to EOF.
     *
     * Below 2.0 s of effective room (contentEnd-aware) there is no lead +
     * dissolve that can breathe: return null so the planner blocks
     * ("too-late-near-eof") instead of firing a stale micro-fade.
     */
    fun reanchor(
        plan: TransitionPlan,
        outAnalysis: TrackAnalysis,
        inAnalysis: TrackAnalysis,
        currentPositionSec: Double,
        durationSec: Double,
    ): TransitionPlan? {
        if (plan.blocked) return null
        if (durationSec <= 0.0 || !durationSec.isFinite() || !currentPositionSec.isFinite()) return null
        if (currentPositionSec <= plan.transitionStart + 0.25) return null
        if (currentPositionSec >= durationSec) return null

        val contentEnd = outAnalysis.contentEndTime
        val rawEnd = if (contentEnd.isFinite() && contentEnd > currentPositionSec + 2.0 && contentEnd > 0.0) {
            contentEnd
        } else {
            durationSec
        }
        val effectiveEndSec = rawEnd.coerceAtMost(durationSec)
        val remainingSec = effectiveEndSec - currentPositionSec
        if (remainingSec < 2.0) return null

        val outBpm = outAnalysis.bpm
        val inBpm = inAnalysis.bpm
        val bpmForBar = if (outBpm > 0) outBpm else if (inBpm > 0) inBpm else 120.0
        val beatSec = if (bpmForBar > 0) 60.0 / bpmForBar else 0.5
        val barSec = if (bpmForBar > 0) 4.0 * beatSec else 2.0

        // Near-EOF quick blend: a snapped 0.9-1.5 s lead would leave <1.2 s
        // to dissolve in. A fixed 0.6 s lead preserves 1.2-1.6 s of it.
        val snapped = snappedLeadSec(outAnalysis, currentPositionSec)
        val leadSec = if (remainingSec < 2.5) 0.6 else snapped
        val catchUpStartSec = currentPositionSec + leadSec
        // Guard-band 0.4 s keeps the tail off the file edge.
        var availableOverlapSec = (effectiveEndSec - catchUpStartSec - 0.4).coerceAtLeast(1.2)
        // Never let the overlap run past the track end.
        val roomLeft = effectiveEndSec - catchUpStartSec
        if (roomLeft < 1.2) return null
        if (availableOverlapSec > roomLeft) availableOverlapSec = roomLeft
        if (availableOverlapSec < 1.2) return null

        val tempoDelta = if (outBpm > 0 && inBpm > 0) {
            val fold = alignedFold(outBpm, inBpm)
            val alignedIn = inBpm / fold
            abs(alignedIn / outBpm - 1.0)
        } else {
            0.0
        }

        val (fadeSec, style) = when {
            availableOverlapSec >= 7.0 && tempoDelta <= 0.08 -> {
                val f = minOf(plan.fadeSeconds, availableOverlapSec, 4.0 * barSec)
                // At least 2 bars when we claimed a 4-bar blend; shorter only if the room is short.
                val clamped = f.coerceIn(minOf(4.0, availableOverlapSec), availableOverlapSec)
                clamped to TransitionStyle.DJ_BLEND
            }
            availableOverlapSec >= 3.5 -> {
                // 2-bar filter/echo ride, clamped to the club 3.5-8.0 s window.
                val f = minOf(availableOverlapSec, 2.0 * barSec).coerceIn(3.5, 8.0)
                // If the room is shorter than 3.5, take the full room (quick blend path).
                val finalF = if (f > availableOverlapSec) availableOverlapSec else f
                finalF to TransitionStyle.DJ_FILTER
            }
            else -> {
                // 1-bar beat-snapped quick blend: full available overlap.
                availableOverlapSec to TransitionStyle.DJ_FILTER
            }
        }
        val endSec = catchUpStartSec + fadeSec
        if (endSec > durationSec + 1e-6) return null
        if (fadeSec < 1.2 || !fadeSec.isFinite()) return null
        // Keep the original blend character where it fits; only the timing moves.
        val baseReason = plan.reason.ifBlank { "smart-duration" }
        return plan.copy(
            shouldStart = false,
            markerVisible = true,
            blocked = false,
            transitionStart = catchUpStartSec,
            transitionEnd = endSec,
            fadeSeconds = fadeSec,
            transitionStyle = style,
            reason = if (baseReason.endsWith(":catch-up")) baseReason else baseReason + ":catch-up",
            beatSeconds = if (plan.beatSeconds > 0) plan.beatSeconds else beatSec,
        )
    }

    /** Lead 0.9-1.5 s snapped to the next downbeat/beat after current+0.8 s. */
    private fun snappedLeadSec(analysis: TrackAnalysis, currentSec: Double): Double {
        val target = currentSec + 0.8
        val maxLead = currentSec + 1.5
        // Prefer explicit downbeats (both sides).
        val grids = listOfNotNull(
            analysis.downbeats.takeIf { it.isNotEmpty() },
            analysis.headBeats?.downbeats?.takeIf { it.isNotEmpty() },
            analysis.headBeats?.beats?.takeIf { it.isNotEmpty() },
            analysis.tailBeats?.downbeats?.takeIf { it.isNotEmpty() },
            analysis.tailBeats?.beats?.takeIf { it.isNotEmpty() },
        )
        for (grid in grids) {
            val next = grid.firstOrNull { it.isFinite() && it >= target && it <= maxLead }
            if (next != null) {
                val lead = next - currentSec
                if (lead in 0.9..1.5) return lead
            }
        }
        // Synthesize from BPM + phase (firstBeat or first grid beat).
        val bpm = analysis.bpm
        if (bpm > 0) {
            val beatSec = 60.0 / bpm
            if (beatSec > 0.1) {
                val anchor = when {
                    analysis.firstBeat.isFinite() && analysis.firstBeat > 0.0 -> analysis.firstBeat
                    analysis.downbeats.isNotEmpty() -> analysis.downbeats.firstOrNull { it.isFinite() } ?: 0.0
                    analysis.headBeats?.beats?.isNotEmpty() == true -> analysis.headBeats.beats.firstOrNull { it.isFinite() } ?: 0.0
                    else -> 0.0
                }
                // Next beat >= target on the anchor+ k*beat lattice.
                val k = kotlin.math.ceil((target - anchor) / beatSec)
                val snapped = anchor + k.coerceAtLeast(0.0) * beatSec
                val lead = snapped - currentSec
                if (lead in 0.9..1.5) return lead
            }
        }
        return 1.0
    }
}

/**
 * djmix16 catch-up pin (pure, scalars only). Any :catch-up plan for a
 * (pair, scrubEpoch) is pinned ONCE and reused on every subsequent IDLE
 * tick for that key — never recomputed as playbackTime + lead, which
 * pushed transitionStart forward every tick (fadeSec 4.59 -> 3.98 ->
 * 2.26 -> 1.2 on Never Let Go -> S.A.D) and kept `remaining` at +900 ms
 * so pos never caught the start. The caller drops the pin on pair or
 * scrubEpoch change; the pin otherwise holds until the mix arms or any
 * valid non-catch-up plan arrives (djmix17).
 */
object CatchUpPins {
    /** Key for a pair at a scrub epoch. */
    fun key(pairKey: String, scrubEpoch: Long): String = "$pairKey|$scrubEpoch"

    /**
     * Pinned plan when it matches [currentKey], else null (store [currentPlan]).
     * The pin freezes transitionStart/fade/style by design, but
     * [TransitionPlan.shouldStart] must still track the playhead: a plan
     * pinned at pos < start (shouldStart=false) flips to true once
     * [currentPositionMs] reaches the pinned start. Without this the pinned
     * plan reads never-started forever even though remaining <= 0 and
     * classic begin() fires on the remaining gate.
     */
    fun reuse(
        pinned: TransitionPlan?,
        pinnedKey: String?,
        currentKey: String,
        currentPositionMs: Long? = null,
    ): TransitionPlan? {
        if (pinned == null || pinnedKey != currentKey) return null
        if (currentPositionMs == null) return pinned
        val startMs = (pinned.transitionStart * 1000).roundToLong()
        if (currentPositionMs >= startMs) {
            return if (pinned.shouldStart) pinned else pinned.copy(shouldStart = true)
        }
        return pinned
    }

    /**
     * True when a valid non-catch-up plan drops the pin. ANY such plan
     * drops it — new pair, or the same pair after an interior -> final
     * anchor flip or a backward seek. While the playhead is past the
     * anchor, planTransition ALWAYS returns :catch-up, so a same-pair
     * non-catch-up plan can only mean transitionStart moved AHEAD of
     * playbackTime; keeping the old pin would resurrect a stale
     * interior-anchor catch-up when the new anchor is later crossed
     * (Never Let Go: seq368 final anchor 181.6 s non-catch-up, then
     * seq369 reused the stale 164.6 s interior pin).
     */
    fun dropOnNonCatchUp(pinnedKey: String?, pairKey: String): Boolean =
        pinnedKey != null
}
