package com.music.bitchord.playback.djmix

import android.util.Log
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.ENGINE_VERSION
import com.music.bitchord.djmix.core.contracts.ExecutionPath
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.contracts.MixTelemetryV3
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.contracts.TelemetryEventType
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.learning.TasteModel
import com.music.bitchord.djmix.core.learning.TasteSignal
import com.music.bitchord.djmix.core.recipes.RecipeRegistry
import com.music.bitchord.djmix.core.machine.MixEffect
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixMachine
import com.music.bitchord.djmix.core.machine.MixMachineConfig
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.machine.PairKey
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.djmix.diagnostics.DiagnosticsBundler
import com.music.bitchord.playback.djmix.diagnostics.DjmixFlags
import com.music.bitchord.playback.djmix.diagnostics.FlagsSnapshot
import com.music.bitchord.playback.djmix.diagnostics.TelemetryV3Writer
import com.music.bitchord.playback.djmix.store.DjmixAnalysisStore
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** Detached history payload shared by the Android store adapter and deterministic integration tests. */
data class TerminalTransitionRecord(
    val outId: String, val inId: String, val planId: String, val recipe: String, val level: String,
    val path: String, val timingsJson: String, val outcome: String, val createdAtMs: Long,
)

/**
 * Deck surface [MixExecutor] drives. Implemented in Wave 3C-2 by the
 * `CrossfadeController` / `PairPreparer` wiring; deliberately free of any
 * import of `CrossfadeController` so the executor stays testable and the
 * dependency direction stays executor -> adapter.
 *
 * Implementations must be thread-safe: [MixExecutor] may call these from the
 * playback tick thread (render prep must therefore be offloaded by the
 * implementation itself, see [prepareRendererOffThread]).
 */
interface MixDeckAdapter {
    fun startPrepare(
        pair: PairKey,
        generation: Long,
        djLevel: DjLevel,
        positionMs: Long,
        minExitMs: Long?,
        preferNoStemVariant: Boolean,
        excludedRecipeIds: Set<String>,
    )

    fun cancelPrepare(generation: Long)

    fun prepareRendererOffThread(planId: String, generation: Long, useNoStemVariant: Boolean)

    fun armTransition(planId: String, generation: Long, atMs: Long, btLatencyOffsetMs: Long)

    fun startTransition(planId: String, generation: Long)

    fun pauseBothInSync(generation: Long, byUser: Boolean)

    fun resumeBothInSync(generation: Long)

    fun retireDeck(deck: DeckTarget, generation: Long, preserveSurvivor: Boolean)

    fun completeHandover(planId: String, generation: Long)
}

/**
 * App-side executor for the pure [MixMachine] (AE3 Stage 3C).
 *
 * Owns the single mutable [MixState], reduces every [MixEvent] through
 * [MixMachine.reduce] under one lock, executes the returned [MixEffect]s on
 * [deckAdapter] in order, publishes [uiState]/[stateFlow]/
 * [isTransitioningFlow], logs every `Emit` to [AutomixTelemetry], and records
 * terminal DONE/FAILED transitions into [store].
 *
 * AE3 M8 additions (all constructor parameters are optional and default to
 * "off", keeping every existing caller and test source-compatible):
 * `Emit` and terminal DONE/FAILED transitions also land in the rotating
 * [TelemetryV3Writer] JSONL sink; [flagCurrentOrLastMix] writes the owner's
 * "Flag this mix" zip through [DiagnosticsBundler]; and mix outcomes, skips,
 * seeks and explicit thumbs are recorded into the [TasteModel] — always
 * behind the [DjmixFlags] snapshot's learning gate and recipe allow-list, so
 * safe mode / kill switch / disabled-learning states are honored cleanly.
 *
 * The lock is held while effects run: `synchronized` is re-entrant, so an
 * adapter that synchronously dispatches a completion event (e.g. a test
 * double, or a renderer that prepares inline) recurses safely against the
 * already-updated state, and concurrent ticks cannot interleave effects.
 */
class MixExecutor(
    private val deckAdapter: MixDeckAdapter,
    private val config: MixMachineConfig = MixMachineConfig.DEFAULT,
    private val store: DjmixAnalysisStore? = null,
    // ---- AE3 M8: diagnostics, telemetry, taste and flags (null = off) ----------
    private val telemetryWriter: TelemetryV3Writer? = null,
    private val diagnosticsBundler: DiagnosticsBundler? = null,
    private val tasteModel: TasteModel? = null,
    private val flags: DjmixFlags? = null,
    private val flagsProvider: () -> FlagsSnapshot = { flags?.flags?.value ?: FlagsSnapshot.defaults() },
    private val learningPolicyProvider: () -> Boolean = { true },
    private val onTasteStateChanged: ((Map<String, Double>) -> Unit)? = null,
    private val tasteEpochProvider: () -> Long = { 0L },
    private val onTasteStateChangedForEpoch: ((Long, Map<String, Double>) -> Unit)? = null,
    private val tasteCallbackDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val retainedMixContextProvider: () -> RetainedMixContext? = { null },
    private val recipeProvider: ((String) -> RecipeV2?)? = RecipeRegistry::byId,
    private val onTransitionRecorded: ((ExplicitRatingIdentity) -> Unit)? = null,
    private val clockMs: () -> Long = System::currentTimeMillis,
    initialGeneration: Long = 0L,
    private val playerEpoch: Long? = null,
    private val callbackAdmission: () -> Boolean = { true },
    private val onGenerationChanged: (Long) -> Unit = {},
    private val onTerminalEntered: (TransitionPlanV1) -> Unit = {},
    private val onDebugEvent: (String, Long, String) -> Unit = { _, _, _ -> },
    private val transitionRecorder: ((TerminalTransitionRecord) -> Long)? = null,
    private val captureIntentProvider: () -> Boolean = { false },
    private val onCaptureSessionAdmitted: (LoopMixSession) -> Boolean = { false },
    private val onCaptureFreezeRequested: (LoopMixRenderer, String) -> Unit = { _, _ -> },
    private val onCaptureRetirement: (LoopMixRenderer, Long) -> Boolean = { _, _ -> false },
    private val monotonicClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lock = Any()

    private var state: MixState = MixState.Idle(generation = initialGeneration)
    private var closed = false
    private var shownCountdown: ShownCountdown? = null

    internal fun capturePreparationEpoch(): Long? = synchronized(lock) {
        playerEpoch?.takeIf { !closed && callbackAdmission() && captureIntentProvider() }
    }

    internal fun admitCaptureSession(session: LoopMixSession): Boolean = synchronized(lock) {
        !closed && callbackAdmission() && session.captureConfigured && onCaptureSessionAdmitted(session)
    }

    internal fun requestCaptureFreeze(renderer: LoopMixRenderer, reason: String) {
        // Already admitted captures belong to the process, including after this executor closes.
        onCaptureFreezeRequested(renderer, reason)
    }

    internal fun retireCaptureRenderer(renderer: LoopMixRenderer, delayMs: Long = 0L): Boolean =
        onCaptureRetirement(renderer, delayMs)
    private val pendingTerminalWrites = AtomicInteger()
    private val asyncWorkFailures = AtomicLong()

    /** The plan of the last terminal (DONE/FAILED) mix, for [flagCurrentOrLastMix]. */
    private var lastMixPlan: TransitionPlanV1? = null

    /** Bounded in-memory ring of recent app events (JSONL lines) for a flag bundle. */
    private val recentEvents = ArrayDeque<String>()
    private val completedMixes = LinkedHashSet<String>()
    private val implicitTasteTracker = ImplicitTasteTracker()
    private val workerJob: CompletableJob = SupervisorJob()
    private val tasteScope = CoroutineScope(workerJob + tasteCallbackDispatcher)
    private val tasteChanges = Channel<TastePersistenceSnapshot>(Channel.CONFLATED)
    private val terminalWrites = Channel<TerminalWrite>(TERMINAL_QUEUE_CAPACITY)
    private val telemetryTasks = Channel<() -> Unit>(256)

    private val _uiState = MutableStateFlow(MixMachine.uiStateFor(state, config))
    val uiState: StateFlow<MixUiState> = _uiState.asStateFlow()

    private val _stateFlow = MutableStateFlow(state)
    val stateFlow: StateFlow<MixState> = _stateFlow.asStateFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioningFlow: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    init {
        tasteScope.launch {
            for (change in tasteChanges) {
                runCatching { onTasteStateChangedForEpoch?.invoke(change.epoch, change.weights) }
                    .onFailure { recordAsyncFailure("taste_epoch_callback", it) }
                runCatching { onTasteStateChanged?.invoke(change.weights) }
                    .onFailure { recordAsyncFailure("taste_callback", it) }
            }
        }
        tasteScope.launch {
            for (task in telemetryTasks) runCatching { task() }.onFailure { recordAsyncFailure("telemetry_callback", it) }
        }
        tasteScope.launch {
            for (write in terminalWrites) {
                try {
                    recordTerminal(write)
                } catch (error: Exception) {
                    recordAsyncFailure("terminal_history", error)
                } finally { pendingTerminalWrites.decrementAndGet() }
            }
        }
    }

    private fun recordTerminal(write: TerminalWrite) {
        if (transitionRecorder == null && store == null) return
        val record = TerminalTransitionRecord(
            outId = write.pair.outTrackId, inId = write.pair.inTrackId, planId = write.plan.planId,
            recipe = write.plan.recipeId, level = write.djLevel.name, path = write.path,
            timingsJson = "{\"frozenExitMs\":${write.plan.frozenExitMs}," +
                "\"frozenEntryMs\":${write.plan.frozenEntryMs},\"overlapDurationMs\":${write.plan.overlapDurationMs}," +
                "\"barDurationMs\":${write.plan.barDurationMs},\"generation\":${write.generation}" +
                (playerEpoch?.let { ",\"playerEpoch\":$it" } ?: "") + "}",
            outcome = write.outcome, createdAtMs = write.createdAtMs,
        )
        val recordId = transitionRecorder?.invoke(record) ?: store?.recordTransition(
            outId = record.outId, inId = record.inId, planId = record.planId, recipe = record.recipe,
            level = record.level, path = record.path, timingsJson = record.timingsJson,
            outcome = record.outcome, createdAtMs = record.createdAtMs,
        ) ?: -1L
        check(recordId > 0L) { "terminal history write failed" }
        val retained = retainedMixContextProvider()?.takeIf { it.planId == write.plan.planId && it.generation == write.generation }
        onTransitionRecorded?.invoke(ExplicitRatingIdentity(recordId, write.plan.planId, write.generation, write.plan.recipeId, retained?.genreClass))
    }

    private fun recordAsyncFailure(operation: String, error: Throwable) {
        asyncWorkFailures.incrementAndGet()
        AutomixTelemetry.recordFailure(operation, error)
    }

    /** Current machine state (lock-safe). */
    fun currentState(): MixState = synchronized(lock) { state }

    /**
     * Reduces [event], updates all flows, and executes every effect.
     * Returns the new state and the exact effect list the machine produced.
     * Never throws for a misbehaving adapter: a failed effect is logged and
     * the remaining effects still run.
     */
    fun dispatch(event: MixEvent): Pair<MixState, List<MixEffect>> {
        val changes = ArrayList<TastePersistenceSnapshot>(1)
        val terminalRecords = ArrayList<TerminalWrite>(1)
        val result = synchronized(lock) {
            if (closed || !callbackAdmission()) {
                implicitTasteTracker.invalidate()
                return state to emptyList()
            }
            // Admit no new opportunity while the bounded archival lane is saturated.
            if (event is MixEvent.NextKnown && pendingTerminalWrites.get() >= TERMINAL_QUEUE_CAPACITY) {
                asyncWorkFailures.incrementAndGet()
                return state to emptyList()
            }
            val previous = state
            // A skip names the audible track before reduction/effects can advance the pair.
            recordPostCompletionSkip(previous, event, changes)
            val (next, initialEffects) = MixMachine.reduce(previous, event, config)
            val policyRefusal = policyRefusal(previous, event)
            val reduced = if (policyRefusal != null && event !is MixEvent.RendererError && next !is MixState.Failed) {
                val error = MixEvent.RendererError(
                    code = FailureKind.RENDERER_PREPARE_ERROR,
                    message = "policy_refused:$policyRefusal",
                )
                val (failed, failureEffects) = MixMachine.reduce(next, error, config)
                failed to failureEffects
            } else next to initialEffects
            val finalNext = reduced.first
            val effects = if (policyRefusal != null && event !is MixEvent.RendererError) reduced.second else initialEffects
            state = finalNext
            onGenerationChanged(finalNext.generation)
            _stateFlow.value = finalNext
            // Recompute rather than trusting only ShowUi payloads: the
            // machine emits ShowUi on most paths but not on every pause/
            // resume/tick path, and uiStateFor is the single source of truth.
            _uiState.value = MixMachine.uiStateFor(finalNext, config)
            _isTransitioning.value = finalNext is MixState.Running || finalNext is MixState.HandingOver

            checkCountdownHonesty(previous, event, effects)

            for (effect in effects) {
                val failed = runCatching { execute(effect) }.exceptionOrNull()
                if (failed != null) {
                    enqueueTelemetry {
                        AutomixTelemetry.event(
                            "mix.executor.effect_failed",
                            mapOf(
                                "engine" to ENGINE_VERSION,
                                "eventType" to "mix.executor.effect_failed",
                                "state" to finalNext.stateKind.name,
                                "generation" to finalNext.generation,
                                "planId" to finalNext.plan?.planId,
                                "effect" to effect::class.simpleName,
                                "detail" to (failed.message ?: failed::class.simpleName),
                            ),
                        )
                    }
                    emitAdapterFailure(effect, failed, finalNext)
                    if (effect is MixEffect.Arm || effect is MixEffect.Start || effect is MixEffect.Handover) {
                        val failedState = state
                        if (failedState !is MixState.Done && failedState !is MixState.Failed) {
                            dispatch(
                                MixEvent.RendererError(
                                    code = FailureKind.RENDERER_PREPARE_ERROR,
                                    message = "adapter_${effect::class.simpleName?.lowercase()}_failed:${failed.message ?: failed::class.simpleName}",
                                ),
                            )
                        }
                        break
                    }
                }
            }

            // Observe after effects; Start is only a request, not proof of a fire.
            runCatching { checkShownCountdown(event, effects) }

            // AE3 M8: recent-event ring for "Flag this mix", implicit taste
            // signals from user events, and terminal records.
            recordDebugEvent(event, state)
            recordUserTasteSignals(previous, event, state, changes)
            recordTerminalIfEntered(previous, event, state, effects, changes, terminalRecords)
            if (policyRefusal != null) emitPolicyRefusal(finalNext, policyRefusal)
            changes.forEach { tasteChanges.trySend(it) }
            terminalRecords.forEach {
                pendingTerminalWrites.incrementAndGet()
                if (terminalWrites.trySend(it).isFailure) {
                    pendingTerminalWrites.decrementAndGet()
                    asyncWorkFailures.incrementAndGet()
                }
            }
            state to effects
        }
        return result
    }

    /** Stops callback work when the epoch owner is disposed. */
    fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            implicitTasteTracker.invalidate()
            tasteChanges.close()
            terminalWrites.close()
            telemetryTasks.close()
            // Completing the parent drains admitted work and then releases every epoch worker.
            workerJob.complete()
        }
    }

    /** Nonblocking lifecycle join for tests/control lanes after [close]. */
    suspend fun awaitClosed() = workerJob.join()

    fun asyncWorkFailureCount(): Long = asyncWorkFailures.get()

    private fun enqueueTelemetry(task: () -> Unit) {
        if (telemetryTasks.trySend(task).isFailure) asyncWorkFailures.incrementAndGet()
    }

    /** Confirms earlier telemetry callbacks have drained; intended for tests and control lanes. */
    fun flushTelemetryAsync(): Deferred<Unit> {
        val done = CompletableDeferred<Unit>()
        if (telemetryTasks.trySend { done.complete(Unit) }.isFailure) {
            done.completeExceptionally(IllegalStateException("executor closed"))
        }
        return done
    }

    private fun policyRefusal(previous: MixState, event: MixEvent): String? {
        if (event !is MixEvent.PlanFrozen) return null
        if (previous !is MixState.Preparing && previous !is MixState.Moved) return null
        if (event.generation != previous.generation || event.plan.generation != previous.generation ||
            previous.pair?.outTrackId != event.plan.outTrackId || previous.pair?.inTrackId != event.plan.inTrackId
        ) return null
        val snapshot = runCatching { flagsProvider().detached() }
            .getOrElse { FlagsSnapshot(safeMode = true, learningEnabled = false) }
        val candidate = event.plan
        val recipe = runCatching { recipeProvider?.invoke(candidate.recipeId) }.getOrNull()
        val excluded = when (previous) {
            is MixState.Preparing -> previous.excludedRecipeIds
            is MixState.Moved -> previous.excludedRecipeIds
            else -> emptySet()
        }
        return when {
            candidate.recipeId in excluded -> "recipe_excluded:${candidate.recipeId}"
            recipeProvider != null && recipe == null -> "recipe_unavailable:${candidate.recipeId}"
            else -> snapshot.executionRefusal(candidate, recipe)
        }
    }

    private fun emitPolicyRefusal(state: MixState, refusal: String) {
        enqueueTelemetry {
            runCatching {
                telemetryWriter?.emit(
                    eventType = TelemetryEventType.MIX_REFUSED,
                    state = state.stateKind.name,
                    planId = state.plan?.planId,
                    generation = state.generation,
                    outId = state.pair?.outTrackId.orEmpty(),
                    inId = state.pair?.inTrackId.orEmpty(),
                    recipeId = state.plan?.recipeId,
                    reason = RefusalReason.RENDERER_UNAVAILABLE,
                    detail = "policy_refused:$refusal",
                )
            }
        }
        enqueueTelemetry {
            AutomixTelemetry.event("mix.executor.policy_refused", mapOf("reason" to refusal, "planId" to state.plan?.planId, "generation" to state.generation))
        }
    }

    private fun emitAdapterFailure(effect: MixEffect, error: Throwable, state: MixState) {
        val critical = effect is MixEffect.Arm || effect is MixEffect.Start || effect is MixEffect.Handover
        enqueueTelemetry {
            runCatching {
                telemetryWriter?.emit(
                    eventType = if (critical) TelemetryEventType.MIX_FAILED else TelemetryEventType.MIX_REFUSED,
                    state = state.stateKind.name,
                    planId = state.plan?.planId,
                    generation = state.generation,
                    outId = state.pair?.outTrackId.orEmpty(),
                    inId = state.pair?.inTrackId.orEmpty(),
                    recipeId = state.plan?.recipeId,
                    path = if (critical) ExecutionPath.CLASSIC_RENDERER_UNAVAILABLE else null,
                    reason = RefusalReason.RENDERER_UNAVAILABLE,
                    detail = "effect=${effect::class.simpleName} error=${error.message ?: error::class.simpleName}",
                )
            }
        }
    }

    private fun execute(effect: MixEffect) {
        when (effect) {
            is MixEffect.StartPrepare -> deckAdapter.startPrepare(
                pair = effect.pair,
                generation = effect.generation,
                djLevel = effect.djLevel,
                positionMs = effect.positionMs,
                minExitMs = effect.minExitMs,
                preferNoStemVariant = effect.preferNoStemVariant,
                excludedRecipeIds = effect.excludedRecipeIds,
            )
            is MixEffect.CancelPrepare -> deckAdapter.cancelPrepare(effect.generation)
            is MixEffect.PrepareRenderer -> deckAdapter.prepareRendererOffThread(
                planId = effect.planId,
                generation = effect.generation,
                useNoStemVariant = effect.useNoStemVariant,
            )
            is MixEffect.Arm -> deckAdapter.armTransition(
                planId = effect.planId,
                generation = effect.generation,
                atMs = effect.atMs,
                btLatencyOffsetMs = effect.btLatencyOffsetMs,
            )
            is MixEffect.Start -> deckAdapter.startTransition(
                planId = effect.planId,
                generation = effect.generation,
            )
            is MixEffect.PauseSync -> deckAdapter.pauseBothInSync(
                generation = effect.generation,
                byUser = effect.byUser,
            )
            is MixEffect.ResumeSync -> deckAdapter.resumeBothInSync(effect.generation)
            is MixEffect.Retire -> deckAdapter.retireDeck(
                deck = effect.deck,
                generation = effect.generation,
                preserveSurvivor = effect.preserveSurvivor,
            )
            is MixEffect.Handover -> deckAdapter.completeHandover(
                planId = effect.planId,
                generation = effect.generation,
            )
            is MixEffect.ShowUi -> Unit // Already published via uiStateFor above.
            is MixEffect.Emit -> {
                // AE3 M8: the structured record goes to the rotating JSONL sink
                // (and the 5-minute ring a flag bundle ships) in addition to the
                // existing AutomixTelemetry logging. Writer failures must never
                // break effect execution.
                enqueueTelemetry {
                    runCatching { telemetryWriter?.write(effect.telemetry) }
                    logTelemetry(effect.telemetry)
                }
            }
        }
    }

    private fun logTelemetry(telemetry: MixTelemetryV3) {
        AutomixTelemetry.event(
            telemetry.eventType.wireName,
            mapOf(
                "engine" to ENGINE_VERSION,
                "eventType" to telemetry.eventType.wireName,
                "timestampMs" to telemetry.timestampMs,
                "planId" to telemetry.planId,
                "generation" to telemetry.generation,
                "state" to telemetry.state,
                "outId" to telemetry.outId,
                "inId" to telemetry.inId,
                "djLevel" to telemetry.djLevel?.name,
                "path" to telemetry.path?.name,
                "recipeId" to telemetry.recipeId,
                "countdownBroken" to telemetry.countdownBroken,
                "reason" to telemetry.reason?.name,
                "detail" to telemetry.detail,
            ),
        )
    }

    /**
     * Countdown honesty: a `Start` fired from `Armed` on a [MixEvent.Tick]
     * must land within [MixMachineConfig.countdownToleranceMs] of
     * `armAtMs`. The machine only fires at or after `armAtMs - tolerance`,
     * so a late tick that jumps well past the arm instant is the one way to
     * exceed the window; log it as `mix.countdown.broken` (target: never).
     */
    private fun checkCountdownHonesty(previous: MixState, event: MixEvent, effects: List<MixEffect>) {
        if (event !is MixEvent.Tick) return
        if (previous !is MixState.Armed) return
        if (effects.none { it is MixEffect.Start }) return
        val driftMs = abs(event.positionMs - previous.armAtMs)
        if (driftMs <= config.countdownToleranceMs) return
        enqueueTelemetry {
            AutomixTelemetry.event(
                "mix.countdown.broken",
                mapOf(
                    "engine" to ENGINE_VERSION,
                    "eventType" to "mix.countdown.broken",
                    "planId" to previous.plan.planId,
                    "generation" to previous.generation,
                    "state" to previous.stateKind.name,
                    "countdownBroken" to true,
                    "detail" to "positionMs=${event.positionMs} armAtMs=${previous.armAtMs} driftMs=$driftMs toleranceMs=${config.countdownToleranceMs}",
                ),
            )
        }
    }

    /**
     * Watch the deadline actually advertised in READY/ARMED, including a Start
     * whose adapter never fires. The UI projection clamps its text to >= 1s;
     * shown=0 denotes that advertised deadline expiring, not a synthetic UI edit.
     * Use the existing post-deadline playback ticks, with no timer or recovery.
     */
    private fun checkShownCountdown(event: MixEvent, effects: List<MixEffect>) {
        if (event is MixEvent.UserSeek || event is MixEvent.UserSkip || event is MixEvent.QueueEdited ||
            state is MixState.Idle || state is MixState.Suspended
        ) {
            shownCountdown = null
            return
        }
        val pending = shownCountdown
        if (pending != null && !pending.resolved && state.pair == pending.pair && state.generation == pending.generation) {
            val fire = MixFireObservation.latest(pending.pair)?.takeIf {
                it.sequence > pending.fireSequence &&
                    (it.generation == null || it.generation == pending.generation) &&
                    (it.planId.isNullOrBlank() || it.planId == pending.planId)
            }
            val positionMs = fire?.positionMs ?: (event as? MixEvent.Tick)?.positionMs
            val playingTick = event is MixEvent.Tick && event.isPlaying && !state.userPaused
            // Let this dispatch's Start reach the existing controller fire seam first.
            val overdue = positionMs != null && positionMs - pending.targetMs > COUNTDOWN_FIRE_GRACE_MS
            if (overdue && (fire != null || (playingTick && effects.none { it is MixEffect.Start }))) {
                pending.resolved = true
                Log.i("BitChordMix", "BitChordMix countdown.broken shown=0 state=${state.stateKind.name}")
            }
            if (fire != null) pending.resolved = true
        }
        val ui = _uiState.value
        val targetMs = when (val current = state) {
            is MixState.Ready -> current.plan.frozenExitMs
            is MixState.Armed -> current.armAtMs
            else -> null
        }
        val plan = state.plan
        val pair = state.pair
        if (ui.countdownVisible && ui.countdownSec != null && targetMs != null && plan != null && pair != null) {
            if (pending == null || pending.planId != plan.planId || pending.generation != state.generation || pending.targetMs != targetMs) {
                shownCountdown = ShownCountdown(pair, plan.planId, state.generation, targetMs, MixFireObservation.latest(pair)?.sequence ?: 0L)
            }
        } else if (state !is MixState.Running && state !is MixState.HandingOver) {
            shownCountdown = null
        }
    }

    /**
     * Records DONE/FAILED exactly once per entry into a terminal state.
     *
     * AE3 M8: also remembers the terminal plan for [flagCurrentOrLastMix],
     * writes a terminal `mix.done` / `mix.failed` JSONL record when this
     * dispatch produced no matching `Emit` of its own (paths that did are
     * already covered by the `Emit` routing, so the record is never
     * duplicated), and feeds the outcome into [TasteModel].
     */
    private fun recordTerminalIfEntered(
        previous: MixState,
        event: MixEvent,
        next: MixState,
        effects: List<MixEffect>,
        changes: MutableList<TastePersistenceSnapshot>,
        terminalRecords: MutableList<TerminalWrite>,
    ) {
        val terminal = when (next) {
            is MixState.Done -> "DONE"
            is MixState.Failed -> "FAILED"
            else -> if (previous.plan != null && next.plan == null) "CANCELLED" else null
        } ?: return
        if (previous is MixState.Done || previous is MixState.Failed) return
        val terminalState = if (terminal == "CANCELLED") previous else next
        val plan = terminalState.plan ?: return
        val key = "${plan.planId}:${plan.generation}"
        if (!completedMixes.add(key)) return
        while (completedMixes.size > TERMINAL_DEDUPE_LIMIT) completedMixes.remove(completedMixes.first())
        lastMixPlan = plan
        if (event is MixEvent.HandoverComplete && previous is MixState.HandingOver && next is MixState.Done &&
            previous.generation == next.generation && previous.plan.planId == next.plan.planId
        ) {
            rememberSuccessfulCompletion(next)
        } else {
            implicitTasteTracker.invalidate()
        }
        onTerminalEntered(plan)
        val alreadyEmitted = effects.any { effect ->
            effect is MixEffect.Emit && (
                (terminal == "DONE" && effect.telemetry.eventType == TelemetryEventType.MIX_DONE) ||
                    (terminal == "FAILED" && effect.telemetry.eventType == TelemetryEventType.MIX_FAILED) ||
                    (terminal == "CANCELLED" && effect.telemetry.eventType == TelemetryEventType.MIX_REFUSED)
                )
        }
        val pair = terminalState.pair
        if (!alreadyEmitted) {
            enqueueTelemetry {
                recordTerminalTelemetry(terminalState, terminal)
            }
        }
        if (pair != null) {
            val path = when (next) {
                is MixState.Failed -> "CLASSIC_RENDERER_UNAVAILABLE"
                else -> if (plan.isLastResort) "LAST_RESORT_MUSICAL_EXIT" else "PREPARER_FROZEN"
            }
            terminalRecords += TerminalWrite(plan, pair, terminalState.djLevel, plan.generation, terminal, path, clockMs())
        }
        if (event is MixEvent.HandoverComplete) recordMixOutcomeTaste(next, changes)
    }

    // ---- AE3 M8: telemetry / taste / "Flag this mix" helpers ---------------------

    /** Effective flag snapshot; code defaults when no flag source is wired. */
    private fun flagsSnapshot(): FlagsSnapshot = runCatching { flagsProvider().detached() }
        .getOrElse { flags?.flags?.value?.detached() ?: FlagsSnapshot(safeMode = true, learningEnabled = false) }

    /**
     * Taste-learning gate: honors the [FlagsSnapshot] cleanly — learning only
     * while `learningEnabled`, and only for recipes the snapshot allows, so
     * safe mode (S1/S5 straight blends only) or a pinned recipe can never be
     * steered by signals the flags disallow. Null taste model = off.
     */
    private fun tasteSignalAllowed(recipeId: String?): Boolean {
        if (recipeId.isNullOrBlank() || tasteModel == null) return false
        val snapshot = flagsSnapshot()
        return snapshot.learningEnabled && runCatching { learningPolicyProvider() }.getOrDefault(false) && snapshot.isRecipeAllowed(recipeId)
    }

    /** Model mutation and detached persistence snapshot share the owner's explicit monitor. */
    private fun applyTasteSignal(signal: TasteSignal, recipeId: String, genreClass: String? = null, changes: MutableList<TastePersistenceSnapshot>) {
        val model = tasteModel ?: return
        runCatching {
            synchronized(model) {
                if (!tasteSignalAllowed(recipeId)) return@synchronized
                model.learningEnabled = true
                model.applySignal(signal, recipeId, genreClass)
                changes += TastePersistenceSnapshot(tasteEpochProvider(), model.toPreferenceMap())
            }
        }
    }

    /** A clean DONE is one full playthrough of its recipe (Part B8.1 streak logic). */
    private fun recordMixOutcomeTaste(next: MixState, changes: MutableList<TastePersistenceSnapshot>) {
        val model = tasteModel ?: return
        if (next !is MixState.Done) return
        val recipeId = next.plan?.recipeId ?: return
        runCatching {
            synchronized(model) {
                if (!tasteSignalAllowed(recipeId)) return@synchronized
                model.learningEnabled = true
                model.noteFullPlaythrough(recipeId)
                changes += TastePersistenceSnapshot(tasteEpochProvider(), model.toPreferenceMap())
            }
        }
    }

    /** Only an admitted clean handover establishes a post-completion skip window. */
    private fun rememberSuccessfulCompletion(next: MixState.Done) {
        val model = tasteModel
        val completed = if (model == null) null else runCatching {
            synchronized(model) {
                val plan = next.plan
                if (closed || !callbackAdmission() || !tasteSignalAllowed(plan.recipeId) ||
                    plan.generation != next.generation || plan.outTrackId != next.pair.outTrackId ||
                    plan.inTrackId != next.pair.inTrackId
                ) return@synchronized null
                ImplicitTasteTracker.CompletedMix(
                    planId = plan.planId, generation = plan.generation, recipeId = plan.recipeId,
                    outTrackId = plan.outTrackId, inTrackId = plan.inTrackId,
                    completedAtMs = monotonicClockMs(), tasteEpoch = tasteEpochProvider(),
                )
            }
        }.getOrNull()
        implicitTasteTracker.recordCompletion(next.generation, completed)
    }

    /** Consume before effects; reset validation and model mutation share the model monitor. */
    private fun recordPostCompletionSkip(previous: MixState, event: MixEvent, changes: MutableList<TastePersistenceSnapshot>) {
        if (!implicitTasteTracker.hasPendingCompletion) return
        val model = tasteModel ?: return
        runCatching {
            synchronized(model) {
                val completed = implicitTasteTracker.observe(
                    state = previous,
                    nowMs = runCatching { monotonicClockMs() }.getOrNull(),
                    tasteEpoch = runCatching { tasteEpochProvider() }.getOrNull(),
                    executorCurrent = !closed && callbackAdmission(),
                    learningAllowed = ::tasteSignalAllowed,
                    userSkip = event is MixEvent.UserSkip,
                ) ?: return@synchronized
                applyTasteSignal(TasteSignal.EarlySkipWithin15s, completed.recipeId, changes = changes)
            }
        }.onFailure { implicitTasteTracker.invalidate() }
    }

    /**
     * Terminal `mix.done` / `mix.failed` record for this transition; only
     * called when the machine produced no matching `Emit` itself.
     */
    private fun recordTerminalTelemetry(next: MixState, terminal: String) {
        val writer = telemetryWriter ?: return
        val plan = next.plan
        val pair = next.pair
        val path = when {
            next is MixState.Failed -> ExecutionPath.CLASSIC_RENDERER_UNAVAILABLE
            plan?.isLastResort == true -> ExecutionPath.LAST_RESORT_MUSICAL_EXIT
            else -> ExecutionPath.PREPARER_FROZEN
        }
        runCatching {
            writer.emit(
                eventType = when (terminal) {
                    "DONE" -> TelemetryEventType.MIX_DONE
                    "CANCELLED" -> TelemetryEventType.MIX_REFUSED
                    else -> TelemetryEventType.MIX_FAILED
                },
                state = next.stateKind.name,
                planId = plan?.planId,
                generation = next.generation,
                outId = pair?.outTrackId.orEmpty(),
                inId = pair?.inTrackId.orEmpty(),
                djLevel = next.djLevel,
                path = path,
                recipeId = plan?.recipeId,
                reason = (next as? MixState.Failed)?.reason,
                detail = when (next) {
                    is MixState.Failed -> next.message.ifBlank { "outcome=failed kind=${next.kind.name}" }
                    else -> "outcome=${terminal.lowercase()}"
                },
            )
        }
    }

    /**
     * A backwards seek while the blend runs is one [TasteSignal.SeekBackDuringMix].
     * Post-completion skips are handled separately before effects advance the pair.
     */
    private fun recordUserTasteSignals(
        previous: MixState,
        event: MixEvent,
        next: MixState,
        changes: MutableList<TastePersistenceSnapshot>,
    ) {
        if (tasteModel == null) return
        val inTransition = previous is MixState.Running || previous is MixState.HandingOver ||
            next is MixState.Running || next is MixState.HandingOver
        val signalPlan = previous.plan ?: next.plan
        val recipeId = signalPlan?.recipeId ?: lastMixPlan?.recipeId ?: return
        when (event) {
            is MixEvent.UserSeek -> run {
                if (!inTransition) return@run
                val positionMs = when (val active = previous) {
                    is MixState.Running -> active.lastPositionMs
                    is MixState.HandingOver -> active.lastPositionMs
                    else -> when (val updated = next) {
                        is MixState.Running -> updated.lastPositionMs
                        is MixState.HandingOver -> updated.lastPositionMs
                        else -> return@run
                    }
                }
                if (event.toMs < positionMs) {
                    applyTasteSignal(TasteSignal.SeekBackDuringMix, recipeId, changes = changes)
                }
            }
            else -> Unit
        }
    }

    /** One JSONL app-event line into the bounded ring (ticks excluded as noise). */
    private fun recordDebugEvent(event: MixEvent, next: MixState) {
        if (event is MixEvent.Tick) return
        enqueueTelemetry {
            val line = runCatching {
                buildJsonObject {
                    put("ts", clockMs())
                    put("event", event.eventKind.name)
                    put("state", next.stateKind.name)
                    put("generation", next.generation)
                    put("planId", next.plan?.planId)
                    put("outId", next.pair?.outTrackId)
                    put("inId", next.pair?.inTrackId)
                }.toString()
            }.getOrNull() ?: return@enqueueTelemetry
            synchronized(lock) {
                recentEvents.addLast(line)
                while (recentEvents.size > RECENT_EVENT_LIMIT) recentEvents.removeFirst()
            }
            next.plan?.let { onDebugEvent(it.planId, next.generation, line) }
        }
    }

    /**
     * "Flag this mix" (AE3 M8): writes the diagnostics zip for the mix in
     * flight or the last terminal one through [DiagnosticsBundler] and
     * returns it, or null when no bundler is wired / nothing was ever mixed /
     * the bundle could not be written. The caller (M7 owner action) may pass
     * the renderer's [script], the pair's [outAnalysis]/[inAnalysis] and the
     * 40 s [audioCapture]; plan, recent events and the telemetry ring come
     * from this executor and its [TelemetryV3Writer]. Zip I/O happens outside
     * this executor's lock.
     */
    fun flagCurrentOrLastMix(
        script: TransitionScriptV1? = null,
        outAnalysis: TrackAnalysisV3? = null,
        inAnalysis: TrackAnalysisV3? = null,
        audioCapture: File? = null,
    ): File? {
        val bundler = diagnosticsBundler ?: return null
        // Snapshot under the lock; the zip I/O below runs outside it.
        val retained = runCatching { retainedMixContextProvider()?.detached() }.getOrNull()
        val (plan, events) = synchronized(lock) {
            if (retained != null) return@synchronized retained.transitionPlanV1 to (recentEvents.toList() + retained.recentEvents)
            val currentPlan = state.plan ?: lastMixPlan ?: return null
            currentPlan to recentEvents.toList()
        }
        val telemetry = runCatching { telemetryWriter?.recentLines() }.getOrNull() ?: emptyList()
        return runCatching {
            bundler.bundle(
                plan = plan,
                script = script ?: retained?.transitionScript,
                events5min = events,
                telemetry5min = telemetry + retained?.telemetryLines.orEmpty(),
                outAnalysis = outAnalysis ?: retained?.analysisA,
                inAnalysis = inAnalysis ?: retained?.analysisB,
                audioCapture = audioCapture ?: retained?.audioCaptureRef,
                frozenRecipe = retained?.frozenRecipe,
                renderGeometry = retained?.renderGeometry,
                flagsSnapshot = retained?.flagsSnapshot ?: flagsSnapshot(),
                tasteSnapshot = retained?.tasteSnapshot ?: tasteModel?.let { synchronized(it) { it.snapshot() } },
                assetSha256 = retained?.assetSha256.orEmpty(),
                modelSha256 = retained?.modelSha256.orEmpty(),
                retainedContext = retained,
            )
        }.onFailure {
            AutomixTelemetry.event(
                "mix.executor.bundle_failed",
                mapOf(
                    "engine" to ENGINE_VERSION,
                    "eventType" to "mix.executor.bundle_failed",
                    "planId" to plan.planId,
                    "detail" to (it.message ?: it::class.simpleName),
                ),
            )
        }.getOrNull()
    }

    /** Flag action variant that moves zip creation/compression completely off the caller lane. */
    suspend fun flagCurrentOrLastMixAsync(): File? = withContext(Dispatchers.IO) { flagCurrentOrLastMix() }

    /**
     * Records an explicit Inspector thumbs-[thumbsUp] rating (Part B8.1) for
     * [recipeId], or the current / last mix's recipe when null, into the
     * [TasteModel]. A no-op when no taste model is wired or the flags gate
     * disallows learning for the recipe.
     */
    fun recordUserTasteOverride(thumbsUp: Boolean, recipeId: String? = null) {
        val changes = ArrayList<TastePersistenceSnapshot>(1)
        synchronized(lock) {
            if (closed || !callbackAdmission()) return
            val id = recipeId ?: state.plan?.recipeId ?: lastMixPlan?.recipeId ?: return
            applyTasteSignal(if (thumbsUp) TasteSignal.ExplicitThumbsUp else TasteSignal.ExplicitThumbsDown, id, changes = changes)
            changes.forEach { tasteChanges.trySend(it) }
        }
    }

    /** Applies feedback only when the visible UI still names this exact frozen generation. */
    fun recordUserTasteOverride(identity: ExplicitRatingIdentity, thumbsUp: Boolean): Boolean {
        val changes = ArrayList<TastePersistenceSnapshot>(1)
        val accepted = synchronized(lock) {
            if (closed || !callbackAdmission()) return false
            val plan = state.plan ?: lastMixPlan ?: return false
            if (identity.planId != plan.planId || identity.generation != plan.generation || identity.recipeId != plan.recipeId) return false
            applyTasteSignal(
                if (thumbsUp) TasteSignal.ExplicitThumbsUp else TasteSignal.ExplicitThumbsDown,
                identity.recipeId, identity.genreClass, changes,
            )
            changes.forEach { tasteChanges.trySend(it) }
            true
        }
        return accepted && changes.isNotEmpty()
    }

    private companion object {
        /** Bound on the in-memory app-event ring handed to a diagnostics bundle. */
        const val RECENT_EVENT_LIMIT = 256
        const val TERMINAL_DEDUPE_LIMIT = 512
        const val TERMINAL_QUEUE_CAPACITY = 64
        // Diagnostic grace only; do not change the machine's countdownToleranceMs.
        const val COUNTDOWN_FIRE_GRACE_MS = 50L
    }

    private data class ShownCountdown(
        val pair: PairKey,
        val planId: String,
        val generation: Long,
        val targetMs: Long,
        val fireSequence: Long,
        var resolved: Boolean = false,
    )

    private data class TastePersistenceSnapshot(val epoch: Long, val weights: Map<String, Double>)
    private data class TerminalWrite(
        val plan: TransitionPlanV1,
        val pair: PairKey,
        val djLevel: DjLevel,
        val generation: Long,
        val outcome: String,
        val path: String,
        val createdAtMs: Long,
    )
}

/** Bounded diagnostic-only bridge from the two existing actual-fire markers. */
internal object MixFireObservation {
    data class Fire(val sequence: Long, val positionMs: Long?, val planId: String?, val generation: Long?)
    private val fires = LinkedHashMap<PairKey, Fire>()
    private var sequence = 0L

    @Synchronized
    fun record(pair: PairKey, positionMs: Long?, planId: String?, generation: Long?) {
        fires.remove(pair)
        fires[pair] = Fire(++sequence, positionMs, planId, generation)
        while (fires.size > 64) fires.remove(fires.keys.first())
    }

    @Synchronized
    fun latest(pair: PairKey): Fire? = fires[pair]
}
