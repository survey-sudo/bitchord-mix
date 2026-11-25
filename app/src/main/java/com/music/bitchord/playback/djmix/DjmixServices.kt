package com.music.bitchord.playback.djmix

import android.content.ContentValues
import android.content.Context
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.RenderGeometryV1
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.learning.TasteModel
import com.music.bitchord.djmix.core.learning.TasteSignal
import com.music.bitchord.djmix.core.learning.TasteSnapshot
import com.music.bitchord.djmix.core.machine.MixMachineConfig
import com.music.bitchord.playback.djmix.diagnostics.DiagnosticsBundler
import com.music.bitchord.playback.djmix.diagnostics.DjmixFlags
import com.music.bitchord.playback.djmix.diagnostics.FlagsSnapshot
import com.music.bitchord.playback.djmix.diagnostics.ReleaseFlagsUpdater
import com.music.bitchord.playback.djmix.diagnostics.TelemetryV3Writer
import com.music.bitchord.playback.djmix.store.DjmixAnalysisStore
import com.music.bitchord.playback.djmix.store.DjmixDatabase
import com.music.bitchord.playback.djmix.store.TransitionRecord
import com.music.bitchord.playback.smart.AnalysisLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.util.WeakHashMap

/** A rating is for one archived frozen generation, never the newest row for a track pair. */
data class ExplicitRatingIdentity(
    val recordId: Long,
    val planId: String,
    val generation: Long,
    val recipeId: String,
    val genreClass: String? = null,
)

/** Values survive the release of a player/controller epoch; native handles deliberately do not. */
data class RetainedMixContext(
    val planId: String,
    val generation: Long,
    val outTrackId: String,
    val inTrackId: String,
    val transitionPlanV1: TransitionPlanV1,
    val frozenRecipe: RecipeV2? = null,
    val renderGeometry: RenderGeometryV1? = null,
    val transitionScript: TransitionScriptV1? = null,
    val analysisA: TrackAnalysisV3? = null,
    val analysisB: TrackAnalysisV3? = null,
    val audioCaptureRef: File? = null,
    val recordId: Long? = null,
    val genreClass: String? = null,
    val flagsSnapshot: FlagsSnapshot? = null,
    val tasteSnapshot: TasteSnapshot? = null,
    val assetSha256: Map<String, String> = emptyMap(),
    val modelSha256: Map<String, String> = emptyMap(),
    val recentEvents: List<String> = emptyList(),
    val telemetryLines: List<String> = emptyList(),
    /** Originating player/controller epoch; immutable archives keep it through rebuilds. */
    val playerEpoch: Long? = null,
    val audioCaptureStatus: String = "unavailable:not_registered",
    val audioCaptureMetadata: CapturedAudioMetadata? = null,
) {
    init {
        require(planId == transitionPlanV1.planId && generation == transitionPlanV1.generation)
        require(outTrackId == transitionPlanV1.outTrackId && inTrackId == transitionPlanV1.inTrackId)
        require(frozenRecipe == null || frozenRecipe.id == transitionPlanV1.recipeId)
        require(transitionScript == null || (transitionScript.planId == planId && transitionScript.generation == generation))
        require(analysisA == null || analysisA.trackId == outTrackId)
        require(analysisB == null || analysisB.trackId == inTrackId)
        require(playerEpoch == null || playerEpoch >= 0L)
    }

    fun ratingIdentity(): ExplicitRatingIdentity? = recordId?.let {
        ExplicitRatingIdentity(it, planId, generation, transitionPlanV1.recipeId, genreClass)
    }

    /** Deep detach on the control/worker lane, including arrays/collections in analyses/scripts. */
    fun detached(): RetainedMixContext = copy(
        transitionPlanV1 = detachValue(transitionPlanV1),
        frozenRecipe = frozenRecipe?.let { detachValue(it) },
        renderGeometry = renderGeometry?.let { detachValue(it) },
        transitionScript = transitionScript?.let { detachValue(it) },
        analysisA = analysisA?.let { detachValue(it) },
        analysisB = analysisB?.let { detachValue(it) },
        audioCaptureRef = audioCaptureRef?.absoluteFile,
        flagsSnapshot = flagsSnapshot?.detached(),
        tasteSnapshot = tasteSnapshot?.let { detachValue(it) },
        assetSha256 = assetSha256.toMap(), modelSha256 = modelSha256.toMap(),
        recentEvents = recentEvents.toList(), telemetryLines = telemetryLines.toList(),
    )

    companion object {
        private val snapshotJson = Json { encodeDefaults = true; allowSpecialFloatingPointValues = true }
        private inline fun <reified T> detachValue(value: T): T =
            snapshotJson.decodeFromString(snapshotJson.encodeToString(value))
    }
}

data class RetainedMixRegistryState(
    val active: RetainedMixContext? = null,
    val lastCompleted: RetainedMixContext? = null,
    val playerEpoch: Long = 0L,
)

sealed interface HydrationState {
    data object NotStarted : HydrationState
    data object Loading : HydrationState
    data object Ready : HydrationState
    /** A completed hydration using the deterministic empty baseline, with an observable cause. */
    data class Failed(val message: String) : HydrationState
}

/** Blocking operations run only on the owner's single worker. Implementations may throw. */
interface TastePersistence {
    fun readPreferences(): Map<String, Double>
    fun replacePreferences(weights: Map<String, Double>): Boolean
    fun resetPreferences(): Boolean
    fun readTransition(recordId: Long): TransitionRecord?
    fun updateRating(recordId: Long, rating: Int): Boolean
}

/**
 * One process owner. Inject [persistence] and flows for JVM tests; [create] supplies Android I/O.
 *
 * The model's monitor is the common lock shared with every injected MixExecutor. Never call an
 * executor while holding it. SQLite has one channel consumer, and each queued snapshot carries
 * the reset epoch captured with that model mutation. No I/O runs on this monitor or an audio lane.
 */
class DjmixServices(
    persistence: TastePersistence,
    database: DjmixDatabase? = null,
    store: DjmixAnalysisStore? = null,
    val flags: DjmixFlags? = null,
    val tasteModel: TasteModel = TasteModel(),
    val telemetryWriter: TelemetryV3Writer? = null,
    val diagnosticsBundler: DiagnosticsBundler? = null,
    private val flagsSource: StateFlow<FlagsSnapshot> = flags?.flags ?: MutableStateFlow(FlagsSnapshot.defaults()),
    private val userLearningSource: StateFlow<Boolean> = MutableStateFlow(true),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (String, Throwable?) -> Unit = { message, error -> AnalysisLog.warn(message, error) },
    private val transitionRecorder: ((TerminalTransitionRecord) -> Long)? = null,
    private val captureDirectory: File? = null,
) : AutoCloseable {
    private data class CacheState(
        val database: DjmixDatabase?,
        val store: DjmixAnalysisStore?,
        val persistence: TastePersistence,
    )

    @Volatile private var cacheState = CacheState(database, store, persistence)
    @Volatile private var cacheInitializer: (() -> CacheState)? = null
    /** Null while the Android cache is opening, or when it is unavailable. */
    val database: DjmixDatabase? get() = cacheState.database
    /** Null while opening or unavailable; hydration completion marks a stable cache result. */
    val store: DjmixAnalysisStore? get() = cacheState.store
    private val persistence: TastePersistence get() = cacheState.persistence

    private val workerJob: CompletableJob = SupervisorJob()
    private val scope = CoroutineScope(workerJob + ioDispatcher)
    private var policyJob: Job? = null
    private var releaseFlagsJob: Job? = null
    @Volatile private var closed = false
    private val commands = Channel<Command>(256)
    private val userOverride = MutableStateFlow<Boolean?>(null)
    private var epoch = 0L
    private var resetCount = 0
    private var generationCounter = 0L
    private var hydration: CompletableDeferred<HydrationState>? = null
    private val ratingIdentities = LinkedHashMap<Long, ExplicitRatingIdentity>()
    private val completedIdentities = LinkedHashMap<Pair<String, Long>, Long>()
    private val pcmHashes = WeakHashMap<FloatArray, String>() // Accessed only by the process worker.
    private val eventRings = LinkedHashMap<Pair<String, Long>, ArrayDeque<String>>()
    private val _hydratedState = MutableStateFlow<HydrationState>(HydrationState.NotStarted)
    val hydratedState: StateFlow<HydrationState> = _hydratedState.asStateFlow()
    private val _effectiveLearning = MutableStateFlow(effectivePolicy())
    val effectiveLearningEnabledFlow: StateFlow<Boolean> = _effectiveLearning.asStateFlow()
    private val _persistenceFailure = MutableStateFlow<String?>(null)
    val persistenceFailure: StateFlow<String?> = _persistenceFailure.asStateFlow()
    private val _retained = MutableStateFlow(RetainedMixRegistryState())
    val retainedMixContexts: StateFlow<RetainedMixRegistryState> = _retained.asStateFlow()
    private val captureOwner = captureDirectory?.takeIf { diagnosticsBundler != null }?.let {
        MixCaptureOwner(it, scope, ioDispatcher, ::mergeAudioCaptureResult)
    }

    init {
        synchronized(tasteModel) { tasteModel.learningEnabled = effectivePolicy() }
        policyJob = scope.launch {
            combine(flagsSource, userLearningSource, userOverride) { _, _, _ -> effectivePolicy() }
                .collect { enabled ->
                    synchronized(tasteModel) {
                        tasteModel.learningEnabled = enabled
                        _effectiveLearning.value = enabled
                    }
                }
        }
        scope.launch {
            for (command in commands) {
                try {
                    // Android cache opening precedes hydration and every persistence command.
                    // This receiver runs on ioDispatcher; create() never waits for SQLite.
                    cacheInitializer?.let { initialize ->
                        cacheState = initialize()
                        cacheInitializer = null
                    }
                    when (command) {
                        is Command.Hydrate -> hydrate(command)
                        is Command.Persist -> persist(command)
                        is Command.Reset -> reset(command)
                        is Command.Rate -> rate(command)
                        is Command.Retain -> {
                            val key = command.pair.transitionPlanV1?.let { it.planId to it.generation }
                            val terminal = key != null && synchronized(tasteModel) { completedIdentities[key] == command.playerEpoch }
                            val currentEpoch = isCurrentPlayerEpoch(command.playerEpoch)
                            val context = if (currentEpoch || terminal) retainedContextFor(command.pair, command.flags, command.taste) { samples ->
                                pcmHashes.getOrPut(samples) { preparedPcmSha256(samples) }
                            }?.copy(playerEpoch = command.playerEpoch) else null
                            if (context != null) {
                                if (terminal || command.pair.state == PrepState.DONE) retainCompletedMix(context, command.playerEpoch)
                                else retainActiveMix(context, command.playerEpoch)
                            }
                        }
                        is Command.RetainTerminal -> retainCompletedMix(command.context, command.playerEpoch)
                        is Command.Barrier -> command.done.complete(Unit)
                    }
                } catch (error: Exception) {
                    reportFailure("djmix preferences: command failed", error)
                    when (command) {
                        is Command.Hydrate -> finishHydration(command, emptyMap(), error)
                        is Command.Reset -> {
                            synchronized(tasteModel) {
                                tasteModel.reset()
                                tasteModel.learningEnabled = effectivePolicy()
                                resetCount--
                            }
                            command.done.complete(false)
                        }
                        is Command.Rate -> command.done.complete(false)
                        is Command.Barrier -> command.done.completeExceptionally(error)
                        is Command.Persist -> Unit
                        is Command.Retain, is Command.RetainTerminal -> Unit
                    }
                }
            }
        }
    }

    /** Starts one asynchronous hydration. Planning stays on empty weights until completion. */
    fun hydrateAsync(): Deferred<HydrationState> = synchronized(tasteModel) {
        hydration?.let { return it }
        val done = CompletableDeferred<HydrationState>()
        hydration = done
        _hydratedState.value = HydrationState.Loading
        if (commands.trySend(Command.Hydrate(epoch, done)).isFailure) {
            _hydratedState.value = HydrationState.Failed("services closed")
            done.complete(_hydratedState.value)
        }
        done
    }

    fun setUserLearningEnabled(enabled: Boolean) = synchronized(tasteModel) {
        userOverride.value = enabled
        tasteModel.learningEnabled = effectivePolicy()
        _effectiveLearning.value = effectivePolicy()
    }

    fun flagsSnapshot(): FlagsSnapshot = flagsSource.value.detached()

    fun effectiveLearningEnabled(): Boolean = effectivePolicy()

    /** Safe to inject into planner requests: detached, no storage access, no pre-hydration data. */
    fun plannerWeights(genreClass: String? = null): Map<String, Double> = synchronized(tasteModel) {
        if (!canLearn()) return emptyMap()
        tasteModel.learningEnabled = true
        val snapshot = flagsSnapshot()
        tasteModel.plannerWeights(genreClass).filterKeys(snapshot::isRecipeAllowed).toMap()
    }

    fun tasteSnapshot(): TasteSnapshot = synchronized(tasteModel) {
        tasteModel.snapshot().copy(learningEnabled = effectivePolicy())
    }

    fun currentTasteEpoch(): Long = synchronized(tasteModel) { epoch }

    /** Accept only the exact snapshot still held by the live model, from the matching reset epoch. */
    fun persistTasteStateAsync(weights: Map<String, Double>, expectedEpoch: Long = currentTasteEpoch()) {
        synchronized(tasteModel) {
            if (expectedEpoch != epoch || !hydrationComplete() || resetCount != 0) return
            if (weights != tasteModel.toPreferenceMap()) return
            if (commands.trySend(Command.Persist(expectedEpoch, weights.toMap())).isFailure) {
                _persistenceFailure.value = "djmix preferences: worker unavailable"
            }
        }
    }

    /** Convenience signal path for non-executor consumers; persistence remains asynchronous. */
    fun recordTasteSignal(signal: TasteSignal, recipeId: String, genreClass: String? = null): Boolean = synchronized(tasteModel) {
        if (!canLearn() || !flagsSnapshot().isRecipeAllowed(recipeId)) return false
        tasteModel.learningEnabled = true
        tasteModel.applySignal(signal, recipeId, genreClass)
        commands.trySend(Command.Persist(epoch, tasteModel.toPreferenceMap())).isSuccess
    }

    /** Invalidates queued writes immediately; success means BOTH SQLite and the live model reset. */
    fun resetLearnedPreferences(): Deferred<Boolean> = synchronized(tasteModel) {
        hydrateAsync()
        epoch++
        resetCount++
        val done = CompletableDeferred<Boolean>()
        if (commands.trySend(Command.Reset(epoch, done)).isFailure) {
            resetCount--
            done.complete(false)
        }
        done
    }

    /** Register an exact archival identity when a retained plan is associated with a history row. */
    fun bindRatingIdentity(identity: ExplicitRatingIdentity): Boolean = synchronized(tasteModel) {
        if (identity.recordId <= 0L || identity.generation < 0L || identity.planId.isBlank() || identity.recipeId.isBlank()) return false
        val existing = ratingIdentities[identity.recordId]
        if (existing != null && existing != identity && !(existing.genreClass == null && existing.copy(genreClass = identity.genreClass) == identity)) return false
        ratingIdentities[identity.recordId] = identity
        while (ratingIdentities.size > MAX_RATING_IDENTITIES) ratingIdentities.remove(ratingIdentities.keys.first())
        true
    }

    /** The row is verified by id + plan + recipe + generation before either history or taste changes. */
    fun recordExplicitRating(identity: ExplicitRatingIdentity, rating: Int): Deferred<Boolean> = synchronized(tasteModel) {
        val done = CompletableDeferred<Boolean>()
        if (rating !in -1..1) { done.complete(false); return done }
        hydrateAsync()
        if (commands.trySend(Command.Rate(epoch, identity, rating, done)).isFailure) done.complete(false)
        done
    }

    fun recordExplicitRating(identity: ExplicitRatingIdentity, thumbsUp: Boolean): Deferred<Boolean> =
        recordExplicitRating(identity, if (thumbsUp) 1 else -1)

    /** Barrier for callers/tests that need confirmation of already queued persistence. */
    fun flushAsync(): Deferred<Unit> = synchronized(tasteModel) {
        val done = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Barrier(done)).isFailure) done.completeExceptionally(IllegalStateException("services closed"))
        done
    }

    /** A rebuild advances callback admission without clearing active/last frozen data. */
    fun beginPlayerEpoch(): Long = synchronized(tasteModel) {
        check(!closed) { "services closed" }
        val next = _retained.value.playerEpoch + 1L
        _retained.value = _retained.value.copy(playerEpoch = next)
        next
    }

    fun isCurrentPlayerEpoch(candidate: Long): Boolean = !closed && _retained.value.playerEpoch == candidate

    fun nextMixGeneration(): Long = synchronized(tasteModel) { ++generationCounter }

    fun retainActiveMix(context: RetainedMixContext, playerEpoch: Long? = null): Boolean = retain(context, playerEpoch, false)

    fun retainCompletedMix(context: RetainedMixContext, playerEpoch: Long? = null): Boolean = retain(context, playerEpoch, true)

    fun completeRetainedMix(planId: String, generation: Long, recordId: Long? = null, playerEpoch: Long? = null): Boolean = synchronized(tasteModel) {
        if (playerEpoch != null && !isCurrentPlayerEpoch(playerEpoch)) return false
        val active = _retained.value.active ?: return false
        if (active.planId != planId || active.generation != generation) return false
        val completed = active.copy(recordId = recordId ?: active.recordId)
        completed.ratingIdentity()?.let { if (!bindRatingIdentity(it)) return false }
        _retained.value = _retained.value.copy(active = null, lastCompleted = completed)
        refreshCaptureRetentionLocked()
        true
    }

    /** Publication copies/maps/hashes only on the bounded process worker, never a player callback. */
    fun retainPreparedPairAsync(pair: PreparedPair, playerEpoch: Long): Boolean {
        if (pair.transitionPlanV1 == null || !isCurrentPlayerEpoch(playerEpoch)) return false
        val accepted = commands.trySend(Command.Retain(pair, playerEpoch, flagsSnapshot(), tasteSnapshot())).isSuccess
        if (!accepted) reportFailure("djmix retention: worker unavailable", null)
        return accepted
    }

    fun currentOrLastMix(): RetainedMixContext? = synchronized(tasteModel) {
        (_retained.value.active ?: _retained.value.lastCompleted)?.detached()
    }

    /** Select once, before waiting. No player/machine lookup is allowed after the capture wait. */
    fun flagCurrentOrLastMixAsync(): Deferred<File?> {
        val selected = currentOrLastMix() ?: return CompletableDeferred<File?>().apply { complete(null) }
        return flagSelectedMixAsync(selected)
    }

    /** Both real Flag callers pass their exact selected archival context to this process owner. */
    fun flagSelectedMixAsync(selected: RetainedMixContext): Deferred<File?> {
        if (closed) return CompletableDeferred<File?>().apply { complete(null) }
        val identity = CaptureIdentity.from(selected)
        val owner = captureOwner
        val pinned = identity != null && owner?.pinSelected(identity) == true
        if (identity != null && owner != null && !pinned) return CompletableDeferred<File?>().apply { complete(null) }
        val work = scope.async(start = CoroutineStart.LAZY) {
            val capture = if (identity != null && owner != null) owner.awaitSelected(identity) else null
            var enriched = if (capture != null) withAudioCapture(selected, capture) else selected.copy(
                audioCaptureRef = null, audioCaptureMetadata = null,
                audioCaptureStatus = if (identity == null) "unavailable:identity_missing" else "unavailable:not_configured",
            )
            // The archive only contains detached values. A missing/foreign file never becomes audio.
            if (enriched.audioCaptureRef != null && enriched.audioCaptureRef?.isFile != true) enriched = enriched.copy(
                audioCaptureRef = null, audioCaptureMetadata = null, audioCaptureStatus = "unavailable:file_missing",
            )
            diagnosticsBundler?.bundle(
                retainedContext = enriched,
                flagsSnapshot = selected.flagsSnapshot ?: flagsSnapshot(),
                tasteSnapshot = selected.tasteSnapshot ?: tasteSnapshot(),
                telemetry5min = telemetryWriter?.recentLines().orEmpty() + selected.telemetryLines,
            )
        }
        // Also release pins when a cancelled parent prevents LAZY work from ever entering its body.
        work.invokeOnCompletion { if (pinned && identity != null) owner?.unpinSelected(identity) }
        work.start()
        return work
    }

    /** Audio-only late completion: update matching slots, never call retainCompletedMix/resurrect. */
    private fun mergeAudioCaptureResult(result: MixCaptureResult) = synchronized(tasteModel) {
        val current = _retained.value
        fun merge(value: RetainedMixContext?) = value?.let {
            if (CaptureIdentity.from(it) == result.identity) withAudioCapture(it, result) else it
        }
        _retained.value = current.copy(active = merge(current.active), lastCompleted = merge(current.lastCompleted))
        refreshCaptureRetentionLocked()
    }

    private fun withAudioCapture(context: RetainedMixContext, result: MixCaptureResult): RetainedMixContext {
        val metadata = result.metadata
        val matching = CaptureIdentity.from(context) == result.identity && (metadata == null ||
            (metadata.planId == context.planId && metadata.generation == context.generation && metadata.playerEpoch == context.playerEpoch))
        return if (!matching) context.copy(audioCaptureRef = null, audioCaptureMetadata = null,
            audioCaptureStatus = "unavailable:foreign_identity") else context.copy(
            audioCaptureRef = result.file.takeIf { result.status == "available" }, audioCaptureStatus = result.status,
            audioCaptureMetadata = metadata.takeIf { result.status == "available" },
        )
    }

    private fun refreshCaptureRetentionLocked() {
        val current = _retained.value
        captureOwner?.updateRetained(listOfNotNull(current.active, current.lastCompleted)
            .mapNotNull(CaptureIdentity::from).toSet())
    }

    /** All production controllers use this owner and a distinct callback epoch/generation seed. */
    fun createExecutor(
        adapter: MixDeckAdapter,
        config: MixMachineConfig = MixMachineConfig.DEFAULT,
        playerEpoch: Long = retainedMixContexts.value.playerEpoch,
    ): MixExecutor {
        check(isCurrentPlayerEpoch(playerEpoch)) { "stale or closed player epoch" }
        return MixExecutor(
        deckAdapter = adapter, config = config, store = store, telemetryWriter = telemetryWriter,
        diagnosticsBundler = diagnosticsBundler, tasteModel = tasteModel, flags = flags,
        flagsProvider = ::flagsSnapshot,
        learningPolicyProvider = { synchronized(tasteModel) { isCurrentPlayerEpoch(playerEpoch) && canLearn() } },
        tasteEpochProvider = ::currentTasteEpoch,
        onTasteStateChangedForEpoch = { expected, weights -> persistTasteStateAsync(weights, expected) },
        tasteCallbackDispatcher = ioDispatcher,
        retainedMixContextProvider = ::currentOrLastMix,
        onTransitionRecorded = { identity -> bindTransitionRecord(identity); Unit },
        initialGeneration = nextMixGeneration(),
        playerEpoch = playerEpoch,
        callbackAdmission = { isCurrentPlayerEpoch(playerEpoch) },
        onGenerationChanged = { value -> synchronized(tasteModel) { generationCounter = maxOf(generationCounter, value) } },
        onTerminalEntered = { plan ->
            synchronized(tasteModel) {
                if (isCurrentPlayerEpoch(playerEpoch)) {
                    completedIdentities[plan.planId to plan.generation] = playerEpoch
                    while (completedIdentities.size > MAX_RATING_IDENTITIES) completedIdentities.remove(completedIdentities.keys.first())
                    if (!completeRetainedMix(plan.planId, plan.generation, playerEpoch = playerEpoch)) {
                        // A refused plan may terminate before PLANNED publication; retain only what exists.
                        commands.trySend(Command.RetainTerminal(RetainedMixContext(
                            planId = plan.planId, generation = plan.generation, outTrackId = plan.outTrackId,
                            inTrackId = plan.inTrackId, transitionPlanV1 = plan,
                            flagsSnapshot = flagsSnapshot(), tasteSnapshot = tasteSnapshot(), playerEpoch = playerEpoch,
                        ), playerEpoch))
                    }
                }
            }
        },
        onDebugEvent = { planId, generation, line ->
            synchronized(tasteModel) {
                val key = planId to generation
                val ring = eventRings.getOrPut(key) { ArrayDeque() }
                ring.addLast(line)
                while (ring.size > 256) ring.removeFirst()
                while (eventRings.size > 32) eventRings.remove(eventRings.keys.first())
                val current = _retained.value
                fun withEvents(value: RetainedMixContext?) = value?.takeIf { it.planId == planId && it.generation == generation }
                    ?.copy(recentEvents = ring.toList())
                _retained.value = current.copy(active = withEvents(current.active) ?: current.active,
                    lastCompleted = withEvents(current.lastCompleted) ?: current.lastCompleted)
            }
        },
        transitionRecorder = transitionRecorder,
        captureIntentProvider = { isCurrentPlayerEpoch(playerEpoch) && captureOwner?.available == true && !flagsSnapshot().isKilled("audio_capture") },
        onCaptureSessionAdmitted = { session -> isCurrentPlayerEpoch(playerEpoch) && captureOwner?.admit(session, playerEpoch) == true },
        onCaptureFreezeRequested = { renderer, reason -> captureOwner?.request(renderer, reason); Unit },
        // No callbackAdmission/epoch/closed gate: admitted native retirements outlive their executor.
        onCaptureRetirement = { renderer, delayMs -> captureOwner?.retire(renderer, delayMs) == true },
        )
    }

    private fun bindTransitionRecord(identity: ExplicitRatingIdentity) = synchronized(tasteModel) {
        val current = _retained.value
        val matching = listOfNotNull(current.active, current.lastCompleted).firstOrNull {
            it.planId == identity.planId && it.generation == identity.generation
        }
        val actualIdentity = identity.copy(genreClass = identity.genreClass ?: matching?.genreClass)
        if (!bindRatingIdentity(actualIdentity)) return@synchronized
        fun withRecord(context: RetainedMixContext?): RetainedMixContext? = context?.takeIf {
            it.planId == identity.planId && it.generation == identity.generation && it.transitionPlanV1.recipeId == identity.recipeId
        }?.copy(recordId = actualIdentity.recordId, genreClass = actualIdentity.genreClass)
        _retained.value = current.copy(
            active = withRecord(current.active) ?: current.active,
            lastCompleted = withRecord(current.lastCompleted) ?: current.lastCompleted,
        )
    }

    override fun close() {
        synchronized(tasteModel) {
            if (closed) return
            closed = true
            tasteModel.learningEnabled = false
            _effectiveLearning.value = false
            commands.close()
        }
        releaseFlagsJob?.cancel()
        policyJob?.cancel()
        captureOwner?.close()
        workerJob.complete()
    }

    suspend fun awaitClosed() = workerJob.join()

    private fun effectivePolicy(): Boolean = !closed && (userOverride.value ?: userLearningSource.value) && flagsSource.value.learningEnabled

    private fun hydrationComplete(): Boolean = _hydratedState.value == HydrationState.Ready || _hydratedState.value is HydrationState.Failed

    private fun canLearn(): Boolean = hydrationComplete() && resetCount == 0 && effectivePolicy()

    private fun hydrate(command: Command.Hydrate) {
        val result = runCatching { persistence.readPreferences() }
        finishHydration(command, result.getOrDefault(emptyMap()), result.exceptionOrNull())
    }

    private fun finishHydration(command: Command.Hydrate, weights: Map<String, Double>, error: Throwable?) {
        val result = synchronized(tasteModel) {
            // A reset requested during the read owns the newer epoch; the old read cannot restore it.
            tasteModel.restoreFromPreferenceMap(if (command.epoch == epoch) weights else emptyMap())
            tasteModel.learningEnabled = effectivePolicy()
            (if (error == null) HydrationState.Ready else HydrationState.Failed(error.message ?: error.javaClass.simpleName))
                .also { _hydratedState.value = it }
        }
        if (error != null) reportFailure("djmix preferences: hydration failed; using empty baseline", error)
        command.done.complete(result)
    }

    private fun persist(command: Command.Persist) {
        val accepted = synchronized(tasteModel) {
            command.epoch == epoch && resetCount == 0 && command.weights == tasteModel.toPreferenceMap()
        }
        if (accepted && !persistence.replacePreferences(command.weights)) reportFailure("djmix preferences: snapshot write failed", null)
    }

    private fun reset(command: Command.Reset) {
        val success = try {
            persistence.resetPreferences()
        } catch (error: Exception) {
            reportFailure("djmix preferences: reset failed", error)
            false
        }
        synchronized(tasteModel) {
            if (success) tasteModel.reset()
            tasteModel.learningEnabled = effectivePolicy()
            resetCount--
            command.done.complete(success)
        }
        if (!success) reportFailure("djmix preferences: reset failed", null)
    }

    private fun rate(command: Command.Rate) {
        val identity = command.identity
        val row = runCatching { persistence.readTransition(identity.recordId) }.getOrElse {
            reportFailure("djmix history: rating identity lookup failed", it)
            command.done.complete(false)
            return
        }
        val bound = synchronized(tasteModel) { ratingIdentities[identity.recordId] }
        val archivedGeneration = row?.let { storedGeneration(it.timingsJson) }
        if (row == null || row.id != identity.recordId || row.planId != identity.planId || row.recipe != identity.recipeId ||
            (bound != identity && archivedGeneration != identity.generation) || (bound != null && bound != identity)) {
            command.done.complete(false)
            return
        }
        val ratingStored = runCatching { persistence.updateRating(identity.recordId, command.rating) }.getOrElse {
            reportFailure("djmix history: rating write failed", it)
            false
        }
        if (!ratingStored) { command.done.complete(false); return }
        val weights = synchronized(tasteModel) {
            if (command.epoch != epoch || !canLearn() || command.rating == 0 || !flagsSnapshot().isRecipeAllowed(identity.recipeId)) null
            else {
                tasteModel.learningEnabled = true
                tasteModel.applySignal(if (command.rating > 0) TasteSignal.ExplicitThumbsUp else TasteSignal.ExplicitThumbsDown, identity.recipeId, identity.genreClass)
                tasteModel.toPreferenceMap()
            }
        }
        if (weights != null) persist(Command.Persist(command.epoch, weights))
        command.done.complete(true)
    }

    private fun retain(context: RetainedMixContext, playerEpoch: Long?, completed: Boolean): Boolean {
        val detached = context.detached().let { if (it.playerEpoch == null && playerEpoch != null) it.copy(playerEpoch = playerEpoch) else it }
        return synchronized(tasteModel) {
            val previous = _retained.value
            val key = detached.planId to detached.generation
            if (playerEpoch != null && detached.playerEpoch != null && detached.playerEpoch != playerEpoch) return false
            if (playerEpoch != null && previous.playerEpoch != playerEpoch &&
                !(completed && completedIdentities[key] == playerEpoch)) return false
            if (listOfNotNull(previous.active, previous.lastCompleted).any { detached.generation < it.generation }) return false
            val existing = listOfNotNull(previous.active, previous.lastCompleted).firstOrNull {
                it.planId == detached.planId && it.generation == detached.generation && it.playerEpoch == detached.playerEpoch
            }
            val recorded = ratingIdentities.values.firstOrNull { it.planId == detached.planId && it.generation == detached.generation }
            val merged = detached.copy(
                recordId = detached.recordId ?: existing?.recordId ?: recorded?.recordId,
                frozenRecipe = detached.frozenRecipe ?: existing?.frozenRecipe,
                renderGeometry = detached.renderGeometry ?: existing?.renderGeometry,
                transitionScript = detached.transitionScript ?: existing?.transitionScript,
                analysisA = detached.analysisA ?: existing?.analysisA,
                analysisB = detached.analysisB ?: existing?.analysisB,
                genreClass = detached.genreClass ?: existing?.genreClass ?: recorded?.genreClass,
                audioCaptureRef = detached.audioCaptureRef ?: existing?.audioCaptureRef,
                audioCaptureStatus = existing?.audioCaptureStatus ?: detached.audioCaptureStatus,
                audioCaptureMetadata = existing?.audioCaptureMetadata ?: detached.audioCaptureMetadata,
                assetSha256 = existing?.assetSha256.orEmpty() + detached.assetSha256,
                modelSha256 = existing?.modelSha256.orEmpty() + detached.modelSha256,
                flagsSnapshot = existing?.flagsSnapshot ?: detached.flagsSnapshot,
                tasteSnapshot = existing?.tasteSnapshot ?: detached.tasteSnapshot,
                recentEvents = eventRings[detached.planId to detached.generation]?.toList() ?: detached.recentEvents,
            )
            merged.ratingIdentity()?.let { if (!bindRatingIdentity(it)) return false }
            // Bounded result rendezvous handles a completed copy that preceded retained publication.
            val captureIdentity = CaptureIdentity.from(merged)
            // Protect the incoming key BEFORE reading its file result or publishing the new slot.
            // Maintenance cannot evict a file in the gap between registry publication and refresh.
            captureOwner?.updateRetained((listOfNotNull(previous.active, previous.lastCompleted)
                .mapNotNull(CaptureIdentity::from) + listOfNotNull(captureIdentity)).toSet())
            val audioResult = captureIdentity?.let { captureOwner?.result(it) }
            val retained = when {
                audioResult != null -> withAudioCapture(merged, audioResult)
                captureOwner != null && merged.audioCaptureRef != null && existing == null -> merged.copy(
                    audioCaptureRef = null, audioCaptureMetadata = null, audioCaptureStatus = "unavailable:result_evicted_or_foreign",
                )
                else -> merged
            }
            generationCounter = maxOf(generationCounter, retained.generation)
            val terminal = completed || (retained.planId to retained.generation) in completedIdentities ||
                (previous.lastCompleted?.planId == retained.planId && previous.lastCompleted.generation == retained.generation)
            _retained.value = if (terminal) previous.copy(
                active = previous.active?.takeUnless { it.planId == retained.planId && it.generation == retained.generation }, lastCompleted = retained,
            ) else previous.copy(active = retained)
            refreshCaptureRetentionLocked()
            true
        }
    }

    private fun reportFailure(message: String, error: Throwable?) {
        _persistenceFailure.value = error?.message?.let { "$message: $it" } ?: message
        runCatching { logger(message, error) }
    }

    private sealed interface Command {
        data class Hydrate(val epoch: Long, val done: CompletableDeferred<HydrationState>) : Command
        data class Persist(val epoch: Long, val weights: Map<String, Double>) : Command
        data class Reset(val epoch: Long, val done: CompletableDeferred<Boolean>) : Command
        data class Rate(val epoch: Long, val identity: ExplicitRatingIdentity, val rating: Int, val done: CompletableDeferred<Boolean>) : Command
        data class Retain(val pair: PreparedPair, val playerEpoch: Long, val flags: FlagsSnapshot, val taste: TasteSnapshot) : Command
        data class RetainTerminal(val context: RetainedMixContext, val playerEpoch: Long) : Command
        data class Barrier(val done: CompletableDeferred<Unit>) : Command
    }

    companion object {
        private const val MAX_RATING_IDENTITIES = 1024
        private val json = Json { ignoreUnknownKeys = true }
        @Volatile private var processInstance: DjmixServices? = null

        /** In-memory lookup only; a pre-owner consumer observes the live owner on its next read. */
        internal fun processFlagsSnapshot(): FlagsSnapshot =
            processInstance?.flagsSnapshot() ?: FlagsSnapshot.defaults()

        /** Returns the owner immediately; its I/O worker opens the cache before hydration. */
        fun create(context: Context): DjmixServices = processInstance ?: synchronized(this) {
            processInstance ?: run {
                val app = context.applicationContext
                val memoryPersistence = InMemoryTastePersistence()
                val owner = DjmixServices(
                    persistence = memoryPersistence, database = null, store = null,
                    flags = DjmixFlags(app), telemetryWriter = TelemetryV3Writer(File(app.noBackupFilesDir, "djmix/telemetry")),
                    diagnosticsBundler = DiagnosticsBundler(File(app.getExternalFilesDir(null) ?: app.filesDir, "djmix-reports")),
                    captureDirectory = File(app.noBackupFilesDir, "djmix/captures"),
                )
                owner.cacheInitializer = {
                    val database = DjmixDatabase.openOrNull(app)
                    val store = database?.let { DjmixAnalysisStore(it) }
                    val persistence = if (database != null && store != null) {
                        SqliteTastePersistence(database, store)
                    } else {
                        owner.reportFailure("djmix cache unavailable; taste is session-only", null)
                        memoryPersistence
                    }
                    CacheState(database, store, persistence)
                }
                owner.releaseFlagsJob = owner.scope.launch {
                    ReleaseFlagsUpdater(app, checkNotNull(owner.flags)).refresh()
                }
                processInstance = owner
                owner.hydrateAsync()
                owner
            }
        }

        private fun storedGeneration(timings: String): Long? = runCatching {
            json.parseToJsonElement(timings).jsonObject["generation"]?.jsonPrimitive?.longOrNull
        }.getOrNull()
    }
}

/** Used only after cache opening fails; calls are serialized by the process worker. */
private class InMemoryTastePersistence : TastePersistence {
    private var weights: Map<String, Double> = emptyMap()

    override fun readPreferences(): Map<String, Double> = weights.toMap()

    override fun replacePreferences(weights: Map<String, Double>): Boolean {
        this.weights = weights.toMap()
        return true
    }

    override fun resetPreferences(): Boolean {
        weights = emptyMap()
        return true
    }

    override fun readTransition(recordId: Long): TransitionRecord? = null
    override fun updateRating(recordId: Long, rating: Int): Boolean = false
}

/**
 * Strict preference adapter: the legacy store's empty-on-error read cannot distinguish a broken
 * SQLite read from a new install. This owner reads the same table with exceptions intact and
 * performs full-map transactions rather than applying a learned delta twice.
 */
private class SqliteTastePersistence(private val database: DjmixDatabase, private val store: DjmixAnalysisStore) : TastePersistence {
    override fun readPreferences(): Map<String, Double> {
        val weights = LinkedHashMap<String, Double>()
        database.readableDatabase.query(
            DjmixDatabase.TABLE_PREFERENCE, arrayOf(DjmixDatabase.COL_PREF_KEY, DjmixDatabase.COL_WEIGHT),
            null, null, null, null, null,
        ).use { cursor -> while (cursor.moveToNext()) weights[cursor.getString(0)] = cursor.getDouble(1) }
        return weights
    }

    override fun replacePreferences(weights: Map<String, Double>): Boolean = transaction {
        val db = database.writableDatabase
        db.delete(DjmixDatabase.TABLE_PREFERENCE, "substr(${DjmixDatabase.COL_PREF_KEY}, 1, 2) != ?", arrayOf("__"))
        for ((key, weight) in weights) {
            if (key.isBlank() || key.startsWith("__")) continue
            val values = ContentValues().apply {
                put(DjmixDatabase.COL_PREF_KEY, key)
                put(DjmixDatabase.COL_WEIGHT, TasteModel.clampWeight(weight))
                put(DjmixDatabase.COL_SAMPLE_COUNT, 1)
                put(DjmixDatabase.COL_UPDATED_AT, System.currentTimeMillis())
            }
            check(db.insertOrThrow(DjmixDatabase.TABLE_PREFERENCE, null, values) != -1L)
        }
        true
    }

    override fun resetPreferences(): Boolean = transaction { check(store.resetPreferences()); true }

    override fun readTransition(recordId: Long): TransitionRecord? {
        val columns = arrayOf(
            DjmixDatabase.COL_ID, DjmixDatabase.COL_OUT_ID, DjmixDatabase.COL_IN_ID, DjmixDatabase.COL_PLAN_ID,
            DjmixDatabase.COL_RECIPE, DjmixDatabase.COL_LEVEL, DjmixDatabase.COL_PATH, DjmixDatabase.COL_TIMINGS_JSON,
            DjmixDatabase.COL_OUTCOME, DjmixDatabase.COL_RATING, DjmixDatabase.COL_CREATED_AT,
        )
        return database.readableDatabase.query(DjmixDatabase.TABLE_TRANSITION_HISTORY, columns,
            "${DjmixDatabase.COL_ID} = ?", arrayOf(recordId.toString()), null, null, null, "1").use { cursor ->
            if (!cursor.moveToFirst()) null else TransitionRecord(
                cursor.getLong(0), cursor.getString(1), cursor.getString(2), cursor.getString(3), cursor.getString(4),
                cursor.getString(5), cursor.getString(6), cursor.getString(7), cursor.getString(8), cursor.getInt(9), cursor.getLong(10),
            )
        }
    }

    override fun updateRating(recordId: Long, rating: Int): Boolean = store.updateRating(recordId, rating)

    private fun <T> transaction(block: () -> T): T {
        val db = database.writableDatabase
        db.beginTransaction()
        try {
            return block().also { db.setTransactionSuccessful() }
        } finally { db.endTransaction() }
    }
}
