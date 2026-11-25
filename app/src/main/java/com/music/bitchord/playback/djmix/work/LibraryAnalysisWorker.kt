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
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.smart.AnalysisLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Decoupled library track source for [LibraryAnalysisWorker] (B3.2 + B15).
 *
 * Implementations provide locally available track IDs ordered newest-first,
 * without introducing any import dependency on `CrossfadeController`. This
 * optional ID-only hook has no cross-source timestamps: known local dates take
 * precedence, with provider rank retained for ties and undated tracks.
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
 * processing local liked songs, downloads, and played cache entries via
 * [QueuePrepRegistry.delegate]. Available local dates sort newest-first;
 * undated tracks retain stable source rank, not an invented global play/like
 * chronology. Yields cleanly on stop, low battery (< 30%), or moderate+ thermal
 * pressure (API 29+).
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
            if (!canContinueWork()) return@withContext Result.success()
            try {
                delegate.ensureFullAnalysis(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                AnalysisLog.warn("LibraryAnalysisWorker analysis failed for $id", failure)
            }
            currentCoroutineContext().ensureActive()
        }
        Result.success()
    }

    private suspend fun collectNewestFirstTrackIds(): List<String> {
        if (!canContinueWork()) return emptyList()
        val providerIds = readLibrarySource("LibraryTrackProvider failed") {
            LibraryTrackProviderRegistry.provider?.newestFirstTrackIds(applicationContext).orEmpty()
        }
        if (!canContinueWork()) return emptyList()
        // Production source needs no registry assignment. cachedSongs only lists
        // played entries with bytes still on disk, never analysis-only/read-ahead.
        val recent = readLibrarySource("djmix library worker: played cache unreadable") {
            AudioCache.cachedSongs()
        }
        if (!canContinueWork()) return emptyList()
        val liked = readLibrarySource("djmix library worker: liked playlists unreadable") {
            OfflinePlaylists.playlists.value.firstOrNull { it.isLiked }?.songs.orEmpty()
        }
        if (!canContinueWork()) return emptyList()
        val downloads = readLibrarySource("djmix library worker: downloads unreadable") {
            Downloads.getDownloadedSongs(applicationContext)
        }
        if (!canContinueWork()) return emptyList()

        val localDatesMs = HashMap<String, Long>()
        fun rememberDate(id: String, dateMs: Long?) {
            if (id.isBlank() || dateMs == null || dateMs <= 0L) return
            localDatesMs[id] = maxOf(localDatesMs[id] ?: dateMs, dateMs)
        }
        // Cache last use is a read/write timestamp, not an exact playback time.
        recent.forEach { rememberDate(it.song.videoId, it.lastUsedMs) }
        for (song in liked + downloads) {
            // These are file/download dates, NOT like timestamps. Likes only
            // record their relative order; missing dates stay missing.
            val seconds = song.localDateAddedSeconds?.takeIf { it > 0L }
                ?: song.localDateModifiedSeconds?.takeIf { it > 0L }
            rememberDate(song.videoId, seconds?.let { TimeUnit.SECONDS.toMillis(it) })
        }
        return mergeNewestFirst(
            providerIds = providerIds,
            likedIds = liked.map { it.videoId },
            downloadIdsNewestFirst = downloads.map { it.videoId },
            recentIdsNewestFirst = recent.map { it.song.videoId },
            localDatesMs = localDatesMs,
        )
    }

    private suspend fun <T> readLibrarySource(message: String, read: suspend () -> List<T>): List<T> =
        try {
            currentCoroutineContext().ensureActive()
            read().also { currentCoroutineContext().ensureActive() }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            AnalysisLog.warn(message, failure)
            emptyList()
        }

    private suspend fun canContinueWork(): Boolean {
        currentCoroutineContext().ensureActive()
        val reason = yieldReason(
            isStopped = isStopped,
            batteryCapacityPercent = batteryCapacityPercent(applicationContext),
            thermalStatus = currentThermalStatus(applicationContext),
        ) ?: return true
        // Yield cleanly, never a retry: the next scheduled/enqueued run resumes
        // the backlog once charged/cooled. A running mix is never touched.
        AnalysisLog.warn("LibraryAnalysisWorker paused: $reason")
        return false
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK_NAME = "djmix_library_analysis_periodic"
        const val UNIQUE_ONCE_WORK_NAME = "djmix_library_analysis_once"
        const val MIN_BATTERY_PERCENT = 30
        const val PERIODIC_INTERVAL_HOURS = 6L

        /**
         * Reason the worker must yield before analysing the next track, or
         * null when it may continue. Pure so the JVM unit test can pin the
         * B3.2.2 / B15.4 rules exactly: stopped, battery below
         * [MIN_BATTERY_PERCENT], or thermal status at or above MODERATE all
         * yield; unknown readings (null) never yield on their own.
         */
        fun yieldReason(
            isStopped: Boolean,
            batteryCapacityPercent: Int?,
            thermalStatus: Int?,
        ): String? = when {
            isStopped -> "stopped"
            batteryCapacityPercent != null && batteryCapacityPercent < MIN_BATTERY_PERCENT ->
                "battery $batteryCapacityPercent% < $MIN_BATTERY_PERCENT%"
            thermalStatus != null && thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE ->
                "thermal status $thermalStatus >= MODERATE"
            else -> null
        }

        /** Battery capacity percent (1-100), or null when unknown/unsupported. */
        fun batteryCapacityPercent(context: Context): Int? {
            val batteryManager =
                context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
            val capacity = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            return if (capacity == Int.MIN_VALUE) null else capacity
        }

        /** Current thermal status, or null below API 29 / when PowerManager is unavailable. */
        fun currentThermalStatus(context: Context): Int? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
            return powerManager.currentThermalStatus
        }

        /**
         * Merge by the newest known local date per ID (epoch millis), descending.
         * Dates mean cache last use or file/download added/modified time, never
         * a fabricated last-played/liked time. Undated IDs follow dated IDs.
         * Ties/undated IDs retain provider, recent-cache, liked, download order
         * and each source's rank. This is not a total play/like chronology:
         * the ID-only provider and liked order cannot supply that precision.
         * Blanks are removed and each ID is analysed once, at its newest date.
         * With no dates/recent source, retains the original ID-only merge order.
         */
        fun mergeNewestFirst(
            providerIds: List<String>,
            likedIds: List<String>,
            downloadIdsNewestFirst: List<String>,
            recentIdsNewestFirst: List<String> = emptyList(),
            localDatesMs: Map<String, Long> = emptyMap(),
        ): List<String> {
            val ordered = LinkedHashSet<String>()
            for (id in providerIds + recentIdsNewestFirst + likedIds + downloadIdsNewestFirst) {
                if (id.isNotBlank()) ordered.add(id)
            }
            return ordered.sortedByDescending { localDatesMs[it]?.takeIf { date -> date > 0L } }
        }

        /** B3.2.2: charging + idle + battery-not-low, so the job only runs when the device is parked. */
        internal fun constraints(): Constraints = Constraints.Builder()
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
