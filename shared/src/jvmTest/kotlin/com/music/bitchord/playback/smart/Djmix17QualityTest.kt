package com.music.bitchord.playback.smart

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * djmix17 Bug C: AnalysisStore must persist `quality`, so an analysed
 * track keeps quality="full" across an app restart instead of loading
 * back as quality="none".
 *
 * In shared JVM tests because [AnalysisStore] is jvmSharedMain.
 */
class Djmix17QualityTest {

    private fun tmpStore(): Pair<AnalysisStore, java.io.File> {
        val root = Files.createTempDirectory("djmix17-quality").toFile()
        root.deleteOnExit()
        return AnalysisStore { root } to root
    }

    private fun fullAnalysis() = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = "never-let-go",
        duration = 195.0,
        bpm = 104.5,
        beatConfidence = 0.9,
        firstBeat = 0.5,
        downbeats = listOf(0.5, 1.8),
        energyCurve = listOf(EnergySample(0.0, 0.5), EnergySample(10.0, 0.7)),
        tailBeats = BeatSide(beats = listOf(180.0, 181.0), downbeats = listOf(180.0), bpm = 104.5, confidence = 0.9),
        quality = TrackAnalysis.QUALITY_FULL,
    )

    @Test fun qualityPersistsAcrossSaveLoad() {
        val (store, _) = tmpStore()
        store.save("never-let-go", fullAnalysis())
        val loaded = store.load("never-let-go")
        assertNotNull(loaded, "saved analysis must load")
        assertEquals(TrackAnalysis.QUALITY_FULL, loaded.quality, "quality=full must survive the round-trip")
        assertEquals(104.5, loaded.bpm, 1e-9)
    }

    @Test fun legacyJsonWithoutQualityInfersFull() {
        val (store, root) = tmpStore()
        store.save("never-let-go", fullAnalysis())
        val dir = java.io.File(root, "smart_analysis")
        val file = dir.listFiles()?.firstOrNull { it.name.endsWith(".json") }
        assertNotNull(file, "save must write a JSON entry")
        // Strip the quality field to mimic pre-djmix17 JSON.
        val legacy = file.readText().replace("\"quality\":\"full\"", "\"quality\":\"\"", )
        assertTrue(!legacy.contains("\"quality\":\"full\""), "legacy JSON must not carry quality=full")
        file.writeText(legacy)
        val loaded = store.load("never-let-go")
        assertNotNull(loaded, "legacy JSON must still load")
        assertEquals(
            TrackAnalysis.QUALITY_FULL, loaded.quality,
            "legacy entry with energyCurve/tailBeats must infer quality=full",
        )
    }

    @Test fun legacyJsonWithoutMarkersInfersNone() {
        val (store, root) = tmpStore()
        store.save(
            "head-only",
            TrackAnalysis(
                status = TrackAnalysis.STATUS_READY,
                trackId = "head-only",
                duration = 200.0,
                bpm = 120.0,
                beatConfidence = 0.8,
                headBeats = BeatSide(beats = listOf(0.5), downbeats = listOf(0.5), bpm = 120.0, confidence = 0.8),
                quality = TrackAnalysis.QUALITY_HEAD,
            ),
        )
        val dir = java.io.File(root, "smart_analysis")
        val file = dir.listFiles()?.firstOrNull { it.name.endsWith(".json") }
        assertNotNull(file, "save must write a JSON entry")
        // Strip quality so inference must decide from markers (none here).
        val legacy = file.readText().replace("\"quality\":\"head\"", "\"quality\":\"\"")
        assertTrue(!legacy.contains("\"quality\":\"head\""), "legacy JSON must not carry quality=head")
        file.writeText(legacy)
        val loaded = store.load("head-only")
        assertNotNull(loaded, "legacy head-only JSON must load")
        assertEquals(
            TrackAnalysis.QUALITY_NONE, loaded.quality,
            "without full markers the inference must be none, never false full",
        )
    }

    @Test fun staleSchema4AndOldProvenanceAreDiscardedOnLoad() {
        val (store, root) = tmpStore()
        store.save("bubble-tea", fullAnalysis().copy(trackId = "bubble-tea", key = "Ab major", keyConfidence = 0.9))
        val dir = java.io.File(root, "smart_analysis")
        val file = dir.listFiles()?.firstOrNull { it.name.endsWith(".json") }
        assertNotNull(file)
        val currentText = file.readText()
        assertTrue(currentText.contains("\"version\":5"), "expected schema v5: $currentText")
        assertTrue(currentText.contains("full-ort-1.31.0-cpu-keyfix1"), "expected keyfix1 provenance: $currentText")

        // Stale v4 entry with old provenance must be discarded.
        val v4Text = currentText
            .replace("\"version\":5", "\"version\":4")
            .replace("full-ort-1.31.0-cpu-keyfix1", "full-ort-1.31.0-cpu")
        file.writeText(v4Text)
        assertNull(store.load("bubble-tea"), "v4 entry must be discarded")
        assertTrue(!file.exists(), "stale v4 file must be deleted")

        // Re-save, then downgrade provenance only: must still be discarded.
        store.save("bubble-tea", fullAnalysis().copy(trackId = "bubble-tea", key = "Ab major", keyConfidence = 0.9))
        val oldProvText = file.readText().replace("full-ort-1.31.0-cpu-keyfix1", "full-ort-1.31.0-cpu")
        file.writeText(oldProvText)
        assertNull(store.load("bubble-tea"), "old provenance entry must be discarded")
        assertTrue(!file.exists(), "stale provenance file must be deleted")
    }

    @Test fun fullVocalWindowsSurviveStoreReloadAndHeadStaysUnenriched() {
        val (store, _) = tmpStore()
        val withVocal = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "with-vocal",
            duration = 32.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.8,
            downbeats = emptyList(),
            vocalWindows = listOf(CurveWindow(0.0, 10.0, FloatArray(40) { 0.8f })),
            quality = TrackAnalysis.QUALITY_FULL,
        )
        store.save("with-vocal", withVocal)
        val loaded = store.load("with-vocal")
        assertNotNull(loaded, "saved full analysis must load")
        assertEquals(TrackAnalysis.QUALITY_FULL, loaded.quality)
        assertTrue(loaded.vocalWindows.isNotEmpty(), "vocalWindows must survive the round-trip")
        val map = SongMaps.fromAnalysis("with-vocal", loaded)
        assertNotNull(map, "loaded analysis must yield a map")
        assertTrue(map.vocalEnergy.isNotEmpty(), "reloaded vocalWindows must preserve non-empty vocalEnergy")
        assertNotNull(map.firstVocalBar, "reloaded map must preserve firstVocalBar")

        val head = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = "head-only",
            duration = 32.0,
            bpm = 120.0,
            beatInterval = 0.5,
            beatConfidence = 0.8,
            downbeats = emptyList(),
            vocalWindows = emptyList(),
            quality = TrackAnalysis.QUALITY_HEAD,
        )
        val headBase = SongMaps.fromAnalysis("head-only", head)
        assertNotNull(headBase, "head analysis must yield a base map")
        val enriched = SongMapEnrichment.enrich(headBase, head)
        assertTrue(enriched === headBase, "QUALITY_HEAD with empty vocalWindows must stay unenriched")
    }
}
