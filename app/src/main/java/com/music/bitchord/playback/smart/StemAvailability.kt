package com.music.bitchord.playback.smart

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.music.bitchord.data.settings.AppSettings
import com.music.bitchord.playback.djmix.DjmixServices
import com.music.bitchord.playback.djmix.diagnostics.FlagsSnapshot
import com.music.bitchord.playback.djmix.npu.AddonState
import com.music.bitchord.playback.djmix.npu.CpuOrtBackend
import com.music.bitchord.playback.djmix.npu.InferenceBackend
import com.music.bitchord.playback.djmix.npu.ModelEntry
import com.music.bitchord.playback.djmix.npu.ModelRegistry
import com.music.bitchord.playback.djmix.npu.ModelSpec
import com.music.bitchord.playback.djmix.npu.NpuAddon
import com.music.bitchord.playback.djmix.npu.NpuSelfTestRunner
import com.music.bitchord.playback.djmix.npu.OrchardNpuBridge
import com.music.bitchord.playback.djmix.npu.QnnAddonBackend
import com.music.bitchord.playback.djmix.npu.SelfTestRecord
import java.io.File
import java.util.concurrent.ExecutorService

/**
 * NPU backend resolution, add-on self-test gating, and device-RAM/opt-in stem capability checks
 * extracted from [TrackAnalyzer] (M9 slice T-1).
 *
 * All [Context] reads preserve the vc70 pre-attach rule by resolving lazily through
 * `context.applicationContext` only when invoked off construction.
 */
internal class NpuAndStemBridge(
    private val context: Context,
    private val ioExecutor: ExecutorService,
    private val isStopped: () -> Boolean,
    private val isOverheated: () -> Boolean,
) {
    // Compatibility plumbing for a future separately delivered NPU runtime.
    // djmix52 is CPU-only, regardless of the restored NPU user setting.
    val npuVendor: NpuSupport.Vendor =
        NpuSupport.detectVendor(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else "",
            Build.HARDWARE,
            Build.MODEL,
            Build.MANUFACTURER,
        )

    val npuCacheDir: File
        get() = File(context.noBackupFilesDir, "npu/cache").apply { mkdirs() }

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
        if (!NpuSupport.QNN_ENABLED || !NpuRuntime.runtimeEnabled()) return null
        val dir = npuLibDir ?: return null
        synchronized(npuPreflightLock) {
            if (!npuPreflightDone) {
                NpuNative.initBundledQnn(dir)
                npuPreflightDone = true
            }
        }
        return dir
    }

    // No backend ships in djmix52. These lazy adapters never preflight it while dark.
    val npuBackendPath: String?
        get() = if (!NpuSupport.QNN_ENABLED || npuVendor != NpuSupport.Vendor.QUALCOMM) null
        else ensureNpuPreflight()?.let {
            NpuSupport.bundledBackendPath(it)
        }?.takeIf { File(it).exists() }

    // ---- AE3 M6: add-on NPU wiring (models.json / OrchardNpuBridge) ---------------
    //
    // The M6 add-on flow beside the legacy vc93 bundled-runtime plumbing above:
    // a SHA-256-pinned catalogue (assets/models.json, optionally overridden by
    // a verified filesDir drop), trusted-package add-on resolution, a
    // per-(add-on version, device fingerprint, app version) parity self-test
    // persisted by NpuSelfTestRunner, and a selection that only ever offers
    // QnnAddonBackend when the stored verdict is a current PASS. Everything
    // else falls back to plain CPU ORT, transparently: no crash, no block.
    //
    // vc70 rule (see npuLibDir above): this analyzer is constructed before the
    // service's base context is attached, so NOTHING here runs at construction
    // time -- every context access defers to the I/O pool, resolves through
    // applicationContext lazily, and maps any pre-attach failure to null.

    /** vc70-safe application context: recomputed on every access, never cached. */
    private fun npuAppContext(): Context? = runCatching { context.applicationContext }.getOrNull()

    /** Reads the live process owner without creating it, caching a separate source, or doing I/O. */
    private fun djmixFlagsSnapshot(): FlagsSnapshot =
        DjmixServices.processFlagsSnapshot()

    /** models.json catalogue (bundled assets + verified filesDir override). */
    @Volatile private var npuCatalogue: List<ModelEntry>? = null

    /** Trusted add-on resolved this session, or null while none is installed. */
    @Volatile private var npuAddonSnapshot: NpuAddon? = null

    /** Persisted self-test verdict consulted this session (may be stale for the current tuple). */
    @Volatile private var npuSelfTestRecord: SelfTestRecord? = null

    /** App version the bridge pass cached for the current selection tuple. */
    @Volatile private var npuAppVersionCode: Long = -1L

    /** Reason of the most recent [selectInferenceBackend] decision. */
    @Volatile private var npuStatusReason: String = "ep=CPU (self-test not run)"

    @Volatile private var npuBridgeScheduled = false
    @Volatile private var npuNextSetupAttemptElapsed = 0L
    private val npuBridgeLock = Any()

    /** Posts one bridge pass to the I/O pool; guarded, backoff-aware, and cheap on the tick path. */
    fun scheduleNpuBridgeSetupOnce() {
        if (npuBridgeScheduled || SystemClock.elapsedRealtime() < npuNextSetupAttemptElapsed) return
        synchronized(npuBridgeLock) {
            if (npuBridgeScheduled || SystemClock.elapsedRealtime() < npuNextSetupAttemptElapsed) return
            npuBridgeScheduled = true
        }
        try {
            ioExecutor.execute { runNpuBridgeSetup() }
        } catch (error: Throwable) {
            npuBridgeScheduled = false
            Log.w(TAG, "BitChordNPU bridge dispatch failed", error)
        }
    }

    /** Marks this pass as retryable no sooner than [retryMs] from now. */
    private fun deferNpuBridgeSetup(retryMs: Long) {
        npuNextSetupAttemptElapsed = SystemClock.elapsedRealtime() + retryMs
        npuBridgeScheduled = false
    }

    /**
     * One M6 bridge pass on the I/O pool: loads the catalogue, resolves the
     * trusted add-on, and when one is installed consults (or runs) the
     * parity self-test. A current PASS or FAIL verdict ends scheduling for
     * this process -- the record is cached per (add-on version, device
     * fingerprint, app version), and a changed tuple invalidates it. Never
     * throws; every failure path means plain CPU ORT.
     */
    private fun runNpuBridgeSetup() {
        val app = npuAppContext()
        if (app == null) {
            // vc70 pre-attach window: a later request() tick retries.
            deferNpuBridgeSetup(NPU_SETUP_RETRY_MS)
            return
        }
        try {
            if (!NpuRuntime.RUNTIME_AVAILABLE || !NpuRuntime.runtimeEnabled()) {
                deferNpuBridgeSetup(NPU_SETUP_RETRY_SLOW_MS)
                return
            }
            if (npuCatalogue == null) npuCatalogue = ModelRegistry.load(app)
            if (npuAppVersionCode <= 0L) npuAppVersionCode = npuAppVersionCodeOf(app)
            val addon = OrchardNpuBridge.resolveAddon(app)
            npuAddonSnapshot = addon
            if (addon == null) {
                Log.i(TAG, "BitChordNPU add-on not installed: CPU execution provider")
                deferNpuBridgeSetup(NPU_SETUP_RETRY_MS)
                return
            }
            if (!AppSettings.automixNpuEnabled.value || djmixFlagsSnapshot().isNpuKilled()) {
                Log.i(TAG, "BitChordNPU ep=CPU (disabled by user setting or flags kill switch)")
                deferNpuBridgeSetup(NPU_SETUP_RETRY_SLOW_MS)
                return
            }
            val record = NpuSelfTestRunner.load(app)
            npuSelfTestRecord = record
            val addonState = AddonState(installed = true, versionCode = addon.versionCode)
            if (npuAppVersionCode > 0L &&
                ModelRegistry.qnnAllowed(addonState, record, Build.FINGERPRINT, npuAppVersionCode)
            ) {
                Log.i(TAG, "BitChordNPU ep=QNN: self-test PASS for add-on ${addon.versionCode}")
                return
            }
            if (isStopped() || isOverheated()) {
                // A party or a hot phone: never start inference work there.
                deferNpuBridgeSetup(NPU_SETUP_RETRY_SLOW_MS)
                return
            }
            Log.i(TAG, "BitChordNPU scheduling add-on self-test (v${addon.versionCode})")
            runNpuSelfTest(app, addon)
        } catch (error: Throwable) {
            // Zero crashes / zero blocks: any failure means CPU ORT and a later retry.
            Log.w(TAG, "BitChordNPU bridge setup failed: CPU execution provider", error)
            deferNpuBridgeSetup(NPU_SETUP_RETRY_SLOW_MS)
        }
    }

    /**
     * Runs the Nirovi QNN-vs-CPU parity and timing self-test at most once per
     * process and persists the verdict with [NpuSelfTestRunner.save], so later
     * consults read the cached record instead of re-running inference. Never
     * throws; an infrastructure failure simply leaves no record (the next
     * process start retries).
     */
    private fun runNpuSelfTest(app: Context, addon: NpuAddon) {
        runCatching {
            val entries = npuCatalogue ?: ModelRegistry.load(app).also { npuCatalogue = it }
            val cpuSpec = verifiedNpuModelSpec(entries, "beat", "cpu-ort")
            val qnnSpec = verifiedNpuModelSpec(entries, "beat", "qnn-v73")
            if (cpuSpec == null || qnnSpec == null ||
                cpuSpec.inputName != qnnSpec.inputName || cpuSpec.inputShape.contentEquals(qnnSpec.inputShape).not() ||
                cpuSpec.outputName != qnnSpec.outputName
            ) {
                Log.w(TAG, "BitChordNPU self-test skipped: no SHA-256 verified CPU beat model")
                return@runCatching
            }
            CpuOrtBackend().open(cpuSpec).use { cpu ->
                QnnAddonBackend(app) { npuAddonSnapshot ?: addon }.open(qnnSpec).use { qnn ->
                    val record = NpuSelfTestRunner.run(app, cpu, qnn, cpuSpec, addon.versionCode)
                    npuSelfTestRecord = record
                    NpuSelfTestRunner.save(app, record)
                    Log.i(
                        TAG,
                        "BitChordNPU self-test verdict=${if (record.passed) "PASS" else "FAIL"} (${record.reason})",
                    )
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "BitChordNPU self-test failed: CPU execution provider", error)
        }
    }

    /**
     * Builds the CPU [ModelSpec] for [role] out of the models.json catalogue,
     * cross-checking the entry against the compiled-in [AutomixModels]
     * identity and handing back only the SHA-256-verified on-disk copy, so a
     * tampered catalogue can never redirect inference to an unverified file.
     */
    private fun verifiedNpuModelSpec(entries: List<ModelEntry>, role: String, backend: String): ModelSpec? {
        val entry = entries.firstOrNull {
            it.role == role && backend in it.backends &&
                !it.inputName.isNullOrBlank() && !it.outputName.isNullOrBlank() && it.inputShape != null
        } ?: return null
        if (backend == "qnn-v73") {
            val expected = when (role) {
                "beat" -> OrchardNpuBridge.QNN_NIROVI_ASSET
                "vocal" -> OrchardNpuBridge.QNN_SYVENU_ASSET
                else -> return null
            }
            if (entry.id != (if (role == "beat") "nirovi-qnn" else "syvenu-qnn") || entry.fileName != expected) return null
            return ModelSpec(
                id = entry.id,
                fileName = entry.fileName,
                file = File(entry.fileName),
                inputName = entry.inputName!!,
                inputShape = entry.inputShape!!,
                outputName = entry.outputName!!,
            )
        }
        val model = when (role) {
            "beat" -> AutomixModels.NIROVI
            "vocal" -> AutomixModels.SYVENU
            else -> return null
        }
        if (entry.id != (if (role == "beat") "nirovi-cpu" else "syvenu-cpu") || entry.fileName != model.fileName) return null
        if (!entry.sha256.equals(model.sha256, ignoreCase = true)) return null
        val verifiedPath = AutomixModels.verifiedPathOrNull(model) ?: return null
        return ModelSpec(
            id = entry.id,
            fileName = entry.fileName,
            file = File(verifiedPath),
            inputName = entry.inputName!!,
            inputShape = entry.inputShape!!,
            outputName = entry.outputName!!,
        )
    }

    /** This app's version code via the package manager; -1 on any failure. */
    private fun npuAppVersionCodeOf(app: Context): Long = runCatching {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
    }.getOrDefault(-1L)

    /** The trusted add-on resolved this session, or null (M7 glue / diagnostics). */
    fun npuAddon(): NpuAddon? = npuAddonSnapshot

    /** The persisted self-test verdict consulted this session (M7 glue / diagnostics). */
    fun npuSelfTestVerdict(): SelfTestRecord? = npuSelfTestRecord

    /** M6 status line for Settings / the Mix Inspector; mirrors [NpuSelfTestRunner.statusLabel]. */
    fun npuStatusLabel(): String {
        val addon = npuAddonSnapshot ?: return "NPU: Not installed"
        val app = npuAppContext() ?: return "NPU: Not tested"
        return runCatching { NpuSelfTestRunner.statusLabel(app, addon.versionCode) }
            .getOrDefault("NPU: Not tested")
    }

    /** Human-readable reason for the most recent backend decision (diagnostics). */
    fun npuSelectionDetail(): String = npuStatusReason

    /**
     * The M6 backend for [role] ("beat" / "vocal"): [QnnAddonBackend] only
     * when a trusted add-on is installed, the user setting and
     * `DjmixFlags.npu_qnn` kill switch allow it, and the persisted
     * self-test verdict is a current PASS; the [CpuOrtBackend] reference
     * implementation otherwise (transparent fallback). Pure decision on
     * cached state: no I/O, no session is opened here.
     */
    fun selectInferenceBackend(role: String): InferenceBackend {
        if (!NpuRuntime.RUNTIME_AVAILABLE || !NpuRuntime.runtimeEnabled()) {
            npuStatusReason = "ep=CPU (NPU runtime gate is closed)"
            return CpuOrtBackend()
        }
        val app = npuAppContext()
        val entries = npuCatalogue
        if (app == null || entries == null) {
            npuStatusReason = "ep=CPU (bridge not resolved yet)"
            return CpuOrtBackend()
        }
        val addon = npuAddonSnapshot
        val gated = !AppSettings.automixNpuEnabled.value || djmixFlagsSnapshot().isNpuKilled()
        val addonState = AddonState(installed = addon != null, versionCode = addon?.versionCode ?: 0L)
        val selection = ModelRegistry.select(
            entries, role, addonState, npuSelfTestRecord, Build.FINGERPRINT, npuAppVersionCode,
        )
        if (selection == null) {
            npuStatusReason = "ep=CPU (no model for role $role)"
            return CpuOrtBackend()
        }
        if (selection.backendId == "qnn-v73" && gated) {
            npuStatusReason = "ep=CPU (disabled by user setting or flags kill switch)"
            return CpuOrtBackend()
        }
        npuStatusReason = selection.reason
        return if (selection.backendId == "qnn-v73" && addon != null) {
            QnnAddonBackend(app) { npuAddonSnapshot }
        } else {
            CpuOrtBackend()
        }
    }

    /**
     * AUTOMIX-V2(orch): v2 stems have their own device gate. The vc80 windowed-stem gate
     * also requires the legacy opt-in (off by default), which would leave 4-stem mixing
     * dark on every phone; v2 keeps only the RAM check and the v2 setting.
     */
    fun canUseV2Stems(): Boolean {
        if (!AppSettings.automixStemsEnabled.value) return false
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            !activityManager.isLowRamDevice &&
                (info.totalMem >= 6L * 1024L * 1024L * 1024L || activityManager.memoryClass >= 256)
        } catch (_: Throwable) {
            false
        }
    }

    /** Whether v2 stems are enabled and allowed on this device and analyzer isn't stopped/thermal-throttled. */
    fun canUseStems(): Boolean = AutomixV2Stems.enabled && canUseV2Stems() && !isStopped() && !isOverheated()

    /**
     * djmix25 windowed-stem gate (P1.5): only non-low-RAM devices with
     * >= 6 GB total RAM (or a >=256 MB memory class) AND the user opt-in
     * [com.music.bitchord.data.settings.AppSettings.automixWindowedStemsEnabled]
     * may run the instrumental-share windows. Everything else stays on the
     * single umxhq vocal stem; the planner never sees instrumental evidence
     * there, so nothing degrades.
     */
    fun canUseWindowedStems(): Boolean {
        if (!AppSettings.automixWindowedStemsEnabled.value) return false
        return try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return false
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            !activityManager.isLowRamDevice &&
                (info.totalMem >= 6L * 1024L * 1024L * 1024L ||
                    activityManager.memoryClass >= 256)
        } catch (_: Throwable) {
            false
        }
    }

    private companion object {
        const val TAG = "BitChordTrackAnalyzer"

        /** M6 bridge-pass retry cadence: pre-attach build, no add-on yet, or a gated/thermal deferral. */
        const val NPU_SETUP_RETRY_MS = 60_000L

        /** M6 bridge-pass retry cadence after an unexpected setup failure (visible, never spammy). */
        const val NPU_SETUP_RETRY_SLOW_MS = 5 * 60_000L
    }
}
