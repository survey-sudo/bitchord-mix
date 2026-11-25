package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.learning.TasteModel
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.machine.PairKey
import com.music.bitchord.playback.djmix.diagnostics.FlagsSnapshot
import com.music.bitchord.playback.djmix.diagnostics.TelemetryV3Writer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class MixExecutorPolicyAndSafetyTest {

    @Test
    fun concurrentTasteOverridesAndDispatchKeepModelMapsSerializedAndCallbacksOutsideStateLock() {
        val model = TasteModel()
        val callbacks = AtomicLong()
        val callbackLockFree = AtomicBoolean(true)
        lateinit var executor: MixExecutor
        executor = MixExecutor(
            deckAdapter = Adapter(),
            tasteModel = model,
            flagsProvider = { FlagsSnapshot.defaults() },
            learningPolicyProvider = { true },
            tasteCallbackDispatcher = Dispatchers.IO,
            onTasteStateChanged = {
                val completed = CountDownLatch(1)
                Thread { executor.currentState(); completed.countDown() }.start()
                if (!completed.await(2, TimeUnit.SECONDS)) callbackLockFree.set(false)
                callbacks.incrementAndGet()
            },
        )
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(6) { worker ->
            pool.execute {
                start.await()
                repeat(20) { index ->
                    if ((worker + index) % 2 == 0) executor.recordUserTasteOverride(true, "S1")
                    else executor.recordUserTasteOverride(false, "S1")
                    if (index % 10 == 0) executor.dispatch(MixEvent.NextKnown(PairKey("out", "in"), index.toLong()))
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        val callbackDrain = CountDownLatch(1)
        Thread {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (callbacks.get() < 60 && System.nanoTime() < deadline) Thread.yield()
            callbackDrain.countDown()
        }.start()
        assertTrue(callbackDrain.await(6, TimeUnit.SECONDS))
        assertTrue(callbackLockFree.get())
        assertTrue(model.recipeWeights().getValue("S1") in -0.3..0.3)
        assertTrue(model.genreWeights().values.all { it in -0.3..0.3 })
        executor.close()
    }

    @Test
    fun earlySkipCountsAtInclusiveFifteenSecondPostMixBoundaryOnly() {
        fun weight(delayAfterDoneMs: Long): Double {
            val clock = AtomicLong(1_000L)
            val model = TasteModel()
            val executor = MixExecutor(Adapter(), tasteModel = model, clockMs = clock::get)
            completeMix(executor)
            clock.addAndGet(delayAfterDoneMs)
            executor.dispatch(MixEvent.UserSkip)
            val result = model.recipeWeights()["S1"] ?: 0.0
            executor.close()
            return result
        }
        assertEquals(-0.05, weight(15_000L), 0.0)
        assertEquals(0.0, weight(15_001L), 0.0)
        assertEquals(0.0, weight(-1L), 0.0)
    }

    @Test
    fun backwardsSeekDuringRunningBlendAddsOneNegativeSignal() {
        val model = TasteModel()
        val executor = MixExecutor(Adapter(), tasteModel = model)
        startRunning(executor)
        assertTrue(executor.currentState() is MixState.Running)
        executor.dispatch(MixEvent.UserSeek(9_999L))
        assertEquals(-0.05, model.adjustmentFor("S1"), 0.0)
        assertEquals(0, model.consecutivePlaythroughs("S1"))
        assertTrue(executor.currentState() is MixState.Done)
        executor.close()
    }

    @Test
    fun skipWithinFifteenSecondsOfFrozenMixEndCountsOnce() {
        val model = TasteModel()
        val executor = MixExecutor(Adapter(), tasteModel = model)
        startRunning(executor)
        executor.dispatch(MixEvent.UserSkip)
        executor.dispatch(MixEvent.UserSkip)
        assertEquals(-0.05, model.adjustmentFor("S1"), 0.0)
        executor.close()
    }

    @Test
    fun threeUniqueDoneGenerationsProduceOneBonusAndRepeatedDoneIsDeduplicated() {
        val model = TasteModel()
        val executor = MixExecutor(Adapter(), tasteModel = model)
        repeat(3) { completeMix(executor) }
        assertEquals(0.20, model.recipeWeights().getValue("S1"), 0.0)
        assertEquals(0, model.consecutivePlaythroughs("S1"))
        val state = executor.currentState()
        assertTrue(state is MixState.Done)
        executor.dispatch(MixEvent.HandoverComplete)
        assertEquals(0.20, model.recipeWeights().getValue("S1"), 0.0)
        executor.close()
    }

    @Test
    fun safeModeAndExplicitlyDisabledRecipeRefuseAtPlanFreeze() {
        val safe = MixExecutor(Adapter(), flagsProvider = { FlagsSnapshot(safeMode = true) })
        val preparing = safe.dispatch(MixEvent.NextKnown(PairKey("out", "in"))).first as MixState.Preparing
        safe.dispatch(MixEvent.PlanFrozen(plan(preparing.generation, recipe = "S2"), preparing.generation))
        assertTrue(safe.currentState() is MixState.Failed)
        safe.close()

        val disabled = MixExecutor(Adapter(), flagsProvider = { FlagsSnapshot(disabledRecipeIds = setOf("S1")) })
        val second = disabled.dispatch(MixEvent.NextKnown(PairKey("out", "in"))).first as MixState.Preparing
        disabled.dispatch(MixEvent.PlanFrozen(plan(second.generation), second.generation))
        assertTrue(disabled.currentState() is MixState.Failed)
        disabled.close()

        val geometry = MixExecutor(Adapter(), flagsProvider = { FlagsSnapshot(safeMode = true) })
        val third = geometry.dispatch(MixEvent.NextKnown(PairKey("out", "in"))).first as MixState.Preparing
        geometry.dispatch(MixEvent.PlanFrozen(plan(third.generation, geometry = TransitionGeometry.LOOP), third.generation))
        assertTrue(geometry.currentState() is MixState.Failed)
        geometry.close()
    }

    @Test
    fun canonicalRecipeCannotBeReacceptedWhenRemixExcludedItsExactStringId() {
        val executor = MixExecutor(Adapter())
        try {
            val first = executor.dispatch(MixEvent.NextKnown(PairKey("out", "in"))).first as MixState.Preparing
            val frozen = plan(first.generation, recipe = "S6")
            executor.dispatch(MixEvent.PlanFrozen(frozen, first.generation))
            executor.dispatch(MixEvent.RegionsReady(frozen.planId, first.generation))
            executor.dispatch(MixEvent.RendererPrepared(frozen.planId, first.generation))
            assertTrue(executor.currentState() is MixState.Ready)
            val remix = executor.dispatch(MixEvent.RemixRequested).first as MixState.Preparing
            assertTrue("canonical ID must remain an exact string", "S6" in remix.excludedRecipeIds)
            executor.dispatch(MixEvent.PlanFrozen(plan(remix.generation, recipe = "S6"), remix.generation))
            assertTrue("a lossy legacy exclusion adapter must fail closed", executor.currentState() is MixState.Failed)
        } finally { executor.close() }
    }

    @Test
    fun armStartAndHandoverExceptionsBecomeFailedStateAndStructuredTelemetry() {
        for (failure in listOf(AdapterFailure.ARM, AdapterFailure.START, AdapterFailure.HANDOVER)) {
            val root = kotlin.io.path.createTempDirectory("mix-effect-failure").toFile()
            val adapter = Adapter(failure)
            val telemetry = TelemetryV3Writer(File(root, "telemetry"))
            val executor = MixExecutor(adapter, telemetryWriter = telemetry)
            executor.dispatch(MixEvent.NextKnown(PairKey("out", "in")))
            val preparing = executor.currentState() as MixState.Preparing
            executor.dispatch(MixEvent.PlanFrozen(plan(preparing.generation), preparing.generation))
            executor.dispatch(MixEvent.RegionsReady("plan-${preparing.generation}", preparing.generation))
            executor.dispatch(MixEvent.RendererPrepared("plan-${preparing.generation}", preparing.generation))
            executor.dispatch(MixEvent.Tick(8_000L))
            if (failure == AdapterFailure.HANDOVER) {
                executor.dispatch(MixEvent.Tick(10_000L))
                executor.dispatch(MixEvent.Tick(11_000L))
            } else if (failure == AdapterFailure.START) {
                executor.dispatch(MixEvent.Tick(10_000L))
            }
            runBlocking { withTimeout(2_000L) { executor.flushTelemetryAsync().await() } }
            assertTrue("$failure must roll back", executor.currentState() is MixState.Failed)
            assertTrue(
                "$failure telemetry detail",
                telemetry.recentRecords().any { it.eventType.name == "MIX_FAILED" && it.detail.orEmpty().contains("${failure.name.lowercase()}_failed") },
            )
            executor.close()
        }
    }

    private fun completeMix(executor: MixExecutor) {
        startRunning(executor)
        executor.dispatch(MixEvent.Tick(11_000L))
        assertTrue(executor.currentState() is MixState.HandingOver)
        executor.dispatch(MixEvent.HandoverComplete)
        assertTrue(executor.currentState() is MixState.Done)
        executor.dispatch(MixEvent.HandoverComplete)
    }

    private fun startRunning(executor: MixExecutor) {
        val state = executor.currentState()
        val preparing = if (state is MixState.Preparing) state else executor.dispatch(MixEvent.NextKnown(PairKey("out", "in"))).first as MixState.Preparing
        if (preparing.plan == null) executor.dispatch(MixEvent.PlanFrozen(plan(preparing.generation), preparing.generation))
        executor.dispatch(MixEvent.RegionsReady("plan-${preparing.generation}", preparing.generation))
        executor.dispatch(MixEvent.RendererPrepared("plan-${preparing.generation}", preparing.generation))
        if (executor.currentState() is MixState.Ready) executor.dispatch(MixEvent.Tick(8_000L))
        if (executor.currentState() is MixState.Armed) executor.dispatch(MixEvent.Tick(10_000L))
    }

    private fun plan(
        generation: Long,
        recipe: String = "S1",
        geometry: TransitionGeometry = TransitionGeometry.STRAIGHT,
    ) = TransitionPlanV1(
        planId = "plan-$generation", generation = generation, outTrackId = "out", inTrackId = "in",
        djLevel = DjLevel.SUBTLE, geometry = geometry, frozenExitMs = 10_000L, frozenEntryMs = 2_000L,
        swapDownbeatMs = 10_500L, overlapDurationMs = 1_000L, barDurationMs = 500L,
        recipeId = recipe, recipeName = recipe,
    )

    private enum class AdapterFailure { NONE, ARM, START, HANDOVER }

    private class Adapter(private val failure: AdapterFailure = AdapterFailure.NONE) : MixDeckAdapter {
        override fun startPrepare(pair: PairKey, generation: Long, djLevel: DjLevel, positionMs: Long, minExitMs: Long?, preferNoStemVariant: Boolean, excludedRecipeIds: Set<String>) = Unit
        override fun cancelPrepare(generation: Long) = Unit
        override fun prepareRendererOffThread(planId: String, generation: Long, useNoStemVariant: Boolean) = Unit
        override fun armTransition(planId: String, generation: Long, atMs: Long, btLatencyOffsetMs: Long) {
            if (failure == AdapterFailure.ARM) error("arm device rejected")
        }
        override fun startTransition(planId: String, generation: Long) {
            if (failure == AdapterFailure.START) error("start device rejected")
        }
        override fun pauseBothInSync(generation: Long, byUser: Boolean) = Unit
        override fun resumeBothInSync(generation: Long) = Unit
        override fun retireDeck(deck: DeckTarget, generation: Long, preserveSurvivor: Boolean) = Unit
        override fun completeHandover(planId: String, generation: Long) {
            if (failure == AdapterFailure.HANDOVER) error("handover device rejected")
        }
    }
}
