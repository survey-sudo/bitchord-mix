package com.music.bitchord.playback.djmix.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.music.bitchord.playback.djmix.PrepThermalGate
import com.music.bitchord.playback.smart.AnalysisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decoupled preparation surface for [QueueAnalysisWorker] (B3.2 queue job).
 *
 * The playback service/preparer registers an implementation at runtime in
 * [QueuePrepRegistry]. The indirection keeps this worker free of any import
 * cycle back into `CrossfadeController` / `PairPreparer`.
 */
interface QueuePrepDelegate {
    /**
     * Raises [trackId] analysis from `HEAD_TAIL` to `FULL` if needed.
     * Returns true when full analysis is available afterwards.
     */
    suspend fun ensureFullAnalysis(trackId: String): Boolean

    /** Plans the next transition for the pair and prepares its stems. */
    suspend fun preparePairAndStems(outId: String, inId: String)
}

/** Runtime holder for the [QueuePrepDelegate]; null until the service registers one. */
object QueuePrepRegistry {
    @Volatile var delegate: QueuePrepDelegate? = null
}

/**
 * WorkManager queue job (B3.2.1): on queue change, analyse the next 5 tracks
 * to `FULL` and plan + stem-prep the next 2 pairs, so cold pairs mostly start
 * READY. Runs on [Dispatchers.IO] at background priority with no network
 * constraint; per-item failures are logged and skipped, never retried.
 * New items wait at SEVERE until MODERATE or cooler; active work/mixes are untouched.
 */
class QueueAnalysisWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ids = analysisTargets(inputData.getStringArray(KEY_TRACK_IDS)?.toList().orEmpty())
        if (ids.isEmpty()) return Result.success()
        val delegate = QueuePrepRegistry.delegate ?: return Result.success()
        withContext(Dispatchers.IO) {
            for (id in ids) {
                if (!awaitThermalBudget()) return@withContext
                runCatching { delegate.ensureFullAnalysis(id) }
                    .onFailure { AnalysisLog.warn("djmix queue worker: analysis failed for $id", it) }
            }
            for ((outId, inId) in pairTargets(ids)) {
                if (!awaitThermalBudget()) return@withContext
                runCatching { delegate.preparePairAndStems(outId, inId) }
                    .onFailure { AnalysisLog.warn("djmix queue worker: pair prep failed for $outId->$inId", it) }
            }
        }
        // Never a retry: the next queue change re-enqueues with fresh ids, and
        // a poisoned track would otherwise make WorkManager loop on it.
        return Result.success()
    }

    private suspend fun awaitThermalBudget(): Boolean = PrepThermalGate.awaitReady(
        status = { PrepThermalGate.currentStatus(applicationContext) },
        isAborted = { isStopped },
    )

    companion object {
        const val UNIQUE_WORK_NAME = "djmix_queue_analysis"
        const val KEY_TRACK_IDS = "track_ids"

        /** How many upcoming tracks one run lifts to FULL analysis. */
        const val QUEUE_ANALYSIS_BUDGET = 1

        /** How many upcoming pairs one run plans and stem-preps. */
        const val PAIR_PREP_BUDGET = 2

        /** Upcoming tracks one run lifts to FULL: non-blank, first [QUEUE_ANALYSIS_BUDGET]. Pure; unit-tested. */
        fun analysisTargets(queueTrackIds: List<String>): List<String> =
            queueTrackIds.filter { it.isNotBlank() }.take(QUEUE_ANALYSIS_BUDGET)

        /** Upcoming pairs one run plans and stem-preps, in queue order. Pure; unit-tested. */
        fun pairTargets(queueTrackIds: List<String>): List<Pair<String, String>> =
            analysisTargets(queueTrackIds).zipWithNext().take(PAIR_PREP_BUDGET)

        /**
         * Enqueues a queue-scan run, replacing any still-waiting one so the
         * newest queue always wins. No-op for an empty queue.
         */
        fun enqueue(context: Context, queueTrackIds: List<String>) {
            val ids = analysisTargets(queueTrackIds)
            if (ids.isEmpty()) return
            val request = OneTimeWorkRequestBuilder<QueueAnalysisWorker>()
                .setInputData(workDataOf(KEY_TRACK_IDS to ids.toTypedArray()))
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
