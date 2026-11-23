/*
 * Modeled on Orchard's own TrackAnalyzer (https://github.com/SFG5453/Orchard).
 * Phase 1 was the DSP-only pass (native/analyzer/audio_analysis.cpp); Phase 2
 * adds the Beat This! ONNX model (see [BeatTracker]) and Phase 3 the
 * open-unmix vocal mask (see [VocalTracker]), both over the head and tail of
 * the track, which is the only part a transition ever reads.
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
import android.media.MediaDataSource
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.util.Locale

/**
 * Bug 1c: prepend zeros when a MediaCodec decode answers a few milliseconds
 * late under SEEK_TO_PREVIOUS_SYNC, then re-anchor the PCM head to the
 * requested source second so coversPhysical()'s exact start check passes.
 * Pure and fail-closed: never pads an early start, never pads more than
 * [maxLateSec], and never changes the arrays otherwise.
 */
internal fun padLateStart(
    left: FloatArray,
    right: FloatArray,
    actualStart: Double,
    requestedStart: Double,
    sampleRate: Double,
    maxLateSec: Double = 0.080,
): Triple<FloatArray, FloatArray, Double> {
    if (requestedStart >= 0.0 && actualStart > requestedStart &&
        (actualStart - requestedStart) <= maxLateSec && sampleRate > 0.0
    ) {
        val pad = ((actualStart - requestedStart) * sampleRate).roundToInt()
        if (pad > 0) {
            return Triple(FloatArray(pad) + left, FloatArray(pad) + right, requestedStart)
        }
    }
    return Triple(left, right, actualStart)
}

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

    // vc69 NPU: Snapdragon QNN for beat and vocal models with CPU fallback.
    // Auto-detected from the device; nothing to configure. The context cache
    // lives in noBackupFilesDir so OS updates never invalidate it wrongly.
    private val npuVendor: com.music.bitchord.playback.smart.NpuSupport.Vendor =
        com.music.bitchord.playback.smart.NpuSupport.detectVendor(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) android.os.Build.SOC_MANUFACTURER else "",
            android.os.Build.HARDWARE,
            android.os.Build.MODEL,
            android.os.Build.MANUFACTURER,
        )
    private val npuCacheDir: java.io.File
        get() = java.io.File(context.noBackupFilesDir, "npu/cache").apply { mkdirs() }
    // vc70 NPU FIX (launch NPE in TrackAnalyzer.<init>): PlaybackService
    // constructs this analyzer from a property initializer
    // (PlaybackService.kt: trackAnalyzer = TrackAnalyzer(this, ...)), i.e.
    // while the Service is still unattached -- ContextWrapper.mBase is null
    // until ActivityThread.attach() runs after Class.newInstance(). Any
    // direct `context.applicationInfo.nativeLibraryDir` read in that window
    // dereferences a null ApplicationInfo and NPEs (captured vc70 crash:
    // TrackAnalyzer.<init>:239, which maps to source line 122). The init
    // catch below kept the service alive but swallowed that NPE, so NPU
    // preflight died before its first BitChordNPU log line on every
    // launch. Resolution is therefore lazy and defensive: the dir is read
    // through applicationContext (always attached once the service starts)
    // and any failure maps to null instead of throwing. This getter must
    // keep recomputing on every access -- never cache it -- because the
    // first access happens before attach and would otherwise freeze a
    // null forever. Once attached, applicationInfo is always non-null for
    // an installed app, so a null here honestly means "no bundled
    // backend", and callers stay on CPU.
    private val npuLibDir: String?
        get() = runCatching {
            context.applicationContext.applicationInfo.nativeLibraryDir
        }.getOrNull()

    private val npuPreflightLock = Any()
    @Volatile private var npuPreflightDone = false

    /**
     * Runs the ADSP preflight (dlopen of the bundled HTP backend) once a
     * real library dir is visible, and returns that dir, or null when it
     * is unavailable (pre-attach construction; defensive read failures).
     * Called from the init block and from [npuBackendPath], so preflight
     * is guaranteed to precede any QNN session open even though the
     * init-time call is deferred while the service is unattached.
     * NpuNative never throws, so neither does this.
     */
    private fun ensureNpuPreflight(): String? {
        // Dormant by default: QNN_ENABLED=false means no dlopen and no
        // backend path, so both trackers stay on the CPU provider.
        if (!NpuSupport.QNN_ENABLED) return null
        val dir = npuLibDir ?: return null
        synchronized(npuPreflightLock) {
            if (!npuPreflightDone) {
                com.music.bitchord.playback.smart.NpuNative.initBundledQnn(dir)
                npuPreflightDone = true
            }
        }
        return dir
    }

    // vc70 NPU: QNN backend is bundled in this APK nativeLibraryDir (QAIRT
    // libQnnHtp.so). Only Qualcomm attempts QNN; everything else stays
    // on the CPU execution provider, exactly as the fallback contract says.
    private val npuBackendPath: String?
        get() = if (!NpuSupport.QNN_ENABLED || npuVendor != com.music.bitchord.playback.smart.NpuSupport.Vendor.QUALCOMM) null
        else ensureNpuPreflight()?.let {
            com.music.bitchord.playback.smart.NpuSupport.bundledBackendPath(it)
        }?.takeIf { java.io.File(it).exists() }

    private val tracker = BeatTracker(
        modelPath = {
            java.io.File(context.filesDir, BeatTracker.RETIRED_MODEL_ASSET).delete()
            unpackedAsset(BeatTracker.MODEL_ASSET)
        },
        inferenceThreads = { AppSettings.automixPerformanceMode.value.inferenceThreads },
        npuVendor = { npuVendor },
        npuBackendPath = { npuBackendPath },
        npuCacheDir = { npuCacheDir },
    )
    private val vocals = VocalTracker(
        modelPath = { unpackedAsset(VocalTracker.MODEL_ASSET) },
        inferenceThreads = { AppSettings.automixPerformanceMode.value.inferenceThreads },
        npuVendor = { npuVendor },
        npuBackendPath = { npuBackendPath },
        npuCacheDir = { npuCacheDir },
    )

    init {
        // The shared trackers report model failures and their timings through
        // this; with no sink they went nowhere on the phone, which is how a
        // beat model that found nothing went unnoticed.
        AnalysisLog.install { message, error -> Log.w(TAG, message, error) }
        // vc70 NPU: point the DSP library search at the bundled QAIRT libs
        // (mirrors the Orchard qnn_runner ADSP_LIBRARY_PATH setup). Best
        // effort: no NPU on the device, everything still runs on CPU.
        // Last-resort guard: NPU init must never crash service
        // construction (which happens at launch). The null that used to
        // NPE here is now handled at its source (see npuLibDir /
        // ensureNpuPreflight), so this boundary should never fire; it
        // stays as a final barrier, not the fix.
        try {
            if (!NpuSupport.QNN_ENABLED) {
                Log.i(TAG, "BitChordNPU disabled (QNN_ENABLED=false): CPU execution provider")
            } else if (npuVendor == com.music.bitchord.playback.smart.NpuSupport.Vendor.QUALCOMM) {
                // Deferred while this Service is unattached (null); the
                // ADSP preflight then runs at first post-attach access via
                // ensureNpuPreflight (see npuBackendPath) instead of
                // throwing here.
                val libDir = ensureNpuPreflight()
                val soc = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) android.os.Build.SOC_MODEL else android.os.Build.HARDWARE
                if (libDir == null) {
                    Log.i(TAG, "BitChordNPU vendor=QUALCOMM soc=" + soc + " libDir=unattached preflight=deferred")
                } else {
                    // NPU observability (one line): the SoC string, the
                    // backend path, and the bundled Skel file(s) present in
                    // nativeLibraryDir. QNN resolves the arch from the SoC
                    // at backend open, so the present Skel(s) are the only
                    // candidates the DSP can load; this line proves which
                    // arch actually loaded. Dumped once at init, never
                    // throwing.
                    runCatching {
                        val skels = java.io.File(libDir).list { _, name ->
                            name.startsWith("libQnnHtpV") && name.endsWith("Skel.so")
                        }?.joinToString(",") ?: "none"
                        Log.i(TAG, "BitChordNPU vendor=QUALCOMM soc=" + soc + " backend=" + (npuBackendPath ?: "missing") + " skels=" + skels)
                    }
                }
            } else {
                Log.i(TAG, "BitChordNPU vendor=" + npuVendor + ": CPU execution provider")
            }
        } catch (failure: Throwable) {
            Log.w(TAG, "BitChordNPU init failed; staying on CPU", failure)
        }
        // Bounded one-shot NPU self-test on a background thread (owned by
        // NpuSelfTest): proves QNN dispatch without playback. Never
        // throws, never blocks the UI, runs once per process.
        com.music.bitchord.playback.smart.NpuSelfTest.schedule(
            context,
            { npuVendor },
            { ensureNpuPreflight() },
        )
    }

    /**
     * A model's weights on disk, unpacked from assets the first time they are
     * asked for.
     *
     * ONNX Runtime wants a file it can memory-map, and an asset inside the APK
     * is not one. This used to live inside each tracker; it moved out when they
     * became shared with the desktop build, which unpacks from the jar instead.
     */
    private fun unpackedAsset(name: String): String {
        val file = java.io.File(context.filesDir, name)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(name).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }

    /**
     * Whether analysis is switched off entirely right now.
     *
     * A party never mixes — [com.music.bitchord.playback.CrossfadeController]
     * refuses to plan a transition while one is running, because every member
     * would start the next track at a moment their own copy of the audio
     * decided on. Measuring for a transition that cannot happen is then pure
     * cost, and not a small one: a whole-track decode, two ONNX passes, and for
     * an uncached track a megabyte fetched over the same connection the party's
     * own sync is sharing.
     *
     * Read fresh at every entry point and between the stages of a pass in
     * flight, rather than latched when the party starts, so joining one stops
     * the work already running instead of only the work not yet queued.
     */
    /**
     * djmix25 windowed-stem gate (P1.5): only non-low-RAM devices with
     * >= 6 GB total RAM (or a >=256 MB memory class) AND the user opt-in
     * [com.music.bitchord.data.settings.AppSettings.automixWindowedStemsEnabled]
     * may run the instrumental-share windows. Everything else stays on the
     * single umxhq vocal stem; the planner never sees instrumental evidence
     * there, so nothing degrades.
     */
    private fun canUseWindowedStems(): Boolean {
        if (!AppSettings.automixWindowedStemsEnabled.value) return false
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return false
            val info = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            !activityManager.isLowRamDevice &&
                (info.totalMem >= 6L * 1024L * 1024L * 1024L ||
                    activityManager.memoryClass >= 256)
        } catch (_: Throwable) {
            false
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

    /** Tracks whose result came from [analyzeHead] and is waiting to be superseded. */
    private val provisional = ConcurrentHashMap.newKeySet<String>()

    /**
     * Cached prefix size, in bytes, at the last head attempt on each
     * *rendition*. See [headWorthTrying].
     *
     * Keyed by rendition rather than by track because the growth guard is
     * asking "have I already decoded roughly this much of this copy", and a
     * track can move between copies: a first attempt on a half-cached lossless
     * rendition recorded six megabytes, and a much lighter Opus head arriving
     * afterwards — the one that would actually have produced a result — was then
     * refused for being smaller than a number belonging to a different file.
     */
    private val headAttempts = ConcurrentHashMap<String, Long>()

    /**
     * Track-and-rendition pairs that have already reported waiting for a head,
     * so the tick doesn't spam. Not keyed by track alone: a track that gains a
     * second copy of itself is in a genuinely new situation, and the first
     * version of this hid exactly that.
     */
    private val headSkipLogged = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many times each track's whole-track pass has been refused for
     * decoding short. Counted rather than flagged so a rendition still filling
     * in its holes gets a few more chances, while one that is genuinely
     * truncated stops being re-decoded on every tick.
     */
    private val shortDecodes = ConcurrentHashMap<String, Int>()

    /** Tracks already looked for on disk this session; see [restoreOnce]. */
    private val restoreAttempted = ConcurrentHashMap.newKeySet<String>()

    /** Results that survive the process, so a track is measured once and stays measured. */
    private val store = AnalysisStore { context.filesDir }

    /**
     * Cache keys of renditions that decoded short despite the cache calling them
     * complete. Keyed by rendition rather than by track, because the point is to
     * send the next attempt at the *same* track to a different copy of it.
     */
    private val badRenditions = ConcurrentHashMap.newKeySet<String>()

    /**
     * Cache keys already put through the whole-track pass, whatever came of it.
     *
     * What reopens a track that was written off: a copy of it nobody has read
     * yet. Without this the write-off is final for the session, and a rendition
     * that arrives seconds later — a quality upgrade, or the head fetch the
     * analyzer itself asked for — is never looked at. Measured, a track was
     * given up on at 22:09:24 and its `#hifi` copy finished downloading at
     * 22:09:41.
     */
    private val triedRenditions = ConcurrentHashMap.newKeySet<String>()

    /**
     * How many times each rendition has been thrown off disk for being
     * undecodable, so a clean slate stays a one-off.
     *
     * The refetch that follows a discard is not guaranteed to be any better —
     * a source can serve the same broken file twice — and without a bound the
     * two halves feed each other: refuse, delete, refetch, refuse, delete, on a
     * 250ms tick, spending the listener's data in a loop. One clean-slate retry
     * is enough for the case this exists for, which is an entry spliced from two
     * different encodings and unrecoverable only because nothing would ever
     * overwrite it.
     */
    private val discarded = ConcurrentHashMap<String, Int>()

    private fun discardsOf(key: String): Int = discarded[key] ?: 0

    // vc74 (djmix32): single-threaded. vc73 on-device acceptance measured three
    // concurrent whole-track passes sharing [modelLock] (on top of ORT's per-model
    // threads) at a uniform 6x slowdown in both mel (native DSP) and infer, on a
    // hot backgrounded phone. One pass at a time keeps the model pipeline at full
    // speed and shortens total queue time for the pair that actually needs it.
    // The djmix14 rationale (next track no longer waits 15 s) is unchanged, but a
    // contended phone runs slower than a serial one, not faster.
    // Thermal guard ([overheated]) and [yieldUntilElapsed] are still honored at request time.
    private val executor = wholeTrackExecutor()

    /**
     * The next track's opening. It does not wait behind a whole-song decode.
     * Inference still takes [modelLock], so the two passes never share a session.
     */
    private val headExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bitchord-smart-analysis-head").apply {
            isDaemon = true
            // Not above the rest of the process. The playback and decode
            // threads are the ones that have to run on time; this pass waits
            // its turn, and [yieldToPlayback] holds it entirely across a handover.
            priority = Thread.NORM_PRIORITY
        }
    }

    /**
     * Elapsed-realtime deadline until which analysis stays off the cores.
     * Written from the playback tick, read from the analysis threads.
     */
    @Volatile private var yieldUntilElapsed = 0L

    // djmix14: in-RAM PCM region cache (4 entries, ~24 MB). Loop decks decode
    // over the network; a cached region covering the request is sub-sliced
    // here in <2 ms instead of decoding again after a scrub.
    private val loopCache = LoopRegionCache()
    /** Exposed for the controller's cold-lead budget gate. */
    fun loopRegionCached(trackId: String, sampleRate: Int, fromSec: Double, toSec: Double): Boolean =
        loopCache.contains(trackId, sampleRate, fromSec, toSec)
    /** The cache itself (tests + budget gate). */
    fun loopRegionCache(): LoopRegionCache = loopCache

    private val modelLock = Any()

    /**
     * What is known about [trackId] right now: never a computation, never a
     * block. Returns an empty analysis for anything not yet finished, which
     * [assessTransitionTier] reads as no evidence rather than as a failure.
     */
    fun analysisFor(trackId: String): TrackAnalysis = results[trackId] ?: TrackAnalysis(trackId = trackId)

    /**
     * Looks [trackId] up on disk, once, off the playback thread.
     *
     * Deliberately not folded into [analysisFor], which is called several times
     * per tick from the playback thread and must never touch the filesystem.
     * The result lands in [results] a tick or two later, which is immaterial
     * against the seconds a real analysis takes — and against the alternative,
     * which is not having it at all.
     */
    private fun restoreOnce(trackId: String) {
        if (results.containsKey(trackId)) return
        // The set doubles as the once-guard: add() is true only for the first
        // caller, so a track with nothing stored is looked for once per session
        // rather than on every tick.
        if (!restoreAttempted.add(trackId)) return
        // Off the whole-track executor: a cache restore queued behind a 10-30s
        // compute pass would only land after the transition it was meant to inform.
        ioExecutor.execute {
            try {
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
                            vocals.release()
                        }
                    },
                    MODEL_IDLE_REAP_MS,
                    TimeUnit.MILLISECONDS,
                )
            } catch (_: Throwable) {
                // Executor shut down (release()); sessions are released there.
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
     * [TrackAnalysis] has no `grid` quality, and [TransitionPlanner] treats `head`
     * as usable-incoming while `full` requires the energy curve/tail, so `head`
     * is the only planner-compatible intermediate. Never overwrites `full`.
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

    /**
     * True while a decode and inference for [trackId] is actually in flight.
     *
     * Distinct from "not analysed": a track waiting on bytes and a track being
     * worked on right now are the same absence of a result, and the difference
     * is the difference between something being wrong and something simply
     * taking the several seconds it takes.
     */
    fun isAnalysing(trackId: String): Boolean = trackId in running

    /**
     * True while a whole-track (or head) analysis pass is queued or running;
     * loop-deck stem work waits on this and falls back to the dry region.
     * The honest available signal is [running]: there is no separately
     * observable queue depth, and head passes share the same [modelLock] as
     * whole-track ones, so gating on any in-flight pass is the conservative
     * (and correct) choice. Allocation-light and non-blocking.
     */
    fun wholeTrackBusy(): Boolean = running.isNotEmpty()

    /**
     * Queues [trackId] (playing at [uri]) for analysis if it is not already
     * done or in flight. Cheap to call repeatedly; callers re-request as
     * caching progresses.
     *
     * Runs in up to two passes, because waiting for a full cache is what kept
     * the *incoming* track of every transition unanalysed. A track only
     * finishes downloading once it is already playing, so the whole-track pass
     * lands in time to describe a track's own mix-out and never in time to
     * describe its entry — which is the half the listener hears at the moment
     * of the blend.
     *
     * So a track with enough of a head on disk gets [analyzeHead] first: beat
     * grid only, over the opening window, which is all the incoming side is
     * read for. That result is provisional and is replaced by the whole-track
     * [analyze] as soon as the remaining bytes arrive.
     *
     * None of that applies to a track playing off the device, which goes
     * straight to [analyze] on the first tick that reaches it — there is nothing
     * to wait for and nothing to escalate through. See [LocalAudioSource] for
     * why that needed saying at all.
     */
    /**
     * Holds new analysis work for [forMs] so a handover's decoder and the mix
     * renderer get the cores. A pass already inside a model call finishes that
     * call, then waits here before the next window. Extends an open window
     * rather than shortening it.
     */
    fun yieldToPlayback(forMs: Long = PLAYBACK_FIRST_MS) {
        val until = android.os.SystemClock.elapsedRealtime() + forMs
        if (until > yieldUntilElapsed) yieldUntilElapsed = until
    }

    /**
     * Sleeps out a [yieldToPlayback] window before decode or inference.
     *
     * vc75: analysis sits in the background cgroup for the whole pass, not
     * only while the window is open. It tolerates latency; the audio render
     * thread does not, and a mel pass or an inference batch at default
     * priority was landing on the same core at the instant the sink was
     * trying to fill a buffer.
     */
    /** [playbackFirst], then the party's stop flag. Safe to poll from a decode loop. */
    private fun yieldOrStopped(): Boolean {
        playbackFirst()
        return stopped
    }

    private fun playbackFirst() {
        if (elapsedNow() >= yieldUntilElapsed) return
        // Default is BACKGROUND; urgent Pass-1 (head lane) is boosted to DEFAULT
        // only while it actually runs, and restored in the task finally.
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
     * whole-track on [executor]): a track with enough of a head on disk gets
     * [analyzeHead] first so the incoming side is measured early, and the
     * whole-track [analyze] follows as soon as the remaining bytes arrive.
     * Whole-track passes publish a grid-ready intermediate (see
     * [publishIntermediate]) before Pass-2, so current/next get a usable grid
     * in ~1-2s instead of waiting behind 5-window UMX + Structure.
     */
    fun request(trackId: String, uri: Uri, durationSeconds: Double) {
        if (trackId.isBlank()) return
        if (stopped) return
        if (trackId in running) return
        if (overheated) {
            restoreOnce(trackId)
            return
        }
        // Source-backed tracks: while the canonical YouTube match is pending
        // or failed this returns the playback URI as a provisional (see
        // [analysisUriFor]); only an exclusion the user opted into (picked
        // addon row) still drops the request.
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
        val untried = if (local) {
            uri.toString() !in triedRenditions
        } else {
            complete.any { it.key !in triedRenditions }
        }
        val supersedable = when {
            recorded == null -> false
            // Source-provisional: only the canonical URI supersedes it. While the
            // match is still pending, re-running would just re-measure the same
            // provisional bytes every tick.
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
        val headRendition = if (usableComplete) null else headWorthTrying(trackId, analysisUri, durationSeconds)
        if (!usableComplete && headRendition == null) {
            requestHeadAsync(analysisUri)
            return
        }
        if (!running.add(trackId)) return
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
                try {
                    val landed = results[trackId]
                    if (landed != null && landed.isUsable && trackId !in provisional) return@execute
                    if (stopped || trackId in cancelled) return@execute
                    if (usableComplete) {
                        val outcome = analyze(
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
                                // Canonical bytes have not landed yet: keep the
                                // provisional flags so the real pass supersedes it,
                                // and never persist source audio.
                                provisional.add(trackId)
                            } else {
                                provisional.remove(trackId)
                                sourcePending.remove(trackId)
                            }
                            shortDecodes.remove(trackId)
                            failed.remove(trackId)
                            stalled.remove(trackId)
                            if (!wasSource) store.save(trackId, whole)
                            restoreAttempted.add(trackId)
                            emitUpdate(trackId)
                        } else if (outcome.aborted) {
                            // Priority/cancellation abort: the grid-ready
                            // intermediate already published stays; no failure.
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
                        analyzeHead(trackId, analysisUri, durationSeconds, headRendition!!)?.let { head ->
                            provisional.add(trackId)
                            results[trackId] = head
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
                // ALWAYS clean running + emit, so stateOf() never hangs on
                // "analysing..." forever, even on early returns / timeouts.
                urgentPass1Thread.remove()
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                running.remove(trackId)
                emitUpdate(trackId)
                if (running.isEmpty()) scheduleModelReaper()
            }
        }
    }

    /**
     * Whether enough of [uri]'s head is on disk to be worth a decode, claiming
     * the attempt if so.
     *
     * The byte threshold is derived from the rendition's own average bitrate
     * where the duration is known, because "30 seconds of audio" is a wildly
     * different number of bytes at 96 kbps and at lossless.
     * [HEAD_BYTES_MARGIN] covers the container header and the fact that a
     * track's opening is rarely at its own average bitrate.
     *
     * Where the duration isn't known — which is the common case, since callers
     * request analysis before anything has read the container — the estimate is
     * unavailable and [MIN_HEAD_BYTES] stands in. That is about 30 s of a
     * typical stream but only a few seconds of lossless, so a single attempt
     * gated on it would be spent on too little audio for exactly the tracks
     * that carry the most bytes per second.
     *
     * Hence retrying on growth rather than attempting once: an attempt is
     * allowed again only when the cached prefix has [HEAD_RETRY_GROWTH]-fold
     * grown since the last one. A track therefore gets a handful of tries
     * spread across its download instead of either one try or one per tick.
     */
    private fun headWorthTrying(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        // Across every rendition of the recording, not just the one the player
        // happens to be on. The same track can be part-downloaded under a
        // sibling cache key, and the live URI's own copy is frequently the one
        // holding nothing — a track that reported six megabytes cached twenty
        // minutes earlier reported zero here, because the question was being
        // asked of the wrong copy of it.
        val candidate = cache.renditionsOf(uri)
            .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(uri.getQueryParameter("v"), it.key) }
            .filter { it.cachedPrefix > 0L && it.key !in badRenditions }
            // The growth guard, applied as a filter rather than to the winner.
            // Applied afterwards it did not skip a copy, it ended the search: the
            // single best candidate was chosen, refused for not having grown, and
            // the second-best — frequently the one that would have worked — was
            // never reached. A track therefore got exactly one head attempt ever,
            // against whichever copy of it happened to rank highest at the time.
            .filter { rendition ->
                val previous = headAttempts[rendition.key] ?: return@filter true
                rendition.cachedPrefix >= previous * HEAD_RETRY_GROWTH
            }
            // Most *audio*, not most bytes: renditions differ in bitrate, so the
            // largest prefix is not necessarily the longest playable head.
            .maxByOrNull { headSecondsOf(it, durationSeconds) }
            ?: return null

        val prefix = candidate.cachedPrefix
        val total = candidate.contentLength
        val needed = if (durationSeconds.isFinite() && durationSeconds > MIN_HEAD_SECONDS && total > 0) {
            val bytesPerSecond = total / durationSeconds
            // Sized to [MIN_HEAD_SECONDS] — the shortest decode [analyzeHead]
            // will accept — not to the model's full window. Gating on the full
            // window meant demanding two and a half times the input the analysis
            // would actually settle for: a lossless rendition needs nine
            // megabytes on disk for thirty seconds of audio, and a track sitting
            // at six was refused outright despite holding twice what was needed
            // to produce a result. Whatever *is* cached still gets decoded — the
            // read simply runs out — so a larger prefix is used when there is
            // one, and [HEAD_RETRY_GROWTH] comes back for a better look as the
            // rest arrives.
            (MIN_HEAD_SECONDS * bytesPerSecond * HEAD_BYTES_MARGIN).toLong()
                .coerceAtLeast(MIN_HEAD_BYTES)
                .coerceAtMost(total)
        } else {
            MIN_HEAD_BYTES
        }
        if (prefix < needed) {
            // Once per track, not per tick: a head pass that never fires is
            // invisible otherwise, which is exactly how the first version of
            // this shipped doing nothing at all.
            if (headSkipLogged.add("$trackId@${candidate.key}")) {
                Log.d(
                    TAG,
                    "Head pass for $trackId waiting: ${prefix / 1024}kB cached of " +
                        "${needed / 1024}kB needed (rendition ${candidate.key})",
                )
            }
            return null
        }

        headAttempts[candidate.key] = prefix
        return candidate
    }

    /**
     * Roughly how many seconds of audio a rendition's cached prefix holds.
     *
     * The ranking this feeds used to be `cachedPrefix / contentLength`, which
     * answers zero whenever the length is unknown — and the length is unknown
     * for precisely the entry that matters most, the head
     * [AudioCache.requestAnalysisHead] just fetched, because a bounded request
     * gets a bounded answer. A megabyte of freshly downloaded opening therefore
     * scored below a sibling holding eight unusable kilobytes, and the analyzer
     * spent its one attempt on the wrong copy.
     *
     * [ASSUMED_BYTES_PER_SECOND] stands in where the length still isn't known.
     * It only has to be the right order of magnitude: this decides which copy to
     * read first, not whether the result is trusted.
     */
    private fun headSecondsOf(rendition: AudioCache.Rendition, durationSeconds: Double): Double =
        if (rendition.contentLength > 0 && durationSeconds.isFinite() && durationSeconds > 0) {
            rendition.cachedPrefix * durationSeconds / rendition.contentLength
        } else {
            rendition.cachedPrefix / ASSUMED_BYTES_PER_SECOND
        }

    /**
     * The opening window only: a beat grid, and nothing that would need the rest
     * of the file.
     *
     * Runs [TrackFeatures] over the head, but copies across only the fields
     * that describe an *entry*: where the file starts making sound, the pickup,
     * the end of the intro, and the mix-in candidates. Those are all measured
     * within the opening seconds, so a head-only pass measures them exactly as
     * a whole-track pass would.
     *
     * Everything that describes the rest of the track is dropped on the floor —
     * content end, outro, mix-out anchors, the energy curve. Over a 30 s head
     * that pass does not fail, it answers confidently about a track that is
     * mostly missing, and the planner has no way to tell the difference. Left at
     * their defaults they read as "no evidence": [contentEndTime] falls back to
     * the real duration and the mix-out list ranks as empty.
     *
     * The energy curve is dropped for the same reason even though it is
     * genuinely measured here: the policy indexes the vocal mask against it and
     * counts audible seconds from it, and a curve that stops at 30 s would have
     * this track's *outgoing* half scored against a window it does not cover.
     * A vocal mask therefore cannot come from this pass either, and waits for
     * the whole-track one.
     */
    private fun analyzeHead(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        rendition: AudioCache.Rendition,
    ): TrackAnalysis? {
        fun openSource(): MediaDataSource? = cache.renditionDataSource(uri, rendition)

        // Same guard the whole-track pass applies, for the same reason: a
        // sibling rendition can be a different cut, and a beat grid borrowed
        // across that would put every anchor seconds out. Skipped for the
        // player's own copy, which is the track by definition. A header that
        // will not parse yet is not held against the rendition — more bytes may
        // well fix it — but a length that genuinely disagrees is.
        val expected = durationSeconds.takeIf { it.isFinite() && it > 0 }
        if (expected != null && rendition.key != cache.cacheKeyOf(uri)) {
            val length = openSource()?.use(AudioDecoder::containerDurationSeconds)
            if (length == null || length <= 0) {
                // Logged rather than returned quietly. This is the likeliest way
                // for a head pass to do nothing — a partial container the
                // extractor will not read a duration out of — and while it was
                // silent the whole path looked like it had never run.
                Log.d(TAG, "Head rendition ${rendition.key} for $trackId has no readable duration yet")
                return null
            }
            if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    TAG,
                    "Head rendition ${rendition.key} rejected for $trackId: " +
                        "${"%.1f".format(Locale.ROOT, length)}s against ${"%.1f".format(Locale.ROOT, expected)}s expected",
                )
                badRenditions.add(rendition.key)
                return null
            }
        }

        playbackFirst()
        val window = BeatTracker.WINDOW_SECONDS
        val head = region(::openSource, 0.0, window, features = null, deriveFeatures = true)
            ?: run {
                // An abandoned decode comes back the same way a broken one
                // does, and only one of the two is worth a warning: see
                // [stopped]. Nothing is recorded either way — the caller
                // publishes only a non-null result — so the track is simply
                // left unmeasured, which is what it is.
                if (stopped) return null
                Log.d(TAG, "Head pass for $trackId could not decode rendition ${rendition.key}")
                return null
            }
        // What was decoded, not what was asked for: the source stops where the
        // cache does. A tempo read off a few seconds is not a weaker measurement
        // than one read off thirty, it is a different and much more credulous
        // one, and the planner cannot see the difference — so it is refused here
        // and the next attempt gets more of the file.
        if (head.seconds < MIN_HEAD_SECONDS) {
            Log.d(TAG, "Head pass for $trackId decoded only ${"%.1f".format(Locale.ROOT, head.seconds)}s; too short")
            return null
        }
        val grid = head.grid
        val entry = head.features
        if (grid == null && entry == null) {
            Log.d(TAG, "Head pass for $trackId produced nothing usable")
            return null
        }

        Log.d(
            TAG,
            "Analysed head of $trackId: bpm=${grid?.bpm ?: entry?.bpm} " +
                "conf=${grid?.beatConfidence ?: entry?.beatConfidence} " +
                "audibleStart=${entry?.audibleStartTime} pickup=${entry?.pickupTime} " +
                "introEnd=${entry?.introEndTime} mixInCandidates=${entry?.mixInCandidates?.size ?: 0} " +
                "over ${"%.1f".format(Locale.ROOT, head.seconds)}s",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = durationSeconds,
            quality = TrackAnalysis.QUALITY_HEAD,
            bpm = grid?.bpm ?: entry?.bpm ?: 0.0,
            beatInterval = grid?.beatInterval ?: entry?.beatInterval ?: 0.0,
            beatConfidence = grid?.beatConfidence ?: entry?.beatConfidence ?: 0.0,
            downbeats = grid?.downbeats ?: entry?.downbeats.orEmpty(),
            firstBeat = grid?.firstBeat ?: entry?.firstBeat ?: 0.0,
            key = entry?.key.orEmpty(),
            keyConfidence = entry?.keyConfidence ?: 0.0,
            audibleStartTime = entry?.audibleStartTime,
            pickupTime = entry?.pickupTime,
            introEndTime = entry?.introEndTime ?: 0.0,
            mixInTime = entry?.mixInTime ?: 0.0,
            mixInCandidates = entry?.mixInCandidates.orEmpty(),
            headBeats = grid?.let { BeatSide(it.beats, it.downbeats, it.bpm, it.beatConfidence) },
            levelWindows = listOfNotNull(head.levelWindow),
        )
    }

    /**
     * Picks which rendition of [uri]'s recording to analyse: the lightest one
     * that is both complete and the same cut as the track being played.
     *
     * A recording can be on disk two or three times over — the Opus stream
     * YouTube served, a substituted source's copy, a later quality upgrade — and
     * they hold the same music, so an analysis of any of them describes all of
     * them. Analysing the smallest is not merely cheaper: it is the one that
     * finished downloading first, and a lossless upgrade can take most of a
     * track's play time to arrive. Waiting for it is why analysis was landing
     * seconds *after* the transition it was meant to inform.
     *
     * The duration check is what makes the sharing safe. A `#alt` rendition
     * comes from an entirely different source and may be a different cut —
     * a radio edit, a version with a longer intro — and a beat grid borrowed
     * across that difference would put every downbeat and both mix anchors
     * seconds out. Comparing container durations catches exactly that, and
     * costs a header parse per candidate.
     */
    private fun chooseRendition(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
    ): AudioCache.Rendition? {
        val complete = cache.renditionsOf(uri)
            .filter { AutomixAnalysisSource.isCanonicalYouTubeRendition(uri.getQueryParameter("v"), it.key) }
            .filter { it.isComplete && it.key !in badRenditions }
        if (complete.isEmpty()) return null

        val expected = durationSeconds.takeIf { it.isFinite() && it > 0 }
        // Without a length to check a sibling against, sharing would be a guess,
        // so only the rendition actually being played can be trusted.
        if (expected == null) {
            val own = cache.cacheKeyOf(uri)
            complete.firstOrNull { it.key == own }?.let { return it }
            // Nothing to cross-check against — but one copy is not ambiguous
            // either, and refusing it outright is a dead end rather than a
            // safeguard. [cacheKeyOf] answers with whichever rendition the key
            // factory resolves to *now*, which with substitution on is the `#alt`
            // entry; the copy actually on disk is routinely the plain one, so
            // this asked for a rendition that did not exist and returned null on
            // every tick, silently, for as long as the track stayed queued.
            //
            // The risk the duration check exists to catch is borrowing a beat
            // grid across two different cuts of a song. That needs two copies to
            // choose wrongly between. With exactly one there is no choice being
            // made, and the worst case degrades from "never analysed" to "a grid
            // measured off the only audio we have".
            return complete.singleOrNull()?.also {
                Log.d(
                    TAG,
                    "Analysing $trackId from its only cached rendition ${it.key}; " +
                        "no duration to check it against",
                )
            }
        }

        for (candidate in complete) {
            val length = cache.renditionDataSource(uri, candidate)
                .use(AudioDecoder::containerDurationSeconds)
            if (length == null || length <= 0) {
                // Every candidate here is complete, so no more bytes are coming
                // to make the header readable: remembered for the reason below.
                badRenditions.add(candidate.key)
                continue
            }
            if (abs(length - expected) > RENDITION_DURATION_TOLERANCE) {
                Log.d(
                    TAG,
                    "Rendition ${candidate.key} rejected for $trackId: " +
                        "${"%.1f".format(Locale.ROOT, length)}s against ${"%.1f".format(Locale.ROOT, expected)}s expected",
                )
                // Remembered, as the head pass remembers its own rejections. A
                // cut that is the wrong length now is the wrong length on the
                // next tick too, and while it counted as complete [request]
                // queued a pass on every one of them — reopening and parsing the
                // same file four times a second for as long as the track played.
                badRenditions.add(candidate.key)
                continue
            }
            if (candidate.key != cache.cacheKeyOf(uri)) {
                Log.d(
                    TAG,
                    "Analysing $trackId from lighter rendition ${candidate.key} " +
                        "(${candidate.contentLength / 1024}kB)",
                )
            }
            return candidate
        }
        return null
    }

    /**
     * Where a whole-track pass reads its audio from, and the identity the retry
     * bookkeeping keys off.
     *
     * Two kinds of copy exist and they are not interchangeable. A **cached
     * rendition** is one of several copies of a streamed recording: chosen
     * between by [chooseRendition], cross-checked for being the same cut, and —
     * when it turns out undecodable — thrown off disk so a clean one can replace
     * it. A **local file** is the track itself: exactly one of it, complete from
     * the moment it exists, nothing to choose between, and not ours to delete.
     *
     * [rendition] is what tells the two apart. Null means the audio is a file on
     * the device, which is why the blame-and-discard half of [structure] is
     * conditional on it rather than on a flag.
     */
    private class Copy(
        val key: String,
        val rendition: AudioCache.Rendition?,
        val open: () -> MediaDataSource?,
    )

    /**
     * Which copy of [trackId]'s audio to read, or null when there isn't a usable
     * one yet.
     *
     * A local URI short-circuits the entire rendition search: none of what that
     * search decides between applies to a file the user already has, and asking
     * the cache about one only ever produced the empty answer that kept Automix
     * from analysing local tracks at all. The URI stands in as the cache key,
     * which is what [request]'s `untried` check reads so a file that will not
     * decode is not re-decoded on every tick for the rest of the session.
     */
    private fun copyToRead(trackId: String, uri: Uri, durationSeconds: Double): Copy? {
        if (LocalAudioSource.isLocal(uri)) {
            return Copy(key = uri.toString(), rendition = null) { LocalAudioSource.open(resolver, uri) }
        }
        val rendition = chooseRendition(trackId, uri, durationSeconds) ?: return null
        return Copy(key = rendition.key, rendition = rendition) { cache.renditionDataSource(uri, rendition) }
    }

    /**
     * djmix25 streaming fallback for [loopRegion]: best-effort decode with no
     * complete-rendition gate. Tries, in order: the resolved YouTube
     * analysis URI (source-backed tracks), any cached prefix on the
     * playback URI (ignoring isComplete/badRenditions), and the resolved
     * URI's prefix. The actual region decode still goes through
     * AudioDecoder.decodeRegionStereo (the streaming decodeRaw path in
     * AudioDecoder.streamRegion shares this extractor loop), so a track
     * that is only prefixes-cached still arms a loop deck.
     */
    private fun streamingCopyForLoop(trackId: String, uri: Uri, durationSeconds: Double): Copy? {
        if (LocalAudioSource.isLocal(uri)) return null
        // Source-backed playback: read the pinned YouTube analysis copy.
        val analysisUri = analysisUriFor(trackId, uri)
        if (analysisUri != null && analysisUri != uri) {
            val viaAnalysis = runCatching { copyToRead(trackId, analysisUri, durationSeconds) }.getOrNull()
            if (viaAnalysis != null) return viaAnalysis
            val prefix = cache.renditionsOf(analysisUri)
                .filter { it.cachedPrefix > 0L && it.key !in badRenditions }
                .maxByOrNull { it.cachedPrefix }
            if (prefix != null) {
                return Copy(key = prefix.key, rendition = prefix) { cache.renditionDataSource(analysisUri, prefix) }
            }
        }
        val prefix = cache.renditionsOf(uri)
            .filter { it.cachedPrefix > 0L && it.key !in badRenditions }
            .maxByOrNull { it.cachedPrefix }
            ?: return null
        return Copy(key = prefix.key, rendition = prefix) { cache.renditionDataSource(uri, prefix) }
    }

    /**
     * The URI analysis reads for [trackId].
     *
     * Source-backed playback URIs (JioSaavn, module/lossless, etc.) carry the
     * same title/artist/runtime matching hints as a YouTube item. Resolve that
     * identity once, then keep playback on its original URI while analysis fills
     * and reads the separately pinned YouTube Opus entry. While the match is
     * pending or has failed, the playback URI itself is returned as an
     * in-memory provisional (tagged in [sourceProvisionalKeys], never persisted
     * to [AnalysisStore] until the canonical match lands).
     */
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

    /**
     * What a whole-track pass came back with.
     *
     * [decodedShort] is the difference between "this copy is broken, strike it"
     * and "there was no copy to read", which the caller counts very differently:
     * three strikes writes a track off for the session. Conflating the two spent
     * all three in 922ms on a track whose only complete copy had just been
     * rejected — the following two attempts decoded nothing because there was
     * nothing left to decode, and were counted as though they had tried.
     */
    private class WholeTrack(val analysis: TrackAnalysis?, val decodedShort: Boolean = false, val aborted: Boolean = false)

    /**
     * What Pass 1 came back with.
     *
     * [decodedShort] has to survive the return rather than collapsing into a null [features]: it is
     * the same distinction [WholeTrack.decodedShort] draws, between a broken copy and no copy, and
     * only one of the two is a strike.
     */
    private class Structural(
        val features: TrackFeatures.Features?,
        val decodedShort: Boolean = false,
        val map: SongMap? = null,
    )

    /**
     * Decodes the whole track and reduces it to DSP features.
     *
     * A method rather than a block in [analyze] for a reason that is about memory, not tidiness —
     * see the call site. Everything it decodes is dead by the time it returns, and returning is
     * what makes that true of the heap as well as of the program.
     */
    private fun structure(
        trackId: String,
        uri: Uri,
        copy: Copy,
        effectiveDuration: Double,
    ): Structural {
        val structRate = TrackFeatures.sampleRate
        val decoded = copy.open()?.use { AudioDecoder.decodeRegion(it, 0.0, effectiveDuration) { yieldOrStopped() } }
            ?: return Structural(null)
        val (pcm, _) = decoded

        // A decode that stops early is indistinguishable, downstream, from a
        // track that simply goes quiet: [TrackFeatures] is handed the
        // container's full duration alongside a short buffer, reads the
        // difference as trailing silence, and puts the mix-out anchor where the
        // bytes ran out. Nothing about the result looks wrong — it is a complete
        // analysis with a plausible contentEnd — and the audible symptom is the
        // track being faded out minutes early. Refused outright rather than
        // published, because a missing analysis degrades to a plain crossfade
        // while a confidently wrong one does not degrade at all.
        val decodedSeconds = if (pcm.sampleRate > 0) pcm.samples.size / pcm.sampleRate else 0.0
        if (decodedSeconds < effectiveDuration * MIN_DECODED_FRACTION) {
            Log.w(
                TAG,
                "Analysis of $trackId refused: ${copy.key} decoded " +
                    "${"%.1f".format(Locale.ROOT, decodedSeconds)}s of a " +
                    "${"%.1f".format(Locale.ROOT, effectiveDuration)}s " +
                    // The two causes are different enough to be worth naming. A
                    // cached rendition stops at the first hole read-ahead left in
                    // it; a file on the device has no holes, so a short decode
                    // there means the container itself is truncated or damaged.
                    if (copy.rendition != null) "container — cached with holes?" else "container — truncated file?",
            )
            // Both halves of this are about *renditions*, so both are conditional
            // on there being one. A local file has no sibling copy to be routed to
            // instead and is not ours to delete either way; the three-strike count
            // in [request] is the whole bound there, and it is enough, because a
            // file on disk that decodes short will decode short again.
            copy.rendition?.let { rendition ->
                // Remembered, so the retry reaches for a *different* rendition. This
                // is the whole reason the lightest one is only a preference: a
                // rendition the cache index calls complete can still decode short if
                // it was written badly, and without this the retries would pick the
                // same broken copy three times over and give up on a track whose
                // heavier rendition would have analysed perfectly well.
                badRenditions.add(rendition.key)
                // And thrown off disk, not merely remembered. A file the cache calls
                // complete and the decoder gives up on partway is not going to
                // improve: nothing else will ever write to it, because as far as the
                // cache is concerned it is finished. Remembering it only helps for as
                // long as this process lives — a restart clears the set, the same
                // bytes are read again, and the same seconds are spent reaching the
                // same conclusion. Deleting it is what lets a clean copy be fetched.
                // Skipped for whatever the player is reading from; see
                // [AudioCache.discardBadRendition].
                if (rendition.isComplete && discardsOf(rendition.key) < MAX_RENDITION_DISCARDS &&
                    cache.discardBadRendition(uri, rendition.key)
                ) {
                    // Every memory of that copy goes with the bytes. Deleting the
                    // file and then still refusing its key is the worst of both: the
                    // clean copy [AudioCache.requestAnalysisHead] fetches in its place
                    // is filtered straight back out by [badRenditions], the track is
                    // written off for the session anyway, and the download was spent
                    // on nothing. The strike count goes too — the next attempt reads
                    // genuinely different bytes, so it starts level.
                    discarded.merge(rendition.key, 1, Int::plus)
                    badRenditions.remove(rendition.key)
                    triedRenditions.remove(rendition.key)
                    shortDecodes.remove(trackId)
                }
            }
            return Structural(null, decodedShort = true)
        }

        val samples = if (abs(pcm.sampleRate - structRate) > 1.0) {
            TrackFeatures.resample(pcm.samples, pcm.sampleRate, structRate) ?: return Structural(null)
        } else {
            pcm.samples
        }

        val features = TrackFeatures.analyze(samples, effectiveDuration)
        var structureMap: SongMap? = null
        if (features != null && !skipPhraseStructure()) {
            val started = SystemClock.elapsedRealtime()
            // djmix18 (Bug 3): the full-track mono PCM is already in memory (decoded for
            // TrackFeatures). Let StructureAnalyzer see it — up to 360s / 160 bars — so
            // SongMap has real OUTRO sections in the final 22% of the song. The beat-sync
            // DSP is ~<50ms at 360s. The 120s cap only ever truncated the duration flag,
            // producing SongMap(durationSec=120, partial) on 210s tracks and early exits.
            // partial is derived from fullTrackDurationSec (shared: full > pcm + 1.0).
            val fullCap = (structRate * 360.0).toInt().coerceAtLeast(1)
            val fullPcm = if (samples.size > fullCap) samples.copyOf(fullCap) else samples
            val map = runCatching {
                StructureAnalyzer.fromPcm(
                    trackKey = trackId,
                    pcm = fullPcm,
                    sampleRate = structRate.toInt(),
                    bpm = features.bpm.coerceAtLeast(1.0),
                    partial = samples.size > fullCap,
                    startedMs = started,
                    now = { SystemClock.elapsedRealtime() },
                    fullTrackDurationSec = effectiveDuration,
                )
            }.onFailure { Log.i("BitChordPhrase", "structure skipped: ${it.message}") }.getOrNull()
            // djmix18: when the head path truncates (pcm < effectiveDuration), the map
            // is partial by definition — assert it so a 120s buffer can never publish a
            // 120s-duration non-partial map on a longer track.
            val correctedMap = if (map != null && !map.partial && effectiveDuration > (fullPcm.size / structRate) + 1.0) {
                map.copy(partial = true)
            } else map
            if (correctedMap != null) {
                PhraseMaps.put(trackId, correctedMap)
                structureMap = correctedMap
                Log.i(
                    "BitChordPhrase",
                    "structMs=${correctedMap.structMs} structWallMs=${SystemClock.elapsedRealtime() - started} " +
                        "bars=${correctedMap.sections.lastOrNull()?.endBar ?: 0} partial=${correctedMap.partial} " +
                        "duration=${correctedMap.durationSec} contentEnd=${correctedMap.contentEndSec}",
                )
            }
        }
        return Structural(features, map = structureMap)
    }

    /** Severe heat or battery saver: keep the scalar map and skip the PCM structure pass. */
    private fun skipPhraseStructure(): Boolean {
        thermalWatch
        if (thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE) return true
        val power = context.getSystemService(PowerManager::class.java)
        return power?.isPowerSaveMode == true
    }

    /**
     * The whole-track pass.
     *
     * Pass-1 (high priority): a BeatThis grid + lightweight DSP on the head, so
     * [onGridReady] publishes a usable intermediate in ~1-2s. Pass-2 (low
     * priority): the full model windows + [structure], meanwhile checking
     * [shouldAbort] between regions so a stale Pass-2 never starves a fresh
     * Pass-1 for current/next.
     */
    private fun analyze(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        onGridReady: (Double, BeatTracker.Grid?, TrackFeatures.Features?, CurveWindow?) -> Unit = { _, _, _, _ -> },
        shouldAbort: () -> Boolean = { false },
    ): WholeTrack {
        val copy = copyToRead(trackId, uri, durationSeconds) ?: return WholeTrack(null)
        // Recorded before the outcome is known, because what this gates is
        // whether a *written-off* track is worth reopening, and the answer is
        // only ever "yes" for a copy that has not been read yet. Recording it on
        // success alone would leave a failure looking permanently reopenable and
        // re-decode the same file on every tick.
        triedRenditions.add(copy.key)
        val openSource = copy.open

        var effectiveDuration = durationSeconds
        if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
            effectiveDuration = openSource()?.use(AudioDecoder::containerDurationSeconds) ?: 0.0
        }
        if (effectiveDuration <= 0) {
            Log.d(TAG, "Skipping $trackId: ${copy.key} has no readable duration")
            return WholeTrack(empty(trackId, 0.0))
        }

        val span = modelWindowSeconds()
        val headEnd = minOf(effectiveDuration, HEAD_MODEL_WINDOWS * span - (HEAD_MODEL_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS)
        val tailStart = max(0.0, effectiveDuration - (TAIL_MODEL_WINDOWS * span - (TAIL_MODEL_WINDOWS - 1) * WINDOW_OVERLAP_SECONDS))

        // Pass-1 now, before Structure/UMX Pass-2: BeatThis head grid + light DSP.
        // BeatThis reads a 29.76s window decoupled from UMX's 22.245s window, so
        // this inference alone makes BPM/beats/downbeats/confidence usable.
        if (!stopped) {
            playbackFirst()
            val preHead = region(openSource, 0.0, minOf(effectiveDuration, span), features = null, deriveFeatures = true)
            if (preHead != null) {
                onGridReady(effectiveDuration, preHead.grid, preHead.features, preHead.levelWindow)
            }
        }

        playbackFirst()
        val structural = structure(trackId, uri, copy, effectiveDuration)
        if (stopped) return WholeTrack(null)
        if (structural.decodedShort) return WholeTrack(null, decodedShort = true)
        val features = structural.features ?: return WholeTrack(empty(trackId, effectiveDuration))

        // Pass-2 (low priority): the model windows. Checked between windows so a
        // stale pass aborts (keeping the intermediate) instead of starving the
        // urgent head/grid work queued behind it.
        val regions = ArrayList<Region>()
        for ((from, to) in modelWindows(headEnd, tailStart, effectiveDuration, span)) {
            if (stopped) return WholeTrack(null)
            if (shouldAbort()) return WholeTrack(null, aborted = true)
            region(openSource, from, to, features)?.let(regions::add)
        }
        if (stopped) return WholeTrack(null)

        val beats = seamBeats(regions)
        val headSide = beatSide(beats, 0.0, headEnd)
        val tailSide = beatSide(beats, tailStart, effectiveDuration)
        val headGrid = headSide?.toGrid()
        val tailGrid = tailSide?.toGrid()

        val leading = tailGrid ?: headGrid

        Log.d(
            TAG,
            "Analysed $trackId: bpm=${leading?.bpm ?: features.bpm} " +
                "conf=${leading?.beatConfidence ?: features.beatConfidence} " +
                "key=${features.key} contentEnd=${features.contentEndTime} " +
                "mixOutCandidates=${features.mixOutCandidates.size} " +
                "grid=${if (leading != null) "model" else "dsp"} " +
                "beats=${headGrid?.beats?.size ?: 0}+${tailGrid?.beats?.size ?: 0} " +
                "downbeats=${headGrid?.downbeats?.size ?: 0}+${tailGrid?.downbeats?.size ?: 0} " +
                "vocalMask=${if (regions.any { it.vocalMask != null }) "model" else "dsp"} " +
                "windows=${regions.size} vocalWindows=${regions.count { it.vocalWindow != null }}",
        )

        val analysis = TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = trackId,
            duration = effectiveDuration,
            quality = TrackAnalysis.QUALITY_FULL,
            contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: effectiveDuration,
            bpm = leading?.bpm ?: features.bpm,
            beatInterval = leading?.beatInterval ?: features.beatInterval,
            beatConfidence = leading?.beatConfidence ?: features.beatConfidence,
            downbeats = (headGrid?.downbeats.orEmpty() + tailGrid?.downbeats.orEmpty())
                .ifEmpty { features.downbeats }
                .sorted(),
            firstBeat = headGrid?.firstBeat ?: features.firstBeat,
            phraseBoundaries = features.phraseBoundaries,
            key = features.key,
            keyConfidence = features.keyConfidence,
            // djmix25: per-region keys from the dual-profile native pass.
            keyHead = features.keyHead,
            keyTail = features.keyTail,
            keyHeadConfidence = features.keyHeadConfidence,
            keyTailConfidence = features.keyTailConfidence,
            audibleStartTime = features.audibleStartTime,
            pickupTime = features.pickupTime,
            introEndTime = features.introEndTime,
            outroStartTime = features.outroStartTime,
            mixInTime = features.mixInTime,
            mixOutTime = features.mixOutTime,
            mixInCandidates = features.mixInCandidates,
            mixOutCandidates = features.mixOutCandidates,
            energyCurve = features.energyCurve,
            lowEnergyCurve = features.lowEnergyCurve,
            vocalActivityMask = mergeMasks(features.energyCurve.size, regions.mapNotNull { it.vocalMask })
                ?: features.vocalActivityMask.map(::heuristicVocalOnModelScale),
            vocalProbability = features.vocalProbability,
            headBeats = headSide,
            tailBeats = tailSide,
            vocalWindows = regions.mapNotNull { it.vocalWindow },
            levelWindows = regions.mapNotNull { it.levelWindow },
            instrumentalWindows = regions.mapNotNull { it.instrumentalWindow },
        )
        if (structural.map != null) {
            val enriched = SongMapEnrichment.enrich(structural.map, analysis)
            if (enriched !== structural.map) {
                PhraseMaps.put(trackId, enriched)
            } else if (analysis.vocalWindows.isNotEmpty() && analysis.bpm > 0.0 && structural.map.bpm > 0.0 &&
                abs(structural.map.bpm - analysis.bpm) / analysis.bpm > 0.02
            ) {
                Log.d(TAG, "SongMap enrichment skipped for $trackId: bpm mismatch base=${structural.map.bpm} analysis=${analysis.bpm}")
            }
        }
        return WholeTrack(analysis)
    }

    /**
     * Everything a decoded region contributes, once its audio has been let go
     * of. [seconds] is what was actually decoded, which for a partially cached
     * file is not what was asked for.
     */
    private class Region(
        val grid: BeatTracker.Grid?,
        val vocalMask: DoubleArray?,
        val seconds: Double,
        /** Only populated when the caller asked for it; see [region]'s `deriveFeatures`. */
        val features: TrackFeatures.Features? = null,
        /** Where the decode really began (the extractor seeks to a sync sample at or before the ask). */
        val start: Double = 0.0,
        /** The vocal model's raw output over this window, for the loop mix's vocal check. */
        val vocalWindow: CurveWindow? = null,
        /** RMS level over this window, for the loop mix's loudness check. */
        val levelWindow: CurveWindow? = null,
        /** djmix25 instrumental share (1 - vocal) over this window, for acapella handoffs. */
        val instrumentalWindow: CurveWindow? = null,
    )

    /**
     * Seconds of audio one model window covers: the vocal model's fixed input,
     * which is the shorter of the two models' (the beat model's 1500 frames run
     * to ~30 s). One window is then exactly one inference of each.
     */
    private fun modelWindowSeconds(): Double =
        if (VocalSpectrogram.available) {
            (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        } else {
            BeatTracker.WINDOW_SECONDS
        }

    /**
     * The windows the models run over: [HEAD_MODEL_WINDOWS] from the start and
     * [TAIL_MODEL_WINDOWS] up to the end, each [span] long and overlapping its
     * neighbour by [WINDOW_OVERLAP_SECONDS] so the beat at a seam is seen from
     * both sides. A short track, where the two spans meet, is covered once.
     */
    private fun modelWindows(headEnd: Double, tailStart: Double, duration: Double, span: Double): List<Pair<Double, Double>> {
        val spans = if (tailStart <= headEnd) {
            listOf(0.0 to duration)
        } else {
            listOf(0.0 to headEnd, tailStart to duration)
        }
        val windows = ArrayList<Pair<Double, Double>>()
        for ((from, to) in spans) {
            val length = to - from
            if (length < 1.0) continue
            if (length <= span) {
                windows += from to to
                continue
            }
            // As few windows as cover the span with at least the overlap between
            // neighbours, spread evenly from its start to its end.
            val count = ceil((length - WINDOW_OVERLAP_SECONDS) / (span - WINDOW_OVERLAP_SECONDS) - 1e-6).toInt()
            val stride = (length - span) / (count - 1)
            for (index in 0 until count) {
                val start = from + index * stride
                windows += start to minOf(to, start + span)
            }
        }
        return windows
    }

    /** One beat list across the windows: at each overlap, the earlier window's beats before its midpoint, the later's after. */
    private class Beats(val beats: List<Double>, val downbeats: List<Double>, val confidence: Double)

    private fun seamBeats(regions: List<Region>): Beats {
        val graded = regions.filter { it.grid != null }.sortedBy { it.start }
        if (graded.isEmpty()) return Beats(emptyList(), emptyList(), 0.0)
        var weighted = 0.0
        var count = 0
        for (region in graded) {
            val grid = region.grid!!
            weighted += grid.beatConfidence * grid.beats.size
            count += grid.beats.size
        }
        val joined = GridRepair.reconcile(
            graded.map { region ->
                val grid = region.grid!!
                GridRepair.Window(grid.beats, grid.downbeats, grid.bpm, grid.beatConfidence)
            },
        )
        return Beats(joined.beats, joined.downbeats, if (count > 0) weighted / count else 0.0)
    }

    /** The model's grid over [from]..[to], or null where it found too few beats there. */
    private fun beatSide(all: Beats, from: Double, to: Double): BeatSide? {
        val beats = all.beats.filter { it >= from && it <= to }
        val bpm = BeatTracker.tempoFromBeats(beats)
        if (bpm <= 0) return null
        return BeatSide(
            beats = beats,
            downbeats = all.downbeats.filter { it >= from && it <= to },
            bpm = bpm,
            confidence = all.confidence,
        )
    }

    private fun BeatSide.toGrid() = BeatTracker.Grid(
        beats = beats,
        downbeats = downbeats,
        bpm = bpm,
        beatInterval = 60 / bpm,
        firstBeat = beats.first(),
        beatConfidence = confidence,
    )

    /**
     * Decodes one stereo region and runs both models over it, returning only their results.
     *
     * The point of the function boundary is the audio: the stereo buffer, its mono mix and the
     * resampled copies are all local, so they become collectible the moment this returns rather
     * than staying live until the whole analysis finishes. A 30 s stereo region is several
     * megabytes before either model's own working set is counted.
     *
     * Null, or a null field, means "no model evidence for this window" — a codec that will not
     * configure, a region too short, a missing model — which [analyze] already falls back on.
     *
     * The extractor seeks to a sync sample at or before what was asked for, so the region's real
     * start (not [startSeconds]) is what its beat times must be stated against.
     */
    private fun region(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        features: TrackFeatures.Features?,
        deriveFeatures: Boolean = false,
    ): Region? {
        playbackFirst()
        val decoded = openSource()?.use { AudioDecoder.decodeRegionStereo(it, startSeconds, endSeconds) { yieldOrStopped() } }
            ?: run {
                // The two ways this comes back empty mean opposite things and
                // were reported identically, which cost a round of guessing:
                // this one is the extractor refusing the container outright, so
                // the bytes are wrong or not enough of them are there to parse.
                Log.d(TAG, "Region [$startSeconds, $endSeconds) would not open")
                return null
            }
        val (stereo, actualStart) = decoded
        if (stereo.left.size < stereo.sampleRate) {
            // And this one is a container that parsed fine and yielded under a
            // second of audio — a decode that started and ran out, not one that
            // never started.
            Log.d(TAG, "Region [$startSeconds, $endSeconds) decoded ${stereo.left.size} frames; too few")
            return null
        }

        val seconds = stereo.left.size / stereo.sampleRate
        // In a frame of its own so the full-rate mono downmix is released before either model runs.
        // It is 23 MB for this window at 48 kHz — the same size as each of the two channels it
        // averages — and it is read exactly twice, to make the resampled model input and the DSP
        // one. As a local it would nonetheless stay reachable through `tracker.track` and
        // `vocalMask` below, which is where the analysis allocates most heavily and where the
        // process was dying. Same reasoning [derived] already had, one level further out.
        val inputs = regionInputs(stereo, seconds, deriveFeatures)

        playbackFirst()
        val (rawGrid, vocalValues) = synchronized(modelLock) {
            val grid = inputs.forModel?.let { tracker.track(it, offsetSeconds = actualStart) }
            val vocals = if (features != null) vocalValues(stereo) else null
            grid to vocals
        }
        return Region(
            grid = rawGrid?.let { snapGrid(it, stereo, actualStart) },
            vocalMask = if (features != null && vocalValues != null) vocalMask(vocalValues, features, actualStart) else null,
            seconds = seconds,
            features = inputs.derived,
            start = actualStart,
            vocalWindow = vocalValues?.let { CurveWindow(actualStart, VocalSpectrogram.frameRate, it) },
            levelWindow = if (features != null || deriveFeatures) levelCurve(stereo, actualStart) else null,
            // djmix25: instrumental = mix - vocal share, derived from the same
            // umxhq vocal tensor (single-stem scalar complement, no second pass).
            // Only meaningful when windowed stems are allowed on this device.
            instrumentalWindow = if (vocalValues != null && canUseWindowedStems()) {
                CurveWindow(actualStart, VocalSpectrogram.frameRate, FloatArray(vocalValues.size) { 1f - vocalValues[it] })
            } else null,
        )
    }

    /**
     * Pulls the model's beats onto spectral-flux onsets, at most 35 ms, and
     * lowers confidence when the onsets are there but do not lock to the grid.
     */
    private fun snapGrid(grid: BeatTracker.Grid, stereo: AudioDecoder.StereoPcm, start: Double): BeatTracker.Grid {
        val onsets = fluxOnsets(stereo, start)
        if (onsets.size < 8) return grid
        val beats = GridRepair.snapToOnsets(grid.beats, onsets)
        val downs = GridRepair.snapToOnsets(grid.downbeats, onsets)
        val coherence = GridRepair.phaseCoherence(beats, onsets)
        var confidence = grid.beatConfidence
        if (coherence in 0.0..0.42 && beats.size >= 16 && onsetCoverage(beats, onsets) >= 0.5) {
            confidence = (confidence * (coherence / 0.42)).coerceIn(0.0, 1.0)
        }
        val tempo = BeatTracker.tempoFromBeats(beats)
        val interval = if (tempo > 0) 60.0 / tempo else grid.beatInterval
        val bpm = if (tempo > 0) tempo else grid.bpm
        return grid.copy(
            beats = beats,
            downbeats = downs,
            bpm = bpm,
            beatInterval = interval,
            firstBeat = beats.firstOrNull() ?: grid.firstBeat,
            beatConfidence = confidence,
        )
    }

    /** Fraction of [beats] with an onset within 70 ms. Sparse flux does not get to reject a grid. */
    private fun onsetCoverage(beats: List<Double>, onsets: List<Double>): Double {
        if (beats.isEmpty() || onsets.isEmpty()) return 0.0
        var hits = 0
        for (beat in beats) {
            hits += if (nearestOnset(onsets, beat)?.let { abs(it - beat) <= 0.070 } == true) 1 else 0
        }
        return hits.toDouble() / beats.size
    }

    private fun nearestOnset(onsets: List<Double>, beat: Double): Double? {
        if (onsets.isEmpty()) return null
        var low = 0
        var high = onsets.size - 1
        while (low < high) {
            val mid = (low + high) ushr 1
            if (onsets[mid] < beat) low = mid + 1 else high = mid
        }
        val after = onsets[low]
        val before = onsets.getOrNull(low - 1) ?: return after
        return if (abs(before - beat) <= abs(after - beat)) before else after
    }

    /** Half-wave spectral flux, one onset per local peak. Times are track seconds. */
    private fun fluxOnsets(stereo: AudioDecoder.StereoPcm, start: Double): List<Double> {
        val rate = stereo.sampleRate
        if (rate <= 0.0 || stereo.left.isEmpty()) return emptyList()
        val hop = (rate / 200.0).toInt().coerceAtLeast(1)
        val frames = stereo.left.size / hop
        if (frames < 8) return emptyList()
        val flux = FloatArray(frames)
        var previous = 0.0
        for (index in 0 until frames) {
            var sum = 0.0
            var n = 0
            var sample = index * hop
            val end = min(stereo.left.size, sample + hop)
            while (sample < end) {
                val mixed = (stereo.left[sample] + stereo.right[sample]) * 0.5f
                sum += mixed * mixed
                n++
                sample += 4
            }
            val energy = if (n == 0) 0.0 else sqrt(sum / n)
            val delta = energy - previous
            flux[index] = if (delta > 0) delta.toFloat() else 0f
            previous = energy
        }
        var mean = 0.0
        for (value in flux) mean += value
        mean /= frames
        var variance = 0.0
        for (value in flux) {
            val d = value - mean
            variance += d * d
        }
        val threshold = mean + sqrt(variance / frames)
        val onsets = ArrayList<Double>()
        for (index in 1 until frames - 1) {
            if (flux[index] >= threshold && flux[index] >= flux[index - 1] && flux[index] > flux[index + 1]) {
                onsets += start + index * hop / rate
            }
        }
        return onsets
    }

    /**
     * RMS of the stereo mix in frames of 1 / [LEVEL_FRAME_RATE] s, on the
     * [levelFromRms] scale (stored in hundredths, so it has to fit 0..1). Every
     * fourth sample: plenty for a level, and a quarter of the reads.
     */
    private fun levelCurve(stereo: AudioDecoder.StereoPcm, actualStart: Double): CurveWindow? {
        val frame = (stereo.sampleRate / LEVEL_FRAME_RATE).toInt()
        if (frame <= 0) return null
        val count = stereo.left.size / frame
        if (count <= 0) return null
        val values = FloatArray(count)
        for (index in 0 until count) {
            var sum = 0.0
            var n = 0
            var sample = index * frame
            val end = sample + frame
            while (sample < end) {
                val mixed = (stereo.left[sample] + stereo.right[sample]) * 0.5f
                sum += mixed * mixed
                n++
                sample += 4
            }
            values[index] = levelFromRms(kotlin.math.sqrt(sum / n)).toFloat()
        }
        return CurveWindow(actualStart, LEVEL_FRAME_RATE, values)
    }

    /** A region's model input, and its DSP features when the caller asked for them. */
    private class RegionInputs(val forModel: FloatArray?, val derived: TrackFeatures.Features?)

    /**
     * Reduces a decoded region to the buffers the models and the DSP actually read.
     *
     * Both come off one full-rate mono downmix, which is why they are made together rather than on
     * demand: that downmix is the largest single allocation in the analysis, and returning is the
     * only way to be rid of it before the models run.
     */
    private fun regionInputs(
        stereo: AudioDecoder.StereoPcm,
        seconds: Double,
        deriveFeatures: Boolean,
    ): RegionInputs {
        val mono = FloatArray(stereo.left.size) { index -> (stereo.left[index] + stereo.right[index]) * 0.5f }
        val forModel = if (abs(stereo.sampleRate - MelSpectrogram.sampleRate) > 1.0) {
            MelSpectrogram.resample(mono, stereo.sampleRate, MelSpectrogram.sampleRate)
        } else {
            mono
        }

        // Derived here rather than by the caller so the mono buffer is still
        // live: handing it back would keep several megabytes reachable for the
        // rest of the analysis, which is the one thing this function exists to
        // avoid.
        val derived = if (deriveFeatures) {
            val forFeatures = if (abs(stereo.sampleRate - TrackFeatures.sampleRate) > 1.0) {
                TrackFeatures.resample(mono, stereo.sampleRate, TrackFeatures.sampleRate)
            } else {
                mono
            }
            forFeatures?.let { TrackFeatures.analyze(it, seconds) }
        } else {
            null
        }

        return RegionInputs(forModel, derived)
    }

    /**
     * A vocal-presence value for every point on the energy curve, filled only where the model
     * actually ran.
     *
     * The policy indexes the mask against energy-curve sample times and requires the two to be the
     * same length, but the model's window is fixed at about 22 seconds, far less than a track. So
     * the mask is built at full length and filled only over this region.
     *
     * Everywhere else stays at [NEUTRAL_VOCAL]. That is not a guess dressed up as data: it sits
     * below the policy's own VOCAL_ACTIVE_THRESHOLD, so unmeasured material can never trip vocal
     * logic in either direction.
     */
        fun separateTransitionWindow(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
        keepVocals: Boolean,
    ): Pair<FloatArray, FloatArray>? {
        if (yieldOrStopped()) return null
        return synchronized(modelLock) {
            if (yieldOrStopped()) null else vocals.separateWindow(left, right, sampleRate.toDouble(), keepVocals)
        }
    }

        fun separateTransitionBothWindows(
        left: FloatArray,
        right: FloatArray,
        sampleRate: Int,
    ): Pair<Pair<FloatArray, FloatArray>, Pair<FloatArray, FloatArray>>? {
        return synchronized(modelLock) {
            if (yieldOrStopped()) null else vocals.separateBothWindows(left, right, sampleRate.toDouble())
        }
    }
 
private fun vocalValues(stereo: AudioDecoder.StereoPcm): FloatArray? {
        if (!VocalSpectrogram.available) return null
        // [VocalTracker.track] refuses anything wider than its graph, so a window that came back a
        // little long is trimmed. Two frames of margin absorb the ±1 sample a rate conversion can
        // land on.
        val maxSeconds = (VocalTracker.FIXED_FRAMES - 2) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        val maxSamples = (maxSeconds * stereo.sampleRate).toInt().coerceAtMost(stereo.left.size)
        if (maxSamples <= 0) return null
        val left = if (maxSamples < stereo.left.size) stereo.left.copyOf(maxSamples) else stereo.left
        val right = if (maxSamples < stereo.right.size) stereo.right.copyOf(maxSamples) else stereo.right
        return vocals.track(left, right, stereo.sampleRate)
    }

    private fun vocalMask(
        values: FloatArray,
        features: TrackFeatures.Features,
        actualStart: Double,
    ): DoubleArray? {
        val curve = features.energyCurve
        if (curve.isEmpty()) return null

        val mask = DoubleArray(curve.size) { NEUTRAL_VOCAL }
        for (index in curve.indices) {
            val frame = ((curve[index].time - actualStart) * VocalSpectrogram.frameRate).toInt()
            if (frame in values.indices) mask[index] = values[frame].toDouble()
        }
        return mask
    }

    /**
     * Overlays the head and tail masks onto one full-length curve, or null when neither ran —
     * which the caller answers by keeping the DSP heuristic rather than reporting a mask of
     * nothing but [NEUTRAL_VOCAL].
     */
    private fun mergeMasks(size: Int, masks: List<DoubleArray>): List<Double>? {
        if (size <= 0 || masks.isEmpty()) return null
        val merged = DoubleArray(size) { NEUTRAL_VOCAL }
        for (source in masks) {
            for (index in merged.indices) {
                if (index < source.size && source[index] != NEUTRAL_VOCAL) merged[index] = source[index]
            }
        }
        return merged.toList()
    }

    /** Recorded ready-but-empty so a track that cannot be decoded is not retried every tick. */
    private fun empty(trackId: String, durationSeconds: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = trackId,
        duration = durationSeconds,
    )

    /**
     * Stereo audio of [fromSeconds]..[toSeconds] of [trackId] at [sampleRate],
     * for a loop mix deck, read off the copy analysis measured (so the deck's
     * loop is cut from the same audio its downbeats were found in). Null for a
     * source-backed track: its analysis reads a matched YouTube recording, not
     * what the player plays, and a deck spliced in from another master would
     * land off the song it takes over from. Blocking — call it off the main
     * thread.
     */
    fun loopRegion(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        fromSeconds: Double,
        toSeconds: Double,
        sampleRate: Int,
        /** Called on this thread at the start of each slice, so a late one can be named. */
        onStep: ((String) -> Unit)? = null,
    ): com.music.bitchord.playback.loopmix.LoopRegion? {
        // djmix25: source-backed URIs (Radio, podcasts, uploads, YouTube Music
        // radio) used to return null here, disabling loop mixes for a huge
        // share of lazy crossfades. They now flow through the same path:
        // copyToRead on the playback URI, else the resolved YouTube analysis
        // URI, else a streaming (partial-rendition) decode below.
        // djmix14: RAM cache first — a covering entry is sub-sliced, no network.
        loopCache.get(trackId, sampleRate, max(0.0, fromSeconds), toSeconds)?.let { slice ->
            Log.d(TAG, "Loop region $trackId cache hit ${slice.startSec}s..${slice.endSec}s at ${sampleRate}Hz")
            return com.music.bitchord.playback.loopmix.LoopRegion(slice.left, slice.right, slice.startSec)
        }
        val began = SystemClock.elapsedRealtime()
        onStep?.invoke("stream")
        // djmix25: streaming fallback (no complete-rendition gate). copyToRead
        // requires isComplete, which fails ~40 ms into a transfer; the loop
        // deck only needs the asked region, so any cached prefix (or the
        // resolved analysis URI's prefix) is decoded with the header-based
        // extractor instead of returning cold-loop-headroom-using-classic.
        val copy = runCatching { copyToRead(trackId, uri, durationSeconds) }.getOrNull()
            ?: runCatching { streamingCopyForLoop(trackId, uri, durationSeconds) }.getOrNull()
            ?: return null
        onStep?.invoke("decode")
        val (stereo, start) = copy.open()?.use { source ->
            AudioDecoder.decodeRegionStereo(source, max(0.0, fromSeconds), toSeconds) { yieldOrStopped() }
        } ?: return null
        if (stereo.left.isEmpty()) return null
        var rawLeft = stereo.left
        var rawRight = stereo.right
        var startOut = start
        // Bug 1c: MediaCodec (c2.android.opus.decoder) can answer a few ms
        // late even mid-track under SEEK_TO_PREVIOUS_SYNC. Prepend the missing
        // zeros before resampling and re-anchor to the requested source second
        // so coversPhysical()'s exact start check still passes. Fails closed
        // beyond 80 ms (never mask a genuinely short/wrong decode).
        val (paddedLeft, paddedRight, anchoredStart) = padLateStart(
            rawLeft, rawRight, start, fromSeconds, stereo.sampleRate.toDouble()
        )
        rawLeft = paddedLeft
        rawRight = paddedRight
        startOut = anchoredStart
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
     * A three-band waveform of [fromSeconds]..[toSeconds] of [trackId], for the
     * Automix popup, read off the same copy analysis measured; null when there
     * is no such copy to read. Blocking — call it off the main thread.
     */
    fun mixWaveform(
        trackId: String,
        uri: Uri,
        durationSeconds: Double,
        fromSeconds: Double,
        toSeconds: Double,
        bins: Int,
    ): MixWaveform.Bands? {
        // Not [analysisUriFor]: that starts a catalogue search, and this only
        // wants whatever analysis already settled on.
        val analysisUri = if (uri.getQueryParameter("v") != null || uri.authority != "source") {
            uri
        } else {
            sourceAnalysisUris[trackId] ?: return null
        }
        val began = SystemClock.elapsedRealtime()
        val copy = runCatching { copyToRead(trackId, analysisUri, durationSeconds) }.getOrNull() ?: return null
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
        headAttempts.clear()
        headSkipLogged.clear()
        provisional.clear()
        restoreAttempted.clear()
        shortDecodes.clear()
        badRenditions.clear()
        triedRenditions.clear()
        discarded.clear()
        failed.clear()
        stalled.clear()
        cancelled.clear()
        sourcePending.clear()
        sourceProvisionalKeys.clear()
        _analysisUpdates.tryEmit("") // best-effort terminal signal (buffered, non-blocking)
        tracker.release()
        vocals.release()
    }

    private companion object {
        const val TAG = "BitChordTrackAnalyzer"

        /** How long a handover keeps analysis off the cores, from the moment it is asked. */
        const val PLAYBACK_FIRST_MS = 2_000L

        /**
         * What an unmeasured instant reads as. Below the policy's VOCAL_ACTIVE_THRESHOLD by
         * design, so absence of measurement is never mistaken for absence of a vocal, or for the
         * presence of one.
         */
        const val NEUTRAL_VOCAL = 0.2

        /** Model windows over the opening (~45 s) and the ending (~68 s); see [modelWindowSeconds]. */
        const val HEAD_MODEL_WINDOWS = 2
        const val TAIL_MODEL_WINDOWS = 3

        /** Overlap between neighbouring model windows, so a beat on a seam is seen from both sides. */
        const val WINDOW_OVERLAP_SECONDS = 1.0

        /**
         * How much more than the average-bitrate estimate of the opening window
         * to insist on before decoding it. Covers the container header and the
         * fact that a track's opening is rarely at its own average bitrate.
         */
        const val HEAD_BYTES_MARGIN = 1.35

        /**
         * Floor under the computed threshold, and the whole requirement when the
         * duration is unknown. Roughly fifteen seconds at 128 kbps — a little
         * over [MIN_HEAD_SECONDS], so it guarantees a parsable container and a
         * usable decode without quietly reinstating the thirty-second demand the
         * bitrate estimate was just lowered away from.
         */
        const val MIN_HEAD_BYTES = 256L * 1024L

        /**
         * How much of the container's stated duration must actually decode
         * before the whole-track pass is trusted. Not 1.0: a decoder legitimately
         * comes up a frame or two short of the container's rounding, and
         * refusing over that would refuse everything.
         */
        const val MIN_DECODED_FRACTION = 0.95

        /** Refusals before a track is written off as truncated rather than still filling in. */
        const val MAX_SHORT_DECODE_ATTEMPTS = 3

        /**
         * Clean-slate retries per rendition. One: a discard is worth doing when
         * the bytes on disk are unrecoverable and nothing would otherwise
         * overwrite them, and worth doing exactly once, because a second identical
         * answer means the source is serving that file rather than the cache
         * having mangled it.
         */
        const val MAX_RENDITION_DISCARDS = 1

        /**
         * How far two renditions' container durations may differ and still count
         * as the same cut. Generous enough for codec padding and the player's own
         * rounding, tight enough that a different edit of the same song — where a
         * borrowed beat grid would be useless — is rejected.
         */
        const val RENDITION_DURATION_TOLERANCE = 1.0

        /**
         * Stand-in bitrate for a rendition whose real length isn't recorded yet,
         * used only to rank copies against each other in [headSecondsOf]. About
         * 160kbps, the middle of what the streams in play here run at.
         */
        const val ASSUMED_BYTES_PER_SECOND = 20_000.0

        /**
         * The least decoded audio a head-only tempo estimate is allowed to rest
         * on. Twelve seconds is around 24 beats at 120 bpm — enough for the
         * grid's own confidence measure to mean something.
         */
        const val MIN_HEAD_SECONDS = 12.0

        /**
         * How much more of the file has to be cached before the head is worth
         * decoding again. Doubling bounds the attempts to a handful over a whole
         * download while still catching up quickly on a high-bitrate rendition
         * whose first attempt covered only a few seconds.
         */
        const val HEAD_RETRY_GROWTH = 2

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
