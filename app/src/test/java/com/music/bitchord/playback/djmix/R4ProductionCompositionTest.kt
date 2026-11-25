package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.DeckRenderSetupV1
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.FailureKind
import com.music.bitchord.djmix.core.contracts.RenderGeometryV1
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.machine.PairKey
import com.music.bitchord.djmix.core.learning.TasteSnapshot
import com.music.bitchord.djmix.core.recipes.RecipeRegistry
import com.music.bitchord.playback.djmix.diagnostics.DiagnosticsBundler
import com.music.bitchord.playback.djmix.diagnostics.FlagsSnapshot
import com.music.bitchord.playback.djmix.diagnostics.TelemetryV3Writer
import com.music.bitchord.playback.djmix.store.TransitionRecord
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.CrossfadeController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Exercises the production owner factory with real machine, telemetry, bundler and retention. */
@OptIn(ExperimentalCoroutinesApi::class)
class R4ProductionCompositionTest {
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test
    fun controllerFinishesAllHelperInitializationBeforePublicationWithoutReadingPlayers() = runTest {
        var playerReads = 0
        val controller = CrossfadeController(
            scope = backgroundScope,
            active = { playerReads++; error("constructor must not access active player") },
            standby = { playerReads++; error("constructor must not access standby player") },
            onHandoff = { _, _ -> error("constructor must not perform handover") },
        )
        try {
            for (name in listOf("coordinatorHost", "bailFallbackHandler", "fadeExecutionDriver", "tickEngine",
                "visualFeedPublisher", "prepCoordinator", "prepAnalysisCoordinator", "coordinatorBindings")) {
                val field = CrossfadeController::class.java.getDeclaredField("${name}\$delegate").apply { isAccessible = true }
                val delegate = field.get(controller) as Lazy<*>
                assertTrue("$name must be initialized before any worker/audio callback can run", delegate.isInitialized())
            }
            assertEquals(0, playerReads)
        } finally {
            controller.mixExecutor.close()
            controller.mixExecutor.awaitClosed()
        }
    }

    @Test
    fun ownerFactoryArchivesExactlyOnceAndFeedsHistoryRatingPlannerAndFlag() = runTest {
        val root = kotlin.io.path.createTempDirectory("r4-composition").toFile()
        val persistence = MemoryPersistence()
        val telemetry = TelemetryV3Writer(File(root, "telemetry"))
        val owner = DjmixServices(
            persistence = persistence, telemetryWriter = telemetry,
            diagnosticsBundler = DiagnosticsBundler(File(root, "reports")),
            ioDispatcher = StandardTestDispatcher(testScheduler), logger = { _, _ -> },
            transitionRecorder = persistence::record,
        )
        val epoch = owner.beginPlayerEpoch()
        val executor = owner.createExecutor(Adapter(), playerEpoch = epoch)
        try {
            owner.hydrateAsync().await()
            var lastPlan: TransitionPlanV1? = null
            repeat(3) {
                val plan = beginMix(executor)
                lastPlan = plan
                assertTrue(owner.retainPreparedPairAsync(prepared(plan), epoch))
                runMix(executor, plan)
                executor.dispatch(MixEvent.HandoverComplete)
                executor.dispatch(MixEvent.HandoverComplete)
            }
            executor.close()
            executor.awaitClosed()
            owner.flushAsync().await()
            assertEquals(3, persistence.records.size)
            assertTrue(persistence.records.values.all { it.outcome == "DONE" && it.timingsJson.contains("\"generation\":") })
            assertEquals(0.20, owner.plannerWeights().getValue("S1"), 0.0)
            assertEquals(0.20, persistence.preferences.getValue("S1"), 0.0)
            val retained = requireNotNull(owner.currentOrLastMix())
            val identity = requireNotNull(retained.ratingIdentity())
            assertEquals(epoch, retained.playerEpoch)
            assertNull(owner.retainedMixContexts.value.active)
            assertEquals(lastPlan?.planId, retained.planId)
            assertEquals(persistence.records.getValue(identity.recordId).planId, identity.planId)
            assertNotNull(retained.transitionScript?.renderGeometry)
            assertNotNull(retained.frozenRecipe)
            assertEquals("out", retained.analysisA?.trackId)
            assertEquals("in", retained.analysisB?.trackId)
            assertTrue(retained.assetSha256.values.all { it.matches(Regex("[a-f0-9]{64}")) })
            assertTrue(retained.recentEvents.isNotEmpty())
            assertTrue(telemetry.recentRecords().any { it.planId == identity.planId && it.eventType.name == "MIX_DONE" })
            assertTrue(owner.recordExplicitRating(identity, true).await())
            assertEquals(1, persistence.records.getValue(identity.recordId).rating)
            assertEquals(0.25, owner.plannerWeights().getValue("S1"), 0.0)
            assertFalse(owner.recordExplicitRating(identity.copy(generation = identity.generation - 1), false).await())
            owner.setUserLearningEnabled(false)
            assertTrue(owner.plannerWeights().isEmpty())
            assertTrue(owner.recordExplicitRating(identity, false).await())
            assertEquals(0.25, owner.tasteModel.recipeWeights().getValue("S1"), 0.0)
            val report = requireNotNull(owner.flagCurrentOrLastMixAsync().await())
            ZipFile(report).use { zip ->
                for (entry in listOf("plan.json", "recipe.json", "script.json", "render_geometry.json",
                    "analysis_out.json", "analysis_in.json", "flags_snapshot.json", "taste_snapshot.json")) {
                    assertNotNull(entry, zip.getEntry(entry))
                }
                assertTrue(zip.getInputStream(zip.getEntry("events_5min.jsonl")).reader().readText().isNotBlank())
                assertTrue(zip.getInputStream(zip.getEntry("telemetry_5min.jsonl")).reader().readText().isNotBlank())
                // No renderer capture/model provenance was supplied by this fixture.
                assertNull(zip.getEntry("mix_capture.wav"))
            }
            assertEquals(0L, executor.asyncWorkFailureCount())
        } finally {
            executor.close()
            executor.awaitClosed()
            owner.close()
            root.deleteRecursively()
        }
    }

    @Test
    fun archivalProjectionUsesActualNoStemScriptAndPreparedPcmHashes() {
        val full = prepared(plan(42))
        val fullScript = requireNotNull(full.canonicalScript)
        val noStem = fullScript.copy(fxParams = fullScript.fxParams.copy(isolate = true))
        val pair = full.copy(canonicalNoStemScript = noStem, preparedSessionNoStem = true)
        val retained = requireNotNull(retainedContextFor(pair, FlagsSnapshot.defaults(), TasteSnapshot()))
        assertEquals(noStem, retained.transitionScript)
        assertEquals("75e253f50979177eba47b2d0805ad36038789108924514d2a761a70de057d16f",
            retained.assetSha256.getValue("prepared_out_region_left_f32le"))
        assertEquals(4, retained.assetSha256.size)
        assertTrue(retained.modelSha256.isEmpty())
        assertNull(retainedContextFor(pair.copy(generation = 43), FlagsSnapshot.defaults(), TasteSnapshot()))
        assertEquals("9b0fb770a5483b463e2e2afaa3b4f3db0d372b37b27260f1652b2a8cb6b25192",
            preparedPcmSha256(floatArrayOf(0f, 1f, -1f)))
        assertEquals("fc8f166e21285cb506c4338c8da8ef1d8c3be0de90cece497c0017474bb63d89",
            preparedPcmSha256(FloatArray(5_000) { 1f }))
    }

    @Test
    fun boundedHistoryLaneDrainsEveryAdmittedTerminalAndThenAdmitsANewOpportunity() = runTest {
        val persistence = MemoryPersistence()
        val owner = DjmixServices(persistence, ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = { _, _ -> }, transitionRecorder = persistence::record)
        owner.hydrateAsync().await()
        val executor = owner.createExecutor(Adapter(), playerEpoch = owner.beginPlayerEpoch())
        try {
            repeat(64) {
                val plan = beginMix(executor)
                runMix(executor, plan)
                executor.dispatch(MixEvent.HandoverComplete)
            }
            val terminal = executor.currentState()
            executor.dispatch(MixEvent.NextKnown(PairKey("out", "in")))
            assertEquals(terminal, executor.currentState())
            assertTrue(executor.asyncWorkFailureCount() > 0L)
            runCurrent()
            assertEquals(64, persistence.records.size)
            assertTrue(executor.dispatch(MixEvent.NextKnown(PairKey("out", "in"))).first is MixState.Preparing)
        } finally {
            executor.close(); executor.awaitClosed(); owner.close()
        }
    }

    @Test
    fun oldEpochCannotMutateOrReplaceNewGenerationAndCloseRejectsLateCallbacks() = runTest {
        val persistence = MemoryPersistence()
        val owner = DjmixServices(persistence, ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = { _, _ -> }, transitionRecorder = persistence::record)
        owner.hydrateAsync().await()
        val oldEpoch = owner.beginPlayerEpoch()
        val oldAdapter = Adapter()
        val old = owner.createExecutor(oldAdapter, playerEpoch = oldEpoch)
        val oldPlan = beginMix(old)
        owner.retainPreparedPairAsync(prepared(oldPlan), oldEpoch)
        runMix(old, oldPlan, handover = false)
        owner.flushAsync().await()
        val newEpoch = owner.beginPlayerEpoch()
        val current = owner.createExecutor(Adapter(), playerEpoch = newEpoch)
        try {
            val newPlan = beginMix(current)
            assertTrue(newPlan.generation > oldPlan.generation)
            assertTrue(owner.retainPreparedPairAsync(prepared(newPlan), newEpoch))
            owner.flushAsync().await()
            val actionsBefore = oldAdapter.actions.toList()
            old.dispatch(MixEvent.RendererError(FailureKind.RENDERER_RUNTIME_ERROR, "late old renderer"))
            old.dispatch(MixEvent.UserSeek(0))
            old.recordUserTasteOverride(true, "S1")
            assertEquals(actionsBefore, oldAdapter.actions)
            assertTrue(old.currentState() is MixState.Running)
            assertFalse(owner.retainPreparedPairAsync(prepared(oldPlan), oldEpoch))
            assertFalse(owner.completeRetainedMix(oldPlan.planId, oldPlan.generation, playerEpoch = oldEpoch))
            assertEquals(newPlan.planId, owner.currentOrLastMix()?.planId)
            assertTrue(owner.plannerWeights().isEmpty())
            runMix(current, newPlan)
            current.dispatch(MixEvent.HandoverComplete)
            current.close()
            current.dispatch(MixEvent.NextKnown(PairKey("late", "callback")))
            current.awaitClosed()
            old.close()
            old.awaitClosed()
            owner.flushAsync().await()
            assertEquals(1, persistence.records.size)
            assertEquals(newPlan.planId, owner.currentOrLastMix()?.planId)
        } finally {
            old.close(); current.close()
            old.awaitClosed(); current.awaitClosed()
            owner.close()
        }
    }

    @Test
    fun terminalBeforePublicationSurvivesRebuildAndRichContextNeverResurrectsActive() = runTest {
        val persistence = MemoryPersistence()
        val owner = DjmixServices(persistence, ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = { _, _ -> }, transitionRecorder = persistence::record)
        owner.hydrateAsync().await()
        val epoch = owner.beginPlayerEpoch()
        val executor = owner.createExecutor(Adapter(), playerEpoch = epoch)
        try {
            val plan = beginMix(executor)
            runMix(executor, plan)
            executor.dispatch(MixEvent.HandoverComplete)
            // Both terminal/history and this richer snapshot are queued when the player is rebuilt.
            assertTrue(owner.retainPreparedPairAsync(prepared(plan), epoch))
            owner.beginPlayerEpoch()
            executor.close()
            executor.awaitClosed()
            owner.flushAsync().await()
            val retained = requireNotNull(owner.currentOrLastMix())
            assertNull(owner.retainedMixContexts.value.active)
            assertEquals(plan.planId, owner.retainedMixContexts.value.lastCompleted?.planId)
            assertNotNull(retained.transitionScript)
            assertNotNull(retained.analysisA)
            assertNotNull(retained.ratingIdentity())
            assertEquals(1, persistence.records.size)
            assertFalse(owner.retainPreparedPairAsync(prepared(plan), epoch))
        } finally {
            executor.close(); executor.awaitClosed(); owner.close()
        }
    }

    @Test
    fun productionFactoryPolicyFailureHasOneArchivedRowAndNoInventedMissingInputs() = runTest {
        val persistence = MemoryPersistence()
        val owner = DjmixServices(persistence, flagsSource = MutableStateFlow(FlagsSnapshot(disabledRecipeIds = setOf("S1"))),
            ioDispatcher = StandardTestDispatcher(testScheduler), logger = { _, _ -> }, transitionRecorder = persistence::record)
        owner.hydrateAsync().await()
        val adapter = Adapter()
        val executor = owner.createExecutor(adapter, playerEpoch = owner.beginPlayerEpoch())
        try {
            val preparing = executor.dispatch(MixEvent.NextKnown(PairKey("out", "in"))).first as MixState.Preparing
            val plan = plan(preparing.generation)
            executor.dispatch(MixEvent.PlanFrozen(plan, plan.generation))
            executor.dispatch(MixEvent.RendererPrepared(plan.planId, plan.generation))
            executor.dispatch(MixEvent.HandoverComplete)
            executor.close(); executor.awaitClosed(); owner.flushAsync().await()
            assertTrue(executor.currentState() is MixState.Failed)
            assertEquals(1, persistence.records.size)
            assertEquals("FAILED", persistence.records.values.single().outcome)
            assertFalse(adapter.actions.any { it == "arm" || it == "start" })
            val retained = requireNotNull(owner.currentOrLastMix())
            assertEquals(plan.planId, retained.planId)
            assertNotNull(retained.ratingIdentity())
            assertNull(retained.transitionScript)
            assertNull(retained.analysisA)
            assertTrue(retained.modelSha256.isEmpty())
            assertTrue(retained.assetSha256.isEmpty())
        } finally {
            executor.close(); executor.awaitClosed(); owner.close()
        }
    }

    @Test
    fun productionPauseResumeAndSeekPreserveSurvivorAndRecordOneTerminal() = runTest {
        val persistence = MemoryPersistence()
        val owner = DjmixServices(persistence, ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = { _, _ -> }, transitionRecorder = persistence::record)
        owner.hydrateAsync().await()
        val adapter = Adapter()
        val executor = owner.createExecutor(adapter, playerEpoch = owner.beginPlayerEpoch())
        try {
            val plan = beginMix(executor)
            runMix(executor, plan, handover = false)
            executor.dispatch(MixEvent.UserPause)
            assertTrue(executor.currentState().userPaused)
            executor.dispatch(MixEvent.UserResume)
            assertFalse(executor.currentState().userPaused)
            executor.dispatch(MixEvent.UserSeek(9_000L))
            executor.dispatch(MixEvent.HandoverComplete)
            executor.close(); executor.awaitClosed(); owner.flushAsync().await()
            assertTrue(executor.currentState() is MixState.Done)
            assertTrue(adapter.actions.containsAll(listOf("pause", "resume", "retire:OUT:true")))
            assertEquals(1, persistence.records.size)
            assertEquals(-0.05, owner.plannerWeights().getValue("S1"), 0.0)
            assertEquals(0, owner.tasteModel.consecutivePlaythroughs("S1"))
        } finally {
            executor.close(); executor.awaitClosed(); owner.close()
        }
    }

    @Test
    fun skipArchivesCancelledIdentityOnceWithoutChangingReducerSurvivorBehavior() = runTest {
        val persistence = MemoryPersistence()
        val owner = DjmixServices(persistence, ioDispatcher = StandardTestDispatcher(testScheduler),
            logger = { _, _ -> }, transitionRecorder = persistence::record)
        owner.hydrateAsync().await()
        val epoch = owner.beginPlayerEpoch()
        val adapter = Adapter()
        val executor = owner.createExecutor(adapter, playerEpoch = epoch)
        try {
            val plan = beginMix(executor)
            owner.retainPreparedPairAsync(prepared(plan), epoch)
            runMix(executor, plan, handover = false)
            executor.dispatch(MixEvent.UserSkip)
            executor.dispatch(MixEvent.UserSkip)
            executor.close(); executor.awaitClosed(); owner.flushAsync().await()
            assertTrue(executor.currentState() is MixState.Idle)
            assertTrue(adapter.actions.any { it.startsWith("retire:") && it.endsWith(":true") })
            assertEquals("CANCELLED", persistence.records.values.single().outcome)
            assertNull(owner.retainedMixContexts.value.active)
            assertNotNull(owner.retainedMixContexts.value.lastCompleted?.ratingIdentity())
            assertEquals(0, owner.tasteModel.consecutivePlaythroughs("S1"))
        } finally {
            executor.close(); executor.awaitClosed(); owner.close(); owner.awaitClosed()
        }
    }

    private fun beginMix(executor: MixExecutor): TransitionPlanV1 {
        val preparing = executor.dispatch(MixEvent.NextKnown(PairKey("out", "in"))).first as MixState.Preparing
        return plan(preparing.generation).also { executor.dispatch(MixEvent.PlanFrozen(it, it.generation)) }
    }

    private fun runMix(executor: MixExecutor, plan: TransitionPlanV1, handover: Boolean = true) {
        executor.dispatch(MixEvent.RegionsReady(plan.planId, plan.generation))
        executor.dispatch(MixEvent.RendererPrepared(plan.planId, plan.generation))
        executor.dispatch(MixEvent.Tick(8_000L))
        executor.dispatch(MixEvent.Tick(10_000L))
        assertTrue(executor.currentState() is MixState.Running)
        if (handover) executor.dispatch(MixEvent.Tick(11_000L))
    }

    private fun plan(generation: Long) = TransitionPlanV1(
        planId = "production-$generation", generation = generation, outTrackId = "out", inTrackId = "in",
        djLevel = DjLevel.SUBTLE, geometry = TransitionGeometry.STRAIGHT, frozenExitMs = 10_000L,
        frozenEntryMs = 2_000L, swapDownbeatMs = 10_500L, overlapDurationMs = 1_000L,
        barDurationMs = 500L, recipeId = "S1", recipeName = "Straight",
    )

    private fun prepared(plan: TransitionPlanV1): PreparedPair {
        fun deck(start: Double) = DeckRenderSetupV1(start, 48_000, 0L, 0L, null,
            listOf(0.0, 48_000.0), listOf(0.0, 48_000.0), false, 1.0, 0.4, 1f, null)
        val geometry = RenderGeometryV1(deck(0.0), deck(2.0), 1.0, 1.0, 0.5, 2.5, true)
        val script = TransitionScriptV1(planId = plan.planId, generation = plan.generation,
            outRegion = AudioRegionRef(DeckTarget.OUT, "out", 0L, 10_000L),
            inRegion = AudioRegionRef(DeckTarget.IN, "in", 0L, 10_000L), renderGeometry = geometry)
        return PreparedPair(outId = "out", inId = "in", djLevel = com.music.bitchord.playback.smart.choreo.DjLevel.SUBTLE,
            state = PrepState.PREPARED, generation = plan.generation, transitionPlanV1 = plan,
            frozenRecipe = requireNotNull(RecipeRegistry.byId("S1")), canonicalScript = script, renderGeometry = geometry,
            outAnalysis = TrackAnalysis(trackId = "out", duration = 120.0, bpm = 120.0),
            inAnalysis = TrackAnalysis(trackId = "in", duration = 120.0, bpm = 120.0),
            outRegion = LoopRegion(floatArrayOf(0.25f), floatArrayOf(-0.25f), 0.0),
            inRegion = LoopRegion(floatArrayOf(0.5f), floatArrayOf(-0.5f), 2.0))
    }

    private class MemoryPersistence : TastePersistence {
        var preferences: Map<String, Double> = emptyMap()
        val records = linkedMapOf<Long, TransitionRecord>()
        override fun readPreferences() = preferences.toMap()
        override fun replacePreferences(weights: Map<String, Double>): Boolean { preferences = weights.toMap(); return true }
        override fun resetPreferences(): Boolean { preferences = emptyMap(); return true }
        override fun readTransition(recordId: Long) = records[recordId]
        override fun updateRating(recordId: Long, rating: Int): Boolean {
            val row = records[recordId] ?: return false
            records[recordId] = row.copy(rating = rating)
            return true
        }
        fun record(value: TerminalTransitionRecord): Long = (records.size + 1L).also { id ->
            records[id] = TransitionRecord(id, value.outId, value.inId, value.planId, value.recipe,
                value.level, value.path, value.timingsJson, value.outcome, 0, value.createdAtMs)
        }
    }

    private class Adapter : MixDeckAdapter {
        val actions = mutableListOf<String>()
        override fun startPrepare(pair: PairKey, generation: Long, djLevel: DjLevel, positionMs: Long, minExitMs: Long?, preferNoStemVariant: Boolean, excludedRecipeIds: Set<String>) = Unit
        override fun cancelPrepare(generation: Long) = Unit
        override fun prepareRendererOffThread(planId: String, generation: Long, useNoStemVariant: Boolean) = Unit
        override fun armTransition(planId: String, generation: Long, atMs: Long, btLatencyOffsetMs: Long) { actions += "arm" }
        override fun startTransition(planId: String, generation: Long) { actions += "start" }
        override fun pauseBothInSync(generation: Long, byUser: Boolean) { actions += "pause" }
        override fun resumeBothInSync(generation: Long) { actions += "resume" }
        override fun retireDeck(deck: DeckTarget, generation: Long, preserveSurvivor: Boolean) { actions += "retire:$deck:$preserveSurvivor" }
        override fun completeHandover(planId: String, generation: Long) = Unit
    }
}
