package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.DeckRenderSetupV1
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.EnvelopeKind
import com.music.bitchord.djmix.core.contracts.EnvelopeLane
import com.music.bitchord.djmix.core.contracts.FxCapability
import com.music.bitchord.djmix.core.contracts.LoudnessSettings
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.PhaserFxParams
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.RenderGeometryV1
import com.music.bitchord.djmix.core.contracts.ScriptFxParams
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.recipes.RecipeRegistry
import com.music.bitchord.djmix.core.recipes.RecipeInterpreter
import com.music.bitchord.djmix.core.recipes.ScriptExecutionGuard
import com.music.bitchord.playback.loopmix.LoopMixRenderer
import com.music.bitchord.playback.loopmix.LoopMixSession
import com.music.bitchord.playback.loopmix.LoopQualification
import com.music.bitchord.playback.loopmix.LoopRegion
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.BeatSide
import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.RegionalLoudness
import com.music.bitchord.playback.smart.StemKind
import com.music.bitchord.playback.smart.StemSet
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.choreo.ChoreoRendererBridge
import com.music.bitchord.playback.smart.choreo.ChoreoSessionGlue
import com.music.bitchord.playback.smart.choreo.RecipeId
import com.music.bitchord.playback.smart.choreo.DjLevel as RuntimeDjLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

import com.music.bitchord.djmix.core.contracts.RefusalReason.CHOREOGRAPHY_UNAVAILABLE

/**
 * R2 integration contracts for frozen geometry, gain, recipe identity and the
 * renderer readout seam. Compilation/execution and native PCM/stem parity await
 * the authorized serialized dvb validation; this lane never invokes Gradle.
 */
class CanonicalCutoverR2Test {
    @Test
    fun geometryRetainsFractionalOriginsAndKnotCoordinates() {
        val geometry = fixtureGeometry()
        assertEquals(10.1250001, geometry.out.regionStartSec, 0.0)
        assertEquals(48000.25, geometry.out.knotPositions.last(), 0.0)
        assertEquals(48000.375, geometry.inbound.knotPositions.last(), 0.0)
        assertEquals(5.1230078125, geometry.handoverTrackSec, 0.0)
        assertEquals(4.6230078125, geometry.incomingStartTrackSec, 0.0)
    }

    @Test
    fun frozenRecipeIsDetachedBeforeRuntimePreparation() {
        val recipe = requireNotNull(RecipeRegistry.byId("S1"))
        val frozen = requireNotNull(RecipeRegistry.byId("S1"))
        assertEquals(recipe, frozen)
        assertNotSame(recipe.requirements, frozen.requirements)
        assertNotSame(recipe.steps, frozen.steps)
    }

    @Test
    fun guardRejectsLegacyExecutionWithoutGeometry() {
        val script = fixtureScript().copy(renderGeometry = null)
        val result = ScriptExecutionGuard.validate(script)
        val refusal = result as MixOutcome.Refused
        assertEquals(RefusalReason.HANDOFF_INVALID_GEOMETRY, refusal.reason)
        assertEquals("render_geometry_missing", refusal.detail)
    }

    @Test
    fun guardAcceptsLosslessCanonicalPlanAndRejectsCompetingLegacyFields() {
        val script = fixtureScript()
        assertTrue(ScriptExecutionGuard.validate(script) is MixOutcome.Ok)
        val plan = fixturePlan()
        assertTrue(ScriptExecutionGuard.validateFrozen(script, plan) is MixOutcome.Ok)

        val competing = ScriptExecutionGuard.validate(script.copy(knots = listOf(
            com.music.bitchord.djmix.core.contracts.TempoKnot(0, 0),
        )))
        val refusal = competing as MixOutcome.Refused
        assertEquals(RefusalReason.HANDOFF_INVALID_GEOMETRY, refusal.reason)
        assertEquals("competing_legacy_geometry", refusal.detail)
    }

    @Test
    fun guardRejectsFrozenRecipeMismatchAndMissingCapability() {
        val recipe = requireNotNull(RecipeRegistry.byId("S1"))
        val mismatch = RecipeInterpreter.compileFrozen(
            recipe, fixturePlan().copy(recipeId = "S2"), fixtureGeometry(),
            fixtureScript().outRegion, fixtureScript().inRegion,
            useNoStemVariant = true,
        ) as MixOutcome.Refused
        assertEquals("frozen_recipe_id_mismatch", mismatch.detail)

        val plan = fixturePlan()
        val canonical = fixtureScript()
        val frozenMismatch = ChoreoSessionGlue.compileSameRecipeNoStem(
            recipe.copy(id = "S2"), plan, fixtureGeometry(), canonical,
        ) as MixOutcome.Refused
        assertEquals("frozen_recipe_id_mismatch", frozenMismatch.detail)

        val phaser = fixtureScript().copy(
            envelopes = fixtureScript().envelopes + EnvelopeLane(
                DeckTarget.OUT, EnvelopeKind.PHASER_WET, listOf(0.0, 1.0), listOf(0f, 1f),
            ),
            fxParams = ScriptFxParams(phaser = PhaserFxParams(enabled = true)),
            loudness = LoudnessSettings(enabled = false, truePeakLimiter = false),
        )
        val missing = ScriptExecutionGuard.validate(phaser, emptySet()) as MixOutcome.Refused
        assertTrue(missing.detail.startsWith("capability_missing"))
    }

    @Test
    fun sixCanonicalIdsSelectStraightWithoutLegacyPreviewOrRecipeSubstitution() {
        var fallbackCalls = 0
        val selector = DefaultTransitionPlanSelector { fallbackCalls++; null }
        for (id in listOf("S6", "C8", "C9", "F9", "F10", "F11")) {
            assertNull("$id must remain outside the legacy enum", RecipeId.fromCode(id))
            val recipe = requireNotNull(RecipeRegistry.byId(id))
            val plan = fixturePlan().copy(
                recipeId = id, recipeName = recipe.name, djLevel = DjLevel.FESTIVAL,
                overlapDurationMs = 8_000L, barDurationMs = 2_000L,
            )
            val selected = requireNotNull(selector.buildConcreteFromPlanV1(plan, selectorInput(), 180.0, 180.0))
            assertTrue("$id should materialize without a legacy script", selected.plan != null)
            assertNull(selected.previewScript)
            assertNull(requireNotNull(selected.v2Prep).forcedRecipe)
            assertEquals(id, requireNotNull(selected.frozenRecipe).id)
            val frozenPlan = requireNotNull(selected.transitionPlanV1)
            assertEquals(plan.planId, frozenPlan.planId)
            assertEquals(plan.frozenExitMs, frozenPlan.frozenExitMs)
            assertEquals(plan.frozenEntryMs, frozenPlan.frozenEntryMs)
        }
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun canonicalStringExclusionsReachRealSelectorPlanningForAllSixRemixIds() {
        val selector = DefaultTransitionPlanSelector()
        val allIds = RecipeRegistry.all().map { it.id }.toSet()
        for (id in listOf("S6", "C8", "C9", "F9", "F10", "F11")) {
            val input = selectorInput().copy(
                outTrack = PrepTrack("remix-$id-out", 180.0), inTrack = PrepTrack("remix-$id-in", 180.0),
                outAnalysis = usableAnalysis("remix-$id-out", 120.0),
                inAnalysis = usableAnalysis("remix-$id-in", if (id == "F9") 138.0 else 120.0),
                preferStraight = true, caps = FxCapability.ALL_MASK,
                excludedRecipeIds = allIds - id,
            )
            // This proves planner identity/exclusion, not that every recipe can
            // execute as straight geometry (C8 requires an outgoing loop).
            val before = requireNotNull(selector.select(input))
            assertEquals("$id must reach canonical selection", id, before.transitionPlanV1?.recipeId)
            val after = requireNotNull(selector.select(input.copy(excludedRecipeIds = allIds)))
            assertNull("excluded $id must not be materialized", after.plan)
            assertTrue("excluded $id must not be selected", after.transitionPlanV1?.recipeId != id)
            assertTrue(after.refusalReason != null)
        }
    }

    @Test
    fun canonicalExclusionsAreUnionedWithLegacyEnumExclusions() {
        val allIds = RecipeRegistry.all().map { it.id }.toSet()
        val input = selectorInput().copy(
            outTrack = PrepTrack("remix-union-out", 180.0), inTrack = PrepTrack("remix-union-in", 180.0),
            outAnalysis = usableAnalysis("remix-union-out", 120.0), inAnalysis = usableAnalysis("remix-union-in", 120.0),
            preferStraight = true, caps = FxCapability.ALL_MASK,
            excludedRecipeIds = allIds - "S1", excludedRecipes = setOf(RecipeId.S1),
        )
        val selected = requireNotNull(DefaultTransitionPlanSelector().select(input))
        assertNull(selected.plan)
        assertTrue(selected.transitionPlanV1?.recipeId != "S1")
    }

    @Test
    fun clubUnequalTempoStraightSelectionCompilesOnTheFrozenClockInBothDirections() {
        for (incomingBpm in listOf(126.0, 114.0)) {
            val input = selectorInput().copy(
                outAnalysis = TrackAnalysis(bpm = 120.0, duration = 180.0, beatConfidence = 0.95),
                inAnalysis = TrackAnalysis(bpm = incomingBpm, duration = 180.0, beatConfidence = 0.95),
                djLevel = RuntimeDjLevel.CLUB, caps = FxCapability.ALL_MASK,
            )
            val plan = fixturePlan().copy(
                djLevel = DjLevel.CLUB, frozenExitMs = 30_000L, frozenEntryMs = 2_000L,
                swapDownbeatMs = 34_000L, overlapDurationMs = 8_000L, barDurationMs = 2_000L,
            )
            val selector = DefaultTransitionPlanSelector()
            val selected = requireNotNull(selector.buildConcreteFromPlanV1(plan, input, 180.0, 180.0))
            val runtime = requireNotNull(selected.plan) { "normal Club beat matching should remain usable: $incomingBpm" }
            val frozen = requireNotNull(selected.transitionPlanV1)
            assertEquals(StraightRateMode.BEAT_MATCH, selected.straightRateMode)
            assertEquals(plan, frozen)
            assertEquals(8.0, LoopMix.Clock(runtime).releaseAt, 1e-9)
            assertTrue(selector.matchesFrozenTiming(frozen, runtime, input, StraightRateMode.BEAT_MATCH))
            val out = LoopRegion(FloatArray(SAMPLE_RATE * 20), FloatArray(SAMPLE_RATE * 20), 29.0)
            val inn = LoopRegion(FloatArray(SAMPLE_RATE * 20), FloatArray(SAMPLE_RATE * 20), 1.0)
            val result = ChoreoScriptBuilder.compileCanonical(
                frozen, selected.frozenRecipe, runtime, out, inn, null, null, SAMPLE_RATE, FxCapability.ALL_MASK,
                outAnalysis = input.outAnalysis, inAnalysis = input.inAnalysis,
                straightRateMode = selected.straightRateMode, level = RuntimeDjLevel.CLUB, useNoStemVariant = true,
            )
            assertTrue("$incomingBpm: $result", result is MixOutcome.Ok)
            val compiled = (result as MixOutcome.Ok).value
            assertTrue(ScriptExecutionGuard.validateFrozen(compiled.script, frozen) is MixOutcome.Ok)
            val geometry = compiled.geometry
            val outAtRelease = requireNotNull(ChoreoScriptBuilder.interpolateKnots(
                geometry.out.knotFrames.toDoubleArray(), geometry.out.knotPositions.toDoubleArray(), 8.0 * SAMPLE_RATE,
            )) / SAMPLE_RATE + geometry.out.regionStartSec
            assertEquals(38.0, outAtRelease, 0.5 / SAMPLE_RATE)
            val inAtRelease = requireNotNull(ChoreoScriptBuilder.interpolateKnots(
                geometry.inbound.knotFrames.toDoubleArray(), geometry.inbound.knotPositions.toDoubleArray(), 8.0 * SAMPLE_RATE,
            )) / SAMPLE_RATE + geometry.inbound.regionStartSec
            assertEquals(2.0 + 16.0 * 60.0 / incomingBpm, inAtRelease, 0.5 / SAMPLE_RATE)
            assertEquals(8.05, geometry.out.stopAtMixSec, 1e-9)
            assertEquals(geometry.handoverTrackSec, geometry.inbound.fadeOutTrackSec!!, 0.0)
            assertEquals(geometry.handoverMixSec * SAMPLE_RATE, geometry.inbound.knotFrames.last(), 1e-6)
            assertTrue(geometry.inbound.knotPositions[1] > geometry.inbound.knotPositions[0])
        }
    }

    @Test
    fun incompatibleFrozenStraightDurationRefusesWithoutShorteningOrChangingIdentity() {
        val plan = fixturePlan().copy(overlapDurationMs = 7_999L, barDurationMs = 2_000L)
        val selected = requireNotNull(DefaultTransitionPlanSelector().buildConcreteFromPlanV1(
            plan, selectorInput(), 180.0, 180.0,
        ))
        assertNull(selected.plan)
        assertEquals(plan, selected.transitionPlanV1)
        assertEquals(RefusalReason.HANDOFF_INVALID_GEOMETRY, selected.refusalReason)
    }

    @Test
    fun frozenLoopCannotBeReselectedAsStraight() {
        var fallbackCalls = 0
        val selector = DefaultTransitionPlanSelector { fallbackCalls++; null }
        val plan = fixturePlan().copy(geometry = TransitionGeometry.LOOP)
        val selected = requireNotNull(selector.buildConcreteFromPlanV1(
            plan, selectorInput().copy(preferStraight = true), 180.0, 180.0,
        ))
        assertNull(selected.plan)
        assertEquals(RefusalReason.HANDOFF_INVALID_GEOMETRY, selected.refusalReason)
        assertEquals(plan, selected.transitionPlanV1)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun frozenSourceProjectionRejectsOneMillisecondDriftOnEitherDeck() {
        val selector = DefaultTransitionPlanSelector()
        val input = selectorInput()
        val plan = fixturePlan()
        val runtime = runtimePlan(plan.frozenExitMs / 1000.0, plan.frozenEntryMs / 1000.0)
        assertTrue(selector.matchesFrozenSources(plan, runtime, input, StraightRateMode.RATE_ONE))
        assertTrue(!selector.matchesFrozenSources(
            plan.copy(frozenExitMs = plan.frozenExitMs + 1), runtime, input, StraightRateMode.RATE_ONE,
        ))
        assertTrue(!selector.matchesFrozenSources(
            plan.copy(frozenEntryMs = plan.frozenEntryMs + 1), runtime, input, StraightRateMode.RATE_ONE,
        ))
        val refused = ScriptExecutionGuard.validateFrozen(
            fixtureScript(), plan.copy(frozenEntryMs = plan.frozenEntryMs + 1),
        ) as MixOutcome.Refused
        assertEquals("frozen_source_anchor_mismatch", refused.detail)
    }

    @Test
    fun regionalGainKeepsFallbackBoundsAndPeakClampBeforeCanonicalProjection() {
        val plan = runtimePlan()
        val silent = LoopRegion(FloatArray(32), FloatArray(32), 0.0)
        val peak = LoopRegion(FloatArray(32), FloatArray(32).also { it[31] = -1f }, 0.0)
        fun gain(region: LoopRegion, fallback: Float) =
            LoopQualification.regionalIncomingLevel(plan, silent, region, SAMPLE_RATE, fallback)
        assertEquals(1.679f, gain(silent, 100f), 0f)
        assertEquals(0.501f, gain(silent, 0.01f), 0f)
        assertEquals(0.7f, gain(peak, 0.7f), 0f)
        val level = gain(peak, 1.6f)
        assertEquals(0.92f, level, 0f)
        val clock = LoopMix.Clock(plan)
        val knots = LoopMixSession.buildDeckKnots(
            plan, clock, SAMPLE_RATE, null, null, false, clock.releaseAt, 1.0, StraightRateMode.RATE_ONE,
        )
        val geometry = ChoreoScriptBuilder.canonicalGeometry(
            plan, clock, knots, silent, peak, SAMPLE_RATE, level, true, clock.releaseAt + 0.05,
        )
        val (_, inRef) = ChoreoScriptBuilder.canonicalRegionRefs("out", "in", silent, peak, SAMPLE_RATE, geometry)
        assertEquals(level, geometry.inbound.levelGain, 0f)
        assertEquals(level, inRef.levelGain, 0f)
    }

    @Test
    fun regionalMeasurementTakesPrecedenceOverFallbackGain() {
        val plan = runtimePlan()
        val wave = FloatArray(SAMPLE_RATE) { kotlin.math.sin(2.0 * kotlin.math.PI * 440.0 * it / SAMPLE_RATE).toFloat() }
        val out = LoopRegion(FloatArray(wave.size) { wave[it] * 0.13f }, FloatArray(wave.size) { wave[it] * 0.13f }, 0.0)
        val inn = LoopRegion(FloatArray(wave.size) { wave[it] * 0.1f }, FloatArray(wave.size) { wave[it] * 0.1f }, 0.0)
        val expected = requireNotNull(RegionalLoudness.matchGain(
            out.left, out.right, 0, SAMPLE_RATE * 8, inn.left, inn.right, 0, SAMPLE_RATE * 4, SAMPLE_RATE,
        ))
        val actual = LoopQualification.regionalIncomingLevel(plan, out, inn, SAMPLE_RATE, 0.55f)
        assertTrue(actual > 1f)
        assertEquals(expected, actual, 0f)
    }

    @Test
    fun canonicalStemRefsRejectSubmillisecondOriginDriftAndShortChannels() {
        val region = LoopRegion(FloatArray(32), FloatArray(32), 0.0)
        fun stems(start: Double = 0.0, rightFrames: Int = 32) = StemSet(
            SAMPLE_RATE, 32, start, mapOf(StemKind.VOCALS to FloatArray(32)),
            mapOf(StemKind.VOCALS to FloatArray(rightFrames)), 2, 1f,
        )
        fun refs(value: StemSet) = ChoreoScriptBuilder.canonicalStemRefs("out", DeckTarget.OUT, region, value, SAMPLE_RATE)
        assertEquals(1, refs(stems()).size)
        assertTrue(refs(stems(start = 1.0 / SAMPLE_RATE)).isEmpty())
        assertTrue(refs(stems(rightFrames = 31)).isEmpty())
    }

    @Test
    fun noStemCompilationRejectsChangedGeometryAndFrozenHoldMismatch() {
        val recipe = requireNotNull(RecipeRegistry.byId("S1"))
        val changedGeometry = fixtureGeometry().copy(highQuality = false)
        val changed = ChoreoSessionGlue.compileSameRecipeNoStem(
            recipe, fixturePlan(), changedGeometry, fixtureScript(),
        ) as MixOutcome.Refused
        assertEquals("frozen_plan_identity_mismatch", changed.detail)
        val holdMismatch = ScriptExecutionGuard.validateFrozen(
            fixtureScript(), fixturePlan().copy(recipeParams = mapOf("incomingHoldSec" to 0.1)),
        ) as MixOutcome.Refused
        assertEquals("frozen_initial_hold_mismatch", holdMismatch.detail)
    }

    @Test
    fun canonicalFxTelemetryReflectsOnlyTheInstalledScript() {
        assertTrue(ChoreoScriptBuilder.canonicalFxNames(fixtureScript()).isEmpty())
        val script = fixtureScript().copy(fxParams = ScriptFxParams(phaser = PhaserFxParams(enabled = true)))
        assertEquals(listOf("Phaser"), ChoreoScriptBuilder.canonicalFxNames(script))
    }

    @Test
    fun canonicalCompilationRequiresTheDetachedSameRecipeValue() {
        val region = LoopRegion(FloatArray(32), FloatArray(32), 0.0)
        fun compile(recipe: com.music.bitchord.djmix.core.contracts.RecipeV2?) =
            ChoreoScriptBuilder.compileCanonical(
                fixturePlan(), recipe, runtimePlan(), region, region, null, null, SAMPLE_RATE, FxCapability.ALL_MASK,
            )
        assertEquals("frozen_recipe_missing:S1", (compile(null) as MixOutcome.Refused).detail)
        val different = requireNotNull(RecipeRegistry.byId("S2"))
        assertEquals("frozen_recipe_id_mismatch", (compile(different) as MixOutcome.Refused).detail)
    }

    @Test
    fun straightEarlyFallbackUnusableOutAnalysisExcludedRecipeIdCannotReturn() {
        val input = selectorInput().copy(
            outAnalysis = unusableAnalysis(),
            inAnalysis = usableAnalysis("in", 120.0),
            preferStraight = true,
            stemsExpected = false,
        )
        val selector = DefaultTransitionPlanSelector()
        val initial = requireNotNull(selector.select(input))
        val initialPlan = requireNotNull(initial.plan) { "expected non-vacuous early fallback plan" }
        val initialPreview = requireNotNull(initial.previewScript) { "expected non-vacuous early fallback preview recipe" }
        val recipeCode = initial.transitionPlanV1?.recipeId ?: initialPreview.recipeId
        assertTrue("initial fallback should be executable", initialPlan.totalBars > 0)
        assertEquals(recipeCode, initialPreview.recipeId)

        val excluded = selector.select(input.copy(excludedRecipeIds = setOf(recipeCode)))
        assertAllowedFallback(excluded, setOf(recipeCode))
    }

    @Test
    fun straightEarlyFallbackUnusableInAnalysisAllRecipeIdsYieldNoExecutablePlan() {
        val input = selectorInput().copy(
            outAnalysis = usableAnalysis("out", 120.0),
            inAnalysis = unusableAnalysis(),
            preferStraight = true,
            stemsExpected = false,
        )
        val selector = DefaultTransitionPlanSelector()
        val initial = requireNotNull(selector.select(input))
        requireNotNull(initial.plan) { "expected non-vacuous early fallback plan for in-unusable case" }
        requireNotNull(initial.previewScript) { "expected non-vacuous early fallback preview recipe for in-unusable case" }

        val allCodes = com.music.bitchord.playback.smart.choreo.Recipes.ALL.map { it.id.code }.toSet()
        val excluded = selector.select(input.copy(excludedRecipeIds = allCodes))
        assertNull("all excluded recipe IDs must not produce executable plan", excluded?.plan)
        assertNull(excluded?.v2Prep)
        assertNull(excluded?.previewScript)
    }

    @Test
    fun straightEarlyFallbackMixedEnumAndStringExclusionsBothRespected() {
        val base = selectorInput().copy(
            outAnalysis = unusableAnalysis(),
            inAnalysis = usableAnalysis("in", 120.0),
            preferStraight = true,
            stemsExpected = false,
        )
        val selector = DefaultTransitionPlanSelector()
        val initial = requireNotNull(selector.select(base))
        requireNotNull(initial.plan)
        val initialCode = requireNotNull(initial.transitionPlanV1?.recipeId ?: initial.previewScript?.recipeId)

        val allCodes = com.music.bitchord.playback.smart.choreo.Recipes.ALL.map { it.id.code }.toSet()
        val initialEnum = requireNotNull(RecipeId.fromCode(initialCode))
        val remainingCodes = allCodes - initialCode
        assertTrue("mixed exclusion fixture needs multiple recipe IDs", remainingCodes.isNotEmpty())
        val mixed = selector.select(
            base.copy(excludedRecipeIds = remainingCodes, excludedRecipes = setOf(initialEnum)),
        )
        assertNull("the string/enum union must exclude every candidate", mixed?.plan)
        assertNull(mixed?.v2Prep)
        assertNull(mixed?.previewScript)
        assertAllowedFallback(selector.select(base.copy(excludedRecipes = setOf(initialEnum))), setOf(initialCode))
        assertAllowedFallback(selector.select(base.copy(excludedRecipeIds = setOf(initialCode))), setOf(initialCode))
    }

    @Test
    fun straightEarlyFallbackInjectedGuardTypedRefusalIsNotRealFallbackSubstitute() {
        val input = selectorInput().copy(
            outAnalysis = unusableAnalysis(),
            inAnalysis = usableAnalysis("in", 120.0),
            preferStraight = true,
            stemsExpected = false,
            excludedRecipeIds = setOf("S1"),
        )
        val injected = SelectedPlan(
            plan = runtimePlan(),
            previewScript = null,
            transitionPlanV1 = fixturePlan().copy(recipeId = "S1"),
            fallbackReason = "injected-test-fallback",
        )
        val selector = DefaultTransitionPlanSelector { injected }
        val selected = requireNotNull(selector.select(input))
        assertNull(selected.plan)
        assertEquals(CHOREOGRAPHY_UNAVAILABLE, selected.refusalReason)
        assertEquals("straight_recipe_excluded:S1", selected.fallbackReason)
    }

    private fun assertAllowedFallback(selected: SelectedPlan?, excluded: Set<String>) {
        if (selected?.plan == null) return
        val identity = requireNotNull(selected.transitionPlanV1?.recipeId ?: selected.previewScript?.recipeId) {
            "an executable fallback must identify its recipe"
        }
        assertTrue("excluded fallback recipe $identity must not execute", identity !in excluded)
    }

    private fun selectorInput(): PlanInput = PlanInput(
        outTrack = PrepTrack("out", 180.0), inTrack = PrepTrack("in", 180.0),
        outAnalysis = TrackAnalysis(bpm = 120.0, duration = 180.0),
        inAnalysis = TrackAnalysis(bpm = 120.0, duration = 180.0),
        outSongMap = null, inSongMap = null, sungOut = null, sungIn = null,
        djLevel = RuntimeDjLevel.FESTIVAL, sampleRate = SAMPLE_RATE,
        positionSec = 0.0, albumOrder = false, stemsExpected = false,
    )

    private fun unusableAnalysis(): TrackAnalysis = TrackAnalysis(status = "failed", bpm = 0.0, duration = 180.0)

    private fun usableAnalysis(id: String, bpm: Double): TrackAnalysis {
        val duration = 180.0
        val beat = 60.0 / bpm
        val beats = List((duration / beat).toInt() + 1) { it * beat }
        val bars = List((duration / (4.0 * beat)).toInt() + 1) { it * 4.0 * beat }
        return TrackAnalysis(
            trackId = id, status = TrackAnalysis.STATUS_READY, quality = TrackAnalysis.QUALITY_FULL,
            bpm = bpm, duration = duration, beatInterval = beat, beatConfidence = 0.95,
            key = "C major", keyConfidence = 0.95, downbeats = bars,
            vocalWindows = listOf(CurveWindow(0.0, 4.0, FloatArray((duration * 4).toInt()))),
            headBeats = BeatSide(beats.take(128), bars.take(32), bpm, 0.95),
            tailBeats = BeatSide(beats.takeLast(128), bars.takeLast(32), bpm, 0.95),
            introEndTime = 16.0, outroStartTime = 156.0, contentEndTime = 179.0,
        )
    }

    private fun runtimePlan(exit: Double = 0.0, entry: Double = 0.0): LoopMix.Plan = LoopMix.Plan(
        out = LoopMix.Pick(exit, exit, 0, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
        inn = LoopMix.Pick(entry, entry, 0, 0.5, 120.0, 0.0, LoopMix.Check.MASK),
        fold = 1.0, enterBars = 1, rampBars = 2, exitBars = 1, totalBars = 4,
        outLoops = false, inLoops = false, nativeIncoming = true, targetBpmOverride = 0.0,
    )

    @Test
    fun canonicalBridgeWritesEnvelopeAndLoudnessToRenderer() {
        val calls = mutableListOf<String>()
        val previousFx = com.music.bitchord.playback.loopmix.AutomixV2Render.fx
        val previousLoudness = com.music.bitchord.playback.loopmix.AutomixV2Render.loudness
        com.music.bitchord.playback.loopmix.AutomixV2Render.fx = true
        com.music.bitchord.playback.loopmix.AutomixV2Render.loudness = true
        val readout = object : LoopMixRenderer.Readout {
            override fun firstFrame() = 0L
            override fun heard(index: Int) = 0.0
            override fun exitGateViolations() = 0
            override fun setEnvelope(index: Int, kind: Int, times: DoubleArray, values: FloatArray, exponential: Boolean) {
                calls += "env:$index:$kind:${values.joinToString()}"
            }
            override fun setLoudnessMatch(enabled: Boolean, targetLufs: Float) {
                calls += "loud:$enabled:$targetLufs"
            }
            override fun setTruePeakLimiter(enabled: Boolean) { calls += "peak:$enabled" }
            override fun setPhaserEx(index: Int, rateBeats: Double, depth: Float, feedback: Float, stages: Int, stereoOffsetDeg: Float) {
                calls += "phaser:$index:$rateBeats:$depth:$feedback:$stages:$stereoOffsetDeg"
            }
        }
        val renderer = LoopMixRenderer(SAMPLE_RATE, readout)
        val canonical = fixtureScript().copy(
            envelopes = fixtureScript().envelopes + EnvelopeLane(
                DeckTarget.OUT, EnvelopeKind.PHASER_WET, listOf(0.0, 1.0), listOf(0f, 0.8f),
            ),
            fxParams = ScriptFxParams(phaser = PhaserFxParams(enabled = true, rateBeats = 3.0)),
        )
        try {
            ChoreoRendererBridge.apply(renderer, canonical, FxCapability.ALL_MASK)
            assertTrue(calls.any { it.startsWith("env:0:${EnvelopeKind.GAIN.nativeId}") })
            assertTrue(calls.contains("loud:true:-14.0"))
            assertTrue(calls.contains("peak:true"))
            assertTrue(calls.any { it.startsWith("env:0:${EnvelopeKind.PHASER_WET.nativeId}") })
            assertTrue(calls.any { it.startsWith("phaser:0:3.0:") })
            val authoritativeCalls = calls.toList()
            calls.clear()
            ChoreoScriptBuilder.installCanonicalScript(renderer, canonical)
            assertEquals(authoritativeCalls, calls)
        } finally {
            renderer.close()
            com.music.bitchord.playback.loopmix.AutomixV2Render.fx = previousFx
            com.music.bitchord.playback.loopmix.AutomixV2Render.loudness = previousLoudness
        }
    }

    @Test
    fun noStemCompilationRetainsFrozenRecipeAndPlanIdentity() {
        val recipe = requireNotNull(RecipeRegistry.byId("S1"))
        val plan = fixturePlan()
        val canonical = fixtureScript()
        val compiled = ChoreoSessionGlue.compileSameRecipeNoStem(
            recipe = recipe,
            plan = plan,
            geometry = fixtureGeometry(),
            canonical = canonical,
        ) as MixOutcome.Ok
        assertEquals(plan.recipeId, recipe.id)
        assertEquals(plan.planId, compiled.value.planId)
        assertEquals(plan.generation, compiled.value.generation)
        assertTrue(compiled.value.stems.isEmpty())
        assertTrue(compiled.value.envelopes.none { it.kind.isStem })
    }

    @Test
    fun rendererRangeChecksAndAtomicCloseArePureAndIdempotent() {
        assertTrue(LoopMixRenderer.isValidRenderRange(16, 0, 8))
        assertTrue(!LoopMixRenderer.isValidRenderRange(16, -1, 1))
        assertTrue(!LoopMixRenderer.isValidRenderRange(16, 8, 1))
        assertTrue(!LoopMixRenderer.isValidRenderRange(16, Int.MAX_VALUE, 1))

        var renderCalls = 0
        val captureChanges = mutableListOf<Boolean>()
        val renderer = LoopMixRenderer(SAMPLE_RATE, object : LoopMixRenderer.Readout {
            override fun firstFrame() = 0L
            override fun heard(index: Int) = 0.0
            override fun exitGateViolations() = 0
            override fun render(frame: Long, interleaved: FloatArray, offset: Int, frames: Int) { renderCalls++ }
            override fun setCaptureEnabled(enabled: Boolean) { captureChanges += enabled }
        })
        val output = FloatArray(16)
        renderer.setCaptureEnabled(true)
        renderer.prepare()
        renderer.setCaptureEnabled(false)
        assertEquals(listOf(true), captureChanges)
        renderer.render(0, output, -1, 1)
        renderer.render(0, output, Int.MAX_VALUE, 1)
        renderer.render(0, output, 8, 1)
        assertEquals(0, renderCalls)
        renderer.render(0, output, 0, 8)
        assertEquals(1, renderCalls)
        renderer.close()

        val handle = AtomicLong(77L)
        var destroys = 0
        val destroy: (Long) -> Unit = { destroys++ }
        LoopMixRenderer.closeNativeHandle(handle, destroy)
        LoopMixRenderer.closeNativeHandle(handle, destroy)
        assertEquals(0L, handle.get())
        assertEquals(1, destroys)
    }

    private fun fixtureScript(): TransitionScriptV1 = TransitionScriptV1(
        planId = "plan-7-S1-0",
        generation = 7L,
        sampleRateHz = SAMPLE_RATE,
        outRegion = region(DeckTarget.OUT, "out", stopAt = 1_000L, runOn = false),
        inRegion = region(DeckTarget.IN, "in", stopAt = 2_000L, fade = 5_123L, runOn = true),
        envelopes = listOf(
            EnvelopeLane(DeckTarget.OUT, EnvelopeKind.GAIN, listOf(-0.4, 0.0, 1.0), listOf(0f, 1f, 0f)),
            EnvelopeLane(DeckTarget.IN, EnvelopeKind.GAIN, listOf(0.0, 1.0), listOf(0f, 1f)),
        ),
        renderGeometry = fixtureGeometry(),
    )

    private fun fixturePlan(): TransitionPlanV1 = TransitionPlanV1(
        planId = "plan-7-S1-0",
        generation = 7L,
        outTrackId = "out",
        inTrackId = "in",
        djLevel = DjLevel.SUBTLE,
        geometry = TransitionGeometry.STRAIGHT,
        frozenExitMs = 10_125L,
        frozenEntryMs = 4_123L,
        swapDownbeatMs = 10_625L,
        overlapDurationMs = 1_000L,
        barDurationMs = 500L,
        recipeId = "S1",
        recipeName = "Smooth blend",
        recipeParams = mapOf("incomingHoldSec" to 0.0),
    )

    private fun fixtureGeometry(): RenderGeometryV1 {
        val out = DeckRenderSetupV1(
            regionStartSec = 10.1250001,
            frameCount = 480_000,
            loopStartFrame = 0,
            loopEndFrame = 0,
            releaseAtFrame = null,
            knotFrames = listOf(0.0, 48_000.0),
            knotPositions = listOf(0.0, 48_000.25),
            runOn = false,
            stopAtMixSec = 1.0,
            prerollSec = 0.4,
            levelGain = 1f,
            fadeOutTrackSec = null,
        )
        val incoming = DeckRenderSetupV1(
            regionStartSec = 4.1230002,
            frameCount = 480_000,
            loopStartFrame = 0,
            loopEndFrame = 0,
            releaseAtFrame = null,
            knotFrames = listOf(0.0, 48_000.0),
            knotPositions = listOf(0.0, 48_000.375),
            runOn = true,
            stopAtMixSec = 2.0,
            prerollSec = 0.4,
            levelGain = 1.0f,
            fadeOutTrackSec = 5.1230078125,
        )
        return RenderGeometryV1(
            out = out,
            inbound = incoming,
            handoverMixSec = 1.0,
            handoverTrackSec = 5.1230078125,
            incomingStartMixSec = 0.5,
            incomingStartTrackSec = 4.6230078125,
            highQuality = true,
        )
    }

    private fun region(
        deck: DeckTarget,
        id: String,
        stopAt: Long,
        fade: Long? = null,
        runOn: Boolean,
    ) = AudioRegionRef(
        deck = deck,
        trackId = id,
        startMs = if (deck == DeckTarget.OUT) 10_125L else 4_123L,
        endMs = if (deck == DeckTarget.OUT) 20_125L else 14_123L,
        sampleRateHz = SAMPLE_RATE,
        prerollMs = 400L,
        stopAtMixMs = stopAt,
        fadeOutTrackMs = fade,
        runOn = runOn,
    )

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
