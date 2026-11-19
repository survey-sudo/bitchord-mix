package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Literal local-evidence contract, independent of planner constants and historical song identities. */
class LoopEligibilityRecoveryTest {
    // Non-null clear lyrics are deliberate: the measured mask must also authorize both loops.
    private val clearLyrics = emptyList<LoopMix.Span>()

    private fun fixture(): Pair<TrackAnalysis, TrackAnalysis> = Pair(
        analysis(duration = 200.0, cleanFrom = 190.0, cleanTo = 198.5),
        analysis(duration = 180.0, cleanFrom = 0.0, cleanTo = 8.5),
    )

    private fun analysis(duration: Double, cleanFrom: Double, cleanTo: Double): TrackAnalysis {
        // Measured local 120 BPM: half-second beats, downbeat phase 0.5 seconds.
        // Head covers 0.5..60.5; tails cover 140.5..199.5 (A) / 120.5..179.5 (B).
        fun measuredSide(beats: List<Double>) = BeatSide(
            beats = beats,
            downbeats = beats.filterIndexed { index, _ -> index % 4 == 0 },
            bpm = 120.0,
            confidence = 0.9,
        )

        val frames = (duration * 10.0).toInt()
        val mask = FloatArray(frames) { index ->
            val time = index / 10.0
            if (time >= cleanFrom && time < cleanTo) 0.05f else 0.9f
        }
        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            duration = duration,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.9,
            downbeats = List((duration / 2.0).toInt()) { 0.5 + it * 2.0 },
            firstBeat = 0.5,
            vocalProbability = 0.0,
            headBeats = measuredSide(List(121) { 0.5 + it * 0.5 }),
            tailBeats = measuredSide(List(119) { duration - 59.5 + it * 0.5 }),
            vocalWindows = listOf(CurveWindow(0.0, 10.0, mask)),
            levelWindows = listOf(
                CurveWindow(0.0, 10.0, FloatArray(frames) { levelFromRms(0.3).toFloat() }),
            ),
        )
    }

    @Test
    fun cleanMeasuredLocalLoopsProduceFourBarCrown() {
        val (out, inn) = fixture()
        assertFalse(shouldDeclineToMix(out, inn), "Control has no whole-track decline")
        assertLocalEvidence(out, inn)
        assertCleanCrown(out, inn)
    }

    @Test
    fun wholeTrackDeclineStillAllowsIdenticalCleanLocalOneTimesCrown() {
        val (controlOut, controlIn) = fixture()
        // Only whole-track metadata differs; beatInterval, local grids, masks and levels stay identical.
        val out = controlOut.copy(bpm = 120.0, vocalProbability = 0.9)
        val inn = controlIn.copy(bpm = 132.0, vocalProbability = 0.9)
        assertTrue(shouldDeclineToMix(out, inn), "Whole-track tempo gap and dual-vocal metadata decline")
        assertLocalEvidence(out, inn)
        // Positive recovery contract: do not characterize the current early-return null as correct.
        assertCleanCrown(out, inn)
    }

    private fun assertLocalEvidence(out: TrackAnalysis, inn: TrackAnalysis) {
        // Literal corridors include the full one-beat guard before each eight-second loop.
        for ((analysis, corridor) in listOf(out to (190.0 to 198.5), inn to (0.0 to 8.5))) {
            val mask = assertNotNull(LoopMix.maskStats(analysis.vocalWindows, corridor.first, corridor.second))
            assertEquals(0.0, mask.share, 1e-9)
            assertEquals(0.0, mask.longestRunSec, 1e-9)
            assertEquals(0.05, mask.peak, 1e-6)
        }
        // Adjacent four-bar candidates cannot become hidden positives: these regions are measured sung.
        assertEquals(1.0, assertNotNull(LoopMix.maskStats(out.vocalWindows, 188.0, 190.0)).share, 1e-9)
        assertEquals(1.0, assertNotNull(LoopMix.maskStats(inn.vocalWindows, 8.5, 10.5)).share, 1e-9)
    }

    private fun assertCleanCrown(out: TrackAnalysis, inn: TrackAnalysis) {
        val plan = assertNotNull(
            LoopMix.plan(out, 200.0, inn, 180.0, 0.0, clearLyrics, clearLyrics),
            "Locally clean measured material must yield a crown even when whole-track metadata declines",
        )
        assertTrue(plan.outLoops, "Outgoing deck must genuinely loop")
        assertTrue(plan.inLoops, "Incoming deck must genuinely loop")
        assertEquals(4, plan.out.bars)
        assertEquals(4, plan.inn.bars)
        assertEquals(1.0, plan.fold, 1e-9)
        assertEquals(190.5, plan.out.loopStart, 1e-9)
        assertEquals(198.5, plan.out.loopEnd, 1e-9)
        assertEquals(0.5, plan.inn.loopStart, 1e-9)
        assertEquals(8.5, plan.inn.loopEnd, 1e-9)
        for (pick in listOf(plan.out, plan.inn)) {
            assertEquals(0.5, pick.beatSec, 1e-9)
            assertEquals(120.0, pick.bpm, 1e-9)
            assertEquals(0.0, pick.vocal, 1e-9)
            assertEquals(LoopMix.Check.LYRICS_AND_MASK, pick.checkedBy)
        }
        assertFalse(LoopMix.overlapsSung(plan, clearLyrics, clearLyrics), "No local lyric veto")
        assertFalse(LoopMix.vetoes(plan, out, inn, clearLyrics, clearLyrics), "No local sung veto")
    }
}
