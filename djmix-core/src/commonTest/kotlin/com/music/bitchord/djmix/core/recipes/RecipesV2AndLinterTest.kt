package com.music.bitchord.djmix.core.recipes

import com.music.bitchord.djmix.core.contracts.AbruptnessClass
import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.EnvelopeKind
import com.music.bitchord.djmix.core.contracts.EnvelopeLane
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RecipeFxType
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.StemKind
import com.music.bitchord.djmix.core.contracts.StemRegionRef
import com.music.bitchord.djmix.core.contracts.TimeRangeMs
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.planner.BuiltInRecipeCatalog
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Milestone M4-B verification: all 26 RecipeV2 JSONs, B5.3 linter rules, FX coverage, and ChoreoGuard. */
class RecipesV2AndLinterTest {

    @AfterTest
    fun resetRegistry() {
        RecipeRegistry.resetOverrides()
    }

    @Test
    fun all26RecipesParseAndMatchBuiltInCatalogMetadata() {
        val recipes = RecipeRegistry.all()
        assertEquals(26, recipes.size)
        val expectedIds = listOf(
            "S1", "S2", "S3", "S4", "S5", "S6",
            "C1", "C2", "C3", "C4", "C5", "C6", "C7", "C8", "C9",
            "F1", "F2", "F3", "F4", "F5", "F6", "F7", "F8", "F9", "F10", "F11",
        )
        assertEquals(expectedIds, recipes.map { it.id })
        for (recipe in recipes) {
            val cat = BuiltInRecipeCatalog.byId(recipe.id)
            assertNotNull(cat, "Missing BuiltInRecipeCatalog entry for ${recipe.id}")
            assertEquals(cat.displayName, recipe.name, "Name mismatch on ${recipe.id}")
            assertEquals(cat.description, recipe.description, "Desc mismatch on ${recipe.id}")
            assertEquals(cat.level, recipe.level, "Level mismatch on ${recipe.id}")
            assertEquals(cat.family, recipe.family, "Family mismatch on ${recipe.id}")
            assertEquals(cat.abruptness, recipe.abruptness, "Abruptness mismatch on ${recipe.id}")
            assertEquals(cat.requiredStems, recipe.requirements.requiredStems, "Stems mismatch on ${recipe.id}")
        }
        assertEquals(26, RecipeRegistry.toCatalogEntries().size)
    }

    @Test
    fun linterPassesAll26RecipesAndPartA5CoverageAndOverridesWork() {
        val all = RecipeRegistry.all()
        val report = RecipeLinter.lintAll(all)
        assertTrue(report.passed, "Linter errors: ${report.errors.joinToString { "${it.recipeId}:${it.message}" }}")

        val clubFx = all.filter { it.level == DjLevel.CLUB }.flatMap { RecipeLinter.directFxTags(it) }.toSet()
        val festFx = all.filter { it.level == DjLevel.FESTIVAL }.flatMap { RecipeLinter.directFxTags(it) }.toSet()
        assertTrue(clubFx.containsAll(setOf(RecipeFxType.LOOP, RecipeFxType.ROLL, RecipeFxType.SWEEP, RecipeFxType.FLANGER, RecipeFxType.PHASER, RecipeFxType.ECHO)))
        assertTrue(festFx.containsAll(RecipeLinter.REQUIRED_CLUB_FX + RecipeLinter.REQUIRED_FESTIVAL_EXTRA_FX))

        for (id in listOf("F2", "F7", "F9")) {
            val r = RecipeRegistry.byId(id)!!
            assertEquals(DjLevel.FESTIVAL, r.level)
            assertEquals(AbruptnessClass.ABRUPT, r.abruptness)
        }

        val overriddenJson = BundledRecipesJson.byId["S6"]!!.replace("Breakdown blend", "Breakdown blend v2")
        val ovResult = RecipeRegistry.overrideFromJson("S6", overriddenJson)
        assertTrue(ovResult is MixOutcome.Ok)
        assertEquals("Breakdown blend v2", RecipeRegistry.byId("S6")?.name)
        assertEquals("Breakdown blend v2", RecipeRegistry.toCatalogEntries().first { it.id == "S6" }.displayName)
        RecipeRegistry.resetOverrides()
        assertEquals("Breakdown blend", RecipeRegistry.byId("S6")?.name)
    }

    @Test
    fun interpreterAndChoreoGuardCompileRepresentativeRecipesNormalAndNoStem() {
        val stems = StemKind.entries.flatMap { stem ->
            listOf(
                StemRegionRef(DeckTarget.OUT, stem, "out-t", 40_000L, 120_000L),
                StemRegionRef(DeckTarget.IN, stem, "in-t", 0L, 60_000L),
            )
        }
        val outReg = AudioRegionRef(DeckTarget.OUT, "out-t", 40_000L, 120_000L)
        val inReg = AudioRegionRef(DeckTarget.IN, "in-t", 0L, 60_000L)

        val representative = listOf("S6", "C5", "C6", "C8", "C9", "F1", "F2", "F9", "F10", "F11")
        for (id in representative) {
            val recipe = RecipeRegistry.byId(id)!!
            val bars = recipe.lengthOptionsBars.first()
            val overlapMs = bars * 2_000L
            val swapOffsetMs = if (id in setOf("F1", "F11")) overlapMs else overlapMs / 2L
            val plan = TransitionPlanV1(
                planId = "test-$id", generation = 1L, outTrackId = "out-t", inTrackId = "in-t",
                djLevel = recipe.level,
                geometry = if (recipe.requirements.requiresOutLoop) TransitionGeometry.LOOP else TransitionGeometry.BRIDGE,
                frozenExitMs = 60_000L, frozenEntryMs = 0L, swapDownbeatMs = 60_000L + swapOffsetMs,
                overlapDurationMs = overlapMs, barDurationMs = 2_000L, recipeId = id, recipeName = recipe.name,
            )
            for (noStem in listOf(false, true)) {
                val res = RecipeInterpreter.interpret(
                    recipe = recipe,
                    plan = plan,
                    outRegion = outReg,
                    inRegion = inReg,
                    stems = stems,
                    useNoStemVariant = noStem,
                    dropWindowMs = if (id in setOf("F1", "F11")) TimeRangeMs(60_000L + swapOffsetMs, 60_000L + swapOffsetMs + 8_000L) else null,
                )
                assertTrue(res is MixOutcome.Ok, "Expected Ok for $id (noStem=$noStem), got $res")
                val script: TransitionScriptV1 = (res as MixOutcome.Ok).value
                assertNull(ChoreoGuard.check(script, plan, swapsBass = id !in setOf("F2", "F9"), requireStemsOnStemLanes = !noStem))
                if (noStem) {
                    assertTrue(script.stems.isEmpty())
                    assertFalse(script.envelopes.any { it.kind.isStem })
                }
            }
        }

        // Verify abrupt gate refuses CLUB level on F2/F9
        val f2 = RecipeRegistry.byId("F2")!!
        val clubPlan = TransitionPlanV1(
            planId = "f2-club", generation = 1L, outTrackId = "out-t", inTrackId = "in-t",
            djLevel = DjLevel.CLUB, geometry = TransitionGeometry.STRAIGHT,
            frozenExitMs = 60_000L, frozenEntryMs = 0L, swapDownbeatMs = 68_000L,
            overlapDurationMs = 8_000L, barDurationMs = 2_000L, recipeId = "F2", recipeName = f2.name,
        )
        val refused = RecipeInterpreter.interpret(f2, clubPlan, outReg, inReg, useNoStemVariant = true)
        assertTrue(refused is MixOutcome.Refused && refused.reason == RefusalReason.ABRUPT_DISALLOWED)

        // Verify ChoreoGuard catches non-neutral FILTER_KNOB at handover
        val s6Plan = TransitionPlanV1(
            planId = "guard-check", generation = 1L, outTrackId = "out-t", inTrackId = "in-t",
            djLevel = DjLevel.SUBTLE, geometry = TransitionGeometry.BRIDGE,
            frozenExitMs = 60_000L, frozenEntryMs = 0L, swapDownbeatMs = 68_000L,
            overlapDurationMs = 16_000L, barDurationMs = 2_000L, recipeId = "S6", recipeName = "Breakdown blend",
        )
        val s6Ok = (RecipeInterpreter.interpret(RecipeRegistry.byId("S6")!!, s6Plan, outReg, inReg) as MixOutcome.Ok).value
        val badKnob = s6Ok.copy(
            envelopes = s6Ok.envelopes + EnvelopeLane(
                DeckTarget.IN, EnvelopeKind.FILTER_KNOB, listOf(0.0, 16.0), listOf(-0.6f, -0.5f),
            ),
        )
        assertNotNull(ChoreoGuard.check(badKnob, s6Plan))
    }
}
