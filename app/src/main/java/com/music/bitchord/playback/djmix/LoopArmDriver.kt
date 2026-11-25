package com.music.bitchord.playback.djmix

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.settings.AutomixPerformanceMode
import com.music.bitchord.data.settings.SmartAnalysis
import com.music.bitchord.data.settings.TrackAnalysisState
import com.music.bitchord.data.settings.TransitionWindow
import com.music.bitchord.djmix.core.contracts.DjLevel as CoreDjLevel
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.AutoMixVisualFeed
import com.music.bitchord.playback.loopmix.LoopMixPort
import com.music.bitchord.playback.MixDebug
import com.music.bitchord.playback.TransitionFilters
import kotlin.math.roundToInt
import com.music.bitchord.playback.SleepTimer
import com.music.bitchord.playback.TransitionFilterProcessor
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.CountdownStabilizer
import com.music.bitchord.playback.smart.DeckBudget
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhraseMaps
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.SongMaps
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.TransitionPlan
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.deckSpeed
import com.music.bitchord.playback.smart.shouldDeclineToMix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Narrow host seam supplying playback state, analysis, engine components,
 * and callbacks to [LoopArmDriver] and [LoopMixDriver] without exposing controller internals.
 */
internal interface LoopDriverHost {
    val scope: CoroutineScope
    fun nowMs(): Long
    fun active(): ExoPlayer
    fun standby(): ExoPlayer
    val outgoing: ExoPlayer?
    val incoming: ExoPlayer?

    val phaseName: String
    fun isLoopArming(): Boolean
    fun isLoopRunning(): Boolean
    fun isIdle(): Boolean
    fun isTail(): Boolean
    fun setPhaseLoopArming()
    fun setPhaseLoopRunning()

    var smartFadeActive: Boolean
    var handedOff: Boolean
    var loopBuildStartedAtMs: Long
    var blendArmLeadMs: Long
    var loopSuppressedPair: String?
    var loopSuppressedUntil: Long
    var outgoingItemIndex: Int
    var queuedItemCount: Int
    var plannedNextId: String?
    var initiatingExitReason: String?
    var capturedTakeoverSpliceBufferStatus: LoopMixPort.TakeoverSpliceBufferStatus?
    var capturedLoopTookOver: Boolean?
    var lastCompleteReason: String?
    var lastTimingPair: Pair<String, String>?
    var lastFirePath: String
    var lastFireMove: String
    var lastFireFxCount: Int
    var lastFireStems: Int
    var lastFireFallbackReason: String
    val lastPlayWhenReadyChangeReason: Int
    var lastFreezeLogKey: String?
    var lastArmDeathKey: String?
    var lastFallbackLogKey: String?
    var outgoingAnalysis: TrackAnalysis
    var incomingAnalysis: TrackAnalysis
    var lastSmartPlan: TransitionPlan?
    var lastSmartPlanStartMs: Long
    var pinnedCatchUpKey: String?
    var pinnedCatchUpPlan: TransitionPlan?
    var handoverExpectedMs: Long
    var handoverHeardMs: Long
    var handoverTargetMs: Long
    var internalHandoverSeek: Boolean
    var suppressScrubUntilMs: Long
    var inFallbackReplan: Boolean
    var routingFallback: Boolean
    var tailLoopSession: LoopMixSession?
    var tailDurationMs: Long

    val visualGeneration: Long?
    val scrubEpoch: Long
    val activeMachineGeneration: Long
    val pairPreparer: PairPreparer?
    val mixExecutor: MixExecutor
    val tempoEngine: EaseTempoEngine
    val countdownStabilizer: CountdownStabilizer
    val filters: TransitionFilters
    val tracks: TransitionFilters
    val lyricSpans: Map<String, LyricSpans>
    val lyricsAskedAt: Map<String, Long>

    fun loopPortOf(player: ExoPlayer): LoopMixPort?
    fun holdAtEnd(player: ExoPlayer)
    fun armAutoMixVisual(out: ExoPlayer, into: ExoPlayer, nextIndex: Int, items: List<MediaItem>)
    fun cancelAutoMixVisual()
    fun onArmIncoming(item: MediaItem, afterId: String?)
    fun onArmAutoMixIncoming(item: MediaItem)
    fun onBlendHeadroom(headroom: Float)
    fun yieldAnalysis()
    fun handOff(out: ExoPlayer, into: ExoPlayer, m: Double = -1.0, visualEnd: Double = -1.0)
    fun onMixPositionJump(wrapped: Boolean)
    fun finish()
    fun disarmTailLoopSession()
    fun openFiltersWhenSilent()
    fun publishAutomixDetail()
    fun considerSmartTransition(duration: Long)
    fun isNativeRendererAvailable(player: ExoPlayer): Boolean
    fun analysisFor(item: MediaItem): TrackAnalysis
    fun loudnessGain(id: String): Float
    fun loopRegion(item: MediaItem, length: Long, from: Double, to: Double, sampleRate: Int): LoopRegion?
    fun frozenPreparedFor(outItem: MediaItem, inItem: MediaItem, targetGen: Long, sampleRate: Int, djLevel: com.music.bitchord.playback.smart.choreo.DjLevel? = null): PreparedPair?
    fun isStraightRoute(plan: LoopMix.Plan): Boolean

    fun ensureOpportunity(outId: String, inId: String): Opportunity
    val opportunity: Opportunity?
    val planRevision: Int
    fun reviseRoute(plan: Any): Int
    var executedRoute: Map<String, Any?>?
    val terminalRecorded: Boolean
    var nativeBinding: NativeBinding?
    val routingEvidenceKeys: MutableSet<String>
    fun nativeRouteFields(plan: LoopMix.Plan, coarseDecline: Boolean? = null): Map<String, Any?>
    fun nativeSessionRouteFields(session: LoopMixSession, coarseDecline: Boolean? = null): Map<String, Any?>
    fun routeRefusal(
        name: String,
        reason: String,
        stage: String,
        technique: String,
        destination: String? = null,
        context: Opportunity? = null,
        revision: Int = 0,
        generation: Int? = null,
        coarseDecline: Boolean? = null,
    )
    fun preparedRoute(route: Map<String, Any?>, context: Opportunity? = null, revision: Int = 0, generation: Int? = null)
    fun mixEvent(name: String, fields: Map<String, Any?>, context: Opportunity? = null)
}

/**
 * Shared state container for native loopmix arming and playback execution (M9 C-5).
 */
internal interface LoopDriverState {
    val deckTiming: DeckTiming
    var loopSession: LoopMixSession?
    var loopBuildJob: Job?
    var loopAlignJob: Job?

    var primedSession: LoopMixSession?
    var primedKey: String?
    var primeJob: Job?
    var primeRetryAt: Long
    var buildGeneration: Int

    var deckSide: String

    var loopTarget: LoopMix.Plan?
    var loopTargetStartMs: Long
    var loopOutItem: MediaItem?
    var loopInItem: MediaItem?
    var loopOutAnalysis: TrackAnalysis?
    var loopInAnalysis: TrackAnalysis?
    var loopSungOut: List<LoopMix.Span>?
    var loopSungIn: List<LoopMix.Span>?
    var loopOutDurationMs: Long
    var loopInDurationMs: Long

    var straightPreparedPair: PreparedPair?

    var loopPlanKey: String?
    var loopPlanned: LoopMix.Plan?
    var loopScript: PhraseScript?
    var shownCountdownKey: String?
    var shownLoopStartMs: Long
    var watchdogFired: Boolean

    var loopRefusal: String?
    var lastLoopVerdict: String
    var loopIncomingStarted: Boolean
    var deferredHandoffLogged: Boolean
    var lastLoopPositionMs: Long
    var lastLoopAnnounceAt: Long
    var lastLoopBeat: Int

    var loopCleanupContendedSession: LoopMixSession?
    var loopCleanupContendedSinceNanos: Long
}

internal class DefaultLoopDriverState(
    override val deckTiming: DeckTiming,
) : LoopDriverState {
    override var loopSession: LoopMixSession? = null
    override var loopBuildJob: Job? = null
    override var loopAlignJob: Job? = null

    override var primedSession: LoopMixSession? = null
    override var primedKey: String? = null
    override var primeJob: Job? = null
    override var primeRetryAt = 0L
    override var buildGeneration = 0

    override var deckSide: String = "outgoing"

    override var loopTarget: LoopMix.Plan? = null
    override var loopTargetStartMs = 0L
    override var loopOutItem: MediaItem? = null
    override var loopInItem: MediaItem? = null
    override var loopOutAnalysis: TrackAnalysis? = null
    override var loopInAnalysis: TrackAnalysis? = null
    override var loopSungOut: List<LoopMix.Span>? = null
    override var loopSungIn: List<LoopMix.Span>? = null
    override var loopOutDurationMs = 0L
    override var loopInDurationMs = 0L

    override var straightPreparedPair: PreparedPair? = null

    override var loopPlanKey: String? = null
    override var loopPlanned: LoopMix.Plan? = null
    override var loopScript: PhraseScript? = null
    override var shownCountdownKey: String? = null
    override var shownLoopStartMs: Long = 0L
    override var watchdogFired = false

    override var loopRefusal: String? = null
    override var lastLoopVerdict = ""
    override var loopIncomingStarted = false
    override var deferredHandoffLogged = false
    override var lastLoopPositionMs = -1L
    override var lastLoopAnnounceAt = 0L
    override var lastLoopBeat = -1

    override var loopCleanupContendedSession: LoopMixSession? = null
    override var loopCleanupContendedSinceNanos = 0L
}

/**
 * Owns loop pre-flight checks, off-thread background deck priming,
 * build lifecycle, shift/fallback evaluation, and audio sink arming (M9 slice C-5 part 1).
 */
@OptIn(UnstableApi::class)
internal class LoopArmDriver(
    private val host: LoopDriverHost,
    private val state: LoopDriverState,
) {
    var mixDriver: LoopMixDriver? = null

    companion object {
        private const val TAG = "CrossfadeController"
        const val LOOP_COMMIT_MS = 400L
        const val LOOP_PRIME_RETRY_MS = 2_000L
        const val LOOP_SUPPRESSION_MS = 90_000L
    }

    /** Why [current] -> [next] can't be a loop mix right now, or null when it can. */
    fun loopRefusalFor(
        player: ExoPlayer,
        current: MediaItem,
        next: MediaItem,
        analysisState: SmartAnalysis,
        currentAnalysis: TrackAnalysis,
        nextAnalysis: TrackAnalysis,
    ): String? = when {
        !LoopMixRenderer.available -> "no renderer"
        AppSettings.playbackSpeed.value != 1f -> "playback speed is not 1x"
        player.skipSilenceEnabled -> "skip silence is on"
        SleepTimer.afterTrack.value -> "sleep timer"
        !gridsReady(analysisState, currentAnalysis, nextAnalysis) -> "waiting on analysis"
        isSourceBacked(current) || isSourceBacked(next) -> "analysed off another recording"
        host.loopPortOf(player)?.supportsLoopMix != true -> "this output can't take it"
        host.loopPortOf(host.standby()) == null -> "no standby sink"
        else -> null
    }

    /** A track whose analysis reads a matched YouTube recording rather than what plays. */
    private fun isSourceBacked(item: MediaItem): Boolean {
        val uri = item.localConfiguration?.uri ?: return true
        return uri.getQueryParameter("v") == null && uri.authority == "source"
    }

    /**
     * Forgets a loop the lyrics or the vocal mask no longer allow. When it was
     * already arming, the decks come down so this tick can start the next rung.
     */
    fun dropVetoedLoop(pair: String, disarm: Boolean, coarseDecline: Boolean? = null) {
        dropRefusedLoop(pair, disarm, "sung veto", coarseDecline)
    }

    fun dropRefusedLoop(pair: String, disarm: Boolean, reason: String, coarseDecline: Boolean? = null) {
        state.loopPlanned = null
        state.loopScript = null
        state.loopPlanKey = null
        if (disarm) {
            mixDriver?.loopFallBack(reason)
            state.loopRefusal = reason
        } else {
            noteLoopVerdict(pair, null, reason, coarseDecline)
        }
    }

    fun isCrown(plan: LoopMix.Plan): Boolean = plan.outLoops && plan.inLoops

    fun nativeFamily(plan: LoopMix.Plan): CountdownStabilizer.Family =
        if (isCrown(plan)) CountdownStabilizer.Family.LOOP else CountdownStabilizer.Family.NATIVE_BRIDGE

    fun retainedBoundsRefusal(plan: LoopMix.Plan, outDurationMs: Long, inDurationMs: Long): String? = when {
        outDurationMs > 1_000L && plan.out.loopEnd * 1000 > outDurationMs -> "outgoing loop beyond playable duration"
        inDurationMs > 1_000L && plan.inn.loopEnd * 1000 > inDurationMs -> "incoming loop beyond playable duration"
        else -> null
    }

    /** The same immutable geometry admission is used for NEW, KEEP and restoration. */
    fun retainedPlanRefusal(
        plan: LoopMix.Plan,
        out: TrackAnalysis,
        inn: TrackAnalysis,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
    ): String? {
        // Local contradictory evidence is authoritative even on an armed crown.
        if (LoopMix.vetoes(plan, out, inn, sungOut, sungIn)) return "sung veto"
        if (plan.echoTail) {
            plan.handoff?.let { handoff ->
                com.music.bitchord.playback.smart.EchoAdmission.musicalRefusal(handoff, out, inn)
                    ?.let { return it }
            }
        }
        if (isCrown(plan)) {
            val qualification = LoopMix.qualifyCrown(plan)
            if (!qualification.accepted) return qualification.refusal ?: "crown qualification refused"
        }
        return null
    }

    /** True for 1.5 s after lyrics were asked, and only when no vocal mask can decide instead. */
    fun lyricsPending(id: String, analysis: TrackAnalysis): Boolean {
        if (host.lyricSpans.containsKey(id)) return false
        if (analysis.vocalWindows.isNotEmpty()) return false
        val asked = host.lyricsAskedAt[id] ?: return false
        return host.nowMs() - asked < 1_500L
    }

    /** A head and a tail are enough. The whole-track pass can still be running. */
    fun gridsReady(analysisState: SmartAnalysis, out: TrackAnalysis, inn: TrackAnalysis): Boolean {
        fun ready(st: TrackAnalysisState, analysis: TrackAnalysis, tail: Boolean): Boolean {
            if (st == TrackAnalysisState.ANALYSED) return true
            if (st != TrackAnalysisState.REFINING) return false
            val side = if (tail) analysis.tailBeats else analysis.headBeats
            return side != null && side.bpm > 0 && side.confidence >= 0.45
        }
        return ready(analysisState.current, out, tail = true) && ready(analysisState.next, inn, tail = false)
    }

    fun noteLoopVerdict(pair: String, plan: LoopMix.Plan?, refusal: String?, coarseDecline: Boolean? = null) {
        state.loopRefusal = refusal
        if (refusal != null) {
            host.routeRefusal(
                "mix.plan.rejected", refusal, "candidate",
                if (refusal.startsWith("bridge")) "native-bridge" else "crown-loop",
                context = host.opportunity, coarseDecline = coarseDecline,
            )
        }
        if (plan != null) {
            val revision = host.reviseRoute(plan)
            val fields = host.nativeRouteFields(plan, coarseDecline = coarseDecline)
            if (host.routingEvidenceKeys.add("candidate|${fields["technique"]}|$revision")) {
                host.mixEvent("mix.plan", fields + mapOf("stage" to "candidate"))
            }
        }
        val verdict = "$pair: " + (plan?.let { "loop mix at ${"%.2f".format(Locale.ROOT, it.out.loopStart)}s · ${describeLoop(it)}" } ?: "no loop mix ($refusal)")
        if (verdict == state.lastLoopVerdict) return
        state.lastLoopVerdict = verdict
        Log.d(TAG, "loop $verdict")
    }

    fun describeLoop(plan: LoopMix.Plan): String {
        val from = plan.out.bpm.roundToInt()
        val to = plan.targetBpm.roundToInt()
        val tempo = if (from == to) "$from BPM" else "$from → $to BPM over ${plan.rampBars} bars"
        val keys = if (plan.keys.isBlank()) "" else " · ${plan.keys}"
        if (plan.rung != "Loop") return "${plan.rung} · ${plan.totalBars} bars · $tempo$keys"
        val fold = when {
            plan.fold > 1 -> " (next in double time)"
            plan.fold < 1 -> " (next in half time)"
            else -> ""
        }
        fun side(pick: LoopMix.Pick): String {
            val by = when (pick.checkedBy) {
                LoopMix.Check.LYRICS -> "lyrics"
                LoopMix.Check.MASK -> "mask"
                LoopMix.Check.LYRICS_AND_MASK -> "lyrics + mask"
            }
            return "${pick.bars}-bar, clear by $by"
        }
        return "out ${side(plan.out)} · in ${side(plan.inn)} · $tempo$fold$keys"
    }

    /**
     * Starts building the decks as soon as a plan exists, off the playback
     * thread. Arming the sinks still waits for the lead window; decode does not.
     */
    fun primeLoopDecks(
        player: ExoPlayer,
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        nextDurationMs: Long,
    ) {
        val into = host.standby()
        if (player === into) return
        val outPort = host.loopPortOf(player) ?: return
        val sampleRate = outPort.sampleRate
        if (sampleRate <= 0) return
        val key = primeKey(plan, outItem, inItem, sampleRate)
        if (state.primedKey == key && state.primedSession != null) return
        if (state.primeJob?.isActive == true && state.primedKey == key) return
        if (state.primedKey == key && host.nowMs() < state.primeRetryAt) return
        val straight = state.straightPreparedPair?.takeIf {
            it.plan?.let { sp -> ChoreoSessionGlue.isSamePlan(sp, plan) } == true &&
                it.sampleRate == sampleRate &&
                it.outId == outItem.mediaId && it.inId == inItem.mediaId
        }
        discardPrime()
        state.straightPreparedPair = straight
        val lead = (plan.out.loopStart * 1000).roundToLong() - player.currentPosition
        state.deckTiming.deadlineMs = DeckBudget.deadlineMs(host.nowMs(), player.isPlaying, (plan.out.loopStart * 1000).roundToLong(), player.currentPosition)
        Log.d(
            TAG,
            "loop prime ${outItem.mediaId}->${inItem.mediaId} at ${sampleRate}Hz, " +
                "${lead}ms before ${"%.2f".format(Locale.ROOT, plan.out.loopStart)}s",
        )
        startLoopBuild(
            key, plan, player, into, outItem, inItem, durationMs, nextDurationMs, sampleRate,
            armWhenReady = false, preparedOverride = straight,
        )
    }

    /** Counts a classic begin() guard exit (deduped per reason) and returns false. */
    fun armDeath(reason: String): Boolean {
        val key = "$reason|${host.phaseName}"
        if (key != host.lastArmDeathKey) {
            host.lastArmDeathKey = key
            host.routeRefusal("mix.arm.death", reason, "accepted", "classic")
        }
        return false
    }

    fun beginLoop(
        out: ExoPlayer,
        plan: LoopMix.Plan,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        nextDurationMs: Long,
    ): Boolean {
        val into = host.standby()
        fun refuse(reason: String): Boolean {
            val pairKey = "${outItem.mediaId}->${inItem.mediaId}"
            val key = "beginLoop:$reason|$pairKey"
            if (key != host.lastFallbackLogKey) {
                host.lastFallbackLogKey = key
                host.routeRefusal(
                    "mix.loop.fallback", reason, "accepted", host.nativeRouteFields(plan)["technique"] as String, "classic",
                    coarseDecline = state.loopOutAnalysis?.let { o -> state.loopInAnalysis?.let { inn -> shouldDeclineToMix(o, inn) } },
                )
            }
            host.loopSuppressedPair = pairKey
            host.loopSuppressedUntil = host.nowMs() + LOOP_SUPPRESSION_MS
            return false
        }
        if (out === into) return refuse("same-player")
        val nextIndex = out.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return refuse("no-next-index")
        val outPort = host.loopPortOf(out) ?: return refuse("no-out-port")
        val inPort = host.loopPortOf(into) ?: return refuse("no-in-port")
        host.tempoEngine.endEase(clickless = false)
        MixDebug.reset()
        host.setPhaseLoopArming()
        host.smartFadeActive = true
        host.handedOff = false
        host.outgoingItemIndex = out.currentMediaItemIndex
        state.loopTarget = plan
        state.loopTargetStartMs = (plan.out.loopStart * 1000).roundToLong()
        host.holdAtEnd(out)
        val sampleRate = outPort.sampleRate
        val key = primeKey(plan, outItem, inItem, sampleRate)
        val ready = state.primedSession?.takeIf { state.primedKey == key }
        if (ready != null) {
            state.primedSession = null
            state.primedKey = null
            Log.d(TAG, "loop decks were ready ${state.loopTargetStartMs - out.currentPosition}ms ahead")
            armLoop(ready, out, into, nextIndex, outPort, inPort)
            return true
        }
        if (state.primeJob?.isActive == true && state.primedKey == key) {
            if (host.loopBuildStartedAtMs <= 0L) host.loopBuildStartedAtMs = host.nowMs()
            if (state.deckTiming.deadlineMs == DeckBudget.NO_DEADLINE_MS || state.deckTiming.deadlineMs <= 0L) {
                state.deckTiming.deadlineMs = DeckBudget.deadlineMs(host.nowMs(), out.isPlaying, state.loopTargetStartMs, out.currentPosition)
            }
            Log.d(TAG, "loop decks still building ${state.loopTargetStartMs - out.currentPosition}ms ahead")
            return true
        }
        state.deckTiming.clear()
        state.deckTiming.deadlineMs = DeckBudget.deadlineMs(host.nowMs(), out.isPlaying, state.loopTargetStartMs, out.currentPosition)
        val targetGen = host.activeMachineGeneration.takeIf { it > 0L }
            ?: host.pairPreparer?.snapshotFor(outItem.mediaId, inItem.mediaId)?.generation
            ?: 0L
        val straight = if (host.isStraightRoute(plan)) {
            host.frozenPreparedFor(outItem, inItem, targetGen, sampleRate)?.takeIf {
                it.plan?.let { p -> ChoreoSessionGlue.isSamePlan(p, plan) } == true
            } ?: return refuse("straight-prepared-pcm-missing")
        } else null
        host.loopBuildStartedAtMs = host.nowMs()
        startLoopBuild(
            key, plan, out, into, outItem, inItem, durationMs, nextDurationMs, sampleRate,
            armWhenReady = true, preparedOverride = straight,
        )
        return true
    }

    fun startLoopBuild(
        key: String,
        plan: LoopMix.Plan,
        out: ExoPlayer,
        into: ExoPlayer,
        outItem: MediaItem,
        inItem: MediaItem,
        durationMs: Long,
        nextDurationMs: Long,
        sampleRate: Int,
        armWhenReady: Boolean,
        preparedOverride: PreparedPair? = null,
        forceNoStems: Boolean = false,
    ) {
        state.primedKey = key
        val generation = ++state.buildGeneration
        if (armWhenReady && host.loopBuildStartedAtMs <= 0L) host.loopBuildStartedAtMs = host.nowMs()
        val currentDjLevel = AppSettings.automixDjLevel.value
        fun straightContract(pair: PreparedPair?) = pair?.takeIf {
            it.straightRateMode != null &&
                it.plan?.let { sp -> ChoreoSessionGlue.isSamePlan(sp, plan) } == true &&
                it.sampleRate == sampleRate &&
                it.djLevel == currentDjLevel && it.outId == outItem.mediaId && it.inId == inItem.mediaId
        }
        val targetGen = preparedOverride?.generation ?: host.activeMachineGeneration.takeIf { it > 0L }
            ?: host.pairPreparer?.snapshotFor(outItem.mediaId, inItem.mediaId, currentDjLevel)?.generation
            ?: 0L
        val straight = when {
            forceNoStems -> null
            preparedOverride != null -> straightContract(preparedOverride)
            host.isStraightRoute(plan) -> host.frozenPreparedFor(outItem, inItem, targetGen, sampleRate, currentDjLevel)
                ?.let(::straightContract)
            else -> null
        }
        val prepared: PreparedPair? =
            straight ?: preparedOverride ?: host.pairPreparer?.snapshotFor(outItem.mediaId, inItem.mediaId, currentDjLevel)
        if (straight == null && host.isStraightRoute(plan)) {
            state.primedKey = null
            host.routeRefusal(
                "mix.plan.rejected", "straight-prepared-pcm-missing", "prepared",
                host.nativeRouteFields(plan)["technique"] as String,
                context = host.opportunity, revision = host.planRevision, generation = generation,
            )
            state.straightPreparedPair = null
            state.primeRetryAt = host.nowMs() + LOOP_PRIME_RETRY_MS
            host.loopBuildStartedAtMs = 0L
            if (armWhenReady && host.isLoopArming() && host.outgoing === out) {
                mixDriver?.loopFallBack("straight-prepared-pcm-missing")
            }
            return
        }
        host.pairPreparer?.markBuilding(outItem.mediaId, inItem.mediaId)
        val inLevel = (host.loudnessGain(inItem.mediaId) / host.loudnessGain(outItem.mediaId))
            .takeIf { it.isFinite() && it > 0f } ?: 1f
        val highQuality = AppSettings.automixPerformanceMode.value != AutomixPerformanceMode.EFFICIENT
        val buildPair = "${outItem.mediaId}->${inItem.mediaId}"
        val script = if (straight != null) null else state.loopScript?.takeIf {
            (state.loopPlanKey?.startsWith("$buildPair|") == true &&
                state.loopPlanned?.let { lp -> ChoreoSessionGlue.isSamePlan(lp, plan) } == true) ||
                state.loopTarget?.let { lt -> ChoreoSessionGlue.isSamePlan(lt, plan) } == true
        } ?: prepared?.phraseScript
        val effectivePlan = if (straight != null) {
            requireNotNull(straight.plan)
        } else prepared?.plan?.takeIf {
            abs(it.out.loopStart - plan.out.loopStart) < 0.02 &&
                it.outLoops == plan.outLoops &&
                it.inLoops == plan.inLoops
        } ?: plan
        val context = host.ensureOpportunity(outItem.mediaId, inItem.mediaId)
        val revision = host.reviseRoute(effectivePlan)
        val capturedPlan = effectivePlan.copy(
            out = effectivePlan.out.copy(anchorBeats = effectivePlan.out.anchorBeats?.toList()),
            inn = effectivePlan.inn.copy(anchorBeats = effectivePlan.inn.anchorBeats?.toList()),
        )
        val rememberedPair = state.loopOutItem?.mediaId == outItem.mediaId && state.loopInItem?.mediaId == inItem.mediaId
        val capturedOutAnalysis = snapshotLoopAnalysis(
            straight?.outAnalysis
                ?: if (rememberedPair) state.loopOutAnalysis ?: host.analysisFor(outItem) else host.analysisFor(outItem),
        )
        val capturedInAnalysis = snapshotLoopAnalysis(
            straight?.inAnalysis
                ?: if (rememberedPair) state.loopInAnalysis ?: host.analysisFor(inItem) else host.analysisFor(inItem),
        )
        val capturedSungOut = host.lyricSpans[outItem.mediaId]?.spans?.toList()
        val capturedSungIn = host.lyricSpans[inItem.mediaId]?.spans?.toList()
        val capturedCoarseDecline = shouldDeclineToMix(capturedOutAnalysis, capturedInAnalysis)
        val capturedStraight = straight
        val capturedPreparedPrep = prepared?.v2Prep
        val capturedPreparedOutStems = if (forceNoStems) null else prepared?.outStems
        val capturedPreparedInStems = if (forceNoStems) null else prepared?.inStems
        val capturedPreparedScript = if (forceNoStems) null else prepared?.script
        val capturedPreparedOutRegion = prepared?.outRegion
        val capturedPreparedInRegion = prepared?.inRegion
        val job = host.scope.launch {
            val began = host.nowMs()
            var buildFailure: String? = null
            val session = withContext(Dispatchers.Default) {
                runCatching {
                    val straightPair = capturedStraight
                    if (straightPair != null) {
                        LoopMixSession.buildStraight(
                            straightPair, outItem.mediaId, inItem.mediaId, sampleRate, inLevel, highQuality,
                            onRefusal = { reason -> buildFailure = reason },
                        )
                    } else {
                        val v2OutMap = prepared?.outSongMap
                            ?: PhraseMaps.get(outItem.mediaId)
                            ?: runCatching { SongMaps.fromAnalysis(outItem.mediaId, capturedOutAnalysis) }.getOrNull()
                        val v2InMap = prepared?.inSongMap
                            ?: PhraseMaps.get(inItem.mediaId)
                            ?: runCatching { SongMaps.fromAnalysis(inItem.mediaId, capturedInAnalysis) }.getOrNull()
                        val v2AlbumOrder = prepared?.albumOrder ?: runCatching {
                            TransitionMoves.isConsecutiveAlbumPair(
                                outItem.mediaMetadata.albumTitle?.toString().orEmpty(), "", outItem.mediaMetadata.trackNumber ?: 0,
                                inItem.mediaMetadata.albumTitle?.toString().orEmpty(), "", inItem.mediaMetadata.trackNumber ?: 0,
                            )
                        }.getOrDefault(false)
                        LoopMixSession.build(
                            capturedPlan, outItem.mediaId, inItem.mediaId, sampleRate, inLevel, highQuality, script,
                            separateStem = null, separateVocalStem = null, separateVocalPair = null,
                            outAnalysis = capturedOutAnalysis, inAnalysis = capturedInAnalysis,
                            sungOut = capturedSungOut, sungIn = capturedSungIn,
                            onRefusal = { reason -> buildFailure = reason },
                            outSongMap = v2OutMap,
                            inSongMap = v2InMap,
                            djLevel = currentDjLevel,
                            albumOrder = v2AlbumOrder,
                            preparedPrep = capturedPreparedPrep,
                            preparedOutStems = capturedPreparedOutStems,
                            preparedInStems = capturedPreparedInStems,
                            preparedScript = capturedPreparedScript,
                            preparedOutRegion = capturedPreparedOutRegion,
                            preparedInRegion = capturedPreparedInRegion,
                            decode = { side, from, to ->
                                val sideName = if (side == LoopMix.Side.OUT) "outgoing" else "incoming"
                                state.deckSide = sideName
                                val item = if (side == LoopMix.Side.OUT) outItem else inItem
                                val length = if (side == LoopMix.Side.OUT) durationMs else nextDurationMs
                                host.loopRegion(item, length, from, to, sampleRate).also { region ->
                                    if (region == null) buildFailure = "$sideName decode-null"
                                }
                            },
                        )
                    }
                }.onFailure {
                    Log.w(TAG, "loop build failed", it)
                    buildFailure = "session error:${it.javaClass.simpleName}"
                }.getOrNull()
            }
            if (generation != state.buildGeneration || state.primedKey != key || context != host.opportunity || host.terminalRecorded) {
                closeSessionOffThread(session)
                return@launch
            }
            state.deckTiming.endOpen()
            val builtMs = host.nowMs() - began
            if (session != null) {
                host.pairPreparer?.recordTiming(outItem.mediaId, inItem.mediaId, buildMs = builtMs)
            } else {
                host.loopBuildStartedAtMs = 0L
            }
            val steps = state.deckTiming.snapshot(host.nowMs())
                .joinToString { "${it.step} ${it.endedMs - it.startedMs}ms" }
            if (session == null) {
                host.routeRefusal(
                    "mix.plan.rejected", buildFailure ?: "session build-null", "prepared",
                    host.nativeRouteFields(capturedPlan, coarseDecline = capturedCoarseDecline)["technique"] as String,
                    context = context, revision = revision, generation = generation,
                    coarseDecline = capturedCoarseDecline,
                )
                Log.w(TAG, "loop build produced no decks ($steps)")
                state.primeRetryAt = host.nowMs() + LOOP_PRIME_RETRY_MS
                if (host.isLoopArming() && host.outgoing === out) {
                    val now = host.nowMs()
                    val deadline = state.deckTiming.deadlineMs
                    val reason = if (deadline != DeckBudget.NO_DEADLINE_MS && deadline > 0L && now <= deadline) {
                        buildFailure ?: "${state.deckSide.ifEmpty { "deck" }} build-null"
                    } else {
                        lateDeckReason()
                    }
                    mixDriver?.loopFallBack(reason)
                }
                return@launch
            }
            host.blendArmLeadMs = com.music.bitchord.playback.smart.PrepLead.next(host.blendArmLeadMs, builtMs)
            Log.d(TAG, "loop decks built in ${builtMs}ms ($steps), next arm ${host.blendArmLeadMs}ms")
            when {
                host.isLoopArming() && host.outgoing === out && armWhenReady -> {
                    val nextIndex = out.nextMediaItemIndex
                    val outPort = host.loopPortOf(out)
                    val inPort = host.loopPortOf(into)
                    if (nextIndex == C.INDEX_UNSET || outPort == null || inPort == null) {
                        closeSessionOffThread(session)
                        shiftLoopOrFallback(out)
                    } else {
                        state.primedKey = null
                        host.nativeBinding = NativeBinding(context, revision, generation, capturedCoarseDecline)
                        armLoop(session, out, into, nextIndex, outPort, inPort)
                    }
                }
                host.isLoopArming() && host.outgoing === out -> {
                    val nextIndex = out.nextMediaItemIndex
                    val outPort = host.loopPortOf(out)
                    val inPort = host.loopPortOf(into)
                    if (nextIndex == C.INDEX_UNSET || outPort == null || inPort == null) {
                        closeSessionOffThread(session)
                        shiftLoopOrFallback(out)
                    } else {
                        state.primedKey = null
                        host.nativeBinding = NativeBinding(context, revision, generation, capturedCoarseDecline)
                        armLoop(session, out, into, nextIndex, outPort, inPort)
                    }
                }
                host.isIdle() -> {
                    state.primedSession = session
                    host.nativeBinding = NativeBinding(context, revision, generation, capturedCoarseDecline)
                    host.preparedRoute(host.nativeSessionRouteFields(session, capturedCoarseDecline), context, revision, generation)
                }
                else -> closeSessionOffThread(session)
            }
        }
        state.primeJob = job
        if (armWhenReady) state.loopBuildJob = job
    }

    fun rememberLoopPair(
        outItem: MediaItem,
        inItem: MediaItem,
        outAnalysis: TrackAnalysis,
        inAnalysis: TrackAnalysis,
        durationMs: Long,
        nextDurationMs: Long,
    ) {
        state.loopOutItem = outItem
        state.loopInItem = inItem
        host.plannedNextId = inItem.mediaId
        state.loopOutAnalysis = outAnalysis
        state.loopInAnalysis = inAnalysis
        state.loopSungOut = host.lyricSpans[outItem.mediaId]?.spans
        state.loopSungIn = host.lyricSpans[inItem.mediaId]?.spans
        state.loopOutDurationMs = durationMs
        state.loopInDurationMs = nextDurationMs
    }

    fun snapshotLoopAnalysis(analysis: TrackAnalysis): TrackAnalysis {
        fun windows(values: List<com.music.bitchord.playback.smart.CurveWindow>) = values.map {
            com.music.bitchord.playback.smart.CurveWindow(it.startTime, it.frameRate, it.values.copyOf())
        }
        fun beats(value: com.music.bitchord.playback.smart.BeatSide?) = value?.copy(
            beats = value.beats.toList(), downbeats = value.downbeats.toList(),
        )
        return analysis.copy(
            downbeats = analysis.downbeats.toList(), phraseBoundaries = analysis.phraseBoundaries.toList(),
            mixInCandidates = analysis.mixInCandidates.toList(), mixOutCandidates = analysis.mixOutCandidates.toList(),
            energyCurve = analysis.energyCurve.toList(), lowEnergyCurve = analysis.lowEnergyCurve.toList(),
            vocalActivityMask = analysis.vocalActivityMask.toList(),
            headBeats = beats(analysis.headBeats), tailBeats = beats(analysis.tailBeats),
            vocalWindows = windows(analysis.vocalWindows), levelWindows = windows(analysis.levelWindows),
            instrumentalWindows = windows(analysis.instrumentalWindows),
        )
    }

    fun primeKey(plan: LoopMix.Plan, outItem: MediaItem, inItem: MediaItem, sampleRate: Int): String =
        "${plan.rung}|${outItem.mediaId}->${inItem.mediaId}|${plan.out.loopStart}|${plan.out.loopEnd}|${plan.out.bars}|" +
            "${plan.inn.loopStart}|${plan.inn.loopEnd}|${plan.inn.bars}|$sampleRate"

    /** Called from the decode thread at the start of each stream/decode/resample slice. */
    fun markDeckStep(step: String) {
        state.deckTiming.begin("${state.deckSide} $step")
    }

    fun discardPrime() {
        state.buildGeneration++
        state.primeJob?.cancel()
        state.primeJob = null
        state.loopBuildJob = null
        host.loopBuildStartedAtMs = 0L
        state.primedKey = null
        state.primeRetryAt = 0L
        closeSessionOffThread(state.primedSession)
        state.primedSession = null
        state.straightPreparedPair = null
        state.deckTiming.clear()
    }

    fun shiftLoopOrFallback(out: ExoPlayer) {
        if (!host.isLoopArming()) return
        val plan = state.loopTarget ?: return run { mixDriver?.loopFallBack(lateDeckReason()) ?: Unit }
        if (host.isStraightRoute(plan)) {
            return run { mixDriver?.loopFallBack(if (lateDeckReason() != "") lateDeckReason() else "straight-prepared-plan-missed") ?: Unit }
        }
        val outAnalysis = state.loopOutAnalysis ?: return run { mixDriver?.loopFallBack(lateDeckReason()) ?: Unit }
        val inAnalysis = state.loopInAnalysis ?: return run { mixDriver?.loopFallBack(lateDeckReason()) ?: Unit }
        val outItem = state.loopOutItem ?: return run { mixDriver?.loopFallBack(lateDeckReason()) ?: Unit }
        val inItem = state.loopInItem ?: return run { mixDriver?.loopFallBack(lateDeckReason()) ?: Unit }
        val why = StringBuilder()
        val positionSec = out.currentPosition / 1000.0
        var candidate = LoopMix.shiftToNextBar(
            plan, outAnalysis, state.loopOutDurationMs / 1000.0, inAnalysis, state.loopInDurationMs / 1000.0,
            state.loopSungOut, state.loopSungIn, why = why,
        )
        while (candidate != null && candidate.out.loopStart < positionSec - PhraseConfig.PREP_TOLERANCE_MS / 1000.0) {
            val further = LoopMix.shiftToNextBar(
                candidate, outAnalysis, state.loopOutDurationMs / 1000.0, inAnalysis, state.loopInDurationMs / 1000.0,
                state.loopSungOut, state.loopSungIn, why = why,
            )
            if (further == null) {
                candidate = null
                break
            }
            candidate = further
        }
        val now = host.nowMs()
        val deadline = state.deckTiming.deadlineMs
        val notLate = deadline != DeckBudget.NO_DEADLINE_MS && deadline > 0L && now <= deadline
        val reason = if (notLate) "shift-bar-null" else lateDeckReason()
        candidate?.let {
            retainedPlanRefusal(it, outAnalysis, inAnalysis, state.loopSungOut, state.loopSungIn)?.let { refusal ->
                mixDriver?.loopFallBack(refusal)
                return
            }
        }
        if (candidate == null) {
            Log.d(TAG, "loop has no later bar ($why)")
            mixDriver?.loopFallBack(reason)
            return
        }
        Log.d(
            TAG,
            "loop start ${"%.2f".format(Locale.ROOT, plan.out.loopStart)}s missed ($reason); " +
                "shifting to ${"%.2f".format(Locale.ROOT, candidate.out.loopStart)}s",
        )
        state.loopSession?.let { session ->
            session.outPort?.disarm()
            session.inPort?.disarm()
            mixDriver?.closeLater(session.renderer)
        }
        state.loopSession = null
        state.primeJob?.cancel()
        state.primeJob = null
        state.loopBuildJob = null
        state.primedSession?.close()
        state.primedSession = null
        state.primedKey = null
        state.loopPlanned = candidate
        state.loopTarget = candidate
        state.loopTargetStartMs = (candidate.out.loopStart * 1000).roundToLong()
        if (state.loopOutDurationMs > 0L) {
            AppSettings.smartTransitionWindow.value = TransitionWindow(
                start = (state.loopTargetStartMs.toFloat() / state.loopOutDurationMs).coerceIn(0f, 1f),
                end = 1f,
            )
        }
        val into = host.incoming ?: host.standby()
        val sampleRate = host.loopPortOf(out)?.sampleRate ?: 0
        if (sampleRate <= 0) {
            mixDriver?.loopFallBack(reason)
            return
        }
        state.deckTiming.clear()
        state.deckTiming.deadlineMs = DeckBudget.deadlineMs(host.nowMs(), out.isPlaying, state.loopTargetStartMs, out.currentPosition)
        state.loopRefusal = reason
        startLoopBuild(
            primeKey(candidate, outItem, inItem, sampleRate),
            candidate, out, into, outItem, inItem, state.loopOutDurationMs, state.loopInDurationMs, sampleRate,
            armWhenReady = true,
        )
    }

    fun lateDeckReason(): String {
        val now = host.nowMs()
        return LoopMix.lateStepReason(state.deckTiming.snapshot(now), now, state.deckTiming.deadlineMs)
    }

    fun armLoop(
        session: LoopMixSession,
        out: ExoPlayer,
        into: ExoPlayer,
        nextIndex: Int,
        outPort: LoopMixPort,
        inPort: LoopMixPort,
    ) {
        if (!session.claimForAttachment()) {
            mixDriver?.loopFallBack("prepared_session_retired")
            return
        }
        host.initiatingExitReason = null
        host.capturedTakeoverSpliceBufferStatus = null
        host.capturedLoopTookOver = null
        val outItem = state.loopOutItem
        val inItem = state.loopInItem
        if (session.planId.isBlank() && outItem != null && inItem != null) {
            retainedPlanRefusal(
                session.plan, host.analysisFor(outItem), host.analysisFor(inItem),
                host.lyricSpans[outItem.mediaId]?.spans, host.lyricSpans[inItem.mediaId]?.spans,
            )?.let { refusal ->
                session.close()
                mixDriver?.loopFallBack(refusal)
                return
            }
        }
        val items = (0 until out.mediaItemCount).map { out.getMediaItemAt(it) }
        host.queuedItemCount = items.size
        host.armAutoMixVisual(out, into, nextIndex, items)
        into.skipSilenceEnabled = false
        into.repeatMode = out.repeatMode
        into.shuffleModeEnabled = false
        into.setPlaybackSpeed(deckSpeed(AppSettings.playbackSpeed.value, 1.0))
        host.filters.outgoing(TransitionFilterProcessor.OPEN_HZ, TransitionFilterProcessor.OFF_HZ)
        host.filters.outgoingMidDb(0f)
        host.openFiltersWhenSilent()
        into.volume = 1f
        out.volume = 1f
        host.onBlendHeadroom(1f)
        into.setMediaItems(items, nextIndex, (session.inStartTrack * 1000).roundToLong())
        items.getOrNull(nextIndex)?.let { item ->
            val after = into.nextMediaItemIndex
            val afterId = if (after == C.INDEX_UNSET) null else items.getOrNull(after)?.mediaId
            host.onArmIncoming(item, afterId)
        }
        into.playWhenReady = false
        host.mixExecutor.admitCaptureSession(session)
        inPort.armIncoming(LoopMixPort.InArm((session.handoverTrack * 1_000_000).roundToLong()))
        state.deckTiming.begin("incoming prepare")
        into.prepare()
        session.attach(outPort, inPort)
        state.loopSession = session
        host.loopBuildStartedAtMs = 0L
        val armLead = (state.loopTargetStartMs - out.currentPosition).coerceAtLeast(0L)
        host.lastTimingPair = session.outId to session.inId
        host.pairPreparer?.markArmed(session.outId, session.inId, armLeadMs = armLead)
        host.nativeBinding?.let { binding ->
            host.preparedRoute(
                host.nativeSessionRouteFields(session, binding.coarseDecline),
                binding.context, binding.revision, binding.generation,
            )
        }
        state.loopIncomingStarted = false
        state.deferredHandoffLogged = false
        state.lastLoopPositionMs = -1L
        state.lastLoopBeat = -1
        Log.d(
            TAG,
            "loop armed: takeover at ${session.takeoverUs}us (stream offset ${outPort.streamOffsetUs}us), " +
                "incoming from ${"%.3f".format(Locale.ROOT, session.inStartTrack)}s, handover at " +
                "${"%.3f".format(Locale.ROOT, session.handoverTrack)}s, mix ${"%.1f".format(Locale.ROOT, session.clock.elementAt)}s",
        )
        items.getOrNull(nextIndex)?.let(host::onArmAutoMixIncoming)
    }

    /** Waits for the outgoing sink to splice over to the renderer. */
    fun driveLoopArming() {
        if (mixDriver?.rejectLiveCrown() == true) return
        val out = host.outgoing ?: return run { mixDriver?.loopBail("no outgoing player") ?: Unit }
        val mStateArm = host.mixExecutor.currentState()
        val syncPausedArm = mStateArm.userPaused || (mStateArm as? MixState.Armed)?.focusPaused == true
        if (syncPausedArm) {
            out.playWhenReady = false
            host.incoming?.playWhenReady = false
            return
        }
        if (!out.playWhenReady) {
            AutomixTelemetry.event(
                "mix.pause.detected",
                mapOf(
                    "site" to "driveLoopArming",
                    "playWhenReady" to out.playWhenReady,
                    "playbackState" to out.playbackState,
                    "isPlaying" to out.isPlaying,
                    "volume" to out.volume,
                    "changeReason" to host.lastPlayWhenReadyChangeReason,
                    "handedOff" to host.handedOff,
                    "tookOver" to (state.loopSession?.outPort?.tookOver == true),
                    "positionMs" to out.currentPosition,
                    "loopIncomingStarted" to state.loopIncomingStarted,
                    "inStartAtMs" to ((state.loopSession?.inStartAt?.times(1000))?.roundToLong() ?: -1L),
                    "handoffAtMs" to ((state.loopSession?.clock?.handoffAt?.times(1000))?.roundToLong() ?: -1L),
                ),
            )
            mixDriver?.loopBail("paused before the loop")
            return
        }
        val session = state.loopSession
        if (session == null) {
            if (state.primedSession != null && state.loopTarget != null && state.loopOutItem != null && state.loopInItem != null && host.incoming != null) {
                val plan0 = state.loopTarget!!
                val outItem0 = state.loopOutItem!!
                val inItem0 = state.loopInItem!!
                val into0 = host.incoming!!
                val sr0 = host.loopPortOf(out)?.sampleRate ?: 0
                if (sr0 > 0 && state.primedKey == primeKey(plan0, outItem0, inItem0, sr0)) {
                    val ready0 = state.primedSession!!
                    val outPort0 = host.loopPortOf(out)
                    val inPort0 = host.loopPortOf(into0)
                    val nextIdx0 = out.nextMediaItemIndex
                    if (outPort0 != null && inPort0 != null && nextIdx0 != C.INDEX_UNSET) {
                        state.primedSession = null
                        state.primedKey = null
                        armLoop(ready0, out, into0, nextIdx0, outPort0, inPort0)
                        return
                    }
                }
            }
            val building = state.primeJob?.isActive == true
            if (building && out.currentPosition >= state.loopTargetStartMs - LOOP_COMMIT_MS) {
                val key = state.loopPlanKey ?: "loop-arming"
                host.loopSuppressedPair = key.substringBefore("|")
                host.loopSuppressedUntil = host.nowMs() + LOOP_SUPPRESSION_MS
                mixDriver?.loopFallBack("build-missed-splice")
                return
            }
            if (!building && out.currentPosition >= state.loopTargetStartMs - LOOP_COMMIT_MS) {
                shiftLoopOrFallback(out)
                return
            }
            if (!building && host.nowMs() >= state.primeRetryAt) {
                val plan = state.loopTarget
                val outItem = state.loopOutItem
                val inItem = state.loopInItem
                val into = host.incoming
                val sampleRate = host.loopPortOf(out)?.sampleRate ?: 0
                if (plan != null && outItem != null && inItem != null && into != null && sampleRate > 0) {
                    state.primeRetryAt = host.nowMs() + LOOP_PRIME_RETRY_MS
                    startLoopBuild(
                        primeKey(plan, outItem, inItem, sampleRate),
                        plan, out, into, outItem, inItem, state.loopOutDurationMs, state.loopInDurationMs, sampleRate,
                        armWhenReady = true,
                    )
                }
            }
            return
        }
        if (host.incoming?.playbackState == Player.STATE_READY) state.deckTiming.endOpenIf("incoming prepare")
        if (session.outPort?.interrupted == true) {
            mixDriver?.loopBail("the outgoing sink was flushed", settle = false)
            return
        }
        if (session.outPort?.tookOver == true) {
            host.setPhaseLoopRunning()
            AppSettings.smartTransitionWindow.value = null
            AppSettings.smartMixInProgress.value = true
            val binding = host.nativeBinding
            val snap = host.pairPreparer?.snapshotFor(session.outId, session.inId)
            val path = session.executionPath.takeIf { it.isNotBlank() && it != "classic" }
                ?: snap?.timings?.path?.takeIf { it.isNotBlank() } ?: "v2-native"
            val choreoStr = when {
                session.choreoRecipeId.isNotEmpty() -> "${session.choreoRecipeId}:${session.choreoRecipeName}"
                session.choreoMove.isNotEmpty() -> session.choreoMove
                else -> ""
            }
            val fxNames = session.choreoFxNames
            val fxCount = session.choreoFxCount
            val levelName = session.choreoLevel.ifBlank { AppSettings.automixDjLevel.value.name }
            val stemsCount = session.choreoStemsUsed
            val fallback = host.lastFireFallbackReason
            host.lastTimingPair = session.outId to session.inId
            host.lastFirePath = path
            host.lastFireMove = session.choreoMove
            host.lastFireFxCount = fxCount
            host.lastFireStems = stemsCount
            host.lastFireFallbackReason = fallback
            host.pairPreparer?.markRunning(session.outId, session.inId)
            host.pairPreparer?.recordTiming(
                session.outId, session.inId,
                path = path,
                level = levelName,
                move = session.choreoMove,
                fxCount = fxCount,
                stems = stemsCount,
                fallbackReason = fallback,
            )
            host.executedRoute = host.nativeSessionRouteFields(session, binding?.coarseDecline)
            host.mixEvent(
                "mix.fire",
                requireNotNull(host.executedRoute) + mapOf(
                    "stage" to "executed", "executionMarker" to "native-takeover-observed",
                    "executionScope" to "takeover-only-not-delegate-buffer-or-listening-proof",
                    "planRevision" to binding?.revision, "buildGeneration" to binding?.generation,
                    "mode" to "loop",
                    "path" to path,
                    "engine" to "v2-native",
                    "choreo" to choreoStr,
                    "fx" to fxNames.joinToString(","),
                    "fxCount" to fxCount,
                    "level" to levelName,
                    "stems" to stemsCount,
                    "fallbackReason" to fallback,
                    "plannedStartMs" to state.loopTargetStartMs,
                    "actualStartMs" to out.currentPosition,
                    "deltaMs" to (out.currentPosition - state.loopTargetStartMs),
                    "prerollSec" to 1.0,
                    "spliceTargetMs" to state.loopTargetStartMs,
                    "trackIndex" to host.outgoingItemIndex,
                ), binding?.context ?: host.opportunity,
            )
            host.scope.launch(Dispatchers.Default) {
                session.outPort?.spliceLag()?.let { (lag, score) ->
                    Log.d(
                        TAG,
                        "loop splice: deck ${"%+.2f".format(Locale.ROOT, lag * 1000.0 / session.sampleRate)}ms " +
                            "against its song (r=${"%.3f".format(Locale.ROOT, score)})",
                    )
                }
            }
            return
        }
        val m = session.mixSeconds(System.nanoTime(), out.isPlaying) ?: return
        if (m > 0.45) {
            val now = host.nowMs()
            state.deckTiming.addClosed("outgoing splice", now, now, state.deckTiming.deadlineMs)
            shiftLoopOrFallback(out)
        }
    }

    fun closeSessionOffThread(session: LoopMixSession?) {
        // Native destruction is asynchronous; do not enqueue ownership transfer on a dying scope.
        session?.close()
    }
}
