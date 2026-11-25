package com.music.bitchord.djmix.core.recipes

import com.music.bitchord.djmix.core.contracts.AudioRegionRef
import com.music.bitchord.djmix.core.contracts.DeckStemRequirement
import com.music.bitchord.djmix.core.contracts.DeckTarget
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.ENGINE_VERSION
import com.music.bitchord.djmix.core.contracts.EnvelopeKind
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.RecipeFxType
import com.music.bitchord.djmix.core.contracts.StemKind
import com.music.bitchord.djmix.core.contracts.StemRegionRef
import com.music.bitchord.djmix.core.contracts.ThermalLevel
import com.music.bitchord.djmix.core.contracts.TransitionGeometry
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.contracts.TransitionScriptV1
import com.music.bitchord.djmix.core.machine.MixEffect
import com.music.bitchord.djmix.core.machine.MixEvent
import com.music.bitchord.djmix.core.machine.MixMachine
import com.music.bitchord.djmix.core.machine.MixState
import com.music.bitchord.djmix.core.machine.PairKey
import com.music.bitchord.djmix.core.planner.BuiltInRecipeCatalog
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A§5.11 FX-family and A§8.3 core regression checks, kept separate from the legacy contract suite. */
class FxCoverageAndInvariantsTest {

    private val pair = PairKey("out-invariant", "in-invariant")

    private fun plan(level: DjLevel, recipeId: String = "invariant-recipe") = TransitionPlanV1(
        planId = "plan-${level.name}",
        generation = 1L,
        outTrackId = pair.outTrackId,
        inTrackId = pair.inTrackId,
        djLevel = level,
        geometry = TransitionGeometry.BRIDGE,
        frozenExitMs = 180_000L,
        frozenEntryMs = 164_000L,
        swapDownbeatMs = 180_000L,
        overlapDurationMs = 16_000L,
        barDurationMs = 2_000L,
        recipeId = recipeId,
        recipeName = "Invariant recipe",
        requiredStems = setOf(
            DeckStemRequirement(DeckTarget.OUT, StemKind.BASS),
            DeckStemRequirement(DeckTarget.IN, StemKind.BASS),
        ),
        hasNoStemVariant = true,
    )

    @Test
    fun clubAndFestivalCatalogsCoverRequiredFxFamiliesInBothVariants() {
        val recipes = RecipeRegistry.all()
        assertEquals(26, recipes.size)

        fun directFx(recipe: com.music.bitchord.djmix.core.contracts.RecipeV2, noStem: Boolean): Set<RecipeFxType> {
            val steps = if (noStem) recipe.noStemSteps else recipe.steps
            return buildSet {
                for (step in steps) {
                    when (step) {
                        is com.music.bitchord.djmix.core.contracts.RecipeStepV2.ConfigureFx -> add(step.fx)
                        is com.music.bitchord.djmix.core.contracts.RecipeStepV2.LaneCurve -> {
                            if (step.kind in setOf(EnvelopeKind.HIGH_PASS_HZ, EnvelopeKind.LOW_PASS_HZ, EnvelopeKind.FILTER_KNOB)) {
                                add(RecipeFxType.SWEEP)
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }

        val clubRequired = setOf(
            RecipeFxType.LOOP, RecipeFxType.ROLL, RecipeFxType.SWEEP,
            RecipeFxType.FLANGER, RecipeFxType.PHASER, RecipeFxType.ECHO,
        )
        val festivalExtra = setOf(
            RecipeFxType.RISER, RecipeFxType.BRAKE, RecipeFxType.SPINBACK, RecipeFxType.GATE,
        )
        for (noStem in listOf(false, true)) {
            val clubFx = recipes.filter { it.level == DjLevel.CLUB }.flatMap { directFx(it, noStem) }.toSet()
            val festivalFx = recipes.filter { it.level == DjLevel.FESTIVAL }.flatMap { directFx(it, noStem) }.toSet()
            assertTrue(clubFx.containsAll(clubRequired), "Club noStem=$noStem missing ${clubRequired - clubFx}")
            assertTrue(festivalFx.containsAll(clubRequired + festivalExtra), "Festival noStem=$noStem missing ${(clubRequired + festivalExtra) - festivalFx}")
        }

        assertEquals(
            setOf(
                RecipeFxType.RISER, RecipeFxType.GATE, RecipeFxType.ECHO, RecipeFxType.ROLL,
                RecipeFxType.LOOP_ROLL, RecipeFxType.SWEEP, RecipeFxType.IMPACT,
            ),
            BuiltInRecipeCatalog.F1.fxTags,
        )
        assertEquals(
            setOf(RecipeFxType.PHASER, RecipeFxType.REVERB, RecipeFxType.GATE, RecipeFxType.SWEEP),
            BuiltInRecipeCatalog.F5.fxTags,
            "F5 tags advertise primary FX, not the optional flanger fallback",
        )
        for (id in listOf("F1", "F5")) {
            val recipe = requireNotNull(RecipeRegistry.builtIn(id))
            assertEquals(BuiltInRecipeCatalog.byId(id)!!.fxTags, RecipeLinter.directFxTags(recipe))
        }
    }

    @Test
    fun pauseStaysStickyThroughReadyTicksAndOnlyResumesOnExplicitUserResume() {
        var state: MixState = MixState.Ready(
            pair = pair,
            generation = 1L,
            djLevel = DjLevel.CLUB,
            plan = plan(DjLevel.CLUB),
            lastPositionMs = 174_000L,
        )

        state = MixMachine.reduce(state, MixEvent.UserPause).first
        assertTrue(state.userPaused)

        val pausedTick = MixMachine.reduce(state, MixEvent.Tick(180_000L, isPlaying = true))
        state = pausedTick.first
        assertTrue(state is MixState.Ready)
        assertTrue((state as MixState.Ready).userPaused, "tick at the frozen exit must not clear user pause")
        assertTrue(pausedTick.second.none { it is MixEffect.Start || it is MixEffect.ResumeSync })

        val routeChange = MixMachine.reduce(state, MixEvent.RouteChanged(150L))
        state = routeChange.first
        assertTrue(state.userPaused, "route changes must preserve user pause")
        assertTrue(routeChange.second.none { it is MixEffect.ResumeSync })

        val resumed = MixMachine.reduce(state, MixEvent.UserResume)
        assertTrue(resumed.first is MixState.Running, "explicit resume at the exit should fire the prepared transition")
        assertFalse(resumed.first.userPaused)
        assertTrue(resumed.second.any { it is MixEffect.ResumeSync })
        assertTrue(resumed.second.any { it is MixEffect.Start })
    }

    @Test
    fun seekSkipAndHandoverRetireOnlyTheOutgoingDeck() {
        val transitionPlan = plan(DjLevel.CLUB)
        val running = MixState.Running(
            pair = pair,
            generation = 1L,
            djLevel = DjLevel.CLUB,
            plan = transitionPlan,
            startedAtMs = transitionPlan.frozenExitMs,
        )
        val handingOver = MixState.HandingOver(
            pair = pair,
            generation = 1L,
            djLevel = DjLevel.CLUB,
            plan = transitionPlan,
        )

        for ((state, event) in listOf(
            running to MixEvent.UserSeek(190_000L),
            running to MixEvent.UserSkip,
            handingOver to MixEvent.UserSeek(190_000L),
            handingOver to MixEvent.UserSkip,
            handingOver to MixEvent.HandoverComplete,
        )) {
            val effects = MixMachine.reduce(state, event).second.filterIsInstance<MixEffect.Retire>()
            assertTrue(effects.isNotEmpty(), "$state x $event should retire the outgoing deck")
            assertTrue(effects.all { it.deck != DeckTarget.IN && it.preserveSurvivor }, "$state x $event retired the survivor: $effects")
        }
    }

    @Test
    fun noStemFallbackPreservesEachDjLevelAndDropsStemAutomation() {
        for (level in DjLevel.entries) {
            val preparingPlan = plan(level, recipeId = "S2")
            var state: MixState = MixState.Preparing(
                pair = pair,
                generation = 1L,
                djLevel = level,
                plan = preparingPlan,
                regionsReady = true,
                stemsReady = false,
                stemsRequired = 2,
                lastPositionMs = 170_000L,
            )

            val fallback = MixMachine.reduce(state, MixEvent.Tick(174_000L, isPlaying = true))
            state = fallback.first
            assertEquals(level, state.djLevel, "no-stem selection changed DJ level $level")
            assertTrue(state is MixState.Preparing && state.usingNoStemVariant)
            assertTrue(fallback.second.filterIsInstance<MixEffect.PrepareRenderer>().single().useNoStemVariant)

            val ready = MixMachine.reduce(state, MixEvent.RendererPrepared(preparingPlan.planId, 1L, usedNoStemVariant = true))
            assertEquals(level, ready.first.djLevel, "renderer promotion changed DJ level $level")
            assertTrue(ready.first is MixState.Armed && (ready.first as MixState.Armed).usingNoStemVariant)
        }

        val recipe = requireNotNull(RecipeRegistry.byId("S2"))
        val outRegion = AudioRegionRef(DeckTarget.OUT, pair.outTrackId, 0L, 180_000L)
        val inRegion = AudioRegionRef(DeckTarget.IN, pair.inTrackId, 0L, 180_000L)
        val stems = listOf(DeckTarget.OUT, DeckTarget.IN).flatMap { deck ->
            recipe.requirements.requiredStems.filter { it.deck == deck }.map { requirement ->
                StemRegionRef(deck, requirement.stem, if (deck == DeckTarget.OUT) pair.outTrackId else pair.inTrackId, 0L, 180_000L)
            }
        }
        for (level in DjLevel.entries) {
            val currentPlan = plan(level, recipeId = "S2")
            val stemScript = compile(recipe, currentPlan, outRegion, inRegion, stems, useNoStemVariant = false)
            val noStemScript = compile(recipe, currentPlan, outRegion, inRegion, stems, useNoStemVariant = true)
            assertEquals(stems, stemScript.stems)
            assertTrue(noStemScript.stems.isEmpty())
            assertFalse(noStemScript.envelopes.any { it.kind.isStem }, "stem lane leaked into $level no-stem script")
        }
    }

    @Test
    fun engineVersionAndFixedSeedReplayRemainDeterministic() {
        assertEquals("3.0.0", ENGINE_VERSION)
        val seed = 0xA83L

        fun eventsForSeed(): List<MixEvent> {
            val random = Random(seed)
            return buildList {
                add(MixEvent.NextKnown(pair, positionMs = 10_000L, djLevel = DjLevel.CLUB))
                add(MixEvent.PlanFrozen(plan(DjLevel.CLUB), generation = 1L))
                add(MixEvent.RegionsReady(plan(DjLevel.CLUB).planId, generation = 1L))
                add(MixEvent.StemsReady(plan(DjLevel.CLUB).planId, generation = 1L, stemsCompleted = 2, stemsRequired = 2))
                add(MixEvent.RendererPrepared(plan(DjLevel.CLUB).planId, generation = 1L))
                repeat(64) {
                    add(
                        when (random.nextInt(5)) {
                            0 -> MixEvent.Tick(random.nextLong(170_000L, 205_000L), isPlaying = random.nextBoolean())
                            1 -> MixEvent.UserPause
                            2 -> MixEvent.UserResume
                            3 -> MixEvent.RouteChanged(random.nextLong(0L, 251L))
                            else -> MixEvent.ThermalChanged(ThermalLevel.entries[random.nextInt(ThermalLevel.entries.size)])
                        },
                    )
                }
            }
        }

        val events = eventsForSeed()
        assertEquals(events, eventsForSeed(), "fixed seed must generate the same event sequence")
        val initial = MixState.Idle(generation = 0L, djLevel = DjLevel.CLUB)
        assertEquals(MixMachine.replay(initial, events), MixMachine.replay(initial, events))
    }

    private fun compile(
        recipe: com.music.bitchord.djmix.core.contracts.RecipeV2,
        plan: TransitionPlanV1,
        outRegion: AudioRegionRef,
        inRegion: AudioRegionRef,
        stems: List<StemRegionRef>,
        useNoStemVariant: Boolean,
    ): TransitionScriptV1 {
        val outcome = RecipeInterpreter.interpret(recipe, plan, outRegion, inRegion, stems, useNoStemVariant)
        assertTrue(outcome is MixOutcome.Ok, "Expected ${recipe.id} noStem=$useNoStemVariant to compile; got $outcome")
        return (outcome as MixOutcome.Ok).value
    }
}
