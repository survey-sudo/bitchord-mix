/*
 * BitChord: the Automix loop mix, a DJ transition on two beat-tracked songs.
 * Ported from the web player's playback/automix/engine/loopMix.ts (which
 * builds on AutoMix by David Finnegan, AGPL-3.0; see that folder's NOTICE.md).
 *
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version. Combined into BitChord (GPL-3.0-or-later) under
 * section 13 of the AGPL.
 */

package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The loop mix: the outgoing song hands over to a deck at the start of an
 * instrumental loop near its end and loops it. The incoming song comes in on
 * its own instrumental loop, bass cut, at the outgoing tempo. One master tempo
 * then ramps from the outgoing song's BPM to the incoming one's, both decks
 * following it beat by beat, so they stay locked however far apart they
 * started. The bass swaps, the outgoing loop drains away through a rising
 * high-pass, the incoming loop lets go into the rest of its song at its own
 * tempo, and its player takes over two bars later.
 *
 * Everything is placed on one beat clock: beat k of the mix (in outgoing
 * beats) is at [Clock.beatTimes]`[k]` seconds after t0, and each deck's
 * position at beat k is worked out from the loop it is in, so nothing drifts.
 *
 * Pure: no audio, no players. [plan] picks the loops from stored analysis;
 * [Clock] lays the plan out in time; the renderer and the controller both read
 * the clock.
 */
object LoopMix {
    /** The longest loop, in each song's own bars; shorter ones are tried when no 4 clean bars exist. */
    const val LOOP_BARS = 4

    /** djmix24 EDM buildup range: steady dance tempo (BPM, post-fold). */
    const val EDM_MIN_BPM = 110.0
    const val EDM_MAX_BPM = 150.0
    /** djmix24 EDM buildup HPF sweep end (Hz) + Q for the resonant Cytomic SVF. */
    const val BUILDUP_HP_END_HZ = 500f
    const val BUILDUP_HP_Q = 0.95f
    /** djmix24 flanger depths: buildup (metallic jet) vs subtle colour. */
    const val FLANGER_DEPTH_BUILDUP_SEC = 0.0045
    const val FLANGER_DEPTH_COLOUR_SEC = 0.003
    /** djmix24 jet flanger wet (energy-balanced) + feedback. */
    const val FLANGER_WET = 0.48f
    const val FLANGER_FEEDBACK = 0.64f

    /** Loop lengths tried, longest first: a 1-bar instrumental loop beats a 4-bar one with a word in it. */
    private val LOOP_LENGTHS = intArrayOf(4, 2, 1)

    /** Bars the incoming loop takes to come in at the outgoing tempo. */
    const val ENTER_BARS = 4

    /** Bars the outgoing loop takes to drain away once the tempo has arrived. */
    const val EXIT_BARS = 2

    /** Bars after the incoming loop lets go before its own player takes over. */
    const val SETTLE_BARS = 2

    /** The fastest the master tempo moves: past this a ramp is heard as a lurch, not a mix. */
    const val MAX_BPM_PER_BAR = 2.5
    const val MIN_RAMP_BARS = 2
    const val MAX_RAMP_BARS = 24

    /** Existing native loop_mix_renderer.cpp advancing-rate processing limits; not musical preferences. */
    const val CROWN_MIN_EXECUTION_RATE: Double = 0.75
    const val CROWN_MAX_EXECUTION_RATE: Double = 1.35

    /** Share of sung mask frames a loop may still have (a breath), and the longest sung run it may hold. */
    const val MAX_SUNG_SHARE = 0.03
    const val MAX_SUNG_RUN_SEC = 0.2

    /** A single vocal-mask frame at or above this is a sung note, whatever the average says. */
    const val MAX_SUNG_PEAK = 0.22

    /** The vocal windows must cover this share of a loop (and the beat before it), or it is not vouched for. */
    const val MIN_MASK_COVERAGE = 0.8

    /** Clear time before a loop from lyrics: a line still ending as the loop starts would be cut and repeated. */
    const val LYRIC_GUARD_SEC = 0.3

    /** A synced line is padded this much at its start (lead-in breath) and its end (the voice's tail and reverb). */
    const val LYRIC_LEAD_SEC = 0.15
    const val LYRIC_TAIL_SEC = 0.35

    /**
     * A line with no stated end is taken as sung until the next line, but a
     * gap of this long or more is not bridged: the voice is not still going
     * a bar later just because the next timestamp is.
     */
    const val LYRIC_OPEN_LINE_SEC = 1.5

    /**
     * Of a line with no stated end, how much is surely sung: its opening words.
     * Past this the line may already be over (line-synced lyrics only time each
     * line's start), so a loop there is cleared by the vocal mask instead.
     */
    const val OPEN_LINE_CERTAIN_SEC = 2.0

    /** A loop quieter than this share of the stretch around it is a breakdown or a fade, not a groove. */
    const val MIN_LOUDNESS = 0.6

    /** An RMS this low is silence, whatever the stretch around it does. */
    const val SILENT_RMS = 0.01

    /** How far from the end the outgoing loop is looked for, and from the start the incoming one. */
    const val OUT_SEARCH_SEC = 60.0
    const val IN_SEARCH_SEC = 45.0

    /** Just past a missed loop start, so the shift cannot select that same bar again. */
    private const val SHIFT_EPSILON_SEC = 1e-3

    /** Element <-> deck handovers: identical audio crossed this quickly is heard as one. */
    const val HANDOVER_SEC = 0.025
    /** djmix24: 8-beat LFO — one sweep cycle per 8 beats (Bug A2). NEVER 60/beatSec/8. */
    fun lfoHzForBeatSec(beatSec: Double): Double =
        if (beatSec > 1e-6 && beatSec.isFinite()) 1.0 / (8.0 * beatSec) else 0.0

    /** djmix24: strict 25 ms phase gate (scalar ms). */
    const val PHASE_GATE_MS = 25.0
    fun phaseGateOk(phaseErrorMs: Double): Boolean = abs(phaseErrorMs) <= PHASE_GATE_MS + 1e-9

    /** djmix24 deterministic EDM detection (scalars only, patent-safe). */
    fun isEdmPair(outBpm: Double, inBpm: Double, fold: Double): Boolean {
        if (outBpm !in EDM_MIN_BPM..EDM_MAX_BPM || inBpm !in EDM_MIN_BPM..EDM_MAX_BPM) return false
        if (fold <= 0.0 || !fold.isFinite()) return false
        val foldedIn = inBpm / fold
        if (foldedIn <= 1.0) return false
        val delta = abs(foldedIn / outBpm - 1.0)
        return delta <= PhraseConfig.TEMPO_HARD_DELTA + 1e-9
    }

    /**
     * djmix24 deterministic pair-hash FX rotation for non-EDM pairs.
     * Scalars only (hash of the two track ids). Never returns FLANGER when
     * dual-vocal overlap is active.
     * 0=FLANGER, 1=CLEAN_SWAP, 2=ECHO, 3=REVERB.
     */
    fun fxSlotForPair(outId: String, inId: String): Int = ((pairHash(outId, inId) % 4) + 4) % 4

    /** Beat-quantized tightening schedule: loop length in beats for [beatsIntoRoll] (0..total). */
    fun rollLoopBeats(beatsIntoRoll: Double, totalRollBeats: Double): Double {
        if (totalRollBeats <= 0.0) return 4.0
        val b = beatsIntoRoll.coerceIn(0.0, totalRollBeats)
        // Final 2 beats carry 1 -> 0.5; before that 4 -> 2.
        return when {
            b >= totalRollBeats - 1.0 -> 0.5
            b >= totalRollBeats - 2.0 -> 1.0
            b >= totalRollBeats - 4.0 -> 2.0
            else -> 4.0
        }
    }
    const val BASS_CUT_DB = -24.0
    const val HIGH_PASS_FROM_HZ = 20.0
    const val HIGH_PASS_TO_HZ = 1500.0

    /** What said a loop is instrumental: the lyrics, the vocal mask, or the lyrics with the mask past an open line. */
    enum class Check { LYRICS, MASK, LYRICS_AND_MASK }

    /**
     * A sung stretch from the lyrics. [certainUntil] is how much of it the lyrics
     * actually vouch for: all of it for a line with a stated end, only its first
     * [OPEN_LINE_CERTAIN_SEC] for one without, whose end is a guess (the next
     * line). The guessed tail is left to the vocal mask.
     */
    data class Span(val start: Double, val end: Double, val certainUntil: Double = end)

    /**
     * One synced lyric line, as [sungSpans] reads it.
     * [wordEndMs] is the last word's own end when the line is word-synced.
     * A line [endMs] that merely points at the next line is the gap after
     * the vocal, not more singing.
     */
    data class Line(
        val timeMs: Long,
        val endMs: Long?,
        val blank: Boolean,
        val wordEndMs: Long? = null,
    )

    data class Pick(
        /** Track seconds. */
        val loopStart: Double,
        val loopEnd: Double,
        /** The loop's length in this song's own bars: 4, or 2 or 1 when no 4 clean bars were found. */
        val bars: Int,
        /** Track seconds per beat on this loop: its length over its beats, bar line to bar line. */
        val beatSec: Double,
        val bpm: Double,
        /** Share of the loop that is sung (0..1); 0 when the lyrics vouch for it. */
        val vocal: Double,
        val checkedBy: Check,
        /**
         * Track seconds of each beat in the loop (beats+1: from `loopStart` to
         * `loopEnd`), taken from this track's own `BeatSide.beats`, or null when
         * the grid does not cover the loop monotonically (straight picks, phrase
         * picks). Crown-loop knots anchor to these when valid and otherwise use
         * the synthetic `loopStart + k * beatSec` — never an elaborate new model.
         * Straight/hold/settle decks use LoopMixSession.buildDeckKnots instead.
         */
        val anchorBeats: List<Double>? = null,
    ) {
        val beats: Int get() = bars * 4
    }

    data class Plan(
        val out: Pick,
        val inn: Pick,
        /** Incoming beats per outgoing beat: 1, or 2 / 0.5 when double or half time is closer. */
        val fold: Double,
        val enterBars: Int,
        val rampBars: Int,
        val exitBars: Int,
        /** Outgoing bars from the outgoing loop's start to the incoming loop's release. */
        val totalBars: Int,
        /** The outgoing deck wraps its loop. Straight playback when false. */
        val outLoops: Boolean = true,
        /** The incoming deck wraps its loop. Straight playback when false. */
        val inLoops: Boolean = true,
        /**
         * The incoming deck stays at 1.000x for the whole bridge. A beat blend
         * leaves this false: the incoming song is stretched only while the
         * tempo is still moving, and is at 1.000x once the ramp has arrived.
         */
        val nativeIncoming: Boolean = false,
        /**
         * vc69 P0 muffle: the outgoing deck keeps its level while a steep
         * low-pass closes over it across the overlap. Native session
         * rendering only; the picks stay the declined pair echo picks.
         */
        val muffle: Boolean = false,
        /**
         * vc69 P0 beatless bridge: both decks fade through a brief ambient
         * moment on their beatless edges. Native session rendering only.
         */
        val beatlessBridge: Boolean = false,
        /**
         * Native drop-in arm: the incoming deck enters unfiltered at full
         * level on its kick-locked anchor while the outgoing cuts. Set
         * alongside [alternativeKind] so the session writer takes the
         * explicit unfiltered arm instead of falling into a generic branch.
         */
        val dropIn: Boolean = false,
        /** Which vc69 alternative family produced this native plan, or "" when none. */
        val alternativeKind: String = "",
        /** Tempo-synced echo on the outgoing deck, ringing on after its dry signal ends. */
        val echoTail: Boolean = false,
        /** Both songs are singing: low-pass the outgoing deck, high-pass the incoming one. */
        val carve: Boolean = false,
        /** Shared loop whose keys are far apart: a gentle low-pass and high-pass while it runs. */
        val clash: Boolean = false,
        /** What the status line calls this plan. */
        val rung: String = "Loop",
        /** Camelot codes, "8B→9B", when both keys are known. */
        val keys: String = "",
        /**
         * Graceful-exit dissolve (djmix11): seconds of equal-power fall the
         * outgoing deck takes past the release before silence. Compiled
         * phrase plans always carry it; legacy plans default to 0.
         */
        val exitTailSeconds: Double = 0.0,
        val targetBpmOverride: Double? = null,
        val incomingHoldSec: Double = 0.0,
        /** djmix24 stereo jet flanger on the outgoing tail (Bug A1 scalar FX carrier). */
        val flanger: Boolean = false,
        /** djmix24 beat-quantized tightening EDM buildup loop roll. */
        val buildupRoll: Boolean = false,
        /** djmix24 stereo plate reverb wash send. */
        val reverbWash: Boolean = false,
        /** djmix24 resonant HPF sweep end (Hz); 0 = no sweep. 420..600 Hz for buildup. */
        val hpSweepEndHz: Float = 0f,
        /** djmix25 P1.6: outgoing vocal rides over incoming instrumental intro. */
        val acapella: Boolean = false,
        /** djmix25 P1.12: bass-swap width in beats (MixGain.faderTemplate table). */
        val faderSwapBeats: Double = 1.0,
    ) {
        /** The tempo the ramp arrives at, in outgoing beats per minute. */
        val targetBpm: Double get() = targetBpmOverride ?: (inn.bpm / fold)
    }

    /** Nullable summaries are always finite; refusals are stable codes suitable for existing telemetry. */
    data class CrownQualification(
        val accepted: Boolean,
        val refusal: String?,
        val outMinRate: Double? = null,
        val outMaxRate: Double? = null,
        val inMinRate: Double? = null,
        val inMaxRate: Double? = null,
        val requiredRampBars: Int? = null,
    )

    /**
     * Execution feasibility of a genuine two-sided crown, not a whole-track musical verdict.
     * Local lyrics/mask/loudness remain the search's responsibility; actual emitted knot rates
     * remain the session's responsibility. This does not qualify or change lower-rung bridges.
     */
    fun qualifyCrown(plan: Plan): CrownQualification {
        fun refused(code: String) = CrownQualification(accepted = false, refusal = code)
        if (!plan.outLoops || !plan.inLoops) return refused("not-crown")

        // Only floating-point arithmetic precision (relative 1e-9), never another musical margin.
        val precision = 1e-9
        fun same(a: Double, b: Double): Boolean = a.isFinite() && b.isFinite() &&
            abs(a - b) <= precision * maxOf(abs(a), abs(b))
        fun validPick(pick: Pick): Boolean {
            if (!pick.loopStart.isFinite() || pick.loopStart < 0.0 || !pick.loopEnd.isFinite() ||
                pick.loopEnd <= pick.loopStart || pick.bars !in LOOP_LENGTHS ||
                !pick.beatSec.isFinite() || pick.beatSec <= 0.0 || !pick.bpm.isFinite() || pick.bpm <= 0.0
            ) return false
            val length = pick.loopEnd - pick.loopStart
            return length.isFinite() && same(length, pick.beatSec * pick.beats) &&
                same(pick.bpm * pick.beatSec, 60.0)
        }
        if (!validPick(plan.out)) return refused("invalid-out-pick")
        if (!validPick(plan.inn)) return refused("invalid-in-pick")
        if (plan.fold != 0.5 && plan.fold != 1.0 && plan.fold != 2.0) return refused("unsupported-fold")

        val target = plan.inn.bpm / plan.fold
        val q = target / plan.out.bpm
        val inverse = 1.0 / q
        if (!target.isFinite() || target <= 0.0 || !q.isFinite() || q <= 0.0 ||
            !inverse.isFinite() || inverse <= 0.0
        ) return refused("invalid-rates")
        val rawRampBars = ceil(abs(target - plan.out.bpm) / MAX_BPM_PER_BAR)
        // Retain the existing minimum, but do not hide a requirement beyond the maximum by clamping.
        val requiredRampBars = rawRampBars.takeIf { it.isFinite() && it <= Int.MAX_VALUE.toDouble() }
            ?.toInt()?.coerceAtLeast(MIN_RAMP_BARS)
        val summary = CrownQualification(
            accepted = false,
            refusal = null,
            outMinRate = minOf(1.0, q),
            outMaxRate = maxOf(1.0, q),
            inMinRate = minOf(1.0, inverse),
            inMaxRate = maxOf(1.0, inverse),
            requiredRampBars = requiredRampBars,
        )
        fun inExecutionBounds(rate: Double): Boolean =
            rate >= CROWN_MIN_EXECUTION_RATE - precision && rate <= CROWN_MAX_EXECUTION_RATE + precision
        if (!inExecutionBounds(q) || !inExecutionBounds(inverse)) return summary.copy(refusal = "execution-rate")
        if (requiredRampBars == null || requiredRampBars > MAX_RAMP_BARS) return summary.copy(refusal = "ramp-budget")

        if (plan.enterBars != ENTER_BARS || plan.rampBars !in MIN_RAMP_BARS..MAX_RAMP_BARS ||
            plan.rampBars < requiredRampBars || plan.exitBars < EXIT_BARS || plan.nativeIncoming ||
            !plan.incomingHoldSec.isFinite() || plan.incomingHoldSec < 0.0 ||
            !plan.faderSwapBeats.isFinite() || plan.faderSwapBeats <= 0.0 || !same(plan.targetBpm, target)
        ) return summary.copy(refusal = "invalid-clock")

        // Existing whole-IN-loop release rounding also bounds Clock allocation before constructing it.
        val inLoopOutBars = plan.inn.bars / plan.fold
        val expectedTotalBars = (ceil((plan.enterBars + plan.rampBars + EXIT_BARS) / inLoopOutBars) * inLoopOutBars).roundToInt()
        if (plan.totalBars != expectedTotalBars || plan.exitBars != plan.totalBars - plan.enterBars - plan.rampBars) {
            return summary.copy(refusal = "invalid-release")
        }
        val clock = Clock(plan)
        if (clock.beatTimes.any { !it.isFinite() } ||
            (1 until clock.beatTimes.size).any { clock.beatTimes[it] <= clock.beatTimes[it - 1] } ||
            !same(clock.tempo(0), plan.out.bpm) || !same(clock.tempo(clock.beats - 1), target)
        ) return summary.copy(refusal = "invalid-clock")
        val releaseLoops = (clock.inReleasePosition - plan.inn.loopStart) / (plan.inn.loopEnd - plan.inn.loopStart)
        val releaseRate = (clock.tempo(clock.beats - 1) / plan.inn.bpm) * plan.fold
        if (!clock.outPositionAt(clock.beats).isFinite() || !clock.inPositionAt(clock.beats).isFinite() ||
            !releaseLoops.isFinite() || releaseLoops < 1.0 || !same(releaseLoops, kotlin.math.round(releaseLoops)) ||
            !same(releaseRate, 1.0)
        ) return summary.copy(refusal = "invalid-release")
        return summary.copy(accepted = true)
    }

    // ---- Choosing the loops ----------------------------------------------------------------

    /**
     * The bars to loop on each side, or null when there is no loop mix to make:
     * either song has no usable grid there, or no instrumental bars at all. A
     * sung word is never looped; the pair then gets a blend or a filter mix.
     *
     * [earliestOutSec] is the earliest the outgoing loop may start (the
     * playhead plus setup time); [sungOut] / [sungIn] are each song's sung
     * stretches from synced lyrics, when it has them ([sungSpans]). A music
     * video ([outVideo] / [inVideo]) is never looped: its audio rarely matches
     * the album cut its lyrics are timed to.
     */
    fun plan(
        out: TrackAnalysis,
        outDuration: Double,
        inn: TrackAnalysis,
        inDuration: Double,
        earliestOutSec: Double,
        sungOut: List<Span>? = null,
        sungIn: List<Span>? = null,
        outVideo: Boolean = false,
        inVideo: Boolean = false,
        /** Filled with why there is no plan, when there isn't one. */
        why: StringBuilder? = null,
        playableOutDurationSec: Double = outDuration,
    ): Plan? {
        // A locally clean executable crown outranks coarse whole-track preferences; bridges do not.
        val effectiveOutDuration = if (playableOutDurationSec > 1.0) minOf(outDuration, playableOutDurationSec) else outDuration
        return mixPlan(
            out, effectiveOutDuration, inn, inDuration,
            outFirst = max(earliestOutSec, effectiveOutDuration - OUT_SEARCH_SEC),
            shift = false,
            sungOut, sungIn, outVideo, inVideo, why,
        )
    }

    /**
     * The same pair as [missed], with the outgoing loop moved to the next bar
     * that still passes [plan]'s lyrics, mask, loudness and tempo checks.
     *
     * Used when the decks are not ready at [missed]'s start. The search walks
     * forward from just after that start (not back to the latest loop in the
     * window). A phrase boundary within a bar of that next line is preferred
     * when the outgoing analysis has phrase boundaries — the same snap
     * [plan] already uses for bar lines, and the transition planner uses for
     * phrases — and a phrase that fails the cleanliness checks is skipped
     * rather than rejecting the bar underneath it. Null when nothing later
     * in the outgoing search window survives.
     */
    fun shiftToNextBar(
        missed: Plan,
        out: TrackAnalysis,
        outDuration: Double,
        inn: TrackAnalysis,
        inDuration: Double,
        sungOut: List<Span>? = null,
        sungIn: List<Span>? = null,
        outVideo: Boolean = false,
        inVideo: Boolean = false,
        why: StringBuilder? = null,
    ): Plan? = mixPlan(
        out, outDuration, inn, inDuration,
        outFirst = max(outDuration - OUT_SEARCH_SEC, missed.out.loopStart + SHIFT_EPSILON_SEC),
        shift = true,
        sungOut, sungIn, outVideo, inVideo, why,
    )

    /** One timed slice of deck preparation. Times are elapsed realtime, milliseconds. */
    data class PrepSpan(
        val step: String,
        val startedMs: Long,
        val endedMs: Long,
        /** When this step had to be finished: the planned loop start, on the same wall clock. */
        val deadlineMs: Long,
    ) {
        // djmix16: wall-only, via DeckBudget (sentinel-guarded). Raw
        // (endedMs - deadlineMs) subtracted a 0 sentinel as the boot
        // uptime (374320 ms) and a media position as wall (116245 ms).
        // Horizon (stale-epoch) is applied in lateStepReason, which has nowMs.
        val overrunMs: Long get() = DeckBudget.provenOverrunMs(endedMs, deadlineMs)
    }

    /**
     * Which preparation step missed the loop, and by how many milliseconds.
     * Only PROVEN lateness counts ([DeckBudget.isProvablyLate]): when the
     * media clock is not running there is no deadline, so the reason is
     * the honest "paused" rather than a bogus overrun number. When nothing
     * provably overran, the longest step is reported with its wall duration.
     *
     * djmix15: wall and media clocks can never subtract, and a stale wall
     * deadline from a prior track can never report as lateness. deadlineMs
     * must be THIS build's wall instant: 0, NO_DEADLINE, or older than the
     * horizon means no budget in force.
     * djmix16: the 120 s horizon let 116245 ms through (116 s < 120 s);
     * the horizon is now LOOP_BUDGET_HORIZON_MS (40 s, the loop lead)
     * and is applied to PER-SPAN deadlines too (the old span path had
     * no horizon check at all). Wall is only compared to wall; media
     * mixSeconds lateness must never be passed as a span deadline.
     */
    fun lateStepReason(spans: List<PrepSpan>, nowMs: Long, deadlineMs: Long): String {
        val late = spans.maxByOrNull { DeckBudget.provenOverrunMs(it.endedMs, it.deadlineMs, nowMs) }
        if (late != null) {
            val over = DeckBudget.provenOverrunMs(late.endedMs, late.deadlineMs, nowMs)
            if (over > 0L) return "${late.step} $over ms over"
        }
        if (deadlineMs == DeckBudget.NO_DEADLINE_MS || deadlineMs <= 0L) return "paused (no budget)"
        if (nowMs - deadlineMs > DeckBudget.LOOP_BUDGET_HORIZON_MS) return "paused (no budget)"
        val slowest = spans.maxByOrNull { it.endedMs - it.startedMs }
        if (nowMs < deadlineMs) {
            val leftMs = deadlineMs - nowMs
            return if (slowest != null) "${slowest.step} early-abort ($leftMs ms left)" else "deck build early-abort ($leftMs ms left)"
        }
        val over = (nowMs - deadlineMs).coerceAtLeast(0L)
        return if (slowest != null) "${slowest.step} $over ms over" else "deck build $over ms over"
    }

    /**
     * djmix16 cold-loop yield (pure, scalars only). A cold loop (decks
     * not in RAM) needs a full classic arm lead to fall back to, not
     * 4.5 s: the old <4.5 s gate left <4 s to buffer a cold HTTP
     * ExoPlayer standby for a 9.1 s DJ_FILTER blend. Yield when the
     * loop start is within [max] (8.5 s decode floor, the learned arm
     * lead, or 25 s while the next stream warms) and the loop is cold.
     * Fast-ready loops never yield (decks already in RAM). djmix17:
     * actively-building loops may run while still outside the hard
     * cold-build deadline (14 s while the next stream warms, 10 s when
     * ready), but a slow/hung primeJob yields inside that deadline —
     * riding it to dry EOF starved classic begin() on Never Let Go ->
     * S.A.D.
     */
    fun shouldYieldColdLoop(
        loopStartMs: Long,
        currentPositionMs: Long,
        fastReady: Boolean,
        building: Boolean,
        blendArmLeadMs: Long,
        nextStreamReady: Boolean,
        isNativeBridge: Boolean = false,
    ): Boolean {
        // Decks already in RAM: never yield, even inside the deadline.
        if (fastReady) return false
        val remainingMs = loopStartMs - currentPositionMs
        if (isNativeBridge) {
            // Keep a valid nativeBridge (Beat blend / Drop / Echo) alive
            // while >= 8 s remains to decode a native transition window.
            return remainingMs < 8_000L
        }
        if (building) {
            // djmix17: an active build may run during the outer prime
            // window (> hard deadline before loopStart), but a slow or
            // hung primeJob must not ride to dry EOF. Once within the
            // hard cold-build deadline (14 s while the next stream warms,
            // 10 s when ready; never below the 6.5 s floor) yield so
            // classic begin() still has >= 6.5 s to buffer and blend.
            // djmix17-harden: 10 s (ready) / 14 s (warming) instead of
            // 8.5/12 — if contentEnd sits ~3 s before loopStart (silence
            // tail), a yield at 8.5 s before loopStart leaves only 5.5 s
            // to contentEnd, marginal for a cold classic arm. 10/14
            // leaves 7/11 s.
            val hardDeadlineMs = maxOf(if (nextStreamReady) 10_000L else 14_000L, 6_500L)
            return remainingMs <= hardDeadlineMs
        }
        val lead = maxOf(8_500L, blendArmLeadMs, if (!nextStreamReady) 25_000L else 0L)
        return remainingMs <= lead
    }

    private fun mixPlan(
        out: TrackAnalysis,
        outDuration: Double,
        inn: TrackAnalysis,
        inDuration: Double,
        outFirst: Double,
        shift: Boolean,
        sungOut: List<Span>?,
        sungIn: List<Span>?,
        outVideo: Boolean,
        inVideo: Boolean,
        why: StringBuilder?,
    ): Plan? {
        if (outVideo || inVideo) return null.also { why?.append("a music video") }
        val outGrid = constantGrid(out) ?: return null.also { why?.append("no grid on the outgoing song") }
        val inGrid = constantGrid(inn) ?: return null.also { why?.append("no grid on the incoming song") }
        val outCount = Rejections()
        val inCount = Rejections()

        // outgoing: the latest instrumental loop that ends before the song does,
        // or, when shifting, the next one after the start that was missed
        val outEnd = outDuration - 0.5
        val outroFloor = maxOf(
            outDuration * PhraseConfig.MIN_PLAY_FRACTION,
            out.outroStartTime.takeIf { it > 0.0 } ?: (outDuration - 32.0),
        )
        // vc73 A1: outro preference is for the latest-first plan search.
        // A shift walks forward to the next bar, so it keeps forward order
        // and must not be dragged to the latest outro loop.
        val effectiveOutroFrom = if (shift) Double.POSITIVE_INFINITY else outroFloor
        val outPick = firstClean(
            candidates = { bars ->
                val loops = loopsBetween(out.tailBeats, outGrid, outFirst, outEnd, bars)
                if (shift) preferNextPhrase(loops, out.phraseBoundaries, outGrid.beatSec) else loops.asReversed()
            },
            analysis = out,
            sung = sungOut,
            beatSec = outGrid.beatSec,
            count = outCount,
            outroFrom = effectiveOutroFrom,
        ) ?: return null.also { why?.append("outgoing: ")?.append(outCount) }

        // incoming: the earliest instrumental loop in its opening
        val inEnd = min(IN_SEARCH_SEC + LOOP_BARS * inGrid.barSec, inDuration - 20)
        val inFloor = maxOf(0.0, inn.audibleStartTime?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0, firstAudibleTime(inn))
        val inFirst = inFloor
        val inSearchFrom = if (inFirst > inEnd - 8.0) 0.0 else inFloor
        val inPick = firstClean(
            candidates = { bars -> loopsBetween(inn.headBeats, inGrid, inSearchFrom, inEnd, bars) },
            analysis = inn,
            sung = sungIn,
            beatSec = inGrid.beatSec,
            count = inCount,
        ) ?: return null.also { why?.append("incoming: ")?.append(inCount) }

        val outBeat = (outPick.end - outPick.start) / (outPick.bars * 4)
        val inBeat = (inPick.end - inPick.start) / (inPick.bars * 4)
        val outBpm = 60 / outBeat
        val inBpm = 60 / inBeat

        val fold = alignedFold(outBpm, inBpm)
        val target = inBpm / fold
        val rampBars = ceil(abs(target - outBpm) / MAX_BPM_PER_BAR).toInt().coerceIn(MIN_RAMP_BARS, MAX_RAMP_BARS)
        // the incoming loop lets go only at its own loop boundary: round the mix up to whole incoming loops
        val inLoopOutBars = inPick.bars / fold
        val totalBars = (ceil((ENTER_BARS + rampBars + EXIT_BARS) / inLoopOutBars) * inLoopOutBars).roundToInt()

        fun pick(clean: Clean, beatSec: Double, bpm: Double, anchors: List<Double>? = null) = Pick(
            loopStart = clean.start,
            loopEnd = clean.end,
            bars = clean.bars,
            beatSec = beatSec,
            bpm = bpm,
            vocal = clean.vocal,
            checkedBy = clean.checkedBy,
            anchorBeats = anchors,
        )
        // djmix24 Bug A4: deterministic EDM buildup. Both sides dance tempo,
        // post-fold delta within TEMPO_HARD_DELTA, clean instrumental loops
        // (firstClean already guarantees low vocal share), low dual-vocal
        // clash. Then flanger + tightening roll + HPF sweep. Non-EDM pairs
        // use deterministic pair-hash FX rotation (scalars only).
        val dualVocal = (outPick.vocal + inPick.vocal) > 0.06
        val edm = isEdmPair(outBpm, inBpm, fold) && !dualVocal
        val (fxFlanger, fxRoll, fxReverb, fxEcho) = if (edm) {
            // EDM: jet flanger + tightening roll + reverb release.
            Quad(true, true, true, false)
        } else {
            // Non-EDM rotation: FLANGER / CLEAN_SWAP / ECHO / REVERB, never
            // flanging over dual-vocal overlap.
            if (dualVocal) Quad(false, false, false, false)
            else when (fxSlotForPair(out.trackId, inn.trackId)) {
                0 -> Quad(true, false, false, false)
                1 -> Quad(false, false, false, false)
                2 -> Quad(false, false, false, true)
                else -> Quad(false, false, true, false)
            }
        }
        // 2B tracked-beat anchors: slice each side's own BeatSide.beats over
        // its chosen crown loop. Null (safe synthetic path) when the grid does not
        // cover the loop monotonically — see [trackedAnchors]. Crown-loop
        // geometry ([loopsBetween]/[firstClean]) is untouched.
        val outAnchors = trackedAnchors(out.tailBeats, outPick.start, outPick.end, outPick.bars)
        val inAnchors = trackedAnchors(inn.headBeats, inPick.start, inPick.end, inPick.bars)
        val candidate = Plan(
            out = pick(outPick, outBeat, outBpm, outAnchors),
            inn = pick(inPick, inBeat, inBpm, inAnchors),
            fold = fold,
            enterBars = ENTER_BARS,
            rampBars = rampBars,
            exitBars = totalBars - ENTER_BARS - rampBars,
            totalBars = totalBars,
            echoTail = fxEcho,
            flanger = fxFlanger,
            buildupRoll = fxRoll && outPick.bars > 0,
            reverbWash = fxReverb,
            hpSweepEndHz = if (fxRoll) BUILDUP_HP_END_HZ else 0f,
        )
        val qualification = qualifyCrown(candidate)
        if (!qualification.accepted) return null.also { why?.append("crown: ")?.append(qualification.refusal) }
        return candidate
    }

    private data class Quad(val a: Boolean, val b: Boolean, val c: Boolean, val d: Boolean)

    /** The fallback constant grid: a beat length, and a bar line to count from. */
    private class Grid(val beatSec: Double, val anchor: Double) {
        val barSec: Double get() = beatSec * 4
    }

    private fun constantGrid(analysis: TrackAnalysis): Grid? {
        val beat = analysis.beatInterval.takeIf { it.isFinite() && it > 0 }
            ?: analysis.bpm.takeIf { it.isFinite() && it > 0 }?.let { 60 / it }
            ?: return null
        val anchor = analysis.downbeats.firstOrNull { it.isFinite() } ?: analysis.firstBeat
        if (!anchor.isFinite()) return null
        return Grid(beat, anchor)
    }

    private open class Loop(val start: Double, val end: Double)

    private class Clean(start: Double, end: Double, val bars: Int, val vocal: Double, val checkedBy: Check) :
        Loop(start, end)

    /**
     * [bars]-bar loops lying in [from]..[to]. With the model's downbeats, each
     * loop runs from a detected beat 1 to the detected beat 1 [bars] bars on, so
     * its seam is the music's own bar line however the tempo wanders; one whose
     * end lands more than a quarter beat from [bars] bars on (a missed or
     * misplaced downbeat between) is skipped. Without them (or where the model
     * found too few), the constant grid.
     */
    private fun loopsBetween(side: BeatSide?, grid: Grid, from: Double, to: Double, bars: Int): List<Loop> {
        val loops = ArrayList<Loop>()
        if (side != null && side.bpm > 0 && side.downbeats.size >= bars + 1) {
            val beat = 60 / side.bpm
            val downs = side.downbeats
            for (start in downs) {
                if (start < from) continue
                val target = start + bars * 4 * beat
                val end = nearest(downs, target)
                if (abs(end - target) > beat / 4 || end > to) continue
                loops += Loop(start, end)
            }
            if (loops.isNotEmpty()) return loops
        }
        if (to < from) return loops
        val bar = grid.barSec
        val length = bars * bar
        var t = grid.anchor + ceil((from - grid.anchor) / bar) * bar
        while (t + length <= to) {
            loops += Loop(t, t + length)
            t += bar
        }
        return loops
    }

    /**
     * Puts a loop that starts on the next phrase boundary first, when one
     * falls within a bar of the earliest candidate — the same tolerance the
     * blend planner snaps a transition to a phrase with. Anything that phrase
     * loop fails (lyrics, mask, loudness) stays in the list behind it, so the
     * bar underneath is still tried.
     */
    private fun preferNextPhrase(loops: List<Loop>, phrases: List<Double>, beatSec: Double): List<Loop> {
        if (loops.isEmpty() || phrases.isEmpty()) return loops
        val tolerance = max(1.0, beatSec * 4)
        val earliest = loops.first().start
        val phrase = phrases
            .filter { it.isFinite() && it + 1e-9 >= earliest && it - earliest <= tolerance + 1e-9 }
            .minOrNull() ?: return loops
        val snapped = loops.filter { abs(it.start - phrase) <= beatSec / 4 }
        if (snapped.isEmpty()) return loops
        val rest = loops.filter { abs(it.start - phrase) > beatSec / 4 }
        return snapped + rest
    }

    private fun nearest(sorted: List<Double>, target: Double): Double {
        var low = 0
        var high = sorted.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] < target) low = mid + 1 else high = mid
        }
        val after = sorted[low]
        val before = sorted.getOrNull(low - 1) ?: return after
        return if (abs(before - target) <= abs(after - target)) before else after
    }

    /**
     * The first loop (in the order [candidates] gives) that is instrumental and
     * grooves, trying 4 bars, then 2, then 1. Null when there is none.
     *
     * Grooves: every bar at least [MIN_LOUDNESS] of the 60th-percentile loop in
     * the search. Instrumental: with synced lyrics, no sung line overlaps the
     * loop or the [LYRIC_GUARD_SEC] before it; without them, the vocal model's
     * windows cover the loop and the beat before it and find almost nothing
     * sung there.
     */
    /** What turned a side's candidate loops down, for the log and stats when none survives. */
    private class Rejections {
        var loops = 0
        var quiet = 0
        var lyrics = 0
        var unmasked = 0
        var sung = 0

        override fun toString() = if (loops == 0) {
            "no bar lines in the search"
        } else {
            "$loops loops: $quiet quiet, $lyrics sung (lyrics), $sung sung (mask), $unmasked unmeasured"
        }
    }

    /**
     * djmix24 loop-seam continuity (scalar RMS only, patent-safe): RMS just
     * before the loop end vs just after the loop start. Smaller is smoother.
     * NaN when windows don't reach (treated as neutral).
     */
    private fun seamDelta(analysis: TrackAnalysis, loop: Loop): Double {
        val pre = meanRms(analysis.levelWindows, loop.end - 0.25, loop.end)
        val post = meanRms(analysis.levelWindows, loop.start, loop.start + 0.25)
        if (pre.isNaN() || post.isNaN()) return 0.0
        val lo = minOf(pre, post).coerceAtLeast(1e-6)
        val hi = maxOf(pre, post)
        return if (lo <= 1e-6) 0.0 else kotlin.math.abs(kotlin.math.ln(hi / lo))
    }

    private fun firstClean(
        candidates: (Int) -> List<Loop>,
        analysis: TrackAnalysis,
        sung: List<Span>?,
        beatSec: Double,
        count: Rejections,
        outroFrom: Double = Double.POSITIVE_INFINITY,
    ): Clean? {
        for (bars in LOOP_LENGTHS) {
            val raw = candidates(bars)
            // djmix24: prefer seam-continuous loops first (no mid-transient
            // chop when an adjacent clean bar is smoother). Stable sort keeps
            // the original order (latest-out / earliest-in) within ties.
            // vc73 A1: when searching the outro (outgoing), never let tiny
            // seamDelta noise in an earlier verse/chorus outrank clean outro
            // loops inside outroFrom; bucket seamDelta coarsely (0.15) so
            // latest-first order survives within a bucket.
            val loops = if (raw.size > 1 && analysis.levelWindows.isNotEmpty()) {
                if (outroFrom.isFinite()) {
                    raw.sortedWith(
                        compareBy<Loop>(
                            { if (it.start >= outroFrom) 0 else 1 },
                            { kotlin.math.floor(seamDelta(analysis, it) / 0.15).toInt() },
                        ),
                    )
                } else {
                    raw.sortedWith(compareBy<Loop>({ seamDelta(analysis, it) }))
                }
            } else if (outroFrom.isFinite() && raw.size > 1) {
                raw.sortedBy { if (it.start >= outroFrom) 0 else 1 }
            } else raw
            if (loops.isEmpty()) continue
            count.loops += loops.size
            val louds = DoubleArray(loops.size) { quietestBar(analysis.levelWindows, loops[it], bars) }
            val known = louds.filter { !it.isNaN() }.sorted()
            val typical = if (known.isEmpty()) Double.NaN else known[min(known.size - 1, floor(known.size * 0.6).toInt())]
            for ((index, loop) in loops.withIndex()) {
                val loud = louds[index]
                val inOutro = loop.start >= outroFrom
                val minLoud = if (inOutro) 0.22 else MIN_LOUDNESS
                val minSilent = if (inOutro) 0.0056234 else SILENT_RMS
                val hasSilentDrop = inOutro && hasSilentChunk(analysis.levelWindows, loop.start, loop.end, minSilent)
                if (!loud.isNaN() && (loud < typical * minLoud || loud <= minSilent || hasSilentDrop)) {
                    count.quiet++
                    continue
                }
                if (sung != null) {
                    val from = loop.start - LYRIC_GUARD_SEC
                    val touching = sung.filter { it.start < loop.end && it.end > from }
                    if (touching.any { it.start < loop.end && it.certainUntil > from }) {
                        count.lyrics++
                        continue
                    }
                    val lyricOverlap = touching.isNotEmpty()
                    // Lyrics are a veto. When the vocal mask exists it also has
                    // to agree: a bar the lyrics never mention can still be sung.
                    if (analysis.vocalWindows.isNotEmpty()) {
                        val mask = maskStats(analysis.vocalWindows, loop.start - beatSec, loop.end)
                        if (mask == null) {
                            count.unmasked++
                            continue
                        }
                        if (maskRejects(mask, hasLyricOverlap = lyricOverlap)) {
                            count.sung++
                            continue
                        }
                        return Clean(loop.start, loop.end, bars, mask.share, Check.LYRICS_AND_MASK)
                    }
                    if (!lyricOverlap) return Clean(loop.start, loop.end, bars, 0.0, Check.LYRICS)
                    // Only the guessed tails of open lines reach it, and there is
                    // no mask to decide them.
                    val mask = maskStats(analysis.vocalWindows, loop.start - beatSec, loop.end)
                    if (mask == null) {
                        count.unmasked++
                        continue
                    }
                    if (maskRejects(mask, hasLyricOverlap = true)) {
                        count.sung++
                        continue
                    }
                    return Clean(loop.start, loop.end, bars, mask.share, Check.LYRICS_AND_MASK)
                }
                val mask = maskStats(analysis.vocalWindows, loop.start - beatSec, loop.end)
                if (mask == null) {
                    count.unmasked++
                    continue
                }
                if (maskRejects(mask, hasLyricOverlap = false)) {
                    count.sung++
                    continue
                }
                return Clean(loop.start, loop.end, bars, mask.share, Check.MASK)
            }
        }
        return null
    }

    /** True when any 0.5 s slice inside [from]..[to] drops to dead silence (e.g. a fade-out hitting 0). */
    private fun hasSilentChunk(windows: List<CurveWindow>, from: Double, to: Double, floorRms: Double): Boolean {
        if (windows.isEmpty() || to <= from) return false
        var t = from
        while (t + 0.25 < to) {
            val end = min(to, t + 0.5)
            val rms = meanRms(windows, t, end)
            if (!rms.isNaN() && rms <= floorRms) return true
            t += 0.5
        }
        return false
    }

    /** The RMS of the quietest of a loop's bars, from the level windows; NaN where they don't reach. */
    private fun quietestBar(windows: List<CurveWindow>, loop: Loop, bars: Int): Double {
        if (windows.isEmpty()) return Double.NaN
        var quietest = Double.POSITIVE_INFINITY
        val bar = (loop.end - loop.start) / bars
        for (b in 0 until bars) {
            val rms = meanRms(windows, loop.start + b * bar, loop.start + (b + 1) * bar)
            if (rms.isNaN()) return Double.NaN
            quietest = min(quietest, rms)
        }
        return quietest
    }

    private fun meanRms(windows: List<CurveWindow>, from: Double, to: Double): Double {
        var sumSquares = 0.0
        var count = 0
        for (window in windows) {
            val a = max(0, ceil((from - window.startTime) * window.frameRate).toInt())
            val b = min(window.values.size, floor((to - window.startTime) * window.frameRate).toInt())
            for (i in a until b) {
                val rms = rmsFromLevel(window.values[i].toDouble())
                sumSquares += rms * rms
                count++
            }
        }
        return if (count == 0) Double.NaN else sqrt(sumSquares / count)
    }

    data class MaskStats(
        val share: Double,
        val longestRunSec: Double,
        val peak: Double = 0.0,
        val mean: Double = 0.0,
    )

    private fun maskRejects(mask: MaskStats, hasLyricOverlap: Boolean = false): Boolean {
        val peakCap = if (!hasLyricOverlap && mask.mean < 0.08 && mask.longestRunSec <= 0.15) 0.30 else MAX_SUNG_PEAK
        return mask.share > MAX_SUNG_SHARE || mask.longestRunSec > MAX_SUNG_RUN_SEC || mask.peak >= peakCap
    }

    /**
     * How much of [from]..[to] the vocal model calls sung, and its longest sung
     * run; null when its windows cover under [MIN_MASK_COVERAGE] of it.
     */
    fun maskStats(windows: List<CurveWindow>, from: Double, to: Double): MaskStats? {
        var sung = 0
        var n = 0
        var covered = 0.0
        var run = 0
        var longest = 0
        var peak = 0.0
        var sum = 0.0
        var fps = 1.0
        for (window in windows) {
            val a = max(0, ceil((from - window.startTime) * window.frameRate).toInt())
            val b = min(window.values.size, floor((to - window.startTime) * window.frameRate).toInt())
            for (i in a until b) {
                n++
                val v = window.values[i].toDouble()
                sum += v
                peak = max(peak, v)
                if (window.values[i] > VOCAL_ACTIVE_THRESHOLD) {
                    sung++
                    run++
                    longest = max(longest, run)
                } else {
                    run = 0
                }
            }
            if (b > a) {
                covered += (b - a) / window.frameRate
                fps = window.frameRate
            }
        }
        return if (n > 0 && covered >= (to - from) * MIN_MASK_COVERAGE) {
            MaskStats(sung.toDouble() / n, longest / fps, peak, sum / n)
        } else {
            null
        }
    }

    /**
     * A song's sung stretches from its synced lyric lines: each from just before
     * its start to just after its end. A line with no stated end is taken as sung
     * until the next line, and never longer than [LYRIC_OPEN_LINE_SEC]. A longer
     * gap is silence as far as the lyric sheet knows. The padding is a short
     * lead ([LYRIC_LEAD_SEC]) and a short tail ([LYRIC_TAIL_SEC]), not a bar.
     */
    fun sungSpans(lines: List<Line>): List<Span> {
        val sorted = lines.sortedBy { it.timeMs }
        val spans = ArrayList<Span>()
        for ((index, line) in sorted.withIndex()) {
            if (line.blank) continue
            val start = line.timeMs / 1000.0
            val next = sorted.subList(index + 1, sorted.size).firstOrNull { it.timeMs > line.timeMs }?.timeMs
            val stated = line.endMs != null && line.endMs > line.timeMs
            val statedEnd = if (stated) line.endMs!! / 1000.0 else null
            val nextSec = next?.div(1000.0)
            // An end that lands on the next line is the provider filling the
            // gap, not the voice still going. Only the opening of the line is
            // certainly sung; the mask decides the rest.
            val gapFill = statedEnd != null && nextSec != null && statedEnd >= nextSec - 0.25
            val wordEnd = line.wordEndMs?.takeIf { it > line.timeMs }?.div(1000.0)
            val openEnd = min(start + LYRIC_OPEN_LINE_SEC, nextSec ?: (start + 10.0))
            val end = when {
                wordEnd != null -> wordEnd
                statedEnd != null && !gapFill -> statedEnd
                else -> openEnd
            }
            val certain = when {
                wordEnd != null -> wordEnd
                statedEnd != null && !gapFill -> statedEnd
                else -> min(end, start + OPEN_LINE_CERTAIN_SEC)
            }
            spans += Span(start - LYRIC_LEAD_SEC, end + LYRIC_TAIL_SEC, certain + LYRIC_TAIL_SEC)
        }
        return spans
    }

    /**
     * Whether a loop that is already on the countdown may be replaced.
     *
     * A new analysis must not take a healthy loop away. [TAKE] only when
     * there was no plan, the pair changed, or the playhead seeked past the
     * bar and the loop is not armed. [CLEAR] is that same case with nothing
     * to put in its place.
     *
     * [sungVeto] is different: lyrics that arrived later, or a vocal mask
     * that now calls the bar sung, drop the plan even when it is armed.
     * The caller then takes the next rung instead of keeping the loop.
     */
    enum class Hold { KEEP, TAKE, CLEAR }

    fun holdLoop(
        armed: Boolean,
        havePlan: Boolean,
        gotReplacement: Boolean,
        pairChanged: Boolean,
        seekPast: Boolean,
        sungVeto: Boolean = false,
    ): Hold {
        if (sungVeto) return if (gotReplacement) Hold.TAKE else Hold.CLEAR
        if (pairChanged || (seekPast && !armed)) return if (gotReplacement) Hold.TAKE else Hold.CLEAR
        if (!havePlan) return if (gotReplacement) Hold.TAKE else Hold.CLEAR
        return Hold.KEEP
    }

    /**
     * True when [plan] must not be kept. A looping side that overlaps a lyric
     * which is certainly sung is out. So is a looping side whose vocal mask
     * exists and either does not cover the bar or calls it sung. A straight
     * bridge (nothing looping) is not a loop, so this does not veto it.
     */
    fun vetoes(
        plan: Plan,
        out: TrackAnalysis,
        inn: TrackAnalysis,
        sungOut: List<Span>?,
        sungIn: List<Span>?,
    ): Boolean = sideVeto(plan.out, plan.outLoops, out, sungOut) || sideVeto(plan.inn, plan.inLoops, inn, sungIn)

    private fun sideVeto(pick: Pick, loops: Boolean, analysis: TrackAnalysis, sung: List<Span>?): Boolean {
        if (!loops) return false
        if (sung != null) {
            val from = pick.loopStart - LYRIC_GUARD_SEC
            if (sung.any { it.start < pick.loopEnd && it.certainUntil > from }) return true
        }
        if (analysis.vocalWindows.isEmpty()) return false
        val mask = maskStats(analysis.vocalWindows, pick.loopStart - pick.beatSec, pick.loopEnd) ?: return true
        return maskRejects(mask)
    }

    /** True when a looping side of [plan] overlaps a lyric that is certainly sung. */
    fun overlapsSung(plan: Plan, sungOut: List<Span>?, sungIn: List<Span>?): Boolean {
        fun hits(pick: Pick, spans: List<Span>?, loops: Boolean): Boolean {
            if (!loops || spans == null) return false
            val from = pick.loopStart - LYRIC_GUARD_SEC
            return spans.any { it.start < pick.loopEnd && it.certainUntil > from }
        }
        return hits(plan.out, sungOut, plan.outLoops) || hits(plan.inn, sungIn, plan.inLoops)
    }

    // ---- Putting it on the clock -----------------------------------------------------------

    /**
     * A plan laid out in time, t0 = 0 being the moment the outgoing song
     * reaches its loop start. Every time here is seconds after t0.
     */
    class Clock(
        val plan: Plan,
        val incomingHoldSec: Double = plan.incomingHoldSec,
    ) {
        /** Beats of the mix proper, in outgoing beats; then the incoming song's settle. */
        val beats: Int = plan.totalBars * 4
        val settleBeats: Int = (SETTLE_BARS * 4 / plan.fold).roundToInt()
        val rampFrom: Int = plan.enterBars * 4
        val rampTo: Int = (plan.enterBars + plan.rampBars) * 4

        /** Master tempo during outgoing beat [k]. */
        fun tempo(k: Int): Double {
            val outBpm = plan.out.bpm.coerceAtLeast(1.0)
            val tgtBpm = plan.targetBpm.takeIf { it > 1.0 } ?: outBpm
            return when {
                k < rampFrom -> outBpm
                k >= rampTo -> tgtBpm
                else -> {
                    val span = (rampTo - rampFrom).coerceAtLeast(1)
                    outBpm + (tgtBpm - outBpm) * (k - rampFrom + 0.5) / span
                }
            }
        }

        /** Beat k of the mix, for k in 0..beats + settleBeats. */
        val beatTimes: DoubleArray = DoubleArray(beats + settleBeats + 1).also { times ->
            for (k in 0 until beats + settleBeats) times[k + 1] = times[k] + 60 / tempo(k)
        }

        val enterEnd: Double get() = beatTimes[rampFrom]
        val rampEnd: Double get() = beatTimes[rampTo]

        /** Middle of the ramp: the app moves to the incoming song. */
        val handoffAt: Double get() = beatTimes[(rampFrom + rampTo) / 2]

        /**
         * djmix24 tight half-beat..1-beat LR4 bass swap (1 beat at mid-ramp). Was 1 bar.
         * djmix25 P1.12: width from the MixGain.faderTemplate table via
         * [Plan.faderSwapBeats] ("club" keeps 1 beat; templates vary it).
         */
        val swapBar: Double get() = 60 / tempo((rampFrom + rampTo) / 2) * plan.faderSwapBeats

        /** The incoming loop lets go; the outgoing one is silent. */
        val releaseAt: Double get() = beatTimes[beats]

        /** The incoming player takes over here, at this track position. */
        val elementAt: Double get() = beatTimes[beats + settleBeats]

        /** Monotonic source coordinate for native knots, including a held loop. */
        fun inUnwrappedTimeAt(mixSec: Double): Double {
            val hold = incomingHoldSec.coerceAtLeast(0.0)
            if (mixSec <= hold) return plan.inn.loopStart
            if (plan.nativeIncoming) return plan.inn.loopStart + mixSec - hold
            fun progress(t: Double): Double {
                if (t <= 0.0) return 0.0
                if (t >= releaseAt) return beats.toDouble()
                val (k, fraction) = beatAt(t)
                return k + fraction
            }
            return plan.inn.loopStart + (progress(mixSec) - progress(hold)).coerceAtLeast(0.0) *
                plan.fold * plan.inn.beatSec + (mixSec - maxOf(releaseAt, hold)).coerceAtLeast(0.0)
        }

        /** Track position (seconds) of the incoming deck at [mixSec] after t0. */
        fun inTrackTimeAt(mixSec: Double): Double {
            val unwrapped = inUnwrappedTimeAt(mixSec)
            // A hold that ends before even one loop was consumed has nothing
            // to wrap. Native-incoming decks are always identity coordinates.
            if (!plan.inLoops || plan.nativeIncoming || inReleasePosition < plan.inn.loopEnd) return unwrapped
            return physicalTrackTime(unwrapped, plan.inn.loopStart, plan.inn.loopEnd, inReleasePosition)
        }

        val handoverTrackSec: Double get() = inTrackTimeAt(elementAt)
        val elementTrackTime: Double get() = handoverTrackSec

        /** The unwrapped track position each deck plays at beat [k] (for k up to [beats]). */
        fun outPositionAt(k: Int): Double = plan.out.loopStart + k * plan.out.beatSec
        fun inPositionAt(k: Int): Double = plan.inn.loopStart + k * plan.fold * plan.inn.beatSec

        /** Unwrapped positions past which each deck no longer loops (the incoming one runs on). */
        val inReleasePosition: Double get() = inUnwrappedTimeAt(releaseAt)

        /** The beat [t] falls in, and how far through it (0..1); k = -1 before t0. */
        fun beatAt(t: Double): Pair<Int, Double> {
            if (t < 0) return -1 to 0.0
            var low = 0
            var high = beatTimes.size - 1
            while (low < high) {
                val mid = (low + high + 1) ushr 1
                if (beatTimes[mid] <= t) low = mid else high = mid - 1
            }
            val k = low
            if (k + 1 >= beatTimes.size) return k to 0.0
            return k to min(1.0, (t - beatTimes[k]) / (beatTimes[k + 1] - beatTimes[k]))
        }
    }

    /** Same coordinate transform as native Deck::trackSecondsAt (track-second units). */
    fun physicalTrackTime(unwrapped: Double, loopStart: Double, loopEnd: Double, release: Double): Double {
        if (loopEnd <= loopStart || unwrapped < loopEnd) return unwrapped
        return if (unwrapped < release) loopStart + (unwrapped - loopStart) % (loopEnd - loopStart)
        else loopEnd + (unwrapped - release)
    }

    /**
     * A transition the loop mix declined, on the same clock: a drop, a beat
     * blend, or an echo. Null when neither song has a grid worth mixing on,
     * which is when the dual player crossfade is the floor.
     *
     * Stretch is capped at 6% when either song is vocal and 8% when both are
     * instrumental, after folding half and double time. The beat blend's ramp
     * arrives before the handover, so the incoming deck is already at 1.000x.
     * The drop and the echo play the incoming song at 1.000x the whole way.
     */
    fun bridge(
        out: TrackAnalysis,
        outDuration: Double,
        inn: TrackAnalysis,
        inDuration: Double,
        earliestOutSec: Double,
        sungOut: List<Span>? = null,
        sungIn: List<Span>? = null,
        outVideo: Boolean = false,
        inVideo: Boolean = false,
        playableOutDurationSec: Double = outDuration,
    ): Plan? {
        if (outVideo || inVideo) return null
        // djmix25: decline-to-mix skips drop/blend but still gets an echo-out
        // (bkmasterplayer tempo_jump_plan doctrine: large gap -> echo on the
        // bar, not a forced blend). Only the Rung 4 echo/cut handles it; if
        // that is null the caller falls to the phrase-length EQUAL_POWER.
        val declinedToMix = shouldDeclineToMix(out, inn)
        val effectiveOutDuration = if (playableOutDurationSec > 1.0) minOf(outDuration, playableOutDurationSec) else outDuration
        if (effectiveOutDuration < 8.0 || inDuration < 8.0) return null
        if (out.beatConfidence < 0.25 && inn.beatConfidence < 0.25) return null
        val outGrid = constantGrid(out)
        val inGrid = constantGrid(inn)
        if (outGrid == null && inGrid == null) return null
        val prelimRate = if (outGrid != null && inGrid != null && out.bpm > 0 && inn.bpm > 0) {
            val f = alignedFold(out.bpm, inn.bpm)
            (f * inGrid.beatSec) / outGrid.beatSec
        } else Double.NaN
        val steadyGridMatch = outGrid != null && inGrid != null &&
            out.downbeats.size >= 8 && inn.downbeats.size >= 8 &&
            prelimRate.isFinite() && abs(prelimRate - 1.0) <= PhraseConfig.TEMPO_HARD_DELTA
        val outOk = outGrid != null && out.bpm > 0 &&
            (out.beatConfidence >= 0.45 || (steadyGridMatch && out.beatConfidence >= MIN_DJ_CONFIDENCE))
        val inOk = inGrid != null && inn.bpm > 0 &&
            (inn.beatConfidence >= 0.45 || (steadyGridMatch && inn.beatConfidence >= MIN_DJ_CONFIDENCE))
        // djmix25 P1.9: regional keys (outgoing tail vs incoming head).
        val keys = camelotPair(exitKeyForMix(out), entryKeyForMix(inn))
        val steps = regionalCamelotSteps(out, inn)
        // djmix25 P1.6: acapella-over-instrumental rung. Same key + both
        // confidences >= 0.6 + incoming instrumental intro + outgoing vocal.
        // Routes through the normal drop/blend/echo picks with the carrier on.
        if (acapellaAllowed(out, inn)) {
            // Try the beatmatched picks first; fall to echo with the carrier.
            // (Reuses the out/in grids already computed above.)
        }
        if (declinedToMix) {
            // vc73 D1: keep FAMILIES precedence (drop/muffle/beatless-bridge
            // ahead of echoBase). Umxhq vocal-separation muffle is owned by
            // the Part 2 LoopMixSession flag; this bridge change deliberately
            // keeps the registry order so declined curves-less pairs stay Echo.
            // djmix25 R2: declined pairs take the Echo rung only (no drop/blend).
            // vc69 P0: the alternative families still fit some declined
            // pairs (muffle over the 8..10 percent band, beatless bridge on
            // beatless edges, drop-in on high-energy entries). Each requires
            // bass-curve evidence, so curve-less pairs keep the Echo rung
            // exactly as before.
            val decBeatSec = out.beatInterval.takeIf { it.isFinite() && it > 0.05 }
                ?: if (out.bpm.isFinite() && out.bpm > 1.0) 60.0 / out.bpm else 0.0
            val decAnchor = out.contentEndTime.takeIf { it.isFinite() && it > 0.0 && it <= outDuration } ?: outDuration
            // Family registry: fitting alternatives decide the base geometry
            // (drop vs echo) and the loop-plan flags; a new family only adds
            // a registry entry, never an edit here. Precedence (as before):
            // drop geometry first, then muffle, then bridge. A drop gate that
            // fits but whose dropPlan is null falls through to the next
            // fitting family instead of degrading to an unflagged echo.
            val fitting = if (decBeatSec > 0.0) TransitionAlternatives.FAMILIES
                .sortedWith(compareBy({ if (it.usesDropBase) 0 else 1 }, { it.priority }))
                .filter { it.fits(out, inn, decAnchor, decBeatSec) } else emptyList()
            var echoLazy: LoopMix.Plan? = null
            var echoBuilt = false
            fun echoBase(): LoopMix.Plan? {
                if (!echoBuilt) {
                    echoLazy = echoPlan(out, inn, effectiveOutDuration, earliestOutSec, outGrid, inGrid, keys, sungIn, sungOut)
                    echoBuilt = true
                }
                return echoLazy
            }
            for (family in fitting) {
                if (family.usesDropBase) {
                    if (outGrid == null) continue
                    val dropped = dropPlan(
                        out, inn, effectiveOutDuration, earliestOutSec, sungOut, sungIn,
                        outGrid, 60.0 / (inn.bpm.takeIf { it > 0 } ?: 120.0), inn.bpm, 60.0 / outGrid.beatSec,
                        out.bpm, alignedFold(out.bpm, inn.bpm), keys,
                    )
                    if (dropped == null) continue
                    val flagged = family.applyLoop(dropped)
                    return if (acapellaAllowed(out, inn)) flagged.copy(acapella = true) else flagged
                } else {
                    val e = echoBase() ?: return null
                    val flagged = family.applyLoop(e)
                    return if (acapellaAllowed(out, inn)) flagged.copy(acapella = true) else flagged
                }
            }
            val e = echoBase() ?: return null
            return if (acapellaAllowed(out, inn)) e.copy(acapella = true) else e
        }
        if (outOk && inOk) {
            val outBeat = outGrid.beatSec
            val inBeat = inGrid.beatSec
            val outBpm = 60.0 / outBeat
            val inBpm = 60.0 / inBeat
            val fold = alignedFold(outBpm, inBpm)
            val startRate = fold * inBeat / outBeat
            val bar = outBeat * 4.0
            val outTailVocal = vocalActivityBetween(out, max(0.0, effectiveOutDuration - 8 * bar), effectiveOutDuration)
            val inCue = cueOf(inn, sungIn)
            val inHeadVocal = vocalActivityBetween(inn, inCue, inCue + 8 * inBeat * 4)
            val windowClean = outTailVocal != null && inHeadVocal != null &&
                outTailVocal < VOCAL_ACTIVE_THRESHOLD && inHeadVocal < VOCAL_ACTIVE_THRESHOLD
            val vocal = out.vocalProbability >= 0.45 || inn.vocalProbability >= 0.45
            val cap = if (vocal && !windowClean) 0.06 else PhraseConfig.TEMPO_HARD_DELTA
            val bothSing = out.vocalProbability > 0.25 && inn.vocalProbability > 0.25 && !windowClean
            val outKeyConf = exitKeyConfidence(out)
            val inKeyConf = entryKeyConfidence(inn)
            val isKeyClash = steps != null && steps > 2 && outKeyConf >= 0.5 && inKeyConf >= 0.5
            // Key-clash pairs route to the filtered Beat blend (clash carrier,
            // 4–8 bars + bass swap), never to the hard-cut Drop which has no
            // clash filtering. Drop priority stands for non-clash pairs.
            if (!isKeyClash && abs(startRate - 1.0) <= 0.15 && out.beatConfidence >= 0.48 && inn.beatConfidence >= 0.48) {
                val dropped = dropPlan(
                    out, inn, effectiveOutDuration, earliestOutSec, sungOut, sungIn,
                    outGrid, inBeat, inBpm, outBeat, outBpm, fold, keys,
                )
                if (dropped != null) return if (acapellaAllowed(out, inn)) dropped.copy(acapella = true) else dropped
            }
            if (abs(startRate - 1.0) <= cap) {
                val blended = blendPlan(
                    out, outGrid, effectiveOutDuration, earliestOutSec, outBeat, inBeat, outBpm, inBpm, fold,
                    vocal, bothSing, windowClean, steps, keys, inn, sungIn, sungOut,
                )
                if (blended != null) return if (acapellaAllowed(out, inn)) blended.copy(acapella = true) else blended
            }
        }
        val finalEcho = echoPlan(out, inn, effectiveOutDuration, earliestOutSec, outGrid, inGrid, keys, sungIn, sungOut)
        return if (finalEcho != null && acapellaAllowed(out, inn)) finalEcho.copy(acapella = true) else finalEcho
    }

    private fun dropPlan(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outDuration: Double,
        earliestOutSec: Double,
        sungOut: List<Span>?,
        sungIn: List<Span>?,
        outGrid: Grid,
        inBeat: Double,
        inBpm: Double,
        outBeat: Double,
        outBpm: Double,
        fold: Double,
        keys: String,
    ): Plan? {
        val outEnd = outDuration - 0.5
        val outFirst = max(earliestOutSec, outDuration - OUT_SEARCH_SEC)
        val outroFloor = maxOf(outDuration * PhraseConfig.MIN_PLAY_FRACTION, out.outroStartTime.takeIf { it > 0.0 } ?: (outDuration - 32.0))
        val clean = firstClean(
            candidates = { bars ->
                if (bars < 2) emptyList()
                else loopsBetween(out.tailBeats, outGrid, outFirst, outEnd, bars).asReversed()
            },
            analysis = out,
            sung = sungOut,
            beatSec = outGrid.beatSec,
            count = Rejections(),
            outroFrom = outroFloor,
        ) ?: return null
        val bars = clean.bars.coerceIn(2, 4)
        val rampBars = (bars - 2).coerceAtLeast(1)
        val exitBars = (bars - 1 - rampBars).coerceAtLeast(0)
        val dropEdm = isEdmPair(outBpm, inBpm, fold) && clean.vocal < 0.06
        return Plan(
            out = Pick(clean.start, clean.end, clean.bars, outBeat, outBpm, clean.vocal, clean.checkedBy),
            inn = straightPick(cueOf(inn, sungIn), inBeat, inBpm),
            fold = fold,
            enterBars = 1,
            rampBars = rampBars,
            exitBars = exitBars,
            totalBars = 1 + rampBars + exitBars,
            outLoops = true,
            inLoops = false,
            nativeIncoming = true,
            rung = "Drop",
            keys = keys,
            flanger = dropEdm,
            buildupRoll = dropEdm,
            reverbWash = dropEdm,
            hpSweepEndHz = if (dropEdm) BUILDUP_HP_END_HZ else 0f,
        )
    }

    private fun blendPlan(
        out: TrackAnalysis,
        outGrid: Grid,
        outDuration: Double,
        earliestOutSec: Double,
        outBeat: Double,
        inBeat: Double,
        outBpm: Double,
        inBpm: Double,
        fold: Double,
        vocal: Boolean,
        bothSing: Boolean,
        windowClean: Boolean,
        steps: Int?,
        keys: String,
        inn: TrackAnalysis,
        sungIn: List<Span>?,
        sungOut: List<Span>? = null,
    ): Plan? {
        val bar = outBeat * 4
        val close = steps == null || steps <= 1
        val outKeyConf = exitKeyConfidence(out)
        val inKeyConf = entryKeyConfidence(inn)
        val keyClash = steps != null && steps > 2 && outKeyConf >= 0.5 && inKeyConf >= 0.5
        val room = (outDuration - earliestOutSec - 0.3).coerceAtLeast(0.0)
        var bars = when {
            keyClash && (bothSing || !windowClean) -> 4
            keyClash -> 8
            !bothSing && (windowClean || !vocal || close) -> 8
            else -> 4
        }
        while (bars > 4 && (bars * bar > 24.0 || bars * bar > room)) bars -= 2
        if (bars >= 4 && (bars * bar > 40.0 || room < 4 * bar)) {
            bars = if (room >= 2 * bar) 2 else return null
        }
        val needTempoRamp = abs((inBpm / fold) - outBpm) > 0.25
        val enter = if (bars <= 2 && needTempoRamp) 0 else 1
        val exit = 1
        val ramp = (bars - enter - exit).coerceAtLeast(if (bars <= 2 && !needTempoRamp) 0 else 1)
        val total = enter + ramp + exit
        val need = total * bar
        val effectiveEnd = out.contentEndTime.takeIf { it.isFinite() && it > earliestOutSec + need * 0.75 + 0.5 && it <= outDuration }
            ?: outDuration
        val rawStart = (effectiveEnd - need - 0.3).coerceAtLeast(earliestOutSec)
        val maxStart = min(outDuration - 0.5, outDuration - need * 0.75)
        val outStart = snapOutStart(out, outGrid, rawStart, earliestOutSec, maxStart, sungOut)
        if (outStart >= outDuration - 0.5 || outDuration - outStart < need * 0.75) return null
        // djmix24 Bug A4 (bridge path): deterministic EDM buildup on clean
        // instrumental blends; rotation otherwise. Never flange dual-vocal or key-clash.
        val blendEdm = !bothSing && !keyClash && windowClean && isEdmPair(outBpm, inBpm, fold)
        val blendFx = if (blendEdm) Quad(true, true, false, false)
        else if (bothSing || keyClash) Quad(false, false, false, false)
        else when (fxSlotForPair(keys, "$outBpm|$inBpm")) {
            0 -> Quad(true, false, false, false)
            1 -> Quad(false, false, false, false)
            2 -> Quad(false, false, false, false)
            else -> Quad(false, false, true, false)
        }
        return Plan(
            out = straightPick(outStart, outBeat, outBpm),
            inn = straightPick(cueOf(inn, sungIn), inBeat, inBpm),
            fold = fold,
            enterBars = enter,
            rampBars = ramp,
            exitBars = exit,
            totalBars = total,
            outLoops = false,
            inLoops = false,
            carve = bothSing || keyClash,
            clash = keyClash,
            rung = "Beat blend",
            keys = keys,
            flanger = blendFx.a,
            buildupRoll = blendFx.b,
            reverbWash = blendFx.c,
            hpSweepEndHz = if (blendFx.b) BUILDUP_HP_END_HZ else 0f,
            faderSwapBeats = 1.0,
        )
    }

    /**
     * djmix25 P1.6 gate (scalar mirror of TransitionMoves.shouldAcapella,
     * kept local so LoopMix stays acyclic): same Camelot, confident keys,
     * instrumental incoming intro, vocal outgoing.
     */
    private fun acapellaAllowed(out: TrackAnalysis, inn: TrackAnalysis): Boolean {
        val left = camelotCode(exitKeyForMix(out)) ?: return false
        val right = camelotCode(entryKeyForMix(inn)) ?: return false
        if (left != right) return false
        val conf = minOf(exitKeyConfidence(out), entryKeyConfidence(inn))
        if (conf < 0.6) return false
        if (inn.vocalProbability >= 0.45) return false
        if (out.vocalProbability < 0.45) return false
        return true
    }

    /**
     * djmix25 P1.5: mean instrumental share over [from]..[to]. Prefers the
     * explicit [TrackAnalysis.instrumentalWindows]; falls back to
     * 1 - vocal over [TrackAnalysis.vocalWindows]. Null when neither exists.
     */
    fun instrumentalShare(analysis: TrackAnalysis, from: Double, to: Double): Double? {
        val windows = analysis.instrumentalWindows
        if (windows.isNotEmpty()) {
            var sum = 0.0
            var count = 0
            for (w in windows) {
                for (i in w.values.indices) {
                    val time = w.startTime + i / w.frameRate
                    if (time < from || time > to) continue
                    sum += w.values[i].toDouble()
                    count++
                }
            }
            if (count > 0) return sum / count
        }
        val vocal = maskStats(analysis.vocalWindows, from, to)
        if (vocal != null) return 1.0 - vocal.mean
        return null
    }

    private fun echoPlan(
        out: TrackAnalysis,
        inn: TrackAnalysis,
        outDuration: Double,
        earliestOutSec: Double,
        outGrid: Grid?,
        inGrid: Grid?,
        keys: String,
        sungIn: List<Span>? = null,
        sungOut: List<Span>? = null,
    ): Plan? {
        val outBeat = outGrid?.beatSec ?: inGrid?.beatSec ?: return null
        val inBeat = inGrid?.beatSec ?: outBeat
        val outBpm = 60.0 / outBeat
        val inBpm = 60.0 / inBeat
        val bar = outBeat * 4
        val effectiveEnd = out.contentEndTime.takeIf {
            it.isFinite() && it > earliestOutSec + bar + 0.5 && it <= outDuration
        } ?: outDuration
        val rawStart = (effectiveEnd - 2 * bar - 0.3).coerceAtLeast(earliestOutSec)
        val maxStart = outDuration - bar - 0.2
        val outStart = if (outGrid != null) {
            snapOutStart(out, outGrid, rawStart, earliestOutSec, maxStart, sungOut)
        } else {
            out.downbeats.lastOrNull { beat ->
                beat.isFinite() && beat >= earliestOutSec && beat <= maxStart && beat <= rawStart + bar * 0.5
            } ?: rawStart
        }
        if (outStart >= outDuration - 0.5 || outStart + bar > outDuration - 0.2) return null
        val inCue = if (inGrid != null) {
            cueOf(inn, sungIn)
        } else {
            inn.audibleStartTime?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        }
        // True DJ Echo-Out (bkmasterplayer tempo_jump_plan + Apple AutoMix):
        // Song A stays clean through the 1-bar pre-cut window while echo + subtle
        // reverb pre-charge across the last 1.5-2 beats before swapAt (= bar),
        // dry cuts sharply on the downbeat, and Song B enters cleanly at 1.0x
        // while the echo/reverb tail rings out for 3-4 beats.
        return Plan(
            out = straightPick(outStart, outBeat, outBpm),
            inn = straightPick(inCue, inBeat, inBpm),
            fold = 1.0,
            enterBars = 1,
            rampBars = 0,
            exitBars = 1,
            totalBars = 2,
            outLoops = false,
            inLoops = false,
            nativeIncoming = true,
            echoTail = true,
            rung = "Echo",
            keys = keys,
            targetBpmOverride = outBpm,
            incomingHoldSec = bar,
            exitTailSeconds = PhraseConfig.exitTailSeconds(outBeat),
            buildupRoll = false,
            reverbWash = true,
        )
    }

    private fun snapOutStart(
        out: TrackAnalysis,
        outGrid: Grid,
        targetStart: Double,
        minStart: Double,
        maxStart: Double,
        sungOut: List<Span>? = null,
    ): Double {
        if (maxStart < minStart) return targetStart.coerceAtLeast(minStart)
        val candidates = ArrayList<Double>()
        val tailDowns = out.tailBeats?.downbeats.orEmpty()
        for (d in tailDowns) {
            if (d.isFinite() && d in minStart..maxStart) candidates += d
        }
        if (candidates.isEmpty()) {
            for (d in out.downbeats) {
                if (d.isFinite() && d in minStart..maxStart) candidates += d
            }
        }
        if (candidates.isEmpty()) {
            val tailBeats = out.tailBeats?.beats.orEmpty().filter { it.isFinite() }
            if (tailBeats.size >= 4) {
                var i = 0
                while (i < tailBeats.size) {
                    val b = tailBeats[i]
                    if (b in minStart..maxStart) candidates += b
                    i += 4
                }
            }
        }
        if (candidates.isEmpty() && outGrid.barSec > 0.1) {
            val bar = outGrid.barSec
            var t = outGrid.anchor + ceil((minStart - outGrid.anchor) / bar) * bar
            while (t <= maxStart) {
                if (t >= minStart) candidates += t
                t += bar
            }
        }
        if (candidates.isEmpty()) return targetStart.coerceIn(minStart, maxStart)
        fun isSung(c: Double): Boolean {
            if (sungOut == null) return false
            val from = c - LYRIC_GUARD_SEC
            return sungOut.any { it.start < c && it.certainUntil > from }
        }
        val barTol = outGrid.barSec * 0.25
        val preferred = candidates.filter { it <= targetStart + barTol }
        var snapped = (preferred.minByOrNull { abs(it - targetStart) }
            ?: candidates.minByOrNull { abs(it - targetStart) })!!
        // Never snap forward past the raw point: if the nearest
        // candidate is after the raw point, fall back to the last
        // candidate at or before the raw point (snap BACK only).
        if (snapped > targetStart + 1e-9) {
            val back = candidates.filter { it <= targetStart + 1e-9 }
            if (back.isNotEmpty()) {
                snapped = back.maxOrNull() ?: snapped
            }
        }
        // Refuse a sung snap entirely (keep raw) — never skip a verse/lyric
        // by scavenging an earlier bar.
        if (isSung(snapped)) return targetStart.coerceIn(minStart, maxStart)
        return snapped
    }

    // ---- 2B downbeat alignment, clock-consistency & envelope specs ----

    /**
     * Last downbeat of [out] at or before [raw] within one bar
     * ([barSec]), or [raw] when the grid is missing/ambiguous or the snap
     * would land inside a certainly-sung stretch. Never extends the blend
     * past [raw] (no skip of a verse by starting earlier than the raw point
     * only snaps BACK by < one bar — past the refused snap the raw stands).
     */
    fun snapToDownbeat(
        out: TrackAnalysis?,
        raw: Double,
        earliest: Double,
        barSec: Double,
        sungOut: List<Span>? = null,
    ): Double {
        if (out == null || !raw.isFinite()) return raw
        val bar = barSec.takeIf { it.isFinite() && it > 0.0 } ?: return raw
        val downs = out.downbeats
        if (downs.size < 2) return raw
        var snapped: Double? = null
        for (d in downs) {
            if (!d.isFinite()) continue
            if (d <= raw + 1e-3 && d >= earliest && raw - d <= bar + 1e-9) {
                snapped = d
            }
        }
        val s = snapped ?: return raw
        if (sungOut != null) {
            val from = s - LYRIC_GUARD_SEC
            if (sungOut.any { it.start < s && it.certainUntil > from }) return raw
        }
        return s
    }

    /**
     * Track seconds for [rampBars] of tempo ramp from [outBpm] toward
     * [targetBpm], integrated exactly as [Clock.beatTimes] integrates
     * `60 / tempo(k)` (with the `(k + 0.5)` sample). Non-positive ramps are
     * 0. `ramp*bar` (constant BPM) disagrees whenever the tempi differ;
     * this agrees with `Clock.plan.rampEnd - Clock.plan.enterEnd` by
     * construction (enterBars = 0 in compiled phrase plans).
     */
    fun rampHoldSec(outBpm: Double, targetBpm: Double, rampBars: Int): Double {
        if (rampBars <= 0) return 0.0
        val outSafe = outBpm.takeIf { it.isFinite() && it > 1.0 } ?: return 0.0
        val tgtSafe = targetBpm.takeIf { it.isFinite() && it > 1.0 } ?: outSafe
        val rampTo = rampBars * 4
        var t = 0.0
        var k = 0
        while (k < rampTo) {
            val tempo = outSafe + (tgtSafe - outSafe) * (k + 0.5) / rampTo.coerceAtLeast(1)
            t += 60.0 / (tempo.takeIf { it.isFinite() && it > 0.5 } ?: outSafe)
            k++
        }
        return t
    }

    /**
     * Validated slice of [side].beats over `loopStart..loopEnd`
     * (bars*4 beats + 1 point), or null when unsuitable. Valid means: finite,
     * strictly increasing, at least 0.25× and at most 1.5× the loop mean
     * interval apart, and pinned to both ends within half a beat. Anything
     * else (sparse, wandered, misshapen) leaves the existing synthetic
     * `loopStart + k*beatSec` path in force. Scalars only. Crown-loop only;
     * straight/hold/settle decks use LoopMixSession.trackedBeatsInWindow.
     */
    fun trackedAnchors(
        side: BeatSide?,
        loopStart: Double,
        loopEnd: Double,
        bars: Int,
    ): List<Double>? {
        val beats = side?.beats ?: return null
        val need = bars * 4 + 1
        if (beats.size < need || bars <= 0) return null
        if (!loopStart.isFinite() || !loopEnd.isFinite() || loopEnd <= loopStart) return null
        val beatSec = (loopEnd - loopStart) / (bars * 4)
        if (!(beatSec > 0.0) || !beatSec.isFinite()) return null
        var idx = -1
        var bestErr = Double.POSITIVE_INFINITY
        for (i in beats.indices) {
            val b = beats[i]
            if (!b.isFinite()) continue
            val e = abs(b - loopStart)
            if (e < bestErr) { bestErr = e; idx = i }
        }
        if (idx < 0 || bestErr > beatSec * 0.5 + 1e-9) return null
        if (idx + need > beats.size) return null
        val slice = ArrayList<Double>(need)
        var prev: Double? = null
        for (j in 0 until need) {
            val v = beats[idx + j]
            if (!v.isFinite()) return null
            val p = prev
            if (p != null) {
                val dt = v - p
                if (dt < beatSec * 0.25 || dt > beatSec * 1.5) return null
            }
            slice += v
            prev = v
        }
        if (abs(slice.first() - loopStart) > beatSec * 0.5 + 1e-9) return null
        if (abs(slice.last() - loopEnd) > beatSec * 0.5 + 1e-9) return null
        return slice
    }

    /**
     * Track seconds at each mix beat (0..[Clock.beats]) for [side]. Uses
     * [Pick.anchorBeats] when valid (monotonic coverage of the loop); the
     * synthetic `start + k*beatSec` otherwise. Pure so JVM tests can verify
     * envelope geometry without the native renderer. Looping decks wrap;
     * straight decks extrapolate at `beatSec` within the loop, then continue
     * at `beatSec` (the renderer's steady tail does the same past the last
     * knot). The incoming deck with fold 2 advances two beats per mix beat.
     * Crown-loop only; straight/hold/settle use LoopMixSession.buildDeckKnots.
     */
    fun knotTrackPositions(
        plan: Plan,
        clock: Clock,
        side: Side,
    ): DoubleArray {
        val pick = if (side == Side.OUT) plan.out else plan.inn
        val loops = if (side == Side.OUT) plan.outLoops else plan.inLoops
        val fold = if (side == Side.IN) plan.fold.takeIf { it.isFinite() && it > 0.0 } ?: 1.0 else 1.0
        val anchors = pick.anchorBeats
        val valid = anchors != null && validAnchors(anchors, pick) && loops &&
            (side == Side.OUT || (fold == 1.0 || fold == 2.0))
        val n = clock.beats + 1
        val out = DoubleArray(n)
        if (!valid) {
            for (k in 0 until n) {
                out[k] = when (side) {
                    Side.OUT -> clock.outPositionAt(k)
                    Side.IN -> clock.inPositionAt(k)
                }
            }
            return out
        }
        val anchorBeats = anchors!!.size - 1
        val loopLen = anchors.last() - anchors.first()
        for (k in 0 until n) {
            val step = when (side) {
                Side.OUT -> k.toDouble()
                Side.IN -> k.toDouble() * fold
            }
            val cycles = floor(step / anchorBeats)
            val idx = (step - cycles * anchorBeats).roundToInt().coerceIn(0, anchorBeats)
            out[k] = anchors.first() + cycles * loopLen + (anchors[idx] - anchors.first())
        }
        return out
    }

    /** Spec of the 2B echo-out envelopes, computed from the plan clock and consumed by `LoopMixSession.build`. Pure. */
    class EchoSpec(
        val cutAt: Double,
        val cutStart: Double,
        val cutEnd: Double,
        val outGainTimes: DoubleArray,
        val outGainValues: FloatArray,
        val inGainTimes: DoubleArray,
        val inGainValues: FloatArray,
        val inLpTimes: DoubleArray,
        val inLpValues: FloatArray,
        val echoSendTimes: DoubleArray,
        val echoSendValues: FloatArray,
        val reverbSendTimes: DoubleArray,
        val reverbSendValues: FloatArray,
        val echoFeedback: Float,
    )

    /**
     * True DJ echo-out geometry for a degenerate (`rampBars = 0`) echo plan:
     * A dry holds through the solo bar, cuts over about 100 ms ending on
     * [EchoSpec.cutAt], B rises at native tempo over at most one beat with a
     * low-pass opening, and both echo and reverb SENDS close at the cut so
     * only their repeats ring over B. The outgoing gain is exactly 0 at
     * `outStopAtSec` (exit gate) — built from [mixGainFall].
     */
    fun echoSpec(
        clock: Clock,
        plan: Plan,
        outStopAtSec: Double = Double.POSITIVE_INFINITY,
    ): EchoSpec {
        val cutAt = clock.rampEnd
        val cutStart = (cutAt - 0.10).coerceAtLeast(0.0)
        val cutEnd = cutAt
        val beatIn = plan.inn.beatSec.takeIf { it.isFinite() && it > 0.0 } ?: plan.out.beatSec
        val outFall = MixGain.fallPoints(cutStart, cutEnd)
        val inRise = MixGain.risePoints(cutAt, cutAt + beatIn)
        val stop = outStopAtSec.takeIf { it.isFinite() } ?: clock.releaseAt
        val h = HANDOVER_SEC
        val outTimes = DoubleArray(outFall.first.size + 3)
        val outVals = FloatArray(outFall.second.size + 3)
        outTimes[0] = -h; outVals[0] = 0f
        outTimes[1] = 0.0; outVals[1] = 1f
        outTimes[2] = cutStart; outVals[2] = 1f
        for (i in 1 until outFall.first.size) {
            outTimes[2 + i] = outFall.first[i]
            outVals[2 + i] = outFall.second[i]
        }
        outTimes[outTimes.size - 1] = stop.coerceAtLeast(cutAt + 1.0)
        outVals[outVals.size - 1] = 0f
        val inTimes = DoubleArray(inRise.first.size + 2)
        val inVals = FloatArray(inRise.second.size + 2)
        inTimes[0] = 0.0; inVals[0] = 0f
        inTimes[1] = cutAt; inVals[1] = 0f
        for (i in 1 until inRise.first.size) {
            inTimes[1 + i] = inRise.first[i]
            inVals[1 + i] = inRise.second[i]
        }
        inTimes[inTimes.size - 1] = clock.releaseAt
        inVals[inVals.size - 1] = 1f
        val lpTimes = doubleArrayOf(cutAt, cutAt + beatIn)
        val lpVals = floatArrayOf(900f, 18000f)
        val echoTimes = doubleArrayOf(0.0, cutStart, cutEnd)
        val echoVals = floatArrayOf(0f, PhraseConfig.ECHO_SEND_PEAK, 0f)
        val revTimes = doubleArrayOf(0.0, cutStart, cutEnd)
        val revVals = floatArrayOf(0f, 0.65f, 0f)
        val feedback = PhraseConfig.ECHO_FEEDBACK.coerceIn(0.50f, 0.58f)
        return EchoSpec(cutAt, cutStart, cutEnd, outTimes, outVals, inTimes, inVals, lpTimes, lpVals, echoTimes, echoVals, revTimes, revVals, feedback)
    }

    /** Spec of the vocal handoff scoop, in mix seconds; null when not sung. */
    class ScoopSpec(
        val outMidTimes: DoubleArray,
        val outMidValues: FloatArray,
        val inMidTimes: DoubleArray,
        val inMidValues: FloatArray,
        val inHighTimes: DoubleArray,
        val inHighValues: FloatArray,
    )

    /**
     * Mid scoop for a genuinely sung incoming vocal: the 4-beat mid scoop
     * ending at the landing, mapped through [Clock.beatTimes] (not raw beat
     * numbers used as seconds). Null when the entry is instrumental
     * ([leadInVocal] < 0.2 and no sung overlap), so instrumental pairs keep
     * `applyPhraseLanes` alone.
     */
    fun scoopSpec(
        clock: Clock,
        plan: Plan,
        leadInVocal: Float,
        vocalOverlapBeats: Double = 0.0,
    ): ScoopSpec? {
        val sung = leadInVocal >= 0.2f || vocalOverlapBeats >= 1.0
        if (!sung) return null
        fun beatToSec(b: Double): Double {
            val landing = (plan.rampBars + plan.exitBars).coerceAtLeast(1) * 4.0
            val beat = landing + b
            if (beat <= 0.0) return beat * plan.out.beatSec
            val last = clock.beatTimes.lastIndex
            val k = beat.toInt().coerceIn(0, (last - 1).coerceAtLeast(0))
            val frac = (beat - k).coerceIn(0.0, 1.0)
            val t0 = clock.beatTimes[k]
            val t1 = clock.beatTimes[(k + 1).coerceAtMost(last)]
            return t0 + (t1 - t0) * frac
        }
        val tM8 = beatToSec(-8.0)
        val tM4 = beatToSec(-4.0)
        val t0 = beatToSec(0.0)
        return ScoopSpec(
            outMidTimes = doubleArrayOf(tM8, tM4, t0),
            outMidValues = floatArrayOf(0f, -6f, -6f),
            inMidTimes = doubleArrayOf(tM8, t0),
            inMidValues = floatArrayOf(0f, 0f),
            inHighTimes = doubleArrayOf(tM8, t0),
            inHighValues = floatArrayOf(-1f, 0f),
        )
    }

    private fun validAnchors(anchors: List<Double>?, pick: Pick): Boolean {
        if (anchors == null) return false
        if (anchors.size < 2) return false
        val mean = pick.beatSec.takeIf { it.isFinite() && it > 0.0 } ?: return false
        for (i in anchors.indices) {
            if (!anchors[i].isFinite()) return false
            if (i > 0) {
                val dt = anchors[i] - anchors[i - 1]
                if (dt < mean * 0.25 || dt > mean * 1.5) return false
            }
        }
        return true
    }

    private fun straightPick(start: Double, beatSec: Double, bpm: Double) = Pick(
        loopStart = start,
        loopEnd = start,
        bars = 4,
        beatSec = beatSec,
        bpm = bpm,
        vocal = 0.0,
        checkedBy = Check.MASK,
    )

    internal fun firstAudibleTime(analysis: TrackAnalysis): Double {
        if (analysis.levelWindows.isNotEmpty()) {
            val values = analysis.levelWindows.flatMap { w -> w.values.map { it.toDouble() } }.filter { it.isFinite() }.sorted()
            val typical = if (values.isEmpty()) 0.0 else values[(values.size * 0.6).toInt().coerceAtMost(values.lastIndex)]
            val threshold = if (typical > SILENT_RMS) maxOf(SILENT_RMS, typical * 0.12) else SILENT_RMS
            for (w in analysis.levelWindows.sortedBy { it.startTime }) {
                for (i in w.values.indices) {
                    if (!w.values[i].isFinite()) continue
                    if (w.values[i] >= threshold) {
                        val t = w.startTime + i / w.frameRate
                        return if (t <= 0.25) 0.0 else t
                    }
                }
            }
            return 0.0
        }
        if (analysis.energyCurve.isNotEmpty()) {
            val peak = analysis.energyCurve.maxOf { it.energy }
            if (peak > 0.05) {
                val need = maxOf(0.03, peak * 0.08)
                for ((index, s) in analysis.energyCurve.withIndex()) {
                    if (s.energy >= need) {
                        return if (index == 0 || s.time <= 0.25) 0.0 else s.time
                    }
                }
            }
        }
        return 0.0
    }

    private fun isSilentAt(analysis: TrackAnalysis, t: Double): Boolean {
        if (!t.isFinite()) return false
        if (analysis.levelWindows.isNotEmpty()) {
            val rms = meanRms(analysis.levelWindows, t, t + 0.25)
            if (!rms.isNaN() && rms <= SILENT_RMS) return true
            if (!rms.isNaN() && rms > SILENT_RMS) return false
        }
        if (analysis.energyCurve.isNotEmpty()) {
            val near = analysis.energyCurve.filter { kotlin.math.abs(it.time - t) <= 0.3 }
            if (near.isNotEmpty()) {
                val maxE = near.maxOf { it.energy }
                if (maxE < 0.03) return true
            }
        }
        return false
    }

    internal fun cueOf(inn: TrackAnalysis, sungIn: List<Span>? = null): Double {
        val audible = maxOf(inn.audibleStartTime?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0, firstAudibleTime(inn))
        val downbeats = (inn.headBeats?.downbeats?.takeIf { it.isNotEmpty() } ?: inn.downbeats)
            .filter { it.isFinite() && it >= 0.0 }
            .sorted()
        val beats = inn.headBeats?.beats?.filter { it.isFinite() && it >= 0.0 }?.sorted().orEmpty()
        val beatSec = inn.headBeats?.bpm?.takeIf { it > 1.0 }?.let { 60.0 / it }
            ?: inn.beatInterval.takeIf { it.isFinite() && it > 0.05 }
            ?: inn.bpm.takeIf { it > 1.0 }?.let { 60.0 / it }
            ?: 0.5

        fun earliestVocalBefore(limitSec: Double): Double? {
            if (limitSec <= audible + 0.08) return null
            val lyricStart = sungIn
                ?.filter { it.certainUntil > audible + 0.05 && it.start < limitSec - 0.08 }
                ?.minOfOrNull { max(audible, it.start) }
            if (lyricStart != null) return lyricStart
            if (inn.vocalWindows.isEmpty()) return null
            val stats = maskStats(inn.vocalWindows, audible, limitSec)
            if (stats != null && (stats.share >= 0.20 || stats.longestRunSec >= 0.25)) {
                var earliest: Double? = null
                for (w in inn.vocalWindows) {
                    for (i in w.values.indices) {
                        val t = w.startTime + i / w.frameRate
                        if (t < audible || t >= limitSec - 0.08) continue
                        if (w.values[i] > VOCAL_ACTIVE_THRESHOLD) {
                            earliest = if (earliest == null) t else min(earliest, t)
                            break
                        }
                    }
                }
                return earliest
            }
            return null
        }

        val firstDown = downbeats.firstOrNull { it >= audible - 0.02 && !isSilentAt(inn, it) }
            ?: downbeats.firstOrNull { it >= audible - 0.02 }
        if (firstDown != null) {
            val vocalAt = earliestVocalBefore(firstDown)
            if (vocalAt == null) return firstDown
            // Lyrics/vocals already enter before firstDown: pick the closest
            // beat at or just before vocalAt (never cutting lyrics).
            val safeBeat = beats.lastOrNull { it >= audible - 0.05 && it <= vocalAt + 0.05 }
                ?: beats.firstOrNull { it >= audible - 0.05 && it <= firstDown }
            if (safeBeat != null && safeBeat <= vocalAt + 0.08) return safeBeat
            return audible
        }

        val firstBeatInList = beats.firstOrNull { it >= audible - 0.02 && !isSilentAt(inn, it) }
            ?: beats.firstOrNull { it >= audible - 0.02 }
        if (firstBeatInList != null) {
            val vocalAt = earliestVocalBefore(firstBeatInList)
            return if (vocalAt == null) firstBeatInList else audible
        }

        val anchor = inn.firstBeat.takeIf { it.isFinite() && it >= 0.0 }
        if (anchor != null) {
            val bar = beatSec * 4.0
            val barStart = if (anchor >= audible - 0.05) anchor else anchor + ceil((audible - 0.05 - anchor) / bar) * bar
            val vocalAt = earliestVocalBefore(barStart)
            return if (vocalAt == null) barStart else audible
        }
        return audible
    }

    private fun camelotPair(left: String, right: String): String {
        val a = camelotCode(left) ?: return ""
        val b = camelotCode(right) ?: return ""
        return "$a→$b"
    }

    enum class Side { OUT, IN }

    /**
     * Where a song is in its own seconds [t] seconds after t0, from the same beat
     * clock the decks run on (so it loops and follows the tempo ramp exactly).
     * Null where that side isn't sounding from a deck: either side before t0,
     * and the outgoing one once its loop stops.
     */
    fun positionAt(clock: Clock, side: Side, t: Double): Double? {
        val plan = clock.plan
        if (t < 0) return null
        if (side == Side.OUT && t >= clock.releaseAt) return null
        if (side == Side.IN && clock.incomingHoldSec > 0.0) {
            return clock.inTrackTimeAt(t)
        }
        if (side == Side.IN && t >= clock.releaseAt) {
            return when {
                plan.nativeIncoming -> plan.inn.loopStart + t
                plan.inLoops -> plan.inn.loopEnd + (t - clock.releaseAt)
                else -> plan.inn.loopStart + clock.beats * plan.fold * plan.inn.beatSec + (t - clock.releaseAt)
            }
        }
        val (k, frac) = clock.beatAt(t)
        if (k >= clock.beats) return null
        return if (side == Side.OUT) {
            if (plan.outLoops) plan.out.loopStart + ((k % plan.out.beats) + frac) * plan.out.beatSec
            else plan.out.loopStart + (k + frac) * plan.out.beatSec
        } else if (plan.nativeIncoming) {
            plan.inn.loopStart + t
        } else if (plan.inLoops) {
            plan.inn.loopStart + (((k * plan.fold) % plan.inn.beats) + frac * plan.fold) * plan.inn.beatSec
        } else {
            plan.inn.loopStart + (k + frac) * plan.fold * plan.inn.beatSec
        }
    }

    /** Exact PCM bounds [LoopMixSession.build] decodes for [plan]. Single source of truth for fast-ready checks. */
    data class RegionBounds(
        val outFrom: Double,
        val outTo: Double,
        val inFrom: Double,
        val inTo: Double,
    )
    /** Matches LoopMixSession.build: out 1.0s preroll + 0.5 seam + exitTail; in 1.0 preroll + element + 3.0 tail (+8 beats if echo). */
    fun regionBounds(plan: Plan): RegionBounds {
        val clock = Clock(plan)
        val echoHold = if (plan.echoTail) plan.out.beatSec * 8 else 0.0
        val outTo = if (plan.outLoops) {
            plan.out.loopEnd + 0.5 + plan.exitTailSeconds
        } else {
            val outStopMixSec = clock.releaseAt + 0.05 + echoHold + plan.exitTailSeconds
            val tailRate = if (clock.beats >= 1) {
                val b0 = clock.beatTimes[clock.beats - 1]
                val b1 = clock.beatTimes[clock.beats]
                (plan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
            } else 1.0
            val nominal = plan.out.loopStart + plan.totalBars * 4 * plan.out.beatSec + 0.5 + plan.exitTailSeconds
            val lastOutPos = plan.out.loopStart + clock.beats * plan.out.beatSec
            val tailTrack = (outStopMixSec - clock.releaseAt).coerceAtLeast(0.0) * tailRate
            val pastElement = plan.out.loopStart + clock.elementAt + maxOf(echoHold, plan.exitTailSeconds) + 0.5
            maxOf(nominal, lastOutPos + tailTrack + 0.5, pastElement)
        }
        val inTail = if (plan.echoTail) 3.0 + plan.out.beatSec * 8.0 else 3.0
        return RegionBounds(
            outFrom = plan.out.loopStart - 1.0,
            outTo = outTo,
            inFrom = plan.inn.loopStart - 1.0,
            inTo = clock.elementTrackTime + inTail,
        )
    }

    /** Where in its beat the mix is at [t], 0 on the beat and rising to 1; null outside the mix. */
    fun beatPhaseAt(clock: Clock, t: Double): Double? {
        if (t < 0 || t >= clock.beatTimes.last()) return null
        return clock.beatAt(t).second
    }
}
