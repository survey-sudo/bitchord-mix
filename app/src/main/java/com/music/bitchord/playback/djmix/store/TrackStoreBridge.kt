/*
 * Copyright (C) 2026 Kushagra Singh
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.music.bitchord.playback.djmix.store

import android.content.Context
import android.util.Log
import com.music.bitchord.playback.smart.AutomixModels
import com.music.bitchord.playback.smart.PhraseMaps
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.structure.AutomixV2Structure
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * Bridge between smart analysis runtime memory state and the persisted
 * djmix.db analysis store (M9 T-2 slice).
 *
 * Handles one-time legacy cache migration, version freshness checks, and
 * bidirectional conversion between [TrackAnalysis] and [DjmixAnalysisStore].
 */
class TrackStoreBridge(
    private val context: Context,
    private val ioExecutor: Executor,
    private val results: ConcurrentHashMap<String, TrackAnalysis>,
    private val provisional: MutableSet<String>,
    private val restoreAttempted: MutableSet<String>,
    private val onAnalysisUpdated: (String) -> Unit,
) {

    // ---- AE3 djmix.db store wiring (Wave 3C-1) ------------------------------
    //
    // Lazy for the same reason [resolver] is: this analyzer is a field
    // initializer on the playback service, which runs before the service has
    // a base context attached, and `DjmixDatabase` needs `applicationContext`.
    // First access happens on a background thread after construction, never
    // in the init block.
    val djmixStore: DjmixAnalysisStore? by lazy {
        runCatching { DjmixAnalysisStore(DjmixDatabase(context)) }
            .onFailure { Log.w(TAG, "djmix store unavailable", it) }
            .getOrNull()
    }

    private val djmixMigrationLock = Any()
    @Volatile private var djmixMigrationTriggered = false

    /**
     * Runs the one-time legacy cache migration exactly once, on the I/O pool.
     * Failures are logged and never break analysis (the legacy
     * [AnalysisStore] path keeps working).
     */
    fun triggerDjmixMigrationOnce() {
        synchronized(djmixMigrationLock) {
            if (djmixMigrationTriggered) return
            djmixMigrationTriggered = true
        }
        try {
            ioExecutor.execute {
                runCatching { djmixStore?.migrateLegacyCachesOnce(context) }
                    .onFailure { Log.w(TAG, "djmix legacy migration failed", it) }
            }
        } catch (error: Throwable) {
            Log.w(TAG, "djmix legacy migration dispatch failed", error)
        }
    }

    /**
     * Model versions a persisted row must match to read as fresh. Keys and
     * values are exactly what [persistToDjmixStore] writes, so a row we wrote
     * ourselves never reads stale against ourselves.
     */
    fun currentModelVersions(): Map<String, String> = mapOf(
        "nirovi" to AutomixModels.NIROVI.sha256,
        "syvenu" to AutomixModels.SYVENU.sha256,
        "structure_v2" to AutomixV2Structure.VERSION.toString(),
    )

    /**
     * Fresh djmix.db lookup for [trackId], restored into [results] and
     * [PhraseMaps] when present. Returns true when a usable restored analysis
     * landed, so compute paths can return before decoding / running ORT.
     * Must be called off the playback thread (it touches SQLite).
     */
    fun restoreFromDjmixStore(trackId: String): Boolean {
        triggerDjmixMigrationOnce()
        val activeStore = djmixStore ?: return false
        val lookup = runCatching {
            activeStore.getAnalysis(trackId, expectedContentHash = null, currentModelVersions())
        }.onFailure { Log.w(TAG, "djmix store lookup failed for $trackId", it) }.getOrNull()
            ?: return false
        val fresh = (lookup as? AnalysisLookupResult.Fresh)?.analysis ?: return false
        if (!fresh.isUsable) return false
        val restored = TrackAnalysisV3Mapper.fromV3(fresh)
        if (!restored.isUsable) return false
        // A stored FULL supersedes a provisional HEAD in memory; anything
        // else only lands when nothing is known yet (putIfAbsent).
        val supersedesProvisional = fresh.analysisLevel.name == "FULL" &&
            trackId in provisional && results[trackId]?.quality != TrackAnalysis.QUALITY_FULL
        if (supersedesProvisional) {
            results[trackId] = restored
            provisional.remove(trackId)
            TrackAnalysisV3Mapper.toPhraseMap(fresh)?.let { PhraseMaps.put(trackId, it) }
            restoreAttempted.add(trackId)
            Log.d(TAG, "Restored djmix FULL analysis for $trackId: bpm=${restored.bpm} conf=${restored.beatConfidence} level=${fresh.analysisLevel}")
            onAnalysisUpdated(trackId)
        } else if (results.putIfAbsent(trackId, restored) == null) {
            TrackAnalysisV3Mapper.toPhraseMap(fresh)?.let { PhraseMaps.put(trackId, it) }
            restoreAttempted.add(trackId)
            Log.d(TAG, "Restored djmix analysis for $trackId: bpm=${restored.bpm} conf=${restored.beatConfidence} level=${fresh.analysisLevel}")
            onAnalysisUpdated(trackId)
        }
        return trackId !in provisional && results[trackId]?.isUsable == true
    }

    /**
     * Persists a freshly computed [analysis] (`HEAD` -> `HEAD_TAIL`, `FULL`
     * -> `FULL`) into djmix.db. Never overwrites a FULL result in memory
     * with a HEAD one. Failures are logged; the legacy [AnalysisStore] write
     * is unaffected.
     */
    fun persistToDjmixStore(trackId: String, analysis: TrackAnalysis) {
        if (analysis.quality == TrackAnalysis.QUALITY_HEAD &&
            results[trackId]?.quality == TrackAnalysis.QUALITY_FULL
        ) return
        val activeStore = djmixStore ?: return
        runCatching {
            val map = PhraseMaps.get(trackId)
            val v3 = TrackAnalysisV3Mapper.toV3(
                trackId = trackId,
                contentHash = "",
                analysis = analysis,
                phraseMap = map,
                songMap = map,
                modelVersions = currentModelVersions(),
            )
            if (v3.analysisLevel.name == "NONE") return@runCatching
            activeStore.putAnalysis(v3, System.currentTimeMillis())
        }.onFailure { Log.w(TAG, "djmix store persist failed for $trackId", it) }
    }

    private companion object {
        const val TAG = "BitChordTrackStoreBridge"
    }
}
