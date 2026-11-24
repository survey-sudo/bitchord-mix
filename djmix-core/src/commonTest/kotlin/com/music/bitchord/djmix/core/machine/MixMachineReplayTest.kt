package com.music.bitchord.djmix.core.machine

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.CandidateScoreSummary
import com.music.bitchord.djmix.core.contracts.DeckStemRequirement
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.StemKind
import com.music.bitchord.djmix.core.contracts.TelemetryEventType
import com.music.bitchord.djmix.core.contracts.TempoKnot
import com.music.bitchord.djmix.core.contracts.ThermalLevel
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Part B2 item 3: Json round-trip + deterministic replay + 8 end-to-end device replays.
 *
 * All scenarios use the default config (bar 2_000ms => commitLead 8_000ms, countdown
 * tolerance 50ms, remix lead 20_000ms) with frozen exit 180_000ms unless stated otherwise.
 */
class MixMachineReplayTest {

    private val pairA = PairKey(outTrackId = "out-A", inTrackId = "in-A")
    private val pairB = PairKey(outTrackId = "out-B", inTrackId = "in-B")

    private fun twoStems(): Set<DeckStemRequirement> = setOf(
        DeckStemRequirement(deck = DeckTarget.OUT, stem = StemKind.VOCALS),
        DeckStemRequirement(deck = DeckTarget.IN, stem = StemKind.VOCALS),
    )

    private fun testPlan(
        planId: String,
        generation: Long,
        outTrackId: String = "out-A",
        inTrackId: String = "in-A",
        djLevel: DjLevel = DjLevel.SUBTLE,
        frozenExitMs: Long = 180_000L,
        overlapDurationMs: Long = 16_000L,
        barDurationMs: Long = 2_000L,
        requiredStems: Set<DeckStemRequirement> = emptySet(),
        hasNoStemVariant: Boolean = true,
        nextEligiblePhraseMs: Long? = null,
        lastPhraseDownbeatMs: Long? = null,
        recipeId: String = "recipe-blend",
        recipeName: String = "Smooth Blend",
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
        recipeId = recipeId,
        recipeName = recipeName,
        requiredStems = requiredStems,
        hasNoStemVariant = hasNoStemVariant,
        nextEligiblePhraseMs = nextEligiblePhraseMs,
        lastPhraseDownbeatMs = lastPhraseDownbeatMs,
    )

    private fun richPlan(generation: Long): TransitionPlanV1 = TransitionPlanV1(
        planId = "plan-rich",
        generation = generation,
        outTrackId = "out-A",
        inTrackId = "in-A",
        djLevel = DjLevel.CLUB,
        geometry = TransitionGeometry.LOOP,
        frozenExitMs = 180_000L,
        frozenEntryMs = 164_000L,
        swapDownbeatMs = 180_000L,
        overlapDurationMs = 16_000L,
        barDurationMs = 2_000L,
        tempoKnots = listOf(
            TempoKnot(mixTimeMs = 0L, trackPosMs = 0L, bpm = 124.0, exactMixSec = 0.0, exactTrackPosSec = 0.0),
            TempoKnot(mixTimeMs = 180_000L, trackPosMs = 164_000L, bpm = 124.5, exactMixSec = 180.0, exactTrackPosSec = 164.0),
        ),
        recipeId = "recipe-rich",
        recipeName = "Rich Blend",
        recipeDescription = "rich scenario",
        recipeParams = mapOf("wet" to 0.5, "depth" to 0.75),
        requiredStems = twoStems(),
        hasNoStemVariant = true,
        placementReasons = listOf("phrase-aligned", "tempo-match"),
        topScores = listOf(
            CandidateScoreSummary(recipeId = "recipe-rich", recipeName = "Rich Blend", score = 0.92, reason = "best"),
            CandidateScoreSummary(recipeId = "recipe-alt", recipeName = "Alt Blend", score = 0.71, reason = "fallback"),
        ),
        tasteAdjustment = 0.25,
        nextEligiblePhraseMs = 260_000L,
        lastPhraseDownbeatMs = 240_000L,
    )

    private fun hasStart(step: ReplayStep): Boolean = step.effects.any { it is MixEffect.Start }
    private fun hasArm(step: ReplayStep): Boolean = step.effects.any { it is MixEffect.Arm }
    private fun hasHandover(step: ReplayStep): Boolean = step.effects.any { it is MixEffect.Handover }
    private fun hasShowUi(step: ReplayStep): Boolean = step.effects.any { it is MixEffect.ShowUi }

    private fun hasPrepareRenderer(step: ReplayStep, useNoStem: Boolean): Boolean =
        step.effects.filterIsInstance<MixEffect.PrepareRenderer>().any { it.useNoStemVariant == useNoStem }

    private fun hasStartPrepare(step: ReplayStep): Boolean =
        step.effects.any { it is MixEffect.StartPrepare }

    private fun hasEmit(step: ReplayStep, type: TelemetryEventType): Boolean =
        step.effects.filterIsInstance<MixEffect.Emit>().any { it.telemetry.eventType == type }

    private fun retireDecks(step: ReplayStep): List<DeckTarget> =
        step.effects.filterIsInstance<MixEffect.Retire>().map { it.deck }

    private fun kinds(steps: List<ReplayStep>): List<MixStateKind> =
        steps.map { it.nextState.stateKind }

    @Test
    fun jsonRoundTripPreservesReplayExactly() {
        val log: List<MixEvent> = listOf(
            MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, djLevel = DjLevel.SUBTLE, isPlaying = true),
            MixEvent.QueueEdited(nextTrackId = "in-A", positionMs = 10_000L),
            MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = 1L),
            MixEvent.AnalysisReady(
                trackId = "in-A",
                level = AnalysisLevel.HEAD_TAIL,
                generation = 1L,
                usable = false,
                refusalReason = RefusalReason.WAITING_ON_ANALYSIS,
            ),
            MixEvent.PlanFrozen(plan = richPlan(generation = 1L), generation = 1L),
            MixEvent.RegionsReady(planId = "plan-rich", generation = 1L),
            MixEvent.StemsReady(
                planId = "plan-rich",
                generation = 1L,
                stemsCompleted = 2,
                stemsRequired = 2,
                stemLatencyOutMs = 120L,
                stemLatencyInMs = 140L,
            ),
            MixEvent.RendererPrepared(planId = "plan-rich", generation = 1L, usedNoStemVariant = false),
            MixEvent.Tick(positionMs = 174_000L, isPlaying = true),
            MixEvent.UserSeek(toMs = 20_000L),
            MixEvent.UserSkip,
            MixEvent.UserPause,
            MixEvent.UserResume,
            MixEvent.AudioFocusLost(transient = true),
            MixEvent.AudioFocusLost(transient = false),
            MixEvent.RouteChanged(btLatencyMs = 150L),
            MixEvent.Backgrounded(isBackgrounded = true),
            MixEvent.Backgrounded(isBackgrounded = false),
            MixEvent.ThermalChanged(status = ThermalLevel.SEVERE),
            MixEvent.RendererError(
                code = FailureKind.STEM_ERROR,
                message = "stem miss",
                recoverable = true,
            ),
            MixEvent.HandoverComplete,
            MixEvent.LevelChanged(level = DjLevel.CLUB),
            MixEvent.RemixRequested,
            MixEvent.QueueEdited(nextTrackId = null, positionMs = 30_000L),
        )
        val json = Json { ignoreUnknownKeys = true; explicitNulls = true; encodeDefaults = true }
        val serializer = ListSerializer(MixEvent.serializer())
        val encoded = json.encodeToString(serializer, log)
        val decoded = json.decodeFromString(serializer, encoded)
        assertEquals(log, decoded)
        val initial = MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE)
        val raw = MixMachine.replay(initial, log)
        val fromDecoded = MixMachine.replay(initial, decoded)
        assertEquals(raw, fromDecoded)
        assertEquals(raw.size, log.size)
    }

    @Test
    fun scenario1HappyPathFullStemTransition() {
        val b = ReplayLogBuilder()
        b.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        b.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = b.generation()))
        b.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = b.generation()))
        val plan = testPlan(planId = "plan-s1", generation = b.generation(), requiredStems = twoStems(), hasNoStemVariant = true)
        b.emit(MixEvent.PlanFrozen(plan = plan, generation = b.generation()))
        b.emit(MixEvent.RegionsReady(planId = plan.planId, generation = b.generation()))
        b.emit(MixEvent.StemsReady(planId = plan.planId, generation = b.generation(), stemsCompleted = 2, stemsRequired = 2))
        b.emit(MixEvent.RendererPrepared(planId = plan.planId, generation = b.generation(), usedNoStemVariant = false))
        b.emit(MixEvent.Tick(positionMs = 174_000L, isPlaying = true))
        b.emit(MixEvent.Tick(positionMs = 180_000L, isPlaying = true))
        b.emit(MixEvent.Tick(positionMs = 196_000L, isPlaying = true))
        b.emit(MixEvent.HandoverComplete)

        val steps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), b.events)
        assertEquals(
            listOf(
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.READY,
                MixStateKind.ARMED,
                MixStateKind.RUNNING,
                MixStateKind.HANDING_OVER,
                MixStateKind.DONE,
            ),
            kinds(steps),
        )
        assertTrue(hasStartPrepare(steps[0]), "step0 must emit StartPrepare")
        assertTrue(hasEmit(steps[0], TelemetryEventType.MIX_PREP_STARTED), "step0 must emit prep started")
        assertTrue(hasShowUi(steps[0]), "step0 must emit ShowUi")
        assertFalse(hasPrepareRenderer(steps[4], useNoStem = false), "regions alone must not emit PrepareRenderer while stems pending")
        assertTrue(hasPrepareRenderer(steps[5], useNoStem = false), "stems completion must emit PrepareRenderer with stems")
        assertTrue(hasEmit(steps[6], TelemetryEventType.MIX_READY), "renderer promotion must emit MIX_READY")
        assertFalse(hasArm(steps[6]), "early promotion to Ready must not emit Arm yet")
        assertTrue(hasArm(steps[7]), "commit tick must emit Arm")
        assertTrue(hasEmit(steps[7], TelemetryEventType.MIX_ARMED), "commit tick must emit MIX_ARMED")
        assertTrue(hasStart(steps[8]), "exit tick must emit Start")
        assertTrue(hasEmit(steps[8], TelemetryEventType.MIX_FIRE), "exit tick must emit MIX_FIRE")
        assertTrue(hasHandover(steps[9]), "handover tick must emit Handover")
        assertTrue(hasEmit(steps[9], TelemetryEventType.MIX_HANDOVER), "handover tick must emit MIX_HANDOVER")
        assertEquals(listOf(DeckTarget.OUT), retireDecks(steps[10]))
        assertTrue(hasEmit(steps[10], TelemetryEventType.MIX_DONE), "handover complete must emit MIX_DONE")
        val running = steps[8].nextState as MixState.Running
        assertEquals(180_000L, running.startedAtMs)
        assertEquals("plan-s1", running.plan.planId)
        assertEquals(1L, running.generation)
        assertEquals(DjLevel.SUBTLE, running.djLevel)
        assertFalse(steps.any { it.nextState is MixState.Moved || it.nextState is MixState.LastResort })
    }

    @Test
    fun scenario2StemsMissingAtCommitUsesNoStemVariantWithoutMoving() {
        val b = ReplayLogBuilder()
        b.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        b.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = b.generation()))
        b.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = b.generation()))
        val plan = testPlan(planId = "plan-s2", generation = b.generation(), requiredStems = twoStems(), hasNoStemVariant = true)
        b.emit(MixEvent.PlanFrozen(plan = plan, generation = b.generation()))
        b.emit(MixEvent.RegionsReady(planId = plan.planId, generation = b.generation()))
        b.emit(MixEvent.Tick(positionMs = 174_000L, isPlaying = true))
        b.emit(MixEvent.RendererPrepared(planId = plan.planId, generation = b.generation(), usedNoStemVariant = true))
        b.emit(MixEvent.Tick(positionMs = 180_000L, isPlaying = true))
        b.emit(MixEvent.Tick(positionMs = 196_000L, isPlaying = true))
        b.emit(MixEvent.HandoverComplete)

        val steps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), b.events)
        assertEquals(
            listOf(
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.ARMED,
                MixStateKind.RUNNING,
                MixStateKind.HANDING_OVER,
                MixStateKind.DONE,
            ),
            kinds(steps),
        )
        assertTrue(hasPrepareRenderer(steps[5], useNoStem = true), "missed stems at commit must emit PrepareRenderer with no-stem variant")
        assertFalse(steps.any { it.nextState is MixState.Moved }, "no-stem fallback must not move the exit")
        assertFalse(steps.any { hasEmit(it, TelemetryEventType.MIX_MOVED) }, "no-stem fallback must not emit MIX_MOVED")
        assertTrue(hasArm(steps[6]), "renderer promotion at commit must emit Arm")
        assertTrue(hasStart(steps[7]), "exit tick must emit Start")
        val armed = steps[6].nextState as MixState.Armed
        assertEquals(180_000L, armed.plan.frozenExitMs)
        assertEquals(180_000L, armed.armAtMs)
        assertEquals(DjLevel.SUBTLE, armed.djLevel)
        assertTrue(armed.usingNoStemVariant, "armed state must use the no-stem variant")
        val running = steps[7].nextState as MixState.Running
        assertEquals(DjLevel.SUBTLE, running.djLevel)
        assertEquals(180_000L, running.plan.frozenExitMs)
        assertTrue(running.usingNoStemVariant, "running state must use the no-stem variant")
    }

    @Test
    fun scenario3SlowRegionsMoveToLaterPhrase() {
        val b = ReplayLogBuilder()
        b.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        b.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = b.generation()))
        b.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = b.generation()))
        val planA = testPlan(
            planId = "plan-s3a",
            generation = b.generation(),
            requiredStems = emptySet(),
            hasNoStemVariant = false,
            nextEligiblePhraseMs = 260_000L,
            lastPhraseDownbeatMs = null,
        )
        b.emit(MixEvent.PlanFrozen(plan = planA, generation = b.generation()))
        b.emit(MixEvent.Tick(positionMs = 174_000L, isPlaying = true))
        val planB = testPlan(
            planId = "plan-s3b",
            generation = b.generation(),
            frozenExitMs = 260_000L,
            requiredStems = emptySet(),
            hasNoStemVariant = true,
        )
        b.emit(MixEvent.PlanFrozen(plan = planB, generation = b.generation()))
        b.emit(MixEvent.RegionsReady(planId = planB.planId, generation = b.generation()))
        b.emit(MixEvent.RendererPrepared(planId = planB.planId, generation = b.generation(), usedNoStemVariant = false))
        b.emit(MixEvent.Tick(positionMs = 254_000L, isPlaying = true))
        b.emit(MixEvent.Tick(positionMs = 260_000L, isPlaying = true))
        b.emit(MixEvent.Tick(positionMs = 276_000L, isPlaying = true))
        b.emit(MixEvent.HandoverComplete)

        val steps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), b.events)
        assertEquals(
            listOf(
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.MOVED,
                MixStateKind.MOVED,
                MixStateKind.MOVED,
                MixStateKind.READY,
                MixStateKind.ARMED,
                MixStateKind.RUNNING,
                MixStateKind.HANDING_OVER,
                MixStateKind.DONE,
            ),
            kinds(steps),
        )
        assertTrue(hasEmit(steps[4], TelemetryEventType.MIX_MOVED), "missed commit must emit MIX_MOVED")
        val moved = steps[4].nextState as MixState.Moved
        assertEquals(180_000L, moved.fromExitMs)
        assertEquals(260_000L, moved.newExitMs)
        assertEquals(2L, moved.generation)
        assertTrue(steps[4].effects.any { it is MixEffect.CancelPrepare }, "move must cancel the old preparation")
        val startPrep = steps[4].effects.filterIsInstance<MixEffect.StartPrepare>().firstOrNull()
        assertTrue(startPrep != null, "move must emit StartPrepare")
        assertEquals(2L, startPrep!!.generation)
        assertEquals(260_000L, startPrep.minExitMs)
        assertTrue(hasPrepareRenderer(steps[6], useNoStem = false), "re-prepared regions must emit PrepareRenderer")
        assertTrue(hasStart(steps[9]), "new exit tick must emit Start")
        val running = steps[9].nextState as MixState.Running
        assertEquals(260_000L, running.plan.frozenExitMs)
        assertEquals(260_000L, running.startedAtMs)
        assertFalse(steps.any { it.nextState is MixState.LastResort || it.nextState is MixState.Failed })
    }

    @Test
    fun scenario4NoLaterPhraseFallsBackToLastResortDownbeat() {
        val b = ReplayLogBuilder()
        b.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        b.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = b.generation()))
        b.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = b.generation()))
        val plan = testPlan(
            planId = "plan-s4",
            generation = b.generation(),
            overlapDurationMs = 40_000L,
            requiredStems = emptySet(),
            hasNoStemVariant = false,
            nextEligiblePhraseMs = null,
            lastPhraseDownbeatMs = 210_000L,
        )
        b.emit(MixEvent.PlanFrozen(plan = plan, generation = b.generation()))
        b.emit(MixEvent.Tick(positionMs = 174_000L, isPlaying = true))
        b.emit(MixEvent.RendererPrepared(planId = plan.planId, generation = b.generation(), usedNoStemVariant = true))
        b.emit(MixEvent.Tick(positionMs = 210_000L, isPlaying = true))
        b.emit(MixEvent.Tick(positionMs = 215_000L, isPlaying = true))
        b.emit(MixEvent.Tick(positionMs = 220_000L, isPlaying = true))
        b.emit(MixEvent.HandoverComplete)

        val steps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), b.events)
        assertEquals(
            listOf(
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.PREPARING,
                MixStateKind.LAST_RESORT,
                MixStateKind.LAST_RESORT,
                MixStateKind.RUNNING,
                MixStateKind.RUNNING,
                MixStateKind.HANDING_OVER,
                MixStateKind.DONE,
            ),
            kinds(steps),
        )
        assertTrue(hasEmit(steps[4], TelemetryEventType.MIX_LAST_RESORT), "dead end must emit MIX_LAST_RESORT")
        assertTrue(hasPrepareRenderer(steps[4], useNoStem = true), "last resort must emit PrepareRenderer with no-stem variant")
        val lastResort = steps[4].nextState as MixState.LastResort
        assertEquals(210_000L, lastResort.targetDownbeatMs)
        assertEquals(210_000L, lastResort.plan.lastPhraseDownbeatMs)
        assertTrue(lastResort.plan.isLastResort, "last resort plan must be flagged")
        assertTrue(steps[5].nextState is MixState.LastResort, "prepared last resort must wait for the downbeat")
        val prepared = steps[5].nextState as MixState.LastResort
        assertTrue(prepared.rendererPrepared, "last resort must be prepared before firing")
        assertTrue(hasStart(steps[6]), "downbeat tick must emit Start")
        val running = steps[6].nextState as MixState.Running
        assertEquals(210_000L, running.startedAtMs)
        assertTrue(running.plan.isLastResort, "last resort running must keep the flag")
        assertFalse(steps.any { it.nextState is MixState.Moved || it.nextState is MixState.Failed })
    }

    @Test
    fun scenario5UserPauseMidRunningStaysPausedUntilResume() {
        val b = ReplayLogBuilder()
        b.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        b.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = b.generation()))
        b.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = b.generation()))
        val plan = testPlan(planId = "plan-s5", generation = b.generation(), requiredStems = twoStems(), hasNoStemVariant = true)
        b.emit(MixEvent.PlanFrozen(plan = plan, generation = b.generation()))
        b.emit(MixEvent.RegionsReady(planId = plan.planId, generation = b.generation()))
        b.emit(MixEvent.StemsReady(planId = plan.planId, generation = b.generation(), stemsCompleted = 2, stemsRequired = 2))
        b.emit(MixEvent.RendererPrepared(planId = plan.planId, generation = b.generation(), usedNoStemVariant = false))
        b.emit(MixEvent.Tick(positionMs = 174_000L, isPlaying = true))
        b.emit(MixEvent.Tick(positionMs = 180_000L, isPlaying = true))
        b.emit(MixEvent.UserPause)
        b.emit(MixEvent.Tick(positionMs = 190_000L, isPlaying = true))
        b.emit(MixEvent.Tick(positionMs = 200_000L, isPlaying = true))
        b.emit(MixEvent.AudioFocusLost(transient = true))
        b.emit(MixEvent.RouteChanged(btLatencyMs = 150L))
        b.emit(MixEvent.Tick(positionMs = 205_000L, isPlaying = true))
        b.emit(MixEvent.UserResume)
        b.emit(MixEvent.Tick(positionMs = 205_000L, isPlaying = true))
        b.emit(MixEvent.HandoverComplete)

        val steps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), b.events)
        assertEquals(18, steps.size)
        val paused = steps[9].nextState as MixState.Running
        assertTrue(paused.userPaused, "UserPause must set the sticky pause flag")
        assertFalse(paused.isPlaying, "paused running must not be playing")
        assertTrue(steps[9].effects.any { it is MixEffect.PauseSync }, "UserPause must emit PauseSync")
        for (i in 10..14) {
            assertTrue(steps[i].nextState is MixState.Running, "step $i must stay Running while paused")
            assertTrue((steps[i].nextState as MixState.Running).userPaused, "step $i must stay user-paused")
            assertFalse(hasHandover(steps[i]), "step $i must not hand over while paused")
            assertFalse(steps[i].effects.any { it is MixEffect.ResumeSync }, "step $i must not emit ResumeSync while paused")
        }
        assertTrue((steps[12].nextState as MixState.Running).focusPaused, "transient focus loss must set focusPaused")
        assertTrue(steps[12].effects.any { it is MixEffect.PauseSync }, "transient focus loss must emit PauseSync")
        assertEquals(150L, (steps[13].nextState as MixState.Running).btLatencyMs)
        assertFalse(steps[13].effects.any { it is MixEffect.ResumeSync }, "route change must not resume")
        val resumed = steps[15].nextState as MixState.Running
        assertFalse(resumed.userPaused, "UserResume must clear the sticky flag")
        assertFalse(resumed.focusPaused, "UserResume must clear focusPaused")
        assertTrue(steps[15].effects.any { it is MixEffect.ResumeSync }, "UserResume must emit ResumeSync")
        assertEquals(MixStateKind.HANDING_OVER, steps[16].nextState.stateKind)
        assertEquals(MixStateKind.DONE, steps[17].nextState.stateKind)
        assertEquals(MixStateKind.DONE, steps.last().nextState.stateKind)
    }

    @Test
    fun scenario6SeekAndSkipPreserveTheSurvivingDeck() {
        val armedBuilder = ReplayLogBuilder()
        armedBuilder.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        armedBuilder.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = armedBuilder.generation()))
        armedBuilder.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = armedBuilder.generation()))
        val armedPlan = testPlan(planId = "plan-s6a", generation = armedBuilder.generation(), requiredStems = emptySet(), hasNoStemVariant = true)
        armedBuilder.emit(MixEvent.PlanFrozen(plan = armedPlan, generation = armedBuilder.generation()))
        armedBuilder.emit(MixEvent.RegionsReady(planId = armedPlan.planId, generation = armedBuilder.generation()))
        armedBuilder.emit(MixEvent.RendererPrepared(planId = armedPlan.planId, generation = armedBuilder.generation(), usedNoStemVariant = false))
        armedBuilder.emit(MixEvent.Tick(positionMs = 174_000L, isPlaying = true))
        armedBuilder.emit(MixEvent.UserSeek(toMs = 20_000L))
        val armedPlan2 = testPlan(planId = "plan-s6a2", generation = armedBuilder.generation(), requiredStems = emptySet(), hasNoStemVariant = true)
        armedBuilder.emit(MixEvent.PlanFrozen(plan = armedPlan2, generation = armedBuilder.generation()))
        armedBuilder.emit(MixEvent.RegionsReady(planId = armedPlan2.planId, generation = armedBuilder.generation()))
        armedBuilder.emit(MixEvent.RendererPrepared(planId = armedPlan2.planId, generation = armedBuilder.generation(), usedNoStemVariant = false))
        armedBuilder.emit(MixEvent.Tick(positionMs = 174_000L, isPlaying = true))
        armedBuilder.emit(MixEvent.UserSkip)
        val armedSteps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), armedBuilder.events)
        assertEquals(MixStateKind.ARMED, armedSteps[6].nextState.stateKind)
        assertEquals(MixStateKind.PREPARING, armedSteps[7].nextState.stateKind)
        assertEquals(listOf(DeckTarget.IN), retireDecks(armedSteps[7]))
        assertTrue(hasStartPrepare(armedSteps[7]), "seek in Armed must re-prepare")
        assertEquals(MixStateKind.ARMED, armedSteps[11].nextState.stateKind)
        assertEquals(MixStateKind.IDLE, armedSteps[12].nextState.stateKind)
        assertEquals(listOf(DeckTarget.IN), retireDecks(armedSteps[12]))
        assertTrue(hasEmit(armedSteps[12], TelemetryEventType.MIX_REFUSED), "skip in Armed must emit MIX_REFUSED")

        val runningBuilder = ReplayLogBuilder()
        runningBuilder.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        runningBuilder.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = runningBuilder.generation()))
        runningBuilder.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = runningBuilder.generation()))
        val runningPlan = testPlan(planId = "plan-s6b", generation = runningBuilder.generation(), requiredStems = emptySet(), hasNoStemVariant = true)
        runningBuilder.emit(MixEvent.PlanFrozen(plan = runningPlan, generation = runningBuilder.generation()))
        runningBuilder.emit(MixEvent.RegionsReady(planId = runningPlan.planId, generation = runningBuilder.generation()))
        runningBuilder.emit(MixEvent.RendererPrepared(planId = runningPlan.planId, generation = runningBuilder.generation(), usedNoStemVariant = false))
        runningBuilder.emit(MixEvent.Tick(positionMs = 174_000L, isPlaying = true))
        runningBuilder.emit(MixEvent.Tick(positionMs = 180_000L, isPlaying = true))
        runningBuilder.emit(MixEvent.UserSeek(toMs = 50_000L))
        runningBuilder.emit(MixEvent.NextKnown(pair = pairB, positionMs = 60_000L, isPlaying = true))
        val runningPlan2 = testPlan(
            planId = "plan-s6b2",
            generation = runningBuilder.generation(),
            outTrackId = "out-B",
            inTrackId = "in-B",
            requiredStems = emptySet(),
            hasNoStemVariant = true,
        )
        runningBuilder.emit(MixEvent.AnalysisReady(trackId = "out-B", level = AnalysisLevel.FULL, generation = runningBuilder.generation()))
        runningBuilder.emit(MixEvent.AnalysisReady(trackId = "in-B", level = AnalysisLevel.FULL, generation = runningBuilder.generation()))
        runningBuilder.emit(MixEvent.PlanFrozen(plan = runningPlan2, generation = runningBuilder.generation()))
        runningBuilder.emit(MixEvent.RegionsReady(planId = runningPlan2.planId, generation = runningBuilder.generation()))
        runningBuilder.emit(MixEvent.RendererPrepared(planId = runningPlan2.planId, generation = runningBuilder.generation(), usedNoStemVariant = false))
        runningBuilder.emit(MixEvent.Tick(positionMs = 174_000L, isPlaying = true))
        runningBuilder.emit(MixEvent.Tick(positionMs = 180_000L, isPlaying = true))
        runningBuilder.emit(MixEvent.UserSkip)
        val runningSteps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), runningBuilder.events)
        assertEquals(MixStateKind.RUNNING, runningSteps[7].nextState.stateKind)
        assertEquals(MixStateKind.DONE, runningSteps[8].nextState.stateKind)
        assertEquals(listOf(DeckTarget.OUT), retireDecks(runningSteps[8]))
        assertTrue(hasEmit(runningSteps[8], TelemetryEventType.MIX_DONE), "seek in Running must emit MIX_DONE")
        assertEquals(MixStateKind.RUNNING, runningSteps[16].nextState.stateKind)
        assertEquals(MixStateKind.IDLE, runningSteps[17].nextState.stateKind)
        assertEquals(listOf(DeckTarget.OUT), retireDecks(runningSteps[17]))
        assertTrue(hasEmit(runningSteps[17], TelemetryEventType.MIX_DONE), "skip in Running must emit MIX_DONE")
    }

    @Test
    fun scenario7ThermalAndBackgroundDegradePreparingButNeverAbortRunning() {
        val thermalBuilder = ReplayLogBuilder()
        thermalBuilder.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        thermalBuilder.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = thermalBuilder.generation()))
        thermalBuilder.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = thermalBuilder.generation()))
        val thermalPlan = testPlan(planId = "plan-s7t", generation = thermalBuilder.generation(), requiredStems = twoStems(), hasNoStemVariant = true)
        thermalBuilder.emit(MixEvent.PlanFrozen(plan = thermalPlan, generation = thermalBuilder.generation()))
        thermalBuilder.emit(MixEvent.RegionsReady(planId = thermalPlan.planId, generation = thermalBuilder.generation()))
        thermalBuilder.emit(MixEvent.ThermalChanged(status = ThermalLevel.SEVERE))
        thermalBuilder.emit(MixEvent.RendererPrepared(planId = thermalPlan.planId, generation = thermalBuilder.generation(), usedNoStemVariant = true))
        val thermalSteps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), thermalBuilder.events)
        val degraded = thermalSteps[5].nextState as MixState.Preparing
        assertTrue(degraded.preferNoStemVariant, "SEVERE thermal in Preparing must set preferNoStemVariant")
        assertTrue(degraded.usingNoStemVariant, "SEVERE thermal in Preparing must switch to the no-stem variant")
        assertTrue(hasPrepareRenderer(thermalSteps[5], useNoStem = true), "thermal degrade must emit PrepareRenderer with no-stem variant")
        assertTrue(hasEmit(thermalSteps[5], TelemetryEventType.MIX_THERMAL_DEGRADED), "thermal degrade must emit MIX_THERMAL_DEGRADED")
        assertEquals(MixStateKind.READY, thermalSteps[6].nextState.stateKind)

        val backgroundBuilder = ReplayLogBuilder()
        backgroundBuilder.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        backgroundBuilder.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = backgroundBuilder.generation()))
        backgroundBuilder.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = backgroundBuilder.generation()))
        val backgroundPlan = testPlan(planId = "plan-s7b", generation = backgroundBuilder.generation(), requiredStems = twoStems(), hasNoStemVariant = true)
        backgroundBuilder.emit(MixEvent.PlanFrozen(plan = backgroundPlan, generation = backgroundBuilder.generation()))
        backgroundBuilder.emit(MixEvent.RegionsReady(planId = backgroundPlan.planId, generation = backgroundBuilder.generation()))
        backgroundBuilder.emit(MixEvent.Backgrounded(isBackgrounded = true))
        backgroundBuilder.emit(MixEvent.RendererPrepared(planId = backgroundPlan.planId, generation = backgroundBuilder.generation(), usedNoStemVariant = true))
        val backgroundSteps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), backgroundBuilder.events)
        val backgrounded = backgroundSteps[5].nextState as MixState.Preparing
        assertTrue(backgrounded.preferNoStemVariant, "backgrounding in Preparing must set preferNoStemVariant")
        assertTrue(backgrounded.usingNoStemVariant, "backgrounding in Preparing must switch to the no-stem variant")
        assertTrue(backgrounded.backgrounded, "background flag must be set")
        assertTrue(hasEmit(backgroundSteps[5], TelemetryEventType.MIX_THERMAL_DEGRADED), "background degrade must emit MIX_THERMAL_DEGRADED")
        assertEquals(MixStateKind.READY, backgroundSteps[6].nextState.stateKind)

        val runningBuilder = ReplayLogBuilder()
        runningBuilder.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        runningBuilder.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = runningBuilder.generation()))
        runningBuilder.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = runningBuilder.generation()))
        val runningPlan = testPlan(planId = "plan-s7r", generation = runningBuilder.generation(), requiredStems = emptySet(), hasNoStemVariant = true)
        runningBuilder.emit(MixEvent.PlanFrozen(plan = runningPlan, generation = runningBuilder.generation()))
        runningBuilder.emit(MixEvent.RegionsReady(planId = runningPlan.planId, generation = runningBuilder.generation()))
        runningBuilder.emit(MixEvent.RendererPrepared(planId = runningPlan.planId, generation = runningBuilder.generation(), usedNoStemVariant = false))
        runningBuilder.emit(MixEvent.Tick(positionMs = 174_000L, isPlaying = true))
        runningBuilder.emit(MixEvent.Tick(positionMs = 180_000L, isPlaying = true))
        runningBuilder.emit(MixEvent.ThermalChanged(status = ThermalLevel.SEVERE))
        runningBuilder.emit(MixEvent.Backgrounded(isBackgrounded = true))
        runningBuilder.emit(MixEvent.Tick(positionMs = 190_000L, isPlaying = true))
        runningBuilder.emit(MixEvent.Tick(positionMs = 196_000L, isPlaying = true))
        runningBuilder.emit(MixEvent.HandoverComplete)
        val runningSteps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), runningBuilder.events)
        assertEquals(MixStateKind.RUNNING, runningSteps[8].nextState.stateKind)
        assertEquals(MixStateKind.RUNNING, runningSteps[9].nextState.stateKind)
        assertTrue((runningSteps[8].nextState as MixState.Running).thermalLevel.requiresStemDegradation, "running thermal must be recorded")
        assertTrue((runningSteps[9].nextState as MixState.Running).backgrounded, "running background must be recorded")
        assertTrue(runningSteps[8].effects.isEmpty(), "thermal in Running must not emit abort effects")
        assertTrue(runningSteps[9].effects.isEmpty(), "backgrounding in Running must not emit abort effects")
        assertFalse(runningSteps.any { it.nextState is MixState.Suspended || it.nextState is MixState.Failed })
        assertEquals(MixStateKind.DONE, runningSteps.last().nextState.stateKind)
    }

    @Test
    fun scenario8RemixLeadAndLevelChangeTiming() {
        val accepted = ReplayLogBuilder()
        accepted.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        accepted.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = accepted.generation()))
        accepted.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = accepted.generation()))
        val acceptedPlan = testPlan(
            planId = "plan-s8a",
            generation = accepted.generation(),
            requiredStems = emptySet(),
            hasNoStemVariant = true,
            recipeId = "recipe-orig",
            recipeName = "Original Blend",
        )
        accepted.emit(MixEvent.PlanFrozen(plan = acceptedPlan, generation = accepted.generation()))
        accepted.emit(MixEvent.RegionsReady(planId = acceptedPlan.planId, generation = accepted.generation()))
        accepted.emit(MixEvent.RendererPrepared(planId = acceptedPlan.planId, generation = accepted.generation(), usedNoStemVariant = false))
        accepted.emit(MixEvent.RemixRequested)
        val acceptedSteps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), accepted.events)
        assertEquals(MixStateKind.READY, acceptedSteps[5].nextState.stateKind)
        assertEquals(MixStateKind.PREPARING, acceptedSteps[6].nextState.stateKind)
        assertTrue(hasEmit(acceptedSteps[6], TelemetryEventType.MIX_REMIXED), "accepted remix must emit MIX_REMIXED")
        val remixPrep = acceptedSteps[6].nextState as MixState.Preparing
        assertEquals(2L, remixPrep.generation)
        assertTrue(remixPrep.excludedRecipeIds.contains("recipe-orig"), "remix must exclude the prior recipeId")
        val remixStartPrepare = acceptedSteps[6].effects.filterIsInstance<MixEffect.StartPrepare>().firstOrNull()
        assertTrue(remixStartPrepare != null, "accepted remix must emit StartPrepare")
        assertTrue(remixStartPrepare!!.excludedRecipeIds.contains("recipe-orig"), "StartPrepare must exclude the prior recipeId")

        val ignored = ReplayLogBuilder()
        ignored.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        ignored.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = ignored.generation()))
        ignored.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = ignored.generation()))
        val ignoredPlan = testPlan(planId = "plan-s8i", generation = ignored.generation(), requiredStems = emptySet(), hasNoStemVariant = true)
        ignored.emit(MixEvent.PlanFrozen(plan = ignoredPlan, generation = ignored.generation()))
        ignored.emit(MixEvent.RegionsReady(planId = ignoredPlan.planId, generation = ignored.generation()))
        ignored.emit(MixEvent.RendererPrepared(planId = ignoredPlan.planId, generation = ignored.generation(), usedNoStemVariant = false))
        ignored.emit(MixEvent.Tick(positionMs = 170_000L, isPlaying = true))
        ignored.emit(MixEvent.RemixRequested)
        val ignoredSteps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), ignored.events)
        assertEquals(MixStateKind.READY, ignoredSteps[6].nextState.stateKind)
        assertEquals(MixStateKind.READY, ignoredSteps[7].nextState.stateKind)
        assertTrue(ignoredSteps[7].effects.isEmpty(), "remix inside the 20s lead must be ignored with no effects")
        assertEquals(ignoredSteps[6].nextState, ignoredSteps[7].nextState)

        val readyLevel = ReplayLogBuilder()
        readyLevel.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        readyLevel.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = readyLevel.generation()))
        readyLevel.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = readyLevel.generation()))
        val readyPlan = testPlan(planId = "plan-s8r", generation = readyLevel.generation(), requiredStems = emptySet(), hasNoStemVariant = true)
        readyLevel.emit(MixEvent.PlanFrozen(plan = readyPlan, generation = readyLevel.generation()))
        readyLevel.emit(MixEvent.RegionsReady(planId = readyPlan.planId, generation = readyLevel.generation()))
        readyLevel.emit(MixEvent.RendererPrepared(planId = readyPlan.planId, generation = readyLevel.generation(), usedNoStemVariant = false))
        readyLevel.emit(MixEvent.LevelChanged(level = DjLevel.CLUB))
        val readySteps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), readyLevel.events)
        assertEquals(MixStateKind.READY, readySteps[5].nextState.stateKind)
        assertEquals(MixStateKind.PREPARING, readySteps[6].nextState.stateKind)
        assertEquals(DjLevel.CLUB, (readySteps[6].nextState as MixState.Preparing).djLevel)
        assertEquals(listOf(DeckTarget.IN), retireDecks(readySteps[6]))
        assertTrue(hasStartPrepare(readySteps[6]), "level change in Ready must immediately re-prepare")

        val runningLevel = ReplayLogBuilder()
        runningLevel.emit(MixEvent.NextKnown(pair = pairA, positionMs = 10_000L, isPlaying = true))
        runningLevel.emit(MixEvent.AnalysisReady(trackId = "out-A", level = AnalysisLevel.FULL, generation = runningLevel.generation()))
        runningLevel.emit(MixEvent.AnalysisReady(trackId = "in-A", level = AnalysisLevel.FULL, generation = runningLevel.generation()))
        val runningPlan = testPlan(planId = "plan-s8run", generation = runningLevel.generation(), requiredStems = emptySet(), hasNoStemVariant = true)
        runningLevel.emit(MixEvent.PlanFrozen(plan = runningPlan, generation = runningLevel.generation()))
        runningLevel.emit(MixEvent.RegionsReady(planId = runningPlan.planId, generation = runningLevel.generation()))
        runningLevel.emit(MixEvent.RendererPrepared(planId = runningPlan.planId, generation = runningLevel.generation(), usedNoStemVariant = false))
        runningLevel.emit(MixEvent.Tick(positionMs = 174_000L, isPlaying = true))
        runningLevel.emit(MixEvent.Tick(positionMs = 180_000L, isPlaying = true))
        runningLevel.emit(MixEvent.LevelChanged(level = DjLevel.CLUB))
        runningLevel.emit(MixEvent.Tick(positionMs = 196_000L, isPlaying = true))
        runningLevel.emit(MixEvent.HandoverComplete)
        val runningSteps = MixMachine.replay(MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE), runningLevel.events)
        assertEquals(MixStateKind.RUNNING, runningSteps[8].nextState.stateKind)
        assertTrue(runningSteps[8].effects.isEmpty(), "level change in Running must defer with no effects")
        val deferred = runningSteps[8].nextState as MixState.Running
        assertEquals(DjLevel.SUBTLE, deferred.djLevel)
        assertEquals(DjLevel.CLUB, deferred.pendingNextLevel)
        assertEquals(MixStateKind.HANDING_OVER, runningSteps[9].nextState.stateKind)
        assertEquals(MixStateKind.DONE, runningSteps[10].nextState.stateKind)
        assertEquals(DjLevel.CLUB, (runningSteps[10].nextState as MixState.Done).djLevel)
    }
}

private class ReplayLogBuilder {
    var state: MixState = MixState.Idle(generation = 0L, djLevel = DjLevel.SUBTLE)
        private set
    val events = mutableListOf<MixEvent>()

    fun generation(): Long = state.generation

    fun emit(event: MixEvent): MixState {
        events += event
        val (next, _) = MixMachine.reduce(state, event)
        state = next
        return next
    }
}
