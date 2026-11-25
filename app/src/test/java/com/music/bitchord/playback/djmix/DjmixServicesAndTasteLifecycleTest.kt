package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.learning.TasteModel
import com.music.bitchord.djmix.core.learning.TasteSignal
import com.music.bitchord.playback.djmix.diagnostics.FlagsSnapshot
import com.music.bitchord.playback.djmix.store.TransitionRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DjmixServicesAndTasteLifecycleTest {

    @Test
    fun hydrationCompletesBeforePlannerReceivesStoredWeights() = runBlocking {
        val persistence = MemoryPersistence(preferences = mapOf("S1" to 0.15, "S1:DANCE" to -0.05))
        persistence.readStarted = CountDownLatch(1)
        persistence.allowRead = CountDownLatch(1)
        val services = services(persistence)
        val hydration = services.hydrateAsync()
        assertTrue(persistence.readStarted!!.await(2, TimeUnit.SECONDS))
        assertTrue(services.plannerWeights("DANCE").isEmpty())
        assertFalse(services.recordTasteSignal(TasteSignal.ExplicitThumbsUp, "S1", "DANCE"))
        persistence.allowRead!!.countDown()
        assertEquals(HydrationState.Ready, withTimeout(2_000) { hydration.await() })
        assertEquals(0.10, services.plannerWeights("DANCE").getValue("S1"), 0.0)
        services.close()
    }

    @Test
    fun hydrationFailureKeepsDeterministicEmptyBaselineAndReportsCause() = runBlocking {
        val persistence = MemoryPersistence().apply { readFailure = IllegalStateException("db unavailable") }
        val services = services(persistence)
        val result = withTimeout(2_000) { services.hydrateAsync().await() }
        assertTrue(result is HydrationState.Failed)
        assertTrue(services.plannerWeights().isEmpty())
        assertTrue(services.persistenceFailure.value.orEmpty().contains("db unavailable"))
        services.close()
    }

    @Test
    fun staleSnapshotCannotWinAcrossResetEpochAndBothStoresClear() = runBlocking {
        val persistence = MemoryPersistence(preferences = mapOf("S1" to 0.2, "__legacy_migration_v1_done" to 1.0))
        val model = TasteModel()
        val services = services(persistence, model)
        services.hydrateAsync().await()
        val staleEpoch = services.currentTasteEpoch()
        assertTrue(services.recordTasteSignal(TasteSignal.ExplicitThumbsUp, "S2"))
        services.persistTasteStateAsync(mapOf("S1" to -0.3), staleEpoch)
        assertTrue(withTimeout(2_000) { services.resetLearnedPreferences().await() })
        withTimeout(2_000) { services.flushAsync().await() }
        assertTrue(model.toPreferenceMap().isEmpty())
        assertTrue(persistence.preferences.keys.all { it.startsWith("__") })
        services.persistTasteStateAsync(mapOf("S1" to 0.3), staleEpoch)
        services.flushAsync().await()
        assertTrue(persistence.preferences.keys.all { it.startsWith("__") })
        services.close()
    }

    @Test
    fun exactExplicitRatingUpdatesHistoryAndTasteThenResetClearsBoth() = runBlocking {
        val persistence = MemoryPersistence(records = mutableMapOf(41L to transitionRecord(41L, "plan-8", "S1", 8L)))
        val services = services(persistence)
        services.hydrateAsync().await()
        val identity = ExplicitRatingIdentity(41L, "plan-8", 8L, "S1", "DANCE")
        assertTrue(withTimeout(2_000) { services.recordExplicitRating(identity, thumbsUp = true).await() })
        assertEquals(1, persistence.records.getValue(41L).rating)
        assertEquals(0.05, services.tasteModel.adjustmentFor("S1", "DANCE"), 0.0)
        assertTrue(withTimeout(2_000) { services.resetLearnedPreferences().await() })
        assertTrue(services.tasteModel.toPreferenceMap().isEmpty())
        assertNull(persistence.preferences["S1"])
        services.close()
    }

    @Test
    fun restartedOwnerHydratesPersistedFullSnapshot() = runBlocking {
        val persistence = MemoryPersistence()
        val first = services(persistence)
        first.hydrateAsync().await()
        assertTrue(first.recordTasteSignal(TasteSignal.ExplicitThumbsDown, "S5", "ROCK"))
        withTimeout(2_000) { first.flushAsync().await() }
        first.close()

        val restarted = services(persistence)
        assertEquals(HydrationState.Ready, withTimeout(2_000) { restarted.hydrateAsync().await() })
        assertEquals(-0.05, restarted.tasteModel.adjustmentFor("S5", "ROCK"), 0.0)
        restarted.close()
    }

    @Test
    fun learningOffProducesEmptyPlannerWeightsEvenWithHydratedPreferences() = runBlocking {
        val services = services(
            MemoryPersistence(preferences = mapOf("S1" to 0.25)),
            userLearning = MutableStateFlow(false),
        )
        services.hydrateAsync().await()
        assertFalse(services.effectiveLearningEnabledFlow.value)
        assertTrue(services.plannerWeights().isEmpty())
        assertFalse(services.recordTasteSignal(TasteSignal.ExplicitThumbsUp, "S1"))
        services.close()
    }

    @Test
    fun retainedFrozenContextSurvivesPlayerEpochRebuildAndCompletesByIdentity() {
        val services = services(MemoryPersistence())
        val plan = plan()
        val context = RetainedMixContext(
            planId = plan.planId,
            generation = plan.generation,
            outTrackId = plan.outTrackId,
            inTrackId = plan.inTrackId,
            transitionPlanV1 = plan,
        )
        assertTrue(services.retainActiveMix(context))
        val oldEpoch = services.retainedMixContexts.value.playerEpoch
        val nextEpoch = services.beginPlayerEpoch()
        assertTrue(nextEpoch > oldEpoch)
        assertFalse(services.retainActiveMix(context, oldEpoch))
        assertEquals(context, services.currentOrLastMix())
        assertTrue(services.completeRetainedMix(plan.planId, plan.generation, recordId = 7L))
        assertNull(services.retainedMixContexts.value.active)
        assertEquals(plan.planId, services.retainedMixContexts.value.lastCompleted?.planId)
        services.close()
    }

    private fun services(
        persistence: MemoryPersistence,
        model: TasteModel = TasteModel(),
        userLearning: MutableStateFlow<Boolean> = MutableStateFlow(true),
    ) = DjmixServices(
        persistence = persistence,
        tasteModel = model,
        flagsSource = MutableStateFlow(FlagsSnapshot.defaults()),
        userLearningSource = userLearning,
        ioDispatcher = Dispatchers.IO,
        logger = { _, _ -> },
    )

    private class MemoryPersistence(
        @Volatile var preferences: Map<String, Double> = emptyMap(),
        val records: MutableMap<Long, TransitionRecord> = mutableMapOf(),
    ) : TastePersistence {
        @Volatile var readFailure: Throwable? = null
        @Volatile var readStarted: CountDownLatch? = null
        @Volatile var allowRead: CountDownLatch? = null
        override fun readPreferences(): Map<String, Double> {
            readStarted?.countDown()
            allowRead?.await(2, TimeUnit.SECONDS)
            readFailure?.let { throw it }
            return synchronized(this) { preferences.toMap() }
        }
        override fun replacePreferences(weights: Map<String, Double>): Boolean = synchronized(this) {
            preferences = preferences.filterKeys { it.startsWith("__") } + weights.filterKeys { !it.startsWith("__") }
            true
        }
        override fun resetPreferences(): Boolean = synchronized(this) {
            preferences = preferences.filterKeys { it.startsWith("__") }
            true
        }
        override fun readTransition(recordId: Long): TransitionRecord? = synchronized(this) { records[recordId] }
        override fun updateRating(recordId: Long, rating: Int): Boolean = synchronized(this) {
            val old = records[recordId] ?: return false
            records[recordId] = old.copy(rating = rating)
            true
        }
    }

    private fun transitionRecord(id: Long, planId: String, recipe: String, generation: Long) = TransitionRecord(
        id = id, outId = "out", inId = "in", planId = planId, recipe = recipe, level = "SUBTLE",
        path = "PREPARER_FROZEN", timingsJson = "{\"generation\":$generation}", outcome = "DONE", rating = 0, createdAt = 1L,
    )

    private fun plan() = TransitionPlanV1(
        planId = "plan-8", generation = 8L, outTrackId = "out", inTrackId = "in", djLevel = DjLevel.SUBTLE,
        geometry = TransitionGeometry.STRAIGHT, frozenExitMs = 10_000L, frozenEntryMs = 2_000L,
        swapDownbeatMs = 10_500L, overlapDurationMs = 1_000L, barDurationMs = 500L,
        recipeId = "S1", recipeName = "Straight",
    )
}
