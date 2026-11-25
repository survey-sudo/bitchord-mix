package com.music.bitchord.playback.djmix

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.PhraseBoundaryV3
import com.music.bitchord.djmix.core.contracts.SectionType
import com.music.bitchord.djmix.core.contracts.SongStructureV3
import com.music.bitchord.djmix.core.contracts.StructureSectionV3
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import com.music.bitchord.djmix.core.learning.TasteModel
import com.music.bitchord.djmix.core.learning.TasteSignal
import com.music.bitchord.djmix.core.learning.TasteSnapshot
import com.music.bitchord.djmix.core.planner.BuiltInRecipeCatalog
import com.music.bitchord.djmix.core.planner.PairPlanner
import com.music.bitchord.djmix.core.planner.PlannerRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B8 pure-model and real PairPlanner regressions. A negative observation is one
 * signal, mapped to a -0.05 bounded weight delta; the three-play bonus is +0.20.
 *
 * These tests deliberately need no Android runtime. Event classification (the
 * executor's inclusive 15-second window / outgoing seek) and SQLite deletion
 * must be verified separately; a preference-map round trip is not a disk test.
 */
class TasteModelDeterminismTest {

    @Test
    fun everySignalHasItsSpecifiedDelta() {
        val expected = mapOf(
            TasteSignal.ExplicitThumbsUp to 0.05,
            TasteSignal.ExplicitThumbsDown to -0.05,
            TasteSignal.EarlySkipWithin15s to -0.05,
            TasteSignal.SeekBackDuringMix to -0.05,
            TasteSignal.ThreeConsecutivePlaythroughs to 0.20,
        )
        assertEquals(TasteSignal.entries.toSet(), expected.keys)
        for ((signal, delta) in expected) {
            val model = TasteModel()
            assertEquals(delta, TasteModel.deltaFor(signal), 0.0)
            model.applySignal(signal, "S1", "DANCE")
            assertEquals(delta, model.recipeWeights().getValue("S1"), 0.0)
            assertEquals(delta, model.genreWeights().getValue("S1:DANCE"), 0.0)
            assertEquals((delta * 2.0).coerceIn(-0.3, 0.3), model.adjustmentFor("S1", "DANCE"), EPS)
        }
    }

    @Test
    fun explicitThumbsUpdateBothScopesAndOppositeRatingsCancel() {
        val model = TasteModel()
        model.applySignal(TasteSignal.ExplicitThumbsUp, "S1", "DANCE")
        assertEquals(0.05, model.adjustmentFor("S1"), 0.0)
        assertEquals(0.10, model.adjustmentFor("S1", "DANCE"), 0.0)
        assertEquals(0.05, model.adjustmentFor("S1", "ROCK"), 0.0)
        assertEquals(0.0, model.adjustmentFor("S2", "DANCE"), 0.0)

        model.applySignal(TasteSignal.ExplicitThumbsDown, "S1", "DANCE")
        assertEquals(0.0, model.recipeWeights().getValue("S1"), 0.0)
        assertEquals(0.0, model.genreWeights().getValue("S1:DANCE"), 0.0)
        assertEquals(0.0, model.adjustmentFor("S1", "DANCE"), 0.0)
    }

    @Test
    fun earlySkipAndSeekBackEachApplyOneNegativeObservation() {
        for (signal in listOf(TasteSignal.EarlySkipWithin15s, TasteSignal.SeekBackDuringMix)) {
            val model = TasteModel()
            model.noteFullPlaythrough("S1")
            model.noteFullPlaythrough("S1")
            model.applySignal(signal, "S1", "DANCE")
            assertEquals(-0.05, model.recipeWeights().getValue("S1"), 0.0)
            assertEquals(-0.05, model.genreWeights().getValue("S1:DANCE"), 0.0)
            assertEquals(0, model.consecutivePlaythroughs("S1"))
            assertFalse(model.noteFullPlaythrough("S1"))
            assertEquals(-0.05, model.adjustmentFor("S1"), 0.0)
        }
    }

    @Test
    fun threeFullPlaythroughsAwardExactlyOneBonusAndRestartTheStreak() {
        val model = TasteModel()
        repeat(2) { index ->
            assertFalse(model.noteFullPlaythrough("S1", "DANCE"))
            assertEquals(index + 1, model.consecutivePlaythroughs("S1"))
            assertEquals(0.0, model.adjustmentFor("S1"), 0.0)
        }
        assertTrue(model.noteFullPlaythrough("S1", "DANCE"))
        assertEquals(0, model.consecutivePlaythroughs("S1"))
        assertEquals(0.20, model.recipeWeights().getValue("S1"), 0.0)
        assertEquals(0.20, model.genreWeights().getValue("S1:DANCE"), 0.0)
        assertEquals(0.30, model.adjustmentFor("S1", "DANCE"), 0.0)
        assertFalse(model.noteFullPlaythrough("S1", "DANCE"))
        assertFalse(model.noteFullPlaythrough("S1", "DANCE"))
        assertEquals(0.20, model.adjustmentFor("S1"), 0.0)
        assertTrue(model.noteFullPlaythrough("S1", "DANCE"))
        assertEquals(0.30, model.recipeWeights().getValue("S1"), 0.0)
        assertEquals(0, model.consecutivePlaythroughs("S1"))
    }

    @Test
    fun playthroughStreaksAreIndependentAndPositiveFeedbackPreservesProgress() {
        val model = TasteModel()
        model.noteFullPlaythrough("S1")
        model.noteFullPlaythrough("S2")
        model.noteFullPlaythrough("S1")
        model.applySignal(TasteSignal.ExplicitThumbsUp, "S1")
        assertEquals(2, model.consecutivePlaythroughs("S1"))
        assertEquals(1, model.consecutivePlaythroughs("S2"))
        assertTrue(model.noteFullPlaythrough("S1"))
        assertEquals(0.25, model.adjustmentFor("S1"), EPS)
        assertEquals(1, model.consecutivePlaythroughs("S2"))
        assertEquals(0.0, model.adjustmentFor("S2"), 0.0)
    }

    @Test
    fun everyNegativeSignalBreaksOnlyItsRecipeStreak() {
        for (signal in listOf(
            TasteSignal.ExplicitThumbsDown,
            TasteSignal.EarlySkipWithin15s,
            TasteSignal.SeekBackDuringMix,
        )) {
            val model = TasteModel()
            repeat(2) { model.noteFullPlaythrough("S1"); model.noteFullPlaythrough("S2") }
            model.applySignal(signal, "S1")
            assertEquals(0, model.consecutivePlaythroughs("S1"))
            assertEquals(2, model.consecutivePlaythroughs("S2"))
            assertTrue(model.noteFullPlaythrough("S2"))
            assertFalse(model.noteFullPlaythrough("S1"))
            assertFalse(model.noteFullPlaythrough("S1"))
            assertTrue(model.noteFullPlaythrough("S1"))
            assertEquals(0.15, model.adjustmentFor("S1"), EPS)
        }
    }

    @Test
    fun bothScopesAndTheirSumStayBoundedAfterRepeatedSignalsInEitherDirection() {
        for ((signal, expected) in listOf(
            TasteSignal.ExplicitThumbsUp to 0.3,
            TasteSignal.ExplicitThumbsDown to -0.3,
            TasteSignal.ThreeConsecutivePlaythroughs to 0.3,
        )) {
            val model = TasteModel()
            repeat(100) {
                model.applySignal(signal, "S1", "DANCE")
                assertTrue(model.recipeWeights().values.all { it in -0.3..0.3 })
                assertTrue(model.genreWeights().values.all { it in -0.3..0.3 })
                assertTrue(model.adjustmentFor("S1", "DANCE") in -0.3..0.3)
            }
            assertEquals(expected, model.recipeWeights().getValue("S1"), 0.0)
            assertEquals(expected, model.genreWeights().getValue("S1:DANCE"), 0.0)
            assertEquals(expected, model.adjustmentFor("S1", "DANCE"), 0.0)
        }
    }

    @Test
    fun saturatedWeightsRespondImmediatelyWhenFeedbackReverses() {
        val model = TasteModel()
        repeat(100) { model.applySignal(TasteSignal.ExplicitThumbsUp, "S1", "DANCE") }
        model.applySignal(TasteSignal.ExplicitThumbsDown, "S1", "DANCE")
        assertEquals(0.25, model.recipeWeights().getValue("S1"), EPS)
        assertEquals(0.25, model.genreWeights().getValue("S1:DANCE"), EPS)
        repeat(100) { model.applySignal(TasteSignal.ExplicitThumbsDown, "S1", "DANCE") }
        model.applySignal(TasteSignal.ExplicitThumbsUp, "S1", "DANCE")
        assertEquals(-0.25, model.recipeWeights().getValue("S1"), EPS)
        assertEquals(-0.25, model.genreWeights().getValue("S1:DANCE"), EPS)
    }

    @Test
    fun blankRecipesAreIgnoredAndRecipeAndGenreInputsAreNormalized() {
        val model = TasteModel()
        for (signal in TasteSignal.entries) model.applySignal(signal, " \t", "DANCE")
        assertFalse(model.noteFullPlaythrough("  ", "DANCE"))
        assertEquals(TasteSnapshot(), model.snapshot())
        model.applySignal(TasteSignal.ExplicitThumbsUp, " S1 ", " dance ")
        model.applySignal(TasteSignal.ExplicitThumbsUp, "S2", " \t")
        assertEquals(mapOf("S1" to 0.05, "S2" to 0.05), model.recipeWeights())
        assertEquals(mapOf("S1:DANCE" to 0.05), model.genreWeights())
        assertEquals(0.10, model.adjustmentFor(" S1 ", "dance"), 0.0)
        assertEquals(0.0, model.adjustmentFor("  ", "DANCE"), 0.0)
    }

    @Test
    fun disabledLearningFreezesExistingWeightsAndStreaksAndReturnsExactZero() {
        val model = TasteModel()
        model.applySignal(TasteSignal.ExplicitThumbsDown, "S1", "DANCE")
        repeat(2) { model.noteFullPlaythrough("S1") }
        model.learningEnabled = false
        val frozen = model.snapshot()
        for (signal in TasteSignal.entries) model.applySignal(signal, "S1", "DANCE")
        repeat(6) { assertFalse(model.noteFullPlaythrough("S1", "DANCE")) }
        assertEquals(frozen, model.snapshot())
        assertEquals(0.0.toBits(), model.adjustmentFor("S1", "DANCE").toBits())
        assertTrue(model.plannerWeights("DANCE").isEmpty())
        model.learningEnabled = true
        assertEquals(-0.10, model.adjustmentFor("S1", "DANCE"), EPS)
        assertTrue(model.noteFullPlaythrough("S1"))
        assertEquals(0.15, model.adjustmentFor("S1"), EPS)
    }

    @Test
    fun learningDisabledFromConstructionNeverAccumulatesEvidence() {
        val model = TasteModel(learningEnabled = false)
        for (signal in TasteSignal.entries) model.applySignal(signal, "S1", "DANCE")
        repeat(3) { assertFalse(model.noteFullPlaythrough("S1")) }
        assertEquals(TasteSnapshot(learningEnabled = false), model.snapshot())
        model.learningEnabled = true
        assertTrue(model.toPreferenceMap().isEmpty())
        assertFalse(model.noteFullPlaythrough("S1"))
    }

    @Test
    fun resetClearsMemoryPreferenceExportAndStreaksAndLearningCanRestart() {
        val model = TasteModel()
        model.applySignal(TasteSignal.ExplicitThumbsUp, "S1", "DANCE")
        model.applySignal(TasteSignal.ExplicitThumbsDown, "S2", "ROCK")
        repeat(2) { model.noteFullPlaythrough("S1") }
        model.reset()
        assertEquals(TasteSnapshot(), model.snapshot())
        assertTrue(model.toPreferenceMap().isEmpty())
        assertTrue(model.plannerWeights("DANCE").isEmpty())
        assertEquals(0.0.toBits(), model.adjustmentFor("S1", "DANCE").toBits())
        assertEquals(0.0.toBits(), model.adjustmentFor("S2", "ROCK").toBits())
        assertFalse(model.noteFullPlaythrough("S1"))
        assertFalse(model.noteFullPlaythrough("S1"))
        assertTrue(model.noteFullPlaythrough("S1"))
        assertEquals(0.20, model.adjustmentFor("S1"), 0.0)
    }

    @Test
    fun resetPreservesDisabledLearningState() {
        val model = TasteModel()
        model.applySignal(TasteSignal.ExplicitThumbsUp, "S1")
        model.learningEnabled = false
        model.reset()
        assertEquals(TasteSnapshot(learningEnabled = false), model.snapshot())
        model.learningEnabled = true
        assertTrue(model.toPreferenceMap().isEmpty())
    }

    @Test
    fun preferenceMapRoundTripPreservesWeightsButDoesNotPersistStreaks() {
        val model = TasteModel()
        model.applySignal(TasteSignal.ExplicitThumbsUp, "S2", "ROCK")
        model.applySignal(TasteSignal.ExplicitThumbsDown, "S1", "DANCE")
        repeat(2) { model.noteFullPlaythrough("S1") }
        val stored = model.toPreferenceMap()
        assertEquals(listOf("S1", "S1:DANCE", "S2", "S2:ROCK"), stored.keys.toList())
        val restored = TasteModel()
        restored.restoreFromPreferenceMap(stored)
        assertEquals(model.recipeWeights(), restored.recipeWeights())
        assertEquals(model.genreWeights(), restored.genreWeights())
        assertEquals(model.plannerWeights("DANCE"), restored.plannerWeights("DANCE"))
        assertEquals(0, restored.consecutivePlaythroughs("S1"))
    }

    @Test
    fun preferenceRestoreReplacesOldStateSkipsMarkersAndSanitizesUnsafeWeights() {
        val model = TasteModel()
        model.applySignal(TasteSignal.ExplicitThumbsUp, "old", "ROCK")
        model.noteFullPlaythrough("old")
        model.restoreFromPreferenceMap(mapOf(
            "__legacy_migration_v1" to 1.0,
            "S1" to 2.0,
            "S1:DANCE" to -2.0,
            "S2" to Double.NaN,
            "S3" to Double.POSITIVE_INFINITY,
            "S4:DANCE" to Double.NEGATIVE_INFINITY,
            " " to 0.1,
        ))
        assertEquals(mapOf("S1" to 0.3, "S2" to 0.0, "S3" to 0.0), model.recipeWeights())
        assertEquals(mapOf("S1:DANCE" to -0.3, "S4:DANCE" to 0.0), model.genreWeights())
        assertTrue(model.snapshot().playthroughStreaks.isEmpty())
        assertEquals(0.0, model.adjustmentFor("S1", "DANCE"), 0.0)
        assertEquals(listOf("S1", "S2", "S3", "S4"), model.plannerWeights("DANCE").keys.toList())
    }

    @Test
    fun snapshotRestoreClampsWeightsAndRestoresLearningAndValidStreaks() {
        val model = TasteModel()
        model.restore(TasteSnapshot(
            recipeWeights = mapOf("S1" to 5.0, "S2" to Double.NaN, " " to 0.1),
            genreWeights = mapOf("S1:DANCE" to -5.0, "S2:ROCK" to Double.POSITIVE_INFINITY),
            playthroughStreaks = mapOf("S1" to 2, "S2" to -1, " " to 1),
            learningEnabled = false,
        ))
        assertFalse(model.learningEnabled)
        assertEquals(mapOf("S1" to 0.3, "S2" to 0.0), model.recipeWeights())
        assertEquals(mapOf("S1:DANCE" to -0.3, "S2:ROCK" to 0.0), model.genreWeights())
        assertEquals(mapOf("S1" to 2), model.snapshot().playthroughStreaks)
        assertEquals(0.0, model.adjustmentFor("S1"), 0.0)
        model.learningEnabled = true
        assertTrue(model.noteFullPlaythrough("S1"))
    }

    @Test
    fun previouslyExportedViewsAndSnapshotsDoNotChangeWhenTheModelMutates() {
        val model = TasteModel()
        model.applySignal(TasteSignal.ExplicitThumbsUp, "S1", "DANCE")
        model.noteFullPlaythrough("S1")
        val recipes = model.recipeWeights()
        val genres = model.genreWeights()
        val preferences = model.toPreferenceMap()
        val snapshot = model.snapshot()
        model.applySignal(TasteSignal.ExplicitThumbsDown, "S1", "DANCE")
        model.reset()
        assertEquals(mapOf("S1" to 0.05), recipes)
        assertEquals(mapOf("S1:DANCE" to 0.05), genres)
        assertEquals(mapOf("S1" to 0.05, "S1:DANCE" to 0.05), preferences)
        assertEquals(mapOf("S1" to 1), snapshot.playthroughStreaks)
        val restored = TasteModel()
        restored.restore(snapshot)
        assertEquals(snapshot, restored.snapshot())
    }

    @Test
    fun identicalSignalHistoriesProduceBitIdenticalPlannerWeights() {
        fun replay() = TasteModel().apply {
            repeat(12) { index ->
                applySignal(TasteSignal.ExplicitThumbsUp, "S2", "ROCK")
                applySignal(TasteSignal.ExplicitThumbsDown, "S1", "DANCE")
                applySignal(TasteSignal.SeekBackDuringMix, "S2", "ROCK")
                if (index % 2 == 0) noteFullPlaythrough("S1", "DANCE")
            }
        }
        val first = replay()
        repeat(10) {
            val next = replay()
            assertEquals(first.snapshot(), next.snapshot())
            assertEquals(
                first.plannerWeights("DANCE").mapValues { it.value.toBits() },
                next.plannerWeights("DANCE").mapValues { it.value.toBits() },
            )
        }
    }

    @Test
    fun threeThumbsDownChangeTheRealPairPlannersRecipePreference() {
        val model = TasteModel()
        val baseline = planned(model)
        assertEquals("TASTE_A", baseline.recipeId)
        repeat(3) { model.applySignal(TasteSignal.ExplicitThumbsDown, baseline.recipeId) }
        assertEquals(-0.15, model.adjustmentFor(baseline.recipeId), EPS)
        val learned = planned(model)
        assertEquals("TASTE_B", learned.recipeId)
        assertEquals(baseline.frozenExitMs, learned.frozenExitMs)
        assertEquals(baseline.frozenEntryMs, learned.frozenEntryMs)
        val oldScore = baseline.topScores.single { it.recipeId == "TASTE_A" }.score
        val newScore = learned.topScores.single { it.recipeId == "TASTE_A" }.score
        assertTrue("The disliked recipe must lose score", newScore < oldScore)
        assertEquals(0.0, learned.tasteAdjustment, 0.0)
    }

    @Test
    fun threeThumbsDownAlsoChangePreferenceWithTheUnmodifiedBuiltInCatalog() {
        val model = TasteModel()
        val request = PlannerRequest(
            generation = 1L, outTrack = track("out"), inTrack = track("in"),
            djLevel = DjLevel.SUBTLE, positionMs = 0L,
            outGenus = "DANCE", inGenus = "DANCE",
        )
        fun choose(): TransitionPlanV1 {
            val outcome = PairPlanner.plan(request.copy(tasteWeights = model.plannerWeights()))
            return (outcome as? MixOutcome.Ok<TransitionPlanV1>)?.value
                ?: error("Expected a qualified built-in recipe, got $outcome")
        }
        val baseline = choose()
        repeat(3) { model.applySignal(TasteSignal.ExplicitThumbsDown, baseline.recipeId) }
        val learned = choose()
        assertTrue("Three dislikes must change this pair's recipe", baseline.recipeId != learned.recipeId)
        assertEquals(-0.15, model.recipeWeights().getValue(baseline.recipeId), EPS)
        model.reset()
        assertEquals(baseline, choose())
    }

    @Test
    fun disabledLearningRestoresTheExactBaselinePlanAcrossRepeatedCalls() {
        val model = TasteModel()
        val baseline = planned(model)
        repeat(3) { model.applySignal(TasteSignal.ExplicitThumbsDown, baseline.recipeId) }
        assertEquals("TASTE_B", planned(model).recipeId)
        model.learningEnabled = false
        repeat(10) {
            model.applySignal(TasteSignal.ExplicitThumbsUp, "TASTE_B", "DANCE")
            assertEquals(baseline, planned(model))
        }
        model.learningEnabled = true
        assertEquals("TASTE_B", planned(model).recipeId)
    }

    @Test
    fun resetRestoresTheExactBaselinePlanAndEmptyHydrationCannotResurrectWeights() {
        val model = TasteModel()
        val baseline = planned(model)
        repeat(3) { model.applySignal(TasteSignal.ExplicitThumbsDown, baseline.recipeId, "DANCE") }
        assertEquals("TASTE_B", planned(model).recipeId)
        model.reset()
        repeat(10) { assertEquals(baseline, planned(model)) }
        val rehydrated = TasteModel()
        rehydrated.restoreFromPreferenceMap(model.toPreferenceMap())
        assertEquals(baseline, planned(rehydrated))
    }

    @Test
    fun preferenceHydrationOrderCannotChangeTheLearnedPlan() {
        val model = TasteModel()
        repeat(3) { model.applySignal(TasteSignal.ExplicitThumbsDown, "TASTE_A", "DANCE") }
        model.applySignal(TasteSignal.ExplicitThumbsUp, "TASTE_B", "ROCK")
        val restored = TasteModel()
        restored.restoreFromPreferenceMap(model.toPreferenceMap().entries.reversed().associate { it.toPair() })
        assertEquals(model.plannerWeights("DANCE"), restored.plannerWeights("DANCE"))
        assertEquals(planned(model), planned(restored))
    }

    private fun planned(model: TasteModel): TransitionPlanV1 {
        // Equal-geometry clones isolate taste from recipe-fit/FX/variety differences.
        // PairPlanner still generates and qualifies real candidates and scores them.
        val catalog = listOf(
            BuiltInRecipeCatalog.S1.copy(id = "TASTE_A", displayName = "Taste A"),
            BuiltInRecipeCatalog.S1.copy(id = "TASTE_B", displayName = "Taste B"),
        )
        val outcome = PairPlanner.plan(
            PlannerRequest(
                generation = 1L,
                outTrack = track("out"),
                inTrack = track("in"),
                djLevel = DjLevel.SUBTLE,
                positionMs = 0L,
                outGenus = "DANCE",
                inGenus = "DANCE",
                tasteWeights = model.plannerWeights("DANCE"),
            ),
            catalog,
        )
        return (outcome as? MixOutcome.Ok<TransitionPlanV1>)?.value
            ?: error("Expected a qualified transition, got $outcome")
    }

    private fun track(id: String): TrackAnalysisV3 = TrackAnalysisV3(
        trackId = id,
        durationMs = 120_000L,
        analysisLevel = AnalysisLevel.FULL,
        bpm = 120.0,
        gridConfidence = 0.9f,
        beatsMs = (0L until 120_000L step 500L).toList(),
        downbeatsMs = (0L until 120_000L step 2_000L).toList(),
        keyCamelot = "8A",
        keyConfidence = 0.9f,
        vocalPresence50fps = List(6_000) { 0.0f },
        structure = SongStructureV3(
            sections = listOf(
                StructureSectionV3(0L, 40_000L, type = SectionType.INTRO),
                StructureSectionV3(40_000L, 80_000L, type = SectionType.CHORUS),
                StructureSectionV3(80_000L, 120_000L, type = SectionType.OUTRO),
            ),
            phrases = (0L until 120_000L step 16_000L).mapIndexed { index, time ->
                PhraseBoundaryV3(time, index * 8)
            },
        ),
        audibleEndMs = 120_000L,
    )

    private companion object {
        const val EPS = 1e-12
    }
}
