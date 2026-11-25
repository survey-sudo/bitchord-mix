package com.music.bitchord.playback.djmix.store

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.music.bitchord.djmix.core.contracts.TrackAnalysisV3
import com.music.bitchord.playback.smart.AnalysisLog
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persistent versioned store over [DjmixDatabase] (B3.1 + ADR 0006).
 *
 * - Analysis rows are [TrackAnalysisV3] JSON keyed by `(track_id, content_hash)`;
 *   staleness is lazy and queue-first: a row whose `schema_version` is not 3 or
 *   whose model versions differ from the current ones reads as
 *   [AnalysisLookupResult.Stale] and is re-earned, never wiped.
 * - Stem rows index on-disk files under `noBackupFilesDir/stems/` (500 MB LRU
 *   cap by `last_used`).
 * - No public method throws: SQLite/serialization failures are logged through
 *   [AnalysisLog] and returned as typed results/false/empty.
 *
 * The pure-VM helpers in the companion object are the exact logic the DB
 * methods use, and are what the JVM contract test pins down.
 */
class DjmixAnalysisStore(private val db: DjmixDatabase) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // ---- Analysis persistence ---------------------------------------------

    /** Serializes and stores [analysis]; true on success, false when logged out. */
    fun putAnalysis(analysis: TrackAnalysisV3, createdAtMs: Long): Boolean {
        if (analysis.trackId.isBlank()) {
            AnalysisLog.warn("djmix store: putAnalysis with blank trackId, ignored")
            return false
        }
        return runCatching {
            val values = ContentValues().apply {
                put(DjmixDatabase.COL_TRACK_ID, analysis.trackId)
                put(DjmixDatabase.COL_CONTENT_HASH, analysis.contentHash)
                put(DjmixDatabase.COL_SCHEMA_VERSION, analysis.schema)
                put(
                    DjmixDatabase.COL_PAYLOAD,
                    json.encodeToString(TrackAnalysisV3.serializer(), analysis)
                        .toByteArray(Charsets.UTF_8),
                )
                put(
                    DjmixDatabase.COL_MODEL_VERSIONS,
                    json.encodeToString(StringMapSerializer, analysis.modelVersions),
                )
                put(DjmixDatabase.COL_LEVEL, analysis.analysisLevel.name)
                put(DjmixDatabase.COL_CREATED_AT, createdAtMs)
            }
            db.writableDatabase
                .insertWithOnConflict(
                    DjmixDatabase.TABLE_TRACK_ANALYSIS, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                ) != -1L
        }.onFailure { AnalysisLog.warn("djmix store: putAnalysis failed for ${analysis.trackId}", it) }
            .getOrDefault(false)
    }

    /**
     * Reads the newest analysis row for [trackId] (optionally pinned to
     * [expectedContentHash]). Returns [AnalysisLookupResult.Fresh] only when
     * the stored schema is 3, every current model version matches, and the
     * payload decodes; anything else is [AnalysisLookupResult.Stale] with the
     * reason, or [AnalysisLookupResult.Missing] when no row exists.
     */
    fun getAnalysis(
        trackId: String,
        expectedContentHash: String?,
        currentModelVersions: Map<String, String>,
    ): AnalysisLookupResult {
        if (trackId.isBlank()) return AnalysisLookupResult.Missing
        val rowResult = runCatching { readAnalysisRow(trackId, expectedContentHash) }
        val row = rowResult.getOrNull()
        if (row == null) {
            val failure = rowResult.exceptionOrNull()
            if (failure != null) {
                AnalysisLog.warn("djmix store: getAnalysis query failed for $trackId", failure)
                return AnalysisLookupResult.Stale(reason = "store error")
            }
            return AnalysisLookupResult.Missing
        }
        val storedModels = runCatching {
            json.decodeFromString(StringMapSerializer, row.modelVersionsJson)
        }.onFailure { AnalysisLog.warn("djmix store: model_versions unreadable for $trackId", it) }
            .getOrDefault(emptyMap())
        staleReason(row.schemaVersion, storedModels, currentModelVersions)
            ?.let { return AnalysisLookupResult.Stale(reason = it) }
        val analysis = runCatching {
            json.decodeFromString(TrackAnalysisV3.serializer(), row.payload.toString(Charsets.UTF_8))
        }.onFailure { AnalysisLog.warn("djmix store: payload unreadable for $trackId", it) }
            .getOrNull() ?: return AnalysisLookupResult.Stale(reason = "unreadable payload")
        if (analysis.trackId != trackId) {
            return AnalysisLookupResult.Stale(reason = "track mismatch")
        }
        return AnalysisLookupResult.Fresh(analysis = analysis)
    }

    private fun readAnalysisRow(trackId: String, expectedContentHash: String?): AnalysisRow? {
        val selection: String
        val args: Array<String>
        if (expectedContentHash != null) {
            selection = "${DjmixDatabase.COL_TRACK_ID} = ? AND ${DjmixDatabase.COL_CONTENT_HASH} = ?"
            args = arrayOf(trackId, expectedContentHash)
        } else {
            selection = "${DjmixDatabase.COL_TRACK_ID} = ?"
            args = arrayOf(trackId)
        }
        val columns = arrayOf(
            DjmixDatabase.COL_SCHEMA_VERSION,
            DjmixDatabase.COL_PAYLOAD,
            DjmixDatabase.COL_MODEL_VERSIONS,
        )
        db.readableDatabase.query(
            DjmixDatabase.TABLE_TRACK_ANALYSIS, columns, selection, args, null, null,
            "${DjmixDatabase.COL_CREATED_AT} DESC", "1",
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return AnalysisRow(
                schemaVersion = cursor.getInt(0),
                payload = cursor.getBlob(1),
                modelVersionsJson = cursor.getString(2),
            )
        }
    }

    // ---- Stem index ---------------------------------------------------------

    /** Indexes an on-disk stem file; true on success. */
    fun registerStem(
        trackId: String,
        windowStartMs: Long,
        windowEndMs: Long,
        modelId: String,
        filePath: String,
        bytes: Long,
        lastUsedMs: Long = System.currentTimeMillis(),
    ): Boolean = runCatching {
        val values = ContentValues().apply {
            put(DjmixDatabase.COL_TRACK_ID, trackId)
            put(DjmixDatabase.COL_WINDOW_START_MS, windowStartMs)
            put(DjmixDatabase.COL_WINDOW_END_MS, windowEndMs)
            put(DjmixDatabase.COL_MODEL_ID, modelId)
            put(DjmixDatabase.COL_FILE_PATH, filePath)
            put(DjmixDatabase.COL_BYTES, bytes)
            put(DjmixDatabase.COL_LAST_USED, lastUsedMs)
        }
        db.writableDatabase
            .insertWithOnConflict(
                DjmixDatabase.TABLE_STEM_INDEX, null, values,
                SQLiteDatabase.CONFLICT_REPLACE,
            ) != -1L
    }.onFailure { AnalysisLog.warn("djmix store: registerStem failed for $trackId", it) }
        .getOrDefault(false)

    /**
     * Touches `last_used` for the indexed stem and returns its path when the
     * file still exists on disk, null otherwise. A row whose file is gone is
     * pruned, so the index never points at phantom bytes.
     */
    fun touchAndResolveStem(
        trackId: String,
        windowStartMs: Long,
        windowEndMs: Long,
        modelId: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String? = runCatching {
        val path = queryStemPath(trackId, windowStartMs, windowEndMs, modelId) ?: return null
        val file = File(path)
        if (!file.isFile) {
            AnalysisLog.warn("djmix store: stem file missing, pruning row: $path")
            deleteStemRow(trackId, windowStartMs, windowEndMs, modelId)
            return null
        }
        val values = ContentValues().apply {
            put(DjmixDatabase.COL_LAST_USED, nowMs)
        }
        db.writableDatabase.update(
            DjmixDatabase.TABLE_STEM_INDEX, values, stemKeySelection(), stemKeyArgs(
                trackId, windowStartMs, windowEndMs, modelId,
            ),
        )
        path
    }.onFailure { AnalysisLog.warn("djmix store: touchAndResolveStem failed for $trackId", it) }
        .getOrNull()

    /**
     * Evicts oldest-first by `last_used` until the indexed total is within
     * [maxBytes], deleting both files and rows. Returns the remaining indexed
     * total, or -1 when the store query failed (logged).
     */
    fun evictStemsToCap(maxBytes: Long = DEFAULT_STEM_CAP_BYTES): Long {
        val rowsResult = runCatching { readAllStems() }
        val rows = rowsResult.getOrNull()
        if (rows == null) {
            AnalysisLog.warn("djmix store: evictStemsToCap query failed", rowsResult.exceptionOrNull())
            return -1L
        }
        val evicted = selectLruEvictions(rows, maxBytes)
        var remaining = rows.sumOf { it.bytes }
        for (stem in evicted) {
            if (File(stem.filePath).delete()) {
                // Bytes freed with the file; the row delete below is the index truth.
            }
            deleteStemRow(stem.trackId, stem.windowStartMs, stem.windowEndMs, stem.modelId)
            remaining -= stem.bytes
        }
        if (evicted.isNotEmpty()) {
            AnalysisLog.warn("djmix store: evicted ${evicted.size} stems (${evicted.sumOf { it.bytes }} bytes) for cap $maxBytes")
        }
        return remaining
    }

    private fun queryStemPath(
        trackId: String, windowStartMs: Long, windowEndMs: Long, modelId: String,
    ): String? = db.readableDatabase.query(
        DjmixDatabase.TABLE_STEM_INDEX,
        arrayOf(DjmixDatabase.COL_FILE_PATH),
        stemKeySelection(), stemKeyArgs(trackId, windowStartMs, windowEndMs, modelId),
        null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun deleteStemRow(
        trackId: String, windowStartMs: Long, windowEndMs: Long, modelId: String,
    ) {
        runCatching {
            db.writableDatabase.delete(
                DjmixDatabase.TABLE_STEM_INDEX, stemKeySelection(),
                stemKeyArgs(trackId, windowStartMs, windowEndMs, modelId),
            )
        }.onFailure { AnalysisLog.warn("djmix store: deleteStemRow failed for $trackId", it) }
    }

    private fun readAllStems(): List<StemEntry> {
        val columns = arrayOf(
            DjmixDatabase.COL_TRACK_ID, DjmixDatabase.COL_WINDOW_START_MS,
            DjmixDatabase.COL_WINDOW_END_MS, DjmixDatabase.COL_MODEL_ID,
            DjmixDatabase.COL_FILE_PATH, DjmixDatabase.COL_BYTES, DjmixDatabase.COL_LAST_USED,
        )
        val out = ArrayList<StemEntry>()
        db.readableDatabase.query(
            DjmixDatabase.TABLE_STEM_INDEX, columns, null, null, null, null,
            "${DjmixDatabase.COL_LAST_USED} ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += StemEntry(
                    trackId = cursor.getString(0),
                    windowStartMs = cursor.getLong(1),
                    windowEndMs = cursor.getLong(2),
                    modelId = cursor.getString(3),
                    filePath = cursor.getString(4),
                    bytes = cursor.getLong(5),
                    lastUsed = cursor.getLong(6),
                )
            }
        }
        return out
    }

    private fun stemKeySelection(): String =
        "${DjmixDatabase.COL_TRACK_ID} = ? AND ${DjmixDatabase.COL_WINDOW_START_MS} = ? AND " +
            "${DjmixDatabase.COL_WINDOW_END_MS} = ? AND ${DjmixDatabase.COL_MODEL_ID} = ?"

    private fun stemKeyArgs(
        trackId: String, windowStartMs: Long, windowEndMs: Long, modelId: String,
    ): Array<String> = arrayOf(trackId, windowStartMs.toString(), windowEndMs.toString(), modelId)

    // ---- Transition history -------------------------------------------------

    /** Records one executed transition; returns the row id, or -1 when logged out. */
    fun recordTransition(
        outId: String,
        inId: String,
        planId: String,
        recipe: String,
        level: String,
        path: String,
        timingsJson: String,
        outcome: String,
        createdAtMs: Long = System.currentTimeMillis(),
        rating: Int = 0,
    ): Long = runCatching {
        val values = ContentValues().apply {
            put(DjmixDatabase.COL_OUT_ID, outId)
            put(DjmixDatabase.COL_IN_ID, inId)
            put(DjmixDatabase.COL_PLAN_ID, planId)
            put(DjmixDatabase.COL_RECIPE, recipe)
            put(DjmixDatabase.COL_LEVEL, level)
            put(DjmixDatabase.COL_PATH, path)
            put(DjmixDatabase.COL_TIMINGS_JSON, timingsJson)
            put(DjmixDatabase.COL_OUTCOME, outcome)
            put(DjmixDatabase.COL_RATING, rating)
            put(DjmixDatabase.COL_CREATED_AT, createdAtMs)
        }
        db.writableDatabase.insert(DjmixDatabase.TABLE_TRANSITION_HISTORY, null, values)
    }.onFailure { AnalysisLog.warn("djmix store: recordTransition failed for $outId->$inId", it) }
        .getOrDefault(-1L)

    /** Newest-first transition history, at most [limit] rows (default 100). */
    fun recentTransitions(limit: Int = 100): List<TransitionRecord> = runCatching {
        val columns = arrayOf(
            DjmixDatabase.COL_ID, DjmixDatabase.COL_OUT_ID, DjmixDatabase.COL_IN_ID,
            DjmixDatabase.COL_PLAN_ID, DjmixDatabase.COL_RECIPE, DjmixDatabase.COL_LEVEL,
            DjmixDatabase.COL_PATH, DjmixDatabase.COL_TIMINGS_JSON, DjmixDatabase.COL_OUTCOME,
            DjmixDatabase.COL_RATING, DjmixDatabase.COL_CREATED_AT,
        )
        val out = ArrayList<TransitionRecord>()
        db.readableDatabase.query(
            DjmixDatabase.TABLE_TRANSITION_HISTORY, columns, null, null, null, null,
            "${DjmixDatabase.COL_CREATED_AT} DESC, ${DjmixDatabase.COL_ID} DESC", limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                out += TransitionRecord(
                    id = cursor.getLong(0),
                    outId = cursor.getString(1),
                    inId = cursor.getString(2),
                    planId = cursor.getString(3),
                    recipe = cursor.getString(4),
                    level = cursor.getString(5),
                    path = cursor.getString(6),
                    timingsJson = cursor.getString(7),
                    outcome = cursor.getString(8),
                    rating = cursor.getInt(9),
                    createdAt = cursor.getLong(10),
                )
            }
        }
        out
    }.onFailure { AnalysisLog.warn("djmix store: recentTransitions failed", it) }
        .getOrDefault(emptyList())

    /** Sets a transition's rating; true when a row was updated. */
    fun updateRating(id: Long, rating: Int): Boolean = runCatching {
        val values = ContentValues().apply { put(DjmixDatabase.COL_RATING, rating) }
        db.writableDatabase.update(
            DjmixDatabase.TABLE_TRANSITION_HISTORY, values,
            "${DjmixDatabase.COL_ID} = ?", arrayOf(id.toString()),
        ) > 0
    }.onFailure { AnalysisLog.warn("djmix store: updateRating failed for $id", it) }
        .getOrDefault(false)

    // ---- Preferences ----------------------------------------------------------

    /** All stored preference weights by key. */
    fun getPreferenceWeights(): Map<String, Double> = runCatching {
        val out = LinkedHashMap<String, Double>()
        db.readableDatabase.query(
            DjmixDatabase.TABLE_PREFERENCE,
            arrayOf(DjmixDatabase.COL_PREF_KEY, DjmixDatabase.COL_WEIGHT),
            null, null, null, null, null,
        ).use { cursor ->
            while (cursor.moveToNext()) out[cursor.getString(0)] = cursor.getDouble(1)
        }
        out
    }.onFailure { AnalysisLog.warn("djmix store: getPreferenceWeights failed", it) }
        .getOrDefault(emptyMap())

    /**
     * Applies [delta] to [key]'s weight, clamped strictly to
     * [[PREF_WEIGHT_MIN], [PREF_WEIGHT_MAX]], bumps the sample count, and
     * returns the new clamped weight.
     */
    fun updatePreferenceWeight(key: String, delta: Double, nowMs: Long = System.currentTimeMillis()): Double {
        var current = 0.0
        var samples = 0
        runCatching {
            db.readableDatabase.query(
                DjmixDatabase.TABLE_PREFERENCE,
                arrayOf(DjmixDatabase.COL_WEIGHT, DjmixDatabase.COL_SAMPLE_COUNT),
                "${DjmixDatabase.COL_PREF_KEY} = ?", arrayOf(key), null, null, null, "1",
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    current = cursor.getDouble(0)
                    samples = cursor.getInt(1)
                }
            }
        }.onFailure { AnalysisLog.warn("djmix store: updatePreferenceWeight read failed for $key", it) }
        val next = applyPreferenceDelta(current, delta)
        return runCatching {
            val values = ContentValues().apply {
                put(DjmixDatabase.COL_PREF_KEY, key)
                put(DjmixDatabase.COL_WEIGHT, next)
                put(DjmixDatabase.COL_SAMPLE_COUNT, samples + 1)
                put(DjmixDatabase.COL_UPDATED_AT, nowMs)
            }
            db.writableDatabase.insertWithOnConflict(
                DjmixDatabase.TABLE_PREFERENCE, null, values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
            next
        }.onFailure { AnalysisLog.warn("djmix store: updatePreferenceWeight write failed for $key", it) }
            .getOrDefault(current)
    }

    /** Clears learned weights but keeps the legacy-migration marker row. */
    fun resetPreferences(): Boolean = runCatching {
        db.writableDatabase.delete(
            DjmixDatabase.TABLE_PREFERENCE,
            "${DjmixDatabase.COL_PREF_KEY} != ?", arrayOf(MIGRATION_MARKER_KEY),
        )
        true
    }.onFailure { AnalysisLog.warn("djmix store: resetPreferences failed", it) }
        .getOrDefault(false)

    // ---- One-time legacy cache migration -------------------------------------
    //
    // Legacy on-disk layout (read-only reference, see shared/.../smart):
    // - AnalysisStore: `filesDir/smart_analysis/<hash>_<len>.json`, Stored has
    //   no trackId field and SCHEMA_VERSION = 5. Entries cannot be keyed into
    //   track_analysis(track_id, content_hash) and are stale for V3 by
    //   definition, so they are counted stale and deleted — the same fate
    //   AnalysisStore.load gives them on read (ADR 0009).
    // - StemCache: `cacheDir/stems/<trackHash>_<tag>_<sampleRate>_<startSample>_
    //   <frames>.stm`. NOT `filesDir/stems` — that is StemModelStore's ONNX
    //   weights and stays untouched. Valid files move to
    //   noBackupFilesDir/stems/ with stem_index rows keyed by trackHash (the
    //   only identity the filename carries); real-id lookups miss and
    //   re-earn, the safe fallback.
    // Runs at most once: the marker row in `preference` is checked first and
    // written last, so a crash mid-migration can resume.

    /** Runs the one-time legacy cache migration; idempotent via the marker row. */
    fun migrateLegacyCachesOnce(context: Context): LegacyMigrationResult {
        val appContext = context.applicationContext
        if (isMigrationDone()) return LegacyMigrationResult(alreadyMigrated = true)
        val nowMs = System.currentTimeMillis()

        // Legacy analysis JSON: every entry is stale for V3 by definition, so
        // it is counted and deleted (same fate AnalysisStore.load gives them
        // on read, ADR 0009).
        val analysis = runCatching {
            cleanLegacyAnalysisDir(File(appContext.filesDir, LEGACY_ANALYSIS_DIR))
        }.onFailure { AnalysisLog.warn("djmix migration: legacy analysis cleanup failed", it) }
            .getOrDefault(LegacyDirCleanup())

        // Legacy StemCache files: valid ones move under noBackupFilesDir/stems
        // and are indexed; junk is deleted.
        val destDir = stemsDir(appContext).apply { mkdirs() }
        val stems = runCatching {
            migrateLegacyStemFiles(File(appContext.cacheDir, LEGACY_STEM_DIR), destDir, nowMs) { stem, windowStartMs, windowEndMs, target ->
                registerStem(
                    stem.trackHash, windowStartMs, windowEndMs, stem.tag,
                    target.absolutePath, target.length(),
                    target.lastModified().takeIf { it > 0L } ?: nowMs,
                )
            }
        }.onFailure { AnalysisLog.warn("djmix migration: legacy stem migration failed", it) }
            .getOrDefault(LegacyStemMigration())

        markMigrationDone(nowMs)
        return LegacyMigrationResult(
            migratedStems = stems.migratedStems,
            staleAnalysis = analysis.staleAnalysis,
            deletedLegacyFiles = analysis.deletedFiles + stems.deletedFiles,
        )
    }

    private fun isMigrationDone(): Boolean = runCatching {
        db.readableDatabase.query(
            DjmixDatabase.TABLE_PREFERENCE,
            arrayOf(DjmixDatabase.COL_PREF_KEY),
            "${DjmixDatabase.COL_PREF_KEY} = ?", arrayOf(MIGRATION_MARKER_KEY),
            null, null, null, "1",
        ).use { it.moveToFirst() }
    }.onFailure { AnalysisLog.warn("djmix migration: marker check failed", it) }
        .getOrDefault(false)

    private fun markMigrationDone(nowMs: Long) {
        runCatching {
            val values = ContentValues().apply {
                put(DjmixDatabase.COL_PREF_KEY, MIGRATION_MARKER_KEY)
                put(DjmixDatabase.COL_WEIGHT, 1.0)
                put(DjmixDatabase.COL_SAMPLE_COUNT, 1)
                put(DjmixDatabase.COL_UPDATED_AT, nowMs)
            }
            db.writableDatabase.insertWithOnConflict(
                DjmixDatabase.TABLE_PREFERENCE, null, values,
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }.onFailure { AnalysisLog.warn("djmix migration: marker write failed", it) }
    }

    // ---- Row model -------------------------------------------------------------

    private data class AnalysisRow(
        val schemaVersion: Int,
        val payload: ByteArray,
        val modelVersionsJson: String,
    )

    companion object {
        /** Default stem-disk cap: 500 MB (ADR 0006). */
        const val DEFAULT_STEM_CAP_BYTES = 500L * 1024L * 1024L

        const val MIGRATION_MARKER_KEY = "__legacy_migration_v1_done"

        const val PREF_WEIGHT_MIN = -0.3
        const val PREF_WEIGHT_MAX = 0.3

        const val EXPECTED_SCHEMA_VERSION = 3

        internal const val STEMS_DIR = "stems"
        internal const val LEGACY_ANALYSIS_DIR = "smart_analysis"
        internal const val LEGACY_STEM_DIR = "stems"
        internal const val STEM_SUFFIX = ".stm"

        private val StringMapSerializer = MapSerializer(String.serializer(), String.serializer())

        /** `noBackupFilesDir/stems/`: where new stem files live (backups excluded). */
        fun stemsDir(context: Context): File = File(context.noBackupFilesDir, STEMS_DIR)

        /** Clamps a raw value into the preference weight bounds. Non-finite becomes 0. */
        fun clampWeight(value: Double): Double =
            if (!value.isFinite()) 0.0 else value.coerceIn(PREF_WEIGHT_MIN, PREF_WEIGHT_MAX)

        /** New weight after applying [delta]; always inside [[PREF_WEIGHT_MIN], [PREF_WEIGHT_MAX]]. */
        fun applyPreferenceDelta(currentWeight: Double, delta: Double): Double {
            val current = if (currentWeight.isFinite()) currentWeight else 0.0
            return clampWeight(current + delta)
        }

        /**
         * Staleness check for a stored row, or null when fresh. A schema other
         * than 3 is stale; with a non-empty [currentModelVersions], any key in
         * the union whose stored/current values differ is stale.
         */
        fun staleReason(
            storedSchemaVersion: Int,
            storedModelVersions: Map<String, String>,
            currentModelVersions: Map<String, String>,
        ): String? {
            if (storedSchemaVersion != EXPECTED_SCHEMA_VERSION) {
                return "schema $storedSchemaVersion != $EXPECTED_SCHEMA_VERSION"
            }
            if (currentModelVersions.isEmpty()) return null
            for (key in (storedModelVersions.keys + currentModelVersions.keys).sorted()) {
                if (storedModelVersions[key] != currentModelVersions[key]) {
                    return "model $key: stored=${storedModelVersions[key]} current=${currentModelVersions[key]}"
                }
            }
            return null
        }

        /**
         * Stems to evict (oldest-first by [StemEntry.lastUsed]) so the total of
         * the survivors is within [maxBytes]. Pure; [evictStemsToCap] deletes
         * exactly this list.
         */
        fun selectLruEvictions(rows: List<StemEntry>, maxBytes: Long): List<StemEntry> {
            var remaining = rows.sumOf { it.bytes }
            if (remaining <= maxBytes) return emptyList()
            val evicted = ArrayList<StemEntry>()
            for (row in rows.sortedBy { it.lastUsed }) {
                if (remaining <= maxBytes) break
                evicted += row
                remaining -= row.bytes
            }
            return evicted
        }

        /**
         * Parses a legacy StemCache file name
         * `<trackHash>_<tag>_<sampleRate>_<startSample>_<frames>.stm`; null when
         * any part is missing or non-numeric. The tag may contain underscores.
         */
        fun parseLegacyStemName(name: String): LegacyStemName? {
            if (!name.endsWith(STEM_SUFFIX)) return null
            val parts = name.removeSuffix(STEM_SUFFIX).split('_')
            if (parts.size < 5) return null
            val frames = parts.last().toIntOrNull() ?: return null
            val startSample = parts[parts.size - 2].toLongOrNull() ?: return null
            val sampleRate = parts[parts.size - 3].toIntOrNull() ?: return null
            if (sampleRate <= 0 || frames <= 0 || startSample < 0) return null
            val tag = parts.subList(1, parts.size - 3).joinToString("_")
            if (tag.isBlank() || parts.first().isBlank()) return null
            return LegacyStemName(
                trackHash = parts.first(),
                tag = tag,
                sampleRate = sampleRate,
                startSample = startSample,
                frames = frames,
            )
        }

        /**
         * Deletes the legacy analysis cache layout (`filesDir/smart_analysis/`):
         * every `.json` entry counts as stale and is deleted, `.tmp` leftovers
         * from interrupted saves are deleted, and the directory itself is
         * removed when empty. Pure file layout (no DB); unit-tested on the JVM.
         */
        internal fun cleanLegacyAnalysisDir(dir: File): LegacyDirCleanup {
            var staleAnalysis = 0
            var deletedFiles = 0
            dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
                ?.forEach { file ->
                    staleAnalysis++
                    if (file.delete()) deletedFiles++
                }
            dir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }
                ?.forEach { file -> if (file.delete()) deletedFiles++ }
            if (!dir.delete() && dir.exists()) {
                AnalysisLog.warn("djmix migration: legacy analysis dir left non-empty: $dir")
            }
            return LegacyDirCleanup(staleAnalysis = staleAnalysis, deletedFiles = deletedFiles)
        }

        /**
         * Moves valid legacy StemCache files from `cacheDir/stems/` into
         * [destDir], invoking [register] per successfully moved stem with its
         * parsed name and playback window in ms. Files with an unparseable
         * name or bad magic are deleted (the index must never point at
         * phantom bytes), `.tmp` leftovers are deleted, and the directory is
         * removed when empty. Pure file layout (no DB); unit-tested on the JVM.
         */
        internal fun migrateLegacyStemFiles(
            legacyStemDir: File,
            destDir: File,
            nowMs: Long,
            register: (stem: LegacyStemName, windowStartMs: Long, windowEndMs: Long, target: File) -> Boolean,
        ): LegacyStemMigration {
            var migratedStems = 0
            var deletedFiles = 0
            destDir.mkdirs()
            legacyStemDir.listFiles { f -> f.isFile && f.name.endsWith(STEM_SUFFIX) }
                ?.forEach { file ->
                    val stem = parseLegacyStemName(file.name)
                    if (stem == null || !hasStemMagic(file)) {
                        if (file.delete()) deletedFiles++
                        return@forEach
                    }
                    val target = File(destDir, file.name)
                    if (!moveInto(file, target)) {
                        AnalysisLog.warn("djmix migration: could not move legacy stem ${file.name}")
                        return@forEach
                    }
                    val windowStartMs = stem.startSample * 1000L / stem.sampleRate
                    val windowEndMs = windowStartMs + stem.frames * 1000L / stem.sampleRate
                    if (register(stem, windowStartMs, windowEndMs, target)) {
                        migratedStems++
                    } else {
                        AnalysisLog.warn("djmix migration: stem moved but index failed: ${target.name}")
                    }
                }
            legacyStemDir.listFiles { f -> f.isFile && f.name.endsWith(".tmp") }
                ?.forEach { file -> if (file.delete()) deletedFiles++ }
            if (!legacyStemDir.delete() && legacyStemDir.exists()) {
                AnalysisLog.warn("djmix migration: legacy stem dir left non-empty: $legacyStemDir")
            }
            return LegacyStemMigration(migratedStems = migratedStems, deletedFiles = deletedFiles)
        }

        private fun moveInto(source: File, target: File): Boolean = runCatching {
            if (target.exists()) target.delete()
            if (source.renameTo(target)) return true
            // renameTo fails across volumes; both dirs are internal here, but fall
            // back to copy+delete rather than losing the stem.
            source.copyTo(target, overwrite = true)
            source.delete()
            true
        }.onFailure { AnalysisLog.warn("djmix migration: move failed for ${source.name}", it) }
            .getOrDefault(false)

        private fun hasStemMagic(file: File): Boolean = runCatching {
            file.inputStream().use { input ->
                val head = ByteArray(4)
                val read = input.read(head)
                read == 4 && head[0] == 0x42.toByte() && head[1] == 0x43.toByte() &&
                    head[2] == 0x53.toByte() && head[3] == 0x54.toByte() // "BCST", StemCache.MAGIC
            }
        }.getOrDefault(false)
    }
}

/** Typed result of [DjmixAnalysisStore.getAnalysis]. */
sealed interface AnalysisLookupResult {
    /** Stored analysis is schema-3, model-current, and decodable. */
    data class Fresh(val analysis: TrackAnalysisV3) : AnalysisLookupResult

    /** A row exists but must be re-earned; [reason] says why. */
    data class Stale(val reason: String) : AnalysisLookupResult

    /** No row exists for the track (or hash). */
    data object Missing : AnalysisLookupResult
}

/** One `stem_index` row. */
data class StemEntry(
    val trackId: String,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val modelId: String,
    val filePath: String,
    val bytes: Long,
    val lastUsed: Long,
)

/** One `transition_history` row. */
data class TransitionRecord(
    val id: Long,
    val outId: String,
    val inId: String,
    val planId: String,
    val recipe: String,
    val level: String,
    val path: String,
    val timingsJson: String,
    val outcome: String,
    val rating: Int,
    val createdAt: Long,
)

/** Outcome of [DjmixAnalysisStore.migrateLegacyCachesOnce]. */
data class LegacyMigrationResult(
    val migratedStems: Int = 0,
    val staleAnalysis: Int = 0,
    val deletedLegacyFiles: Int = 0,
    val alreadyMigrated: Boolean = false,
)

/** File-layout counts from [DjmixAnalysisStore.cleanLegacyAnalysisDir]. */
data class LegacyDirCleanup(
    val staleAnalysis: Int = 0,
    val deletedFiles: Int = 0,
)

/** File-layout counts from [DjmixAnalysisStore.migrateLegacyStemFiles]. */
data class LegacyStemMigration(
    val migratedStems: Int = 0,
    val deletedFiles: Int = 0,
)

/** Parsed legacy StemCache file name; see [DjmixAnalysisStore.parseLegacyStemName]. */
data class LegacyStemName(
    val trackHash: String,
    val tag: String,
    val sampleRate: Int,
    val startSample: Long,
    val frames: Int,
)
