package com.music.bitchord.playback.djmix

import android.os.Process
import android.os.SystemClock
import androidx.media3.common.MediaItem
import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.DjLevel as CoreDjLevel
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.planner.BuiltInRecipeCatalog
import com.music.bitchord.djmix.core.planner.PairPlanner
import com.music.bitchord.djmix.core.planner.PlannerRequest
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.djmix.store.DjmixAnalysisStore
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.LyricCue
import com.music.bitchord.playback.smart.MixInspectorFeed
import com.music.bitchord.playback.smart.MixInspectorPhase
import com.music.bitchord.playback.smart.MixInspectorRecipeScore
import com.music.bitchord.playback.smart.MixInspectorState
import com.music.bitchord.playback.smart.MixInspectorTrack
import com.music.bitchord.playback.smart.PhraseMaps
import com.music.bitchord.playback.smart.PhraseScript
import com.music.bitchord.playback.smart.SongMap
import com.music.bitchord.playback.smart.SongMaps
import com.music.bitchord.playback.smart.StemSet
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.AutomixV2Choreo
import com.music.bitchord.playback.smart.choreo.Candidate
import com.music.bitchord.playback.smart.choreo.ChoreoCaps
import com.music.bitchord.playback.smart.choreo.ChoreoRendererBridge
import com.music.bitchord.playback.smart.choreo.ChoreoScript
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.choreo.RecipeId
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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToLong

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
    val title: String = "",
    val artist: String = "",
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
    val preferStraight: Boolean = false,
    val excludedRecipes: Set<RecipeId> = emptySet(),
    val fallbackSeconds: Double = 8.0,
    val lyricsOut: List<LyricCue> = emptyList(),
    val lyricsIn: List<LyricCue> = emptyList(),
    val minExitMs: Long? = null,
    val preferNoStemVariant: Boolean = false,
    val machineGeneration: Long? = null,
)

data class PreparedPair(
    val outId: String,
    val inId: String,
    val outTitle: String = "",
    val outArtist: String = "",
    val inTitle: String = "",
    val inArtist: String = "",
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
    val straightRateMode: StraightRateMode? = null,
    val transitionPlanV1: TransitionPlanV1? = null,
    val useNoStemVariant: Boolean = false,
    val preparedSession: LoopMixSession? = null,
    val preparedSessionNoStem: Boolean = false,
    val lastRefusalReason: RefusalReason? = null,
    internal val outStemWindowStartSec: Double? = null,
    internal val outStemWindowEndSec: Double? = null,
    internal val inStemWindowStartSec: Double? = null,
    internal val inStemWindowEndSec: Double? = null,
)

/**
 * Background pipeline that prepares `(outId, inId)` well before the splice:
 * HEAD_TAIL/FULL analysis -> [PairPlanner] freeze -> containment-aware decode on `djmix-prep` ->
 * SCNet stem separation on dedicated `djmix-stems` -> off-thread `LoopMixSession` + `nativePrepare`.
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
    private val store: DjmixAnalysisStore? = null,
    private val stemExecutor: ExecutorService = newStemExecutor(),
) {
    private val _state = MutableStateFlow<PreparedPair?>(null)
    val state: StateFlow<PreparedPair?> = _state.asStateFlow()

    private val generation = AtomicLong(0L)
    private val historyLock = Any()
    private val recentRecipeIds = ArrayDeque<String>()
    private var transitionsSinceLastAbrupt: Int = 999
    private var lastRecordedDoneGen: Long = -1L

    @Volatile private var mixExecutor: MixExecutor? = null
    @Volatile private var inLevelProvider: (String, String) -> Float = { _, _ -> 1f }
    @Volatile private var highQualityProvider: () -> Boolean = { true }

    @Volatile private var collectorJob: Job? = null
    @Volatile private var inspectorCollectorJob: Job? = null
    @Volatile private var activeRequest: PrepRequest? = null
    @Volatile private var activeGeneration: Long = 0L
    @Volatile private var released = false

    private val sessionManager = PreparedSessionManager(
        object : PreparedSessionHost {
            override val stateFlow: MutableStateFlow<PreparedPair?> get() = _state
            override val scope: CoroutineScope get() = this@PairPreparer.scope
            override val executor: ExecutorService get() = this@PairPreparer.executor
            override fun isReleased(): Boolean = released
            override fun currentGeneration(): Long = generation.get()
            override fun nowMs(): Long = this@PairPreparer.nowMs()
            override fun mixExecutor(): MixExecutor? = mixExecutor
            override fun inLevel(outId: String, inId: String): Float = inLevelProvider(outId, inId)
            override fun highQuality(): Boolean = highQualityProvider()
            override fun publishIfCurrent(gen: Long, transform: (PreparedPair) -> PreparedPair) {
                this@PairPreparer.publishIfCurrent(gen, transform)
            }
        },
    )

    private val stemWindowPlanner = StemWindowPlanner(
        object : StemWindowHost {
            override val scope: CoroutineScope get() = this@PairPreparer.scope
            override val mainDispatcher: CoroutineDispatcher get() = this@PairPreparer.mainDispatcher
            override val stemExecutor: ExecutorService get() = this@PairPreparer.stemExecutor
            override val stemLock: Any get() = this@PairPreparer.stemLock
            override fun isAborted(gen: Long): Boolean = released || generation.get() != gen
            override fun nowMs(): Long = this@PairPreparer.nowMs()
            override fun positionSec(): Double = positionSecProvider()
            override fun songMapFor(trackId: String, analysis: TrackAnalysis): SongMap? =
                this@PairPreparer.songMapFor(trackId, analysis)
            override fun sungFor(trackId: String): List<LoopMix.Span>? = this@PairPreparer.sungFor(trackId)
            override fun canUseStems(): Boolean = this@PairPreparer.canUseStems()
            override fun capabilities(): Int = this@PairPreparer.capabilities()
            override fun recentRecipeIdsSnapshot(): List<String> = this@PairPreparer.recentRecipeIdsSnapshot()
            override fun currentTransitionsSinceLastAbrupt(): Int = this@PairPreparer.currentTransitionsSinceLastAbrupt()
            override fun tasteWeights(): Map<String, Double> = store?.getPreferenceWeights().orEmpty()
            override fun selectPlan(input: PlanInput): SelectedPlan? = planSelector.select(input)
            override fun resolveTransitionPlanV1(
                selected: SelectedPlan,
                request: PrepRequest,
                gen: Long,
                outA: TrackAnalysis,
                inA: TrackAnalysis,
                script: ChoreoScript?,
            ): TransitionPlanV1? = this@PairPreparer.resolveTransitionPlanV1(selected, request, gen, outA, inA, script)
            override fun decodeRegion(track: PrepTrack, fromSec: Double, toSec: Double, sampleRate: Int): LoopRegion? =
                this@PairPreparer.decodeRegion(track, fromSec, toSec, sampleRate)
            override fun separateStems(
                trackId: String,
                left: FloatArray,
                right: FloatArray,
                sampleRate: Int,
                regionStartSec: Double,
                shouldAbort: () -> Boolean,
            ): StemSet? = this@PairPreparer.separateStems(trackId, left, right, sampleRate, regionStartSec, shouldAbort)
            override fun publishIfCurrent(gen: Long, transform: (PreparedPair) -> PreparedPair) {
                this@PairPreparer.publishIfCurrent(gen, transform)
            }
            override fun mixExecutor(): MixExecutor? = mixExecutor
            override fun onArtworkPrefetch(item: MediaItem) = this@PairPreparer.onArtworkPrefetch(item)
            override fun prepareRendererIfCurrent(gen: Long, useNoStemVariant: Boolean) {
                _state.value?.takeIf { it.generation == gen }?.let { frozenPair ->
                    sessionManager.prepareRenderer(frozenPair, useNoStemVariant = useNoStemVariant)
                }
            }
        },
    )

    fun attachExecutionBridge(
        executor: MixExecutor?,
        inLevel: (String, String) -> Float = { _, _ -> 1f },
        highQuality: () -> Boolean = { true },
    ) {
        mixExecutor = executor
        inLevelProvider = inLevel
        highQualityProvider = highQuality
    }

    fun recordCompletedRecipe(recipeId: String?) {
        val id = recipeId?.trim()?.uppercase().takeUnless { it.isNullOrEmpty() } ?: return
        synchronized(historyLock) {
            if (recentRecipeIds.size >= RECIPE_HISTORY_CAP) recentRecipeIds.removeFirst()
            recentRecipeIds.addLast(id)
            transitionsSinceLastAbrupt = if (id in ABRUPT_RECIPE_IDS) {
                0
            } else {
                (transitionsSinceLastAbrupt + 1).coerceAtMost(999)
            }
        }
    }

    private fun recentRecipeIdsSnapshot(): List<String> = synchronized(historyLock) { recentRecipeIds.toList() }

    private fun currentTransitionsSinceLastAbrupt(): Int = synchronized(historyLock) { transitionsSinceLastAbrupt }

    // FORK-HOOK(mix-inspector): PairPreparer is the only process-global producer of this feed.
    init {
        collectorJob = scope.launch {
            analysisUpdates.collect { trackId ->
                if (trackId.isBlank() || released) return@collect
                onAnalysisUpdated(trackId)
            }
        }
        inspectorCollectorJob = scope.launch {
            _state.collect { publishInspectorSnapshot(it) }
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

        val gen = request.machineGeneration?.also { generation.set(it) } ?: generation.incrementAndGet()
        activeRequest = request
        activeGeneration = gen

        val initialTimings = PairTimings(
            outId = outId,
            inId = inId,
            prepStartMs = nowMs(),
            level = request.djLevel.name,
        )
        val previous = _state.value
        sessionManager.closeSupersededSession(previous, gen)
        _state.value = PreparedPair(
            outId = outId,
            inId = inId,
            outTitle = request.outTrack.title,
            outArtist = request.outTrack.artist,
            inTitle = request.inTrack.title,
            inArtist = request.inTrack.artist,
            djLevel = request.djLevel,
            state = PrepState.ANALYZING,
            sampleRate = request.sampleRate,
            albumOrder = request.albumOrder,
            timings = initialTimings,
            generation = gen,
            useNoStemVariant = request.preferNoStemVariant,
        )

        runCatching {
            executor.execute {
                executePipeline(gen, request, initialTimings, reuseFrom = previous)
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

    /** Returns false without invalidating the existing preparation if no other allowed recipe fits. */
    fun onRemixRequested(request: PrepRequest, exclude: RecipeId? = null): Boolean {
        val current = _state.value ?: return false
        if (released || current.state in ACTIVE_EXECUTION_STATES || current.outId != request.outTrack.id ||
            current.inId != request.inTrack.id || current.djLevel != request.djLevel) return false
        val currentCode = current.transitionPlanV1?.recipeId ?: current.script?.recipeId
        val excluded = exclude ?: RecipeId.fromCode(currentCode)
        val excludedCodes = (request.excludedRecipes.map { it.code } + listOfNotNull(currentCode, excluded?.code)).toSet()
        val outA = current.outAnalysis ?: analysisFor(request.outTrack.id)
        val inA = current.inAnalysis ?: analysisFor(request.inTrack.id)
        if (outA.isUsable && inA.isUsable) {
            val outV3 = toV3ForPlanner(request.outTrack.id, outA, PhraseMaps.get(request.outTrack.id), current.outSongMap, request.outTrack.durationSec)
            val inV3 = toV3ForPlanner(request.inTrack.id, inA, PhraseMaps.get(request.inTrack.id), current.inSongMap, request.inTrack.durationSec)
            val probe = PairPlanner.plan(
                PlannerRequest(
                    generation = current.generation,
                    outTrack = outV3,
                    inTrack = inV3,
                    outTitle = request.outTrack.title,
                    inTitle = request.inTrack.title,
                    djLevel = toCoreDjLevel(request.djLevel),
                    positionMs = (request.positionSec * 1000.0).roundToLong().coerceAtLeast(0L),
                    minExitMs = request.minExitMs,
                    recentRecipeIds = recentRecipeIdsSnapshot(),
                    transitionsSinceLastAbrupt = currentTransitionsSinceLastAbrupt(),
                    excludedRecipeIds = excludedCodes,
                    preferNoStemVariant = request.preferNoStemVariant,
                    tasteWeights = store?.getPreferenceWeights().orEmpty(),
                ),
            )
            if (probe !is MixOutcome.Ok) return false
        } else if (excluded == null) {
            return false
        }
        val nextExcluded = if (excluded != null) request.excludedRecipes + excluded else request.excludedRecipes
        trigger(request.copy(excludedRecipes = nextExcluded), force = true)
        return true
    }

    /**
     * Handles analysis updates reactively on `djmix-prep` without calling `trigger(force = true)`
     * or cancelling running decode/SCNet jobs. Refines quietly via [PairPlanner] and re-freezes
     * only if refined exit/entry shifts by `>= 1 bar` or breaks recipe requirements.
     */
    fun onAnalysisUpdated(trackId: String) {
        if (released) return
        val req = activeRequest ?: return
        if (trackId != req.outTrack.id && trackId != req.inTrack.id) return
        val current = _state.value ?: return
        if (current.state in ACTIVE_EXECUTION_STATES) return
        val targetGen = current.generation
        runCatching {
            executor.execute {
                refineOnAnalysisUpdate(targetGen, req)
            }
        }
    }

    fun snapshotFor(outId: String, inId: String, djLevel: DjLevel? = null): PreparedPair? =
        _state.value?.takeIf {
            it.generation == generation.get() && it.outId == outId && it.inId == inId &&
                (djLevel == null || it.djLevel == djLevel)
        }

    /**
     * Exact execution lookup replacing `snapshotForBuild` and structural `plan ==` comparisons.
     * Validates pair identity, exact frozen generation, active DJ level, script/prep readiness,
     * and PCM coverage.
     */
    fun frozen(
        outId: String,
        inId: String,
        generation: Long,
        djLevel: DjLevel? = null,
    ): PreparedPair? {
        val expectedLevel = djLevel ?: activeRequest?.djLevel
        return snapshotFor(outId, inId, expectedLevel)?.takeIf { pair ->
            pair.generation == generation &&
                pair.state in setOf(PrepState.PREPARED, PrepState.BUILDING, PrepState.ARMED, PrepState.RUNNING) &&
                pair.plan != null &&
                pair.v2Prep != null &&
                ChoreoSessionGlue.isSamePlan(pair.v2Prep.plan, pair.plan) &&
                pair.script != null &&
                LoopMixSession.preparedCoverageRefusal(
                    pair.plan, pair.sampleRate, pair.outRegion, pair.inRegion,
                    pair.outAnalysis, pair.inAnalysis, pair.straightRateMode, pair.djLevel,
                ) == null
        }
    }

    fun isCurrentGeneration(outId: String, inId: String, expected: Long): Boolean =
        snapshotFor(outId, inId)?.generation == expected

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
        val updated = updateIfPair(outId, inId) { cur ->
            cur.copy(state = PrepState.DONE)
        } ?: return
        if (updated.generation != lastRecordedDoneGen) {
            lastRecordedDoneGen = updated.generation
            recordCompletedRecipe(updated.transitionPlanV1?.recipeId ?: updated.script?.recipeId)
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
        val cur = _state.value
        sessionManager.closeSupersededSession(cur, generation.get())
        if (cur != null && cur.state != PrepState.IDLE) {
            _state.value = cur.copy(
                state = PrepState.IDLE,
                outStems = null,
                inStems = null,
                outRegion = null,
                inRegion = null,
                preparedSession = null,
                timings = if (reason.isNotEmpty()) cur.timings.copy(fallbackReason = reason) else cur.timings,
            )
        }
    }

    fun release() {
        released = true
        generation.incrementAndGet()
        activeRequest = null
        collectorJob?.cancel()
        collectorJob = null
        sessionManager.closeSupersededSession(_state.value, generation.get())
        _state.value = null
        publishInspectorSnapshot(null)
        inspectorCollectorJob?.cancel()
        inspectorCollectorJob = null
        if (ownsExecutor) {
            runCatching { executor.shutdownNow() }
            runCatching { stemExecutor.shutdownNow() }
        }
    }

    fun consumePreparedSession(
        outId: String,
        inId: String,
        expectedGeneration: Long,
        useNoStemVariant: Boolean = false,
    ): LoopMixSession? = sessionManager.consumePreparedSession(outId, inId, expectedGeneration, useNoStemVariant)

    /** Builds `LoopMixSession` + `nativePrepare()` off the main/audio thread during `PREPARING -> READY`. */
    fun prepareRenderer(
        frozen: PreparedPair,
        useNoStemVariant: Boolean = frozen.useNoStemVariant,
        onRefusal: ((String) -> Unit)? = null,
    ): LoopMixSession? = sessionManager.prepareRenderer(frozen, useNoStemVariant, onRefusal)

    /** Executes `MixEffect.PrepareRenderer` off-thread on `djmix-prep`. */
    fun prepareRendererOffThread(planId: String, targetGeneration: Long, useNoStemVariant: Boolean) =
        sessionManager.prepareRendererOffThread(planId, targetGeneration, useNoStemVariant)

    private fun shouldDeduplicate(
        current: PreparedPair?,
        prevRequest: PrepRequest?,
        nextRequest: PrepRequest,
    ): Boolean {
        if (current == null || prevRequest == null) return false
        if (current.outId != nextRequest.outTrack.id || current.inId != nextRequest.inTrack.id) return false
        if (current.djLevel != nextRequest.djLevel) return false
        if (current.state in ACTIVE_EXECUTION_STATES) return true
        if (prevRequest.sampleRate != nextRequest.sampleRate || prevRequest.albumOrder != nextRequest.albumOrder ||
            prevRequest.preferStraight != nextRequest.preferStraight ||
            prevRequest.outTrack.durationSec != nextRequest.outTrack.durationSec ||
            prevRequest.inTrack.durationSec != nextRequest.inTrack.durationSec ||
            prevRequest.outTrack.uri != nextRequest.outTrack.uri || prevRequest.inTrack.uri != nextRequest.inTrack.uri) return false
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

    /** The selector's view of an already-prepared pair, for a remix feasibility probe. */
    private fun planInput(request: PrepRequest, current: PreparedPair): PlanInput {
        val outA = current.outAnalysis ?: analysisFor(request.outTrack.id)
        val inA = current.inAnalysis ?: analysisFor(request.inTrack.id)
        return PlanInput(
            outTrack = request.outTrack,
            inTrack = request.inTrack,
            outAnalysis = outA,
            inAnalysis = inA,
            outSongMap = current.outSongMap,
            inSongMap = current.inSongMap,
            sungOut = current.sungOut ?: sungFor(request.outTrack.id),
            sungIn = current.sungIn ?: sungFor(request.inTrack.id),
            djLevel = request.djLevel,
            sampleRate = request.sampleRate,
            positionSec = request.positionSec,
            albumOrder = current.albumOrder,
            stemsExpected = canUseStems() && AutomixV2Choreo.useStems &&
                ChoreoCaps.has(capabilities(), ChoreoCaps.STEMS),
            preferStraight = request.preferStraight,
            excludedRecipes = request.excludedRecipes,
            fallbackSeconds = request.fallbackSeconds,
            lyricsOut = request.lyricsOut,
            lyricsIn = request.lyricsIn,
            caps = capabilities(),
            recentRecipeIds = recentRecipeIdsSnapshot(),
            transitionsSinceLastAbrupt = currentTransitionsSinceLastAbrupt(),
            minExitMs = request.minExitMs,
            preferNoStemVariant = request.preferNoStemVariant,
            tasteWeights = store?.getPreferenceWeights().orEmpty(),
            machineGeneration = current.generation,
        )
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

    private fun executePipeline(
        gen: Long,
        request: PrepRequest,
        initialTimings: PairTimings,
        reuseFrom: PreparedPair? = null,
    ) {
        fun isAborted(): Boolean = released || generation.get() != gen

        runCatching {
            if (isAborted()) return

            // --- Stage 1: Non-blocking HEAD_TAIL/FULL check (never stall 15 s in awaitAnalysis) ---
            requestAnalysis(request.outTrack)
            requestAnalysis(request.inTrack)

            val rawOut = analysisFor(request.outTrack.id)
            val rawIn = analysisFor(request.inTrack.id)
            mixExecutor?.dispatch(MixEvent.AnalysisReady(request.outTrack.id, analysisLevelOf(rawOut), gen, rawOut.isUsable))
            mixExecutor?.dispatch(MixEvent.AnalysisReady(request.inTrack.id, analysisLevelOf(rawIn), gen, rawIn.isUsable))

            val outTermFail = rawOut.status == TrackAnalysis.STATUS_READY && !rawOut.isUsable && !analysisRunningFor(request.outTrack.id)
            val inTermFail = rawIn.status == TrackAnalysis.STATUS_READY && !rawIn.isUsable && !analysisRunningFor(request.inTrack.id)

            if ((!rawOut.isUsable || !rawIn.isUsable) && !request.preferStraight) {
                val sig = analysisSignature(rawOut, rawIn)
                val analysisMs = (nowMs() - initialTimings.prepStartMs).coerceAtLeast(0L)
                if (outTermFail || inTermFail) {
                    publishIfCurrent(gen) { cur ->
                        cur.copy(
                            state = PrepState.IDLE,
                            outAnalysis = rawOut,
                            inAnalysis = rawIn,
                            analysisSignature = sig,
                            timings = cur.timings.copy(
                                analysisMs = analysisMs,
                                fallbackReason = "analysis-unavailable",
                            ),
                        )
                    }
                    mixExecutor?.dispatch(
                        MixEvent.AnalysisReady(
                            trackId = if (outTermFail) request.outTrack.id else request.inTrack.id,
                            level = AnalysisLevel.NONE,
                            generation = gen,
                            usable = false,
                            refusalReason = RefusalReason.WAITING_ON_ANALYSIS,
                        ),
                    )
                } else {
                    publishIfCurrent(gen) { cur ->
                        cur.copy(
                            outAnalysis = rawOut,
                            inAnalysis = rawIn,
                            analysisSignature = sig,
                            timings = cur.timings.copy(analysisMs = analysisMs),
                        )
                    }
                }
                return
            }

            stemWindowPlanner.planDecodeAndLaunchStems(
                gen = gen,
                request = request,
                initialTimings = initialTimings,
                rawOut = rawOut,
                rawIn = rawIn,
                reuseFrom = reuseFrom,
            )
        }
    }

    private fun refineOnAnalysisUpdate(targetGen: Long, request: PrepRequest) {
        if (released || generation.get() != targetGen) return
        val cur = _state.value ?: return
        if (cur.generation != targetGen || cur.state in ACTIVE_EXECUTION_STATES) return
        if (cur.outId != request.outTrack.id || cur.inId != request.inTrack.id) return

        val rawOut = analysisFor(request.outTrack.id)
        val rawIn = analysisFor(request.inTrack.id)
        mixExecutor?.dispatch(MixEvent.AnalysisReady(request.outTrack.id, analysisLevelOf(rawOut), targetGen, rawOut.isUsable))
        mixExecutor?.dispatch(MixEvent.AnalysisReady(request.inTrack.id, analysisLevelOf(rawIn), targetGen, rawIn.isUsable))

        if (cur.state == PrepState.ANALYZING && cur.plan == null) {
            if ((rawOut.isUsable && rawIn.isUsable) || request.preferStraight) {
                stemWindowPlanner.planDecodeAndLaunchStems(
                    gen = targetGen,
                    request = request.copy(positionSec = positionSecProvider()),
                    initialTimings = cur.timings,
                    rawOut = rawOut,
                    rawIn = rawIn,
                    reuseFrom = cur,
                )
            } else {
                val outTermFail = rawOut.status == TrackAnalysis.STATUS_READY && !rawOut.isUsable && !analysisRunningFor(request.outTrack.id)
                val inTermFail = rawIn.status == TrackAnalysis.STATUS_READY && !rawIn.isUsable && !analysisRunningFor(request.inTrack.id)
                if (outTermFail || inTermFail) {
                    publishIfCurrent(targetGen) { s ->
                        s.copy(
                            state = PrepState.IDLE,
                            outAnalysis = rawOut,
                            inAnalysis = rawIn,
                            analysisSignature = analysisSignature(rawOut, rawIn),
                            timings = s.timings.copy(fallbackReason = "analysis-unavailable"),
                        )
                    }
                }
            }
            return
        }

        val sig = analysisSignature(rawOut, rawIn)
        val outMap = if (rawOut.isUsable) songMapFor(request.outTrack.id, rawOut) else cur.outSongMap
        val inMap = if (rawIn.isUsable) songMapFor(request.inTrack.id, rawIn) else cur.inSongMap
        if (sig == cur.analysisSignature &&
            songMapsEquivalent(outMap, cur.outSongMap) &&
            songMapsEquivalent(inMap, cur.inSongMap)
        ) return
        if (!rawOut.isUsable || !rawIn.isUsable) return

        val outA = rawOut.copy(duration = request.outTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: rawOut.duration)
        val inA = rawIn.copy(duration = request.inTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: rawIn.duration)
        val outDur = request.outTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: outA.duration
        val inDur = request.inTrack.durationSec.takeIf { it.isFinite() && it > 1.0 } ?: inA.duration
        val liveReq = request.copy(positionSec = positionSecProvider().coerceAtLeast(request.positionSec))

        val outV3 = toV3ForPlanner(liveReq.outTrack.id, outA, PhraseMaps.get(liveReq.outTrack.id), outMap, outDur)
        val inV3 = toV3ForPlanner(liveReq.inTrack.id, inA, PhraseMaps.get(liveReq.inTrack.id), inMap, inDur)
        val refinedOutcome = PairPlanner.plan(
            PlannerRequest(
                generation = targetGen,
                outTrack = outV3,
                inTrack = inV3,
                outTitle = liveReq.outTrack.title,
                inTitle = liveReq.inTrack.title,
                djLevel = toCoreDjLevel(liveReq.djLevel),
                positionMs = (liveReq.positionSec * 1000.0).roundToLong().coerceAtLeast(0L),
                minExitMs = liveReq.minExitMs,
                recentRecipeIds = recentRecipeIdsSnapshot(),
                transitionsSinceLastAbrupt = currentTransitionsSinceLastAbrupt(),
                excludedRecipeIds = liveReq.excludedRecipes.map { it.code }.toSet(),
                preferNoStemVariant = liveReq.preferNoStemVariant,
                tasteWeights = store?.getPreferenceWeights().orEmpty(),
            ),
        )
        val oldV1 = cur.transitionPlanV1
        val refinedV1 = (refinedOutcome as? MixOutcome.Ok)?.value
        val barMs = (oldV1?.barDurationMs ?: refinedV1?.barDurationMs ?: (barSeconds(outA.bpm, 0.5) * 1000.0).roundToLong())
            .coerceAtLeast(500L)
        val oldExitMs = oldV1?.frozenExitMs ?: (cur.plannedWindowStartSec * 1000.0).roundToLong()
        val oldEntryMs = oldV1?.frozenEntryMs ?: (cur.entryCueSec * 1000.0).roundToLong()
        val shiftedGeBar = refinedV1 != null &&
            (abs(refinedV1.frozenExitMs - oldExitMs) >= barMs || abs(refinedV1.frozenEntryMs - oldEntryMs) >= barMs)
        val reqInvalidated = refinedV1 != null && oldV1 != null &&
            (oldV1.requiredStems != refinedV1.requiredStems && cur.state == PrepState.PREPARED &&
                ((cur.script?.outUsesStems == true && cur.outStems == null) ||
                    (cur.script?.inUsesStems == true && cur.inStems == null)))

        if (!shiftedGeBar && !reqInvalidated) {
            // < 1 bar shift and requirements intact: keep the frozen plan and any decoded/stem work.
            publishIfCurrent(targetGen) { s ->
                s.copy(
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = outMap,
                    inSongMap = inMap,
                    analysisSignature = sig,
                )
            }
            return
        }

        // Only re-freeze if MixMachine has not yet promoted past Preparing/Moved (or when standalone).
        val machineState = mixExecutor?.currentState()
        if (machineState != null && machineState !is MixState.Preparing && machineState !is MixState.Moved) {
            publishIfCurrent(targetGen) { s ->
                s.copy(
                    outAnalysis = outA,
                    inAnalysis = inA,
                    outSongMap = outMap,
                    inSongMap = inMap,
                    analysisSignature = sig,
                )
            }
            return
        }

        stemWindowPlanner.planDecodeAndLaunchStems(
            gen = targetGen,
            request = liveReq,
            initialTimings = cur.timings,
            rawOut = rawOut,
            rawIn = rawIn,
            reuseFrom = cur,
        )
    }

    private fun resolveTransitionPlanV1(
        selected: SelectedPlan,
        request: PrepRequest,
        gen: Long,
        outA: TrackAnalysis,
        inA: TrackAnalysis,
        script: ChoreoScript?,
    ): TransitionPlanV1? {
        selected.transitionPlanV1?.let { return it }
        val plan = selected.plan
        val outBpm = outA.bpm.takeIf { it.isFinite() && it > 1.0 } ?: plan?.out?.bpm?.takeIf { it > 1.0 } ?: 120.0
        val barMs = (barSeconds(outBpm, plan?.out?.beatSec ?: 0.5) * 1000.0).roundToLong().coerceAtLeast(500L)
        val exitMs = ((plan?.out?.loopStart ?: selected.exitWindowSec?.start ?: 0.0) * 1000.0).roundToLong().coerceAtLeast(0L)
        val entryMs = ((plan?.inn?.loopStart ?: selected.entryCueSec ?: 0.0) * 1000.0).roundToLong().coerceAtLeast(0L)
        val overlapMs = if (plan != null) {
            (LoopMix.Clock(plan).releaseAt * 1000.0).roundToLong().coerceAtLeast(1L)
        } else {
            val w = selected.exitWindowSec ?: return null
            ((w.endInclusive - w.start) * 1000.0).roundToLong().coerceAtLeast(1L)
        }
        val geom = when {
            plan == null -> TransitionGeometry.STRAIGHT
            plan.outLoops || plan.inLoops -> TransitionGeometry.LOOP
            selected.straightRateMode == null -> TransitionGeometry.BRIDGE
            else -> TransitionGeometry.STRAIGHT
        }
        val recipeCode = script?.recipeId?.takeIf { it.isNotBlank() }
            ?: selected.previewScript?.recipeId?.takeIf { it.isNotBlank() }
            ?: selected.v2Prep?.forcedRecipe?.code
            ?: "S1"
        val recipeName = script?.recipeName?.takeIf { it.isNotBlank() }
            ?: selected.previewScript?.recipeName?.takeIf { it.isNotBlank() }
            ?: selected.v2Prep?.forcedRecipe?.displayName
            ?: "Smooth blend"
        val catalogEntry = BuiltInRecipeCatalog.byId(recipeCode)
        return TransitionPlanV1(
            planId = "plan-$gen-$recipeCode-$exitMs",
            generation = gen,
            outTrackId = request.outTrack.id,
            inTrackId = request.inTrack.id,
            outTitle = request.outTrack.title,
            inTitle = request.inTrack.title,
            djLevel = toCoreDjLevel(request.djLevel),
            geometry = geom,
            frozenExitMs = exitMs,
            frozenEntryMs = entryMs,
            swapDownbeatMs = exitMs + overlapMs / 2L,
            overlapDurationMs = overlapMs,
            barDurationMs = barMs,
            recipeId = recipeCode,
            recipeName = recipeName,
            requiredStems = catalogEntry?.requiredStems ?: emptySet(),
            hasNoStemVariant = catalogEntry?.hasNoStemVariant ?: true,
        )
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

    private fun publishInspectorSnapshot(pair: PreparedPair?) {
        if (released) {
            MixInspectorFeed.publish(null)
            return
        }
        if (pair == null) {
            MixInspectorFeed.publish(null)
            return
        }
        val script = pair.script
        val planV1 = pair.transitionPlanV1
        val timings = pair.timings
        val plannedPath = timings.path.takeIf { it.isNotBlank() }
        val actualPath = if (pair.state.ordinal >= PrepState.PREPARED.ordinal) plannedPath else null
        val candidates = pair.topCandidates.ifEmpty { script?.topCandidates.orEmpty() }
            .take(3)
            .map { candidate ->
                MixInspectorRecipeScore(
                    recipeId = candidate.recipeId.code,
                    recipeName = candidate.recipeName,
                    score = candidate.total,
                    reason = candidate.reason,
                )
            }
            .ifEmpty {
                planV1?.topScores.orEmpty().take(3).map { s ->
                    MixInspectorRecipeScore(
                        recipeId = s.recipeId,
                        recipeName = s.recipeName,
                        score = s.score,
                        reason = s.reason,
                    )
                }
            }
        val effects = if (script == null) {
            emptyList()
        } else {
            (script.outFx.activeFxNames + script.inFx.activeFxNames).distinct()
        }
        val stemsReady = when {
            pair.state.ordinal >= PrepState.PREPARED.ordinal -> true
            pair.state == PrepState.STEMMING -> false
            else -> null
        }
        val remixReady = pair.state == PrepState.PREPARED && script != null
        val remixUnavailableReason = when {
            remixReady -> null
            pair.state.ordinal >= PrepState.BUILDING.ordinal -> "This transition is already building or playing."
            pair.script == null && pair.state == PrepState.PREPARED -> "No prepared recipe is available to remix."
            pair.state == PrepState.IDLE -> "There is no prepared transition to remix."
            else -> "Preparation is not ready yet."
        }
        MixInspectorFeed.publish(
            MixInspectorState(
                generation = pair.generation,
                current = MixInspectorTrack(
                    mediaId = pair.outId,
                    title = pair.outTitle,
                    artist = pair.outArtist,
                ),
                next = MixInspectorTrack(
                    mediaId = pair.inId,
                    title = pair.inTitle,
                    artist = pair.inArtist,
                ),
                level = pair.djLevel,
                phase = when (pair.state) {
                    PrepState.IDLE -> MixInspectorPhase.IDLE
                    PrepState.ANALYZING -> MixInspectorPhase.ANALYZING
                    PrepState.PLANNED -> MixInspectorPhase.PLANNED
                    PrepState.STEMMING -> MixInspectorPhase.STEMMING
                    PrepState.PREPARED -> MixInspectorPhase.PREPARED
                    PrepState.BUILDING -> MixInspectorPhase.BUILDING
                    PrepState.ARMED -> MixInspectorPhase.ARMED
                    PrepState.RUNNING -> MixInspectorPhase.RUNNING
                    PrepState.DONE -> MixInspectorPhase.DONE
                },
                plannedPath = plannedPath,
                path = actualPath,
                recipeId = planV1?.recipeId?.takeIf { it.isNotBlank() }
                    ?: script?.recipeId?.takeIf { it.isNotBlank() }
                    ?: pair.v2Prep?.forcedRecipe?.code
                    ?: candidates.firstOrNull()?.recipeId,
                recipeName = planV1?.recipeName?.takeIf { it.isNotBlank() }
                    ?: script?.recipeName?.takeIf { it.isNotBlank() }
                    ?: pair.v2Prep?.forcedRecipe?.displayName
                    ?: candidates.firstOrNull()?.recipeName,
                reason = script?.reason?.takeIf { it.isNotBlank() }
                    ?: planV1?.placementReasons?.firstOrNull(),
                stemsReady = stemsReady,
                stemCount = if (pair.state.ordinal >= PrepState.PREPARED.ordinal) timings.stems else null,
                prepStartMs = timings.prepStartMs.takeIf { it > 0L },
                analysisMs = if (pair.state != PrepState.ANALYZING) timings.analysisMs else null,
                planMs = if (pair.state.ordinal >= PrepState.PLANNED.ordinal && pair.state != PrepState.ANALYZING) timings.planMs else null,
                stemMsOut = if (pair.state.ordinal >= PrepState.STEMMING.ordinal) timings.stemMsOut else null,
                stemMsIn = if (pair.state.ordinal >= PrepState.STEMMING.ordinal) timings.stemMsIn else null,
                preparedLeadSec = if (pair.state.ordinal >= PrepState.PREPARED.ordinal) timings.preparedLeadSec else null,
                buildMs = timings.buildMs.takeIf { it > 0L },
                armLeadMs = timings.armLeadMs.takeIf { it > 0L },
                watchdog = timings.watchdog,
                fxCount = script?.fxCount ?: timings.fxCount.takeIf { it > 0 },
                effects = effects,
                topCandidates = candidates,
                fallbackReason = timings.fallbackReason.takeIf { it.isNotBlank() },
                remixAvailable = remixReady,
                remixUnavailableReason = remixUnavailableReason,
            ),
        )
    }

    companion object {
        private const val DEFAULT_ANALYSIS_WAIT_MS = 15_000L
        private const val DEFAULT_FULL_PREFERRED_WAIT_MS = 3_000L
        private const val RECIPE_HISTORY_CAP = 12
        private val ABRUPT_RECIPE_IDS = setOf("F2", "F7", "F9")
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

        internal fun newStemExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread({
                runCatching {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
                }
                runnable.run()
            }, "djmix-stems").apply { isDaemon = true }
        }

        internal fun toCoreDjLevel(level: DjLevel): CoreDjLevel =
            com.music.bitchord.playback.djmix.toCoreDjLevel(level)

        internal fun analysisLevelOf(analysis: TrackAnalysis): AnalysisLevel =
            com.music.bitchord.playback.djmix.analysisLevelOf(analysis)

        internal fun toV3ForPlanner(
            trackId: String,
            analysis: TrackAnalysis,
            phraseMap: SongMap?,
            songMap: SongMap?,
            fallbackDurationSec: Double,
        ): TrackAnalysisV3 =
            com.music.bitchord.playback.djmix.toV3ForPlanner(trackId, analysis, phraseMap, songMap, fallbackDurationSec)

        internal fun sliceLoopRegion(
            region: LoopRegion?,
            sampleRate: Int,
            fromSec: Double,
            toSec: Double,
        ): LoopRegion? = StemWindowPlanner.sliceLoopRegion(region, sampleRate, fromSec, toSec)

        internal fun sliceStemSet(
            stems: StemSet?,
            fromSec: Double,
            toSec: Double,
        ): StemSet? = StemWindowPlanner.sliceStemSet(stems, fromSec, toSec)

        internal fun barSeconds(bpm: Double, fallbackBeatSec: Double): Double =
            StemWindowPlanner.barSeconds(bpm, fallbackBeatSec)

        internal fun analysisSignature(out: TrackAnalysis, inn: TrackAnalysis): String =
            "${out.status}:${out.quality}:${(out.bpm * 10).toInt()}|${inn.status}:${inn.quality}:${(inn.bpm * 10).toInt()}"

        private fun songMapsEquivalent(a: SongMap?, b: SongMap?): Boolean {
            if (a === b) return true
            if (a == null || b == null) return false
            return a.schema == b.schema &&
                a.trackKey == b.trackKey &&
                a.bpm == b.bpm &&
                a.beatsPerBar == b.beatsPerBar &&
                a.downbeats.contentEquals(b.downbeats) &&
                a.gridConfidence == b.gridConfidence &&
                a.tempoStable == b.tempoStable &&
                a.phraseAnchor == b.phraseAnchor &&
                a.phraseLen == b.phraseLen &&
                a.anchors.contentEquals(b.anchors) &&
                a.sections == b.sections &&
                a.sungBars.contentEquals(b.sungBars) &&
                a.vocalEnergy.contentEquals(b.vocalEnergy) &&
                a.firstVocalBar == b.firstVocalBar &&
                a.lastVocalBar == b.lastVocalBar &&
                a.startKind == b.startKind &&
                a.endingKind == b.endingKind &&
                a.contentEndSec == b.contentEndSec &&
                a.drops == b.drops &&
                a.builds == b.builds &&
                a.breakdowns == b.breakdowns &&
                a.chorusRepeats == b.chorusRepeats &&
                a.structureVersion == b.structureVersion
        }

        internal fun paddedWindow(
            coreFrom: Double,
            coreTo: Double,
            padSec: Double,
            trackDurationSec: Double,
            maxRegionSec: Double = AutomixV2Choreo.MAX_STEM_REGION_SEC,
        ): Pair<Double, Double> =
            StemWindowPlanner.paddedWindow(coreFrom, coreTo, padSec, trackDurationSec, maxRegionSec)
    }
}
