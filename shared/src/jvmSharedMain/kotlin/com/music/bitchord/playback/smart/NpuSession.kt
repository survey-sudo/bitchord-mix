package com.music.bitchord.playback.smart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtLoggingLevel
import ai.onnxruntime.OrtSession
import java.io.File
import java.security.MessageDigest

/** Model bytes (including I/O contract), ORT/QAIRT binaries, arch/options and firmware key EPContext. */
internal fun qnnContextFingerprint(
    model: File,
    backend: File,
    config: NpuSupport.NpuConfig,
    runtimeVersion: String,
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun text(value: String) { digest.update((value + "\n").toByteArray(Charsets.UTF_8)) }
    text("bitchord-epcontext-v1")
    text("ort=$runtimeVersion;optimization=ALL_OPT;qairt=${NpuSupport.QAIRT_VERSION}")
    text("device=${config.deviceFingerprint}")
    // backend_path is deployment-specific; binary content below identifies it instead.
    NpuSupport.qnnProviderOptions(null, config).toSortedMap().forEach { (key, value) -> text("$key=$value") }
    val dir = backend.parentFile
    val libraries = listOf(
        backend.name, "libonnxruntime.so", "libQnnSystem.so", "libQnnHtpPrepare.so",
        "libQnnHtpV${config.htpArch}Stub.so", "libQnnHtpV${config.htpArch}Skel.so",
    ).distinct().map { File(dir, it) }
    for (file in listOf(model) + libraries) {
        text("file=${file.name};bytes=${if (file.isFile) file.length() else -1}")
        if (file.isFile) file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/**
 * NPU-aware ONNX Runtime session opening (vc70 NPU). Orchard opens its QNN
 * addon with the HTP backend in burst mode plus context caching (qnn_runner
 * open: backend_path, htp performance burst, disable cpu fallback). BitChord
 * prepares that option set for a future validated ONNX Runtime 1.31.0 QNN
 * build (the full Maven CPU runtime still ships while dormant), then falls
 * back to the CPU execution provider when the QNN session fails to open, so
 * the NPU stays an optimization and never a requirement. This runs once per
 * model on the analysis thread, never on the audio thread.
 */
object NpuSession {

    /**
     * Opens the model: QNN when the config and vendor allow it, CPU session
     * otherwise. Playback always allows CPU fallback. Only openQnnProbe
     * disables it, so a self-test cannot silently pass on CPU. Throws only
     * when the CPU open also fails, which callers
     * treat as the model being unavailable.
     */
    fun open(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        vendor: NpuSupport.Vendor,
        backendPath: String?,
        cacheDir: File?,
        preflightPassed: Boolean = false,
    ): OrtSession {
        // Startup guard: any QNN failure degrades to CPU; CPU fallback
        // itself throws and callers (BeatTracker/VocalTracker session())
        // convert that to null via runCatching, so analysis stays off.
        if (NpuSupport.shouldAttemptQnn(config, vendor)) {
            try {
                return openQnn(modelPath, threads, config.copy(disableCpuFallback = false), backendPath, cacheDir, preflightPassed)
            } catch (failure: Throwable) {
                AnalysisLog.warn("NPU QNN open failed; falling back to CPU", failure)
            }
        }
        try {
            return openCpu(modelPath, threads)
        } catch (failure: Throwable) {
            AnalysisLog.warn("NPU CPU open failed; model unavailable", failure)
            throw failure
        }
    }

    private fun openCpu(modelPath: String, threads: Int): OrtSession {
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

    /** Direct probe adapter: no outer CPU retry, no cached context, verbose node-assignment logs. */
    fun openQnnProbe(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        backendPath: String?,
        cacheDir: File?,
        preflightPassed: Boolean,
    ): OrtSession = openQnn(
        modelPath, threads, NpuSupport.probeConfig(config), backendPath, cacheDir,
        preflightPassed, logNodeAssignments = true,
    )

    fun openQnn(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        backendPath: String?,
        cacheDir: File?,
        preflightPassed: Boolean = false,
        logNodeAssignments: Boolean = false,
    ): OrtSession {
        // Dormant by default: QNN_ENABLED=false makes this unreachable in
        // production (open() gates on shouldAttemptQnn, which includes
        // QNN_ENABLED, and NpuSelfTest.schedule returns early). The check
        // keeps any future direct caller from loading QNN libs.
        check(NpuSupport.QNN_ENABLED) { "QNN disabled (NpuSupport.QNN_ENABLED=false)" }
        check(NpuSupport.htpPreflightReady(backendPath?.let { File(it).isFile } == true, preflightPassed)) {
            "QNN HTP preflight failed (backend or linker-visible libcdsprpc missing)"
        }
        val environment = OrtEnvironment.getEnvironment()
        val contextPath = if (config.contextCaching) cacheDir?.let {
            val fingerprint = qnnContextFingerprint(File(modelPath), File(requireNotNull(backendPath)), config, environment.version)
            val dir = File(it, "beat-$fingerprint")
            check(dir.isDirectory || dir.mkdirs()) { "Cannot create fingerprinted EPContext directory" }
            File(dir, "ctx.onnx").absolutePath
        } else null
        return OrtSession.SessionOptions().use { options ->
            options.apply {
                setIntraOpNumThreads(threads)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setCPUArenaAllocator(false)
                setMemoryPatternOptimization(false)
                if (logNodeAssignments) {
                    setLoggerId("BitChordNPU-probe")
                    setSessionLogLevel(OrtLoggingLevel.ORT_LOGGING_LEVEL_VERBOSE)
                    setSessionLogVerbosityLevel(1)
                }
                addQnn(NpuSupport.qnnProviderOptions(backendPath, config))
                for ((key, value) in NpuSupport.qnnSessionConfig(config, contextPath)) {
                    addConfigEntry(key, value)
                }
            }
            environment.createSession(modelPath, options)
        }
    }
}
