package com.music.bitchord.playback.smart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * AUTOMIX-V2(A) QDQ-only QNN session route (onnxruntime-android-qnn). New file beside the
 * legacy [NpuSession] (left untouched for a clean 3-way merge onto vc91): every CPU open
 * still goes through `NpuSession.open`, whose QNN path stays dormant behind the
 * compile-time `NpuSupport.QNN_ENABLED = false`.
 *
 * Per model: QNN is attempted only when the runtime policy allows it
 * ([NpuRuntime.shouldAttemptQnn]), the caller feeds fixed-shape chunks
 * (`acceptsFixedShape`; HTP graphs are static), the model family is not recurrent, and a
 * statically quantized sibling exists (`<base>_qdq_ctx.onnx` precompiled, or
 * `<base>_qdq.onnx` compiled on device and cached as an EP context keyed by model SHA-256
 * + ORT version + SoC + options). Dynamic-int8 models always open on CPU. Any QNN failure
 * counts toward the policy's auto-disable and falls back to the CPU session on the
 * original model, so the NPU stays an optimization and never a requirement.
 *
 * Runs on analysis worker threads only (session creation can take seconds the first time
 * the HTP graph is finalized), never on the audio thread.
 */
/** Contract name (CONTRACTS.md): which EP each model actually opened on. */
fun NpuSession.describe(): String = NpuQnnSession.describe()

/** Contract name: which EP [session] actually runs on. */
fun NpuSession.describe(session: OrtSession): String = NpuQnnSession.describe(session)

object NpuQnnSession {

    /** Finds the QNN-capable file named [candidateName] for [modelFile] (null = none shipped). */
    fun interface QdqResolver {
        fun resolve(modelFile: File, candidateName: String): File?
    }

    /** Default: a sibling file next to the model. The app installs an asset-unpacking resolver. */
    @Volatile var qdqResolver: QdqResolver = QdqResolver { model, name ->
        model.parentFile?.let { File(it, name) }?.takeIf { it.isFile && it.length() > 0 }
    }

    private class Opened(val session: OrtSession, val description: String)

    private val sessionEp: MutableMap<OrtSession, String> = Collections.synchronizedMap(WeakHashMap())
    private val qnnSessions: MutableMap<OrtSession, Boolean> = Collections.synchronizedMap(WeakHashMap())
    private val lastByModel = ConcurrentHashMap<String, String>()
    private val shaCache = ConcurrentHashMap<String, String>()

    /**
     * Opens the model: QNN when everything above allows it, CPU session otherwise. Throws
     * only when the CPU open also fails (or QNN failed and the config forbids fallback),
     * which callers treat as the model being unavailable.
     *
     * [acceptsFixedShape] defaults to false so existing callers (VocalTracker, the stem
     * engines) keep their exact vc80 CPU behaviour; BeatTracker passes true once it aligns
     * every chunk to [fixedInputFrames] (orchestrator hook in NOTES).
     */
    fun open(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        vendor: NpuSupport.Vendor,
        backendPath: String?,
        cacheDir: File?,
        acceptsFixedShape: Boolean = false,
    ): OrtSession {
        val model = File(modelPath)
        val name = model.name
        var cpuReason = cpuReason(config, vendor, acceptsFixedShape, name)
        if (cpuReason == null) {
            val candidate = NpuRuntime.qnnCandidateName(name)
            val target = candidate?.let { resolveQnnFile(model, it) }
            val backend = NpuRuntime.backendPath() ?: backendPath
            if (backend == null) {
                cpuReason = "bundled libQnnHtp.so unavailable"
            } else if (target == null) {
                cpuReason = "no QDQ model for " + name
            } else {
                try {
                    val opened = openQnnDetailed(target, threads, NpuRuntime.QnnConfig(), backend, cacheDir)
                    remember(opened.session, name, opened.description, qnn = true)
                    return opened.session
                } catch (failure: Throwable) {
                    NpuRuntime.policy()?.recordFailure("open " + target.name + ": " + shortMessage(failure))
                    if (config.disableCpuFallback) throw failure
                    AnalysisLog.warn("NPU QNN open failed for " + target.name + "; falling back to CPU", failure)
                    cpuReason = "QNN open failed: " + shortMessage(failure)
                }
            }
        }
        // Legacy opener: CPU (its own QNN path is dormant behind NpuSupport.QNN_ENABLED=false).
        // It logs and rethrows when the CPU open fails too.
        val session = NpuSession.open(modelPath, threads, config, vendor, backendPath, cacheDir)
        remember(session, name, "CPU (" + cpuReason + ")", qnn = false)
        return session
    }

    /** Plain CPU session for the self-test reference (same options as the vc80 opener). */
    fun openCpu(modelPath: String, threads: Int): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            // CPU execution provider. NNAPI is not used.
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // ORT arena allocator retained blocks are tens of megabytes of
            // native heap for the life of the session, which a backgrounded
            // music player cannot justify; allocate per run instead.
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
        }
        return OrtEnvironment.getEnvironment().createSession(modelPath, options)
    }

    /**
     * Opens [modelPath] (a QDQ or EP-context model) on QNN directly, without CPU-session
     * fallback, so the self-test reports the truth. Gated on [NpuSupport.runtimeEnabled].
     */
    fun openQnn(
        modelPath: String,
        threads: Int,
        config: NpuRuntime.QnnConfig,
        backendPath: String?,
        cacheDir: File?,
    ): OrtSession {
        check(NpuRuntime.runtimeEnabled()) { "QNN disabled (NpuRuntime.runtimeEnabled()=false)" }
        val file = File(modelPath)
        val opened = openQnnDetailed(file, threads, config, backendPath, cacheDir)
        remember(opened.session, file.name, opened.description, qnn = true)
        return opened.session
    }

    /** Which execution provider each model opened on most recently, one entry per model. */
    fun describe(): String {
        if (lastByModel.isEmpty()) return "no sessions opened"
        return lastByModel.entries.sortedBy { it.key }.joinToString("; ") { it.key + "=" + it.value }
    }

    /** Which execution provider [session] actually runs on. */
    fun describe(session: OrtSession): String = sessionEp[session] ?: "unknown (not opened through NpuSession)"

    fun isQnn(session: OrtSession): Boolean = qnnSessions[session] == true

    /**
     * False when [session] is a QNN session but the NPU has since been switched off or
     * auto-disabled: the caller should close it and reopen (which lands on CPU).
     */
    fun stillAllowed(session: OrtSession): Boolean = !isQnn(session) || NpuRuntime.runtimeEnabled()

    /**
     * Report an inference failure. Returns true when [session] is a QNN session: the
     * failure is counted (3 in a row auto-disable the NPU) and the caller must close the
     * session and reopen it, which recovers from QNN ENGINE_ERROR after a DSP restart.
     */
    fun onRunFailure(session: OrtSession, failure: Throwable): Boolean {
        if (!isQnn(session)) return false
        val tripped = NpuRuntime.policy()?.recordFailure("run: " + shortMessage(failure)) ?: false
        AnalysisLog.warn("NPU QNN run failed (" + (if (tripped) "NPU now auto-disabled" else "session will be recreated") + ")", failure)
        return true
    }

    /** Report a successful inference on [session] (ends a QNN failure streak). */
    fun onRunSuccess(session: OrtSession) {
        if (isQnn(session)) NpuRuntime.policy()?.recordSuccess()
    }

    /** The fixed time dimension (input dim 1) of the session's first input, or null when dynamic. */
    fun fixedInputFrames(session: OrtSession): Int? = runCatching {
        val info = session.inputInfo.values.firstOrNull()?.info as? TensorInfo ?: return@runCatching null
        val shape = info.shape
        if (shape.size < 2) null else shape[1].takeIf { it > 0 }?.toInt()
    }.getOrNull()

    // ---- internals --------------------------------------------------------------------

    private fun cpuReason(
        config: NpuSupport.NpuConfig,
        vendor: NpuSupport.Vendor,
        acceptsFixedShape: Boolean,
        name: String,
    ): String? {
        if (!AutomixV2Npu.enabled) return "AutomixV2Npu.enabled=false"
        if (vendor != NpuSupport.Vendor.QUALCOMM) return "vendor=" + vendor
        if (!config.enabled) return "config disabled"
        if (!NpuRuntime.runtimeEnabled()) {
            return "npu off: " + (NpuRuntime.policy()?.summary() ?: "no policy installed")
        }
        if (!NpuRuntime.productionQnnAllowed()) return "awaiting self-test PASS"
        if (NpuRuntime.isCpuOnlyModel(name)) return "recurrent model family, CPU only"
        if (!acceptsFixedShape) return "caller feeds dynamic shapes"
        return null
    }

    private fun resolveQnnFile(model: File, qdqName: String): File? {
        val resolver = qdqResolver
        val contextName = NpuRuntime.precompiledContextName(qdqName)
        if (contextName != null) {
            runCatching { resolver.resolve(model, contextName) }.getOrNull()?.let { return it }
        }
        return runCatching { resolver.resolve(model, qdqName) }.getOrNull()
    }

    private fun openQnnDetailed(
        file: File,
        threads: Int,
        config: NpuRuntime.QnnConfig,
        backendPath: String?,
        cacheDir: File?,
    ): Opened {
        val performance = NpuRuntime.performanceModeFor(file.name, config)
        val target = NpuRuntime.htpTargetFor(NpuRuntime.socModel())
        // A pinned target that the device rejects (QNN_DEVICE_ERROR_INVALID_CONFIG) gets one
        // retry with QNN's own detection before the whole attempt counts as a failure.
        val attempts: List<NpuRuntime.HtpTarget?> = if (target != null) listOf(target, null) else listOf(null)
        var last: Throwable? = null
        for (attempt in attempts) {
            val providerOptions = NpuRuntime.qnnProviderOptions(backendPath, performance, attempt, config)
            try {
                return openWithCache(file, threads, config, providerOptions, cacheDir, attempt, performance)
            } catch (failure: Throwable) {
                last = failure
                if (attempt != null) {
                    AnalysisLog.warn("NPU QNN open with htp_arch=" + attempt.arch + " failed; retrying with auto-detect", failure)
                }
            }
        }
        throw last ?: IllegalStateException("QNN open failed")
    }

    private fun openWithCache(
        file: File,
        threads: Int,
        config: NpuRuntime.QnnConfig,
        providerOptions: Map<String, String>,
        cacheDir: File?,
        target: NpuRuntime.HtpTarget?,
        performance: String,
    ): Opened {
        val label = "QNN(HTP " + (target?.let { "v" + it.arch + (it.socModel?.let { soc -> "/soc" + soc } ?: "") } ?: "auto") +
            ", perf=" + performance + ", " + partitionLabel(config)
        if (NpuRuntime.isContextModelName(file.name)) {
            val session = create(file.absolutePath, threads, providerOptions, NpuRuntime.qnnSessionConfig(config, null))
            return Opened(session, label + ", precompiled " + file.name + ")")
        }
        val contextFile = if (config.contextCaching && cacheDir != null) contextFileFor(file, cacheDir, providerOptions) else null
        if (contextFile != null && contextFile.isFile && contextFile.length() > 0) {
            try {
                val session = create(contextFile.absolutePath, threads, providerOptions, NpuRuntime.qnnSessionConfig(config, null))
                return Opened(session, label + ", cached " + contextFile.name + ")")
            } catch (failure: Throwable) {
                AnalysisLog.warn("NPU stale QNN context " + contextFile.name + "; recompiling", failure)
                runCatching { contextFile.delete() }
            }
        }
        if (contextFile != null && cacheDir != null) {
            pruneStale(cacheDir, file.name, contextFile)
            // ORT refuses to overwrite an existing EP-context file.
            runCatching { contextFile.delete() }
        }
        val session = create(file.absolutePath, threads, providerOptions, NpuRuntime.qnnSessionConfig(config, contextFile?.absolutePath))
        return Opened(session, label + ", compiled " + file.name + (if (contextFile != null) " -> cache" else "") + ")")
    }

    private fun partitionLabel(config: NpuRuntime.QnnConfig): String = when {
        config.strictPartition -> "all nodes on QNN (cpu fallback disabled)"
        NpuRuntime.policy()?.selfTestPassed() == true -> "full offload verified by self-test"
        else -> "partition unverified"
    }

    private fun create(
        path: String,
        threads: Int,
        providerOptions: Map<String, String>,
        entries: Map<String, String>,
    ): OrtSession {
        // Not closed: the options must outlive every session created from them.
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(threads)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        options.setCPUArenaAllocator(false)
        options.setMemoryPatternOptimization(false)
        for ((key, value) in entries) options.addConfigEntry(key, value)
        options.addQnn(providerOptions)
        return OrtEnvironment.getEnvironment().createSession(path, options)
    }

    private fun contextFileFor(model: File, cacheDir: File, providerOptions: Map<String, String>): File? = runCatching {
        if (!cacheDir.isDirectory && !cacheDir.mkdirs()) return@runCatching null
        val name = NpuRuntime.contextCacheFileName(
            modelFileName = model.name,
            modelSha256 = sha256(model),
            ortVersion = ortVersion(),
            socModel = NpuRuntime.socModel(),
            optionsKey = NpuRuntime.optionsFingerprint(providerOptions),
        )
        File(cacheDir, name)
    }.getOrNull()

    private fun pruneStale(cacheDir: File, modelFileName: String, keep: File) {
        runCatching {
            val prefix = NpuRuntime.contextCachePrefix(modelFileName)
            cacheDir.listFiles()?.forEach { candidate ->
                val stale = (candidate.name.startsWith(prefix) && candidate.name != keep.name) ||
                    candidate.name == "ctx.onnx" // the vc69/vc70 fixed name, never reusable
                if (stale) candidate.delete()
            }
        }
    }

    /**
     * ONNX Runtime native version ("unknown" on runtimes without getVersion()). Reflective so
     * the shared module compiles against any ORT Java API and the TV app's older AAR.
     */
    fun ortVersion(): String = runCatching {
        OrtEnvironment::class.java.getMethod("getVersion").invoke(OrtEnvironment.getEnvironment()) as? String
    }.getOrNull() ?: "unknown"

    private fun sha256(file: File): String {
        val key = file.absolutePath + "|" + file.length() + "|" + file.lastModified()
        shaCache[key]?.let { return it }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { byte -> ((byte.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
        shaCache[key] = hex
        return hex
    }

    private fun remember(session: OrtSession, modelName: String, description: String, qnn: Boolean) {
        sessionEp[session] = description
        if (qnn) qnnSessions[session] = true
        lastByModel[modelName] = description
        AnalysisLog.warn("BitChordNPU session " + modelName + " ep=" + description)
    }

    private fun shortMessage(failure: Throwable): String =
        (failure.message ?: failure.javaClass.simpleName).replace('\n', ' ').take(160)
}
