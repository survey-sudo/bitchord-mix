package com.music.bitchord.playback.smart

import kotlin.math.log10
import kotlin.math.roundToLong

/**
 * Retire gate (djmix12): every classic/native handoff must pass through
 * silence before any player is stopped. Shared so the guard is unit
 * testable and identical on every call site (finish(), bail energy tail,
 * loop fallbacks).
 *
 * [audible] uses the linear gain the sinks mix with: below 0.001 the deck
 * is already quieter than -60 dB, so a stop cannot click. Everything else
 * must take the dissolve first.
 */
object RetireGuard {
    /** Linear -60 dB. */
    const val AUDIBLE_EPS = 0.001f

    fun audible(volume: Float): Boolean = volume > AUDIBLE_EPS

    /** Dissolve length when [planned] is false: the 350 ms emergency floor. */
    fun dissolveMs(planned: Boolean): Long =
        if (planned) PhraseConfig.EXIT_TAIL_MIN_MS.toLong() else PhraseConfig.EXIT_TAIL_EMERGENCY_MS.toLong()

    /** Gain in dBFS for telemetry; -inf past silence. */
    fun gainDb(gain: Float): Double = if (gain > 0f) 20.0 * log10(gain.toDouble()) else Double.NEGATIVE_INFINITY

    /** True when [volume] may be retired without a tail (silent or forced). */
    fun canRetire(volume: Float, force: Boolean = false): Boolean = force || !audible(volume)

    /**
     * djmix16: whether the spare/incoming player is actually audible
     * during LOOP_ARMING, for the retire guard.
     *
     * Before [LoopMixPort.tookOver] the outgoing sink has never spliced
     * to the renderer: the incoming player is prepared but
     * playWhenReady == false and its sink has never emitted a sample, so
     * it cannot be heard no matter what ExoPlayer.volume says (armLoop
     * sets volume = 1.0 while silent). Audible only when the loop has
     * taken over (or handed off) AND volume is above -60 dB AND the
     * player is actually playing. Pre-takeover volumes must be silenced
     * by the caller, never tailed.
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
     * djmix15 loop-completion scalars. Loop mixes store BPMs/tail in
     * [LoopMix.Plan], not in the (blank) TrackAnalysis pair, so a completed
     * loop would otherwise report 0.0 BPM and a stale tail:
     * - [loopOutBpm]/[loopInBpm]: prefer the analysed BPM, fall back to
     *   the armed plan's pick (never 0.0 when the plan is known).
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
