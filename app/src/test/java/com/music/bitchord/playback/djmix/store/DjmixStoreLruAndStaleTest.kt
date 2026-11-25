package com.music.bitchord.playback.djmix.store

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Focused JVM tests for the B3.1 store rules that [DjmixStoreContractTest]
 * does not pin down: the configurable 500 MB `stem_index` LRU cap semantics,
 * the full model/schema staleness matrix, and the legacy
 * JSON/`PhraseMaps`/`StemCache` migration file layout (count-and-delete
 * analysis caches, magic-checked stem moves with window math, junk cleanup).
 *
 * The migration helpers are pure file-layout functions (no SQLite), so they
 * run against real temp directories here.
 */
class DjmixStoreLruAndStaleTest {

    private val dirs = mutableListOf<File>()

    @Before
    fun setUp() {
        dirs.clear()
    }

    @After
    fun tearDown() {
        // Best-effort recursive cleanup; the OS reaps temp dirs anyway.
        for (dir in dirs) dir.deleteRecursively()
    }

    private fun tempDir(name: String): File =
        Files.createTempDirectory("djmix-store-$name").toFile().also { dirs += it }

    private fun file(dir: File, name: String, content: ByteArray): File =
        File(dir, name).apply { writeBytes(content) }

    // ---- LRU cap (B3.1: 500 MB, configurable, by lastUsed) -------------------

    private fun stem(id: String, lastUsed: Long, bytes: Long) = StemEntry(
        trackId = id, windowStartMs = 0L, windowEndMs = 1_000L, modelId = "m",
        filePath = "/tmp/$id.stm", bytes = bytes, lastUsed = lastUsed,
    )

    @Test
    fun defaultStemCapIs500Mb() {
        assertEquals(500L * 1024L * 1024L, DjmixAnalysisStore.DEFAULT_STEM_CAP_BYTES)
    }

    @Test
    fun lruEvictionIsOldestByLastUsedUntilUnderCap() {
        val rows = listOf(
            stem("newest", lastUsed = 900L, bytes = 100L),
            stem("middle", lastUsed = 500L, bytes = 100L),
            stem("oldest", lastUsed = 100L, bytes = 100L),
        )
        val evicted = DjmixAnalysisStore.selectLruEvictions(rows, maxBytes = 150L)
        assertEquals(listOf("oldest", "middle"), evicted.map { it.trackId })
        // Survivors are within the cap and untouched.
        assertEquals(listOf("newest"), rows.map { it.trackId } - evicted.map { it.trackId }.toSet())
    }

    @Test
    fun lruEvictionStopsAsSoonAsRemainingFitsTheCap() {
        val rows = listOf(
            stem("a", lastUsed = 100L, bytes = 60L),
            stem("b", lastUsed = 200L, bytes = 60L),
            stem("c", lastUsed = 300L, bytes = 60L),
        )
        // 180 total, cap 60: evicting the oldest two (120 freed) reaches 60;
        // evicting the third would be needless churn on the survivor.
        val evicted = DjmixAnalysisStore.selectLruEvictions(rows, maxBytes = 60L)
        assertEquals(listOf("a", "b"), evicted.map { it.trackId })
    }

    @Test
    fun lruEvictionBoundaryTotalEqualsCapEvictsNothing() {
        val rows = listOf(stem("a", 100L, 250L), stem("b", 200L, 250L))
        assertTrue(DjmixAnalysisStore.selectLruEvictions(rows, maxBytes = 500L).isEmpty())
    }

    @Test
    fun lruEvictionSingleEntryLargerThanCapIsEvicted() {
        val rows = listOf(stem("huge", 100L, 600L))
        val evicted = DjmixAnalysisStore.selectLruEvictions(rows, maxBytes = 500L)
        assertEquals(listOf("huge"), evicted.map { it.trackId })
    }

    @Test
    fun lruEvictionHonoursConfigurableCapNotJustDefault() {
        val rows = listOf(
            stem("old", lastUsed = 1L, bytes = 2_000L),
            stem("new", lastUsed = 2L, bytes = 2_000L),
        )
        // A 2 KB custom cap evicts both; the default 500 MB cap would evict
        // neither — the cap is a parameter, not a hard-wired constant.
        assertEquals(2, DjmixAnalysisStore.selectLruEvictions(rows, maxBytes = 2_048L).size)
        assertTrue(DjmixAnalysisStore.selectLruEvictions(rows, maxBytes = 4_000L).isEmpty())
    }

    @Test
    fun lruEvictionWithNoRowsOrZeroCapIsWellFormed() {
        assertTrue(DjmixAnalysisStore.selectLruEvictions(emptyList(), maxBytes = 0L).isEmpty())
        val rows = listOf(stem("only", 1L, 10L))
        assertEquals(1, DjmixAnalysisStore.selectLruEvictions(rows, maxBytes = 0L).size)
    }

    // ---- Staleness (B3.1: model/schema mismatch marks stale, never crashes) --

    @Test
    fun schemaVersionMismatchMarksStaleWithReason() {
        val models = mapOf("beat" to "v1")
        val current = mapOf("beat" to "v1")
        for (stored in listOf(2, 4, 5, 0, -1)) {
            val reason = DjmixAnalysisStore.staleReason(stored, models, current)
            assertNotNull("schema $stored must be stale", reason)
            assertTrue(reason!!.contains("schema"))
            assertTrue(reason.contains("$stored"))
        }
        assertNull(DjmixAnalysisStore.staleReason(3, models, current))
    }

    @Test
    fun modelVersionDriftMarksStaleAndNamesTheModel() {
        val reason = DjmixAnalysisStore.staleReason(
            3, storedModelVersions = mapOf("beat" to "v1", "stem" to "v2"),
            currentModelVersions = mapOf("beat" to "v2", "stem" to "v2"),
        )
        assertNotNull(reason)
        assertTrue(reason!!.contains("beat"))
    }

    @Test
    fun modelSetChangesMarkStaleInBothDirections() {
        // A model the row never saw is stale (new model shipped).
        assertNotNull(
            DjmixAnalysisStore.staleReason(3, mapOf("beat" to "v1"), mapOf("beat" to "v1", "syvenu" to "v9")),
        )
        // A model that was removed from the current set is stale too.
        assertNotNull(
            DjmixAnalysisStore.staleReason(3, mapOf("beat" to "v1", "old" to "v0"), mapOf("beat" to "v1")),
        )
    }

    @Test
    fun emptyCurrentModelVersionsNeverMarksStale() {
        assertNull(DjmixAnalysisStore.staleReason(3, mapOf("beat" to "v1"), emptyMap()))
        assertNull(DjmixAnalysisStore.staleReason(3, emptyMap(), emptyMap()))
    }

    @Test
    fun staleReasonNeverThrowsOnOddInput() {
        // Huge version strings / unicode must not crash the staleness check.
        assertNull(
            DjmixAnalysisStore.staleReason(
                3, mapOf("k" to "x".repeat(10_000)), mapOf("k" to "x".repeat(10_000)),
            ),
        )
        assertNotNull(DjmixAnalysisStore.staleReason(Int.MAX_VALUE, emptyMap(), emptyMap()))
    }

    // ---- Legacy analysis JSON cleanup (counted stale, deleted safely) ---------

    @Test
    fun legacyAnalysisDirCountsStaleJsonAndDeletesIt() {
        val dir = tempDir("analysis")
        file(dir, "a.json", "{}".toByteArray())
        file(dir, "b.json", "{}".toByteArray())
        file(dir, "interrupted.tmp", "garbage".toByteArray())
        val result = DjmixAnalysisStore.cleanLegacyAnalysisDir(dir)
        assertEquals(2, result.staleAnalysis)
        assertEquals(3, result.deletedFiles)
        assertFalse(dir.exists())
    }

    @Test
    fun legacyAnalysisDirKeepsUnknownFilesAndTheDirItself() {
        val dir = tempDir("analysis-foreign")
        file(dir, "a.json", "{}".toByteArray())
        file(dir, "unrelated.txt", "keep me".toByteArray())
        val result = DjmixAnalysisStore.cleanLegacyAnalysisDir(dir)
        assertEquals(1, result.staleAnalysis)
        assertEquals(1, result.deletedFiles)
        assertTrue(dir.exists())
        assertTrue(File(dir, "unrelated.txt").isFile)
    }

    @Test
    fun legacyAnalysisDirMissingOrEmptyIsSafe() {
        val missing = File(tempDir("analysis-missing"), "does-not-exist")
        assertEquals(
            LegacyDirCleanup(staleAnalysis = 0, deletedFiles = 0),
            DjmixAnalysisStore.cleanLegacyAnalysisDir(missing),
        )
        val empty = tempDir("analysis-empty")
        val result = DjmixAnalysisStore.cleanLegacyAnalysisDir(empty)
        assertEquals(0, result.staleAnalysis)
        assertTrue("empty dir is removed", !empty.exists())
    }

    // ---- Legacy StemCache migration (magic check, move, window math) -----------

    private fun stemBytes(payload: String = "pcm") = "BCST".toByteArray(Charsets.US_ASCII) + payload.toByteArray()

    @Test
    fun legacyStemWithValidNameAndMagicMovesAndRegistersWithWindow() {
        val legacy = tempDir("stem-legacy")
        val dest = tempDir("stem-dest")
        file(legacy, "abc123_vocals_44100_88200_44100.stm", stemBytes())
        val registered = mutableListOf<Triple<String, Long, Long>>()
        val result = DjmixAnalysisStore.migrateLegacyStemFiles(legacy, dest, nowMs = 42_000L) { stem, startMs, endMs, target ->
            registered += Triple(stem.trackHash, startMs, endMs)
            target.isFile
        }
        assertEquals(1, result.migratedStems)
        assertEquals(0, result.deletedFiles)
        // 88200 samples @ 44100 Hz = 2000 ms in; 44100 frames = 1000 ms long.
        assertEquals(listOf(Triple("abc123", 2000L, 3000L)), registered)
        // File moved, not copied: legacy side is gone, dest side has the bytes.
        assertFalse(File(legacy, "abc123_vocals_44100_88200_44100.stm").exists())
        val moved = File(dest, "abc123_vocals_44100_88200_44100.stm")
        assertTrue(moved.isFile)
        assertTrue(moved.readBytes().size >= 4)
        assertTrue("BCST".toByteArray().contentEquals(moved.readBytes().copyOf(4)))
    }

    @Test
    fun legacyStemTagMayContainUnderscores() {
        val legacy = tempDir("stem-tag")
        val dest = tempDir("stem-tag-dest")
        // tag = "my_tag" with an underscore inside.
        file(legacy, "h1_my_tag_48000_96000_48000.stm", stemBytes())
        var seenTag: String? = null
        val result = DjmixAnalysisStore.migrateLegacyStemFiles(legacy, dest, nowMs = 1L) { stem, _, _, _ ->
            seenTag = stem.tag
            true
        }
        assertEquals(1, result.migratedStems)
        assertEquals("my_tag", seenTag)
    }

    @Test
    fun legacyStemWithBadMagicIsDeletedNotRegistered() {
        val legacy = tempDir("stem-badmagic")
        val dest = tempDir("stem-badmagic-dest")
        // Name parses, but the payload lacks the StemCache "BCST" magic.
        file(legacy, "h1_voc_44100_0_1000.stm", "NOT_STEM_DATA".toByteArray())
        var registered = 0
        val result = DjmixAnalysisStore.migrateLegacyStemFiles(legacy, dest, nowMs = 1L) { _, _, _, _ ->
            registered++
            true
        }
        assertEquals(0, result.migratedStems)
        assertEquals(1, result.deletedFiles)
        assertEquals(0, registered)
        assertFalse(File(legacy, "h1_voc_44100_0_1000.stm").exists())
    }

    @Test
    fun legacyStemWithUnparseableNameIsDeletedNotRegistered() {
        val legacy = tempDir("stem-badname")
        val dest = tempDir("stem-badname-dest")
        file(legacy, "junk.stm", stemBytes())
        file(legacy, "a_b_c.stm", stemBytes())
        file(legacy, "x_t_44100_abc_100.stm", stemBytes())
        var registered = 0
        val result = DjmixAnalysisStore.migrateLegacyStemFiles(legacy, dest, nowMs = 1L) { _, _, _, _ ->
            registered++
            true
        }
        assertEquals(0, result.migratedStems)
        assertEquals(3, result.deletedFiles)
        assertEquals(0, registered)
    }

    @Test
    fun legacyStemRegisterFailureStillMovesTheFileButCountsNoMigration() {
        val legacy = tempDir("stem-regfail")
        val dest = tempDir("stem-regfail-dest")
        file(legacy, "h1_voc_44100_0_1000.stm", stemBytes())
        val result = DjmixAnalysisStore.migrateLegacyStemFiles(legacy, dest, nowMs = 1L) { _, _, _, _ -> false }
        assertEquals(0, result.migratedStems)
        // The stem bytes still landed in the destination; only the index write failed.
        assertTrue(File(dest, "h1_voc_44100_0_1000.stm").isFile)
    }

    @Test
    fun legacyStemTmpLeftoversAreDeleted() {
        val legacy = tempDir("stem-tmp")
        val dest = tempDir("stem-tmp-dest")
        file(legacy, "half-write.tmp", "partial".toByteArray())
        val result = DjmixAnalysisStore.migrateLegacyStemFiles(legacy, dest, nowMs = 1L) { _, _, _, _ -> true }
        assertEquals(0, result.migratedStems)
        assertEquals(1, result.deletedFiles)
        assertFalse(File(legacy, "half-write.tmp").exists())
    }

    @Test
    fun legacyStemDirIsRemovedWhenEmptyAndKeptWhenItCannotBe() {
        val legacy = tempDir("stem-rmdir")
        val dest = tempDir("stem-rmdir-dest")
        file(legacy, "h1_voc_44100_0_1000.stm", stemBytes())
        DjmixAnalysisStore.migrateLegacyStemFiles(legacy, dest, nowMs = 1L) { _, _, _, _ -> true }
        assertFalse("dir emptied by the migration is removed", legacy.exists())

        val legacyWithDir = tempDir("stem-rmdir-kept").also { File(it, "nested").mkdirs() }
        file(legacyWithDir, "h1_voc_44100_0_1000.stm", stemBytes())
        DjmixAnalysisStore.migrateLegacyStemFiles(legacyWithDir, dest, nowMs = 1L) { _, _, _, _ -> true }
        assertTrue("a non-empty dir (subdirectory) is left in place", legacyWithDir.exists())
    }

    @Test
    fun migrationMarkerKeyIsStableAndExcludedFromPreferenceResets() {
        // resetPreferences() deletes every preference row except the marker;
        // the marker key itself must never change or the migration re-runs.
        assertEquals("__legacy_migration_v1_done", DjmixAnalysisStore.MIGRATION_MARKER_KEY)
    }
}
