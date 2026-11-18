package com.music.bitchord.playback

import com.music.bitchord.playback.smart.CurveWindow
import com.music.bitchord.playback.smart.SectionFn
import com.music.bitchord.playback.smart.SongMapEnrichment
import com.music.bitchord.playback.smart.SongMaps
import com.music.bitchord.playback.smart.StructureAnalyzer
import com.music.bitchord.playback.smart.TrackAnalysis
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackAnalyzerStructureWiringTest {
    @Test
    fun dspPhaseGridAndMergedModelMasksIntegrateThroughEnrichment() {
        val pcm = FloatArray(22050 * 16)
        val dspDownbeats = doubleArrayOf(0.30, 2.30, 4.30, 6.30, 8.30, 10.30, 12.30, 14.30)
        val pass1 = StructureAnalyzer.fromPcm(
            trackKey = "wired",
            pcm = pcm,
            sampleRate = 22050,
            bpm = 120.0,
            partial = false,
            fullTrackDurationSec = 16.0,
            downbeats = dspDownbeats,
            gridConfidence = 0.85f,
            gridMeasured = false,
        )
        assertFalse(pass1.gridMeasured)
        assertTrue(pass1.gridConfidence <= 0.60f)
        assertEquals(0.30, pass1.downbeats.first(), 1e-4)

        val neutral = 0.2
        val headMask = DoubleArray(16) { i -> if (i <= 5) 0.1 else neutral }
        val tailMask = DoubleArray(16) { i -> if (i >= 4) 0.8 else neutral }
        val merged = SongMaps.mergeMeasuredMasks(16, listOf(tailMask, headMask), neutral)
        assertNotNull(merged)
        assertEquals(0.1, merged!![0], 1e-4)
        assertTrue(merged[4] in 0.1..0.8)
        assertEquals(0.8, merged[15], 1e-4)

        val analysis = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "wired",
            duration = 16.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.92,
            quality = TrackAnalysis.QUALITY_FULL,
            downbeats = dspDownbeats.toList(),
            vocalWindows = listOf(CurveWindow(0.0, 10.0, FloatArray(160) { 0.85f })),
        )
        val enriched = SongMapEnrichment.enrich(pass1, analysis)
        assertTrue(enriched.gridMeasured)
        assertTrue(enriched.isVocalMeasured(0))
        assertTrue(enriched.sections.any { it.fn == SectionFn.VERSE || it.fn == SectionFn.CHORUS })
        assertTrue(abs(enriched.downbeats.first() - 0.30) < 1e-4)
    }
}
