/*
 * Modeled on Orchard's own TrackAnalyzer (https://github.com/SFG5453/Orchard).
 * Phase 1 was the DSP-only pass (native/analyzer/audio_analysis.cpp); Phase 2/3
 * run Nirovi ([BeatTracker]) and Syvenu ([VocalPresence]) over a single shared
 * mono [MelSpectrogram], removing open-unmix from analysis ([VocalTracker] stays
 * only for the 2-stem render fallback).
 *
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

package com.music.bitchord.playback.smart

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.util.UnstableApi
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.listentogether.ListenTogether
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.data.sources.SourceResolver
import com.music.bitchord.data.sources.TrackMatcher
import com.music.bitchord.playback.AudioCache
import com.music.bitchord.playback.djmix.npu.InferenceBackend
import com.music.bitchord.playback.djmix.npu.NpuAddon
import com.music.bitchord.playback.djmix.npu.SelfTestRecord
import com.music.bitchord.playback.djmix.store.TrackStoreBridge
import com.music.bitchord.playback.djmix.work.QueuePrepDelegate
import com.music.bitchord.playback.djmix.work.QueuePrepRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Produces [TrackAnalysis] for tracks that are about to be mixed, and hands it
 * to [TransitionPlanner].
 *
 * [analysisFor] is called from the crossfade watcher every tick, so it never
 * blocks or computes: it returns what is already known, and an unanalysed
 * track simply reads as no evidence, which the policy ladder answers with a
 * plain fade.
 */
/** vc74 (djmix32): whole-track analysis pool - exactly one pass at a time.
 * Whole-track passes share [modelLock] and ORT's per-model threads, and on a
 * hot or backgrounded phone concurrent passes produced a uniform 6x slowdown in
 * both mel (native DSP) and infer. Pinned as an internal factory so the pool
 * size is unit-testable in Djmix32AppTest. */
internal fun wholeTrackExecutor(): java.util.concurrent.ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bitchord-smart-analysis").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

@UnstableApi
class TrackAnalyzer(private val context: Context, private val cache: AudioCache) {

    /**
     * Dedicated bounded I/O pool for [AnalysisStore.load] and head fetches.
     * Cache restores must never queue behind 10-30s compute passes on the
     * whole-track / head executors.
     */
    private val ioExecutor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "bitchord-smart-analysis-io").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    private val stopped: Boolean get() = ListenTogether.state.value.inParty

    /** The last thermal status the system reported; see [overheated]. */
    @Volatile private var thermalStatus = 0

    /**
     * Registered on first use rather than at construction, for the reason
     * [resolver] gives: there is no base context to ask for a service yet.
     */
    private val thermalWatch by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.let { power ->
                thermalStatus = power.currentThermalStatus
                power.addThermalStatusListener(Runnable::run) { thermalStatus = it }
            }
        }
    }

    /**
     * Whether the phone has asked apps to shed load. No new pass is started
     * while it has: a pass is tens of seconds of decoding and inference, and
     * started on a phone already throttled to half its clocks it is the load
     * that keeps it there — measured, a session of back-to-back analyses held
     * a phone at SEVERE with every frame of the UI missing its vsync. A track
     * left unmeasured gets a plain crossfade, and is measured on a later tick
     * once the phone has cooled. A pass already running finishes.
     */
    private val overheated: Boolean
        get() {
            thermalWatch
            return thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        }

    private val npuStemBridge = NpuAndStemBridge(
        context = context,
        ioExecutor = ioExecutor,
        isStopped = { stopped },
        isOverheated = { overheated },
    )

    private val beatLock = Any()
    private val presenceLock = Any()
    private val vocalLock = Any()
    val stemLock = Any()

    private val tracker = BeatTracker(
        modelPath = { AutomixModels.path(AutomixModels.NIROVI) },
        inferenceThreads = { BeatTracker.DEFAULT_INTRA_OP_THREADS },
        npuVendor = { npuStemBridge.npuVendor },
        npuBackendPath = { npuStemBridge.npuBackendPath },
        npuCacheDir = { npuStemBridge.npuCacheDir },
        fallbackModelPath = { AutomixModels.verifiedPathOrNull(AutomixModels.BEAT) },
    )
    private val vocalPresence = VocalPresence(
        modelPath = { AutomixModels.path(AutomixModels.SYVENU) },
        inferenceThreads = { VocalPresence.DEFAULT_INTRA_OP_THREADS },
        npuVendor = { npuStemBridge.npuVendor },
        npuBackendPath = { npuStemBridge.npuBackendPath },
        npuCacheDir = { npuStemBridge.npuCacheDir },
    )
    /** UMX vocal model kept ONLY for 2-stem render fallback (detached from analysis). */
    private val vocals = VocalTracker(
        modelPath = { AutomixModels.path(AutomixModels.VOCALS) },
        inferenceThreads = { AppSettings.automixPerformanceMode.value.inferenceThreads },
        npuVendor = { npuStemBridge.npuVendor },
        npuBackendPath = { npuStemBridge.npuBackendPath },
        npuCacheDir = { npuStemBridge.npuCacheDir },
    )

    // 4-stem separation uses the coordinator's verified SCNet copy, with the umxhq 2-stem fallback.
    // All lazy: modelLock/executor are declared later in the class, and nothing may touch
    // the context before the service is attached.
    val stemModels by lazy {
        StemModelStore(
            directory = { File(context.filesDir, "stems").apply { mkdirs() } },
            coordinator = AutomixModels,
        )
    }
    private val stemEngines by lazy {
        StemEngines(
            store = stemModels,
            dsp = { StemNative },
            inferenceThreads = { 4 },
            npuVendor = { npuStemBridge.npuVendor },
            npuBackendPath = { npuStemBridge.npuBackendPath },
            npuCacheDir = { npuStemBridge.npuCacheDir },
        )
    }
    val stemSeparator by lazy {
        StemSeparator(
            engine = { stemEngines.current() },
            legacy = LegacyTwoStem { l, r, rate -> vocals.separateBothWindows(l, r, rate) },
            cache = StemCache({ File(context.cacheDir, "stems") }),
            modelLock = stemLock,
            shouldStop = { stopped || overheated },
            legacyLock = vocalLock,
        )
    }

    /** The trusted add-on resolved this session, or null (M7 glue / diagnostics). */
    fun npuAddon(): NpuAddon? = npuStemBridge.npuAddon()

    /** The persisted self-test verdict consulted this session (M7 glue / diagnostics). */
    fun npuSelfTestVerdict(): SelfTestRecord? = npuStemBridge.npuSelfTestVerdict()

    /** M6 status line for Settings / the Mix Inspector; mirrors [NpuSelfTestRunner.statusLabel]. */
    fun npuStatusLabel(): String = npuStemBridge.npuStatusLabel()

    /** Human-readable reason for the most recent backend decision (diagnostics). */
    fun npuSelectionDetail(): String = npuStemBridge.npuSelectionDetail()

    /** The M6 inference backend for [role] ("beat" / "vocal"), defaulting cleanly to CPU ORT. */
    fun selectInferenceBackend(role: String): InferenceBackend = npuStemBridge.selectInferenceBackend(role)

    /** Whether v2 stems are enabled and allowed on this device and analyzer isn't stopped/thermal-throttled. */
    fun canUseStems(): Boolean = npuStemBridge.canUseStems()

    /** AUTOMIX-V2(orch): per-deck provider; null when stems are not allowed on this device. */
    fun stemProviderFor(trackId: String, cachedOnly: Boolean): StemProvider? {
        if (!AutomixV2Stems.enabled || !npuStemBridge.canUseV2Stems()) return null
        return if (cachedOnly) stemSeparator.cachedOnly(trackId) else stemSeparator.forTrack(trackId)
    }

    /** Queues the coordinator's gated batch; true only when SCNet is already verified. No independent HTTP. */
    fun installStemModel(): Boolean = runCatching { stemModels.install(StemModels.SCNET_INT8) }.getOrDefault(false)

    init {
        // The shared trackers report model failures and their timings through
        // this; with no sink they went nowhere on the phone, which is how a
        // beat model that found nothing went unnoticed.
        AnalysisLog.install { message, error -> Log.w(TAG, message, error) }
        // AE3 M6: the bundled vc93 QNN runtime preflight / self-test scheduling
        // that used to run here is replaced by the add-on bridge above. It is
        // deliberately NOT armed in <init>: this analyzer is constructed
        // before the service's base context is attached (vc70).
    }

    /**
     * Resolved on first use, not at construction, for the reason [AnalysisStore]
     * documents about its own directory: this class is a field initializer on
     * the playback service, which runs before the service has a base context
     * attached, and asking a context for anything there returns null.
     */
    private val resolver by lazy { context.contentResolver }

    private val results = ConcurrentHashMap<String, TrackAnalysis>()
    private val running = ConcurrentHashMap.newKeySet<String>()

    /**
     * Emitted (`trackId`) whenever any analysis state changes: cache restore,
     * grid-ready intermediate, head publish, whole-track complete, or terminal
     * failure/stalled. Collectors (Wave 2 L2/L3) re-read [analysisFor] on emit
     * instead of polling every tick.
     */
    private val _analysisUpdates = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val analysisUpdates: SharedFlow<String> = _analysisUpdates

    /** Terminal-failure / stalled ids for this session (bounded; retried only on explicit change). */
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val stalled = ConcurrentHashMap.newKeySet<String>()
    /** Ids [cancel] was called for; checked between Pass-2 regions. */
    private val cancelled = ConcurrentHashMap.newKeySet<String>()

    /** Most recently queued track and the request sequence it was queued with. */
    private val requestSeq = AtomicLong(0L)
    @Volatile private var lastRequestedTrackId: String? = null

    /** True on the Pass-1 (head) thread: bounded DEFAULT-priority boost, restored in finally. */
    private val urgentPass1Thread = ThreadLocal<Boolean>()

    /** Delayed ORT-session release: models stay loaded across tracks, reaped after idle. */
    private val reaperExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "bitchord-smart-analysis-reaper").apply { isDaemon = true }
    }
    private val reaperLock = Any()
    private var modelReaper: java.util.concurrent.ScheduledFuture<*>? = null

    /** Tracks analysing a source/playback URI as an in-memory provisional while a canonical match is pending/failed. */
    private val sourcePending = ConcurrentHashMap.newKeySet<String>()
    /** trackId -> rendition key the provisional is tagged with (never persisted). */
    private val sourceProvisionalKeys = ConcurrentHashMap<String, String>()
    private val sourceAnalysisUris = ConcurrentHashMap<String, Uri>()
    private val sourceResolutions = ConcurrentHashMap.newKeySet<String>()
    private val sourceResolutionAttempted = ConcurrentHashMap.newKeySet<String>()
    private val sourceResolutionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Tracks whose result came from [HeadAnalyzer.analyzeHead] and are waiting to be superseded. */
    private val provisional = ConcurrentHashMap.newKeySet<String>()

    /** How many times each track's whole-track pass has been refused for decoding short. */
    private val shortDecodes = ConcurrentHashMap<String, Int>()

    /** Tracks already looked for on disk this session; see [restoreOnce]. */
    private val restoreAttempted = ConcurrentHashMap.newKeySet<String>()

    /** Results that survive the process, so a track is measured once and stays measured. */
    private val store = AnalysisStore { context.filesDir }

    private val storeBridge = TrackStoreBridge(
        context = context,
        ioExecutor = ioExecutor,
        results = results,
        provisional = provisional,
        restoreAttempted = restoreAttempted,
        onAnalysisUpdated = ::emitUpdate,
    )

    private val queuePrepLock = Any()

    /** Cache keys of renditions that decoded short despite the cache calling them complete. */
    private val badRenditions = ConcurrentHashMap.newKeySet<String>()

    /** Cache keys already put through the whole-track pass, whatever came of it. */
    private val triedRenditions = ConcurrentHashMap.newKeySet<String>()
    /** Model availability of the last actual attempt, not of an unavailable-model request. */
    private val renditionModelGenerations = ConcurrentHashMap<String, Long>()

    /** How many times each rendition has been thrown off disk for being undecodable. */
    private val discarded = ConcurrentHashMap<String, Int>()

    private val headAnalyzer = HeadAnalyzer(
        cache = cache,
        resolver = { resolver },
        badRenditions = badRenditions,
        isStopped = { stopped },
        playbackFirst = ::playbackFirst,
        analysisUriFor = ::analysisUriFor,
        analyzeMonoWindow = { openSource, startSeconds, endSeconds, deriveFeatures, includeVocals ->
            wholeTrackPipeline.analyzeMonoWindow(openSource, startSeconds, endSeconds, deriveFeatures, includeVocals)
        },
    )

    private val wholeTrackPipeline = WholeTrackPipeline(
        context = context,
        cache = cache,
        tracker = tracker,
        vocalPresence = vocalPresence,
        beatLock = beatLock,
        presenceLock = presenceLock,
        badRenditions = badRenditions,
        triedRenditions = triedRenditions,
        shortDecodes = shortDecodes,
        discarded = discarded,
        isStopped = { stopped },
        isOverheated = { overheated },
        playbackFirst = ::playbackFirst,
        yieldOrStopped = ::yieldOrStopped,
        canUseWindowedStems = { npuStemBridge.canUseWindowedStems() },
        elapsedNow = ::elapsedNow,
        copyToRead = { trackId, uri, durationSeconds -> headAnalyzer.copyToRead(trackId, uri, durationSeconds) },
    )

    /**
     * Queue-worker surface: raises [trackId] to FULL when a URI is known.
     * Without a URI (the common case for an id-only queue entry) this can
     * only report whether FULL is already in hand; the playback path's
     * `request(trackId, uri, ...)` remains the way to earn it.
     */
    suspend fun ensureFullAnalysis(trackId: String): Boolean {
        if (trackId.isBlank()) return false
        storeBridge.triggerDjmixMigrationOnce()
        ensureQueuePrepRegistered()
        // AE3 M6: cheap guarded post of the add-on NPU bridge pass.
        npuStemBridge.scheduleNpuBridgeSetupOnce()
        if (results[trackId]?.quality == TrackAnalysis.QUALITY_FULL && results[trackId]?.isUsable == true) {
            return true
        }
        val uri = sourceAnalysisUris[trackId] ?: return false
        val duration = results[trackId]?.duration ?: 0.0
        request(trackId, uri, duration)
        // request() is async; poll briefly on the worker's IO dispatcher.
        repeat(20) {
            if (results[trackId]?.quality == TrackAnalysis.QUALITY_FULL && results[trackId]?.isUsable == true) {
                return true
            }
            delay(250)
        }
        return results[trackId]?.quality == TrackAnalysis.QUALITY_FULL && results[trackId]?.isUsable == true
    }

    /**
     * Queue-worker pair prep. Ensures both analyses exist when their URIs are known.
     */
    suspend fun preparePairAndStems(outId: String, inId: String) {
        ensureFullAnalysis(outId)
        ensureFullAnalysis(inId)
    }

    /** [QueuePrepDelegate] for [QueuePrepRegistry]; registered by PlaybackService. */
    val queuePrepDelegate: QueuePrepDelegate = object : QueuePrepDelegate {
        override suspend fun ensureFullAnalysis(trackId: String): Boolean =
            this@TrackAnalyzer.ensureFullAnalysis(trackId)

        override suspend fun preparePairAndStems(outId: String, inId: String) {
            this@TrackAnalyzer.preparePairAndStems(outId, inId)
        }
    }

    @Volatile private var queuePrepRegistered = false

    /** Registers [queuePrepDelegate] once; safe to call from any thread. */
    fun ensureQueuePrepRegistered() {
        if (queuePrepRegistered) return
        synchronized(queuePrepLock) {
            if (queuePrepRegistered) return
            if (QueuePrepRegistry.delegate == null) {
                QueuePrepRegistry.delegate = queuePrepDelegate
            }
            queuePrepRegistered = true
        }
    }

    private val executor = wholeTrackExecutor()

    /**
     * The next track's opening. It does not wait behind a whole-song decode.
     * Inference still takes [modelLock], so the two passes never share a session.
     */
    private val headExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bitchord-smart-analysis-head").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY
        }
    }

    /**
     * Elapsed-realtime deadline until which analysis stays off the cores.
     * Written from the playback tick, read from the analysis threads.
     */
    @Volatile private var yieldUntilElapsed = 0L

    // djmix14: in-RAM PCM region cache (4 entries, ~24 MB).
    private val loopCache = LoopRegionCache()
    /** Exposed for the controller's cold-lead budget gate. */
    fun loopRegionCached(trackId: String, sampleRate: Int, fromSec: Double, toSec: Double): Boolean =
        loopCache.contains(trackId, sampleRate, fromSec, toSec)
    /** The cache itself (tests + budget gate). */
    fun loopRegionCache(): LoopRegionCache = loopCache

    /**
     * What is known about [trackId] right now: never a computation, never a
     * block. Returns an empty analysis for anything not yet finished.
     */
    fun analysisFor(trackId: String): TrackAnalysis = results[trackId] ?: TrackAnalysis(trackId = trackId)

    /** Looks [trackId] up on disk, once, off the playback thread. */
    private fun restoreOnce(trackId: String) {
        if (results.containsKey(trackId)) return
        if (!restoreAttempted.add(trackId)) return
        ioExecutor.execute {
            try {
                // AE3 djmix.db first: a fresh HEAD_TAIL/FULL row restores without ORT.
                if (storeBridge.restoreFromDjmixStore(trackId)) return@execute
                val stored = store.load(trackId) ?: return@execute
                if (results.putIfAbsent(trackId, stored) == null) {
                    Log.d(TAG, "Restored analysis for $trackId: bpm=${stored.bpm} conf=${stored.beatConfidence}")
                    emitUpdate(trackId)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Restore of $trackId failed", error)
            }
        }
    }

    /** Emits [trackId] on [analysisUpdates] (never blocks; buffer is 64). */
    private fun emitUpdate(trackId: String) {
        _analysisUpdates.tryEmit(trackId)
    }

    /** Disk load from a compute thread, via the I/O pool with a hard timeout. */
    private fun loadFromDisk(trackId: String): TrackAnalysis? = runCatching {
        ioExecutor.submit<TrackAnalysis?> { store.load(trackId) }.get(STORE_LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    }.getOrNull()

    /** Head-byte fetches run on the I/O pool, not the audio thread. */
    private fun requestHeadAsync(analysisUri: Uri) {
        try {
            ioExecutor.execute { cache.requestAnalysisHead(analysisUri) }
        } catch (_: Throwable) {
            runCatching { cache.requestAnalysisHead(analysisUri) }
        }
    }

    /** Cancels pending ORT release (a new analysis arrived). */
    private fun cancelModelReaper() {
        synchronized(reaperLock) {
            try {
                modelReaper?.cancel(false)
            } catch (_: Throwable) {
            }
            modelReaper = null
        }
    }

    /** Releases ORT sessions after [MODEL_IDLE_REAP_MS] idle (cancelled by new work). */
    private fun scheduleModelReaper() {
        if (running.isNotEmpty()) return
        synchronized(reaperLock) {
            if (running.isNotEmpty()) return
            try {
                modelReaper?.cancel(false)
                modelReaper = reaperExecutor.schedule(
                    {
                        if (running.isEmpty()) {
                            tracker.release()
                            vocalPresence.release()
                            vocals.release()
                        }
                    },
                    MODEL_IDLE_REAP_MS,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: Throwable) {
            }
        }
    }

    private fun rememberTerminal(set: MutableSet<String>, trackId: String) {
        if (set.size >= 512) set.clear()
        set.add(trackId)
    }

    private fun elapsedNow(): Long = SystemClock.elapsedRealtime()

    private fun deadlineOf(usableComplete: Boolean): Long =
        if (usableComplete) WHOLE_TRACK_DEADLINE_MS else HEAD_DEADLINE_MS

    /**
     * Publishes a grid-ready intermediate (`QUALITY_HEAD`, never persisted).
     * Never overwrites `full`.
     */
    private fun publishIntermediate(
        trackId: String,
        effectiveDuration: Double,
        grid: BeatTracker.Grid?,
        feats: TrackFeatures.Features?,
        level: CurveWindow?,
    ) {
        if (grid == null && feats == null) return
        val bpm = grid?.bpm ?: feats?.bpm ?: 0.0
        if (bpm <= 0.0) return
        val existing = results[trackId]
        if (existing != null && existing.quality == TrackAnalysis.QUALITY_FULL) return
        val intermediate = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = effectiveDuration,
            quality = TrackAnalysis.QUALITY_HEAD,
            bpm = bpm,
            beatInterval = grid?.beatInterval ?: feats?.beatInterval ?: 0.0,
            beatConfidence = grid?.beatConfidence ?: feats?.beatConfidence ?: 0.0,
            downbeats = grid?.downbeats ?: feats?.downbeats.orEmpty(),
            firstBeat = grid?.firstBeat ?: feats?.firstBeat ?: 0.0,
            key = feats?.key.orEmpty(),
            keyConfidence = feats?.keyConfidence ?: 0.0,
            audibleStartTime = feats?.audibleStartTime,
            pickupTime = feats?.pickupTime,
            introEndTime = feats?.introEndTime ?: 0.0,
            mixInTime = feats?.mixInTime ?: 0.0,
            mixInCandidates = feats?.mixInCandidates.orEmpty(),
            headBeats = grid?.let { BeatSide(it.beats, it.downbeats, it.bpm, it.beatConfidence) },
            levelWindows = listOfNotNull(level),
        )
        provisional.add(trackId)
        results[trackId] = intermediate
        emitUpdate(trackId)
    }

    /** Marks [trackId] cancelled; any in-flight pass abandons between Pass-2 regions. */
    fun cancel(trackId: String) {
        if (trackId.isBlank()) return
        cancelled.add(trackId)
        emitUpdate(trackId)
    }

    /** The rendition key a source-provisional is tagged with, or null when it is not provisional. */
    fun provisionalRenditionOf(trackId: String): String? = sourceProvisionalKeys[trackId]

    /** True once [trackId] has a result, including a failure. Nothing more will arrive. */
    fun isAnalysed(trackId: String): Boolean = results.containsKey(trackId)

    /** True while a decode and inference for [trackId] is actually in flight. */
    fun isAnalysing(trackId: String): Boolean = trackId in running

    /** True while a whole-track (or head) analysis pass is queued or running. */
    fun wholeTrackBusy(): Boolean = running.isNotEmpty()

    /**
     * Holds new analysis work for [forMs] so a handover's decoder and the mix
     * renderer get the cores.
     */
    fun yieldToPlayback(forMs: Long = PLAYBACK_FIRST_MS) {
        val until = SystemClock.elapsedRealtime() + forMs
        if (until > yieldUntilElapsed) yieldUntilElapsed = until
    }

    /** [playbackFirst], then the party's stop flag. Safe to poll from a decode loop. */
    private fun yieldOrStopped(): Boolean {
        playbackFirst()
        return stopped
    }

    private fun playbackFirst() {
        if (elapsedNow() >= yieldUntilElapsed) return
        val urgent = runCatching { urgentPass1Thread.get() == true }.getOrDefault(false)
        runCatching {
            Process.setThreadPriority(
                if (urgent) Process.THREAD_PRIORITY_DEFAULT else Process.THREAD_PRIORITY_BACKGROUND,
            )
        }
        while (!stopped && elapsedNow() < yieldUntilElapsed) {
            val left = yieldUntilElapsed - elapsedNow()
            if (left <= 0L) break
            try {
                Thread.sleep(left.coerceAtMost(200L))
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    /**
     * Queues [trackId] (playing at [uri]) for analysis if it is not already
     * done or in flight. Cheap to call repeatedly; callers re-request as
     * caching progresses.
     *
     * Runs in up to two passes (Pass-1 head/grid on [headExecutor] vs Pass-2
     * whole-track on [executor]).
     */
    fun request(trackId: String, uri: Uri, durationSeconds: Double) {
        if (trackId.isBlank()) return
        if (stopped) return
        storeBridge.triggerDjmixMigrationOnce()
        ensureQueuePrepRegistered()
        npuStemBridge.scheduleNpuBridgeSetupOnce()
        if (trackId in running) return
        if (overheated) {
            restoreOnce(trackId)
            return
        }
        restoreOnce(trackId)
        if (!AutomixModels.isReady()) return
        val modelGeneration = AutomixModels.availabilityGeneration.value
        val analysisUri = analysisUriFor(trackId, uri) ?: return

        restoreOnce(trackId)

        val local = LocalAudioSource.isLocal(analysisUri)

        val complete = if (local) {
            emptyList()
        } else {
            cache.renditionsOf(analysisUri)
                .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(analysisUri.getQueryParameter("v"), it.key) }
                .filter { it.isComplete && it.key !in badRenditions }
        }
        val usableComplete = local || complete.isNotEmpty()

        val recorded = results[trackId]
        val untried = renditionModelGenerations[trackId] != modelGeneration || if (local) {
            uri.toString() !in triedRenditions
        } else {
            complete.any { it.key !in triedRenditions }
        }
        val supersedable = when {
            recorded == null -> false
            trackId in provisional && trackId in sourcePending -> analysisUri != uri && usableComplete
            trackId in provisional -> usableComplete
            else -> !recorded.isUsable && untried
        }
        if (recorded != null && !supersedable) {
            if (trackId in provisional && !usableComplete) requestHeadAsync(analysisUri)
            return
        }
        if (recorded != null && !recorded.isUsable) shortDecodes.remove(trackId)
        failed.remove(trackId)
        stalled.remove(trackId)
        cancelled.remove(trackId)
        val headRendition = if (usableComplete) null else headAnalyzer.headWorthTrying(trackId, analysisUri, durationSeconds)
        if (!usableComplete && headRendition == null) {
            requestHeadAsync(analysisUri)
            return
        }
        if (!running.add(trackId)) return
        renditionModelGenerations[trackId] = modelGeneration
        val seqAtQueue = requestSeq.incrementAndGet()
        lastRequestedTrackId = trackId
        cancelModelReaper()

        val pool = if (usableComplete) executor else headExecutor
        val urgentPass1 = !usableComplete
        val startedAtElapsed = SystemClock.elapsedRealtime()
        pool.execute {
            if (urgentPass1) {
                urgentPass1Thread.set(true)
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
            } else {
                urgentPass1Thread.set(false)
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
            }
            try {
                if (!AutomixModels.isReady()) {
                    headRendition?.let { headAnalyzer.headAttempts.remove(it.key) }
                    return@execute
                }
                playbackFirst()
                if (elapsedNow() - startedAtElapsed > deadlineOf(usableComplete)) {
                    rememberTerminal(stalled, trackId)
                    Log.w(TAG, "Analysis of $trackId stalled past its deadline")
                    return@execute
                }
                if (!usableComplete && trackId !in provisional && !results.containsKey(trackId)) {
                    loadFromDisk(trackId)?.let {
                        if (results.putIfAbsent(trackId, it) == null) {
                            Log.d(TAG, "Restored analysis for $trackId: bpm=${it.bpm} conf=${it.beatConfidence}")
                            emitUpdate(trackId)
                        }
                    }
                }
                // AE3: a fresh djmix.db HEAD_TAIL/FULL row restores here before decode/ORT.
                if (storeBridge.restoreFromDjmixStore(trackId)) return@execute
                try {
                    val landed = results[trackId]
                    if (landed != null && landed.isUsable && trackId !in provisional) return@execute
                    if (stopped || trackId in cancelled) return@execute
                    if (usableComplete) {
                        val outcome = wholeTrackPipeline.analyze(
                            trackId,
                            analysisUri,
                            durationSeconds,
                            onGridReady = { effective, grid, feats, level ->
                                publishIntermediate(trackId, effective, grid, feats, level)
                            },
                            shouldAbort = {
                                stopped || trackId in cancelled ||
                                    (requestSeq.get() != seqAtQueue && lastRequestedTrackId != trackId && results[trackId]?.isUsable == true) ||
                                    (elapsedNow() - startedAtElapsed > WHOLE_TRACK_DEADLINE_MS)
                            },
                        )
                        val whole = outcome.analysis
                        if (whole != null) {
                            val wasSource = trackId in sourcePending
                            results[trackId] = whole
                            if (wasSource) {
                                provisional.add(trackId)
                            } else {
                                provisional.remove(trackId)
                                sourcePending.remove(trackId)
                            }
                            shortDecodes.remove(trackId)
                            failed.remove(trackId)
                            stalled.remove(trackId)
                            if (!wasSource) {
                                store.save(trackId, whole)
                                storeBridge.persistToDjmixStore(trackId, whole)
                            }
                            restoreAttempted.add(trackId)
                            emitUpdate(trackId)
                        } else if (outcome.aborted) {
                            // Priority/cancellation abort: the grid-ready intermediate stays.
                        } else if (outcome.decodedShort &&
                            shortDecodes.merge(trackId, 1, Int::plus)!! >= MAX_SHORT_DECODE_ATTEMPTS
                        ) {
                            Log.w(TAG, "Giving up on $trackId after $MAX_SHORT_DECODE_ATTEMPTS short decodes")
                            if (trackId !in provisional || results[trackId]?.isUsable != true) {
                                results[trackId] = TrackAnalysis(
                                    status = TrackAnalysis.STATUS_READY,
                                    trackId = trackId,
                                    duration = durationSeconds,
                                )
                                provisional.remove(trackId)
                            }
                            rememberTerminal(failed, trackId)
                            emitUpdate(trackId)
                        }
                    } else {
                        if (elapsedNow() - startedAtElapsed > HEAD_DEADLINE_MS) {
                            rememberTerminal(stalled, trackId)
                            return@execute
                        }
                        headAnalyzer.analyzeHead(trackId, analysisUri, durationSeconds, headRendition!!)?.let { head ->
                            provisional.add(trackId)
                            results[trackId] = head
                            storeBridge.persistToDjmixStore(trackId, head)
                            emitUpdate(trackId)
                        }
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Analysis of $trackId failed", error)
                    rememberTerminal(failed, trackId)
                    val keepIntermediate = trackId in provisional && results[trackId]?.isUsable == true
                    if (usableComplete && !keepIntermediate) {
                        results[trackId] = TrackAnalysis(
                            status = TrackAnalysis.STATUS_READY,
                            trackId = trackId,
                            duration = durationSeconds,
                        )
                        provisional.remove(trackId)
                    }
                    emitUpdate(trackId)
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Analysis of $trackId failed", error)
                rememberTerminal(failed, trackId)
                emitUpdate(trackId)
            } finally {
                urgentPass1Thread.remove()
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                running.remove(trackId)
                emitUpdate(trackId)
                if (running.isEmpty()) scheduleModelReaper()
            }
        }
    }

    /** The URI analysis reads for [trackId]. */
    private fun analysisUriFor(trackId: String, playbackUri: Uri): Uri? {
        if (playbackUri.getQueryParameter("v") != null || playbackUri.authority != "source") return playbackUri
        if (SourceResolver.isPickedAddonRow(playbackUri)) return null
        sourceAnalysisUris[trackId]?.let {
            sourcePending.remove(trackId)
            return it
        }
        if (trackId !in sourceResolutionAttempted && sourceResolutions.add(trackId)) {
            val target = SourceResolver.targetIn(playbackUri)
            sourceResolutionScope.launch {
                var match: com.music.bitchord.data.model.Song? = null
                try {
                    var attempt = 0
                    while (attempt < SOURCE_RESOLUTION_ATTEMPTS && match == null) {
                        attempt++
                        match = findYouTubeMatch(target)
                        if (match == null && attempt < SOURCE_RESOLUTION_ATTEMPTS) {
                            Log.d(TAG, "YouTube match attempt $attempt timed out for $trackId; retrying")
                            delay(1_000)
                        }
                    }
                    if (match != null) {
                        sourceAnalysisUris[trackId] = Uri.parse(AutomixAnalysisSource.opusUri(match.videoId))
                        sourcePending.remove(trackId)
                        Log.d(TAG, "Automix analysis for $trackId will use YouTube Opus ${match.videoId}")
                        emitUpdate(trackId)
                    } else {
                        Log.d(TAG, "No YouTube match for Automix analysis of $trackId")
                    }
                } catch (error: Throwable) {
                    Log.d(TAG, "YouTube match failed for Automix analysis of $trackId: ${error.message}")
                } finally {
                    sourceResolutionAttempted.add(trackId)
                    sourceResolutions.remove(trackId)
                }
            }
        }
        sourcePending.add(trackId)
        sourceProvisionalKeys.putIfAbsent(
            trackId,
            runCatching { cache.cacheKeyOf(playbackUri) }.getOrElse { playbackUri.toString() },
        )
        return playbackUri
    }

    private suspend fun findYouTubeMatch(target: TrackMatcher.Target): com.music.bitchord.data.model.Song? {
        if (target.title.isBlank()) return null
        for (query in TrackMatcher.queries(target)) {
            val candidates = withTimeoutOrNull(YOUTUBE_MATCH_QUERY_TIMEOUT_MS) {
                YtMusicRepository.search(query, SearchFilter.SONGS).getOrNull()
            }?.filterIsInstance<SearchResult.Track>()?.map { it.song }.orEmpty()
            TrackMatcher.best(candidates, target)?.let { return it }
        }
        return null
    }

    /** 2-stem UMX fallback for render transition decks (not used in analysis). */
    fun separateTransitionWindow(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        keepVocals: Boolean,
    ): Pair<FloatArray, FloatArray>? {
        if (yieldOrStopped()) return null
        return synchronized(vocalLock) {
            if (yieldOrStopped()) null else vocals.separateWindow(left, right, sampleRate.toDouble(), keepVocals)
        }
    }

    fun separateTransitionBothWindows(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
    ): Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>? {
        return synchronized(vocalLock) {
            if (yieldOrStopped()) null else vocals.separateBothWindows(left, right, sampleRate.toDouble())
        }
    }

    /**
     * Stereo audio of [fromSeconds]..[toSeconds] of [trackId] at [sampleRate] for a loop mix deck.
     * Blocking — call off the main thread.
     */
    fun loopRegion(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        fromSeconds: Double,
        toSeconds: Double,
        sampleRate: Int,
        onStep: ((String) -> Unit)? = null,
    ): com.music.bitchord.playback.loopmix.LoopRegion? {
        loopCache.get(trackId, sampleRate, max(0.0, fromSeconds), toSeconds)?.let { slice ->
            Log.d(TAG, "Loop region $trackId cache hit ${slice.startSec}s..${slice.endSec}s at ${sampleRate}Hz")
            return com.music.bitchord.playback.loopmix.LoopRegion(slice.left, slice.right, slice.startSec)
        }
        val began = SystemClock.elapsedRealtime()
        onStep?.invoke("stream")
        val copy = runCatching { headAnalyzer.copyToRead(trackId, uri, durationSeconds) }.getOrNull()
            ?: runCatching { headAnalyzer.streamingCopyForLoop(trackId, uri, durationSeconds) }.getOrNull()
            ?: return null
        onStep?.invoke("decode")
        val (stereo, start) = copy.open()?.use { source ->
            AudioDecoder.decodeRegionStereo(source, max(0.0, fromSeconds), toSeconds) { yieldOrStopped() }
        } ?: return null
        if (stereo.left.isEmpty()) return null
        var rawLeft = stereo.left
        var rawRight = stereo.right
        var startOut = start
        // handoff_pcm_before_protected_event: prepend priming zeros when fresh decode wasn't padded.
        if (fromSeconds <= 0.05 && start > 0.0 && start <= 0.080 && stereo.sampleRate > 0.0) {
            val pad = (start * stereo.sampleRate).roundToInt()
            if (pad > 0) {
                rawLeft = FloatArray(pad) + rawLeft
                rawRight = FloatArray(pad) + rawRight
                startOut = 0.0
            }
        }
        val rate = sampleRate.toDouble()
        val left: FloatArray
        val right: FloatArray
        if (abs(stereo.sampleRate - rate) > 0.5) {
            onStep?.invoke("resample")
            left = MelSpectrogram.resample(rawLeft, stereo.sampleRate, rate) ?: return null
            right = MelSpectrogram.resample(rawRight, stereo.sampleRate, rate) ?: return null
        } else {
            left = rawLeft
            right = rawRight
        }
        Log.d(
            TAG,
            "Loop region $trackId from ${copy.key}: asked ${"%.2f".format(Locale.ROOT, fromSeconds)}s, " +
                "got ${"%.3f".format(Locale.ROOT, startOut)}s + ${"%.2f".format(Locale.ROOT, left.size / rate)}s " +
                "at ${stereo.sampleRate.toInt()}->$sampleRate Hz in ${SystemClock.elapsedRealtime() - began}ms",
        )
        loopCache.put(trackId, sampleRate, startOut, startOut + left.size / rate, left, right)
        return com.music.bitchord.playback.loopmix.LoopRegion(left, right, startOut)
    }

    /**
     * A three-band waveform of [fromSeconds]..[toSeconds] of [trackId] for the Automix popup.
     * Blocking — call off the main thread.
     */
    fun mixWaveform(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        fromSeconds: Double,
        toSeconds: Double,
        bins: Int,
    ): MixWaveform.Bands? {
        val analysisUri = if (uri.getQueryParameter("v") != null || uri.authority != "source") {
            uri
        } else {
            sourceAnalysisUris[trackId] ?: return null
        }
        val began = SystemClock.elapsedRealtime()
        val copy = runCatching { headAnalyzer.copyToRead(trackId, analysisUri, durationSeconds) }.getOrNull() ?: return null
        val chosen = SystemClock.elapsedRealtime()
        val bands = copy.open()?.use { source ->
            MixWaveform.build(source, fromSeconds, toSeconds, bins) { stopped }
        }
        Log.d(
            TAG,
            "Mix waveform $trackId: copy ${chosen - began}ms, decode ${SystemClock.elapsedRealtime() - chosen}ms, " +
                "asked ${"%.1f".format(Locale.ROOT, fromSeconds)}s, decoder started " +
                "${bands?.decodedFrom?.let { "%.1f".format(Locale.ROOT, it) } ?: "-"}s",
        )
        return bands
    }

    fun release() {
        cancelModelReaper()
        try {
            reaperExecutor.shutdownNow()
        } catch (_: Throwable) {
        }
        executor.shutdownNow()
        headExecutor.shutdownNow()
        ioExecutor.shutdownNow()
        sourceResolutionScope.cancel()
        sourceAnalysisUris.clear()
        sourceResolutions.clear()
        sourceResolutionAttempted.clear()
        headAnalyzer.clear()
        provisional.clear()
        restoreAttempted.clear()
        shortDecodes.clear()
        badRenditions.clear()
        triedRenditions.clear()
        renditionModelGenerations.clear()
        discarded.clear()
        failed.clear()
        stalled.clear()
        cancelled.clear()
        sourcePending.clear()
        sourceProvisionalKeys.clear()
        _analysisUpdates.tryEmit("") // best-effort terminal signal (buffered, non-blocking)
        tracker.release()
        vocalPresence.release()
        vocals.release()
        runCatching { stemSeparator.trim() } // AUTOMIX-V2(orch)
    }

    private companion object {
        const val TAG = "BitChordTrackAnalyzer"

        /** How long a handover keeps analysis off the cores, from the moment it is asked. */
        const val PLAYBACK_FIRST_MS = 2_000L

        /** Refusals before a track is written off as truncated rather than still filling in. */
        const val MAX_SHORT_DECODE_ATTEMPTS = 3

        /** Hard deadline for a head (Pass-1) pass. */
        const val HEAD_DEADLINE_MS = 45_000L

        /** Hard deadline for a whole-track pass. */
        const val WHOLE_TRACK_DEADLINE_MS = 480_000L

        /** Disk loads from compute threads never block longer than this. */
        const val STORE_LOAD_TIMEOUT_SECONDS = 10L

        /** Idle time before ORT sessions are reaped (90-120s; cancelled by new work). */
        const val MODEL_IDLE_REAP_MS = 100_000L

        /** Per-query timeout for the YouTube matching search. */
        const val YOUTUBE_MATCH_QUERY_TIMEOUT_MS = 8_000L

        /** Attempts for one source resolution before staying provisional. */
        const val SOURCE_RESOLUTION_ATTEMPTS = 2
    }
}
