package com.music.bitchord.playback.smart

/**
 * Deck-preparation budget (djmix13). Deadlines used to be
 * `elapsedRealtime() + (targetMs - positionMs)`, mixing the wall uptime
 * with the media timeline. While the player is paused (or while a seek
 * has frozen the position), the wall clock advances but the media clock
 * does not, so the "deadline" silently lay in the past and every later
 * verdict claimed bogus overruns (17 ms, 49012 ms, 136249 ms seen on
 * device). A deadline is only meaningful while the media clock is
 * actually running at playback speed; while paused there is no budget.
 */
object DeckBudget {
    /** Sentinel: no deadline in force (player paused). Never report lateness. */
    const val NO_DEADLINE_MS = Long.MAX_VALUE / 4

    /**
     * djmix16: wall-budget horizon. Loop lead is at most 40 s
     * (LOOP_PRIME_LEAD_MS) and classic arm at most 45 s
     * (PrepLead.MAX_MS, 2B); a wall deadline older than 45 s relative to
     * now can never be THIS build's deadline (stale prior-track epoch
     * or a media position mistaken for wall). The old 120 s horizon
     * let 116245 ms through (116 s < 120 s); the horizon grew 40 → 45 s
     * so the expanded 2B readiness lead never reads its own deadline as
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

    /** Proven media-time lateness of a span, in ms (0 when satisfied or paused). */
    // djmix15: deadlineMs == 0L is the uninitialized DeckTiming sentinel
    // (never a valid wall instant). It must never subtract as a wall epoch
    // (elapsedRealtime - 0 is the whole boot uptime, ~374000 ms on device).
    fun provenOverrunMs(endedMs: Long, deadlineMs: Long): Long =
        if (deadlineMs == NO_DEADLINE_MS || deadlineMs <= 0L) 0L else (endedMs - deadlineMs).coerceAtLeast(0L)

    /**
     * djmix16 horizon-aware proven lateness: wall [endedMs] vs wall
     * [deadlineMs] only, and only when the deadline is this build's
     * ([nowMs] - deadline within [LOOP_BUDGET_HORIZON_MS]). A stale
     * wall epoch (prior track) or a media position mistaken for wall
     * (e.g. loopTargetStartMs 150000 vs wall 500000) is older than the
     * horizon and reads as no budget, never as budget lateness.
     * Media lateness (mixSeconds) must never be passed here.
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
