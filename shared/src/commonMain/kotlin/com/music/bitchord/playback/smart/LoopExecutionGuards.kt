package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToLong

/**
 * Guards and budgets that decide whether a deck may be retired, how its tail
 * is spliced to dry, whether a plan survives a dry-run check, and when its
 * preparation must have finished.
 *
 * Everything here is scalar and pure: no allocation beyond small lists, no
 * locking, and therefore safe on the render short path that programs envelope
 * breakpoints from a worker thread.
 */

/**
 * Retire gate: every classic/native handoff must pass through silence before
 * any player is stopped. Shared so the guard is unit testable and identical on
 * every call site (finish(), bail energy tail, loop fallbacks).
 *
 * [audible] uses the linear gain the sinks mix with: below 0.001 the deck is
 * already quieter than -60 dB, so a stop cannot click. Everything else must
 * take the dissolve first.
 */
object RetireGuard {
    /** Linear -60 dB. */
    const val AUDIBLE_EPS = 0.001f

    fun audible(volume: Float): Boolean = volume > AUDIBLE_EPS

    /** Dissolve length when [planned] is false: the emergency floor. */
    fun dissolveMs(planned: Boolean): Long =
        if (planned) PhraseConfig.EXIT_TAIL_MIN_MS.toLong() else PhraseConfig.EXIT_TAIL_EMERGENCY_MS.toLong()

    /** Gain in dBFS for telemetry; -inf past silence. */
    fun gainDb(gain: Float): Double = if (gain > 0f) 20.0 * log10(gain.toDouble()) else Double.NEGATIVE_INFINITY

    /** True when [volume] may be retired without a tail (silent or forced). */
    fun canRetire(volume: Float, force: Boolean = false): Boolean = force || !audible(volume)

    /**
     * Whether the spare/incoming player is actually audible during
     * LOOP_ARMING, for the retire guard.
     *
     * Before [LoopMixPort.tookOver] the outgoing sink has never spliced to the
     * renderer: the incoming player is prepared but playWhenReady == false and
     * its sink has never emitted a sample, so it cannot be heard no matter what
     * ExoPlayer.volume says (armLoop sets volume = 1.0 while silent). Audible
     * only when the loop has taken over (or handed off) AND volume is above
     * -60 dB AND the player is actually playing. Pre-takeover volumes must be
     * silenced by the caller, never tailed.
     */
    fun loopArmingSpareAudible(
        tookOver: Boolean,
        handedOff: Boolean,
        volume: Float,
        playWhenReady: Boolean,
    ): Boolean {
        if (!tookOver && !handedOff) return false
        return audible(volume) && playWhenReady
    }

    /** True when a LOOP_ARMING spare may retire without a tail. */
    fun canRetireLoopArmingSpare(
        tookOver: Boolean,
        handedOff: Boolean,
        volume: Float,
        playWhenReady: Boolean,
        force: Boolean = false,
    ): Boolean = force || !loopArmingSpareAudible(tookOver, handedOff, volume, playWhenReady)

    /**
     * Loop-completion scalars. Loop mixes store BPMs/tail in [LoopMix.Plan], not
     * in the (blank) TrackAnalysis pair, so a completed loop would otherwise
     * report 0.0 BPM and a stale tail:
     * - [loopOutBpm]/[loopInBpm]: prefer the analysed BPM, fall back to the
     *   armed plan's pick (never 0.0 when the plan is known).
     * - [loopTailMs]: the plan's graceful-exit dissolve in ms.
     * Scalars only; no audio crosses.
     */
    fun loopOutBpm(analysedBpm: Double, planOutBpm: Double): Double =
        analysedBpm.takeIf { it > 0 } ?: planOutBpm.takeIf { it > 0 } ?: 0.0

    fun loopInBpm(analysedBpm: Double, planInBpm: Double): Double =
        analysedBpm.takeIf { it > 0 } ?: planInBpm.takeIf { it > 0 } ?: 0.0

    fun loopTailMs(exitTailSeconds: Double): Long =
        (exitTailSeconds * 1000.0).roundToLong()
}

/**
 * Release-to-dry splice: a smooth crossfade between wet echo or reverb tail and
 * dry signal at transition boundaries, so the handback never clicks. Orchard
 * keeps native playback untouched around a glide and fades into the rendered
 * output over SPLICE_FRAMES (earmark render stage.rs, 1024 frames) with a
 * 0.25 s native edge. BitChord applies the same shape on the mix clock: hold
 * the wet signal at unity, then a cosine equal-power fall to zero over 0.25 s,
 * then dry signal only.
 */
object ReleaseSplice {
    /** Width of the cosine handback window, in seconds (Orchard NATIVE_EDGE_SECONDS). */
    const val SPLICE_SECONDS = 0.25

    /** Frames in the splice window at the given sample rate. */
    fun spliceFrames(sampleRate: Int): Long =
        if (sampleRate > 0) (SPLICE_SECONDS * sampleRate).toLong() else 0L

    /**
     * Wet weight at one step of the fall: 1 at step zero, 0 at totalSteps,
     * cosine in between. A cosine has flat ends, so the splice meets both
     * full-wet and full-dry without a gain kink.
     */
    fun spliceWeight(stepIndex: Int, totalSteps: Int): Double {
        if (totalSteps <= 0) return 1.0
        if (stepIndex <= 0) return 1.0
        if (stepIndex >= totalSteps) return 0.0
        val t = stepIndex.toDouble() / totalSteps.toDouble()
        return 0.5 * (1.0 + cos(PI * t))
    }

    /**
     * Wet gain at mix second mixSec when the dry release starts at releaseSec
     * and the cosine fall spans widthSec. Unity before the release, zero once
     * the window has passed.
     */
    fun spliceGain(mixSec: Double, releaseSec: Double, widthSec: Double = SPLICE_SECONDS): Double {
        if (widthSec <= 0.0) return 0.0
        if (mixSec < releaseSec) return 1.0
        if (mixSec >= releaseSec + widthSec) return 0.0
        val t = (mixSec - releaseSec) / widthSec
        return 0.5 * (1.0 + cos(PI * t))
    }

    /**
     * Breakpoints for a wet-signal envelope that holds unity until releaseSec
     * and splices to zero over widthSec: (time, value) pairs. steps slices the
     * cosine, so a four-step splice reads as the linear three-point form plus
     * shaped interior points. Always ends at zero.
     */
    fun envelopePoints(releaseSec: Double, widthSec: Double = SPLICE_SECONDS, steps: Int = 4): List<Pair<Double, Double>> {
        val points = ArrayList<Pair<Double, Double>>(steps + 2)
        points.add(releaseSec to 1.0)
        val n = if (steps < 1) 1 else steps
        for (step in 1 until n) {
            val t = step.toDouble() / n.toDouble()
            points.add((releaseSec + widthSec * t) to (0.5 * (1.0 + cos(PI * t))))
        }
        points.add((releaseSec + widthSec) to 0.0)
        return points
    }

    /**
     * Envelope times/values arrays shaped for the native renderer setEnvelope
     * call: the release at releaseSec spliced dry over widthSec.
     */
    fun envelopeArrays(releaseSec: Double, widthSec: Double = SPLICE_SECONDS, steps: Int = 4): Pair<DoubleArray, DoubleArray> {
        val points = envelopePoints(releaseSec, widthSec, steps)
        val times = DoubleArray(points.size)
        val values = DoubleArray(points.size)
        for ((index, point) in points.withIndex()) {
            times[index] = point.first
            values[index] = point.second
        }
        return times to values
    }
}

/**
 * Post-plan dry-run verification (opensonic-style): scalars only. Checks
 * loudness delta, peak ceiling, phase error and exit gates.
 */
object MixVerify {
    const val MAX_LUFS_DELTA_DB = 3.0
    const val MAX_PEAK_LINEAR = 0.896f
    const val MAX_PHASE_ERROR_MS = 25.0
    data class VerifyResult(val ok: Boolean, val reasons: List<String>)
    fun verify(
        lufsDeltaDb: Double,
        peakLinear: Float,
        phaseErrorMs: Double,
        exitGateViolations: Int,
    ): VerifyResult {
        val reasons = ArrayList<String>()
        if (kotlin.math.abs(lufsDeltaDb) > MAX_LUFS_DELTA_DB) reasons.add("lufs-delta " + lufsDeltaDb)
        if (peakLinear > MAX_PEAK_LINEAR) reasons.add("peak " + peakLinear)
        if (kotlin.math.abs(phaseErrorMs) > MAX_PHASE_ERROR_MS) reasons.add("phase " + phaseErrorMs)
        if (exitGateViolations != 0) reasons.add("exit-gates " + exitGateViolations)
        return VerifyResult(reasons.isEmpty(), reasons)
    }
}

/**
 * Deck-preparation budget. A deadline is `now + (target - position)/speed`;
 * mixing the wall uptime with the media timeline was wrong because while the
 * player is paused (or a seek has frozen the position) the wall clock advances
 * but the media clock does not, so the "deadline" silently lay in the past and
 * every later verdict claimed bogus overruns (17 ms, 49012 ms, 136249 ms seen
 * on device). A deadline is only meaningful while the media clock is actually
 * running at playback speed; while paused there is no budget.
 */
object DeckBudget {
    /** Sentinel: no deadline in force (player paused). Never report lateness. */
    const val NO_DEADLINE_MS = Long.MAX_VALUE / 4

    /**
     * Wall-budget horizon. Loop lead is at most 40 s (LOOP_PRIME_LEAD_MS) and
     * classic arm at most 45 s (PrepLead.MAX_MS); a wall deadline older than
     * 45 s relative to now can never be THIS build's deadline (stale
     * prior-track epoch or a media position mistaken for wall). The horizon
     * matches the longest readiness lead so a live deadline never reads as
     * stale.
     */
    const val LOOP_BUDGET_HORIZON_MS = 45_000L

    /**
     * Wall-clock instant by which preparation must be done, or
     * [NO_DEADLINE_MS] when no budget applies.
     *
     * @param nowMs wall clock (elapsedRealtime)
     * @param playing whether the media position is advancing
     * @param targetStartMs media time of the planned loop start
     * @param positionMs current media position
     * @param speed playback speed (>0); <=0 is treated as paused
     */
    fun deadlineMs(
        nowMs: Long,
        playing: Boolean,
        targetStartMs: Long,
        positionMs: Long,
        speed: Float = 1f,
    ): Long {
        if (!playing || speed <= 0f) return NO_DEADLINE_MS
        val remaining = targetStartMs - positionMs
        if (remaining <= 0L) return nowMs
        return nowMs + (remaining / speed.toDouble()).toLong()
    }

    /**
     * Proven media-time lateness of a span, in ms (0 when satisfied or paused).
     * deadlineMs == 0L is the uninitialized DeckTiming sentinel (never a valid
     * wall instant); it must never subtract as a wall epoch
     * (elapsedRealtime - 0 is the whole boot uptime, ~374000 ms on device).
     */
    fun provenOverrunMs(endedMs: Long, deadlineMs: Long): Long =
        if (deadlineMs == NO_DEADLINE_MS || deadlineMs <= 0L) 0L else (endedMs - deadlineMs).coerceAtLeast(0L)

    /**
     * Horizon-aware proven lateness: wall [endedMs] vs wall [deadlineMs] only,
     * and only when the deadline is this build's ([nowMs] - deadline within
     * [LOOP_BUDGET_HORIZON_MS]). A stale wall epoch (prior track) or a media
     * position mistaken for wall (e.g. loopTargetStartMs 150000 vs wall
     * 500000) is older than the horizon and reads as no budget, never as
     * budget lateness. Media lateness (mixSeconds) must never be passed here.
     */
    fun provenOverrunMs(endedMs: Long, deadlineMs: Long, nowMs: Long): Long {
        if (deadlineMs == NO_DEADLINE_MS || deadlineMs <= 0L) return 0L
        if (nowMs - deadlineMs > LOOP_BUDGET_HORIZON_MS) return 0L
        return (endedMs - deadlineMs).coerceAtLeast(0L)
    }

    /** True when [span] provably ran past an in-force deadline. */
    fun isProvablyLate(span: LoopMix.PrepSpan): Boolean = provenOverrunMs(span.endedMs, span.deadlineMs) > 0L

    /** Horizon-aware variant: stale wall epochs never count. */
    fun isProvablyLate(span: LoopMix.PrepSpan, nowMs: Long): Boolean =
        provenOverrunMs(span.endedMs, span.deadlineMs, nowMs) > 0L
}

/**
 * How early the next deck is armed, from NullMusic's adaptive pre-arm.
 *
 * The base is 20 seconds. A preparation that took longer than 8 seconds pushes
 * the next one out, up to 45 seconds. A fast preparation walks it back toward
 * the base. Decode of a loop mix still starts as soon as the plan exists; this
 * only moves the moment the sinks are armed.
 */
object PrepLead {
    const val BASE_MS = 20_000L
    // Plausible 35–45 s pre-arming. The worst-case cold prime + classic
    // standby must fully buffer inside this; forcing a larger lead than 45 s
    // would thrash cold builds with no extra benefit.
    const val MAX_MS = 45_000L
    const val SLOW_MS = 8_000L

    fun next(previousLeadMs: Long, previousPrepMs: Long): Long {
        val prev = previousLeadMs.coerceIn(BASE_MS, MAX_MS)
        val prep = previousPrepMs.coerceAtLeast(0L)
        if (prep > SLOW_MS) return (prev + (prep - SLOW_MS)).coerceAtMost(MAX_MS)
        val room = SLOW_MS - prep
        val step = if (room > 2_000L) 2_000L else room
        val lowered = prev - step
        return if (lowered < BASE_MS) BASE_MS else lowered
    }
}
