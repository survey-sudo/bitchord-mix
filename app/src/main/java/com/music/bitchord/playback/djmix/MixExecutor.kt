package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.ENGINE_VERSION
import com.music.bitchord.djmix.core.contracts.MixTelemetryV3
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.djmix.store.DjmixAnalysisStore
import com.music.bitchord.djmix.core.machine.MixEffect
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixMachine
import com.music.bitchord.djmix.core.machine.MixMachineConfig
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.machine.PairKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
 * The lock is held while effects run: `synchronized` is re-entrant, so an
 * adapter that synchronously dispatches a completion event (e.g. a test
 * double, or a renderer that prepares inline) recurses safely against the
 * already-updated state, and concurrent ticks cannot interleave effects.
 */
class MixExecutor(
    private val deckAdapter: MixDeckAdapter,
    private val config: MixMachineConfig = MixMachineConfig.DEFAULT,
    private val store: DjmixAnalysisStore? = null,
) {
    private val lock = Any()

    private var state: MixState = MixState.Idle()

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

            recordTerminalIfEntered(previous, next)
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
            is MixEffect.Emit -> logTelemetry(effect.telemetry)
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

    /** Records DONE/FAILED exactly once per entry into a terminal state. */
    private fun recordTerminalIfEntered(previous: MixState, next: MixState) {
        val terminal = when (next) {
            is MixState.Done -> "DONE"
            is MixState.Failed -> "FAILED"
            else -> null
        } ?: return
        if (previous is MixState.Done || previous is MixState.Failed) return
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
}
