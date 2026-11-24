package com.music.bitchord.playback.smart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * NPU-aware ONNX Runtime session opening (vc70 NPU). Orchard opens its QNN
 * addon with the HTP backend in burst mode plus context caching (qnn_runner
 * open: backend_path, htp performance burst, disable cpu fallback). BitChord
 * mirrors that option set inside the bundled ONNX Runtime 1.31.0 (npu-addon
 * source-built QNN runtime), then falls back to the CPU execution provider
 * when the QNN session fails to open, so the NPU stays an optimization and
 * never a requirement. This runs once per model on the analysis thread, never
 * on the audio thread.
 */
object NpuSession {

    fun open(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        vendor: NpuSupport.Vendor,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
    ): OrtSession {
        // Startup guard: any QNN failure degrades to CPU; CPU fallback
        // itself throws and callers (BeatTracker/VocalTracker session())
        // convert that to null via runCatching, so analysis stays off.
        val provider = NpuSupport.resolveProvider(config, vendor)
        if (NpuSupport.shouldAttempt(provider, config, vendor)) {
            try {
                return openQnn(modelPath, threads, config, backendPath, cacheDir, socModel, provider)
            } catch (failure: Throwable) {
                if (config.disableCpuFallback) throw failure
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

    /**
     * Opens the plain CPU execution provider session. Immediate CPU ORT wins:
     * the arena allocator and the memory pattern optimisation are on, and the
     * intra-op thread pool respects [threads] (clamped to 1..4) with one
     * inter-op thread.
     */
    fun openCpu(modelPath: String, threads: Int): OrtSession {
        val options = OrtSession.SessionOptions().apply {
            // CPU execution provider. NNAPI is not used.
            setIntraOpNumThreads(threads.coerceIn(1, 4))
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            // Immediate CPU ORT wins: arena allocator retained blocks are tens of
            // megabytes of native heap for the life of the session, which a backgrounded
            // music player cannot justify; allocate per run instead.
            setCPUArenaAllocator(true)
            setMemoryPatternOptimization(true)
        }
        return OrtEnvironment.getEnvironment().createSession(modelPath, options)
    }

    // Widened from private for the NPU self-test (NpuSelfTest, app module):
    // the probe must attempt QNN open directly so its FAILED log is
    // truthful instead of being masked by the CPU fallback in open(). Not
    // planner/DSP logic.
    fun openQnn(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
        provider: NpuSupport.InferenceProvider = NpuSupport.InferenceProvider.QNN_HTP_QDQ,
    ): OrtSession {
        // Dormant by default: QNN_ENABLED=false makes this unreachable in
        // production (open() gates on the resolved provider, which is
        // CPU_DEFAULT while QNN_ENABLED is false, and NpuSelfTest.schedule
        // returns early). The check keeps any future direct caller from
        // loading QNN libs.
        check(NpuSupport.QNN_ENABLED) { "QNN disabled (NpuSupport.QNN_ENABLED=false)" }
        val contextPath = cacheDir?.let { File(it, "ctx.onnx").absolutePath }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            addQnn(
                NpuSupport.qnnProviderOptions(
                    backendPath = backendPath,
                    socModel = socModel,
                    backendType = if (provider == NpuSupport.InferenceProvider.QNN_GPU_FP16) "gpu" else null,
                ),
            )
            for ((key, value) in NpuSupport.qnnSessionConfig(config, contextPath)) {
                addConfigEntry(key, value)
            }
        }
        return OrtEnvironment.getEnvironment().createSession(modelPath, options)
    }

    /**
     * QNN diagnostic session (test builds only): same open as [openQnn] but with
     * the CPU execution provider fallback disabled (disable_cpu_ep_fallback = "1")
     * and ORT profiling enabled, so a full graph partition can be proven instead of
     * accepting a silent CPU fallback. Reached only while explicitly enabled in a
     * test build; while QNN is off the diagnostic is unreachable and behaviour is
     * unchanged.
     */
    fun openQnnDiagnostic(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        backendPath: String?,
        cacheDir: File?,
        socModel: String? = null,
        provider: NpuSupport.InferenceProvider = NpuSupport.InferenceProvider.QNN_HTP_QDQ,
    ): OrtSession {
        check(NpuSupport.QNN_ENABLED) { "QNN disabled (NpuSupport.QNN_ENABLED=false)" }
        val contextPath = cacheDir?.let { File(it, "ctx.onnx").absolutePath }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setInterOpNumThreads(1)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            addQnn(
                NpuSupport.qnnProviderOptions(
                    backendPath = backendPath,
                    socModel = socModel,
                    backendType = if (provider == NpuSupport.InferenceProvider.QNN_GPU_FP16) "gpu" else null,
                ),
            )
            for ((key, value) in NpuSupport.qnnSessionConfig(config, contextPath)) {
                addConfigEntry(key, value)
            }
            // Diagnostic: prove full graph partitioning, never accept a silent CPU fallback.
            addConfigEntry("session.disable_cpu_ep_fallback", "1")
            enableProfiling("BitChordNPU")
        }
        return OrtEnvironment.getEnvironment().createSession(modelPath, options)
    }
}
