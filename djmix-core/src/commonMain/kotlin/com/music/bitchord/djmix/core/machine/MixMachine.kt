package com.music.bitchord.djmix.core.machine

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.ExecutionPath
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.contracts.MixTelemetryV3
import com.music.bitchord.djmix.core.contracts.MixUiState
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.TelemetryEventType
import com.music.bitchord.djmix.core.contracts.ThermalLevel
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1

/**
 * Pure transition state machine reducer (`(MixState, MixEvent) -> Pair<MixState, List<MixEffect>>`).
 *
 * Holds zero mutable state, performs zero I/O, and reads zero clocks: playback position,
 * thermal status, route latency, and async preparation completions arrive solely as [MixEvent]s.
 */
object MixMachine {

    /** Reduces [state] and [event] under [config] into the next [MixState] and ordered [MixEffect] list. */
    fun reduce(
        state: MixState,
        event: MixEvent,
        config: MixMachineConfig = MixMachineConfig.DEFAULT,
    ): Pair<MixState, List<MixEffect>> = when (event) {
        is MixEvent.NextKnown -> onNextKnown(state, event, config)
        is MixEvent.QueueEdited -> onQueueEdited(state, event, config)
        is MixEvent.AnalysisReady -> onAnalysisReady(state, event, config)
        is MixEvent.PlanFrozen -> onPlanFrozen(state, event, config)
        is MixEvent.RegionsReady -> onRegionsReady(state, event, config)
        is MixEvent.StemsReady -> onStemsReady(state, event, config)
        is MixEvent.RendererPrepared -> onRendererPrepared(state, event, config)
        is MixEvent.Tick -> onTick(state, event, config)
        is MixEvent.UserSeek -> onUserSeek(state, event, config)
        is MixEvent.UserSkip -> onUserSkip(state, config)
        is MixEvent.UserPause -> onUserPause(state)
        is MixEvent.UserResume -> onUserResume(state, config)
        is MixEvent.AudioFocusLost -> onAudioFocusLost(state, event, config)
        is MixEvent.RouteChanged -> onRouteChanged(state, event)
        is MixEvent.Backgrounded -> onBackgrounded(state, event, config)
        is MixEvent.ThermalChanged -> onThermalChanged(state, event, config)
        is MixEvent.RendererError -> onRendererError(state, event, config)
        is MixEvent.HandoverComplete -> onHandoverComplete(state, config)
        is MixEvent.LevelChanged -> onLevelChanged(state, event, config)
        is MixEvent.RemixRequested -> onRemixRequested(state, config)
    }

    /** Replays [events] starting from [initial], returning every transition step for diagnostics and tests. */
    fun replay(
        initial: MixState,
        events: List<MixEvent>,
        config: MixMachineConfig = MixMachineConfig.DEFAULT,
    ): List<ReplayStep> {
        val steps = ArrayList<ReplayStep>(events.size)
        var current = initial
        for (i in events.indices) {
            val ev = events[i]
            val (next, effects) = reduce(current, ev, config)
            steps += ReplayStep(i, current, ev, next, effects)
            current = next
        }
        return steps
    }

    /** Projects any [MixState] to its honest [MixUiState]. */
    fun uiStateFor(state: MixState, config: MixMachineConfig = MixMachineConfig.DEFAULT): MixUiState =
        MixUiProjection.uiStateFor(state, config)

    // ---- Shared representation of Preparing / Moved prep fields --------------------------------

    private data class PrepFields(
        val pair: PairKey,
        val generation: Long,
        val djLevel: DjLevel,
        val outAnalysisLevel: AnalysisLevel,
        val inAnalysisLevel: AnalysisLevel,
        val plan: TransitionPlanV1?,
        val regionsReady: Boolean,
        val stemsReady: Boolean,
        val stemsRequired: Int,
        val stemsCompleted: Int,
        val rendererPrepared: Boolean,
        val usingNoStemVariant: Boolean,
        val preferNoStemVariant: Boolean,
        val excludedRecipeIds: Set<String>,
        val lastPositionMs: Long,
        val isPlaying: Boolean,
        val btLatencyMs: Long,
        val thermalLevel: ThermalLevel,
        val backgrounded: Boolean,
        val userPaused: Boolean,
        val focusPaused: Boolean,
        val movedFromExitMs: Long?,
        val movedNewExitMs: Long?,
        val movedAtPositionMs: Long,
        val movedReason: RefusalReason,
    ) {
        fun toState(): MixState = if (movedNewExitMs != null && movedFromExitMs != null) {
            MixState.Moved(
                pair, generation, djLevel, movedFromExitMs, movedNewExitMs, movedAtPositionMs, movedReason,
                outAnalysisLevel, inAnalysisLevel, plan, regionsReady, stemsReady, stemsRequired,
                stemsCompleted, rendererPrepared, usingNoStemVariant, preferNoStemVariant, excludedRecipeIds,
                lastPositionMs, isPlaying, btLatencyMs, thermalLevel, backgrounded, userPaused, focusPaused,
            )
        } else {
            MixState.Preparing(
                pair, generation, djLevel, outAnalysisLevel, inAnalysisLevel, plan, regionsReady,
                stemsReady, stemsRequired, stemsCompleted, rendererPrepared, usingNoStemVariant,
                preferNoStemVariant, excludedRecipeIds, lastPositionMs, isPlaying, btLatencyMs,
                thermalLevel, backgrounded, userPaused, focusPaused,
            )
        }
    }

    private fun asPrep(state: MixState): PrepFields? = when (state) {
        is MixState.Preparing -> PrepFields(
            state.pair, state.generation, state.djLevel, state.outAnalysisLevel, state.inAnalysisLevel,
            state.plan, state.regionsReady, state.stemsReady, state.stemsRequired, state.stemsCompleted,
            state.rendererPrepared, state.usingNoStemVariant, state.preferNoStemVariant, state.excludedRecipeIds,
            state.lastPositionMs, state.isPlaying, state.btLatencyMs, state.thermalLevel, state.backgrounded,
            state.userPaused, state.focusPaused, null, null, 0L, RefusalReason.COMMIT_DEADLINE_MISSED,
        )
        is MixState.Moved -> PrepFields(
            state.pair, state.generation, state.djLevel, state.outAnalysisLevel, state.inAnalysisLevel,
            state.plan, state.regionsReady, state.stemsReady, state.stemsRequired, state.stemsCompleted,
            state.rendererPrepared, state.usingNoStemVariant, state.preferNoStemVariant, state.excludedRecipeIds,
            state.lastPositionMs, state.isPlaying, state.btLatencyMs, state.thermalLevel, state.backgrounded,
            state.userPaused, state.focusPaused, state.fromExitMs, state.newExitMs, state.movedAtPositionMs,
            state.reason,
        )
        else -> null
    }

    // ---- Event handlers ------------------------------------------------------------------------

    private fun onNextKnown(state: MixState, event: MixEvent.NextKnown, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        if (state is MixState.Running) {
            val lvl = event.djLevel ?: state.pendingNextLevel
            return if (lvl == state.pendingNextLevel) state to emptyList() else state.copy(pendingNextLevel = lvl) to emptyList()
        }
        if (state is MixState.HandingOver) {
            val lvl = event.djLevel ?: state.pendingNextLevel
            return if (lvl == state.pendingNextLevel) state to emptyList() else state.copy(pendingNextLevel = lvl) to emptyList()
        }
        val effectiveLevel = event.djLevel ?: state.djLevel
        if (state.pair == event.pair && effectiveLevel == state.djLevel && state.stateKind in ACTIVE_PREP_OR_ARMED) {
            return state to emptyList()
        }
        return startPrep(
            previous = state, newPair = event.pair, newLevel = effectiveLevel, positionMs = event.positionMs,
            isPlaying = event.isPlaying, excludedRecipes = emptySet(),
            cancelExisting = state.stateKind in ACTIVE_PREP_OR_ARMED,
            retireIncoming = needsIncomingRetire(state),
            telemetryType = TelemetryEventType.MIX_PREP_STARTED, config = config,
        )
    }

    private fun onQueueEdited(state: MixState, event: MixEvent.QueueEdited, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        if (state is MixState.Running || state is MixState.HandingOver) return state to emptyList()
        val pair = state.pair ?: return state to emptyList()
        if (state.stateKind !in ACTIVE_PREP_OR_ARMED || event.nextTrackId == pair.inTrackId) return state to emptyList()
        if (event.nextTrackId == null) return cancelToIdle(state, RefusalReason.QUEUE_CHANGED, "Queue cleared", config)
        return startPrep(
            previous = state, newPair = PairKey(pair.outTrackId, event.nextTrackId), newLevel = state.djLevel,
            positionMs = event.positionMs, isPlaying = stateIsPlaying(state), excludedRecipes = emptySet(),
            cancelExisting = true, retireIncoming = needsIncomingRetire(state),
            telemetryType = TelemetryEventType.MIX_PREP_STARTED, config = config,
        )
    }

    private fun onAnalysisReady(state: MixState, event: MixEvent.AnalysisReady, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        if (event.generation != null && event.generation != state.generation) return state to emptyList()
        val p = asPrep(state) ?: return state to emptyList()
        val isOut = event.trackId == p.pair.outTrackId
        val isIn = event.trackId == p.pair.inTrackId
        if (!isOut && !isIn) return state to emptyList()
        val next = p.copy(
            outAnalysisLevel = if (isOut && event.level.ordinal > p.outAnalysisLevel.ordinal) event.level else p.outAnalysisLevel,
            inAnalysisLevel = if (isIn && event.level.ordinal > p.inAnalysisLevel.ordinal) event.level else p.inAnalysisLevel,
        ).toState()
        return emitUiIfChanged(state, next, config)
    }

    private fun onPlanFrozen(state: MixState, event: MixEvent.PlanFrozen, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        if (event.generation != state.generation || event.plan.generation != state.generation) return state to emptyList()
        val p = asPrep(state) ?: return state to emptyList()
        if (event.plan.outTrackId != p.pair.outTrackId || event.plan.inTrackId != p.pair.inTrackId) return state to emptyList()
        val useNoStem = (p.preferNoStemVariant || shouldDegradeStems(state)) && event.plan.hasNoStemVariant
        val updated = p.copy(
            plan = event.plan,
            stemsRequired = if (useNoStem) 0 else event.plan.requiredStems.size,
            usingNoStemVariant = useNoStem,
            rendererPrepared = false,
        )
        return finalizePrepUpdate(updated, TelemetryEventType.MIX_PLAN_FROZEN, config)
    }

    private fun onRegionsReady(state: MixState, event: MixEvent.RegionsReady, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val p = matchingPrepPlan(state, event.planId, event.generation) ?: return state to emptyList()
        return finalizePrepUpdate(p.copy(regionsReady = true), null, config)
    }

    private fun onStemsReady(state: MixState, event: MixEvent.StemsReady, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val p = matchingPrepPlan(state, event.planId, event.generation) ?: return state to emptyList()
        val done = event.stemsCompleted >= event.stemsRequired
        val updated = p.copy(
            stemsCompleted = event.stemsCompleted.coerceAtLeast(0),
            stemsRequired = if (p.usingNoStemVariant) 0 else event.stemsRequired.coerceAtLeast(0),
            stemsReady = done,
        )
        return finalizePrepUpdate(updated, null, config)
    }

    private fun onRendererPrepared(state: MixState, event: MixEvent.RendererPrepared, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        if (event.generation != state.generation) return state to emptyList()
        if (state is MixState.LastResort && event.planId == state.plan.planId) {
            val updated = state.copy(rendererPrepared = true)
            return if (canFireAt(updated.userPaused, updated.focusPaused, updated.isPlaying, updated.lastPositionMs, updated.targetDownbeatMs, config)) {
                fireLastResort(updated, updated.lastPositionMs, config)
            } else {
                updated to listOf(MixEffect.ShowUi(uiStateFor(updated, config)))
            }
        }
        val p = matchingPrepPlan(state, event.planId, event.generation) ?: return state to emptyList()
        return promoteIfPrepared(p.copy(rendererPrepared = true, usingNoStemVariant = p.usingNoStemVariant || event.usedNoStemVariant), config)
    }

    private fun onTick(state: MixState, event: MixEvent.Tick, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        asPrep(state)?.let { return onPrepTick(state, it, event, config) }
        return when (state) {
            is MixState.Ready -> onReadyTick(state, event, config)
            is MixState.Armed -> onArmedTick(state, event, config)
            is MixState.LastResort -> onLastResortTick(state, event, config)
            is MixState.Running -> onRunningTick(state, event, config)
            else -> state to emptyList()
        }
    }

    private fun onUserSeek(state: MixState, event: MixEvent.UserSeek, config: MixMachineConfig): Pair<MixState, List<MixEffect>> = when (state) {
        is MixState.Running -> retireOutToDone(state, state.pair, state.generation, state.djLevel, state.pendingNextLevel, state.plan, event.toMs, config)
        is MixState.HandingOver -> retireOutToDone(state, state.pair, state.generation, state.djLevel, state.pendingNextLevel, state.plan, event.toMs, config)
        is MixState.Armed -> startPrep(state, state.pair, state.djLevel, event.toMs, state.isPlaying, emptySet(), cancelExisting = true, retireIncoming = true, TelemetryEventType.MIX_PREP_STARTED, config)
        is MixState.Ready -> {
            val commitAtMs = state.plan.frozenExitMs - config.commitLeadMs(state.plan.barDurationMs)
            if (event.toMs >= commitAtMs) {
                startPrep(state, state.pair, state.djLevel, event.toMs, state.isPlaying, state.excludedRecipeIds, cancelExisting = true, retireIncoming = true, TelemetryEventType.MIX_PREP_STARTED, config)
            } else emitUiIfChanged(state, state.copy(lastPositionMs = event.toMs), config)
        }
        is MixState.LastResort -> {
            val commitLead = config.commitLeadMs(state.plan.barDurationMs)
            if (event.toMs >= state.targetDownbeatMs || event.toMs < state.targetDownbeatMs - commitLead) {
                startPrep(state, state.pair, state.djLevel, event.toMs, state.isPlaying, emptySet(), cancelExisting = true, retireIncoming = state.rendererPrepared, TelemetryEventType.MIX_PREP_STARTED, config)
            } else state.copy(lastPositionMs = event.toMs) to emptyList()
        }
        is MixState.Preparing -> {
            val commitAtMs = state.plan?.let { it.frozenExitMs - config.commitLeadMs(it.barDurationMs) }
            if (commitAtMs != null && event.toMs >= commitAtMs) {
                startPrep(state, state.pair, state.djLevel, event.toMs, state.isPlaying, state.excludedRecipeIds, cancelExisting = true, retireIncoming = state.rendererPrepared, TelemetryEventType.MIX_PREP_STARTED, config)
            } else emitUiIfChanged(state, state.copy(lastPositionMs = event.toMs), config)
        }
        is MixState.Moved -> {
            val commitLead = state.plan?.let { config.commitLeadMs(it.barDurationMs) } ?: config.minCommitLeadMs
            if (event.toMs >= state.newExitMs - commitLead || event.toMs < state.fromExitMs - commitLead) {
                startPrep(state, state.pair, state.djLevel, event.toMs, state.isPlaying, state.excludedRecipeIds, cancelExisting = true, retireIncoming = state.rendererPrepared, TelemetryEventType.MIX_PREP_STARTED, config)
            } else emitUiIfChanged(state, state.copy(lastPositionMs = event.toMs), config)
        }
        else -> state to emptyList()
    }

    private fun onUserSkip(state: MixState, config: MixMachineConfig): Pair<MixState, List<MixEffect>> = when (state) {
        is MixState.Idle -> state to emptyList()
        is MixState.Done, is MixState.Suspended, is MixState.Failed -> {
            val idle = toIdle(state, bumpGeneration = false)
            idle to listOf(MixEffect.ShowUi(uiStateFor(idle, config)))
        }
        is MixState.Running -> retireSurvivorToIdle(state, state.pendingNextLevel ?: state.djLevel, state.lastPositionMs, config)
        is MixState.HandingOver -> retireSurvivorToIdle(state, state.pendingNextLevel ?: state.djLevel, state.lastPositionMs, config)
        else -> cancelToIdle(state, RefusalReason.USER_CANCELLED, "User skipped track", config)
    }

    private fun onUserPause(state: MixState): Pair<MixState, List<MixEffect>> =
        applyPauseFlags(state, setUserPaused = true, setFocusPaused = null, isPlaying = false, byUser = true)

    private fun onUserResume(state: MixState, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        if (!state.userPaused && !stateFocusPaused(state)) return state to emptyList()
        val (resumed, effects) = applyPauseFlags(state, setUserPaused = false, setFocusPaused = false, isPlaying = true, byUser = true)
        return when (resumed) {
            is MixState.Ready -> if (resumed.lastPositionMs >= resumed.plan.frozenExitMs - config.countdownToleranceMs) {
                val (run, runFx) = fireReadyDirect(resumed, resumed.lastPositionMs, config)
                run to (effects + runFx)
            } else resumed to effects
            is MixState.Armed -> if (resumed.lastPositionMs >= resumed.armAtMs - config.countdownToleranceMs) {
                val (run, runFx) = fireArmed(resumed, resumed.lastPositionMs, config)
                run to (effects + runFx)
            } else resumed to effects
            is MixState.LastResort -> if (resumed.rendererPrepared && resumed.lastPositionMs >= resumed.targetDownbeatMs - config.countdownToleranceMs) {
                val (run, runFx) = fireLastResort(resumed, resumed.lastPositionMs, config)
                run to (effects + runFx)
            } else resumed to effects
            else -> resumed to effects
        }
    }

    private fun onAudioFocusLost(state: MixState, event: MixEvent.AudioFocusLost, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        if (event.transient) {
            return applyPauseFlags(state, setUserPaused = null, setFocusPaused = true, isPlaying = false, byUser = false)
        }
        if (state.stateKind !in ACTIVE_NON_TERMINAL) return state to emptyList()
        val effects = buildList {
            if (state is MixState.Running || state is MixState.HandingOver) {
                add(MixEffect.Retire(DeckTarget.OUT, state.generation, preserveSurvivor = true))
            } else {
                add(MixEffect.CancelPrepare(state.generation))
                if (needsIncomingRetire(state)) add(MixEffect.Retire(DeckTarget.IN, state.generation, preserveSurvivor = true))
            }
        }
        val next = MixState.Suspended(
            reason = RefusalReason.AUDIO_FOCUS_LOST, detail = "Permanent audio focus loss", pair = state.pair,
            generation = state.generation, djLevel = pendingOrCurrentLevel(state), plan = state.plan,
            btLatencyMs = state.btLatencyMs, thermalLevel = state.thermalLevel, backgrounded = state.backgrounded,
            userPaused = state.userPaused,
        )
        return next to (effects + emitTelemetry(state, TelemetryEventType.MIX_SUSPENDED, reason = RefusalReason.AUDIO_FOCUS_LOST, detail = "Permanent audio focus loss") + MixEffect.ShowUi(uiStateFor(next, config)))
    }

    private fun onRouteChanged(state: MixState, event: MixEvent.RouteChanged): Pair<MixState, List<MixEffect>> {
        val next = updateEnv(state, btLatencyMs = event.btLatencyMs)
        return if (next is MixState.Armed) {
            next to listOf(MixEffect.Arm(next.plan.planId, next.generation, next.armAtMs, event.btLatencyMs))
        } else next to emptyList()
    }

    private fun onBackgrounded(state: MixState, event: MixEvent.Backgrounded, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val updated = updateEnv(state, backgrounded = event.isBackgrounded)
        return if (event.isBackgrounded) applyStemDegradation(updated, "backgrounded", config) else updated to emptyList()
    }

    private fun onThermalChanged(state: MixState, event: MixEvent.ThermalChanged, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val updated = updateEnv(state, thermalLevel = event.status)
        return if (event.status.requiresStemDegradation) applyStemDegradation(updated, "thermal=${event.status.name}", config) else updated to emptyList()
    }

    private fun onRendererError(state: MixState, event: MixEvent.RendererError, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        if (state.stateKind !in ACTIVE_NON_TERMINAL) return state to emptyList()
        val detail = event.message.ifEmpty { event.code.name }
        val cleanup = if (state is MixState.Running || state is MixState.HandingOver) {
            listOf(MixEffect.Retire(DeckTarget.OUT, state.generation, preserveSurvivor = true))
        } else {
            buildList {
                add(MixEffect.CancelPrepare(state.generation))
                if (needsIncomingRetire(state)) add(MixEffect.Retire(DeckTarget.IN, state.generation, preserveSurvivor = true))
            }
        }
        val failed = MixState.Failed(
            kind = event.code, reason = RefusalReason.RENDERER_UNAVAILABLE, message = event.message,
            pair = state.pair, generation = state.generation, djLevel = pendingOrCurrentLevel(state), plan = state.plan,
            btLatencyMs = state.btLatencyMs, thermalLevel = state.thermalLevel, backgrounded = state.backgrounded,
            userPaused = state.userPaused,
        )
        return failed to (cleanup + listOf(
            emitTelemetry(state, TelemetryEventType.MIX_FAILED, path = ExecutionPath.CLASSIC_RENDERER_UNAVAILABLE, reason = RefusalReason.RENDERER_UNAVAILABLE, detail = detail),
            emitTelemetry(state, TelemetryEventType.MIX_CLASSIC, path = ExecutionPath.CLASSIC_RENDERER_UNAVAILABLE, reason = RefusalReason.RENDERER_UNAVAILABLE, detail = detail),
            MixEffect.ShowUi(uiStateFor(failed, config)),
        ))
    }

    private fun onHandoverComplete(state: MixState, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        if (state !is MixState.HandingOver) return state to emptyList()
        return retireOutToDone(state, state.pair, state.generation, state.djLevel, state.pendingNextLevel, state.plan, state.lastPositionMs, config, detail = null)
    }

    private fun onLevelChanged(state: MixState, event: MixEvent.LevelChanged, config: MixMachineConfig): Pair<MixState, List<MixEffect>> = when (state) {
        is MixState.Idle -> if (state.djLevel == event.level) state to emptyList() else state.copy(djLevel = event.level) to emptyList()
        is MixState.Done -> if (state.djLevel == event.level) state to emptyList() else state.copy(djLevel = event.level) to emptyList()
        is MixState.Suspended -> if (state.djLevel == event.level) state to emptyList() else state.copy(djLevel = event.level) to emptyList()
        is MixState.Failed -> if (state.djLevel == event.level) state to emptyList() else state.copy(djLevel = event.level) to emptyList()
        is MixState.Running -> state.copy(pendingNextLevel = event.level) to emptyList()
        is MixState.HandingOver -> state.copy(pendingNextLevel = event.level) to emptyList()
        else -> if (state.djLevel == event.level) state to emptyList() else {
            startPrep(
                state, requireNotNull(state.pair), event.level, statePositionMs(state), stateIsPlaying(state),
                emptySet(), cancelExisting = true, retireIncoming = needsIncomingRetire(state),
                TelemetryEventType.MIX_PREP_STARTED, config,
            )
        }
    }

    private fun onRemixRequested(state: MixState, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val pair = when (state) {
            is MixState.Preparing -> state.pair
            is MixState.Ready -> state.pair
            else -> return state to emptyList()
        }
        val activePlan = state.plan ?: return state to emptyList()
        val positionMs = statePositionMs(state)
        if (!MixUiProjection.isRemixAllowed(activePlan, positionMs, config)) return state to emptyList()
        val existing = (state as? MixState.Preparing)?.excludedRecipeIds ?: (state as? MixState.Ready)?.excludedRecipeIds ?: emptySet()
        return startPrep(
            previous = state, newPair = pair, newLevel = state.djLevel, positionMs = positionMs,
            isPlaying = stateIsPlaying(state), excludedRecipes = existing + activePlan.recipeId,
            cancelExisting = true, retireIncoming = false, telemetryType = TelemetryEventType.MIX_REMIXED, config = config,
        )
    }

    // ---- Tick & preparation helpers ------------------------------------------------------------

    private fun matchingPrepPlan(state: MixState, planId: String, generation: Long): PrepFields? {
        if (generation != state.generation) return null
        val p = asPrep(state) ?: return null
        if (p.plan?.planId != planId) return null
        return p
    }

    private fun finalizePrepUpdate(p: PrepFields, telemetryType: TelemetryEventType?, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val preState = p.toState()
        val prefix = if (telemetryType != null) listOf(emitTelemetry(preState, telemetryType)) else emptyList()
        val plan = p.plan
        if (p.rendererPrepared && plan != null && p.regionsReady && (p.stemsReady || p.usingNoStemVariant || plan.requiredStems.isEmpty())) {
            val (promoted, promoFx) = promoteIfPrepared(p, config)
            return promoted to (prefix + promoFx)
        }
        if (plan != null && p.regionsReady && !p.rendererPrepared &&
            (p.stemsReady || p.usingNoStemVariant || plan.requiredStems.isEmpty() || (p.preferNoStemVariant && plan.hasNoStemVariant))
        ) {
            val useNoStem = p.usingNoStemVariant || (p.preferNoStemVariant && plan.hasNoStemVariant)
            val next = p.copy(usingNoStemVariant = useNoStem, stemsRequired = if (useNoStem) 0 else p.stemsRequired).toState()
            return next to (prefix + listOf(
                MixEffect.PrepareRenderer(plan.planId, p.generation, useNoStemVariant = useNoStem),
                MixEffect.ShowUi(uiStateFor(next, config)),
            ))
        }
        return preState to (prefix + MixEffect.ShowUi(uiStateFor(preState, config)))
    }

    private fun promoteIfPrepared(p: PrepFields, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val plan = p.plan ?: return p.toState() to emptyList()
        val stemsSatisfied = p.stemsReady || p.usingNoStemVariant || plan.requiredStems.isEmpty()
        if (!p.regionsReady || !stemsSatisfied) {
            val s = p.toState()
            return s to listOf(MixEffect.ShowUi(uiStateFor(s, config)))
        }
        if (p.lastPositionMs >= plan.frozenExitMs - config.countdownToleranceMs) {
            return handleMissedCommit(p, plan, config)
        }
        val path = if (p.usingNoStemVariant) ExecutionPath.PREPARER_NO_STEM_FALLBACK else ExecutionPath.PREPARER_FROZEN
        val commitAtMs = plan.frozenExitMs - config.commitLeadMs(plan.barDurationMs)
        if (p.lastPositionMs >= commitAtMs && !p.userPaused && !p.focusPaused && p.isPlaying) {
            val armed = MixState.Armed(
                p.pair, p.generation, p.djLevel, plan, plan.frozenExitMs, true, p.usingNoStemVariant,
                p.lastPositionMs, p.isPlaying, p.btLatencyMs, p.thermalLevel, p.backgrounded, p.userPaused, p.focusPaused,
            )
            return armed to listOf(
                MixEffect.Arm(plan.planId, armed.generation, armed.armAtMs, armed.btLatencyMs),
                emitTelemetry(armed, TelemetryEventType.MIX_READY, timestampMs = p.lastPositionMs, path = path),
                emitTelemetry(armed, TelemetryEventType.MIX_ARMED, timestampMs = p.lastPositionMs, path = path),
                MixEffect.ShowUi(uiStateFor(armed, config)),
            )
        }
        val ready = MixState.Ready(
            p.pair, p.generation, p.djLevel, plan, true, p.usingNoStemVariant,
            p.stemsRequired, p.stemsCompleted, p.excludedRecipeIds, p.lastPositionMs, p.isPlaying,
            p.btLatencyMs, p.thermalLevel, p.backgrounded, p.userPaused, p.focusPaused,
        )
        return ready to listOf(
            emitTelemetry(ready, TelemetryEventType.MIX_READY, timestampMs = p.lastPositionMs, path = path),
            MixEffect.ShowUi(uiStateFor(ready, config)),
        )
    }

    private fun onPrepTick(prevState: MixState, p: PrepFields, event: MixEvent.Tick, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val updated = p.copy(lastPositionMs = event.positionMs, isPlaying = event.isPlaying)
        val nextState = updated.toState()
        if (updated.userPaused || updated.focusPaused || !event.isPlaying) return emitUiIfChanged(prevState, nextState, config)
        val plan = updated.plan ?: return emitUiIfChanged(prevState, nextState, config)
        val commitAtMs = plan.frozenExitMs - config.commitLeadMs(plan.barDurationMs)
        return if (event.positionMs < commitAtMs) emitUiIfChanged(prevState, nextState, config) else handleMissedCommit(updated, plan, config)
    }

    private fun handleMissedCommit(p: PrepFields, plan: TransitionPlanV1, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val pos = p.lastPositionMs
        val commitLead = config.commitLeadMs(plan.barDurationMs)
        // 1) Regions decoded, stems lagging, no-stem variant available before exit: build no-stem in place.
        if (p.regionsReady && !p.stemsReady && plan.hasNoStemVariant && !p.rendererPrepared &&
            pos < plan.frozenExitMs - config.countdownToleranceMs
        ) {
            if (p.usingNoStemVariant) return p.toState() to emptyList()
            val updated = p.copy(usingNoStemVariant = true, preferNoStemVariant = true, stemsRequired = 0).toState()
            return updated to listOf(
                MixEffect.PrepareRenderer(plan.planId, p.generation, useNoStemVariant = true),
                MixEffect.ShowUi(uiStateFor(updated, config)),
            )
        }
        // 2) Move to next eligible phrase boundary that leaves full commit lead.
        val nextPhrase = plan.nextEligiblePhraseMs
        val laterThanCurrent = p.movedNewExitMs == null || (nextPhrase != null && nextPhrase > p.movedNewExitMs)
        if (nextPhrase != null && laterThanCurrent && nextPhrase - commitLead > pos) {
            val newGen = p.generation + 1
            val degrade = p.preferNoStemVariant || shouldDegradeStems(p.toState())
            val moved = MixState.Moved(
                p.pair, newGen, p.djLevel, plan.frozenExitMs, nextPhrase, pos, RefusalReason.COMMIT_DEADLINE_MISSED,
                p.outAnalysisLevel, p.inAnalysisLevel, preferNoStemVariant = degrade, excludedRecipeIds = p.excludedRecipeIds,
                lastPositionMs = pos, isPlaying = p.isPlaying, btLatencyMs = p.btLatencyMs, thermalLevel = p.thermalLevel,
                backgrounded = p.backgrounded, userPaused = p.userPaused, focusPaused = p.focusPaused,
            )
            return moved to listOf(
                MixEffect.CancelPrepare(p.generation),
                MixEffect.StartPrepare(p.pair, newGen, p.djLevel, pos, nextPhrase, degrade, p.excludedRecipeIds),
                emitTelemetry(moved, TelemetryEventType.MIX_MOVED, timestampMs = pos, reason = RefusalReason.COMMIT_DEADLINE_MISSED, detail = "from=${plan.frozenExitMs} to=$nextPhrase"),
                MixEffect.ShowUi(uiStateFor(moved, config)),
            )
        }
        // 3) Musical exit on the last phrase downbeat (never starts at "now").
        val lastDownbeat = plan.lastPhraseDownbeatMs
        if (lastDownbeat != null && lastDownbeat > pos) {
            val lrPlan = plan.copy(isLastResort = true)
            val lr = MixState.LastResort(
                p.pair, p.generation, p.djLevel, lrPlan, lastDownbeat, false, pos, p.isPlaying,
                p.btLatencyMs, p.thermalLevel, p.backgrounded, p.userPaused, p.focusPaused,
            )
            return lr to listOf(
                MixEffect.PrepareRenderer(lrPlan.planId, p.generation, useNoStemVariant = true),
                emitTelemetry(lr, TelemetryEventType.MIX_LAST_RESORT, timestampMs = pos, path = ExecutionPath.LAST_RESORT_MUSICAL_EXIT, reason = RefusalReason.COMMIT_DEADLINE_MISSED),
                MixEffect.ShowUi(uiStateFor(lr, config)),
            )
        }
        // 4) Past audible end or no eligible boundary left.
        val failed = MixState.Failed(
            FailureKind.RENDERER_PREPARE_ERROR, RefusalReason.NO_ELIGIBLE_BOUNDARY,
            "No eligible boundary remaining before audible end", p.pair, p.generation, p.djLevel, plan,
            p.btLatencyMs, p.thermalLevel, p.backgrounded, p.userPaused,
        )
        return failed to listOf(
            MixEffect.CancelPrepare(p.generation),
            MixEffect.Retire(DeckTarget.IN, p.generation, preserveSurvivor = true),
            emitTelemetry(failed, TelemetryEventType.MIX_FAILED, timestampMs = pos, path = ExecutionPath.CLASSIC_RENDERER_UNAVAILABLE, reason = RefusalReason.NO_ELIGIBLE_BOUNDARY),
            MixEffect.ShowUi(uiStateFor(failed, config)),
        )
    }

    private fun onReadyTick(state: MixState.Ready, event: MixEvent.Tick, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val updated = state.copy(lastPositionMs = event.positionMs, isPlaying = event.isPlaying)
        if (updated.userPaused || updated.focusPaused || !event.isPlaying) return emitUiIfChanged(state, updated, config)
        if (event.positionMs >= updated.plan.frozenExitMs - config.countdownToleranceMs) {
            return fireReadyDirect(updated, event.positionMs, config)
        }
        val commitAtMs = updated.plan.frozenExitMs - config.commitLeadMs(updated.plan.barDurationMs)
        if (event.positionMs >= commitAtMs) {
            val armed = MixState.Armed(
                updated.pair, updated.generation, updated.djLevel, updated.plan, updated.plan.frozenExitMs, true,
                updated.usingNoStemVariant, event.positionMs, event.isPlaying, updated.btLatencyMs,
                updated.thermalLevel, updated.backgrounded, updated.userPaused, updated.focusPaused,
            )
            return armed to listOf(
                MixEffect.Arm(armed.plan.planId, armed.generation, armed.armAtMs, armed.btLatencyMs),
                emitTelemetry(armed, TelemetryEventType.MIX_ARMED, timestampMs = event.positionMs),
                MixEffect.ShowUi(uiStateFor(armed, config)),
            )
        }
        return emitUiIfChanged(state, updated, config)
    }

    private fun onArmedTick(state: MixState.Armed, event: MixEvent.Tick, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val updated = state.copy(lastPositionMs = event.positionMs, isPlaying = event.isPlaying)
        return if (canFireAt(updated.userPaused, updated.focusPaused, event.isPlaying, event.positionMs, updated.armAtMs, config)) {
            fireArmed(updated, event.positionMs, config)
        } else emitUiIfChanged(state, updated, config)
    }

    private fun onLastResortTick(state: MixState.LastResort, event: MixEvent.Tick, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val updated = state.copy(lastPositionMs = event.positionMs, isPlaying = event.isPlaying)
        if (!canFireAt(updated.userPaused, updated.focusPaused, event.isPlaying, event.positionMs, updated.targetDownbeatMs, config)) {
            return updated to emptyList()
        }
        if (updated.rendererPrepared) return fireLastResort(updated, event.positionMs, config)
        val failed = MixState.Failed(
            FailureKind.RENDERER_PREPARE_ERROR, RefusalReason.RENDERER_UNAVAILABLE,
            "Last-resort renderer preparation did not finish before downbeat", updated.pair, updated.generation,
            updated.djLevel, updated.plan, updated.btLatencyMs, updated.thermalLevel, updated.backgrounded, updated.userPaused,
        )
        return failed to listOf(
            MixEffect.CancelPrepare(updated.generation),
            MixEffect.Retire(DeckTarget.IN, updated.generation, preserveSurvivor = true),
            emitTelemetry(failed, TelemetryEventType.MIX_FAILED, timestampMs = event.positionMs, path = ExecutionPath.CLASSIC_RENDERER_UNAVAILABLE, reason = RefusalReason.RENDERER_UNAVAILABLE),
            emitTelemetry(failed, TelemetryEventType.MIX_CLASSIC, timestampMs = event.positionMs, path = ExecutionPath.CLASSIC_RENDERER_UNAVAILABLE, reason = RefusalReason.RENDERER_UNAVAILABLE),
            MixEffect.ShowUi(uiStateFor(failed, config)),
        )
    }

    private fun onRunningTick(state: MixState.Running, event: MixEvent.Tick, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val updated = state.copy(lastPositionMs = event.positionMs, isPlaying = event.isPlaying)
        if (updated.userPaused || updated.focusPaused || !event.isPlaying) return updated to emptyList()
        val handoverAtMs = updated.plan.frozenExitMs + updated.plan.overlapDurationMs
        if (event.positionMs >= handoverAtMs) {
            val ho = MixState.HandingOver(
                updated.pair, updated.generation, updated.djLevel, updated.plan, event.positionMs, DeckTarget.IN,
                updated.pendingNextLevel, updated.btLatencyMs, updated.thermalLevel, updated.backgrounded,
                updated.userPaused, updated.focusPaused,
            )
            return ho to listOf(
                MixEffect.Handover(ho.plan.planId, ho.generation),
                emitTelemetry(ho, TelemetryEventType.MIX_HANDOVER, timestampMs = event.positionMs),
                MixEffect.ShowUi(uiStateFor(ho, config)),
            )
        }
        val overlap = updated.plan.overlapDurationMs.coerceAtLeast(1L)
        val pct = (((event.positionMs - updated.startedAtMs).coerceAtLeast(0L) * 100L) / overlap).toInt().coerceIn(0, 100)
        return emitUiIfChanged(state, updated.copy(progressPct = pct), config)
    }

    private fun fireReadyDirect(s: MixState.Ready, posMs: Long, cfg: MixMachineConfig) =
        fireToRunning(s.pair, s.generation, s.djLevel, s.plan, posMs, s.usingNoStemVariant, s.btLatencyMs, s.thermalLevel, s.backgrounded, s.userPaused, s.focusPaused, if (s.usingNoStemVariant) ExecutionPath.PREPARER_NO_STEM_FALLBACK else ExecutionPath.PREPARER_FROZEN, emitArm = true, cfg)

    private fun fireArmed(s: MixState.Armed, posMs: Long, cfg: MixMachineConfig) =
        fireToRunning(s.pair, s.generation, s.djLevel, s.plan, posMs, s.usingNoStemVariant, s.btLatencyMs, s.thermalLevel, s.backgrounded, s.userPaused, s.focusPaused, if (s.usingNoStemVariant) ExecutionPath.PREPARER_NO_STEM_FALLBACK else ExecutionPath.PREPARER_FROZEN, emitArm = false, cfg)

    private fun fireLastResort(s: MixState.LastResort, posMs: Long, cfg: MixMachineConfig) =
        fireToRunning(s.pair, s.generation, s.djLevel, s.plan, posMs, true, s.btLatencyMs, s.thermalLevel, s.backgrounded, s.userPaused, s.focusPaused, ExecutionPath.LAST_RESORT_MUSICAL_EXIT, emitArm = false, cfg)

    private fun fireToRunning(
        pair: PairKey,
        generation: Long,
        djLevel: DjLevel,
        plan: TransitionPlanV1,
        positionMs: Long,
        usingNoStem: Boolean,
        btLatencyMs: Long,
        thermalLevel: ThermalLevel,
        backgrounded: Boolean,
        userPaused: Boolean,
        focusPaused: Boolean,
        path: ExecutionPath,
        emitArm: Boolean,
        config: MixMachineConfig,
    ): Pair<MixState.Running, List<MixEffect>> {
        val running = MixState.Running(
            pair, generation, djLevel, plan, startedAtMs = positionMs, lastPositionMs = positionMs, progressPct = 0,
            usingNoStemVariant = usingNoStem, isPlaying = true, survivingDeck = DeckTarget.IN, pendingNextLevel = null,
            btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded,
            userPaused = userPaused, focusPaused = focusPaused,
        )
        val effects = buildList {
            if (emitArm) {
                add(MixEffect.Arm(plan.planId, generation, plan.frozenExitMs, btLatencyMs))
            }
            add(MixEffect.Start(plan.planId, generation))
            if (emitArm) {
                add(emitTelemetry(running, TelemetryEventType.MIX_ARMED, timestampMs = positionMs, path = path))
            }
            add(emitTelemetry(running, TelemetryEventType.MIX_FIRE, timestampMs = positionMs, path = path, countdownBroken = false))
            add(MixEffect.ShowUi(uiStateFor(running, config)))
        }
        return running to effects
    }

    // ---- Shared state transformation helpers ---------------------------------------------------

    private fun startPrep(
        previous: MixState,
        newPair: PairKey,
        newLevel: DjLevel,
        positionMs: Long,
        isPlaying: Boolean,
        excludedRecipes: Set<String>,
        cancelExisting: Boolean,
        retireIncoming: Boolean,
        telemetryType: TelemetryEventType,
        config: MixMachineConfig,
    ): Pair<MixState, List<MixEffect>> {
        val newGen = previous.generation + 1
        val degrade = shouldDegradeStems(previous)
        val prep = MixState.Preparing(
            pair = newPair, generation = newGen, djLevel = newLevel, preferNoStemVariant = degrade,
            excludedRecipeIds = excludedRecipes, lastPositionMs = positionMs, isPlaying = isPlaying,
            btLatencyMs = previous.btLatencyMs, thermalLevel = previous.thermalLevel,
            backgrounded = previous.backgrounded, userPaused = previous.userPaused,
        )
        val effects = buildList {
            if (cancelExisting) add(MixEffect.CancelPrepare(previous.generation))
            if (retireIncoming) add(MixEffect.Retire(DeckTarget.IN, previous.generation, preserveSurvivor = true))
            add(MixEffect.StartPrepare(newPair, newGen, newLevel, positionMs, null, degrade, excludedRecipes))
            add(emitTelemetry(prep, telemetryType, timestampMs = positionMs))
            add(MixEffect.ShowUi(uiStateFor(prep, config)))
        }
        return prep to effects
    }

    private fun cancelToIdle(state: MixState, reason: RefusalReason, detail: String, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val idle = toIdle(state, bumpGeneration = true)
        val effects = buildList {
            add(MixEffect.CancelPrepare(state.generation))
            if (needsIncomingRetire(state)) add(MixEffect.Retire(DeckTarget.IN, state.generation, preserveSurvivor = true))
            add(emitTelemetry(state, TelemetryEventType.MIX_REFUSED, reason = reason, detail = detail))
            add(MixEffect.ShowUi(uiStateFor(idle, config)))
        }
        return idle to effects
    }

    private fun retireOutToDone(
        state: MixState,
        pair: PairKey,
        generation: Long,
        djLevel: DjLevel,
        pendingNextLevel: DjLevel?,
        plan: TransitionPlanV1,
        positionMs: Long,
        config: MixMachineConfig,
        detail: String? = "user_seek",
    ): Pair<MixState, List<MixEffect>> {
        val done = MixState.Done(
            pair, generation, pendingNextLevel ?: djLevel, plan,
            state.btLatencyMs, state.thermalLevel, state.backgrounded, state.userPaused,
        )
        return done to listOf(
            MixEffect.Retire(DeckTarget.OUT, generation, preserveSurvivor = true),
            emitTelemetry(done, TelemetryEventType.MIX_DONE, timestampMs = positionMs, path = ExecutionPath.PREPARER_FROZEN, detail = detail),
            MixEffect.ShowUi(uiStateFor(done, config)),
        )
    }

    private fun retireSurvivorToIdle(state: MixState, nextLevel: DjLevel, posMs: Long, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val idle = toIdle(state, bumpGeneration = true, overrideLevel = nextLevel)
        return idle to listOf(
            MixEffect.Retire(DeckTarget.OUT, state.generation, preserveSurvivor = true),
            emitTelemetry(state, TelemetryEventType.MIX_DONE, timestampMs = posMs, detail = "user_skip"),
            MixEffect.ShowUi(uiStateFor(idle, config)),
        )
    }

    private fun applyStemDegradation(state: MixState, detail: String, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val p = asPrep(state)
        if (p != null) {
            val degraded = p.copy(preferNoStemVariant = true)
            val plan = degraded.plan
            if (plan != null && degraded.regionsReady && !degraded.stemsReady &&
                !degraded.rendererPrepared && plan.hasNoStemVariant && !degraded.usingNoStemVariant
            ) {
                val noStem = degraded.copy(usingNoStemVariant = true, stemsRequired = 0).toState()
                return noStem to listOf(
                    MixEffect.PrepareRenderer(plan.planId, p.generation, useNoStemVariant = true),
                    emitTelemetry(noStem, TelemetryEventType.MIX_THERMAL_DEGRADED, detail = detail),
                    MixEffect.ShowUi(uiStateFor(noStem, config)),
                )
            }
            val next = degraded.toState()
            return next to listOf(emitTelemetry(next, TelemetryEventType.MIX_THERMAL_DEGRADED, detail = detail))
        }
        return if (state is MixState.Ready) {
            state to listOf(emitTelemetry(state, TelemetryEventType.MIX_THERMAL_DEGRADED, detail = detail))
        } else state to emptyList()
    }

    private fun applyPauseFlags(
        state: MixState,
        setUserPaused: Boolean?,
        setFocusPaused: Boolean?,
        isPlaying: Boolean,
        byUser: Boolean,
    ): Pair<MixState, List<MixEffect>> {
        val nextUser = setUserPaused ?: state.userPaused
        val nextFocus = setFocusPaused ?: stateFocusPaused(state)
        val next = when (state) {
            is MixState.Idle -> state.copy(userPaused = nextUser)
            is MixState.Done -> state.copy(userPaused = nextUser)
            is MixState.Suspended -> state.copy(userPaused = nextUser)
            is MixState.Failed -> state.copy(userPaused = nextUser)
            is MixState.Preparing -> state.copy(userPaused = nextUser, focusPaused = nextFocus, isPlaying = isPlaying)
            is MixState.Ready -> state.copy(userPaused = nextUser, focusPaused = nextFocus, isPlaying = isPlaying)
            is MixState.Armed -> state.copy(userPaused = nextUser, focusPaused = nextFocus, isPlaying = isPlaying)
            is MixState.Moved -> state.copy(userPaused = nextUser, focusPaused = nextFocus, isPlaying = isPlaying)
            is MixState.LastResort -> state.copy(userPaused = nextUser, focusPaused = nextFocus, isPlaying = isPlaying)
            is MixState.Running -> state.copy(userPaused = nextUser, focusPaused = nextFocus, isPlaying = isPlaying)
            is MixState.HandingOver -> state.copy(userPaused = nextUser, focusPaused = nextFocus)
        }
        if (state.stateKind !in ACTIVE_NON_TERMINAL) return next to emptyList()
        val fx = if (isPlaying) MixEffect.ResumeSync(state.generation) else MixEffect.PauseSync(state.generation, byUser = byUser)
        return next to listOf(fx)
    }

    private fun updateEnv(
        state: MixState,
        btLatencyMs: Long = state.btLatencyMs,
        thermalLevel: ThermalLevel = state.thermalLevel,
        backgrounded: Boolean = state.backgrounded,
    ): MixState = when (state) {
        is MixState.Idle -> state.copy(btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded)
        is MixState.Preparing -> state.copy(btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded)
        is MixState.Ready -> state.copy(btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded)
        is MixState.Armed -> state.copy(btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded)
        is MixState.Running -> state.copy(btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded)
        is MixState.HandingOver -> state.copy(btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded)
        is MixState.Done -> state.copy(btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded)
        is MixState.Moved -> state.copy(btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded)
        is MixState.LastResort -> state.copy(btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded)
        is MixState.Suspended -> state.copy(btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded)
        is MixState.Failed -> state.copy(btLatencyMs = btLatencyMs, thermalLevel = thermalLevel, backgrounded = backgrounded)
    }

    private fun emitUiIfChanged(prev: MixState, next: MixState, config: MixMachineConfig): Pair<MixState, List<MixEffect>> {
        val nextUi = uiStateFor(next, config)
        return if (uiStateFor(prev, config) != nextUi) next to listOf(MixEffect.ShowUi(nextUi)) else next to emptyList()
    }

    private fun toIdle(state: MixState, bumpGeneration: Boolean, overrideLevel: DjLevel = state.djLevel): MixState.Idle =
        MixState.Idle(
            generation = if (bumpGeneration) state.generation + 1 else state.generation,
            djLevel = overrideLevel, btLatencyMs = state.btLatencyMs, thermalLevel = state.thermalLevel,
            backgrounded = state.backgrounded, userPaused = state.userPaused,
        )

    private fun emitTelemetry(
        state: MixState,
        eventType: TelemetryEventType,
        timestampMs: Long = statePositionMs(state),
        path: ExecutionPath? = null,
        reason: RefusalReason? = null,
        detail: String? = null,
        countdownBroken: Boolean? = null,
    ): MixEffect.Emit = MixEffect.Emit(
        MixTelemetryV3(
            timestampMs = timestampMs, eventType = eventType, planId = state.plan?.planId, generation = state.generation,
            state = state.stateKind.name, outId = state.pair?.outTrackId.orEmpty(), inId = state.pair?.inTrackId.orEmpty(),
            djLevel = state.djLevel, path = path, recipeId = state.plan?.recipeId, countdownBroken = countdownBroken,
            reason = reason, detail = detail,
        ),
    )

    private fun canFireAt(userPaused: Boolean, focusPaused: Boolean, isPlaying: Boolean, positionMs: Long, targetMs: Long, cfg: MixMachineConfig): Boolean =
        !userPaused && !focusPaused && isPlaying && positionMs >= targetMs - cfg.countdownToleranceMs

    private fun needsIncomingRetire(state: MixState): Boolean =
        state is MixState.Armed || state is MixState.Ready || (state is MixState.LastResort && state.rendererPrepared)

    private fun shouldDegradeStems(state: MixState): Boolean =
        state.backgrounded || state.thermalLevel.requiresStemDegradation

    private fun pendingOrCurrentLevel(state: MixState): DjLevel =
        (state as? MixState.Running)?.pendingNextLevel ?: (state as? MixState.HandingOver)?.pendingNextLevel ?: state.djLevel

    private fun statePositionMs(state: MixState): Long = when (state) {
        is MixState.Preparing -> state.lastPositionMs
        is MixState.Ready -> state.lastPositionMs
        is MixState.Armed -> state.lastPositionMs
        is MixState.Running -> state.lastPositionMs
        is MixState.HandingOver -> state.lastPositionMs
        is MixState.Moved -> state.lastPositionMs
        is MixState.LastResort -> state.lastPositionMs
        else -> 0L
    }

    private fun stateIsPlaying(state: MixState): Boolean = when (state) {
        is MixState.Preparing -> state.isPlaying
        is MixState.Ready -> state.isPlaying
        is MixState.Armed -> state.isPlaying
        is MixState.Running -> state.isPlaying
        is MixState.Moved -> state.isPlaying
        is MixState.LastResort -> state.isPlaying
        else -> true
    }

    private fun stateFocusPaused(state: MixState): Boolean = when (state) {
        is MixState.Preparing -> state.focusPaused
        is MixState.Ready -> state.focusPaused
        is MixState.Armed -> state.focusPaused
        is MixState.Running -> state.focusPaused
        is MixState.HandingOver -> state.focusPaused
        is MixState.Moved -> state.focusPaused
        is MixState.LastResort -> state.focusPaused
        else -> false
    }

    private val ACTIVE_PREP_OR_ARMED = setOf(
        MixStateKind.PREPARING, MixStateKind.READY, MixStateKind.ARMED, MixStateKind.MOVED, MixStateKind.LAST_RESORT,
    )

    private val ACTIVE_NON_TERMINAL = ACTIVE_PREP_OR_ARMED + setOf(MixStateKind.RUNNING, MixStateKind.HANDING_OVER)
}
