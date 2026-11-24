package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.ENGINE_VERSION
import com.music.bitchord.djmix.core.contracts.ExecutionPath
import com.music.bitchord.djmix.core.contracts.MixTelemetryV3
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.contracts.TelemetryEventType
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.learning.TasteModel
import com.music.bitchord.djmix.core.learning.TasteSignal
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

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
) {
    private val lock = Any()

    private var state: MixState = MixState.Idle()

    /** The plan of the last terminal (DONE/FAILED) mix, for [flagCurrentOrLastMix]. */
    private var lastMixPlan: TransitionPlanV1? = null

    /** Bounded in-memory ring of recent app events (JSONL lines) for a flag bundle. */
    private val recentEvents = ArrayDeque<String>()

    private val _uiState = MutableStateFlow(MixMachine.uiStateFor(state, config))
    val uiState: StateFlow<MixUiState> = _uiState.asStateFlow()

    private val _stateFlow = MutableStateFlow(state)
    val stateFlow: StateFlow<MixState> = _stateFlow.asStateFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioningFlow: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    /** Current machine state (lock-safe). */
    fun currentState(): MixState = synchronized(lock) { state }

    /**
     * Reduces [event], updates all flows, and executes every effect.
     * Returns the new state and the exact effect list the machine produced.
     * Never throws for a misbehaving adapter: a failed effect is logged and
     * the remaining effects still run.
     */
    fun dispatch(event: MixEvent): Pair<MixState, List<MixEffect>> {
        synchronized(lock) {
            val previous = state
            val (next, effects) = MixMachine.reduce(previous, event, config)
            state = next
            _stateFlow.value = next
            // Recompute rather than trusting only ShowUi payloads: the
            // machine emits ShowUi on most paths but not on every pause/
            // resume/tick path, and uiStateFor is the single source of truth.
            _uiState.value = MixMachine.uiStateFor(next, config)
            _isTransitioning.value = next is MixState.Running || next is MixState.HandingOver

            checkCountdownHonesty(previous, event, effects)

            for (effect in effects) {
                runCatching { execute(effect) }.onFailure {
                    AutomixTelemetry.event(
                        "mix.executor.effect_failed",
                        mapOf(
                            "engine" to ENGINE_VERSION,
                            "eventType" to "mix.executor.effect_failed",
                            "state" to next.stateKind.name,
                            "generation" to next.generation,
                            "planId" to next.plan?.planId,
                            "effect" to effect::class.simpleName,
                            "detail" to (it.message ?: it::class.simpleName),
                        ),
                    )
                }
            }

            // AE3 M8: recent-event ring for "Flag this mix", implicit taste
            // signals from user events, and terminal records.
            recordDebugEvent(event, next)
            recordUserTasteSignals(previous, event, next)
            recordTerminalIfEntered(previous, next, effects)
            return next to effects
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
                runCatching { telemetryWriter?.write(effect.telemetry) }
                logTelemetry(effect.telemetry)
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

    /**
     * Records DONE/FAILED exactly once per entry into a terminal state.
     *
     * AE3 M8: also remembers the terminal plan for [flagCurrentOrLastMix],
     * writes a terminal `mix.done` / `mix.failed` JSONL record when this
     * dispatch produced no matching `Emit` of its own (paths that did are
     * already covered by the `Emit` routing, so the record is never
     * duplicated), and feeds the outcome into [TasteModel].
     */
    private fun recordTerminalIfEntered(previous: MixState, next: MixState, effects: List<MixEffect>) {
        val terminal = when (next) {
            is MixState.Done -> "DONE"
            is MixState.Failed -> "FAILED"
            else -> null
        } ?: return
        if (previous is MixState.Done || previous is MixState.Failed) return
        lastMixPlan = next.plan
        val alreadyEmitted = effects.any { effect ->
            effect is MixEffect.Emit && (
                (terminal == "DONE" && effect.telemetry.eventType == TelemetryEventType.MIX_DONE) ||
                    (terminal == "FAILED" && effect.telemetry.eventType == TelemetryEventType.MIX_FAILED)
                )
        }
        if (!alreadyEmitted) recordTerminalTelemetry(next, terminal)
        recordMixOutcomeTaste(next)
        val activeStore = store ?: return
        val pair = next.pair ?: return
        val plan = next.plan ?: return
        val path = when (next) {
            is MixState.Failed -> "CLASSIC_RENDERER_UNAVAILABLE"
            else -> if (plan.isLastResort) "LAST_RESORT_MUSICAL_EXIT" else "PREPARER_FROZEN"
        }
        val timingsJson = "{\"frozenExitMs\":${plan.frozenExitMs}," +
            "\"frozenEntryMs\":${plan.frozenEntryMs}," +
            "\"overlapDurationMs\":${plan.overlapDurationMs}," +
            "\"barDurationMs\":${plan.barDurationMs}}"
        runCatching {
            activeStore.recordTransition(
                outId = pair.outTrackId,
                inId = pair.inTrackId,
                planId = plan.planId,
                recipe = plan.recipeId,
                level = next.djLevel.name,
                path = path,
                timingsJson = timingsJson,
                outcome = terminal,
                createdAtMs = System.currentTimeMillis(),
            )
        }
    }

    // ---- AE3 M8: telemetry / taste / "Flag this mix" helpers ---------------------

    /** Effective flag snapshot; code defaults when no flag source is wired. */
    private fun flagsSnapshot(): FlagsSnapshot = flags?.flags?.value ?: FlagsSnapshot.defaults()

    /**
     * Taste-learning gate: honors the [FlagsSnapshot] cleanly — learning only
     * while `learningEnabled`, and only for recipes the snapshot allows, so
     * safe mode (S1/S5 straight blends only) or a pinned recipe can never be
     * steered by signals the flags disallow. Null taste model = off.
     */
    private fun tasteSignalAllowed(recipeId: String?): Boolean {
        if (recipeId.isNullOrBlank() || tasteModel == null) return false
        val snapshot = flagsSnapshot()
        return snapshot.learningEnabled && snapshot.isRecipeAllowed(recipeId)
    }

    /** Applies one taste signal through the flags gate; a no-op when gated off. */
    private fun applyTasteSignal(signal: TasteSignal, recipeId: String) {
        val model = tasteModel ?: return
        if (!tasteSignalAllowed(recipeId)) return
        runCatching { model.applySignal(signal, recipeId) }
    }

    /** A clean DONE is one full playthrough of its recipe (Part B8.1 streak logic). */
    private fun recordMixOutcomeTaste(next: MixState) {
        val model = tasteModel ?: return
        if (next !is MixState.Done) return
        val recipeId = next.plan?.recipeId ?: return
        if (!tasteSignalAllowed(recipeId)) return
        runCatching { model.noteFullPlaythrough(recipeId) }
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
                eventType = if (terminal == "DONE") TelemetryEventType.MIX_DONE else TelemetryEventType.MIX_FAILED,
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
                    else -> "outcome=done"
                },
            )
        }
    }

    /**
     * Implicit taste signals from user-initiated events during a transition:
     * a skip inside [EARLY_SKIP_WINDOW_MS] of the mix starting is one
     * [TasteSignal.EarlySkipWithin15s]; a backwards seek while the blend runs
     * is one [TasteSignal.SeekBackDuringMix].
     */
    private fun recordUserTasteSignals(previous: MixState, event: MixEvent, next: MixState) {
        if (tasteModel == null) return
        val inTransition = previous is MixState.Running || previous is MixState.HandingOver ||
            next is MixState.Running || next is MixState.HandingOver
        if (!inTransition) return
        val recipeId = (previous.plan ?: next.plan)?.recipeId ?: return
        when (event) {
            is MixEvent.UserSkip -> run {
                // startedAtMs/lastPositionMs are outgoing-deck positions, so
                // their difference is real elapsed mix time at the skip.
                val running = (previous as? MixState.Running) ?: (next as? MixState.Running) ?: return@run
                val elapsedMixMs = running.lastPositionMs - running.startedAtMs
                if (elapsedMixMs in 0..EARLY_SKIP_WINDOW_MS) {
                    applyTasteSignal(TasteSignal.EarlySkipWithin15s, recipeId)
                }
            }
            is MixEvent.UserSeek -> run {
                val positionMs = (previous as? MixState.Running)?.lastPositionMs
                    ?: (next as? MixState.Running)?.lastPositionMs
                    ?: return@run
                if (event.toMs < positionMs) {
                    applyTasteSignal(TasteSignal.SeekBackDuringMix, recipeId)
                }
            }
            else -> Unit
        }
    }

    /** One JSONL app-event line into the bounded ring (ticks excluded as noise). */
    private fun recordDebugEvent(event: MixEvent, next: MixState) {
        if (event is MixEvent.Tick) return
        runCatching {
            recentEvents.addLast(
                JSONObject()
                    .put("ts", System.currentTimeMillis())
                    .put("event", event.eventKind.name)
                    .put("state", next.stateKind.name)
                    .put("generation", next.generation)
                    .put("planId", next.plan?.planId)
                    .put("outId", next.pair?.outTrackId)
                    .put("inId", next.pair?.inTrackId)
                    .toString(),
            )
            while (recentEvents.size > RECENT_EVENT_LIMIT) recentEvents.removeFirst()
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
        val (plan, events) = synchronized(lock) {
            val currentPlan = state.plan ?: lastMixPlan ?: return null
            currentPlan to recentEvents.toList()
        }
        val telemetry = runCatching { telemetryWriter?.recentLines() }.getOrNull() ?: emptyList()
        return runCatching {
            bundler.bundle(
                plan = plan,
                script = script,
                events5min = events,
                telemetry5min = telemetry,
                outAnalysis = outAnalysis,
                inAnalysis = inAnalysis,
                audioCapture = audioCapture,
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

    /**
     * Records an explicit Inspector thumbs-[thumbsUp] rating (Part B8.1) for
     * [recipeId], or the current / last mix's recipe when null, into the
     * [TasteModel]. A no-op when no taste model is wired or the flags gate
     * disallows learning for the recipe.
     */
    fun recordUserTasteOverride(thumbsUp: Boolean, recipeId: String? = null) {
        val id = recipeId
            ?: synchronized(lock) { state.plan?.recipeId ?: lastMixPlan?.recipeId }
            ?: return
        applyTasteSignal(
            if (thumbsUp) TasteSignal.ExplicitThumbsUp else TasteSignal.ExplicitThumbsDown,
            id,
        )
    }

    private companion object {
        /** A skip within this much of the mix starting counts as [TasteSignal.EarlySkipWithin15s]. */
        const val EARLY_SKIP_WINDOW_MS = 15_000L

        /** Bound on the in-memory app-event ring handed to a diagnostics bundle. */
        const val RECENT_EVENT_LIMIT = 256
    }
}
