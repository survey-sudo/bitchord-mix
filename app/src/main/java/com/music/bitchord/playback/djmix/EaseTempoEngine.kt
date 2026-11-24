package com.music.bitchord.playback.djmix

import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.playback.TransitionFilters
import com.music.bitchord.playback.smart.BUILDUP_RISE
import com.music.bitchord.playback.smart.MAX_INCOMING_RATE
import com.music.bitchord.playback.smart.MIN_INCOMING_RATE
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.deckSpeed
import com.music.bitchord.playback.smart.releasableRate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Narrow host seam supplying playback players, clocks, coroutine scope, and
 * filter tempo sinks to [EaseTempoEngine] without exposing controller internals.
 *
 * Thread owner: invoked from the main/controller thread.
 */
internal interface EaseTempoHost {
    val scope: CoroutineScope
    val filters: TransitionFilters
    val tracks: TransitionFilters
    fun active(): ExoPlayer
    fun incoming(): ExoPlayer?
    fun isHandedOff(): Boolean
    fun isIdle(): Boolean
    fun nowMs(): Long
    fun positionMs(player: ExoPlayer): Double
    fun userPlaybackSpeed(): Float
    fun inParty(): Boolean
}

/**
 * Owns beatmatch deck-speed discipline, pre-fade outgoing tempo ramping,
 * buildup speed lift, post-transition tempo ease-back with clickless
 * AudioTrack gain-notch transitions, phase-lock pulse state, and beat-grid
 * phase lookup (M9 slice C-2).
 *
 * Responsibility: tempo state lifecycle, player speed updates, pitch-corrector
 * synchronization, and beat-in-bar lookup (`beatAt`).
 * Inputs: transition rates/timings from controller arm/fade/finish/tick hooks
 * and live playback state via [EaseTempoHost].
 * Thread owner: main/controller thread ([MainThread]). `setPlaybackSpeedClickless`
 * launches its short gain-notch coroutine on [EaseTempoHost.scope] so all
 * `ExoPlayer` volume/speed mutations stay on the controller's thread.
 */
@OptIn(UnstableApi::class)
internal class EaseTempoEngine(private val host: EaseTempoHost) {

    // ---- Phase lock state ---------------------------------------------------

    /** When the speed nudge in flight ends, from [EaseTempoHost.nowMs]; 0 when none is. */
    private var nudgeUntil = 0L

    /** No phase is measured before this: the players are settling after a start or a nudge. */
    private var lockQuietUntil = 0L

    /** Phase errors, in incoming seconds, gathered until there are enough to take a median of. */
    private val lockErrors = DoubleArray(LOCK_SAMPLES)
    private var lockCount = 0

    /** [beatAt]'s answer — seconds past the last beat, and that beat's length — without allocating. */
    private val beatScratch = DoubleArray(2)

    /** Seconds past the last beat from the most recent successful [beatAt] call. */
    val beatSinceSec: Double get() = beatScratch[0]

    /** Length of the local beat in seconds from the most recent successful [beatAt] call. */
    val beatIntervalSec: Double get() = beatScratch[1]

    // ---- Outgoing ramp state ------------------------------------------------

    /** The outgoing speed-up ramp: steps in all, one beat of outgoing media each, from [rampStartMs]. */
    private var rampSteps = 0
    private var rampStepMs = 0L
    private var rampStartMs = 0L
    private var rampStep = 0

    // ---- Tempo ease-back & buildup state ------------------------------------

    /** The player still coming off a beatmatch stretch after its blend finished, or null. */
    private var easing: ExoPlayer? = null
    private var easeOnActive = false
    private var easeRate = 1.0
    private var easeStep = 0.0
    private var easeStepMs = 0L
    private var easeNextAtMs = 0L
    private var easeItemIndex = 0
    private var softSpeedJob: Job? = null
    private var softSpeedPlayer: ExoPlayer? = null
    private var softSpeedLevel = 1f
    private var softSpeedTarget = 1f
    private var softSpeedUserSpeed = 1f
    private var softSpeedItemIndex = 0
    private var softSpeedItem: MediaItem? = null
    private var softSpeedGeneration = 0L

    /** Last speed written for a buildup, so the rise steps instead of clicking every tick. */
    private var buildupSpeed = 1f

    // ---- Transition lifecycle hooks -----------------------------------------

    /**
     * Initializes the pre-fade outgoing speed-up ramp schedule and returns the
     * total ramp lead duration in milliseconds to add to the arming deadline.
     */
    @MainThread
    fun configureOutgoingRamp(
        outgoingRate: Double,
        beatMs: Long,
        endMs: Long,
        fadeMs: Long,
    ): Long {
        rampSteps = if (beatMs > 0L) rampStepsFor(outgoingRate) else 0
        rampStepMs = beatMs
        rampStartMs = endMs - fadeMs - rampSteps * beatMs
        rampStep = 0
        return rampSteps * beatMs
    }

    /**
     * Configures the standby incoming player's initial beatmatch playback speed
     * while silent and primes the buildup speed tracker.
     */
    @MainThread
    fun prepareIncomingSpeed(
        into: ExoPlayer,
        incomingPlaybackRate: Double,
        localBlendInFlight: Boolean,
    ) {
        into.setPlaybackSpeed(incomingBaseSpeed(incomingPlaybackRate, localBlendInFlight))
        buildupSpeed = into.playbackParameters.speed
        host.tracks.incomingTempo(1f)
    }

    /** Advances the outgoing speed-up ramp during arming when active. */
    @MainThread
    fun maybeRampOutgoing(out: ExoPlayer, outgoingPlaybackRate: Double) {
        if (rampSteps > 0) rampOutgoing(out, outgoingPlaybackRate)
    }

    /**
     * Resets phase-lock settling on fade start and snaps any unfinished
     * outgoing tempo ramp onto [outgoingPlaybackRate] so the blend begins in sync.
     */
    @MainThread
    fun onFadeStart(out: ExoPlayer, fadeStartedAt: Long, outgoingPlaybackRate: Double) {
        nudgeUntil = 0L
        lockCount = 0
        // The incoming player's clock is still settling off its start.
        lockQuietUntil = fadeStartedAt + LOCK_START_MS
        // However the ramp went — a late arm, a slow buffer — the blend starts
        // on the incoming tempo.
        if (rampSteps > 0 && rampStep < rampSteps) {
            rampStep = rampSteps
            out.setPlaybackSpeed(deckSpeedSafe(host.userPlaybackSpeed(), outgoingPlaybackRate))
            host.tracks.outgoingTempo(1f)
        }
    }

    /**
     * Cancels any pending ease/nudge at midpoint handover and returns [into] to
     * listener speed while the outgoing tail still masks the parameter change
     * (except for locally admitted blends, which finish at their mapped rate).
     */
    @MainThread
    fun onHandover(into: ExoPlayer, localBlendInFlight: Boolean) {
        // Never call setPlaybackSpeed after handover with WSOLA steps across B's
        // solo intro; reset once here while the outgoing tail still masks it.
        easing = null
        easeOnActive = false
        cancelPendingSpeedChange()
        nudgeUntil = 0L
        lockCount = 0
        // A locally admitted blend keeps its measured source mapping through
        // the remaining overlap; finish() releases it once.
        if (!localBlendInFlight) {
            into.setPlaybackSpeed(deckSpeedSafe(host.userPlaybackSpeed(), 1.0, beatmatched = false))
        }
        host.tracks.incomingTempo(1f)
    }

    /** Clears pending phase nudge before teardown evaluates handoff branch. */
    fun clearPhaseNudge() {
        nudgeUntil = 0L
    }

    /** Re-asserts unit mix speed on the surviving handed-off [into] deck at finish without stepping. */
    @MainThread
    fun onHandedOffFinish(into: ExoPlayer) {
        easing = null
        cancelPendingSpeedChange()
        nudgeUntil = 0L
        into.setPlaybackSpeed(deckSpeedSafe(host.userPlaybackSpeed(), 1.0, beatmatched = false))
        host.filters.incomingTempo(1f)
    }

    /** Resets outgoing ramp step count when a transition finishes. */
    fun resetRamp() {
        rampSteps = 0
    }

    // ---- Phase lock & buildup -----------------------------------------------

    /**
     * Keeps the incoming track's beats on the outgoing track's, the way a DJ
     * rides the jog wheel: finishes any active nudge pulse cleanly, while new
     * micro-speed pulsing remains disabled to avoid Sonic WSOLA stretcher
     * stutters on live vocals and beats.
     */
    @MainThread
    fun holdPhase(
        out: ExoPlayer,
        into: ExoPlayer,
        progress: Float,
        incomingPlaybackRate: Double,
        mediaRatio: Double,
        localBlendInFlight: Boolean,
        outgoingAnalysis: TrackAnalysis,
        incomingAnalysis: TrackAnalysis,
    ) {
        val now = host.nowMs()
        if (nudgeUntil != 0L) {
            // A pause mid-pulse ends it early; the next reading picks up the rest.
            if (now < nudgeUntil && into.isPlaying) return
            nudgeUntil = 0L
            into.setPlaybackSpeed(incomingBaseSpeed(incomingPlaybackRate, localBlendInFlight))
            host.tracks.incomingTempo(1f)
            lockQuietUntil = now + LOCK_SETTLE_MS
            lockCount = 0
            return
        }
        // Phase-lock micro-speed pulsing disabled: repeated +/-3% setPlaybackSpeed
        // writes stutter the Sonic WSOLA stretcher on live vocals/beats. Hold the
        // incoming deck at its beatmatch rate for the whole blend.
        return
        // Once the outgoing track is on its way out its beat stops mattering.
        @Suppress("UNREACHABLE_CODE")
        if (progress >= LOCK_UNTIL || now < lockQuietUntil) return
        if (!out.isPlaying || !into.isPlaying) {
            lockCount = 0
            return
        }
        val error = phaseError(out, into, mediaRatio, outgoingAnalysis, incomingAnalysis)
        if (error.isNaN()) return
        lockErrors[lockCount++] = error
        if (lockCount < LOCK_SAMPLES) return
        lockCount = 0
        lockErrors.sort()
        val median = lockErrors[LOCK_SAMPLES / 2]
        if (abs(median) < LOCK_DEADBAND_SECONDS) {
            lockQuietUntil = now + LOCK_IN_PHASE_MS
            return
        }
        val base = incomingBaseSpeed(incomingPlaybackRate, localBlendInFlight)
        val factor = if (median > 0) {
            1f - NUDGE
        } else {
            minOf(1f + NUDGE, (MAX_TOTAL_SPEED_UP / incomingPlaybackRate).toFloat())
        }
        if (factor <= 1f && median < 0) return
        into.setPlaybackSpeed(deckSpeedSafe(host.userPlaybackSpeed(), incomingPlaybackRate * factor))
        host.tracks.incomingTempo(1f)
        nudgeUntil = now + (abs(median) / (base * abs(factor - 1f)) * 1000).roundToLong().coerceIn(1L, MAX_NUDGE_MS)
    }

    /**
     * The buildup: speed and pitch rise together by [BUILDUP_RISE] across the
     * overlap in ~1% steps rather than writing on every tick.
     */
    @MainThread
    fun rideBuildup(
        into: ExoPlayer?,
        buildup: Boolean,
        handedOff: Boolean,
        localBlendInFlight: Boolean,
        incomingPlaybackRate: Double,
        progress: Float,
    ) {
        if (into == null || !buildup || handedOff || localBlendInFlight) return
        val lift = BUILDUP_RISE * progress.toDouble() * progress.toDouble()
        // Clamp the beatmatch rate to [0.92f, 1.08f] before the buildup lift so
        // the tempo lift can never push a deck past the hard ceiling.
        val target = deckSpeed(host.userPlaybackSpeed(), safeRateForDeck(incomingPlaybackRate) + lift)
        if (abs(target - buildupSpeed) < 0.008f) return
        buildupSpeed = target
        into.setPlaybackSpeed(target)
    }

    /**
     * Where [positionSec] falls in [analysis]'s beat: seconds past the last
     * beat into `beatScratch[0]` ([beatSinceSec]), that beat's length into
     * `beatScratch[1]` ([beatIntervalSec]). False when the track has no grid.
     *
     * Beats are counted within the bar the analyzer's downbeats bound, not off
     * one constant interval from the first beat, so a track that drifts still
     * reads its own local beat rather than one that slid minutes ago.
     */
    fun beatAt(analysis: TrackAnalysis, positionSec: Double): Boolean {
        // The tempo is the authority. A stored beat interval that disagrees with
        // it by more than a quarter is not a beat — a head-only analysis was seen
        // carrying one of 24.7s on a 127 BPM track, which stopped the scrubber's
        // pulse dead for the rest of the blend.
        val fromTempo = if (analysis.bpm > 0.0) 60.0 / analysis.bpm else 0.0
        val interval = analysis.beatInterval
        val nominal = when {
            interval > 0.0 && (fromTempo <= 0.0 || abs(interval / fromTempo - 1.0) < 0.25) -> interval
            fromTempo > 0.0 -> fromTempo
            else -> return false
        }
        val downbeats = analysis.downbeats
        val next = downbeats.binarySearch(positionSec).let { if (it >= 0) it + 1 else -it - 1 }
        val origin: Double
        var beat = nominal
        if (next in 1 until downbeats.size) {
            origin = downbeats[next - 1]
            val bar = downbeats[next] - origin
            // A bar of one to eight beats is a bar, counted from the analyzer's own
            // downbeats. Anything longer is a stretch the analyzer found no
            // downbeat in, and dividing it up would invent a tempo.
            val beatsInBar = (bar / nominal).roundToInt()
            if (beatsInBar in 1..8) beat = bar / beatsInBar
        } else {
            origin = if (next >= 1) downbeats[next - 1] else analysis.firstBeat
        }
        if (beat <= 0.0 || !beat.isFinite()) return false
        beatScratch[0] = ((positionSec - origin) % beat + beat) % beat
        beatScratch[1] = beat
        return true
    }

    // ---- Tempo ease-back ----------------------------------------------------

    /**
     * Starts walking [player] off a beatmatch stretch of [rate] across four
     * steps (~6.5 s) instead of snapping back to listener speed immediately.
     */
    @MainThread
    @Suppress("UNUSED_PARAMETER")
    fun beginEase(player: ExoPlayer, rate: Double, analysis: TrackAnalysis) {
        val audible = releasableRate(rate)
        val stretch = audible - 1.0
        if (abs(stretch) < 0.01) {
            player.setPlaybackSpeed(deckSpeed(host.userPlaybackSpeed(), 1.0))
            easeTempo(player, 1f)
            return
        }
        // Four steps across about 6.5s, then the song is itself again.
        val steps = 4
        easeStep = stretch / steps
        easeRate = audible
        easeStepMs = 1_620L
        easeItemIndex = player.currentMediaItemIndex
        easeNextAtMs = player.currentPosition + easeStepMs
        easing = player
        easeOnActive = if (player === host.incoming()) host.isHandedOff() else !host.isHandedOff()
        player.setPlaybackSpeed(deckSpeedSafe(host.userPlaybackSpeed(), audible))
        easeTempo(player, 1f)
    }

    /**
     * One step of the ease, when the playhead has crossed the next threshold.
     * Measured on the track's own position so a pause holds it. Anything that
     * moves the listener off the stretched track — a skip, the next song, a
     * seek backwards, a party starting, the player no longer being the
     * session's — ends it on the listener's speed at once.
     */
    @MainThread
    fun driveEase() {
        val player = easing ?: return
        val position = player.currentPosition
        if (player !== host.active() ||
            player.currentMediaItemIndex != easeItemIndex ||
            position < easeNextAtMs - 2 * easeStepMs ||
            host.inParty()
        ) {
            endEase()
            return
        }
        if (position < easeNextAtMs) return
        easeRate -= easeStep
        if (abs(easeRate - 1.0) < EASE_DONE) {
            // The last step, the same size as the others.
            endEase(clickless = false)
            return
        }
        player.setPlaybackSpeed(deckSpeedSafe(host.userPlaybackSpeed(), easeRate))
        easeTempo(player, 1f)
        easeNextAtMs += easeStepMs
    }

    /** Puts the easing player on the listener's own speed and forgets it. Idempotent. */
    @MainThread
    fun endEase(clickless: Boolean = true) {
        // The notch outlives `easing`, which is cleared when it starts. A new
        // arm or release must cancel that job even when there is no ease left.
        if (!clickless) cancelPendingSpeedChange()
        val player = easing ?: return
        val onActive = easeOnActive
        easing = null
        if (clickless) {
            setPlaybackSpeedClickless(player, deckSpeed(host.userPlaybackSpeed(), 1.0))
        } else {
            player.volume = 1f
            player.setPlaybackSpeed(deckSpeed(host.userPlaybackSpeed(), 1.0))
        }
        if (onActive) host.filters.incomingTempo(1f) else host.filters.outgoingTempo(1f)
    }

    /**
     * Cancels a pending gain notch before seek, pause, skip, release, or a deck
     * role change. Complete the reset before restoring the still-owned idle
     * session deck's level, even if the coroutine has not entered yet. A new
     * item or role invalidates ownership; a changed listener speed replaces
     * the saved target. Invalidate the old coroutine before settling so its
     * delayed finally block cannot write into a new role or generation.
     */
    @MainThread
    fun cancelPendingSpeedChange() {
        val player = softSpeedPlayer
        val level = softSpeedLevel
        softSpeedGeneration++
        val generation = softSpeedGeneration
        val job = softSpeedJob
        softSpeedJob = null
        try {
            job?.cancel()
            if (player != null && ownsPendingSpeedChange(player, generation)) {
                settlePendingSpeed(player)
                if (ownsPendingSpeedChange(player, generation)) player.volume = level
            }
        } finally {
            clearPendingSpeedChange(generation)
        }
    }

    // ---- Internal helpers ---------------------------------------------------

    private fun incomingBaseSpeed(incomingPlaybackRate: Double, localBlendInFlight: Boolean): Float {
        if (!localBlendInFlight) return deckSpeedSafe(host.userPlaybackSpeed(), incomingPlaybackRate)
        return deckSpeedForAdmittedBlend(host.userPlaybackSpeed(), incomingPlaybackRate)
    }

    private fun phaseError(
        out: ExoPlayer,
        into: ExoPlayer,
        mediaRatio: Double,
        outgoingAnalysis: TrackAnalysis,
        incomingAnalysis: TrackAnalysis,
    ): Double {
        if (!beatAt(outgoingAnalysis, host.positionMs(out) / 1000.0)) return Double.NaN
        val outSince = beatScratch[0]
        val outBeat = beatScratch[1]
        if (!beatAt(incomingAnalysis, host.positionMs(into) / 1000.0)) return Double.NaN
        val rate = mediaRatio
        val period = minOf(outBeat, beatScratch[1] / rate)
        var diff = (beatScratch[0] / rate - outSince) % period
        if (diff > period / 2) diff -= period else if (diff < -period / 2) diff += period
        if (abs(diff) > period * LOCK_MAX_BEATS) return Double.NaN
        return diff * rate
    }

    @MainThread
    private fun rampOutgoing(out: ExoPlayer, outgoingPlaybackRate: Double) {
        val due = ((out.currentPosition - rampStartMs) / rampStepMs + 1).toInt().coerceIn(0, rampSteps)
        if (due <= rampStep) return
        rampStep = due
        val rate = 1.0 + (outgoingPlaybackRate - 1.0) * due / rampSteps
        out.setPlaybackSpeed(deckSpeedSafe(host.userPlaybackSpeed(), rate))
        host.tracks.outgoingTempo(1f)
    }

    /** Beats a tempo change of [rate] takes at [TEMPO_STEP_PER_BEAT]; 0 for none. */
    private fun rampStepsFor(rate: Double): Int {
        val stretch = abs(rate - 1.0)
        return if (stretch < EASE_DONE) 0 else ceil(stretch / TEMPO_STEP_PER_BEAT).toInt()
    }

    /**
     * Pitch correction for the deck that is easing. Remembered as active or
     * spare at the moment the ease starts, because `finish` clears the
     * handoff flag while the walk back to the listener's speed is still going.
     */
    private fun easeTempo(player: ExoPlayer, rate: Float) {
        if (easing == null || easing !== player) {
            easeOnActive = if (player === host.incoming()) host.isHandedOff() else !host.isHandedOff()
        }
        if (easeOnActive) host.filters.incomingTempo(rate) else host.filters.outgoingTempo(rate)
    }

    /**
     * Changes speed behind a very short gain notch. Android's AudioTrack speed
     * path can expose a discontinuity when PlaybackParams changes with a
     * non-zero sample under the cursor; the notch puts that boundary at silence.
     * Used only between transitions, so the fade's own gain automation never
     * competes with it. The incoming deck is configured while silent instead.
     */
    @MainThread
    private fun setPlaybackSpeedClickless(player: ExoPlayer, target: Float) {
        cancelPendingSpeedChange()
        if (abs(player.playbackParameters.speed - target) < SPEED_CHANGE_EPSILON) return
        if (!player.isPlaying || player !== host.active() || player.volume <= 0f) {
            player.setPlaybackSpeed(target)
            return
        }
        val generation = softSpeedGeneration
        val level = player.volume
        val itemIndex = player.currentMediaItemIndex
        val item = player.currentMediaItem
        val userSpeed = host.userPlaybackSpeed()
        // Publish ownership before launch: Standard/Main dispatch can defer
        // entry until after a pause, release, or a new arm cancels the reset.
        softSpeedPlayer = player
        softSpeedLevel = level
        softSpeedTarget = target
        softSpeedUserSpeed = userSpeed
        softSpeedItemIndex = itemIndex
        softSpeedItem = item
        softSpeedJob = host.scope.launch {
            try {
                for (step in 1..SPEED_NOTCH_STEPS) {
                    if (!ownsPendingSpeedChange(player, generation)) return@launch
                    player.volume = level * (1f - step.toFloat() / SPEED_NOTCH_STEPS)
                    delay(SPEED_NOTCH_STEP_MS)
                }
                if (!ownsPendingSpeedChange(player, generation)) return@launch
                settlePendingSpeed(player)
                delay(SPEED_NOTCH_HOLD_MS)
                for (step in 1..SPEED_NOTCH_STEPS) {
                    if (!ownsPendingSpeedChange(player, generation)) return@launch
                    player.volume = level * step.toFloat() / SPEED_NOTCH_STEPS
                    delay(SPEED_NOTCH_STEP_MS)
                }
                // A listener-speed change during fade-up must not leave the
                // old target installed when this reset relinquishes ownership.
                if (ownsPendingSpeedChange(player, generation)) settlePendingSpeed(player)
            } finally {
                if (generation == softSpeedGeneration) {
                    try {
                        if (ownsPendingSpeedChange(player, generation)) player.volume = level
                    } finally {
                        clearPendingSpeedChange(generation)
                    }
                }
            }
        }
    }

    private fun ownsPendingSpeedChange(player: ExoPlayer, generation: Long): Boolean =
        generation == softSpeedGeneration && softSpeedPlayer === player &&
            player === host.active() && host.isIdle() &&
            player.currentMediaItemIndex == softSpeedItemIndex && player.currentMediaItem == softSpeedItem

    private fun settlePendingSpeed(player: ExoPlayer) {
        val userSpeed = host.userPlaybackSpeed()
        val target = if (userSpeed == softSpeedUserSpeed) softSpeedTarget else deckSpeed(userSpeed, 1.0)
        if (player.playbackParameters.speed != target) player.setPlaybackSpeed(target)
    }

    private fun clearPendingSpeedChange(generation: Long) {
        if (generation != softSpeedGeneration) return
        softSpeedJob = null
        softSpeedPlayer = null
        softSpeedItem = null
        softSpeedItemIndex = 0
        softSpeedLevel = 1f
        softSpeedTarget = 1f
        softSpeedUserSpeed = 1f
    }

    companion object {
        /**
         * Hard deck-speed discipline: the beatmatch rate a deck may play at.
         * Returns `1.0f` when [raw] is not a beatmatch or not finite, and
         * strictly clamps to `[0.92f, 1.08f]`.
         */
        fun safeDeckSpeed(raw: Float, beatmatched: Boolean = true): Float {
            if (!beatmatched || !raw.isFinite()) return 1.0f
            return raw.coerceIn(0.92f, 1.08f)
        }

        /** [safeDeckSpeed] applied to a mix [rate] before [deckSpeed] stacks it with listener speed. */
        fun safeRateForDeck(rate: Double, beatmatched: Boolean = true): Double =
            safeDeckSpeed(rate.toFloat(), beatmatched).toDouble()

        /** Final `ExoPlayer` speed for a mix [rate]: listener speed stacked on [safeRateForDeck]. */
        fun deckSpeedSafe(user: Float, rate: Double, beatmatched: Boolean = true): Float =
            deckSpeed(user, safeRateForDeck(rate, beatmatched))

        /**
         * Applies a locally admitted physical stretch before the composite
         * player-speed cap. Invalid rates cannot bypass the deck-speed guard.
         */
        fun deckSpeedForAdmittedBlend(user: Float, admittedRate: Double): Float {
            val base = if (user.isFinite() && user in 0.25f..3f) user else 1f
            val rate = if (admittedRate.isFinite() &&
                admittedRate in MIN_INCOMING_RATE..MAX_INCOMING_RATE
            ) admittedRate.toFloat() else 1f
            return (base * rate).coerceIn(0.25f, 3f)
        }

        /** Grace after the incoming track starts before its position is trusted for phase. */
        const val LOCK_START_MS = 300L

        /** Phase readings per decision; the median of these is what gets corrected. */
        const val LOCK_SAMPLES = 5

        /** Under this the two beats are together as far as anyone can hear. */
        const val LOCK_DEADBAND_SECONDS = 0.008

        /** After a nudge: long enough for the audio already buffered to play out at the new speed. */
        const val LOCK_SETTLE_MS = 400L

        /** After a reading in phase, how long before looking again. */
        const val LOCK_IN_PHASE_MS = 500L

        /** How far a nudge moves the incoming speed. */
        const val NUDGE = 0.03f

        /** Longest single nudge; anything left over is corrected on the next reading. */
        const val MAX_NUDGE_MS = 1_500L

        /** Past this much of the blend the outgoing track is too far gone for its beat to matter. */
        const val LOCK_UNTIL = 0.9f

        /** Max phase disagreement in beats of the shared pulse before skipping nudge. */
        const val LOCK_MAX_BEATS = 0.3

        /** The most the tempo moves in one beat, ramping up or easing back. */
        const val TEMPO_STEP_PER_BEAT = 0.0075

        /** The ceiling on a phase-lock nudge stacked on a speed-up. */
        const val MAX_TOTAL_SPEED_UP = 1.12

        /** Close enough to the listener's own speed to call the ease done. */
        const val EASE_DONE = 1e-4

        /** A step every this long when the track has no beat to step on. */
        const val EASE_FALLBACK_STEP_MS = 500L

        /** Short silence around an audible AudioTrack PlaybackParams update. */
        const val SPEED_NOTCH_STEPS = 2
        const val SPEED_NOTCH_STEP_MS = 2L
        const val SPEED_NOTCH_HOLD_MS = 2L
        const val SPEED_CHANGE_EPSILON = 0.0001f
    }
}
