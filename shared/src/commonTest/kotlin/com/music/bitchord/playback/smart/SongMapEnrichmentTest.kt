package com.music.bitchord.playback.smart

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SongMapEnrichmentTest {

    private fun baseMap(): SongMap {
        val sr = 22050
        val durationSec = 32.0
        val pcm = FloatArray((durationSec * sr).toInt()) { i ->
            (0.4 * sin(2 * PI * 110.0 * i / sr)).toFloat()
        }
        return StructureAnalyzer.fromPcm(
            trackKey = "enrich-base",
            pcm = pcm,
            sampleRate = sr,
            bpm = 120.0,
            mask = null,
        )
    }

    private fun fullAnalysis(bpm: Double, windows: List<CurveWindow>): TrackAnalysis = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = "enrich-base",
        duration = 32.0,
        bpm = bpm,
        beatInterval = 60.0 / bpm,
        beatConfidence = 0.8,
        vocalWindows = windows,
        quality = TrackAnalysis.QUALITY_FULL,
    )

    private fun headWindow(values: FloatArray): CurveWindow = CurveWindow(0.0, 10.0, values)

    @Test
    fun maskNullBasePlusFullAnalysisPopulatesVocalEnergy() {
        val base = baseMap()
        assertTrue(base.sungBars.isNotEmpty(), "base map must have bars, got ${base.sungBars.size}")
        assertTrue(base.vocalEnergy.isEmpty(), "mask=null PCM map must start unmeasured")
        val analysis = fullAnalysis(120.0, listOf(headWindow(FloatArray(40) { 0.8f })))
        val enriched = SongMapEnrichment.enrich(base, analysis)
        assertTrue(enriched !== base, "enriched map must be a new instance")
        assertTrue(enriched.vocalEnergy.isNotEmpty(), "enriched vocalEnergy must be non-empty")
        assertEquals(base.sungBars.size, enriched.vocalEnergy.size)
        assertNotNull(enriched.firstVocalBar, "firstVocalBar must be populated")
        assertEquals(0, enriched.firstVocalBar)
        assertTrue(enriched.sungBars[0], "covered bar 0 must be sung")
        assertTrue(enriched.sungBars[1], "covered bar 1 must be sung")
        assertTrue(enriched.vocalEnergy[0] > 0.5f, "covered energy must be high, got ${enriched.vocalEnergy[0]}")
        assertTrue(base != enriched, "equals() must differ after enrichment")
        assertTrue(base.hashCode() != enriched.hashCode(), "hashCode() must differ after enrichment")
    }

    @Test
    fun uncoveredMiddleBarsPreserveBaseValues() {
        val base = baseMap()
        val n = base.sungBars.size
        assertTrue(n >= 8, "need enough bars for a middle, got $n")
        val mid = n / 2
        val baseWithMiddle = base.copy(
            sungBars = BooleanArray(n) { it == mid || it == mid + 1 },
            vocalEnergy = FloatArray(n) { if (it == mid || it == mid + 1) 0.33f else 0f },
        )
        val tailStart = base.barTime(n - 2)
        val analysis = fullAnalysis(
            120.0,
            listOf(
                headWindow(FloatArray(40) { 0.8f }),
                CurveWindow(tailStart, 10.0, FloatArray(40) { 0.6f }),
            ),
        )
        val enriched = SongMapEnrichment.enrich(baseWithMiddle, analysis)
        // Covered head overwritten.
        assertTrue(enriched.sungBars[0], "covered head bar must be sung")
        assertTrue(enriched.vocalEnergy[0] > 0.5f, "covered head energy must be high")
        // Covered tail overwritten.
        assertTrue(enriched.sungBars[n - 1], "covered tail bar must be sung")
        // Uncovered middle preserves base.
        assertTrue(enriched.sungBars[mid], "uncovered middle must preserve base sungBars")
        assertEquals(0.33f, enriched.vocalEnergy[mid], 0.0001f, "uncovered middle must preserve base vocalEnergy")
        assertTrue(enriched.sungBars[mid + 1], "uncovered middle+1 must preserve base sungBars")
        assertEquals(0.33f, enriched.vocalEnergy[mid + 1], 0.0001f)
        // An uncovered quiet bar stays quiet.
        val quietMid = mid + 2
        if (quietMid < n - 2) {
            assertTrue(!enriched.sungBars[quietMid] || baseWithMiddle.sungBars[quietMid], "uncovered quiet bar must preserve base")
        }
    }

    @Test
    fun withinTwoPercentBpmMapsByBaseBarTime() {
        val base = baseMap()
        val n = base.sungBars.size
        assertTrue(n > 2, "need bars for bar 2")
        // Window covering only bar 2: [base.barTime(2), base.barTime(3)).
        val bar2Start = base.barTime(2)
        val analysis = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "enrich-base",
            duration = 32.0,
            bpm = 122.0,
            beatInterval = 60.0 / 122.0,
            beatConfidence = 0.8,
            downbeats = listOf(0.3, 2.3, 4.3, 6.3, 8.3),
            vocalWindows = listOf(CurveWindow(bar2Start, 10.0, FloatArray(20) { 0.8f })),
            quality = TrackAnalysis.QUALITY_FULL,
        )
        assertTrue(
            kotlin.math.abs(base.bpm - analysis.bpm) / analysis.bpm <= 0.02,
            "test BPM must be within 2%, base=${base.bpm} analysis=${analysis.bpm}",
        )
        val enriched = SongMapEnrichment.enrich(base, analysis)
        assertTrue(enriched !== base, "within-2% BPM must still enrich")
        assertTrue(enriched.sungBars[2], "window on bar 2 must mark bar 2 via base.barTime")
        assertTrue(!enriched.sungBars[0], "bar 0 must not be marked when only bar 2 is covered")
        assertEquals(2, enriched.firstVocalBar)
    }

    @Test
    fun qualityHeadIsNoOp() {
        val base = baseMap()
        val analysis = fullAnalysis(120.0, listOf(headWindow(FloatArray(40) { 0.8f })))
            .copy(quality = TrackAnalysis.QUALITY_HEAD)
        val enriched = SongMapEnrichment.enrich(base, analysis)
        assertTrue(enriched === base, "QUALITY_HEAD must return base unchanged")
    }

    @Test
    fun emptyVocalWindowsIsNoOp() {
        val base = baseMap()
        val analysis = fullAnalysis(120.0, emptyList())
        val enriched = SongMapEnrichment.enrich(base, analysis)
        assertTrue(enriched === base, "empty vocalWindows must return base unchanged")
    }

    @Test
    fun bpmMismatchOverTwoPercentIsNoOp() {
        val base = baseMap()
        val analysis = fullAnalysis(130.0, listOf(headWindow(FloatArray(40) { 0.8f })))
        assertTrue(
            kotlin.math.abs(base.bpm - analysis.bpm) / analysis.bpm > 0.02,
            "mismatch BPM must exceed 2%",
        )
        val enriched = SongMapEnrichment.enrich(base, analysis)
        assertTrue(enriched === base, ">2% BPM mismatch must return base unchanged")
    }
}
