package com.music.bitchord.playback

internal enum class IdleStallAction { WAIT_AND_FREEZE, PREPARE_ONCE, FORCE_BAIL }

internal fun idleStallDecision(stalledMs: Long, outExhausted: Boolean, alreadyPrepared: Boolean): IdleStallAction = when {
    outExhausted -> IdleStallAction.FORCE_BAIL
    stalledMs >= 1500L && !alreadyPrepared -> IdleStallAction.PREPARE_ONCE
    stalledMs >= 2000L && alreadyPrepared -> IdleStallAction.FORCE_BAIL
    else -> IdleStallAction.WAIT_AND_FREEZE
}

/**
 * FADING-stall bound: [FADE_STEP_MS] ticks in [CrossfadeController.driveFade]
 * can hold `progress == 1` forever when the incoming player sits in
 * `STATE_BUFFERING` (elapsed frozen, so `incomingAt < 0.999` never reaches
 * `incomingReady`) and the outgoing file still has more than
 * [HANDOFF_GUARD_MS] left (so `outAboutToEof` never fires either). The outgoing
 * fader is already 0 at progress 1, so waiting changes nothing audible but
 * also never unblocks. At [FADING_STALL_BAIL_MS] of continuous buffering the
 * caller force-bails through the existing abnormal-exit path (survivor kept,
 * survivor filters reopened only once the retiree is silent — Seam A).
 * A user pause parks in `STATE_READY`, never `STATE_BUFFERING`, so an
 * intentional hold can never trip this. Pure scalar; unit-tested in
 * `FadingStallWatchdogTest`.
 */
internal const val FADING_STALL_BAIL_MS = 2_500L

internal fun fadingStallShouldBail(
    stalledMs: Long,
    progressAtLeastOne: Boolean,
    incomingReady: Boolean,
    outAboutToEof: Boolean,
    incomingBuffering: Boolean,
): Boolean =
    progressAtLeastOne && !incomingReady && !outAboutToEof && incomingBuffering &&
        stalledMs >= FADING_STALL_BAIL_MS

internal fun freezeDedupKey(phaseName: String, outgoingId: String?, incomingId: String?): String =
    "$phaseName|${outgoingId ?: ""}->${incomingId ?: ""}"
