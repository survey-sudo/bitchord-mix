package com.music.bitchord.playback.djmix

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.machine.MixStateKind
import com.music.bitchord.djmix.core.machine.PairKey
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.SleepTimer
import com.music.bitchord.playback.isVideoOrigin


/** State observed or updated by [TickEngine]; all access stays on the controller thread. */
@OptIn(UnstableApi::class)
internal interface TickEngineHost : CoordinatorCallbacks {
    val active: () -> ExoPlayer
    val analysisFor: (MediaItem) -> TrackAnalysis
    var classicFallbackReason: String
    var classicHandledGeneration: Long
    var handedOff: Boolean
    var holdWanted: Boolean
    var incoming: ExoPlayer?
    var lastHandoffCompleteAtMs: Long
    var lastPlayWhenReadyChangeReason: Int
    var lastSmartPlanStartMs: Long
    var loopIncomingStarted: Boolean
    var loopPlanKey: String?
    var loopPlanned: LoopMix.Plan?
    var loopTargetStartMs: Long
    val loudnessGain: (String) -> Float
    val mixExecutor: MixExecutor
    var outgoing: ExoPlayer?
    val pairPreparer: com.music.bitchord.playback.djmix.PairPreparer?
    val phaseName: String
    var plannedTransition: Pair<String, Long>?
    val scope: CoroutineScope
    var scrubSettledAtMs: Long
    val tempoEngine: EaseTempoEngine
    var tickerJob: Job?
    var uiStateJob: Job?
    val versionSwapActive: () -> Boolean
    var visualGeneration: Long?
}

/** Runs the cancellable controller ticker and pending-transition observation and dispatch. */
@MainThread
@OptIn(UnstableApi::class)
internal class TickEngine(private val host: TickEngineHost) {
    private var lastTickState: Map<String, Any?>? = null

    private var lastTickStateAtMs = 0L

    private var pausedMarkerAt = 0L

    fun start() {
        with(host) {
                    pairPreparer?.attachExecutionBridge(
                executor = mixExecutor,
                inLevel = { outId, inId ->
                    (loudnessGain(inId) / loudnessGain(outId)).takeIf { it.isFinite() && it > 0f } ?: 1f
                },
                highQuality = { AppSettings.automixPerformanceMode.value != AutomixPerformanceMode.EFFICIENT },
            )
            listenTo(active())
            startAnalysisCollector()
            uiStateJob?.cancel()
            uiStateJob = scope.launch {
                mixExecutor.uiState.collect { ui ->
                    syncInspectorWithUiState(ui)
                    publishAutomixDetail()
                }
            }
            tickerJob?.cancel()
            tickerJob = scope.launch {
                var failedTicks = 0L
                while (isActive) {
                    val nextDelayMs = try {
                        this@TickEngine.tick()
                        val base = tickCadenceMs()
                        // djmix14: firing jitter — within 2 s of the target, tick
                        // every 20 ms so startFade fires within +-25 ms.
                        val nearTarget = when {
                            isArming() -> lastSmartPlanStartMs > 0L && kotlin.math.abs((active().currentPosition - lastSmartPlanStartMs)) <= 2000L
                            isLoopArming() -> loopTargetStartMs > 0L && kotlin.math.abs((active().currentPosition - loopTargetStartMs)) <= 2000L
                            else -> false
                        }
                        if (failedTicks > 0L) {
                            AutomixTelemetry.event("mix.tick.recovered", mapOf("phase" to phaseName, "failedTicks" to failedTicks))
                            failedTicks = 0L
                        }
                        if (nearTarget) 20L else base
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        // A failed iteration must not kill Automix's SupervisorJob child.
                        // Report once per uninterrupted failure burst; success re-arms it.
                        if (failedTicks == 0L) this@TickEngine.reportTickError(error)
                        failedTicks++
                        IDLE_STEP_MS
                    }
                    // Also yield on failure, including deterministic player getter failures.
                    delay(nextDelayMs)
                }
            }
        }
    }

    fun reportTickError(error: Exception) {
        with(host) {
            // Diagnostic getters can fail for the same reason as tick(). Never let
            // them hide the original failure, and never swallow cancellation/errors.
            fun <T> readSafely(read: () -> T): T? = try {
                read()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val player = readSafely { active() }
            AutomixTelemetry.event(
                "mix.tick.error",
                mapOf(
                    "phase" to phaseName,
                    "currentId" to readSafely { player?.currentMediaItem?.mediaId },
                    "nextId" to readSafely {
                        player?.let { deck ->
                            deck.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }
                                ?.let { deck.getMediaItemAt(it).mediaId }
                        }
                    },
                    "positionMs" to readSafely { player?.currentPosition },
                    "exception" to error.javaClass.name,
                    // Frames identify the throw site without messages containing URLs/tokens.
                    "origin" to error.stackTrace.take(4).joinToString(" <- "),
                ),
            )
        }
    }

    // ---- Ticker -------------------------------------------------------------

    fun publishTickState() {
        with(host) {
            val player = active()
            val current = player.currentMediaItem
            val next = player.nextMediaItemIndex.takeIf { it != C.INDEX_UNSET }?.let { player.getMediaItemAt(it) }
            val enabled = AppSettings.smartFadeEnabled.value
            val state = mapOf<String, Any?>(
                "phase" to phaseName,
                "currentId" to current?.mediaId,
                "nextId" to next?.mediaId,
                "smartFadeEnabled" to enabled,
                "isPlaying" to player.isPlaying,
                "playWhenReady" to player.playWhenReady,
                "playbackState" to player.playbackState,
                "durationMs" to player.duration,
                "repeatMode" to player.repeatMode,
                "currentVideoOrigin" to current?.isVideoOrigin,
                "nextVideoOrigin" to next?.isVideoOrigin,
                "versionSwapActive" to versionSwapActive(),
                "inParty" to ListenTogether.state.value.inParty,
                "bitPerfect" to BitPerfectOutput.status.value.active,
            )
            val now = nowMs()
            if (state != lastTickState || (enabled && now - lastTickStateAtMs >= 30_000L)) {
                AutomixTelemetry.event("mix.state", state + ("positionMs" to player.currentPosition))
                lastTickState = state
                lastTickStateAtMs = now
            }
        }
    }

    fun tick() {
        with(host) {
            if (visualGeneration != null && !AppSettings.smartFadeEnabled.value) cancelAutoMixVisual()
            // A pause has to take the other player with it, or one half of the blend
            // carries on alone over a stopped one. Mirrored every tick rather than
            // handled as an event, so audio focus loss, the sleep timer and the
            // pause button all get the same treatment for free. Which player follows
            // which flips at the handoff, halfway through the blend: before it the
            // incoming track shadows the session, after it the outgoing tail does.
            if (isFading() || isBailing()) {
                if (handedOff) {
                    outgoing?.playWhenReady = incoming?.playWhenReady ?: true
                } else {
                    outgoing?.playWhenReady?.let { incoming?.playWhenReady = it }
                }
            }

            // Every tick, not only when a transition can be planned. This used to
            // live inside [considerSmartTransition], which needs an idle phase, a
            // playing player and a known duration — none of which hold during a
            // transition or during the re-buffer after a quality upgrade. The line
            // simply froze on the previous pair, so a track that had not been
            // analysed kept showing the *departing* track's "analysed" until
            // ticking resumed.
            publishAnalysisState()
            publishAutomixDetail()
            expireMixPreview()
            this@TickEngine.checkHandoffPauseWatch()
            if (!isIdle() && AppSettings.smartFadeEnabled.value &&
                !ListenTogether.state.value.inParty && !BitPerfectOutput.status.value.active &&
                !versionSwapActive()
            ) {
                val mState = mixExecutor.currentState()
                if (mState.pair != null && mState !is MixState.Done && mState !is MixState.Failed && mState !is MixState.Suspended) {
                    val tickPlayer = active()
                    mixExecutor.dispatch(
                        MixEvent.Tick(
                            positionMs = tickPlayer.currentPosition.coerceAtLeast(0L),
                            isPlaying = tickPlayer.isPlaying,
                        ),
                    )
                }
            }

            if (isIdle()) tempoEngine.driveEase()

            val watchForStall = isIdle() || isLoopArming()
            dispatchPhaseTick()
            // Only run watchdog when tick started in IDLE or LOOP_ARMING so an
            // in-tick arm-expired cancelArming() -> finish() transition to IDLE
            // is not immediately overridden back into ARMING on that same tick.
            if (watchForStall) {
                checkHardWatchdog()
            }
            try {
                this@TickEngine.publishTickState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Best-effort observation must not interrupt core work or its cadence.
            }
        }
    }

    /**
     * Watches for an unexpected paused session player within 3 s after a completed handoff
     * and records `mix.pause.detected` telemetry once per handover.
     */
    fun checkHandoffPauseWatch() {
        with(host) {
            if (!isIdle() || lastHandoffCompleteAtMs <= 0L) return
            val now = nowMs()
            if (now - lastHandoffCompleteAtMs > 3000L) {
                lastHandoffCompleteAtMs = 0L
                return
            }
            val sessionPlayer = active()
            if (sessionPlayer.playWhenReady) return
            val sinceHandoff = now - lastHandoffCompleteAtMs
            lastHandoffCompleteAtMs = 0L
            AutomixTelemetry.event(
                "mix.pause.detected",
                mapOf(
                    "site" to "post-handoff",
                    "playWhenReady" to sessionPlayer.playWhenReady,
                    "playbackState" to sessionPlayer.playbackState,
                    "isPlaying" to sessionPlayer.isPlaying,
                    "volume" to sessionPlayer.volume,
                    "changeReason" to lastPlayWhenReadyChangeReason,
                    "msSinceHandoff" to sinceHandoff,
                    "positionMs" to sessionPlayer.currentPosition,
                    "loopIncomingStarted" to loopIncomingStarted,
                ),
            )
        }
    }

    /**
     * Arms a crossfade as the playing track runs out, and holds the track's
     * end for it ahead of that — see [holdAhead]. Whatever path through
     * [planAutoTransition] decides no transition is coming lets go of the
     * hold, which is free.
     */
    fun considerAutoTransition() {
        with(host) {
            val player = active()
            // Paused or buffering: the transition is no nearer, and a hold already
            // in place is still exactly as right as it was. The seek bar's marker
            // is the exception — see [refreshMarkerWhilePaused].
            if (!player.isPlaying) {
                this@TickEngine.refreshMarkerWhilePaused(player)
                return
            }
            holdWanted = false
            this@TickEngine.planAutoTransition(player)
            if (!holdWanted && isIdle()) releaseHold()
        }
    }

    /**
     * Puts the Automix window on the seek bar for a track that is not playing.
     *
     * The marker comes out of the same plan that arms a transition, and that
     * plan only ran while playing — so a paused track never got one: a song
     * restored paused when the app opens, or paused before its first tick
     * planned, showed a bare bar with both tracks analysed. This plans for the
     * marker alone, at most once a [PAUSED_MARKER_STEP_MS]; nothing is armed
     * or held, and anything that would clear the marker while
     * playing is left for the playing path to decide.
     */
    fun refreshMarkerWhilePaused(player: ExoPlayer) {
        with(host) {
            if (!AppSettings.smartFadeEnabled.value) return
            val now = nowMs()
            // The mix popup reads the same plan, and someone looking at it is
            // waiting on the next answer rather than glancing at a marker.
            val step = if (AppSettings.mixViewOpen.value) IDLE_STEP_MS else PAUSED_MARKER_STEP_MS
            if (now - pausedMarkerAt < step) return
            pausedMarkerAt = now
            if (versionSwapActive() || ListenTogether.state.value.inParty) return
            if (!player.hasNextMediaItem() || player.repeatMode == Player.REPEAT_MODE_ONE) return
            // A queue restored paused is not prepared yet, so the player has no
            // duration until play: the runtime the item was queued with stands in,
            // then the analysed one.
            val item = player.currentMediaItem ?: return
            val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                ?: queuedDurationMs(item).takeIf { it > 0L }
                ?: (analysisFor(item).duration * 1000).roundToLong().takeIf { it > 0L }
                ?: return
            this@TickEngine.considerSmartTransition(duration, markerOnly = true)
        }
    }

    fun planAutoTransition(player: ExoPlayer) {
        with(host) {
            plannedTransition = null
            // Not while a version swap owns the standby player — see
            // [versionSwapActive]. Nothing to clean up on the way out unlike the
            // party case below: a version swap is a between-tracks affair on the
            // same item, so the transition window and mix flag it would have
            // armed still describe the next track correctly once the swap lets go.
            if (versionSwapActive()) return
            // Not while listening together. A blend starts the next track early, by
            // a length this device decides for itself from its own copy of the
            // audio — so in a party every member would begin the next song at a
            // different moment, and each would then be dragged back by a correcting
            // seek. The transition a party shares is the plain one: whoever reaches
            // the end first publishes the change and everybody moves together. See
            // [PartySync].
            //
            // The analysis behind it stops too, including a pass already running —
            // see [com.music.bitchord.playback.smart.TrackAnalyzer], which reads
            // the party for itself. Nothing here asks for one while this returns,
            // but a request made a tick before the party started would otherwise
            // run to completion: a whole-track decode and two model passes, spent
            // on a transition that cannot happen.
            if (ListenTogether.state.value.inParty) {
                // Left behind by the last pair planned before the party started.
                // The marker describes a transition that is no longer going to
                // happen, and the flag a mix that is no longer running; both would
                // otherwise sit on screen for as long as the party lasts.
                AppSettings.smartTransitionWindow.value = null
                AppSettings.smartMixInProgress.value = false
                return
            }
            // Bit-perfect output carries one stream to the DAC, unchanged. A blend
            // needs two, with gain on both, so the queue plays gapless instead.
            if (BitPerfectOutput.status.value.active) {
                AppSettings.smartTransitionWindow.value = null
                return
            }
            // Nothing to transition *into*, so any analysis state left over from the
            // previous pair is stale — the last track of a queue should not still be
            // claiming both songs are measured.
            if (!player.hasNextMediaItem()) {
                AppSettings.smartTransitionWindow.value = null
                return
            }

            val duration = player.duration
            if (duration == C.TIME_UNSET || duration <= 0L) return

            // Repeating one track would crossfade it into itself, so nothing is
            // armed and no window is marked — but the queue behind the loop has not
            // moved, and what sits after it is still the track that plays next the
            // moment repeat-one comes off.
            //
            // Returning here outright is what made turning repeat off look like it
            // lost an analysis. Analysis is only ever asked for on the way to
            // planning a transition, so for as long as the loop ran nothing asked
            // for the following track at all, and the request that finally arrived
            // when repeat came off was the *first* one — a whole-track decode
            // starting from nothing on a song that was by then seconds away, where
            // an unlooped queue would have had it measured minutes earlier. The
            // measurement is the same either way, so it may as well be made during
            // the loop rather than after it.
            if (player.repeatMode == Player.REPEAT_MODE_ONE) {
                if (AppSettings.smartFadeEnabled.value) requestAnalysisAround(player, duration)
                // Stale otherwise: the marker would keep describing the transition
                // planned for this pair before the loop went on, at a point the
                // playhead now runs past on every lap without anything happening.
                AppSettings.smartTransitionWindow.value = null
                return
            }

            // Automix is its own on/off, independent of the manual crossfade
            // length: it decides its own duration from each pair of tracks (beats,
            // tempo, structure), so requiring a nonzero [AppSettings.crossfadeSeconds]
            // first would tie an automatic feature to a manual one it doesn't use.
            if (AppSettings.smartFadeEnabled.value) {
                this@TickEngine.considerSmartTransition(duration)
                return
            }
            discardPrime()

            if (configuredFadeMs() <= 0L) return
            val fade = fadeFor(duration)
            if (fade <= 0L) return

            player.currentMediaItem?.mediaId?.let { plannedTransition = it to duration - fade }
            val remaining = duration - player.currentPosition
            if (SleepTimer.afterTrack.value) {
                // The sleep timer is due when this track ends. There is no next
                // track to arm a crossfade into, so run the same fade-out this
                // pair would otherwise have used against silence instead — see
                // [beginSleepFadeOut]. No arm-lead margin: nothing is buffering.
                if (remaining > fade) return
                beginSleepFadeOut(fade)
                return
            }
            holdAhead(player, duration)
            // Arm early: the standby has to open the incoming track and buffer to
            // its cue point, and that work has to be finished by the time the fade
            // is due rather than started then.
            if (remaining > fade + ARM_LEAD_MS) return

            begin(fade, endMs = duration, smart = false)
        }
    }

    /**
     * Arms a Automix transition once its plan says the playhead is close
     * enough to start arming for it.
     *
     * Reads the plan's timing (where the fade starts and how long it runs),
     * where the incoming track should be cued
     * ([com.music.bitchord.playback.smart.TransitionPlan.incomingCueTime]),
     * and the tempo-stretch to align it with the outgoing track
     * ([com.music.bitchord.playback.smart.TransitionPlan.incomingPlaybackRate])
     * — see [driveLap], which applies both at the handoff — and the style the
     * blend is rendered in
     * ([com.music.bitchord.playback.smart.TransitionPlan.transitionStyle]),
     * which [rideFilters] turns into a filter ride or a bass swap over the same
     * equal-power gain curve.
     */
    fun considerSmartTransition(duration: Long, markerOnly: Boolean = false) {
        with(host) {
            val pairPreparer = host.pairPreparer
            val player = active()
            val currentItem = player.currentMediaItem ?: return
            val nextIndex = player.nextMediaItemIndex
            val activePrepStates = setOf(
                MixStateKind.PREPARING, MixStateKind.MOVED, MixStateKind.READY,
                MixStateKind.ARMED, MixStateKind.LAST_RESORT,
            )
            if (nextIndex == C.INDEX_UNSET || nextIndex >= player.mediaItemCount) {
                val mState = mixExecutor.currentState()
                if (mState.pair != null && mState.stateKind in activePrepStates) {
                    mixExecutor.dispatch(MixEvent.QueueEdited(null, player.currentPosition.coerceAtLeast(0L)))
                }
                AppSettings.smartTransitionWindow.value = null
                return
            }
            val nextItem = player.getMediaItemAt(nextIndex)
            ensureOpportunity(currentItem.mediaId, nextItem.mediaId)
            if (currentItem.isVideoOrigin || nextItem.isVideoOrigin) {
                val mState = mixExecutor.currentState()
                if (mState.pair != null && mState.stateKind in activePrepStates) {
                    mixExecutor.dispatch(MixEvent.QueueEdited(null, player.currentPosition.coerceAtLeast(0L)))
                }
                AppSettings.smartTransitionWindow.value = null
                AppSettings.smartMixInProgress.value = false
                return
            }
            val nextDuration = nextItemDurationMs(nextIndex, nextItem)
            val posMs = player.currentPosition.coerceAtLeast(0L)

            requestAnalysisAround(player, duration)
            askLyrics(currentItem, duration)
            askLyrics(nextItem, nextDuration)

            if (SleepTimer.afterTrack.value) {
                val sleepFade = configuredFadeMs().takeIf { it > 0L }
                    ?: (DEFAULT_SMART_FALLBACK_SECONDS * 1000.0).roundToLong()
                if (!markerOnly && duration - posMs <= sleepFade) {
                    beginSleepFadeOut(sleepFade)
                }
                return
            }

            val pair = PairKey(currentItem.mediaId, nextItem.mediaId)
            val coreLevel = toCoreLevel(AppSettings.automixDjLevel.value)
            var mState = mixExecutor.currentState()
            if (mState.djLevel != coreLevel) {
                mState = mixExecutor.dispatch(MixEvent.LevelChanged(coreLevel)).first
            }

            val nativeAvailable = isNativeRendererAvailable(player)
            if (pairPreparer != null && nativeAvailable) {
                val inActivePrep = mState.stateKind in activePrepStates
                when {
                    mState is MixState.Idle ||
                        (mState is MixState.Done && mState.pair != pair) ||
                        (mState is MixState.Failed && mState.pair != pair) ||
                        (mState is MixState.Suspended && mState.pair != pair) -> {
                        classicFallbackReason = ""
                        classicHandledGeneration = -1L
                        mState = mixExecutor.dispatch(
                            MixEvent.NextKnown(pair, posMs, coreLevel, player.isPlaying),
                        ).first
                    }
                    inActivePrep && mState.pair?.outTrackId != pair.outTrackId -> {
                        classicFallbackReason = ""
                        classicHandledGeneration = -1L
                        mState = mixExecutor.dispatch(
                            MixEvent.NextKnown(pair, posMs, coreLevel, player.isPlaying),
                        ).first
                    }
                    inActivePrep && mState.pair?.inTrackId != pair.inTrackId -> {
                        classicFallbackReason = ""
                        classicHandledGeneration = -1L
                        mState = mixExecutor.dispatch(
                            MixEvent.QueueEdited(pair.inTrackId, posMs),
                        ).first
                    }
                }
                if (mState.pair == pair && mState !is MixState.Failed &&
                    mState !is MixState.Suspended && mState !is MixState.Done
                ) {
                    mState = mixExecutor.dispatch(MixEvent.Tick(posMs, player.isPlaying)).first
                }

                if (!isIdle()) return

                if (mState !is MixState.Failed) {
                    if (mState is MixState.Suspended) {
                        AppSettings.smartTransitionWindow.value = null
                        plannedTransition = null
                        return
                    }
                    val v1Plan = mState.plan
                    val targetStartMs = when (mState) {
                        is MixState.Armed -> mState.armAtMs
                        is MixState.LastResort -> mState.targetDownbeatMs
                        is MixState.Moved -> mState.plan?.frozenExitMs ?: mState.newExitMs
                        is MixState.Ready -> mState.plan.frozenExitMs
                        is MixState.Preparing -> mState.plan?.frozenExitMs
                        else -> null
                    }
                    val targetSpanMs = v1Plan?.overlapDurationMs?.coerceAtLeast(1_000L) ?: 0L
                    val windowAllowed = nowMs() >= scrubSettledAtMs || duration - posMs <= 5_000L
                    if (v1Plan != null && targetStartMs != null && targetStartMs > 0L && duration > 0L) {
                        lastSmartPlanStartMs = targetStartMs
                        plannedTransition = currentItem.mediaId to targetStartMs
                        val prepared = pairPreparer.snapshotFor(pair.outTrackId, pair.inTrackId)
                        prepared?.plan?.let { p ->
                            loopPlanned = p
                            loopPlanKey = "${pair.outTrackId}->${pair.inTrackId}|${p.out.loopStart}"
                            if (AppSettings.mixViewOpen.value && AppSettings.smartMixView.value == null) {
                                prefetchMixWaves(
                                    currentItem, nextItem, duration, nextDuration,
                                    p.out.loopStart,
                                    (p.out.loopStart + LoopMix.Clock(p).releaseAt).coerceAtLeast(p.out.loopStart + 0.5),
                                    p.inn.loopStart,
                                    p.inn.bpm / p.out.bpm.coerceAtLeast(1.0),
                                )
                            }
                        }
                        AppSettings.smartTransitionWindow.value = if (windowAllowed && targetStartMs < duration) {
                            val s = (targetStartMs.toDouble() / duration).toFloat().coerceIn(0f, 1f)
                            val e = ((targetStartMs + targetSpanMs).toDouble() / duration).toFloat().coerceIn(s, 1f)
                            TransitionWindow(start = s, end = e)
                        } else null
                        holdAhead(player, duration)
                    } else {
                        AppSettings.smartTransitionWindow.value = null
                        plannedTransition = null
                    }
                    return
                }
            } else if (!nativeAvailable && mState.pair != null && mState.stateKind in activePrepStates) {
                val reason = nativeUnavailabilityReason(player)
                classicFallbackReason = reason
                mixExecutor.dispatch(MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, reason, recoverable = false))
                mState = mixExecutor.currentState()
            }

            // Classic two-player crossfade runs strictly when the native renderer is unavailable or failed.
            val reason = when {
                !nativeAvailable -> nativeUnavailabilityReason(player)
                mState is MixState.Failed -> mState.message.ifBlank { mState.reason?.name?.lowercase() ?: mState.kind.name.lowercase() }
                else -> "preparer-unavailable"
            }
            classicFallbackReason = reason
            classicFallbackOnRendererUnavailable(
                player = player,
                currentItem = currentItem,
                nextItem = nextItem,
                duration = duration,
                nextDuration = nextDuration,
                reason = reason,
                markerOnly = markerOnly,
                failedPlan = (mState as? MixState.Failed)?.plan,
            )
        }
    }
}
