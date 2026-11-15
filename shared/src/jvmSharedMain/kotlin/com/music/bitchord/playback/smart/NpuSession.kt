package com.music.bitchord.playback.smart

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File

/**
 * NPU-aware ONNX Runtime session opening (vc70 NPU). Orchard opens its QNN
 * addon with the HTP backend in burst mode plus context caching (qnn_runner
 * open: backend_path, htp performance burst, disable cpu fallback). BitChord
 * mirrors that option set inside the bundled ONNX Runtime 1.31.0 (npu-addon
 * source-built QNN runtime), then falls
 * back to the CPU execution provider when the QNN session fails to open, so
 * the NPU stays an optimization and never a requirement. This runs once per
 * model on the analysis thread, never on the audio thread.
 */
object NpuSession {

    /**
     * Opens the model: QNN when the config and vendor allow it, CPU session
     * otherwise. When the QNN open throws, falls back to CPU unless the
     * config forbids fallback (Orchard hard-fails; BitChord default keeps
     * playing). Throws only when the CPU open also fails, which callers
     * treat as the model being unavailable.
     */
    fun open(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        vendor: NpuSupport.Vendor,
        backendPath: String?,
        cacheDir: File?,
    ): OrtSession {
        // Startup guard: any QNN failure degrades to CPU; CPU fallback
        // itself throws and callers (BeatTracker/VocalTracker session())
        // convert that to null via runCatching, so analysis stays off.
        if (NpuSupport.shouldAttemptQnn(config, vendor)) {
            try {
                return openQnn(modelPath, threads, config, backendPath, cacheDir)
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

    // Widened from private for the NPU self-test (NpuSelfTest, app module):
    // the probe must attempt QNN open directly so its FAILED log is
    // truthful instead of being masked by the CPU fallback in open().
    // Not planner/DSP logic.
    fun openQnn(
        modelPath: String,
        threads: Int,
        config: NpuSupport.NpuConfig,
        backendPath: String?,
        cacheDir: File?,
    ): OrtSession {
        // Dormant by default: QNN_ENABLED=false makes this unreachable in
        // production (open() gates on shouldAttemptQnn, which includes
        // QNN_ENABLED, and NpuSelfTest.schedule returns early). The check
        // keeps any future direct caller from loading QNN libs.
        check(NpuSupport.QNN_ENABLED) { "QNN disabled (NpuSupport.QNN_ENABLED=false)" }
        val contextPath = cacheDir?.let { File(it, "ctx.onnx").absolutePath }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setCPUArenaAllocator(false)
            setMemoryPatternOptimization(false)
            addQnn(NpuSupport.qnnProviderOptions(backendPath))
            for ((key, value) in NpuSupport.qnnSessionConfig(config, contextPath)) {
                addConfigEntry(key, value)
            }
        }
        return OrtEnvironment.getEnvironment().createSession(modelPath, options)
    }
}
