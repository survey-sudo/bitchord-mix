package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.AbruptnessClass
import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.FxCapability
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.PhraseBoundaryV3
import com.music.bitchord.djmix.core.contracts.RecipeFxType
import com.music.bitchord.djmix.core.contracts.RecipeV2
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.SectionType
import com.music.bitchord.djmix.core.contracts.SongStructureV3
import com.music.bitchord.djmix.core.contracts.StemRegionRef
import com.music.bitchord.djmix.core.contracts.StructureSectionV3
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.planner.BuiltInRecipeCatalog
import com.music.bitchord.djmix.core.planner.PairPlanner
import com.music.bitchord.djmix.core.planner.PlannerRequest
import com.music.bitchord.djmix.core.planner.RecipeCatalogEntry
import com.music.bitchord.djmix.core.recipes.RecipeRegistry
import com.music.bitchord.djmix.core.recipes.RecipeInterpreter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** AE3 A§5.11–5.12: bundled FX coverage and history-driven Festival variety. */
class RecipeCoverageAndVarietyTest {
    private val clubFx = setOf(
        RecipeFxType.LOOP, RecipeFxType.ROLL, RecipeFxType.SWEEP,
        RecipeFxType.FLANGER, RecipeFxType.PHASER, RecipeFxType.ECHO,
    )
    private val festivalFx = clubFx + setOf(
        RecipeFxType.RISER, RecipeFxType.BRAKE, RecipeFxType.SPINBACK, RecipeFxType.GATE,
    )

    // Read immutable originals; another test's installed overrides cannot change
    // bundled coverage and this test does not mutate global registry state.
    private fun bundledRecipes(): List<RecipeV2> = expectedIds.map { id ->
        requireNotNull(RecipeRegistry.builtIn(id)) { "Missing bundled recipe $id" }
    }

    @Test
    fun bundled26RecipesAndBuiltInDescriptorsCoverEachRequiredFxFamily() {
        val descriptors = BuiltInRecipeCatalog.entries
        assertEquals(expectedIds, descriptors.map { it.id })
        assertCoverage(descriptors, "built-in descriptors")

        val recipes = bundledRecipes()
        assertEquals(26, recipes.size)
        assertTrue("Every bundled recipe must remain enabled", recipes.all { it.enabled })
        assertTrue("Every recipe needs a no-stem variant", recipes.all { it.noStemSteps.isNotEmpty() })
        assertCoverage(recipes.map(BuiltInRecipeCatalog::fromRecipeV2), "bundled JSON steps")
        assertCoverage(
            recipes.map { BuiltInRecipeCatalog.fromRecipeV2(it.copy(steps = it.noStemSteps)) },
            "bundled JSON noStemSteps",
        )
    }

    @Test
    fun festivalPlannerRotatesRecipesAndEffectsWithBuiltInDescriptors() {
        assertFestivalVariety(BuiltInRecipeCatalog.entries, "built-in descriptors")
    }

    @Test
    fun festivalPlannerRotatesRecipesAndEffectsWithBundledJson() {
        assertFestivalVariety(bundledRecipes().map(BuiltInRecipeCatalog::fromRecipeV2), "bundled JSON")
    }

    @Test
    fun f1AndF5DescriptorsMatchPrimaryFxInBothBundledVariants() {
        for (id in listOf("F1", "F5")) {
            val recipe = requireNotNull(RecipeRegistry.builtIn(id))
            val descriptor = requireNotNull(BuiltInRecipeCatalog.byId(id))
            for (steps in listOf(recipe.steps, recipe.noStemSteps)) {
                val actual = BuiltInRecipeCatalog.fromRecipeV2(recipe.copy(steps = steps))
                assertEquals("$id must not advertise fallback-only FX as primary", actual.fxTags, descriptor.fxTags)
            }
        }
    }

    @Test
    fun f1EveryRiserAndGateCapabilityCombinationCompilesWithinFestivalBudget() {
        val recipe = requireNotNull(RecipeRegistry.builtIn("F1"))
        for (bars in recipe.lengthOptionsBars) {
            val durationMs = bars * 2_000L
            val plan = TransitionPlanV1(
                planId = "f1-fallback-$bars", generation = 1L,
                outTrackId = "out", inTrackId = "in", djLevel = DjLevel.FESTIVAL,
                geometry = TransitionGeometry.BRIDGE, frozenExitMs = 60_000L,
                frozenEntryMs = 0L, swapDownbeatMs = 60_000L + durationMs,
                overlapDurationMs = durationMs, barDurationMs = 2_000L,
                recipeId = recipe.id, recipeName = recipe.name,
            )
            val stems = recipe.requirements.requiredStems.map {
                StemRegionRef(it.deck, it.stem, if (it.deck == DeckTarget.OUT) "out" else "in", 0L, 120_000L)
            }
            for (noStem in listOf(false, true)) {
                for (riserAvailable in listOf(false, true)) {
                    for (gateAvailable in listOf(false, true)) {
                        val caps = FxCapability.entries.toSet() - buildSet {
                            if (!riserAvailable) add(FxCapability.RISER)
                            if (!gateAvailable) add(FxCapability.GATE)
                        }
                        val outcome = RecipeInterpreter.interpret(
                            recipe, plan,
                            AudioRegionRef(DeckTarget.OUT, "out", 50_000L, 120_000L),
                            AudioRegionRef(DeckTarget.IN, "in", 0L, 120_000L),
                            stems = if (noStem) emptyList() else stems,
                            useNoStemVariant = noStem, availableCapabilities = caps,
                        )
                        assertTrue("bars=$bars noStem=$noStem riser=$riserAvailable gate=$gateAvailable: $outcome", outcome is MixOutcome.Ok)
                        val fx = (outcome as MixOutcome.Ok).value.fxParams
                        assertEquals(riserAvailable, fx.riser?.enabled == true)
                        assertEquals(riserAvailable, fx.impact?.enabled == true)
                        assertEquals(!riserAvailable, fx.flanger?.enabled == true)
                        assertEquals(gateAvailable, fx.gate?.enabled == true)
                        assertTrue("F1 must retain its roll and echo", fx.roll?.enabled == true && fx.echo?.enabled == true)
                    }
                }
            }
        }
    }

    @Test
    fun abruptBundledRecipesRequireFestivalJumpAndCooldown() {
        val abrupt = bundledRecipes().filter { it.abruptness == AbruptnessClass.ABRUPT }
        assertEquals(setOf("F2", "F7", "F9"), abrupt.map { it.id }.toSet())
        for (recipe in abrupt) {
            assertEquals(DjLevel.FESTIVAL, recipe.level)
            assertTrue("${recipe.id} must declare a jump requirement", recipe.requirements.requiresTempoOrGenreJump)
            val catalog = listOf(BuiltInRecipeCatalog.fromRecipeV2(recipe))
            val base = PlannerRequest(
                generation = 1L, outTrack = track("out", bpm = 100.0), inTrack = track("in", bpm = 115.0),
                djLevel = DjLevel.FESTIVAL, positionMs = 0L, transitionsSinceLastAbrupt = 8,
            )
            for (request in listOf(
                base.copy(djLevel = DjLevel.CLUB),
                base.copy(inTrack = track("same-tempo", bpm = 100.0)),
                base.copy(inTrack = track("nine-percent", bpm = 109.0)),
                base.copy(transitionsSinceLastAbrupt = 7),
            )) {
                val result = PairPlanner.plan(request, catalog)
                assertTrue(
                    "${recipe.id} should refuse level=${request.djLevel}, bpm=${request.inTrack.bpm}, " +
                        "cooldown=${request.transitionsSinceLastAbrupt}; got $result",
                    result is MixOutcome.Refused,
                )
                assertEquals(RefusalReason.ABRUPT_DISALLOWED, (result as MixOutcome.Refused).reason)
            }
            assertEquals(recipe.id, requirePlan(PairPlanner.plan(base, catalog)).recipeId)
            assertEquals(
                recipe.id,
                requirePlan(PairPlanner.plan(base.copy(
                    inTrack = track("genre-jump", bpm = 109.0), outGenus = "HIPHOP", inGenus = "DANCE",
                ), catalog)).recipeId,
            )
        }
    }

    private fun assertCoverage(catalog: List<RecipeCatalogEntry>, label: String) {
        for ((level, required) in listOf(DjLevel.CLUB to clubFx, DjLevel.FESTIVAL to festivalFx)) {
            val actual = catalog.filter { it.enabled && it.level == level }.flatMap { it.fxTags }.toSet()
            assertTrue("$label $level missing FX ${required - actual}; actual=$actual", actual.containsAll(required))
        }
    }

    private fun assertFestivalVariety(catalog: List<RecipeCatalogEntry>, label: String) {
        val history = mutableListOf<String>()
        var transitionsSinceLastAbrupt = 8
        // Repeatedly plan equally blendable neighbours without exclusions or taste
        // weights. Only recentRecipeIds changes the recipe/Fx diversity pressure.
        val tracks = (0..12).map { track("track-$it") }
        repeat(12) { index ->
            val plan = requirePlan(PairPlanner.plan(
                PlannerRequest(
                    generation = index.toLong(), outTrack = tracks[index], inTrack = tracks[index + 1],
                    djLevel = DjLevel.FESTIVAL, positionMs = 0L,
                    outGenus = "DANCE", inGenus = "DANCE",
                    recentRecipeIds = history.toList(), transitionsSinceLastAbrupt = transitionsSinceLastAbrupt,
                ),
                catalog,
            ))
            val chosen = requireNotNull(catalog.firstOrNull { it.id == plan.recipeId })
            assertTrue("$label picked abrupt ${chosen.id} for a blendable pair", chosen.abruptness != AbruptnessClass.ABRUPT)
            transitionsSinceLastAbrupt = if (chosen.abruptness == AbruptnessClass.ABRUPT) 0 else transitionsSinceLastAbrupt + 1
            history += chosen.id
        }
        for ((index, window) in history.windowed(6).withIndex()) {
            val effects = window.flatMap { id -> catalog.first { it.id == id }.fxTags }.toSet()
            assertTrue("$label window $index has fewer than 4 recipes: $window", window.toSet().size >= 4)
            assertTrue("$label window $index has fewer than 5 FX: $effects; recipes=$window", effects.size >= 5)
        }
    }

    private fun requirePlan(outcome: MixOutcome<TransitionPlanV1>): TransitionPlanV1 = when (outcome) {
        is MixOutcome.Ok -> outcome.value
        else -> error("Expected a plan, got $outcome")
    }

    private fun track(id: String, bpm: Double = 120.0): TrackAnalysisV3 {
        val durationMs = 240_000L
        val beatMs = (60_000.0 / bpm).toLong()
        val barMs = 4 * beatMs
        return TrackAnalysisV3(
            trackId = id, durationMs = durationMs, analysisLevel = AnalysisLevel.FULL,
            bpm = bpm, gridConfidence = 0.9f,
            beatsMs = (0L until durationMs step beatMs).toList(),
            downbeatsMs = (0L until durationMs step barMs).toList(),
            keyCamelot = "8A", keyConfidence = 0.9f,
            vocalPresence50fps = List((durationMs / 20L).toInt()) { 0.05f },
            energyCurve = List(24) { 0.7f },
            structure = SongStructureV3(
                sections = listOf(
                    StructureSectionV3(0L, 64_000L, type = SectionType.INTRO),
                    StructureSectionV3(64_000L, 160_000L, type = SectionType.CHORUS),
                    StructureSectionV3(160_000L, durationMs, type = SectionType.OUTRO),
                ),
                phrases = (0L until durationMs step 8 * barMs).mapIndexed { index, time ->
                    PhraseBoundaryV3(time, index * 8)
                },
            ),
        )
    }

    private val expectedIds = (1..6).map { "S$it" } + (1..9).map { "C$it" } + (1..11).map { "F$it" }
}
