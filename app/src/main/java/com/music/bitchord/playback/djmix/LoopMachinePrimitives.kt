package com.music.bitchord.playback.djmix

import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.TrackAnalysis

/**
 * Action taken when idle preparation stalls (M9 C-7).
 */
internal enum class IdleStallAction { WAIT_AND_FREEZE, PREPARE_ONCE, FORCE_BAIL }

internal fun idleStallDecision(stalledMs: Long, outExhausted: Boolean, alreadyPrepared: Boolean): IdleStallAction = when {
    outExhausted -> IdleStallAction.FORCE_BAIL
    stalledMs >= 1500L && !alreadyPrepared -> IdleStallAction.PREPARE_ONCE
    stalledMs >= 2000L && alreadyPrepared -> IdleStallAction.FORCE_BAIL
    else -> IdleStallAction.WAIT_AND_FREEZE
}

internal fun freezeDedupKey(phaseName: String, outgoingId: String?, incomingId: String?): String =
    "$phaseName|${outgoingId ?: ""}->${incomingId ?: ""}"

/**
 * Whether a session player paused by an audio-focus swap may be auto-resumed (djmix22, vc64).
 */
internal fun shouldRestorePlayAfterFocusSwap(wasPlaying: Boolean, userPaused: Boolean, focusHeld: Boolean): Boolean =
    wasPlaying && !userPaused && focusHeld

/**
 * What finish must do with an audible spare (djmix22).
 */
internal enum class LoopFinishAction { FINISH_LOOP, EMERGENCY_TAIL, FINISH }

/**
 * LoopMix completion routing (djmix22, vc64).
 */
internal fun loopFinishAction(
    phaseName: String,
    tookOver: Boolean,
    handedOff: Boolean,
    exitGateViolations: Int,
    audibleSpare: Boolean,
): LoopFinishAction = when {
    !audibleSpare -> LoopFinishAction.FINISH
    (phaseName == "LOOP_RUNNING" || phaseName == "LOOP_ARMING") && tookOver && handedOff &&
        exitGateViolations == 0 -> LoopFinishAction.FINISH_LOOP
    else -> LoopFinishAction.EMERGENCY_TAIL
}

/**
 * Session-adoption guard (djmix23, vc65).
 */
internal fun canAdoptSessionPlayer(loopIncomingStarted: Boolean): Boolean = loopIncomingStarted

/**
 * Evaluated candidate for phrase transition scoring (M9 C-7).
 */
internal class PhraseCandidate(
    val plan: LoopMix.Plan,
    val script: PhraseScript,
    val detail: String,
    val logKey: String,
    val locked: Boolean,
    val slides: Int,
    val lockedAtSec: Double?,
    val out: TrackAnalysis,
    val inn: TrackAnalysis,
)

/**
 * Synced lyrics per media id, once asked.
 */
internal class LyricSpans(
    val spans: List<LoopMix.Span>?,
    val cues: List<LyricCue> = emptyList(),
)
