package com.music.bitchord.playback.smart.choreo

import com.music.bitchord.playback.smart.EnergySample
import com.music.bitchord.playback.smart.LoopMix
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Step 5 verification for data-driven DJ levels & 20 choreography recipes:
 * - Every recipe (S1-S5, C1-C7, F1-F8) passes ChoreoGuard WITH and WITHOUT stems.
 * - Strict level scoping + Festival fallback ladder (never drops below Festival).
 * - Anti-repeat: 0 back-to-back recipe repeats across 30 consecutive transitions per level.
 * - FX budget compliance (Festival 2-3 FX, Club <= 2 FX, Subtle <= 1 FX).
 * - Festival drop exemption window metadata & genre weighting.
 */
class RecipesTest {

    @BeforeTest
    fun reset() {
        AutomixV2Choreo.enabled = true
        AutomixV2Choreo.useStems = true
        AutomixV2Choreo.seed = 0L
        AutomixV2Choreo.history.clear()
    }

    private fun track(
        id: String,
        bpm: Double,
        key: String = "C major",
        keyConfidence: Double = 0.85,
        beatConfidence: Double = 0.9,
        vocal: Double = 0.3,
        energy: List<EnergySample> = List(24) { EnergySample(it * 10.0, 0.7) },
    ) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = id,
        duration = 240.0,
        bpm = bpm,
        beatInterval = 60.0 / bpm,
        beatConfidence = beatConfidence,
        key = key,
        keyConfidence = keyConfidence,
        vocalProbability = vocal,
        energyCurve = energy,
        quality = TrackAnalysis.QUALITY_FULL,
    )

    private fun pick(start: Double, end: Double, bpm: Double) =
        LoopMix.Pick(start, end, 4, 60.0 / bpm, bpm, 0.0, LoopMix.Check.MASK)

    private fun plan(
        outBpm: Double,
        inBpm: Double = outBpm,
        bars: Int = 16,
        outLoops: Boolean = false,
        inStart: Double = 10.0,
        beatMatched: Boolean = true,
    ): LoopMix.Plan {
        val bar = 4 * 60.0 / outBpm
        val hold = if (beatMatched) 0.0 else bar
        val enter = if (beatMatched) 0 else 1
        val exit = bars - enter
        return LoopMix.Plan(
            out = pick(180.0, 180.0 + 4 * bar, outBpm),
            inn = pick(inStart, inStart + 4 * bar, inBpm),
            fold = 1.0,
            enterBars = enter,
            rampBars = 0,
            exitBars = exit,
            totalBars = bars,
            outLoops = outLoops,
            inLoops = false,
            nativeIncoming = true,
            echoTail = !beatMatched,
            rung = if (beatMatched) "Beat blend" else "Echo",
            targetBpmOverride = if (beatMatched) null else outBpm,
            incomingHoldSec = hold,
        )
    }

    private fun stems(vocal: Double = 0.1, bass: Double = 0.3) = StemView(
        stemCount = 4,
        confidence = 0.92f,
        regionStartSec = 0.0,
        sampleRate = 1000,
        frames = 1000 * 600,
    ) { part, _, _ ->
        when (part) {
            StemPart.ALL -> 1.0
            StemPart.VOCALS -> vocal
            StemPart.BASS -> bass
            else -> 0.5
        }
    }

    private fun buildInputsForRecipe(recipeId: RecipeId, withStems: Boolean): ChoreoInputs {
        val recipe = Recipes.byId(recipeId)
        val bpm = when (recipeId) {
            RecipeId.F7 -> 92.0
            RecipeId.S5 -> 84.0
            else -> 124.0
        }
        val bars = when {
            recipeId == RecipeId.F7 -> 4
            recipeId == RecipeId.F3 -> 24
            else -> 16
        }
        val p = plan(
            outBpm = bpm,
            inBpm = if (recipeId == RecipeId.S5) 128.0 else bpm,
            bars = bars,
            outLoops = recipe.requiresOutLoop,
            beatMatched = recipeId != RecipeId.S5,
        )
        val clock = LoopMix.Clock(p)
        val dropBeatIdx = (bars * 4 - 4).coerceAtLeast(8)
        val dropTrackTime = p.inn.loopStart + clock.beatTimes[dropBeatIdx]
        val outDropTime = p.out.loopStart + clock.beatTimes[dropBeatIdx]
        val outStruct = if (recipe.requiresOutDrop) {
            StructureView(emptyList(), listOf(outDropTime), emptyList(), emptyList(), emptyList(), 0.85, "test")
        } else {
            StructureView.EMPTY
        }
        val inStruct = if (recipe.requiresDrops || recipeId == RecipeId.F1) {
            StructureView(emptyList(), listOf(dropTrackTime), emptyList(), emptyList(), emptyList(), 0.85, "test")
        } else {
            StructureView.EMPTY
        }
        val outVocal = if (recipeId == RecipeId.C3 || recipeId == RecipeId.F4) 0.75 else 0.3
        val inVocal = if (recipeId == RecipeId.C3 || recipeId == RecipeId.F4) 0.1 else 0.4
        return ChoreoInputs(
            plan = p,
            clock = clock,
            out = track("out-${recipeId.code}", bpm, vocal = outVocal, beatConfidence = if (recipeId == RecipeId.S5) 0.35 else 0.9),
            inn = track("in-${recipeId.code}", if (recipeId == RecipeId.S5) 128.0 else bpm, vocal = inVocal, beatConfidence = if (recipeId == RecipeId.S5) 0.35 else 0.9),
            outStructure = outStruct,
            inStructure = inStruct,
            outStems = if (withStems) stems(vocal = outVocal) else null,
            inStems = if (withStems) stems(vocal = inVocal) else null,
            caps = ChoreoCaps.ALL,
            level = recipe.minLevel,
            pairKey = "test-${recipeId.code}",
        )
    }

    @Test
    fun all20RecipesBuildValidScriptsWithAndWithoutStems() {
        assertEquals(20, Recipes.ALL.size)
        for (id in RecipeId.values()) {
            for (withStems in listOf(true, false)) {
                val inp = buildInputsForRecipe(id, withStems = withStems)
                val log = ArrayList<String>()
                val script = assertNotNull(
                    Choreographer.chooseRecipe(inp, forced = id, log = log),
                    "Recipe ${id.code} (${id.displayName}) failed withStems=$withStems: ${log.joinToString("; ")}",
                )
                assertEquals(id.code, script.recipeId, "Wrong recipeId for ${id.code}")
                assertEquals(id.displayName, script.recipeName)
                assertEquals(id.minLevel, script.level)
                assertTrue(script.fxCount <= id.minLevel.maxFx, "${id.code} fx=${script.fxCount} > max=${id.minLevel.maxFx}")
                if (id.minLevel == DjLevel.FESTIVAL) {
                    assertTrue(script.fxCount in 2..3, "${id.code} Festival fx=${script.fxCount} must be 2..3 (withStems=$withStems)")
                }
                if (withStems && (id == RecipeId.C1 || id == RecipeId.F3 || id == RecipeId.C3 || id == RecipeId.F4)) {
                    assertTrue(script.outUsesStems || script.inUsesStems, "${id.code} should use stems when provided")
                }
                if (!withStems) {
                    assertFalse(script.outUsesStems || script.inUsesStems, "${id.code} must not rely on stems when absent")
                }
            }
        }
    }

    @Test
    fun strictLevelScopingNeverDowngrades() {
        val danceOut = track("d1", 124.0, vocal = 0.2)
        val danceIn = track("d2", 124.0, vocal = 0.2)
        val p = plan(124.0, bars = 16)
        val clock = LoopMix.Clock(p)

        for (level in DjLevel.values()) {
            val ranked = Choreographer.rankRecipes(
                ChoreoInputs(
                    plan = p, clock = clock, out = danceOut, inn = danceIn,
                    outStems = stems(), inStems = stems(), caps = ChoreoCaps.ALL, level = level,
                ),
            )
            assertTrue(ranked.isNotEmpty(), "Expected candidates for $level")
            assertTrue(ranked.all { it.recipeId.minLevel == level }, "$level leaked recipes from another level: ${ranked.map { it.recipeId }}")
        }

        // Festival fallback ladder: low beat confidence + 30% tempo jump + no stems + zero v2 caps
        // must STILL stay in Festival (F2/F5), never dropping to Subtle/Club.
        val badPlan = plan(88.0, 128.0, bars = 4, beatMatched = false)
        val badOut = track("b1", 88.0, key = "C major", beatConfidence = 0.2, vocal = 0.8)
        val badIn = track("b2", 128.0, key = "F# major", beatConfidence = 0.2, vocal = 0.8)
        val festFallback1 = assertNotNull(
            Choreographer.chooseRecipe(
                ChoreoInputs(
                    plan = badPlan, clock = LoopMix.Clock(badPlan),
                    out = badOut, inn = badIn,
                    caps = 0, level = DjLevel.FESTIVAL, pairKey = "bad1",
                ),
            ),
        )
        assertEquals(DjLevel.FESTIVAL, festFallback1.level)
        assertTrue(festFallback1.recipeId in listOf("F2", "F5"), "Expected F2 or F5 fallback, got ${festFallback1.recipeId}")
        assertTrue(festFallback1.fxCount >= 2, "Festival fallback should preserve >= 2 vc80 FX, got ${festFallback1.fxCount}")

        // Second consecutive fallback transition alternates F2 <-> F5 without repeating back-to-back.
        val prevId = RecipeId.fromCode(festFallback1.recipeId)!!
        val festFallback2 = assertNotNull(
            Choreographer.chooseRecipe(
                ChoreoInputs(
                    plan = badPlan, clock = LoopMix.Clock(badPlan),
                    out = badOut, inn = badIn,
                    caps = 0, level = DjLevel.FESTIVAL, pairKey = "bad2",
                    recipeHistory = listOf(prevId),
                ),
            ),
        )
        assertEquals(DjLevel.FESTIVAL, festFallback2.level)
        assertTrue(festFallback2.recipeId in listOf("F2", "F5"))
        assertNotEquals(festFallback1.recipeId, festFallback2.recipeId)
    }

    @Test
    fun zeroBackToBackRepeatsAcross30TransitionsPerLevelAndFxBudgetsHold() {
        val dancePlan = plan(126.0, bars = 16)
        val danceClock = LoopMix.Clock(dancePlan)
        for (level in DjLevel.values()) {
            val history = ArrayList<RecipeId>()
            var prev: String? = null
            var festMultiFxCount = 0
            for (step in 0 until 30) {
                val out = track("t_${level}_$step", 126.0, vocal = 0.25)
                val inn = track("t_${level}_${step + 1}", 126.0, vocal = 0.25)
                val script = assertNotNull(
                    Choreographer.chooseRecipe(
                        ChoreoInputs(
                            plan = dancePlan, clock = danceClock,
                            out = out, inn = inn,
                            outStems = stems(vocal = 0.2), inStems = stems(vocal = 0.2),
                            caps = ChoreoCaps.ALL, level = level,
                            pairKey = "p$step->p${step + 1}",
                            recipeHistory = history.toList(),
                        ),
                    ),
                    "No script at $level step $step",
                )
                assertEquals(level, script.level)
                assertTrue(RecipeId.fromCode(script.recipeId)!!.minLevel == level)
                if (prev != null) {
                    assertNotEquals(prev, script.recipeId, "$level repeated ${script.recipeId} back-to-back at step $step")
                }
                if (level == DjLevel.SUBTLE) {
                    assertTrue(script.fxCount <= 1, "Subtle exceeded 1 FX: ${script.fxCount}")
                } else if (level == DjLevel.CLUB) {
                    assertTrue(script.fxCount <= 2, "Club exceeded 2 FX: ${script.fxCount}")
                } else {
                    assertTrue(script.fxCount in 2..3, "Festival must layer 2-3 FX: ${script.fxCount}")
                    if (script.fxCount >= 2) festMultiFxCount++
                }
                assertTrue(script.topCandidates.size in 1..3, "Expected 1..3 top candidates, got ${script.topCandidates.size}")
                prev = script.recipeId
                history += RecipeId.fromCode(script.recipeId)!!
            }
            if (level == DjLevel.FESTIVAL) {
                assertEquals(30, festMultiFxCount, "100% of Festival transitions should have >= 2 FX")
            }
        }
    }

    @Test
    fun festivalDropExemptionAndActiveFxNamesAndRockReverb() {
        val p = plan(128.0, bars = 16)
        val clock = LoopMix.Clock(p)
        val dropBeatIdx = 60
        val dropTime = p.inn.loopStart + clock.beatTimes[dropBeatIdx]
        val f1 = assertNotNull(
            Choreographer.chooseRecipe(
                ChoreoInputs(
                    plan = p, clock = clock,
                    out = track("d1", 128.0, vocal = 0.2),
                    inn = track("d2", 128.0, vocal = 0.2),
                    inStructure = StructureView(emptyList(), listOf(dropTime), emptyList(), emptyList(), emptyList(), 0.9, "test"),
                    outStems = stems(), inStems = stems(),
                    caps = ChoreoCaps.ALL, level = DjLevel.FESTIVAL,
                ),
                forced = RecipeId.F1,
            ),
        )
        assertEquals("F1", f1.recipeId)
        assertEquals("Riser drop", f1.label)
        assertEquals(1.5f, f1.dropBumpLu, 1e-6f)
        assertTrue(f1.dropExemptEndSec > f1.dropExemptStartSec)
        assertEquals(f1.swapAt, f1.dropExemptEndSec, 1e-6)
        assertTrue(f1.outFx.activeFxNames.containsAll(listOf("Echo", "Gate", "Riser")))

        // Spinback (F7) exposes brakeSpinback and activeFxNames "Spinback"
        val hipPlan = plan(92.0, bars = 4)
        val f7 = assertNotNull(
            Choreographer.chooseRecipe(
                ChoreoInputs(
                    plan = hipPlan, clock = LoopMix.Clock(hipPlan),
                    out = track("h1", 92.0, vocal = 0.8),
                    inn = track("h2", 92.0, vocal = 0.8),
                    caps = ChoreoCaps.ALL, level = DjLevel.FESTIVAL,
                ),
                forced = RecipeId.F7,
            ),
        )
        assertTrue(f7.outFx.brakeSpinback)
        assertTrue("Spinback" in f7.outFx.activeFxNames)
        assertTrue("Echo" in f7.outFx.activeFxNames)

        // Rock/Ballad pairs classify and boost reverb-heavy F2/F5 settings.
        val rockPlan = plan(110.0, bars = 8)
        val rockOut = track("r1", 110.0, beatConfidence = 0.45, vocal = 0.35)
        val rockIn = track("r2", 110.0, beatConfidence = 0.45, vocal = 0.35)
        val rockCtx = PairContext(
            ChoreoInputs(
                plan = rockPlan, clock = LoopMix.Clock(rockPlan),
                out = rockOut, inn = rockIn, caps = ChoreoCaps.ALL, level = DjLevel.FESTIVAL,
            ),
        )
        assertEquals(GenreClass.ROCK, rockCtx.genre)
        val f5Rock = assertNotNull(
            Choreographer.chooseRecipe(rockCtx.inputs, forced = RecipeId.F5),
        )
        assertTrue(f5Rock.outFx.reverbWet >= 0.6f, "Rock/ballad F5 should use reverb-heavy wet, got ${f5Rock.outFx.reverbWet}")
    }

    @Test
    fun djLevelPropertiesAndCapabilitiesMatchSpec() {
        assertEquals(0.03, DjLevel.SUBTLE.maxStretch, 1e-9)
        assertEquals(1, DjLevel.SUBTLE.maxFx)
        assertEquals(0.6f, DjLevel.SUBTLE.fxDepth, 1e-6f)
        assertEquals(0.0, DjLevel.SUBTLE.maxEntrySkipSec, 1e-9)

        assertEquals(0.06, DjLevel.CLUB.maxStretch, 1e-9)
        assertEquals(2, DjLevel.CLUB.maxFx)
        assertEquals(0.85f, DjLevel.CLUB.fxDepth, 1e-6f)
        assertEquals(32.0, DjLevel.CLUB.maxEntrySkipSec, 1e-9)

        assertEquals(0.08, DjLevel.FESTIVAL.maxStretch, 1e-9)
        assertEquals(3, DjLevel.FESTIVAL.maxFx)
        assertEquals(1.0f, DjLevel.FESTIVAL.fxDepth, 1e-6f)
        assertEquals(64.0, DjLevel.FESTIVAL.maxEntrySkipSec, 1e-9)

        assertEquals(1 shl 5, ChoreoCaps.STUTTER)
        assertEquals(1 shl 9, ChoreoCaps.SPINBACK)
    }

    @Test
    fun softPenaltyInLastThreeTransitionsLetsCompetingRecipeWin() {
        val p = plan(126.0, bars = 16)
        val clock = LoopMix.Clock(p)
        val out = track("pop1", 104.0, vocal = 0.25)
        val inn = track("pop2", 104.0, vocal = 0.25)
        val baseInputs = ChoreoInputs(
            plan = p, clock = clock, out = out, inn = inn,
            outStems = stems(vocal = 0.25), inStems = stems(vocal = 0.25),
            caps = ChoreoCaps.ALL, level = DjLevel.SUBTLE,
            pairKey = "soft-penalty-pair",
        )
        val baseline = Choreographer.rankRecipes(baseInputs)
        assertTrue(baseline.size >= 3)
        val top1 = baseline[0]
        val top2 = baseline[1]
        // Put top1 in the 3-back slot (not immediate last, so not hard-skipped) -> -0.12 penalty applies.
        val penalized = Choreographer.rankRecipes(
            ChoreoInputs(
                plan = p, clock = clock, out = out, inn = inn,
                outStems = stems(vocal = 0.25), inStems = stems(vocal = 0.25),
                caps = ChoreoCaps.ALL, level = DjLevel.SUBTLE,
                pairKey = "soft-penalty-pair",
                recipeHistory = listOf(top1.recipeId, baseline[2].recipeId),
            ),
        )
        val top1After = penalized.first { it.recipeId == top1.recipeId }
        assertEquals(PairScorer.REPEAT_RECENT_3, top1After.score.variety, 1e-9)
        if (top1.total - top2.total < PairScorer.REPEAT_RECENT_3 - 1e-6) {
            assertEquals(top2.recipeId, penalized.first().recipeId)
        }
    }
}
