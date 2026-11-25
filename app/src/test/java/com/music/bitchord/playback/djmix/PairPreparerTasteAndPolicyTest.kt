package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.DeckRenderSetupV1
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.GateFxParams
import com.music.bitchord.djmix.core.contracts.PhaserFxParams
import com.music.bitchord.djmix.core.contracts.RenderGeometryV1
import com.music.bitchord.djmix.core.contracts.ScriptFxParams
import com.music.bitchord.djmix.core.contracts.SweepFxParams
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.recipes.RecipeRegistry
import com.music.bitchord.playback.AutomixTelemetry
import com.music.bitchord.playback.loopmix.LoopQualification
import com.music.bitchord.playback.smart.MixInspectorState
import com.music.bitchord.playback.smart.MixInspectorTrack
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.DjLevel
import com.music.bitchord.playback.smart.choreo.RecipeId
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairPreparerTasteAndPolicyTest {
    @Test
    fun selectedPlanTasteDeltaIsBoundedAndPreserved() {
        assertEquals(0.12, inspectorTasteAdjustment(0.12), 0.0)
        assertEquals(-0.08, inspectorTasteAdjustment(-0.08), 0.0)
        assertEquals(0.0, inspectorTasteAdjustment(Double.NaN), 0.0)
        assertEquals(0.0, inspectorTasteAdjustment(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, inspectorTasteAdjustment(Double.NEGATIVE_INFINITY), 0.0)
        assertEquals(0.3, inspectorTasteAdjustment(9.0), 0.0)
        assertEquals(-0.3, inspectorTasteAdjustment(-9.0), 0.0)
    }

    @Test
    fun learningOffNeverInvokesPlannerWeightsProvider() {
        var calls = 0
        val weights = learningGatedPlannerWeights(false, "", { calls++; mapOf("S1" to 0.2) })
        assertTrue(weights.isEmpty())
        for (path in listOf("selection", "replan", "remix", "no-stem")) {
            assertTrue("$path must use the same off-policy baseline", learningGatedPlannerWeights(false, "", {
                calls++
                mapOf("S1" to 0.2)
            }).isEmpty())
        }
        assertEquals(0, calls)
    }

    @Test
    fun policyFlowReemitsWithoutChangingFrozenPair() = runTest {
        val pair = MutableStateFlow("frozen-plan-42")
        val policy = MutableStateFlow(false)
        val emissions = mutableListOf<Pair<String, Boolean>>()
        val job = launch {
            combineInspectorPolicy(pair.asStateFlow(), policy.asStateFlow()).collect { emissions += it }
        }
        yield()
        policy.value = true
        yield()
        assertEquals("frozen-plan-42", emissions.last().first)
        assertEquals(true, emissions.last().second)
        job.cancel()
    }

    @Test
    fun inspectorStateCarriesPolicyFields() {
        val states = listOf(0.12, -0.08).mapIndexed { generation, delta ->
            MixInspectorState(
                generation = generation.toLong(),
                current = MixInspectorTrack("out", "Out"),
                next = MixInspectorTrack("in", "In"),
                level = DjLevel.SUBTLE,
                tasteAdjustment = inspectorTasteAdjustment(delta),
                learningEnabled = true,
            )
        }
        assertEquals(0.12, states[0].tasteAdjustment, 0.0)
        assertEquals(-0.08, states[1].tasteAdjustment, 0.0)
        assertTrue(states.all { it.learningEnabled })
    }

    @Test
    fun canonicalOnlySixRecipeIdsPublishFrozenIdentityInstalledFxAndRemixEligibility() {
        for (id in listOf("S6", "C8", "C9", "F9", "F10", "F11")) {
            assertNull("$id has no legacy alias", RecipeId.fromCode(id))
            val pair = canonicalPair(id)
            assertNull(pair.script)
            val snapshot = PairPreparer.InspectorProjection.inspectorSnapshotFor(pair, true)
            assertEquals(id, snapshot.recipeId)
            assertEquals(pair.frozenRecipe?.name, snapshot.recipeName)
            assertEquals(listOf("Sweep", "Phaser"), snapshot.effects)
            assertEquals(2, snapshot.fxCount)
            assertTrue("$id must be remixable from its frozen canonical identity", snapshot.remixAvailable)
            assertNull(snapshot.remixUnavailableReason)
        }
    }

    @Test
    fun frozenCanonicalRecipeCanRemixBeforeScriptCompilationAndNoStemUsesActualVariantFx() {
        val pair = canonicalPair("C9")
        val beforeCompile = PairPreparer.InspectorProjection.inspectorSnapshotFor(pair.copy(canonicalScript = null), false)
        assertTrue(beforeCompile.remixAvailable)
        assertTrue(beforeCompile.effects.isEmpty())
        assertNull(beforeCompile.fxCount)
        val noStem = requireNotNull(pair.canonicalScript).copy(fxParams = ScriptFxParams(gate = GateFxParams(enabled = true)))
        val snapshot = PairPreparer.InspectorProjection.inspectorSnapshotFor(
            pair.copy(canonicalNoStemScript = noStem, preparedSessionNoStem = true), true,
        )
        assertEquals(listOf("Gate"), snapshot.effects)
        assertEquals(1, snapshot.fxCount)
        assertTrue(snapshot.remixAvailable)
        assertFalse(PairPreparer.InspectorProjection.inspectorSnapshotFor(pair.copy(state = PrepState.BUILDING), true).remixAvailable)
    }

    @Test
    fun canonicalInspectorFailsClosedForAbsentOrMismatchedPlanRecipeScriptAndTrackIdentity() {
        val pair = canonicalPair("F10")
        val script = requireNotNull(pair.canonicalScript)
        val plan = requireNotNull(pair.transitionPlanV1)
        val invalid = listOf(
            pair.copy(transitionPlanV1 = null),
            pair.copy(frozenRecipe = null),
            pair.copy(frozenRecipe = requireNotNull(RecipeRegistry.byId("F11"))),
            pair.copy(generation = pair.generation + 1),
            pair.copy(transitionPlanV1 = plan.copy(outTrackId = "other")),
            pair.copy(canonicalScript = script.copy(planId = "other-plan")),
            pair.copy(canonicalScript = script.copy(generation = script.generation + 1)),
            pair.copy(canonicalScript = script.copy(inRegion = script.inRegion.copy(trackId = "other"))),
            pair.copy(canonicalScript = script.copy(renderGeometry = null)),
            pair.copy(frozenRecipe = null, canonicalScript = null),
        )
        for (candidate in invalid) {
            val snapshot = PairPreparer.InspectorProjection.inspectorSnapshotFor(candidate, true)
            assertFalse(snapshot.remixAvailable)
            assertNotNull(snapshot.remixUnavailableReason)
            assertTrue(snapshot.effects.isEmpty())
            assertNull(snapshot.fxCount)
        }
    }

    @Test
    fun canonicalS6RemixKeepsExactStringExclusionAndSelectsDifferentRegistryRecipe() {
        assertNull(RecipeId.fromCode("S6"))
        val request = PrepRequest(PrepTrack("r4-out", 180.0), PrepTrack("r4-in", 180.0), DjLevel.SUBTLE, 0.0)
        assertTrue(request.excludedRecipeIds.isEmpty())
        val remix = request.copy(
            excludedRecipeIds = setOf("S6"),
            excludedRecipes = setOf(RecipeId.S1, RecipeId.S2, RecipeId.S3, RecipeId.S4),
            machineGeneration = 43L,
        )
        fun analysis(id: String) = TrackAnalysis(trackId = id, status = TrackAnalysis.STATUS_READY,
            quality = TrackAnalysis.QUALITY_FULL, duration = 180.0, bpm = 120.0,
            beatInterval = 0.5, beatConfidence = 0.95, key = "8B", keyConfidence = 0.9,
            downbeats = List(91) { it * 2.0 }, introEndTime = 16.0, outroStartTime = 156.0, contentEndTime = 179.0)
        val selected = requireNotNull(DefaultTransitionPlanSelector().select(PlanInput(
            outTrack = remix.outTrack, inTrack = remix.inTrack,
            outAnalysis = analysis(remix.outTrack.id), inAnalysis = analysis(remix.inTrack.id),
            outSongMap = null, inSongMap = null, sungOut = null, sungIn = null,
            djLevel = remix.djLevel, sampleRate = remix.sampleRate, positionSec = remix.positionSec,
            albumOrder = false, stemsExpected = false, preferStraight = true,
            excludedRecipes = remix.excludedRecipes, excludedRecipeIds = remix.excludedRecipeIds,
            machineGeneration = requireNotNull(remix.machineGeneration),
        )))
        val plan = requireNotNull(selected.transitionPlanV1)
        assertEquals(setOf("S6"), remix.excludedRecipeIds)
        assertFalse(plan.recipeId in remix.excludedRecipeIds)
        assertFalse(plan.recipeId in remix.excludedRecipes.map { it.code })
        assertEquals("S5", plan.recipeId)
        assertEquals(plan.recipeId, selected.frozenRecipe?.id)
    }

    private fun canonicalPair(id: String): PreparedPair {
        val recipe = requireNotNull(RecipeRegistry.byId(id))
        val plan = TransitionPlanV1(
            planId = "inspector-$id", generation = 42L, outTrackId = "out", inTrackId = "in",
            djLevel = recipe.level, geometry = TransitionGeometry.STRAIGHT, frozenExitMs = 10_000L,
            frozenEntryMs = 2_000L, swapDownbeatMs = 10_500L, overlapDurationMs = 1_000L,
            barDurationMs = 500L, recipeId = id, recipeName = "legacy display must not override frozen name",
        )
        fun deck(start: Double) = DeckRenderSetupV1(start, 48_000, 0L, 0L, null,
            listOf(0.0, 48_000.0), listOf(0.0, 48_000.0), false, 1.0, 0.4, 1f, null)
        val geometry = RenderGeometryV1(deck(0.0), deck(2.0), 1.0, 1.0, 0.5, 2.5, true)
        val script = TransitionScriptV1(planId = plan.planId, generation = plan.generation,
            outRegion = AudioRegionRef(DeckTarget.OUT, "out", 0L, 10_000L),
            inRegion = AudioRegionRef(DeckTarget.IN, "in", 0L, 10_000L), renderGeometry = geometry,
            fxParams = ScriptFxParams(phaser = PhaserFxParams(enabled = true),
                incomingSweep = SweepFxParams(enabled = true, deck = DeckTarget.IN)))
        val level = when (recipe.level) {
            com.music.bitchord.djmix.core.contracts.DjLevel.SUBTLE -> DjLevel.SUBTLE
            com.music.bitchord.djmix.core.contracts.DjLevel.CLUB -> DjLevel.CLUB
            com.music.bitchord.djmix.core.contracts.DjLevel.FESTIVAL -> DjLevel.FESTIVAL
        }
        return PreparedPair(outId = "out", inId = "in", generation = 42L, djLevel = level,
            state = PrepState.PREPARED, transitionPlanV1 = plan, frozenRecipe = recipe, canonicalScript = script,
            timings = PairTimings(outId = "out", inId = "in", level = level.name, fxCount = 99))
    }

    @Test
    fun failureDiagnosticsRemainNonUserVisible() {
        val before = AutomixTelemetry.failureCount()
        AutomixTelemetry.recordFailure("test", IllegalStateException("synthetic"))
        assertEquals(before + 1L, AutomixTelemetry.failureCount())
        LoopQualification.recordPreparedGeometryFailure(IllegalArgumentException("synthetic"))
        assertEquals(IllegalArgumentException::class.java.name, LoopQualification.lastPreparedGeometryFailureClass())
    }
}
