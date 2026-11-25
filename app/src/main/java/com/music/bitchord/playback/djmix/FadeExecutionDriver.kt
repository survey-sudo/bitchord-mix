package com.music.bitchord.playback.djmix

import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LocalBlendAdmission
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.MixGain
import com.music.bitchord.playback.smart.RetireGuard
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.localBlendFitsDeckSpeedCap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import java.util.Locale
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.AutoMixVisualFeed
import com.music.bitchord.playback.SleepTimer
import com.music.bitchord.playback.TransitionFilters
import com.music.bitchord.playback.TransitionFilterProcessor


/** State observed or updated by [FadeExecutionDriver]; all access stays on the controller thread. */
@OptIn(UnstableApi::class)
internal interface FadeExecutionDriverHost : CoordinatorCallbacks {
    val active: () -> ExoPlayer
    val analysisFor: (MediaItem) -> TrackAnalysis
    var armDeadline: Long
    var armGraceUsed: Boolean
    var armOutgoingPreFiltered: Boolean
    var classicArmLeadMs: Long
    var classicFallbackReason: String
    var classicPrepStartMs: Long
    var executedRoute: Map<String, Any?>?
    val fadeCurves: FadeRideCurves
    var fadeEndMs: Long
    var fadeMs: Long
    var fadeOutBudgetMs: Long
    var fadeOutOriginMs: Long
    var fadeStartedAt: Long
    var fadingStallSinceMs: Long
    var handedOff: Boolean
    var hardWatchdogCooldownUntil: Long
    var hardWatchdogTriggeredPair: String?
    var heldAtEnd: ExoPlayer?
    var holdWanted: Boolean
    var idleStallPrepared: Boolean
    var idleStallSinceMs: Long
    val inClock: PositionClock
    var incoming: ExoPlayer?
    var incomingAnalysis: TrackAnalysis
    var incomingCueTimeMs: Long
    var incomingPlaybackRate: Double
    var lastCompleteReason: String?
    var lastFireFallbackReason: String
    var lastFireFxCount: Int
    var lastFireMove: String
    var lastFirePath: String
    var lastFireStems: Int
    var lastSmartPlanStartMs: Long
    var lastTimingPair: Pair<String, String>?
    var localBlendInFlight: Boolean
    var loopRefusal: String?
    val lyricSpans: java.util.concurrent.ConcurrentHashMap<String, LyricSpans>
    val mediaRatio: Double
    val mixExecutor: MixExecutor
    val onArmAutoMixIncoming: (MediaItem) -> Unit
    val onArmIncoming: (MediaItem, String?) -> Unit
    val onBlendHeadroom: (Float) -> Unit
    val onHandoff: (outgoing: ExoPlayer, incoming: ExoPlayer) -> Unit
    val outClock: PositionClock
    var outgoing: ExoPlayer?
    var outgoingAnalysis: TrackAnalysis
    var outgoingItemIndex: Int
    var outgoingPlaybackRate: Double
    val pairPreparer: com.music.bitchord.playback.djmix.PairPreparer?
    val phaseName: String
    var planInFlight: TransitionPlan?
    var plannedNextId: String?
    var queuedItemCount: Int
    var render: RideSpec
    var smartFadeActive: Boolean
    val standby: () -> ExoPlayer
    var survivorRecoveryPending: Boolean
    var tailDurationMs: Long
    var tailFromGain: Float
    var tailLoopSession: LoopMixSession?
    var tailStartMs: Long
    var tailTarget: ExoPlayer?
    val tempoEngine: EaseTempoEngine
    val tracks: TransitionFilters
    var visualCurrentIndex: Int
    var visualGeneration: Long?
    var visualQueue: List<MediaItem>
    val yieldAnalysis: () -> Unit
}

/**
 * A real crossfade: two tracks audible at once, the outgoing one falling as the
 * incoming one rises, the way Spotify and Apple Music do it.
 *
 * ## Why there are two players
 *
 * One ExoPlayer renders one queue item at a time, so at a track boundary there
 * is exactly one source and the gain it can be given is either 1 (no fade) or 0
 * (silence). The previous version of this class was a single-player volume
 * ramp, and that is precisely why it never sounded like a crossfade: it dipped
 * to silence at the join and climbed back out, leaving a hole where the blend
 * should be. Overlap needs a second decoder. There is no way around it.
 *
 * ## Which player plays what
 *
 * Two peers, not a player and a helper. Both are full ExoPlayers built the same
 * way and both can own the queue; at any instant one of them *is* the session
 * (it backs the MediaSession, holds audio focus and carries the notification)
 * and the other is idle. They swap roles at every transition.
 *
 *  - **[active]** — whichever player the session currently points at. The rest
 *    of the app only ever sees this one.
 *  - **[standby]** — the idle player. Between transitions it holds nothing. To
 *    arm a transition it is loaded with *the queue, positioned on the incoming
 *    track* at the plan's cue point, and started silently.
 *
 * The crucial word is **incoming**. An earlier version of this class put the
 * *outgoing* track on the second player: the session player jumped ahead to the
 * next song and the second player carried the old song's tail. That works, but
 * it forces a moment where both players render *the same audio*, and two
 * ExoPlayers cannot be started sample-accurately against each other. Whatever
 * they were misaligned by — measured on real transitions at 9 to 41ms — was
 * heard as the last instant of the outgoing track playing twice, at the head of
 * every single crossfade. No amount of tuning removes that; the duplication is
 * structural.
 *
 * Loading the *incoming* track on the standby removes it outright. The two
 * players never hold the same audio, so there is nothing to align, nothing to
 * hand over, and no seam to hide. The incoming track is simply already playing,
 * from exactly the right position, when its fader starts to move.
 *
 * ## The handoff
 *
 * Because both players own the queue, finishing a transition is a **role swap**
 * rather than a seek: nothing is re-buffered, nothing is re-sought, and no audio
 * is rendered twice. [onHandoff] is what performs it — the service moves the
 * MediaSession, audio focus, its listeners and its bookkeeping onto the incoming
 * player.
 *
 * It fires halfway through the blend — [HANDOFF_AT] — where the equal-power
 * curve makes the two tracks equally loud and the incoming one takes over. The
 * queue index, the metadata, the notification and the UI flip to the incoming
 * song there: not at its first, barely audible note, and not trailing the song
 * on its way out. Before it the incoming player is a silent-then-rising
 * shadow of the session; after it [outgoing] is the idle player, still audible,
 * being faded out.
 *
 * ## Curve
 *
 * `sin`/`cos` rather than the old `sqrt`: `sin²+cos²=1` exactly, so two tracks
 * fading past each other hold constant *power* the whole way through and the
 * transition has no dip in the middle. That is the standard crossfade law, and
 * it is what makes a long crossfade sound like a blend instead of a dip.
 */
@MainThread
@OptIn(UnstableApi::class)
internal class FadeExecutionDriver(private val host: FadeExecutionDriverHost) {
    /**
     * Loads the standby player with the queue, positioned on the incoming track
     * at the plan's cue point, and leaves it buffering there silently.
     *
     * Nothing is committed here. The standby is a scratch player until
     * [startFade] runs, so a queue edit, a skip or a pause arriving during
     * arming costs nothing but the decoder it was holding.
     *
     * The cue point is reached by *starting there* rather than by seeking:
     * `setMediaItems` takes the position the item is to begin at, so the
     * incoming track opens at its analyzed mix-in point with no seek, no
     * discontinuity and no frame-rounding. Same for the beatmatch stretch, which
     * is applied before a note has been rendered rather than being switched on
     * underneath one already playing.
     */
    fun begin(
        fade: Long,
        endMs: Long,
        smart: Boolean,
        cueTimeMs: Long = 0L,
        playbackRate: Double = 1.0,
        outgoingRate: Double = 1.0,
        beatMs: Long = 0L,
        renderStyle: RideSpec = RideSpec(),
        plan: TransitionPlan? = null,
    ): Boolean {
        with(host) {
            val out = active()
            val into = standby()
            // djmix17: no silent arm failures — count every guard exit.
            if (out === into) return armDeath("same-player")
            val nextIndex = out.nextMediaItemIndex
            if (nextIndex == C.INDEX_UNSET || nextIndex >= out.mediaItemCount) return armDeath("no-next-index")
            val localBlend = plan?.takeIf { LocalBlendAdmission.MARKER in it.policyReasons }
            if (localBlend != null && (!LocalBlendAdmission.isQualified(localBlend,
                    out.currentMediaItem?.let(analysisFor) ?: TrackAnalysis(), analysisFor(out.getMediaItemAt(nextIndex)),
                    out.currentMediaItem?.let { lyricSpans[it.mediaId]?.cues }.orEmpty().toList(),
                    lyricSpans[out.getMediaItemAt(nextIndex).mediaId]?.cues.orEmpty().toList()) ||
                    !localBlendFitsDeckSpeedCap(AppSettings.playbackSpeed.value.toDouble(), playbackRate) ||
                    abs(playbackRate - localBlend.incomingPlaybackRate) > 0.0001 || outgoingRate != 1.0 ||
                    abs(cueTimeMs / 1000.0 - localBlend.incomingCueTime) > 0.002 ||
                    abs(endMs / 1000.0 - localBlend.transitionEnd) > 0.002 ||
                    abs(fade / 1000.0 - localBlend.fadeSeconds) > 0.002)) return armDeath("local-blend-admission-lost")
            val classicBlockedByNative = smart && isNativeRendererAvailable(out) &&
                classicFallbackReason.isBlank() && mixExecutor.currentState() !is MixState.Failed &&
                !SleepTimer.afterTrack.value
            if (classicBlockedByNative) return armDeath("native-renderer-available")
            plannedNextId = out.getMediaItemAt(nextIndex).mediaId
            out.currentMediaItem?.let { ensureOpportunity(it.mediaId, requireNotNull(plannedNextId)) }
            // The last blend's stretch, if it is somehow still easing off: this
            // player is about to be the outgoing side of a new one.
            tempoEngine.endEase(clickless = false)

            // vc67: reset tail telemetry so a stale 800 from the previous
            // transition can never leak into mix.complete after a zero-tail.
            tailDurationMs = 0L
            fadeMs = fade
            fadeEndMs = endMs
            smartFadeActive = smart
            incomingCueTimeMs = cueTimeMs.coerceAtLeast(0L)
            localBlendInFlight = localBlend != null
            incomingPlaybackRate = if (localBlendInFlight) playbackRate
                else com.music.bitchord.playback.smart.audibleTempoRate(playbackRate)
            outgoingPlaybackRate = com.music.bitchord.playback.smart.audibleTempoRate(outgoingRate)
            val rampLeadMs = tempoEngine.configureOutgoingRamp(outgoingRate, beatMs, endMs, fade)
            render = renderStyle
            planInFlight = plan
            armDeadline = nowMs() + ARM_TIMEOUT_MS + rampLeadMs
            armGraceUsed = false
            armOutgoingPreFiltered = false
            handedOff = false
            outgoing = out
            incoming = into
            outgoingItemIndex = out.currentMediaItemIndex

            val t0 = SystemClock.elapsedRealtimeNanos()
            val items = (0 until out.mediaItemCount).map { out.getMediaItemAt(it) }
            val copyDurationUs = (SystemClock.elapsedRealtimeNanos() - t0) / 1000L
            queuedItemCount = items.size
            val perTrackUs = if (items.isNotEmpty()) copyDurationUs.toDouble() / items.size else 0.0
            Log.d(
                TAG,
                "begin() queue copy: ${items.size} tracks in ${copyDurationUs}µs (${String.format(java.util.Locale.US, "%.2f", perTrackUs)}µs/track)",
            )

            Log.d(
                TAG,
                "arm ${if (smart) "smart" else "standard"} fade=${fade}ms end=${endMs}ms " +
                    "cue=${incomingCueTimeMs}ms rate=$incomingPlaybackRate at=${out.currentPosition}ms " +
                    "style=${render.style} bassSwap=${render.bassSwap}@${render.swapAt} " +
                    "advanced=${render.advanced} lock=${render.phaseLock} handoff=${render.handoffAt} " +
                    "echo=${if (render.echo) "${render.echoSecondsF}s@${render.echoAt}" else "off"} " +
                    "sweep=${render.filterSweep}",
            )

            this@FadeExecutionDriver.holdAtEnd(out)

            // Always disable skip-silence on incoming deck consistently across classic and native paths.
            into.skipSilenceEnabled = false
            into.repeatMode = out.repeatMode
            into.shuffleModeEnabled = false
            // Stacks on top of the listener's speed control rather than replacing
            // it, so a beatmatched transition and "play everything at 1.25x" don't
            // fight each other. Undone in [finish].
            // Ordinary requests retain safeDeckSpeed; a locally admitted blend preserves
            // its checked physical rate, set once while this incoming deck is silent.
            // Tempo only. The old pitch corrector turned a small transpose into a
            // time factor of about a thousand, which is the 999x runaway.
            tempoEngine.prepareIncomingSpeed(into, incomingPlaybackRate, localBlendInFlight)
            into.volume = 0f
            // vc77: the incoming track rides on the spare sink; start its filter
            // neutral so no parked cutoff from the last blend muffles its first
            // decoded frames. Gated on the retiree (here the incoming deck
            // itself, just silenced) so Seam A is never violated.
            openSpareFiltersWhenSilent()
            val setNanos0 = SystemClock.elapsedRealtimeNanos()
            into.setMediaItems(items, nextIndex, incomingCueTimeMs)
            val setDurationUs = (SystemClock.elapsedRealtimeNanos() - setNanos0) / 1000L
            Log.d(TAG, "standby setMediaItems: ${items.size} tracks in ${setDurationUs}µs")
            // Before `prepare`, so the standby's per-player audio state is right
            // for the incoming track from its very first decoded frame rather than
            // from the handoff, which is half a blend too late.
            items.getOrNull(nextIndex)?.let { item ->
                val after = into.nextMediaItemIndex
                val afterId = if (after == C.INDEX_UNSET) null else items.getOrNull(after)?.mediaId
                onArmIncoming(item, afterId)
            }
            // Buffers without sounding. Started for real in [startFade].
            into.playWhenReady = false
            into.prepare()
            if (smart) notePrep(into)
            if (smart && plan != null) {
                val outItem = items.getOrNull(outgoingItemIndex)
                val inItem = items.getOrNull(nextIndex)
                if (outItem != null && inItem != null) {
                    val outDuration = out.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                    val geometry = mixGeometry()
                    prefetchMixWaves(
                        outItem, inItem, outDuration, queuedDurationMs(inItem),
                        geometry.outStart, geometry.outEnd, geometry.inStart, geometry.ratio,
                    )
                }
            }

            val outId = out.currentMediaItem?.mediaId.orEmpty()
            val inId = into.currentMediaItem?.mediaId ?: plannedNextId.orEmpty()
            if (classicPrepStartMs <= 0L) classicPrepStartMs = nowMs()
            if (classicArmLeadMs <= 0L) classicArmLeadMs = (endMs - fade - out.currentPosition).coerceAtLeast(0L)
            lastTimingPair = outId to inId
            pairPreparer?.markArmed(outId, inId, armLeadMs = classicArmLeadMs)
            onArming()
            if (smart) {
                armAutoMixVisual(out, into, nextIndex, items)
                items.getOrNull(nextIndex)?.let(onArmAutoMixIncoming)
            }
            acceptRoute(plan ?: "standard-classic", classicRouteFields(plan))
            if (into.playbackState == Player.STATE_READY && into.currentMediaItem?.mediaId == opportunity?.inId) {
                preparedRoute(classicRouteFields(plan))
            }
            return true
        }
    }

    /**
     * Stops [out] reading on into the next track, so the handoff can cut its
     * queue without a break in its sound.
     *
     * A player reads well ahead of what it plays: everything its sink holds,
     * seconds of it on a float route, plus what the decoder has in flight. So
     * the outgoing player starts reading the *next* item — the song fading
     * in — that far before its own track ends, and its audio is already
     * queued behind the last of this one. [handOff] then removes that item,
     * and Media3, finding the period its renderers are reading gone, seeks to
     * where it already is: the sink is flushed and refilled, and the track
     * still sounding through the second half of the blend drops out for a
     * moment. Back when the handoff came at the start of the blend that
     * never happened — the track had a whole fade left to run — which is why
     * the break arrived with the midpoint handoff.
     *
     * Pausing at the end of the item keeps the renderers on this track: they
     * are told it is final, play it out to its last sample and stop. Removing
     * the items behind it then touches nothing being read. It has to be set
     * before the read-ahead reaches the next track, though — setting it once
     * that has happened makes Media3 flush to undo it, the same break, at
     * full volume, just before the blend.
     *
     * Which is what the standard crossfade kept doing. This used to be set
     * at arm time and trusted an estimate of the read-ahead — the sink's lead
     * plus a second and a half for the decoder — and the standard path arms
     * only its fade plus [ARM_LEAD_MS] before the file ends, inside what a
     * deep decoder pipeline (FLAC frames, a software decoder's queue) really
     * reads ahead. Automix mostly escaped it by arming off a mix-out point
     * well clear of the file end. So the estimate is gone: this only holds
     * where Media3 *cannot* yet be reading on — see [MEDIA3_READ_AHEAD_LIMIT_MS]
     * — and [holdAhead] puts it there from the idle tick, for either kind of
     * transition, long before either arms. Where that is too late — a seek
     * into the last seconds — the end is left unheld, and [handOff] leaves
     * the queue alone rather than flush.
     *
     * Released in [finish], by an idle tick that no longer expects a
     * transition, and by [listener] if the track runs out while still the
     * session's.
     */
    fun holdAtEnd(out: ExoPlayer) {
        with(host) {
            if (heldAtEnd === out) return
            val duration = out.duration
            if (duration == C.TIME_UNSET || duration <= 0L) return
            val left = duration - out.currentPosition
            if (left <= this@FadeExecutionDriver.maxReadAheadMs(out)) {
                Log.d(TAG, "end not held: ${left}ms left, may already be reading on (${maxReadAheadMs(out)}ms)")
                return
            }
            this@FadeExecutionDriver.releaseHold()
            out.pauseAtEndOfMediaItems = true
            heldAtEnd = out
            Log.d(TAG, "end held at ${left}ms left")
        }
    }

    /**
     * Holds the playing track's end for the transition an idle tick has
     * found coming, as soon as it is within reach of [holdAtEnd] — whether or
     * not that transition has armed yet. Arming waits for its own lead;
     * holding cannot, because by then the player may already be reading on.
     */
    fun holdAhead(player: ExoPlayer, duration: Long) {
        with(host) {
            val left = duration - player.currentPosition
            val limit = this@FadeExecutionDriver.maxReadAheadMs(player)
            if (left > limit + HOLD_AHEAD_MS) return
            holdWanted = true
            // Silently, past the point it can be done: [begin] says so once, not
            // every idle tick.
            if (left > limit) this@FadeExecutionDriver.holdAtEnd(player)
        }
    }

    /** Undoes [holdAtEnd]. Idempotent; never flushes. */
    fun releaseHold() {
        with(host) {
            heldAtEnd?.pauseAtEndOfMediaItems = false
            heldAtEnd = null
        }
    }

    /**
     * The furthest before its track's end, in media ms, that [player] can
     * have started reading the next one: Media3's wall-clock limit at its
     * current speed, and a margin for a position report that is a tick old
     * and the hop to the playback thread.
     */
    fun maxReadAheadMs(player: ExoPlayer): Long {
        with(host) {
            val speed = player.playbackParameters.speed.coerceAtLeast(1f)
            return (MEDIA3_READ_AHEAD_LIMIT_MS * speed).roundToLong() + HOLD_SAFETY_MS
        }
    }

    /**
     * Whether [out], left unheld, has run on from its own track into the one
     * after it — the song fading in, started a second time on the player
     * that is leaving. Never true for a held player: it stops on its track.
     */
    fun ranOn(out: ExoPlayer): Boolean = with(host) {
        handedOff && outgoingItemIndex != C.INDEX_UNSET && out.currentMediaItemIndex != outgoingItemIndex
    }

    /**
     * Waits for the standby to have the incoming track ready at its cue point,
     * and for the outgoing track to reach the fade.
     *
     * There is nothing to align here — the two players hold different songs — so
     * this is only ever waiting on a buffer.
     */
    fun driveArming() {
        with(host) {
            val out = outgoing ?: return this@FadeExecutionDriver.cancelArming("missing-outgoing-player")
            val into = incoming ?: return this@FadeExecutionDriver.cancelArming("missing-incoming-player")
            if (!stillWorthFading()) return this@FadeExecutionDriver.cancelArming("mix-disabled-or-no-next")
            val armedPlan = planInFlight
            if (armedPlan != null && LocalBlendAdmission.MARKER in armedPlan.policyReasons &&
                !localBlendFitsDeckSpeedCap(AppSettings.playbackSpeed.value.toDouble(), incomingPlaybackRate)
            ) return this@FadeExecutionDriver.cancelArming("local-blend-listener-speed-cap")
            // Paused while armed: the transition is no longer imminent, and holding
            // a prepared decoder open against a stopped player is worse than arming
            // again when playback resumes.
            if (!out.playWhenReady) return this@FadeExecutionDriver.cancelArming("paused-before-fade")

            val now = nowMs()
            val expired = now > armDeadline
            val ready = into.playbackState == Player.STATE_READY
            if (ready && into.currentMediaItem?.mediaId == opportunity?.inId) preparedRoute(classicRouteFields(planInFlight))

            // A standby that never got the incoming track ready has nothing to fade
            // up. Give up and let the queue move on plainly rather than fading into
            // silence. djmix10: streamed Opus routinely needs one grace window
            // past the deadline (quality-upgrade rebuffer, BT jitter) — extend
            // once instead of bailing straight to a plain advance.
            if (expired && !ready) {
                if (!armGraceUsed) {
                    armGraceUsed = true
                    armDeadline = now + ARM_TIMEOUT_MS
                    AutomixTelemetry.event("mix.arm.grace", mapOf("phase" to phaseName))
                    if (armOutgoingPreFiltered) {
                        armOutgoingPreFiltered = false
                        this@FadeExecutionDriver.resetPreFilteredOutgoing()
                        fadeCurves.rideFilters(render, 0f, null)
                    }
                    return
                }
                AutomixTelemetry.event("mix.arm.expired", mapOf("phase" to phaseName))
                return this@FadeExecutionDriver.cancelArming("arm-expired")
            }

            // Wait for the track to actually reach the fade point. [fadeEndMs] is
            // the track's own duration in standard mode, or a Automix plan's
            // analyzed mix-out anchor when it ends before the file does.
            val atFadePoint = fadeEndMs <= 0L || fadeEndMs - out.currentPosition <= fadeMs
            if (atFadePoint && ready) {
                this@FadeExecutionDriver.startFade()
                return
            }

            // Both sinks write seconds ahead of the speaker. The standby fills its
            // whole buffer while it waits here, so its filter has to be at the
            // blend's opening position before that audio is written, not once the
            // fade starts — otherwise the incoming track's first seconds are heard
            // unfiltered. The outgoing sink crosses into the blend its lead before
            // the listener does, so its filter only moves ahead once incoming is
            // ready and the fade start has not already passed; otherwise solo A
            // darkens/colours alone and jumps when [startFade] re-anchors
            // [fadeOutOriginMs].
            val outgoingAt = if (ready && !atFadePoint && fadeMs > 0L && fadeEndMs > 0L) {
                ((out.currentPosition + tracks.outgoingLeadMs() - (fadeEndMs - fadeMs)).toFloat() / fadeMs)
                    .takeIf { it > 0f }
                    ?.coerceAtMost(1f)
            } else {
                null
            }
            if (outgoingAt != null) {
                armOutgoingPreFiltered = true
                fadeCurves.rideFilters(render, 0f, outgoingAt)
            } else {
                if (armOutgoingPreFiltered) {
                    armOutgoingPreFiltered = false
                    this@FadeExecutionDriver.resetPreFilteredOutgoing()
                }
                fadeCurves.rideFilters(render, 0f, null)
            }
            tempoEngine.maybeRampOutgoing(out, outgoingPlaybackRate)
        }
    }

    /** Restores solo outgoing filter/colour/echo state if [driveArming] pre-filtered before rebuffering. */
    fun resetPreFilteredOutgoing() {
        with(host) {
            tracks.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
            tracks.outgoingMidDb(0f)
            tracks.outgoingColour(com.music.bitchord.playback.smart.DjEffect.NONE, 0f, render.beatSecondsF, 0f)
            if (render.echo) tracks.outgoingEcho(render.echoSecondsF, 0f, 1f)
        }
    }

    /** Releases a pre-fade arm cleanly without calling global [TransitionFilters.open] while solo A is audible. */
    fun cancelArming(reason: String) {
        with(host) {
            if (!isArming()) return
            val expiredPairKey = if (reason == "arm-expired") {
                val curId = outgoing?.currentMediaItem?.mediaId ?: runCatching { active().currentMediaItem?.mediaId }.getOrNull()
                val nxtId = incoming?.currentMediaItem?.mediaId ?: plannedNextId
                if (!curId.isNullOrBlank() && !nxtId.isNullOrBlank()) "$curId->$nxtId" else null
            } else null
            lastCompleteReason = reason
            mixEvent(
                "mix.arm.death",
                mapOf(
                    "stage" to "prepared", "technique" to "classic", "refusalCode" to refusalCode(reason),
                    "reason" to reason,
                    "phase" to phaseName,
                    "msSincePlan" to planAgeMs(),
                ),
            )
            if (armOutgoingPreFiltered) {
                armOutgoingPreFiltered = false
                survivorRecoveryPending = true
            }
            bail(force = true)
            if (expiredPairKey != null && isIdle()) {
                // Brief cooldown so a standby player that timed out preparing does not immediately thrash-arm on next tick.
                hardWatchdogTriggeredPair = expiredPairKey
                hardWatchdogCooldownUntil = nowMs() + 5_000L
            }
        }
    }

    /**
     * Starts the incoming track under the outgoing one.
     * The session stays where it is: the player, the notification and the
     * queue keep showing the outgoing song through the first half of the
     * blend, while its bar fills to the end, and move to the incoming song at
     * the midpoint — see [handOff]. Switching the moment the incoming track
     * first sounded put a song on screen that could barely be heard yet, under
     * one that was still plainly playing.
     */
    fun startFade() {
        with(host) {
            val out = outgoing ?: return this@FadeExecutionDriver.cancelArming("missing-outgoing-at-fade")
            val into = incoming ?: return this@FadeExecutionDriver.cancelArming("missing-incoming-at-fade")
            if (localBlendInFlight &&
                !localBlendFitsDeckSpeedCap(AppSettings.playbackSpeed.value.toDouble(), incomingPlaybackRate)
            ) return this@FadeExecutionDriver.cancelArming("local-blend-listener-speed-cap")

            armOutgoingPreFiltered = false
            into.volume = 0f
            into.playWhenReady = true
            fadeStartedAt = nowMs()
            // djmix13: record firing accuracy at the moment the blend starts.
            // djmix14: cueErrorMs was seconds-minus-ms (5000-5=4995). Convert first.
            val targetCueMs = ((planInFlight?.incomingCueTime ?: 0.0) * 1000.0).roundToLong()
            val outId = out.currentMediaItem?.mediaId.orEmpty()
            val inId = into.currentMediaItem?.mediaId ?: plannedNextId.orEmpty()
            val levelName = AppSettings.automixDjLevel.value.name
            val fxNames = classicActiveFxNames(render)
            val fallback = lastFireFallbackReason.ifBlank { loopRefusal.orEmpty() }
            lastTimingPair = outId to inId
            lastFirePath = "classic"
            lastFireMove = ""
            lastFireFxCount = fxNames.size
            lastFireStems = 0
            lastFireFallbackReason = fallback
            pairPreparer?.markRunning(outId, inId)
            pairPreparer?.recordTiming(
                outId, inId,
                armLeadMs = classicArmLeadMs,
                path = "classic",
                level = levelName,
                move = "",
                fxCount = fxNames.size,
                stems = 0,
                fallbackReason = fallback,
            )
            val classicReason = classicFallbackReason.ifBlank {
                fallback.ifBlank { if (smartFadeActive) "native-renderer-unavailable" else "manual-crossfade" }
            }
            AutomixTelemetry.event(
                "mix.classic.reason",
                mapOf(
                    "reason" to classicReason,
                    "outId" to outId,
                    "inId" to inId,
                    "smart" to smartFadeActive,
                    "stage" to "start-fade",
                ),
            )
            executedRoute = classicRouteFields(planInFlight)
            mixEvent(
                "mix.fire",
                requireNotNull(executedRoute) + mapOf(
                    "stage" to "executed", "executionMarker" to "classic-fade-fired",
                    "mode" to "classic",
                    "path" to "classic",
                    "engine" to "classic",
                    "choreo" to "",
                    "fx" to fxNames.joinToString(","),
                    "fxCount" to fxNames.size,
                    "level" to levelName,
                    "stems" to 0,
                    "fallbackReason" to fallback,
                    "plannedStartMs" to lastSmartPlanStartMs,
                    "actualStartMs" to out.currentPosition,
                    "deltaMs" to (out.currentPosition - lastSmartPlanStartMs),
                    "cueErrorMs" to (into.currentPosition - targetCueMs),
                    "trackIndex" to outgoingItemIndex,
                ),
            )
            fadeOutOriginMs = out.currentPosition
            val fileLeft = out.duration.takeIf { it != C.TIME_UNSET && it > 0L }?.minus(fadeOutOriginMs)
            fadeOutBudgetMs = minOf(fadeMs, fileLeft ?: fadeMs).coerceAtLeast(1L)
            outClock.reset()
            inClock.reset()
            tempoEngine.onFadeStart(out, fadeStartedAt, outgoingPlaybackRate)
            outgoingAnalysis = out.currentMediaItem?.let(analysisFor) ?: TrackAnalysis()
            incomingAnalysis = into.currentMediaItem?.let(analysisFor) ?: TrackAnalysis()

            Log.d(TAG, "blend start at cue=${into.currentPosition}ms out=${out.currentPosition}ms")

            // Published before the mix flag, in the same tick: the scrubber shows
            // the old sheen for a mix flag with no blend beside it, and would flash
            // it for a frame otherwise.
            publishBlend(out, into, force = true)
            val realMix = FadeRideCurves.isRealMix(
                smartFadeActive, render.style, incomingCueTimeMs, incomingPlaybackRate, outgoingPlaybackRate,
            )
            AppSettings.smartMixInProgress.value = realMix
            if (realMix) publishMixView(out, into)
            // The bar fills to the end through the first half of the blend, which
            // would run straight over the marker for the transition now under way.
            AppSettings.smartTransitionWindow.value = null
            onFading()
            AutoMixVisualFeed.start(visualGeneration, audioSeconds = 0.0)
            this@FadeExecutionDriver.driveFade()
        }
    }

    /**
     * Moves the session onto the incoming player, halfway through the blend.
     *
     * Everything hanging off the session player — queue index, metadata, the
     * notification, the UI, audio focus — flips to the incoming song at the
     * point it becomes the louder of the two. From here [outgoing] is the idle
     * player, still audible, being faded away.
     */
    @JvmOverloads
    fun handOff(
        out: ExoPlayer,
        into: ExoPlayer,
        visualAudioSeconds: Double? = null,
        visualEndSeconds: Double? = null,
    ) {
        with(host) {
            if (handedOff) return
            if (!this@FadeExecutionDriver.reconcileQueue(out, into)) {
                bail()
                return
            }
            // djmix18 (Bug 1): never call setPlaybackSpeed after handover with WSOLA steps across
            // B's solo intro. Reset the incoming player cleanly to the listener speed here, while
            // the outgoing tail still masks the single parameter change, and cancel any in-flight
            // ease so finish() cannot restart stepped WSOLA speed changes on the solo. Classic
            // mixes finish the blend at the incoming rate; the stretch came off at this line.
            // Native LoopMix decks are already at 1x (the renderer did the tempo), so this is a
            // no-op there — and still a guard that no ease is pending when the player takes over.
            runCatching {
                tempoEngine.onHandover(into, localBlendInFlight)
            }
            // AutoPlay may have appended to the queue since the standby was loaded
            // with a copy of it — during arming or the first half of the blend,
            // both of which the outgoing player owned — and those tracks would
            // otherwise be lost at the swap. [reconcileQueue] already carried
            // that append across, or refused a change that was not an append.

            Log.d(TAG, "handoff at in=${into.currentPosition}ms out=${out.currentPosition}ms")
            // The incoming song is about to be the one on screen, and a few seconds
            // later the one the speaker is playing from the player itself. Analysis
            // that starts now lands on the same cores as that decoder.
            yieldAnalysis()

            // Before the swap, so the listener follows the session rather than
            // firing on a player this class is about to demote.
            listenTo(into)
            handedOff = true
            AutoMixVisualFeed.pending(visualGeneration)?.let { visual ->
                val expected = visualQueue.getOrNull(visual.incoming.armedIndex)
                if (expected == null || into.currentMediaItemIndex != visual.incoming.armedIndex ||
                    !sameVisualQueueItem(expected, into.currentMediaItem)
                ) {
                    cancelAutoMixVisual()
                } else {
                    visualQueue = (0 until into.mediaItemCount).map(into::getMediaItemAt)
                    visualCurrentIndex = into.currentMediaItemIndex
                }
            }
            val endOfItemSample = if (visualAudioSeconds == null && AutoMixVisualFeed.pending(visualGeneration) != null) {
                val span = this@FadeExecutionDriver.effectiveFadeSpan(into)
                val axisMs = if (render.advanced) span * mediaRatio.toFloat() else span.toFloat()
                val elapsed = (into.currentPosition - incomingCueTimeMs).coerceAtLeast(0L).toFloat()
                val remaining = out.duration.takeIf { it != C.TIME_UNSET && it > 0L }?.minus(out.currentPosition)
                val endProgress = remaining?.let { (span - it).toFloat() / span } ?: 0f
                val progress = maxOf(elapsed / axisMs, endProgress).coerceIn(0f, 1f)
                (progress * axisMs / 1000.0) to (axisMs / 1000.0)
            } else null
            onHandoff(out, into)
            val swap = visualAudioSeconds ?: endOfItemSample?.first
            val end = visualEndSeconds ?: endOfItemSample?.second
            if (swap != null && end != null) AutoMixVisualFeed.swap(visualGeneration, swap, end)

            // The outgoing player holds the whole queue too, and a standard
            // crossfade runs right up to its track's natural end — at which point
            // ExoPlayer would do what it always does and advance to the next item,
            // starting the incoming song a second time, on top of itself, out of the
            // player that is supposed to be going quiet. Truncating the queue at the
            // playing item turns that into STATE_ENDED, which [driveFade] already
            // reads as the tail being spent. Safe to discard: [into] is the
            // authoritative queue from here, and this player is retired seconds
            // later anyway.
            //
            // Only seamless because [holdAtEnd] kept this player from reading into
            // the items being removed. Without the hold, a handoff this close to
            // the track's end removes the period its renderers are reading, and
            // Media3 flushes the sink to recover — a break in the track still
            // playing. So an unheld player keeps its queue: it runs on into the
            // incoming song at the very end of its fade, where [ranOn] has it
            // silenced, and [finish] retires it a tick or two later.
            if (heldAtEnd === out && out.mediaItemCount > out.currentMediaItemIndex + 1) {
                out.removeMediaItems(out.currentMediaItemIndex + 1, out.mediaItemCount)
            }
        }
    }

    /**
     * Copies onto the standby anything appended to the queue while it was
     * arming.
     *
     * AutoPlay extending the queue mid-transition is explicitly allowed — it
     * doesn't change the playing item, so it has never been a reason to drop a
     * blend. Under the old design that was free, because only one player ever
     * held the queue. Now the standby is carrying a copy taken at arm time, and
     * that copy is what survives the swap, so the difference has to be carried
     * across or the appended tracks simply vanish when the roles change.
     *
     * Only a pure append is reconciled. Anything else — a queue replaced, an
     * item removed or moved — changes what the incoming track *is*, and
     * [listener] has already bailed the transition for it.
     */
    /**
     * Copies a pure append onto the standby. A removal, a move, or a replaced
     * item is not an append: the incoming track may no longer be the one this
     * blend armed, and the caller drops the blend. Returns false in that case.
     * Does not assume a listener already noticed.
     */
    fun reconcileQueue(out: ExoPlayer, into: ExoPlayer): Boolean {
        with(host) {
            val outCount = out.mediaItemCount
            val intoCount = into.mediaItemCount
            if (queuedItemCount < 0 || queuedItemCount > outCount || queuedItemCount > intoCount) {
                Log.d(TAG, "queue length diverged (out=$outCount into=$intoCount snap=$queuedItemCount)")
                return false
            }
            val shared = queuedItemCount
            for (i in 0 until shared) {
                if (i >= outCount || i >= intoCount) return false
                if (out.getMediaItemAt(i).mediaId != into.getMediaItemAt(i).mediaId) {
                    Log.d(TAG, "queue diverged at $i")
                    return false
                }
            }
            if (outCount < queuedItemCount) return false
            val nextIndex = out.currentMediaItemIndex + 1
            if (nextIndex in 0 until shared) {
                val outTail = (nextIndex + 1 until outCount).map { out.getMediaItemAt(it).mediaId }
                val intoTail = (nextIndex + 1 until intoCount).map { into.getMediaItemAt(it).mediaId }
                if (outTail != intoTail) {
                    if (intoCount > nextIndex + 1) into.removeMediaItems(nextIndex + 1, intoCount)
                    val rebuilt = (nextIndex + 1 until outCount).map { out.getMediaItemAt(it) }
                    if (rebuilt.isNotEmpty()) into.addMediaItems(rebuilt)
                    queuedItemCount = outCount
                    Log.d(TAG, "rebuilt ${rebuilt.size} item(s) after next")
                    return true
                }
            }
            if (outCount == queuedItemCount) return true
            val appended = (queuedItemCount until outCount).map { out.getMediaItemAt(it) }
            into.addMediaItems(appended)
            queuedItemCount = outCount
            Log.d(TAG, "reconciled ${appended.size} appended item(s) onto the incoming player")
            return true
        }
    }

    fun effectiveFadeSpan(player: ExoPlayer): Long {
        with(host) {
            val remainingIncoming = player.duration
                .takeIf { it != C.TIME_UNSET && it > 0L }
                ?.minus(incomingCueTimeMs)
                ?.coerceAtLeast(0L)
            val fourBarsMs = if (render.beatSecondsF > 0f) (render.beatSecondsF * 16f * 1000f).toLong() else 0L
            val incomingCap = remainingIncoming?.let { left ->
                // A third of a short song used to shrink an 8-bar blend to a
                // fraction of a bar. Keep at least four bars when the song has them.
                maxOf(left / 3, minOf(left, fourBarsMs)).coerceAtLeast(1L)
            } ?: Long.MAX_VALUE
            return minOf(fadeMs, incomingCap).coerceAtLeast(1L)
        }
    }

    /**
     * The crossfade proper.
     *
     * Driven off the *incoming* track's position rather than off a clock, so a
     * pause parks the transition where it stands and resuming picks it back up
     * — no timer to reconcile, and neither player left hanging at half volume
     * while the other waits.
     */
    fun driveFade() {
        with(host) {
            val out = outgoing ?: return bail()
            val player = incoming ?: return bail()
            val span = this@FadeExecutionDriver.effectiveFadeSpan(player)
            val elapsed = (inClock.read(player) - incomingCueTimeMs).coerceAtLeast(0.0).toFloat()
            // Advanced: [fadeMs] is outgoing time, and a stretched incoming track
            // covers `rate` times as much of its own timeline in it — unscaled, a
            // 4% stretch finished the blend 4% early and left the incoming drop
            // landing after it rather than on its end.
            val wallSpan = if (render.advanced) span * mediaRatio.toFloat() else span.toFloat()
            val incomingHeard = elapsed / wallSpan
            val incomingProgress = incomingHeard.coerceIn(0f, 1f)
            // Duration/decoder timing can put the real file end ahead of the
            // analysed anchor. In that case the outgoing deck must still reach
            // zero before its final sample instead of remaining loud and appearing
            // to stop. Normally this is zero and incoming timing remains authority.
            // Read through [outClock] rather than raw `out.currentPosition` so
            // outgoing-driven progress and AutoMix artwork reveal stay smooth.
            val outDuration = out.duration.takeIf { it != C.TIME_UNSET && it > 0L }
            val outPosition = outClock.read(out).toLong()
            val outRemaining = outDuration?.minus(outPosition)
            // Measured from where the fade actually started, over the overlap we
            // still have. Using the planned span against a file that was already
            // about to end put progress near 1 on the first tick and cut A off.
            val traveled = (outPosition - fadeOutOriginMs).coerceAtLeast(0L)
            val budget = fadeOutBudgetMs.coerceAtLeast(1L)
            val outgoingProgress = (traveled.toFloat() / budget.toFloat()).coerceIn(0f, 1f)
            val progress = maxOf(incomingProgress, outgoingProgress)
            val echoTailEnd = render.echoAt + render.echoTailMs / wallSpan
            val echoFileLeft = if (render.echo) outDuration?.minus(fadeOutOriginMs) else null
            val echoBudget = minOf(budget, echoFileLeft ?: budget).coerceAtLeast(1L)
            val echoHeard = if (render.echo) maxOf(incomingHeard, traveled.toFloat() / echoBudget) else progress

            // Before the handoff the session is still the outgoing player, so a
            // failure on the incoming one would otherwise go unheard: its position
            // stops, the blend parks at whatever mix it had reached, and the
            // outgoing track runs out under it.
            // djmix19: an incoming that never leaves STATE_IDLE used to bail() on
            // every 30 ms tick, freezing the blend and logging mix.freeze 762x.
            // Track the stall: try prepare() once after 1.5 s, then force-exit so
            // the blend never hangs; an outgoing about to run out forces at once.
            if (!handedOff && player.playbackState == Player.STATE_IDLE) {
                val now = nowMs()
                if (idleStallSinceMs == 0L) idleStallSinceMs = now
                val stalledMs = now - idleStallSinceMs
                val outExhausted = outRemaining != null && outRemaining <= HANDOFF_GUARD_MS
                return when (idleStallDecision(stalledMs, outExhausted, idleStallPrepared)) {
                    IdleStallAction.WAIT_AND_FREEZE -> bail()
                    IdleStallAction.PREPARE_ONCE -> {
                        idleStallPrepared = true
                        runCatching { player.prepare() }
                        bail()
                    }
                    IdleStallAction.FORCE_BAIL -> {
                        idleStallSinceMs = 0L
                        idleStallPrepared = false
                        bail(force = true)
                    }
                }
            }
            idleStallSinceMs = 0L
            idleStallPrepared = false

            // Faders act at the speaker; everything in the DSP chain — the filters
            // and the headroom trim — acts where each sink is writing, its lead
            // ahead of that. So those are aimed at the blend as it will be when the
            // audio they touch is played, each side by its own lead.
            val incomingAt = ((elapsed + tracks.incomingLeadMs()) / wallSpan).coerceIn(0f, 1f)
            val outgoingAt = (echoHeard + tracks.outgoingLeadMs() / span.toFloat()).coerceIn(0f, 1f)

            val rise = fadeCurves.mixRise(render, progress)
            val fall = if (render.echo) {
                val fileEnd = echoFileLeft?.let { it.toDouble() / echoBudget } ?: Double.POSITIVE_INFINITY
                val fadeEnd = minOf(maxOf(1.0, echoTailEnd), fileEnd.coerceAtLeast(1.0))
                // The player fader carries both dry and wet. Keep it moving through
                // the existing tail, ending at the decoder EOF if that arrives first.
                if (echoHeard >= fadeEnd) 0f
                else MixGain.fall(((echoHeard - render.echoAt) / (fadeEnd - render.echoAt)).toFloat()).coerceAtLeast(0f)
            } else {
                fadeCurves.mixFall(render, progress)
            }
            player.volume = rise
            out.volume = if (this@FadeExecutionDriver.ranOn(out)) 0f else fall
            onBlendHeadroom(
                FadeRideCurves.headroomFor(
                    fadeCurves.mixRise(render, outgoingAt),
                    fadeCurves.outgoingLevel(render, elapsed / wallSpan + tracks.outgoingLeadMs() / span.toFloat(), wallSpan),
                ),
            )
            if (render.phaseLock) tempoEngine.holdPhase(
                out, player, progress, incomingPlaybackRate, mediaRatio,
                localBlendInFlight, outgoingAnalysis, incomingAnalysis,
            )

            // The midpoint, or sooner if the outgoing track is about to run out
            // from under it: until the handoff its queue still runs on past the
            // track, and reaching the end would start the next item — the very
            // song fading in — a second time on the player that is leaving.
            val visualEnd = wallSpan / 1000.0
            val visualTime = progress * wallSpan / 1000.0
            if (!handedOff && (progress >= render.handoffAt || (outRemaining != null && outRemaining <= HANDOFF_GUARD_MS))) {
                this@FadeExecutionDriver.handOff(out, player, visualTime, visualEnd)
            }
            AutoMixVisualFeed.advance(visualGeneration, visualTime, visualEnd)
            if (progress >= 1f) AutoMixVisualFeed.complete(visualGeneration)
            publishBlend(out, player)
            if (AppSettings.smartMixView.value != null) AppSettings.smartMixProgress.value = progress
            fadeCurves.rideFilters(render, incomingAt, outgoingAt)
            tempoEngine.rideBuildup(
                incoming, render.buildup, handedOff, localBlendInFlight, incomingPlaybackRate, progress,
            )

            // Finish when the fade runs its course or its setting is switched off.
            // The outgoing decoder ending is not completion: its final sample may
            // arrive early, and the incoming fader still has to finish rising.
            // Checked against the
            // setting that actually started it — a Automix normally runs with
            // [configuredFadeMs] at zero, and reading that as "turned off" would
            // end every Automix on its first tick.
            val settingSwitchedOff = if (smartFadeActive) {
                !AppSettings.smartFadeEnabled.value
            } else {
                configuredFadeMs() <= 0L
            }
            // An echo out rings past the end of the blend, carried by the outgoing
            // player's own silenced audio, so that player is kept until its tail
            // has been heard out.
            val tailDone = !render.echo || incomingHeard >= echoTailEnd
            // vc77: progress is the max of the two sides, so it can reach 1 while
            // the incoming sink is still mid-blend and would strand its filter.
            // Unless the outgoing file is about to EOF (where waiting would cut
            // it off), completion needs the incoming side caught up too; when it
            // is about to EOF the clamp below puts both sides at 1.0 full-band.
            val outAboutToEof = outRemaining != null && outRemaining <= HANDOFF_GUARD_MS
            val incomingReady = incomingAt >= 0.999f
            val done = settingSwitchedOff ||
                (progress >= 1f && tailDone && (incomingReady || outAboutToEof))
            // vc77 hang bound: progress==1 blocked by incomingReady while the
            // incoming player sits in STATE_BUFFERING and the outgoing file is
            // not near EOF. After FADING_STALL_BAIL_MS of
            // continuous buffering, force-bail through the existing
            // abnormal-exit path (survivor kept, its filters reopened only by
            // finish() once the retiree is silent — Seam A). A pause parks in
            // STATE_READY, never BUFFERING, so an intentional hold never trips
            // this, and a stall that recovers first completes normally.
            if (!done) {
                val now = nowMs()
                val buffering = player.playbackState == Player.STATE_BUFFERING
                val blockedAtOne = progress >= 1f && !incomingReady && !outAboutToEof && buffering
                if (blockedAtOne) {
                    if (fadingStallSinceMs == 0L) fadingStallSinceMs = now
                } else {
                    fadingStallSinceMs = 0L
                }
                if (fadingStallSinceMs != 0L) {
                    val stalledMs = now - fadingStallSinceMs
                    if (CoordinatorDecisions.fadingStallShouldBail(stalledMs, progress >= 1f, incomingReady, outAboutToEof, buffering)) {
                        fadingStallSinceMs = 0L
                        AutomixTelemetry.event(
                            "mix.stall.forceBail",
                            mapOf(
                                "stalledMs" to stalledMs,
                                "progress" to progress,
                                "incomingAt" to incomingAt,
                                "playbackState" to player.playbackState,
                                "bufferedPercentage" to player.bufferedPercentage,
                                "outRemainingMs" to (outRemaining ?: -1L),
                                "handoff" to handedOff,
                            ),
                        )
                        bail(force = true)
                        return
                    }
                }
            }
            if (done) {
                // Clamp both sides to progress 1 before teardown: a stranded
                // incoming filter otherwise survives into the next blend at a
                // parked cutoff.
                if (progress >= 1f) {
                    fadeCurves.rideFilters(render, 1f, 1f)
                } else {
                    // Abnormal exit (setting switched off) before progress 1:
                    // defer survivor-only recovery to finish() once the retiree
                    // is silent; the spare filter is left untouched.
                    survivorRecoveryPending = true
                }
                // A blend cut short before its midpoint still ends on the incoming
                // track — it is the one playing on, and what [finish] keeps.
                this@FadeExecutionDriver.handOff(out, player)
                this@FadeExecutionDriver.beginTailOrFinish(out, "planned-fade-done", "driveFade-done")
            }
        }
    }

    /**
     * Graceful-exit guarantee (djmix11): the outgoing player may never be
     * retired while audible. The fade reached its end quietly, or jumped
     * there in one tick (a zero-length span, a cued standby clock ahead) —
     * either way, dissolve over the planned tail (800 ms) or the emergency
     * one (350 ms) before [finish] retires it. The retire is the only
     * hard-cut site left on this path; it now always sees silence.
     */
    fun beginTailOrFinish(out: ExoPlayer, reason: String = "planned", retireSite: String = "driveFade-done") {
        with(host) {
            lastCompleteReason = reason
            val vol = out.volume
            if (vol <= 0.001f) {
                // djmix14-review: a smoothly finished planned fade already
                // dissolved to silence (<= -60 dB). It must still truthfully
                // record mix.exit.tail (0 occurrences in djmix11-13 because this
                // early return skipped it), then retire.
                val planned = fadeMs > 0L
                // vc67: zero-tail must not retain the previous transition tail.
                tailDurationMs = 0L
                AutomixTelemetry.event(
                    "mix.exit.tail",
                    mapOf(
                        "tailMs" to 0L,
                        "mode" to if (planned) "planned" else "emergency",
                        "gain" to vol,
                        "gainDb" to RetireGuard.gainDb(vol),
                        "reason" to reason,
                        "retireSite" to retireSite,
                    ),
                )
                finish()
                return
            }
            tailFromGain = vol
            tailStartMs = nowMs()
            tailTarget = out
            val planned = fadeMs > 0L
            tailDurationMs = if (planned) PhraseConfig.EXIT_TAIL_MIN_MS.toLong() else PhraseConfig.EXIT_TAIL_EMERGENCY_MS.toLong()
            AutomixTelemetry.event(
                "mix.exit.tail",
                mapOf(
                    "tailMs" to tailDurationMs,
                    "mode" to if (planned) "planned" else "emergency",
                    "gain" to vol,
                    "gainDb" to RetireGuard.gainDb(vol),
                    "reason" to reason,
                    "retireSite" to retireSite,
                ),
            )
            onTail()
        }
    }

    fun disarmTailLoopSession() {
        with(host) {
            val s = tailLoopSession ?: return
            captureLoopExitObservation(s)
            tailLoopSession = null
            s.outPort?.disarm()
            s.inPort?.disarm()
            closeLater(s.renderer)
        }
    }

    fun driveTail() {
        with(host) {
            val out = tailTarget ?: outgoing
            if (out == null) {
                this@FadeExecutionDriver.disarmTailLoopSession()
                finish()
                return
            }
            val p = (nowMs() - tailStartMs).toFloat() / tailDurationMs.coerceAtLeast(1L)
            if (p >= 1f) {
                out.volume = 0f
                this@FadeExecutionDriver.disarmTailLoopSession()
                finish()
                return
            }
            // Same equal-power law as the blend itself (MixGain.fall): the sum
            // with the incoming at 1 stays flat through the dissolve.
            out.volume = tailFromGain * MixGain.fall(p.coerceIn(0f, 1f))
        }
    }
}
