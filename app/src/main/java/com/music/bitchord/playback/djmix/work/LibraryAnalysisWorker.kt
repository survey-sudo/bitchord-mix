package com.music.bitchord.playback.djmix.work

import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.bitchord.data.OfflinePlaylists
import com.music.bitchord.download.Downloads
import com.music.bitchord.playback.smart.AnalysisLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Decoupled library track source for [LibraryAnalysisWorker] (B3.2 + B15).
 *
 * Implementations provide track IDs (liked songs, downloads, recently played)
 * ordered newest-first without introducing any import dependency on
 * `CrossfadeController`.
 */
interface LibraryTrackProvider {
    suspend fun newestFirstTrackIds(context: Context): List<String>
}

/** Runtime registry for [LibraryTrackProvider]. */
object LibraryTrackProviderRegistry {
    @Volatile
    var provider: LibraryTrackProvider? = null
}

/**
 * Background library full-analysis worker (M5 B3.2 + B15).
 *
 * Runs only under idle, charging, and battery-not-low WorkManager constraints,
 * processing liked songs, downloads, and recently played tracks newest-first via
 * [QueuePrepRegistry.delegate] and yielding cleanly on stop, low battery (< 30%),
 * or moderate+ thermal pressure (API 29+).
 */
class LibraryAnalysisWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val delegate = QueuePrepRegistry.delegate ?: return@withContext Result.success()
        val trackIds = collectNewestFirstTrackIds()
        if (trackIds.isEmpty()) return@withContext Result.success()

        for (id in trackIds) {
            if (!canContinueWork()) {
                return@withContext Result.success()
            }
            runCatching { delegate.ensureFullAnalysis(id) }
                .onFailure { AnalysisLog.warn("LibraryAnalysisWorker analysis failed for $id", it) }
        }
        Result.success()
    }

    private suspend fun collectNewestFirstTrackIds(): List<String> {
        val ordered = LinkedHashSet<String>()
        LibraryTrackProviderRegistry.provider?.let { provider ->
            runCatching { provider.newestFirstTrackIds(applicationContext) }
                .onFailure { AnalysisLog.warn("LibraryTrackProvider failed", it) }
                .getOrNull()
                ?.forEach { id -> if (id.isNotBlank()) ordered.add(id) }
        }
        // Fallback/merge from local liked playlist & verified downloads, newest-first.
        OfflinePlaylists.playlists.value
            .firstOrNull { it.isLiked }
            ?.songs
            ?.forEach { song -> if (song.videoId.isNotBlank()) ordered.add(song.videoId) }

        runCatching { Downloads.getDownloadedSongs(applicationContext) }
            .getOrNull()
            ?.sortedByDescending { it.localDateAddedSeconds ?: it.localDateModifiedSeconds ?: 0L }
            ?.forEach { song -> if (song.videoId.isNotBlank()) ordered.add(song.videoId) }

        return ordered.toList()
    }

    private fun canContinueWork(): Boolean {
        if (isStopped) return false
        val batteryManager = applicationContext.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val capacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (capacity != null && capacity != Int.MIN_VALUE && capacity < MIN_BATTERY_PERCENT) {
            AnalysisLog.warn("LibraryAnalysisWorker paused: battery capacity $capacity% < $MIN_BATTERY_PERCENT%")
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null && powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) {
                AnalysisLog.warn("LibraryAnalysisWorker paused: thermal status=${powerManager.currentThermalStatus}")
                return false
            }
        }
        return true
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK_NAME = "djmix_library_analysis_periodic"
        const val UNIQUE_ONCE_WORK_NAME = "djmix_library_analysis_once"
        const val MIN_BATTERY_PERCENT = 30
        const val PERIODIC_INTERVAL_HOURS = 6L

        private fun constraints(): Constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .setRequiresBatteryNotLow(true)
            .build()

        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<LibraryAnalysisWorker>(
                PERIODIC_INTERVAL_HOURS,
                TimeUnit.HOURS,
            )
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
        }

        fun enqueueOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<LibraryAnalysisWorker>()
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    UNIQUE_ONCE_WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request,
                )
        }
    }
}
