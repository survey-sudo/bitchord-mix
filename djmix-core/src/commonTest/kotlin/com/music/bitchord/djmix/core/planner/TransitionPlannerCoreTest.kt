package com.music.bitchord.djmix.core.planner

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.MixOutcome
import com.music.bitchord.djmix.core.contracts.PhraseBoundaryV3
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.SectionType
import com.music.bitchord.djmix.core.contracts.SongStructureV3
import com.music.bitchord.djmix.core.contracts.StructureSectionV3
import com.music.bitchord.djmix.core.contracts.TimeRangeMs
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.djmix.core.contracts.TransitionPlanV1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransitionPlannerCoreTest {

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

    private fun requireRefused(outcome: MixOutcome<TransitionPlanV1>): RefusalReason =
        (outcome as? MixOutcome.Refused)?.reason ?: error("Expected Refused, got $outcome")

    @Test
    fun midVocalExitOnlyViaAcapellaRecipeAndEntryHardRejects() {
        val loudVocal = List(6_000) { 0.8f }
        // A§4.4 hard-rejects mid-vocal exit for every recipe EXCEPT the deliberate acapella
        // techniques (C3 Acapella ride at CLUB, F4 Acapella mashup at FESTIVAL), which isolate
        // and ride the outgoing vocal by design. Here C3 is the only eligible winner.
        val exitOk = requireOk(
            PairPlanner.plan(
                PlannerRequest(1L, makeTrack("out-v", vocalFrames = loudVocal), makeTrack("in-c"), djLevel = DjLevel.CLUB, positionMs = 0L),
            ),
        )
        assertEquals("C3", exitOk.recipeId)
        assertTrue(exitOk.placementReasons.isNotEmpty())

        // Mid-vocal entry has no exception: hard-refused at SUBTLE and CLUB.
        val outClean = makeTrack("out-c")
        val inVocal = makeTrack("in-v", vocalFrames = loudVocal)
        assertEquals(RefusalReason.ENTRY_MID_VOCAL, requireRefused(PairPlanner.plan(PlannerRequest(2L, outClean, inVocal, djLevel = DjLevel.SUBTLE, positionMs = 0L))))
        assertEquals(RefusalReason.ENTRY_MID_VOCAL, requireRefused(PairPlanner.plan(PlannerRequest(3L, outClean, inVocal, djLevel = DjLevel.CLUB, positionMs = 0L))))
    }

    @Test
    fun blendableFestivalPairPicksMusicalRecipeNeverAbrupt() {
        val plan = requireOk(
            PairPlanner.plan(
                PlannerRequest(
                    generation = 10L, outTrack = makeTrack("out-blend", bpm = 124.0, key = "8A"),
                    inTrack = makeTrack("in-blend", bpm = 126.0, key = "9A"),
                    djLevel = DjLevel.FESTIVAL, positionMs = 10_000L, transitionsSinceLastAbrupt = 20,
                ),
            ),
        )
        assertTrue(plan.recipeId !in setOf("F2", "F7", "F9"), "Expected musical blend, got ${plan.recipeId}")
        assertTrue(plan.topScores.size in 1..3)
        assertTrue(plan.placementReasons.isNotEmpty())
        assertEquals(1.0, CamelotKey.parse("8A")!!.compatibilityScore(CamelotKey.parse("8A")!!))
        assertEquals(0.88, CamelotKey.parse("8A")!!.compatibilityScore(CamelotKey.parse("9A")!!))
        assertEquals(0.65, CamelotKey.parse("8A")!!.compatibilityScore(CamelotKey.parse("10A")!!))
        assertEquals(0.25, CamelotKey.parse("8A")!!.compatibilityScore(CamelotKey.parse("3B")!!))
    }

    @Test
    fun brakeAndSpinbackGatedUnlessFestivalJumpPhraseEndAndCooldownMet() {
        val out = makeTrack("out-j", bpm = 100.0)
        val abruptOnly = listOf(BuiltInRecipeCatalog.F2, BuiltInRecipeCatalog.F7)

        assertEquals(
            RefusalReason.ABRUPT_DISALLOWED,
            requireRefused(PairPlanner.plan(PlannerRequest(1L, out, makeTrack("in-15", bpm = 115.0), djLevel = DjLevel.CLUB, positionMs = 0L, transitionsSinceLastAbrupt = 10), abruptOnly)),
        )
        assertEquals(
            RefusalReason.ABRUPT_DISALLOWED,
            requireRefused(PairPlanner.plan(PlannerRequest(2L, out, makeTrack("in-9", bpm = 109.0), djLevel = DjLevel.FESTIVAL, positionMs = 0L, outGenus = "POP", inGenus = "POP", transitionsSinceLastAbrupt = 10), abruptOnly)),
        )
        assertEquals(
            RefusalReason.ABRUPT_DISALLOWED,
            requireRefused(PairPlanner.plan(PlannerRequest(3L, out, makeTrack("in-15b", bpm = 115.0), djLevel = DjLevel.FESTIVAL, positionMs = 0L, transitionsSinceLastAbrupt = 7), abruptOnly)),
        )

        val midPhraseOut = out.copy(
            structure = SongStructureV3(
                phrases = listOf(PhraseBoundaryV3(0L)),
                sections = listOf(StructureSectionV3(0L, 120_000L, type = SectionType.CHORUS)),
            ),
            downbeatsMs = (0L until 120_000L step 2_400L).toList(),
        )
        assertEquals(
            RefusalReason.ABRUPT_DISALLOWED,
            requireRefused(PairPlanner.plan(PlannerRequest(4L, midPhraseOut, makeTrack("in-15c", bpm = 115.0), djLevel = DjLevel.FESTIVAL, positionMs = 10_000L, transitionsSinceLastAbrupt = 10), abruptOnly)),
        )

        val allowed = requireOk(
            PairPlanner.plan(PlannerRequest(5L, out, makeTrack("in-9g", bpm = 109.0), djLevel = DjLevel.FESTIVAL, positionMs = 0L, outGenus = "HIPHOP", inGenus = "DANCE", transitionsSinceLastAbrupt = 8), abruptOnly),
        )
        assertTrue(allowed.recipeId in setOf("F2", "F7"))
    }

    @Test
    fun lowConfidenceFestivalSelectsTunnelOrFilterWashNeverBrake() {
        val plan = requireOk(
            PairPlanner.plan(
                PlannerRequest(
                    6L,
                    makeTrack("out-low", bpm = 100.0, gridConf = 0.25f),
                    makeTrack("in-low", bpm = 120.0, gridConf = 0.30f),
                    djLevel = DjLevel.FESTIVAL, positionMs = 0L, transitionsSinceLastAbrupt = 20,
                ),
            ),
        )
        assertTrue(plan.recipeId == "F5" || plan.recipeId == "C2", "Expected F5 or C2, got ${plan.recipeId}")
    }

    @Test
    fun handoffQualifierRejectsOutOfBoundsPcmBeforeFreezing() {
        val res = PairPlanner.plan(
            PlannerRequest(
                generation = 7L, outTrack = makeTrack("out-h"), inTrack = makeTrack("in-h"),
                djLevel = DjLevel.SUBTLE, positionMs = 0L, outDecodeRangeMs = TimeRangeMs(0L, 4_000L),
            ),
        )
        assertEquals(RefusalReason.HANDOFF_PCM_BEFORE_PROTECTED_EVENT, requireRefused(res))
    }

    @Test
    fun lastResortMusicalExitPicksLastPhraseDownbeatNotPositionMs() {
        val out = makeTrack("out-lr", bpm = 120.0, durationMs = 120_000L, audibleEndMs = 119_000L)
        val inn = makeTrack("in-lr", bpm = 120.0, silentIntroEndMs = 2_000L)
        val pos = 99_500L
        val plan = requireOk(PairPlanner.planLastResortMusicalExit(PlannerRequest(8L, out, inn, djLevel = DjLevel.CLUB, positionMs = pos)))
        assertTrue(plan.isLastResort)
        assertTrue(plan.requiredStems.isEmpty())
        assertEquals(112_000L, plan.frozenExitMs)
        assertTrue(plan.frozenExitMs > pos)
        assertTrue(plan.frozenEntryMs >= inn.silentIntroEndMs)
    }
}
