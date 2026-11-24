package com.music.bitchord.playback.djmix.store

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.music.bitchord.playback.smart.AnalysisLog

/**
 * Persistent `djmix.db` store (Stage 3 Wave 3B-2, B3.1 + ADR 0006).
 *
 * Direct `SQLiteOpenHelper` with WAL mode — the same 4-table schema as the
 * Room design, minus the Kotlin 2.3.20 KSP build-time overhead (ADR 0006
 * addendum). Table/column parity with Room is the contract; the pure-JVM
 * contract test asserts the SQL constants below verbatim.
 *
 * Data access lives in [DjmixAnalysisStore]; this class owns only the
 * database file, its schema, and write-ahead logging setup.
 */
class DjmixDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, SCHEMA_VERSION) {

    init {
        // API 16+; minSdk is 26. WAL lets the queue worker read while the
        // playback path writes, without blocking either.
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // NORMAL synchronous is the WAL companion: durable across process
        // death, without paying FULL's fsync per write.
        runCatching {
            db.execSQL("PRAGMA journal_mode=WAL;")
            db.execSQL("PRAGMA synchronous=NORMAL;")
        }.onFailure { AnalysisLog.warn("djmix.db pragma setup failed", it) }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_TRACK_ANALYSIS)
        db.execSQL(CREATE_STEM_INDEX)
        db.execSQL(CREATE_TRANSITION_HISTORY)
        db.execSQL(CREATE_PREFERENCE)
        db.execSQL(INDEX_TRACK_ANALYSIS_TRACK)
        db.execSQL(INDEX_STEM_LAST_USED)
        db.execSQL(INDEX_STEM_TRACK)
        db.execSQL(INDEX_TRANSITION_CREATED)
        db.execSQL(INDEX_TRANSITION_PAIR)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v1 is the first shipped schema; there is nothing to migrate yet.
        // Future bumps must preserve track_analysis rows (staleness is lazy,
        // never a wipe) — see DjmixAnalysisStore.getAnalysis.
    }

    companion object {
        const val DB_NAME = "djmix.db"
        const val SCHEMA_VERSION = 1

        const val TABLE_TRACK_ANALYSIS = "track_analysis"
        const val TABLE_STEM_INDEX = "stem_index"
        const val TABLE_TRANSITION_HISTORY = "transition_history"
        const val TABLE_PREFERENCE = "preference"

        // Column names shared by DjmixAnalysisStore's SQL.
        const val COL_TRACK_ID = "track_id"
        const val COL_CONTENT_HASH = "content_hash"
        const val COL_SCHEMA_VERSION = "schema_version"
        const val COL_PAYLOAD = "payload"
        const val COL_MODEL_VERSIONS = "model_versions"
        const val COL_LEVEL = "level"
        const val COL_CREATED_AT = "created_at"
        const val COL_WINDOW_START_MS = "window_start_ms"
        const val COL_WINDOW_END_MS = "window_end_ms"
        const val COL_MODEL_ID = "model_id"
        const val COL_FILE_PATH = "file_path"
        const val COL_BYTES = "bytes"
        const val COL_LAST_USED = "last_used"
        const val COL_ID = "id"
        const val COL_OUT_ID = "out_id"
        const val COL_IN_ID = "in_id"
        const val COL_PLAN_ID = "plan_id"
        const val COL_RECIPE = "recipe"
        const val COL_PATH = "path"
        const val COL_TIMINGS_JSON = "timings_json"
        const val COL_OUTCOME = "outcome"
        const val COL_RATING = "rating"
        const val COL_PREF_KEY = "pref_key"
        const val COL_WEIGHT = "weight"
        const val COL_SAMPLE_COUNT = "sample_count"
        const val COL_UPDATED_AT = "updated_at"

        val CREATE_TRACK_ANALYSIS = """
            CREATE TABLE IF NOT EXISTS track_analysis(
              track_id TEXT NOT NULL,
              content_hash TEXT NOT NULL,
              schema_version INTEGER NOT NULL,
              payload BLOB NOT NULL,
              model_versions TEXT NOT NULL,
              level TEXT NOT NULL,
              created_at INTEGER NOT NULL,
              PRIMARY KEY (track_id, content_hash))
        """.trimIndent()

        val CREATE_STEM_INDEX = """
            CREATE TABLE IF NOT EXISTS stem_index(
              track_id TEXT NOT NULL,
              window_start_ms INTEGER NOT NULL,
              window_end_ms INTEGER NOT NULL,
              model_id TEXT NOT NULL,
              file_path TEXT NOT NULL,
              bytes INTEGER NOT NULL,
              last_used INTEGER NOT NULL,
              PRIMARY KEY (track_id, window_start_ms, window_end_ms, model_id))
        """.trimIndent()

        val CREATE_TRANSITION_HISTORY = """
            CREATE TABLE IF NOT EXISTS transition_history(
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              out_id TEXT NOT NULL,
              in_id TEXT NOT NULL,
              plan_id TEXT NOT NULL,
              recipe TEXT NOT NULL,
              level TEXT NOT NULL,
              path TEXT NOT NULL,
              timings_json TEXT NOT NULL,
              outcome TEXT NOT NULL,
              rating INTEGER NOT NULL DEFAULT 0,
              created_at INTEGER NOT NULL)
        """.trimIndent()

        val CREATE_PREFERENCE = """
            CREATE TABLE IF NOT EXISTS preference(
              pref_key TEXT PRIMARY KEY,
              weight REAL NOT NULL,
              sample_count INTEGER NOT NULL,
              updated_at INTEGER NOT NULL)
        """.trimIndent()

        const val INDEX_TRACK_ANALYSIS_TRACK =
            "CREATE INDEX IF NOT EXISTS idx_track_analysis_track ON track_analysis(track_id)"

        // LRU eviction scans in this order; without it every cap check is a sort.
        const val INDEX_STEM_LAST_USED =
            "CREATE INDEX IF NOT EXISTS idx_stem_last_used ON stem_index(last_used)"

        const val INDEX_STEM_TRACK =
            "CREATE INDEX IF NOT EXISTS idx_stem_track ON stem_index(track_id)"

        const val INDEX_TRANSITION_CREATED =
            "CREATE INDEX IF NOT EXISTS idx_transition_created ON transition_history(created_at)"

        const val INDEX_TRANSITION_PAIR =
            "CREATE INDEX IF NOT EXISTS idx_transition_pair ON transition_history(out_id, in_id)"
    }
}
