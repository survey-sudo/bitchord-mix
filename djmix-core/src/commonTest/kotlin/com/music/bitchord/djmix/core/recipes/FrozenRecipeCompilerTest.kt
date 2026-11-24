package com.music.bitchord.djmix.core.recipes

import com.music.bitchord.djmix.core.contracts.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Frozen compiler behavior, exact recipe identity, S3 reference curves and snapshot ownership. */
class FrozenRecipeCompilerTest {
    @AfterTest
    fun resetOverrides() = RecipeRegistry.resetOverrides()

    @Test
    fun everyExactCatalogIdCompilesItsOwnRecipeAndBothVariantsOnACompleteFixture() {
        val ids = (1..6).map { "S$it" } + (1..9).map { "C$it" } + (1..11).map { "F$it" }
        assertEquals(ids, RecipeRegistry.all().map { it.id })
        for (id in ids) {
            val recipe = requireNotNull(RecipeRegistry.builtIn(id))
            val loop = recipe.requirements.requiresOutLoop || recipe.steps.any {
                it is RecipeStepV2.ConfigureFx && it.fx in setOf(RecipeFxType.LOOP, RecipeFxType.ROLL, RecipeFxType.LOOP_ROLL)
            }
            val f = CanonicalFixture(release = recipe.lengthOptionsBars.first() * 2.0, outLoop = loop)
            val plan = f.plan(recipe)
            val stems = fullStems(f)
            for (noStem in listOf(false, true)) {
                val result = RecipeInterpreter.compileFrozen(recipe, plan, f.geometry,
                    f.region(DeckTarget.OUT), f.region(DeckTarget.IN), stems, noStem)
                val script = assertIs<MixOutcome.Ok<TransitionScriptV1>>(result, "$id/noStem=$noStem: $result").value
                assertEquals(id, plan.recipeId)
                assertEquals("frozen-$id", script.planId)
                assertEquals(plan.generation, script.generation)
                assertEquals(f.geometry, script.renderGeometry)
                assertTrue(script.knots.isEmpty())
                assertNull(script.loopBounds)
                assertIs<MixOutcome.Ok<TransitionScriptV1>>(ScriptExecutionGuard.validateFrozen(script, plan))
                if (noStem) {
                    assertTrue(script.stems.isEmpty())
                    assertFalse(script.envelopes.any { it.kind.isStem })
                }
            }
        }
    }

    @Test
    fun sameRecipeNoStemRetainsFrozenIdentitySelectedLevelAndGeometry() {
        val recipe = requireNotNull(RecipeRegistry.builtIn("S2"))
        val f = CanonicalFixture()
        val plan = f.plan(recipe, DjLevel.FESTIVAL)
        val normal = assertIs<MixOutcome.Ok<TransitionScriptV1>>(RecipeInterpreter.compileFrozen(recipe, plan, f.geometry,
            f.region(DeckTarget.OUT), f.region(DeckTarget.IN), fullStems(f))).value
        val noStem = assertIs<MixOutcome.Ok<TransitionScriptV1>>(RecipeInterpreter.compileFrozen(recipe, plan, f.geometry,
            f.region(DeckTarget.OUT), f.region(DeckTarget.IN), useNoStemVariant = true)).value
        assertEquals(normal.planId, noStem.planId)
        assertEquals(normal.generation, noStem.generation)
        assertEquals(normal.renderGeometry, noStem.renderGeometry)
        assertEquals("S2", plan.recipeId)
        assertEquals(DjLevel.FESTIVAL, plan.djLevel)
        assertTrue(normal.envelopes.any { it.kind == EnvelopeKind.STEM_BASS })
        assertTrue(noStem.envelopes.any { it.kind == EnvelopeKind.LOW_DB })

        val s4 = requireNotNull(RecipeRegistry.builtIn("S4"))
        val selected = f.plan(s4, DjLevel.CLUB)
        val elevated = assertIs<MixOutcome.Ok<TransitionScriptV1>>(RecipeInterpreter.compileFrozen(s4, selected, f.geometry,
            f.region(DeckTarget.OUT), f.region(DeckTarget.IN), useNoStemVariant = true)).value
        assertEquals((0.55 * DjLevel.CLUB.fxDepth).toFloat(), elevated.fxParams.echo?.wet)
        assertNotEquals((0.55 * DjLevel.SUBTLE.fxDepth).toFloat(), elevated.fxParams.echo?.wet)
    }

    @Test
    fun compilerRefusesWrongRecipeDisabledIneligibleAssetsAndMissingFxWithoutFallback() {
        val s3 = requireNotNull(RecipeRegistry.builtIn("S3"))
        val f = CanonicalFixture()
        val plan = f.plan(s3)
        fun attempt(recipe: RecipeV2 = s3, p: TransitionPlanV1 = plan,
            caps: Set<FxCapability> = FxCapability.entries.toSet(), out: AudioRegionRef = f.region(DeckTarget.OUT)) =
            RecipeInterpreter.compileFrozen(recipe, p, f.geometry, out, f.region(DeckTarget.IN),
                useNoStemVariant = true, availableCapabilities = caps)
        assertEquals("frozen_recipe_id_mismatch", assertIs<MixOutcome.Refused>(attempt(p = plan.copy(recipeId = "s3"))).detail)
        assertIs<MixOutcome.Refused>(attempt(recipe = s3.copy(enabled = false)))
        val c6 = requireNotNull(RecipeRegistry.builtIn("C6"))
        assertEquals("recipe_level_ineligible", assertIs<MixOutcome.Refused>(attempt(c6, f.plan(c6, DjLevel.SUBTLE))).detail)
        assertIs<MixOutcome.Refused>(attempt(out = f.region(DeckTarget.OUT).copy(trackId = "not-out")))
        assertIs<MixOutcome.Refused>(attempt(out = f.region(DeckTarget.OUT).copy(sampleRateHz = 44_100)))
        val f1 = requireNotNull(RecipeRegistry.builtIn("F1"))
        val missing = attempt(f1, f.plan(f1), FxCapability.entries.toSet() - FxCapability.RISER)
        assertEquals("capability_missing:RISER", assertIs<MixOutcome.Refused>(missing).detail)
        // F1 declares FLANGER as its legacy RISER fallback. Frozen compilation refuses it.
        val missingStem = RecipeInterpreter.compileFrozen(requireNotNull(RecipeRegistry.builtIn("S2")),
            f.plan(requireNotNull(RecipeRegistry.builtIn("S2"))), f.geometry, f.region(DeckTarget.OUT), f.region(DeckTarget.IN))
        assertEquals(RefusalReason.STEM_DUCK_UNAVAILABLE, assertIs<MixOutcome.Refused>(missingStem).reason)
    }

    @Test
    fun canonicalCompilationNeverExtendsStopOrManufacturesLoopBounds() {
        val f = CanonicalFixture()
        val c2 = requireNotNull(RecipeRegistry.builtIn("C2"))
        val plan = f.plan(c2).copy(swapDownbeatMs = 76_000)
        val short = f.geometry.copy(out = f.geometry.out.copy(stopAtMixSec = 16.05))
        val rejected = RecipeInterpreter.compileFrozen(c2, plan, short, f.region(DeckTarget.OUT, short),
            f.region(DeckTarget.IN, short), useNoStemVariant = true)
        assertEquals(RefusalReason.HANDOFF_RENDER_BUDGET, assertIs<MixOutcome.Refused>(rejected).reason)
        assertEquals(16.05, short.out.stopAtMixSec)
        val c5 = requireNotNull(RecipeRegistry.builtIn("C5"))
        val noLoop = RecipeInterpreter.compileFrozen(c5, f.plan(c5), f.geometry,
            f.region(DeckTarget.OUT), f.region(DeckTarget.IN), useNoStemVariant = true)
        assertEquals("frozen_recipe_requires_out_loop", assertIs<MixOutcome.Refused>(noLoop).detail)
    }

    @Test
    fun s3RetainsLegacyExactGainFilterAndBassLanesWithBaseQPointNine() {
        val recipe = requireNotNull(RecipeRegistry.builtIn("S3"))
        val f = CanonicalFixture()
        val script = assertIs<MixOutcome.Ok<TransitionScriptV1>>(RecipeInterpreter.compileFrozen(recipe, f.plan(recipe), f.geometry,
            f.region(DeckTarget.OUT), f.region(DeckTarget.IN), useNoStemVariant = true)).value
        fun lane(deck: DeckTarget, kind: EnvelopeKind) = script.envelopes.single { it.deck == deck && it.kind == kind }
        val lp = lane(DeckTarget.IN, EnvelopeKind.LOW_PASS_HZ)
        val hp = lane(DeckTarget.OUT, EnvelopeKind.HIGH_PASS_HZ)
        assertEquals(listOf(0.0, 8.0), lp.timesSec)
        assertEquals(listOf(300f, 20000f), lp.values)
        assertEquals(listOf(0.0, 8.0), hp.timesSec)
        assertEquals(listOf(20f, 400f), hp.values)
        assertTrue(lp.exponential && hp.exponential)
        assertEquals(0.9f, script.fxParams.outSvfQ)
        assertEquals(0.9f, script.fxParams.inSvfQ)
        assertNull(script.fxParams.sweep)
        assertNull(script.fxParams.incomingSweep)
        val out = lane(DeckTarget.OUT, EnvelopeKind.GAIN)
        assertEquals(listOf(-f.geometry.out.prerollSec, 0.0, 8.0) + (1..12).map { 8.0 + 4.0 * it / 12.0 }, out.timesSec)
        val expectedOut = listOf(0f, 1f, 1f) + (1..12).map { cos(it / 12.0 * PI * 0.5).toFloat() }
        expectedOut.zip(out.values).forEach { (expected, actual) -> assertEquals(expected, actual, 1e-6f) }
        val incoming = lane(DeckTarget.IN, EnvelopeKind.GAIN)
        assertEquals(listOf(0.0, 0.001, 8.0) + (1..12).map { 8.0 + 0.5 * it / 12.0 }, incoming.timesSec)
        val expectedIn = listOf(0f, 0.9f, 0.9f) + (1..12).map { 0.9f + 0.1f * sin(it / 12.0 * PI * 0.5).toFloat() }
        expectedIn.zip(incoming.values).forEach { (expected, actual) -> assertEquals(expected, actual, 1e-6f) }
        for ((deck, values) in listOf(DeckTarget.OUT to listOf(0f, 0f, -24f), DeckTarget.IN to listOf(-12f, -12f, 0f))) {
            val low = lane(deck, EnvelopeKind.LOW_DB)
            assertEquals(listOf(0.0, 7.9375, 8.0625), low.timesSec)
            assertEquals(values, low.values)
        }
    }

    @Test
    fun configuredSweepsGetResolvedTimesAndInvalidRecipeNumbersNeverGetSanitized() {
        val base = requireNotNull(RecipeRegistry.builtIn("S1"))
        val sweep = RecipeStepV2.ConfigureFx(DeckTarget.OUT, RecipeFxType.SWEEP,
            startTime = StepTimeV2(RecipeAnchor.SWAP_BACK_ZERO, param = 2.0), endTime = StepTimeV2(RecipeAnchor.SWAP))
        val recipe = base.copy(steps = base.steps + sweep, noStemSteps = base.noStemSteps + sweep)
        val f = CanonicalFixture()
        val result = RecipeInterpreter.compileFrozen(recipe, f.plan(recipe), f.geometry, f.region(DeckTarget.OUT),
            f.region(DeckTarget.IN), useNoStemVariant = true)
        val configured = assertIs<MixOutcome.Ok<TransitionScriptV1>>(result).value.fxParams.sweep
        assertEquals(4.0, configured?.startMixSec)
        assertEquals(8.0, configured?.endMixSec)
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.4, 8.0)) {
            val bad = base.copy(noStemSteps = base.noStemSteps + RecipeStepV2.ConfigureFx(DeckTarget.OUT,
                RecipeFxType.SVF_Q, params = mapOf("q" to value)))
            assertIs<MixOutcome.Refused>(RecipeInterpreter.compileFrozen(bad, f.plan(bad), f.geometry,
                f.region(DeckTarget.OUT), f.region(DeckTarget.IN), useNoStemVariant = true))
        }
    }

    @Test
    fun generationNIsDetachedFromNestedSourceMutationAndRegistryReload() {
        val base = requireNotNull(RecipeRegistry.builtIn("S3"))
        val q = mutableMapOf("q" to 0.9)
        val hpPoints = (base.steps.filterIsInstance<RecipeStepV2.LaneCurve>().single { it.kind == EnvelopeKind.HIGH_PASS_HZ }).points.toMutableList()
        fun mutableSteps(steps: List<RecipeStepV2>) = steps.map { step -> when (step) {
            is RecipeStepV2.ConfigureFx -> step.copy(params = q)
            is RecipeStepV2.LaneCurve -> if (step.kind == EnvelopeKind.HIGH_PASS_HZ) step.copy(points = hpPoints) else step
            else -> step
        } }.toMutableList()
        val genre = base.requirements.genreAffinity.toMutableMap()
        val lengths = base.lengthOptionsBars.toMutableList()
        val source = base.copy(requirements = base.requirements.copy(genreAffinity = genre),
            lengthOptionsBars = lengths, steps = mutableSteps(base.steps), noStemSteps = mutableSteps(base.noStemSteps))
        val frozen = source.frozenCopy()
        val f = CanonicalFixture()
        val planN = f.plan(frozen).copy(generation = 100)
        fun compile(r: RecipeV2, p: TransitionPlanV1) = assertIs<MixOutcome.Ok<TransitionScriptV1>>(
            RecipeInterpreter.compileFrozen(r, p, f.geometry, f.region(DeckTarget.OUT), f.region(DeckTarget.IN), useNoStemVariant = true)).value
        val original = compile(frozen, planN)
        q["q"] = 1.1
        hpPoints[hpPoints.lastIndex] = hpPoints.last().copy(value = 800f)
        genre["POP"] = 0.99
        lengths += 16
        assertEquals(0.16, frozen.requirements.genreAffinity["POP"])
        assertEquals(listOf(8), frozen.lengthOptionsBars)
        assertEquals(original, compile(frozen, planN))
        val reload = RecipeRegistry.overrideFromJson("S3", RecipeRegistry.json.encodeToString(RecipeV2.serializer(), source))
        assertIs<MixOutcome.Ok<RecipeV2>>(reload)
        val next = requireNotNull(RecipeRegistry.byId("S3"))
        assertEquals(1.1f, compile(next, planN.copy(generation = 101)).fxParams.outSvfQ)
        assertEquals(original, compile(frozen, planN))
        assertEquals(0.9f, original.fxParams.outSvfQ)
        val escaped = (next.steps.first() as RecipeStepV2.LaneCurve).points as? MutableList<WaypointV2>
        escaped?.clear()
        // Egress copies do not let clients mutate the registry's private generation source.
        assertTrue((requireNotNull(RecipeRegistry.byId("S3")).steps.first() as RecipeStepV2.LaneCurve).points.isNotEmpty())
    }

    private fun fullStems(f: CanonicalFixture): List<StemRegionRef> = DeckTarget.entries.flatMap { deck ->
        val region = f.region(deck)
        StemKind.entries.map { StemRegionRef(deck, it, region.trackId, region.startMs, region.endMs, sampleRateHz = f.sr) }
    }
}
