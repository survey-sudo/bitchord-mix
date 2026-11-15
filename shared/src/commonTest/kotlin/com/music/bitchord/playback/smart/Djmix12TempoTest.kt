package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * djmix12 tempo discipline + fold unification + scalar ranking.
 */
class Djmix12TempoTest {

    // ---- fold unification ----

    @Test fun halfTimeFoldsToUnity() {
        assertEquals(0.5, alignedFold(148.0, 74.0), 1e-9)
        assertEquals(148.0, alignTempoOctave(148.0, 74.0), 1e-9)
        assertEquals(2.0, alignedFold(120.0, 240.0), 1e-9)
        assertEquals(0.5, alignedFold(120.0, 60.0), 1e-9)
        // The filmed +42.5%: 88.93 vs 126.70 folds to 2 (63.35).
        assertEquals(2.0, alignedFold(88.93466859973474, 126.697427976501), 1e-9)
    }

    @Test fun beyondEightPercentIsNotARelaxedRung() {
        // 88.93 -> 126.70: post-fold 63.35/88.93 = 0.712 (28.7% off).
        val a = song("a", 88.93466859973474, 16, listOf(section(0, 16, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5)))
        val b = song("b", 126.697427976501, 16, listOf(section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 5)))
        val choice = assertNotNull(PhrasePlanner.select(a, b, 0, 88.93466859973474, 126.697427976501))
        assertTrue(choice.template != MixTemplate.RELAXED, "long tempo-off blends never ride RELAXED, got ${choice.template}")
    }

    @Test fun closeTemposKeepTheBlend() {
        val a = song("a", 112.0, 16, listOf(section(0, 16, SectionFn.OUTRO, SectionRole.GROOVE, energy = 5)))
        val b = song("b", 113.5, 16, listOf(section(0, 16, SectionFn.INTRO, SectionRole.GROOVE, energy = 5)))
        val choice = assertNotNull(PhrasePlanner.select(a, b, 0, 112.0, 113.5))
        assertTrue(choice.template == MixTemplate.M1 || choice.template == MixTemplate.ROLLING, "close tempos blend, got ${choice.template}")
    }

    // ---- scalar ranking: quiet break beats loud content_end ----

    @Test fun quietBreakOutranksLoudContentEnd() {
        val contentEnd = 200.0
        val curve = (0 until 240).map { i -> EnergySample(i * 0.833, 0.9) }
        val mask = (0 until 240).map { i ->
            val t = i * 0.833
            if (t >= 188.0) 0.1 else 0.5
        }
        val a = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, trackId = "t1", duration = contentEnd,
            bpm = 120.0, beatInterval = 0.5, beatConfidence = 0.9,
            contentEndTime = contentEnd,
            mixOutCandidates = listOf(
                MixCandidate(188.0, 0.7, "break_outro"),
                MixCandidate(contentEnd, 0.75, "content_end"),
            ),
            energyCurve = curve, vocalActivityMask = mask,
        )
        val ranked = rankMixOutCandidates(a, contentEnd = contentEnd, duration = contentEnd)
        assertEquals("break_outro", ranked.firstOrNull()?.type)
    }

    // ---- fixtures (mirror PhrasePlannerTest) ----

    private fun section(start: Int, end: Int, fn: SectionFn, role: SectionRole, energy: Int) =
        PhraseSection(start, end, fn, role, 0.8f, 0, 0, energy / 10f, energy, 0f, 0f, 0f, 0.4f, false)

    private fun song(key: String, bpm: Double, bars: Int, sections: List<PhraseSection>): SongMap {
        val bar = 60.0 / bpm * 4
        return SongMap(
            trackKey = key, durationSec = bars * bar, bpm = bpm, beatsPerBar = 4,
            downbeats = DoubleArray(bars + 1) { it * bar }, gridConfidence = 0.9f,
            tempoStable = true, phraseAnchor = 0, phraseLen = 8, sections = sections,
            sungBars = BooleanArray(bars) { false }, firstVocalBar = null, lastVocalBar = null, pickupBeats = 0f,
            startKind = StartKind.INTRO_INSTRUMENTAL, endingKind = EndingKind.OUTRO_INSTRUMENTAL,
            contentEndSec = bars * bar, labelConfidence = 0.8f, structMs = 1, chorusCount = 0,
        )
    }
}
