package com.music.bitchord.playback.djmix

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel as CoreDjLevel
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixMachineConfig
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.machine.PairKey
import com.music.bitchord.playback.audio.bitperfect.BitPerfectOutput
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.shouldDeclineToMix
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.DeckBudget
import com.music.bitchord.playback.smart.RetireGuard
import com.music.bitchord.playback.smart.SeekAction
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToLong
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.MixDebug
import com.music.bitchord.playback.isVideoOrigin


/** State observed or updated by [PrepCoordinator]; all access stays on the controller thread. */
@OptIn(UnstableApi::class)
internal interface PrepCoordinatorHost : CoordinatorCallbacks {
    val active: () -> ExoPlayer
    var activeMachineGeneration: Long
    val analysisFor: (MediaItem) -> TrackAnalysis
    var analysisReplanJob: Job?
    var blendArmLeadMs: Long
    var classicFallbackReason: String
    var classicHandledGeneration: Long
    val countdownStabilizer: CountdownStabilizer
    val deckTiming: DeckTiming
    var handedOff: Boolean
    var hardWatchdogCooldownUntil: Long
    var hardWatchdogTriggeredPair: String?
    var heldAtEnd: ExoPlayer?
    var incoming: ExoPlayer?
    var internalHandoverSeek: Boolean
    var lastArmDeathKey: String?
    var lastDispatchedPairKey: PairKey?
    var lastEmittedPlanVerdict: String?
    var lastFallbackLogKey: String?
    var lastPlayWhenReadyChangeReason: Int
    var lastRejectedLogKey: String?
    var lastSmartPlan: TransitionPlan?
    var lastSmartPlanStartMs: Long
    var listeningTo: ExoPlayer?
    var loopBuildStartedAtMs: Long
    var loopInItem: MediaItem?
    var loopIncomingStarted: Boolean
    var loopPlanKey: String?
    var loopPlanned: LoopMix.Plan?
    val loopPortOf: (ExoPlayer) -> LoopMixPort?
    val loopRegionCached: (String, Int, Double, Double) -> Boolean
    var loopScript: PhraseScript?
    var loopSession: LoopMixSession?
    var loopSuppressedPair: String?
    var loopSuppressedUntil: Long
    var loopTarget: LoopMix.Plan?
    var loopTargetStartMs: Long
    val lyricSpans: java.util.concurrent.ConcurrentHashMap<String, LyricSpans>
    val mixExecutor: MixExecutor
    var nativeBinding: NativeBinding?
    var outgoing: ExoPlayer?
    var outgoingItemIndex: Int
    val pairPreparer: com.music.bitchord.playback.djmix.PairPreparer?
    val phaseName: String
    var pinnedCatchUpKey: String?
    var pinnedCatchUpPlan: TransitionPlan?
    var planGeneration: Long
    var plannedNextId: String?
    var primedKey: String?
    var primedSession: LoopMixSession?
    val scope: CoroutineScope
    var scrubEpoch: Long
    var scrubSettledAtMs: Long
    var shownCountdownKey: String?
    var shownLoopStartMs: Long
    var smartFadeActive: Boolean
    val standby: () -> ExoPlayer
    var straightPreparedPair: com.music.bitchord.playback.djmix.PreparedPair?
    var suppressScrubUntilMs: Long
    val tempoEngine: EaseTempoEngine
    var terminalRecorded: Boolean
    val versionSwapActive: () -> Boolean
    var watchdogFired: Boolean
}

/** Coordinates preparation requests and controller-thread player and executor callbacks. */
@MainThread
@OptIn(UnstableApi::class)
internal class PrepCoordinator(
    private val host: PrepCoordinatorHost,
    private val callbackAdmission: () -> Boolean = { true },
) {
    private var incomingPreparedAt = 0L

    private var prepListener: Player.Listener? = null

    fun notePrep(player: ExoPlayer) {
        with(host) {
            prepListener?.let { previous ->
                runCatching { player.removeListener(previous) }
            }
            incomingPreparedAt = nowMs()
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (!callbackAdmission()) return
                    if (playbackState != Player.STATE_READY || incomingPreparedAt == 0L) return
                    val prep = nowMs() - incomingPreparedAt
                    incomingPreparedAt = 0L
                    blendArmLeadMs = com.music.bitchord.playback.smart.PrepLead.next(blendArmLeadMs, prep)
                    player.removeListener(this)
                    if (prepListener === this) prepListener = null
                }
            }
            prepListener = listener
            player.addListener(listener)
        }
    }

    fun toCoreLevel(level: com.music.bitchord.playback.smart.choreo.DjLevel): CoreDjLevel = with(host) {
        when (level) {
            com.music.bitchord.playback.smart.choreo.DjLevel.SUBTLE -> CoreDjLevel.SUBTLE
            com.music.bitchord.playback.smart.choreo.DjLevel.CLUB -> CoreDjLevel.CLUB
            com.music.bitchord.playback.smart.choreo.DjLevel.FESTIVAL -> CoreDjLevel.FESTIVAL
        }
    }

    fun toChoreoLevel(level: CoreDjLevel): com.music.bitchord.playback.smart.choreo.DjLevel = with(host) {
        when (level) {
            CoreDjLevel.SUBTLE -> com.music.bitchord.playback.smart.choreo.DjLevel.SUBTLE
            CoreDjLevel.CLUB -> com.music.bitchord.playback.smart.choreo.DjLevel.CLUB
            CoreDjLevel.FESTIVAL -> com.music.bitchord.playback.smart.choreo.DjLevel.FESTIVAL
        }
    }

    fun isNativeRendererAvailable(player: ExoPlayer): Boolean {
        with(host) {
            val outPort = loopPortOf(player) ?: return false
            if (!LoopMixRenderer.available || !outPort.supportsLoopMix) return false
            return loopPortOf(standby()) != null
        }
    }

    fun nativeUnavailabilityReason(player: ExoPlayer): String {
        with(host) {
            val outPort = loopPortOf(player)
            return when {
                !LoopMixRenderer.available -> "no renderer"
                outPort == null -> "no output port"
                !outPort.supportsLoopMix -> "this output can't take it"
                loopPortOf(standby()) == null -> "no standby sink"
                else -> "renderer_unavailable"
            }
        }
    }

    val mixDeckAdapter: MixDeckAdapter = with(host) { object : MixDeckAdapter {
        override fun startPrepare(
            pair: PairKey,
            generation: Long,
            djLevel: CoreDjLevel,
            positionMs: Long,
            minExitMs: Long?,
            preferNoStemVariant: Boolean,
            excludedRecipeIds: Set<String>,
        ) {
            activeMachineGeneration = generation
            val preparer = pairPreparer ?: return
            val player = runCatching { active() }.getOrNull() ?: return
            val baseReq = this@PrepCoordinator.prepRequestFor(player, djLevelOverride = this@PrepCoordinator.toChoreoLevel(djLevel)) ?: return
            if (baseReq.outTrack.id != pair.outTrackId || baseReq.inTrack.id != pair.inTrackId) return
            val excluded = excludedRecipeIds.mapNotNull {
                com.music.bitchord.playback.smart.choreo.RecipeId.fromCode(it)
            }.toSet()
            preparer.trigger(
                baseReq.copy(
                    positionSec = positionMs.coerceAtLeast(0L) / 1000.0,
                    minExitMs = minExitMs,
                    preferNoStemVariant = preferNoStemVariant,
                    excludedRecipes = excluded,
                    excludedRecipeIds = excludedRecipeIds.toSet(),
                    machineGeneration = generation,
                ),
                force = true,
            )
        }

        override fun cancelPrepare(generation: Long) {
            val cur = pairPreparer?.state?.value
            if (cur != null && cur.generation == generation) {
                pairPreparer?.invalidate("cancelled:$generation")
            }
            if (isIdle()) {
                discardPrime()
            }
        }

        override fun prepareRendererOffThread(planId: String, generation: Long, useNoStemVariant: Boolean) {
            val preparer = pairPreparer ?: return
            val player = runCatching { active() }.getOrNull()
            if (player != null && !this@PrepCoordinator.isNativeRendererAvailable(player)) {
                val reason = this@PrepCoordinator.nativeUnavailabilityReason(player)
                classicFallbackReason = reason
                AutomixTelemetry.event(
                    "mix.classic.reason",
                    mapOf("reason" to reason, "planId" to planId, "generation" to generation, "stage" to "prepare"),
                )
                mixExecutor.dispatch(MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, reason, recoverable = false))
                return
            }
            preparer.prepareRendererOffThread(planId, generation, useNoStemVariant)
        }

        override fun armTransition(planId: String, generation: Long, atMs: Long, btLatencyOffsetMs: Long) {
            this@PrepCoordinator.armFromExecutor(planId, generation, atMs)
        }

        override fun startTransition(planId: String, generation: Long) {
            this@PrepCoordinator.startFromExecutor(planId, generation)
        }

        override fun pauseBothInSync(generation: Long, byUser: Boolean) = onControllerThread {
            val out = outgoing ?: runCatching { active() }.getOrNull()
            val into = incoming
            out?.playWhenReady = false
            into?.playWhenReady = false
        }

        override fun resumeBothInSync(generation: Long) = onControllerThread {
            val out = outgoing
            val into = incoming
            if (handedOff) {
                into?.playWhenReady = true
                out?.playWhenReady = true
            } else if (out != null && into != null) {
                out.playWhenReady = true
                if (loopIncomingStarted || isFading()) {
                    into.playWhenReady = true
                }
            } else {
                runCatching { active().playWhenReady = true }
            }
        }

        override fun retireDeck(deck: DeckTarget, generation: Long, preserveSurvivor: Boolean) {
            if (!preserveSurvivor) return
            onControllerThread {
                when {
                    isLoopArming() || isLoopRunning() -> {
                        if (deck == DeckTarget.OUT && handedOff) {
                            finishLoop()
                        } else {
                            loopBail(
                                "executor-retire-${deck.name.lowercase()}",
                                settle = false,
                                preserveSeek = true,
                                deferDisarm = handedOff,
                            )
                        }
                    }
                    isArming() -> cancelArming("executor-retire-${deck.name.lowercase()}")
                    isFading() -> bail(force = true)
                }
            }
        }

        override fun completeHandover(planId: String, generation: Long) = onControllerThread {
            val out = outgoing
            val into = incoming
            if (out != null && into != null && !handedOff) {
                if (isLoopRunning()) {
                    val session = loopSession
                    val m = session?.mixSeconds(System.nanoTime(), out.isPlaying)
                    if (session != null && canAdoptSessionPlayer(loopIncomingStarted)) {
                        handOff(out, into, m, session.clock.releaseAt)
                    }
                } else if (isFading()) {
                    handOff(out, into)
                }
            }
        }
    }
    }

    fun onControllerThread( block: () -> Unit) {
        with(host) {
            if (!callbackAdmission()) return
            val admittedGeneration = mixExecutor.currentState().generation
            fun invokeIfCurrent() {
                if (callbackAdmission() && mixExecutor.currentState().generation == admittedGeneration) block()
            }
            val looperThread = runCatching { active().applicationLooper.thread }.getOrNull()
            if (looperThread == null || Thread.currentThread() === looperThread) {
                invokeIfCurrent()
            } else {
                scope.launch { invokeIfCurrent() }
            }
        }
    }

    fun armFromExecutor(planId: String, generation: Long, atMs: Long) = with(host) {
        onControllerThread {
            if (!AppSettings.smartFadeEnabled.value || ListenTogether.state.value.inParty ||
                BitPerfectOutput.status.value.active || versionSwapActive()
            ) return@onControllerThread
            if (isLoopArming() && loopSession?.preparationGeneration == generation) {
                loopTargetStartMs = atMs.coerceAtLeast(0L)
                return@onControllerThread
            }
            if (!isIdle() && !isLoopArming()) return@onControllerThread
            val player = runCatching { active() }.getOrNull() ?: return@onControllerThread
            if (!this@PrepCoordinator.isNativeRendererAvailable(player)) {
                val reason = this@PrepCoordinator.nativeUnavailabilityReason(player)
                classicFallbackReason = reason
                AutomixTelemetry.event(
                    "mix.classic.reason",
                    mapOf("reason" to reason, "planId" to planId, "generation" to generation, "stage" to "arm"),
                )
                mixExecutor.dispatch(MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, reason, recoverable = false))
                return@onControllerThread
            }
            val currentItem = player.currentMediaItem ?: return@onControllerThread
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex == C.INDEX_UNSET || nextIndex >= player.mediaItemCount) return@onControllerThread
            val nextItem = player.getMediaItemAt(nextIndex)
            val outPort = loopPortOf(player) ?: return@onControllerThread
            val into = standby()
            val inPort = loopPortOf(into) ?: return@onControllerThread
            if (player === into) return@onControllerThread
            val sampleRate = outPort.sampleRate
            if (sampleRate <= 0) return@onControllerThread

            val preparer = pairPreparer ?: return@onControllerThread
            val pair = preparer.frozen(currentItem.mediaId, nextItem.mediaId, generation)
                ?: preparer.snapshotFor(currentItem.mediaId, nextItem.mediaId)?.takeIf {
                    it.generation == generation && it.plan != null
                }
                ?: run {
                    mixExecutor.dispatch(
                        MixEvent.RendererError(
                            FailureKind.RENDERER_PREPARE_ERROR,
                            "frozen_pair_missing",
                            recoverable = false,
                        ),
                    )
                    return@onControllerThread
                }
            val plan = pair.plan ?: return@onControllerThread
            val mState = mixExecutor.currentState()
            val wantNoStem = when (mState) {
                is MixState.Armed -> mState.usingNoStemVariant
                is MixState.Ready -> mState.usingNoStemVariant
                is MixState.LastResort -> true
                else -> pair.useNoStemVariant
            }
            val session = preparer.consumePreparedSession(currentItem.mediaId, nextItem.mediaId, generation, wantNoStem)
                ?: preparer.consumePreparedSession(currentItem.mediaId, nextItem.mediaId, generation, !wantNoStem)
                ?: loopSession?.takeIf { it.preparationGeneration == generation }
                ?: preparer.prepareRenderer(pair, useNoStemVariant = wantNoStem)
            if (session == null) {
                mixExecutor.dispatch(
                    MixEvent.RendererError(
                        FailureKind.RENDERER_PREPARE_ERROR,
                        "arm_session_null",
                        recoverable = false,
                    ),
                )
                return@onControllerThread
            }

            val durationMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                ?: (pair.outAnalysis?.duration?.times(1000.0))?.roundToLong()
                ?: 0L
            val nextDurationMs = nextItemDurationMs(nextIndex, nextItem)
            rememberLoopPair(
                currentItem, nextItem,
                pair.outAnalysis ?: analysisFor(currentItem),
                pair.inAnalysis ?: analysisFor(nextItem),
                durationMs, nextDurationMs,
            )
            discardPrime()
            tempoEngine.endEase(clickless = false)
            MixDebug.reset()
            onLoopArming()
            smartFadeActive = true
            handedOff = false
            outgoing = player
            incoming = into
            outgoingItemIndex = player.currentMediaItemIndex
            loopPlanned = plan
            loopPlanKey = "${currentItem.mediaId}->${nextItem.mediaId}|${plan.out.loopStart}"
            loopTarget = plan
            loopTargetStartMs = atMs.coerceAtLeast(0L)
            loopScript = pair.phraseScript
            holdAtEnd(player)
            deckTiming.clear()
            deckTiming.deadlineMs = DeckBudget.deadlineMs(nowMs(), player.isPlaying, loopTargetStartMs, player.currentPosition)
            loopBuildStartedAtMs = 0L
            val context = ensureOpportunity(currentItem.mediaId, nextItem.mediaId)
            val revision = reviseRoute(plan)
            val coarseDecline = shouldDeclineToMix(
                pair.outAnalysis ?: analysisFor(currentItem),
                pair.inAnalysis ?: analysisFor(nextItem),
            )
            nativeBinding = NativeBinding(context, revision, generation.toInt(), coarseDecline)
            armLoop(session, player, into, nextIndex, outPort, inPort)
        }
    }

    fun startFromExecutor(planId: String, generation: Long) = with(host) {
        onControllerThread {
            if (isLoopRunning() && loopSession?.preparationGeneration == generation) return@onControllerThread
            if (loopSession == null || loopSession?.preparationGeneration != generation) {
                val targetAtMs = mixExecutor.currentState().plan?.let {
                    (it.lastPhraseDownbeatMs ?: it.frozenExitMs)
                } ?: runCatching { active().currentPosition.coerceAtLeast(0L) }.getOrDefault(0L)
                this@PrepCoordinator.armFromExecutor(planId, generation, targetAtMs)
            }
            val session = loopSession ?: return@onControllerThread
            val out = outgoing ?: return@onControllerThread
            val outPort = session.outPort
            if (outPort != null && !outPort.tookOver && isLoopArming()) {
                Log.d(TAG, "executor Start planId=$planId gen=$generation pos=${out.currentPosition}ms tookOver=${outPort.tookOver}")
            }
        }
    }

    val listener: Player.Listener = with(host) { object : Player.Listener {
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (!callbackAdmission()) return
            // The listener moving the playhead. djmix13: never silently
            // fire a stale plan or double-fire the incoming deck.
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                // djmix18 (Bug 2): the loop-mix handover's own corrective seek is not a
                // scrub. Ignore it entirely — no resetPlansForScrub, no second seek.
                val nowMs0 = nowMs()
                if (internalHandoverSeek || nowMs0 < suppressScrubUntilMs) {
                    internalHandoverSeek = false
                    AutomixTelemetry.event(
                        "mix.seek",
                        mapOf(
                            "phase" to phaseName,
                            "positionBeforeMs" to oldPosition.positionMs,
                            "positionAfterMs" to newPosition.positionMs,
                            "action" to "handover-suppressed",
                            "planAgeMs" to planAgeMs(nowMs0),
                        ),
                    )
                    return
                }
                val now = nowMs()
                val tookOver = loopSession?.outPort?.tookOver == true
                val seekPhase = seekPhase(tookOver)
                val sameMediaItem = oldPosition.mediaItemIndex == newPosition.mediaItemIndex
                if (SeekAction.shouldIgnoreSeek(seekPhase, oldPosition.positionMs, newPosition.positionMs, sameMediaItem)) {
                    AutomixTelemetry.event(
                        "mix.seek",
                        mapOf(
                            "phase" to phaseName,
                            "positionBeforeMs" to oldPosition.positionMs,
                            "positionAfterMs" to newPosition.positionMs,
                            "action" to "spurious-ignored",
                            "planAgeMs" to planAgeMs(now),
                        ),
                    )
                    return
                }
                val action = SeekAction.decide(
                    seekPhase,
                    appSettingsMarkerShown() || shownCountdownKey != null,
                )
                tempoEngine.cancelPendingSpeedChange()
                AutomixTelemetry.event(
                    "mix.seek",
                    mapOf(
                        "phase" to phaseName,
                        "positionBeforeMs" to oldPosition.positionMs,
                        "positionAfterMs" to newPosition.positionMs,
                        "action" to action.name.lowercase(),
                        "planAgeMs" to planAgeMs(now),
                    ),
                )
                when (action) {
                    SeekAction.Action.HONOUR -> Unit
                    SeekAction.Action.CANCEL -> {
                        cancelAutoMixVisual()
                        this@PrepCoordinator.resetPlansForScrub()
                        AutomixTelemetry.event("mix.plan.rejected", mapOf("rung" to "scrub-cancelled", "reason" to "scrub", "phase" to phaseName))
                        if (isArming()) cancelArming("scrub-cancelled") else bail(force = true)
                    }
                    SeekAction.Action.EMERGENCY_TAIL -> {
                        cancelAutoMixVisual()
                        this@PrepCoordinator.resetPlansForScrub()
                        val isLoopRunning = isLoopRunning() || loopSession?.outPort?.tookOver == true
                        if (isLoopRunning) {
                            val wasHandedOff = handedOff
                            val activePlayer = active()
                            val audibleSpare = if (wasHandedOff) outgoing else incoming
                            val session = loopSession
                            session?.let { s ->
                                val heardOut = runCatching { s.renderer.heard(LoopMixRenderer.OUT) }.getOrNull() ?: 0.0
                                val heardIn = runCatching { s.renderer.heard(LoopMixRenderer.IN) }.getOrNull() ?: 0.0
                                runCatching { s.renderer.setFadeOut(LoopMixRenderer.OUT, (heardOut + 0.35).coerceAtLeast(0.0)) }
                                runCatching { s.renderer.setFadeOut(LoopMixRenderer.IN, (heardIn + 0.35).coerceAtLeast(0.0)) }
                            }
                            loopBail("seek-emergency-tail", settle = false, preserveSeek = true, deferDisarm = true)
                            @Suppress("UNUSED_VARIABLE") val captured = activePlayer to audibleSpare
                        } else {
                            val spare = if (handedOff) outgoing else incoming
                            if (spare != null && RetireGuard.audible(spare.volume)) {
                                beginTailOrFinish(spare, "seek-emergency-tail", "discontinuity:" + phaseName)
                            } else {
                                bail()
                            }
                        }
                    }
                }
                if (sameMediaItem) {
                    mixExecutor.dispatch(MixEvent.UserSeek(newPosition.positionMs))
                    this@PrepCoordinator.notifyPairPreparer(fromSeek = true)
                }
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (!callbackAdmission()) return
            if (reason != Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) return
            this@PrepCoordinator.onPlaylistChanged(timeline)
            this@PrepCoordinator.notifyPairPreparer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (!callbackAdmission()) return
            tempoEngine.cancelPendingSpeedChange()
            when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> bail()
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> {
                    if (!handedOff) cancelAutoMixVisual()
                    expireIdleOpportunity("natural-auto-advance")
                    this@PrepCoordinator.ranOutBeforeHandoff()
                }
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> {
                    cancelAutoMixVisual()
                    expireIdleOpportunity("natural-repeat")
                }
            }
            lastDispatchedPairKey = null
            this@PrepCoordinator.onTrackChanged()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (!callbackAdmission()) return
            AutomixTelemetry.event("mix.error", mapOf("error" to error.message.orEmpty(), "phase" to phaseName))
            mixExecutor.dispatch(
                MixEvent.RendererError(
                    FailureKind.RENDERER_RUNTIME_ERROR,
                    error.message.orEmpty(),
                    recoverable = false,
                ),
            )
            bail()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!callbackAdmission()) return
            if (!playWhenReady) tempoEngine.cancelPendingSpeedChange()
            lastPlayWhenReadyChangeReason = reason
            when {
                !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ->
                    mixExecutor.dispatch(MixEvent.UserPause)
                !playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS ->
                    mixExecutor.dispatch(MixEvent.AudioFocusLost(transient = true))
                playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST ->
                    mixExecutor.dispatch(MixEvent.UserResume)
                playWhenReady && !mixExecutor.currentState().userPaused &&
                    reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM ->
                    mixExecutor.dispatch(MixEvent.UserResume)
            }
            if (playWhenReady || reason != Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
            val held = heldAtEnd ?: return
            if (held !== listeningTo) return
            val into = incoming
            if (isFading() && !handedOff && into != null) {
                // The blend is running and the incoming track is already
                // playing under it: this is just the handoff, a little late.
                handOff(held, into)
                return
            }
            // No blend to hand to. Let the queue move on as it would have.
            releaseHold()
            if (isArming()) cancelArming("outgoing-ended-while-arming")
            held.play()
        }
    }
    }

    /**
     * The session player ran off the end of its track, unheld, before the
     * handoff took it off that track — so it is now playing the song this
     * transition was bringing in, from its first note, on the outgoing side.
     *
     * Still arming, that copy is simply the next track arriving the plain
     * way, but on a sink the arming ride had left low-passed for the blend,
     * and [driveArming] would go on to wait for the *new* song to reach the
     * old one's mix-out point and blend it into itself. So the transition is
     * dropped and the queue moves on as it would have. Mid-blend, the incoming
     * player already has the song at its cue: that is the handoff, late, and
     * the copy on the outgoing side is silenced as [ranOn] does.
     */
    /**
     * The queue changed under a plan. If the next track is no longer the one
     * the plan was built for, the prime is discarded and an in-flight blend
     * bails. A change that only touches items after next rebuilds the standby
     * snapshot instead. Every index is checked against [Timeline.windowCount].
     */
    fun onPlaylistChanged(timeline: Timeline) {
        with(host) {
            val player = listeningTo ?: return
            val count = timeline.windowCount
            val current = player.currentMediaItemIndex
            if (current < 0 || current >= count) {
                this@PrepCoordinator.dropForQueueEdit("current index $current outside $count windows")
                return
            }
            val next = current + 1
            val nextId = if (next < count) {
                val window = Timeline.Window()
                timeline.getWindow(next, window).mediaItem.mediaId
            } else {
                null
            }
            val expected = plannedNextId ?: loopInItem?.mediaId
            // djmix15: after the handoff the session player IS the incoming
            // track (i+1), so its "next" is i+2 by definition. Comparing that
            // to the armed expected (i+1) is a certain false queueEdit 1.7-6.9s
            // after every classic startFade (phone PIN-locked). Only the
            // pre-handoff window can judge next against the plan; the active
            // current-index check above still catches a user removing or
            // replacing the audible incoming track. See [SeekAction.isNextEdit].
            if (SeekAction.isNextEdit(handedOff, nextId, expected)) {
                this@PrepCoordinator.dropForQueueEdit("next is $nextId, plan wanted $expected")
                return
            }
            val out = outgoing
            val into = incoming
            // Same reason: after the handoff the standby snapshot is obsolete
            // (the retiring player may have been truncated at handOff), so a
            // diverged snapshot is not a user edit.
            if (!handedOff && out != null && into != null && !isIdle() && !reconcileQueue(out, into)) {
                this@PrepCoordinator.dropForQueueEdit("standby snapshot diverged")
            }
        }
    }

    /**
     * djmix14 scrub reset: every plan, lock and countdown is dropped so a
     * stale [loopPlanned] (past loopStart) can never short-circuit classic
     * begin() or re-lock a dead 0s countdown after a seek. Called on both
     * CANCEL and EMERGENCY_TAIL scrub paths.
     */
    fun resetPlansForScrub() {
        with(host) {
            if (isIdle() && opportunity != null && !terminalRecorded) {
                mixEvent("mix.plan.rejected", mapOf("stage" to "terminal", "outcome" to "cancelled",
                    "terminal" to true, "refusalCode" to "scrub-cancelled", "reason" to "scrub"))
                terminalRecorded = true
                opportunity = null
            }
            scrubEpoch++
            planGeneration++
            analysisReplanJob?.cancel()
            analysisReplanJob = null
            countdownStabilizer.reset()
            scrubSettledAtMs = nowMs() + SCRUB_SETTLE_MS
            pinnedCatchUpKey = null
            pinnedCatchUpPlan = null
            shownCountdownKey = null
            shownLoopStartMs = 0L
            watchdogFired = false
            loopPlanned = null
            loopPlanKey = null
            loopScript = null
            loopTargetStartMs = 0L
            loopTarget = null
            classicFallbackReason = ""
            classicHandledGeneration = -1L
            lastSmartPlan = null
            lastSmartPlanStartMs = 0L
            lastRejectedLogKey = null
            lastEmittedPlanVerdict = null
            lastFallbackLogKey = null
            lastArmDeathKey = null
            loopSuppressedPair = null
            loopSuppressedUntil = 0L
            AppSettings.smartTransitionWindow.value = null
            discardPrime()
        }
    }

    /** Minimum lead (s) a new loop start needs ahead of the playhead. */
    fun minLoopLeadSec(): Double {
        with(host) {
            val primed = primedSession != null && primedKey != null
            return if (primed) 0.8 else 4.5
        }
    }

    /**
     * djmix14-review: single fast-ready check for all 4 sites. True when the
     * decks are already primed for this exact key, or both exact PCM regions
     * are in the RAM cache (bounds match LoopMixSession.build exactly).
     */
    fun isLoopFastReady(
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        sampleRate: Int,
    ): Boolean {
        with(host) {
            if (primedSession != null && primedKey == primeKey(plan, outItem, inItem, sampleRate)) return true
            val straight = straightPreparedPair
            if (straight != null && ChoreoSessionGlue.isSamePlan(straight.plan ?: return false, plan) &&
                straight.sampleRate == sampleRate &&
                straight.outId == outItem.mediaId && straight.inId == inItem.mediaId
            ) return true
            val b = LoopMix.regionBounds(plan)
            return loopRegionCached(outItem.mediaId, sampleRate, b.outFrom, b.outTo) &&
                loopRegionCached(inItem.mediaId, sampleRate, b.inFrom, b.inTo)
        }
    }

    /** Lead for [plan] via the shared fast-ready check: 0.8 cached/primed, 4.5 cold. */
    fun fastLeadSec(
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        sampleRate: Int,
    ): Double = with(host) {
        if (this@PrepCoordinator.isLoopFastReady(plan, outItem, inItem, sampleRate)) 0.8 else 4.5
    }

    fun isStraightRoute(plan: LoopMix.Plan): Boolean = with(host) {
        plan.alternativeKind == "v2-straight"
    }

    /**
     * Returns the exact frozen preparation matching `(outItem, inItem, generation, level)`
     * from [PairPreparer], never triggering decode or transition-time re-planning.
     */
    fun frozenPreparedFor(
        outItem: MediaItem,
        inItem: MediaItem,
        generation: Long,
        sampleRate: Int,
        level: com.music.bitchord.playback.smart.choreo.DjLevel = AppSettings.automixDjLevel.value,
    ): PreparedPair? {
        with(host) {
            if (sampleRate <= 0 || generation <= 0L) return null
            return pairPreparer?.frozen(outItem.mediaId, inItem.mediaId, generation, level)
                ?.takeIf { it.sampleRate == sampleRate }
        }
    }

    fun dropForQueueEdit(why: String) {
        with(host) {
            Log.d(TAG, "playlist changed: $why")
            // djmix10: an audible mix keeps its plan (plan vars are only
            // cleared pre-audible); the blend finishes and hands off.
            val audible = isFading() || isLoopArming() || isLoopRunning() ||
                (isArming() && appSettingsMarkerShown())
            if (audible) {
                AutomixTelemetry.event("mix.freeze.queueEdit", mapOf("why" to why, "phase" to phaseName))
                return
            }
            if (isIdle() && opportunity != null && !terminalRecorded) {
                mixEvent("mix.plan.rejected", mapOf("stage" to "terminal", "outcome" to "cancelled",
                    "terminal" to true, "refusalCode" to "queue-edit", "reason" to why))
                terminalRecorded = true
                opportunity = null
            }
            planGeneration++
            analysisReplanJob?.cancel()
            analysisReplanJob = null
            lastSmartPlan = null
            discardPrime()
            loopPlanned = null
            loopScript = null
            loopPlanKey = null
            plannedNextId = null
            if (!isIdle()) bail()
        }
    }

    fun ranOutBeforeHandoff() {
        with(host) {
            if (handedOff) return
            when {
                isArming() -> cancelArming("outgoing-ran-on-while-arming")
                isLoopArming() || isLoopRunning() -> bail()
                isFading() -> {
                    val out = outgoing ?: return bail()
                    val into = incoming ?: return bail()
                    Log.d(TAG, "outgoing ran on into the next track before the handoff")
                    out.volume = 0f
                    handOff(out, into)
                }
                else -> Unit
            }
        }
    }

    /**
     * Keeps [listener] on whichever player is the session.
     *
     * It has to move rather than sit on both: arming loads a whole queue onto
     * the standby, which Media3 reports as the playlist changing, and a listener
     * attached there would read that as the queue being replaced out from under
     * the very transition it is setting up.
     */
    fun listenTo(target: ExoPlayer) {
        with(host) {
            if (listeningTo === target) return
            listeningTo?.removeListener(listener)
            target.addListener(listener)
            listeningTo = target
        }
    }

    fun onTrackChanged() {
        with(host) {
            loopSuppressedPair = null
            loopSuppressedUntil = 0L
            countdownStabilizer.reset()
            hardWatchdogTriggeredPair = null
            hardWatchdogCooldownUntil = 0L
            this@PrepCoordinator.notifyPairPreparer()
        }
    }

    fun triggerRemixPrep() {
        with(host) {
            this@PrepCoordinator.notifyPairPreparer(force = true)
        }
    }

    /**
     * Remixes the coming transition: same pair, same level, a different allowed recipe.
     *
     * Only before anything is armed or sounding (a running renderer is never mutated), and only
     * with enough lead to re-decode and rebuild. Returns false without touching the existing
     * preparation when no other recipe fits, so the Inspector never shows a remix that did not
     * happen.
     */
    fun remixCurrentTransition(): Boolean {
        with(host) {
            val preparer = pairPreparer ?: return false
            if (isArming() || isFading() || isLoopArming() ||
                isLoopRunning() || isTail() || isBailing()
            ) return false
            val player = runCatching { active() }.getOrNull() ?: return false
            val mState = mixExecutor.currentState()
            val mPlan = mState.plan
            if (mPlan != null && (mState is MixState.Preparing || mState is MixState.Ready)) {
                val remainingMs = mPlan.frozenExitMs - player.currentPosition
                if (remainingMs < MixMachineConfig.DEFAULT.remixMinLeadMs) return false
                val (nextState, _) = mixExecutor.dispatch(MixEvent.RemixRequested)
                if (nextState.generation <= mState.generation) return false
                AutomixTelemetry.event(
                    "mix.remix",
                    mapOf(
                        "outId" to mPlan.outTrackId,
                        "inId" to mPlan.inTrackId,
                        "level" to mState.djLevel.name,
                        "excludedRecipe" to mPlan.recipeId,
                        "remainingMs" to remainingMs,
                        "phase" to phaseName,
                    ),
                )
                discardPrime()
                return true
            }
            val request = this@PrepCoordinator.prepRequestFor(player) ?: return false
            val currentSnap = preparer.snapshotFor(
                request.outTrack.id, request.inTrack.id, request.djLevel,
            ) ?: return false
            val startMs = currentSnap.plan?.out?.loopStart?.let { (it * 1000.0).roundToLong() }
                ?: (currentSnap.plannedWindowStartSec * 1000.0).roundToLong().takeIf { it > 0L }
                ?: return false
            val remainingMs = startMs - player.currentPosition
            if (remainingMs < maxOf(blendArmLeadMs, LOOP_PRIME_RETRY_MS + 2_500L)) return false
            val excluded = com.music.bitchord.playback.smart.choreo.RecipeId.fromCode(currentSnap.script?.recipeId)
                ?: currentSnap.v2Prep?.forcedRecipe
            if (!preparer.onRemixRequested(request, excluded)) return false
            AutomixTelemetry.event(
                "mix.remix",
                mapOf(
                    "outId" to request.outTrack.id,
                    "inId" to request.inTrack.id,
                    "level" to request.djLevel.name,
                    "excludedRecipe" to excluded?.name,
                    "remainingMs" to remainingMs,
                    "phase" to phaseName,
                ),
            )
            discardPrime()
            return true
        }
    }

    /** The one request shape both ordinary preparation and a remix use for the shown pair. */
    fun prepRequestFor(
        player: ExoPlayer,
        djLevelOverride: com.music.bitchord.playback.smart.choreo.DjLevel = AppSettings.automixDjLevel.value,
    ): com.music.bitchord.playback.djmix.PrepRequest? {
        with(host) {
            if (!AppSettings.smartFadeEnabled.value || ListenTogether.state.value.inParty ||
                BitPerfectOutput.status.value.active || versionSwapActive()
            ) return null
            if (player.repeatMode == Player.REPEAT_MODE_ONE) return null
            val currentItem = player.currentMediaItem ?: return null
            val nextIndex = player.nextMediaItemIndex
            if (nextIndex == C.INDEX_UNSET || nextIndex >= player.mediaItemCount) return null
            val nextItem = player.getMediaItemAt(nextIndex)
            if (currentItem.isVideoOrigin || nextItem.isVideoOrigin) return null
            val outDurMs = player.duration.takeIf { it != C.TIME_UNSET && it > 0L }
                ?: queuedDurationMs(currentItem).takeIf { it > 0L }
                ?: (analysisFor(currentItem).duration * 1000.0).roundToLong().coerceAtLeast(0L)
            val inDurMs = nextItemDurationMs(nextIndex, nextItem).coerceAtLeast(0L)
            val sampleRate = loopPortOf(player)?.sampleRate?.takeIf { it > 0 } ?: 48_000
            val albumOrder = runCatching {
                TransitionMoves.isConsecutiveAlbumPair(
                    currentItem.mediaMetadata.albumTitle?.toString().orEmpty(), "",
                    currentItem.mediaMetadata.trackNumber ?: 0,
                    nextItem.mediaMetadata.albumTitle?.toString().orEmpty(), "",
                    nextItem.mediaMetadata.trackNumber ?: 0,
                )
            }.getOrDefault(false)
            val fallbackSeconds = configuredFadeMs().takeIf { it > 0L }?.div(1000.0)
                ?: DEFAULT_SMART_FALLBACK_SECONDS
            return com.music.bitchord.playback.djmix.PrepRequest(
                outTrack = com.music.bitchord.playback.djmix.PrepTrack(
                    id = currentItem.mediaId,
                    durationSec = outDurMs / 1000.0,
                    title = currentItem.mediaMetadata.title?.toString().orEmpty(),
                    artist = currentItem.mediaMetadata.artist?.toString().orEmpty(),
                    albumTitle = currentItem.mediaMetadata.albumTitle?.toString().orEmpty(),
                    trackNumber = currentItem.mediaMetadata.trackNumber ?: 0,
                    mediaItem = currentItem,
                    uri = currentItem.localConfiguration?.uri,
                ),
                inTrack = com.music.bitchord.playback.djmix.PrepTrack(
                    id = nextItem.mediaId,
                    durationSec = inDurMs / 1000.0,
                    title = nextItem.mediaMetadata.title?.toString().orEmpty(),
                    artist = nextItem.mediaMetadata.artist?.toString().orEmpty(),
                    albumTitle = nextItem.mediaMetadata.albumTitle?.toString().orEmpty(),
                    trackNumber = nextItem.mediaMetadata.trackNumber ?: 0,
                    mediaItem = nextItem,
                    uri = nextItem.localConfiguration?.uri,
                ),
                djLevel = djLevelOverride,
                positionSec = player.currentPosition.coerceAtLeast(0L) / 1000.0,
                sampleRate = sampleRate,
                albumOrder = albumOrder,
                preferStraight = false,
                fallbackSeconds = fallbackSeconds,
                lyricsOut = lyricSpans[currentItem.mediaId]?.cues.orEmpty().toList(),
                lyricsIn = lyricSpans[nextItem.mediaId]?.cues.orEmpty().toList(),
            )
        }
    }

    fun notifyPairPreparer(force: Boolean = false, fromSeek: Boolean = false) {
        with(host) {
            val preparer = pairPreparer ?: return
            val player = runCatching { active() }.getOrNull() ?: return
            val baseReq = this@PrepCoordinator.prepRequestFor(player) ?: return
            val mState = mixExecutor.currentState()
            val matchingPair = mState.pair?.outTrackId == baseReq.outTrack.id &&
                mState.pair?.inTrackId == baseReq.inTrack.id
            val req = if (matchingPair && activeMachineGeneration > 0L) {
                baseReq.copy(machineGeneration = activeMachineGeneration)
            } else baseReq
            when {
                force -> {
                    if (mState is MixState.Preparing || mState is MixState.Ready) {
                        mixExecutor.dispatch(MixEvent.RemixRequested)
                    } else {
                        preparer.onRemixRequested(req)
                    }
                }
                fromSeek -> preparer.onSeek(req)
                else -> if (!matchingPair || mState is MixState.Idle || mState is MixState.Failed) {
                    preparer.onTrackStart(req)
                }
            }
        }
    }
}
