package com.music.bitchord.playback.smart

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * AUTOMIX-V2(A) NPU runtime: Snapdragon QNN (Hexagon HTP) through the
 * onnxruntime-android-qnn AAR, with CPU fallback. Lives beside the legacy [NpuSupport]
 * (whose compile-time `QNN_ENABLED` stays `false`, so its dynamic-int8 path stays dormant)
 * and adds a separate, QDQ-only route. Pure and testable: vendor and SoC
 * detection, option maps, model routing (which file may run on the NPU), the
 * context-cache key, and the self-test maths. The ONNX Runtime plumbing lives in
 * [NpuSession] (jvmSharedMain) and the Android wiring in NpuAndroid / NpuNative /
 * NpuSelfTest (app module).
 *
 * AUTOMIX-V2(A): the compile-time `const val QNN_ENABLED = false` gate is replaced by
 * [runtimeEnabled], backed by an injectable [NpuPolicy] (user switch, persisted
 * auto-disable after repeated QNN failures or a self-test mismatch, reset when the app
 * versionCode changes). With no policy installed (desktop, TV, unit tests that do not
 * install one) the NPU is OFF, which is exactly the vc80 CPU behaviour.
 *
 * Routing rules (see [qnnCandidateName]):
 * - dynamic-int8 models (DynamicQuantizeLinear / MatMulInteger / DynamicQuantizeLSTM)
 *   cannot run on HTP and always stay on CPU;
 * - only a statically quantized QDQ sibling (`<base>_qdq.onnx`, w8a16) or its
 *   precompiled EP context (`<base>_qdq_ctx.onnx`) is ever opened on QNN;
 * - recurrent model families (SCNet, Open-Unmix: LSTM is not an HTP op) never try QNN.
 */
/** Contract name (CONTRACTS.md "A -> everyone"): `NpuSupport.runtimeEnabled()`. */
fun NpuSupport.runtimeEnabled(): Boolean = NpuRuntime.runtimeEnabled()

object NpuRuntime {

    // ---- runtime switch -------------------------------------------------------------

    @Volatile private var installedPolicy: NpuPolicy? = null

    /** Installs (or clears, with null) the runtime policy. Called once by the app. */
    fun install(policy: NpuPolicy?) {
        installedPolicy = policy
    }

    /** The installed policy, or null when the platform never installed one. */
    fun policy(): NpuPolicy? = installedPolicy

    /**
     * Runtime replacement for the old compile-time QNN gate: true when the v2 kill
     * switch is on, a policy is installed, the user switch is on and the policy has not
     * auto-disabled the NPU. Gates every QNN entry point (dlopen preflight, self-test,
     * session open). Cheap: the policy caches its persisted state in memory.
     */
    fun runtimeEnabled(): Boolean {
        if (!AutomixV2Npu.enabled) return false
        val policy = installedPolicy ?: return false
        return runCatching { policy.enabled() }.getOrDefault(false)
    }

    /**
     * True when production analysis sessions may use QNN: [runtimeEnabled] and, unless
     * [AutomixV2Npu.requireSelfTestPass] is off, the self-test passed (numerically equal
     * to CPU and faster) for this app version on this device.
     */
    fun productionQnnAllowed(): Boolean {
        if (!runtimeEnabled()) return false
        if (!AutomixV2Npu.requireSelfTestPass) return true
        val policy = installedPolicy ?: return false
        return runCatching { policy.selfTestPassed() }.getOrDefault(false)
    }

    /** v2 route gate for one session open (legacy config flag + vendor + [productionQnnAllowed]). */
    fun shouldAttemptQnn(config: NpuSupport.NpuConfig, vendor: NpuSupport.Vendor): Boolean =
        config.enabled && vendor == NpuSupport.Vendor.QUALCOMM && productionQnnAllowed()

    @Volatile private var backendProvider: (() -> String?)? = null

    /**
     * Installs the resolver of the bundled libQnnHtp.so path; it also runs the one-time
     * ADSP_LIBRARY_PATH / dlopen preflight (NpuAndroid). Returns null when unavailable.
     */
    fun installBackendProvider(provider: (() -> String?)?) {
        backendProvider = provider
    }

    fun backendPath(): String? = runCatching { backendProvider?.invoke() }.getOrNull()

    // ---- device -----------------------------------------------------------------------

    @Volatile private var installedSocModel: String? = null

    /** The SoC model string (Build.SOC_MODEL on API 31+), installed by the app; null = unknown. */
    fun installSocModel(socModel: String?) {
        installedSocModel = socModel?.trim()?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
    }

    fun socModel(): String? = installedSocModel

    /** Explicit HTP target for the QNN provider; null = omit both options and let QNN detect. */
    data class HtpTarget(val arch: Int, val socModel: Int?)

    /**
     * Hexagon target for SoCs we have verified. SM8635 (Snapdragon 8s Gen 3) is Hexagon v73,
     * soc_model 68. SM8550 / QCS8550 (8 Gen 2) is the same v73 generation; its soc_model is
     * left to QNN's own detection (only the arch is pinned). Everything else: null.
     */
    fun htpTargetFor(socModel: String?): HtpTarget? {
        val soc = (socModel ?: "").trim().uppercase()
        if (soc.isEmpty()) return null
        if (soc.startsWith("SM8635")) return HtpTarget(arch = 73, socModel = 68)
        if (soc.startsWith("SM8550") || soc.startsWith("QCS8550")) return HtpTarget(arch = 73, socModel = null)
        return null
    }

    // ---- options ----------------------------------------------------------------------

    /** QNN compile/run options for the v2 route (the legacy NpuSupport.NpuConfig is untouched). */
    data class QnnConfig(
        /** Hexagon performance mode used when [autoPerformanceMode] is false. */
        val htpPerformanceMode: String = PERF_BURST,
        /** Burst for short analysis bursts, sustained for long stem batches. */
        val autoPerformanceMode: Boolean = true,
        /** HTP graph finalization optimization mode (3 = slowest compile, best graph). */
        val graphFinalizationOptMode: String = "3",
        /** Cache the compiled EP context so repeat opens skip the compile. */
        val contextCaching: Boolean = true,
        /** enable_htp_fp16_precision=1 (float islands of the graph in fp16). */
        val fp16Precision: Boolean = true,
        /** offload_graph_io_quantization=1 (graph-I/O Q/DQ on the CPU EP). */
        val offloadGraphIoQuantization: Boolean = true,
        /**
         * session.disable_cpu_ep_fallback=1: session creation fails unless every node runs
         * on QNN. The self-test probe uses it to rule out silent CPU fallback.
         */
        val strictPartition: Boolean = false,
    )

    const val PERF_BURST = "burst"
    const val PERF_SUSTAINED = "sustained_high_performance"

    /** Burst for the short beat-analysis bursts; sustained for the long stem batches. */
    fun performanceModeFor(modelFileName: String, config: QnnConfig = QnnConfig()): String {
        if (!config.autoPerformanceMode) return config.htpPerformanceMode
        val name = modelFileName.lowercase()
        val long = AutomixV2Npu.sustainedModelPrefixes.any { name.startsWith(it) } || name.contains("stem")
        return if (long) PERF_SUSTAINED else PERF_BURST
    }

    /**
     * QNN execution provider options map: the bundled HTP backend, the performance mode,
     * the deepest graph finalization, fp16 for float islands, graph-I/O quantization
     * placement and, only for verified SoCs, the explicit HTP arch / soc model.
     */
    fun qnnProviderOptions(
        backendPath: String?,
        performanceMode: String,
        target: HtpTarget?,
        config: QnnConfig,
    ): Map<String, String> {
        val options = LinkedHashMap<String, String>()
        if (backendPath != null && backendPath.isNotBlank()) options["backend_path"] = backendPath
        options["htp_performance_mode"] = performanceMode
        options["htp_graph_finalization_optimization_mode"] = config.graphFinalizationOptMode
        options["enable_htp_fp16_precision"] = if (config.fp16Precision) "1" else "0"
        options["offload_graph_io_quantization"] = if (config.offloadGraphIoQuantization) "1" else "0"
        if (target != null) {
            options["htp_arch"] = target.arch.toString()
            target.socModel?.let { options["soc_model"] = it.toString() }
        }
        return options
    }

    /**
     * Session config entries for the QNN session: spinning off, CPU-EP fallback per config,
     * and, only when [contextPath] is given (i.e. we are generating a context cache, not
     * loading one), EP-context dumping with the binary embedded.
     */
    fun qnnSessionConfig(config: QnnConfig, contextPath: String?): Map<String, String> {
        val entries = LinkedHashMap<String, String>()
        entries["session.disable_cpu_ep_fallback"] =
            if (config.strictPartition) "1" else "0"
        entries["session.intra_op.allow_spinning"] = "0"
        if (config.contextCaching && contextPath != null && contextPath.isNotBlank()) {
            entries["ep.context_enable"] = "1"
            entries["ep.context_embed_mode"] = "1"
            entries["ep.context_file_path"] = contextPath
        }
        return entries
    }

    // ---- model routing ----------------------------------------------------------------

    private val DYNAMIC_SUFFIXES = listOf("_q8", "_int8", ".int8", "-int8", "_dyn", "_fp32", "_f32")

    fun isQdqModelName(fileName: String): Boolean = fileName.lowercase().endsWith("_qdq.onnx")

    fun isContextModelName(fileName: String): Boolean = fileName.lowercase().endsWith("_ctx.onnx")

    /** True for model families that must never try QNN (recurrent layers). */
    fun isCpuOnlyModel(fileName: String): Boolean {
        val name = fileName.lowercase()
        return AutomixV2Npu.cpuOnlyModelPrefixes.any { name.startsWith(it) }
    }

    /** `beat_this_final0_q8.onnx` -> `beat_this_final0_qdq.onnx`; a QDQ name maps to itself. */
    fun qdqSiblingName(fileName: String): String? {
        if (!fileName.lowercase().endsWith(".onnx")) return null
        if (isContextModelName(fileName)) return null
        if (isQdqModelName(fileName)) return fileName
        var base = fileName.substring(0, fileName.length - ".onnx".length)
        for (suffix in DYNAMIC_SUFFIXES) {
            if (base.lowercase().endsWith(suffix)) {
                base = base.substring(0, base.length - suffix.length)
                break
            }
        }
        if (base.isEmpty()) return null
        return base + "_qdq.onnx"
    }

    /** `x_qdq.onnx` -> `x_qdq_ctx.onnx` (an EP context precompiled offline for v73). */
    fun precompiledContextName(qdqFileName: String): String? {
        if (!isQdqModelName(qdqFileName)) return null
        return qdqFileName.substring(0, qdqFileName.length - ".onnx".length) + "_ctx.onnx"
    }

    /** The file name QNN may open for [fileName], or null when this model stays on CPU. */
    fun qnnCandidateName(fileName: String): String? {
        if (isCpuOnlyModel(fileName)) return null
        return qdqSiblingName(fileName)
    }

    /**
     * Context-cache file name: model + its SHA-256 + ORT version + SoC + option fingerprint,
     * so a model update, an ORT/QAIRT bump, a different phone or other compile options
     * never load a stale context.
     */
    fun contextCacheFileName(
        modelFileName: String,
        modelSha256: String,
        ortVersion: String,
        socModel: String?,
        optionsKey: String,
    ): String {
        val base = modelFileName.removeSuffix(".onnx")
        val sha = modelSha256.lowercase().take(16)
        return sanitize(base) + "." + sanitize(sha) + ".ort" + sanitize(ortVersion) + "." +
            sanitize(socModel ?: "soc-unknown") + "." + sanitize(optionsKey) + "_ctx.onnx"
    }

    /** Prefix shared by every cache file of [modelFileName] (for pruning stale ones). */
    fun contextCachePrefix(modelFileName: String): String = sanitize(modelFileName.removeSuffix(".onnx")) + "."

    /**
     * Stable fingerprint of the compile-relevant options (FNV-1a 32 bit). The performance
     * mode and backend path are runtime-only and excluded.
     */
    fun optionsFingerprint(options: Map<String, String>): String {
        var hash = 0x811C9DC5.toInt()
        for (key in options.keys.sorted()) {
            if (key == "htp_performance_mode" || key == "backend_path") continue
            val text = key + "=" + options[key] + ";"
            for (ch in text) {
                hash = hash xor ch.code
                hash *= 0x01000193
            }
        }
        return (hash.toLong() and 0xFFFFFFFFL).toString(16).padStart(8, '0')
    }

    private fun sanitize(value: String): String {
        val out = StringBuilder(value.length)
        for (ch in value) {
            out.append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_') ch else '-')
        }
        return out.toString().take(64)
    }

    // ---- self-test maths --------------------------------------------------------------

    /**
     * Deterministic beat-like log-mel input, row-major [frames][mels], values in the
     * log1p(1000*mel) range the model was trained on: a 120 BPM pulse (strong every fourth
     * beat), low-band weighted, over a slow spectral tilt plus fixed LCG noise.
     */
    fun selfTestInput(frames: Int, mels: Int): FloatArray {
        val out = FloatArray(frames * mels)
        var seed = 0x2545F491L
        val beatFrames = 25 // 0.5 s at 50 fps
        for (f in 0 until frames) {
            val phase = f % beatFrames
            val beatIndex = f / beatFrames
            val accent = if (beatIndex % 4 == 0) 1.6f else 1.0f
            val pulse = (exp(-phase / 3.0) * accent).toFloat()
            for (m in 0 until mels) {
                seed = (seed * 6364136223846793005L + 1442695040888963407L)
                val noise = ((seed ushr 40).toInt() and 0xFFFF) / 65535f - 0.5f
                val tilt = 1.2f + 0.6f * sin(f * 0.013 + m * 0.05).toFloat()
                val lowWeight = 1.0f - m.toFloat() / mels * 0.6f
                out[f * mels + m] = max(0f, tilt + 3.2f * pulse * lowWeight + 0.25f * noise)
            }
        }
        return out
    }

    data class LogitDiff(val maxAbs: Double, val meanAbs: Double, val count: Int)

    fun compareLogits(a: FloatArray, b: FloatArray): LogitDiff {
        val n = min(a.size, b.size)
        if (n == 0 || a.size != b.size) return LogitDiff(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, n)
        var maxAbs = 0.0
        var sum = 0.0
        for (i in 0 until n) {
            val av = a[i].toDouble()
            val bv = b[i].toDouble()
            if (av.isNaN() || bv.isNaN()) return LogitDiff(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, n)
            val d = abs(av - bv)
            if (d > maxAbs) maxAbs = d
            sum += d
        }
        return LogitDiff(maxAbs, sum / n, n)
    }

    /** F-measure of two peak lists (frames) with a +-[tolerance] window, greedy one-to-one. */
    fun peakFMeasure(reference: List<Double>, estimate: List<Double>, tolerance: Double): Double {
        if (reference.isEmpty() && estimate.isEmpty()) return 1.0
        if (reference.isEmpty() || estimate.isEmpty()) return 0.0
        val used = BooleanArray(estimate.size)
        var hits = 0
        for (r in reference) {
            var best = -1
            var bestDistance = Double.MAX_VALUE
            for ((index, e) in estimate.withIndex()) {
                if (used[index]) continue
                val d = abs(e - r)
                if (d <= tolerance && d < bestDistance) {
                    best = index
                    bestDistance = d
                }
            }
            if (best >= 0) {
                used[best] = true
                hits += 1
            }
        }
        val precision = hits.toDouble() / estimate.size
        val recall = hits.toDouble() / reference.size
        return if (precision + recall == 0.0) 0.0 else 2 * precision * recall / (precision + recall)
    }

    /**
     * Self-test verdict. MISMATCH when the QNN logits drift past the tolerances (or the
     * beat peaks disagree when there are enough of them); SLOW when numerically fine but
     * not faster than CPU (production then stays on CPU, nothing is disabled); else PASS.
     */
    fun judgeSelfTest(diff: LogitDiff, peakF: Double?, qnnMs: Double, cpuMs: Double): NpuVerdict {
        if (diff.maxAbs.isNaN() || diff.maxAbs > AutomixV2Npu.selfTestMaxAbs) return NpuVerdict.MISMATCH
        if (diff.meanAbs > AutomixV2Npu.selfTestMeanAbs) return NpuVerdict.MISMATCH
        if (peakF != null && peakF < AutomixV2Npu.selfTestMinPeakF) return NpuVerdict.MISMATCH
        if (cpuMs > 0 && qnnMs * AutomixV2Npu.minSpeedup > cpuMs) return NpuVerdict.SLOW
        return NpuVerdict.PASS
    }
}

/** Outcome of one NPU self-test. */
enum class NpuVerdict { PASS, SLOW, MISMATCH, QNN_FAILED, SKIPPED }

/**
 * AUTOMIX-V2(A): Stream A feature flags. Defaults fail safe: anything that goes wrong
 * lands on the CPU execution provider (vc80 behaviour), never on silence.
 */
object AutomixV2Npu {
    /** Master kill switch for every QNN path. false = CPU everywhere, no QNN lib is dlopened. */
    @Volatile var enabled: Boolean = true

    /** Production sessions use QNN only after the self-test PASSed for this versionCode. */
    @Volatile var requireSelfTestPass: Boolean = true

    /** Consecutive QNN failures (open, run, ENGINE_ERROR) that auto-disable the NPU until the next versionCode. */
    @Volatile var maxConsecutiveFailures: Int = 3

    /** Re-run the self-test at every launch instead of once per versionCode. */
    @Volatile var selfTestEveryLaunch: Boolean = false

    /** QNN must be at least this many times faster than CPU in the self-test (1.0 = not slower). */
    @Volatile var minSpeedup: Double = 1.0

    /** Self-test tolerances on Beat This! logits (QNN w8a16+fp16 vs CPU on the same QDQ graph). */
    @Volatile var selfTestMaxAbs: Double = 1.5
    @Volatile var selfTestMeanAbs: Double = 0.15
    @Volatile var selfTestMinPeakF: Double = 0.9

    /** Recurrent model families: LSTM is not an HTP op, so these never try QNN. */
    val cpuOnlyModelPrefixes: List<String> = listOf("scnet", "vocals_umx", "umx", "htdemucs", "stem_")

    /** Families that run long batches (sustained_high_performance instead of burst). */
    val sustainedModelPrefixes: List<String> = listOf("scnet", "vocals_umx", "umx", "htdemucs", "stem_")
}
