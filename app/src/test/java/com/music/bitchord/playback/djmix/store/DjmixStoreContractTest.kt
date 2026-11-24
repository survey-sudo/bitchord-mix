package com.music.bitchord.playback.djmix.store

import com.music.bitchord.djmix.core.contracts.AnalysisLevel
import com.music.bitchord.djmix.core.contracts.SongStructureV3
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM contract test for the Wave 3B-2 store (no Robolectric in :app, so this
 * pins the pure logic the SQLite layer uses: schema SQL, V3 JSON round-trip,
 * staleness, preference clamping, LRU ordering, legacy stem naming).
 */
class DjmixStoreContractTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun schemaConstantsDeclareFourTablesAndPrimaryKeys() {
        assertEquals("djmix.db", DjmixDatabase.DB_NAME)
        assertEquals(1, DjmixDatabase.SCHEMA_VERSION)
        assertTrue(DjmixDatabase.CREATE_TRACK_ANALYSIS.contains("CREATE TABLE IF NOT EXISTS track_analysis"))
        assertTrue(DjmixDatabase.CREATE_TRACK_ANALYSIS.contains("PRIMARY KEY (track_id, content_hash)"))
        assertTrue(DjmixDatabase.CREATE_TRACK_ANALYSIS.contains("payload BLOB NOT NULL"))
        assertTrue(DjmixDatabase.CREATE_STEM_INDEX.contains("PRIMARY KEY (track_id, window_start_ms, window_end_ms, model_id)"))
        assertTrue(DjmixDatabase.CREATE_TRANSITION_HISTORY.contains("id INTEGER PRIMARY KEY AUTOINCREMENT"))
        assertTrue(DjmixDatabase.CREATE_TRANSITION_HISTORY.contains("rating INTEGER NOT NULL DEFAULT 0"))
        assertTrue(DjmixDatabase.CREATE_PREFERENCE.contains("pref_key TEXT PRIMARY KEY"))
        assertTrue(DjmixDatabase.INDEX_STEM_LAST_USED.contains("idx_stem_last_used"))
    }

    @Test
    fun trackAnalysisV3JsonRoundTrip() {
        val original = TrackAnalysisV3(
            trackId = "track-1",
            contentHash = "hash-1",
            durationMs = 210_000L,
            analysisLevel = AnalysisLevel.FULL,
            bpm = 124.5,
            beatsMs = listOf(0L, 482L, 964L, 1_446L),
            downbeatsMs = listOf(0L, 1_928L),
            keyCamelot = "8A",
            modelVersions = mapOf("beat" to "v1", "stem" to "v2"),
            structure = SongStructureV3(phraseLenBars = 8),
        )
        val encoded = json.encodeToString(TrackAnalysisV3.serializer(), original)
        val decoded = json.decodeFromString(TrackAnalysisV3.serializer(), encoded)
        assertEquals(original, decoded)
        assertEquals(3, decoded.schema)
        assertEquals(mapOf("beat" to "v1", "stem" to "v2"), decoded.modelVersions)
    }

    @Test
    fun staleReasonFlagsSchemaAndModelMismatch() {
        val current = mapOf("beat" to "v1", "stem" to "v2")
        assertNull(DjmixAnalysisStore.staleReason(3, current, current))
        // No current models to compare against: nothing to be stale about.
        assertNull(DjmixAnalysisStore.staleReason(3, current, emptyMap()))
        // Legacy schema 5 rows are stale by definition.
        assertNotNull(DjmixAnalysisStore.staleReason(5, current, current))
        assertTrue(DjmixAnalysisStore.staleReason(5, current, current)!!.contains("schema"))
        // A drifted model version is stale and names the model.
        val drift = DjmixAnalysisStore.staleReason(3, current, mapOf("beat" to "v9", "stem" to "v2"))
        assertNotNull(drift)
        assertTrue(drift!!.contains("beat"))
        // A model the row never saw is stale too.
        assertNotNull(DjmixAnalysisStore.staleReason(3, emptyMap(), current))
    }

    @Test
    fun preferenceDeltasClampToBounds() {
        assertEquals(0.3, DjmixAnalysisStore.clampWeight(0.5), 1e-9)
        assertEquals(-0.3, DjmixAnalysisStore.clampWeight(-1.0), 1e-9)
        assertEquals(0.1, DjmixAnalysisStore.clampWeight(0.1), 1e-9)
        assertEquals(0.0, DjmixAnalysisStore.clampWeight(Double.NaN), 1e-9)
        assertEquals(0.3, DjmixAnalysisStore.applyPreferenceDelta(0.25, 0.2), 1e-9)
        assertEquals(-0.3, DjmixAnalysisStore.applyPreferenceDelta(-0.25, -0.2), 1e-9)
        assertEquals(0.2, DjmixAnalysisStore.applyPreferenceDelta(0.1, 0.1), 1e-9)
    }

    @Test
    fun lruEvictionTakesOldestFirstUntilUnderCap() {
        fun stem(id: String, lastUsed: Long, bytes: Long) = StemEntry(
            trackId = id, windowStartMs = 0L, windowEndMs = 1_000L, modelId = "m",
            filePath = "/tmp/$id.stm", bytes = bytes, lastUsed = lastUsed,
        )
        val rows = listOf(stem("a", 100L, 100L), stem("b", 200L, 100L), stem("c", 300L, 100L))
        assertEquals(500L * 1024L * 1024L, DjmixAnalysisStore.DEFAULT_STEM_CAP_BYTES)
        assertTrue(DjmixAnalysisStore.selectLruEvictions(rows, 500L).isEmpty())
        assertEquals(listOf("a", "b"), DjmixAnalysisStore.selectLruEvictions(rows, 150L).map { it.trackId })
        assertEquals(listOf("a", "b"), DjmixAnalysisStore.selectLruEvictions(rows, 100L).map { it.trackId })
        assertEquals(3, DjmixAnalysisStore.selectLruEvictions(rows, 0L).size)
        assertTrue(DjmixAnalysisStore.selectLruEvictions(emptyList(), 0L).isEmpty())
    }

    @Test
    fun legacyStemNameParser() {
        val parsed = DjmixAnalysisStore.parseLegacyStemName("abc123_my_tag_44100_88200_44100.stm")
        assertNotNull(parsed)
        assertEquals("abc123", parsed!!.trackHash)
        assertEquals("my_tag", parsed.tag)
        assertEquals(44_100, parsed.sampleRate)
        assertEquals(88_200L, parsed.startSample)
        assertEquals(44_100, parsed.frames)
        assertNull(DjmixAnalysisStore.parseLegacyStemName("junk.stm"))
        assertNull(DjmixAnalysisStore.parseLegacyStemName("a_b_c.stm"))
        assertNull(DjmixAnalysisStore.parseLegacyStemName("x_t_44100_x_100.stm"))
    }
}
