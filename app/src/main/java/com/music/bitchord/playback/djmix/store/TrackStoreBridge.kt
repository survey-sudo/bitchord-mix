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
import com.music.bitchord.BitChordApplication
import com.music.bitchord.playback.smart.AutomixModels
import com.music.bitchord.playback.smart.PhraseMaps
import com.music.bitchord.playback.smart.TrackAnalysis
import com.music.bitchord.playback.smart.TrackAnalysisProvenance
import com.music.bitchord.playback.smart.structure.AutomixV2Structure
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.security.MessageDigest

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
    /** Optional source/rendition fingerprint supplied by the analyzer. */
    private val contentHashForTrack: (String) -> String? = { null },
    /** Resolved on first background use; the process owner retains store ownership. */
    private val storeProvider: () -> DjmixAnalysisStore? = {
        (context.applicationContext as? BitChordApplication)?.djmixServicesOrNull()?.store
    },
) {

    // ---- AE3 djmix.db store wiring (Wave 3C-1) ------------------------------
    //
    // Lazy because this analyzer is a field
    // initializer on the playback service, which runs before the service has
    // a base context attached. Do not resolve applicationContext at construction.
    // First access happens on a background thread after construction, never
    // in the init block. An unavailable owner leaves the legacy store path active;
    // the bridge never creates or closes a database/store of its own.
    val djmixStore: DjmixAnalysisStore? by lazy {
        runCatching { storeProvider() }
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
     * Current declared model constants, captured only for a new producer pass
     * and retained as declarations on restore. These do not identify
     * executed model bytes, an inference session, provider, or runtime.
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
        val expectedHash = runCatching { contentHashForTrack(trackId) }.getOrNull()?.takeIf { it.isNotBlank() }
        val lookup = runCatching {
            fun read(hash: String?): AnalysisLookupResult {
                // The map also transports reserved provenance metadata. Compare
                // only actual recorded model declarations, never metadata keys.
                val result = activeStore.getAnalysis(trackId, hash, emptyMap())
                val row = (result as? AnalysisLookupResult.Fresh)?.analysis ?: return result
                val declared = TrackAnalysisProvenance.declaredModels(row.modelVersions)
                // Beat This! and Nirovi grids remain valid across model upgrades.
                // Keep their original declarations; schema/grid validation still applies.
                val compatibleBeatModel = declared["beat"] == AutomixModels.BEAT.sha256 ||
                    declared["beat_this"] == AutomixModels.BEAT.sha256 ||
                    declared["nirovi"] == AutomixModels.NIROVI.sha256
                val current = if (compatibleBeatModel) emptyMap() else currentModelVersions()
                val stale = DjmixAnalysisStore.staleReason(row.schema, declared, current)
                return if (stale == null) result else AnalysisLookupResult.Stale(reason = stale)
            }
            val exact = expectedHash?.let {
                read(it)
            }
            val result = exact?.takeIf { it is AnalysisLookupResult.Fresh }
                ?: read(null)
            result to expectedHash
        }.onFailure { Log.w(TAG, "djmix store lookup failed for $trackId", it) }.getOrNull()
            ?: return false
        val fresh = (lookup.first as? AnalysisLookupResult.Fresh)?.analysis ?: return false
        if (!isContentHashCompatible(fresh.contentHash, expectedHash)) return false
        if (!fresh.isUsable) return false
        // Keep the source row's blank hash blank. Today's compatible fingerprint
        // cannot establish the source of a historical, unkeyed analysis.
        val projected = TrackAnalysisV3Mapper.fromV3(fresh)
        val provenance = projected.provenance ?: TrackAnalysisProvenance.UNKNOWN
        val legacyCompatible = expectedHash == null || fresh.contentHash.isBlank()
        val restored = projected.copy(provenance = provenance.copy(
            status = if (legacyCompatible) "legacy_compatible_unverified" else provenance.status,
            hashCompatibility = if (legacyCompatible) "legacy_compatible_unverified" else "matching_declared_hash_unverified",
        ))
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
        val provenance = analysis.provenance ?: return
        val contentHash = provenance.sourceHash?.takeIf { it.isNotBlank() }
            ?: return // Never replace a previously keyed row with an empty hash.
        runCatching {
            val map = PhraseMaps.get(trackId)
            val v3 = TrackAnalysisV3Mapper.toV3(
                trackId = trackId,
                contentHash = contentHash,
                analysis = analysis,
                phraseMap = map,
                songMap = map,
                modelVersions = provenance.declaredModelVersions.orEmpty(),
            )
            if (v3.analysisLevel.name == "NONE") return@runCatching
            activeStore.putAnalysis(v3, System.currentTimeMillis())
        }.onFailure { Log.w(TAG, "djmix store persist failed for $trackId", it) }
    }

    companion object {
        private const val TAG = "BitChordTrackStoreBridge"

        /** Stable SHA-256 key for a source/rendition metadata fingerprint. */
        fun contentHashFromFingerprint(trackId: String, fingerprint: String?): String? {
            val value = fingerprint?.trim().orEmpty()
            if (trackId.isBlank() || value.isBlank()) return null
            val digest = MessageDigest.getInstance("SHA-256").digest("$trackId\u0000$value".toByteArray())
            return digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        }

        /** An empty hash is accepted only as a legacy row; known mismatches are never fresh. */
        fun isContentHashCompatible(storedHash: String, expectedHash: String?): Boolean = when {
            expectedHash.isNullOrBlank() -> true
            storedHash.isBlank() -> true
            else -> storedHash == expectedHash
        }
    }
}
