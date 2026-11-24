package com.music.bitchord.playback.djmix.diagnostics

import com.music.bitchord.djmix.core.contracts.DjLevel
import com.music.bitchord.djmix.core.contracts.ExecutionPath
import com.music.bitchord.djmix.core.contracts.MixTelemetryV3
import com.music.bitchord.djmix.core.contracts.RefusalReason
import com.music.bitchord.djmix.core.contracts.TelemetryEventType
import com.music.bitchord.playback.smart.AnalysisLog
import kotlinx.serialization.json.Json
import java.io.File

/**
 * JSONL sink for [MixTelemetryV3] records (diagnostics, Part B7 / M8).
 *
 * Files (rotating, 10 MB x 3):
 * - `automix-telemetry-v3.jsonl` (active)
 * - `automix-telemetry-v3.jsonl.1`
 * - `automix-telemetry-v3.jsonl.2`
 *
 * Rotation happens *before* an append that would exceed [maxFileBytes]: `.1` is
 * discarded, the active file becomes `.1`, and a fresh active file is created, so
 * at most 3 files / ~30 MB are kept. Every record is stamped with
 * `engine = "3.0.0"` by the [MixTelemetryV3] default; [write] rejects records with
 * a foreign engine so diagnostics bundles never mix schemas.
 *
 * [recentLines]/[recentRecords] expose a 5-minute in-memory ring buffer used by
 * `DiagnosticsBundler` for the "Flag this mix" bundle (`telemetry_5min.jsonl`).
 * The buffer is bounded ([MAX_RING_ENTRIES]) and evicts on age and count. The
 * writer never throws: I/O failures are logged through [AnalysisLog] and dropped,
 * telemetry must not break playback.
 *
 * All public methods are thread-safe (single internal lock; telemetry is emitted
 * from the `MixMachine` reducer thread and the executor thread).
 */
class TelemetryV3Writer(
    val telemetryDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
) {

    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Active telemetry file (`automix-telemetry-v3.jsonl`). */
    val telemetryFile: File = File(telemetryDir, TELEMETRY_FILENAME)

    private val rotation1: File = File(telemetryDir, "$TELEMETRY_FILENAME.1")
    private val rotation2: File = File(telemetryDir, "$TELEMETRY_FILENAME.2")

    private data class BufferedLine(val timestampMs: Long, val line: String)

    private val ring = ArrayDeque<BufferedLine>()

    init {
        runCatching { telemetryDir.mkdirs() }
            .onFailure { AnalysisLog.warn("djmix telemetry: could not create dir $telemetryDir", it) }
    }

    /** Appends [record] as one JSONL line; false when the record was rejected or the write failed. */
    fun write(record: MixTelemetryV3): Boolean {
        if (record.engine != ENGINE_EXPECTED) {
            AnalysisLog.warn("djmix telemetry: rejecting record with engine=${record.engine}")
            return false
        }
        val nowMs = clock()
        val line = runCatching { json.encodeToString(MixTelemetryV3.serializer(), record) }
            .onFailure { AnalysisLog.warn("djmix telemetry: encode failed", it) }
            .getOrNull() ?: return false
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        synchronized(lock) {
            evictExpiredLocked(nowMs)
            ring.addLast(BufferedLine(timestampMs = nowMs, line = line))
            while (ring.size > MAX_RING_ENTRIES) ring.removeFirst()
            return runCatching {
                if (!telemetryDir.isDirectory) telemetryDir.mkdirs()
                val currentLength = if (telemetryFile.isFile) telemetryFile.length() else 0L
                if (currentLength > 0L && currentLength + bytes.size > maxFileBytes) {
                    rotateLocked()
                }
                telemetryFile.appendBytes(bytes)
                true
            }.onFailure { AnalysisLog.warn("djmix telemetry: write failed", it) }
                .getOrDefault(false)
        }
    }

    /**
     * Convenience factory + [write] for callers that only have the moving parts.
     * [timestampMs] defaults to [clock]; everything else mirrors [MixTelemetryV3].
     */
    fun emit(
        eventType: TelemetryEventType,
        state: String,
        timestampMs: Long = clock(),
        planId: String? = null,
        generation: Long = 0L,
        outId: String = "",
        inId: String = "",
        djLevel: DjLevel? = null,
        path: ExecutionPath? = null,
        recipeId: String? = null,
        countdownBroken: Boolean? = null,
        analysisMs: Long? = null,
        planMs: Long? = null,
        stemMsOut: Long? = null,
        stemMsIn: Long? = null,
        buildMs: Long? = null,
        preparedLeadSec: Double? = null,
        armLeadMs: Long? = null,
        reason: RefusalReason? = null,
        detail: String? = null,
    ): Boolean = write(
        MixTelemetryV3(
            timestampMs = timestampMs,
            eventType = eventType,
            planId = planId,
            generation = generation,
            state = state,
            outId = outId,
            inId = inId,
            djLevel = djLevel,
            path = path,
            recipeId = recipeId,
            countdownBroken = countdownBroken,
            analysisMs = analysisMs,
            planMs = planMs,
            stemMsOut = stemMsOut,
            stemMsIn = stemMsIn,
            buildMs = buildMs,
            preparedLeadSec = preparedLeadSec,
            armLeadMs = armLeadMs,
            reason = reason,
            detail = detail,
        ),
    )

    /** JSONL lines from the last 5 minutes (oldest first); the most recent [MAX_RING_ENTRIES] at most. */
    fun recentLines(nowMs: Long = clock()): List<String> = synchronized(lock) {
        evictExpiredLocked(nowMs)
        ring.map { it.line }
    }

    /** Decoded [MixTelemetryV3] records from the last 5 minutes (oldest first). */
    fun recentRecords(nowMs: Long = clock()): List<MixTelemetryV3> =
        recentLines(nowMs).mapNotNull { line ->
            runCatching { json.decodeFromString(MixTelemetryV3.serializer(), line) }.getOrNull()
        }

    /** Number of records currently in the 5-minute ring buffer. */
    fun bufferedCount(nowMs: Long = clock()): Int = synchronized(lock) {
        evictExpiredLocked(nowMs)
        ring.size
    }

    private fun evictExpiredLocked(nowMs: Long) {
        val cutoff = nowMs - RING_WINDOW_MS
        while (ring.isNotEmpty() && ring.first().timestampMs < cutoff) {
            ring.removeFirst()
        }
    }

    private fun rotateLocked() {
        runCatching {
            rotation2.delete()
            if (rotation1.isFile) rotation1.renameTo(rotation2)
            if (telemetryFile.isFile) telemetryFile.renameTo(rotation1)
        }.onFailure { AnalysisLog.warn("djmix telemetry: rotation failed", it) }
    }

    companion object {
        /** Active file name inside [telemetryDir]. */
        const val TELEMETRY_FILENAME: String = "automix-telemetry-v3.jsonl"

        /** Expected `engine` stamp on every record (Part B16). */
        const val ENGINE_EXPECTED: String = "3.0.0"

        /** Per-file rotation cap: 10 MB. */
        const val DEFAULT_MAX_FILE_BYTES: Long = 10L * 1024L * 1024L

        /** How long ring-buffer records are kept for "Flag this mix". */
        const val RING_WINDOW_MS: Long = 5L * 60L * 1000L

        /** Hard cap on buffered records so a burst cannot grow the ring without bound. */
        const val MAX_RING_ENTRIES: Int = 10_000
    }
}
