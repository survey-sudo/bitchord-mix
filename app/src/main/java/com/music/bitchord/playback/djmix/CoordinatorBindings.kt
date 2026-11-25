package com.music.bitchord.playback.djmix

import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.MixWaveform
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.math.round
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import com.music.bitchord.playback.SleepTimer
import com.music.bitchord.playback.TransitionFilters


/** State observed or updated by [CoordinatorBindings]; all access stays on the controller thread. */
@OptIn(UnstableApi::class)
internal interface CoordinatorBindingsHost : CoordinatorCallbacks {
    val active: () -> ExoPlayer
    var activeMachineGeneration: Long
    val analysisFor: (MediaItem) -> TrackAnalysis
    var bailFromGain: Float
    var bailStartedAt: Long
    var blendArmLeadMs: Long
    var buildGeneration: Int
    var capturedLoopTookOver: Boolean?
    var capturedTakeoverSpliceBufferStatus: LoopMixPort.TakeoverSpliceBufferStatus?
    var classicFallbackReason: String
    val countdownStabilizer: CountdownStabilizer
    var deckSide: String
    val deckTiming: DeckTiming
    var deferredHandoffLogged: Boolean
    var executedRoute: Map<String, Any?>?
    val fadeCurves: FadeRideCurves
    var fadeEndMs: Long
    var fadeMs: Long
    var fallbackNaturalGeneration: Long
    val filters: TransitionFilters
    var handedOff: Boolean
    var handoverExpectedMs: Long
    var handoverHeardMs: Long
    var handoverTargetMs: Long
    var inFallbackReplan: Boolean
    var incoming: ExoPlayer?
    var incomingAnalysis: TrackAnalysis
    var incomingCueTimeMs: Long
    var initiatingExitReason: String?
    var internalHandoverSeek: Boolean
    var lastArmDeathKey: String?
    var lastCompleteReason: String?
    var lastEmittedPlanVerdict: String?
    var lastFallbackLogKey: String?
    var lastFireFallbackReason: String
    var lastFireFxCount: Int
    var lastFireMove: String
    var lastFirePath: String
    var lastFireStems: Int
    var lastFreezeLogKey: String?
    var lastLoopAnnounceAt: Long
    var lastLoopBeat: Int
    var lastLoopPositionMs: Long
    var lastLoopVerdict: String
    var lastPlayWhenReadyChangeReason: Int
    var lastRejectedLogKey: String?
    var lastSmartPlan: TransitionPlan?
    var lastSmartPlanStartMs: Long
    var lastTimingPair: Pair<String, String>?
    var loopAlignJob: Job?
    var loopBuildJob: Job?
    var loopBuildStartedAtMs: Long
    var loopCleanupContendedSession: LoopMixSession?
    var loopCleanupContendedSinceNanos: Long
    var loopInAnalysis: TrackAnalysis?
    var loopInDurationMs: Long
    var loopInItem: MediaItem?
    var loopIncomingStarted: Boolean
    var loopOutAnalysis: TrackAnalysis?
    var loopOutDurationMs: Long
    var loopOutItem: MediaItem?
    var loopPlanKey: String?
    var loopPlanned: LoopMix.Plan?
    val loopPortOf: (ExoPlayer) -> LoopMixPort?
    var loopRefusal: String?
    val loopRegion: (MediaItem, Long, Double, Double, Int) -> LoopRegion?
    var loopScript: PhraseScript?
    var loopSession: LoopMixSession?
    var loopSungIn: List<LoopMix.Span>?
    var loopSungOut: List<LoopMix.Span>?
    var loopSuppressedPair: String?
    var loopSuppressedUntil: Long
    var loopTarget: LoopMix.Plan?
    var loopTargetStartMs: Long
    val loudnessGain: (String) -> Float
    val lyricSpans: java.util.concurrent.ConcurrentHashMap<String, LyricSpans>
    val lyricsAskedAt: java.util.concurrent.ConcurrentHashMap<String, Long>
    val mediaRatio: Double
    val mixExecutor: MixExecutor
    val mixWaveform: (MediaItem, Long, Double, Double, Int) -> MixWaveform.Bands?
    var nativeBinding: NativeBinding?
    var naturalProvisionalUntilPosMs: Long
    val onArmAutoMixIncoming: (MediaItem) -> Unit
    val onArmIncoming: (MediaItem, String?) -> Unit
    val onBlendHeadroom: (Float) -> Unit
    val onMixPositionJump: (wrapped: Boolean) -> Unit
    var outgoing: ExoPlayer?
    var outgoingAnalysis: TrackAnalysis
    var outgoingItemIndex: Int
    val pairPreparer: com.music.bitchord.playback.djmix.PairPreparer?
    val phaseName: String
    var pinnedCatchUpKey: String?
    var pinnedCatchUpPlan: TransitionPlan?
    var planGeneration: Long
    var planInFlight: TransitionPlan?
    var planResultGeneration: Long
    var plannedNextId: String?
    var primeJob: Job?
    var primeRetryAt: Long
    var primedKey: String?
    var primedSession: LoopMixSession?
    var queuedItemCount: Int
    var render: RideSpec
    val routingEvidenceKeys: MutableSet<String>
    var routingFallback: Boolean
    val scope: CoroutineScope
    var scrubEpoch: Long
    var shownCountdownKey: String?
    var shownLoopStartMs: Long
    var smartFadeActive: Boolean
    val standby: () -> ExoPlayer
    var straightPreparedPair: com.music.bitchord.playback.djmix.PreparedPair?
    var suppressScrubUntilMs: Long
    var tailDurationMs: Long
    var tailLoopSession: LoopMixSession?
    var tailTarget: ExoPlayer?
    val tempoEngine: EaseTempoEngine
    var terminalRecorded: Boolean
    val tracks: TransitionFilters
    var visualGeneration: Long?
    var watchdogFired: Boolean
    val yieldAnalysis: () -> Unit
}

/** Adapts controller-owned state to the existing loop, status, filter, and feed host contracts. */
@MainThread
@OptIn(UnstableApi::class)
internal class CoordinatorBindings(private val host: CoordinatorBindingsHost) {
    val loopState: LoopDriverState = with(host) { object : LoopDriverState {
        override val deckTiming: DeckTiming get() = host.deckTiming
        override var loopSession: LoopMixSession?
            get() = host.loopSession
            set(v) { host.loopSession = v }
        override var loopBuildJob: Job?
            get() = host.loopBuildJob
            set(v) { host.loopBuildJob = v }
        override var loopAlignJob: Job?
            get() = host.loopAlignJob
            set(v) { host.loopAlignJob = v }
        override var primedSession: LoopMixSession?
            get() = host.primedSession
            set(v) { host.primedSession = v }
        override var primedKey: String?
            get() = host.primedKey
            set(v) { host.primedKey = v }
        override var primeJob: Job?
            get() = host.primeJob
            set(v) { host.primeJob = v }
        override var primeRetryAt: Long
            get() = host.primeRetryAt
            set(v) { host.primeRetryAt = v }
        override var buildGeneration: Int
            get() = host.buildGeneration
            set(v) { host.buildGeneration = v }
        override var deckSide: String
            get() = host.deckSide
            set(v) { host.deckSide = v }
        override var loopTarget: LoopMix.Plan?
            get() = host.loopTarget
            set(v) { host.loopTarget = v }
        override var loopTargetStartMs: Long
            get() = host.loopTargetStartMs
            set(v) { host.loopTargetStartMs = v }
        override var loopOutItem: MediaItem?
            get() = host.loopOutItem
            set(v) { host.loopOutItem = v }
        override var loopInItem: MediaItem?
            get() = host.loopInItem
            set(v) { host.loopInItem = v }
        override var loopOutAnalysis: TrackAnalysis?
            get() = host.loopOutAnalysis
            set(v) { host.loopOutAnalysis = v }
        override var loopInAnalysis: TrackAnalysis?
            get() = host.loopInAnalysis
            set(v) { host.loopInAnalysis = v }
        override var loopSungOut: List<LoopMix.Span>?
            get() = host.loopSungOut
            set(v) { host.loopSungOut = v }
        override var loopSungIn: List<LoopMix.Span>?
            get() = host.loopSungIn
            set(v) { host.loopSungIn = v }
        override var loopOutDurationMs: Long
            get() = host.loopOutDurationMs
            set(v) { host.loopOutDurationMs = v }
        override var loopInDurationMs: Long
            get() = host.loopInDurationMs
            set(v) { host.loopInDurationMs = v }
        override var straightPreparedPair: com.music.bitchord.playback.djmix.PreparedPair?
            get() = host.straightPreparedPair
            set(v) { host.straightPreparedPair = v }
        override var loopPlanKey: String?
            get() = host.loopPlanKey
            set(v) { host.loopPlanKey = v }
        override var loopPlanned: LoopMix.Plan?
            get() = host.loopPlanned
            set(v) { host.loopPlanned = v }
        override var loopScript: PhraseScript?
            get() = host.loopScript
            set(v) { host.loopScript = v }
        override var shownCountdownKey: String?
            get() = host.shownCountdownKey
            set(v) { host.shownCountdownKey = v }
        override var shownLoopStartMs: Long
            get() = host.shownLoopStartMs
            set(v) { host.shownLoopStartMs = v }
        override var watchdogFired: Boolean
            get() = host.watchdogFired
            set(v) { host.watchdogFired = v }
        override var loopRefusal: String?
            get() = host.loopRefusal
            set(v) { host.loopRefusal = v }
        override var lastLoopVerdict: String
            get() = host.lastLoopVerdict
            set(v) { host.lastLoopVerdict = v }
        override var loopIncomingStarted: Boolean
            get() = host.loopIncomingStarted
            set(v) { host.loopIncomingStarted = v }
        override var deferredHandoffLogged: Boolean
            get() = host.deferredHandoffLogged
            set(v) { host.deferredHandoffLogged = v }
        override var lastLoopPositionMs: Long
            get() = host.lastLoopPositionMs
            set(v) { host.lastLoopPositionMs = v }
        override var lastLoopAnnounceAt: Long
            get() = host.lastLoopAnnounceAt
            set(v) { host.lastLoopAnnounceAt = v }
        override var lastLoopBeat: Int
            get() = host.lastLoopBeat
            set(v) { host.lastLoopBeat = v }
        override var loopCleanupContendedSession: LoopMixSession?
            get() = host.loopCleanupContendedSession
            set(v) { host.loopCleanupContendedSession = v }
        override var loopCleanupContendedSinceNanos: Long
            get() = host.loopCleanupContendedSinceNanos
            set(v) { host.loopCleanupContendedSinceNanos = v }
    }
    }

    val loopDriverHost: LoopDriverHost = with(host) { object : LoopDriverHost {
        override val scope: CoroutineScope get() = host.scope
        override fun nowMs(): Long = host.nowMs()
        override fun active(): ExoPlayer = host.active()
        override fun standby(): ExoPlayer = host.standby()
        override val outgoing: ExoPlayer? get() = host.outgoing
        override val incoming: ExoPlayer? get() = host.incoming

        override val phaseName: String get() = host.phaseName
        override fun isLoopArming(): Boolean = host.isLoopArming()
        override fun isLoopRunning(): Boolean = host.isLoopRunning()
        override fun isIdle(): Boolean = host.isIdle()
        override fun isTail(): Boolean = host.isTail()
        override fun setPhaseLoopArming() { host.onLoopArming() }
        override fun setPhaseLoopRunning() { host.onLoopRunning() }

        override var smartFadeActive: Boolean
            get() = host.smartFadeActive
            set(v) { host.smartFadeActive = v }
        override var handedOff: Boolean
            get() = host.handedOff
            set(v) { host.handedOff = v }
        override var loopBuildStartedAtMs: Long
            get() = host.loopBuildStartedAtMs
            set(v) { host.loopBuildStartedAtMs = v }
        override var blendArmLeadMs: Long
            get() = host.blendArmLeadMs
            set(v) { host.blendArmLeadMs = v }
        override var loopSuppressedPair: String?
            get() = host.loopSuppressedPair
            set(v) { host.loopSuppressedPair = v }
        override var loopSuppressedUntil: Long
            get() = host.loopSuppressedUntil
            set(v) { host.loopSuppressedUntil = v }
        override var outgoingItemIndex: Int
            get() = host.outgoingItemIndex
            set(v) { host.outgoingItemIndex = v }
        override var queuedItemCount: Int
            get() = host.queuedItemCount
            set(v) { host.queuedItemCount = v }
        override var plannedNextId: String?
            get() = host.plannedNextId
            set(v) { host.plannedNextId = v }
        override var initiatingExitReason: String?
            get() = host.initiatingExitReason
            set(v) { host.initiatingExitReason = v }
        override var capturedTakeoverSpliceBufferStatus: LoopMixPort.TakeoverSpliceBufferStatus?
            get() = host.capturedTakeoverSpliceBufferStatus
            set(v) { host.capturedTakeoverSpliceBufferStatus = v }
        override var capturedLoopTookOver: Boolean?
            get() = host.capturedLoopTookOver
            set(v) { host.capturedLoopTookOver = v }
        override var lastCompleteReason: String?
            get() = host.lastCompleteReason
            set(v) { host.lastCompleteReason = v }
        override var lastTimingPair: Pair<String, String>?
            get() = host.lastTimingPair
            set(v) { host.lastTimingPair = v }
        override var lastFirePath: String
            get() = host.lastFirePath
            set(v) { host.lastFirePath = v }
        override var lastFireMove: String
            get() = host.lastFireMove
            set(v) { host.lastFireMove = v }
        override var lastFireFxCount: Int
            get() = host.lastFireFxCount
            set(v) { host.lastFireFxCount = v }
        override var lastFireStems: Int
            get() = host.lastFireStems
            set(v) { host.lastFireStems = v }
        override var lastFireFallbackReason: String
            get() = host.lastFireFallbackReason
            set(v) { host.lastFireFallbackReason = v }
        override val lastPlayWhenReadyChangeReason: Int
            get() = host.lastPlayWhenReadyChangeReason
        override var lastFreezeLogKey: String?
            get() = host.lastFreezeLogKey
            set(v) { host.lastFreezeLogKey = v }
        override var lastArmDeathKey: String?
            get() = host.lastArmDeathKey
            set(v) { host.lastArmDeathKey = v }
        override var lastFallbackLogKey: String?
            get() = host.lastFallbackLogKey
            set(v) { host.lastFallbackLogKey = v }
        override var outgoingAnalysis: TrackAnalysis
            get() = host.outgoingAnalysis
            set(v) { host.outgoingAnalysis = v }
        override var incomingAnalysis: TrackAnalysis
            get() = host.incomingAnalysis
            set(v) { host.incomingAnalysis = v }
        override var lastSmartPlan: TransitionPlan?
            get() = host.lastSmartPlan
            set(v) { host.lastSmartPlan = v }
        override var lastSmartPlanStartMs: Long
            get() = host.lastSmartPlanStartMs
            set(v) { host.lastSmartPlanStartMs = v }
        override var pinnedCatchUpKey: String?
            get() = host.pinnedCatchUpKey
            set(v) { host.pinnedCatchUpKey = v }
        override var pinnedCatchUpPlan: TransitionPlan?
            get() = host.pinnedCatchUpPlan
            set(v) { host.pinnedCatchUpPlan = v }
        override var handoverExpectedMs: Long
            get() = host.handoverExpectedMs
            set(v) { host.handoverExpectedMs = v }
        override var handoverHeardMs: Long
            get() = host.handoverHeardMs
            set(v) { host.handoverHeardMs = v }
        override var handoverTargetMs: Long
            get() = host.handoverTargetMs
            set(v) { host.handoverTargetMs = v }
        override var internalHandoverSeek: Boolean
            get() = host.internalHandoverSeek
            set(v) { host.internalHandoverSeek = v }
        override var suppressScrubUntilMs: Long
            get() = host.suppressScrubUntilMs
            set(v) { host.suppressScrubUntilMs = v }
        override var inFallbackReplan: Boolean
            get() = host.inFallbackReplan
            set(v) { host.inFallbackReplan = v }
        override var routingFallback: Boolean
            get() = host.routingFallback
            set(v) { host.routingFallback = v }
        override var tailLoopSession: LoopMixSession?
            get() = host.tailLoopSession
            set(v) { host.tailLoopSession = v }
        override var tailDurationMs: Long
            get() = host.tailDurationMs
            set(v) { host.tailDurationMs = v }

        override val visualGeneration: Long? get() = host.visualGeneration
        override val scrubEpoch: Long get() = host.scrubEpoch
        override val activeMachineGeneration: Long get() = host.activeMachineGeneration
        override val pairPreparer: com.music.bitchord.playback.djmix.PairPreparer? get() = host.pairPreparer
        override val mixExecutor: MixExecutor get() = host.mixExecutor
        override val tempoEngine: EaseTempoEngine get() = host.tempoEngine
        override val countdownStabilizer: CountdownStabilizer get() = host.countdownStabilizer
        override val filters: TransitionFilters get() = host.filters
        override val tracks: TransitionFilters get() = host.tracks
        override val lyricSpans: Map<String, LyricSpans> get() = host.lyricSpans
        override val lyricsAskedAt: Map<String, Long> get() = host.lyricsAskedAt

        override fun loopPortOf(player: ExoPlayer): LoopMixPort? = host.loopPortOf(player)
        override fun holdAtEnd(player: ExoPlayer) = host.holdAtEnd(player)
        override fun armAutoMixVisual(out: ExoPlayer, into: ExoPlayer, nextIndex: Int, items: List<MediaItem>) =
            host.armAutoMixVisual(out, into, nextIndex, items)
        override fun cancelAutoMixVisual() = host.cancelAutoMixVisual()
        override fun onArmIncoming(item: MediaItem, afterId: String?) = host.onArmIncoming(item, afterId)
        override fun onArmAutoMixIncoming(item: MediaItem) = host.onArmAutoMixIncoming(item)
        override fun onBlendHeadroom(headroom: Float) = host.onBlendHeadroom(headroom)
        override fun yieldAnalysis() = host.yieldAnalysis()
        override fun handOff(out: ExoPlayer, into: ExoPlayer, m: Double, visualEnd: Double) =
            host.handOff(out, into, m, visualEnd)
        override fun onMixPositionJump(wrapped: Boolean) = host.onMixPositionJump(wrapped)
        override fun finish() = host.finish()
        override fun disarmTailLoopSession() = host.disarmTailLoopSession()
        override fun openFiltersWhenSilent() = host.openFiltersWhenSilent()
        override fun publishAutomixDetail() = host.publishAutomixDetail()
        override fun considerSmartTransition(duration: Long) = host.considerSmartTransition(duration)
        override fun isNativeRendererAvailable(player: ExoPlayer): Boolean =
            host.isNativeRendererAvailable(player)
        override fun analysisFor(item: MediaItem): TrackAnalysis = host.analysisFor(item)
        override fun loudnessGain(id: String): Float = host.loudnessGain(id)
        override fun loopRegion(item: MediaItem, length: Long, from: Double, to: Double, sampleRate: Int): LoopRegion? =
            host.loopRegion(item, length, from, to, sampleRate)
        override fun frozenPreparedFor(
            outItem: MediaItem,
            inItem: MediaItem,
            targetGen: Long,
            sampleRate: Int,
            djLevel: com.music.bitchord.playback.smart.choreo.DjLevel?,
        ): PreparedPair? = host.frozenPreparedFor(
            outItem,
            inItem,
            targetGen,
            sampleRate,
            djLevel ?: AppSettings.automixDjLevel.value,
        )
        override fun isStraightRoute(plan: LoopMix.Plan): Boolean = host.isStraightRoute(plan)

        override fun ensureOpportunity(outId: String, inId: String): Opportunity =
            host.ensureOpportunity(outId, inId)
        override val opportunity: Opportunity? get() = host.opportunity
        override val planRevision: Int get() = host.planRevision
        override fun reviseRoute(plan: Any): Int = host.reviseRoute(plan)
        override var executedRoute: Map<String, Any?>?
            get() = host.executedRoute
            set(v) { host.executedRoute = v }
        override val terminalRecorded: Boolean get() = host.terminalRecorded
        override var nativeBinding: NativeBinding?
            get() = host.nativeBinding
            set(v) { host.nativeBinding = v }
        override val routingEvidenceKeys: MutableSet<String> get() = host.routingEvidenceKeys
        override fun nativeRouteFields(plan: LoopMix.Plan, coarseDecline: Boolean?): Map<String, Any?> =
            host.nativeRouteFields(plan, actualConfiguration = true, coarseDecline = coarseDecline)
        override fun nativeSessionRouteFields(session: LoopMixSession, coarseDecline: Boolean?): Map<String, Any?> =
            host.nativeSessionRouteFields(session, coarseDecline)
        override fun routeRefusal(
            name: String,
            reason: String,
            stage: String,
            technique: String,
            destination: String?,
            context: Opportunity?,
            revision: Int,
            generation: Int?,
            coarseDecline: Boolean?,
        ) = host.routeRefusal(name, reason, stage, technique, destination, context, revision, generation, coarseDecline)
        override fun preparedRoute(route: Map<String, Any?>, context: Opportunity?, revision: Int, generation: Int?) =
            host.preparedRoute(route, context, revision, generation)
        override fun mixEvent(name: String, fields: Map<String, Any?>, context: Opportunity?) =
            host.mixEvent(name, fields, context)
    }
    }

    val mixStatusHost: MixStatusHost = with(host) { object : MixStatusHost {
        override fun active(): ExoPlayer = host.active()
        override val outgoing: ExoPlayer? get() = host.outgoing
        override val incoming: ExoPlayer? get() = host.incoming
        override val tailTarget: ExoPlayer? get() = host.tailTarget
        override val handedOff: Boolean get() = host.handedOff
        override val blendArmLeadMs: Long get() = host.blendArmLeadMs
        override val mixExecutor: MixExecutor get() = host.mixExecutor
        override val loopSession: LoopMixSession? get() = host.loopSession
        override val classicFallbackReason: String get() = host.classicFallbackReason
        override val lastSmartPlan: TransitionPlan? get() = host.lastSmartPlan
        override val lastSmartPlanStartMs: Long get() = host.lastSmartPlanStartMs
        override fun isNativeRendererAvailable(player: ExoPlayer): Boolean = host.isNativeRendererAvailable(player)
        override fun analysisFor(item: MediaItem): TrackAnalysis = host.analysisFor(item)
        override fun stateOf(item: MediaItem, analysis: TrackAnalysis): TrackAnalysisState = host.stateOf(item, analysis)
        override val filters: TransitionFilters get() = host.filters
        override val tracks: TransitionFilters get() = host.tracks
    }
    }

    /**
     * [filters] addressed by track rather than by session role.
     *
     * The service wires [TransitionFilters.incoming] to the session player's
     * sink and [TransitionFilters.outgoing] to the spare's, which describes the
     * two tracks only once the handoff has moved the session. The handoff is
     * halfway through the blend now, so for the first half the roles are the
     * other way round, and each ride would otherwise be filtering the wrong
     * song.
     */
    val tracks: TransitionFilters = with(host) { object : TransitionFilters {
        override fun incoming(lowPassHz: Float, highPassHz: Float) {
            if (handedOff) filters.incoming(lowPassHz, highPassHz) else filters.outgoing(lowPassHz, highPassHz)
        }

        override fun outgoing(lowPassHz: Float, highPassHz: Float) {
            if (handedOff) filters.outgoing(lowPassHz, highPassHz) else filters.incoming(lowPassHz, highPassHz)
        }

        override fun incomingLeadMs(): Long =
            if (handedOff) filters.incomingLeadMs() else filters.outgoingLeadMs()

        override fun outgoingLeadMs(): Long =
            if (handedOff) filters.outgoingLeadMs() else filters.incomingLeadMs()

        override fun outgoingEcho(delaySeconds: Float, send: Float, dry: Float) {
            if (handedOff) {
                filters.outgoingEcho(delaySeconds, send, dry)
            } else {
                filters.incomingEcho(delaySeconds, send, dry)
            }
        }

        override fun incomingTempo(rate: Float) {
            if (handedOff) filters.incomingTempo(rate) else filters.outgoingTempo(rate)
        }

        override fun outgoingTempo(rate: Float) {
            if (handedOff) filters.outgoingTempo(rate) else filters.incomingTempo(rate)
        }

        override fun outgoingColour(
            effect: com.music.bitchord.playback.smart.DjEffect,
            amount: Float,
            beatSeconds: Float,
            scoop: Float,
        ) {
            if (handedOff) filters.outgoingColour(effect, amount, beatSeconds, scoop)
            else filters.incomingColour(effect, amount, beatSeconds, scoop)
        }

        override fun incomingColour(
            effect: com.music.bitchord.playback.smart.DjEffect,
            amount: Float,
            beatSeconds: Float,
            scoop: Float,
        ) {
            if (handedOff) filters.incomingColour(effect, amount, beatSeconds, scoop)
            else filters.outgoingColour(effect, amount, beatSeconds, scoop)
        }

        override fun incomingMidDb(gainDb: Float) {
            if (handedOff) filters.incomingMidDb(gainDb) else filters.outgoingMidDb(gainDb)
        }

        override fun outgoingMidDb(gainDb: Float) {
            if (handedOff) filters.outgoingMidDb(gainDb) else filters.incomingMidDb(gainDb)
        }
    }
    }

    val mixViewHost: MixViewHost = with(host) { object : MixViewHost {
        override val scope: CoroutineScope get() = host.scope
        override val fadeCurves: FadeRideCurves get() = host.fadeCurves
        override val tempoEngine: EaseTempoEngine get() = host.tempoEngine
        override val mixWaveform: (MediaItem, Long, Double, Double, Int) -> MixWaveform.Bands?
            get() = host.mixWaveform
        override fun nowMs(): Long = host.nowMs()
        override fun positionMs(player: ExoPlayer): Double = host.positionMs(player)
        override fun isSmartFadeActive(): Boolean = smartFadeActive
        override fun isHandedOff(): Boolean = handedOff
        override fun isIdle(): Boolean = host.isIdle()
        override val outgoingAnalysis: TrackAnalysis get() = host.outgoingAnalysis
        override val incomingAnalysis: TrackAnalysis get() = host.incomingAnalysis
        override val planInFlight: TransitionPlan? get() = host.planInFlight
        override val fadeMs: Long get() = host.fadeMs
        override val fadeEndMs: Long get() = host.fadeEndMs
        override val incomingCueTimeMs: Long get() = host.incomingCueTimeMs
        override val mediaRatio: Double get() = host.mediaRatio
        override fun currentRideSpec(): RideSpec = render
        override fun rideSpecFor(plan: TransitionPlan, advanced: Boolean): RideSpec =
            renderFor(plan, advanced)
    }
    }

    val mixEventHost: MixEventHost = with(host) { object : MixEventHost {
        override val phaseName: String get() = host.phaseName
        override val scrubEpoch: Long get() = host.scrubEpoch
        override fun isIdle(): Boolean = host.isIdle()
        override fun active(): ExoPlayer = host.active()
        override val outgoingPlayer: ExoPlayer? get() = outgoing
        override fun setOutgoingPlayer(player: ExoPlayer?) {
            outgoing = player
        }
        override fun setIncomingPlayer(player: ExoPlayer?) {
            incoming = player
        }
        override fun setHandedOff(handedOff: Boolean) {
            host.handedOff = handedOff
        }
        override fun onSleepFadeStarted() {
            onSleepFade()
        }
        override fun onSleepFadeFinished() {
            outgoing = null
            onIdle()
        }
        override fun onSleepFadeAborted() {
            outgoing = null
            onIdle()
        }
        override fun isSleepTimerAfterTrack(): Boolean = SleepTimer.afterTrack.value
        override fun cancelSleepTimer() {
            SleepTimer.cancel()
        }
        override fun leavingOnBail(): ExoPlayer? = host.leavingOnBail()
        override fun finish() {
            host.finish()
        }
        override fun nowMs(): Long = host.nowMs()
        override val bailStartedAt: Long get() = host.bailStartedAt
        override val bailFromGain: Float get() = host.bailFromGain
        override fun isRanOn(player: ExoPlayer): Boolean = ranOn(player)
        override fun onBlendHeadroom(headroom: Float) {
            host.onBlendHeadroom(headroom)
        }
        override fun onNewOpportunity() {
            countdownStabilizer.clearFailedNativeRecovery()
            planGeneration++
            planResultGeneration = -1L
            fallbackNaturalGeneration = -1L
            naturalProvisionalUntilPosMs = -1L
            initiatingExitReason = null
            capturedTakeoverSpliceBufferStatus = null
            capturedLoopTookOver = null
        }
        override fun onResetOpportunityEventKeys() {
            lastRejectedLogKey = null
            lastEmittedPlanVerdict = null
            lastArmDeathKey = null
            lastFallbackLogKey = null
        }
    }
    }
}
