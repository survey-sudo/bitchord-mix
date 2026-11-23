package com.music.bitchord.playback.smart

/**
 * Scrub handling. The phase the controller is in when the user
 * seeks decides what the (possible) half-armed mix becomes:
 *
 * - Pre-audible (IDLE, ARMING, LOOP_ARMING before splice takeover): cancel
 *   cleanly, then replan from the NEW position. The marker/countdown is
 *   cleared so a stale plan can never fire. NO 350 ms retire-guard dip on
 *   an inaudible player — the teardown is silent and instant.
 * - Audible (FADING, TAIL, LOOP_RUNNING / tookOver): emergency 350 ms tail
 *   on the spare (or renderer fade), never an instant retire and never a
 *   double-fire. Seeking mid-mix dissolves gracefully, then the playhead
 *   rests at the scrub target and replans from IDLE.
 *
 * LOOP is kept for backward compatibility with older call sites and maps
 * to pre-audible CANCEL. New code uses LOOP_ARMING / LOOP_RUNNING.
 */
object SeekAction {
    enum class Phase { IDLE, ARMING, FADING, TAIL, LOOP, LOOP_ARMING, LOOP_RUNNING, BAILING, SLEEP }

    enum class Action { CANCEL, HONOUR, EMERGENCY_TAIL }

    fun decide(phase: Phase, markerShown: Boolean): Action = when (phase) {
        Phase.FADING -> Action.EMERGENCY_TAIL
        // TAIL-phase seeks honour the planned tail dissolve instead of
        // stacking an emergency 350 ms tail on top of it. The 4x exit.tail
        // cascade comes from re-issuing EMERGENCY_TAIL while already
        // dissolving; HONOUR lets the in-flight tail finish.
        Phase.TAIL -> Action.HONOUR
        Phase.LOOP_RUNNING -> Action.EMERGENCY_TAIL
        Phase.IDLE, Phase.ARMING, Phase.LOOP, Phase.LOOP_ARMING, Phase.BAILING, Phase.SLEEP -> Action.CANCEL
    }

    /**
     * A shown plan must be invalidated once the playhead moved across its
     * mix-start since it was shown. Returns true when it may still fire.
     */
    fun planStillValid(planStartMs: Long, positionMs: Long): Boolean = positionMs <= planStartMs

    /**
     * Queue-edit decision for the "next vs plan wanted" check.
     * After the handoff the session IS the incoming track (i+1), so its
     * "next" is i+2 by definition — comparing that to the armed expected
     * (i+1) is a certain false queueEdit ~1.7-6.9 s after every classic
     * startFade. Only the pre-handoff window treats a mismatch as a user
     * edit; the current-index check still catches an active-track removal.
     */
    fun isNextEdit(handedOff: Boolean, nextId: String?, expected: String?): Boolean =
        !handedOff && expected != null && nextId != expected

    /**
     * Spurious DISCONTINUITY_REASON_SEEK events (0 -> 0,
     * same-position prepare/rendition seeks, or tiny <= 1200 ms non-user seeks
     * on the same item while IDLE) must never trigger resetPlansForScrub().
     */
    fun shouldIgnoreSeek(
        phase: Phase,
        oldPositionMs: Long,
        newPositionMs: Long,
        sameMediaItem: Boolean = true,
    ): Boolean {
        if (oldPositionMs < 0L || newPositionMs < 0L) return false
        if (oldPositionMs == newPositionMs) return true
        if (phase == Phase.IDLE && sameMediaItem && kotlin.math.abs(newPositionMs - oldPositionMs) <= SPURIOUS_IDLE_SEEK_MS) {
            return true
        }
        return false
    }

    /**
     * When EMERGENCY_TAIL runs on an audible LOOP_RUNNING
     * session and a 350 ms native fade-out was scheduled on the renderer, defer
     * disarming the retiring port until the fade finishes instead of disarming
     * instantaneously in loopBail.
     */
    fun shouldDeferLoopDisarm(deferRequested: Boolean, isLoopRunning: Boolean): Boolean =
        deferRequested && isLoopRunning

    const val SPURIOUS_IDLE_SEEK_MS = 1_200L
}
