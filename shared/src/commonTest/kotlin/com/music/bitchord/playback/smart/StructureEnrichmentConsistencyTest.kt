package com.music.bitchord.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructureEnrichmentConsistencyTest {
    @Test
    fun vocalEvidenceRecomputesInstrumentalConclusion() {
        val section = PhraseSection(0, 4, SectionFn.INSTRUMENTAL, SectionRole.GROOVE, 0.4f, 7, 0, 0.5f, 5, 0f, 0f, 0f, 0.4f, true)
        val base = SongMap(
            trackKey = "enrich", durationSec = 16.0, bpm = 120.0, beatsPerBar = 4,
            downbeats = DoubleArray(5) { it * 4.0 }, gridConfidence = 0.8f, tempoStable = true,
            phraseAnchor = 0, phraseLen = 8, sections = listOf(section), sungBars = BooleanArray(4),
            firstVocalBar = null, lastVocalBar = null, pickupBeats = 0f,
            startKind = StartKind.INTRO_INSTRUMENTAL, endingKind = EndingKind.UNKNOWN,
            contentEndSec = 16.0, labelConfidence = 0.4f, structMs = 0,
            vocalCoverage = BooleanArray(4),
        )
        val analysis = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY, duration = 16.0, bpm = 120.0,
            beatInterval = 0.5, quality = TrackAnalysis.QUALITY_FULL,
            vocalWindows = listOf(CurveWindow(0.0, 10.0, FloatArray(32) { 0.8f })),
        )
        val enriched = SongMapEnrichment.enrich(base, analysis)
        assertEquals(SectionFn.VERSE, enriched.sections.single().fn)
        assertTrue(!enriched.sections.single().loopable)
        assertTrue(enriched.sections.single().vocal > 0.9f)
        assertTrue(enriched.isVocalMeasured(0))
    }
}
