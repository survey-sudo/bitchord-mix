package com.music.bitchord.playback.djmix

import android.os.Process
import android.os.SystemClock
import androidx.media3.common.MediaItem
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.PhraseConfig
import com.music.bitchord.playback.smart.PhraseMaps
import com.music.bitchord.playback.smart.PhrasePlanner
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.SongMaps
import com.music.bitchord.playback.smart.StemSet
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TransitionMoves
import com.music.bitchord.playback.smart.camelotCode
import com.music.bitchord.playback.smart.choreo.AutomixV2Choreo
import com.music.bitchord.playback.smart.choreo.Candidate
import com.music.bitchord.playback.smart.choreo.ChoreoCaps
import com.music.bitchord.playback.smart.choreo.ChoreoInputs
import com.music.bitchord.playback.smart.choreo.ChoreoRendererBridge
import com.music.bitchord.playback.smart.choreo.ChoreoScript
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.choreo.Choreographer
import com.music.bitchord.playback.smart.choreo.DeckTarget
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.choreo.RecipeId
import com.music.bitchord.playback.smart.choreo.Recipes
import com.music.bitchord.playback.smart.choreo.StemAdapter
import com.music.bitchord.playback.smart.choreo.StemView
import com.music.bitchord.playback.smart.choreo.TrackClock
import com.music.bitchord.playback.smart.entryKeyConfidence
import com.music.bitchord.playback.smart.entryKeyForMix
import com.music.bitchord.playback.smart.exitKeyConfidence
import com.music.bitchord.playback.smart.exitKeyForMix
import com.music.bitchord.playback.smart.regionalCamelotSteps
import com.music.bitchord.playback.smart.shouldDeclineToMix
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.abs

/**
 * Lifecycle state machine for background preparation of an upcoming `(outId, inId)` pair.
 */
enum class PrepState {
    IDLE,
    ANALYZING,
    PLANNED,
    STEMMING,
    PREPARED,
    BUILDING,
    ARMED,
    RUNNING,
    DONE,
}

/**
 * Per-transition preparation & execution timing record emitted as `mix.timing` telemetry
 * and exposed for the Mix Inspector.
 */
data class PairTimings(
    val outId: String = "",
    val inId: String = "",
    val prepStartMs: Long = 0L,
    val analysisMs: Long = 0L,
    val planMs: Long = 0L,
    val stemMsOut: Long = 0L,
    val stemMsIn: Long = 0L,
    val preparedLeadSec: Double = 0.0,
    val buildMs: Long = 0L,
    val armLeadMs: Long = 0L,
    val watchdog: Boolean = false,
    val path: String = "",
    val level: String = "",
    val move: String = "",
    val fxCount: Int = 0,
    val stems: Int = 0,
    val fallbackReason: String = "",
) {
    fun toTelemetryMap(): Map<String, Any?> = mapOf(
        "outId" to outId,
        "inId" to inId,
        "prepStartMs" to prepStartMs,
        "analysisMs" to analysisMs,
        "planMs" to planMs,
        "stemMsOut" to stemMsOut,
        "stemMsIn" to stemMsIn,
        "preparedLeadSec" to preparedLeadSec,
        "buildMs" to buildMs,
        "armLeadMs" to armLeadMs,
        "watchdog" to watchdog,
        "path" to path,
        "level" to level,
        "move" to move,
        "fxCount" to fxCount,
        "stems" to stems,
        "fallbackReason" to fallbackReason,
    )
}

data class PrepTrack(
    val id: String,
    val durationSec: Double,
    val albumTitle: String = "",
    val trackNumber: Int = 0,
    val albumArtist: String = "",
    val mediaItem: MediaItem? = null,
    val uri: Any? = null,
)

data class PrepRequest(
    val outTrack: PrepTrack,
    val inTrack: PrepTrack,
    val djLevel: DjLevel,
    val positionSec: Double,
    val sampleRate: Int = 48_000,
    val albumOrder: Boolean = false,
)

data class PlanInput(
    val outTrack: PrepTrack,
    val inTrack: PrepTrack,
    val outAnalysis: TrackAnalysis,
    val inAnalysis: TrackAnalysis,
    val outSongMap: SongMap?,
    val inSongMap: SongMap?,
    val sungOut: List<LoopMix.Span>?,
    val sungIn: List<LoopMix.Span>?,
    val djLevel: DjLevel,
    val sampleRate: Int,
    val positionSec: Double,
    val albumOrder: Boolean,
    val stemsExpected: Boolean,
)

data class SelectedPlan(
    val plan: LoopMix.Plan?,
    val phraseScript: PhraseScript? = null,
    val v2Prep: ChoreoSessionGlue.Prep? = null,
    val exitWindowSec: ClosedFloatingPointRange<Double>? = null,
    val entryCueSec: Double? = null,
    val pathHint: String = "v2-native",
    val fallbackReason: String = "",
)

fun interface TransitionPlanSelector {
    fun select(input: PlanInput): SelectedPlan?
}

/**
 * Default pass-1 planner combining [LoopMix.plan], [PhrasePlanner], and [LoopMix.bridge], with
 * an optional [straightFallback] slot for Step 4 (`buildStraight`) when `planLoop == null`.
 */
class DefaultTransitionPlanSelector(
    private val straightFallback: ((PlanInput) -> SelectedPlan?)? = null,
) : TransitionPlanSelector {
    override fun select(input: PlanInput): SelectedPlan? {
        val outA = input.outAnalysis
        val inA = input.inAnalysis
        val outDur = input.outTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: outA.duration
        val inDur = input.inTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: inA.duration
        val loop = LoopMix.plan(
            out = outA,
            outDuration = outDur,
            inn = inA,
            inDuration = inDur,
            earliestOutSec = input.positionSec + 1.0,
            sungOut = input.sungOut,
            sungIn = input.sungIn,
            playableOutDurationSec = outDur,
        )?.let { crown ->
            if (crown.outLoops && crown.inLoops && PhraseConfig.PHRASE_PLANNER_ENABLED) {
                runCatching {
                    val choice = PhrasePlanner.select(
                        input.outSongMap ?: SongMaps.fromAnalysis(input.outTrack.id, outA),
                        input.inSongMap ?: SongMaps.fromAnalysis(input.inTrack.id, inA),
                        regionalCamelotSteps(outA, inA),
                        outA.bpm,
                        inA.bpm,
                        legacyLoop = crown,
                        playableOutDurationSec = outDur,
                        outAnalysis = outA,
                        inAnalysis = inA,
                    )
                    val kept = choice?.legacyLoop
                    if (kept != null && kept.out.bars >= crown.out.bars && kept.inn.bars >= crown.inn.bars &&
                        kept.outLoops && kept.inLoops
                    ) kept else crown
                }.getOrDefault(crown)
            } else crown
        }

        if (loop != null) {
            val stamped = stamp(loop, outA, inA)
            val prep = ChoreoSessionGlue.prepare(
                plan = stamped,
                script = null,
                outAnalysis = outA,
                inAnalysis = inA,
                outSongMap = input.outSongMap,
                inSongMap = input.inSongMap,
                level = input.djLevel,
                outId = input.outTrack.id,
                inId = input.inTrack.id,
                sampleRate = input.sampleRate,
                albumOrder = input.albumOrder,
                stemsExpected = input.stemsExpected,
            )
            return SelectedPlan(
                plan = prep?.plan ?: stamped,
                phraseScript = null,
                v2Prep = prep,
                pathHint = "v2-native",
            )
        }

        val steps = regionalCamelotSteps(outA, inA)
        val phraseChoice = if (PhraseConfig.PHRASE_PLANNER_ENABLED && !shouldDeclineToMix(outA, inA)) {
            runCatching {
                PhrasePlanner.select(
                    input.outSongMap,
                    input.inSongMap,
                    steps,
                    outA.bpm,
                    inA.bpm,
                    earliestExitSec = input.positionSec,
                    playableOutDurationSec = outDur,
                    outAnalysis = outA,
                    inAnalysis = inA,
                )
            }.getOrNull()
        } else null

        if (phraseChoice != null) {
            val script = TransitionMoves.compile(
                phraseChoice, outA.bpm, inA.bpm,
                outgoingId = input.outTrack.id,
                incomingId = input.inTrack.id,
            )
            val stamped = stamp(script.plan, outA, inA)
            val prep = ChoreoSessionGlue.prepare(
                plan = stamped,
                script = script,
                outAnalysis = outA,
                inAnalysis = inA,
                outSongMap = input.outSongMap,
                inSongMap = input.inSongMap,
                level = input.djLevel,
                outId = input.outTrack.id,
                inId = input.inTrack.id,
                sampleRate = input.sampleRate,
                albumOrder = input.albumOrder,
                stemsExpected = input.stemsExpected,
            )
            return SelectedPlan(
                plan = prep?.plan ?: stamped,
                phraseScript = script,
                v2Prep = prep,
                pathHint = "v2-native",
            )
        }

        // Step 4 slot: when planLoop/phrase are null, straight planning runs before bridge/fallback.
        straightFallback?.invoke(input)?.let { return it }

        val bridge = LoopMix.bridge(
            out = outA,
            outDuration = outDur,
            inn = inA,
            inDuration = inDur,
            earliestOutSec = input.positionSec + 1.0,
            sungOut = input.sungOut,
            sungIn = input.sungIn,
            playableOutDurationSec = outDur,
        ) ?: return null

        val stamped = stamp(bridge, outA, inA)
        val prep = ChoreoSessionGlue.prepare(
            plan = stamped,
            script = null,
            outAnalysis = outA,
            inAnalysis = inA,
            outSongMap = input.outSongMap,
            inSongMap = input.inSongMap,
            level = input.djLevel,
            outId = input.outTrack.id,
            inId = input.inTrack.id,
            sampleRate = input.sampleRate,
            albumOrder = input.albumOrder,
            stemsExpected = input.stemsExpected,
        )
        return SelectedPlan(
            plan = prep?.plan ?: stamped,
            phraseScript = null,
            v2Prep = prep,
            pathHint = "v2-native",
        )
    }

    private fun stamp(plan: LoopMix.Plan, out: TrackAnalysis, inn: TrackAnalysis): LoopMix.Plan {
        val steps = regionalCamelotSteps(out, inn)
        val left = camelotCode(exitKeyForMix(out))
        val right = camelotCode(entryKeyForMix(inn))
        val keys = if (left != null && right != null) "$left→$right" else plan.keys
        val outConf = exitKeyConfidence(out)
        val inConf = entryKeyConfidence(inn)
        val keyClash = steps != null && steps > 2 && outConf >= 0.5 && inConf >= 0.5
        val clash = plan.clash || (plan.rung != "Echo" && keyClash)
        val carve = plan.carve || (plan.rung == "Beat blend" && keyClash)
        return plan.copy(clash = clash, carve = carve, keys = keys)
    }
}

data class PreparedPair(
    val outId: String,
    val inId: String,
    val djLevel: DjLevel,
    val state: PrepState,
    val plan: LoopMix.Plan? = null,
    val phraseScript: PhraseScript? = null,
    val v2Prep: ChoreoSessionGlue.Prep? = null,
    val script: ChoreoScript? = null,
    val topCandidates: List<Candidate> = emptyList(),
    val outStems: StemSet? = null,
    val inStems: StemSet? = null,
    val outRegion: LoopRegion? = null,
    val inRegion: LoopRegion? = null,
    val sampleRate: Int = 48_000,
    val outAnalysis: TrackAnalysis? = null,
    val inAnalysis: TrackAnalysis? = null,
    val outSongMap: SongMap? = null,
    val inSongMap: SongMap? = null,
    val sungOut: List<LoopMix.Span>? = null,
    val sungIn: List<LoopMix.Span>? = null,
    val albumOrder: Boolean = false,
    val timings: PairTimings = PairTimings(outId = outId, inId = inId, level = djLevel.name),
    val preparedAtMs: Long = 0L,
    val plannedWindowStartSec: Double = 0.0,
    val plannedWindowEndSec: Double = 0.0,
    val entryCueSec: Double = 0.0,
    val generation: Long = 0L,
    val analysisSignature: String = "",
)

/**
 * Fork-owned background pipeline that prepares `(outId, inId)` well before the splice:
 * analysis readiness -> pass-1 planning + padded decode -> overlap-window stem separation ->
 * pass-2 choreography + artwork prefetch.
 *
 * Runs strictly on its own dedicated single-thread `"djmix-prep"` executor at
 * `Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE` (never on
 * `TrackAnalyzer.executor`).
 */
class PairPreparer(
    private val scope: CoroutineScope,
    private val analysisFor: (String) -> TrackAnalysis,
    private val analysisUpdates: SharedFlow<String>,
    private val analysisRunningFor: (String) -> Boolean = { false },
    private val songMapFor: (String, TrackAnalysis) -> SongMap? = { id, analysis ->
        PhraseMaps.get(id) ?: runCatching { SongMaps.fromAnalysis(id, analysis) }.getOrNull()
    },
    private val sungFor: (String) -> List<LoopMix.Span>? = { null },
    private val canUseStems: () -> Boolean = { AutomixV2Choreo.useStems },
    private val planSelector: TransitionPlanSelector,
    private val decodeRegion: (track: PrepTrack, fromSec: Double, toSec: Double, sampleRate: Int) -> LoopRegion?,
    private val separateStems: (
        trackId: String,
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        regionStartSec: Double,
        shouldAbort: () -> Boolean,
    ) -> StemSet?,
    private val stemLock: Any = Any(),
    private val requestAnalysis: (PrepTrack) -> Unit = {},
    private val onArtworkPrefetch: (MediaItem) -> Unit = {},
    private val capabilities: () -> Int = { runCatching { ChoreoRendererBridge.capabilities() }.getOrDefault(0) },
    private val positionSecProvider: () -> Double = { 0.0 },
    private val nowMs: () -> Long = {
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.nanoTime() / 1_000_000L }
    },
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val telemetryEvent: (String, Map<String, Any?>) -> Unit = { name, fields ->
        AutomixTelemetry.event(name, fields)
    },
    private val analysisWaitTimeoutMs: Long = DEFAULT_ANALYSIS_WAIT_MS,
    private val fullPreferredWaitMs: Long = DEFAULT_FULL_PREFERRED_WAIT_MS,
    private val executor: ExecutorService = newPrepExecutor(),
    private val ownsExecutor: Boolean = true,
) {
    private val _state = MutableStateFlow<PreparedPair?>(null)
    val state: StateFlow<PreparedPair?> = _state.asStateFlow()

    private val generation = AtomicLong(0L)
    private val waitLock = ReentrantLock()
    private val waitCondition = waitLock.newCondition()

    @Volatile private var collectorJob: Job? = null
    @Volatile private var activeRequest: PrepRequest? = null
    @Volatile private var activeGeneration: Long = 0L
    @Volatile private var released = false

    init {
        collectorJob = scope.launch {
            analysisUpdates.collect { trackId ->
                if (trackId.isBlank() || released) return@collect
                onAnalysisUpdated(trackId)
            }
        }
    }

    /** Trigger preparation when next track is known (track start, queue edit, skip, seek past window, remix). */
    fun trigger(request: PrepRequest, force: Boolean = false) {
        if (released) return
        val outId = request.outTrack.id
        val inId = request.inTrack.id
        if (outId.isBlank() || inId.isBlank() || outId == inId) return

        val current = _state.value
        if (!force && shouldDeduplicate(current, activeRequest, request)) {
            return
        }

        val gen = generation.incrementAndGet()
        activeRequest = request
        activeGeneration = gen
        signalWaiters()

        val initialTimings = PairTimings(
            outId = outId,
            inId = inId,
            prepStartMs = nowMs(),
            level = request.djLevel.name,
        )
        _state.value = PreparedPair(
            outId = outId,
            inId = inId,
            djLevel = request.djLevel,
            state = PrepState.ANALYZING,
            sampleRate = request.sampleRate,
            albumOrder = request.albumOrder,
            timings = initialTimings,
            generation = gen,
        )

        runCatching {
            executor.execute {
                executePipeline(gen, request, initialTimings)
            }
        }
    }

    fun onTrackStart(request: PrepRequest) = trigger(request, force = false)

    fun onQueueChange(request: PrepRequest) = trigger(request, force = false)

    fun onSeek(request: PrepRequest) {
        val current = _state.value
        val soughtPastWindow = current != null &&
            current.outId == request.outTrack.id &&
            current.inId == request.inTrack.id &&
            current.plannedWindowStartSec > 0.0 &&
            request.positionSec > current.plannedWindowStartSec + 0.5 &&
            current.state !in ACTIVE_EXECUTION_STATES
        trigger(request, force = soughtPastWindow)
    }

    fun onRemixRequested(request: PrepRequest) = trigger(request, force = true)

    fun onAnalysisUpdated(trackId: String) {
        if (released) return
        signalWaiters()
        val req = activeRequest ?: return
        if (trackId != req.outTrack.id && trackId != req.inTrack.id) return
        val current = _state.value ?: return
        if (current.state in ACTIVE_EXECUTION_STATES || current.state == PrepState.ANALYZING) return
        val outA = analysisFor(req.outTrack.id)
        val inA = analysisFor(req.inTrack.id)
        val sig = analysisSignature(outA, inA)
        if (sig != current.analysisSignature && outA.isUsable && inA.isUsable) {
            trigger(req.copy(positionSec = positionSecProvider()), force = true)
        }
    }

    fun snapshotFor(outId: String, inId: String, djLevel: DjLevel? = null): PreparedPair? =
        _state.value?.takeIf {
            it.outId == outId && it.inId == inId && (djLevel == null || it.djLevel == djLevel)
        }

    fun markBuilding(outId: String, inId: String) {
        updateIfPair(outId, inId) { cur ->
            val nextState = if (cur.state.ordinal < PrepState.BUILDING.ordinal || cur.state == PrepState.BUILDING) {
                PrepState.BUILDING
            } else cur.state
            cur.copy(state = nextState)
        }
    }

    fun markArmed(outId: String, inId: String, armLeadMs: Long = 0L) {
        updateIfPair(outId, inId) { cur ->
            val lead = if (armLeadMs > 0L) armLeadMs else cur.timings.armLeadMs
            cur.copy(
                state = PrepState.ARMED,
                timings = cur.timings.copy(armLeadMs = lead),
            )
        }
    }

    fun markRunning(outId: String, inId: String) {
        updateIfPair(outId, inId) { cur ->
            cur.copy(state = PrepState.RUNNING)
        }
    }

    fun markDone(outId: String, inId: String) {
        updateIfPair(outId, inId) { cur ->
            cur.copy(state = PrepState.DONE)
        }
    }

    fun recordTiming(
        outId: String,
        inId: String,
        buildMs: Long? = null,
        armLeadMs: Long? = null,
        path: String? = null,
        level: String? = null,
        move: String? = null,
        fxCount: Int? = null,
        stems: Int? = null,
        fallbackReason: String? = null,
        watchdog: Boolean? = null,
    ): PairTimings {
        val updated = updateIfPair(outId, inId) { cur ->
            cur.copy(
                timings = cur.timings.copy(
                    buildMs = buildMs ?: cur.timings.buildMs,
                    armLeadMs = armLeadMs ?: cur.timings.armLeadMs,
                    path = path ?: cur.timings.path,
                    level = level ?: cur.timings.level,
                    move = move ?: cur.timings.move,
                    fxCount = fxCount ?: cur.timings.fxCount,
                    stems = stems ?: cur.timings.stems,
                    fallbackReason = fallbackReason ?: cur.timings.fallbackReason,
                    watchdog = watchdog ?: cur.timings.watchdog,
                ),
            )
        }
        return updated?.timings ?: PairTimings(
            outId = outId,
            inId = inId,
            buildMs = buildMs ?: 0L,
            armLeadMs = armLeadMs ?: 0L,
            path = path.orEmpty(),
            level = level.orEmpty(),
            move = move.orEmpty(),
            fxCount = fxCount ?: 0,
            stems = stems ?: 0,
            fallbackReason = fallbackReason.orEmpty(),
            watchdog = watchdog ?: false,
        )
    }

    fun recordWatchdog(
        outId: String,
        inId: String,
        reason: String,
        path: String? = null,
        fallbackReason: String = reason,
    ) {
        updateIfPair(outId, inId) { cur ->
            cur.copy(
                timings = cur.timings.copy(
                    watchdog = true,
                    path = path ?: cur.timings.path.ifBlank { "classic" },
                    fallbackReason = fallbackReason,
                ),
            )
        }
    }

    fun emitTiming(timings: PairTimings) {
        telemetryEvent("mix.timing", timings.toTelemetryMap())
    }

    fun invalidate(reason: String = "") {
        generation.incrementAndGet()
        activeRequest = null
        signalWaiters()
        val cur = _state.value
        if (cur != null && cur.state != PrepState.IDLE) {
            _state.value = cur.copy(
                state = PrepState.IDLE,
                outStems = null,
                inStems = null,
                outRegion = null,
                inRegion = null,
                timings = if (reason.isNotEmpty()) cur.timings.copy(fallbackReason = reason) else cur.timings,
            )
        }
    }

    fun release() {
        released = true
        generation.incrementAndGet()
        activeRequest = null
        signalWaiters()
        collectorJob?.cancel()
        collectorJob = null
        _state.value = null
        if (ownsExecutor) {
            runCatching { executor.shutdownNow() }
        }
    }

    private fun shouldDeduplicate(
        current: PreparedPair?,
        prevRequest: PrepRequest?,
        nextRequest: PrepRequest,
    ): Boolean {
        if (current == null || prevRequest == null) return false
        if (current.outId != nextRequest.outTrack.id || current.inId != nextRequest.inTrack.id) return false
        if (current.djLevel != nextRequest.djLevel) return false
        if (current.state in ACTIVE_EXECUTION_STATES) return true
        // Seek past the planned exit window must re-plan.
        if (current.plannedWindowStartSec > 0.0 && nextRequest.positionSec > current.plannedWindowStartSec + 0.5) {
            return false
        }
        return when (current.state) {
            PrepState.ANALYZING, PrepState.PLANNED, PrepState.STEMMING -> true
            PrepState.PREPARED -> {
                val sig = analysisSignature(
                    analysisFor(nextRequest.outTrack.id),
                    analysisFor(nextRequest.inTrack.id),
                )
                sig == current.analysisSignature
            }
            PrepState.IDLE, PrepState.DONE -> false
            else -> true
        }
    }

    private fun updateIfPair(
        outId: String,
        inId: String,
        transform: (PreparedPair) -> PreparedPair,
    ): PreparedPair? {
        while (true) {
            val cur = _state.value ?: return null
            if (cur.outId != outId || cur.inId != inId) return null
            val next = transform(cur)
            if (_state.compareAndSet(cur, next)) return next
        }
    }

    private fun executePipeline(gen: Long, request: PrepRequest, initialTimings: PairTimings) {
        fun isAborted(): Boolean = released || generation.get() != gen

        runCatching {
            if (isAborted()) return

            // --- Stage 1: ANALYZING ---
            requestAnalysis(request.outTrack)
            requestAnalysis(request.inTrack)

            val analysisReady = awaitAnalysis(gen, request, ::isAborted)
            if (isAborted()) return
            val analysisMs = (nowMs() - initialTimings.prepStartMs).coerceAtLeast(0L)
            if (analysisReady == null) {
                val cur = _state.value
                if (cur != null && cur.generation == gen) {
                    _state.value = cur.copy(
                        state = PrepState.IDLE,
                        timings = initialTimings.copy(
                            analysisMs = analysisMs,
                            fallbackReason = "analysis-unavailable",
                        ),
                    )
                }
                return
            }
            val (outA, inA, outMap, inMap) = analysisReady
            val sig = analysisSignature(outA, inA)
            val sungOut = sungFor(request.outTrack.id)
            val sungIn = sungFor(request.inTrack.id)

            // --- Stage 2: PLANNED ---
            val planStartMs = nowMs()
            val stemsAllowed = canUseStems() && AutomixV2Choreo.useStems &&
                ChoreoCaps.has(capabilities(), ChoreoCaps.STEMS)
            val selected = planSelector.select(
                PlanInput(
                    outTrack = request.outTrack,
                    inTrack = request.inTrack,
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = outMap,
                    inSongMap = inMap,
                    sungOut = sungOut,
                    sungIn = sungIn,
                    djLevel = request.djLevel,
                    sampleRate = request.sampleRate,
                    positionSec = request.positionSec,
                    albumOrder = request.albumOrder,
                    stemsExpected = stemsAllowed,
                ),
            )
            val planMs = (nowMs() - planStartMs).coerceAtLeast(0L)
            if (isAborted()) return

            if (selected == null || (selected.plan == null && selected.exitWindowSec == null)) {
                val reason = selected?.fallbackReason?.ifBlank { "no-v2-plan" } ?: "no-v2-plan"
                publishIfCurrent(gen) { cur ->
                    cur.copy(
                        state = PrepState.IDLE,
                        outAnalysis = outA,
                        inAnalysis = inA,
                        outSongMap = outMap,
                        inSongMap = inMap,
                        sungOut = sungOut,
                        sungIn = sungIn,
                        analysisSignature = sig,
                        timings = cur.timings.copy(
                            analysisMs = analysisMs,
                            planMs = planMs,
                            path = selected?.pathHint.orEmpty(),
                            fallbackReason = reason,
                        ),
                    )
                }
                return
            }

            val v2Prep = selected.v2Prep ?: selected.plan?.let { plan ->
                ChoreoSessionGlue.prepare(
                    plan = plan,
                    script = selected.phraseScript,
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = outMap,
                    inSongMap = inMap,
                    level = request.djLevel,
                    outId = request.outTrack.id,
                    inId = request.inTrack.id,
                    sampleRate = request.sampleRate,
                    albumOrder = request.albumOrder,
                    stemsExpected = stemsAllowed,
                )
            }
            val effectivePlan = v2Prep?.plan ?: selected.plan
            val windowStartSec = effectivePlan?.out?.loopStart
                ?: selected.exitWindowSec?.start
                ?: 0.0
            val windowEndSec = effectivePlan?.let {
                val c = LoopMix.Clock(it)
                it.out.loopStart + c.releaseAt + it.exitTailSeconds
            } ?: selected.exitWindowSec?.endInclusive ?: (windowStartSec + 8.0)
            val entryCueSec = effectivePlan?.inn?.loopStart ?: selected.entryCueSec ?: 0.0

            val plannedPair = PreparedPair(
                outId = request.outTrack.id,
                inId = request.inTrack.id,
                djLevel = request.djLevel,
                state = PrepState.PLANNED,
                plan = effectivePlan,
                phraseScript = selected.phraseScript,
                v2Prep = v2Prep,
                sampleRate = request.sampleRate,
                outAnalysis = outA,
                inAnalysis = inA,
                outSongMap = outMap,
                inSongMap = inMap,
                sungOut = sungOut,
                sungIn = sungIn,
                albumOrder = request.albumOrder,
                timings = initialTimings.copy(
                    analysisMs = analysisMs,
                    planMs = planMs,
                    path = selected.pathHint,
                ),
                plannedWindowStartSec = windowStartSec,
                plannedWindowEndSec = windowEndSec,
                entryCueSec = entryCueSec,
                generation = gen,
                analysisSignature = sig,
            )
            publishIfCurrent(gen) { plannedPair }

            val (outBounds, inBounds) = computeDecodeWindows(
                effectivePlan = effectivePlan,
                exitWindowSec = selected.exitWindowSec,
                entryCueSec = entryCueSec,
                outDurationSec = request.outTrack.durationSec.takeIf { it > 0.0 } ?: outA.duration,
                inDurationSec = request.inTrack.durationSec.takeIf { it > 0.0 } ?: inA.duration,
                outAnalysis = outA,
                inAnalysis = inA,
                sampleRate = request.sampleRate,
            )

            val outRegion = decodeRegion(request.outTrack, outBounds.first, outBounds.second, request.sampleRate)
            if (isAborted()) return
            val inRegion = decodeRegion(request.inTrack, inBounds.first, inBounds.second, request.sampleRate)
            if (isAborted()) return

            // --- Stage 3: STEMMING ---
            publishIfCurrent(gen) { cur ->
                cur.copy(
                    state = PrepState.STEMMING,
                    outRegion = outRegion,
                    inRegion = inRegion,
                )
            }

            var outStems: StemSet? = null
            var inStems: StemSet? = null
            var stemMsOut = 0L
            var stemMsIn = 0L

            if (stemsAllowed && (outRegion != null || inRegion != null)) {
                val (needOutStems, needInStems) = requiredStemSides(
                    effectivePlan = effectivePlan,
                    v2Prep = v2Prep,
                    outA = outA,
                    inA = inA,
                    sungOut = sungOut,
                    sungIn = sungIn,
                    sampleRate = request.sampleRate,
                    caps = v2Prep?.caps ?: capabilities(),
                    level = request.djLevel,
                    outId = request.outTrack.id,
                    inId = request.inTrack.id,
                    albumOrder = request.albumOrder,
                )
                if (needOutStems && outRegion != null &&
                    outRegion.left.size / request.sampleRate.toDouble() <= AutomixV2Choreo.MAX_STEM_REGION_SEC
                ) {
                    val t0 = nowMs()
                    outStems = synchronized(stemLock) {
                        if (isAborted()) null
                        else separateStems(
                            request.outTrack.id,
                            outRegion.left,
                            outRegion.right,
                            request.sampleRate,
                            outRegion.start,
                            ::isAborted,
                        )
                    }?.takeIf { StemAdapter.matchesRegion(it, outRegion.left.size, request.sampleRate) }
                    stemMsOut = (nowMs() - t0).coerceAtLeast(0L)
                }
                if (isAborted()) return
                if (needInStems && inRegion != null &&
                    inRegion.left.size / request.sampleRate.toDouble() <= AutomixV2Choreo.MAX_STEM_REGION_SEC
                ) {
                    val t0 = nowMs()
                    inStems = synchronized(stemLock) {
                        if (isAborted()) null
                        else separateStems(
                            request.inTrack.id,
                            inRegion.left,
                            inRegion.right,
                            request.sampleRate,
                            inRegion.start,
                            ::isAborted,
                        )
                    }?.takeIf { StemAdapter.matchesRegion(it, inRegion.left.size, request.sampleRate) }
                    stemMsIn = (nowMs() - t0).coerceAtLeast(0L)
                }
                if (isAborted()) return
            }

            // --- Stage 4: PREPARED ---
            val (script, top3) = chooseFinalScript(
                effectivePlan = effectivePlan,
                v2Prep = v2Prep,
                outA = outA,
                inA = inA,
                sungOut = sungOut,
                sungIn = sungIn,
                outStems = outStems,
                inStems = inStems,
                sampleRate = request.sampleRate,
                caps = v2Prep?.caps ?: capabilities(),
                level = request.djLevel,
                outId = request.outTrack.id,
                inId = request.inTrack.id,
                albumOrder = request.albumOrder,
            )
            if (isAborted()) return

            val usedOutStems = if (script?.outUsesStems == true) outStems else null
            val usedInStems = if (script?.inUsesStems == true) inStems else null
            val stemsCount = maxOf(usedOutStems?.stemCount ?: 0, usedInStems?.stemCount ?: 0)
            val leadSec = (windowStartSec - positionSecProvider()).coerceAtLeast(0.0)
            val readyAtMs = nowMs()

            publishIfCurrent(gen) { cur ->
                cur.copy(
                    state = PrepState.PREPARED,
                    script = script,
                    topCandidates = top3,
                    outStems = usedOutStems,
                    inStems = usedInStems,
                    outRegion = outRegion,
                    inRegion = inRegion,
                    preparedAtMs = readyAtMs,
                    timings = cur.timings.copy(
                        analysisMs = analysisMs,
                        planMs = planMs,
                        stemMsOut = stemMsOut,
                        stemMsIn = stemMsIn,
                        preparedLeadSec = leadSec,
                        path = selected.pathHint,
                        level = request.djLevel.name,
                        move = script?.move?.name.orEmpty(),
                        fxCount = script?.fxCount ?: 0,
                        stems = stemsCount,
                        fallbackReason = selected.fallbackReason,
                    ),
                )
            }

            request.inTrack.mediaItem?.let { item ->
                scope.launch(mainDispatcher) {
                    if (!isAborted()) onArtworkPrefetch(item)
                }
            }
        }
    }

    private fun publishIfCurrent(gen: Long, transform: (PreparedPair) -> PreparedPair) {
        while (true) {
            if (released || generation.get() != gen) return
            val cur = _state.value ?: return
            if (cur.generation != gen) return
            val next = transform(cur)
            if (_state.compareAndSet(cur, next)) return
        }
    }

    private data class ReadyAnalysis(
        val outA: TrackAnalysis,
        val inA: TrackAnalysis,
        val outMap: SongMap?,
        val inMap: SongMap?,
    )

    private fun awaitAnalysis(
        gen: Long,
        request: PrepRequest,
        isAborted: () -> Boolean,
    ): ReadyAnalysis? {
        val deadlineMs = nowMs() + analysisWaitTimeoutMs
        var minimumMetAtMs = 0L

        while (!isAborted()) {
            val outA = analysisFor(request.outTrack.id)
            val inA = analysisFor(request.inTrack.id)
            val outMap = if (outA.isUsable) songMapFor(request.outTrack.id, outA) else null
            val inMap = if (inA.isUsable) songMapFor(request.inTrack.id, inA) else null

            val outMinReady = outA.isUsable && outMap != null &&
                (outA.tailBeats != null || outA.quality == TrackAnalysis.QUALITY_FULL || !analysisRunningFor(request.outTrack.id))
            val inMinReady = inA.isUsable && inMap != null

            if (outMinReady && inMinReady) {
                val bothFull = outA.quality == TrackAnalysis.QUALITY_FULL && inA.quality == TrackAnalysis.QUALITY_FULL
                val analyzingAny = analysisRunningFor(request.outTrack.id) || analysisRunningFor(request.inTrack.id)
                val now = nowMs()
                if (minimumMetAtMs == 0L) minimumMetAtMs = now
                val waitedFullMs = now - minimumMetAtMs
                if (bothFull || !analyzingAny || fullPreferredWaitMs <= 0L || waitedFullMs >= fullPreferredWaitMs) {
                    return ReadyAnalysis(outA, inA, outMap, inMap)
                }
                val remFullMs = (fullPreferredWaitMs - waitedFullMs).coerceIn(10L, 500L)
                awaitSignal(remFullMs)
                continue
            }

            // Terminal failure on either track -> don't spin until timeout.
            val outTerminalFail = outA.status == TrackAnalysis.STATUS_READY && !outA.isUsable && !analysisRunningFor(request.outTrack.id)
            val inTerminalFail = inA.status == TrackAnalysis.STATUS_READY && !inA.isUsable && !analysisRunningFor(request.inTrack.id)
            if (outTerminalFail || inTerminalFail) {
                return null
            }

            val now = nowMs()
            if (now >= deadlineMs) {
                return if (outA.isUsable && inA.isUsable && outMap != null && inMap != null) {
                    ReadyAnalysis(outA, inA, outMap, inMap)
                } else null
            }
            val waitSliceMs = (deadlineMs - now).coerceIn(10L, 500L)
            awaitSignal(waitSliceMs)
        }
        return null
    }

    private fun awaitSignal(timeoutMs: Long) {
        waitLock.withLock {
            runCatching {
                waitCondition.await(timeoutMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun signalWaiters() {
        waitLock.withLock {
            waitCondition.signalAll()
        }
    }

    private fun computeDecodeWindows(
        effectivePlan: LoopMix.Plan?,
        exitWindowSec: ClosedFloatingPointRange<Double>?,
        entryCueSec: Double,
        outDurationSec: Double,
        inDurationSec: Double,
        outAnalysis: TrackAnalysis,
        inAnalysis: TrackAnalysis,
        sampleRate: Int,
    ): Pair<Pair<Double, Double>, Pair<Double, Double>> {
        val outBarSec = barSeconds(outAnalysis.bpm, effectivePlan?.out?.beatSec ?: 0.5)
        val inBarSec = barSeconds(inAnalysis.bpm, effectivePlan?.inn?.beatSec ?: 0.5)
        if (effectivePlan != null) {
            val bounds = LoopMix.regionBounds(effectivePlan)
            val clock = LoopMix.Clock(effectivePlan)
            val echoHold = if (effectivePlan.echoTail) effectivePlan.out.beatSec * 8 else 0.0
            val outStop = clock.releaseAt + 0.05 + echoHold + effectivePlan.exitTailSeconds
            val tailRate = if (clock.beats >= 1) {
                val b0 = clock.beatTimes[clock.beats - 1]
                val b1 = clock.beatTimes[clock.beats]
                (effectivePlan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
            } else 1.0
            val outHasTailKnot = !effectivePlan.outLoops && outStop > clock.releaseAt + 1e-6
            val actualInTo = runCatching {
                val knots = LoopMixSession.buildDeckKnots(
                    effectivePlan, clock, sampleRate, outAnalysis, inAnalysis,
                    outHasTailKnot, outStop, tailRate,
                )
                val geom = LoopMixSession.incomingGeometry(effectivePlan, clock, knots)
                val inTail = bounds.inTo - clock.elementTrackTime
                maxOf(bounds.inTo, geom.handoverTrack + inTail, geom.requiredEnd)
            }.getOrDefault(bounds.inTo)

            val outWin = paddedWindow(bounds.outFrom, bounds.outTo, 2.0 * outBarSec, outDurationSec)
            val inWin = paddedWindow(bounds.inFrom, actualInTo, 2.0 * inBarSec, inDurationSec)
            return outWin to inWin
        }
        val window = requireNotNull(exitWindowSec)
        val span = (window.endInclusive - window.start).coerceAtLeast(4.0)
        val outWin = paddedWindow(
            coreFrom = (window.start - 1.0).coerceAtLeast(0.0),
            coreTo = window.endInclusive + 1.0,
            padSec = 2.0 * outBarSec,
            trackDurationSec = outDurationSec,
        )
        val inWin = paddedWindow(
            coreFrom = (entryCueSec - 1.0).coerceAtLeast(0.0),
            coreTo = entryCueSec + span + 3.0,
            padSec = 2.0 * inBarSec,
            trackDurationSec = inDurationSec,
        )
        return outWin to inWin
    }

    private fun requiredStemSides(
        effectivePlan: LoopMix.Plan?,
        v2Prep: ChoreoSessionGlue.Prep?,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
        sampleRate: Int,
        caps: Int,
        level: DjLevel,
        outId: String,
        inId: String,
        albumOrder: Boolean,
    ): Pair<Boolean, Boolean> {
        if (effectivePlan == null) return true to true
        val inputs = buildChoreoInputs(
            effectivePlan = effectivePlan,
            v2Prep = v2Prep,
            outA = outA,
            inA = inA,
            sungOut = sungOut,
            sungIn = sungIn,
            outStems = dummyStemView(sampleRate),
            inStems = dummyStemView(sampleRate),
            sampleRate = sampleRate,
            caps = caps,
            level = level,
            outId = outId,
            inId = inId,
            albumOrder = albumOrder,
            stemsExpected = true,
        )
        val top3 = Choreographer.rank(inputs).take(3).map { it.recipeId }.toMutableList()
        v2Prep?.forcedRecipe?.let { if (it !in top3) top3.add(0, it) }
        var needOut = false
        var needIn = false
        for (id in top3) {
            val recipe = Recipes.byId(id)
            val needsAny = recipe.requiresStems > 0 || recipe.stemModeMinStems > 0 ||
                recipe.steps.any {
                    (it is com.music.bitchord.playback.smart.choreo.RecipeStep.LaneCurve && it.kind.isStem) ||
                        (it is com.music.bitchord.playback.smart.choreo.RecipeStep.SwapLows && it.useBassStems)
                }
            if (needsAny) {
                if (recipe.requiresStems > 0 || recipe.steps.any {
                        (it is com.music.bitchord.playback.smart.choreo.RecipeStep.LaneCurve &&
                            it.deck == DeckTarget.OUT && it.kind.isStem) ||
                            (it is com.music.bitchord.playback.smart.choreo.RecipeStep.SwapLows && it.useBassStems)
                    }
                ) {
                    needOut = true
                }
                if ((recipe.requiresStems > 0 && id != RecipeId.C3 && id != RecipeId.F4) || recipe.steps.any {
                        (it is com.music.bitchord.playback.smart.choreo.RecipeStep.LaneCurve &&
                            it.deck == DeckTarget.IN && it.kind.isStem) ||
                            (it is com.music.bitchord.playback.smart.choreo.RecipeStep.SwapLows && it.useBassStems)
                    }
                ) {
                    needIn = true
                }
            }
        }
        return needOut to needIn
    }

    private fun chooseFinalScript(
        effectivePlan: LoopMix.Plan?,
        v2Prep: ChoreoSessionGlue.Prep?,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
        outStems: StemSet?,
        inStems: StemSet?,
        sampleRate: Int,
        caps: Int,
        level: DjLevel,
        outId: String,
        inId: String,
        albumOrder: Boolean,
    ): Pair<ChoreoScript?, List<Candidate>> {
        if (effectivePlan == null || v2Prep == null) return null to emptyList()
        val inputs = buildChoreoInputs(
            effectivePlan = effectivePlan,
            v2Prep = v2Prep,
            outA = outA,
            inA = inA,
            sungOut = sungOut,
            sungIn = sungIn,
            outStems = StemAdapter.view(outStems),
            inStems = StemAdapter.view(inStems),
            sampleRate = sampleRate,
            caps = caps,
            level = level,
            outId = outId,
            inId = inId,
            albumOrder = albumOrder,
            stemsExpected = outStems != null || inStems != null,
        )
        val ranked = Choreographer.rank(inputs).take(3)
        val script = (if (v2Prep.forcedRecipe != null) Choreographer.chooseRecipe(inputs, v2Prep.forcedRecipe) else null)
            ?: Choreographer.choose(inputs, v2Prep.forced)
            ?: Choreographer.chooseRecipe(inputs, null)
            ?: ChoreoSessionGlue.withoutStems(
                v2Prep, effectivePlan, LoopMix.Clock(effectivePlan), sampleRate,
                outA, inA, sungOut, sungIn, plannedRecipe = v2Prep.forcedRecipe,
            )
        return script to (script?.topCandidates ?: ranked)
    }

    private fun buildChoreoInputs(
        effectivePlan: LoopMix.Plan,
        v2Prep: ChoreoSessionGlue.Prep?,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        sungOut: List<LoopMix.Span>?,
        sungIn: List<LoopMix.Span>?,
        outStems: StemView?,
        inStems: StemView?,
        sampleRate: Int,
        caps: Int,
        level: DjLevel,
        outId: String,
        inId: String,
        albumOrder: Boolean,
        stemsExpected: Boolean,
    ): ChoreoInputs {
        val clock = LoopMix.Clock(effectivePlan)
        val echoHold = if (effectivePlan.echoTail) effectivePlan.out.beatSec * 8 else 0.0
        val outStop = clock.releaseAt + 0.05 + echoHold + effectivePlan.exitTailSeconds
        val tailRate = if (clock.beats >= 1) {
            val b0 = clock.beatTimes[clock.beats - 1]
            val b1 = clock.beatTimes[clock.beats]
            (effectivePlan.out.beatSec / (b1 - b0).coerceAtLeast(1e-6)).coerceIn(0.85, 1.15)
        } else 1.0
        val outHasTailKnot = !effectivePlan.outLoops && outStop > clock.releaseAt + 1e-6
        val pairKey = "$outId->$inId"
        val outStruct = v2Prep?.outStructure ?: com.music.bitchord.playback.smart.choreo.StructureView.best(null, outA)
        val inStruct = v2Prep?.inStructure ?: com.music.bitchord.playback.smart.choreo.StructureView.best(null, inA)
        val (outClock, inClock) = runCatching {
            val knots = LoopMixSession.buildDeckKnots(
                effectivePlan, clock, sampleRate, outA, inA, outHasTailKnot, outStop, tailRate,
            )
            val geometry = LoopMixSession.incomingGeometry(effectivePlan, clock, knots)
            val sr = sampleRate.toDouble()
            val outTrk = TrackClock { mix ->
                if (mix < 0.0) null
                else interpolateKnots(knots.outFrames, knots.outPositionsSec, mix * sr)?.let { u ->
                    if (effectivePlan.outLoops) LoopMix.physicalTrackTime(u, effectivePlan.out.loopStart, effectivePlan.out.loopEnd, 1e18) else u
                }
            }
            val inTrk = TrackClock { mix ->
                if (mix < 0.0) null
                else interpolateKnots(knots.inFrames, knots.inPositionsSec, mix * sr)?.let { u ->
                    if (geometry.loops) LoopMix.physicalTrackTime(u, effectivePlan.inn.loopStart, effectivePlan.inn.loopEnd, geometry.releaseTrack) else u
                }
            }
            outTrk to inTrk
        }.getOrDefault(null to null)

        return ChoreoInputs(
            plan = effectivePlan,
            clock = clock,
            out = outA,
            inn = inA,
            outStructure = outStruct,
            inStructure = inStruct,
            outStems = outStems,
            inStems = inStems,
            caps = caps,
            level = level,
            pairKey = pairKey,
            history = AutomixV2Choreo.history.recent(pairKey),
            recipeHistory = AutomixV2Choreo.history.recentRecipes(pairKey),
            seed = AutomixV2Choreo.seed,
            sungOut = sungOut,
            sungIn = sungIn,
            albumOrder = albumOrder,
            stemsExpected = stemsExpected,
            outClock = outClock,
            inClock = inClock,
        )
    }

    companion object {
        private const val DEFAULT_ANALYSIS_WAIT_MS = 15_000L
        private const val DEFAULT_FULL_PREFERRED_WAIT_MS = 3_000L
        private val ACTIVE_EXECUTION_STATES = setOf(
            PrepState.BUILDING,
            PrepState.ARMED,
            PrepState.RUNNING,
        )

        internal fun newPrepExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
                }
                runnable.run()
            }, "djmix-prep").apply { isDaemon = true }
        }

        internal fun barSeconds(bpm: Double, fallbackBeatSec: Double): Double =
            if (bpm.isFinite() && bpm > 1.0) (240.0 / bpm) else (fallbackBeatSec.takeIf { it > 0.05 } ?: 0.5) * 4.0

        internal fun analysisSignature(out: TrackAnalysis, inn: TrackAnalysis): String =
            "${out.status}:${out.quality}:${(out.bpm * 10).toInt()}|${inn.status}:${inn.quality}:${(inn.bpm * 10).toInt()}"

        internal fun paddedWindow(
            coreFrom: Double,
            coreTo: Double,
            padSec: Double,
            trackDurationSec: Double,
            maxRegionSec: Double = AutomixV2Choreo.MAX_STEM_REGION_SEC,
        ): Pair<Double, Double> {
            val maxEnd = if (trackDurationSec.isFinite() && trackDurationSec > 0.0) trackDurationSec else Double.POSITIVE_INFINITY
            val baseFrom = coreFrom.coerceIn(0.0, maxEnd)
            val baseTo = coreTo.coerceIn(baseFrom, maxEnd)
            val coreSpan = (baseTo - baseFrom).coerceAtLeast(0.0)
            if (coreSpan >= maxRegionSec) {
                return baseFrom to baseTo
            }
            val extraBudget = (maxRegionSec - coreSpan).coerceAtLeast(0.0)
            val eachSidePad = minOf(padSec.coerceAtLeast(0.0), extraBudget / 2.0)
            var from = (baseFrom - eachSidePad).coerceAtLeast(0.0)
            var to = minOf(maxEnd, baseTo + eachSidePad)
            val usedPad = (baseFrom - from) + (to - baseTo)
            val remainingPad = minOf(2.0 * padSec.coerceAtLeast(0.0), extraBudget) - usedPad
            if (remainingPad > 1e-6) {
                if (from <= 1e-6 && to < maxEnd) {
                    to = minOf(maxEnd, to + remainingPad)
                } else if (to >= maxEnd - 1e-6 && from > 0.0) {
                    from = (from - remainingPad).coerceAtLeast(0.0)
                }
            }
            return from to to
        }

        private fun dummyStemView(sampleRate: Int): StemView =
            StemView(
                stemCount = 4,
                confidence = 1f,
                regionStartSec = 0.0,
                sampleRate = sampleRate,
                frames = sampleRate * 60,
                rms = { _, _, _ -> 0.5 },
            )

        private fun interpolateKnots(frames: DoubleArray, positions: DoubleArray, frame: Double): Double? {
            if (frames.isEmpty() || frames.size != positions.size) return null
            if (frame <= frames[0]) return positions[0]
            val last = frames.size - 1
            if (frame >= frames[last]) {
                val denom = maxOf(1.0, frames[last] - frames[maxOf(0, last - 1)])
                return positions[last] + (frame - frames[last]) / denom *
                    (positions[last] - positions[maxOf(0, last - 1)])
            }
            var lo = 0
            var hi = last
            while (hi - lo > 1) {
                val mid = (lo + hi) ushr 1
                if (frames[mid] <= frame) lo = mid else hi = mid
            }
            val span = frames[hi] - frames[lo]
            val f = if (span <= 0.0) 0.0 else (frame - frames[lo]) / span
            return positions[lo] + (positions[hi] - positions[lo]) * f
        }
    }
}
