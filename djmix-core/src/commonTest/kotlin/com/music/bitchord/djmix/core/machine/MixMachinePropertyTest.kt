package com.music.bitchord.djmix.core.machine

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.DeckStemRequirement
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.contracts.MixUiPhase
import com.music.bitchord.djmix.core.contracts.StemKind
import com.music.bitchord.djmix.core.contracts.TelemetryEventType
import com.music.bitchord.djmix.core.contracts.ThermalLevel
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Part B2 item 2: deterministic seeded property test (10,000 sequences).
 *
 * Every step checks the six B2 invariants against the real [MixMachine] reducer:
 * 1) honest countdown (visible only in Ready/Armed, zero countdown-broken, Armed ticks
 *    at armAtMs - countdownToleranceMs fire immediately),
 * 2) at most one running plan,
 * 3) prepared-only Start,
 * 4) liveness (Preparing drains via completion events — see also the dedicated test),
 * 5) sticky user pause,
 * 6) surviving-deck safety (Retire never targets the survivor in Running/HandingOver).
 *
 * Note on btLatencyMs: the reducer does NOT shift armAtMs by btLatencyMs. canFireAt checks
 * positionMs >= targetMs - countdownToleranceMs only; btLatencyMs is carried in the Arm
 * effect's btLatencyOffsetMs. The firing checks below mirror that exact condition.
 */
class MixMachinePropertyTest {

    @Test
    fun propertyInvariantsHoldAcrossTenThousandSequences() {
        val config = MixMachineConfig.DEFAULT
        val runs = 10_000
        var totalSteps = 0L
        var totalStarts = 0L
        var totalArmedFires = 0L
        var livenessDrains = 0L
        val coverage = mutableSetOf<MixEventKind>()

        for (runIdx in 0 until runs) {
            val seed = 1_234_567L + runIdx.toLong()
            val rnd = Random(seed)
            var state: MixState = MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE)
            val tracker = PropertyTracker()
            val seqLen = 20 + rnd.nextInt(31)
            var positionMs = rnd.nextLong(5_000L, 30_000L)

            for (step in 0 until seqLen) {
                val event = generateEvent(state, rnd, positionMs)
                coverage += event.eventKind
                val (next, effects) = MixMachine.reduce(state, event, config)
                tracker.check(seed, step, state, event, next, effects, config)
                totalSteps += 1L
                if (event is MixEvent.Tick) positionMs = event.positionMs
                if (event is MixEvent.UserSeek) positionMs = event.toMs
                if (event is MixEvent.NextKnown) positionMs = event.positionMs
                if (event is MixEvent.QueueEdited) positionMs = event.positionMs
                state = next
            }

            totalStarts += tracker.startCount.toLong()
            totalArmedFires += tracker.armedFireCount.toLong()

            if (state is MixState.Preparing || state is MixState.Moved) {
                val drained = drainPreparing(state, seed, config, tracker)
                if (drained is MixState.Preparing || drained is MixState.Moved) {
                    error(
                        "seed=$seed step=drain state=$state final=$drained event=drain " +
                            "invariant=liveness Preparing did not drain to Ready",
                    )
                }
                livenessDrains += 1L
            }

            if (tracker.brokenCount != 0) {
                error(
                    "seed=$seed step=end state=$state event=end " +
                        "invariant=honest-countdown brokenCount=${tracker.brokenCount} expected 0",
                )
            }
        }

        assertTrue(totalSteps >= 200_000L, "expected >=200k steps from 10k sequences, got $totalSteps")
        assertTrue(totalStarts > 0L, "expected at least one Start across 10k sequences")
        assertTrue(totalArmedFires > 0L, "expected at least one honest Armed firing across 10k sequences")
        assertTrue(livenessDrains > 0L, "expected at least one liveness drain across 10k sequences")
        assertTrue(
            coverage.size == MixEventKind.entries.size,
            "expected all ${MixEventKind.entries.size} event kinds generated, got ${coverage.size} $coverage",
        )
    }

    @Test
    fun livenessPreparingAlwaysDrainsToReadyOrBeyond() {
        val config = MixMachineConfig.DEFAULT
        var drains = 0L
        for (runIdx in 0 until 1_000) {
            val seed = 9_876_543L + runIdx.toLong()
            val rnd = Random(seed)
            var state: MixState = MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE)
            val tracker = PropertyTracker()
            var positionMs = rnd.nextLong(5_000L, 20_000L)
            val start = MixEvent.NextKnown(
                pair = PairKey(outTrackId = "out-A", inTrackId = "in-A"),
                positionMs = positionMs,
                djLevel = null,
                isPlaying = true,
            )
            val (afterStart, startFx) = MixMachine.reduce(state, start, config)
            tracker.check(seed, 0, state, start, afterStart, startFx, config)
            state = afterStart
            for (step in 1..12) {
                val event = generateEvent(state, rnd, positionMs)
                val (next, effects) = MixMachine.reduce(state, event, config)
                tracker.check(seed, step, state, event, next, effects, config)
                if (event is MixEvent.Tick) positionMs = event.positionMs
                if (event is MixEvent.UserSeek) positionMs = event.toMs
                state = next
            }
            if (state is MixState.Preparing || state is MixState.Moved) {
                val drained = drainPreparing(state, seed, config, tracker)
                if (drained is MixState.Preparing || drained is MixState.Moved) {
                    error(
                        "seed=$seed step=drain state=$state final=$drained event=drain " +
                            "invariant=liveness Preparing did not drain",
                    )
                }
                drains += 1L
            }
        }
        assertTrue(drains > 0L, "expected at least one Preparing drain in dedicated liveness check")
    }

    private fun drainPreparing(
        start: MixState,
        seed: Long,
        config: MixMachineConfig,
        tracker: PropertyTracker,
    ): MixState {
        val pair = start.pair ?: return start
        val gen = start.generation
        val planId = "drain-$seed-$gen"
        val plan = propertyPlan(
            planId = planId,
            generation = gen,
            outTrackId = pair.outTrackId,
            inTrackId = pair.inTrackId,
            djLevel = start.djLevel,
            frozenExitMs = 300_000L,
            overlapDurationMs = 16_000L,
            barDurationMs = 2_000L,
            requiredStems = emptySet(),
            hasNoStemVariant = true,
            nextEligiblePhraseMs = null,
            lastPhraseDownbeatMs = null,
        )
        val drainEvents: List<MixEvent> = listOf(
            MixEvent.AnalysisReady(
                trackId = pair.outTrackId,
                level = AnalysisLevel.FULL,
                generation = gen,
            ),
            MixEvent.AnalysisReady(
                trackId = pair.inTrackId,
                level = AnalysisLevel.FULL,
                generation = gen,
            ),
            MixEvent.PlanFrozen(plan = plan, generation = gen),
            MixEvent.Tick(positionMs = 1_000L, isPlaying = true),
            MixEvent.RegionsReady(planId = planId, generation = gen),
            MixEvent.RendererPrepared(planId = planId, generation = gen, usedNoStemVariant = true),
        )
        var current = start
        var idx = 10_000
        for (event in drainEvents) {
            val (next, effects) = MixMachine.reduce(current, event, config)
            tracker.check(seed, idx, current, event, next, effects, config)
            idx += 1
            current = next
        }
        return current
    }

    private fun propertyPlan(
        planId: String,
        generation: Long,
        outTrackId: String,
        inTrackId: String,
        djLevel: DjLevel,
        frozenExitMs: Long,
        overlapDurationMs: Long,
        barDurationMs: Long,
        requiredStems: Set<DeckStemRequirement>,
        hasNoStemVariant: Boolean,
        nextEligiblePhraseMs: Long?,
        lastPhraseDownbeatMs: Long?,
    ): TransitionPlanV1 = TransitionPlanV1(
        planId = planId,
        generation = generation,
        outTrackId = outTrackId,
        inTrackId = inTrackId,
        djLevel = djLevel,
        geometry = TransitionGeometry.BRIDGE,
        frozenExitMs = frozenExitMs,
        frozenEntryMs = (frozenExitMs - overlapDurationMs).coerceAtLeast(0L),
        swapDownbeatMs = frozenExitMs,
        overlapDurationMs = overlapDurationMs,
        barDurationMs = barDurationMs,
        recipeId = "recipe-prop",
        recipeName = "Property Blend",
        requiredStems = requiredStems,
        hasNoStemVariant = hasNoStemVariant,
        nextEligiblePhraseMs = nextEligiblePhraseMs,
        lastPhraseDownbeatMs = lastPhraseDownbeatMs,
    )

    private fun generateEvent(state: MixState, rnd: Random, positionMs: Long): MixEvent {
        if (rnd.nextInt(100) < 4) {
            return when (rnd.nextInt(3)) {
                0 -> MixEvent.AudioFocusLost(transient = rnd.nextBoolean())
                1 -> MixEvent.RendererError(
                    code = randomFailure(rnd),
                    message = "forced-error",
                    recoverable = rnd.nextBoolean(),
                )
                else -> MixEvent.HandoverComplete
            }
        }
        return when {
            state is MixState.Idle || state is MixState.Done || state is MixState.Suspended || state is MixState.Failed ->
                terminalEvent(rnd, positionMs)
            state is MixState.Preparing || state is MixState.Moved ->
                prepEvent(state, rnd, positionMs)
            state is MixState.Ready -> readyEvent(state, rnd, positionMs)
            state is MixState.Armed -> armedEvent(state, rnd, positionMs)
            state is MixState.Running -> runningEvent(state, rnd, positionMs)
            state is MixState.HandingOver -> handingOverEvent(state, rnd, positionMs)
            state is MixState.LastResort -> lastResortEvent(state, rnd, positionMs)
            else -> makeTick(state, rnd, positionMs)
        }
    }

    private fun terminalEvent(rnd: Random, positionMs: Long): MixEvent {
        val roll = rnd.nextInt(100)
        return when {
            roll < 60 -> makeNextKnown(rnd, positionMs)
            roll < 70 -> MixEvent.Tick(positionMs = positionMs, isPlaying = true)
            roll < 80 -> MixEvent.LevelChanged(level = randomLevel(rnd))
            roll < 85 -> MixEvent.UserSkip
            roll < 90 -> MixEvent.QueueEdited(nextTrackId = randomNextTrack(rnd), positionMs = positionMs)
            roll < 93 -> MixEvent.UserPause
            roll < 95 -> MixEvent.UserResume
            roll < 97 -> MixEvent.ThermalChanged(status = randomThermal(rnd))
            roll < 99 -> MixEvent.RouteChanged(btLatencyMs = randomLatency(rnd))
            else -> MixEvent.Backgrounded(isBackgrounded = rnd.nextBoolean())
        }
    }

    private fun prepEvent(state: MixState, rnd: Random, positionMs: Long): MixEvent {
        val roll = rnd.nextInt(100)
        val hasPlan = state.plan != null
        return when {
            !hasPlan && roll < 55 -> makePlanFrozen(state, rnd, positionMs)
            !hasPlan && roll < 65 -> makeAnalysis(state, rnd)
            !hasPlan && roll < 80 -> makeTick(state, rnd, positionMs)
            !hasPlan && roll < 85 -> makeStaleCompletion(state, rnd)
            !hasPlan && roll < 88 -> MixEvent.UserPause
            !hasPlan && roll < 90 -> MixEvent.UserResume
            !hasPlan && roll < 92 -> MixEvent.ThermalChanged(status = randomThermal(rnd))
            !hasPlan && roll < 94 -> MixEvent.Backgrounded(isBackgrounded = rnd.nextBoolean())
            !hasPlan && roll < 96 -> MixEvent.RouteChanged(btLatencyMs = randomLatency(rnd))
            !hasPlan && roll < 98 -> MixEvent.UserSeek(toMs = randomSeek(rnd, positionMs))
            !hasPlan -> makeNextKnown(rnd, positionMs)
            roll < 20 -> makeRegions(state, rnd)
            roll < 40 -> makeStems(state, rnd)
            roll < 55 -> makeRenderer(state, rnd)
            roll < 75 -> makeTick(state, rnd, positionMs)
            roll < 80 -> makeAnalysis(state, rnd)
            roll < 84 -> MixEvent.UserPause
            roll < 86 -> MixEvent.UserResume
            roll < 89 -> MixEvent.ThermalChanged(status = randomThermal(rnd))
            roll < 91 -> MixEvent.Backgrounded(isBackgrounded = rnd.nextBoolean())
            roll < 93 -> MixEvent.RouteChanged(btLatencyMs = randomLatency(rnd))
            roll < 95 -> MixEvent.UserSeek(toMs = randomSeek(rnd, positionMs))
            roll < 97 -> MixEvent.RemixRequested
            roll < 99 -> MixEvent.LevelChanged(level = randomLevel(rnd))
            else -> makeQueueOrNext(state, rnd, positionMs)
        }
    }

    private fun readyEvent(state: MixState.Ready, rnd: Random, positionMs: Long): MixEvent {
        val roll = rnd.nextInt(100)
        return when {
            roll < 50 -> makeTick(state, rnd, positionMs)
            roll < 60 -> MixEvent.RemixRequested
            roll < 70 -> MixEvent.LevelChanged(level = randomLevel(rnd))
            roll < 80 -> MixEvent.UserSeek(toMs = randomSeek(rnd, positionMs))
            roll < 85 -> MixEvent.UserPause
            roll < 90 -> MixEvent.UserSkip
            roll < 92 -> MixEvent.ThermalChanged(status = randomThermal(rnd))
            roll < 94 -> MixEvent.Backgrounded(isBackgrounded = rnd.nextBoolean())
            roll < 96 -> MixEvent.RouteChanged(btLatencyMs = randomLatency(rnd))
            roll < 98 -> makeNextKnown(rnd, positionMs)
            else -> MixEvent.QueueEdited(nextTrackId = randomNextTrack(rnd), positionMs = positionMs)
        }
    }

    private fun armedEvent(state: MixState.Armed, rnd: Random, positionMs: Long): MixEvent {
        val roll = rnd.nextInt(100)
        return when {
            roll < 55 -> makeTick(state, rnd, positionMs)
            roll < 65 -> MixEvent.UserSeek(toMs = randomSeek(rnd, positionMs))
            roll < 70 -> MixEvent.UserPause
            roll < 75 -> MixEvent.UserSkip
            roll < 80 -> MixEvent.RouteChanged(btLatencyMs = randomLatency(rnd))
            roll < 85 -> MixEvent.LevelChanged(level = randomLevel(rnd))
            roll < 88 -> MixEvent.ThermalChanged(status = randomThermal(rnd))
            roll < 91 -> MixEvent.Backgrounded(isBackgrounded = rnd.nextBoolean())
            roll < 94 -> makeNextKnown(rnd, positionMs)
            roll < 97 -> MixEvent.QueueEdited(nextTrackId = randomNextTrack(rnd), positionMs = positionMs)
            else -> MixEvent.UserResume
        }
    }

    private fun runningEvent(state: MixState.Running, rnd: Random, positionMs: Long): MixEvent {
        val roll = rnd.nextInt(100)
        return when {
            roll < 50 -> makeTick(state, rnd, positionMs)
            roll < 60 -> MixEvent.UserPause
            roll < 70 -> MixEvent.UserSeek(toMs = randomSeek(rnd, positionMs))
            roll < 75 -> MixEvent.UserSkip
            roll < 80 -> MixEvent.ThermalChanged(status = randomThermal(rnd))
            roll < 85 -> MixEvent.Backgrounded(isBackgrounded = rnd.nextBoolean())
            roll < 90 -> MixEvent.RouteChanged(btLatencyMs = randomLatency(rnd))
            roll < 95 -> MixEvent.LevelChanged(level = randomLevel(rnd))
            roll < 97 -> makeNextKnown(rnd, positionMs)
            roll < 99 -> MixEvent.QueueEdited(nextTrackId = randomNextTrack(rnd), positionMs = positionMs)
            else -> MixEvent.UserResume
        }
    }

    private fun handingOverEvent(state: MixState.HandingOver, rnd: Random, positionMs: Long): MixEvent {
        val roll = rnd.nextInt(100)
        return when {
            roll < 50 -> MixEvent.HandoverComplete
            roll < 70 -> makeTick(state, rnd, positionMs)
            roll < 80 -> MixEvent.UserSeek(toMs = randomSeek(rnd, positionMs))
            roll < 85 -> MixEvent.UserSkip
            roll < 90 -> MixEvent.UserPause
            roll < 95 -> MixEvent.LevelChanged(level = randomLevel(rnd))
            roll < 97 -> makeNextKnown(rnd, positionMs)
            roll < 99 -> MixEvent.QueueEdited(nextTrackId = randomNextTrack(rnd), positionMs = positionMs)
            else -> MixEvent.RouteChanged(btLatencyMs = randomLatency(rnd))
        }
    }

    private fun lastResortEvent(state: MixState.LastResort, rnd: Random, positionMs: Long): MixEvent {
        val roll = rnd.nextInt(100)
        return when {
            roll < 30 -> makeRenderer(state, rnd)
            roll < 60 -> makeTick(state, rnd, positionMs)
            roll < 65 -> MixEvent.UserPause
            roll < 70 -> MixEvent.UserResume
            roll < 75 -> MixEvent.UserSeek(toMs = randomSeek(rnd, positionMs))
            roll < 80 -> MixEvent.UserSkip
            roll < 85 -> MixEvent.ThermalChanged(status = randomThermal(rnd))
            roll < 90 -> MixEvent.Backgrounded(isBackgrounded = rnd.nextBoolean())
            roll < 93 -> MixEvent.RouteChanged(btLatencyMs = randomLatency(rnd))
            roll < 96 -> MixEvent.LevelChanged(level = randomLevel(rnd))
            roll < 98 -> makeNextKnown(rnd, positionMs)
            else -> MixEvent.QueueEdited(nextTrackId = randomNextTrack(rnd), positionMs = positionMs)
        }
    }

    private fun makeTick(state: MixState, rnd: Random, positionMs: Long): MixEvent.Tick {
        val playingRoll = rnd.nextInt(100)
        val isPlaying = if (state is MixState.Armed) playingRoll < 90 else playingRoll < 85
        if (rnd.nextInt(100) < 8) {
            return MixEvent.Tick(
                positionMs = (positionMs - 5_000L).coerceAtLeast(0L),
                isPlaying = isPlaying,
            )
        }
        val plan = state.plan
        val pos: Long = when (state) {
            is MixState.Armed -> when (rnd.nextInt(100)) {
                in 0..49 -> state.armAtMs
                in 50..64 -> state.armAtMs - MixMachineConfig.DEFAULT.countdownToleranceMs
                in 65..79 -> (state.armAtMs - 5_000L).coerceAtLeast(0L)
                else -> state.armAtMs + 1_000L
            }
            is MixState.Ready -> {
                val exit = state.plan.frozenExitMs
                val commit = exit - MixMachineConfig.DEFAULT.commitLeadMs(state.plan.barDurationMs)
                when (rnd.nextInt(100)) {
                    in 0..34 -> exit
                    in 35..54 -> commit
                    in 55..69 -> commit + 1_000L
                    in 70..84 -> (exit - 1_000L).coerceAtLeast(0L)
                    else -> positionMs + rnd.nextLong(500L, 3_000L)
                }
            }
            is MixState.LastResort -> when (rnd.nextInt(100)) {
                in 0..49 -> state.targetDownbeatMs
                in 50..69 -> state.targetDownbeatMs + 1_000L
                else -> positionMs + rnd.nextLong(500L, 3_000L)
            }
            is MixState.Running -> {
                val handover = state.plan.frozenExitMs + state.plan.overlapDurationMs
                when (rnd.nextInt(100)) {
                    in 0..44 -> handover
                    in 45..64 -> (handover - 1_000L).coerceAtLeast(0L)
                    in 65..79 -> handover + 1_000L
                    else -> positionMs + rnd.nextLong(500L, 3_000L)
                }
            }
            is MixState.Preparing, is MixState.Moved -> {
                if (plan != null) {
                    val commit = plan.frozenExitMs - MixMachineConfig.DEFAULT.commitLeadMs(plan.barDurationMs)
                    when (rnd.nextInt(100)) {
                        in 0..24 -> commit
                        in 25..39 -> commit + 1_000L
                        in 40..49 -> plan.frozenExitMs
                        else -> positionMs + rnd.nextLong(500L, 3_000L)
                    }
                } else {
                    positionMs + rnd.nextLong(500L, 3_000L)
                }
            }
            else -> positionMs + rnd.nextLong(500L, 3_000L)
        }
        return MixEvent.Tick(positionMs = pos.coerceAtLeast(0L), isPlaying = isPlaying)
    }

    private fun makeNextKnown(rnd: Random, positionMs: Long): MixEvent.NextKnown {
        val pair = if (rnd.nextInt(100) < 20) {
            PairKey(outTrackId = "out-B", inTrackId = "in-B")
        } else {
            PairKey(outTrackId = "out-A", inTrackId = "in-A")
        }
        val level: DjLevel? = if (rnd.nextInt(100) < 70) null else randomLevel(rnd)
        return MixEvent.NextKnown(
            pair = pair,
            positionMs = positionMs,
            djLevel = level,
            isPlaying = rnd.nextInt(100) < 90,
        )
    }

    private fun makeQueueOrNext(state: MixState, rnd: Random, positionMs: Long): MixEvent {
        return if (rnd.nextBoolean()) {
            MixEvent.QueueEdited(nextTrackId = randomNextTrack(rnd), positionMs = positionMs)
        } else {
            makeNextKnown(rnd, positionMs)
        }
    }

    private fun makePlanFrozen(state: MixState, rnd: Random, positionMs: Long): MixEvent {
        val pair = state.pair ?: PairKey(outTrackId = "out-A", inTrackId = "in-A")
        val exit = when (rnd.nextInt(100)) {
            in 0..59 -> positionMs + rnd.nextLong(60_000L, 180_000L)
            in 60..79 -> positionMs + rnd.nextLong(20_000L, 40_000L)
            else -> positionMs + rnd.nextLong(6_000L, 12_000L)
        }
        val requiredStems: Set<DeckStemRequirement> = when (rnd.nextInt(100)) {
            in 0..49 -> emptySet()
            in 50..79 -> setOf(
                DeckStemRequirement(deck = DeckTarget.OUT, stem = StemKind.VOCALS),
                DeckStemRequirement(deck = DeckTarget.IN, stem = StemKind.VOCALS),
            )
            else -> setOf(
                DeckStemRequirement(deck = DeckTarget.OUT, stem = StemKind.VOCALS),
            )
        }
        val hasNoStem = rnd.nextInt(100) < 60
        val nextEligible: Long? = if (rnd.nextInt(100) < 40) null else exit + rnd.nextLong(40_000L, 100_000L)
        val lastPhrase: Long? = if (rnd.nextInt(100) < 40) null else exit + rnd.nextLong(20_000L, 60_000L)
        val recipeId = "recipe-${rnd.nextInt(4)}"
        val geos = TransitionGeometry.entries
        val plan = TransitionPlanV1(
            planId = "plan-${state.generation}-${rnd.nextInt(1_000_000)}",
            generation = state.generation,
            outTrackId = pair.outTrackId,
            inTrackId = pair.inTrackId,
            djLevel = randomLevel(rnd),
            geometry = geos[rnd.nextInt(geos.size)],
            frozenExitMs = exit,
            frozenEntryMs = (exit - rnd.nextLong(8_000L, 40_000L)).coerceAtLeast(0L),
            swapDownbeatMs = exit,
            overlapDurationMs = rnd.nextLong(8_000L, 40_000L),
            barDurationMs = rnd.nextLong(1_500L, 2_500L),
            recipeId = recipeId,
            recipeName = "Property $recipeId",
            requiredStems = requiredStems,
            hasNoStemVariant = hasNoStem,
            nextEligiblePhraseMs = nextEligible,
            lastPhraseDownbeatMs = lastPhrase,
        )
        return MixEvent.PlanFrozen(plan = plan, generation = state.generation)
    }

    private fun makeAnalysis(state: MixState, rnd: Random): MixEvent.AnalysisReady {
        val pair = state.pair ?: PairKey(outTrackId = "out-A", inTrackId = "in-A")
        val trackId = if (rnd.nextBoolean()) pair.outTrackId else pair.inTrackId
        val gen = if (rnd.nextInt(100) < 85) state.generation else state.generation + 1L + rnd.nextInt(5).toLong()
        return MixEvent.AnalysisReady(
            trackId = trackId,
            level = randomAnalysisLevel(rnd),
            generation = gen,
        )
    }

    private fun stalePlanId(rnd: Random): String = "stale-plan-${rnd.nextInt(100_000)}"

    private fun staleGen(gen: Long, rnd: Random): Long = gen + 1L + rnd.nextInt(5).toLong()

    private fun makeRegions(state: MixState, rnd: Random): MixEvent.RegionsReady {
        val plan = state.plan
        return if (plan != null && rnd.nextInt(100) < 85) {
            MixEvent.RegionsReady(planId = plan.planId, generation = state.generation)
        } else {
            MixEvent.RegionsReady(planId = stalePlanId(rnd), generation = staleGen(state.generation, rnd))
        }
    }

    private fun makeStems(state: MixState, rnd: Random): MixEvent.StemsReady {
        val plan = state.plan
        val required = plan?.requiredStems?.size ?: 2
        val completed = if (rnd.nextInt(100) < 70) required else (required - 1).coerceAtLeast(0)
        return if (plan != null && rnd.nextInt(100) < 85) {
            MixEvent.StemsReady(
                planId = plan.planId,
                generation = state.generation,
                stemsCompleted = completed,
                stemsRequired = required,
            )
        } else {
            MixEvent.StemsReady(
                planId = stalePlanId(rnd),
                generation = staleGen(state.generation, rnd),
                stemsCompleted = completed,
                stemsRequired = required,
            )
        }
    }

    private fun makeRenderer(state: MixState, rnd: Random): MixEvent.RendererPrepared {
        val plan = state.plan
        return if (plan != null && rnd.nextInt(100) < 85) {
            MixEvent.RendererPrepared(
                planId = plan.planId,
                generation = state.generation,
                usedNoStemVariant = rnd.nextInt(100) < 30,
            )
        } else {
            MixEvent.RendererPrepared(
                planId = stalePlanId(rnd),
                generation = staleGen(state.generation, rnd),
                usedNoStemVariant = rnd.nextBoolean(),
            )
        }
    }

    private fun makeStaleCompletion(state: MixState, rnd: Random): MixEvent {
        return when (rnd.nextInt(3)) {
            0 -> MixEvent.RegionsReady(planId = stalePlanId(rnd), generation = staleGen(state.generation, rnd))
            1 -> MixEvent.StemsReady(
                planId = stalePlanId(rnd),
                generation = staleGen(state.generation, rnd),
                stemsCompleted = 0,
                stemsRequired = 2,
            )
            else -> MixEvent.RendererPrepared(
                planId = stalePlanId(rnd),
                generation = staleGen(state.generation, rnd),
                usedNoStemVariant = rnd.nextBoolean(),
            )
        }
    }

    private fun randomLevel(rnd: Random): DjLevel {
        val levels = DjLevel.entries
        return levels[rnd.nextInt(levels.size)]
    }

    private fun randomThermal(rnd: Random): ThermalLevel {
        val levels = ThermalLevel.entries
        return levels[rnd.nextInt(levels.size)]
    }

    private fun randomAnalysisLevel(rnd: Random): AnalysisLevel {
        val levels = AnalysisLevel.entries
        return levels[rnd.nextInt(levels.size)]
    }

    private fun randomFailure(rnd: Random): FailureKind {
        val kinds = FailureKind.entries
        return kinds[rnd.nextInt(kinds.size)]
    }

    private fun randomLatency(rnd: Random): Long = when (rnd.nextInt(4)) {
        0 -> 0L
        1 -> 80L
        2 -> 150L
        else -> 250L
    }

    private fun randomNextTrack(rnd: Random): String? {
        return when (rnd.nextInt(100)) {
            in 0..69 -> "in-A"
            in 70..84 -> "in-B"
            in 85..94 -> "in-C"
            else -> null
        }
    }

    private fun randomSeek(rnd: Random, positionMs: Long): Long {
        return when (rnd.nextInt(100)) {
            in 0..49 -> (positionMs + rnd.nextLong(0L, 5_000L)).coerceAtLeast(0L)
            in 50..79 -> rnd.nextLong(0L, 10_000L)
            else -> positionMs + rnd.nextLong(10_000L, 30_000L)
        }
    }
}

private class PropertyTracker {
    private val prepared = mutableSetOf<Pair<String, Long>>()
    private var activeRunning: Pair<String, Long>? = null
    private var stickyPaused = false

    var brokenCount = 0
        private set
    var startCount = 0
        private set
    var armedFireCount = 0
        private set

    fun check(
        seed: Long,
        step: Int,
        prev: MixState,
        event: MixEvent,
        next: MixState,
        effects: List<MixEffect>,
        config: MixMachineConfig,
    ) {
        fun diag(invariant: String, extra: String = ""): String =
            "seed=$seed step=$step state=$prev event=$event next=$next effects=$effects " +
                "invariant=$invariant $extra"

        if (event is MixEvent.RendererPrepared) {
            val prepLike = prev is MixState.Preparing || prev is MixState.Moved || prev is MixState.LastResort
            if (prepLike && next.generation == event.generation && next.plan?.planId == event.planId) {
                prepared += event.planId to event.generation
            }
        }

        for (fx in effects) {
            if (fx is MixEffect.ShowUi) {
                val ui = fx.uiState
                if (ui.countdownVisible && ui.phase != MixUiPhase.READY && ui.phase != MixUiPhase.ARMED) {
                    error(diag("honest-countdown-showui", "ui=$ui"))
                }
            }
            if (fx is MixEffect.Emit) {
                val t = fx.telemetry
                if (t.eventType == TelemetryEventType.MIX_COUNTDOWN_BROKEN) {
                    error(diag("honest-countdown-broken-event", "telemetry=$t"))
                }
                if (t.countdownBroken == true) {
                    brokenCount += 1
                    error(diag("honest-countdown-broken-flag", "telemetry=$t"))
                }
            }
        }

        val nextUi = MixMachine.uiStateFor(next, config)
        if (nextUi.countdownVisible && next !is MixState.Ready && next !is MixState.Armed) {
            error(diag("honest-countdown-uiStateFor", "ui=$nextUi"))
        }
        if ((next is MixState.Ready || next is MixState.Armed) && !nextUi.countdownVisible) {
            error(diag("honest-countdown-missing", "ui=$nextUi"))
        }

        val starts = effects.filterIsInstance<MixEffect.Start>()
        if (starts.size > 1) {
            error(diag("at-most-one-running", "starts=$starts"))
        }

        val targetFor: Long? = when (prev) {
            is MixState.Armed -> prev.armAtMs
            is MixState.Ready -> prev.plan.frozenExitMs
            is MixState.LastResort -> prev.targetDownbeatMs
            else -> null
        }
        val effectivePos: Long? = when {
            event is MixEvent.Tick -> event.positionMs
            prev is MixState.Armed -> prev.lastPositionMs
            prev is MixState.Ready -> prev.lastPositionMs
            prev is MixState.LastResort -> prev.lastPositionMs
            else -> null
        }

        for (start in starts) {
            startCount += 1
            if (prev is MixState.Running || prev is MixState.HandingOver) {
                error(diag("at-most-one-running-prev", "start=$start"))
            }
            if (next !is MixState.Running) {
                error(diag("at-most-one-running-next", "start=$start"))
            }
            if (activeRunning != null) {
                error(diag("at-most-one-running-active", "start=$start active=$activeRunning"))
            }
            if (start.planId != next.plan.planId || start.generation != next.generation) {
                error(diag("at-most-one-running-identity", "start=$start"))
            }
            if ((start.planId to start.generation) !in prepared) {
                error(diag("prepared-only-start", "start=$start prepared=$prepared"))
            }
            if (targetFor != null && effectivePos != null && effectivePos < targetFor - config.countdownToleranceMs) {
                error(
                    diag(
                        "honest-countdown-early-start",
                        "start=$start target=$targetFor pos=$effectivePos tol=${config.countdownToleranceMs}",
                    ),
                )
            }
        }

        if (prev is MixState.Armed && event is MixEvent.Tick && event.isPlaying &&
            !prev.userPaused && !prev.focusPaused &&
            event.positionMs >= prev.armAtMs - config.countdownToleranceMs
        ) {
            if (starts.isEmpty() || next !is MixState.Running) {
                error(
                    diag(
                        "honest-countdown-armed-fire",
                        "armAt=${prev.armAtMs} pos=${event.positionMs} tol=${config.countdownToleranceMs}",
                    ),
                )
            }
            armedFireCount += 1
        }

        val hasResume = effects.any { it is MixEffect.ResumeSync }
        if (hasResume && event !is MixEvent.UserResume) {
            error(diag("sticky-user-pause-resume-sync", "effects=$effects"))
        }
        val wasSticky = stickyPaused
        if (wasSticky && event !is MixEvent.UserResume) {
            if (hasResume) {
                error(diag("sticky-user-pause-resume-sync", "effects=$effects"))
            }
            if (!next.userPaused) {
                error(diag("sticky-user-pause-flag", "userPaused=${next.userPaused}"))
            }
        }
        if (event is MixEvent.UserPause && !next.userPaused) {
            error(diag("sticky-user-pause-flag", "after UserPause userPaused=${next.userPaused}"))
        }
        if (event is MixEvent.UserPause) stickyPaused = true
        if (event is MixEvent.UserResume) stickyPaused = false

        val prevSurviving: DeckTarget? = when (prev) {
            is MixState.Running -> prev.survivingDeck
            is MixState.HandingOver -> prev.survivingDeck
            else -> null
        }
        for (retire in effects.filterIsInstance<MixEffect.Retire>()) {
            if (!retire.preserveSurvivor) {
                error(diag("surviving-deck-preserve", "retire=$retire"))
            }
            if (prevSurviving != null && retire.deck == prevSurviving) {
                error(diag("surviving-deck", "retire=$retire surviving=$prevSurviving"))
            }
            if ((prev is MixState.Running || prev is MixState.HandingOver) && retire.deck != DeckTarget.OUT) {
                error(diag("surviving-deck", "retire=$retire expected OUT"))
            }
        }

        if (next is MixState.Running) {
            activeRunning = next.plan.planId to next.generation
        } else if (next is MixState.HandingOver || next is MixState.Done ||
            next is MixState.Suspended || next is MixState.Failed || next is MixState.Idle
        ) {
            activeRunning = null
        } else {
            if (activeRunning != null) {
                error(diag("at-most-one-running-leak", "active=$activeRunning next=$next"))
            }
        }
    }
}
