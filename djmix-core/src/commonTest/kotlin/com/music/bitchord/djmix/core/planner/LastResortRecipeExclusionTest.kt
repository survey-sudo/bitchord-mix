package com.music.bitchord.djmix.core.planner

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.PhraseBoundaryV3
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.SectionType
import com.music.bitchord.djmix.core.contracts.SongStructureV3
import com.music.bitchord.djmix.core.contracts.StructureSectionV3
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LastResortRecipeExclusionTest {

    private fun makeTrack(
        id: String,
        bpm: Double = 120.0,
        durationMs: Long = 120_000L,
        gridConf: Float = 0.85f,
        key: String = "8A",
        silentIntroEndMs: Long = 0L,
        audibleEndMs: Long = durationMs,
        vocalFrames: List<Float> = List((durationMs / 20L).toInt()) { 0.05f },
        sections: List<StructureSectionV3> = emptyList(),
    ): TrackAnalysisV3 {
        val beatMs = (60_000.0 / bpm).toLong()
        val barMs = beatMs * 4L
        val beats = (0L until durationMs step beatMs).toList()
        val downs = (0L until durationMs step barMs).toList()
        val phrases = (0L until durationMs step barMs * 8L).mapIndexed { i, t -> PhraseBoundaryV3(t, i * 8) }
        val resolvedSections = sections.ifEmpty {
            listOf(
                StructureSectionV3(0L, durationMs / 3L, type = SectionType.INTRO),
                StructureSectionV3(durationMs / 3L, (durationMs * 2L) / 3L, type = SectionType.CHORUS),
                StructureSectionV3((durationMs * 2L) / 3L, durationMs, type = SectionType.OUTRO),
            )
        }
        return TrackAnalysisV3(
            trackId = id, durationMs = durationMs, analysisLevel = AnalysisLevel.FULL,
            bpm = bpm, gridConfidence = gridConf, beatsMs = beats, downbeatsMs = downs,
            keyCamelot = key, keyConfidence = 0.85f, vocalPresence50fps = vocalFrames,
            structure = SongStructureV3(sections = resolvedSections, phrases = phrases),
            silentIntroEndMs = silentIntroEndMs, audibleEndMs = audibleEndMs,
        )
    }

    private fun requireOk(outcome: MixOutcome<TransitionPlanV1>): TransitionPlanV1 =
        (outcome as? MixOutcome.Ok<TransitionPlanV1>)?.value ?: error("Expected Ok, got $outcome")

    private fun requireRefused(outcome: MixOutcome<TransitionPlanV1>): MixOutcome.Refused =
        (outcome as? MixOutcome.Refused) ?: error("Expected Refused, got $outcome")

    private fun lastResortRequest(
        djLevel: DjLevel,
        excludedRecipeIds: Set<String> = emptySet(),
        generation: Long = 100L,
        positionMs: Long = 99_500L,
    ): PlannerRequest = PlannerRequest(
        generation = generation,
        outTrack = makeTrack("out-lr-$djLevel-${excludedRecipeIds.sorted().joinToString("_")}", bpm = 120.0, durationMs = 120_000L, audibleEndMs = 119_000L),
        inTrack = makeTrack("in-lr-$djLevel-${excludedRecipeIds.sorted().joinToString("_")}", bpm = 120.0, silentIntroEndMs = 2_000L),
        djLevel = djLevel,
        positionMs = positionMs,
        excludedRecipeIds = excludedRecipeIds,
    )

    @Test
    fun allPreferredExcludedReturnsTypedRefusalAndNeverRevivesS4_subtle() {
        val outcome = PairPlanner.planLastResortMusicalExit(
            lastResortRequest(DjLevel.SUBTLE, excludedRecipeIds = setOf("S4", "S5")),
        )
        val refused = requireRefused(outcome)
        assertEquals(RefusalReason.CHOREOGRAPHY_UNAVAILABLE, refused.reason)
        assertTrue(refused.detail.contains("No allowed last-resort recipe"), "detail=${refused.detail}")
        assertTrue(outcome !is MixOutcome.Ok || (outcome as MixOutcome.Ok<TransitionPlanV1>).value.recipeId != "S4")
    }

    @Test
    fun allPreferredExcludedReturnsTypedRefusalAndNeverRevivesS4_club() {
        val outcome = PairPlanner.planLastResortMusicalExit(
            lastResortRequest(DjLevel.CLUB, excludedRecipeIds = setOf("C2", "S4", "S5")),
        )
        val refused = requireRefused(outcome)
        assertEquals(RefusalReason.CHOREOGRAPHY_UNAVAILABLE, refused.reason)
        assertTrue(refused.detail.contains("No allowed last-resort recipe"), "detail=${refused.detail}")
        assertTrue(outcome !is MixOutcome.Ok || (outcome as MixOutcome.Ok<TransitionPlanV1>).value.recipeId != "S4")
    }

    @Test
    fun allPreferredExcludedReturnsTypedRefusalAndNeverRevivesS4_festival() {
        val outcome = PairPlanner.planLastResortMusicalExit(
            lastResortRequest(DjLevel.FESTIVAL, excludedRecipeIds = setOf("C2", "S4", "S5")),
        )
        val refused = requireRefused(outcome)
        assertEquals(RefusalReason.CHOREOGRAPHY_UNAVAILABLE, refused.reason)
        assertTrue(refused.detail.contains("No allowed last-resort recipe"), "detail=${refused.detail}")
        assertTrue(outcome !is MixOutcome.Ok || (outcome as MixOutcome.Ok<TransitionPlanV1>).value.recipeId != "S4")
    }

    @Test
    fun excludedC2WhileS4AllowedKeepsS4_club() {
        val plan = requireOk(
            PairPlanner.planLastResortMusicalExit(
                lastResortRequest(DjLevel.CLUB, excludedRecipeIds = setOf("C2")),
            ),
        )
        assertEquals("S4", plan.recipeId)
        assertTrue(plan.isLastResort)
    }

    @Test
    fun excludedC2WhileS4AllowedKeepsS4_festival() {
        val plan = requireOk(
            PairPlanner.planLastResortMusicalExit(
                lastResortRequest(DjLevel.FESTIVAL, excludedRecipeIds = setOf("C2")),
            ),
        )
        assertEquals("S4", plan.recipeId)
        assertTrue(plan.isLastResort)
    }

    @Test
    fun excludedS4ChoosesRemainingAllowedS5_subtle() {
        val plan = requireOk(
            PairPlanner.planLastResortMusicalExit(
                lastResortRequest(DjLevel.SUBTLE, excludedRecipeIds = setOf("S4")),
            ),
        )
        assertEquals("S5", plan.recipeId)
        assertTrue(plan.isLastResort)
    }

    @Test
    fun excludedS4ChoosesRemainingAllowedC2_clubAndFestival() {
        val club = requireOk(
            PairPlanner.planLastResortMusicalExit(
                lastResortRequest(DjLevel.CLUB, excludedRecipeIds = setOf("S4")),
            ),
        )
        assertEquals("C2", club.recipeId)

        val festival = requireOk(
            PairPlanner.planLastResortMusicalExit(
                lastResortRequest(DjLevel.FESTIVAL, excludedRecipeIds = setOf("S4")),
            ),
        )
        assertEquals("C2", festival.recipeId)
    }

    @Test
    fun emptyExclusionsPreservesOriginalFirstChoice() {
        val subtle = requireOk(PairPlanner.planLastResortMusicalExit(lastResortRequest(DjLevel.SUBTLE, emptySet())))
        assertEquals("S4", subtle.recipeId)

        val club = requireOk(PairPlanner.planLastResortMusicalExit(lastResortRequest(DjLevel.CLUB, emptySet())))
        assertEquals("C2", club.recipeId)

        val festival = requireOk(PairPlanner.planLastResortMusicalExit(lastResortRequest(DjLevel.FESTIVAL, emptySet())))
        assertEquals("C2", festival.recipeId)
    }

    @Test
    fun exactStringIdsAndArbitraryIgnoredStringsRemainExact() {
        val planWithArbitrary = requireOk(
            PairPlanner.planLastResortMusicalExit(
                lastResortRequest(
                    DjLevel.CLUB,
                    excludedRecipeIds = setOf("s4", " c2", "C2 ", "S4X", "random", ""),
                ),
            ),
        )
        assertEquals("C2", planWithArbitrary.recipeId)

        val exactExcluded = requireOk(
            PairPlanner.planLastResortMusicalExit(
                lastResortRequest(
                    DjLevel.CLUB,
                    excludedRecipeIds = setOf("C2", "random", "s4"),
                ),
            ),
        )
        assertEquals("S4", exactExcluded.recipeId)
    }

    @Test
    fun excludedS4WithS5AllowedPicksS5WhenC2AlsoExcluded_clubFestival() {
        val club = requireOk(
            PairPlanner.planLastResortMusicalExit(
                lastResortRequest(DjLevel.CLUB, excludedRecipeIds = setOf("C2", "S4")),
            ),
        )
        assertEquals("S5", club.recipeId)

        val festival = requireOk(
            PairPlanner.planLastResortMusicalExit(
                lastResortRequest(DjLevel.FESTIVAL, excludedRecipeIds = setOf("C2", "S4")),
            ),
        )
        assertEquals("S5", festival.recipeId)
    }
}
