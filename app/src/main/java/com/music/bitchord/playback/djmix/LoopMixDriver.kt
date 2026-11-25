package com.music.bitchord.playback.djmix

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.MixBlend
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.machine.MixStateKind
import com.music.bitchord.playback.AutoMixVisualFeed
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.smart.RetireGuard
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.MixDebug
import com.music.bitchord.playback.smart.SeekAction
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.shouldDeclineToMix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Owns live loop mix clock tracking, tempo alignment, beat publishing,
 * handoff execution, and clean/abnormal session teardown (M9 slice C-5 part 2).
 */
@OptIn(UnstableApi::class)
internal class LoopMixDriver(
    private val host: LoopDriverHost,
    private val state: LoopDriverState,
    private val armDriver: LoopArmDriver,
) {
    companion object {
        private const val TAG = "CrossfadeController"
        const val LOOP_ALIGN_ROUNDS = 5
        const val LOOP_ALIGN_SAMPLES = 15
        const val LOOP_ALIGN_SAMPLE_MS = 30L
        const val LOOP_ALIGN_SETTLE_SECONDS = 1.2
        const val LOOP_END_MARGIN_SECONDS = 0.3
        const val LOOP_WRAP_MS = 400L
        const val LOOP_ANNOUNCE_MS = 2_000L
        const val LOOP_RENDERER_CLOSE_DELAY_MS = 1_500L
        const val LOOP_SUPPRESSION_MS = 90_000L
    }

    /** The mix is running: starts the incoming player, aligns it, hands the session over, and ends. */
    fun driveLoopMix() {
        if (rejectLiveCrown()) return
        val session = state.loopSession ?: return host.finish()
        val out = host.outgoing ?: return loopBail("no outgoing player")
        val into = host.incoming ?: return loopBail("no incoming player")
        val sessionPlayer = if (host.handedOff) into else out
        if (session.outPort?.interrupted == true || (state.loopIncomingStarted && session.inPort?.interrupted == true)) {
            return loopBail("a sink was flushed", settle = false)
        }
        val mStateMix = host.mixExecutor.currentState()
        val syncPausedMix = mStateMix.userPaused ||
            (mStateMix as? MixState.Running)?.focusPaused == true ||
            (mStateMix as? MixState.HandingOver)?.focusPaused == true
        if (syncPausedMix) {
            out.playWhenReady = false
            into.playWhenReady = false
            return
        }
        if (!sessionPlayer.playWhenReady) {
            AutomixTelemetry.event(
                "mix.pause.detected",
                mapOf(
                    "site" to "driveLoopMix",
                    "playWhenReady" to sessionPlayer.playWhenReady,
                    "playbackState" to sessionPlayer.playbackState,
                    "isPlaying" to sessionPlayer.isPlaying,
                    "volume" to sessionPlayer.volume,
                    "changeReason" to host.lastPlayWhenReadyChangeReason,
                    "handedOff" to host.handedOff,
                    "tookOver" to (session.outPort?.tookOver == true),
                    "positionMs" to sessionPlayer.currentPosition,
                    "loopIncomingStarted" to state.loopIncomingStarted,
                    "inStartAtMs" to (session.inStartAt * 1000).roundToLong(),
                    "handoffAtMs" to (session.clock.handoffAt * 1000).roundToLong(),
                ),
            )
            return loopBail("paused", settle = true)
        }
        if (state.loopCleanupContendedSession === session) return finishLoop()
        val now = System.nanoTime()
        val m = session.mixSeconds(now, out.isPlaying) ?: return
        if (!state.loopIncomingStarted && m >= session.inStartAt) {
            into.playWhenReady = true
            state.loopIncomingStarted = true
            host.yieldAnalysis()
        }
        if (state.loopIncomingStarted && state.loopAlignJob == null && into.isPlaying &&
            session.inPort?.positionUs != C.TIME_UNSET && m >= session.inStartAt + LOOP_ALIGN_SETTLE_SECONDS
        ) {
            startLoopAlignment(session)
        }
        val visualEnd = session.clock.releaseAt
        if (m >= 0.0) AutoMixVisualFeed.start(host.visualGeneration, m.coerceAtMost(visualEnd))
        if (!host.handedOff && m >= session.clock.handoffAt) {
            if (canAdoptSessionPlayer(state.loopIncomingStarted)) {
                host.handOff(out, into, m, visualEnd)
            } else if (!state.deferredHandoffLogged) {
                state.deferredHandoffLogged = true
                AutomixTelemetry.event(
                    "mix.handoff.deferred",
                    mapOf(
                        "mMs" to (m * 1000).roundToLong(),
                        "inStartAtMs" to (session.inStartAt * 1000).roundToLong(),
                        "handoffAtMs" to (session.clock.handoffAt * 1000).roundToLong(),
                    ),
                )
            }
        }
        if (m >= 0.0) {
            AutoMixVisualFeed.advance(host.visualGeneration, m.coerceAtMost(visualEnd), visualEnd)
            if (m >= visualEnd) AutoMixVisualFeed.complete(host.visualGeneration)
        }
        publishLoopBeat(session, m, now)
        announceLoopPosition()
        if (m >= session.clock.elementAt + LOOP_END_MARGIN_SECONDS) finishLoop()
    }

    /** No callback work: contradictory local evidence is checked on the ticker. */
    fun rejectLiveCrown(): Boolean {
        val plan = state.loopSession?.plan ?: state.loopTarget ?: return false
        if (!armDriver.isCrown(plan)) return false
        val outItem = state.loopOutItem ?: return false
        val inItem = state.loopInItem ?: return false
        val reason = armDriver.retainedPlanRefusal(
            plan, host.analysisFor(outItem), host.analysisFor(inItem),
            host.lyricSpans[outItem.mediaId]?.spans, host.lyricSpans[inItem.mediaId]?.spans,
        ) ?: return false
        if (state.loopSession?.outPort?.tookOver == true) {
            loopBail(reason, settle = false, preserveSeek = false, deferDisarm = false, force = false)
        } else {
            loopFallBack(reason)
        }
        return true
    }

    /**
     * Puts the incoming player exactly in step with the deck it takes over
     * from, while it is still silent: measures the offset on both sinks' own
     * clocks, and has its sink drop frames or insert silence to cancel it.
     */
    fun startLoopAlignment(session: LoopMixSession) {
        val job = host.scope.launch {
            try {
                repeat(LOOP_ALIGN_ROUNDS) { round ->
                    val samples = ArrayList<Double>(LOOP_ALIGN_SAMPLES)
                    repeat(LOOP_ALIGN_SAMPLES) {
                        session.offsetNow(System.nanoTime())?.let(samples::add)
                        delay(LOOP_ALIGN_SAMPLE_MS)
                    }
                    if (samples.size < LOOP_ALIGN_SAMPLES / 2) return@launch
                    samples.sort()
                    val error = samples[samples.size / 2]
                    val spread = samples.last() - samples.first()
                    Log.d(
                        TAG,
                        "loop align $round: incoming ${"%+.2f".format(Locale.ROOT, error * 1000)}ms against the deck " +
                            "(spread ${"%.2f".format(Locale.ROOT, spread * 1000)}ms)",
                    )
                    session.recordAligned(error)
                    if (LoopMixSession.aligned(error)) return@launch
                    val port = session.inPort
                    val lead = if (port != null && port.processedUs != C.TIME_UNSET && port.positionUs != C.TIME_UNSET) {
                        ((port.processedUs - port.positionUs).coerceAtLeast(0L)) / 1e6
                    } else {
                        1.0
                    }
                    session.correct(error)
                    delay(((lead + 0.15) * 1000).roundToLong().coerceIn(250L, 2500L))
                }
                session.offsetNow(System.nanoTime())?.let {
                    session.recordAligned(it)
                    Log.d(TAG, "loop align: left at ${"%+.2f".format(Locale.ROOT, it * 1000)}ms")
                }
            } finally {
                if (state.loopAlignJob != null && state.loopAlignJob === coroutineContext[Job]) state.loopAlignJob = null
            }
        }
        state.loopAlignJob = job
    }

    /** The scrubber's beat pulse, on the mix's own beats (they move through the ramp). */
    fun publishLoopBeat(session: LoopMixSession, m: Double, now: Long) {
        if (m < 0) return
        val (beat, _) = session.clock.beatAt(m)
        val times = session.clock.beatTimes
        if (beat == state.lastLoopBeat || beat !in 0 until times.size - 1) return
        state.lastLoopBeat = beat
        val beatMs = ((times[beat + 1] - times[beat]) * 1000).toFloat()
        val anchor = now - ((m - times[beat]) * 1e9).roundToLong()
        AppSettings.smartMixBlend.value = MixBlend(beatMs = beatMs, beatAnchorNanos = anchor, playing = true)
    }

    /**
     * Re-anchors the session's controllers on the mix's playhead: at every loop
     * wrap (a deliberate jump back) and every couple of seconds through the
     * ramp, where the song runs at the ramp's tempo rather than at 1x.
     */
    fun announceLoopPosition() {
        val position = loopPositionMs() ?: return
        val now = host.nowMs()
        val wrapped = state.lastLoopPositionMs >= 0 && position < state.lastLoopPositionMs - LOOP_WRAP_MS
        state.lastLoopPositionMs = position
        if (wrapped || now - state.lastLoopAnnounceAt >= LOOP_ANNOUNCE_MS) {
            state.lastLoopAnnounceAt = now
            host.onMixPositionJump(wrapped)
        }
    }

    /**
     * Where the session's song is during a loop mix, from the mix's own clock:
     * looping, following the ramp, and on its own after the release. Null
     * outside one, where the player's own position is the truth.
     */
    fun loopPositionMs(): Long? {
        if (!host.isLoopRunning()) return null
        val session = state.loopSession ?: return null
        val playing = host.outgoing?.isPlaying ?: return null
        val m = session.mixSeconds(System.nanoTime(), playing) ?: return null
        if (m < 0) return null
        val side = if (host.handedOff) LoopMix.Side.IN else LoopMix.Side.OUT
        val position = LoopMix.positionAt(session.clock, side, m) ?: return null
        return (position * 1000).roundToLong()
    }

    /** The mix played out: the incoming player is the song, the outgoing one is retired. */
    fun finishLoop() {
        val session = state.loopSession ?: return host.finish()
        captureLoopExitObservation(session)
        val out = host.outgoing
        val into = host.incoming
        val wasIncomingStarted = state.loopIncomingStarted
        val nowNanos = System.nanoTime()
        val outReading = session.outPort?.readPresentedPosition(nowNanos, out?.isPlaying == true)
        val inReading = if (wasIncomingStarted) session.inPort?.readPresentedPosition(nowNanos, into?.isPlaying == true) else null
        val contended = outReading?.contended == true || inReading?.contended == true
        if (contended && (if (host.handedOff) into?.playWhenReady else out?.playWhenReady) == true &&
            session.outPort?.interrupted != true && session.inPort?.interrupted != true
        ) {
            if (state.loopCleanupContendedSession !== session) {
                state.loopCleanupContendedSession = session
                state.loopCleanupContendedSinceNanos = nowNanos
            }
            if (nowNanos - state.loopCleanupContendedSinceNanos < 100_000_000L) return
            loopBail("loop-cleanup-clock-contention-timeout", settle = false, preserveSeek = true, deferDisarm = true)
            return
        }
        state.loopCleanupContendedSession = null
        state.loopCleanupContendedSinceNanos = 0L
        val m = outReading?.seconds?.let { it - session.plan.out.loopStart }
        val incomingNow = inReading?.seconds
        val violations = session.renderer.exitGateViolations()
        val abnormal = when {
            out == null || into == null -> "loop-cleanup-missing-player"
            session.outPort?.interrupted == true || session.inPort?.interrupted == true -> "loop-cleanup-interrupted"
            !(if (host.handedOff) into.playWhenReady else out.playWhenReady) -> "loop-cleanup-paused"
            m == null -> "loop-cleanup-clock-unavailable"
            m < session.clock.elementAt -> "loop-cleanup-before-splice"
            violations != 0 -> "loop-cleanup-exit-gate"
            wasIncomingStarted && (!out.isPlaying || !into.isPlaying ||
                into.playbackState != Player.STATE_READY ||
                incomingNow == null) -> "loop-cleanup-incoming-unavailable"
            !wasIncomingStarted && (!out.playWhenReady || !out.isPlaying) -> "loop-cleanup-outgoing-unavailable"
            else -> null
        }
        if (abnormal != null) {
            loopBail(abnormal, settle = false, preserveSeek = true, deferDisarm = true)
            return
        }
        host.lastCompleteReason = "loop-complete"
        val plan = session.plan
        if (host.outgoingAnalysis.bpm <= 0) host.outgoingAnalysis = host.outgoingAnalysis.copy(bpm = plan.out.bpm)
        if (host.incomingAnalysis.bpm <= 0) host.incomingAnalysis = host.incomingAnalysis.copy(bpm = plan.inn.bpm)
        host.tailDurationMs = RetireGuard.loopTailMs(plan.exitTailSeconds)
        Log.d(
            TAG,
            "loop mix cleanup: incoming ${"%+.2f".format(Locale.ROOT, session.alignedErrorMs)}ms " +
                "against the deck at the handover",
        )
        state.loopScript?.let { script ->
            val counts = MixDebug.counts()
            Log.i("AutomixScript", TransitionMoves.scriptJson(script, counts[0], counts[1], counts[2]))
            Log.i("BitChordPhrase", TransitionMoves.phraseLine(script.choice))
        }
        val expectedHandoverMs = (session.handoverTrack * 1000).roundToLong()
        val heardInSecRaw = runCatching { session.renderer.heard(LoopMixRenderer.IN) }.getOrNull()
        val heardValidSec = heardInSecRaw?.takeIf { it.isFinite() && it != 0.0 &&
            it >= session.plan.inn.loopStart - 0.05 }
        val targetSec = session.handoverTrack + (m!! - session.clock.elementAt)
        val targetMs = (targetSec * 1000).roundToLong()
        host.handoverExpectedMs = expectedHandoverMs
        host.handoverHeardMs = heardValidSec?.let { (it * 1000).roundToLong() } ?: -1L
        host.handoverTargetMs = targetMs
        val driftMs = incomingNow?.let { ((targetSec - it) * 1000).roundToLong() }
        if (!wasIncomingStarted || (driftMs != null && abs(driftMs) > 150L)) {
            val reason = if (wasIncomingStarted) "loop-handover-drift-recovery" else "loop-handover-unstarted-recovery"
            host.lastCompleteReason = reason
            AutomixTelemetry.event(
                "mix.seek",
                mapOf(
                    "phase" to host.phaseName, "reason" to reason,
                    "expectedHandoverMs" to expectedHandoverMs,
                    "actualHeardMs" to host.handoverHeardMs, "targetMs" to targetMs,
                    "driftMs" to driftMs,
                ),
            )
            host.internalHandoverSeek = true
            host.suppressScrubUntilMs = host.nowMs() + 1_200L
            try {
                into!!.seekTo(targetMs.coerceAtLeast(0L))
            } finally {
                host.internalHandoverSeek = false
            }
        }
        if (!wasIncomingStarted) {
            state.loopIncomingStarted = true
            into!!.playWhenReady = true
        }
        if (!host.handedOff) host.handOff(out!!, into!!, m, session.clock.releaseAt)
        if (!host.handedOff) {
            loopBail("loop-cleanup-adoption-refused", settle = false, preserveSeek = true, deferDisarm = true)
            return
        }
        if (m >= session.clock.releaseAt) AutoMixVisualFeed.complete(host.visualGeneration)
        if (wasIncomingStarted && incomingNow != null) session.recordAligned(incomingNow - targetSec)
        state.loopAlignJob?.cancel()
        state.loopAlignJob = null
        host.outgoing?.volume = 0f
        captureLoopExitObservation(session)
        session.outPort?.disarm()
        session.inPort?.disarm()
        state.loopSession = null
        AutomixTelemetry.event("mix.exit.gate", mapOf("violations" to violations))
        runCatching {
            val aligned = session.alignedErrorMs
            val phaseErr = if (wasIncomingStarted && aligned.isFinite()) aligned else Double.NaN
            val vr = com.music.bitchord.playback.smart.MixVerify.verify(0.0, 0.0f, phaseErr, violations)
            AutomixTelemetry.event(
                "mix.verify",
                mapOf(
                    "ok" to (vr.ok && phaseErr.isFinite()),
                    "reasons" to if (phaseErr.isFinite()) vr.reasons.joinToString(",") else "phase-unmeasured",
                    "phaseErrorMs" to phaseErr.takeIf { it.isFinite() },
                    "exitGateViolations" to violations,
                    "scope" to "phase-and-exit-gate-only",
                ),
            )
        }

        host.yieldAnalysis()
        host.finish()
        closeLater(session.renderer)
    }

    fun loopBail(
        reason: String,
        settle: Boolean = true,
        preserveSeek: Boolean = false,
        deferDisarm: Boolean = false,
    ) = loopBail(reason, settle, preserveSeek, deferDisarm, force = true)

    fun loopBail(
        reason: String,
        settle: Boolean = true,
        preserveSeek: Boolean = false,
        deferDisarm: Boolean = false,
        force: Boolean,
    ) {
        val currentSession = state.loopSession
        if (!force && (host.isLoopRunning() || currentSession?.outPort?.tookOver == true || host.handedOff)) {
            val freezeKey = freezeDedupKey(
                "loopBail:${host.phaseName}:$reason",
                host.outgoing?.currentMediaItem?.mediaId,
                host.incoming?.currentMediaItem?.mediaId,
            )
            if (freezeKey != host.lastFreezeLogKey) {
                host.lastFreezeLogKey = freezeKey
                AutomixTelemetry.event(
                    "mix.freeze.onInterruption",
                    mapOf("phase" to host.phaseName, "reason" to reason),
                )
            }
            return
        }
        host.cancelAutoMixVisual()
        host.lastCompleteReason = reason
        if (host.initiatingExitReason == null) host.initiatingExitReason = reason
        state.loopCleanupContendedSession = null
        state.loopCleanupContendedSinceNanos = 0L
        val session = state.loopSession
        if (session != null) captureLoopExitObservation(session)
        val bailPlan = session?.plan
        if (bailPlan != null) {
            if (host.outgoingAnalysis.bpm <= 0) host.outgoingAnalysis = host.outgoingAnalysis.copy(bpm = bailPlan.out.bpm)
            if (host.incomingAnalysis.bpm <= 0) host.incomingAnalysis = host.incomingAnalysis.copy(bpm = bailPlan.inn.bpm)
        }
        if (state.loopRefusal.isNullOrBlank()) state.loopRefusal = reason
        Log.d(TAG, "loop mix stops (${host.phaseName}): $reason")
        armDriver.discardPrime()
        state.loopBuildJob?.cancel()
        state.loopBuildJob = null
        state.loopAlignJob?.cancel()
        state.loopAlignJob = null
        val out = host.outgoing
        val into = host.incoming
        val wasHandedOff = host.handedOff
        val m = session?.mixSeconds(System.nanoTime(), playing = false)
        val tookOver = session?.outPort?.tookOver == true
        val defer = SeekAction.shouldDeferLoopDisarm(deferDisarm, tookOver)
        if (defer && session != null) {
            if (wasHandedOff) session.inPort?.disarm()
            host.tailLoopSession = session
        } else {
            session?.outPort?.disarm()
            session?.inPort?.disarm()
        }
        if (!tookOver && !wasHandedOff) {
            into?.volume = 0f
            into?.playWhenReady = false
        }
        val target: Long? = when {
            session == null -> null
            wasHandedOff -> m?.let { LoopMix.positionAt(session.clock, LoopMix.Side.IN, it) }?.let { (it * 1000).roundToLong() }
            tookOver -> (m?.takeIf { it >= 0 }?.let { LoopMix.positionAt(session.clock, LoopMix.Side.OUT, it) }
                ?: session.plan.out.loopStart).let { (it * 1000).roundToLong() }
            else -> null
        }
        state.loopSession = null
        host.finish()
        if (!defer) {
            session?.renderer?.let(::closeLater)
        } else if (!host.isTail()) {
            host.disarmTailLoopSession()
        }
        val sessionPlayer = if (wasHandedOff) into else out
        if (sessionPlayer != null && target != null && !preserveSeek && (settle || (!wasHandedOff && tookOver))) {
            sessionPlayer.seekTo(target)
        }
    }

    fun captureLoopExitObservation(session: LoopMixSession) {
        val port = session.outPort ?: return
        host.capturedLoopTookOver = port.tookOver
        host.capturedTakeoverSpliceBufferStatus = port.takeoverSpliceBufferStatus
    }

    fun loopFallBack(reason: String) {
        val failedPlan = state.loopSession?.plan ?: state.loopTarget ?: state.loopPlanned
        val failedTechnique = failedPlan?.let { host.nativeRouteFields(it)["technique"] as String } ?: "unknown-native"
        host.routeRefusal(
            "mix.arm.death", reason, "prepared", failedTechnique, "classic",
            coarseDecline = host.nativeBinding?.coarseDecline
                ?: state.loopOutAnalysis?.let { out -> state.loopInAnalysis?.let { inn -> shouldDeclineToMix(out, inn) } },
        )
        val fbKey = "$reason|${host.phaseName}"
        if (fbKey != host.lastFallbackLogKey) {
            host.lastFallbackLogKey = fbKey
            host.routeRefusal(
                "mix.loop.fallback", reason, "prepared", failedTechnique, "classic",
                coarseDecline = host.nativeBinding?.coarseDecline
                    ?: state.loopOutAnalysis?.let { out -> state.loopInAnalysis?.let { inn -> shouldDeclineToMix(out, inn) } },
            )
        }
        Log.d(TAG, "loop mix falls back to a blend: $reason")
        host.lastFireFallbackReason = reason
        val pairKey = state.loopOutItem?.let { o -> state.loopInItem?.let { i -> "${o.mediaId}->${i.mediaId}" } }
            ?: state.loopPlanKey?.substringBefore("|")
        if (pairKey != null) {
            host.pairPreparer?.recordTiming(
                outId = pairKey.substringBefore("->"),
                inId = pairKey.substringAfter("->", ""),
                fallbackReason = reason,
            )
            host.countdownStabilizer.onRungFailed(
                pairKey, host.scrubEpoch, CountdownStabilizer.Family.LOOP, suppressBridgeToo = true,
            )
            host.loopSuppressedPair = pairKey
            host.loopSuppressedUntil = host.nowMs() + LOOP_SUPPRESSION_MS
        }
        state.loopPlanned = null
        state.loopScript = null
        state.loopPlanKey = null
        host.lastSmartPlan = null
        host.lastSmartPlanStartMs = 0L
        host.pinnedCatchUpKey = null
        host.pinnedCatchUpPlan = null
        state.loopRefusal = reason
        val previousFallback = host.routingFallback
        host.routingFallback = true
        try {
            loopBail(reason, settle = false, preserveSeek = false, deferDisarm = false, force = false)
        } finally {
            if (!host.isTail()) host.routingFallback = previousFallback
        }
        val curState = host.mixExecutor.currentState()
        if (!host.isLoopRunning() && curState.pair != null &&
            curState.stateKind !in setOf(MixStateKind.IDLE, MixStateKind.DONE, MixStateKind.FAILED, MixStateKind.SUSPENDED)
        ) {
            host.mixExecutor.dispatch(
                MixEvent.RendererError(
                    code = FailureKind.RENDERER_RUNTIME_ERROR,
                    message = reason,
                    recoverable = false,
                ),
            )
        }
        if (host.isIdle() && !host.inFallbackReplan) {
            host.inFallbackReplan = true
            try {
                val p = host.active()
                val dur = p.duration
                if (AppSettings.smartFadeEnabled.value && p.hasNextMediaItem() && dur != C.TIME_UNSET && dur > 0L &&
                    (!host.isNativeRendererAvailable(p) || host.mixExecutor.currentState() is MixState.Failed)
                ) {
                    host.considerSmartTransition(dur)
                    host.publishAutomixDetail()
                }
            } finally {
                host.inFallbackReplan = false
            }
        }
    }

    fun closeLater(renderer: LoopMixRenderer) {
        host.scope.launch(Dispatchers.IO) {
            delay(LOOP_RENDERER_CLOSE_DELAY_MS)
            runCatching { renderer.close() }
        }
    }

    fun closeSessionOffThread(session: LoopMixSession?) {
        if (session == null) return
        host.scope.launch(Dispatchers.IO) {
            runCatching { session.close() }
        }
    }
}
