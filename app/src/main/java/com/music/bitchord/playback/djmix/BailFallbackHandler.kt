package com.music.bitchord.playback.djmix

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.machine.MixStateKind
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.shouldDeclineToMix
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.FilterTransitionVariant
import com.music.bitchord.playback.smart.DeckBudget
import com.music.bitchord.playback.smart.RetireGuard
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.TransitionStyle
import com.music.bitchord.playback.smart.BUILDUP_RISE
import com.music.bitchord.playback.smart.deckPlayedRate
import com.music.bitchord.playback.smart.deckSpeed
import kotlin.math.roundToLong
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.MixDebug
import com.music.bitchord.playback.TransitionFilters


/** State observed or updated by [BailFallbackHandler]; all access stays on the controller thread. */
@OptIn(UnstableApi::class)
internal interface BailFallbackHandlerHost : CoordinatorCallbacks {
    val active: () -> ExoPlayer
    val analysisFor: (MediaItem) -> TrackAnalysis
    var armOutgoingPreFiltered: Boolean
    var bailFromGain: Float
    var bailStartedAt: Long
    var blendArmLeadMs: Long
    var buildGeneration: Int
    var capturedLoopTookOver: Boolean?
    var capturedTakeoverSpliceBufferStatus: LoopMixPort.TakeoverSpliceBufferStatus?
    var classicArmLeadMs: Long
    var classicFallbackReason: String
    var classicPrepStartMs: Long
    val countdownStabilizer: CountdownStabilizer
    val deckTiming: DeckTiming
    var executedRoute: Map<String, Any?>?
    var fadeMs: Long
    var fadingStallSinceMs: Long
    val filters: TransitionFilters
    var handedOff: Boolean
    var handoverExpectedMs: Long
    var handoverHeardMs: Long
    var handoverTargetMs: Long
    var hardWatchdogCooldownUntil: Long
    var hardWatchdogTriggeredPair: String?
    var idleStallPrepared: Boolean
    var idleStallSinceMs: Long
    var incoming: ExoPlayer?
    var incomingAnalysis: TrackAnalysis
    var incomingCueTimeMs: Long
    var incomingPlaybackRate: Double
    var initiatingExitReason: String?
    var lastCompleteReason: String?
    var lastFallbackLogKey: String?
    var lastFireFallbackReason: String
    var lastFireFxCount: Int
    var lastFireMove: String
    var lastFirePath: String
    var lastFireStems: Int
    var lastFreezeLogKey: String?
    var lastHandoffCompleteAtMs: Long
    var lastPairAnalysisSignature: String?
    var lastSmartPlan: TransitionPlan?
    var lastSmartPlanStartMs: Long
    var lastTimingPair: Pair<String, String>?
    var localBlendInFlight: Boolean
    var loopBuildStartedAtMs: Long
    var loopPlanKey: String?
    var loopPlanned: LoopMix.Plan?
    val loopPortOf: (ExoPlayer) -> LoopMixPort?
    var loopScript: PhraseScript?
    var loopSession: LoopMixSession?
    var loopSuppressedPair: String?
    var loopSuppressedUntil: Long
    var loopTarget: LoopMix.Plan?
    var loopTargetStartMs: Long
    val mixExecutor: MixExecutor
    var nativeBinding: NativeBinding?
    val nextStreamReady: (MediaItem) -> Boolean
    val onBlendHeadroom: (Float) -> Unit
    val onSettled: () -> Unit
    var outgoing: ExoPlayer?
    var outgoingAnalysis: TrackAnalysis
    var outgoingItemIndex: Int
    var outgoingPlaybackRate: Double
    val pairPreparer: com.music.bitchord.playback.djmix.PairPreparer?
    val phaseName: String
    var planGeneratedAtMs: Long
    var plannedNextId: String?
    var plannedTransition: Pair<String, Long>?
    var queuedItemCount: Int
    var render: RideSpec
    var routingFallback: Boolean
    var scrubEpoch: Long
    var scrubSettledAtMs: Long
    var settledAt: Long
    var smartFadeActive: Boolean
    val standby: () -> ExoPlayer
    var survivorRecoveryPending: Boolean
    var tailDurationMs: Long
    var tailLoopSession: LoopMixSession?
    var tailTarget: ExoPlayer?
    val tempoEngine: EaseTempoEngine
    var terminalRecorded: Boolean
    val tracks: TransitionFilters
}

/** Coordinates fallback execution, interruption ramps, and terminal cleanup on the controller thread. */
@MainThread
@OptIn(UnstableApi::class)
internal class BailFallbackHandler(private val host: BailFallbackHandlerHost) {
    fun classicFallbackOnRendererUnavailable(
        player: ExoPlayer,
        currentItem: MediaItem,
        nextItem: MediaItem,
        duration: Long,
        nextDuration: Long,
        reason: String,
        markerOnly: Boolean,
        failedPlan: TransitionPlanV1?,
    ) {
        with(host) {
            val posMs = player.currentPosition.coerceAtLeast(0L)
            val reasonKey = "${currentItem.mediaId}->${nextItem.mediaId}|$scrubEpoch|$reason"
            if (reasonKey != lastFallbackLogKey) {
                lastFallbackLogKey = reasonKey
                AutomixTelemetry.event(
                    "mix.classic.reason",
                    mapOf(
                        "reason" to reason,
                        "outId" to currentItem.mediaId,
                        "inId" to nextItem.mediaId,
                        "stage" to "consider-smart",
                        "rendererAvailable" to LoopMixRenderer.available,
                    ),
                )
            }
            val pairKey = "${currentItem.mediaId}->${nextItem.mediaId}"
            val currentAnalysis = analysisFor(currentItem)
            val nextAnalysis = analysisFor(nextItem)
            val analysisState = AppSettings.smartAnalysis.value
            val recoveryRequired = countdownStabilizer.hasFailedCommittedNative(pairKey, scrubEpoch)
            val bothAnalyzed = currentAnalysis.isUsable && nextAnalysis.isUsable
            val coarseDecline = bothAnalyzed && shouldDeclineToMix(currentAnalysis, nextAnalysis)

            if (failedPlan == null && !recoveryRequired && (!bothAnalyzed || coarseDecline)) {
                val naturalPlan = TransitionPlan(
                    shouldStart = false,
                    markerVisible = false,
                    blocked = true,
                    reason = if (!bothAnalyzed) "classic:analysis-pending" else "classic:declined",
                    transitionStart = duration / 1000.0,
                    transitionEnd = duration / 1000.0,
                    fadeSeconds = 0.0,
                    transitionStyle = TransitionStyle.NATURAL,
                    beatSeconds = 0.0,
                    mixSummary = "Natural handoff ($reason)",
                )
                lastSmartPlan = naturalPlan
                lastSmartPlanStartMs = 0L
                planGeneratedAtMs = nowMs()
                plannedTransition = null
                AppSettings.smartTransitionWindow.value = null
                acceptRoute(naturalPlan, classicRouteFields(naturalPlan) + ("technique" to "natural"))
                publishMixPreview(currentItem, nextItem, duration, nextDuration, currentAnalysis, nextAnalysis, analysisState, naturalPlan, true)
                return
            }

            val fallbackSec = configuredFadeMs().takeIf { it > 0L }?.div(1000.0) ?: DEFAULT_SMART_FALLBACK_SECONDS
            val outEndMs = failedPlan?.let { (it.frozenExitMs + it.overlapDurationMs).coerceIn(1_500L, duration) }
                ?: (currentAnalysis.contentEndTime.takeIf { it.isFinite() && it > 1.0 }?.times(1000.0))
                    ?.roundToLong()?.coerceIn(1_500L, duration)
                ?: duration
            val rawFadeMs = failedPlan?.overlapDurationMs?.takeIf { it >= 1_200L }
                ?: (fallbackSec * 1000.0).roundToLong()
            val maxFadeForMode = if (failedPlan == null && recoveryRequired && coarseDecline) 4_000L else rawFadeMs
            val fadeMs = minOf(rawFadeMs, maxFadeForMode, (outEndMs - 500L).coerceAtLeast(1_200L)).coerceAtLeast(1_200L)
            val startMs = (outEndMs - fadeMs).coerceAtLeast(0L)
            val beatSec = currentAnalysis.beatInterval.takeIf { it.isFinite() && it in 0.25..1.5 }
                ?: currentAnalysis.bpm.takeIf { it.isFinite() && it > 40.0 }?.let { 60.0 / it }
                ?: 0.5
            val inCueMs = failedPlan?.frozenEntryMs
                ?: (nextAnalysis.mixInTime.takeIf { it.isFinite() && it >= 0.0 }?.times(1000.0))?.roundToLong()
                ?: 0L
            val classicPlan = if (failedPlan == null && recoveryRequired) {
                TransitionPlan(
                    shouldStart = true,
                    markerVisible = true,
                    blocked = false,
                    reason = if (coarseDecline) "classic:decline-recovery:$reason" else "classic:native-recovery:$reason",
                    transitionStart = startMs / 1000.0,
                    transitionEnd = outEndMs / 1000.0,
                    fadeSeconds = fadeMs / 1000.0,
                    transitionStyle = TransitionStyle.DJ_FILTER,
                    incomingCueTime = inCueMs / 1000.0,
                    incomingPlaybackRate = 1.0,
                    outgoingPlaybackRate = 1.0,
                    bassSwap = !coarseDecline,
                    filterSweep = 1.0,
                    filterVariant = if (coarseDecline) FilterTransitionVariant.DECLINE else FilterTransitionVariant.SWEEP,
                    beatSeconds = beatSec,
                    phaseLock = false,
                    echoSeconds = 0.0,
                    djEffect = com.music.bitchord.playback.smart.DjEffect.NONE,
                    mixSummary = if (coarseDecline) "Filter decline ($reason)" else "Recovery crossfade ($reason)",
                )
            } else {
                val classicStyle = if (failedPlan == null && bothAnalyzed && !coarseDecline) {
                    TransitionStyle.DJ_BLEND
                } else {
                    TransitionStyle.EQUAL_POWER
                }
                TransitionPlan(
                    shouldStart = true,
                    markerVisible = true,
                    blocked = false,
                    reason = "classic:$reason",
                    transitionStart = startMs / 1000.0,
                    transitionEnd = outEndMs / 1000.0,
                    fadeSeconds = fadeMs / 1000.0,
                    transitionStyle = classicStyle,
                    incomingCueTime = inCueMs / 1000.0,
                    incomingPlaybackRate = 1.0,
                    outgoingPlaybackRate = 1.0,
                    bassSwap = classicStyle == TransitionStyle.DJ_BLEND,
                    beatSeconds = beatSec,
                    phaseLock = false,
                    mixSummary = "Classic crossfade ($reason)",
                )
            }
            lastSmartPlan = classicPlan
            lastSmartPlanStartMs = startMs
            planGeneratedAtMs = nowMs()
            acceptRoute(classicPlan, classicRouteFields(classicPlan))
            publishMixPreview(currentItem, nextItem, duration, nextDuration, currentAnalysis, nextAnalysis, analysisState, classicPlan, true)

            val readyToMark = (failedPlan != null ||
                (analysisState.current == TrackAnalysisState.ANALYSED && analysisState.next in MEASURED_ENOUGH_TO_ENTER_ON)) &&
                (nowMs() >= scrubSettledAtMs || duration - posMs <= 5_000L)
            AppSettings.smartTransitionWindow.value = if (readyToMark && startMs < duration) {
                TransitionWindow(
                    start = (startMs.toDouble() / duration).toFloat().coerceIn(0f, 1f),
                    end = (outEndMs.toDouble() / duration).toFloat().coerceIn(0f, 1f),
                )
            } else null
            if (markerOnly) return
            plannedTransition = currentItem.mediaId to startMs
            holdAhead(player, duration)
            val effectiveLeadMs = maxOf(blendArmLeadMs, if (!nextStreamReady(nextItem)) 25_000L else 0L)
            if (startMs - posMs > effectiveLeadMs) return
            begin(
                fade = fadeMs,
                endMs = outEndMs,
                smart = true,
                cueTimeMs = inCueMs,
                playbackRate = 1.0,
                outgoingRate = 1.0,
                beatMs = (beatSec * 1000.0).roundToLong(),
                renderStyle = renderFor(classicPlan, true),
                plan = classicPlan,
            )
        }
    }

    fun twoBarsMsFor(bpm: Double, beatSecondsFallback: Double): Long {
        with(host) {
            val barSec = when {
                bpm.isFinite() && bpm > 1.0 -> 240.0 / bpm
                beatSecondsFallback.isFinite() && beatSecondsFallback > 0.05 -> beatSecondsFallback * 4.0
                else -> 0.0
            }
            return if (barSec > 0.0) (2.0 * barSec * 1000.0).roundToLong() else 0L
        }
    }

    fun classicActiveFxNames(r: RideSpec): List<String> = with(host) {
        buildList {
            if (r.style == TransitionStyle.DJ_FILTER || r.filterSweep > 0.0) add("Filter")
            if (r.bassSwap) add("BassSwap")
            if (r.echo) add("Echo")
            if (r.djEffect != com.music.bitchord.playback.smart.DjEffect.NONE) {
                val name = when (r.djEffect) {
                    com.music.bitchord.playback.smart.DjEffect.ECHO -> "Echo"
                    com.music.bitchord.playback.smart.DjEffect.REVERB -> "Reverb"
                    com.music.bitchord.playback.smart.DjEffect.FLANGER -> "Flanger"
                    com.music.bitchord.playback.smart.DjEffect.LOOP_ROLL -> "LoopRoll"
                    com.music.bitchord.playback.smart.DjEffect.NONE -> ""
                }
                if (name.isNotEmpty() && name !in this) add(name)
            }
            if (r.vocalHandoff) add("VocalScoop")
        }
    }

    /**
     * Resets any stalled build if requested, emits `mix.watchdog` + updates [PairPreparer],
     * and fires the emergency transition so Automix never falls off EOF dry.
     */
    fun emitWatchdogAndTriggerEmergency(
        player: ExoPlayer,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        reason: String,
        plannedStartMs: Long? = null,
        elapsedMs: Long? = null,
        resetFromLoopArming: Boolean = false,
    ): Boolean {
        with(host) {
            val fromPhase = phaseName
            if (resetFromLoopArming) {
                discardPrime()
                loopPlanned = null
                loopScript = null
                loopPlanKey = null
                loopTarget = null
                loopTargetStartMs = 0L
                outgoing = null
                incoming = null
                releaseHold()
                onIdle()
            }
            val pairKey = "${outItem.mediaId}->${inItem.mediaId}"
            val pos = player.currentPosition
            val remainingMs = (durationMs - pos).coerceAtLeast(0L)
            hardWatchdogTriggeredPair = pairKey
            hardWatchdogCooldownUntil = nowMs() + 5_000L
            lastFireFallbackReason = reason
            val outPort = loopPortOf(player)
            val inPort = loopPortOf(standby())
            val level = AppSettings.automixDjLevel.value
            val snap = pairPreparer?.snapshotFor(outItem.mediaId, inItem.mediaId, level)
            val snapPlan = snap?.plan
            val canTryNativeEmergency = reason != "stuck-building-8s" &&
                isNativeRendererAvailable(player) &&
                outPort != null &&
                inPort != null &&
                remainingMs >= 2_500L &&
                snap != null &&
                snapPlan != null &&
                snap.outRegion != null &&
                snap.inRegion != null &&
                snapPlan.out.loopStart * 1000.0 >= pos + 400L
            val attemptedPath = if (canTryNativeEmergency) "v2-native" else "classic"
            pairPreparer?.recordWatchdog(outItem.mediaId, inItem.mediaId, reason, path = attemptedPath)
            val fields = linkedMapOf<String, Any?>(
                "reason" to reason,
                "key" to pairKey,
                "outId" to outItem.mediaId,
                "inId" to inItem.mediaId,
                "pos" to pos,
                "remainingMs" to remainingMs,
                "plannedStartMs" to plannedStartMs,
                "path" to attemptedPath,
                "level" to AppSettings.automixDjLevel.value.name,
                "phase" to fromPhase,
            )
            if (elapsedMs != null) fields["elapsedMs"] = elapsedMs
            AutomixTelemetry.event("mix.watchdog", fields)
            return this@BailFallbackHandler.triggerEmergencyMix(player, outItem, inItem, durationMs, reason)
        }
    }

    fun triggerEmergencyMix(
        player: ExoPlayer,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        reason: String,
    ): Boolean {
        with(host) {
            val remMs = (durationMs - player.currentPosition).coerceAtLeast(1_200L)
            val outPort = loopPortOf(player)
            val into = standby()
            val inPort = loopPortOf(into)
            val level = AppSettings.automixDjLevel.value
            val snap = pairPreparer?.snapshotFor(outItem.mediaId, inItem.mediaId, level)
            val snapPlan = snap?.plan
            val canTryNativeEmergency = reason != "stuck-building-8s" &&
                isNativeRendererAvailable(player) &&
                outPort != null &&
                inPort != null &&
                remMs >= 2_500L &&
                snap != null &&
                snapPlan != null &&
                snap.outRegion != null &&
                snap.inRegion != null &&
                snapPlan.out.loopStart * 1000.0 >= player.currentPosition + 400L

            if (canTryNativeEmergency) {
                val nextIdx = player.nextMediaItemIndex
                val inDurationMs = if (nextIdx != C.INDEX_UNSET) nextItemDurationMs(nextIdx, inItem) else 0L
                val key = primeKey(snapPlan!!, outItem, inItem, outPort!!.sampleRate)
                tempoEngine.endEase(clickless = false)
                MixDebug.reset()
                onLoopArming()
                smartFadeActive = true
                handedOff = false
                outgoing = player
                incoming = into
                outgoingItemIndex = player.currentMediaItemIndex
                loopTarget = snapPlan
                loopTargetStartMs = (snapPlan.out.loopStart * 1000.0).roundToLong()
                holdAtEnd(player)
                deckTiming.clear()
                deckTiming.deadlineMs = DeckBudget.deadlineMs(nowMs(), player.isPlaying, loopTargetStartMs, player.currentPosition)
                loopBuildStartedAtMs = nowMs()
                lastFireFallbackReason = reason
                pairPreparer?.recordWatchdog(outItem.mediaId, inItem.mediaId, reason, path = "v2-native")
                startLoopBuild(
                    key, snapPlan, player, into, outItem, inItem, durationMs, inDurationMs, outPort.sampleRate,
                    armWhenReady = true, preparedOverride = snap, forceNoStems = true,
                )
                return true
            }
            val mState = mixExecutor.currentState()
            if (mState.pair != null && mState.stateKind !in setOf(
                    MixStateKind.IDLE, MixStateKind.DONE, MixStateKind.FAILED, MixStateKind.SUSPENDED,
                )
            ) {
                mixExecutor.dispatch(
                    MixEvent.RendererError(FailureKind.RENDERER_PREPARE_ERROR, reason, recoverable = false),
                )
            }
            return this@BailFallbackHandler.emergencyClassicFadeWithFx(player, outItem, inItem, durationMs, reason)
        }
    }

    fun emergencyClassicFadeWithFx(
        player: ExoPlayer,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        reason: String,
    ): Boolean {
        with(host) {
            classicFallbackReason = reason
            AutomixTelemetry.event(
                "mix.classic.reason",
                mapOf(
                    "reason" to reason,
                    "outId" to outItem.mediaId,
                    "inId" to inItem.mediaId,
                    "stage" to "watchdog-emergency",
                ),
            )
            val level = AppSettings.automixDjLevel.value
            val outAnalysis = analysisFor(outItem)
            val inAnalysis = analysisFor(inItem)
            val remMs = (durationMs - player.currentPosition).coerceAtLeast(1_200L)
            val targetFadeMs = when (level) {
                com.music.bitchord.playback.smart.choreo.DjLevel.SUBTLE -> 5_000L
                com.music.bitchord.playback.smart.choreo.DjLevel.CLUB -> 6_500L
                com.music.bitchord.playback.smart.choreo.DjLevel.FESTIVAL -> 8_000L
            }
            val fadeMsEmergency = minOf(remMs, targetFadeMs).coerceAtLeast(1_200L)
            val fadeSec = fadeMsEmergency / 1000.0
            val rawBeatSec = outAnalysis.beatInterval.takeIf { it.isFinite() && it in 0.25..1.25 }
                ?: outAnalysis.bpm.takeIf { it.isFinite() && it > 40.0 }?.let { 60.0 / it }
                ?: 0.5
            val effectiveBeatSec = minOf(rawBeatSec, fadeSec / 4.0).coerceAtLeast(0.25)
            val echoSec = (effectiveBeatSec * 2.0).coerceAtLeast(1.0)
            val inCueSec = inAnalysis.mixInTime.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
            val startSec = player.currentPosition.coerceAtLeast(0L) / 1000.0
            val endSec = durationMs / 1000.0
            val emergencyPlan = TransitionPlan(
                shouldStart = true,
                markerVisible = false,
                blocked = false,
                reason = reason,
                transitionStart = startSec,
                transitionEnd = endSec,
                fadeSeconds = fadeSec,
                transitionStyle = TransitionStyle.DJ_FILTER,
                incomingCueTime = inCueSec,
                incomingPlaybackRate = 1.0,
                outgoingPlaybackRate = 1.0,
                bassSwap = true,
                bassSwapFraction = 0.5,
                filterSweep = 1.0,
                filterVariant = FilterTransitionVariant.ECHO_RIDE,
                vocalOverlap = 0.5,
                beatSeconds = effectiveBeatSec,
                phaseLock = false,
                echoSeconds = echoSec,
                djEffect = com.music.bitchord.playback.smart.DjEffect.ECHO,
                mixSummary = "Emergency Echo Sweep",
            )
            lastFireFallbackReason = reason
            lastSmartPlan = emergencyPlan
            lastSmartPlanStartMs = player.currentPosition
            pairPreparer?.recordWatchdog(outItem.mediaId, inItem.mediaId, reason, path = "classic")
            val renderStyle = RideSpec(
                style = TransitionStyle.DJ_FILTER,
                bassSwap = true,
                bassSwapFraction = 0.5,
                filterSweep = 1.0,
                filterVariant = FilterTransitionVariant.ECHO_RIDE,
                vocalOverlap = 0.5,
                advanced = true,
                beats = (fadeSec / effectiveBeatSec).coerceAtLeast(4.0),
                lockPhase = false,
                echoSeconds = echoSec,
                djEffect = com.music.bitchord.playback.smart.DjEffect.ECHO,
                vocalHandoff = false,
                beatSecondsF = effectiveBeatSec.toFloat(),
            )
            val began = begin(
                fade = fadeMsEmergency,
                endMs = durationMs,
                smart = true,
                cueTimeMs = (inCueSec * 1000.0).roundToLong(),
                playbackRate = 1.0,
                outgoingRate = 1.0,
                beatMs = (effectiveBeatSec * 1000.0).roundToLong(),
                renderStyle = renderStyle,
                plan = emergencyPlan,
            )
            if (began && standby().playbackState == Player.STATE_READY) {
                driveArming()
            }
            return began
        }
    }

    fun bail(force: Boolean = false) {
        with(host) {
            if (isIdle() || isBailing()) return
            // djmix10 freeze-once-armed: once the blend is audible (FADING,
            // or a live loop session), a load/queue/seek/error must not
            // destroy it. The mix is already paid for (decks decoded,
            // gains ramped); finishing it is always more graceful than
            // aborting it. Pre-arm phases may still bail (costs only the
            // standby decoder).
            // ARMING (marker shown or not) is now frozen once armed (djmix12 P1-4).
            if (!force && (isFading() || isTail() || isLoopArming() || isLoopRunning() ||
                isArming())) {
                val freezeKey = freezeDedupKey(
                    phaseName,
                    outgoing?.currentMediaItem?.mediaId,
                    incoming?.currentMediaItem?.mediaId,
                )
                if (freezeKey != lastFreezeLogKey) {
                    lastFreezeLogKey = freezeKey
                    AutomixTelemetry.event("mix.freeze.onInterruption", mapOf("phase" to phaseName, "reason" to "interrupted-while-armed"))
                }
                return
            }
            cancelAutoMixVisual()
            if (isLoopArming() || isLoopRunning()) {
                loopBail("interrupted", settle = false, preserveSeek = false, deferDisarm = false, force = force)
                return
            }
            Log.d(TAG, "bail from $phaseName")
            AppSettings.smartMixInProgress.value = false
            AppSettings.smartMixBlend.value = null
            clearMixView()
            if (!handedOff && !isFading()) {
                if (armOutgoingPreFiltered) {
                    armOutgoingPreFiltered = false
                    survivorRecoveryPending = true
                }
                // Nothing was ever audible; no ramp to run.
                this@BailFallbackHandler.finish()
                return
            }
            // Deferred open (Seam A invariant): both tracks are audible here.
            // Reopening a parked filter now would glide open in ~30 ms under
            // full gain (the measured step). Leave the cutoff parked through
            // the BAILING dissolve; finish() opens it once gains reach zero.
            openFiltersWhenSilent()
            // Whichever track is *not* the session's is the one ramped away: the
            // outgoing tail after the handoff, the incoming track before it.
            if (handedOff) incoming?.volume = 1f else outgoing?.volume = 1f
            // Forced exit from an audible blend before its ride completed:
            // the survivor would strand parked if finish() only opened the spare.
            survivorRecoveryPending = true
            bailFromGain = this@BailFallbackHandler.leavingOnBail()?.volume ?: 0f
            bailStartedAt = nowMs()
            onBailing()
        }
    }

    fun leavingOnBail(): ExoPlayer? = with(host) {
        if (handedOff) outgoing else incoming
    }

    fun finish() {
        with(host) {
            (loopSession ?: tailLoopSession)?.let(::captureLoopExitObservation)
            // djmix14: capture the real completion values BEFORE clearing.
            // djmix15: loop mixes store BPMs in loopSession.plan, not
            // outgoingAnalysis/incomingAnalysis (which stay blank 0.0). Fall
            // back to the plan so loop completions never report 0.0. loopSession
            // is deliberately kept non-null through finish() by finishLoop() and
            // loopBail() for exactly this capture.
            val endPhase = phaseName
            val completionContext = opportunity
            val timingPair = lastTimingPair ?: completionContext?.let { it.outId to it.inId }
            val completionRoute = executedRoute ?: mapOf("technique" to "unknown")
            val completionBinding = nativeBinding.takeUnless { completionRoute["technique"] == "classic" }
            val completionRevision = completionBinding?.revision ?: planRevision
            val completionGeneration = completionBinding?.generation
            val didHandoff = handedOff
            val loopOutPort = loopSession?.outPort
            val didTakeOver = loopOutPort?.tookOver ?: capturedLoopTookOver
            val takeoverSpliceBufferStatus = if (loopOutPort != null) {
                loopOutPort.takeoverSpliceBufferStatus
            } else {
                capturedTakeoverSpliceBufferStatus
            }
            val retiringPlayer = if (didHandoff) outgoing else incoming
            val retiringPlayerPlayWhenReady = retiringPlayer?.playWhenReady
            val retiringPlayerPlaybackState = retiringPlayer?.playbackState
            val loopPlan = loopSession?.plan
            val outBpm = RetireGuard.loopOutBpm(outgoingAnalysis.bpm, loopPlan?.out?.bpm ?: 0.0)
            val inBpm = RetireGuard.loopInBpm(incomingAnalysis.bpm, loopPlan?.inn?.bpm ?: 0.0)
            val tailMs = tailDurationMs.takeIf { it > 0 } ?: (loopPlan?.let { RetireGuard.loopTailMs(it.exitTailSeconds) } ?: tailDurationMs)
            // vc67: clear the tail account so the next mix never inherits it.
            tailDurationMs = 0L
            val completeReason = lastCompleteReason ?: "finish"
            // djmix12 retire guard: an audible spare may not be retired
            // here. Convert to an emergency TAIL (350 ms) first; driveTail
            // quiets it to silence and re-enters finish() with phase==TAIL.
            // djmix16: LOOP_ARMING pre-takeover (!tookOver, !handedOff) is
            // never audible (prepare without play, sink never spliced) even
            // at volume 1.0 — silence it and finish at once (0 ms to IDLE)
            // instead of an 800 ms emergency tail that blocked classic
            // re-arming. Genuine audible (tookOver/handedOff) still tails.
            // djmix22 (vc64): a LOOP mix that has taken over AND handed off
            // completes via finishLoop() — the renderer exit-gate path, which
            // mutes the retiring player while the port still holds its silence
            // and hands the session to the incoming song at the handover
            // position — never via this bare emergency settle, which treated the
            // audible spare at volume 1.0 as a guard violation mid-mix (vc63:
            // mix.fire loop -> finish-guard 1.668 s later -> 350 ms emergency
            // tail -> seek-emergency-tail double teardown). finishLoop() nulls
            // loopSession before calling finish(), so the re-entry below is
            // never re-routed (violations reads -1, tookOver false).
            if (!isIdle() && !isTail() && !isBailing()) {
                val spare = if (handedOff) outgoing else incoming
                val vol = spare?.volume ?: 0f
                val preTakeoverLoopArming = isLoopArming() && !handedOff &&
                    loopSession?.outPort?.tookOver != true &&
                    !RetireGuard.loopArmingSpareAudible(
                        tookOver = loopSession?.outPort?.tookOver == true,
                        handedOff = handedOff,
                        volume = vol,
                        playWhenReady = spare?.playWhenReady ?: false,
                    )
                if (preTakeoverLoopArming) {
                    spare?.volume = 0f
                    spare?.playWhenReady = false
                } else if (RetireGuard.audible(vol)) {
                    val routing = loopFinishAction(
                        phaseName = phaseName,
                        tookOver = loopSession?.outPort?.tookOver == true,
                        handedOff = handedOff,
                        exitGateViolations = loopSession?.renderer?.exitGateViolations() ?: -1,
                        audibleSpare = true,
                    )
                    if (routing == LoopFinishAction.FINISH_LOOP) {
                        finishLoop()
                        return
                    }
                    AutomixTelemetry.event(
                        "mix.exit.guard",
                        opportunityFields(completionContext) + mapOf("planRevision" to completionRevision) +
                            (executedRoute ?: mapOf("technique" to "unknown")) + mapOf(
                                "site" to "finish", "phase" to phaseName, "gainDb" to RetireGuard.gainDb(vol),
                                "initiatingExitReason" to initiatingExitReason,
                                "takeoverSpliceBufferDelegateStatus" to takeoverSpliceBufferStatus?.name,
                                "handedOff" to didHandoff, "tookOver" to didTakeOver,
                                "retiringPlayerPlayWhenReady" to retiringPlayerPlayWhenReady,
                                "retiringPlayerPlaybackState" to retiringPlayerPlaybackState,
                            ),
                    )
                    beginTailOrFinish(spare!!, "emergency-retire-guard", "finish:" + phaseName)
                    return
                }
            }
            if (!isTail()) disarmTailLoopSession()
            clearAutoMixVisual()
            discardPrime()
            val wasTransitioning = !isIdle()
            val isTerminal = wasTransitioning && !routingFallback && !terminalRecorded
            val completionOutcome = CoordinatorDecisions.automixTerminalOutcome(
                isTerminal = isTerminal,
                executed = executedRoute != null,
                handedOff = didHandoff,
                reason = completeReason,
            )
            if (isTerminal) terminalRecorded = true
            val completionFields = completionRoute + mapOf(
                "stage" to if (isTerminal) "terminal" else "fallback",
                "terminal" to isTerminal, "outcome" to completionOutcome,
                "planRevision" to completionRevision, "buildGeneration" to completionGeneration,
                "phase" to endPhase, "handoff" to didHandoff, "bpmA" to outBpm, "bpmB" to inBpm,
                "tailMs" to tailMs, "reason" to completeReason,
                "expectedHandoverMs" to handoverExpectedMs, "actualHeardMs" to handoverHeardMs, "targetMs" to handoverTargetMs,
                "initiatingExitReason" to initiatingExitReason,
                "takeoverSpliceBufferDelegateStatus" to takeoverSpliceBufferStatus?.name,
                "handedOff" to didHandoff, "tookOver" to didTakeOver,
                "retiringPlayerPlayWhenReady" to retiringPlayerPlayWhenReady,
                "retiringPlayerPlaybackState" to retiringPlayerPlaybackState,
            )
            if (!isIdle()) {
                Log.d(TAG, "finish from $phaseName")
                // Stamped under this guard rather than beside the assignment at the
                // bottom, because this function is idempotent and gets called with
                // nothing in flight: marking every one of those as a transition
                // just ended would keep pushing the mark forward and hold a waiting
                // caller off for as long as the calls kept coming.
                settledAt = nowMs()
                // djmix22: arm the 3 s post-handoff pause watch (tick() clears it).
                if (didHandoff) lastHandoffCompleteAtMs = settledAt
            }
            armOutgoingPreFiltered = false
            AppSettings.smartMixInProgress.value = false
            AppSettings.smartMixBlend.value = null
            clearMixView()
            // Before anything else: a transition that never handed off leaves the
            // outgoing player as the session, and it must not pause at the end of
            // its track for a blend that is not coming.
            releaseHold()
            // Deferred open (Seam A invariant): only reopen once both decks are
            // silent. The retiring deck was dissolved to zero by TAIL/BAILING
            // before this point; the session deck is either already full-band
            // at progress 1 (two-phase exit legs) or will be driven from the
            // parked state by the next arm while silent. Opening while audible
            // is the ~30 ms step.
            // vc77: past the guard above the retiring spare has dissolved to
            // <= 0.001f (or was silenced pre-takeover), so silence it
            // unconditionally when handed off; then also open the spare filter
            // (see openSpareFiltersWhenSilent) so a handed-off survivor at 1
            // never strands it at DECLINE/SWEEP park. Colour is released
            // smoothly before echoes are parked.
            if (handedOff) outgoing?.volume = 0f
            openFiltersWhenSilent()
            if (survivorRecoveryPending) {
                survivorRecoveryPending = false
                // Abnormal exit: return the survivor to full-band via the role
                // mapping and leave the retiring spare filter untouched (Seam A:
                // never open both while one is audible). The spare stays parked
                // until its next arm, when begin() reopens it while silent.
                openSurvivorFiltersWhenRetireeSilent()
            } else {
                openSpareFiltersWhenSilent()
            }
            filters.releaseColour()
            filters.releaseEchoes()
            onBlendHeadroom(1f)
            // A loop mix already plays the incoming player at 1× (the renderer did
            // the tempo). [incomingPlaybackRate] belongs to the dual-player path
            // and can still hold the previous blend's rate. Walking the player
            // off that rate, or re-applying the speed it already has, changes
            // AudioTrack playback params under the first notes after the splice.
            val nativeHandover = isLoopRunning() || isLoopArming()
            val releaseFrom = if (render.buildup) {
                deckPlayedRate(incomingPlaybackRate, BUILDUP_RISE)
            } else {
                incomingPlaybackRate
            }
            render = RideSpec()
            tempoEngine.clearPhaseNudge()

            if (handedOff) {
                // The roles have already traded: the incoming player is the session
                // and owns the queue from here, and the outgoing one is spare.
                incoming?.let {
                    it.volume = 1f
                    // djmix18 (Bug 1): NEVER run beginEase()/driveEase() WSOLA speed steps
                    // across B's solo intro after handover. handOff() already reset this player
                    // to the listener speed while the outgoing tail masked the single change;
                    // finish() now only re-asserts that speed (no steps). Stepping 1.62s apart
                    // over the solo intro is what stuttered on vocals/beats. A native mix skips
                    // this for the same reason; classic handoffs get the same guarantee.
                    @Suppress("UNUSED_VARIABLE") val releaseFromUnused = releaseFrom
                    tempoEngine.onHandedOffFinish(it)
                }
                outgoing?.let(this@BailFallbackHandler::retire)
            } else {
                // The transition never became audible, so the session player never
                // moved and the standby is the one to throw away.
                outgoing?.let { out ->
                    out.volume = 1f
                    // Sped up for a blend that never came: walk it back like any stretch.
                    if (outgoingPlaybackRate != 1.0) {
                        tempoEngine.beginEase(out, outgoingPlaybackRate, out.currentMediaItem?.let(analysisFor) ?: TrackAnalysis())
                    }
                }
                incoming?.let(this@BailFallbackHandler::retire)
            }

            outgoing = null
            incoming = null
            // Clear the dissolved-tail reference only after retire and after the
            // Seam A gates above have run. An active tail is preserved until
            // driveTail has silenced it, and finish's early returns (emergency
            // tail / finishLoop) never reach here, so a new tailTarget is never
            // cleared early. Without this, retire's volume=1 reset leaves a stale
            // audible reference that blocks openFiltersWhenSilent/
            // openSpareFiltersWhenSilent and the next begin.
            tailTarget = null
            handedOff = false
            outgoingItemIndex = C.INDEX_UNSET
            queuedItemCount = 0
            plannedNextId = null
            incomingCueTimeMs = 0L
            incomingPlaybackRate = 1.0
            outgoingPlaybackRate = 1.0
            tempoEngine.resetRamp()
            outgoingAnalysis = TrackAnalysis()
            incomingAnalysis = TrackAnalysis()
            localBlendInFlight = false
            lastPairAnalysisSignature = null
            loopBuildStartedAtMs = 0L
            onIdle()
            if (wasTransitioning) {
                mixEvent("mix.complete", completionFields, completionContext)
                if (isTerminal) {
                    opportunity = null
                    initiatingExitReason = null
                }
                capturedTakeoverSpliceBufferStatus = null
                capturedLoopTookOver = null
                classicFallbackReason = ""
                if (!routingFallback) {
                    loopSuppressedPair = null
                    loopSuppressedUntil = 0L
                    countdownStabilizer.reset()
                    hardWatchdogTriggeredPair = null
                    hardWatchdogCooldownUntil = 0L
                    val timingOutId = timingPair?.first.orEmpty()
                    val timingInId = timingPair?.second.orEmpty()
                    if (timingOutId.isNotBlank() && timingInId.isNotBlank()) {
                        pairPreparer?.markDone(timingOutId, timingInId)
                    }
                    val levelName = AppSettings.automixDjLevel.value.name
                    val finalTimings = pairPreparer?.snapshotFor(timingOutId, timingInId)?.timings
                        ?: com.music.bitchord.playback.djmix.PairTimings(
                            outId = timingOutId,
                            inId = timingInId,
                            prepStartMs = classicPrepStartMs,
                            armLeadMs = classicArmLeadMs,
                            watchdog = lastFireFallbackReason.startsWith("watchdog") || lastFireFallbackReason == "stuck-building-8s" || lastFireFallbackReason.startsWith("natural-override"),
                            path = lastFirePath.ifBlank { if (didTakeOver == true) "v2-native" else "classic" },
                            level = levelName,
                            move = lastFireMove,
                            fxCount = lastFireFxCount,
                            stems = lastFireStems,
                            fallbackReason = lastFireFallbackReason,
                        )
                    AutomixTelemetry.event("mix.timing", finalTimings.toTelemetryMap())
                    if (isTerminal && didHandoff) {
                        val mState = mixExecutor.currentState()
                        if (mState is MixState.Armed) {
                            mixExecutor.dispatch(MixEvent.Tick(mState.armAtMs, isPlaying = true))
                        }
                        val afterArm = mixExecutor.currentState()
                        if (afterArm is MixState.Running) {
                            mixExecutor.dispatch(
                                MixEvent.Tick(
                                    positionMs = afterArm.plan.frozenExitMs + afterArm.plan.overlapDurationMs,
                                    isPlaying = true,
                                ),
                            )
                        }
                        if (mixExecutor.currentState() is MixState.HandingOver) {
                            mixExecutor.dispatch(MixEvent.HandoverComplete)
                        }
                    } else if (isTerminal) {
                        val mState = mixExecutor.currentState()
                        if (mState.pair != null && mState.stateKind !in setOf(
                                MixStateKind.IDLE, MixStateKind.DONE, MixStateKind.FAILED, MixStateKind.SUSPENDED,
                            )
                        ) {
                            mixExecutor.dispatch(MixEvent.UserSkip)
                        }
                    }
                    lastTimingPair = null
                    classicPrepStartMs = 0L
                    classicArmLeadMs = 0L
                    lastFirePath = ""
                    lastFireMove = ""
                    lastFireFxCount = 0
                    lastFireStems = 0
                    lastFireFallbackReason = ""
                }
                if (routingFallback) {
                    nativeBinding = null
                    routingFallback = false
                }
                handoverExpectedMs = 0L
                handoverHeardMs = -1L
                handoverTargetMs = 0L
                idleStallSinceMs = 0L
                idleStallPrepared = false
                fadingStallSinceMs = 0L
                lastFreezeLogKey = null
                onSettled()
            }
            lastCompleteReason = null
        }
    }

    /** Still a next track, still playing, still switched on — by whichever setting armed this one. */
    fun stillWorthFading(): Boolean {
        with(host) {
            val stillOn = if (smartFadeActive) AppSettings.smartFadeEnabled.value else configuredFadeMs() > 0L
            return stillOn && (outgoing ?: active()).hasNextMediaItem()
        }
    }

    /**
     * Puts a player back in the drawer: emptied, silent no longer, and on the
     * listener's own playback rate again.
     *
     * The volume matters as much as the emptying. A player left at the gain it
     * faded out on is the next transition's *incoming* player, and it would
     * arrive already turned down — so the reset is part of retiring it, not part
     * of preparing it.
     */
    fun retire(player: ExoPlayer) {
        with(host) {
            // djmix12 invariant: retire() must only ever see silence.
            if (player.volume > 0.001f) {
                AutomixTelemetry.event("mix.exit.violated", mapOf("site" to "retire", "gainDb" to RetireGuard.gainDb(player.volume)))
            }
            player.stop()
            player.clearMediaItems()
            // This is the next transition's incoming player, and from its handoff
            // the session: a hold left on it would pause every track at its end.
            player.pauseAtEndOfMediaItems = false
            player.volume = 1f
            player.setPlaybackSpeed(deckSpeed(AppSettings.playbackSpeed.value, 1.0))
            tracks.incomingTempo(1f)
            tracks.outgoingTempo(1f)
        }
    }
}
